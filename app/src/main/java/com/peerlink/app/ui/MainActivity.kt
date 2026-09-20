package com.peerlink.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.peerlink.app.network.HotspotPairing
import com.peerlink.app.network.LanPath
import com.peerlink.app.network.LanPathResolver
import com.peerlink.app.godmode.PrimeLadderManager
import com.peerlink.app.core.PeerConnectionType
import com.peerlink.app.core.PeerInfo
import com.peerlink.app.core.PeerSessionPhase
import com.peerlink.app.core.AppState
import com.peerlink.app.discovery.NsdDiscovery
import com.peerlink.app.godmode.GodModeManager
import com.peerlink.app.service.PeerLinkVpnService
import com.peerlink.app.service.CallMonitorService
import androidx.compose.ui.unit.Dp
import android.content.ContentValues
import android.provider.MediaStore
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest

class MainActivity : AppCompatActivity(), NsdDiscovery.NsdCallback {

    companion object {
        private const val MASTER_KEY = "DENIS-BOSS-2026"
        private const val DESTRUCT_TIME_MS = 5L * 60L * 1000L
        private const val DISCOVERY_HEALTH_INTERVAL_MS = 2_500L
        const val ACTION_OPEN_PRIME_SETUP = "com.peerlink.app.action.OPEN_PRIME_SETUP"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var nsdDiscovery: NsdDiscovery? = null
    private val prefs by lazy { getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE) }
    private var hotspotPairing: HotspotPairing? = null
    private var showPrimeSetup by mutableStateOf(false)

    // Discovery is intentionally self-healing. Hotspot-owner interfaces often
    // appear a short time after Android reports the hotspot toggle as enabled,
    // and they are not exposed as a ConnectivityManager Wi-Fi Network on many
    // OEMs. Remember the actual bound path and periodically reconcile only when
    // idle; this repairs dead/stale sockets without churning a healthy listener.
    private var discoveryRegistrationKey: String = ""
    private var hotspotListenerKey: String = ""
    private var discoveryHealthRunnable: Runnable? = null

    private val _discoveredPeers = mutableStateListOf<PeerInfo>()
    // Both UDP discovery channels can see the same peer. A timeout from one
    // channel must not delete a peer that is still fresh on the other.
    private val peerDiscoverySources = mutableMapOf<String, MutableSet<String>>()
    private var discoveryUiState by mutableStateOf(
        DiscoveryUiState(
            phase = DiscoveryPhase.PAUSED,
            message = "Discovery will start after unlock",
        )
    )
    private var myLocalIp by mutableStateOf<String?>(null)
    private var deviceShortId: String = ""
    private var vpnConsentLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingVpnStartedCallback: (() -> Unit)? = null
    private val hotspotVpnStartInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Match system ───────────────────────────────────────────────────────

    // ── Post-unlock startup gate ────────────────────────────────────────────
    // Nothing heavy starts until the user has unlocked the app.
    @Volatile private var postUnlockStartupDone = false
    @Volatile private var pendingSessionIdForRecovery: String? = null
    @Volatile private var pendingNotificationAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_OPEN_PRIME_SETUP) showPrimeSetup = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        deviceShortId = if (deviceId.length > 6) deviceId.takeLast(6).uppercase() else deviceId.uppercase()

        AppState.deviceId = deviceId.take(8)

        if (!prefs.getBoolean("isDestroyed", false)) {
            nsdDiscovery = NsdDiscovery(this, this)
            nsdDiscovery?.initialize()
        }

        myLocalIp = getLocalIp()

        // PeerLink is Wi-Fi/hotspot only. Clear any legacy transport choice.
        prefs.edit().putString("transport_mode", "wifi").apply()

        vpnConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val startedCb = pendingVpnStartedCallback
            pendingVpnStartedCallback = null
            if (result.resultCode == Activity.RESULT_OK) {
                startedCb?.invoke()
                if (!AppState.isRunning.get()) {
                    startVpnService()
                }
                handler.postDelayed({
                    if (!AppState.isRunning.get()) {
                        hotspotVpnStartInFlight.set(false)
                    }
                }, 3000)
            } else {
                hotspotVpnStartInFlight.set(false)
                AppState.appendLog("[VPN-START ] User denied VPN permission")
                AppState.sessionError.set("VPN permission was not granted")
                AppState.sessionPhase.set(PeerSessionPhase.ERROR)
            }
        }

        setContent {
            var isDestroyed by remember { mutableStateOf(prefs.getBoolean("isDestroyed", false)) }
                var isUnlocked by remember { mutableStateOf(prefs.getBoolean("isUnlocked", false)) }
                var isAdminState by remember { mutableStateOf(prefs.getBoolean("isAdmin", false)) }
                if (isDestroyed) {
                    PeerLinkTheme { DestructionScreen() }
                } else if (!isUnlocked) {
                    PeerLinkTheme {
                        LockScreen(
                            onUnlocked = { admin ->
                                isAdminState = admin
                                isUnlocked = true
                                runPostUnlockStartupOnce()
                            },
                            onDestroyed = { isDestroyed = true }
                        )
                    }
                } else if (showPrimeSetup) {
                    PeerLinkTheme {
                        PrimeSetupScreen(
                            ctx = this@MainActivity,
                            onBack = { showPrimeSetup = false },
                            onStartPairing = {
                                ensureNotificationPermissionThen {
                                    GodModeManager.startNotificationPairing(this@MainActivity)
                                }
                            },
                        )
                    }
                } else PeerLinkTheme {
                    LaunchedEffect(Unit) { runPostUnlockStartupOnce() }

                    var pendingDiscovery by remember { mutableStateOf<(() -> Unit)?>(null) }
                    var pendingCallBlockEnable by remember { mutableStateOf(false) }
                    val callPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED)
                        if (pendingCallBlockEnable && granted) {
                            prefs.edit().putBoolean("apex_call_block_gameplay", true).apply()
                            CallMonitorService.start(this@MainActivity, gameplayMode = true)
                            Toast.makeText(this@MainActivity, "Call protection enabled", Toast.LENGTH_SHORT).show()
                        } else if (pendingCallBlockEnable) {
                            prefs.edit().putBoolean("apex_call_block_gameplay", false).apply()
                            Toast.makeText(
                                this@MainActivity,
                                "Phone permission is required for call protection",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        pendingCallBlockEnable = false
                    }

                    fun setCallBlockSafely(on: Boolean) {
                        if (!on) {
                            prefs.edit().putBoolean("apex_call_block_gameplay", false).apply()
                            CallMonitorService.stop(this@MainActivity)
                            return
                        }
                        val required = buildList {
                            add(Manifest.permission.READ_PHONE_STATE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(Manifest.permission.ANSWER_PHONE_CALLS)
                        }
                        val missing = required.filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) {
                            prefs.edit().putBoolean("apex_call_block_gameplay", true).apply()
                            CallMonitorService.start(this@MainActivity, gameplayMode = true)
                        } else {
                            pendingCallBlockEnable = true
                            callPermissionLauncher.launch(missing.toTypedArray())
                        }
                    }
                    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
                        if (perms.values.all { it }) pendingDiscovery?.invoke()
                        else {
                            discoveryUiState = DiscoveryUiState(
                                phase = DiscoveryPhase.WAITING_FOR_PERMISSION,
                                message = "Nearby Wi-Fi permission is needed to find players",
                            )
                            Toast.makeText(this@MainActivity, "Nearby Wi-Fi permission is needed to find players", Toast.LENGTH_SHORT).show()
                        }
                        pendingDiscovery = null
                    }
                    fun withDiscoveryPerms(run: () -> Unit) {
                        val req = discoveryPermissionsToRequest()
                        val ok = req.all { ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED }
                        if (ok) run() else {
                            discoveryUiState = DiscoveryUiState(
                                phase = DiscoveryPhase.WAITING_FOR_PERMISSION,
                                message = "Allow Nearby Wi-Fi so PeerLink can find the other phone",
                            )
                            pendingDiscovery = run
                            nearbyLauncher.launch(req)
                        }
                    }

                PeerLinkScreen(
                    ctx = this@MainActivity,
                    peers = _discoveredPeers,
                    discovery = discoveryUiState,
                    actions = PeerLinkActions(
                        saveName = { n ->
                            prefs.edit().putString("username", n).apply()
                            // Re-register discovery under the new name immediately so
                            // peers see the right name and a fresh install (which loaded
                            // the screen before any name existed) starts broadcasting.
                            withDiscoveryPerms { startBroadcastAndDiscovery(n) {} }
                        },
                        connectToPeer = connect@ { peer ->
                            val phase = AppState.sessionPhase.get()
                            if (phase != PeerSessionPhase.IDLE && phase != PeerSessionPhase.ERROR) {
                                AppState.appendLog("[VPN-START ] Ignoring peer tap while session phase=$phase")
                                return@connect
                            }
                            AppState.sessionError.set(null)
                            AppState.sessionPhase.set(PeerSessionPhase.PAIRING)
                            when (peer.connectionType) {
                                PeerConnectionType.HOTSPOT -> {
                                    val pairing = hotspotPairing
                                    if (pairing?.isRunning == true) {
                                        pairing.initiatePairing(peer.ip)
                                    } else {
                                        // The listener may have been recreated after a
                                        // Wi-Fi/settings transition. Restart it and wait
                                        // briefly for its socket to bind before sending.
                                        startHotspotPairingListener()
                                        handler.postDelayed({
                                            hotspotPairing?.initiatePairing(peer.ip)
                                        }, 200L)
                                    }
                                }
                                else -> { }
                            }
                        },
                        disconnect = { stopVpnService() },
                        isAdmin = isAdminState,
                        tryAdminUnlock = { entered ->
                            if (entered.trim() == MASTER_KEY) {
                                prefs.edit().putBoolean("isAdmin", true).apply(); isAdminState = true; true
                            } else false
                        },
                        deviceId = deviceShortId,
                        generateKey = { id -> generateKey(id) },
                        onSetupPrimeMode = { showPrimeSetup = true },
                        openBatterySettings = { openBatteryProtectionSettings() },
                        exportMatchLogs = {
                            // Trace assembly can be several MB. Keep CSV formatting
                            // and Downloads I/O off the UI and packet threads.
                            Thread({
                                // Read the FULL session log from the session file
                                // (both appendLog and appendFileOnly entries).
                                val fullLog = try {
                                    AppState.flushAndReadSessionLog(this@MainActivity)
                                } catch (t: Throwable) {
                                    AppState.appendLog("[EXPORT ] Failed to read session file: ${t.message}")
                                    AppState.getLogs()
                                }
                                val stamp = System.currentTimeMillis()
                                val logOk = saveLogsToDownloads("peerlink_match_$stamp.txt", fullLog)
                                val udpTrace = runCatching { PeerLinkVpnService.dumpNativeUdpTrace() }
                                    .getOrDefault("")
                                val traceOk = udpTrace.isBlank() ||
                                    saveLogsToDownloads("peerlink_udp_trace_$stamp.csv", udpTrace)
                                val shots = com.peerlink.app.service.ScoreCaptureDump.exportedFiles()
                                var shotOk = 0
                                for (shot in shots) {
                                    val mime = if (shot.name.endsWith(".jpg")) "image/jpeg" else "text/plain"
                                    if (saveFileToDownloads("peerlink_${stamp}_${shot.name}", shot, mime)) shotOk++
                                }
                                runOnUiThread {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        when {
                                            !logOk || !traceOk -> "Export incomplete — check Downloads access"
                                            else -> "Saved match log, UDP trace, and $shotOk score shots to Downloads"
                                        },
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                AppState.appendLog(
                                    "[EXPORT ] Match log chars=${fullLog.length} ok=$logOk; " +
                                        "UDP timing trace chars=${udpTrace.length} ok=$traceOk; " +
                                        "score shots $shotOk/${shots.size}"
                                )
                            }, "PeerLink-Log-Export").apply {
                                isDaemon = true
                                start()
                            }
                        },
                        callBlockEnabled = {
                            CallMonitorService.isActive(this@MainActivity)
                        },
                        setCallBlock = { on -> setCallBlockSafely(on) },
                        setMatchMarker = { on ->
                            prefs.edit().putBoolean("match_marker_enabled", on).apply()
                            if (on && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                                runCatching {
                                    startActivity(Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:$packageName"),
                                    ))
                                }.onFailure {
                                    Toast.makeText(this@MainActivity, "Open app settings to allow match markers", Toast.LENGTH_LONG).show()
                                }
                            } else if (on) {
                                com.peerlink.app.service.MatchMarkerOverlay.show(this@MainActivity)
                            } else {
                                com.peerlink.app.service.MatchMarkerOverlay.hide()
                            }
                        },
                        startDiscovery = {
                            withDiscoveryPerms {
                                prefs.edit().putString("transport_mode", "wifi").apply()
                                val nm = prefs.getString("username", "") ?: ""
                                startBroadcastAndDiscovery(nm) {}
                            }
                        },
                    )
                )
                }
        }
    }

    private fun startHotspotPairingListener(
        myName: String = getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE).getString("username", "") ?: "",
        resolvedPath: LanPath? = null,
        forceRestart: Boolean = false,
    ) {
        // Use the same resolved path as the beacon listener whenever possible.
        // Re-resolve only when this function is called independently (for
        // example, a peer tap while the pairing listener is recovering).
        val freshPath = resolvedPath ?: LanPathResolver.bestDiscoveryPath(this)
        if (freshPath == null) {
            hotspotPairing?.stop()
            hotspotPairing = null
            hotspotListenerKey = ""
            return
        }

        lockLanPath(freshPath)
        val effectiveName = myName.ifBlank {
            (Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player").take(14)
        }
        val desiredKey = listOf(
            AppState.deviceId,
            effectiveName,
            freshPath.interfaceName,
            freshPath.interfaceIndex.toString(),
            freshPath.localIp,
            freshPath.prefixLength.toString(),
        ).joinToString("|")

        val existing = hotspotPairing
        if (!forceRestart && existing?.isRunning == true && hotspotListenerKey == desiredKey) {
            existing.updateLocalPort(AppState.peerPort.get())
            return
        }

        AppState.appendFileOnly(
            "[HOTSPOT   ] Discovery path bind: ${freshPath.interfaceName}#${freshPath.interfaceIndex} " +
                "${freshPath.localIp}/${freshPath.prefixLength}"
        )
        existing?.stop()
        hotspotPairing = HotspotPairing().apply {

            // NEW: Peer discovered — add to list so user can tap before pairing.
            onPeerDiscovered = { peerIp, peerName ->
                runOnUiThread {
                    markPeerSeen("pairing", peerIp, peerName)
                }
            }

            // Fires after user taps peer and initiatePairing is called.
            onPeerReady = { peerIpStr ->
                runOnUiThread {
                    try {
                        // Establish peer identity FIRST, then resolve the exact LAN path
                        // that reaches it. The old ordering called local-IP detection before
                        // peerIp/connectionMode were set, so the hotspot-specific branch could
                        // never run and cellular 10.x addresses were sometimes selected.
                        AppState.peerIp.set(InetAddress.getByName(peerIpStr))
                        AppState.connectionMode = "hotspot"
                        AppState.activeTransportMode.set("wifi_udp")

                        AppState.connectedPeerIp = peerIpStr
                        AppState.connectedPeerName = _discoveredPeers
                            .firstOrNull { it.ip == peerIpStr }
                            ?.name
                            ?.ifBlank { peerIpStr }
                            ?: peerIpStr

                        val lanPath = LanPathResolver.resolveForPeer(this@MainActivity, peerIpStr)
                            ?: throw IllegalStateException("No LAN interface can reach peer $peerIpStr")
                        lockLanPath(lanPath)
                        val detectedLocalIp = lanPath.localIp
                        AppState.isPaired.set(true)
                        AppState.sessionPhase.set(PeerSessionPhase.STARTING_TUNNEL)

                        val fabricatedOk = AppState.calculateFabricatedIps()

                        AppState.appendLog("[HOTSPOT   ] Local LAN path locked: ${lanPath.interfaceName}#${lanPath.interfaceIndex} $detectedLocalIp/${lanPath.prefixLength}")
                        AppState.appendLog("[HOTSPOT   ] Peer confirmed for pairing: $peerIpStr")
                        AppState.appendLog("[HOTSPOT   ] Fabricated IPs ready=$fabricatedOk | my=${AppState.myFabricatedIp} peer=${AppState.peerFabricatedIp}")
                        if (!hotspotVpnStartInFlight.compareAndSet(false, true)) {
                            AppState.appendLog("[VPN-START ] Hotspot VPN start already in flight — ignoring duplicate pair event")
                            return@runOnUiThread
                        }

                        val startAction: () -> Unit = {
                            startVpnService()
                            handler.postDelayed({
                                if (!AppState.isRunning.get()) {
                                    hotspotVpnStartInFlight.set(false)
                                }
                            }, 3000)
                            Unit
                        }

                        val consentIntent = VpnService.prepare(this@MainActivity)
                        if (consentIntent != null) {
                            pendingVpnStartedCallback = startAction
                            vpnConsentLauncher?.launch(consentIntent) ?: run {
                                pendingVpnStartedCallback = null
                                hotspotVpnStartInFlight.set(false)
                                AppState.appendLog("[VPN-START ] ERROR: VPN consent launcher unavailable")
                                AppState.sessionError.set("VPN permission screen could not be opened")
                                AppState.sessionPhase.set(PeerSessionPhase.ERROR)
                            }
                        } else {
                            startAction()
                        }
                    } catch (e: Exception) {
                        AppState.appendLog("Failed to set peer IP: ${e.message}")
                        AppState.sessionError.set(e.message ?: "Could not prepare the peer connection")
                        AppState.sessionPhase.set(PeerSessionPhase.ERROR)
                    }
                }
            }

            onPeerPortReceived = { port ->
                runOnUiThread {
                    val effectivePort = if (port > 0) port else 17024
                    AppState.peerPort.set(effectivePort)
                    AppState.appendLog("[HOTSPOT   ] Peer tunnel port: $effectivePort")
                }
            }

            onPeerLost = { peerIp ->
                runOnUiThread {
                    markPeerLost("pairing", peerIp)
                }
            }

            onStatus = { status ->
                runOnUiThread {
                    AppState.appendLog("[HOTSPOT   ] $status")
                    if (status.contains("timed out", ignoreCase = true) ||
                        status.contains("Port busy", ignoreCase = true) ||
                        status.contains("not ready", ignoreCase = true)) {
                        if (AppState.sessionPhase.get() == PeerSessionPhase.PAIRING) {
                            AppState.sessionError.set(status)
                            AppState.sessionPhase.set(PeerSessionPhase.ERROR)
                            hotspotVpnStartInFlight.set(false)
                        }
                        discoveryUiState = discoveryUiState.copy(
                            phase = DiscoveryPhase.ERROR,
                            message = status,
                            peerCount = _discoveredPeers.size,
                        )
                    }
                }
            }
        }
        hotspotPairing?.updateLocalPort(AppState.peerPort.get())
        // Bind to the path resolved *now*. Do not reuse myLocalIp from a prior
        // Wi-Fi/hotspot state; that stale value was one source of discovery
        // requiring an app restart after network changes.
        hotspotPairing?.start(
            deviceId = AppState.deviceId,
            myName = effectiveName,
            localIp = freshPath.localIp,
            prefixLength = freshPath.prefixLength,
        )
        hotspotListenerKey = desiredKey
    }



    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureNotificationPermissionThen(action: () -> Unit) {
        if (hasNotificationPermission()) {
            action()
            return
        }
        pendingNotificationAction = action
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1002 -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    pendingNotificationAction?.invoke()
                } else {
                    // Android allows a foreground service to run even when its
                    // notification is hidden by POST_NOTIFICATIONS. Continue the
                    // pairing watcher and direct the user to the in-app code box.
                    // Refusing to start here made Prime setup impossible after a
                    // notification denial even though notification access is not a
                    // technical requirement for local ADB pairing.
                    AppState.appendLog("[PRIME-MODE ] Notification permission denied — using in-app pairing entry")
                    pendingNotificationAction?.invoke()
                    Toast.makeText(
                        this,
                        "Notification is off — return to PeerLink and enter the code in the app",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                pendingNotificationAction = null
            }
        }
    }






















    private fun startBroadcastAndDiscovery(userName: String, onStarted: () -> Unit) {
        val effectiveName = userName.ifBlank {
            val m = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"
            m.take(14)
        }

        val discoveryPath = LanPathResolver.bestDiscoveryPath(this)
        if (discoveryPath == null || discoveryPath.localIp.isBlank() || discoveryPath.localIp == "127.0.0.1") {
            val wasBound = discoveryRegistrationKey.isNotBlank() ||
                nsdDiscovery?.isRunning == true || hotspotPairing?.isRunning == true
            if (wasBound) {
                runCatching { nsdDiscovery?.stopDiscovery() }
                runCatching { hotspotPairing?.stop() }
                hotspotPairing = null
                discoveryRegistrationKey = ""
                hotspotListenerKey = ""
                _discoveredPeers.clear()
                peerDiscoverySources.clear()
            }
            if (discoveryUiState.phase != DiscoveryPhase.WAITING_FOR_WIFI || discoveryUiState.interfaceName.isNotBlank()) {
                AppState.appendLog("[NET-DISC  ] Waiting for Wi-Fi/hotspot LAN interface; cellular paths are ignored")
            }
            discoveryUiState = DiscoveryUiState(
                phase = DiscoveryPhase.WAITING_FOR_WIFI,
                message = "Connect both phones to the same Wi-Fi or hotspot",
            )
            return
        }

        val ip = discoveryPath.localIp
        val prefix = discoveryPath.prefixLength
        val registrationKey = listOf(
            effectiveName,
            AppState.deviceId,
            discoveryPath.interfaceName,
            discoveryPath.interfaceIndex.toString(),
            ip,
            prefix.toString(),
            AppState.peerPort.get().toString(),
        ).joinToString("|")
        val pathChanged = discoveryRegistrationKey != registrationKey
        val beaconNeedsRecovery = nsdDiscovery?.isRunning != true

        myLocalIp = ip
        AppState.localIp.set(ip)
        lockLanPath(discoveryPath)

        if (pathChanged || beaconNeedsRecovery) {
            AppState.appendLog(
                "[NET-DISC  ] Binding discovery to ${discoveryPath.interfaceName}#${discoveryPath.interfaceIndex} $ip/$prefix" +
                    if (beaconNeedsRecovery && !pathChanged) " (listener recovery)" else ""
            )
            nsdDiscovery?.registerService(
                port = AppState.peerPort.get(),
                name = effectiveName,
                ip = ip,
                prefixLength = prefix,
                deviceId = AppState.deviceId,
            )
            nsdDiscovery?.startDiscovery()
            discoveryRegistrationKey = registrationKey
        }

        // The pairing channel is reconciled independently. It remains untouched
        // while healthy, but a dead socket or changed hotspot interface is
        // recreated automatically.
        startHotspotPairingListener(effectiveName, resolvedPath = discoveryPath)

        discoveryUiState = DiscoveryUiState(
            phase = if (_discoveredPeers.isEmpty()) DiscoveryPhase.SCANNING else DiscoveryPhase.PEERS_FOUND,
            message = when (_discoveredPeers.size) {
                0 -> "Scanning the local link continuously"
                1 -> "1 player found"
                else -> "${_discoveredPeers.size} players found"
            },
            interfaceName = discoveryPath.interfaceName,
            peerCount = _discoveredPeers.size,
        )

        onStarted()
    }

    private fun hasDiscoveryPermissions(): Boolean =
        discoveryPermissionsToRequest().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startDiscoveryHealthLoop() {
        if (discoveryHealthRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val phase = AppState.sessionPhase.get()
                    val idleEnough = phase == PeerSessionPhase.IDLE || phase == PeerSessionPhase.ERROR
                    if (prefs.getBoolean("isUnlocked", false) &&
                        !AppState.isRunning.get() && idleEnough && hasDiscoveryPermissions()) {
                        val name = prefs.getString("username", "") ?: ""
                        startBroadcastAndDiscovery(name) {}
                    }
                } catch (t: Throwable) {
                    AppState.appendFileOnly("[NET-DISC  ] Health reconcile failed: ${t.message}")
                } finally {
                    if (discoveryHealthRunnable === this) {
                        handler.postDelayed(this, DISCOVERY_HEALTH_INTERVAL_MS)
                    }
                }
            }
        }
        discoveryHealthRunnable = runnable
        handler.post(runnable)
    }

    private fun stopDiscoveryHealthLoop() {
        discoveryHealthRunnable?.let { handler.removeCallbacks(it) }
        discoveryHealthRunnable = null
    }



    private fun generateKey(id: String): String {
        val normalized = id.trim().uppercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("PeerLink::${normalized}::LanLink-Admin-2026".toByteArray())
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val raw = buildString {
            repeat(12) { idx ->
                val b = digest[idx].toInt() and 0xFF
                append(alphabet[b % alphabet.length])
            }
        }
        return raw.chunked(4).joinToString("-")
    }


    @Composable
    fun LockScreen(onUnlocked: (isAdmin: Boolean) -> Unit, onDestroyed: () -> Unit) {
        val activity = this@MainActivity
        BackHandler { activity.finish() }

        val lockStartTime = remember {
            val saved = prefs.getLong("lockStartTime", 0L)
            if (saved == 0L) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("lockStartTime", now).apply()
                now
            } else {
                saved
            }
        }

        val initialRemaining = remember {
            val elapsed = System.currentTimeMillis() - lockStartTime
            (DESTRUCT_TIME_MS - elapsed).coerceAtLeast(0L)
        }

        if (initialRemaining <= 0L) {
            LaunchedEffect(Unit) {
                prefs.edit().putBoolean("isDestroyed", true).apply()
                onDestroyed()
            }
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF111318)))
            return
        }

        var timeLeftMs by remember { mutableLongStateOf(initialRemaining) }
        var keyInput by remember { mutableStateOf("") }
        var showError by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current

        val userKey = remember { generateKey(deviceShortId) }

        LaunchedEffect(Unit) {
            while (timeLeftMs > 0L) {
                kotlinx.coroutines.delay(1000L)
                val elapsed = System.currentTimeMillis() - lockStartTime
                timeLeftMs = (DESTRUCT_TIME_MS - elapsed).coerceAtLeast(0L)
            }
            prefs.edit().putBoolean("isDestroyed", true).apply()
            onDestroyed()
        }

        val minutes = timeLeftMs / 1000L / 60L
        val seconds = timeLeftMs / 1000L % 60L

        val infiniteTransition = rememberInfiniteTransition(label = "LockPulse")
        val timerAlpha by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 0.4f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "TimerAlpha"
        )

        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.05f, targetValue = 0.15f,
            animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
            label = "GlowAlpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111318))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFB5A2FF).copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.3f),
                        radius = size.width * 0.8f
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.peerlink.app.R.drawable.ic_peerlink_mark),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "PeerLink",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        color = Color(0xFFA0A2AF)
                    )
                )

                Spacer(modifier = Modifier.weight(0.3f))

                Text(
                    "%d:%02d".format(minutes, seconds),
                    color = Color(0xFFFFA29A).copy(alpha = timerAlpha),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Thin,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    "DEVICE ID",
                    color = Color(0xFFA0A2AF),
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    deviceShortId,
                    color = Color(0xFFD2F47A),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(40.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(
                            1.dp,
                            if (showError) Color(0xFFFFA29A) else Color(0xFF323641),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; showError = false },
                        textStyle = TextStyle(
                            color = Color(0xFFF6F5F0),
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Color(0xFFD2F47A)),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (keyInput.isEmpty()) {
                                    Text(
                                        "ENTER ACCESS KEY",
                                        color = Color(0xFFA0A2AF).copy(alpha = 0.4f),
                                        fontSize = 14.sp,
                                        letterSpacing = 2.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                AnimatedVisibility(visible = showError) {
                    Text(
                        "INVALID KEY",
                        color = Color(0xFFFFA29A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val entered = keyInput.trim()
                        when {
                            entered == MASTER_KEY -> {
                                prefs.edit()
                                    .putBoolean("isUnlocked", true)
                                    .putBoolean("isAdmin", true)
                                    .remove("lockStartTime")
                                    .apply()
                                onUnlocked(true)
                            }
                            entered == userKey -> {
                                prefs.edit()
                                    .putBoolean("isUnlocked", true)
                                    .putBoolean("isAdmin", false)
                                    .remove("lockStartTime")
                                    .apply()
                                onUnlocked(false)
                            }
                            else -> showError = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD2F47A), contentColor = Color(0xFF111318)),
                    shape = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "UNLOCK",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    fun DestructionScreen() {
        val activity = this@MainActivity
        BackHandler { activity.finish() }

        val infiniteTransition = rememberInfiniteTransition(label = "DestructPulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
            label = "DestructAlpha"
        )
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 1.1f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
            label = "DestructScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111318))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFB5A2FF).copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width * 0.6f
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFA29A).copy(alpha = pulseAlpha),
                    modifier = Modifier
                        .size(80.dp)
                        .scale(pulseScale)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    "SYSTEM FAILURE",
                    color = Color(0xFFFFA29A).copy(alpha = pulseAlpha),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "This app has been permanently disabled\ndue to unauthorized access.",
                    color = Color(0xFFA0A2AF),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        }
    }












    /**
     * Compact God Mode status card shown in SetupView.
     * Tapping it opens the full GodModeDialog.
     */



    // ── MatchView — slides in from right of DiscoveryView ─────────────────
    //
    // Layout:
    //   1. PeerCoin balance dashboard (tappable → shows opponents bubble)
    //   2. Latest match score
    //   3. Scrollable full match history
    //
    // Opponents bubble (almost full-screen):
    //   Scrollable list of everyone you've played, with win-rate per person.
    //   Tap a name → filters history to only that opponent.

    // Sample data — replace with server data when backend is ready



    // ── PeerCoin dashboard card ─────────────────────────────────────────────



    // ── Unique hexagonal coin icon ──────────────────────────────────────────



    // Helper extension for Box with size

    // ── Latest match card ───────────────────────────────────────────────────



    // ── Match history row ───────────────────────────────────────────────────



    // ── Opponents bubble (almost full screen sheet) ─────────────────────────



    // ── Opponent row with unique avatar ──────────────────────────────────────











    // ── Match system helpers ──────────────────────────────────────────────────

    /**
     * Request MediaProjection permission if not already granted.
     * Shows the system dialog once — subsequent calls are no-ops.
     * The granted token is stored in EFootballScreenMonitor via the launcher callback.
     */
    // Fix #2: stores user's role choice while permission dialog is pending



    /**
     * Host / Away role selection card shown in ConnectedView.
     * If the peer already picked HOST or AWAY, we immediately lock the opposite
     * role on this device so both players cannot choose the same side. This is
     * the role behavior described in the match-system design doc.
     */



    /**
     * Runs once after the user unlocks the app or when an already-unlocked
     * process is recreated. This keeps heavy startup work away from the lock
     * screen while still ensuring Prime Mode pieces are ready before use.
     */
    private fun runPostUnlockStartupOnce() {
        if (postUnlockStartupDone) return
        postUnlockStartupDone = true

        // Request POST_NOTIFICATIONS up-front so the VPN foreground notification is visible
        // on Android 13+ (API 33+). Without this, the notification is silently suppressed —
        // the root cause of the invisible-notification issue on Android 13+/15 devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ensureNotificationPermissionThen { }
        }

        try {
            GodModeManager.init(this)
            AppState.appendLog("[STARTUP    ] GodModeManager.init ok")
        } catch (t: Exception) {
            Log.e("MainActivity", "GodModeManager.init failed", t)
            AppState.appendLog("[STARTUP    ] GodModeManager.init failed: ${t.message}")
        }

        try {
            PrimeLadderManager.checkAliveOnAppOpen()
            AppState.appendLog("[STARTUP    ] Prime alive check ok")
        } catch (t: Throwable) {
            Log.e("MainActivity", "PrimeLadderManager.checkAliveOnAppOpen failed", t)
            AppState.appendLog("[STARTUP    ] Prime alive check failed: ${t.message}")
        }

        // Auto is deliberately silent: it may ask the already-running local
        // Prime server to enable Wi-Fi, but it never opens Android Settings and
        // never turns Wi-Fi back off from an Activity lifecycle callback.
        GodModeManager.enableRadiosForSession()
        startDiscoveryHealthLoop()
    }

    /**
     * Open Android's battery-protection flow only after an explicit user tap.
     * Startup must remain interruption-free: no surprise system screens.
     */
    private fun openBatteryProtectionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Battery optimisation is not used on this Android version", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "PeerLink is already excluded from battery optimisation", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Throwable) {
            // Some OEMs do not support the per-app request. The fallback is
            // still user-initiated, so it cannot interrupt normal startup.
            try {
                val fallback = Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                )
                startActivity(fallback)
            } catch (_: Throwable) { }
        }
    }

    private fun saveLogsToDownloads(filename: String, text: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                true
            } ?: false
        } catch (e: Exception) {
            Log.e("MainActivity", "saveLogsToDownloads failed: ${e.message}", e)
            false
        }
    }

    private fun saveFileToDownloads(filename: String, source: File, mimeType: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().buffered(1024 * 1024).use { input ->
                    input.copyTo(output, 1024 * 1024)
                }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "saveFileToDownloads failed: ${e.message}", e)
            false
        }
    }













    private fun discoveryPermissionsToRequest(): Array<String> {
        val permissions = mutableListOf<String>()
        
        // PeerLink discovery only needs Nearby Wi-Fi on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        return permissions.toTypedArray()
    }









    private fun lockLanPath(path: LanPath) {
        AppState.localIp.set(path.localIp)
        AppState.localLanInterfaceName.set(path.interfaceName)
        AppState.localLanInterfaceIndex.set(path.interfaceIndex)
        AppState.localLanPrefixLength.set(path.prefixLength)
        AppState.localLanNetwork.set(path.androidNetwork)
        myLocalIp = path.localIp
    }















    private fun startVpnService() {
        // Hotspot pairing already locks the session LAN identity before VPN startup.
        // Do not override that identity here, otherwise the fabricated-IP assignment can
        // flip at service start and split the session between the paired control plane
        // and the native datapath. Only refresh hotspot IP if we somehow do not have one.
        val peer = AppState.peerIp.get()?.hostAddress
        if (!peer.isNullOrBlank()) {
            val path = LanPathResolver.resolveForPeer(this, peer)
            if (path != null) {
                lockLanPath(path)
                AppState.appendLog("[VPN-START ] Revalidated LAN path ${path.interfaceName}#${path.interfaceIndex} ${path.localIp}/${path.prefixLength} -> $peer")
            } else {
                AppState.appendLog("[VPN-START ] WARNING: could not re-resolve peer LAN path; preserving ${AppState.localIp.get()}")
            }
        }

        
        // Auto-activate Prime Mode if paired and master toggle is on.
        // This covers all connection paths (hotspot / LAN) without
        // requiring the user to tap ACTIVATE every session.
        if (GodModeManager.isApexMasterEnabled() &&
            GodModeManager.state.value == GodModeManager.State.PAIRED_IDLE) {
            AppState.appendLog("[PRIME-MODE ] VPN started — auto-activating Prime Mode")
            GodModeManager.activateGodMode()
        }

        val intent = Intent(this, PeerLinkVpnService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (t: Exception) {
            hotspotVpnStartInFlight.set(false)
            AppState.sessionError.set(t.message ?: "Android refused to start the VPN service")
            AppState.sessionPhase.set(PeerSessionPhase.ERROR)
            AppState.appendLog("[VPN-START ] Service start failed: ${t.message}")
        }
    }

    private fun stopVpnService() {
        hotspotPairing?.cancelPendingPairing()
        hotspotVpnStartInFlight.set(false)
        AppState.sessionError.set(null)
        AppState.sessionPhase.set(PeerSessionPhase.STOPPING)
        // Log the call stack so we can see WHO triggered the disconnect.
        val caller = Throwable().stackTrace.take(4)
            .filter { it.className.contains("peerlink") }
            .joinToString(" ← ") { "${it.fileName}:${it.lineNumber}.${it.methodName}" }
        AppState.appendLog("[VPN-STOP  ] stopVpnService() called from: $caller")
        val intent = Intent(this, PeerLinkVpnService::class.java).apply {
            action = PeerLinkVpnService.ACTION_STOP
            putExtra("source", "MainActivity.stopVpnService: $caller")
        }
        startService(intent)
    }

    private fun getLocalIp(): String {
        return try {
            val interfaceIp = findFirstNonLoopbackIpv4 { ip ->
                ip.startsWith("192.168.") || ip.startsWith("10.") ||
                (ip.startsWith("172.") && ip.split(".").getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true)
            }

            if (interfaceIp != null) {
                return interfaceIp
            }

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            for (network in cm.allNetworks) {
                val lp = cm.getLinkProperties(network) ?: continue

                val ip = lp.linkAddresses.asSequence()
                    .map { it.address }
                    .filterIsInstance<java.net.Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress

                if (ip != null) {
                    return ip
                }
            }

            findFirstNonLoopbackIpv4 { true } ?: "127.0.0.1"

        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun findFirstNonLoopbackIpv4(match: (String) -> Boolean): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null

        return interfaces.asSequence()
            .filter { !it.isLoopback && it.isUp }
            .filter {
                val name = it.name.lowercase()
                !name.startsWith("tun") && !name.startsWith("tap") && !name.startsWith("ppp")
            }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .firstOrNull(match)
    }

    private fun markPeerSeen(source: String, peerIp: String, peerName: String) {
        peerDiscoverySources.getOrPut(peerIp) { mutableSetOf() }.add(source)
        val replacement = PeerInfo(
            name = peerName.ifBlank { peerIp },
            ip = peerIp,
            mac = "",
            connectionType = PeerConnectionType.HOTSPOT,
        )
        val existing = _discoveredPeers.indexOfFirst { it.ip == peerIp }
        if (existing >= 0) {
            if (_discoveredPeers[existing] != replacement) _discoveredPeers[existing] = replacement
        } else {
            _discoveredPeers.add(replacement)
            AppState.appendLog("[NET-DISC  ] Peer discovered: ${replacement.name} @ $peerIp via $source")
        }
        refreshDiscoveryPeerCount()
    }

    private fun markPeerLost(source: String, peerIp: String) {
        val sources = peerDiscoverySources[peerIp] ?: return
        sources.remove(source)
        if (sources.isEmpty()) {
            peerDiscoverySources.remove(peerIp)
            _discoveredPeers.removeAll { it.ip == peerIp }
        }
        refreshDiscoveryPeerCount()
    }

    private fun refreshDiscoveryPeerCount() {
        discoveryUiState = discoveryUiState.copy(
            phase = if (_discoveredPeers.isEmpty()) DiscoveryPhase.SCANNING else DiscoveryPhase.PEERS_FOUND,
            message = if (_discoveredPeers.isEmpty()) "Scanning the local link continuously"
                else if (_discoveredPeers.size == 1) "1 player found"
                else "${_discoveredPeers.size} players found",
            peerCount = _discoveredPeers.size,
        )
    }

    override fun onPeerFound(ip: InetAddress, port: Int, serviceName: String) {
        runOnUiThread {
            val peerIp = ip.hostAddress ?: return@runOnUiThread
            markPeerSeen("beacon", peerIp, serviceName)
        }
        Log.d("LanLink", "Found: $serviceName")
    }

    override fun onPeerLost(ip: InetAddress, serviceName: String) {
        runOnUiThread {
            val peerIp = ip.hostAddress ?: return@runOnUiThread
            markPeerLost("beacon", peerIp)
        }
    }

    override fun onLog(message: String) {
        Log.d("LanLink", message)
        AppState.appendLog(message)
    }

    override fun onPause() {
        super.onPause()
        AppState.appendLog("[T-STATE   ] MainActivity onPause running=${AppState.isRunning.get()} mode=${AppState.connectionMode}")
        stopDiscoveryHealthLoop()
        try {
            nsdDiscovery?.stopDiscovery()
        } catch (_: Exception) {}
        try {
            hotspotPairing?.stop()
        } catch (_: Exception) {}
        discoveryRegistrationKey = ""
        hotspotListenerKey = ""
        if (!AppState.isRunning.get()) {
            discoveryUiState = DiscoveryUiState(
                phase = DiscoveryPhase.PAUSED,
                message = "Discovery paused while PeerLink is in the background",
            )
        }
        if (AppState.isRunning.get()) {
            AppState.appendLog("[T-STATE   ] Active session preserved onPause — control-plane watchers kept alive")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_PRIME_SETUP) {
            showPrimeSetup = true
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.appendLog("[T-STATE   ] MainActivity onResume running=${AppState.isRunning.get()} mode=${AppState.connectionMode}")

        // If the app is still locked, do nothing.
        // Heavy subsystems must not start before the user unlocks.
        if (!prefs.getBoolean("isUnlocked", false)) return


        if (AppState.isRunning.get()) {
            stopDiscoveryHealthLoop()
            AppState.appendLog("[RESUME     ] Active session detected — preserving live transport state")
            try {
                GodModeManager.startWatching()
                GodModeManager.refreshSetupState()
                AppState.appendLog("[RESUME     ] Control-plane watchers ensured for active session")
            } catch (e: Exception) {
                AppState.appendLog("[RESUME     ] Watcher ensure failed: ${e.message}")
            }
            return
        }

        try {
            val missingPermissions = discoveryPermissionsToRequest().any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPermissions) {
                discoveryUiState = DiscoveryUiState(
                    phase = DiscoveryPhase.WAITING_FOR_PERMISSION,
                    message = "Nearby Wi-Fi permission is needed to find players",
                )
            } else {
                // Re-resolve and re-register the complete discovery stack. A plain
                // start() reused the pre-Settings IP and left the UDP socket bound
                // to an interface that no longer existed after hotspot changes.
                val name = prefs.getString("username", "") ?: ""
                startBroadcastAndDiscovery(name) {}
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "onResume discovery restart failed: ${e.message}", e)
            AppState.appendLog("[RESUME     ] Discovery restart failed: ${e.message}")
            discoveryUiState = DiscoveryUiState(
                phase = DiscoveryPhase.ERROR,
                message = "Discovery is recovering automatically",
            )
        }
        // SoftAP interfaces can appear after onResume and often have no
        // ConnectivityManager Wi-Fi callback. Keep a small idle-only health
        // check running so discovery heals as soon as the real LAN path exists.
        startDiscoveryHealthLoop()
        try {
            GodModeManager.startWatching()
            GodModeManager.refreshSetupState()
        } catch (e: Exception) {
            Log.e("MainActivity", "GodModeManager.startWatching failed: ${e.message}", e)
            AppState.appendLog("[RESUME     ] Prime watcher failed: ${e.message}")
        }
    }



    override fun onDestroy() {
        AppState.appendLog("[T-STATE   ] MainActivity onDestroy running=${AppState.isRunning.get()} mode=${AppState.connectionMode}")
        stopDiscoveryHealthLoop()

        if (CallMonitorService.isGameplayMode.get()) {
            try { CallMonitorService.stop(this) } catch (_: Exception) { }
        }

        if (!AppState.isRunning.get()) {
            try { hotspotPairing?.stop() } catch (_: Exception) { }
            hotspotPairing = null
            try { nsdDiscovery?.tearDown() } catch (_: Exception) { }
        } else {
            AppState.appendLog("[T-STATE   ] onDestroy while session active — preserving transport state")
        }

        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
