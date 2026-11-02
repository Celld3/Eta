package io.github.mangi.eta.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AutoBackupSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按“每天若干时:分”安排自动备份。
 *
 * 精确闹钟只负责把进程叫醒并立刻投递一次 [AutoBackupWorker]；
 * 真正的备份耗时不可控，放在 Worker 里才不会被 BroadcastReceiver 的生命周期掐断。
 */
internal object AutoBackupScheduler {
    const val ACTION_ALARM = "io.github.mangi.eta.action.AUTO_BACKUP_ALARM"
    private const val REQUEST_CODE = 4711
    /** 精确闹钟不可用时的近似周期。 */
    private val DEGRADED_INTERVAL = TimeUnit.HOURS.toMillis(12)

    /** 权限状态，供设置页提示用户。 */
    internal enum class Exactness { EXACT, INEXACT }

    suspend fun schedule(context: Context): AutoBackupSettings = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val settings = SettingsDataStore.autoBackupSettings()
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        if (alarmManager == null || !settings.enabled) {
            cancel(appContext)
            return@withContext settings
        }
        val now = System.currentTimeMillis()
        if (canScheduleExact(alarmManager)) {
            val triggerAt = AutoBackupScheduleCalculator.nextTriggerAt(now, settings.hour, settings.minute)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(appContext))
        } else {
            // 没有精确闹钟授权时退化为不精确唤醒，调度器仍会重排，只是时刻会漂移。
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                now + DEGRADED_INTERVAL,
                pendingIntent(appContext),
            )
        }
        settings
    }

    suspend fun cancel(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        appContext.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(appContext))
        Unit
    }

    suspend fun exactness(context: Context): Exactness = withContext(Dispatchers.IO) {
        val alarmManager = context.applicationContext.getSystemService(AlarmManager::class.java)
        if (alarmManager != null && canScheduleExact(alarmManager)) Exactness.EXACT else Exactness.INEXACT
    }

    /** 下一次触发时刻；对不精确模式返回 null，避免 UI 显示无法保证的时间。 */
    suspend fun nextTriggerAt(context: Context): Long? = withContext(Dispatchers.IO) {
        val settings = SettingsDataStore.autoBackupSettings()
        if (!settings.enabled) return@withContext null
        val alarmManager = context.applicationContext.getSystemService(AlarmManager::class.java)
            ?: return@withContext null
        if (!canScheduleExact(alarmManager)) return@withContext null
        AutoBackupScheduleCalculator.nextTriggerAt(
            System.currentTimeMillis(), settings.hour, settings.minute,
        )
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutoBackupAlarmReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 记录一次运行结果，供设置页展示；不抛异常以免影响备份本身。 */
    suspend fun recordResult(
        context: Context,
        succeeded: Boolean,
        failureKind: String?,
        backupName: String?,
    ) {
        runCatching {
            SettingsDataStore.setAutoBackupSettings { settings ->
                settings.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastRunSucceeded = succeeded,
                    lastRunFailureKind = failureKind,
                    lastBackupName = backupName ?: settings.lastBackupName,
                )
            }
        }.onFailure {
            AndroidAgentLogger.warn("Agent auto backup result persist failed: type=${it.javaClass.simpleName}")
        }
    }

    suspend fun recordSafResult(context: Context, failure: SafWriteFailure?) {
        runCatching {
            SettingsDataStore.setAutoBackupSettings { settings ->
                if (failure == null) {
                    settings.copy(lastSafFailureAt = null, lastSafFailureKind = null)
                } else {
                    settings.copy(
                        lastSafFailureAt = System.currentTimeMillis(),
                        lastSafFailureKind = failure.persisted,
                    )
                }
            }
        }
    }
}
