#!/usr/bin/env python3
"""F10 host replay for PMT2 serialization, protocol parity and lifecycle invariants."""
from __future__ import annotations

import collections
import hashlib
import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRATCH = ROOT.parents[1]
sys.path.insert(0, str(SCRATCH / "f9_review" / "vendor"))
import dpkt  # type: ignore

UPLOAD = SCRATCH / "upload"
MAGIC = 0x32544D50
HEADER = 88
EVENT = 80
HEAD = 60
GOAL = bytes([0x90, 0x90, 0x0E, 0x0E, 0x8E, 0x2E, 0x8E, 0x2E])
FLOWS = {
    "28_Jun": ("PCAPdroid_28_Jun_15_53_33.pcap", "10.233.65.237", 56008, "105.112.105.35", 24722, (0, 1)),
    "29_Jan": ("PCAPdroid_29_Jan_14_32_11.pcap", "10.195.253.206", 58134, "10.195.253.203", 20769, (0, 1)),
    "16_Mar": ("PCAPdroid_16_Mar_08_00_42.pcap", "10.215.173.1", 10841, "13.247.213.155", 36107, None),
    "03_Jul_30_relay": ("PCAPdroid_03_Jul_11_46_30.pcap", "10.215.173.1", 36656, "34.14.65.240", 31808, None),
    "03_Jul_34_relay": ("PCAPdroid_03_Jul_11_46_34.pcap", "10.215.173.1", 49915, "34.62.54.28", 32758, None),
    "06_Jan_relay": ("PCAPdroid_06_Jan_14_29_23.pcap", "10.73.104.8", 46220, "34.155.120.34", 5735, None),
}

CORPUS_GATES = {
    "PCAPdroid_28_Jun_15_53_33.pcap": True,
    "PCAPdroid_29_Jan_14_32_11.pcap": True,
    "PCAPdroid_16_Mar_08_00_42.pcap": False,
    "PCAPdroid_03_Jul_11_46_30.pcap": False,
    "PCAPdroid_03_Jul_11_46_34.pcap": False,
    "PCAPdroid_06_Jan_14_29_23.pcap": False,
    "PCAPdroid_31_Jan_22_41_15.pcap": False,
    "PCAPdroid_04_Feb_12_58_41.pcap": False,
}

checks = failures = 0


def check(name: str, condition: bool) -> None:
    global checks, failures
    checks += 1
    failures += not condition
    print(("PASS " if condition else "FAIL ") + name)


def ip(value: bytes) -> str:
    return ".".join(str(x) for x in value)


def load_flow(spec):
    filename, a_ip, a_port, b_ip, b_port, _ = spec
    packets = []
    with (UPLOAD / filename).open("rb") as stream:
        reader = dpkt.pcap.Reader(stream)
        link = reader.datalink()
        for timestamp, raw in reader:
            try:
                packet = dpkt.ethernet.Ethernet(raw).data if link == 1 else dpkt.ip.IP(raw)
            except Exception:
                continue
            if not isinstance(packet, dpkt.ip.IP) or packet.p != dpkt.ip.IP_PROTO_UDP:
                continue
            udp = packet.data
            if not isinstance(udp, dpkt.udp.UDP):
                continue
            source, destination = ip(packet.src), ip(packet.dst)
            payload = bytes(udp.data)
            if source == a_ip and udp.sport == a_port and destination == b_ip and udp.dport == b_port:
                packets.append((timestamp, 0, payload))
            elif source == b_ip and udp.sport == b_port and destination == a_ip and udp.dport == a_port:
                packets.append((timestamp, 1, payload))
    packets.sort()
    return packets


def selected(length: int) -> bool:
    return length in (14, 26, 59, 266, 272) or length >= 400


def capture_has_bidirectional_len59_gate(filename: str) -> bool:
    counts = collections.defaultdict(lambda: [0, 0])
    with (UPLOAD / filename).open("rb") as stream:
        reader = dpkt.pcap.Reader(stream)
        link = reader.datalink()
        for _, raw in reader:
            try:
                packet = dpkt.ethernet.Ethernet(raw).data if link == 1 else dpkt.ip.IP(raw)
            except Exception:
                continue
            if not isinstance(packet, dpkt.ip.IP) or packet.p != dpkt.ip.IP_PROTO_UDP:
                continue
            udp = packet.data
            if not isinstance(udp, dpkt.udp.UDP) or len(udp.data) != 59:
                continue
            source = (ip(packet.src), udp.sport)
            destination = (ip(packet.dst), udp.dport)
            endpoints = tuple(sorted((source, destination)))
            counts[endpoints][0 if source == endpoints[0] else 1] += 1
    return any(a >= 40 and b >= 40 for a, b in counts.values())


def build_pmt2(events, counters, dropped=0):
    raw = bytearray(HEADER + len(events) * EVENT)
    struct.pack_into("<IHHHHI", raw, 0, MAGIC, 2, HEADER, EVENT, len(events), 0)
    struct.pack_into("<9Q", raw, 16, *counters, dropped)
    for index, (ordinal, timestamp, direction, payload) in enumerate(events):
        offset = HEADER + index * EVENT
        head = payload[:HEAD]
        struct.pack_into("<QQBHB", raw, offset, ordinal, timestamp, direction, len(payload), len(head))
        raw[offset + 20: offset + 20 + len(head)] = head
    return bytes(raw)


def parse_pmt2(raw: bytes):
    if len(raw) < HEADER:
        return None
    magic, version, header, event_size, count, _ = struct.unpack_from("<IHHHHI", raw, 0)
    if (magic, version, header, event_size) != (MAGIC, 2, HEADER, EVENT):
        return None
    if count > 4096 or len(raw) != HEADER + count * EVENT:
        return None
    counters = struct.unpack_from("<9Q", raw, 16)
    events = []
    previous = 0
    for index in range(count):
        offset = HEADER + index * EVENT
        ordinal, timestamp, direction, length, head_len = struct.unpack_from("<QQBHB", raw, offset)
        if ordinal <= previous or direction not in (0, 1) or head_len > min(HEAD, length):
            return None
        previous = ordinal
        events.append((ordinal, timestamp, direction, length, raw[offset + 20:offset + 80], head_len))
    return counters, events


class Reader:
    def __init__(self):
        self.counts = {0: [collections.Counter() for _ in range(51)], 1: [collections.Counter() for _ in range(51)]}
        self.n = {0: 0, 1: 0}
        self.tables = None
        self.messages = []
        self.pending = []
        self.decode_drops = 0
        self.full_time = {0: collections.deque(), 1: collections.deque()}
        self.full_time_at = 0

    def feed(self, timestamp, direction, length, head, head_len):
        if length < 8 or head_len < 8:
            return
        if head[4:8] == b"\x21\x12\xa4\x42":
            return
        if self.tables is None:
            if length == 59 and head_len >= 59:
                for column in range(51):
                    self.counts[direction][column][head[8 + column]] += 1
                self.n[direction] += 1
                if self.n[0] >= 40 and self.n[1] >= 40:
                    self.tables = {
                        side: [max(range(256), key=lambda value, c=column: c[value]) for column in self.counts[side]]
                        for side in (0, 1)
                    }
                    for pending in self.pending:
                        self._decode(*pending)
                    self.pending.clear()
            elif length >= 400 and head_len >= 34:
                if len(self.pending) < 512:
                    self.pending.append((timestamp, direction, length, head[:34], 34))
                else:
                    self.decode_drops += 1
            return
        if length == 26:
            self._full_time(timestamp, direction)
        self._decode(timestamp, direction, length, head, head_len)

    def _decode(self, timestamp, direction, length, head, head_len):
        if self.tables is None or head_len < 34:
            return
        table = self.tables[direction]
        role = 1 if direction == 0 else 2
        key = (head[19] ^ table[11]) ^ role
        decoded = [head[8 + j] ^ table[j] ^ key for j in range(26)]
        family = (decoded[6] == decoded[7] and decoded[8] == decoded[9] and
                  decoded[12] == decoded[13] and decoded[14] == decoded[15] == decoded[16] and
                  decoded[6] ^ decoded[8] == 1 and decoded[12] ^ decoded[14] == 0x10)
        if family and length >= 400 and bytes(decoded[18:26]) == GOAL:
            self.messages.append((timestamp, direction, length))

    def _full_time(self, timestamp, direction):
        self.full_time[direction].append(timestamp)
        cutoff = timestamp - 8_000
        for side in (0, 1):
            while self.full_time[side] and self.full_time[side][0] < cutoff:
                self.full_time[side].popleft()
        if timestamp >= 90_000 and all(len(self.full_time[side]) >= 20 for side in (0, 1)):
            self.full_time_at = timestamp

    def full_time_proven(self, last_game):
        return self.full_time_at > 0 and 0 <= last_game - self.full_time_at <= 10_000

    def score(self):
        clusters = []
        for message in self.messages:
            if clusters and message[0] - clusters[-1][-1][0] <= 10_000:
                clusters[-1].append(message)
            else:
                clusters.append([message])
        goals = []
        for cluster in clusters:
            first = cluster[0]
            echo = next((message for message in cluster if message[1] != first[1] and 0 <= message[0] - first[0] <= 12_000), None)
            goals.append((first[1], echo is not None))
        return goals.count((0, True)), goals.count((1, True)), goals


def replay(name, spec):
    packets = load_flow(spec)
    origin = packets[0][0]
    selected_events = []
    out_packets = in_packets = out_bytes = in_bytes = 0
    last_out = last_in = 0
    ordinal = 1
    for timestamp, direction, payload in packets:
        relative = int(round((timestamp - origin) * 1000))
        if direction == 0:
            out_packets += 1; out_bytes += len(payload); last_out = relative
        else:
            in_packets += 1; in_bytes += len(payload); last_in = relative
        if selected(len(payload)):
            selected_events.append((ordinal, relative, direction, payload)); ordinal += 1
    counters = (out_packets, in_packets, out_bytes, in_bytes, last_out, last_in, 1, max(last_out, last_in))
    raw = build_pmt2(selected_events, counters)
    parsed = parse_pmt2(raw)
    check(f"{name}: PMT2 parses", parsed is not None)
    reader = Reader()
    if parsed:
        for _, timestamp, direction, length, head, head_len in parsed[1]:
            reader.feed(timestamp, direction, length, head, head_len)
    expected = spec[-1]
    if expected is None:
        check(f"{name}: non-eFootball flow rejected", reader.tables is None and not reader.messages)
    else:
        check(f"{name}: bidirectional protocol gate converges", reader.tables is not None)
        score = reader.score()
        check(f"{name}: score is {expected[0]}-{expected[1]}", score[:2] == expected)
        check(f"{name}: every counted goal has peer echo", all(goal[1] for goal in score[2]))
        check(f"{name}: measured full-time control burst is present",
              reader.full_time_proven(max(counters[4], counters[5])))
    return raw


def malformed_tests(valid):
    check("PMT2 rejects a truncated header", parse_pmt2(valid[:87]) is None)
    check("PMT2 rejects a truncated event", parse_pmt2(valid[:-1]) is None)
    bad = bytearray(valid); struct.pack_into("<I", bad, 0, 0)
    check("PMT2 rejects wrong magic", parse_pmt2(bytes(bad)) is None)
    bad = bytearray(valid); struct.pack_into("<H", bad, 4, 99)
    check("PMT2 rejects unknown version", parse_pmt2(bytes(bad)) is None)
    bad = bytearray(valid); struct.pack_into("<H", bad, 8, 79)
    check("PMT2 rejects wrong event size", parse_pmt2(bytes(bad)) is None)
    if len(valid) >= HEADER + EVENT:
        bad = bytearray(valid); bad[HEADER + 16] = 7
        check("PMT2 rejects invalid direction", parse_pmt2(bytes(bad)) is None)
        bad = bytearray(valid); bad[HEADER + 19] = 61
        check("PMT2 rejects oversized head", parse_pmt2(bytes(bad)) is None)


def early_goal_and_overflow_tests():
    packets = load_flow(FLOWS["29_Jan"])
    origin = packets[0][0]
    reader = Reader()
    # Move the real, corroborated goal window ahead of table convergence while
    # preserving its internal timing. The buffered reader must recover it once
    # 40 len-59 samples from each direction arrive.
    for timestamp, direction, payload in packets:
        relative = int(round((timestamp - origin) * 1000))
        if 41_000 <= relative <= 49_500 and len(payload) >= 400:
            reader.feed(relative - 36_000, direction, len(payload), payload[:HEAD], min(len(payload), HEAD))
    for timestamp, direction, payload in packets:
        if len(payload) == 59:
            relative = int(round((timestamp - origin) * 1000)) + 30_000
            reader.feed(relative, direction, len(payload), payload[:HEAD], HEAD)
    check("goal arriving before table convergence is replayed, not lost", reader.score()[:2] == (0, 1))

    bounded = Reader()
    dummy = bytes(60)
    for ordinal in range(513):
        bounded.feed(ordinal, ordinal & 1, 400, dummy, 60)
    check("pre-gate event buffering is bounded and fail-closed", len(bounded.pending) == 512 and bounded.decode_drops == 1)


def peercoin_matrix_tests():
    def reward(mine, theirs):
        win, loss = mine > theirs, mine < theirs
        margin = abs(mine - theirs)
        return ((30 if win else -13 if loss else 0) +
                (20 if win and mine >= 3 else 0) +
                (10 if win and margin >= 3 else 0) +
                (-80 if loss and margin >= 3 else 0))

    expected = {
        (0, 0): 0,
        (1, 0): 30,
        (0, 1): -13,
        (3, 2): 50,
        (3, 0): 60,
        (0, 3): -93,
        (3, 3): 0,
    }
    for score, cents in expected.items():
        check(f"PeerCoin matrix {score[0]}-{score[1]} = {cents} cents", reward(*score) == cents)


def full_corpus_gate_tests():
    for filename, expected in CORPUS_GATES.items():
        actual = capture_has_bidirectional_len59_gate(filename)
        check(f"full corpus gate {filename} = {expected}", actual == expected)


def source_invariants():
    tracker = (ROOT / "app/src/main/java/com/peerlink/app/core/MatchTracker.kt").read_text()
    data = (ROOT / "app/src/main/java/com/peerlink/app/core/MatchData.kt").read_text()
    native = (ROOT / "app/src/main/jni/peerlink_backend.cpp").read_text()
    main = (ROOT / "app/src/main/java/com/peerlink/app/ui/MainActivity.kt").read_text()
    service = (ROOT / "app/src/main/java/com/peerlink/app/service/PeerLinkVpnService.kt").read_text()
    backend = (ROOT / "app/src/main/java/com/peerlink/app/tunnel/NativePeerLinkBackend.kt").read_text()
    check("finalized reader is replaced after idle", "reader = null" in tracker and "finalized reader is never reused" in tracker)
    check("disconnect is explicitly incomplete", 'segmentResult("disconnect", completed = false' in tracker)
    check("silence alone cannot settle PeerCoins",
          "full-time control sequence and a terminal boundary were not both proven" in
          (ROOT / "app/src/main/java/com/peerlink/app/core/MatchProtocolReader.kt").read_text())
    check("record IDs include session and segment", '"match_${sessionId}_$segmentOrdinal"' in tracker)
    check("legacy confirmation defaults fail closed", 'optBoolean("confirmed", false)' in data and "integrityVersion >= CURRENT_INTEGRITY_VERSION" in data)
    check("ledger rejects duplicate IDs", "if (current.any { it.id == record.id }) return false" in data)
    check("ledger uses fsync and atomic move", "stream.fd.sync()" in data and "StandardCopyOption.ATOMIC_MOVE" in data)
    check("gameplay and control TUN queues are isolated", "control_tun_inject_queue" in native and "policy=game-first" in native)
    check("score hot path uses SPSC rings, not an event mutex", "MatchEventRing" in native and "event_mutex" not in native)
    check("shutdown drains telemetry after stopping its periodic consumer",
          "stopStatsPolling(waitForTermination = true)" in backend and
          "drainFinalMatchTelemetry" in service)
    check("telemetry rejects cross-poll ordinal or counter rollback",
          "lastTelemetryOrdinal" in backend and "lastTelemetryHeader" in backend and
          "headerValues[it] < lastTelemetryHeader[it]" in backend)
    check("continuous Wi-Fi scanning removed", "startScan()" not in main and "startContinuousWifiScan" not in main)
    check("log export includes native UDP truth trace off the UI thread",
          "PeerLink-Log-Export" in main and "dumpNativeUdpTrace()" in main and
          "peerlink_udp_trace_" in main)
    check("lost Wi-Fi path gets immediate recovery when it returns",
          'refreshGameplayPath("wifi-recovered", force = false)' in service and
          "lastGameplayNetworkIdentity != null" in service)
    check("Linux transmit timestamp IDs start at zero",
          "uint32_t next_peer_tx_timestamp_id = 0u" in native)

    expected = {
        "StunFabricator.kt": "2aa47a9ec0a0556d572fcfb424b60fed902a779ab7565dbf42f0364da3e6a260",
        "PacketParser.kt": "0064ce3beb1c68d3b03f78a6d1e0ab773061dc31e3f2e8738a031a5da5cc5f8e",
    }
    for filename, digest in expected.items():
        actual = hashlib.sha256((ROOT / "app/src/main/java/com/peerlink/app/tunnel" / filename).read_bytes()).hexdigest()
        check(f"corrected {filename} is installed", actual == digest)


def main():
    valid = None
    for name, spec in FLOWS.items():
        result = replay(name, spec)
        if valid is None:
            valid = result
    malformed_tests(valid)
    early_goal_and_overflow_tests()
    peercoin_matrix_tests()
    full_corpus_gate_tests()
    source_invariants()
    print(f"SUMMARY checks={checks} failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
