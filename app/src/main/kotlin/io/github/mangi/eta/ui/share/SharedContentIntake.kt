package io.github.mangi.eta.ui.share

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 由系统分享菜单或划词菜单送进本体的内容。
 *
 * 只承载用户主动选中的文本、图片与其他附件：不读剪贴板，也不抓来源应用的窗口内容。
 * 应用名只用来告诉模型这段话出自哪里，取不到就留空，不做推断。
 */
data class SharedContentIntake(
    val text: String = "",
    val imageUris: List<String> = emptyList(),
    /** 非图片附件（文档、压缩包、音视频等）：走文件引用通道，与手动选文件得到同一种引用。 */
    val fileUris: List<String> = emptyList(),
    val sourceAppLabel: String? = null,
    /** 同一段内容被重复分享时也要重新预填，因此用单调递增的令牌区分两次不同的送达。 */
    val nonce: Long = 0L,
) {
    val isEmpty: Boolean get() = text.isBlank() && imageUris.isEmpty() && fileUris.isEmpty()
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
        /** 按 URI 查真实 MIME；查不到就退回 Intent 声明的类型，仍然判不出来才当作普通文件。 */
        mimeTypeOf: (String) -> String? = { null },
    ): SharedContentIntake? {
        val action = intent?.action ?: return null
        val intake = when (action) {
            Intent.ACTION_SEND -> read(intent, sourceAppLabel, nonce, multiple = false, mimeTypeOf = mimeTypeOf)
            Intent.ACTION_SEND_MULTIPLE -> read(intent, sourceAppLabel, nonce, multiple = true, mimeTypeOf = mimeTypeOf)
            Intent.ACTION_PROCESS_TEXT -> SharedContentIntake(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
                sourceAppLabel = sourceAppLabel,
                nonce = nonce,
            )
            else -> return null
        }
        return intake.takeUnless { it.isEmpty }
    }

    /**
     * 分享进来的附件按 MIME 分流：图片继续走图片通道（有预览、走图像解码上限），
     * 其余一律走文件引用通道，与手动选文件得到同一种引用，不再只认图片。
     */
    private fun read(
        intent: Intent,
        sourceAppLabel: String?,
        nonce: Long,
        multiple: Boolean,
        mimeTypeOf: (String) -> String?,
    ): SharedContentIntake {
        val streams = readStreams(intent, multiple)
        val images = ArrayList<String>()
        val files = ArrayList<String>()
        for (uri in streams) {
            if (isImage(uri, intent.type, mimeTypeOf)) images += uri else files += uri
        }
        return SharedContentIntake(
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
            imageUris = images,
            fileUris = files.take(MAX_SHARED_FILES),
            sourceAppLabel = sourceAppLabel,
            nonce = nonce,
        )
    }

    /**
     * 判不出来时按普通文件处理。
     *
     * 反过来错判成图片的代价更大：图片通道要求能解码，非图片会让 `attachImage` 弹一次读取失败。
     * 而文件通道对图片同样成立，最多是没有缩略图。
     */
    private fun isImage(uri: String, declaredType: String?, mimeTypeOf: (String) -> String?): Boolean {
        val resolved = mimeTypeOf(uri)?.takeIf { it.isNotBlank() }
        val mime = resolved ?: declaredType?.takeIf { it.isNotBlank() }
        if (mime == null || mime == "*/*") return false
        return mime.startsWith("image/")
    }

    /** 只接受 content 与 file 方案：其余方案没有可读授权，收下来也只会在发送时失败。 */
    private fun readStreams(intent: Intent, multiple: Boolean): List<String> = runCatching {
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

/**
 * 单次分享的文件数量上限。
 *
 * 文件会逐个复制进工作区（单个已由导入通道限制在 32MB），不加限制时一次误分享整个相册
 * 就能把磁盘和内存一起打满；超出部分直接丢弃，不排队等待。
 */
internal const val MAX_SHARED_FILES = 10

/**
 * 决定本次启动的预填草稿从哪里来。
 *
 * 冷启动解析分享 Intent；界面重建时只还原已保存内容，因为重建同样带着原始 Intent，
 * 再解析一次等于把同一段内容重复填回输入框。
 *
 * 之所以单独抽成纯函数：这一步在 `setContent` 之前执行，写错不会有任何界面症状，
 * 只有真机冷启动才看得出来，必须能被单测直接覆盖。
 */
internal fun initialSharedIntake(
    savedInstanceState: Bundle?,
    parseFromIntent: () -> SharedContentIntake?,
    restoreFromState: (Bundle) -> SharedContentIntake?,
): SharedContentIntake? = if (savedInstanceState == null) {
    parseFromIntent()
} else {
    restoreFromState(savedInstanceState)
}
