package com.chen.powermeter.ui

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chen.powermeter.R
import com.chen.powermeter.data.BatteryInfo
import com.chen.powermeter.data.PowerSample
import com.chen.powermeter.data.RootPowerReader
import com.chen.powermeter.data.SampleStore
import com.chen.powermeter.data.SessionStats
import com.chen.powermeter.ui.common.BlurTopBar
import com.chen.powermeter.ui.theme.LocalCornerRadius
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeSource
import java.util.Locale

/**
 * 数字 / 单位统一等宽字体。
 *
 * 口径对齐 SportLink「运动详细卡片」（ui/DetailGrid.kt:124 / 189）：数值与单位走
 * FontFamily.Monospace，避免高频刷新（最快 500ms 一次）时数字宽度跳动。
 *
 * 不用 fontFeatureSettings = "tnum" 的原因：该 OpenType 特性依赖系统字体实现，
 * 而 MIUI/HyperOS 默认字体 MiSans 官方字体页标注的等宽数字 tag 是自定义 tag "thum"，
 * 标准 "tnum" 在小米设备上不生效；FontFamily.Monospace 不依赖字体特性，结果确定。
 */
private val NumericFontFamily = FontFamily.Monospace

/**
 * 指标网格「卡片显隐」过渡时长（进入 300ms / 退出 250ms）。
 *
 * 场景是 binder 兜底时的**一次性**布局切换（第二行整行收起 + 第二列内容互换），
 * 不是每帧触发的动画；曲线取 FastOutSlowInEasing（M3 Standard 口径），与 miuix 观感一致。
 */
private const val GRID_ENTER_MS = 300
private const val GRID_EXIT_MS = 250

private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)

private fun Double?.f3OrDash(): String = this?.f3() ?: "—"

/** 温度类（电池 / 最高温度）按用户约定取 1 位小数（2026-09-21）；充电 IC 温度取 3 位，接口温度取整 */
private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

private fun Double?.f1OrDash(): String = this?.f1() ?: "—"

/**
 * 整数档（2026-09-21 用户约定）：用于底层分辨率只到个位的量 —— 电流 mA（内核只上报 mA 整数）、
 * 接口温度、剩余 / 满充 / 设计容量 mAh。显示与 CSV 记录同口径。
 */
private fun Double.f0(): String = String.format(Locale.US, "%.0f", this)

private fun Double?.f0OrDash(): String = this?.f0() ?: "—"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PowerMeterScreen(
    running: Boolean,
    samples: List<PowerSample>,
    batteryInfo: BatteryInfo?,
    error: String?,
    intervalMs: Long,
    wakeLock: Boolean,
    /** 充电功率监测：开始采样 5s 后自动熄屏，输入电流为 0 持续 30s 时自动保存 CSV */
    chargeMonitor: Boolean,
    /** 串联双电池：电压按整组（单节读数 ×2）换算，功率同步 ×2 */
    seriesDualBattery: Boolean,
    /** Shizuku 是否在位（已安装且服务运行） */
    shizukuAvailable: Boolean,
    /** Shizuku 是否已授权本应用 */
    shizukuGranted: Boolean,
    /** Shizuku UserService 是否已绑定（真正可以执行命令） */
    shizukuBound: Boolean,
    /** 非空 = 当前展示的是导入的 CSV（而非实时采样），值为文件名 */
    importedName: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIntervalChange: (Long) -> Unit,
    onWakeLockChange: (Boolean) -> Unit,
    onChargeMonitorChange: (Boolean) -> Unit,
    onSeriesDualBatteryChange: (Boolean) -> Unit,
    /** 申请 Shizuku 授权（弹系统授权框） */
    onShizukuRequest: () -> Unit,
    /** 强制重绑 Shizuku UserService */
    onShizukuRetry: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onExitImport: () -> Unit,
    /** 双击顶栏标题：切换到另一个监测模式。入参 = 标题栏的窗口矩形（ClipReveal 锚点展开的起点） */
    onToggleMode: (Rect) -> Unit,
) {
    val corner = LocalCornerRadius.current
    val cardShape = remember(corner) { RoundedCornerShape(corner) }
    var settingsOpen by remember { mutableStateOf(false) }
    // 本机可切换的指标 tab：PMIC 温度只在真 root 机器上出现（见 rememberAvailableMetrics）
    val availableMetrics = rememberAvailableMetrics()
    var selectedMetric by remember { mutableStateOf(Metric.POWER) }
    // 图表与 tab 行统一读这个**派生值**：root 结论若在会话中变化（RootPowerReader 复位探测
    // 缓存后重探），当前选中项可能不再可用 —— 此时自动回落到功率，否则会出现"图表画着
    // PMIC 温度、tab 行里却没有对应胶囊"的错位，用户无法切回去。派生而非副作用改名，
    // 不会多触发一轮重组。
    val metric = selectedMetric.takeIf { it in availableMetrics } ?: Metric.POWER
    // 非空 = 颜色面板打开中，值为正在编辑的指标
    var colorTarget by remember { mutableStateOf<Metric?>(null) }
    // 实时态用环形缓冲维护的 O(1) 增量统计（档二-1）；导入态数据是一次性的，沿用全量重算。
    // 两者公式一致（梯形积分），差别只在实时侧改成了「会话累计」语义 —— 详见 SampleStore 的说明。
    val liveStats by SampleStore.stats.collectAsState()
    val stats = if (importedName != null) {
        remember(samples) { computeStats(samples) }
    } else {
        liveStats
    }
    val latest = samples.lastOrNull()
    val bg = MaterialTheme.colorScheme.background
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val metricColor = rememberMetricColor(metric)
    // 趋势卡 → 全屏：起独立 Activity（真沉浸隐藏系统栏 + 避让摄像头 + **ClipReveal 一镜
    // 到底转场**：全屏页把自身 UI 渲染进 ClipReveal 覆盖层，从 `< >` 按钮矩形四边同步撑开
    // 铺满、退出收回到按钮矩形 —— 锚点跨"竖屏源窗口 → 横屏目标窗口"的坐标换算在目标页做，
    // 这里只负责把按钮矩形（源窗口坐标）与源窗口宽高一起传过去）。
    // 采样数据无需跨页传递：全屏页直接读 SampleStore（实时环形缓冲）/ ImportedSeries 两个单例。
    val openTrendFullscreen: (Rect) -> Unit = { bounds ->
        val dm = context.resources.displayMetrics
        if (dm.widthPixels <= dm.heightPixels) {
            // 竖屏源窗口：锚点矩形换算的前提成立（mapPortraitRectToWindow）
            TrendFullscreenActivity.sourcePortraitWidth = dm.widthPixels
            TrendFullscreenActivity.sourcePortraitHeight = dm.heightPixels
            TrendFullscreenActivity.launch(
                context, metric,
                android.graphics.Rect(
                    bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt(),
                ),
            )
        } else {
            // 横握设备下的主页（源窗口已是横屏）：跨方向换算不适用 → 不带锚点，
            // 全屏页退化为过中心的全宽线展开（仍是一镜到底，只是无矩形本体）
            TrendFullscreenActivity.launch(context, metric, null)
        }
    }

    // 内容区水平 insets：**只避挖孔，不避导航栏**
    // （口径对齐 sportlink 历史列表 ui/HistoryScreen.kt:313：
    //  windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))）
    //
    // safeDrawing = systemBars ∪ displayCutout，横屏时 systemBars 含侧边手势条；
    // 用它做水平避让会把内容从手势条一侧额外顶开一整个导航栏宽度，
    // 那条区域只剩纯背景色 → 观感上就是"小白条没沉浸"。
    // 真沉浸 = 背景铺满全屏 + 内容延伸到手势条之下，手势条浮在内容上。
    val sideInsets = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)

    // 顶栏：顶部要避状态栏 + 挖孔（TopAppBar 默认 insets 只含 systemBars，
    // 横屏挖孔在左/右侧会压住标题）；水平与内容区同口径只避挖孔，
    // 否则顶栏比内容窄一截，手势条一侧出现断层
    val topBarInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Top)
        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

    // ---- 顶栏三档模糊（移植自 fold ui/common/BlurTopBar.kt）----
    // API≥33 → com.kyant.backdrop 渐进式 AGSL 模糊；31–32 → Haze 真实模糊；26–30 → 渐变盖板
    val hazeState = remember { HazeState() }
    val topBarHazeStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        blurRadius = 20.dp,
        tint = null,
    )
    val useKyantTopBar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val topBarBackdrop = rememberLayerBackdrop()
    // 顶栏总高 = 状态栏/挖孔高度 + TopAppBar 64dp；内容层据此起排，滚动内容从顶栏下方穿过
    val topBarHeight = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 64.dp

    Box(Modifier.fillMaxSize().background(bg)) {
        // 内容层 = 模糊采样源（haze 与 kyant 同挂一节点，互不干扰）
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .then(if (useKyantTopBar) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
        ) {
            if (landscape) {
                // 横屏：左右两列，以「趋势」为分界
                // 左列 = 主功率卡 + 指标网格；右列 = 趋势 + 统计 + 电池 + 控制按钮
                Row(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(sideInsets)
                        // ⚠️ 横屏**不挂 bottom padding**：Row 是滚动视口的父节点，底部的 12dp 会把视口
                        //    从窗口底边顶开，高卡片被视口硬切 → 观感就是"内容一到小白条区域就被裁切"。
                        //    竖屏分支与参照实现同理（fold 文件管理器：RecyclerView 内 padding +
                        //    clipToPadding=false，内容一直画到窗口底边；sportlink 设备管理：根部不避让）。
                        //    滚动末尾的 BottomInsetSpacer() 已负责让最后一张卡片能滚出导航栏区域。
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            // top padding 在 verticalScroll 之后：内容滚动时从顶栏下方穿过
                            .padding(top = topBarHeight),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        error?.let { ErrorCard(it, cardShape) }
                        importedName?.let { ImportBanner(it, samples.size, cardShape, onExitImport) }
                        HeroPowerCard(latest, running, cardShape)
                        MetricGrid(latest, cardShape)
                        BottomInsetSpacer()
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(top = topBarHeight),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TrendCard(
                            samples = samples,
                            metric = metric,
                            tabs = availableMetrics,
                            metricColor = metricColor,
                            onMetricChange = { selectedMetric = it },
                            onColorClick = { colorTarget = metric },
                            shape = cardShape,
                            onFullscreenClick = openTrendFullscreen,
                        )
                        StatsCard(stats, cardShape)
                        if (batteryInfo != null) BatteryInfoCard(batteryInfo, cardShape)
                        ControlRow(running, onStart, onStop, onExport)
                        BottomInsetSpacer()
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(sideInsets)
                        .verticalScroll(rememberScrollState())
                        .padding(top = topBarHeight)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let { ErrorCard(it, cardShape) }
                    importedName?.let { ImportBanner(it, samples.size, cardShape, onExitImport) }
                    HeroPowerCard(latest, running, cardShape)
                    MetricGrid(latest, cardShape)
                    TrendCard(
                        samples = samples,
                        metric = metric,
                        tabs = availableMetrics,
                        metricColor = metricColor,
                        onMetricChange = { selectedMetric = it },
                        onColorClick = { colorTarget = metric },
                        shape = cardShape,
                        onFullscreenClick = openTrendFullscreen,
                    )
                    StatsCard(stats, cardShape)
                    if (batteryInfo != null) BatteryInfoCard(batteryInfo, cardShape)
                    ControlRow(running, onStart, onStop, onExport)
                    BottomInsetSpacer()
                }
            }
        }

        // 毛玻璃顶栏：必须位于采样源节点之外作为兄弟，避免 MIUI/HyperOS 自引用崩溃
        BlurTopBar(
            kyantBackdrop = if (useKyantTopBar) topBarBackdrop else null,
            hazeState = if (useKyantTopBar) null else hazeState,
            hazeStyle = if (useKyantTopBar) null else topBarHazeStyle,
        ) {
            TopAppBar(
                title = {
                    // 标题的窗口矩形：双击时把标题本体换成窗口坐标，作为模式切换
                    // ClipReveal 锚点展开的起点（展开从标题矩形长大，见 ModeRevealOverlay）
                    var titleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    Column(
                        // 双击标题 = 切换监测模式（功率监测 ↔ 帧率监测）。
                        // 手势只挂在标题这一列上：顶栏其余区域（尤其右侧「设置」按钮）
                        // 不该被双击误触，也不必为整条顶栏多加一层手势节点。
                        Modifier
                            .onGloballyPositioned { titleCoords = it }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    // boundsInWindow() 在本 Compose 版本不可用 → positionInWindow + size 手动拼
                                    val c = titleCoords?.takeIf { it.isAttached }
                                    val pos = c?.localToWindow(Offset.Zero) ?: Offset.Zero
                                    onToggleMode(
                                        Rect(
                                            pos.x, pos.y,
                                            pos.x + (c?.size?.width ?: 0),
                                            pos.y + (c?.size?.height ?: 0),
                                        ),
                                    )
                                })
                            },
                    ) {
                        Text(
                            stringResource(R.string.mode_power),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                        if (running) {
                            stringResource(R.string.status_sampling, intervalMs)
                        } else {
                            stringResource(R.string.status_stopped)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = NumericFontFamily,
                    )
                    }
                },
                // 容器透明：模糊背景由 BlurTopBar 绘制在下层
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = topBarInsets,
                actions = {
                    FilledTonalButton(
                        onClick = { settingsOpen = true },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_settings),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            intervalMs = intervalMs,
            wakeLock = wakeLock,
            chargeMonitor = chargeMonitor,
            seriesDualBattery = seriesDualBattery,
            shizukuAvailable = shizukuAvailable,
            shizukuGranted = shizukuGranted,
            shizukuBound = shizukuBound,
            corner = corner,
            onIntervalChange = onIntervalChange,
            onWakeLockChange = onWakeLockChange,
            onChargeMonitorChange = onChargeMonitorChange,
            onSeriesDualBatteryChange = onSeriesDualBatteryChange,
            onShizukuRequest = onShizukuRequest,
            onShizukuRetry = onShizukuRetry,
            onClear = onClear,
            onDismiss = { settingsOpen = false },
        )
    }

    colorTarget?.let { target ->
        ColorPickerSheet(
            title = stringResource(R.string.color_sheet_title, target.label()),
            initialColor = rememberMetricColor(target),
            onPick = { picked ->
                ChartColors.set(context, target, picked)
                colorTarget = null
            },
            onDismiss = { colorTarget = null },
        )
    }
}

// ---------- 通用小组件 ----------

/** 错误提示卡（竖屏单列与横屏两列共用） */
@Composable
private fun ErrorCard(message: String, shape: RoundedCornerShape) {
    ElevatedCard(
        shape = shape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 「正在查看导入的 CSV」提示条。
 *
 * 必须显眼地给出**来源与条数**并配一个明确退出口：导入态下曲线看着与实时一模一样，
 * 不标注的话用户会以为采样数据被换掉了。退出后自动回到实时曲线。
 */
@Composable
private fun ImportBanner(
    fileName: String,
    count: Int,
    shape: RoundedCornerShape,
    onExit: () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.import_banner_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.import_banner_count, fileName, count),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = NumericFontFamily,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                onClick = onExit,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(R.string.action_exit_import),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** 滚动内容末尾的底部系统栏避让（导航栏 / 手势条） */
@Composable
private fun BottomInsetSpacer() {
    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
}

// ---------- 主功率卡 ----------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroPowerCard(
    sample: PowerSample?,
    running: Boolean,
    shape: RoundedCornerShape,
) {
    val charging = sample?.isCharging == true
    val accent = if (charging) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        sample == null -> stringResource(R.string.hero_waiting)
                        charging -> stringResource(R.string.hero_charging)
                        else -> stringResource(R.string.hero_discharging)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 只在「已启动、还没有首个样本」这段真实等待期转一下（档二-3）。
                // 采样全程挂着它等于让界面每帧都有动画驱动 —— 亮屏时白耗电、白掉帧，
                // 而它对"采样正在进行"的表达并不比旁边的实时读数更强。
                if (running && sample == null) {
                    Spacer(Modifier.size(10.dp))
                    LoadingIndicator(modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            // 整组贴右缘：单位 "W" 右端钉死，数值长度变化（— → 4.421）时只向左侧扩展，单位不位移
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    sample?.powerW?.f3() ?: "—",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp),
                    fontWeight = FontWeight.Bold,
                    fontFamily = NumericFontFamily,
                    color = accent,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "W",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = NumericFontFamily,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(stringResource(R.string.hero_soc, sample?.socPct ?: 0))
                    sample?.chargeType?.takeIf { it.isNotBlank() }?.let {
                        append(stringResource(R.string.sep_dot)).append(it)
                    }
                    sample?.remainingMah?.let {
                        append(stringResource(R.string.sep_dot))
                        append(stringResource(R.string.hero_remaining, it.f3()))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = NumericFontFamily,
            )
        }
    }
}

// ---------- 指标网格 ----------

@Composable
private fun MetricGrid(sample: PowerSample?, shape: RoundedCornerShape) {
    // ⚠️ 判据**不能**用 binderFallback：它只代表「power_supply 被 SELinux 拦」，并不代表这两个温度拿不到 ——
    // 接口温度 / 充电 IC 温度实际取自 thermal 温感区（type=usb / charger_therm0），shell(Shizuku) 身份可读，
    // 本机实测 36.0℃ / 41.8℃，CSV 两列也有值。旧判据会把明明拿得到的温度藏起来，造成
    // 「CSV 有值、界面看不到」（用户 2026-09-21 报告）。
    // 改为按「该温度是否真的拿不到」判：仅当**有样本且两个温度都缺席**时才降级
    // （开路电压顶替接口温度的位置、收起充电 IC 温度行）；无样本时乐观显示温度卡，保持空闲态观感。
    val tempsUnavailable =
        sample != null && sample.tempUsbC == null && sample.tempChargerC == null
    // 「显隐」是一次性布局切换（点开始采样后 binder 兜底生效那一次），不是每帧动画；
    // 曲线取 FastOutSlowInEasing（M3 Standard 口径），进入略长于退出。
    val enterFade = remember { tween<Float>(GRID_ENTER_MS, easing = FastOutSlowInEasing) }
    // expandVertically / shrinkVertically 的 animationSpec 是 FiniteAnimationSpec<IntSize>（按整体尺寸补间），不是 Int
    val enterSize = remember { tween<IntSize>(GRID_ENTER_MS, easing = FastOutSlowInEasing) }
    val exitFade = remember { tween<Float>(GRID_EXIT_MS, easing = FastOutSlowInEasing) }
    val exitSize = remember { tween<IntSize>(GRID_EXIT_MS, easing = FastOutSlowInEasing) }
    val lVoltage = stringResource(R.string.metric_voltage)
    val lCurrent = stringResource(R.string.metric_current)
    val lBatteryTemp = stringResource(R.string.metric_battery_temp)
    val lOcv = stringResource(R.string.metric_ocv)
    val lUsbTemp = stringResource(R.string.metric_usb_temp)
    val lChargerIcTemp = stringResource(R.string.metric_charger_ic_temp)
    // ⚠️ 外层 Column **不能**用 verticalArrangement.spacedBy：被隐藏的分支也占一份 spacing，
    //    第三行收起后会残留 12dp 空隙。间距改由显式 Spacer 与 AnimatedVisibility **内容自带**
    //    的 top padding 承担，高度动画才能干净地归零。
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(lVoltage, sample?.voltageV.f3OrDash(), "V", shape, Modifier.weight(1f))
            MetricCard(lCurrent, sample?.currentMa.f0OrDash(), "mA", shape, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(lBatteryTemp, sample?.tempBatteryC.f1OrDash(), "℃", shape, Modifier.weight(1f))
            // 同一个格子内换内容：接口温度 ↔ 开路电压。两张卡结构一致（高度相同），
            // 交叉淡入淡出即可；加 SizeTransform 反而会让容器尺寸抖一下。
            AnimatedContent(
                targetState = tempsUnavailable,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(enterFade) togetherWith fadeOut(exitFade) },
                label = "slotOcvOrUsbTemp",
            ) { unavailable ->
                if (unavailable) {
                    MetricCard(lOcv, sample?.voltageOcvV.f3OrDash(), "V", shape, Modifier.fillMaxWidth())
                } else {
                    MetricCard(lUsbTemp, sample?.tempUsbC.f0OrDash(), "℃", shape, Modifier.fillMaxWidth())
                }
            }
        }
        // 整行出现 / 消失：透明度与高度一起过渡，从顶边收起（不是向中间塌陷）
        AnimatedVisibility(
            visible = !tempsUnavailable,
            enter = fadeIn(enterFade) + expandVertically(enterSize, expandFrom = Alignment.Top),
            exit = fadeOut(exitFade) + shrinkVertically(exitSize, shrinkTowards = Alignment.Top),
        ) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(lOcv, sample?.voltageOcvV.f3OrDash(), "V", shape, Modifier.weight(1f))
                MetricCard(lChargerIcTemp, sample?.tempChargerC.f3OrDash(), "℃", shape, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    unit: String,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            // 同上：数值 + 单位整组贴右，采样开始后单位不会随数值位数变化而左右跳动
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = NumericFontFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = NumericFontFamily,
                )
            }
        }
    }
}

// ---------- 趋势曲线 ----------

/**
 * 趋势曲线的可选指标。
 *
 * internal（而非 private）：趋势全屏页 TrendFullscreenActivity 与本文件同模块，
 * 需复用同一枚举与 [TrendChart]（见 TrendChartView.kt），
 * 避免两处各写一套导致口径/配色分叉。
 */
internal enum class Metric(@StringRes val labelRes: Int) {
    POWER(R.string.metric_power),
    VOLTAGE(R.string.metric_voltage),
    CURRENT(R.string.metric_current),
    TEMP(R.string.metric_temp),

    /**
     * 充电 IC 温度（温感区 `charger_therm0`）。
     *
     * ⚠️ 与 [PMIC_TEMP] **不是一回事** —— 取自**不同温感区**（`charger_therm0` vs
     * `pm8350c_tz` / `pm8350b_tz`），两者读数是两个不同的器件温度。
     * 该温感区在 shell(Shizuku) 身份下即可读（本机实测 41.8℃），故**非 root 机器也列出本 tab**，
     * 不按 root 过滤（对比 [PMIC_TEMP]）。
     */
    CHARGER_TEMP(R.string.metric_charger_ic_temp),

    /**
     * 主 PMIC 温度（温感区 `pm8350c_tz` / `pm8350b_tz`）。
     *
     * ⚠️ 该节点由 root 通道稳定取得，UI 侧**仅在真 root 机器**列出本 tab
     * （见 [rememberAvailableMetrics]），Shizuku(shell) 机器不列出。
     */
    PMIC_TEMP(R.string.metric_pmic_temp),
    ;

    fun value(s: PowerSample): Double = when (this) {
        POWER -> s.powerW
        VOLTAGE -> s.voltageV
        CURRENT -> s.currentMa
        TEMP -> s.tempBatteryC
        // 无读数（旧版 15 列 CSV 不含该列、或本机缺该温感区）返回 NaN：
        // 曲线按"断点"处理、读数与刻度显示破折号，绝不回落成 0.0 —— 否则会被画成
        // 一条贴在 0℃ 的假曲线，与 CSV 导入"留空即 null"的口径（README 3.x）相悖
        CHARGER_TEMP -> s.tempChargerC ?: Double.NaN
        PMIC_TEMP -> s.tempPmicC ?: Double.NaN
    }
}

/**
 * 指标名的本地化文本。
 *
 * 做成 Composable 扩展而非枚举字段，是因为枚举常量初始化拿不到 Context；
 * 拿不到 Composable 上下文的地方（如 `remember` 的计算 lambda）改用
 * `LocalContext.current.getString(metric.labelRes)`。
 */
@Composable
internal fun Metric.label(): String = stringResource(labelRes)

/**
 * 本机可切换的指标 tab 列表（趋势卡与全屏趋势页共用，两处口径必须一致）。
 *
 * PMIC 温度**仅在真 root 机器**出现（用户拍板 2026-09-21）：该温感区靠 root 通道取数，
 * Shizuku(shell) 机器上列出该 tab 只会得到一条空曲线，且与电池信息卡"仅 root 显示"相悖。
 * 判据取 [RootPowerReader.rootAvailable]（su 探测结论），**不是** `accessMode` ——
 * root 机器若同时开着 Shizuku，通道会优先走 Shizuku，用通道判定会把该 tab 误藏。
 *
 * 所有消费「指标全集」的 tab 行都必须走本函数，不要在别处再写一份过滤条件。
 */
@Composable
internal fun rememberAvailableMetrics(): List<Metric> {
    val rootAvailable by RootPowerReader.rootAvailable.collectAsState()
    return remember(rootAvailable) {
        if (rootAvailable) {
            Metric.entries.toList()
        } else {
            Metric.entries.filterNot { it == Metric.PMIC_TEMP }
        }
    }
}

/**
 * 「颜色」胶囊的**回落底色**（Material Purple 200 的淡紫）。
 *
 * 2026-09-26 起仅在**不支持莫奈取色**的机器（API 30-）上使用：支持莫奈的机器走
 * [colorButtonBackground] 的 Material You 动态取色。有意的原约定不变：按钮色
 * **不随曲线色变化**——它是一枚功能按钮（点开颜色选择面板），不是颜色指示器。
 */
internal val ColorButtonLilac = Color(0xFFB39DDB)

/**
 * 「颜色」胶囊底色（2026-09-26 莫奈取色口径）：
 * - **支持莫奈取色**的机器（Android 12+，[com.chen.powermeter.ui.theme.PowerMeterTheme]
 *   默认走 dynamicColorScheme）→ Material You **secondaryContainer**（随壁纸取色），
 *   文字配对角色 [colorButtonContent] 的 onSecondaryContainer（M3 标准容器/内容对，
 *   深浅主题与动态取色自动适配，无需再亮度反算）；
 * - **不支持**的机器（API 30-）→ 固定淡紫 [ColorButtonLilac]，文字色走亮度反算
 *   （口径不变）。
 * 判定用 SDK 版本而非尝试性取色：dynamicColorScheme 在 API 31+ 恒可得，主题侧
 * `dynamicColor = true` 无条件开（调用处从不覆写），两者一致。
 */
@Composable
internal fun colorButtonBackground(): Color =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MaterialTheme.colorScheme.secondaryContainer
    else ColorButtonLilac

/** 「颜色」胶囊文字色：与 [colorButtonBackground] 同口径取配对角色（见其 KDoc） */
@Composable
internal fun colorButtonContent(): Color =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MaterialTheme.colorScheme.onSecondaryContainer
    else onColorFor(ColorButtonLilac)

@Composable
internal fun TrendCard(
    samples: List<PowerSample>,
    metric: Metric,
    /** 可切换的指标 tab（见 [rememberAvailableMetrics]）：PMIC 温度只在真 root 机器上出现 */
    tabs: List<Metric>,
    metricColor: Color,
    onMetricChange: (Metric) -> Unit,
    /** 点击标题行右侧的颜色胶囊：打开该指标的颜色选择面板 */
    onColorClick: () -> Unit,
    shape: RoundedCornerShape,
    /** 点击标题行右侧的 `< >` 胶囊：把本卡片放大到全屏。入参 = 按钮窗口矩形（clip-reveal 展开起点） */
    onFullscreenClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        // ⚠️ 外层**只留垂直 padding**，水平内边距下放到各子节点 —— 目的是让指标 tab 行的
        // 滚动视口**撑满到卡片左右边框**。视口若被卡片内边距收窄，chip 会在距边框 16dp 处
        // 就被裁掉（2026-09-21 用户报告「裁切在卡片边框内部就开始了」）。
        // 口径同 SportLink：SegmentSection.kt:117-119「水平内边距移到非滚动内容内部，
        // 外层只留垂直 padding」；ChartSection.kt:117-119（padding 置于 horizontalScroll 之后）。
        Column(Modifier.padding(vertical = 16.dp)) {
            // 标题行：「趋势」二字在整张卡片宽度内真正居中；两个胶囊置于右端
            // 口径对齐 SportLink 分段数据卡 SegmentSection.kt:139-172：
            // 标题 Text.align(Center) 占满整宽居中，按钮 Box.align(CenterEnd) 贴右，二者叠加不冲突
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.title_trend),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 底色**不随曲线色**（2026-09-21 用户约定）：跟随曲线色时，用户把线调成
                    // 黄/白等浅色后按钮会跟着变浅，既让标题行右侧两枚胶囊的视觉权重随数据漂移，
                    // 也和"这是按钮、不是色块"的语义打架。曲线色改由 tab 上的色点表达（见下方
                    // FilterChip 的 leadingIcon）——色点紧贴指标名，位置唯一、不会被误读成按钮底纹。
                    // 底色本身走莫奈取色（2026-09-26）：支持莫奈的机器 = Material You
                    // secondaryContainer（随壁纸），否则回落固定淡紫，见 colorButtonBackground。
                    ChartPillButton(
                        text = stringResource(R.string.action_color),
                        background = colorButtonBackground(),
                        contentColor = colorButtonContent(),
                        onClick = onColorClick,
                    )
                    FullscreenPillButton(onClick = onFullscreenClick)
                }
            }
            Spacer(Modifier.height(10.dp))
            // 指标 tab 行必须是**横向滚动**容器（口径对齐 SportLink ChartSection.kt:115-135）：
            // 普通 Row 按"剩余宽度"测量子项，竖屏下 5 个 chip 的总固有宽度超出卡片内容宽度时，
            // 末位的「PMIC 温度」会被压到近 0 宽；而 Material3 FilterChip 的 label 没有 maxLines
            // 约束 → 逐字换行，chip 被撑成一根竖排细条（2026-09-21 用户报告）。
            // 滚动容器给子项**无界最大宽度** ⇒ chip 恒保持固有尺寸与单行高度。
            // ⚠️ 首端 16dp 内边距必须写在 horizontalScroll **之后**：此时它属于滚动内容，
            // 不参与收窄视口 —— 首个 chip 仍与标题行左对齐，而视口右边界 = 卡片边框，
            // 末位 chip 可一直滑到卡缘才被裁切（末尾有意不留 padding，同 SportLink 写法）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { m ->
                    // 色点标出该指标当前的曲线色，口径与全屏趋势页的 tab 完全一致
                    // （TrendFullscreenActivity 的 leadingIcon 同一写法：8dp 圆点 + 该指标色）
                    val chipColor = rememberMetricColor(m)
                    FilterChip(
                        selected = metric == m,
                        onClick = { onMetricChange(m) },
                        // softWrap = false + maxLines = 1：单行硬约束。即便将来再遇到宽度受限的
                        // 场景，也只会被截断，不会逐字换行把 chip 撑高
                        label = { Text(m.label(), maxLines = 1, softWrap = false) },
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(chipColor, RoundedCornerShape(50)),
                            )
                        },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 卡片内嵌：不可缩放（state = null），读数走长按拖动
            // —— 单指直接拖动会与页面竖直滚动抢手势
            TrendChart(
                // 绘图只需要时间轴：帧率监测页复用了同一个 TrendChart，故入参是「时间戳列表」
                // 而非 PowerSample 列表（见 TrendChartView 的 times 参数）
                times = remember(samples) { samples.map { it.timeMillis } },
                series = listOf(metric.toSeries(samples, metricColor, metric.label())),
                // 图表自身仍按 16dp 内缩 —— 外层 Column 已改为只留垂直 padding，故在此补齐
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * 胶囊按钮（标题行右侧通用件）。
 *
 * modifier 链顺序有意为之：background → clip → clickable。
 * clip 在 clickable **之前**，水波纹绘制在 clip 节点内部 → 被裁成圆角，
 * 不会出现默认方形 ripple 与卡片圆角规范冲突的问题。
 */
@Composable
internal fun ChartPillButton(
    text: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner = LocalCornerRadius.current
    val shape = remember(corner) { RoundedCornerShape(corner) }
    Box(
        modifier = modifier
            .background(color = background, shape = shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

/**
 * 全屏胶囊按钮（`< >`），样式对齐 SportLink `ChartSection.SmallPillButton`：
 * 深色底 #2E7D32 / 浅色底 #C8E6C9，13sp 加粗，圆角走 [LocalCornerRadius]。
 *
 * 点击回调携带**按钮自身的窗口矩形**：主界面趋势卡用它作全屏 Activity 的
 * clip-reveal 展开起点（新页从按钮矩形长大铺满，见 TrendFullscreenActivity.launch）；
 * 其他调用方（全屏页 ✕）不关心该参数。坐标用 localToWindow + size 手动拼 ——
 * 本 Compose 版本的 LayoutCoordinates 没有 boundsInWindow。
 */
@Composable
internal fun FullscreenPillButton(
    onClick: (Rect) -> Unit,
    text: String = "< >",
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    ChartPillButton(
        text = text,
        background = if (isDark) Color(0xFF2E7D32) else Color(0xFFC8E6C9),
        contentColor = if (isDark) Color.White else Color.Black,
        onClick = { onClick(bounds) },
        modifier = modifier.onGloballyPositioned { c ->
            val pos = c.localToWindow(Offset.Zero)
            bounds = Rect(pos.x, pos.y, pos.x + c.size.width, pos.y + c.size.height)
        },
    )
}

// ---------- 统计 ----------

private fun computeStats(samples: List<PowerSample>): SessionStats {
    if (samples.size < 2) return SessionStats(sampleCount = samples.size)
    var chargeMah = 0.0
    var dischargeMah = 0.0
    var chargeWh = 0.0
    var dischargeWh = 0.0
    var peakCharge = 0.0
    var peakDischarge = 0.0
    var minV = Double.MAX_VALUE
    var maxV = Double.MIN_VALUE
    var maxT = Double.NEGATIVE_INFINITY
    var sumP = 0.0
    var n = 0

    for (s in samples) {
        if (s.powerW > peakCharge) peakCharge = s.powerW
        if (s.powerW < peakDischarge) peakDischarge = s.powerW
        if (s.voltageV < minV) minV = s.voltageV
        if (s.voltageV > maxV) maxV = s.voltageV
        if (s.tempBatteryC > maxT) maxT = s.tempBatteryC
        sumP += s.powerW
        n++
    }
    for (i in 1 until samples.size) {
        val prev = samples[i - 1]
        val cur = samples[i]
        val dtH = (cur.timeMillis - prev.timeMillis) / 3_600_000.0
        if (dtH <= 0) continue
        val avgI = (prev.currentMa + cur.currentMa) / 2.0
        val avgP = (prev.powerW + cur.powerW) / 2.0
        if (avgI >= 0) {
            chargeMah += avgI * dtH
            chargeWh += avgP * dtH
        } else {
            dischargeMah += -avgI * dtH
            dischargeWh += -avgP * dtH
        }
    }
    return SessionStats(
        sampleCount = samples.size,
        durationMs = (samples.last().timeMillis - samples.first().timeMillis).coerceAtLeast(0),
        avgPowerW = if (n > 0) sumP / n else 0.0,
        peakChargeW = peakCharge,
        peakDischargeW = peakDischarge,
        minVoltageV = if (minV == Double.MAX_VALUE) 0.0 else minV,
        maxVoltageV = if (maxV == Double.MIN_VALUE) 0.0 else maxV,
        maxTempC = if (maxT == Double.NEGATIVE_INFINITY) 0.0 else maxT,
        chargedMah = chargeMah,
        dischargedMah = dischargeMah,
        chargedWh = chargeWh,
        dischargedWh = dischargeWh,
    )
}

@Composable
private fun StatsCard(stats: SessionStats, shape: RoundedCornerShape) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.title_stats),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            StatRow(stringResource(R.string.stat_sample_count), "${stats.sampleCount}")
            StatRow(stringResource(R.string.stat_duration), formatDuration(stats.durationMs))
            StatRow(stringResource(R.string.stat_avg_power), "${stats.avgPowerW.f3()} W")
            StatRow(stringResource(R.string.stat_peak_charge), "${stats.peakChargeW.f3()} W")
            StatRow(stringResource(R.string.stat_peak_discharge), "${stats.peakDischargeW.f3()} W")
            StatRow(
                stringResource(R.string.stat_voltage_range),
                "${stats.minVoltageV.f3()} ~ ${stats.maxVoltageV.f3()} V",
            )
            StatRow(stringResource(R.string.stat_max_temp), "${stats.maxTempC.f1()} ℃")
            StatRow(
                stringResource(R.string.stat_charged_total),
                "${stats.chargedMah.f3()} mAh · ${stats.chargedWh.f3()} Wh",
            )
            StatRow(
                stringResource(R.string.stat_discharged_total),
                "${stats.dischargedMah.f3()} mAh · ${stats.dischargedWh.f3()} Wh",
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = NumericFontFamily,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else if (m > 0) "${m}m ${s}s" else "${s}s"
}

// ---------- 电池信息 ----------

@Composable
private fun BatteryInfoCard(info: BatteryInfo, shape: RoundedCornerShape) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.title_battery),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            info.technology?.let { StatRow(stringResource(R.string.battery_type), it) }
            info.sohPct?.let { StatRow(stringResource(R.string.battery_soh), "$it%") }
            info.cycleCount?.let {
                StatRow(
                    stringResource(R.string.battery_cycle_count),
                    stringResource(R.string.value_times, it),
                )
            }
            info.fullMah?.let {
                StatRow(stringResource(R.string.battery_full_capacity), "${it.f0()} mAh")
            }
            info.designMah?.let {
                StatRow(stringResource(R.string.battery_design_capacity), "${it.f0()} mAh")
            }
            info.maxChargeW?.let {
                StatRow(stringResource(R.string.battery_max_charge_level), "${it.f3()} W")
            }
        }
    }
}

// ---------- 控制条 ----------

@Composable
private fun ControlRow(
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = if (running) onStop else onStart,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (running) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            ),
        ) {
            Text(
                if (running) {
                    stringResource(R.string.action_stop_sampling)
                } else {
                    stringResource(R.string.action_start_sampling)
                },
            )
        }
        FilledTonalButton(
            onClick = onExport,
            modifier = Modifier.height(56.dp),
            shape = RoundedCornerShape(50),
        ) {
            Text(stringResource(R.string.action_export_csv))
        }
    }
}

// ---------- 设置面板（bottom sheet） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    intervalMs: Long,
    wakeLock: Boolean,
    chargeMonitor: Boolean,
    seriesDualBattery: Boolean,
    shizukuAvailable: Boolean,
    shizukuGranted: Boolean,
    shizukuBound: Boolean,
    corner: Dp,
    onIntervalChange: (Long) -> Unit,
    onWakeLockChange: (Boolean) -> Unit,
    onChargeMonitorChange: (Boolean) -> Unit,
    onSeriesDualBatteryChange: (Boolean) -> Unit,
    onShizukuRequest: () -> Unit,
    onShizukuRetry: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(500L to "0.5s", 1_000L to "1s", 2_000L to "2s", 5_000L to "5s")
    // 开板态按方向区分（口径与 ColorPickerSheet 完全一致）：
    // ⚠️ material3 1.5.0-alpha27 的 rememberBottomSheetState 没有 skipPartiallyExpanded 参数
    //    （那是旧 API rememberModalBottomSheetState 的），它用 enabledValues 集合；
    //    SheetState.show() 的落点是「enabledValues 含 PartiallyExpanded → 先停半展开；否则 → Expanded」。
    // · 竖屏：去掉 PartiallyExpanded → 开板即全展开。本面板内容约 500dp（标题 + 采样间隔 +
    //   三个开关 + 权限区块 + 清空按钮），超过竖屏半屏，停在半展开会把底部「清空采样数据」
    //   压到屏幕外，用户还得再上滑一次。
    // · 横屏：维持默认（含 PartiallyExpanded），可用高度只有 360dp 上下，开板观感与改动前一致；
    //   内容已加 verticalScroll，超高时照旧可滚。
    //   enabledValues 是 rememberSaveable 的 key，旋转后会正确重建状态。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
        //  · 它被加在 **Surface 内部的 Column** 上（BottomSheet.kt:350-353），
        //    相当于替我们把内容底部抬高一个导航栏高度 → 滚动视口到不了屏幕底 →
        //    内容滚不穿手势小白条（「滑动也不沉浸」）；且该 insets 被消费后，
        //    内容末尾的 windowInsetsBottomHeight(WindowInsets.safeDrawing) 只能取到 0。
        // 去掉 Bottom 后：Surface 背景照旧铺满到屏幕底（背景沉浸），
        // 底部避让由内容末尾那个 Spacer 自己负责（此时它能读到真值）。
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
        // 自定义拖拽横条已移入 sheet 内容区（见下方 Column 首项），此处 dragHandle = null，
        // 彻底规避 M3 默认 SheetDefaults.DragHandle 长按弹出的"拖动手柄" tooltip；
        // 纯绘制横条，无 clickable/ripple/文本；面板拖拽由 sheet 容器自身手势处理，不受影响
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 可竖向滚动：横屏半展开时可用高度只有 360dp 上下，内容必然超出；
                // 无滚动则底部「清空采样数据」按钮点不到（与 ColorPickerSheet 同口径）
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            // 自定义拖拽横条（dragHandle 已置 null，规避 M3 默认手柄的长按 tooltip"拖动手柄"）
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
                            shape = RoundedCornerShape(50),
                        ),
                )
            }
            Text(
                stringResource(R.string.title_settings),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.label_sampling_interval),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, label) ->
                    FilterChip(
                        selected = intervalMs == value,
                        onClick = { onIntervalChange(value) },
                        label = { Text(label, fontFamily = NumericFontFamily) },
                        shape = RoundedCornerShape(50),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.option_keep_sampling_on_lock),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.option_keep_sampling_on_lock_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wakeLock, onCheckedChange = onWakeLockChange)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.option_charge_monitor),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.option_charge_monitor_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = chargeMonitor, onCheckedChange = onChargeMonitorChange)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.option_series_dual_battery),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.option_series_dual_battery_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = seriesDualBattery,
                    onCheckedChange = onSeriesDualBatteryChange,
                )
            }
            Spacer(Modifier.height(20.dp))

            // ---- 数据读取权限（Shizuku） ----
            // 非 root 机器必须装 Shizuku 并授权，才能以 shell 身份读 /sys 电量节点；
            // 已 root 设备走 su 通道，本节显示「未检测到 Shizuku」可忽略。
            val shizukuUi: Triple<String, String?, (() -> Unit)?> = when {
                shizukuBound -> Triple(
                    stringResource(R.string.shizuku_ready),
                    null,
                    null,
                )
                shizukuAvailable && shizukuGranted -> Triple(
                    stringResource(R.string.shizuku_connecting),
                    stringResource(R.string.action_retry),
                    onShizukuRetry,
                )
                shizukuAvailable -> Triple(
                    stringResource(R.string.shizuku_not_granted),
                    stringResource(R.string.action_authorize),
                    onShizukuRequest,
                )
                else -> Triple(
                    stringResource(R.string.shizuku_not_found),
                    null,
                    null,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.title_data_access_permission),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        shizukuUi.first,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                shizukuUi.third?.let { action ->
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(
                        onClick = action,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(shizukuUi.second ?: "")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            FilledTonalButton(
                onClick = { onClear(); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(corner),
            ) {
                Text(stringResource(R.string.action_clear_samples))
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}
