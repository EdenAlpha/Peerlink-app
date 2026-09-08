package com.peerlink.app.godmode

import com.peerlink.app.core.AppState
import com.peerlink.app.core.PrimeGameplayTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Conservative graphics auto-tuner. It measures eFootball's own SurfaceFlinger
 * layer rather than trusting the display refresh rate. Auto mode only steps
 * render scale down after repeated measured jank / missed target FPS. Manual
 * mode is never overridden.
 */
class PrimeAutoTuner(
    private val execute: (String) -> PrimeExecResult,
    private val capabilities: () -> PrimeCapabilities,
    private val graphicsMode: () -> PrimeGraphicsMode,
    private val currentScale: () -> String,
    private val targetFps: () -> Int,
    private val performanceGameModeEnabled: () -> Boolean,
    private val gameForeground: () -> Boolean,
    private val applyScaleOverride: (String) -> Boolean,
    private val persistScale: (String) -> Unit,
) {
    companion object {
        private const val GAME = "jp.konami.pesam"
        private const val SAMPLE_MS = 20_000L
        private val SCALES = listOf("1.00", "0.90", "0.85", "0.80", "0.75", "0.70")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var badSamples = 0
    private var stableSamples = 0
    private var lastSuggested = ""

    private val _frameStats = MutableStateFlow(PrimeFrameStats())
    val frameStats: StateFlow<PrimeFrameStats> = _frameStats.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var captureRunning = false
            while (isActive) {
                try {
                    val active = gameForeground() || PrimeGameplayTracker.isMatchProtected()
                    val caps = capabilities()
                    if (active && caps.surfaceFlingerTimeStats) {
                        if (!captureRunning) {
                            execute("dumpsys SurfaceFlinger --timestats -clear -enable")
                            captureRunning = true
                            delay(SAMPLE_MS)
                            continue
                        }
                        val dump = execute("dumpsys SurfaceFlinger --timestats -dump")
                        currentCoroutineContext().ensureActive()
                        if (dump.ok) parseGameStats(dump.output)?.let(::evaluate)
                        execute("dumpsys SurfaceFlinger --timestats -clear -enable")
                    } else if (captureRunning) {
                        execute("dumpsys SurfaceFlinger --timestats -disable")
                        captureRunning = false
                        badSamples = 0
                        stableSamples = 0
                    }
                } catch (cancelled: CancellationException) { throw cancelled } catch (t: Exception) {
                    AppState.appendLog("[PRIME-AUTO ] Frame sampler: ${t.message}")
                }
                delay(if (gameForeground() || PrimeGameplayTracker.isMatchProtected()) SAMPLE_MS else 3_000L)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        execute("dumpsys SurfaceFlinger --timestats -disable")
    }

    fun applyScale(scale: String): Boolean {
        // The compatibility controller owns backend selection and readback.
        // Auto Tuner only requests a scale; it never guesses OEM commands.
        val applied = applyScaleOverride(scale)
        if (applied) {
            persistScale(scale)
            AppState.appendLog(
                "[PRIME-GPU  ] Render scale $scale verified for next eFootball start"
            )
            return true
        }
        AppState.appendLog(
            "[PRIME-GPU  ] Render scale $scale was not verified; previous setting retained"
        )
        return false
    }

    private fun evaluate(stats: PrimeFrameStats) {
        val target = targetFps().coerceIn(30, 60)
        val jankRatio = if (stats.totalFrames > 0) stats.jankyFrames.toFloat() / stats.totalFrames else 0f
        val missesTarget = stats.averageFps > 0f && stats.averageFps < target * 0.96f
        val gpuDominant = stats.gpuJankyFrames >= stats.cpuJankyFrames
        val bad = (missesTarget || jankRatio >= 0.045f) && gpuDominant
        val stable = stats.averageFps >= target * 0.99f && jankRatio < 0.015f

        if (bad) {
            badSamples++
            stableSamples = 0
        } else if (stable) {
            stableSamples++
            badSamples = 0
        } else {
            badSamples = 0
            stableSamples = 0
        }

        var recommendation: String? = null
        var note = when {
            bad && gpuDominant -> "GPU-side jank detected"
            missesTarget -> "Below ${target} FPS target"
            stable -> "Frame delivery is stable"
            else -> "Watching frame consistency"
        }

        if (badSamples >= 2) {
            recommendation = nextLowerScale(currentScale())
            if (recommendation != null) {
                note = "Recommend $recommendation next launch"
                if (graphicsMode() == PrimeGraphicsMode.AUTO && recommendation != lastSuggested) {
                    if (applyScale(recommendation)) {
                        lastSuggested = recommendation
                        note = "Auto selected $recommendation for next launch"
                    }
                }
            }
            badSamples = 0
        }

        _frameStats.value = stats.copy(recommendedScale = recommendation, note = note)
    }

    private fun nextLowerScale(scale: String): String? {
        val idx = SCALES.indexOf(scale).takeIf { it >= 0 } ?: 0
        return SCALES.getOrNull(idx + 1)
    }

    private fun parseGameStats(text: String): PrimeFrameStats? {
        // TimeStats outputs one block per layer. Select the eFootball block with
        // the most frames so overlays/toasts don't become the tuning signal.
        val blocks = text.split("\nlayerName =").mapIndexed { i, block ->
            if (i == 0) block else "layerName =$block"
        }.filter { it.contains("packageName = $GAME") }
        var best: PrimeFrameStats? = null
        for (block in blocks) {
            fun int(key: String): Int = Regex("(?m)^$key = (\\d+)").find(block)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            fun flt(key: String): Float = Regex("(?m)^$key = ([0-9.]+)").find(block)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
            val s = PrimeFrameStats(
                averageFps = flt("averageFPS"),
                totalFrames = int("totalFrames"),
                jankyFrames = int("jankyFrames"),
                gpuJankyFrames = int("sfLongGpuJankyFrames"),
                cpuJankyFrames = int("sfLongCpuJankyFrames"),
            )
            if (s.totalFrames > (best?.totalFrames ?: -1)) best = s
        }
        return best
    }
}
