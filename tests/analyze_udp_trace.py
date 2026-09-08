#!/usr/bin/env python3
"""Summarize PeerLink's version-2 UDP truth trace.

The trace deliberately records timestamps on both sides of each queue boundary.
This tool turns those raw columns into a short attribution report.  It does not
guess that every long interval is "Wi-Fi jitter": one packet may legitimately
be late because the game itself did not send during that interval.
"""

from __future__ import annotations

import argparse
import csv
import io
import pathlib
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass


REQUIRED_V2 = {
    "sender_id",
    "seq",
    "dir",
    "rel_ms",
    "sender_delta_us",
    "rx_kernel_delta_us",
    "rx_kernel_to_user_us",
    "rx_user_to_enqueue_us",
    "rx_enqueue_to_write_us",
    "rx_user_to_tun_write_us",
    "tx_tun_to_classify_us",
    "tx_classify_to_send_us",
    "tx_user_to_sched_us",
    "tx_user_to_soft_us",
    "status",
}


@dataclass(frozen=True)
class Trace:
    metadata: str
    rows: list[dict[str, str]]


def number(row: dict[str, str], key: str) -> int | None:
    try:
        value = int(row[key])
    except (KeyError, TypeError, ValueError):
        return None
    return None if value < 0 else value


def percentile(values: list[int], fraction: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    index = round((len(ordered) - 1) * fraction)
    return ordered[index]


def fmt_us(value: int | None) -> str:
    return "n/a" if value is None else f"{value / 1000.0:.3f} ms"


def read_trace(path: pathlib.Path) -> Trace:
    metadata = ""
    header = ""
    data: list[str] = []
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.rstrip("\r\n")
            if line.startswith("# wifi_truth_trace"):
                metadata = line[2:]
            elif line.startswith("# sender_id,"):
                header = line[2:]
            elif line and not line.startswith("#"):
                data.append(line)
    if not header:
        raise ValueError("missing PeerLink CSV header")
    rows = list(csv.DictReader(io.StringIO("\n".join([header, *data]))))
    missing = REQUIRED_V2.difference(rows[0].keys() if rows else header.split(","))
    if missing:
        raise ValueError(
            "trace is not the required version-2 format; missing "
            + ", ".join(sorted(missing))
        )
    return Trace(metadata=metadata, rows=rows)


def latency_line(name: str, rows: list[dict[str, str]], key: str) -> str:
    values = [value for row in rows if (value := number(row, key)) is not None]
    return (
        f"  {name}: n={len(values)} p50={fmt_us(percentile(values, .50))} "
        f"p95={fmt_us(percentile(values, .95))} "
        f"p99={fmt_us(percentile(values, .99))} max={fmt_us(max(values) if values else None)}"
    )


def classify_gap(row: dict[str, str], threshold_us: int) -> set[str]:
    causes: set[str] = set()
    sender = number(row, "sender_delta_us")
    kernel_gap = number(row, "rx_kernel_delta_us")
    kernel_to_user = number(row, "rx_kernel_to_user_us")
    enqueue_to_write = number(row, "rx_enqueue_to_write_us")

    if sender is not None and sender >= threshold_us:
        causes.add("sender/game produced a gap")
    if kernel_gap is not None and kernel_gap >= threshold_us:
        if sender is not None and sender < threshold_us:
            causes.add("between sender send and receiver kernel")
        elif sender is None:
            causes.add("receiver-kernel arrival gap; sender timing unavailable")
    if kernel_to_user is not None and kernel_to_user >= threshold_us:
        causes.add("receiver app woke/drained late")
    if enqueue_to_write is not None and enqueue_to_write >= threshold_us:
        causes.add("PeerLink TUN injection queue/write late")
    if not causes:
        causes.add("not attributable from available timestamps")
    return causes


def report(path: pathlib.Path, trace: Trace, gap_ms: float, burst_ms: float) -> str:
    threshold_us = round(gap_ms * 1000)
    burst_us = round(burst_ms * 1000)
    incoming = [row for row in trace.rows if row.get("dir") == "in"]
    outgoing = [row for row in trace.rows if row.get("dir") == "out"]
    result = [f"TRACE {path.name}", f"  {trace.metadata or 'metadata unavailable'}"]
    result.append(f"  gameplay rows: total={len(trace.rows)} incoming={len(incoming)} outgoing={len(outgoing)}")
    result.append("INCOMING LATENCY")
    for name, key in (
        ("sender spacing", "sender_delta_us"),
        ("receiver-kernel spacing", "rx_kernel_delta_us"),
        ("kernel -> PeerLink receive", "rx_kernel_to_user_us"),
        ("PeerLink receive -> enqueue", "rx_user_to_enqueue_us"),
        ("enqueue -> actual TUN write", "rx_enqueue_to_write_us"),
        ("PeerLink receive -> actual TUN write", "rx_user_to_tun_write_us"),
    ):
        result.append(latency_line(name, incoming, key))
    result.append("OUTGOING LATENCY")
    for name, key in (
        ("TUN read -> classify", "tx_tun_to_classify_us"),
        ("classify -> send call", "tx_classify_to_send_us"),
        ("send call -> kernel scheduled", "tx_user_to_sched_us"),
        ("send call -> kernel software timestamp", "tx_user_to_soft_us"),
    ):
        result.append(latency_line(name, outgoing, key))

    by_sender: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in incoming:
        by_sender[row.get("sender_id", "?")].append(row)
    causes: Counter[str] = Counter()
    gaps = 0
    burst_after_gap = 0
    examples: list[str] = []
    for sender_rows in by_sender.values():
        sender_rows.sort(key=lambda row: float(row.get("rel_ms", "0") or 0))
        for index, row in enumerate(sender_rows):
            kernel_gap = number(row, "rx_kernel_delta_us")
            app_gap = number(row, "rx_kernel_to_user_us")
            queue_gap = number(row, "rx_enqueue_to_write_us")
            if max(kernel_gap or 0, app_gap or 0, queue_gap or 0) < threshold_us:
                continue
            gaps += 1
            row_causes = classify_gap(row, threshold_us)
            causes.update(row_causes)
            if index + 1 < len(sender_rows):
                following = number(sender_rows[index + 1], "rx_kernel_delta_us")
                if following is not None and following <= burst_us:
                    burst_after_gap += 1
            if len(examples) < 8:
                examples.append(
                    "  "
                    + f"t={row.get('rel_ms', '?')}ms sender={row.get('sender_id', '?')} "
                    + f"seq={row.get('seq', '?')} kernelGap={fmt_us(kernel_gap)} "
                    + f"kernelToUser={fmt_us(app_gap)} queueToWrite={fmt_us(queue_gap)} "
                    + "cause=" + "; ".join(sorted(row_causes))
                )

    result.append(f"GAPS >= {gap_ms:g} ms")
    result.append(f"  suspicious packets={gaps}; followed by <= {burst_ms:g} ms arrival={burst_after_gap}")
    if causes:
        for cause, count in causes.most_common():
            result.append(f"  {cause}: {count}")
    else:
        result.append("  none")
    if examples:
        result.append("EXAMPLES")
        result.extend(examples)
    result.append(
        "INTERPRETATION: sender spacing is embedded by the remote PeerLink; "
        "receiver-kernel spacing is when Android accepted the datagram; "
        "kernel->receive exposes wake/drain delay; enqueue->write exposes PeerLink's local injection path."
    )
    return "\n".join(result)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", nargs="+", type=pathlib.Path)
    parser.add_argument("--gap-ms", type=float, default=75.0)
    parser.add_argument("--burst-ms", type=float, default=10.0)
    args = parser.parse_args()
    if args.gap_ms <= 0 or args.burst_ms < 0:
        parser.error("thresholds must be positive")
    reports: list[str] = []
    failed = False
    for path in args.trace:
        try:
            reports.append(report(path, read_trace(path), args.gap_ms, args.burst_ms))
        except (OSError, ValueError) as exc:
            failed = True
            reports.append(f"TRACE {path.name}\n  ERROR: {exc}")
    print("\n\n".join(reports))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
