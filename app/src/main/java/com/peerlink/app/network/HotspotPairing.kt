package com.peerlink.app.network

import android.util.Log
import org.json.JSONObject
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic UDP pairing for Hotspot mode.
 *
 * Previous behaviour:
 *  - discovery and pairing both used a generic PAIR packet
 *  - tapping a peer only set targetPeerIp and waited for a later matching packet
 *  - local tunnel port defaulted to 0 unless explicitly updated, so peers often never
 *    learned a usable port
 *
 * New behaviour:
 *  - HELLO beacons are discovery only
 *  - PAIR_REQ is sent immediately when the user taps a peer
 *  - PAIR_ACK is sent immediately in reply, so a single tap is enough
 *  - tunnel port is always advertised with a usable default (17024)
 */
class HotspotPairing {

    companion object {
        private const val TAG = "HotspotPairing"
        private const val PAIR_PORT = 17027
        private const val DEFAULT_TUNNEL_PORT = 17024
        private const val SOCKET_TIMEOUT = 1500
        private const val PAIRING_ATTEMPT_TIMEOUT = 30_000L
        private const val PAIR_REQ_INTERVAL = 700L
        private const val BEACON_INTERVAL = 2_000L
        private const val PEER_TTL_MS = 8_000L
        private const val DIRECT_REPLY_THROTTLE_MS = 2_000L
        private const val TYPE_HELLO = "HELLO"
        private const val TYPE_PAIR_REQ = "PAIR_REQ"
        private const val TYPE_PAIR_ACK = "PAIR_ACK"
    }

    private val running = AtomicBoolean(false)
    // Fixed-port RX must bind wildcard to receive subnet/limited broadcasts.
    // TX uses a separate ephemeral socket source-bound to the selected LAN IP.
    private var socket: DatagramSocket? = null
    private var sendSocket: DatagramSocket? = null
    private var sendSocketPinnedToLan = false
    private var thread: Thread? = null
    private val generation = AtomicLong(0L)

    @Volatile private var targetPeerIp: String? = null
    @Volatile private var peerConfirmed = false
    @Volatile private var pairingStartedAtMs = 0L
    @Volatile private var lastPairReqMs = 0L
    @Volatile private var localTunnelPort = DEFAULT_TUNNEL_PORT
    @Volatile private var myUserName = ""
    @Volatile private var myDeviceId = ""
    // Local LAN IP (Wi-Fi / hotspot). When set, the listener socket is bound to
    // this IP so beacons and pairing packets cannot leak out the cellular
    // interface on multi-homed devices. Blank → wildcard bind (legacy).
    @Volatile private var localIp: String = ""
    @Volatile private var localPrefixLength: Int = 24

    var onPeerDiscovered: ((peerIp: String, peerName: String) -> Unit)? = null
    var onPeerReady: ((peerIp: String) -> Unit)? = null
    var onPeerPortReceived: ((peerPort: Int) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onPeerLost: ((peerIp: String) -> Unit)? = null

    private val knownPeerIps = ConcurrentHashMap.newKeySet<String>()
    private val lastSeenMs = ConcurrentHashMap<String, Long>()
    private val lastHelloReplyMs = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun start(deviceId: String, myName: String = "", localIp: String = "", prefixLength: Int = 24) {
        if (!running.compareAndSet(false, true)) return
        val myGeneration = generation.incrementAndGet()
        myUserName = myName
        myDeviceId = deviceId
        this.localIp = localIp.trim()
        this.localPrefixLength = prefixLength.coerceIn(1, 32)

        thread = Thread {
            var sessionSocket: DatagramSocket? = null
            try {
                val bindAddr: InetAddress? =
                    if (this.localIp.isNotBlank() && this.localIp != "127.0.0.1") {
                        runCatching { InetAddress.getByName(this.localIp) }.getOrNull()
                    } else null

                // Broadcast destination packets are not delivered to a socket
                // bound to one concrete unicast address. Receive wildcard on the
                // well-known port so both hotspot host and station hear HELLO.
                sessionSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = SOCKET_TIMEOUT
                    broadcast = true
                    bind(InetSocketAddress(null as InetAddress?, PAIR_PORT))
                }
                socket = sessionSocket

                // Keep transmit routing deterministic with a source-pinned
                // ephemeral socket. Direct PAIR_REQ/ACK packets also use it.
                sendSocketPinnedToLan = false
                sendSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    if (bindAddr != null) {
                        try {
                            bind(InetSocketAddress(bindAddr, 0))
                            sendSocketPinnedToLan = true
                        } catch (bindError: Exception) {
                            Log.w(TAG, "[HOTSPOT   ] LAN TX bind failed (${bindError.message}); using routed TX")
                            bind(InetSocketAddress(null as InetAddress?, 0))
                        }
                    } else {
                        bind(InetSocketAddress(null as InetAddress?, 0))
                    }
                }
                Log.i(
                    TAG,
                    "[HOTSPOT   ] RX UDP $PAIR_PORT wildcard; TX " +
                        (if (sendSocketPinnedToLan) "pinned to ${this.localIp}" else "kernel-routed")
                )

                var lastBeaconMs = 0L

                // Discovery is a listener lifecycle, not a 90-second timer. The
                // old listener silently died while the UI continued to claim
                // discovery was active. Individual pairing attempts time out;
                // the listener itself stays alive until the Activity stops it.
                while (running.get() && generation.get() == myGeneration) {
                    try {
                        val buf = ByteArray(2048)
                        val pkt = DatagramPacket(buf, buf.size)
                        sessionSocket?.receive(pkt) ?: break
                        runCatching { handlePacket(pkt) }
                            .onFailure { Log.w(TAG, "[HOTSPOT   ] Ignored malformed discovery packet: ${it.message}") }
                    } catch (_: SocketTimeoutException) {
                        // expected: used to wake up for beacon/expiry work
                    } catch (_: SocketException) {
                        break
                    }

                    expireSilentPeers()

                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastBeaconMs >= BEACON_INTERVAL) {
                        sendHelloBeacon()
                        lastBeaconMs = nowMs
                    }

                    // While the user has selected a peer but the handshake is not yet complete,
                    // keep nudging with PAIR_REQ so transient packet loss does not stall pairing.
                    val target = targetPeerIp
                    if (!peerConfirmed && target != null) {
                        if (pairingStartedAtMs > 0L && nowMs - pairingStartedAtMs >= PAIRING_ATTEMPT_TIMEOUT) {
                            targetPeerIp = null
                            pairingStartedAtMs = 0L
                            lastPairReqMs = 0L
                            onStatus?.invoke("Pairing timed out — tap the player to retry")
                        } else if (nowMs - lastPairReqMs >= PAIR_REQ_INTERVAL) {
                            sendPairReq(target)
                            lastPairReqMs = nowMs
                        }
                    }
                }
            } catch (e: BindException) {
                Log.e(TAG, "[HOTSPOT   ] Port $PAIR_PORT busy: ${e.message}")
                onStatus?.invoke("Discovery listener unavailable — retrying automatically")
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "[HOTSPOT   ] Error: ${e.message}", e)
            } finally {
                sessionSocket?.runCatching { close() }
                if (generation.get() == myGeneration) {
                    sendSocket?.runCatching { close() }
                    sendSocket = null
                    sendSocketPinnedToLan = false
                    socket = null
                    running.set(false)
                }
            }
        }.apply {
            name = "LanLink-HotspotPairing"
            isDaemon = true
            start()
        }
    }

    /** User tapped a hotspot peer — begin actively sending a real pairing request. */
    fun initiatePairing(peerIp: String) {
        if (!running.get()) {
            onStatus?.invoke("Discovery is recovering — connection will retry when ready")
            Log.w(TAG, "[HOTSPOT   ] Pair requested while listener was stopped")
            return
        }
        targetPeerIp = peerIp
        peerConfirmed = false
        pairingStartedAtMs = System.currentTimeMillis()
        lastPairReqMs = 0L
        onStatus?.invoke("Connecting to peer…")
        Log.i(TAG, "[HOTSPOT   ] Initiating pairing with $peerIp")

        // start() marks the listener running before the background thread has
        // finished binding its socket. Do not make the user tap twice during
        // that tiny window: remember the target and let the listener loop send
        // PAIR_REQ as soon as the socket exists.
        if (socket != null) {
            sendPairReq(peerIp)
            lastPairReqMs = System.currentTimeMillis()
        }
    }

    /** Cancel only the selected-peer handshake; continuous discovery stays on. */
    fun cancelPendingPairing() {
        targetPeerIp = null
        peerConfirmed = false
        pairingStartedAtMs = 0L
        lastPairReqMs = 0L
        onStatus?.invoke("Connection attempt cancelled")
    }

    /** Called once VPN is up and the tunnel port is known. */
    fun updateLocalPort(port: Int) {
        if (port > 0) localTunnelPort = port
    }

    @Synchronized
    fun stop() {
        running.set(false)
        generation.incrementAndGet()
        targetPeerIp = null
        peerConfirmed = false
        pairingStartedAtMs = 0L
        lastPairReqMs = 0L
        localTunnelPort = DEFAULT_TUNNEL_PORT
        knownPeerIps.toList().forEach { onPeerLost?.invoke(it) }
        knownPeerIps.clear()
        lastSeenMs.clear()
        lastHelloReplyMs.clear()
        socket?.runCatching { close() }
        sendSocket?.runCatching { close() }
        sendSocket = null
        sendSocketPinnedToLan = false
        thread?.interrupt()
        if (Thread.currentThread() !== thread) thread?.runCatching { join(150L) }
        thread = null
    }

    val isRunning get() = running.get()

    private fun handlePacket(pkt: DatagramPacket) {
        val senderIp = pkt.address.hostAddress ?: return
        val json = JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
        val type = json.optString("type")
        val peerDeviceId = json.optString("device_id", "")
        if (peerDeviceId.isNotBlank() && peerDeviceId == myDeviceId) return

        val peerPort = json.optInt("tunnel_port", DEFAULT_TUNNEL_PORT)
        // optString returns "" if the key exists with an empty value (which is
        // exactly what happens when the sender passed the wrong arg order into
        // start() and myName defaulted to ""). Fall back to the sender IP so
        // the UI never shows a nameless peer.
        val rawName = json.optString("user_name", "")
        val peerName = rawName.ifBlank { senderIp }
        lastSeenMs[senderIp] = System.currentTimeMillis()

        when (type) {
            TYPE_HELLO -> {
                publishPeerIfNeeded(senderIp, peerName)
                maybeReplyHelloDirect(senderIp)
            }
            TYPE_PAIR_REQ -> {
                publishPeerIfNeeded(senderIp, peerName)
                if (peerPort > 0) onPeerPortReceived?.invoke(peerPort)
                targetPeerIp = senderIp
                sendPairAck(senderIp)
                confirmPeer(senderIp, fromAck = false)
            }
            TYPE_PAIR_ACK -> {
                publishPeerIfNeeded(senderIp, peerName)
                if (targetPeerIp == senderIp) {
                    if (peerPort > 0) onPeerPortReceived?.invoke(peerPort)
                    confirmPeer(senderIp, fromAck = true)
                }
            }
        }
    }

    private fun publishPeerIfNeeded(peerIp: String, peerName: String) {
        if (!knownPeerIps.contains(peerIp)) {
            knownPeerIps.add(peerIp)
            Log.i(TAG, "[HOTSPOT   ] Peer discovered: $peerName @ $peerIp")
            onPeerDiscovered?.invoke(peerIp, peerName)
            onStatus?.invoke("Found peer: $peerName")
        }
    }

    private fun confirmPeer(peerIp: String, fromAck: Boolean) {
        if (peerConfirmed) return
        peerConfirmed = true
        pairingStartedAtMs = 0L
        lastPairReqMs = 0L
        val msg = if (fromAck) "Peer acknowledged" else "Peer confirmed"
        Log.i(TAG, "[HOTSPOT   ] $msg: $peerIp")
        onStatus?.invoke(msg)
        onPeerReady?.invoke(peerIp)
    }

    private fun expireSilentPeers() {
        val now = System.currentTimeMillis()
        val expired = lastSeenMs.filter { (_, t) -> now - t > PEER_TTL_MS }.keys.toList()
        expired.forEach { ip ->
            lastSeenMs.remove(ip)
            knownPeerIps.remove(ip)
            lastHelloReplyMs.remove(ip)
            if (targetPeerIp == ip && !peerConfirmed) {
                targetPeerIp = null
                pairingStartedAtMs = 0L
                lastPairReqMs = 0L
                onStatus?.invoke("Pairing timed out — the selected player left the local link")
            }
            onPeerLost?.invoke(ip)
            Log.i(TAG, "[HOTSPOT   ] Peer expired: $ip")
        }
    }

    private fun sendHelloBeacon() {
        val json = JSONObject().apply {
            put("type", TYPE_HELLO)
            put("device_id", myDeviceId)
            put("user_name", myUserName)
            put("tunnel_port", localTunnelPort)
        }
        val data = json.toString().toByteArray(Charsets.UTF_8)
        try {
            val tx = sendSocket ?: return
            val subnetBroadcast = directedBroadcast(localIp, localPrefixLength)
            if (subnetBroadcast != null) {
                tx.send(DatagramPacket(data, data.size, InetAddress.getByName(subnetBroadcast), PAIR_PORT))
            }
            if (sendSocketPinnedToLan || subnetBroadcast == null) {
                tx.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), PAIR_PORT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[HOTSPOT   ] HELLO send: ${e.message}")
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

    private fun maybeReplyHelloDirect(senderIp: String) {
        val now = System.currentTimeMillis()
        val last = lastHelloReplyMs[senderIp] ?: 0L
        if (now - last < DIRECT_REPLY_THROTTLE_MS) return
        lastHelloReplyMs[senderIp] = now
        sendPacket(
            targetIp = senderIp,
            type = TYPE_HELLO
        )
    }

    private fun sendPairReq(targetIp: String) {
        sendPacket(targetIp = targetIp, type = TYPE_PAIR_REQ)
    }

    private fun sendPairAck(targetIp: String) {
        sendPacket(targetIp = targetIp, type = TYPE_PAIR_ACK)
    }

    private fun sendPacket(targetIp: String, type: String) {
        try {
            val json = JSONObject().apply {
                put("type", type)
                    put("device_id", myDeviceId)
                put("user_name", myUserName)
                put("tunnel_port", localTunnelPort)
            }
            val data = json.toString().toByteArray(Charsets.UTF_8)
            (sendSocket ?: socket)?.send(DatagramPacket(data, data.size, InetAddress.getByName(targetIp), PAIR_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "[HOTSPOT   ] $type send: ${e.message}")
        }
    }
}
