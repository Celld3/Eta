package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
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
sealed interface AgentChoiceResult {
    data class Selected(val index: Int, val title: String) : AgentChoiceResult
    /** 用户不走选项，直接在内嵌面板里输入文字发送；文本作为本轮回复提交。 */
    data class CustomInput(val text: String) : AgentChoiceResult
    data object Cancelled : AgentChoiceResult
    data object TimedOut : AgentChoiceResult
    data object Unavailable : AgentChoiceResult
}

/**
 * 卡片收起的动画时长。窗口层要等这段动画放完再摘窗，
 * 否则 removeView 会把最后一帧直接抹掉，看起来就是硬闪一下。
 */
internal const val InteractionCardExitMs = 190L

/** 卡片收起与进场共用的进度：0 完全隐藏，1 完全就位。 */
@Composable
private fun rememberCardProgress(visible: Boolean, label: String): State<Float> =
    animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        // 进场用弹簧，落位时有一点软着陆；收起用短促的减速曲线。
        // 两段都只驱动一个进度值，透明度与缩放不会各自跑偏。
        animationSpec = if (visible) {
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(durationMillis = InteractionCardExitMs.toInt(), easing = FastOutLinearInEasing)
        },
        label = label,
    )

/**
 * 模型需要用户拍板时弹出的底部选择卡片。
 *
 * 与结果卡片共用同一套浮窗语汇：底部圆角面板、顶部抓手、窗口外触摸穿透。
 * 与结果卡片不同的是它必须被回答：卡片不会自动消失，只由点选、取消、返回或超时结束。
 *
 * [visible] 由窗口层驱动：置 false 后卡片先播收起动画，窗口层等
 * [InteractionCardExitMs] 再摘窗，进出两侧都有完整的一帧序列。
 */
@Composable
internal fun AgentChoiceCard(
    request: AgentChoiceRequest,
    visible: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosenIndex by remember { mutableIntStateOf(-1) }
    var settled by remember { mutableStateOf(false) }
    val progress by rememberCardProgress(visible, "choice_progress")

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
                    // 浮窗高度就是卡片高度，任何位移都会被窗口边界裁掉一截，
                    // 所以进出场只做缩放与透明度：从底边生长，收回时缩回底边。
                    alpha = progress
                    val scale = CardEnterExitScaleStart + (1f - CardEnterExitScaleStart) * progress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
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
                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
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
 * 收起同样走窗口层的两段式：先播动画，再由窗口层摘窗。
 */
@Composable
internal fun AgentCopyCard(
    title: String,
    content: String,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val progressState = rememberCardProgress(visible, "copy_progress")
    val progress by progressState
    // 只在进场动画落位后才开始计时，否则弹簧还没停卡片就自己走了。
    LaunchedEffect(copied) {
        snapshotFlow { progressState.value }.first { it >= 1f }
        delay(if (copied) CopyCardAfterCopyMs else CopyCardAutoDismissMs)
        onDismiss()
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
                .graphicsLayer {
                    alpha = progress
                    val scale = CardEnterExitScaleStart + (1f - CardEnterExitScaleStart) * progress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
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

/** 进出场的起始缩放；1 减去它等于弹簧要走的剩余行程。 */
private const val CardEnterExitScaleStart = 0.92f

private const val CopyCardAfterCopyMs = 1_400L
private const val CopyCardAutoDismissMs = 12_000L

/** 内嵌面板选项区的固定最大高度；超出后由 LazyColumn 内部滚动。 */
private val InlineChoiceListMaxHeight = 220.dp

/**
 * 对话输入框上方内嵌的选项面板。
 *
 * 与浮窗版 [AgentChoiceCard] 语义一致：点选项即以选项标题作为本轮回复提交，
 * 自定义输入区打字发送则以输入文字提交，两者二选一。区别是它嵌在聊天界面里，
 * 不再依赖浮窗权限；收起只折叠内容、不结束等待，关闭才是取消。
 *
 * 折叠/展开用 expand-shrink 过渡，进场用弹簧缩放+淡入，都在同一段进度里完成。
 */
@Composable
internal fun AgentInlineChoicePanel(
    request: AgentChoiceRequest,
    visible: Boolean,
    onSelect: (Int) -> Unit,
    onCustomInput: (String) -> Unit,
    onDismiss: () -> Unit,
    onToggleCollapse: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var settled by remember { mutableStateOf(false) }
    var chosenIndex by remember { mutableIntStateOf(-1) }
    var collapsed by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    val customField = rememberTextFieldState()
    val progress by rememberCardProgress(visible, "inline_choice_progress")
    LaunchedEffect(Unit) { appeared = true }
    val reveal by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "inline_choice_reveal",
    )

    fun choose(index: Int) {
        if (settled) return
        settled = true
        chosenIndex = index
        onSelect(index)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                // 进场：从 92% 缩放 + 12dp 上浮 + 淡入，一段弹簧完成，收起时反向收回。
                alpha = progress * reveal
                val scale = CardEnterExitScaleStart + (1f - CardEnterExitScaleStart) * progress
                scaleX = scale
                scaleY = scale
                translationY = (1f - reveal) * 12.dp.toPx()
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(22.dp),
            ),
    ) {
        // 顶部 header：图标 + 标题 + 收起/关闭。
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = request.title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    collapsed = !collapsed
                    onToggleCollapse()
                },
                minWidth = 34.dp,
                minHeight = 34.dp,
            ) {
                Icon(
                    imageVector = if (collapsed) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            IconButton(
                onClick = { if (!settled) onDismiss() },
                minWidth = 34.dp,
                minHeight = 34.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }

        // 折叠区：副标题、选项列表、自定义输入、取消；收起时整体 shrink 回去而不是硬切。
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column {
                if (request.subtitle.isNotBlank()) {
                    Text(
                        text = request.subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = InlineChoiceListMaxHeight)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(request.options.size) { index ->
                        ChoiceRow(
                            option = request.options[index],
                            chosen = settled && chosenIndex == index,
                            onChosen = { choose(index) },
                        )
                    }
                }

                // 自定义输入：胶囊输入框 + 圆形发送键，与聊天输入条同一套语汇。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        state = customField,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .border(
                                0.5.dp,
                                MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(18.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        textStyle = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (customField.text.isBlank()) {
                                    MiuixTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MiuixTheme.colorScheme.primary
                                },
                            )
                            .clickable {
                                val text = customField.text.toString().trim()
                                if (text.isNotEmpty() && !settled) {
                                    settled = true
                                    onCustomInput(text)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (customField.text.isBlank()) {
                                MiuixTheme.colorScheme.onSurfaceVariantActions
                            } else {
                                MiuixTheme.colorScheme.onPrimary
                            },
                        )
                    }
                }

            }
        }
    }
}