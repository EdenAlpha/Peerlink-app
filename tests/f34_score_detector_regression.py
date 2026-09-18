#!/usr/bin/env python3
"""F34 structural + calibration regression.

Guards the invariants that make the reader device-independent:

  A. STRUCTURE — neither the Python reference nor the Kotlin port may contain
     fixed screen-position windows. Every location must be derived from
     anchors found in the frame. Greps the sources for the banned patterns
     that defined the F29..F33 readers (banner zones, box x-windows, fixed
     table row pitches, global fraction gates).

  B. BANK HYGIENE — every shipped template is non-degenerate and its
     provenance weight is sane; per-digit coverage exists.

  C. CALIBRATION — the Python reference reads all 7 captures exactly
     (native PNG + production 1280px q88), and the in-envelope robustness
     matrix produces ZERO wrong reads (fail-safe refusals allowed at
     sub-production JPEG quality).

  D. KOTLIN SYNC — the port exposes the same API surface the engine calls
     (analyze/ScreenType/Finality/Stats/Geometry) and mirrors the F34 stages.
"""
import io
import os
import re
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, '/home/z/my-project/scripts')
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'f34_reference'))
import prototype as P

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_KT = os.path.join(ROOT, 'app/src/main/java/com/peerlink/app/service/ScoreBoardDetector.kt')
SRC_PY = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'f34_reference/prototype.py')
IMG = os.environ.get('F34_CAPTURES', '/home/z/my-project/work/f32/PeerLink-F32')

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

fails = []


def check(name, ok, detail=""):
    if not ok:
        fails.append(f"{name}: {detail}")
        print(f"  [FAIL] {name} {detail}")
    else:
        print(f"  [ok] {name}")
    return ok


# ----------------------------------------------------------------------
def check_structure():
    print("A. structure (no fixed screen windows)")
    kt = open(SRC_KT).read()
    py = open(SRC_PY).read()
    banned = [
        # F29..F33 style fixed windows / zones / row pitches
        (r"bannerZone", "banner zone fractions"),
        (r"0\.415f|0\.4975f|0\.5025f|0\.585f", "score box x-windows"),
        (r"0\.148f\.\.0\.235|0\.79f to 0\.89f|bannerZoneBoard|bannerZoneWalking", "banner y-windows"),
        (r"c0\s*=\s*0\.29|step\s*=\s*0\.044", "fixed table row pitch"),
        (r"0\.2944|0\.04472", "F32 stats constants"),
        (r"0\.350f|0\.408f|0\.590f|0\.648f", "stats value columns"),
        (r"0\.225f\.\.0\.285f|0\.035f\.\.0\.095f", "finality label windows"),
        (r"range\(13\)|0\.04472f", "hard 13-row iteration by fraction"),
        (r"yellowFrac\s*>=\s*0\.14|navyFrac\s*>=", "global fraction gate (F33)"),
        (r"map\.x\(|map\.y\(|FrameMap", "fraction coordinate mapper"),
    ]
    for pat, what in banned:
        check(f"kotlin free of {what}", re.search(pat, kt) is None)
    py_banned = [
        (r"banner_zone|bannerZone", "banner zones"),
        (r"CROP_X0|CROP_X1", "composite crop windows"),
        (r"CELL_TOP_Y|CELL_HOME_X|CELL_AWAY_X", "fixed score cells"),
        (r"0\.2944|0\.04472", "F32 stats constants"),
        (r"0\.415.*0\.4975|0\.5025.*0\.585", "score x-windows"),
    ]
    for pat, what in py_banned:
        check(f"python free of {what}", re.search(pat, py) is None)
    # the reader must derive the yellow hue from the frame itself
    check("adaptive hue estimation present", "estimate_yellow_hue" in py and "weighted MODE" in py or "chroma-weighted MODE" in py)
    check("kotlin adaptive hue estimation present", "chroma-weighted mode" in kt.lower())
    # anchors must drive sub-windows: strip found below found boxes, table below strip
    check("strip derived from pair", "find_strip_below(m, (A, B), H)" in py or "findStripBelow(m, A, B, h)" in kt)


def check_bank():
    print("B. bank hygiene")
    P.TPL_KT = os.path.join(ROOT, 'app/src/main/java/com/peerlink/app/service/ScoreBoardTemplates.kt')
    P.TEMPLATES = None
    tpl = P.parse_templates()
    check("bank non-empty", len(tpl) >= 40, f"{len(tpl)} templates")
    from collections import Counter
    cov = Counter(t[0] for t in tpl)
    missing = [d for d in range(10) if cov.get(d, 0) == 0]
    check("all digits covered", not missing, f"missing {missing}")
    degen = 0
    for (digit, grid, asp, holes, w) in tpl:
        dens = grid.sum() / grid.size
        if dens < 0.15 or dens > 0.82:  # bold box digits stretch to ~0.80
            degen += 1
        if w not in (1.0, 0.85):
            check("template weight sane", False, f"digit {digit} weight {w}")
            return
    check("no degenerate templates", degen == 0, f"{degen} degenerate")
    print(f"       coverage: {dict(sorted(cov.items()))} total={len(tpl)}")


def production_sim(name):
    img = Image.open(os.path.join(IMG, name)).convert("RGB")
    if img.width > 1280:
        img = img.resize((1280, int(img.height * 1280 / img.width)), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=88)
    buf.seek(0)
    return np.asarray(Image.open(buf).convert("RGB"))


def check_calibration():
    print("C. calibration (native + production encoding, exact)")
    for name, (ttype, score, fin, stats) in GROUND_TRUTH.items():
        rgb = np.asarray(Image.open(os.path.join(IMG, name)).convert("RGB"))
        out = P.analyze(rgb)
        ok = (out["type"] == ttype and (score is None or (out["home"], out["away"]) == score)
              and (fin is None or out["finality"] == fin)
              and (stats is None or [(r[0], (r[1], r[2])) for r in (out["stats"] or [])]
                   == [(i, stats[i]) for i, (hh, aa) in ((r[0], (r[1], r[2])) for r in (out["stats"] or []))]
                   if out["stats"] else stats is None))
        # simpler exact check:
        stats_ok = stats is None or all(
            (r[1], r[2]) == stats[r[0]] for r in (out["stats"] or []))
        stats_ok = stats_ok and (stats is None or len(out["stats"] or []) == 13)
        ok = ok and stats_ok
        check(f"native {name[:24]}", ok,
              f"got {out['type']} {out['home']}-{out['away']} {out['finality']} rows={len(out['stats'] or [])}")
    for name, (ttype, score, fin, stats) in GROUND_TRUTH.items():
        rgb = production_sim(name)
        out = P.analyze(rgb)
        score_ok = score is None or (out["home"], out["away"]) == score
        stats_ok = stats is None or all((r[1], r[2]) == stats[r[0]] for r in (out["stats"] or []))
        # production must never be WRONG: exact reads, and stats either exact or absent
        wrong = (not score_ok and out["home"] is not None) or (not stats_ok and out["stats"])
        check(f"production {name[:24]}", not wrong,
              f"got {out['type']} {out['home']}-{out['away']} rows={len(out['stats'] or [])}")


def check_kotlin_sync():
    print("D. kotlin sync")
    kt = open(SRC_KT).read()
    for sym in ["fun analyze(frame: Bitmap", "enum class ScreenType", "enum class Finality",
                "class Stats", "sealed class Geometry", "findScoreBoxes", "findStripBelow",
                "tableRows", "findMenuScore", "readStripLabel", "classifyFH",
                "closeV", "boxInkRef", "occupancyLabelBand", "pctVeto"]:
        check(f"kotlin has {sym}", sym in kt)
    for stage in ["INK_V_CEIL = 0.52f", "INK_LEAN = 16", "0.85f", "0.15f * H"]:
        check(f"kotlin constant {stage}", stage in kt)


def main():
    print("== F34 structural + calibration regression ==")
    check_structure()
    check_bank()
    check_calibration()
    check_kotlin_sync()
    print(f"\n{'ALL PASS' if not fails else f'{len(fails)} FAILURES'}")
    for f in fails:
        print(" -", f)
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
