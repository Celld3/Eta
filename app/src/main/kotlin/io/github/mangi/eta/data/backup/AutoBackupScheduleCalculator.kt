package io.github.mangi.eta.data.backup

import androidx.annotation.VisibleForTesting
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 计算“每天 [hour]:[minute]”的下一次触发时刻。
 *
 * Android 上精确闹钟的延迟不可控，因此这里只给调度器提供纯时间计算，便于单测。
 * 所有函数都不读系统时钟，`now` 由调用方注入。
 */
internal object AutoBackupScheduleCalculator {
    /**
     * 同一天内认定“这个计划点已经被消费过”的容差。
     *
     * 精确闹钟带窗口、系统也可能提前投递，实测会在设定时刻前几分钟就触发；
     * 若只比较 `nextTriggerAt(now)`，刚跑完那次会把当天同一个点再排一遍，形成成对备份。
     */
    @VisibleForTesting
    internal val CONSUMED_GRACE_MS = TimeUnit.MINUTES.toMillis(30)

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

    /**
     * 备份跑完后的重排：当天那个点若已在容差内跑过，直接排到明天。
     *
     * 这是“一天两份”的直接修复点——早触发的那次不会再把当天同一个时刻排一遍。
     */
    fun nextTriggerAtAfterRun(
        now: Long,
        hour: Int,
        minute: Int,
        lastRunAt: Long?,
    ): Long {
        val candidate = nextTriggerAt(now, hour, minute)
        if (lastRunAt == null) return candidate
        val consumed = lastRunAt >= candidate - CONSUMED_GRACE_MS && lastRunAt <= now
        if (!consumed) return candidate
        // candidate 是今天（或正好现在之后）那个点，已消费则推一天。
        return nextTriggerAt(candidate, hour, minute)
    }

    /** 不晚于 [now] 的最近一个计划时刻；用于判断“今天这个点是否已经错过”。 */
    fun lastSlotAtOrBefore(now: Long, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis > now) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return calendar.timeInMillis
    }

    /**
     * 是否需要补跑一次。
     *
     * 闹钟被 ROM 吞掉、应用被强停清空队列、或设备长时间关机后，
     * 计划点会静默错过；这里给出“错过且超过容差”的判定，由调度器补跑一次。
     */
    fun isOverdue(expectedAt: Long, now: Long, tolerance: Long = TimeUnit.HOURS.toMillis(2)): Boolean =
        now > expectedAt + tolerance

    /**
     * 结合最近一次运行结果判断是否需要补跑：从未跑过、或已错过最新计划点且超过容差。
     */
    fun needsCatchUp(
        now: Long,
        hour: Int,
        minute: Int,
        lastRunAt: Long?,
        tolerance: Long = TimeUnit.HOURS.toMillis(2),
    ): Boolean {
        val slot = lastSlotAtOrBefore(now, hour, minute)
        if (lastRunAt == null) {
            // 从未跑过：只要这个计划点已经过去且超过容差，就补一次。
            return isOverdue(slot, now, tolerance)
        }
        // 上次运行早于最近计划点，说明这个点没跑。
        return lastRunAt < slot - CONSUMED_GRACE_MS && isOverdue(slot, now, tolerance)
    }
}
