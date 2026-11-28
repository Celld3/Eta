package io.github.mangi.eta.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AutoBackupSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按“每天若干时:分”安排自动备份。
 *
 * 布局对齐上游 4.0.0 的 `ScheduledTaskAlarmScheduler`：只向系统注册**一个**闹钟，
 * 由它把进程叫醒并立刻投递一次 [AutoBackupWorker]；真正的备份耗时不可控，
 * 放在 Worker 里才不会被 BroadcastReceiver 的生命周期掐断。
 *
 * 与旧实现的两处关键差异：
 * 1. 没有精确闹钟授权时不再用 `now + 12h` 这种与用户设置无关的间隔反复漂移，
 *    而是按用户设定的时刻排非精确闹钟，让系统只在窗口内推迟，不改变计划点；
 * 2. 排下一次之前先用 [AutoBackupScheduleCalculator.nextTriggerAtAfterRun] 判断
 *    当天那个点是否已被消费，避免同一天跑两次。
 */
internal object AutoBackupScheduler {
    const val ACTION_ALARM = "io.github.mangi.eta.action.AUTO_BACKUP_ALARM"
    private const val REQUEST_CODE = 4711

    /** 系统 AlarmManager 的最小提前量，确保刚过期的待执行点也能立即触发。 */
    private const val MIN_FUTURITY_MS = 5_000L

    /** 权限状态，供设置页提示用户。 */
    internal enum class Exactness { EXACT, INEXACT }

    /**
     * 排下一次自动备份。
     *
     * [afterRun] 为真表示由 [AutoBackupWorker] 在跑完后重排，此时需要参考
     * `lastRunAt` 判断当天计划点是否已被这次运行消费。
     */
    suspend fun schedule(context: Context, afterRun: Boolean = false): AutoBackupSettings =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val settings = SettingsDataStore.autoBackupSettings()
            val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            if (alarmManager == null || !settings.enabled) {
                cancel(appContext)
                return@withContext settings
            }
            val now = System.currentTimeMillis()
            val triggerAt = if (afterRun) {
                AutoBackupScheduleCalculator.nextTriggerAtAfterRun(
                    now = now,
                    hour = settings.hour,
                    minute = settings.minute,
                    lastRunAt = settings.lastRunAt,
                )
            } else {
                AutoBackupScheduleCalculator.nextTriggerAt(now, settings.hour, settings.minute)
            }.coerceAtLeast(now + MIN_FUTURITY_MS)

            val pendingIntent = pendingIntent(appContext)
            val canExact = canScheduleExact(alarmManager)
            try {
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    // 无精确授权时仍按计划点排，只是系统会在窗口内推迟；
                    // 不再用固定 12 小时间隔，避免用户设的时间被彻底忽略。
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
            } catch (security: SecurityException) {
                // 精确闹钟权限可能在并发瞬间被撤销，降级为非精确唤醒并记录日志。
                AndroidAgentLogger.warn(
                    "Agent auto backup exact alarm denied, falling back: type=${security.javaClass.simpleName}",
                )
                runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
            } catch (failure: RuntimeException) {
                AndroidAgentLogger.warn(
                    "Agent auto backup schedule failed: type=${failure.javaClass.simpleName}",
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

    /**
     * 下一次触发时刻。
     *
     * 无精确授权时不再返回 null：计划点依然成立，只是系统可能推迟，
     * 设置页照常显示“下次”比显示上次结果更有用。
     */
    suspend fun nextTriggerAt(context: Context): Long? = withContext(Dispatchers.IO) {
        val settings = SettingsDataStore.autoBackupSettings()
        if (!settings.enabled) return@withContext null
        AutoBackupScheduleCalculator.nextTriggerAt(
            System.currentTimeMillis(), settings.hour, settings.minute,
        )
    }

    /**
     * 检查是否错过了计划点，返回需要补跑的时刻；不需要补跑时返回 null。
     *
     * 由 [AutoBackupBootstrap] 在应用启动、开机、改时间后调用，
     * 覆盖“闹钟被 ROM 吞掉 / 应用被强停 / 长时间关机”这几种静默错过。
     */
    suspend fun overdueSlot(context: Context): Long? = withContext(Dispatchers.IO) {
        val settings = SettingsDataStore.autoBackupSettings()
        if (!settings.enabled) return@withContext null
        val now = System.currentTimeMillis()
        if (AutoBackupScheduleCalculator.needsCatchUp(
                now = now,
                hour = settings.hour,
                minute = settings.minute,
                lastRunAt = settings.lastRunAt,
            )
        ) {
            AutoBackupScheduleCalculator.lastSlotAtOrBefore(now, settings.hour, settings.minute)
        } else {
            null
        }
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
            AndroidAgentLogger.warn(
                "Agent auto backup result persist failed: type=${it.javaClass.simpleName}",
            )
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
