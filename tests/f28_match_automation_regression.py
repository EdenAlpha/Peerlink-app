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

engine = 'app/src/main/java/com/peerlink/app/service/MatchAutomationEngine.kt'
overlay = 'app/src/main/java/com/peerlink/app/service/MatchMarkerOverlay.kt'
control = 'app/src/main/java/com/peerlink/app/service/MatchControlChannel.kt'
detector = 'app/src/main/java/com/peerlink/app/service/PrimeScreenScoreDetector.kt'
client = 'app/src/main/java/com/peerlink/app/godmode/PrimeClient.kt'
server = 'app/src/main/java/com/peerlink/app/godmode/PrimeServer.kt'
vpn = 'app/src/main/java/com/peerlink/app/service/PeerLinkVpnService.kt'
tracker = 'app/src/main/java/com/peerlink/app/core/MatchTracker.kt'

a = Path(engine).read_text()

has(engine, 'SIDE_PROMPT_PACKET_THRESHOLD = 200L', 'H/A prompt waits for 200 tunneled packets')
has(engine, 'GAMEPLAY_PPS_MIN = 24', 'gameplay floor is 24 pps')
has(engine, 'GAMEPLAY_PPS_MAX = 27', 'gameplay ceiling is 27 pps')
has(engine, 'CAPTURE_TRIGGER_PPS = 20', 'capture trigger is below 20 pps')
has(engine, 'CAPTURE_CANCEL_PPS = 24', 'capture recovery threshold is above 24 pps')
has(engine, 'AUTO_CAPTURE_DELAY_MS = 5 * 60_000L', 'automatic capture gate is exactly five minutes')
lacks(engine, '6 * 60_000', 'no six-minute gate remains')
lacks(engine, 'FT_ARMED', 'no separate FT armed state exists')
has(engine, 'CAPTURE_INTERVAL_MS = 250L', 'capture cadence is four frames per second')
has(engine, 'CAPTURE_MAX_MS = 20_000L', 'capture burst is capped at twenty seconds')
has(engine, 'DISCONNECT_CONFIRM_MS = 135_000L', 'disconnect confirmation is 135 seconds')
has(engine, 'currentPps < CAPTURE_CANCEL_PPS', 'capture loop watches live pps recovery')
has(engine, 'Channel<PrimeScreenScoreDetector.CapturedFrame>(capacity = 1)', 'capture and OCR use bounded pipeline')
has(engine, 'frames.tryReceive().getOrNull()?.recycle()', 'pipeline drops stale pending frames instead of growing memory')
has(engine, 'autoCandidateHits >= 2', 'automatic score needs two matching reads')
has(engine, 'PrimeClient.isPackageForeground(EFOOTBALL_PACKAGE)', 'manual FT verifies eFootball foreground')
lacks(engine, 'reason = "invalid_manual_ft"', 'foreground uncertainty cannot forfeit a match')
has(engine, 'if (scoreConfirmed) return', 'confirmed score blocks later disconnect settlement')

lacks(overlay, '"G"', 'goal button is absent from overlay')
has(overlay, 'button("H", "Choose Home"', 'Home selection button exists')
has(overlay, 'button("A", "Choose Away"', 'Away selection button exists')
has(overlay, 'button("FT", "Capture visible full-time score"', 'manual FT button exists after role lock')
has(overlay, 'durationMs = 320L, amplitude = 190', 'same-side conflict uses noticeable strong haptic')
has(overlay, 'main.postDelayed({ stopAttention() }, 3_000L)', 'initial H/A attention stops after three seconds')

has(control, 'private const val PORT = 17025', 'H/A metadata uses separate peer-control UDP port')
has(control, 'if (!isExpectedPeer(packet.address)) continue', 'control messages are restricted to paired peer')
has(control, 'sendRole(side: Side, confirmed: Boolean)', 'role claims and confirmations are exchanged')
has(control, 'sendReset()', 'same-side conflict can reset both peers')

has(server, 'SCORE_SCREENSHOT_CMD = "__scorecap_jpeg__"', 'Prime exposes score-only capture command')
has(server, 'SCORE_FRAME_MAX_WIDTH = 960', 'Prime bounds score frame size')
has(server, 'source.width * 0.22f', 'Prime crops horizontal non-score pixels')
has(server, 'source.height * 0.70f', 'Prime crops middle pitch pixels')
has(server, 'Bitmap.CompressFormat.JPEG, 88', 'Prime sends compact score JPEG')
has(client, 'captureScoreFrame', 'app uses compact Prime score frame')
has(detector, 'TextRecognition.getClient', 'bundled on-device recognizer is used')
has(detector, 'FinalScoreEvidence.isFinal(line.text)', 'automatic finality is verified independently of digit recognition')
lacks(detector, 'contains("online match")', 'generic match text cannot finalize a result')
has('app/src/main/java/com/peerlink/app/service/ScoreLaneReader.kt', 'it in 0..20', 'score values remain in plausible range')
has(detector, 'makeScoreOnlyComposite(frame)', 'incoming score-lane crop is integrated')
has(engine, 'if (score.finalScreen)', 'automatic submission requires final-screen evidence')
has(detector, 'ocrInFlight.compareAndSet(false, true)', 'single OCR task owns its bitmap through timeout')

has(vpn, 'MatchAutomationEngine.onNativeStats(stats)', 'automation observes counters outside packet hot path')
has(vpn, 'MatchAutomationEngine.start(applicationContext)', 'automation starts only with active native tunnel')
has(vpn, 'MatchAutomationEngine.stop()', 'automation stops with VPN')
has(tracker, 'fun confirmScreenScore(', 'screen result has a dedicated ledger commit path')
has(tracker, 'fun confirmNoContest(', 'unattributed disconnect has No Contest ledger path')

print(f'F28 match automation regression PASS {len(checks)}/{len(checks)}')
for label in checks:
    print('PASS', label)
