package com.peerlink.app.godmode.shizuku

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.AdbConnectClient
import com.peerlink.app.godmode.PeerLinkAdbManager
import com.peerlink.app.godmode.PrimeClient
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

class PrimeShizukuBootstrapEngine(private val context: Context) {
    sealed class Result {
        data class Success(val port: Int) : Result()
        data class Failure(val reason: String, val throwable: Throwable? = null) : Result()
    }

    suspend fun start(
        needBootstrap: Boolean,
        cachedPort: Int = 0,
        discoverTimeoutMs: Long = 12_000L,
        onStage: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext Result.Failure("Wireless debugging requires Android 11 or later")
        }
        var stage = "Finding this phone's Wireless debugging connection"
        fun report(value: String) { stage = value; onStage(value) }
        if (needBootstrap) AppState.appendLog("[PRIME-ADB] first-time bootstrap start")
        try {
            withTimeoutOrNull(45_000L) {
                if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED) {
                    runCatching {
                        val cr = context.contentResolver
                        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                    }
                }
                val wifiEnabled = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
                if (!wifiEnabled && cachedPort !in 1..65535) {
                    return@withTimeoutOrNull Result.Failure(
                        "Wireless debugging did not stay enabled (adb_wifi_enabled=0)"
                    )
                }
                val candidate = if (cachedPort in 1..65535) cachedPort else 0
                // Shizuku never trusts a cached port: wireless debugging hands
                // out a NEW random port after every toggle/reboot, and a stale
                // port dead-ends the whole activation. Probe the cached port
                // first; on failure fall through to a fresh mDNS discovery.
                val port = when {
                    candidate > 0 && probeTcpPort(candidate) -> candidate
                    candidate > 0 -> {
                        AppState.appendLog("[PRIME-ADB] cached port $candidate is dead — re-discovering")
                        discoverTlsPort(discoverTimeoutMs).takeIf { it in 1..65535 } ?: candidate
                    }
                    else -> discoverTlsPort(discoverTimeoutMs)
                }
                if (port !in 1..65535) {
                    return@withTimeoutOrNull Result.Failure(
                        "No local ADB endpoint found. Keep Wireless debugging enabled."
                    )
                }
                report("Starting with wireless adb in port $port")
                startOnPort(port)
            } ?: if (engineAlive()) Result.Success(cachedPort) else Result.Failure("Prime timed out: $stage. Check Wireless debugging and retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppState.appendLog("[PRIME-ADB] ${error.javaClass.simpleName}: ${error.message}")
            if (engineAlive()) Result.Success(cachedPort)
            else Result.Failure(describeAdbError(error, stage), error)
        }
    }

    /** Cheap liveness probe: can we open a TCP socket to this local port right now? */
    private fun probeTcpPort(port: Int): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun discoverTlsPort(timeoutMs: Long): Int {
        val ports = Channel<Int>(Channel.CONFLATED)
        val adbMdns = PrimeShizukuAdbMdns(context, PrimeShizukuAdbMdns.TLS_CONNECT) { port ->
            if (port in 1..65535) ports.trySend(port)
        }
        adbMdns.start()
        return try {
            withTimeoutOrNull(timeoutMs) { ports.receive() } ?: 0
        } finally {
            adbMdns.stop()
            ports.close()
        }
    }

    private suspend fun startOnPort(port: Int): Result {
        PeerLinkAdbManager.resetInstance(context)
        val client = AdbConnectClient(context)
        return try {
            try {
                client.connect("127.0.0.1", port)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ConnectException) {
                return Result.Failure("Cannot connect to Wireless debugging port $port: ${error.message}", error)
            } catch (error: SSLProtocolException) {
                return Result.Failure("Prime pairing is required before activation (${error.message})", error)
            } catch (error: AdbPairingRequiredException) {
                return Result.Failure("Prime pairing is required before activation (${error.message})", error)
            }
            AppState.appendLog("[PRIME-ADB] connect 127.0.0.1:$port")
            val outcome = client.runStarter(PrimeShizukuStarter.internalCommand(context))
            val output = outcome.output
            if (output.isNotBlank()) {
                AppState.appendLog("[PRIME-ADB] starter output:\n${output.takeLast(1_500)}")
            }
            val fatal = output.lineSequence().firstOrNull { it.contains("fatal:") }
            when {
                output.contains("info: peerlink_starter exit with 0") || engineAlive() -> Result.Success(port)
                fatal != null -> Result.Failure("Prime starter failed: $fatal")
                outcome.complete && outcome.exitCode != null && outcome.exitCode != 0 ->
                    Result.Failure("Prime starter exited ${outcome.exitCode}")
                else -> Result.Failure(
                    if (output.isBlank()) "Prime launcher produced no starter output"
                    else "Prime starter did not exit cleanly"
                )
            }
        } finally {
            client.disconnect()
        }
    }

    private fun describeAdbError(error: Throwable, stage: String): String = when (error) {
        is ConnectException -> "Cannot connect to Wireless debugging: ${error.message}"
        is SSLProtocolException, is AdbPairingRequiredException ->
            "Prime pairing is required before activation (${error.message})"
        else -> "ADB start failed: ${error.javaClass.simpleName}: ${error.message} ($stage)"
    }

    private suspend fun engineAlive(): Boolean {
        repeat(16) {
            if (PrimeClient.isAlive(timeoutMs = 800)) return true
            delay(250L)
        }
        return false
    }
}
