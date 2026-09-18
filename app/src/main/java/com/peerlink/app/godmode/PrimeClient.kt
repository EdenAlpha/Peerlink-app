package com.peerlink.app.godmode

import android.content.Context
import com.peerlink.app.core.AppState
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/** App-side client for PrimeServer. */
object PrimeClient {

    const val HOST = PrimeServer.HOST
    const val PORT = PrimeServer.PORT
    const val PING_TIMEOUT_MS = 500
    const val CMD_TIMEOUT_MS = 8_000

    @Volatile var protocolVersion: Int = 0
        private set

    fun init(context: Context) = PrimeAuth.init(context)

    private fun request(command: String, timeoutMs: Int): JSONObject? {
        val token = PrimeAuth.tokenOrNull() ?: return null
        return try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(HOST, PORT), minOf(timeoutMs, 2_000))
                val req = JSONObject().apply {
                    put("token", token)
                    put("cmd", command)
                    put("timeoutMs", (timeoutMs - 500).coerceIn(250, 120_000))
                }.toString() + "\n"
                s.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()
                val line = readUtf8LineLimited(s.getInputStream(), 4 * 1024 * 1024) ?: return null
                JSONObject(line)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isAlive(timeoutMs: Int = PING_TIMEOUT_MS): Boolean {
        val response = request("__health__", timeoutMs) ?: return false
        val output = response.optString("output", "")
        val version = output.removePrefix("prime_ok_v").toIntOrNull() ?: 0
        val alive = response.optBoolean("ok", false) && version in 2..6
        protocolVersion = if (alive) version else 0
        return alive
    }

    fun execute(command: String, timeoutMs: Int = CMD_TIMEOUT_MS): String? {
        val response = request(command, timeoutMs)
        return if (response == null || !response.optBoolean("ok", false)) {
            AppState.appendLog("[PRIME-CLIENT] ${commandLabel(command)} FAILED")
            null
        } else {
            val out = response.optString("output", "")
            AppState.appendLog("[PRIME-CLIENT] ${commandLabel(command)} completed (${out.length} chars)")
            out
        }
    }

    /** Internal high-frequency path: same authenticated command protocol, no log I/O. */
    private fun executeQuiet(command: String, timeoutMs: Int): String? {
        val response = request(command, timeoutMs) ?: return null
        if (!response.optBoolean("ok", false)) return null
        return response.optString("output", "")
    }



    data class ScoreCaptureFrame(
        val bytes: ByteArray,
        val topHeight: Int,
        val gap: Int,
        val referenceHeight: Int,
    )

    /**
     * Preferred match-score capture path. Prime discards the pitch/middle of
     * the frame before transfer, so an automatic burst moves only a compact
     * score-bearing JPEG over loopback. Returns null on a pre-v4 resident
     * PrimeServer; callers then use the compatible full-frame path.
     */
    fun captureScoreFrame(timeoutMs: Int = 4_000): ScoreCaptureFrame? {
        val token = PrimeAuth.tokenOrNull() ?: return null
        return try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(HOST, PORT), minOf(timeoutMs, 1_000))
                val req = JSONObject().apply {
                    put("token", token)
                    put("cmd", "__scorecap_jpeg__")
                }.toString() + "\n"
                val output = s.getOutputStream()
                output.write(req.toByteArray(Charsets.UTF_8))
                output.flush()

                val input = s.getInputStream()
                val headerLine = readUtf8LineLimited(input, 8 * 1024) ?: return null
                val header = JSONObject(headerLine)
                if (!header.optBoolean("ok", false) || !header.optBoolean("scoreComposite", false)) return null
                val length = header.optInt("binaryBytes", -1)
                val topHeight = header.optInt("topHeight", -1)
                val gap = header.optInt("gap", -1)
                val referenceHeight = header.optInt("referenceHeight", -1)
                if (length !in 64..(2 * 1024 * 1024) || topHeight <= 0 || gap < 0 || referenceHeight <= 0) return null
                val bytes = readExactly(input, length) ?: return null
                ScoreCaptureFrame(bytes, topHeight, gap, referenceHeight)
            }
        } catch (_: Exception) {
            null
        }
    }

    data class FullCaptureFrame(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * v6 full-display JPEG for the F33 statistics reader. Returns null for a
     * pre-v6 resident PrimeServer; callers then fall back to the composite
     * band capture (score only, no statistics table).
     */
    fun captureFullFrame(timeoutMs: Int = 4_000): FullCaptureFrame? {
        val token = PrimeAuth.tokenOrNull() ?: return null
        return try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(HOST, PORT), minOf(timeoutMs, 1_000))
                val req = JSONObject().apply {
                    put("token", token)
                    put("cmd", "__fullcap_jpeg__")
                }.toString() + "\n"
                val output = s.getOutputStream()
                output.write(req.toByteArray(Charsets.UTF_8))
                output.flush()

                val input = s.getInputStream()
                val headerLine = readUtf8LineLimited(input, 8 * 1024) ?: return null
                val header = JSONObject(headerLine)
                if (!header.optBoolean("ok", false) || !header.optBoolean("fullFrame", false)) return null
                val length = header.optInt("binaryBytes", -1)
                val width = header.optInt("width", -1)
                val height = header.optInt("height", -1)
                if (length !in 64..(4 * 1024 * 1024) || width <= 0 || height <= 0) return null
                val bytes = readExactly(input, length) ?: return null
                FullCaptureFrame(bytes, width, height)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Capture the current display directly from the privileged Prime process.
     * The PNG is streamed over loopback; no storage permission, temporary file,
     * or MediaProjection dialog is involved.
     *
     * Returns null when an older PrimeServer is still resident. The caller can
     * fail closed and ask for Prime recovery rather than hammering the server.
     */
    fun captureScreenPng(timeoutMs: Int = 3_500): ByteArray? {
        val token = PrimeAuth.tokenOrNull() ?: return null
        return try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(HOST, PORT), minOf(timeoutMs, 2_000))
                val req = JSONObject().apply {
                    put("token", token)
                    put("cmd", "__screencap_png__")
                }.toString() + "\n"
                val output = s.getOutputStream()
                output.write(req.toByteArray(Charsets.UTF_8))
                output.flush()

                val input = s.getInputStream()
                val headerLine = readUtf8LineLimited(input, 8 * 1024) ?: return null
                val header = JSONObject(headerLine)
                if (!header.optBoolean("ok", false)) return null
                val length = header.optInt("binaryBytes", -1)
                if (length !in 64..(12 * 1024 * 1024)) return null
                readExactly(input, length)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Compatibility path for an older resident PrimeServer (v2). */
    fun captureScreenPngToPath(path: String, timeoutMs: Int = 3_500): Boolean {
        if (path.isBlank()) return false
        val quoted = shellQuote(path)
        val out = executeQuiet("screencap -p $quoted && chmod 664 $quoted && echo __PL_CAP_OK__", timeoutMs)
            ?: return false
        return out.contains("__PL_CAP_OK__")
    }

    /** Fast manual-FT fraud gate; only called on the user's explicit FT tap. */
    fun isPackageForeground(packageName: String, timeoutMs: Int = 1_500): Boolean? {
        val safe = packageName.replace(Regex("[^A-Za-z0-9._]"), "")
        if (safe.isBlank()) return false
        val out = executeQuiet(
            "dumpsys activity activities | grep -E 'mResumedActivity|[tT]opResumedActivity'",
            timeoutMs,
        ) ?: return null
        return PrimeForegroundParser.isForeground(out, safe)
    }

    private fun readUtf8LineLimited(input: java.io.InputStream, maxBytes: Int): String? {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 1024))
        while (out.size() <= maxBytes) {
            val value = input.read()
            if (value == -1) return out.takeIf { it.size() > 0 }?.toString(Charsets.UTF_8.name())
            if (value == '\n'.code) return out.toString(Charsets.UTF_8.name())
            if (value != '\r'.code) out.write(value)
        }
        return null
    }


    private fun readExactly(input: java.io.InputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) return null
            offset += n
        }
        return bytes
    }

    fun shutdown(): Boolean {
        val response = request("__shutdown__", 1_000) ?: return false
        return response.optBoolean("ok", false) &&
            response.optString("output", "") == "prime_stopping"
    }

    fun connectWifi(ssid: String, password: String): Boolean {
        val out = execute(
            "cmd wifi connect-network ${shellQuote(ssid)} WPA2 ${shellQuote(password)}",
            6_000,
        )
            ?: return false
        val ok = !out.lowercase().contains("error") && !out.lowercase().contains("failed")
        AppState.appendLog("[PRIME-CLIENT] connectWifi '$ssid' ok=$ok")
        return ok
    }

    private fun commandLabel(command: String): String = when {
        command.startsWith("settings ") -> "settings"
        command.startsWith("dumpsys ") -> "dumpsys"
        command.startsWith("am ") -> "activity-manager"
        command.startsWith("cmd wifi ") -> "wifi"
        command == "__health__" -> "health"
        command == "__shutdown__" -> "shutdown"
        else -> command.substringBefore(' ').take(24).ifBlank { "command" }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
