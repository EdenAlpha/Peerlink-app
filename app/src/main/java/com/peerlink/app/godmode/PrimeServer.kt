package com.peerlink.app.godmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.IOException
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PrimeServer — long-lived loopback shell server launched with app_process.
 */
@Keep
object PrimeServer {

    const val HOST = "127.0.0.1"
    const val PORT = 13373
    const val PROCESS_NAME = "peerlink_prime"
    const val LOG_PATH = "/data/local/tmp/peerlink_prime.log"

    private const val MAX_BACKLOG = 8
    private const val CONN_TIMEOUT_MS = 30_000
    private const val DEFAULT_CMD_TIMEOUT_MS = 10_000L
    private val screenshotBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val MAX_REQUEST_BYTES = 16 * 1024
    private const val MAX_OUTPUT_BYTES = 512 * 1024
    private const val HEALTH_CMD = "__health__"
    private const val SHUTDOWN_CMD = "__shutdown__"
    private const val SCREENSHOT_CMD = "__screencap_png__"
    private const val SCORE_SCREENSHOT_CMD = "__scorecap_jpeg__"
    private const val FULLCAP_CMD = "__fullcap_jpeg__"
    private const val MAX_SCREENSHOT_BYTES = 12 * 1024 * 1024
    private const val MAX_SCORE_FRAME_BYTES = 2 * 1024 * 1024
    private const val SCORE_FRAME_MAX_WIDTH = 960
    private const val FULL_FRAME_MAX_BYTES = 4 * 1024 * 1024

    /** Shizuku-style app_process launch command. */
    fun buildLaunchCommand(context: Context): String {
        return com.peerlink.app.godmode.shizuku.PrimeShizukuStarter.internalCommand(context)
    }

    fun runForever(authToken: String) {
        if (!PrimeAuth.isValidToken(authToken)) {
            log("FATAL: invalid authentication token")
            return
        }
        setProcessName(PROCESS_NAME)
        log("PrimeServer starting on $HOST:$PORT")

        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName(HOST), PORT), MAX_BACKLOG)
            }
        } catch (e: Exception) {
            log("FATAL: cannot bind port $PORT — ${e.message}")
            System.exit(1)
            return
        }

        // PrimeServer is deliberately command-only. A Wi-Fi lock held by this
        // reboot-long process could never follow the match lifecycle and kept
        // the radio awake after deactivation. PeerLinkVpnService owns the real
        // LOW_LATENCY lock and releases it when the tunnel stops.
        Runtime.getRuntime().addShutdownHook(Thread {
            log("Shutdown hook — closing server")
            try { server.close() } catch (_: Exception) {}
        })

        grantManagerSecureSettings()
        log("PrimeServer listening (PID=${android.os.Process.myPid()})")

        while (!server.isClosed) {
            try {
                val client = server.accept()
                Thread { handleClient(client, authToken) }.apply {
                    isDaemon = true
                    name = "prime_worker"
                    start()
                }
            } catch (e: Exception) {
                if (!server.isClosed) {
                    log("Accept error: ${e.message}")
                    Thread.sleep(100)
                }
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.firstOrNull { it.startsWith("--token=") }
            ?.substringAfter("--token=")
            .orEmpty()
        runForever(token)
    }

    private fun handleClient(socket: Socket, authToken: String) {
        try {
            socket.soTimeout = CONN_TIMEOUT_MS
            val writer = PrintWriter(socket.outputStream, true, Charsets.UTF_8)
            val line = readUtf8LineLimited(socket, MAX_REQUEST_BYTES)?.trim() ?: return
            if (line.isEmpty()) return

            val req = try { JSONObject(line) } catch (_: Exception) {
                log("Bad JSON: ${line.take(60)}")
                return
            }

            val suppliedToken = req.optString("token", "")
            if (!constantTimeEquals(authToken, suppliedToken)) {
                writer.println(JSONObject().apply {
                    put("output", "")
                    put("ok", false)
                    put("error", "unauthorized")
                }.toString())
                log("Rejected unauthenticated loopback client")
                return
            }

            val cmd = req.optString("cmd", "").trim()
            if (cmd.isEmpty()) return

            if (cmd == SCREENSHOT_CMD || cmd == SCORE_SCREENSHOT_CMD || cmd == FULLCAP_CMD) {
                if (!screenshotBusy.compareAndSet(false, true)) {
                    writer.println(JSONObject().put("ok", false).put("error", "capture_busy"))
                    return
                }
                try {
                    if (cmd == FULLCAP_CMD) {
                        // v6: full display JPEG for the statistics-table reader.
                        val fastSource = captureDisplayFull()
                        val full = fastSource?.let { makeFullFrame(it, recycleSource = true) }
                        if (full != null) {
                            writer.println(JSONObject().apply {
                                put("ok", true)
                                put("binaryBytes", full.bytes.size)
                                put("width", full.width)
                                put("height", full.height)
                                put("fullFrame", true)
                                put("capturePath", "surfacecontrol")
                            }.toString())
                            writer.flush()
                            socket.outputStream.write(full.bytes)
                            socket.outputStream.flush()
                        } else {
                            val fullPng = runBinaryCommand(
                                listOf("/system/bin/screencap", "-p"),
                                MAX_SCREENSHOT_BYTES,
                                2_000L,
                            )
                            val decoded = fullPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            val frame = decoded?.let { makeFullFrame(it, recycleSource = true) }
                            if (frame == null) {
                                writer.println(JSONObject().apply {
                                    put("ok", false)
                                    put("error", "full_capture_failed")
                                }.toString())
                            } else {
                                writer.println(JSONObject().apply {
                                    put("ok", true)
                                    put("binaryBytes", frame.bytes.size)
                                    put("width", frame.width)
                                    put("height", frame.height)
                                    put("fullFrame", true)
                                    put("capturePath", "screencap_fallback")
                                }.toString())
                                writer.flush()
                                socket.outputStream.write(frame.bytes)
                                socket.outputStream.flush()
                            }
                        }
                        return
                    }
                    if (cmd == SCORE_SCREENSHOT_CMD) {
                        // Fast path: SurfaceFlinger capture through the privileged
                        // Prime process. No PNG process, filesystem round-trip or
                        // full-frame loopback transfer is needed for the 4 Hz path.
                        val fastSource = captureDisplayFast()
                        val frame = fastSource?.let { makeScoreFrame(it, recycleSource = true) }
                        if (frame != null) {
                            writer.println(JSONObject().apply {
                                put("ok", true)
                                put("binaryBytes", frame.bytes.size)
                                put("topHeight", frame.topHeight)
                                put("gap", frame.gap)
                                put("referenceHeight", frame.referenceHeight)
                                put("scoreComposite", true)
                                put("capturePath", "surfacecontrol")
                            }.toString())
                            writer.flush()
                            socket.outputStream.write(frame.bytes)
                            socket.outputStream.flush()
                        } else {
                            // Compatibility fallback for OEMs/Android builds that
                            // deny the hidden SurfaceControl capture to shell UID.
                            val fullPng = runBinaryCommand(
                                listOf("/system/bin/screencap", "-p"),
                                MAX_SCREENSHOT_BYTES,
                                2_000L,
                            )
                            val legacyFrame = fullPng?.let { makeScoreFrame(it) }
                            if (legacyFrame == null) {
                                writer.println(JSONObject().apply {
                                    put("ok", false)
                                    put("error", "score_capture_failed")
                                }.toString())
                            } else {
                                writer.println(JSONObject().apply {
                                    put("ok", true)
                                    put("binaryBytes", legacyFrame.bytes.size)
                                    put("topHeight", legacyFrame.topHeight)
                                    put("gap", legacyFrame.gap)
                                    put("referenceHeight", legacyFrame.referenceHeight)
                                    put("scoreComposite", true)
                                    put("capturePath", "screencap_fallback")
                                }.toString())
                                writer.flush()
                                socket.outputStream.write(legacyFrame.bytes)
                                socket.outputStream.flush()
                            }
                        }
                    } else {
                        val fullPng = runBinaryCommand(listOf("/system/bin/screencap", "-p"), MAX_SCREENSHOT_BYTES, 2_000L)
                        if (fullPng == null) {
                            writer.println(JSONObject().apply {
                                put("ok", false)
                                put("error", "screencap_failed")
                            }.toString())
                        } else {
                            writer.println(JSONObject().apply {
                                put("ok", true)
                                put("binaryBytes", fullPng.size)
                                put("scoreComposite", false)
                            }.toString())
                            writer.flush()
                            socket.outputStream.write(fullPng)
                            socket.outputStream.flush()
                        }
                    }
                    return
                } finally {
                    screenshotBusy.set(false)
                }
            }

            val output = when (cmd) {
                HEALTH_CMD -> "prime_ok_v6"
                SHUTDOWN_CMD -> "prime_stopping"
                else -> runShell(cmd, req.optLong("timeoutMs", DEFAULT_CMD_TIMEOUT_MS).coerceIn(250L, 120_000L))
            }
            writer.println(JSONObject().apply {
                put("output", output)
                put("ok", true)
            }.toString())
            if (cmd == SHUTDOWN_CMD) {
                Thread {
                    try { Thread.sleep(100L) } catch (_: InterruptedException) { }
                    System.exit(0)
                }.apply { isDaemon = true; name = "prime_shutdown"; start() }
            }
        } catch (_: IOException) {
        } catch (e: Exception) {
            log("Handler error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun runShell(cmd: String, timeoutMs: Long): String = try {
        PrimeShellRunner.run(listOf("sh", "-c", cmd), timeoutMs, MAX_OUTPUT_BYTES)
    } catch (_: Exception) {
        // Checked callers require their exit sentinel; errors cannot look successful.
        "ERROR: command failed or timed out"
    }

    /** Read one protocol line without allowing an unauthenticated local peer
     * to make BufferedReader allocate an unbounded String before auth runs. */
    private fun readUtf8LineLimited(socket: Socket, maxBytes: Int): String? {
        val input = socket.getInputStream()
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 1024))
        while (out.size() <= maxBytes) {
            val value = input.read()
            if (value == -1) return out.takeIf { it.size() > 0 }?.toString(Charsets.UTF_8.name())
            if (value == '\n'.code) return out.toString(Charsets.UTF_8.name())
            if (value != '\r'.code) out.write(value)
        }
        throw IOException("request exceeds $maxBytes bytes")
    }

    /** Cap shell output so a legitimate but noisy command cannot exhaust the
     * long-lived Prime process. The command is killed once the cap is hit. */
    private fun readProcessOutputLimited(process: Process, maxBytes: Int): String {
        val input = process.inputStream
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var truncated = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val remaining = maxBytes - out.size()
            if (remaining <= 0) {
                truncated = true
                process.destroyForcibly()
                break
            }
            out.write(buffer, 0, minOf(read, remaining))
            if (read > remaining) {
                truncated = true
                process.destroyForcibly()
                break
            }
        }
        return out.toString(Charsets.UTF_8.name()) + if (truncated) "\n[output truncated]" else ""
    }

    private data class ScoreFrame(
        val bytes: ByteArray,
        val topHeight: Int,
        val gap: Int,
        val referenceHeight: Int,
    )

    /**
     * Keep screenshot work out of the app process. Prime captures the display,
     * throws away ~70% of pixels that can never contain the final score, and
     * sends only one small JPEG over loopback. The 960 px ceiling preserves
     * small result-screen anchor text on high-resolution phones while remaining
     * far cheaper than OCR on a full landscape frame. The score reader therefore never
     * ships or OCRs a full display frame during gameplay.
     */
    private fun makeScoreFrame(fullPng: ByteArray): ScoreFrame? {
        val source = BitmapFactory.decodeByteArray(fullPng, 0, fullPng.size) ?: return null
        return makeScoreFrame(source, recycleSource = true)
    }

    private fun makeScoreFrame(source: Bitmap, recycleSource: Boolean = false): ScoreFrame? {
        if (source.width < 400 || source.height < 240) {
            if (recycleSource && !source.isRecycled) source.recycle()
            return null
        }

        var composite: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val left = (source.width * 0.22f).toInt().coerceIn(0, source.width - 2)
            val right = (source.width * 0.78f).toInt().coerceIn(left + 1, source.width)
            val topEnd = (source.height * 0.32f).toInt().coerceIn(1, source.height)
            val bottomStart = (source.height * 0.70f).toInt().coerceIn(0, source.height - 1)
            val rawGap = (source.height / 140).coerceIn(4, 10)
            val rawWidth = right - left
            val rawHeight = topEnd + rawGap + (source.height - bottomStart)

            composite = Bitmap.createBitmap(rawWidth, rawHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite!!)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                source,
                Rect(left, 0, right, topEnd),
                Rect(0, 0, rawWidth, topEnd),
                null,
            )
            canvas.drawBitmap(
                source,
                Rect(left, bottomStart, right, source.height),
                Rect(0, topEnd + rawGap, rawWidth, rawHeight),
                null,
            )

            val scale = if (rawWidth > SCORE_FRAME_MAX_WIDTH) {
                SCORE_FRAME_MAX_WIDTH.toFloat() / rawWidth.toFloat()
            } else 1f
            val frameBitmap: Bitmap
            val topHeight: Int
            val gap: Int
            val referenceHeight: Int
            if (scale < 0.999f) {
                val targetHeight = (rawHeight * scale).toInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(composite!!, SCORE_FRAME_MAX_WIDTH, targetHeight, true)
                frameBitmap = scaled!!
                topHeight = (topEnd * scale).toInt().coerceAtLeast(1)
                gap = (rawGap * scale).toInt().coerceAtLeast(2)
                referenceHeight = (source.height * scale).toInt().coerceAtLeast(1)
            } else {
                frameBitmap = composite!!
                topHeight = topEnd
                gap = rawGap
                referenceHeight = source.height
            }

            val out = java.io.ByteArrayOutputStream(96 * 1024)
            if (!frameBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) return null
            if (out.size() !in 64..MAX_SCORE_FRAME_BYTES) return null
            ScoreFrame(out.toByteArray(), topHeight, gap, referenceHeight)
        } catch (e: Throwable) {
            log("Score frame crop failed: ${e.message}")
            null
        } finally {
            if (scaled != null && scaled !== composite && !scaled!!.isRecycled) scaled!!.recycle()
            if (composite != null && !composite!!.isRecycled) composite!!.recycle()
            if (recycleSource && !source.isRecycled) source.recycle()
        }
    }

    /**
     * Fast privileged display capture. SurfaceControl is hidden from ordinary
     * apps, but Prime runs as the shell UID; SurfaceFlinger explicitly permits
     * shell callers to capture a display. Reflection keeps the main APK free of
     * hidden-API compile dependencies and the existing screencap path remains a
     * safe OEM fallback.
     */
    private fun captureDisplayFast(): Bitmap? = runCatching {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val token = surfaceControl.getMethod("getInternalDisplayToken").invoke(null) ?: return null
        val builderClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
        val ibinderClass = Class.forName("android.os.IBinder")
        val builder = builderClass.getConstructor(ibinderClass).newInstance(token)
        runCatching {
            builderClass.getMethod("setSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(builder, SCORE_FRAME_MAX_WIDTH, 0)
        }
        val args = builderClass.getMethod("build").invoke(builder)
        val capture = surfaceControl.getMethod("captureDisplay", args.javaClass).invoke(null, args) ?: return null
        val hardwareBitmap = capture.javaClass.getMethod("asBitmap").invoke(capture) as? Bitmap ?: return null
        // Prime needs a software bitmap because it immediately crops/compresses
        // the score lanes. Keep the conversion small by asking SurfaceFlinger for
        // at most 960 px wide before this copy.
        val software = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (hardwareBitmap !== software && !hardwareBitmap.isRecycled) hardwareBitmap.recycle()
        software
    }.onFailure { e ->
        log("SurfaceControl score capture unavailable: ${e.javaClass.simpleName}: ${e.message}")
    }.getOrNull()

    private fun captureDisplayFull(): Bitmap? = runCatching {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val token = surfaceControl.getMethod("getInternalDisplayToken").invoke(null) ?: return null
        val builderClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
        val ibinderClass = Class.forName("android.os.IBinder")
        val builder = builderClass.getConstructor(ibinderClass).newInstance(token)
        val args = builderClass.getMethod("build").invoke(builder)
        val capture = surfaceControl.getMethod("captureDisplay", args.javaClass).invoke(null, args) ?: return null
        val hardwareBitmap = capture.javaClass.getMethod("asBitmap").invoke(capture) as? Bitmap ?: return null
        val software = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (hardwareBitmap !== software && !hardwareBitmap.isRecycled) hardwareBitmap.recycle()
        software
    }.onFailure { e ->
        log("SurfaceControl full capture unavailable: ${e.javaClass.simpleName}: ${e.message}")
    }.getOrNull()

    private class FullFrame(val bytes: ByteArray, val width: Int, val height: Int)

    private fun makeFullFrame(source: Bitmap, recycleSource: Boolean = false): FullFrame? {
        if (source.width < 320 || source.height < 180) {
            if (recycleSource && !source.isRecycled) source.recycle()
            return null
        }
        return try {
            val out = java.io.ByteArrayOutputStream(256 * 1024)
            if (!source.compress(Bitmap.CompressFormat.JPEG, 88, out)) return null
            if (out.size() !in 64..FULL_FRAME_MAX_BYTES) return null
            FullFrame(out.toByteArray(), source.width, source.height)
        } catch (e: Throwable) {
            log("Full frame capture failed: ${e.message}")
            null
        } finally {
            if (recycleSource && !source.isRecycled) source.recycle()
        }
    }

    private fun runBinaryCommand(command: List<String>, maxBytes: Int, timeoutMs: Long): ByteArray? {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (e: Exception) {
            log("Binary spawn failed: ${e.message}")
            return null
        }

        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                process.destroyForcibly()
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; name = "prime_binary_watchdog"; start() }

        return try {
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 1024 * 1024))
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = process.inputStream.read(buffer)
                if (n < 0) break
                if (out.size() + n > maxBytes) {
                    process.destroyForcibly()
                    log("Binary command exceeded ${maxBytes}B")
                    return null
                }
                out.write(buffer, 0, n)
            }
            val exit = process.waitFor()
            if (exit != 0 || out.size() < 64) null else out.toByteArray()
        } catch (e: Exception) {
            log("Binary command failed: ${e.message}")
            null
        } finally {
            watchdog.interrupt()
            runCatching { process.errorStream.close() }
            runCatching { process.inputStream.close() }
        }
    }

    private fun grantManagerSecureSettings() {
        runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("pm", "grant", "com.peerlink.app", "android.permission.WRITE_SECURE_SETTINGS")
            )
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }.onFailure { log("grant WRITE_SECURE_SETTINGS: ${it.message}") }
    }

    private fun setProcessName(name: String) {
        try {
            android.os.Process::class.java.getMethod("setArgV0", String::class.java).invoke(null, name)
        } catch (_: Exception) {}
        Thread.currentThread().name = name
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        if (!PrimeAuth.isValidToken(actual)) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private fun log(msg: String) {
        val line = "[${fmt.format(Date())}][PrimeServer] $msg"
        println(line)
        android.util.Log.i("PrimeServer", msg)
    }
}
