package com.peerlink.app.godmode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** A single authenticated ADB connection, with bounded, cancellable operations. */
class AdbConnectClient(context: Context) {
    private val manager = PeerLinkAdbManager.getInstance(context)

    suspend fun connect(host: String, port: Int) {
        withTimeout(8_000L) {
            runInterruptible(Dispatchers.IO) {
                if (!manager.connect(host, port) || !manager.isConnected) {
                    throw java.io.IOException("ADB handshake was not accepted on $host:$port")
                }
            }
        }
    }

    suspend fun shell(command: String): String? = withTimeoutOrNull(8_000L) {
        runInterruptible(Dispatchers.IO) {
            val marker = "__PL_ADB_${UUID.randomUUID().toString().replace("-", "")}__"
            val wrapped = "( $command ); __pl_rc=\$?; printf '\\n${marker}%s\\n' \"${'$'}__pl_rc\""
            manager.openStream("shell:$wrapped").use { stream ->
                PrimeAdbShellReader.read(stream.openInputStream().reader(), marker)
            }
        }
    }

    suspend fun runStarter(command: String): PrimeAdbShellReader.Outcome {
        val marker = "__PL_ADB_${UUID.randomUUID().toString().replace("-", "")}__"
        val wrapped = "( $command ); __pl_rc=\$?; printf '\\n${marker}%s\\n' \"${'$'}__pl_rc\""
        val sink = StringBuilder()
        val outcome = AtomicReference<PrimeAdbShellReader.Outcome?>(null)
        return try {
            withTimeout(15_000L) {
                runInterruptible(Dispatchers.IO) {
                    manager.openStream("shell:$wrapped").use { stream ->
                        PrimeAdbShellReader.readOutcome(stream.openInputStream().reader(), marker, sink = sink)
                            .also { outcome.set(it) }
                    }
                }
            }
        } catch (_: Exception) {
            outcome.get() ?: PrimeAdbShellReader.Outcome(sink.toString().trim(), null, complete = false)
        }
    }

    fun disconnect() {
        try { manager.disconnect() } catch (_: Exception) {
            Log.w("AdbConnectClient", "ADB connection cleanup failed")
        }
    }
}
