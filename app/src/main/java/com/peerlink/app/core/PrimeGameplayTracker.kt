package com.peerlink.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Lightweight, decode-free gameplay detector driven only by the real PeerLink
 * P2P UDP data plane. A live match is latched only after sustained bidirectional
 * traffic is observed, then held through pauses for MATCH_GRACE_MS.
 *
 * This deliberately does not equate "PeerLink connected" with "match active".
 */
object PrimeGameplayTracker {
    data class Snapshot(
        val matchActive: Boolean = false,
        val outboundPps: Int = 0,
        val inboundPps: Int = 0,
        val secondsSinceStrongTraffic: Int = Int.MAX_VALUE,
    )

    private const val RATE_WINDOW_MS = 2_000L
    private const val MIN_PPS_EACH_DIRECTION = 8
    private const val MATCH_GRACE_MS = 65_000L

    private val lock = Any()
    private val outbound = ArrayDeque<Long>()
    private val inbound = ArrayDeque<Long>()
    @Volatile private var lastStrongGameplayMs = 0L
    @Volatile private var latched = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                publish(System.currentTimeMillis())
                delay(1_000L)
            }
        }
    }

    fun noteOutboundPeerUdp(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            outbound.addLast(nowMs)
            evaluateLocked(nowMs)
        }
    }

    fun noteInboundPeerUdp(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            inbound.addLast(nowMs)
            evaluateLocked(nowMs)
        }
    }

    fun isMatchProtected(nowMs: Long = System.currentTimeMillis()): Boolean {
        val strong = lastStrongGameplayMs
        return latched && strong > 0L && nowMs - strong <= MATCH_GRACE_MS
    }

    fun reset() {
        synchronized(lock) {
            outbound.clear()
            inbound.clear()
            lastStrongGameplayMs = 0L
            latched = false
            _snapshot.value = Snapshot()
        }
    }

    private fun evaluateLocked(nowMs: Long) {
        purgeLocked(nowMs)
        val seconds = RATE_WINDOW_MS / 1_000.0
        val outPps = (outbound.size / seconds).toInt()
        val inPps = (inbound.size / seconds).toInt()
        if (outPps >= MIN_PPS_EACH_DIRECTION && inPps >= MIN_PPS_EACH_DIRECTION) {
            if (!latched) {
                AppState.appendLog("[PRIME-MATCH] Sustained bidirectional P2P gameplay detected — Return Shield armed")
            }
            latched = true
            lastStrongGameplayMs = nowMs
        }
        publishLocked(nowMs, outPps, inPps)
    }

    private fun publish(nowMs: Long) {
        synchronized(lock) {
            purgeLocked(nowMs)
            if (latched && lastStrongGameplayMs > 0L && nowMs - lastStrongGameplayMs > MATCH_GRACE_MS) {
                latched = false
                AppState.appendLog("[PRIME-MATCH] Gameplay quiet for ${MATCH_GRACE_MS / 1000}s — match protection released")
            }
            val seconds = RATE_WINDOW_MS / 1_000.0
            publishLocked(nowMs, (outbound.size / seconds).toInt(), (inbound.size / seconds).toInt())
        }
    }

    private fun publishLocked(nowMs: Long, outPps: Int, inPps: Int) {
        val since = if (lastStrongGameplayMs <= 0L) Int.MAX_VALUE
        else ((nowMs - lastStrongGameplayMs).coerceAtLeast(0L) / 1_000L).toInt()
        _snapshot.value = Snapshot(
            matchActive = latched && since <= MATCH_GRACE_MS / 1_000L,
            outboundPps = outPps,
            inboundPps = inPps,
            secondsSinceStrongTraffic = since,
        )
    }

    private fun purgeLocked(nowMs: Long) {
        val cutoff = nowMs - RATE_WINDOW_MS
        while (outbound.isNotEmpty() && outbound.first() < cutoff) outbound.removeFirst()
        while (inbound.isNotEmpty() && inbound.first() < cutoff) inbound.removeFirst()
    }
}
