package com.peerlink.app.service

import android.content.Context
import android.graphics.Bitmap
import com.peerlink.app.core.AppState
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

object ScoreCaptureDump {
    private const val MAX_FILES = 96
    private val seq = AtomicInteger(0)
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        val folder = File(context.applicationContext.getExternalFilesDir(null) ?: context.filesDir, "score_shots")
        if (!folder.exists()) folder.mkdirs()
        dir = folder
    }

    fun save(bitmap: Bitmap, tag: String, note: String): String? {
        val folder = dir ?: return null
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return null
        val id = seq.incrementAndGet()
        val safe = tag.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val stem = "shot_%04d_%s".format(id, safe)
        val jpeg = File(folder, "$stem.jpg")
        return try {
            FileOutputStream(jpeg).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                    jpeg.delete()
                    return null
                }
            }
            File(folder, "$stem.txt").writeText(note.trim() + "\n")
            prune(folder)
            AppState.appendLog("[MATCH-SHOT] id=$id file=$stem.jpg ${bitmap.width}x${bitmap.height} $note")
            stem
        } catch (error: Exception) {
            AppState.appendLog("[MATCH-SHOT] save failed ${error.message}")
            null
        }
    }

    fun exportedFiles(): List<File> {
        val folder = dir ?: return emptyList()
        return folder.listFiles { file -> file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".txt")) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun prune(folder: File) {
        val jpegs = folder.listFiles { file -> file.isFile && file.name.endsWith(".jpg") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val extra = jpegs.size - MAX_FILES
        if (extra <= 0) return
        jpegs.take(extra).forEach { jpeg ->
            jpeg.delete()
            File(folder, jpeg.name.removeSuffix(".jpg") + ".txt").delete()
        }
    }
}
