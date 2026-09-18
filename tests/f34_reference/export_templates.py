#!/usr/bin/env python3
"""F34 template exporter — regenerates ScoreBoardTemplates.kt by harvesting
glyphs from the calibration captures THROUGH the F34 structural reader
itself (same masks, same ink, same segmentation, same normalization), so the
frozen bank is guaranteed coherent with the reader's pixel pipeline.

This is offline tooling: it may use ground-truth labels for the harvest;
the shipped reader never does.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prototype as P

IMG = os.environ.get("F34_CAPTURES", "/home/z/my-project/work/f32/PeerLink-F32")
OUT_KT = "/home/z/my-project/work/f34/app/src/main/java/com/peerlink/app/service/ScoreBoardTemplates.kt"
FONT = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"

# captures whose score boxes carry a known single digit
BOX_GT = {
    "Screenshot_20260905-010207.png": (0, 1),   # walking banner
    "Screenshot_20260905-184444.png": (0, 1),   # FT board
    "Screenshot_20260905-005550.png": (0, 0),   # HT board
    "Screenshot_20260808-135907.png": (1, 2),   # HT board
    "Screenshot_20260808-140853.png": (4, 2),   # HT board
}
MENU_GT = {
    "Screenshot_20260906-204840.png": (2, 2),
    "Screenshot_20260808-140859.png": (4, 2),
}
TABLE_GT = {
    "Screenshot_20260905-184444.png": [(55, 45), (4, 1), (2, 1), (0, 1), (0, 1), (1, 0), (1, 0), (68, 80), (55, 62), (0, 0), (13, 11), (3, 5), (0, 3)],
    "Screenshot_20260905-005550.png": [(54, 46), (2, 2), (2, 1), (1, 0), (0, 0), (1, 1), (0, 1), (35, 34), (29, 25), (0, 1), (8, 5), (2, 5), (1, 2)],
    "Screenshot_20260808-135907.png": [(50, 50), (4, 3), (2, 3), (0, 0), (0, 0), (1, 1), (0, 0), (37, 38), (34, 30), (1, 0), (6, 2), (0, 4), (1, 1)],
    "Screenshot_20260808-140853.png": [(50, 50), (8, 2), (6, 2), (0, 0), (0, 0), (1, 0), (0, 0), (32, 46), (26, 40), (0, 0), (5, 4), (1, 0), (0, 2)],
}

collected = []   # (digit, tight_bool_mask)


def add(digit, mask):
    ys, xs = np.where(mask)
    if len(ys) == 0:
        return
    mm = mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    # a template must carry digit identity: at least 8px tall and a sane ink
    # density — blur-fused blobs (density > 0.8) would otherwise enter the
    # bank and let 3x4 compression fragments 'read' as digits
    if mm.shape[0] < 8 or mm.shape[1] < 4:
        return
    d = float(mm.sum()) / mm.size
    if d < 0.15 or d > 0.75:
        return
    collected.append((digit, mm, 1.0))


def harvest_box_digits():
    for name, (gh, ga) in BOX_GT.items():
        rgb = load_capture(name)
        H, W = rgb.shape[:2]
        m = P.Masks(rgb)
        pairs = P.find_score_boxes(m, W, H)
        if not pairs:
            print(f"  [warn] no box pair on {name}")
            continue
        A, B = pairs[0]          # largest digit-box pair = the scoreline
        home, away = (A, B) if A.x0 < B.x0 else (B, A)
        for box, gt in ((home, gh), (away, ga)):
            pieces = box_pieces(m, box)
            if len(pieces) != len(str(gt)):
                print(f"  [warn] box pieces {len(pieces)} != '{gt}' on {name}")
                continue
            for piece in pieces:
                add(gt, piece)


def box_pieces(m, box):
    """Digit pieces of one box, via the reader's own path (no classify)."""
    ix0 = box.x0 + int(0.14 * box.w)
    ix1 = box.x1 - int(0.14 * box.w)
    iy0 = box.y0 + int(0.14 * box.h)
    iy1 = box.y1 - int(0.14 * box.h)
    th, hue_ink = P.box_ink_ref(m, box)
    ink = P.close_v(P.ink_mask(m, iy0, iy1, ix0, ix1, th, hue_ink))
    comps = P.cc(ink, min_area=5, dx=ix0, dy=iy0)
    comps = P.merge_fragments(comps, ygap=max(2, int(0.12 * box.h)), max_w=box.w, max_h=box.h)
    comps = [c for c in comps if c.h >= 0.25 * box.h]
    if not comps:
        return []
    hmax = max(c.h for c in comps)
    comps = [c for c in comps if c.h >= 0.62 * hmax]
    comps.sort(key=lambda c: c.x0)
    out = []
    for c in comps:
        sub = ink[c.y0 - iy0:c.y1 - iy0 + 1, c.x0 - ix0:c.x1 - ix0 + 1]
        out.extend(P.split_wide(sub, max_w=int(1.30 * (c.y1 - c.y0 + 1))))
    return out


def harvest_menu_digits():
    """STRUCTURAL menu harvest — no classifier in the loop. Clusters flank a
    wide-short dash; home = left cluster, away = right cluster; pieces must
    match the GT string length. This keeps the exporter independent of the
    on-disk bank (no feedback loop)."""
    for name, (gh, ga) in MENU_GT.items():
        rgb = load_capture(name)
        H, W = rgb.shape[:2]
        m = P.Masks(rgb)
        min_area = max(20, int(0.00006 * W * H))
        comps = P.cc(m.loose, min_area)
        cand = [c for c in comps
                if max(8, 0.012 * H) <= c.h <= 0.15 * H and c.w <= 0.5 * W
                and P.dark_background(m, c, H)]
        if len(cand) < 2:
            print(f"  [warn] menu candidates missing on {name}")
            continue
        med_h = float(np.median([c.h for c in cand]))
        clusters = [(P.Box(c.x0, c.y0, c.x1, c.y1, c.area), [c]) for c in cand]
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
                    nb = P.Box(min(t.x0, c.x0), min(t.y0, c.y0), max(t.x1, c.x1),
                               max(t.y1, c.y1), t.area + c.area)
                    out[out.index(target)] = (nb, target[1] + cur[1])
                    changed = True
                else:
                    out.append(cur)
            clusters = out
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
        if dash is not None:
            left = [cl for cl in number_clus if cl[0].x1 < dash.x0]
            right = [cl for cl in number_clus if cl[0].x0 > dash.x1]
        else:
            cand2 = sorted(number_clus, key=lambda cl: cl[0].x0)
            left, right = (cand2[:1], cand2[1:2]) if len(cand2) >= 2 else ([], [])
        if not left or not right:
            print(f"  [warn] menu flanks missing on {name}")
            continue
        for (tb, _parts), gt in ((left[-1], gh), (right[0], ga)):
            mask = m.loose[tb.y0:tb.y1 + 1, tb.x0:tb.x1 + 1].copy()
            pieces = P.split_wide(mask, max_w=int(1.30 * mask.shape[0]))
            if len(pieces) != len(str(gt)):
                print(f"  [warn] menu piece count {len(pieces)} != {gt} on {name}")
                continue
            for piece in pieces:
                add(gt, piece)


def cluster_tokens(row, tok_gap):
    row = sorted(row, key=lambda c: c.x0)
    tokens = []
    for c in row:
        if tokens and c.x0 - tokens[-1][0].x1 <= tok_gap:
            t = tokens[-1]
            nb = P.Box(t[0].x0, min(t[0].y0, c.y0), max(t[0].x1, c.x1),
                       max(t[0].y1, c.y1), t[0].area + c.area)
            tokens[-1] = (nb, t[1] + [c])
        else:
            tokens.append((P.Box(c.x0, c.y0, c.x1, c.y1, c.area), [c]))
    return tokens


def harvest_table_digits():
    """Classification-free harvest: rows from y-clustering, tokens from
    x-proximity, the label column from cross-row occupancy (labels exist in
    every row and are wide; value columns are narrow), values by position.
    Piece->digit mapping uses the ground-truth string with a count check."""
    for name, gt_rows in TABLE_GT.items():
        rgb = load_capture(name)
        H, W = rgb.shape[:2]
        m = P.Masks(rgb)
        pairs = P.find_score_boxes(m, W, H)
        if not pairs:
            print(f"  [warn] no boxes on {name}")
            continue
        A, B = pairs[0]
        strip = P.find_strip_below(m, (A, B), H)
        box_h = max(A.h, B.h)
        box_w = min(A.w, B.w)
        y_top = (strip[1] if strip else max(A.y1, B.y1)) + max(2, int(0.12 * box_h))
        min_area = max(6, int(0.000008 * W * H))
        comps = P.cc(m.loose[y_top:H, :], min_area=min_area, dy=y_top)
        comps = [c for c in comps if 0.18 * box_h <= c.h <= 0.90 * box_h and c.w <= 1.2 * box_w]
        if len(comps) < 8:
            print(f"  [warn] too few comps on {name}")
            continue
        med_h = float(np.median([c.h for c in comps]))
        row_gap = max(3, 0.70 * med_h)
        comps.sort(key=lambda c: c.cy)
        rows = []
        for c in comps:
            if rows and abs(c.cy - rows[-1][-1].cy) < row_gap:
                rows[-1].append(c)
            else:
                rows.append([c])
        token_rows_raw = [cluster_tokens(r, max(2, 0.60 * med_h)) for r in rows]
        # a real stat row has home + label + away (>= 3 tokens); junk rows
        # (UI text outside the table) have fewer
        rows = [r for r, tr in zip(rows, token_rows_raw) if len(tr) >= 3]
        # merge split rows: two y-clusters of one logical row sit much closer
        # than the median row pitch
        if len(rows) >= 3:
            pitches = [rows[i + 1][0].cy - rows[i][0].cy
                       for i in range(len(rows) - 1) if rows[i + 1][0].cy > rows[i][0].cy]
            if pitches:
                pitch = float(np.median(pitches))
                merged = [rows[0]]
                for r in rows[1:]:
                    if r[0].cy - merged[-1][0].cy < 0.6 * pitch:
                        merged[-1] = merged[-1] + r
                    else:
                        merged.append(r)
                rows = merged
        if len(rows) != len(gt_rows):
            print(f"  [warn] {len(rows)} rows vs {len(gt_rows)} GT on {name}")
        tok_gap = max(2, 0.60 * med_h)
        token_rows = [cluster_tokens(r, tok_gap) for r in rows]
        # occupancy label band (4px bins)
        x_lo = min(t[0].x0 for tr in token_rows for t in tr)
        x_hi = max(t[0].x1 for tr in token_rows for t in tr)
        nb = max(1, (x_hi - x_lo) // 4)
        cover = np.zeros(nb + 1, dtype=int)
        for tr in token_rows:
            for tb, _ in tr:
                a = max(0, (tb.x0 - x_lo) // 4)
                b = min(nb, (tb.x1 - x_lo) // 4)
                cover[a:b + 1] += 1
        need = int(0.8 * len(token_rows))
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
        band_x0 = x_lo + best_s * 4
        band_x1 = x_lo + best_e * 4
        for tr, (gh, ga) in zip(token_rows, gt_rows):
            home = [t for t in tr if t[0].x1 < band_x0]
            away = [t for t in tr if t[0].x0 > band_x1]
            if not home or not away:
                print(f"  [warn] row without value tokens on {name}")
                continue
            tok_pieces(m, home[-1], str(gh), name)
            tok_pieces(m, away[0], str(ga), name)


def tok_pieces(m, tok, gt, name):
    tb, parts = tok
    mask = np.zeros((tb.y1 - tb.y0 + 1, tb.x1 - tb.x0 + 1), dtype=bool)
    for c in parts:
        # same part filter as the reader's token_pieces (wide/sparse drops)
        if c.area / max(1, c.w * c.h) < 0.15:
            continue
        mask[c.y0 - tb.y0:c.y1 - tb.y0 + 1, c.x0 - tb.x0:c.x1 - tb.x0 + 1] |= \
            m.loose[c.y0:c.y1 + 1, c.x0:c.x1 + 1]
    pieces = P.split_wide(mask, max_w=int(1.30 * mask.shape[0]))
    digit_pieces = []
    for piece in pieces:
        d, conf, margin = P.classify_glyph(piece)
        asp = piece.shape[1] / max(1, piece.shape[0])
        if d is not None and conf >= 0.40 and asp <= 0.85:
            digit_pieces.append(piece)
    if len(digit_pieces) != len(gt):
        print(f"  [warn] token pieces {len(digit_pieces)} != '{gt}' on {name}")
        return
    for piece, gch in zip(digit_pieces, gt):
        add(int(gch), piece)


def font_digit_mask(digit):
    from PIL import ImageDraw, ImageFont
    size = 96
    f = ImageFont.truetype(FONT, size=size)
    img = Image.new("L", (size * 2, size * 2), 0)
    d = ImageDraw.Draw(img)
    d.text((size // 2, size // 4), str(digit), font=f, fill=255)
    a = np.asarray(img) > 128
    ys, xs = np.where(a)
    if len(ys) == 0:
        return None
    return a[ys.min():ys.max() + 1, xs.min():xs.max() + 1]


def harvest_pass(transform):
    """One harvest sweep over all captures under a given encoding transform.
    The bank must cover what Prime actually ships (full-frame JPEG, capped
    resolution), so glyph grids are harvested under the production encodings
    as well as lossless."""
    global IMG_ACTIVE
    IMG_ACTIVE = transform


IMG_ACTIVE = None


def load_capture(name):
    img = Image.open(f"{IMG}/{name}").convert("RGB")
    import io
    if IMG_ACTIVE == "jpeg88":
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=88); buf.seek(0)
        img = Image.open(buf).convert("RGB")
    elif IMG_ACTIVE == "jpeg75":
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=75); buf.seek(0)
        img = Image.open(buf).convert("RGB")
    elif IMG_ACTIVE == "jpeg88_1280":
        if img.width > 1280:
            img = img.resize((1280, int(img.height * 1280 / img.width)), Image.LANCZOS)
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=88); buf.seek(0)
        img = Image.open(buf).convert("RGB")
    return np.asarray(img)


def main():
    # pass 1 (lossless): seeds the trusted bank.
    # degraded passes: a candidate template is admitted only if the LOSSLESS
    # bank still recognises it as its ground-truth digit (conf >= 0.55).
    # Otherwise an eroded '2' that looks like a '7' would enter the bank
    # labelled '2' and poison every later read of a degraded '2'.
    collected.clear()
    harvest_pass(None)
    harvest_box_digits()
    harvest_menu_digits()
    harvest_table_digits()
    lossless = list(collected)
    for p in ("jpeg88", "jpeg75", "jpeg88_1280"):
        collected.clear()
        harvest_pass(p)
        harvest_box_digits()
        harvest_menu_digits()
        harvest_table_digits()
        keep = []
        for (digit, mask, _w) in collected:
            g, asp = P.normalize_grid(mask)
            if g is None:
                continue
            d, conf = classify_against(g, lossless)
            if d == digit and conf >= 0.55:
                keep.append((digit, mask, 0.85))
        lossless = lossless + keep
        print(f"  pass {p}: {len(keep)}/{len(collected)} degraded templates admitted")
    collected.clear()
    collected.extend(lossless)
    build_and_write()


def classify_against(grid, bank_collected):
    """Classify a normalized grid against a list of (digit, mask) via the
    same scoring the reader uses."""
    holes = None
    best_d, best_c = None, 0.0
    gh = grid
    hh = P.count_holes(gh) if gh is not None else 0
    per = {}
    for entry in bank_collected:
        digit, mask = entry[0], entry[1]
        tg, _ = P.normalize_grid(mask)
        if tg is None:
            continue
        inter = np.logical_and(gh, tg).sum()
        union = np.logical_or(gh, tg).sum()
        sc = inter / union if union else 0.0
        th = P.count_holes(mask)
        if th != hh:
            sc *= 0.35
        if sc > per.get(digit, -1.0):
            per[digit] = sc
    if not per:
        return None, 0.0
    ranked = sorted(per.items(), key=lambda kv: -kv[1])
    return ranked[0][0], float(ranked[0][1])


COLLECTED = []


def build_and_write():
    # dedupe + grid-normalize, plus near-duplicate pruning WITHIN a digit
    # class: 300 near-identical templates collapse the margin between digits
    # and every read fails safe. Diversity is kept, redundancy is not.
    seen = set()
    bank = {}            # digit -> [(grid, aspect, holes, weight)]
    lossless_grids = {}  # digit -> [grids] for near-dup detection
    for digit, mask, weight in [(c[0], c[1], c[2] if len(c) > 2 else 1.0) for c in collected]:
        g, asp = P.normalize_grid(mask)
        if g is None:
            continue
        holes = P.count_holes(mask)
        key = (digit, g.tobytes())
        if key in seen:
            continue
        seen.add(key)
        dup = False
        for tg, _ta, _th, _tw in bank.get(digit, []):
            inter = np.logical_and(g, tg).sum()
            union = np.logical_or(g, tg).sum()
            prune = float(os.environ.get('F34_PRUNE', '0.94'))
            if union and inter / union >= prune:
                dup = True
                break
        if dup:
            continue
        bank.setdefault(digit, []).append((g, round(float(asp), 3), holes, weight))

    have = sorted(bank.keys())
    print("coverage:", {d: len(bank[d]) for d in have})
    for d in range(10):
        if d not in bank:
            mk = font_digit_mask(d)
            g, asp = P.normalize_grid(mk)
            bank[d] = [(g, round(float(asp), 3), P.count_holes(mk))]
            print(f"  font prototype added for digit {d}")

    lines = []
    lines.append("package com.peerlink.app.service")
    lines.append("")
    lines.append("/**")
    lines.append(" * Digit template bank for ScoreBoardDetector (F34), harvested from the")
    lines.append(" * calibration captures THROUGH the F34 structural reader (adaptive hue +")
    lines.append(" * local-contrast navy-aware ink), so the bank is coherent with the")
    lines.append(" * reader's pixel pipeline by construction. Each entry: digit, width/height")
    lines.append(" * aspect, enclosed-hole count, provenance weight (1.0 lossless / 0.85")
    lines.append(" * degraded-encoding), and a 16x24 binary grid packed into 48")
    lines.append(" * bytes (bit i = row-major, MSB first).")
    lines.append(" *")
    lines.append(" * Regenerate via tests/f34_reference/export_templates.py when calibration")
    lines.append(" * captures change. Grids are scale/position invariant: only shape matters.")
    lines.append(" */")
    lines.append("internal object ScoreBoardTemplates {")
    lines.append("")
    lines.append("    class T(val digit: Int, val aspect: Float, val holes: Int, val weight: Float, val bits: String)")
    lines.append("")
    lines.append("    val ALL: List<T> = listOf(")
    for d in range(10):
        for (g, asp, holes, weight) in bank[d]:
            by = bytearray(48)
            for i in range(384):
                if g[i // 16, i % 16]:
                    by[i // 8] |= (0x80 >> (i % 8))
            bits = bytes(by).hex().upper()
            lines.append(f'        T({d}, {asp}f, {holes}, {weight}f, "{bits}"),')
    lines.append("    )")
    lines.append("}")
    open(OUT_KT, "w").write("\n".join(lines) + "\n")
    total = sum(len(v) for v in bank.values())
    print(f"wrote {OUT_KT}: {total} templates")
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
