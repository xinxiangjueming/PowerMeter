package com.chen.powermeter.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chen.powermeter.R
import com.chen.powermeter.ui.theme.LocalCornerRadius
import com.chen.powermeter.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.basic.ColorPalette

/**
 * 曲线颜色仓库（进程内单例 + StateFlow 广播）。
 *
 * 为什么不是「用的时候直接读 Prefs」：颜色可能在**全屏页**修改，返回主页面后卡片曲线
 * 必须立刻变色。用 StateFlow 让两处订阅同一个值即可实时同步，不必依赖 onResume 重读。
 * 落库仍走 [Prefs]（按指标名分别记忆），进程重启后由 [load] 恢复。
 */
internal object ChartColors {

    private val _colors = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 指标名 → ARGB；只包含用户**自定义过**的指标 */
    val colors: StateFlow<Map<String, Int>> = _colors.asStateFlow()

    /** 进程启动时从 [Prefs] 恢复一次（MainActivity / TrendFullscreenActivity 的 onCreate 各调一次） */
    fun load(context: Context) {
        val app = context.applicationContext
        _colors.value = Metric.entries
            .mapNotNull { metric -> Prefs.getMetricColor(app, metric.name)?.let { metric.name to it } }
            .toMap()
    }

    fun set(context: Context, metric: Metric, color: Color) {
        val app = context.applicationContext
        val argb = color.toArgb()
        Prefs.setMetricColor(app, metric.name, argb)
        _colors.value = _colors.value + (metric.name to argb)
    }
}

/** 指标当前的曲线色（自定义色优先，否则回落到默认配色）。[Metric.seriesColor] 的 Composable 封装 */
@Composable
internal fun rememberMetricColor(metric: Metric): Color = metric.seriesColor(
    custom = ChartColors.colors.collectAsState().value,
    primary = MaterialTheme.colorScheme.primary,
)

/**
 * 指标曲线色：**自定义色优先，否则回落到默认配色**。
 *
 * - **POWER 默认沿用主题 `primary`**：这是「只画一条功率曲线」这一最常用形态，
 *   保持既有观感不变（改动前 TrendChart 用的就是 `colorScheme.primary`）。
 * - VOLTAGE / CURRENT / TEMP 给定固定色：横屏全屏页可多选叠加，四条曲线必须彼此可区分，
 *   若都跟随主题色则叠在一起无法辨识。色值口径与 SportLink 曲线默认调色板同源。
 *
 * 做成非 Composable 纯函数，是为了让需要在 `remember` 键里取色的调用方（趋势全屏页缓存
 * 曲线序列）也能复用 —— 否则那里得另写一份 when 分支，日后改默认色就会两处分叉。
 *
 * @param custom 用户自定义色表（指标名 → ARGB），一般来自 `ChartColors.colors`
 * @param primary 主题主色（仅 POWER 未自定义时使用）
 */
internal fun Metric.seriesColor(custom: Map<String, Int>, primary: Color): Color {
    custom[name]?.let { return Color(it) }
    return when (this) {
        Metric.POWER -> primary
        Metric.VOLTAGE -> Color(0xFF2979FF)
        Metric.CURRENT -> Color(0xFF00E676)
        Metric.TEMP -> Color(0xFFFF9100)
        // 充电 IC 温度（温感区 charger_therm0，非 root 也有）：青，与电池温度的橙、
        // PMIC 温度的紫一眼可区分；取预置色板内的值，打开颜色面板时当前色会被直接高亮
        Metric.CHARGER_TEMP -> Color(0xFF00BCD4)
        // PMIC 温度（仅真 root 机器出现）：紫，与电池温度的橙同属"读温度"但一眼可区分；
        // 取预置色板内的值，用户打开颜色面板时当前色会被直接高亮
        Metric.PMIC_TEMP -> Color(0xFF9C27B0)
    }
}

/**
 * 曲线颜色的文字/元素对比色：亮度高的底配黑字，否则配白字。
 *
 * 不能像 SportLink `SmallPillButton` 那样写死 `Color.White`——那里底色恒为深色 monet 色；
 * 本应用的颜色由用户自由指定，浅色（如黄、白）底配白字会完全看不见。
 */
internal fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.55f) Color(0xFF1B1B1B) else Color.White

/** 预置色：与 SportLink 曲线调色板同源，末尾补黑白，保证明暗两种主题下都有高对比可选 */
private val PresetSwatches = listOf(
    Color(0xFFFF1744), Color(0xFF2979FF), Color(0xFF00E676),
    Color(0xFFFF9100), Color(0xFF9C27B0), Color(0xFF00BCD4),
    Color(0xFFFFEB3B), Color(0xFF8BC34A), Color(0xFFE91E63),
    Color(0xFF795548), Color(0xFF000000), Color(0xFFFFFFFF),
)

/**
 * 曲线颜色选择面板（bottom sheet）。
 *
 * 交互形态取项目既有规范：设置类交互一律 bottom sheet 上滑（口径对齐主页面 SettingsSheet），
 * 自定义拖拽横条 + 居中标题 + 底部 insets 避让；色盘本身用 miuix
 * [ColorPalette]（与 SportLink `ColorPaletteDialog` 同一个组件、同一版本），保证
 * 「颜色选择样式」跨项目一致。
 *
 * 与 SportLink 的两点差异（都是本项目的实际情况决定）：
 * 1. 容器是 sheet 而非 AlertDialog —— 本项目没有弹窗模糊组件，也没有 Dialog 体系；
 * 2. `showPreview = false` —— 顶部预览条与下方预置色块网格表达重复，省下约 38dp 高度，
 *    横屏（全屏页也会开这个面板）下更不容易溢出。
 *
 * 内容整体可竖向滚动：横屏时可用高度只有 360dp 上下，色盘固定 180dp + 预览/滑块
 * 必然超出一屏，必须能滚，否则「确定」按钮点不到。
 *
 * @param initialColor 初始颜色（取当前曲线色，色盘指示器会落到对应色格）
 * @param onPick       点「确定」回调；已强制 alpha=1（见下方注释）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorPickerSheet(
    title: String,
    initialColor: Color,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val corner = LocalCornerRadius.current
    val buttonShape = remember(corner) { RoundedCornerShape(corner) }
    val swatchShape = remember { RoundedCornerShape(50) }
    val isDark = isSystemInDarkTheme()
    // 开板态按方向区分（只改竖屏，横屏观感保持不变）——需在 rememberBottomSheetState 之前读到
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 行/色块框线：浅色模式浅黑、深色模式浅白（与项目弹窗边框体系一致）
    val borderColor = if (isDark) Color(0x22FFFFFF) else Color(0x1A000000)
    // 草稿色：确定前不落库，取消/关闭即丢弃
    var draft by remember { mutableStateOf(initialColor) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 开板态按方向区分（只改竖屏，横屏观感保持不变）。
        // ⚠️ material3 1.5.0-alpha27 的 rememberBottomSheetState **没有** skipPartiallyExpanded 参数
        //    （那是旧 API rememberModalBottomSheetState 的），它用 enabledValues 集合；而
        //    SheetState.show() 的落点是「enabledValues 含 PartiallyExpanded → 先停半展开；否则 → Expanded」
        //    （androidx material3 1.5.0-alpha27 sources：commonMain/.../SheetDefaults.kt:283-290）。
        // · 竖屏：去掉 PartiallyExpanded → 开板即全展开。本面板内容 ≈ 420dp（色盘固定 180dp +
        //   预置色块两行 80dp + 标题/滑块/按钮）超过竖屏半屏，停在半展开会把底部「取消/确定」
        //   压到屏幕外，用户还得再上滑一次。
        // · 横屏：维持默认（含 PartiallyExpanded），可用高度只有 360dp 上下，开板观感与改动前一致；
        //   内容仍保留 verticalScroll，超高时照旧可滚。
        //   enabledValues 是 rememberSaveable 的 key（同文件 :719-725），旋转后会正确重建状态。
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = if (isLandscape) {
                setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
            } else {
                setOf(SheetValue.Hidden, SheetValue.Expanded)
            },
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // ⚠️ 必须显式去掉 Bottom，否则本面板**永远不沉浸**（已核 material3 1.5.0-alpha27 源码）：
        //  · M3 默认 contentWindowInsets = BottomSheetDefaults.modalWindowInsets
        //      = WindowInsets.safeDrawing.only(Bottom + Top)        （SheetDefaults.kt:553-555）
        //  · 它被加在 **Surface 内部的 Column** 上，不是 Surface 外面：
        //      Surface(...) { Column(Modifier.windowInsetsPadding(contentWindowInsets())) }
        //                                                          （BottomSheet.kt:350-353）
        //    相当于替我们把内容底部抬高一个导航栏高度 → 滚动视口到不了屏幕底 →
        //    内容滚不穿手势小白条（「滑动也不沉浸」）；且该 insets 被此处消费后，
        //    内容末尾的 windowInsetsBottomHeight(WindowInsets.safeDrawing) 只能取到 0。
        // 去掉 Bottom 后：Surface 背景照旧铺满到屏幕底（背景沉浸），
        // 底部避让由内容末尾那个 Spacer 自己负责（此时它能读到真值）。
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
        // 与 SettingsSheet 同口径：dragHandle = null，改用自绘横条，
        // 彻底规避 M3 默认手柄长按弹出的「拖动手柄」tooltip
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = swatchShape,
                        ),
                )
            }
            Text(
                title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.label_quick_pick),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            // 分两行、每行 6 个：单行 12 个在竖屏手机上（可用宽度约 320dp）必然溢出；
            // SpaceBetween 让色块均匀铺满整行，不硬编码间距
            PresetSwatches.chunked(6).forEach { rowColors ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    rowColors.forEach { preset ->
                        val selected = preset.toArgb() == draft.toArgb()
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(preset, swatchShape)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        borderColor
                                    },
                                    shape = swatchShape,
                                )
                                // clip 必须紧贴 clickable 之前：ripple 以节点矩形为边界，
                                // 无 clip 时会在圆形之外溢出方形水波纹（项目水波纹规范）
                                .clip(swatchShape)
                                .clickable { draft = preset },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
            ColorPalette(
                color = draft,
                onColorChanged = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                showPreview = false,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = buttonShape,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        // ColorPalette 自带 alpha 滑块；曲线 alpha<1 会与下方渐变填充叠加，
                        // 得到一条"看起来更暗"的线，与色块显示的色不一致 → 落库前统一压回不透明
                        onPick(draft.copy(alpha = 1f))
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = buttonShape,
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

/**
 * 多曲线叠加时的「选曲线」弹窗（居中玻璃对话框，移植自 SportLink `SelectListDialog`）。
 *
 * 对齐 SportLink `ChartFullscreenActivity.showColorPickerForSelection()`：叠加多条曲线时点颜色按钮
 * 先让用户挑"给哪条改色"，再打开颜色面板；否则颜色按钮只能改首条曲线，其余叠加曲线永远改不了色，
 * 也永远不会出现"选择曲线"这一层 —— 即 2026-09-21 用户报告的「点击颜色没有设备选择弹窗」。
 *
 * ⚠️ 形态分工（用户拍板 2026-09-21）：
 * - **选曲线**这一层 = 居中 `GlassDialog`（本函数，外部 Haze 模糊 + 内部 miuix 毛玻璃 + Highlight 描边）；
 * - **颜色面板** [ColorPickerSheet] = 底部弹层（`ModalBottomSheet`），不是居中弹窗。
 * 单列指标（只有一条曲线）时本弹窗不会被调用 —— 颜色按钮会直接打开 [ColorPickerSheet]。
 *
 * @param metrics 当前叠加的指标列表（调用方保证 size > 1 才打开本弹窗）
 * @param onSelect 点某条曲线回调，参数为被选中的指标（随后由调用方打开 [ColorPickerSheet]）
 * @param onDismiss 关闭（点遮罩 / 系统返回）回调
 */
@Composable
internal fun CurveSelectSheet(
    metrics: List<Metric>,
    onSelect: (Metric) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    // 行框线：浅色模式浅黑、深色模式浅白（与项目弹窗边框体系一致）
    val borderColor = if (isDark) Color(0x22FFFFFF) else Color(0x1A000000)

    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.select_curve),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                metrics.forEach { m ->
                    val dot = rememberMetricColor(m)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(m) }
                            .padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 当前曲线色点：和 tab 上的色点同一个取色口径（rememberMetricColor）
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(dot, RoundedCornerShape(50)),
                        )
                        Text(
                            m.label(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    // 行分隔：只画一条水平细线（除末行）。
                    // ⚠️ 不能用 Modifier.border(shape = RoundedCornerShape(0.dp)) 冒充「下边框」——
                    // border() 会给四条边都描边，于是每行被画成一个方角矩形框（即用户报告的
                    // 「设备列是方角的」）。用 HorizontalDivider 只留底部一条线。
                    if (m != metrics.last()) {
                        HorizontalDivider(color = borderColor, thickness = 1.dp)
                    }
                }
            }
        },
        // 列表类弹窗无按钮：点项即选、点遮罩/返回即关（与 SportLink SelectListDialog 同口径）
        confirmButton = {},
    )
}
