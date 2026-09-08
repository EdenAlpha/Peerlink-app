package com.peerlink.app.godmode

import android.content.Context
import android.os.Build
import com.peerlink.app.core.AppState
import com.peerlink.app.core.PrimeGameplayTracker

/**
 * Runtime-verified graphics compatibility layer.
 *
 * Prime does not infer support from SDK level or shell help text. Every backend
 * is verified by reading Android's state back after a safe probe. Probes never
 * run during protected multiplayer gameplay.
 */
class PrimeGraphicsController(
    context: Context,
    private val execute: (String) -> PrimeExecResult,
) {
    data class ProbeResult(
        val backend: PrimeGraphicsBackend,
        val verified: Boolean,
        val detail: String,
    )

    companion object {
        private const val GAME = "jp.konami.pesam"
        private const val PREFS = "prime_graphics_compat"
        private const val KEY_PROFILE = "profile"
        private const val KEY_BACKEND = "backend"
        private const val KEY_OVERLAY_OWNED = "overlay_owned"
        private const val KEY_OVERLAY_BACKUP = "overlay_backup"
        private const val KEY_MODERN_OWNED_MODE = "modern_owned_mode"
        private const val KEY_LEGACY_OWNED = "legacy_owned"
        private const val KEY_LEGACY_BACKUP = "legacy_backup"
        private const val NULL_OVERLAY = "__PEERLINK_NULL__"
        private const val DOWNSCALED_ID = 168419799L

        private val SCALE_CHANGE_IDS = mapOf(
            "0.90" to 182811243L,
            "0.85" to 189969734L,
            "0.80" to 176926753L,
            "0.75" to 189969779L,
            "0.70" to 176926829L,
        )
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceProfile(): String {
        val version = runCatching {
            val pi = appContext.packageManager.getPackageInfo(GAME, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        }.getOrDefault(-1L)
        return "${Build.FINGERPRINT}|${Build.VERSION.SDK_INT}|$version"
    }

    @Synchronized fun probe(performanceAvailable: Boolean, force: Boolean = false): ProbeResult {
        if (Build.VERSION.SDK_INT < 31) {
            return remember(ProbeResult(
                PrimeGraphicsBackend.NONE,
                false,
                "Android ${Build.VERSION.SDK_INT} has no standard Game Mode backbuffer intervention path",
            ))
        }
        if (PrimeGameplayTracker.isMatchProtected()) {
            return ProbeResult(
                PrimeGraphicsBackend.PENDING,
                false,
                "Verification deferred until the live P2P match ends",
            )
        }

        val profile = deviceProfile()
        if (!force && prefs.getString(KEY_PROFILE, null) == profile) {
            val cached = runCatching {
                PrimeGraphicsBackend.valueOf(
                    prefs.getString(KEY_BACKEND, PrimeGraphicsBackend.PENDING.name)!!
                )
            }.getOrDefault(PrimeGraphicsBackend.PENDING)
            if (cached != PrimeGraphicsBackend.PENDING &&
                cached != PrimeGraphicsBackend.NONE &&
                cachedSurfaceStillReachable(cached)
            ) {
                return ProbeResult(cached, true, "Verified backend revalidated for this OS/game build")
            }
        }

        // 1) Modern GameManager custom-mode override. This is the preferred path:
        // it does not require changing global display resolution and a probe can
        // be performed without switching the game's current mode.
        val configsBefore = execute("cmd game list-configs $GAME")
        if (configsBefore.ok && hasModeScalingField(configsBefore.output, 4)) {
            return remember(ProbeResult(
                PrimeGraphicsBackend.MODERN_CUSTOM,
                true,
                "Modern GameManager custom scaling is already present and readable",
            ))
        }

        // Activation only reads/revalidates. A compatibility probe can restart
        // the game and costs multiple shell round trips; the user runs it explicitly.
        if (!force) return ProbeResult(
            PrimeGraphicsBackend.PENDING, false,
            "Use Verify graphics before opening the game to enable render scaling",
        )
        if (PrimeClient.isPackageForeground(GAME) != false) return ProbeResult(
            PrimeGraphicsBackend.PENDING, false,
            "Close eFootball before verifying graphics compatibility",
        )
        // Never overwrite an existing custom configuration just to test scaling.
        if (!configsBefore.ok || Regex("(?:mode|gameMode)\\s*[=:]\\s*4", RegexOption.IGNORE_CASE).containsMatchIn(configsBefore.output)) return ProbeResult(
            PrimeGraphicsBackend.PENDING, false, "Existing game configuration could not be safely probed",
        )
        val modeBefore = execute("cmd game list-modes $GAME").takeIf { it.ok }
            ?.output?.let { PrimePerformanceModeParser.currentMode(it) }
        if (modeBefore == null) return ProbeResult(
            PrimeGraphicsBackend.PENDING, false, "Current Game Mode could not be saved for verification",
        )
        val modernSet = execute("cmd game set --downscale 0.9 $GAME")
        val modernReadback = readGameConfigState()
        val modernVerified = hasModeScale(modernReadback, 4, "0.90")
        // AOSP rejects reset --mode 4. Explicitly disable only custom downscaling,
        // then restore the selected mode. Never erase all game configurations.
        val cleanup = execute("cmd game set --downscale disable $GAME")
        val restoreMode = execute("cmd game mode $modeBefore $GAME")
        if (modernSet.ok || modernVerified) {
            val afterCleanup = readGameConfigState()
            val modeAfter = execute("cmd game list-modes $GAME")
            val disabled = hasModeScale(afterCleanup, 4, "-1.00") || hasModeScale(afterCleanup, 4, "1.00")
            if (!cleanup.ok || !restoreMode.ok || !disabled || !modeAfter.ok ||
                PrimePerformanceModeParser.currentMode(modeAfter.output) != modeBefore) {
                return ProbeResult(PrimeGraphicsBackend.PENDING, false,
                    "Graphics probe restore was not verified; select Native before launching the game")
            }
        }
        if (modernVerified) {
            return remember(ProbeResult(
                PrimeGraphicsBackend.MODERN_CUSTOM, true,
                "Modern GameManager scaling verified and previous mode restored",
            ))
        }

        // 2) Legacy Android 12 / early Android 13 direct compatibility route.
        // Some old implementations perform the action but still return a
        // non-zero shell status, so exitCode is deliberately NOT the verifier.
        val compatBefore = execute("dumpsys platform_compat")
        if (compatBefore.ok) {
            val existingScale = activeLegacyScale(compatBefore.output)
            if (compatOverrideEnabled(compatBefore.output, DOWNSCALED_ID) && existingScale != null) {
                return remember(ProbeResult(
                    PrimeGraphicsBackend.LEGACY_DIRECT,
                    true,
                    "Legacy GameManager downscale override is already active and readable",
                ))
            }
            if (!compatMentionsGameOverride(compatBefore.output, DOWNSCALED_ID)) {
                execute("cmd game downscale 0.9 $GAME")
                val compatAfter = execute("dumpsys platform_compat")
                val legacyVerified = compatAfter.ok &&
                    compatOverrideEnabled(compatAfter.output, DOWNSCALED_ID) &&
                    compatOverrideEnabled(compatAfter.output, SCALE_CHANGE_IDS.getValue("0.90"))
                // We created this only for probing, so put the package back.
                execute("cmd game downscale disable $GAME")
                if (legacyVerified) {
                    return remember(ProbeResult(
                        PrimeGraphicsBackend.LEGACY_DIRECT,
                        true,
                        "Legacy GameManager scaling verified through platform_compat",
                    ))
                }
            }
        }

        // 3) Official game_overlay fallback. Preserve the exact existing OEM
        // value, verify both DeviceConfig writeback and GameManager ingestion,
        // then restore the exact original value. We only accept this backend
        // when Performance mode is genuinely available, because game_overlay
        // interventions are mode-scoped and Prime must not silently force an
        // unrelated mode.
        if (performanceAvailable) {
            val overlayBefore = execute("device_config get game_overlay $GAME")
            if (overlayBefore.ok) {
                val original = overlayBefore.output.trim()
                val probeValue = "mode=2,downscaleFactor=0.9"
                val put = execute("device_config put game_overlay $GAME ${shellQuote(probeValue)}")
                val readback = execute("device_config get game_overlay $GAME")
                val ingested = execute("sleep 0.25; dumpsys game")
                val overlayVerified = put.ok &&
                    readback.ok &&
                    readback.output.contains("downscaleFactor=0.9") &&
                    hasModeScale(ingested.output, 2, "0.90")
                restoreOverlayValue(original)
                if (overlayVerified) {
                    return remember(ProbeResult(
                        PrimeGraphicsBackend.DEVICE_CONFIG_PERFORMANCE,
                        true,
                        "Official game_overlay scaling verified and OEM value restored",
                    ))
                }
            }
        }

        val detail = buildString {
            append("No downscale backend produced verified Android readback")
            if (!modernSet.ok && modernSet.output.isNotBlank()) {
                append(" · GameManager: ")
                append(modernSet.output.lineSequence().firstOrNull()?.take(70))
            }
        }
        return remember(ProbeResult(PrimeGraphicsBackend.NONE, false, detail))
    }

    @Synchronized fun applyScale(
        scale: String,
        backend: PrimeGraphicsBackend,
        performanceWanted: Boolean,
        performanceAvailable: Boolean,
    ): Boolean {
        if (scale != "1.00" && scale !in SCALE_CHANGE_IDS.keys) return false
        if (PrimeGameplayTracker.isMatchProtected()) {
            AppState.appendLog("[PRIME-GPU  ] Render-scale change deferred — live P2P match protected")
            return false
        }

        return when (backend) {
            PrimeGraphicsBackend.MODERN_CUSTOM ->
                applyModern(scale, performanceWanted, performanceAvailable)
            PrimeGraphicsBackend.LEGACY_DIRECT ->
                applyLegacy(scale)
            PrimeGraphicsBackend.DEVICE_CONFIG_PERFORMANCE ->
                applyDeviceConfig(scale, performanceWanted, performanceAvailable)
            else -> false
        }
    }

    private fun applyModern(
        scale: String,
        performanceWanted: Boolean,
        performanceAvailable: Boolean,
    ): Boolean {
        if (scale == "1.00") {
            val ownedMode = prefs.getInt(KEY_MODERN_OWNED_MODE, -1)
            if (ownedMode == 2 || ownedMode == 4) {
                val disabled = execute("cmd game set --mode $ownedMode --downscale disable $GAME")
                val state = readGameConfigState()
                if (!disabled.ok || !(hasModeScale(state, ownedMode, "-1.00") || hasModeScale(state, ownedMode, "1.00"))) return false
                val desiredMode = if (performanceWanted && performanceAvailable) 2 else 1
                val changed = execute("cmd game mode $desiredMode $GAME")
                val current = execute("cmd game list-modes $GAME")
                if (!changed.ok || !current.ok || PrimePerformanceModeParser.currentMode(current.output) != desiredMode) return false
                prefs.edit().remove(KEY_MODERN_OWNED_MODE).apply()
            }
            return true
        }

        val mode = if (performanceWanted && performanceAvailable) 2 else 4
        val command = if (mode == 2) {
            "cmd game set --mode 2 --downscale ${scaleToken(scale)} $GAME"
        } else {
            "cmd game set --downscale ${scaleToken(scale)} $GAME"
        }
        execute(command)
        val verified = hasModeScale(readGameConfigState(), mode, scale)
        if (!verified) {
            AppState.appendLog("[PRIME-GPU  ] GameManager wrote no verified $scale scaling for mode $mode")
            return false
        }

        val modeResult = if (mode == 2) {
            execute("cmd game mode performance $GAME")
        } else {
            execute("cmd game mode custom $GAME")
        }
        if (!modeResult.ok) {
            AppState.appendLog("[PRIME-GPU  ] Scaling config verified, but mode activation was rejected")
            return false
        }
        prefs.edit().putInt(KEY_MODERN_OWNED_MODE, mode).apply()
        return true
    }

    private fun applyLegacy(scale: String): Boolean {
        if (scale == "1.00") {
            if (!prefs.getBoolean(KEY_LEGACY_OWNED, false)) return true
            val backup = prefs.getString(KEY_LEGACY_BACKUP, "none") ?: "none"
            val restore = if (backup in SCALE_CHANGE_IDS.keys) backup else "disable"
            execute("cmd game downscale ${if (restore == "disable") restore else scaleToken(restore)} $GAME")
            prefs.edit().remove(KEY_LEGACY_OWNED).remove(KEY_LEGACY_BACKUP).apply()
            return verifyLegacyScale(backup.takeIf { it in SCALE_CHANGE_IDS.keys })
        }

        if (!prefs.getBoolean(KEY_LEGACY_OWNED, false)) {
            val before = execute("dumpsys platform_compat")
            val previous = if (before.ok) activeLegacyScale(before.output) else null
            prefs.edit()
                .putBoolean(KEY_LEGACY_OWNED, true)
                .putString(KEY_LEGACY_BACKUP, previous ?: "none")
                .apply()
        }

        // Do not trust the old command's exit code; verify the actual overrides.
        execute("cmd game downscale ${scaleToken(scale)} $GAME")
        return verifyLegacyScale(scale)
    }

    private fun applyDeviceConfig(
        scale: String,
        performanceWanted: Boolean,
        performanceAvailable: Boolean,
    ): Boolean {
        if (!performanceAvailable) return false

        if (scale == "1.00") {
            if (prefs.getBoolean(KEY_OVERLAY_OWNED, false)) {
                val backup = prefs.getString(KEY_OVERLAY_BACKUP, NULL_OVERLAY) ?: NULL_OVERLAY
                if (backup == NULL_OVERLAY) {
                    execute("device_config delete game_overlay $GAME")
                } else {
                    execute("device_config put game_overlay $GAME ${shellQuote(backup)}")
                }
                prefs.edit().remove(KEY_OVERLAY_OWNED).remove(KEY_OVERLAY_BACKUP).apply()
            }
            if (performanceWanted) execute("cmd game mode performance $GAME")
            return true
        }

        val currentResult = execute("device_config get game_overlay $GAME")
        if (!currentResult.ok) return false
        val current = currentResult.output.trim()
        if (!prefs.getBoolean(KEY_OVERLAY_OWNED, false)) {
            prefs.edit()
                .putBoolean(KEY_OVERLAY_OWNED, true)
                .putString(KEY_OVERLAY_BACKUP, if (isNullOverlay(current)) NULL_OVERLAY else current)
                .apply()
        }

        val merged = mergeOverlay(current, 2, scaleToken(scale))
        val put = execute("device_config put game_overlay $GAME ${shellQuote(merged)}")
        if (!put.ok) return false
        val readback = execute("device_config get game_overlay $GAME")
        val ingested = execute("sleep 0.25; dumpsys game")
        val verified = readback.ok &&
            readback.output.contains("downscaleFactor=${scaleToken(scale)}") &&
            hasModeScale(ingested.output, 2, scale)
        if (!verified) {
            AppState.appendLog("[PRIME-GPU  ] game_overlay write was not accepted by GameManager")
            return false
        }
        val mode = execute("cmd game mode performance $GAME")
        return mode.ok
    }

    private fun cachedSurfaceStillReachable(backend: PrimeGraphicsBackend): Boolean {
        return when (backend) {
            PrimeGraphicsBackend.MODERN_CUSTOM -> execute("cmd game list-configs $GAME").ok
            PrimeGraphicsBackend.LEGACY_DIRECT -> execute("dumpsys platform_compat").ok
            PrimeGraphicsBackend.DEVICE_CONFIG_PERFORMANCE ->
                execute("device_config get game_overlay $GAME").ok
            else -> false
        }
    }

    private fun remember(result: ProbeResult): ProbeResult {
        prefs.edit()
            .putString(KEY_PROFILE, deviceProfile())
            .putString(KEY_BACKEND, result.backend.name)
            .apply()
        AppState.appendLog(
            "[PRIME-CAPS ] graphics=${result.backend.name} verified=${result.verified} · ${result.detail}"
        )
        return result
    }

    private fun readGameConfigState(): String {
        val configs = execute("cmd game list-configs $GAME")
        val dump = execute("dumpsys game")
        return buildString {
            if (configs.ok) append(configs.output)
            append('\n')
            if (dump.ok) append(dump.output)
        }
    }

    private fun hasModeScalingField(text: String, mode: Int): Boolean {
        val p1 = Regex(
            "(?is)Game\\s*Mode\\s*[:=]\\s*$mode\\b.{0,320}?(?:Scaling|downscaleFactor)\\s*[:=]\\s*[0-9.]+"
        )
        val p2 = Regex(
            "(?is)\\bmode\\s*=\\s*$mode\\b[^:\\n]{0,320}?\\bdownscaleFactor\\s*=\\s*[0-9.]+"
        )
        return p1.containsMatchIn(text) || p2.containsMatchIn(text)
    }

    private fun hasModeScale(text: String, mode: Int, scale: String): Boolean {
        val token = Regex.escape(scaleToken(scale))
        val p1 = Regex(
            "(?is)Game\\s*Mode\\s*[:=]\\s*$mode\\b.{0,320}?(?:Scaling|downscaleFactor)\\s*[:=]\\s*$token(?:\\D|$)"
        )
        val p2 = Regex(
            "(?is)\\bmode\\s*=\\s*$mode\\b[^:\\n]{0,320}?\\bdownscaleFactor\\s*=\\s*$token(?:\\D|$)"
        )
        return p1.containsMatchIn(text) || p2.containsMatchIn(text)
    }

    private fun scaleToken(scale: String): String =
        scale.toFloatOrNull()?.toString() ?: scale

    private fun compatMentionsGameOverride(text: String, id: Long): Boolean =
        text.lineSequence().any { line ->
            line.contains(id.toString()) && line.contains(GAME)
        }

    private fun compatOverrideEnabled(text: String, id: Long): Boolean =
        text.lineSequence().any { line ->
            line.contains(id.toString()) &&
                (line.contains("$GAME=true") || line.contains("$GAME = true"))
        }

    private fun activeLegacyScale(text: String): String? {
        if (!compatOverrideEnabled(text, DOWNSCALED_ID)) return null
        return SCALE_CHANGE_IDS.entries.firstOrNull { compatOverrideEnabled(text, it.value) }?.key
    }

    private fun verifyLegacyScale(scale: String?): Boolean {
        val state = execute("dumpsys platform_compat")
        if (!state.ok) return false
        if (scale == null) return !compatOverrideEnabled(state.output, DOWNSCALED_ID)
        val id = SCALE_CHANGE_IDS[scale] ?: return false
        return compatOverrideEnabled(state.output, DOWNSCALED_ID) &&
            compatOverrideEnabled(state.output, id)
    }

    private fun restoreOverlayValue(original: String) {
        if (isNullOverlay(original)) {
            execute("device_config delete game_overlay $GAME")
        } else {
            execute("device_config put game_overlay $GAME ${shellQuote(original)}")
        }
    }

    private fun isNullOverlay(value: String): Boolean =
        value.isBlank() || value.equals("null", ignoreCase = true)

    private fun mergeOverlay(raw: String, mode: Int, scale: String): String {
        val segments = if (isNullOverlay(raw)) mutableListOf() else
            raw.split(':').filter { it.isNotBlank() }.toMutableList()
        val modeRegex = Regex("(^|,)\\s*mode\\s*=\\s*$mode(,|$)", RegexOption.IGNORE_CASE)
        val index = segments.indexOfFirst { modeRegex.containsMatchIn(it) }
        val fields = if (index >= 0) {
            segments[index].split(',').filter { it.isNotBlank() }.toMutableList()
        } else {
            mutableListOf("mode=$mode")
        }
        val downscaleIndex = fields.indexOfFirst {
            it.substringBefore('=').trim().equals("downscaleFactor", ignoreCase = true)
        }
        val value = "downscaleFactor=$scale"
        if (downscaleIndex >= 0) fields[downscaleIndex] = value else fields.add(value)
        val mergedMode = fields.joinToString(",")
        if (index >= 0) segments[index] = mergedMode else segments.add(mergedMode)
        return segments.joinToString(":")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
