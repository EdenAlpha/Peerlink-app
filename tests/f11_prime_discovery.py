#!/usr/bin/env python3
"""Source-level regression gates for F11 Prime/discovery/session UX.

The Android SDK/Gradle distribution is not available in this workspace, so
these checks complement (not replace) an Android build. They encode the exact
bugs fixed in F11 and fail closed if a later edit reintroduces them.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


checks = []


def check(name: str, condition: bool) -> None:
    checks.append((name, bool(condition)))
    print(("PASS" if condition else "FAIL"), name)


screen = read("app/src/main/java/com/peerlink/app/ui/PeerLinkScreen.kt")
activity = read("app/src/main/java/com/peerlink/app/ui/MainActivity.kt")
manager = read("app/src/main/java/com/peerlink/app/godmode/GodModeManager.kt")
pair_service = read("app/src/main/java/com/peerlink/app/godmode/GodModePairingService.kt")
prime_server = read("app/src/main/java/com/peerlink/app/godmode/PrimeServer.kt")
prime_client = read("app/src/main/java/com/peerlink/app/godmode/PrimeClient.kt")
prime_auth = read("app/src/main/java/com/peerlink/app/godmode/PrimeAuth.kt")
starter = read("app/src/main/java/com/peerlink/app/godmode/shizuku/PrimeShizukuStarter.kt")
starter_cpp = read("app/src/main/jni/starter.cpp")
discovery = read("app/src/main/java/com/peerlink/app/discovery/NsdDiscovery.kt")
pairing = read("app/src/main/java/com/peerlink/app/network/HotspotPairing.kt")
vpn = read("app/src/main/java/com/peerlink/app/service/PeerLinkVpnService.kt")
lan_service = read("app/src/main/java/com/peerlink/app/service/LanLinkForegroundService.kt")
app_state = read("app/src/main/java/com/peerlink/app/core/AppState.kt")
tunnel_engine = read("app/src/main/java/com/peerlink/app/tunnel/TunnelEngine.kt")

# Pairing setup must use durable proof, never a transient enum state.
check("Prime paired UI uses durable setup evidence",
      "val isPaired = snap.bootstrapped || snap.pairedTrusted" in screen)
check("Prime paired UI does not treat every non-NOT_PAIRED state as paired",
      "state != GodModeManager.State.NOT_PAIRED" not in screen)
check("Developer Options action remains present", "Open Developer Options" in screen)
check("In-app pairing accepts CODE or PORT:CODE", "123456 or 45678:123456" in screen)
check("Pairing notification deep-links to Prime setup",
      "MainActivity.ACTION_OPEN_PRIME_SETUP" in pair_service and
      "override fun onNewIntent" in activity and
      "showPrimeSetup = true" in activity)
check("Failed pairing attempts remain alive",
      "if (success) mainHandler.postDelayed({ stopSelf() }" in pair_service)
check("Pairing listener has no fixed discovery lifetime", "LISTENER_LIFETIME" not in pairing)
check("Pair attempt still has a bounded timeout", "PAIRING_ATTEMPT_TIMEOUT" in pairing)

# Discovery identity/lifecycle.
check("Discovery peers are keyed by endpoint", "ConcurrentHashMap<String, SeenPeer>" in discovery)
check("Discovery carries a stable device id", 'put("device_id", deviceId)' in discovery)
check("Same display names are not rejected", "peerName == userName" not in discovery)
check("Discovery source freshness is merged by peer IP", "peerDiscoverySources" in activity)
check("Discovery announces permission and Wi-Fi wait states",
      "WAITING_FOR_PERMISSION" in activity and "WAITING_FOR_WIFI" in activity)
check("Hotspot listener is restarted before a delayed pair request",
      "startHotspotPairingListener()" in activity and "postDelayed" in activity)

# Truthful, cancellable peer connection lifecycle.
for phase in ("PAIRING", "STARTING_TUNNEL", "VERIFYING_PATH", "ACTIVE", "ERROR"):
    check(f"Session phase {phase} exists", phase in app_state)
check("Connected is set only after bidirectional path verification",
      vpn.index("verifyPeerPath") < vpn.index("AppState.isRunning.set(true)"))
check("Connection verification is shown to the user", "Making sure both phones can exchange game traffic." in screen)
check("Connection attempt is cancellable", "ConnectionProgressCard" in screen and "Cancel" in screen)
check("Cancelling pairing does not stop continuous discovery", "fun cancelPendingPairing" in pairing)
check("VPN permission denial exits the pending state",
      "VPN permission was not granted" in activity and "PeerSessionPhase.ERROR" in activity)
check("A vanished selected peer fails instead of hanging forever",
      "the selected player left the local link" in pairing)
check("VPN interface creation failure is user-visible",
      "Android could not create the VPN interface" in vpn)

# Prime command safety and truthfulness.
activation = manager[manager.index("private suspend fun runGodModeSequence"):]
check("No process-killing RAM clear command", "am kill-all" not in activation)
check("No cache-trimming pseudo optimization", "pm trim-caches" not in activation)
check("No hidden Wi-Fi suspend setting in new activation",
      "settings put global wifi_suspend_optimizations_enabled" not in activation)
check("No deprecated Wi-Fi sleep policy in new activation",
      "settings put global wifi_sleep_policy" not in activation)
check("No captive-portal mutation in new activation",
      "settings put global captive_portal_mode" not in activation)
check("No global Wi-Fi band mutation in new activation",
      "cmd wifi set-wifi-bands" not in activation)
check("Legacy changed settings remain restorable",
      "settingsRestoreCommand(\"wifi_sleep_policy\"" in manager and
      "settingsRestoreCommand(\"captive_portal_mode\"" in manager)
check("UI does not claim RAM pinning", "cannot truly pin another app in RAM" in screen)
check("Fake Network Prime tool is removed", 'Tool("network"' not in screen)
check("Fake local-only SettingRow switches are removed", "SettingRow(" not in screen)

# Prime loopback server must be authenticated and bounded.
check("Prime uses a per-install authentication token",
      "SecureRandom" in prime_auth and 'put("token", token)' in prime_client)
check("Token reaches the app_process server", "--token=" in starter and "--token" in starter_cpp)
check("Prime server health protocol remains versioned and authenticated",
      "prime_ok_v6" in prime_server and "version in 2..6" in prime_client and
      "constantTimeEquals(authToken, suppliedToken)" in prime_server)
check("Prime request input is bounded", "MAX_REQUEST_BYTES" in prime_server and "readUtf8LineLimited" in prime_server)
check("Prime command output is bounded", "MAX_OUTPUT_BYTES" in prime_server and "readProcessOutputLimited" in prime_server)
check("Forgetting pairing shuts down the old server", "PrimeClient.shutdown()" in manager)
check("Forgetting pairing rotates the token", "PrimeAuth.rotate(appContext)" in manager)

# Supported gameplay latency API is scoped to the VPN lifetime.
check("VPN acquires Android low-latency Wi-Fi mode", "WIFI_MODE_FULL_LOW_LATENCY" in vpn)
check("VPN releases its low-latency Wi-Fi lock", "WIFI_MODE_FULL_LOW_LATENCY lock released" in vpn)
check("Reboot-long Prime server holds no Wi-Fi lock", "createWifiLock" not in prime_server)

# Startup and radio ownership must never obstruct or unexpectedly disconnect.
check("Startup never opens Wi-Fi Settings automatically",
      "Settings.ACTION_WIFI_SETTINGS" not in activity)
check("Auto Wi-Fi runs through the background Prime scope",
      manager.index("fun enableRadiosForSession") < manager.index("scope.launch", manager.index("fun enableRadiosForSession")) <
      manager.index("PrimeClient.isAlive", manager.index("fun enableRadiosForSession")))
check("Activity destruction never restores or disables Wi-Fi",
      "restoreRadiosIfNeeded" not in activity and "_auto_prev_wifi" not in activity)
check("Foreground-service destruction preserves user radio state",
      "restoreRadiosIfNeeded" not in lan_service)
check("No automatic Wi-Fi disable command remains", 'svc wifi disable' not in manager)
check("Battery protection is an explicit user action",
      "openBatterySettings" in screen and "openBatteryProtectionSettings" in activity and
      "requestBatteryOptimisationExemptionOnce" not in activity and
      "battery_opt_dialog_shown" not in activity)

# F11 uses direct native gameplay delivery; the old UI switch affected no live
# native packet and must not imply otherwise.
check("Misleading jitter-buffer UI is removed", "Jitter Buffer" not in screen)
check("Legacy Kotlin jitter buffer is hard-disabled", "val jitterEnabled = false" in tunnel_engine)
check("UI exposes connection log export", "onExportLogs()" in screen)

failures = [name for name, ok in checks if not ok]
print(f"SUMMARY checks={len(checks)} failures={len(failures)}")
if failures:
    sys.exit(1)
