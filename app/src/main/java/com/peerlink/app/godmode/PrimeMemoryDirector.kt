package com.peerlink.app.godmode

import android.content.Context
import android.os.Process
import com.peerlink.app.core.AppState
import com.peerlink.app.core.MatchPhase
import com.peerlink.app.core.MatchTracker
import com.peerlink.app.core.PrimeGameplayTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Prime's pressure-aware, match-aware memory policy. */
class PrimeMemoryDirector(
    context: Context,
    private val execute: (String) -> PrimeExecResult,
    private val capabilities: () -> PrimeCapabilities,
    private val instantVaultEnabled: () -> Boolean,
    private val aggression: () -> PrimeMemoryAggression,
) {
    data class Snapshot(
        val running: Boolean = false,
        val matchProtected: Boolean = false,
        val gameForeground: Boolean = false,
        val gameVaulted: Boolean = false,
        val availableMb: Int = 0,
        val totalMb: Int = 0,
        val reserveMb: Int = 0,
        val swapUsedMb: Int = 0,
        val swapTotalMb: Int = 0,
        val zramRatio: Float = 0f,
        val protectedForeground: String = "",
        val pressure: String = "Idle",
        val lastAction: String = "None",
    )

    private data class Mem(val totalKb: Long, val availableKb: Long, val swapTotalKb: Long, val swapFreeKb: Long, val psiSomeAvg10: Float, val zramRatio: Float)
    private data class Proc(val uid: Int, val pid: Int, val rssKb: Long, val name: String, val adj: Int)

    companion object {
        private const val GAME = "jp.konami.pesam"
        private const val MONITOR_MS = 750L
        private const val UID_SAMPLE_MS = 1_500L
        private const val MEMORY_SAMPLE_MS = 4_000L
        private const val ACTION_COOLDOWN_MS = 4_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var gameUid: Int = -1
    private var previousGameTop = false
    private var seenGameTop = false
    private var gameVaulted = false
    private var lastVaultAttemptMs = 0L
    private var lastUidSampleMs = 0L
    private var cachedGameTop = false
    private var lastMemorySampleMs = 0L
    private var lastActionMs = 0L
    private var previousAvailableKb = 0L
    private var temporaryForegroundPkg = ""

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        gameUid = runCatching { appContext.packageManager.getApplicationInfo(GAME, 0).uid }.getOrDefault(-1)
        job = scope.launch {
            AppState.appendLog("[PRIME-MEM  ] Memory Director started")
            _snapshot.value = _snapshot.value.copy(running = true)
            while (isActive) {
                try { tick() } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) { AppState.appendLog("[PRIME-MEM  ] Monitor error: ${t.message}") }
                delay(MONITOR_MS)
            }
        }
    }

    suspend fun stop(): Boolean {
        job?.cancelAndJoin(); job = null
        // Wait for an in-flight freeze before undoing it, otherwise it can finish after stop.
        if (gameVaulted) {
            val restored = execute("am unfreeze --sticky $GAME")
            if (!restored.ok) {
                AppState.appendLog("[PRIME-MEM  ] Game unfreeze failed; restore remains pending")
                return false
            }
        }
        gameVaulted = false
        previousGameTop = false
        seenGameTop = false
        lastVaultAttemptMs = 0L
        lastUidSampleMs = 0L
        cachedGameTop = false
        temporaryForegroundPkg = ""
        _snapshot.value = _snapshot.value.copy(running = false, gameForeground = false, gameVaulted = false, pressure = "Idle")
        AppState.appendLog("[PRIME-MEM  ] Memory Director stopped")
        return true
    }

    private suspend fun tick() {
        if (gameUid <= 0) return
        val now = System.currentTimeMillis()
        val matchProtected = PrimeGameplayTracker.isMatchProtected(now)
        if (capabilities().uidState && now - lastUidSampleMs >= UID_SAMPLE_MS) {
            lastUidSampleMs = now
            val sampledState = readUidState(gameUid)
            if (sampledState != null) cachedGameTop = sampledState <= 2
        }
        currentCoroutineContext().ensureActive()
        val gameTop = cachedGameTop
        if (gameTop) seenGameTop = true

        if (gameVaulted) {
            val top = readTopPackage()
            if (top == GAME || matchProtected || !instantVaultEnabled()) {
                val r = execute("am unfreeze --sticky $GAME")
                if (r.ok) {
                    gameVaulted = false
                    AppState.appendLog("[PRIME-MEM  ] eFootball unvaulted immediately for return")
                    setLastAction("Game unvaulted")
                }
            } else if (top.isNotBlank()) temporaryForegroundPkg = top
        } else if (seenGameTop && !gameTop) {
            if (previousGameTop) {
                temporaryForegroundPkg = readTopPackage().takeIf { it != GAME }.orEmpty()
                if (matchProtected) AppState.appendLog("[PRIME-MEM  ] eFootball left foreground during protected match — Return Shield, no freeze")
            }
            if (!matchProtected && instantVaultEnabled() && capabilities().processFreeze && now - lastVaultAttemptMs >= 2_000L) {
                lastVaultAttemptMs = now
                val top = readTopPackage()
                currentCoroutineContext().ensureActive()
                // A stale cached package or unreadable foreground is not permission to freeze.
                if (top.isNotBlank() && top != GAME &&
                    !PrimeGameplayTracker.isMatchProtected() &&
                    MatchTracker.state.value.phase == MatchPhase.NO_MATCH) {
                    if (top.isNotBlank()) temporaryForegroundPkg = top
                    val r = execute("am freeze --sticky $GAME")
                    if (r.ok) {
                        gameVaulted = true
                        AppState.appendLog("[PRIME-MEM  ] eFootball backgrounded with no live P2P match — vaulted immediately")
                        setLastAction("Game vaulted")
                    } else AppState.appendLog("[PRIME-MEM  ] Vault command rejected: ${r.output.take(100)}")
                }
            }
        }
        previousGameTop = gameTop
        if (gameTop) temporaryForegroundPkg = ""

        if (now - lastMemorySampleMs >= MEMORY_SAMPLE_MS) {
            lastMemorySampleMs = now
            val mem = readMemory() ?: return
            applyPressurePolicy(mem, matchProtected, now)
        } else {
            _snapshot.value = _snapshot.value.copy(matchProtected = matchProtected, gameForeground = gameTop, gameVaulted = gameVaulted, protectedForeground = temporaryForegroundPkg)
        }
    }

    private suspend fun applyPressurePolicy(mem: Mem, matchProtected: Boolean, now: Long) {
        val totalMb = (mem.totalKb / 1024L).toInt()
        val availMb = (mem.availableKb / 1024L).toInt()
        val reserveMb = calculateReserveMb(totalMb)
        val reserveKb = reserveMb * 1024L
        val fallingFast = previousAvailableKb > 0L && previousAvailableKb - mem.availableKb > max(96L * 1024L, mem.totalKb / 25L)
        previousAvailableKb = mem.availableKb
        val early = mem.availableKb < reserveKb * 125L / 100L || fallingFast || mem.psiSomeAvg10 >= 0.5f
        val hard = mem.availableKb < reserveKb || mem.psiSomeAvg10 >= 2.0f
        val critical = mem.availableKb < reserveKb * 3L / 4L || mem.psiSomeAvg10 >= 6.0f
        val pressure = when { critical -> "Critical"; hard -> "High"; early -> "Early"; else -> "Healthy" }

        _snapshot.value = Snapshot(
            running = true, matchProtected = matchProtected, gameForeground = cachedGameTop, gameVaulted = gameVaulted,
            availableMb = availMb, totalMb = totalMb, reserveMb = reserveMb,
            swapUsedMb = ((mem.swapTotalKb - mem.swapFreeKb).coerceAtLeast(0L) / 1024L).toInt(),
            swapTotalMb = (mem.swapTotalKb / 1024L).toInt(), zramRatio = mem.zramRatio,
            protectedForeground = temporaryForegroundPkg, pressure = pressure, lastAction = _snapshot.value.lastAction,
        )
        if (!early || now - lastActionMs < ACTION_COOLDOWN_MS) return
        currentCoroutineContext().ensureActive()
        // Reclamation/compaction competes with packet/game threads; leave live matches alone.
        if (matchProtected) return
        val candidates = cachedCandidates(); if (candidates.isEmpty()) return
        currentCoroutineContext().ensureActive()
        val zramNearFull = mem.swapTotalKb > 0L && mem.swapFreeKb < mem.swapTotalKb / 7L
        val poorCompression = mem.zramRatio in 0.1f..1.25f
        val preferKill = critical || (hard && (zramNearFull || poorCompression))
        val canCompact = capabilities().processCompact
        when {
            preferKill -> killOne(candidates.first(), now)
            !canCompact -> return
            hard || aggression() == PrimeMemoryAggression.AGGRESSIVE -> compactOne(candidates.first(), true, now)
            else -> compactOne(candidates.first(), false, now)
        }
    }

    private fun calculateReserveMb(totalMb: Int): Int {
        val ratio = if (aggression() == PrimeMemoryAggression.AGGRESSIVE) 0.20 else 0.15
        return min(2_500, max(if (totalMb <= 4_500) 700 else 1_000, (totalMb * ratio).toInt()))
    }

    private fun compactOne(proc: Proc, full: Boolean, now: Long) {
        val profile = if (full) "full" else "some"
        val r = execute("am compact $profile ${shellQuote(proc.name)}")
        if (r.ok) {
            lastActionMs = now
            val label = "${if (full) "Full" else "Soft"} compact ${proc.name} (${proc.rssKb / 1024} MB)"
            setLastAction(label); AppState.appendLog("[PRIME-MEM  ] $label")
        }
    }

    private fun killOne(proc: Proc, now: Long) {
        if (proc.adj < 800) { compactOne(proc, true, now); return }
        val pkg = proc.name.substringBefore(':'); if (!pkg.contains('.')) return
        val r = execute("am kill ${shellQuote(pkg)}")
        if (r.ok) {
            lastActionMs = now
            val label = "Reclaimed cached $pkg (${proc.rssKb / 1024} MB)"
            setLastAction(label); AppState.appendLog("[PRIME-MEM  ] $label")
        }
    }

    private suspend fun cachedCandidates(): List<Proc> {
        val ps = execute("ps -A -n -o UID,PID,RSS,NAME 2>/dev/null"); if (!ps.ok) return emptyList()
        val foregroundUid = temporaryForegroundPkg.takeIf { it.isNotBlank() }?.let { pkg -> runCatching { appContext.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() }
        val peerLinkUid = Process.myUid()
        val prelim = ps.output.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"), limit = 4); if (p.size < 4) return@mapNotNull null
            val uid = p[0].toIntOrNull() ?: return@mapNotNull null
            val pid = p[1].toIntOrNull() ?: return@mapNotNull null
            val rss = p[2].toLongOrNull() ?: return@mapNotNull null
            val name = p[3].trim()
            if (uid < 10_000 || uid == gameUid || uid == peerLinkUid || uid == foregroundUid) return@mapNotNull null
            if (name == GAME || name.startsWith("${appContext.packageName}:")) return@mapNotNull null
            Triple(uid, pid, rss to name)
        }.sortedByDescending { it.third.first }.take(8).toList()
        return prelim.mapNotNull { (uid, pid, pair) ->
            currentCoroutineContext().ensureActive()
            val adj = execute("cat /proc/$pid/oom_score_adj 2>/dev/null").output.trim().toIntOrNull() ?: return@mapNotNull null
            if (adj < 500) return@mapNotNull null
            Proc(uid, pid, pair.first, pair.second, adj)
        }.sortedWith(compareByDescending<Proc> { if (it.adj >= 800) 1 else 0 }.thenByDescending { it.rssKb }.thenByDescending { it.adj })
    }

    private fun readUidState(uid: Int): Int? {
        val r = execute("am get-uid-state $uid"); if (!r.ok) return null
        return r.output.trim().substringBefore(' ').toIntOrNull()
    }

    private fun readTopPackage(): String {
        val r = execute("dumpsys activity activities | grep -m 1 -E 'mResumedActivity|[tT]opResumedActivity'")
        if (r.ok) Regex("u\\d+\\s+([A-Za-z0-9_.]+?)/").find(r.output)?.groupValues?.getOrNull(1)?.let { return it }
        val w = execute("dumpsys window windows | grep -m 1 'mCurrentFocus'")
        return Regex("([A-Za-z0-9_.]+?)/[A-Za-z0-9_.$]+").find(w.output)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun readMemory(): Mem? {
        val cmd = "cat /proc/meminfo; echo __PL_PSI__; cat /proc/pressure/memory 2>/dev/null; echo __PL_ZRAM__; cat /sys/block/zram0/mm_stat 2>/dev/null"
        val r = execute(cmd); if (!r.ok) return null
        val text = r.output
        fun kb(key: String): Long = Regex("(?m)^$key:\\s+(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val psi = Regex("some\\s+avg10=([0-9.]+)").find(text.substringAfter("__PL_PSI__", ""))?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
        val mm = text.substringAfter("__PL_ZRAM__", "").trim().lineSequence().firstOrNull().orEmpty().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        val orig = mm.getOrNull(0) ?: 0L; val used = mm.getOrNull(2) ?: 0L
        val ratio = if (orig > 0L && used > 0L) orig.toFloat() / used.toFloat() else 0f
        val total = kb("MemTotal"); if (total <= 0L) return null
        return Mem(total, kb("MemAvailable"), kb("SwapTotal"), kb("SwapFree"), psi, ratio)
    }

    private fun setLastAction(action: String) { _snapshot.value = _snapshot.value.copy(lastAction = action) }
    private fun shellQuote(v: String): String = "'" + v.replace("'", "'\"'\"'") + "'"
}
