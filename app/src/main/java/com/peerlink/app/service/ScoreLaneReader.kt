package com.peerlink.app.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Geometry/token parsing separated from ML Kit so recognition rules have real JVM tests. */
internal object ScoreLaneReader {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val x get() = (left + right) / 2f
        val y get() = (top + bottom) / 2f
        val height get() = bottom - top
        val width get() = right - left
    }
    data class Token(val text: String, val box: Box)
    data class Reading(val home: Int, val away: Int, val region: Int, val source: String)
    private data class Candidate(val reading: Reading, val rank: Float)
    private data class Digit(val value: Int, val box: Box, val region: Int)
    private const val GLYPHS = "0-9OoQqDIilL|!ZzSsB"
    // A clock, decimal, or arbitrary word between digits is not a football score.
    private val pairPattern = Regex("^\\s*([$GLYPHS]{1,2})\\s*[-–—]\\s*([$GLYPHS]{1,2})\\s*$")

    private fun normalizeGlyph(raw: Char, box: Box? = null): Int? = when (raw) {
        in '0'..'9' -> raw.digitToInt()
        'O', 'o', 'Q', 'q', 'D' -> 0
        'I', 'i', 'l', 'L', '|', '!' -> 1
        'Z', 'z' -> 2
        'S', 's' -> if (box != null && box.height > 0f && box.width / box.height < 0.58f) 1 else 5
        'B' -> 8
        else -> null
    }

    fun normalizeToken(raw: String, box: Box? = null): Int? {
        val value = raw.trim()
        if (value.length !in 1..2) return null
        val normalized = value.map { normalizeGlyph(it, box) ?: return null }
            .joinToString("")
        return normalized.toIntOrNull()?.takeIf { it in 0..20 }
    }

    fun read(tokens: List<Token>, width: Int, topHeight: Int, gap: Int, referenceHeight: Int): Reading? {
        if (width <= 0 || topHeight <= 0 || gap < 0 || referenceHeight <= 0) return null
        val minHeight = max(9f, referenceHeight * 0.018f)
        fun region(box: Box): Int = when {
            box.top < 0 || box.right <= box.left || box.height <= 0 -> -1
            box.bottom <= topHeight -> 0
            box.top >= topHeight + gap -> 1
            else -> -1 // A box spanning the join is not a real score row.
        }
        val nonScoreSeparators = tokens.filter {
            it.text.trim() in setOf(":", ".", "/") || Regex("^\\s*\\d{1,2}\\s*[:./]\\s*\\d{1,2}\\s*$").matches(it.text)
        }
        val candidates = ArrayList<Candidate>()
        val digits = ArrayList<Digit>()
        for (token in tokens) {
            val box = token.box
            val band = region(box)
            if (band < 0 || box.height < minHeight || box.left < 0 || box.right > width) continue
            val center = box.x / width
            val pair = pairPattern.matchEntire(token.text)
            if (pair != null && center in 0.30f..0.70f && box.left < width * 0.48f &&
                box.right > width * 0.52f && box.right - box.left >= width * 0.12f) {
                val home = normalizeToken(pair.groupValues[1], box)
                val away = normalizeToken(pair.groupValues[2], box)
                if (home != null && away != null) {
                    candidates += Candidate(Reading(home, away, band, if (band == 0) "top_pair" else "bottom_pair"), box.height * 2f)
                }
            }
            // A digit-only OCR pass may return "01" or "22" as one token
            // spanning both cells. Split only when the bounding box actually
            // crosses the center; a two-digit score such as "10" stays inside
            // its own half and is therefore preserved as 10.
            val compact = token.text.trim()
            if (compact.length == 2 && box.left < width * 0.48f && box.right > width * 0.52f) {
                val home = normalizeGlyph(compact[0])
                val away = normalizeGlyph(compact[1])
                if (home != null && away != null) {
                    candidates += Candidate(
                        Reading(home, away, band, if (band == 0) "top_compact" else "bottom_compact"),
                        box.height * 2.1f,
                    )
                }
            }
            val value = normalizeToken(token.text, box) ?: continue
            if (center !in 0.12f..0.88f) continue
            digits += Digit(value, box, band)
        }
        // Pair aligned elements jointly. Choosing the largest element in each
        // half independently can let one unrelated numeral hide a valid score.
        for (left in digits) {
            if (left.box.x / width !in 0.15f..0.495f) continue
            for (right in digits) {
                if (right.region != left.region || right.box.x / width !in 0.505f..0.85f) continue
                val smaller = min(left.box.height, right.box.height)
                val bigger = max(left.box.height, right.box.height)
                val overlap = min(left.box.bottom, right.box.bottom) - max(left.box.top, right.box.top)
                val dy = abs(left.box.y - right.box.y)
                if (overlap < smaller * 0.5f || smaller / bigger < 0.65f || dy > bigger * 0.75f) continue
                if (nonScoreSeparators.any { token ->
                    token.box.x in left.box.x..right.box.x &&
                        token.box.y in max(left.box.top, right.box.top)..min(left.box.bottom, right.box.bottom)
                }) continue
                candidates += Candidate(Reading(left.value, right.value, left.region,
                    if (left.region == 0) "top_digits" else "bottom_digits"), smaller * 2f - dy)
            }
        }
        val ordered = candidates.sortedByDescending { it.rank }
        val best = ordered.firstOrNull() ?: return null
        // Do not select the first of two equally plausible but conflicting rows.
        if (ordered.any { it.rank >= best.rank * 0.9f &&
                (it.reading.home != best.reading.home || it.reading.away != best.reading.away) }) return null
        return best.reading
    }
}
