# F25 source review and changes

Date: 2026-09-06. Input: Peerlink-peerlink-f24-38.zip.

## Findings addressed

| Finding in the assembled source | Change and expected effect |
| --- | --- |
| CSV export held `timestamp_mutex` while building and formatting the full trace; peer transmission also needed it. | Export copies individual trace records through nonblocking slot guards. Formatting never owns the sender's timestamp mutex. Contention may omit diagnostics, with counts in the export; forwarding does not wait for an exporter. |
| Ring writers, live export, and late TX timestamps could access a reused event slot concurrently. | All event-field reads/writes use the same per-slot guard. A writer or timestamp update skips a busy slot. This addresses the identified race; it is not a proof that every possible race in the app is eliminated. |
| A successful datagram with an omitted trace event did not advance the mirrored kernel timestamp ID. | Every successful datagram advances the ID, preserving diagnostic correlation. A full pending timestamp map evicts one entry per send instead of clearing thousands at once. Error-queue draining has a bounded budget. |
| Native stop closed descriptors while input workers could still be blocked in `poll()`. Notification of queue waiters also had gaps. | An eventfd wakes all input waiters; cancellation synchronizes each condition-variable wait boundary. Descriptors close after workers join. A fatal input hangup cancels sibling workers. |
| `nativeHandle` was read by export, probe, telemetry, rebinding, and stop without common lifetime protection. | JNI users take a read lock; stop first cancels native waits, then takes the write lock before freeing the handle. The stats task and final telemetry drain have defined ownership. |
| Service stop detached resources while startup could still be constructing or verifying them. Destruction shut down the executor without native cleanup. | Startup and cleanup share one process-owned executor. Cancellation wakes verification. Destruction queues cleanup and preserves recovery preferences for system-driven teardown. A STOPPING UI state blocks an early reconnect. |
| The main UI ran unconditional polling, collected flows without lifecycle gating, and used repeated decorative animation/blur. | Visible-lifecycle polling/collection, a static main design, and disk-backed activity reads on the IO dispatcher. This reduces avoidable UI work; no on-device CPU/GPU measurement was performed. |
| The No Calls action directly started its service even though a permission-aware handler existed nearby. | The action now uses that handler. Permission callbacks re-check actual grants rather than accepting an empty result map. |
| Overlay state ignored a user switch, could be re-created by a queued refresh after stop, and used small fixed-position buttons. | Settings switch, active-session/permission checks, 48 dp controls, drag positioning with bounds checks, haptic/tap feedback, and accidental double-tap suppression. |
| Build verification secretly applied F24 and rewrote other tests. | A complete assembled source tree and direct Android CI workflow. F22 verification is now read-only, and the runner checks that app file hashes are unchanged by tests. |

### Correction to the initial build diagnosis

The top-level workflow looked like an F23 build, but `f22_runtime_performance_regression.py` indirectly applied F24, removed the marker's invalid counter reference, and rewrote the native smoke test. Therefore the original final build was not proven to have raw capture enabled or that marker compile error. F25 removes that indirection; the confirmed fixes above concern the assembled runtime code.

## User interface

The main screen uses consistent dark surfaces, an ice-blue accent, clearer text hierarchy, and a connection card that explains the next action. Nearby players have explicit connect actions. The Activity tab shows match/PeerCoin history and an empty state. Settings contains diagnostic export, the marker switch, and access to the existing Prime controls. Disconnect requires a confirmation. Small screens can scroll instead of squeezing a fixed-height layout.

The main screen does not invent a ping or signal-quality measurement. Session duration uses the VPN's monotonic start time, so returning to the app or changing the wall clock does not restart it.

Existing game packet formats, score-decoding logic, Prime capabilities, and the root-free connection approach are retained. Full raw PCAPNG capture remains disabled, as in F24. Existing bounded match-telemetry snapshots are retained; “raw capture off” does not mean all match telemetry has been removed.

## Verification

- 51 existing/current native checks passed against the production C++ backend with host Android/JNI adapters.
- 16 added native checks passed: idle pollers and queue waiters wake on cancellation; descriptors stay valid until workers finish; transmission works when diagnostics are omitted; timestamp IDs stay aligned; four concurrent producers complete 80,000 trace-write attempts across ring wrap while exports run.
- AddressSanitizer and UndefinedBehaviorSanitizer reported no errors in these host scenarios. LeakSanitizer was disabled because this container cannot enumerate process threads. ThreadSanitizer was not run.
- Kotlin token/manifest/JNI structural checks, cross-file AppState/enum/resource checks, retained discovery/Prime contracts, lightweight trace checks, trace-analysis tests, and the retained Python score-reference regression passed. These do not replace Kotlin compilation or testing the real Android UI.
- Four stale native assertions expected a 64-byte head / 88-byte telemetry record. The supplied F23/F24 code uses a 60-byte head / 84-byte record; assertions were aligned with that existing format. Styling/wording assertions were adjusted to the UI changes. Score decoder behavior was not altered to make these tests pass.
- The runner verifies app source hashes before and after the host tests. Current results and suite logs are in `verification/`.

The local Gradle attempt failed while downloading its distribution/dependencies. No APK was produced, no Android Gradle/Kotlin compilation passed here, and no emulator or phone UI rendering was verified. The included CI workflow is prepared but has not been executed on a connected repository in this session.

## Two-phone acceptance check

1. Build the APK with the included workflow and install the same build on both phones. Check the Play, Activity, Settings, and Prime screens, including a larger system font.
2. Check nearby discovery on a two-phone hotspot and on a shared Wi-Fi network. Pair, grant the VPN prompts, and verify that Connected appears only after the path check succeeds.
3. Cancel while pairing and while checking the path. Reconnect after STOPPING finishes. Confirm that Stop removes the VPN and marker and that reopening PeerLink preserves an active session's duration.
4. Enable/disable the marker, grant or deny overlay permission, move it, and mark a goal/full time. Export diagnostics; verify the manual timestamps and timing CSV. Check call protection with permission denied and then granted.
5. Play matched sessions before/after on the same phones/network. Compare felt jitter with the existing local-processing and receive/TUN-queue timing records and packet-drop counters. An improvement in host tests alone does not establish an improvement in game smoothness.

Radio interference, hotspot firmware/power saving, thermal throttling, the game's simulation, and device-specific behavior remain possible contributors. The identified code defects are addressed; zero jitter and the absence of all other bugs are not claimed.

## References used for Android lifecycle decisions

- [Android: State and Jetpack Compose](https://developer.android.com/develop/ui/compose/state)
- [Android: lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines)
- [Android NDK: JNI tips](https://developer.android.com/ndk/guides/jni-tips)
