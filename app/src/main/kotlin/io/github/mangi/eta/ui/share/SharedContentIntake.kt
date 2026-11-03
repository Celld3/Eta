package io.github.mangi.eta.ui.share

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 由系统分享菜单或划词菜单送进本体的内容。
 *
 * 只承载用户主动选中的文本与图片：不读剪贴板，也不抓来源应用的窗口内容。
 * 应用名只用来告诉模型这段话出自哪里，取不到就留空，不做推断。
 */
internal data class SharedContentIntake(
    val text: String = "",
    val imageUris: List<String> = emptyList(),
    val sourceAppLabel: String? = null,
    /** 同一段内容被重复分享时也要重新预填，因此用单调递增的令牌区分两次不同的送达。 */
    val nonce: Long = 0L,
) {
    val isEmpty: Boolean get() = text.isBlank() && imageUris.isEmpty()
}

/**
 * 把分享与划词 Intent 翻译成待预填的草稿。
 *
 * 只认 `ACTION_SEND`、`ACTION_SEND_MULTIPLE` 与 `ACTION_PROCESS_TEXT`；其余 Action 返回
 * null，继续交给既有的启动跳转逻辑，不改动它们的语义。
 */
internal object SharedContentIntakeParser {
    fun parse(
        intent: Intent?,
        sourceAppLabel: String?,
        nonce: Long,
        maxImages: Int = MAX_SHARED_IMAGES,
    ): SharedContentIntake? {
        val action = intent?.action ?: return null
        val intake = when (action) {
            Intent.ACTION_SEND -> read(intent, sourceAppLabel, nonce, maxImages, multiple = false)
            Intent.ACTION_SEND_MULTIPLE -> read(intent, sourceAppLabel, nonce, maxImages, multiple = true)
            Intent.ACTION_PROCESS_TEXT -> SharedContentIntake(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
                sourceAppLabel = sourceAppLabel,
                nonce = nonce,
            )
            else -> return null
        }
        return intake.takeUnless { it.isEmpty }
    }

    private fun read(
        intent: Intent,
        sourceAppLabel: String?,
        nonce: Long,
        maxImages: Int,
        multiple: Boolean,
    ): SharedContentIntake = SharedContentIntake(
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
        imageUris = readImages(intent, multiple).take(maxImages),
        sourceAppLabel = sourceAppLabel,
        nonce = nonce,
    )

    /** 只接受 content 与 file 方案：其余方案没有可读授权，收下来也只会在发送时失败。 */
    private fun readImages(intent: Intent, multiple: Boolean): List<String> = runCatching {
        val uris = if (multiple) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?.filterNotNull()
                .orEmpty()
        } else {
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        }
        uris.mapNotNull { uri -> uri.toString().takeIf { isReadableScheme(uri) } }
    }.getOrDefault(emptyList())

    private fun isReadableScheme(uri: Uri): Boolean =
        uri.scheme == "content" || uri.scheme == "file"

    /** 分享整篇文章时正文会很长；只保留前几张图，避免一次请求塞满上下文。 */
    const val MAX_SHARED_IMAGES = 4
}

/**
 * 判断内容来自哪个应用。
 *
 * 分享与划词 Intent 都不带调用方包名，`getCallingPackage()` 对这类启动恒为 null，
 * 因此依次尝试调用方 referrer 与使用记录里最近一次切到前台的包；两条都拿不到就不写来源。
 */
internal object SharedSourceAppResolver {
    fun resolve(
        context: Context,
        referrerPackage: String?,
        windowMs: Long = SOURCE_LOOKBACK_MS,
    ): String? {
        val own = context.packageName
        val candidates = listOfNotNull(referrerPackage, recentForegroundPackage(context, own, windowMs))
        for (packageName in candidates.distinct()) {
            if (packageName == own || packageName.isBlank()) continue
            labelOf(context, packageName)?.let { return it }
        }
        return null
    }

    private fun recentForegroundPackage(context: Context, own: String, windowMs: Long): String? =
        runCatching {
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: return@runCatching null
            val end = System.currentTimeMillis()
            val events = manager.queryEvents(end - windowMs, end) ?: return@runCatching null
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val packageName = event.packageName
                if (packageName.isNullOrBlank() || packageName == own) continue
                latest = packageName
            }
            latest
        }.getOrNull()

    private fun labelOf(context: Context, packageName: String): String? = runCatching {
        val packageManager = context.packageManager
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private const val SOURCE_LOOKBACK_MS = 10 * 60 * 1000L
}

/**
 * 生成预填草稿：正文在上，来源行在下，末尾留一个空行给用户补指令。
 *
 * [sourceLine] 由调用方按当前语言提供，避免在纯逻辑里硬编码文案。
 */
internal fun SharedContentIntake.composeDraft(
    sourceLine: String?,
    maxChars: Int = MAX_DRAFT_SOURCE_CHARS,
): String {
    val body = text.trim()
    if (body.isEmpty()) return ""
    val builder = StringBuilder(body.take(maxChars))
    sourceLine?.trim()?.takeIf { it.isNotEmpty() }?.let { line ->
        builder.append("\n\n").append(line)
    }
    return builder.append("\n\n").toString()
}

/** 单次预填的正文上限：再长多半是整篇文章，塞进输入框只会让编辑变难。 */
internal const val MAX_DRAFT_SOURCE_CHARS = 8_000
