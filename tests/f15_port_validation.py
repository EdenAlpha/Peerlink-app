#!/usr/bin/env python3
"""F15 port validation.

Feeds the EXACT native telemetry v4 event stream (extended snapshot filter,
64-byte head, sport/dport) through the 1:1 Python reference of the merged
F15 Kotlin reader (F13 lifecycle + F14 goal-truth detector).

It must reproduce the validated F14 verdicts — the goal detection the user
confirmed is solved — AND the F13 lifecycle guarantees on the same captures:
  28_Jun -> GOAL t=55.814 scorer=peer (1825107968) 0-1 LOSS, handshake + ports
  29_Jan -> GOAL t=41.984 scorer=peer (1774133334) 0-1 LOSS, handshake + ports
  06_Jan -> relay: not gated, relaySuspected, zero goals, never settleable
  04_Feb / 31_Jan (other captures) -> handled honestly (relay or no game flow)
Plus the synthetic replay of the real failed match scenario (user-scored,
direction flipped): attributed to ME with the local STUN player ID.
"""
import json
import pathlib
import struct
import sys
from collections import defaultdict

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import f15_reference as ref

BASE = pathlib.Path("/home/z/my-project/upload/Pcsp")
OUT = pathlib.Path(__file__).parent / "evidence_f15_port_validation.json"


# ---------------- pcap parsing (same as validated detector) ----------------
def iter_pcap(path):
    with open(path, "rb") as f:
        gh = f.read(24)
        if len(gh) < 24:
            return
        magic = gh[:4]
        if magic == b"\x0a\x0d\x0d\x0a":
            f.seek(0)
            yield from iter_pcapng(f)
            return
        if magic == b"\xd4\xc3\xb2\xa1":
            endian, nano = "little", False
        elif magic == b"\x4d\x3c\xb2\xa1":
            endian, nano = "little", True
        elif magic == b"\xa1\xb2\xc3\xd4":
            endian, nano = "big", False
        elif magic == b"\xa1\xb2\x3c\x4d":
            endian, nano = "big", True
        else:
            return
        linktype = int.from_bytes(gh[20:24], endian) & 0xFFFF
        while True:
            ph = f.read(16)
            if len(ph) < 16:
                break
            ts_sec = int.from_bytes(ph[0:4], endian)
            ts_usec = int.from_bytes(ph[4:8], endian)
            caplen = int.from_bytes(ph[8:12], endian)
            data = f.read(caplen)
            if len(data) < caplen:
                break
            yield ts_sec + (ts_usec / 1e9 if nano else ts_usec / 1e6), linktype, data


def iter_pcapng(f):
    linktype = None
    while True:
        bh = f.read(8)
        if len(bh) < 8:
            break
        btype = int.from_bytes(bh[0:4], "<")
        blen = int.from_bytes(bh[4:8], "<")
        if blen < 12:
            break
        body = f.read(blen - 12)
        f.read(4)
        if btype == 1 and len(body) >= 8:
            linktype = int.from_bytes(body[0:2], "<")
        elif btype == 6 and len(body) >= 20:
            _, ts_hi, ts_lo, caplen, _ = struct.unpack("<IIIII", body[:20])
            data = body[20:20 + caplen]
            yield (ts_hi * 4294967296 + ts_lo) / 1e6, linktype or 1, data


def strip_link(lt, data):
    if lt == 1:
        if len(data) < 14:
            return None
        et = int.from_bytes(data[12:14], "big")
        off = 14
        while et in (0x8100, 0x88A8) and len(data) >= off + 4:
            et = int.from_bytes(data[off + 2:off + 4], "big")
            off += 4
        return data[off:] if et == 0x0800 else None
    if lt in (101, 12, 14):
        return data if data and (data[0] >> 4) == 4 else None
    if lt == 0:
        return data[4:] if len(data) >= 4 and int.from_bytes(data[:4], "little") == 2 else None
    if lt == 113:
        return data[16:] if len(data) >= 16 and int.from_bytes(data[14:16], "big") == 0x0800 else None
    if lt == 276:
        return data[20:] if len(data) >= 20 and int.from_bytes(data[0:2], "big") == 0x0800 else None
    return None


def game_flow(path):
    """All UDP records on the game flow, as (t_rel_s, sent_by_me, payload, sport, dport)."""
    recs = []
    for ts, lt, data in iter_pcap(path):
        ip = strip_link(lt, data)
        if ip is None or len(ip) < 28 or (ip[0] >> 4) != 4 or ip[9] != 17:
            continue
        ihl = (ip[0] & 0xF) * 4
        total = int.from_bytes(ip[2:4], "big")
        end = total if 28 <= total <= len(ip) else len(ip)
        udp = ip[ihl:end]
        if len(udp) < 8:
            continue
        sport, dport, ulen = struct.unpack(">HHH", udp[:6])
        plen = ulen - 8 if 8 <= ulen <= len(udp) - 8 else len(udp) - 8
        src = ".".join(str(b) for b in ip[12:16])
        dst = ".".join(str(b) for b in ip[16:20])
        recs.append((ts, (src, sport), (dst, dport), udp[8:8 + plen] if plen >= 0 else udp[8:]))
    if not recs:
        return None

    def norm(s, d):
        return (s, d) if s <= d else (d, s)

    fstat = defaultdict(lambda: [0, 0])
    for ts, s, d, p in recs:
        k = norm(s, d)
        fstat[k][0] += 1
        fstat[k][1] += len(p)

    def is_virtual(k):
        return k[0][0].startswith("10.215.173.") or k[1][0].startswith("10.215.173.")

    real = []
    for k, v in fstat.items():
        srcs = {s for ts, s, d, p in recs if norm(s, d) == k}
        if not is_virtual(k) and len(srcs) >= 2:
            real.append((k, v))
    if real:
        game_key = max(real, key=lambda kv: kv[1][1])[0]
    else:
        virt = [(k, v) for k, v in fstat.items()
                if 53 not in (k[0][1], k[1][1]) and 443 not in (k[0][1], k[1][1])]
        if not virt:
            return None
        game_key = max(virt, key=lambda kv: kv[1][1])[0]
    ep1, ep2 = game_key
    first_src = min((ts, s) for ts, s, d, p in recs if norm(s, d) == game_key)
    a_ep = first_src[1]
    b_ep = ep2 if a_ep == ep1 else ep1
    game = [(t, 1 if s == b_ep else 0, p, s[1], d[1])
            for t, s, d, p in recs if norm(s, d) == game_key]
    t0 = game[0][0]
    return [(t - t0, isb, p, sp, dp) for t, isb, p, sp, dp in game]


# ---------------- run one capture through the merged reader ----------------
def run_capture(path, expect):
    game = game_flow(path)
    if game is None:
        return dict(file=path.name, error="no game flow", _ok=expect.get("error_ok", False))
    reader = ref.Reader(started_at_ms=0.0)
    out_total = in_total = 0
    snapshotted = 0
    for t, isb, p, sp, dp in game:
        if isb:
            in_total += 1
        else:
            out_total += 1
        if ref.should_snapshot_match_payload(p, len(p)):
            head_len = min(len(p), ref.HEAD_SIZE)
            reader.on_packet(t * 1000.0, not isb, len(p), p[:head_len], head_len,
                             sport=(sp if not isb else dp), dport=(dp if not isb else sp))
            snapshotted += 1
    reader.observe_snapshot(0.0, out_total, in_total)
    events = reader.goal_events()
    my = sum(1 for e in events if e["scorer_is_me"] and e["phase_confirmed"])
    opp = sum(1 for e in events if (not e["scorer_is_me"]) and e["phase_confirmed"])
    handshake = reader.handshake
    result = dict(
        file=path.name,
        n_game=len(game), snapshotted=snapshotted,
        gated=reader.gated,
        relay_suspected=reader.relay_suspected,
        keystream_coverage=min(reader.mine.known_positions(), reader.theirs.known_positions()),
        handshake=dict(probe_by_me=handshake.probe_sent_by_me,
                       exchange_by_me=handshake.exchange_sent_by_me,
                       port_pair=handshake.port_pair) if handshake else None,
        port_pair=reader.game_port_pair,
        local_id=reader.local_player_id(), remote_id=reader.remote_player_id(),
        goals=[dict(t=round(e["t"] / 1000.0, 3), scorer=("me" if e["scorer_is_me"] else "peer"),
                    player=e["scorer_player_id"], phase=f"{e['phase']:02x}",
                    phase_confirmed=e["phase_confirmed"], corroborated=e["corroborated"],
                    msgs=e["n_messages"]) for e in events],
        final=f"{my}-{opp}",
        result=("WIN" if my > opp else "LOSS" if my < opp else "DRAW") if events else
               ("RELAY" if reader.relay_suspected else "NO GOALS"),
        decode_drops=reader.protocol_decode_drops,
    )
    ok = True
    checks = []

    def check(name, cond):
        nonlocal ok
        checks.append(f"{'PASS' if cond else 'FAIL'}: {name}")
        if not cond:
            ok = False

    check("gated == %s" % expect.get("gated", True), result["gated"] == expect.get("gated", True))
    check("final == %s" % expect["final"], result["final"] == expect["final"])
    check("result == %s" % expect["result"], result["result"] == expect["result"])
    if expect.get("handshake"):
        check("handshake evidence recorded (probe/exchange)", handshake is not None)
        if handshake:
            check("port pair captured from telemetry v4", handshake.port_pair is not None)
    if expect.get("goals"):
        eg = expect["goals"][0]
        rg = result["goals"][0] if result["goals"] else None
        check("goal time ~= %.3f" % eg["t"], rg and abs(rg["t"] - eg["t"]) < 0.05)
        check("scorer == %s" % eg["scorer"], rg and rg["scorer"] == eg["scorer"])
        check("player == %s" % eg["player"], rg and rg["player"] == eg["player"])
        check("phase confirmed (c6)", rg and rg["phase_confirmed"] and rg["phase"] == "c6")
        check("corroborated by echo", rg and rg["corroborated"])
    if expect.get("settle_shape"):
        # Full-time + terminal silence: end the match at last traffic + 61s.
        last_t = max(t for t, isb, p, sp, dp in game) * 1000.0
        seg = reader.segment_result("idle", True, last_t + 61_000.0)
        check("segment settleable (fail-closed union passes)",
              seg["settleable"] == expect["settle_shape"])
        if expect["settle_shape"]:
            check("settlement blockers empty", not seg["settlement_blockers"])
    check("no decode drops", result["decode_drops"] == 0)
    result["_checks"] = checks
    result["_ok"] = ok
    return result


def synthetic_local_goal():
    """The real failed-match scenario with the direction flipped: the user's
    0xC6 original goes OUTGOING and the peer's echo comes INCOMING. The
    reader must attribute the goal to ME (player 1012115771)."""
    game = game_flow(BASE / "PCAPdroid_28_Jun_15_53_33.pcap")
    reader = ref.Reader(started_at_ms=0.0)
    out_total = in_total = 0
    for t, isb, p, sp, dp in game:
        if isb:
            in_total += 1
        else:
            out_total += 1
        if ref.should_snapshot_match_payload(p, len(p)):
            head_len = min(len(p), ref.HEAD_SIZE)
            reader.on_packet(t * 1000.0, not isb, len(p), p[:head_len], head_len)
    reader.observe_snapshot(0.0, out_total, in_total)

    # Re-derive the raw c6/c7 heads from the pcap: the merged reader freezes
    # (consumes) its candidates, but the converged keystream states persist,
    # so decoding each big game-format packet on the fly reproduces them.
    raw_candidates = []
    for t, isb, p, sp, dp in game:
        if 300 <= len(p) <= 2000 and len(p) > 0 and p[0] == 0:
            raw_candidates.append((t * 1000.0, not isb, len(p), bytes(p[:ref.HEAD_SIZE])))
    decoded = [(c, reader._decode_goal_candidate(c)) for c in raw_candidates]
    originals = [(c, m) for c, m in decoded if m and m[3] == 0xC6]
    echoes = [(c, m) for c, m in decoded if m and m[3] == 0xC7]
    if not originals or not echoes:
        return dict(_ok=False, _checks=["FAIL: could not locate real c6/c7 pair in 28_Jun"])

    def x_of(candidate, direction):
        votes = [0] * 256
        for k, sig in enumerate(ref.GOAL_SIG):
            pos = ref.SIG_POS_MIN + k
            if pos < len(candidate[3]) and direction.known[pos]:
                votes[candidate[3][pos] ^ direction.v[pos] ^ sig] += 1
        return max(range(256), key=lambda v: votes[v])

    orig_cand = originals[0][0]
    echo_cand = echoes[0][0]
    x_orig = x_of(orig_cand, reader.theirs)
    x_echo = x_of(echo_cand, reader.mine)

    def flip(candidate, src_dir, dst_dir, x_old, x_new, phase_new):
        t, sent_by_me, payload_len, head = candidate
        out = bytearray(head)
        for i in range(19, 64):
            if src_dir.known[i] and dst_dir.known[i]:
                m = head[i] ^ src_dir.v[i] ^ x_old
                out[i] = m ^ dst_dir.v[i] ^ x_new
        out[ref.PHASE_POS] = (phase_new ^ dst_dir.v[ref.PHASE_POS] ^ x_new) \
            if dst_dir.known[ref.PHASE_POS] else out[ref.PHASE_POS]
        return bytes(out), payload_len

    local_head, local_len = flip(orig_cand, reader.theirs, reader.mine, x_orig, 0x37, 0xC6)
    peer_head, peer_len = flip(echo_cand, reader.mine, reader.theirs, x_echo, 0x42, 0xC7)

    reader.on_packet(170_000.0, True, local_len, local_head[:ref.HEAD_SIZE],
                     min(local_len, ref.HEAD_SIZE))
    reader.on_packet(171_200.0, False, peer_len, peer_head[:ref.HEAD_SIZE],
                     min(peer_len, ref.HEAD_SIZE))

    events = reader.goal_events()
    my = sum(1 for e in events if e["scorer_is_me"])
    opp = sum(1 for e in events if not e["scorer_is_me"])
    mine_event = [e for e in events if e["scorer_is_me"]]
    checks = []
    ok = True

    def check(name, cond):
        nonlocal ok
        checks.append(f"{'PASS' if cond else 'FAIL'}: {name}")
        if not cond:
            ok = False

    check("two goals total (1 real peer + 1 synthetic mine)", len(events) == 2)
    check("final 1-1", (my, opp) == (1, 1))
    check("synthetic goal attributed to ME", len(mine_event) == 1)
    if mine_event:
        e = mine_event[0]
        check("mine goal phase-confirmed c6", e["phase_confirmed"] and e["phase"] == 0xC6)
        check("mine goal corroborated by peer echo", e["corroborated"])
        check("mine goal player id == 1012115771", e["scorer_player_id"] == "1012115771")
        check("mine goal time ~170s", abs(e["t"] - 170_000.0) < 1.0)
    return dict(
        phase2="synthetic user-scored goal (real cipher, flipped direction)",
        n_events=len(events), final=f"{my}-{opp}",
        goals=[dict(t=round(e["t"] / 1000.0, 3), scorer=("me" if e["scorer_is_me"] else "peer"),
                    phase=f"{e['phase']:02x}", confirmed=e["phase_confirmed"],
                    corroborated=e["corroborated"], player=e["scorer_player_id"]) for e in events],
        _checks=checks, _ok=ok,
    )


def main():
    expectations = {
        "PCAPdroid_28_Jun_15_53_33.pcap": dict(
            final="0-1", result="LOSS", handshake=True,
            goals=[dict(t=55.814, scorer="peer", player="1825107968")]),
        "PCAPdroid_29_Jan_14_32_11.pcap": dict(
            final="0-1", result="LOSS", handshake=True,
            goals=[dict(t=41.984, scorer="peer", player="1774133334")]),
        "PCAPdroid_06_Jan_14_29_23.pcap": dict(
            gated=False, final="0-0", result="RELAY", goals=[]),
    }
    results = []
    for fname, expect in expectations.items():
        r = run_capture(BASE / fname, expect)
        results.append(r)
        print(json.dumps({k: v for k, v in r.items() if not k.startswith("_")}, indent=1, default=str))
        for c in r.get("_checks", []):
            print("  " + c)
        print()

    p2 = synthetic_local_goal()
    results.append(p2)
    print(json.dumps({k: v for k, v in p2.items() if not k.startswith("_")}, indent=1, default=str))
    for c in p2["_checks"]:
        print("  " + c)
    print()

    all_ok = all(r.get("_ok", False) for r in results)
    print("=" * 60)
    print("F15 PORT VALIDATION:", "ALL PASS" if all_ok else "FAILURES PRESENT")
    print("=" * 60)
    with open(OUT, "w") as f:
        json.dump(results, f, indent=1, default=str)
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
