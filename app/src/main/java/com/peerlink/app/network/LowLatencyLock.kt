package com.peerlink.app.network

import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.WorkSource

/**
 * Attributes a WIFI_MODE_FULL_LOW_LATENCY WifiLock to eFootball's uid so the
 * OS evaluates the GAME's importance, not PeerLink's.
 *
 * Why: a foreground VPN service never reaches IMPORTANCE_FOREGROUND (100),
 * so a lock carrying PeerLink's own uid can never disable Wi-Fi power save
 * during a match. The game, with its activity on screen, is at 100.
 *
 * Mechanism (verified against AOSP Android 10-15 sources):
 *   - WifiLock.setWorkSource(ws) BEFORE acquire() only writes the local
 *     client field (mHeld == false -> no binder call -> no permission check).
 *   - acquire() passes the WorkSource to WifiServiceImpl.acquireWifiLock,
 *     which on Android 10+ checks ONLY WAKE_LOCK (already in the manifest)
 *     and hands the WorkSource to WifiLockManager untouched; the low-latency
 *     watch list keys per-UID entries from that WorkSource and checks each
 *     uid's own foreground state (ClientModeImpl.setPowerSave -> driver
 *     power save off while the game is foreground).
 *
 * Android 8.1/9 gate a non-empty WorkSource behind UPDATE_DEVICE_STATS
 * (signature|privileged) and would throw SecurityException at acquire(); the
 * SDK >= Q guard keeps those devices on today's behavior.
 *
 * Must be called BEFORE lock.acquire(). Returns the injected uid, or -1.
 */
object LowLatencyLock {
    const val GAME_PACKAGE = "jp.konami.pesam"

    fun attachGame(lock: WifiManager.WifiLock, pm: PackageManager): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return try {
            val uid = pm.getPackageUid(GAME_PACKAGE, 0)
            if (uid <= 0) return -1
            val ws = WorkSource()
            // add(int) is @hide/@UnsupportedAppUsage (greylist): the one
            // reflective call; it is how the framework itself populates a
            // WorkSource from battery-stats attribution.
            WorkSource::class.java.getMethod("add", Int::class.javaPrimitiveType)
                .invoke(ws, uid)
            lock.setWorkSource(ws)
            uid
        } catch (t: Throwable) {
            // Any failure (game missing, hidden-API block, future ownership
            // check) falls back to today's behavior: no WorkSource at all.
            try { lock.setWorkSource(null) } catch (_: Throwable) {}
            -1
        }
    }
}
