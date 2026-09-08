package com.peerlink.app.godmode.shizuku

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

class PrimeShizukuAdbMdns(
    context: Context, private val serviceType: String,
    private val observer: (Int) -> Unit
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            nsdManager.stopServiceDiscovery(listener)
        }
    }

    private fun onDiscoveryStart() { registered = true }
    private fun onDiscoveryStop() { registered = false }
    private fun onServiceFound(info: NsdServiceInfo) { nsdManager.resolveService(info, ResolveListener(this)) }
    private fun onServiceLost(info: NsdServiceInfo) { if (info.serviceName == serviceName) observer(-1) }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        if (!running) return

        // Validate: resolved host must belong to one of our local interfaces
        val isLocal = try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { iface ->
                    iface.inetAddresses.asSequence()
                        .any { resolvedService.host.hostAddress == it.hostAddress }
                }
        } catch (_: Exception) { false }

        if (!isLocal) return

        // FIX (Bug 1): adbd registers its mDNS record 1-2 seconds BEFORE it finishes
        // binding its TCP port. The original code did a single isPortAvailable() check
        // and silently dropped the result if adbd wasn't ready yet — the latch never
        // fired, causing a 20-second timeout and PAIRED_IDLE on every first attempt.
        //
        // Fix: retry the port-occupied check for up to 3 seconds with 500ms intervals.
        // If the port is still not bound after retries, trust NSD anyway — the local
        // address check above is the meaningful security guard. adbd will be ready by
        // the time we open the ADB TLS socket.
        val port = resolvedService.port
        var portOccupied = isPortOccupied(port)
        if (!portOccupied) {
            Log.d(TAG, "Port $port not yet occupied — retrying (adbd still starting)")
            for (attempt in 1..6) {                    // 6 × 500ms = 3 seconds max
                Thread.sleep(500)
                portOccupied = isPortOccupied(port)
                if (portOccupied) {
                    Log.d(TAG, "Port $port occupied after $attempt retry(ies)")
                    break
                }
            }
            if (!portOccupied) {
                // adbd still not bound after 3s. Proceed anyway — we validated the local
                // address. The ADB client will retry its own connection if needed.
                Log.w(TAG, "Port $port still not occupied after retries — proceeding anyway")
            }
        }

        if (running) {
            serviceName = resolvedService.serviceName
            observer(port)
        }
    }

    /**
     * Returns true when the port is OCCUPIED (adbd is listening).
     * Attempts a bind on 127.0.0.1:port — if bind SUCCEEDS the port is free (adbd absent),
     * if bind FAILS the port is taken (adbd present).
     *
     * Named "isPortOccupied" to make the semantics unambiguous.
     * The original "isPortAvailable" name was the inverse of its return value, which was
     * a source of confusion (it returned true when the port was NOT available / was in use).
     */
    private fun isPortOccupied(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false   // bind succeeded → port is free → adbd NOT listening
        }
    } catch (_: IOException) {
        true        // bind failed   → port is taken → adbd IS listening
    }

    internal class DiscoveryListener(private val adbMdns: PrimeShizukuAdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart()
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop()
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(serviceInfo)
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: PrimeShizukuAdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) { adbMdns.onServiceResolved(nsdServiceInfo) }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "PrimeShizukuAdbMdns"
    }
}
