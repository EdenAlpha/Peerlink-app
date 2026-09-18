#!/usr/bin/env python3
"""Robustness suite: JPEG q88 re-compression, 1280/960 downscale, speed, negatives."""
import os, sys, io, time
sys.path.insert(0, '/home/z/my-project/scripts')
import numpy as np
from PIL import Image
import prototype as P

SRC = P.SRC
EXPECT = {
    "Screenshot_20260905-010207.png": ("WALKING", (0,1)),
    "Screenshot_20260905-184444.png": ("STATS_BOARD", (0,1)),
    "Screenshot_20260905-005550.png": ("STATS_BOARD", (0,0)),
    "Screenshot_20260906-204840.png": ("MENU", (2,2)),
    "Screenshot_20260808-135907.png": ("STATS_BOARD", (1,2)),
    "Screenshot_20260808-140853.png": ("STATS_BOARD", (4,2)),
    "Screenshot_20260808-140859.png": ("MENU", (4,2)),
}

def build_cls():
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
        a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
        m = P.Masks(a)
        P.read_stats_table(a, m, cls, collect=collected, gt=gt)
    for digit, piece in collected:
        g, asp = P.normalize_grid(piece)
        if g is None: continue
        templates.append((digit, g, asp, P.count_holes(piece)))
    return P.GlyphClassifier(templates)

def run_variant(cls, name, variant):
    img = Image.open(os.path.join(SRC,name)).convert("RGB")
    if variant == "jpg88":
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=88); buf.seek(0)
        img = Image.open(buf).convert("RGB")
    elif variant == "jpg75":
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=75); buf.seek(0)
        img = Image.open(buf).convert("RGB")
    elif variant == "w1280":
        img = img.resize((1280, int(img.height*1280/img.width)), Image.LANCZOS)
    elif variant == "w960":
        img = img.resize((960, int(img.height*960/img.width)), Image.LANCZOS)
    elif variant == "w960_jpg80":
        img = img.resize((960, int(img.height*960/img.width)), Image.LANCZOS)
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=80); buf.seek(0)
        img = Image.open(buf).convert("RGB")
    a = np.asarray(img)
    out = P.run_frame_with(a, cls)
    return out

def main():
    cls = build_cls()
    P.set_classifier(cls)
    variants = ["raw","jpg88","jpg75","w1280","w960","w960_jpg80"]
    total=0; ok=0
    for v in variants:
        line=f"{v:12s}: "
        allok=True
        for name,(etype,escore) in EXPECT.items():
            out = run_variant(cls, name, v)
            good = out["gate"]==etype and out["score"]==escore
            ok += good; total += 1
            if not good:
                allok=False
                line += f"[{name[11:17]}: {out['gate']}/{out['score']} FAIL] "
        if allok: line += "all 7 OK"
        print(line)
    print(f"\nmatrix: {ok}/{total}")

    # speed (raw, includes read+gate+stats)
    print("\nspeed (raw 1600x720, per-frame):")
    for name in list(EXPECT)[:4]:
        a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
        t=time.time(); n=3
        for _ in range(n): P.run_frame_with(a, cls)
        print(f"  {name}: {(time.time()-t)/n*1000:.0f}ms")

    # gate speed on downscaled gate image only
    a = np.asarray(Image.open(os.path.join(SRC,"Screenshot_20260905-184444.png")).convert("RGB"))
    t=time.time(); n=20
    for _ in range(n): P.gate(a)
    print(f"  gate alone: {(time.time()-t)/n*1000:.1f}ms")

    # negatives: must be rejected as OTHER (no score read)
    print("\nnegatives (must be OTHER/no-read):")
    negs = []
    base = Image.open(os.path.join(SRC,"Screenshot_20260905-010207.png")).convert("RGB")
    negs.append(("pitch_crop", base.crop((0,0,1600,500))))          # stadium+pitch, no banner
    negs.append(("sky", base.crop((0,0,1600,120)).resize((1600,720))))
    negs.append(("grass", base.crop((0,300,1600,520)).resize((1600,720))))
    menu = Image.open(os.path.join(SRC,"Screenshot_20260906-204840.png")).convert("RGB")
    negs.append(("menu_cards_only", menu.crop((0,250,1600,720)).resize((1600,720))))
    rng = np.random.default_rng(7)
    negs.append(("noise", Image.fromarray(rng.integers(0,255,(720,1600,3),dtype=np.uint8))))
    for label, im in negs:
        a = np.asarray(im)
        out = P.run_frame_with(a, cls)
        good = out["score"] is None
        print(f"  {label:16s}: gate={out['gate']:8s} score={out['score']} {'OK(rejected)' if good else 'LEAKED!'}")

if __name__ == "__main__":
    main()
