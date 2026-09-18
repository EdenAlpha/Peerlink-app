#!/usr/bin/env python3
"""Dump every table piece (conf, density, GT digit) to place gates on data."""
import numpy as np, sys
from PIL import Image
sys.path.insert(0, '/home/z/my-project/scripts')
import f34_prototype as P

IMG = "/home/z/my-project/work/f32/PeerLink-F32"
GT = {
 "Screenshot_20260905-184444.png": [(55,45),(4,1),(2,1),(0,1),(0,1),(1,0),(1,0),(68,80),(55,62),(0,0),(13,11),(3,5),(0,3)],
 "Screenshot_20260905-005550.png": [(54,46),(2,2),(2,1),(1,0),(0,0),(1,1),(0,1),(35,34),(29,25),(0,1),(8,5),(2,5),(1,2)],
 "Screenshot_20260808-135907.png": [(50,50),(4,3),(2,3),(0,0),(0,0),(1,1),(0,0),(37,38),(34,30),(1,0),(6,2),(0,4),(1,1)],
 "Screenshot_20260808-140853.png": [(50,50),(8,2),(6,2),(0,0),(0,0),(1,0),(0,0),(32,46),(26,40),(0,0),(5,4),(1,0),(0,2)],
}

rows_out = []
for name, gt_rows in GT.items():
    rgb = np.asarray(Image.open(f"{IMG}/{name}").convert("RGB"))
    H, W = rgb.shape[:2]
    m = P.Masks(rgb)
    A, B = P.find_score_boxes(m, W, H)[0]
    strip = P.find_strip_below(m, (A, B), H)
    parsed = P.table_rows(m, strip[1], (A, B), W, H)
    if parsed is None:
        print(f"{name}: NO TABLE")
        continue
    for (cy, h, a, bx0, bx1), (gh, ga) in zip(parsed, gt_rows):
        for side, tok, gt in (("H", h, str(gh)), ("A", a, str(ga))):
            pieces = P.token_pieces(m, tok)
            if len(pieces) != len(gt):
                rows_out.append((name[-8:], side, gt, "COUNT-MISMATCH", len(pieces), "", ""))
            for piece, gch in zip(pieces, gt):
                d, conf, mg = P.classify_glyph(piece)
                dens = float(piece.sum()) / max(1, piece.shape[0] * piece.shape[1])
                asp = piece.shape[1] / max(1, piece.shape[0])
                rows_out.append((name[-8:], side, gch, d, round(conf, 3), round(dens, 2), round(asp, 2)))

print(f"{'cap':<9}{'s':<3}{'gt':<4}{'d':<6}{'conf':<7}{'dens':<6}{'asp':<5}")
for r in rows_out:
    flag = ""
    if isinstance(r[3], int) and r[3] != int(r[2]):
        flag = " <-- MISREAD"
    if r[3] == "COUNT-MISMATCH":
        flag = " <-- COUNT"
    print(f"{r[0]:<9}{r[1]:<3}{str(r[2]):<4}{str(r[3]):<6}{str(r[4]):<7}{str(r[5]):<6}{str(r[6]):<5}{flag}")
