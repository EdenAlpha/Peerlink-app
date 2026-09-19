package com.peerlink.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class MatchResult { WIN, LOSS, DRAW }

/** One statistics-board row read from the eFootball result screen (F33). */
data class MatchStatRow(
    val name: String,
    val home: Int,
    val away: Int,
)

/**
 * Full 13-row statistics table read by the F33 pixel detector on the
 * statistics board. Recorded alongside the confirmed score for the match
 * ledger; entirely optional and never part of settlement or integrity.
 */
data class MatchStats(val rows: List<MatchStatRow>) {
    operator fun get(name: String): Pair<Int, Int>? =
        rows.firstOrNull { it.name == name }?.let { it.home to it.away }

    fun toJsonArray(): JSONArray = JSONArray().apply {
        rows.forEach { row ->
            put(JSONObject().apply {
                put("name", row.name)
                put("home", row.home)
                put("away", row.away)
            })
        }
    }

    companion object {
        fun fromJson(array: JSONArray): MatchStats? {
            val rows = ArrayList<MatchStatRow>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val home = o.optInt("home", -1)
                val away = o.optInt("away", -1)
                if (name.isNotEmpty() && home in 0..100 && away in 0..100) {
                    rows.add(MatchStatRow(name, home, away))
                }
            }
            return if (rows.isEmpty()) null else MatchStats(rows)
        }
    }
}

/**
 * A goal as recorded in the legacy audit ledger (F13-F31 packet-decode era).
 * F32 removed packet goal detection: new records always carry an empty goal
 * list. The structure is retained so existing ledger files keep loading.
 */
data class GoalEvent(
    val atMs: Long,
    val payloadLen: Int,
    val firstSentByMe: Boolean,
    val senderRole: Int,
    val corroborated: Boolean,
    val scoreByteA: Int,
    val scoreByteB: Int,
    val tagByteA: Int,
    val tagByteB: Int,
    val postTailHex: String,
    val vGroupHex: String,
    val scorerSide: String,
    val attributionTier: String,
    val reason: String?,
) {
    companion object {
        const val SIDE_UNRESOLVED = "UNRESOLVED"
        const val SIDE_ME = "ME"
        const val SIDE_PEER = "PEER"
    }
}

data class MatchReward(
    val winDeltaCents: Long,
    val goalsBonusCents: Long,
    val marginBonusCents: Long,
    val marginPenaltyCents: Long,
) {
    val totalCents: Long get() = winDeltaCents + goalsBonusCents + marginBonusCents + marginPenaltyCents

    fun describe(): String {
        val parts = mutableListOf<String>()
        if (winDeltaCents != 0L) parts += label("Win/Loss", winDeltaCents)
        if (goalsBonusCents != 0L) parts += label("3+ goals", goalsBonusCents)
        if (marginBonusCents != 0L) parts += label("3+ margin", marginBonusCents)
        if (marginPenaltyCents != 0L) parts += label("Heavy loss", marginPenaltyCents)
        return if (parts.isEmpty()) "No change" else parts.joinToString(" · ")
    }

    private fun label(name: String, cents: Long): String =
        "$name ${if (cents > 0) "+" else ""}${formatCents(cents)}"
}

object PeerCoinRules {
    const val WIN_CENTS = 30L
    const val LOSS_CENTS = -13L
    const val GOALS_BONUS_CENTS = 20L
    const val WIN_MARGIN_BONUS_CENTS = 10L
    const val LOSS_MARGIN_PENALTY_CENTS = -80L

    fun compute(myGoals: Int, opponentGoals: Int): MatchReward {
        val win = myGoals > opponentGoals
        val loss = myGoals < opponentGoals
        val margin = kotlin.math.abs(myGoals - opponentGoals)
        return MatchReward(
            winDeltaCents = when {
                win -> WIN_CENTS
                loss -> LOSS_CENTS
                else -> 0L
            },
            goalsBonusCents = if (win && myGoals >= 3) GOALS_BONUS_CENTS else 0L,
            marginBonusCents = if (win && margin >= 3) WIN_MARGIN_BONUS_CENTS else 0L,
            marginPenaltyCents = if (loss && margin >= 3) LOSS_MARGIN_PENALTY_CENTS else 0L,
        )
    }
}

/**
 * A record can exist for audit/UI while [confirmed] remains false and pays
 * nothing.
 *
 * integrityVersion 4 (F13-v2): scorer attribution is recorded per goal from
 * decoded wire fields and is UNRESOLVED until a calibration capture pins the
 * side encoding. Version 3 and older records were settled under the
 * confounded exchange-responder rule and are retained for history only;
 * their settlements are revoked and they pay nothing.
 */
data class MatchRecord(
    val id: String,
    val sessionId: String,
    val segmentOrdinal: Int,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val opponentName: String,
    val opponentIp: String,
    val myGoals: Int,
    val opponentGoals: Int,
    val totalGoals: Int,
    val goals: List<GoalEvent>,
    val endedBy: String,
    val gamePackets: Long,
    val outgoingPackets: Long = 0,
    val incomingPackets: Long = 0,
    val telemetryDrops: Long = 0,
    val telemetryParseFailures: Long = 0,
    val protocolDecodeDrops: Long = 0,
    val reconnects: Int = 0,
    val attributionTier: String = "UNPROVEN",
    val calibrationLoaded: Boolean = false,
    val handshakeProbeByMe: Boolean? = null,
    val handshakeExchangeByMe: Boolean? = null,
    val gamePortPair: Pair<Int, Int>? = null,
    val quarantinedPackets: Long = 0,
    val confirmed: Boolean = false,
    val settlementNote: String? = null,
    val integrityVersion: Int = CURRENT_INTEGRITY_VERSION,
    val stats: MatchStats? = null,
) {
    val result: MatchResult
        get() = when {
            myGoals > opponentGoals -> MatchResult.WIN
            myGoals < opponentGoals -> MatchResult.LOSS
            else -> MatchResult.DRAW
        }
    val margin: Int get() = kotlin.math.abs(myGoals - opponentGoals)
    val reward: MatchReward get() = PeerCoinRules.compute(myGoals, opponentGoals)
    val settledReward: MatchReward get() =
        if (confirmed && integrityVersion >= CURRENT_INTEGRITY_VERSION) reward else ZERO_REWARD
    val durationSec: Long get() = ((endedAtMs - startedAtMs) / 1000L).coerceAtLeast(0L)
    val cutShort: Boolean get() = endedBy == "disconnect" || endedBy == "service" || endedBy == "replaced"
    val attributionResolved: Boolean get() = goals.none { it.scorerSide == GoalEvent.SIDE_UNRESOLVED }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("segmentOrdinal", segmentOrdinal)
        put("startedAtMs", startedAtMs)
        put("endedAtMs", endedAtMs)
        put("opponentName", opponentName)
        put("opponentIp", opponentIp)
        put("myGoals", myGoals)
        put("opponentGoals", opponentGoals)
        put("totalGoals", totalGoals)
        put("endedBy", endedBy)
        put("gamePackets", gamePackets)
        put("outgoingPackets", outgoingPackets)
        put("incomingPackets", incomingPackets)
        put("telemetryDrops", telemetryDrops)
        put("telemetryParseFailures", telemetryParseFailures)
        put("protocolDecodeDrops", protocolDecodeDrops)
        put("reconnects", reconnects)
        put("attributionTier", attributionTier)
        put("calibrationLoaded", calibrationLoaded)
        handshakeProbeByMe?.let { put("handshakeProbeByMe", it) }
        handshakeExchangeByMe?.let { put("handshakeExchangeByMe", it) }
        gamePortPair?.let { put("gamePortPair", "${it.first}<->${it.second}") }
        put("quarantinedPackets", quarantinedPackets)
        put("confirmed", confirmed)
        put("settlementNote", settlementNote ?: "")
        put("integrityVersion", integrityVersion)
        stats?.let { put("stats", it.toJsonArray()) }
        put("goals", JSONArray().apply {
            goals.forEach { goal ->
                put(JSONObject().apply {
                    put("atMs", goal.atMs)
                    put("len", goal.payloadLen)
                    put("firstSentByMe", goal.firstSentByMe)
                    put("senderRole", goal.senderRole)
                    put("corroborated", goal.corroborated)
                    put("scoreByteA", goal.scoreByteA)
                    put("scoreByteB", goal.scoreByteB)
                    put("tagByteA", goal.tagByteA)
                    put("tagByteB", goal.tagByteB)
                    put("postTailHex", goal.postTailHex)
                    put("vGroupHex", goal.vGroupHex)
                    put("scorerSide", goal.scorerSide)
                    put("attributionTier", goal.attributionTier)
                    put("reason", goal.reason ?: "")
                })
            }
        })
    }

    companion object {
        const val CURRENT_INTEGRITY_VERSION = 4
        private val ZERO_REWARD = MatchReward(0, 0, 0, 0)

        private fun parsePortPair(raw: String?): Pair<Int, Int>? {
            val parts = raw?.split("<->") ?: return null
            if (parts.size != 2) return null
            val a = parts[0].toIntOrNull() ?: return null
            val b = parts[1].toIntOrNull() ?: return null
            return if (a > 0 && b > 0) a to b else null
        }

        fun fromJson(json: JSONObject): MatchRecord {
            val goals = buildList {
                val array = json.optJSONArray("goals") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val goal = array.optJSONObject(index) ?: continue
                    add(
                        GoalEvent(
                            atMs = goal.optLong("atMs", 0L),
                            payloadLen = goal.optInt("len", 0).coerceAtLeast(0),
                            firstSentByMe = goal.optBoolean("firstSentByMe", false),
                            senderRole = goal.optInt("senderRole", 0),
                            corroborated = goal.optBoolean("corroborated", false),
                            scoreByteA = goal.optInt("scoreByteA", -1),
                            scoreByteB = goal.optInt("scoreByteB", -1),
                            tagByteA = goal.optInt("tagByteA", -1),
                            tagByteB = goal.optInt("tagByteB", -1),
                            postTailHex = goal.optString("postTailHex", ""),
                            vGroupHex = goal.optString("vGroupHex", ""),
                            scorerSide = goal.optString("scorerSide", GoalEvent.SIDE_UNRESOLVED),
                            attributionTier = goal.optString("attributionTier", "UNPROVEN"),
                            reason = goal.optString("reason", "").ifBlank { null },
                        )
                    )
                }
            }
            val integrityVersion = json.optInt("integrityVersion", 0)
            // v3 and older records were settled under the confounded
            // exchange-responder attribution rule: kept for audit history,
            // never paid. Missing fields never default to paid either.
            val confirmed = integrityVersion >= CURRENT_INTEGRITY_VERSION &&
                json.optBoolean("confirmed", false)
            val started = json.optLong("startedAtMs", 0L).coerceAtLeast(0L)
            val ended = json.optLong("endedAtMs", started).coerceAtLeast(started)
            val myGoals = json.optInt("myGoals", 0).coerceAtLeast(0)
            val opponentGoals = json.optInt("opponentGoals", 0).coerceAtLeast(0)
            return MatchRecord(
                id = json.optString("id", "legacy_$started"),
                sessionId = json.optString("sessionId", "legacy"),
                segmentOrdinal = json.optInt("segmentOrdinal", 0).coerceAtLeast(0),
                startedAtMs = started,
                endedAtMs = ended,
                opponentName = json.optString("opponentName", "Unknown"),
                opponentIp = json.optString("opponentIp", ""),
                myGoals = myGoals,
                opponentGoals = opponentGoals,
                totalGoals = json.optInt("totalGoals", goals.size).coerceAtLeast(0),
                goals = goals,
                endedBy = json.optString("endedBy", "unknown"),
                gamePackets = json.optLong("gamePackets", 0L).coerceAtLeast(0L),
                outgoingPackets = json.optLong("outgoingPackets", 0L).coerceAtLeast(0L),
                incomingPackets = json.optLong("incomingPackets", 0L).coerceAtLeast(0L),
                telemetryDrops = json.optLong("telemetryDrops", 0L).coerceAtLeast(0L),
                telemetryParseFailures = json.optLong("telemetryParseFailures", 0L).coerceAtLeast(0L),
                protocolDecodeDrops = json.optLong("protocolDecodeDrops", 0L).coerceAtLeast(0L),
                reconnects = json.optInt("reconnects", 0).coerceAtLeast(0),
                attributionTier = json.optString("attributionTier", "UNPROVEN"),
                calibrationLoaded = json.optBoolean("calibrationLoaded", false),
                handshakeProbeByMe = if (json.has("handshakeProbeByMe")) json.optBoolean("handshakeProbeByMe") else null,
                handshakeExchangeByMe = if (json.has("handshakeExchangeByMe")) json.optBoolean("handshakeExchangeByMe") else null,
                gamePortPair = parsePortPair(if (json.has("gamePortPair")) json.optString("gamePortPair") else null),
                quarantinedPackets = json.optLong("quarantinedPackets", 0L).coerceAtLeast(0L),
                confirmed = confirmed,
                settlementNote = json.optString("settlementNote", "").ifBlank {
                    when {
                        integrityVersion < CURRENT_INTEGRITY_VERSION ->
                            "Legacy record - settled under the revoked responder attribution rule; not paid"
                        else -> null
                    }
                },
                integrityVersion = integrityVersion,
                stats = json.optJSONArray("stats")?.let { MatchStats.fromJson(it) },
            )
        }
    }
}

data class PeerCoinStats(val records: List<MatchRecord>) {
    private val settledRecords get() = records.filter { it.confirmed && it.integrityVersion >= MatchRecord.CURRENT_INTEGRITY_VERSION }
    val matchCount: Int get() = settledRecords.size
    val wins: Int get() = settledRecords.count { it.result == MatchResult.WIN }
    val losses: Int get() = settledRecords.count { it.result == MatchResult.LOSS }
    val draws: Int get() = settledRecords.count { it.result == MatchResult.DRAW }
    val goalsFor: Int get() = settledRecords.sumOf { it.myGoals }
    val goalsAgainst: Int get() = settledRecords.sumOf { it.opponentGoals }
    val balanceCents: Long get() = settledRecords.sumOf { it.settledReward.totalCents }
    val winRatePct: Int
        get() = if (matchCount == 0) 0 else (((wins + draws * 0.5f) / matchCount) * 100f).toInt()
    val lastRecord: MatchRecord? get() = records.maxByOrNull { it.endedAtMs }
}

data class OpponentRecord(
    val key: String,
    val name: String,
    val ip: String,
    val matches: List<MatchRecord>,
) {
    private val settled get() = matches.filter { it.confirmed && it.integrityVersion >= MatchRecord.CURRENT_INTEGRITY_VERSION }
    val wins: Int get() = settled.count { it.result == MatchResult.WIN }
    val losses: Int get() = settled.count { it.result == MatchResult.LOSS }
    val draws: Int get() = settled.count { it.result == MatchResult.DRAW }
    val goalsFor: Int get() = settled.sumOf { it.myGoals }
    val goalsAgainst: Int get() = settled.sumOf { it.opponentGoals }
    val coinCents: Long get() = settled.sumOf { it.settledReward.totalCents }
    val lastPlayedMs: Long get() = matches.maxOfOrNull { it.endedAtMs } ?: 0L
}

/** Crash-safe, append-idempotent local audit ledger. */
object MatchStore {
    private const val FILE_NAME = "match_records.json"
    private const val BACKUP_NAME = "match_records.backup.json"
    private const val MAX_LEDGER_BYTES = 8 * 1024 * 1024

    @Volatile private var cache: List<MatchRecord>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun backup(context: Context) = File(context.filesDir, BACKUP_NAME)

    @Synchronized
    fun load(context: Context): List<MatchRecord> {
        cache?.let { return it }
        val primary = readLedger(file(context))
        val loaded = primary ?: readLedger(backup(context)) ?: emptyList()
        val unique = LinkedHashMap<String, MatchRecord>()
        loaded.sortedBy { it.endedAtMs }.forEach { record ->
            if (record.id.isNotBlank() && !unique.containsKey(record.id)) unique[record.id] = record
        }
        return unique.values.toList().also { cache = it }
    }

    /** Returns false for a duplicate ID; an existing ledger entry is never overwritten. */
    @Synchronized
    fun append(context: Context, record: MatchRecord): Boolean {
        require(record.id.isNotBlank()) { "Match ID must not be blank" }
        val current = ArrayList(load(context))
        if (current.any { it.id == record.id }) return false
        current += record
        persistAtomically(context, current)
        cache = current
        return true
    }

    @Synchronized
    fun updateStats(context: Context, id: String, stats: MatchStats): Boolean {
        if (id.isBlank()) return false
        val current = ArrayList(load(context))
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        current[index] = current[index].copy(stats = stats)
        persistAtomically(context, current)
        cache = current
        return true
    }

    @Synchronized
    fun stats(context: Context) = PeerCoinStats(load(context).sortedBy { it.endedAtMs })

    @Synchronized
    fun opponentRecords(context: Context): List<OpponentRecord> =
        load(context)
            .sortedBy { it.endedAtMs }
            .groupBy { it.opponentName.ifBlank { it.opponentIp } }
            .map { (name, matches) ->
                OpponentRecord(name, name, matches.lastOrNull()?.opponentIp.orEmpty(), matches)
            }
            .sortedByDescending { it.lastPlayedMs }

    @Synchronized
    fun clearCacheForTests() { cache = null }

    private fun readLedger(source: File): List<MatchRecord>? {
        // Missing, zero-length, oversized and malformed primaries must be null
        // so load() actually tries the last-known-good backup. Returning an
        // empty list here silently discarded the backup after a crash.
        if (!source.exists() || source.length() !in 1..MAX_LEDGER_BYTES.toLong()) return null
        return runCatching {
            val array = JSONArray(source.readText(StandardCharsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(MatchRecord.fromJson(it)) }
                }
            }
        }.getOrNull()
    }

    private fun persistAtomically(context: Context, records: List<MatchRecord>) {
        val target = file(context)
        val backup = backup(context)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        val bytes = JSONArray().apply { records.forEach { put(it.toJson()) } }
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_LEDGER_BYTES) { "Match ledger exceeds safe size" }

        if (target.exists()) {
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        FileOutputStream(temporary, false).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}

fun formatCents(cents: Long): String {
    val negative = cents < 0
    val absolute = kotlin.math.abs(cents)
    return "${if (negative) "-" else ""}${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
}
