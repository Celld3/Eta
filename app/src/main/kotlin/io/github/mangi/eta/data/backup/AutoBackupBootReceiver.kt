package io.github.mangi.eta.data.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机后重建自动备份闹钟。
 *
 * 设备的 AlarmManager 队列不跨重启保留，没有这个接收者时，
 * 用户重启一次手机后自动备份会永久停摆且没有任何提示。
 */
internal class AutoBackupBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AutoBackupScheduler.schedule(appContext)
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn(
                    "Agent auto backup reschedule failed: type=${failure.javaClass.simpleName}"
                )
            } finally {
                pending.finish()
            }
        }
    }
}
