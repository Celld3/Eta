package io.github.mangi.eta.agent.voice.local

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/**
 * 本地流式识别：把录音帧喂给 sherpa-onnx 的流式识别器，边说边出预览文字。
 *
 * 识别器只有一份，由 [LocalRecognizerHolder] 管着；输入栏与助手浮窗互斥使用，
 * 同一时刻只有一个会话在跑。加载一次约几秒、常驻约数百 MB，因此空闲一段时间后释放。
 */
internal object LocalSpeechRecognizer {
    /** 录音器采样率；也是交给 sherpa 的采样率，两者必须一致。 */
    internal const val SAMPLE_RATE = 16_000

    /** 录音器一帧 6400 字节，即 3200 个 16 位采样点。 */
    internal const val FRAME_SAMPLES = 3_200

    /**
     * 停顿判定的噪声底绝对下限。
     *
     * 阈值是「噪声底 × [SILENCE_RATIO]」而不是固定值：实测录音的噪声底从 286 到 6071
     * 都有，固定阈值在安静房间会把呼吸当说话，在嘈杂环境又永远不会触发。
     */
    internal const val SILENCE_FLOOR_MIN = 300.0

    /** 高于噪声底这么多倍才算说话；实测停顿段约为噪声底的 1~2 倍，留出余量。 */
    internal const val SILENCE_RATIO = 2.5

    /** 判定为静音时噪声底快速下探，避免一次误判把阈值一直顶在高位。 */
    internal const val SILENCE_ATTACK = 0.5

    /** 判定为说话时噪声底极慢上浮，跟得上环境变吵又不被语音本身带偏。 */
    internal const val SILENCE_RELEASE = 0.002

    /** 开头几帧先取最小值当噪声底：第一帧很可能已经是说话声，直接采用会把阈值顶高。 */
    internal const val SILENCE_PRIME_FRAMES = 3

    const val MODEL_MISSING = "本地识别模型还没下载，请到设置里下载后再用"
    const val MODEL_LOAD_FAILED = "本地识别模型无法加载，请重新下载模型"
    const val DECODE_FAILED = "本地识别出错，请重试"

    /**
     * @param endpointSilenceMs 停顿判定阈值；为 0 时不自动结束，只在用户手动结束时收尾。
     */
    suspend fun recognize(
        frames: ReceiveChannel<ByteArray>,
        onReady: () -> Unit,
        onPreview: (String) -> Unit,
        onEndpoint: () -> Unit,
        automaticEndpoint: Boolean,
        endpointSilenceMs: Int,
    ): String = withContext(Dispatchers.IO) {
        val recognizer = LocalRecognizerHolder.acquire()
        val stream = recognizer.createStream()
        try {
            val samples = FloatArray(FRAME_SAMPLES)
            val silenceSamples = if (automaticEndpoint) endpointSilenceMs * SAMPLE_RATE / 1000 else 0
            val silence = if (silenceSamples > 0) SilenceDetector() else null
            var trailingSilence = 0
            var lastText = ""
            // 模型就绪后再通知调用方开始录音：onReady 才是启动录音的地方，而模型加载要几秒，
            // 若等第一帧音频才回调，录音永远不会开始，界面会一直停在「正在连接」。
            onReady()
            for (frame in frames) {
                val count = frame.size / 2
                if (count <= 0 || count > FRAME_SAMPLES) continue
                pcm16ToFloat(frame, count, samples)
                // acceptWaveform 的第二个参数是采样率，不是采样点数：传点数会让 native 侧按
                // 「点数 Hz」建重采样器，下一帧长度不同就报 input sampling rate changed 并
                // exit(-1)（进程 status=255，Java 侧拦不住）。
                stream.acceptWaveform(nativeBuffer(samples, count), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                if (text != lastText) {
                    lastText = text
                    onPreview(text)
                }
                if (silence != null) {
                    trailingSilence = if (silence.accept(rms(frame, count))) trailingSilence + count else 0
                    if (trailingSilence >= silenceSamples) {
                        onEndpoint()
                        break
                    }
                }
            }
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val finalText = recognizer.getResult(stream).text
            if (finalText != lastText) onPreview(finalText)
            finalText
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AndroidAgentLogger.warn("Local ASR decode failed: ${error.javaClass.simpleName}")
            throw SpeechFailure(SpeechErrorCode.AUDIO, DECODE_FAILED)
        } finally {
            stream.release()
        }
    }

    /**
     * 交给 native 的采样缓冲：满帧直接复用，短帧截到实际点数。
     *
     * JNI 按整个数组长度取样本，短帧不截会把上一帧的尾巴重复喂进去。
     */
    internal fun nativeBuffer(samples: FloatArray, count: Int): FloatArray =
        if (count == samples.size) samples else samples.copyOf(count)

    /**
     * 自适应静音判定：维护一条缓慢跟随的噪声底，一帧能量低于「噪声底 × [SILENCE_RATIO]」即视为静音。
     *
     * 噪声底只在会话内有效，新一次识别重新建。开头 [SILENCE_PRIME_FRAMES] 帧取最小值作为初值，
     * 此后静音帧让它快速下探、说话帧让它极慢上浮，因此既能适配安静房间，也能适配持续底噪。
     */
    internal class SilenceDetector {
        private var floor: Double? = null
        private val primed = ArrayList<Double>(SILENCE_PRIME_FRAMES)

        /** 传入一帧的 RMS，返回这一帧是否算静音。 */
        fun accept(rms: Double): Boolean {
            val current = floor
            if (current == null) {
                primed.add(rms)
                if (primed.size < SILENCE_PRIME_FRAMES) return rms < SILENCE_FLOOR_MIN
                val initial = primed.min()
                floor = initial
                return rms < threshold(initial)
            }
            val silent = rms < threshold(current)
            val rate = if (silent) SILENCE_ATTACK else SILENCE_RELEASE
            floor = current + (rms - current) * rate
            return silent
        }

        /** 当前阈值，便于日志与测试观察。 */
        internal fun threshold(): Double = threshold(floor ?: return SILENCE_FLOOR_MIN)

        private fun threshold(base: Double): Double =
            maxOf(SILENCE_FLOOR_MIN, base * SILENCE_RATIO)
    }

    /** 一帧的 RMS；用均方根而非峰值，避免单个尖峰被当成说话。 */
    internal fun rms(frame: ByteArray, count: Int): Double {
        var energy = 0.0
        var index = 0
        while (index + 1 < count * 2) {
            val sample = ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)).toShort().toDouble()
            energy += sample * sample
            index += 2
        }
        return kotlin.math.sqrt(energy / count)
    }

    /** 固定阈值判定，仅供测试与对照；正式链路用 [SilenceDetector]。 */
    internal fun isSilent(frame: ByteArray, count: Int, threshold: Double = SILENCE_FLOOR_MIN): Boolean =
        rms(frame, count) < threshold

    internal fun pcm16ToFloat(frame: ByteArray, count: Int, out: FloatArray) {
        var index = 0
        var sample = 0
        while (sample < count) {
            val value = ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)).toShort()
            out[sample] = value / 32768f
            index += 2
            sample++
        }
    }
}

/**
 * 识别器单例。模型只加载一次，多个会话共用；空闲 [IDLE_RELEASE_MS] 后释放。
 * 只在真正开始识别时按需加载，不在页面组合期预热。
 */
internal object LocalRecognizerHolder {
    private val recognizer = AtomicReference<OnlineRecognizer?>(null)
    private val lock = Any()

    /** 设备 4 核，识别本身远快于实时，2 线程足够且留出余量给界面。 */
    private const val THREADS = 2
    private const val IDLE_RELEASE_MS = 90_000L

    @Volatile
    private var lastUsedAt = 0L

    @Volatile
    private var busy = false

    /** 取得可用识别器；模型缺失或加载失败会抛出带提示的 [SpeechFailure]。 */
    fun acquire(): OnlineRecognizer {
        synchronized(lock) {
            recognizer.get()?.let {
                lastUsedAt = System.currentTimeMillis()
                busy = true
                return it
            }
            val loaded = load()
                ?: throw SpeechFailure(
                    SpeechErrorCode.CONFIGURATION,
                    if (modelDirectoryExists()) LocalSpeechRecognizer.MODEL_LOAD_FAILED
                    else LocalSpeechRecognizer.MODEL_MISSING,
                )
            recognizer.set(loaded)
            lastUsedAt = System.currentTimeMillis()
            busy = true
            return loaded
        }
    }

    /** 会话结束时调用，允许空闲回收。 */
    fun releaseSession() {
        synchronized(lock) {
            busy = false
            lastUsedAt = System.currentTimeMillis()
        }
    }

    /** 空闲释放；会话进行中不释放。 */
    fun releaseIfIdle(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (busy) return
            val current = recognizer.get() ?: return
            if (now - lastUsedAt < IDLE_RELEASE_MS) return
            recognizer.set(null)
            runCatching { current.release() }
            AndroidAgentLogger.info("Local ASR recognizer released after idle")
        }
    }

    fun releaseNow() {
        synchronized(lock) {
            busy = false
            val current = recognizer.get() ?: return
            recognizer.set(null)
            runCatching { current.release() }
        }
    }

    private fun modelDirectoryExists(): Boolean {
        val context = Prefs.applicationContext() ?: return false
        return LocalAsrModelStore(context).isReady()
    }

    private fun load(): OnlineRecognizer? {
        val context = Prefs.applicationContext() ?: return null
        val root: File = LocalAsrModelStore(context).readyDirectory() ?: return null
        val model = LocalAsrModels.active
        return try {
            val started = System.currentTimeMillis()
            // 模型是 App 私有目录里的绝对路径，必须走 fromFile 构造：给 OnlineRecognizer 传
            // assetManager 会让 native 侧认为模型要按 assets 路径读取，与绝对路径冲突后
            // 直接 exit(-1)（进程 status=255，无 Java 异常可捕获）。
            val loaded = OnlineRecognizer(
                assetManager = null,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = model.encoder(root).absolutePath,
                            decoder = model.decoder(root).absolutePath,
                            joiner = model.joiner(root).absolutePath,
                        ),
                        tokens = model.tokens(root).absolutePath,
                        numThreads = THREADS,
                        // 显式声明，避免库在探测模型类型时多读一遍模型。
                        modelType = "zipformer2",
                    ),
                    // 标点由模型自带，不再接标点模型；自动断句交给调用方按停顿判定。
                    enableEndpoint = false,
                    decodingMethod = "greedy_search",
                ),
            )
            AndroidAgentLogger.info(
                "Local ASR recognizer loaded in ${System.currentTimeMillis() - started} ms",
            )
            loaded
        } catch (error: Throwable) {
            AndroidAgentLogger.warn("Local ASR recognizer load failed: ${error.javaClass.simpleName}")
            null
        }
    }
}
