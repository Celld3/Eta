package io.github.mangi.eta.agent.voice

import android.content.Context
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.k2fsa.sherpa.ncnn.getDecoderConfig
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 本地流式识别：模型随 APK 打包，首次使用时解压到私有目录，之后完全离线运行。
 * 采样率必须与 [SpeechRecorder.RATE] 一致，[SherpaNcnn.acceptSamples] 只接受归一化的 Float 采样。
 */
internal object SherpaRecognizer {
    const val MODEL_DIR = "sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-23"
    private const val ASSET_ROOT = "sherpa"
    private const val MARKER = ".ready"
    /** 手动结束时不需要在说话中途断句，给一个足够长的静音阈值，只在长停顿时切句。 */
    private const val MANUAL_SILENCE_SECONDS = 3.0f
    private const val MIN_AUTO_SILENCE_SECONDS = 0.6f

    @Volatile private var cached: File? = null

    /** 把 assets 里的模型解压到私有目录；用标记文件避免每次启动都重写 25MB。 */
    fun prepare(context: Context): File {
        cached?.let { if (it.exists()) return it }
        val target = File(context.filesDir, "sherpa/$MODEL_DIR")
        val marker = File(target, MARKER)
        if (marker.exists()) { cached = target; return target }
        target.deleteRecursively()
        target.mkdirs()
        val assets = context.assets
        val names = assets.list(ASSET_ROOT) ?: emptyArray()
        if (names.isEmpty()) {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "安装包缺少本地识别模型，请重新安装")
        }
        names.forEach { name ->
            val source = "$ASSET_ROOT/$name"
            val destination = File(target, name)
            assets.open(source).use { input ->
                destination.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 16) }
            }
        }
        marker.writeText("ok")
        cached = target
        return target
    }

    /**
     * [endpointSilenceMs] 大于 0 表示停顿这么久就判定一句话说完（助手浮窗的自动发送）；
     * 0 表示手动结束，此时把阈值调高，避免用户的自然停顿被切成多段。
     */
    fun create(context: Context, endpointSilenceMs: Int): SherpaNcnn {
        val dir = prepare(context)
        fun path(name: String): String {
            val file = File(dir, name)
            if (!file.exists()) {
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别模型缺少 $name")
            }
            return file.absolutePath
        }
        val modelConfig = ModelConfig(
            encoderParam = path("encoder_jit_trace-pnnx.ncnn.param"),
            encoderBin = path("encoder_jit_trace-pnnx.ncnn.bin"),
            decoderParam = path("decoder_jit_trace-pnnx.ncnn.param"),
            decoderBin = path("decoder_jit_trace-pnnx.ncnn.bin"),
            joinerParam = path("joiner_jit_trace-pnnx.ncnn.param"),
            joinerBin = path("joiner_jit_trace-pnnx.ncnn.bin"),
            tokens = path("tokens.txt"),
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            useGPU = false,
        )
        val silence = if (endpointSilenceMs > 0) {
            (endpointSilenceMs / 1_000f).coerceAtLeast(MIN_AUTO_SILENCE_SECONDS)
        } else {
            MANUAL_SILENCE_SECONDS
        }
        val config = RecognizerConfig(
            featConfig = FeatureExtractorConfig(sampleRate = SpeechRecorder.RATE.toFloat(), featureDim = 80),
            modelConfig = modelConfig,
            decoderConfig = getDecoderConfig(method = "greedy_search", numActivePaths = 4),
            enableEndpoint = true,
            // rule1 在有文字后生效，必须大于 rule2；两条都随用户选择的停顿时长缩放。
            rule1MinTrailingSilence = silence * 1.6f,
            rule2MinTrailingSilence = silence,
            rule3MinUtteranceLength = 30.0f,
        )
        return runCatching { SherpaNcnn(config = config) }
            .getOrElse { error ->
                AndroidAgentLogger.warn("Eta sherpa init failed: ${error.javaClass.simpleName}")
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别引擎初始化失败，请重试或改用在线识别")
            }
    }
}

/** PCM16 小端字节流转换成 ncnn 需要的 [-1, 1] 浮点采样；末尾不足两字节的残余直接丢弃。 */
internal fun pcm16ToSamples(bytes: ByteArray): FloatArray {
    val count = bytes.size / 2
    val samples = FloatArray(count)
    for (index in 0 until count) {
        val low = bytes[index * 2].toInt() and 0xFF
        val high = (bytes[index * 2 + 1].toInt() and 0xFF) shl 8
        samples[index] = ((low or high).toShort()) / 32768.0f
    }
    return samples
}

/**
 * 逐帧喂给本地识别器，[onPreview] 拿到当前已识别的完整文字；返回最终结果。
 * [onReady] 在模型加载完成后才调用，避免 25MB 首次解压期间录音通道被写满。
 * 断句由模型的 endpoint 规则判定：只有 [endpointSilenceMs] 大于 0 时才会在第一句结束时
 * 通知 [onEndpoint]；否则只在内部切句并继续累积，不会把用户的自然停顿当成说完。
 */
internal suspend fun recognizeSpeechLocal(
    context: Context,
    frames: ReceiveChannel<ByteArray>,
    endpointSilenceMs: Int,
    onReady: () -> Unit,
    onPreview: (String) -> Unit,
    onEndpoint: () -> Unit,
): String = withTimeout(120_000) {
    val autoEndpoint = endpointSilenceMs > 0
    val recognizer = withContext(Dispatchers.Default) { SherpaRecognizer.create(context, endpointSilenceMs) }
    onReady()
    val parts = mutableListOf<String>()
    var preview = ""
    var endpointSent = false
    fun publish(segment: String) {
        val merged = (parts + segment).joinToString("")
        if (merged.isNotEmpty() && merged != preview) {
            preview = merged
            onPreview(merged)
        }
    }
    try {
        for (bytes in frames) {
            val segment = withContext(Dispatchers.Default) {
                recognizer.acceptSamples(pcm16ToSamples(bytes))
                recognizer.decode()
                recognizer.text.trim()
            }
            publish(segment)
            val ended = withContext(Dispatchers.Default) { recognizer.isEndpoint() }
            if (ended) {
                if (segment.isNotEmpty()) parts += segment
                withContext(Dispatchers.Default) { recognizer.reset(recreate = false) }
                if (autoEndpoint && !endpointSent && parts.joinToString("").isNotBlank()) {
                    endpointSent = true
                    onEndpoint()
                }
            }
        }
        val tail = withContext(Dispatchers.Default) {
            recognizer.inputFinished()
            recognizer.decode()
            recognizer.text.trim()
        }
        if (tail.isNotEmpty()) parts += tail
        parts.joinToString("")
    } finally {
        runCatching { recognizer.reset(recreate = false) }
    }
}
