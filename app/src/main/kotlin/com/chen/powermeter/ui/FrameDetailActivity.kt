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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * ⚠️ 临时排查工具（2026-09-28 横屏滑动卡顿问题，定位后移除）：轻量计数器。指标含义：
 * - `*.recompose` = 该 Composable 重组频率（SideEffect 计数）；滑动中持续增长 = 有重组波及；
 * - `lineChart.draw` = FrameLineChart 绘制指令重录频率；滑动中逼近帧率 = 每帧重绘（有失效源）；
 * - `lineChart.buildPaths` = Path 预构建重建事件（期望：仅旋转/数据变化时出现）。
 * 输出行首 [H]/[V] = 横/竖屏（ChartPerf.orientation 由 Activity 维护）。
 * tick/timed 仅主线程调用（HashMap 未加锁）；logBuild 自 2026-09-30 起也在位图后台构建
 * 线程调用（只碰 Log 与 orientation 读取，无 Map 访问，线程无关）。
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
 * **转场形态**（2026-09-30 试验：进/退场均一镜到底，独立 commit 可整体 revert）：列表
 * 卡片点击时 [AppTransitions.register] 锚点 → [AppTransitions.capture] 截图 → 本页
 * [launch] 经 [AppTransitions.launchWithTransform] 登记 Handoff 并压制主题窗口滑动，
 * 进场从卡片矩形四向撑开（expandOnEnter=true 默认）；返回整页收回卡片矩形后 finish。
 * 历史：2026-09-27 曾因大场次整页图表卡首帧组合重、ClipReveal 展开要求首帧整页就绪
 * 会卡，拆成"进场侧边滑入 + 退场才收拢"（launchWithCollapseBack + expandOnEnter=false）；
 * 本轮试验恢复进场展开，观感不佳时 revert 该 commit 即回到滑入形态。展开动画延后到
 * 页面内容首帧画完才起跑（waitForContentReady 闸门，2026-09-30 二轮：图表首帧构建与
 * 展开同帧抢 UI 线程卡顿，等待期窗口停在卡片矩形 = 观感停在列表）。
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
         * 打开详情页。有 [capture]（列表卡片截图 + 窗口矩形）→ 登记 Handoff 并压制窗口
         * 滑动，进场/退场均走 ClipReveal 一镜到底（见类注释）；null（截图失败等）→
         * 普通启动（无收拢，主题窗口动画）。
         */
        fun launch(context: Context, sessionId: Long, capture: AppTransitions.Capture? = null) {
            val intent = Intent(context, FrameDetailActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            val act = context as? Activity
            if (capture != null && act != null) {
                AppTransitions.launchWithTransform(act, intent, capture)
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
                // 数据就绪闸门（2026-09-28 修进场"闪一下才加载好图表"）：读库走
                // LaunchedEffect，首帧组合必然先于它完成 —— 期间 session=null，详情页会先
                // 画一帧「not-found 空态」再整页换成图表卡，这个突变即用户看到的闪变。
                // 加载中不渲染任何占位（半透明窗口透出底下列表）；就绪后内容淡入；读库
                // 完成且确无记录才显示 not-found 文案（区分「还在加载」与「真没这条记录」）
                var dataReady by remember { mutableStateOf(false) }
                // 只在 sessionId 变化时读一次；旋转 / 深浅色切换不重建本 Activity 之外的
                // 情形（configChanges 与主页同口径）本就不会走到这里
                LaunchedEffect(sessionId) {
                    if (sessionId == NO_ID) {
                        // 缺 / 非法 ID = 必然查无此记录，直接进 not-found（不能停在加载态）
                        dataReady = true
                        return@LaunchedEffect
                    }
                    // 预热命中（列表卡片按压时 FrameDetailPreheat 已在后台读好）→ 直接落地，
                    // 读库等待从进场关键路径上整段消失；未命中走原读库链路
                    val pre = FrameDetailPreheat.consume(sessionId)
                    if (pre != null) {
                        session = pre.session
                        samples = pre.samples
                        cpuPoints = pre.cpuPoints
                        fpsPoints = pre.fpsPoints
                        dataReady = true
                        return@LaunchedEffect
                    }
                    val loaded = loadDetailData(this@FrameDetailActivity, sessionId)
                    session = loaded.session
                    samples = loaded.samples
                    cpuPoints = loaded.cpuPoints
                    fpsPoints = loaded.fpsPoints
                    dataReady = true
                }

                // 进场转场放行（2026-09-30 试验批次二）：整页十几张图表卡的位图在首帧
                // remember 里同步构建（lineChart.buildPaths），展开动画若与它同帧抢 UI
                // 线程 = 全程卡顿。dataReady 后等两帧 —— 第一帧 = 内容组合+布局+绘制的
                // 重帧（等待期窗口裁剪停在卡片矩形，屏幕观感停在列表）、第二帧起都是轻帧
                // —— 再通知 AppTransitions 启动展开。无转场 Handoff（通知/过期启动）时
                // notify 是 no-op。
                LaunchedEffect(dataReady) {
                    if (!dataReady) return@LaunchedEffect
                    withFrameNanos { }
                    withFrameNanos { }
                    AppTransitions.notifyPageReady(this@FrameDetailActivity)
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
                        dataReady = dataReady,
                        onBack = { finish() },
                        onShare = { s, sm, fp, cp -> shareSession(s, sm, fp, cp) },
                    )
                }
            }
        }

        // 窗口开/关转场双压 0（2026-09-30 试验：进场恢复一镜到底）：进场展开由
        // installWindowTransform 首帧 PreDraw 手动播放，主题 activityOpen* 侧边滑入
        // 若不压会与窗口内 ClipReveal 展开叠播（批次三十一 v1 失败根因之一，源侧已在
        // launchWithTransform 里 overridePendingTransition(0,0)）；退场同理由收拢动画
        // 接管，activityClose* 的滑出会把收拢末帧整窗滑出。API 34+ 在此注册；更早版本
        // 由 overridePendingTransition / CollapseHost.finishNow 兜底。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
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
            // 状态在这里落地，闸门在 onResume 之后的首帧触发（宿主必已 RESUMED）→ 播
            // 圆孔揭露动画（2026-09-29 用户定案：后台错过的变化回前台也播动画，不再
            // 静默硬切；离开前的旧主题帧正好是揭露的起点画面）
            darkThemeState.value = night
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !night)
            AppTransitions.onThemeChangedForSnapshot(this, night)
        }
    }

    /**
     * 跨 Activity 转场装配（SportLink DeviceManageActivity / 本项目 TrendFullscreenActivity
     * 同款时机）：有新鲜 Handoff（从列表卡片点进）→ 页面根包进 ClipRevealLayout，进场
     * 首帧从卡片矩形四向撑开、返回整页收拢（expandOnEnter 默认 true，2026-09-30 试验
     * 恢复）；waitForContentReady=true = 展开动画延后到内容首帧画完才起跑（图表页首帧
     * 重组极重，等待期观感停在列表，见 setContent 里的 notifyPageReady）；无（Handoff
     * 过期/截图失败）→ no-op，页面普通显示。
     */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        AppTransitions.installWindowTransform(this, waitForContentReady = true)
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
            // 送达时本页可能还没 onStart（后台错过的变化随返回事务补发）：不在这里判
            // 静默（2026-09-29 用户定案，同 MainActivity）——由 PowerMeterTheme 闸门在
            // 首帧判定：宿主已 RESUMED → 播圆孔揭露；仍 paused 可见 → 闸门静默落地
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
internal class DetailData(
    val session: FrameSession?,
    val samples: List<FrameSample>,
    val cpuPoints: List<CpuPoint>,
    val fpsPoints: List<FrameFpsSampleEntity>,
)

/**
 * 详情页四件套读库（内部已切 IO）。[FrameDetailPreheat] 与 Activity 的 LaunchedEffect
 * 走同一条链，口径永远一致（实体映射 / CPU 快样回退 / 子拍点读取都在这一处）。
 */
internal suspend fun loadDetailData(context: Context, sessionId: Long): DetailData =
    withContext(Dispatchers.IO) {
        val dao = FrameDatabase.getInstance(context).frameDao()
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

/**
 * 详情页数据预热（2026-09-30 治展开动画与首帧构建抢 UI 线程的「挪时间窗」半边）：
 * 列表卡片**按压**（ACTION_DOWN，比 click 提前一整个抬手）就在后台预读该场次四件套，
 * 详情页首帧组合时 [consume] 命中即用 —— 读库等待从进场关键路径上整段消失，dataReady
 * 闸门提前放行、展开动画更早起跑。按压 → capture（整窗截图）→ Activity 启动 → 首帧组合
 * 的窗口（~100-300ms）正好盖住读库耗时。
 *
 * 单槽位 + 请求序号：连续按压/换卡时只有**最后一次** warm 的结果落地；consume 一次性
 * 取走。场次数据落库后不可变（录制停止时一次性写入），缓存不存在陈旧问题。
 */
internal object FrameDetailPreheat {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var warmed: Pair<Long, DetailData>? = null
    private var warmSeq = 0L

    fun warm(context: Context, sessionId: Long) {
        val seq = synchronized(lock) {
            if (warmed?.first == sessionId) return
            ++warmSeq
        }
        scope.launch {
            val data = try {
                loadDetailData(context.applicationContext, sessionId)
            } catch (e: Exception) {
                Log.w(TAG, "detail preheat session=$sessionId failed: ${e.message}")
                null
            }
            // 查无此记录不缓存；过期请求（期间又按了别的卡）不落地
            if (data == null || data.session == null) return@launch
            synchronized(lock) { if (seq == warmSeq) warmed = sessionId to data }
        }
    }

    /** 取走预热数据（仅 sessionId 匹配时）；详情页主线程在 LaunchedEffect 里调用 */
    fun consume(sessionId: Long): DetailData? = synchronized(lock) {
        warmed?.takeIf { it.first == sessionId }?.also { warmed = null }?.second
    }
}

@Composable
private fun FrameDetailScreen(
    session: FrameSession?,
    samples: List<FrameSample>,
    /** CPU 两卡的绘图点（250ms 快样，旧会话回退 1s 样本 —— 见 [CpuPoint]） */
    cpuPoints: List<CpuPoint>,
    /** FPS 曲线的绘图点（250ms 子拍差分，旧会话回退 1s 样本，见 [FrameFpsTempCard]） */
    fpsPoints: List<FrameFpsSampleEntity>,
    /** 读库是否完成（2026-09-28 加载态区分：未就绪不画 not-found 占位，见 Activity 侧说明） */
    dataReady: Boolean,
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
    // 内容淡入（2026-09-28 修进场"闪一下"）：读库在首帧组合之后才完成，session 从
    // null→有值时整页图表卡首次组合（大场次组合重），硬切上屏就是闪变 —— 内容 alpha
    // 0→1 把这次上屏藏进过渡。⚠️ 本常量必须挂在函数体顶层（session 未就绪时就组合），
    // 放进 else 分支的话首次组合即达目标值 1f，不会有动画。仅 null→有值 动一次，之后
    // 恒为 1f，滚动 / 深浅色切换不重播
    val contentAlpha by animateFloatAsState(
        targetValue = if (session != null) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "frameDetailContentAlpha",
    )
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
                    // 加载中（dataReady=false）不画任何东西：半透明窗口透出底下列表，比
                    // 一帧假 not-found 干净；读库完成且确无记录才显示占位 —— 空白页上文字
                    // 浮现，没有"内容被替换"的突变（2026-09-28）
                    if (dataReady) {
                        Spacer(Modifier.height(48.dp))
                        Text(
                            stringResource(R.string.frame_not_found),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 数据卡整体走 contentAlpha 淡入（见函数体顶层说明）；内层 Column 保持
                    // 与外层相同的 12dp 卡间距，包一层只为挂 alpha
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .alpha(contentAlpha),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 2026-09-25 重构：按 Kite 报告版式重排（不再照搬电池查看页的卡片区）——
                        // 顶部统计网格 → 帧率与温度（双轴叠加）→ Frame Time → Jank → Power
                        // → Temperature → CPU Usage → CPU Frequency（后两卡 2026-09-25 追加置底）
                        // 逐帧卡顿/稳帧统计一次算好三卡共用（旧会话为 null，卡内各自回退旧口径）
                        val frameStats = remember(samples) { frameJankStats(samples) }
                        FrameSummaryCard(session, samples, frameStats, cardShape)
                        if (samples.size >= 2) {
                            FrameFpsTempCard(samples, fpsPoints, fpsAxisMax, cardShape)
                            FrameTimeCard(samples, frameStats, cardShape)
                            FrameJankCard(samples, frameStats, cardShape)
                            FramePowerCard(samples, cardShape)
                            // Temperature 卡按数据显隐：CPU/GPU 温度取 thermal_zone 温感区
                            // （需 root/Shizuku，非 root 机型整场缺失）；2026-09-29 起 VIR 线
                            // （Kite virTemp 口径）渲染期从电池温度现算、旧会话照常出线 →
                            // 四线任一有数据即显示，全无才隐藏
                            if (samples.any {
                                    it.tempVirtualC != null || it.gpuTempC != null || it.tempBatteryC != null
                                }
                            ) {
                                FrameTempCard(samples, cardShape)
                            }
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
                    // 标题随数据 Crossfade（2026-09-28）：加载中是回退页名，就绪瞬间若硬切
                    // 成应用名+时间戳副标题，与内容闪变同源 —— 一并用淡入抹平
                    Crossfade(
                        targetState = session,
                        animationSpec = tween(durationMillis = 220),
                        label = "frameDetailTitle",
                    ) { s ->
                        Column {
                            Text(
                                s?.appLabel ?: stringResource(R.string.mode_frame),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            if (s != null) {
                                Text(
                                    stringResource(
                                        R.string.frame_captured_at,
                                        formatFrameStamp(s.startTime),
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = DetailNumericFont,
                                )
                            }
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

/** 稳帧指数 ⓘ 弹窗的口径文案（卡顿判定同理见 [FrameJankCard]）；[open] 透传 GlassDialog 按压预备 */
@Composable
private fun FrameInfoDialog(title: String, body: String, onDismiss: () -> Unit, open: Boolean = true) {
    GlassDialog(
        onDismissRequest = onDismiss,
        open = open,
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

// ── 逐帧卡顿判定（2026-09-28 重构，PerfDog 式口径回到**单帧**粒度）──────────
//
// ⚠️ 旧口径的事故（Jank 卡三档全零，用户报障）：PerfDog 的 83/125ms 是**单帧耗时**
// 门槛（一帧 83ms ≈ 卡满 5 个 60Hz vsync），此前套在 **1s 平均帧时间**上没换算聚合
// 粒度 —— 「整秒均值 > 83ms」意味着该秒平均帧率 < 12fps，正常录制永不触发。2026-09-28
// 王者实测一整场 1s 均值 max=13.31ms（门槛的 16%）、小卡顿门槛（2× 均值 = 16ms）也
// 够不着，SMALL/JANK/BIG JANK 全零；而同场 FPS 最低 62.8、累计 vsync 缺口 ~2257 帧
// —— 掉帧真实发生，判定粒度错配。
//
// 新口径：p2pHist（presentToPresent 差集分布）本身就是**当秒逐帧间隔**（桶 ms → 帧数），
// 判定回到帧：
// - 小卡顿：单帧 > 前 3 个有效秒平均帧时间 × 2（PerfDog 的相对条件；基线用**前 3 秒**
//   均值近似「前 3 帧均值」—— 直方图只有分布没有帧序，逐帧滑窗不可得；不用本秒自己的
//   均值，避免卡顿秒抬高自己的门槛）；
// - 卡顿：单帧 > 83ms（2× 电影帧耗时，24fps → 41.7ms）；
// - 严重卡顿：单帧 > 125ms（3× 电影帧耗时）。
// 每帧只计最高一档；图例 = 全场各档**帧数**；柱 = 该秒命中档位的最高档着色。
//
// ⚠️ ≥1s 的间隔按**呈现中断**处理（切页 / 空闲 / Surface 重建），不进任何一档、不进
// 卡顿率与稳帧指数的 Σms —— presentToPresent 把「停顿后恢复的那次呈现」记成巨大间隔，
// 那是没渲染，不是卡了一帧。
private const val JANK_FRAME_MS = 83.0
private const val BIG_JANK_FRAME_MS = 125.0
private const val SMALL_JANK_FACTOR = 2.0
private const val PRESENT_GAP_MS = 1_000

/**
 * 逐帧卡顿统计 —— Jank 卡 / 卡顿率 / 稳帧指数（逐帧口径）的统一数据源。
 * 三个耗时量（卡顿率分母、稳帧指数的 Σx/Σx²）都跳过 ≥[PRESENT_GAP_MS] 的中断桶。
 *
 * @return 全部样本都无 p2pHist（旧会话，frame.db v5 前落库）时为 null，调用方回退
 *   [jankTiers] / 1s 均值口径；同场内个别缺测秒（无基线 / 直方图被清）整秒跳过。
 */
private class FrameJankStats(
    /** 每秒最高档：0 无 / 1 小卡顿 / 2 卡顿 / 3 严重卡顿（柱着色用） */
    val tiers: List<Int>,
    /** 全场各档**帧数**（图例） */
    val smallFrames: Int,
    val jankFrames: Int,
    val bigFrames: Int,
    /** 卡顿率 % = (卡顿+严重卡顿帧的耗时 ms) ÷ 全部帧耗时 ms ×100；小卡顿不计（同旧口径） */
    val jankRatePct: Double,
    /** 逐帧帧时间标准差 ms（稳帧指数）与总体方差 ms²（Frame Time 卡，互为平方） */
    val frameTimeSd: Double,
    val frameTimeVar: Double,
)

private fun frameJankStats(samples: List<FrameSample>): FrameJankStats? {
    if (samples.none { it.p2pHist.isNotEmpty() }) return null
    val tiers = IntArray(samples.size)
    val baseline = ArrayDeque<Double>() // 前 3 个有效秒的（去中断）平均帧时间
    var small = 0
    var jank = 0
    var big = 0
    var jankMs = 0.0
    var totalMs = 0.0
    var sum = 0.0
    var sumSq = 0.0
    var frames = 0L
    for (i in samples.indices) {
        val hist = samples[i].p2pHist
        if (hist.isEmpty()) continue // 该秒缺测/无帧：不判定、不进基线（同旧口径）
        var secMs = 0.0
        var secFrames = 0L
        for ((ms, cnt) in hist) {
            if (ms >= PRESENT_GAP_MS) continue
            val m = ms.toDouble()
            secMs += m * cnt
            secFrames += cnt
            totalMs += m * cnt
            sum += m * cnt
            sumSq += m.toDouble() * m * cnt
            frames += cnt
            if (baseline.size >= 3 && m > SMALL_JANK_FACTOR * baseline.average()) {
                small += cnt.toInt()
                if (tiers[i] < 1) tiers[i] = 1
            }
            if (m > JANK_FRAME_MS) {
                jank += cnt.toInt()
                jankMs += m * cnt
                if (tiers[i] < 2) tiers[i] = 2
            }
            if (m > BIG_JANK_FRAME_MS) {
                big += cnt.toInt()
                tiers[i] = 3
            }
        }
        if (secFrames > 0L) {
            baseline.addLast(secMs / secFrames)
            if (baseline.size > 3) baseline.removeFirst()
        }
    }
    val mean = if (frames > 0L) sum / frames else 0.0
    // Σx²/n - mean² 的灾难性抵消可能出现微小负值，夹到 0
    val variance = if (frames > 0L) (sumSq / frames - mean * mean).coerceAtLeast(0.0) else 0.0
    return FrameJankStats(
        tiers = tiers.toList(),
        smallFrames = small,
        jankFrames = jank,
        bigFrames = big,
        jankRatePct = if (totalMs > 0.0) jankMs * 100.0 / totalMs else 0.0,
        frameTimeSd = sqrt(variance),
        frameTimeVar = variance,
    )
}

/**
 * p2pHist 分布 → **逐帧帧时间序列**（Scene 同款 Frame Time 渲染的数据源，2026-09-28）。
 *
 * 直方图只有「每秒 × 每桶」的多元集、没有帧序 —— 桶内按计数展开、桶间升序拼接。
 * 1s 内 ~115 帧摊在 ~3px 上，帧序在亚像素密度下不可辨（Scene 的逐帧条同样亚像素，
 * 可见形态只由各高度的**密度**决定，与帧顺序无关）。
 * ≥[PRESENT_GAP_MS] 的中断桶不展开（同 [frameJankStats] 口径：呈现中断不是帧）。
 */
private fun flattenFrameTimes(samples: List<FrameSample>): DoubleArray {
    var total = 0L
    val perSample = ArrayList<Pair<List<Pair<Int, Long>>, Long>>()
    for (s in samples) {
        val hist = s.p2pHist
        if (hist.isEmpty()) continue
        val valid = hist.filterKeys { it < PRESENT_GAP_MS }
        val n = valid.values.sum()
        if (n == 0L) continue
        perSample.add(valid.entries.sortedBy { it.key }.map { it.key to it.value } to n)
        total += n
    }
    val out = DoubleArray(total.toInt())
    var w = 0
    for ((flat, n) in perSample) {
        if (flat.size == 1) {
            val ms = flat[0].first.toDouble()
            for (k in 0 until n.toInt()) out[w++] = ms
        } else {
            for ((ms, cnt) in flat) {
                for (k in 0 until cnt.toInt()) out[w++] = ms.toDouble()
            }
        }
    }
    return out
}

/**
 * 卡顿判定 —— PerfDog 式口径在本应用 1s 采样粒度的移植（2026-09-25 按用户提供定义）：
 * - **卡顿**：帧时间 > 前 3 个有效秒均值 × 2，且 > 83ms（两倍电影帧耗时，24fps → 41.7ms）；
 * - **严重卡顿**：帧时间 > 前 3 秒均值 × 3，且 > 125ms；
 * - **小卡顿**：帧时间 > 前 3 秒均值 × 2，但未过 83ms 绝对门槛（纯相对尖峰）。
 * 每秒只计最高一档；前 3 秒基线不足 / 该秒无帧（frameSpace=0）不判定、不进基线。
 *
 * ⚠️ 粒度局限：样本是 1s 平均帧时间，单帧级尖刺会被整秒摊薄 —— 只有持续到秒级的
 * 卡顿才判得出来（忠实于数据，不假装有逐帧精度）。**2026-09-28 起降级为旧会话回退
 * 路径**：正口径见 [frameJankStats]（逐帧判定，事故记录在其上方）—— 本函数的绝对
 * 门槛套在秒均值上永不触发的问题即出自这里，保留只为 frame.db v5 前的旧会话（无
 * p2pHist 分布）仍能出图。
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
 * - AVG 功率为正数（2026-09-28 绝对值口径：库里只存大小不存符号，卡片不再出现负号，
 *   历史 v5 负数行由 frame.db v5→v6 迁移就地取 abs 纠正）；
 * - 帧能耗 = 平均功率 ÷ 平均帧率（mW/帧，Kite 同名指标的换算口径；功率恒正后无需再取 abs）；
 * - 卡顿率：**逐帧口径优先**（[frameJankStats]，PerfDog 卡顿率的帧耗时占比口径 =
 *   卡顿+严重卡顿帧的耗时 ÷ 全部帧耗时，小卡顿不计）——旧会话（无 p2pHist 分布）
 *   回退时长占比 = 卡顿（含严重卡顿）秒的时长 ÷ 会话总时长（判定见 [jankTiers]）；
 * - 稳帧指数 = **逐帧**帧时间标准差（ms），PerfDog 稳帧指数（Smooth）的简化口径，
 *   点 ⓘ 看定义 —— 旧会话回退 1s 均值的标准差（粒度局限见 [jankTiers] 注释）。
 */
@Composable
private fun FrameSummaryCard(
    session: FrameSession,
    samples: List<FrameSample>,
    frameStats: FrameJankStats?,
    shape: RoundedCornerShape,
) {
    val fpsVals = samples.map { it.fps }.filter { it > 0.0 }
    val fpsSd = stdev(fpsVals)
    val avgPowerMw = samples.mapNotNull { it.powerMw }.takeIf { it.isNotEmpty() }?.average()
    // 卡顿率：逐帧口径优先；旧会话回退时长占比
    val jankRate = frameStats?.jankRatePct ?: run {
        val tiers = jankTiers(samples)
        val spanMs = (samples.last().timeMillis - samples.first().timeMillis).coerceAtLeast(1L)
        val stutterMs = tiers.withIndex().sumOf { (i, tier) ->
            if (tier >= 2 && i > 0) {
                (samples[i].timeMillis - samples[i - 1].timeMillis).coerceAtLeast(0L)
            } else {
                0L
            }
        }
        stutterMs * 100.0 / spanMs
    }
    val energyPerFrameMw =
        if (avgPowerMw != null && session.avgFps > 0.0) avgPowerMw / session.avgFps else null
    val maxBatTemp = samples.mapNotNull { it.tempBatteryC }.maxOrNull()
    val ftSd = frameStats?.frameTimeSd
        ?: stdev(samples.map { it.frameSpaceMs }.filter { it > 0.0 })

    AppCard(
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
            var showSteadyInfo by remember { mutableStateOf(false) }
            // ⓘ 的 interactionSource 提升到此处：按压预备（rememberDialogPressArmed）要在
            // 按下的那一帧就把弹窗以 2% alpha 组合出来，管线首挂载开销全部付在按压帧
            val steadyInfoSource = remember { MutableInteractionSource() }
            val steadyInfoArmed = rememberDialogPressArmed(steadyInfoSource, showSteadyInfo)

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
                    onInfo = { showSteadyInfo = !showSteadyInfo },
                    onInfoSource = steadyInfoSource,
                )
                Spacer(Modifier.weight(1f))
            }
            // ⓘ 说明弹窗：⚠️ 不能包 AnimatedVisibility —— GlassDialog 是同窗口浮层、自带
            // dialogEnterAnim 入场动效；外层再对**全屏毛玻璃遮罩**做淡入缩放，等于每帧
            // 重渲染一次模糊层，过渡又卡又怪（2026-09-25 用户反馈）。显隐直接交 GlassDialog，
            // 再点一次图标即收起。
            // 按压预备（2026-09-29）：按住 ⓘ 期间即以 2% alpha 组合弹窗（管线首挂载/
            // 整页重测/首次模糊全付在按压帧，视觉零变化），松开 open=true 只播入场动画。
            if (steadyInfoArmed) {
                FrameInfoDialog(
                    open = showSteadyInfo,
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
 * [onInfoSource] 由调用方提升（配合 rememberDialogPressArmed 做按压预备）；不传时
 * ⓘ 内部自备（无预备，行为同旧版）。
 */
@Composable
private fun SummaryCell(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    onInfo: (() -> Unit)? = null,
    onInfoSource: MutableInteractionSource? = null,
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
                val fallbackSource = remember { MutableInteractionSource() }
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = stringResource(R.string.frame_steady_index_info),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(13.dp)
                        // 去水波纹：小图标上的 ripple 会溢出成方形闪烁，纯变色即可
                        .clickable(
                            interactionSource = onInfoSource ?: fallbackSource,
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
 * [strokeWidth] 线宽 px（默认 5f 主线规格；CPU 两卡的簇内逐核细线传更小值）；
 * [bridgeNullRun] 连片缺测 ≤ 该数时跨接连线（见 [buildChartLinePath]；0 = 缺测即断线，
 * 其余卡维持原口径，只有 FPS 线开——CPU 频率卡的 null = 核离线，桥接会画出假线）。
 */
private class FrameLine(
    val values: List<Double?>,
    val color: Color,
    val onRight: Boolean = false,
    val strokeWidth: Float = 5f,
    val bridgeNullRun: Int = 0,
)

/** FPS 曲线的缺测桥接阈值：连片 ≤3 拍（~4s）直接连线。timestats 图层跟踪 churn
 *  （statsd 拉 atom 清表 / 图层蒸发，09-27 审计定案）每 ~8s 打掉一拍差分，1s 节奏下
 *  这些单拍缺测画断线就是把正常渲染的线打成虚线；场景加载等长缺测段（>3 拍）仍断线。 */
private const val FPS_LINE_BRIDGE_NULL_RUN = 3

/** Power 卡折线蓝（Kite 同款亮蓝；固定色避免动态主题下与 Capacity 浅蓝难区分） */
private val PowerBlue = Color(0xFF2F7DE1)

private val CapacityBlue = Color(0xFF8FD0F4)

private val CpuTempBlue = Color(0xFF63B8EC)

private val GpuTempPurple = Color(0xFF9C7BE8)

/** VIR 虚拟温度线绿色：与 CPU 蓝 / GPU 紫 / BAT 橙在同一张卡里一眼可区分 */
private val VirTempGreen = Color(0xFF4CAF50)

/**
 * VIR 虚拟温度 = Kite 的 virTemp 口径（2026-09-29 用户提供）：virTemp ≈ 0.75 × 电池温度 +
 * 0.25 × 热点温度，热点温度实测均值 ≈ 43.5℃，恒定代入。不读温感区、无 root/Shizuku 也算
 * 得出，渲染期从电池温度现算（不落库、不改 DB），旧会话照常出线。
 */
private const val VIR_TEMP_HOTSPOT_C = 43.5

private fun virTempC(batteryTempC: Double?): Double? =
    batteryTempC?.let { 0.75 * it + 0.25 * VIR_TEMP_HOTSPOT_C }

/** CPU 占用率线（FPS 卡右轴候选；Kite 图例里的粉色） */
private val CpuLoadPink = Color(0xFFF48FB1)

/** GPU 占用率线（FPS 卡右轴候选；Kite 图例里的蓝色） */
private val GpuLoadBlue = Color(0xFF64B5F6)
/** GPU 频率线（2026-09-29 加，右轴 GPU(MHz) 候选；淡紫与 CpuLoadPink/GpuLoadBlue 区分） */
private val GpuFreqPurple = Color(0xFF9575CD)

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
     * 非空 = 左轴刻度用这组**任意值**（CPU 频率卡：300MHz 一格 + 顶档 = 本场峰值 —— 等分
     * 轴做不到"步长固定 300、轴顶却是任意峰值"），此时忽略 [leftTickCount]；值必须随
     * [rememberChartInsets] 的同名参数一致传入，inset 计算与绘制才同一套刻度。
     */
    leftTickValues: List<Double>? = null,
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
    // drawPath 指令录制。
    // ⚠️ 折线预渲染为位图（2026-09-28 横屏卡顿修复，用户实测减线即流畅定位）：
    // RT 每帧回放 12 条 stroke path（横屏 ~1.4 万段）的绘制命令 + 路径几何处理成本与
    // 线数/点数成正比，横屏绘制量是竖屏 ~1.7 倍 → 顶破 120Hz 帧预算（RT-issue 段大、
    // GPU/UI 都不慢的实测形态）。这里把全部折线一次性画进 ImageBitmap（设备像素级，
    // 视觉 1:1 无损），每帧只剩一条 drawImage 贴图命令 —— 即「画好存成一张图片」，
    // 成本与线数彻底脱钩。网格 / 轴标签留在实时绘制（命令数少、与线数无关）。
    // 位图仅在 lines / 轴区间 / 尺寸 / 主题色变化时重建（含旋转一次）。
    // ⚠️ 折线位图构建搬后台线程（2026-09-30 治展开动画抢线程的「搬走」半边）：旧实现在
    // remember 里同步构建（大场次 12 线 × 2400 点的 Path 几何 + 整卡位图填充，十几张卡
    // 叠加 = 进场首帧重活），与展开动画同帧抢 UI 线程 = 动画全程卡顿（批次七十五装机实测，
    // 内容就绪闸门治标不治本——重活只是被挪出了动画窗口，还拖慢展开起跑）。现在纯软件
    // 位图光栅化（ARGB_8888 Bitmap + Canvas，不碰任何 View/Compose 状态）在
    // Dispatchers.Default 上画，主线程只剩一条 drawImage 贴图指令。键变化时旧位图保留
    // 继续贴（旋转拉伸口径见绘制处注释），新位图就绪自动替换；网格/轴标签是廉价指令，
    // 位图未就绪时先画骨架 —— 曲线随后台计算逐卡跟进（进场交叉淡变期内到达，无感）。
    val lineBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        lines, leftRange, rightRange, chartSize, startInset, endInset, density,
    ) {
        value = withContext(Dispatchers.Default) {
            buildLineChartBitmap(lines, leftRange, rightRange, chartSize, startInset, endInset, density)
        }
    }
    // 数据本身能否成线（与尺寸无关，组合期同步可知）：<2 点没有可画的折线，连网格都不画
    // （与旧同步位图的 null 口径一致：degenerate 数据卡内空白，不是空网格）
    val hasDrawableData = (lines.maxOfOrNull { it.values.size } ?: 0) >= 2
    Box(
        modifier
            .fillMaxWidth()
            .height(190.dp)
            .onSizeChanged { chartSize = it }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            ChartPerf.tick("lineChart.draw")
            if (!hasDrawableData) return@Canvas
            val plotLeft = startInset.toPx()
            val plotRight = size.width - endInset.toPx()
            val plotW = plotRight - plotLeft
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f))
            // 左轴网格行位置：等分（默认）或任意刻度值（leftTickValues，按左轴值域换算 frac）
            val leftFracs: List<Float> = if (leftTickValues != null && leftRange != null) {
                val span = leftRange.endInclusive - leftRange.start
                leftTickValues.map { (1 - (it - leftRange.start) / span).toFloat() }
            } else {
                (1..leftTickCount).map { (1 - it / leftTickCount.toFloat()).toFloat() }
            }
            for (frac in leftFracs) {
                val y = size.height * frac
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
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                if (leftTickValues != null) {
                    val st = if (leftTickValues.size >= 2) leftTickValues[1] - leftTickValues[0] else 1.0
                    val span = r.endInclusive - r.start
                    for (v in leftTickValues) {
                        val y = size.height * (1 - ((v - r.start) / span).toFloat())
                        drawContext.canvas.nativeCanvas.drawText(
                            tickText(v, st),
                            plotLeft - 6.dp.toPx(),
                            y + labelPaint.textSize / 3,
                            labelPaint,
                        )
                    }
                } else {
                    val step = (r.endInclusive - r.start) / leftTickCount
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
            // 还是旧方向尺寸、后台重建要等 onSizeChanged 后一拍，不拉伸的话旧窄图只会贴在
            // 左半边（"半截曲线、半秒后才铺开"的观感 bug）。按 dstSize 拉伸后旧图立即
            // 铺满全宽（横向拉伸等价于同一时间轴映射，短暂略糊），新图就绪自动 1:1。
            // 位图未就绪（首帧 / 后台重建中）只缺 drawImage —— 网格骨架已在上面先画。
            lineBitmap?.let { bmp ->
                clipRect(left = plotLeft, top = 0f, right = plotRight, bottom = size.height) {
                    drawImage(
                        image = bmp,
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
}

/**
 * 折线位图构建（[FrameLineChart] 的 lineBitmap 生产函数，纯函数无状态）：尺寸/点数不达标
 * 返回 null。只碰软件 Bitmap + Compose 几何，**主线程或 [Dispatchers.Default] 后台均可调**
 * —— 不碰任何 View/Compose 状态，ARGB_8888 软件 Bitmap 允许任意线程创建与绘制。
 * ChartPerf.logBuild 只打 Log，线程无关。
 */
private fun buildLineChartBitmap(
    lines: List<FrameLine>,
    leftRange: ClosedFloatingPointRange<Double>?,
    rightRange: ClosedFloatingPointRange<Double>?,
    chartSize: IntSize,
    startInset: Dp,
    endInset: Dp,
    density: Density,
): ImageBitmap? {
    val t0 = System.nanoTime()
    val maxCount = lines.maxOfOrNull { it.values.size } ?: 0
    val bmp = if (chartSize.width < 2 || chartSize.height < 2 || maxCount < 2) {
        null
    } else {
        val plotLeft = with(density) { startInset.toPx() }
        val plotW = (chartSize.width - plotLeft - with(density) { endInset.toPx() })
            .coerceAtLeast(1f)
        // ⚠️ 不做 min-max 抽稀（2026-09-28）：位图化后每帧只剩 drawImage、成本与点数
        // 脱钩，抽稀省的只是重建时一次性的路径构建；而「一桶含任一 null → 整桶断线」
        // 的口径会在密集会话（点数 > 2×bins）里把零星缺测放大成可见断口，得不偿失。
        // ⚠️ 「断断续续」的真根因（2026-09-28 晚 xlsx 取证定案）：不是抽稀（518 点的
        // 会话从未触发抽稀），是 fps=null 的拍被「缺测断线」画成洞——帧率差分 09-27
        // 起回退 1s 完整拍后，timestats 图层 churn 每 ~8s 打掉一拍（全场 ~19% null、
        // 几乎全是单拍），点距 ~5px 时每个洞肉眼可见 = 虚线观感。修法 = FPS 线的
        // bridgeNullRun 短缺测桥接（见 FPS_LINE_BRIDGE_NULL_RUN），与本处无关。
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
                        line.values, 0f, size.width, size.height, r, line.bridgeNullRun,
                    )
                    drawPath(path, line.color, style = Stroke(line.strokeWidth))
                }
            }
        }
        bmp
    }
    ChartPerf.logBuild("lineChart.buildPaths", lines.size, maxCount, System.nanoTime() - t0)
    return bmp
}

/**
 * 单条折线的 Path 构建（从 draw lambda 外移到组合期的版本，几何口径与旧实现逐字一致）：
 * x 按索引均分铺满绘图区，y 按轴量程线性映射，null/NaN 断线（moveTo 重新起笔）。
 * [bridgeNullRun] > 0 时，连片缺测 ≤ 该数**跨接连线**（跳过缺测点，由下一个有效点直接
 * lineTo 接上）；只有超阈值的长缺测段才断线——1s 节奏下 timestats 图层 churn 每 ~8s
 * 打掉一拍（fps=null），点距数 px 时单拍断线就是肉眼可见的洞，把整条线打成虚线
 * （2026-09-28 xlsx 取证：518 拍 99 个 null、89 个断口，80 个是单拍）。缺测点不参与
 * 统计的口径不变，这里只管画的形态。
 * 点数不足 2 返回空 Path（drawPath 空路径 = 无操作）。
 */
private fun buildChartLinePath(
    values: List<Double?>,
    plotLeft: Float,
    plotWidth: Float,
    plotHeight: Float,
    range: ClosedFloatingPointRange<Double>,
    bridgeNullRun: Int = 0,
): Path {
    val count = values.size
    if (count < 2) return Path()
    val span = range.endInclusive - range.start
    val path = Path()
    var drawing = false
    var i = 0
    while (i < count) {
        val v = values[i]
        if (v == null || v.isNaN()) {
            if (bridgeNullRun <= 0) {
                drawing = false
                i++
                continue
            }
            var j = i
            while (j < count && (values[j] == null || values[j]!!.isNaN())) j++
            if (j - i > bridgeNullRun) drawing = false
            i = j
            continue
        }
        val px = plotLeft + plotWidth * i / (count - 1)
        val py = (plotHeight * (1 - ((v - range.start) / span))).toFloat()
        if (drawing) path.lineTo(px, py) else path.moveTo(px, py)
        drawing = true
        i++
    }
    return path
}

/**
 * FPS 卡右轴可多选叠加序列（Kite 同款：电量 / 温度 / CPU/GPU 负载）。
 * [values2]/[color2] 非空时该图例项挂**两条线**（CPU/GPU 负载合并为单图例
 * 「CPU/GPU 负载」，用户 2026-10-01 定稿：粉=CPU、蓝=GPU，一个图例两个色块）。
 */
private class RightSeriesOption(
    val label: String,
    val range: ClosedFloatingPointRange<Double>,
    val color: Color,
    val values: List<Double?>,
    val color2: Color? = null,
    val values2: List<Double?>? = null,
)

/**
 * 帧率与温度卡：**双轴 + 右轴多曲线叠加**（Kite 版式，2026-09-28 单选改多选）——
 * FPS 折线走左轴（灰，0 → 设备铺满刷新率），右轴候选电量 / 温度 / CPU/GPU 负载（单图例
 * 双线：粉=CPU、蓝=GPU）/ GPU(MHz)：点底部图例**切换勾选**（可多条同绘，选中高亮、
 * 未选压暗），右上角 Refresh 钮轮换到下一候选（收敛为单选）。右轴值域：纯温度 0-50、
 * 纯百分项 0-100；混选（温度+百分项）统一挂 0-100，温度曲线落在轴下半区。
 * ⚠️ 右轴标签区宽度**恒按 0-100 档预留**（用户 2026-09-28：温度 0-50 切换时绘图区宽度跳动）
 * ——右轴标签从绘图区右缘起画，窄标签只是右侧多留白，绘图区宽度稳定不跳。
 * ⚠️ 整场无数据的候选自动不进切换列表（GPU 负载：节点全不可读的机器 / 旧会话缺列）。
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
    // CPU/GPU 负载（2026-10-01 定稿）：合并为**单图例「CPU/GPU 负载」**（用户否决两个
    // 分开图例），粉线=CPU、蓝线=GPU 同挂一项；GPU 走 gpubusy 漏网通道（Scene 同款，
    // 见 FrameRateSource.readGpuLoadPct）。任一条有数据即进列表（旧会话 CPU/GPU 整列
    // 缺、或节点全不可读的机器只剩单线，仍可显示）。
    // 旧会话（v7 前）的 Battery 同理。TEMP 恒在（v1 起就有电池温度），保底不为空。
    // ⚠️ 文案在组合上下文先解析再进 remember（stringResource 不能在 remember 块里调）
    val batteryLabel = stringResource(R.string.frame_card_battery)
    val tempLabel = stringResource(R.string.frame_card_temp)
    val cpuGpuLoadLabel = stringResource(R.string.frame_right_cpu_gpu_load)
    val gpuFreqLabel = stringResource(R.string.frame_right_gpu_freq)
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
    val rightOptions = remember(samples, fpsPoints, batteryLabel, tempLabel, cpuGpuLoadLabel, gpuFreqLabel) {
        // GPU 频率（2026-09-29 加）：值域按本场数据自适应取 500MHz 档上限（1000/1500/2000/2500…），
        // 固定档会顶格裁尖或留大片空白；无数据的机器 / 旧会话整列 null → 选项自动不出现
        val gpuFreqTop = samples.mapNotNull { it.gpuFreqMhz }.maxOrNull()
            ?.let { max -> ((max / 500.0).toInt() + 1) * 500.0 }
            ?: 1500.0
        val cpuLoadVals = samples.map { it.cpuUsagePct }.toFpsGrid()
        val gpuLoadVals = samples.map { it.gpuLoadPct }.toFpsGrid()
        listOfNotNull(
            RightSeriesOption(batteryLabel, 0.0..100.0, CapacityBlue, samples.map { it.capacityPct }.toFpsGrid())
                .takeIf { o -> o.values.any { it != null } },
            RightSeriesOption(tempLabel, 0.0..50.0, TempOrange, samples.map { it.tempBatteryC }.toFpsGrid()),
            RightSeriesOption(
                cpuGpuLoadLabel, 0.0..100.0, CpuLoadPink, cpuLoadVals,
                color2 = GpuLoadBlue, values2 = gpuLoadVals,
            ).takeIf { o -> o.values.any { it != null } || o.values2!!.any { it != null } },
            RightSeriesOption(gpuFreqLabel, 0.0..gpuFreqTop, GpuFreqPurple, samples.map { it.gpuFreqMhz }.toFpsGrid())
                .takeIf { o -> o.values.any { it != null } },
        )
    }
    // 右轴多选叠加：List 保**选择序**（标题按序拼接，后选中的线画在上层）。至少保一个 ——
    // 全清空会让右轴刻度消失、绘图区变宽，又是一次跳动。Refresh 仍单选轮换。
    // ⚠️ filter 兜底：rightOptions 随 samples 异步重建（空 → 有数据）时防旧下标越界
    var rightSel by remember { mutableStateOf(listOf(0)) }
    val sel = rightSel.filter { it < rightOptions.size }.ifEmpty { listOf(0) }
    // 右轴值域：选中项值域全一致（纯温度 0-50 / 纯百分项 0-100）直接用；混选统一挂
    // **最宽值域**（2026-09-29 从固定 0-100 改：GPU 频率 0-2500 与温度/百分项混选时，
    // 固定 0-100 会把频率线压成贴底的直线）—— 值域窄的线落到轴下半区，诚实不裁剪。
    // 兼容性：原有全部混选组合的最宽值域本就是 0-100（电池/百分项），行为不变。
    val rightRange = remember(sel, rightOptions) {
        val rs = sel.map { rightOptions[it].range }.distinct()
        if (rs.size == 1) rs.first() else 0.0..(rs.maxOf { it.endInclusive })
    }
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
                    ) { rightSel = listOf((sel.last() + 1) % rightOptions.size) },
                ) {
                    // 多选时标题按选择序拼接；weight(fill=false) 让超长串在给图标让位后省略，
                    // 不把左侧 "FPS" 标题挤下卡片
                    Text(
                        sel.joinToString(" / ") { rightOptions[it].label },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
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
            // 内缩按两侧标签实际宽度算；⚠️ 右侧宽度**恒按 0-100 档预留**（2026-09-28 用户反馈：
            // 温度 0-50 与百分项 0-100 切换时，"50"→"100" 的标签宽度差牵动绘图区左右跳）——
            // 右轴标签从绘图区右缘起画，窄标签只是右侧多留白，绘图区宽度稳定不跳
            val (fpsStartInset, fpsEndInset) =
                rememberChartInsets(0.0..fpsAxisMax, 0.0..100.0, rightTickCount = 5)
            val fpsLines = remember(fpsVals, sel, rightOptions, fpsLineColor) {
                listOf(
                    // FPS 线开缺测桥接：timestats churn 的单拍 null 不再打断线（右轴不开——
                    // 1s 值缺测在子拍网格上是 4 连 null，超桥接阈值，仍断线）
                    FrameLine(fpsVals, fpsLineColor, bridgeNullRun = FPS_LINE_BRIDGE_NULL_RUN),
                ) + sel.flatMap { i ->
                    val opt = rightOptions[i]
                    // 「CPU/GPU 负载」单图例项展开两条线（粉=CPU、蓝=GPU）；整列缺测的
                    // 那条线（旧会话 / 节点不可读）不画
                    listOfNotNull(
                        FrameLine(opt.values, opt.color, onRight = true),
                        opt.values2?.let { v2 -> FrameLine(v2, opt.color2!!, onRight = true) },
                    )
                }
            }
            FrameLineChart(
                lines = fpsLines,
                leftRange = 0.0..fpsAxisMax,
                rightRange = rightRange,
                rightTickCount = 5,
                startInset = fpsStartInset,
                endInset = fpsEndInset,
                modifier = Modifier.fillMaxWidth(),
            )
            ClockTicks(durationMs, startInset = fpsStartInset, endInset = fpsEndInset)
            Spacer(Modifier.height(6.dp))
            // 图例：FPS（恒显，左轴锚）+ 四个右轴候选；选中高亮、未选压暗，**点图例切换勾选**
            // 多选叠加（Kite 同款方块标）；仅剩一个选中项时再点它不清空（右轴恒有线）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JankLegendDot(fpsLineColor, "FPS")
                rightOptions.forEachIndexed { i, opt ->
                    Spacer(Modifier.width(12.dp))
                    val active = i in sel
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            rightSel = when {
                                i in sel && sel.size > 1 -> sel - i
                                i in sel -> sel
                                else -> sel + i
                            }
                        },
                    ) {
                        // 「CPU/GPU 负载」单图例两个色块（粉=CPU、蓝=GPU），同选同暗
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    opt.color.copy(alpha = if (active) 1f else 0.35f),
                                    RoundedCornerShape(2.dp),
                                )
                        )
                        if (opt.color2 != null) {
                            Spacer(Modifier.width(2.dp))
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(
                                        opt.color2.copy(alpha = if (active) 1f else 0.35f),
                                        RoundedCornerShape(2.dp),
                                    )
                            )
                        }
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
 * ⚠️ 纵轴口径 = 功率大小（2026-09-28 起库里为绝对值口径，曲线恒正；旧"放电为正、
 * 充电为负"的取反画法随 v6 数据纠正一并作废）。底部 MAX / MIN / AVG 为图中口径；
 * 容量 v7 起采集，旧会话整线缺失。
 */
@Composable
private fun FramePowerCard(samples: List<FrameSample>, shape: RoundedCornerShape) {
    // powerW remember（2026-09-28 滑动卡顿修复）：派生列表引用稳定，下游 lines →
    // FrameLineChart 的 Path 缓存不因重组失效
    val powerW = remember(samples) { samples.map { it.powerMw?.let { mw -> mw / 1_000.0 } } }
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
 * Temperature(°C) 卡：CPU（温感区代表温度，需 root/Shizuku）/ GPU（GPU 温感区，v7 起采集）/
 * BAT（电池）/ VIR（Kite virTemp 口径虚拟温度，见 [virTempC]，电池温度在即有）四线共绘，
 * y 轴固定 0..50（Kite 同款）；缺测断线，旧会话无 GPU 列整线缺失。
 * 卡内单线自适应：某条线整场无任何读数（如机型没有 GPU 温感区）则该线连同图例点不出现，
 * 不画一个只有颜色的空图例。
 */
@Composable
private fun FrameTempCard(samples: List<FrameSample>, shape: RoundedCornerShape) {
    var showInfo by remember { mutableStateOf(false) }
    // 按压预备：口径同稳帧指数 ⓘ（rememberDialogPressArmed，见 GlassDialog KDoc）
    val infoSource = remember { MutableInteractionSource() }
    val infoArmed = rememberDialogPressArmed(infoSource, showInfo)
    val durationMs = samples.last().timeMillis - samples.first().timeMillis
    val hasCpuTemp = samples.any { it.tempVirtualC != null }
    val hasGpuTemp = samples.any { it.gpuTempC != null }
    val hasBatTemp = samples.any { it.tempBatteryC != null }

    AppCard(
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 图表列不再吃横向 padding（同 Power 卡：轴标签贴边、绘图区加宽），标题行保留 16dp
        Column(Modifier.padding(vertical = 16.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.frame_card_temperature),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                // VIR 虚拟温度说明 ⓘ（样式/交互同 Jank 卡：无水波纹、再点一次收起）
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = stringResource(R.string.frame_card_temp_vir_info_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(16.dp)
                        .clickable(
                            interactionSource = infoSource,
                            indication = null,
                        ) { showInfo = !showInfo },
                )
            }
            Spacer(Modifier.height(8.dp))
            val (startInset, endInset) = rememberChartInsets(0.0..50.0, null)
            // ⚠️ lines 必须稳定（remember 住）：FrameLineChart 按 lines 实例记忆折线位图，
            // 每次重组新建 List 会把位图重建拖回每帧
            val tempLines = remember(samples, hasCpuTemp, hasGpuTemp, hasBatTemp) {
                buildList {
                    if (hasCpuTemp) add(FrameLine(samples.map { it.tempVirtualC }, CpuTempBlue))
                    if (hasGpuTemp) add(FrameLine(samples.map { it.gpuTempC }, GpuTempPurple))
                    if (hasBatTemp) {
                        add(FrameLine(samples.map { it.tempBatteryC }, TempOrange))
                        add(FrameLine(samples.map { virTempC(it.tempBatteryC) }, VirTempGreen))
                    }
                }
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
                buildList {
                    if (hasCpuTemp) add(CpuTempBlue to "CPU")
                    if (hasGpuTemp) add(GpuTempPurple to "GPU")
                    if (hasBatTemp) {
                        add(TempOrange to "BAT")
                        add(VirTempGreen to "VIR")
                    }
                }.forEachIndexed { index, (color, label) ->
                    if (index > 0) Spacer(Modifier.width(14.dp))
                    JankLegendDot(color, label)
                }
            }
            // ⓘ 弹窗直接交 GlassDialog（自带入场动效），不包 AnimatedVisibility —— 理由同稳帧指数 ⓘ
            if (infoArmed) {
                FrameInfoDialog(
                    open = showInfo,
                    title = stringResource(R.string.frame_card_temp_vir_info_title),
                    body = stringResource(R.string.frame_card_temp_vir_info),
                    onDismiss = { showInfo = false },
                )
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
internal class CpuPoint(
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
 *
 * y 轴 = **0-100 每 10% 一档、含顶档 100 全标**（2026-09-29 用户指定 Scene 样式：
 * 网格虚线每 10% 一条、标签 10/20/…/90/100，0 = 底轴不标 —— Scene 截图只到 90，
 * 用户点名要补上 100）；190dp / 10 行 = 19dp 行距，10sp 居中标签不叠印。
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
    FrameCpuMultiLineCard(
        stringResource(R.string.frame_card_cpu_usage),
        series,
        0.0..100.0,
        points,
        shape,
        leftTickCount = 10,
    )
    SideEffect { ChartPerf.tick("cpuUsage.recompose") }
}

/**
 * CPU Frequency(MHz) 卡：四条分簇频率线（按簇聚合）+ 簇内逐核淡色细线。
 * y 轴 = **300MHz 一格 + 顶档 = 本场记录到的最大频率**（2026-09-29 用户定案，Scene 同款
 * —— Scene 顶上那个 3148 就是它自己那场库里的频率峰值、随场次变；「设备静态最大频率」
 * 方案作废：Shizuku 虽可读 cpuinfo_max_freq（实测 24031PN0DC 逐核 3302/3149/2957/2266
 * MHz）但按最终口径不再需要）。
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
        peak.coerceAtLeast(300.0)
    }
    // y 轴刻度 = 300MHz 整倍数 + 顶档 = 本场峰值（标签 %.0f，如 3148.8 → "3149"）。
    // ⚠️ 距顶不足 minGap 的整倍数省略，否则与顶档标签叠印：3302 顶下 3300 只差 2MHz、
    // 3148 顶下 3000 差 149 —— minGap 按「10sp 标签高 ~14dp / 绘图区 190dp」的比例随
    // 轴顶缩放（3302 → 省略 3300 留 3000；3148 / 3053 → 省略 3000）。
    val leftTickValues = remember(yMax) {
        buildList {
            var v = 300.0
            val minGap = yMax * 14.0 / 190.0
            while (v <= yMax - minGap) {
                add(v)
                v += 300.0
            }
            add(yMax)
        }
    }
    FrameCpuMultiLineCard(
        stringResource(R.string.frame_card_cpu_frequency),
        series,
        0.0..yMax,
        points,
        shape,
        leftTickValues = leftTickValues,
    )
    SideEffect { ChartPerf.tick("cpuFreq.recompose") }
}

/**
 * 多线共轴折线卡（CPU 两卡共用）：标题 + 折线 + 时间刻度 + 图例。
 * 图例点击切换对应线的显示/隐藏（截图中 Kite「Chart Options」的等价简化：
 * 隐藏以图例压暗表示，至少保留一条可见）；轴标签贴边内缩，同其它折线卡。
 * [leftTickCount] = 左轴等分数（默认 4 等分；Usage 卡传 10 = Scene 同款每 10% 一档），
 * 必须同喂 [rememberChartInsets] 与 [FrameLineChart]（inset 计算与绘制同一套刻度）。
 */
@Composable
private fun FrameCpuMultiLineCard(
    title: String,
    series: List<FrameSeries>,
    leftRange: ClosedFloatingPointRange<Double>,
    points: List<CpuPoint>,
    shape: RoundedCornerShape,
    leftTickCount: Int = 4,
    /** 非空 = 左轴任意刻度值（频率卡 300MHz 一格 + 顶档峰值），见 [FrameLineChart.leftTickValues] */
    leftTickValues: List<Double>? = null,
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
            val (startInset, endInset) = rememberChartInsets(
                leftRange,
                null,
                leftTickCount = leftTickCount,
                leftTickValues = leftTickValues,
            )
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
                    leftTickCount = leftTickCount,
                    leftTickValues = leftTickValues,
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
 * Frame Time(ms) 卡：**逐帧**帧时间柱状图（2026-09-28 起，Scene 同款渲染；旧会话回退
 * 每秒平均柱 —— Kite 同名卡）。
 *
 * ⚠️ 为什么弃"每秒平均柱"：1s 平均把单帧尖刺全摊平 —— 同一份王者数据逐帧 MAX 157ms
 * vs 秒均 MAX 13.31ms，秒均柱是一条 8ms 死线（用户对照 Scene 截图拍板改逐帧；与 Jank
 * 卡旧口径全零同源，事故记录见 [frameJankStats] 上方）。
 *
 * - 逐帧渲染：y 轴**固定 0-100ms**（[FramePerFrameChart]；12 档、8.33ms/档、标签
 *   8/16/25/…/100 与 Scene 120Hz 逐位一致），超顶帧钉在顶端，footer MAX =
 *   最大单帧桶（≥1s 呈现中断不计，见 [flattenFrameTimes]）；
 * - 方差：逐帧口径（[FrameJankStats.frameTimeVar]，稳帧指数的平方，两处口径一致）；
 * - 回退每秒平均柱（旧会话 frame.db v5 前落库、**TaskFps 采样源场次** —— 系统直推
 *   不逐帧推时间戳，p2pHist 恒空 → [frameJankStats] 返 null）：y 轴**固定 0-100、
 *   步长 20 共 6 档**，MAX = 秒均值最大值 —— 粒度局限同 [jankTiers]。
 *
 * ⚠️ 两分支 y 轴 2026-10-01 起一律钉死 0-100（用户报"120hz 的场次 y 轴居然是 0-12"）：
 * 旧版逐帧轴顶 = 录制时刷新率×12（60fps 场次面板被 LTPO 压到 60Hz 时轴顶 200）、回退
 * 分支走自适应整步长轴（健康 120fps 场次秒均 ~8-11ms → 轴顶 10-14），观感都与 Scene
 * 的 0-100 割裂。
 */
@Composable
private fun FrameTimeCard(
    samples: List<FrameSample>,
    frameStats: FrameJankStats?,
    shape: RoundedCornerShape,
) {
    val durationMs = samples.last().timeMillis - samples.first().timeMillis
    AppCard(
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 2026-09-27 用户拍板多语言化（此前"Kite 原文不译"的口径作废）
            Text(stringResource(R.string.frame_card_frame_time), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            if (frameStats != null) {
                val frames = remember(samples) { flattenFrameTimes(samples) }
                FramePerFrameChart(frames, durationMs, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "MAX: ${frames.maxOrNull()?.f0() ?: "—"}ms  " +
                        stringResource(R.string.frame_variance) + ": " + frameStats.frameTimeVar.f2(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = DetailNumericFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else {
                val ftVals = samples.map { it.frameSpaceMs }
                val maxFt = (ftVals.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
                // y 轴固定 0-100、步长 20 共 6 档（2026-10-01 用户报"120hz 的场次 y 轴居然是
                // 0-12"）：旧版自适应整步长轴在健康 120fps 场次（秒均 ~8-11ms）轴顶只有
                // 10-14，与逐帧分支/Scene 的 0-100 观感割裂，两分支统一钉死；150dp ÷ 6 档 =
                // 25dp/行不叠印。>100ms 的秒（整秒停滞）柱高钳在轴顶，真值看 footer MAX
                val ftAxisMax = 100.0
                val ftTickCount = 6
                val variance =
                    if (ftVals.size >= 2) {
                        val avg = ftVals.average()
                        ftVals.sumOf { (it - avg) * (it - avg) } / ftVals.size
                    } else {
                        null
                    }
                val barColor = MaterialTheme.colorScheme.primary
                FrameBarChart(
                    values = ftVals,
                    barColors = List(ftVals.size) { barColor },
                    yMax = ftAxisMax,
                    yTickCount = ftTickCount,
                    durationMs = durationMs,
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
}

/**
 * 逐帧帧时间柱状图（Scene 同款渲染，2026-09-28）：每一帧一根竖条、高度 = 该帧帧间隔。
 *
 * - y 轴**固定 0-100ms**（2026-10-01 用户指定，不再随录制时刷新率缩放——旧版轴顶 =
 *   vsync×12，60fps 场次面板被 LTPO 压到 60Hz 时轴顶 200ms），[lineCount] 档、
 *   8.33ms/档，标签 = 档值向下取整（8/16/25/33/41/50/58/66/75/83/91/100，与 Scene
 *   120Hz 截图逐位一致）；**超轴顶的帧钉在顶端画出**（Scene 同款），真值看 footer MAX；
 * - **12 档全标**（2026-09-29 用户指定 Scene 样式，推翻上一版隔行标注）：绘图区加高到
 *   200dp（150dp 塞 12 行 = 12.5dp/行，9sp 居中标签必然叠印；200dp = 16.7dp/行，
 *   fontScale 1.3 下仍有余量 —— 防叠印按"行距 ≥21dp"口径，本卡 12 档
 *   放宽到 16.7dp 靠加高而不是减档）；
 * - 网格 = **点状虚线**（dash 5f/7f，与 FrameLineChart 折线卡同一套；Scene 同款）；
 * - x 轴 = 帧序号等分铺满全程（帧与帧的真实间隔不均匀，但亚像素密度下不可辨，时间
 *   刻度仍按真实时长 [ClockTicks]）；
 * - 绘制：等值段合并成单矩形（数据桶内升序展开，同高帧天然连成 run）—— 路径矩形数
 *   = 每秒桶数级（~1.5k），不是帧数级（~8.6 万）；**run 宽与孤立帧最小 1px**（slotW
 *   亚像素时单个尖帧才可见，密集段重叠成实心带 = Scene 的观感来源）。
 */
@Composable
private fun FramePerFrameChart(
    frames: DoubleArray,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = MaterialTheme.colorScheme.primary
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    val lineCount = 12
    val axisTop = 100.0
    val labels = (1..lineCount).map { "${(axisTop * it / lineCount).toInt()}" }
    val startInset = rememberBarChartLeftInsetLabels(labels)
    // 点状虚线网格（与 FrameLineChart 同参数）
    val gridDash = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 7f)) }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                if (frames.isEmpty() || axisTop <= 0.0) return@Canvas
                val plotLeft = startInset.toPx()
                val plotW = size.width - plotLeft
                val h = size.height
                labelPaint.textSize = 9.sp.toPx()
                labelPaint.color = labelColor.toArgb()
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                for (k in 1..lineCount) {
                    val y = h * (1f - k / lineCount.toFloat())
                    drawLine(
                        gridColor,
                        Offset(plotLeft, y),
                        Offset(size.width, y),
                        strokeWidth = 1f,
                        pathEffect = gridDash,
                    )
                    // 标签垂直居中在横线上（baseline = y + textSize/3，口径同 FrameBarChart）
                    drawContext.canvas.nativeCanvas.drawText(
                        labels[k - 1],
                        plotLeft - 6.dp.toPx(),
                        y + labelPaint.textSize / 3,
                        labelPaint,
                    )
                }
                // 内部三条竖向分隔线 + 外框（与 FrameBarChart / FrameLineChart 同一套骨架）
                for (q in 1..3) {
                    val x = plotLeft + plotW * q / 4
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                }
                drawRect(
                    labelColor.copy(alpha = 0.4f),
                    topLeft = Offset(plotLeft, 0f),
                    size = Size(plotW, h),
                    style = Stroke(1f),
                )
                val slotW = plotW / frames.size
                val path = Path()
                var i = 0
                while (i < frames.size) {
                    val ms = frames[i]
                    var j = i
                    while (j < frames.size && frames[j] == ms) j++
                    val x0 = plotLeft + i * slotW
                    val w = ((j - i) * slotW).coerceAtLeast(1f)
                    // 超轴顶的帧钉在顶端（Scene 同款）；单帧至少 1px 高保可见
                    val bh = (ms / axisTop * h).toFloat().coerceIn(1f, h)
                    path.addRect(Rect(x0, h - bh, x0 + w, h))
                    i = j
                }
                drawPath(path, barColor)
            }
        }
        ClockTicks(durationMs, startInset = startInset)
    }
}

/**
 * Jank 卡：卡顿柱状图（Kite 同名卡）。柱高恒 1（该秒命中某档即立柱）。
 * 三档判定**逐帧口径**（[frameJankStats]，2026-09-28 重构 —— 旧口径把 83/125ms 单帧
 * 门槛套在 1s 均值上，正常录制三档全零，事故记录在该函数上方）：小卡顿（灰）/
 * 卡顿（蓝）/ 严重卡顿（红），每帧只计最高一档、每秒柱取该秒命中帧的最高档。
 * 底部图例 = 全场三档**帧数**合计；旧会话（无 p2pHist 分布）回退 [jankTiers]，此时
 * 图例为**秒数**（1s 均值口径，粒度局限见该函数注释）；ⓘ 打开口径说明。
 */
@Composable
private fun FrameJankCard(
    samples: List<FrameSample>,
    frameStats: FrameJankStats?,
    shape: RoundedCornerShape,
) {
    var showInfo by remember { mutableStateOf(false) }
    // 按压预备：口径同稳帧指数 ⓘ（rememberDialogPressArmed，见 GlassDialog KDoc）
    val infoSource = remember { MutableInteractionSource() }
    val infoArmed = rememberDialogPressArmed(infoSource, showInfo)
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    val blue = MaterialTheme.colorScheme.primary
    // 逐帧口径优先（图例=帧数）；旧会话回退 1s 均值口径（图例=秒数）
    val tiers = frameStats?.tiers ?: jankTiers(samples)
    val barColors = tiers.map { tier ->
        when (tier) {
            1 -> gray
            2 -> blue
            3 -> BigJankRed
            else -> Color.Transparent
        }
    }
    val smallCount = frameStats?.smallFrames ?: tiers.count { it == 1 }
    val jankCount = frameStats?.jankFrames ?: tiers.count { it == 2 }
    val bigCount = frameStats?.bigFrames ?: tiers.count { it == 3 }

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
                            interactionSource = infoSource,
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
            if (infoArmed) {
                FrameInfoDialog(
                    open = showInfo,
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
                // 档步长（yMax 恒为步长整数倍，见各调用方的固定档）：
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
    /** 非空 = 左轴刻度用这组**任意值**（CPU 频率卡 300MHz 一格 + 顶档峰值），忽略 leftTickCount */
    leftTickValues: List<Double>? = null,
): Pair<Dp, Dp> {
    val density = LocalDensity.current
    return remember(leftRange, rightRange, rightTickCount, leftTickCount, leftTickValues, density) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 10.sp.toPx() }
        }
        val widestLeft = if (leftTickValues != null) {
            val st = if (leftTickValues.size >= 2) leftTickValues[1] - leftTickValues[0] else 1.0
            with(density) {
                leftTickValues.maxOf { paint.measureText(tickText(it, st)) }.toDp()
            }
        } else {
            leftRange
                ?.let { r -> leftTickTexts(r, leftTickCount).maxOf { paint.measureText(it) } }
                ?.let { with(density) { it.toDp() } }
        }
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
    val step = yMax / (yTickCount - 1)
    val labels = (0 until yTickCount - 1).map {
        tickText(yMax * (1 - it.toFloat() / (yTickCount - 1)), step)
    }
    return rememberBarChartLeftInsetLabels(labels)
}

/** 同 [rememberBarChartLeftInset]，但直接吃标签文本（[FramePerFrameChart] 的固定档标签不走 tickText） */
@Composable
private fun rememberBarChartLeftInsetLabels(labels: List<String>): Dp {
    val density = LocalDensity.current
    return remember(labels, density) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 9.sp.toPx() }
        }
        val maxW = labels.maxOf { paint.measureText(it) }
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
