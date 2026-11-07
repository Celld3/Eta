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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.local.LocalAsrModelState
import io.github.mangi.eta.agent.voice.local.LocalAsrModelStore
import io.github.mangi.eta.agent.voice.local.LocalAsrModels
import io.github.mangi.eta.agent.voice.local.LocalRecognizerHolder
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.StatusError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地识别的模型管理：下载、进度、取消、重试、删除与体积展示。
 *
 * 下载由用户点一下才开始；页面销毁时协程随之取消，下次进入按磁盘真实状态重新判断。
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

    EtaPreferenceGroupTitle(stringResource(R.string.speech_local_model_title))
    EtaPreferenceGroup {
        EtaPreference(
            title = stringResource(
                when (state) {
                    is LocalAsrModelState.Ready -> R.string.speech_local_model_ready
                    is LocalAsrModelState.Failed -> R.string.speech_local_model_retry
                    is LocalAsrModelState.Unpacking -> R.string.speech_local_model_unpacking
                    else -> R.string.speech_local_model_download
                },
            ),
            summary = when (val current = state) {
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
            },
            summaryColor = BasicComponentDefaults.summaryColor(
                color = if (state is LocalAsrModelState.Failed) {
                    StatusError
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            ),
            // 只有「未下载 / 失败」可点：下载中由取消按钮接管，就绪后由删除按钮接管。
            enabled = !downloading && state !is LocalAsrModelState.Ready,
            onClick = {
                job = scope.launch {
                    store.download { state = it }
                    // 模型换了，旧识别器必须丢掉，否则仍指向被覆盖的文件。
                    LocalRecognizerHolder.releaseNow()
                }
            },
            endActions = {
                if (downloading) {
                    EtaTextButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = {
                            job?.cancel()
                            job = null
                            state = LocalAsrModelState.Absent
                        },
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        cornerRadius = 17.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    )
                } else if (state is LocalAsrModelState.Ready) {
                    EtaTextButton(
                        text = stringResource(R.string.action_delete),
                        onClick = {
                            job = scope.launch {
                                try {
                                    store.delete()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                }
                                LocalRecognizerHolder.releaseNow()
                                state = LocalAsrModelState.Absent
                            }
                        },
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        cornerRadius = 17.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            },
            bottomAction = if (state is LocalAsrModelState.Downloading) {
                {
                    CircularProgressIndicator(
                        progress = (state as LocalAsrModelState.Downloading).fraction,
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
            } else {
                null
            },
        )
    }
    SpeechNote(stringResource(R.string.speech_local_model_note))
}

/** 体积统一用 MB；大体积取整、小体积保留一位小数，避免出现 128.0 MB 这种写法。 */
private fun formatModelSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 10) "${mb.toInt()} MB" else String.format("%.1f MB", mb)
}
