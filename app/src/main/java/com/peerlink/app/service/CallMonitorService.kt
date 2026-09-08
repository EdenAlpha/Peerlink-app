package com.peerlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.peerlink.app.core.AppState
import com.peerlink.app.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CallMonitorService — runs as a foreground service.
 *
 * MODES:
 *
 *  STANDALONE (Apex Settings, persistent):
 *    User activates from settings. Stays active until manually disabled.
 *    Counts all incoming calls. Sends urgent notification if same number
 *    calls 3+ times. Declined silently (caller hears nothing / busy tone
 *    depending on carrier — we reject immediately from our side).
 *
 *  GAMEPLAY (Apex Mode dashboard):
 *    Only activates once TunnelEngine signals ≥22 game packets/sec.
 *    Deactivates when game traffic stops. This is the in-game version.
 *
 * The caller does NOT hear a ring — we use endCall() immediately on RINGING state.
 * The user on THIS phone also hears nothing. The app logs the attempt and shows
 * a badge count in the Apex Settings screen.
 */
class CallMonitorService : Service() {

    companion object {
        private const val TAG            = "CallMonitor"
        const val ACTION_START_STANDALONE  = "com.peerlink.CALL_BLOCK_START"
        const val ACTION_START_GAMEPLAY    = "com.peerlink.CALL_BLOCK_GAMEPLAY"
        const val ACTION_STOP              = "com.peerlink.CALL_BLOCK_STOP"
        const val NOTIF_CHANNEL_ID         = "lanlink_call_block"
        const val NOTIF_ID                 = 42
        const val URGENT_NOTIF_ID          = 43
        private const val URGENT_THRESHOLD = 3   // calls from same number to trigger urgent alert

        // Shared state accessible from TunnelEngine and UI
        val isRunning         = AtomicBoolean(false)
        val blockedCallCount  = AtomicInteger(0)
        val isGameplayMode    = AtomicBoolean(false)

        // Map: normalized phone number → call count this session
        val callerCounts = ConcurrentHashMap<String, Int>()

        fun start(context: Context, gameplayMode: Boolean = false) {
            val action = if (gameplayMode) ACTION_START_GAMEPLAY else ACTION_START_STANDALONE
            val intent = Intent(context, CallMonitorService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CallMonitorService::class.java).apply { action = ACTION_STOP })
        }

        fun isActive(context: Context): Boolean {
            // UI must reflect the service that is actually monitoring calls,
            // not a stale preference left behind after permission/service loss.
            return isRunning.get()
        }
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null  // TelephonyCallback on API 31+
    private var standalone = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_START_GAMEPLAY -> {
                standalone = false
                isGameplayMode.set(true)
            }
            else -> {
                standalone = true
                isGameplayMode.set(false)
            }
        }

        createNotificationChannel()
        // Android 14+ (targetSdk 36) requires the foreground service type be passed
        // to startForeground() matching the <service android:foregroundServiceType="phoneCall">
        // manifest declaration. Omitting it throws MissingForegroundServiceTypeException → crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } catch (e: Exception) {
                Log.w(TAG, "typed startForeground failed, falling back: ${e.message}")
                try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
            }
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        // FIX: Guard with READ_PHONE_STATE permission check before startMonitoring().
        // On Android 12+ (API 31+), registerTelephonyCallback() throws SecurityException
        // if READ_PHONE_STATE has not been granted at runtime — even with @Suppress("MissingPermission").
        // Stop gracefully instead of crashing so the user can re-enable after granting the permission.
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            AppState.appendLog("[CALL-BLOCK] READ_PHONE_STATE not granted — stopping service gracefully")
            getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("apex_call_block_gameplay", false).apply()
            isRunning.set(false)
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        startMonitoring()
        isRunning.set(true)
        AppState.appendLog("[CALL-BLOCK] Service started (standalone=$standalone)")
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    // ─── Phone state monitoring ───────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun startMonitoring() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: TelephonyCallback
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleCallState(state, null)
            }
            telephonyCallback = cb
            try {
                telephonyManager?.registerTelephonyCallback(mainExecutor, cb)
            } catch (e: SecurityException) {
                // READ_PHONE_STATE was revoked mid-session — stop cleanly
                AppState.appendLog("[CALL-BLOCK] SecurityException registering callback: ${e.message} — stopping")
                stopMonitoring()
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            try {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: SecurityException) {
                AppState.appendLog("[CALL-BLOCK] SecurityException listening: ${e.message} — stopping")
                stopMonitoring()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun stopMonitoring() {
        isRunning.set(false)
        isGameplayMode.set(false)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unregister error: ${e.message}")
        }

        try { stopForeground(true) } catch (_: Exception) {}
        try { stopSelf() } catch (_: Exception) {}
        AppState.appendLog("[CALL-BLOCK] Service stopped")
    }

    @Suppress("MissingPermission")
    private fun handleCallState(state: Int, phoneNumber: String?) {
        if (state != TelephonyManager.CALL_STATE_RINGING) return

        // Immediately reject the call
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                tm?.endCall()
            } else {
                @Suppress("DEPRECATION")
                val method = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "phone")
                if (method != null) {
                    val itelephony = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                        .getMethod("asInterface", android.os.IBinder::class.java)
                        .invoke(null, method)
                    itelephony?.javaClass?.getMethod("endCall")?.invoke(itelephony)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "endCall failed: ${e.message}")
            // Fallback: mute ringer and set DND — caller still hears ringing on their end
            // but user is not disturbed
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                am?.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
            } catch (_: Exception) {}
        }

        // Record the blocked call
        val count = blockedCallCount.incrementAndGet()
        val callerKey = phoneNumber?.filter { it.isDigit() }?.takeLast(10) ?: "unknown"
        val callerDisplayName = phoneNumber ?: "Unknown"

        val callerCount = (callerCounts[callerKey] ?: 0) + 1
        callerCounts[callerKey] = callerCount

        AppState.appendLog("[CALL-BLOCK] Blocked call from $callerDisplayName (total=$count, from this number=$callerCount)")

        // Update persistent notification badge
        updateNotification(count)

        // Urgent alert if same caller rings multiple times
        if (callerCount >= URGENT_THRESHOLD) {
            showUrgentNotification(callerDisplayName, callerCount)
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                NOTIF_CHANNEL_ID,
                "LanLink Call Blocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) })

            // Separate high-priority channel for urgent alerts
            nm.createNotificationChannel(NotificationChannel(
                "${NOTIF_CHANNEL_ID}_urgent",
                "LanLink Call Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a contact calls multiple times"
                enableVibration(true)
            })
        }
    }

    private fun buildNotification(blockedCount: Int = 0): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Call Blocker Active")
            .setContentText(if (blockedCount == 0) "Silently declining incoming calls" else "Blocked $blockedCount call${if (blockedCount == 1) "" else "s"}")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(count: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(count))
    }

    private fun showUrgentNotification(callerName: String, count: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "${NOTIF_CHANNEL_ID}_urgent")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        nm.notify(URGENT_NOTIF_ID, builder
            .setContentTitle("Possible urgent call")
            .setContentText("$callerName has called $count times — may be urgent")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        )
        AppState.appendLog("[CALL-BLOCK] Urgent alert: $callerName × $count")
    }
}
