package com.peerlink.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * F13-v2 match lifecycle owner.
 *
 * One PeerLink session can carry several matches. Each match is an immutable
 * epoch with its own protocol reader, its own handshake evidence and its own
 * ledger record; a sealed epoch is never re-opened, never re-fed and never
 * settled twice. Lifecycle phases follow the verified protocol evidence:
 *
 *   NO_MATCH  - no session or no game channel
 *   WAITING   - session active, game channel not yet seen
 *   STARTING  - game channel born, len-59 fingerprint converging
 *   LIVE      - fingerprint verified, goal events readable
 *   ENDING    - full-time control burst observed, waiting for terminal silence
 *   SEALED    - epoch finalized; settles only if every gate passes
 *
 * Boundaries:
 *  - a second probe->exchange handshake is a NEW MATCH when the current epoch
 *    saw the full-time control burst (or already went silent, or never
 *    converged), or when the handshake runs on a different UDP port pair
 *    than this epoch's game channel (measured on the recorded 1-Sep session:
 *    the second match opened its own flow on a new port). It is a RECONNECT
 *    only when play was live and recent AND the port pair is unchanged - the
 *    score is preserved and the cipher tables are re-learned.
 *  - 60s of game-channel silence seals an epoch (idle end).
 *  - VPN teardown seals an epoch as disconnect, which never settles.
 *
 * Scorer attribution is never inferred here: goals are recorded with their
 * decoded wire fields and stay UNRESOLVED until a calibration capture pins
 * the side encoding (see MatchProtocolReader.AttributionCalibration). A
 * match with unresolved goals - or any match while calibration is not
 * loaded - records for audit but pays no PeerCoins.
 *
 * Delayed telemetry events from a sealed epoch are quarantined by a
 * watermark so they can neither resurrect it nor leak into the next one.
 */
object MatchTracker {
    private val lock = Any()
    private val _state = MutableStateFlow(LiveMatchState())
    val state: StateFlow<LiveMatchState> = _state.asStateFlow()

    /** A reconnect only continues the same epoch while play is this recent. */
    const val RECONNECT_MERGE_MAX_GAP_MS = 45_000L

    private var appContext: Context? = null
    private var sessionActive = false
    private var sessionId = ""
    private var epochOrdinal = 0
    private var reader: MatchProtocolReader.Reader? = null
    private var epochStartWallMs = 0L
    private var epochSealed = false
    private var sealedThroughMs = 0L
    private var quarantinedPackets = 0L
    private var latestOutPackets = 0L
    private var latestInPackets = 0L
    private var latestTelemetryDrops = 0L
    private var latestParseFailures = 0L

    // F15 VPN-path health baselines (cumulative native counters; per-epoch
    // deltas are logged at finalize so a mid-game cutoff becomes attributable:
    // zero gaps => the VPN path was clean, the teardown was game-side).
    private var latestTunGaps = 0L
    private var latestRxGaps = 0L
    private var latestNativeNowMs = 0L
    private var latestTunGapLastMs = 0L
    private var latestRxGapLastMs = 0L
    private var epochStartTunGaps = 0L
    private var epochStartRxGaps = 0L
    private var myGoals = 0
    private var opponentGoals = 0
    private var totalGoals = 0
    private var goals = emptyList<GoalEvent>()

    // --- F15 wire-evidence capture (file-only; see logWireEvidence) ---
    private var evidenceLogged = 0L
    private var evidenceDroppedByThrottle = 0L
    private var lastSteadyEvidenceMs = LongArray(3)  // last log time per steady class 39/59/63
    private var steadyClassLogged = IntArray(3)      // full-detail budget per steady class

    fun beginSession(
        context: Context,
        opponentName: String,
        opponentIp: String,
        baseline: NativeTelemetrySample? = null,
    ) {
        synchronized(lock) {
            if (sessionActive) {
                val now = System.currentTimeMillis()
                reader?.let { current ->
                    current.observeSnapshot(
                        _state.value.lastActivityMs,
                        latestOutPackets,
                        latestInPackets,
                        latestTelemetryDrops,
                        latestParseFailures,
                    )
                    finalizeEpochLocked(
                        current.segmentResult("replaced", completed = false, endedAtMs = now),
                        now,
                    )
                }
                endSessionLocked("replaced")
            }
            appContext = context.applicationContext
            sessionActive = true
            sessionId = UUID.randomUUID().toString()
            epochOrdinal = -1
            reader = null
            epochStartWallMs = 0L
            epochSealed = false
            sealedThroughMs = 0L
            quarantinedPackets = 0L
            latestOutPackets = baseline?.gameOutPackets ?: 0L
            latestInPackets = baseline?.gameInPackets ?: 0L
            latestTelemetryDrops = baseline?.droppedSnapshots ?: 0L
            latestParseFailures = baseline?.telemetryParseFailures ?: 0L
            resetLiveScore()
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

    fun onTelemetry(sample: NativeTelemetrySample) {
        synchronized(lock) {
            if (!sessionActive) return
            val wallNow = System.currentTimeMillis()
            val anchor = if (sample.nativeNowMs > 0L) wallNow - sample.nativeNowMs else 0L
            val lastGameMono = maxOf(sample.lastOutMs, sample.lastInMs)
            val lastGameWall = if (lastGameMono > 0L) anchor + lastGameMono else 0L

            // Preserve the previous poll as the exact baseline for an epoch
            // whose first packet is in this batch. Using this batch's final
            // cumulative totals as the baseline would silently discard up to
            // one second of traffic from every match.
            val sampleOutPackets = maxOf(latestOutPackets, sample.gameOutPackets)
            val sampleInPackets = maxOf(latestInPackets, sample.gameInPackets)
            val sampleTelemetryDrops = maxOf(latestTelemetryDrops, sample.droppedSnapshots)
            val sampleParseFailures = maxOf(latestParseFailures, sample.telemetryParseFailures)

            sample.events.sortedBy { it.ordinal }.forEach { packet ->
                val packetWall = anchor + packet.tsMs
                if (epochSealed && packetWall <= sealedThroughMs) {
                    // Delayed fragment of a sealed epoch: it must never
                    // resurrect the old epoch or bleed into the new one.
                    if (quarantinedPackets < 16 || quarantinedPackets % 100L == 0L) {
                        AppState.appendLog(
                            "[MATCH] Quarantined delayed packet (t=${packet.tsMs}ms, len=${packet.payloadLen}) from a sealed epoch"
                        )
                    }
                    quarantinedPackets++
                    return@forEach
                }
                var current = reader ?: newEpochLocked(packetWall)
                val boundary = current.onPacket(
                    tMs = packetWall,
                    sentByMe = packet.sentByMe,
                    payloadLen = packet.payloadLen,
                    head = packet.head,
                    headLen = packet.headLen,
                    sport = packet.sport,
                    dport = packet.dport,
                )
                // F23: full packet bytes are captured asynchronously in PCAPNG; avoid hex work here.
                if (boundary == MatchProtocolReader.Boundary.REMATCH) {
                    // Game-traffic age, not poll age: the merge window must
                    // measure how long actual play has been silent, or every
                    // second handshake (even minutes after the last game
                    // packet) would look "recent" while polls keep running.
                    val lastGameAge = current.gameSilentForMs(wallNow)
                    val epochPorts = current.gamePortPair
                    val rematchPorts = current.pendingRematchPortPair
                    val portChanged = epochPorts != null && rematchPorts != null && epochPorts != rematchPorts
                    val isReconnect = !portChanged && current.gated &&
                        !current.fullTimeObserved &&
                        lastGameAge <= RECONNECT_MERGE_MAX_GAP_MS
                    if (portChanged) {
                        AppState.appendLog(
                            "[MATCH] Second handshake on a new port pair " +
                                "(${rematchPorts?.first}<->${rematchPorts?.second} vs ${epochPorts?.first}<->${epochPorts?.second}) - sealing epoch, starting a new match"
                        )
                    }
                    if (isReconnect) {
                        current.onReconnect()
                        AppState.appendLog(
                            "[MATCH] Reconnect handshake - same match continues (goals ${totalGoals} total, cipher tables re-learned)"
                        )
                        _state.value = _state.value.copy(
                            phase = MatchPhase.STARTING,
                            statusNote = "Reconnected - re-verifying both directions",
                        )
                    } else {
                        val probeSeed = current.pendingProbeForNextEpoch()
                        current.observeSnapshot(
                            lastGameWall, latestOutPackets, latestInPackets,
                            latestTelemetryDrops, latestParseFailures,
                        )
                        finalizeEpochLocked(
                            current.segmentResult("boundary", completed = true, endedAtMs = packetWall),
                            packetWall,
                        )
                        // A boundary can sit in the middle of a one-second poll.
                        // Starting the new epoch at the poll's final counters is
                        // conservative: it can undercount this one batch but can
                        // never credit old-match packets to the new match.
                        newEpochLocked(
                            packetWall,
                            sampleOutPackets,
                            sampleInPackets,
                            sampleTelemetryDrops,
                            sampleParseFailures,
                        )
                        current = reader ?: return@forEach
                        // F15: the probe that opened this handshake arrived
                        // BEFORE the boundary fired, so the fresh reader never
                        // saw it. Seed it - otherwise every rematch-started
                        // epoch reports "match start was not proven".
                        probeSeed?.let { current.seedProbe(it) }
                        current.onPacket(
                            packetWall, packet.sentByMe, packet.payloadLen,
                            packet.head, packet.headLen, packet.sport, packet.dport,
                        )
                    }
                }
            }

            // Cumulative values make loss visible even if an SPSC ring fills
            // before the next one-second drain.
            latestOutPackets = sampleOutPackets
            latestInPackets = sampleInPackets
            latestTelemetryDrops = sampleTelemetryDrops
            latestParseFailures = sampleParseFailures
            latestTunGaps = sample.tunGaps1s
            latestRxGaps = sample.rxGaps1s
            latestNativeNowMs = sample.nativeNowMs
            latestTunGapLastMs = sample.tunGapLastMs
            latestRxGapLastMs = sample.rxGapLastMs

            val current = reader
            if (current != null && !epochSealed) {
                current.observeSnapshot(
                    lastGameWall, latestOutPackets, latestInPackets,
                    latestTelemetryDrops, latestParseFailures,
                )
                val phase = _state.value.phase
                if (current.gated && (phase == MatchPhase.STARTING || phase == MatchPhase.WAITING)) {
                    _state.value = _state.value.copy(
                        phase = MatchPhase.LIVE,
                        statusNote = "Match verified - reading goal events (scorer attribution pending calibration)",
                    )
                    AppState.appendLog("[MATCH] Bidirectional eFootball fingerprint verified")
                }
                if (current.fullTimeObserved && phase == MatchPhase.LIVE) {
                    _state.value = _state.value.copy(
                        phase = MatchPhase.ENDING,
                        statusNote = "Full-time control burst observed - awaiting terminal silence",
                    )
                    AppState.appendLog("[MATCH] Full-time control burst observed")
                }
                updateLiveScore(current)

                if (current.checkIdleEnd(wallNow)) {
                    finalizeEpochLocked(
                        current.segmentResult("idle", completed = true, endedAtMs = wallNow),
                        wallNow,
                    )
                    // Critical lifecycle rule: a finalized reader is never reused.
                    // This prevents a later rematch exchange from sealing/paying
                    // it twice.
                    reader = null
                    epochStartWallMs = 0L
                    epochSealed = true
                    sealedThroughMs = wallNow
                } else if (!current.gated && current.gameSilentForMs(wallNow) >= MatchProtocolReader.IDLE_END_SILENCE_MS) {
                    // An epoch that never converged and has been silent for the
                    // full idle window proves nothing: no fingerprint, no
                    // handshake, no goals. Drop it instead of letting the next
                    // match's traffic (measured 1-Sep trace A: match 2 opened
                    // 4 minutes after match 1's tail went quiet) absorb into it.
                    AppState.appendLog(
                        "[MATCH] Discarded un-gated silent epoch (no fingerprint, no handshake, nothing recorded)"
                    )
                    reader = null
                    epochStartWallMs = 0L
                    epochSealed = true
                    sealedThroughMs = wallNow
                }
            }
            publish(wallNow)
        }
    }


    /**
     * Commit a result read directly from the eFootball result screen. Screen
     * evidence resolves the final score without relying on per-goal packet
     * attribution, so it is eligible for settlement on its own.
     *
     * The current protocol reader is deliberately retained but the epoch is
     * sealed. That lets its rematch-boundary logic consume the harmless
     * post-match tail and open a fresh epoch later without creating a duplicate
     * record for this already-confirmed match.
     */
    fun confirmScreenScore(
        myGoals: Int,
        opponentGoals: Int,
        source: String,
    ): Boolean = synchronized(lock) {
        if (!sessionActive || myGoals !in 0..20 || opponentGoals !in 0..20) return@synchronized false
        val now = System.currentTimeMillis()
        val startedAt = when {
            epochStartWallMs > 0L -> epochStartWallMs
            _state.value.matchStartMs > 0L -> _state.value.matchStartMs
            _state.value.sessionStartMs > 0L -> _state.value.sessionStartMs
            else -> now
        }
        val current = reader
        current?.observeSnapshot(
            maxOf(_state.value.lastActivityMs, now),
            latestOutPackets,
            latestInPackets,
            latestTelemetryDrops,
            latestParseFailures,
        )
        val audit = current?.segmentResult("screen", completed = true, endedAtMs = now)
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
            gamePackets = (audit?.outgoingPackets ?: latestOutPackets) + (audit?.incomingPackets ?: latestInPackets),
            outgoingPackets = audit?.outgoingPackets ?: latestOutPackets,
            incomingPackets = audit?.incomingPackets ?: latestInPackets,
            telemetryDrops = audit?.telemetryDrops ?: latestTelemetryDrops,
            telemetryParseFailures = audit?.telemetryParseFailures ?: latestParseFailures,
            protocolDecodeDrops = audit?.protocolDecodeDrops ?: 0L,
            reconnects = audit?.reconnects ?: 0,
            attributionTier = "SCREEN_OCR",
            calibrationLoaded = audit?.calibrationLoaded ?: false,
            handshakeProbeByMe = audit?.handshake?.probeSentByMe,
            handshakeExchangeByMe = audit?.handshake?.exchangeSentByMe,
            gamePortPair = audit?.portPair,
            quarantinedPackets = quarantinedPackets,
            confirmed = true,
            settlementNote = "Final score verified from eFootball screen ($source)",
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
        sealedThroughMs = now
        this.myGoals = myGoals
        this.opponentGoals = opponentGoals
        this.totalGoals = myGoals + opponentGoals
        this.goals = emptyList()
        val reward = record.settledReward
        _state.value = _state.value.copy(
            phase = MatchPhase.SEALED,
            myGoals = myGoals,
            opponentGoals = opponentGoals,
            totalGoals = myGoals + opponentGoals,
            goals = emptyList(),
            lastActivityMs = now,
            lastCompleted = record,
            statusNote = "Full time - screen verified",
        )
        AppState.appendLog(
            "[MATCH] SCREEN FINAL $myGoals-$opponentGoals source=$source; " +
                "PeerCoin ${if (reward.totalCents >= 0) "+" else ""}${formatCents(reward.totalCents)}"
        )
        true
    }

    /** Record a verified 3-0/0-3 forfeit using the same local ledger path. */
    fun confirmForfeit(localPlayerLost: Boolean, reason: String): Boolean {
        val mine = if (localPlayerLost) 0 else 3
        val theirs = if (localPlayerLost) 3 else 0
        return confirmScreenScore(mine, theirs, "forfeit:${reason.take(48)}")
    }


    /**
     * Seal an un-attributable sustained disconnect as No Contest. It is kept in
     * the audit ledger, but completed=false guarantees zero PeerCoin settlement.
     * The VPN/session stays alive so a later fresh gameplay flow can form a
     * rematch without touching the network data plane.
     */
    fun confirmNoContest(reason: String): Boolean = synchronized(lock) {
        if (!sessionActive) return@synchronized false
        val now = System.currentTimeMillis()
        val current = reader
        if (current == null || epochSealed) {
            _state.value = _state.value.copy(
                phase = MatchPhase.SEALED,
                lastActivityMs = now,
                statusNote = "No Contest - ${reason.take(64)}",
            )
            AppState.appendLog("[MATCH] NO CONTEST (${reason.take(64)}); no PeerCoins")
            return@synchronized true
        }
        current.observeSnapshot(
            maxOf(_state.value.lastActivityMs, now),
            latestOutPackets,
            latestInPackets,
            latestTelemetryDrops,
            latestParseFailures,
        )
        finalizeEpochLocked(
            current.segmentResult("no_contest", completed = false, endedAtMs = now),
            now,
        )
        _state.value = _state.value.copy(statusNote = "No Contest - ${reason.take(64)}")
        AppState.appendLog("[MATCH] NO CONTEST (${reason.take(64)}); no PeerCoins")
        true
    }

    fun endSession(reason: String) {
        synchronized(lock) {
            if (!sessionActive) return
            val now = System.currentTimeMillis()
            reader?.let { current ->
                if (!epochSealed) {
                    current.observeSnapshot(
                        maxOf(_state.value.lastActivityMs, now),
                        latestOutPackets, latestInPackets,
                        latestTelemetryDrops, latestParseFailures,
                    )
                    // Disconnect/service teardown is never proof of full time.
                    // Record it for audit, but it cannot settle PeerCoins.
                    finalizeEpochLocked(
                        current.segmentResult("disconnect", completed = false, endedAtMs = now),
                        now,
                    )
                }
            }
            endSessionLocked(reason)
        }
    }

    private fun newEpochLocked(
        startedAtMs: Long,
        baselineOutPackets: Long = latestOutPackets,
        baselineInPackets: Long = latestInPackets,
        baselineTelemetryDrops: Long = latestTelemetryDrops,
        baselineParseFailures: Long = latestParseFailures,
    ): MatchProtocolReader.Reader {
        epochStartWallMs = startedAtMs
        epochSealed = false
        sealedThroughMs = 0L
        epochOrdinal++
        epochStartTunGaps = latestTunGaps
        epochStartRxGaps = latestRxGaps
        resetLiveScore()
        val ordinal = epochOrdinal
        val reader = MatchProtocolReader.Reader(
            startedAtMs = startedAtMs,
            baselineOutPackets = baselineOutPackets,
            baselineInPackets = baselineInPackets,
            baselineTelemetryDrops = baselineTelemetryDrops,
            baselineParseFailures = baselineParseFailures,
            calibration = MatchCalibration.loaded,
        )
        this.reader = reader
        _state.value = _state.value.copy(
            phase = MatchPhase.STARTING,
            matchStartMs = startedAtMs,
            statusNote = "Game channel active - verifying both directions",
        )
        AppState.appendLog("[MATCH] Match epoch $ordinal started at ${startedAtMs}")
        return reader
    }

    /**
     * F23: selected packet heads still feed MatchProtocolReader, but text hex
     * duplication is disabled. The native backend captures every TUN packet
     * byte into the asynchronous PCAPNG stream instead.
     */

    /**
     * F15 cutoff forensics. The 3-Sep session cut off 2 of 3 matches mid-game
     * while both phones' VPN data paths measured clean (sub-3ms forwarding,
     * 1 seq skip in 21,965 packets, no >=1s TUN stalls during the complete
     * match). This line pins each future teardown to its cause: zero TUN/RX
     * gaps at a mid-game end => the game itself closed the session (uplink,
     * session validation or netcode), NOT the VPN forwarding path. Non-zero
     * gap counts with recent last-gap timestamps localize the stall to the
     * TUN-read path (game -> VPN) or the peer-RX path (radio link -> VPN).
     */
    private fun logEpochHealthLocked(durationMs: Long, result: MatchProtocolReader.SegmentResult) {
        val tunGaps = (latestTunGaps - epochStartTunGaps).coerceAtLeast(0L)
        val rxGaps = (latestRxGaps - epochStartRxGaps).coerceAtLeast(0L)
        val nowNative = latestNativeNowMs
        fun sinceLast(gapLastMs: Long): String =
            if (gapLastMs > 0L && nowNative > gapLastMs) "${(nowNative - gapLastMs) / 1000L}s before end"
            else if (gapLastMs > 0L) "at epoch end" else "n/a"
        AppState.appendLog(
            "[HEALTH] epoch $epochOrdinal ended by ${result.endedBy} after ${durationMs / 1000L}s: " +
                "vpnPath tunGaps>=1s=$tunGaps (last ${sinceLast(latestTunGapLastMs)}), " +
                "rxGaps>=1s=$rxGaps (last ${sinceLast(latestRxGapLastMs)}), " +
                "packets out=${result.outgoingPackets} in=${result.incomingPackets}, " +
                "telemetryDrops=${result.telemetryDrops}, parseFailures=${result.telemetryParseFailures}, " +
                "quarantined=$quarantinedPackets" +
                if (tunGaps == 0L && rxGaps == 0L) " - VPN forwarding path was clean; teardown was game-side" else ""
        )
    }

    private fun updateLiveScore(current: MatchProtocolReader.Reader) {
        val events = current.goalEvents()
        val mine = events.count { it.scorerSide == MatchProtocolReader.ScorerSide.ME }
        val theirs = events.count { it.scorerSide == MatchProtocolReader.ScorerSide.PEER }
        if (events.size != totalGoals || mine != myGoals || theirs != opponentGoals) {
            val latest = events.last()
            val fields = latest.fields?.describe() ?: "fields unavailable"
            val echo = if (latest.corroborated) "peer echo confirmed" else "awaiting peer echo"
            val scorer = when (latest.scorerSide) {
                MatchProtocolReader.ScorerSide.UNRESOLVED ->
                    "scorer UNRESOLVED (${latest.reason ?: "calibration pending"}) - settlement blocked"
                MatchProtocolReader.ScorerSide.ME -> "scorer resolved: me"
                MatchProtocolReader.ScorerSide.PEER -> "scorer resolved: peer"
            }
            AppState.appendLog(
                "[MATCH] Goal #${events.size}: ${latest.nMessages} message(s), $echo; " +
                    "wire fields $fields; $scorer"
            )
        }
        myGoals = mine
        opponentGoals = theirs
        totalGoals = events.size
        goals = events.map { GoalEvent.fromReader(it) }
    }

    private fun finalizeEpochLocked(result: MatchProtocolReader.SegmentResult, endedAtMs: Long) {
        if (!result.gated || epochStartWallMs <= 0L) return
        if (epochSealed) return
        val note = result.settlementBlockers.takeIf { it.isNotEmpty() }?.joinToString("; ")
        val record = MatchRecord(
            id = "match_${sessionId}_$epochOrdinal",
            sessionId = sessionId,
            segmentOrdinal = epochOrdinal,
            startedAtMs = epochStartWallMs,
            endedAtMs = endedAtMs,
            opponentName = _state.value.opponentName,
            opponentIp = _state.value.opponentIp,
            myGoals = result.myGoals,
            opponentGoals = result.opponentGoals,
            totalGoals = result.totalGoals,
            goals = result.events.map { GoalEvent.fromReader(it) },
            endedBy = result.endedBy,
            gamePackets = result.outgoingPackets + result.incomingPackets,
            outgoingPackets = result.outgoingPackets,
            incomingPackets = result.incomingPackets,
            telemetryDrops = result.telemetryDrops,
            telemetryParseFailures = result.telemetryParseFailures,
            protocolDecodeDrops = result.protocolDecodeDrops,
            reconnects = result.reconnects,
            attributionTier = result.attribution.name,
            calibrationLoaded = result.calibrationLoaded,
            handshakeProbeByMe = result.handshake?.probeSentByMe,
            handshakeExchangeByMe = result.handshake?.exchangeSentByMe,
            gamePortPair = result.portPair,
            quarantinedPackets = quarantinedPackets,
            confirmed = result.settleable,
            settlementNote = note,
        )
        epochSealed = true
        sealedThroughMs = maxOf(sealedThroughMs, endedAtMs)
        val stored = appContext?.let { context ->
            runCatching { MatchStore.append(context, record) }
                .onFailure { AppState.appendLog("[MATCH] Ledger write failed: ${it.message}") }
                .getOrDefault(false)
        } ?: false
        if (!stored) {
            AppState.appendLog("[MATCH] Record ${record.id} was not appended (duplicate or storage failure)")
        }
        val reward = record.settledReward
        AppState.appendLog(
            "[MATCH] FINAL ${record.myGoals}-${record.opponentGoals} of ${record.totalGoals} goal(s); " +
                if (record.confirmed) {
                    "PeerCoin ${if (reward.totalCents >= 0) "+" else ""}${formatCents(reward.totalCents)}"
                } else {
                    "UNVERIFIED - no PeerCoins (${record.settlementNote})"
                }
        )
        logEpochHealthLocked(endedAtMs - epochStartWallMs, result)
        _state.value = _state.value.copy(
            phase = MatchPhase.SEALED,
            myGoals = record.myGoals,
            opponentGoals = record.opponentGoals,
            totalGoals = record.totalGoals,
            goals = record.goals,
            lastCompleted = record,
            statusNote = if (record.confirmed) "Full time - settled" else "Recorded, not settled: ${record.settlementNote}",
        )
    }

    private fun endSessionLocked(reason: String) {
        sessionActive = false
        appContext = null
        reader = null
        epochStartWallMs = 0L
        epochSealed = false
        sealedThroughMs = 0L
        resetLiveScore()
        _state.value = LiveMatchState(
            phase = MatchPhase.NO_MATCH,
            opponentName = _state.value.opponentName,
            opponentIp = _state.value.opponentIp,
            lastCompleted = _state.value.lastCompleted,
        )
        AppState.appendLog("[MATCH] Session ended ($reason)")
    }

    private fun resetLiveScore() {
        myGoals = 0
        opponentGoals = 0
        totalGoals = 0
        goals = emptyList()
    }

    private fun publish(nowMs: Long) {
        _state.value = _state.value.copy(
            lastActivityMs = nowMs,
            myGoals = myGoals,
            opponentGoals = opponentGoals,
            totalGoals = totalGoals,
            goals = goals,
            matchStartMs = if (epochStartWallMs > 0L) epochStartWallMs else _state.value.matchStartMs,
        )
    }
}

enum class MatchPhase { NO_MATCH, WAITING, STARTING, LIVE, ENDING, SEALED }

data class LiveMatchState(
    val phase: MatchPhase = MatchPhase.NO_MATCH,
    val opponentName: String = "",
    val opponentIp: String = "",
    val sessionStartMs: Long = 0,
    val matchStartMs: Long = 0,
    val myGoals: Int = 0,
    val opponentGoals: Int = 0,
    val totalGoals: Int = 0,
    val goals: List<GoalEvent> = emptyList(),
    val lastActivityMs: Long = 0,
    val statusNote: String = "",
    val lastCompleted: MatchRecord? = null,
)

data class NativeTelemetrySample(
    val gameOutPackets: Long = 0,
    val gameInPackets: Long = 0,
    val gameOutBytes: Long = 0,
    val gameInBytes: Long = 0,
    val lastOutMs: Long = 0,
    val lastInMs: Long = 0,
    val firstTrafficMs: Long = 0,
    val nativeNowMs: Long = 0,
    val droppedSnapshots: Long = 0,
    val tunGaps1s: Long = 0,
    val tunGapLastMs: Long = 0,
    val rxGaps1s: Long = 0,
    val rxGapLastMs: Long = 0,
    val telemetryParseFailures: Long = 0,
    val events: List<NativePacketEvent> = emptyList(),
)

data class NativePacketEvent(
    val ordinal: Long,
    val tsMs: Long,
    val sentByMe: Boolean,
    val payloadLen: Int,
    val headLen: Int,
    val head: ByteArray,
    val sport: Int = 0,
    val dport: Int = 0,
)
