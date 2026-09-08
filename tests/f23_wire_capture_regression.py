from pathlib import Path
import re

root = Path('.')
checks = []

def check(name, ok):
    checks.append((name, bool(ok)))
    print(('PASS ' if ok else 'FAIL ') + name)

cpp = (root/'app/src/main/jni/peerlink_backend.cpp').read_text()
hdr = (root/'app/src/main/jni/peerlink_backend.h').read_text()
models = (root/'app/src/main/java/com/peerlink/app/tunnel/NativeBackendModels.kt').read_text()
backend = (root/'app/src/main/java/com/peerlink/app/tunnel/NativePeerLinkBackend.kt').read_text()
service = (root/'app/src/main/java/com/peerlink/app/service/PeerLinkVpnService.kt').read_text()
main = (root/'app/src/main/java/com/peerlink/app/ui/MainActivity.kt').read_text()
tracker = (root/'app/src/main/java/com/peerlink/app/core/MatchTracker.kt').read_text()
reader = (root/'app/src/main/java/com/peerlink/app/core/MatchProtocolReader.kt').read_text()
data = (root/'app/src/main/java/com/peerlink/app/core/MatchData.kt').read_text()

# Goal-truth merge from the newer older-branch work.
check('goal detector has Sep-3 corroboration gate', 'unilateralSummaries' in reader and 'MIN_GOAL_PAYLOAD = 200' in reader)
check('goal detector carries rematch probe seeding', 'HandshakeSeed' in reader and 'pendingProbeForNextEpoch' in reader)
check('goal detector records STUN identity', 'localPlayerId' in reader and 'remotePlayerId' in reader)
check('ledger uses unresolved/calibrated attribution model', 'AttributionCalibration' in reader and 'MatchCalibration' in data)
check('selected protocol telemetry still feeds detector', 'pollMatchTelemetry' in backend and 'NativePacketEvent' in tracker)

# Full binary capture design.
check('legacy UDP timing trace disabled', 'constexpr size_t kUdpTraceCapacity = 0;' in cpp)
check('verbose packet diagnostics disabled', 'constexpr bool kPacketDiagnosticsEnabled = false;' in cpp)
check('two SPSC raw capture rings exist', 'RawCaptureRing raw_capture_out;' in cpp and 'RawCaptureRing raw_capture_in;' in cpp)
check('raw capture slot holds full VPN MTU packet', 'kRawCaptureMaxPacket = 2048' in cpp and 'kRawCaptureRingCapacity = 1024' in cpp)
check('writer thread is low priority', 'setpriority(PRIO_PROCESS, 0, 10)' in cpp)
check('PCAPNG section and enhanced packet blocks emitted', '0x0A0D0D0Au' in cpp and '0x00000006u' in cpp and 'LINKTYPE_RAW (101)' in cpp)
check('outbound TUN packets captured before classification', 'enqueue_raw_capture(state, true, tun_buffer.data(), packet_length, t0_ns);' in cpp)
check('inbound packets captured after successful TUN write', 'enqueue_raw_capture(state, false, job.bytes.data(), job.bytes.size(), write_start_ns);' in cpp)
check('capture overflow never blocks forwarding', 'Capture must never back-pressure gameplay' in cpp and 'raw_capture_queue_drops.fetch_add' in cpp)

m = re.search(r'void enqueue_raw_capture\(.*?\n\}', cpp, re.S)
enqueue_body = m.group(0) if m else ''
check('capture hot path contains no mutex/wait/file I/O', all(x not in enqueue_body for x in ['lock_guard', 'unique_lock', 'wait_for', 'fwrite', 'fflush', 'fsync']))

check('native flush API exists', 'nativeFlushRawCapture' in backend and 'nativeFlushRawCapture' in hdr and 'Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeFlushRawCapture' in cpp)
check('capture path passed into native start', 'rawCapturePath = config.rawCapturePath' in backend and 'jstring raw_capture_path' in cpp)
check('VPN stores latest binary capture privately', 'peerlink_wire_latest.pcapng' in service)
check('export writes PCAPNG to Downloads', 'peerlink_wire_$stamp.pcapng' in main and 'saveFileToDownloads' in main)
check('text hex wire duplication removed', 'logWireEvidence(packetWall, packet)' not in tracker)
check('old UDP CSV export removed', 'peerlink_udp_trace_' not in main)
check('capture integrity counters exported', all(x in models for x in ['queueDrops', 'oversizedDrops', 'writeErrors', 'fileBytes']))

failed = [name for name, ok in checks if not ok]
print(f'SUMMARY checks={len(checks)} failures={len(failed)}')
if failed:
    raise SystemExit('F23 regression failed: ' + '; '.join(failed))
