package io.github.mangi.eta.agent.voice

import android.content.Context
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.k2fsa.sherpa.ncnn.getDecoderConfig
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout

/**
 * 本地流式识别：模型随 APK 打包，首次使用时解压到私有目录，之后完全离线运行。
 * 采样率必须与 [SpeechRecorder.RATE] 一致，[acceptSamples] 只接受归一化的 Float 采样。
 */
internal object SherpaRecognizer {
    const val MODEL_DIR = "sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-13"
    private const val ASSET_ROOT = "sherpa"
    private const val MARKER = ".ready"
    private const val NUM_THREADS = 4

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
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        marker.writeText("ok")
        cached = target
        return target
    }

    fun create(context: Context): SherpaNcnn {
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
            numThreads = NUM_THREADS,
            useGPU = false,
        )
        val config = RecognizerConfig(
            featConfig = FeatureExtractorConfig(sampleRate = SpeechRecorder.RATE.toFloat(), featureDim = 80),
            modelConfig = modelConfig,
            decoderConfig = getDecoderConfig(method = "greedy_search", numActivePaths = 4),
            enableEndpoint = true,
            rule1MinTrailingSilence = 2.4f,
            rule2MinTrailingSilence = 1.0f,
            rule3MinUtteranceLength = 30.0f,
        )
        return runCatching { SherpaNcnn(config = config) }
            .getOrElse { error ->
                AndroidAgentLogger.warn("Eta sherpa init failed: ${error.javaClass.simpleName}")
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别引擎初始化失败，请重试或改用在线识别")
            }
    }
}

/** PCM16 小端字节流转换成 ncnn 需要的 [-1, 1] 浮点采样。 */
internal fun pcm16ToSamples(bytes: ByteArray): FloatArray {
    val count = bytes.size / 2
    val samples = FloatArray(count)
    for (index in 0 until count) {
        val low = bytes[index * 2].toInt() and 0xFF
        val high = bytes[index * 2 + 1].toInt() shl 8
        samples[index] = ((low or high).toShort()) / 32768.0f
    }
    return samples
}

/**
 * 逐帧喂给本地识别器，[onPreview] 拿到当前部分结果；返回最终的完整文字。
 * 与在线流式识别不同，断句完全由模型的 endpoint 规则判定，不依赖服务端 VAD。
 */
internal suspend fun recognizeSpeechLocal(
    context: Context,
    frames: ReceiveChannel<ByteArray>,
    onReady: () -> Unit,
    onPreview: (String) -> Unit,
    onEndpoint: () -> Unit,
): String = withTimeout(90_000) {
    val recognizer = withContext(Dispatchers.Default) { SherpaRecognizer.create(context) }
    var last = ""
    var endpointSent = false
    try {
        onReady()
        for (bytes in frames) {
            val samples = pcm16ToSamples(bytes)
            withContext(Dispatchers.Default) {
                recognizer.acceptSamples(samples)
                recognizer.decode()
            }
            val text = recognizer.text.trim()
            if (text.isNotEmpty() && text != last) {
                last = text
                onPreview(text)
            }
            val ended = withContext(Dispatchers.Default) { recognizer.isEndpoint() }
            if (ended) {
                if (!endpointSent) {
                    endpointSent = true
                    onEndpoint()
                }
                withContext(Dispatchers.Default) { recognizer.reset(recreate = false) }
                last = ""
            }
        }
        withContext(Dispatchers.Default) {
            recognizer.inputFinished()
            recognizer.decode()
        }
        recognizer.text.trim()
    } finally {
        runCatching { recognizer.reset(recreate = false) }
    }
}
