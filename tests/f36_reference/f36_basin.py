#!/usr/bin/env python3
import json
"""F36 parameter basin check: perturb each new F36 constant and re-run the
full 182-check matrix. A constant is defensible when its neighbourhood keeps
0 in-envelope wrong reads (exactness may vary; wrongness may not)."""
import io
import os
import sys
import contextlib

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "f34_reference"))

BASE = {
    "BOX_YGAP_FRAC": 0.20,
    "LOCAL_YGAP_FRAC": 0.25,
    "XJOIN_FRAC": 0.15,
    "THIN_ASPECT": 0.28,
}
FACTORS = [0.70, 0.85, 1.15, 1.30]

os.environ["F34_EVIDENCE"] = os.path.join(os.path.dirname(os.path.abspath(__file__)), "basin_evidence")
os.makedirs("/home/z/my-project/work/f36_basin_evidence", exist_ok=True)

import prototype as P
import robustness as R

LINES = []


def run_matrix(tag):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        summary = R.main()
    txt = buf.getvalue()
    # summary line: last line contains the totals
    tail = [l for l in txt.strip().splitlines() if l.strip()]
    LINES.append(f"== {tag}")
    # authoritative totals from the evidence the run just wrote
    ev = json.load(open("/home/z/my-project/work/f36_basin_evidence/robustness_latest.json"))
    LINES.append(f"   clean={ev['clean']}/182 wrong_in_envelope={ev['wrong_in_envelope']} "
                 f"wrong_edge={ev['wrong_envelope_edge']}")
    LINES.extend(tail[-2:])
    return summary


def main():
    batch = sys.argv[1] if len(sys.argv) > 1 else "all"
    names = list(BASE) if batch == "all" else [batch]
    if batch == "all":
        run_matrix("baseline (unpatched)")
    for name in names:
        base = BASE[name]
        for f in FACTORS:
            v = round(base * f, 4)
            setattr(P, name, v)
            run_matrix(f"{name}={v} ({f:.2f}x)")
            setattr(P, name, base)
    out = "\n".join(LINES)
    print(out)
    mode = "a" if batch != "all" else "w"
    dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "evidence", "f36_parameter_basin.txt")
    with open(dst, mode) as fh:
        fh.write(out + "\n")
    print("evidence:", dst)


if __name__ == "__main__":
    main()
