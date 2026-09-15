package com.peerlink.app.tunnel

import androidx.annotation.Keep
import com.peerlink.app.core.AppState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
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
    private val nativeCallbacks = NativeCallbacks()
    private var statsExecutor: ScheduledExecutorService? = null

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

    companion object {
        const val LOG_LEVEL_INFO = 1
        const val LOG_LEVEL_WARN = 2
        const val LOG_LEVEL_ERROR = 3

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
