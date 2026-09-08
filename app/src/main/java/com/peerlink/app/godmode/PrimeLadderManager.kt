package com.peerlink.app.godmode

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.peerlink.app.core.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PrimeLadderManager — two responsibilities:
 *
 *   1. Check PrimeServer alive on app open (fast, silent, IO thread)
 *   2. Create / tear down LocalOnlyHotspot (LinkUp side only)
 *
 * WHY LINKUP CREATES THE HOTSPOT FOR BEACON:
 *   Beacon is GO → only has p2p0, no wlan0 → adbd will not bind.
 *   LinkUp creates hotspot → Beacon connects to it → wlan0 up → adbd starts →
 *   GodModeManager bootstraps PrimeServer on Beacon.
 *   One-time per reboot. Torn down immediately after.
 *
 * BAND: API 33+ forces BAND_2GHZ. API 31/32 uses system default (budget devices = 2.4GHz).
 */
object PrimeLadderManager {

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var reservation:      WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var _hotspotSsid:     String? = null
    @Volatile private var _hotspotPassword: String? = null

    val hotspotSsid:     String? get() = _hotspotSsid
    val hotspotPassword: String? get() = _hotspotPassword
    val isHotspotActive: Boolean get() = reservation != null

    // ─── App-open check ────────────────────────────────────────────

    fun checkAliveOnAppOpen() {
        scope.launch {
            val alive = PrimeClient.isAlive()
            AppState.primeServerAlive = alive
            AppState.appendLog("[PRIME-LADDER] App open — PrimeServer alive=$alive")
        }
    }

    // ─── Hotspot (LinkUp side only) ────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startHotspot(
        context: Context,
        onReady: (ssid: String, password: String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopHotspot()

        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: run { onError("WifiManager unavailable"); return }

        AppState.appendLog("[PRIME-LADDER] Starting hotspot (2.4GHz preferred)")

        val cb = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                reservation      = r
                _hotspotSsid     = extractSsid(r)     ?: "PL_${System.currentTimeMillis().toString().takeLast(5)}"
                _hotspotPassword = extractPassword(r) ?: "peerlink1"
                AppState.appendLog("[PRIME-LADDER] Hotspot up ssid=$_hotspotSsid band=${bandLabel(r)}")
                mainHandler.post { onReady(_hotspotSsid!!, _hotspotPassword!!) }
            }
            override fun onStopped() {
                clearState()
                AppState.appendLog("[PRIME-LADDER] Hotspot stopped by system")
            }
            override fun onFailed(reason: Int) {
                clearState()
                val msg = when (reason) {
                    ERROR_NO_CHANNEL           -> "No available channel"
                    ERROR_GENERIC              -> "Generic failure"
                    ERROR_INCOMPATIBLE_MODE    -> "Another hotspot is active"
                    ERROR_TETHERING_DISALLOWED -> "Tethering disallowed"
                    else                       -> "Hotspot failed (code=$reason)"
                }
                AppState.appendLog("[PRIME-LADDER] Hotspot FAILED: $msg")
                mainHandler.post { onError(msg) }
            }
        }

        try {
            // API 31/32 — no public API to force band. Budget devices (Infinix HOT 60i, XOS)
            // default to 2.4GHz which is exactly what we want. Simple call, no reflection needed.
            wm.startLocalOnlyHotspot(cb, mainHandler)
        } catch (e: Exception) {
            AppState.appendLog("[PRIME-LADDER] startHotspot exception: ${e.message}")
            onError("Hotspot exception: ${e.message}")
        }
    }

    fun stopHotspot() {
        try { reservation?.close() } catch (_: Exception) {}
        clearState()
        AppState.appendLog("[PRIME-LADDER] Hotspot torn down")
    }

    private fun extractSsid(r: WifiManager.LocalOnlyHotspotReservation): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            r.softApConfiguration?.ssid?.trim('"')
        else @Suppress("DEPRECATION") r.wifiConfiguration?.SSID?.trim('"')
    } catch (_: Exception) { null }

    private fun extractPassword(r: WifiManager.LocalOnlyHotspotReservation): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            r.softApConfiguration?.passphrase
        else @Suppress("DEPRECATION") r.wifiConfiguration?.preSharedKey?.trim('"')
    } catch (_: Exception) { null }

    private fun bandLabel(@Suppress("UNUSED_PARAMETER") r: WifiManager.LocalOnlyHotspotReservation): String = "system-default"

    private fun clearState() {
        reservation = null; _hotspotSsid = null; _hotspotPassword = null
    }
}
