package com.peerlink.app.core

/**
 * F15 goal-truth protocol reader — the F13-v2 score-truth structure with the
 * 3-Sep real-match failure modes fixed.
 *
 * Reads the measured eFootball P2P session evidence without delaying packets:
 *  - the len-14 probe / len-266,272 exchange handshake that opens every match
 *    epoch (probe sender = protocol side 1, exchange responder = side 2),
 *  - the per-direction len-59 modal tables (per-session keystream: 0/51 table
 *    bytes repeat across the 28_Jun and 29_Jan captures),
 *  - the goal-summary family, decoded under BOTH protocol roles so either
 *    phone detects the same goal (the clean role is the sender's own),
 *  - the bidirectional len-26 full-time control burst.
 *
 * F15 changes, driven by the recorded 3-Sep 3:0 session:
 *  1. CORROBORATION GATE. Every match fired exactly one "goal" 7-10s after
 *     keystream convergence - the kickoff/state summary (payload ~1197,
 *     single direction, no peer echo). A real goal (both January captures)
 *     is a bidirectional cluster: the original from one side, the echo/sync
 *     from the other within the echo window. Clusters whose decoded
 *     goal-family messages are ALL one-directional are now recorded as
 *     unilateral summaries and never counted as goals.
 *  2. MIN_GOAL_PAYLOAD 400 -> 200. The 3-Sep half-time cluster carried
 *     228-719 byte payloads; 400 would have discarded the small members.
 *  3. STUN IDENTITY (R1 port). Custom-STUN frames carry 10-digit ASCII
 *     player IDs; the modal ID per direction identifies each side's player
 *     - recorded with every segment for audit.
 *  4. PROBE SEEDING. A rematch boundary fires on the exchange packet; the
 *     probe that preceded it belongs to the NEW epoch but arrives before it
 *     exists. The old reader exposes the pending probe so the tracker seeds
 *     the new epoch - "match start was not proven" no longer blocks every
 *     second match of a session.
 *
 * SCORER ATTRIBUTION (F13-v2, corrected, unchanged): a fixed session role
 * cannot attribute per-goal scorers; the v1 responder rule was confounded
 * and removed. The 91/90-style pair values are score-progression DATA.
 * Attribution requires a CALIBRATION capture (both players score); until
 * then every goal is UNRESOLVED and PeerCoin settlement is blocked.
 *
 * This reader is deliberately fail-closed for PeerCoin settlement and never
 * runs on the native forwarding thread.
 */
object MatchProtocolReader {
    const val TABLE_SIZE = 51
    const val GATE_LEN59_MIN = 40
    const val MIN_GOAL_PAYLOAD = 200
    const val CLUSTER_GAP_MS = 10_000L
    const val ECHO_WINDOW_MS = 12_000L
    const val IDLE_END_SILENCE_MS = 60_000L
    const val PROBE_LEN = 14
    const val EXCHANGE_LEN_A = 266
    const val EXCHANGE_LEN_B = 272
    const val HANDSHAKE_WINDOW_MS = 10_000L
    const val FULL_TIME_CONTROL_LEN = 26
    const val FULL_TIME_BURST_WINDOW_MS = 8_000L
    const val FULL_TIME_BURST_MIN_PER_DIRECTION = 20
    const val FULL_TIME_MIN_DURATION_MS = 90_000L

    /**
     * Measured post-burst trailing game traffic before the channel goes
     * silent: 3.1s (28_Jun), ~0s (29_Jan), 28.1s (recorded 1-Sep match 1 -
     * the result/stats exchange). v1 used 10s, which rejected the one real
     * completed match we hold; 40s covers the measured maximum with margin.
     * Kickoff len-26 bursts occur before the protocol gate and never count.
     */
    const val FULL_TIME_MAX_TRAILING_GAME_MS = 40_000L
    const val MIN_SETTLEMENT_PACKETS_PER_DIRECTION = 100L
    const val MIN_SETTLEMENT_DURATION_MS = 30_000L
    const val MAX_PRE_GATE_EVENT_HEADS = 512
    const val MAX_CAPTURED_HEAD = 60

    private val STUN_MAGIC = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
    private val GOAL_TAIL = byteArrayOf(
        0x90.toByte(), 0x90.toByte(), 0x0E.toByte(), 0x0E.toByte(),
        0x8E.toByte(), 0x2E.toByte(), 0x8E.toByte(), 0x2E.toByte(),
    )

    /**
     * Per-goal scorer calibration, pinned by ONE capture of a match in which
     * both players score. All three fields are derived by diffing the
     * goal-summary field values of a me-scored goal against a peer-scored
     * goal in the same session:
     *  - [scoreMask]: xor mask carried by the score-pair bytes (the working
     *    hypothesis from the observed 0x91/0x90 pair on 1-0 first goals is
     *    0x90, i.e. plain 0x90|score);
     *  - [probeSenderScoreIndex]: which byte of the pair is the probe
     *    sender's side score (0 = decoded[6], 1 = decoded[8]);
     *  - [validated]: set only after the calibration replay confirms the
     *    masked pairs form a valid non-decreasing score progression on the
     *    calibration capture itself.
     * Until a calibration is loaded, every goal resolves UNRESOLVED and no
     * match settles - including goalless matches, because "0-0" then rests
     * on an uncalibrated detector rather than on proven attribution.
     */
    data class AttributionCalibration(
        val scoreMask: Int,
        val probeSenderScoreIndex: Int,
        val validated: Boolean,
    )

    enum class Boundary { REMATCH }

    enum class ScorerSide { UNRESOLVED, ME, PEER }
    enum class AttributionTier { UNPROVEN, CALIBRATED }

    /**
     * Per-goal wire field values, as decoded from the goal-summary plaintext.
     * These are the calibration inputs: a match with goals from BOTH players
     * is diffed against these values to pin the side encoding.
     */
    data class GoalFields(
        val scoreByteA: Int,
        val scoreByteB: Int,
        val tagByteA: Int,
        val tagByteB: Int,
        val postTail: List<Int>,
        val vGroup: List<Int>,
    ) {
        /**
         * Compares only the attribution-relevant candidate fields. postTail
         * and vGroup legitimately differ per direction in the real captures
         * (28_Jun OUT: 8c 8c 98..., IN: 8c 84 d2...) - they are per-direction
         * session values, not per-goal values, and must never flag a
         * disagreement.
         */
        fun sameAttributionFieldsAs(other: GoalFields): Boolean =
            scoreByteA == other.scoreByteA && scoreByteB == other.scoreByteB &&
                tagByteA == other.tagByteA && tagByteB == other.tagByteB

        fun describe(): String =
            "score=%02x/%02x tag=%02x/%02x postTail=%s vGroup=%s".format(
                scoreByteA, scoreByteB, tagByteA, tagByteB,
                postTail.joinToString("") { "%02x".format(it) },
                vGroup.joinToString("") { "%02x".format(it) },
            )
    }

    data class GoalEvent(
        val tMs: Long,
        val firstSentByMe: Boolean,
        val senderRole: Int,
        val payloadLen: Int,
        val nMessages: Int,
        val corroborated: Boolean,
        val echoAfterMs: Long?,
        val fields: GoalFields?,
        val fieldsDisagree: Boolean,
        val scorerSide: ScorerSide,
        val attribution: AttributionTier,
        val reason: String?,
    )

    /**
     * Session-setup evidence. This is METADATA: it identifies which phone is
     * protocol side 1 (probe sender) and side 2 (exchange responder) and on
     * which UDP ports the epoch ran, so that a calibrated side encoding can
     * be translated to "me"/"peer". It deliberately carries NO scorer
     * inference - the v1 `scorerIsMe = exchangeSentByMe` was confounded and
     * has been removed.
     */
    data class HandshakeEvidence(
        val probeAtMs: Long,
        val probeSentByMe: Boolean,
        val exchangeAtMs: Long,
        val exchangeSentByMe: Boolean,
        val portPair: Pair<Int, Int>?,
    )

    /**
     * A probe observed just before a rematch boundary, handed to the new
     * epoch so its handshake evidence is complete (probe + exchange).
     */
    data class HandshakeSeed(
        val tMs: Long,
        val sentByMe: Boolean,
        val portPair: Pair<Int, Int>?,
    )

    data class SegmentResult(
        val gated: Boolean,
        val tableConvergedAtMs: Long,
        val handshake: HandshakeEvidence?,
        val events: List<GoalEvent>,
        val myGoals: Int,
        val opponentGoals: Int,
        val totalGoals: Int,
        val unilateralSummaries: Int,
        val attribution: AttributionTier,
        val calibrationLoaded: Boolean,
        val localPlayerId: String?,
        val remotePlayerId: String?,
        val endedBy: String,
        val completed: Boolean,
        val settleable: Boolean,
        val settlementBlockers: List<String>,
        val outgoingPackets: Long,
        val incomingPackets: Long,
        val telemetryDrops: Long,
        val telemetryParseFailures: Long,
        val protocolDecodeDrops: Long,
        val durationMs: Long,
        val reconnects: Int,
        val portPair: Pair<Int, Int>?,
    )

    class Reader(
        private val startedAtMs: Long,
        private val baselineOutPackets: Long,
        private val baselineInPackets: Long,
        private val baselineTelemetryDrops: Long,
        private val baselineParseFailures: Long,
        private val calibration: AttributionCalibration? = null,
    ) {
        private var aCounts = Array(TABLE_SIZE) { IntArray(256) }
        private var bCounts = Array(TABLE_SIZE) { IntArray(256) }
        private var n59A = 0
        private var n59B = 0
        private var tables: Pair<IntArray, IntArray>? = null
        private var lastProbeMs = 0L
        private var lastProbeSentByMe = false
        private var handshake: HandshakeEvidence? = null
        private var epochPortPair: Pair<Int, Int>? = null
        private var rematchPortPair: Pair<Int, Int>? = null
        private var lastGameMs = 0L
        private var lastSubstantiveGameMs = 0L
        private var fullTimeBurstAtMs = 0L
        private val fullTimeControlA = java.util.ArrayDeque<Long>()
        private val fullTimeControlB = java.util.ArrayDeque<Long>()
        private var latestOutPackets = baselineOutPackets
        private var latestInPackets = baselineInPackets
        private var latestTelemetryDrops = baselineTelemetryDrops
        private var latestParseFailures = baselineParseFailures
        private val goalMessages = ArrayList<GoalMessage>()
        private val preGateEvents = ArrayList<PendingEvent>()
        private var protocolDecodeDrops = 0L
        private var reconnects = 0
        private val stunIdsMine = HashMap<String, Int>()
        private val stunIdsTheirs = HashMap<String, Int>()
        private var lastUnilateralCount = 0

        private data class GoalMessage(
            val tMs: Long,
            val sentByMe: Boolean,
            val senderRole: Int,
            val payloadLen: Int,
            val decoded: IntArray,
            val head: ByteArray,
        )

        private data class PendingEvent(
            val tMs: Long,
            val sentByMe: Boolean,
            val payloadLen: Int,
            val head: ByteArray,
            val headLen: Int,
        )

        var tableConvergedAtMs: Long = 0L
            private set
        val gated: Boolean get() = tables != null
        val epochHandshake: HandshakeEvidence? get() = handshake
        val fullTimeObserved: Boolean get() = fullTimeBurstAtMs > 0L
        val reconnectCount: Int get() = reconnects

        /** UDP port pair of the epoch's game channel, when telemetry carries ports. */
        val gamePortPair: Pair<Int, Int>? get() = epochPortPair

        /** Port pair of a handshake that triggered a REMATCH boundary, if any. */
        val pendingRematchPortPair: Pair<Int, Int>? get() = rematchPortPair

        fun observeSnapshot(
            lastGameWallMs: Long,
            outPackets: Long,
            inPackets: Long,
            telemetryDrops: Long,
            parseFailures: Long,
        ) {
            if (lastGameWallMs > lastGameMs) lastGameMs = lastGameWallMs
            latestOutPackets = maxOf(latestOutPackets, outPackets)
            latestInPackets = maxOf(latestInPackets, inPackets)
            latestTelemetryDrops = maxOf(latestTelemetryDrops, telemetryDrops)
            latestParseFailures = maxOf(latestParseFailures, parseFailures)
        }

        /**
         * Returns REMATCH when a second probe->exchange handshake completes
         * inside this epoch; the owner decides whether that is a reconnect
         * (same epoch, tables re-learned, score preserved) or a new match
         * (seal first), using [pendingRematchPortPair] and the merge window.
         * The boundary is evaluated before any table or full-time state so a
         * mid-poll split can never half-apply.
         */
        fun onPacket(
            tMs: Long,
            sentByMe: Boolean,
            payloadLen: Int,
            head: ByteArray,
            headLen: Int,
            sport: Int = 0,
            dport: Int = 0,
        ): Boundary? {
            if (payloadLen < 8 || headLen < 8 || headLen > head.size) return null
            val stun = isStun(head, headLen)
            if (!stun && payloadLen == PROBE_LEN) {
                lastProbeMs = tMs
                lastProbeSentByMe = sentByMe
            } else if (!stun && (payloadLen == EXCHANGE_LEN_A || payloadLen == EXCHANGE_LEN_B)) {
                if (lastProbeMs > 0L && tMs - lastProbeMs in 0L..HANDSHAKE_WINDOW_MS) {
                    val portPair = portPairOf(sport, dport)
                    if (handshake == null) {
                        handshake = HandshakeEvidence(
                            probeAtMs = lastProbeMs,
                            probeSentByMe = lastProbeSentByMe,
                            exchangeAtMs = tMs,
                            exchangeSentByMe = sentByMe,
                            portPair = portPair,
                        )
                        if (epochPortPair == null && portPair != null) epochPortPair = portPair
                    } else if (noGoalFamilySince(lastProbeMs - ECHO_WINDOW_MS)) {
                        // A second handshake in the same epoch. Goal-window
                        // len-266/272 traffic must not masquerade as one.
                        rematchPortPair = portPair ?: epochPortPair
                        return Boundary.REMATCH
                    }
                }
            }
            if (stun) {
                observeStunIdentity(sentByMe, head, headLen)
                return null
            }
            if (tMs > lastGameMs) lastGameMs = tMs
            if (payloadLen != PROBE_LEN && payloadLen != EXCHANGE_LEN_A && payloadLen != EXCHANGE_LEN_B) {
                if (tMs > lastSubstantiveGameMs) lastSubstantiveGameMs = tMs
                if (epochPortPair == null) epochPortPair = portPairOf(sport, dport)
            }

            if (tables == null) {
                if (payloadLen == 59 && headLen >= 8 + TABLE_SIZE) {
                    val counts = if (sentByMe) aCounts else bCounts
                    for (j in 0 until TABLE_SIZE) {
                        counts[j][head[8 + j].toInt() and 0xFF]++
                    }
                    if (sentByMe) n59A++ else n59B++
                    if (n59A >= GATE_LEN59_MIN && n59B >= GATE_LEN59_MIN) {
                        tables = modeTable(aCounts) to modeTable(bCounts)
                        tableConvergedAtMs = tMs
                        preGateEvents.forEach { pending ->
                            decodePotentialGoal(
                                pending.tMs,
                                pending.sentByMe,
                                pending.payloadLen,
                                pending.head,
                                pending.headLen,
                            )
                        }
                        preGateEvents.clear()
                    }
                } else if (payloadLen >= MIN_GOAL_PAYLOAD && headLen >= 34) {
                    // Do not lose an early goal merely because the steady
                    // len-59 table has not converged yet. Only the bytes the
                    // decoder can consume are retained, with a strict bound.
                    if (preGateEvents.size < MAX_PRE_GATE_EVENT_HEADS) {
                        preGateEvents += PendingEvent(
                            tMs,
                            sentByMe,
                            payloadLen,
                            head.copyOf(MAX_CAPTURED_HEAD),
                            minOf(headLen, MAX_CAPTURED_HEAD),
                        )
                    } else {
                        protocolDecodeDrops++
                    }
                }
                return null
            }

            // A quiet UDP flow is not, by itself, proof of full time: it can
            // also mean radio loss, a game crash or a peer disconnect. Every
            // observed complete match contains the same bidirectional len-26
            // control burst immediately before game traffic stops (28_Jun:
            // 113 packets t+299-302; 29_Jan: 65 packets t+278-280; the
            // recorded 1-Sep match: 45 packets at 18:33:49.5), while the
            // kickoff bursts occur before the protocol gate. Require that
            // measured end marker; otherwise the audit record pays no coins.
            if (payloadLen == FULL_TIME_CONTROL_LEN) {
                observeFullTimeControl(tMs, sentByMe)
            }

            decodePotentialGoal(tMs, sentByMe, payloadLen, head, headLen)
            return null
        }

        /**
         * A reconnect keeps the same epoch: the score is preserved and the
         * tables are re-learned because the 256-family exchange that
         * precedes the handshake re-keys the stream.
         */
        fun onReconnect() {
            aCounts = Array(TABLE_SIZE) { IntArray(256) }
            bCounts = Array(TABLE_SIZE) { IntArray(256) }
            n59A = 0
            n59B = 0
            tables = null
            tableConvergedAtMs = 0L
            fullTimeBurstAtMs = 0L
            fullTimeControlA.clear()
            fullTimeControlB.clear()
            reconnects++
        }

        private fun portPairOf(sport: Int, dport: Int): Pair<Int, Int>? =
            // A UDP flow is unordered: store the normalized pair so an
            // incoming (sport, dport) and an outgoing (dport, sport) of the
            // same flow can never masquerade as a port change.
            if (sport > 0 && dport > 0) Pair(minOf(sport, dport), maxOf(sport, dport)) else null

        private fun noGoalFamilySince(sinceMs: Long): Boolean =
            goalMessages.none { it.tMs >= sinceMs } && preGateEvents.none { it.tMs >= sinceMs }

        /**
         * Dual-role decode (F12, preserved): each message decodes cleanly
         * under exactly one role - the sender's own protocol side (the clean
         * role was the sender's side in every decodable capture, never the
         * scorer's). Trying both roles makes detection perspective-safe:
         * the same goal is recognized on either phone.
         *
         * Detection requires the duplicated-pair structure plus the
         * GOAL_TAIL. The v1 xor-of-pair constraints were dropped on purpose:
         * they encode score-progression data (first-goal 1-0 pairs) and would
         * reject an equalizer (91/91) or a second goal (92/90), leaving the
         * reader blind to every goal after the first.
         */
        private fun decodePotentialGoal(
            tMs: Long,
            sentByMe: Boolean,
            payloadLen: Int,
            head: ByteArray,
            headLen: Int,
        ) {
            val (tableA, tableB) = tables ?: return
            if (headLen < 34 || headLen > head.size) return
            val table = if (sentByMe) tableA else tableB
            for (role in 1..2) {
                val messageKey = ((head[19].toInt() and 0xFF) xor table[11]) xor role
                val decodedLen = if (headLen >= 8 + TABLE_SIZE) TABLE_SIZE else 26
                val decoded = IntArray(decodedLen)
                for (j in 0 until decodedLen) {
                    decoded[j] = (head[8 + j].toInt() and 0xFF) xor table[j] xor messageKey
                }
                val structure = decoded[6] == decoded[7] &&
                    decoded[8] == decoded[9] &&
                    decoded[12] == decoded[13] &&
                    decoded[14] == decoded[15] &&
                    decoded[15] == decoded[16]
                if (!structure || payloadLen < MIN_GOAL_PAYLOAD) continue
                var tail = true
                for (index in GOAL_TAIL.indices) {
                    if (decoded[18 + index] != (GOAL_TAIL[index].toInt() and 0xFF)) {
                        tail = false
                        break
                    }
                }
                if (!tail) continue
                val captured = if (headLen >= MAX_CAPTURED_HEAD) head.copyOf(MAX_CAPTURED_HEAD) else head.copyOf(headLen)
                goalMessages += GoalMessage(tMs, sentByMe, role, payloadLen, decoded, captured)
                break
            }
        }

        /**
         * The most recent len-14 probe observed by this reader, with the port
         * pair of the handshake that fired the boundary (the rematch pair
         * when set, else this epoch's pair). The tracker seeds a NEW epoch
         * with it at a rematch boundary so the exchange packet that fired
         * the boundary completes the new epoch's handshake evidence instead
         * of leaving it unproven.
         */
        fun pendingProbeForNextEpoch(): HandshakeSeed? =
            if (lastProbeMs > 0L) {
                HandshakeSeed(lastProbeMs, lastProbeSentByMe, rematchPortPair ?: epochPortPair)
            } else {
                null
            }

        /** Seeds this reader with a probe observed just before the epoch began. */
        fun seedProbe(seed: HandshakeSeed) {
            if (lastProbeMs <= 0L) {
                lastProbeMs = seed.tMs
                lastProbeSentByMe = seed.sentByMe
                if (epochPortPair == null) epochPortPair = seed.portPair
            }
        }

        /** Modal 10-digit player ID seen on MY outgoing custom-STUN frames. */
        fun localPlayerId(): String? = stunIdsMine.maxByOrNull { it.value }?.key

        /** Modal 10-digit player ID seen on the peer's incoming custom-STUN frames. */
        fun remotePlayerId(): String? = stunIdsTheirs.maxByOrNull { it.value }?.key

        /**
         * Number of unilateral goal-family summaries (single-direction
         * clusters: kickoff/state sync markers) - evidence, never goals.
         */
        fun unilateralSummaries(): Int = countUnilateralClusters().first

        private fun countUnilateralClusters(): Pair<Int, List<List<GoalMessage>>> {
            val clusters = clusterGoalMessages()
            val unilateral = clusters.filter { cluster -> cluster.all { it.sentByMe == cluster.first().sentByMe } }
            return unilateral.size to unilateral
        }

        private fun clusterGoalMessages(): List<List<GoalMessage>> {
            val clusters = ArrayList<ArrayList<GoalMessage>>()
            goalMessages.sortedBy { it.tMs }.forEach { message ->
                if (clusters.isNotEmpty() && message.tMs - clusters.last().last().tMs <= CLUSTER_GAP_MS) {
                    clusters.last().add(message)
                } else {
                    clusters.add(arrayListOf(message))
                }
            }
            return clusters
        }

        private fun observeStunIdentity(sentByMe: Boolean, head: ByteArray, headLen: Int) {
            // eFootball custom STUN types 0x08xx/0x09xx carry 10-digit ASCII
            // player IDs (attribute 0x9090). The modal ID per direction pins
            // each side's player identity for audit; it never attributes
            // scorers.
            if (headLen < 32) return
            val typeHi = head[0].toInt() and 0xFF
            val typeLo = head[1].toInt() and 0xFF
            if (typeHi != 0x08 && typeHi != 0x09) return
            if (typeLo < 0x0A || typeLo > 0x0C) return
            val ids = if (sentByMe) stunIdsMine else stunIdsTheirs
            var i = 0
            while (i <= headLen - 10) {
                if (isTenDigitAscii(head, i) &&
                    (i == 0 || !isDigit(head[i - 1])) &&
                    (i + 10 >= headLen || !isDigit(head[i + 10]))
                ) {
                    val id = String(head, i, 10, Charsets.US_ASCII)
                    ids[id] = (ids[id] ?: 0) + 1
                    i += 10
                } else {
                    i++
                }
            }
        }

        private fun isDigit(b: Byte): Boolean = b in 0x30..0x39

        private fun isTenDigitAscii(head: ByteArray, offset: Int): Boolean {
            for (j in offset until offset + 10) {
                if (!isDigit(head[j])) return false
            }
            return true
        }

        fun gameSilentForMs(nowMs: Long): Long =
            if (lastGameMs <= 0L) 0L else (nowMs - lastGameMs).coerceAtLeast(0L)

        fun checkIdleEnd(nowMs: Long): Boolean =
            gated && gameSilentForMs(nowMs) >= IDLE_END_SILENCE_MS

        private fun observeFullTimeControl(tMs: Long, sentByMe: Boolean) {
            val mine = if (sentByMe) fullTimeControlA else fullTimeControlB
            mine.addLast(tMs)
            val cutoff = tMs - FULL_TIME_BURST_WINDOW_MS
            while (fullTimeControlA.peekFirst()?.let { it < cutoff } == true) fullTimeControlA.removeFirst()
            while (fullTimeControlB.peekFirst()?.let { it < cutoff } == true) fullTimeControlB.removeFirst()
            if (tMs - startedAtMs >= FULL_TIME_MIN_DURATION_MS &&
                fullTimeControlA.size >= FULL_TIME_BURST_MIN_PER_DIRECTION &&
                fullTimeControlB.size >= FULL_TIME_BURST_MIN_PER_DIRECTION) {
                fullTimeBurstAtMs = tMs
            }
        }

        private fun hasFullTimeEvidence(): Boolean {
            if (fullTimeBurstAtMs <= 0L || lastSubstantiveGameMs < fullTimeBurstAtMs) return false
            return lastSubstantiveGameMs - fullTimeBurstAtMs <= FULL_TIME_MAX_TRAILING_GAME_MS
        }

        private fun fieldsOf(message: GoalMessage): GoalFields? {
            val d = message.decoded
            if (d.size < 26) return null
            return GoalFields(
                scoreByteA = d[6],
                scoreByteB = d[8],
                tagByteA = d[12],
                tagByteB = d[14],
                postTail = if (d.size >= 30) List(4) { d[26 + it] } else emptyList(),
                vGroup = if (d.size >= 34) List(4) { d[30 + it] } else emptyList(),
            )
        }

        /**
         * Resolves the scorer of every goal from the recorded per-goal wire
         * fields. With no calibration loaded every goal stays UNRESOLVED.
         * With a calibration, the masked score pairs must form a valid
         * non-decreasing progression: each goal increments exactly one side
         * by exactly one, otherwise the goal (and the match) stays
         * unresolved - the progression check is the safety net against a
         * wrong mask, a wrong side mapping, or merged records from a
         * same-port rematch after an abandoned epoch.
         */
        private fun resolveScorers(
            clusterFields: List<GoalFields?>,
            disagreeing: Set<Int>,
        ): List<Pair<ScorerSide, String?>> {
            val hs = handshake
            val cal = calibration
            return clusterFields.mapIndexed { index, fields ->
                var side: ScorerSide? = null
                var reason: String? = null
                when {
                    cal == null || !cal.validated ->
                        reason = "attribution is not calibrated (record a match where both players score)"
                    disagreeing.contains(index) ->
                        reason = "the two directions disagreed on the goal-summary fields"
                    fields == null ->
                        reason = "the goal-summary head was too short to decode the field values"
                    hs == null ->
                        reason = "session roles were not observed (no probe/exchange handshake)"
                    else -> {
                        val scoreA = fields.scoreByteA xor cal.scoreMask
                        val scoreB = fields.scoreByteB xor cal.scoreMask
                        if (scoreA !in 0..15 || scoreB !in 0..15) {
                            reason = "field values %02x/%02x are outside the calibrated range".format(
                                fields.scoreByteA, fields.scoreByteB,
                            )
                        } else {
                            var prevA = 0
                            var prevB = 0
                            var progressionKnown = true
                            for (earlier in 0 until index) {
                                val f = clusterFields[earlier]
                                if (f == null || disagreeing.contains(earlier)) {
                                    progressionKnown = false
                                    break
                                }
                                prevA = f.scoreByteA xor cal.scoreMask
                                prevB = f.scoreByteB xor cal.scoreMask
                            }
                            if (!progressionKnown) {
                                reason = "an earlier goal's fields were unreadable or disagreed, so the score progression is unknown"
                            } else {
                                val deltaA = scoreA - prevA
                                val deltaB = scoreB - prevB
                                val scorerIsSideA: Boolean? = when {
                                    deltaA == 1 && deltaB == 0 -> true
                                    deltaA == 0 && deltaB == 1 -> false
                                    else -> null
                                }
                                if (scorerIsSideA == null) {
                                    reason = "field values %02x/%02x do not continue a valid score progression".format(
                                        fields.scoreByteA, fields.scoreByteB,
                                    )
                                } else {
                                    val scorerIsProbeSender =
                                        if (scorerIsSideA) cal.probeSenderScoreIndex == 0 else cal.probeSenderScoreIndex == 1
                                    side = if (scorerIsProbeSender == hs.probeSentByMe) ScorerSide.ME else ScorerSide.PEER
                                }
                            }
                        }
                    }
                }
                (side ?: ScorerSide.UNRESOLVED) to reason
            }
        }

        fun goalEvents(): List<GoalEvent> {
            val clusters = clusterGoalMessages()
            // F15 CORROBORATION GATE: a goal is a BIDIRECTIONAL exchange - the
            // original from the scoring side plus the conceder's echo/sync
            // within the cluster window (measured on every real goal in the
            // 28_Jun and 29_Jan captures). Clusters whose goal-family
            // messages are all one-directional are kickoff/state summaries:
            // the 3-Sep session fired exactly one of those per match (payload
            // ~1197, ~7-10s after keystream convergence, no echo) while the
            // real 3 goals never produced such messages. Summaries are
            // recorded as evidence (unilateralSummaries), never as goals.
            val goalClusters = clusters.filter { cluster ->
                cluster.any { it.sentByMe != cluster.first().sentByMe }
            }
            val clusterFields = goalClusters.map { cluster ->
                val first = cluster.first()
                val other = cluster.firstOrNull { it.sentByMe != first.sentByMe }
                val mine = fieldsOf(first)
                val theirs = other?.let { fieldsOf(it) }
                val disagree = mine != null && theirs != null && !mine.sameAttributionFieldsAs(theirs)
                Triple(mine, disagree, first)
            }
            val disagreeing = clusterFields.mapIndexed { index, triple -> if (triple.second) index else null }
                .filterNotNullTo(HashSet())
            val resolutions = resolveScorers(clusterFields.map { it.first }, disagreeing)
            return goalClusters.mapIndexed { index, cluster ->
                val first = cluster.first()
                val peerEcho = cluster.firstOrNull {
                    it.sentByMe != first.sentByMe && it.tMs - first.tMs in 0L..ECHO_WINDOW_MS
                }
                val (fields, disagree, _) = clusterFields[index]
                val (side, reason) = resolutions[index]
                GoalEvent(
                    tMs = first.tMs,
                    firstSentByMe = first.sentByMe,
                    senderRole = first.senderRole,
                    payloadLen = first.payloadLen,
                    nMessages = cluster.size,
                    corroborated = peerEcho != null,
                    echoAfterMs = peerEcho?.let { it.tMs - first.tMs },
                    fields = fields,
                    fieldsDisagree = disagree,
                    scorerSide = side,
                    attribution = if (side == ScorerSide.UNRESOLVED) AttributionTier.UNPROVEN else AttributionTier.CALIBRATED,
                    reason = reason,
                )
            }
        }

        fun segmentResult(endedBy: String, completed: Boolean, endedAtMs: Long): SegmentResult {
            val events = goalEvents()
            val resolved = events.filter { it.scorerSide != ScorerSide.UNRESOLVED }
            val unresolved = events.size - resolved.size
            val unilateral = unilateralSummaries()
            val outgoing = (latestOutPackets - baselineOutPackets).coerceAtLeast(0L)
            val incoming = (latestInPackets - baselineInPackets).coerceAtLeast(0L)
            val telemetryDrops = (latestTelemetryDrops - baselineTelemetryDrops).coerceAtLeast(0L)
            val parseFailures = (latestParseFailures - baselineParseFailures).coerceAtLeast(0L)
            val duration = (endedAtMs - startedAtMs).coerceAtLeast(0L)
            val completionProven = completed && hasFullTimeEvidence()
            val calibrationLoaded = calibration != null && calibration.validated
            val attribution = when {
                events.isEmpty() && calibrationLoaded -> AttributionTier.CALIBRATED
                resolved.size == events.size && events.isNotEmpty() -> AttributionTier.CALIBRATED
                else -> AttributionTier.UNPROVEN
            }
            val blockers = buildList {
                if (!gated) add("protocol fingerprint did not converge")
                if (handshake == null) add("match start was not proven (no probe/exchange handshake in this epoch)")
                if (!calibrationLoaded) {
                    add("scorer attribution is not calibrated - settlement stays disabled until one both-players-score capture pins it")
                }
                if (unresolved > 0) add("$unresolved goal(s) have unresolved scorer attribution")
                events.filter { it.reason != null }.take(3).forEach { add(it.reason!!) }
                if (!completionProven) add("full-time control sequence and a terminal boundary were not both proven")
                if (events.any { !it.corroborated }) add("one or more goal events lacked the peer echo")
                if (telemetryDrops > 0) add("$telemetryDrops score-telemetry snapshot(s) were dropped")
                if (parseFailures > 0) add("$parseFailures score-telemetry poll(s) were malformed")
                if (protocolDecodeDrops > 0) add("$protocolDecodeDrops pre-gate event head(s) exceeded the bounded decoder queue")
                if (outgoing < MIN_SETTLEMENT_PACKETS_PER_DIRECTION ||
                    incoming < MIN_SETTLEMENT_PACKETS_PER_DIRECTION) {
                    add("insufficient bidirectional match traffic")
                }
                if (duration < MIN_SETTLEMENT_DURATION_MS) add("match segment was too short")
                if (unilateral > 0) add("$unilateral unilateral goal-family summary(ies) observed (kickoff/state sync markers - not goals; wire evidence logged)")
            }
            return SegmentResult(
                gated = gated,
                tableConvergedAtMs = tableConvergedAtMs,
                handshake = handshake,
                events = events,
                myGoals = resolved.count { it.scorerSide == ScorerSide.ME },
                opponentGoals = resolved.count { it.scorerSide == ScorerSide.PEER },
                totalGoals = events.size,
                unilateralSummaries = unilateral,
                attribution = attribution,
                calibrationLoaded = calibrationLoaded,
                localPlayerId = localPlayerId(),
                remotePlayerId = remotePlayerId(),
                endedBy = endedBy,
                completed = completionProven,
                settleable = blockers.isEmpty(),
                settlementBlockers = blockers,
                outgoingPackets = outgoing,
                incomingPackets = incoming,
                telemetryDrops = telemetryDrops,
                telemetryParseFailures = parseFailures,
                protocolDecodeDrops = protocolDecodeDrops,
                durationMs = duration,
                reconnects = reconnects,
                portPair = epochPortPair,
            )
        }
    }

    private fun isStun(head: ByteArray, headLen: Int): Boolean =
        headLen >= 8 && head[4] == STUN_MAGIC[0] && head[5] == STUN_MAGIC[1] &&
            head[6] == STUN_MAGIC[2] && head[7] == STUN_MAGIC[3]

    private fun modeTable(counts: Array<IntArray>): IntArray = IntArray(TABLE_SIZE) { column ->
        var bestByte = 0
        var bestCount = -1
        for (value in 0 until 256) {
            if (counts[column][value] > bestCount) {
                bestCount = counts[column][value]
                bestByte = value
            }
        }
        bestByte
    }
}
