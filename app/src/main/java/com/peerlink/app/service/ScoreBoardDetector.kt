package com.peerlink.app.service

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * F35 structure-first eFootball score-screen reader.
 *
 * DESIGN CONTRACT (what makes this device-independent — the F29..F33 readers
 * trusted screen fractions measured on one phone; this reader trusts nothing
 * about WHERE an element sits, only what it LOOKS LIKE):
 *
 *  1. NO FIXED SCREEN WINDOWS. The score boxes, the label strip, the table
 *     rows, the value tokens and the menu glyphs are FOUND by structural
 *     search over the full frame; every sub-window derives from what was
 *     found (anchor-relative geometry).
 *  2. ADAPTIVE COLOUR. The UI-yellow hue is estimated per frame from the
 *     frame's own strong-chroma warm hue histogram (chroma-weighted mode,
 *     5-degree bins). F35: the mode pools only BLUE-FREE pixels — yellow is
 *     spectrally the absence of blue (b/g <= 0.06 even under JPEG bleed;
 *     floodlit grass keeps b/g >= 0.24), so a vivid pitch close-up can no
 *     longer out-vote the scoreboard in the hue histogram.
 *  3. INK = LUMA-DOMINANT LOCAL CONTRAST. F35: the ink threshold is Otsu's
 *     split of the box's own bimodal v histogram (parameter-free), with a
 *     certainly-ink deep zone (luma alone) and an ambiguous zone where a
 *     strong-yellow chroma signature still rejects halo. The F34 single
 *     hue-family gate broke under 4:2:0 chroma subsampling, which bleeds the
 *     surrounding yellow INTO a thin navy stroke and even collapsed the
 *     ink-hue reference itself; luma is the invariant across every panel
 *     and compression transform (ink v 0.14-0.54 vs background 0.80-1.00).
 *  4. AUTHORED-SCALE BOUNDS ONLY. Size gates bound what the game UI can
 *     render relative to frame height (the UI scales with display height on
 *     every device); gates are 2-6x wider than any measured device.
 *  5. FAIL-SAFE + CROSS-VALIDATION. Ambiguity refuses the read; a refused
 *     read is never a wrong read. Table values are read twice (global mask
 *     + local Otsu window): agreement emits, disagreement refuses, one
 *     reading emits it. Callers fall back to the (slow) ML Kit chain.
 *
 * Screen presentations covered (all verified against real captures):
 *  STATS_BOARD - full/halftime statistics board, score in the top banner.
 *  WALKING     - end-of-half pitch walk-out, score in the bottom banner.
 *  MENU        - result menu after tapping Next, large score under the clock.
 */
internal object ScoreBoardDetector {

    enum class ScreenType { STATS_BOARD, WALKING, MENU, OTHER }

    /** 13 fixed eFootball statistic rows, in board order. */
    val STAT_NAMES = listOf(
        "Possession", "TotalShots", "ShotsOnTarget", "Fouls", "Offsides",
        "CornerKicks", "FreeKicks", "Passes", "SuccessfulPasses", "Crosses",
        "Interceptions", "Tackles", "Saves",
    )

    class StatRow(val name: String, val home: Int, val away: Int)

    class Stats internal constructor(internal val rows: List<StatRow>) {
        val size: Int get() = rows.size
        operator fun get(name: String): Pair<Int, Int>? =
            rows.firstOrNull { it.name == name }?.let { it.home to it.away }
        fun toJsonArray(): org.json.JSONArray = org.json.JSONArray().apply {
            rows.forEach { row ->
                put(org.json.JSONObject().apply {
                    put("name", row.name)
                    put("home", row.home)
                    put("away", row.away)
                })
            }
        }

        companion object {
            fun fromJson(array: org.json.JSONArray): Stats {
                val rows = ArrayList<StatRow>(array.length())
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    val home = o.optInt("home", -1)
                    val away = o.optInt("away", -1)
                    if (name.isNotEmpty() && home in 0..100 && away in 0..100) {
                        rows.add(StatRow(name, home, away))
                    }
                }
                return Stats(rows)
            }
        }
    }

    enum class Finality { FULL_TIME, HALF_TIME, CLOCK, UNKNOWN }

    class Detection(
        val type: ScreenType,
        val home: Int?,
        val away: Int?,
        val finality: Finality,
        val stats: Stats?,
        val gateInfo: String,
    ) {
        val score: Pair<Int, Int>? get() = if (home == null || away == null) null else home to away
        val finalScreen: Boolean get() = finality == Finality.FULL_TIME
    }

    /**
     * Kept for call-site compatibility. The structure-first reader derives
     * everything from the frame itself, so the geometry only decides whether
     * a statistics table can exist at all (legacy band composites crop it).
     */
    sealed class Geometry {
        object Full : Geometry()
        class Composite(val topHeight: Int, val gap: Int, val refHeight: Int) : Geometry()
    }

    // Tunable primitives (grid-searched over native-exact + robustness-matrix
    // objective: wrong reads = 0 hard constraint, then max clean checks).
    // INK_V_CEIL is legacy since F35 (Otsu replaced the fixed ceiling); it is
    // kept only for regression continuity and is no longer read.
    internal const val INK_V_CEIL = 0.52f
    internal const val INK_LEAN = 16

    // F35 constants. Each sits in a MEASURED gap and was basin-checked over
    // +/-30% on the full robustness matrix (0 in-envelope wrong reads
    // throughout); see the comment at each use site for the measurement.
    internal const val BLUE_FREE_BG_RATIO = 0.15f   // UI yellow b/g <= 0.06, grass >= 0.24
    internal const val MENU_SOLID_FILL = 0.72f      // box blobs 0.86-0.92, bold digits 0.54-0.60
    internal const val STATS_PIECE_FLOOR = 9        // px; digit identity floor (confusion onset ~8px)
    internal const val TDEEP_FRAC = 0.25f           // deep-ink floor inside [p02, tOtsu]
    internal const val UNIMODAL_FALLBACK = 0.45f    // span when the box histogram has no valley
    internal const val YSTRONG_CHROMA = 0.60f       // halo band: chroma vs box yellow
    internal const val YSTRONG_HUE = 45f            // halo band: hue window around yellow
    internal const val BLOB_TALL = 0.55f            // fused-banner: tall-column run threshold
    internal const val BLOB_BRIDGE = 1.5f           // fused-banner: wall-bridge factor
    internal const val FH_LOWER = 0.70f             // F/H: bottom-quarter window (F crossbar ~0.45-0.57h)
    internal const val FH_HGATE = 0.80f             // F/H: H top gate (H stems 0.5-0.67, F bar ~1.0)

    // ----------------------------------------------------------------------
    // Colour masks with per-frame adaptive yellow hue.
    // ----------------------------------------------------------------------
    internal class Masks(val w: Int, val h: Int) {
        val strict = BooleanArray(w * h)   // solid yellow (bands/boxes)
        val loose = BooleanArray(w * h)    // glyph ink on navy/dark
        val v = FloatArray(w * h)
        val chroma = FloatArray(w * h)
        val hue = FloatArray(w * h)
        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        var hueY = -1f
        var strictFrac = 0f
    }

    internal fun buildMasks(pixels: IntArray, w: Int, h: Int): Masks {
        val m = Masks(w, h)
        val hue = FloatArray(w * h)
        val sat = FloatArray(w * h)
        var strictCount = 0
        val total = w * h
        for (i in 0 until total) {
            val c = pixels[i]
            val rr = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val bb = c and 0xFF
            m.r[i] = rr; m.g[i] = gg; m.b[i] = bb
            val mx = max(rr, max(gg, bb))
            val mn = min(rr, min(gg, bb))
            val d = mx - mn
            m.chroma[i] = d.toFloat()
            m.v[i] = mx / 255f
            var hu = 0f
            if (d > 0) {
                val rm = mx == rr; val gm = mx == gg && !rm
                hu = when {
                    rm -> 60f * (gg - bb) / d
                    gm -> 60f * (bb - rr) / d + 120f
                    else -> 60f * (rr - gg) / d + 240f
                }
                if (hu < 0f) hu += 360f
            }
            hue[i] = hu
            m.hue[i] = hu
            sat[i] = if (mx > 0) d.toFloat() / mx else 0f
        }
        // --- adaptive yellow hue: chroma-weighted mode over 5-degree bins in
        // [20,100]; then the median hue within +/-[10,15] of the peak bin.
        // F35 blue-free refinement: UI yellow carries no blue channel
        // (b/g <= 0.06 even under JPEG chroma bleed) while the yellow-green
        // grass spike on vivid panels keeps b/g >= 0.24; restricting the
        // mode to blue-free pixels makes the estimate area-independent.
        val nb = 17
        val bins = FloatArray(nb)
        var poolSize = 0
        val poolIdx = IntArray(total)
        for (i in 0 until total) {
            if (m.chroma[i] >= 110f && m.v[i] >= 0.35f && hue[i] >= 20f && hue[i] <= 100f) {
                val bin = ((hue[i] - 20f) / 5f).toInt().coerceIn(0, nb - 1)
                bins[bin] += m.chroma[i]
                poolIdx[poolSize++] = i
            }
        }
        if (poolSize >= 150) {
            // blue-free subset; falls back to the full pool when absent
            var bf = 0
            val bfIdx = IntArray(poolSize)
            for (k in 0 until poolSize) {
                val i = poolIdx[k]
                if (m.b[i] <= BLUE_FREE_BG_RATIO * max(1, m.g[i])) bfIdx[bf++] = i
            }
            val use = if (bf >= 150) { poolSize = bf; bfIdx } else {
                // re-expand poolIdx to full length for the loop below
                val all = IntArray(poolSize); for (k in 0 until poolSize) all[k] = poolIdx[k]; all
            }
            if (poolSize >= 150) {
                // recomputed chroma-weighted mode over the chosen subset
                val bins2 = FloatArray(nb)
                val poolHue = FloatArray(poolSize)
                val poolChroma = FloatArray(poolSize)
                var pn = 0
                for (k in 0 until poolSize) {
                    val i = use[k]
                    val bin = ((hue[i] - 20f) / 5f).toInt().coerceIn(0, nb - 1)
                    bins2[bin] += m.chroma[i]
                    poolHue[pn] = hue[i]; poolChroma[pn] = m.chroma[i]; pn++
                }
                var peak = 0
                for (k in 1 until nb) if (bins2[k] > bins2[peak]) peak = k
                val lo = 20f + peak * 5f - 10f
                val hi = 20f + peak * 5f + 15f
                val sel = FloatArray(pn)
                var sn = 0
                for (k in 0 until pn) {
                    if (poolHue[k] >= lo && poolHue[k] <= hi) sel[sn++] = poolHue[k]
                }
                if (sn >= 50) {
                    java.util.Arrays.sort(sel, 0, sn)
                    val hy = sel[sn / 2]
                    if (hy in 30f..90f) m.hueY = hy
                }
            }
        }
        if (m.hueY > 0f) {
            for (i in 0 until total) {
                var dh = abs(((hue[i] - m.hueY + 180f) % 360f) - 180f)
                if (dh <= 24f && m.chroma[i] >= 95f && m.v[i] >= 0.42f) {
                    m.strict[i] = true; strictCount++
                }
                if (dh <= 32f && m.chroma[i] >= 60f && m.v[i] >= 0.30f) {
                    m.loose[i] = true
                }
            }
        }
        m.strictFrac = strictCount.toFloat() / total
        return m
    }

    // ----------------------------------------------------------------------
    // Entry point.
    // ----------------------------------------------------------------------
    fun analyze(frame: Bitmap, geometry: Geometry): Detection? {
        val w = frame.width
        val h = frame.height
        if (w < 320 || h < 180) return null
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val m = buildMasks(pixels, w, h)
        val info = "hueY=${m.hueY} strict=${m.strictFrac}"
        val out = Detection(ScreenType.OTHER, null, null, Finality.UNKNOWN, null, info)

        // universal pre-gate: no plausible yellow anywhere -> not a score frame
        if (m.hueY <= 0f || m.strictFrac * w * h < max(300f, 0.0004f * w * h)) return out

        // banner presentations (stats board top / walking bottom): find boxes
        // F36 unknown-stays-unknown: a pair that passes the SHAPE validation
        // is a scoreboard with certainty the shape test already owns; if no
        // pair then validates by READING, the frame must end unknown -- it
        // must never fall through to the menu reader, whose looser gates
        // once converted a safe "cannot read" into a confident wrong score.
        var pairSeen = false
        for ((A, B) in findScoreBoxes(m, w, h)) {
            pairSeen = true
            val hv = readBoxValue(m, A, minConf = 0.50f, minMargin = 0.04f)
            val av = readBoxValue(m, B, minConf = 0.50f, minMargin = 0.04f)
            if (hv == null || av == null) continue
            val home: Int
            val away: Int
            if (A.x0 < B.x0) { home = hv; away = av } else { home = av; away = hv }
            val strip = findStripBelow(m, A, B, h)
            var rows: List<IndexedRow>? = null
            if (strip != null) {
                rows = tableRows(m, strip.second, A, B, w, h)
            } else {
                rows = tableRows(m, max(A.y1, B.y1), A, B, w, h)
            }
            return if (rows != null && rows.size >= 4) {
                val statRows = ArrayList<StatRow>(rows.size)
                for (r in rows) {
                    if (r.index < STAT_NAMES.size) {
                        statRows.add(StatRow(STAT_NAMES[r.index], r.home, r.away))
                    }
                }
                val fin = if (strip != null) readStripLabel(m, strip, A, B) else Finality.UNKNOWN
                Detection(ScreenType.STATS_BOARD, home, away, fin, Stats(statRows), info)
            } else {
                Detection(ScreenType.WALKING, home, away, Finality.UNKNOWN, null, info)
            }
        }

        if (pairSeen) return out   // unknown, full stop -- never menu (F36)

        // menu presentation: big yellow digits on dark, no boxes
        val menu = findMenuScore(m, w, h)
        if (menu != null) {
            val fin = readMenuFinality(m, menu.first, menu.second)
            return Detection(ScreenType.MENU, menu.third, menu.fourth, fin, null, info)
        }
        return out
    }

    // ----------------------------------------------------------------------
    // Box type (inclusive corners).
    // ----------------------------------------------------------------------
    internal class Box(var x0: Int, var y0: Int, var x1: Int, var y1: Int, var area: Int) {
        val w: Int get() = x1 - x0 + 1
        val h: Int get() = y1 - y0 + 1
        val cy: Float get() = (y0 + y1) / 2f
    }

    // ----------------------------------------------------------------------
    // Connected components (4-neighbour flood fill, iterative).
    // ----------------------------------------------------------------------
    internal fun components(mask: BooleanArray, w: Int, h: Int, minArea: Int): ArrayList<Box> {
        val seen = BooleanArray(mask.size)
        val out = ArrayList<Box>()
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start
            seen[start] = true
            var bx0 = start % w; var bx1 = bx0
            var by0 = start / w; var by1 = by0
            var area = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                area++
                if (x < bx0) bx0 = x
                if (x > bx1) bx1 = x
                if (y < by0) by0 = y
                if (y > by1) by1 = y
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1 }
                if (x + 1 < w && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1 }
                if (y > 0 && mask[p - w] && !seen[p - w]) { seen[p - w] = true; queue[tail++] = p - w }
                if (y + 1 < h && mask[p + w] && !seen[p + w]) { seen[p + w] = true; queue[tail++] = p + w }
            }
            if (area >= minArea) out.add(Box(bx0, by0, bx1, by1, area))
        }
        return out
    }

    internal fun mergeFragments(comps: List<Box>, ygap: Int, maxW: Int, maxH: Int, xjoin: Int = -1): ArrayList<Box> {
        // F36 proximity gluing (prototype.py merge_fragments 1:1). Fragments
        // of ONE glyph split at a thin joint (a '3' loses its backbone under
        // compression: top curl + spine + bottom curl) sit stacked with a
        // small gap and x-spans that overlap or nearly touch. The old
        // 50%-horizontal-overlap rule refused the overhanging curls of a
        // narrow-spine '3' (measured on a second device: a real 3-1 board
        // read 1-1), so join on vertical proximity + horizontal overlap OR
        // adjacency instead. splitWide still re-splits side-by-side digits,
        // maxW/maxH still cap every union, and the classify gates still bind
        // every piece: a bad glue ends in refusal, never a guess. Fixpoint
        // loop so multi-way splits reassemble regardless of break side.
        var cur: List<Box> = comps.sortedWith(compareBy({ it.y0 }, { it.x0 }))
            .map { Box(it.x0, it.y0, it.x1, it.y1, it.area) }
        var out = ArrayList<Box>(cur)
        var changed = true
        while (changed) {
            changed = false
            out = ArrayList()
            for (c in cur) {
                var target: Box? = null
                for (p in out) {
                    val ov = min(c.x1, p.x1) - max(c.x0, p.x0) + 1
                    val gap = max(c.y0 - p.y1, p.y0 - c.y1)
                    if (gap > ygap) continue
                    val xg = max(c.x0 - p.x1, p.x0 - c.x1)
                    val xj = if (xjoin >= 0) xjoin else max(1, (0.15f * max(c.h, p.h)).toInt())
                    if (ov <= 0 && xg > xj) continue
                    if (max(c.x1, p.x1) - min(c.x0, p.x0) + 1 > maxW) continue
                    if (max(c.y1, p.y1) - min(c.y0, p.y0) + 1 > maxH) continue
                    target = p; break
                }
                if (target != null) {
                    target.x0 = min(target.x0, c.x0); target.x1 = max(target.x1, c.x1)
                    target.y0 = min(target.y0, c.y0); target.y1 = max(target.y1, c.y1)
                    target.area += c.area
                    changed = true
                } else {
                    out.add(Box(c.x0, c.y0, c.x1, c.y1, c.area))
                }
            }
            if (changed) {
                cur = out.map { Box(it.x0, it.y0, it.x1, it.y1, it.area) }
            }
        }
        return out
    }

    /** Column-valley split of a glyph cluster into digit pieces. Empty
     *  columns split first (always safe: a digit has no full-height empty
     *  column); fused pieces wider than maxW go to recognition-guided
     *  valley cuts (both halves must read as digits, else min-ink fallback
     *  that downstream gates will reject). */
    internal fun splitWide(mask: BooleanArray, mw: Int, mh: Int, maxW: Int): List<Triple<BooleanArray, Int, Int>> {
        val col = IntArray(mw)
        for (y in 0 until mh) for (x in 0 until mw) if (mask[y * mw + x]) col[x]++
        val segs = ArrayList<IntArray>()
        var start = -1; var gap = 0
        for (x in 0 until mw) {
            if (col[x] > 0) { if (start < 0) start = x; gap = 0 } else if (start >= 0) {
                gap++
                if (gap >= 1) {
                    if (x - gap - start + 1 >= 2) segs.add(intArrayOf(start, x - gap))
                    start = -1
                }
            }
        }
        if (start >= 0 && mw - start >= 2) segs.add(intArrayOf(start, mw - 1))
        if (segs.size > 1) {
            return segs.map { ab ->
                val a = ab[0]; val b = ab[1]
                val sw = b - a + 1
                val sub = BooleanArray(sw * mh)
                for (y in 0 until mh) for (x in 0 until sw) sub[y * sw + x] = mask[y * mw + a + x]
                Triple(sub, sw, mh)
            }
        }
        if (mw <= maxW) return listOf(Triple(mask, mw, mh))
        // fused digits: recognition-guided valley cuts
        val out = ArrayList<Triple<BooleanArray, Int, Int>>()
        var s = 0
        while (mw - s > maxW) {
            val lo = s + max(1, (0.20f * maxW).toInt())
            val hi = min(mw - 1, s + (0.80f * (mw - s)).toInt())
            val cand = (lo..hi).toList().sortedBy { col[it] }.take(5)
            var bestCut = -1; var bestScore = -1f
            for (cut in cand) {
                if (cut - s < 2 || mw - cut < 2) continue
                var score = 0f; var ok = true
                for (piece in listOf(cut to s, mw to cut)) {
                    val a = piece.second; val b = piece.first
                    val sw = b - a
                    val sub = BooleanArray(sw * mh)
                    for (y in 0 until mh) for (x in 0 until sw) sub[y * sw + x] = mask[y * mw + a + x]
                    val rd = classifyGlyph(sub, sw, mh)
                    if (rd.digit == null || rd.confidence < 0.5f) { ok = false; break }
                    score += rd.confidence
                }
                if (ok && score > bestScore) { bestScore = score; bestCut = cut }
            }
            if (bestCut < 0) bestCut = cand.minByOrNull { col[it] } ?: lo
            if (bestCut - s <= 0) break
            val sw = bestCut - s
            val sub = BooleanArray(sw * mh)
            for (y in 0 until mh) for (x in 0 until sw) sub[y * sw + x] = mask[y * mw + s + x]
            out.add(Triple(sub, sw, mh))
            s = bestCut
        }
        if (s < mw) {
            val sw = mw - s
            val sub = BooleanArray(sw * mh)
            for (y in 0 until mh) for (x in 0 until sw) sub[y * sw + x] = mask[y * mw + s + x]
            out.add(Triple(sub, sw, mh))
        }
        return out
    }

    // ----------------------------------------------------------------------
    // Ink (luma-dominant, Otsu-anchored) and box threshold.
    // ----------------------------------------------------------------------
    internal class InkRef(
        val tOtsu: Float, val tDeep: Float, val hueInk: Float,
        val bgV: Float, val bgChroma: Float,
    )

    /** Otsu split over [0,1] values; second element is false when the
     *  histogram is effectively unimodal (no meaningful two-class split).
     *  Parameter-free: the threshold comes from the window's own
     *  distribution, which is what makes it survive panel shifts. */
    internal fun otsuThreshold(vals: FloatArray, n: Int): Pair<Float, Boolean> {
        if (n <= 0) return 0.5f to false
        val nb = 64
        val hist = FloatArray(nb)
        for (k in 0 until n) {
            val b = (vals[k] * nb).toInt().coerceIn(0, nb - 1)
            hist[b]++
        }
        var muT = 0f
        for (k in 0 until nb) muT += hist[k] * ((k + 0.5f) / nb)
        var w0 = 0f; var muK = 0f; var best = -1f; var bestK = 0
        val tot = n.toFloat()
        for (k in 0 until nb) {
            w0 += hist[k]
            val w1 = tot - w0
            if (w0 <= 0f || w1 <= 0f) continue
            muK += hist[k] * ((k + 0.5f) / nb)
            val mu0 = muK / w0
            val mu1 = (muT - muK) / w1
            val sb = (w0 / tot) * (w1 / tot) * (mu0 - mu1) * (mu0 - mu1)
            if (sb > best) { best = sb; bestK = k }
        }
        val t = (bestK + 0.5f) / nb
        // recompute the class weights at the winning split for the guard
        var wa = 0f
        for (k in 0..bestK) wa += hist[k]
        val waF = wa / tot
        if (waF < 0.03f || waF > 0.97f) return t to false   // a real class holds real mass
        return t to true
    }

    internal fun percentile(vals: FloatArray, n: Int, p: Float): Float {
        if (n <= 0) return 0f
        val s = vals.copyOf(n)
        java.util.Arrays.sort(s, 0, n)
        val idx = ((p / 100f) * (n - 1)).toInt().coerceIn(0, n - 1)
        return s[idx]
    }

    internal fun medianOf(vals: FloatArray, n: Int): Float {
        if (n <= 0) return 0f
        val s = vals.copyOf(n)
        java.util.Arrays.sort(s, 0, n)
        return s[n / 2]
    }

    /** F35: self-derived ink reference from the box's own v-distribution.
     *  The F34 model (fixed ceiling + single hue-family gate) broke under
     *  night-shift + JPEG q60: 4:2:0 chroma subsampling bleeds the yellow
     *  background INTO a thin navy stroke, the stroke's chroma becomes a
     *  yellow-navy mix, and the hue-family gate rejects the digit's own
     *  pixels while the ink-hue reference itself collapsed to yellow.
     *  LUMA is the invariant: ink v 0.14-0.54 vs yellow background
     *  0.80-1.00 on every measured panel/compression transform.
     *  Two zones: deep (v <= tDeep) is ink by luma alone; ambiguous
     *  (tDeep..tOtsu) is ink unless it carries a strong-yellow signature
     *  (chroma >= YSTRONG_CHROMA x bg, hue within YSTRONG_HUE of the
     *  frame's yellow, no blue lean) - that is the halo band. */
    internal fun boxInkRef(m: Masks, box: Box): InkRef? {
        var sum = 0f; var n = 0
        val vv = FloatArray(max(1, box.w * box.h))
        val cc = FloatArray(max(1, box.w * box.h))
        var vn = 0
        for (y in box.y0..box.y1) for (x in box.x0..box.x1) {
            val i = y * m.w + x
            vv[vn] = m.v[i]; vn++
            if (m.strict[i]) { sum += m.v[i]; n++; cc[n - 1] = m.chroma[i] }
        }
        if (n == 0) return null
        val bgV = sum / n
        val bgChroma = medianOf(cc, n)
        val p02 = percentile(vv, vn, 2f)
        val (tRaw, ok) = otsuThreshold(vv, vn)
        val tOtsu = if (ok) tRaw else p02 + UNIMODAL_FALLBACK * max(0.05f, bgV - p02)
        val tDeep = p02 + TDEEP_FRAC * max(0f, tOtsu - p02)
        val hues = FloatArray(max(1, box.w * box.h))
        var hn = 0
        for (y in box.y0..box.y1) for (x in box.x0..box.x1) {
            val i = y * m.w + x
            if (m.v[i] <= tDeep) hues[hn++] = m.hue[i]
        }
        val hueInk = if (hn >= 6) medianOf(hues, hn) else 240f
        return InkRef(tOtsu, tDeep, hueInk, bgV, bgChroma)
    }

    internal fun inkMask(m: Masks, y0: Int, y1: Int, x0: Int, x1: Int, ref: InkRef): BooleanArray {
        val ww = x1 - x0 + 1
        val hh = y1 - y0 + 1
        val out = BooleanArray(ww * hh)
        for (y in 0 until hh) {
            val fy = y0 + y
            for (x in 0 until ww) {
                val i = fy * m.w + x0 + x
                val lean = m.b[i] + INK_LEAN >= m.r[i] && m.b[i] + INK_LEAN >= m.g[i]
                val v = m.v[i]
                val deep = v <= ref.tDeep
                if (deep) { out[y * ww + x] = true; continue }
                if (v > ref.tOtsu) continue
                val dhY = abs(((m.hue[i] - m.hueY + 180f) % 360f) - 180f)
                val yellowStrong = m.chroma[i] >= YSTRONG_CHROMA * max(1f, ref.bgChroma) && dhY <= YSTRONG_HUE
                out[y * ww + x] = !yellowStrong && (lean || m.chroma[i] <= 25f)
            }
        }
        return out
    }

    /** 3-tap vertical closing: fills 1-2px horizontal cracks that JPEG noise
     *  cuts into thin strokes (a cracked-open '0' counts two holes and reads
     *  '8'). Only used in the score-box path where glyphs are ~29px; the
     *  ~16px table glyphs deform under closing and stay raw. */
    internal fun closeV(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val d = BooleanArray(mask.size)
        for (i in mask.indices) {
            val y = i / w
            d[i] = mask[i] || (y > 0 && mask[i - w]) || (y + 1 < h && mask[i + w])
        }
        val out = BooleanArray(mask.size)
        for (i in mask.indices) {
            val y = i / w
            out[i] = (d[i] && (y == 0 || d[i - w]) && (y + 1 == h || d[i + w])) || mask[i]
        }
        return out
    }

    // ----------------------------------------------------------------------
    // Stage A: score-box pair, found anywhere (structural).
    // ----------------------------------------------------------------------
    internal class Evidence(val ok: Boolean, val frac: Float, val glyphs: Int)

    internal fun boxDigitEvidence(m: Masks, box: Box): Evidence {
        val ref = boxInkRef(m, box) ?: return Evidence(false, 0f, 0)
        val ix0 = box.x0 + (0.14f * box.w).toInt()
        val ix1 = box.x1 - (0.14f * box.w).toInt()
        val iy0 = box.y0 + (0.14f * box.h).toInt()
        val iy1 = box.y1 - (0.14f * box.h).toInt()
        if (ix1 - ix0 < 4 || iy1 - iy0 < 4) return Evidence(false, 0f, 0)
        val closed = closeV(inkMask(m, iy0, iy1, ix0, ix1, ref), ix1 - ix0 + 1, iy1 - iy0 + 1)
        val ink = closed
        val ww = ix1 - ix0 + 1; val hh = iy1 - iy0 + 1
        var inkN = 0
        for (v in ink) if (v) inkN++
        val frac = inkN.toFloat() / (ww * hh)
        if (frac < 0.05f || frac > 0.62f) return Evidence(false, frac, 0)
        val comps = components(ink, ww, hh, 6)
        val glyphs = comps.filter { 0.25f * box.h <= it.h && it.h <= 0.95f * box.h }
        if (glyphs.isEmpty()) return Evidence(false, frac, 0)
        val boxArea = (box.w - 2 * (0.14f * box.w).toInt()) * (box.h - 2 * (0.14f * box.h).toInt())
        for (g in glyphs) if (g.area > 0.30f * max(1, boxArea)) return Evidence(false, frac, 0)
        // 8-point solidity: a real box is a FILLED rounded rectangle — yellow
        // at its four corners AND four edge midpoints. Digit glyphs fail at
        // least one sample ('0'/'8' corners, '4' left edge, ':' everywhere).
        val cs = max(3, (0.22f * min(box.w, box.h)).toInt())
        val strict = m.strict
        val bx0 = box.x0 + 1; val by0 = box.y0 + 1
        val bx1 = box.x1 - 1; val by1 = box.y1 - 1
        val my = (box.y0 + box.y1) / 2; val mx = (box.x0 + box.x1) / 2
        val probes = listOf(
            bx0 to by0, bx1 - cs to by0, bx0 to by1 - cs, bx1 - cs to by1 - cs,
            bx0 to my - cs / 2, bx1 - cs to my - cs / 2,
            mx - cs / 2 to by0, mx - cs / 2 to by1 - cs,
        )
        for ((px, py) in probes) {
            val cx0 = px.coerceIn(0, m.w - cs)
            val cy0 = py.coerceIn(0, m.h - cs)
            var n = 0; var tot = 0
            for (y in cy0 until cy0 + cs) for (x in cx0 until cx0 + cs) {
                tot++; if (strict[y * m.w + x]) n++
            }
            if (n.toFloat() / tot < 0.45f) return Evidence(false, frac, 0)
        }
        return Evidence(true, frac, glyphs.size)
    }

    /** F35: sub-box recovery inside a wide low-fill strict component.
     *  A translucent banner (score boxes joined by a dim bar) can fuse into
     *  ONE strict component after downscale + JPEG chroma bleed; its aspect
     *  then fails the box gate and the scoreline is lost. The boxes are
     *  still recoverable from the component's own geometry: per-column
     *  vertical runs are tall and equal at box columns, short at bar/label
     *  columns, and the glyph holes punch narrow gaps between a box's own
     *  walls. Both scales come from the profile itself (p90 run height,
     *  median wall width) - no absolute constants. */
    internal fun blobSubBoxes(m: Masks, b: Box): List<Box> {
        val bw = b.w; val bh = b.h
        val runs = IntArray(bw); val tops = IntArray(bw)
        for (x in 0 until bw) {
            var bestLen = 0; var bestS = 0; var s = -1
            for (y in 0 until bh) {
                val on = m.strict[(b.y0 + y) * m.w + b.x0 + x]
                if (on && s < 0) s = y
                else if (!on && s >= 0) {
                    if (y - s > bestLen) { bestLen = y - s; bestS = s }
                    s = -1
                }
            }
            if (s >= 0 && bh - s > bestLen) { bestLen = bh - s; bestS = s }
            runs[x] = bestLen; tops[x] = bestS
        }
        var nzN = 0
        val nz = FloatArray(bw)
        for (x in 0 until bw) if (runs[x] > 0) { nz[nzN++] = runs[x].toFloat() }
        if (nzN < 8) return emptyList()
        val runH = percentile(nz, nzN, 90f)
        if (runH < 12f) return emptyList()
        val tall = BooleanArray(bw)
        for (x in 0 until bw) tall[x] = runs[x] >= BLOB_TALL * runH
        val groups = ArrayList<IntArray>()
        var s = -1; var gap = 0
        for (x in 0 until bw) {
            if (tall[x]) { if (s < 0) s = x; gap = 0 }
            else if (s >= 0) {
                gap++
                if (gap > 0) { groups.add(intArrayOf(s, x - gap)); s = -1; gap = 0 }
            }
        }
        if (s >= 0) groups.add(intArrayOf(s, bw - 1))
        if (groups.isEmpty()) return emptyList()
        val widths = FloatArray(groups.size)
        for ((k, g) in groups.withIndex()) widths[k] = (g[1] - g[0] + 1).toFloat()
        val medW = medianOf(widths, groups.size)
        // a box's own glyph hole is narrow relative to its walls; the space
        // between two boxes is wide. Bridge the former, split the latter.
        val bridge = BLOB_BRIDGE * medW
        val merged = ArrayList<IntArray>()
        for (g in groups) {
            val last = merged.lastOrNull()
            if (last != null && g[0] - last[1] <= bridge) last[1] = g[1]
            else merged.add(intArrayOf(g[0], g[1]))
        }
        val out = ArrayList<Box>()
        for (g in merged) {
            if (g[1] - g[0] + 1 < max(10, (0.5f * runH).toInt())) continue
            var bi = g[0]; var bv = -1
            for (x in g[0]..g[1]) if (runs[x] > bv) { bv = runs[x]; bi = x }
            val y0 = b.y0 + tops[bi]
            val y1 = y0 + runs[bi] - 1
            val nb = Box(b.x0 + g[0], y0, b.x0 + g[1], y1, 0)
            var area = 0
            for (y in nb.y0..nb.y1) for (x in nb.x0..nb.x1) if (m.strict[y * m.w + x]) area++
            nb.area = area
            out.add(nb)
        }
        return out
    }

    internal fun findScoreBoxes(m: Masks, W: Int, H: Int): List<Pair<Box, Box>> {
        val minArea = max(30, (0.00005f * W * H).toInt())
        val comps = components(m.strict, W, H, minArea)
        data class Cand(val b: Box, val th: Float, val quality: Float)
        val cands = ArrayList<Cand>()
        val extraBlobs = ArrayList<Box>()
        for (b in comps) {
            if (b.h < max(12, (0.012f * H).toInt()) || b.h > 0.15f * H) continue
            if (b.w > 0.90f * W) continue
            val asp = b.w.toFloat() / b.h
            if (asp < 0.55f || asp > 2.8f) {
                // a fused banner (boxes + translucent connecting bar) is wide
                // and low-fill; recover the boxes from its own run profile
                if (asp > 2.8f && b.area.toFloat() / (b.w * b.h) < 0.65f) extraBlobs.add(b)
                continue
            }
            if (b.area.toFloat() / (b.w * b.h) < 0.52f) continue
            val ev = boxDigitEvidence(m, b)
            if (ev.ok) {
                cands.add(Cand(b, 0.5f,
                    ev.frac * sqrt(b.area.toFloat()) * (1 + 0.1f * ev.glyphs)))
            }
        }
        for (blob in extraBlobs) {
            for (b in blobSubBoxes(m, blob)) {
                if (b.h < max(12, (0.012f * H).toInt()) || b.h > 0.15f * H || b.w > 0.90f * W) continue
                val asp = b.w.toFloat() / b.h
                if (asp < 0.55f || asp > 2.8f || b.area.toFloat() / (b.w * b.h) < 0.52f) continue
                val ev = boxDigitEvidence(m, b)
                if (ev.ok) {
                    cands.add(Cand(b, 0.5f,
                        ev.frac * sqrt(b.area.toFloat()) * (1 + 0.1f * ev.glyphs)))
                }
            }
        }
        data class PairCand(val minArea: Int, val quality: Float, val A: Box, val B: Box)
        val pairs = ArrayList<PairCand>()
        for (i in cands.indices) for (j in i + 1 until cands.size) {
            val A = cands[i]; val B = cands[j]
            val hmax = max(A.b.h, B.b.h)
            if (abs(A.b.h - B.b.h) > 0.35f * hmax) continue
            if (abs(A.b.cy - B.b.cy) > 0.35f * hmax) continue
            val gap = max(A.b.x0 - B.b.x1, B.b.x0 - A.b.x1)
            if (gap < 0.30f * min(A.b.w, B.b.w) || gap > 2.5f * min(A.b.w, B.b.w)) continue
            pairs.add(PairCand(min(A.b.area, B.b.area), A.quality + B.quality, A.b, B.b))
        }
        pairs.sortWith(compareByDescending<PairCand> { it.minArea }.thenByDescending { it.quality })
        return pairs.map { it.A to it.B }
    }

    // ----------------------------------------------------------------------
    // Stage B: read a number out of one yellow box.
    // ----------------------------------------------------------------------
    internal fun readBoxValue(m: Masks, box: Box, minConf: Float, minMargin: Float): Int? {
        val ref = boxInkRef(m, box) ?: return null
        val ix0 = box.x0 + (0.14f * box.w).toInt()
        val ix1 = box.x1 - (0.14f * box.w).toInt()
        val iy0 = box.y0 + (0.14f * box.h).toInt()
        val iy1 = box.y1 - (0.14f * box.h).toInt()
        if (ix1 - ix0 < 4 || iy1 - iy0 < 4) return null
        val ink = closeV(inkMask(m, iy0, iy1, ix0, ix1, ref), ix1 - ix0 + 1, iy1 - iy0 + 1)
        val ww = ix1 - ix0 + 1; val hh = iy1 - iy0 + 1
        var inkN = 0
        for (v in ink) if (v) inkN++
        if (inkN < 10) return null
        var comps: List<Box> = components(ink, ww, hh, 5)
        comps = mergeFragments(comps, max(2, (0.20f * box.h).toInt()), box.w, box.h)
        comps = comps.filter { it.h >= 0.25f * box.h }
        if (comps.isEmpty()) return null
        val hmax = comps.maxOf { it.h }
        comps = comps.filter { it.h >= 0.62f * hmax }.sortedBy { it.x0 }
        if (comps.size > 3) return null
        val digits = StringBuilder()
        for (c in comps) {
            // reading-resolution floor: below ~8px a glyph carries no identity
            if (c.h < 8) return null
            val sw = c.x1 - c.x0 + 1
            val sub = BooleanArray(sw * c.h)
            for (y in 0 until c.h) for (x in 0 until sw) {
                sub[y * sw + x] = ink[(c.y0 - iy0 + y) * ww + (c.x0 - ix0 + x)]
            }
            for ((piece, pw, ph) in splitWide(sub, sw, c.h, (1.30f * c.h).toInt())) {
                val rd = classifyGlyph(piece, pw, ph)
                if (rd.digit == null || rd.confidence < minConf || rd.margin < minMargin) return null
                digits.append('0' + rd.digit)
            }
        }
        val s = digits.toString()
        if (s.isEmpty() || s.length > 2) return null
        return s.toIntOrNull()
    }

    // ----------------------------------------------------------------------
    // Stage C: label strip + finality.
    // ----------------------------------------------------------------------
    internal fun findStripBelow(m: Masks, A: Box, B: Box, H: Int): Pair<Int, Int>? {
        val px0 = min(A.x0, B.x0); val px1 = max(A.x1, B.x1)
        val span = px1 - px0
        val x0 = max(0, px0 - span); val x1 = min(m.w - 1, px1 + span)
        var y = max(A.y1, B.y1) + 1
        val limit = min(H, y + (3.5f * max(A.h, B.h)).toInt())
        val minRun = max(3, (0.30f * max(A.h, B.h)).toInt())
        var best: Pair<Int, Int>? = null
        var runStart = -1; var runEnd = -1; var runLen = 0
        while (y < limit) {
            var n = 0; var tot = 0
            var x = x0
            while (x <= x1) { tot++; if (m.strict[y * m.w + x]) n++; x += 2 }
            if (n.toFloat() / max(1, tot) >= 0.55f) {
                if (runStart < 0) runStart = y
                runEnd = y; runLen++
            } else {
                if (runStart >= 0 && runLen >= minRun) {
                    if (best == null || runLen > best.second - best.first + 1) best = runStart to runEnd
                }
                runStart = -1; runLen = 0
            }
            y++
        }
        if (runStart >= 0 && runLen >= minRun) {
            if (best == null || runLen > best.second - best.first + 1) best = runStart to runEnd
        }
        return best
    }

    internal fun readStripLabel(m: Masks, strip: Pair<Int, Int>, A: Box, B: Box): Finality {
        val (top, bot) = strip
        val x0 = min(A.x0, B.x0); val x1 = max(A.x1, B.x1)
        val stripH = bot - top + 1
        // F35: same two-zone Otsu ink as the score boxes (the strip is the
        // same yellow-navy construction), derived from a synthetic box over
        // the label window - this recovered FULL_TIME/HALF_TIME under
        // panel shifts and downscale.
        val ref = boxInkRef(m, Box(x0, top, x1, bot, 0)) ?: return Finality.UNKNOWN
        val closed = closeV(inkMask(m, top, bot, x0, x1, ref), x1 - x0 + 1, stripH)
        val ink = closed
        val comps = components(ink, x1 - x0 + 1, stripH, 8)
        val cands = comps.filter { 0.25f * stripH <= it.h && it.h <= 0.90f * stripH }
        if (cands.isEmpty()) return Finality.UNKNOWN
        val c = cands.minByOrNull { it.x0 } ?: return Finality.UNKNOWN
        val cw = c.x1 - c.x0 + 1; val ch = c.y1 - c.y0 + 1
        val sub = BooleanArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            sub[y * cw + x] = ink[(c.y0 - top + y) * (x1 - x0 + 1) + (c.x0 - x0 + x)]
        }
        return classifyFH(sub, cw, ch)
    }

    // ----------------------------------------------------------------------
    // Stage D: statistics table (index-positioned rows).
    // ----------------------------------------------------------------------
    internal class IndexedRow(val index: Int, val home: Int, val away: Int)

    internal fun clusterTokens(row: List<Box>, tokGap: Int): ArrayList<Pair<Box, ArrayList<Box>>> {
        val sorted = row.sortedBy { it.x0 }
        val tokens = ArrayList<Pair<Box, ArrayList<Box>>>()
        for (c in sorted) {
            val last = tokens.lastOrNull()
            if (last != null && c.x0 - last.first.x1 <= tokGap) {
                val (tb, parts) = last
                val nb = Box(tb.x0, min(tb.y0, c.y0), max(tb.x1, c.x1), max(tb.y1, c.y1), tb.area + c.area)
                parts.add(c)
                tokens[tokens.size - 1] = nb to parts
            } else {
                tokens.add(Box(c.x0, c.y0, c.x1, c.y1, c.area) to arrayListOf(c))
            }
        }
        return tokens
    }

    internal fun occupancyLabelBand(tokenRows: List<List<Pair<Box, ArrayList<Box>>>>): Pair<Int, Int>? {
        if (tokenRows.isEmpty()) return null
        var xLo = Int.MAX_VALUE; var xHi = Int.MIN_VALUE
        for (tr in tokenRows) for ((tb, _) in tr) {
            if (tb.x0 < xLo) xLo = tb.x0
            if (tb.x1 > xHi) xHi = tb.x1
        }
        val nb = max(1, (xHi - xLo) / 4)
        val cover = IntArray(nb + 1)
        for (tr in tokenRows) for ((tb, _) in tr) {
            val a = max(0, (tb.x0 - xLo) / 4).coerceAtMost(nb)
            val b = max(0, (tb.x1 - xLo) / 4).coerceAtMost(nb)
            for (k in a..b) cover[k]++
        }
        var peak = 0
        for (cv in cover) if (cv > peak) peak = cv
        val need = max(3, (0.8f * peak).toInt())
        var bestS = 0; var bestE = 0; var curS = -1
        for (i in 0..nb) {
            if (cover[i] >= need) { if (curS < 0) curS = i }
            else {
                if (curS >= 0 && i - curS > bestE - bestS) { bestS = curS; bestE = i }
                curS = -1
            }
        }
        if (curS >= 0 && nb + 1 - curS > bestE - bestS) { bestS = curS; bestE = nb + 1 }
        if (bestE - bestS < 2) return null
        return (xLo + bestS * 4) to (xLo + bestE * 4)
    }

    internal fun tableRows(m: Masks, stripBottom: Int, A: Box, B: Box, W: Int, H: Int): List<IndexedRow>? {
        val boxH = max(A.h, B.h); val boxW = min(A.w, B.w)
        val yTop = stripBottom + max(2, (0.12f * boxH).toInt())
        if (yTop >= H - 4) return null
        val minArea = max(6, (0.000008f * W * H).toInt())
        var comps: List<Box> = componentsOffset(m.loose, m.w, yTop, H, minArea)
        val hlo = 0.18f * boxH; val hhi = 0.90f * boxH
        comps = comps.filter { it.h.toFloat() in hlo..hhi && it.w <= 1.2f * boxW }
        if (comps.size < 8) return null
        val medH = comps.map { it.h.toFloat() }.sorted()[comps.size / 2]
        val rowGap = max(3, (0.70f * medH).toInt())
        comps = comps.sortedBy { it.cy }
        val rowsList = ArrayList<ArrayList<Box>>()
        for (c in comps) {
            val last = rowsList.lastOrNull()
            if (last != null && abs(c.cy - last.last().cy) < rowGap) last.add(c)
            else rowsList.add(arrayListOf(c))
        }
        val tokenRows = rowsList.map { clusterTokens(it, max(2, (0.60f * medH).toInt())) }
        val band = occupancyLabelBand(tokenRows) ?: return null
        val (bandX0, bandX1) = band
        data class ParsedRow(val cy: Float, val home: Pair<Box, ArrayList<Box>>, val away: Pair<Box, ArrayList<Box>>)
        val parsed = ArrayList<ParsedRow>()
        for ((row, tr) in rowsList.zip(tokenRows)) {
            val cy = row.map { it.cy }.average().toFloat()
            val home = tr.filter { it.first.x1 < bandX0 }.maxByOrNull { it.first.x1 }
            val away = tr.filter { it.first.x0 > bandX1 }.minByOrNull { it.first.x0 }
            if (home != null && away != null) parsed.add(ParsedRow(cy, home, away))
        }
        if (parsed.size < 4) return null
        // trim trailing rows off the regular lattice (UI below the table)
        while (parsed.size > 4) {
            val pitches = (0 until parsed.size - 1).map { parsed[it + 1].cy - parsed[it].cy }.filter { it > 0f }
            if (pitches.isEmpty()) break
            val medPitch = pitches.sorted()[pitches.size / 2]
            if (parsed[parsed.size - 1].cy - parsed[parsed.size - 2].cy <= 1.6f * medPitch) break
            parsed.removeAt(parsed.size - 1)
        }
        // row y-lattice -> table index: one unreadable value never shifts rows
        val cys = parsed.map { it.cy }
        val pitches2 = (0 until cys.size - 1).map { cys[it + 1] - cys[it] }.filter { it > 2f }
        if (pitches2.isEmpty()) return null
        val pitch = pitches2.sorted()[pitches2.size / 2]
        val cy0 = cys[0]
        val out = ArrayList<IndexedRow>()
        for (p in parsed) {
            val idx = Math.round((p.cy - cy0) / pitch)
            if (idx > 12) continue
            // F35 cross-validated values: global mask read + local Otsu
            // window read; agreement emits, disagreement refuses, one
            // reading emits it (see crossValue). This removed the wrong
            // stat rows under night-shift + JPEG q60 while improving recall
            // on vivid/q75 tables.
            val hv = crossValue(tokenValue(m, p.home), tokenValueLocal(m, p.home, bandX0, bandIsLeft = false))
            val av = crossValue(tokenValue(m, p.away), tokenValueLocal(m, p.away, bandX1, bandIsLeft = true))
            if (hv == null || av == null) continue
            out.add(IndexedRow(idx, hv, av))
        }
        if (out.size < 4) return null
        return out
    }

    /** components() on a sub-window of a frame-sized mask, offsetting boxes. */
    internal fun componentsOffset(mask: BooleanArray, w: Int, yFrom: Int, yTo: Int, minArea: Int): ArrayList<Box> {
        val seen = BooleanArray(mask.size)
        val out = ArrayList<Box>()
        val queue = IntArray(mask.size)
        for (start in yFrom * w until yTo * w) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var bx0 = start % w; var bx1 = bx0
            var by0 = start / w; var by1 = by0
            var area = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                area++
                if (x < bx0) bx0 = x
                if (x > bx1) bx1 = x
                if (y < by0) by0 = y
                if (y > by1) by1 = y
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1 }
                if (x + 1 < w && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1 }
                if (y > yFrom && mask[p - w] && !seen[p - w]) { seen[p - w] = true; queue[tail++] = p - w }
                if (y + 1 < yTo && mask[p + w] && !seen[p + w]) { seen[p + w] = true; queue[tail++] = p + w }
            }
            if (area >= minArea) out.add(Box(bx0, by0, bx1, by1, area))
        }
        return out
    }

    // ----------------------------------------------------------------------
    // Token pieces, '%' veto, value reading.
    // ----------------------------------------------------------------------
    internal fun pctVeto(piece: BooleanArray, pw: Int, ph: Int): Boolean {
        val asp = pw.toFloat() / max(1, ph)
        if (asp > 0.85f) return true
        return asp > 0.80f && countHoles(piece, pw, ph, minHoleArea = 1) >= 2
    }

    internal fun tokenPieces(m: Masks, tb: Box, parts: List<Box>): List<Triple<BooleanArray, Int, Int>> {
        val mw = tb.x1 - tb.x0 + 1; val mh = tb.y1 - tb.y0 + 1
        val mask = BooleanArray(mw * mh)
        for (c in parts) {
            // sparse fragments (a degraded '%' sliver fills ~0.21 of its bbox;
            // real glyph parts fill >= ~0.40) never enter the union
            if (c.area.toFloat() / max(1, c.w * c.h) < 0.15f) continue
            for (y in c.y0..c.y1) for (x in c.x0..c.x1) {
                if (m.loose[y * m.w + x]) mask[(y - tb.y0) * mw + (x - tb.x0)] = true
            }
        }
        return splitWide(mask, mw, mh, (1.30f * mh).toInt())
    }

    internal fun tokenValue(m: Masks, tok: Pair<Box, ArrayList<Box>>): Int? {
        val digits = StringBuilder()
        for ((piece, pw, ph) in tokenPieces(m, tok.first, tok.second)) {
            if (pctVeto(piece, pw, ph)) continue
            val rd = classifyGlyph(piece, pw, ph)
            // 0.60 confidence: real stat digits read 0.61..1.0, compression
            // sliver junk reads ~0.51 (measured on the calibration set)
            // F35 integrity rule: a token is emitted ONLY if every piece is
            // either a confident digit or a '%'. The old drop-and-continue
            // turned a refused glyph into a SHORTER number that still looked
            // valid ('38' -> '3') - a silent wrong value. Refusal kills the
            // row; the row lattice keeps every other row at its index.
            if (rd.digit == null || rd.confidence < 0.60f || rd.margin < 0.03f) return null
            digits.append('0' + rd.digit)
        }
        val s = digits.toString()
        if (s.isEmpty() || s.length > 2) return null   // a stat value is <= 2 digits
        return s.toIntOrNull()
    }

    /** F35 local value read: an Otsu window over the token's own
     *  neighbourhood. The global loose mask is a frame-wide colour gate;
     *  under panel shift + chroma-subsampled JPEG it can lose a thin glyph
     *  part entirely (the top bar of a '5' -> the residual shape reads '6';
     *  an '8' drops out -> '3'). This reader derives a threshold from the
     *  token window's own bimodal v histogram and pads the window toward
     *  the column's alignment side (home values right-align against the
     *  label band, away values left-align), recovering what the global mask
     *  missed. Returns a value or null; the caller cross-validates. */
    internal fun tokenValueLocal(m: Masks, tok: Pair<Box, ArrayList<Box>>, bandEdge: Int, bandIsLeft: Boolean): Int? {
        val tb = tok.first
        val h = max(1, tb.h)
        val padOut = (0.70f * h).toInt() + 1
        val padIn = 2
        val padY = 2
        var x0: Int; var x1: Int
        if (bandIsLeft) {                    // away column: values left-aligned at band
            x0 = max(0, tb.x0 - padIn); x1 = min(m.w - 1, tb.x1 + padOut)
        } else {                             // home column: values right-aligned at band
            x0 = max(0, tb.x0 - padOut); x1 = min(m.w - 1, tb.x1 + padIn)
        }
        if (bandEdge >= 0) {
            if (bandIsLeft) x0 = max(x0, bandEdge + 2)
            else x1 = min(x1, bandEdge - 2)
        }
        val y0 = max(0, tb.y0 - padY)
        val y1 = min(m.h - 1, tb.y1 + padY)
        if (x1 - x0 < 2 || y1 - y0 < 4) return null
        val ww = x1 - x0 + 1; val wh = y1 - y0 + 1
        val winV = FloatArray(ww * wh)
        var vn = 0
        for (y in y0..y1) for (x in x0..x1) { winV[vn++] = m.v[y * m.w + x] }
        val (t, ok) = otsuThreshold(winV, vn)
        if (!ok) return null
        val mask = BooleanArray(ww * wh)
        var maskN = 0
        for (y in 0 until wh) for (x in 0 until ww) {
            val i = (y0 + y) * m.w + x0 + x
            val dh = abs(((m.hue[i] - m.hueY + 180f) % 360f) - 180f)
            val keep = m.v[i] >= t && (dh <= 50f || m.chroma[i] >= 80f)
            mask[y * ww + x] = keep
            if (keep) maskN++
        }
        if (maskN < 8) return null
        var comps = components(mask, ww, wh, 6)
        if (comps.isEmpty()) return null
        comps = mergeFragments(comps, max(2, (0.25f * tb.h).toInt()),
            tb.w + 2 * ((0.70f * tb.h).toInt() + 2), (1.6f * tb.h).toInt())
        val clean = BooleanArray(ww * wh)
        var cleanN = 0
        for (c in comps) {
            for (y in c.y0..c.y1) for (x in c.x0..c.x1) {
                if (mask[y * ww + x]) { clean[y * ww + x] = true; cleanN++ }
            }
        }
        if (cleanN < 8) return null
        val pieces = splitWide(clean, ww, wh, (1.30f * wh).toInt())
        var hmax = 0
        for ((_, _, ph) in pieces) if (ph > hmax) hmax = ph
        if (hmax < STATS_PIECE_FLOOR) return null   // whole table below identity floor
        val digits = StringBuilder()
        for ((piece, pw, ph) in pieces) {
            if (ph < max(4, (0.60f * hmax).toInt())) {
                continue                     // speck / streak fragment, not a glyph
            }
            if (pw.toFloat() / max(1, ph) < 0.28f) {
                // F36: a GLYPH-SIZED thin piece is a serif-less '1' (ink
                // 3-5px wide at 16-19px tall, aspect 0.19-0.27) or a '%'
                // sliver -- never silently droppable. Dropping it let the
                // token read a SHORTER number ("14" -> "4"); crossValue then
                // refused the disagreement and the stat row came back EMPTY
                // (the review's Total Shots / Shots on Target / Free Kicks
                // symptom). Classify it with the standard gates instead: a
                // digit joins the number; a refusal kills the token and
                // crossValue falls back to the global read.
                val rd0 = classifyGlyph(piece, pw, ph)
                if (rd0.digit == null || rd0.confidence < 0.60f || rd0.margin < 0.03f) return null
                digits.append('0' + rd0.digit)
                continue
            }
            if (ph < STATS_PIECE_FLOOR) return null   // real glyph below identity floor
            if (pctVeto(piece, pw, ph)) continue      // '%'
            val rd = classifyGlyph(piece, pw, ph)
            if (rd.digit == null || rd.confidence < 0.60f || rd.margin < 0.03f) return null
            digits.append('0' + rd.digit)
        }
        val s = digits.toString()
        if (s.isEmpty() || s.length > 3) return null
        return s.toIntOrNull()
    }

    /** F35: combine the global-mask and local-window reads of one token.
     *  Agreement -> the value. Disagreement -> refuse (never guess which of
     *  two independent measurements is wrong). One reading -> emit it. */
    internal fun crossValue(gv: Int?, lv: Int?): Int? {
        if (gv == null) return lv
        if (lv == null) return gv
        return if (gv == lv) gv else null
    }

    // ----------------------------------------------------------------------
    // Stage E: menu score.
    // ----------------------------------------------------------------------
    internal fun darkBackground(m: Masks, box: Box): Boolean {
        val r = box.h
        val x0 = max(0, box.x0 - r); val x1 = min(m.w - 1, box.x1 + r)
        val y0 = max(0, box.y0 - r); val y1 = min(m.h - 1, box.y1 + r)
        var sum = 0f; var n = 0
        for (y in y0..y1) for (x in x0..x1) {
            val inside = x in box.x0..box.x1 && y in box.y0..box.y1
            if (!inside) { sum += m.v[y * m.w + x]; n++ }
        }
        return n > 0 && sum / n <= 0.34f
    }

    internal class MenuResult(val first: Box, val second: Box, val third: Int, val fourth: Int)

    internal fun readNumberTokens(m: Masks, clusters: List<Pair<Box, ArrayList<Box>>>, medH: Float): List<Pair<Box, Int>> {
        val out = ArrayList<Pair<Box, Int>>()
        for ((tb, parts) in clusters) {
            // F35 solid-blob guard: a SOLID yellow rectangle (a score box, a
            // label strip) that lost its own detection path would arrive here
            // as a cluster and be classified from its INVERSE glyph (the
            // digit-shaped holes in the yellow) - measured fill 0.86-0.92 vs
            // 0.54-0.60 for real bold digit strokes - which is exactly how a
            // real 0-1 stats board once reported MENU 0-0.
            var area = 0
            for (c in parts) area += c.area
            if (area.toFloat() / max(1, tb.w * tb.h) > MENU_SOLID_FILL) continue
            val mw = tb.x1 - tb.x0 + 1; val mh = tb.y1 - tb.y0 + 1
            val mask = BooleanArray(mw * mh)
            for (c in parts) {
                for (y in c.y0..c.y1) for (x in c.x0..c.x1) {
                    if (m.loose[y * m.w + x]) mask[(y - tb.y0) * mw + (x - tb.x0)] = true
                }
            }
            var digits = ""
            var pure = true
            for ((piece, pw, ph) in splitWide(mask, mw, mh, (1.30f * mh).toInt())) {
                val rd = classifyGlyph(piece, pw, ph)
                if (rd.digit == null || rd.confidence < 0.45f || rd.margin < 0.02f) { pure = false; break }
                digits += ('0' + rd.digit)
            }
            if (pure && digits.length in 1..2) out.add(tb to (digits.toIntOrNull() ?: continue))
        }
        return out
    }

    internal fun findMenuScore(m: Masks, W: Int, H: Int): MenuResult? {
        val minArea = max(20, (0.00006f * W * H).toInt())
        val comps = components(m.loose, W, H, minArea)
        // component floor is an anti-speck guard in ABSOLUTE pixels; the
        // authored-scale floor lives on whole CLUSTERS further down
        val cand = comps.filter { it.h >= 6 && it.h <= 0.15f * H && it.w <= 0.5f * W && darkBackground(m, it) }
        if (cand.size < 2) return null
        val medH = cand.map { it.h.toFloat() }.sorted()[cand.size / 2]
        // merge fragments by X-OVERLAP: a bold digit splits into bowl + base
        // bar that fully overlap the digit horizontally; the score DASH
        // overlaps neither neighbour and survives as its own cluster
        val clusters = cand.map { it to arrayListOf(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val out = ArrayList<Pair<Box, ArrayList<Box>>>()
            for (cur in clusters) {
                val c = cur.first
                var target: Pair<Box, ArrayList<Box>>? = null
                for (o in out) {
                    val ov = min(c.x1, o.first.x1) - max(c.x0, o.first.x0) + 1
                    val minw = min(c.w, o.first.w)
                    val ygap = max(c.y0 - o.first.y1, o.first.y0 - c.y1)
                    if (ov < 0.5f * minw || ygap > 0.5f * medH) continue
                    if (max(c.y1, o.first.y1) - min(c.y0, o.first.y0) + 1 > 0.16f * H) continue
                    target = o; break
                }
                if (target != null) {
                    val t = target.first
                    val nb = Box(min(t.x0, c.x0), min(t.y0, c.y0), max(t.x1, c.x1), max(t.y1, c.y1), t.area + c.area)
                    out[out.indexOf(target)] = nb to ArrayList<Box>().apply {
                        addAll(target.second)
                        addAll(cur.second)
                    }
                    changed = true
                } else {
                    out.add(cur)
                }
            }
            clusters.clear(); clusters.addAll(out)
        }
        var dash: Box? = null
        val numberClus = ArrayList<Pair<Box, ArrayList<Box>>>()
        for (cl in clusters) {
            val tb = cl.first
            if (tb.h < 0.035f * H) continue
            if (cl.second.size == 1 && tb.w.toFloat() / max(1, tb.h) >= 1.4f && dash == null) {
                dash = tb; continue
            }
            numberClus.add(cl)
        }
        val numbers = readNumberTokens(m, numberClus, medH).toMutableList()
        if (numbers.size < 2) return null
        val maxH = numbers.maxOf { it.first.h }
        val strong = numbers.filter { it.first.h >= 0.60f * maxH }
        if (strong.size < 2) return null
        val sorted = strong.sortedBy { it.first.x0 }
        if (dash != null) {
            val left = sorted.lastOrNull { it.first.x1 < dash.x0 }
            val right = sorted.firstOrNull { it.first.x0 > dash.x1 }
            if (left != null && right != null) return MenuResult(left.first, right.first, left.second, right.second)
        }
        var best: Triple<Int, Pair<Box, Int>, Pair<Box, Int>>? = null
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val A = sorted[i].first; val B = sorted[j].first
            if (abs(A.h - B.h) > 0.35f * max(A.h, B.h)) continue
            val gap = B.x0 - A.x1
            if (gap < 0 || gap > 2.5f * max(A.w, B.w)) continue
            if (best == null || gap < best.first) best = Triple(gap, sorted[i], sorted[j])
        }
        best ?: return null
        return MenuResult(best.second.first, best.third.first, best.second.second, best.third.second)
    }

    internal fun readMenuFinality(m: Masks, homeBox: Box, awayBox: Box): Finality {
        val x0 = min(homeBox.x0, awayBox.x0)
        val x1 = max(homeBox.x1, awayBox.x1)
        val gh = max(homeBox.h, awayBox.h)
        val y1 = min(homeBox.y0, awayBox.y0) - 1
        val y0 = max(0, y1 - (2.0f * gh).toInt())
        if (y1 - y0 < 4) return Finality.UNKNOWN
        val ww = x1 - x0 + 1; val wh = y1 - y0 + 1
        val win = BooleanArray(ww * wh)
        for (y in 0 until wh) for (x in 0 until ww) win[y * ww + x] = m.loose[(y0 + y) * m.w + x0 + x]
        val comps = components(win, ww, wh, 8)
        val cands = comps.filter { 0.10f * gh <= it.h && it.h <= 0.90f * gh }
        if (cands.isEmpty()) return Finality.UNKNOWN
        val c = cands.minByOrNull { it.x0 } ?: return Finality.UNKNOWN
        val cw = c.x1 - c.x0 + 1; val ch = c.y1 - c.y0 + 1
        val sub = BooleanArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) sub[y * cw + x] = win[(c.y0 + y) * ww + c.x0 + x]
        return classifyFH(sub, cw, ch)
    }

    // ----------------------------------------------------------------------
    // F / H / clock on glyph-internal fractions only (scale-free).
    // ----------------------------------------------------------------------
    internal fun classifyFH(mask: BooleanArray, w: Int, h: Int): Finality {
        if (h < 8 || w < 3) return Finality.UNKNOWN
        val holeFloor = max(4, (h / 8) * (h / 8))
        val holes = countHoles(mask, w, h, holeFloor)
        if (holes >= 1) return Finality.CLOCK
        val q = max(1, w / 4)
        var leftInk = 0; var leftArea = 0
        for (y in 0 until h) for (x in 0 until q) { leftArea++; if (mask[y * w + x]) leftInk++ }
        val colL = leftInk.toFloat() / leftArea
        val r0 = 2 * q
        var lowerInk = 0; var lowerArea = 0
        // F35: bottom-quarter window. F's crossbar sits at ~0.45-0.57h on
        // every rendering, so the old 0.55h window swallowed it on 10-14px
        // glyphs and read F's own bar as an H stem (fin=UNKNOWN at 0.75x);
        // the bottom quarter is unambiguous: F empty, H stem 0.35-0.5+.
        val yStart = (FH_LOWER * h).toInt()
        for (y in yStart until h) for (x in r0 until w) { lowerArea++; if (mask[y * w + x]) lowerInk++ }
        val colRL = if (lowerArea > 0) lowerInk.toFloat() / lowerArea else 0f
        var topInk = 0; var topArea = 0
        val tEnd = max(1, h / 5)
        for (y in 0 until tEnd) for (x in 0 until w) { topArea++; if (mask[y * w + x]) topInk++ }
        val rowT = topInk.toFloat() / topArea
        // F lower-right is empty (0.0-0.1), H's right stem fills 0.35-0.5+ even
        // on narrow small glyphs (a 0.45 gate measured 0.43 on a real 11px H)
        if (colL > 0.30f && colRL < 0.25f && rowT > 0.40f) return Finality.FULL_TIME
        // F35: H top = stems only, measured 0.5-0.67 (a 9px-wide H with 3px
        // stems is 0.67); F's full top bar is ~1.0 - the gate sits mid-gap.
        if (colL > 0.30f && colRL > 0.25f && rowT < FH_HGATE) return Finality.HALF_TIME
        return Finality.UNKNOWN
    }

    // ----------------------------------------------------------------------
    // Glyph classifier (frozen bank, per-digit hole/aspect evidence).
    // ----------------------------------------------------------------------
    internal const val GRID_W = 16
    internal const val GRID_H = 24

    internal class GlyphRead(val digit: Int?, val confidence: Float, val margin: Float)

    private val templates: List<Tpl> by lazy {
        ScoreBoardTemplates.ALL.map { t ->
            val bytes = ByteArray(48)
            for (i in 0 until 48) {
                bytes[i] = ((Character.digit(t.bits[i * 2], 16) shl 4) or
                    Character.digit(t.bits[i * 2 + 1], 16)).toByte()
            }
            val grid = LongArray(6)
            for (i in 0 until 384) {
                if ((bytes[i / 8].toInt() shr (7 - (i % 8))) and 1 == 1) {
                    grid[i / 64] = grid[i / 64] or (1L shl (i % 64))
                }
            }
            Tpl(t.digit, grid, t.aspect, t.holes, t.weight)
        }
    }

    internal class Tpl(val digit: Int, val grid: LongArray, val aspect: Float, val holes: Int, val weight: Float)

    internal fun countHoles(mask: BooleanArray, w: Int, h: Int, minHoleArea: Int = 1): Int {
        if (h < 3 || w < 3) return 0
        val inv = BooleanArray(mask.size) { !mask[it] }
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        fun flood(start: Int): Int {
            var head = 0; var tail = 0; var size = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val p = queue[head++]; size++
                val x = p % w; val y = p / w
                if (x > 0 && inv[p - 1] && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1 }
                if (x + 1 < w && inv[p + 1] && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1 }
                if (y > 0 && inv[p - w] && !seen[p - w]) { seen[p - w] = true; queue[tail++] = p - w }
                if (y + 1 < h && inv[p + w] && !seen[p + w]) { seen[p + w] = true; queue[tail++] = p + w }
            }
            return size
        }
        for (x in 0 until w) {
            if (inv[x] && !seen[x]) flood(x)
            if (inv[(h - 1) * w + x] && !seen[(h - 1) * w + x]) flood((h - 1) * w + x)
        }
        for (y in 0 until h) {
            if (inv[y * w] && !seen[y * w]) flood(y * w)
            if (inv[y * w + w - 1] && !seen[y * w + w - 1]) flood(y * w + w - 1)
        }
        var holes = 0
        for (i in inv.indices) {
            if (inv[i] && !seen[i]) {
                if (flood(i) >= minHoleArea) holes++
            }
        }
        return holes
    }

    internal class Norm(val grid: LongArray, val aspect: Float)

    internal fun normalizeGrid(mask: BooleanArray, w: Int, h: Int): Norm? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x]) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return null
        val sw = maxX - minX + 1
        val sh = maxY - minY + 1
        val aspect = sw.toFloat() / sh
        val grid = LongArray(6)
        for (gy in 0 until GRID_H) {
            val sy = minY + min(sh - 1, (gy * sh + sh / 2) / GRID_H)
            for (gx in 0 until GRID_W) {
                val sx = minX + min(sw - 1, (gx * sw + sw / 2) / GRID_W)
                if (mask[sy * w + sx]) {
                    val i = gy * GRID_W + gx
                    grid[i / 64] = grid[i / 64] or (1L shl (i % 64))
                }
            }
        }
        return Norm(grid, aspect)
    }

    internal fun classifyGlyph(mask: BooleanArray, w: Int, h: Int): GlyphRead {
        val norm = normalizeGrid(mask, w, h) ?: return GlyphRead(null, 0f, 0f)
        // ignore sub-digit pinholes: a JPEG-noise split of a '4' counter fakes
        // a second hole and reads '8'; real digit holes are >= h/4 pixels
        val holes = countHoles(mask, w, h, max(3, h / 4))
        // per-digit class features: a 16px '5' may close its bowl under
        // compression (holes 0->1) — that must not penalise the digit '5' if
        // any harvested '5' variant shows the same topology, but it MUST
        // penalise digits that never render that way.
        val digitHoles = HashMap<Int, MutableSet<Int>>()
        val digitAspect = HashMap<Int, Pair<Float, Float>>()
        for (t in templates) {
            digitHoles.getOrPut(t.digit) { mutableSetOf() }.add(t.holes)
            val cur = digitAspect[t.digit] ?: (Float.MAX_VALUE to -Float.MAX_VALUE)
            digitAspect[t.digit] = min(cur.first, t.aspect) to max(cur.second, t.aspect)
        }
        val perDigit = HashMap<Int, Float>()
        for (t in templates) {
            var inter = 0L; var union = 0L
            for (k in 0 until 6) {
                inter += java.lang.Long.bitCount(norm.grid[k] and t.grid[k])
                union += java.lang.Long.bitCount(norm.grid[k] or t.grid[k])
            }
            var sc = (if (union > 0) inter.toFloat() / union else 0f) * t.weight
            if (holes !in (digitHoles[t.digit] ?: emptySet())) sc *= 0.35f
            val (lo, hi) = digitAspect[t.digit] ?: (0f to 1f)
            if (norm.aspect < lo - 0.15f || norm.aspect > hi + 0.15f) sc *= 0.6f
            if (sc > (perDigit[t.digit] ?: -1f)) perDigit[t.digit] = sc
        }
        val ranked = perDigit.entries.sortedByDescending { it.value }
        val bd = ranked[0].key; val best = ranked[0].value
        val second = if (ranked.size > 1) ranked[1].value else 0f
        val margin = best - second
        if (best < 0.45f || margin < 0.02f) return GlyphRead(null, best, margin)
        return GlyphRead(bd, best, margin)
    }
}
