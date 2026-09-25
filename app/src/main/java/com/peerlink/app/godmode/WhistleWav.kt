package com.peerlink.app.godmode

/**
 * WhistleWav — tiny pure-Kotlin WAV helpers for the whistle-tap recordings.
 *
 * Deliberately free of any Android import so the unit tests on any JVM can
 * verify byte-exact output: the Settings player and the export zip both
 * consume these files, and a malformed header would make a genuine whistle
 * recording unplayable — the exact failure this feature must not have.
 *
 * Format: RIFF/WAVE, PCM 16-bit little-endian, mono, header fixed at 44 bytes.
 */
object WhistleWav {

    const val HEADER_BYTES = 44
    const val BITS_PER_SAMPLE = 16
    const val CHANNELS = 1

    /** 44-byte canonical WAV header for [pcmBytes] of 16-bit mono PCM. */
    fun header(pcmBytes: Int, sampleRate: Int): ByteArray {
        require(pcmBytes >= 0) { "negative pcm length" }
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArray(HEADER_BYTES)
        fun ascii(offset: Int, s: String) {
            for (i in s.indices) out[offset + i] = s[i].code.toByte()
        }
        fun u32(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
            out[offset + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun u16(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        u32(4, 36 + pcmBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        u32(16, 16)              // fmt chunk size
        u16(20, 1)               // PCM
        u16(22, CHANNELS)
        u32(24, sampleRate)
        u32(28, byteRate)
        u16(32, blockAlign)
        u16(34, BITS_PER_SAMPLE)
        ascii(36, "data")
        u32(40, pcmBytes)
        return out
    }

    /** Header + PCM in one buffer. */
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArray(HEADER_BYTES + pcm.size)
        header(pcm.size, sampleRate).copyInto(out)
        pcm.copyInto(out, HEADER_BYTES)
        return out
    }

    /** Largest absolute 16-bit sample; 0 means digital silence. */
    fun peak(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val v = if (s and 0x8000 != 0) (s and 0xFFFF) - 0x10000 else s
            val abs = if (v < 0) -v else v
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    /** True when the PCM carries no audible signal at all. */
    fun isSilent(pcm: ByteArray): Boolean = peak(pcm) == 0
}
