package com.peerlink.app.service

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.GodModeManager
import com.peerlink.app.godmode.PrimeClient
import com.peerlink.app.godmode.shizuku.PrimeShizukuBootstrapEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BootReceiver — fired on BOOT_COMPLETED and MY_PACKAGE_REPLACED.
 *
 * Photocopy of Shizuku BootCompleteReceiver:
 *   • Restores persistent Apex Mode settings that survive reboots (call blocker).
 *   • If Prime was bootstrapped (WRITE_SECURE_SETTINGS held) and the engine is
 *     dead, re-launches it exactly the way Shizuku adbStart does: write the
 *     wireless-debugging settings triple, NSD-resolve adbd's connect port,
 *     connect once, run the starter, close. One attempt, then give up —
 *     never hammer ADB. The runtime guardian handles later retries.
 *
 * Does NOT restart the VPN — that would be intrusive. User must tap
 * Initialize + connect to peer again after reboot.
 *
 * Registration in AndroidManifest.xml:
 *   <receiver android:name=".service.BootReceiver" android:exported="false">
 *       <intent-filter>
 *           <action android:name="android.intent.action.BOOT_COMPLETED"/>
 *           <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
 *       </intent-filter>
 *   </receiver>
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS       = "godmode_prefs"
        private const val KEY_CALL_BLK = "apex_call_block_standalone"
        private const val TAG = "PeerLinkBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i(TAG, "Boot/update received — checking persistent Apex settings")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Restore standalone call blocker if user had it on
        if (prefs.getBoolean(KEY_CALL_BLK, false)) {
            Log.i(TAG, "Restoring call blocker (was enabled before reboot)")
            AppState.appendLog("[BOOT      ] Restoring call blocker from persistent state")
            CallMonitorService.start(context, gameplayMode = false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            adbStart(context)
        } else {
            Log.w(TAG, "No support start Prime on boot before Android 13")
        }
    }

    /**
     * Shizuku BootCompleteReceiver.adbStart, copied: only when the settings
     * permission is held and the engine is genuinely dead. goAsync() keeps the
     * receiver alive across the coroutine; Shizuku gives NSD 3 seconds.
     */
    private fun adbStart(context: Context) {
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        if (PrimeClient.isAlive()) return

        val cr = context.contentResolver
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppState.appendLog("[BOOT      ] Prime engine not running — attempting Shizuku-style auto start")
                val result = PrimeShizukuBootstrapEngine(context).start(
                    needBootstrap = false,
                    discoverTimeoutMs = 3_000L,
                )
                if (result is PrimeShizukuBootstrapEngine.Result.Success) {
                    AppState.appendLog("[BOOT      ] Prime engine auto-started via 127.0.0.1:${result.port}")
                    GodModeManager.onEngineAutoStarted(context)
                } else {
                    AppState.appendLog("[BOOT      ] Prime auto-start not completed; guardian will retry when possible")
                }
            } catch (_: Exception) {
                AppState.appendLog("[BOOT      ] Prime auto-start failed; guardian will retry when possible")
            } finally {
                pending.finish()
            }
        }
    }
}
