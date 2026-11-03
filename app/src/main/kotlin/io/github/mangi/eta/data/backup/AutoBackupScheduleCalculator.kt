package io.github.mangi.eta.data.backup

import androidx.annotation.VisibleForTesting
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 计算“每天 [hour]:[minute]”的下一次触发时刻。
 *
 * Android 上精确闹钟的延迟不可控，因此这里只给调度器提供纯时间计算，便于单测。
 */
internal object AutoBackupScheduleCalculator {
    /** 返回严格晚于 [now] 的下一次触发时刻（毫秒）。 */
    fun nextTriggerAt(now: Long, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /** 闹钟被系统丢弃后的兜底窗口：下次触发时刻过久时，WorkManager 近似补齐。 */
    @VisibleForTesting
    fun isOverdue(expectedAt: Long, now: Long, tolerance: Long = TimeUnit.HOURS.toMillis(2)): Boolean =
        now > expectedAt + tolerance
}
