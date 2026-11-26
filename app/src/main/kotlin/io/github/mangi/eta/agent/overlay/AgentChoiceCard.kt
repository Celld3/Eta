package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
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
    /**
     * 发起这次选择的会话身份，取值与 [io.github.mangi.eta.agent.runtime.AgentConversationIdentity] 一致。
     *
     * 卡片只在归属会话里显示。没有会话归属的运行（终端页、外部入口）取 `run:<runId>`，
     * 这类键在聊天界面里没有可渲染的位置，运行时会让 [AgentChoiceResult.Unavailable] 退化，
     * 由模型改用文字提问，而不是挂一张用户点不到、只能等超时的卡片。
     */
    val conversationKey: String = "",
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

/** 内嵌卡片与输入栏共用的圆角；两边要一致，否则并排看会像两套容器。 */
private val InteractionCardCornerRadius = 24.dp

/** 卡片内部行与自定义输入框的圆角，比整卡小一档但同一曲率。 */
private val ChoiceRowCornerRadius = 16.dp

/**
 * 内嵌卡片的外壳：沿用聊天输入条的玻璃写法（[GlassShape] + squircle 兜底），
 * 让卡片和输入栏在同一界面里是同一族容器，而不是「同样 miuix 但看着不像」。
 */
@Composable
private fun Modifier.interactionCardSurface(backdrop: Backdrop?): Modifier {
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    // 与输入栏同一套兜底底色：浅色下 surfaceContainer 和 surface 同值时给一点灰，避免飘在纯白上。
    val fallbackBg = if (!isDark && MiuixTheme.colorScheme.surfaceContainer == MiuixTheme.colorScheme.surface) {
        Color(0xFFF5F6F8)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val shape = remember { GlassShape(InteractionCardCornerRadius) }
    return this.glassPanel(
        backdrop = backdrop,
        shape = shape,
        style = GlassDefaults.style(isDark),
        material = GlassMaterials.puredThinGlass(isDark),
        stroke = GlassEdgeStrokes.forTheme(
            isDark = isDark,
            light = GlassEdgeStrokes.SmallLight,
            dark = GlassEdgeStrokes.SmallDark,
        ),
        shadow = GlassShadows.Regular,
        shading = false,
        fallback = Modifier
            .squircleSurface(color = fallbackBg, cornerRadius = InteractionCardCornerRadius)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.4f),
                cornerRadius = InteractionCardCornerRadius,
            ),
    )
}

@Composable
private fun ChoiceRow(
    option: AgentChoiceOption,
    chosen: Boolean,
    onChosen: () -> Unit,
) {
    val accent = MiuixTheme.colorScheme.primary
    // 行内小方块的圆角也走 squircle，和整卡、输入栏的曲率是一套。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(
                color = if (chosen) accent.copy(alpha = 0.12f) else MiuixTheme.colorScheme.secondaryContainer,
                cornerRadius = ChoiceRowCornerRadius,
            )
            .then(
                if (option.recommended && !chosen) {
                    Modifier.squircleBorder(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.5f),
                        cornerRadius = ChoiceRowCornerRadius,
                    )
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
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent,
            )
        }
    }
}

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
    onSelect: (Int) -> Unit,
    onCustomInput: (String) -> Unit,
    onDismiss: () -> Unit,
    onToggleCollapse: () -> Unit = {},
    glassBackdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
) {
    var settled by remember { mutableStateOf(false) }
    var chosenIndex by remember { mutableIntStateOf(-1) }
    var collapsed by remember { mutableStateOf(false) }
    val customField = rememberTextFieldState()
    val hapticFeedback = LocalHapticFeedback.current

    // 面板进场时给一次轻触震动，提示有选择在等。
    LaunchedEffect(Unit) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun choose(index: Int) {
        if (settled) return
        settled = true
        chosenIndex = index
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        onSelect(index)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .interactionCardSurface(glassBackdrop),
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
                    imageVector = MiuixIcons.Glass.Info,
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
                    imageVector = MiuixIcons.Glass.ChevronForward,
                    contentDescription = null,
                    // 收起时朝上（提示可展开），展开时朝下。
                    modifier = Modifier.size(19.dp).rotate(if (collapsed) -90f else 90f),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            IconButton(
                onClick = { if (!settled) onDismiss() },
                minWidth = 34.dp,
                minHeight = 34.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.Close,
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
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                cornerRadius = ChoiceRowCornerRadius,
                            )
                            .squircleBorder(
                                width = 0.5.dp,
                                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
                                cornerRadius = ChoiceRowCornerRadius,
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
