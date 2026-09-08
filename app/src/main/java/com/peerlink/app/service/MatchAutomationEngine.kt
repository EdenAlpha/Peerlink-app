package com.peerlink.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
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
 * Decode-free eFootball match automation.
 *
 * Hot-path work is intentionally tiny: one delta calculation per native stats
 * poll. Screen capture/OCR only exists in the short post-5-minute low-PPS
 * window, so normal gameplay packet forwarding is untouched.
 */
object MatchAutomationEngine : MatchControlChannel.Listener {
    private const val EFOOTBALL_PACKAGE = PeerLinkVpnService.EFOOTBALL_PACKAGE

    private const val SIDE_PROMPT_PACKET_THRESHOLD = 200L
    private const val GAMEPLAY_PPS_MIN = 24
    private const val GAMEPLAY_PPS_MAX = 27
    private const val CAPTURE_TRIGGER_PPS = 20          // strictly below
    private const val CAPTURE_CANCEL_PPS = 24           // strictly above
    private const val AUTO_CAPTURE_DELAY_MS = 5 * 60_000L
    private const val CAPTURE_INTERVAL_MS = 250L        // 4 fps
    private const val CAPTURE_MAX_MS = 20_000L
    private const val DISCONNECT_CONFIRM_MS = 135_000L
    private const val REMATCH_MIN_GAP_MS = 20_000L

    private enum class PpsDirection { OUTBOUND, INBOUND }
    private enum class Topology { HOTSPOT_OWNER, WIFI_CLIENT, UNKNOWN }

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

    private var localSide: MatchControlChannel.Side? = null
    private var localSideConfirmed = false
    private var peerSide: MatchControlChannel.Side? = null
    private var peerSideConfirmed = false
    private var rolesLocked = false

    private var localTopology = Topology.UNKNOWN
    private var peerTopology = Topology.UNKNOWN

    private var captureJob: Job? = null
    private var sessionGeneration = 0L
    private var captureGeneration = 0L
    private val manualCaptureBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var captureAttemptLatched = false
    private var lastAutoCandidate: PrimeScreenScoreDetector.Score? = null
    private var autoCandidateHits = 0

    private var lowFlowSinceMs = 0L
    private var disconnectResolved = false

    private var scoreConfirmed = false
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
        AppState.appendLog("[MATCH-AUTO] Started: T0=first 24-27pps; auto-capture allowed after 5:00")
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

        var startCapture = false
        var cancelCapture = false
        var startSidePrompt = false
        var detectTopology = false
        var resolveDisconnect = false
        var resetRematch = false
        var logT0: String? = null

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
                    logT0 = "[MATCH-AUTO] GAMEPLAY_T0 detected pps=$currentPps direction=${ppsDirection!!.name} out=$outPps in=$inPps"
                }
                return@synchronized
            }

            val signal = ppsFor(ppsDirection ?: PpsDirection.OUTBOUND, outPps, inPps)
            currentPps = signal
            val age = now - gameplayT0Ms

            if (signal >= CAPTURE_CANCEL_PPS) {
                if (captureJob != null) cancelCapture = true
                captureAttemptLatched = false
                lastAutoCandidate = null
                autoCandidateHits = 0
                lowFlowSinceMs = 0L
                disconnectResolved = false
            } else if (age >= AUTO_CAPTURE_DELAY_MS) {
                if (signal < CAPTURE_TRIGGER_PPS && !captureAttemptLatched && captureJob == null) {
                    captureAttemptLatched = true
                    startCapture = true
                }

                // A true disconnect is deliberately much slower than FT capture.
                // Any sustained non-gameplay rate can start the 135 s timer;
                // a return to the normal 24+ pps floor clears it immediately.
                // Drops before 5:00 never count toward this timer.
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
        }

        logT0?.let(AppState::appendLog)
        if (startSidePrompt) MatchMarkerOverlay.beginSideSelection()
        if (detectTopology) scope.launch { detectAndAdvertiseTopology() }
        if (cancelCapture) cancelAutoCapture("gameplay recovered >24pps")
        if (startCapture) startAutoCapture()
        if (resolveDisconnect) scope.launch { resolveSustainedDisconnect() }
        if (resetRematch) resetForRematch()
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
        if (conflict) roleConflict("peer also selected ${side.wire}")
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
        cancelAutoCapture("peer forfeit")
        MatchTracker.confirmForfeit(localPlayerLost = false, reason = "peer:$reason")
        MatchMarkerOverlay.setWaiting()
        AppState.appendLog("[MATCH-AUTO] Peer forfeit received reason=$reason -> local 3-0")
    }

    /** Manual FT is one explicit screenshot; repeated taps do not overlap. */
    fun manualFullTimeCapture() {
        val context = appContext ?: return
        val generation = synchronized(lock) {
            if (!started || !rolesLocked || scoreConfirmed || gameplayT0Ms <= 0L) return
            sessionGeneration
        }
        if (!manualCaptureBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                when (PrimeClient.isPackageForeground(EFOOTBALL_PACKAGE)) {
                    false -> {
                        // An explicit FT claim while eFootball is not the foreground
                        // app is the strong fake-result signal described by the match
                        // rules. Record the local 0-3 immediately; the normal ledger
                        // reward/penalty path supplies the point deduction.
                        var forfeited = false
                        synchronized(lock) {
                            if (started && sessionGeneration == generation && !scoreConfirmed) {
                                forfeited = MatchTracker.confirmForfeit(
                                    localPlayerLost = true,
                                    reason = "invalid_manual_ft",
                                )
                                if (forfeited) {
                                    scoreConfirmed = true
                                    scoreConfirmedAtMs = SystemClock.elapsedRealtime()
                                    MatchMarkerOverlay.setWaiting()
                                }
                            }
                        }
                        if (forfeited) {
                            MatchControlChannel.sendForfeit("invalid_manual_ft")
                            AppState.appendLog("[MATCH-FT  ] Invalid manual FT: eFootball was not foreground -> local 0-3")
                            main.post {
                                Toast.makeText(
                                    context,
                                    "Invalid FT claim. Match forfeited 0–3.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } else {
                            main.post { Toast.makeText(context, "Open eFootball and try FT again", Toast.LENGTH_SHORT).show() }
                        }
                        return@launch
                    }
                    null -> {
                        main.post { Toast.makeText(context, "Could not verify eFootball is foreground — try FT again", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    true -> Unit
                }

                // Manual FT is intentionally one capture only. The player is
                // explicitly asserting that the final score is visible now.
                val score = PrimeScreenScoreDetector.captureScore(context)
                if (score == null) {
                    main.post { Toast.makeText(context, "Final score not visible — keep the result visible and retry", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                commitDetectedScore(score, "manual", generation)
            } finally {
                manualCaptureBusy.set(false)
            }
        }
    }

    private fun startAutoCapture() {
        val context = appContext ?: return
        val (generation, burst) = synchronized(lock) {
            if (!started || scoreConfirmed || captureJob != null) return
            lastAutoCandidate = null
            autoCandidateHits = 0
            sessionGeneration to ++captureGeneration
        }
        AppState.appendLog("[MATCH-CAP ] PPS<$CAPTURE_TRIGGER_PPS after 5:00 -> Prime capture 4fps, max 20s")

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
            var confirmationPrompted = false
            val ocrJob = launch {
                for (frame in frames) {
                    try {
                        analyzed++
                        val score = PrimeScreenScoreDetector.detectFrame(frame)
                        val stillValid = synchronized(lock) {
                            started && sessionGeneration == generation && captureGeneration == burst && !scoreConfirmed && currentPps <= CAPTURE_CANCEL_PPS && captureJob != null
                        }
                        // A single OCR miss must not erase a valid candidate.
                        // The burst is already bounded to 20 s and a final score is
                        // still gated by two matching reads plus final-time evidence.
                        if (stillValid && score != null && registerAutomaticCandidate(score, generation, burst)) {
                            if (PrimeClient.isPackageForeground(EFOOTBALL_PACKAGE) == true) {
                                if (score.finalScreen) {
                                    if (commitDetectedScore(score, "auto", generation, burst)) break
                                } else if (!confirmationPrompted) {
                                    confirmationPrompted = true
                                    AppState.appendLog("[MATCH-OCR ] Score ${score.home}-${score.away} read; final-time context unclear, awaiting FT tap")
                                    main.post {
                                        val valid = synchronized(lock) { started && sessionGeneration == generation && !scoreConfirmed }
                                        if (valid) Toast.makeText(context,
                                            "Score ${score.home}–${score.away} found. If the match has ended, tap FT to confirm.",
                                            Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            }

            val startedAt = SystemClock.elapsedRealtime()
            var nextFrameAt = startedAt
            try {
                while (isActive && SystemClock.elapsedRealtime() - startedAt < CAPTURE_MAX_MS) {
                    val shouldContinue = synchronized(lock) {
                        started && sessionGeneration == generation && captureGeneration == burst && !scoreConfirmed && currentPps <= CAPTURE_CANCEL_PPS
                    }
                    if (!shouldContinue) break

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
                    // frame when PPS recovers or a score is confirmed.
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
            if (burst != null && (captureGeneration != burst || currentPps > CAPTURE_CANCEL_PPS)) return false
            val side = localSide ?: return false
            val mine = if (side == MatchControlChannel.Side.HOME) score.home else score.away
            val theirs = if (side == MatchControlChannel.Side.HOME) score.away else score.home
            // Serialize validation and ledger commit against stop, rematch and competing OCR.
            if (!MatchTracker.confirmScreenScore(mine, theirs, "$mode:${score.source}")) return false
            scoreConfirmed = true
            scoreConfirmedAtMs = SystemClock.elapsedRealtime()
            MatchMarkerOverlay.setWaiting()
        }
        AppState.appendLog("[MATCH-OCR ] Confirmed HOME ${score.home}-${score.away} AWAY ($mode)")
        return true
    }

    private fun cancelAutoCapture(reason: String) {
        val job = synchronized(lock) {
            captureGeneration++
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
        MatchMarkerOverlay.showFullTime()
        AppState.appendLog("[MATCH-ROLE] Locked complementary sides: local=${mine.name} peer=${mine.opposite().name}; FT enabled")
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
        captureGeneration++
        captureJob?.cancel()
        captureJob = null
        lastStats = null
        lastStatsAtMs = 0L
        stunSeen = false
        sideSelectionStarted = false
        ppsDirection = null
        gameplayT0Ms = 0L
        currentPps = 0
        resetRolesLocked()
        localTopology = Topology.UNKNOWN
        peerTopology = Topology.UNKNOWN
        captureAttemptLatched = false
        lastAutoCandidate = null
        autoCandidateHits = 0
        lowFlowSinceMs = 0L
        disconnectResolved = false
        scoreConfirmed = false
        scoreConfirmedAtMs = 0L
        rematchNormalSinceMs = 0L
        rematchNormalSamples = 0
    }

    private fun resetForRematch() {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!started || !scoreConfirmed) return
            sessionGeneration++
            captureGeneration++
            captureJob?.cancel()
            captureJob = null
            scoreConfirmed = false
            scoreConfirmedAtMs = 0L
            gameplayT0Ms = now
            captureAttemptLatched = false
            lastAutoCandidate = null
            autoCandidateHits = 0
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
                cancelAutoCapture("No Contest")
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

    private fun hasExactLanIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
                iface.isUp && Collections.list(iface.inetAddresses).any { it is Inet4Address && it.hostAddress == ip }
            }
        }.getOrDefault(false)
    }

    private fun hasOtherWifiIpv4(lockedIp: String): Boolean {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
                val name = iface.name.lowercase()
                iface.isUp && (name.contains("wlan") || name.contains("wifi")) &&
                    Collections.list(iface.inetAddresses).any {
                        it is Inet4Address && !it.isLoopbackAddress && it.hostAddress != lockedIp
                    }
            }
        }.getOrDefault(false)
    }
}
