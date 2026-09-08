package com.peerlink.app.tunnel

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class JitterBuffer(
    private val onDeliver: (ByteArray, Int, Long) -> Unit,
    private val debugLog: (String, String) -> Unit
) {
    // ═══════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════
    private val DEFAULT_INTERVAL_MS = 37L

    // Queue management
    private val TARGET_QUEUE = 14

    // Proportional control
    private val GAIN_NS_PER_PACKET = 1_000_000L
    private val MAX_SPEEDUP_NS = 5_000_000L
    private val MAX_SLOWDOWN_NS = 15_000_000L

    // Detection
    private val DETECT_PACKETS = 14
    private val DETECT_TIMEOUT_MS = 600L
    private val CONFIRM_PACKETS = 27
    private val CONFIRM_TIMEOUT_MS = 1500L
    private val ACCEPTABLE_DIFF_MS = 5L

    // ═══════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════
    private data class QueuedPacket(
        val data: ByteArray,
        val length: Int,
        val receivedAtNs: Long
    )

    private val queue = ConcurrentLinkedQueue<QueuedPacket>()
    private val running = AtomicBoolean(false)
    private var deliveryThread: Thread? = null

    @Volatile private var baseIntervalNs = DEFAULT_INTERVAL_MS * 1_000_000

    // Detection state
    private var detectStartTime = 0L
    private var detectCount = 0
    @Volatile private var detected = false

    // Confirmation state
    private var confirmStartTime = 0L
    private var confirmCount = 0
    @Volatile private var confirmed = false

    // Stats
    val statsDelivered = AtomicLong(0)
    val statsUnderruns = AtomicLong(0)

    // ═══════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════

    fun start() {
        if (running.getAndSet(true)) return

        queue.clear()
        detectStartTime = 0L
        detectCount = 0
        detected = false
        confirmStartTime = 0L
        confirmCount = 0
        confirmed = false
        baseIntervalNs = DEFAULT_INTERVAL_MS * 1_000_000
        statsDelivered.set(0)
        statsUnderruns.set(0)

        startDeliveryLoop()
        debugLog("JITTER-BUF", "Started (target=$TARGET_QUEUE gain=${GAIN_NS_PER_PACKET/1000}us/pkt)")
    }

    fun stop() {
        running.set(false)
        deliveryThread?.interrupt()
        queue.clear()
        debugLog("JITTER-BUF", "Stopped (delivered=${statsDelivered.get()} underruns=${statsUnderruns.get()})")
    }

    @Synchronized
    fun enqueue(data: ByteArray, length: Int, receivedAtNs: Long) {
        val now = System.currentTimeMillis()
        val packet = QueuedPacket(data.copyOf(length), length, receivedAtNs)

        // === Phase 3: Confirmed — just queue it ===
        if (confirmed) {
            queue.offer(packet)
            return
        }

        // === Phase 2: Confirming sustained rate ===
        if (detected) {
            queue.offer(packet)
            confirmCount++

            val elapsed = now - confirmStartTime

            if (confirmCount >= CONFIRM_PACKETS) {
                if (elapsed <= CONFIRM_TIMEOUT_MS) {
                    val measured = elapsed / confirmCount
                    val diff = abs(measured - DEFAULT_INTERVAL_MS)

                    val calibrated = if (diff <= ACCEPTABLE_DIFF_MS) {
                        DEFAULT_INTERVAL_MS
                    } else {
                        measured
                    }

                    baseIntervalNs = calibrated * 1_000_000
                    confirmed = true
                    debugLog("JITTER-BUF", "✓ Confirmed! calibrated=${calibrated}ms base=${calibrated}ms queue=${queue.size}")
                } else {
                    debugLog("JITTER-BUF", "✗ Confirm failed ($confirmCount in ${elapsed}ms), flushing")
                    detected = false
                    flushQueueImmediately()
                }
            } else if (elapsed > CONFIRM_TIMEOUT_MS) {
                debugLog("JITTER-BUF", "✗ Confirm timeout ($confirmCount pkts), flushing")
                detected = false
                flushQueueImmediately()
            }
            return
        }

        // === Phase 1: Detecting gameplay start ===
        if (detectStartTime == 0L) {
            detectStartTime = now
            detectCount = 0
        }

        queue.offer(packet)
        detectCount++

        val elapsed = now - detectStartTime

        if (detectCount >= DETECT_PACKETS) {
            if (elapsed <= DETECT_TIMEOUT_MS) {
                detected = true
                confirmStartTime = now
                confirmCount = 0
                debugLog("JITTER-BUF", "⚡ Detected ($detectCount pkts in ${elapsed}ms)")
            } else {
                debugLog("JITTER-BUF", "Setup ($detectCount in ${elapsed}ms), flushing")
                flushQueueImmediately()
            }
        } else if (elapsed > DETECT_TIMEOUT_MS) {
            debugLog("JITTER-BUF", "Timeout ($detectCount pkts), flushing")
            flushQueueImmediately()
        }
    }

    private fun flushQueueImmediately() {
        while (true) {
            val pkt = queue.poll() ?: break
            onDeliver(pkt.data, pkt.length, pkt.receivedAtNs)
        }
        detectStartTime = 0L
        detectCount = 0
    }

    // ═══════════════════════════════════════════════════════════
    // DELIVERY LOOP — CHANGED: No flush, no wait.
    //                 Proportional control handles EVERYTHING.
    // ═══════════════════════════════════════════════════════════

    private fun startDeliveryLoop() {
        deliveryThread = Thread({
            Thread.currentThread().name = "JitterBuffer-Delivery"
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            } catch (_: Exception) {}

            // Wait for confirmation
            while (running.get() && !confirmed) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    // stop() was called while waiting for game traffic detection.
                    // Exit cleanly — the outer running.get() check will also be
                    // false now so the main loop won't start either.
                    return@Thread
                }
            }
            if (!running.get()) return@Thread

            // ═══════════════════════════════════════════════════
            // NO FLUSH. NO WAIT.
            // Queue has ~41 packets from detection+confirmation.
            // Proportional control will:
            //   - Drain excess quickly (err=-27 → deliver at 32ms)
            //   - Settle at TARGET naturally
            //   - Never underrun because we always have buffer
            // ═══════════════════════════════════════════════════
            debugLog("JITTER-BUF", "✓ GO! queue=${queue.size} base=${baseIntervalNs/1_000_000}ms (proportional handles settling)")

            var lastDelivery = System.nanoTime()

            while (running.get()) {
                try {
                    val now = System.nanoTime()
                    val currentSize = queue.size

                    val error = TARGET_QUEUE - currentSize
                    val adjustNs = (error.toLong() * GAIN_NS_PER_PACKET)
                        .coerceIn(-MAX_SPEEDUP_NS, MAX_SLOWDOWN_NS)

                    val currentIntervalNs = baseIntervalNs + adjustNs

                    if (now >= lastDelivery + currentIntervalNs) {
                        val packet = queue.poll()

                        if (packet != null) {
                            onDeliver(packet.data, packet.length, packet.receivedAtNs)
                            statsDelivered.incrementAndGet()
                            lastDelivery = now
                        } else {
                            statsUnderruns.incrementAndGet()
                            while (running.get() && queue.isEmpty()) {
                                Thread.sleep(1)
                            }
                            lastDelivery = System.nanoTime()
                        }
                    } else {
                        val sleepMs = (lastDelivery + currentIntervalNs - now) / 1_000_000
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs)
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    debugLog("JITTER-BUF", "Error: ${e.message}")
                }
            }
        }, "JitterBuffer")

        deliveryThread?.start()
    }

    fun getStats(): String {
        val qSize = queue.size
        val error = TARGET_QUEUE - qSize
        val adjustMs = (error.toLong() * GAIN_NS_PER_PACKET).coerceIn(-MAX_SPEEDUP_NS, MAX_SLOWDOWN_NS) / 1_000_000
        val effectiveMs = baseIntervalNs / 1_000_000 + adjustMs
        return "base=${baseIntervalNs/1_000_000}ms effective=${effectiveMs}ms queue=$qSize/$TARGET_QUEUE err=$error delivered=${statsDelivered.get()} underruns=${statsUnderruns.get()}"
    }
}
