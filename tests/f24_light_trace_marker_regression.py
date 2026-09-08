from pathlib import Path

checks = []

def has(path, needle, label):
    text = Path(path).read_text()
    if needle not in text:
        raise AssertionError(f'{label}: missing {needle!r} in {path}')
    checks.append(label)

def lacks(path, needle, label):
    text = Path(path).read_text()
    if needle in text:
        raise AssertionError(f'{label}: unexpectedly found {needle!r} in {path}')
    checks.append(label)

cpp = 'app/src/main/jni/peerlink_backend.cpp'
native = 'app/src/main/java/com/peerlink/app/tunnel/NativePeerLinkBackend.kt'
vpn = 'app/src/main/java/com/peerlink/app/service/PeerLinkVpnService.kt'
ui = 'app/src/main/java/com/peerlink/app/ui/MainActivity.kt'
manifest = 'app/src/main/AndroidManifest.xml'
overlay = 'app/src/main/java/com/peerlink/app/service/MatchMarkerOverlay.kt'

has(cpp, 'constexpr size_t kUdpTraceCapacity = 32768;', 'compact UDP timing ring enabled')
has(cpp, 'constexpr bool kPacketDiagnosticsEnabled = false;', 'verbose packet text diagnostics remain off')
has(cpp, 'const bool capture_ok = false;', 'raw PCAP writer hard-disabled')
lacks(cpp, 'enqueue_raw_capture(state, true, tun_buffer.data(), packet_length, t0_ns);', 'outbound packet bytes not captured')
lacks(cpp, 'enqueue_raw_capture(state, false, job.bytes.data(), job.bytes.size(), write_start_ns);', 'inbound packet bytes not captured')
has(cpp, '[F24-TRACE] rawBytes=off udpTiming=on', 'native startup declares light trace mode')
has(native, 'return@read nativeDumpUdpTrace(handle)', 'native timing CSV dump restored')
has(vpn, 'private var lastNativeUdpTraceDump: String = ""', 'VPN timing cache restored')
has(vpn, 'fun dumpNativeUdpTrace(): String {', 'VPN timing export restored')
has(vpn, 'rawCaptureFile = null', 'raw file creation disabled')
has(vpn, 'lastRawCapturePath = ""', 'raw backend path disabled')
has(vpn, 'MatchMarkerOverlay.show(this)', 'overlay tied to active VPN')
has(vpn, 'MatchMarkerOverlay.hide()', 'overlay removed on VPN stop')
has(vpn, 'Enable match marker', 'notification exposes overlay permission action')
has(ui, 'peerlink_udp_trace_$stamp.csv', 'export writes timing CSV')
has(ui, 'rawBytes=off', 'export records raw-byte-disabled mode')
lacks(ui, 'peerlink_wire_$stamp.pcapng', 'PCAPNG export removed')
has(manifest, 'android.permission.SYSTEM_ALERT_WINDOW', 'overlay permission declared')
lacks(overlay, 'markerButton("G", "GOAL")', 'obsolete goal marker removed')
has(overlay, 'Mode.SIDE_CHOICES', 'overlay now supports H/A selection')
has(overlay, 'Mode.FULL_TIME', 'overlay retains manual FT fallback')
lacks(overlay, 'while (', 'overlay has no polling loop')

print(f'F24 regression PASS {len(checks)}/{len(checks)}')
for label in checks:
    print('PASS', label)
