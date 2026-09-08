package com.peerlink.app.godmode

import android.os.Build
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * DeviceChecker — detects the device's ROM skin at runtime by reading
 * system properties, and returns the exact pre-pairing steps the user
 * needs to complete on their specific device for pm grant to succeed.
 *
 * Why this is needed:
 *   pm grant WRITE_SECURE_SETTINGS fails silently or throws SecurityException
 *   on several manufacturer ROMs unless specific Developer Options toggles
 *   are enabled first. This class surfaces the exact toggles per device so
 *   users never hit a mysterious failure.
 *
 * Detection strategy:
 *   - HyperOS: ro.mi.os.version.name is non-empty (exclusive to HyperOS, not present on MIUI)
 *   - MIUI:    ro.miui.ui.version.name is non-empty (present on MIUI and older HyperOS builds)
 *   - Samsung: Build.MANUFACTURER == "samsung"
 *   - ColorOS: Build.MANUFACTURER in oppo/realme/oneplus OR ro.coloros.version non-empty
 *   - Stock:   none of the above
 */
object DeviceChecker {

    enum class RomType {
        STOCK,              // Pixel, AOSP, generic — no extra steps
        SAMSUNG,            // One UI — needs "USB Debugging (Security Settings)" ON
        XIAOMI_MIUI,        // MIUI — needs all 3 USB debugging toggles ON
        XIAOMI_HYPEROS,     // HyperOS — same toggles as MIUI, different detection
        COLOR_OS,           // ColorOS/Realme/OnePlus — needs "Disable Permission Monitoring" ON
        TRANSSION_XOS       // Infinix/Tecno/itel (XOS) — standard Wireless Debugging, no extra toggles
    }

    // Cached on first call — ROM type never changes at runtime
    private var cachedRom: RomType? = null

    fun detect(): RomType {
        cachedRom?.let { return it }

        val brand        = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val xiaomiBrands = setOf("xiaomi", "redmi", "poco")
        // Transsion Group brands: Infinix, Tecno, itel (all run XOS on top of Android)
        val transsionBrands = setOf("infinix", "tecno", "itel", "transsion")

        val rom = when {
            // HyperOS: check before MIUI because HyperOS also sets ro.miui.ui.version.name
            brand in xiaomiBrands && getProperty("ro.mi.os.version.name").isNullOrBlank().not() ->
                RomType.XIAOMI_HYPEROS

            // MIUI
            brand in xiaomiBrands && getProperty("ro.miui.ui.version.name").isNullOrBlank().not() ->
                RomType.XIAOMI_MIUI

            // Samsung
            manufacturer == "samsung" || brand == "samsung" ->
                RomType.SAMSUNG

            // ColorOS / OPPO / Realme / OnePlus (ColorOS-based)
            manufacturer in setOf("oppo", "realme", "oneplus") ||
            brand in setOf("oppo", "realme", "oneplus") ||
            getProperty("ro.build.version.opporom").isNullOrBlank().not() ||
            getProperty("ro.coloros.version").isNullOrBlank().not() ->
                RomType.COLOR_OS

            // Transsion Group: Infinix, Tecno, itel — all run XOS.
            // Detection uses brand, manufacturer string, and the ro.transsion.version
            // system property which is present on all XOS devices.
            manufacturer in transsionBrands ||
            brand in transsionBrands ||
            manufacturer.contains("infinix", ignoreCase = true) ||
            manufacturer.contains("tecno", ignoreCase = true) ||
            manufacturer.contains("transsion", ignoreCase = true) ||
            getProperty("ro.transsion.version").isNullOrBlank().not() ->
                RomType.TRANSSION_XOS

            else -> RomType.STOCK
        }

        cachedRom = rom
        return rom
    }

    /**
     * Returns a human-readable label for the detected ROM.
     * Used in UI badges and log entries.
     */
    fun getDeviceLabel(): String = when (detect()) {
        RomType.STOCK            -> "Stock Android"
        RomType.SAMSUNG          -> "Samsung One UI"
        RomType.XIAOMI_MIUI      -> "Xiaomi MIUI"
        RomType.XIAOMI_HYPEROS   -> "Xiaomi HyperOS"
        RomType.COLOR_OS         -> "ColorOS / Realme / OnePlus"
        RomType.TRANSSION_XOS    -> "Infinix / Tecno / itel (XOS)"
    }

    /**
     * Returns true if this device needs extra Developer Options toggles
     * before pairing will work. Used to conditionally show warning boxes in UI.
     */
    fun requiresExtraSteps(): Boolean = detect() != RomType.STOCK && detect() != RomType.TRANSSION_XOS

    /**
     * Returns the complete, step-by-step instructions the user must follow
     * BEFORE starting the pairing flow. Returns null for stock Android
     * where no extra steps are needed.
     *
     * These instructions are shown:
     *   - In the in-app setup wizard (GodModeSetupSheet Step 2)
     *   - In the pairing notification (GodModePairingService)
     */
    fun getPrePairingInstructions(): String? = when (detect()) {

        RomType.SAMSUNG ->
            "Samsung device detected — one extra step needed:\n\n" +
            "In Developer Options, enable:\n" +
            "  • USB Debugging (Security Settings) → ON\n\n" +
            "This is a separate toggle from regular USB Debugging. " +
            "Without it, permission grant will silently fail on Samsung."

        RomType.XIAOMI_MIUI ->
            "Xiaomi MIUI device detected — enable ALL of these in Developer Options:\n\n" +
            "  • USB Debugging → ON\n" +
            "  • USB Debugging (Security Settings) → ON\n" +
            "  • Wireless Debugging → ON\n\n" +
            "All three must be active before you tap Pair. Missing any one will cause pairing to fail."

        RomType.XIAOMI_HYPEROS ->
            "Xiaomi HyperOS device detected — enable ALL of these in Developer Options:\n\n" +
            "  • USB Debugging → ON\n" +
            "  • USB Debugging (Security Settings) → ON\n" +
            "  • Wireless Debugging → ON\n\n" +
            "All three must be active before you tap Pair. Missing any one will cause pairing to fail."

        RomType.COLOR_OS ->
            "ColorOS / Realme / OnePlus device detected — two extra steps:\n\n" +
            "  1. In Developer Options, enable:\n" +
            "     • Disable Permission Monitoring → ON\n\n" +
            "  2. Toggle USB Debugging OFF, then back ON.\n\n" +
            "Do both steps before pairing, otherwise the permission grant will be blocked."

        // XOS does not require any extra Developer Options toggles beyond the standard
        // Wireless Debugging flow. The only known difference is that XOS never writes
        // adb_wifi_port to Settings.Global — but LanLink handles this automatically
        // using mDNS service discovery, so no extra user action is needed.
        RomType.TRANSSION_XOS -> null

        RomType.STOCK -> null
    }

    /**
     * Short single-line hint used in compact UI spaces (e.g. notification
     * collapsed view, small cards).
     */
    fun getShortHint(): String? = when (detect()) {
        RomType.SAMSUNG        -> "Enable 'USB Debugging (Security Settings)' first"
        RomType.XIAOMI_MIUI,
        RomType.XIAOMI_HYPEROS -> "Enable all 3 USB Debugging toggles first"
        RomType.COLOR_OS       -> "Enable 'Disable Permission Monitoring' first"
        RomType.TRANSSION_XOS  -> null  // no extra steps needed on XOS
        RomType.STOCK          -> null
    }

    // ── Private ──────────────────────────────────────────────────────────

    private fun getProperty(property: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $property")
            BufferedReader(InputStreamReader(process.inputStream), 1024).use { it.readLine() }
        } catch (_: IOException) {
            null
        }
    }
}
