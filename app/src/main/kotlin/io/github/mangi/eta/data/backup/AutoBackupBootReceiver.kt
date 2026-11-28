package io.github.mangi.eta.data.backup

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机、覆盖安装、改时间、换时区、精确闹钟权限变化后重建自动备份。
 *
 * 设备的 AlarmManager 队列不跨重启保留，闹钟也可能被 ROM 清掉；
 * 没有这个接收者时，用户重启一次手机后自动备份会永久停摆且没有任何提示。
 * 改时间与换时区必须重算，否则“每天 12:05”会按旧时区继续排。
 */
internal class AutoBackupBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        receiverScope.launch {
            try {
                AutoBackupBootstrap.reconcile(appContext)
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn(
                    "Agent auto backup reconcile failed: type=${failure.javaClass.simpleName}"
                )
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
