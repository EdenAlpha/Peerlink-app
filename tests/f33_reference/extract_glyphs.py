#!/usr/bin/env python3
"""Extract digit glyphs + stats table geometry. Save masks for template building."""
import os, json
import numpy as np
from PIL import Image

SRC = "/home/z/my-project/peerlink/Peerlink-app-main"
OUT = "/home/z/my-project/scripts/glyphs"
os.makedirs(OUT, exist_ok=True)

def hsv_arrays(a):
    r = a[:,:,0].astype(np.float32); g = a[:,:,1].astype(np.float32); b = a[:,:,2].astype(np.float32)
    mx = np.maximum(np.maximum(r,g),b); mn = np.minimum(np.minimum(r,g),b)
    d = mx-mn
    h = np.zeros_like(mx); m = d>0
    rm = m & (mx==r); gm = m & (mx==g) & ~rm; bm = m & (mx==b) & ~gm & ~rm
    h[rm] = (60*((g[rm]-b[rm])/d[rm])) % 360
    h[gm] = 60*((b[gm]-r[gm])/d[gm])+120
    h[bm] = 60*((r[bm]-g[bm])/d[bm])+240
    s = np.where(mx>0, d/np.maximum(mx,1e-9), 0)
    v = mx/255.0
    return h,s,v

def components(mask, min_area=20):
    H, W = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    out = []
    for sy in range(H):
        for sx in range(W):
            if mask[sy,sx] and not seen[sy,sx]:
                stack=[(sy,sx)]; seen[sy,sx]=True
                x0=x1=sx; y0=y1=sy; area=0
                while stack:
                    y,x = stack.pop(); area+=1
                    x0=min(x0,x);x1=max(x1,x);y0=min(y0,y);y1=max(y1,y)
                    for ny,nx in ((y,x-1),(y,x+1),(y-1,x),(y+1,x)):
                        if 0<=ny<H and 0<=nx<W and mask[ny,nx] and not seen[ny,nx]:
                            seen[ny,nx]=True; stack.append((ny,nx))
                if area>=min_area: out.append((x0,y0,x1,y1,area))
    return out

def save_mask(mask, path):
    img = Image.fromarray((mask*255).astype(np.uint8))
    img.save(path)

# ---------------- Stats boards: banner digits ----------------
print("=== STAT BOARDS: navy glyph components in banner band (y 100..175, x 600..1000) ===")
boards = {
    "Screenshot_20260905-184444.png": ("ft_board", "0", "1"),
    "Screenshot_20260905-005550.png": ("ht_board", "0", "0"),
    "Screenshot_20260808-135907.png": ("ht_board_laliga", "1", "2"),
    "Screenshot_20260808-140853.png": ("ht_board_back", "4", "2"),
}
for name,(label, gh, ga) in boards.items():
    a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
    h,s,v = hsv_arrays(a)
    navy = (h>=200)&(h<=260)&(s>=0.45)&(v<=0.55)
    region = navy[100:175, 550:1050]
    comps = components(region, min_area=30)
    comps.sort(key=lambda c:c[0])
    txt = []
    for (x0,y0,x1,y1,area) in comps:
        txt.append(f"comp x[{550+x0}..{550+x1}] y[{100+y0}..{100+y1}] w={x1-x0+1} h={y1-y0+1} area={area}")
    print(f"{label}: " + " | ".join(txt))

# ---------------- Walking banner digits ----------------
print("\n=== WALKING: digit boxes y 575..640 ===")
a = np.asarray(Image.open(os.path.join(SRC,"Screenshot_20260905-010207.png")).convert("RGB"))
h,s,v = hsv_arrays(a)
navy = (h>=200)&(h<=260)&(s>=0.45)&(v<=0.60)
for x0,x1,tag in [(700,790,"home(0)"),(820,900,"away(1)")]:
    region = navy[575:640, x0:x1]
    comps = components(region, min_area=20)
    for (cx0,cy0,cx1,cy1,area) in sorted(comps, key=lambda c:-c[4])[:3]:
        print(f"  {tag}: x[{x0+cx0}..{x0+cx1}] y[{575+cy0}..{575+cy1}] w={cx1-cx0+1} h={cy1-cy0+1} area={area}")

# ---------------- Menu digits ----------------
print("\n=== MENU: yellow glyphs y 40..200 x 600..1000 ===")
menus = {"Screenshot_20260906-204840.png": ("menu_ft","2","2"), "Screenshot_20260808-140859.png": ("menu_ht","4","2")}
for name,(label,gh,ga) in menus.items():
    a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
    h,s,v = hsv_arrays(a)
    yel = (h>=48)&(h<=72)&(s>=0.55)&(v>=0.45)
    region = yel[40:200, 600:1000]
    comps = components(region, min_area=30)
    comps.sort(key=lambda c:c[0])
    print(f"{label}: " + " | ".join(f"x[{600+x0}..{600+x1}] y[{40+y0}..{40+y1}] w={x1-x0+1} h={y1-y0+1} a={area}" for x0,y0,x1,y1,area in comps))

# ---------------- Stats table rows ----------------
print("\n=== STATS TABLE: separator lines + number columns (board 184444) ===")
a = np.asarray(Image.open(os.path.join(SRC,"Screenshot_20260905-184444.png")).convert("RGB"))
h,s,v = hsv_arrays(a)
yellow = (h>=48)&(h<=72)&(s>=0.55)&(v>=0.55)
table = yellow[:, 530:1040]
rowfrac = table.mean(axis=1)
seps = [int(y) for y in range(150, 650) if rowfrac[y] > 0.85]
print(f"separator rows: {seps}")
navy = (h>=200)&(h<=260)&(s>=0.45)&(v<=0.55)
for (x0,x1,tag) in [(530,700,"home_nums"),(870,1040,"away_nums")]:
    reg = navy[190:640, x0:x1]
    comps = components(reg, min_area=25)
    rows = {}
    for (cx0,cy0,cx1,cy1,area) in comps:
        cy = (190+cy0+190+cy1)//2
        rows.setdefault(cy//20*20, []).append((x0+cx0,190+cy0,x0+cx1,190+cy1,area))
    print(f"  {tag}: {len(comps)} glyph components")

# Save glyph masks for template extraction: digits from all boards
print("\n=== SAVING GLYPH MASKS ===")
def save_glyph(a, h,s,v, x0,y0,x1,y1, ink, name, pad=6, scale_up=6):
    navy = ink
    reg = navy[max(0,y0-pad):y1+pad, max(0,x0-pad):x1+pad]
    ys,xs = np.where(reg)
    if len(ys)==0: return
    reg = reg[ys.min():ys.max()+1, xs.min():xs.max()+1]
    img = Image.fromarray((reg*255).astype(np.uint8))
    img = img.resize((img.width*scale_up, img.height*scale_up), Image.NEAREST)
    img.save(os.path.join(OUT, name))
    print(f"  saved {name} ({img.width}x{img.height})")

# board digits (navy ink)
jobs = [
    ("Screenshot_20260905-184444.png", [(700,110,786,165,"A_0_ft"),(812,110,898,165,"A_1_ft")]),
    ("Screenshot_20260905-005550.png", [(700,110,786,165,"A_0_ht"),(812,110,898,165,"A_0_ht2")]),
    ("Screenshot_20260808-135907.png", [(700,110,786,165,"A_1_laliga"),(812,110,898,165,"A_2_laliga")]),
    ("Screenshot_20260808-140853.png", [(700,110,786,165,"A_4_back"),(812,110,898,165,"A_2_back")]),
]
for name, glyphs in jobs:
    a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
    h,s,v = hsv_arrays(a)
    navy = (h>=200)&(h<=260)&(s>=0.45)&(v<=0.55)
    for (x0,y0,x1,y1,tag) in glyphs:
        save_glyph(a,h,s,v, x0,y0,x1,y1, navy, f"{tag}.png")

# walking digits
a = np.asarray(Image.open(os.path.join(SRC,"Screenshot_20260905-010207.png")).convert("RGB"))
h,s,v = hsv_arrays(a)
navy = (h>=200)&(h<=260)&(s>=0.45)&(v<=0.60)
save_glyph(a,h,s,v, 700,575,790,640, navy, "A_0_walk.png")
save_glyph(a,h,s,v, 820,575,900,640, navy, "A_1_walk.png")

# menu digits (yellow ink on dark)
menus = [("Screenshot_20260906-204840.png", [("B_2_m1",715,70,790,160),("B_2_m2",815,70,890,160)]),
         ("Screenshot_20260808-140859.png", [("B_4_m1",715,70,790,160),("B_2_m3",815,70,890,160)])]
for name, glyphs in menus:
    a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
    h,s,v = hsv_arrays(a)
    yel = (h>=48)&(h<=72)&(s>=0.55)&(v>=0.45)
    for (tag,x0,y0,x1,y1) in glyphs:
        save_glyph(a,h,s,v, x0,y0,x1,y1, yel, f"{tag}.png")
print("done")
