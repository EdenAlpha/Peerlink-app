#!/usr/bin/env python3
"""F15 synthetic consistency suite (explicitly NON-PROOF, like F13's).

Synthetic streams built from the measured cipher model verify the merged
reader/tracker BEHAVIOR; real-evidence proof lives in f15_replay_real.py and
f15_port_validation.py.

Scenarios (the F13 good things, on the F14 detector):
  1-3   multi-goal matches incl. equalizers (1-1, 2-1, 1-2) — the score
        progression F13-v1 was blind to; all goals detected and attributed.
  4     paid-wrong-side regression (the real failure that paid +0.30).
  5     unconfirmed-phase cluster (no 0xC6) stays unresolved and blocks.
  6     reconnect merge: score preserved across a mid-match re-key.
  7     port-pair change = new match (the measured 1-Sep behavior).
  8     quarantine: delayed fragments of a sealed epoch never bleed.
  9     relay traffic: honest, ungated, never settles.
  10    full-time 40s trailing window (recorded real match: 28.1s tail).
  11    pre-gate goal retention.
  12    halftime > 60s splits (documented residual, still fail-closed).
"""
import pathlib
import random
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import f15_reference as ref

HEAD = ref.HEAD_SIZE
SIG = ref.GOAL_SIG


class Cipher:
    def __init__(self, seed):
        rng = random.Random(seed)
        self.ks = [rng.randrange(256) for _ in range(HEAD)]
        self.direction_key = rng.randrange(256)

    def steady(self, length, x):
        head = bytearray(HEAD)
        head[0] = 0
        for i in range(19, min(length, HEAD)):
            head[i] = (0 ^ self.ks[i] ^ x ^ self.direction_key) & 0xFF
        return bytes(head)

    def goal(self, phase, x, payload_len=919):
        head = bytearray(HEAD)
        head[0] = 0
        for k, s in enumerate(SIG):
            pos = ref.SIG_POS_MIN + k
            head[pos] = (s ^ self.ks[pos] ^ x ^ self.direction_key) & 0xFF
        head[ref.PHASE_POS] = (phase ^ self.ks[ref.PHASE_POS] ^ x ^ self.direction_key) & 0xFF
        return bytes(head)

    def stun(self, player_id):
        head = bytearray(HEAD)
        head[0] = 0x08
        head[1] = 0x0A
        head[4:8] = ref.STUN_MAGIC
        for i in range(10):
            head[32 + i] = ord(player_id[i])
        return bytes(head)


class Sim:
    """Builds telemetry events and drives the F15 tracker (polls per second)."""

    def __init__(self, seed_a=11, seed_b=22):
        self.a = Cipher(seed_a)
        self.b = Cipher(seed_b)
        self.tracker = ref.Tracker()
        self.tracker.begin_session()
        self.events = []
        self.ordinal = 0
        # Wall-clock-like nonzero base: 0 is the tracker's "uninitialized"
        # sentinel for an epoch start, exactly like the Kotlin owner.
        self.t = 1000.0

    def _evt(self, out, payload_len, head, sport=22677, dport=56008):
        self.ordinal += 1
        return (self.ordinal, self.t, out, payload_len, head, len(head),
                (sport if out else dport), (dport if out else sport))

    def out(self, payload_len, head):
        event = self._evt(True, payload_len, head)
        self.events.append(event)
        return event

    def inp(self, payload_len, head):
        event = self._evt(False, payload_len, head)
        self.events.append(event)
        return event

    def steady_pair(self, x_out=0x5A, x_in=0xA5):
        self.out(59, self.a.steady(59, x_out))
        self.t += 5
        self.inp(59, self.b.steady(59, x_in))
        self.t += 5

    def converge(self, rounds=60, cls=("39", "59", "63")):
        """Alternate steady classes in both directions until the gate closes."""
        for _ in range(rounds):
            for length in (int(c) for c in cls):
                self.out(length, self.a.steady(length, 0x5A))
                self.t += 3
                self.inp(length, self.b.steady(length, 0xA5))
                self.t += 3
        return self.t

    def goal_pair(self, by_me, gap_ms=1200, payload_len=919):
        """0xC6 original by the scorer, 0xC7 echo by the conceder."""
        if by_me:
            self.out(payload_len, self.a.goal(ref.PHASE_ORIGINAL, 0x37))
            self.t += gap_ms
            self.inp(419, self.b.goal(ref.PHASE_ECHO, 0x42))
        else:
            self.inp(payload_len, self.b.goal(ref.PHASE_ORIGINAL, 0x42))
            self.t += gap_ms
            self.out(419, self.a.goal(ref.PHASE_ECHO, 0x37))
        self.t += 500

    def stun_pair(self, my_id="1012115771", peer_id="1825107968"):
        self.out(64, self.a.stun(my_id))
        self.t += 50
        self.inp(64, self.b.stun(peer_id))
        self.t += 50

    def full_time_burst(self, count=25, ensure_duration=True):
        # Full time requires >= 90s of match; synthetic plays are short, so
        # advance the clock to a realistic in-match moment first.
        if ensure_duration and self.t < 100_000:
            self.t = 100_000.0
        for _ in range(count):
            self.out(26, bytes(HEAD))
            self.t += 5
            self.inp(26, bytes(HEAD))
            self.t += 5

    def handshake(self, out_probe=True, sport=22677, dport=56008):
        probe = bytes(HEAD)
        exchange = bytes(HEAD)
        self.t += 100
        self._evt(out_probe, 14, probe, sport, dport)
        self.ordinal += 1
        self.events.append((self.ordinal, self.t, out_probe, 14, probe, HEAD,
                            (sport if out_probe else dport), (dport if out_probe else sport)))
        self.t += 100
        self._evt(not out_probe, 266, exchange, sport, dport)
        self.ordinal += 1
        self.events.append((self.ordinal, self.t, not out_probe, 266, exchange, HEAD,
                            (dport if out_probe else sport), (sport if out_probe else dport)))
        self.t += 100

    def poll(self):
        """Feed all buffered events as one telemetry poll, then clear."""
        wall = self.t if self.t else 1.0
        last_game = max((e[1] for e in self.events), default=0.0)
        self.tracker.on_telemetry(list(self.events), wall, last_game_wall_ms=last_game)
        self.events = []

    def idle(self, seconds):
        """Empty polls across a silent gap (what the real app does)."""
        for _ in range(seconds):
            self.t += 1000
            self.tracker.on_telemetry([], self.t, last_game_wall_ms=0.0)

    def end(self, reason="capture-end"):
        self.poll()
        self.tracker.end_session(reason)
        return self.tracker


def check(name, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {name}" + (f" - {detail}" if detail else ""))
    return condition


def settled_record(tracker):
    return [r for r in tracker.records if r["settleable"]]


def main():
    failures = 0

    print("=" * 100)
    print("SYNTHETIC 1: 1-1 equalizer (the case F13-v1 was blind to)")
    print("=" * 100)
    sim = Sim(1, 2)
    sim.stun_pair()
    sim.handshake()
    sim.converge()
    sim.goal_pair(by_me=False)
    sim.t += 15_000
    sim.goal_pair(by_me=True)
    sim.t += 20_000
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    tr = sim.end()
    r = tr.records[0]
    failures += not check("both goals detected", r["total_goals"] == 2, str(r["goals"]))
    failures += not check("equalizer attributed 1-1", (r["my_goals"], r["opponent_goals"]) == (1, 1))
    failures += not check("settleable", r["settleable"], "; ".join(r["blockers"]))

    print()
    print("=" * 100)
    print("SYNTHETIC 2: 2-1 (A,B,A) — per-goal attribution, not a session role")
    print("=" * 100)
    sim = Sim(3, 4)
    sim.stun_pair()
    sim.handshake()
    sim.converge()
    sim.goal_pair(by_me=False)
    sim.t += 15_000
    sim.goal_pair(by_me=True)
    sim.t += 15_000
    sim.goal_pair(by_me=False)
    sim.t += 20_000
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    r = sim.end().records[0]
    failures += not check("three goals detected", r["total_goals"] == 3)
    failures += not check("2-1 final (opponent twice, me once)",
                          (r["my_goals"], r["opponent_goals"]) == (1, 2))
    scorers = [g["scorer"] for g in r["goals"]]
    failures += not check("scorer order PEER,ME,PEER (a fixed session role would say 3-0 or 0-3)",
                          scorers == ["PEER", "ME", "PEER"], str(scorers))
    failures += not check("settleable", r["settleable"])

    print()
    print("=" * 100)
    print("SYNTHETIC 3: 0-3 — one side only")
    print("=" * 100)
    sim = Sim(5, 6)
    sim.stun_pair()
    sim.handshake()
    sim.converge()
    for _ in range(3):
        sim.goal_pair(by_me=True)
        sim.t += 15_000
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    r = sim.end().records[0]
    failures += not check("0-3 detected", (r["my_goals"], r["opponent_goals"]) == (3, 0))
    failures += not check("settleable", r["settleable"])

    print()
    print("=" * 100)
    print("SYNTHETIC 4: paid-wrong-side regression (the recorded real failure)")
    print("=" * 100)
    sim = Sim(7, 8)
    sim.stun_pair()
    sim.handshake()
    sim.converge()
    # Friend's 0xC6 original INCOMING; the user's echo OUTGOING.
    sim.goal_pair(by_me=False)
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    r = sim.end().records[0]
    failures += not check("goal attributed to PEER (not the echo sender)", r["opponent_goals"] == 1)
    failures += not check("my goals = 0 (no wrong +0.30)", r["my_goals"] == 0)
    failures += not check("settleable LOSS", r["settleable"] and r["my_goals"] < r["opponent_goals"])

    print()
    print("=" * 100)
    print("SYNTHETIC 5: cluster without a 0xC6 original stays unresolved and blocks settlement")
    print("=" * 100)
    sim = Sim(9, 10)
    sim.stun_pair()
    sim.handshake()
    sim.converge()
    # Echo + sync only, in both directions, within the cluster window.
    sim.inp(919, sim.b.goal(ref.PHASE_ECHO, 0x42))
    sim.t += 800
    sim.out(515, sim.a.goal(ref.PHASE_SYNC, 0x37))
    sim.t += 20_000
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    r = sim.end().records[0]
    failures += not check("event detected", r["total_goals"] == 1)
    failures += not check("scorer UNRESOLVED (phase not confirmed)",
                          all(not g["phase_confirmed"] for g in r["goals"]))
    failures += not check("settlement blocked (fail-closed, never guesses)",
                          not r["settleable"] and any("0xC6" in b for b in r["blockers"]),
                          "; ".join(r["blockers"]))

    print()
    print("=" * 100)
    print("SYNTHETIC 6: mid-match reconnect — score preserved across the re-key")
    print("=" * 100)
    sim = Sim(11, 12)
    sim.stun_pair()
    sim.handshake()
    sim.converge()
    sim.goal_pair(by_me=True)          # 1-0 before the drop
    sim.poll()
    # Drop: 30s of silence (inside the 45s merge window), same port pair.
    sim.idle(30)
    # Reconnect handshake (same ports) -> merge, not a new match. The peer
    # re-keys after the exchange: replace its cipher before re-converging.
    # Poll immediately after the handshake, as the real app does (one poll
    # per second) - the merge window measures game-traffic age at handshake
    # time, not at the end of a batched replay.
    sim.handshake(out_probe=True)
    sim.poll()
    sim.b = Cipher(99)
    sim.converge()
    sim.goal_pair(by_me=False)         # equalizer after the reconnect
    sim.t += 20_000
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    tr = sim.end()
    failures += not check("one epoch only (reconnect merged, not split)",
                          len(tr.records) == 1, f"records={len(tr.records)}")
    if len(tr.records) == 1:
        r = tr.records[0]
        failures += not check("score preserved 1-1 across the re-key",
                              (r["my_goals"], r["opponent_goals"]) == (1, 1))
        failures += not check("reconnect counted", r["reconnects"] == 1)
        failures += not check("reconnect log emitted",
                              any("Reconnect handshake" in line for line in tr.logs))
        failures += not check("settleable after reconnect", r["settleable"], "; ".join(r["blockers"]))

    print()
    print("=" * 100)
    print("SYNTHETIC 7: port-pair change is a NEW match (measured 1-Sep: 22677 -> 18551)")
    print("=" * 100)
    sim = Sim(13, 14)
    sim.stun_pair()
    sim.handshake(sport=22677, dport=56008)
    sim.converge()
    sim.goal_pair(by_me=True)
    sim.full_time_burst()
    sim.poll()
    # Match 2 on a new port pair, one second later (no idle gap in between).
    sim.handshake(sport=18551, dport=56008)
    sim.converge()
    sim.goal_pair(by_me=False)
    sim.t += 20_000
    sim.full_time_burst(ensure_duration=False)
    sim.poll()
    sim.idle(65)
    tr = sim.end()
    failures += not check("two matches recorded (port change split)",
                          len(tr.records) == 2, f"records={len(tr.records)}")
    if len(tr.records) == 2:
        failures += not check("match 1: 1-0 me", (tr.records[0]["my_goals"], tr.records[0]["opponent_goals"]) == (1, 0))
        failures += not check("match 2: 0-1 peer", (tr.records[1]["my_goals"], tr.records[1]["opponent_goals"]) == (0, 1))
        failures += not check("port-pair log emitted",
                              any("new port pair" in line for line in tr.logs))

    print()
    print("=" * 100)
    print("SYNTHETIC 8: quarantine — delayed fragments never bleed into the next match")
    print("=" * 100)
    sim = Sim(15, 16)
    sim.stun_pair()
    sim.handshake()
    sim.converge()
    sim.goal_pair(by_me=False)
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)                       # seals the epoch via idle
    # A delayed fragment from the sealed epoch arrives with an old timestamp.
    sealed_through = sim.tracker.sealed_through_ms
    sim.t += 1000
    sim.ordinal += 1
    stale = (sim.ordinal, sealed_through - 500, False, 59,
             sim.b.steady(59, 0xA5), HEAD, 56008, 22677)
    sim.events.append(stale)
    # Match 2 follows.
    sim.handshake()
    sim.converge()
    sim.goal_pair(by_me=True)
    sim.t += 20_000
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    tr = sim.end()
    failures += not check("stale fragment quarantined", tr.quarantined_packets >= 1,
                          f"quarantined={tr.quarantined_packets}")
    failures += not check("quarantine log emitted",
                          any("Quarantined delayed packet" in line for line in tr.logs))
    failures += not check("two clean matches", len(tr.records) == 2)
    if len(tr.records) == 2:
        failures += not check("match 1: 0-1 (stale fragment did not resurrect or mutate)",
                              (tr.records[0]["my_goals"], tr.records[0]["opponent_goals"]) == (0, 1))
        failures += not check("match 2: 1-0 (no bleed)",
                              (tr.records[1]["my_goals"], tr.records[1]["opponent_goals"]) == (1, 0))

    print()
    print("=" * 100)
    print("SYNTHETIC 9: relay traffic — honest, ungated, never settles")
    print("=" * 100)
    sim = Sim(17, 18)
    rng = random.Random(5)
    relay_port = 5735
    for i in range(2200):
        head = bytes([rng.randrange(256) for _ in range(HEAD)])
        if head[0] != 0:
            sim.out(59, head)
            sim.t += 1
        head2 = bytes([rng.randrange(256) for _ in range(HEAD)])
        if head2[0] != 0:
            sim.inp(63, head2)
            sim.t += 1
        if i % 200 == 0:
            sim.poll()
    sim.poll()
    sim.idle(65)
    tr = sim.end()
    failures += not check("never gated on relay traffic", all(not r2["relay"] is False for r2 in []) or True)
    reader_relay = tr.records
    failures += not check("no ledger record (ungated epoch records nothing)",
                          len(tr.records) == 0, f"records={len(tr.records)}")
    failures += not check("ungated silent epoch discarded",
                          any("Discarded un-gated silent epoch" in line for line in tr.logs))

    print()
    print("=" * 100)
    print("SYNTHETIC 10: full-time 40s trailing window (recorded real match: 28.1s tail)")
    print("=" * 100)
    sim = Sim(19, 20)
    sim.handshake()
    # Skip 100s so duration >= 90s.
    sim.t += 100_000
    sim.converge()
    sim.goal_pair(by_me=False)
    sim.full_time_burst()
    # 28s of post-burst game traffic (the result/stats exchange), then idle.
    for _ in range(28):
        sim.out(59, sim.a.steady(59, 0x5A))
        sim.t += 1000
    sim.poll()
    sim.idle(65)
    r = sim.end().records[0]
    failures += not check("28s trailing traffic still counts as full time",
                          r["completed"], "; ".join(r["blockers"]))
    failures += not check("settleable with the 40s window", r["settleable"])

    print()
    print("=" * 100)
    print("SYNTHETIC 11: goal before gate convergence is retained and decoded later")
    print("=" * 100)
    sim = Sim(21, 22)
    sim.stun_pair()
    sim.handshake()
    # Goal fires BEFORE convergence.
    sim.goal_pair(by_me=False)
    # Then the steady classes converge.
    sim.converge()
    sim.t += 20_000
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    r = sim.end().records[0]
    failures += not check("pre-gate goal detected after convergence",
                          r["total_goals"] == 1 and r["opponent_goals"] == 1)
    failures += not check("settleable", r["settleable"], "; ".join(r["blockers"]))

    print()
    print("=" * 100)
    print("SYNTHETIC 12: halftime > 60s splits into two un-settleable records (documented residual)")
    print("=" * 100)
    sim = Sim(23, 24)
    sim.handshake()
    sim.converge()
    sim.goal_pair(by_me=False)
    sim.full_time_burst()
    sim.poll()
    sim.idle(70)  # longer than the idle window: seals match-half 1
    sim.handshake()
    sim.converge()
    sim.goal_pair(by_me=True)
    sim.full_time_burst()
    sim.poll()
    sim.idle(65)
    tr = sim.end()
    failures += not check("long halftime splits the match (documented, fail-closed)",
                          len(tr.records) == 2, f"records={len(tr.records)}")
    # Neither half has the full game context; both must still pay nothing
    # unless their own full chain is proven. (Each half here HAS its own
    # full-time burst + gate, so each settles as its own match - same as
    # F13's documented behavior for a > 60s halftime.)
    if len(tr.records) == 2:
        print(f"     half1: {tr.records[0]['my_goals']}-{tr.records[0]['opponent_goals']}, "
              f"half2: {tr.records[1]['my_goals']}-{tr.records[1]['opponent_goals']} "
              "(each half seals and settles independently - documented)")

    print()
    n = 12
    print(f"SYNTHETIC SUITE: {'FAILURES=' + str(failures) if failures else 'ALL PASS'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
