package io.github.mangi.eta.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.mangi.eta.data.model.SpeechSettings
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 系统语音播报：直接用设备自带的 TextToSpeech 引擎朗读，不走 HTTP 合成，也没有 PCM 音频流。
 * 引擎是进程级单例，[shutdown] 后可再次 [obtain] 重建。
 */
internal object SystemSpeechEngine {
    private var engine: TextToSpeech? = null
    private var initialized = false

    private suspend fun obtain(context: Context): TextToSpeech =
        withContext(Dispatchers.Main.immediate) {
            val existing = engine
            if (existing != null && initialized) return@withContext existing
            val appContext = context.applicationContext
            val created = suspendCancellableCoroutine { continuation ->
                val pending = TextToSpeech(appContext) { status ->
                    if (status != TextToSpeech.SUCCESS) {
                        initialized = false
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                SpeechFailure(
                                    SpeechErrorCode.CONFIGURATION,
                                    "系统语音引擎未初始化，请在系统设置检查语音服务",
                                ),
                            )
                        }
                    }
                }
                engine = pending
                if (continuation.isActive) continuation.resume(pending)
            }
            when (created.setLanguage(Locale.CHINA)) {
                TextToSpeech.LANG_MISSING_DATA -> throw SpeechFailure(
                    SpeechErrorCode.CONFIGURATION,
                    "系统语音缺少中文数据，请在系统设置下载语音包",
                )
                TextToSpeech.LANG_NOT_SUPPORTED -> throw SpeechFailure(
                    SpeechErrorCode.CONFIGURATION,
                    "当前系统语音引擎不支持中文，请更换引擎",
                )
            }
            initialized = true
            created
        }

    /**
     * 逐段串行朗读：上一段 [UtteranceProgressListener.onDone] 之后才排队下一段，
     * 避免把长正文一次性灌给引擎被截断。[onFirstAudio] 在第一段真正开始发声时回调。
     */
    suspend fun speak(
        context: Context,
        settings: SpeechSettings,
        chunks: List<String>,
        onFirstAudio: () -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        if (chunks.isEmpty()) return@withContext
        val tts = obtain(context)
        tts.setSpeechRate(settings.systemRate.coerceIn(0.5f, 2f))
        tts.setPitch(settings.systemPitch.coerceIn(0.5f, 2f))
        if (settings.systemVoice.isNotBlank()) {
            tts.voices?.firstOrNull { it.name == settings.systemVoice }?.let { voice ->
                runCatching { tts.voice = voice }
            }
        }
        suspendCancellableCoroutine { continuation ->
            var index = 0
            var announced = false
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (!announced) {
                        announced = true
                        onFirstAudio()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    index++
                    if (index >= chunks.size) {
                        if (continuation.isActive) continuation.resume(Unit)
                        return
                    }
                    if (!continuation.isActive) return
                    queue(tts, chunks[index], index)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechFailure(SpeechErrorCode.AUDIO, "系统语音播报失败"),
                        )
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechFailure(SpeechErrorCode.AUDIO, "系统语音播报失败"),
                        )
                    }
                }
            }
            tts.setOnUtteranceProgressListener(listener)
            continuation.invokeOnCancellation {
                runCatching { tts.setOnUtteranceProgressListener(null) }
                runCatching { tts.stop() }
            }
            queue(tts, chunks[0], 0)
        }
    }

    private fun queue(tts: TextToSpeech, text: String, index: Int) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eta-system-$index")
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        initialized = false
    }

    /** 引擎可用音色；中文优先，名称缺失时用语言显示名兜底。 */
    fun voices(): List<Pair<String, String>> = engine?.voices
        ?.filter { it.locale?.language == "zh" }
        ?.map { voice ->
            val label = voice.name.ifBlank { voice.locale?.displayName.orEmpty() }
            label to voice.name
        }
        .orEmpty()
}
