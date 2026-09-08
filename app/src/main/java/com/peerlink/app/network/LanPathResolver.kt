package com.peerlink.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Resolves the exact LAN path that can reach a PeerLink peer.
 *
 * Hotspot owners are commonly multi-homed (cellular + SoftAP). Selecting the
 * first RFC1918 address can therefore return a cellular 10.x address even
 * though the peer lives on ap0/wlan1. This resolver ranks exact same-subnet
 * paths, prefers Android Wi-Fi Networks for station clients, and falls back to
 * the kernel's own route decision for OEM SoftAP implementations that do not
 * publish the hotspot as a ConnectivityManager Network.
 */
data class LanPath(
    val localIp: String,
    val interfaceName: String,
    val interfaceIndex: Int,
    val prefixLength: Int,
    val androidNetwork: Network? = null,
    val isWifiTransport: Boolean = false,
) {
    val isHotspotOwnerStyle: Boolean get() = androidNetwork == null
}

object LanPathResolver {

    fun resolveForPeer(context: Context, peerIpText: String): LanPath? {
        val peer = runCatching { InetAddress.getByName(peerIpText) as? Inet4Address }
            .getOrNull() ?: return null
        val candidates = collectCandidates(context, requirePrivate = false)
            .filter { it.path.isWifiTransport || isWifiLikeInterface(it.path.interfaceName) }
            .filter { sameSubnet(it.pathAddress, peer, it.path.prefixLength) }
            .map { candidate ->
                var score = candidate.score
                if (candidate.path.androidNetwork != null && candidate.path.isWifiTransport) score += 10_000
                candidate.copy(score = score)
            }
            .toMutableList()

        // Some OEMs expose the SoftAP with incomplete/odd prefix metadata. Ask
        // the kernel which source address it would use to reach the peer. UDP
        // connect() does not send a datagram, so this is a side-effect-free
        // route lookup while the VPN is not yet active.
        resolveViaKernelRoute(peer)?.let { routed ->
            if (!routed.isWifiTransport && !isWifiLikeInterface(routed.interfaceName)) return@let
            val existing = candidates.any { it.path.localIp == routed.localIp }
            candidates += ScoredPath(routed, if (existing) 15_000 else 12_000 + interfaceScore(routed.interfaceName), routedAddress(routed))
        }

        // Same address can be visible from both NetworkInterface and
        // ConnectivityManager. Prefer the version carrying a real Android
        // Network because Network.bindSocket() is the strongest station-mode bind.
        return candidates
            .groupBy { it.path.localIp }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.score } }
            .maxByOrNull { it.score }
            ?.path
    }

    // Best Wi-Fi / hotspot address to advertise before a peer is known.
    // PeerLink discovery is LAN-only. Cellular interfaces can also carry RFC1918
    // addresses, so ranking them lower is not enough: if the SoftAP interface
    // appears a moment later, discovery can otherwise bind to cellular and stay
    // there. Reject non-Wi-Fi paths and let the caller retry until the real LAN
    // interface becomes visible.
    fun bestDiscoveryPath(context: Context): LanPath? =
        collectCandidates(context, requirePrivate = true)
            .filter { candidate ->
                candidate.path.isWifiTransport || isWifiLikeInterface(candidate.path.interfaceName)
            }
            .maxByOrNull { it.score }
            ?.path

    /** Validate the saved source address against the live interface, not an old index alone. */
    fun isStillAvailable(path: LanPath): Boolean = runCatching {
        val nif = NetworkInterface.getByName(path.interfaceName) ?: return@runCatching false
        nif.index == path.interfaceIndex && isUsableInterface(nif) &&
            nif.interfaceAddresses.any { it.address is Inet4Address && it.address.hostAddress == path.localIp }
    }.getOrDefault(false)

    private data class ScoredPath(
        val path: LanPath,
        val score: Int,
        val pathAddress: Inet4Address,
    )

    private fun collectCandidates(context: Context, requirePrivate: Boolean): List<ScoredPath> {
        val result = ArrayList<ScoredPath>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
                val iface = lp.interfaceName.orEmpty()
                if (isCellularInterface(iface)) continue
                for (la in lp.linkAddresses) {
                    val local = la.address as? Inet4Address ?: continue
                    if (!isUsableLocal(local) || (requirePrivate && !isPrivateIpv4(local))) continue
                    val prefix = la.prefixLength
                    if (prefix !in 1..32) continue
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    var score = interfaceScore(iface)
                    if (wifi) score += 10_000
                    if (network == cm.activeNetwork) score += 500
                    result += ScoredPath(
                        path = LanPath(
                            localIp = local.hostAddress ?: continue,
                            interfaceName = iface,
                            interfaceIndex = networkInterfaceIndex(iface),
                            prefixLength = prefix,
                            androidNetwork = network,
                            isWifiTransport = wifi,
                        ),
                        score = score,
                        pathAddress = local,
                    )
                }
            }
        }

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        if (interfaces != null) {
            for (nif in interfaces.toList()) {
                if (!isUsableInterface(nif)) continue
                for (ia in nif.interfaceAddresses) {
                    val local = ia.address as? Inet4Address ?: continue
                    if (!isUsableLocal(local) || (requirePrivate && !isPrivateIpv4(local))) continue
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix !in 1..32) continue
                    result += ScoredPath(
                        path = LanPath(
                            localIp = local.hostAddress ?: continue,
                            interfaceName = nif.name.orEmpty(),
                            interfaceIndex = nif.index,
                            prefixLength = prefix,
                            androidNetwork = null,
                            isWifiTransport = isWifiLikeInterface(nif.name),
                        ),
                        score = interfaceScore(nif.name),
                        pathAddress = local,
                    )
                }
            }
        }
        return result
    }

    private fun resolveViaKernelRoute(peer: Inet4Address): LanPath? {
        val local = runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(peer, 9))
                socket.localAddress as? Inet4Address
            }
        }.getOrNull() ?: return null
        if (!isUsableLocal(local)) return null

        val nif = runCatching { NetworkInterface.getByInetAddress(local) }.getOrNull() ?: return null
        if (!isUsableInterface(nif)) return null
        val prefix = nif.interfaceAddresses
            .firstOrNull { it.address == local }
            ?.networkPrefixLength?.toInt()
            ?.takeIf { it in 1..32 }
            ?: 24
        return LanPath(
            localIp = local.hostAddress ?: return null,
            interfaceName = nif.name.orEmpty(),
            interfaceIndex = nif.index,
            prefixLength = prefix,
            androidNetwork = null,
            isWifiTransport = isWifiLikeInterface(nif.name),
        )
    }

    private fun routedAddress(path: LanPath): Inet4Address =
        InetAddress.getByName(path.localIp) as Inet4Address

    private fun networkInterfaceIndex(name: String): Int =
        if (name.isBlank()) 0 else runCatching { NetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)

    private fun isUsableInterface(nif: NetworkInterface): Boolean = runCatching {
        if (!nif.isUp || nif.isLoopback) return@runCatching false
        val n = nif.name.orEmpty().lowercase()
        if (nif.isVirtual && !isWifiLikeInterface(n)) return@runCatching false
        !n.startsWith("tun") && !n.startsWith("tap") && !n.startsWith("ppp") &&
            !n.startsWith("dummy") && !n.contains("vpn") && !isCellularInterface(n)
    }.getOrDefault(false)

    private fun isUsableLocal(address: Inet4Address): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isMulticastAddress

    private fun isPrivateIpv4(address: Inet4Address): Boolean {
        val b = address.address.map { it.toInt() and 0xFF }
        return b[0] == 10 ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 168)
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 1..32) return false
        val av = ipv4ToLong(a)
        val bv = ipv4ToLong(b)
        val mask = if (prefixLength == 32) 0xFFFF_FFFFL
        else (0xFFFF_FFFFL shl (32 - prefixLength)) and 0xFFFF_FFFFL
        return (av and mask) == (bv and mask)
    }

    private fun ipv4ToLong(address: Inet4Address): Long {
        val b = address.address
        return ((b[0].toLong() and 0xFF) shl 24) or
            ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or
            (b[3].toLong() and 0xFF)
    }

    private fun isWifiLikeInterface(name: String?): Boolean {
        val n = name.orEmpty().lowercase()
        return n.startsWith("ap") || n.contains("softap") || n.startsWith("swlan") ||
            n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("p2p")
    }

    private fun isCellularInterface(name: String?): Boolean {
        val n = name.orEmpty().lowercase()
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("wwan") || n.contains("cell") || n.startsWith("v4-rmnet")
    }

    private fun interfaceScore(name: String?): Int {
        val n = name.orEmpty().lowercase()
        return when {
            n.startsWith("ap") || n.contains("softap") || n.startsWith("swlan") -> 8_000
            n.startsWith("wlan") || n.startsWith("wifi") -> 7_000
            n.startsWith("p2p") -> 6_000
            n.startsWith("eth") || n.startsWith("usb") -> 2_000
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
                n.contains("cell") || n.contains("wwan") -> -10_000
            else -> 0
        }
    }
}
