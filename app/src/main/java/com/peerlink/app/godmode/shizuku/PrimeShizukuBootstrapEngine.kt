package com.peerlink.app.godmode.shizuku

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.peerlink.app.godmode.AdbConnectClient
import com.peerlink.app.godmode.AdbNsdWatcher
import com.peerlink.app.godmode.PeerLinkAdbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PrimeShizukuBootstrapEngine(private val context: Context) {
    sealed class Result {
        data class Success(val port: Int) : Result()
        data class Failure(val reason: String, val throwable: Throwable? = null) : Result()
    }

    suspend fun start(
        needBootstrap: Boolean,
        cachedPort: Int = 0,
        onStage: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext Result.Failure("Wireless debugging requires Android 11 or later")
        }
        var stage = "Finding this phone's Wireless debugging connection"
        fun report(value: String) { stage = value; onStage(value) }
        try {
            withTimeoutOrNull(45_000L) {
                if (!needBootstrap && context.checkSelfPermission(WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED) {
                    // Request wireless debugging only; pairing still authorizes the connection.
                    runCatching { Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) }
                }
                val ports = Channel<Int>(Channel.CONFLATED)
                val discovery = AdbNsdWatcher(context).apply {
                    onConnectPortFound = { _, port -> ports.trySend(port); Unit }
                }
                report(stage)
                discovery.startConnectDiscovery()
                try {
                    var port = cachedPort
                    var lastFailure = "No local ADB endpoint found. Keep Wireless debugging enabled."
                    repeat(3) { attempt ->
                        if (port !in 1..65535) {
                            report("Finding this phone's Wireless debugging connection")
                            port = withTimeoutOrNull(12_000L) { ports.receive() } ?: 0
                        }
                        if (port !in 1..65535) return@withTimeoutOrNull Result.Failure(lastFailure)
                        report("Connecting to Wireless debugging (attempt ${attempt + 1}/3)")
                        PeerLinkAdbManager.resetInstance(context)
                        val client = AdbConnectClient(context)
                        try {
                            val connected = try { client.connect("127.0.0.1", port) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            if (!connected) {
                                lastFailure = "ADB connection was not accepted. Check Wireless debugging and pairing."
                            } else {
                                if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                                    report("Completing Prime permission setup")
                                    val output = client.shell("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
                                    if (output == null || context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                                        return@withTimeoutOrNull Result.Failure("Android did not grant Prime's settings permission")
                                    }
                                }
                                report("Starting Prime engine")
                                // The launcher contains a per-install token: never log the command.
                                if (client.shell(PrimeShizukuStarter.internalCommand(context)) == null) {
                                    return@withTimeoutOrNull Result.Failure("Prime launcher timed out; retry activation")
                                }
                                return@withTimeoutOrNull Result.Success(port)
                            }
                        } finally {
                            client.disconnect()
                        }
                        // Prefer a fresh advertisement; allow a newly advertised daemon time to bind.
                        port = ports.tryReceive().getOrNull() ?: port
                        delay(600L)
                        if (attempt == 1) port = 0
                    }
                    Result.Failure(lastFailure)
                } finally {
                    discovery.stopConnectDiscovery()
                    ports.close()
                }
            } ?: Result.Failure("Prime timed out: $stage. Check Wireless debugging and retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not expose shell command text or the authentication token through errors.
            Result.Failure("Prime failed while ${stage.lowercase()}; retry activation")
        }
    }
}
