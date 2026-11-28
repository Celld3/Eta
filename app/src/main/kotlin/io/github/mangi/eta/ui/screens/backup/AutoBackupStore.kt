package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.backup.AutoBackupFile
import io.github.mangi.eta.data.backup.AutoBackupScheduler
import io.github.mangi.eta.data.backup.AutoBackupWorker
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
                // 改份数时同步淘汰 SAF 目录，否则那边仍会无限增长。
                settings.safDirectoryUri?.let { uri ->
                    runCatching {
                        BackupHistoryStore.pruneSaf(appContext, uri, settings.retentionCount)
                    }.onFailure {
                        AndroidAgentLogger.warn(
                            "Agent auto backup SAF prune on retention failed: type=${it.javaClass.simpleName}",
                        )
                    }
                }
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

    /** 立即备份一次：不等待下一个计划点，用于临时造一份快照。 */
    fun runNow() {
        AutoBackupWorker.enqueue(appContext, reason = "manual")
        refresh()
    }

    /** 亮给外部（导入流程）用的忙标志，避免导入期间还能再点记录或拨开关。 */
    fun setBusy(busy: Boolean) {
        state = state.copy(busy = busy)
        if (!busy) refresh()
    }

    /**
     * 手动删除一份自动备份：私有目录那份必删，额外副本目录里有同名文件时一起删。
     *
     * 两边成败分别统计，任一失败都不回滚另一边，最终通过 [AutoBackupStore.DeleteResult] 交给界面提示。
     */
    fun delete(entry: AutoBackupFile) {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { SettingsDataStore.autoBackupSettings() }
            state = state.copy(busy = true)
            val localDeleted = withContext(Dispatchers.IO) {
                runCatching { BackupHistoryStore.delete(appContext, entry.name) }.getOrDefault(false)
            }
            val safDeleted = settings.safDirectoryUri?.let { uri ->
                withContext(Dispatchers.IO) {
                    runCatching { BackupHistoryStore.deleteSaf(appContext, uri, entry.name) }
                        .getOrDefault(false)
                }
            }
            if (!localDeleted) {
                AndroidAgentLogger.warn("Agent auto backup delete failed: type=IOException")
            }
            // 删掉的正好是上次记录里那份时，清掉名字，避免设置页指向已不存在的文件。
            if (settings.lastBackupName == entry.name) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        SettingsDataStore.setAutoBackupSettings { it.copy(lastBackupName = null) }
                    }
                }
            }
            state = state.copy(busy = false)
            refresh()
            deleteResult = DeleteResult(
                name = entry.name,
                localDeleted = localDeleted,
                safDeleted = safDeleted,
            )
        }
    }

    /** 最近一次手动删除的结果，界面读走提示后置空。 */
    var deleteResult by mutableStateOf<DeleteResult?>(null)
        private set

    fun consumeDeleteResult() {
        deleteResult = null
    }

    internal data class DeleteResult(
        val name: String,
        val localDeleted: Boolean,
        val safDeleted: Boolean,
    )

    fun exactAlarmSettingsIntent(): Intent =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${appContext.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${appContext.packageName}"))
        }
}
