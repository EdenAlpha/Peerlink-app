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
backend = 'app/src/main/jni/peerlink_backend.cpp'
models = 'app/src/main/java/com/peerlink/app/tunnel/NativeBackendModels.kt'
native_kt = 'app/src/main/java/com/peerlink/app/tunnel/NativePeerLinkBackend.kt'

has(engine, 'SIDE_PROMPT_PACKET_THRESHOLD = 200L', 'H/A prompt waits for 200 tunneled packets')
has(engine, 'GAMEPLAY_PPS_MIN = 24', 'gameplay floor is 24 pps')
has(engine, 'GAMEPLAY_PPS_MAX = 27', 'gameplay ceiling is 27 pps')
has(engine, 'AUTO_CAPTURE_DELAY_MS = 0L', 'no five-minute wall-clock capture gate')
has(engine, 'GAMEPLAY_ARM_SAMPLES = 15', 'capture waits for 15s of 24-27pps kickoff flow')
has(engine, '54B ignored — waiting for first 24-27pps kickoff and H/A lock', 'pre-match 54B does not start capture')

# F32 Path A: 54B tail trigger
has(engine, 'PATH_B_TRIGGER_PPS = 9', 'Path B deep-collapse trigger is below 9 pps')
has(engine, 'ZERO_PPS_CONFIRM_MS = 3_000L', 'Path A expects the cliff within 3 seconds')
has(engine, 'PAUSE_WINDOW_MS = 1_500L', 'Path A pause window is 1.5 seconds')
has(engine, 'PATH_A_TAIL_MS = 7_000L', 'Path A tail budget is 7 seconds')
has(engine, 'PATH_B_MAX_MS = 10_000L', 'Path B budget is 10 seconds')
has(engine, 'ZERO_PPS_THRESHOLD = 1', 'cliff is read at or below 1 pps')
has(engine, 'CaptureMode.PATH_A_WATCH', 'Path A watch mode exists')
has(engine, 'CaptureMode.PATH_A_PAUSE', 'Path A pause mode exists')
has(engine, 'CaptureMode.PATH_A_TAIL', 'Path A tail mode exists')
has(engine, 'CaptureMode.PATH_B', 'Path B mode exists')
has(engine, 'smallPacketSeenThisMatch = true', '54B tail latches the small-packet marker')
has(engine, 'signal <= ZERO_PPS_THRESHOLD', 'cliff confirmation polls the live feed')
has(engine, '54B was noise', 'a 54B burst without a cliff is abandoned after the pause window')
has(engine, '54B tail detected', 'the 54B signal logs its Path A entry')
has(engine, 'Delayed cliff reached 0pps', 'a delayed cliff resumes paused capture')
has(engine, '54B inside a Path-B burst is deliberately ignored', 'Path B ignores 54B that appears mid-burst')

# Old F31 20pps/20s trigger fully removed
lacks(engine, 'CAPTURE_TRIGGER_PPS', 'old 20pps trigger constant is gone')
lacks(engine, 'CAPTURE_CANCEL_PPS', 'old 24pps cancel constant is gone')
lacks(engine, 'CAPTURE_MAX_MS', 'old 20s burst cap is gone')
lacks(engine, 'captureAttemptLatched', 'old one-shot latch is gone')
has(engine, 'CAPTURE_INTERVAL_MS = 250L', 'capture cadence is four frames per second')
has(engine, 'DISCONNECT_CONFIRM_MS = 135_000L', 'disconnect confirmation is 135 seconds')
has(engine, 'Channel<PrimeScreenScoreDetector.CapturedFrame>(capacity = 1)', 'capture and OCR use bounded pipeline')
has(engine, 'frames.tryReceive().getOrNull()?.recycle()', 'pipeline drops stale pending frames instead of growing memory')
has(engine, 'autoCandidateHits >= 2', 'walking score needs two matching reads')
has(engine, 'PrimeClient.isPackageForeground(EFOOTBALL_PACKAGE)', 'manual FT verifies eFootball foreground')
has(engine, 'reason = "invalid_manual_ft"', 'confirmed fake manual FT forfeits the local player')
has(engine, 'if (scoreConfirmed) return', 'confirmed score blocks later disconnect settlement')
has(engine, '"[MATCH-FT  ] Manual FT tap"', 'every manual FT tap is logged')

# F32: packet score detection fully removed
if Path('app/src/main/java/com/peerlink/app/core/MatchProtocolReader.kt').exists():
    raise AssertionError('MatchProtocolReader.kt still exists')
checks.append('MatchProtocolReader.kt is deleted')
lacks(tracker, 'onTelemetry', 'tracker no longer consumes packet telemetry')
lacks(tracker, 'MatchProtocolReader', 'tracker no longer references the packet reader')
lacks(tracker, 'quarantinedPackets', 'packet quarantine watermark is gone')
lacks(tracker, 'scorer UNRESOLVED', 'scorer attribution logging is gone')
lacks(tracker, 'goalEvents', 'packet goal events are gone from the tracker')
has(tracker, 'fun confirmScreenScore(', 'screen result has a dedicated ledger commit path')
has(tracker, 'fun confirmNoContest(', 'unattributed disconnect has No Contest ledger path')
has(tracker, 'fun markGameplayStarted(', 'engine informs tracker of gameplay T0')
has(tracker, 'fun noteTunnelStats(', 'tracker cites real tunnel counters')
lacks(native_kt, 'pollMatchTelemetry', 'Kotlin no longer polls PMT2 telemetry')
lacks(native_kt, 'drainFinalMatchTelemetry', 'final telemetry drain is gone')
lacks(native_kt, 'nativePollMatchTelemetry', 'PMT2 JNI declaration is gone')
lacks(backend, 'record_match_telemetry', 'native telemetry recorder is gone')
lacks(backend, 'nativePollMatchTelemetry', 'PMT2 JNI export is gone')
lacks(backend, 'should_snapshot_match_payload', 'snapshot filter is gone')
lacks(backend, 'MatchTelemetry', 'telemetry rings are gone')
lacks(backend, 'GamePacketEvent', 'packet event ring entries are gone')

# Native 54B signal
has(backend, 'kSmallGamePayloadMax = 55', 'native counts sub-55B game payloads')
has(backend, 'record_match_packet_signal', 'native small-packet signal recorder exists')
has(backend, 'small_game_packets', 'stats carry the small-packet counter')
has(backend, 'NewLongArray(14)', 'stats array grows to 14 slots')
has(models, 'val smallGamePackets: Long', 'stats model exposes the 54B counter')
has(models, 'val smallGameLastMs: Long', 'stats model exposes the last 54B time')
has(models, 'raw.size < 14', 'stats decode requires the new width')

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
has(detector, 'FinalScoreEvidence.isFinal(text.text)', 'automatic finality is verified in the same OCR pass as the isolated score')
lacks(detector, 'contains("online match")', 'generic match text cannot finalize a result')
has('app/src/main/java/com/peerlink/app/service/ScoreLaneReader.kt', 'it in 0..20', 'score values remain in plausible range')
has('app/src/main/java/com/peerlink/app/service/ScoreLaneReader.kt', 'compact', 'compact two-digit OCR tokens can be split across Home/Away')
has(detector, 'ScoreVisualPreprocessor.prepare', 'pixel-isolated score preprocessing is integrated')
has(engine, 'confirmedCandidate', 'manual FT can commit the toasted auto-read score')
has(engine, 'invalid_manual_ft', 'fake manual FT can be forfeited')
has(server, 'captureDisplayFast', 'Prime uses direct SurfaceControl capture for the high-rate path')
has(detector, 'ScoreVisualPreprocessor', 'score reader uses fixed-layout visual preprocessing')
has(detector, 'ocrInFlight.compareAndSet(false, true)', 'single OCR task owns its bitmap through timeout')

has(vpn, 'MatchAutomationEngine.onNativeStats(stats)', 'automation observes counters outside packet hot path')
has(vpn, 'MatchAutomationEngine.start(applicationContext)', 'automation starts only with active native tunnel')
has(vpn, 'MatchAutomationEngine.stop()', 'automation stops with VPN')
has(vpn, 'MatchTracker.noteTunnelStats', 'stats poll feeds the tracker counters')

print(f'F32 match automation regression PASS {len(checks)}/{len(checks)}')
for label in checks:
    print('PASS', label)
