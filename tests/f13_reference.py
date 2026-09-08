#!/usr/bin/env python3
"""F13-v2 test reference: a faithful Python port of the Kotlin
MatchProtocolReader.Reader + the MatchTracker lifecycle, plus a synthetic
eFootball wire generator that encrypts goal messages with the REAL measured
structure (per-session tables, per-message key, duplicated pairs, GOAL_TAIL).

The port mirrors the Kotlin logic line-for-line so that replay results on this
reference are meaningful for the shipped implementation. Where the Kotlin uses
Android clocks, the port takes explicit timestamps.

Labels used by the suites built on this module:
  - REAL EVIDENCE tests: replay actual PCAPs/traces captured from the phones.
  - CONSISTENCY tests: synthetic streams; they prove the implementation is
    self-consistent and fail-closed, NOT that the protocol semantics are right.
"""
import collections
import random
import struct

TABLE_SIZE = 51
GATE_LEN59_MIN = 40
MIN_GOAL_PAYLOAD = 200
CLUSTER_GAP_MS = 10_000
ECHO_WINDOW_MS = 12_000
IDLE_END_SILENCE_MS = 60_000
PROBE_LEN = 14
EXCHANGE_LEN_A = 266
EXCHANGE_LEN_B = 272
HANDSHAKE_WINDOW_MS = 10_000
FULL_TIME_CONTROL_LEN = 26
FULL_TIME_BURST_WINDOW_MS = 8_000
FULL_TIME_BURST_MIN_PER_DIRECTION = 20
FULL_TIME_MIN_DURATION_MS = 90_000
# Measured post-burst trailing game traffic: 3.1s (28_Jun), ~0s (29_Jan),
# 28.1s (recorded 1-Sep match 1). v1's 10s rejected the only real completed
# match we hold; 40s covers the measured maximum with margin.
FULL_TIME_MAX_TRAILING_GAME_MS = 40_000
MIN_SETTLEMENT_PACKETS_PER_DIRECTION = 100
MIN_SETTLEMENT_DURATION_MS = 30_000
MAX_PRE_GATE_EVENT_HEADS = 512
MAX_CAPTURED_HEAD = 60
RECONNECT_MERGE_MAX_GAP_MS = 45_000

# F15: a bidirectional len-54/55 teardown cluster was measured on every
# match end (2 of 3 matches cut off mid-game on 3-Sep). The len-26 full-time
# control burst and the 54s inter-match silence are the protocol's own
# teardown markers.
STUN_MAGIC = bytes([0x21, 0x12, 0xA4, 0x42])
GOAL_TAIL = bytes([0x90, 0x90, 0x0E, 0x0E, 0x8E, 0x2E, 0x8E, 0x2E])


def is_stun(head):
    return len(head) >= 8 and bytes(head[4:8]) == STUN_MAGIC


def mode_table(counts):
    return [max(range(256), key=lambda v: counts[j][v]) for j in range(TABLE_SIZE)]


class Calibration:
    """Mirror of MatchProtocolReader.AttributionCalibration."""

    def __init__(self, score_mask, probe_sender_score_index, validated=True):
        self.score_mask = score_mask
        self.probe_sender_score_index = probe_sender_score_index
        self.validated = validated


class GoalFields:
    def __init__(self, score_a, score_b, tag_a, tag_b, post_tail, v_group):
        self.score_a = score_a
        self.score_b = score_b
        self.tag_a = tag_a
        self.tag_b = tag_b
        self.post_tail = list(post_tail)
        self.v_group = list(v_group)

    def __eq__(self, other):
        return isinstance(other, GoalFields) and self.__dict__ == other.__dict__

    def same_attribution_fields_as(self, other):
        """Only the attribution-relevant candidates. postTail/vGroup are
        per-direction session values in the real captures (28_Jun OUT:
        8c 8c 98..., IN: 8c 84 d2...) and must never flag a disagreement."""
        return (self.score_a == other.score_a and self.score_b == other.score_b and
                self.tag_a == other.tag_a and self.tag_b == other.tag_b)

    def describe(self):
        return ("score=%02x/%02x tag=%02x/%02x postTail=%s vGroup=%s" % (
            self.score_a, self.score_b, self.tag_a, self.tag_b,
            "".join("%02x" % b for b in self.post_tail),
            "".join("%02x" % b for b in self.v_group),
        ))


class GoalEvent:
    def __init__(self, t_ms, first_sent_by_me, sender_role, payload_len, n_messages,
                 corroborated, echo_after_ms, fields, fields_disagree,
                 scorer_side, attribution, reason):
        self.t_ms = t_ms
        self.first_sent_by_me = first_sent_by_me
        self.sender_role = sender_role
        self.payload_len = payload_len
        self.n_messages = n_messages
        self.corroborated = corroborated
        self.echo_after_ms = echo_after_ms
        self.fields = fields
        self.fields_disagree = fields_disagree
        self.scorer_side = scorer_side  # 'UNRESOLVED' | 'ME' | 'PEER'
        self.attribution = attribution  # 'UNPROVEN' | 'CALIBRATED'
        self.reason = reason


class HandshakeEvidence:
    def __init__(self, probe_at_ms, probe_sent_by_me, exchange_at_ms, exchange_sent_by_me, port_pair):
        self.probe_at_ms = probe_at_ms
        self.probe_sent_by_me = probe_sent_by_me
        self.exchange_at_ms = exchange_at_ms
        self.exchange_sent_by_me = exchange_sent_by_me
        self.port_pair = port_pair
        # NOTE: no scorerIsMe. The v1 field (exchange responder = scorer) was
        # confounded and is deliberately absent from this port.


class SegmentResult:
    pass


class Reader:
    """Port of MatchProtocolReader.Reader (F13-v2)."""

    def __init__(self, started_at_ms, baseline_out=0, baseline_in=0,
                 baseline_drops=0, baseline_failures=0, calibration=None):
        self.started_at_ms = started_at_ms
        self.baseline_out = baseline_out
        self.baseline_in = baseline_in
        self.baseline_drops = baseline_drops
        self.baseline_failures = baseline_failures
        self.calibration = calibration
        self.a_counts = [collections.Counter() for _ in range(TABLE_SIZE)]
        self.b_counts = [collections.Counter() for _ in range(TABLE_SIZE)]
        self.n59a = 0
        self.n59b = 0
        self.tables = None
        self.table_converged_at_ms = 0
        self.last_probe_ms = 0
        self.last_probe_sent_by_me = False
        self.handshake = None
        self.epoch_port_pair = None
        self.rematch_port_pair = None
        self.last_game_ms = 0
        self.last_substantive_game_ms = 0
        self.full_time_burst_at_ms = 0
        self.full_time_a = collections.deque()
        self.full_time_b = collections.deque()
        self.latest_out = baseline_out
        self.latest_in = baseline_in
        self.latest_drops = baseline_drops
        self.latest_failures = baseline_failures
        self.goal_messages = []
        self.pre_gate_events = []
        self.protocol_decode_drops = 0
        self.reconnects = 0
        self.stun_ids_mine = {}
        self.stun_ids_theirs = {}

    @property
    def gated(self):
        return self.tables is not None

    @property
    def full_time_observed(self):
        return self.full_time_burst_at_ms > 0

    @property
    def game_port_pair(self):
        return self.epoch_port_pair

    @property
    def pending_rematch_port_pair(self):
        return self.rematch_port_pair

    def observe_snapshot(self, last_game_wall_ms, out_packets, in_packets, drops, failures):
        if last_game_wall_ms > self.last_game_ms:
            self.last_game_ms = last_game_wall_ms
        self.latest_out = max(self.latest_out, out_packets)
        self.latest_in = max(self.latest_in, in_packets)
        self.latest_drops = max(self.latest_drops, drops)
        self.latest_failures = max(self.latest_failures, failures)

    def _port_pair_of(self, sport, dport):
        # A UDP flow is unordered: normalize so (sport, dport) and
        # (dport, sport) of the same flow can never masquerade as a port
        # change.
        if sport > 0 and dport > 0:
            return (min(sport, dport), max(sport, dport))
        return None

    def _no_goal_family_since(self, since_ms):
        return (not any(m[0] >= since_ms for m in self.goal_messages) and
                not any(e[0] >= since_ms for e in self.pre_gate_events))

    def on_packet(self, t_ms, sent_by_me, payload_len, head, head_len, sport=0, dport=0):
        head = bytes(head[:head_len]) if head is not None else b""
        if payload_len < 8 or head_len < 8 or head_len > len(head):
            return None
        stun = is_stun(head)
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
                elif self._no_goal_family_since(self.last_probe_ms - ECHO_WINDOW_MS):
                    self.rematch_port_pair = port_pair or self.epoch_port_pair
                    return "REMATCH"
        if stun:
            self._observe_stun_identity(sent_by_me, head, head_len)
            return None
        if t_ms > self.last_game_ms:
            self.last_game_ms = t_ms
        if payload_len not in (PROBE_LEN, EXCHANGE_LEN_A, EXCHANGE_LEN_B):
            if t_ms > self.last_substantive_game_ms:
                self.last_substantive_game_ms = t_ms
            if self.epoch_port_pair is None:
                self.epoch_port_pair = self._port_pair_of(sport, dport)

        if self.tables is None:
            if payload_len == 59 and head_len >= 8 + TABLE_SIZE:
                counts = self.a_counts if sent_by_me else self.b_counts
                for j in range(TABLE_SIZE):
                    counts[j][head[8 + j]] += 1
                if sent_by_me:
                    self.n59a += 1
                else:
                    self.n59b += 1
                if self.n59a >= GATE_LEN59_MIN and self.n59b >= GATE_LEN59_MIN:
                    self.tables = (mode_table(self.a_counts), mode_table(self.b_counts))
                    self.table_converged_at_ms = t_ms
                    for pending in self.pre_gate_events:
                        self._decode_potential_goal(*pending)
                    self.pre_gate_events = []
            elif payload_len >= MIN_GOAL_PAYLOAD and head_len >= 34:
                if len(self.pre_gate_events) < MAX_PRE_GATE_EVENT_HEADS:
                    self.pre_gate_events.append(
                        (t_ms, sent_by_me, payload_len, head[:MAX_CAPTURED_HEAD],
                         min(head_len, MAX_CAPTURED_HEAD)))
                else:
                    self.protocol_decode_drops += 1
            return None

        if payload_len == FULL_TIME_CONTROL_LEN:
            self._observe_full_time_control(t_ms, sent_by_me)
        self._decode_potential_goal(t_ms, sent_by_me, payload_len, head, head_len)
        return None

    def on_reconnect(self):
        self.a_counts = [collections.Counter() for _ in range(TABLE_SIZE)]
        self.b_counts = [collections.Counter() for _ in range(TABLE_SIZE)]
        self.n59a = 0
        self.n59b = 0
        self.tables = None
        self.table_converged_at_ms = 0
        self.full_time_burst_at_ms = 0
        self.full_time_a.clear()
        self.full_time_b.clear()
        self.reconnects += 1

    def _observe_full_time_control(self, t_ms, sent_by_me):
        mine = self.full_time_a if sent_by_me else self.full_time_b
        mine.append(t_ms)
        cutoff = t_ms - FULL_TIME_BURST_WINDOW_MS
        while self.full_time_a and self.full_time_a[0] < cutoff:
            self.full_time_a.popleft()
        while self.full_time_b and self.full_time_b[0] < cutoff:
            self.full_time_b.popleft()
        if (t_ms - self.started_at_ms >= FULL_TIME_MIN_DURATION_MS and
                len(self.full_time_a) >= FULL_TIME_BURST_MIN_PER_DIRECTION and
                len(self.full_time_b) >= FULL_TIME_BURST_MIN_PER_DIRECTION):
            self.full_time_burst_at_ms = t_ms

    def _has_full_time_evidence(self):
        if self.full_time_burst_at_ms <= 0 or self.last_substantive_game_ms < self.full_time_burst_at_ms:
            return False
        return self.last_substantive_game_ms - self.full_time_burst_at_ms <= FULL_TIME_MAX_TRAILING_GAME_MS

    def _decode_potential_goal(self, t_ms, sent_by_me, payload_len, head, head_len):
        table_a, table_b = self.tables
        if head_len < 34 or head_len > len(head):
            return
        table = table_a if sent_by_me else table_b
        for role in (1, 2):
            message_key = head[19] ^ table[11] ^ role
            decoded_len = TABLE_SIZE if head_len >= 8 + TABLE_SIZE else 26
            decoded = [(head[8 + j] ^ table[j] ^ message_key) for j in range(decoded_len)]
            structure = (decoded[6] == decoded[7] and decoded[8] == decoded[9] and
                         decoded[12] == decoded[13] and decoded[14] == decoded[15] and
                         decoded[15] == decoded[16])
            if not structure or payload_len < MIN_GOAL_PAYLOAD:
                continue
            if bytes(decoded[18:26]) != GOAL_TAIL:
                continue
            self.goal_messages.append(
                (t_ms, sent_by_me, role, payload_len, decoded, head[:MAX_CAPTURED_HEAD]))
            break

    def game_silent_for_ms(self, now_ms):
        if self.last_game_ms <= 0:
            return 0
        return max(0, now_ms - self.last_game_ms)

    def check_idle_end(self, now_ms):
        return self.gated and self.game_silent_for_ms(now_ms) >= IDLE_END_SILENCE_MS

    def _fields_of(self, message):
        d = message[4]
        if len(d) < 26:
            return None
        return GoalFields(
            d[6], d[8], d[12], d[14],
            d[26:30] if len(d) >= 30 else [],
            d[30:34] if len(d) >= 34 else [],
        )

    def _resolve_scorers(self, cluster_fields, disagreeing):
        hs = self.handshake
        cal = self.calibration
        out = []
        for index, fields in enumerate(cluster_fields):
            side, reason = None, None
            if cal is None or not cal.validated:
                reason = "attribution is not calibrated (record a match where both players score)"
            elif index in disagreeing:
                reason = "the two directions disagreed on the goal-summary fields"
            elif fields is None:
                reason = "the goal-summary head was too short to decode the field values"
            elif hs is None:
                reason = "session roles were not observed (no probe/exchange handshake)"
            else:
                score_a = fields.score_a ^ cal.score_mask
                score_b = fields.score_b ^ cal.score_mask
                if not (0 <= score_a <= 15 and 0 <= score_b <= 15):
                    reason = "field values %02x/%02x are outside the calibrated range" % (
                        fields.score_a, fields.score_b)
                else:
                    prev_a, prev_b = 0, 0
                    bad = None
                    for earlier in range(index):
                        f = cluster_fields[earlier]
                        if f is None:
                            bad = "an earlier goal's fields were unreadable, so the score progression is unknown"
                            break
                        if earlier in disagreeing:
                            bad = "an earlier goal's directions disagreed, so the score progression is unknown"
                            break
                        prev_a = f.score_a ^ cal.score_mask
                        prev_b = f.score_b ^ cal.score_mask
                    if bad:
                        reason = bad
                    else:
                        delta_a = score_a - prev_a
                        delta_b = score_b - prev_b
                        scorer_is_side_a = None
                        if delta_a == 1 and delta_b == 0:
                            scorer_is_side_a = True
                        elif delta_a == 0 and delta_b == 1:
                            scorer_is_side_a = False
                        if scorer_is_side_a is None:
                            reason = ("field values %02x/%02x do not continue a valid score progression"
                                      % (fields.score_a, fields.score_b))
                        else:
                            scorer_is_probe_sender = (cal.probe_sender_score_index == 0
                                                      if scorer_is_side_a
                                                      else cal.probe_sender_score_index == 1)
                            side = "ME" if scorer_is_probe_sender == hs.probe_sent_by_me else "PEER"
            out.append((side or "UNRESOLVED", reason))
        return out

    def goal_events(self):
        clusters = []
        for message in sorted(self.goal_messages, key=lambda m: m[0]):
            if clusters and message[0] - clusters[-1][-1][0] <= CLUSTER_GAP_MS:
                clusters[-1].append(message)
            else:
                clusters.append([message])
        # F15 CORROBORATION GATE: a goal is a BIDIRECTIONAL exchange - the
        # original from the scoring side plus the conceder's echo/sync within
        # the cluster window (measured on every real goal in the 28_Jun and
        # 29_Jan captures). Clusters whose goal-family messages are all
        # one-directional are kickoff/state summaries: the 3-Sep session fired
        # exactly one of those per match (payload ~1197, ~7-10s after
        # keystream convergence, no echo) while the real 3 goals produced no
        # such messages. Summaries are evidence, never goals.
        goal_clusters = [c for c in clusters
                         if any(m[1] != c[0][1] for m in c)]
        cluster_info = []
        for cluster in goal_clusters:
            first = cluster[0]
            other = next((m for m in cluster if m[1] != first[1]), None)
            mine = self._fields_of(first)
            theirs = self._fields_of(other) if other else None
            disagree = (mine is not None and theirs is not None and
                        not mine.same_attribution_fields_as(theirs))
            cluster_info.append((mine, disagree, first))
        disagreeing = {i for i, info in enumerate(cluster_info) if info[1]}
        resolutions = self._resolve_scorers([info[0] for info in cluster_info], disagreeing)
        events = []
        for index, cluster in enumerate(goal_clusters):
            first = cluster[0]
            peer_echo = next((m for m in cluster
                              if m[1] != first[1] and 0 <= m[0] - first[0] <= ECHO_WINDOW_MS), None)
            fields, disagree, _ = cluster_info[index]
            side, reason = resolutions[index]
            events.append(GoalEvent(
                first[0], first[1], first[2], first[3], len(cluster),
                peer_echo is not None,
                (peer_echo[0] - first[0]) if peer_echo else None,
                fields, disagree, side,
                "UNPROVEN" if side == "UNRESOLVED" else "CALIBRATED",
                reason))
        return events

    def unilateral_summaries(self):
        clusters = []
        for message in sorted(self.goal_messages, key=lambda m: m[0]):
            if clusters and message[0] - clusters[-1][-1][0] <= CLUSTER_GAP_MS:
                clusters[-1].append(message)
            else:
                clusters.append([message])
        return sum(1 for c in clusters if all(m[1] == c[0][1] for m in c))

    def pending_probe_for_next_epoch(self):
        if self.last_probe_ms > 0:
            return (self.last_probe_ms, self.last_probe_sent_by_me,
                    self.rematch_port_pair or self.epoch_port_pair)
        return None

    def seed_probe(self, seed):
        if self.last_probe_ms <= 0:
            self.last_probe_ms = seed[0]
            self.last_probe_sent_by_me = seed[1]
            if self.epoch_port_pair is None and seed[2] is not None:
                self.epoch_port_pair = seed[2]

    def local_player_id(self):
        return max(self.stun_ids_mine, key=self.stun_ids_mine.get) if self.stun_ids_mine else None

    def remote_player_id(self):
        return max(self.stun_ids_theirs, key=self.stun_ids_theirs.get) if self.stun_ids_theirs else None

    def _observe_stun_identity(self, sent_by_me, head, head_len):
        if head_len < 32:
            return
        type_hi = head[0]
        type_lo = head[1]
        if type_hi not in (0x08, 0x09):
            return
        if not (0x0A <= type_lo <= 0x0C):
            return
        ids = self.stun_ids_mine if sent_by_me else self.stun_ids_theirs
        i = 0
        while i <= head_len - 10:
            window = head[i:i + 10]
            if all(0x30 <= b <= 0x39 for b in window):
                left_ok = i == 0 or not (0x30 <= head[i - 1] <= 0x39)
                right_ok = i + 10 >= head_len or not (0x30 <= head[i + 10] <= 0x39)
                if left_ok and right_ok:
                    ids[bytes(window)] = ids.get(bytes(window), 0) + 1
                    i += 10
                    continue
            i += 1

    def segment_result(self, ended_by, completed, ended_at_ms):
        events = self.goal_events()
        resolved = [e for e in events if e.scorer_side != "UNRESOLVED"]
        unresolved = len(events) - len(resolved)
        unilateral = self.unilateral_summaries()
        outgoing = max(0, self.latest_out - self.baseline_out)
        incoming = max(0, self.latest_in - self.baseline_in)
        duration = max(0, ended_at_ms - self.started_at_ms)
        completion_proven = completed and self._has_full_time_evidence()
        calibration_loaded = self.calibration is not None and self.calibration.validated
        if not events and calibration_loaded:
            attribution = "CALIBRATED"
        elif resolved and len(resolved) == len(events):
            attribution = "CALIBRATED"
        else:
            attribution = "UNPROVEN"
        blockers = []
        if not self.gated:
            blockers.append("protocol fingerprint did not converge")
        if self.handshake is None:
            blockers.append("match start was not proven (no probe/exchange handshake in this epoch)")
        if not calibration_loaded:
            blockers.append("scorer attribution is not calibrated - settlement stays disabled until one both-players-score capture pins it")
        if unresolved > 0:
            blockers.append("%d goal(s) have unresolved scorer attribution" % unresolved)
        for e in [e for e in events if e.reason][:3]:
            blockers.append(e.reason)
        if not completion_proven:
            blockers.append("full-time control sequence and a terminal boundary were not both proven")
        if any(not e.corroborated for e in events):
            blockers.append("one or more goal events lacked the peer echo")
        if self.latest_drops - self.baseline_drops > 0:
            blockers.append("%d score-telemetry snapshot(s) were dropped" % (self.latest_drops - self.baseline_drops))
        if self.latest_failures - self.baseline_failures > 0:
            blockers.append("%d score-telemetry poll(s) were malformed" % (self.latest_failures - self.baseline_failures))
        if self.protocol_decode_drops > 0:
            blockers.append("%d pre-gate event head(s) exceeded the bounded decoder queue" % self.protocol_decode_drops)
        if outgoing < MIN_SETTLEMENT_PACKETS_PER_DIRECTION or incoming < MIN_SETTLEMENT_PACKETS_PER_DIRECTION:
            blockers.append("insufficient bidirectional match traffic")
        if duration < MIN_SETTLEMENT_DURATION_MS:
            blockers.append("match segment was too short")
        if unilateral > 0:
            blockers.append("%d unilateral goal-family summary(ies) observed (kickoff/state sync markers - not goals; wire evidence logged)" % unilateral)
        result = SegmentResult()
        result.gated = self.gated
        result.table_converged_at_ms = self.table_converged_at_ms
        result.handshake = self.handshake
        result.events = events
        result.my_goals = sum(1 for e in resolved if e.scorer_side == "ME")
        result.opponent_goals = sum(1 for e in resolved if e.scorer_side == "PEER")
        result.total_goals = len(events)
        result.unilateral_summaries = unilateral
        result.attribution = attribution
        result.calibration_loaded = calibration_loaded
        result.ended_by = ended_by
        result.completed = completion_proven
        result.settleable = not blockers
        result.settlement_blockers = blockers
        result.outgoing_packets = outgoing
        result.incoming_packets = incoming
        result.duration_ms = duration
        result.reconnects = self.reconnects
        result.port_pair = self.epoch_port_pair
        result.local_player_id = self.local_player_id()
        result.remote_player_id = self.remote_player_id()
        return result


class Tracker:
    """Port of the MatchTracker lifecycle (F13-v2), with explicit clocks."""

    def __init__(self, calibration=None):
        self.calibration = calibration
        self.records = []
        self.logs = []
        self.session_active = False
        self.reader = None
        self.epoch_ordinal = -1
        self.epoch_start_ms = 0
        self.epoch_sealed = False
        self.sealed_through_ms = 0
        self.quarantined_packets = 0
        self.out_total = 0
        self.in_total = 0
        self.my_goals = 0
        self.opponent_goals = 0
        self.total_goals = 0
        self.last_activity_ms = 0

    def log(self, message):
        self.logs.append(message)

    def begin_session(self):
        self.session_active = True
        self.epoch_ordinal = -1
        self.reader = None
        self.quarantined_packets = 0
        self._reset_score()

    def on_telemetry(self, events, wall_now, last_game_wall_ms=None):
        """events: list of (ordinal, ts_ms, sent_by_me, payload_len, head, head_len, sport, dport)."""
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
                    self.log("[MATCH] Second handshake on a new port pair (%s<->%s vs %s<->%s) - sealing epoch, starting a new match"
                             % (rematch_ports[0] if rematch_ports else 0, rematch_ports[1] if rematch_ports else 0,
                                epoch_ports[0] if epoch_ports else 0, epoch_ports[1] if epoch_ports else 0))
                if is_reconnect:
                    current.on_reconnect()
                    self.log("[MATCH] Reconnect handshake - same match continues (goals %d total, cipher tables re-learned)"
                             % self.total_goals)
                else:
                    # F15: capture the probe that opened this handshake BEFORE
                    # sealing - it arrived before the boundary fired, so the
                    # fresh reader never sees it without seeding.
                    seed = current.pending_probe_for_next_epoch()
                    self._finalize_epoch(current.segment_result("boundary", True, packet_wall), packet_wall)
                    self._new_epoch(packet_wall, batch_out, batch_in)
                    current = self.reader
                    if current is None:
                        continue
                    if seed is not None:
                        current.seed_probe(seed)
                    current.on_packet(packet_wall, sent_by_me, payload_len,
                                      head, head_len, sport, dport)
        current = self.reader
        if current is not None and not self.epoch_sealed:
            last_game_wall = last_game_wall_ms if last_game_wall_ms is not None else (
                max((e[1] for e in events), default=0))
            current.observe_snapshot(last_game_wall, batch_out, batch_in, 0, 0)
            self._update_live_score(current)
            if current.check_idle_end(wall_now):
                self._finalize_epoch(current.segment_result("idle", True, wall_now), wall_now)
                self.reader = None
                self.epoch_start_ms = 0
                self.epoch_sealed = True
                self.sealed_through_ms = wall_now
            elif (not current.gated and
                  current.game_silent_for_ms(wall_now) >= IDLE_END_SILENCE_MS):
                # Un-gated and silent for the full idle window: proves nothing.
                # Drop it so the next match cannot absorb into it (measured on
                # the 1-Sep trace A: match 2 opened 4 minutes after match 1's
                # tail went quiet and its len-59 phase predated the capture).
                self.log("[MATCH] Discarded un-gated silent epoch (no fingerprint, no handshake, nothing recorded)")
                self.reader = None
                self.epoch_start_ms = 0
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
        self.sealed_through_ms = 0
        self.epoch_ordinal += 1
        self._reset_score()
        self.reader = Reader(
            started_at_ms,
            baseline_out if baseline_out is not None else self.out_total,
            baseline_in if baseline_in is not None else self.in_total,
            calibration=self.calibration)
        self.log("[MATCH] Match epoch %d started at %d" % (self.epoch_ordinal, started_at_ms))
        return self.reader

    def _update_live_score(self, current):
        events = current.goal_events()
        mine = sum(1 for e in events if e.scorer_side == "ME")
        theirs = sum(1 for e in events if e.scorer_side == "PEER")
        if len(events) != self.total_goals or mine != self.my_goals or theirs != self.opponent_goals:
            latest = events[-1]
            fields = latest.fields.describe() if latest.fields else "fields unavailable"
            self.log("[MATCH] Goal #%d: %d message(s), %s; wire fields %s; scorer %s" % (
                len(events), latest.n_messages,
                "peer echo confirmed" if latest.corroborated else "awaiting peer echo",
                fields, latest.scorer_side +
                (" (%s)" % (latest.reason or "calibration pending") if latest.scorer_side == "UNRESOLVED" else "")))
        self.my_goals = mine
        self.opponent_goals = theirs
        self.total_goals = len(events)

    def _finalize_epoch(self, result, ended_at_ms):
        if not result.gated or self.epoch_start_ms <= 0:
            return
        if self.epoch_sealed:
            return
        unilateral = getattr(result, "unilateral_summaries", 0)
        record = {
            "ordinal": self.epoch_ordinal,
            "my_goals": result.my_goals,
            "opponent_goals": result.opponent_goals,
            "total_goals": result.total_goals,
            "unilateral_summaries": unilateral,
            "gated": result.gated,
            "ended_by": result.ended_by,
            "completed": result.completed,
            "settleable": result.settleable,
            "blockers": result.settlement_blockers,
            "goals": [{
                "t": e.t_ms, "scorer": e.scorer_side, "fields": e.fields.describe() if e.fields else None,
                "corroborated": e.corroborated, "reason": e.reason,
            } for e in result.events],
            "events": list(result.events),
            "reconnects": result.reconnects,
            "ended_at_ms": ended_at_ms,
            "port_pair": result.port_pair,
            "attribution": result.attribution,
            "calibration_loaded": result.calibration_loaded,
            "handshake": result.handshake,
            "local_player_id": result.local_player_id,
            "remote_player_id": result.remote_player_id,
        }
        self.records.append(record)
        self.epoch_sealed = True
        self.sealed_through_ms = max(self.sealed_through_ms, ended_at_ms)
        self.log("[MATCH] FINAL %s-%s of %s goal(s); %s" % (
            record["my_goals"], record["opponent_goals"], record["total_goals"],
            "settleable" if record["settleable"] else "UNVERIFIED - no PeerCoins (%s)" % "; ".join(record["blockers"])))

    def _reset_score(self):
        self.my_goals = 0
        self.opponent_goals = 0
        self.total_goals = 0


class WireGen:
    """Synthetic eFootball wire generator with the REAL measured structure.

    World assumptions (both are generator-side choices; the reader under test
    never sees them):
      - protocol side 1 (role 1) = the probe sender;
      - first_byte_is_probe_sender: whether plain[6] carries the probe
        sender's side score (the open question the calibration capture pins);
      - hypothesis 'mask': plain[6..9] = 0x90^sA, 0x90^sA, 0x90^sB, 0x90^sB
        (masked cumulative score pair);
        hypothesis 'constant': plain[6..9] = 91 91 90 90 always (fields are
        constants and the side is NOT in the header).
    """

    def __init__(self, seed=1234, first_byte_is_probe_sender=True):
        self.rnd = random.Random(seed)
        self.table_out = [self.rnd.randrange(256) for _ in range(TABLE_SIZE)]
        self.table_in = [self.rnd.randrange(256) for _ in range(TABLE_SIZE)]
        self.first_byte_is_probe_sender = first_byte_is_probe_sender

    def _table(self, direction):
        return self.table_out if direction == "out" else self.table_in

    def _role(self, direction, probe_by_me):
        # role 1 = probe sender
        if probe_by_me:
            return 1 if direction == "out" else 2
        return 2 if direction == "out" else 1

    def len59(self, direction):
        table = self._table(direction)
        payload = bytearray(59)
        payload[0:8] = bytes(8)
        for j in range(TABLE_SIZE):
            noise = self.rnd.randrange(256) if self.rnd.random() < 0.02 else 0
            payload[8 + j] = table[j] ^ noise
        return bytes(payload)

    def goal(self, direction, score_a, score_b, hypothesis="mask", probe_by_me=True, payload_len=920):
        table = self._table(direction)
        role = self._role(direction, probe_by_me)
        mk = self.rnd.randrange(256)
        plain = bytearray(60)
        plain[0] = self.rnd.randrange(256)
        plain[1] = self.rnd.randrange(256)
        plain[2] = plain[3] = self.rnd.randrange(256)
        plain[4] = self.rnd.randrange(256)
        plain[5] = 0x85 if direction == "out" else 0x87
        if hypothesis == "mask":
            plain[6] = plain[7] = 0x90 ^ (score_a & 0x0F)
            plain[8] = plain[9] = 0x90 ^ (score_b & 0x0F)
        else:
            plain[6] = plain[7] = 0x91
            plain[8] = plain[9] = 0x90
        plain[10] = 0
        plain[11] = role
        plain[12] = plain[13] = 0x10
        plain[14] = plain[15] = plain[16] = 0
        plain[17] = 0x06
        plain[18:26] = GOAL_TAIL
        plain[26] = 0x8c
        plain[27] = 0x8c if direction == "out" else 0x84
        plain[28] = 0x98 if direction == "out" else 0xd2
        plain[29] = self.rnd.randrange(256)
        v = self.rnd.randrange(256)
        plain[30] = v ^ 0x0C
        plain[31] = plain[32] = v
        plain[33] = v ^ 0x20
        for j in range(34, 51):
            plain[j] = self.rnd.randrange(256)
        wire = bytearray(60)
        wire[0:8] = bytes(8)
        for j in range(51):
            wire[8 + j] = plain[j] ^ table[j] ^ mk
        return bytes(wire), payload_len

    @staticmethod
    def probe():
        return bytes(14), 14

    @staticmethod
    def exchange():
        return bytes(60), 266

    @staticmethod
    def control():
        return bytes(26), 26

    def filler(self, length=None):
        length = length or self.rnd.randrange(60, 399)
        payload = bytearray(length)
        payload[0:8] = bytes(8)
        for i in range(8, min(length, 60)):
            payload[i] = self.rnd.randrange(256)
        return bytes(payload[:60]), length
