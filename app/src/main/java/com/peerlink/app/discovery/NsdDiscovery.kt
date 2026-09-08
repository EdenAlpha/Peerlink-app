package com.peerlink.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * HotspotDiscovery — replaces mDNS/NSD with UDP broadcast discovery.
 *
 * Why the change:
 *   Android's NsdManager (DNS-SD / mDNS) requires multicast packets to flow
 *   through the network. Many Android hotspot implementations enable "AP isolation"
 *   which blocks multicast between clients, making NSD completely silent even when
 *   both phones are on the same hotspot. This is the root cause of "I never see my
 *   peer when I turn on hotspot."
 *
 *   UDP directed broadcast (255.255.255.255 or subnet broadcast) bypasses AP
 *   isolation on most hotspot implementations and is reliably delivered.
 *
 * The public interface is IDENTICAL to the old NsdDiscovery — MainActivity.kt
 * requires zero changes.
 *
 * Protocol:
 *   Both peers send a JSON "HELLO" beacon every 2 seconds on port 17026.
 *   When A hears B's HELLO, A calls onPeerFound(B.ip, B.port, B.name).
 *   When the listener stops, onPeerLost fires for all known peers.
 */
class NsdDiscovery(
    private val context: Context,
    private val callback: NsdCallback
) {
    companion object {
        private const val TAG              = "HotspotDisc"
        private const val DISCOVERY_PORT   = 17026
        private const val BEACON_INTERVAL  = 2_000L   // ms between broadcasts
        private const val SOCKET_TIMEOUT   = 1_200    // ms receive timeout
        private const val PEER_TTL_MS      = 8_000L   // remove peer if silent this long
    }

    interface NsdCallback {
        fun onPeerFound(ip: InetAddress, port: Int, serviceName: String)
        fun onPeerLost(ip: InetAddress, serviceName: String)
        fun onLog(message: String)
    }

    private val running     = AtomicBoolean(false)
    // Receive and transmit are intentionally separate sockets. A UDP socket
    // bound to one concrete LAN address does NOT receive packets whose
    // destination is the subnet/limited broadcast address on Linux/Android.
    // RX therefore binds wildcard on the fixed discovery port, while TX is
    // source-bound to the selected Wi-Fi/SoftAP address on an ephemeral port.
    private var socket: DatagramSocket? = null
    private var sendSocket: DatagramSocket? = null
    private var sendSocketPinnedToLan = false
    private var beaconThread: Thread?   = null
    private var listenThread: Thread?   = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val generation = AtomicLong(0L)

    // Our own identity (set in registerService)
    @Volatile private var localPort  = 0
    @Volatile private var userName   = ""
    @Volatile private var deviceId   = ""
    @Volatile private var localIp    = ""
    @Volatile private var localPrefixLength = 24

    private data class SeenPeer(
        val address: InetAddress,
        val name: String,
        val port: Int,
        val lastSeenMs: Long,
    )

    // Key by endpoint, not display name. Two people can legitimately choose
    // the same name (or own the same phone model with the same default name).
    private val knownPeers = ConcurrentHashMap<String, SeenPeer>()
    private val lastDirectReplyMs = ConcurrentHashMap<String, Long>()

    // ── Public API (unchanged from old NsdDiscovery) ─────────────────────────

    fun initialize() {
        callback.onLog("[NET-DISC  ] UDP broadcast discovery initialised (replaces mDNS)")
    }

    @Synchronized
    fun registerService(
        port: Int,
        name: String,
        ip: String,
        prefixLength: Int = 24,
        deviceId: String = "",
    ) {
        val safePort = port.takeIf { it in 1..65535 } ?: 17024
        val safeName = name.trim().take(48)
        val safeIp = ip.trim()
        val safePrefix = prefixLength.coerceIn(1, 32)
        val safeDeviceId = deviceId.trim().take(64)
        val pathChanged = localIp != safeIp || localPrefixLength != safePrefix
        val identityChanged = localPort != safePort || userName != safeName || this.deviceId != safeDeviceId

        localPort = safePort
        userName  = safeName
        localIp   = safeIp
        localPrefixLength = safePrefix
        this.deviceId = safeDeviceId
        callback.onLog("[NET-DISC  ] Registered as \"$name\" on $ip:$port")

        // A running DatagramSocket remains bound to the old address. Merely
        // changing these fields after Wi-Fi/hotspot migration creates a false
        // "scanning" state. Restart the socket on the new path instead.
        if (running.get() && (pathChanged || identityChanged)) {
            stopDiscovery()
            startDiscovery()
        }
    }

    @Synchronized
    fun startDiscovery() {
        if (!running.compareAndSet(false, true)) return
        val myGeneration = generation.incrementAndGet()

        // Multicast lock still needed even for broadcast on some chipsets
        acquireMulticastLock()

        try {
            val bindAddr: InetAddress? =
                if (localIp.isNotBlank() && localIp != "127.0.0.1") {
                    runCatching { InetAddress.getByName(localIp) }.getOrNull()
                } else null

            // RX MUST be wildcard. Binding RX to bindAddr filters out packets
            // addressed to 192.168.x.255 / 255.255.255.255 before userspace sees
            // them, which produced the false "Scanning" state on real phones.
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT
                broadcast = true
                bind(InetSocketAddress(null as InetAddress?, DISCOVERY_PORT))
            }

            // TX is a separate ephemeral socket pinned by source address to the
            // selected LAN path. This keeps mobile data from stealing limited
            // broadcasts while still allowing RX to hear broadcast destinations.
            sendSocketPinnedToLan = false
            sendSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                if (bindAddr != null) {
                    try {
                        bind(InetSocketAddress(bindAddr, 0))
                        sendSocketPinnedToLan = true
                    } catch (bindError: Exception) {
                        callback.onLog("[NET-DISC  ] LAN TX bind failed (${bindError.message}); using routed TX")
                        bind(InetSocketAddress(null as InetAddress?, 0))
                    }
                } else {
                    bind(InetSocketAddress(null as InetAddress?, 0))
                }
            }
        } catch (e: Exception) {
            callback.onLog("[NET-DISC  ] Socket bind failed: ${e.message}")
            socket?.runCatching { close() }
            sendSocket?.runCatching { close() }
            socket = null
            sendSocket = null
            sendSocketPinnedToLan = false
            running.set(false)
            generation.incrementAndGet()
            releaseMulticastLock()
            return
        }

        callback.onLog(
            "[NET-DISC  ] RX UDP $DISCOVERY_PORT wildcard; TX " +
                (if (sendSocketPinnedToLan) "pinned to $localIp" else "kernel-routed")
        )

        // ── Listen thread ──────────────────────────────────────────────────
        listenThread = Thread {
            while (running.get() && generation.get() == myGeneration) {
                // Receive incoming beacons
                try {
                    val buf = ByteArray(512)
                    val pkt = DatagramPacket(buf, buf.size)
                    socket?.receive(pkt)
                    handlePacket(pkt)
                } catch (_: SocketTimeoutException) { /* expected every 1.2 s */ }
                  catch (_: SocketException)        { /* socket closed = shutting down */ break }
                  catch (e: Exception)              { callback.onLog("[NET-DISC  ] recv err: ${e.message}") }

                // Expire silent peers
                val now = System.currentTimeMillis()
                val expired = knownPeers.entries
                    .filter { (_, peer) -> now - peer.lastSeenMs > PEER_TTL_MS }
                    .map { it.key to it.value }
                expired.forEach { (key, peer) ->
                    if (!knownPeers.remove(key, peer)) return@forEach
                    lastDirectReplyMs.remove(key)
                    callback.onLog("[NET-DISC  ] Peer expired: ${peer.name} @ $key")
                    callback.onPeerLost(peer.address, peer.name)
                }
            }
        }.apply { name = "LanLink-Hotspot-Listen"; isDaemon = true; start() }

        // ── Beacon thread ──────────────────────────────────────────────────
        beaconThread = Thread {
            while (running.get() && generation.get() == myGeneration) {
                sendBeacon()
                try { Thread.sleep(BEACON_INTERVAL) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "LanLink-Hotspot-Beacon"; isDaemon = true; start() }
    }

    @Synchronized
    fun stopDiscovery() {
        if (!running.compareAndSet(true, false)) return
        generation.incrementAndGet()
        socket?.runCatching { close() }
        sendSocket?.runCatching { close() }
        socket = null
        sendSocket = null
        sendSocketPinnedToLan = false
        beaconThread?.interrupt()
        listenThread?.interrupt()
        // The generation guard prevents old loops from joining a newly started
        // session. Short joins also make reconfiguration deterministic without
        // ever blocking the UI for a socket timeout.
        if (Thread.currentThread() !== beaconThread) beaconThread?.runCatching { join(150L) }
        if (Thread.currentThread() !== listenThread) listenThread?.runCatching { join(150L) }
        beaconThread = null
        listenThread = null

        // Notify caller about all lost peers
        knownPeers.values.toList().forEach { callback.onPeerLost(it.address, it.name) }
        knownPeers.clear()
        lastDirectReplyMs.clear()

        releaseMulticastLock()
        callback.onLog("[NET-DISC  ] Discovery stopped")
    }

    fun tearDown() {
        stopDiscovery()
    }

    val isRunning: Boolean
        get() = running.get() && socket?.isClosed == false

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun sendBeacon() {
        if (userName.isBlank()) return
        try {
            val payload = JSONObject().apply {
                put("type", "LL_HELLO")
                put("name", userName)
                put("port", localPort)
                if (deviceId.isNotBlank()) put("device_id", deviceId)
            }.toString().toByteArray(Charsets.UTF_8)

            val tx = sendSocket ?: return
            val subnetBroadcast = directedBroadcast(localIp, localPrefixLength)
            if (subnetBroadcast != null) {
                tx.send(
                    DatagramPacket(
                        payload, payload.size,
                        InetAddress.getByName(subnetBroadcast), DISCOVERY_PORT
                    )
                )
            }

            // Some Android tethering stacks forward only limited broadcast while
            // others behave better with subnet-directed broadcast. When TX is
            // source-pinned to the selected LAN, send both for compatibility.
            // If pinning failed, avoid 255.255.255.255 so mobile data cannot win.
            if (sendSocketPinnedToLan || subnetBroadcast == null) {
                tx.send(
                    DatagramPacket(
                        payload, payload.size,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                    )
                )
            }
        } catch (e: Exception) {
            callback.onLog("[NET-DISC  ] beacon send err: ${e.message}")
        }
    }

    private fun directedBroadcast(ip: String, prefixLength: Int): String? {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 } || prefixLength !in 1..32) return null
        var value = 0L
        for (part in parts) value = (value shl 8) or part.toLong()
        val mask = if (prefixLength == 32) 0xFFFF_FFFFL else (0xFFFF_FFFFL shl (32 - prefixLength)) and 0xFFFF_FFFFL
        val broadcast = (value and mask) or (mask.inv() and 0xFFFF_FFFFL)
        return listOf(24, 16, 8, 0).joinToString(".") { shift -> ((broadcast shr shift) and 0xFF).toString() }
    }

    private fun handlePacket(pkt: DatagramPacket) {
        try {
            val json = org.json.JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
            if (json.optString("type") != "LL_HELLO") return

            val peerName = json.optString("name", "").ifBlank { return }
            val advertisedPort = json.optInt("port", 17024)
            val peerPort = advertisedPort.takeIf { it in 1..65535 } ?: 17024
            val peerDeviceId = json.optString("device_id", "")
            val senderIp = pkt.address ?: return
            val senderKey = senderIp.hostAddress ?: return

            // Display names are not identities. Ignore only our exact endpoint
            // or stable device id so two identical phones still discover each
            // other when both use the same default name.
            if (senderKey == localIp) return
            if (deviceId.isNotBlank() && peerDeviceId == deviceId) return

            val now = System.currentTimeMillis()
            val previous = knownPeers.put(
                senderKey,
                SeenPeer(senderIp, peerName.take(48), peerPort, now),
            )
            val changed = previous == null || previous.name != peerName || previous.port != peerPort

            // Reply directly so hotspot hosts/clients can discover each other even
            // when one side's broadcast is not forwarded by the hotspot implementation.
            val lastReply = lastDirectReplyMs[senderKey] ?: 0L
            if (now - lastReply > 2_000L) {
                lastDirectReplyMs[senderKey] = now
                try {
                    val payload = JSONObject().apply {
                        put("type", "LL_HELLO")
                        put("name", userName)
                        put("port", localPort)
                        if (deviceId.isNotBlank()) put("device_id", deviceId)
                    }.toString().toByteArray(Charsets.UTF_8)
                    sendSocket?.send(DatagramPacket(payload, payload.size, senderIp, DISCOVERY_PORT))
                } catch (_: Exception) { }
            }

            if (changed) {
                callback.onLog("[NET-DISC  ] Peer found: $peerName @ ${senderIp.hostAddress}")
                callback.onPeerFound(senderIp, peerPort, peerName)
            }
        } catch (_: Exception) { /* malformed packet, ignore */ }
    }

    private fun acquireMulticastLock() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wm?.createMulticastLock("LanLinkDisc")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.runCatching { release() }
        multicastLock = null
    }
}
