package com.peerlink.app.tunnel

import android.content.Context
import com.peerlink.app.core.AppState
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * F37 passthrough evidence capture.
 *
 * Records every internet-side (non-game) packet that crosses the bridge —
 * full IP bytes plus flow identity — into one CSV per VPN session, bundled
 * into the match export zip. Purpose: offline study of whether the game
 * keeps a server heartbeat alive while gameplay itself is peer-to-peer,
 * and whether HOME/AWAY or full-time state can be read from that traffic.
 *
 * The recorder stays off the packet path's critical timing: packet threads
 * only copy bytes and enqueue; a single background daemon thread parses,
 * hexes and appends. The queue is bounded and drops are counted, so a
 * capture flood can never stall forwarding.
 *
 * Scope notes (v1): IPv4 passthrough both directions (UDP + TCP outbound,
 * UDP inbound) and IPv6 inbound UDP. IPv6 outbound passthrough and inbound
 * TCP payloads are not recorded yet; match events are still noted so the
 * timeline can be sliced by [PassthroughRecorder.note].
 */
object PassthroughRecorder {

    /** offerToDevice* source tags that carry real internet responses. */
    val RX_SOURCES = setOf("UDP-PASS", "IPv6-UDP-PASS")

    private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    private const val QUEUE_CAPACITY = 4096
    private const val HEX = "0123456789abcdef"

    private sealed class Item {
        class Packet(val dir: Char, val atMs: Long, val bytes: ByteArray) : Item()
        class Event(val atMs: Long, val text: String) : Item()
    }

    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val written = AtomicLong()
    private val dropped = AtomicLong()
    @Volatile private var capped = false
    @Volatile private var outputFile: File? = null
    private var writer: OutputStreamWriter? = null
    private var writerThread: Thread? = null

    /** Fresh file per VPN session; safe to call repeatedly. */
    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        stopping.set(false)
        capped = false
        written.set(0)
        dropped.set(0)
        val folder = File(context.applicationContext.getExternalFilesDir(null) ?: context.filesDir, "passthrough")
        if (!folder.exists()) folder.mkdirs()
        val file = File(folder, "passthrough_capture.csv")
        outputFile = file
        runCatching {
            writer = OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8).apply {
                write("# passthrough_capture v1 — full-byte internet-side (non-game) traffic\r\n")
                write("# dir: t=phone->internet, r=internet->phone; payload_hex = complete IP packet\r\n")
                write("# event lines start with '# event'\r\n")
                write("ts_ms,dir,proto,src,sport,dst,dport,ip_len,payload_hex\r\n")
                flush()
            }
        }.onFailure {
            AppState.appendLog("[PASS-CAP ] init failed: ${it.message}")
        }
        writerThread = Thread({ runWriter() }, "PeerLink-PassCap").apply {
            isDaemon = true
            start()
        }
        AppState.appendLog("[PASS-CAP ] full-byte passthrough capture started (${PassthroughRecorder.statsLine()})")
    }

    /** Outbound internet packet (complete IP packet bytes, reused buffer). */
    fun recordTx(packet: ByteArray, length: Int) = enqueue('t', packet, length)

    /** Inbound internet response (complete IP packet bytes). */
    fun recordRx(packet: ByteArray, length: Int) = enqueue('r', packet, length)

    /** Timeline marker (T0, 0pps cliff, score commit, ...) for offline slicing. */
    fun note(text: String) {
        if (!started.get()) return
        queue.offer(Item.Event(System.currentTimeMillis(), text.replace(Regex("[\\r\\n]"), " ").take(160)))
    }

    /** Flush and return the capture file for the match export zip, if useful. */
    fun snapshotForExport(): File? {
        val file = outputFile ?: return null
        if (dropped.get() > 0L || capped) note("snapshot ${statsLine()} capped=$capped")
        synchronized(this) { runCatching { writer?.flush() } }
        return file.takeIf { it.isFile && it.length() > 200L }
    }

    fun statsLine(): String = "packets=${written.get()} dropped=${dropped.get()}"

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        // Bypasses note()'s started-guard so the end marker still lands.
        queue.offer(Item.Event(System.currentTimeMillis(), "capture_stop"))
        stopping.set(true)
        writerThread?.join(1_500)
        writerThread = null
        synchronized(this) {
            runCatching { writer?.flush(); writer?.close() }
            writer = null
        }
    }

    private fun enqueue(dir: Char, packet: ByteArray, length: Int) {
        if (!started.get() || length <= 0) return
        if (queue.offer(Item.Packet(dir, System.currentTimeMillis(), packet.copyOf(length)))) return
        dropped.incrementAndGet()
    }

    private fun runWriter() {
        val sb = StringBuilder(4_096)
        while (true) {
            val item = try {
                queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (item == null) {
                if (stopping.get() && queue.isEmpty()) break
                continue
            }
            if (capped) {
                if (item is Item.Packet) dropped.incrementAndGet()
                continue
            }
            try {
                synchronized(this) {
                    val w = writer ?: return@synchronized
                    when (item) {
                        is Item.Event -> w.write("# event ${item.atMs} ${item.text}\r\n")
                        is Item.Packet -> {
                            formatPacket(sb, item.dir, item.atMs, item.bytes)
                            w.write(sb.toString())
                            written.incrementAndGet()
                            if (written.get() % 64L == 0L && (outputFile?.length() ?: 0L) > MAX_FILE_BYTES) {
                                capped = true
                                w.write("# event ${System.currentTimeMillis()} capture_capped file>${MAX_FILE_BYTES}\r\n")
                            }
                        }
                    }
                    w.flush()
                }
            } catch (e: Exception) {
                AppState.appendLog("[PASS-CAP ] write failed: ${e.message}")
            }
        }
    }

    private fun formatPacket(sb: StringBuilder, dir: Char, atMs: Long, bytes: ByteArray) {
        val parsed = PacketParser.parse(bytes, bytes.size)
        sb.setLength(0)
        sb.append(atMs).append(',').append(dir).append(',')
        if (parsed.isValid) {
            sb.append(protoName(parsed.protocol)).append(',')
                .append(parsed.sourceIp).append(',').append(parsed.sourcePort).append(',')
                .append(parsed.destIp).append(',').append(parsed.destPort).append(',')
                .append(parsed.totalLength).append(',')
        } else {
            sb.append("raw,?,?,?,?,?,")
        }
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        sb.append("\r\n")
    }

    private fun protoName(protocol: Int): String = when (protocol) {
        PacketParser.PROTOCOL_ICMP -> "icmp"
        PacketParser.PROTOCOL_TCP -> "tcp"
        PacketParser.PROTOCOL_UDP -> "udp"
        else -> protocol.toString()
    }
}
