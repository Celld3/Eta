package io.github.mangi.eta.data.backup

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import kotlinx.coroutines.CancellationException

/**
 * 自动备份的启动/恢复入口。
 *
 * 应用冷启动、开机、改时间、换时区、精确闹钟权限变化、覆盖安装都走这里：
 * 先补跑错过的计划点，再重建下一次闹钟。把这两步收在一处，
 * 避免各调用点各自实现而漏掉补跑或漏掉重排。
 */
internal object AutoBackupBootstrap {

    /**
     * 同步一次备份状态。
     *
     * 补跑与重排互不阻塞：补跑交给 WorkManager（KEEP 去重），
     * 重排是纯 AlarmManager 操作，即使补跑排队中也应立即生效。
     */
    suspend fun reconcile(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val overdue = AutoBackupScheduler.overdueSlot(appContext)
            if (overdue != null) {
                AndroidAgentLogger.info(
                    "Agent auto backup catch-up enqueued: overdueByMs=${System.currentTimeMillis() - overdue}",
                )
                AutoBackupWorker.enqueue(appContext, reason = "catch_up")
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            AndroidAgentLogger.warn(
                "Agent auto backup catch-up failed: type=${failure.safeLogType()}",
            )
        }
        runCatching {
            AutoBackupScheduler.schedule(appContext)
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            AndroidAgentLogger.warn(
                "Agent auto backup reschedule failed: type=${failure.safeLogType()}",
            )
        }
    }
}
