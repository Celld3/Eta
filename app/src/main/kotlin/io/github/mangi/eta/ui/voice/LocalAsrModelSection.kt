package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.local.LocalAsrModelState
import io.github.mangi.eta.agent.voice.local.LocalAsrModelStore
import io.github.mangi.eta.agent.voice.local.LocalAsrModels
import io.github.mangi.eta.agent.voice.local.LocalRecognizerHolder
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地识别的模型管理：下载、进度、取消、重试、删除与体积展示。
 *
 * 下载由用户点一下才开始；页面销毁时协程随之取消，下次进入按磁盘真实状态重新判断。
 * COUI 与 Miuix 两套样式各写一份，沿用语音设置页其余分组的组件体系。
 */
@Composable
internal fun LocalAsrModelSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { LocalAsrModelStore(context) }
    var state by remember { mutableStateOf<LocalAsrModelState>(LocalAsrModelState.Absent) }
    var job by remember { mutableStateOf<Job?>(null) }

    // 进入页面先按磁盘真实状态校准一次，避免显示上次会话残留的进度。
    LaunchedEffect(store) {
        state = if (store.isReady()) {
            LocalAsrModelState.Ready(store.installedBytes())
        } else {
            LocalAsrModelState.Absent
        }
    }

    val downloading = state is LocalAsrModelState.Downloading || state is LocalAsrModelState.Unpacking
    val defaultSummary = stringResource(
        R.string.speech_local_model_size,
        (LocalAsrModels.active.sizeBytes / 1024 / 1024).toString() + " MB",
    )
    val title = stringResource(
        when (state) {
            is LocalAsrModelState.Ready -> R.string.speech_local_model_ready
            is LocalAsrModelState.Failed -> R.string.speech_local_model_retry
            is LocalAsrModelState.Unpacking -> R.string.speech_local_model_unpacking
            else -> R.string.speech_local_model_download
        },
    )
    val summary = when (val current = state) {
        is LocalAsrModelState.Ready ->
            stringResource(R.string.speech_local_model_size, formatModelSize(current.sizeBytes))
        is LocalAsrModelState.Downloading ->
            stringResource(
                R.string.speech_local_model_progress,
                formatModelSize(current.downloadedBytes),
                formatModelSize(current.totalBytes),
            )
        is LocalAsrModelState.Unpacking -> null
        is LocalAsrModelState.Failed -> current.message
        LocalAsrModelState.Absent -> defaultSummary
    }
    val failed = state is LocalAsrModelState.Failed
    // 只有「未下载 / 失败」可点：下载中由取消按钮接管，就绪后由删除按钮接管。
    val rowEnabled = !downloading && state !is LocalAsrModelState.Ready

    val startDownload: () -> Unit = {
        job = scope.launch {
            store.download { state = it }
            // 模型换了，旧识别器必须丢掉，否则仍指向被覆盖的文件。
            LocalRecognizerHolder.releaseNow()
        }
    }
    val cancelDownload: () -> Unit = {
        job?.cancel()
        job = null
        state = LocalAsrModelState.Absent
    }
    val deleteModel: () -> Unit = {
        job = scope.launch {
            try {
                store.delete()
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
            LocalRecognizerHolder.releaseNow()
            state = LocalAsrModelState.Absent
        }
    }
    val progressFraction = (state as? LocalAsrModelState.Downloading)?.fraction
    val groupTitle = stringResource(R.string.speech_local_model_title)
    val note = stringResource(R.string.speech_local_model_note)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Group(title = groupTitle) {
                CouiPreference(
                    title = title,
                    summary = summary,
                    summaryColor = if (failed) StatusError else Color.Unspecified,
                    enabled = rowEnabled || downloading,
                    showChevron = false,
                    trailing = when {
                        downloading -> {
                            {
                                CouiButton(
                                    text = stringResource(R.string.action_cancel),
                                    onClick = cancelDownload,
                                    minHeight = 32.dp,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                        state is LocalAsrModelState.Ready -> {
                            {
                                CouiButton(
                                    text = stringResource(R.string.action_delete),
                                    onClick = deleteModel,
                                    minHeight = 32.dp,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                        else -> null
                    },
                    onClick = if (rowEnabled) startDownload else null,
                )
                if (progressFraction != null) {
                    Divider()
                    CircularProgressIndicator(
                        progress = progressFraction,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = MiuixTheme.colorScheme.primary,
                            disabledForegroundColor = MiuixTheme.colorScheme.primary,
                            backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                        ),
                        strokeWidth = 2.5.dp,
                        size = 20.dp,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
            }
            SpeechNote(note)
        }

        AppearanceUiStyle.MIUIX -> {
            SmallTitle(text = groupTitle)
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = title,
                    summary = summary,
                    summaryColor = BasicComponentDefaults.summaryColor(
                        color = if (failed) StatusError else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    ),
                    enabled = rowEnabled || downloading,
                    onClick = if (rowEnabled) startDownload else null,
                    endActions = when {
                        downloading -> {
                            {
                                TextButton(
                                    text = stringResource(R.string.action_cancel),
                                    onClick = cancelDownload,
                                    minWidth = 0.dp,
                                    minHeight = 34.dp,
                                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        }
                        state is LocalAsrModelState.Ready -> {
                            {
                                TextButton(
                                    text = stringResource(R.string.action_delete),
                                    onClick = deleteModel,
                                    minWidth = 0.dp,
                                    minHeight = 34.dp,
                                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        }
                        else -> null
                    },
                )
                if (progressFraction != null) {
                    CircularProgressIndicator(
                        progress = progressFraction,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = MiuixTheme.colorScheme.primary,
                            disabledForegroundColor = MiuixTheme.colorScheme.primary,
                            backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                        ),
                        strokeWidth = 2.5.dp,
                        size = 20.dp,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }
            SpeechNote(note)
        }
    }
}

/** 体积统一用 MB；大体积取整、小体积保留一位小数，避免出现 128.0 MB 这种写法。 */
private fun formatModelSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 10) "${mb.toInt()} MB" else String.format("%.1f MB", mb)
}
