package com.peerlink.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.peerlink.app.core.AppState
import com.peerlink.app.core.MatchStats
import com.peerlink.app.godmode.PrimeClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F33 on-device eFootball final-score reader.
 *
 * Primary path: Prime v6 delivers a full-display JPEG (`__fullcap_jpeg__`);
 * [ScoreBoardDetector] first classifies the frame from pure colour structure
 * (hue-60 yellow / navy / dark menu) and rejects non-score frames in a few
 * milliseconds, then reads score digits — and the 13 statistics rows on the
 * statistics board — from fixed game-UI geometry against a template bank of
 * real eFootball glyphs. A read costs ~5-25 ms instead of the ~1.5 s the old
 * ML Kit chain needed, and the previous hue-window bug (the old reader
 * filtered hue 15..45° while eFootball draws its yellow at ~60°, so it never
 * read a single frame in the field) cannot recur.
 *
 * ML Kit is retained only as a last-resort fallback when the pixel engine
 * cannot find the digits on a frame the gate classified as a score screen;
 * the resident pre-v6 PrimeServer band composite is still supported for
 * score-only reads until Prime is redeployed.
 */
object PrimeScreenScoreDetector {
    data class Score(
        val home: Int,
        val away: Int,
        val source: String,
        val finalScreen: Boolean = false,
        val stats: MatchStats? = null,
    )

    class CapturedFrame internal constructor(
        val bitmap: Bitmap,
        val topHeight: Int,
        val gap: Int,
        val referenceHeight: Int,
        internal val geometry: ScoreBoardDetector.Geometry,
    ) {
        fun recycle() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private val ocrInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val completionExecutor = java.util.concurrent.Executor { it.run() }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Capture only. Kept separate so the 4 Hz producer is not blocked by reads. */
    fun captureFrame(context: Context): CapturedFrame? {
        ScoreCaptureDump.init(context)
        PrimeClient.captureFullFrame()?.let { frame ->
            val bitmap = BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
            if (bitmap == null) {
                AppState.appendLog("[MATCH-CAP ] fullcap decode failed bytes=${frame.bytes.size} ${frame.width}x${frame.height}")
                return@let
            }
            AppState.appendLog("[MATCH-CAP ] frame=full ${bitmap.width}x${bitmap.height} jpeg=${frame.bytes.size}")
            return CapturedFrame(
                bitmap,
                topHeight = bitmap.height,
                gap = 0,
                referenceHeight = bitmap.height,
                geometry = ScoreBoardDetector.Geometry.Full,
            )
        }

        val png = PrimeClient.captureScreenPng() ?: captureViaLegacyPrime(context)
        if (png != null) {
            val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            if (source != null) {
                AppState.appendLog("[MATCH-CAP ] frame=fullpng ${source.width}x${source.height} bytes=${png.size}")
                return CapturedFrame(
                    source,
                    topHeight = source.height,
                    gap = 0,
                    referenceHeight = source.height,
                    geometry = ScoreBoardDetector.Geometry.Full,
                )
            }
        }

        AppState.appendLog("[MATCH-CAP ] no full frame (scorecap crop skipped so stats stay visible)")
        return null
    }

    /**
     * Two-stage read: colour gate first (sub-10 ms reject for non-score
     * frames), then the fixed-geometry pixel reader. ML Kit only runs when the
     * pixel engine cannot resolve digits on a frame the gate believed to be a
     * score screen — a rare degraded-capture path.
     */
    fun detectFrame(frame: CapturedFrame): Score? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val detection = try {
            ScoreBoardDetector.analyze(frame.bitmap, frame.geometry)
        } catch (error: Exception) {
            val note = "crash ${error.javaClass.simpleName}:${error.message} ${frame.bitmap.width}x${frame.bitmap.height}"
            AppState.appendLog("[MATCH-OCR ] analyze $note")
            ScoreCaptureDump.save(frame.bitmap, "crash", note)
            null
        }
        if (detection == null) {
            AppState.appendLog("[MATCH-OCR ] analyze=null ${frame.bitmap.width}x${frame.bitmap.height} (too small or failed)")
            ScoreCaptureDump.save(frame.bitmap, "null", "analyze=null ${frame.bitmap.width}x${frame.bitmap.height}")
            return null
        }
        val analyzeMs = android.os.SystemClock.elapsedRealtime() - startedAt
        if (detection.type == ScoreBoardDetector.ScreenType.OTHER) {
            val note = "OTHER ${detection.gateInfo} ${frame.bitmap.width}x${frame.bitmap.height} ${analyzeMs}ms"
            AppState.appendLog("[MATCH-OCR ] F33 $note")
            ScoreCaptureDump.save(frame.bitmap, "OTHER", note)
            return null
        }
        val score = detection.score
        if (score != null) {
            val stats = detection.stats
            val matchStats = stats?.let { s ->
                com.peerlink.app.core.MatchStats(s.rows.map {
                    com.peerlink.app.core.MatchStatRow(it.name, it.home, it.away)
                })
            }
            val source = when (detection.type) {
                ScoreBoardDetector.ScreenType.STATS_BOARD -> "f33:board"
                ScoreBoardDetector.ScreenType.WALKING -> "f33:walking"
                ScoreBoardDetector.ScreenType.MENU -> "f33:menu"
                ScoreBoardDetector.ScreenType.OTHER -> "f33"
            }
            val note = "${detection.type} ${score.first}-${score.second} final=${detection.finality} stats=${matchStats?.rows?.size ?: 0} ${detection.gateInfo} ${analyzeMs}ms"
            AppState.appendLog("[MATCH-OCR ] F33 $note")
            ScoreCaptureDump.save(frame.bitmap, "${detection.type}_${score.first}-${score.second}", note)
            return Score(score.first, score.second, source, detection.finalScreen, matchStats)
        }
        AppState.appendLog("[MATCH-OCR ] F33 ${detection.type} no digits ${detection.gateInfo} — trying ML Kit")
        val ml = detectViaMlKit(frame)
        val mlNote = if (ml == null) "MLKit none ${detection.gateInfo}" else "MLKit ${ml.home}-${ml.away} final=${ml.finalScreen}"
        AppState.appendLog("[MATCH-OCR ] $mlNote")
        ScoreCaptureDump.save(frame.bitmap, if (ml == null) "${detection.type}_nodigits" else "MLKIT_${ml.home}-${ml.away}", mlNote)
        return ml
    }

    /** One-shot helper used by the manual FT button. */
    fun captureScore(context: Context): Score? {
        val frame = captureFrame(context) ?: return null
        return try {
            detectFrame(frame)
        } finally {
            frame.recycle()
        }
    }

    // ------------------------------------------------------------------
    // Legacy ML Kit fallback (previous F29 reader, unchanged semantics).
    // ------------------------------------------------------------------
    private fun detectViaMlKit(frame: CapturedFrame): Score? {
        val source = if (frame.geometry is ScoreBoardDetector.Geometry.Composite) {
            frame.bitmap
        } else {
            makeComposite(frame.bitmap)?.bitmap ?: return null
        }
        val prepared = ScoreVisualPreprocessor.prepare(source, frame.referenceHeight)
        if (source !== frame.bitmap) source.recycle()
        if (prepared == null) return null
        return try {
            val text = recognizeBlocking(prepared.bitmap) ?: return null
            val reading = ScoreLaneReader.read(
                tokens(text),
                prepared.bitmap.width,
                prepared.scoreHeight,
                prepared.gap,
                prepared.referenceHeight,
            ) ?: return null
            val finalScreen = FinalScoreEvidence.isFinal(text.text)
            Score(reading.home, reading.away, "mlkit:${reading.source}", finalScreen, null)
        } finally {
            prepared.recycle()
        }
    }

    private fun tokens(text: Text): List<ScoreLaneReader.Token> = buildList {
        fun addToken(value: String, box: Rect?) {
            if (box != null) add(ScoreLaneReader.Token(value, ScoreLaneReader.Box(
                box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(),
            )))
        }
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                addToken(line.text, line.boundingBox)
                line.elements.forEach { addToken(it.text, it.boundingBox) }
            }
        }
    }

    /** Compatibility path for a resident v2 PrimeServer. */
    private fun captureViaLegacyPrime(context: Context): ByteArray? {
        val dir = context.externalCacheDir ?: return null
        if (!dir.exists()) runCatching { dir.mkdirs() }
        val file = java.io.File(dir, "match_score_frame.png")
        return try {
            if (!PrimeClient.captureScreenPngToPath(file.absolutePath)) return null
            if (!file.isFile || file.length() !in 64L..(12L * 1024L * 1024L)) return null
            file.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { file.delete() }
        }
    }

    /** Used by tests and by pre-v4 Prime compatibility. */
    internal fun detectScore(source: Bitmap): Score? {
        val frame = CapturedFrame(
            source,
            topHeight = source.height,
            gap = 0,
            referenceHeight = source.height,
            geometry = ScoreBoardDetector.Geometry.Full,
        )
        return detectFrame(frame)
    }

    /**
     * Build the legacy band composite (central 22..78% width, top 0..32% +
     * bottom 70..100%) for the ML Kit fallback path.
     */
    private fun makeComposite(source: Bitmap): CapturedFrame? {
        if (source.width < 400 || source.height < 240) return null

        val left = (source.width * 0.22f).toInt().coerceIn(0, source.width - 2)
        val right = (source.width * 0.78f).toInt().coerceIn(left + 1, source.width)
        val topHeight = (source.height * 0.32f).toInt().coerceAtLeast(1)
        val lowerTop = (source.height * 0.70f).toInt().coerceIn(0, source.height - 1)
        val gap = (source.height / 140).coerceIn(4, 10)
        val width = right - left
        val height = topHeight + gap + (source.height - lowerTop)
        val composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            source,
            Rect(left, 0, right, topHeight),
            Rect(0, 0, width, topHeight),
            null,
        )
        canvas.drawBitmap(
            source,
            Rect(left, lowerTop, right, source.height),
            Rect(0, topHeight + gap, width, height),
            null,
        )
        return CapturedFrame(
            composite,
            topHeight,
            gap,
            source.height,
            ScoreBoardDetector.Geometry.Composite(topHeight, gap, source.height),
        )
    }

    private fun recognizeBlocking(bitmap: Bitmap): Text? {
        if (!ocrInFlight.compareAndSet(false, true)) return null
        // ML Kit continues after our wait times out. Give it its own bitmap and
        // retain both that bitmap and the single-flight slot until task completion.
        val owned = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) }
        catch (_: Exception) { null }
        if (owned == null) { ocrInFlight.set(false); return null }
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Text?>()
        try {
            recognizer.process(InputImage.fromBitmap(owned, 0))
                .addOnCompleteListener(completionExecutor) { task ->
                    try {
                        if (task.isSuccessful) result.set(task.result)
                    } finally {
                        owned.recycle()
                        ocrInFlight.set(false)
                        latch.countDown()
                    }
                }
        } catch (_: Exception) {
            owned.recycle()
            ocrInFlight.set(false)
            return null
        }
        return try {
            if (latch.await(1_800L, TimeUnit.MILLISECONDS)) result.get() else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null // Completion still owns cleanup; never recycle ML Kit's live input.
        }
    }

}
