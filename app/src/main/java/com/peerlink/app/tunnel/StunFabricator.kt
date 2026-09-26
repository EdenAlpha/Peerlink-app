package com.peerlink.app.tunnel

import com.peerlink.app.core.AppState

/**
 * STUN responses for explicitly supported eFootball server profiles.
 * Unknown profiles, unsupported attributes and native IPv6 server addresses
 * return null so the caller can preserve normal Internet passthrough.
 */
object StunFabricator {

    /**
     * Kill-test toggle (CAPTURE_AUTOPSY §7.3 / VERDICT §3.1).
     * true  = current behavior: answer game STUN locally with 197.x fabricated reflexive.
     * false = passthrough: return null so real STUN goes to Konami (proves/disproves suspect #1 in one match).
     * Flip from Settings or adb; default true so production behavior is unchanged.
     */
    @Volatile var fabricationEnabled: Boolean = true

    // ==================== CONSTANTS ====================
    
    private const val STUN_MAGIC_COOKIE = 0x2112A442
    private val MAGIC_COOKIE_BYTES = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
    
    // Magic cookie bytes as integers for XOR operations (avoids hex literal issues)
    private const val MAGIC_BYTE_0 = 33   // 0x21
    private const val MAGIC_BYTE_1 = 18   // 0x12
    private const val MAGIC_BYTE_2 = 164  // 0xA4
    private const val MAGIC_BYTE_3 = 66   // 0x42
    private const val MAGIC_PORT_XOR = 8466 // 0x2112
    
    // Message Types
    private const val BINDING_REQUEST: Int = 0x0001
    
    // Attribute Types
    private const val ATTR_MAPPED_ADDRESS: Int = 0x0001
    private const val ATTR_CHANGE_REQUEST: Int = 0x0003
    private const val ATTR_XOR_MAPPED_ADDRESS: Int = 0x0020
    private const val ATTR_RESPONSE_PORT: Int = 0x0027
    private const val ATTR_SOFTWARE: Int = 0x8022
    private const val ATTR_FINGERPRINT: Int = 0x8028
    private const val ATTR_RESPONSE_ORIGIN: Int = 0x802B
    private const val ATTR_OTHER_ADDRESS: Int = 0x802C
    private const val ATTR_SIGNALING_A: Int = 0x9090
    private const val ATTR_SIGNALING_B: Int = 0x9091
    private const val ATTR_KONAMI_EXT: Int = 0xF000
    
    // Request Type Classification
    private const val TYPE_KONAMI_BINDING = 1
    private const val TYPE_RESPONSE_PORT = 2
    private const val TYPE_BARE_BINDING = 3
    private const val TYPE_CHANGE_BOTH = 4
    private const val TYPE_CHANGE_IP = 5
    private const val TYPE_CHANGE_PORT = 6
    private const val TYPE_SIGNALING = 7
    
    // Server Clusters (Konami STUN servers)
    private val CLUSTER_1 = ServerCluster(
        primaryA = "54.248.105.235",
        primaryB = "54.150.255.185",
        thirdServer = "52.198.221.89"
    )
    
    private val CLUSTER_2 = ServerCluster(
        primaryA = "18.176.255.15",
        primaryB = "54.199.110.22",
        thirdServer = "3.113.89.160"
    )

    // Verified in the supplied July captures, including F000's third address:
    // 11_46_30 packet 12202; 11_46_34 packet 7567. These are observed profiles,
    // not a promise that Konami will keep using the same servers indefinitely.
    private val CLUSTER_3 = ServerCluster("35.76.243.83", "54.150.169.24", "54.65.202.22")
    private val CLUSTER_4 = ServerCluster("54.64.79.140", "13.230.144.13", "176.34.63.31")
    private val SERVER_CLUSTERS = listOf(CLUSTER_1, CLUSTER_2, CLUSTER_3, CLUSTER_4)
    
    // SOFTWARE string as explicit bytes to avoid encoding issues
    private val SOFTWARE_BYTES = byteArrayOf(
        0x54, 0x75, 0x72, 0x6E, 0x53, 0x65, 0x72, 0x76, 0x65, 0x72,
        0x20,
        0x30, 0x2E, 0x37, 0x2E, 0x32,
        0x2B,
        0x30, 0x33
    )
    
    // A STUN retry must receive a response even if the preceding response was
    // lost. Stateless construction is deterministic; no global transaction-ID
    // suppression that can also collide across sockets or sessions.
    
    // IPv6 STUN ports
    private val IPV6_STUN_PORTS = setOf(3478, 3479, 3480, 3481, 19302, 19303, 19304, 19305, 19306, 19307, 19308, 19309)
    
    // ==================== DATA CLASSES ====================
    
    data class ServerCluster(
        val primaryA: String,
        val primaryB: String,
        val thirdServer: String,
        val primaryPort: Int = 3478,
        val alternatePort: Int = 3479
    )
    
    data class ParsedStunRequest(
        val transactionId: ByteArray,
        val messageLength: Int,
        val attributes: Map<Int, ByteArray>,
        val hasKonamiExt: Boolean,
        val responsePort: Int,
        val changeFlags: Int
    )
    
    data class ResponseSource(
        val spoofIp: String,
        val spoofPort: Int,
        val responseOriginIp: String,
        val responseOriginPort: Int,
        val otherAddressIp: String,
        val otherAddressPort: Int
    )
    
    // ==================== IPv4 STUN ENTRY POINT ====================
    
    /**
     * Fabricates a complete STUN response packet for IPv4.
     * 
     * @param originalPacket Parsed packet metadata
     * @param rawData The raw packet buffer (since ParsedPacket no longer stores it)
     * @param rawDataLength Length of valid data in rawData
     * @param fabricatedIp The IP to report in STUN response
     * @param fabricatedPort The port to report in STUN response
     * @param vpnAddress The VPN address (unused but kept for API compatibility)
     */
    fun fabricateStunResponse(
        originalPacket: PacketParser.ParsedPacket,
        rawData: ByteArray,
        rawDataLength: Int = rawData.size,
        fabricatedIp: String,
        fabricatedPort: Int,
        vpnAddress: String
    ): ByteArray? {
        if (!fabricationEnabled) return null
        try {
            val udpPayloadOffset = originalPacket.udpPayloadOffset
            val udpPayloadLength = originalPacket.udpPayloadLength
            
            if (rawDataLength < 0 || rawDataLength > rawData.size) return null
            if (udpPayloadLength < 20 || udpPayloadOffset < 0) return null
            if (udpPayloadOffset > rawDataLength - udpPayloadLength) return null
            if (fabricatedPort !in 1..65535) return null
            
            val parsed = parseStunRequest(rawData, udpPayloadOffset, udpPayloadLength) ?: return null
            
            val requestType = classifyRequest(parsed)
            val cluster = getCluster(originalPacket.destIp) ?: run {
                AppState.appendLog("[STUN] No verified server profile for ${originalPacket.destIp}; using normal Internet path")
                return null
            }
            if (originalPacket.destPort != cluster.primaryPort && originalPacket.destPort != cluster.alternatePort) return null
            val replyPort = responseDestinationPort(parsed, originalPacket.sourcePort)
            
            val responseSource = determineResponseSource(
                destIp = originalPacket.destIp,
                destPort = originalPacket.destPort,
                parsed = parsed,
                cluster = cluster
            )
            
            val fabIpBytes = ipToBytes(fabricatedIp)
            val stunResponse = buildStunResponse(
                transactionId = parsed.transactionId,
                fabricatedIp = fabIpBytes,
                fabricatedPort = fabricatedPort,
                responseSource = responseSource,
                cluster = cluster,
                includeKonamiExt = parsed.hasKonamiExt
            )
            
            val srcIpBytes = ipToBytes(responseSource.spoofIp)
            
            // Respond to the ACTUAL source IP that sent the request
            val dstIpBytes = ipToBytes(originalPacket.sourceIp)
            
            val typeName = getTypeName(requestType)
            AppState.appendLog("STUN[$typeName]: $fabricatedIp:$fabricatedPort from ${responseSource.spoofIp}:${responseSource.spoofPort} → ${originalPacket.sourceIp}:$replyPort")
            
            return buildUdpIpPacket(
                srcIpBytes = srcIpBytes,
                dstIpBytes = dstIpBytes,
                srcPort = responseSource.spoofPort,
                dstPort = replyPort,
                payload = stunResponse
            )
            
        } catch (e: Exception) {
            AppState.appendLog("STUN error: ${e.message}")
            return null
        }
    }
    
    // ==================== IPv6 STUN ENTRY POINT ====================
    
    /**
     * Check if IPv6 packet is STUN
     */
    fun isIpv6Stun(packet: ByteArray, length: Int): Boolean {
        if (length < 68 || length > packet.size || packet[0].toInt().ushr(4) != 6) return false
        
        val nextHeader = packet[6].toInt() and 255
        if (nextHeader != 17) return false  // Not UDP
        
        // Check destination port (bytes 42-43)
        val dstPort = ((packet[42].toInt() and 255) shl 8) or (packet[43].toInt() and 255)
        
        if (dstPort !in IPV6_STUN_PORTS) return false
        
        // Check magic cookie at UDP payload offset 4 (byte 48 + 4 = 52)
        if (length >= 56) {
            val cookie = ((packet[52].toInt() and 255) shl 24) or
                    ((packet[53].toInt() and 255) shl 16) or
                    ((packet[54].toInt() and 255) shl 8) or
                    (packet[55].toInt() and 255)
            if (cookie == STUN_MAGIC_COOKIE) return true
        }
        
        return false
    }
    
    /**
     * Fabricate STUN response for IPv6 request.
     * Uses SAME STUN logic as IPv4, just different packet wrapper.
     * Extracts client address internally from packet.
     */
    fun fabricateIpv6StunResponse(
        ipv6Packet: ByteArray,
        length: Int,
        fabricatedIpv4: String,
        fabricatedPort: Int
    ): ByteArray? {
        if (!fabricationEnabled) return null
        try {
            if (!isIpv6Stun(ipv6Packet, length)) return null
            val ipPayloadLength = read16(ipv6Packet, 4)
            val udpLength = read16(ipv6Packet, 44)
            if (ipPayloadLength < 28 || ipPayloadLength > length - 40) return null
            if (udpLength < 28 || udpLength > ipPayloadLength) return null
            if (fabricatedPort !in 1..65535) return null
            
            // Extract client address (source of request) and server address (dest of request)
            val clientAddr = ByteArray(16)
            val serverAddr = ByteArray(16)
            System.arraycopy(ipv6Packet, 8, clientAddr, 0, 16)
            System.arraycopy(ipv6Packet, 24, serverAddr, 0, 16)

            // Do not claim a reply came from an unrelated IPv4 server when
            // the actual destination was a native IPv6 address. No verified
            // native IPv6 server profiles are bundled yet.
            if (!(0 until 10).all { serverAddr[it] == 0.toByte() } ||
                serverAddr[10] != 0xFF.toByte() || serverAddr[11] != 0xFF.toByte()) return null
            val serverIpv4 = (12..15).joinToString(".") { (serverAddr[it].toInt() and 255).toString() }
            val cluster = getCluster(serverIpv4) ?: return null
            
            // Extract ports from UDP header (starts at byte 40)
            val srcPort = ((ipv6Packet[40].toInt() and 255) shl 8) or (ipv6Packet[41].toInt() and 255)
            val dstPort = ((ipv6Packet[42].toInt() and 255) shl 8) or (ipv6Packet[43].toInt() and 255)
            
            // UDP payload starts at byte 48
            val udpPayloadOffset = 48
            val udpPayloadLength = udpLength - 8
            
            if (udpPayloadLength < 20) return null
            
            // Parse STUN request - SAME FUNCTION AS IPv4!
            val parsed = parseStunRequest(ipv6Packet, udpPayloadOffset, udpPayloadLength)
                ?: return null
            
            val requestType = classifyRequest(parsed)
            if (dstPort != cluster.primaryPort && dstPort != cluster.alternatePort) return null
            
            val responseSource = determineResponseSource(
                destIp = serverIpv4,
                destPort = dstPort,
                parsed = parsed,
                cluster = cluster
            )
            
            // Build STUN response - SAME FUNCTION AS IPv4!
            val fabIpBytes = ipToBytes(fabricatedIpv4)
            val stunResponse = buildStunResponse(
                transactionId = parsed.transactionId,
                fabricatedIp = fabIpBytes,
                fabricatedPort = fabricatedPort,
                responseSource = responseSource,
                cluster = cluster,
                includeKonamiExt = parsed.hasKonamiExt,
                ipv6Transport = true
            )
            
            val typeName = getTypeName(requestType)
            AppState.appendLog("IPv6 STUN[$typeName]: Fabricating $fabricatedIpv4:$fabricatedPort from ${responseSource.spoofIp}:${responseSource.spoofPort}")
            
            // Build IPv6 packet: server responds to client
            return buildIpv6UdpPacket(
                srcAddr = ipv4MappedAddress(ipToBytes(responseSource.spoofIp)),
                dstAddr = clientAddr,
                srcPort = responseSource.spoofPort,
                dstPort = responseDestinationPort(parsed, srcPort),
                payload = stunResponse
            )
            
        } catch (e: Exception) {
            AppState.appendLog("IPv6 STUN error: ${e.message}")
            return null
        }
    }
    
    private fun getTypeName(type: Int): String = when (type) {
        TYPE_KONAMI_BINDING -> "T1:Konami"
        TYPE_RESPONSE_PORT -> "T2:RespPort"
        TYPE_BARE_BINDING -> "T3:Bare"
        TYPE_CHANGE_BOTH -> "T4:ChgBoth"
        TYPE_CHANGE_IP -> "T5:ChgIP"
        TYPE_CHANGE_PORT -> "T6:ChgPort"
        TYPE_SIGNALING -> "T7:Signal"
        else -> "Unknown"
    }
    
    // ==================== PARSING (SHARED IPv4/IPv6) ====================
    
    private fun parseStunRequest(data: ByteArray, offset: Int, length: Int): ParsedStunRequest? {
        if (offset < 0 || length < 20 || offset > data.size - length) return null
        
        val cookie = read32(data, offset + 4)
        if (cookie != STUN_MAGIC_COOKIE) return null
        
        val msgType = read16(data, offset)
        if (msgType != BINDING_REQUEST) return null
        
        val msgLength = read16(data, offset + 2)
        if ((msgLength and 3) != 0 || msgLength != length - 20) return null
        
        val transactionId = ByteArray(12)
        System.arraycopy(data, offset + 8, transactionId, 0, 12)
        
        val attributes = mutableMapOf<Int, ByteArray>()
        var hasKonamiExt = false
        var responsePort = 0
        var changeFlags = 0
        
        var attrOffset = offset + 20
        val endOffset = offset + 20 + msgLength
        
        while (attrOffset < endOffset) {
            if (endOffset - attrOffset < 4) return null
            val attrType = read16(data, attrOffset)
            val attrLen = read16(data, attrOffset + 2)
            val paddedLen = (attrLen + 3) and 0x7FFFFFFC
            if (paddedLen > endOffset - attrOffset - 4) return null
            // No credentials or padding policy are implemented here. Preserve
            // the real server path for unsupported comprehension-required attrs.
            if (attrType < 0x8000 && attrType != ATTR_CHANGE_REQUEST && attrType != ATTR_RESPONSE_PORT) return null
            if (attrType == ATTR_CHANGE_REQUEST && attrLen != 4) return null
            if (attrType == ATTR_RESPONSE_PORT && attrLen != 4) return null
            if (attrType == ATTR_FINGERPRINT) {
                if (attrLen != 4 || attrOffset + 8 != endOffset) return null
                if (read32(data, attrOffset + 4) != (crc32(data.copyOfRange(offset, attrOffset)) xor 0x5354554E)) return null
            }
            
            val attrValue = ByteArray(attrLen)
            if (attrLen > 0) {
                System.arraycopy(data, attrOffset + 4, attrValue, 0, attrLen)
            }
            // STUN uses the first occurrence of an attribute, not the last.
            if (!attributes.containsKey(attrType)) {
                attributes[attrType] = attrValue
                when (attrType) {
                    ATTR_KONAMI_EXT -> hasKonamiExt = true
                    ATTR_RESPONSE_PORT -> {
                        responsePort = read16(attrValue, 0)
                        if (responsePort == 0) return null
                    }
                    ATTR_CHANGE_REQUEST -> changeFlags = read32(attrValue, 0)
                }
            }
            attrOffset += 4 + paddedLen
        }
        
        return ParsedStunRequest(transactionId, msgLength, attributes, hasKonamiExt, responsePort, changeFlags)
    }
    
    // ==================== CLASSIFICATION (SHARED IPv4/IPv6) ====================
    
    private fun classifyRequest(parsed: ParsedStunRequest): Int {
        if (parsed.attributes.containsKey(ATTR_CHANGE_REQUEST)) {
            return when (parsed.changeFlags and 0x06) {
                0x06 -> TYPE_CHANGE_BOTH
                0x04 -> TYPE_CHANGE_IP
                0x02 -> TYPE_CHANGE_PORT
                else -> TYPE_BARE_BINDING
            }
        }
        if (parsed.attributes.containsKey(ATTR_RESPONSE_PORT)) return TYPE_RESPONSE_PORT
        if (parsed.attributes.containsKey(ATTR_SIGNALING_A) || parsed.attributes.containsKey(ATTR_SIGNALING_B)) return TYPE_SIGNALING
        if (parsed.hasKonamiExt) return TYPE_KONAMI_BINDING
        if (parsed.messageLength == 0) return TYPE_BARE_BINDING
        return TYPE_KONAMI_BINDING
    }
    
    // ==================== SERVER CLUSTERS ====================
    
    private fun getCluster(destIp: String): ServerCluster? = SERVER_CLUSTERS.firstOrNull {
        destIp == it.primaryA || destIp == it.primaryB || destIp == it.thirdServer
    }
    
    private fun getPartnerIp(destIp: String, cluster: ServerCluster): String = when (destIp) {
        cluster.primaryA -> cluster.primaryB
        cluster.primaryB -> cluster.primaryA
        else -> cluster.primaryB
    }
    
    private fun getAlternatePort(port: Int, cluster: ServerCluster): Int =
        if (port == cluster.primaryPort) cluster.alternatePort else cluster.primaryPort

    private fun responseDestinationPort(parsed: ParsedStunRequest, requestSourcePort: Int): Int =
        if (parsed.attributes.containsKey(ATTR_RESPONSE_PORT)) parsed.responsePort else requestSourcePort
    
    // ==================== RESPONSE SOURCE DETERMINATION ====================
    
    private fun determineResponseSource(
        destIp: String,
        destPort: Int,
        parsed: ParsedStunRequest,
        cluster: ServerCluster
    ): ResponseSource {
        val partnerIp = getPartnerIp(destIp, cluster)
        val partnerPort = getAlternatePort(destPort, cluster)
        val sourceIp = if ((parsed.changeFlags and 0x04) != 0) partnerIp else destIp
        val sourcePort = if ((parsed.changeFlags and 0x02) != 0) partnerPort else destPort
        // RFC 5780 table 1: OTHER-ADDRESS always refers to the alternative
        // of the REQUEST destination, regardless of the selected reply source.
        // RESPONSE-PORT is independent and changes only the reply destination.
        return ResponseSource(sourceIp, sourcePort, sourceIp, sourcePort, partnerIp, partnerPort)
    }
    
    // ==================== RESPONSE BUILDING (SHARED IPv4/IPv6) ====================
    
    private fun buildStunResponse(
        transactionId: ByteArray,
        fabricatedIp: ByteArray,
        fabricatedPort: Int,
        responseSource: ResponseSource,
        cluster: ServerCluster,
        includeKonamiExt: Boolean,
        ipv6Transport: Boolean = false
    ): ByteArray {
        val attributes = mutableListOf<ByteArray>()
        
        attributes.add(buildXorMappedAddress(fabricatedIp, fabricatedPort))
        attributes.add(buildSoftware())
        attributes.add(buildMappedAddress(fabricatedIp, fabricatedPort))
        val origin = ipToBytes(responseSource.responseOriginIp)
        val other = ipToBytes(responseSource.otherAddressIp)
        attributes.add(buildAddressAttr(0x802B, if (ipv6Transport) ipv4MappedAddress(origin) else origin, responseSource.responseOriginPort))
        attributes.add(buildAddressAttr(0x802C, if (ipv6Transport) ipv4MappedAddress(other) else other, responseSource.otherAddressPort))
        if (includeKonamiExt) {
            attributes.add(buildKonamiExtension(ipToBytes(cluster.thirdServer)))
        }
        
        var attrLength = attributes.sumOf { it.size }
        val totalAttrLength = attrLength + 8
        
        val header = ByteArray(20)
        header[0] = 0x01; header[1] = 0x01
        header[2] = ((totalAttrLength shr 8) and 255).toByte()
        header[3] = (totalAttrLength and 255).toByte()
        System.arraycopy(MAGIC_COOKIE_BYTES, 0, header, 4, 4)
        System.arraycopy(transactionId, 0, header, 8, 12)
        
        val preFingerprint = ByteArray(20 + attrLength)
        System.arraycopy(header, 0, preFingerprint, 0, 20)
        var offset = 20
        for (attr in attributes) {
            System.arraycopy(attr, 0, preFingerprint, offset, attr.size)
            offset += attr.size
        }
        
        val fingerprint = buildFingerprint(preFingerprint)
        
        val response = ByteArray(preFingerprint.size + fingerprint.size)
        System.arraycopy(preFingerprint, 0, response, 0, preFingerprint.size)
        System.arraycopy(fingerprint, 0, response, preFingerprint.size, fingerprint.size)
        
        return response
    }
    
    // ==================== ATTRIBUTE BUILDERS ====================
    
    private fun buildXorMappedAddress(ip: ByteArray, port: Int): ByteArray {
        val attr = ByteArray(12)
        
        attr[0] = 0x00
        attr[1] = 0x20
        attr[2] = 0x00
        attr[3] = 0x08
        attr[4] = 0x00
        attr[5] = 0x01
        
        val xorPort = port xor MAGIC_PORT_XOR
        attr[6] = ((xorPort ushr 8) and 255).toByte()
        attr[7] = (xorPort and 255).toByte()
        
        val ip0 = ip[0].toInt() and 255
        val ip1 = ip[1].toInt() and 255
        val ip2 = ip[2].toInt() and 255
        val ip3 = ip[3].toInt() and 255
        
        attr[8] = (ip0 xor MAGIC_BYTE_0).toByte()
        attr[9] = (ip1 xor MAGIC_BYTE_1).toByte()
        attr[10] = (ip2 xor MAGIC_BYTE_2).toByte()
        attr[11] = (ip3 xor MAGIC_BYTE_3).toByte()
        
        return attr
    }
    
    private fun buildSoftware(): ByteArray {
        val strLen = SOFTWARE_BYTES.size
        val paddedLen = ((strLen + 3) / 4) * 4
        val attr = ByteArray(4 + paddedLen)
        
        attr[0] = 0x80.toByte()
        attr[1] = 0x22
        attr[2] = ((strLen shr 8) and 255).toByte()
        attr[3] = (strLen and 255).toByte()
        System.arraycopy(SOFTWARE_BYTES, 0, attr, 4, strLen)
        
        return attr
    }
    
    private fun buildMappedAddress(ip: ByteArray, port: Int): ByteArray {
        val attr = ByteArray(12)
        attr[0] = 0x00; attr[1] = 0x01; attr[2] = 0x00; attr[3] = 0x08
        attr[4] = 0x00; attr[5] = 0x01
        attr[6] = ((port ushr 8) and 255).toByte()
        attr[7] = (port and 255).toByte()
        System.arraycopy(ip, 0, attr, 8, 4)
        return attr
    }
    
    private fun buildAddressAttr(type: Int, ip: ByteArray, port: Int): ByteArray {
        require(ip.size == 4 || ip.size == 16)
        val attr = ByteArray(8 + ip.size)
        attr[0] = ((type ushr 8) and 255).toByte()
        attr[1] = (type and 255).toByte()
        attr[2] = 0x00; attr[3] = (4 + ip.size).toByte(); attr[4] = 0x00
        attr[5] = (if (ip.size == 4) 0x01 else 0x02).toByte()
        attr[6] = ((port ushr 8) and 255).toByte()
        attr[7] = (port and 255).toByte()
        System.arraycopy(ip, 0, attr, 8, ip.size)
        return attr
    }
    
    private fun buildKonamiExtension(thirdServerIp: ByteArray): ByteArray {
        val attr = ByteArray(20)
        attr[0] = 0xF0.toByte(); attr[1] = 0x00; attr[2] = 0x00; attr[3] = 0x10
        attr[4] = 0x05; attr[5] = 0x73; attr[6] = 0x00; attr[7] = 0x03
        attr[8] = 0x00; attr[9] = 0x00; attr[10] = 0x00; attr[11] = 0x03
        attr[12] = 0x00; attr[13] = 0x01; attr[14] = 0x0D; attr[15] = 0x97.toByte()
        System.arraycopy(thirdServerIp, 0, attr, 16, 4)
        return attr
    }
    
    private fun buildFingerprint(message: ByteArray): ByteArray {
        val crc = crc32(message)
        val fp = crc xor 0x5354554E
        val attr = ByteArray(8)
        attr[0] = 0x80.toByte(); attr[1] = 0x28; attr[2] = 0x00; attr[3] = 0x04
        attr[4] = ((fp ushr 24) and 255).toByte()
        attr[5] = ((fp ushr 16) and 255).toByte()
        attr[6] = ((fp ushr 8) and 255).toByte()
        attr[7] = (fp and 255).toByte()
        return attr
    }
    
    // ==================== CRC32 ====================
    
    private val crcTable: IntArray by lazy {
        IntArray(256) { n ->
            var c = n
            repeat(8) { c = if ((c and 1) != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
            c
        }
    }
    
    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) crc = crcTable[(crc xor (b.toInt() and 255)) and 255] xor (crc ushr 8)
        return crc.inv()
    }
    
    // ==================== IPv4 PACKET BUILDING ====================
    
    private fun buildUdpIpPacket(srcIpBytes: ByteArray, dstIpBytes: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        
        p[0] = 0x45; p[1] = 0x00
        p[2] = ((total ushr 8) and 255).toByte(); p[3] = (total and 255).toByte()
        p[6] = 0x40; p[8] = 0x40; p[9] = 17
        System.arraycopy(srcIpBytes, 0, p, 12, 4)
        System.arraycopy(dstIpBytes, 0, p, 16, 4)
        
        p[20] = ((srcPort ushr 8) and 255).toByte(); p[21] = (srcPort and 255).toByte()
        p[22] = ((dstPort ushr 8) and 255).toByte(); p[23] = (dstPort and 255).toByte()
        p[24] = ((udpLen ushr 8) and 255).toByte(); p[25] = (udpLen and 255).toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        
        val ipCs = checksum(p, 0, 20)
        p[10] = ((ipCs ushr 8) and 255).toByte(); p[11] = (ipCs and 255).toByte()
        
        val udpCs = udpChecksum(srcIpBytes, dstIpBytes, p, 20, udpLen)
        p[26] = ((udpCs ushr 8) and 255).toByte(); p[27] = (udpCs and 255).toByte()
        
        return p
    }
    
    // ==================== IPv6 PACKET BUILDING ====================
    
    private fun buildIpv6UdpPacket(
        srcAddr: ByteArray,
        dstAddr: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 40 + udpLen
        val p = ByteArray(total)
        
        // IPv6 Header
        p[0] = 0x60
        p[1] = 0x00
        p[2] = 0x00
        p[3] = 0x00
        
        // Payload Length
        p[4] = ((udpLen ushr 8) and 255).toByte()
        p[5] = (udpLen and 255).toByte()
        
        // Next Header = UDP (17)
        p[6] = 17
        
        // Hop Limit
        p[7] = 64
        
        // Source Address (16 bytes)
        System.arraycopy(srcAddr, 0, p, 8, 16)
        
        // Destination Address (16 bytes)
        System.arraycopy(dstAddr, 0, p, 24, 16)
        
        // UDP Header
        p[40] = ((srcPort ushr 8) and 255).toByte()
        p[41] = (srcPort and 255).toByte()
        p[42] = ((dstPort ushr 8) and 255).toByte()
        p[43] = (dstPort and 255).toByte()
        p[44] = ((udpLen ushr 8) and 255).toByte()
        p[45] = (udpLen and 255).toByte()
        p[46] = 0x00
        p[47] = 0x00
        
        // Payload
        System.arraycopy(payload, 0, p, 48, payload.size)
        
        // UDP Checksum
        val udpCs = ipv6UdpChecksum(p, 40, udpLen)
        p[46] = ((udpCs ushr 8) and 255).toByte()
        p[47] = (udpCs and 255).toByte()
        
        return p
    }
    
    private fun ipv6UdpChecksum(packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum: Long = 0
        
        // Source address (bytes 8-23)
        for (i in 8..22 step 2) {
            sum += ((packet[i].toInt() and 255) shl 8) or (packet[i + 1].toInt() and 255)
        }
        
        // Destination address (bytes 24-39)
        for (i in 24..38 step 2) {
            sum += ((packet[i].toInt() and 255) shl 8) or (packet[i + 1].toInt() and 255)
        }
        
        // UDP length + next header
        sum += udpLen.toLong()
        sum += 17L
        
        // UDP data
        var i = 0
        while (i < udpLen - 1) {
            sum += ((packet[udpOffset + i].toInt() and 255) shl 8) or (packet[udpOffset + i + 1].toInt() and 255)
            i += 2
        }
        if (i < udpLen) {
            sum += (packet[udpOffset + i].toInt() and 255) shl 8
        }
        
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        
        val cs = sum.inv().toInt() and 0xFFFF
        return if (cs == 0) 0xFFFF else cs
    }
    
    // ==================== UTILITIES ====================
    
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum: Long = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 255) shl 8) or (data[i + 1].toInt() and 255)
            i += 2
        }
        if (i < offset + length) sum += (data[i].toInt() and 255) shl 8
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv().toInt() and 0xFFFF)
    }
    
    private fun udpChecksum(srcIp: ByteArray, dstIp: ByteArray, pkt: ByteArray, off: Int, len: Int): Int {
        var sum: Long = 0
        sum += ((srcIp[0].toInt() and 255) shl 8) or (srcIp[1].toInt() and 255)
        sum += ((srcIp[2].toInt() and 255) shl 8) or (srcIp[3].toInt() and 255)
        sum += ((dstIp[0].toInt() and 255) shl 8) or (dstIp[1].toInt() and 255)
        sum += ((dstIp[2].toInt() and 255) shl 8) or (dstIp[3].toInt() and 255)
        sum += 17; sum += len
        var i = 0
        while (i < len - 1) { sum += ((pkt[off + i].toInt() and 255) shl 8) or (pkt[off + i + 1].toInt() and 255); i += 2 }
        if (i < len) sum += (pkt[off + i].toInt() and 255) shl 8
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val cs = sum.inv().toInt() and 0xFFFF
        return if (cs == 0) 0xFFFF else cs
    }
    
    private fun read16(data: ByteArray, offset: Int): Int = 
        ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)
    
    private fun read32(data: ByteArray, offset: Int): Int = 
        ((data[offset].toInt() and 255) shl 24) or ((data[offset + 1].toInt() and 255) shl 16) or
        ((data[offset + 2].toInt() and 255) shl 8) or (data[offset + 3].toInt() and 255)
    
    private fun ipToBytes(ip: String): ByteArray {
        val octets = ip.split(".").map { it.toInt() }
        require(octets.size == 4 && octets.all { it in 0..255 })
        return octets.map { it.toByte() }.toByteArray()
    }

    private fun ipv4MappedAddress(ip: ByteArray): ByteArray = ByteArray(16).also {
        it[10] = 0xFF.toByte(); it[11] = 0xFF.toByte()
        System.arraycopy(ip, 0, it, 12, 4)
    }
    
    // ==================== TESTING INTERFACE ====================
    
    data class TestResult(val success: Boolean, val info: String, val responseHex: String)
    
    fun testFabricate(hexDump: String, destIp: String, destPort: Int, fabricatedIp: String, fabricatedPort: Int): TestResult {
        try {
            val cleanHex = hexDump.lines().joinToString("") { line ->
                val doubleSpaceIdx = line.lastIndexOf("  ")
                val hexPart = if (doubleSpaceIdx > 0) line.substring(0, doubleSpaceIdx) else line
                hexPart.replace(Regex("[^0-9a-fA-F]"), "")
            }.lowercase()
            
            if (cleanHex.length < 40) return TestResult(false, "Hex too short: ${cleanHex.length / 2} bytes (need 20+)", "")
            
            val bytes = ByteArray(cleanHex.length / 2) { i -> cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            
            val parsed = parseStunRequest(bytes, 0, bytes.size) 
                ?: return TestResult(false, "Failed to parse STUN request", "")
            
            val requestType = classifyRequest(parsed)
            val typeName = getTypeName(requestType)
            val cluster = getCluster(destIp) ?: return TestResult(false, "No verified server profile for $destIp", "")
            if (destPort != cluster.primaryPort && destPort != cluster.alternatePort) return TestResult(false, "Unsupported server port", "")
            val responseSource = determineResponseSource(destIp, destPort, parsed, cluster)
            
            val fabIpBytes = ipToBytes(fabricatedIp)
            val response = buildStunResponse(parsed.transactionId, fabIpBytes, fabricatedPort, responseSource, cluster, parsed.hasKonamiExt)
            
            val xorPort = fabricatedPort xor MAGIC_PORT_XOR
            val xorIp2 = (fabIpBytes[2].toInt() and 255) xor MAGIC_BYTE_2
            
            val info = buildString {
                appendLine("═══ REQUEST ═══")
                appendLine("Type: $requestType ($typeName)")
                appendLine("TxID: ${parsed.transactionId.joinToString("") { "%02x".format(it) }}")
                appendLine("Has Konami Ext: ${parsed.hasKonamiExt}")
                if (parsed.changeFlags > 0) appendLine("Change Flags: 0x${parsed.changeFlags.toString(16).padStart(2, '0')}")
                if (parsed.responsePort > 0) appendLine("Response Port: ${parsed.responsePort}")
                appendLine()
                appendLine("═══ RESPONSE ═══")
                appendLine("From: ${responseSource.spoofIp}:${responseSource.spoofPort}")
                appendLine("XOR-MAPPED: $fabricatedIp:$fabricatedPort")
                appendLine("  Port XOR: $fabricatedPort xor $MAGIC_PORT_XOR = 0x${xorPort.toString(16)}")
                appendLine("  IP[2] XOR: ${fabIpBytes[2].toInt() and 255} xor $MAGIC_BYTE_2 = 0x${xorIp2.toString(16)}")
                appendLine("RESPONSE-ORIGIN: ${responseSource.responseOriginIp}:${responseSource.responseOriginPort}")
                appendLine("OTHER-ADDRESS: ${responseSource.otherAddressIp}:${responseSource.otherAddressPort}")
                appendLine("Size: ${response.size} bytes")
            }
            
            val responseHex = response.joinToString(" ") { "%02x".format(it) }
            return TestResult(true, info, responseHex)
            
        } catch (e: Exception) {
            return TestResult(false, "Error: ${e.message}", "")
        }
    }
}
