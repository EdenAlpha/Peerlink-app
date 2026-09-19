package com.peerlink.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * F32 match lifecycle owner.
 *
 * One PeerLink session can carry several matches. Score detection comes from
 * exactly one source: the eFootball result screen, read by the Prime capture
 * + OCR chain (see PrimeScreenScoreDetector). The old game-packet goal
 * decoder was removed: it could never attribute scorers, so it could never
 * settle a match, and it cost native per-packet work on the hot path.
 *
 * Lifecycle phases:
 *   NO_MATCH  - no session
 *   WAITING   - session active, gameplay not yet detected
 *   LIVE      - gameplay clock (T0) established by MatchAutomationEngine
 *   SEALED    - epoch finalized via screen OCR / forfeit / no-contest
 *
 * Ledger records are only written for confirmed results (screen OCR or
 * forfeit); every confirmed record settles PeerCoins. Sustained disconnects
 * are recorded as No Contest without settlement.
 */
object MatchTracker {
    private val lock = Any()
    private val _state = MutableStateFlow(LiveMatchState())
    val state: StateFlow<LiveMatchState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var sessionActive = false
    private var sessionId = ""
    private var epochOrdinal = 0
    private var epochStartWallMs = 0L
    private var epochSealed = false

    /** Latest cumulative tunnel counters, pushed by the 1Hz stats poll. */
    @Volatile private var tunnelOutPackets = 0L
    @Volatile private var tunnelInPackets = 0L

    fun beginSession(
        context: Context,
        opponentName: String,
        opponentIp: String,
    ) {
        synchronized(lock) {
            if (sessionActive) endSessionLocked("replaced")
            appContext = context.applicationContext
            sessionActive = true
            sessionId = UUID.randomUUID().toString()
            epochOrdinal = -1
            epochStartWallMs = 0L
            epochSealed = false
            _state.value = LiveMatchState(
                phase = MatchPhase.WAITING,
                opponentName = opponentName,
                opponentIp = opponentIp,
                sessionStartMs = System.currentTimeMillis(),
                statusNote = "Waiting for eFootball game traffic",
            )
            AppState.appendLog("[MATCH] Session $sessionId begun vs $opponentName ($opponentIp)")
        }
    }

    /** Called by the native stats poll so ledger records can cite real counters. */
    fun noteTunnelStats(outPackets: Long, inPackets: Long) {
        tunnelOutPackets = outPackets
        tunnelInPackets = inPackets
    }

    /**
     * MatchAutomationEngine detected the gameplay clock (sustained gameplay
     * PPS after the side-selection gate). Marks the epoch live for the UI and
     * pins the match start time used by the ledger.
     */
    fun markGameplayStarted() {
        synchronized(lock) {
            if (!sessionActive || epochSealed) return
            if (epochStartWallMs == 0L) {
                epochStartWallMs = System.currentTimeMillis()
                epochOrdinal++
                _state.value = _state.value.copy(
                    phase = MatchPhase.LIVE,
                    matchStartMs = epochStartWallMs,
                    statusNote = "Match live",
                )
                AppState.appendLog("[MATCH] Match epoch $epochOrdinal started at $epochStartWallMs")
            }
        }
    }

    /**
     * Commit a result read directly from the eFootball result screen. Screen
     * evidence is the only settlement-eligible source.
     */
    fun confirmScreenScore(
        myGoals: Int,
        opponentGoals: Int,
        source: String,
        stats: MatchStats? = null,
    ): Boolean = synchronized(lock) {
        if (!sessionActive || myGoals !in 0..20 || opponentGoals !in 0..20) return@synchronized false
        if (epochSealed) return@synchronized false
        val now = System.currentTimeMillis()
        val startedAt = when {
            epochStartWallMs > 0L -> epochStartWallMs
            _state.value.matchStartMs > 0L -> _state.value.matchStartMs
            _state.value.sessionStartMs > 0L -> _state.value.sessionStartMs
            else -> now
        }
        val ordinal = epochOrdinal.coerceAtLeast(0)
        val record = MatchRecord(
            id = "match_${sessionId}_$ordinal",
            sessionId = sessionId,
            segmentOrdinal = ordinal,
            startedAtMs = startedAt,
            endedAtMs = now,
            opponentName = _state.value.opponentName,
            opponentIp = _state.value.opponentIp,
            myGoals = myGoals,
            opponentGoals = opponentGoals,
            totalGoals = myGoals + opponentGoals,
            goals = emptyList(),
            endedBy = "screen",
            gamePackets = tunnelOutPackets + tunnelInPackets,
            outgoingPackets = tunnelOutPackets,
            incomingPackets = tunnelInPackets,
            telemetryDrops = 0L,
            telemetryParseFailures = 0L,
            protocolDecodeDrops = 0L,
            reconnects = 0,
            attributionTier = "SCREEN_OCR",
            calibrationLoaded = false,
            confirmed = true,
            settlementNote = "Final score verified from eFootball screen ($source)",
            stats = stats,
        )
        val context = appContext ?: return@synchronized false
        val stored = runCatching { MatchStore.append(context, record) }
            .onFailure { AppState.appendLog("[MATCH] Screen-score ledger write failed: ${it.message}") }
            .getOrDefault(false)
        if (!stored) {
            AppState.appendLog("[MATCH] Screen-score record ${record.id} was not appended (duplicate or storage failure)")
            return@synchronized false
        }

        epochSealed = true
        val reward = record.settledReward
        _state.value = _state.value.copy(
            phase = MatchPhase.SEALED,
            myGoals = myGoals,
            opponentGoals = opponentGoals,
            totalGoals = myGoals + opponentGoals,
            lastActivityMs = now,
            lastCompleted = record,
            stats = stats,
            statusNote = if (stats == null) "Score locked — waiting for stats" else "Full time - screen verified",
        )
        AppState.appendLog(
            "[MATCH] SCREEN FINAL $myGoals-$opponentGoals source=$source; " +
                "PeerCoin ${if (reward.totalCents >= 0) "+" else ""}${formatCents(reward.totalCents)}"
        )
        true
    }

    fun attachStats(stats: MatchStats): Boolean = synchronized(lock) {
        if (!sessionActive) return@synchronized false
        val current = _state.value.lastCompleted ?: return@synchronized false
        val context = appContext ?: return@synchronized false
        if (!MatchStore.updateStats(context, current.id, stats)) return@synchronized false
        val updated = current.copy(stats = stats)
        _state.value = _state.value.copy(
            lastCompleted = updated,
            stats = stats,
            statusNote = "Full time - score and stats verified",
        )
        AppState.appendLog("[MATCH] Attached ${stats.rows.size} stat rows to ${current.id}")
        true
    }

    /** Record a verified 3-0/0-3 forfeit using the same local ledger path. */
    fun confirmForfeit(localPlayerLost: Boolean, reason: String): Boolean {
        val mine = if (localPlayerLost) 0 else 3
        val theirs = if (localPlayerLost) 3 else 0
        return confirmScreenScore(mine, theirs, "forfeit:${reason.take(48)}")
    }

    /**
     * Seal an un-attributable sustained disconnect as No Contest. The VPN
     * session stays alive so a later fresh gameplay flow can form a rematch
     * without touching the network data plane.
     */
    fun confirmNoContest(reason: String): Boolean = synchronized(lock) {
        if (!sessionActive) return@synchronized false
        val now = System.currentTimeMillis()
        epochSealed = true
        _state.value = _state.value.copy(
            phase = MatchPhase.SEALED,
            lastActivityMs = now,
            statusNote = "No Contest - ${reason.take(64)}",
        )
        AppState.appendLog("[MATCH] NO CONTEST (${reason.take(64)}); no PeerCoins")
        true
    }

    fun endSession(reason: String) {
        synchronized(lock) {
            if (!sessionActive) return
            endSessionLocked(reason)
        }
    }

    private fun endSessionLocked(reason: String) {
        sessionActive = false
        appContext = null
        epochStartWallMs = 0L
        epochSealed = false
        _state.value = LiveMatchState(
            phase = MatchPhase.NO_MATCH,
            opponentName = _state.value.opponentName,
            opponentIp = _state.value.opponentIp,
            lastCompleted = _state.value.lastCompleted,
        )
        AppState.appendLog("[MATCH] Session ended ($reason)")
    }
}

enum class MatchPhase { NO_MATCH, WAITING, LIVE, SEALED }

data class LiveMatchState(
    val phase: MatchPhase = MatchPhase.NO_MATCH,
    val opponentName: String = "",
    val opponentIp: String = "",
    val sessionStartMs: Long = 0,
    val matchStartMs: Long = 0,
    val myGoals: Int = 0,
    val opponentGoals: Int = 0,
    val totalGoals: Int = 0,
    val lastActivityMs: Long = 0,
    val statusNote: String = "",
    val lastCompleted: MatchRecord? = null,
    val stats: MatchStats? = null,
)
