package com.peerlink.app.tunnel

import com.peerlink.app.core.AppState
import java.util.concurrent.ConcurrentHashMap

object PacketParser {

    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    const val PORT_STUN_START = 3478
    const val PORT_STUN_END = 3481

    const val STUN_MAGIC_COOKIE = 0x2112A442
    
    private const val MIN_IP_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8

    val learnedStunServerIps: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val learnedStunServerIpv6s: MutableSet<String> = ConcurrentHashMap.newKeySet()
    
    fun clearLearnedStunServers() {
        learnedStunServerIps.clear()
        learnedStunServerIpv6s.clear()
    }
    
    fun hasLearnedStunServers(): Boolean {
        return learnedStunServerIps.isNotEmpty() || learnedStunServerIpv6s.isNotEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPTIMIZED: Mutable ParsedPacket to avoid allocations
    // ═══════════════════════════════════════════════════════════════════
    
    class ParsedPacket {
        var isValid: Boolean = false
        var version: Int = 0
        var headerLength: Int = 0
        var totalLength: Int = 0
        var protocol: Int = 0
        var sourcePort: Int = 0
        var destPort: Int = 0
        var isStun: Boolean = false
        var udpPayloadOffset: Int = 0
        var udpPayloadLength: Int = 0
        
        // Store IP as bytes to avoid String allocation on hot path
        val sourceIpBytes = ByteArray(4)
        val destIpBytes = ByteArray(4)
        
        // Lazily computed strings - only when needed for logging/debugging
        @Volatile private var _sourceIp: String? = null
        @Volatile private var _destIp: String? = null
        
        val sourceIp: String
            get() {
                var s = _sourceIp
                if (s == null) {
                    s = formatIpFromBytes(sourceIpBytes)
                    _sourceIp = s
                }
                return s
            }
        
        val destIp: String
            get() {
                var d = _destIp
                if (d == null) {
                    d = formatIpFromBytes(destIpBytes)
                    _destIp = d
                }
                return d
            }
        
        fun reset() {
            isValid = false
            version = 0
            headerLength = 0
            totalLength = 0
            protocol = 0
            sourcePort = 0
            destPort = 0
            isStun = false
            udpPayloadOffset = 0
            udpPayloadLength = 0
            _sourceIp = null
            _destIp = null
        }
        
        companion object {
            private fun formatIpFromBytes(bytes: ByteArray): String {
                return "${bytes[0].toInt() and 255}.${bytes[1].toInt() and 255}.${bytes[2].toInt() and 255}.${bytes[3].toInt() and 255}"
            }
        }
    }
    
    // ThreadLocal reusable ParsedPacket per thread - eliminates allocation
    private val threadLocalParsedPacket = ThreadLocal.withInitial { ParsedPacket() }
    
    /**
     * OPTIMIZED: Parse into a reusable ParsedPacket (no allocation on hot path)
     */
    fun parse(data: ByteArray, length: Int): ParsedPacket {
        val packet = threadLocalParsedPacket.get()
        packet.reset()
        
        if (length < MIN_IP_HEADER_LEN || length > data.size) return packet
        
        val versionAndIhl = data[0].toInt() and 255
        val version = (versionAndIhl ushr 4) and 0x0F
        val ihl = versionAndIhl and 0x0F
        
        if (version != 4 || ihl < 5) return packet

        val headerLength = ihl * 4
        if (length < headerLength) return packet

        packet.version = version
        packet.headerLength = headerLength
        packet.totalLength = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
        if (packet.totalLength < headerLength || packet.totalLength > length) return packet
        val fragmentFlags = ((data[6].toInt() and 255) shl 8) or (data[7].toInt() and 255)
        // This parser has no IP reassembler. Never interpret a fragment body
        // as UDP ports or return a partly populated packet as valid.
        if ((fragmentFlags and 0x3FFF) != 0) return packet
        packet.protocol = data[9].toInt() and 255
        
        // Copy IP bytes directly - no String allocation
        System.arraycopy(data, 12, packet.sourceIpBytes, 0, 4)
        System.arraycopy(data, 16, packet.destIpBytes, 0, 4)

        // Parse transport layer inline (avoid TransportInfo allocation)
        if (packet.protocol == PROTOCOL_UDP && packet.totalLength < headerLength + UDP_HEADER_LEN) return packet
        if ((packet.protocol == PROTOCOL_TCP || packet.protocol == PROTOCOL_UDP) && packet.totalLength >= headerLength + 4) {
            packet.sourcePort = ((data[headerLength].toInt() and 255) shl 8) or (data[headerLength + 1].toInt() and 255)
            packet.destPort = ((data[headerLength + 2].toInt() and 255) shl 8) or (data[headerLength + 3].toInt() and 255)
            
            if (packet.protocol == PROTOCOL_UDP) {
                packet.udpPayloadOffset = headerLength + UDP_HEADER_LEN
                val udpLength = ((data[headerLength + 4].toInt() and 255) shl 8) or (data[headerLength + 5].toInt() and 255)
                if (udpLength < UDP_HEADER_LEN || udpLength > packet.totalLength - headerLength) return packet
                packet.udpPayloadLength = udpLength - UDP_HEADER_LEN
            }
        }
        
        // Detect STUN
        if (packet.protocol == PROTOCOL_UDP) {
            packet.isStun = detectStun(data, length, packet.udpPayloadOffset, packet.udpPayloadLength)
        }

        packet.isValid = true
        return packet
    }

    private fun detectStun(data: ByteArray, length: Int, offset: Int, payloadLen: Int): Boolean {
        if (payloadLen < 20) return false
        
        val cookieOffset = offset + 4
        if (length < cookieOffset + 4) return false

        val magicCookie = ((data[cookieOffset].toInt() and 255) shl 24) or
                ((data[cookieOffset + 1].toInt() and 255) shl 16) or
                ((data[cookieOffset + 2].toInt() and 255) shl 8) or
                (data[cookieOffset + 3].toInt() and 255)

        return magicCookie == STUN_MAGIC_COOKIE
    }

    enum class PacketAction {
        PASSTHROUGH, TUNNEL, DROP, INTERCEPT
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPTIMIZED: Cached peer fabricated IP bytes for fast comparison
    // ═══════════════════════════════════════════════════════════════════
    
    @Volatile
    private var cachedPeerFabricatedIpBytes: ByteArray? = null
    @Volatile
    private var cachedPeerFabricatedIp: String? = null
    
    fun updatePeerFabricatedIpCache(ip: String) {
        cachedPeerFabricatedIp = ip
        cachedPeerFabricatedIpBytes = ipStringToBytesNoAlloc(ip)
    }
    
    // OPTIMIZED: No split(), no regex, no intermediate objects
    private fun ipStringToBytesNoAlloc(ip: String): ByteArray {
        val result = ByteArray(4)
        var idx = 0
        var octet = 0
        for (i in 0 until ip.length) {
            val c = ip[i]
            if (c == '.') {
                result[idx++] = octet.toByte()
                octet = 0
            } else {
                octet = octet * 10 + (c - '0')
            }
        }
        result[idx] = octet.toByte()
        return result
    }

    /**
     * OPTIMIZED: Uses byte comparison instead of String comparison
     */
    fun getPacketAction(packet: ParsedPacket): PacketAction {
        if (!packet.isValid) return PacketAction.DROP

        // Update cache if needed
        val currentPeerIp = AppState.peerFabricatedIp
        if (cachedPeerFabricatedIp != currentPeerIp) {
            updatePeerFabricatedIpCache(currentPeerIp)
        }

        // RULE 1: TUNNEL - Compare bytes directly (no String allocation)
        val peerBytes = cachedPeerFabricatedIpBytes
        if (peerBytes != null && ipBytesEqual(packet.destIpBytes, peerBytes)) {
            return if (packet.protocol == PROTOCOL_UDP) {
                PacketAction.TUNNEL
            } else {
                PacketAction.DROP
            }
        }

        // RULE 2: INTERCEPT STUN
        if (AppState.isPaired.get() && packet.protocol == PROTOCOL_UDP) {
            if (shouldInterceptStun(packet)) {
                return PacketAction.INTERCEPT
            }
        }

        // RULE 3: PASSTHROUGH
        return PacketAction.PASSTHROUGH
    }
    
    private fun ipBytesEqual(a: ByteArray, b: ByteArray): Boolean {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3]
    }

    private fun shouldInterceptStun(packet: ParsedPacket): Boolean {
        val port = packet.destPort
        
        // Never intercept traffic to private/LAN IPs - OPTIMIZED: no split()
        if (isPrivateIpBytes(packet.destIpBytes)) {
            return false
        }
        
        // For learned server lookup, we need the String (but this is rare - only STUN packets)
        val ip = packet.destIp
        val isLearnedStunServer = learnedStunServerIps.contains(ip)
        
        if (isLearnedStunServer) {
            return port in PORT_STUN_START..PORT_STUN_END || packet.isStun
        }
        
        if (!hasLearnedStunServers() && packet.isStun) {
            return port in PORT_STUN_START..PORT_STUN_END
        }
        
        return false
    }
    
    /**
     * OPTIMIZED: Byte-based private IP check - no String.split() allocation
     */
    fun isPrivateIpBytes(ip: ByteArray): Boolean {
        val b0 = ip[0].toInt() and 255
        val b1 = ip[1].toInt() and 255
        
        // 10.0.0.0/8
        if (b0 == 10) return true
        
        // 192.168.0.0/16
        if (b0 == 192 && b1 == 168) return true
        
        // 172.16.0.0/12
        if (b0 == 172 && b1 in 16..31) return true
        
        // 127.0.0.0/8
        if (b0 == 127) return true
        
        // 169.254.0.0/16
        if (b0 == 169 && b1 == 254) return true
        
        // 100.64.0.0/10 (CGNAT)
        if (b0 == 100 && b1 in 64..127) return true
        
        // 0.0.0.0
        val b2 = ip[2].toInt() and 255
        val b3 = ip[3].toInt() and 255
        if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) return true
        
        // 255.255.255.255
        if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) return true
        
        return false
    }
}
