package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SpeechInputState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val progress: String = "",
    val error: String? = null,
    val configureAvailable: Boolean = false,
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

internal class SpeechInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val automaticEndpoint: Boolean = false,
    private val beforeCapture: () -> Unit = {},
    private val afterCapture: () -> Unit = {},
    private val refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner(),
    private val createRecorder: () -> SpeechAudioCapture = ::SpeechRecorder,
    private val recognizeStream: suspend (
        SpeechSettings,
        SpeechCredentials,
        ReceiveChannel<ByteArray>,
        () -> Unit,
        (String) -> Unit,
        () -> Unit,
        Int,
    ) -> String = ::recognizeSpeechStream,
    private val onResult: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var finishRequested = false
    private var job: Job? = null
    private var recorder: SpeechAudioCapture? = null
    private var recognizer: SpeechRecognizer? = null
    private var lease: SpeechAudioLease? = null
    private var capturing = false

    /**
     * [holdToTalk] 表示用户按住说话：松手才结束，期间的停顿不会自动发送。
     * 停顿自动发送只对构造时声明 [automaticEndpoint] 的入口生效，聊天听写始终等待用户确认。
     */
    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null, holdToTalk: Boolean = false) {
        cancel()
        val session = generation
        mutableState.value = SpeechInputState(phase = EtaSpeechPhase.STARTING, progress = "正在连接")
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val config = settings ?: SpeechSettingsRepository.settings()
                if (!config.recognitionEnabled) return@launch
                val secrets = credentials ?: SpeechSettingsRepository.credentials(context)
                validateSpeechSettings(config, secrets, synthesis = false)
                if (config.asr == AsrProvider.SYSTEM) {
                    val systemText = recognizeWithSystem(config, session)
                    if (systemText.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
                    val systemFinal = refined(session, config.refineTranscript, systemText)
                    if (generation == session) {
                        cancel()
                        onResult(systemFinal)
                    }
                    return@launch
                }
                lease = SpeechAudioLease(context) { cancel() }.also { it.acquire(playback = false) }
                val endpointSilenceMs = if (automaticEndpoint && !holdToTalk) config.autoSendSilenceMs else 0
                val audio = createRecorder()
                recorder = audio
                fun update(transform: (SpeechInputState) -> SpeechInputState) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation == session) mutableState.value = transform(mutableState.value)
                    }
                }
                val text = coroutineScope {
                    var recording: Job? = null
                    val begin = {
                        if (recording == null) {
                            beforeCapture()
                            capturing = true
                            recording = launch {
                                try {
                                    audio.record(
                                        onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听，点击完成结束") } },
                                        onLevel = { level -> update { it.copy(level = level) } },
                                    )
                                } finally {
                                    if (generation == session) releaseCapture()
                                }
                                update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f, progress = "正在识别") }
                            }
                        }
                        Unit
                    }
                    try {
                        if (config.asr == AsrProvider.QWEN_REALTIME || config.asr == AsrProvider.DOUBAO) {
                            recognizeStream(
                                config,
                                secrets,
                                audio.frames,
                                begin,
                                { preview -> update { it.copy(preview = preview) } },
                                { audio.finish() },
                                endpointSilenceMs,
                            )
                        } else {
                            begin()
                            val wav = withTimeout(65_000) { collectSpeechAudio(audio.frames) }
                            recognizeSpeechFile(context, config, secrets, wav) { progress ->
                                update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progress) }
                            }
                        }
                    } finally {
                        audio.cancel()
                        recording?.cancel()
                    }
                }
                if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
                val final = refined(session, config.refineTranscript, text)
                if (generation == session) {
                    cancel()
                    onResult(final)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == session) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech recognition failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    cancel()
                    mutableState.value = SpeechInputState(
                        error = failure.userMessage,
                        configureAvailable = failure.code == SpeechErrorCode.CONFIGURATION ||
                            failure.code == SpeechErrorCode.CREDENTIALS ||
                            failure.code == SpeechErrorCode.AUTHENTICATION,
                    )
                }
            } finally {
                if (generation == session) cancel()
            }
        }
    }

    /**
     * 系统识别服务自己录音，不占用 [SpeechRecorder] 与音频租约；回调直接写入状态，
     * 代际号不匹配说明会话已被取消，丢弃迟到的回调。
     */
    private suspend fun recognizeWithSystem(config: SpeechSettings, session: Long): String {
        val system = SystemSpeechRecognizer.create(context)
            ?: throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                "未找到系统语音识别服务，请安装 Google 语音服务或改用输入法语音",
            )
        recognizer = system
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (config.language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
        }
        fun update(transform: (SpeechInputState) -> SpeechInputState) {
            if (generation == session) mutableState.value = transform(mutableState.value)
        }
        try {
            return withTimeout(65_000) {
                suspendCancellableCoroutine { cont ->
                    system.setRecognitionListener(SystemRecognitionListener(
                        readyCallback = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听，点击完成结束") } },
                        endOfSpeechCallback = { update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f, progress = "正在识别") } },
                        levelCallback = { level -> update { it.copy(level = (level / 10f).coerceIn(0f, 1f)) } },
                        partialCallback = { preview -> update { it.copy(preview = preview) } },
                        doneCallback = { result -> if (cont.isActive) cont.resume(result) },
                        errorCallback = { code -> if (cont.isActive) cont.resumeWithException(systemRecognitionFailure(code)) },
                    ))
                    cont.invokeOnCancellation { runCatching { system.cancel() } }
                    runCatching { system.startListening(intent) }
                        .onFailure { error -> if (cont.isActive) cont.resumeWithException(error) }
                }
            }
        } finally {
            runCatching { system.setRecognitionListener(null) }
            runCatching { system.destroy() }
            if (recognizer === system) recognizer = null
        }
    }

    /** 纠错失败不影响交付；只有纠错仍属于当前会话时才展示进度。 */
    private suspend fun refined(session: Long, refineTranscript: Boolean, text: String): String {
        if (!refineTranscript || generation != session) return text
        mutableState.value = mutableState.value.copy(
            phase = EtaSpeechPhase.RECOGNIZING, preview = text, progress = "正在校对", level = 0f,
        )
        return try {
            refiner.refine(text)
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val failure = error.speechFailure()
            AndroidAgentLogger.warn("Eta speech refine skipped: code=${failure.code} type=${error.javaClass.simpleName}")
            text
        }
    }

    fun finish() {
        if (!mutableState.value.active || finishRequested) return
        finishRequested = true
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recognizer?.stopListening()
        recorder?.finish()
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别", level = 0f)
    }

    fun cancel() {
        generation++
        finishRequested = false
        recorder?.cancel(); recorder = null
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        releaseCapture()
        job?.cancel(); job = null
        lease?.close(); lease = null
        mutableState.value = SpeechInputState()
    }

    private fun releaseCapture() {
        if (capturing) afterCapture()
        capturing = false
    }
}
