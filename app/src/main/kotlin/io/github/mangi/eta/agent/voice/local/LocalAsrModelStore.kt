package io.github.mangi.eta.agent.voice.local

import android.content.Context
import io.github.mangi.eta.agent.terminal.VerifiedArtifact
import io.github.mangi.eta.agent.terminal.VerifiedArtifactDownloader
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import kotlin.coroutines.coroutineContext

/** 模型在磁盘上的状态；界面只据此决定显示下载、就绪还是重试。 */
internal sealed interface LocalAsrModelState {
    /** 尚未下载，或下载后未通过校验。 */
    data object Absent : LocalAsrModelState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : LocalAsrModelState {
        val fraction: Float get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes)
    }

    /** 归档已下载并通过摘要校验，正在解压。 */
    data object Unpacking : LocalAsrModelState

    /** 全部文件就位，可以加载。 */
    data class Ready(val sizeBytes: Long) : LocalAsrModelState

    data class Failed(val message: String) : LocalAsrModelState
}

/**
 * 模型的落盘位置、校验、下载与解压。
 *
 * 归档先下载到缓存目录，按清单里的大小与 SHA-256 验收通过后才解压到模型目录，
 * 解压结束写一个就绪标记；标记与必需文件同时存在才算可用，因此中途被杀不会留下
 * 「看着有文件其实残缺」的状态。导入与下载走同一套落盘流程，区别只是归档从哪来。
 */
internal class LocalAsrModelStore(
    private val context: Context,
    private val downloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
) {
    private val model: LocalAsrModel get() = LocalAsrModels.active

    /** 模型目录，位于 App 私有空间，不需要额外权限。 */
    fun modelDirectory(): File = File(context.filesDir, "local-asr/${model.directoryName}")

    private fun markerFile(): File = File(modelDirectory(), READY_MARKER)

    private fun archiveFile(): File = File(context.cacheDir, "local-asr/${model.fileName}")

    /** 已就绪时返回模型目录，否则返回 null；只在必需文件齐全时才算就绪。 */
    fun readyDirectory(): File? {
        val root = modelDirectory()
        if (!markerFile().isFile) return null
        val missing = model.requiredFiles.any { !File(root, it).isFile }
        if (missing) return null
        return root
    }

    fun isReady(): Boolean = readyDirectory() != null

    /** 已占用的磁盘体积，用于设置页显示与删除。 */
    fun installedBytes(): Long {
        val root = modelDirectory()
        if (!root.isDirectory) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 下载归档并解压。进度先报下载，再报解压。 */
    suspend fun download(onState: (LocalAsrModelState) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val archive = archiveFile()
        val artifact = VerifiedArtifact(
            id = model.id,
            version = model.directoryName,
            fileName = model.fileName,
            url = model.url,
            sha256 = model.sha256,
            sizeBytes = model.sizeBytes,
            // 官方地址在大陆经常只有几 KB/s，镜像放在前面先试；校验仍按官方摘要，
            // 所以镜像给错文件会被直接拒掉。
            preferredUrls = MIRRORS.map { it + model.url },
        )
        onState(LocalAsrModelState.Downloading(0L, model.sizeBytes))
        val downloaded = try {
            downloader.download(artifact, archive) { done, total ->
                onState(LocalAsrModelState.Downloading(done, total))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AndroidAgentLogger.warn("Local ASR model download failed: ${error.javaClass.simpleName}")
            onState(LocalAsrModelState.Failed(DOWNLOAD_FAILED))
            return@withContext false
        }
        if (!downloaded) {
            onState(LocalAsrModelState.Failed(DOWNLOAD_FAILED))
            return@withContext false
        }
        onState(LocalAsrModelState.Unpacking)
        val unpacked = try {
            unpack(archive)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AndroidAgentLogger.warn("Local ASR model unpack failed: ${error.javaClass.simpleName}")
            false
        } finally {
            archive.delete()
        }
        if (!unpacked) {
            onState(LocalAsrModelState.Failed(UNPACK_FAILED))
            return@withContext false
        }
        onState(LocalAsrModelState.Ready(installedBytes()))
        true
    }

    /** 删除模型目录；下载中的归档一并清掉。 */
    suspend fun delete(): Boolean = withContext(Dispatchers.IO) {
        markerFile().delete()
        val removed = modelDirectory().deleteRecursively()
        archiveFile().delete()
        AndroidAgentLogger.info("Local ASR model deleted: ${model.id}")
        removed
    }

    /**
     * 解压 tar.bz2。归档里通常套一层同名目录，这里按相对路径剥离掉最外层，
     * 只保留模型文件本身，避免落盘层级随归档来源变化。
     */
    private suspend fun unpack(archive: File): Boolean {
        if (!archive.isFile || archive.length() == 0L) return false
        val root = modelDirectory()
        root.deleteRecursively()
        root.mkdirs()
        val pending = File(context.cacheDir, "local-asr-unpack-${System.currentTimeMillis()}")
        pending.mkdirs()
        try {
            BufferedInputStream(archive.inputStream()).use { raw ->
                BZip2CompressorInputStream(raw).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val entry = tar.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val relative = stripLeadingDirectory(entry.name)
                            // 只接受模型需要的文件，避免归档里夹带无关内容。
                            if (relative !in model.requiredFiles) continue
                            val target = File(pending, relative)
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> tar.copyTo(out, DEFAULT_BUFFER) }
                        }
                    }
                }
            }
            val missing = model.requiredFiles.filter { !File(pending, it).isFile }
            if (missing.isNotEmpty()) {
                AndroidAgentLogger.warn("Local ASR model unpack missing ${missing.size} required files")
                return false
            }
            pending.listFiles()?.forEach { it.renameTo(File(root, it.name)) }
            markerFile().writeText(model.id)
            AndroidAgentLogger.info("Local ASR model ready: ${model.id} bytes=${installedBytes()}")
            return true
        } finally {
            pending.deleteRecursively()
        }
    }

    /** 去掉归档最外层目录：`name/encoder.int8.onnx` -> `encoder.int8.onnx`。 */
    private fun stripLeadingDirectory(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        val slash = normalized.indexOf('/')
        return if (slash < 0) normalized else normalized.substring(slash + 1)
    }

    companion object {
        private const val READY_MARKER = ".ready"
        private const val DEFAULT_BUFFER = 64 * 1024

        /** GitHub 直连在部分网络下极慢，镜像先试；摘要校验兜底，镜像不可信也没关系。 */
        private val MIRRORS = listOf(
            "https://gh-proxy.com/",
            "https://ghproxy.cn/",
        )

        const val DOWNLOAD_FAILED = "模型下载失败，请检查网络后重试"
        const val UNPACK_FAILED = "模型解压失败，归档可能已损坏，请重新下载"
    }
}
