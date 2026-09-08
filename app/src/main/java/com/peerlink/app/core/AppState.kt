package com.peerlink.app.core

import android.app.Application
import android.content.Context
import android.net.Network
import com.peerlink.app.godmode.PeerLinkAdbManager
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Application class.
 */
class PeerLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Conscrypt MUST be installed first — libadb-android's TLS 1.3 mutual auth
            // silently fails on Android 11+ without it.
            PeerLinkAdbManager.installConscrypt()
        } catch (_: Throwable) {
        }

        try {
            CrashLogger.install(this)
        } catch (_: Throwable) {
        }

        try {
            com.peerlink.app.godmode.GodModeManager.init(this)
        } catch (_: Throwable) {
        }

        // NOTE: We intentionally do NOT auto-start LanLinkForegroundService or
        // request the battery-optimisation exemption from here. On Android 12+
        // (and especially 13/14) the system considers the app "in the
        // background" during Application.onCreate() because no Activity has
        // been created yet. Starting a foreground service from this state
        // throws ForegroundServiceStartNotAllowedException asynchronously
        // when the service calls startForeground(), which crashes the whole
        // process. The Hot 30i (Android 13/14) was crashing exactly here.
        //
        // Instead, both operations are triggered from MainActivity.onCreate()
        // — by that point the app is in the foreground and the system allows
        // foreground-service starts and (where applicable) activity launches.
        // See MainActivity for the call sites.
    }
}

/**
 * Peer connection type — how this peer was discovered.
 */
enum class PeerConnectionType {
    HOTSPOT
}

/**
 * The user-visible connection lifecycle. Discovery and pairing only prove
 * that the phones can see one another; ACTIVE is reserved for a tunnel whose
 * bidirectional data path has been verified.
 */
enum class PeerSessionPhase {
    IDLE,
    PAIRING,
    STARTING_TUNNEL,
    VERIFYING_PATH,
    ACTIVE,
    STOPPING,
    ERROR,
}

/**
 * Peer info for discovery list.
 */
data class PeerInfo(
    val name: String,
    val ip: String,
    val mac: String = "",
    val connectionType: PeerConnectionType = PeerConnectionType.HOTSPOT
)

/**
 * Thread-safe global state for PeerLink.
 */
object AppState {

    // ✅ FABRICATED IPs - Calculated deterministically after pairing
    @Volatile
    var myFabricatedIp: String = "197.210.53.1"
    
    @Volatile
    var peerFabricatedIp: String = "197.210.53.2"

    const val FABRICATED_PUBLIC_IP_BASE = "197.210.53"

    // --- PEER CONNECTION INFO ---
    val peerIp = AtomicReference<InetAddress?>(null)
    val tunnelAdvertisedIp = AtomicReference<String?>(null)
    val peerPort = AtomicInteger(17024)

    // Store our own local IP (detected before VPN starts)
    val localIp = AtomicReference<String?>(null)

    // Exact LAN path locked after the peer is known. Hotspot owners often do
    // not expose their SoftAP as a ConnectivityManager Network, so keep both
    // the kernel interface identity and the optional Android Network.
    val localLanInterfaceName = AtomicReference<String?>(null)
    val localLanInterfaceIndex = AtomicInteger(0)
    val localLanPrefixLength = AtomicInteger(0)
    val localLanNetwork = AtomicReference<Network?>(null)

    /** Active transport mode: "none" or "wifi_udp". */
    val activeTransportMode = AtomicReference("none")

    // Status Flags
    val isRunning = AtomicBoolean(false)
    val isPaired = AtomicBoolean(false)
    val sessionPhase = AtomicReference(PeerSessionPhase.IDLE)
    val sessionError = AtomicReference<String?>(null)
    val sessionStartedElapsedMs = AtomicLong(0L)

    // Packet Counters
    val tunneled = AtomicLong(0)
    val passedThrough = AtomicLong(0)

    // ═══════════════════════════════════════════════════════════════
    //  N E W :  F I E L D S   F O R   H O T S P O T   P A I R I N G
    // ═══════════════════════════════════════════════════════════════

    /** Connection mode: hotspot/LAN pairing. */
    @Volatile
    var connectionMode: String = ""

    /** Device identifier for pairing protocol */
    @Volatile
    var deviceId: String = ""

    @Volatile
    var connectedPeerName: String = ""

    @Volatile
    var connectedPeerIp: String = ""

    // ═══════════════════════════════════════════════════════════════
    //  G O D   M O D E   S T A T E
    // ═══════════════════════════════════════════════════════════════

    /** True while God Mode hardware-lock commands are active. */
    val isGodModeActive = AtomicBoolean(false)

    // PrimeServer (app_process shell daemon) alive state — set on every app open.
    // true  = PrimeServer is running on 127.0.0.1:13373, survived since last reboot.
    // false = rebooted or never bootstrapped — bootstrap flow required before session.
    @Volatile var primeServerAlive: Boolean = false

    /** Human-readable God Mode status, updated by GodModeManager. */
    @Volatile
    var godModeStatusText: String = ""

    // ═══════════════════════════════════════════════════════════════
    //  L O G G I N G
    // ═══════════════════════════════════════════════════════════════

    private val logBuffer = StringBuilder(65536)
    // Cap for the in-memory log buffer. 1MB is plenty for the fallback use cases
    // (crash reports, export fallback). The session FILE is uncapped and is what
    // the export button reads — so this cap does NOT limit what gets saved to
    // Downloads. See appendLog() for why this matters.
    private const val MAX_DISPLAY_LOG = 1_000_000
    private val logLock = Any()
    private val timestampFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    private val fileIoExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PeerLink-Log-IO").apply {
            isDaemon = true
        }
    }

    private var fileWriter: java.io.BufferedWriter? = null
    private var lastFileFlushMs: Long = 0L
    private const val FILE_FLUSH_INTERVAL_MS: Long = 500L
    private var logFile: java.io.File? = null

    fun initFileLog(context: android.content.Context) {
        startNewSession(context)
    }

    // ═══════════════════════════════════════════════════════════════
    //  S E S S I O N   L O G G I N G
    // ═══════════════════════════════════════════════════════════════

    @Volatile
    var currentSessionId: String = ""
        private set

    @Volatile
    var sessionStartTime: Long = 0L
        private set

    fun startNewSession(context: android.content.Context) {
        // Close previous file writer but DO NOT clear the in-memory log buffer.
        // Bootstrap logs (pairing, pm grant, tcpip:5555) are written before VPN
        // starts — wiping here made them invisible in DATA FEED every session.
        closeFileLog()

        currentSessionId = "session_${System.currentTimeMillis()}"
        sessionStartTime = System.currentTimeMillis()

        synchronized(logLock) {
            try {
                val dir = java.io.File(
                    context.getExternalFilesDir(null) ?: context.filesDir, "sessions"
                )
                if (!dir.exists()) dir.mkdirs()

                logFile = java.io.File(dir, "peerlink_$currentSessionId.txt")
                fileWriter = java.io.BufferedWriter(java.io.FileWriter(logFile!!, true), 8192)
                lastFileFlushMs = 0L
            } catch (_: Exception) {}
        }

        appendLog("════ Session started: $currentSessionId ════")
    }

    fun getSessionDurationSeconds(): Long {
        return if (sessionStartTime > 0) (System.currentTimeMillis() - sessionStartTime) / 1000 else 0
    }

    fun getLogFile(): java.io.File? = logFile

    /**
     * Flush the file writer and return the FULL contents of ALL session log
     * files in the sessions directory, sorted oldest-first by modification
     * time. Use this for "Export Match Logs" — the previous implementation
     * read CrashLogger.getLiveLog() which was a 50KB SharedPreferences
     * snapshot, missing most of the actual gameplay-traffic diagnostics
     * (which are written via appendFileOnly and never appear in the
     * in-memory logBuffer).
     *
     * Reading ALL session files means that if the VPN restarted mid-match
     * (creating a new session file), the export still contains the complete
     * history across restarts — no caps, no truncation.
     *
     * Falls back to the in-memory logBuffer only if NO session files exist
     * at all (e.g. export clicked before any session was started).
     */
    fun flushAndReadSessionLog(context: android.content.Context): String {
        // Force-flush the writer so the latest entries are on disk.
        try {
            synchronized(logLock) {
                maybeFlushFileLocked(force = true)
            }
        } catch (_: Exception) {}

        val sb = StringBuilder()

        // Find ALL session files in the sessions directory, sorted oldest-first
        // by last-modified time. This ensures the export has the complete
        // history across VPN restarts within the same play session.
        try {
            val dir = java.io.File(
                context.getExternalFilesDir(null) ?: context.filesDir, "sessions"
            )
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { f -> f.isFile && f.name.startsWith("peerlink_session_") && f.name.endsWith(".txt") }
                    ?.sortedBy { it.lastModified() }
                    ?: emptyList()
                for (f in files) {
                    try {
                        if (sb.isNotEmpty()) {
                            sb.appendLine()
                            sb.appendLine("════════════════════════════════════════════════════════════════")
                            sb.appendLine("  SESSION FILE: ${f.name}")
                            sb.appendLine("════════════════════════════════════════════════════════════════")
                            sb.appendLine()
                        }
                        f.bufferedReader().use { reader ->
                            reader.forEachLine { sb.appendLine(it) }
                        }
                    } catch (_: Exception) {
                        // Skip unreadable files but continue with the rest.
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback to in-memory buffer if no session files gave us anything
        if (sb.isEmpty()) {
            sb.append(getLogs())
        }

        return sb.toString()
    }

    private fun maybeFlushFileLocked(force: Boolean = false) {
        val writer = fileWriter ?: return
        val now = System.currentTimeMillis()
        if (force || now - lastFileFlushMs >= FILE_FLUSH_INTERVAL_MS) {
            try { writer.flush() } catch (_: Exception) {}
            lastFileFlushMs = now
        }
    }

    fun closeFileLog() {
        try {
            fileIoExecutor.submit {
                synchronized(logLock) {
                    try { maybeFlushFileLocked(force = true) } catch (_: Exception) {}
                    try { fileWriter?.close() } catch (_: Exception) {}
                    fileWriter = null
                    lastFileFlushMs = 0L
                }
            }.get(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
            synchronized(logLock) {
                try { maybeFlushFileLocked(force = true) } catch (_: Exception) {}
                try { fileWriter?.close() } catch (_: Exception) {}
                fileWriter = null
                lastFileFlushMs = 0L
            }
        }
    }

    fun resetStats() {
        peerIp.set(null)
        tunnelAdvertisedIp.set(null)
        localIp.set(null)
        localLanInterfaceName.set(null)
        localLanInterfaceIndex.set(0)
        localLanPrefixLength.set(0)
        localLanNetwork.set(null)
        isPaired.set(false)

        activeTransportMode.set("none")

        tunneled.set(0)
        passedThrough.set(0)
        
        myFabricatedIp = "197.210.53.1"
        peerFabricatedIp = "197.210.53.2"
        
        
        connectionMode = ""
        connectedPeerName = ""
        connectedPeerIp = ""
        isGodModeActive.set(false)
        godModeStatusText = ""
        
    }

    fun calculateFabricatedIps(): Boolean {
        val myLanIp = localIp.get()
        val peerLanIp = peerIp.get()?.hostAddress
        
        if (myLanIp == null || peerLanIp == null) {
            appendLog("⚠️ Cannot calculate fabricated IPs - missing LAN IPs (my=$myLanIp, peer=$peerLanIp)")
            return false
        }
        
        val myIpValue = ipToLong(myLanIp)
        val peerIpValue = ipToLong(peerLanIp)
        
        if (myIpValue == 0L || peerIpValue == 0L) {
            appendLog("⚠️ Failed to parse IPs for comparison")
            return false
        }
        
        if (myIpValue < peerIpValue) {
            myFabricatedIp = "197.210.53.1"
            peerFabricatedIp = "197.210.53.2"
        } else {
            myFabricatedIp = "197.210.53.2"
            peerFabricatedIp = "197.210.53.1"
        }
        
        appendLog("✅ Fabricated IPs calculated:")
        appendLog("   My LAN: $myLanIp (value=$myIpValue) → My Fabricated: $myFabricatedIp")
        appendLog("   Peer LAN: $peerLanIp (value=$peerIpValue) → Peer Fabricated: $peerFabricatedIp")
        
        return true
    }
    
    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        if (parts.size != 4) return 0L
        
        return try {
            ((parts[0].toLong() and 0xFF) shl 24) or
            ((parts[1].toLong() and 0xFF) shl 16) or
            ((parts[2].toLong() and 0xFF) shl 8) or
            (parts[3].toLong() and 0xFF)
        } catch (e: Exception) {
            0L
        }
    }

    private fun buildTimestampedLine(message: String): String {
        val timestamp = timestampFormatter.get().format(Date())
        return "> [$timestamp] $message\n"
    }

    private fun enqueueFileWrite(line: String) {
        fileIoExecutor.execute {
            synchronized(logLock) {
                try {
                    fileWriter?.append(line)
                    maybeFlushFileLocked()
                } catch (_: Exception) {}
            }
        }
    }

    fun appendLog(message: String) {
        val line = buildTimestampedLine(message)
        synchronized(logLock) {
            logBuffer.append(line)
            // Cap the in-memory buffer at 1MB. This buffer is ONLY used for:
            //   (a) the DATA FEED admin panel UI (which the user doesn't use)
            //   (b) as a fallback for crash reports
            //   (c) as a fallback for export if the session file is missing
            // The canonical log store is the session FILE (uncapped) — export
            // reads from the file, not from this buffer. So capping the buffer
            // does NOT affect the "no cap on downloads" requirement.
            // Previously this was uncapped, which combined with
            // CrashLogger.persistLiveLog() on every call caused 10MB+ of
            // SharedPreferences I/O per log line — the root cause of the
            // process being killed on Android 15.
            if (logBuffer.length > MAX_DISPLAY_LOG) {
                logBuffer.delete(0, logBuffer.length - MAX_DISPLAY_LOG)
            }
        }
        enqueueFileWrite(line)
        // NOTE: CrashLogger.persistLiveLog() is NO LONGER called here.
        // It was copying the entire log buffer into SharedPreferences on every
        // single log line — with an uncapped buffer that was 10MB+ of XML
        // serialization and disk I/O per call, hundreds of times per second
        // during gameplay. This caused catastrophic memory pressure and was
        // the root cause of Android 15 killing the entire process (VPN service
        // + foreground service + app) mid-match. The session file already
        // captures everything; the SharedPreferences snapshot was only for
        // the admin panel UI the user doesn't use.
    }

    /**
     * Writes a line only to the current session file. Use this for high-volume packet
     * diagnostics that should not flood the connected-view UI.
     */
    fun appendFileOnly(message: String) {
        enqueueFileWrite(buildTimestampedLine(message))
    }

    fun getLogs(): String {
        synchronized(logLock) {
            return logBuffer.toString()
        }
    }

    fun clearLogs() {
        synchronized(logLock) {
            logBuffer.clear()
        }
    }
}
