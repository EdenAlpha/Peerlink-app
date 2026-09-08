package com.peerlink.app.godmode

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Deadlines cover output collection AND process exit, including early stdout EOF. */
internal object PrimeShellRunner {
    fun run(command: List<String>, timeoutMs: Long, maxBytes: Int): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val reader = FutureTask<String> {
            process.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) {
                        process.destroyForcibly()
                        throw IOException("Command output exceeded limit")
                    }
                    out.write(chunk, 0, count)
                }
                out.toString(Charsets.UTF_8.name()).trim()
            }
        }
        Thread(reader, "prime-shell-output").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("Command timed out")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("Command timed out")
            return reader.get(remaining, TimeUnit.NANOSECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.cancel(true)
        }
    }
}
