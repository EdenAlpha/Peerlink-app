"""Read-only F22 runtime checks on the already assembled source tree.
F25 removes the old source-mutating F24 install/CI-compatibility hooks.
"""
from pathlib import Path

root = Path('.')
manager = (root/'app/src/main/java/com/peerlink/app/godmode/GodModeManager.kt').read_text()
memory = (root/'app/src/main/java/com/peerlink/app/godmode/PrimeMemoryDirector.kt').read_text()
auto = (root/'app/src/main/java/com/peerlink/app/godmode/PrimeAutoTuner.kt').read_text()
service = (root/'app/src/main/java/com/peerlink/app/service/LanLinkForegroundService.kt').read_text()
starter = (root/'app/src/main/jni/starter.cpp').read_text()

checks = {
    'manager-owned 2s guardian loop': 'GUARDIAN_PULSE_MS = 2_000L' in manager and 'startGuardianLoop()' in manager,
    'interactive actions recover daemon': 'ensurePrimeForInteractiveAction' in manager and 'Reconnecting Prime engine' in manager,
    'graphics does not claim applied while offline': 'saved, but the Prime engine could not reconnect' in manager,
    'foreground gameplay drives autotuner': 'gameForeground() || PrimeGameplayTracker.isMatchProtected()' in auto,
    'memory exports game foreground': 'val gameForeground: Boolean = false' in memory,
    'memory uid polling throttled': 'UID_SAMPLE_MS = 1_500L' in memory and 'MEMORY_SAMPLE_MS = 4_000L' in memory,
    'guardian lifecycle is exported': 'Foreground guardian service created' in service and 'Foreground guardian service destroyed' in service,
    'native daemon ignores SIGHUP': 'signal(SIGHUP, SIG_IGN)' in starter and 'PR_SET_PDEATHSIG' in starter,
    'native daemon keeps diagnostics': '/data/local/tmp/peerlink_prime.log' in starter,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
if failed:
    raise SystemExit('F22 regression failed: ' + ', '.join(failed))
print('F22 runtime/performance regression: PASS')
