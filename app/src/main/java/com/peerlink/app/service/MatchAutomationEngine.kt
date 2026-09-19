package com.peerlink.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.peerlink.app.core.AppState
import com.peerlink.app.core.MatchTracker
import com.peerlink.app.godmode.PrimeClient
import com.peerlink.app.tunnel.NativeBackendStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.math.roundToInt

/**
 * F32 eFootball match automation — capture-trigger state machine.
 *
 * Two validated full-time signatures (both from real FT-tapped captures):
 *
 *  PATH A — 54B tail. The game's dying tick stream shrinks its payload to a
 *  uniform ~54 bytes (never seen anywhere else across whole captures: goals,
 *  half-time, replays and transient dips — lowest observed dip 17pps — all
 *  ride 89-243B payloads) roughly 1s before the PPS cliff. On the first
 *  <55B game packet after the 5-minute gate we start 4fps capture, then
 *  expect the game feed to reach ~0pps within 3s (the cliff is 27->0 in
 *  ~2-3s, never a gradual slope). Confirmed cliff -> keep capturing at most
 *  7 more seconds while score reading runs; a verified score stops capture
 *  early. No cliff within 3s -> pause capture for up to 1.5s; if the feed
 *  reaches ~0pps inside that window (delayed cliff), capture resumes and all
 *  frames feed the reader; otherwise the 54B was noise and the burst ends.
 *
 *  PATH B — deep PPS drop without any 54B. A game feed under 9pps sustained
 *  (real gameplay never came close: floor was 17pps for 1-2.5s blips) is a
 *  strong end signal on its own. Capture immediately for at most 10s. Any
 *  54B packets that appear during a Path-B burst are ignored: the burst is
 *  already running on stronger evidence and mixing trigger semantics only
 *  invites state-machine confusion.
 *
 * Both paths share: the 5-minute gate after T0 (a real match is at least
 * that long), mandatory locked H/A roles, the 4fps producer / single-OCR
 * consumer pipeline with capacity-1 frame dropping, two agreeing frames
 * before a candidate is real, eFootball-foreground checks, and full-time
 * screen text ("full time" / "match ended" / "final result") for auto
 * commits. The manual FT tap remains the human override with the fake-FT
 * forfeit; every tap is now logged.
 *
 * Hot-path work is intentionally tiny: one delta calculation per native
 * stats poll plus two integer compares; screen capture/OCR exists only in
 * the short post-whistle bursts.
 */
object MatchAutomationEngine : MatchControlChannel.Listener {
    private const val EFOOTBALL_PACKAGE = PeerLinkVpnService.EFOOTBALL_PACKAGE

    private const val SIDE_PROMPT_PACKET_THRESHOLD = 200L
    private const val GAMEPLAY_PPS_MIN = 24
    private const val GAMEPLAY_PPS_MAX = 27

    /** Path B trigger: sustained game feed below this is a deep collapse. */
    private const val PATH_B_TRIGGER_PPS = 9
    /** Path B budget: capture at most this long unless the score verifies. */
    private const val PATH_B_MAX_MS = 10_000L

    /** Path A: expect the ~0pps cliff within this window after first 54B. */
    private const val ZERO_PPS_CONFIRM_MS = 3_000L
    /** Path A pause: wait this long for a delayed cliff before giving up. */
    private const val PAUSE_WINDOW_MS = 1_500L
    /** Path A tail budget after a confirmed cliff. */
    private const val PATH_A_TAIL_MS = 7_000L

    /** A poll at or below this reads as the cliff (game feed dead). */
    private const val ZERO_PPS_THRESHOLD = 1

    private const val AUTO_CAPTURE_DELAY_MS = 5 * 60_000L
    private const val CAPTURE_INTERVAL_MS = 250L        // 4 fps
    private const val DISCONNECT_CONFIRM_MS = 135_000L
    private const val REMATCH_MIN_GAP_MS = 20_000L

    private enum class PpsDirection { OUTBOUND, INBOUND }
    private enum class Topology { HOTSPOT_OWNER, WIFI_CLIENT, UNKNOWN }

    /** Capture burst mode, driven by which end signal opened it. */
    private enum class CaptureMode {
        /** 54B seen; waiting up to 3s for the 0pps cliff. */
        PATH_A_WATCH,
        /** 54B seen, cliff confirmed; tail burst (<=7s). */
        PATH_A_TAIL,
        /** 54B seen, no cliff in 3s; capture paused, watching <=1.5s. */
        PATH_A_PAUSE,
        /** Deep PPS collapse without 54B (<=10s); any 54B inside is ignored. */
        PATH_B,
        /** Score locked; keep filming until stats arrive or eFootball leaves. */
        STATS_HUNT,
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false

    private var lastStats: NativeBackendStats? = null
    private var lastStatsAtMs = 0L
    private var stunSeen = false
    private var sideSelectionStarted = false

    private var ppsDirection: PpsDirection? = null
    private var gameplayT0Ms = 0L
    private var currentPps = 0

    private var lastSmallGamePackets = 0L
    private var smallPacketSeenThisMatch = false

    private var localSide: MatchControlChannel.Side? = null
    private var localSideConfirmed = false
    private var peerSide: MatchControlChannel.Side? = null
    private var peerSideConfirmed = false
    private var rolesLocked = false

    private var captureJob: Job? = null
    private var sessionGeneration = 0L
    private var captureGeneration = 0L
    private var lastAutoCandidate: PrimeScreenScoreDetector.Score? = null
    private var autoCandidateHits = 0

    /** Path-A sequencing, all in elapsedRealtime ms. */
    private var captureMode: CaptureMode? = null
    private var modeStartedAtMs = 0L
    private var pathBTriggerSinceMs = 0L
    private var pathBCooldownUntilMs = 0L

    private var lowFlowSinceMs = 0L
    private var disconnectResolved = false

    private var scoreConfirmed = false
    private var statsComplete = false
    private var scoreConfirmedAtMs = 0L
    private var rematchNormalSinceMs = 0L
    private var rematchNormalSamples = 0

    fun start(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            started = true
            resetSessionLocked()
        }
        MatchControlChannel.start(this)
        MatchMarkerOverlay.setWaiting()
        MatchMarkerOverlay.show(context)
        AppState.appendLog("[MATCH-AUTO] Started: T0=first 24-27pps; capture on 54B-tail or <${PATH_B_TRIGGER_PPS}pps after 5:00")
    }

    fun stop() {
        val job = synchronized(lock) {
            started = false
            appContext = null
            captureJob.also { captureJob = null }
        }
        job?.cancel()
        MatchControlChannel.stop()
    }

    /** Called once per native backend stats poll (normally ~1 Hz). */
    fun onNativeStats(stats: NativeBackendStats) {
        if (!started) return
        val now = SystemClock.elapsedRealtime()

        var startSidePrompt = false
        var detectTopology = false
        var resolveDisconnect = false
        var resetRematch = false
        var startProducer = false
        var logT0: String? = null
        var logCapture: String? = null

        synchronized(lock) {
            val previous = lastStats
            val previousAt = lastStatsAtMs
            lastStats = stats
            lastStatsAtMs = now
            stunSeen = stunSeen || stats.stunInterceptedIpv4 > 0L || stats.stunInterceptedIpv6 > 0L

            if (!sideSelectionStarted && stunSeen && stats.totalTunneledPackets >= SIDE_PROMPT_PACKET_THRESHOLD) {
                sideSelectionStarted = true
                startSidePrompt = true
                detectTopology = true
            }

            // Fresh 54B signal since the previous poll (monotonic counter).
            val smallDelta = stats.smallGamePackets - lastSmallGamePackets
            lastSmallGamePackets = stats.smallGamePackets
            val smallArrived = smallDelta > 0

            if (previous == null || previousAt <= 0L || now <= previousAt) return@synchronized
            val dt = (now - previousAt).coerceAtLeast(1L)
            val outPps = normalizePps(stats.tunnelOutPackets - previous.tunnelOutPackets, dt)
            val inPps = normalizePps(stats.tunnelInPackets - previous.tunnelInPackets, dt)

            if (scoreConfirmed) {
                val signal = ppsDirection?.let { ppsFor(it, outPps, inPps) } ?: outPps
                if (now - scoreConfirmedAtMs >= REMATCH_MIN_GAP_MS && signal in GAMEPLAY_PPS_MIN..GAMEPLAY_PPS_MAX) {
                    if (rematchNormalSinceMs == 0L) rematchNormalSinceMs = now
                    rematchNormalSamples++
                    if (rematchNormalSamples >= 2) resetRematch = true
                } else {
                    rematchNormalSinceMs = 0L
                    rematchNormalSamples = 0
                }
                return@synchronized
            }

            if (gameplayT0Ms == 0L) {
                // Do not let unrelated pre-game/passthrough traffic define the
                // match clock. T0 can only be established after the STUN-backed
                // session has crossed the 200-tunneled-packet preparation gate.
                if (!sideSelectionStarted) return@synchronized
                ppsDirection = when {
                    outPps in GAMEPLAY_PPS_MIN..GAMEPLAY_PPS_MAX -> PpsDirection.OUTBOUND
                    inPps in GAMEPLAY_PPS_MIN..GAMEPLAY_PPS_MAX -> PpsDirection.INBOUND
                    else -> null
                }
                if (ppsDirection != null) {
                    gameplayT0Ms = now
                    currentPps = ppsFor(ppsDirection!!, outPps, inPps)
                    MatchTracker.markGameplayStarted()
                    logT0 = "[MATCH-AUTO] GAMEPLAY_T0 detected pps=$currentPps direction=${ppsDirection!!.name} out=$outPps in=$inPps"
                }
                return@synchronized
            }

            val signal = ppsFor(ppsDirection ?: PpsDirection.OUTBOUND, outPps, inPps)
            currentPps = signal
            val age = now - gameplayT0Ms

            // Pre-5:00 traffic only maintains the disconnect watch. Capture
            // triggers cannot exist before the earliest realistic full time.
            if (age < AUTO_CAPTURE_DELAY_MS) {
                if (signal < GAMEPLAY_PPS_MIN) {
                    if (lowFlowSinceMs == 0L) lowFlowSinceMs = now
                    if (!disconnectResolved && now - lowFlowSinceMs >= DISCONNECT_CONFIRM_MS) {
                        disconnectResolved = true
                        resolveDisconnect = true
                    }
                } else {
                    lowFlowSinceMs = 0L
                }
                return@synchronized
            }

            val mode = captureMode
            when (mode) {
                null -> {
                    // Idle: waiting for an end-of-match signal.
                    if (smallArrived) {
                        smallPacketSeenThisMatch = true
                        enterModeLocked(CaptureMode.PATH_A_WATCH, now)
                        startProducer = true
                        logCapture = "[MATCH-CAP ] 54B tail detected -> Path A capture (4fps), expecting ~0pps within ${ZERO_PPS_CONFIRM_MS / 1000}s"
                    } else if (!smallPacketSeenThisMatch && signal in 1 until PATH_B_TRIGGER_PPS && now >= pathBCooldownUntilMs) {
                        if (pathBTriggerSinceMs == 0L) {
                            pathBTriggerSinceMs = now
                        } else if (now - pathBTriggerSinceMs >= 1_000L) {
                            enterModeLocked(CaptureMode.PATH_B, now)
                            startProducer = true
                            logCapture = "[MATCH-CAP ] pps=$signal < $PATH_B_TRIGGER_PPS without 54B -> Path B capture (4fps, max ${PATH_B_MAX_MS / 1000}s)"
                        }
                    } else if (signal >= PATH_B_TRIGGER_PPS) {
                        pathBTriggerSinceMs = 0L
                    }
                    if (signal < GAMEPLAY_PPS_MIN) {
                        if (lowFlowSinceMs == 0L) lowFlowSinceMs = now
                        if (!disconnectResolved && now - lowFlowSinceMs >= DISCONNECT_CONFIRM_MS) {
                            disconnectResolved = true
                            resolveDisconnect = true
                        }
                    } else {
                        lowFlowSinceMs = 0L
                    }
                }
                CaptureMode.PATH_A_WATCH -> {
                    // Capturing while waiting for the cliff. All timing
                    // decisions live here (1Hz); the producer only follows
                    // the current mode.
                    if (signal <= ZERO_PPS_THRESHOLD) {
                        enterModeLocked(CaptureMode.PATH_A_TAIL, now)
                        logCapture = "[MATCH-CAP ] Path A cliff confirmed (pps=$signal) -> tail capture <= ${PATH_A_TAIL_MS / 1000}s"
                    } else if (now - modeStartedAtMs >= ZERO_PPS_CONFIRM_MS) {
                        enterModeLocked(CaptureMode.PATH_A_PAUSE, now)
                        logCapture = "[MATCH-CAP ] No 0pps within ${ZERO_PPS_CONFIRM_MS / 1000}s -> capture paused ${PAUSE_WINDOW_MS / 1000}s watching for the cliff"
                    }
                }
                CaptureMode.PATH_A_TAIL -> {
                    // Cliff confirmed; the 7s tail budget is enforced here.
                    if (signal >= GAMEPLAY_PPS_MIN) {
                        // Gameplay-grade traffic returned after a confirmed
                        // cliff: the "cliff" was a stall, not full time.
                        endModeLocked()
                        logCapture = "[MATCH-CAP ] Gameplay recovered after cliff watch -> capture ended"
                    } else if (now - modeStartedAtMs >= PATH_A_TAIL_MS) {
                        if (scoreConfirmed && !statsComplete) {
                            enterModeLocked(CaptureMode.STATS_HUNT, now)
                            logCapture = "[MATCH-CAP ] Score locked — hunting stats board"
                        } else {
                            endModeLocked()
                            logCapture = "[MATCH-CAP ] Path A tail window closed without a verified score"
                        }
                    }
                }
                CaptureMode.PATH_A_PAUSE -> {
                    // Capture producer paused; watching for a delayed cliff.
                    if (signal <= ZERO_PPS_THRESHOLD) {
                        enterModeLocked(CaptureMode.PATH_A_TAIL, now)
                        logCapture = "[MATCH-CAP ] Delayed cliff reached 0pps -> capture resumed, tail <= ${PATH_A_TAIL_MS / 1000}s"
                    } else if (now - modeStartedAtMs >= PAUSE_WINDOW_MS) {
                        if (scoreConfirmed && !statsComplete) {
                            enterModeLocked(CaptureMode.STATS_HUNT, now)
                            logCapture = "[MATCH-CAP ] Score locked — hunting stats board"
                        } else {
                            endModeLocked()
                            logCapture = "[MATCH-CAP ] No 0pps within pause window -> 54B was noise; capture ended"
                        }
                    }
                }
                CaptureMode.PATH_B -> {
                    // 54B inside a Path-B burst is deliberately ignored: the
                    // burst already runs on the stronger deep-collapse signal.
                    if (signal >= GAMEPLAY_PPS_MIN) {
                        endModeLocked()
                        logCapture = "[MATCH-CAP ] Gameplay recovered (pps=$signal) -> Path B capture ended"
                    } else if (now - modeStartedAtMs >= PATH_B_MAX_MS) {
                        if (scoreConfirmed && !statsComplete) {
                            enterModeLocked(CaptureMode.STATS_HUNT, now)
                            logCapture = "[MATCH-CAP ] Score locked — hunting stats board"
                        } else {
                            endModeLocked()
                            pathBTriggerSinceMs = 0L
                            pathBCooldownUntilMs = now + 20_000L
                            logCapture = "[MATCH-CAP ] Path B window closed without a verified score"
                        }
                    }
                }
                CaptureMode.STATS_HUNT -> {
                    if (signal >= GAMEPLAY_PPS_MIN) {
                        endModeLocked()
                        logCapture = "[MATCH-CAP ] Gameplay returned during stats hunt -> capture ended"
                    }
                }
            }
        }

        logT0?.let(AppState::appendLog)
        logCapture?.let(AppState::appendLog)
        if (startProducer) startProducerIfIdle()
        if (startSidePrompt) MatchMarkerOverlay.beginSideSelection()
        if (detectTopology) scope.launch { detectAndAdvertiseTopology() }
        if (resolveDisconnect) scope.launch { resolveSustainedDisconnect() }
        if (resetRematch) resetForRematch()
    }

    /** Must be called outside [lock] after a mode was entered. */
    private fun startProducerIfIdle() {
        val should = synchronized(lock) { captureMode != null && captureJob == null }
        if (should) startAutoCapture()
    }

    private fun enterModeLocked(mode: CaptureMode, now: Long) {
        captureMode = mode
        modeStartedAtMs = now
    }

    private fun endModeLocked() {
        captureMode = null
        modeStartedAtMs = 0L
        pathBTriggerSinceMs = 0L
        captureGeneration++   // invalidates any running producer
        captureJob?.cancel()
        captureJob = null
    }

    fun chooseLocalSide(side: MatchControlChannel.Side) {
        var conflict = false
        synchronized(lock) {
            if (!started || !sideSelectionStarted || scoreConfirmed || rolesLocked) return
            localSide = side
            localSideConfirmed = false
            if (peerSide == side) conflict = true
        }
        if (conflict) {
            roleConflict("both selected ${side.wire}")
            return
        }
        MatchMarkerOverlay.showSelected(side)
        MatchControlChannel.sendRole(side, confirmed = false)
        AppState.appendLog("[MATCH-ROLE] Local tentative side=${side.name}")
    }

    fun confirmLocalSide(side: MatchControlChannel.Side) {
        var reject = false
        var conflict = false
        var lockedNow = false
        synchronized(lock) {
            if (!started || scoreConfirmed || rolesLocked || localSide != side) {
                reject = true
            } else if (peerSide == null) {
                reject = true
            } else if (peerSide == side) {
                conflict = true
            } else {
                localSideConfirmed = true
                lockedNow = maybeLockRolesLocked()
            }
        }
        when {
            conflict -> roleConflict("same-side confirmation")
            reject -> {
                MatchMarkerOverlay.rejectFeedback()
                // Re-advertise the tentative choice in case the peer missed it.
                synchronized(lock) { localSide }?.let { MatchControlChannel.sendRole(it, localSideConfirmed) }
            }
            else -> {
                MatchControlChannel.sendRole(side, confirmed = true)
                if (lockedNow) onRolesLocked() else AppState.appendLog("[MATCH-ROLE] Local ${side.name} confirmed; waiting for peer confirmation")
            }
        }
    }

    override fun onPeerRole(side: MatchControlChannel.Side, confirmed: Boolean) {
        var conflict = false
        var lockedNow = false
        synchronized(lock) {
            if (!started || !sideSelectionStarted || scoreConfirmed || rolesLocked) return
            peerSide = side
            peerSideConfirmed = confirmed
            if (localSide == side) conflict = true
            else lockedNow = maybeLockRolesLocked()
        }
        if (conflict) roleConflict("peer also selected ${side.name}")
        else if (lockedNow) onRolesLocked()
    }

    override fun onPeerRoleReset() {
        synchronized(lock) {
            if (!started || !sideSelectionStarted || scoreConfirmed || rolesLocked) return
            resetRolesLocked()
        }
        MatchMarkerOverlay.beginSideSelection()
        AppState.appendLog("[MATCH-ROLE] Peer requested H/A reset")
    }

    override fun onPeerTopology(topology: String) {
        synchronized(lock) {
            peerTopology = runCatching { Topology.valueOf(topology) }.getOrDefault(Topology.UNKNOWN)
        }
    }

    override fun onPeerForfeit(reason: String) {
        val shouldRecord = synchronized(lock) {
            if (!started || scoreConfirmed) false else {
                scoreConfirmed = true
                scoreConfirmedAtMs = SystemClock.elapsedRealtime()
                true
            }
        }
        if (!shouldRecord) return
        endModeLocked()
        MatchTracker.confirmForfeit(localPlayerLost = false, reason = "peer:$reason")
        MatchMarkerOverlay.setWaiting()
        AppState.appendLog("[MATCH-AUTO] Peer forfeit received reason=$reason -> local 3-0")
    }



    /**
     * One producer/consumer capture burst. The producer is a pure follower:
     * it captures 4fps whenever a burst mode is active and holds (without
     * capturing) through PATH_A_PAUSE. All timing and mode transitions are
     * decided by onNativeStats at 1Hz; a transition that ends the burst
     * (endModeLocked) bumps [captureGeneration], which the producer treats
     * as a stop signal.
     */
    private fun startAutoCapture() {
        val context = appContext ?: return
        val (generation, burst) = synchronized(lock) {
            val mode = captureMode ?: return
            if (!started || scoreConfirmed || captureJob != null) return
            lastAutoCandidate = null
            autoCandidateHits = 0
            sessionGeneration to ++captureGeneration
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!PrimeClient.isAlive(timeoutMs = 500)) {
                AppState.appendLog("[MATCH-CAP ] PrimeServer unavailable; automatic score capture skipped")
                clearCaptureJob(burst)
                return@launch
            }

            // Capture and OCR are decoupled. The producer keeps the requested
            // 250 ms cadence while one OCR consumer works on only the newest
            // available frame. Capacity stays at one, so memory cannot grow if
            // OCR briefly takes longer than a frame interval.
            val frames = Channel<PrimeScreenScoreDetector.CapturedFrame>(capacity = 1)
            var captured = 0
            var analyzed = 0
                    val ocrJob = launch {
                for (frame in frames) {
                    try {
                        analyzed++
                        val score = PrimeScreenScoreDetector.detectFrame(frame)
                        val stillValid = synchronized(lock) {
                            started && sessionGeneration == generation && !statsComplete &&
                                captureGeneration == burst && captureJob != null && captureMode != null
                        }
                        if (stillValid && score != null && registerAutomaticCandidate(score, generation, burst)) {
                            if (!scoreConfirmed) {
                                commitDetectedScore(score, "auto", generation, burst)
                            }
                            if (score.stats != null && score.stats.rows.size >= 4) {
                                attachDetectedStats(score, generation)
                            }
                            if (statsComplete) break
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            }

            var nextFrameAt = SystemClock.elapsedRealtime()
            try {
                while (isActive) {
                    val modeNow = synchronized(lock) {
                        if (!started || sessionGeneration != generation ||
                            captureGeneration != burst || statsComplete || captureJob == null
                        ) null else captureMode
                    }
                    if (modeNow == null) break
                    if (modeNow == CaptureMode.STATS_HUNT &&
                        PrimeClient.isPackageForeground(EFOOTBALL_PACKAGE) == false) {
                        synchronized(lock) { endModeLocked() }
                        AppState.appendLog("[MATCH-CAP ] eFootball left foreground — stats hunt ended")
                        break
                    }
                    if (modeNow != CaptureMode.PATH_A_PAUSE) {
                        val frame = PrimeScreenScoreDetector.captureFrame(context)
                        captured++
                        if (frame != null) {
                            if (frames.trySend(frame).isFailure) {
                                // Keep only the newest pending frame. One frame may
                                // be in OCR and one may wait; memory never grows.
                                frames.tryReceive().getOrNull()?.recycle()
                                if (frames.trySend(frame).isFailure) frame.recycle()
                            }
                        }
                    }
                    nextFrameAt += CAPTURE_INTERVAL_MS
                    val wait = nextFrameAt - SystemClock.elapsedRealtime()
                    if (wait > 0L) delay(wait)
                    else nextFrameAt = SystemClock.elapsedRealtime()
                }
            } finally {
                withContext(NonCancellable) {
                    frames.close()
                    ocrJob.cancel()
                    ocrJob.join()
                    // A capacity-1 channel may still contain one unconsumed
                    // frame when the mode ends or a score is confirmed.
                    while (true) {
                        val leftover = frames.tryReceive().getOrNull() ?: break
                        leftover.recycle()
                    }
                    AppState.appendLog(
                        "[MATCH-CAP ] Capture burst ended captured=$captured analyzed=$analyzed " +
                            "scoreConfirmed=${synchronized(lock) { scoreConfirmed }}"
                    )
                    clearCaptureJob(burst)
                }
            }
        }
        synchronized(lock) {
            if (!started || sessionGeneration != generation || captureGeneration != burst || captureJob != null) {
                job.cancel()
                return
            }
            captureJob = job
        }
        job.start()
    }

    private fun registerAutomaticCandidate(score: PrimeScreenScoreDetector.Score, generation: Long, burst: Long): Boolean = synchronized(lock) {
        if (!started || sessionGeneration != generation || captureGeneration != burst || !rolesLocked) return@synchronized false
        val previous = lastAutoCandidate
        if (previous != null && previous.home == score.home && previous.away == score.away) {
            autoCandidateHits++
            lastAutoCandidate = score
        } else {
            lastAutoCandidate = score
            autoCandidateHits = 1
        }
        // Two matching frames only cost ~250 ms but substantially reduce an OCR
        // misread on a transition frame.
        autoCandidateHits >= 2
    }

    private fun commitDetectedScore(score: PrimeScreenScoreDetector.Score, mode: String, generation: Long, burst: Long? = null): Boolean {
        synchronized(lock) {
            if (!started || sessionGeneration != generation || !rolesLocked || scoreConfirmed) return false
            if (burst != null && captureGeneration != burst) return false
            val side = localSide ?: return false
            val mine = if (side == MatchControlChannel.Side.HOME) score.home else score.away
            val theirs = if (side == MatchControlChannel.Side.HOME) score.away else score.home
            if (!MatchTracker.confirmScreenScore(mine, theirs, "$mode:${score.source}", score.stats)) return false
            scoreConfirmed = true
            scoreConfirmedAtMs = SystemClock.elapsedRealtime()
            if (score.stats != null && score.stats.rows.size >= 4) statsComplete = true
            MatchMarkerOverlay.setWaiting()
        }
        AppState.appendLog("[MATCH-OCR ] Confirmed HOME ${score.home}-${score.away} AWAY ($mode)")
        return true
    }

    private fun attachDetectedStats(score: PrimeScreenScoreDetector.Score, generation: Long): Boolean {
        val stats = score.stats ?: return false
        synchronized(lock) {
            if (!started || sessionGeneration != generation || !scoreConfirmed || statsComplete) return false
            val side = localSide ?: return false
            val mine = if (side == MatchControlChannel.Side.HOME) score.home else score.away
            val theirs = if (side == MatchControlChannel.Side.HOME) score.away else score.home
            val live = MatchTracker.state.value
            if (live.myGoals != mine || live.opponentGoals != theirs) return false
            if (!MatchTracker.attachStats(stats)) return false
            statsComplete = true
            endModeLocked()
        }
        AppState.appendLog("[MATCH-OCR ] Stats attached ${stats.rows.size} rows")
        return true
    }

    private fun cancelAutoCapture(reason: String) {
        val job = synchronized(lock) {
            endModeLocked()
            captureJob.also { captureJob = null }
        }
        job?.cancel()
        if (job != null) AppState.appendLog("[MATCH-CAP ] Capture cancelled: $reason")
    }

    private fun clearCaptureJob(burst: Long) {
        synchronized(lock) { if (captureGeneration == burst) captureJob = null }
    }

    private fun maybeLockRolesLocked(): Boolean {
        val mine = localSide ?: return false
        val theirs = peerSide ?: return false
        if (mine == theirs || !localSideConfirmed || !peerSideConfirmed) return false
        rolesLocked = true
        return true
    }

    private fun onRolesLocked() {
        val mine = synchronized(lock) { localSide } ?: return
        MatchMarkerOverlay.setWaiting()
        AppState.appendLog("[MATCH-ROLE] Locked complementary sides: local=${mine.name} peer=${mine.opposite().name}")
    }

    private fun roleConflict(reason: String) {
        synchronized(lock) { resetRolesLocked() }
        MatchControlChannel.sendReset()
        MatchMarkerOverlay.beginSideSelection()
        MatchMarkerOverlay.conflictFeedback()
        AppState.appendLog("[MATCH-ROLE] CONFLICT $reason -> both sides reset")
    }

    private fun resetRolesLocked() {
        localSide = null
        localSideConfirmed = false
        peerSide = null
        peerSideConfirmed = false
        rolesLocked = false
    }

    private fun resetSessionLocked() {
        sessionGeneration++
        endModeLocked()
        lastStats = null
        lastStatsAtMs = 0L
        stunSeen = false
        sideSelectionStarted = false
        ppsDirection = null
        gameplayT0Ms = 0L
        currentPps = 0
        lastSmallGamePackets = 0L
        smallPacketSeenThisMatch = false
        resetRolesLocked()
        localTopology = Topology.UNKNOWN
        peerTopology = Topology.UNKNOWN
        lastAutoCandidate = null
        autoCandidateHits = 0
        pathBTriggerSinceMs = 0L
        pathBCooldownUntilMs = 0L
        lowFlowSinceMs = 0L
        disconnectResolved = false
        scoreConfirmed = false
        statsComplete = false
        scoreConfirmedAtMs = 0L
        rematchNormalSinceMs = 0L
        rematchNormalSamples = 0
    }

    private fun resetForRematch() {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!started || !scoreConfirmed) return
            sessionGeneration++
            endModeLocked()
            scoreConfirmed = false
            statsComplete = false
            scoreConfirmedAtMs = 0L
            gameplayT0Ms = now
            lastAutoCandidate = null
            autoCandidateHits = 0
            lastSmallGamePackets = lastStats?.smallGamePackets ?: lastSmallGamePackets
            smallPacketSeenThisMatch = false
            lowFlowSinceMs = 0L
            disconnectResolved = false
            rematchNormalSinceMs = 0L
            rematchNormalSamples = 0
            sideSelectionStarted = true
            resetRolesLocked()
        }
        MatchControlChannel.sendReset()
        MatchMarkerOverlay.beginSideSelection()
        AppState.appendLog("[MATCH-AUTO] New sustained 24-27pps flow after result -> rematch T0 and H/A reset")
    }

    private fun normalizePps(delta: Long, dtMs: Long): Int {
        if (delta <= 0L) return 0
        return (delta.toDouble() * 1_000.0 / dtMs.toDouble()).roundToInt().coerceAtLeast(0)
    }

    private fun ppsFor(direction: PpsDirection, outPps: Int, inPps: Int): Int =
        if (direction == PpsDirection.OUTBOUND) outPps else inPps

    private suspend fun detectAndAdvertiseTopology() {
        val iface = AppState.localLanInterfaceName.get().orEmpty()
        val localIp = AppState.localIp.get().orEmpty()
        val route = if (iface.isNotBlank() && PrimeClient.isAlive(350)) {
            PrimeClient.execute("ip route show dev ${iface.replace(Regex("[^A-Za-z0-9_.:-]"), "")}", 1_500).orEmpty()
        } else ""
        val topology = when {
            route.contains("default via") -> Topology.WIFI_CLIENT
            iface.contains("ap", ignoreCase = true) || iface.contains("soft", ignoreCase = true) -> Topology.HOTSPOT_OWNER
            route.isNotBlank() && localIp.isNotBlank() && route.contains(localIp) -> Topology.HOTSPOT_OWNER
            else -> Topology.UNKNOWN
        }
        synchronized(lock) { localTopology = topology }
        MatchControlChannel.sendTopology(topology.name)
        AppState.appendLog("[MATCH-NET ] topology=$topology iface=$iface")
    }

    private suspend fun resolveSustainedDisconnect() {
        val context = appContext ?: return
        val outcome = synchronized(lock) {
            if (!started || scoreConfirmed) return
            determineDisconnectFaultLocked(context)
        }
        when (outcome) {
            true -> {
                synchronized(lock) {
                    if (scoreConfirmed) return
                    scoreConfirmed = true
                    scoreConfirmedAtMs = SystemClock.elapsedRealtime()
                }
                MatchTracker.confirmForfeit(localPlayerLost = true, reason = "network_disconnect")
                MatchControlChannel.sendForfeit("network_disconnect")
                MatchMarkerOverlay.setWaiting()
                AppState.appendLog("[MATCH-NET ] Sustained disconnect attributed locally -> 0-3")
            }
            false -> {
                synchronized(lock) {
                    if (scoreConfirmed) return
                    scoreConfirmed = true
                    scoreConfirmedAtMs = SystemClock.elapsedRealtime()
                }
                MatchTracker.confirmForfeit(localPlayerLost = false, reason = "peer_network_disconnect")
                MatchMarkerOverlay.setWaiting()
                AppState.appendLog("[MATCH-NET ] Local LAN identity survived sustained disconnect -> peer 0-3")
            }
            null -> {
                synchronized(lock) {
                    if (scoreConfirmed) return
                    scoreConfirmed = true
                    scoreConfirmedAtMs = SystemClock.elapsedRealtime()
                }
                endModeLocked()
                MatchTracker.confirmNoContest("unattributed_network_disconnect")
                MatchMarkerOverlay.setWaiting()
                AppState.appendLog("[MATCH-NET ] Sustained disconnect could not be safely attributed -> No Contest")
            }
        }
    }

    /** true=local fault, false=peer fault, null=not safe to attribute. */
    private fun determineDisconnectFaultLocked(context: Context): Boolean? {
        val exactLanPresent = hasExactLanIp(AppState.localIp.get().orEmpty())
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = runCatching { wifi?.isWifiEnabled ?: false }.getOrDefault(false)
        val otherIpv4 = hasOtherWifiIpv4(AppState.localIp.get().orEmpty())

        return when (localTopology) {
            Topology.HOTSPOT_OWNER -> if (exactLanPresent) null else true
            Topology.WIFI_CLIENT -> when {
                exactLanPresent -> null // A retained IP lease cannot prove the opponent caused the outage.
                !wifiEnabled -> true
                otherIpv4 -> true // local phone switched away from the match LAN
                // When the peer is the hotspot owner, loss of that hotspot can
                // legitimately remove our LAN address while Wi-Fi itself stays
                // enabled. The hotspot owner independently verifies whether its
                // SoftAP/LAN identity survived, so do not classify this local
                // side as a fault merely because the lease disappeared.
                peerTopology == Topology.HOTSPOT_OWNER -> null
                else -> null       // shared router/AP failure: No Contest
            }
            Topology.UNKNOWN -> when {
                exactLanPresent -> null // A retained IP lease cannot prove the opponent caused the outage.
                !wifiEnabled -> true
                otherIpv4 -> true
                else -> null
            }
        }
    }

    private fun hasExactLanIp(localIp: String): Boolean {
        if (localIp.isBlank()) return false
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
                iface.interfaceAddresses.any { addr ->
                    (addr.address as? Inet4Address)?.hostAddress == localIp
                }
            }
        }.getOrDefault(false)
    }

    private fun hasOtherWifiIpv4(localIp: String): Boolean = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
            val name = iface.name.lowercase()
            (name.contains("wlan") || name.contains("ap")) &&
                iface.interfaceAddresses.any { addr ->
                    (addr.address as? Inet4Address)?.hostAddress?.let { it != localIp } == true
                }
        }
    }.getOrDefault(false)

    private var localTopology = Topology.UNKNOWN
    private var peerTopology = Topology.UNKNOWN
}
