# PeerLink hotspot + cleanup pass

> Historical baseline notes. See `F10_FINAL_REPORT.md` for the current combined
> source tree, including the corrected STUN/parser files, PeerCoin lifecycle,
> transport latency fixes, and validation limits.

This tree is based on the uploaded `Peerlink-main` project and focuses on the three requested changes: hotspot reliability first, removal of the invalid relay fallback, and conservative dead-code/UI cleanup.

## 1. Hotspot-owner / Wi-Fi client reliability

- Added `LanPathResolver` to select the **peer-reachable LAN path** instead of the first private IPv4 on the phone.
- The selected path records the local IPv4, prefix length, interface name/index, and (when Android exposes one) the exact `ConnectivityManager.Network`.
- Hotspot owners no longer require a `TRANSPORT_WIFI` `Network` object. This is important because Android/OEM SoftAP interfaces are frequently absent from `ConnectivityManager` even though the kernel can route through them.
- Discovery now uses the actual subnet prefix instead of assuming `/24` for directed broadcasts.
- Discovery binding falls back safely to wildcard on OEMs that reject Java binding to the SoftAP address; the data plane is still pinned later.
- The LAN path is re-resolved after the peer is known and again immediately before VPN startup, so cellular `10.x` addresses cannot accidentally become the session identity when the peer is on SoftAP/Wi-Fi.
- The native peer UDP socket source-binds to that exact local LAN address and is pinned to the exact kernel interface with `IP_UNICAST_IF` whenever an interface index is available.
- Rebinds re-apply the same interface pin.
- Added a native bidirectional probe/ACK (`0xFC`/`0xFD`) before the session is reported as running. Discovery/pairing and a working tunnel are now separate states.
- The probe runs off the Android service main thread and allows up to 60 seconds, avoiding ANRs and reducing first-run failures while the second phone is granting VPN consent.
- The foreground notification says `PeerLink Starting` until the data path is verified.

## 2. Relay fallback removed

The old relay shortcut has been removed from the native and Kotlin runtime paths.

The PCAPdroid reference captures show eFootball relay gameplay is carried in independent DTLS sessions to Konami `turn.konami.com` infrastructure. Copying one player's DTLS ciphertext over Wi-Fi and injecting it into the other player's different DTLS association is not a valid replacement for the relay.

Removed relay-specific classification/state, DNS relay-pool logic, relay tunnel flags/counters, relay source-rewrite injection, duplicate relay forwarding, and suppression of legitimate relay return packets. If eFootball falls back to its real relay, PeerLink now leaves that traffic on the ordinary passthrough path instead of attempting to replace it.

The fabricated direct-P2P/STUN path was preserved rather than redesigned in this cleanup.

## 3. Dead code / UI cleanup

Removed unreachable or superseded subsystems, including:

- old Wi-Fi Direct configuration/UI
- QR pairing/capture helpers
- Bluetooth credential exchange / Bluetooth peer transport
- native Bluetooth parallel data-plane branches and statistics
- obsolete match overlay/OCR/forfeit/signaling stack
- old `ApexSettingsScreen`, discovery animation UI, and the legacy ~1,900-line `CommonComponents` UI monolith
- obsolete accessibility resource left behind by the old Bluetooth-MAC UI
- unused Google downloadable-font resources/dependency
- unused ConstraintLayout dependency
- obsolete hand-written Prime/Shizuku ADB client/key/protocol/message stack that the live bootstrap had already replaced with `libadb-android`
- deprecated `AdbSpake2` stub that was no longer called

The active UI remains `PeerLinkScreen` plus the still-reachable unlock/Prime setup surfaces in `MainActivity`.

## 4. Intentionally retained

`TunnelEngine` is retained because `PassthroughBridgeEngine` still instantiates its bridge mode to provide eFootball's ordinary Internet TCP/UDP passthrough while the native backend owns the peer tunnel. Deleting that file wholesale would risk breaking login, DNS, authentication and matchmaking.

Prime Mode / GodMode pairing and call-blocking services are also retained because they are still reachable from the current UI/manifest.

## Validation performed

- Native `peerlink_backend.cpp`: host C++17 syntax/type check passes. The only emitted warnings are the known desktop-JDK JNI `AttachCurrentThread` signature difference; there are no C++ errors.
- Kotlin: parser pass reports no Kotlin syntax errors and no unresolved references to any removed/renamed PeerLink symbols. A complete Kotlin/Android compile cannot be performed without the Android/Compose classpath.
- Android manifest and all remaining XML resources parse successfully.
- Every manifest component still has a corresponding Kotlin class declaration.
- Repository scans show no remaining runtime relay-fallback symbols or references to the deleted UI/Wi-Fi Direct/Bluetooth/match classes.
- Native/Kotlin stats layouts remain aligned at 11 fields.

## Build-environment limitation

A full Gradle Android build was attempted, but this environment does not have the required Gradle 8.11.1 distribution cached and has no network access to download `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`. Therefore this package is source-validated but **not claimed to be APK-build-verified in this environment**.

## Size of cleanup

Uploaded source tree: approximately 30,355 Kotlin/Java/C/C++/header lines across 70 source files.

Cleaned tree: approximately 18,499 lines across 45 source files.

Reduction: approximately 11,856 source lines (39.1%).

# Strategic relay / direct-bypass blocking (gameplay regression fix)

## Problem

Match testing showed the current build playing badly: the tunneled counter
stayed in single digits while the passthrough capture filled with gameplay
packets, with occasional relay matches. Capture analysis found two failure
modes, both of which bypass the fabricated-IP tunnel:

1. **Konami relay fallback.** The only confirmed relay capture in the user's
   GitHub `Pcsp` pcaps (`PCAPdroid_06_Jan_14_29_23.pcap`) carries 44,140
   gameplay packets (42.9 pps) to `34.155.120.34:5735` — the only
   gameplay-rate public flow in that capture. `tests/evidence_f15_port_validation.json`
   records the same signature (`port_pair [5735, 46220]`, `relay_suspected: true`).
   No direct-P2P capture ever used remote port 5735, and 5735 sits outside
   Android's ephemeral range (32768–60999), so a peer socket cannot source it.
2. **Direct-to-real-address bypass.** In the m3 capture of a bad match, the
   game sent 17,114 gameplay packets direct to the peer's real socket
   (`10.7.6.86:62195`) while the tunnel was alive and delivering; only 134
   replies returned (0.8% — a black hole). The game learns the peer's real
   address from Konami's encrypted signaling (host candidates), which PeerLink
   cannot rewrite, so the only counter is to refuse that direct path.

## Fix (TunnelEngine)

Three block rules, active only while paired:

- **relay**: passthrough UDP with remote port 5735 to a public address is
  dropped in both directions.
- **bypass**: passthrough UDP aimed at `stableGameplayRemotePort` (learned
  from tunneled flows, now also learned from inbound tunnel traffic so the
  device that rarely sends tunneled packets arms too) is dropped in both
  directions, but only while a tunneled packet was seen within the last 30 s.
  If the tunnel dies for real, the rule disarms itself after 30 s and ordinary
  passthrough resumes.
- **turn-relay**: passthrough UDP to/from an IP learned for Konami's *constant*
  TURN/relay hostname (`turn.konami.com`) at gameplay speed — ≥10 packets
  within 3 s per address:port, either direction sharing one window. The name is
  fixed even when its IPs rotate, and the IPs are learned two independent ways:
  by resolving the name on a background thread while the engine runs (does not
  depend on the game re-querying DNS), and by sniffing the game's own DNS
  answers (same learner that already watches `pesam.stun.service.konami.net`,
  now extended to watch turn domains in both the IPv4 and IPv6 query paths).
  This is what covers TURN-relayed gameplay on arbitrary ports: relay on those
  addresses runs at 10–40 pps and is caught whatever port or framing it wears
  (including DTLS-wrapped relay), while ports 53/443 are always exempt as a
  belt-and-braces.

Safety properties:

- DTLS records (content type 0x14–0x17, version 0xfefd) are never blocked in
  either direction *outside* the turn-relay rule, so the ~2.1 s heartbeat to
  `turn.konami.com` cannot be caught even by a port collision. Inside the
  turn-relay rule the heartbeat survives on **rate** instead of framing: one
  packet per ~2.1 s can never reach 10 packets per 3 s, and the rate window is
  shared between directions only for the same address:port — a relayed
  gameplay stream is a different flow key than the heartbeat.
- TURN addresses learned via DNS go into their own set, never into
  `learnedStunServerIps`, so STUN interception can not swallow the heartbeat.
- Healthy sessions never classify anything as relay or bypass traffic, so the
  rules are no-ops there.
- Blocked packets are still recorded in the passthrough capture as evidence,
  annotated once via `PassthroughRecorder.note`, counted per rule, logged on
  the first block and every 1000th (`STRATEGIC-BLOCK`), and summarised every
  30 s (`STRATEGIC` stats line: RelayBlocked / BypassBlocked / TurnRelayBlocked /
  TurnIps / RemoteGamePort / TunnelAgeMs).

Not covered: relay traffic to addresses that neither use port 5735, nor belong
to `turn.konami.com`, nor match the peer's own game port (no capture of such a
signature exists in this repository; if one appears, the passthrough capture
will contain it for analysis). IPv6 passthrough has no block rules yet (all
observed failures were IPv4); IPv6 answers for the turn hostname are likewise
not tracked.
