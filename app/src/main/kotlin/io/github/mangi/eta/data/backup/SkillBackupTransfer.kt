package io.github.mangi.eta.data.backup

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.skill.BUILTIN_SKILL_SOURCE
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.SkillRegistryEntity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** 备份中的单个技能文件；[path] 为相对技能根目录的路径，`/` 分隔。 */
@Serializable
internal data class SkillBackupFile(
    val path: String,
    val contentBase64: String,
)

/** 备份中的单个技能：元信息加全部文件内容。 */
@Serializable
internal data class SkillBackupRecord(
    val id: String,
    val name: String = "",
    val source: String = "user",
    val enabled: Boolean = true,
    val files: List<SkillBackupFile> = emptyList(),
    /** 体积或安全原因跳过正文时记录原因；这类技能恢复不了内容。 */
    val skippedReason: String? = null,
)

/**
 * 技能目录与备份文档之间的搬运层。
 *
 * 刻意不经过 [SkillRuntime] 的索引服务：那条路径会获取跨进程文件锁并在内部回读数据库，
 * 在导出事务中调用会与 Room 事务互相等待。这里只读 skill_registry 表并直接扫描文件树。
 *
 * 导出跳过内建技能（属于 APK 资产，重装即恢复），只处理用户安装的技能。
 * 恢复是合并式：覆盖同名技能，保留备份中不存在的本地技能，不删除任何东西。
 */
internal object SkillBackupTransfer {
    /** 单个文件上限；超过则整个技能记为跳过，避免一份巨型资源拖垮整包。 */
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
    /** 单个技能所有文件合计上限。 */
    private const val MAX_SKILL_BYTES = 32L * 1024L * 1024L
    private const val SKILL_FILE = "SKILL.md"

    /** 逐条产出用户技能；不把全部技能同时读进内存。 */
    suspend fun exportSkills(context: Context, emit: suspend (SkillBackupRecord) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val root = SkillRuntime.skillsRoot(appContext)
            val registry = runCatching {
                EtaDatabase.get(appContext).skillDao().registryEntries().associateBy { it.skillId }
            }.getOrElse {
                AndroidAgentLogger.warn("Agent skill registry unreadable: type=${it.javaClass.simpleName}")
                emptyMap()
            }
            val directories = root.listFiles()
                .orEmpty()
                .filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .sortedBy { it.name }
            var count = 0
            for (directory in directories) {
                val entry = registry[directory.name]
                // 内建技能随 APK 提供，备份它们没有意义。
                if (entry?.source == BUILTIN_SKILL_SOURCE) continue
                if (!File(directory, SKILL_FILE).isFile) continue
                emit(snapshot(directory, entry))
                count++
            }
            count
        }

    private fun snapshot(directory: File, entry: SkillRegistryEntity?): SkillBackupRecord {
        val base = SkillBackupRecord(
            id = directory.name,
            source = entry?.source ?: "user",
            enabled = entry?.enabled ?: true,
        )
        val files = mutableListOf<SkillBackupFile>()
        var total = 0L
        val rootPath = directory.canonicalPath
        val stack = ArrayDeque<File>().apply { add(directory) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in current.listFiles().orEmpty().sortedBy { it.name }) {
                // 符号链接不跟随，避免把技能目录外的内容打进备份。
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    stack.add(child)
                    continue
                }
                if (!child.isFile) continue
                val relative = child.canonicalPath
                    .removePrefix("$rootPath${File.separator}")
                    .replace(File.separatorChar, '/')
                if (!isSafeRelativePath(relative)) return base.copy(skippedReason = "unsafe_path")
                if (child.length() > MAX_FILE_BYTES) return base.copy(skippedReason = "file_too_large")
                total += child.length()
                if (total > MAX_SKILL_BYTES) return base.copy(skippedReason = "skill_too_large")
                val bytes = runCatching { child.readBytes() }.getOrElse {
                    return base.copy(skippedReason = "unreadable")
                }
                files += SkillBackupFile(
                    path = relative,
                    contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            }
        }
        if (files.none { it.path == SKILL_FILE }) return base.copy(skippedReason = "missing_skill_md")
        return base.copy(files = files)
    }

    /**
     * 合并式恢复单个技能：目录内有则覆盖对应文件，没有则新建目录。
     *
     * 返回是否真正写入；被跳过的技能返回 false 且不改动磁盘。
     */
    suspend fun restoreSkill(context: Context, record: SkillBackupRecord): Boolean =
        withContext(Dispatchers.IO) {
            if (record.skippedReason != null) return@withContext false
            if (!isSafeSkillId(record.id)) return@withContext false
            if (record.files.isEmpty()) return@withContext false
            if (record.files.none { it.path == SKILL_FILE }) return@withContext false
            val root = SkillRuntime.skillsRoot(context.applicationContext)
            val target = File(root, record.id)
            if (target.exists() && java.nio.file.Files.isSymbolicLink(target.toPath())) return@withContext false
            if (!target.isDirectory && !target.mkdirs()) throw IOException("无法创建技能目录：${record.id}")
            val canonicalRoot = target.canonicalPath
            for (file in record.files) {
                if (!isSafeRelativePath(file.path)) continue
                val destination = File(target, file.path)
                if (!destination.canonicalPath.startsWith("$canonicalRoot${File.separator}")) continue
                val bytes = runCatching {
                    Base64.decode(file.contentBase64, Base64.NO_WRAP)
                }.getOrElse {
                    AndroidAgentLogger.warn("Agent skill restore skipped undecodable file: type=${it.javaClass.simpleName}")
                    continue
                }
                destination.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) continue }
                destination.writeBytes(bytes)
            }
            true
        }

    /** 恢复后写回 registry 的开关与来源；内建技能不覆盖，避免破坏 APK 资产记录。 */
    suspend fun restoreRegistry(context: Context, records: List<SkillBackupRecord>) =
        withContext(Dispatchers.IO) {
            if (records.isEmpty()) return@withContext
            val dao = EtaDatabase.get(context.applicationContext).skillDao()
            val existing = runCatching { dao.registryEntries().associateBy { it.skillId } }
                .getOrElse { emptyMap() }
            val upserts = records.mapNotNull { record ->
                if (record.skippedReason != null) return@mapNotNull null
                if (existing[record.id]?.source == BUILTIN_SKILL_SOURCE) return@mapNotNull null
                SkillRegistryEntity(
                    skillId = record.id,
                    enabled = record.enabled,
                    source = record.source,
                    installState = "installed",
                )
            }
            if (upserts.isNotEmpty()) dao.insertRegistryEntries(upserts)
        }

    private fun isSafeSkillId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
