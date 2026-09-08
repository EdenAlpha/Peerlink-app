package com.peerlink.app.tunnel

import androidx.annotation.Keep
import com.peerlink.app.core.AppState
import com.peerlink.app.core.MatchTracker
import com.peerlink.app.core.NativePacketEvent
import com.peerlink.app.core.NativeTelemetrySample
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@Keep
class NativePeerLinkBackend(
    private val config: NativeBackendConfig,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun preparePeerSocket(fdForBinding: Int): Boolean
        fun fabricateStunResponse(packet: ByteArray, length: Int): ByteArray?
        fun onNativeLog(level: Int, message: String, fileOnly: Boolean) {}
        fun onStats(stats: NativeBackendStats) {}
    }

    @Keep
    private inner class NativeCallbacks {
        @Suppress("unused")
        fun preparePeerSocket(fdForBinding: Int): Boolean {
            return callbacks.preparePeerSocket(fdForBinding)
        }

        @Suppress("unused")
        fun fabricateStunResponse(packet: ByteArray, length: Int): ByteArray? {
            return callbacks.fabricateStunResponse(packet, length)
        }

        @Suppress("unused")
        fun onNativeLog(level: Int, message: String, fileOnly: Boolean) {
            callbacks.onNativeLog(level, message, fileOnly)
        }
    }

    private val handleLock = ReentrantReadWriteLock()
    private var nativeHandle: Long = 0L
    @Volatile private var stopping = false
    private val pollingLock = Any()
    private val telemetryLock = Any()
    private val nativeCallbacks = NativeCallbacks()
    private var statsExecutor: ScheduledExecutorService? = null
    private val telemetryParseFailures = AtomicLong(0L)
    private var lastTelemetryOrdinal = 0L
    private var lastTelemetryHeader = LongArray(9)
    private var hasTelemetryHeader = false

    @Volatile
    var latestStats: NativeBackendStats = NativeBackendStats.EMPTY
        private set

    fun start(tunFd: Int): Int = handleLock.write {
        check(nativeHandle == 0L) { "Native backend already started" }

        val startResult = nativeStart(
            tunFd = tunFd,
            peerLanIp = config.peerLanIp,
            peerPort = config.peerPort,
            localPort = config.localPort,
            myFabricatedIp = config.myFabricatedIp,
            peerFabricatedIp = config.peerFabricatedIp,
            vpnAddress = config.vpnAddress,
            vpnAddressIpv6 = config.vpnAddressIpv6,
            localLanIp = config.localLanIp,
            localInterfaceIndex = config.localInterfaceIndex,
            mtu = config.mtu,
            rawCapturePath = config.rawCapturePath,
            callbacks = nativeCallbacks,
        )

        if (startResult.size < 2 || startResult[0] == 0L || startResult[1] <= 0L) {
            throw IllegalStateException("Native backend failed to start")
        }

        nativeHandle = startResult[0]
        stopping = false
        latestStats = NativeBackendStats.EMPTY
        telemetryParseFailures.set(0L)
        lastTelemetryOrdinal = 0L
        lastTelemetryHeader = LongArray(9)
        hasTelemetryHeader = false
        return@write startResult[1].toInt()
    }

    /** Begin periodic stats/telemetry only after the peer path is verified and MatchTracker is armed. */
    fun startPolling() {
        handleLock.read { if (nativeHandle != 0L && !stopping) startStatsPolling() }
    }

    /** Wake native waits without freeing the handle used by in-flight JNI calls. */
    fun requestStop() = handleLock.read {
        stopping = true
        if (nativeHandle != 0L) nativeRequestStop(nativeHandle)
    }

    fun stop() {
        requestStop()
        stopStatsPolling(waitForTermination = true)
        handleLock.write {
            val handle = nativeHandle
            nativeHandle = 0L
            if (handle != 0L) nativeStop(handle)
        }
        latestStats = NativeBackendStats.EMPTY
    }

    fun pollStats(): NativeBackendStats = handleLock.read {
        val handle = nativeHandle
        if (handle == 0L) return@read NativeBackendStats.EMPTY
        val stats = NativeBackendStats.fromRaw(nativePollStats(handle))
        latestStats = stats
        return@read stats
    }

    /**
     * Drains the versioned PMT2 native rings. A malformed/truncated poll is
     * returned as an integrity failure, never silently accepted or allowed to
     * affect packet forwarding.
     */
    fun pollMatchTelemetry(): NativeTelemetrySample? = handleLock.read {
        synchronized(telemetryLock) {
            val handle = nativeHandle
            if (handle == 0L) return@read null
            val raw = runCatching { nativePollMatchTelemetry(handle) }.getOrElse {
                return@read failedTelemetryPoll()
            }
            decodeMatchTelemetry(raw) ?: failedTelemetryPoll()
        }
    }

    /**
     * Stop the only periodic consumer, wait for it to leave the native drain,
     * then consume the final events exactly once before nativeStop().
     */
    fun drainFinalMatchTelemetry(): NativeTelemetrySample? {
        stopStatsPolling(waitForTermination = true)
        return handleLock.write { pollMatchTelemetry() }
    }

    private fun failedTelemetryPoll(): NativeTelemetrySample = NativeTelemetrySample(
        telemetryParseFailures = telemetryParseFailures.incrementAndGet(),
    )

    private fun decodeMatchTelemetry(raw: ByteArray): NativeTelemetrySample? = runCatching {
        if (raw.size < MATCH_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MATCH_MAGIC) return null
        val version = buffer.short.toInt() and 0xFFFF
        val headerSize = buffer.short.toInt() and 0xFFFF
        val eventSize = buffer.short.toInt() and 0xFFFF
        val count = buffer.short.toInt() and 0xFFFF
        buffer.int // flags/reserved
        if (version != MATCH_VERSION || headerSize != MATCH_HEADER_SIZE || eventSize != MATCH_EVENT_SIZE) return null
        val expectedSize = headerSize.toLong() + count.toLong() * eventSize.toLong()
        if (expectedSize != raw.size.toLong() || count > MATCH_MAX_EVENTS_PER_POLL) return null

        val gameOut = buffer.long
        val gameIn = buffer.long
        val outBytes = buffer.long
        val inBytes = buffer.long
        val lastOut = buffer.long
        val lastIn = buffer.long
        val firstTraffic = buffer.long
        val nativeNow = buffer.long
        val dropped = buffer.long
        val tunGaps1s = buffer.long
        val tunGapLastMs = buffer.long
        val rxGaps1s = buffer.long
        val rxGapLastMs = buffer.long
        if (listOf(gameOut, gameIn, outBytes, inBytes, lastOut, lastIn, firstTraffic, nativeNow, dropped,
                   tunGaps1s, tunGapLastMs, rxGaps1s, rxGapLastMs).any { it < 0L }) return null

        val headerValues = longArrayOf(
            gameOut, gameIn, outBytes, inBytes, lastOut, lastIn,
            firstTraffic, nativeNow, dropped,
            tunGaps1s, tunGapLastMs, rxGaps1s, rxGapLastMs,
        )
        if (hasTelemetryHeader && headerValues.indices.any {
                headerValues[it] < lastTelemetryHeader[it]
            }) return null

        val events = ArrayList<NativePacketEvent>(count)
        var previousOrdinal = lastTelemetryOrdinal
        repeat(count) {
            val ordinal = buffer.long
            val timestamp = buffer.long
            val direction = buffer.get().toInt() and 0xFF
            val payloadLength = buffer.short.toInt() and 0xFFFF
            val headLength = buffer.get().toInt() and 0xFF
            val head = ByteArray(MATCH_HEAD_SIZE)
            buffer.get(head)
            val sourcePort = buffer.short.toInt() and 0xFFFF
            val destPort = buffer.short.toInt() and 0xFFFF
            if (ordinal <= previousOrdinal || timestamp < 0L || direction !in 0..1 ||
                headLength > MATCH_HEAD_SIZE || headLength > payloadLength) return null
            previousOrdinal = ordinal
            events += NativePacketEvent(
                ordinal = ordinal,
                tsMs = timestamp,
                sentByMe = direction == 0,
                payloadLen = payloadLength,
                headLen = headLength,
                head = head,
                sport = sourcePort,
                dport = destPort,
            )
        }
        lastTelemetryOrdinal = previousOrdinal
        lastTelemetryHeader = headerValues
        hasTelemetryHeader = true
        NativeTelemetrySample(
            gameOutPackets = gameOut,
            gameInPackets = gameIn,
            gameOutBytes = outBytes,
            gameInBytes = inBytes,
            lastOutMs = lastOut,
            lastInMs = lastIn,
            firstTrafficMs = firstTraffic,
            nativeNowMs = nativeNow,
            droppedSnapshots = dropped,
            tunGaps1s = tunGaps1s,
            tunGapLastMs = tunGapLastMs,
            rxGaps1s = rxGaps1s,
            rxGapLastMs = rxGapLastMs,
            telemetryParseFailures = telemetryParseFailures.get(),
            events = events,
        )
    }.getOrNull()

    /**
     * Flushes the asynchronous PCAPNG writer without stopping forwarding.
     * Packet threads never wait for this operation; export may wait briefly.
     */
    fun flushRawCapture(): RawCaptureStats = handleLock.read {
        val handle = nativeHandle
        if (handle == 0L) return@read RawCaptureStats()
        return@read RawCaptureStats.fromRaw(nativeFlushRawCapture(handle))
    }

    fun dumpUdpTrace(): String = handleLock.read {
        val handle = nativeHandle
        if (handle == 0L) return@read ""
        return@read nativeDumpUdpTrace(handle)
    }

    fun rebindPeerSocket(): Boolean = handleLock.read {
        val handle = nativeHandle
        if (handle == 0L || stopping) return@read false
        return@read nativeRebindPeerSocket(handle)
    }

    /**
     * Kernel thread IDs (gettid) of the latency-critical native loops — the TUN
     * reader, peer RX, peer TX and TUN-inject threads. Android gives these
     * normal-process URGENT_DISPLAY priority. The TIDs are diagnostic; stock
     * non-root devices are not assumed to have SCHED_FIFO permission.
     */
    fun hotThreadTids(): IntArray = handleLock.read {
        val handle = nativeHandle
        if (handle == 0L) return@read IntArray(0)
        return@read runCatching { nativeGetHotThreadTids(handle) }.getOrDefault(IntArray(0))
    }

    /** Prove the real native UDP data plane works in both directions. */
    fun verifyPeerPath(timeoutMs: Int = 60_000): Boolean = handleLock.read {
        val handle = nativeHandle
        if (handle == 0L) return@read false
        return@read nativeVerifyPeerPath(handle, timeoutMs.coerceIn(500, 90_000))
    }

    private fun startStatsPolling() = synchronized(pollingLock) {
        if (stopping) return@synchronized
        stopStatsPolling()
        statsExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "PeerLink-Native-Stats").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    handleLock.read {
                        if (stopping || nativeHandle == 0L) return@read
                        runCatching {
                            val stats = pollStats()
                            AppState.tunneled.set(stats.totalTunneledPackets)
                            callbacks.onStats(stats)
                        }
                        pollMatchTelemetry()?.let { sample ->
                            runCatching { MatchTracker.onTelemetry(sample) }
                        }
                    }
                },
                1L,
                1L,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun stopStatsPolling(waitForTermination: Boolean = false) {
        val executor = synchronized(pollingLock) {
            statsExecutor.also { statsExecutor = null }
        }
        executor?.shutdownNow()
        if (waitForTermination && executor != null &&
            Thread.currentThread().name != "PeerLink-Native-Stats") {
            runCatching { executor.awaitTermination(2L, TimeUnit.SECONDS) }
        }
    }

    private external fun nativeStart(
        tunFd: Int,
        peerLanIp: String,
        peerPort: Int,
        localPort: Int,
        myFabricatedIp: String,
        peerFabricatedIp: String,
        vpnAddress: String,
        vpnAddressIpv6: String,
        localLanIp: String,
        localInterfaceIndex: Int,
        mtu: Int,
        rawCapturePath: String,
        callbacks: Any,
    ): LongArray

    private external fun nativeStop(handle: Long)
    private external fun nativeRequestStop(handle: Long)

    private external fun nativePollStats(handle: Long): LongArray
    private external fun nativeDumpUdpTrace(handle: Long): String
    private external fun nativeFlushRawCapture(handle: Long): LongArray
    private external fun nativeRebindPeerSocket(handle: Long): Boolean
    private external fun nativeGetHotThreadTids(handle: Long): IntArray
    private external fun nativeVerifyPeerPath(handle: Long, timeoutMs: Int): Boolean
    private external fun nativePollMatchTelemetry(handle: Long): ByteArray

    companion object {
        const val LOG_LEVEL_INFO = 1
        const val LOG_LEVEL_WARN = 2
        const val LOG_LEVEL_ERROR = 3
        private const val MATCH_MAGIC = 0x32544D50
        private const val MATCH_VERSION = 4
        private const val MATCH_HEADER_SIZE = 120
        private const val MATCH_EVENT_SIZE = 84
        private const val MATCH_HEAD_SIZE = 60
        private const val MATCH_MAX_EVENTS_PER_POLL = 4096

        init {
            System.loadLibrary("peerlinkbackend")
        }

        fun fabricateStunWithExistingLogic(
            config: NativeBackendConfig,
            packet: ByteArray,
            length: Int,
        ): ByteArray? {
            if (length <= 0 || packet.isEmpty()) return null
            val version = (packet[0].toInt() ushr 4) and 0x0F
            return when (version) {
                4 -> {
                    val parsed = PacketParser.parse(packet, length)
                    if (!parsed.isValid) {
                        null
                    } else {
                        StunFabricator.fabricateStunResponse(
                            originalPacket = parsed,
                            rawData = packet,
                            rawDataLength = length,
                            fabricatedIp = config.myFabricatedIp,
                            fabricatedPort = parsed.sourcePort,
                            vpnAddress = config.vpnAddress,
                        )
                    }
                }
                6 -> {
                    if (length < 48) {
                        null
                    } else {
                        val srcPort = ((packet[40].toInt() and 0xFF) shl 8) or (packet[41].toInt() and 0xFF)
                        StunFabricator.fabricateIpv6StunResponse(
                            ipv6Packet = packet,
                            length = length,
                            fabricatedIpv4 = config.myFabricatedIp,
                            fabricatedPort = srcPort,
                        )
                    }
                }
                else -> null
            }
        }
    }
}
