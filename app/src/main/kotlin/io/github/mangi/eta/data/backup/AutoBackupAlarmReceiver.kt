package io.github.mangi.eta.data.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 精确闹钟的接收者。
 *
 * 刻意不在这里做任何备份工作：BroadcastReceiver 只有十秒左右的存活窗口，
 * 导出与写盘必然被系统掐断。这里只投递 [AutoBackupWorker] 就返回。
 */
internal class AutoBackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoBackupScheduler.ACTION_ALARM) return
        runCatching { AutoBackupWorker.enqueue(context, reason = "alarm") }
            .onFailure {
                AndroidAgentLogger.warn("Agent auto backup enqueue failed: type=${it.javaClass.simpleName}")
            }
    }
}
