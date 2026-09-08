package com.peerlink.app.godmode

import java.io.Reader
import java.io.IOException

/** Stop at our exit marker: detached descendants may keep the ADB pipe open. */
internal object PrimeAdbShellReader {
    fun read(reader: Reader, marker: String, maxChars: Int = 64 * 1024): String {
        val text = StringBuilder()
        while (text.length < maxChars) {
            val ch = reader.read()
            if (ch < 0) throw IOException("ADB shell closed before reporting command status")
            text.append(ch.toChar())
            if (ch != '\n'.code) continue
            val markerAt = text.lastIndexOf("\n$marker")
            if (markerAt < 0) continue
            val exit = text.substring(markerAt + marker.length + 1).trim().toIntOrNull()
                ?: throw IOException("Invalid ADB command status")
            if (exit != 0) throw IOException("ADB command failed (exit $exit)")
            return text.substring(0, markerAt).trim()
        }
        throw IOException("ADB command output exceeded limit")
    }
}
