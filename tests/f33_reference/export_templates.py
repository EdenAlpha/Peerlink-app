#!/usr/bin/env python3
"""Export the validated template bank + calibration constants as Kotlin code."""
import os, sys
sys.path.insert(0, '/home/z/my-project/scripts')
import numpy as np
from PIL import Image
import prototype as P

templates = P.build_all()
cls = P.GlyphClassifier(templates)

GT = {
    "Screenshot_20260905-184444.png": [(55,45),(4,1),(2,1),(0,1),(0,1),(1,0),(1,0),(68,80),(55,62),(0,0),(13,11),(3,5),(0,3)],
    "Screenshot_20260905-005550.png": [(54,46),(2,2),(2,1),(1,0),(0,0),(1,1),(0,1),(35,34),(29,25),(0,1),(8,5),(2,5),(1,2)],
    "Screenshot_20260808-135907.png": [(50,50),(4,3),(2,3),(0,0),(0,0),(1,1),(0,0),(37,38),(34,30),(1,0),(6,2),(0,4),(1,1)],
    "Screenshot_20260808-140853.png": [(50,50),(8,2),(6,2),(0,0),(0,0),(1,0),(0,0),(32,46),(26,40),(0,0),(5,4),(1,0),(0,2)],
}
collected=[]
for name, gt in GT.items():
    a = np.asarray(Image.open(os.path.join(P.SRC,name)).convert("RGB"))
    m = P.Masks(a)
    P.read_stats_table(a, m, cls, collect=collected, gt=gt)
all_t = list(templates)
for digit, piece in collected:
    g, asp = P.normalize_grid(piece)
    if g is None: continue
    all_t.append((digit, g, asp, P.count_holes(piece)))
print(f"total templates before dedupe: {len(all_t)}")

# Dedupe: per digit, keep at most 6 templates (greedy, max diversity by Tanimoto < 0.97)
keep = []
for d in range(10):
    ds = [t for t in all_t if t[0]==d]
    sel = []
    for t in ds:
        if all(P.tanimoto(t[1], s[1]) < 0.97 for s in sel):
            sel.append(t)
        if len(sel) >= 6: break
    keep += sel
print(f"after dedupe: {len(keep)}")

def grid_to_bits(g):
    bits = 0
    for i, v in enumerate(g.flatten()):
        if v: bits |= (1 << i)
    return bits

lines = []
for (digit, g, asp, holes) in keep:
    b = grid_to_bits(g)
    hexs = b.to_bytes(48, 'little').hex().upper()
    lines.append(f'        T({digit}, {asp:.2f}f, {holes}, "{hexs}"),')

out = f"""package com.peerlink.app.service

/**
 * Digit template bank for ScoreBoardDetector, harvested from real eFootball
 * captures (walking banner, statistics boards, result menus) and completed
 * with font-rendered prototypes for digits that never appeared in the
 * calibration set. Each entry: digit, width/height aspect, enclosed-hole
 * count, and a 16x24 binary grid packed into 48 bytes (bit i = row-major).
 *
 * Regenerate via scripts/export_templates.py when calibration captures change.
 */
internal object ScoreBoardTemplates {{

    class T(val digit: Int, val aspect: Float, val holes: Int, val bits: String)

    val ALL: List<T> = listOf(
{chr(10).join(lines)}
    )
}}
"""
with open("/home/z/my-project/scripts/ScoreBoardTemplates.kt", "w") as f:
    f.write(out)
print("written ScoreBoardTemplates.kt", len(out), "bytes")
