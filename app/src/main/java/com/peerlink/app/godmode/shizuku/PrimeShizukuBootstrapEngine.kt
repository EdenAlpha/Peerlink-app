package com.peerlink.app.godmode.shizuku

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.peerlink.app.godmode.AdbConnectClient
import com.peerlink.app.godmode.PeerLinkAdbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
                val ports = Channel<Int>(Channel.CONFLATED)
                val adbMdns = PrimeShizukuAdbMdns(context, PrimeShizukuAdbMdns.TLS_CONNECT) { port ->
                    if (port in 1..65535) ports.trySend(port)
                }
                report(stage)
                adbMdns.start()
                try {
                    val livePort = withTimeoutOrNull(discoverTimeoutMs) { ports.receive() } ?: 0
                    val systemPort = adbTcpPort()
                    val port = when {
                        livePort in 1..65535 -> livePort
                        cachedPort in 1..65535 -> cachedPort
                        systemPort > 0 -> systemPort
                        else -> 0
                    }
                    if (port !in 1..65535) {
                        return@withTimeoutOrNull Result.Failure(
                            "No local ADB endpoint found. Keep Wireless debugging enabled."
                        )
                    }
                    report("Starting with wireless adb in port $port")
                    PeerLinkAdbManager.resetInstance(context)
                    val client = AdbConnectClient(context)
                    try {
                        val connected = try {
                            client.connect("127.0.0.1", port)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: ConnectException) {
                            return@withTimeoutOrNull Result.Failure(
                                "Cannot connect to Wireless debugging port $port",
                                error,
                            )
                        } catch (error: SSLProtocolException) {
                            return@withTimeoutOrNull Result.Failure(
                                "Prime pairing is required before activation",
                                error,
                            )
                        } catch (_: Exception) {
                            false
                        }
                        if (!connected) {
                            return@withTimeoutOrNull Result.Failure(
                                "ADB connection was not accepted. Check Wireless debugging and pairing."
                            )
                        }
                        val output = client.shellCommand(PrimeShizukuStarter.internalCommand(context))
                            ?: return@withTimeoutOrNull Result.Failure(
                                "Prime launcher timed out; retry activation"
                            )
                        if (!output.contains("info: peerlink_starter exit with 0")) {
                            return@withTimeoutOrNull Result.Failure(
                                "Prime launcher timed out; retry activation"
                            )
                        }
                        Result.Success(port)
                    } finally {
                        client.disconnect()
                    }
                } finally {
                    adbMdns.stop()
                    ports.close()
                }
            } ?: Result.Failure("Prime timed out: $stage. Check Wireless debugging and retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.Failure("Prime failed while ${stage.lowercase()}; retry activation")
        }
    }

    private fun adbTcpPort(): Int {
        val service = systemProperty("service.adb.tcp.port")
        if (service > 0) return service
        return systemProperty("persist.adb.tcp.port")
    }

    private fun systemProperty(name: String): Int = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, name, "-1") as String).toInt()
    }.getOrDefault(-1)
}
