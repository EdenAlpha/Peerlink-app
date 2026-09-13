package com.peerlink.app.godmode

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PrimeAdbKeepalive — holds one authenticated ADB connection open to adbd's
 * wireless-debugging listener for the whole time Prime Mode is active.
 *
 * Why this exists: XOS/Transsion (and some other OEM builds) tear down the
 * wireless-debugging TLS listener shortly after the last mTLS client
 * disconnects, and that teardown also reaps daemons spawned by the ADB
 * session — including the detached PrimeServer. Keeping one quiet stream
 * open prevents the teardown, so the engine survives indefinitely.
 *
 * The connection carries no traffic beyond an occasional empty keepalive
 * stream open/close, which costs nothing but keeps the session registered
 * as active in adbd.
 *
 * Thread-safety: [ensure] and [drop] are guarded; reconnects are serialized
 * by the atomic running flag. A stale connection is detected by libadb's
 * isConnected and transparently re-established with the newest port.
 */
object PrimeAdbKeepalive {
    private const val TAG = "PrimeAdbKeepalive"
    private const val KEEPALIVE_INTERVAL_MS = 8_000L
    private const val KEEPALIVE_SERVICE = "shell:true"

    private val running = AtomicBoolean(false)
    @Volatile private var keepalivePort: Int = 0
    // Mirrors the guardian's endpoint gate: reconnect attempts happen only
    // while adbd is actually advertising (mDNS visible). An invisible
    // endpoint means Wi-Fi is off or wireless debugging was disabled; a
    // blind TCP/mTLS attempt then is exactly the hammer we must avoid.
    @Volatile private var endpointVisible: Boolean = false
    @Volatile private var appContext: Context? = null
    private var worker: Thread? = null

    /** Start (or re-target) the keepalive with the freshest known port. */
    fun ensure(context: Context, port: Int) {
        if (port !in 1..65535) return
        appContext = context.applicationContext
        if (keepalivePort != port) keepalivePort = port
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ loop() }, "prime-adb-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    /** Update only the port; safe from any thread. */
    fun onPortChanged(port: Int) {
        if (port in 1..65535) {
            keepalivePort = port
            endpointVisible = true
        }
    }

    /** The wireless-debugging advertisement disappeared; pause reconnects. */
    fun onEndpointLost() {
        endpointVisible = false
    }

    /** Stop the keepalive when Prime deactivates or the engine is gone. */
    fun drop() {
        if (!running.compareAndSet(true, false)) return
        val w = worker
        worker = null
        w?.interrupt()
        PeerLinkAdbManager.getInstanceQuiet()?.let { mgr ->
            try { mgr.disconnect() } catch (_: Exception) {}
        }
        Log.d(TAG, "keepalive dropped")
    }

    private fun loop() {
        val context = appContext ?: return
        var quietSince = System.currentTimeMillis()
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val port = keepalivePort
                if (port !in 1..65535) {
                    Thread.sleep(2_000L)
                    continue
                }
                val manager = PeerLinkAdbManager.getInstanceQuiet()
                if (manager == null || !manager.isConnected) {
                    // Re-establish the held connection quietly. Never hammer:
                    // one attempt per loop iteration, and only while the
                    // advertisement is actually visible.
                    if (endpointVisible) {
                        PeerLinkAdbManager.resetInstanceQuiet(context)
                        val fresh = PeerLinkAdbManager.getInstanceQuiet()
                        try {
                            if (fresh != null && fresh.connect("127.0.0.1", port)) {
                                Log.d(TAG, "keepalive connection re-established on port $port")
                                quietSince = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "keepalive reconnect deferred: ${e.message}")
                        }
                    }
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                    continue
                }
                // Session is alive: touch a tiny stream so adbd sees activity.
                if (System.currentTimeMillis() - quietSince >= KEEPALIVE_INTERVAL_MS) {
                    try {
                        val s: AdbStream? = manager.openStream(KEEPALIVE_SERVICE)
                        s?.close()
                        quietSince = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.d(TAG, "keepalive touch failed: ${e.message}")
                        // Connection died; loop will reconnect on next pass.
                    }
                }
                Thread.sleep(KEEPALIVE_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
        } finally {
            running.set(false)
        }
    }
}
