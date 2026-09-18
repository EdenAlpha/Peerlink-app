#!/usr/bin/env python3
"""F34 structure-first score reader — Python reference for the Kotlin port.

DESIGN CONTRACT (what makes this device-independent, unlike F32/F33):

  1. NO FIXED SCREEN WINDOWS. Nothing in this file trusts a screen fraction
     for WHERE an element sits. The score boxes, the label strip, the table
     rows, the value tokens and the menu glyphs are all FOUND by structural
     search over the full frame, then every sub-window is derived from what
     was found (anchor-relative geometry).

  2. ADAPTIVE COLOUR. The UI-yellow hue is estimated per frame from the
     frame's own saturated-warm hue distribution (saturation-weighted median).
     Masks are centred on that estimate with generous absolute guard rails
     ("is it still recognisably yellow at all"), so panel calibration shifts,
     vivid-mode oversaturation and eye-comfort dimming are absorbed.

  3. INK = LOCAL CONTRAST, NOT ABSOLUTE COLOUR. Digits inside a yellow box
     are "significantly darker than the box", thresholded from the box's own
     value distribution — never from a global RGB constant.

  4. AUTHORED-SCALE BOUNDS ONLY. Size gates bound what the game UI can
     render relative to frame height (the UI scales with display height on
     every device). Gates are 3-6x wider than any measured device, so they
     encode "a scoreboard", never "this phone".

  5. FAIL-SAFE. Any ambiguity refuses the read. A refused read is never a
     wrong read; callers fall back to the slow OCR chain.

Primitives mirror the Kotlin 1:1: components / mergeFragments / splitWide /
normalizeGrid / countHoles / classifyGlyph / classifyFH.
"""
import math
import os
import re
import sys

# --- tunable primitives (grid-searched over the robustness objective) ---
INK_V_CEIL = float(os.environ.get('F34_INK_V_CEIL', '0.55'))
INK_LEAN = int(os.environ.get('F34_INK_LEAN', '20'))

# F35 additions (each placed in a measured gap; see the notes at each site):
# blue-free refinement of the yellow-hue pool: UI yellow carries no blue
# channel (b/g <= 0.06 even under JPEG bleed), floodlit grass >= 0.24
BLUE_FREE_BG_RATIO = 0.15
# table glyph identity floor in px: below ~9px a digit carries no identity
# (measured confusion onset); the table refuses rather than guesses
STATS_PIECE_FLOOR = 9
# a solid blob (score box / strip) fills its bbox 0.86-0.92; digit strokes
# fill 0.54-0.60 (measured on menu clusters). The guard sits mid-gap.
MENU_SOLID_FILL = 0.72
# F36 constants (basin-checked: tests/evidence/f36_parameter_basin.txt)
BOX_YGAP_FRAC = 0.20     # read_box_value glue window (fraction of box height)
LOCAL_YGAP_FRAC = 0.25   # token_value_local glue window (fraction of token height)
XJOIN_FRAC = 0.15        # merge_fragments x-adjacency window (of fragment height)
THIN_ASPECT = 0.28       # thin-glyph-sized-piece reroute bar in token_value_local

import numpy as np
from PIL import Image
from scipy import ndimage

SRC_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IMG_DIR = os.environ.get("F34_CAPTURES", "/home/z/my-project/work/f32/PeerLink-F32")
TPL_KT = os.environ.get("F34_TPL", os.path.join(SRC_DIR, "app/src/main/java/com/peerlink/app/service/ScoreBoardTemplates.kt"))

STAT_NAMES = ["Possession", "TotalShots", "ShotsOnTarget", "Fouls", "Offsides",
              "CornerKicks", "FreeKicks", "Passes", "SuccessfulPasses", "Crosses",
              "Interceptions", "Tackles", "Saves"]

GRID_W, GRID_H = 16, 24


# ----------------------------------------------------------------------
# Small box type (inclusive corners, like the Kotlin Box).
# ----------------------------------------------------------------------
class Box:
    __slots__ = ("x0", "y0", "x1", "y1", "area", "th", "quality")

    def __init__(self, x0, y0, x1, y1, area):
        self.x0, self.y0, self.x1, self.y1, self.area = x0, y0, x1, y1, area

    @property
    def w(self):
        return self.x1 - self.x0 + 1

    @property
    def h(self):
        return self.y1 - self.y0 + 1

    @property
    def cx(self):
        return (self.x0 + self.x1) / 2.0

    @property
    def cy(self):
        return (self.y0 + self.y1) / 2.0

    def __repr__(self):
        return f"Box({self.x0},{self.y0},{self.x1},{self.y1},a={self.area})"


# ----------------------------------------------------------------------
# Colour: HSV + adaptive yellow hue + masks.
# ----------------------------------------------------------------------
def hsv_arrays(a):
    r = a[:, :, 0].astype(np.float32)
    g = a[:, :, 1].astype(np.float32)
    b = a[:, :, 2].astype(np.float32)
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    d = mx - mn
    h = np.zeros_like(mx)
    m = d > 0
    rm = m & (mx == r)
    gm = m & (mx == g) & ~rm
    bm = m & (mx == b) & ~gm & ~rm
    h[rm] = (60 * ((g[rm] - b[rm]) / d[rm])) % 360
    h[gm] = 60 * ((b[gm] - r[gm]) / d[gm]) + 120
    h[bm] = 60 * ((r[bm] - g[bm]) / d[bm]) + 240
    s = np.where(mx > 0, d / np.maximum(mx, 1e-9), 0)
    v = mx / 255.0
    return h, s, v, d


def estimate_yellow_hue(h, s, v, chroma, rgb=None):
    """Estimate the UI-yellow hue from the frame itself. Pool = warm hues with
    high ABSOLUTE CHROMA (max-min ~110+): rendered eFootball yellow has chroma
    150-230 while the strongest greens/grass on real pitches stay below ~90
    even on vivid floodlit panels, so chroma — not saturation ratio — is the
    calibration-robust separator. Guard rails are absolute but generous: if
    the dominant strong-chroma warm hue is not recognisably yellow (30..90
    deg) the frame cannot be a score presentation on any sane panel -> refuse.

    F35 blue-free refinement: on vivid panels a floodlit pitch close-up can
    form a TIGHT high-chroma spike near 90-95 deg (yellow-green) that
    out-weighs the UI yellow in total chroma mass (measured 25.1M vs 14.3M
    on one capture), dragging the mode out of the yellow guard and refusing
    the whole frame. Yellow is spectrally the ABSENCE of blue: b/g <= 0.06
    for UI yellow even under JPEG chroma bleed, while that grass spike keeps
    b/g >= 0.24. Restricting the mode to blue-free pixels makes the estimate
    area-independent - a pitch cannot out-vote the scoreboard."""
    pool = (chroma >= 110) & (v >= 0.35) & (h >= 20.0) & (h <= 100.0)
    if int(pool.sum()) < 150:
        return None
    if rgb is not None:
        b = rgb[:, :, 2].astype(np.float32)
        g = rgb[:, :, 1].astype(np.float32)
        sub = pool & (b <= BLUE_FREE_BG_RATIO * np.maximum(g, 1.0))
        if int(sub.sum()) >= 150:
            pool = sub
    hh, cc = h[pool], chroma[pool]
    # chroma-weighted MODE of the hue: UI yellow is one spectrally tight
    # cluster and concentrates in a single 5-degree bin; vivid floodlit grass
    # may carry more TOTAL chroma but it spreads across many bins and never
    # wins one. A plain median gets dragged by the grass mass; the mode not.
    nb = int((100 - 20) // 5) + 1
    idx = np.clip(((hh - 20.0) // 5.0).astype(int), 0, nb - 1)
    w = np.bincount(idx, weights=cc, minlength=nb)
    peak = int(np.argmax(w))
    sel = (hh >= 20 + peak * 5 - 10) & (hh <= 20 + peak * 5 + 15)
    if int(sel.sum()) < 50:
        return None
    hue_y = float(np.median(hh[sel]))
    if not (30.0 <= hue_y <= 90.0):
        return None
    return hue_y


class Masks:
    def __init__(self, rgb):
        self.rgb = rgb
        self.h, self.s, self.v, self.chroma = hsv_arrays(rgb)
        hy = estimate_yellow_hue(self.h, self.s, self.v, self.chroma, rgb)
        self.hue_y = hy
        if hy is None:
            self.strict = np.zeros(self.h.shape, dtype=bool)
            self.loose = np.zeros(self.h.shape, dtype=bool)
        else:
            dh = np.abs(((self.h - hy + 180.0) % 360.0) - 180.0)
            self.strict = (dh <= 24) & (self.chroma >= 95) & (self.v >= 0.42)
            self.loose = (dh <= 32) & (self.chroma >= 60) & (self.v >= 0.30)


# ----------------------------------------------------------------------
# Connected components (scipy here; flood-fill in Kotlin).
# ----------------------------------------------------------------------
def cc(mask, min_area, dx=0, dy=0):
    lab, n = ndimage.label(mask)
    if n == 0:
        return []
    areas = np.bincount(lab.ravel())
    objs = ndimage.find_objects(lab)
    out = []
    for i, sl in enumerate(objs, start=1):
        if sl is None:
            continue
        area = int(areas[i])
        if area < min_area:
            continue
        out.append(Box(sl[1].start + dx, sl[0].start + dy,
                       sl[1].stop - 1 + dx, sl[0].stop - 1 + dy, area))
    return out


def close3(mask):
    """3x3 binary closing (cross structuring element): fills 1-2px pinholes
    and potholes inside strokes that JPEG mosquito noise punches into thin
    glyphs, without moving the glyph boundary."""
    m = mask
    d = m.copy()
    d[1:, :] |= m[:-1, :]
    d[:-1, :] |= m[1:, :]
    d[:, 1:] |= m[:, :-1]
    d[:, :-1] |= m[:, 1:]
    e = d.copy()
    e[1:, :] &= d[:-1, :]
    e[:-1, :] &= d[1:, :]
    e[:, 1:] &= d[:, :-1]
    e[:, :-1] &= d[:, 1:]
    return e | m


def close_v(mask):
    """3-tap vertical closing (dilate then erode along y). JPEG mosquito
    noise cuts 1-2px-tall horizontal cracks in thin strokes (a '0' whose
    shoulder cracks open counts TWO holes and misreads as '8'). Vertical
    closing fills those cracks and cannot bridge the full-height x-gaps
    between neighbouring digits, so "11" still splits by empty columns."""
    m = mask
    d = m.copy()
    d[1:, :] |= m[:-1, :]
    d[:-1, :] |= m[1:, :]
    e = d.copy()
    e[1:, :] &= d[:-1, :]
    e[:-1, :] &= d[1:, :]
    return e | m


def merge_fragments(comps, ygap, max_w, max_h, xjoin=None):
    """F36 proximity gluing (replaces the F34/F35 50%-overlap rule).

    Fragments of ONE glyph split at a thin joint (a '3' loses its backbone
    under compression: top curl + spine + bottom curl) sit stacked with a
    small gap and x-spans that overlap or nearly touch. The old rule required
    the horizontal overlap to exceed 50% of the narrower piece's width; on
    renders where the curls overhang a narrow spine (measured on a second
    device: a real 3-1 board read 1-1 because the curls failed the bar, the
    height floors then kept a spine-shaped remainder) the glue refused and
    the glyph misread or refused.

    Rule: vertically close (gap <= ygap) AND horizontally overlapping
    (ov > 0) or horizontally adjacent within xjoin px (default: derived from
    the fragments' own height, 0.15*h -- no frame-size constant). Distinct
    side-by-side digits stay safe: even when two thin digits fuse here
    (x-gap < xjoin), split_wide re-splits them downstream exactly as before;
    max_w/max_h still cap every union; every piece still passes the
    unchanged confidence gates, so a bad glue ends in refusal, never a
    guess. Runs to a fixpoint so multi-way splits reassemble regardless of
    which side the break lands on."""
    if xjoin is None:
        def xj(c, g):
            return max(1, int(XJOIN_FRAC * max(c.h, g.h)))
    else:
        def xj(c, g):
            return xjoin
    comps = sorted(comps, key=lambda c: (c.y0, c.x0))
    out = []
    changed = True
    while changed:
        changed = False
        out = []
        for c in comps:
            target = None
            for g in out:
                ov = min(c.x1, g.x1) - max(c.x0, g.x0) + 1
                gap = max(c.y0 - g.y1, g.y0 - c.y1)
                if gap > ygap:
                    continue
                xgap = max(c.x0 - g.x1, g.x0 - c.x1)
                if ov <= 0 and xgap > xj(c, g):
                    continue
                if (max(c.x1, g.x1) - min(c.x0, g.x0) + 1) > max_w:
                    continue
                if (max(c.y1, g.y1) - min(c.y0, g.y0) + 1) > max_h:
                    continue
                target = g
                break
            if target is not None:
                target.x0 = min(target.x0, c.x0)
                target.x1 = max(target.x1, c.x1)
                target.y0 = min(target.y0, c.y0)
                target.y1 = max(target.y1, c.y1)
                target.area += c.area
                changed = True
            else:
                out.append(Box(c.x0, c.y0, c.x1, c.y1, c.area))
        comps = [Box(c.x0, c.y0, c.x1, c.y1, c.area) for c in out]
        if not changed:
            break
    return out


def split_wide(mask, max_w):
    """Split a glyph cluster into digit pieces. Empty columns split FIRST,
    always — a digit never contains a full-height empty column, so this is
    safe at any width ("11" is two 6px glyphs whose union is only as wide as
    one fused digit). Only pieces still wider than max_w with no empty
    column go to guided valley cuts (fused digits like a touching "34")."""
    hh, ww = mask.shape
    col = mask.sum(axis=0)
    segs = []
    start, gap = None, 0
    for x in range(ww):
        if col[x] > 0:
            if start is None:
                start = x
            gap = 0
        elif start is not None:
            gap += 1
            if gap >= 1 and (x - gap) - start + 1 >= 2:
                segs.append((start, x - gap))
            if gap >= 1:
                start = None
    if start is not None and ww - start >= 2:
        segs.append((start, ww - 1))
    if len(segs) > 1:
        return [mask[:, a:b + 1] for a, b in segs]
    if ww <= max_w:
        return [mask]
    # fused glyphs ("34" rendered touching): no empty column exists, so cut
    # at low-ink valleys. Candidates are scored by recognition — the cut that
    # makes BOTH halves read as digits wins; if none does, the pieces fail
    # downstream (fail-safe), never silently misread.
    out, s = [], 0
    while ww - s > max_w:
        lo = s + max(1, int(0.20 * max_w))
        hi = min(ww - 1, s + int(0.80 * (ww - s)))
        cand = sorted(range(lo, hi + 1), key=lambda x: col[x])[:5]
        best_cut, best_score = None, -1.0
        for cut in cand:
            if cut - s < 2 or ww - cut < 2:
                continue
            score = 0.0
            ok = True
            for piece_m in (mask[:, s:cut], mask[:, cut:ww]):
                d, conf, _mg = classify_glyph(piece_m)
                if d is None or conf < 0.5:
                    ok = False
                    break
                score += conf
            if ok and score > best_score:
                best_score, best_cut = score, cut
        if best_cut is None:
            best_cut = min(cand, key=lambda x: col[x])
        if best_cut - s <= 0:
            break
        out.append(mask[:, s:best_cut])
        s = best_cut
    if s < ww:
        out.append(mask[:, s:])
    return out


# ----------------------------------------------------------------------
# Glyph classifier — frozen template bank parsed from ScoreBoardTemplates.kt
# (pure data; scale/position invariant by construction).
# ----------------------------------------------------------------------
def parse_templates():
    src = open(TPL_KT).read()
    tpl = []
    # weight field optional for backward compatibility with older banks
    for m in re.finditer(r'T\((\d+),\s*([\d.]+)f,\s*(\d+)(?:,\s*([\d.]+)f)?,\s*"([0-9a-fA-F]+)"\)', src):
        digit, aspect, holes, bits = int(m.group(1)), float(m.group(2)), int(m.group(3)), m.group(5)
        weight = float(m.group(4)) if m.group(4) else 1.0
        by = bytes.fromhex(bits)
        grid = np.zeros((GRID_H, GRID_W), dtype=bool)
        for i in range(384):
            if (by[i // 8] >> (7 - (i % 8))) & 1:
                grid[i // GRID_W, i % GRID_W] = True
        tpl.append((digit, grid, aspect, holes, weight))
    return tpl


TEMPLATES = None
_DIGIT_FEATS = None


def count_holes(mask, min_hole_area=1):
    """Enclosed background regions. min_hole_area ignores JPEG-noise pinholes
    (a q60 'H' grows 1-4px holes that would classify it as a clock digit)."""
    hh, ww = mask.shape
    if hh < 3 or ww < 3:
        return 0
    inv = ~mask
    seen = np.zeros_like(inv)
    stack = []
    for x in range(ww):
        for y in (0, hh - 1):
            if inv[y, x] and not seen[y, x]:
                seen[y, x] = True
                stack.append((y, x))
    for y in range(hh):
        for x in (0, ww - 1):
            if inv[y, x] and not seen[y, x]:
                seen[y, x] = True
                stack.append((y, x))
    while stack:
        y, x = stack.pop()
        for ny, nx in ((y, x - 1), (y, x + 1), (y - 1, x), (y + 1, x)):
            if 0 <= ny < hh and 0 <= nx < ww and inv[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                stack.append((ny, nx))
    holes = 0
    for sy in range(hh):
        for sx in range(ww):
            if inv[sy, sx] and not seen[sy, sx]:
                stack = [(sy, sx)]
                seen[sy, sx] = True
                size = 0
                while stack:
                    y, x = stack.pop()
                    size += 1
                    for ny, nx in ((y, x - 1), (y, x + 1), (y - 1, x), (y + 1, x)):
                        if 0 <= ny < hh and 0 <= nx < ww and inv[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            stack.append((ny, nx))
                if size >= min_hole_area:
                    holes += 1
    return holes


def normalize_grid(mask):
    ys, xs = np.where(mask)
    if len(ys) == 0:
        return None, 0.0
    mm = mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    aspect = mm.shape[1] / mm.shape[0]
    img = Image.fromarray((mm * 255).astype(np.uint8)).resize((GRID_W, GRID_H), Image.BILINEAR)
    return np.asarray(img) > 100, aspect


def classify_glyph(mask):
    """Returns (digit|None, confidence, margin_to_second). Never guesses: low
    confidence or a small margin to the runner-up returns None.

    Hole/aspect evidence is applied PER DIGIT CLASS, not per template: a 16px
    '5' may close its bowl under compression (holes 0->1) — that must not
    penalise the digit '5' if any harvested '5' variant shows a closed bowl,
    but it MUST penalise digits that never render with a hole there."""
    global TEMPLATES, _DIGIT_FEATS
    if TEMPLATES is None:
        TEMPLATES = parse_templates()
    if _DIGIT_FEATS is None:
        fh = {}
        fa = {}
        for (digit, tg, tasp, tholes, _w) in TEMPLATES:
            fh.setdefault(digit, set()).add(tholes)
            lo, hi = fa.get(digit, (99.0, 0.0))
            fa[digit] = (min(lo, tasp), max(hi, tasp))
        _DIGIT_FEATS = (fh, fa)
    digit_holes, digit_aspect = _DIGIT_FEATS
    g, aspect = normalize_grid(mask)
    if g is None:
        return None, 0.0, 0.0
    holes = count_holes(mask)
    per_digit = {}
    for (digit, tg, tasp, tholes, tw) in TEMPLATES:
        inter = np.logical_and(g, tg).sum()
        union = np.logical_or(g, tg).sum()
        sc = (inter / union if union else 0.0) * tw
        if holes not in digit_holes[digit]:
            sc *= 0.35             # this digit never has this hole topology
        lo, hi = digit_aspect[digit]
        if not (lo - 0.15 <= aspect <= hi + 0.15):
            sc *= 0.6              # outside every observed aspect of the digit
        if sc > per_digit.get(digit, -1.0):
            per_digit[digit] = sc
    ranked = sorted(per_digit.items(), key=lambda kv: -kv[1])
    bd, best = ranked[0]
    second = ranked[1][1] if len(ranked) > 1 else 0.0
    margin = float(best - second)
    if best < 0.45 or margin < 0.02:
        return None, float(best), margin
    return bd, float(best), margin


def classify_fh(mask):
    """F / H / closed-glyph classifier on glyph-internal fractions only
    (scale-free). F -> FULL_TIME, H -> HALF_TIME, digit-like -> CLOCK."""
    hh, ww = mask.shape
    if hh < 8 or ww < 3:
        return "UNKNOWN"
    holes = count_holes(mask, min_hole_area=max(4, (hh // 8) * (hh // 8)))
    if holes >= 1:
        return "CLOCK"
    q = max(1, ww // 4)
    left = mask[:, :q]
    col_l = left.mean()
    r0 = 2 * q
    # bottom quarter: F's crossbar sits at ~0.45-0.55h on every rendering, so
    # a window starting at 0.55h swallowed it on 10-14px glyphs and read F's
    # own bar as an H stem (fin=UNKNOWN at 0.75x). The bottom quarter is
    # unambiguous: F empty, H stem 0.35-0.5+ even on small glyphs.
    lower = mask[int(0.70 * hh):, r0:]
    col_rl = lower.mean() if lower.size else 0.0
    top = mask[:max(1, hh // 5), :]
    row_t = top.mean()
    # F vs H separate cleanly in data: F's lower-right is empty (0.0-0.1),
    # H's right stem fills 0.35-0.5 of the lower-right region even on narrow
    # small glyphs (a 0.45 gate measured 0.43 on a real 11px 'H' and failed)
    if col_l > 0.30 and col_rl < 0.25 and row_t > 0.40:
        return "FULL_TIME"
    # H top = stems only: measured 0.5-0.67 (a 9px-wide H with 3px stems is
    # 0.67), F's full top bar = ~1.0; the gate sits mid-gap at 0.80
    if col_l > 0.30 and col_rl > 0.25 and row_t < 0.80:
        return "HALF_TIME"
    return "UNKNOWN"


# ----------------------------------------------------------------------
# Stage A: find the score-box pair ANYWHERE in the frame (structural).
# ----------------------------------------------------------------------
def otsu_threshold(vals, nbins=64):
    """Otsu split over [0,1] values. Returns (t, ok): ok=False when the
    histogram is effectively unimodal (no meaningful two-class split).
    Parameter-free: the threshold comes from the window's own distribution,
    which is what makes it survive panel calibration shifts."""
    hist, edges = np.histogram(vals, bins=nbins, range=(0.0, 1.0))
    hist = hist.astype(np.float64)
    total = hist.sum()
    if total <= 0:
        return 0.5, False
    p = hist / total
    centers = (edges[:-1] + edges[1:]) / 2
    w0 = np.cumsum(p)
    w1 = 1 - w0
    mu_t = np.sum(p * centers)
    mu_k = np.cumsum(p * centers)
    with np.errstate(divide="ignore", invalid="ignore"):
        mu0 = mu_k / np.maximum(w0, 1e-12)
        mu1 = (mu_t - mu_k) / np.maximum(w1, 1e-12)
        sigma_b = w0 * w1 * (mu0 - mu1) ** 2
    sigma_b[~np.isfinite(sigma_b)] = 0
    k = int(np.argmax(sigma_b))
    if w0[k] < 0.03 or w1[k] < 0.03:      # a real class must hold real mass
        return float(centers[k]), False
    return float(centers[k]), True


def box_ink_ref(m, box):
    """F35: self-derived ink reference from the box's own v-distribution.

    Returns (t_otsu, t_deep, hue_ink, bg_v, bg_chroma) or None.

    The F34 model (th = min(0.55, med-0.30), single hue-family gate) broke
    under night-shift + JPEG q60: 4:2:0 chroma subsampling bleeds the
    surrounding yellow into a THIN navy stroke, the stroke's own chroma
    becomes a yellow-navy mix, and the hue-family gate then rejects the
    digit's own pixels while the ink-hue reference itself (median over a
    contaminated set) collapsed to the yellow hue. Measured across every
    panel/compression transform, LUMA is the invariant: ink v sits 0.14-0.54
    while the yellow background sits at 0.80-1.00 - always a wide gap.

    So the ink threshold is now Otsu's split of the box's bimodal v
    histogram (parameter-free), with two zones:
      deep zone  (v <= t_deep): certainly ink - luma alone decides
      ambiguous  (t_deep..t_otsu): ink unless it carries a strong-yellow
                 signature (chroma >= 0.60 x bg, hue within 45 deg of the
                 frame's yellow, and no blue lean) - that is the halo band
    t_deep = p02 + 0.25*(t_otsu - p02) keeps the deep floor inside the ink
    mass even when blur dilutes the dark tail."""
    win_v = m.v[box.y0:box.y1 + 1, box.x0:box.x1 + 1]
    strict_mask = m.strict[box.y0:box.y1 + 1, box.x0:box.x1 + 1]
    if not strict_mask.any() or win_v.size == 0:
        return None
    bg_v = float(np.median(win_v[strict_mask]))
    p02 = float(np.percentile(win_v, 2))
    t_otsu, ok = otsu_threshold(win_v.ravel())
    if not ok:
        t_otsu = p02 + 0.45 * max(0.05, bg_v - p02)   # unimodal fallback
    t_deep = p02 + 0.25 * max(0.0, t_otsu - p02)
    bg_chroma = float(np.median(m.chroma[box.y0:box.y1 + 1, box.x0:box.x1 + 1][strict_mask]))
    deep_m = win_v <= t_deep
    hues = m.h[box.y0:box.y1 + 1, box.x0:box.x1 + 1][deep_m]
    hue_ink = float(np.median(hues)) if int(deep_m.sum()) >= 6 else 240.0
    return t_otsu, t_deep, hue_ink, bg_v, bg_chroma


def ink_mask(m, y0, y1, x0, x1, ref):
    """F35 two-zone digit ink from a box_ink_ref. See box_ink_ref for the
    rationale: luma decides in the deep zone; chroma only rejects the
    strong-yellow halo band in the ambiguous zone."""
    t_otsu, t_deep, _hue_ink, _bg_v, bg_chroma = ref
    v = m.v[y0:y1 + 1, x0:x1 + 1]
    rgb = m.rgb[y0:y1 + 1, x0:x1 + 1].astype(np.int16)
    b = rgb[:, :, 2]
    r = rgb[:, :, 0]
    g = rgb[:, :, 1]
    ch = m.chroma[y0:y1 + 1, x0:x1 + 1]
    hu = m.h[y0:y1 + 1, x0:x1 + 1]
    lean = (b + INK_LEAN >= r) & (b + INK_LEAN >= g)
    deep = v <= t_deep
    amb = (v > t_deep) & (v <= t_otsu)
    dh_y = np.abs(((hu - m.hue_y + 180.0) % 360.0) - 180.0)
    yellow_strong = (ch >= 0.60 * max(1.0, bg_chroma)) & (dh_y <= 45.0)
    amb_ok = amb & ~yellow_strong & (lean | (ch <= 25))
    return deep | amb_ok


def box_digit_evidence(m, box):
    """A score box must contain dark glyph-shaped ink. Returns
    (ok, ink_frac, n_glyphs) — used both as candidate filter and pair score."""
    ref = box_ink_ref(m, box)
    if ref is None:
        return False, 0.0, 0
    ix0 = box.x0 + int(0.14 * box.w)
    ix1 = box.x1 - int(0.14 * box.w)
    iy0 = box.y0 + int(0.14 * box.h)
    iy1 = box.y1 - int(0.14 * box.h)
    if ix1 - ix0 < 4 or iy1 - iy0 < 4:
        return False, 0.0, 0
    ink = close_v(ink_mask(m, iy0, iy1, ix0, ix1, ref))
    frac = float(ink.mean())
    if frac < 0.05 or frac > 0.62:
        return False, frac, 0
    comps = cc(ink, min_area=6, dx=ix0, dy=iy0)
    glyphs = [c for c in comps if 0.25 * box.h <= c.h <= 0.95 * box.h]
    if not glyphs:
        return False, frac, 0
    # A solid box contains SMALL stroke components (digit strokes). A yellow
    # GLYPH masquerading as a box (menu clock digit, table digit) contains one
    # huge background component spanning most of its bbox — reject it.
    box_area = (box.w - 2 * int(0.14 * box.w)) * (box.h - 2 * int(0.14 * box.h))
    for g in glyphs:
        if g.area > 0.30 * max(1, box_area):
            return False, frac, 0
    # 8-point solidity test: a real score box is a FILLED rounded rectangle —
    # yellow at its four bbox corners AND four edge midpoints. Every digit
    # glyph fails at least one of the eight samples ('0'/'8' have dark
    # corners, '4' has a dark left edge, '1' has dark left/right edges, the
    # ':' colon has dark everything). This is what stops result-menu clock
    # digits from pairing up as a scoreline on degraded frames.
    cs = max(3, int(0.22 * min(box.w, box.h)))
    strict = m.strict
    Hf, Wf = strict.shape
    # sample squares anchored 1px INSIDE the box: the JPEG halo lives on the
    # yellow/navy boundary row and would fail edge-exact sampling, while a
    # digit glyph's dark corners stay dark either way
    bx0, by0 = box.x0 + 1, box.y0 + 1
    bx1, by1 = box.x1 - 1, box.y1 - 1

    def sample(cx0, cy0):
        cx0 = max(0, min(cx0, Wf - cs))
        cy0 = max(0, min(cy0, Hf - cs))
        return float(strict[cy0:cy0 + cs, cx0:cx0 + cs].mean()) >= 0.45

    my, mx = (box.y0 + box.y1) // 2, (box.x0 + box.x1) // 2
    probes = (
        (bx0, by0), (bx1 - cs, by0), (bx0, by1 - cs), (bx1 - cs, by1 - cs),
        (bx0, my - cs // 2), (bx1 - cs, my - cs // 2),
        (mx - cs // 2, by0), (mx - cs // 2, by1 - cs),
    )
    for cx0, cy0 in probes:
        if not sample(cx0, cy0):
            return False, frac, 0
    return True, frac, len(glyphs)


def blob_sub_boxes(m, b):
    """Sub-box extraction inside a wide low-fill strict component.

    A translucent banner (boxes joined by a dim bar) can fuse into ONE strict
    component after downscale + JPEG chroma bleed; its aspect then fails the
    box gate and the scoreline is lost. The boxes are still recoverable from
    the component's own geometry: per-column vertical runs are tall and equal
    at box columns, short at bar/label columns, and the glyph holes punch
    narrow gaps between a box's own walls. Both scales come from the profile
    itself (p90 run height, median wall width) - no absolute constants.
    """
    sub = m.strict[b.y0:b.y1 + 1, b.x0:b.x1 + 1]
    ww = sub.shape[1]
    runs = np.zeros(ww, dtype=int)
    tops = np.zeros(ww, dtype=int)
    for x in range(ww):
        col = sub[:, x]
        ys = np.where(col)[0]
        if len(ys) == 0:
            continue
        best_len, best_s, s = 0, 0, None
        for y in range(len(col)):
            if col[y] and s is None:
                s = y
            elif not col[y] and s is not None:
                if y - s > best_len:
                    best_len, best_s = y - s, s
                s = None
        if s is not None and len(col) - s > best_len:
            best_len, best_s = len(col) - s, s
        runs[x] = best_len
        tops[x] = best_s
    nz = runs[runs > 0]
    if len(nz) < 8:
        return []
    run_h = float(np.percentile(nz, 90))
    if run_h < 12:
        return []
    tall = runs >= 0.55 * run_h
    groups = []
    s, gap = None, 0
    for x in range(ww):
        if tall[x]:
            if s is None:
                s = x
            gap = 0
        elif s is not None:
            gap += 1
            if gap > 0:
                groups.append((s, x - gap))
                s, gap = None, 0
    if s is not None:
        groups.append((s, ww - 1))
    if not groups:
        return []
    med_w = float(np.median([gb - ga + 1 for ga, gb in groups]))
    # a box's own glyph hole is narrow relative to its walls; the space
    # between two boxes is wide (>= 2x a wall). Bridge the former, split the latter.
    bridge = 1.5 * med_w
    merged = []
    for ga, gb in groups:
        if merged and ga - merged[-1][1] <= bridge:
            merged[-1] = (merged[-1][0], gb)
        else:
            merged.append((ga, gb))
    out = []
    for ga, gb in merged:
        if gb - ga + 1 < max(10, int(0.5 * run_h)):
            continue
        # y extent from the tallest columns of the group
        seg_runs = runs[ga:gb + 1]
        seg_tops = tops[ga:gb + 1]
        i = int(np.argmax(seg_runs))
        y0 = b.y0 + int(seg_tops[i])
        y1 = y0 + int(seg_runs[i]) - 1
        nb = Box(b.x0 + ga, y0, b.x0 + gb, y1, 0)
        nb.area = int(m.strict[nb.y0:nb.y1 + 1, nb.x0:nb.x1 + 1].sum())
        out.append(nb)
    return out


def find_score_boxes(m, W, H):
    """Search the WHOLE frame for two yellow digit boxes. No window, no
    position prior: candidates are filtered by shape, solidity and
    dark-glyph evidence, then validated by actually reading them.
    Size gates are authored-scale bounds: the game UI scales with display
    height on every device, and a human-readable score box occupies roughly
    1.5-9% of frame height, so the gate is set 2-6x wider than that."""
    min_area = max(30, int(0.00005 * W * H))
    comps = cc(m.strict, min_area)
    cands = []
    extra_blobs = []
    for b in comps:
        if b.h < max(12, 0.012 * H) or b.h > 0.15 * H:
            continue
        if b.w > 0.90 * W:
            continue
        asp = b.w / b.h
        if not (0.55 <= asp <= 2.8):
            # a fused banner (boxes + translucent connecting bar) is wide and
            # low-fill; recover the boxes from its own run profile
            if asp > 2.8 and b.area / (b.w * b.h) < 0.65:
                extra_blobs.append(b)
            continue
        if b.area / (b.w * b.h) < 0.52:
            continue
        ok, frac, nglyphs = box_digit_evidence(m, b)
        if ok:
            b.th = box_ink_ref(m, b)[0]
            b.quality = frac * math.sqrt(b.area) * (1 + 0.1 * nglyphs)
            cands.append(b)
    for blob in extra_blobs:
        for b in blob_sub_boxes(m, blob):
            if b.h < max(12, 0.012 * H) or b.h > 0.15 * H or b.w > 0.90 * W:
                continue
            asp = b.w / b.h
            if not (0.55 <= asp <= 2.8) or b.area / (b.w * b.h) < 0.52:
                continue
            ok, frac, nglyphs = box_digit_evidence(m, b)
            if ok:
                b.th = box_ink_ref(m, b)[0]
                b.quality = frac * math.sqrt(b.area) * (1 + 0.1 * nglyphs)
                cands.append(b)
    # candidate pairs: similar height, same band, facing gap coherent with a
    # scoreline (digits never touch; a logo may sit between them)
    pairs = []
    for i in range(len(cands)):
        for j in range(i + 1, len(cands)):
            A, B = cands[i], cands[j]
            hmax = max(A.h, B.h)
            if abs(A.h - B.h) > 0.35 * hmax:
                continue
            if abs(A.cy - B.cy) > 0.35 * hmax:
                continue
            gap = max(A.x0 - B.x1, B.x0 - A.x1)
            if gap < 0.30 * min(A.w, B.w) or gap > 2.5 * min(A.w, B.w):
                continue
            # the real scoreline is always the LARGEST digit-box pair on the
            # screen (table digits, letters and icons are far smaller)
            pairs.append((min(A.area, B.area), A.quality + B.quality, A, B))
    pairs.sort(key=lambda p: (-p[0], -p[1]))
    return [(a, b) for _, _, a, b in pairs]


# ----------------------------------------------------------------------
# Stage B: read a number out of one yellow box (local-contrast ink).
# ----------------------------------------------------------------------
def read_box_value(m, box, H, min_conf=0.45, min_margin=0.02):
    ix0 = box.x0 + int(0.14 * box.w)
    ix1 = box.x1 - int(0.14 * box.w)
    iy0 = box.y0 + int(0.14 * box.h)
    iy1 = box.y1 - int(0.14 * box.h)
    if ix1 - ix0 < 4 or iy1 - iy0 < 4:
        return None
    ref = box_ink_ref(m, box)
    if ref is None:
        return None
    ink = close_v(ink_mask(m, iy0, iy1, ix0, ix1, ref))
    if ink.sum() < 10:
        return None
    comps = cc(ink, min_area=5, dx=ix0, dy=iy0)
    if not comps:
        return None
    # F36: ygap 0.12->0.20 of the box height -- a joint a 2-3px chroma bleed
    # eats must stay inside the glue window (measured: 29px box, 3-5px loss);
    # the merge itself is proximity-based since F36 (see merge_fragments).
    comps = merge_fragments(comps, ygap=max(2, int(BOX_YGAP_FRAC * box.h)),
                            max_w=box.w, max_h=box.h)
    comps = [c for c in comps if c.h >= 0.25 * box.h]
    if not comps:
        return None
    hmax = max(c.h for c in comps)
    comps = [c for c in comps if c.h >= 0.62 * hmax]
    comps.sort(key=lambda c: c.x0)
    if len(comps) > 3:
        return None
    digits = ""
    for c in comps:
        # reading-resolution floor: below ~8px a glyph carries no identity;
        # reading it would be guessing (compression fragments under blur)
        if c.h < 8:
            return None
        sub = ink[c.y0 - iy0:c.y1 - iy0 + 1, c.x0 - ix0:c.x1 - ix0 + 1]
        for piece in split_wide(sub, max_w=int(1.30 * (c.y1 - c.y0 + 1))):
            d, conf, margin = classify_glyph(piece)
            if d is None or conf < min_conf or margin < min_margin:
                return None           # anything non-digit kills the box
            digits += str(d)
    if not digits or len(digits) > 2:
        return None
    return int(digits)


# ----------------------------------------------------------------------
# Stage C: label strip below the pair (finality on the stats board).
# ----------------------------------------------------------------------
def find_strip_below(m, pair, H):
    """Find the solid yellow strip that carries the finality label, directly
    below the score pair. The scan window is the pair span plus one span of
    context on each side (anchor-relative): the label text sits in the centre
    and dilutes a narrow window, while the strip itself extends well beyond
    the scoreline on every layout."""
    A, B = pair
    px0 = min(A.x0, B.x0)
    px1 = max(A.x1, B.x1)
    span = px1 - px0
    x0 = max(0, px0 - span)
    x1 = min(m.v.shape[1] - 1, px1 + span)
    y = max(A.y1, B.y1) + 1
    limit = min(H, y + int(3.5 * max(A.h, B.h)))
    run_start, run_end, run_len = None, None, 0
    best = None
    min_run = max(3, int(0.30 * max(A.h, B.h)))
    while y < limit:
        frac = float(m.strict[y, x0:x1 + 1].mean())
        if frac >= 0.55:
            if run_start is None:
                run_start = y
            run_end = y
            run_len += 1
        else:
            if run_start is not None and run_len >= min_run:
                if best is None or run_len > best[1] - best[0] + 1:
                    best = (run_start, run_end)
            run_start, run_len = None, 0
        y += 1
    if run_start is not None and run_len >= min_run:
        if best is None or run_len > best[1] - best[0] + 1:
            best = (run_start, run_end)
    return best   # (top, bottom) or None


def read_strip_label(m, strip, pair):
    """Dark ink on the yellow strip; leftmost glyph decides F/H/clock.
    F35: same two-zone Otsu ink as the score boxes (the strip is the same
    yellow-navy construction), derived from a synthetic box over the label
    window - this is what recovered FULL_TIME/HALF_TIME under panel shifts."""
    top, bot = strip
    A, B = pair
    x0 = min(A.x0, B.x0)
    x1 = max(A.x1, B.x1)
    strip_h = bot - top + 1
    ref = box_ink_ref(m, Box(x0, top, x1, bot, 0))
    if ref is None:
        return "UNKNOWN"
    ink = close_v(ink_mask(m, top, bot, x0, x1, ref))
    comps = cc(ink, min_area=8, dx=x0, dy=top)
    comps = [c for c in comps if 0.25 * strip_h <= c.h <= 0.90 * strip_h]
    if not comps:
        return "UNKNOWN"
    comps.sort(key=lambda c: c.x0)
    c = comps[0]
    sub = ink[c.y0 - top:c.y1 - top + 1, c.x0 - x0:c.x1 - x0 + 1]
    return classify_fh(sub)


# ----------------------------------------------------------------------
# Stage D: statistics table — rows and value tokens derived from structure.
# ----------------------------------------------------------------------
def cluster_tokens(row, tok_gap):
    """x-proximity tokens within one row band."""
    row = sorted(row, key=lambda c: c.x0)
    tokens = []
    for c in row:
        if tokens and c.x0 - tokens[-1][0].x1 <= tok_gap:
            t = tokens[-1]
            nb = Box(t[0].x0, min(t[0].y0, c.y0), max(t[0].x1, c.x1),
                     max(t[0].y1, c.y1), t[0].area + c.area)
            tokens[-1] = (nb, t[1] + [c])
        else:
            tokens.append((Box(c.x0, c.y0, c.x1, c.y1, c.area), [c]))
    return tokens


def occupancy_label_band(token_rows):
    """The label column exists in EVERY row and is wide; value columns are
    narrow and vary. 4px-bin occupancy across rows: widest run with coverage
    >= 80% = label band. Pure structure — no classification involved."""
    if not token_rows:
        return None
    x_lo = min(t[0].x0 for tr in token_rows for t in tr)
    x_hi = max(t[0].x1 for tr in token_rows for t in tr)
    nb = max(1, (x_hi - x_lo) // 4)
    cover = np.zeros(nb + 1, dtype=int)
    for tr in token_rows:
        for tb, _ in tr:
            a = max(0, (tb.x0 - x_lo) // 4)
            b = min(nb, (tb.x1 - x_lo) // 4)
            cover[a:b + 1] += 1
    # relative to the coverage PEAK, not the row count: UI rows below the
    # table (Back buttons etc) would otherwise dilute the label column's
    # coverage below a row-count threshold
    peak = int(cover.max())
    need = max(3, int(0.8 * peak))
    best_s, best_e, cur_s = 0, 0, None
    for i in range(nb + 1):
        if cover[i] >= need:
            if cur_s is None:
                cur_s = i
        else:
            if cur_s is not None and i - cur_s > best_e - best_s:
                best_s, best_e = cur_s, i
            cur_s = None
    if cur_s is not None and nb + 1 - cur_s > best_e - best_s:
        best_s, best_e = cur_s, nb + 1
    if best_e - best_s < 2:
        return None
    return (x_lo + best_s * 4, x_lo + best_e * 4)


def table_rows(m, strip_bottom, pair, W, H):
    """Everything below the strip is derived: glyph size bounds come from the
    score-box height (authored UI ratio), rows from y-clustering, tokens from
    x-proximity, and the label band from cross-row occupancy. Home/away are
    the value tokens flanking the band — chosen by POSITION, validated by
    classification (a token whose pieces are not all digits yields no value
    and the row is dropped). No screen fractions anywhere.
    Returns [(cy, home_token, away_token, band_x0, band_x1)]."""
    A, B = pair
    box_h = max(A.h, B.h)
    box_w = min(A.w, B.w)
    y_top = strip_bottom + max(2, int(0.12 * box_h))
    if y_top >= H - 4:
        return None
    min_area = max(6, int(0.000008 * W * H))
    comps = cc(m.loose[y_top:H, :], min_area=min_area, dy=y_top)
    hlo, hhi = 0.18 * box_h, 0.90 * box_h
    comps = [c for c in comps if hlo <= c.h <= hhi and c.w <= 1.2 * box_w]
    if len(comps) < 8:
        return None
    med_h = float(np.median([c.h for c in comps]))
    row_gap = max(3, 0.70 * med_h)
    comps.sort(key=lambda c: c.cy)
    rows = []
    for c in comps:
        if rows and abs(c.cy - rows[-1][-1].cy) < row_gap:
            rows[-1].append(c)
        else:
            rows.append([c])
    token_rows = [cluster_tokens(r, max(2, 0.60 * med_h)) for r in rows]
    token_rows = [tr for tr in tr if len(tr) >= 2] if False else token_rows
    band = occupancy_label_band(token_rows)
    if band is None:
        return None
    band_x0, band_x1 = band
    parsed = []
    for tr, r in zip(token_rows, rows):
        cy = float(np.mean([c.cy for c in r]))
        home = [t for t in tr if t[0].x1 < band_x0]
        away = [t for t in tr if t[0].x0 > band_x1]
        if not home or not away:
            continue
        parsed.append((cy, home[-1], away[0], band_x0, band_x1))
    if len(parsed) < 4:
        return None
    # trim trailing rows that broke the table's regular vertical lattice
    # (UI elements below the table); a mid-table gap stays — a row may
    # legitimately fail to read
    while len(parsed) > 4:
        cys = [p[0] for p in parsed]
        pitches = [cys[i + 1] - cys[i] for i in range(len(cys) - 1) if cys[i + 1] > cys[i]]
        if not pitches:
            break
        med_pitch = float(np.median(pitches))
        if cys[-1] - cys[-2] <= 1.6 * med_pitch:
            break
        parsed.pop()
    # the row y-lattice is a regular grid: assign each row its TABLE INDEX
    # from position, so one unreadable value never shifts the rows after it
    cys = [p[0] for p in parsed]
    pitches = [cys[i + 1] - cys[i] for i in range(len(cys) - 1) if cys[i + 1] - cys[i] > 2]
    if not pitches:
        return None
    pitch = float(np.median(pitches))
    cy0 = cys[0]
    out = []
    for (cy, h, a, lx0, lx1) in parsed:
        idx = int(round((cy - cy0) / pitch))
        out.append((idx, h, a, lx0, lx1))
    return out


def pct_veto(piece):
    """'%' glyphs are wider than ANY stat digit: measured piece aspects are
    0.62-0.82 for digits 0-9 (tight-cropped) and 0.88 for the '%', so the
    threshold sits mid-gap at 0.85; a secondary wide+two-holes rule catches
    rounder '%' renderings. Checked BEFORE classification — with a large
    template bank some '%' grids get close enough to a digit to pass the
    confidence gate, and a possession row would read '555' instead of '55'."""
    asp = piece.shape[1] / max(1, piece.shape[0])
    if asp > 0.85:
        return True
    return asp > 0.80 and count_holes(piece) >= 2


def token_pieces(m, tok):
    """Union the token's fragment components and valley-split into glyph
    pieces — the same geometry the menu reader and the exporter use."""
    tb, parts = tok
    mask = np.zeros((tb.y1 - tb.y0 + 1, tb.x1 - tb.x0 + 1), dtype=bool)
    for c in parts:
        # '%' remnants and letter fragments: wide OR sparse. Real digit glyph
        # parts fill >= ~0.40 of their bbox; a degraded '%' sliver fills ~0.21.
        if c.area / max(1, c.w * c.h) < 0.15:
            continue
        mask[c.y0 - tb.y0:c.y1 - tb.y0 + 1, c.x0 - tb.x0:c.x1 - tb.x0 + 1] |= \
            m.loose[c.y0:c.y1 + 1, c.x0:c.x1 + 1]
    # NO closing here: table glyphs are ~16px and a 3-tap closing deforms
    # them (spurious stroke fragments -> digit confusion). The vertical
    # closing lives only in the score-box path where glyphs are ~29px and
    # JPEG shoulder-cracks genuinely split holes.
    # 1.30x glyph height: a single digit is at most ~0.85 aspect, so this
    # never splits a real digit; fused glyph clusters reach the recognition-
    # guided valley cut. (A tighter 1.05 floor was tested to recover fused
    # '45%' pieces on the production encoding: it cut even fused pieces but
    # also made the local window reader split neighbour bleed, net zero
    # checks for added risk - rejected, measured 2026-09-16.)
    return split_wide(mask, max_w=int(1.30 * mask.shape[0]))


def token_value(m, tok):
    """Numeric value of a VALUE token ('%' pieces dropped).

    F35 integrity rule: a token is emitted ONLY if every piece is either a
    confident digit or a '%'. The old drop-and-continue turned a refused
    glyph into a SHORTER number that still looked valid ('38' -> '3') - a
    silent wrong value. Refusal kills the row; the row lattice keeps every
    other row at its correct table index."""
    digits = ""
    for piece in token_pieces(m, tok):
        if pct_veto(piece):
            continue          # '%'
        d, conf, margin = classify_glyph(piece)
        # 0.60 confidence: real stat digits read 0.61..1.0, compression sliver
        # junk (e.g. a degraded '%') reads ~0.51 — measured on the calibration set
        if d is None or conf < 0.60 or margin < 0.03:
            return None       # integrity: never emit a partially-read token
        digits += str(d)
    if not digits or len(digits) > 2:
        return None           # a stat value is at most two digits
    return int(digits)


def token_value_local(m, tok, band_edge, band_is_left):
    """F35 local value read: an Otsu window over the token's own neighbourhood.

    The global loose mask is a frame-wide colour gate; under panel shift +
    chroma-subsampled JPEG it can lose a thin glyph part entirely (the top
    bar of a '5' -> theresidual  shape reads '6'; an '8' drops out -> '3'). This
    reader derives a threshold from the token window's own bimodal v
    histogram and pads the window toward the column's alignment side (home
    values right-align against the label band, away values left-align), so a
    glyph the global mask missed is recovered from local contrast.
    Returns a value or None; the caller cross-validates against token_value."""
    tb, parts = tok
    h = max(1, tb.h)
    pad_out = int(0.70 * h) + 1
    pad_in = 2
    pad_y = 2
    if band_is_left:                      # away column: values left-aligned at band
        x0 = max(0, tb.x0 - pad_in)
        x1 = min(m.v.shape[1] - 1, tb.x1 + pad_out)
    else:                                 # home column: values right-aligned at band
        x0 = max(0, tb.x0 - pad_out)
        x1 = min(m.v.shape[1] - 1, tb.x1 + pad_in)
    if band_edge is not None:
        if band_is_left:
            x0 = max(x0, band_edge + 2)
        else:
            x1 = min(x1, band_edge - 2)
    y0 = max(0, tb.y0 - pad_y)
    y1 = min(m.v.shape[0] - 1, tb.y1 + pad_y)
    if x1 - x0 < 2 or y1 - y0 < 4:
        return None
    win_v = m.v[y0:y1 + 1, x0:x1 + 1].ravel()
    t, ok = otsu_threshold(win_v)
    if not ok:
        return None
    win_h = m.h[y0:y1 + 1, x0:x1 + 1]
    win_ch = m.chroma[y0:y1 + 1, x0:x1 + 1]
    dh = np.abs(((win_h - m.hue_y + 180.0) % 360.0) - 180.0)
    mask = (m.v[y0:y1 + 1, x0:x1 + 1] >= t) & ((dh <= 50.0) | (win_ch >= 80))
    if mask.sum() < 8:
        return None
    comps = cc(mask, min_area=6)
    if not comps:
        return None
    # F36: ygap 0.12->0.25 of the token height (window glyphs are ~16px, so
    # this is 2->4px); next table row sits ~1 pitch away, cross-row gluing
    # stays impossible.
    comps = merge_fragments(comps, ygap=max(2, int(LOCAL_YGAP_FRAC * tb.h)),
                            max_w=tb.w + 2 * (int(0.70 * tb.h) + 2),
                            max_h=int(1.6 * tb.h))
    clean = np.zeros_like(mask)
    for c in comps:
        clean[c.y0:c.y1 + 1, c.x0:c.x1 + 1] |= mask[c.y0:c.y1 + 1, c.x0:c.x1 + 1]
    if clean.sum() < 8:
        return None
    pieces = split_wide(clean, max_w=int(1.30 * clean.shape[0]))
    hmax = max((p.shape[0] for p in pieces), default=0)
    if hmax < STATS_PIECE_FLOOR:
        return None                       # whole table below identity floor
    digits = ""
    for piece in pieces:
        ph, pw = piece.shape[0], piece.shape[1]
        if ph < max(4, int(0.60 * hmax)):
            continue                      # speck / streak fragment, not a glyph
        if pw / max(1, ph) < THIN_ASPECT:
            # F36: a GLYPH-SIZED thin piece is a serif-less '1' (measured ink
            # 3-5px wide at 16-19px tall, aspect 0.19-0.27) or a '%' sliver --
            # never silently droppable. Dropping it let the token read a
            # SHORTER number ("14" -> "4"); cross_value then refused the
            # disagreement and the stat row came back EMPTY (the review's
            # Total Shots / Shots on Target / Free Kicks symptom). Classify
            # it with the standard gates instead: a digit joins the number;
            # a refusal kills the token and cross_value falls back to the
            # global read -- never a silent partial read.
            d, conf, margin = classify_glyph(piece)
            if d is None or conf < 0.60 or margin < 0.03:
                return None               # integrity
            digits += str(d)
            continue
        if ph < STATS_PIECE_FLOOR:
            return None                   # real glyph below identity floor
        if pct_veto(piece):
            continue                      # '%'
        d, conf, margin = classify_glyph(piece)
        if d is None or conf < 0.60 or margin < 0.03:
            return None                   # integrity
        digits += str(d)
    if not digits or len(digits) > 3:
        return None
    return int(digits)


def read_stats_table(m, strip_bottom, pair, W, H):
    """Returns [(table_index, home, away)] — indices position each row in
    the 13-row stat table even when some rows fail to read (fail-safe per
    row, never a shift).

    F35 cross-validated values: every value token is read TWICE by
    independent measurements - the global loose-mask read (token_value) and
    the local Otsu-window read (token_value_local). Agreement emits the
    value; disagreement refuses the row (never guess which reader is right);
    a single reading emits it (recovery for the local reader, proven path
    for the global one). This is the token-level analogue of the engine's
    two-agreeing-reads rule and it is what removed the wrong stat rows under
    night-shift + JPEG q60 while IMPROVING recall on vivid/q75 tables."""
    parsed = table_rows(m, strip_bottom, pair, W, H)
    if parsed is None:
        return None
    rows_out = []
    for idx, home_tok, away_tok, lx0, lx1 in parsed:
        if idx > 12:
            continue          # below the 13-row table: not a stat row
        hv = cross_value(token_value(m, home_tok),
                         token_value_local(m, home_tok, lx0, band_is_left=False))
        av = cross_value(token_value(m, away_tok),
                         token_value_local(m, away_tok, lx1, band_is_left=True))
        if hv is None or av is None:
            continue
        rows_out.append((idx, hv, av))
    if len(rows_out) < 4:
        return None
    return rows_out


def cross_value(gv, lv):
    """Combine the global-mask and local-window reads of one token.
    Agreement -> the value. Disagreement -> refuse (never guess which of two
    independent measurements is wrong). One reading -> emit it."""
    if gv is None:
        return lv
    if lv is None:
        return gv
    return gv if gv == lv else None


# ----------------------------------------------------------------------
# Stage E: menu score — big yellow digits on a dark background, anywhere.
# ----------------------------------------------------------------------
def dark_background(m, box, H):
    """The ring around the glyph must be dark (menu black), median-based."""
    r = int(1.0 * box.h)
    x0, x1 = max(0, box.x0 - r), min(m.v.shape[1] - 1, box.x1 + r)
    y0, y1 = max(0, box.y0 - r), min(m.v.shape[0] - 1, box.y1 + r)
    ring = np.ones((y1 - y0 + 1, x1 - x0 + 1), dtype=bool)
    ring[box.y0 - y0:box.y1 - y0 + 1, box.x0 - x0:box.x1 - x0 + 1] = False
    vals = m.v[y0:y1 + 1, x0:x1 + 1][ring]
    return float(np.median(vals)) <= 0.34 if vals.size else False


def read_number_tokens(m, comps, med_h):
    """Classify glyph clusters as numbers. The cluster's fragments are unioned
    into one mask first (a bold digit arrives as bowl + base bar), then split
    by column valleys, then every piece must read as a digit. Returns
    [(cluster_box, value)]; anything else is silently not a number.

    F35 solid-blob guard: a SOLID yellow rectangle (a score box, a label
    strip) that lost its own detection path would arrive here as a cluster
    and be classified from its INVERSE glyph (the digit-shaped holes in the
    yellow) - measured fill 0.86-0.92 vs 0.54-0.60 for real bold digit
    strokes - which is exactly how a real 0-1 stats board once reported
    MENU 0-0. Blobs above the measured gap are not glyph clusters."""
    out = []
    for tb, parts in comps:
        fill = sum(c.area for c in parts) / max(1, tb.w * tb.h)
        if fill > MENU_SOLID_FILL:
            continue
        mask = np.zeros((tb.y1 - tb.y0 + 1, tb.x1 - tb.x0 + 1), dtype=bool)
        for c in parts:
            mask[c.y0 - tb.y0:c.y1 - tb.y0 + 1, c.x0 - tb.x0:c.x1 - tb.x0 + 1] |= \
                m.loose[c.y0:c.y1 + 1, c.x0:c.x1 + 1]
        digits, pure = "", True
        for piece in split_wide(mask, max_w=int(1.30 * mask.shape[0])):
            d, conf, margin = classify_glyph(piece)
            if d is None or conf < 0.45 or margin < 0.02:
                pure = False
                break
            digits += str(d)
        if pure and 1 <= len(digits) <= 2:
            out.append((tb, int(digits)))
    return out


def find_menu_score(m, W, H):
    """Menu score = the LARGEST readable yellow digit clusters on a dark
    background, anywhere in the frame. Crests/team names fail digit reading;
    clock text is smaller by an authored-scale floor. No position prior."""
    min_area = max(20, int(0.00006 * W * H))
    comps = cc(m.loose, min_area)
    # components may be small FRAGMENTS (a bold '2' splits into bowl + base
    # bar under compression), so the component floor is low; the authored-
    # scale floor applies to whole CLUSTERS further down.
    # component floor is an anti-speck guard in ABSOLUTE pixels (6px), never
    # authored scale: fragments (a digit's base bar is ~13px on any device)
    # must be free to join their cluster regardless of frame/padding size.
    # The authored-scale floor lives on whole CLUSTERS further down.
    cand = [c for c in comps
            if c.h >= 6 and c.h <= 0.15 * H and c.w <= 0.5 * W and dark_background(m, c, H)]
    if len(cand) < 2:
        return None, None, None
    med_h = float(np.median([c.h for c in cand]))
    # Merge fragments into glyph clusters by X-OVERLAP: a bold digit splits
    # into a bowl + a base bar that fully overlap the digit horizontally, so
    # stacked pieces rejoin their own glyph. The score DASH overlaps neither
    # neighbour, so it survives as a separate wide-short cluster and marks the
    # home/away split — the structural substitute for a fixed "dash window":
    # the dash is whatever the digits' own geometry leaves over.
    clusters = [(Box(c.x0, c.y0, c.x1, c.y1, c.area), [c]) for c in cand]
    changed = True
    while changed:
        changed = False
        out = []
        for cur in clusters:
            c = cur[0]
            target = None
            for o in out:
                ov = min(c.x1, o[0].x1) - max(c.x0, o[0].x0) + 1
                minw = min(c.w, o[0].w)
                ygap = max(c.y0 - o[0].y1, o[0].y0 - c.y1)
                if ov < 0.5 * minw or ygap > 0.5 * med_h:
                    continue
                if max(c.y1, o[0].y1) - min(c.y0, o[0].y0) + 1 > 0.16 * H:
                    continue
                target = o
                break
            if target is not None:
                t = target[0]
                nb = Box(min(t.x0, c.x0), min(t.y0, c.y0), max(t.x1, c.x1),
                         max(t.y1, c.y1), t.area + c.area)
                out[out.index(target)] = (nb, target[1] + cur[1])
                changed = True
            else:
                out.append(cur)
        clusters = out
    # authored-scale floor on whole clusters: the menu score is display-
    # dominant (>= ~4.5% of frame height); clock text never is.
    dash = None
    number_clus = []
    for cl in clusters:
        tb = cl[0]
        if tb.h < 0.035 * H:
            continue
        if len(cl[1]) == 1 and tb.w / max(1, tb.h) >= 1.4 and dash is None:
            dash = tb
            continue
        number_clus.append(cl)
    # only clusters that READ as 1-2 digits survive; crests/letters cannot
    numbers = read_number_tokens(m, number_clus, med_h)
    if len(numbers) < 2:
        return None, None, None
    max_h = max(b.h for b, _ in numbers)
    numbers = [(b, v) for b, v in numbers if b.h >= 0.60 * max_h]
    if len(numbers) < 2:
        return None, None, None
    numbers.sort(key=lambda n: n[0].x0)
    if dash is not None:
        left = [n for n in numbers if n[0].x1 < dash.x0]
        right = [n for n in numbers if n[0].x0 > dash.x1]
        if left and right:
            return left[-1], right[0], "DASH"
    # no usable dash: closest same-height pair
    best = None
    for i in range(len(numbers)):
        for j in range(i + 1, len(numbers)):
            A, B = numbers[i][0], numbers[j][0]
            if abs(A.h - B.h) > 0.35 * max(A.h, B.h):
                continue
            gap = B.x0 - A.x1
            if gap < 0 or gap > 2.5 * max(A.w, B.w):
                continue
            if best is None or gap < best[0]:
                best = (gap, numbers[i], numbers[j])
    if best is None:
        return None, None, None
    return best[1], best[2], "PAIR"


def read_menu_finality(m, home_box, away_box):
    """Yellow text just above the menu digits ('Full Time' or a clock)."""
    x0 = min(home_box.x0, away_box.x0)
    x1 = max(home_box.x1, away_box.x1)
    gh = max(home_box.h, away_box.h)
    y1 = min(home_box.y0, away_box.y0) - 1
    y0 = max(0, y1 - int(2.0 * gh))
    if y1 - y0 < 4:
        return "UNKNOWN"
    comps = cc(m.loose[y0:y1 + 1, x0:x1 + 1], min_area=8, dx=x0, dy=y0)
    comps = [c for c in comps if 0.10 * gh <= c.h <= 0.90 * gh]
    if not comps:
        return "UNKNOWN"
    comps.sort(key=lambda c: c.x0)
    c = comps[0]
    sub = m.loose[c.y0:c.y1 + 1, c.x0:c.x1 + 1]
    return classify_fh(sub)


# ----------------------------------------------------------------------
# Top level: classify + read. Returns a dict (mirrors Kotlin Detection).
# ----------------------------------------------------------------------
def analyze(rgb):
    H, W = rgb.shape[:2]
    m = Masks(rgb)
    out = {"type": "OTHER", "home": None, "away": None,
           "finality": "UNKNOWN", "stats": None, "hue_y": m.hue_y}

    # --- universal pre-gate: no plausible yellow anywhere -> not a score frame
    if m.hue_y is None or int(m.strict.sum()) < max(300, 0.0004 * W * H):
        return out

    # --- banner presentations (stats board top / walking bottom): find boxes
    # F36 unknown-stays-unknown: a pair that passes the SHAPE validation is a
    # scoreboard with certainty our shape test already owns; if no pair then
    # validates by READING, the frame must end unknown -- it must never fall
    # through to the menu reader, whose looser gates once converted a safe
    # "cannot read" into a confident wrong score (the F34 0-0 MENU failure).
    pair_seen = False
    for A, B in find_score_boxes(m, W, H):
        pair_seen = True
        # pair validation reads with a tighter gate than generic tokens
        hv = read_box_value(m, A, H, min_conf=0.50, min_margin=0.04)
        av = read_box_value(m, B, H, min_conf=0.50, min_margin=0.04)
        if hv is None or av is None:
            continue                     # pair not validated by reading -> next
        home, away = (hv, av) if A.x0 < B.x0 else (av, hv)
        out["home"], out["away"] = home, away
        strip = find_strip_below(m, (A, B), H)
        rows = None
        if strip is not None:
            rows = read_stats_table(m, strip[1], (A, B), W, H)
        else:
            rows = read_stats_table(m, max(A.y1, B.y1), (A, B), W, H)
        if rows is not None and len(rows) >= 4:
            out["type"] = "STATS_BOARD"
            out["stats"] = rows
            if strip is not None:
                out["finality"] = read_strip_label(m, strip, (A, B))
        else:
            out["type"] = "WALKING"
        return out

    if pair_seen:
        return out                       # unknown, full stop -- never menu

    # --- menu presentation: big yellow digits on dark, no boxes
    left, right, _how = find_menu_score(m, W, H)
    if left is not None and right is not None:
        out["type"] = "MENU"
        out["home"], out["away"] = left[1], right[1]
        out["finality"] = read_menu_finality(m, left[0], right[0])
    return out


GROUND_TRUTH = {
    "Screenshot_20260905-010207.png": ("WALKING", (0, 1), None, None),
    "Screenshot_20260905-184444.png": ("STATS_BOARD", (0, 1), "FULL_TIME",
        [(55, 45), (4, 1), (2, 1), (0, 1), (0, 1), (1, 0), (1, 0), (68, 80),
         (55, 62), (0, 0), (13, 11), (3, 5), (0, 3)]),
    "Screenshot_20260905-005550.png": ("STATS_BOARD", (0, 0), "HALF_TIME",
        [(54, 46), (2, 2), (2, 1), (1, 0), (0, 0), (1, 1), (0, 1), (35, 34),
         (29, 25), (0, 1), (8, 5), (2, 5), (1, 2)]),
    "Screenshot_20260906-204840.png": ("MENU", (2, 2), "FULL_TIME", None),
    "Screenshot_20260808-135907.png": ("STATS_BOARD", (1, 2), "HALF_TIME",
        [(50, 50), (4, 3), (2, 3), (0, 0), (0, 0), (1, 1), (0, 0), (37, 38),
         (34, 30), (1, 0), (6, 2), (0, 4), (1, 1)]),
    "Screenshot_20260808-140853.png": ("STATS_BOARD", (4, 2), "HALF_TIME",
        [(50, 50), (8, 2), (6, 2), (0, 0), (0, 0), (1, 0), (0, 0), (32, 46),
         (26, 40), (0, 0), (5, 4), (1, 0), (0, 2)]),
    "Screenshot_20260808-140859.png": ("MENU", (4, 2), "CLOCK", None),
}


def main():
    ok = 0
    for name, (ttype, score, fin, stats) in GROUND_TRUTH.items():
        path = os.path.join(IMG_DIR, name)
        rgb = np.asarray(Image.open(path).convert("RGB"))
        out = analyze(rgb)
        got_stats = [(i, v) for i, v in ((r[0], (r[1], r[2])) for r in (out["stats"] or []))]
        stats_ok = stats is None or all(
            i < len(stats) and got_stats[j][1] == stats[got_stats[j][0]]
            for j, (i, v) in enumerate(got_stats))
        good = (out["type"] == ttype and (score is None or (out["home"], out["away"]) == score)
                and (fin is None or out["finality"] == fin) and stats_ok)
        ok += good
        tag = "PASS" if good else "FAIL"
        print(f"[{tag}] {name}: type={out['type']} score={out['home']}-{out['away']} "
              f"fin={out['finality']} rows={0 if out['stats'] is None else len(out['stats'])} "
              f"hueY={out['hue_y'] and round(out['hue_y'],1)}")
        if not good and stats is not None and out['stats'] is not None:
            for i, (got, want) in enumerate(zip(out['stats'], stats)):
                if got != want:
                    print(f"    row {i} ({STAT_NAMES[i] if i < 13 else '?'}): got {got} want {want}")
    print(f"{ok}/{len(GROUND_TRUTH)} PASS")
    return 0 if ok == len(GROUND_TRUTH) else 1


if __name__ == "__main__":
    sys.exit(main())
