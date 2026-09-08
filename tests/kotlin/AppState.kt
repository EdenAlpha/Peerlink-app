package com.peerlink.app.core

import java.util.concurrent.atomic.AtomicBoolean

// HOST TEST ONLY. Compile this instead of the Android application's AppState.
object AppState {
    var peerFabricatedIp: String = "203.0.113.2"
    val isPaired = AtomicBoolean(true)
    val isRunning = AtomicBoolean(false)
    val tunneled = java.util.concurrent.atomic.AtomicLong()
    val passed = java.util.concurrent.atomic.AtomicLong()
    var connectionMode: String = "unknown"
    val peerIp = java.util.concurrent.atomic.AtomicReference<java.net.InetAddress>()
    val peerFabricatedIpObj = java.util.concurrent.atomic.AtomicReference<java.net.InetAddress>()
    fun appendLog(message: String) {}
    fun getLogs(): String = ""
}
