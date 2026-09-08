#!/usr/bin/env python3
"""F15 structural invariants — the merge contract.

Checks the SOURCE (not behavior): every F13-v2 lifecycle guarantee that F14
regressed must be present, and the F14 goal-truth detector must be intact.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).parent.parent
READER = ROOT / "app/src/main/java/com/peerlink/app/core/MatchProtocolReader.kt"
TRACKER = ROOT / "app/src/main/java/com/peerlink/app/core/MatchTracker.kt"
DATA = ROOT / "app/src/main/java/com/peerlink/app/core/MatchData.kt"
BACKEND_KT = ROOT / "app/src/main/java/com/peerlink/app/tunnel/NativePeerLinkBackend.kt"
BACKEND_CPP = ROOT / "app/src/main/jni/peerlink_backend.cpp"
TEST_KT = ROOT / "app/src/test/java/com/peerlink/app/core/MatchProtocolReaderTest.kt"
NATIVE_TEST = ROOT / "tests/native_regression.cpp"

reader = READER.read_text()
tracker = TRACKER.read_text()
data = DATA.read_text()
backend_kt = BACKEND_KT.read_text()
backend_cpp = BACKEND_CPP.read_text()
test_kt = TEST_KT.read_text() if TEST_KT.exists() else ""
native_test = NATIVE_TEST.read_text()

failures = []


def check(name, condition):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {name}")
    if not condition:
        failures.append(name)


print("=" * 100)
print("STRUCTURAL 1: the F14 goal-truth detector is intact")
print("=" * 100)
check("unified keystream classes 39/59/63 with vote thresholds",
      all(k in reader for k in ("KS_CLASS_39", "KS_CLASS_59", "KS_CLASS_63",
                                "KS_MIN_PACKETS", "KS_VOTE_MIN_PERCENT")))
check("V[i] = KS[i]^KS[58] rebuild present",
      "rebuildV" in reader and "v[i] = d xor v[36]" in reader)
check("18-byte signature constant with 14-vote threshold",
      "GOAL_SIG_MIN_VOTES = 14" in reader and "SIG_POS_MIN = 19" in reader and "PHASE_POS = 37" in reader)
check("phase bytes 0xC6/0xC7/0xC4 defined",
      all(k in reader for k in ("PHASE_ORIGINAL = 0xC6", "PHASE_ECHO = 0xC7", "PHASE_SYNC = 0xC4")))
check("attribution = sender of the 0xC6 original (only originals count)",
      "originals" in reader and "scorerIsMe = original.sentByMe" in reader)
check("12s celebration clustering and echo window",
      "CLUSTER_GAP_MS = 12_000L" in reader and "ECHO_WINDOW_MS = 12_000L" in reader)
check("STUN player identity extraction (custom-STUN 10-digit IDs)",
      "isCustomStun" in reader and "stunIds" in reader and "modalStunId" in reader)
check("relay suspicion threshold", "RELAY_SUSPECT_MIN_GAME_PACKETS" in reader)
check("goal candidates 300..2000 bounded", "MAX_GOAL_CANDIDATES = 256" in reader)
check("decode-freeze model (candidates frozen, survive re-key)",
      "flushPendingGoals" in reader and "MAX_FROZEN_GOAL_MESSAGES" in reader)

print()
print("=" * 100)
print("STRUCTURAL 2: the F13 lifecycle regressions are FIXED")
print("=" * 100)
check("full-time trailing window is 40s (F14's 10s regression removed)",
      "FULL_TIME_MAX_TRAILING_GAME_MS = 40_000L" in reader and
      "10_000L" not in reader.split("FULL_TIME_MAX_TRAILING_GAME_MS")[1].split("\n")[0])
check("telemetry carries sport/dport (port-pair boundary needs them)",
      "sport" in reader and "dport" in reader and "portPairOf" in reader)
check("handshake evidence with port pair kept as metadata",
      "HandshakeEvidence" in reader and "rematchPortPair" in reader)
check("goal-window masquerade protection (noGoalActivitySince)",
      "noGoalActivitySince" in reader)
check("reconnect re-learns keystream, preserves frozen score",
      "onReconnect" in reader and "resetVoters" in reader and "flushPendingGoals()" in
      reader.split("fun onReconnect")[1].split("}")[0])
check("tracker: epoch quarantine watermark", "quarantinedPackets" in tracker and "sealedThroughMs" in tracker)
check("tracker: reconnect merge window 45s on game-traffic age",
      "RECONNECT_MERGE_MAX_GAP_MS = 45_000L" in tracker and "gameSilentForMs" in tracker)
check("tracker: port-pair change forces a new match", "portChanged" in tracker and "pendingRematchPortPair" in tracker)
check("tracker: un-gated silent epochs are discarded", "Discarded un-gated silent epoch" in tracker)
check("tracker: phases WAITING/STARTING/LIVE/ENDING/SEALED (F13 model)",
      "enum class MatchPhase { NO_MATCH, WAITING, STARTING, LIVE, ENDING, SEALED }" in tracker)
check("tracker: goal log line carries player ID, phase, echo latency",
      "YOU scored" in tracker and "0xC6 original confirmed" in tracker and "phase byte NOT confirmed" in tracker)

print()
print("=" * 100)
print("STRUCTURAL 3: the confounded rules are gone (F13-v2 invariants kept)")
print("=" * 100)
all_sources = reader + tracker + data
check("no exchange-responder scorer rule anywhere",
      not re.search(r"scorerIsMe\s*=\s*exchangeSentByMe", all_sources))
check("no calibration model left (superseded by the 0xC6 rule)",
      "AttributionCalibration" not in all_sources and "MatchCalibration" not in all_sources)
check("no disproven 91/90 score-field recording",
      "scoreByteA" not in all_sources and "scoreByteB" not in all_sources)
check("ledger integrity version 5 (0xC6 attribution era; older pay nothing)",
      "CURRENT_INTEGRITY_VERSION = 5" in data)
check("goal records carry phase confirmation + player ID",
      "phaseConfirmed" in data and "scorerPlayerId" in data and '"pid"' in data)
check("record carries reconnects/port pair/quarantine/relay/player IDs",
      all(k in data for k in ("reconnects", "gamePortPair", "quarantinedPackets",
                              "relaySuspected", "localPlayerId", "remotePlayerId")))
check("settlement blockers include handshake proof and 0xC6 confirmation",
      "no probe/exchange handshake" in reader and "0xC6 original" in reader)

print()
print("=" * 100)
print("STRUCTURAL 4: native telemetry v4 layout is consistent everywhere")
print("=" * 100)
check("native: 64-byte head capture", "kMatchHeadCaptureMax = 64;" in backend_cpp)
check("native: v4 / 88-byte events", "kMatchTelemetryVersion = 4;" in backend_cpp and
      "kMatchTelemetryEventSize = 88;" in backend_cpp)
check("native: sport/dport serialized after the 64-byte head",
      "put_u16(offset + 84, event.sport)" in backend_cpp and
      "put_u16(offset + 86, event.dport)" in backend_cpp)
check("native: snapshot filter retains 39/63 classes + goal range + custom STUN",
      "payload_length == 39" in backend_cpp and "payload_length == 63" in backend_cpp and
      "300" in backend_cpp and "2000" in backend_cpp and "is_game_stun_payload" in backend_cpp)
check("native: record_match_telemetry still receives the flow ports",
      "uint16_t sport" in backend_cpp and "event.sport = sport;" in backend_cpp)
check("kotlin parser: v4 / 88 / 64 constants",
      "MATCH_VERSION = 4" in backend_kt and "MATCH_EVENT_SIZE = 88" in backend_kt and
      "MATCH_HEAD_SIZE = 64" in backend_kt)
check("kotlin parser: head then ports read order",
      backend_kt.index("buffer.get(head)") < backend_kt.index("sourcePort"))
check("kotlin reader HEAD_SIZE matches native head capture",
      "const val HEAD_SIZE = 64" in reader)
check("native regression suite covers classes/STUN/ports/v4 layout",
      "stun_frame" in native_test and "kMatchTelemetryVersion == 4" in native_test and
      "full_time.sport == 51000" in native_test)

print()
print("=" * 100)
print("STRUCTURAL 5: the test suites exist")
print("=" * 100)
tests_dir = ROOT / "tests"
check("f15 reference port", (tests_dir / "f15_reference.py").exists())
check("f15 real replay suite", (tests_dir / "f15_replay_real.py").exists())
check("f15 synthetic suite", (tests_dir / "f15_synthetic.py").exists())
check("f15 port validation suite", (tests_dir / "f15_port_validation.py").exists())
check("f15 structural suite", (tests_dir / "f15_structural.py").exists())
check("kotlin unit tests with the paid-wrong-side regression",
      "paidWrongSideRegression" in test_kt and "reconnectPreservesGoalsAcrossReKey" in test_kt and
      "fullTimeTrailingWindowAccepts28Seconds" in test_kt)
check("native regression runner", (tests_dir / "run_native_tests.sh").exists())

print()
print(f"STRUCTURAL SUITE: {'FAILURES=' + str(len(failures)) if failures else 'ALL PASS'}")
return_code = 1 if failures else 0
if failures:
    print("  failed:", failures)
sys.exit(return_code)
