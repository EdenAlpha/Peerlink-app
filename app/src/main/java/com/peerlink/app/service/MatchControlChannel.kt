package com.peerlink.app.service

import com.peerlink.app.core.AppState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny out-of-band peer control channel used only for match metadata such as
 * Home/Away selection. It deliberately does not share the eFootball tunnel
 * port, so these control packets can never enter the gameplay data plane.
 */
object MatchControlChannel {
    private const val PORT = 17025
    private const val MAGIC = "PLM1"
    private const val MAX_PACKET = 160

    enum class Side(val wire: String) {
        HOME("H"),
        AWAY("A");

        fun opposite(): Side = if (this == HOME) AWAY else HOME

        companion object {
            fun fromWire(value: String): Side? = when (value) {
                "H" -> HOME
                "A" -> AWAY
                else -> null
            }
        }
    }

    interface Listener {
        fun onPeerRole(side: Side, confirmed: Boolean)
        fun onPeerRoleReset()
        fun onPeerTopology(topology: String)
        fun onPeerForfeit(reason: String)
    }

    private val running = AtomicBoolean(false)
    @Volatile private var listener: Listener? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var io: ScheduledExecutorService? = null

    fun start(listener: Listener) {
        this.listener = listener
        if (!running.compareAndSet(false, true)) return

        val executor = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "PeerLink-Match-Control").apply { isDaemon = true }
        }
        io = executor
        executor.execute { receiveLoop() }
        AppState.appendLog("[MATCH-CTRL] Peer match-control channel starting on UDP/$PORT")
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        io?.shutdownNow()
        io = null
        listener = null
    }

    fun sendRole(side: Side, confirmed: Boolean) {
        sendReliable("ROLE|${side.wire}|${if (confirmed) 1 else 0}")
    }

    fun sendReset() {
        sendReliable("RESET")
    }

    fun sendTopology(topology: String) {
        sendReliable("TOPO|${topology.replace('|', '_').take(24)}")
    }

    fun sendForfeit(reason: String) {
        val safe = reason.replace('|', '_').take(48)
        sendReliable("FORFEIT|$safe")
    }

    private fun sendReliable(body: String) {
        val executor = io ?: return
        // The control packet is tiny; three sends spaced across 140 ms are
        // cheaper than a full ACK protocol and make a missed role tap very
        // unlikely on a local Wi-Fi link.
        longArrayOf(0L, 60L, 140L).forEach { delayMs ->
            executor.schedule({ sendOnce(body) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun sendOnce(body: String) {
        if (!running.get()) return
        val peer = AppState.peerIp.get() ?: AppState.connectedPeerIp
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            ?: return
        val active = socket ?: return
        val bytes = "$MAGIC|$body".toByteArray(StandardCharsets.UTF_8)
        runCatching {
            synchronized(active) {
                active.send(DatagramPacket(bytes, bytes.size, peer, PORT))
            }
        }.onFailure {
            AppState.appendFileOnly("[MATCH-CTRL] send failed: ${it.message}")
        }
    }

    private fun receiveLoop() {
        var localSocket: DatagramSocket? = null
        try {
            localSocket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1_000
            }
            if (!PeerLinkVpnService.prepareAuxDatagramSocket(localSocket, preferGameplayNetwork = true)) {
                AppState.appendLog("[MATCH-CTRL] Could not protect/bind match-control socket")
                return
            }
            val localIp = AppState.localIp.get()
                ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            localSocket.bind(InetSocketAddress(localIp, PORT))
            socket = localSocket
            AppState.appendLog("[MATCH-CTRL] Ready local=${localSocket.localAddress.hostAddress}:$PORT")

            val buffer = ByteArray(MAX_PACKET)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    localSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (!isExpectedPeer(packet.address)) continue
                val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                handle(text)
            }
        } catch (e: Exception) {
            if (running.get()) AppState.appendLog("[MATCH-CTRL] receive loop failed: ${e.message}")
        } finally {
            if (socket === localSocket) socket = null
            runCatching { localSocket?.close() }
        }
    }

    private fun isExpectedPeer(address: InetAddress): Boolean {
        val expected = AppState.peerIp.get()?.hostAddress ?: AppState.connectedPeerIp
        return expected.isBlank() || address.hostAddress == expected
    }

    private fun handle(text: String) {
        val parts = text.split('|')
        if (parts.isEmpty() || parts[0] != MAGIC) return
        when (parts.getOrNull(1)) {
            "ROLE" -> {
                val side = Side.fromWire(parts.getOrNull(2).orEmpty()) ?: return
                val confirmed = parts.getOrNull(3) == "1"
                listener?.onPeerRole(side, confirmed)
            }
            "RESET" -> listener?.onPeerRoleReset()
            "TOPO" -> listener?.onPeerTopology(parts.getOrNull(2).orEmpty())
            "FORFEIT" -> listener?.onPeerForfeit(parts.getOrNull(2).orEmpty().ifBlank { "peer_forfeit" })
        }
    }
}
