package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.SpeechRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地识别的纯逻辑部分。
 *
 * 这里刻意不碰 sherpa-onnx：native 侧的参数错误（例如把采样点数当采样率传）会让进程
 * 直接 exit(-1)，Java 层既抛不出异常也测不到。因此能守住的是这些纯函数——
 * PCM 转 float、静音判定，以及「交给 native 的采样率与帧长无关」这一条。
 */
class LocalSpeechRecognizerTest {

    // ---- pcm16ToFloat ----

    @Test fun decodesLittleEndianAndScalesToUnitRange() {
        // +32767 / -32768 / 0 各一个 16 位小端样本。
        val frame = byteArrayOf(
            0xFF.toByte(), 0x7F,             // +32767
            0x00, 0x80.toByte(),             // -32768
            0x00, 0x00,                      // 0
        )
        val out = FloatArray(3)

        LocalSpeechRecognizer.pcm16ToFloat(frame, 3, out)

        assertEquals(32767f / 32768f, out[0], 1e-6f)
        assertEquals(-1f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
    }

    @Test fun writesOnlyRequestedCountAndLeavesRestUntouched() {
        // 数组比实际样本多时必须停在 count：多写会把上一帧的尾巴重复喂给 native。
        val frame = byteArrayOf(0x10, 0x00, 0x20, 0x00)
        val out = FloatArray(4) { -1f }

        LocalSpeechRecognizer.pcm16ToFloat(frame, 2, out)

        assertEquals(16f / 32768f, out[0], 1e-6f)
        assertEquals(32f / 32768f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
        assertEquals(-1f, out[3], 1e-6f)
    }

    // ---- isSilent ----

    @Test fun allZeroFrameIsSilent() {
        assertTrue(LocalSpeechRecognizer.isSilent(ByteArray(6400), 3200))
    }

    @Test fun fullScaleFrameIsNotSilent() {
        assertFalse(LocalSpeechRecognizer.isSilent(constantWave(amplitude = 32767), 3200))
    }

    @Test fun judgementSitsOnTheThreshold() {
        // 恒定幅值的帧其 RMS 就等于该幅值；取阈值两侧各一档，确认判定落在阈值两边。
        val below = constantWave(amplitude = (LocalSpeechRecognizer.SILENCE_RMS * 0.9).toInt())
        val above = constantWave(amplitude = (LocalSpeechRecognizer.SILENCE_RMS * 1.1).toInt())
        assertTrue(LocalSpeechRecognizer.isSilent(below, below.size / 2))
        assertFalse(LocalSpeechRecognizer.isSilent(above, above.size / 2))
    }

    @Test fun quietSpeechAboveThresholdCountsAsSpeech() {
        // 正常说话音量远高于阈值，不能被当成静音，否则端点判定会立刻触发。
        assertFalse(LocalSpeechRecognizer.isSilent(constantWave(amplitude = 3000), 3200))
    }

    // ---- 交给 native 的采样率 ----

    @Test fun sampleRateMatchesRecorder() {
        // 回归用例：曾经把这一帧的采样点数（3200）当成采样率传给 acceptWaveform，
        // native 侧按 3200Hz 建重采样器；点完成时录音被 stop 打断，read 返回 640 点的
        // 短帧又被当成 640Hz，于是报 input sampling rate changed 并 exit(-1)。
        // 交给 sherpa 的采样率必须等于录音器实际采样率，且与帧长无关。
        assertEquals(SpeechRecorder.RATE, LocalSpeechRecognizer.SAMPLE_RATE)
        assertEquals(SpeechRecorder.FRAME_BYTES / 2, LocalSpeechRecognizer.FRAME_SAMPLES)
    }

    @Test fun nativeBufferIsTrimmedToActualSampleCount() {
        // 短帧必须截断：JNI 按整个数组长度取样本，不截就会把上一帧的尾巴重复喂进去。
        val samples = FloatArray(3200) { 1f }

        val full = LocalSpeechRecognizer.nativeBuffer(samples, 3200)
        val short = LocalSpeechRecognizer.nativeBuffer(samples, 320)

        assertEquals(3200, full.size)
        assertEquals(320, short.size)
        assertTrue("满帧不应额外复制", full === samples)
    }

    /** 16 位小端恒定幅值帧；每个采样都等于 [amplitude]，因此 RMS 也等于 [amplitude]。 */
    private fun constantWave(amplitude: Int): ByteArray {
        val clamped = amplitude.coerceIn(0, 32767)
        val low = (clamped and 0xFF).toByte()
        val high = ((clamped shr 8) and 0xFF).toByte()
        return ByteArray(6400) { index -> if (index % 2 == 0) low else high }
    }
}
