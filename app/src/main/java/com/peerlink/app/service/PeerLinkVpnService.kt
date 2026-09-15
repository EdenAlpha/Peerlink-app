package com.peerlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.peerlink.app.core.AppState
import com.peerlink.app.core.MatchTracker
import com.peerlink.app.core.PeerSessionPhase
import com.peerlink.app.godmode.GodModeManager
import com.peerlink.app.network.LanPathResolver
import com.peerlink.app.network.LanPath
import com.peerlink.app.network.GameplayPathPolicy
import com.peerlink.app.tunnel.NativeBackendConfig
import com.peerlink.app.tunnel.NativeBackendStats
import com.peerlink.app.tunnel.NativePeerLinkBackend
import com.peerlink.app.tunnel.RawCaptureStats
import com.peerlink.app.tunnel.PassthroughBridgeEngine
import com.peerlink.app.ui.MainActivity
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference
import java.net.DatagramSocket
import java.net.Inet4Address

class PeerLinkVpnService : VpnService() {

    companion object {
        // One owner across service recreation: an old session must finish
        // cleanup before a replacement service can create a new native backend.
        private val vpnLifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PeerLink-VPN-Lifecycle").apply { isDaemon = true }
        }

        const val ACTION_STOP = "com.peerlink.STOP_VPN"
        const val NOTIFICATION_CHANNEL_ID = "lanlink_vpn"
        const val NOTIFICATION_ID = 1
        const val EFOOTBALL_PACKAGE = "jp.konami.pesam"
        // Retry failures on the same path at most once per interval. A newly
        // available exact path can recover immediately.
        const val REBIND_COOLDOWN_MS: Long = 5_000L

        // ── Session-state persistence (for auto-restart after process kill) ──
        // On Android 15 the OS can kill the app process mid-session. START_STICKY
        // restarts the service, but AppState (in-memory) is lost. We persist the
        // session parameters to SharedPreferences so the restart can re-establish
        // the tunnel automatically.
        const val SESSION_STATE_PREFS = "peerlink_session_state"
        const val KEY_PEER_IP = "peer_ip"
        const val KEY_LOCAL_IP = "local_ip"
        const val KEY_CONNECTION_MODE = "connection_mode"
        const val KEY_TRANSPORT_MODE = "transport_mode"
        const val KEY_PEER_PORT = "peer_port"
        const val KEY_WAS_RUNNING = "was_running"
        const val KEY_SAVED_AT_MS = "saved_at_ms"

        @Volatile
        private var activeServiceRef: WeakReference<PeerLinkVpnService>? = null

        @Volatile
        private var lastNativeUdpTraceDump: String = ""

        @Volatile
        private var lastRawCapturePath: String = ""
        @Volatile
        private var lastRawCaptureStats: RawCaptureStats = RawCaptureStats()

        data class RawCaptureExport(
            val file: File?,
            val stats: RawCaptureStats,
        )

        /** Snapshot the binary capture without pausing or stopping the VPN. */
        fun snapshotRawCapture(): RawCaptureExport {
            val active = activeServiceRef?.get()
            val backend = active?.nativeBackend
            val stats = runCatching {
                backend?.flushRawCapture()?.also { lastRawCaptureStats = it } ?: lastRawCaptureStats
            }.getOrDefault(lastRawCaptureStats.copy(writeErrors = lastRawCaptureStats.writeErrors + 1L))
            val path = backend?.let { active.rawCaptureFile?.absolutePath } ?: lastRawCapturePath
            val file = path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
            return RawCaptureExport(file, stats)
        }

        fun dumpNativeUdpTrace(): String {
            val active = activeServiceRef?.get()
            val liveDump = try {
                active?.nativeBackend?.dumpUdpTrace().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (liveDump.isNotBlank()) {
                lastNativeUdpTraceDump = liveDump
                return liveDump
            }
            return lastNativeUdpTraceDump
        }

        fun requestGameplayPathRefresh(reason: String) {
            val service = activeServiceRef?.get() ?: return
            service.postGameplayNetworkEvent { service.refreshGameplayPath(reason) }
        }

        fun prepareAuxDatagramSocket(socket: DatagramSocket, preferGameplayNetwork: Boolean = true): Boolean {
            return activeServiceRef?.get()?.prepareAuxDatagramSocketInternal(socket, preferGameplayNetwork) == true
        }

        fun getActiveNativeBackend(): NativePeerLinkBackend? = activeServiceRef?.get()?.nativeBackend
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    // Keep a separate reference to the ORIGINAL VPN PFD (before detach) so we
    // can explicitly close it in stopVpn(). Previously the PFD was detached
    // and nulled, leaving the system thinking the VPN was still established —
    // the VPN icon stayed in the status bar even though the native backend
    // was dead. The raw FD is closed by nativeStop(), but the SYSTEM tracks
    // the VPN through the ParcelFileDescriptor, not the raw FD.
    private var vpnInterfaceForClose: ParcelFileDescriptor? = null
    @Volatile internal var nativeBackend: NativePeerLinkBackend? = null
    internal var rawCaptureFile: File? = null
    private var passthroughBridgeEngine: PassthroughBridgeEngine? = null
    /**
     * Immutable subset of a LinkProperties callback that identifies the LAN
     * source path. LinkProperties itself does not expose a public copy
     * constructor on every Android SDK, so retaining it would leave the path
     * monitor dependent on a mutable framework object after the callback.
     */
    private data class GameplayLinkSnapshot(
        val interfaceName: String?,
        val localAddresses: List<String>,
    ) {
        companion object {
            fun from(linkProperties: LinkProperties) = GameplayLinkSnapshot(
                interfaceName = linkProperties.interfaceName,
                localAddresses = linkProperties.linkAddresses.mapNotNull { it.address?.hostAddress },
            )
        }
    }

    private val stopVpnGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stopCleanupStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var gameplayMonitorExecutor: ScheduledExecutorService? = null
    private val gameplayRefreshLock = Any()
    private val gameplayPathPolicy = GameplayPathPolicy(REBIND_COOLDOWN_MS)
    @Volatile private var lockedLanPath: LanPath? = null
    @Volatile private var boundGameplayNetwork: Network? = null
    @Volatile private var preparedGameplayNetwork: Network? = null
    private val gameplayLinkProperties = ConcurrentHashMap<Network, GameplayLinkSnapshot>()
    private val lostGameplayNetworks = ConcurrentHashMap.newKeySet<Network>()
    private var gameplayRetry: ScheduledFuture<*>? = null
    private var tunnelWakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLowLatencyLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wifiHighPerformanceLock: android.net.wifi.WifiManager.WifiLock? = null
    private var notificationRefreshExecutor: ScheduledExecutorService? = null
    private val startupExecutor get() = vpnLifecycleExecutor
    private val cleanupComplete = java.util.concurrent.atomic.AtomicBoolean(false)
    private val serviceDestroyed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    // Callback snapshots are processed in order on the path monitor executor.
    private var gameplayNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeServiceRef = WeakReference(this)
        AppState.appendLog("[T-STATE   ] VPN service onStartCommand action=${intent?.action ?: "<none>"} flags=$flags startId=$startId running=${AppState.isRunning.get()}")
        if (intent?.action == ACTION_STOP) {
            // Log whether this STOP came from the notification Stop button
            // (PendingIntent) or from stopVpnService() in MainActivity.
            // The notification Stop button's PendingIntent has no extras, so
            // we can't distinguish by extras — but stopVpnService() now logs
            // its own call stack before sending the intent. If we see that log
            // entry immediately before this one, the source was the UI. If not,
            // the source was the notification Stop button.
            AppState.appendLog("[VPN-STOP  ] STOP_VPN intent received — source: ${if (intent.getStringExtra("source") != null) intent.getStringExtra("source") else "notification Stop button or external"}")
            startupCancelled.set(true)
            stopVpn()
            return START_NOT_STICKY
        }

        if (stopCleanupStarted.get()) {
            AppState.appendLog("[VPN-START ] Previous session is still stopping")
            return START_NOT_STICKY
        }

        // ── Auto-restart recovery ──────────────────────────────────────
        // On Android 15 (and to a lesser extent 13/14) the OS can kill the
        // app process for memory pressure or app-standby enforcement. The
        // VPN service dies with it. START_STICKY causes Android to restart
        // the service, but AppState (in-memory) is lost — peerIp, localIp,
        // peerPort, connectionMode all reset to defaults. Without restoring
        // them, startVpn() would fail with "Peer LAN IP is not set" and the
        // tunnel would never come back. The user sees "VPN was killed" and
        // has to manually reconnect.
        //
        // Fix: if onStartCommand is called with a null intent (which is what
        // happens on a START_STICKY restart — the system has no original
        // intent to redeliver), AND AppState.isRunning was true before the
        // kill (which we persist to SharedPreferences), restore the session
        // state from SharedPreferences so startVpn() can re-establish the
        // tunnel automatically.
        if (intent == null) {
            AppState.appendLog("[T-STATE   ] VPN service restarted by OS (null intent) — attempting session recovery")
            restoreSessionStateIfAvailable()
        }

        createNotificationChannel()
        // Foreground type MUST match manifest declaration: connectedDevice.
        // We tried specialUse for Android 15 hardening but it broke Android 13
        // (Hot 30i) because specialUse was only added in API 34. The VPN
        // service is started by the system VpnService infrastructure (not by
        // the app), so ForegroundServiceStartNotAllowedException doesn't
        // apply here. connectedDevice works on Android 13+ and accurately
        // describes the peer-to-peer tunnel use case.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        MatchMarkerOverlay.show(this)
        // Re-issue the notification every 5 s so Tunneled/Passthrough counters stay live.
        // Without this the notification is built once and never updated — the root cause of
        // "shows but frozen" on Android 12 and "invisible" on Android 13+ when POST_NOTIFICATIONS
        // is granted mid-session.
        if (notificationRefreshExecutor == null || notificationRefreshExecutor!!.isShutdown) {
            notificationRefreshExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "pl-notif-refresh").apply { isDaemon = true }
            }.also { exec ->
                exec.scheduleWithFixedDelay({
                    try {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, buildNotification())
                        MatchMarkerOverlay.show(this)
                    } catch (_: Exception) { }
                }, 5L, 5L, TimeUnit.SECONDS)
            }
        }
        if (!AppState.isRunning.get() && startupInFlight.compareAndSet(false, true)) {
            startupCancelled.set(false)
            AppState.sessionPhase.set(PeerSessionPhase.STARTING_TUNNEL)
            startupExecutor.execute {
                try {
                    startVpn()
                } finally {
                    startupInFlight.set(false)
                }
            }
        }
        // START_STICKY asks Android to restart the service with a null intent
        // if the process is killed by the OS (low-memory / app-standby). When
        // Android restarts it, onStartCommand sees a null intent — our
        // existing guard "if (!AppState.isRunning.get()) startVpn()" then
        // re-establishes the tunnel automatically.
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "PeerLink VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "PeerLink tunnel status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this,
            0,
            Intent(this, PeerLinkVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mainPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val overlayPi = PendingIntent.getActivity(
            this,
            41,
            Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val transportLabel = "Wi-Fi"

        val tunPkts = AppState.tunneled.get()
        val pktsStr = when {
            tunPkts >= 1_000_000L -> "${tunPkts / 1_000_000}M pkts"
            tunPkts >= 1_000L     -> "${tunPkts / 1_000}K pkts"
            else                   -> "$tunPkts pkts"
        }

        val builder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        })
            .setContentTitle(if (AppState.isRunning.get()) "PeerLink Active" else "PeerLink Starting")
            .setContentText("▲▼ $pktsStr · $transportLabel")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (!android.provider.Settings.canDrawOverlays(this)) {
            builder.addAction(android.R.drawable.ic_menu_view, "Enable match marker", overlayPi)
        }
        return builder.build()
    }

    private fun startVpn() {
        if (startupCancelled.get() || stopVpnGuard.get()) return
        AppState.sessionError.set(null)
        AppState.sessionPhase.set(PeerSessionPhase.STARTING_TUNNEL)
        try {
            lastNativeUdpTraceDump = ""
            AppState.initFileLog(this)
            AppState.appendLog("[VPN-START ] PeerLink VPN starting")

            val transportMode = canonicalTransportMode()
            val peerLanIp = AppState.peerIp.get()?.hostAddress
            if (peerLanIp.isNullOrBlank()) {
                throw IllegalStateException("Peer LAN IP is not set (mode=$transportMode)")
            }

            // Resolve the LAN path only after the peer is known. Hotspot owners
            // are often multi-homed; selecting an arbitrary private 10.x address
            // here can accidentally choose cellular instead of the SoftAP.
            val lanPath = LanPathResolver.resolveForPeer(this, peerLanIp)
                ?: throw IllegalStateException("No local LAN interface can reach peer $peerLanIp")
            lockedLanPath = lanPath
            AppState.localIp.set(lanPath.localIp)
            AppState.localLanInterfaceName.set(lanPath.interfaceName)
            AppState.localLanInterfaceIndex.set(lanPath.interfaceIndex)
            AppState.localLanPrefixLength.set(lanPath.prefixLength)
            AppState.localLanNetwork.set(lanPath.androidNetwork)
            AppState.appendLog(
                "[VPN-UNDER ] Locked LAN path ${lanPath.interfaceName}#${lanPath.interfaceIndex} " +
                    "${lanPath.localIp}/${lanPath.prefixLength} -> $peerLanIp " +
                    "(${if (lanPath.androidNetwork != null) "Android Wi-Fi Network" else "kernel/SoftAP"})"
            )

            if (!AppState.calculateFabricatedIps()) {
                throw IllegalStateException("Fabricated IPs are not ready")
            }
            if (startupCancelled.get()) return

            val builder = Builder()
                .setSession("LanLink")
                .setMtu(1400)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00::2", 128)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setBlocking(true)
                .allowBypass()

            try {
                builder.addAllowedApplication(EFOOTBALL_PACKAGE)
                AppState.appendLog("[VPN-START ] Per-app VPN: $EFOOTBALL_PACKAGE only")
            } catch (e: Exception) {
                AppState.appendLog("[VPN-START ] eFootball not installed — capturing all traffic")
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                AppState.appendLog("[T-STATE   ] builder.establish() returned null prepared=${VpnService.prepare(this) == null}")
                AppState.appendLog("[VPN-START ] ERROR: VPN interface establish failed")
                stopVpn()
                AppState.sessionError.set("Android could not create the VPN interface")
                AppState.sessionPhase.set(PeerSessionPhase.ERROR)
                return
            }

            AppState.appendLog("[VPN-START ] Interface established (IPv4 + IPv6)")
            updateUnderlyingNetworks()

            try {
            } catch (_: Exception) {
            }

            // F24: full packet-byte capture is intentionally disabled. Keep the
            // backend path empty so no PCAPNG file or writer is created.
            rawCaptureFile = null
            lastRawCapturePath = ""
            val backendConfig = NativeBackendConfig(
                peerLanIp = peerLanIp,
                peerPort = AppState.peerPort.get(),
                myFabricatedIp = AppState.myFabricatedIp,
                peerFabricatedIp = AppState.peerFabricatedIp,
                localLanIp = lanPath.localIp,
                localInterfaceIndex = lanPath.interfaceIndex,
                rawCapturePath = lastRawCapturePath,
            )

            // Save the PFD reference BEFORE detaching so we can close it
            // later in stopVpn(). detachFd() releases ownership of the raw
            // FD to the native backend, but the Android system still tracks
            // the VPN through this ParcelFileDescriptor. If we don't close
            // it explicitly, the VPN icon stays in the status bar after
            // stopVpn() even though no packets are flowing.
            vpnInterfaceForClose = vpnInterface
            val detachedTunFd = vpnInterface!!.detachFd()
            vpnInterface = null
            lastRawCaptureStats = RawCaptureStats()
            AppState.appendLog("[VPN-START ] TUN detached to native backend")

            nativeBackend = NativePeerLinkBackend(
                config = backendConfig,
                callbacks = object : NativePeerLinkBackend.Callbacks {
                    override fun preparePeerSocket(fdForBinding: Int): Boolean {
                        return prepareNativePeerSocket(fdForBinding)
                    }

                    override fun fabricateStunResponse(packet: ByteArray, length: Int): ByteArray? {
                        return NativePeerLinkBackend.fabricateStunWithExistingLogic(
                            config = backendConfig,
                            packet = packet,
                            length = length,
                        )
                    }

                    override fun onNativeLog(level: Int, message: String, fileOnly: Boolean) {
                        val elevated = !fileOnly || level >= NativePeerLinkBackend.LOG_LEVEL_WARN
                        if (elevated) {
                            AppState.appendLog(message)
                        } else {
                            AppState.appendFileOnly(message)
                        }
                    }

                    override fun onStats(stats: NativeBackendStats) {
                        AppState.tunneled.set(stats.totalTunneledPackets)
                        MatchTracker.noteTunnelStats(stats.tunnelOutPackets, stats.tunnelInPackets)
                        MatchAutomationEngine.onNativeStats(stats)
                        if (!stats.backendRunning && !stopVpnGuard.get()) {
                            AppState.sessionError.set("The packet connection stopped. Reconnect to your player.")
                            AppState.appendLog("[VPN-UNDER ] Native packet workers stopped; closing the VPN session")
                            stopVpn()
                        }
                    }
                },
            )

            val bridgeFd = nativeBackend!!.start(detachedTunFd)
            val bridgePfd = ParcelFileDescriptor.adoptFd(bridgeFd)

            // The native reader is already consuming TUN packets. Start its
            // Internet bridge now, otherwise DNS/login/STUN passthrough waits
            // behind the peer probe (up to 60 seconds) and can time out.
            passthroughBridgeEngine = PassthroughBridgeEngine(
                context = this,
                vpnInterface = bridgePfd,
                protectDatagramSocket = { socket -> protect(socket) },
                protectTcpSocket = { socket -> protect(socket) },
            )
            passthroughBridgeEngine?.start()

            // Discovery/pairing proves only that the phones saw each other.
            // This probe proves the actual native UDP dataplane works both ways.
            // It is intentionally done before declaring the VPN session active.
            AppState.appendLog("[VPN-UNDER ] Verifying bidirectional peer tunnel...")
            AppState.sessionPhase.set(PeerSessionPhase.VERIFYING_PATH)
            if (startupCancelled.get() || stopVpnGuard.get()) {
                throw IllegalStateException("VPN startup cancelled")
            }
            val peerReachable = nativeBackend!!.verifyPeerPath(60_000)
            if (!peerReachable || startupCancelled.get() || stopVpnGuard.get()) {
                // stopVpn() owns the started bridge and closes its descriptor.
                throw IllegalStateException(
                    if (startupCancelled.get()) "VPN startup cancelled"
                    else "PeerLink LAN tunnel probe failed — peer data path is not bidirectionally reachable"
                )
            }
            AppState.appendLog("[VPN-UNDER ] Bidirectional peer tunnel verified")
            AppState.sessionStartedElapsedMs.set(android.os.SystemClock.elapsedRealtime())
            AppState.isRunning.set(true)
            AppState.sessionPhase.set(PeerSessionPhase.ACTIVE)

            // Drain pre-session stats so the tracker's ledger records cite
            // real cumulative counters from the first poll onward.
            MatchTracker.noteTunnelStats(
                nativeBackend!!.latestStats.tunnelOutPackets,
                nativeBackend!!.latestStats.tunnelInPackets,
            )
            MatchTracker.beginSession(
                context = applicationContext,
                opponentName = AppState.connectedPeerName.ifBlank { "Opponent" },
                opponentIp = AppState.connectedPeerIp,
            )
            MatchAutomationEngine.start(applicationContext)
            nativeBackend!!.startPolling()

            startGameplayPathMonitor()

            // Acquire PARTIAL_WAKE_LOCK to prevent Android from suspending the
            // CPU/network stack mid-session. This is the correct API for a
            // background VPN service — LOW_LATENCY requires foreground+screen-on,
            // HIGH_PERF is deprecated at API 34. PARTIAL_WAKE_LOCK keeps the
            // tunnel threads alive through screen-off and power-save events.
            try {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                tunnelWakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "LanLink:TunnelSession"
                ).also { it.acquire() }
                AppState.appendLog("[VPN-START ] PARTIAL_WAKE_LOCK acquired")
            } catch (e: Exception) {
                AppState.appendLog("[VPN-START ] Wake lock acquire failed: ${e.message}")
            }

            // A foreground SERVICE does not make this app the foreground app
            // when the player switches to the game. LOW_LATENCY is only a
            // request; isHeld does not prove radio power saving is disabled.
            // Before Android 14 a HIGH_PERF lock remains effective in the
            // background. Android 14+ maps it to LOW_LATENCY, so requesting
            // both there cannot provide a background fallback.
            if (Build.VERSION.SDK_INT < 34 && canonicalTransportMode() == "wifi_udp") {
                try {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                    @Suppress("DEPRECATION")
                    val lock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PeerLink:GameWifi")
                    lock.setReferenceCounted(false)
                    lock.acquire()
                    wifiHighPerformanceLock = lock
                    AppState.appendLog("[VPN-START ] Background Wi-Fi performance lock requested (Android < 14)")
                } catch (e: Exception) {
                    AppState.appendLog("[VPN-START ] Background Wi-Fi lock unavailable: ${e.message}")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                canonicalTransportMode() == "wifi_udp") {
                try {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                    wifiLowLatencyLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "LanLink:LowLatency"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    AppState.appendLog("[VPN-START ] WIFI_MODE_FULL_LOW_LATENCY requested; effectiveness depends on foreground state and device support")
                } catch (e: Exception) {
                    AppState.appendLog("[VPN-START ] Wi-Fi low-latency lock failed: ${e.message}")
                }
            }

            // Native code already applies safe URGENT_DISPLAY priority. The
            // optional SCHED_FIFO path below self-disables unless the command
            // identity is genuinely root; stock phones are fully supported.
            try {
                GodModeManager.promoteHotThreads { nativeBackend?.hotThreadTids() ?: IntArray(0) }
            } catch (_: Exception) {
            }

            AppState.appendLog("[VPN-START ] Native datapath active — passthrough bridge ready")
            AppState.appendLog("[VPN-TRACE ] UDP trace armed (save-only, stages=6, udp-only)")
            AppState.appendLog("[VPN-START ] Tunnel active — waiting for game traffic")

            // Persist session state so START_STICKY restart can re-establish
            // the tunnel automatically if the OS kills the process (Android 15
            // app-standby, low-memory, etc.). Without this, the restart would
            // find AppState empty and fail with "Peer LAN IP is not set".
            saveSessionState()
        } catch (e: Exception) {
            AppState.appendLog("[VPN-START ] Start error: ${e.message}")
            stopVpn()
            if (!startupCancelled.get()) {
                AppState.sessionError.set(e.message ?: "Peer tunnel setup failed")
                AppState.sessionPhase.set(PeerSessionPhase.ERROR)
            }
        }
    }

    private fun prepareAuxDatagramSocketInternal(socket: DatagramSocket, preferGameplayNetwork: Boolean): Boolean {
        val protectedOk = runCatching { protect(socket) }.getOrElse {
            AppState.appendLog("[VPN-UNDER ] Aux socket protect error: ${it.message}")
            false
        }
        if (!protectedOk) return false

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val target = if (preferGameplayNetwork) {
            resolveExactGameplayNetwork(cm, canonicalTransportMode())
        } else {
            resolveExactGameplayNetwork(cm, canonicalTransportMode())
        }
        if (target == null) return true

        return runCatching {
            target.bindSocket(socket)
            true
        }.getOrElse {
            AppState.appendLog("[VPN-UNDER ] Aux socket bind error: ${it.message}")
            false
        }
    }

    private fun prepareNativePeerSocket(fdForBinding: Int): Boolean {
        // Native owns the passed descriptor. fromFd duplicates it so every JNI
        // success/exception path has explicit ownership and closes exactly once.
        return try {
            ParcelFileDescriptor.fromFd(fdForBinding).use { pfd ->
                if (!protect(pfd.fd)) {
                    AppState.appendLog("[VPN-UNDER ] Native peer socket protection failed")
                    false
                } else {
                    bindSocketFdToPreferredNetwork(pfd.fileDescriptor)
                }
            }
        } catch (e: Exception) {
            AppState.appendLog("[VPN-UNDER ] Native socket preparation failed: ${e.message}")
            false
        }
    }

    private fun isUsableUnderlay(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        val iface = try { cm.getLinkProperties(network)?.interfaceName } catch (_: Exception) { null }
        if (!iface.isNullOrBlank()) {
            val lowered = iface.lowercase()
            if (lowered.startsWith("tun") || lowered.contains("vpn")) return false
        }
        return true
    }

    private fun canonicalTransportMode(): String {
        val active = AppState.activeTransportMode.get()
        if (active != "none") return active
        return when (AppState.connectionMode) {
            "hotspot" -> "wifi_udp"
            else -> "none"
        }
    }

    private fun matchesLockedGameplayNetwork(cm: ConnectivityManager, network: Network): Boolean {
        val path = lockedLanPath ?: return false
        if (network in lostGameplayNetworks || !isUsableUnderlay(cm, network)) return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val lp = gameplayLinkProperties[network]
            ?: cm.getLinkProperties(network)?.let(GameplayLinkSnapshot::from)
            ?: return false
        return GameplayPathPolicy.matchesLockedPath(
            path.localIp, path.interfaceName, lp.interfaceName,
            lp.localAddresses,
        )
    }

    private fun resolveExactGameplayNetwork(cm: ConnectivityManager, transportMode: String): Network? {
        if (transportMode != "wifi_udp") return null
        val path = lockedLanPath ?: return null
        if (!LanPathResolver.isStillAvailable(path)) return null
        // Keep the existing bind while its actual IPv4 address/interface exist.
        // An old Network object remaining queryable is insufficient.
        boundGameplayNetwork?.let { if (matchesLockedGameplayNetwork(cm, it)) return it }
        path.androidNetwork?.let { if (matchesLockedGameplayNetwork(cm, it)) return it }
        val matches = (cm.allNetworks.toList() + gameplayLinkProperties.keys).distinct()
            .filter { matchesLockedGameplayNetwork(cm, it) }
        return matches.singleOrNull() ?: cm.activeNetwork?.takeIf { it in matches }
    }

    private fun isKernelGameplayPathAvailable(): Boolean {
        val path = lockedLanPath ?: return false
        // A station that lost its Network cannot become a SoftAP owner merely
        // because its old interface index is still saved.
        return path.isHotspotOwnerStyle && LanPathResolver.isStillAvailable(path)
    }

    private fun bindSocketFdToPreferredNetwork(fileDescriptor: FileDescriptor): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val exactNetwork = resolveExactGameplayNetwork(cm, canonicalTransportMode())
        if (exactNetwork == null) {
            if (isKernelGameplayPathAvailable()) {
                preparedGameplayNetwork = null
                AppState.appendLog("[VPN-UNDER ] Protected native socket uses the live source-bound hotspot path")
                return true
            }
            AppState.appendLog("[VPN-UNDER ] Exact Wi-Fi source path unavailable")
            return false
        }
        return try {
            exactNetwork.bindSocket(fileDescriptor)
            preparedGameplayNetwork = exactNetwork
            AppState.appendLog("[VPN-UNDER ] Native socket bound to ${describeNetwork(cm, exactNetwork)}")
            true
        } catch (e: Exception) {
            AppState.appendLog("[VPN-UNDER ] Exact gameplay bind failed: ${e.message}")
            false
        }
    }

    private fun gameplayNetworkIdentity(network: Network?): String? {
        val path = lockedLanPath ?: return null
        return network?.let {
            GameplayPathPolicy.identity(it.toString(), path.interfaceName, path.localIp)
        }
    }

    /** Remember the socket that the successful bidirectional probe actually used. */
    private fun rememberVerifiedGameplayPath() {
        synchronized(gameplayRefreshLock) {
            boundGameplayNetwork = preparedGameplayNetwork
            gameplayPathPolicy.rememberBound(gameplayNetworkIdentity(boundGameplayNetwork))
            updateUnderlyingNetworks()
            AppState.appendFileOnly("[VPN-UNDER ] Verified startup path retained without redundant rebind")
        }
    }

    private fun startGameplayPathMonitor() {
        stopGameplayPathMonitor()
        if (canonicalTransportMode() != "wifi_udp") return
        // Reset an old monitor first; never erase the just-verified identity.
        rememberVerifiedGameplayPath()
        gameplayMonitorExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PeerLink-GameplayPath").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                { runCatching { refreshGameplayPath("monitor") } },
                10L, 10L, TimeUnit.SECONDS,
            )
        }
        registerGameplayNetworkCallback()
    }

    private fun stopGameplayPathMonitor() {
        unregisterGameplayNetworkCallback()
        val executor = gameplayMonitorExecutor
        gameplayMonitorExecutor = null
        executor?.shutdownNow()
        if (executor != null && Thread.currentThread().name != "PeerLink-GameplayPath") {
            runCatching { executor.awaitTermination(2L, TimeUnit.SECONDS) }
        }
        synchronized(gameplayRefreshLock) {
            gameplayRetry?.cancel(false)
            gameplayRetry = null
            gameplayPathPolicy.markLost()
            boundGameplayNetwork = null
            gameplayLinkProperties.clear()
            lostGameplayNetworks.clear()
        }
    }

    private fun postGameplayNetworkEvent(action: () -> Unit) {
        val executor = gameplayMonitorExecutor ?: return
        // A callback can already be queued when Android unregisters it. A
        // shutdown race must neither crash the callback thread nor rebind.
        runCatching {
            executor.execute {
                if (gameplayMonitorExecutor !== executor || stopVpnGuard.get() || !AppState.isRunning.get()) return@execute
                runCatching { action() }.onFailure {
                    AppState.appendLog("[VPN-UNDER ] Path callback failed: ${it.message}")
                }
            }
        }
    }

    private fun registerGameplayNetworkCallback() {
        if (gameplayNetworkCallback != null) return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // API 26+ follows this with capabilities and LinkProperties.
                    // Do not query incomplete state or reconnect here.
                    postGameplayNetworkEvent {
                        lostGameplayNetworks.remove(network)
                        gameplayLinkProperties.remove(network)
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val snapshot = GameplayLinkSnapshot.from(linkProperties)
                    postGameplayNetworkEvent {
                        gameplayLinkProperties[network] = snapshot
                        lostGameplayNetworks.remove(network)
                        refreshGameplayPath("wifi-address-ready")
                    }
                }

                override fun onLost(network: Network) {
                    postGameplayNetworkEvent {
                        synchronized(gameplayRefreshLock) {
                            gameplayLinkProperties.remove(network)
                            lostGameplayNetworks.add(network)
                            if (network == boundGameplayNetwork) {
                                boundGameplayNetwork = null
                                gameplayPathPolicy.markLost()
                                AppState.appendLog("[VPN-UNDER ] Gameplay Wi-Fi lost; waiting for the exact source path")
                                updateUnderlyingNetworks()
                            }
                        }
                        refreshGameplayPath("wifi-lost")
                    }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // RSSI/validation changes do not alter the IPv4 socket bind.
                }
            }
            cm.registerNetworkCallback(request, cb)
            gameplayNetworkCallback = cb
        } catch (e: Exception) {
            AppState.appendLog("[VPN-UNDER ] Network callback registration failed: ${e.message}")
        }
    }

    private fun unregisterGameplayNetworkCallback() {
        val cb = gameplayNetworkCallback ?: return
        gameplayNetworkCallback = null
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
        }
    }

    private fun scheduleGameplayRetry(delayMs: Long) {
        if (gameplayRetry?.isDone == false) return
        val executor = gameplayMonitorExecutor ?: return
        gameplayRetry = runCatching {
            executor.schedule({
                synchronized(gameplayRefreshLock) { gameplayRetry = null }
                runCatching { refreshGameplayPath("retry") }
            }, delayMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun refreshGameplayPath(reason: String): Boolean {
        synchronized(gameplayRefreshLock) {
            if (stopVpnGuard.get() || !AppState.isRunning.get()) return false
            val backend = nativeBackend ?: return false
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = resolveExactGameplayNetwork(cm, canonicalTransportMode())
            if (network == null) {
                if (isKernelGameplayPathAvailable()) return true
                gameplayPathPolicy.markLost()
                updateUnderlyingNetworks()
                AppState.appendFileOnly("[VPN-UNDER ] Path unavailable ($reason): waiting for the locked IPv4/interface; a changed LAN address needs a new pairing")
                return false
            }
            val identity = gameplayNetworkIdentity(network) ?: return false
            if (!gameplayPathPolicy.needsRebind(identity)) return true
            val delay = gameplayPathPolicy.retryDelayMs(identity, SystemClock.elapsedRealtime())
            if (delay > 0L) {
                scheduleGameplayRetry(delay)
                return false
            }
            val rebound = runCatching { backend.rebindPeerSocket() }.getOrDefault(false)
            // The callback may observe a newer snapshot; record the network it
            // actually bound, rather than the network seen before the JNI call.
            val actualNetwork = preparedGameplayNetwork
            gameplayPathPolicy.recordAttempt(identity, SystemClock.elapsedRealtime(), rebound)
            if (rebound) {
                boundGameplayNetwork = actualNetwork
                gameplayPathPolicy.rememberBound(gameplayNetworkIdentity(actualNetwork))
                gameplayRetry?.cancel(false)
                gameplayRetry = null
                updateUnderlyingNetworks()
                AppState.appendLog("[VPN-UNDER ] Gameplay socket rebound ($reason)")
            } else {
                scheduleGameplayRetry(REBIND_COOLDOWN_MS)
                AppState.appendLog("[VPN-UNDER ] Gameplay socket rebind failed ($reason); retry scheduled")
            }
            return rebound
        }
    }

    private fun stopVpn(clearSavedSession: Boolean = true) {
        if (!stopVpnGuard.compareAndSet(false, true)) return
        AppState.isRunning.set(false)
        AppState.sessionPhase.set(PeerSessionPhase.STOPPING)
        MatchAutomationEngine.stop()
        MatchMarkerOverlay.hide()
        // Clear the saved session state — the user deliberately stopped the
        // session, so we don't want a START_STICKY restart to bring it back.
        if (clearSavedSession) clearSessionState()
        notificationRefreshExecutor?.shutdownNow()
        notificationRefreshExecutor = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {
        }

        if (!stopCleanupStarted.compareAndSet(false, true)) return

        Thread({ runCatching { nativeBackend?.requestStop() } }, "PeerLink-VPN-Cancel").apply {
            isDaemon = true
            start()
        }
        // Startup and cleanup share one owner. Stop cannot detach or free a
        // backend that startup is still constructing or verifying.
        startupExecutor.execute {
            stopGameplayPathMonitor()
            val backend = nativeBackend
            nativeBackend = null
            val bridge = passthroughBridgeEngine
            passthroughBridgeEngine = null
            val iface = vpnInterface
            vpnInterface = null
            // Capture the original PFD reference that we saved before detaching.
            // This is what the Android system uses to track the VPN interface —
            // closing it is what makes the VPN icon disappear from the status bar.
            val vpnPfdForClose = vpnInterfaceForClose
            vpnInterfaceForClose = null

            runCatching { backend?.requestStop() }
            lastNativeUdpTraceDump = try {
                backend?.dumpUdpTrace().orEmpty().ifBlank { lastNativeUdpTraceDump }
            } catch (_: Exception) {
                lastNativeUdpTraceDump
            }
            AppState.appendLog(
                "[F24-TRACE ] UDP timing metadata cached chars=${lastNativeUdpTraceDump.length}; raw packet bytes=off"
            )

            // A teardown itself is never treated as full time by MatchTracker.
            runCatching { MatchTracker.endSession("disconnect") }

            try {
                GodModeManager.onVpnStopped()
            } catch (e: Exception) {
                AppState.appendLog("[VPN-STOP  ] GodMode stop error (non-fatal): ${e.message}")
            }

            try {
                bridge?.stop()
            } catch (e: Exception) {
                AppState.appendLog("[VPN-STOP  ] Bridge engine stop error (non-fatal): ${e.message}")
            }

            try {
                backend?.stop()
            } catch (e: Exception) {
                AppState.appendLog("[VPN-STOP  ] Native backend stop error (non-fatal): ${e.message}")
            }

            try {
                iface?.close()
            } catch (e: Exception) {
                AppState.appendLog("[VPN-STOP  ] Interface close error (non-fatal): ${e.message}")
            }

            // ── CRITICAL: Close the original VPN ParcelFileDescriptor ──
            // The native backend already closed the raw FD, but the Android
            // system tracks the VPN interface through the ParcelFileDescriptor
            // object. Without closing this, the VPN icon stays in the status
            // bar after stopVpn() — the user sees the icon and thinks the VPN
            // is still running, but no packets flow because the native backend
            // is dead. This was the root cause of "VPN icon still showing but
            // stuck at 5 packets."
            //
            // Note: detachFd() was called, so the PFD no longer owns the FD.
            // Calling close() on a detached PFD is safe — it's a no-op on the
            // FD itself but signals to the system that the VPN interface is
            // gone. On some Android versions the PFD.close() after detach
            // throws, so we catch and ignore.
            try {
                vpnPfdForClose?.close()
                if (vpnPfdForClose != null) {
                    AppState.appendLog("[VPN-STOP  ] VPN ParcelFileDescriptor closed — VPN icon should disappear")
                }
            } catch (e: Exception) {
                // Expected on some Android versions after detachFd(). The
                // important thing is that we attempted to close it, which
                // signals to the system that the VPN is gone.
                AppState.appendLog("[VPN-STOP  ] VPN PFD close (post-detach) — non-fatal: ${e.message}")
            }

            // Release wake lock acquired at tunnel start.
            try {
                val wl = tunnelWakeLock
                tunnelWakeLock = null
                if (wl?.isHeld == true) {
                    wl.release()
                    AppState.appendLog("[VPN-STOP  ] PARTIAL_WAKE_LOCK released")
                }
            } catch (e: Exception) {
                AppState.appendLog("[VPN-STOP  ] Wake lock release error (non-fatal): ${e.message}")
            }

            // Release the Wi-Fi low-latency lock acquired at tunnel start.
            try {
                val lock = wifiHighPerformanceLock
                wifiHighPerformanceLock = null
                if (lock?.isHeld == true) lock.release()
            } catch (e: Exception) {
                AppState.appendLog("[VPN-STOP  ] Background Wi-Fi lock release error: ${e.message}")
            }
            try {
                val wfl = wifiLowLatencyLock
                wifiLowLatencyLock = null
                if (wfl?.isHeld == true) {
                    wfl.release()
                    AppState.appendLog("[VPN-STOP  ] WIFI_MODE_FULL_LOW_LATENCY lock released")
                }
            } catch (e: Exception) {
                AppState.appendLog("[VPN-STOP  ] Wi-Fi low-latency lock release error (non-fatal): ${e.message}")
            }

            AppState.appendLog("[T-STATE   ] stopVpn completed guard=${stopVpnGuard.get()}")
            AppState.appendLog("[VPN-STOP  ] VPN stopped cleanly")
            AppState.closeFileLog()

            AppState.sessionStartedElapsedMs.set(0L)
            cleanupComplete.set(true)
            finishStoppedUiIfReady()
            runCatching { stopSelf() }
        }
    }

    private fun finishStoppedUiIfReady() {
        if (cleanupComplete.get() && serviceDestroyed.get()) {
            AppState.sessionPhase.compareAndSet(PeerSessionPhase.STOPPING, PeerSessionPhase.IDLE)
        }
    }

    private fun updateUnderlyingNetworks() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val transportMode = canonicalTransportMode()

            val ordered = LinkedHashSet<Network>()
            val exactGameplay = resolveExactGameplayNetwork(cm, transportMode)

            exactGameplay?.let { ordered.add(it) }

            val kernelPinnedHotspotPath = transportMode == "wifi_udp" &&
                exactGameplay == null && isKernelGameplayPathAvailable()
            if (kernelPinnedHotspotPath) {
                // SoftAP owner: the peer UDP socket is independently pinned in
                // native. Keep the VPN's ordinary Internet passthrough on the
                // system default so eFootball auth/matchmaking can still use
                // cellular/available Internet.
                runCatching { setUnderlyingNetworks(null) }
                AppState.appendFileOnly("[VPN-UNDER ] Hotspot-owner kernel path locked; VPN Internet underlay left on system default")
                return
            }

            if (transportMode != "none" && ordered.isEmpty()) {
                // The exact gameplay network (e.g. the specific Wi-Fi we were
                // paired on) is no longer available. Previously we kept the OLD
                // underlying-networks list, which meant the VPN kept pointing
                // at a dead Network object and packets went nowhere — this is
                // what caused eFootball to detect a network drop and kick both
                // players mid-match. Fall back to the system default instead:
                // pass null to setUnderlyingNetworks(), which makes the VPN
                // route through whatever the OS considers the current default
                // network (often the same Wi-Fi once it re-appears, or
                // cellular). Even if the new underlay isn't the right one for
                // the session, at least the VPN interface stays up and the
                // session can potentially recover when Wi-Fi returns.
                AppState.appendLog("[VPN-UNDER ] mode=$transportMode exact gameplay network gone — falling back to system default underlay")
                runCatching { setUnderlyingNetworks(null) }
                return
            }

            setUnderlyingNetworks(ordered.takeIf { it.isNotEmpty() }?.toTypedArray())
            AppState.appendLog("[VPN-UNDER ] mode=$transportMode ${if (ordered.isEmpty()) "Using system default underlay" else "Underlying networks: ${ordered.joinToString { describeNetwork(cm, it) }}"}")
        } catch (e: Exception) {
            AppState.appendLog("[VPN-UNDER ] Failed to set underlying networks: ${e.message}")
        }
    }

    /**
     * Describe a network for logging (show transport type + interface name).
     */
    private fun describeNetwork(cm: ConnectivityManager, network: Network): String {
        val caps = cm.getNetworkCapabilities(network)
        val lp = cm.getLinkProperties(network)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cell"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> "?"
        }
        return "$transport:${lp?.interfaceName ?: network}"
    }

    /**
     * Persist the current session state to SharedPreferences so that if the
     * OS kills the process (Android 15 app-standby, low-memory), the START_STICKY
     * restart can re-establish the tunnel automatically without user intervention.
     *
     * Called from startVpn() after the tunnel is successfully established.
     */
    private fun saveSessionState() {
        try {
            val peerIpStr = AppState.peerIp.get()?.hostAddress ?: return
            val localIpStr = AppState.localIp.get() ?: return
            val mode = AppState.connectionMode
            if (mode.isBlank()) return
            val transport = AppState.activeTransportMode.get()
            val port = AppState.peerPort.get()

            val prefs = getSharedPreferences(SESSION_STATE_PREFS, MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PEER_IP, peerIpStr)
                .putString(KEY_LOCAL_IP, localIpStr)
                .putString(KEY_CONNECTION_MODE, mode)
                .putString(KEY_TRANSPORT_MODE, transport)
                .putInt(KEY_PEER_PORT, port)
                .putBoolean(KEY_WAS_RUNNING, true)
                .putLong(KEY_SAVED_AT_MS, System.currentTimeMillis())
                .commit()  // synchronous — must complete before any potential kill
            AppState.appendLog("[T-STATE   ] Session state persisted for auto-restart (peer=$peerIpStr local=$localIpStr mode=$mode)")
        } catch (e: Exception) {
            AppState.appendLog("[T-STATE   ] Failed to persist session state: ${e.message}")
        }
    }

    /**
     * Restore session state from SharedPreferences if available. Called from
     * onStartCommand when intent is null (START_STICKY restart after kill).
     * Restores peerIp, localIp, peerPort, connectionMode, activeTransportMode
     * into AppState so that startVpn() can re-establish the tunnel.
     */
    private fun restoreSessionStateIfAvailable() {
        try {
            val prefs = getSharedPreferences(SESSION_STATE_PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_WAS_RUNNING, false)) {
                AppState.appendLog("[T-STATE   ] No saved session state — not auto-restarting")
                return
            }
            val savedAt = prefs.getLong(KEY_SAVED_AT_MS, 0L)
            val ageMs = System.currentTimeMillis() - savedAt
            // If the saved state is older than 30 minutes, the peer has almost
            // certainly gone away by now — don't try to auto-restart into a
            // stale session.
            if (ageMs > 30 * 60 * 1000L) {
                AppState.appendLog("[T-STATE   ] Saved session state is ${ageMs / 1000}s old — too stale, discarding")
                clearSessionState()
                return
            }
            val peerIpStr = prefs.getString(KEY_PEER_IP, null) ?: return
            val localIpStr = prefs.getString(KEY_LOCAL_IP, null) ?: return
            val mode = prefs.getString(KEY_CONNECTION_MODE, null) ?: return
            val transport = prefs.getString(KEY_TRANSPORT_MODE, "none") ?: "none"
            val port = prefs.getInt(KEY_PEER_PORT, 17024)

            // Restore into AppState
            AppState.peerIp.set(java.net.InetAddress.getByName(peerIpStr))
            AppState.localIp.set(localIpStr)
            AppState.connectionMode = mode
            AppState.activeTransportMode.set(transport)
            AppState.peerPort.set(port)
            AppState.isPaired.set(true)
            // isRunning is intentionally NOT set here — startVpn() will set it
            // if the re-establish succeeds.
            AppState.appendLog("[T-STATE   ] Session state restored — peer=$peerIpStr local=$localIpStr mode=$mode transport=$transport")
        } catch (e: Exception) {
            AppState.appendLog("[T-STATE   ] Failed to restore session state: ${e.message}")
        }
    }

    /**
     * Clear the saved session state. Called from stopVpn() when the user
     * deliberately ends the session — we don't want auto-restart to bring
     * it back.
     */
    private fun clearSessionState() {
        try {
            getSharedPreferences(SESSION_STATE_PREFS, MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        } catch (_: Exception) {}
    }

    override fun onRevoke() {
        // onRevoke fires when the user toggles "Always-on VPN" off, revokes
        // our VPN permission, or starts a different VPN. We currently treat
        // this as a stop — but DON'T call stopSelf, because Android may
        // re-grant us the VPN moments later (e.g. user toggling back).
        // START_STICKY means we'll be restarted; the next onStartCommand
        // will re-establish the tunnel if AppState.isRunning is still true.
        AppState.appendLog("[T-STATE   ] VPN service onRevoke — tearing down tunnel but staying alive")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        AppState.appendLog("[T-STATE   ] VPN service onDestroy")
        if (activeServiceRef?.get() === this) activeServiceRef = null
        startupCancelled.set(true)
        serviceDestroyed.set(true)
        // Preserve recovery preferences for system-driven recreation; explicit
        // Stop already cleared them. Do not discard a queued native cleanup.
        stopVpn(clearSavedSession = false)
        super.onDestroy()
        finishStoppedUiIfReady()
    }
}
