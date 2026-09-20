package com.chen.powermeter.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)

private fun Double?.f3OrDash(): String = this?.f3() ?: "—"

/** 温度类（电池/接口/最高温度）按用户约定取 1 位小数（2026-09-21）；充电 IC 等其余温度仍 3 位 */
private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

private fun Double?.f1OrDash(): String = this?.f1() ?: "—"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PowerMeterScreen(
    running: Boolean,
    samples: List<PowerSample>,
    batteryInfo: BatteryInfo?,
    error: String?,
    intervalMs: Long,
    wakeLock: Boolean,
    /** 充电功率监测：开始采样 5s 后自动熄屏，低功率持续 5min 时自动保存 CSV */
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
) {
    val corner = LocalCornerRadius.current
    val cardShape = remember(corner) { RoundedCornerShape(corner) }
    var settingsOpen by remember { mutableStateOf(false) }
    var metric by remember { mutableStateOf(Metric.POWER) }
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
    // 趋势卡 → 全屏：起独立 Activity（真沉浸隐藏系统栏 + 避让摄像头 + 缩放淡入过渡）。
    // 采样数据无需跨页传递：全屏页直接读 SampleStore（实时环形缓冲）/ ImportedSeries 两个单例。
    val openTrendFullscreen = { TrendFullscreenActivity.launch(context, metric) }

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
                            metricColor = metricColor,
                            onMetricChange = { metric = it },
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
                        metricColor = metricColor,
                        onMetricChange = { metric = it },
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
                    Column {
                        Text(
                            "功率监测",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                        if (running) "采样中 · 每 ${intervalMs}ms" else "已停止",
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
                        Text("设置", style = MaterialTheme.typography.labelLarge)
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
            title = "${target.label}曲线颜色",
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
                    "查看导入的 CSV",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$fileName · $count 条",
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
                Text("退出查看", style = MaterialTheme.typography.labelLarge)
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
                        sample == null -> "等待采样"
                        charging -> "充电中"
                        else -> "放电中"
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
                    append("SOC ${sample?.socPct ?: 0}%")
                    sample?.chargeType?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    sample?.remainingMah?.let { append(" · 剩余 ${it.f3()} mAh") }
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
    // Shizuku binder 兜底（sysfs 被 SELinux 拦截的机器）拿不到接口/充电 IC 温度：
    // 开路电压顶替接口温度的位置，接口温度与充电 IC 温度两张卡片隐藏（用户拍板 2026-09-21）
    val binderFallback by RootPowerReader.binderFallback.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("电压", sample?.voltageV.f3OrDash(), "V", shape, Modifier.weight(1f))
            MetricCard("电流", sample?.currentMa.f3OrDash(), "mA", shape, Modifier.weight(1f))
        }
        if (binderFallback) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("电池温度", sample?.tempBatteryC.f1OrDash(), "℃", shape, Modifier.weight(1f))
                MetricCard("开路电压", sample?.voltageOcvV.f3OrDash(), "V", shape, Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("电池温度", sample?.tempBatteryC.f1OrDash(), "℃", shape, Modifier.weight(1f))
                MetricCard("接口温度", sample?.tempUsbC.f1OrDash(), "℃", shape, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("开路电压", sample?.voltageOcvV.f3OrDash(), "V", shape, Modifier.weight(1f))
                MetricCard("充电 IC 温度", sample?.tempChargerC.f3OrDash(), "℃", shape, Modifier.weight(1f))
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
internal enum class Metric(val label: String) {
    POWER("功率"), VOLTAGE("电压"), CURRENT("电流"), TEMP("温度");

    fun value(s: PowerSample): Double = when (this) {
        POWER -> s.powerW
        VOLTAGE -> s.voltageV
        CURRENT -> s.currentMa
        TEMP -> s.tempBatteryC
    }
}

@Composable
internal fun TrendCard(
    samples: List<PowerSample>,
    metric: Metric,
    metricColor: Color,
    onMetricChange: (Metric) -> Unit,
    /** 点击标题行右侧的颜色胶囊：打开该指标的颜色选择面板 */
    onColorClick: () -> Unit,
    shape: RoundedCornerShape,
    /** 点击标题行右侧的 `< >` 胶囊：把本卡片放大到全屏（隐藏状态栏与小白条） */
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 标题行：「趋势」二字在整张卡片宽度内真正居中；两个胶囊置于右端
            // 口径对齐 SportLink 分段数据卡 SegmentSection.kt:139-172：
            // 标题 Text.align(Center) 占满整宽居中，按钮 Box.align(CenterEnd) 贴右，二者叠加不冲突
            Box(Modifier.fillMaxWidth()) {
                Text(
                    "趋势",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 底色 = 当前曲线色，一眼看出"这根线是什么颜色"；
                    // 文字颜色按亮度反算（色值由用户自由指定，不能像 SportLink 那样写死白色）
                    ChartPillButton(
                        text = "颜色",
                        background = metricColor,
                        contentColor = onColorFor(metricColor),
                        onClick = onColorClick,
                    )
                    FullscreenPillButton(onClick = onFullscreenClick)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric.entries.forEach { m ->
                    FilterChip(
                        selected = metric == m,
                        onClick = { onMetricChange(m) },
                        label = { Text(m.label) },
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
                samples = samples,
                series = listOf(metric.toSeries(samples, metricColor)),
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
 */
@Composable
internal fun FullscreenPillButton(
    onClick: () -> Unit,
    text: String = "< >",
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    ChartPillButton(
        text = text,
        background = if (isDark) Color(0xFF2E7D32) else Color(0xFFC8E6C9),
        contentColor = if (isDark) Color.White else Color.Black,
        onClick = onClick,
        modifier = modifier,
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
                "统计",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            StatRow("样本数", "${stats.sampleCount}")
            StatRow("时长", formatDuration(stats.durationMs))
            StatRow("平均功率", "${stats.avgPowerW.f3()} W")
            StatRow("峰值充电", "${stats.peakChargeW.f3()} W")
            StatRow("峰值放电", "${stats.peakDischargeW.f3()} W")
            StatRow("电压范围", "${stats.minVoltageV.f3()} ~ ${stats.maxVoltageV.f3()} V")
            StatRow("最高温度", "${stats.maxTempC.f1()} ℃")
            StatRow("累计充入", "${stats.chargedMah.f3()} mAh · ${stats.chargedWh.f3()} Wh")
            StatRow("累计放出", "${stats.dischargedMah.f3()} mAh · ${stats.dischargedWh.f3()} Wh")
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
                "电池",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            info.technology?.let { StatRow("类型", it) }
            info.sohPct?.let { StatRow("健康度 SOH", "$it%") }
            info.cycleCount?.let { StatRow("循环次数", "$it 次") }
            info.fullMah?.let { StatRow("满充容量", "${it.f3()} mAh") }
            info.designMah?.let { StatRow("设计容量", "${it.f3()} mAh") }
            info.maxChargeW?.let { StatRow("最大充电档位", "${it.f3()} W") }
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
            Text(if (running) "停止采样" else "开始采样")
        }
        FilledTonalButton(
            onClick = onExport,
            modifier = Modifier.height(56.dp),
            shape = RoundedCornerShape(50),
        ) {
            Text("导出 CSV")
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
                "采样设置",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "采样间隔",
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
                    Text("锁屏保持采样", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "用 CPU 唤醒锁换取息屏后采样连续；关闭时息屏间隔放宽到 5s，更省电",
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
                    Text("充电功率监测", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "点击开始采样 5s 后熄灭屏幕；充电功率低于 1W 且持续超过 5min 时自动保存 CSV 文件（不停止采样）",
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
                    Text("串联双电池", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "内核上报单节电压，串联机型整组电压 ×2，功率随之 ×2（小米机器不需要开启）",
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
                shizukuBound -> Triple("已就绪 · 以 shell 身份读取底层节点", null, null)
                shizukuAvailable && shizukuGranted -> Triple("已授权 · 正在连接 Shizuku 服务…", "重试", onShizukuRetry)
                shizukuAvailable -> Triple("Shizuku 正在运行 · 尚未授权本应用", "授权", onShizukuRequest)
                else -> Triple("未检测到 Shizuku（已 root 设备可忽略此项）", null, null)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("数据读取权限", style = MaterialTheme.typography.bodyLarge)
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
                Text("清空采样数据")
            }
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}
