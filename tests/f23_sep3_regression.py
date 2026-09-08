#!/usr/bin/env python3
"""F15 DECISIVE regression suite - the recorded 3-Sep 3:0 failure, replayed.

The 3-Sep session (both phones captured) proved:
  1. Every match fired exactly ONE false "goal" 7-10s after keystream
     convergence - a unilateral kickoff/state summary (payload ~1197) with
     no peer echo. F13-v2 counted it as a goal on BOTH phones.
  2. The 3 real goals of the 3:0 match produced ZERO goal-family messages
     at the legacy sizes (wire evidence capture added in F15 to locate them).
  3. The final match's record carried the blocker "match start was not
     proven" because the probe that opened the second handshake arrived
     before the boundary fired and the fresh reader never saw it.

This suite replays that structure through the F15 reference (the same
logic as the Kotlin MatchProtocolReader/MatchTracker) and asserts the
failure modes are dead:

  TEST 1  the 3-Sep match shape: kickoff summary, no decodable goals,
          full-time burst => 0 goals, 1 unilateral summary, no phantom.
  TEST 2  the same match + one REAL goal (bidirectional cluster) => the
          goal is detected; the summary is still not a goal.
  TEST 3  the 3:0 shape: three real goals with a 1-0/2-0/3-0 progression
          + kickoff summary => exactly 3 goals (what both phones SHOULD
          have shown), all UNRESOLVED (uncalibrated - honest).
  TEST 4  probe seeding: a second match after a full-time boundary has a
          PROVEN handshake (the 3-Sep "match start was not proven" bug).
  TEST 5  STUN identity: 10-digit ASCII player IDs land in the record.
  TEST 6  echo arriving after the 10s cluster gap forms no phantom goal and
          loses the real one - documented, measured evidence says echoes
          arrive within seconds; retune only if a capture says otherwise.
"""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import f13_reference as ref

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print("  [%s] %s%s" % ("PASS" if ok else "FAIL", name, " - " + detail if detail else ""))


def build_match_3sep(gen, kickoff_payload=1197, real_goals=(), end_t=620):
    """Wire events mirroring the recorded 3-Sep match: probe/exchange,
    len-59 keystream convergence, kickoff summary 8s after the gate,
    optional real goal clusters, full-time control burst, trailing play."""
    events = []
    ordinal = [0]

    def add(t, sent_by_me, payload, payload_len):
        ordinal[0] += 1
        events.append((ordinal[0], int(t * 1000), sent_by_me, payload_len,
                       payload, min(len(payload), 60), 55938, 62612))

    t = 1.0
    add(t, True, *gen.probe())
    add(t + 0.1, False, *gen.exchange())
    # keystream convergence: 45 len-59 each way over ~22s
    for i in range(45):
        add(t + 0.2 + i * 0.5, True, gen.len59("out"), 59)
        add(t + 0.45 + i * 0.5, False, gen.len59("in"), 59)
    gate_t = t + 0.2 + 44 * 0.5  # ~23.2s
    # the kickoff/state summary: ONE goal-family message, one direction
    ko_wire, ko_len = gen.goal("out", 1, 0, payload_len=kickoff_payload)
    add(gate_t + 8.0, True, ko_wire, ko_len)
    # steady play: mid-size event-channel + filler (no goal family)
    t = gate_t + 9.0
    gi = 0
    while t < end_t - 40:
        add(t, True, *gen.filler())
        add(t + 0.01, False, *gen.filler())
        t += 0.4
    # real goals (bidirectional clusters: original + peer echo)
    for (gt, scorer_dir, sa, sb) in real_goals:
        wire, plen = gen.goal(scorer_dir, sa, sb, payload_len=920)
        add(gt, scorer_dir == "out", wire, plen)
        wire2, plen2 = gen.goal("in" if scorer_dir == "out" else "out", sa, sb, payload_len=948)
        add(gt + 0.9, scorer_dir != "out", wire2, plen2)
    # full-time control burst (both directions, 24 each within 8s) + trailing
    for i in range(24):
        add(end_t - 35 + i * 0.3, True, *gen.control())
        add(end_t - 35 + i * 0.3 + 0.15, False, *gen.control())
    for i in range(6):
        add(end_t - 5 + i * 0.5, True, *gen.filler())
    return events, gate_t


def run_tracker(events, wall_end):
    tracker = ref.Tracker(calibration=None)
    tracker.begin_session()
    # feed in 1s batches like the app's telemetry poll (all times in ms)
    i = 0
    wall = 0
    while i < len(events):
        batch = events[i:i + 60]
        wall = batch[-1][1] + 1000
        tracker.on_telemetry(batch, wall)
        i += len(batch)
    tracker.on_telemetry([], int(wall_end * 1000))
    tracker.end_session("end")
    return tracker


print("=" * 100)
print("F15 REGRESSION 1: the recorded 3-Sep match shape (kickoff summary, no decodable real goals)")
print("=" * 100)
gen = ref.WireGen(seed=97)
events, gate_t = build_match_3sep(gen, real_goals=())
tracker = run_tracker(events, wall_end=700)
records = tracker.records
check("one record sealed", len(records) == 1, "records=%d" % len(records))
r = records[0]
check("ZERO goals (the 3-Sep app wrongly showed 1)", r["total_goals"] == 0,
      "total_goals=%d" % r["total_goals"])
check("kickoff summary recorded as evidence, not a goal", r["unilateral_summaries"] == 1,
      "unilateral_summaries=%d" % r["unilateral_summaries"])
check("gated + full-time observed", r["gated"] and r["completed"], "gated=%s completed=%s" % (r["gated"], r["completed"]))
check("settlement still blocked (calibration)", not r["settleable"],
      "; ".join(r["blockers"][:2]))

print()
print("=" * 100)
print("F15 REGRESSION 2: same match + ONE real goal (bidirectional cluster at t+300)")
print("=" * 100)
gen = ref.WireGen(seed=98)
events, gate_t = build_match_3sep(gen, real_goals=((gate_t + 300, "out", 1, 0),))
tracker = run_tracker(events, wall_end=700)
r = tracker.records[0]
check("exactly ONE goal detected", r["total_goals"] == 1, "total_goals=%d" % r["total_goals"])
check("goal is corroborated (peer echo seen)", r["events"][0].corroborated,
      "echo_after_ms=%s" % r["events"][0].echo_after_ms)
check("kickoff summary still not a goal", r["unilateral_summaries"] == 1)
check("scorer UNRESOLVED without calibration", r["events"][0].scorer_side == "UNRESOLVED",
      "side=%s" % r["events"][0].scorer_side)

print()
print("=" * 100)
print("F15 REGRESSION 3: the true 3:0 shape - three real goals, all one side + kickoff summary")
print("=" * 100)
gen = ref.WireGen(seed=99)
goals = ((gate_t + 120, "in", 0, 1), (gate_t + 280, "in", 0, 2), (gate_t + 430, "in", 0, 3))
events, gate_t = build_match_3sep(gen, real_goals=goals)
tracker = run_tracker(events, wall_end=700)
r = tracker.records[0]
check("exactly THREE goals (what both phones should have shown)", r["total_goals"] == 3,
      "total_goals=%d" % r["total_goals"])
check("all three corroborated", all(e.corroborated for e in r["events"]))
check("all UNRESOLVED - honest attribution", all(e.scorer_side == "UNRESOLVED" for e in r["events"]))
check("kickoff summary separate from the goals", r["unilateral_summaries"] == 1)

print()
print("=" * 100)
print("F15 REGRESSION 4: probe seeding - the second match of a session has a proven start")
print("=" * 100)
gen = ref.WireGen(seed=100)
events, gate_t = build_match_3sep(gen, real_goals=())
# a SECOND match: 60s silence, then new handshake on the same ports
second = []
o = [len(events)]
def add2(t, sent_by_me, payload, payload_len):
    o[0] += 1
    second.append((o[0], int(t * 1000), sent_by_me, payload_len,
                   payload, min(len(payload), 60), 55938, 62612))
base = 700.0
add2(base, True, *gen.probe())
add2(base + 0.1, False, *gen.exchange())
for i in range(45):
    add2(base + 0.2 + i * 0.5, True, gen.len59("out"), 59)
    add2(base + 0.45 + i * 0.5, False, gen.len59("in"), 59)
t = base + 25
while t < base + 560:
    add2(t, True, *gen.filler())
    t += 0.4
for i in range(24):
    add2(base + 570 + i * 0.3, True, *gen.control())
    add2(base + 570 + i * 0.3 + 0.15, False, *gen.control())
all_events = events + second
tracker = run_tracker(all_events, wall_end=1400)
check("two records (two matches)", len(tracker.records) == 2, "records=%d" % len(tracker.records))
if len(tracker.records) == 2:
    r2 = tracker.records[1]
    not_proven = [b for b in r2["blockers"] if "match start was not proven" in b]
    check("second match start IS proven (probe seeded)", not not_proven,
          "blockers=%s" % (not_proven or "clean")[:80])
    check("second record has handshake evidence", r2["handshake"] is not None,
          "probe_by_me=%s exchange_by_me=%s" % (r2["handshake"].probe_sent_by_me, r2["handshake"].exchange_sent_by_me))

print()
print("=" * 100)
print("F15 REGRESSION 5: STUN identity - 10-digit player IDs recorded")
print("=" * 100)
gen = ref.WireGen(seed=101)
events, gate_t = build_match_3sep(gen, real_goals=())
# custom-STUN frames (0x080B type, magic cookie at 4..7, 10-digit ASCII IDs)
def stun_frame(player_id):
    f = bytearray(60)
    f[0] = 0x08
    f[1] = 0x0B
    f[4:8] = ref.STUN_MAGIC
    digits = player_id.encode("ascii")
    f[20:30] = digits
    return bytes(f)
events2 = list(events)
for k in range(5):
    events2.append((100000 + k, gate_t * 1000 + 2000 + k, True, 60, stun_frame("1012115771"), 60, 55938, 62612))
    events2.append((100100 + k, gate_t * 1000 + 2100 + k, False, 60, stun_frame("2098765432"), 60, 62612, 55938))
events2.sort(key=lambda e: e[0])
tracker = run_tracker(events2, wall_end=700)
r = tracker.records[0]
check("local player ID captured", r["local_player_id"] == b"1012115771",
      "local=%s" % r["local_player_id"])
check("remote player ID captured", r["remote_player_id"] == b"2098765432",
      "remote=%s" % r["remote_player_id"])

print()
print("=" * 100)
print("F15 REGRESSION 6: echo after the 10s cluster gap (documented limit)")
print("=" * 100)
gen = ref.WireGen(seed=102)
events, gate_t = build_match_3sep(gen, real_goals=())
wire, plen = gen.goal("out", 1, 0, payload_len=920)
events.append((200000, int((gate_t + 300) * 1000), True, plen, wire, 60, 55938, 62612))
wire2, plen2 = gen.goal("in", 1, 0, payload_len=948)
events.append((200001, int((gate_t + 311.5) * 1000), False, plen2, wire2, 60, 62612, 55938))
events.sort(key=lambda e: e[0])
tracker = run_tracker(events, wall_end=700)
r = tracker.records[0]
check("no phantom goal from a >10s-separated echo", r["total_goals"] == 0,
      "total_goals=%d (kickoff + original + late echo summaries=%d)" % (r["total_goals"], r["unilateral_summaries"]))
check("kickoff + both echo halves logged as summaries", r["unilateral_summaries"] == 3,
      "unilateral_summaries=%d (kickoff + original + late echo)" % r["unilateral_summaries"])

print()
failed = [r for r in RESULTS if not r[1]]
print("=" * 100)
print("F15 SEP-3 REGRESSION SUITE: %s (%d/%d passed)" % (
    "ALL PASS" if not failed else "FAILURES", len(RESULTS) - len(failed), len(RESULTS)))
for name, _, detail in failed:
    print("  FAILED: %s - %s" % (name, detail))
sys.exit(1 if failed else 0)
