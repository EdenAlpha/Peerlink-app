package com.peerlink.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the three supplied eFootball result layouts into a tiny OCR image.
 *
 * This is deliberately not a general screenshot OCR pass.  The score boxes are
 * fixed by the game UI, so we first isolate the actual glyph pixels and throw
 * away team names, logos, pitch, statistics and the rest of the display.
 */
internal object ScoreVisualPreprocessor {
    data class Prepared(
        val bitmap: Bitmap,
        val scoreHeight: Int,
        val gap: Int,
        val referenceHeight: Int,
    ) {
        fun recycle() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private data class Cell(val mask: Bitmap, val ink: Int)
    private data class Row(val home: Cell, val away: Cell) {
        val strength: Int get() = min(home.ink, away.ink)
    }

    private const val CELL_W = 140
    private const val CELL_H = 140
    private const val CELL_GAP = 30
    private const val FINALITY_W = 300
    private const val FINALITY_H = 72
    private const val FINALITY_GAP = 12

    fun prepare(source: Bitmap, referenceHeight: Int): Prepared? {
        if (source.width < 240 || source.height < 180 || referenceHeight <= 0) return null

        // The Prime composite is the central 22..78% of the original display,
        // with the original top 32% followed by the original bottom 30%.
        val top = buildRow(source, 0.16f, 0.40f)
        val bottom = buildRow(source, 0.64f, 0.88f)
        val row = when {
            top != null && bottom != null -> if (top.strength >= bottom.strength) top else bottom
            top != null -> top
            bottom != null -> bottom
            else -> return null
        }

        // A genuine score has substantial glyph ink in both left and right cells.
        // This also rejects the small yellow statistics labels in the lower band.
        if (row.strength < 35) {
            row.home.mask.recycle()
            row.away.mask.recycle()
            return null
        }

        val finality = buildFinality(source)
        val outWidth = CELL_W * 2 + CELL_GAP
        val outHeight = CELL_H + FINALITY_GAP + FINALITY_H
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(row.home.mask, 0f, 0f, null)
        canvas.drawBitmap(row.away.mask, (CELL_W + CELL_GAP).toFloat(), 0f, null)

        if (finality != null) {
            canvas.drawBitmap(finality, 0f, (CELL_H + FINALITY_GAP).toFloat(), null)
            finality.recycle()
        }
        row.home.mask.recycle()
        row.away.mask.recycle()

        return Prepared(output, CELL_H, CELL_GAP, outHeight)
    }

    private fun buildRow(source: Bitmap, y0: Float, y1: Float): Row? {
        val home = buildCell(source, 0.38f, 0.49f, y0, y1) ?: return null
        val away = buildCell(source, 0.51f, 0.62f, y0, y1) ?: run {
            home.mask.recycle()
            return null
        }
        return Row(home, away)
    }

    private fun buildCell(source: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): Cell? {
        val left = (source.width * x0).toInt().coerceIn(0, source.width - 2)
        val right = (source.width * x1).toInt().coerceIn(left + 1, source.width)
        val top = (source.height * y0).toInt().coerceIn(0, source.height - 2)
        val bottom = (source.height * y1).toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return try {
            val pixels = IntArray(crop.width * crop.height)
            crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)

            // First recognize the yellow score boxes used by the walking and
            // statistics screens.  Only the dark-blue glyph inside that box is
            // retained; grass and team text therefore cannot become digits.
            val yellow = BooleanArray(pixels.size)
            var yellowCount = 0
            val hsv = FloatArray(3)
            for (i in pixels.indices) {
                val c = pixels[i]
                Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
                val hit = hsv[0] in 15f..45f && hsv[1] >= 0.58f && hsv[2] >= 0.67f
                yellow[i] = hit
                if (hit) yellowCount++
            }
            val yellowBox = findLargeBox(yellow, crop.width, crop.height)
            if (yellowBox != null) {
                val mask = buildDarkBlueGlyph(crop, yellowBox)
                if (mask != null) return Cell(mask, inkCount(mask))
            }

            // Full-Time menu uses yellow glyphs directly on black. Require a
            // genuinely large connected glyph so small yellow UI labels cannot
            // masquerade as a score.
            val yellowMask = buildYellowGlyphMask(yellow, crop.width, crop.height)
            if (yellowCount >= 100 && yellowMask != null) {
                return Cell(yellowMask, inkCount(yellowMask))
            }
            null
        } finally {
            crop.recycle()
        }
    }

    private fun findLargeBox(mask: BooleanArray, width: Int, height: Int): Rect? {
        // Connected components are implemented with a tiny flood fill because
        // the score cell is only ~100x100 pixels and this avoids OpenCV/OpenGL.
        val seen = BooleanArray(mask.size)
        var best: Rect? = null
        var bestArea = 0
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY
            var area = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % width
                val y = p / width
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) visit(mask, seen, queue, tail, p - 1).also { tail = it }
                if (x + 1 < width) visit(mask, seen, queue, tail, p + 1).also { tail = it }
                if (y > 0) visit(mask, seen, queue, tail, p - width).also { tail = it }
                if (y + 1 < height) visit(mask, seen, queue, tail, p + width).also { tail = it }
            }
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (area >= width * height * 0.12f && boxW >= width * 0.42f && boxH >= height * 0.38f && area > bestArea) {
                bestArea = area
                best = Rect(minX, minY, maxX + 1, maxY + 1)
            }
        }
        return best
    }

    private fun visit(mask: BooleanArray, seen: BooleanArray, queue: IntArray, tail: Int, index: Int): Int {
        if (mask[index] && !seen[index]) {
            seen[index] = true
            queue[tail] = index
            return tail + 1
        }
        return tail
    }

    private fun buildDarkBlueGlyph(source: Bitmap, box: Rect): Bitmap? {
        val inset = 3
        val left = (box.left + inset).coerceAtMost(box.right - 1)
        val top = (box.top + inset).coerceAtMost(box.bottom - 1)
        val right = (box.right - inset).coerceAtLeast(left + 1)
        val bottom = (box.bottom - inset).coerceAtLeast(top + 1)
        val w = right - left
        val h = bottom - top
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, left, top, w, h)
        val mask = BooleanArray(pixels.size)
        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val c = pixels[i]
            Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
            mask[i] = hsv[0] in 90f..145f && hsv[1] >= 0.30f && hsv[2] <= 0.63f
        }
        return normalizeMask(mask, w, h, CELL_W, CELL_H, minInk = 30)
    }

    private fun buildYellowGlyphMask(mask: BooleanArray, width: Int, height: Int): Bitmap? {
        // Keep only meaningful connected glyph components. The Full-Time label
        // is outside the vertical score crop, while each score digit is large.
        val keep = BooleanArray(mask.size)
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY
            var area = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % width
                val y = p / width
                area++
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                if (x > 0) visit(mask, seen, queue, tail, p - 1).also { tail = it }
                if (x + 1 < width) visit(mask, seen, queue, tail, p + 1).also { tail = it }
                if (y > 0) visit(mask, seen, queue, tail, p - width).also { tail = it }
                if (y + 1 < height) visit(mask, seen, queue, tail, p + width).also { tail = it }
            }
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (area >= 100 && boxH >= height * 0.20f && boxW >= width * 0.08f) {
                for (i in 0 until tail) keep[queue[i]] = true
            }
        }
        return normalizeMask(keep, width, height, CELL_W, CELL_H, minInk = 30)
    }

    private fun normalizeMask(mask: BooleanArray, width: Int, height: Int, outW: Int, outH: Int, minInk: Int): Bitmap? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var ink = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            val x = i % width
            val y = i / width
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            ink++
        }
        if (ink < minInk || maxX < minX || maxY < minY) return null
        val pad = 4
        minX = (minX - pad).coerceAtLeast(0)
        minY = (minY - pad).coerceAtLeast(0)
        maxX = (maxX + pad + 1).coerceAtMost(width)
        maxY = (maxY + pad + 1).coerceAtMost(height)
        val srcW = maxX - minX
        val srcH = maxY - minY
        val src = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val px = IntArray(srcW * srcH)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                px[y * srcW + x] = if (mask[(y + minY) * width + x + minX]) Color.WHITE else Color.BLACK
            }
        }
        src.setPixels(px, 0, srcW, 0, 0, srcW, srcH)
        val scale = min((outW - 12).toFloat() / srcW, (outH - 12).toFloat() / srcH)
        val scaledW = max(1, (srcW * scale).toInt())
        val scaledH = max(1, (srcH * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, false)
        src.recycle()
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, ((outW - scaledW) / 2f), ((outH - scaledH) / 2f), null)
        scaled.recycle()
        return out
    }

    private fun buildFinality(source: Bitmap): Bitmap? {
        val menu = cropFinality(source, 0.04f, 0.18f, menu = true)
        val stats = cropFinality(source, 0.34f, 0.44f, menu = false)
        if (menu == null && stats == null) return null
        val out = Bitmap.createBitmap(FINALITY_W, FINALITY_H * 2 + FINALITY_GAP, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        menu?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            it.recycle()
        }
        stats?.let {
            canvas.drawBitmap(it, 0f, (FINALITY_H + FINALITY_GAP).toFloat(), null)
            it.recycle()
        }
        return out
    }

    private fun cropFinality(source: Bitmap, y0: Float, y1: Float, menu: Boolean): Bitmap? {
        val left = (source.width * 0.35f).toInt().coerceIn(0, source.width - 2)
        val right = (source.width * 0.65f).toInt().coerceIn(left + 1, source.width)
        val top = (source.height * y0).toInt().coerceIn(0, source.height - 2)
        val bottom = (source.height * y1).toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return try {
            val pixels = IntArray(crop.width * crop.height)
            crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
            val mask = BooleanArray(pixels.size)
            val hsv = FloatArray(3)
            for (i in pixels.indices) {
                val c = pixels[i]
                if (menu) {
                    Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
                    mask[i] = hsv[0] in 15f..45f && hsv[1] >= 0.45f && hsv[2] >= 0.55f
                } else {
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    val gray = (r * 30 + g * 59 + b * 11) / 100
                    mask[i] = gray < 105
                }
            }
            normalizeMask(mask, crop.width, crop.height, FINALITY_W, FINALITY_H, minInk = 40)
        } finally {
            crop.recycle()
        }
    }

    private fun inkCount(bitmap: Bitmap): Int {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return px.count { Color.red(it) > 180 }
    }
}
