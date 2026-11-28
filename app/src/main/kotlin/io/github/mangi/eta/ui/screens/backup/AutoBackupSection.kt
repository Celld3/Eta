package io.github.mangi.eta.ui.screens.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.backup.AutoBackupFile
import io.github.mangi.eta.data.model.AutoBackupSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class AutoBackupUiState(
    val settings: AutoBackupSettings = AutoBackupSettings(),
    val nextTriggerAt: Long? = null,
    val exactAllowed: Boolean = true,
    val history: List<AutoBackupFile> = emptyList(),
    val busy: Boolean = false,
)

/**
 * 自动备份设置与历史分组。
 *
 * 历史条目点按后由父组件弹确认框，避免在这一层嵌入恢复/删除的业务判断。
 * 4.0.0 统一收口到 Miuix，只用 SmallTitle + Card + 原生 Preference 一套布局。
 */
@Composable
internal fun AutoBackupSection(
    state: AutoBackupUiState,
    onToggle: (Boolean) -> Unit,
    onPickTime: () -> Unit,
    onPickRetention: () -> Unit,
    onPickDirectory: (String?) -> Unit,
    onRequestExactAlarm: () -> Unit,
    onRunNow: () -> Unit,
    onSelectHistory: (AutoBackupFile) -> Unit,
) {
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> if (uri != null) onPickDirectory(uri.toString()) }

    val settingsGroupTitle = stringResource(R.string.data_backup_auto_title)
    val historyGroupTitle = stringResource(R.string.data_backup_history_title)

    Column {
        SmallTitle(text = settingsGroupTitle)
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            SwitchPreference(
                title = stringResource(R.string.data_backup_auto_enabled),
                summary = stringResource(R.string.data_backup_auto_enabled_summary),
                checked = state.settings.enabled,
                enabled = !state.busy,
                onCheckedChange = onToggle,
            )
            ArrowPreference(
                title = stringResource(R.string.data_backup_auto_time),
                summary = stringResource(
                    R.string.data_backup_auto_time_summary,
                    formatClock(state.settings.hour, state.settings.minute),
                ),
                enabled = state.settings.enabled && !state.busy,
                onClick = onPickTime,
            )
            ArrowPreference(
                title = stringResource(R.string.data_backup_auto_retention),
                summary = stringResource(
                    R.string.data_backup_auto_retention_summary,
                    state.settings.retentionCount,
                ),
                enabled = state.settings.enabled && !state.busy,
                onClick = onPickRetention,
            )
            ArrowPreference(
                title = stringResource(R.string.data_backup_auto_directory),
                summary = state.settings.safDirectoryUri?.let {
                    stringResource(R.string.data_backup_auto_directory_set)
                } ?: stringResource(R.string.data_backup_auto_directory_none),
                enabled = state.settings.enabled && !state.busy,
                onClick = { directoryLauncher.launch(null) },
            )
            if (state.settings.safDirectoryUri != null) {
                ArrowPreference(
                    title = stringResource(R.string.data_backup_auto_directory_clear),
                    enabled = state.settings.enabled && !state.busy,
                    onClick = { onPickDirectory(null) },
                )
            }
            if (!state.exactAllowed && state.settings.enabled) {
                ArrowPreference(
                    title = stringResource(R.string.data_backup_auto_exactness),
                    summary = stringResource(R.string.data_backup_auto_exactness_denied),
                    onClick = onRequestExactAlarm,
                )
            }
            ArrowPreference(
                title = stringResource(R.string.data_backup_auto_next),
                summary = state.nextTriggerAt?.let {
                    stringResource(R.string.data_backup_auto_next_value, formatDateTime(it))
                } ?: describeLastRun(state.settings),
            )
            if (state.settings.lastSafFailureAt != null) {
                BasicComponent(
                    title = stringResource(R.string.data_backup_auto_saf_failure_title),
                    summary = stringResource(
                        R.string.data_backup_auto_saf_failure,
                        formatDateTime(state.settings.lastSafFailureAt),
                        state.settings.lastSafFailureKind ?: "-",
                    ),
                )
            }
            ArrowPreference(
                title = stringResource(R.string.data_backup_auto_run_now),
                summary = stringResource(R.string.data_backup_auto_run_now_summary),
                enabled = !state.busy,
                onClick = onRunNow,
            )
        }
    }

    Column {
        SmallTitle(text = historyGroupTitle)
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            if (state.history.isEmpty()) {
                BasicComponent(title = stringResource(R.string.data_backup_history_empty))
            } else {
                state.history.forEachIndexed { index, entry ->
                    ArrowPreference(
                        title = stringResource(
                            R.string.data_backup_history_entry,
                            formatDateTime(entry.createdAt),
                            formatSize(entry.sizeBytes),
                        ),
                        enabled = !state.busy,
                        onClick = { onSelectHistory(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun describeLastRun(settings: AutoBackupSettings): String {
    val at = settings.lastRunAt ?: return stringResource(R.string.data_backup_auto_never)
    return when (settings.lastRunSucceeded) {
        true -> stringResource(R.string.data_backup_auto_last_success, formatDateTime(at))
        false -> stringResource(
            R.string.data_backup_auto_last_failure,
            formatDateTime(at),
            settings.lastRunFailureKind ?: "-",
        )
        null -> stringResource(R.string.data_backup_auto_never)
    }
}

internal fun formatClock(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)

internal fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
