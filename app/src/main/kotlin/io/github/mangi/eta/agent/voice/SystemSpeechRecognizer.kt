package io.github.mangi.eta.agent.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

internal object SystemSpeechRecognizer {
    fun create(context: Context): SpeechRecognizer? {
        val appContext = context.applicationContext
        resolveExternalService(appContext)?.let { component ->
            return SpeechRecognizer.createSpeechRecognizer(appContext, component)
        }
        return if (SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            null
        }
    }

    internal fun resolveExternalService(context: Context): ComponentName? {
        val configured = Settings.Secure.getString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE,
        )?.let(ComponentName::unflattenFromString)
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        return selectExternalService(services, context.packageName, configured)
    }

    internal fun selectExternalService(
        services: List<ResolveInfo>,
        ownPackage: String,
        configured: ComponentName?,
    ): ComponentName? = services
            .asSequence()
            .mapNotNull { it.serviceInfo }
            .filter {
                it.packageName != ownPackage && it.exported && it.enabled &&
                    it.applicationInfo?.enabled != false
            }
            .filter {
                val component = ComponentName(it.packageName, it.name)
                component == configured ||
                    it.permission == "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"
            }
            .sortedWith(
                compareByDescending<android.content.pm.ServiceInfo> {
                    ComponentName(it.packageName, it.name) == configured
                }.thenByDescending {
                    (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                },
            )
            .map { ComponentName(it.packageName, it.name) }
            .firstOrNull()

    private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
}

/** 系统识别回调到控制器状态与协程的桥接。 */
internal class SystemRecognitionListener(
    private val onReady: () -> Unit,
    private val onEndOfSpeech: () -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onDone: (String) -> Unit,
    private val onError: (Int) -> Unit,
) : android.speech.RecognitionListener {
    override fun onReadyForSpeech(params: android.os.Bundle?) = onReady()
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = onEndOfSpeech()
    override fun onError(error: Int) = onError(error)
    override fun onResults(results: android.os.Bundle?) = onDone(firstResult(results))
    override fun onPartialResults(partialResults: android.os.Bundle?) = onPartial(firstResult(partialResults))
    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

    private fun firstResult(bundle: android.os.Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}

/** 把系统识别的错误码转成与其他服务一致的失败分类。 */
internal fun systemRecognitionFailure(code: Int): SpeechFailure = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
        SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        SpeechFailure(SpeechErrorCode.PERMISSION, "缺少麦克风权限，无法使用系统语音识别")
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        SpeechFailure(SpeechErrorCode.CONFIGURATION, "系统语音识别不支持所选语言，请更换语言或引擎")
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        SpeechFailure(SpeechErrorCode.NETWORK, "系统语音识别网络异常")
    else -> SpeechFailure(SpeechErrorCode.AUDIO, "系统语音识别失败，请重试")
}
