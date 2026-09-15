#!/usr/bin/env python3
"""Offline Kotlin/manifest/JNI structural checks when Android Gradle is unavailable."""
from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"
NATIVE = ROOT / "app/src/main/jni/peerlink_backend.cpp"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

checks = failures = 0


def check(name: str, condition: bool) -> None:
    global checks, failures
    checks += 1
    failures += not condition
    print(("PASS " if condition else "FAIL ") + name)


def strip_kotlin(source: str) -> str:
    """Replace comments/strings/chars with spaces while preserving newlines."""
    out = list(source)
    i = 0
    block_depth = 0
    while i < len(source):
        if block_depth:
            if source.startswith("/*", i):
                block_depth += 1
                out[i:i + 2] = "  "
                i += 2
            elif source.startswith("*/", i):
                block_depth -= 1
                out[i:i + 2] = "  "
                i += 2
            else:
                if source[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        if source.startswith("//", i):
            end = source.find("\n", i)
            if end < 0:
                end = len(source)
            for j in range(i, end):
                out[j] = " "
            i = end
            continue
        if source.startswith("/*", i):
            block_depth = 1
            out[i:i + 2] = "  "
            i += 2
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = len(source) - 3 if end < 0 else end
            for j in range(i, min(len(source), end + 3)):
                if source[j] != "\n":
                    out[j] = " "
            i = min(len(source), end + 3)
            continue
        if source[i] in ('"', "'"):
            quote = source[i]
            out[i] = " "
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    out[i] = " "
                    if i + 1 < len(source):
                        out[i + 1] = " "
                    i += 2
                    continue
                char = source[i]
                if char != "\n":
                    out[i] = " "
                i += 1
                if char == quote:
                    break
            continue
        i += 1
    return "".join(out)


def balanced(source: str) -> bool:
    clean = strip_kotlin(source)
    pairs = {')': '(', ']': '[', '}': '{'}
    stack: list[str] = []
    for char in clean:
        if char in "([{":
            stack.append(char)
        elif char in pairs:
            if not stack or stack.pop() != pairs[char]:
                return False
    return not stack


def main() -> int:
    files = sorted(JAVA.rglob("*.kt"))
    check("Kotlin source tree is present", len(files) >= 30)
    all_sources = {path: path.read_text(encoding="utf-8") for path in files}
    for path, source in all_sources.items():
        relative = path.relative_to(ROOT)
        check(f"balanced Kotlin tokens: {relative}", balanced(source))
        check(f"single package declaration: {relative}", len(re.findall(r"(?m)^\s*package\s+", strip_kotlin(source))) == 1)

    backend = (JAVA / "com/peerlink/app/tunnel/NativePeerLinkBackend.kt").read_text(encoding="utf-8")
    native = NATIVE.read_text(encoding="utf-8")
    externals = re.findall(r"private\s+external\s+fun\s+(\w+)", strip_kotlin(backend))
    check("native backend declares JNI methods", len(externals) >= 7)
    for method in externals:
        check(f"JNI implementation exists: {method}", f"NativePeerLinkBackend_{method}" in native)

    android_ns = "{http://schemas.android.com/apk/res/android}"
    root = ET.parse(MANIFEST).getroot()
    package = "com.peerlink.app"
    components = []
    for tag in ("activity", "service", "receiver", "provider"):
        for node in root.findall(f".//{tag}"):
            name = node.attrib.get(android_ns + "name")
            if name and name.startswith("."):
                components.append(package + name)
    declared = set()
    for path, source in all_sources.items():
        package_match = re.search(r"(?m)^\s*package\s+([\w.]+)", source)
        if not package_match:
            continue
        pkg = package_match.group(1)
        for kind, name in re.findall(r"(?m)^\s*(?:data\s+)?(class|object)\s+(\w+)", strip_kotlin(source)):
            declared.add(f"{pkg}.{name}")
    for component in components:
        check(f"manifest component exists: {component}", component in declared)
    check("F21 guardian LanLink foreground service is registered",
          any(name.endswith("LanLinkForegroundService") for name in components))

    combined = "\n".join(all_sources.values())
    required = (
        "MatchTracker", "PrimeScreenScoreDetector", "ScoreLaneReader",
        "MatchAutomationEngine", "rememberVerifiedGameplayPath",
        "smallGamePackets", "CaptureMode",
    )
    for symbol in required:
        check(f"F32 integration symbol present: {symbol}", symbol in combined)
    for symbol in (
        "MatchProtocolReader", "NativeTelemetrySample", "NativePacketEvent",
        "drainFinalMatchTelemetry", "pollMatchTelemetry",
        "FULL_TIME_CONTROL_LEN", "MatchCalibration",
    ):
        check(f"F32 removed symbol absent: {symbol}", symbol not in combined)
    check("no continuous Wi-Fi scan call remains", ".startScan(" not in combined)
    check("no conflict markers remain", not re.search(r"(?m)^(<<<<<<<|=======|>>>>>>>)", combined))

    print(f"SUMMARY checks={checks} failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
