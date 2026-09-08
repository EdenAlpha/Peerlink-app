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
import com.peerlink.app.godmode.PrimeClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Very small, on-device eFootball final-score reader.
 *
 * Prime v4 performs the expensive full-display screenshot in the privileged
 * process and returns only the two narrow score-bearing bands. The app therefore
 * decodes/OCRs roughly a third of a frame instead of a complete game frame.
 * ML Kit is kept as the recognizer because it is substantially more tolerant of
 * eFootball font/rendering changes than fixed digit templates, while geometry +
 * screen anchors keep false positives out of the statistics table.
 */
object PrimeScreenScoreDetector {
    data class Score(val home: Int, val away: Int, val source: String, val finalScreen: Boolean = false)

    class CapturedFrame internal constructor(
        val bitmap: Bitmap,
        val topHeight: Int,
        val gap: Int,
        val referenceHeight: Int,
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

    /** Capture only. Kept separate so the 4 Hz producer is not blocked by OCR. */
    fun captureFrame(context: Context): CapturedFrame? {
        // Preferred path: Prime crops/scales before the bytes cross loopback.
        PrimeClient.captureScoreFrame()?.let { frame ->
            val bitmap = BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size) ?: return@let
            if (frame.topHeight <= 0 || frame.gap < 0 || frame.topHeight.toLong() + frame.gap >= bitmap.height || frame.referenceHeight <= 0) {
                bitmap.recycle()
                return@let
            }
            return CapturedFrame(bitmap, frame.topHeight, frame.gap, frame.referenceHeight)
        }

        // A slow v4/v5 capture must not trigger two extra full screenshots.
        if (PrimeClient.protocolVersion == 0 && !PrimeClient.isAlive()) return null
        if (PrimeClient.protocolVersion >= 4) return null

        // A v3 Prime daemon can survive an APK update until reboot/recovery.
        // Keep it usable by taking the old full PNG and cropping locally.
        val png = PrimeClient.captureScreenPng() ?: captureViaLegacyPrime(context) ?: return null
        val source = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        return try {
            makeComposite(source)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    /**
     * Recognize only the isolated score glyphs plus two tiny Full-Time text
     * strips. One ML Kit pass is therefore enough for both the score and the
     * finality signal; the full composite is never OCRed.
     */
    fun detectFrame(frame: CapturedFrame): Score? {
        val prepared = ScoreVisualPreprocessor.prepare(frame.bitmap, frame.referenceHeight) ?: return null
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
            Score(reading.home, reading.away, reading.source, finalScreen)
        } finally {
            prepared.recycle()
        }
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
        val frame = makeComposite(source) ?: return null
        return try {
            detectFrame(frame)
        } finally {
            frame.recycle()
        }
    }

    private fun makeComposite(source: Bitmap): CapturedFrame? {
        if (source.width < 400 || source.height < 240) return null

        // These bands cover all three observed eFootball result presentations:
        // top Full-Time menu, top statistics result, and the bottom walking-pitch
        // score banner. The normal top-left in-game clock/scoreboard is excluded.
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
        return CapturedFrame(composite, topHeight, gap, source.height)
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
