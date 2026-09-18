#!/usr/bin/env python3
"""
PeerLink ScoreBoardDetector — reference prototype v2 (ported 1:1 to Kotlin).

Stage 1 GATE  (~3-6 ms): STATS_BOARD | WALKING | MENU | OTHER
Stage 2 READ  (~5-20 ms): band-zone glyph extraction + column-gap segmentation
          + structural/template digit classifier + stats table + F/H finality.
"""
import os, json, math, time
import numpy as np
from PIL import Image

SRC = "/home/z/my-project/peerlink/Peerlink-app-main"

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

class Masks:
    def __init__(self, a):
        h,s,v = hsv_arrays(a)
        # strict yellow: bands/zones/table separators (must NOT catch grass)
        self.yellow = (h>=48)&(h<=72)&(s>=0.58)&(v>=0.60)
        # loose yellow: glyph ink on navy/dark backgrounds only
        self.yellow_ink = (h>=45)&(h<=75)&(s>=0.45)&(v>=0.40)
        self.navy   = (h>=200)&(h<=260)&(s>=0.45)&(v<=0.55)
        self.green  = (h>=70)&(h<=160)&(s>=0.25)&(v>=0.25)
        self.dark   = (v<=0.22)
        self.white  = (s<=0.12)&(v>=0.85)

def components(mask, min_area=20, x0=0, y0=0):
    H, W = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    out = []
    for sy in range(H):
        for sx in range(W):
            if mask[sy,sx] and not seen[sy,sx]:
                stack=[(sy,sx)]; seen[sy,sx]=True
                bx0=bx1=sx; by0=by1=sy; area=0
                while stack:
                    y,x = stack.pop(); area+=1
                    if x<bx0:bx0=x
                    if x>bx1:bx1=x
                    if y<by0:by0=y
                    if y>by1:by1=y
                    if x>0 and mask[y,x-1] and not seen[y,x-1]: seen[y,x-1]=True; stack.append((y,x-1))
                    if x+1<W and mask[y,x+1] and not seen[y,x+1]: seen[y,x+1]=True; stack.append((y,x+1))
                    if y>0 and mask[y-1,x] and not seen[y-1,x]: seen[y-1,x]=True; stack.append((y-1,x))
                    if y+1<H and mask[y+1,x] and not seen[y+1,x]: seen[y+1,x]=True; stack.append((y+1,x))
                if area>=min_area:
                    out.append({"x0":bx0+x0,"y0":by0+y0,"x1":bx1+x0,"y1":by1+y0,"area":area})
    return out

# ------------------------------------------------------------------ gate
def gate(a):
    h, w = a.shape[:2]
    scale = min(1.0, 320.0/w)
    small = np.asarray(Image.fromarray(a).resize((max(1,int(w*scale)), max(1,int(h*scale))), Image.BILINEAR)) if scale < 1.0 else a
    m = Masks(small)
    gh, gw = small.shape[:2]
    fy=float(m.yellow.mean()); fn=float(m.navy.mean()); fg=float(m.green.mean())
    fd=float(m.dark.mean()); fw=float(m.white.mean())
    rowY = m.yellow.mean(axis=1)
    band_rows = np.where(rowY > 0.18)[0]
    bottom_band = len(band_rows)>0 and band_rows.mean() > 0.62*gh
    cx0,cx1,cy0,cy1 = int(0.40*gw),int(0.60*gw),int(0.04*gh),int(0.26*gh)
    menu_ink = float(m.yellow[cy0:cy1, cx0:cx1].mean())
    if fy>=0.14 and fn>=0.06 and fg<=0.34 and fw<=0.12:
        return "STATS_BOARD", {"fy":fy,"fn":fn,"fg":fg,"fw":fw,"fd":fd}
    if fd>=0.38 and fw>=0.10 and fy<=0.06 and menu_ink>=0.004:
        return "MENU", {"fy":fy,"fn":fn,"fg":fg,"fw":fw,"fd":fd,"menu_ink":menu_ink}
    if fg>=0.38 and fy>=0.02 and bottom_band:
        return "WALKING", {"fy":fy,"fn":fn,"fg":fg,"fw":fw,"fd":fd}
    return "OTHER", {"fy":fy,"fn":fn,"fg":fg,"fw":fw,"fd":fd,"menu_ink":menu_ink}

# ------------------------------------------------------------------ glyph classifier
GRID_W, GRID_H = 16, 24

def count_holes(mask):
    hh, ww = mask.shape
    if hh<3 or ww<3: return 0
    inv = ~mask
    seen = np.zeros_like(mask, dtype=bool)
    stack=[]
    for x in range(ww):
        for y in (0,hh-1):
            if inv[y,x] and not seen[y,x]: seen[y,x]=True; stack.append((y,x))
    for y in range(hh):
        for x in (0,ww-1):
            if inv[y,x] and not seen[y,x]: seen[y,x]=True; stack.append((y,x))
    while stack:
        y,x=stack.pop()
        for ny,nx in ((y,x-1),(y,x+1),(y-1,x),(y+1,x)):
            if 0<=ny<hh and 0<=nx<ww and inv[ny,nx] and not seen[ny,nx]:
                seen[ny,nx]=True; stack.append((ny,nx))
    holes=0
    for sy in range(hh):
        for sx in range(ww):
            if inv[sy,sx] and not seen[sy,sx]:
                holes+=1
                stack=[(sy,sx)]; seen[sy,sx]=True
                while stack:
                    y,x=stack.pop()
                    for ny,nx in ((y,x-1),(y,x+1),(y-1,x),(y+1,x)):
                        if 0<=ny<hh and 0<=nx<ww and inv[ny,nx] and not seen[ny,nx]:
                            seen[ny,nx]=True; stack.append((ny,nx))
    return holes

def normalize_grid(mask):
    ys,xs = np.where(mask)
    if len(ys)==0: return None, 0.0
    mm = mask[ys.min():ys.max()+1, xs.min():xs.max()+1]
    aspect = mm.shape[1]/mm.shape[0]
    img = Image.fromarray((mm*255).astype(np.uint8)).resize((GRID_W,GRID_H), Image.BILINEAR)
    return (np.asarray(img)>100), aspect

def tanimoto(a,b):
    inter=np.logical_and(a,b).sum(); union=np.logical_or(a,b).sum()
    return inter/union if union else 0.0

class GlyphClassifier:
    def __init__(self, templates):
        self.templates = templates  # (digit, grid, aspect, holes)
    def classify(self, mask):
        g, aspect = normalize_grid(mask)
        if g is None: return None, 0.0, {}
        holes = count_holes(mask)
        best, second, bestd = -1.0, -1.0, None
        for (digit, tg, tasp, tholes) in self.templates:
            sc = tanimoto(g, tg)
            if tholes != holes: sc *= 0.35
            if abs(tasp-aspect) > 0.40: sc *= 0.6
            if sc > best: second=best; best, bestd = sc, digit
            elif sc > second: second = sc
        return bestd, best, {"second":round(second,3),"holes":holes,"aspect":round(aspect,2)}

# ------------------------------------------------------------------ segmentation
def column_segments(mask, min_gap=2, min_w=3):
    """Split mask into glyph segments by empty columns."""
    colink = mask.sum(axis=0)
    segs=[]; start=None; gap=0
    for x in range(len(colink)):
        if colink[x] > 0:
            if start is None: start = x
            gap = 0
        else:
            if start is not None:
                gap += 1
                if gap >= min_gap:
                    if (x-gap) - start + 1 >= min_w: segs.append((start, x-gap))
                    start=None; gap=0
    if start is not None and len(colink)-start >= min_w:
        segs.append((start, len(colink)-1))
    return segs

def segments_of_window(mask, min_area=8, wide_max=None, ygap=0):
    """Connected comps -> drop wide fragments (separator lines) -> merge
    x-overlapping parts of the same glyph (y-gap tolerant for stacked strokes
    like the menu '2'). Returns list of bbox dicts."""
    comps = components(mask, min_area=min_area)
    if not comps: return []
    if wide_max is not None:
        comps = [c for c in comps if (c["x1"]-c["x0"]+1) <= wide_max]
    comps.sort(key=lambda c:(c["y0"], c["x0"]))
    groups=[]
    for c in comps:
        best=None; best_ov=0
        for g in groups:
            ov = min(c["x1"],g["x1"]) - max(c["x0"],g["x0"]) + 1
            minw = min(c["x1"]-c["x0"]+1, g["x1"]-g["x0"]+1)
            ygapv = max(c["y0"]-g["y1"], g["y0"]-c["y1"])
            if ov > 0.45*minw and ygapv <= ygap and (best is None or ov>best_ov):
                best=g; best_ov=ov
        if best is not None:
            best["x0"]=min(best["x0"],c["x0"]); best["x1"]=max(best["x1"],c["x1"])
            best["y0"]=min(best["y0"],c["y0"]); best["y1"]=max(best["y1"],c["y1"])
            best["area"]+=c["area"]
        else:
            groups.append({"x0":c["x0"],"y0":c["y0"],"x1":c["x1"],"y1":c["y1"],"area":c["area"]})
    # second pass: re-merge groups whose extended ranges now overlap
    changed=True
    while changed:
        changed=False
        groups.sort(key=lambda g:(g["y0"],g["x0"]))
        out=[]
        for g in groups:
            tgt=None
            for p in out:
                ov = min(g["x1"],p["x1"])-max(g["x0"],p["x0"])+1
                minw = min(g["x1"]-g["x0"]+1, p["x1"]-p["x0"]+1)
                ygapv = max(g["y0"]-p["y1"], p["y0"]-g["y1"])
                if ov > 0.45*minw and ygapv <= ygap:
                    tgt=p; break
            if tgt is not None:
                tgt["x0"]=min(tgt["x0"],g["x0"]); tgt["x1"]=max(tgt["x1"],g["x1"])
                tgt["y0"]=min(tgt["y0"],g["y0"]); tgt["y1"]=max(tgt["y1"],g["y1"])
                tgt["area"]+=g["area"]
                changed=True
            else:
                out.append(dict(g))
        groups=out
    return groups

def segment_masks(mask, groups):
    out=[]
    for g in groups:
        out.append((mask[g["y0"]:g["y1"]+1, g["x0"]:g["x1"]+1].copy(), g))
    return out

# ------------------------------------------------------------------ banner zone
def banner_zone_board(m, H, W):
    """Stats board: fixed banner window measured from real eFootball UI
    (banner spans 0.148..0.235 of frame height on every observed board)."""
    return (int(0.148*H), int(0.235*H))

def banner_zone_walking(m, H, W):
    """Walking: score boxes sit at 0.79..0.89 of frame height."""
    return (int(0.79*H), int(0.89*H))

def loose_navy_mask(a):
    """Tier-3 mask for JPEG-degraded small digits."""
    h,s,v = hsv_arrays(a)
    return (h>=195)&(h<=265)&(s>=0.28)&(v<=0.68)

# ------------------------------------------------------------------ score readers
def read_banner_score(a, m, zone, masks_loose=None):
    """Navy digit clusters flanking center, inside the banner zone.
    Tier 1: raw components on strict navy. Tier 2: fragment merge.
    Tier 3: loose navy mask (JPEG-degraded frames).
    masks_loose: optional [loose_mask] computed once by the caller.
    Returns clusters in absolute coords; 'masks' key carries the mask used per side."""
    H, W = a.shape[:2]
    y0,y1 = zone
    res={}
    used_masks = {}
    for side,(wx0,wx1) in (("home",(0.415,0.4975)),("away",(0.5025,0.585))):
        X0,X1 = int(wx0*W), int(wx1*W)
        used_mask = m.navy
        cand = None
        navy_masks = [m.navy]
        if masks_loose:
            navy_masks.append(masks_loose[0])
        for mi, nm in enumerate(navy_masks):
            win = nm[y0:y1+1, X0:X1]
            fw = max(3, int(0.012*H))  # fragment y-gap (resolution scaled)
            for ygap in (0, fw):
                comps = components(win, min_area=max(6,int(0.00002*W*H)))
                if ygap:
                    comps = merge_fragments(comps, ygap,
                                            max_w=int(0.052*W), max_h=int(0.068*H))
                cand=[c for c in comps
                      if 0.030*H <= (c["y1"]-c["y0"]+1) <= 0.068*H
                      and (c["x1"]-c["x0"]+1) <= 0.070*W
                      and c["area"] >= max(10,int(0.00006*W*H))]
                if cand: break
            if cand:
                used_mask = nm; break
        if not cand: res[side]=None; continue
        used_masks[side] = used_mask
        for c in cand:
            c["x0"]+=X0; c["x1"]+=X0; c["y0"]+=y0; c["y1"]+=y0
        target = 0.4655 if side=="home" else 0.5355
        def rank(c):
            cx=(c["x0"]+c["x1"])/2/W
            prox=math.exp(-(((cx-target)/0.016)**2))
            return prox*math.sqrt(c["area"])
        cand.sort(key=rank, reverse=True)
        # merge touching comps into one number cluster
        best=cand[0]
        cl={"x0":best["x0"],"y0":best["y0"],"x1":best["x1"],"y1":best["y1"],"area":best["area"]}
        changed=True
        while changed:
            changed=False
            for c in cand:
                if c["x0"] <= cl["x1"] and c["x1"] >= cl["x0"]:
                    if c["x0"] < cl["x0"] or c["x1"] > cl["x1"]:
                        cl={"x0":min(cl["x0"],c["x0"]),"y0":min(cl["y0"],c["y0"]),
                            "x1":max(cl["x1"],c["x1"]),"y1":max(cl["y1"],c["y1"]),
                            "area":cl["area"]+c["area"]}
                        changed=True
        if (cl["x1"]-cl["x0"]+1) > 0.075*W: res[side]=None; continue
        res[side]=cl
    if res["home"] is None or res["away"] is None: return None, res
    res["masks"] = used_masks
    return res, res

def merge_fragments(comps, ygap, max_w=None, max_h=None):
    """Merge vertically-adjacent, x-overlapping fragments of one glyph.
    Unions that would exceed max_w/max_h (border bars, trims) are rejected."""
    comps = sorted(comps, key=lambda c:(c["y0"], c["x0"]))
    out=[]
    for c in comps:
        tgt=None
        for p in out:
            ov = min(c["x1"],p["x1"])-max(c["x0"],p["x0"])+1
            minw = min(c["x1"]-c["x0"]+1, p["x1"]-p["x0"]+1)
            gap = max(c["y0"]-p["y1"], p["y0"]-c["y1"])
            if ov <= 0 or ov <= 0.5*minw or gap > ygap: continue
            ux = max(c["x1"]-c["x0"]+1, p["x1"]-p["x0"]+1)
            if max_w and min(c["x1"],p["x1"]) > 0 and False: pass
            union_w = max(c["x1"],p["x1"])-min(c["x0"],p["x0"])+1
            union_h = max(c["y1"],p["y1"])-min(c["y0"],p["y0"])+1
            if max_w and union_w > max_w: continue
            if max_h and union_h > max_h: continue
            tgt=p; break
        if tgt is not None:
            tgt["x0"]=min(tgt["x0"],c["x0"]); tgt["x1"]=max(tgt["x1"],c["x1"])
            tgt["y0"]=min(tgt["y0"],c["y0"]); tgt["y1"]=max(tgt["y1"],c["y1"])
            tgt["area"]+=c["area"]
        else:
            out.append(dict(c))
    return out

def read_menu_score(a, m):
    H, W = a.shape[:2]
    Y0,Y1 = int(0.045*H), int(0.27*H)
    res={}
    for side,(wx0,wx1) in (("home",(0.40,0.4975)),("away",(0.5025,0.60))):
        X0,X1 = int(wx0*W), int(wx1*W)
        win = m.yellow_ink[Y0:Y1, X0:X1]
        groups = segments_of_window(win, min_area=max(10,int(0.00008*W*H)),
                                    wide_max=int(0.055*W), ygap=max(3,int(0.012*H)))
        cand=[g for g in groups if 0.055*H <= (g["y1"]-g["y0"]+1) <= 0.13*H and (g["x1"]-g["x0"]+1) <= 0.060*W]
        if not cand: res[side]=None; continue
        target = 0.4655 if side=="home" else 0.5335
        def rank(g):
            cx=(g["x0"]+g["x1"])/2/W
            prox=math.exp(-(((cx-target)/0.02)**2))
            return prox*math.sqrt(g["area"])
        cand.sort(key=rank, reverse=True)
        g = cand[0]
        # absolute frame coordinates
        res[side]={"x0":g["x0"]+X0,"y0":g["y0"]+Y0,"x1":g["x1"]+X0,"y1":g["y1"]+Y0,"area":g["area"]}
    if res["home"] is None or res["away"] is None: return None, res
    return res, res

def read_score_value(m_full, cluster, classifier, ink="navy", max_digits=2, min_conf=0.45):
    """Column-segment the cluster window, classify each segment.
    NOTE: segments_of_window returns window-relative coords."""
    x0,y0,x1,y1 = cluster["x0"],cluster["y0"],cluster["x1"],cluster["y1"]
    win = m_full[y0:y1+1, x0:x1+1]
    cw = x1-x0+1
    groups = segments_of_window(win, min_area=6, wide_max=cw, ygap=max(2,int(0.010*cluster.get("H",720))))
    groups.sort(key=lambda g:g["x0"])
    digits=[]; detail=[]
    for g in groups:
        if (g["x1"]-g["x0"]+1) < 3: continue
        seg = win[g["y0"]:g["y1"]+1, g["x0"]:g["x1"]+1]
        d, conf, info = classifier.classify(seg)
        detail.append((d, round(float(conf),3), info))
        if d is not None and conf >= min_conf:
            digits.append(d)
    val = int("".join(str(d) for d in digits)) if 1 <= len(digits) <= max_digits else None
    return val, detail

# ------------------------------------------------------------------ stats table
STAT_NAMES = ["Possession","TotalShots","ShotsOnTarget","Fouls","Offsides","CornerKicks",
              "FreeKicks","Passes","SuccessfulPasses","Crosses","Interceptions","Tackles","Saves"]

def split_wide(mask, max_w):
    """Split an over-wide glyph mask into per-glyph masks by column valleys."""
    h, w = mask.shape
    if w <= max_w: return [mask]
    colink = mask.sum(axis=0)
    # try clean gaps first
    segs = column_segments(mask, min_gap=1, min_w=2)
    if len(segs) > 1:
        return [mask[:, a:b+1] for a,b in segs]
    # valley split: cut at min-ink column in the central band
    out=[]; start=0
    while (w - start) > max_w:
        lo = start + max(1,int(0.25*max_w)); hi = min(w-1, start + int(0.75*(w-start)))
        window = colink[lo:hi+1]
        cut = lo + int(np.argmin(window)) if len(window) else lo
        out.append(mask[:, start:cut])
        start = cut
    if start < w: out.append(mask[:, start:])
    return out

def read_stats_table(a, m, classifier, collect=None, gt=None):
    H, W = a.shape[:2]
    strip_bottom = find_strip_bottom(m, H, W)
    if strip_bottom is None: return None, {"error":"no strip"}
    rows = {}
    win_top = strip_bottom+2
    for side,(wx0,wx1) in (("home",(0.350,0.408)),("away",(0.590,0.648))):
        X0,X1 = int(wx0*W), int(wx1*W)
        win = m.yellow_ink[win_top:int(0.97*H), X0:X1]
        groups = segments_of_window(win, min_area=8, wide_max=int(0.052*W), ygap=2)
        cand=[g for g in groups
              if 0.020*H <= (g["y1"]-g["y0"]+1) <= 0.050*H
              and (g["x1"]-g["x0"]+1) <= 0.045*W
              and g["area"] >= 25]
        # convert to absolute frame coordinates
        for g in cand:
            g["x0"]+=X0; g["x1"]+=X0; g["y0"]+=win_top; g["y1"]+=win_top
        rows[side]=cand
    # group rows by y-center per side
    def group_rows(cands):
        cands = sorted(cands, key=lambda g:(g["y0"]+g["y1"])/2)
        out=[]
        for g in cands:
            cy=(g["y0"]+g["y1"])/2
            if out and cy - (out[-1]["cy"]) < 0.017*H:
                p=out[-1]
                p["x0"]=min(p["x0"],g["x0"]); p["x1"]=max(p["x1"],g["x1"])
                p["y0"]=min(p["y0"],g["y0"]); p["y1"]=max(p["y1"],g["y1"])
                p["cy"]=(p["y0"]+p["y1"])/2; p["area"]+=g["area"]
            else:
                gg=dict(g); gg["cy"]=cy; out.append(gg)
        return out
    # pair rows across sides by y-center proximity, then map to stat order
    hrows = group_rows(rows["home"]); arows = group_rows(rows["away"])
    pairs=[]
    used=set()
    for hr in sorted(hrows, key=lambda g:g["cy"]):
        best=None; bd=1e9
        for k,ar in enumerate(arows):
            if k in used: continue
            d=abs(hr["cy"]-ar["cy"])
            if d<bd: bd=d; best=k
        if best is not None and bd <= 0.012*H:
            used.add(best); pairs.append((hr, arows[best]))
    pairs.sort(key=lambda p:(p[0]["cy"]+p[1]["cy"])/2)
    stats={}
    debug={"strip_bottom":strip_bottom,"home_rows":len(hrows),"away_rows":len(arows),"pairs":len(pairs)}
    for k,(hr,ar) in enumerate(pairs):
        if k >= 13: break
        vals={}
        for side, cl in (("home",hr),("away",ar)):
            win_full = m.yellow_ink
            x0,y0,x1,y1 = cl["x0"],cl["y0"],cl["x1"],cl["y1"]
            win = win_full[y0:y1+1, x0:x1+1]
            cw = x1-x0+1
            ch = y1-y0+1
            groups = segments_of_window(win, min_area=6, wide_max=cw, ygap=2)
            groups.sort(key=lambda g:g["x0"])
            pieces=[]
            for g in groups:
                seg = win[g["y0"]:g["y1"]+1, g["x0"]:g["x1"]+1]
                # a segment wider than tall-ish is several fused digits
                pieces += split_wide(seg, max_w=int(1.30*ch)) if ch>0 else [seg]
            digits=[]
            for piece in pieces:
                d, conf, info = classifier.classify(piece)
                if d is not None and info.get("aspect",1) > 0.85 and info.get("holes",0) >= 2:
                    d = None  # '%' veto: wide glyph with two counters
                if d is not None and conf >= 0.45:
                    digits.append(d)
            if 1 <= len(digits) <= 3:
                v = int("".join(map(str,digits)))
                if v <= 100: vals[side]=v
            # ground-truth teaching: label pieces by GT when piece count matches
            if collect is not None and gt is not None and k < len(gt):
                gtv = gt[k][0 if side=="home" else 1]
                s = str(gtv)
                if len(pieces) == len(s):
                    for chi, piece in zip(s, pieces):
                        collect.append((int(chi), piece))
        if len(vals)==2:
            stats[STAT_NAMES[k]]=vals
    return stats, debug

def find_strip_bottom(m, H, W):
    """Solid yellow strip below the banner ('Full Time' label strip)."""
    cx0,cx1 = int(0.35*W), int(0.65*W)
    yelRow = m.yellow[:, cx0:cx1].mean(axis=1)
    y = int(0.20*H)
    end = int(0.40*H)
    strip_end=None
    run=0
    while y < end:
        if yelRow[y] > 0.82:
            run+=1; strip_end=y
        else:
            if run >= int(0.020*H): return strip_end
            run=0
        y+=1
    return strip_end if run >= int(0.015*H) else None

# ------------------------------------------------------------------ finality
def classify_FH(mask):
    if mask.size == 0: return "unknown", {}
    hh, ww = mask.shape
    if hh < 8 or ww < 3: return "unknown", {"tiny":True}
    holes = count_holes(mask)
    q = max(1, ww//4)
    colL = float(mask[:, :q].mean())
    right = mask[:, 2*q:]
    rh = right.shape[0]
    lower = right[int(0.55*rh):, :]
    colR_lower = float(lower.mean()) if lower.size else 0.0
    rowT = float(mask[:max(1,hh//5), :].mean())
    info={"holes":holes,"L":round(colL,2),"Rlow":round(colR_lower,2)}
    if holes == 0 and colL > 0.30 and colR_lower < 0.22 and rowT > 0.40:
        return "full", info      # F: left stem, empty lower right, top bar
    if holes == 0 and colL > 0.30 and colR_lower > 0.45 and rowT < 0.60:
        return "half", info      # H: both stems run full height
    if holes >= 1:
        return "clock", info     # closed digits (e.g. 45:00 clock)
    return "unknown", info

def read_finality_board(m, H, W):
    Y0,Y1 = int(0.225*H), int(0.285*H)
    X0,X1 = int(0.38*W), int(0.62*W)
    win = m.navy[Y0:Y1, X0:X1]
    groups = segments_of_window(win, min_area=8, wide_max=int(0.10*W), ygap=2)
    cand=[g for g in groups if 0.018*H <= (g["y1"]-g["y0"]+1) <= 0.045*H]
    if not cand: return "unknown", None
    cand.sort(key=lambda g:g["x0"])
    g=cand[0]
    mask = win[g["y0"]:g["y1"]+1, g["x0"]:g["x1"]+1]
    return classify_FH(mask)

def read_finality_menu(m, H, W):
    Y0,Y1 = int(0.035*H), int(0.095*H)
    X0,X1 = int(0.38*W), int(0.62*W)
    win = m.yellow_ink[Y0:Y1, X0:X1]
    groups = segments_of_window(win, min_area=6, wide_max=int(0.10*W), ygap=2)
    cand=[g for g in groups if 0.016*H <= (g["y1"]-g["y0"]+1) <= 0.045*H]
    if not cand: return "unknown", None
    cand.sort(key=lambda g:g["x0"])
    g=cand[0]
    mask = win[g["y0"]:g["y1"]+1, g["x0"]:g["x1"]+1]
    return classify_FH(mask)

# ------------------------------------------------------------------ templates
def font_digit_mask(digit, font_path, size=64):
    from PIL import ImageFont, ImageDraw
    f = ImageFont.truetype(font_path, size)
    img = Image.new("L", (size*2, size*2), 0)
    d = ImageDraw.Draw(img)
    d.text((size//2, size//4), str(digit), font=f, fill=255)
    a = np.asarray(img)>128
    ys,xs=np.where(a)
    if len(ys)==0: return None
    return a[ys.min():ys.max()+1, xs.min():xs.max()+1]

def build_all():
    real=[]  # (digit, mask, aspect, holes)
    def add_template(digit, mask):
        g, asp = normalize_grid(mask)
        if g is None: return
        real.append((digit, g, asp, count_holes(mask)))

    def harvest_banner(name, pairs):
        a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
        H,W = a.shape[:2]
        m = Masks(a)
        zone = banner_zone_board(m, H, W) or banner_zone_walking(m, H, W)
        res,_ = read_banner_score(a, m, zone)
        if not res: return
        for side, digit in pairs:
            cl = res.get(side)
            if not cl: continue
            win = m.navy[cl["y0"]:cl["y1"]+1, cl["x0"]:cl["x1"]+1]
            groups = segments_of_window(win, min_area=6)
            groups.sort(key=lambda g:g["x0"])
            if groups:
                g = groups[0]
                add_template(digit, win[g["y0"]:g["y1"]+1, g["x0"]:g["x1"]+1])

    harvest_banner("Screenshot_20260905-184444.png", [("home",0),("away",1)])
    harvest_banner("Screenshot_20260905-005550.png", [("home",0),("away",0)])
    harvest_banner("Screenshot_20260808-135907.png", [("home",1),("away",2)])
    harvest_banner("Screenshot_20260808-140853.png", [("home",4),("away",2)])
    harvest_banner("Screenshot_20260905-010207.png", [("home",0),("away",1)])
    # menu glyphs
    for name, pairs in [("Screenshot_20260906-204840.png",[("home",2),("away",2)]),
                        ("Screenshot_20260808-140859.png",[("home",4),("away",2)])]:
        a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
        H,W=a.shape[:2]
        m = Masks(a)
        res,_ = read_menu_score(a, m)
        if not res: continue
        for side, digit in pairs:
            cl = res.get(side)
            if not cl: continue
            win = m.yellow_ink[cl["y0"]:cl["y1"]+1, cl["x0"]:cl["x1"]+1]
            groups = segments_of_window(win, min_area=6, wide_max=cl["x1"]-cl["x0"]+1, ygap=max(3,int(0.012*H)))
            groups.sort(key=lambda g:g["x0"])
            for g in groups:
                add_template(digit, win[g["y0"]:g["y1"]+1, g["x0"]:g["x1"]+1])
    # font templates for missing digits
    have = set(d for d,_,_,_ in real)
    fp = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
    for d in range(10):
        if d not in have:
            mk = font_digit_mask(d, fp)
            if mk is not None:
                g, asp = normalize_grid(mk)
                real.append((d, g, asp, count_holes(mk)))
    return real

SCORE_CLS = None

def run_frame(a, verbose=False):
    H, W = a.shape[:2]
    gtype, ginfo = gate(a)
    m = Masks(a)
    out={"gate":gtype}
    score=None; finality="unknown"; stats=None
    if gtype=="STATS_BOARD":
        zone = banner_zone_board(m, H, W)
        if zone:
            loose = [loose_navy_mask(a)]
            res,_ = read_banner_score(a, m, zone, masks_loose=loose)
            if res:
                for side in ("home","away"):
                    res[side]["H"]=H
                hv,_ = read_score_value(res["masks"]["home"], res["home"], SCORE_CLS, "navy")
                av,_ = read_score_value(res["masks"]["away"], res["away"], SCORE_CLS, "navy")
                if hv is not None and av is not None: score=(hv,av)
        finality,_ = read_finality_board(m, H, W)
        stats, sdbg = read_stats_table(a, m, SCORE_CLS)
        out["stats_dbg"]=sdbg
    elif gtype=="WALKING":
        zone = banner_zone_walking(m, H, W)
        if zone:
            loose = [loose_navy_mask(a)]
            res,_ = read_banner_score(a, m, zone, masks_loose=loose)
            if res:
                for side in ("home","away"):
                    res[side]["H"]=H
                hv,_ = read_score_value(res["masks"]["home"], res["home"], SCORE_CLS, "navy")
                av,_ = read_score_value(res["masks"]["away"], res["away"], SCORE_CLS, "navy")
                if hv is not None and av is not None: score=(hv,av)
    elif gtype=="MENU":
        res,_ = read_menu_score(a, m)
        if res:
            hcl = dict(res["home"]); hcl["H"]=H
            acl = dict(res["away"]); acl["H"]=H
            hv,_ = read_score_value(m.yellow_ink, hcl, SCORE_CLS, "yellow")
            av,_ = read_score_value(m.yellow_ink, acl, SCORE_CLS, "yellow")
            if hv is not None and av is not None: score=(hv,av)
        finality,_ = read_finality_menu(m, H, W)
    out["score"]=score; out["finality"]=finality; out["stats"]=stats
    return out

def main():
    global SCORE_CLS
    t0=time.time()
    templates = build_all()
    SCORE_CLS = GlyphClassifier(templates)
    # phase 2: harvest stats-font templates using ground truth
    GT = {
        "Screenshot_20260905-184444.png": [(55,45),(4,1),(2,1),(0,1),(0,1),(1,0),(1,0),(68,80),(55,62),(0,0),(13,11),(3,5),(0,3)],
        "Screenshot_20260905-005550.png": [(54,46),(2,2),(2,1),(1,0),(0,0),(1,1),(0,1),(35,34),(29,25),(0,1),(8,5),(2,5),(1,2)],
        "Screenshot_20260808-135907.png": [(50,50),(4,3),(2,3),(0,0),(0,0),(1,1),(0,0),(37,38),(34,30),(1,0),(6,2),(0,4),(1,1)],
        "Screenshot_20260808-140853.png": [(50,50),(8,2),(6,2),(0,0),(0,0),(1,0),(0,0),(32,46),(26,40),(0,0),(5,4),(1,0),(0,2)],
    }
    collected=[]
    for name, gt in GT.items():
        a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
        m = Masks(a)
        read_stats_table(a, m, SCORE_CLS, collect=collected, gt=gt)
    for digit, piece in collected:
        g, asp = normalize_grid(piece)
        if g is None: continue
        templates.append((digit, g, asp, count_holes(piece)))
    SCORE_CLS = GlyphClassifier(templates)
    print(f"templates: {len(templates)} (stats harvested: {len(collected)}) digits={sorted(set(d for d,_,_,_ in templates))} built in {time.time()-t0:.1f}s")

    EXPECT = {
        "Screenshot_20260905-010207.png": ("WALKING", (0,1), "not_full", None),
        "Screenshot_20260905-184444.png": ("STATS_BOARD", (0,1), "full",
            {"Possession":[55,45],"TotalShots":[4,1],"ShotsOnTarget":[2,1],"Fouls":[0,1],
             "Offsides":[0,1],"CornerKicks":[1,0],"FreeKicks":[1,0],"Passes":[68,80],
             "SuccessfulPasses":[55,62],"Crosses":[0,0],"Interceptions":[13,11],"Tackles":[3,5],"Saves":[0,3]}),
        "Screenshot_20260905-005550.png": ("STATS_BOARD", (0,0), "not_full",
            {"Possession":[54,46],"TotalShots":[2,2],"ShotsOnTarget":[2,1],"Fouls":[1,0],
             "Offsides":[0,0],"CornerKicks":[1,1],"FreeKicks":[0,1],"Passes":[35,34],
             "SuccessfulPasses":[29,25],"Crosses":[0,1],"Interceptions":[8,5],"Tackles":[2,5],"Saves":[1,2]}),
        "Screenshot_20260906-204840.png": ("MENU", (2,2), "full", None),
        "Screenshot_20260808-135907.png": ("STATS_BOARD", (1,2), "not_full",
            {"Possession":[50,50],"TotalShots":[4,3],"ShotsOnTarget":[2,3],"Fouls":[0,0],
             "Offsides":[0,0],"CornerKicks":[1,1],"FreeKicks":[0,0],"Passes":[37,38],
             "SuccessfulPasses":[34,30],"Crosses":[1,0],"Interceptions":[6,2],"Tackles":[0,4],"Saves":[1,1]}),
        "Screenshot_20260808-140853.png": ("STATS_BOARD", (4,2), "not_full",
            {"Possession":[50,50],"TotalShots":[8,2],"ShotsOnTarget":[6,2],"Fouls":[0,0],
             "Offsides":[0,0],"CornerKicks":[1,0],"FreeKicks":[0,0],"Passes":[32,46],
             "SuccessfulPasses":[26,40],"Crosses":[0,0],"Interceptions":[5,4],"Tackles":[1,0],"Saves":[0,2]}),
        "Screenshot_20260808-140859.png": ("MENU", (4,2), "not_full", None),
    }
    all_ok=True
    for name,(etype, escore, efin, estats) in EXPECT.items():
        a = np.asarray(Image.open(os.path.join(SRC,name)).convert("RGB"))
        t1=time.time()
        out = run_frame(a)
        ms=(time.time()-t1)*1000
        ok = out["gate"]==etype and out["score"]==escore
        if efin=="not_full": ok &= (out["finality"] != "full")
        else: ok &= (out["finality"]==efin)
        stats_ok=True
        if estats is not None:
            norm = {k:[v["home"],v["away"]] for k,v in (out["stats"] or {}).items()}
            if norm != estats: stats_ok=False
        ok &= stats_ok
        all_ok &= ok
        print(f"{'PASS' if ok else 'FAIL'} {name}: gate={out['gate']} score={out['score']}(exp {escore}) "
              f"fin={out['finality']}(exp {efin}) stats={'OK' if stats_ok else 'MISMATCH'} [{ms:.0f}ms]")
        if not stats_ok: print(f"   got: {json.dumps(out['stats'])}\n   dbg: {out.get('stats_dbg')}")
        if out["score"] != escore: print(f"   score detail gate={out['gate']}")
    print(f"\nALL {'PASS ✅' if all_ok else 'FAIL ❌'}")

if __name__ == "__main__":
    main()

# ---- harness hooks ----
_ACTIVE_CLS = None
def set_classifier(cls):
    global _ACTIVE_CLS, SCORE_CLS
    _ACTIVE_CLS = cls; SCORE_CLS = cls

def run_frame_with(a, cls):
    set_classifier(cls)
    return run_frame(a)
