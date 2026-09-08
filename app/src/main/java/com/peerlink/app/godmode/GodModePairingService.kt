package com.peerlink.app.godmode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.DeviceChecker
import com.peerlink.app.ui.MainActivity

/**
 * GodModePairingService — foreground service mirroring the Shizuku notification pattern.
 *
 * Lifecycle:
 *   1. Started by GodModeManager.startNotificationPairing()
 *   2. Posts a persistent notification immediately
 *   3. AdbNsdWatcher runs inside; when it finds _adb-tls-pairing._tcp it
 *      updates the notification to prompt for the 6-digit code
 *   4. FALLBACK: If NSD fails after [NSD_TIMEOUT_MS] (60 s, possible on restricted/OEM networks
 *      where mDNS multicast may not work reliably), the notification
 *      switches to manual mode: user types "PORT:CODE" (e.g. "45678:123456").
 *      Connect to 127.0.0.1 since adbd always runs on the same device.
 *   5. User pulls down the notification, enters code (or PORT:CODE), taps Send
 *   6. RemoteInput result arrives in PinReceiver → calls GodModeManager
 *   7. On success/failure the notification updates; service stops itself
 *
 * FIXES applied vs original:
 *   [FATAL] submitPairingPin argument order was wrong (host/port/pin transposed)
 *           → fixed with named parameters
 *   [MEDIUM] RECEIVER_NOT_EXPORTED used raw constant that crashes on API < 33
 *            → replaced with ContextCompat.registerReceiver()
 *   [NEW]   NSD can be unavailable on restricted/OEM networks
 *            → timeout → manual PORT:CODE fallback
 */
class GodModePairingService : Service() {

    companion object {
        private const val TAG = "GodModePairing"

        const val ACTION_STOP         = "com.peerlink.app.godmode.ACTION_STOP_PAIRING"
        private const val ACTION_PIN  = "com.peerlink.app.godmode.PIN_SUBMITTED"
        private const val EXTRA_PIN   = "pin_code"

        private const val CHANNEL_ID  = "peerlink_godmode"
        private const val NOTIF_ID    = 7701

        // After this delay without NSD success → switch notification to manual PORT:CODE mode
        private const val NSD_TIMEOUT_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, GodModePairingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GodModePairingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    // ── Internal state ─────────────────────────────────────────────────

    private var nsdWatcher: AdbNsdWatcher? = null
    private var nm: NotificationManager?   = null
    private var pairingHost: String?       = null
    private var pairingPort: Int           = 0
    private var nsdSucceeded               = false
    private var manualModeActive           = false
    // True once pairing succeeds. Guards onPairingPortLost from overwriting
    // the success notification — adbd always drops _adb-tls-pairing._tcp
    // immediately after success, so the lost event is expected and must be ignored.
    @Volatile private var pairingSucceeded = false
    @Volatile private var cancelledByUser = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Fires after NSD_TIMEOUT_MS if NSD hasn't found the pairing port
    private val nsdTimeoutRunnable = Runnable {
        if (!nsdSucceeded) {
            manualModeActive = true
            AppState.appendLog("Prime pairing [svc]: NSD timeout — manual PORT:CODE remains available")
            GodModeManager.onNsdPairingTimeout()
            // Keep the notification actionable. On some ROMs mDNS never reports the
            // pairing service, but manual PORT:CODE pairing still works reliably.
            postNotification(
                title = "Prime Mode — Enter Port:Code",
                body  = "Auto-detect timed out.\n\nIn Wireless Debugging, use the PORT from \"Pair device with pairing code\" and enter PORT:CODE here, for example 45678:123456.",
                phase = Phase.WAITING_MANUAL
            )
        }
    }

    // ── PIN broadcast receiver ────────────────────────────────────────

    private val pinReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIN) return

            val bundle = RemoteInput.getResultsFromIntent(intent) ?: return
            val raw    = bundle.getCharSequence(EXTRA_PIN)?.toString()?.trim() ?: return

            postNotification(
                title = "Prime Mode — Pairing…",
                body  = "Connecting to ADB daemon. Stay in Developer Options.",
                phase = Phase.PAIRING
            )

            AppState.appendLog("GodMode [svc]: Submitting pairing input")

            GodModeManager.submitPairingInput(raw) { success, message ->
                if (success) {
                    pairingSucceeded = true  // block onPairingPortLost from clobbering this
                    postNotification(
                        title = "Prime Mode — Paired",
                        body  = "Pairing succeeded. Keep Wireless Debugging ON, return to PeerLink, then tap Activate Now.",
                        phase = Phase.DONE_OK
                    )
                    AppState.appendLog("GodMode [svc]: ✅ Paired — waiting for explicit ACTIVATE")
                } else {
                    GodModeManager.onPairingAttemptFailed(message)
                    postNotification(
                        title = "Prime Mode — Failed",
                        body  = "Error: $message\nCorrect the code and try again here or in PeerLink.",
                        phase = if (nsdSucceeded) Phase.WAITING_CODE else Phase.WAITING_MANUAL
                    )
                    AppState.appendLog("GodMode [svc]: ❌ Pairing failed: $message")
                }
                // Keep failed attempts alive and actionable. The old service
                // promised "try again" and then destroyed the input receiver
                // six seconds later. Only a successful pairing auto-closes.
                if (success) mainHandler.postDelayed({ stopSelf() }, 6_000)
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()

        // FIX: Use ContextCompat.registerReceiver — handles API level check internally
        ContextCompat.registerReceiver(
            this,
            pinReceiver,
            IntentFilter(ACTION_PIN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelledByUser = true
            stopSelf()
            return START_NOT_STICKY
        }

        // A repeated Start/Restart request resets this service instance rather
        // than stacking NSD listeners and timeout callbacks.
        mainHandler.removeCallbacks(nsdTimeoutRunnable)
        nsdWatcher?.stop()
        nsdWatcher = null
        pairingHost = null
        pairingPort = 0
        nsdSucceeded = false
        manualModeActive = false
        pairingSucceeded = false
        cancelledByUser = false

        // Post foreground notification immediately (required before any async work)
        val deviceInstructions = DeviceChecker.getPrePairingInstructions()
        val shortHint = DeviceChecker.getShortHint()
        postNotification(
            title = "Prime Mode Setup${if (DeviceChecker.requiresExtraSteps()) " — ${DeviceChecker.getDeviceLabel()}" else ""}",
            body  = buildString {
                if (deviceInstructions != null) {
                    appendLine("⚠ BEFORE PAIRING — ${DeviceChecker.getDeviceLabel()}:")
                    appendLine(deviceInstructions)
                    appendLine()
                }
                appendLine("Enable Wireless Debugging → tap 'Pair device with pairing code'.")
                appendLine()
                append("⚠ Do this while connected to regular Wi-Fi/hotspot.")
            },
            phase = Phase.WAITING_DETECTION
        )

        startNsdWatch()

        // Schedule fallback to manual mode if NSD doesn't work
        mainHandler.postDelayed(nsdTimeoutRunnable, NSD_TIMEOUT_MS)

        AppState.appendLog("GodMode [svc]: Foreground service started — NSD timeout in ${NSD_TIMEOUT_MS / 1000}s")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(nsdTimeoutRunnable)
        nsdWatcher?.stop()
        nsdWatcher = null
        try { unregisterReceiver(pinReceiver) } catch (_: Exception) {}
        if (!pairingSucceeded) {
            GodModeManager.onPairingServiceStopped(cancelledByUser)
        }
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── NSD watching ──────────────────────────────────────────────────

    private fun startNsdWatch() {
        nsdWatcher = AdbNsdWatcher(applicationContext).apply {
            onPairingPortFound = { host, port ->
                pairingHost  = host
                pairingPort  = port
                nsdSucceeded = true
                mainHandler.removeCallbacks(nsdTimeoutRunnable) // cancel manual fallback

                AppState.appendLog("GodMode [svc]: ✅ Pairing port → $host:$port")
                GodModeManager.onPairingPortDiscovered(host, port)

                postNotification(
                    title = "Prime Mode — Enter Code",
                    body  = "Pairing service detected!\nEnter the 6-digit code shown on your screen.",
                    phase = Phase.WAITING_CODE
                )
            }
            onPairingPortLost = {
                // adbd drops _adb-tls-pairing._tcp the instant pairing succeeds — this is normal.
                // Only show "lost" error if we haven't actually succeeded yet.
                if (pairingSucceeded) {
                    AppState.appendLog("GodMode [svc]: Pairing service lost after success — expected, ignoring")
                } else if (pairingHost != null) {
                    pairingHost  = null
                    pairingPort  = 0
                    nsdSucceeded = false
                    postNotification(
                        title = "Prime Mode Setup",
                        body  = "Pairing service lost. Tap 'Pair device with pairing code' again.",
                        phase = Phase.WAITING_DETECTION
                    )
                }
            }
            start()
        }
    }

    // ── Notification ──────────────────────────────────────────────────

    private enum class Phase { WAITING_DETECTION, WAITING_CODE, WAITING_MANUAL, TIMEOUT, PAIRING, DONE_OK }

    private fun postNotification(title: String, body: String, phase: Phase) {
        val isInputPhase = phase == Phase.WAITING_CODE ||
                           phase == Phase.WAITING_DETECTION ||
                           phase == Phase.WAITING_MANUAL

        val inputHint = when (phase) {
            Phase.WAITING_MANUAL    -> "PORT:CODE (e.g. 45678:123456)"
            Phase.WAITING_CODE      -> "6-digit pairing code"
            Phase.WAITING_DETECTION -> "6-digit code or PORT:CODE"
            else                    -> "code"
        }

        val actionLabel = when (phase) {
            Phase.WAITING_MANUAL -> "Enter Port:Code"
            else                 -> "Enter Code"
        }

        // For collapsed notification, use first sentence only so \n doesn't show as literal text
        val shortBody = body.substringBefore('\n').trimEnd().ifBlank { body }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(shortBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(phase != Phase.DONE_OK)
            .setOnlyAlertOnce(true)
            .setColor(0xFF69FF8E.toInt())
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    2,
                    Intent(this, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_OPEN_PRIME_SETUP
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )

        if (isInputPhase) {
            val remoteInput = RemoteInput.Builder(EXTRA_PIN)
                .setLabel(inputHint)
                .build()

            val replyIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_PIN).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    actionLabel,
                    replyIntent
                ).addRemoteInput(remoteInput).build()
            )
        }

        if (phase != Phase.DONE_OK) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_manage,
                    "Developer Options",
                    PendingIntent.getActivity(
                        this,
                        3,
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, GodModePairingService::class.java).apply { action = ACTION_STOP },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
        }

        val notif = builder.build()
        startForeground(NOTIF_ID, notif)
        nm?.notify(NOTIF_ID, notif)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Prime Mode", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "PeerLink Prime Mode setup and status"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(ch)
        }
    }
}
