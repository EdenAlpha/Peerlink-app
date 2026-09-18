"""F36 probe: reproduce the split-'3' mechanism the external review isolated.

Mechanism under test (their words): a '3' is two lobes joined by a thin
backbone; compression/AA can drop the thin joint below the ink threshold, so
the ink mask splits into top curl + spine + bottom curl. merge_fragments()
only re-glues pieces whose horizontal overlap exceeds 50% of the narrower
piece -- the curls overhang the spine, so the glue fails.

This probe follows the shipped readers' EXACT data flow:
  box path  : ink canvas -> cc(min_area=5) -> merge_fragments -> h-filters
              -> per-comp crop -> split_wide -> classify (conf>=0.50, m>=0.04)
  stats path: window canvas -> cc(min_area=6) -> merge_fragments -> clean
              -> split_wide -> piece filters -> classify (conf>=0.60, m>=0.03)

It harvests REAL digit masks from the 7 native captures, simulates joint
loss (erase the thinnest rows in the vertical mid-band, 1..3 px), and
compares OLD merge (shipped 50% rule) vs NEW merge (proximity rule).
"""
import os
import sys
import numpy as np
from PIL import Image
from collections import Counter

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'f34_reference'))
import prototype as P

IMG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
CAPS = sorted(f for f in os.listdir(IMG_DIR) if f.endswith('.png'))


def merge_fragments_old(comps, ygap, max_w, max_h):
    """The shipped F34/F35 rule (50% horizontal overlap), kept here only as
    the A/B baseline now that the prototype ships the F36 proximity rule."""
    comps = sorted(comps, key=lambda c: (c.y0, c.x0))
    out = []
    for c in comps:
        target = None
        for g in out:
            ov = min(c.x1, g.x1) - max(c.x0, g.x0) + 1
            minw = min(c.w, g.w)
            gap = max(c.y0 - g.y1, g.y0 - c.y1)
            if ov <= 0 or ov <= 0.5 * minw or gap > ygap:
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
        else:
            out.append(P.Box(c.x0, c.y0, c.x1, c.y1, c.area))
    return out


def harvest_score_digits():
    """(capture, digit, canvas_mask, box) from native score boxes."""
    out = []
    for name in CAPS:
        rgb = np.asarray(Image.open(os.path.join(IMG_DIR, name)).convert('RGB'))
        m = P.Masks(rgb)
        H, W = rgb.shape[:2]
        for A, B in P.find_score_boxes(m, W, H):
            for box in (A, B):
                ref = P.box_ink_ref(m, box)
                if ref is None:
                    continue
                ix0 = box.x0 + int(0.14 * box.w)
                ix1 = box.x1 - int(0.14 * box.w)
                iy0 = box.y0 + int(0.14 * box.h)
                iy1 = box.y1 - int(0.14 * box.h)
                if ix1 - ix0 < 4 or iy1 - iy0 < 4:
                    continue
                ink = P.close_v(P.ink_mask(m, iy0, iy1, ix0, ix1, ref))
                if ink.sum() < 10:
                    continue
                comps = P.cc(ink, min_area=5)
                if len(comps) != 1:
                    continue
                d0, conf0, _ = P.classify_glyph(ink[comps[0].y0:comps[0].y1 + 1,
                                                    comps[0].x0:comps[0].x1 + 1])
                if d0 is None:
                    continue
                out.append((name, d0, ink, box.h, box.w))
    return out


def harvest_stat_digits():
    """(capture, digit, glyph_mask, tok_h, tok_w) from native stats tokens."""
    out = []
    for name in CAPS:
        rgb = np.asarray(Image.open(os.path.join(IMG_DIR, name)).convert('RGB'))
        m = P.Masks(rgb)
        H, W = rgb.shape[:2]
        for A, B in P.find_score_boxes(m, W, H):
            hv = P.read_box_value(m, A, H, min_conf=0.50, min_margin=0.04)
            av = P.read_box_value(m, B, H, min_conf=0.50, min_margin=0.04)
            if hv is None or av is None:
                continue
            pair = (A, B) if A.x0 < B.x0 else (B, A)
            strip = P.find_strip_below(m, pair, H)
            yref = strip[1] if strip else max(A.y1, B.y1)
            if P.read_stats_table(m, yref, pair, W, H) is None:
                continue
            parsed = P.table_rows(m, yref, pair, W, H)
            if parsed is None:
                continue
            for idx, home_tok, away_tok, lx0, lx1 in parsed:
                if idx > 12:
                    continue
                for tok in (home_tok, away_tok):
                    pieces = P.token_pieces(m, tok)
                    hmax = max((p.shape[0] for p in pieces), default=0)
                    for piece in pieces:
                        if P.pct_veto(piece):
                            continue
                        if piece.shape[0] < max(4, int(0.60 * hmax)):
                            continue
                        d0, conf0, _ = P.classify_glyph(piece)
                        if d0 is None or conf0 < 0.60:
                            continue
                        out.append((name, d0, piece.copy(), tok[0].h, tok[0].w))
    return out


def split_joint(mask, erase):
    """Erase `erase` rows centred on the thinnest row of the vertical middle
    band (the joint) -- the measured JPEG failure mode."""
    h, w = mask.shape
    mid = mask[:, w // 4: 3 * w // 4]
    row_ink = mid.sum(axis=1)
    lo, hi = int(0.30 * h), int(0.70 * h)
    if hi - lo < 2:
        return None
    yc = lo + int(np.argmin(row_ink[lo:hi]))
    out = mask.copy()
    r0 = max(0, yc - erase // 2)
    r1 = min(h, r0 + erase)
    out[r0:r1, :] = False
    return out if out.sum() >= 6 else None


def split_two_joints(mask, erase):
    """The review's exact '3' failure: BOTH thin joints drop below the ink
    threshold -> top curl + spine + bottom curl. Finds the thinnest row in
    the upper band and in the lower band, erases `erase` rows at each.
    The cut spans the glyph's full ink width: the whole joint line drops
    below the ink threshold (what chroma-bleed + a lowered threshold do)."""
    h, w = mask.shape
    cols = np.where(mask.any(axis=0))[0]
    if cols.size == 0:
        return None
    cx0, cx1 = int(cols[0]), int(cols[-1]) + 1
    row_ink = mask[:, cx0:cx1].sum(axis=1)
    cuts = []
    for lo, hi in ((int(0.28 * h), int(0.48 * h)),
                   (int(0.55 * h), int(0.75 * h))):
        if hi - lo < 2 or hi >= h:
            continue
        cuts.append(lo + int(np.argmin(row_ink[lo:hi])))
    if not cuts:
        return None
    out = mask.copy()
    for yc in cuts:
        r0 = max(0, yc - erase // 2)
        r1 = min(h, r0 + erase)
        out[r0:r1, cx0:cx1] = False
    return out if out.sum() >= 6 else None


split_two_joints_full = split_two_joints


def narrow_spine_variant(mask):
    """The review's device geometry: the middle piece is a NARROW spine that
    the curls only barely touch sideways. Built from the real '3': keep the
    two waist breaks, then keep only the spine's RIGHT half columns (thin
    vertical stub), so curl-vs-spine horizontal overlap is 1-2px at most.
    This is the fragmentation family their 1376x768 frame produces."""
    sp = split_two_joints(mask, 1)
    if sp is None:
        return None
    comps = P.cc(sp, min_area=3)
    if len(comps) != 3:
        return None
    comps.sort(key=lambda c: c.y0)
    mid = comps[1]
    out = sp.copy()
    keep_to = mid.x0 + max(1, (mid.x1 - mid.x0 + 1) // 2) - 1
    out[mid.y0:mid.y1 + 1, keep_to + 1:mid.x1 + 1] = False
    return out


def read_box(canvas, scale_h, scale_w, rule):
    """read_box_value's downstream, OLD vs NEW merge."""
    comps = P.cc(canvas, min_area=5)
    if not comps:
        return None, 0
    ygap = max(2, int((0.12 if rule == 'old' else 0.20) * scale_h))
    if rule == 'old':
        comps = merge_fragments_old(comps, ygap=ygap, max_w=scale_w,
                                    max_h=scale_h)
    else:
        comps = P.merge_fragments(comps, ygap=ygap, max_w=scale_w,
                                  max_h=scale_h)
    comps = [c for c in comps if c.h >= 0.25 * scale_h]
    if not comps:
        return None, len(comps)
    hmax = max(c.h for c in comps)
    comps = [c for c in comps if c.h >= 0.62 * hmax]
    comps.sort(key=lambda c: c.x0)
    if len(comps) > 3:
        return None, len(comps)
    digits = ""
    for c in comps:
        if c.h < 8:
            return None, len(comps)
        sub = canvas[c.y0:c.y1 + 1, c.x0:c.x1 + 1]
        for piece in P.split_wide(sub, max_w=int(1.30 * (c.y1 - c.y0 + 1))):
            d, conf, margin = P.classify_glyph(piece)
            if d is None or conf < 0.50 or margin < 0.04:
                return None, len(comps)
            digits += str(d)
    if not digits or len(digits) > 2:
        return None, len(comps)
    return digits, len(comps)


def read_stat(glyph, tok_h, tok_w, rule):
    """token_value_local's downstream, OLD vs NEW merge. Canvas geometry
    mirrors the real Otsu window: pad_y=2 vertically, 0.70*h+2 horizontally
    (the real window is y-tight and x-wide -- a y-wide canvas would break
    the piece aspect filter with empty rows the real path never has)."""
    pad_x = int(0.70 * tok_h) + 2
    pad_y = 2
    canvas = np.zeros((glyph.shape[0] + 2 * pad_y,
                       glyph.shape[1] + 2 * pad_x), dtype=bool)
    canvas[pad_y:pad_y + glyph.shape[0],
           pad_x:pad_x + glyph.shape[1]] = glyph
    comps = P.cc(canvas, min_area=6)
    if not comps:
        return None
    ygap = max(2, int((0.12 if rule == 'old' else 0.25) * tok_h))
    if rule == 'old':
        comps = merge_fragments_old(comps, ygap=ygap,
                                    max_w=tok_w + 2 * (int(0.70 * tok_h) + 2),
                                    max_h=int(1.6 * tok_h))
    else:
        comps = P.merge_fragments(comps, ygap=ygap,
                                  max_w=tok_w + 2 * (int(0.70 * tok_h) + 2),
                                  max_h=int(1.6 * tok_h))
    clean = np.zeros_like(canvas)
    for c in comps:
        clean[c.y0:c.y1 + 1, c.x0:c.x1 + 1] |= canvas[c.y0:c.y1 + 1,
                                                      c.x0:c.x1 + 1]
    pieces = P.split_wide(clean, max_w=int(1.30 * clean.shape[0]))
    hmax = max((p.shape[0] for p in pieces), default=0)
    if hmax < P.STATS_PIECE_FLOOR:
        return None
    digits = ""
    for piece in pieces:
        ph, pw = piece.shape[0], piece.shape[1]
        if ph < max(4, int(0.60 * hmax)) or pw / max(1, ph) < 0.28:
            continue
        if ph < P.STATS_PIECE_FLOOR:
            return None
        if P.pct_veto(piece):
            continue
        d, conf, margin = P.classify_glyph(piece)
        if d is None or conf < 0.60 or margin < 0.03:
            return None
        digits += str(d)
    if not digits or len(digits) > 3:
        return None
    return digits


def main():
    boxes = harvest_score_digits()
    stats = harvest_stat_digits()
    print(f"harvested: {len(boxes)} score-box glyphs, {len(stats)} stat glyphs")
    print("box digits:", Counter(d for _, d, *_ in boxes))
    print("stat digits:", Counter(d for _, d, *_ in stats))

    n = old_fail = old_wrong = new_fail = new_wrong = 0
    examples = []
    per_digit = Counter()
    for kind, pool in (('box', boxes), ('stat', stats)):
        for item in pool:
            name, d0 = item[0], item[1]
            mask = item[2]
            sh, sw = item[3], item[4]
            # baseline sanity: unsplit glyph must read d0 through the path
            base = (read_box(mask, sh, sw, 'old') if kind == 'box'
                    else read_stat(mask, sh, sw, 'old'))
            if base[0] != str(d0) if kind == 'box' else base != str(d0):
                continue          # path quirks unrelated to splitting
            for sim in ('one', 'two'):
                for erase in (1, 2, 3):
                    sp = (split_joint(mask, erase) if sim == 'one'
                          else split_two_joints(mask, erase))
                    if sp is None:
                        continue
                    comps = P.cc(sp, min_area=5 if kind == 'box' else 6)
                    npieces = len(comps)
                    if npieces == 1:
                        continue
                    n += 1
                    per_digit[(kind, sim, d0)] += 1
                    got_old = (read_box(sp, sh, sw, 'old') if kind == 'box'
                               else read_stat(sp, sh, sw, 'old'))
                    got_new = (read_box(sp, sh, sw, 'new') if kind == 'box'
                               else read_stat(sp, sh, sw, 'new'))
                    ov = got_old[0] if kind == 'box' else got_old
                    nv = got_new[0] if kind == 'box' else got_new
                    if ov != str(d0):
                        old_fail += 1
                        if ov is not None:
                            old_wrong += 1
                        if len(examples) < 20:
                            examples.append((kind, sim, name, d0, erase,
                                             npieces, ov, nv))
                    if nv != str(d0):
                        new_fail += 1
                        if nv is not None:
                            new_wrong += 1
    print(f"\nsplit simulations run: {n}")
    print(f"OLD (shipped 50% rule): refused-or-wrong {old_fail}, WRONG digit {old_wrong}")
    print(f"NEW (proximity rule):   refused-or-wrong {new_fail}, WRONG digit {new_wrong}")
    print("\nsplit-prone digits:", dict(per_digit))
    print("\nexamples (kind, sim, capture, true, erase_px, pieces, OLD->, NEW->):")
    for e in examples:
        print("  ", e)


if __name__ == '__main__':
    main()
