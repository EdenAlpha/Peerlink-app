# PeerLink F27 — latency review and design refresh

Based on the published F26 commit `f382019b26bff2584e58105817ddb11e7f3c5cf2`. Compared against the user's `Peerlink-main (1) (4).zip`, which identifies itself as version 5.0.0 / code 5. F27 is 5.0.3-f27 / code 8.

## What the comparison established

Both versions use the native gameplay path, disable the legacy Kotlin jitter buffer, and send gameplay UDP without a deliberate pacing interval. Their native queue capacities and nonblocking gameplay send policy are the same. Finding a large queue capacity does not establish that packets were accumulating in it. No recording from the affected match was supplied, so the cause of the reported steady input delay remains unconfirmed.

## Confirmed blocking defects fixed

The transmitter and receiver used blocking mutex acquisition for diagnostic timestamps. The packet warning path also waited for the background logger's queue mutex. A preempted thread holding either mutex could delay forwarding even though that work was only diagnostic. F27 uses nonblocking acquisition, counts omitted diagnostics, and continues forwarding. Successful UDP sends still advance kernel timestamp IDs even when their diagnostic event is omitted. Failed sends clean up registered IDs without waiting a second time.

When both incoming data and outgoing timestamp notifications are ready, the receiver now handles the incoming batch first. Error-only events still drain promptly; batch sizes remain bounded.

`tests/f27_latency_regression.cpp` deliberately holds diagnostic locks while running production packet code over owned local sockets. Against F26, the first packet failed to arrive during a 200 ms observation period while the timestamp lock was held; warning and timestamp-update calls also blocked. Against F27, the first packet and the whole 32-packet burst arrived while the lock remained held, in FIFO order with unchanged payloads. All seven checks passed. The 200 ms period is a test timeout, not a measured delay on a phone, and these are not radio benchmarks.

## Wi-Fi power handling

The previous comment incorrectly equated a foreground VPN service with guaranteed effective low-latency Wi-Fi during gameplay. Android restricts this lock to the foreground app, with the screen on and a station connection. Before Android 14, F27 also requests the supported high-performance lock during an active session so switching to the game can retain a background Wi-Fi performance request. Both locks are released at session end. This can consume more battery on those older devices.

Android 14+ remaps high-performance requests to low-latency mode with the same restrictions, so F27 does not request a redundant fallback there. Acquisition logs describe a request, not proof of firmware behavior. Hotspot-host behavior and device support still need physical testing. See [Android's WifiManager contract](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY) and [Android Wi-Fi low-latency implementation](https://source.android.com/docs/core/connect/wifi-low-latency).

## Design

- Charcoal background, a warm light connection panel, and lime/violet accents distinguish the main action and status.
- Nearby players, traffic counters, match history and Prime shortcuts use open rows instead of repeated outlined boxes.
- The connected screen opens eFootball directly; disconnect still requires confirmation.
- A new rounded link mark is used in the app and adaptive launcher icon, including Android themed-icon support.
- Window/system-bar colors and activation-screen colors now match the Compose theme. Access and activation behavior is preserved.
- Layouts scroll; Prime shortcuts adapt to narrower widths and larger type. Instrumented UI checks cover player selection, navigation, disconnect cancellation and 150% text size.

## Build and validation

The current assembled source lives in `project/` in the GitHub repository. The F27 workflow builds it directly; older archives and patches are retained as history.

Host entry point: `python3 tests/run_checks.py`. The required Android checks are Kotlin unit tests and debug APK compilation. The workflow also attempts three Compose instrumentation tests on an Android 15 emulator as a best-effort UI smoke check. The generated AVD is normalized to a 2 GB userdata partition, and a hosted-emulator capacity problem does not block the APK or release. Screenshots and reports are workflow artifacts when the device is available; emulator rendering does not validate Wi-Fi latency.

For the next physical test, install F27 on both phones and try the same setup that felt delayed. Save connection logs from both phones immediately after a delayed match. Existing local processing, receive-wakeup and TUN-injection timing can help distinguish delay inside PeerLink from radio delivery or game-side processing. Comparing timestamps from different phones directly cannot establish one-way latency without clock synchronization.

No zero-lag guarantee or measured improvement in real-game response is claimed.
