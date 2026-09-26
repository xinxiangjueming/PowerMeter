package com.chen.powermeter.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.chen.powermeter.R
import com.chen.powermeter.data.FrameSample
import com.chen.powermeter.data.db.FrameCpuSampleEntity
import com.chen.powermeter.data.db.FrameSampleEntity
import com.chen.powermeter.data.db.FrameSession
import com.chen.powermeter.data.db.FrameDatabase
import com.chen.powermeter.ui.common.BlurTopBar
import com.chen.powermeter.ui.theme.PowerMeterTheme
import com.chen.powermeter.util.KiteXlsxExporter
import com.chen.powermeter.util.NavigationBarHelper
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Share
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 数字 / 单位统一等宽字体（口径同 PowerMeterScreen.NumericFontFamily） */
private val DetailNumericFont = FontFamily.Monospace

private const val TAG = "FrameDetailActivity"

private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

private fun Double.f2(): String = String.format(Locale.US, "%.2f", this)

private fun Double.f0(): String = String.format(Locale.US, "%.0f", this)

private fun Double?.f0OrDash(): String = this?.f0() ?: "—"

/**
 * 单条帧率记录详情页。
 *
 * 独立的 Activity（而非主页内的一个子页面）：返回手势 / 返回键天然可用，且过渡走主题的
 * 窗口动画，与「趋势全屏页」的路径一致。数据源按 sessionId 从 Room 直接读，
 * 不经过进程内单例 —— 详情页是一次性查看，没必要为了它常驻一份列表状态。
 */
class FrameDetailActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** 非法 / 缺失的会话 ID */
        private const val NO_ID = -1L

        fun launch(context: Context, sessionId: Long) {
            context.startActivity(
                Intent(context, FrameDetailActivity::class.java)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
            )
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())

        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, NO_ID) ?: NO_ID

        setContent {
            PowerMeterTheme {
                var session by remember { mutableStateOf<FrameSession?>(null) }
                var samples by remember { mutableStateOf<List<FrameSample>>(emptyList()) }
                // CPU 快样（250ms 级）；空 = 快样表建立前录的旧会话 → 详情页回退 1s 样本
                var cpuPoints by remember { mutableStateOf<List<CpuPoint>>(emptyList()) }
                // 只在 sessionId 变化时读一次；旋转 / 深浅色切换不重建本 Activity 之外的
                // 情形（configChanges 与主页同口径）本就不会走到这里
                LaunchedEffect(sessionId) {
                    if (sessionId == NO_ID) return@LaunchedEffect
                    val loaded = withContext(Dispatchers.IO) {
                        val dao = FrameDatabase
                            .getInstance(this@FrameDetailActivity)
                            .frameDao()
                        val session = dao.session(sessionId)
                        val samples = dao.samples(sessionId).map(FrameSampleEntity::toFrameSample)
                        // 新会话直接吃 250ms 快样；旧会话（无快样行）回退 1s 样本的 CPU 字段
                        val points = dao.cpuSamples(sessionId).let { fast ->
                            if (fast.isNotEmpty()) fast.map { it.toCpuPoint() }
                            else samples.map { it.toCpuPointFallback() }
                        }
                        Triple(session, samples, points)
                    }
                    session = loaded.first
                    samples = loaded.second
                    cpuPoints = loaded.third
                }

                // DialogBackdropHost：详情页弹窗（稳帧指数 / Jank ⓘ）走「宿主 + slot」玻璃路径。
                // ⚠️ 缺宿主时 GlassDialog 降级为**就地渲染**——说明弹窗长在统计卡里，
                // 出现/消失会瞬间顶开卡片高度（用户实测反馈）。口径同 MainActivity（2026-09-25 补）。
                DialogBackdropHost {
                    FrameDetailScreen(
                        session = session,
                        samples = samples,
                        cpuPoints = cpuPoints,
                        onBack = { finish() },
                        onShare = { s, sm -> shareSession(s, sm) },
                    )
                }
            }
        }
    }

    /**
     * 把本场记录转成 Kite 兼容 xlsx 并调起系统分享（ACTION_SEND，见 [KiteXlsxExporter]）。
     *
     * 文件写入 `cacheDir/share/`，经 FileProvider（`${applicationId}.fileprovider`，
     * 路径表 res/xml/file_paths.xml）临时授予读权限 —— 导出文件不落公共目录，
     * 不需要任何存储权限。每次分享前清空 share 目录，分享多了也不攒垃圾。
     */
    private fun shareSession(session: FrameSession, samples: List<FrameSample>) {
        if (samples.isEmpty()) {
            Toast.makeText(this, R.string.frame_share_empty, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "share")
                    if (dir.exists()) dir.deleteRecursively()
                    dir.mkdirs()
                    val file = File(dir, KiteXlsxExporter.fileName(session))
                    file.writeBytes(KiteXlsxExporter.build(session, samples))
                    FileProvider.getUriForFile(
                        this@FrameDetailActivity,
                        "${packageName}.fileprovider",
                        file,
                    )
                }.onFailure { e ->
                    Log.e(TAG, "导出帧率 xlsx 失败 id=${session.id}", e)
                }.getOrNull()
            }
            if (uri == null) {
                Toast.makeText(this@FrameDetailActivity, R.string.frame_share_failed, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.frame_share)))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

@Composable
private fun FrameDetailScreen(
    session: FrameSession?,
    samples: List<FrameSample>,
    /** CPU 两卡的绘图点（250ms 快样，旧会话回退 1s 样本 —— 见 [CpuPoint]） */
    cpuPoints: List<CpuPoint>,
    onBack: () -> Unit,
    /** 顶栏分享：把本场记录转 Kite 兼容 xlsx 并调起系统分享（Activity 侧实现） */
    onShare: (FrameSession, List<FrameSample>) -> Unit,
) {
    // 本页卡片圆角统一 10dp（2026-09-25 用户指定；不用 LocalCornerRadius 的大圆角）
    val cardShape = RoundedCornerShape(10.dp)
    val context = LocalContext.current
    // FPS 轴上限 = 设备铺满刷新率（supportedModes 最大值，如 120/144；取不到退回录制时
    // 的激活刷新率）。remember(session)：supportedModes 是冷数据，没必要每次重组都查
    val fpsAxisMax = remember(session) {
        val panel = context.display?.supportedModes
            ?.maxOfOrNull { it.refreshRate }?.roundToInt()?.toDouble() ?: 0.0
        maxOf(panel, session?.refreshRateHz?.toDouble() ?: 0.0, 1.0)
    }

    val sideInsets = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
    val topBarInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Top)
        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

    val hazeState = remember { HazeState() }
    val topBarHazeStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        blurRadius = 20.dp,
        tint = null,
    )
    val useKyantTopBar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val topBarBackdrop = rememberLayerBackdrop()
    val topBarHeight = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 64.dp

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .then(if (useKyantTopBar) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(sideInsets)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topBarHeight)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (session == null) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        stringResource(R.string.frame_not_found),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 2026-09-25 重构：按 Kite 报告版式重排（不再照搬电池查看页的卡片区）——
                    // 顶部统计网格 → 帧率与温度（双轴叠加）→ Frame Time → Jank → Power
                    // → Temperature → CPU Usage → CPU Frequency（后两卡 2026-09-25 追加置底）
                    FrameSummaryCard(session, samples, cardShape)
                    if (samples.size >= 2) {
                        FrameFpsTempCard(samples, fpsAxisMax, cardShape)
                        FrameTimeCard(samples, cardShape)
                        FrameJankCard(samples, cardShape)
                        FramePowerCard(samples, cardShape)
                        FrameTempCard(samples, cardShape)
                        // CPU 两卡按数据自适应显隐：占用卡在整场连 Total 都没有时隐藏；
                        // 频率卡有任一核的有效读数即显示。数据源 = 250ms 快样（旧会话回退 1s）
                        if (cpuPoints.any { it.totalPct != null || it.corePct.any { c -> c != null } }) {
                            FrameCpuUsageCard(cpuPoints, cardShape)
                        }
                        if (cpuPoints.any { p -> p.mhz.any { m -> (m ?: 0.0) > 0.0 } }) {
                            FrameCpuFreqCard(cpuPoints, cardShape)
                        }
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }

        BlurTopBar(
            kyantBackdrop = if (useKyantTopBar) topBarBackdrop else null,
            hazeState = if (useKyantTopBar) null else hazeState,
            hazeStyle = if (useKyantTopBar) null else topBarHazeStyle,
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session?.appLabel ?: stringResource(R.string.mode_frame),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        if (session != null) {
                            Text(
                                stringResource(
                                    R.string.frame_captured_at,
                                    formatFrameStamp(session.startTime),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = DetailNumericFont,
                            )
                        }
                    }
                },
                navigationIcon = {
                    // 与趋势全屏页同一枚胶囊（✕）：本页由主题提供水平滑入 / 滑出过渡，
                    // 返回手势与这里都只做 finish()（按钮会 register 转场锚点，无消费者自动过期）
                    FullscreenPillButton(text = "✕", onClick = onBack)
                },
                actions = {
                    // Kite 兼容 xlsx 分享：无样本（理论不可达）时 Activity 侧 toast 兜底
                    IconButton(
                        onClick = { session?.let { onShare(it, samples) } },
                        enabled = session != null,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Share,
                            contentDescription = stringResource(R.string.frame_share),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = topBarInsets,
            )
        }
    }
}

// ── 顶部统计卡（Kite 报告同款 4 列网格；2026-09-25 重构，不再照搬电池查看页的卡片区）──

/** 稳帧指数 ⓘ 弹窗的口径文案（卡顿判定同理见 [FrameJankCard]） */
@Composable
private fun FrameInfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // 纯说明弹窗：看完点遮罩/返回即关（口径同 CurveSelectSheet 列表弹窗）
        confirmButton = {},
    )
}

/** 总体标准差（除以 n）；样本 <2 个 = 无法衡量波动，返回 null */
private fun stdev(values: List<Double>): Double? {
    if (values.size < 2) return null
    val avg = values.average()
    return sqrt(values.sumOf { (it - avg) * (it - avg) } / values.size)
}

/**
 * 卡顿判定 —— PerfDog 式口径在本应用 1s 采样粒度的移植（2026-09-25 按用户提供定义）：
 * - **卡顿**：帧时间 > 前 3 个有效秒均值 × 2，且 > 83ms（两倍电影帧耗时，24fps → 41.7ms）；
 * - **严重卡顿**：帧时间 > 前 3 秒均值 × 3，且 > 125ms；
 * - **小卡顿**：帧时间 > 前 3 秒均值 × 2，但未过 83ms 绝对门槛（纯相对尖峰）。
 * 每秒只计最高一档；前 3 秒基线不足 / 该秒无帧（frameSpace=0）不判定、不进基线。
 *
 * ⚠️ 粒度局限：样本是 1s 平均帧时间，单帧级尖刺会被整秒摊薄 —— 只有持续到秒级的
 * 卡顿才判得出来（忠实于数据，不假装有逐帧精度）。顶部卡顿率与 Jank 卡共用本判定。
 *
 * @return 每样本一档：0 无 / 1 小卡顿 / 2 卡顿 / 3 严重卡顿
 */
private fun jankTiers(samples: List<FrameSample>): List<Int> {
    val tiers = IntArray(samples.size)
    val baseline = ArrayDeque<Double>()
    for (i in samples.indices) {
        val ft = samples[i].frameSpaceMs
        if (ft <= 0.0) continue // 该秒无帧：不判定，也不进基线
        if (baseline.size >= 3) {
            val prevAvg = baseline.average()
            tiers[i] = when {
                ft > 3 * prevAvg && ft > 125.0 -> 3
                ft > 2 * prevAvg && ft > 83.0 -> 2
                ft > 2 * prevAvg -> 1
                else -> 0
            }
        }
        baseline.addLast(ft)
        if (baseline.size > 3) baseline.removeFirst()
    }
    return tiers.toList()
}

/**
 * 顶部统计网格 —— 版式对齐 Kite 报告页首卡（用户截图，2026-09-25）：
 * 行1 MAX / MIN / AVG / VARIANCE（FPS 四项）
 * 行2 1% Low / 5% Low / MAX 温度 / AVG 功率
 * 行3 帧能耗 / 卡顿率 / 稳帧指数ⓘ（3 格，第 4 列留空）
 *
 * 口径：
 * - VARIANCE = FPS 总体标准差（Kite 页面标签作 VARIANCE，量纲 FPS）；
 * - MAX 温度 = 电池温度峰值（无电池温度样本 → 破折号）；
 * - AVG 功率带符号（"正=充电"口径，放电为负，与 Kite 截图 -8.53 一致）；
 * - 帧能耗 = |平均功率| ÷ 平均帧率（mW/帧，Kite 同名指标的换算口径）；
 * - 卡顿率 = 卡顿总时长 ÷ 总时长（卡顿判定见 [jankTiers]：PerfDog 式口径，
 *   卡顿与严重卡顿秒的时长计入，小卡顿不计）；
 * - 稳帧指数 = 帧时间标准差（ms），PerfDog 稳帧指数（Smooth）的简化口径，点 ⓘ 看定义。
 */
@Composable
private fun FrameSummaryCard(
    session: FrameSession,
    samples: List<FrameSample>,
    shape: RoundedCornerShape,
) {
    val fpsVals = samples.map { it.fps }.filter { it > 0.0 }
    val fpsSd = stdev(fpsVals)
    val avgPowerMw = samples.mapNotNull { it.powerMw }.takeIf { it.isNotEmpty() }?.average()
    // 卡顿率：卡顿（含严重卡顿）秒的时长合计 ÷ 会话总时长；首样本无前历不计时长
    val tiers = jankTiers(samples)
    val spanMs = (samples.last().timeMillis - samples.first().timeMillis).coerceAtLeast(1L)
    val stutterMs = tiers.withIndex().sumOf { (i, tier) ->
        if (tier >= 2 && i > 0) {
            (samples[i].timeMillis - samples[i - 1].timeMillis).coerceAtLeast(0L)
        } else {
            0L
        }
    }
    val jankRate = stutterMs * 100.0 / spanMs
    val energyPerFrameMw =
        if (avgPowerMw != null && session.avgFps > 0.0) abs(avgPowerMw) / session.avgFps else null
    val maxBatTemp = samples.mapNotNull { it.tempBatteryC }.maxOrNull()
    val ftSd = stdev(samples.map { it.frameSpaceMs }.filter { it > 0.0 })

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
            var showSteadyInfo by remember { mutableStateOf(false) }

            SummaryRow {
                SummaryCell("MAX", session.maxFps.f0(), "FPS", Modifier.weight(1f))
                SummaryCell("MIN", session.minFps.f0(), "FPS", Modifier.weight(1f))
                SummaryCell("AVG", session.avgFps.f0(), "FPS", Modifier.weight(1f))
                SummaryCell("VARIANCE", fpsSd?.f0() ?: "—", "FPS", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            SummaryRow {
                SummaryCell("1% Low", session.lowFps1.f0OrDash(), "FPS", Modifier.weight(1f))
                SummaryCell("5% Low", session.lowFps5.f0OrDash(), "FPS", Modifier.weight(1f))
                SummaryCell("MAX", maxBatTemp?.f1() ?: "—", "Temperature", Modifier.weight(1f))
                SummaryCell("AVG", avgPowerMw?.div(1_000.0)?.f2() ?: "—", "Power(W)", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            SummaryRow {
                SummaryCell(
                    stringResource(R.string.frame_energy_per_frame),
                    energyPerFrameMw?.f2() ?: "—",
                    "mW",
                    Modifier.weight(1f),
                )
                SummaryCell(
                    stringResource(R.string.frame_jank_rate),
                    jankRate?.f2() ?: "—",
                    "%",
                    Modifier.weight(1f),
                )
                SummaryCell(
                    stringResource(R.string.frame_steady_index),
                    ftSd?.f1() ?: "—",
                    "ms",
                    Modifier.weight(1f),
                ) {
                    showSteadyInfo = !showSteadyInfo
                }
                Spacer(Modifier.weight(1f))
            }
            // ⓘ 说明弹窗：⚠️ 不能包 AnimatedVisibility —— GlassDialog 是同窗口浮层、自带
            // dialogEnterAnim 入场动效；外层再对**全屏毛玻璃遮罩**做淡入缩放，等于每帧
            // 重渲染一次模糊层，过渡又卡又怪（2026-09-25 用户反馈）。显隐直接交 GlassDialog，
            // 再点一次图标即收起。
            if (showSteadyInfo) {
                FrameInfoDialog(
                    title = stringResource(R.string.frame_steady_index),
                    body = stringResource(R.string.frame_steady_index_info),
                    onDismiss = { showSteadyInfo = false },
                )
            }
        }
    }
}

/** 统计网格的一行（等宽 4 列由调用方用 weight 控制） */
@Composable
private fun SummaryRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 单个统计格：上标签（含可选 ⓘ）+ 大数值 + 下单位。
 * ⓘ 的点击热区就是图标本身（13dp）—— 嵌在网格里放不下 IconButton 的 48dp 最小热区。
 */
@Composable
private fun SummaryCell(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    onInfo: (() -> Unit)? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onInfo != null) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = stringResource(R.string.frame_steady_index_info),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(13.dp)
                        // 去水波纹：小图标上的 ripple 会溢出成方形闪烁，纯变色即可
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onInfo,
                        ),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
            fontWeight = FontWeight.Bold,
            fontFamily = DetailNumericFont,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 帧率与温度 / Frame Time / Jank 三卡（Kite 报告版式，2026-09-25 重构）──

/** 严重卡顿柱色：与删除确认键的 DeleteRed 同源（0xFFD32F2F，两主题白字/深底对比均达标） */
private val BigJankRed = Color(0xFFD32F2F)

/** 温度曲线橙色（对齐 Kite 双轴图的暖橙；与主题色解耦，和灰的 FPS 线对比清晰） */
private val TempOrange = Color(0xFFF5A623)

/** 折线规格：values 里 null/NaN = 缺测秒（断线不画）；[FrameLine.onRight] = 走右轴（默认左轴） */
private class FrameLine(val values: List<Double?>, val color: Color, val onRight: Boolean = false)

/** Power 卡折线蓝（Kite 同款亮蓝；固定色避免动态主题下与 Capacity 浅蓝难区分） */
private val PowerBlue = Color(0xFF2F7DE1)

private val CapacityBlue = Color(0xFF8FD0F4)

private val CpuTempBlue = Color(0xFF63B8EC)

private val GpuTempPurple = Color(0xFF9C7BE8)

/** CPU 占用率线（FPS 卡右轴候选；Kite 图例里的粉色） */
private val CpuLoadPink = Color(0xFFF48FB1)

/** GPU 占用率线（FPS 卡右轴候选；Kite 图例里的蓝色） */
private val GpuLoadBlue = Color(0xFF64B5F6)

/**
 * CPU Usage / CPU Frequency 两卡的分簇配色（Kite 同款：紫 / 亮青 / 深青 / 橙；
 * Total 浅蓝直接复用 CapacityBlue）。与温度卡的橙 / 紫是不同常量：两卡调色互不牵连。
 */
private val CpuClusterPurple = Color(0xFF9C7BE8)
private val CpuClusterTeal = Color(0xFF2EC4C4)
private val CpuClusterTealDark = Color(0xFF17948E)
private val CpuClusterOrange = Color(0xFFF08C00)

/** 分簇配色按 [CpuClusters] 的顺序一一对应 */
private val CpuClusterColors =
    listOf(CpuClusterPurple, CpuClusterTeal, CpuClusterTealDark, CpuClusterOrange)

/**
 * 折线图（帧率与温度 / Power / Temperature 三卡共用）。
 *
 * - [leftRange] / [rightRange] = 左右轴值域，null = 不画该轴刻度；左轴 4 等分虚线网格
 *   + 刻度（右对齐贴轴），右轴顶/中/底三档刻度（nativeCanvas 直绘，走 Compose 密度）；
 * - [lines] 每条线各走自己的轴，null / NaN 断线；x 轴四分点时间刻度由调用方接 [ClockTicks]。
 */
@Composable
private fun FrameLineChart(
    lines: List<FrameLine>,
    leftRange: ClosedFloatingPointRange<Double>?,
    rightRange: ClosedFloatingPointRange<Double>?,
    modifier: Modifier = Modifier,
    /** 右轴刻度档数（含顶档，不标 0）：默认 3 档（顶/中/底）；FPS 卡传 5（100/80/60/40/20） */
    rightTickCount: Int = 3,
    /**
     * 绘图区左右内缩 = 轴标签区宽度。**必须由调用方按两侧标签实际宽度算好传入**
     * （[rememberChartInsets]，ClockTicks 的 x 刻度要用同一对值对位）——2026-09-25 前是
     * 固定 30/42dp，Power 卡的 1~2 字符轴标签（"3/2/1"、"50/0"）也占同样的宽度，
     * 标签离卡片边缘一大截、绘图区白白窄一圈（用户实测截图反馈）。
     */
    startInset: Dp = ChartPlotInsetStart,
    endInset: Dp = ChartPlotInsetEnd,
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    Box(
        modifier
            .fillMaxWidth()
            .height(190.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val count = lines.maxOfOrNull { it.values.size } ?: 0
            if (count < 2) return@Canvas
            val plotLeft = startInset.toPx()
            val plotRight = size.width - endInset.toPx()
            val plotW = plotRight - plotLeft
            fun x(i: Int) = plotLeft + plotW * i / (count - 1)
            fun yOf(v: Double, r: ClosedFloatingPointRange<Double>) =
                (size.height * (1 - ((v - r.start) / (r.endInclusive - r.start)))).toFloat()
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f))
            for (i in 1..4) {
                val y = size.height * (1 - i / 4f)
                drawLine(
                    gridColor,
                    Offset(plotLeft, y),
                    Offset(plotRight, y),
                    strokeWidth = 1f,
                    pathEffect = dash,
                )
            }
            // 内部三条竖向分隔线（x 四分点，实线）+ 绘图区外框（2026-09-25 用户口径）
            for (q in 1..3) {
                val x = plotLeft + plotW * q / 4
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            val borderColor = labelColor.copy(alpha = 0.4f)
            drawRect(
                borderColor,
                topLeft = Offset(plotLeft, 0f),
                size = Size(plotW, size.height),
                style = Stroke(1f),
            )
            labelPaint.textSize = 10.sp.toPx()
            labelPaint.color = labelColor.toArgb()
            leftRange?.let { r ->
                val step = (r.endInclusive - r.start) / 4
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                for (i in 1..4) {
                    val y = size.height * (1 - i / 4f)
                    drawContext.canvas.nativeCanvas.drawText(
                        tickText(r.start + (r.endInclusive - r.start) * i / 4, step),
                        plotLeft - 6.dp.toPx(),
                        y + labelPaint.textSize / 3,
                        labelPaint,
                    )
                }
            }
            rightRange?.let { r ->
                val step = (r.endInclusive - r.start) / (rightTickCount - 1)
                labelPaint.textAlign = android.graphics.Paint.Align.LEFT
                for (i in 0 until rightTickCount) {
                    val frac = i / (rightTickCount - 1).toFloat()
                    val v = r.endInclusive - (r.endInclusive - r.start) * frac
                    val y = size.height * frac
                    // 顶档文字画到线下方、底档画到线上方，避免贴边裁切
                    val baseline = when (i) {
                        0 -> y + labelPaint.textSize
                        rightTickCount - 1 -> y - 2.dp.toPx()
                        else -> y + labelPaint.textSize / 3
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        tickText(v, step),
                        plotRight + 6.dp.toPx(),
                        baseline,
                        labelPaint,
                    )
                }
            }
            // 折线裁剪在绘图区内：轴范围固定后（FPS 0-刷新率 / 温度 0-50），超范围样本
            // 不得越出外框压到轴题上
            clipRect(left = plotLeft, top = 0f, right = plotRight, bottom = size.height) {
                lines.forEach { line ->
                    val r = if (line.onRight) rightRange ?: return@forEach else leftRange ?: return@forEach
                    val span = r.endInclusive - r.start
                    val path = Path()
                    var drawing = false
                    line.values.forEachIndexed { i, v ->
                        if (v == null || v.isNaN()) {
                            drawing = false
                            return@forEachIndexed
                        }
                        val px = x(i)
                        val py = (size.height * (1 - ((v - r.start) / span))).toFloat()
                        if (drawing) path.lineTo(px, py) else path.moveTo(px, py)
                        drawing = true
                    }
                    drawPath(path, line.color, style = Stroke(5f))
                }
            }
        }
    }
}

/** FPS 卡右轴可切换序列（Kite 同款：电量 / 温度 / CPU load / GPU load） */
private class RightSeriesOption(
    val label: String,
    val range: ClosedFloatingPointRange<Double>,
    val color: Color,
    val values: List<Double?>,
)

/**
 * 帧率与温度卡：**双轴叠加 + 右轴可切换**（Kite 同款版式，2026-09-25）——
 * FPS 折线走左轴（灰，0 → 设备铺满刷新率），右轴在电量 / 温度 / CPU(%) / GPU(%) 间切换：
 * 点右上角 Refresh 钮循环轮换，或直接点底部图例选中（选中高亮、其余压暗，Kite 同款）。
 * 右轴值域随选项走（电量 / CPU / GPU 0-100，温度 0-50，右轴 5 档刻度）；缺测秒断线。
 * ⚠️ 整场无数据的候选自动不进切换列表（GPU(%) 在本机 HyperOS 被 SELinux 拦 / 旧会话缺列）。
 */
@Composable
private fun FrameFpsTempCard(
    samples: List<FrameSample>,
    fpsAxisMax: Double,
    shape: RoundedCornerShape,
) {
    // 右轴候选按**数据自适应**：整场一条数据都没有的选项不进切换列表 ——
    // GPU(%) 在 24031PN0DC / HyperOS 上 shell 对 /sys/class/kgsl 全目录拒绝（2026-09-25
    // 实机验证），Shizuku 模式恒空 → 自动隐藏；root 机器或可读 kgsl 的 ROM 照常出现。
    // 旧会话（v7 前）的 Battery/CPU 同理。TEMP 恒在（v1 起就有电池温度），保底不为空。
    val rightOptions = remember(samples) {
        listOfNotNull(
            RightSeriesOption("Battery(%)", 0.0..100.0, CapacityBlue, samples.map { it.capacityPct })
                .takeIf { o -> o.values.any { it != null } },
            RightSeriesOption("TEMP(°C)", 0.0..50.0, TempOrange, samples.map { it.tempBatteryC }),
            RightSeriesOption("CPU(%)", 0.0..100.0, CpuLoadPink, samples.map { it.cpuUsagePct })
                .takeIf { o -> o.values.any { it != null } },
            RightSeriesOption("GPU(%)", 0.0..100.0, GpuLoadBlue, samples.map { it.gpuLoadPct })
                .takeIf { o -> o.values.any { it != null } },
        )
    }
    var rightIdx by remember { mutableStateOf(0) }
    val selected = rightOptions[rightIdx.coerceAtMost(rightOptions.lastIndex)]
    val fpsVals = samples.map { it.fps }
    val fpsLineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val durationMs = samples.last().timeMillis - samples.first().timeMillis

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("FPS", style = MaterialTheme.typography.titleMedium)
                // 右轴切换（点文字或刷新图标循环轮换）：⚠️ 去掉默认水波纹 —— Material 波纹按
                // 可组合项的矩形边界铺开，在圆角卡片里就是一块方形闪斑（2026-09-25 用户反馈）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { rightIdx = (rightIdx + 1) % rightOptions.size },
                ) {
                    Text(selected.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.frame_right_axis_switch),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // 内缩按两侧标签实际宽度算（右轴切换后随选项重算）：标签贴卡片边缘，绘图区加宽
            val (fpsStartInset, fpsEndInset) =
                rememberChartInsets(0.0..fpsAxisMax, selected.range, rightTickCount = 5)
            FrameLineChart(
                lines = listOf(
                    FrameLine(fpsVals.map { it as Double? }, fpsLineColor),
                    FrameLine(selected.values, selected.color, onRight = true),
                ),
                leftRange = 0.0..fpsAxisMax,
                rightRange = selected.range,
                rightTickCount = 5,
                startInset = fpsStartInset,
                endInset = fpsEndInset,
                modifier = Modifier.fillMaxWidth(),
            )
            ClockTicks(durationMs, startInset = fpsStartInset, endInset = fpsEndInset)
            Spacer(Modifier.height(6.dp))
            // 图例：FPS + 四个右轴候选；当前选中高亮、其余压暗，点图例直接切换（Kite 同款方块标）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JankLegendDot(fpsLineColor, "FPS")
                rightOptions.forEachIndexed { i, opt ->
                    Spacer(Modifier.width(12.dp))
                    val active = i == rightIdx
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { rightIdx = i },
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    opt.color.copy(alpha = if (active) 1f else 0.35f),
                                    RoundedCornerShape(2.dp),
                                )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = DetailNumericFont,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = if (active) 1f else 0.45f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Power(W) 卡：功率（左轴 W，蓝）+ 电池容量 %（右轴，浅蓝）—— Kite 同名卡。
 * ⚠️ 纵轴口径 = **放电为正**（Kite 观感；与顶部 AVG Power(W) 的"正=充电"口径相反，
 * 充电时曲线为负）。底部 MAX / MIN / AVG 为图中口径；容量 v7 起采集，旧会话整线缺失。
 */
@Composable
private fun FramePowerCard(samples: List<FrameSample>, shape: RoundedCornerShape) {
    val powerW = samples.map { it.powerMw?.let { mw -> -mw / 1_000.0 } }
    val powerVals = powerW.filterNotNull()
    val pMax = ceil(powerVals.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val pMin = powerVals.minOrNull()
    val avg = powerVals.takeIf { it.isNotEmpty() }?.average()
    val durationMs = samples.last().timeMillis - samples.first().timeMillis

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 图表列不再吃横向 padding（2026-09-25 用户反馈：轴标签贴边、绘图区加宽）——
        // 标题行单独保留 16dp；图例与 MAX/MIN/AVG 行本身居中，全宽无碍
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Power(W)", style = MaterialTheme.typography.titleMedium)
                Text("Capacity %", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            val (startInset, endInset) = rememberChartInsets(0.0..pMax, 0.0..100.0)
            FrameLineChart(
                lines = listOf(
                    FrameLine(powerW, PowerBlue),
                    FrameLine(samples.map { it.capacityPct }, CapacityBlue, onRight = true),
                ),
                leftRange = 0.0..pMax,
                rightRange = 0.0..100.0,
                startInset = startInset,
                endInset = endInset,
                modifier = Modifier.fillMaxWidth(),
            )
            ClockTicks(durationMs, startInset = startInset, endInset = endInset)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JankLegendDot(PowerBlue, "Power")
                Spacer(Modifier.width(14.dp))
                JankLegendDot(CapacityBlue, "Capacity")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "MAX: ${pMax.f1()}W MIN: ${pMin?.f1() ?: "—"}W AVG: ${avg?.f1() ?: "—"}W",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = DetailNumericFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Temperature(°C) 卡：CPU（虚拟温度 = CPU 代表温感）/ GPU（GPU 温感区，v7 起采集）/
 * BAT（电池）三线共绘，y 轴固定 0..100（Kite 同款）；缺测断线，旧会话无 GPU 列整线缺失。
 */
@Composable
private fun FrameTempCard(samples: List<FrameSample>, shape: RoundedCornerShape) {
    val durationMs = samples.last().timeMillis - samples.first().timeMillis

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 图表列不再吃横向 padding（同 Power 卡：轴标签贴边、绘图区加宽），标题行保留 16dp
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                "Temperature(°C)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            val (startInset, endInset) = rememberChartInsets(0.0..50.0, null)
            FrameLineChart(
                lines = listOf(
                    FrameLine(samples.map { it.tempVirtualC }, CpuTempBlue),
                    FrameLine(samples.map { it.gpuTempC }, GpuTempPurple),
                    FrameLine(samples.map { it.tempBatteryC }, TempOrange),
                ),
                leftRange = 0.0..50.0,
                rightRange = null,
                startInset = startInset,
                endInset = endInset,
                modifier = Modifier.fillMaxWidth(),
            )
            ClockTicks(durationMs, startInset = startInset, endInset = endInset)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JankLegendDot(CpuTempBlue, "CPU")
                Spacer(Modifier.width(14.dp))
                JankLegendDot(GpuTempPurple, "GPU")
                Spacer(Modifier.width(14.dp))
                JankLegendDot(TempOrange, "BAT")
            }
        }
    }
}

// ── CPU Usage(%) / CPU Frequency(MHz) 卡（Kite 同名卡，2026-09-25 加，详情页置底）──────

/** 多线共轴折线卡的一条序列：label 进图例，values 的 null/NaN 断线不画 */
private class FrameSeries(val label: String, val color: Color, val values: List<Double?>)

/**
 * 8 核机型的典型簇划分（Kite 同款，0-1 小核 / 2-4 中核 / 5-6 大核 / 7 超大核）。
 * ⚠️ 与逐核列的对应关系按核心号写死：少数核数不同的机型，缺位核取不到值 = 该簇断线。
 */
private val CpuClusters = listOf(
    "CPU 0~1" to intArrayOf(0, 1),
    "CPU 2~4" to intArrayOf(2, 3, 4),
    "CPU 5~6" to intArrayOf(5, 6),
    "CPU 7" to intArrayOf(7),
)

/**
 * CPU 两卡的绘图点**统一视图**（2026-09-25 加）：新会话吃 250ms 级 CPU 快样
 * （frame_cpu_samples，对齐 Scene 工具箱的密度观感 —— CPU 是快变量，1s 一点会把
 * 使用率抖动 / 频率升降挡全部摊平），快样表建立前录的旧会话回退到 1s 样本里的
 * CPU 字段（摊平口径）。
 */
private class CpuPoint(
    val timeMillis: Long,
    val totalPct: Double?,
    val corePct: List<Double?>,
    /** 逐核频率 MHz；null = 该核没读到（离线 / 节点不可读），画线时断开 */
    val mhz: List<Double?>,
)

private fun FrameCpuSampleEntity.toCpuPoint() = CpuPoint(
    timeMillis = timeMillis,
    totalPct = totalPct,
    corePct = coreUsagePct,
    mhz = mhzList,
)

/** 旧会话回退：1s 样本的 CPU 字段；频率 0 = 离线核补位值，转成 null 与快样口径对齐 */
private fun FrameSample.toCpuPointFallback() = CpuPoint(
    timeMillis = timeMillis,
    totalPct = cpuUsagePct,
    corePct = cpuCoreUsagePct,
    mhz = cpuMhz.map { m -> m.takeIf { v -> v > 0.0 } },
)

/** 按簇聚合逐核使用率：簇内有效核（非 null）取均值；整簇无数据 = 线缺失 */
private fun clusterUsagePct(points: List<CpuPoint>, cores: IntArray): List<Double?> =
    points.map { p ->
        cores.map { p.corePct.getOrNull(it) }
            .filterNotNull()
            .takeIf { it.isNotEmpty() }
            ?.average()
    }

/** 按簇聚合逐核频率 MHz：跳过 null / 0（离线核的补位值，见 [FrameSample.cpuMhz]）；整簇无数据 = 线缺失 */
private fun clusterMhz(points: List<CpuPoint>, cores: IntArray): List<Double?> =
    points.map { p ->
        cores.map { p.mhz.getOrNull(it)?.takeIf { m -> m > 0.0 } }
            .filterNotNull()
            .takeIf { it.isNotEmpty() }
            ?.average()
    }

/**
 * CPU Usage(%) 卡：Total（全核合计）+ 四条分簇线，全部 0-100 共轴。
 * 数据源 = 250ms 快样（旧会话回退 1s 样本，只剩 Total 一条线）；逐核 / Total
 * 全缺的会话整卡隐藏。
 */
@Composable
private fun FrameCpuUsageCard(points: List<CpuPoint>, shape: RoundedCornerShape) {
    val series = buildList {
        add(FrameSeries("Total", CapacityBlue, points.map { it.totalPct }))
        CpuClusters.forEachIndexed { i, (label, cores) ->
            add(FrameSeries(label, CpuClusterColors[i], clusterUsagePct(points, cores)))
        }
    }
    FrameCpuMultiLineCard("CPU Usage(%)", series, 0.0..100.0, points, shape)
}

/**
 * CPU Frequency(MHz) 卡：四条分簇频率线（按簇聚合）。
 * y 轴 0 → 本场簇均值峰值向上取整到 300MHz 档（Kite 同款"顶格 = 实测峰值"的观感）。
 */
@Composable
private fun FrameCpuFreqCard(points: List<CpuPoint>, shape: RoundedCornerShape) {
    val series = CpuClusters.mapIndexed { i, (label, cores) ->
        FrameSeries(label, CpuClusterColors[i], clusterMhz(points, cores))
    }
    val peak = series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0.0
    val yMax = (ceil(peak / 300.0) * 300.0).coerceAtLeast(300.0)
    FrameCpuMultiLineCard("CPU Frequency(MHz)", series, 0.0..yMax, points, shape)
}

/**
 * 多线共轴折线卡（CPU 两卡共用）：标题 + 折线 + 时间刻度 + 图例。
 * 图例点击切换对应线的显示/隐藏（截图中 Kite「Chart Options」的等价简化：
 * 隐藏以图例压暗表示，至少保留一条可见）；轴标签贴边内缩，同其它折线卡。
 */
@Composable
private fun FrameCpuMultiLineCard(
    title: String,
    series: List<FrameSeries>,
    leftRange: ClosedFloatingPointRange<Double>,
    points: List<CpuPoint>,
    shape: RoundedCornerShape,
) {
    var hidden by remember { mutableStateOf(setOf<Int>()) }
    val visible = series.withIndex().filter { it.index !in hidden }
    val durationMs = points.last().timeMillis - points.first().timeMillis
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 图表列不吃横向 padding（同 Power/Temperature 卡：轴标签贴边、绘图区加宽）
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            val (startInset, endInset) = rememberChartInsets(leftRange, null)
            if (visible.isEmpty()) {
                // 全部隐藏时占位等高：时间刻度与卡片布局不塌陷
                Box(Modifier.fillMaxWidth().height(190.dp))
            } else {
                FrameLineChart(
                    lines = visible.map { FrameLine(it.value.values, it.value.color) },
                    leftRange = leftRange,
                    rightRange = null,
                    startInset = startInset,
                    endInset = endInset,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ClockTicks(durationMs, startInset = startInset, endInset = endInset)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                series.forEachIndexed { i, s ->
                    if (i > 0) Spacer(Modifier.width(12.dp))
                    val shown = i !in hidden
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            hidden = if (shown) {
                                // 至少保留一条可见：最后一条可见线拒绝隐藏
                                if (visible.size > 1) hidden + i else hidden
                            } else {
                                hidden - i
                            }
                        },
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    s.color.copy(alpha = if (shown) 1f else 0.3f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            s.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = DetailNumericFont,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = if (shown) 1f else 0.4f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Frame Time(ms) 卡：每秒平均帧时间柱状图（Kite 同名卡）。
 * 底部 MAX / 方差 —— 方差为帧时间总体方差（稳帧指数是它的平方根，见 [FrameSummaryCard]）。
 * y 轴线性 12 档刻度（Kite 同款观感），柱高按本场最大帧时间自适应。
 */
@Composable
private fun FrameTimeCard(samples: List<FrameSample>, shape: RoundedCornerShape) {
    val ftVals = samples.map { it.frameSpaceMs }
    val maxFt = (ftVals.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val variance =
        if (ftVals.size >= 2) {
            val avg = ftVals.average()
            ftVals.sumOf { (it - avg) * (it - avg) } / ftVals.size
        } else {
            null
        }
    val barColor = MaterialTheme.colorScheme.primary
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Kite 原文标题（用户截图同款，各语言不译）
            Text("Frame Time(ms)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            FrameBarChart(
                values = ftVals,
                barColors = List(ftVals.size) { barColor },
                yMax = maxFt,
                yTickCount = 12,
                durationMs = samples.last().timeMillis - samples.first().timeMillis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "MAX: ${maxFt.f1()}ms  " +
                    stringResource(R.string.frame_variance) + ": " + (variance?.f1() ?: "—"),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = DetailNumericFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Jank 卡：卡顿秒柱状图（Kite 同名卡）。柱高恒 1（该秒命中某档即立柱），三档判定见
 * [jankTiers]（PerfDog 式口径）：小卡顿（灰）/ 卡顿（蓝）/ 严重卡顿（红），每秒只计最高一档。
 * 底部图例为三档秒数合计；ⓘ 打开口径说明。
 */
@Composable
private fun FrameJankCard(samples: List<FrameSample>, shape: RoundedCornerShape) {
    var showInfo by remember { mutableStateOf(false) }
    val tiers = jankTiers(samples)
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    val blue = MaterialTheme.colorScheme.primary
    val barColors = tiers.map { tier ->
        when (tier) {
            1 -> gray
            2 -> blue
            3 -> BigJankRed
            else -> Color.Transparent
        }
    }
    val smallCount = tiers.count { it == 1 }
    val jankCount = tiers.count { it == 2 }
    val bigCount = tiers.count { it == 3 }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Text("Jank", style = MaterialTheme.typography.titleMedium) // Kite 原文
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = stringResource(R.string.frame_jank_info),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(16.dp)
                        // 同稳帧指数 ⓘ：无水波纹 + 再点一次收起
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { showInfo = !showInfo },
                )
            }
            Spacer(Modifier.height(10.dp))
            FrameBarChart(
                values = tiers.map { if (it > 0) 1.0 else 0.0 },
                barColors = barColors,
                yMax = 3.0,
                yTickCount = 4,
                durationMs = samples.last().timeMillis - samples.first().timeMillis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JankLegendDot(gray, "SMALL JANK: $smallCount")
                Spacer(Modifier.width(14.dp))
                JankLegendDot(blue, "JANK: $jankCount")
                Spacer(Modifier.width(14.dp))
                JankLegendDot(BigJankRed, "BIG JANK: $bigCount")
            }
            // ⓘ 弹窗直接交 GlassDialog（自带入场动效），不包 AnimatedVisibility ——
            // 理由同稳帧指数 ⓘ（对全屏毛玻璃遮罩做动画 = 每帧重渲染模糊层，卡顿）
            if (showInfo) {
                FrameInfoDialog(
                    title = "Jank",
                    body = stringResource(R.string.frame_jank_info),
                    onDismiss = { showInfo = false },
                )
            }
        }
    }
}

@Composable
private fun JankLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = DetailNumericFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 秒级柱状图（Frame Time / Jank 两卡共用）。
 *
 * - y 轴线性 [yMax]、[yTickCount] 档横向网格线，刻度值用 nativeCanvas 直绘在**绘图区左侧的
 *   留白槽**（右对齐贴轴；最底下一档不标 —— x 轴本身就是 0 线，Kite 同款）；左缘内缩按最宽
 *   标签实测（[rememberBarChartLeftInset]），标签不再压进框线内侧；
 * - x 轴 5 个时间刻度：0 / 四分点 / 中点 / 四分之三点 / 全程（Kite 的 0,1m3s,2m6s,… 同款）；
 * - [values] 每秒一柱、[barColors] 等长一一对应；0 / NaN 不画（该秒无帧或无事件）。
 */
@Composable
private fun FrameBarChart(
    values: List<Double>,
    barColors: List<Color>,
    yMax: Double,
    yTickCount: Int,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    // 绘图区左缘按最宽 y 标签实测内缩（口径同 FrameLineChart 的贴边内缩，2026-09-25）：
    // 标签右对齐画在轴左侧的留白槽里 —— 旧版绘图区从 x=0 起、标签左对齐压在框线内侧，
    // Jank 卡的 3/2/1 看起来"画进了图表内部"（用户截图反馈）
    val startInset = rememberBarChartLeftInset(yMax, yTickCount)
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val n = values.size
                if (n == 0 || yMax <= 0.0) return@Canvas
                val plotLeft = startInset.toPx()
                val plotW = size.width - plotLeft
                val barW = plotW / n
                labelPaint.textSize = 9.sp.toPx()
                labelPaint.color = labelColor.toArgb()
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                for (i in 0 until yTickCount) {
                    val frac = i / (yTickCount - 1).toFloat()
                    val y = size.height * frac
                    drawLine(gridColor, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = 1f)
                    if (i < yTickCount - 1) {
                        val v = yMax * (1 - frac)
                        // 首档文字画在网格线下方，其余画在上方，避免贴边裁切
                        val baseline = if (i == 0) y + labelPaint.textSize else y - 4.dp.toPx()
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.US, "%.0f", v),
                            plotLeft - 6.dp.toPx(),
                            baseline,
                            labelPaint,
                        )
                    }
                }
                // 内部三条竖向分隔线 + 外框（与折线图 FrameLineChart 同一套骨架）
                for (q in 1..3) {
                    val x = plotLeft + plotW * q / 4
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
                drawRect(
                    labelColor.copy(alpha = 0.4f),
                    topLeft = Offset(plotLeft, 0f),
                    size = Size(plotW, size.height),
                    style = Stroke(1f),
                )
                values.forEachIndexed { i, v ->
                    if (v > 0.0 && !v.isNaN() && i < barColors.size) {
                        val h = (v / yMax * size.height).toFloat().coerceAtMost(size.height)
                        drawRect(
                            color = barColors[i],
                            topLeft = Offset(plotLeft + i * barW + 0.5f, size.height - h),
                            size = Size((barW - 1f).coerceAtLeast(1f), h),
                        )
                    }
                }
            }
        }
        ClockTicks(durationMs, startInset = startInset)
    }
}

/**
 * 折线图绘图区的**兜底**内缩 —— 实际内缩一律由 [rememberChartInsets] 按两侧标签真实宽度
 * 算出并随卡片传入（2026-09-25 用户反馈：固定 30/42dp 让 1~2 字符的轴标签离卡片边缘
 * 一大截，绘图区白白窄一圈）。仅保留作 FrameLineChart 的参数默认值。
 */
private val ChartPlotInsetStart = 30.dp
private val ChartPlotInsetEnd = 42.dp

/**
 * 轴刻度文本：档步长 ≥1 走整数（120/60/25），<1 保留 1 位小数（0.8/1.5）。
 * 后者修掉 Power 卡 0..3 轴的老毛病 —— "%.0f" 会把 0.75/1.5/2.25 打成 1/2/2，
 * y 轴出现 3/2/2/1 重档（用户截图可见）。
 */
private fun tickText(v: Double, step: Double): String =
    if (step >= 1.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

/** 左轴 4 档刻度文本（与 FrameLineChart 的画法同源，inset 计算不能和绘制各编一套） */
private fun leftTickTexts(r: ClosedFloatingPointRange<Double>): List<String> {
    val step = (r.endInclusive - r.start) / 4
    return (1..4).map { tickText(r.start + (r.endInclusive - r.start) * it / 4, step) }
}

/** 右轴 [rightTickCount] 档刻度文本（含顶档与 0，与 FrameLineChart 同源） */
private fun rightTickTexts(r: ClosedFloatingPointRange<Double>, rightTickCount: Int): List<String> {
    val step = (r.endInclusive - r.start) / (rightTickCount - 1)
    return (0 until rightTickCount)
        .map { tickText(r.endInclusive - (r.endInclusive - r.start) * it / (rightTickCount - 1), step) }
}

/**
 * 折线图轴标签的**贴边内缩**：左 = 边距4 + 最宽左标签 + 间隙6，右 = 间隙6 + 最宽右标签
 * + 边距4，标签按 FrameLineChart 的实际画法（10sp 默认字体）用 Paint 实测宽度。
 * 返回值同时喂给 [FrameLineChart] 与 [ClockTicks]，保证 x 轴刻度与绘图区竖线对位。
 * 右轴为 null（Temperature 卡）时右侧只留 10dp 呼吸位，绘图区尽量铺满卡宽。
 */
@Composable
private fun rememberChartInsets(
    leftRange: ClosedFloatingPointRange<Double>?,
    rightRange: ClosedFloatingPointRange<Double>?,
    rightTickCount: Int = 3,
): Pair<Dp, Dp> {
    val density = LocalDensity.current
    return remember(leftRange, rightRange, rightTickCount, density) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 10.sp.toPx() }
        }
        val widestLeft = leftRange
            ?.let { r -> leftTickTexts(r).maxOf { paint.measureText(it) } }
            ?.let { with(density) { it.toDp() } }
        val widestRight = rightRange
            ?.let { r -> rightTickTexts(r, rightTickCount).maxOf { paint.measureText(it) } }
            ?.let { with(density) { it.toDp() } }
        val start = 4.dp + (widestLeft ?: 0.dp) + 6.dp
        val end = if (rightRange == null) 10.dp else 6.dp + widestRight!! + 4.dp
        start to end
    }
}

/**
 * 柱状图（[FrameBarChart]）绘图区左缘内缩：按 y 轴最宽标签（9sp，最底档不标）实测宽度算，
 * 口径同 [rememberChartInsets]（左 = 边距4 + 最宽标签 + 间隙6）。柱状卡无右轴，右侧贴满卡宽。
 */
@Composable
private fun rememberBarChartLeftInset(yMax: Double, yTickCount: Int): Dp {
    val density = LocalDensity.current
    return remember(yMax, yTickCount, density) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 9.sp.toPx() }
        }
        var maxW = 0f
        for (i in 0 until yTickCount - 1) {
            val v = yMax * (1 - i.toFloat() / (yTickCount - 1))
            maxW = maxOf(maxW, paint.measureText(String.format(Locale.US, "%.0f", v)))
        }
        4.dp + with(density) { maxW.toDp() } + 6.dp
    }
}

/**
 * x 轴时间刻度行：0 / 四分点 / 中点 / 四分之三点 / 全程（Kite 的 0,1m3s,2m6s,… 同款）。
 *
 * 标签**中心精确对齐**在对应刻度竖线正下方（首标签左贴齐、尾标签右贴齐）——用 Canvas
 * 直绘而不是 Row+SpaceBetween：后者按全宽分布且随文字宽度漂移，和折线图内缩后的
 * 竖线对不上（用户实测：「x 轴的时间不在对应的竖向正下方」）。
 * [startInset] / [endInset] = 绘图区左右内缩（折线图传 [ChartPlotInsetStart/End]，
 * 柱状图满宽传 0），必须与 FrameLineChart 的 plotLeft/plotRight 完全一致。
 */
@Composable
private fun ClockTicks(
    durationMs: Long,
    modifier: Modifier = Modifier,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .padding(start = startInset, end = endInset)
    ) {
        paint.textSize = 10.sp.toPx()
        paint.color = labelColor.toArgb()
        for (q in 0..4) {
            val text = formatClock(durationMs * q / 4)
            paint.textAlign = when (q) {
                0 -> android.graphics.Paint.Align.LEFT
                4 -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText(
                text,
                if (q == 4) size.width else size.width * q / 4,
                size.height,
                paint,
            )
        }
    }
}

/** 时长刻度：63250ms → "1m3s"（Kite 同款）；不足一分钟只给秒，短于 10s 带一位小数避免重复 "0s" */
private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0"
    val totalSec = ms / 1000.0
    val m = totalSec.toInt() / 60
    val s = totalSec.toInt() % 60
    return when {
        m > 0 -> "${m}m${s}s"
        totalSec < 10 -> String.format(Locale.US, "%.1fs", totalSec)
        else -> "${s}s"
    }
}
