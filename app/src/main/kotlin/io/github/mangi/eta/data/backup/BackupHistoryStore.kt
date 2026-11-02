package io.github.mangi.eta.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 私有目录中自动备份文件的描述。 */
internal data class AutoBackupFile(
    val file: File,
    val createdAt: Long,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/** SAF 双写失败分类；只区分可对用户解释的粗粒度原因。 */
internal enum class SafWriteFailure(val persisted: String) {
    PERMISSION("permission"),
    IO("io"),
    UNKNOWN("unknown");

    companion object {
        fun from(error: Throwable): SafWriteFailure = when (error) {
            is SecurityException -> PERMISSION
            is IOException -> IO
            else -> UNKNOWN
        }
    }
}

/**
 * 自动备份的磁盘层：文件名生成、`.partial` 原子落盘、历史枚举、FIFO 淘汰与 SAF 双写。
 *
 * 所有文件操作集中在这里，Worker 只负责调用；这样淘汰与原子性可以脱离调度逻辑单测。
 */
internal object BackupHistoryStore {
    private const val DIRECTORY_NAME = "auto_backup"
    private const val PARTIAL_SUFFIX = ".partial"
    private const val FILE_PREFIX = "eta-auto-"
    private const val FILE_SUFFIX = ".json"
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

    fun newFileName(timestamp: Long): String =
        "$FILE_PREFIX${fileNameFormat.format(Date(timestamp))}$FILE_SUFFIX"

    /**
     * 把 [write] 产生的内容落到一个新文件：先写 `.partial`，成功后原子重命名。
     *
     * 任何异常都会清理临时文件并原样抛出，已存在的旧备份不受影响。
     */
    suspend fun writeAtomically(
        context: Context,
        fileName: String = newFileName(System.currentTimeMillis()),
        write: suspend (File) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = directory(context).apply {
            if (!exists() && !mkdirs()) throw IOException("无法创建自动备份目录")
        }
        val target = File(directory, fileName)
        val partial = File(directory, "$fileName$PARTIAL_SUFFIX")
        try {
            if (partial.exists() && !partial.delete()) throw IOException("无法清理残留的备份临时文件")
            write(partial)
            currentCoroutineContext().ensureActive()
            // 同一文件系统内的重命名是原子的；失败时旧文件仍然完整。
            if (!partial.renameTo(target)) throw IOException("无法保存自动备份文件")
            target
        } catch (failure: Throwable) {
            partial.delete()
            if (failure is CancellationException) throw failure
            throw failure
        }
    }

    /** 按创建时间从新到旧列出可用的自动备份；`.partial` 与损坏文件不返回。 */
    suspend fun list(context: Context): List<AutoBackupFile> = withContext(Dispatchers.IO) {
        val directory = directory(context)
        if (!directory.isDirectory) return@withContext emptyList()
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .filter { it.length() > 0L }
            .map { AutoBackupFile(it, parseTimestamp(it.name) ?: it.lastModified()) }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    suspend fun delete(context: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        val directory = directory(context)
        val target = File(directory, name)
        // 只允许删除本目录下的自动备份文件，避免路径拼接逃逸。
        if (target.parentFile?.canonicalPath != directory.canonicalPath) return@withContext false
        if (!target.isFile) return@withContext false
        target.delete()
    }

    /** FIFO 淘汰：保留最新 [retention] 份，删除更旧的。返回被删除的文件名。 */
    suspend fun prune(context: Context, retention: Int): List<String> = withContext(Dispatchers.IO) {
        val keep = retention.coerceAtLeast(1)
        val extra = list(context).drop(keep)
        extra.mapNotNull { entry ->
            if (entry.file.delete()) entry.name else null.also {
                AndroidAgentLogger.warn("Agent auto backup prune failed: type=IOException")
            }
        }
    }

    /** 清理崩溃遗留的 `.partial` 文件；应在每次备份开始时调用。 */
    suspend fun clearPartials(context: Context): Int = withContext(Dispatchers.IO) {
        val directory = directory(context)
        if (!directory.isDirectory) return@withContext 0
        directory.listFiles()
            .orEmpty()
            .count { it.isFile && it.name.endsWith(PARTIAL_SUFFIX) && it.delete() }
    }

    /**
     * 把已完成的私有备份复制到用户选定的 SAF 目录。
     *
     * 同名文件会被覆盖；目录授权失效时抛异常，由调用方降级记录，不影响私有目录那份。
     */
    suspend fun copyToSaf(context: Context, source: File, directoryUri: String) = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(directoryUri))
            ?: throw IOException("无法打开备份目录")
        if (!tree.canWrite()) throw SecurityException("备份目录不可写")
        val existing = tree.findFile(source.name)
        val target = existing?.takeIf { it.isFile }?.apply { delete() }
            ?: tree.createFile("application/json", source.name)
            ?: throw IOException("无法创建备份文件")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("无法写入备份文件")
        target
    }

    private fun parseTimestamp(name: String): Long? = runCatching {
        val raw = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        fileNameFormat.parse(raw)?.time
    }.getOrNull()
}
