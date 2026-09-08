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
