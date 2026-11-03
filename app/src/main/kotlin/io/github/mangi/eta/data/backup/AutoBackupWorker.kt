package io.github.mangi.eta.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.EtaBackupRepository
import kotlinx.coroutines.CancellationException

/**
 * 自动备份的执行者：导出 → 原子落盘 → FIFO 淘汰 → SAF 双写 → 重排下一次闹钟。
 *
 * 任何失败都只记录状态并返回 success，避免 WorkManager 反复重试造成写盘风暴；
 * 需要重试的情形由下一次定时触发覆盖。
 */
internal class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = runCatching { SettingsDataStore.autoBackupSettings() }.getOrElse {
            AndroidAgentLogger.warn("Agent auto backup settings unavailable: type=${it.safeLogType()}")
            return Result.success()
        }
        if (!settings.enabled) {
            AutoBackupScheduler.cancel(applicationContext)
            return Result.success()
        }

        var backupName: String? = null
        try {
            BackupHistoryStore.clearPartials(applicationContext)
            val file = BackupHistoryStore.writeAtomically(applicationContext) { partial ->
                partial.outputStream().buffered().use { output ->
                    EtaBackupRepository.export(applicationContext, output)
                }
            }
            backupName = file.name
            BackupHistoryStore.prune(applicationContext, settings.retentionCount)
            AutoBackupScheduler.recordResult(applicationContext, succeeded = true, failureKind = null, backupName = backupName)
            syncToSaf(file, settings)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AndroidAgentLogger.error(
                "Agent auto backup failed: type=${failure.safeLogType()}\n${failure.stackTraceToString().take(2000)}"
            )
            AutoBackupScheduler.recordResult(
                applicationContext,
                succeeded = false,
                failureKind = failure.javaClass.simpleName,
                backupName = null,
            )
        } finally {
            // 无论成败都要排下一次，否则一次失败就会永久停摆。
            runCatching { AutoBackupScheduler.schedule(applicationContext) }
        }
        return Result.success()
    }

    private suspend fun syncToSaf(
        file: java.io.File,
        settings: io.github.mangi.eta.data.model.AutoBackupSettings,
    ) {
        val directoryUri = settings.safDirectoryUri ?: return
        try {
            BackupHistoryStore.copyToSaf(applicationContext, file, directoryUri)
            AutoBackupScheduler.recordSafResult(applicationContext, null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // SAF 失败不影响私有目录那份，只记录状态供设置页提示。
            AutoBackupScheduler.recordSafResult(applicationContext, SafWriteFailure.from(failure))
        }
    }

    companion object {
        private const val WORK_NAME = "eta-auto-backup"
        private const val KEY_REASON = "reason"

        /** 以 KEEP 策略投递，避免闹钟与开机重排在短时间内重复备份。 */
        fun enqueue(context: Context, reason: String) {
            val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setInputData(workDataOf(KEY_REASON to reason))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
