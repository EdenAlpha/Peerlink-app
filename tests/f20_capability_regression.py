#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def check(name, cond):
    checks.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name)

def must(text, needle, label):
    check(label, needle in text)

def must_not(text, needle, label):
    check(label, needle not in text)

tracker = (ROOT / "app/src/main/java/com/peerlink/app/core/PrimeGameplayTracker.kt").read_text()
tunnel = (ROOT / "app/src/main/java/com/peerlink/app/tunnel/TunnelEngine.kt").read_text()
mem = (ROOT / "app/src/main/java/com/peerlink/app/godmode/PrimeMemoryDirector.kt").read_text()
auto = (ROOT / "app/src/main/java/com/peerlink/app/godmode/PrimeAutoTuner.kt").read_text()
manager = (ROOT / "app/src/main/java/com/peerlink/app/godmode/GodModeManager.kt").read_text()
models = (ROOT / "app/src/main/java/com/peerlink/app/godmode/PrimePerformanceModels.kt").read_text()
graphics = (ROOT / "app/src/main/java/com/peerlink/app/godmode/PrimeGraphicsController.kt").read_text()
ui = (ROOT / "app/src/main/java/com/peerlink/app/ui/PeerLinkScreen.kt").read_text()
nsd = (ROOT / "app/src/main/java/com/peerlink/app/discovery/NsdDiscovery.kt").read_text()
pair = (ROOT / "app/src/main/java/com/peerlink/app/network/HotspotPairing.kt").read_text()

# F17 transport must survive.
check("Discovery RX remains wildcard", "bind(InetSocketAddress(null as InetAddress?, DISCOVERY_PORT))" in nsd)
check("Discovery has LAN-pinned TX socket", "sendSocketPinnedToLan" in nsd and "InetSocketAddress(bindAddr, 0)" in nsd)
check("Pairing RX remains wildcard", "bind(InetSocketAddress(null as InetAddress?, PAIR_PORT))" in pair)
check("Pairing has LAN-pinned TX socket", "sendSocketPinnedToLan" in pair and "InetSocketAddress(bindAddr, 0)" in pair)

# Gameplay detector is driven by real tunneled UDP in both directions.
check("Gameplay tracker uses sustained bidirectional P2P", "MIN_PPS_EACH_DIRECTION = 8" in tracker and "MATCH_GRACE_MS = 65_000L" in tracker)
check("IPv4/IPv6 outbound UDP feeds tracker", tunnel.count("PrimeGameplayTracker.noteOutboundPeerUdp()") >= 2)
check("IPv4/IPv6 inbound UDP feeds tracker", tunnel.count("PrimeGameplayTracker.noteInboundPeerUdp()") >= 2)
check("No TCP tracker feed", "if (parsed.protocol == PacketParser.PROTOCOL_UDP)" in tunnel)

# Prime Memory behavior.
check("Instant vault command exists", "am freeze --sticky $GAME" in mem)
check("Immediate unvault command exists", "am unfreeze --sticky $GAME" in mem)
check("Live match blocks vault", "!matchProtected && instantVaultEnabled()" in mem)
check("Return Shield is explicit", "Return Shield, no freeze" in mem)
check("Temporary foreground app is protected", "uid == foregroundUid" in mem and "temporaryForegroundPkg" in mem)
check("PeerLink UID is protected", "uid == peerLinkUid" in mem)
check("Game UID is protected from Cannon", "uid == gameUid" in mem)
check("Only cached-ish apps are reclaim candidates", "if (adj < 500)" in mem and "if (proc.adj < 800)" in mem)
check("Memory Cannon supports compact then kill", "am compact $profile" in mem and "am kill ${shellQuote(pkg)}" in mem)
check("ZRAM compression ratio is measured", "/sys/block/zram0/mm_stat" in mem and "orig.toFloat() / used.toFloat()" in mem)
check("Memory PSI participates in pressure", "/proc/pressure/memory" in mem and "psiSomeAvg10" in mem)

# Manual graphics controls remain user-owned.
for scale in ["1.00", "0.90", "0.85", "0.80", "0.75", "0.70"]:
    check(f"Render scale {scale} exposed", f'"{scale}"' in ui and f'"{scale}"' in manager)
check("No unsupported 0.95 user scale", '"0.95"' not in manager and '"0.95"' not in ui)
check("Auto and Manual graphics modes exist", "PrimeGraphicsMode.AUTO" in ui and "PrimeGraphicsMode.MANUAL" in ui)
check("User FPS targets are 30/60", "30 FPS" in ui and "60 FPS" in ui and "getTargetFps" in manager)

# F20: SDK/help text must not be treated as proof of graphics support.
must_not(manager, 'gameHelp.output.contains("downscale"', "No help-text downscale gate")
must(models, "gameDownscale = false", "Coarse graphics starts unverified")
must(models, "PrimeGraphicsBackend.PENDING", "Pending graphics backend exists")

# Modern Android backend: mutate only a scoped custom override and verify readback.
must(graphics, 'cmd game set --downscale 0.9 $GAME', "Modern custom probe exists")
must(graphics, 'cmd game list-configs $GAME', "Modern config readback exists")
must(graphics, 'dumpsys game', "GameManager ingestion readback exists")
must(graphics, 'cmd game set --downscale disable $GAME', "Modern custom scaling is explicitly disabled after probing")
must(graphics, 'cmd game mode custom $GAME', "Custom mode activation exists")

# Performance + graphics can coexist where the phone really supports it.
must(graphics, 'cmd game set --mode 2 --downscale ${scaleToken(scale)} $GAME', "Performance-mode scaling exists")
must(graphics, 'cmd game mode performance $GAME', "Performance mode activation exists")

# Android 12 / early 13 legacy backend: read back platform compat, don't trust exit code.
must(graphics, 'cmd game downscale 0.9 $GAME', "Legacy probe exists")
must(graphics, 'dumpsys platform_compat', "Legacy readback exists")
for change_id, label in [
    ("168419799L", "DOWNSCALED"),
    ("182811243L", "DOWNSCALE_90"),
    ("189969734L", "DOWNSCALE_85"),
    ("176926753L", "DOWNSCALE_80"),
    ("189969779L", "DOWNSCALE_75"),
    ("176926829L", "DOWNSCALE_70"),
]:
    must(graphics, change_id, f"{label} change ID present")
must_not(graphics, "am compat enable", "No raw am-compat production-app fallback")

# Official game_overlay fallback: backup, write, read back, GameManager verify, restore.
must(graphics, 'device_config get game_overlay $GAME', "game_overlay backup/readback exists")
must(graphics, 'device_config put game_overlay $GAME', "game_overlay write exists")
must(graphics, 'device_config delete game_overlay $GAME', "Null game_overlay restore exists")
must(graphics, "restoreOverlayValue(original)", "Probe restores exact overlay state")
must(graphics, "mergeOverlay(current, 2, scaleToken(scale))", "OEM overlay merge preserves unknown fields")
must(graphics, "KEY_OVERLAY_BACKUP", "Persistent overlay backup exists")

# No graphics probing or scale mutation during protected multiplayer.
check("Graphics probe and apply both guard live match", graphics.count("PrimeGameplayTracker.isMatchProtected()") >= 2)

# Auto Tuner delegates to the verified backend rather than guessing commands.
must(auto, "applyScaleOverride(scale)", "Auto Tuner delegates scale application")
must_not(auto, "cmd game downscale", "Auto Tuner contains no direct legacy command")
must_not(auto, "cmd game set --downscale", "Auto Tuner contains no direct modern command")
must(manager, "graphicsController?.applyScale(", "Manager wires verified graphics controller")

# Actual per-game state drives capability classification.
must(manager, 'executePrimeShellChecked("cmd game list-modes $EFOOTBALL_PKG")', "Per-game mode query exists")
must(manager, "gameDownscale = graphics.verified", "Only verified graphics is exposed")
must(manager, "graphicsBackend = graphics.backend", "Verified backend is surfaced")
must(manager, "recheckPerformanceCapabilities()", "User can force a safe recheck")

# Frame tuner remains measured and manual mode remains authoritative.
check("SurfaceFlinger real frame sampling drives tuner", "dumpsys SurfaceFlinger --timestats" in auto and "averageFPS" in auto)
check("Auto only steps down after repeated bad samples", "badSamples >= 2" in auto and "nextLowerScale" in auto)
check("Manual graphics mode prevents auto override", "graphicsMode() == PrimeGraphicsMode.AUTO" in auto)

# CPU/ART and F19 restore fix must survive F20.
check("Performance Game Mode is capability gated", "gamePerformanceMode" in manager and "cmd game mode performance" in manager)
check("ART profile/full optimizer exists", "speed-profile" in manager and '"speed"' in manager and "cmd package compile" in manager)
check("F19 system-derived standby bucket guard retained", "bucket >= 10" in manager and "standby priority already optimal" in manager)

# UI explicitly differentiates pending from verified-unsupported.
must(ui, "PrimeGraphicsBackend.PENDING", "Pending graphics UI exists")
must(ui, "Recheck graphics compatibility", "Graphics recheck button exists")
must(ui, "Verified backend", "Verified backend shown in UI")
for title in ["Prime Memory", "Prime Graphics", "Prime CPU", "Prime Auto Tuner"]:
    check(f"UI section {title}", f'title = "{title}"' in ui)
check("Auto tuner has independent user toggle", "isAutoTunerEnabled" in manager and "Adaptive tuning" in ui)
check("UI preserves non-root RAM honesty", "cannot truly pin another app in RAM" in ui)

failed = [name for name, ok in checks if not ok]
print(f"SUMMARY checks={len(checks)} failures={len(failed)}")
if failed:
    print("FAILED: " + ", ".join(failed))
    sys.exit(1)
print("F20 full capability-engine regression checks passed")
