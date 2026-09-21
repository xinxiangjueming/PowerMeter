package com.chen.powermeter.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.chen.powermeter.ui.theme.LocalCornerRadius
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * 弹窗模糊基础设施（移植自 SportLink `DialogBlur.kt` / `DialogBackdrop.kt`，同版本口径）。
 *
 * 两条采样链路并存（2026-08-24 SportLink 定稿：外部 Haze + 内部 miuix）：
 * - 内部玻璃卡：miuix-blur 同窗口 backdrop（[layerBackdrop] 源 + 卡片 [textureBlur]）。
 * - 外部 scrim：Haze 同窗口采样（[hazeSource] 源 + 浮层 scrim [hazeEffect]）。
 *
 * 核心约束（miuix-blur / Haze 共同铁律）：模糊消费方（弹窗卡片 / scrim）必须与采样源是
 * **同窗口兄弟节点**（textureBlur 内部按 positionInWindow 计算采样偏移，跨窗口取不到主窗口
 * 内容；且源内嵌套消费方会自采样 → 黑屏/异常）。本文件通过「宿主 + slot」模式满足该约束：
 * [DialogBackdropHost] 挂在页面根，内部是 源 Box（录制页面内容） + slot Box（渲染弹窗浮层），
 * 两者天然兄弟；弹窗内容经 [DialogOverlay] 注册后都在 slot 处渲染，源采样不到弹窗卡片自身。
 *
 * 两个采样源**仅在弹窗活跃时挂载**（openCount > 0），避免整屏离屏录制常驻消耗 GPU。
 * API 33 以下 [rememberDialogBackdrop] 返回 null（miuix-blur 0.9.2 内部虽有
 * isRuntimeShaderSupported 守卫自动失效，但显式跳过可避免低版本创建 backdrop 资源，
 * 直接走半透明底降级，属双保险）→ 弹窗走实心卡片降级。Haze 在 API < 31（无 RenderEffect）
 * 自动回落纯压暗 scrim（[isHazeSupported] 守卫）。
 */

/** 弹窗玻璃卡片模糊半径（dp，2026-09-01 SportLink 用户定稿 5→10；20f 过糊） */
const val DialogGlassBlurRadius = 10f

/**
 * 玻璃卡片 tint 不透明度（叠加在 textureBlur 模糊层之上）。2026-08-22 SportLink 用户定稿：
 * 深浅色统一 0.55（玻璃感与文字对比度平衡；0.25 实测过透，0.85 浅色模式模糊被完全盖住）。
 */
const val DialogGlassTintAlphaLight = 0.55f
const val DialogGlassTintAlphaDark = 0.55f

/** 弹窗玻璃卡高光描边带宽（dp，miuix Highlight 发光带宽度）。2026-08-24 SportLink 定稿 0.8→1.5 */
const val DialogGlassHighlightWidth = 1.5f

/** 弹窗玻璃卡高光（加粗带宽版 Middle 预设，按深浅色；库预设见 miuix blur Highlight.kt） */
val DialogGlassHighlightLight = Highlight(
    width = DialogGlassHighlightWidth.dp,
    style = Highlight.GlassStrokeMiddleLight.style,
)
val DialogGlassHighlightDark = Highlight(
    width = DialogGlassHighlightWidth.dp,
    style = Highlight.GlassStrokeMiddleDark.style,
)

/** 弹窗玻璃卡实色描边宽度（dp）。2026-08-24 SportLink 定稿 0.5→1 加粗 */
val DialogGlassBorderWidth = 1.dp

/** 弹窗玻璃卡实色描边颜色（黑/白 20%，原 10%/13% 过淡） */
val DialogGlassBorderLight = Color(0x33000000)
val DialogGlassBorderDark = Color(0x33FFFFFF)

/**
 * 弹窗外部 scrim 毛玻璃样式：25% 黑压暗底 + 20dp 模糊。模糊层自带压暗底色，替代原 scrim 的
 * 纯 background(Black@0.25f)，弹窗外部显示模糊的页面内容。
 */
val DialogBlurStyle: HazeStyle = HazeStyle(
    backgroundColor = Color.Black.copy(alpha = 0.25f),   // 压暗 scrim（替代原 background）
    blurRadius = 20.dp,
    tint = null,
)

/** Haze 真模糊可用（RenderEffect，API 31+）；低版本维持纯压暗 scrim 降级 */
val isHazeSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** miuix textureBlur 可用（AGSL RuntimeShader，API 33+） */
val isMiuixBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** 弹窗外部 Haze 模糊效果节点（挂全屏 scrim，替代 background(Black@0.25f)）：
 *  低版本（无 RenderEffect）或 null 时回落纯压暗 scrim */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.dialogHazeEffect(hazeState: HazeState?): Modifier =
    if (hazeState != null && isHazeSupported) this.hazeEffect(state = hazeState, style = DialogBlurStyle) {
        clipToAreasBounds = false
    } else this.background(Color.Black.copy(alpha = 0.25f))

/** 弹窗外部模糊采样源（挂页面根内容层 / DialogBackdropHost 源 Box，与弹窗浮层兄弟） */
fun Modifier.dialogHazeSource(hazeState: HazeState): Modifier = this.hazeSource(hazeState)

/**
 * 创建弹窗 backdrop 句柄。API 33+ 返回 [LayerBackdrop]；API<33 返回 null（静默降级实心卡）。
 */
@Composable
fun rememberDialogBackdrop(): LayerBackdrop? =
    if (isMiuixBlurSupported) rememberLayerBackdrop() else null

/** 弹窗 backdrop 宿主状态；无宿主（LocalDialogBackdrop 为 null）时为 null */
val LocalDialogBackdrop = staticCompositionLocalOf<DialogBackdropState?> { null }

/**
 * 弹窗 backdrop 状态：持有 miuix 采样源句柄 + Haze 采样状态 + 活跃弹窗计数 + slot 注册表。
 */
class DialogBackdropState internal constructor(
    /** 采样源句柄；API<33 时为 null（不调用任何 miuix-blur 方法，类不加载） */
    val backdrop: LayerBackdrop?,
    /** Haze 外部模糊采样状态（与 backdrop 并存，同源 Box 双离屏录制） */
    val hazeState: HazeState,
) {
    /** 当前活跃弹窗数；> 0 才挂 backdrop 源 */
    var openCount by mutableIntStateOf(0)

    /** slot 注册表：弹窗浮层按注册顺序叠加渲染 */
    val entries = mutableStateListOf<DialogEntry>()
}

/** 单个 slot 条目：content 可观察，每次弹窗重组更新引用，驱动宿主 slot 渲染最新内容 */
class DialogEntry {
    var content by mutableStateOf<@Composable () -> Unit>({})
}

/**
 * 条件挂双采样源：仅弹窗活跃时挂（常驻整屏离屏录制不可取）。
 * - miuix backdrop：backdrop 存在（API 33+）且弹窗活跃
 * - Haze source：弹窗活跃 且 API >= 31（低版本无 RenderEffect，回落压暗）
 * 必须挂在**非滚动容器**（页面根 Box）上；页面内容作为其子节点嵌套。
 */
fun Modifier.dialogBackdropSource(state: DialogBackdropState): Modifier =
    then(if (state.backdrop != null && state.openCount > 0)
        Modifier.layerBackdrop(state.backdrop) else Modifier)
        .then(if (state.openCount > 0 && isHazeSupported)
            Modifier.dialogHazeSource(state.hazeState) else Modifier)

/**
 * 弹窗 backdrop 宿主。包在页面根（PowerMeterTheme 内层），提供 backdrop + 条件挂源 + 浮层 slot。
 *
 * 结构（源与 slot 同层兄弟，防自采样反馈环）：
 *   Box {
 *     Box(fillMaxSize().dialogBackdropSource) {                       // 源：录制页面内容
 *       Box(fillMaxSize().background(主题页底色)) { content() }        // 不透明底：保证采样层有像素
 *     }
 *     Box(fillMaxSize()) { entries }                                  // slot：渲染弹窗浮层
 *   }
 *
 * ⚠️ 采样源内必须有**不透明底色**：采样源只录制本 Box 内的 Compose 绘制，而 Activity 窗口背景
 * 由 DecorView 绘制、位于 Compose 树之外，不进入采样层。若页面根背景透明，采样层近乎全透明 →
 * 弹窗 scrim 的模糊结果为空 → 只剩 25% 压暗（观感"只压暗、不模糊"）。这里统一铺一层主题页底色
 * 兜底，与窗口背景同色，正常情况不可见，任何宿主页都不会再因页根透明而静默失效。
 */
@Composable
fun DialogBackdropHost(
    modifier: Modifier = Modifier.fillMaxSize(),
    onActiveChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val backdrop = rememberDialogBackdrop()
    val hazeState = remember { HazeState() }
    val state = remember { DialogBackdropState(backdrop, hazeState) }
    LaunchedEffect(state.openCount) { onActiveChanged(state.openCount > 0) }
    CompositionLocalProvider(LocalDialogBackdrop provides state) {
        Box(modifier) {
            // 源 Box：仅弹窗活跃时录制（兄弟①，先绘制 → slot 采样同帧页面内容，不含卡片）
            Box(Modifier.fillMaxSize().dialogBackdropSource(state)) {
                // 底色必须画在源 Box 内层（源之前绘制的背景不被采样）→ 采样层恒不透明
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
            // 浮层 slot：弹窗浮层在此渲染，与源 Box 兄弟（兄弟②）
            Box(Modifier.fillMaxSize()) {
                // 用 entry 对象作 key（非索引）：索引 key(i) 在弹窗切换（如 选曲线→颜色面板
                // 同帧替换，[A]→[B]）时，key(0) 组被 Compose 复用，新弹窗内容 lambda 被跳过不执行
                // → 内容不显示。entry 引用稳定：同一弹窗重组保留状态，不同弹窗切换强制全新组合。
                for (entry in state.entries) {
                    key(entry) { entry.content() }
                }
            }
        }
    }
}

/**
 * 弹窗浮层注册入口：把弹窗内容注册到宿主 slot（渲染为源 Box 兄弟）。
 * 无宿主（LocalDialogBackdrop 为 null）→ 就地渲染兜底，保持可用。
 */
@Composable
fun DialogOverlay(content: @Composable () -> Unit) {
    val state = LocalDialogBackdrop.current
    if (state == null) {
        content()
        return
    }
    val entry = remember { DialogEntry() }
    // 每次重组更新条目内容（可观察 → 宿主 slot 渲染最新，弹窗内部状态变化不失效）
    entry.content = content
    DisposableEffect(Unit) {
        state.openCount++
        state.entries.add(entry)
        onDispose {
            state.openCount--
            state.entries.remove(entry)
        }
    }
}

/**
 * 弹窗卡片进入动画：alpha 0→1（[initialScale] 默认 1f → 不缩放，底部弹层用纯淡入）。
 * 首次组合即可播，规避 AnimatedVisibility(visible=true) 首帧不播动画的问题。
 */
@Composable
fun Modifier.dialogEnterAnim(
    durationMillis: Int = 150,
    initialScale: Float = 1f,
): Modifier {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(initialScale) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(durationMillis))
        scale.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * 弹窗卡片柔和阴影（玻璃卡片统一）。shadow 须在外层（clip 之前）：clip 之后自绘会盖住阴影；
 * clip=false 必须显式（shadow() 默认 elevation>0 时 clip=true 会裁剪内容）。
 */
@Composable
fun Modifier.dialogCardShadow(elevation: Dp = 8.dp): Modifier =
    this.shadow(elevation, RoundedCornerShape(LocalCornerRadius.current), clip = false)

/**
 * 居中玻璃对话框（移植自 SportLink `BlurredAlertDialog`，复用本项目同一套「宿主 + slot」模糊基础设施）。
 *
 * 与全屏页 SportLink 弹窗（[SelectListDialog] / [ColorPaletteDialog]）完全同口径：
 * - 外部 Haze scrim（[dialogHazeEffect]，25% 黑压暗 + 20dp 模糊）；
 * - 内部居中玻璃卡：clip(shape) → miuix [textureBlur]（API33+，含 [Highlight] 边缘高光）
 *   → 半透明 tint → 实色描边；
 * - **居中显示**（`Alignment.Center`），**非**底部锚定 —— 这正是此前写成 bottom sheet 与 SportLink 的分歧点。
 *
 * 三段式布局（与 BlurredAlertDialog 一致）：标题固定 + 正文独立滚动（[text] 是唯一滚动容器）
 * + 按钮固定底部（[confirmButton]/[dismissButton]）。卡片高度上限 80% 屏高。
 *
 * @param onDismissRequest 点 scrim / 系统返回 关闭回调
 * @param confirmButton    确认按钮（[text] 非空时恒渲染；列表类弹窗可传空 lambda）
 * @param dismissButton    取消按钮（可空）
 * @param shape            卡片圆角（默认项目圆角 [LocalCornerRadius]）
 * @param containerColor   玻璃卡底色（API<33 实心降级色）
 * @param title            标题（居中），可空
 * @param text             正文（可滚动）；列表 / 色盘等放入此处
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(LocalCornerRadius.current),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    val state = LocalDialogBackdrop.current
    val bp = state?.backdrop   // API<33 时 null → 实心卡降级
    val glass = bp != null
    val isDark = isSystemInDarkTheme()

    // 三段式：标题固定 + 正文独立滚动 + 按钮固定底部（同 BlurredAlertDialog）
    val content: @Composable () -> Unit = {
        val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        // ⚠️ 必须显式提供 LocalContentColor：玻璃卡不是 Surface，不会像 Surface 那样按底色
        // 自动派生内容色；不提供时 LocalContentColor 落到 Compose 默认值 Color.Black ——
        // 深色模式下卡内所有文字（标题/列表项）都是纯黑，在深色玻璃上不可见
        // （用户报告：「曲线选择弹窗内部文字全部是硬编码黑色、没做深浅色切换」）。
        // 用 onSurface：浅色近黑、深色近白，随主题自动切换。
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .heightIn(max = maxContentHeight),
            ) {
                title?.let {
                    it()
                    if (text != null) Spacer(Modifier.height(16.dp))
                }
                text?.let { textContent ->
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        textContent()
                    }
                }
                if (text != null) Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }

    if (state != null) {
        // 主路径：同窗口浮层（与 BlurredAlertDialog 一致），居中
        DialogOverlay {
            BackHandler { onDismissRequest() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .dialogHazeEffect(state.hazeState)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismissRequest() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = modifier
                        .dialogWidthAdaptive()
                        .dialogEnterAnim(initialScale = 0.92f)
                        .dialogCardShadow()
                        .clip(shape)
                        // 玻璃链：外层 clip 必须（miuix 模糊层是节点边界+padding 矩形，不随 shape 裁剪）；
                        // blurRadius 单位 dp（DialogGlassBlurRadius = 10f 轻磨砂）；API<33 跳过
                        .then(
                            if (bp != null) Modifier.textureBlur(
                                bp, shape,
                                blurRadius = DialogGlassBlurRadius,
                                // 玻璃边缘高光（miuix Highlight，加粗带宽版 Middle 预设）
                                highlight = if (isDark) DialogGlassHighlightDark
                                else DialogGlassHighlightLight,
                            )
                            else Modifier
                        )
                        // 半透明 tint（叠加在模糊之上 / 实心降级）
                        .background(
                            if (glass) containerColor.copy(
                                alpha = if (isDark) DialogGlassTintAlphaDark else DialogGlassTintAlphaLight
                            )
                            else containerColor,
                        )
                        .border(
                            width = DialogGlassBorderWidth,
                            color = if (isDark) DialogGlassBorderDark else DialogGlassBorderLight,
                            shape = shape,
                        )
                        // ⚠️ 点击拦截（移植自 SportLink）：scrim 是上面的全屏 Box + clickable{onDismissRequest}，
                        // 卡片内容若无消费事件的节点（ColorPalette 色盘轻点、正文空白），点击会冒泡到
                        // scrim 误触发关闭。空 onClick 消费卡片内点击阻断冒泡。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { },
                ) {
                    content()
                }
            }
        }
    } else {
        // 无宿主兜底（本项目全屏页恒挂 DialogBackdropHost，正常不可达；保持可用）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismissRequest() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = modifier
                    .dialogWidthAdaptive()
                    .dialogEnterAnim(initialScale = 0.92f)
                    .clip(shape)
                    .background(containerColor)
                    .border(DialogGlassBorderWidth, if (isDark) DialogGlassBorderDark else DialogGlassBorderLight, shape),
            ) {
                content()
            }
        }
    }
}

/**
 * 弹窗宽度限制（移植自 SportLink `dialogWidthAdaptive`）：横屏上限 60% 屏宽，竖屏 90%（低 DPI 95%）。
 * 居中对话框挂在全屏 scrim 内，宽度受此约束避免过宽。
 */
@Composable
fun Modifier.dialogWidthAdaptive(): Modifier {
    val configuration = LocalConfiguration.current
    val dpi = LocalContext.current.resources.displayMetrics.densityDpi
    val maxWidthDp = when {
        // 横屏：上限 60%
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ->
            (configuration.screenWidthDp * 0.6f).dp
        // 低 DPI 设备：95%
        dpi < 200 -> (configuration.screenWidthDp * 0.95f).dp
        // 默认：90%
        else -> (configuration.screenWidthDp * 0.9f).dp
    }
    return this.widthIn(max = maxWidthDp)
}
