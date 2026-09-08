package com.peerlink.app.godmode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A single authenticated ADB connection, with bounded, cancellable operations. */
class AdbConnectClient(context: Context) {
    private val manager = PeerLinkAdbManager.getInstance(context)

    suspend fun connect(host: String, port: Int): Boolean =
        withTimeoutOrNull(8_000L) {
            runInterruptible(Dispatchers.IO) {
                // libadb may return false without throwing (e.g. handshake timeout).
                manager.connect(host, port) && manager.isConnected
            }
        } ?: false

    suspend fun shell(command: String): String? = withTimeoutOrNull(8_000L) {
        runInterruptible(Dispatchers.IO) {
            val marker = "__PL_ADB_${UUID.randomUUID().toString().replace("-", "")}__"
            val wrapped = "( $command ); __pl_rc=\$?; printf '\\n${marker}%s\\n' \"${'$'}__pl_rc\""
            manager.openStream("shell:$wrapped").use { stream ->
                PrimeAdbShellReader.read(stream.openInputStream().reader(), marker)
            }
        }
    }

    fun disconnect() {
        // Close this client's manager, never a newer singleton or its stored key.
        try { manager.disconnect() } catch (_: Exception) {
            Log.w("AdbConnectClient", "ADB connection cleanup failed")
        }
    }
}
