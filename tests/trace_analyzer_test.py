#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import tempfile

from analyze_udp_trace import read_trace, report


HEADER = (
    "# wifi_truth_trace version=2 packets=3 capturedEvents=9 overwritten=0 "
    "localSenderId=222 kernelRxTs=enabled txKernelTs=enabled "
    "gameplayStartNs=1000000000 gameplayThresholdPps=20\n"
    "# sender_id,seq,dir,ipver,flow_hash,flags,len,sport,dport,rel_ms,"
    "sender_delta_us,tx_tun_to_classify_us,tx_classify_to_send_us,"
    "tx_user_to_sched_us,tx_user_to_soft_us,tx_sched_to_soft_us,"
    "rx_kernel_delta_us,rx_kernel_to_user_us,rx_user_to_enqueue_us,"
    "rx_enqueue_to_write_us,rx_user_to_tun_write_us,tun_read_ns,"
    "classified_ns,send_call_ns,sender_t0_ns,sender_s1_ns,"
    "sender_send_attempt_ns,rx_kernel_rt_ns,rx_user_ns,enqueue_ns,"
    "tun_write_ns,status\n"
)


def row(seq: int, rel_ms: int, sender_delta: int, kernel_delta: int) -> str:
    values = [
        111, seq, "in", 4, "12345678", "tunnel|fromPeer|stableKnown", 64,
        20769, 56008, rel_ms, sender_delta, -1, -1, -1, -1, -1,
        kernel_delta, 1000, 10, 20, 30,
        0, 0, 0, 0, 0, rel_ms * 1_000_000,
        rel_ms * 1_000_000, rel_ms * 1_000_000 + 1_000_000,
        rel_ms * 1_000_000 + 1_010_000,
        rel_ms * 1_000_000 + 1_030_000, "rx_written",
    ]
    return ",".join(map(str, values)) + "\n"


def main() -> int:
    content = HEADER + row(1, 0, -1, -1) + row(2, 100, 37_000, 100_000) + row(3, 103, 37_000, 3_000)
    with tempfile.TemporaryDirectory(prefix="peerlink_trace_test_") as directory:
        path = pathlib.Path(directory) / "trace.csv"
        path.write_text(content, encoding="utf-8")
        output = report(path, read_trace(path), gap_ms=75.0, burst_ms=10.0)
    checks = {
        "version-2 trace parses": "wifi_truth_trace version=2" in output,
        "path delay is distinguished from sender spacing": "between sender send and receiver kernel: 1" in output,
        "gap followed by burst is detected": "followed by <= 10 ms arrival=1" in output,
        "actual TUN-write metric is reported": "enqueue -> actual TUN write" in output,
    }
    failures = 0
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
        failures += not passed
    print(f"SUMMARY checks={len(checks)} failures={failures}")
    return int(failures != 0)


if __name__ == "__main__":
    raise SystemExit(main())
