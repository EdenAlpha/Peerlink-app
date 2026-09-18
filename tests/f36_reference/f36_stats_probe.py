"""Measure what token_value_local's aspect<0.28 piece-skip actually drops.

For every native capture + a few stress transforms: parse the stats table,
and for each value token log every piece that the local reader's aspect
clause would skip: bbox, aspect, classify result, the token's global read
and local read, and whether the row emitted or refused.
"""
import os
import sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'f34_reference'))
import prototype as P

IMG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
CAPS = sorted(f for f in os.listdir(IMG_DIR) if f.endswith('.png'))


def transform_q60(rgb):
    import io
    buf = io.BytesIO()
    Image.fromarray(rgb).save(buf, format='JPEG', quality=60)
    return np.asarray(Image.open(buf).convert('RGB'))


def transform_night(rgb):
    from PIL import ImageEnhance
    im = Image.fromarray(rgb)
    r, g, b = im.split()
    r = r.point(lambda v: min(255, int(v * 1.08 + 18)))
    b = b.point(lambda v: int(v * 0.82))
    im = Image.merge('RGB', (r, g, b))
    return np.asarray(im)


def probe_frame(name, rgb):
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
        parsed = P.table_rows(m, yref, pair, W, H)
        if parsed is None:
            return
        print(f"  {name}: {len(parsed)} rows parsed")
        for idx, home_tok, away_tok, lx0, lx1 in parsed:
            if idx > 12:
                continue
            for side, tok, edge, left in (('home', home_tok, lx0, False),
                                          ('away', away_tok, lx1, True)):
                gv = P.token_value(m, tok)
                # replicate token_value_local with instrumentation
                tb, parts = tok
                h = max(1, tb.h)
                pad_out = int(0.70 * h) + 1
                pad_in, pad_y = 2, 2
                if left:
                    x0 = max(0, tb.x0 - pad_in)
                    x1 = min(W - 1, tb.x1 + pad_out)
                else:
                    x0 = max(0, tb.x0 - pad_out)
                    x1 = min(W - 1, tb.x1 + pad_in)
                if edge is not None:
                    if left:
                        x0 = max(x0, edge + 2)
                    else:
                        x1 = min(x1, edge - 2)
                y0 = max(0, tb.y0 - pad_y)
                y1 = min(H - 1, tb.y1 + pad_y)
                if x1 - x0 < 2 or y1 - y0 < 4:
                    lv = None
                else:
                    win_v = m.v[y0:y1 + 1, x0:x1 + 1].ravel()
                    t, ok = P.otsu_threshold(win_v)
                    if not ok:
                        lv = None
                    else:
                        win_h = m.h[y0:y1 + 1, x0:x1 + 1]
                        win_ch = m.chroma[y0:y1 + 1, x0:x1 + 1]
                        dh = np.abs(((win_h - m.hue_y + 180.0) % 360.0) - 180.0)
                        mask = (m.v[y0:y1 + 1, x0:x1 + 1] >= t) & \
                               ((dh <= 50.0) | (win_ch >= 80))
                        comps = P.cc(mask, min_area=6) if mask.sum() >= 8 else []
                        clean = np.zeros_like(mask)
                        for c in comps:
                            clean[c.y0:c.y1 + 1, c.x0:c.x1 + 1] |= \
                                mask[c.y0:c.y1 + 1, c.x0:c.x1 + 1]
                        pieces = P.split_wide(clean,
                                              max_w=int(1.30 * clean.shape[0]))
                        hmax = max((p.shape[0] for p in pieces), default=0)
                        digits = ""
                        skipped = []
                        for piece in pieces:
                            ph, pw = piece.shape[0], piece.shape[1]
                            asp = pw / max(1, ph)
                            if ph < max(4, int(0.60 * hmax)):
                                continue
                            if asp < 0.28:
                                d, conf, mg = P.classify_glyph(piece)
                                skipped.append((ph, pw, round(asp, 2), d,
                                                round(conf, 2) if conf else None))
                                continue
                            if P.pct_veto(piece):
                                continue
                            d, conf, mg = P.classify_glyph(piece)
                            if d is None or conf < 0.60 or mg < 0.03:
                                digits = None
                                break
                            digits += str(d)
                        lv = None
                        if digits:
                            lv = int(digits)
                val = P.cross_value(gv, lv)
                tag = 'OK' if val is not None else 'REFUSED'
                if skipped or val is None:
                    print(f"    row{idx:2d} {side:4s} gv={gv} lv={lv} -> {tag:7s}"
                          f" aspect-skips={skipped}")


def main():
    for name in CAPS:
        rgb = np.asarray(Image.open(os.path.join(IMG_DIR, name)).convert('RGB'))
        for tname, t in (('native', lambda x: x), ('q60', transform_q60),
                         ('night', transform_night)):
            try:
                probe_frame(f"{name[:22]}/{tname}", t(rgb))
            except Exception as e:
                print(f"  {name[:22]}/{tname}: ERROR {e}")


if __name__ == '__main__':
    main()
