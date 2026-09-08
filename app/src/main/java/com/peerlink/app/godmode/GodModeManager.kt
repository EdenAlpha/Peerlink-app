package com.peerlink.app.godmode

import android.content.Context
import android.content.Intent
import com.peerlink.app.godmode.shizuku.PrimeShizukuBootstrapEngine
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.peerlink.app.core.AppState
import com.peerlink.app.core.PrimeGameplayTracker
import com.peerlink.app.service.LanLinkForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GodModeManager — Prime Mode controller.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ARCHITECTURE (Shizuku pattern)
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ADB is used in exactly TWO scenarios, and never again:
 *
 *   1. FIRST-TIME SETUP (submitPairingPin):
 *      pair → connect → pm grant WRITE_SECURE_SETTINGS → adb_wifi_enabled=1 →
 *      app_process PrimeServer & → wait isAlive → runGodModeSequence
 *
 *   2. POST-REBOOT (launchPrimeServer):
 *      [wlan0 must be up — LinkUp's LocalOnlyHotspot provides this] →
 *      find adbd port (NSD → scan) → connect → app_process PrimeServer & →
 *      wait isAlive → runGodModeSequence
 *
 * NORMAL SESSION (PrimeServer already alive):
 *   PrimeClient.isAlive() → runGodModeSequence() — zero ADB, zero WiFi needed.
 *   All commands go through PrimeClient → TCP loopback 127.0.0.1:13373.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * COMPLETE COMMAND INVENTORY
 * ══════════════════════════════════════════════════════════════════════════
 *
 * BOOTSTRAP (one-time, via ADB):
 *   0a. pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
 *   0b. Settings.Global adb_wifi_enabled=1   (persists across reboots)
 *
 * ALWAYS-ON:
 *   3.  dumpsys deviceidle whitelist +<pkg>           — PeerLink doze immunity
 *   4.  settings put global airplane_mode_radios cell,bluetooth,nfc,wimax
 *
 * USER-TOGGLEABLE:
 *   9.  dumpsys deviceidle whitelist +jp.konami.pesam
 *   10. am set-standby-bucket jp.konami.pesam active
 *
 * The VPN owns Android's supported WIFI_MODE_FULL_LOW_LATENCY lock for the
 * exact gameplay lifetime. Prime deliberately does not alter deprecated
 * wifi_sleep_policy, hidden Wi-Fi power settings, captive-portal validation,
 * or global Wi-Fi band policy.
 * ══════════════════════════════════════════════════════════════════════════
 */
object GodModeManager {

    private const val PREFS         = "godmode_prefs"
    private const val EFOOTBALL_PKG = "jp.konami.pesam"

    // ─── Preference keys ──────────────────────────────────────────────────
    private const val KEY_KEEP_GAME        = "keep_game_in_ram"
    private const val KEY_RAM_CLEAR        = "apex_ram_clear"
    private const val KEY_AIRPLANE_SH      = "apex_airplane_shield"
    private const val KEY_CAPTIVE          = "apex_captive_portal"
    private const val KEY_APEX_MASTER      = "apex_master_enabled"
    private const val KEY_5GHZ_OVERRIDE    = "apex_5ghz_override"
    private const val KEY_AUTO_CONNECT     = "apex_auto_connect"
    private const val KEY_INSTANT_VAULT    = "prime_instant_vault"
    private const val KEY_MEMORY_AGGRESSION= "prime_memory_aggression"
    private const val KEY_GRAPHICS_MODE    = "prime_graphics_mode"
    private const val KEY_RENDER_SCALE     = "prime_render_scale"
    private const val KEY_TARGET_FPS       = "prime_target_fps"
    private const val KEY_ART_MODE         = "prime_art_mode"
    private const val KEY_CPU_GAME_MODE    = "prime_cpu_game_mode"
    private const val KEY_AUTO_TUNER       = "prime_auto_tuner"
    private const val KEY_BOOTSTRAP_DONE   = "bootstrap_grant_done"
    private const val KEY_BOOTSTRAP_STATUS = "bootstrap_last_status"
    private const val KEY_PAIRED_TRUST     = "paired_trust_confirmed"
    private const val KEY_RESTORE_PENDING  = "prime_restore_pending_v1"
    private const val KEY_RESTORE_WIFI_SUSPEND = "restore_wifi_suspend"
    private const val KEY_RESTORE_WIFI_SLEEP   = "restore_wifi_sleep"
    private const val KEY_RESTORE_AIRPLANE     = "restore_airplane_radios"
    private const val KEY_RESTORE_CAPTIVE      = "restore_captive_portal"
    private const val KEY_RESTORE_GAME_BUCKET  = "restore_game_bucket"
    private const val KEY_RESTORE_WIFI_BANDS   = "restore_wifi_bands"
    private const val KEY_RESTORE_APP_WHITELISTED  = "restore_app_whitelisted"
    private const val KEY_RESTORE_GAME_WHITELISTED = "restore_game_whitelisted"
    private const val KEY_RESTORE_APPLIED_AIRPLANE = "restore_applied_airplane"
    private const val KEY_RESTORE_APPLIED_CAPTIVE = "restore_applied_captive"
    private const val KEY_RESTORE_APPLIED_GAME = "restore_applied_game"
    private const val KEY_RESTORE_APPLIED_BANDS = "restore_applied_bands"
    private const val RESTORE_DELETE = "__DELETE__"

    private const val CONNECT_NSD_WAIT_MS = 20_000L
    private const val SERVER_WAIT_MS      = 10_000L

    // ─── Public state ─────────────────────────────────────────────────────

    enum class State {
        NOT_PAIRED, DISCOVERING, PAIRING,
        PAIRED_IDLE, BOOTSTRAPPING, CONNECTING,
        PRIME_MODE_ACTIVE, ERROR
    }

    data class SetupSnapshot(
        val pairedTrusted: Boolean = false,
        val bootstrapped: Boolean = false,
        val primeServerAlive: Boolean = false,
        val restorePending: Boolean = false,
        val bootstrapStatus: String = "Not set up"
    )

    private val _state               = MutableStateFlow(State.NOT_PAIRED)
    private val _status              = MutableStateFlow("Ready")
    private val _wirelessDebugOn     = MutableStateFlow(false)
    private val _primeWifiRetryState = MutableStateFlow<Boolean?>(null)
    private val _setupSnapshot       = MutableStateFlow(SetupSnapshot())
    private val _capabilities        = MutableStateFlow(PrimeCapabilities.coarse())
    private val _memorySnapshot      = MutableStateFlow(PrimeMemoryDirector.Snapshot())
    private val _frameStats          = MutableStateFlow(PrimeFrameStats())
    private val _primeLinkState      = MutableStateFlow(PrimeLinkState.IDLE)
    private val _actionFeedback      = MutableStateFlow(PrimeActionFeedback())

    val state:               StateFlow<State>    = _state.asStateFlow()
    val status:              StateFlow<String>   = _status.asStateFlow()
    val wirelessDebugOn:     StateFlow<Boolean>  = _wirelessDebugOn.asStateFlow()
    /** null=hidden  false=orange(no wifi)  true=green(tap to retry) */
    val primeWifiRetryState: StateFlow<Boolean?> = _primeWifiRetryState.asStateFlow()
    val setupSnapshot:       StateFlow<SetupSnapshot> = _setupSnapshot.asStateFlow()
    val capabilities:        StateFlow<PrimeCapabilities> = _capabilities.asStateFlow()
    val memorySnapshot:      StateFlow<PrimeMemoryDirector.Snapshot> = _memorySnapshot.asStateFlow()
    val frameStats:          StateFlow<PrimeFrameStats> = _frameStats.asStateFlow()
    val primeLinkState:      StateFlow<PrimeLinkState> = _primeLinkState.asStateFlow()
    val actionFeedback:      StateFlow<PrimeActionFeedback> = _actionFeedback.asStateFlow()

    // ─── Internal ─────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var appContext: Context

    private var nsdWatcher: AdbNsdWatcher? = null

    @Volatile private var pairingHost: String? = null
    @Volatile private var pairingPort: Int     = 0
    @Volatile private var connectHost: String? = null
    @Volatile private var connectPort: Int     = 0   // set by NSD watcher, consumed by findAdbPort()

    @Volatile private var keepGameWasActive = false

    // Prevents double-execution when VPN restarts or Prime Mode is toggled rapidly
    private val activeCommands = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // FIX-A/C: True while runGodModeSequence() is executing.
    // Prevents stopWatching() from resetting state to PAIRED_IDLE mid-sequence,
    // and prevents redundant activation calls from slipping through the guard.
    @Volatile private var sequenceRunning = false
    private val activationRequested = AtomicBoolean(false)
    private val deactivationRequested = AtomicBoolean(false)
    private val restoreRecoveryRequested = AtomicBoolean(false)
    private val pairingRequested = AtomicBoolean(false)
    private val commandGate = Semaphore(1)
    @Volatile private var activationJob: Job? = null

    private var wifiMonitorJob: Job? = null

    private var memoryDirector: PrimeMemoryDirector? = null
    private var graphicsController: PrimeGraphicsController? = null
    private var autoTuner: PrimeAutoTuner? = null
    private var performanceCollectorsStarted = false
    private val guardianPulseRunning = AtomicBoolean(false)
    @Volatile private var lastGuardianRecoveryAttemptMs = 0L
    private var guardianJob: Job? = null
    private const val GUARDIAN_RECOVERY_COOLDOWN_MS = 6_000L
    private const val GUARDIAN_PULSE_MS = 2_000L

    private val primeExitMarker = "__PEERLINK_EXIT__="


    // ─── Prime performance profile ────────────────────────────────────────

    fun isInstantVaultEnabled(): Boolean = prefs().getBoolean(KEY_INSTANT_VAULT, true)
    fun setInstantVaultEnabled(v: Boolean) { prefs().edit().putBoolean(KEY_INSTANT_VAULT, v).apply() }

    fun getMemoryAggression(): PrimeMemoryAggression = runCatching {
        PrimeMemoryAggression.valueOf(prefs().getString(KEY_MEMORY_AGGRESSION, PrimeMemoryAggression.AGGRESSIVE.name)!!)
    }.getOrDefault(PrimeMemoryAggression.AGGRESSIVE)
    fun setMemoryAggression(v: PrimeMemoryAggression) { prefs().edit().putString(KEY_MEMORY_AGGRESSION, v.name).apply() }

    fun getGraphicsMode(): PrimeGraphicsMode = runCatching {
        PrimeGraphicsMode.valueOf(prefs().getString(KEY_GRAPHICS_MODE, PrimeGraphicsMode.AUTO.name)!!)
    }.getOrDefault(PrimeGraphicsMode.AUTO)
    fun setGraphicsMode(v: PrimeGraphicsMode) { prefs().edit().putString(KEY_GRAPHICS_MODE, v.name).apply() }

    fun getRenderScale(): String = prefs().getString(KEY_RENDER_SCALE, "1.00") ?: "1.00"
    fun setRenderScale(scale: String) {
        val allowed = setOf("1.00", "0.90", "0.85", "0.80", "0.75", "0.70")
        if (scale !in allowed) return
        prefs().edit().putString(KEY_RENDER_SCALE, scale).apply()
        publishAction("graphics_scale", PrimeActionPhase.WORKING, "Applying ${scaleLabel(scale)} render scale…")
        scope.launch {
            if (!AppState.isGodModeActive.get()) {
                publishAction(
                    "graphics_scale",
                    PrimeActionPhase.SUCCESS,
                    "${scaleLabel(scale)} saved; activate Prime before launching eFootball"
                )
                AppState.appendLog("[PRIME-GPU  ] Render scale $scale saved for next Prime activation")
                return@launch
            }

            val ready = ensurePrimeForInteractiveAction("graphics_scale")
            if (!ready) {
                publishAction(
                    "graphics_scale",
                    PrimeActionPhase.ERROR,
                    "${scaleLabel(scale)} saved, but the Prime engine could not reconnect"
                )
                return@launch
            }
            if (!_capabilities.value.gameDownscale) {
                publishAction("graphics_scale", PrimeActionPhase.ERROR, "No verified graphics backend is available")
                return@launch
            }

            val ok = autoTuner?.applyScale(scale) == true
            if (ok) {
                publishAction("graphics_scale", PrimeActionPhase.SUCCESS, "${scaleLabel(scale)} render scale verified")
            } else {
                publishAction("graphics_scale", PrimeActionPhase.ERROR, "Android did not verify that render-scale change")
            }
        }
    }

    private fun scaleLabel(scale: String): String =
        if (scale == "1.00") "Native" else "${(scale.toFloat() * 100).toInt()}%"

    private fun publishAction(action: String, phase: PrimeActionPhase, message: String) {
        _actionFeedback.value = PrimeActionFeedback(action, phase, message, System.currentTimeMillis())
    }

    fun clearActionFeedback() { _actionFeedback.value = PrimeActionFeedback() }

    fun getTargetFps(): Int = prefs().getInt(KEY_TARGET_FPS, 60).let { if (it == 30) 30 else 60 }
    fun setTargetFps(v: Int) { prefs().edit().putInt(KEY_TARGET_FPS, if (v <= 30) 30 else 60).apply() }

    fun getArtMode(): PrimeArtMode = runCatching {
        PrimeArtMode.valueOf(prefs().getString(KEY_ART_MODE, PrimeArtMode.PROFILE.name)!!)
    }.getOrDefault(PrimeArtMode.PROFILE)
    fun setArtMode(v: PrimeArtMode) { prefs().edit().putString(KEY_ART_MODE, v.name).apply() }

    fun isCpuGameModeEnabled(): Boolean = prefs().getBoolean(KEY_CPU_GAME_MODE, true)
    fun setCpuGameModeEnabled(v: Boolean) { prefs().edit().putBoolean(KEY_CPU_GAME_MODE, v).apply() }

    fun isAutoTunerEnabled(): Boolean = prefs().getBoolean(KEY_AUTO_TUNER, true)
    fun setAutoTunerEnabled(v: Boolean) { prefs().edit().putBoolean(KEY_AUTO_TUNER, v).apply() }

    fun runArtOptimizationNow() {
        val mode = getArtMode()
        if (mode == PrimeArtMode.DEFAULT) return
        publishAction("art_optimize", PrimeActionPhase.WORKING, "Preparing Prime engine…")
        scope.launch {
            val ready = ensurePrimeForInteractiveAction("art_optimize")
            if (!ready) {
                AppState.appendLog("[PRIME-CPU  ] ART optimize deferred — Prime Server recovery failed")
                publishAction("art_optimize", PrimeActionPhase.ERROR, "Prime engine could not reconnect")
                return@launch
            }

            if (PrimeGameplayTracker.isMatchProtected() || PrimeClient.isPackageForeground(EFOOTBALL_PKG) != false) {
                publishAction("art_optimize", PrimeActionPhase.ERROR, "Close eFootball before optimizing its runtime")
                return@launch
            }
            val filter = if (mode == PrimeArtMode.FULL) "speed" else "speed-profile"
            publishAction("art_optimize", PrimeActionPhase.WORKING, "Optimizing eFootball runtime…")
            setState(State.CONNECTING, "Optimizing eFootball runtime…")
            val r = executePrimeShellChecked("cmd package compile -f -m $filter $EFOOTBALL_PKG", timeoutMs = 120_000)
            if (r.ok) {
                AppState.appendLog("[PRIME-CPU  ] ART $filter optimization complete")
                publishAction("art_optimize", PrimeActionPhase.SUCCESS, "Runtime optimization completed")
            } else {
                AppState.appendLog("[PRIME-CPU  ] ART optimization failed: ${r.output.take(160)}")
                publishAction("art_optimize", PrimeActionPhase.ERROR, "Runtime optimization was rejected on this device")
            }
            if (AppState.isGodModeActive.get()) setState(State.PRIME_MODE_ACTIVE, "Prime Mode active") else setState(State.PAIRED_IDLE, "Prime Mode ready")
        }
    }

    private suspend fun ensurePrimeForInteractiveAction(actionId: String): Boolean {
        if (PrimeClient.isAlive(timeoutMs = 800)) return true
        if (!AppState.isGodModeActive.get()) return false
        publishAction(actionId, PrimeActionPhase.WORKING, "Reconnecting Prime engine…")
        _primeLinkState.value = PrimeLinkState.RECOVERING
        val recovered = commandGate.withPermit {
            if (PrimeClient.isAlive(timeoutMs = 500)) true
            else ensurePrimeServerAlive(needBootstrap = !isBootstrapped())
        }
        if (recovered) {
            AppState.primeServerAlive = true
            _primeLinkState.value = PrimeLinkState.CONNECTED
            updateSetupSnapshot(primeServerAlive = true)
            startGuardianLoop()
            ensureGuardianService()
            AppState.appendLog("[PRIME-GUARD] Interactive action recovered PrimeServer")
        }
        return recovered
    }

    private fun ensurePerformanceEngines() {
        if (memoryDirector == null) {
            memoryDirector = PrimeMemoryDirector(
                context = appContext,
                execute = { executePrimeShellChecked(it) },
                capabilities = { _capabilities.value },
                instantVaultEnabled = { isInstantVaultEnabled() },
                aggression = { getMemoryAggression() },
            )
        }
        if (graphicsController == null) {
            graphicsController = PrimeGraphicsController(
                context = appContext,
                execute = { executePrimeShellChecked(it, timeoutMs = 30_000) },
            )
        }
        if (autoTuner == null) {
            autoTuner = PrimeAutoTuner(
                execute = { executePrimeShellChecked(it, timeoutMs = 30_000) },
                capabilities = { _capabilities.value },
                graphicsMode = { if (isAutoTunerEnabled()) getGraphicsMode() else PrimeGraphicsMode.MANUAL },
                currentScale = { getRenderScale() },
                targetFps = { getTargetFps() },
                performanceGameModeEnabled = { isCpuGameModeEnabled() },
                gameForeground = { _memorySnapshot.value.gameForeground },
                applyScaleOverride = { scale ->
                    graphicsController?.applyScale(
                        scale = scale,
                        backend = _capabilities.value.graphicsBackend,
                        performanceWanted = isCpuGameModeEnabled(),
                        performanceAvailable = _capabilities.value.gamePerformanceMode,
                    ) == true
                },
                persistScale = { prefs().edit().putString(KEY_RENDER_SCALE, it).apply() },
            )
        }
        if (!performanceCollectorsStarted) {
            performanceCollectorsStarted = true
            scope.launch { memoryDirector!!.snapshot.collect { _memorySnapshot.value = it } }
            scope.launch { autoTuner!!.frameStats.collect { _frameStats.value = it } }
        }
    }

    private fun probePerformanceCapabilities(forceGraphics: Boolean = false) {
        ensurePerformanceEngines()

        // Help text is diagnostic only. OEMs frequently trim or rewrite it.
        // Real support is established by executable readback in the graphics
        // compatibility controller.
        val amHelp = executePrimeShellChecked("am help", timeoutMs = 2_000)
        val gameHelp = executePrimeShellChecked("cmd game help", timeoutMs = 2_000)
        val packageHelp = executePrimeShellChecked("cmd package help", timeoutMs = 2_000)
        val powerHelp = executePrimeShellChecked("cmd power help", timeoutMs = 2_000)
        val modes = executePrimeShellChecked("cmd game list-modes $EFOOTBALL_PKG", timeoutMs = 2_000)
        val performanceAvailable = modes.ok &&
            PrimePerformanceModeParser.hasPerformanceMode(modes.output)
        val graphics = graphicsController?.probe(
            performanceAvailable = performanceAvailable,
            force = forceGraphics,
        ) ?: PrimeGraphicsController.ProbeResult(
            PrimeGraphicsBackend.PENDING,
            false,
            "Graphics verifier not initialized",
        )
        val sf = executePrimeShellChecked("dumpsys SurfaceFlinger --timestats -dump", timeoutMs = 2_000)

        _capabilities.value = PrimeCapabilities(
            sdk = Build.VERSION.SDK_INT,
            uidState = amHelp.ok && amHelp.output.contains("get-uid-state"),
            processFreeze = amHelp.ok && amHelp.output.contains("freeze ["),
            processCompact = amHelp.ok && amHelp.output.contains("compact"),
            gameManager = gameHelp.ok || modes.ok ||
                graphics.backend !in setOf(PrimeGraphicsBackend.NONE, PrimeGraphicsBackend.PENDING),
            gameDownscale = graphics.verified,
            gamePerformanceMode = performanceAvailable,
            artCompile = packageHelp.ok && packageHelp.output.contains("compile", ignoreCase = true),
            surfaceFlingerTimeStats = sf.ok &&
                (sf.output.contains("TimeStats") || sf.output.contains("timestats", ignoreCase = true)),
            fixedPerformanceMode = powerHelp.ok &&
                powerHelp.output.contains("set-fixed-performance-mode-enabled"),
            graphicsBackend = graphics.backend,
            graphicsProbeDetail = graphics.detail,
        )
        AppState.appendLog(
            "[PRIME-CAPS ] sdk=${Build.VERSION.SDK_INT} freeze=${_capabilities.value.processFreeze} " +
                "compact=${_capabilities.value.processCompact} graphics=${graphics.backend} " +
                "verified=${graphics.verified} gamePerf=$performanceAvailable ART=${_capabilities.value.artCompile}"
        )
    }

    fun recheckPerformanceCapabilities() {
        if (!PrimeClient.isAlive(timeoutMs = 300)) {
            AppState.appendLog("[PRIME-CAPS ] Recheck needs an active Prime Server")
            publishAction("capability_recheck", PrimeActionPhase.ERROR, "Prime engine is offline")
            return
        }
        if (PrimeGameplayTracker.isMatchProtected()) {
            AppState.appendLog("[PRIME-CAPS ] Recheck deferred — live P2P match protected")
            publishAction("capability_recheck", PrimeActionPhase.ERROR, "Compatibility checks are locked during a live match")
            return
        }
        publishAction("capability_recheck", PrimeActionPhase.WORKING, "Testing Android graphics backends…")
        scope.launch {
            commandGate.withPermit {
            if (PrimeGameplayTracker.isMatchProtected()) {
                publishAction("capability_recheck", PrimeActionPhase.ERROR, "Wait until the match ends")
                return@withPermit
            }
            probePerformanceCapabilities(forceGraphics = true)
            if (_capabilities.value.gameDownscale) {
                publishAction(
                    "capability_recheck",
                    PrimeActionPhase.SUCCESS,
                    "Graphics backend verified: ${_capabilities.value.graphicsBackend.name.replace('_', ' ')}"
                )
            } else {
                publishAction(
                    "capability_recheck",
                    PrimeActionPhase.ERROR,
                    "No graphics backend passed Android readback verification"
                )
            }
            }
        }
    }

    private fun startGuardianLoop() {
        if (guardianJob?.isActive == true) return
        guardianJob = scope.launch {
            AppState.appendLog("[PRIME-GUARD] Runtime guardian started")
            while (isActive) {
                val needed = AppState.isGodModeActive.get() || prefs().getBoolean(KEY_RESTORE_PENDING, false)
                if (!needed) break
                guardianPulse()
                delay(GUARDIAN_PULSE_MS)
            }
            AppState.appendLog("[PRIME-GUARD] Runtime guardian stopped")
        }
    }

    private fun stopGuardianLoop() {
        guardianJob?.cancel()
        guardianJob = null
    }

    private fun ensureGuardianService() {
        runCatching { LanLinkForegroundService.start(appContext) }
            .onSuccess { AppState.appendLog("[PRIME-GUARD] Foreground guardian requested") }
            .onFailure { AppState.appendLog("[PRIME-GUARD] Foreground guardian start failed: ${it.message}") }
    }

    /**
     * Foreground-service heartbeat for the detached privileged engine.
     * mDNS disappearing is not treated as server death; loopback health is the
     * authority. Recovery is throttled so OEM failures cannot create a spin loop.
     */
    fun guardianPulse() {
        if (!::appContext.isInitialized) return
        val active = AppState.isGodModeActive.get()
        val restorePending = prefs().getBoolean(KEY_RESTORE_PENDING, false)
        if (!active && !restorePending) {
            _primeLinkState.value = PrimeLinkState.IDLE
            return
        }
        if (!guardianPulseRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (PrimeClient.isAlive(timeoutMs = 650)) {
                    AppState.primeServerAlive = true
                    _primeLinkState.value = PrimeLinkState.CONNECTED
                    updateSetupSnapshot(primeServerAlive = true)
                    return@launch
                }

                AppState.primeServerAlive = false
                updateSetupSnapshot(primeServerAlive = false)
                _primeLinkState.value = PrimeLinkState.DEGRADED
                val now = System.currentTimeMillis()
                if (now - lastGuardianRecoveryAttemptMs < GUARDIAN_RECOVERY_COOLDOWN_MS) return@launch
                lastGuardianRecoveryAttemptMs = now
                _primeLinkState.value = PrimeLinkState.RECOVERING
                AppState.appendLog("[PRIME-GUARD] PrimeServer heartbeat lost — automatic recovery starting")

                commandGate.withPermit {
                    if (!PrimeClient.isAlive(timeoutMs = 350)) {
                        val recovered = ensurePrimeServerAlive(needBootstrap = false)
                        if (!recovered) {
                            _primeLinkState.value = PrimeLinkState.DEGRADED
                            if (active) setState(State.PRIME_MODE_ACTIVE, "Prime active · engine reconnect pending")
                            return@withPermit
                        }
                    }

                    _primeLinkState.value = PrimeLinkState.CONNECTED
                    AppState.primeServerAlive = true
                    updateSetupSnapshot(primeServerAlive = true)
                    if (AppState.isGodModeActive.get()) {
                        ensurePerformanceEngines()
                        memoryDirector?.start()
                        autoTuner?.start()
                        val savedScale = getRenderScale()
                        if (_capabilities.value.gameDownscale && savedScale != "1.00") {
                            val applied = autoTuner?.applyScale(savedScale) == true
                            AppState.appendLog("[PRIME-GUARD] Re-applied saved render scale $savedScale verified=$applied")
                        }
                        setState(State.PRIME_MODE_ACTIVE, "Prime Mode active · engine recovered")
                        AppState.appendLog("[PRIME-GUARD] PrimeServer recovered without user action")
                    } else if (prefs().getBoolean(KEY_RESTORE_PENDING, false)) {
                        if (restoreExactSettingsNow()) {
                            setState(State.PAIRED_IDLE, "Recovered engine and restored interrupted Prime settings")
                            LanLinkForegroundService.stop(appContext)
                        }
                    }
                }
            } catch (t: Throwable) {
                _primeLinkState.value = PrimeLinkState.DEGRADED
                AppState.appendLog("[PRIME-GUARD] Recovery pulse failed: ${t.message}")
            } finally {
                guardianPulseRunning.set(false)
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (::appContext.isInitialized) { refreshSetupState(); return }
        appContext = context.applicationContext
        PrimeClient.init(appContext)
        ensurePerformanceEngines()
        val bootstrapped = isBootstrapped()
        val pairedTrusted = isPairingTrusted()
        updateSetupSnapshot()
        _state.value = when {
            bootstrapped || pairedTrusted -> State.PAIRED_IDLE
            else -> State.NOT_PAIRED
        }
        _status.value = when {
            bootstrapped -> "Prime Mode ready"
            pairedTrusted -> "Paired — bootstrap pending"
            else -> "Not paired"
        }
        AppState.appendLog("[PRIME-MODE ] init (bootstrapped=$bootstrapped pairedTrusted=$pairedTrusted keepGame=${getKeepGameInRam()})")
    }

    fun refreshSetupState(checkServer: Boolean = true) {
        if (!::appContext.isInitialized) return
        updateSetupSnapshot()
        syncIdleStateFromSetup()

        if (!checkServer) return
        scope.launch {
            val alive = PrimeClient.isAlive(timeoutMs = 350)
            AppState.primeServerAlive = alive
            _primeLinkState.value = when {
                alive -> PrimeLinkState.CONNECTED
                AppState.isGodModeActive.get() -> PrimeLinkState.DEGRADED
                else -> PrimeLinkState.IDLE
            }
            updateSetupSnapshot(primeServerAlive = alive)
            syncIdleStateFromSetup()
            if (alive && prefs().getBoolean(KEY_RESTORE_PENDING, false) &&
                !AppState.isRunning.get() &&
                _state.value != State.PRIME_MODE_ACTIVE &&
                !isSetupBusyState() &&
                restoreRecoveryRequested.compareAndSet(false, true)) {
                try {
                    commandGate.withPermit {
                        AppState.appendLog("[PRIME-MODE ] Recovering settings from an interrupted Prime session")
                        if (restoreExactSettingsNow()) {
                            setState(State.PAIRED_IDLE, "Recovered interrupted Prime session settings")
                        } else {
                            setState(State.ERROR, "Some interrupted Prime settings still need restore")
                        }
                    }
                } finally {
                    restoreRecoveryRequested.set(false)
                }
            }
        }
    }

    /**
     * Start NSD watcher. Idempotent.
     * Caches connectPort whenever adbd advertises _adb-tls-connect._tcp.
     * Does NOT auto-connect via ADB — port is used by findAdbPort() only.
     */
    fun startWatching() {
        if (nsdWatcher != null) return
        AppState.appendLog("[PRIME-MODE ] NSD watcher starting")
        nsdWatcher = AdbNsdWatcher(appContext).apply {
            onPairingPortFound = { host, port ->
                pairingHost = host; pairingPort = port
                AppState.appendLog("[PRIME-MODE ] Pairing port: $host:$port")
                if (_state.value == State.DISCOVERING)
                    setState(State.DISCOVERING, "Enter the 6-digit code shown in your notification")
            }
            onPairingPortLost = {
                pairingHost = null; pairingPort = 0
                AppState.appendLog("[PRIME-MODE ] Pairing service lost")
            }
            onConnectPortFound = { host, port ->
                connectHost = host; connectPort = port
                _wirelessDebugOn.value = true
                AppState.appendLog("[PRIME-MODE ] Connect port cached: $host:$port")
            }
            onConnectPortLost = {
                connectHost = null; connectPort = 0
                _wirelessDebugOn.value = false
                // mDNS loss is not proof that the detached PrimeServer died.
                // The foreground guardian independently health-checks loopback.
                AppState.appendLog("[PRIME-MODE ] Wireless-debugging advertisement lost; PrimeServer health checked separately")
            }
            start()
        }
    }

    fun stopWatching() {
        nsdWatcher?.stop(); nsdWatcher = null
        stopWifiMonitor()
        _primeWifiRetryState.value = null
        // FIX-A: Only reset to PAIRED_IDLE if we are genuinely waiting for NSD
        // (the bootstrap/connect phase). If sequenceRunning=true, the CONNECTING
        // state was set by runGodModeSequence() mid-execution. Resetting it here
        // would tell the UI "tap ACTIVATE again" even though all commands are
        // running fine in the background.
        if (_state.value == State.CONNECTING && !sequenceRunning && !activationRequested.get())
            setState(State.PAIRED_IDLE, "Monitoring stopped")
    }

    // ─── Pref accessors ───────────────────────────────────────────────────

    fun isApexMasterEnabled()     = prefs().getBoolean(KEY_APEX_MASTER,    false)
    // Kept as a compatibility accessor for old call sites/preferences. The
    // former "RAM clear" ran am kill-all + pm trim-caches, which neither pins
    // eFootball in RAM nor improves a live game and can create restart bursts.
    fun isRamClearEnabled()       = false
    fun isAirplaneShieldEnabled() = prefs().getBoolean(KEY_AIRPLANE_SH,     true)
    // Compatibility accessors: these legacy toggles changed global network
    // policy without improving the game tunnel, so new activations force them off.
    fun isCaptivePortalEnabled()  = false
    fun getKeepGameInRam()        = prefs().getBoolean(KEY_KEEP_GAME,       false)
    fun isAutoConnectEnabled()    = prefs().getBoolean(KEY_AUTO_CONNECT,    true)
    fun is5GhzOverrideEnabled()   = false

    fun setApexMasterEnabled(v: Boolean) {
        prefs().edit().putBoolean(KEY_APEX_MASTER, v).apply()
        AppState.appendLog("[PRIME-MODE ] Master → $v")
        if (!v && (_state.value == State.PRIME_MODE_ACTIVE || activationRequested.get() || sequenceRunning)) {
            deactivateGodMode()
        }
    }
    fun setRamClearEnabled(v: Boolean)       { prefs().edit().putBoolean(KEY_RAM_CLEAR, false).apply() }
    fun setAirplaneShieldEnabled(v: Boolean) { prefs().edit().putBoolean(KEY_AIRPLANE_SH,   v).apply() }
    fun setCaptivePortalEnabled(@Suppress("UNUSED_PARAMETER") v: Boolean)  { prefs().edit().putBoolean(KEY_CAPTIVE, false).apply() }
    fun setKeepGameInRam(v: Boolean)         { prefs().edit().putBoolean(KEY_KEEP_GAME,     v).apply(); AppState.appendLog("[PRIME-MODE ] keepGameInRam → $v") }
    fun setAutoConnectEnabled(v: Boolean)    { prefs().edit().putBoolean(KEY_AUTO_CONNECT,  v).apply(); AppState.appendLog("[PRIME-MODE ] autoConnect → $v") }
    fun set5GhzOverrideEnabled(@Suppress("UNUSED_PARAMETER") v: Boolean)   { prefs().edit().putBoolean(KEY_5GHZ_OVERRIDE, false).apply() }

    // ─── Bootstrap state ──────────────────────────────────────────────────

    fun isBootstrapped(): Boolean {
        return appContext.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                PackageManager.PERMISSION_GRANTED
    }

    fun isPairingTrusted(): Boolean {
        return prefs().getBoolean(KEY_PAIRED_TRUST, false) || AdbKeyManager.isPaired(appContext)
    }

    fun selfEnableAdbd() = isBootstrapped()

    fun getBootstrapStatus(): String =
        prefs().getString(KEY_BOOTSTRAP_STATUS, "Not set up") ?: "Not set up"

    private fun markPairingTrusted() {
        prefs().edit().putBoolean(KEY_PAIRED_TRUST, true).apply()
        AdbKeyManager.markPaired(appContext, true)
        updateSetupSnapshot()
        AppState.appendLog("[PRIME-MODE ] Pairing trust confirmed ✅")
    }

    private fun markBootstrapped() {
        prefs().edit().putBoolean(KEY_BOOTSTRAP_DONE, true).apply()
        saveBootstrapStatus("WRITE_SECURE_SETTINGS granted and verified")
        updateSetupSnapshot(primeServerAlive = true)
        AppState.appendLog("[PRIME-MODE ] Bootstrap confirmed ✅")
    }

    private fun saveBootstrapStatus(s: String) {
        prefs().edit().putString(KEY_BOOTSTRAP_STATUS, s).apply()
        updateSetupSnapshot()
    }

    fun forgetPairing() {
        if (AppState.isGodModeActive.get() || prefs().getBoolean(KEY_RESTORE_PENDING, false)) {
            setState(State.ERROR, "Deactivate Prime Mode and restore phone settings before forgetting pairing")
            return
        }
        if (activationRequested.get() || sequenceRunning || pairingRequested.get() || isSetupBusyState()) {
            setState(State.ERROR, "Wait for the current Prime operation to finish before forgetting pairing")
            return
        }
        setState(State.CONNECTING, "Removing Prime pairing safely…")
        scope.launch {
            try {
                commandGate.withPermit {
                // A trusted pairing is not truly forgotten while the detached
                // shell server still accepts this install's token. Never rotate
                // the token and strand an unreachable old daemon on the port.
                val serverWasAlive = PrimeClient.isAlive()
                if (serverWasAlive && !PrimeClient.shutdown()) {
                    setState(State.ERROR, "Could not stop Prime Server — pairing was not forgotten")
                    return@withPermit
                }
                if (serverWasAlive) {
                    delay(250L)
                    if (PrimeClient.isAlive(timeoutMs = 250)) {
                        setState(State.ERROR, "Prime Server is still running — pairing was not forgotten")
                        return@withPermit
                    }
                }

                PrimeAuth.rotate(appContext)
                activationJob?.cancel()
                activationRequested.set(false)
                pairingRequested.set(false)
                AdbKeyManager.clearAll(appContext)
                sequenceRunning = false
                activeCommands.clear()
                pairingHost = null; pairingPort = 0
                connectHost = null; connectPort = 0
                stopWifiMonitor()
                _primeWifiRetryState.value = null
                prefs().edit()
                    .putBoolean(KEY_BOOTSTRAP_DONE, false)
                    .putBoolean(KEY_PAIRED_TRUST, false)
                    .apply()
                AppState.primeServerAlive = false
                updateSetupSnapshot(primeServerAlive = false)
                setState(State.NOT_PAIRED, "Pairing forgotten — run setup again")
                    AppState.appendLog("[PRIME-MODE ] Pairing forgotten")
                }
            } catch (e: Exception) {
                setState(State.ERROR, "Could not forget pairing safely: ${e.message ?: "unknown error"}")
                AppState.appendLog("[PRIME-MODE ] Forget pairing failed: ${e.message}")
            }
        }
    }

    // ─── Role & network helpers ───────────────────────────────────────────

    private fun isGroupOwner() = AppState.localIp.get() == "192.168.49.1"

    private fun isWifiConnected(): Boolean {
        return try {
            val cm   = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)
        } catch (_: Exception) { false }
    }

    // ─── WiFi retry (Group Owner fallback) ────────────────────────────────

    private fun startWifiMonitorForRetry() {
        wifiMonitorJob?.cancel()
        wifiMonitorJob = scope.launch {
            AppState.appendLog("[PRIME-MODE ] WiFi monitor started for retry button")
            while (true) { _primeWifiRetryState.value = isWifiConnected(); delay(2000L) }
        }
    }

    private fun stopWifiMonitor() { wifiMonitorJob?.cancel(); wifiMonitorJob = null }

    fun retryAfterWifi() {
        AppState.appendLog("[PRIME-MODE ] WiFi retry tapped — re-running")
        stopWifiMonitor()
        _primeWifiRetryState.value = null
        connectPort = 0
        activateGodMode()
    }

    private fun updateSetupSnapshot(primeServerAlive: Boolean = AppState.primeServerAlive) {
        _setupSnapshot.value = SetupSnapshot(
            pairedTrusted = isPairingTrusted(),
            bootstrapped = isBootstrapped(),
            primeServerAlive = primeServerAlive,
            restorePending = prefs().getBoolean(KEY_RESTORE_PENDING, false),
            bootstrapStatus = getBootstrapStatus()
        )
    }

    private fun isSetupBusyState(state: State = _state.value): Boolean {
        return state == State.DISCOVERING ||
               state == State.PAIRING ||
               state == State.BOOTSTRAPPING ||
               state == State.CONNECTING
    }

    private fun syncIdleStateFromSetup() {
        if (isSetupBusyState() || activationRequested.get() || _state.value == State.PRIME_MODE_ACTIVE || _state.value == State.ERROR) return

        val snapshot = _setupSnapshot.value
        when {
            snapshot.bootstrapped -> {
                if (_state.value != State.PAIRED_IDLE) {
                    setState(State.PAIRED_IDLE, "Prime Mode ready")
                } else {
                    _status.value = "Prime Mode ready"
                }
            }
            snapshot.pairedTrusted -> {
                if (_state.value != State.PAIRED_IDLE) {
                    setState(State.PAIRED_IDLE, "Paired — bootstrap pending")
                } else {
                    _status.value = "Paired — bootstrap pending"
                }
            }
            _state.value != State.NOT_PAIRED && _state.value != State.ERROR -> setState(State.NOT_PAIRED, "Not paired")
        }
    }

    // ─── Activate ─────────────────────────────────────────────────────────

    /**
     * Main entry point.
     *
     * PrimeServer alive  → runGodModeSequence() via PrimeClient. No ADB. No WiFi.
     * PrimeServer dead   → launchPrimeServer() [requires wlan0] → runGodModeSequence().
     */
    fun activateGodMode() {
        if (deactivationRequested.get()) {
            publishAction("activate", PrimeActionPhase.ERROR, "Wait for Prime to finish restoring phone settings")
            return
        }
        if (!isApexMasterEnabled()) {
            setState(State.ERROR, "Turn on Prime Master before activation")
            return
        }
        if (!isPairingTrusted()) {
            setState(State.ERROR, "Prime Mode not paired — run setup first")
            AppState.appendLog("[PRIME-MODE ] activateGodMode: not paired")
            return
        }
        // FIX-B: Include PRIME_MODE_ACTIVE in the guard. Without it, tapping ACTIVATE
        // while commands are already done would run the full sequence again — wasting
        // resources and producing the triple-activation seen in logs (three runs 10s apart).
        // Also block if sequenceRunning to handle the brief PAIRED_IDLE window at startup.
        if (_state.value == State.BOOTSTRAPPING ||
            _state.value == State.CONNECTING ||
            _state.value == State.PRIME_MODE_ACTIVE ||
            sequenceRunning) return
        if (!activationRequested.compareAndSet(false, true)) return
        publishAction("activate", PrimeActionPhase.WORKING, "Starting Prime Mode…")

        activationJob = scope.launch {
            try {
                commandGate.withPermit {
                    var alive = PrimeClient.isAlive()
                    if (alive && PrimeClient.protocolVersion < 5) {
                        if (PrimeGameplayTracker.isMatchProtected()) {
                            setState(State.ERROR, "Finish the match before upgrading the Prime engine")
                            publishAction("activate", PrimeActionPhase.ERROR, _status.value)
                            return@withPermit
                        }
                        if (prefs().getBoolean(KEY_RESTORE_PENDING, false) && !restoreExactSettingsNow()) {
                            setState(State.ERROR, "Previous Prime settings need restoration before engine upgrade")
                            publishAction("activate", PrimeActionPhase.ERROR, _status.value)
                            return@withPermit
                        }
                        if (!PrimeClient.shutdown()) throw IllegalStateException("Prime engine upgrade could not stop the old engine")
                        repeat(10) { if (PrimeClient.isAlive(timeoutMs = 200)) delay(200L) }
                        alive = PrimeClient.isAlive()
                        if (alive) throw IllegalStateException("Old Prime engine did not stop")
                    }
                    if (alive || ensurePrimeServerAlive(!isBootstrapped())) {
                        runGodModeSequence()
                    }
                    if (!AppState.isGodModeActive.get()) {
                        publishAction("activate", PrimeActionPhase.ERROR, _status.value)
                    }
                }
            } catch (cancelled: CancellationException) {
                publishAction("activate", PrimeActionPhase.ERROR, "Prime activation cancelled")
                throw cancelled
            } catch (e: Exception) {
                AppState.appendLog("[PRIME-MODE ] Activation failed: ${e.javaClass.simpleName}")
                setState(State.ERROR, "Prime activation failed; retry activation")
                publishAction("activate", PrimeActionPhase.ERROR, _status.value)
            } finally {
                // Also runs if cancelled while waiting to acquire the command gate.
                sequenceRunning = false
                activationRequested.set(false)
            }
        }
    }

    fun onVpnStopped() {
        if (_state.value == State.PAIRED_IDLE || _state.value == State.NOT_PAIRED) return
        AppState.appendLog("[PRIME-MODE ] VPN stopped — reversing optimizations")
        deactivateGodMode()
    }

    fun deactivateGodMode() {
        if (!deactivationRequested.compareAndSet(false, true)) return
        publishAction("deactivate", PrimeActionPhase.WORKING, "Restoring phone settings…")
        val inFlight = activationJob
        inFlight?.cancel()
        scope.launch {
            try {
                inFlight?.join()
                commandGate.withPermit {
                    sequenceRunning = false
                    activeCommands.clear()
                    val memoryRestored = memoryDirector?.stop() != false
                    autoTuner?.stop()
                    val settingsRestored = restoreExactSettingsNow()
                    val allRestored = memoryRestored && settingsRestored
                    AppState.isGodModeActive.set(false)
                    if (allRestored) {
                        _primeLinkState.value = PrimeLinkState.IDLE
                        stopGuardianLoop()
                        setState(State.PAIRED_IDLE, "Prime Mode deactivated — phone settings restored")
                        AppState.appendLog("[PRIME-MODE ] All changed settings restored exactly")
                        LanLinkForegroundService.stop(appContext)
                        publishAction("deactivate", PrimeActionPhase.SUCCESS, "Prime Mode stopped and phone settings restored")
                    } else {
                        _primeLinkState.value = PrimeLinkState.DEGRADED
                        setState(State.ERROR, "Prime Mode stopped, but some phone settings need restore")
                        AppState.appendLog("[PRIME-MODE ] Restore incomplete — snapshot retained for retry")
                        publishAction("deactivate", PrimeActionPhase.ERROR, "Some settings still need automatic restore")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setState(State.ERROR, "Phone settings could not be fully restored; retry deactivation")
                publishAction("deactivate", PrimeActionPhase.ERROR, _status.value)
            } finally {
                deactivationRequested.set(false)
            }
        }
    }

    // ─── Launch PrimeServer via ADB (once per reboot) ─────────────────────

    /**
     * Connects to adbd and runs app_process to start PrimeServer in background.
     * Returns true once PrimeServer is confirmed alive. Does NOT run any session
     * commands — callers decide what to do next.
     */
    private suspend fun ensurePrimeServerAlive(needBootstrap: Boolean): Boolean {
        setState(if (needBootstrap) State.BOOTSTRAPPING else State.CONNECTING,
            if (needBootstrap) "Completing one-time Prime setup…" else "Restoring Prime Mode connection…")
        AppState.appendLog("[PRIME-MODE ] ensurePrimeServerAlive: starting (needBootstrap=$needBootstrap)")

        val result = PrimeShizukuBootstrapEngine(appContext).start(needBootstrap, connectPort) { stage ->
            _status.value = stage
            publishAction("activate", PrimeActionPhase.WORKING, stage)
            AppState.appendLog("[PRIME-MODE ] $stage")
        }
        when (result) {
            is PrimeShizukuBootstrapEngine.Result.Failure -> {
                AppState.appendLog("[PRIME-MODE ] Bootstrap FAILED: ${result.reason}${result.throwable?.let { " — ${it.message}" } ?: ""}")
                saveBootstrapStatus("PENDING: ${result.reason}")
                updateSetupSnapshot(primeServerAlive = false)
                setState(State.ERROR, result.reason)
                return false
            }
            is PrimeShizukuBootstrapEngine.Result.Success -> {
                AppState.appendLog("[PRIME-MODE ] Shizuku-style start sent via 127.0.0.1:${result.port}")

            }
        }

        val deadline = android.os.SystemClock.elapsedRealtime() + SERVER_WAIT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (PrimeClient.isAlive()) {
                AppState.appendLog("[PRIME-MODE ] PrimeServer alive ✅")
                AppState.primeServerAlive = true
                stopWifiMonitor()
                _primeWifiRetryState.value = null
                if (needBootstrap) markBootstrapped()
                saveBootstrapStatus(if (needBootstrap) "COMPLETE — PrimeServer launched" else "RECOVERY — PrimeServer launched")
                return true
            }
            delay(300L)
        }

        AppState.appendLog("[PRIME-MODE ] PrimeServer did not bind in ${SERVER_WAIT_MS / 1000}s")
        AppState.primeServerAlive = false
        updateSetupSnapshot(primeServerAlive = false)
        setState(State.ERROR, "Prime Server failed to start")
        return false
    }

    private fun executePrimeShellChecked(command: String, timeoutMs: Int = PrimeClient.CMD_TIMEOUT_MS): PrimeExecResult {
        val wrapped = "( $command ); __pl_rc=\$?; printf '\\n${primeExitMarker}%s\\n' \"${'$'}__pl_rc\""
        val raw = PrimeClient.execute(wrapped, timeoutMs)
            ?: return PrimeExecResult("", -1, false)
        val idx = raw.lastIndexOf(primeExitMarker)
        if (idx < 0) return PrimeExecResult(raw.trim(), -1, false)

        val output = raw.substring(0, idx).trim()
        val exitRaw = raw.substring(idx + primeExitMarker.length).trim()
        val exit = exitRaw.lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: -1
        return PrimeExecResult(output, exit, exit == 0)
    }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** Capture every global value before Prime changes it. */
    private fun captureRestoreSnapshot(keepGame: Boolean): Boolean {
        val p = prefs()
        if (p.getBoolean(KEY_RESTORE_PENDING, false)) return true

        fun readSetting(name: String): String? {
            val result = executePrimeShellChecked("settings get global $name")
            if (!result.ok) return null
            val value = result.output.trim()
            return if (value.isBlank() || value.equals("null", ignoreCase = true)) RESTORE_DELETE else value
        }

        val airplane = if (isAirplaneShieldEnabled()) readSetting("airplane_mode_radios") ?: return false else RESTORE_DELETE

        val whitelistResult = executePrimeShellChecked("dumpsys deviceidle whitelist")
        if (!whitelistResult.ok) return false
        fun listed(pkg: String): Boolean = whitelistResult.output.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed == pkg || trimmed.endsWith(",$pkg") || trimmed.endsWith("=$pkg")
        }

        val gameBucket = if (keepGame) {
            val result = executePrimeShellChecked("am get-standby-bucket $EFOOTBALL_PKG")
            if (!result.ok) return false
            result.output.lineSequence().firstOrNull()?.trim()?.takeIf { it.toIntOrNull() != null }
                ?: return false
        } else RESTORE_DELETE

        val wifiBands = if (is5GhzOverrideEnabled()) {
            val result = executePrimeShellChecked("cmd wifi get-wifi-bands")
            if (!result.ok) return false
            result.output.trim().takeIf { it.matches(Regex("[0-9]+")) } ?: return false
        } else RESTORE_DELETE

        val committed = p.edit()
            // Remove legacy F10 snapshot fields before creating a new snapshot.
            // If an F10 restore is genuinely pending we returned above and keep
            // those values; otherwise they must never leak into this session.
            .remove(KEY_RESTORE_WIFI_SUSPEND)
            .remove(KEY_RESTORE_WIFI_SLEEP)
            .remove(KEY_RESTORE_CAPTIVE)
            .putString(KEY_RESTORE_AIRPLANE, airplane)
            .putString(KEY_RESTORE_GAME_BUCKET, gameBucket)
            .putString(KEY_RESTORE_WIFI_BANDS, wifiBands)
            .putBoolean(KEY_RESTORE_APP_WHITELISTED, listed(appContext.packageName))
            .putBoolean(KEY_RESTORE_GAME_WHITELISTED, listed(EFOOTBALL_PKG))
            .putBoolean(KEY_RESTORE_APPLIED_AIRPLANE, isAirplaneShieldEnabled())
            .putBoolean(KEY_RESTORE_APPLIED_CAPTIVE, false)
            .putBoolean(KEY_RESTORE_APPLIED_GAME, keepGame)
            .putBoolean(KEY_RESTORE_APPLIED_BANDS, is5GhzOverrideEnabled())
            .putBoolean(KEY_RESTORE_PENDING, true)
            .commit()
        if (committed) {
            updateSetupSnapshot()
            AppState.appendLog("[PRIME-MODE ] Original device settings captured for exact restore")
        }
        return committed
    }

    private fun settingsRestoreCommand(name: String, prefKey: String): String? {
        val value = prefs().getString(prefKey, null) ?: return null
        return if (value == RESTORE_DELETE) {
            "settings delete global $name"
        } else {
            "settings put global $name ${shellSingleQuote(value)}"
        }
    }

    private fun buildExactRestoreCommands(): List<Pair<String, String>> {
        val p = prefs()
        if (!p.getBoolean(KEY_RESTORE_PENDING, false)) return emptyList()
        return buildList {
            settingsRestoreCommand("wifi_suspend_optimizations_enabled", KEY_RESTORE_WIFI_SUSPEND)
                ?.let { add(it to "Wi-Fi power setting restored") }
            settingsRestoreCommand("wifi_sleep_policy", KEY_RESTORE_WIFI_SLEEP)
                ?.let { add(it to "Wi-Fi sleep setting restored") }
            if (p.getBoolean(KEY_RESTORE_APPLIED_AIRPLANE, false)) {
                settingsRestoreCommand("airplane_mode_radios", KEY_RESTORE_AIRPLANE)
                    ?.let { add(it to "Airplane radio list restored") }
            }
            if (p.getBoolean(KEY_RESTORE_APPLIED_CAPTIVE, false)) {
                settingsRestoreCommand("captive_portal_mode", KEY_RESTORE_CAPTIVE)
                    ?.let { add(it to "Captive portal setting restored") }
            }
            if (!p.getBoolean(KEY_RESTORE_APP_WHITELISTED, false)) {
                add("dumpsys deviceidle whitelist -${appContext.packageName}" to "PeerLink Doze exception restored")
            }
            if (p.getBoolean(KEY_RESTORE_APPLIED_GAME, false) &&
                !p.getBoolean(KEY_RESTORE_GAME_WHITELISTED, false)) {
                add("dumpsys deviceidle whitelist -$EFOOTBALL_PKG" to "eFootball Doze exception restored")
            }
            p.getString(KEY_RESTORE_GAME_BUCKET, null)
                ?.takeIf { it != RESTORE_DELETE }
                ?.toIntOrNull()
                ?.let { bucket ->
                    // get-standby-bucket can report EXEMPTED (5), but Android's
                    // setter rejects values below ACTIVE (10). EXEMPTED is a
                    // system-derived effective state, so there is nothing valid
                    // for shell to restore explicitly. Never let that optional
                    // state keep Prime trapped in a permanent restore loop.
                    if (bucket >= 10) {
                        add("am set-standby-bucket $EFOOTBALL_PKG $bucket" to "eFootball standby bucket restored")
                    } else {
                        AppState.appendLog("[PRIME-MODE ] Standby bucket $bucket is system-derived; explicit restore skipped")
                    }
                }
            p.getString(KEY_RESTORE_WIFI_BANDS, null)
                ?.takeIf { it != RESTORE_DELETE && it.matches(Regex("[0-9]+")) }
                ?.let { add("cmd wifi set-wifi-bands $it" to "Wi-Fi band preference restored") }
        }
    }

    private fun restoreExactSettingsNow(): Boolean {
        var allRestored = true
        val cpuMode = prefs().getInt("prime_restore_cpu_mode", 0)
        if (cpuMode in 1..4) {
            val before = executePrimeShellChecked("cmd game list-modes $EFOOTBALL_PKG")
            val activeMode = before.takeIf { it.ok }?.output?.let { PrimePerformanceModeParser.currentMode(it) }
            if (activeMode != null && activeMode != 2) {
                // A subsequent user/graphics mode selection superseded our performance mode.
                prefs().edit().remove("prime_restore_cpu_mode").commit()
            } else {
                val restored = executePrimeShellChecked("cmd game mode $cpuMode $EFOOTBALL_PKG")
                val current = executePrimeShellChecked("cmd game list-modes $EFOOTBALL_PKG")
                if (restored.ok && current.ok && PrimePerformanceModeParser.currentMode(current.output) == cpuMode) {
                    prefs().edit().remove("prime_restore_cpu_mode").commit()
                } else allRestored = false
            }
        }

        buildExactRestoreCommands().forEach { (cmd, label) ->
            val result = executePrimeShellChecked(cmd)
            if (result.ok) {
                AppState.appendLog("[PRIME-MODE ] $label ✓")
            } else {
                allRestored = false
                AppState.appendLog("[PRIME-MODE ] $label FAILED: exit=${result.exitCode} ${result.output.take(80)}")
            }
        }
        if (allRestored) clearRestoreSnapshot()
        return allRestored
    }

    private fun clearRestoreSnapshot() {
        prefs().edit()
            .remove(KEY_RESTORE_WIFI_SUSPEND)
            .remove(KEY_RESTORE_WIFI_SLEEP)
            .remove(KEY_RESTORE_AIRPLANE)
            .remove(KEY_RESTORE_CAPTIVE)
            .remove(KEY_RESTORE_GAME_BUCKET)
            .remove(KEY_RESTORE_WIFI_BANDS)
            .remove(KEY_RESTORE_APP_WHITELISTED)
            .remove(KEY_RESTORE_GAME_WHITELISTED)
            .remove(KEY_RESTORE_APPLIED_AIRPLANE)
            .remove(KEY_RESTORE_APPLIED_CAPTIVE)
            .remove(KEY_RESTORE_APPLIED_GAME)
            .remove(KEY_RESTORE_APPLIED_BANDS)
            .putBoolean(KEY_RESTORE_PENDING, false)
            .apply()
        updateSetupSnapshot()
    }

    // ─── Pre-VPN RAM clear ────────────────────────────────────────────────

    fun runRamClearNow() {
        AppState.appendLog("[PRIME-MODE ] Legacy RAM clear skipped — unsafe/no pinning effect")
    }

    // ─── Radio management ─────────────────────────────────────────────────


    fun enableRadiosForSession() {
        if (!::appContext.isInitialized || !isApexMasterEnabled() || !isAutoConnectEnabled()) return
        scope.launch {
            try {
                if (!PrimeClient.isAlive(timeoutMs = 350)) {
                    AppState.appendLog("[PRIME-MODE ] Auto-connect skipped — local Prime server is not ready")
                    return@launch
                }
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null && !wm.isWifiEnabled) {
                    val result = executePrimeShellChecked("svc wifi enable")
                    delay(750L)
                    if (result.ok && wm.isWifiEnabled) {
                        AppState.appendLog("[PRIME-MODE ] Auto-connect: Wi-Fi enabled without opening Settings")
                    } else {
                        AppState.appendLog("[PRIME-MODE ] Auto-connect could not enable Wi-Fi; use Android Quick Settings")
                    }
                }
            } catch (e: Exception) {
                AppState.appendLog("[PRIME-MODE ] enableRadiosForSession: ${e.message}")
            }
        }
    }
    /**
     * SCHED_FIFO requires root/CAP_SYS_NICE on Android. Prime normally runs as
     * shell uid 2000, so stock non-root phones keep the native loops at the
     * safe URGENT_DISPLAY priority already set by the backend. We only invoke
     * chrt when the executing identity is actually uid 0.
     */
    fun promoteHotThreads(tidsProvider: () -> IntArray) {
        if (!isApexMasterEnabled() || !PrimeClient.isAlive()) {
            AppState.appendLog("[PRIME-RT  ] Skipped — Prime inactive; hot threads stay at default priority")
            return
        }
        scope.launch {
            val uid = executePrimeShellChecked("id -u")
            if (!uid.ok || uid.output.lineSequence().firstOrNull()?.trim() != "0") {
                AppState.appendLog("[PRIME-RT  ] Stock non-root device — using safe URGENT_DISPLAY packet threads")
                return@launch
            }
            var tids = IntArray(0)
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                tids = runCatching { tidsProvider() }.getOrDefault(IntArray(0))
                // Four hot loops: TUN reader, peer RX, peer TX, TUN injector.
                if (tids.size >= 4) break
                delay(150L)
            }
            if (tids.isEmpty()) {
                AppState.appendLog("[PRIME-RT  ] No hot-thread TIDs reported — skipping SCHED_FIFO promotion")
                return@launch
            }
            var promoted = 0
            for (tid in tids) {
                if (tid <= 0) continue
                val r = executePrimeShellChecked("chrt -f -p 10 $tid")
                if (r.ok) {
                    promoted++
                } else {
                    AppState.appendLog("[PRIME-RT  ] chrt tid=$tid FAILED: exit=${r.exitCode} ${r.output.take(80)}")
                }
            }
            AppState.appendLog("[PRIME-RT  ] SCHED_FIFO(prio=10) applied to $promoted/${tids.size} hot thread(s)")
        }
    }

    // ─── Pairing flow ─────────────────────────────────────────────────────

    fun startNotificationPairing(context: Context) {
        if (!isSupported()) {
            setState(State.ERROR, "Prime Mode requires Android 11 or newer")
            return
        }
        setState(State.DISCOVERING, "Check notification shade — enter the 6-digit code")
        AppState.appendLog("[PRIME-MODE ] Starting notification pairing service")
        try {
            GodModePairingService.start(context)
        } catch (t: Throwable) {
            setState(State.ERROR, "Could not start pairing watcher: ${t.message ?: "unknown error"}")
            AppState.appendLog("[PRIME-MODE ] Pairing service start failed: ${t.message}")
        }
    }

    fun onNsdPairingTimeout() {
        if (_state.value == State.DISCOVERING) {
            setState(State.DISCOVERING, "Auto-detect timed out — enter PORT:CODE manually or restart")
            AppState.appendLog("[PRIME-MODE ] NSD timeout — manual pairing remains active")
        }
    }

    fun beginPairingDiscovery() {
        setState(State.DISCOVERING, "Waiting for Wireless Debugging service")
        startWatching()
    }

    fun onPairingPortDiscovered(host: String, port: Int) {
        pairingHost = host; pairingPort = port
        AppState.appendLog("[PRIME-MODE ] Pairing endpoint cached: $host:$port")
        if (_state.value == State.DISCOVERING) {
            setState(State.DISCOVERING, "Pairing endpoint detected — enter the 6-digit code")
        }
    }

    fun onPairingServiceStopped(cancelled: Boolean) {
        if (_state.value != State.DISCOVERING && _state.value != State.PAIRING) return
        updateSetupSnapshot()
        val snap = _setupSnapshot.value
        when {
            snap.bootstrapped -> setState(State.PAIRED_IDLE, "Prime Mode ready")
            snap.pairedTrusted -> setState(State.PAIRED_IDLE, "Paired — bootstrap pending")
            cancelled -> setState(State.NOT_PAIRED, "Pairing cancelled — start again when ready")
            else -> setState(State.NOT_PAIRED, "Pairing watcher stopped — start again")
        }
    }

    fun onPairingAttemptFailed(message: String) {
        if (_setupSnapshot.value.bootstrapped || _setupSnapshot.value.pairedTrusted) {
            syncIdleStateFromSetup()
        } else {
            setState(State.DISCOVERING, "Pairing failed — correct the code and try again")
        }
        AppState.appendLog("[PRIME-MODE ] Pairing attempt remains retryable: $message")
    }

    /**
     * Shared parser for both the in-app form and notification RemoteInput.
     * Accepts CODE or PORT:CODE and keeps all validation in one place.
     */
    fun submitPairingInput(
        rawInput: String,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        val raw = rawInput.trim()
        val manualPort: Int
        val pin: String
        if (raw.contains(':')) {
            val parts = raw.split(':', limit = 2)
            manualPort = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            pin = parts.getOrNull(1)?.trim().orEmpty()
            if (manualPort !in 1024..65535) {
                onResult(false, "Port must be between 1024 and 65535")
                return
            }
        } else {
            manualPort = 0
            pin = raw
        }
        if (pin.length != 6 || !pin.all(Char::isDigit)) {
            onResult(false, "Enter the exact 6-digit pairing code")
            return
        }
        submitPairingPin(
            pin = pin,
            manualHost = if (manualPort > 0) "127.0.0.1" else "",
            manualPort = manualPort,
            onResult = onResult,
        )
    }

    /**
     * First-time setup. Called by GodModePairingService when user submits PIN.
     *
     * pair → find connect port → connect → pm grant WRITE_SECURE_SETTINGS →
     * canary write → markBootstrapped → adb_wifi_enabled=1 →
     * app_process PrimeServer & → wait isAlive → runGodModeSequence
     */
    fun submitPairingPin(
        pin: String,
        manualHost: String = "",
        manualPort: Int = 0,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val port = when {
            manualPort > 0 -> manualPort
            pairingPort > 0 -> pairingPort
            else -> {
                onResult(false, "Pairing port unknown. Enter PORT:CODE (e.g. 45678:123456).")
                return
            }
        }
        val hostsToTry = buildList {
            add("127.0.0.1")
            val alt = manualHost.ifBlank { pairingHost }
            if (alt != null && alt != "127.0.0.1") add(alt)
        }

        if (!pairingRequested.compareAndSet(false, true)) {
            onResult(false, "A pairing attempt is already running")
            return
        }

        setState(State.PAIRING, "Connecting to ADB daemon")
        startWatching()
        AppState.appendLog("[PRIME-MODE ] Pairing port=$port hosts=$hostsToTry")

        scope.launch {
            try {
                var lastFail = "All hosts failed"
                for (host in hostsToTry) {
                    AppState.appendLog("[PRIME-MODE ] Trying $host:$port")
                    when (val result = AdbPairingClient(appContext).pair(host, port, pin)) {
                        is AdbPairingClient.Result.Success -> {
                            AppState.appendLog("[PRIME-MODE ] Paired via $host ✅")
                            pairingHost = null
                            pairingPort = 0
                            markPairingTrusted()
                            saveBootstrapStatus("PENDING: paired, waiting for explicit ACTIVATE/start")
                            setState(State.PAIRED_IDLE, "Paired — keep Wireless debugging on and tap Activate")
                            onResult(true, "Paired successfully. Keep Wireless Debugging ON and tap Activate Now in PeerLink.")
                            return@launch
                        }
                        is AdbPairingClient.Result.Failure -> {
                            lastFail = result.reason
                            AppState.appendLog("[PRIME-MODE ] $host failed: ${result.reason}")
                        }
                    }
                }
                setState(State.NOT_PAIRED, "Pairing failed: $lastFail")
                updateSetupSnapshot(primeServerAlive = false)
                onResult(false, lastFail)
            } catch (t: Throwable) {
                val message = t.message ?: "Unexpected pairing failure"
                setState(State.NOT_PAIRED, "Pairing failed: $message")
                AppState.appendLog("[PRIME-MODE ] Pairing exception: $message")
                onResult(false, message)
            } finally {
                pairingRequested.set(false)
            }
        }
    }

    // ─── runGodModeSequence — all via PrimeClient ─────────────────────────

    /**
     * Runs all optimisation commands through PrimeClient → TCP loopback → PrimeServer → shell.
     * Zero ADB. Zero WiFi needed. Works completely offline once PrimeServer is running.
     * Each command is verified with a follow-up read for [OK]/[WARN] logging.
     */
    private suspend fun runGodModeSequence() {
        // FIX-C: Set CONNECTING at the very start (not mid-sequence) and raise the
        // sequenceRunning flag so stopWatching() knows not to interfere.
        sequenceRunning = true
        setState(State.CONNECTING, "Applying performance lock…")

        try {

        ensurePerformanceEngines()
        probePerformanceCapabilities()
        val keepGame = getKeepGameInRam()
        if (!captureRestoreSnapshot(keepGame)) {
            setState(State.ERROR, "Could not capture current phone settings safely")
            AppState.isGodModeActive.set(false)
            AppState.appendLog("[PRIME-MODE ] Activation stopped — exact restore snapshot unavailable")
            return
        }

        // 3. LanLink doze immunity
        val peerLinkDozeOk = runAndLog(
            "dumpsys deviceidle whitelist +${appContext.packageName}",
            "LanLink doze immunity", "doze_lanlink",
            verify = "dumpsys deviceidle whitelist", expectation = appContext.packageName
        ); delay(150)

        // The VPN service owns Android's real WIFI_MODE_FULL_LOW_LATENCY lock
        // for the exact lifetime of gameplay. Root-only `cmd wifi force-*`
        // commands are intentionally not attempted on this non-root product.

        if (!peerLinkDozeOk) {
            val restored = restoreExactSettingsNow()
            AppState.isGodModeActive.set(false)
            setState(
                State.ERROR,
                if (restored) "Prime connected, but required performance settings were rejected"
                else "Prime failed and some phone settings still need restore",
            )
            return
        }

        var optionalFailures = 0

        // 7. Airplane mode shield
        if (isAirplaneShieldEnabled()) {
            if (!runAndLog(
                "settings put global airplane_mode_radios cell,bluetooth,nfc,wimax",
                "Airplane shield on", "airplane_shield",
                verify = "settings get global airplane_mode_radios",
                expectation = "cell,bluetooth,nfc,wimax"
            )) optionalFailures++
            delay(150)
        }

        // eFootball priority (not a RAM pin; stock Android exposes no such API)
        keepGameWasActive = keepGame
        if (keepGame) {
            if (!runAndLog(
                "dumpsys deviceidle whitelist +$EFOOTBALL_PKG",
                "eFootball doze immunity", "doze_efootball",
                verify = "dumpsys deviceidle whitelist", expectation = EFOOTBALL_PKG
            )) optionalFailures++
            delay(100)
            // Some devices report STANDBY_BUCKET_EXEMPTED (5). That is already
            // stronger than ACTIVE (10) and cannot legally be passed back to
            // set-standby-bucket. Only force ACTIVE when the current effective
            // bucket is actually worse than ACTIVE.
            val currentBucket = executePrimeShellChecked("am get-standby-bucket $EFOOTBALL_PKG")
                .takeIf { it.ok }
                ?.output
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.toIntOrNull()
            if (currentBucket != null && currentBucket <= 10) {
                AppState.appendLog("[PRIME-MODE ] eFootball standby priority already optimal ($currentBucket)")
            } else if (!runAndLog(
                "am set-standby-bucket $EFOOTBALL_PKG active",
                "eFootball active standby priority", "bucket_efootball",
                verify = "am get-standby-bucket $EFOOTBALL_PKG", expectation = "10"
            )) optionalFailures++
        }

        // Capability-aware CPU policy. We only expose/apply Performance Game
        // Mode when the OEM reports it for eFootball; unsupported phones never
        // see a fake switch.
        if (isCpuGameModeEnabled() && _capabilities.value.gamePerformanceMode) {
            val beforeMode = executePrimeShellChecked("cmd game list-modes $EFOOTBALL_PKG")
            val original = beforeMode.takeIf { it.ok }?.output?.let { PrimePerformanceModeParser.currentMode(it) }
            if (original == null || (!prefs().contains("prime_restore_cpu_mode") && !prefs().edit().putInt("prime_restore_cpu_mode", original).commit())) {
                optionalFailures++
            } else {
                val applied = executePrimeShellChecked("cmd game mode performance $EFOOTBALL_PKG")
                val after = executePrimeShellChecked("cmd game list-modes $EFOOTBALL_PKG")
                if (!applied.ok || !after.ok || PrimePerformanceModeParser.currentMode(after.output) != 2) optionalFailures++
            }
        }

        // Manual/Auto render profile is game-specific and intentionally persists
        // across Prime sessions. Android applies the backbuffer override on the
        // next game start.
        if (_capabilities.value.gameDownscale) {
            val scale = getRenderScale()
            if (scale != "1.00") {
                if (autoTuner?.applyScale(scale) != true) optionalFailures++
            }
        }

        val total = 1 +
                (if (isAirplaneShieldEnabled())  1 else 0) +
                (if (keepGame)                   2 else 0) +
                (if (isCpuGameModeEnabled() && _capabilities.value.gamePerformanceMode) 1 else 0) +
                (if (_capabilities.value.gameDownscale && getRenderScale() != "1.00") 1 else 0)

        currentCoroutineContext().ensureActive()
        val activeStatus = buildString {
            append("Prime Mode active")
            if (keepGame) append(" · Game priority on")
            if (optionalFailures > 0) append(" · $optionalFailures optional setting(s) unavailable")
        }
        setState(State.PRIME_MODE_ACTIVE, activeStatus)
        AppState.isGodModeActive.set(true)
        _primeLinkState.value = PrimeLinkState.CONNECTED
        startGuardianLoop()
        ensureGuardianService()
        memoryDirector?.start()
        autoTuner?.start()
        publishAction("activate", PrimeActionPhase.SUCCESS, "Prime Mode is active")
        AppState.appendLog("[PRIME-MODE ] Applied ${total - optionalFailures}/$total requested settings")

        } finally {
            // FIX-C: Always release the lock, even if an exception occurs mid-sequence
            sequenceRunning = false
        }
    }

    // ─── runAndLog — PrimeClient edition ──────────────────────────────────

    /**
     * Executes via PrimeClient and logs [OK]/[WARN]/[FAIL].
     * Skips if key already ran this session (prevents double-execution on VPN restart).
     */
    private suspend fun runAndLog(
        cmd:         String,
        label:       String,
        key:         String,
        verify:      String? = null,
        expectation: String? = null
    ): Boolean {
        if (activeCommands.contains(key)) {
            AppState.appendLog("[PRIME] $label — already active, skipping")
            return true
        }
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val result = executePrimeShellChecked(cmd)
        if (!result.ok) {
            AppState.appendLog("[PRIME] [$ts] [FAIL] $label → exit=${result.exitCode} ${result.output.take(80)}")
            return false
        }

        return if (verify != null && expectation != null) {
            val verifyResult = executePrimeShellChecked(verify)
            val ok = verifyResult.ok && verifyResult.output.contains(expectation, ignoreCase = true)
            AppState.appendLog("[PRIME] [$ts] ${if (ok) "[OK] " else "[WARN]"} $label → ${verifyResult.output.take(80)}")
            if (ok) activeCommands.add(key)
            ok
        } else {
            AppState.appendLog("[PRIME] [$ts] [OK]  $label")
            activeCommands.add(key)
            true
        }
    }

    // ─── Misc helpers ─────────────────────────────────────────────────────

    fun openWirelessDebuggingSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val isPairingPortReady: Boolean get() = pairingPort > 0

    private fun setState(s: State, msg: String = "") {
        _state.value = s; _status.value = msg
        if (msg.isNotBlank()) AppState.appendLog("[PRIME-MODE ] $s — $msg")
        else                  AppState.appendLog("[PRIME-MODE ] $s")
    }

    private fun prefs(): android.content.SharedPreferences {
        if (!::appContext.isInitialized) {
            val app = try {
                val cls = Class.forName("android.app.ActivityThread")
                val method = cls.getMethod("currentApplication")
                method.invoke(null) as? android.app.Application
            } catch (_: Throwable) {
                null
            }
            if (app != null) {
                appContext = app.applicationContext
            } else {
                throw IllegalStateException("GodModeManager appContext unavailable")
            }
        }
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
