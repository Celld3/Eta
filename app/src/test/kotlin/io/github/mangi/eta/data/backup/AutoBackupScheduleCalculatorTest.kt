package io.github.mangi.eta.data.backup

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动备份排期的时间计算测试。
 *
 * 所有用例都注入固定 `now`，不依赖系统时钟；覆盖三类曾经的线上问题：
 * 1. 闹钟被 ROM 吞掉后静默停摆（需要补跑）；
 * 2. 一天跑出两份（早触发后重排回当天同一时刻）；
 * 3. 无精确授权时计划点被彻底忽略。
 */
class AutoBackupScheduleCalculatorTest {

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.NOVEMBER)
            set(Calendar.DAY_OF_MONTH, 28)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun nextTriggerAt_rollsToTomorrow_whenSlotAlreadyPassed() {
        val now = at(13, 0)
        val next = AutoBackupScheduleCalculator.nextTriggerAt(now, 12, 5)
        assertEquals(at(12, 5) + 24 * 60 * 60 * 1000L, next)
    }

    @Test
    fun nextTriggerAt_keepsTodaySlot_whenStillAhead() {
        val now = at(11, 0)
        val next = AutoBackupScheduleCalculator.nextTriggerAt(now, 12, 5)
        assertEquals(at(12, 5), next)
    }

    @Test
    fun nextTriggerAtAfterRun_skipsTodaySlot_whenRunConsumedIt() {
        // 12:03 跑完（系统在窗口内提前投递），计划点是 12:05。
        val now = at(12, 3)
        val next = AutoBackupScheduleCalculator.nextTriggerAtAfterRun(
            now = now,
            hour = 12,
            minute = 5,
            lastRunAt = now,
        )
        // 必须排到明天，而不是当天 12:05——这就是“一天两份”的成因。
        assertEquals(at(12, 5) + 24 * 60 * 60 * 1000L, next)
    }

    @Test
    fun nextTriggerAtAfterRun_keepsTodaySlot_whenLastRunWasYesterday() {
        val now = at(12, 3)
        val yesterday = at(12, 3) - 24 * 60 * 60 * 1000L
        val next = AutoBackupScheduleCalculator.nextTriggerAtAfterRun(
            now = now,
            hour = 12,
            minute = 5,
            lastRunAt = yesterday,
        )
        assertEquals(at(12, 5), next)
    }

    @Test
    fun nextTriggerAtAfterRun_keepsTodaySlot_whenNeverRun() {
        val now = at(11, 0)
        val next = AutoBackupScheduleCalculator.nextTriggerAtAfterRun(now, 12, 5, lastRunAt = null)
        assertEquals(at(12, 5), next)
    }

    @Test
    fun needsCatchUp_true_whenSlotMissedAndBeyondTolerance() {
        // 今天 12:05 的计划点没跑，现在 15:00，超过 2 小时容差。
        val now = at(15, 0)
        assertTrue(
            AutoBackupScheduleCalculator.needsCatchUp(
                now = now,
                hour = 12,
                minute = 5,
                lastRunAt = at(12, 3) - 24 * 60 * 60 * 1000L,
            ),
        )
    }

    @Test
    fun needsCatchUp_false_whenSlotAlreadyRan() {
        val now = at(15, 0)
        assertFalse(
            AutoBackupScheduleCalculator.needsCatchUp(
                now = now,
                hour = 12,
                minute = 5,
                lastRunAt = at(12, 3),
            ),
        )
    }

    @Test
    fun needsCatchUp_false_withinTolerance() {
        // 刚过 12:05 五分钟，属于正常延迟，不算错过。
        val now = at(12, 10)
        assertFalse(
            AutoBackupScheduleCalculator.needsCatchUp(
                now = now,
                hour = 12,
                minute = 5,
                lastRunAt = null,
            ),
        )
    }

    @Test
    fun needsCatchUp_true_whenNeverRunAndSlotLongPast() {
        val now = at(20, 0)
        assertTrue(
            AutoBackupScheduleCalculator.needsCatchUp(
                now = now,
                hour = 12,
                minute = 5,
                lastRunAt = null,
            ),
        )
    }

    @Test
    fun lastSlotAtOrBefore_returnsYesterday_whenTodaySlotAhead() {
        val now = at(9, 0)
        assertEquals(
            at(12, 5) - 24 * 60 * 60 * 1000L,
            AutoBackupScheduleCalculator.lastSlotAtOrBefore(now, 12, 5),
        )
    }
}
