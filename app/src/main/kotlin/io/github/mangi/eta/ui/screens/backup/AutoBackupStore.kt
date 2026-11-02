package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.backup.AutoBackupScheduler
import io.github.mangi.eta.data.backup.BackupHistoryStore
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动备份设置页状态。
 *
 * 所有写入都先落 DataStore 再重排调度器，保证进程被杀后配置与闹钟仍一致。
 */
internal class AutoBackupStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var state by mutableStateOf(AutoBackupUiState())
        private set

    fun refresh() {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { SettingsDataStore.autoBackupSettings() }
            val history = withContext(Dispatchers.IO) { BackupHistoryStore.list(appContext) }
            val next = AutoBackupScheduler.nextTriggerAt(appContext)
            val exact = AutoBackupScheduler.exactness(appContext) ==
                AutoBackupScheduler.Exactness.EXACT
            state = state.copy(
                settings = settings,
                history = history,
                nextTriggerAt = next,
                exactAllowed = exact,
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                SettingsDataStore.setAutoBackupSettings { it.copy(enabled = enabled) }
                AutoBackupScheduler.schedule(appContext)
            }
            refresh()
        }
    }

    fun setTime(hour: Int, minute: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                SettingsDataStore.setAutoBackupSettings { it.copy(hour = hour, minute = minute) }
                AutoBackupScheduler.schedule(appContext)
            }
            refresh()
        }
    }

    fun setRetention(count: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                SettingsDataStore.setAutoBackupSettings { it.copy(retentionCount = count) }
                val settings = SettingsDataStore.autoBackupSettings()
                BackupHistoryStore.prune(appContext, settings.retentionCount)
            }
            refresh()
        }
    }

    fun setDirectory(uri: String?) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (uri != null) {
                    // 目录授权需要持久化，否则重启后 SAF 写入必然失败。
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(
                            Uri.parse(uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }.onFailure {
                        AndroidAgentLogger.warn(
                            "Agent auto backup directory permission failed: type=${it.javaClass.simpleName}"
                        )
                    }
                }
                SettingsDataStore.setAutoBackupSettings {
                    it.copy(safDirectoryUri = uri, lastSafFailureAt = null, lastSafFailureKind = null)
                }
            }
            refresh()
        }
    }

    fun removeHistory(entryName: String) {
        scope.launch {
            withContext(Dispatchers.IO) { BackupHistoryStore.delete(appContext, entryName) }
            refresh()
        }
    }

    fun setBusy(busy: Boolean) {
        state = state.copy(busy = busy)
    }

    fun exactAlarmSettingsIntent(): Intent =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${appContext.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${appContext.packageName}"))
        }

    fun retentionOptions(): List<Int> = listOf(3, 5, 7, 10, 14, 21, 30)
}
