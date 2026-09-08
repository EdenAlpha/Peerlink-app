package com.peerlink.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.peerlink.app.core.AppState

/**
 * BootReceiver — fired on BOOT_COMPLETED and MY_PACKAGE_REPLACED.
 *
 * Restores persistent Apex Mode settings that survive reboots:
 *   • Call blocker: if user had it enabled, restart CallMonitorService
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
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i("BootReceiver", "Boot/update received — checking persistent Apex settings")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Restore standalone call blocker if user had it on
        if (prefs.getBoolean(KEY_CALL_BLK, false)) {
            Log.i("BootReceiver", "Restoring call blocker (was enabled before reboot)")
            AppState.appendLog("[BOOT      ] Restoring call blocker from persistent state")
            CallMonitorService.start(context, gameplayMode = false)
        }
    }
}
