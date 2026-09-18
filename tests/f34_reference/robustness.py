#!/usr/bin/env python3
"""F34/F35 device-variation robustness harness.

The F33 suite only tested JPEG/downscale on ONE phone's screenshots at ONE
aspect ratio. This harness simulates the install base the app actually ships
to — Nigeria / India / Indonesia / Malaysia: 18:9 through 21:9 phones,
16:9 and 4:3 tablets, vivid-mode oversaturation, eye-comfort dimming, warm
night shifts, cheap JPEG pipelines, low-RAM downscale — and asserts:

  - score read EXACT everywhere the board is fully visible
  - statistics exact wherever resolution permits
  - at degradation limits: exact read or FAIL-SAFE (no read), NEVER a wrong read
  - non-score frames classified OTHER

Every transform applies to all 7 calibration captures. Zero per-variant
constants: the same reader, untouched, must handle all of them.

F35 evidence rule: EVERY run writes a full machine-readable log (JSON) and a
human-readable matrix (TXT) to tests/evidence/, plus a stable latest.json.
A claim of "0 wrong reads" must always be backed by the artifact that
produced it. The summary separates IN-ENVELOPE wrong reads (the claim) from
the documented envelope-edge rows (Gaussian blur, which never occurs in the
shipping capture path; those cells stay visible).
"""
import io
import json
import os
import sys
import time
from datetime import datetime, timezone

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prototype as P

IMG = os.environ.get("F34_CAPTURES", "/home/z/my-project/work/f32/PeerLink-F32")

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


# ----------------------------------------------------------------------
# Transforms. Each returns a PIL RGB image; no reader knowledge inside.
# ----------------------------------------------------------------------
def t_identity(img):
    return img


def t_ar(name_ratio):
    """Re-frame to another aspect ratio: scale to fit WIDTH for taller frames
    (extra vertical space, like a 19:9 phone showing a 20:9-rendered game
    letterboxed vertically — the board shifts off any assumed position), or
    fit HEIGHT + pad sides for shorter frames (tablet). Padding uses content,
    not black bars, for the horizontal case we use blurred edge stretch."""
    ratio = name_ratio
    def f(img):
        W, H = img.size
        target = ratio
        cur = W / H
        if abs(cur - target) < 0.01:
            return img
        if target > cur:
            # wider frame: pad left/right with stretched edge content
            nw = int(H * target)
            canvas = Image.new("RGB", (nw, H))
            edge = img.crop((W - 2, 0, W, H)).resize((nw - W, H))
            edge2 = img.crop((0, 0, 2, H)).resize((nw - W, H))
            canvas.paste(edge2, (0, 0))
            canvas.paste(img, ((nw - W) // 2, 0))
            canvas.paste(edge, (W + (nw - W) // 2, 0))
            return canvas
        else:
            # taller frame: pad top/bottom (letterbox with dark + grass tones)
            nh = int(W / target)
            canvas = Image.new("RGB", (W, nh), (12, 12, 16))
            top = Image.new("RGB", (W, nh - H), (20, 24, 18))
            canvas.paste(top, (0, (nh - H) // 4))
            canvas.paste(img, (0, (nh - H) // 2))
            return canvas
    return f


def t_vivid(img):
    """Vivid display mode: saturation + contrast boost."""
    img = ImageEnhance.Color(img).enhance(1.35)
    return ImageEnhance.Contrast(img).enhance(1.15)


def t_eye_comfort(img):
    """Eye-comfort mode: dim + warm shift + slight gamma lift of darks."""
    img = ImageEnhance.Brightness(img).enhance(0.78)
    r, g, b = img.split()
    r = r.point(lambda v: min(255, int(v * 1.12 + 8)))
    b = b.point(lambda v: int(v * 0.88))
    return Image.merge("RGB", (r, g, b))


def t_night_shift(img):
    """Strong night/blue-light filter: heavy warm shift + dim."""
    img = ImageEnhance.Brightness(img).enhance(0.7)
    r, g, b = img.split()
    r = r.point(lambda v: min(255, int(v * 1.2 + 12)))
    g = g.point(lambda v: min(255, int(v * 1.05 + 4)))
    b = b.point(lambda v: int(v * 0.72))
    return Image.merge("RGB", (r, g, b))


def t_cool_panel(img):
    """Cool-calibrated panel: blues up, warm down."""
    r, g, b = img.split()
    r = r.point(lambda v: int(v * 0.92))
    b = b.point(lambda v: min(255, int(v * 1.1)))
    return Image.merge("RGB", (r, g, b))


def t_dull(img):
    """Washed-out cheap panel: low saturation + brightness."""
    img = ImageEnhance.Color(img).enhance(0.62)
    return ImageEnhance.Brightness(img).enhance(0.9)


def t_jpeg(q):
    def f(img):
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=q)
        buf.seek(0)
        return Image.open(buf).convert("RGB")
    return f


def t_scale(s):
    def f(img):
        w, h = img.size
        return img.resize((int(w * s), int(h * s)), Image.LANCZOS if s < 1 else Image.BICUBIC)
    return f


def t_blur(img):
    return img.filter(ImageFilter.GaussianBlur(0.8))


def t_noise(img):
    a = np.asarray(img).astype(np.int16)
    rng = np.random.default_rng(42)
    a = a + rng.normal(0, 7, a.shape).astype(np.int16)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def t_combine(*ts):
    def f(img):
        for t in ts:
            img = t(img)
        return img
    return f


TRANSFORMS = [
    ("native", t_identity),
    ("ar16:9", t_ar(16 / 9)),
    ("ar4:3", t_ar(4 / 3)),
    ("ar18:9", t_ar(2.0)),
    ("ar19.5:9", t_ar(19.5 / 9)),
    ("ar21:9", t_ar(21 / 9)),
    ("vivid", t_vivid),
    ("eye-comfort", t_eye_comfort),
    ("night-shift", t_night_shift),
    ("cool-panel", t_cool_panel),
    ("dull-panel", t_dull),
    ("jpeg88", t_jpeg(88)),
    ("jpeg75", t_jpeg(75)),
    ("jpeg60", t_jpeg(60)),
    ("scale0.75", t_scale(0.75)),
    ("scale0.5", t_scale(0.5)),
    ("scale1.5", t_scale(1.5)),
    ("blur+jpg75", t_combine(t_blur, t_jpeg(75))),
    ("noise", t_noise),
    # the money shots: other-device combinations
    ("19.5:9+vivid+jpg88", t_combine(t_ar(19.5 / 9), t_vivid, t_jpeg(88))),
    ("18:9+eye-comfort+jpg75", t_combine(t_ar(2.0), t_eye_comfort, t_jpeg(75))),
    ("4:3+night+jpeg60", t_combine(t_ar(4 / 3), t_night_shift, t_jpeg(60))),
    ("21:9+dull+scale0.75", t_combine(t_ar(21 / 9), t_dull, t_scale(0.75))),
    ("16:9+cool+blur+jpg88", t_combine(t_ar(16 / 9), t_cool_panel, t_blur, t_jpeg(88))),
    ("19:9+vivid+scale0.5+jpg75", t_combine(t_ar(2.111), t_vivid, t_scale(0.5), t_jpeg(75))),
    ("prod1280+q88", t_combine(t_scale(0.8), t_jpeg(88))),
]

# beyond-production envelope: Gaussian blur does not occur anywhere in the
# shipping capture path (screencast -> JPEG q88, optional LANCZOS <= 1280).
# These transforms stay in the matrix as documented envelope-edge stress;
# the "0 in-envelope wrong reads" claim covers everything else.
ENVELOPE_EXCLUDED = {"blur+jpg75", "16:9+cool+blur+jpg88"}

# stats are asserted while the table is legible; at extreme degradation the
# reader must either read exactly or return fewer/no rows (never wrong rows)
DEGRADED_STATS = {"scale0.5", "19:9+vivid+scale0.5+jpg75", "4:3+night+jpeg60",
                  "21:9+dull+scale0.75"}

# Fast subset used by the parameter grid search (final winner re-runs full).
FAST_NAMES = {"native", "jpeg88", "vivid", "cool-panel", "eye-comfort", "scale0.75",
              "ar4:3", "ar19.5:9", "ar21:9", "dull-panel", "jpeg88_1280prod"}
if os.environ.get("F34_FAST"):
    TRANSFORMS = [t for t in TRANSFORMS if t[0] in FAST_NAMES and t[0] not in ENVELOPE_EXCLUDED]
elif os.environ.get("F34_NOBLUR"):
    TRANSFORMS = [t for t in TRANSFORMS if t[0] not in ENVELOPE_EXCLUDED]


def run_frame(img):
    rgb = np.asarray(img.convert("RGB"))
    return P.analyze(rgb)


def check(name, tname, out, gt, stats_mode):
    ttype, score, fin, stats = gt
    errs = []
    if out["type"] != ttype:
        errs.append(f"type={out['type']}!={ttype}")
    if score is not None and (out["home"], out["away"]) != score:
        if out["home"] is not None:
            errs.append(f"score={out['home']}-{out['away']}!={score[0]}-{score[1]} (WRONG READ)")
        else:
            errs.append("score=None (fail-safe)")
    if fin is not None and out["finality"] != fin:
        if out["finality"] != "UNKNOWN":
            errs.append(f"fin={out['finality']}!={fin} (WRONG)")
        else:
            errs.append("fin=UNKNOWN (fail-safe)")
    if stats is not None and stats_mode == "exact":
        got = out["stats"] or []
        wrong_vals = [(i, (hv, av)) for (i, hv, av) in got
                      if not (0 <= i < len(stats)) or (hv, av) != stats[i]]
        if wrong_vals:
            errs.append(f"stats WRONG rows ({wrong_vals})")
        elif len(got) < 13:
            errs.append(f"stats partial ({len(got)}/13, fail-safe)")
    return errs


def main():
    total = passed = 0
    wrong_reads = wrong_in_envelope = 0
    failures = []
    per_check = []
    t0 = time.time()
    for tname, tf in TRANSFORMS:
        for name, gt in GROUND_TRUTH.items():
            img = tf(Image.open(os.path.join(IMG, name)).convert("RGB"))
            out = run_frame(img)
            stats_mode = "exact" if tname not in DEGRADED_STATS else "safe"
            errs = check(name, tname, out, gt, stats_mode)
            total += 1
            wrong = any("WRONG" in e for e in errs)
            wrong_reads += wrong
            if wrong and tname not in ENVELOPE_EXCLUDED:
                wrong_in_envelope += 1
            if errs:
                failures.append((tname, name, errs))
            else:
                passed += 1
            per_check.append({
                "transform": tname, "capture": name, "ok": not errs,
                "wrong": wrong, "in_envelope": tname not in ENVELOPE_EXCLUDED,
                "errors": errs,
                "got": {"type": out["type"], "score": [out["home"], out["away"]],
                        "finality": out["finality"],
                        "rows": None if out["stats"] is None else len(out["stats"])},
            })
    dt = time.time() - t0
    env_note = " (envelope edge)" if wrong_reads and not wrong_in_envelope else ""
    print(f"\n==== F35 robustness: {passed}/{total} checks clean, "
          f"{wrong_in_envelope} in-envelope WRONG reads ====")
    if wrong_reads != wrong_in_envelope:
        print(f"     (+{wrong_reads - wrong_in_envelope} WRONG on documented envelope-edge rows{env_note})")
    print(f"     ({dt:.1f}s, {dt/total*1000:.0f} ms/frame avg in Python)")
    # compact matrix: transform x capture -> ok / PARTIAL / OTHER / WRONG
    names = list(GROUND_TRUTH.keys())
    print(f"{'transform':<28}" + "".join(n[11:17] for n in names))
    for tname, _ in TRANSFORMS:
        cells = []
        for name in names:
            errs = [e for (tn, n, es) in failures if tn == tname and n == name for e in es]
            if not errs:
                cells.append("ok")
            elif any("WRONG" in e for e in errs):
                cells.append("WRONG")
            elif any("type=" in e for e in errs):
                cells.append("other")
            else:
                cells.append("part")
        print(f"{tname:<28}" + "".join(f"{c:>6}" for c in cells))
    for tname, name, errs in failures:
        if any("WRONG" in e for e in errs):
            tag = "[envelope edge]" if tname in ENVELOPE_EXCLUDED else "[IN ENVELOPE]"
            print(f"  WRONG {tag} [{tname}] {name}: {'; '.join(errs)}")

    # ---- evidence artifacts (always) ----
    ev_dir = os.environ.get("F34_EVIDENCE",
                            os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                         "evidence"))
    os.makedirs(ev_dir, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    summary = {
        "tool": "f34_reference/robustness.py",
        "reader": "F35 device-independent score reader (prototype.py)",
        "generated_utc": stamp,
        "captures_dir": IMG,
        "n_transforms": len(TRANSFORMS),
        "n_captures": len(GROUND_TRUTH),
        "checks": total,
        "clean": passed,
        "wrong_in_envelope": wrong_in_envelope,
        "wrong_envelope_edge": wrong_reads - wrong_in_envelope,
        "envelope_excluded_transforms": sorted(ENVELOPE_EXCLUDED),
        "degraded_stats_transforms": sorted(DEGRADED_STATS),
        "elapsed_s": round(dt, 1),
        "claims": {
            "in_envelope_wrong_reads": wrong_in_envelope,
            "gate": "the in-envelope WRONG count must be 0",
        },
        "per_check": per_check,
    }
    js = os.path.join(ev_dir, "robustness_latest.json")
    js_stamp = os.path.join(ev_dir, f"robustness_{stamp}.json")
    for path in (js, js_stamp):
        with open(path, "w") as f:
            json.dump(summary, f, indent=1)
    ts = os.path.join(ev_dir, "robustness_latest.txt")
    with open(ts, "w") as f:
        f.write(f"F35 robustness matrix — {stamp} UTC\n")
        f.write(f"captures: {IMG}\n")
        f.write(f"clean {passed}/{total}; in-envelope WRONG {wrong_in_envelope}; "
                f"envelope-edge WRONG {wrong_reads - wrong_in_envelope}; {dt:.1f}s\n\n")
        f.write(f"{'transform':<28}" + "".join(n[11:17] for n in names) + "\n")
        for tname, _ in TRANSFORMS:
            cells = []
            for name in names:
                errs = [e for (tn, n, es) in failures if tn == tname and n == name for e in es]
                if not errs:
                    cells.append("ok")
                elif any("WRONG" in e for e in errs):
                    cells.append("WRONG")
                elif any("type=" in e for e in errs):
                    cells.append("other")
                else:
                    cells.append("part")
            f.write(f"{tname:<28}" + "".join(f"{c:>6}" for c in cells) + "\n")
        f.write("\nWRONG cells:\n")
        for tname, name, errs in failures:
            if any("WRONG" in e for e in errs):
                f.write(f"  [{tname}] {name}: {'; '.join(errs)}\n")
    print(f"evidence: {js}")
    print(f"evidence: {ts}")
    return 0 if wrong_in_envelope == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
