package com.chen.powermeter.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.chen.powermeter.R
import com.chen.powermeter.data.FrameSample
import com.chen.powermeter.data.db.FrameCpuSampleEntity
import com.chen.powermeter.data.db.FrameFpsSampleEntity
import com.chen.powermeter.data.db.FrameSampleEntity
import com.chen.powermeter.data.db.FrameSession
import com.chen.powermeter.data.db.FrameDatabase
import com.chen.powermeter.ui.common.AppCard
import com.chen.powermeter.ui.common.BlurTopBar
import com.chen.powermeter.ui.theme.PowerMeterTheme
import com.chen.powermeter.util.AppTransitions
import com.chen.powermeter.util.FrameXlsxExporter
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

/**
 * 详情页顶栏模式开关（2026-09-28 用户要求：先关闭模糊顶栏，改半透明样式）：
 * - **false = 半透明模式**：顶栏直接铺 `surface` 色 alpha 0.9（与 BlurTopBar 渐变盖板
 *   顶档同基准），滚动内容从底下透出；内容层不再挂 `hazeSource` / `layerBackdrop`
 *   （供源无消费者时是纯捕获开销）。
 * - **true = 完整回退**：三档模糊顶栏原实现逐行保留（≥33 Kyant backdrop / 31–32 Haze
 *   / 26–30 渐变盖板），改回 true 重编即整体还原，无其它改动点。
 */
private const val FRAME_DETAIL_TOPBAR_BLUR = true

/**
 * ⚠️ 临时排查工具（2026-09-28 横屏滑动卡顿问题，定位后移除）：仅主线程调用的轻量计数器。
 * 指标含义：
 * - `*.recompose` = 该 Composable 重组频率（SideEffect 计数）；滑动中持续增长 = 有重组波及；
 * - `lineChart.draw` = FrameLineChart 绘制指令重录频率；滑动中逼近帧率 = 每帧重绘（有失效源）；
 * - `lineChart.buildPaths` = Path 预构建重建事件（期望：仅旋转/数据变化时出现）。
 * 输出行首 [H]/[V] = 横/竖屏（ChartPerf.orientation 由 Activity 维护）。
 */
private object ChartPerf {
    private const val TAG = "FrameDetailPerf"
    private const val WINDOW_MS = 1000L

    /** 当前屏幕方向标记（Configuration.ORIENTATION_*），由 FrameDetailActivity 维护 */
    var orientation: Int = 0

    private class Stat {
        var count = 0
        var totalNs = 0L
        var maxMs = 0f
        var windowStart = 0L
    }

    private val stats = HashMap<String, Stat>()

    private fun now(): Long = SystemClock.uptimeMillis()

    private fun label(): String =
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "H" else "V"

    /** 事件计数：到达窗口边界时输出一次并清零 */
    fun tick(name: String) {
        val t = now()
        val s = stats.getOrPut(name) { Stat().also { it.windowStart = t } }
        s.count++
        if (t - s.windowStart >= WINDOW_MS) {
            Log.i(TAG, "[${label()}] $name: ${s.count} in ${t - s.windowStart}ms")
            s.count = 0
            s.windowStart = t
        }
    }

    /** 计数 + 耗时聚合（次数/均值/峰值）；单次超过 8ms（120Hz 帧预算）立即告警 */
    fun timed(name: String, ns: Long) {
        val ms = ns / 1_000_000f
        val t = now()
        val s = stats.getOrPut(name) { Stat().also { it.windowStart = t } }
        s.count++
        s.totalNs += ns
        if (ms > s.maxMs) s.maxMs = ms
        if (ms > 8f) Log.w(TAG, "[${label()}] $name: 单次 %.1fms 超 120Hz 帧预算".format(ms))
        if (t - s.windowStart >= WINDOW_MS) {
            if (s.count > 0) {
                Log.i(
                    TAG,
                    "[${label()}] $name: ${s.count}x avg=%.1fms max=%.1fms in %dms"
                        .format(s.totalNs / s.count / 1e6f, s.maxMs, t - s.windowStart),
                )
            }
            s.count = 0
            s.totalNs = 0
            s.maxMs = 0f
            s.windowStart = t
        }
    }

    /** Path 预构建重建事件：期望只在旋转 / 数据变化时出现一条 */
    fun logBuild(name: String, lines: Int, maxPoints: Int, ns: Long) {
        Log.i(TAG, "[${label()}] $name: rebuilt lines=$lines maxPoints=$maxPoints time=%.1fms".format(ns / 1e6f))
    }
}


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
 *
 * **转场形态**（2026-09-27 用户定稿：进场侧边滑入、退场一镜到底）：列表卡片点击时
 * [AppTransitions.register] 锚点 → [AppTransitions.capture] 截图 → 本页 [launch] 经
 * [AppTransitions.launchWithCollapseBack] 登记 Handoff，进场走主题窗口侧边滑入（大场次
 * 整页图表卡首帧组合重，ClipReveal 展开要求首帧整页就绪、会卡）；onPostCreate 仍把
 * 页面根包进 ClipRevealLayout（expandOnEnter=false 只装配不展开），返回整页收回卡片
 * 矩形后 finish——一镜到底只在退场侧。
 * 源页与目标页都跟随系统方向（均不锁横竖屏），同方向无旋转 → 不传 anchorTransform。
 */
class FrameDetailActivity : ComponentActivity() {

    /**
     * 应用当前深浅（2026-09-28 修复系统深浅色切换回顶部）：本页 configChanges 含 uiMode
     * 后系统切换**不再重建**，Compose 的 LocalConfiguration 依赖 Activity 的配置分发——
     * 后台切换时 ViewRootImpl 跳过分发会不跟随（2026-08-15 已知坑），因此深浅不走
     * isSystemInDarkTheme() 直读，改由 Activity 三处驱动：onCreate 初值 /
     * onConfigurationChanged（前台切换）/ onResume 兜底（后台切换回前台）。
     * 变化经 PowerMeterTheme 闸门 → 旧界面快照 + 右下角圆孔揭露动画（ThemeTransition）。
     */
    private val darkThemeState by lazy { mutableStateOf(isNightMode()) }

    /**
     * 进入本页时的系统深浅 = 收拢截图素材（冻结列表快照 / 锚点卡片截图）的拍摄主题
     * （2026-09-28）：返回时与当前值不一致 → 素材是旧主题像素，收拢前需主题修复
     * （见 AppTransitions.collapseAndFinish 的 staleTheme 通道）。
     */
    private var initialDark = false

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** 非法 / 缺失的会话 ID */
        private const val NO_ID = -1L

        /**
         * 打开详情页。有 [capture]（列表卡片截图 + 窗口矩形）→ 登记 Handoff 供**退场**
         * 收拢一镜到底，进场走主题侧边滑入（见类注释）；null（截图失败等）→ 普通启动
         * （退场也无收拢，主题窗口动画）。
         */
        fun launch(context: Context, sessionId: Long, capture: AppTransitions.Capture? = null) {
            val intent = Intent(context, FrameDetailActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            val act = context as? Activity
            if (capture != null && act != null) {
                AppTransitions.launchWithCollapseBack(act, intent, capture)
            } else {
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ChartPerf.orientation = resources.configuration.orientation
        initialDark = isNightMode()
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())

        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, NO_ID) ?: NO_ID

        setContent {
            PowerMeterTheme(darkTheme = darkThemeState.value) {
                var session by remember { mutableStateOf<FrameSession?>(null) }
                var samples by remember { mutableStateOf<List<FrameSample>>(emptyList()) }
                // CPU 快样（250ms 级）；空 = 快样表建立前录的旧会话 → 详情页回退 1s 样本
                var cpuPoints by remember { mutableStateOf<List<CpuPoint>>(emptyList()) }
                // 帧率子拍点（250ms 级，2026-09-27 起）；空 = 子拍表建立前录的旧会话 →
                // FPS 曲线回退 1s 样本
                var fpsPoints by remember { mutableStateOf<List<FrameFpsSampleEntity>>(emptyList()) }
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
                        val fps = dao.fpsSamples(sessionId)
                        DetailData(session, samples, points, fps)
                    }
                    session = loaded.session
                    samples = loaded.samples
                    cpuPoints = loaded.cpuPoints
                    fpsPoints = loaded.fpsPoints
                }

                // DialogBackdropHost：详情页弹窗（稳帧指数 / Jank ⓘ）走「宿主 + slot」玻璃路径。
                // ⚠️ 缺宿主时 GlassDialog 降级为**就地渲染**——说明弹窗长在统计卡里，
                // 出现/消失会瞬间顶开卡片高度（用户实测反馈）。口径同 MainActivity（2026-09-25 补）。
                DialogBackdropHost {
                    FrameDetailScreen(
                        session = session,
                        samples = samples,
                        cpuPoints = cpuPoints,
                        fpsPoints = fpsPoints,
                        onBack = { finish() },
                        onShare = { s, sm, fp, cp -> shareSession(s, sm, fp, cp) },
                    )
                }
            }
        }

        // 窗口关闭转场压 0：退场由收拢动画接管（整页裁剪回源卡片矩形，末帧唯一可见
        // 内容 = 卡片截图，activityClose* 的滑出会把这帧整窗滑出）。OPEN 不压——进场
        // 走主题侧边滑入（2026-09-27 用户定稿，见类注释）。API 34+ 在此注册；更早版本
        // 由 CollapseHost.finishNow 的 overridePendingTransition(0,0) 兜底。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        // 后台切换兜底（configChanges 含 uiMode 的已知坑：App 在后台时 ViewRootImpl
        // 跳过配置分发，Compose 不跟随）——回前台读 Resources 最新配置，走**静默通道**：
        // 错过的变化立即呈现目标主题、不补播圆孔动画（2026-09-28）
        val night = isNightMode()
        if (darkThemeState.value != night) {
            // 回前台才侦测到"后台期间错过的变化"（本页窗口 stopped 期间不分发配置）→
            // 静默落地，不补播圆孔动画（2026-09-28）
            ThemeTransition.requestSilent()
            darkThemeState.value = night
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !night)
            AppTransitions.onThemeChangedForSnapshot(this, night)
        }
    }

    /**
     * 跨 Activity 退场收拢装配（SportLink DeviceManageActivity / 本项目 TrendFullscreenActivity
     * 同款时机）：有新鲜 Handoff（从列表卡片点进）→ 页面根包进 ClipRevealLayout 并钉
     * 卡片截图（expandOnEnter=false：进场已走主题滑入，这里只服务退场收拢）；无（Handoff
     * 过期/截图失败）→ no-op，页面普通显示。
     */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        AppTransitions.installWindowTransform(this, expandOnEnter = false)
    }

    /**
     * 返回 = 收拢转场优先（onBack 按钮 / 返回手势 / 返回键都经此）：有收拢宿主 → 整页
     * 裁剪收回源卡片矩形后 finish（一镜到底）；无 → 直接 finish（主题窗口动画）。
     */
    override fun finish() {
        // staleTheme（2026-09-28）：详情页期间系统深浅变化过 → 收拢截图素材（冻结列表 /
        // 容器底色 / 遮罩色 / 锚点卡片）是旧主题像素，收拢前逐项修复到目标主题，
        // 一镜到底返回动画不再带旧色
        val darkTarget = isNightMode()
        // 退场交棒（2026-09-28 二轮）：把目标深浅留给即将显示的源页（帧率列表），让它在
        // 首帧绘制之前就切成目标主题 —— 否则返回瞬间先按旧主题画一帧（闪回白色），
        // 随后的配置分发再补播一遍圆孔动画。消费方见 MainActivity.onStart。
        ThemeTransition.noteExitTheme(darkTarget)
        if (
            AppTransitions.collapseAndFinish(
                this,
                staleTheme = darkTarget != initialDark,
                darkTarget = darkTarget,
            )
        ) return
        super.finish()
    }

    /**
     * 把本场记录按**库里原生节奏**导出 xlsx 并调起系统分享（ACTION_SEND，见
     * [FrameXlsxExporter]）：新会话 250ms 子拍行（FPS/CPU），旧会话回退 1s 样本行。
     *
     * 文件写入 `cacheDir/share/`，经 FileProvider（`${applicationId}.fileprovider`，
     * 路径表 res/xml/file_paths.xml）临时授予读权限 —— 导出文件不落公共目录，
     * 不需要任何存储权限。每次分享前清空 share 目录，分享多了也不攒垃圾。
     */
    private fun shareSession(
        session: FrameSession,
        samples: List<FrameSample>,
        fpsPoints: List<FrameFpsSampleEntity>,
        cpuPoints: List<CpuPoint>,
    ) {
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
                    val cpuRows = cpuPoints.map {
                        FrameXlsxExporter.CpuExportRow(it.timeMillis, it.totalPct, it.corePct, it.mhz)
                    }
                    val file = File(dir, FrameXlsxExporter.fileName(session))
                    file.writeBytes(FrameXlsxExporter.build(session, samples, fpsPoints, cpuRows))
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
        // ⚠️ configChanges 含 uiMode（2026-09-28）后系统深浅切换不再重建本页（重建会
        // 丢滚动位置回到顶部，即用户报的 bug）：改在这里驱动深浅状态 —— 经 PowerMeterTheme
        // 闸门播圆孔揭露动画（ThemeTransition），并重设系统栏图标色
        ChartPerf.orientation = newConfig.orientation
        val dark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (darkThemeState.value != dark) {
            // 送达时本页还没 onStart（后台错过的变化）→ 静默落地（同 MainActivity，
            // 判据见 ThemeTransition.isHostForeground）
            if (!ThemeTransition.isHostForeground(this)) ThemeTransition.requestSilent()
            darkThemeState.value = dark
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !dark)
            // 截图素材（冻结列表 / 锚点卡片）是旧主题像素 → 后台重映射到新主题，
            // 返回收拢时无缝呈新色（2026-09-28）
            AppTransitions.onThemeChangedForSnapshot(this, dark)
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

/** 详情页一次装载的四件套（sessionId 变化时读一次） */
private class DetailData(
    val session: FrameSession?,
    val samples: List<FrameSample>,
    val cpuPoints: List<CpuPoint>,
    val fpsPoints: List<FrameFpsSampleEntity>,
)

@Composable
private fun FrameDetailScreen(
    session: FrameSession?,
    samples: List<FrameSample>,
    /** CPU 两卡的绘图点（250ms 快样，旧会话回退 1s 样本 —— 见 [CpuPoint]） */
    cpuPoints: List<CpuPoint>,
    /** FPS 曲线的绘图点（250ms 子拍差分，旧会话回退 1s 样本，见 [FrameFpsTempCard]） */
    fpsPoints: List<FrameFpsSampleEntity>,
    onBack: () -> Unit,
    /** 顶栏分享：按库里原生节奏导出 xlsx 并调起系统分享（Activity 侧实现） */
    onShare: (
        session: FrameSession,
        samples: List<FrameSample>,
        fpsPoints: List<FrameFpsSampleEntity>,
        cpuPoints: List<CpuPoint>,
    ) -> Unit,
) {
    // 本页卡片圆角统一 10dp（2026-09-25 用户指定；不用 LocalCornerRadius 的大圆角）
    val cardShape = RoundedCornerShape(10.dp)
    val context = LocalContext.current
    SideEffect { ChartPerf.tick("detailScreen.recompose") }
    // FPS 轴上限 = 设备铺满刷新率 + 2（2026-09-27 用户指定：铺满刷新率的曲线恰好顶在轴顶、
    // 视觉上像溢出，留 2fps 余量 —— 120Hz 铺满 → 0..122。取 supportedModes 最大值，如
    // 120/144；取不到退回录制时的激活刷新率）。remember(session)：supportedModes 是冷数据，
    // 没必要每次重组都查。
    // ⚠️ 4Hz 短窗的边界帧效应（2026-09-27）：250ms 差分窗合法上限 = refresh + 1/dt
    // （= +4），单点可以高于 1s 口径的轴顶 —— 轴顶随实测最大子拍点再抬高 1fps，避免
    // 尖峰画出绘图区；无子拍点的旧会话维持用户指定的 +2
    val fpsAxisMax = remember(session, fpsPoints) {
        val panel = context.display?.supportedModes
            ?.maxOfOrNull { it.refreshRate }?.roundToInt()?.toDouble() ?: 0.0
        val base = maxOf(panel, session?.refreshRateHz?.toDouble() ?: 0.0, 1.0) + 2.0
        val spike = fpsPoints.maxOfOrNull { it.fps ?: 0.0 } ?: 0.0
        maxOf(base, spike + 1.0)
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
    // 顶栏模糊供源链只在模糊模式下挂（FRAME_DETAIL_TOPBAR_BLUR=false 时半透明顶栏
    // 不消费采样层，hazeSource/layerBackdrop 挂着是纯捕获开销 —— 2026-09-28）
    val useKyantTopBar = FRAME_DETAIL_TOPBAR_BLUR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val topBarBackdrop = rememberLayerBackdrop()
    val topBarHeight = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 64.dp

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (FRAME_DETAIL_TOPBAR_BLUR) {
                        Modifier
                            .hazeSource(state = hazeState)
                            .then(if (useKyantTopBar) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
                    } else {
                        Modifier
                    }
                )
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
                        FrameFpsTempCard(samples, fpsPoints, fpsAxisMax, cardShape)
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

        // 顶栏内容两模式共用一份（title / 返回 / 分享只写一次）：
        // 模糊模式 containerColor 透明（BlurTopBar 盖板供底色）；半透明模式直接铺
        // surface 色 alpha 0.9（与 BlurTopBar 渐变盖板顶档同基准），滚动内容从底下透出
        // （2026-09-28 用户指定：关闭模糊顶栏，改半透明样式）
        val barContainerColor =
            if (FRAME_DETAIL_TOPBAR_BLUR) Color.Transparent
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        val topBarContent: @Composable () -> Unit = {
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
                    // 原生节奏 xlsx 分享：无样本（理论不可达）时 Activity 侧 toast 兜底
                    IconButton(
                        onClick = { session?.let { onShare(it, samples, fpsPoints, cpuPoints) } },
                        enabled = session != null,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Share,
                            contentDescription = stringResource(R.string.frame_share),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barContainerColor,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = topBarInsets,
            )
        }
        if (FRAME_DETAIL_TOPBAR_BLUR) {
            // 回退路径：三档模糊顶栏（≥33 Kyant backdrop / 31–32 Haze / 26–30 渐变盖板），
            // 原实现完整保留 —— FRAME_DETAIL_TOPBAR_BLUR 改回 true 重编即整体还原
            BlurTopBar(
                kyantBackdrop = if (useKyantTopBar) topBarBackdrop else null,
                hazeState = if (useKyantTopBar) null else hazeState,
                hazeStyle = if (useKyantTopBar) null else topBarHazeStyle,
            ) {
                topBarContent()
            }
        } else {
            topBarContent()
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

    AppCard(
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
            var showSteadyInfo by remember { mutableStateOf(false) }

            SummaryRow {
                SummaryCell("MAX", session.maxFps.f0(), "FPS", Modifier.weight(1f))
                SummaryCell("MIN", session.minFps.f0(), "FPS", Modifier.weight(1f))
                SummaryCell("AVG", session.avgFps.f0(), "FPS", Modifier.weight(1f))
                SummaryCell(stringResource(R.string.frame_variance), fpsSd?.f0() ?: "—", "FPS", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            SummaryRow {
                SummaryCell("1% Low", session.lowFps1.f0OrDash(), "FPS", Modifier.weight(1f))
                SummaryCell("5% Low", session.lowFps5.f0OrDash(), "FPS", Modifier.weight(1f))
                SummaryCell("MAX", maxBatTemp?.f1() ?: "—", stringResource(R.string.frame_card_temperature_name), Modifier.weight(1f))
                SummaryCell("AVG", avgPowerMw?.div(1_000.0)?.f2() ?: "—", stringResource(R.string.frame_card_power), Modifier.weight(1f))
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

/**
 * 折线规格：values 里 null/NaN = 缺测秒（断线不画）；[FrameLine.onRight] = 走右轴（默认左轴）；
 * [strokeWidth] 线宽 px（默认 5f 主线规格；CPU 两卡的簇内逐核细线传更小值）。
 */
private class FrameLine(
    val values: List<Double?>,
    val color: Color,
    val onRight: Boolean = false,
    val strokeWidth: Float = 5f,
)

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
 * - [leftRange] / [rightRange] = 左右轴值域，null = 不画该轴刻度；左轴 [leftTickCount] 等分
 *   虚线网格 + 刻度（右对齐贴轴），右轴顶/中/底三档刻度（nativeCanvas 直绘，走 Compose 密度）；
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
    /** 左轴网格/刻度行数 = 顶线到底线的等分数（不标底档 0）：默认 4 等分；Power 卡传 7（Kite 观感） */
    leftTickCount: Int = 4,
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
    // 画布像素尺寸：Path 预构建需要 draw 阶段才有的 size，经 onSizeChanged 提前到组合期拿到
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    // ⚠️ 折线 Path 预构建（2026-09-28 滑动卡顿修复）：旧实现把 Path 构建写在 draw lambda
    // 里 —— Canvas 不是「画好就存成图片」，它是绘制指令录制（RenderNode/DisplayList），任何
    // 一次重绘失效（重组波及 / 系统丢弃缓存 / 点图例）都会把 draw lambda 从头重跑。CPU 两卡
    // 12 条线 × 2400 点（250ms 快样）= 每次失效约 3 万次 moveTo/lineTo 的纯 CPU 几何计算，
    // 滑动中一插帧就掉。现在 Path 只在 lines / 轴区间 / 尺寸变化时重建一次，重绘只剩
    // drawPath 指令录制；构建前先按绘图区像素宽做 min-max 抽稀（每 bin 保 min+max，峰谷不丢）。
    // ⚠️ 折线预渲染为位图（2026-09-28 横屏卡顿修复，用户实测减线即流畅定位）：
    // RT 每帧回放 12 条 stroke path（横屏 ~1.4 万段）的绘制命令 + 路径几何处理成本与
    // 线数/点数成正比，横屏绘制量是竖屏 ~1.7 倍 → 顶破 120Hz 帧预算（RT-issue 段大、
    // GPU/UI 都不慢的实测形态）。这里把全部折线一次性画进 ImageBitmap（设备像素级，
    // 视觉 1:1 无损），每帧只剩一条 drawImage 贴图命令 —— 即「画好存成一张图片」，
    // 成本与线数彻底脱钩。网格 / 轴标签留在实时绘制（命令数少、与线数无关）。
    // 位图仅在 lines / 轴区间 / 尺寸 / 主题色变化时重建（含旋转一次）。
    val lineBitmap = remember(lines, leftRange, rightRange, chartSize, startInset, endInset, density) {
        val t0 = System.nanoTime()
        val maxCount = lines.maxOfOrNull { it.values.size } ?: 0
        val bmp = if (chartSize.width < 2 || chartSize.height < 2 || maxCount < 2) {
            null
        } else {
            val plotLeft = with(density) { startInset.toPx() }
            val plotW = (chartSize.width - plotLeft - with(density) { endInset.toPx() })
                .coerceAtLeast(1f)
            // 抽稀档位：每 ~3px 一个 bin —— 250ms 快样抽到几百点，视觉与全量点无差
            val bins = (plotW / 3f).toInt().coerceIn(120, 600)
            val bmp = ImageBitmap(
                plotW.toInt().coerceAtLeast(1),
                chartSize.height.coerceAtLeast(1),
            )
            // 折线画进位图内坐标系（x 从 0 起）：实时绘制时按 plotLeft 贴回原位置
            val bmpCanvas = androidx.compose.ui.graphics.Canvas(bmp)
            CanvasDrawScope().draw(
                density, LayoutDirection.Ltr, bmpCanvas,
                Size(bmp.width.toFloat(), bmp.height.toFloat()),
            ) {
                clipRect(0f, 0f, size.width, size.height) {
                    lines.forEach { line ->
                        val r = (if (line.onRight) rightRange else leftRange) ?: return@forEach
                        val path = buildChartLinePath(
                            downsampleForChart(line.values, bins), 0f, size.width, size.height, r,
                        )
                        drawPath(path, line.color, style = Stroke(line.strokeWidth))
                    }
                }
            }
            bmp
        }
        ChartPerf.logBuild("lineChart.buildPaths", lines.size, maxCount, System.nanoTime() - t0)
        bmp
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(190.dp)
            .onSizeChanged { chartSize = it }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            ChartPerf.tick("lineChart.draw")
            if (lineBitmap == null) return@Canvas
            val plotLeft = startInset.toPx()
            val plotRight = size.width - endInset.toPx()
            val plotW = plotRight - plotLeft
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f))
            for (i in 1..leftTickCount) {
                val y = size.height * (1 - i / leftTickCount.toFloat())
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
                val step = (r.endInclusive - r.start) / leftTickCount
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                for (i in 1..leftTickCount) {
                    val y = size.height * (1 - i / leftTickCount.toFloat())
                    drawContext.canvas.nativeCanvas.drawText(
                        tickText(r.start + (r.endInclusive - r.start) * i / leftTickCount, step),
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
                    // 标签统一垂直居中在横线上（2026-09-27 用户报"顶部数字没居中于横线"：
                    // 旧版顶档画在线下方、底档画在线上方 2dp、中间居中，三钟摆错落）。
                    // Compose 绘制默认不裁剪：顶档上缘探出画布顶、底档下缘探出画布底
                    // （下方 ClockTicks 16dp 高里文字贴底画，顶部 ~9dp 空白可容纳），均无碍
                    drawContext.canvas.nativeCanvas.drawText(
                        tickText(v, step),
                        plotRight + 6.dp.toPx(),
                        y + labelPaint.textSize / 3,
                        labelPaint,
                    )
                }
            }
            // 折线裁剪在绘图区内：轴范围固定后（FPS 0-刷新率 / 温度 0-50），超范围样本
            // 不得越出外框压到轴题上。折线已在组合期渲染进位图（含抽稀），
            // 这里只贴一条 drawImage —— 每帧成本与线数脱钩（2026-09-28 横屏卡顿修复）。
            // ⚠️ dstSize 必须传当前绘图区尺寸（而非默认的位图原尺寸）：旋转瞬间位图
            // 还是旧方向尺寸、重建要等 onSizeChanged 后下一帧，不拉伸的话旧窄图只会贴在
            // 左半边（"半截曲线、半秒后才铺开"的观感 bug）。按 dstSize 拉伸后旧图立即
            // 铺满全宽（横向拉伸等价于同一时间轴映射，短暂略糊），新图就绪自动 1:1
            clipRect(left = plotLeft, top = 0f, right = plotRight, bottom = size.height) {
                drawImage(
                    image = lineBitmap,
                    dstOffset = IntOffset(plotLeft.toInt(), 0),
                    dstSize = IntSize(
                        (plotRight - plotLeft).toInt().coerceAtLeast(1),
                        size.height.toInt().coerceAtLeast(1),
                    ),
                )
            }
        }
    }
}

/**
 * min-max 抽稀：把等间隔采样点压进 [bins] 个等宽 bin，每 bin 输出 min+max 两点（按
 * 时间序）——峰、谷一律保留，短谷可见性不受影响（FPS 卡 2026-09-27 的 4Hz 短谷口径不变）。
 * bin 内含 null/NaN（缺测）→ 该 bin 输出 null 断线，与「缺测断线」口径一致；点数不超过
 * 2×[bins] 时原样返回（短会话不抽）。
 */
private fun downsampleForChart(values: List<Double?>, bins: Int): List<Double?> {
    val n = values.size
    if (n <= bins * 2) return values
    val out = ArrayList<Double?>(bins * 2 + 2)
    out.add(values.first())
    val binW = n.toDouble() / bins
    var start = 1
    for (b in 0 until bins) {
        val end = minOf(n, ceil((b + 1) * binW).toInt())
        if (end <= start) continue
        var hasNull = false
        var minI = -1
        var maxI = -1
        for (j in start until end) {
            val v = values[j]
            if (v == null || v.isNaN()) {
                hasNull = true
                continue
            }
            if (minI == -1 || v < values[minI]!!) minI = j
            if (maxI == -1 || v > values[maxI]!!) maxI = j
        }
        when {
            hasNull || minI == -1 -> out.add(null)
            minI <= maxI -> { out.add(values[minI]); out.add(values[maxI]) }
            else -> { out.add(values[maxI]); out.add(values[minI]) }
        }
        start = end
        if (start >= n) break
    }
    out.add(values.last())
    return out
}

/**
 * 单条折线的 Path 构建（从 draw lambda 外移到组合期的版本，几何口径与旧实现逐字一致）：
 * x 按索引均分铺满绘图区，y 按轴量程线性映射，null/NaN 断线（moveTo 重新起笔）。
 * 点数不足 2 返回空 Path（drawPath 空路径 = 无操作）。
 */
private fun buildChartLinePath(
    values: List<Double?>,
    plotLeft: Float,
    plotWidth: Float,
    plotHeight: Float,
    range: ClosedFloatingPointRange<Double>,
): Path {
    val count = values.size
    if (count < 2) return Path()
    val span = range.endInclusive - range.start
    val path = Path()
    var drawing = false
    values.forEachIndexed { i, v ->
        if (v == null || v.isNaN()) {
            drawing = false
            return@forEachIndexed
        }
        val px = plotLeft + plotWidth * i / (count - 1)
        val py = (plotHeight * (1 - ((v - range.start) / span))).toFloat()
        if (drawing) path.lineTo(px, py) else path.moveTo(px, py)
        drawing = true
    }
    return path
}

/** FPS 卡右轴可切换序列（Kite 同款：电量 / 温度 / CPU Load / GPU Load） */
private class RightSeriesOption(
    val label: String,
    val range: ClosedFloatingPointRange<Double>,
    val color: Color,
    val values: List<Double?>,
)

/**
 * 帧率与温度卡：**双轴叠加 + 右轴可切换**（Kite 同款版式，2026-09-25）——
 * FPS 折线走左轴（灰，0 → 设备铺满刷新率），右轴在电量 / 温度 / CPU Load(%) / GPU Load(%) 间切换：
 * 点右上角 Refresh 钮循环轮换，或直接点底部图例选中（选中高亮、其余压暗，Kite 同款）。
 * 右轴值域随选项走（电量 / CPU / GPU 0-100，温度 0-50，右轴 5 档刻度）；缺测秒断线。
 * ⚠️ 整场无数据的候选自动不进切换列表（GPU Load 在本机 HyperOS 被 SELinux 拦 / 旧会话缺列）。
 */
/**
 * 帧率与温度卡：**双轴叠加 + 右轴可切换**（Kite 同款版式，2026-09-25）——
 * FPS 折线走左轴（灰，0 → 设备铺满刷新率），右轴在电量 / 温度 / CPU Load(%) / GPU Load(%) 间切换：
 * 点右上角 Refresh 钮循环轮换，或直接点底部图例选中（选中高亮、其余压暗，Kite 同款）。
 * 右轴值域随选项走（电量 / CPU / GPU 0-100，温度 0-50，右轴 5 档刻度）；缺测秒断线。
 * ⚠️ 整场无数据的候选自动不进切换列表（GPU Load 在本机 HyperOS 被 SELinux 拦 / 旧会话缺列）。
 * ⚠️ FPS 线数据源（2026-09-27 4Hz 化）：新会话吃 250ms 子拍差分点（Scene 式密度、短谷可见），
 * 旧会话回退 1s 样本；右轴序列保持 1s 粒度、按索引比例重采样对齐子拍网格。
 */
@Composable
private fun FrameFpsTempCard(
    samples: List<FrameSample>,
    fpsPoints: List<FrameFpsSampleEntity>,
    fpsAxisMax: Double,
    shape: RoundedCornerShape,
) {
    // 右轴候选按**数据自适应**：整场一条数据都没有的选项不进切换列表 ——
    // GPU Load 在 24031PN0DC / HyperOS 上 shell 身份对全部 GPU busy 节点拒绝（kgsl、
    // /sys/kernel/gpu 两大目录 SELinux 拦截，2026-09-27 复测含 devfreq / tracefs / dumpsys
    // 全家无收获），Shizuku 模式恒空 → 自动隐藏；root 模式或节点可读的 ROM 照常出现。
    // 旧会话（v7 前）的 Battery/CPU 同理。TEMP 恒在（v1 起就有电池温度），保底不为空。
    // ⚠️ 文案在组合上下文先解析再进 remember（stringResource 不能在 remember 块里调）
    val batteryLabel = stringResource(R.string.frame_card_battery)
    val tempLabel = stringResource(R.string.frame_card_temp)
    val cpuLoadLabel = stringResource(R.string.frame_right_cpu_load)
    val gpuLoadLabel = stringResource(R.string.frame_right_gpu_load)
    // FPS 线数据源：新会话吃 250ms 子拍点（短谷可见），旧会话回退 1s 样本。
    // 右轴序列是 1s 粒度 —— 索引制图要求两条线等长，按索引比例最近邻把 1s 值重采样到
    // 子拍点数（1s 值在 4Hz 网格上呈台阶、形状不变）
    val hasFpsPoints = fpsPoints.isNotEmpty()
    // fpsVals remember（2026-09-28 滑动卡顿修复）：与 rightOptions 同 key（samples/fpsPoints），
    // 派生列表引用稳定，下游 lines → FrameLineChart 的 Path 缓存不因重组失效
    val fpsVals: List<Double?> = remember(samples, fpsPoints) {
        if (fpsPoints.isNotEmpty()) fpsPoints.map { it.fps } else samples.map { it.fps }
    }
    fun List<Double?>.toFpsGrid(): List<Double?> {
        if (!hasFpsPoints || size == fpsVals.size) return this
        val n = fpsVals.size
        return List(n) { i -> this[((i.toLong() * size) / n).toInt().coerceAtMost(size - 1)] }
    }
    val rightOptions = remember(samples, fpsPoints, batteryLabel, tempLabel, cpuLoadLabel, gpuLoadLabel) {
        listOfNotNull(
            RightSeriesOption(batteryLabel, 0.0..100.0, CapacityBlue, samples.map { it.capacityPct }.toFpsGrid())
                .takeIf { o -> o.values.any { it != null } },
            RightSeriesOption(tempLabel, 0.0..50.0, TempOrange, samples.map { it.tempBatteryC }.toFpsGrid()),
            RightSeriesOption(cpuLoadLabel, 0.0..100.0, CpuLoadPink, samples.map { it.cpuUsagePct }.toFpsGrid())
                .takeIf { o -> o.values.any { it != null } },
            RightSeriesOption(gpuLoadLabel, 0.0..100.0, GpuLoadBlue, samples.map { it.gpuLoadPct }.toFpsGrid())
                .takeIf { o -> o.values.any { it != null } },
        )
    }
    var rightIdx by remember { mutableStateOf(0) }
    val selected = rightOptions[rightIdx.coerceAtMost(rightOptions.lastIndex)]
    val fpsLineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val durationMs = samples.last().timeMillis - samples.first().timeMillis

    AppCard(
        shape = shape,
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
            val fpsLines = remember(fpsVals, selected, fpsLineColor) {
                listOf(
                    FrameLine(fpsVals, fpsLineColor),
                    FrameLine(selected.values, selected.color, onRight = true),
                )
            }
            FrameLineChart(
                lines = fpsLines,
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
    // powerW remember（2026-09-28 滑动卡顿修复）：派生列表引用稳定，下游 lines →
    // FrameLineChart 的 Path 缓存不因重组失效
    val powerW = remember(samples) { samples.map { it.powerMw?.let { mw -> -mw / 1_000.0 } } }
    val powerVals = powerW.filterNotNull()
    val pMax = ceil(powerVals.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    // 左轴整洁多行刻度（2026-09-27 用户指 Kite 截图要求）：轴顶 = 步长整数倍、每行标签
    // 都是整洁值（见 [nicePowerAxis]）；曲线改按轴顶归一化（Kite 同款：轴顶略高于峰值）
    val (axisStep, leftTicks) = nicePowerAxis(pMax)
    val axisMax = leftTicks * axisStep
    val pMin = powerVals.minOrNull()
    val avg = powerVals.takeIf { it.isNotEmpty() }?.average()
    val durationMs = samples.last().timeMillis - samples.first().timeMillis

    AppCard(
        shape = shape,
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
                Text(
                    stringResource(R.string.frame_card_power),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.frame_card_capacity_pct), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            val (startInset, endInset) =
                rememberChartInsets(0.0..axisMax, 0.0..100.0, leftTickCount = leftTicks)
            val powerLines = remember(powerW, samples, axisMax) {
                listOf(
                    FrameLine(powerW, PowerBlue),
                    FrameLine(samples.map { it.capacityPct }, CapacityBlue, onRight = true),
                )
            }
            FrameLineChart(
                lines = powerLines,
                leftRange = 0.0..axisMax,
                rightRange = 0.0..100.0,
                leftTickCount = leftTicks,
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
                JankLegendDot(PowerBlue, stringResource(R.string.frame_card_power_name))
                Spacer(Modifier.width(14.dp))
                JankLegendDot(CapacityBlue, stringResource(R.string.frame_card_capacity))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "MAX: ${pMax.f2()}W MIN: ${pMin?.f2() ?: "—"}W AVG: ${avg?.f2() ?: "—"}W",
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

    AppCard(
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 图表列不再吃横向 padding（同 Power 卡：轴标签贴边、绘图区加宽），标题行保留 16dp
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.frame_card_temperature),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            val (startInset, endInset) = rememberChartInsets(0.0..50.0, null)
            val tempLines = remember(samples) {
                listOf(
                    FrameLine(samples.map { it.tempVirtualC }, CpuTempBlue),
                    FrameLine(samples.map { it.gpuTempC }, GpuTempPurple),
                    FrameLine(samples.map { it.tempBatteryC }, TempOrange),
                )
            }
            FrameLineChart(
                lines = tempLines,
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

/**
 * 多线共轴折线卡的一条序列：label 进图例，values 的 null/NaN 断线不画。
 * [underlays] = 画在本序列**下方**的从属细线（CPU 两卡：簇内逐核的淡色曲线，
 * 不进图例、可见性跟主序列走 —— 图例开关的是整簇）。颜色传之前先 copy(alpha) 调淡。
 */
private class FrameSeries(
    val label: String,
    val color: Color,
    val values: List<Double?>,
    val underlays: List<FrameSeries> = emptyList(),
)

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
 * 簇内**逐核**细线（2026-09-27 加，用户反馈"CPU 2~4 不止一个核，为什么只有一根线"）：
 * 簇均值线只剩"平均后的一根"，单核被拉满/空转的差异全被抹掉。每核一条淡色细线画在
 * 均值线**下方**（同簇色 copy(alpha=0.35)、3f 线宽），均值粗线压在上面做引导。
 * 单核簇返回空：主簇线本身就是那颗核，再画一遍只是把线加粗。
 */
private fun coreUnderlays(
    points: List<CpuPoint>,
    cores: IntArray,
    color: Color,
    valueOf: (CpuPoint, Int) -> Double?,
): List<FrameSeries> =
    if (cores.size <= 1) emptyList()
    else cores.map { core ->
        FrameSeries("CPU $core", color.copy(alpha = 0.35f), points.map { valueOf(it, core) })
    }

/**
 * CPU Usage(%) 卡：Total（全核合计）+ 四条分簇线，全部 0-100 共轴。
 * 每条簇线下方叠簇内逐核的淡色细线（多核簇才有；图例开关整簇）。
 * 数据源 = 250ms 快样（旧会话回退 1s 样本，只剩 Total 一条线）；逐核 / Total
 * 全缺的会话整卡隐藏。
 */
@Composable
private fun FrameCpuUsageCard(points: List<CpuPoint>, shape: RoundedCornerShape) {
    // ⚠️ 聚合进 remember(points)（2026-09-28 滑动卡顿修复）：旧实现每次重组都重跑
    // 11 遍全量扫描（4 簇均值 + 7 条逐核细线）并新建 12 个 2400 元素列表 —— 参数
    // List 不稳定导致卡片不可跳过重组，父级任何波及都会整段重算
    val series = remember(points) {
        buildList {
            add(FrameSeries("Total", CapacityBlue, points.map { it.totalPct }))
            CpuClusters.forEachIndexed { i, (label, cores) ->
                val color = CpuClusterColors[i]
                add(
                    FrameSeries(
                        label,
                        color,
                        clusterUsagePct(points, cores),
                        underlays = coreUnderlays(points, cores, color) { p, c -> p.corePct.getOrNull(c) },
                    ),
                )
            }
        }
    }
    FrameCpuMultiLineCard(stringResource(R.string.frame_card_cpu_usage), series, 0.0..100.0, points, shape)
    SideEffect { ChartPerf.tick("cpuUsage.recompose") }
}

/**
 * CPU Frequency(MHz) 卡：四条分簇频率线（按簇聚合）+ 簇内逐核淡色细线。
 * y 轴 0 → 本场峰值向上取整到 300MHz 档（Kite 同款"顶格 = 实测峰值"的观感）；
 * ⚠️ 峰值必须把逐核细线算进去：单核升挡可以高出簇均值一大截，只按均值算顶格会被裁掉。
 */
@Composable
private fun FrameCpuFreqCard(points: List<CpuPoint>, shape: RoundedCornerShape) {
    // ⚠️ 聚合 + 峰值进 remember(points)（2026-09-28 滑动卡顿修复，口径同 Usage 卡）：
    // peak 必须把逐核细线算进去（单核升挡可以高出簇均值一大截，只按均值算顶格会被裁掉）
    val series = remember(points) {
        CpuClusters.mapIndexed { i, (label, cores) ->
            val color = CpuClusterColors[i]
            FrameSeries(
                label,
                color,
                clusterMhz(points, cores),
                underlays = coreUnderlays(points, cores, color) { p, c ->
                    p.mhz.getOrNull(c)?.takeIf { m -> m > 0.0 }
                },
            )
        }
    }
    val yMax = remember(series) {
        val peak = series.flatMap { it.underlays + it }.flatMap { it.values }
            .filterNotNull().maxOrNull() ?: 0.0
        (ceil(peak / 300.0) * 300.0).coerceAtLeast(300.0)
    }
    FrameCpuMultiLineCard(stringResource(R.string.frame_card_cpu_frequency), series, 0.0..yMax, points, shape)
    SideEffect { ChartPerf.tick("cpuFreq.recompose") }
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
    // visible / lines 链条 remember（2026-09-28 滑动卡顿修复）：series 引用稳定后，
    // visible 只在图例开合（hidden 变化）时重建，下游 FrameLineChart 的 Path 缓存
    // （remember(lines)）才不会因重组波及而失效
    val visible = remember(series, hidden) { series.withIndex().filter { it.index !in hidden } }
    val durationMs = points.last().timeMillis - points.first().timeMillis
    SideEffect { ChartPerf.tick("cpuMultiLine.recompose") }
    AppCard(
        shape = shape,
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
                // 画序 = 叠放序：先各可见序列的逐核细线（淡色、下层），再簇均值主线压上
                val chartLines = remember(visible) {
                    visible.flatMap { (_, s) ->
                        s.underlays.map { FrameLine(it.values, it.color, strokeWidth = 3f) } +
                            FrameLine(s.values, s.color)
                    }
                }
                FrameLineChart(
                    lines = chartLines,
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
 * y 轴整步长 ≤7 档（[barAxisStep]），柱高按轴顶自适应（轴顶 = 步长整数倍 ≥ 本场峰值）。
 */
@Composable
private fun FrameTimeCard(samples: List<FrameSample>, shape: RoundedCornerShape) {
    val ftVals = samples.map { it.frameSpaceMs }
    val maxFt = (ftVals.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    // y 轴整洁刻度（2026-09-27 用户报"最上面的 13 乱糟糟"）：旧版 yMax=峰值、12 档均分 ——
    // 峰值非整数时档值取整出 13/12/11/9/8/7…跳档序列；且 150dp 高塞 11 格（13.6dp/格）
    // 让顶档标签（画在线下方）与次档（画在线上方）几乎完全叠印成乱码。改整步长轴
    // （1/2/5×10ⁿ 中档数 ≤7 的最小解）：轴顶 = 档数×步长 ≥ 峰值，每档都是整洁值、
    // 行距 ≥21dp 不叠印（口径同 Power 卡左轴）
    val (ftStep, ftTicks) = barAxisStep(maxFt)
    val ftAxisMax = ftTicks * ftStep
    val variance =
        if (ftVals.size >= 2) {
            val avg = ftVals.average()
            ftVals.sumOf { (it - avg) * (it - avg) } / ftVals.size
        } else {
            null
        }
    val barColor = MaterialTheme.colorScheme.primary
    AppCard(
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 2026-09-27 用户拍板多语言化（此前"Kite 原文不译"的口径作废）
            Text(stringResource(R.string.frame_card_frame_time), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            FrameBarChart(
                values = ftVals,
                barColors = List(ftVals.size) { barColor },
                yMax = ftAxisMax,
                yTickCount = ftTicks + 1,
                durationMs = samples.last().timeMillis - samples.first().timeMillis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "MAX: ${maxFt.f2()}ms  " +
                    stringResource(R.string.frame_variance) + ": " + (variance?.f2() ?: "—"),
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

    AppCard(
        shape = shape,
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
                // 档步长（yMax 恒为步长整数倍，见各调用方的 barAxisStep/固定档）：
                // <1 走 tickText 的一位小数（低峰值会话 0.5 档不与整数档重档）
                val step = yMax / (yTickCount - 1)
                for (i in 0 until yTickCount) {
                    val frac = i / (yTickCount - 1).toFloat()
                    val y = size.height * frac
                    drawLine(gridColor, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = 1f)
                    if (i < yTickCount - 1) {
                        val v = yMax * (1 - frac)
                        // 标签垂直居中在横线上（baseline = y + textSize/3，口径同 FrameLineChart
                        // 左轴）—— 2026-09-27 用户报"数值没对齐横线"：旧版顶档画在线下方、
                        // 其余画在线上方 4dp，同一张图错落不齐。Compose 绘制默认不裁剪，
                        // 顶档字形上缘探出画布顶 ~3dp 落进上方空白，无碍
                        drawContext.canvas.nativeCanvas.drawText(
                            tickText(v, step),
                            plotLeft - 6.dp.toPx(),
                            y + labelPaint.textSize / 3,
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

/** 左轴刻度文本（与 FrameLineChart 的画法同源，inset 计算不能和绘制各编一套）；[tickCount] = 等分数 */
private fun leftTickTexts(r: ClosedFloatingPointRange<Double>, tickCount: Int = 4): List<String> {
    val step = (r.endInclusive - r.start) / tickCount
    return (1..tickCount).map { tickText(r.start + (r.endInclusive - r.start) * it / tickCount, step) }
}

/**
 * Power 卡左轴的「整洁步长」：目标 ≈7 行网格（2026-09-27 用户指 Kite 截图要求），从
 * 1/2/5×10^n 序列里选**行数最接近 7** 的整步长（同分取更小步长 = 行更密），返回
 * (步长, 行数)。轴顶 = 行数×步长 ≥ 数据峰值，每行标签都是整洁值 —— 0..14 → 步 2 共
 * 7 行（2/4/…/14，与 Kite 截图逐档一致）；不再 4 等分把 3.5/10.5 取整成 4/11 的错行观感。
 */
private fun nicePowerAxis(span: Double): Pair<Double, Int> {
    var bestStep = span / 7.0
    var bestTicks = 7
    var bestScore = Double.MAX_VALUE
    var mag = 0.1
    while (mag <= 100_000.0) {
        for (f in listOf(1.0, 2.0, 5.0)) {
            val step = mag * f
            val ticks = ceil(span / step).toInt()
            if (ticks < 2) continue
            val score = abs(ticks - 7).toDouble()
            if (score < bestScore || (score == bestScore && step < bestStep)) {
                bestScore = score
                bestStep = step
                bestTicks = ticks
            }
        }
        mag *= 10
    }
    return bestStep to bestTicks
}

/**
 * 柱状卡（Frame Time）左轴的「整步长 ≤7 档」解：1/2/5×10ⁿ 序列里**档数 ≤7 的最小步长**，
 * 返回 (步长, 档数)，轴顶 = 档数×步长 ≥ 峰值。与 [nicePowerAxis]（折线卡目标 ≈7 档、
 * 同分取行更密）的差异：柱状卡绘图区矮（150dp）且顶档标签画在线下方、次档画在线上方，
 * 行距 <15dp 时两个标签叠印 —— ≤7 档保证行距 ≥21dp 恒不叠印。
 * 峰值 13.4ms → 步 2 共 7 档（轴顶 14：2/4/…/14）；16.7ms → 步 5 共 4 档（轴顶 20）。
 */
private fun barAxisStep(span: Double): Pair<Double, Int> {
    var mag = 0.1
    while (mag <= 100_000.0) {
        for (f in listOf(1.0, 2.0, 5.0)) {
            val step = mag * f
            val ticks = ceil(span / step).toInt()
            if (ticks in 2..7) return step to ticks
        }
        mag *= 10
    }
    return span to 2  // 兜底（span > 70 万才可达）：轴顶 2×span，档值仍随 tickText 取整
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
    leftTickCount: Int = 4,
): Pair<Dp, Dp> {
    val density = LocalDensity.current
    return remember(leftRange, rightRange, rightTickCount, leftTickCount, density) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 10.sp.toPx() }
        }
        val widestLeft = leftRange
            ?.let { r -> leftTickTexts(r, leftTickCount).maxOf { paint.measureText(it) } }
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
        val step = yMax / (yTickCount - 1)
        for (i in 0 until yTickCount - 1) {
            val v = yMax * (1 - i.toFloat() / (yTickCount - 1))
            maxW = maxOf(maxW, paint.measureText(tickText(v, step)))
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
