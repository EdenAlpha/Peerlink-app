package com.peerlink.app.tunnel

import android.content.Context
import android.os.ParcelFileDescriptor
import java.net.DatagramSocket
import java.net.Socket

/**
 * Temporary bridge that keeps the existing Kotlin TCP/UDP passthrough stack alive
 * while the real TUN and peer tunnel path are owned by the native backend.
 */
class PassthroughBridgeEngine(
    context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    protectDatagramSocket: (DatagramSocket) -> Boolean,
    protectTcpSocket: (Socket) -> Boolean,
) {
    private val engine = TunnelEngine(
        context = context,
        vpnInterface = vpnInterface,
        protectDatagramSocket = protectDatagramSocket,
        protectTcpSocket = protectTcpSocket,
        bridgeMode = true,
    )

    fun start() {
        engine.start()
    }

    fun stop() {
        engine.stop()
        runCatching { vpnInterface.close() }
    }
}
