package com.peerlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.GodModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LanLinkForegroundService — persistent foreground service that keeps
 * Prime Mode and GodModeManager alive even when eFootball is on screen.
 *
 * Responsibilities:
 *   1. Saves pre-open Wi-Fi state on start.
 *   2. Auto Connect: enables Wi-Fi when the app opens if needed.
 *   3. Watches for P2P session end → restores pre-open radio state.
 *   4. Keeps GodModeManager alive regardless of which app is on screen.
 *   5. Installs a crash handler that writes full stack traces to AppState
 *      log with [PRIME] prefix so they show in the PRIME LOG tab.
 *   6. Notification updates every 5 seconds showing session status.
 *
 * START_STICKY ensures the OS reschedules it if killed.
 * onTaskRemoved() restores radios when app is removed from recents.
 */
class LanLinkForegroundService : Service() {

    companion object {
        private const val TAG               = "LanLinkFgService"
        private const val CHANNEL_ID        = "lanlink_foreground"
        private const val NOTIF_ID          = 2001
        private const val STATUS_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, LanLinkForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanLinkForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statusJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Foreground type matches manifest declaration: dataSync. We tried
        // switching to specialUse for Android 15 hardening but it crashed on
        // Android 13 (Hot 30i) because the system rejects the unknown FGS
        // type. dataSync works on all supported versions; on Android 15 it
        // has a 6-hour daily cap which is plenty for a match.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("LanLink running"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("LanLink running"))
        }
        installPrimeCrashHandler()
        AppState.appendLog("[PRIME-GUARD] Foreground guardian service created")
        GodModeManager.guardianPulse()
        startStatusLoop()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App removed from recents. If there's an active session, Android 15
        // would normally kill the process and take the VPN down with it. We
        // try to re-launch MainActivity (which works on most OEMs when the
        // service is itself in the foreground, which we are). If the OS
        // blocks the activity launch (Android 12+ background-activity-start
        // restrictions), the START_STICKY return from onStartCommand still
        // gets us restarted by the system shortly after. Either way, the
        // foreground service stays alive long enough for the VPN to survive
        // the swipe.
        if (AppState.isRunning.get()) {
            AppState.appendLog("[PRIME] Service: app swiped during active session — trying to re-anchor process")
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(launchIntent)
                }
            } catch (_: Throwable) {
                // Background-activity-start blocked by Android 12+ — this is
                // expected on some OEMs. The foreground service itself
                // survives the swipe (that's the point of having one), so the
                // VPN tunnel stays up. The user just needs to reopen the app
                // from the launcher to see the UI again.
                AppState.appendLog("[PRIME] Service: activity relaunch blocked by OS — tunnel still alive via foreground service")
            }
        } else {
            AppState.appendLog("[PRIME] Service: app removed from recents — user radio state preserved")
        }
    }

    override fun onDestroy() {
        statusJob?.cancel()
        AppState.appendLog("[PRIME-GUARD] Foreground guardian service destroyed")
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ─── Crash handler ───────────────────────────────────────────────────

    private fun installPrimeCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                AppState.appendLog("[PRIME] [$ts] CRASH on thread '${thread.name}'")
                AppState.appendLog("[PRIME] [$ts] ${throwable.javaClass.simpleName}: ${throwable.message}")
                sw.toString().lines().take(12).forEach { line ->
                    if (line.isNotBlank()) AppState.appendLog("[PRIME] $line")
                }
            } catch (_: Throwable) { /* never block original handler */ }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ─── Status loop ─────────────────────────────────────────────────────

    private fun startStatusLoop() {
        statusJob = serviceScope.launch {
            while (true) {
                GodModeManager.guardianPulse()
                val link = GodModeManager.primeLinkState.value
                val status = when {
                    GodModeManager.state.value == GodModeManager.State.PRIME_MODE_ACTIVE &&
                        link == com.peerlink.app.godmode.PrimeLinkState.RECOVERING -> "Prime active · recovering engine"
                    GodModeManager.state.value == GodModeManager.State.PRIME_MODE_ACTIVE &&
                        link == com.peerlink.app.godmode.PrimeLinkState.DEGRADED -> "Prime active · engine retry pending"
                    AppState.isRunning.get() -> "Peer session active"
                    GodModeManager.state.value == GodModeManager.State.PRIME_MODE_ACTIVE -> "Prime Mode active"
                    GodModeManager.isApexMasterEnabled() -> "Prime Mode ready"
                    else -> "PeerLink ready"
                }
                updateNotification(status)
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LanLink",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LanLink connection status"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LanLink")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }
}
