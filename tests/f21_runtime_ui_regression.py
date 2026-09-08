from pathlib import Path

root = Path('.')
manager = (root / 'app/src/main/java/com/peerlink/app/godmode/GodModeManager.kt').read_text()
models = (root / 'app/src/main/java/com/peerlink/app/godmode/PrimePerformanceModels.kt').read_text()
service = (root / 'app/src/main/java/com/peerlink/app/service/LanLinkForegroundService.kt').read_text()
call = (root / 'app/src/main/java/com/peerlink/app/service/CallMonitorService.kt').read_text()
main = (root / 'app/src/main/java/com/peerlink/app/ui/MainActivity.kt').read_text()
ui = (root / 'app/src/main/java/com/peerlink/app/ui/PeerLinkScreen.kt').read_text()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()

required_manager = [
    'fun guardianPulse()',
    'GUARDIAN_RECOVERY_COOLDOWN_MS',
    'PrimeLinkState.RECOVERING',
    'PrimeLinkState.DEGRADED',
    'LanLinkForegroundService.start(appContext)',
    'LanLinkForegroundService.stop(appContext)',
    'publishAction("graphics_scale", PrimeActionPhase.WORKING',
    'publishAction("art_optimize", PrimeActionPhase.WORKING',
    'publishAction("activate", PrimeActionPhase.WORKING',
    'publishAction("deactivate", PrimeActionPhase.WORKING',
    'Wireless-debugging advertisement lost; PrimeServer health checked separately',
]
for token in required_manager:
    assert token in manager, token

for token in ['enum class PrimeLinkState', 'enum class PrimeActionPhase', 'data class PrimeActionFeedback']:
    assert token in models, token

assert 'Dispatchers.IO' in service
assert 'GodModeManager.guardianPulse()' in service
assert '.service.LanLinkForegroundService' in manifest
assert 'android:foregroundServiceType="dataSync"' in manifest

for token in ['setCallBlockSafely', 'callPermissionLauncher', 'Manifest.permission.READ_PHONE_STATE', 'Manifest.permission.ANSWER_PHONE_CALLS']:
    assert token in main, token
assert 'setCallBlock = { on -> setCallBlockSafely(on) }' in main
assert 'return isRunning.get()' in call
assert 'putBoolean("apex_call_block_gameplay", false)' in call

for token in [
    'PrimeEngineStrip(',
    'PrimePicker(',
    'PrimeActionButton(',
    'PrimeInlineFeedback(',
    'collectIsPressedAsState()',
    'Engine online',
    'Applying ${scaleLabel(scale)} render scale',
    'label = "Recheck graphics compatibility"',
    'label = "Optimize eFootball runtime now"',
    'label = when {',
]:
    assert token in (ui + manager), token

assert ui.index('var renderScale by remember') < ui.index('LaunchedEffect(actionFeedback.changedAtMs)')
assert 'actionId = "capability_recheck",\n                                "Recheck' not in ui
assert 'actionId = "art_optimize",\n                            "Optimize' not in ui
assert 'actionId = "activate",\n                                when {' not in ui

print('F21 runtime/UI regression: PASS')
