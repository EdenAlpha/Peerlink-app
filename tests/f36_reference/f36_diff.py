"""F36 differential: reproduce the review's exact 1-1-vs-3-1 mechanism at
SCORE-BOX scale, using a real '3' harvested from the captures.

Mechanism chain (their diagnosis):
  1. both waist joints drop below the ink threshold -> 3 pieces
  2. the curls additionally shatter into sub-glyph fragments
  3. merge_fragments (50% overlap rule) refuses to glue them
  4. the h-filters (h >= 0.25*box.h, h >= 0.62*hmax) kill the curl fragments
  5. the bare spine survives and classifies as '1'  -> 1-1 instead of 3-1

Variants (all from the real glyph, 2x nearest-upscaled to box scale):
  V1 two-joint break, curls intact
  V2 two-joint break + shattered curls  (their frame's fragmentation)
  V3 V2 under the NEW proximity rule
plus: the bare spine alone -> what does the classifier say?
"""
import os
import sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'f34_reference'))
import prototype as P

import f36_probe as PBP

IMG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')


def show(mask, label):
    print(f"--- {label} ({mask.shape[0]}x{mask.shape[1]}) ---")
    for r in mask.astype(int):
        print(''.join('#' if v else '.' for v in r))


def upscale2(mask):
    return np.kron(mask, np.ones((2, 2), dtype=bool))


def shatter_curls(sp):
    """Cut each curl once more at its thinnest row-band (left arc region) --
    the extra fragmentation heavy compression produces on a different
    device's rendering."""
    out = sp.copy()
    h, w = out.shape
    for lo, hi in ((int(0.02 * h), int(0.20 * h)),      # top curl band
                   (int(0.80 * h), int(0.98 * h))):     # bottom curl band
        band = out[lo:hi, :]
        if not band.any():
            continue
        rows = band.sum(axis=1)
        yc = lo + int(np.argmin(rows))
        out[yc:yc + 1, :] = False
    return out


def pieces_of(canvas, min_area=5):
    return P.cc(canvas, min_area=min_area)


def run(canvas, scale_h, scale_w, rule):
    return PBP.read_box(canvas, scale_h, scale_w, rule)


def main():
    stats = PBP.harvest_stat_digits()
    three = [s for s in stats if s[1] == 3][0]
    g16 = three[2]
    g = upscale2(g16)                      # ~32px tall score-box-scale '3'
    gh, gw = g.shape
    box_h = int(round(gh / 0.72))
    box_w = box_h
    canvas = np.zeros((gh + 6, gw + 6), dtype=bool)
    canvas[3:3 + gh, 3:3 + gw] = g
    print(f"glyph {gh}x{gw}, scale_h(box)={box_h}")

    d, conf, mg = P.classify_glyph(g)
    print(f"baseline classify: {d} conf={conf:.3f} margin={mg:.3f}")

    # bare spine: rows between the two waist joints, right-side columns only
    row_ink = g.sum(axis=1)
    lo, hi = int(0.40 * gh), int(0.62 * gh)
    spine = g[lo:hi, int(0.45 * gw):]
    if spine.any():
        d1, c1, m1 = P.classify_glyph(spine)
        print(f"bare spine ({spine.shape}) classify: {d1} "
              f"conf={c1 if c1 else -1:.3f} margin={m1 if m1 else -1:.3f}")
        show(spine, "bare spine")

    for label, mask in (
            ("V1 two-joint break", PBP.split_two_joints(g, 2)),
            ("V2 two-joint + shattered curls",
             shatter_curls(PBP.split_two_joints(g, 2)))):
        if mask is None:
            print(f"{label}: no split")
            continue
        cv = np.zeros_like(canvas)
        cv[3:3 + mask.shape[0], 3:3 + mask.shape[1]] = mask
        comps = pieces_of(cv)
        print(f"\n== {label}: {len(comps)} pieces "
              f"{[(c.x0, c.y0, c.x1, c.y1, c.area) for c in comps]}")
        old = run(cv, box_h, box_w, 'old')
        new = run(cv, box_h, box_w, 'new')
        print(f"   OLD rule -> {old}")
        print(f"   NEW rule -> {new}")
        show(mask, label)


if __name__ == '__main__':
    main()
