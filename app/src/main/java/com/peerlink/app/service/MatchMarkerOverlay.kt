package com.peerlink.app.service

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.PrimeClient

/**
 * Small in-game match overlay.
 *
 * The old diagnostic GOAL button is intentionally gone. The overlay now has
 * three lightweight states owned by [MatchAutomationEngine]: waiting, H/A
 * side selection, and the manual FT fallback after both peers have locked
 * complementary sides.
 */
object MatchMarkerOverlay {
    enum class Mode { WAITING, SIDE_CHOICES, HOME_SELECTED, AWAY_SELECTED, FULL_TIME }

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var actions: LinearLayout? = null
    private var position: WindowManager.LayoutParams? = null
    private var appContext: Context? = null
    private var mode: Mode = Mode.WAITING
    private var lastTapMs = 0L
    private var attentionAnimator: ObjectAnimator? = null
    private var conflictAnimator: ObjectAnimator? = null

    /** Serializes FT hold start/stop so Prime sees a strict press→release order. */
    private val whistleExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "whistle-tap").apply { isDaemon = true }
    }

    @Volatile
    private var ftRecording = false

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun removeWindow() {
        stopAttention()
        conflictAnimator?.cancel()
        conflictAnimator = null
        if (ftRecording) {
            // Overlay torn down mid-hold: close the tap so Prime never keeps
            // a session alive with no UI able to stop it.
            ftRecording = false
            whistleExecutor.execute { discardWhistleTap() }
        }
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        actions = null
        windowManager = null
        position = null
        appContext = null
    }

    private fun clampPosition(context: Context, params: WindowManager.LayoutParams, view: View) {
        val metrics = context.resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - view.measuredWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - view.measuredHeight).coerceAtLeast(0))
    }

    fun show(context: Context) {
        val app = context.applicationContext
        main.post {
            val prefs = app.getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE)
            if (!AppState.isRunning.get() ||
                !prefs.getBoolean("match_marker_enabled", true) ||
                !Settings.canDrawOverlays(app)
            ) {
                removeWindow()
                return@post
            }
            root?.let { view ->
                position?.let { params ->
                    val oldX = params.x
                    val oldY = params.y
                    clampPosition(app, params, view)
                    if (oldX != params.x || oldY != params.y) {
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                }
                renderActions()
                return@post
            }

            appContext = app
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val panel = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4))
                background = GradientDrawable().apply {
                    setColor(0xEB111F2E.toInt())
                    cornerRadius = dp(app, 16).toFloat()
                    setStroke(dp(app, 1), 0x66344D66)
                }
            }
            val handle = TextView(app).apply {
                text = "⠿"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(0xFFB7C8DA.toInt())
                minWidth = dp(app, 48)
                minHeight = dp(app, 48)
                contentDescription = "Drag match controls"
            }
            val actionBox = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            panel.addView(handle)
            panel.addView(actionBox)
            actions = actionBox

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = prefs.getInt("marker_x", (app.resources.displayMetrics.widthPixels - dp(app, 174)).coerceAtLeast(0))
                y = prefs.getInt("marker_y", dp(app, 86))
            }

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            handle.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        clampPosition(app, params, panel)
                        runCatching { wm.updateViewLayout(panel, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit().putInt("marker_x", params.x).putInt("marker_y", params.y).apply()
                        view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }

            runCatching {
                wm.addView(panel, params)
                windowManager = wm
                root = panel
                position = params
                panel.post {
                    if (root === panel) {
                        clampPosition(app, params, panel)
                        runCatching { wm.updateViewLayout(panel, params) }
                    }
                }
                renderActions()
                AppState.appendLog("[MATCH-UI  ] overlay=shown mode=$mode")
            }.onFailure {
                AppState.appendLog("[MATCH-UI  ] overlay show failed: ${it.message}")
            }
        }
    }

    fun hide() {
        main.post { removeWindow() }
    }

    fun setWaiting() = setMode(Mode.WAITING)

    fun beginSideSelection() {
        setMode(Mode.SIDE_CHOICES)
        main.post {
            startAttention()
            AppState.appendLog("[MATCH-ROLE] H/A selection requested")
        }
    }

    fun showSelected(side: MatchControlChannel.Side) {
        setMode(if (side == MatchControlChannel.Side.HOME) Mode.HOME_SELECTED else Mode.AWAY_SELECTED)
    }

    fun showFullTime() {
        setMode(Mode.FULL_TIME)
    }

    fun conflictFeedback() {
        main.post {
            stopAttention()
            val app = appContext ?: return@post
            vibrate(app, durationMs = 320L, amplitude = 190)
            val panel = root ?: return@post
            conflictAnimator?.cancel()
            conflictAnimator = ObjectAnimator.ofFloat(
                panel,
                View.TRANSLATION_X,
                0f,
                dp(app, 10).toFloat(),
                -dp(app, 10).toFloat(),
                dp(app, 8).toFloat(),
                -dp(app, 8).toFloat(),
                dp(app, 4).toFloat(),
                -dp(app, 4).toFloat(),
                0f,
            ).apply {
                duration = 360L
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    fun rejectFeedback() {
        main.post {
            val app = appContext ?: return@post
            vibrate(app, durationMs = 85L, amplitude = 110)
            root?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

    private fun setMode(newMode: Mode) {
        main.post {
            if (mode == newMode) return@post
            if (mode == Mode.FULL_TIME && ftRecording) {
                // The FT button vanished mid-hold (auto-capture won the race):
                // never leave Prime recording in the background.
                ftRecording = false
                whistleExecutor.execute { discardWhistleTap() }
            }
            mode = newMode
            if (newMode != Mode.SIDE_CHOICES) stopAttention()
            renderActions()
        }
    }

    private fun renderActions() {
        val app = appContext ?: return
        val box = actions ?: return
        box.removeAllViews()

        fun button(label: String, description: String, fill: Int, onClick: () -> Unit): TextView = TextView(app).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(app, 58)
            minHeight = dp(app, 50)
            setPadding(dp(app, 8), 0, dp(app, 8), 0)
            contentDescription = description
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(app, 13).toFloat()
            }
            setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastTapMs < 220L || !AppState.isRunning.get()) return@setOnClickListener
                lastTapMs = now
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

        when (mode) {
            Mode.WAITING -> Unit
            Mode.SIDE_CHOICES -> {
                val h = button("H", "Choose Home", 0xFF245F50.toInt()) {
                    MatchAutomationEngine.chooseLocalSide(MatchControlChannel.Side.HOME)
                }
                val a = button("A", "Choose Away", 0xFF334D73.toInt()) {
                    MatchAutomationEngine.chooseLocalSide(MatchControlChannel.Side.AWAY)
                }
                box.addView(h, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(app, 10)
                })
                box.addView(a)
            }
            Mode.HOME_SELECTED -> box.addView(
                button("H", "Confirm Home", 0xFF245F50.toInt()) {
                    MatchAutomationEngine.confirmLocalSide(MatchControlChannel.Side.HOME)
                }
            )
            Mode.AWAY_SELECTED -> box.addView(
                button("A", "Confirm Away", 0xFF334D73.toInt()) {
                    MatchAutomationEngine.confirmLocalSide(MatchControlChannel.Side.AWAY)
                }
            )
            Mode.FULL_TIME -> box.addView(whistleFtButton(app))
        }
    }

    /**
     * FT button with two gestures:
     *  tap  (<400 ms) — exactly the previous behavior: grab the visible
     *                   full-time score through MatchAutomationEngine.
     *  hold (≥400 ms) — record the game's own audio while held (press starts
     *                   Prime's loop-back tap, release stops it). A held
     *                   recording that carries sound is saved under
     *                   filesDir/whistle/ref_*.wav so Settings can play it
     *                   back and the match export zip can carry it.
     *
     * A quick tap also cancels the capture its press accidentally started, so
     * the tap path can never leave Prime recording in the background.
     */
    private fun whistleFtButton(app: Context): TextView {
        val view = TextView(app).apply {
            text = "FT"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(app, 58)
            minHeight = dp(app, 50)
            setPadding(dp(app, 8), 0, dp(app, 8), 0)
            contentDescription = "Tap: capture score. Hold: record the whistle."
            background = GradientDrawable().apply {
                setColor(0xFF733B49.toInt())
                cornerRadius = dp(app, 13).toFloat()
            }
        }
        var downAt = 0L
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!AppState.isRunning.get()) return@setOnTouchListener true
                    downAt = android.os.SystemClock.elapsedRealtime()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    ftRecording = true
                    whistleExecutor.execute {
                        val resp = PrimeClient.audioStart()
                        if (resp?.optBoolean("ok", false) == true) {
                            AppState.appendLog("[WHISTLE] hold recording started")
                        } else {
                            ftRecording = false
                            AppState.appendLog(
                                "[WHISTLE] hold start failed: ${resp?.optString("error") ?: "Prime unreachable"}",
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val held = android.os.SystemClock.elapsedRealtime() - downAt
                    val started = ftRecording
                    ftRecording = false
                    if (AppState.isRunning.get() && held >= 400L && started) {
                        whistleExecutor.execute { stopAndSaveReference(app) }
                    } else {
                        // Tap, or a hold that never actually started recording.
                        whistleExecutor.execute { discardWhistleTap() }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (AppState.isRunning.get() && now - lastTapMs >= 220L) {
                            lastTapMs = now
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            MatchAutomationEngine.manualFullTimeCapture()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (ftRecording) whistleExecutor.execute { discardWhistleTap() }
                    ftRecording = false
                    true
                }
                else -> true
            }
        }
        return view
    }

    /** Release-side of the hold: fetch the WAV and keep it if it has sound. */
    private fun stopAndSaveReference(app: Context) {
        val probe = PrimeClient.audioStop()
        val header = probe?.header
        val err = header?.optString("error", "").orEmpty()
        if (probe == null || header == null || !header.optBoolean("ok", false) || probe.wav.isEmpty()) {
            AppState.appendLog("[WHISTLE] hold ended without audio: ${err.ifBlank { "no response" }}")
            val ctx = appContext ?: app
            main.post { vibrate(ctx, durationMs = 85L, amplitude = 110) }
            return
        }
        if (header.optBoolean("silent", true)) {
            AppState.appendLog("[WHISTLE] hold heard only silence (${probe.wav.size} bytes) — not saved")
            val ctx = appContext ?: app
            main.post { vibrate(ctx, durationMs = 85L, amplitude = 110) }
            return
        }
        val name = saveWhistleFile(app, "ref", probe.wav)
        if (name != null) {
            AppState.appendLog(
                "[WHISTLE] reference saved: $name (${probe.wav.size} bytes, peak=${header.optInt("peak")})",
            )
            val ctx = appContext ?: app
            main.post { vibrate(ctx, durationMs = 120L, amplitude = 170) }
        } else {
            AppState.appendLog("[WHISTLE] reference save failed (disk)")
            val ctx = appContext ?: app
            main.post { vibrate(ctx, durationMs = 85L, amplitude = 110) }
        }
    }

    /** Best-effort close of an unwanted session (tap path, teardown). */
    private fun discardWhistleTap() {
        runCatching {
            val probe = PrimeClient.audioStop(timeoutMs = 5_000)
            if (probe?.header?.optBoolean("ok", false) == true) {
                AppState.appendLog("[WHISTLE] short capture discarded")
            }
            // "not_active" is the common, expected outcome here — stays quiet.
        }
    }

    private fun saveWhistleFile(app: Context, prefix: String, wav: ByteArray): String? = runCatching {
        val dir = java.io.File(app.filesDir, "whistle").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(dir, "${prefix}_${stamp}.wav")
        file.writeBytes(wav)
        file.name
    }.getOrNull()

    private fun startAttention() {
        val app = appContext ?: return
        val panel = root ?: return
        stopAttention()
        vibratePattern(app)
        attentionAnimator = ObjectAnimator.ofFloat(
            panel,
            View.TRANSLATION_X,
            0f,
            dp(app, 4).toFloat(),
            -dp(app, 4).toFloat(),
            0f,
        ).apply {
            duration = 260L
            repeatCount = 10
            interpolator = LinearInterpolator()
            start()
        }
        main.postDelayed({ stopAttention() }, 3_000L)
    }

    private fun stopAttention() {
        attentionAnimator?.cancel()
        attentionAnimator = null
        root?.translationX = 0f
        val app = appContext ?: return
        val vibrator = vibrator(app)
        runCatching { vibrator?.cancel() }
    }

    private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun vibratePattern(context: Context) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 70, 170, 70, 170, 70, 170, 70, 170, 70, 170, 70, 170, 70),
                        intArrayOf(0, 90, 0, 90, 0, 90, 0, 90, 0, 90, 0, 90, 0, 90),
                        -1,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 70, 170, 70, 170, 70, 170, 70), -1)
            }
        }
    }

    private fun vibrate(context: Context, durationMs: Long, amplitude: Int) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        }
    }
}
