package com.peerlink.app.godmode

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Resolve only this device's advertisements, then authenticate over loopback. */
class AdbNsdWatcher(context: Context) {
    companion object {
        private const val TAG = "AdbNsdWatcher"
        const val TYPE_PAIR = "_adb-tls-pairing._tcp"
        const val TYPE_CONNECT = "_adb-tls-connect._tcp"
        // Shared across short bootstrap watchers; no thread leaks after every activation.
        private val resolver = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "prime-nsd").apply { isDaemon = true }
        }
    }
    var onPairingPortFound: ((String, Int) -> Unit)? = null
    var onPairingPortLost: (() -> Unit)? = null
    var onConnectPortFound: ((String, Int) -> Unit)? = null
    var onConnectPortLost: (() -> Unit)? = null
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var pairListener: Discovery? = null
    @Volatile private var connectListener: Discovery? = null

    fun start() { startPairingDiscovery(); startConnectDiscovery() }
    @Synchronized fun startPairingDiscovery() {
        if (pairListener != null || nsd == null) return
        val discovery = Discovery(TYPE_PAIR, { port -> onPairingPortFound?.invoke("127.0.0.1", port) }, { onPairingPortLost?.invoke() })
        pairListener = discovery
        begin(discovery)
    }
    @Synchronized fun startConnectDiscovery() {
        if (connectListener != null || nsd == null) return
        val discovery = Discovery(TYPE_CONNECT, { port -> onConnectPortFound?.invoke("127.0.0.1", port) }, { onConnectPortLost?.invoke() })
        connectListener = discovery
        begin(discovery)
    }
    private fun begin(discovery: Discovery) {
        if (multicastLock == null) {
            multicastLock = wifi?.createMulticastLock("PeerLinkAdbNsd")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        try { nsd?.discoverServices(discovery.type, NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (_: Exception) { finished(discovery) }
    }
    @Synchronized private fun finished(discovery: Discovery) {
        discovery.active = false
        if (pairListener === discovery) pairListener = null
        if (connectListener === discovery) connectListener = null
        if (pairListener == null && connectListener == null) {
            runCatching { multicastLock?.release() }
            multicastLock = null
        }
    }
    @Synchronized fun stopPairingDiscovery() { pairListener?.let { stop(it) } }
    @Synchronized fun stopConnectDiscovery() { connectListener?.let { stop(it) } }
    private fun stop(discovery: Discovery) {
        finished(discovery) // Invalidate callbacks before Android acknowledges the stop.
        runCatching { nsd?.stopServiceDiscovery(discovery) }
    }
    fun stop() { stopPairingDiscovery(); stopConnectDiscovery() }

    private fun isLoopbackPortOccupied(port: Int): Boolean = try {
        java.net.ServerSocket().use {
            it.bind(java.net.InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (_: java.io.IOException) {
        // Shizuku AdbMdns.isPortAvailable: bind failure means adbd owns the port.
        true
    }

    private inner class Discovery(
        val type: String,
        val found: (Int) -> Unit,
        val lost: () -> Unit,
    ) : NsdManager.DiscoveryListener {
        @Volatile var active = true
        @Volatile private var selected: String? = null
        private val services = ConcurrentHashMap.newKeySet<String>()
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) { finished(this) }
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            Log.w(TAG, "Discovery failed: $code"); finished(this)
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) { finished(this) }
        override fun onServiceFound(service: NsdServiceInfo) {
            if (!active || !service.serviceType.contains(type) || services.size >= 16) return
            if (services.add(service.serviceName)) resolve(service, 0)
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            services.remove(service.serviceName)
            if (active && selected == service.serviceName) { selected = null; lost() }
        }
        private fun resolve(service: NsdServiceInfo, attempt: Int) {
            if (!active || !services.contains(service.serviceName)) return
            fun retry() {
                if (attempt < 5 && active) resolver.schedule({ resolve(service, attempt + 1) }, 300L, TimeUnit.MILLISECONDS)
                else services.remove(service.serviceName)
            }
            val callback = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) { retry() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (!active || !services.contains(service.serviceName)) return
                    val host = info.host ?: return
                    val local = runCatching {
                        NetworkInterface.getNetworkInterfaces().asSequence().any { iface ->
                            iface.inetAddresses.asSequence().any { it.address.contentEquals(host.address) }
                        }
                    }.getOrDefault(false)
                    // Photocopy of Shizuku AdbMdns: an advertised port is only real
                    // if adbd actually bound it on loopback. This proves the daemon
                    // finished binding (replaces the old fixed 1.5s sleep).
                    if (!local || info.port !in 1..65535) return
                    if (!isLoopbackPortOccupied(info.port)) return
                    selected = service.serviceName
                    found(info.port)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) nsd?.resolveService(service, resolver, callback)
                else {
                    @Suppress("DEPRECATION")
                    nsd?.resolveService(service, callback)
                }
            } catch (_: Exception) { retry() }
        }
    }
}
