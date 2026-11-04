package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaSamplesTest {
    @Test fun convertsPcm16ToNormalizedSamples() {
        val bytes = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val samples = pcm16ToSamples(bytes)
        assertEquals(3, samples.size)
        assertEquals(0f, samples[0], 1e-6f)
        assertEquals(32767f / 32768f, samples[1], 1e-6f)
        assertEquals(-1f, samples[2], 1e-6f)
    }

    @Test fun ignoresTrailingOddByte() {
        val samples = pcm16ToSamples(byteArrayOf(0x10, 0x00, 0x7F))
        assertEquals(1, samples.size)
        assertEquals(16f / 32768f, samples[0], 1e-6f)
    }

    @Test fun emptyInputYieldsNoSamples() {
        assertEquals(0, pcm16ToSamples(ByteArray(0)).size)
    }
}
