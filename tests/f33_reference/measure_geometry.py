#!/usr/bin/env python3
"""Measure true geometry of every score element across all 7 screenshots
using the CORRECT yellow hue (~60 deg). Outputs a JSON geometry report."""
import os, json
import numpy as np
from PIL import Image

SRC = "/home/z/my-project/peerlink/Peerlink-app-main"
SHOTS = {
    "Screenshot_20260905-010207.png": "walking_0-1",
    "Screenshot_20260905-184444.png": "ft_board_0-1",
    "Screenshot_20260905-005550.png": "ht_board_0-0",
    "Screenshot_20260906-204840.png": "menu_2-2",
    "Screenshot_20260808-135907.png": "ht_board_1-2",
    "Screenshot_20260808-140853.png": "ht_board_4-2",
    "Screenshot_20260808-140859.png": "menu_4-2",
}

def hsv_arrays(a):
    r = a[:,:,0].astype(np.float32); g = a[:,:,1].astype(np.float32); b = a[:,:,2].astype(np.float32)
    mx = np.maximum(np.maximum(r,g),b); mn = np.minimum(np.minimum(r,g),b)
    d = mx-mn
    h = np.zeros_like(mx)
    m = d>0
    rm = m & (mx==r); gm = m & (mx==g) & ~rm; bm = m & (mx==b) & ~gm & ~rm
    h[rm] = (60*((g[rm]-b[rm])/d[rm])) % 360
    h[gm] = 60*((b[gm]-r[gm])/d[gm])+120
    h[bm] = 60*((r[bm]-g[bm])/d[bm])+240
    s = np.where(mx>0, d/np.maximum(mx,1e-9), 0)
    v = mx/255.0
    return h,s,v

def components(mask, min_area=30):
    """4-connected components -> list of (x0,y0,x1,y1,area). Simple BFS."""
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
                    if x<x0:x0=x
                    if x>x1:x1=x
                    if y<y0:y0=y
                    if y>y1:y1=y
                    for ny,nx in ((y,x-1),(y,x+1),(y-1,x),(y+1,x)):
                        if 0<=ny<H and 0<=nx<W and mask[ny,nx] and not seen[ny,nx]:
                            seen[ny,nx]=True; stack.append((ny,nx))
                if area>=min_area:
                    out.append((x0,y0,x1,y1,area))
    return out

report = {}
for name, label in SHOTS.items():
    img = Image.open(os.path.join(SRC,name)).convert("RGB")
    a = np.asarray(img)
    H, W = a.shape[:2]
    h,s,v = hsv_arrays(a)
    yellow = (h>=48)&(h<=72)&(s>=0.55)&(v>=0.55)
    navy   = (h>=200)&(h<=260)&(s>=0.5)&(v<=0.55)
    white  = (s<=0.12)&(v>=0.85)
    dark   = (v<=0.22)
    green  = (h>=70)&(h<=160)&(s>=0.25)&(v>=0.25)

    rowY = yellow.mean(axis=1)
    rowN = navy.mean(axis=1)
    rowW = white.mean(axis=1)
    rowD = dark.mean(axis=1)
    rowG = green.mean(axis=1)

    info = {"label":label, "W":W, "H":H,
            "yellow_rows": None, "navy_rows": None,
            "screen_stats": {"green":round(float(green.mean()),3), "dark":round(float(dark.mean()),3),
                              "white":round(float(white.mean()),3), "yellow":round(float(yellow.mean()),3)}}
    yr = np.where(rowY>0.25)[0]
    if len(yr): info["yellow_rows"] = [int(yr.min()), int(yr.max()), round(float(yr.min()/H),3), round(float(yr.max()/H),3)]
    nr = np.where(rowN>0.5)[0]
    if len(nr): info["navy_rows"] = [int(nr.min()), int(nr.max())]
    # components of yellow overall (band + digit boxes + table numbers)
    # downsample x2 for speed
    yds = yellow[::2,::2]
    comps = components(yds, min_area=25)
    comps.sort(key=lambda c:-c[4])
    info["yellow_components_top8"] = [
        {"x0":c[0]*2,"y0":c[1]*2,"x1":c[2]*2,"y1":c[3]*2,"area":c[4]*4,
         "rel":[round(c[0]*2/W,3),round(c[1]*2/H,3),round(c[2]*2/W,3),round(c[3]*2/H,3)]} for c in comps[:8]]
    report[name] = info

print(json.dumps(report, indent=1))
with open("/home/z/my-project/scripts/geometry_report.json","w") as f:
    json.dump(report, f, indent=1)
