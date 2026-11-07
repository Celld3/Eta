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
    private const val SAMPLE_RATE = 16_000

    /** 录音器一帧 6400 字节，即 3200 个 16 位采样点。 */
    private const val FRAME_SAMPLES = 3_200

    /** 与录音器算音量用的量级对齐；低于它按静音计，用于停顿判定。 */
    private const val SILENCE_RMS = 800.0

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
            var trailingSilence = 0
            var lastText = ""
            // 模型就绪后再通知调用方开始录音：onReady 才是启动录音的地方，而模型加载要几秒，
            // 若等第一帧音频才回调，录音永远不会开始，界面会一直停在「正在连接」。
            onReady()
            for (frame in frames) {
                val count = frame.size / 2
                if (count <= 0 || count > FRAME_SAMPLES) continue
                pcm16ToFloat(frame, count, samples)
                stream.acceptWaveform(samples, count)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                if (text != lastText) {
                    lastText = text
                    onPreview(text)
                }
                if (silenceSamples > 0) {
                    trailingSilence = if (isSilent(frame, count)) trailingSilence + count else 0
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

    /** 一帧能量低于阈值即视为静音；用 RMS 而非峰值，避免单个尖峰误判为说话。 */
    private fun isSilent(frame: ByteArray, count: Int): Boolean {
        var energy = 0.0
        var index = 0
        while (index + 1 < count * 2) {
            val sample = ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)).toShort().toDouble()
            energy += sample * sample
            index += 2
        }
        return kotlin.math.sqrt(energy / count) < SILENCE_RMS
    }

    private fun pcm16ToFloat(frame: ByteArray, count: Int, out: FloatArray) {
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

    /**
     * 预热：模型已就绪时在后台线程提前加载，避免点麦克风才开始等加载。
     *
     * 加载会把上百 MB 模型读进内存，且底层是 native 调用，必须在工作线程上跑；
     * 由调用方在合适的时机（例如用户按下麦克风）触发，不在页面组合期自动执行。
     */
    fun warmUp() {
        if (recognizer.get() != null) return
        if (!modelDirectoryExists()) return
        Thread({
            synchronized(lock) {
                if (recognizer.get() != null) return@synchronized
                runCatching { load()?.let { recognizer.compareAndSet(null, it) } }
            }
        }, "local-asr-warmup").apply { isDaemon = true }.start()
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
