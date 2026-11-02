package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.data.backup.AutoBackupFile
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream) -> EtaBackupSummary,
    onImport: suspend (InputStream) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    val autoBackup = remember { AutoBackupStore(context, scope) }
    var pendingHistory by remember { mutableStateOf<AutoBackupFile?>(null) }
    var pendingDelete by remember { mutableStateOf<AutoBackupFile?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { autoBackup.refresh() }

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AndroidAgentLogger.error("Agent backup UI failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}")
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val summary = output.use { onExport(it) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.data_backup_exported,
                        summary.conversationCount,
                        summary.providerCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = stringResource(R.string.data_backup_warning_title),
                    summary = stringResource(R.string.data_backup_warning_summary),
                )
            }
        }
        item(key = "actions-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.data_backup_actions))
        }
        item(key = "actions-card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_export),
                    summary = if (busy) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_export_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Download,
                            loading = busy,
                        )
                    },
                    onClick = {
                        exportLauncher.launch(defaultBackupFileName())
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_import),
                    summary = stringResource(R.string.data_backup_import_summary),
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Description,
                            loading = false,
                        )
                    },
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                )
            }
        }
        item(key = "auto-backup") {
            AutoBackupSection(
                state = autoBackup.state,
                onToggle = autoBackup::setEnabled,
                onPickTime = { showTimePicker(context) { h, m -> autoBackup.setTime(h, m) } },
                onPickRetention = { showRetentionPicker(context, autoBackup.state.settings.retentionCount) { autoBackup.setRetention(it) } },
                onPickDirectory = autoBackup::setDirectory,
                onRequestExactAlarm = {
                    runCatching { context.startActivity(autoBackup.exactAlarmSettingsIntent()) }
                },
                onSelectHistory = { pendingHistory = it },
            )
        }
    }

    if (showImportDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_import_confirm_title),
            summary = stringResource(R.string.data_backup_import_confirm_summary),
            onDismissRequest = {
                if (!busy) {
                    showImportDialog = false
                    pendingImportUri = null
                }
            },
        ) {
            MiuixDialogActions(
                confirmText = if (busy) {
                    stringResource(R.string.data_backup_working)
                } else {
                    stringResource(R.string.action_import)
                },
                destructive = true,
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = {
                    showImportDialog = false
                    pendingImportUri = null
                },
                onConfirm = {
                    val uri = pendingImportUri ?: return@MiuixDialogActions
                    showImportDialog = false
                    scope.launch {
                        busy = true
                        try {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: error(context.getString(R.string.data_backup_file_open_failed))
                            val summary = input.use { onImport(it) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_imported,
                                    summary.conversationCount,
                                    summary.providerCount,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (throwable: Throwable) {
                            showFailure(throwable)
                        } finally {
                            pendingImportUri = null
                            busy = false
                        }
                    }
                },
            )
        }
    }

    pendingHistory?.let { entry ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_auto_restore_confirm_title),
            summary = stringResource(R.string.data_backup_auto_restore_confirm_summary),
            onDismissRequest = { if (!busy) pendingHistory = null },
        ) {
            MiuixDialogActions(
                confirmText = if (busy) stringResource(R.string.data_backup_working)
                else stringResource(R.string.data_backup_history_restore),
                destructive = true,
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = { pendingHistory = null },
                onConfirm = {
                    pendingHistory = null
                    scope.launch {
                        busy = true
                        autoBackup.setBusy(true)
                        try {
                            val summary = entry.file.inputStream().use { onImport(it) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_imported_skills,
                                    summary.conversationCount,
                                    summary.providerCount,
                                    summary.skillCount,
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        } catch (throwable: Throwable) {
                            showFailure(throwable)
                        } finally {
                            busy = false
                            autoBackup.setBusy(false)
                            autoBackup.refresh()
                        }
                    }
                },
            )
        }
    }

    pendingDelete?.let { entry ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_auto_delete_confirm_title),
            summary = stringResource(R.string.data_backup_auto_delete_confirm_summary),
            onDismissRequest = { pendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.data_backup_history_delete),
                destructive = true,
                onCancel = { pendingDelete = null },
                onConfirm = {
                    autoBackup.removeHistory(entry.name)
                    pendingDelete = null
                },
            )
        }
    }
}

@Composable
internal fun BackupIcon(icon: ImageVector, loading: Boolean) {
    Box(
        // 与 EtaPreferenceIcon 同宽，正文和分割线起点保持对齐。
        modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            InfiniteProgressIndicator(size = 20.dp)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun defaultBackupFileName(): String =
    "Eta-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-backup.json"

/** 用系统时间选择器与份数单选，避免引入额外 Compose 组件依赖。 */
private fun showTimePicker(context: Context, onPicked: (Int, Int) -> Unit) {
    val now = java.util.Calendar.getInstance()
    android.app.TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour, minute) },
        now.get(java.util.Calendar.HOUR_OF_DAY),
        now.get(java.util.Calendar.MINUTE),
        true,
    ).show()
}

private fun showRetentionPicker(context: Context, current: Int, onPicked: (Int) -> Unit) {
    val options = listOf(3, 5, 7, 10, 14, 21, 30)
    android.app.AlertDialog.Builder(context)
        .setTitle(R.string.data_backup_auto_retention)
        .setSingleChoiceItems(
            options.map { context.getString(R.string.data_backup_auto_retention_option, it) }
                .toTypedArray(),
            options.indexOf(current).coerceAtLeast(0),
        ) { dialog, which ->
            onPicked(options[which])
            dialog.dismiss()
        }
        .show()
}
