#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/com/peerlink/app/ui/MainActivity.kt').read_text()
UI = (ROOT / 'app/src/main/java/com/peerlink/app/ui/PeerLinkScreen.kt').read_text()
PATH = (ROOT / 'app/src/main/java/com/peerlink/app/network/LanPathResolver.kt').read_text()
HOT = (ROOT / 'app/src/main/java/com/peerlink/app/network/HotspotPairing.kt').read_text()
NSD = (ROOT / 'app/src/main/java/com/peerlink/app/discovery/NsdDiscovery.kt').read_text()

checks = [
    ('Prime step lays multiple action children vertically',
     'action: @Composable ColumnScope.() -> Unit' in UI and
     'Column(\n                modifier = Modifier.fillMaxWidth().padding(start = 36.dp)' in UI),
    ('Discovery cannot select cellular as best LAN path',
     '.filter { candidate ->\n                candidate.path.isWifiTransport || isWifiLikeInterface(candidate.path.interfaceName)' in PATH),
    ('ConnectivityManager cellular networks are rejected',
     'NetworkCapabilities.TRANSPORT_CELLULAR' in PATH),
    ('Virtual Wi-Fi/SoftAP interfaces remain eligible',
     'nif.isVirtual && !isWifiLikeInterface(n)' in PATH),
    ('ccmni/rmnet are classified as cellular',
     'n.startsWith("ccmni")' in PATH and 'n.startsWith("rmnet")' in PATH),
    ('Discovery has a health signal', 'val isRunning: Boolean' in NSD),
    ('Idle discovery self-heals periodically',
     'DISCOVERY_HEALTH_INTERVAL_MS = 2_500L' in MAIN and 'startDiscoveryHealthLoop()' in MAIN),
    ('Healthy hotspot listener is not restarted every health tick',
     'existing?.isRunning == true && hotspotListenerKey == desiredKey' in MAIN),
    ('Hotspot listener binds the freshly resolved path',
     'localIp = freshPath.localIp' in MAIN and 'prefixLength = freshPath.prefixLength' in MAIN),
    ('No restart-app instruction remains for discovery bind failure',
     'Port busy — restart the app' not in HOT),
    ('Pairing target survives the socket-startup window',
     'if (socket != null)' in HOT and 'lastPairReqMs = 0L' in HOT),
]

bad = 0
for name, ok in checks:
    print(('PASS ' if ok else 'FAIL ') + name)
    bad += not ok
print(f'SUMMARY checks={len(checks)} failures={bad}')
raise SystemExit(1 if bad else 0)
