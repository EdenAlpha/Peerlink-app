package com.peerlink.app.godmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhistleWavTest {

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    @Test fun headerIsCanonicalRiffWave() {
        val h = WhistleWav.header(pcmBytes = 96_000, sampleRate = 48_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", ascii(h, 0, 4))
        assertEquals(36 + 96_000, u32(h, 4))
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals(16, u32(h, 16))
        assertEquals(1, u16(h, 20))              // PCM
        assertEquals(1, u16(h, 22))              // mono
        assertEquals(48_000, u32(h, 24))
        assertEquals(96_000, u32(h, 28))         // byte rate = 48000 * 2
        assertEquals(2, u16(h, 32))              // block align
        assertEquals(16, u16(h, 34))             // bits per sample
        assertEquals("data", ascii(h, 36, 4))
        assertEquals(96_000, u32(h, 40))
    }

    @Test fun wrapPlacesPcmAfterHeader() {
        val pcm = ByteArray(8) { it.toByte() }
        val wav = WhistleWav.wrap(pcm, 48_000)
        assertEquals(44 + 8, wav.size)
        assertEquals(pcm.size, u32(wav, 40))
        for (i in pcm.indices) assertEquals(pcm[i], wav[44 + i])
    }

    @Test fun peakFindsLoudestSignedSample() {
        // little-endian int16: 0x1234, -1 (0xFFFF), -32768 (0x8000), 0
        val pcm = byteArrayOf(
            0x34, 0x12,
            0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x80.toByte(),
            0x00, 0x00,
        )
        assertEquals(32_768, WhistleWav.peak(pcm))
    }

    @Test fun silenceIsExactlyAllZeros() {
        assertTrue(WhistleWav.isSilent(ByteArray(4096)))
        assertFalse(WhistleWav.isSilent(ByteArray(4096).also { it[100] = 1 }))
        assertFalse(WhistleWav.isSilent(ByteArray(4096).also { it[100] = 0; it[101] = 0x80.toByte() })) // -32768
    }

    @Test fun wrapOfSilenceReportsSilentPcm() {
        val pcm = ByteArray(48_000 * 2) // one second of digital silence
        val wav = WhistleWav.wrap(pcm, 48_000)
        val decoded = wav.copyOfRange(WhistleWav.HEADER_BYTES, wav.size)
        assertTrue(WhistleWav.isSilent(decoded))
        assertEquals(0, WhistleWav.peak(decoded))
    }
}
