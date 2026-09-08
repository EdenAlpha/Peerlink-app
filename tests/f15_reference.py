#!/usr/bin/env python3
"""f15_reference — a 1:1 Python transcription of the F15 Kotlin
MatchProtocolReader (merged F13 lifecycle + F14 goal-truth detector) plus the
native telemetry v4 simulation (snapshot filter, 64-byte head, sport/dport).

Used by f15_port_validation.py / f15_replay_real.py / f15_synthetic.py to
verify the exact behavior the app will exhibit on device, without an Android
toolchain.
"""

HEAD_SIZE = 64

# --- steady-state keystream classes (F14) ---
KS_CLASS_39 = 39
KS_CLASS_59 = 59
KS_CLASS_63 = 63
KS_MIN_PACKETS = 50
KS_VOTE_MIN_PERCENT = 95

# --- goal-family scan (F14) ---
GOAL_MIN_LEN = 300
GOAL_MAX_LEN = 2000
GOAL_SIG_MIN_VOTES = 14
SIG_POS_MIN = 19
SIG_POS_MAX = 36
PHASE_POS = 37

PHASE_ORIGINAL = 0xC6
PHASE_ECHO = 0xC7
PHASE_SYNC = 0xC4

# --- clustering / echo ---
CLUSTER_GAP_MS = 12_000.0
ECHO_WINDOW_MS = 12_000.0

# --- lifecycle (F13-v2 values on the F14 model) ---
IDLE_END_SILENCE_MS = 60_000.0
PROBE_LEN = 14
EXCHANGE_LEN_A = 266
EXCHANGE_LEN_B = 272
HANDSHAKE_WINDOW_MS = 10_000.0
FULL_TIME_CONTROL_LEN = 26
FULL_TIME_BURST_WINDOW_MS = 8_000.0
FULL_TIME_BURST_MIN_PER_DIRECTION = 20
FULL_TIME_MIN_DURATION_MS = 90_000.0
FULL_TIME_MAX_TRAILING_GAME_MS = 40_000.0
MIN_SETTLEMENT_PACKETS_PER_DIRECTION = 100
MIN_SETTLEMENT_DURATION_MS = 30_000.0
MAX_GOAL_CANDIDATES = 256
MAX_FROZEN_GOAL_MESSAGES = 512
RELAY_SUSPECT_MIN_GAME_PACKETS = 2000

STUN_MAGIC = b"\x21\x12\xa4\x42"
GOAL_SIG = [0x00] * 7 + [0x04, 0x74] + [0x04] * 5 + [0x54] + [0x06] * 3


# ---------------- native telemetry v4 simulation ----------------
def is_game_stun_payload(payload, length):
    return (payload is not None and length >= 20 and
            payload[4:8] == STUN_MAGIC)


def should_snapshot_match_payload(payload, length):
    if length in (39, 59, 63, 26, 14, 266, 272):
        return True
    if 300 <= length <= 2000:
        return True
    return is_game_stun_payload(payload, length)


class ClassVoter:
    def __init__(self, from_pos, to_pos, ref_pos):
        self.from_pos, self.to_pos, self.ref_pos = from_pos, to_pos, ref_pos
        self.counts = [[0] * 256 for _ in range(to_pos - from_pos + 1)]
        self.n = 0

    def observe(self, head):
        ref = head[self.ref_pos]
        for i in range(self.from_pos, self.to_pos + 1):
            self.counts[i - self.from_pos][head[i] ^ ref] += 1
        self.n += 1

    def mode(self, i):
        col = self.counts[i - self.from_pos]
        best_value, best_count = 0, -1
        for v in range(256):
            if col[v] > best_count:
                best_count, best_value = col[v], v
        return best_value, (best_count * 100 // self.n if self.n else 0)


class DirectionState:
    def __init__(self):
        self.c59 = ClassVoter(36, 58, 58)
        self.c39 = ClassVoter(19, 35, 36)
        self.c63 = ClassVoter(50, 62, 62)
        self.stun_ids = {}
        self.v = [0] * HEAD_SIZE
        self.known = [False] * HEAD_SIZE
        self.dirty = True

    def mark_dirty(self):
        self.dirty = True

    def rebuild_v(self):
        if not self.dirty:
            return
        self.known = [False] * HEAD_SIZE
        if self.c59.n >= KS_MIN_PACKETS:
            for i in range(36, 59):
                d, share = self.c59.mode(i)
                if share >= KS_VOTE_MIN_PERCENT:
                    self.v[i], self.known[i] = d, True
        if self.known[36] and self.c39.n >= KS_MIN_PACKETS:
            for i in range(19, 36):
                d, share = self.c39.mode(i)
                if share >= KS_VOTE_MIN_PERCENT:
                    self.v[i], self.known[i] = d ^ self.v[36], True
        if self.known[58] and self.c63.n >= KS_MIN_PACKETS:
            d58, _ = self.c63.mode(58)
            for i in range(50, 63):
                if i == 58:
                    continue
                d, share = self.c63.mode(i)
                if share >= KS_VOTE_MIN_PERCENT:
                    self.v[i], self.known[i] = d ^ d58 ^ self.v[58], True
        self.dirty = False

    def detection_ready(self):
        self.rebuild_v()
        return all(self.known[i] for i in range(SIG_POS_MIN, PHASE_POS + 1))

    def known_positions(self):
        self.rebuild_v()
        return sum(1 for i in range(SIG_POS_MIN, 63) if self.known[i])

    def modal_stun_id(self):
        if not self.stun_ids:
            return None
        return max(self.stun_ids.items(), key=lambda kv: kv[1])[0]

    def reset_voters(self):
        # Keep the STUN identity: players do not change across a reconnect.
        self.c59 = ClassVoter(36, 58, 58)
        self.c39 = ClassVoter(19, 35, 36)
        self.c63 = ClassVoter(50, 62, 62)
        self.v = [0] * HEAD_SIZE
        self.known = [False] * HEAD_SIZE
        self.dirty = True


def is_stun(head, head_len):
    return head_len >= 8 and bytes(head[4:8]) == STUN_MAGIC


def is_custom_stun(head, head_len):
    return (head_len >= 32 and is_stun(head, head_len) and
            head[0] in (0x08, 0x09) and head[1] in (0x0A, 0x0B, 0x0C))


class HandshakeEvidence:
    def __init__(self, probe_at_ms, probe_sent_by_me, exchange_at_ms,
                 exchange_sent_by_me, port_pair):
        self.probe_at_ms = probe_at_ms
        self.probe_sent_by_me = probe_sent_by_me
        self.exchange_at_ms = exchange_at_ms
        self.exchange_sent_by_me = exchange_sent_by_me
        self.port_pair = port_pair


class Reader:
    def __init__(self, started_at_ms=0.0, baseline_out=0, baseline_in=0,
                 baseline_drops=0, baseline_failures=0):
        self.started_at_ms = started_at_ms
        self.mine = DirectionState()
        self.theirs = DirectionState()
        self.last_probe_ms = 0.0
        self.last_probe_sent_by_me = False
        self.handshake = None
        self.epoch_port_pair = None
        self.rematch_port_pair = None
        self.last_game_ms = 0.0
        self.last_substantive_game_ms = 0.0
        self.full_time_burst_at_ms = 0.0
        self.full_time_control_a = []
        self.full_time_control_b = []
        self.latest_out = baseline_out
        self.latest_in = baseline_in
        self.latest_drops = baseline_drops
        self.latest_failures = baseline_failures
        self.goal_candidates = []       # raw, pending decode-freeze
        self.goal_messages = []         # frozen, keystream-independent
        self.protocol_decode_drops = 0
        self.reconnects = 0
        self.table_converged_at_ms = 0.0

    # ---------------- gate ----------------
    @property
    def gated(self):
        return self.mine.detection_ready() and self.theirs.detection_ready()

    @property
    def relay_suspected(self):
        return (not self.gated) and \
            (self.latest_out + self.latest_in) >= RELAY_SUSPECT_MIN_GAME_PACKETS

    @property
    def full_time_observed(self):
        return self.full_time_burst_at_ms > 0

    @property
    def game_port_pair(self):
        return self.epoch_port_pair

    @property
    def pending_rematch_port_pair(self):
        return self.rematch_port_pair

    def local_player_id(self):
        return self.mine.modal_stun_id()

    def remote_player_id(self):
        return self.theirs.modal_stun_id()

    def observe_snapshot(self, last_game_wall_ms, out_packets, in_packets,
                         telemetry_drops=0, parse_failures=0):
        if last_game_wall_ms > self.last_game_ms:
            self.last_game_ms = last_game_wall_ms
        self.latest_out = max(self.latest_out, out_packets)
        self.latest_in = max(self.latest_in, in_packets)
        self.latest_drops = max(self.latest_drops, telemetry_drops)
        self.latest_failures = max(self.latest_failures, parse_failures)

    # ---------------- packet path (1:1 with Kotlin onPacket) ----------------
    def on_packet(self, t_ms, sent_by_me, payload_len, head, head_len,
                  sport=0, dport=0):
        if payload_len < 8 or head_len < 8 or head_len > len(head):
            return None
        stun = is_stun(head, head_len)
        if not stun and payload_len == PROBE_LEN:
            self.last_probe_ms = t_ms
            self.last_probe_sent_by_me = sent_by_me
        elif not stun and payload_len in (EXCHANGE_LEN_A, EXCHANGE_LEN_B):
            if self.last_probe_ms > 0 and 0 <= t_ms - self.last_probe_ms <= HANDSHAKE_WINDOW_MS:
                port_pair = self._port_pair_of(sport, dport)
                if self.handshake is None:
                    self.handshake = HandshakeEvidence(
                        self.last_probe_ms, self.last_probe_sent_by_me,
                        t_ms, sent_by_me, port_pair)
                    if self.epoch_port_pair is None and port_pair is not None:
                        self.epoch_port_pair = port_pair
                elif self._no_goal_activity_since(self.last_probe_ms - ECHO_WINDOW_MS):
                    self.rematch_port_pair = port_pair or self.epoch_port_pair
                    return "REMATCH"
        if stun:
            if is_custom_stun(head, head_len):
                self._observe_stun_identity(sent_by_me, head, head_len)
            return None
        if t_ms > self.last_game_ms:
            self.last_game_ms = t_ms
        if payload_len not in (PROBE_LEN, EXCHANGE_LEN_A, EXCHANGE_LEN_B):
            if t_ms > self.last_substantive_game_ms:
                self.last_substantive_game_ms = t_ms
            if self.epoch_port_pair is None:
                self.epoch_port_pair = self._port_pair_of(sport, dport)

        # Steady-state keystream classes (game format: b0 == 0).
        if head_len >= 20 and head[0] == 0:
            direction = self.mine if sent_by_me else self.theirs
            if payload_len == KS_CLASS_59 and head_len > 58:
                direction.c59.observe(head)
                direction.mark_dirty()
                self._check_convergence(t_ms)
            elif payload_len == KS_CLASS_39 and head_len > 36:
                direction.c39.observe(head)
                direction.mark_dirty()
                self._check_convergence(t_ms)
            elif payload_len == KS_CLASS_63 and head_len > 62:
                direction.c63.observe(head)
                direction.mark_dirty()
                self._check_convergence(t_ms)

        if payload_len == FULL_TIME_CONTROL_LEN:
            self._observe_full_time_control(t_ms, sent_by_me)

        if (GOAL_MIN_LEN <= payload_len <= GOAL_MAX_LEN and
                head_len > PHASE_POS and head[0] == 0):
            if len(self.goal_candidates) < MAX_GOAL_CANDIDATES:
                self.goal_candidates.append(
                    (t_ms, sent_by_me, payload_len, bytes(head[:head_len])))
                if self.gated:
                    self._flush_pending_goals()
            else:
                self.protocol_decode_drops += 1
        return None

    def _check_convergence(self, t_ms):
        if self.table_converged_at_ms == 0.0 and self.gated:
            self.table_converged_at_ms = t_ms
            self._flush_pending_goals()

    def _flush_pending_goals(self):
        if not self.goal_candidates:
            return
        for candidate in self.goal_candidates:
            message = self._decode_goal_candidate(candidate)
            if message is not None:
                if len(self.goal_messages) < MAX_FROZEN_GOAL_MESSAGES:
                    self.goal_messages.append(message)
                else:
                    self.protocol_decode_drops += 1
        self.goal_candidates = []

    def on_reconnect(self):
        self._flush_pending_goals()
        self.mine.reset_voters()
        self.theirs.reset_voters()
        self.table_converged_at_ms = 0.0
        self.full_time_burst_at_ms = 0.0
        self.full_time_control_a = []
        self.full_time_control_b = []
        self.reconnects += 1

    def _port_pair_of(self, sport, dport):
        if sport > 0 and dport > 0:
            return (min(sport, dport), max(sport, dport))
        return None

    def _no_goal_activity_since(self, since_ms):
        return not any(m[0] >= since_ms for m in self.goal_messages) and \
            not any(c[0] >= since_ms for c in self.goal_candidates)

    def _observe_stun_identity(self, sent_by_me, head, head_len):
        direction = self.mine if sent_by_me else self.theirs
        i = 0
        while i <= head_len - 10:
            seg = head[i:i + 10]
            if all(0x30 <= b <= 0x39 for b in seg) and \
               (i == 0 or not (0x30 <= head[i - 1] <= 0x39)) and \
               (i + 10 >= head_len or not (0x30 <= head[i + 10] <= 0x39)):
                key = seg.decode()
                direction.stun_ids[key] = direction.stun_ids.get(key, 0) + 1
                i += 10
            else:
                i += 1

    def _decode_goal_candidate(self, candidate):
        t_ms, sent_by_me, payload_len, head = candidate
        direction = self.mine if sent_by_me else self.theirs
        direction.rebuild_v()
        votes = [0] * 256
        n = 0
        for k, sig in enumerate(GOAL_SIG):
            pos = SIG_POS_MIN + k
            if pos < len(head) and direction.known[pos]:
                votes[head[pos] ^ direction.v[pos] ^ sig] += 1
                n += 1
        if n < GOAL_SIG_MIN_VOTES:
            return None
        best_x, best_count = 0, -1
        for v in range(256):
            if votes[v] > best_count:
                best_count, best_x = votes[v], v
        if best_count < GOAL_SIG_MIN_VOTES:
            return None
        if direction.known[PHASE_POS] and len(head) > PHASE_POS:
            phase = head[PHASE_POS] ^ direction.v[PHASE_POS] ^ best_x
        else:
            phase = -1
        return (t_ms, sent_by_me, payload_len, phase, best_count, n)

    # ---------------- lifecycle ----------------
    def game_silent_for_ms(self, now_ms):
        if self.last_game_ms <= 0:
            return 0.0
        return max(0.0, now_ms - self.last_game_ms)

    def check_idle_end(self, now_ms):
        return self.gated and self.game_silent_for_ms(now_ms) >= IDLE_END_SILENCE_MS

    def _observe_full_time_control(self, t_ms, sent_by_me):
        deque_ = self.full_time_control_a if sent_by_me else self.full_time_control_b
        deque_.append(t_ms)
        cutoff = t_ms - FULL_TIME_BURST_WINDOW_MS
        self.full_time_control_a = [x for x in self.full_time_control_a if x >= cutoff]
        self.full_time_control_b = [x for x in self.full_time_control_b if x >= cutoff]
        if (t_ms - self.started_at_ms >= FULL_TIME_MIN_DURATION_MS and
                len(self.full_time_control_a) >= FULL_TIME_BURST_MIN_PER_DIRECTION and
                len(self.full_time_control_b) >= FULL_TIME_BURST_MIN_PER_DIRECTION):
            self.full_time_burst_at_ms = t_ms

    def _has_full_time_evidence(self):
        if self.full_time_burst_at_ms <= 0 or self.last_substantive_game_ms < self.full_time_burst_at_ms:
            return False
        return (self.last_substantive_game_ms - self.full_time_burst_at_ms
                <= FULL_TIME_MAX_TRAILING_GAME_MS)

    # ---------------- goals ----------------
    def goal_events(self):
        if not self.gated and not self.goal_messages:
            return []
        messages = sorted(self.goal_messages, key=lambda m: m[0])
        clusters = []
        for m in messages:
            if clusters and m[0] - clusters[-1][-1][0] < CLUSTER_GAP_MS:
                clusters[-1].append(m)
            else:
                clusters.append([m])
        events = []
        for cluster in clusters:
            originals = [m for m in cluster if m[3] == PHASE_ORIGINAL]
            fallback = not originals
            chosen = [cluster[0]] if fallback else originals
            for original in chosen:
                echo = next((m for m in cluster if m[1] != original[1] and
                             0 <= (m[0] - original[0]) <= ECHO_WINDOW_MS), None)
                scorer_id = (self.mine if original[1] else self.theirs).modal_stun_id()
                events.append(dict(
                    t=original[0],
                    scorer_is_me=original[1],
                    payload_len=original[2],
                    n_messages=len(cluster),
                    corroborated=echo is not None,
                    echo_after_ms=(echo[0] - original[0]) if echo else None,
                    phase=original[3],
                    phase_confirmed=not fallback,
                    scorer_player_id=scorer_id,
                    reason=(None if not fallback else
                            "goal cluster had no decodable 0xC6 original - "
                            "scorer unknown, settlement blocked"),
                ))
        return events

    def segment_result(self, ended_by, completed, ended_at_ms):
        events = self.goal_events()
        resolved = [e for e in events if e["phase_confirmed"]]
        unresolved = len(events) - len(resolved)
        outgoing = max(0, self.latest_out)
        incoming = max(0, self.latest_in)
        telemetry_drops = 0
        parse_failures = 0
        duration = max(0.0, ended_at_ms - self.started_at_ms)
        completion_proven = completed and self._has_full_time_evidence()
        relay = self.relay_suspected
        blockers = []
        if not self.gated:
            blockers.append(
                "relay-mode traffic suspected — keystream did not converge, attribution unavailable"
                if relay else "keystream fingerprint did not converge")
        if self.handshake is None:
            blockers.append("match start was not proven (no probe/exchange handshake in this epoch)")
        if unresolved > 0:
            blockers.append(f"{unresolved} goal(s) had no confirmed 0xC6 original")
        if not completion_proven:
            blockers.append("full-time control sequence and a terminal boundary were not both proven")
        if any(not e["corroborated"] for e in events):
            blockers.append("one or more goal events lacked the peer echo")
        if telemetry_drops > 0:
            blockers.append(f"{telemetry_drops} score-telemetry snapshot(s) were dropped")
        if parse_failures > 0:
            blockers.append(f"{parse_failures} score-telemetry poll(s) were malformed")
        if self.protocol_decode_drops > 0:
            blockers.append(f"{self.protocol_decode_drops} goal-candidate head(s) exceeded the bounded decoder queue")
        if outgoing < MIN_SETTLEMENT_PACKETS_PER_DIRECTION or \
                incoming < MIN_SETTLEMENT_PACKETS_PER_DIRECTION:
            blockers.append("insufficient bidirectional match traffic")
        if duration < MIN_SETTLEMENT_DURATION_MS:
            blockers.append("match segment was too short")
        return dict(
            gated=self.gated,
            table_converged_at_ms=self.table_converged_at_ms,
            handshake=self.handshake,
            events=events,
            my_goals=sum(1 for e in resolved if e["scorer_is_me"]),
            opponent_goals=sum(1 for e in resolved if not e["scorer_is_me"]),
            total_goals=len(events),
            ended_by=ended_by,
            completed=completion_proven,
            settleable=not blockers,
            settlement_blockers=blockers,
            outgoing_packets=outgoing,
            incoming_packets=incoming,
            telemetry_drops=telemetry_drops,
            telemetry_parse_failures=parse_failures,
            protocol_decode_drops=self.protocol_decode_drops,
            duration_ms=duration,
            reconnects=self.reconnects,
            port_pair=self.epoch_port_pair,
            keystream_coverage=min(self.mine.known_positions(), self.theirs.known_positions()),
            relay_suspected=relay,
            local_player_id=self.mine.modal_stun_id(),
            remote_player_id=self.theirs.modal_stun_id(),
        )


# ---------------- MatchTracker port (F15 epoch architecture) ----------------
RECONNECT_MERGE_MAX_GAP_MS = 45_000.0


class Tracker:
    """Port of the F15 MatchTracker lifecycle (F13-v2 epoch architecture fed
    by the F14 goal-truth reader), with explicit clocks for replay."""

    def __init__(self):
        self.records = []
        self.logs = []
        self.session_active = False
        self.reader = None
        self.epoch_ordinal = -1
        self.epoch_start_ms = 0.0
        self.epoch_sealed = False
        self.sealed_through_ms = 0.0
        self.quarantined_packets = 0
        self.out_total = 0
        self.in_total = 0
        self.my_goals = 0
        self.opponent_goals = 0
        self.total_goals = 0
        self.last_activity_ms = 0.0

    def log(self, message):
        self.logs.append(message)

    def begin_session(self):
        self.session_active = True
        self.epoch_ordinal = -1
        self.reader = None
        self.epoch_sealed = False
        self.quarantined_packets = 0
        self._reset_score()

    def on_telemetry(self, events, wall_now, last_game_wall_ms=None):
        """events: list of (ordinal, ts_ms, sent_by_me, payload_len, head,
        head_len, sport, dport) - exactly what NativePeerLinkBackend emits."""
        if not self.session_active:
            return
        events = sorted(events, key=lambda e: e[0])
        if events:
            self.out_total += sum(1 for e in events if e[2])
            self.in_total += sum(1 for e in events if not e[2])
        batch_out = self.out_total
        batch_in = self.in_total
        for ordinal, ts_ms, sent_by_me, payload_len, head, head_len, sport, dport in events:
            packet_wall = ts_ms
            if self.epoch_sealed and packet_wall <= self.sealed_through_ms:
                if self.quarantined_packets < 16 or self.quarantined_packets % 100 == 0:
                    self.log("[MATCH] Quarantined delayed packet (t=%dms, len=%d) from a sealed epoch"
                             % (ts_ms, payload_len))
                self.quarantined_packets += 1
                continue
            current = self.reader or self._new_epoch(packet_wall)
            boundary = current.on_packet(packet_wall, sent_by_me, payload_len,
                                         head, head_len, sport, dport)
            if boundary == "REMATCH":
                last_game_age = current.game_silent_for_ms(wall_now)
                epoch_ports = current.game_port_pair
                rematch_ports = current.pending_rematch_port_pair
                port_changed = (epoch_ports is not None and rematch_ports is not None
                                and epoch_ports != rematch_ports)
                is_reconnect = (not port_changed and current.gated
                                and not current.full_time_observed
                                and last_game_age <= RECONNECT_MERGE_MAX_GAP_MS)
                if port_changed:
                    self.log("[MATCH] Second handshake on a new port pair (%s<->%s vs %s<->%s) - "
                             "sealing epoch, starting a new match"
                             % (rematch_ports[0], rematch_ports[1],
                                epoch_ports[0], epoch_ports[1]))
                if is_reconnect:
                    current.on_reconnect()
                    self.log("[MATCH] Reconnect handshake - same match continues "
                             "(goals %d total, keystream re-learned)" % self.total_goals)
                else:
                    current.observe_snapshot(
                        last_game_wall_ms if last_game_wall_ms else 0.0, batch_out, batch_in)
                    self._finalize_epoch(
                        current.segment_result("boundary", True, packet_wall), packet_wall)
                    self._new_epoch(packet_wall, batch_out, batch_in)
                    current = self.reader
                    if current is None:
                        continue
                    current.on_packet(packet_wall, sent_by_me, payload_len,
                                     head, head_len, sport, dport)
        current = self.reader
        if current is not None and not self.epoch_sealed:
            last_game_wall = last_game_wall_ms if last_game_wall_ms is not None else (
                max((e[1] for e in events), default=0.0))
            current.observe_snapshot(last_game_wall, batch_out, batch_in)
            self._update_live_score(current)
            if current.check_idle_end(wall_now):
                self._finalize_epoch(current.segment_result("idle", True, wall_now), wall_now)
                self.reader = None
                self.epoch_start_ms = 0.0
                self.epoch_sealed = True
                self.sealed_through_ms = wall_now
            elif (not current.gated and
                  current.game_silent_for_ms(wall_now) >= IDLE_END_SILENCE_MS):
                # Un-gated and silent for the full idle window: proves nothing.
                # Drop it so the next match cannot absorb into it (measured on
                # the 1-Sep trace A: match 2 opened 4 minutes after match 1's
                # tail went quiet).
                self.log("[MATCH] Discarded un-gated silent epoch "
                         "(no fingerprint, no handshake, nothing recorded)")
                self.reader = None
                self.epoch_start_ms = 0.0
                self.epoch_sealed = True
                self.sealed_through_ms = wall_now
        self.last_activity_ms = wall_now

    def end_session(self, reason):
        if not self.session_active:
            return
        now = self.last_activity_ms
        if self.reader is not None and not self.epoch_sealed:
            self._finalize_epoch(self.reader.segment_result("disconnect", False, now), now)
        self.session_active = False
        self.reader = None
        self.log("[MATCH] Session ended (%s)" % reason)

    def _new_epoch(self, started_at_ms, baseline_out=None, baseline_in=None):
        self.epoch_start_ms = started_at_ms
        self.epoch_sealed = False
        self.sealed_through_ms = 0.0
        self.epoch_ordinal += 1
        self._reset_score()
        self.reader = Reader(
            started_at_ms,
            baseline_out if baseline_out is not None else self.out_total,
            baseline_in if baseline_in is not None else self.in_total)
        self.log("[MATCH] Match epoch %d started at %d" % (self.epoch_ordinal, started_at_ms))
        return self.reader

    def _update_live_score(self, current):
        events = current.goal_events()
        mine = sum(1 for e in events if e["scorer_is_me"] and e["phase_confirmed"])
        theirs = sum(1 for e in events if (not e["scorer_is_me"]) and e["phase_confirmed"])
        if len(events) != self.total_goals or mine != self.my_goals or theirs != self.opponent_goals:
            latest = events[-1]
            if not latest["phase_confirmed"]:
                scorer = "scorer UNRESOLVED (%s) - settlement blocked" % (latest["reason"] or "?")
            elif latest["scorer_is_me"]:
                scorer = "YOU scored" + (
                    " (player %s)" % latest["scorer_player_id"] if latest["scorer_player_id"] else "")
            else:
                scorer = "PEER scored" + (
                    " (player %s)" % latest["scorer_player_id"] if latest["scorer_player_id"] else "")
            detail = ("phase byte NOT confirmed (holding settlement)"
                      if not latest["phase_confirmed"] else
                      ("0xC6 original confirmed, peer echo in %sms" % latest["echo_after_ms"]
                       if latest["corroborated"] else
                       "0xC6 original confirmed, awaiting peer echo"))
            self.log("[MATCH] Goal #%d: %d message(s); %s; %s"
                     % (len(events), latest["n_messages"], scorer, detail))
        self.my_goals = mine
        self.opponent_goals = theirs
        self.total_goals = len(events)

    def _finalize_epoch(self, result, ended_at_ms):
        if not result["gated"] or self.epoch_start_ms <= 0:
            return
        if self.epoch_sealed:
            return
        if result["events"] and all(e["phase_confirmed"] for e in result["events"]):
            attribution = "0xC6_ORIGINAL"
        elif result["relay_suspected"] and not result["gated"]:
            attribution = "RELAY_UNGATED"
        else:
            attribution = "UNCONFIRMED_PHASE"
        record = {
            "ordinal": self.epoch_ordinal,
            "my_goals": result["my_goals"],
            "opponent_goals": result["opponent_goals"],
            "total_goals": result["total_goals"],
            "ended_by": result["ended_by"],
            "completed": result["completed"],
            "settleable": result["settleable"],
            "blockers": result["settlement_blockers"],
            "ended_at_ms": ended_at_ms,
            "started_at_ms": self.epoch_start_ms,
            "reconnects": result["reconnects"],
            "port_pair": result["port_pair"],
            "handshake": result["handshake"],
            "relay": result["relay_suspected"],
            "local_player_id": result["local_player_id"],
            "remote_player_id": result["remote_player_id"],
            "quarantined_packets": self.quarantined_packets,
            "attribution": attribution,
            "goals": [dict(t=e["t"], scorer=("ME" if e["scorer_is_me"] else "PEER"),
                           phase=e["phase"], phase_confirmed=e["phase_confirmed"],
                           corroborated=e["corroborated"],
                           player=e["scorer_player_id"]) for e in result["events"]],
        }
        self.records.append(record)
        self.epoch_sealed = True
        self.sealed_through_ms = max(self.sealed_through_ms, ended_at_ms)
        if record["reconnects"] > 0:
            self.log("[MATCH] Epoch survived %d reconnect(s) with the score preserved"
                     % record["reconnects"])
        if record["local_player_id"] and record["remote_player_id"]:
            self.log("[MATCH] Player IDs: local=%s remote=%s (STUN 0x9090)"
                     % (record["local_player_id"], record["remote_player_id"]))
        note = "; ".join(record["blockers"]) if record["blockers"] else None
        self.log("[MATCH] FINAL %d-%d of %d goal(s); %s"
                 % (record["my_goals"], record["opponent_goals"], record["total_goals"],
                    ("settled" if record["settleable"] else "UNVERIFIED - no PeerCoins (%s)" % note)))

    def _reset_score(self):
        self.my_goals = 0
        self.opponent_goals = 0
        self.total_goals = 0
