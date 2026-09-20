package com.peerlink.app.godmode

import java.io.Reader
import java.io.IOException

/** Stop at our exit marker: detached descendants may keep the ADB pipe open. */
object PrimeAdbShellReader {
    data class Outcome(
        val output: String,
        val exitCode: Int?,
        val complete: Boolean,
    )

    fun read(reader: Reader, marker: String, maxChars: Int = 64 * 1024): String {
        val outcome = readOutcome(reader, marker, maxChars)
        if (!outcome.complete) throw IOException("ADB shell closed before reporting command status")
        val exit = outcome.exitCode ?: throw IOException("Invalid ADB command status")
        if (exit != 0) throw IOException("ADB command failed (exit $exit)")
        return outcome.output
    }

    fun readOutcome(
        reader: Reader,
        marker: String,
        maxChars: Int = 64 * 1024,
        sink: StringBuilder? = null,
    ): Outcome {
        val text = sink ?: StringBuilder()
        val start = text.length
        while (text.length - start < maxChars) {
            val ch = reader.read()
            if (ch < 0) {
                return Outcome(text.substring(start).trim(), null, complete = false)
            }
            text.append(ch.toChar())
            if (ch != '\n'.code) continue
            val markerAt = text.lastIndexOf("\n$marker")
            if (markerAt < start) continue
            val exit = text.substring(markerAt + marker.length + 1).trim().toIntOrNull()
            val body = text.substring(start, markerAt).trim()
            return Outcome(body, exit, complete = true)
        }
        throw IOException("ADB command output exceeded limit")
    }
}
