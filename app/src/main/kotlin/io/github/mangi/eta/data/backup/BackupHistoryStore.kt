package io.github.mangi.eta.data.backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
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

/** SAF 目录中的自动备份条目；只能通过 [BackupHistoryStore.listSaf] 取得。 */
internal data class SafBackupEntry(
    val uri: Uri,
    val name: String,
    val createdAt: Long,
)

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
 * 自动备份的磁盘层：文件名生成、`.partial` 原子落盘、历史枚举、淘汰与 SAF 双写。
 *
 * 淘汰策略参照 Seedvault 的做法：保留份数由设置决定，但至少保留 [MIN_KEEP] 份，
 * 且私有目录与 SAF 目录各自独立淘汰，避免两边互相误删。
 */
internal object BackupHistoryStore {
    private const val DIRECTORY_NAME = "auto_backup"
    private const val PARTIAL_SUFFIX = ".partial"
    private const val FILE_PREFIX = "eta-auto-"
    private const val FILE_SUFFIX = ".json"

    /** 无论用户设成多少，至少留一份，否则自动备份失去意义。 */
    const val MIN_KEEP = 1

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

    /** FIFO 淘汰私有目录：保留最新 [retention] 份，删除更旧的。返回被删除的文件名。 */
    suspend fun prune(context: Context, retention: Int): List<String> = withContext(Dispatchers.IO) {
        val extra = list(context).drop(retention.coerceAtLeast(MIN_KEEP))
        extra.mapNotNull { entry ->
            if (entry.file.delete()) entry.name else null.also {
                AndroidAgentLogger.warn("Agent auto backup prune failed: type=IOException")
            }
        }
    }

    /**
     * 按创建时间从新到旧列出 SAF 目录里的自动备份。
     *
     * 目录授权失效时返回空列表而不抛异常：淘汰是收尾动作，不能让它连累已经成功的备份。
     */
    suspend fun listSaf(context: Context, directoryUri: String): List<SafBackupEntry> =
        withContext(Dispatchers.IO) {
            val tree = DocumentFileCompat.fromTreeUri(context, Uri.parse(directoryUri))
                ?: return@withContext emptyList()
            runCatching { tree.listBackups() }
                .onFailure {
                    AndroidAgentLogger.warn("Agent auto backup SAF list failed: type=${it.javaClass.simpleName}")
                }
                .getOrDefault(emptyList())
                .sortedByDescending { it.createdAt }
        }

    /** FIFO 淘汰 SAF 目录，规则与私有目录一致；返回被删除的条目名。 */
    suspend fun pruneSaf(context: Context, directoryUri: String, retention: Int): List<String> =
        withContext(Dispatchers.IO) {
            val keep = retention.coerceAtLeast(MIN_KEEP)
            val extra = listSaf(context, directoryUri).drop(keep)
            if (extra.isEmpty()) return@withContext emptyList()
            val tree = DocumentFileCompat.fromTreeUri(context, Uri.parse(directoryUri))
                ?: return@withContext emptyList()
            extra.mapNotNull { entry ->
                if (runCatching { tree.delete(entry.uri) }.getOrDefault(false)) {
                    entry.name
                } else {
                    AndroidAgentLogger.warn("Agent auto backup SAF prune failed: type=IOException")
                    null
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
        val tree = DocumentFileCompat.fromTreeUri(context, Uri.parse(directoryUri))
            ?: throw IOException("无法打开备份目录")
        val target = tree.createOrReplace(source.name)
        context.contentResolver.openOutputStream(target, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("无法写入备份文件")
    }

    private fun parseTimestamp(name: String): Long? = runCatching {
        val raw = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        fileNameFormat.parse(raw)?.time
    }.getOrNull()

    /** 供 SAF 淘汰复用：只有符合自动备份命名的条目才参与淘汰，用户手动放进去的文件不动。 */
    internal fun isAutoBackupName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)
}

/**
 * SAF 目录的最小封装。
 *
 * 项目未引入 documentfile 依赖，这里只用 ContentResolver 完成查找、创建与删除，
 * 避免为了一个目录操作拉入整个 AndroidX 模块。
 */
internal class SafDirectory(
    private val resolver: android.content.ContentResolver,
    private val treeUri: Uri,
) {
    /** 覆盖写入同名文件：先删除已存在的同目录同条目，再新建。 */
    fun createOrReplace(name: String): Uri {
        findByDisplayName(name)?.let { uri ->
            runCatching { resolver.delete(uri, null, null) }
        }
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return DocumentsContract.createDocument(
            resolver,
            documentUri,
            "application/json",
            name,
        ) ?: throw IOException("无法在备份目录创建文件")
    }

    /**
     * 列出目录中的自动备份条目。
     *
     * 查询 `LAST_MODIFIED` 以便在文件名解析失败时仍能排序；部分 DocumentsProvider
     * 不返回该列时退回 0，排序交给文件名时间戳。
     */
    fun listBackups(): List<SafBackupEntry> {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val entries = mutableListOf<SafBackupEntry>()
        queryChildren(children, projection) { cursor ->
            val documentId = cursor.getString(0) ?: return@queryChildren
            val name = cursor.getString(1) ?: return@queryChildren
            if (!BackupHistoryStore.isAutoBackupName(name)) return@queryChildren
            val modified = if (cursor.isNull(2)) 0L else cursor.getLong(2)
            entries += SafBackupEntry(
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                name = name,
                createdAt = parseNameTimestamp(name) ?: modified,
            )
        }
        return entries
    }

    fun delete(uri: Uri): Boolean = runCatching {
        resolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    private fun findByDisplayName(name: String): Uri? {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        var found: Uri? = null
        queryChildren(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
        ) { cursor ->
            if (found == null && cursor.getString(1) == name) {
                found = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
            }
        }
        return found
    }

    /**
     * 逐行读取子条目。
     *
     * 投影列在部分 Provider 上可能缺失，索引访问统一走 [safeIndex]，
     * 避免某一列为空时整个淘汰流程中断。
     */
    private inline fun queryChildren(
        childrenUri: Uri,
        projection: Array<String>,
        onRow: (Cursor) -> Unit,
    ) {
        val cursor = runCatching {
            resolver.query(childrenUri, projection, null, null, null)
        }.getOrNull() ?: return
        cursor.use {
            while (it.moveToNext()) {
                runCatching { onRow(it) }.onFailure { failure ->
                    AndroidAgentLogger.warn(
                        "Agent auto backup SAF row skipped: type=${failure.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun parseNameTimestamp(name: String): Long? = runCatching {
        val raw = name.removePrefix("eta-auto-").removeSuffix(".json")
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(raw)?.time
    }.getOrNull()
}

internal object DocumentFileCompat {
    fun fromTreeUri(context: android.content.Context, uri: Uri): SafDirectory? =
        runCatching { SafDirectory(context.contentResolver, uri) }.getOrNull()
}
