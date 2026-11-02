package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 自动备份配置与最近一次运行结果。
 *
 * 时间使用设备本地时区的“时:分”，不是固定间隔；调度器据此换算下一次精确闹钟时刻。
 * 运行结果一并保存在这里，使设置页无需访问日志即可显示上次备份是否成功。
 */
@Serializable
internal data class AutoBackupSettings(
    val enabled: Boolean = false,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = DEFAULT_MINUTE,
    val retentionCount: Int = DEFAULT_RETENTION,
    /** SAF 目录凭证；为空表示只写私有目录。 */
    val safDirectoryUri: String? = null,
    /** 上次 SAF 双写失败时刻与原因分类；成功时清空。 */
    val lastSafFailureAt: Long? = null,
    val lastSafFailureKind: String? = null,
    val lastRunAt: Long? = null,
    /** null 表示从未运行过，避免把默认值误显示成“上次成功”。 */
    val lastRunSucceeded: Boolean? = null,
    val lastRunFailureKind: String? = null,
    val lastBackupName: String? = null,
) {
    fun normalized(): AutoBackupSettings = copy(
        hour = hour.coerceIn(0, 23),
        minute = minute.coerceIn(0, 59),
        retentionCount = retentionCount.coerceIn(MIN_RETENTION, MAX_RETENTION),
    )

    fun sanitized(): AutoBackupSettings = normalized().copy(
        safDirectoryUri = safDirectoryUri?.takeIf { it.isNotBlank() },
        lastSafFailureKind = lastSafFailureKind?.takeIf { it.isNotBlank() },
        lastRunFailureKind = lastRunFailureKind?.takeIf { it.isNotBlank() },
        lastBackupName = lastBackupName?.takeIf { it.isNotBlank() },
    )

    companion object {
        const val DEFAULT_HOUR = 3
        const val DEFAULT_MINUTE = 30
        const val DEFAULT_RETENTION = 7
        const val MIN_RETENTION = 3
        const val MAX_RETENTION = 30
    }
}
