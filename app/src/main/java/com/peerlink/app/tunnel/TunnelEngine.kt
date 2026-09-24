package com.peerlink.app.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.peerlink.app.core.AppState
import com.peerlink.app.core.PrimeGameplayTracker
import com.peerlink.app.service.CallMonitorService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import com.peerlink.app.network.UdpProxySockets
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TunnelEngine(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean,
    private val protectTcpSocket: (Socket) -> Boolean,
    private val bridgeMode: Boolean = false
) {

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // OPTIMIZED: Pooled packet wrapper to avoid allocations
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private class PooledPacket {
        var data: ByteArray = ByteArray(1500)
        var length: Int = 0
        var receivedAtNs: Long = 0
        var enqueuedAtNs: Long = 0
        var source: String = ""
        /**
         * Sequence number used by high level instrumentation. A value of 0 indicates
         * that this packet does not participate in the high level logging pipeline
         * (e.g. keepalives, DNS, STUN responses, etc.).
         */
        var seq: Long = 0L
        var t0Ns: Long = 0L
        var s1Ns: Long = 0L
        var flowHash: Int = 0
        var tunnelFlags: Int = 0
        
        fun set(srcData: ByteArray, srcOffset: Int, srcLength: Int, rxNs: Long, src: String) {
            if (data.size < srcLength) {
                data = ByteArray(srcLength)
            }
            System.arraycopy(srcData, srcOffset, data, 0, srcLength)
            length = srcLength
            receivedAtNs = rxNs
            enqueuedAtNs = System.nanoTime()
            source = src
            seq = 0L
            t0Ns = 0L
            s1Ns = 0L
            flowHash = 0
            tunnelFlags = 0
        }
        
        fun setDirect(srcData: ByteArray, srcLength: Int, rxNs: Long, src: String) {
            if (data.size < srcLength) {
                data = ByteArray(srcLength)
            }
            System.arraycopy(srcData, 0, data, 0, srcLength)
            length = srcLength
            receivedAtNs = rxNs
            enqueuedAtNs = System.nanoTime()
            source = src
            seq = 0L
            t0Ns = 0L
            s1Ns = 0L
            flowHash = 0
            tunnelFlags = 0
        }
    }
    
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // OPTIMIZED: Object pools to eliminate allocations
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private class PacketPool(private val maxSize: Int = 256) {
        private val pool = ArrayDeque<PooledPacket>(maxSize)
        private val lock = ReentrantLock()
        
        fun acquire(): PooledPacket {
            lock.withLock {
                return pool.pollFirst() ?: PooledPacket()
            }
        }
        
        fun release(packet: PooledPacket) {
            lock.withLock {
                if (pool.size < maxSize) {
                    pool.addLast(packet)
                }
            }
        }
    }
    
    private val packetPool = PacketPool(256)

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // âš¡ PERF: Signal-based queue â€” wakes consumer thread INSTANTLY when packet arrives
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private class SignaledQueue(capacity: Int = 512) {
        private val queue = ArrayDeque<PooledPacket>(capacity)
        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()

        fun offer(packet: PooledPacket): Boolean {
            lock.withLock {
                queue.addLast(packet)
                notEmpty.signal()
                return true
            }
        }

        fun poll(): PooledPacket? {
            lock.withLock {
                return queue.pollFirst()
            }
        }

        fun pollWait(timeoutMs: Long): PooledPacket? {
            lock.withLock {
                val pkt = queue.pollFirst()
                if (pkt != null) return pkt
                notEmpty.await(timeoutMs, TimeUnit.MILLISECONDS)
                return queue.pollFirst()
            }
        }

        fun drainTo(dest: MutableList<PooledPacket>, maxElements: Int): Int {
            lock.withLock {
                var count = 0
                while (count < maxElements) {
                    val pkt = queue.pollFirst() ?: break
                    dest.add(pkt)
                    count++
                }
                return count
            }
        }

        val size: Int get() = lock.withLock { queue.size }

        fun clear() { lock.withLock { queue.clear() } }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // OPTIMIZED: Pooled UDP/TCP packet queue
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private class QueuedUdpTcpPacket {
        var data: ByteArray = ByteArray(1500)
        var length: Int = 0
        var headerLength: Int = 0
        var totalLength: Int = 0
        var protocol: Int = 0
        var sourcePort: Int = 0
        var destPort: Int = 0
        val sourceIpBytes: ByteArray = ByteArray(4)
        val destIpBytes: ByteArray = ByteArray(4)
        
        @Volatile private var _sourceIp: String? = null
        @Volatile private var _destIp: String? = null
        
        val sourceIp: String
            get() {
                var s = _sourceIp
                if (s == null) {
                    s = "${sourceIpBytes[0].toInt() and 255}.${sourceIpBytes[1].toInt() and 255}.${sourceIpBytes[2].toInt() and 255}.${sourceIpBytes[3].toInt() and 255}"
                    _sourceIp = s
                }
                return s
            }
        
        val destIp: String
            get() {
                var d = _destIp
                if (d == null) {
                    d = "${destIpBytes[0].toInt() and 255}.${destIpBytes[1].toInt() and 255}.${destIpBytes[2].toInt() and 255}.${destIpBytes[3].toInt() and 255}"
                    _destIp = d
                }
                return d
            }
        
        fun copyFrom(src: ByteArray, srcLen: Int, parsed: PacketParser.ParsedPacket) {
            if (data.size < srcLen) data = ByteArray(srcLen)
            System.arraycopy(src, 0, data, 0, srcLen)
            length = srcLen
            headerLength = parsed.headerLength
            totalLength = parsed.totalLength
            protocol = parsed.protocol
            sourcePort = parsed.sourcePort
            destPort = parsed.destPort
            System.arraycopy(parsed.sourceIpBytes, 0, sourceIpBytes, 0, 4)
            System.arraycopy(parsed.destIpBytes, 0, destIpBytes, 0, 4)
            _sourceIp = null
            _destIp = null
        }
    }
    
    private class UdpTcpPacketPool(private val maxSize: Int = 128) {
        private val pool = ArrayDeque<QueuedUdpTcpPacket>(maxSize)
        private val lock = ReentrantLock()
        
        fun acquire(): QueuedUdpTcpPacket {
            lock.withLock {
                return pool.pollFirst() ?: QueuedUdpTcpPacket()
            }
        }
        
        fun release(packet: QueuedUdpTcpPacket) {
            lock.withLock {
                if (pool.size < maxSize) {
                    pool.addLast(packet)
                }
            }
        }
    }
    
    private class UdpTcpQueue(capacity: Int = 256) {
        private val queue = ArrayDeque<QueuedUdpTcpPacket>(capacity)
        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()

        fun offer(packet: QueuedUdpTcpPacket): Boolean {
            lock.withLock {
                queue.addLast(packet)
                notEmpty.signal()
                return true
            }
        }

        fun poll(): QueuedUdpTcpPacket? {
            lock.withLock {
                return queue.pollFirst()
            }
        }

        fun pollWait(timeoutMs: Long): QueuedUdpTcpPacket? {
            lock.withLock {
                if (queue.isEmpty() && timeoutMs > 0) {
                    notEmpty.awaitNanos(timeoutMs * 1_000_000L)
                }
                return queue.pollFirst()
            }
        }

        fun clear() {
            lock.withLock {
                queue.clear()
                notEmpty.signalAll()
            }
        }
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(8)
    private val isRunning = AtomicBoolean(false)

    // âš¡ PERF: Non-blocking DatagramChannel replaces blocking DatagramSocket
    private var peerChannel: DatagramChannel? = null
    private var peerSelector: Selector? = null
    private var localWifiIp: String? = null

    private var udpSelector: Selector? = null
    private var tcpSelector: Selector? = null
    private var ipv6UdpSelector: Selector? = null

    private val MTU = 1400
    private val BUFFER_SIZE = 65535
    private val TUNNEL_PORT = AppState.peerPort.get()
    private val VPN_ADDRESS = "10.0.0.2"
    private val VPN_ADDRESS_BYTES = ipv4StringToBytesOptimized(VPN_ADDRESS)
    
    private val VPN_ADDRESS_IPV6 = "fd00::2"
    private val VPN_ADDRESS_IPV6_BYTES = byteArrayOf(
        0xfd.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02
    )

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // OPTIMIZED: Pre-cached IP byte arrays (computed once, not per-packet)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    @Volatile private var cachedMyFabricatedIpBytes: ByteArray = ByteArray(4)
    @Volatile private var cachedPeerFabricatedIpBytes: ByteArray = ByteArray(4)
    @Volatile private var cachedMyFabricatedIp: String = ""
    @Volatile private var cachedPeerFabricatedIp: String = ""
    
    @Volatile private var cachedPeerSocketAddress: InetSocketAddress? = null
    
    @Volatile private var tunNonBlocking = false
    private val warmupEnabled = false
    
    // âš¡ PERF: WiFi keepalive to prevent power-save gaps
    private val lastKeepaliveSentNs = AtomicLong(0)
    private val KEEPALIVE_INTERVAL_NS = 100_000_000L  // 100ms steady-state keepalive
    private val keepaliveBuffer = ByteBuffer.allocateDirect(1)

    // âš¡ WARMUP: Pre-game WiFi path warmup flag
    @Volatile
    private var warmupCompleted = false

    // âš¡ AGGRESSIVE KEEPALIVE: Track when game traffic starts for dynamic interval
    @Volatile
    private var gameTrafficStartTime: Long = 0L
    
    // âš¡ PERF: WiFi high-performance lock to prevent client power-save
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Legacy tunnel diagnostics
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private var jitterBuffer: JitterBuffer? = null

    private fun updateCachedIpBytes() {
        val myIp = AppState.myFabricatedIp
        val peerIp = AppState.peerFabricatedIp
        
        if (myIp != cachedMyFabricatedIp) {
            cachedMyFabricatedIp = myIp
            cachedMyFabricatedIpBytes = ipv4StringToBytesOptimized(myIp)
        }
        if (peerIp != cachedPeerFabricatedIp) {
            cachedPeerFabricatedIp = peerIp
            cachedPeerFabricatedIpBytes = ipv4StringToBytesOptimized(peerIp)
            PacketParser.updatePeerFabricatedIpCache(peerIp)
        }
    }
    
    private fun getMyFabricatedIpBytes(): ByteArray = cachedMyFabricatedIpBytes
    private fun getPeerFabricatedIpBytes(): ByteArray = cachedPeerFabricatedIpBytes

    @Volatile
    private var detectedGameIp: String? = null
    @Volatile
    private var detectedGameIpBytes: ByteArray? = null

    @Volatile
    private var detectedGameIpv6: String? = null
    @Volatile
    private var detectedGameIpv6Bytes: ByteArray? = null

    @Volatile
    private var stunAssignedGamePort: Int? = null
    @Volatile
    private var gameplayStartHintMs: Long = 0L
    private val recentGameplayUdpTimesMs = ArrayDeque<Long>()
    private val gameplayHintRateThresholdPps = 22
    private val gameplayHintRateWindowMs = 2000L

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // OPTIMIZED: Use pooled queues instead of ConcurrentLinkedQueue
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private val udpTcpPacketPool = UdpTcpPacketPool(128)
    private val deviceToNetworkUdpQueue = UdpTcpQueue(256)
    private val deviceToNetworkTcpQueue = UdpTcpQueue(256)
    
    private val networkToDeviceQueue = SignaledQueue(512)

    private val random = Random()

    // Use literal header size here to avoid Kotlin property initialization order issues;
    // the diagnostic header size remains defined below as 48 bytes.
    private val tunnelSendByteBuffer = ByteBuffer.allocateDirect(MTU + 48 + 32)

    // â•â•â• ASYNC TUNNEL SEND â•â•â•
    private val tunnelSendQueue = java.util.concurrent.ArrayBlockingQueue<PooledPacket>(512)
    private var tunnelSendThread: Thread? = null

    private val buildBufferLocal = ThreadLocal.withInitial { ByteArray(MTU) }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // ðŸ”¬ JITTER DIAGNOSTICS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private object JitterDiag {
        val lastPeerRxNs = AtomicLong(0)
        val lastTunWriteNs = AtomicLong(0)
        val lastTunReadTunnelNs = AtomicLong(0)
        val lastTunReadAnyNs = AtomicLong(0)
        val lastPeerTxNs = AtomicLong(0)
        val intervalPeerRx = AtomicLong(0)
        val intervalPeerTx = AtomicLong(0)
        val intervalTunWrites = AtomicLong(0)
        val intervalTunReadAll = AtomicLong(0)
        val intervalTunReadTunnel = AtomicLong(0)
        val intervalTunReadPass = AtomicLong(0)
        val intervalTunReadIntercept = AtomicLong(0)
        val intervalTunReadDrop = AtomicLong(0)
        val intervalForcedUdpTunnel = AtomicLong(0)
        val intervalInboundGaps = AtomicLong(0)
        val intervalOutboundGaps = AtomicLong(0)
        val intervalQueueSpikes = AtomicLong(0)
        val maxQueueLatencyMs = AtomicLong(0)
        val totalQueueLatencyUs = AtomicLong(0)
        val queueSamples = AtomicLong(0)
        val maxE2eLatencyMs = AtomicLong(0)
        val totalE2eLatencyUs = AtomicLong(0)
        val e2eSamples = AtomicLong(0)
        val intervalSleeps = AtomicLong(0)
        val intervalBatched = AtomicLong(0)
        val intervalGcEvents = AtomicLong(0)
        val totalGcEvents = AtomicLong(0)
        val lastGcTimeMs = AtomicLong(0)
        val heartbeatTunRead = AtomicLong(0)
        val heartbeatTunWrite = AtomicLong(0)
        val heartbeatPeerRx = AtomicLong(0)
        val heartbeatUdpOut = AtomicLong(0)
        val heartbeatUdpIn = AtomicLong(0)
        val heartbeatTcpOut = AtomicLong(0)
        val heartbeatTcpIn = AtomicLong(0)

        const val GAP_THRESHOLD_MS = 150L
        const val QUEUE_SPIKE_MS = 25L
        const val E2E_SPIKE_MS = 35L

        const val HEARTBEAT_STALE_MS = 500L
        const val REPORT_INTERVAL_MS = 2000L
        val tunnelActive = AtomicBoolean(false)
        val tunnelStartTimeMs = AtomicLong(0)

        val maxInboundGapMs = AtomicLong(0)
        val maxOutboundGapMs = AtomicLong(0)
        val maxWriteMs = AtomicLong(0)
        val maxSendMs = AtomicLong(0)
        val intervalWriteSpikes = AtomicLong(0)
        val intervalSendSpikes = AtomicLong(0)

        // Largest observed gap between packets arriving from the peer.
        val maxPeerGapMs = AtomicLong(0)

        fun updateMaxAtomic(atomic: AtomicLong, newValue: Long) {
            while (true) {
                val current = atomic.get()
                if (newValue <= current) break
                if (atomic.compareAndSet(current, newValue)) break
            }
        }

        fun resetInterval() {
            intervalPeerRx.set(0)
            intervalPeerTx.set(0)
            intervalTunWrites.set(0)
            intervalTunReadAll.set(0)
            intervalTunReadTunnel.set(0)
            intervalTunReadPass.set(0)
            intervalTunReadIntercept.set(0)
            intervalTunReadDrop.set(0)
            intervalForcedUdpTunnel.set(0)
            intervalInboundGaps.set(0)
            intervalOutboundGaps.set(0)
            intervalQueueSpikes.set(0)
            maxQueueLatencyMs.set(0)
            totalQueueLatencyUs.set(0)
            queueSamples.set(0)
            maxE2eLatencyMs.set(0)
            totalE2eLatencyUs.set(0)
            e2eSamples.set(0)
            intervalSleeps.set(0)
            intervalBatched.set(0)
            intervalGcEvents.set(0)
            maxInboundGapMs.set(0)
            maxOutboundGapMs.set(0)
            maxWriteMs.set(0)
            maxSendMs.set(0)
            intervalWriteSpikes.set(0)
            intervalSendSpikes.set(0)
            maxPeerGapMs.set(0)
        }
    }

    private fun offerToDevice(data: ByteArray, length: Int, source: String, receivedAtNs: Long = 0L) {
        val pkt = packetPool.acquire()
        pkt.setDirect(data, length, receivedAtNs, source)
        networkToDeviceQueue.offer(pkt)
    }

    /**
     * Version of offerToDevice that also propagates an instrumentation sequence number to
     * the packet. This sequence will later be used in tunWriteLoop to log the R2 stage.
     */
    private fun offerToDeviceWithSeq(data: ByteArray, length: Int, source: String, receivedAtNs: Long, seq: Long) {
        val pkt = packetPool.acquire()
        pkt.setDirect(data, length, receivedAtNs, source)
        pkt.seq = seq
        networkToDeviceQueue.offer(pkt)
    }
    
    private fun offerToDeviceRange(data: ByteArray, offset: Int, length: Int, source: String, receivedAtNs: Long = 0L) {
        val pkt = packetPool.acquire()
        pkt.set(data, offset, length, receivedAtNs, source)
        networkToDeviceQueue.offer(pkt)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // DNS-BASED STUN SERVER DISCOVERY
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private val pendingStunDnsQueries = ConcurrentHashMap<Int, String>()
    private val STUN_DOMAIN_KEYWORDS = listOf("stun")

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // DEBUG LOGGING SYSTEM
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private object DebugStats {
        val stunIntercepted = AtomicLong(0)
        val stunResponsesSent = AtomicLong(0)
        val tunnelOutPackets = AtomicLong(0)
        val tunnelOutBytes = AtomicLong(0)
        val tunnelInPackets = AtomicLong(0)
        val tunnelInBytes = AtomicLong(0)
        val tunnelInRejected = AtomicLong(0)
        val passthroughUdp = AtomicLong(0)
        val passthroughTcp = AtomicLong(0)
        val droppedPackets = AtomicLong(0)
        val ipv6Packets = AtomicLong(0)
        val ipv6StunIntercepted = AtomicLong(0)
        val ipv6TunnelOut = AtomicLong(0)
        val ipv6TunnelIn = AtomicLong(0)
        val ipv6Injected = AtomicLong(0)
        val portCorrected = AtomicLong(0)
        val portChanges = AtomicLong(0)
        val injectionErrors = AtomicLong(0)
        val dnsQueriesWatched = AtomicLong(0)
        val dnsResponsesProcessed = AtomicLong(0)
        val stunServersLearned = AtomicLong(0)
        val lastStatsDump = AtomicLong(System.currentTimeMillis())
        
        fun reset() {
            stunIntercepted.set(0); stunResponsesSent.set(0)
            tunnelOutPackets.set(0); tunnelOutBytes.set(0)
            tunnelInPackets.set(0); tunnelInBytes.set(0); tunnelInRejected.set(0)
            passthroughUdp.set(0); passthroughTcp.set(0); droppedPackets.set(0)
            ipv6Packets.set(0); ipv6StunIntercepted.set(0)
            ipv6TunnelOut.set(0); ipv6TunnelIn.set(0); ipv6Injected.set(0)
            portCorrected.set(0); portChanges.set(0); injectionErrors.set(0)
            dnsQueriesWatched.set(0); dnsResponsesProcessed.set(0); stunServersLearned.set(0)
            lastStatsDump.set(System.currentTimeMillis())
        }
    }
    
    private val seenStunServers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val seenTunnelDestinations: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val seenGamePorts: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    
    /**
     * Centralized logging helper for the tunnel engine.
     *
     * A few quality of life improvements are applied here:
     *  - Any box‑drawing characters (often introduced due to encoding mismatches) are
     *    normalised to simple dashes. This avoids polluting the logs with unreadable
     *    glyphs like the heavy box drawing characters that were previously present.
     *  - The tag is wrapped in square brackets to match existing log style.
     *  - Callers can include emojis directly in the tag to visually distinguish
     *    different stages of the pipeline.
     */
    private fun isPacketHeavyTag(tag: String): Boolean {
        return tag == "📤 S1" || tag == "📤 S2" || tag == "📥 R1" || tag == "📥 R2" || tag == "📦 UDP"
    }

    private val packetTraceLogsEnabled = false

    private inline fun packetTraceLog(tag: String, message: () -> String) {
        if (packetTraceLogsEnabled) {
            debugLog(tag, message())
        }
    }

    private fun debugLog(tag: String, message: String) {
        // replace all Unicode box drawing characters with plain dashes for readability
        val sanitized = message.replace(Regex("[\u2500-\u257F]+"), "----------------")
        val line = "[$tag] $sanitized"
        if (isPacketHeavyTag(tag)) {
            AppState.appendFileOnly(line)
        } else {
            AppState.appendLog(line)
        }
    }

    /**
     * Global sequence counters used by the high level tunnel logging instrumentation.
     * outboundSeqCounter is incremented whenever a packet is read from the TUN and
     * enqueued for tunnelling, while inboundSeqCounter is incremented when a packet
     * is accepted from the peer prior to injection back into the TUN.
     */
    private val outboundSeqCounter: AtomicLong = AtomicLong(0L)
    private val inboundSeqCounter: AtomicLong = AtomicLong(0L)
    private val engineDiagId: Int = ((System.currentTimeMillis().toInt() xor System.identityHashCode(this)) and 0x7fffffff)
    @Volatile private var lastReceivedRemoteSeq: Long = 0L
    private var lastRemoteDiagSenderId: Int = 0
    private var lastRemoteDiagFlowHash: Int = 0
    private var lastRemoteDiagT0Ns: Long = 0L
    private var lastRemoteDiagS1Ns: Long = 0L
    private var lastRemoteDiagSendAttemptNs: Long = 0L
    @Volatile private var boundNetworkLabel: String = "unbound"
    @Volatile private var boundSocketLocalAddress: String = "unknown"
    private val forceTunnelAllUdpAfterGameplay = false
    @Volatile private var allGameUdpTunnelModeActive = false
    private val udpFlowFirstSeen = ConcurrentHashMap.newKeySet<String>()
    private val udpFlowUiAnnounceCount = AtomicLong(0L)

    private data class TunnelDiagMeta(
        val senderId: Int,
        val flowHash: Int,
        val seq: Long,
        val t0Ns: Long,
        val s1Ns: Long,
        val sendAttemptNs: Long,
        val payloadLength: Int,
        val headerSize: Int,
        val flags: Int
    )

    private val TUNNEL_DIAG_MAGIC = 0x504C4447  // 'PLDG'
    private val TUNNEL_DIAG_VERSION: Byte = 2
    private val TUNNEL_DIAG_HEADER_SIZE = 56
    private val TUNNEL_FLAG_FABRICATED_FLOW = 1 shl 0
    private val TUNNEL_FLAG_FORCED_UDP = 1 shl 1


    private data class GameplayFlowObservation(
        var hitsInWindow: Int = 0,
        var firstSeenMs: Long = 0L,
        var lastSeenMs: Long = 0L,
        var lastRemotePort: Int = 0,
        var lastSource: String = ""
    )

    private val gameplayFlowLock = Any()
    private val gameplayFlowObservations = HashMap<Int, GameplayFlowObservation>()
    private val gameplayPortAliases = HashMap<Int, Int>()
    private val gameplayPortAliasExpiryMs = HashMap<Int, Long>()
    @Volatile private var stableGameplayPortLastSeenMs = 0L
    @Volatile private var stableGameplayRemotePort: Int? = null
    private val gameplayFlowCandidateWindowMs = 450L
    private val gameplayFlowMinHitsToLock = 3
    private val gameplayFlowSwitchIdleMs = 1200L
    private val gameplayPortAliasTtlMs = 15000L

    private fun computeFlowHash(parsed: PacketParser.ParsedPacket): Int {
        var result = parsed.protocol
        result = 31 * result + parsed.sourceIp.hashCode()
        result = 31 * result + parsed.destIp.hashCode()
        result = 31 * result + parsed.sourcePort
        result = 31 * result + parsed.destPort
        return result
    }

    private fun shouldExcludeFromForceTunnel(parsed: PacketParser.ParsedPacket): Boolean {
        if (parsed.protocol != PacketParser.PROTOCOL_UDP) return true
        return parsed.destPort == 53 || parsed.sourcePort == 53
    }

    private fun noteGameplayHint(nowMs: Long) {
        if (!forceTunnelAllUdpAfterGameplay) return
        if (gameplayStartHintMs == 0L) {
            gameplayStartHintMs = nowMs
            debugLog("🧪 FORCE-UDP", "Gameplay hint detected from fabricated-peer UDP — watching all game UDP for ${gameplayHintRateThresholdPps}+ pps")
        }
    }

    private fun noteRawGameplayUdpForForceMode(nowMs: Long) {
        if (!forceTunnelAllUdpAfterGameplay || gameplayStartHintMs == 0L || allGameUdpTunnelModeActive) return
        synchronized(recentGameplayUdpTimesMs) {
            recentGameplayUdpTimesMs.addLast(nowMs)
            while (recentGameplayUdpTimesMs.isNotEmpty() && nowMs - recentGameplayUdpTimesMs.first() > gameplayHintRateWindowMs) {
                recentGameplayUdpTimesMs.removeFirst()
            }
            val thresholdCount = (gameplayHintRateThresholdPps * gameplayHintRateWindowMs / 1000).toInt()
            if (recentGameplayUdpTimesMs.size >= thresholdCount) {
                allGameUdpTunnelModeActive = true
                debugLog(
                    "🧪 FORCE-UDP",
                    "Activated tunnel-all-game-UDP mode at ${recentGameplayUdpTimesMs.size} raw UDP packets over ${gameplayHintRateWindowMs}ms after gameplay hint (~${gameplayHintRateThresholdPps}+ pps)"
                )
            }
        }
    }

    private fun currentActionReason(parsed: PacketParser.ParsedPacket, action: PacketParser.PacketAction, forcedTunnel: Boolean): String {
        if (forcedTunnel) return "force-all-udp-after-gameplay"
        return when (action) {
            PacketParser.PacketAction.TUNNEL -> "dest==peerFabricatedIp"
            PacketParser.PacketAction.INTERCEPT -> "stun-intercept"
            PacketParser.PacketAction.PASSTHROUGH -> when {
                parsed.destPort == 53 || parsed.sourcePort == 53 -> "dns"
                parsed.protocol != PacketParser.PROTOCOL_UDP -> "non-udp"
                else -> "default-pass"
            }
            PacketParser.PacketAction.DROP -> when {
                parsed.protocol != PacketParser.PROTOCOL_UDP -> "non-udp-to-fabricated-peer"
                else -> "drop"
            }
        }
    }

    private fun logUdpPacketMeta(parsed: PacketParser.ParsedPacket, length: Int, action: PacketParser.PacketAction, forcedTunnel: Boolean) {
        if (parsed.protocol != PacketParser.PROTOCOL_UDP) return
        val reason = currentActionReason(parsed, action, forcedTunnel)
        val flow = "udp|${parsed.sourceIp}:${parsed.sourcePort}|${parsed.destIp}:${parsed.destPort}"
        if (udpFlowFirstSeen.add(flow)) {
            if (udpFlowUiAnnounceCount.incrementAndGet() <= 20L) {
                AppState.appendLog("[🧭 UDP-FLOW] first-seen action=${action.name} reason=$reason flow=$flow len=$length forceAllUdp=$allGameUdpTunnelModeActive peerFab=${AppState.peerFabricatedIp} gamePort=${stunAssignedGamePort ?: "NONE"}")
            } else {
                AppState.appendFileOnly("[🧭 UDP-FLOW] first-seen action=${action.name} reason=$reason flow=$flow len=$length forceAllUdp=$allGameUdpTunnelModeActive peerFab=${AppState.peerFabricatedIp} gamePort=${stunAssignedGamePort ?: "NONE"}")
            }
        }
        packetTraceLog("📦 UDP") {
            "action=${action.name} reason=$reason flow=$flow len=$length forceAllUdp=$allGameUdpTunnelModeActive peerFab=${AppState.peerFabricatedIp} gameIp=${detectedGameIp ?: "NONE"} gamePort=${stunAssignedGamePort ?: "NONE"} bind=$boundNetworkLabel"
        }
    }

    private fun updateBoundSocketState(socket: DatagramSocket, label: String) {
        boundNetworkLabel = label
        boundSocketLocalAddress = try {
            val localHost = socket.localAddress?.hostAddress ?: localWifiIp ?: "0.0.0.0"
            "$localHost:${socket.localPort}"
        } catch (_: Exception) {
            (localWifiIp ?: "0.0.0.0") + ":?"
        }
    }

    private fun writeTunnelDiagHeader(buffer: ByteBuffer, pkt: PooledPacket, sendAttemptNs: Long) {
        buffer.putInt(TUNNEL_DIAG_MAGIC)
        buffer.put(TUNNEL_DIAG_VERSION)
        buffer.put(pkt.tunnelFlags.toByte())
        buffer.putShort(TUNNEL_DIAG_HEADER_SIZE.toShort())
        buffer.putInt(engineDiagId)
        buffer.putInt(pkt.flowHash)
        buffer.putLong(pkt.seq)
        buffer.putLong(pkt.t0Ns)
        buffer.putLong(pkt.s1Ns)
        buffer.putLong(sendAttemptNs)
        buffer.putInt(pkt.length)
        buffer.putInt(0)
    }

    private fun parseTunnelDiagMeta(data: ByteArray, length: Int): TunnelDiagMeta? {
        if (length < TUNNEL_DIAG_HEADER_SIZE) return null
        val bb = ByteBuffer.wrap(data, 0, length)
        val magic = bb.int
        if (magic != TUNNEL_DIAG_MAGIC) return null
        val version = bb.get()
        if (version != TUNNEL_DIAG_VERSION) return null
        val flags = bb.get().toInt() and 0xff
        val headerSize = bb.short.toInt() and 0xffff
        if (headerSize < TUNNEL_DIAG_HEADER_SIZE || headerSize > length) return null
        val senderId = bb.int
        val flowHash = bb.int
        val seq = bb.long
        val t0Ns = bb.long
        val s1Ns = bb.long
        val sendAttemptNs = bb.long
        val payloadLength = bb.int
        bb.int // reserved
        if (payloadLength < 0 || payloadLength > length - headerSize) return null
        return TunnelDiagMeta(senderId, flowHash, seq, t0Ns, s1Ns, sendAttemptNs, payloadLength, headerSize, flags)
    }

    private fun cleanupGameplayPortAliasesLocked(nowMs: Long) {
        val expired = gameplayPortAliasExpiryMs.entries
            .filter { it.value <= nowMs }
            .map { it.key }
        expired.forEach {
            gameplayPortAliasExpiryMs.remove(it)
            gameplayPortAliases.remove(it)
        }
    }

    private fun lockStableGameplayPortLocked(newPort: Int, remotePort: Int, source: String, nowMs: Long) {
        val oldPort = stunAssignedGamePort
        val oldIdleMs = if (stableGameplayPortLastSeenMs > 0L) nowMs - stableGameplayPortLastSeenMs else -1L
        stunAssignedGamePort = newPort
        stableGameplayRemotePort = remotePort
        stableGameplayPortLastSeenMs = nowMs
        if (oldPort != null && oldPort != newPort) {
            gameplayPortAliases[oldPort] = newPort
            gameplayPortAliasExpiryMs[oldPort] = nowMs + gameplayPortAliasTtlMs
            DebugStats.portChanges.incrementAndGet()
            debugLog("🎮 PORT-CHANGE", "Stable gameplay port CHANGED: $oldPort → $newPort (remote=$remotePort source=$source idleMs=$oldIdleMs)")
        } else if (oldPort == null) {
            debugLog("🎮 PORT-DETECT", "Stable gameplay port detected: $newPort (remote=$remotePort source=$source)")
        }
    }

    private fun observeGameplayFlow(localPort: Int, remotePort: Int, source: String) {
        val nowMs = System.currentTimeMillis()
        seenGamePorts.add(localPort)
        synchronized(gameplayFlowLock) {
            cleanupGameplayPortAliasesLocked(nowMs)
            val obs = gameplayFlowObservations.getOrPut(localPort) { GameplayFlowObservation() }
            if (nowMs - obs.lastSeenMs > gameplayFlowCandidateWindowMs) {
                obs.hitsInWindow = 0
                obs.firstSeenMs = nowMs
            }
            obs.hitsInWindow += 1
            if (obs.firstSeenMs == 0L) obs.firstSeenMs = nowMs
            obs.lastSeenMs = nowMs
            obs.lastRemotePort = remotePort
            obs.lastSource = source

            val currentStable = stunAssignedGamePort
            if (currentStable == null) {
                if (obs.hitsInWindow >= gameplayFlowMinHitsToLock) {
                    lockStableGameplayPortLocked(localPort, remotePort, source, nowMs)
                }
                return
            }

            if (currentStable == localPort) {
                stableGameplayPortLastSeenMs = nowMs
                stableGameplayRemotePort = remotePort
                return
            }

            if (nowMs - stableGameplayPortLastSeenMs < gameplayFlowSwitchIdleMs) {
                gameplayPortAliases[localPort] = currentStable
                gameplayPortAliasExpiryMs[localPort] = nowMs + gameplayPortAliasTtlMs
                return
            }

            if (obs.hitsInWindow >= gameplayFlowMinHitsToLock) {
                lockStableGameplayPortLocked(localPort, remotePort, source, nowMs)
            }
        }
    }

    private fun resolveInboundGameplayPort(peerSentPort: Int, tunnelFlags: Int): Int {
        val currentStable = stunAssignedGamePort ?: return peerSentPort
        val nowMs = System.currentTimeMillis()
        synchronized(gameplayFlowLock) {
            cleanupGameplayPortAliasesLocked(nowMs)
            stableGameplayPortLastSeenMs = maxOf(stableGameplayPortLastSeenMs, nowMs)
            if (peerSentPort == currentStable) return currentStable
            gameplayPortAliases[peerSentPort]?.let { return it }
            if ((tunnelFlags and TUNNEL_FLAG_FABRICATED_FLOW) != 0) {
                if (seenGamePorts.contains(peerSentPort)) return currentStable
                return currentStable
            }
            return peerSentPort
        }
    }

    private fun logGapTriggeredSnapshot(tag: String, gapMs: Long, seq: Long, missing: Long, extra: String = "") {
        if (bridgeMode && tag == "🧨 GAP-T0" && gapMs < 150L && AppState.isRunning.get()) return
        val effectiveTag = if (bridgeMode && tag == "🧨 GAP-T0") "🧨 BRIDGE-GAP-T0" else tag
        val detail = if (bridgeMode) {
            "gapMs=$gapMs seq=$seq missing=$missing mode=bridge qTun=${networkToDeviceQueue.size} $extra"
        } else {
            "gapMs=$gapMs seq=$seq missing=$missing bind=$boundNetworkLabel local=$boundSocketLocalAddress peer=${AppState.peerIp.get()?.hostAddress ?: "?"} qSend=${tunnelSendQueue.size} qTun=${networkToDeviceQueue.size} $extra"
        }
        debugLog(effectiveTag, detail)
    }

    private fun classifyRxGapSource(
        sameRemoteFlow: Boolean,
        gapMs: Long,
        remoteTunGapMs: Long,
        remoteCaptureGapMs: Long,
        remoteSendGapMs: Long,
        currentTunToS1Us: Long,
        currentPreSendUs: Long,
        currentPostSendUs: Long
    ): String {
        if (!sameRemoteFlow) return "unknown-prev-flow"
        if (gapMs <= JitterDiag.GAP_THRESHOLD_MS) return "no-gap"

        val gapUs = gapMs * 1_000L
        val senderStillSteady = remoteSendGapMs in 0..maxOf(20L, gapMs / 3)
        val senderHeldAfterTun = currentTunToS1Us >= maxOf(20_000L, gapUs / 4)
        val senderHeldBeforeSend = currentPreSendUs >= maxOf(80_000L, gapUs / 2)
        val receiverSawItLate = currentPostSendUs >= maxOf(80_000L, gapUs / 2)
        val senderTunGap = remoteTunGapMs >= maxOf(80L, gapMs - 40)
        val senderInputGap = remoteCaptureGapMs >= maxOf(80L, gapMs - 40)
        val senderSendGap = remoteSendGapMs >= maxOf(80L, gapMs - 40)

        return when {
            senderStillSteady && receiverSawItLate -> "post-send-delivery"
            senderSendGap && !senderInputGap && senderHeldBeforeSend -> "sender-pre-send"
            senderInputGap && !senderTunGap && senderHeldAfterTun -> "post-tun-pre-s1"
            senderTunGap && senderInputGap && senderSendGap -> "before-tun-ingress"
            senderInputGap && senderSendGap -> "sender-input-or-capture"
            senderTunGap -> "before-tun-ingress"
            senderInputGap -> "post-tun-pre-s1-or-mixed"
            senderSendGap -> "sender-send-cadence"
            else -> "mixed"
        }
    }

    private fun hexDump(data: ByteArray, maxLen: Int = 64): String {
        val len = minOf(data.size, maxLen)
        return data.take(len).joinToString(" ") { String.format("%02X", it) } +
               if (data.size > maxLen) "... (${data.size}b total)" else ""
    }
    
    private fun dumpStatsIfNeeded() {
        if (bridgeMode) return
        val now = System.currentTimeMillis()
        val lastDump = DebugStats.lastStatsDump.get()
        
        if (now - lastDump > 30_000) {
            DebugStats.lastStatsDump.set(now)
            
            val peerIp = AppState.peerIp.get()?.hostAddress ?: "NOT SET"
            val isPaired = AppState.isPaired.get()
            
            debugLog("ðŸ“Š STATS", "â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•")
            debugLog("ðŸ“Š CONFIG", "Paired=$isPaired | PeerLanIP=$peerIp | LocalIP=$localWifiIp | Bind=$boundNetworkLabel | Sock=$boundSocketLocalAddress | Engine=$engineDiagId")
            debugLog("ðŸ“Š CONFIG", "MyFabricatedIP=${AppState.myFabricatedIp} | PeerFabricatedIP=${AppState.peerFabricatedIp}")
            debugLog("ðŸ“Š GAME-IPv4", "DetectedIP=${detectedGameIp ?: "NOT YET"}")
            debugLog("ðŸ“Š GAME-IPv6", "DetectedIP=${detectedGameIpv6 ?: "NOT YET"}")
            debugLog("ðŸ“Š GAME-PORT", "Current=${stunAssignedGamePort ?: "NOT YET"} | Remote=${stableGameplayRemotePort ?: "NONE"} | Changes=${DebugStats.portChanges.get()} | SeenPorts=${seenGamePorts.joinToString(",")}")
            debugLog("ðŸ“Š DNS", "Watched=${DebugStats.dnsQueriesWatched.get()} | Responses=${DebugStats.dnsResponsesProcessed.get()} | ServersLearned=${DebugStats.stunServersLearned.get()}")
            debugLog("ðŸ“Š DNS-LEARNED", "IPv4=${PacketParser.learnedStunServerIps.joinToString(",")} | IPv6=${PacketParser.learnedStunServerIpv6s.size} entries")
            debugLog("ðŸ“Š STUN", "IPv4=${DebugStats.stunIntercepted.get()} | IPv6=${DebugStats.ipv6StunIntercepted.get()} | Responses=${DebugStats.stunResponsesSent.get()}")
            debugLog("ðŸ“Š TUNNEL-OUT", "Total=${DebugStats.tunnelOutPackets.get()} pkts (${DebugStats.tunnelOutBytes.get()}b) | IPv6=${DebugStats.ipv6TunnelOut.get()}")
            debugLog("ðŸ“Š TUNNEL-IN", "Total=${DebugStats.tunnelInPackets.get()} pkts (${DebugStats.tunnelInBytes.get()}b) | IPv6=${DebugStats.ipv6TunnelIn.get()}")
            debugLog("ðŸ“Š INJECT", "IPv6=${DebugStats.ipv6Injected.get()} | PortCorrected=${DebugStats.portCorrected.get()} | Errors=${DebugStats.injectionErrors.get()}")
            debugLog("ðŸ“Š REJECTED", "TunnelIn=${DebugStats.tunnelInRejected.get()}")
            debugLog("ðŸ“Š PASS", "UDP=${DebugStats.passthroughUdp.get()} | TCP=${DebugStats.passthroughTcp.get()}")
            debugLog("ðŸ“Š OTHER", "IPv6Total=${DebugStats.ipv6Packets.get()} | Dropped=${DebugStats.droppedPackets.get()}")
            
            if (seenStunServers.isNotEmpty()) {
                debugLog("ðŸ“Š STUN-SERVERS", seenStunServers.joinToString(", "))
            }
            if (seenTunnelDestinations.isNotEmpty()) {
                debugLog("ðŸ“Š TUNNEL-DESTS", seenTunnelDestinations.joinToString(", "))
            }
            debugLog("ðŸ“Š STATS", "â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•")
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // CACHES
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private val ipv6ChannelCacheLock = Any()
    private val ipv6ChannelCache = object : LinkedHashMap<String, DatagramChannel>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DatagramChannel>?): Boolean {
            if (size > 100) { eldest?.value?.closeQuietly(); return true }
            return false
        }
    }

    private val udpChannelCacheLock = Any()
    private val udpChannelCache = object : LinkedHashMap<String, DatagramChannel>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DatagramChannel>?): Boolean {
            if (size > 100) { eldest?.value?.closeQuietly(); return true }
            return false
        }
    }

    private val tcbCacheLock = Any()
    private val tcbCache = object : LinkedHashMap<String, TcpTcb>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TcpTcb>?): Boolean {
            if (size > 50) { eldest?.value?.close(); return true }
            return false
        }
    }

    private val ipv6TcbCacheLock = Any()
    private val ipv6TcbCache = object : LinkedHashMap<String, TcpTcb>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TcpTcb>?): Boolean {
            if (size > 50) { eldest?.value?.close(); return true }
            return false
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // âš¡ OPTIMIZATION HELPERS: Warmup + Dynamic Keepalive
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Conservative keepalive policy.
     * The old 5ms/10ms policy created avoidable wakeups and scheduler churn.
     */
    private fun getCurrentKeepaliveIntervalNs(): Long {
        if (gameTrafficStartTime == 0L) return KEEPALIVE_INTERVAL_NS
        val elapsed = System.currentTimeMillis() - gameTrafficStartTime
        return when {
            elapsed < 30_000L -> 50_000_000L    // 50ms
            elapsed < 90_000L -> 75_000_000L    // 75ms
            else -> KEEPALIVE_INTERVAL_NS        // 100ms
        }
    }

    /**
     * âš¡ WARMUP: Send rapid keepalive packets to pre-warm WiFi path.
     * This wakes the WiFi radio from power-save mode BEFORE game traffic arrives,
     * eliminating the 15-50ms latency spike that occurs when the radio first wakes up.
     * Also triggers GC cleanup of startup garbage to prevent GC pauses during gameplay.
     */
    private fun runWarmupPhase() {
        if (!warmupEnabled) {
            warmupCompleted = true
            debugLog("WARMUP", "Warmup disabled for stable packet cadence")
            return
        }
        Thread {
            Thread.currentThread().name = "PeerLink-Warmup"
            try {
                val peerIp = AppState.peerIp.get() ?: return@Thread
                val ch = peerChannel ?: return@Thread

                var addr = cachedPeerSocketAddress
                if (addr == null || addr.address != peerIp) {
                    addr = InetSocketAddress(peerIp, TUNNEL_PORT)
                    cachedPeerSocketAddress = addr
                }

                debugLog("WARMUP", "Starting conservative WiFi path warmup (20 packets over 2s)...")

                val warmupBuf = ByteBuffer.allocateDirect(1)

                for (i in 1..20) {
                    if (!isRunning.get()) return@Thread
                    try {
                        warmupBuf.clear()
                        warmupBuf.put(0xFF.toByte())
                        warmupBuf.flip()
                        ch.send(warmupBuf, addr)
                    } catch (_: Exception) {}
                    Thread.sleep(100)
                }

                warmupCompleted = true
                lastKeepaliveSentNs.set(System.nanoTime())
                debugLog("WARMUP", "Conservative warmup completed")

            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PERF: start() — Non-blocking TUN + DatagramChannel + Selector + Wi-Fi lock + warmup
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun startBridgeMode() {
        tunNonBlocking = false
        jitterBuffer = null
        lastKeepaliveSentNs.set(0)

        udpSelector = Selector.open()
        tcpSelector = Selector.open()
        ipv6UdpSelector = Selector.open()

        debugLog("ENGINE", "Passthrough bridge mode: Kotlin TCP/UDP proxy only")
        debugLog("ENGINE", "Bridge TUN mode: blocking")

        executor.submit { tunReadLoop() }
        executor.submit { tunWriteLoop() }
        executor.submit { udpOutputLoop() }
        executor.submit { udpInputLoop() }
        executor.submit { ipv6UdpInputLoop() }
        executor.submit { tcpOutputLoop() }
        executor.submit { tcpInputLoop() }
    }

    fun start() {
        if (isRunning.getAndSet(true)) {
            AppState.appendLog("Engine already running")
            return
        }

        DebugStats.reset()
        JitterDiag.resetInterval()
        JitterDiag.tunnelActive.set(false)
        JitterDiag.totalGcEvents.set(0)
        PassthroughRecorder.init(context)
        seenStunServers.clear()
        seenTunnelDestinations.clear()
        seenGamePorts.clear()
        pendingStunDnsQueries.clear()
        PacketParser.clearLearnedStunServers()
        
        detectedGameIp = null
        detectedGameIpBytes = null
        detectedGameIpv6 = null
        detectedGameIpv6Bytes = null
        stunAssignedGamePort = null
        cachedPeerSocketAddress = null
        lastKeepaliveSentNs.set(0)

        // âš¡ WARMUP + AGGRESSIVE KEEPALIVE: Reset optimization state
        warmupCompleted = false
        gameTrafficStartTime = 0L
        allGameUdpTunnelModeActive = false
        gameplayStartHintMs = 0L
        synchronized(recentGameplayUdpTimesMs) { recentGameplayUdpTimesMs.clear() }
        udpFlowFirstSeen.clear()
        udpFlowUiAnnounceCount.set(0L)

        if (!AppState.calculateFabricatedIps()) {
            debugLog("âš ï¸ ENGINE", "Could not calculate fabricated IPs yet - will retry after socket binding")
        }
        
        updateCachedIpBytes()

        if (bridgeMode) {
            try {
                startBridgeMode()
                return
            } catch (e: Exception) {
                debugLog("âŒ ENGINE", "Bridge mode start failed: ${e.message}")
                AppState.appendLog("Bridge mode start failed: ${e.message}")
                stop()
                return
            }
        }

        // âš¡ PERF: Acquire LOW-LATENCY WiFi lock
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            
            val lockMode = if (android.os.Build.VERSION.SDK_INT >= 29) {
                4 // WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val modeName = if (lockMode == 4) "LOW-LATENCY" else "HIGH-PERF"
            
            wifiLock = wifiManager.createWifiLock(lockMode, "PeerLink:TunnelWifiLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            debugLog("ðŸš€ ENGINE", "âš¡ WiFi $modeName lock acquired (mode=$lockMode)")
            
            try {
                multicastLock = wifiManager.createMulticastLock("PeerLink:MulticastLock")
                multicastLock?.setReferenceCounted(false)
                multicastLock?.acquire()
                debugLog("ðŸš€ ENGINE", "âš¡ Multicast lock acquired")
            } catch (e2: Exception) {
                debugLog("âš ï¸ ENGINE", "Multicast lock failed: ${e2.message}")
            }
        } catch (e: Exception) {
            debugLog("âš ï¸ ENGINE", "WiFi lock failed: ${e.message}")
        }

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        // Legacy Kotlin jitter buffer intentionally disabled. The production
        // gameplay path is the native bridge and must not add an app-level
        // pacing queue or claim to repair upstream receive gaps.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        val jitterEnabled = false
        if (jitterEnabled) {
            jitterBuffer = JitterBuffer(
                onDeliver = { data, length, receivedAtNs ->
                    offerToDevice(data, length, "JITTER-BUF", receivedAtNs)
                },
                debugLog = { tag, msg -> debugLog(tag, msg) }
            )
            jitterBuffer?.start()
            debugLog("ENGINE", "[JITTER    ] Buffer ENABLED")
        } else {
            jitterBuffer = null
            debugLog("ENGINE", "[JITTER    ] Buffer DISABLED (Apex Mode active or user preference)")
        }

        // âš¡ Start keepalives immediately
        lastKeepaliveSentNs.set(System.nanoTime())
        try {
            udpSelector = Selector.open()
            tcpSelector = Selector.open()
            ipv6UdpSelector = Selector.open()

            tunNonBlocking = false
            debugLog("ENGINE", "Using blocking TUN I/O for stable packet cadence")

            peerChannel = DatagramChannel.open().also { ch ->
                ch.bind(InetSocketAddress(TUNNEL_PORT))
                val sock = ch.socket()
                if (!protectDatagramSocket(sock)) {
                    throw IllegalStateException("Failed to protect peer channel")
                }
                if (!bindSocketToWifi(sock)) {
                    throw IllegalStateException("Exact gameplay network bind failed")
                }
                ch.configureBlocking(false)

                try {
                    sock.receiveBufferSize = 65536
                    sock.sendBufferSize = 65536
                    debugLog("ENGINE", "Peer socket buffers: rx=${sock.receiveBufferSize}, tx=${sock.sendBufferSize}")
                } catch (_: Exception) {}
            }

            if (!localWifiIp.isNullOrBlank() && localWifiIp != "127.0.0.1") {
                if (AppState.localIp.get().isNullOrBlank() || AppState.localIp.get() == "127.0.0.1") {
                    AppState.localIp.set(localWifiIp)
                    debugLog("🌐 HOTSPOT", "Local IP repaired from bound socket: $localWifiIp")
                }
                if (AppState.calculateFabricatedIps()) {
                    updateCachedIpBytes()
                    debugLog("🌐 FAB-ID", "Fabricated mapping repaired after bind: my=${AppState.myFabricatedIp} peer=${AppState.peerFabricatedIp}")
                }
            }

            peerSelector = Selector.open()
            peerChannel!!.register(peerSelector!!, SelectionKey.OP_READ)

            executor.submit { tunReadLoop() }
            executor.submit { tunWriteLoop() }
            executor.submit { udpOutputLoop() }
            executor.submit { udpInputLoop() }
            executor.submit { ipv6UdpInputLoop() }
            executor.submit { tcpOutputLoop() }
            executor.submit { tcpInputLoop() }
            executor.submit { peerReceiveLoop() }
            executor.submit { gcMonitorLoop() }
            executor.submit { jitterDiagnosticLoop() }

            // âš¡ Start async tunnel send thread
            tunnelSendThread = Thread {
                tunnelSendLoop()
            }.apply {
                name = "PeerLink-Tunnel-Send"
                isDaemon = true
                start()
            }
            debugLog("ðŸš€ ENGINE", "âš¡ ASYNC TUNNEL SEND: ENABLED")

            debugLog("ðŸš€ ENGINE", "â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• STARTED â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•")
            debugLog("ðŸš€ ENGINE", "Tunnel Port: $TUNNEL_PORT")
            debugLog("ðŸš€ ENGINE", "Local Wi-Fi IP: ${localWifiIp ?: "Unknown"}")
            debugLog("ðŸš€ ENGINE", "VPN Address IPv4: $VPN_ADDRESS")
            debugLog("ðŸš€ ENGINE", "VPN Address IPv6: $VPN_ADDRESS_IPV6")
            debugLog("ðŸš€ ENGINE", "Peer LAN IP: ${AppState.peerIp.get()?.hostAddress ?: "NOT SET"}")
            debugLog("ðŸš€ ENGINE", "My Fabricated IP: ${AppState.myFabricatedIp}")
            debugLog("ðŸš€ ENGINE", "Peer Fabricated IP: ${AppState.peerFabricatedIp}")
            debugLog("ðŸš€ ENGINE", "Is Paired: ${AppState.isPaired.get()}")
            debugLog("ðŸš€ ENGINE", "DNS-based STUN discovery: ENABLED")
            debugLog("ðŸš€ ENGINE", "ðŸ”¬ JITTER DIAGNOSTICS: ENABLED (summary every 2s)")
            debugLog("ENGINE", "Network underlay: DatagramChannel + Selector")
            debugLog("ENGINE", "Signal-based device queue enabled")
            debugLog("ENGINE", "TUN mode: ${if (tunNonBlocking) "non-blocking" else "blocking"}")
            debugLog("ENGINE", "Thread priority: default")
            val wifiMode = if (android.os.Build.VERSION.SDK_INT >= 29) "LOW-LATENCY" else "HIGH-PERF"
            debugLog("ðŸš€ ENGINE", "âš¡ WIFI LOCK: ${if (wifiLock?.isHeld == true) "ACQUIRED ($wifiMode)" else "NOT HELD"}")
            debugLog("ðŸš€ ENGINE", "âš¡ MULTICAST LOCK: ${if (multicastLock?.isHeld == true) "ACQUIRED" else "NOT HELD"}")
            // âš¡ CHANGED: Dynamic keepalive info
            debugLog("ENGINE", "Keepalive: conservative dynamic (50ms→75ms→100ms)")
            // âš¡ NEW: Warmup info
            debugLog("ENGINE", "Warmup: disabled by default")
            debugLog("ðŸš€ ENGINE", "â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•")

            // âš¡ WARMUP: Pre-warm WiFi path before game traffic arrives
            runWarmupPhase()

        } catch (e: Exception) {
            debugLog("âŒ ENGINE", "Start failed: ${e.message}")
            e.printStackTrace()
            AppState.appendLog("Engine start failed: ${e.message}")
            stop()
        }
    }

    private fun preferredPassthroughNetwork(): android.net.Network? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val storedIp = AppState.localIp.get()

            fun eligible(network: android.net.Network, requireValidated: Boolean): Boolean {

                val caps = cm.getNetworkCapabilities(network) ?: return false
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
                if (requireValidated && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false

                val linkProperties = cm.getLinkProperties(network)
                val carriesStoredIp = !storedIp.isNullOrBlank() && linkProperties?.linkAddresses?.any {
                    it.address.hostAddress == storedIp
                } == true
                if (carriesStoredIp && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return false
                }
                return true
            }

            cm.allNetworks.firstOrNull { eligible(it, true) }
                ?: cm.allNetworks.firstOrNull { eligible(it, false) }
        } catch (_: Exception) {
            null
        }
    }

    private fun bindPassthroughDatagramSocket(socket: DatagramSocket) {
        try {
            preferredPassthroughNetwork()?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }

    private fun bindPassthroughTcpSocket(socket: Socket) {
        try {
            preferredPassthroughNetwork()?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }

    private fun canonicalTransportMode(): String {
        val active = AppState.activeTransportMode.get()
        if (active != "none") return active
        return when (AppState.connectionMode) {
            "hotspot" -> "wifi_udp"
            else -> "none"
        }
    }

    private fun isUsableGameplayNetwork(cm: ConnectivityManager, network: android.net.Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        val iface = try { cm.getLinkProperties(network)?.interfaceName } catch (_: Exception) { null }
        if (!iface.isNullOrBlank()) {
            val lowered = iface.lowercase()
            if (lowered.startsWith("tun") || lowered.contains("vpn")) return false
        }
        return true
    }

    private fun resolveExactGameplayNetwork(cm: ConnectivityManager, transportMode: String): android.net.Network? {
fun exactWifiUdpNetwork(): android.net.Network? {
            val localIp = AppState.localIp.get()
            if (localIp.isNullOrBlank() || localIp == "127.0.0.1") return null
            val matches = cm.allNetworks.filter { network ->
                if (!isUsableGameplayNetwork(cm, network)) return@filter false
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@filter false
                val lp = cm.getLinkProperties(network) ?: return@filter false
                lp.linkAddresses.any { it.address is Inet4Address && it.address.hostAddress == localIp }
            }
            if (matches.isEmpty()) return null
            if (matches.size == 1) return matches.first()
            val active = cm.activeNetwork
            if (active != null && matches.contains(active)) return active
            return null
        }

        return when (transportMode) {
            "wifi_udp" -> exactWifiUdpNetwork()
            else -> null
        }
    }

    private fun bindSocketToWifi(socket: DatagramSocket): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val transportMode = canonicalTransportMode()
            val network = resolveExactGameplayNetwork(cm, transportMode)
            if (network == null) {
                debugLog("NETWORK", "No exact gameplay network available for mode=$transportMode")
                updateBoundSocketState(socket, "unbound:$transportMode")
                false
            } else {
                network.bindSocket(socket)
                val localIp = cm.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                    it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
                }?.address?.hostAddress ?: AppState.localIp.get()
                localWifiIp = localIp
                updateBoundSocketState(socket, "exact:$transportMode:${network}")
                debugLog("NETWORK", "peerChannel bound to exact ${transportMode} network: $localIp | local=$boundSocketLocalAddress")
                true
            }
        } catch (e: Exception) {
            debugLog("NETWORK", "Exact gameplay bind error: ${e.message}")
            false
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PERF: stop() — closes channels, peer selector and Wi-Fi locks
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    fun stop() {
        isRunning.set(false)
        PassthroughRecorder.stop()

        // âš¡ Stop async tunnel send thread
        tunnelSendThread?.interrupt()
        tunnelSendThread = null

        // Drain tunnel send queue
        while (true) {
            val pkt = tunnelSendQueue.poll() ?: break
            packetPool.release(pkt)
        }
        
        if (bridgeMode) {
            debugLog("ENGINE", "Passthrough bridge stopping")
        } else {
            debugLog("ðŸ›‘ ENGINE", "â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• STOPPING â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•")
            debugLog("ðŸ“Š FINAL", "DNS: Watched=${DebugStats.dnsQueriesWatched.get()}, Responses=${DebugStats.dnsResponsesProcessed.get()}, Learned=${DebugStats.stunServersLearned.get()}")
            debugLog("ðŸ“Š FINAL", "Learned STUN IPs: ${PacketParser.learnedStunServerIps.joinToString(", ")}")
            debugLog("ðŸ“Š FINAL", "STUN: IPv4=${DebugStats.stunIntercepted.get()}, IPv6=${DebugStats.ipv6StunIntercepted.get()}, Responses=${DebugStats.stunResponsesSent.get()}")
            debugLog("ðŸ“Š FINAL", "TUNNEL OUT: ${DebugStats.tunnelOutPackets.get()} pkts, ${DebugStats.tunnelOutBytes.get()} bytes")
            debugLog("ðŸ“Š FINAL", "TUNNEL IN: ${DebugStats.tunnelInPackets.get()} pkts, ${DebugStats.tunnelInBytes.get()} bytes")
            debugLog("ðŸ“Š FINAL", "IPv6: Out=${DebugStats.ipv6TunnelOut.get()}, In=${DebugStats.ipv6TunnelIn.get()}, Injected=${DebugStats.ipv6Injected.get()}")
            debugLog("ðŸ“Š FINAL", "Port: Corrected=${DebugStats.portCorrected.get()}, Changes=${DebugStats.portChanges.get()}")
            debugLog("ðŸ“Š FINAL", "Rejected=${DebugStats.tunnelInRejected.get()}, InjectionErrors=${DebugStats.injectionErrors.get()}")
            debugLog("ðŸ“Š FINAL", "Detected Game IPv4: ${detectedGameIp ?: "NONE"}")
            debugLog("ðŸ“Š FINAL", "Detected Game IPv6: ${detectedGameIpv6 ?: "NONE"}")
            debugLog("ðŸ“Š FINAL", "Final Game Port: ${stunAssignedGamePort ?: "NONE"}")
            debugLog("ðŸ“Š FINAL", "All Seen Ports: ${seenGamePorts.joinToString(",")}")
            debugLog("ðŸ“Š FINAL", "ðŸ”¬ Total GC events: ${JitterDiag.totalGcEvents.get()}")
            debugLog("ðŸ›‘ ENGINE", "â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•")
        }

        try { udpSelector?.wakeup() } catch (_: Exception) {}
        try { tcpSelector?.wakeup() } catch (_: Exception) {}
        try { ipv6UdpSelector?.wakeup() } catch (_: Exception) {}
        try { peerSelector?.wakeup() } catch (_: Exception) {}

        try { peerChannel?.close() } catch (_: Exception) {}
        peerChannel = null
        localWifiIp = null
        
        detectedGameIp = null
        detectedGameIpBytes = null
        detectedGameIpv6 = null
        detectedGameIpv6Bytes = null
        stunAssignedGamePort = null
        cachedPeerSocketAddress = null
        lastKeepaliveSentNs.set(0)

        // âš¡ WARMUP + AGGRESSIVE KEEPALIVE: Reset optimization state
        warmupCompleted = false
        gameTrafficStartTime = 0L
        allGameUdpTunnelModeActive = false
        gameplayStartHintMs = 0L
        synchronized(recentGameplayUdpTimesMs) { recentGameplayUdpTimesMs.clear() }
        udpFlowFirstSeen.clear()
        udpFlowUiAnnounceCount.set(0L)
        
        pendingStunDnsQueries.clear()
        PacketParser.clearLearnedStunServers()
        
        networkToDeviceQueue.clear()
        deviceToNetworkUdpQueue.clear()
        deviceToNetworkTcpQueue.clear()

        try {
            synchronized(udpChannelCacheLock) {
                val it = udpChannelCache.entries.iterator()
                while (it.hasNext()) { it.next().value.closeQuietly(); it.remove() }
            }
        } catch (_: Exception) {}

        try {
            synchronized(ipv6ChannelCacheLock) {
                val it = ipv6ChannelCache.entries.iterator()
                while (it.hasNext()) { it.next().value.closeQuietly(); it.remove() }
            }
        } catch (_: Exception) {}

        try {
            synchronized(tcbCacheLock) {
                val it = tcbCache.entries.iterator()
                while (it.hasNext()) { it.next().value.close(); it.remove() }
            }
        } catch (_: Exception) {}

        try {
            synchronized(ipv6TcbCacheLock) {
                val it = ipv6TcbCache.entries.iterator()
                while (it.hasNext()) { it.next().value.close(); it.remove() }
            }
        } catch (_: Exception) {}

        try { udpSelector?.close() } catch (_: Exception) {}
        try { tcpSelector?.close() } catch (_: Exception) {}
        try { ipv6UdpSelector?.close() } catch (_: Exception) {}
        try { peerSelector?.close() } catch (_: Exception) {}
        udpSelector = null; tcpSelector = null; ipv6UdpSelector = null; peerSelector = null

        try {
            wifiLock?.release()
            wifiLock = null
            debugLog("ðŸ›‘ ENGINE", "WiFi lock released")
        } catch (_: Exception) {}
        try {
            multicastLock?.release()
            multicastLock = null
            debugLog("ðŸ›‘ ENGINE", "Multicast lock released")
        } catch (_: Exception) {}

        try {
            jitterBuffer?.stop()
            jitterBuffer = null
            debugLog("ðŸ›‘ ENGINE", "Jitter buffer stopped")
        } catch (_: Exception) {}

        executor.shutdownNow()
        debugLog("ðŸ›‘ ENGINE", "Stopped")
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Peer tunnel diagnostics
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // GC MONITOR
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun gcMonitorLoop() {
        Thread.currentThread().name = "PeerLink-GC-Monitor"
        val runtime = Runtime.getRuntime()
        var lastFreeBytes = runtime.freeMemory()
        var lastCheckNs = System.nanoTime()

        debugLog("JITTER-GC", "ðŸ”¬ GC monitor started")

        while (isRunning.get()) {
            try {
                Thread.sleep(50)
                val nowNs = System.nanoTime()
                val nowFree = runtime.freeMemory()

                if (nowFree > lastFreeBytes + 200_000) {
                    JitterDiag.intervalGcEvents.incrementAndGet()
                    JitterDiag.totalGcEvents.incrementAndGet()
                    JitterDiag.lastGcTimeMs.set(System.currentTimeMillis())
                }

                lastFreeBytes = nowFree
                lastCheckNs = nowNs
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) { }
        }

        debugLog("JITTER-GC", "ðŸ”¬ GC monitor stopped")
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // JITTER DIAGNOSTIC REPORTER
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun jitterDiagnosticLoop() {
        Thread.currentThread().name = "PeerLink-Jitter-Diag"
        var reportCount = 0L

        debugLog("JITTER-DIAG", "ðŸ”¬ Diagnostic reporter started (reports every ${JitterDiag.REPORT_INTERVAL_MS}ms during tunneling)")

        while (isRunning.get()) {
            try {
                Thread.sleep(JitterDiag.REPORT_INTERVAL_MS)

                if (!JitterDiag.tunnelActive.get()) continue

                reportCount++
                val uptimeSec = (System.currentTimeMillis() - JitterDiag.tunnelStartTimeMs.get()) / 1000
                val intervalSec = JitterDiag.REPORT_INTERVAL_MS / 1000.0

                val peerRx = JitterDiag.intervalPeerRx.get()
                val peerTx = JitterDiag.intervalPeerTx.get()
                val tunWr = JitterDiag.intervalTunWrites.get()
                val tunReadAll = JitterDiag.intervalTunReadAll.get()
                val tunReadTunnel = JitterDiag.intervalTunReadTunnel.get()
                val tunReadPass = JitterDiag.intervalTunReadPass.get()
                val tunReadIntercept = JitterDiag.intervalTunReadIntercept.get()
                val tunReadDrop = JitterDiag.intervalTunReadDrop.get()
                val forcedUdpTunnel = JitterDiag.intervalForcedUdpTunnel.get()

                val peerRxRate = (peerRx / intervalSec).toInt()
                val peerTxRate = (peerTx / intervalSec).toInt()
                val tunReadAllRate = (tunReadAll / intervalSec).toInt()

                val qSamples = JitterDiag.queueSamples.get()
                val qAvgUs = if (qSamples > 0) JitterDiag.totalQueueLatencyUs.get() / qSamples else 0
                val qAvgMs = qAvgUs / 1000.0
                val qMaxMs = JitterDiag.maxQueueLatencyMs.get()

                val e2eSamples = JitterDiag.e2eSamples.get()
                val e2eAvgUs = if (e2eSamples > 0) JitterDiag.totalE2eLatencyUs.get() / e2eSamples else 0
                val e2eAvgMs = e2eAvgUs / 1000.0
                val e2eMaxMs = JitterDiag.maxE2eLatencyMs.get()

                val inGaps = JitterDiag.intervalInboundGaps.get()
                val outGaps = JitterDiag.intervalOutboundGaps.get()
                val qSpikes = JitterDiag.intervalQueueSpikes.get()
                val gcEvts = JitterDiag.intervalGcEvents.get()
                val sleeps = JitterDiag.intervalSleeps.get()

                val maxInGap = JitterDiag.maxInboundGapMs.get()
                val maxOutGap = JitterDiag.maxOutboundGapMs.get()
                val maxWr = JitterDiag.maxWriteMs.get()
                val maxSnd = JitterDiag.maxSendMs.get()
                val wrSpikes = JitterDiag.intervalWriteSpikes.get()
                val sndSpikes = JitterDiag.intervalSendSpikes.get()

                val runtime = Runtime.getRuntime()
                val heapUsedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val heapTotalMB = runtime.totalMemory() / (1024 * 1024)

                val queueDepth = networkToDeviceQueue.size

                // âš¡ NEW: Current dynamic keepalive interval
                val keepaliveMs = getCurrentKeepaliveIntervalNs() / 1_000_000

                val trueGap = JitterDiag.maxPeerGapMs.get()

                // âš¡ CHANGED: Added TRUE-GAP + KA (keepalive) interval to diagnostic output
                debugLog("JITTER-DIAG", "â•â•â• T+${uptimeSec}s #$reportCount â•â•â• " +
                    "T0:${tunReadAll}(${tunReadAllRate}/s) TUN:${tunReadTunnel} PASS:${tunReadPass} INT:${tunReadIntercept} DROP:${tunReadDrop} F-UDP:${forcedUdpTunnel}(${if (allGameUdpTunnelModeActive) "ON" else "OFF"}) HINT:${if (gameplayStartHintMs != 0L) "ON" else "OFF"} | " +
                    "IN:${peerRx}(${peerRxRate}/s) OUT:${peerTx}(${peerTxRate}/s) TUN-WR:${tunWr} | " +
                    "Q-AVG:${"%.1f".format(qAvgMs)}ms Q-MAX:${qMaxMs}ms Q-SPIKES:${qSpikes} Q-DEPTH:${queueDepth} | " +
                    "E2E-AVG:${"%.1f".format(e2eAvgMs)}ms E2E-MAX:${e2eMaxMs}ms | " +
                    "GAPS-IN:${inGaps}(max${maxInGap}ms) PEER-GAP:${trueGap}ms GAPS-OUT:${outGaps}(max${maxOutGap}ms) | " +
                    "GC:${gcEvts} WR-SLOW:${wrSpikes}(max${maxWr}ms) SEND-SLOW:${sndSpikes}(max${maxSnd}ms) | " +
                    "KA:${keepaliveMs}ms | " +
                    "SLEEPS:${sleeps} | HEAP:${heapUsedMB}MB/${heapTotalMB}MB | " +
                    "JB: ${jitterBuffer?.getStats() ?: "DISABLED"}")

                val nowNs = System.nanoTime()
                val staleMs = JitterDiag.HEARTBEAT_STALE_MS
                checkHeartbeat(nowNs, JitterDiag.heartbeatTunRead, "tunReadLoop", staleMs)
                checkHeartbeat(nowNs, JitterDiag.heartbeatTunWrite, "tunWriteLoop", staleMs)
                checkHeartbeat(nowNs, JitterDiag.heartbeatPeerRx, "peerReceiveLoop", staleMs)

                JitterDiag.resetInterval()

            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) { }
        }

        debugLog("JITTER-DIAG", "ðŸ”¬ Diagnostic reporter stopped")
    }

    private fun checkHeartbeat(nowNs: Long, heartbeat: AtomicLong, name: String, staleMs: Long) {
        val lastBeat = heartbeat.get()
        if (lastBeat > 0) {
            val ageMsVal = (nowNs - lastBeat) / 1_000_000
            if (ageMsVal > staleMs) {
                debugLog("JITTER-THREAD", "âš ï¸ $name heartbeat STALE: last seen ${ageMsVal}ms ago!")
            }
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // DNS SNIFFING
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private fun isStunDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        return STUN_DOMAIN_KEYWORDS.any { lower.contains(it) }
    }
    
    private fun handleOutgoingDnsQuery(data: ByteArray, payloadOffset: Int, payloadLength: Int) {
        if (payloadLength < 12) return
        if (payloadOffset + payloadLength > data.size) return
        
        try {
            val flags = ((data[payloadOffset + 2].toInt() and 0xFF) shl 8) or (data[payloadOffset + 3].toInt() and 0xFF)
            val isQuery = (flags and 0x8000) == 0
            
            if (!isQuery) return
            
            val transactionId = ((data[payloadOffset].toInt() and 0xFF) shl 8) or (data[payloadOffset + 1].toInt() and 0xFF)
            val domain = parseDnsDomainName(data, payloadOffset, payloadOffset + 12)
            
            if (isStunDomain(domain)) {
                pendingStunDnsQueries[transactionId] = domain
                DebugStats.dnsQueriesWatched.incrementAndGet()
                debugLog("ðŸ” DNS-QUERY", "Watching STUN DNS: '$domain' (txn=0x${transactionId.toString(16)})")
            }
        } catch (e: Exception) {
            debugLog("âš ï¸ DNS-QUERY", "Parse error: ${e.message}")
        }
    }
    
    private fun handleIncomingDnsResponse(payload: ByteArray) {
        if (payload.size < 12) return
        
        try {
            val transactionId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            
            val domain = pendingStunDnsQueries.remove(transactionId) ?: return
            
            DebugStats.dnsResponsesProcessed.incrementAndGet()
            debugLog("ðŸ“¥ DNS-RESPONSE", "Processing response for '$domain' (txn=0x${transactionId.toString(16)})")
            
            val (ipv4List, ipv6List) = extractIpsFromDnsResponse(payload)
            
            ipv4List.forEach { ip ->
                if (PacketParser.learnedStunServerIps.add(ip)) {
                    DebugStats.stunServersLearned.incrementAndGet()
                    debugLog("âœ… DNS-LEARNED", "STUN IPv4: $ip (from '$domain')")
                }
            }
            
            ipv6List.forEach { ip ->
                if (PacketParser.learnedStunServerIpv6s.add(ip)) {
                    DebugStats.stunServersLearned.incrementAndGet()
                    debugLog("âœ… DNS-LEARNED", "STUN IPv6: $ip (from '$domain')")
                }
            }
            
            if (ipv4List.isEmpty() && ipv6List.isEmpty()) {
                debugLog("âš ï¸ DNS-RESPONSE", "No IPs found in response for '$domain'")
            }
            
        } catch (e: Exception) {
            debugLog("âš ï¸ DNS-RESPONSE", "Parse error: ${e.message}")
        }
    }
    
    private fun parseDnsDomainName(payload: ByteArray, baseOffset: Int, startOffset: Int): String {
        val parts = mutableListOf<String>()
        var offset = startOffset
        var jumps = 0
        val maxJumps = 10
        
        while (offset < payload.size && jumps < maxJumps) {
            val len = payload[offset].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                if (offset + 1 >= payload.size) break
                val pointer = ((len and 0x3F) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                offset = baseOffset + pointer
                jumps++
                continue
            }
            offset++
            if (offset + len > payload.size) break
            parts.add(String(payload, offset, len, Charsets.US_ASCII))
            offset += len
        }
        
        return parts.joinToString(".")
    }
    
    private fun extractIpsFromDnsResponse(payload: ByteArray): Pair<List<String>, List<String>> {
        val ipv4List = mutableListOf<String>()
        val ipv6List = mutableListOf<String>()
        
        try {
            if (payload.size < 12) return Pair(ipv4List, ipv6List)
            
            val questions = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
            val answers = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
            
            var pos = 12
            
            repeat(questions) {
                while (pos < payload.size) {
                    val len = payload[pos].toInt() and 0xFF
                    if (len == 0) { pos++; break }
                    if ((len and 0xC0) == 0xC0) { pos += 2; break }
                    pos += len + 1
                }
                pos += 4
            }
            
            repeat(answers) {
                if (pos >= payload.size) return@repeat
                
                val nameLen = payload[pos].toInt() and 0xFF
                if ((nameLen and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < payload.size) {
                        val l = payload[pos].toInt() and 0xFF
                        if (l == 0) { pos++; break }
                        if ((l and 0xC0) == 0xC0) { pos += 2; break }
                        pos += l + 1
                    }
                }
                
                if (pos + 10 > payload.size) return@repeat
                
                val type = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                val rdLength = ((payload[pos + 8].toInt() and 0xFF) shl 8) or (payload[pos + 9].toInt() and 0xFF)
                pos += 10
                
                if (pos + rdLength > payload.size) return@repeat
                
                when {
                    type == 1 && rdLength == 4 -> {
                        val ip = "${payload[pos].toInt() and 0xFF}.${payload[pos+1].toInt() and 0xFF}.${payload[pos+2].toInt() and 0xFF}.${payload[pos+3].toInt() and 0xFF}"
                        ipv4List.add(ip)
                    }
                    type == 28 && rdLength == 16 -> {
                        val ipv6Str = formatIpv6Address(payload, pos)
                        ipv6List.add(ipv6Str)
                    }
                }
                
                pos += rdLength
            }
        } catch (e: Exception) {
            debugLog("âš ï¸ DNS-PARSE", "Error extracting IPs: ${e.message}")
        }
        
        return Pair(ipv4List, ipv6List)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // GAME PORT TRACKING
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    
    private fun updateGamePort(newPort: Int, remotePort: Int, source: String) {
        observeGameplayFlow(newPort, remotePort, source)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // âš¡ PERF: TUN READ LOOP â€” Non-blocking with poll + thread priority
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun tunReadLoop() {
        Thread.currentThread().name = "PeerLink-TUN-Read"

        val fd = vpnInterface.fileDescriptor
        val buffer = ByteArray(BUFFER_SIZE)
        val ipv6Buffer = ByteArray(MTU)
        val inputStream = FileInputStream(fd)

        debugLog("TUN-READ", "Loop started (blocking mode)")

        while (isRunning.get()) {
            try {
                JitterDiag.heartbeatTunRead.set(System.nanoTime())

                val length = inputStream.read(buffer)
                if (length <= 0) continue

                val t0Ns = System.nanoTime()
                val prevAnyNs = JitterDiag.lastTunReadAnyNs.getAndSet(t0Ns)
                JitterDiag.intervalTunReadAll.incrementAndGet()
                if (prevAnyNs > 0) {
                    val gapMs = (t0Ns - prevAnyNs) / 1_000_000
                    if (gapMs > JitterDiag.GAP_THRESHOLD_MS) {
                        logGapTriggeredSnapshot(
                            "🧨 GAP-T0",
                            gapMs,
                            outboundSeqCounter.get(),
                            0,
                            "len=$length"
                        )
                    }
                }

                val version = (buffer[0].toInt() and 0xF0) ushr 4
                
                when (version) {
                    4 -> {
                        val parsed = PacketParser.parse(buffer, length)
                        if (!parsed.isValid) continue
                        val packetLength = if (parsed.totalLength in 1..length) parsed.totalLength else length
                        handleTunAction(buffer, packetLength, parsed, t0Ns)
                    }
                    6 -> {
                        DebugStats.ipv6Packets.incrementAndGet()
                        System.arraycopy(buffer, 0, ipv6Buffer, 0, length)
                        handleIpv6Packet(ipv6Buffer, length)
                    }
                    else -> {
                        DebugStats.droppedPackets.incrementAndGet()
                    }
                }
                
                dumpStatsIfNeeded()

            } catch (e: ErrnoException) {
                if (isRunning.get() && e.errno != OsConstants.EAGAIN) {
                    debugLog("âŒ TUN-READ", "Error: ${e.message}")
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    debugLog("âŒ TUN-READ", "Error: ${e.message}")
                }
            }
        }

        debugLog("ðŸ”„ TUN-READ", "Loop ended")
    }

    private fun maybeDetectGameIpBytes(sourceIpBytes: ByteArray) {
        if (detectedGameIp != null) return
        if (sourceIpBytes[0] == VPN_ADDRESS_BYTES[0] && sourceIpBytes[1] == VPN_ADDRESS_BYTES[1] &&
            sourceIpBytes[2] == VPN_ADDRESS_BYTES[2] && sourceIpBytes[3] == VPN_ADDRESS_BYTES[3]) return
        if ((sourceIpBytes[0].toInt() and 255) == 127) return
        if (sourceIpBytes[0] == 0.toByte() && sourceIpBytes[1] == 0.toByte() && 
            sourceIpBytes[2] == 0.toByte() && sourceIpBytes[3] == 0.toByte()) return
        
        val sourceIp = "${sourceIpBytes[0].toInt() and 255}.${sourceIpBytes[1].toInt() and 255}.${sourceIpBytes[2].toInt() and 255}.${sourceIpBytes[3].toInt() and 255}"
        detectedGameIp = sourceIp
        detectedGameIpBytes = sourceIpBytes.copyOf()
        debugLog("ðŸŽ® GAME-IP", "Detected game IPv4 source: $sourceIp")
    }

    private fun handleTunAction(buffer: ByteArray, length: Int, parsed: PacketParser.ParsedPacket, t0Ns: Long) {
        maybeDetectGameIpBytes(parsed.sourceIpBytes)

        if (parsed.protocol == PacketParser.PROTOCOL_UDP && parsed.destPort == 53) {
            handleOutgoingDnsQuery(buffer, parsed.udpPayloadOffset, parsed.udpPayloadLength)
        }

        if (bridgeMode) {
            if (parsed.protocol == PacketParser.PROTOCOL_UDP) {
                DebugStats.passthroughUdp.incrementAndGet()
            } else if (parsed.protocol == PacketParser.PROTOCOL_TCP) {
                DebugStats.passthroughTcp.incrementAndGet()
            }
            handlePassthrough(buffer, length, parsed)
            return
        }

        val originalAction = PacketParser.getPacketAction(parsed)
        val nowMsForMode = System.currentTimeMillis()
        if (parsed.protocol == PacketParser.PROTOCOL_UDP && originalAction == PacketParser.PacketAction.TUNNEL) {
            noteGameplayHint(nowMsForMode)
        }
        if (parsed.protocol == PacketParser.PROTOCOL_UDP && gameplayStartHintMs != 0L && !shouldExcludeFromForceTunnel(parsed)) {
            noteRawGameplayUdpForForceMode(nowMsForMode)
        }
        val forcedTunnel = originalAction == PacketParser.PacketAction.PASSTHROUGH &&
            parsed.protocol == PacketParser.PROTOCOL_UDP &&
            allGameUdpTunnelModeActive &&
            !shouldExcludeFromForceTunnel(parsed)
        val action = if (forcedTunnel) PacketParser.PacketAction.TUNNEL else originalAction

        logUdpPacketMeta(parsed, length, action, forcedTunnel)

        when (action) {
            PacketParser.PacketAction.PASSTHROUGH -> {
                JitterDiag.intervalTunReadPass.incrementAndGet()
                if (parsed.protocol == PacketParser.PROTOCOL_UDP) {
                    DebugStats.passthroughUdp.incrementAndGet()
                } else if (parsed.protocol == PacketParser.PROTOCOL_TCP) {
                    DebugStats.passthroughTcp.incrementAndGet()
                }
                handlePassthrough(buffer, length, parsed)
            }
            PacketParser.PacketAction.TUNNEL -> {
                JitterDiag.intervalTunReadTunnel.incrementAndGet()
                if (forcedTunnel) {
                    JitterDiag.intervalForcedUdpTunnel.incrementAndGet()
                }
                seenTunnelDestinations.add(parsed.destIp)

                val nowNs = System.nanoTime()
                val prevNs = JitterDiag.lastTunReadTunnelNs.getAndSet(nowNs)

                if (prevNs > 0) {
                    val gapMs = (nowNs - prevNs) / 1_000_000
                    if (gapMs > JitterDiag.GAP_THRESHOLD_MS) {
                        JitterDiag.intervalOutboundGaps.incrementAndGet()
                        JitterDiag.updateMaxAtomic(JitterDiag.maxOutboundGapMs, gapMs)
                        logGapTriggeredSnapshot(
                            "🧨 GAP-TX",
                            gapMs,
                            outboundSeqCounter.get() + 1,
                            0,
                            "src=${parsed.sourceIp}:${parsed.sourcePort} dest=${parsed.destIp}:${parsed.destPort}"
                        )
                    }
                }

                if (!JitterDiag.tunnelActive.get()) {
                    JitterDiag.tunnelActive.set(true)
                    JitterDiag.tunnelStartTimeMs.set(System.currentTimeMillis())
                    if (gameTrafficStartTime == 0L) {
                        gameTrafficStartTime = System.currentTimeMillis()
                        debugLog("ENGINE", "[SESSION   ] Game traffic detected — aggressive keepalive active")

                        val prefs = context.getSharedPreferences("godmode_prefs", android.content.Context.MODE_PRIVATE)
                        val gameplayCallBlockEnabled = prefs.getBoolean("apex_call_block_gameplay", false)
                        if (gameplayCallBlockEnabled && !CallMonitorService.isRunning.get()) {
                            CallMonitorService.start(context, gameplayMode = true)
                            debugLog("ENGINE", "[CALL-BLOCK] Gameplay call blocker activated at game traffic start")
                        }
                    }
                    debugLog("ENGINE", "[SESSION   ] Tunnel now active — diagnostics reporting starts")
                }

                handleTunnelAction(
                    buffer,
                    length,
                    parsed,
                    t0Ns = t0Ns,
                    trackGameplayPort = !forcedTunnel && originalAction == PacketParser.PacketAction.TUNNEL,
                    originalDestIsFabricated = originalAction == PacketParser.PacketAction.TUNNEL,
                    forcedTunnel = forcedTunnel
                )
            }
            PacketParser.PacketAction.INTERCEPT -> {
                JitterDiag.intervalTunReadIntercept.incrementAndGet()
                seenStunServers.add("${parsed.destIp}:${parsed.destPort}")
                DebugStats.stunIntercepted.incrementAndGet()
                val isLearned = PacketParser.learnedStunServerIps.contains(parsed.destIp)
                debugLog("ðŸŽ¯ STUN-IPv4", "Intercepting ${parsed.sourceIp}:${parsed.sourcePort} â†’ ${parsed.destIp}:${parsed.destPort} (learned=$isLearned)")
                handleInterceptAction(buffer, length, parsed)
            }
            PacketParser.PacketAction.DROP -> {
                JitterDiag.intervalTunReadDrop.incrementAndGet()
                DebugStats.droppedPackets.incrementAndGet()
            }
        }
    }

    private fun handlePassthrough(buffer: ByteArray, length: Int, parsed: PacketParser.ParsedPacket) {
        PassthroughRecorder.recordTx(buffer, length)
        if (parsed.protocol == PacketParser.PROTOCOL_UDP) {
            val qp = udpTcpPacketPool.acquire()
            qp.copyFrom(buffer, length, parsed)
            deviceToNetworkUdpQueue.offer(qp)
            AppState.passedThrough.incrementAndGet()
        } else if (parsed.protocol == PacketParser.PROTOCOL_TCP) {
            val qp = udpTcpPacketPool.acquire()
            qp.copyFrom(buffer, length, parsed)
            deviceToNetworkTcpQueue.offer(qp)
            AppState.passedThrough.incrementAndGet()
        }
    }

    private fun handleTunnelAction(
        buffer: ByteArray,
        length: Int,
        parsed: PacketParser.ParsedPacket,
        t0Ns: Long,
        trackGameplayPort: Boolean = true,
        originalDestIsFabricated: Boolean = false,
        forcedTunnel: Boolean = false
    ) {
        if (trackGameplayPort && parsed.protocol == PacketParser.PROTOCOL_UDP) {
            updateGamePort(parsed.sourcePort, parsed.destPort, "IPv4-TUNNEL-OUT")
        }
        val seq = outboundSeqCounter.incrementAndGet()
        val s1Ns = System.nanoTime()
        val flowHash = computeFlowHash(parsed)
        packetTraceLog("📤 S1") {
            "seq=$seq flow=${flowHash.toUInt().toString(16)} len=$length src=${parsed.sourceIp}:${parsed.sourcePort} dest=${parsed.destIp}:${parsed.destPort} t0ToS1Us=${if (t0Ns > 0) ((s1Ns - t0Ns) / 1_000) else -1} bind=$boundNetworkLabel"
        }
        val tunnelFlags = (if (originalDestIsFabricated) TUNNEL_FLAG_FABRICATED_FLOW else 0) or (if (forcedTunnel) TUNNEL_FLAG_FORCED_UDP else 0)
        handleTunnel(buffer, length, seq, t0Ns, s1Ns, flowHash, tunnelFlags)
        if (parsed.protocol == PacketParser.PROTOCOL_UDP) {
            PrimeGameplayTracker.noteOutboundPeerUdp()
        }
        AppState.tunneled.incrementAndGet()
    }

    private fun handleInterceptAction(buffer: ByteArray, length: Int, parsed: PacketParser.ParsedPacket) {
        updateCachedIpBytes()
        val fabricatedIp = cachedMyFabricatedIp
        
        val response = StunFabricator.fabricateStunResponse(
            originalPacket = parsed,
            rawData = buffer,
            rawDataLength = length,
            fabricatedIp = fabricatedIp,
            fabricatedPort = parsed.sourcePort,
            vpnAddress = VPN_ADDRESS
        )
        
        if (response != null) {
            DebugStats.stunResponsesSent.incrementAndGet()
            offerToDevice(response, response.size, "STUN-v4")
            AppState.passedThrough.incrementAndGet()
        } else {
            debugLog("âŒ STUN-IPv4", "Failed to fabricate response!")
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PERF: TUNNEL SEND — cached peer address + TOS + keepalive
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Enqueue a packet for transmission over the peer channel.
     *
     * A sequence number can be provided for high level instrumentation. When
     * non‑zero, the send thread will include this sequence in its logs. For
     * control or ancillary traffic such as keepalives, pass the default seq (0)
     * so the packet will not be instrumented.
     */
    private fun handleTunnel(packetBytes: ByteArray, length: Int, seq: Long = 0L, t0Ns: Long = 0L, s1Ns: Long = 0L, flowHash: Int = 0, tunnelFlags: Int = 0) {
        // ⚡ ASYNC: Enqueue for dedicated tunnel send thread (eliminates SEND-SLOW stalls on TUN-read thread)
        val pkt = packetPool.acquire()
        if (pkt.data.size < length) {
            pkt.data = ByteArray(length)
        }
        System.arraycopy(packetBytes, 0, pkt.data, 0, length)
        pkt.length = length
        pkt.seq = seq
        pkt.t0Ns = t0Ns
        pkt.s1Ns = s1Ns
        pkt.flowHash = flowHash
        pkt.tunnelFlags = tunnelFlags
        pkt.enqueuedAtNs = System.nanoTime()
        pkt.source = "TUNNEL-OUT"
        pkt.receivedAtNs = 0L

        if (!tunnelSendQueue.offer(pkt)) {
            val dropped = tunnelSendQueue.poll()
            if (dropped != null) packetPool.release(dropped)
            if (!tunnelSendQueue.offer(pkt)) {
                packetPool.release(pkt)
                DebugStats.droppedPackets.incrementAndGet()
            } else {
                DebugStats.droppedPackets.incrementAndGet()
            }
        }
    }

    /**
     * âš¡ Dedicated tunnel send thread.
     * Decouples TUN-read from WiFi send to eliminate SEND-SLOW blocking.
     */
    private fun tunnelSendLoop() {
        Thread.currentThread().name = "PeerLink-Tunnel-Send"

        debugLog("TUNNEL-SEND", "Async send thread started")

        while (isRunning.get()) {
            var pkt: PooledPacket? = null
            try {
                pkt = tunnelSendQueue.poll(20, TimeUnit.MILLISECONDS)
                if (pkt == null) continue

                val ch = peerChannel
                if (ch == null || !ch.isOpen) continue

                val peerIp = AppState.peerIp.get() ?: continue

                var addr = cachedPeerSocketAddress
                if (addr == null || addr.address != peerIp || addr.port != TUNNEL_PORT) {
                    addr = InetSocketAddress(peerIp, TUNNEL_PORT)
                    cachedPeerSocketAddress = addr
                }

                val sendStartNs = System.nanoTime()

                tunnelSendByteBuffer.clear()
                if (pkt.seq > 0) {
                    writeTunnelDiagHeader(tunnelSendByteBuffer, pkt, sendStartNs)
                }
                tunnelSendByteBuffer.put(pkt.data, 0, pkt.length)
                tunnelSendByteBuffer.flip()
                
                val sent = ch.send(tunnelSendByteBuffer, addr)

                val sendDoneNs = System.nanoTime()
                JitterDiag.lastPeerTxNs.set(sendDoneNs)
                lastKeepaliveSentNs.set(sendDoneNs)

                if (sent > 0) {
                    DebugStats.tunnelOutPackets.incrementAndGet()
                    DebugStats.tunnelOutBytes.addAndGet(pkt.length.toLong())
                    JitterDiag.intervalPeerTx.incrementAndGet()
                    if (pkt.seq > 0) {
                        val queueUs = (sendStartNs - pkt.enqueuedAtNs) / 1_000
                        val sendUs = (sendDoneNs - sendStartNs) / 1_000
                        packetTraceLog("📤 S2") {
                            "seq=${pkt.seq} flow=${pkt.flowHash.toUInt().toString(16)} flags=${pkt.tunnelFlags} payload=${pkt.length} frame=$sent qUs=$queueUs sendUs=$sendUs t0ToS2Us=${if (pkt.t0Ns > 0) ((sendDoneNs - pkt.t0Ns) / 1_000) else -1} s1ToS2Us=${if (pkt.s1Ns > 0) ((sendDoneNs - pkt.s1Ns) / 1_000) else -1} local=$boundSocketLocalAddress peer=${addr.address.hostAddress}:${addr.port} bind=$boundNetworkLabel"
                        }
                    }
                } else {
                    DebugStats.droppedPackets.incrementAndGet()
                }

                val sendMs = (sendDoneNs - sendStartNs) / 1_000_000
                if (sendMs > 5) {
                    JitterDiag.updateMaxAtomic(JitterDiag.maxSendMs, sendMs)
                    JitterDiag.intervalSendSpikes.incrementAndGet()
                }

                
            } catch (e: Exception) {
                if (isRunning.get()) {
                    debugLog("âŒ TUNNEL-SEND", "Error: ${e.message}")
                }
            } finally {
                if (pkt != null) packetPool.release(pkt)
            }
        }

        debugLog("âš¡ TUNNEL-SEND", "Async send thread stopped")
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // IPv6 HANDLING
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun maybeDetectGameIpv6(srcAddrBytes: ByteArray) {
        if (detectedGameIpv6 != null) return
        if (srcAddrBytes.contentEquals(VPN_ADDRESS_IPV6_BYTES)) return
        if (srcAddrBytes.all { it == 0.toByte() }) return
        val isLoopback = srcAddrBytes.take(15).all { it == 0.toByte() } && srcAddrBytes[15] == 1.toByte()
        if (isLoopback) return
        if ((srcAddrBytes[0].toInt() and 0xFF) == 0xFE && (srcAddrBytes[1].toInt() and 0xC0) == 0x80) return
        
        detectedGameIpv6 = formatIpv6Address(srcAddrBytes, 0)
        detectedGameIpv6Bytes = srcAddrBytes.copyOf()
        debugLog("ðŸŽ® GAME-IPv6", "Detected game IPv6 source: $detectedGameIpv6")
    }

    private fun handleIpv6Packet(buffer: ByteArray, length: Int) {
        if (length < 40) {
            return
        }
        
        val srcAddr = ByteArray(16)
        System.arraycopy(buffer, 8, srcAddr, 0, 16)
        maybeDetectGameIpv6(srcAddr)
        
        val nextHeader = buffer[6].toInt() and 255
        
        if (nextHeader == 17 && length >= 48) {
            val destPort = ((buffer[42].toInt() and 255) shl 8) or (buffer[43].toInt() and 255)
            if (destPort == 53) {
                handleOutgoingDnsQueryIpv6(buffer, length)
            }
        }

        if (bridgeMode) {
            forwardIpv6Packet(buffer, length)
            return
        }
        
        if (shouldTunnelIpv6(buffer)) {
            if (nextHeader == 17 && length >= 44) {
                val srcPort = ((buffer[40].toInt() and 255) shl 8) or (buffer[41].toInt() and 255)
                val dstPort = ((buffer[42].toInt() and 255) shl 8) or (buffer[43].toInt() and 255)
                updateGamePort(srcPort, dstPort, "IPv6-TUNNEL-OUT")
            }
            
            val nowNs = System.nanoTime()
            val prevNs = JitterDiag.lastTunReadTunnelNs.getAndSet(nowNs)
            JitterDiag.intervalTunReadTunnel.incrementAndGet()
            if (prevNs > 0) {
                val gapMs = (nowNs - prevNs) / 1_000_000
                if (gapMs > JitterDiag.GAP_THRESHOLD_MS) {
                    JitterDiag.intervalOutboundGaps.incrementAndGet()
                    JitterDiag.updateMaxAtomic(JitterDiag.maxOutboundGapMs, gapMs)
                }
            }
            if (!JitterDiag.tunnelActive.get()) {
                JitterDiag.tunnelActive.set(true)
                JitterDiag.tunnelStartTimeMs.set(System.currentTimeMillis())
                if (gameTrafficStartTime == 0L) {
                    gameTrafficStartTime = System.currentTimeMillis()
                    debugLog("ENGINE", "[SESSION   ] Game traffic detected (IPv6) — aggressive keepalive active")
                }
                debugLog("ENGINE", "[SESSION   ] Tunnel now active (IPv6) — diagnostics reporting starts")
            }

            DebugStats.ipv6TunnelOut.incrementAndGet()
            handleIpv6Tunnel(buffer, length)
            if (nextHeader == 17) {
                PrimeGameplayTracker.noteOutboundPeerUdp()
            }
            AppState.tunneled.incrementAndGet()
            return
        }
        
        if (AppState.isPaired.get() && shouldInterceptIpv6Stun(buffer, length)) {
            updateCachedIpBytes()
            val fabricatedIp = cachedMyFabricatedIp
            val srcPort = ((buffer[40].toInt() and 255) shl 8) or (buffer[41].toInt() and 255)
            
            DebugStats.ipv6StunIntercepted.incrementAndGet()
            
            val response = StunFabricator.fabricateIpv6StunResponse(
                ipv6Packet = buffer,
                length = length,
                fabricatedIpv4 = fabricatedIp,
                fabricatedPort = srcPort
            )
            
            if (response != null) {
                DebugStats.stunResponsesSent.incrementAndGet()
                offerToDevice(response, response.size, "STUN-v6")
                AppState.passedThrough.incrementAndGet()
                return
            }
        }
        
        forwardIpv6Packet(buffer, length)
    }
    
    private fun handleOutgoingDnsQueryIpv6(buffer: ByteArray, length: Int) {
        if (length < 48 + 12) return
        
        try {
            val udpPayloadOffset = 48
            val udpLength = ((buffer[44].toInt() and 255) shl 8) or (buffer[45].toInt() and 255)
            val dnsPayloadLength = udpLength - 8
            
            if (dnsPayloadLength < 12) return
            if (udpPayloadOffset + dnsPayloadLength > length) return
            
            val flags = ((buffer[udpPayloadOffset + 2].toInt() and 0xFF) shl 8) or (buffer[udpPayloadOffset + 3].toInt() and 0xFF)
            val isQuery = (flags and 0x8000) == 0
            if (!isQuery) return
            
            val transactionId = ((buffer[udpPayloadOffset].toInt() and 0xFF) shl 8) or (buffer[udpPayloadOffset + 1].toInt() and 0xFF)
            val domain = parseDnsDomainName(buffer, udpPayloadOffset, udpPayloadOffset + 12)
            
            if (isStunDomain(domain)) {
                pendingStunDnsQueries[transactionId] = domain
                DebugStats.dnsQueriesWatched.incrementAndGet()
                debugLog("ðŸ” DNS-QUERY-IPv6", "Watching STUN DNS: '$domain' (txn=0x${transactionId.toString(16)})")
            }
        } catch (e: Exception) {
            debugLog("âš ï¸ DNS-QUERY-IPv6", "Parse error: ${e.message}")
        }
    }
    
    private fun shouldInterceptIpv6Stun(buffer: ByteArray, length: Int): Boolean {
        if (length < 48) return false
        
        val destAddr = ByteArray(16)
        System.arraycopy(buffer, 24, destAddr, 0, 16)
        val destIpStr = formatIpv6Address(destAddr, 0)
        
        val destPort = ((buffer[42].toInt() and 255) shl 8) or (buffer[43].toInt() and 255)
        
        val isLearnedStunServer = PacketParser.learnedStunServerIpv6s.contains(destIpStr)
        val hasStunMagic = StunFabricator.isIpv6Stun(buffer, length)
        
        if (isLearnedStunServer) {
            return destPort in PacketParser.PORT_STUN_START..PacketParser.PORT_STUN_END || hasStunMagic
        }
        
        if (!PacketParser.hasLearnedStunServers() && hasStunMagic) {
            return destPort in PacketParser.PORT_STUN_START..PacketParser.PORT_STUN_END
        }
        
        return false
    }

    private fun shouldTunnelIpv6(buffer: ByteArray): Boolean {
        val destAddrBytes = ByteArray(16)
        System.arraycopy(buffer, 24, destAddrBytes, 0, 16)
        
        updateCachedIpBytes()
        if (isIpv4MappedIpv6MatchingPeerFabricatedIp(destAddrBytes)) return true
        
        val peerIpAddr = AppState.peerIp.get() ?: return false
        val peerBytes = peerIpAddr.address
        
        return when (peerBytes.size) {
            4 -> isIpv4MappedIpv6MatchingPeer(destAddrBytes, peerBytes)
            16 -> destAddrBytes.contentEquals(peerBytes)
            else -> false
        }
    }

    private fun isIpv4MappedIpv6MatchingPeerFabricatedIp(ipv6Bytes: ByteArray): Boolean {
        for (i in 0..9) { if (ipv6Bytes[i] != 0.toByte()) return false }
        if ((ipv6Bytes[10].toInt() and 255) != 255) return false
        if ((ipv6Bytes[11].toInt() and 255) != 255) return false
        val peerFabBytes = cachedPeerFabricatedIpBytes
        return ipv6Bytes[12] == peerFabBytes[0] && ipv6Bytes[13] == peerFabBytes[1] &&
               ipv6Bytes[14] == peerFabBytes[2] && ipv6Bytes[15] == peerFabBytes[3]
    }

    private fun isIpv4MappedIpv6MatchingPeer(ipv6Bytes: ByteArray, peerIpv4Bytes: ByteArray): Boolean {
        for (i in 0..9) { if (ipv6Bytes[i] != 0.toByte()) return false }
        if ((ipv6Bytes[10].toInt() and 255) != 255) return false
        if ((ipv6Bytes[11].toInt() and 255) != 255) return false
        return ipv6Bytes[12] == peerIpv4Bytes[0] && ipv6Bytes[13] == peerIpv4Bytes[1] &&
               ipv6Bytes[14] == peerIpv4Bytes[2] && ipv6Bytes[15] == peerIpv4Bytes[3]
    }

    // PERF: IPv6 tunnel with keepalive tracking
    private fun handleIpv6Tunnel(buffer: ByteArray, length: Int) {
        // âš¡ ASYNC: Enqueue for dedicated tunnel send thread (eliminates SEND-SLOW stalls on TUN-read thread)
        val pkt = packetPool.acquire()
        if (pkt.data.size < length) {
            pkt.data = ByteArray(length)
        }
        System.arraycopy(buffer, 0, pkt.data, 0, length)
        pkt.length = length

        if (!tunnelSendQueue.offer(pkt)) {
            val dropped = tunnelSendQueue.poll()
            if (dropped != null) packetPool.release(dropped)
            if (!tunnelSendQueue.offer(pkt)) {
                packetPool.release(pkt)
                DebugStats.droppedPackets.incrementAndGet()
            } else {
                DebugStats.droppedPackets.incrementAndGet()
            }
        }
    }

    private fun forwardIpv6Packet(buffer: ByteArray, length: Int) {
        try {
            if (length < 40) { AppState.passedThrough.incrementAndGet(); return }
            val nextHeader = buffer[6].toInt() and 255
            when (nextHeader) {
                17 -> forwardIpv6Udp(buffer, length)
                6 -> forwardIpv6Tcp(buffer, length)
                else -> AppState.passedThrough.incrementAndGet()
            }
        } catch (e: Exception) {
            AppState.passedThrough.incrementAndGet()
        }
    }

    private fun forwardIpv6Udp(buffer: ByteArray, length: Int) {
        if (length < 48) { AppState.passedThrough.incrementAndGet(); return }
        
        try {
            val srcPort = ((buffer[40].toInt() and 255) shl 8) or (buffer[41].toInt() and 255)
            val destPort = ((buffer[42].toInt() and 255) shl 8) or (buffer[43].toInt() and 255)
            
            val destAddr = ByteArray(16)
            System.arraycopy(buffer, 24, destAddr, 0, 16)
            val srcAddr = ByteArray(16)
            System.arraycopy(buffer, 8, srcAddr, 0, 16)
            
            val payloadStart = 48
            val payloadLen = length - payloadStart
            if (payloadLen <= 0) { AppState.passedThrough.incrementAndGet(); return }
            
            val payload = ByteArray(payloadLen)
            System.arraycopy(buffer, payloadStart, payload, 0, payloadLen)
            
            val inet6Addr = Inet6Address.getByAddress(destAddr)
            val key = "${formatIpv6Address(srcAddr, 0)}:$srcPort->${formatIpv6Address(destAddr, 0)}:$destPort"
            
            var channel: DatagramChannel?
            synchronized(ipv6ChannelCacheLock) { channel = ipv6ChannelCache[key] }
            
            if (channel == null || !channel!!.isOpen) {
                val opened = UdpProxySockets.openConnected(InetSocketAddress(inet6Addr, destPort)) { socket ->
                    if (!protectDatagramSocket(socket)) false else {
                        bindPassthroughDatagramSocket(socket)
                        true
                    }
                }
                try {
                    val selector = ipv6UdpSelector ?: throw IllegalStateException("IPv6 selector stopped")
                    synchronized(ipv6ChannelCacheLock) {
                        check(isRunning.get()) { "UDP bridge stopped" }
                        selector.wakeup()
                        opened.register(selector, SelectionKey.OP_READ, Ipv6FlowRef(srcAddr, destAddr, srcPort, destPort))
                        ipv6ChannelCache[key] = opened
                    }
                    channel = opened
                } catch (e: Exception) {
                    opened.closeQuietly()
                    throw e
                }
            }

            channel?.write(ByteBuffer.wrap(payload))
            AppState.passedThrough.incrementAndGet()
            
        } catch (e: Exception) {
            AppState.passedThrough.incrementAndGet()
        }
    }

    private fun ipv6UdpInputLoop() {
        Thread.currentThread().name = "PeerLink-IPv6-UDP-In"
        val selector = ipv6UdpSelector ?: return
        val readBuffer = ByteBuffer.allocate(MTU)
        
        while (isRunning.get()) {
            try {
                val ready = selector.select(5)
                if (ready <= 0) continue
                
                val keys = selector.selectedKeys()
                val it = keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    if (!key.isValid || !key.isReadable) continue
                    
                    val channel = key.channel() as? DatagramChannel
                    if (channel == null || !channel.isOpen) { key.cancel(); continue }
                    val ref = key.attachment() as? Ipv6FlowRef
                    if (ref == null) { key.cancel(); continue }
                    
                    readBuffer.clear()
                    val len = try { channel.read(readBuffer) }
                        catch (_: PortUnreachableException) { continue } // transient ICMP; preserve the local UDP port
                        catch (e: Exception) { key.cancel(); channel.closeQuietly(); continue }
                    
                    if (len > 0) {
                        readBuffer.flip()
                        val payload = ByteArray(len)
                        readBuffer.get(payload)
                        
                        if (ref.dstPort == 53) { handleIncomingDnsResponse(payload) }
                        
                        val pkt = buildIpv6UdpPacket(ref.dstAddr, ref.srcAddr, ref.dstPort, ref.srcPort, payload)
                        PassthroughRecorder.recordRx(pkt, pkt.size)
                        offerToDevice(pkt, pkt.size, "IPv6-UDP-PASS")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) debugLog("âŒ IPv6-UDP-IN", "Error: ${e.message}")
            }
        }
    }

    private fun forwardIpv6Tcp(buffer: ByteArray, length: Int) {
        if (length < 60) { AppState.passedThrough.incrementAndGet(); return }
        
        try {
            val srcAddr = ByteArray(16)
            val destAddr = ByteArray(16)
            System.arraycopy(buffer, 8, srcAddr, 0, 16)
            System.arraycopy(buffer, 24, destAddr, 0, 16)
            
            val srcIp = formatIpv6Address(srcAddr, 0)
            val destIp = formatIpv6Address(destAddr, 0)
            
            val tcpOffset = 40
            val srcPort = ((buffer[tcpOffset].toInt() and 255) shl 8) or (buffer[tcpOffset + 1].toInt() and 255)
            val destPort = ((buffer[tcpOffset + 2].toInt() and 255) shl 8) or (buffer[tcpOffset + 3].toInt() and 255)
            
            val tcp = parseIpv6TcpHeader(buffer, tcpOffset) ?: return
            
            val key = "$destIp:$destPort:$srcPort"
            val payloadOffset = tcpOffset + tcp.headerLength
            val payloadSize = maxOf(0, length - payloadOffset)
            
            val tcb = getIpv6Tcb(key)
            if (tcb == null) {
                initializeIpv6TcpConnection(key, destIp, destPort, srcIp, srcPort, tcp, destAddr, srcAddr)
                return
            }
            
            handleIpv6TcpForExistingTcb(tcb, tcp, buffer, payloadOffset, payloadSize)
            
        } catch (e: Exception) {
            AppState.passedThrough.incrementAndGet()
        }
    }

    private fun parseIpv6TcpHeader(p: ByteArray, off: Int): ParsedTcpHeader? {
        if (p.size < off + 20) return null
        val hl = ((p[off + 12].toInt() and 0xF0) shr 4) * 4
        if (hl < 20 || p.size < off + hl) return null
        return ParsedTcpHeader(readU16(p, off), readU16(p, off + 2), readU32(p, off + 4), readU32(p, off + 8), hl, p[off + 13].toInt() and 0xFF)
    }

    private fun getIpv6Tcb(key: String): TcpTcb? { synchronized(ipv6TcbCacheLock) { return ipv6TcbCache[key] } }
    private fun putIpv6Tcb(key: String, tcb: TcpTcb) { synchronized(ipv6TcbCacheLock) { ipv6TcbCache[key] = tcb } }
    private fun closeIpv6Tcb(tcb: TcpTcb) { tcb.close(); synchronized(ipv6TcbCacheLock) { ipv6TcbCache.remove(tcb.ipAndPort) } }

    private fun initializeIpv6TcpConnection(key: String, remoteIp: String, remotePort: Int, localIp: String, localPort: Int, tcp: ParsedTcpHeader, remoteAddrBytes: ByteArray, localAddrBytes: ByteArray) {
        if (!tcp.isSYN()) {
            val rst = buildIpv6TcpPacket(remoteAddrBytes, localAddrBytes, remotePort, localPort, 0, tcp.sequenceNumber + 1, TCP_FLAG_RST, null, 0)
            offerToDevice(rst, rst.size, "IPv6-TCP-RST")
            return
        }
        val selector = tcpSelector ?: return
        var tcb: TcpTcb? = null
        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            if (!protectTcpSocket(channel.socket())) {
                channel.close()
                val rst = buildIpv6TcpPacket(remoteAddrBytes, localAddrBytes, remotePort, localPort, 0, tcp.sequenceNumber + 1, TCP_FLAG_RST, null, 0)
                offerToDevice(rst, rst.size, "IPv6-TCP-RST")
                return
            }
            bindPassthroughTcpSocket(channel.socket())
            val mySeq = random.nextInt(Short.MAX_VALUE + 1).toLong()
            tcb = TcpTcb(key, remoteIp, remotePort, localIp, localPort, channel, mySeq, tcp.sequenceNumber + 1, tcp.acknowledgementNumber)
            tcb.isIpv6 = true; tcb.remoteAddrBytes = remoteAddrBytes; tcb.localAddrBytes = localAddrBytes
            putIpv6Tcb(key, tcb)
            val inet6Addr = Inet6Address.getByAddress(remoteAddrBytes)
            channel.connect(InetSocketAddress(inet6Addr, remotePort))
            if (channel.finishConnect()) {
                tcb.status = TcpTcb.TcbStatus.SYN_RECEIVED
                val pkt = buildIpv6TcpPacket(remoteAddrBytes, localAddrBytes, remotePort, localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_SYN or TCP_FLAG_ACK, null, 0)
                offerToDevice(pkt, pkt.size, "IPv6-TCP-SYNACK")
                tcb.mySequenceNum++
            } else {
                tcb.status = TcpTcb.TcbStatus.SYN_SENT
                val sk = channel.register(selector, SelectionKey.OP_CONNECT, tcb)
                tcb.selectionKey = sk
            }
        } catch (e: Exception) {
            if (tcb != null) closeIpv6Tcb(tcb)
            val rst = buildIpv6TcpPacket(remoteAddrBytes, localAddrBytes, remotePort, localPort, 0, tcp.sequenceNumber + 1, TCP_FLAG_RST, null, 0)
            offerToDevice(rst, rst.size, "IPv6-TCP-RST")
        }
    }

    private fun handleIpv6TcpForExistingTcb(tcb: TcpTcb, tcp: ParsedTcpHeader, data: ByteArray, payloadOffset: Int, payloadSize: Int) {
        if (tcp.isSYN()) {
            if (tcb.status == TcpTcb.TcbStatus.SYN_SENT) tcb.myAcknowledgementNum = tcp.sequenceNumber + 1
            else sendIpv6RstAndClose(tcb, 1)
            return
        }
        if (tcp.isRST()) { closeIpv6Tcb(tcb); return }
        if (tcp.isFIN()) { processIpv6Fin(tcb, tcp); return }
        if (tcp.isACK()) {
            val pl = if (payloadSize > 0) ByteBuffer.wrap(data, payloadOffset, payloadSize) else null
            processIpv6Ack(tcb, tcp, pl, payloadSize)
        }
    }

    private fun processIpv6Fin(tcb: TcpTcb, tcp: ParsedTcpHeader) {
        tcb.myAcknowledgementNum = tcp.sequenceNumber + 1
        val pkt = if (tcb.waitingForNetworkData) {
            tcb.status = TcpTcb.TcbStatus.CLOSE_WAIT
            buildIpv6TcpPacket(tcb.remoteAddrBytes!!, tcb.localAddrBytes!!, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_ACK, null, 0)
        } else {
            tcb.status = TcpTcb.TcbStatus.LAST_ACK
            val p = buildIpv6TcpPacket(tcb.remoteAddrBytes!!, tcb.localAddrBytes!!, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_FIN or TCP_FLAG_ACK, null, 0)
            tcb.mySequenceNum++
            p
        }
        offerToDevice(pkt, pkt.size, "IPv6-TCP-FIN")
    }

    private fun processIpv6Ack(tcb: TcpTcb, tcp: ParsedTcpHeader, payload: ByteBuffer?, payloadSize: Int) {
        val selector = tcpSelector ?: return
        if (tcb.status == TcpTcb.TcbStatus.SYN_RECEIVED) {
            tcb.status = TcpTcb.TcbStatus.ESTABLISHED
            tcb.selectionKey = tcb.channel.register(selector, SelectionKey.OP_READ, tcb)
            tcb.waitingForNetworkData = true
        }
        if (payload != null && payloadSize > 0) {
            try { while (payload.hasRemaining()) tcb.channel.write(payload) }
            catch (e: Exception) { sendIpv6RstAndClose(tcb, payloadSize); return }
        }
        if (payloadSize > 0) { tcb.myAcknowledgementNum = tcp.sequenceNumber + payloadSize }
        val pkt = buildIpv6TcpPacket(tcb.remoteAddrBytes!!, tcb.localAddrBytes!!, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_ACK, null, 0)
        offerToDevice(pkt, pkt.size, "IPv6-TCP-ACK")
    }

    private fun sendIpv6RstAndClose(tcb: TcpTcb, ackInc: Int) {
        val pkt = buildIpv6TcpPacket(tcb.remoteAddrBytes!!, tcb.localAddrBytes!!, tcb.remotePort, tcb.localPort, 0, tcb.myAcknowledgementNum + ackInc, TCP_FLAG_RST, null, 0)
        offerToDevice(pkt, pkt.size, "IPv6-TCP-RST")
        closeIpv6Tcb(tcb)
    }

    private data class Ipv6FlowRef(val srcAddr: ByteArray, val dstAddr: ByteArray, val srcPort: Int, val dstPort: Int)

    private fun formatIpv6Address(bytes: ByteArray, offset: Int): String {
        val parts = mutableListOf<String>()
        for (i in 0..14 step 2) {
            val word = ((bytes[offset + i].toInt() and 255) shl 8) or (bytes[offset + i + 1].toInt() and 255)
            parts.add(word.toString(16))
        }
        return parts.joinToString(":")
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // âš¡ PERF: TUN WRITE LOOP â€” Non-blocking write + thread priority + batch drain
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun tunWriteLoop() {
        Thread.currentThread().name = "PeerLink-TUN-Write"

        val fd = vpnInterface.fileDescriptor
        val outputStream = if (!tunNonBlocking) FileOutputStream(fd) else null
        val maxBatch = if (bridgeMode) 8 else 64
        val batch = ArrayList<PooledPacket>(maxBatch)
        
        debugLog("JITTER-DIAG", "tunWriteLoop started (mode=${if (tunNonBlocking) "non-blocking" else "blocking"}, priority=default)")
        
        while (isRunning.get()) {
            try {
                JitterDiag.heartbeatTunWrite.set(System.nanoTime())

                batch.clear()

                val first = networkToDeviceQueue.pollWait(10)
                if (first != null) {
                    batch.add(first)
                    networkToDeviceQueue.drainTo(batch, maxBatch - 1)
                    if (batch.size > 1) {
                        JitterDiag.intervalBatched.addAndGet(batch.size.toLong())
                    }
                } else {
                    JitterDiag.intervalSleeps.incrementAndGet()
                    continue
                }

                for (pkt in batch) {
                    val dequeueNs = System.nanoTime()

                    val queueLatencyUs = (dequeueNs - pkt.enqueuedAtNs) / 1_000
                    val queueLatencyMs = queueLatencyUs / 1_000
                    JitterDiag.totalQueueLatencyUs.addAndGet(queueLatencyUs)
                    JitterDiag.queueSamples.incrementAndGet()
                    JitterDiag.updateMaxAtomic(JitterDiag.maxQueueLatencyMs, queueLatencyMs)

                    if (queueLatencyMs > JitterDiag.QUEUE_SPIKE_MS) {
                        JitterDiag.intervalQueueSpikes.incrementAndGet()
                    }

                    if (pkt.receivedAtNs > 0) {
                        val e2eUs = (dequeueNs - pkt.receivedAtNs) / 1_000
                        val e2eMs = e2eUs / 1_000
                        JitterDiag.totalE2eLatencyUs.addAndGet(e2eUs)
                        JitterDiag.e2eSamples.incrementAndGet()
                        JitterDiag.updateMaxAtomic(JitterDiag.maxE2eLatencyMs, e2eMs)
                    }

                    val writeStartNs = System.nanoTime()

                    if (tunNonBlocking) {
                        var written = false
                        try {
                            Os.write(fd, pkt.data, 0, pkt.length)
                            written = true
                        } catch (e: ErrnoException) {
                            if (e.errno == OsConstants.EAGAIN) {
                                for (retry in 0 until 50) {
                                    LockSupport.parkNanos(200_000L)
                                    try {
                                        Os.write(fd, pkt.data, 0, pkt.length)
                                        written = true
                                        break
                                    } catch (e2: ErrnoException) {
                                        if (e2.errno != OsConstants.EAGAIN) throw e2
                                    }
                                }
                            } else throw e
                        }
                        if (!written) {
                            DebugStats.droppedPackets.incrementAndGet()
                        }
                    } else {
                        outputStream!!.write(pkt.data, 0, pkt.length)
                    }

                    val writeEndNs = System.nanoTime()
                    val writeMs = (writeEndNs - writeStartNs) / 1_000_000
                    if (writeMs > 5) {
                        JitterDiag.updateMaxAtomic(JitterDiag.maxWriteMs, writeMs)
                        JitterDiag.intervalWriteSpikes.incrementAndGet()
                    }

                    JitterDiag.lastTunWriteNs.set(writeEndNs)
                    JitterDiag.intervalTunWrites.incrementAndGet()

                    // High level instrumentation: log R2 once the packet has been injected into the TUN
                    val seq = pkt.seq
                    if (seq > 0) {
                        packetTraceLog("📥 R2") {
                            "seq=$seq len=${pkt.length} qUs=$queueLatencyUs e2eUs=${if (pkt.receivedAtNs > 0) ((dequeueNs - pkt.receivedAtNs) / 1_000) else -1} r1ToR2Us=${if (pkt.receivedAtNs > 0) ((writeEndNs - pkt.receivedAtNs) / 1_000) else -1} source=${pkt.source}"
                        }
                        pkt.seq = 0L
                    }
                    packetPool.release(pkt)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    debugLog("âŒ TUN-WRITE", "Error: ${e.message}")
                }
            }
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // UDP I/O LOOPS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun udpOutputLoop() {
        Thread.currentThread().name = "PeerLink-UDP-Out"
        val selector = udpSelector ?: return
        while (isRunning.get()) {
            val qp = deviceToNetworkUdpQueue.pollWait(2) ?: continue
            try {
                JitterDiag.heartbeatUdpOut.set(System.nanoTime())
                val destIp = qp.destIp
                val destPort = qp.destPort
                val srcPort = qp.sourcePort
                val key = "${qp.sourceIp}:$srcPort->$destIp:$destPort"
                var channel = synchronized(udpChannelCacheLock) { udpChannelCache[key] }
                if (channel == null || !channel.isOpen) {
                    val opened = UdpProxySockets.openConnected(InetSocketAddress(InetAddress.getByName(destIp), destPort)) { socket ->
                        if (!protectDatagramSocket(socket)) false else {
                            bindPassthroughDatagramSocket(socket)
                            true
                        }
                    }
                    try {
                        val ref = UdpFlowRef(qp.destIpBytes.copyOf(), qp.sourceIpBytes.copyOf(), destPort, srcPort)
                        synchronized(udpChannelCacheLock) {
                            check(isRunning.get()) { "UDP bridge stopped" }
                            selector.wakeup()
                            opened.register(selector, SelectionKey.OP_READ, ref)
                            udpChannelCache[key] = opened
                        }
                        channel = opened
                    } catch (e: Exception) {
                        opened.closeQuietly()
                        throw e
                    }
                }
                val payloadOffset = qp.headerLength + 8
                val payloadLength = qp.totalLength - payloadOffset
                if (payloadLength > 0 && payloadOffset + payloadLength <= qp.length) {
                    channel?.write(ByteBuffer.wrap(qp.data, payloadOffset, payloadLength))
                }
            } catch (e: Exception) {
                if (isRunning.get()) debugLog("UDP-OUT", "Error: ${e.message}")
            } finally {
                // A send, bind, or registration failure must not leak the
                // pooled packet and create growing allocation/GC pressure.
                udpTcpPacketPool.release(qp)
            }
        }
    }

    private fun udpInputLoop() {
        Thread.currentThread().name = "PeerLink-UDP-In"
        val selector = udpSelector ?: return
        val readBuffer = ByteBuffer.allocate(MTU)
        while (isRunning.get()) {
            try {
                JitterDiag.heartbeatUdpIn.set(System.nanoTime())
                val ready = selector.select(5)
                if (ready <= 0) continue
                
                val keys = selector.selectedKeys()
                val it = keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    if (!key.isValid || !key.isReadable) continue
                    
                    val channel = key.channel() as? DatagramChannel
                    if (channel == null || !channel.isOpen) { key.cancel(); continue }
                    val ref = key.attachment() as? UdpFlowRef
                    if (ref == null) { key.cancel(); continue }
                    
                    readBuffer.clear()
                    val len = try { channel.read(readBuffer) }
                        catch (_: PortUnreachableException) { continue } // transient ICMP; preserve the local UDP port
                        catch (e: Exception) { key.cancel(); channel.closeQuietly(); continue }
                    
                    if (len > 0) {
                        readBuffer.flip()
                        val payload = ByteArray(len)
                        readBuffer.get(payload)
                        
                        if (ref.srcPort == 53) { handleIncomingDnsResponse(payload) }
                        
                        val pkt = buildUdpIpPacket(ref.srcIp, ref.dstIp, ref.srcPort, ref.dstPort, payload, len)
                        PassthroughRecorder.recordRx(pkt, pkt.size)
                        offerToDevice(pkt, pkt.size, "UDP-PASS")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) debugLog("âŒ UDP-IN", "${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
            }
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TCP I/O LOOPS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun processTcpOutputPacket(qp: QueuedUdpTcpPacket) {
        val ipHdrLen = qp.headerLength
        val tcp = parseTcpHeader(qp.data, ipHdrLen) ?: return
        val key = "${qp.destIp}:${qp.destPort}:${qp.sourcePort}"
        val payloadOffset = ipHdrLen + tcp.headerLength
        val payloadSize = maxOf(0, qp.totalLength - payloadOffset)
        val tcb = getTcb(key)
        if (tcb == null) { 
            initializeTcpConnection(key, qp.destIp, qp.destPort, qp.sourceIp, qp.sourcePort, tcp)
            return 
        }
        handleTcpForExistingTcb(tcb, tcp, qp.data, payloadOffset, payloadSize)
    }

    private fun handleTcpForExistingTcb(tcb: TcpTcb, tcp: ParsedTcpHeader, data: ByteArray, payloadOffset: Int, payloadSize: Int) {
        if (tcp.isSYN()) { processDuplicateSyn(tcb, tcp); return }
        if (tcp.isRST()) { closeTcb(tcb); return }
        if (tcp.isFIN()) { processFin(tcb, tcp); return }
        if (tcp.isACK()) {
            val pl = if (payloadSize > 0) ByteBuffer.wrap(data, payloadOffset, payloadSize) else null
            processAck(tcb, tcp, pl, payloadSize)
        }
    }

    private fun tcpOutputLoop() {
        Thread.currentThread().name = "PeerLink-TCP-Out"
        while (isRunning.get()) {
            val qp = deviceToNetworkTcpQueue.pollWait(2) ?: continue
            try {
                JitterDiag.heartbeatTcpOut.set(System.nanoTime())
                processTcpOutputPacket(qp)
            } catch (e: Exception) {
                if (isRunning.get()) debugLog("âŒ TCP-OUT", "Error: ${e.message}")
            } finally {
                udpTcpPacketPool.release(qp)
            }
        }
    }

    private fun tcpInputLoop() {
        Thread.currentThread().name = "PeerLink-TCP-In"
        val selector = tcpSelector ?: return
        val readBuffer = ByteBuffer.allocate(MTU)
        while (isRunning.get()) {
            try {
                JitterDiag.heartbeatTcpIn.set(System.nanoTime())
                if (selector.select(5) <= 0) continue
                processTcpSelectedKeys(selector, readBuffer)
            } catch (e: Exception) {
                if (isRunning.get()) debugLog("âŒ TCP-IN", "Error: ${e.message}")
            }
        }
    }

    private fun processTcpSelectedKeys(selector: Selector, readBuffer: ByteBuffer) {
        val it = selector.selectedKeys().iterator()
        while (it.hasNext()) {
            val key = it.next(); it.remove()
            if (!key.isValid) continue
            if (key.isConnectable) { processTcpConnect(key); continue }
            if (key.isReadable) processTcpRead(key, readBuffer)
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // âš¡ PERF: PEER RECEIVE LOOP â€” Selector-based + thread priority + DYNAMIC keepalive
    //         + peer packet handling + deduplication
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun shouldAcceptPeerAddress(address: InetAddress): Boolean {
        val peerLanIp = AppState.peerIp.get() ?: return false
        return address == peerLanIp
    }

    private fun peerReceiveLoop() {
        Thread.currentThread().name = "PeerLink-Peer-Rx"
        
        val selector = peerSelector ?: return
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        val tempArray = ByteArray(BUFFER_SIZE)

        debugLog("ðŸ”„ PEER-RX", "Loop started on port $TUNNEL_PORT (NIO non-blocking)")

        while (isRunning.get()) {
            try {
                JitterDiag.heartbeatPeerRx.set(System.nanoTime())
                
                // âš¡ CHANGED: Use dynamic keepalive interval instead of fixed KEEPALIVE_INTERVAL_NS
                val nowNs = System.nanoTime()
                val lastKeepalive = lastKeepaliveSentNs.get()
                val currentKeepaliveIntervalNs = getCurrentKeepaliveIntervalNs()
                if (lastKeepalive > 0 && (nowNs - lastKeepalive) > currentKeepaliveIntervalNs) {
                    val peerIp = AppState.peerIp.get()
                    val ch = peerChannel
                    if (peerIp != null && ch != null && ch.isOpen) {
                        try {
                            var addr = cachedPeerSocketAddress
                            if (addr == null || addr.address != peerIp) {
                                addr = InetSocketAddress(peerIp, TUNNEL_PORT)
                                cachedPeerSocketAddress = addr
                            }
                            keepaliveBuffer.clear()
                            keepaliveBuffer.put(0xFF.toByte())
                            keepaliveBuffer.flip()
                            ch.send(keepaliveBuffer, addr)
                            lastKeepaliveSentNs.set(nowNs)
                        } catch (_: Exception) {}
                    }
                }
                
                val selectTimeoutMs = maxOf(10L, minOf(50L, currentKeepaliveIntervalNs / 1_000_000L / 2))
                val ready = selector.select(selectTimeoutMs)
                if (ready <= 0) continue
                
                val keys = selector.selectedKeys()
                val keyIt = keys.iterator()
                while (keyIt.hasNext()) {
                    val key = keyIt.next()
                    keyIt.remove()
                    if (!key.isValid || !key.isReadable) continue
                    
                    val channel = key.channel() as? DatagramChannel ?: continue
                    
                    while (isRunning.get()) {
                        buffer.clear()
                        val addr = channel.receive(buffer) as? InetSocketAddress ?: break
                        buffer.flip()
                        
                        val rxNs = System.nanoTime()
                        val len = buffer.remaining()
                        if (len <= 0) continue
                        
                        // âš¡ Ignore keepalive packets (1 byte, value 0xFF)
                        if (len == 1) {
                            val singleByte = buffer.get(0)
                            if (singleByte == 0xFF.toByte()) continue
                        }
                        
                        DebugStats.tunnelInPackets.incrementAndGet()
                        DebugStats.tunnelInBytes.addAndGet(len.toLong())
                        
                        if (!shouldAcceptPeerAddress(addr.address)) {
                            DebugStats.tunnelInRejected.incrementAndGet()
                            continue
                        }

                        buffer.get(tempArray, 0, len)

                        val diagMeta = parseTunnelDiagMeta(tempArray, len)
                        val payloadOffset = diagMeta?.headerSize ?: 0
                        val payloadLength = diagMeta?.payloadLength ?: len
                        if (payloadOffset > 0) {
                            System.arraycopy(tempArray, payloadOffset, tempArray, 0, payloadLength)
                        }

                        val firstByte = tempArray[0].toInt() and 0xFF

                        val prevRxNs = JitterDiag.lastPeerRxNs.getAndSet(rxNs)
                        JitterDiag.intervalPeerRx.incrementAndGet()

                        var gapMs = 0L
                        if (prevRxNs > 0) {
                            gapMs = (rxNs - prevRxNs) / 1_000_000
                            JitterDiag.updateMaxAtomic(JitterDiag.maxPeerGapMs, gapMs)
                            if (gapMs > JitterDiag.GAP_THRESHOLD_MS) {
                                JitterDiag.intervalInboundGaps.incrementAndGet()
                                JitterDiag.updateMaxAtomic(JitterDiag.maxInboundGapMs, gapMs)
                            }
                        }

                        val inboundSeq = diagMeta?.seq ?: inboundSeqCounter.incrementAndGet()
                        val missing = if (diagMeta != null && lastReceivedRemoteSeq > 0 && diagMeta.seq > lastReceivedRemoteSeq) diagMeta.seq - lastReceivedRemoteSeq - 1 else 0L
                        if (diagMeta != null) {
                            val sameRemoteFlow =
                                lastRemoteDiagSendAttemptNs > 0L &&
                                lastRemoteDiagS1Ns > 0L &&
                                lastRemoteDiagSenderId == diagMeta.senderId &&
                                lastRemoteDiagFlowHash == diagMeta.flowHash
                            val remoteTunGapMs = if (sameRemoteFlow && diagMeta.t0Ns > 0L && lastRemoteDiagT0Ns > 0L && diagMeta.t0Ns >= lastRemoteDiagT0Ns) {
                                (diagMeta.t0Ns - lastRemoteDiagT0Ns) / 1_000_000
                            } else {
                                -1L
                            }
                            val remoteCaptureGapMs = if (sameRemoteFlow && diagMeta.s1Ns >= lastRemoteDiagS1Ns) {
                                (diagMeta.s1Ns - lastRemoteDiagS1Ns) / 1_000_000
                            } else {
                                -1L
                            }
                            val remoteSendGapMs = if (sameRemoteFlow && diagMeta.sendAttemptNs >= lastRemoteDiagSendAttemptNs) {
                                (diagMeta.sendAttemptNs - lastRemoteDiagSendAttemptNs) / 1_000_000
                            } else {
                                -1L
                            }
                            val currentTunToS1Us = if (diagMeta.t0Ns > 0L && diagMeta.s1Ns >= diagMeta.t0Ns) {
                                (diagMeta.s1Ns - diagMeta.t0Ns) / 1_000
                            } else {
                                -1L
                            }
                            val currentPreSendUs = if (diagMeta.sendAttemptNs >= diagMeta.s1Ns) {
                                (diagMeta.sendAttemptNs - diagMeta.s1Ns) / 1_000
                            } else {
                                -1L
                            }
                            val currentPostSendUs = if (rxNs >= diagMeta.sendAttemptNs) {
                                (rxNs - diagMeta.sendAttemptNs) / 1_000
                            } else {
                                -1L
                            }
                            val currentEndToEndUs = if (rxNs >= diagMeta.s1Ns) {
                                (rxNs - diagMeta.s1Ns) / 1_000
                            } else {
                                -1L
                            }
                            val likelySource = classifyRxGapSource(
                                sameRemoteFlow,
                                gapMs,
                                remoteTunGapMs,
                                remoteCaptureGapMs,
                                remoteSendGapMs,
                                currentTunToS1Us,
                                currentPreSendUs,
                                currentPostSendUs
                            )
                            if (gapMs > JitterDiag.GAP_THRESHOLD_MS || missing > 0) {
                                logGapTriggeredSnapshot(
                                    "🧨 GAP-RX",
                                    gapMs,
                                    diagMeta.seq,
                                    missing,
                                    "senderId=${diagMeta.senderId} flow=${diagMeta.flowHash.toUInt().toString(16)} from=${addr.address.hostAddress}:${addr.port} payload=$payloadLength sameFlow=$sameRemoteFlow remoteT0GapMs=$remoteTunGapMs remoteS1GapMs=$remoteCaptureGapMs remoteS2GapMs=$remoteSendGapMs t0ToS1Us=$currentTunToS1Us s1ToS2Us=$currentPreSendUs s2ToR1Us=$currentPostSendUs s1ToR1Us=$currentEndToEndUs likely=$likelySource"
                                )
                            }
                            lastReceivedRemoteSeq = diagMeta.seq
                            lastRemoteDiagSenderId = diagMeta.senderId
                            lastRemoteDiagFlowHash = diagMeta.flowHash
                            lastRemoteDiagT0Ns = diagMeta.t0Ns
                            lastRemoteDiagS1Ns = diagMeta.s1Ns
                            lastRemoteDiagSendAttemptNs = diagMeta.sendAttemptNs
                        }
                        packetTraceLog("📥 R1") {
                            "seq=$inboundSeq${if (diagMeta != null) " sender=${diagMeta.senderId} flow=${diagMeta.flowHash.toUInt().toString(16)} flags=${diagMeta.flags} remoteSeq=${diagMeta.seq} t0ToS1Us=${if (diagMeta.t0Ns > 0L && diagMeta.s1Ns >= diagMeta.t0Ns) ((diagMeta.s1Ns - diagMeta.t0Ns) / 1_000) else -1} s1ToR1Us=${(rxNs - diagMeta.s1Ns) / 1_000} s2ToR1Us=${(rxNs - diagMeta.sendAttemptNs) / 1_000}" else ""} len=$payloadLength from=${addr.address.hostAddress}:${addr.port} gapMs=$gapMs bind=$boundNetworkLabel"
                        }

                        val version = (tempArray[0].toInt() and 0xF0) ushr 4

                        if (version == 4) {
                            val parsed = PacketParser.parse(tempArray, payloadLength)
                            injectInnerUdpToDevice(tempArray, 0, payloadLength, parsed, rxNs, inboundSeq, diagMeta)
                        } else if (version == 6) {
                            DebugStats.ipv6TunnelIn.incrementAndGet()
                            injectInnerIpv6ToDevice(tempArray, 0, payloadLength, rxNs, inboundSeq, diagMeta)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    // Ignore shutdown-related exceptions
                }
            }
        }
        
        debugLog("ðŸ”„ PEER-RX", "Loop ended")
    }

    private fun injectInnerUdpToDevice(buffer: ByteArray, offset: Int, length: Int, parsed: PacketParser.ParsedPacket, receivedAtNs: Long, seq: Long, diagMeta: TunnelDiagMeta? = null) {
        if (!parsed.isValid) { DebugStats.injectionErrors.incrementAndGet(); return }
        if (parsed.protocol != PacketParser.PROTOCOL_UDP) { DebugStats.injectionErrors.incrementAndGet(); return }

        val payloadOffset = parsed.headerLength + 8
        val payloadLength = parsed.totalLength - payloadOffset

        if (payloadLength <= 0) { DebugStats.injectionErrors.incrementAndGet(); return }
        if (payloadOffset + payloadLength > length) { DebugStats.injectionErrors.incrementAndGet(); return }

        val destIpBytes = detectedGameIpBytes ?: VPN_ADDRESS_BYTES

        val peerSentPort = parsed.destPort
        val targetPort = resolveInboundGameplayPort(peerSentPort, diagMeta?.flags ?: 0)

        if (peerSentPort != targetPort) {
            DebugStats.portCorrected.incrementAndGet()
        }

        val payload = ByteArray(payloadLength)
        System.arraycopy(buffer, offset + payloadOffset, payload, 0, payloadLength)
        
        val ipPacket = buildUdpIpPacket(
            srcIpBytes = getPeerFabricatedIpBytes(),
            dstIpBytes = destIpBytes,
            srcPort = parsed.sourcePort,
            dstPort = targetPort,
            payload = payload,
            payloadLength = payload.size
        )

        // Route through jitter buffer instead of direct delivery
        val jb = jitterBuffer
        if (jb != null) {
            // When jitter buffer is active we cannot propagate the instrumentation sequence
            jb.enqueue(ipPacket, ipPacket.size, receivedAtNs)
        } else {
            // Propagate the inbound sequence so tunWriteLoop can log R2
            offerToDeviceWithSeq(ipPacket, ipPacket.size, "TUNNEL-IN-v4", receivedAtNs, seq)
        }
        PrimeGameplayTracker.noteInboundPeerUdp()
        AppState.tunneled.incrementAndGet()
    }

    private fun injectInnerIpv6ToDevice(buffer: ByteArray, offset: Int, length: Int, receivedAtNs: Long, seq: Long, diagMeta: TunnelDiagMeta? = null) {
        if (length < 40) { DebugStats.injectionErrors.incrementAndGet(); return }
        
        val nextHeader = buffer[offset + 6].toInt() and 255
        
        if (nextHeader == 17) {
            if (length < 48) { DebugStats.injectionErrors.incrementAndGet(); return }
            
            val srcPort = ((buffer[offset + 40].toInt() and 255) shl 8) or (buffer[offset + 41].toInt() and 255)
            val dstPort = ((buffer[offset + 42].toInt() and 255) shl 8) or (buffer[offset + 43].toInt() and 255)
            
            val srcAddr = ByteArray(16)
            System.arraycopy(buffer, offset + 8, srcAddr, 0, 16)
            
            val udpLength = ((buffer[offset + 44].toInt() and 255) shl 8) or (buffer[offset + 45].toInt() and 255)
            val payloadLength = udpLength - 8
            
            if (payloadLength <= 0 || 48 + payloadLength > length) { DebugStats.injectionErrors.incrementAndGet(); return }
            
            val payload = ByteArray(payloadLength)
            System.arraycopy(buffer, offset + 48, payload, 0, payloadLength)
            
            val peerSentPort = dstPort
            val targetPort = resolveInboundGameplayPort(peerSentPort, diagMeta?.flags ?: 0)

            if (peerSentPort != targetPort) {
                DebugStats.portCorrected.incrementAndGet()
            }
            
            val targetDstAddr = detectedGameIpv6Bytes ?: VPN_ADDRESS_IPV6_BYTES
            
            val correctedPacket = buildIpv6UdpPacket(srcAddr = srcAddr, dstAddr = targetDstAddr, srcPort = srcPort, dstPort = targetPort, payload = payload)
            
            DebugStats.ipv6Injected.incrementAndGet()

            // Route through jitter buffer instead of direct delivery
            val jb = jitterBuffer
            if (jb != null) {
                jb.enqueue(correctedPacket, correctedPacket.size, receivedAtNs)
            } else {
                // Propagate the inbound sequence so tunWriteLoop can log R2
                offerToDeviceWithSeq(correctedPacket, correctedPacket.size, "TUNNEL-IN-v6", receivedAtNs, seq)
            }
            PrimeGameplayTracker.noteInboundPeerUdp()
            AppState.tunneled.incrementAndGet()

        } else {
            val inner = ByteArray(length)
            System.arraycopy(buffer, offset, inner, 0, length)
            DebugStats.ipv6Injected.incrementAndGet()

            // Route through jitter buffer
            val jb2 = jitterBuffer
            if (jb2 != null) {
                jb2.enqueue(inner, inner.size, receivedAtNs)
            } else {
                // Propagate the inbound sequence for non‑UDP IPv6 as well
                offerToDeviceWithSeq(inner, inner.size, "TUNNEL-IN-v6-other", receivedAtNs, seq)
            }
            AppState.tunneled.incrementAndGet()
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TCP CONNECTION HANDLING
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun initializeTcpConnection(key: String, remoteIp: String, remotePort: Int, localIp: String, localPort: Int, tcp: ParsedTcpHeader) {
        val remoteIpBytes = ipv4StringToBytesOptimized(remoteIp)
        val localIpBytes = ipv4StringToBytesOptimized(localIp)
        
        if (!tcp.isSYN()) {
            val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, remotePort, localPort, 0, tcp.sequenceNumber + 1, TCP_FLAG_RST, null, 0)
            offerToDevice(pkt, pkt.size, "TCP-RST")
            return
        }
        val selector = tcpSelector ?: return
        var tcb: TcpTcb? = null
        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            if (!protectTcpSocket(channel.socket())) {
                channel.close()
                val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, remotePort, localPort, 0, tcp.sequenceNumber + 1, TCP_FLAG_RST, null, 0)
                offerToDevice(pkt, pkt.size, "TCP-RST")
                return
            }
            bindPassthroughTcpSocket(channel.socket())
            val mySeq = random.nextInt(Short.MAX_VALUE + 1).toLong()
            tcb = TcpTcb(key, remoteIp, remotePort, localIp, localPort, channel, mySeq, tcp.sequenceNumber + 1, tcp.acknowledgementNumber)
            putTcb(key, tcb)
            channel.connect(InetSocketAddress(InetAddress.getByName(remoteIp), remotePort))
            if (channel.finishConnect()) {
                tcb.status = TcpTcb.TcbStatus.SYN_RECEIVED
                val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, remotePort, localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_SYN or TCP_FLAG_ACK, null, 0)
                offerToDevice(pkt, pkt.size, "TCP-SYNACK")
                tcb.mySequenceNum++
            } else {
                tcb.status = TcpTcb.TcbStatus.SYN_SENT
                tcb.selectionKey = channel.register(selector, SelectionKey.OP_CONNECT, tcb)
            }
        } catch (e: Exception) {
            if (tcb != null) closeTcb(tcb)
            val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, remotePort, localPort, 0, tcp.sequenceNumber + 1, TCP_FLAG_RST, null, 0)
            offerToDevice(pkt, pkt.size, "TCP-RST")
        }
    }

    private fun processDuplicateSyn(tcb: TcpTcb, tcp: ParsedTcpHeader) {
        if (tcb.status == TcpTcb.TcbStatus.SYN_SENT) tcb.myAcknowledgementNum = tcp.sequenceNumber + 1
        else sendRstAndClose(tcb, 1)
    }

    private fun processFin(tcb: TcpTcb, tcp: ParsedTcpHeader) {
        tcb.myAcknowledgementNum = tcp.sequenceNumber + 1
        val remoteIpBytes = ipv4StringToBytesOptimized(tcb.remoteIp)
        val localIpBytes = ipv4StringToBytesOptimized(tcb.localIp)
        
        val pkt = if (tcb.waitingForNetworkData) {
            tcb.status = TcpTcb.TcbStatus.CLOSE_WAIT
            buildTcpIpPacket(remoteIpBytes, localIpBytes, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_ACK, null, 0)
        } else {
            tcb.status = TcpTcb.TcbStatus.LAST_ACK
            val p = buildTcpIpPacket(remoteIpBytes, localIpBytes, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_FIN or TCP_FLAG_ACK, null, 0)
            tcb.mySequenceNum++
            p
        }
        offerToDevice(pkt, pkt.size, "TCP-FIN")
    }

    private fun processAck(tcb: TcpTcb, tcp: ParsedTcpHeader, payload: ByteBuffer?, payloadSize: Int) {
        val selector = tcpSelector ?: return
        if (tcb.status == TcpTcb.TcbStatus.SYN_RECEIVED) {
            tcb.status = TcpTcb.TcbStatus.ESTABLISHED
            tcb.selectionKey = tcb.channel.register(selector, SelectionKey.OP_READ, tcb)
            tcb.waitingForNetworkData = true
        }
        if (payload != null && payloadSize > 0) {
            try { while(payload.hasRemaining()) tcb.channel.write(payload) }
            catch(e: Exception) { sendRstAndClose(tcb, payloadSize); return }
        }
        if (payloadSize > 0) { tcb.myAcknowledgementNum = tcp.sequenceNumber + payloadSize }
        
        val remoteIpBytes = ipv4StringToBytesOptimized(tcb.remoteIp)
        val localIpBytes = ipv4StringToBytesOptimized(tcb.localIp)
        val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_ACK, null, 0)
        offerToDevice(pkt, pkt.size, "TCP-ACK")
    }

    private fun processTcpConnect(key: SelectionKey) {
        val tcb = key.attachment() as TcpTcb
        try {
            if (tcb.channel.finishConnect()) {
                tcb.status = TcpTcb.TcbStatus.SYN_RECEIVED
                if (tcb.isIpv6) {
                    val pkt = buildIpv6TcpPacket(tcb.remoteAddrBytes!!, tcb.localAddrBytes!!, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_SYN or TCP_FLAG_ACK, null, 0)
                    offerToDevice(pkt, pkt.size, "IPv6-TCP-SYNACK")
                } else {
                    val remoteIpBytes = ipv4StringToBytesOptimized(tcb.remoteIp)
                    val localIpBytes = ipv4StringToBytesOptimized(tcb.localIp)
                    val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_SYN or TCP_FLAG_ACK, null, 0)
                    offerToDevice(pkt, pkt.size, "TCP-SYNACK")
                }
                tcb.mySequenceNum++
                key.interestOps(SelectionKey.OP_READ)
            }
        } catch(e: Exception) {
            if (tcb.isIpv6) sendIpv6RstAndClose(tcb, 0)
            else sendRstAndClose(tcb, 0)
        }
    }

    private fun processTcpRead(key: SelectionKey, buf: ByteBuffer) {
        val tcb = key.attachment() as TcpTcb
        buf.clear()
        val len = try { tcb.channel.read(buf) } catch(e: Exception) {
            if (tcb.isIpv6) sendIpv6RstAndClose(tcb, 0) else sendRstAndClose(tcb, 0)
            return
        }
        if (len == -1) {
            key.interestOps(0)
            if (tcb.status != TcpTcb.TcbStatus.LAST_ACK) {
                tcb.status = TcpTcb.TcbStatus.LAST_ACK
                if (tcb.isIpv6) {
                    val pkt = buildIpv6TcpPacket(tcb.remoteAddrBytes!!, tcb.localAddrBytes!!, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_FIN or TCP_FLAG_ACK, null, 0)
                    offerToDevice(pkt, pkt.size, "IPv6-TCP-FIN")
                } else {
                    val remoteIpBytes = ipv4StringToBytesOptimized(tcb.remoteIp)
                    val localIpBytes = ipv4StringToBytesOptimized(tcb.localIp)
                    val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_FIN or TCP_FLAG_ACK, null, 0)
                    offerToDevice(pkt, pkt.size, "TCP-FIN")
                }
                tcb.mySequenceNum++
            }
            return
        }
        buf.flip()
        val payload = ByteArray(len); buf.get(payload)
        if (tcb.isIpv6) {
            val pkt = buildIpv6TcpPacket(tcb.remoteAddrBytes!!, tcb.localAddrBytes!!, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_PSH or TCP_FLAG_ACK, payload, len)
            offerToDevice(pkt, pkt.size, "IPv6-TCP-DATA")
        } else {
            val remoteIpBytes = ipv4StringToBytesOptimized(tcb.remoteIp)
            val localIpBytes = ipv4StringToBytesOptimized(tcb.localIp)
            val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, tcb.remotePort, tcb.localPort, tcb.mySequenceNum, tcb.myAcknowledgementNum, TCP_FLAG_PSH or TCP_FLAG_ACK, payload, len)
            offerToDevice(pkt, pkt.size, "TCP-DATA")
        }
        tcb.mySequenceNum += len
    }

    private fun sendRstAndClose(tcb: TcpTcb, ackInc: Int) {
        val remoteIpBytes = ipv4StringToBytesOptimized(tcb.remoteIp)
        val localIpBytes = ipv4StringToBytesOptimized(tcb.localIp)
        val pkt = buildTcpIpPacket(remoteIpBytes, localIpBytes, tcb.remotePort, tcb.localPort, 0, tcb.myAcknowledgementNum + ackInc, TCP_FLAG_RST, null, 0)
        offerToDevice(pkt, pkt.size, "TCP-RST")
        closeTcb(tcb)
    }

    private fun getTcb(key: String): TcpTcb? { synchronized(tcbCacheLock) { return tcbCache[key] } }
    private fun putTcb(key: String, tcb: TcpTcb) { synchronized(tcbCacheLock) { tcbCache[key] = tcb } }
    private fun closeTcb(tcb: TcpTcb) { tcb.close(); synchronized(tcbCacheLock) { tcbCache.remove(tcb.ipAndPort) } }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // DATA CLASSES
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private data class UdpFlowRef(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int)
    
    private class TcpTcb(
        val ipAndPort: String, val remoteIp: String, val remotePort: Int,
        val localIp: String, val localPort: Int, val channel: SocketChannel,
        var mySequenceNum: Long, var myAcknowledgementNum: Long, var theirAcknowledgementNum: Long
    ) {
        enum class TcbStatus { SYN_SENT, SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT, LAST_ACK }
        var status = TcbStatus.SYN_SENT
        var waitingForNetworkData = false
        var selectionKey: SelectionKey? = null
        var isIpv6 = false
        var remoteAddrBytes: ByteArray? = null
        var localAddrBytes: ByteArray? = null
        fun close() { try { selectionKey?.cancel(); channel.close() } catch(e: Exception){ } }
    }
    
    private data class ParsedTcpHeader(val sourcePort: Int, val destPort: Int, val sequenceNumber: Long, val acknowledgementNumber: Long, val headerLength: Int, val flags: Int) {
        fun isSYN() = (flags and TCP_FLAG_SYN) != 0
        fun isACK() = (flags and TCP_FLAG_ACK) != 0
        fun isFIN() = (flags and TCP_FLAG_FIN) != 0
        fun isRST() = (flags and TCP_FLAG_RST) != 0
    }
    
    private fun parseTcpHeader(p: ByteArray, off: Int): ParsedTcpHeader? {
        if (p.size < off + 20) return null
        val hl = ((p[off+12].toInt() and 0xF0) shr 4) * 4
        if (hl < 20 || p.size < off + hl) return null
        return ParsedTcpHeader(readU16(p, off), readU16(p, off+2), readU32(p, off+4), readU32(p, off+8), hl, p[off+13].toInt() and 0xFF)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PACKET BUILDING
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun buildUdpIpPacket(srcIpBytes: ByteArray, dstIpBytes: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray, payloadLength: Int): ByteArray {
        val udpLen = 8 + payloadLength
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p[8] = 0x40.toByte(); p[9] = 17.toByte()
        writeU16(p, 2, total)
        System.arraycopy(srcIpBytes, 0, p, 12, 4)
        System.arraycopy(dstIpBytes, 0, p, 16, 4)
        val udpOffset = 20
        writeU16(p, udpOffset, srcPort); writeU16(p, udpOffset + 2, dstPort)
        writeU16(p, udpOffset + 4, udpLen); writeU16(p, udpOffset + 6, 0)
        System.arraycopy(payload, 0, p, udpOffset + 8, payloadLength)
        val ipChecksum = calculateChecksum(p, 0, 20).toInt() and 0xFFFF
        p[10] = (ipChecksum shr 8).toByte(); p[11] = (ipChecksum and 0xFF).toByte()
        var sum: Long = 0
        sum += ((srcIpBytes[0].toInt() and 0xFF) shl 8) or (srcIpBytes[1].toInt() and 0xFF)
        sum += ((srcIpBytes[2].toInt() and 0xFF) shl 8) or (srcIpBytes[3].toInt() and 0xFF)
        sum += ((dstIpBytes[0].toInt() and 0xFF) shl 8) or (dstIpBytes[1].toInt() and 0xFF)
        sum += ((dstIpBytes[2].toInt() and 0xFF) shl 8) or (dstIpBytes[3].toInt() and 0xFF)
        sum += 17; sum += udpLen
        var i = 0
        while (i < udpLen - 1) { sum += ((p[udpOffset + i].toInt() and 0xFF) shl 8) or (p[udpOffset + i + 1].toInt() and 0xFF); i += 2 }
        if (i < udpLen) { sum += (p[udpOffset + i].toInt() and 0xFF) shl 8 }
        while ((sum shr 16) > 0) { sum = (sum and 0xFFFF) + (sum shr 16) }
        val udpChecksum = sum.inv().toInt() and 0xFFFF
        val finalChecksum = if (udpChecksum == 0) 0xFFFF else udpChecksum
        p[udpOffset + 6] = (finalChecksum shr 8).toByte(); p[udpOffset + 7] = (finalChecksum and 0xFF).toByte()
        return p
    }

    private fun buildIpv6UdpPacket(srcAddr: ByteArray, dstAddr: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 40 + udpLen
        val p = ByteArray(total)
        p[0] = 0x60.toByte(); writeU16(p, 4, udpLen); p[6] = 17; p[7] = 64
        System.arraycopy(srcAddr, 0, p, 8, 16); System.arraycopy(dstAddr, 0, p, 24, 16)
        val udpOffset = 40
        writeU16(p, udpOffset, srcPort); writeU16(p, udpOffset + 2, dstPort)
        writeU16(p, udpOffset + 4, udpLen); writeU16(p, udpOffset + 6, 0)
        System.arraycopy(payload, 0, p, udpOffset + 8, payload.size)
        val checksum = calculateIpv6UdpChecksum(p, udpOffset, udpLen)
        writeU16(p, udpOffset + 6, checksum)
        return p
    }

    private fun calculateIpv6UdpChecksum(packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum: Long = 0
        for (i in 8..22 step 2) { sum += ((packet[i].toInt() and 255) shl 8) or (packet[i + 1].toInt() and 255) }
        for (i in 24..38 step 2) { sum += ((packet[i].toInt() and 255) shl 8) or (packet[i + 1].toInt() and 255) }
        sum += udpLen.toLong(); sum += 17L
        var i = 0
        while (i < udpLen - 1) { sum += ((packet[udpOffset + i].toInt() and 255) shl 8) or (packet[udpOffset + i + 1].toInt() and 255); i += 2 }
        if (i < udpLen) { sum += (packet[udpOffset + i].toInt() and 255) shl 8 }
        while ((sum ushr 16) > 0) { sum = (sum and 0xFFFF) + (sum ushr 16) }
        val cs = sum.inv().toInt() and 0xFFFF
        return if (cs == 0) 0xFFFF else cs
    }

    private fun buildTcpIpPacket(
        srcIpBytes: ByteArray, dstIpBytes: ByteArray,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        payload: ByteArray?, payloadLength: Int
    ): ByteArray {
        val tcpHeaderLen = 20
        val ipHeaderLen = 20
        val dataLen = payloadLength.coerceAtLeast(0)
        val total = ipHeaderLen + tcpHeaderLen + dataLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte(); p[8] = 0x40.toByte(); p[9] = 6.toByte()
        writeU16(p, 2, total)
        System.arraycopy(srcIpBytes, 0, p, 12, 4)
        System.arraycopy(dstIpBytes, 0, p, 16, 4)
        val tcpOff = ipHeaderLen
        writeU16(p, tcpOff, srcPort); writeU16(p, tcpOff + 2, dstPort)
        p[tcpOff + 4] = ((seqNum shr 24) and 0xFF).toByte()
        p[tcpOff + 5] = ((seqNum shr 16) and 0xFF).toByte()
        p[tcpOff + 6] = ((seqNum shr 8) and 0xFF).toByte()
        p[tcpOff + 7] = (seqNum and 0xFF).toByte()
        p[tcpOff + 8] = ((ackNum shr 24) and 0xFF).toByte()
        p[tcpOff + 9] = ((ackNum shr 16) and 0xFF).toByte()
        p[tcpOff + 10] = ((ackNum shr 8) and 0xFF).toByte()
        p[tcpOff + 11] = (ackNum and 0xFF).toByte()
        p[tcpOff + 12] = (5 shl 4).toByte()
        p[tcpOff + 13] = flags.toByte()
        writeU16(p, tcpOff + 14, 65535)
        writeU16(p, tcpOff + 16, 0); writeU16(p, tcpOff + 18, 0)
        if (payload != null && dataLen > 0) {
            System.arraycopy(payload, 0, p, tcpOff + tcpHeaderLen, dataLen)
        }
        val ipChecksum = calculateChecksum(p, 0, ipHeaderLen).toInt() and 0xFFFF
        p[10] = (ipChecksum shr 8).toByte(); p[11] = (ipChecksum and 0xFF).toByte()
        val tcpLen = tcpHeaderLen + dataLen
        var sum: Long = 0
        sum += ((srcIpBytes[0].toInt() and 0xFF) shl 8) or (srcIpBytes[1].toInt() and 0xFF)
        sum += ((srcIpBytes[2].toInt() and 0xFF) shl 8) or (srcIpBytes[3].toInt() and 0xFF)
        sum += ((dstIpBytes[0].toInt() and 0xFF) shl 8) or (dstIpBytes[1].toInt() and 0xFF)
        sum += ((dstIpBytes[2].toInt() and 0xFF) shl 8) or (dstIpBytes[3].toInt() and 0xFF)
        sum += 6; sum += tcpLen
        var i = 0
        while (i < tcpLen - 1) { sum += ((p[tcpOff + i].toInt() and 0xFF) shl 8) or (p[tcpOff + i + 1].toInt() and 0xFF); i += 2 }
        if (i < tcpLen) { sum += (p[tcpOff + i].toInt() and 0xFF) shl 8 }
        while ((sum shr 16) > 0) { sum = (sum and 0xFFFF) + (sum shr 16) }
        val tcpChecksum = sum.inv().toInt() and 0xFFFF
        val finalTcpChecksum = if (tcpChecksum == 0) 0xFFFF else tcpChecksum
        p[tcpOff + 16] = (finalTcpChecksum shr 8).toByte(); p[tcpOff + 17] = (finalTcpChecksum and 0xFF).toByte()
        return p
    }

    private fun buildIpv6TcpPacket(
        srcAddr: ByteArray, dstAddr: ByteArray,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        payload: ByteArray?, payloadLength: Int
    ): ByteArray {
        val tcpHeaderLen = 20
        val dataLen = payloadLength.coerceAtLeast(0)
        val tcpLen = tcpHeaderLen + dataLen
        val total = 40 + tcpLen
        val p = ByteArray(total)
        p[0] = 0x60.toByte(); writeU16(p, 4, tcpLen); p[6] = 6; p[7] = 64
        System.arraycopy(srcAddr, 0, p, 8, 16); System.arraycopy(dstAddr, 0, p, 24, 16)
        val tcpOff = 40
        writeU16(p, tcpOff, srcPort); writeU16(p, tcpOff + 2, dstPort)
        p[tcpOff + 4] = ((seqNum shr 24) and 0xFF).toByte()
        p[tcpOff + 5] = ((seqNum shr 16) and 0xFF).toByte()
        p[tcpOff + 6] = ((seqNum shr 8) and 0xFF).toByte()
        p[tcpOff + 7] = (seqNum and 0xFF).toByte()
        p[tcpOff + 8] = ((ackNum shr 24) and 0xFF).toByte()
        p[tcpOff + 9] = ((ackNum shr 16) and 0xFF).toByte()
        p[tcpOff + 10] = ((ackNum shr 8) and 0xFF).toByte()
        p[tcpOff + 11] = (ackNum and 0xFF).toByte()
        p[tcpOff + 12] = (5 shl 4).toByte(); p[tcpOff + 13] = flags.toByte()
        writeU16(p, tcpOff + 14, 65535); writeU16(p, tcpOff + 16, 0); writeU16(p, tcpOff + 18, 0)
        if (payload != null && dataLen > 0) {
            System.arraycopy(payload, 0, p, tcpOff + tcpHeaderLen, dataLen)
        }
        var sum: Long = 0
        for (i in 8..22 step 2) { sum += ((p[i].toInt() and 255) shl 8) or (p[i + 1].toInt() and 255) }
        for (i in 24..38 step 2) { sum += ((p[i].toInt() and 255) shl 8) or (p[i + 1].toInt() and 255) }
        sum += tcpLen.toLong(); sum += 6L
        var i = 0
        while (i < tcpLen - 1) { sum += ((p[tcpOff + i].toInt() and 255) shl 8) or (p[tcpOff + i + 1].toInt() and 255); i += 2 }
        if (i < tcpLen) { sum += (p[tcpOff + i].toInt() and 255) shl 8 }
        while ((sum ushr 16) > 0) { sum = (sum and 0xFFFF) + (sum ushr 16) }
        val cs = sum.inv().toInt() and 0xFFFF
        val finalCs = if (cs == 0) 0xFFFF else cs
        p[tcpOff + 16] = (finalCs shr 8).toByte(); p[tcpOff + 17] = (finalCs and 0xFF).toByte()
        return p
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // UTILITY FUNCTIONS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum: Long = 0
        var i = 0
        while (i < length - 1) {
            sum += ((data[offset + i].toInt() and 0xFF) shl 8) or (data[offset + i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) { sum += (data[offset + i].toInt() and 0xFF) shl 8 }
        while ((sum shr 16) > 0) { sum = (sum and 0xFFFF) + (sum shr 16) }
        return sum.inv().toShort()
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        return (((data[offset].toInt() and 0xFF).toLong()) shl 24) or
               (((data[offset + 1].toInt() and 0xFF).toLong()) shl 16) or
               (((data[offset + 2].toInt() and 0xFF).toLong()) shl 8) or
               ((data[offset + 3].toInt() and 0xFF).toLong())
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

     private fun ipv4StringToBytesOptimized(ip: String): ByteArray {
        val result = ByteArray(4)
        var octet = 0
        var index = 0
        for (c in ip) {
            if (c == '.') {
                result[index++] = octet.toByte()
                octet = 0
            } else {
                octet = octet * 10 + (c - '0')
            }
        }
        result[index] = octet.toByte()
        return result
    }

    private fun DatagramChannel.closeQuietly() {
        try { close() } catch (_: Exception) {}
    }

    companion object {
        const val TCP_FLAG_FIN = 0x01
        const val TCP_FLAG_SYN = 0x02
        const val TCP_FLAG_RST = 0x04
        const val TCP_FLAG_PSH = 0x08
        const val TCP_FLAG_ACK = 0x10
    }
}
