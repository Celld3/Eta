package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一个可点选的选项；推荐项由模型标注，界面只做轻量强调。 */
internal data class AgentChoiceOption(
    val title: String,
    val description: String = "",
    val recommended: Boolean = false,
)

/** 模型请求用户拍板的一次选择。选项数量在工具层已收窄到 2~4 个。 */
internal data class AgentChoiceRequest(
    val title: String,
    val subtitle: String = "",
    val options: List<AgentChoiceOption> = emptyList(),
    val timeoutMs: Long = DefaultTimeoutMs,
) {
    companion object {
        const val DefaultTimeoutMs = 120_000L
        const val MinTimeoutMs = 10_000L
        const val MaxTimeoutMs = 600_000L
    }
}

/** 选择卡片的终态；不可用时调用方退化为文字提问。 */
internal sealed interface AgentChoiceResult {
    data class Selected(val index: Int, val title: String) : AgentChoiceResult
    data object Cancelled : AgentChoiceResult
    data object TimedOut : AgentChoiceResult
    data object Unavailable : AgentChoiceResult
}

/**
 * 模型需要用户拍板时弹出的底部选择卡片。
 *
 * 与结果卡片共用同一套浮窗语汇：底部圆角面板、顶部抓手、窗口外触摸穿透。
 * 与结果卡片不同的是它必须被回答：卡片不会自动消失，只由点选、取消、返回或超时结束。
 */
@Composable
internal fun AgentChoiceCard(
    request: AgentChoiceRequest,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    var chosenIndex by remember { mutableIntStateOf(-1) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(180),
        label = "choice_alpha",
    )
    val offset by animateFloatAsState(
        targetValue = if (entered) 0f else 64f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "choice_offset",
    )

    // settled 先落，避免连点或点完立刻按返回把同一个结论回传两次。
    fun choose(index: Int) {
        if (settled) return
        settled = true
        chosenIndex = index
        onSelect(index)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ChoiceCardMaxHeight)
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = offset
                }
                .shadow(16.dp, RoundedCornerShape(ChoiceCardCorner), ambientColor = Color.Black.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(ChoiceCardCorner))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = request.title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (request.subtitle.isNotBlank()) {
                    Text(
                        text = request.subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                request.options.forEachIndexed { index, option ->
                    ChoiceRow(
                        option = option,
                        chosen = settled && chosenIndex == index,
                        onChosen = { choose(index) },
                    )
                }
            }
            TextButton(
                text = stringResource(R.string.overlay_choice_dismiss),
                onClick = { if (!settled) onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                minHeight = 44.dp,
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    option: AgentChoiceOption,
    chosen: Boolean,
    onChosen: () -> Unit,
) {
    val accent = MiuixTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (chosen) accent.copy(alpha = 0.12f) else MiuixTheme.colorScheme.secondaryContainer,
            )
            .then(
                if (option.recommended && !chosen) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onChosen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.title,
                    style = MiuixTheme.textStyles.body2,
                    color = if (chosen) accent else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (chosen || option.recommended) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (option.recommended) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.overlay_choice_recommended),
                        style = MiuixTheme.textStyles.footnote2,
                        color = accent,
                    )
                }
            }
            if (option.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = option.description,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (chosen) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent,
            )
        }
    }
}

/**
 * 复制卡片：模型把一段需要落地的正文交给用户时弹出，一键写剪贴板。
 *
 * 它不阻塞运行：用户不点也照样继续，卡片自己收起；点过之后按钮变成已复制再收起。
 */
@Composable
internal fun AgentCopyCard(
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(copied) {
        delay(if (copied) CopyCardAfterCopyMs else CopyCardAutoDismissMs)
        onDismiss()
    }
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(180),
        label = "copy_alpha",
    )
    val offset by animateFloatAsState(
        targetValue = if (entered) 0f else 64f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "copy_offset",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = offset
                }
                .shadow(16.dp, RoundedCornerShape(ChoiceCardCorner), ambientColor = Color.Black.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(ChoiceCardCorner))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Text(
                text = title.ifBlank { stringResource(R.string.overlay_copy_title) },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            Text(
                text = content,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(
                        if (copied) R.string.copy_copied else R.string.overlay_copy_action,
                    ),
                    onClick = {
                        clipboard.setText(AnnotatedString(content))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.overlay_copy_close),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    minHeight = 44.dp,
                )
            }
        }
    }
}

private val ChoiceCardCorner = 28.dp
private val ChoiceCardMaxHeight = 460.dp
private const val CopyCardAfterCopyMs = 1_400L
private const val CopyCardAutoDismissMs = 12_000L
