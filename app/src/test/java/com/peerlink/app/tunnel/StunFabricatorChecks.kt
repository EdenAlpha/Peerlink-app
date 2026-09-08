package com.peerlink.app.tunnel

import java.util.zip.CRC32

/** Runs against the real fabricator/parser. No duplicated response generator. */
object StunFabricatorChecks {
    private var assertions = 0
    private fun expect(ok: Boolean, message: String) {
        assertions++
        check(ok) { message }
    }
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()
    private fun w16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    private fun w32(b: ByteArray, o: Int, v: Long) { w16(b, o, (v ushr 16).toInt()); w16(b, o + 2, v.toInt()) }
    private fun ip(s: String) = s.split('.').map { it.toInt().toByte() }.toByteArray()
    private fun mapped(s: String) = ByteArray(16).also { it[10] = -1; it[11] = -1; ip(s).copyInto(it, 12) }
    private fun attr(type: Int, value: ByteArray): ByteArray = ByteArray(4 + ((value.size + 3) / 4) * 4).also {
        w16(it, 0, type); w16(it, 2, value.size); value.copyInto(it, 4)
    }
    private fun intAttr(type: Int, v: Int) = attr(type, ByteArray(4).also { w32(it, 0, v.toLong()) })
    private fun portAttr(v: Int) = attr(0x0027, ByteArray(4).also { w16(it, 0, v) })
    private fun request(vararg attrs: ByteArray): ByteArray {
        val body = attrs.fold(ByteArray(0)) { a, b -> a + b }
        return ByteArray(20 + body.size).also {
            w16(it, 0, 1); w16(it, 2, body.size); w32(it, 4, 0x2112A442)
            for (i in 8..19) it[i] = i.toByte()
            body.copyInto(it, 20)
        }
    }
    private fun packet(payload: ByteArray, server: String = "18.176.255.15", serverPort: Int = 3478,
                       localPort: Int = 56008): ByteArray = ByteArray(28 + payload.size).also {
        it[0] = 0x45; it[8] = 64; it[9] = 17
        w16(it, 2, it.size); ip("10.0.0.2").copyInto(it, 12); ip(server).copyInto(it, 16)
        w16(it, 20, localPort); w16(it, 22, serverPort); w16(it, 24, 8 + payload.size)
        payload.copyInto(it, 28)
    }
    private fun reply(raw: ByteArray): ByteArray? {
        val parsed = PacketParser.parse(raw, raw.size)
        if (!parsed.isValid) return null
        return StunFabricator.fabricateStunResponse(parsed, raw, raw.size, "203.0.113.1", parsed.sourcePort, "10.0.0.2")
    }
    private fun attributes(raw: ByteArray, start: Int): Map<Int, ByteArray> {
        val end = start + 20 + u16(raw, start + 2)
        expect(end == raw.size, "response STUN length must match UDP body")
        val found = linkedMapOf<Int, ByteArray>()
        var pos = start + 20
        while (pos < end) {
            val type = u16(raw, pos); val length = u16(raw, pos + 2)
            expect(pos + 4 + length <= end, "response attribute bounds")
            found[type] = raw.copyOfRange(pos + 4, pos + 4 + length)
            pos += 4 + ((length + 3) / 4) * 4
        }
        expect(pos == end, "response attribute alignment")
        expect(found.keys.last() == 0x8028, "fingerprint must be last")
        val crc = CRC32().also { it.update(raw, start, end - start - 8) }.value xor 0x5354554EL
        expect(u32(raw, end - 4) == crc, "independent CRC32 fingerprint")
        return found
    }
    private fun sum(data: ByteArray, start: Int = 0, length: Int = data.size): Int {
        var total = 0L
        var i = start
        while (i + 1 < start + length) { total += u16(data, i); i += 2 }
        if (i < start + length) total += (data[i].toInt() and 255) shl 8
        while ((total ushr 16) != 0L) total = (total and 65535) + (total ushr 16)
        return total.toInt()
    }
    private fun address(value: ByteArray, expectedIp: ByteArray, port: Int, family: Int = 1) {
        expect(value.size == 4 + expectedIp.size && value[1].toInt() == family, "address family/size")
        expect(u16(value, 2) == port, "address attribute port")
        expect(value.copyOfRange(4, value.size).contentEquals(expectedIp), "address attribute IP")
    }

    fun verifyAll() {
        assertions = 0
        val profiles = listOf(
            Triple("54.248.105.235", "54.150.255.185", "52.198.221.89"),
            Triple("18.176.255.15", "54.199.110.22", "3.113.89.160"),
            Triple("35.76.243.83", "54.150.169.24", "54.65.202.22"),
            Triple("54.64.79.140", "13.230.144.13", "176.34.63.31")
        )
        // Both server addresses, both server ports, every change-bit combination,
        // with/without a response destination different from the request source.
        for (profile in profiles) for (reverse in listOf(false, true)) {
            val server = if (reverse) profile.second else profile.first
            val partner = if (reverse) profile.first else profile.second
            for (serverPort in listOf(3478, 3479)) for (flags in listOf(0, 2, 4, 6)) for (targetPort in listOf(56008, 61000)) {
                val change = intAttr(3, flags)
                val req = if (targetPort == 56008) request(change) else request(change, portAttr(targetPort))
                val response = checkNotNull(reply(packet(req, server, serverPort))) { "Missing response for $server:$serverPort flags=$flags" }
                val expectedSource = if (flags == 4 || flags == 6) partner else server
                val otherPort = if (serverPort == 3478) 3479 else 3478
                val expectedSourcePort = if (flags == 2 || flags == 6) otherPort else serverPort
                expect(response.copyOfRange(12, 16).contentEquals(ip(expectedSource)), "CHANGE_REQUEST source IP")
                expect(response.copyOfRange(16, 20).contentEquals(ip("10.0.0.2")), "reply to original local address")
                expect(u16(response, 20) == expectedSourcePort, "CHANGE_REQUEST source port")
                expect(u16(response, 22) == targetPort, "RESPONSE_PORT changes destination only")
                expect(u16(response, 28) == 0x0101, "Binding success message type")
                expect(response.copyOfRange(36, 48).contentEquals(req.copyOfRange(8, 20)), "transaction ID preserved")
                expect(sum(response, 0, 20) == 65535, "IPv4 header checksum")
                val pseudo = response.copyOfRange(12, 20) + byteArrayOf(0, 17) + response.copyOfRange(24, 26)
                expect(sum(pseudo + response.copyOfRange(20, response.size)) == 65535, "UDP checksum")
                val attrs = attributes(response, 28)
                address(attrs.getValue(0x802B), ip(expectedSource), expectedSourcePort)
                address(attrs.getValue(0x802C), ip(partner), otherPort)
                address(attrs.getValue(1), ip("203.0.113.1"), 56008)
                val xor = attrs.getValue(0x20)
                expect((u16(xor, 2) xor 0x2112) == 56008, "mapped port is request source, not RESPONSE_PORT")
                val decoded = ByteArray(4) { (xor[4 + it].toInt() xor byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)[it].toInt()).toByte() }
                expect(decoded.contentEquals(ip("203.0.113.1")), "XOR-MAPPED address")
            }
            val ext = checkNotNull(reply(packet(request(attr(0xF000, ByteArray(16))), server)))
            expect(attributes(ext, 28).getValue(0xF000).copyOfRange(12,16).contentEquals(ip(profile.third)), "capture-backed third-server address")
        }
        val plain = packet(request())
        val first = checkNotNull(reply(plain))
        repeat(100) { expect(reply(plain)?.contentEquals(first) == true, "immediate retry must reproduce response") }
        expect(reply(packet(request(), localPort = 56009))?.let { u16(it, 22) == 56009 } == true, "same TxID on different socket is not suppressed")
        expect(reply(packet(request(portAttr(61000))))?.let { u16(it, 20) == 3478 && u16(it, 22) == 61000 } == true, "RESPONSE_PORT without CHANGE_REQUEST")
        expect(reply(packet(request(intAttr(3, 2), intAttr(3, 4))))?.let { u16(it, 20) == 3479 } == true, "first duplicate attribute wins")
        expect(reply(packet(request(), "192.0.2.123")) == null, "unknown server cannot inherit an unrelated cluster")
        expect(reply(packet(request(), serverPort = 3481)) == null, "unknown server-port pairing is passed through")
        expect(reply(packet(request(portAttr(0)))) == null, "zero response port must not silently select another port")
        expect(reply(packet(request(attr(0x0011, ByteArray(0))))) == null, "unknown required attribute is not fabricated")
        expect(reply(packet(request(attr(0x0008, ByteArray(20))))) == null, "authenticated request goes to real server")
        expect(reply(packet(request(attr(0x0026, ByteArray(4)), portAttr(61000)))) == null, "padding with response port is not fabricated")
        val shortMessage = request().also { w16(it, 2, 4) }
        expect(reply(packet(shortMessage)) == null, "declared message outside UDP bounds")
        val malformedAttribute = request(intAttr(3, 2)).also { w16(it, 22, 65535) }
        expect(reply(packet(malformedAttribute)) == null, "attribute exceeds declared message")
        expect(reply(packet(request() + byteArrayOf(1,2,3,4))) == null, "trailing bytes cannot extend declared message")
        val fingerprinted = request(attr(0x8028, ByteArray(4)))
        w32(fingerprinted, fingerprinted.size - 4, CRC32().also { it.update(fingerprinted, 0, fingerprinted.size - 8) }.value xor 0x5354554EL)
        expect(reply(packet(fingerprinted)) != null, "valid request fingerprint accepted")
        fingerprinted[fingerprinted.lastIndex] = (fingerprinted.last().toInt() xor 1).toByte()
        expect(reply(packet(fingerprinted)) == null, "invalid request fingerprint rejected")
        val p = packet(request())
        expect(!PacketParser.parse(p, p.size + 1).isValid, "raw packet length larger than array rejected")
        expect(!PacketParser.parse(p, -1).isValid, "negative packet length rejected")
        val badIp = p.copyOf().also { w16(it, 2, 60000) }
        expect(!PacketParser.parse(badIp, badIp.size).isValid, "truncated IP rejected")
        val fragment = p.copyOf().also { w16(it, 6, 1) }
        expect(!PacketParser.parse(fragment, fragment.size).isValid, "fragments are not mistaken for UDP headers")

        val sixRequest = request(intAttr(3, 6), portAttr(61000))
        val six = ByteArray(48 + sixRequest.size).also {
            it[0] = 0x60; it[6] = 17; it[7] = 64
            w16(it, 4, it.size - 40); mapped("10.0.0.2").copyInto(it, 8); mapped("18.176.255.15").copyInto(it, 24)
            w16(it, 40, 56008); w16(it, 42, 3478); w16(it, 44, it.size - 40); sixRequest.copyInto(it, 48)
        }
        val sixReply = checkNotNull(StunFabricator.fabricateIpv6StunResponse(six, six.size, "203.0.113.1", 56008))
        expect(sixReply.copyOfRange(8,24).contentEquals(mapped("54.199.110.22")), "mapped IPv6 source obeys change IP")
        expect(u16(sixReply,40) == 3479 && u16(sixReply,42) == 61000, "mapped IPv6 response port directions")
        address(attributes(sixReply,48).getValue(0x802B), mapped("54.199.110.22"), 3479, 2)
        val sixPseudo = sixReply.copyOfRange(8,40) + ByteArray(8).also { w32(it,0,(sixReply.size-40).toLong()); it[7]=17 }
        expect(sum(sixPseudo + sixReply.copyOfRange(40,sixReply.size)) == 65535, "IPv6 UDP checksum")
        val nativeSix = six.copyOf().also { it[24] = 0x20; it[25] = 0x01 }
        expect(StunFabricator.fabricateIpv6StunResponse(nativeSix, nativeSix.size, "203.0.113.1", 56008) == null, "unverified native IPv6 server uses normal path")
        println("PASS STUN regression matrix: $assertions assertions")
    }

    @JvmStatic fun main(args: Array<String>) { verifyAll() }
}
