package com.chen.powermeter.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chen.powermeter.R
import com.chen.powermeter.data.ImportedSeries
import com.chen.powermeter.service.SamplingService
import com.chen.powermeter.ui.theme.LocalCornerRadius
import com.chen.powermeter.ui.theme.PowerMeterTheme
import com.chen.powermeter.util.NavigationBarHelper

/**
 * 关闭时内容淡到主题底色的时长。全屏页（[TrendFullscreenScreen]）与 Activity 的关闭时序共用，
 * 故放在文件级而非 companion 内。
 */
private const val CLOSE_FADE_MS = 120

/**
 * 关闭时等待「显示方向转回」落地的最长时间。
 *
 * 解锁方向后若设备本就横握（解锁不产生旋转），`onConfigurationChanged` 不会来 ——
 * 由本超时兜底，避免页面卡在"已淡出但不关闭"的状态。
 */
private const val ORIENTATION_SETTLE_TIMEOUT_MS = 250L

/**
 * 趋势卡全屏页。
 *
 * 三条硬性要求（对应本次需求）：
 * 1. **隐藏状态栏与小白条**：[NavigationBarHelper.enterImmersive]，上滑可瞬时唤出。
 * 2. **小白条真沉浸**：系统栏透明 + 关闭 contrast（[NavigationBarHelper.setupEdgeToEdge]），
 *    内容延伸到系统栏之下（背景铺满，**不**用 safeDrawing 全边 padding，否则只是
 *    "把系统栏区域换成背景色"而非沉浸）。
 * 3. **避开摄像头**：窗口声明 LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS（允许画到挖孔区，
 *    否则默认模式会在挖孔侧留一条黑带），再由 Compose 的
 *    [WindowInsets.displayCutout] 全边避让——竖屏挖孔在顶部中央、横屏在左/右侧，
 *    同一行代码两种形态都覆盖。
 *
 * 横屏增强（本页专有，卡片内嵌版本不启用）：
 * - 指标胶囊**多选**，实现多条曲线叠加对比；
 * - 双指缩放 / 单指平移，放大后图表下方出现时间轴滑条；
 * - 单指按下即读数（页面本身不可滚动，不会与滚动抢手势）。
 *
 * 数据源按 `if (导入非空) 导入 else 实时` 取 —— 与主页面同一口径，
 * 因此从主页面进入本页时看到的一定是同一份数据。
 *
 * **关闭时序（C+，勿简化回直接 `finish()`）**：
 * 本页把**显示方向**锁成横屏（见 [onCreate] ⓪），而被它覆盖的 MainActivity 是 `configChanges`
 * 含 `orientation|screenSize` 的（不重建）。因此直接 `finish()` 会出现：本页滑出的同时显示才转回
 * 竖屏，主页先按**横屏两列**画出来、随后再翻回竖屏单列 —— 趋势卡宽度与位置同时改变，
 * 观感就是"返回主页闪一下"。关闭必须走 [requestClose]：
 * **覆盖层先收回到锚点矩形（一镜到底）** → 恢复系统栏 + 解锁方向（旋转发生在纯色屏之下）→
 * 等方向落地（`onConfigurationChanged` 或超时兜底）→ `finish()`（无过渡，主页已是正确的
 * 竖屏单列）。进入/退出的转场都由 [ClipReveal] 覆盖层承担（2026-09-26 一镜到底 Container
 * Transform 口径），主题窗口动画不再参与。
 * 打开方向不需要淡出铺底处理：本页是**独立窗口**，`setRequestedOrientation` 的效果在启动
 * 窗口（StartingWindow）底下就生效了，首帧即横屏。
 */
class TrendFullscreenActivity : ComponentActivity() {

    companion object {
        /** 进入时的指标选择（Metric.name）；缺省回退 POWER */
        const val EXTRA_METRIC = "extra_metric"

        /** 源页（竖屏窗口）里 `< >` 按钮的窗口矩形 + 源窗口宽高，供锚点跨方向换算（见 [mapPortraitRectToWindow]） */
        const val EXTRA_SOURCE_BOUNDS = "extra_source_bounds"

        /**
         * 打开趋势全屏页。
         *
         * 进入动画 = **ClipReveal 一镜到底**：本页首帧把整页内容放进 [ClipReveal] 覆盖层，
         * 从 [revealBounds]（`< >` 按钮在**源页竖屏窗口**里的矩形）换算出的横屏锚点矩形
         * 四边同步撑开铺满（见 [mapPortraitRectToWindow] 与 onCreate 的 PreDraw 时序），
         * 不用系统 ActivityOptions（无圆角、无内容交叉淡变、退出无法收回锚点）。
         *
         * @param revealBounds `< >` 按钮矩形（源页窗口坐标）；null = 退化为过屏幕中心的
         *   全宽线展开（兜底，仍是一镜到底）
         */
        // internal：签名含 internal 的 Metric，public 会触发「public function exposes
        // its internal parameter type」；调用方（PowerMeterScreen）同模块，可见性足够
        internal fun launch(context: Context, metric: Metric, revealBounds: Rect? = null) {
            val i = Intent(context, TrendFullscreenActivity::class.java)
                .putExtra(EXTRA_METRIC, metric.name)
            revealBounds?.takeIf { it.width() > 0 && it.height() > 0 }?.let { r ->
                i.putExtra(
                    EXTRA_SOURCE_BOUNDS,
                    intArrayOf(r.left, r.top, r.right, r.bottom, sourcePortraitWidth, sourcePortraitHeight),
                )
            }
            context.startActivity(i)
            // 压制本次启动的窗口滑动动画（同 SportLink AppTransitions.launchWithTransform）：
            // 主题 activityOpen* 的整窗滑入会与目标窗口内 ClipReveal 的四向撑开**叠播**，
            // ClipReveal 的前半段被整窗滑入吞掉 —— 用户只会看到一次普通滑入，
            // 一镜到底完全不可见（2026-09-26 装机实测）。窗口动画全交给覆盖层。
            (context as? Activity)?.let { src ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    src.overridePendingTransition(0, 0)
                }
            }
        }

        // 源页竖屏窗口宽高：调用方（PowerMeterScreen）同进程直接写 —— 竖屏窗口 metrics
        // 在本页（横屏窗口）里无法再拿到，extra 带过来最直接
        var sourcePortraitWidth = 0
        var sourcePortraitHeight = 0
    }

    /**
     * 正在关闭（Compose 可读的 state）。
     *
     * true = 覆盖层已/正在收回锚点矩形，收拢结束后恢复系统栏 + 解锁方向，等显示方向
     * 转回落地后再真正 `finish()`。
     * 同时作为**幂等护栏**：✕ 与系统返回手势可能在极短时间内都触发一次，重复执行会让
     * 超时兜底与 `onConfigurationChanged` 两条路径各调一次 `finish()`。
     */
    private var closing by mutableStateOf(false)

    /** ClipReveal 覆盖层会话句柄：requestClose 收回、onClosed 接续关闭时序都经它 */
    private var revealHolder: ClipReveal.Holder? = null

    /** 覆盖层底色 = Compose 主题底色（空壳 setContent 的 SideEffect 写入，PreDraw 启动转场时读） */
    private var pageBackground = android.graphics.Color.WHITE

    /** 关闭收尾（恢复系统栏 + 解锁方向）是否已启动：onClosed 与超时兜底双路径幂等 */
    private var closeSequenceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⓪ 强制横屏：趋势曲线横向展开才有读数价值（竖屏时间轴被压得过短）。
        //    用 SENSOR_LANDSCAPE 而非 LANDSCAPE —— 前者允许 landscape ↔ reverseLandscape
        //    随重力自由切换，用户把设备转 180° 也不会被钉死在一个方向。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // ① edge-to-edge：系统栏透明 + 关 contrast + decorFitsSystemWindows(false)
        //    + 注册 decorView insets 监听（180° 翻转不回调 onConfigurationChanged 的兜底）
        NavigationBarHelper.setupEdgeToEdge(
            this,
            lightStatusBar = !isNightMode(),
            immersive = true,
        )
        // ② 真沉浸：隐藏状态栏 + 小白条
        NavigationBarHelper.enterImmersive(this)

        // ③ 允许窗口延伸到摄像头区域；避让交给 Compose 的 displayCutout insets。
        //    minSdk 30 ≥ API 28，无需版本分支。
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        // 曲线颜色：本页可改色，先从 Prefs 恢复一次（与 MainActivity 同一仓库）
        ChartColors.load(this)

        val initialMetric = intent
            ?.getStringExtra(EXTRA_METRIC)
            ?.let { runCatching { Metric.valueOf(it) }.getOrNull() }
            ?: Metric.POWER

        setContent {
            PowerMeterTheme {
                // 空壳：真正的全屏页 UI 渲染在 ClipReveal 覆盖层里（见 startReveal）——
                // 覆盖层展开完成后它**就是**全屏页本体（驻留整个 Activity 生命周期），
                // 收拢时收回锚点矩形后露出的就是这层空壳纯底色，旋转无内容闪动。
                // 这里只铺主题底色 + 把底色交给 View 层（覆盖层 background 同色）。
                val bg = MaterialTheme.colorScheme.background
                SideEffect { pageBackground = bg.toArgb() }
                Box(Modifier.fillMaxSize().background(bg))
            }
        }

        // 系统返回手势 / 返回键必须走同一套关闭时序：放行给默认实现（直接 finish()）时，
        // 本页会在显示仍是横屏的状态下被移除 → 主页先按横屏两列画出来再翻回竖屏（返回瞬闪）
        onBackPressedDispatcher.addCallback(this) { requestClose() }

        // ⑤ 关闭过渡：整条交给覆盖层收回（[revealHolder.close] → onClosed →
        //    [finishClosingSequence]），finish() 本身不再播任何窗口动画 —— 此刻窗口只剩
        //    空壳纯底色，滑出/淡出只会多露一拍底色块。挂 0 = 显式禁用主题 activityClose*。
        //    open 方向同理压 0：主题 activityOpen* 的整窗滑入会与覆盖层 ClipReveal 的四向
        //    撑开叠播，一镜到底完全不可见（2026-09-26 装机实测）。API 34+ 在此注册两个方向；
        //    更早版本由源页 overridePendingTransition(0,0)（launch 内）+ finish 处兜底。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }

        // ⑥ 进入转场 = ClipReveal 一镜到底：等本页首帧 PreDraw（此刻窗口已按横屏布局、
        //    display.rotation 已落到横屏值、空壳的 SideEffect 已交出主题底色）再挂覆盖层 ——
        //    锚点矩形需要从源页竖屏坐标换算成横屏窗口坐标，换算依赖横屏 rotation。
        //    返回 true：空壳正常绘制（无内容，纯底色），下一帧起覆盖层接管。
        window.decorView.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!closing && !isFinishing && !isDestroyed) startReveal(initialMetric)
                    return true
                }
            },
        )
    }

    /**
     * 挂 ClipReveal 覆盖层（首帧 PreDraw 里调用）：
     * 锚点 = `< >` 按钮矩形（源页竖屏坐标，extra 带入）换算到本页横屏窗口坐标；
     * 内容 = 本页完整 UI（[TrendFullscreenScreen]，与原 setContent 同一棵树）。
     * 展开完成（onOpened）后覆盖层就是全屏页本体；收拢（[requestClose] → close）
     * 结束（onClosed）接续 [finishClosingSequence]。
     */
    private fun startReveal(initialMetric: Metric) {
        val source = intent?.getIntArrayExtra(EXTRA_SOURCE_BOUNDS)
        val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
        val anchorRect = source?.takeIf { it.size >= 6 }
            ?.let { mapPortraitRectToWindow(it, rotation) }
        // 锚点换算留痕：装机若"展开起点不在按钮位置"，先看这条日志的 rotation 与
        // 前后矩形对不对（错位最常见原因 = 设备旋转方向与映射分支相反，交换即可）
        Log.d(
            "TrendReveal",
            "rotation=$rotation source=${source?.toList()} anchor=$anchorRect bg=$pageBackground",
        )
        val hostHeight = window.decorView.height
        revealHolder = ClipReveal.openRevealAt(
            activity = this,
            anchorYInWindow = anchorRect?.exactCenterY() ?: (hostHeight / 2f),
            backgroundColor = pageBackground,
            // 收拢结束（onClosed）= 转场动画播完 → 接系统栏/方向/finish 收尾时序
            onClosed = { finishClosingSequence() },
            anchorRectInWindow = anchorRect,
            createContent = { ctx, _ ->
                ComposeView(ctx).apply {
                    setContent {
                        PowerMeterTheme {
                            TrendFullscreenScreen(
                                initialMetric = initialMetric,
                                closing = closing,
                                onFinish = { requestClose() },
                            )
                        }
                    }
                }
            },
        )
    }

    /**
     * 源页（竖屏窗口）矩形 → 本页（横屏窗口）窗口坐标。
     * 两侧窗口都全屏 edge-to-edge、原点都落在物理屏左上（按各自 orientation 解读），
     * 纯旋转映射（[pw] = 源竖屏窗口宽、[ph] = 源竖屏窗口高，extra 带入）：
     * - ROTATION_90（设备逆时针、顶朝左）：x' = y，y' = x → Rect(t, l, b, r)；
     * - ROTATION_270（设备顺时针、顶朝右）：x' = ph - y，y' = pw - x；
     * - 其他（直角屏 / 尚未落向横屏）：原样使用，退化为中心线兜底也不穿帮。
     * ⚠️ 映射方向若装机后锚点飘到对角，优先交换两个 rotation 分支再查其他。
     */
    private fun mapPortraitRectToWindow(src: IntArray, rotation: Int): Rect {
        val (l, t, r, b) = src
        val pw = src[4]
        val ph = src[5]
        return when (rotation) {
            android.view.Surface.ROTATION_90 -> Rect(t, l, b, r)
            android.view.Surface.ROTATION_270 -> Rect(ph - b, pw - r, ph - t, pw - l)
            else -> Rect(l, t, r, b)
        }
    }

    /**
     * 关闭全屏页 —— 一镜到底时序：
     *
     * ① [closing] = true（覆盖层里的内容同步淡出，[TrendFullscreenScreen] 的 closing 淡出
     *    与 ClipReveal 收拢的内容淡出时间窗叠加，t=0 立即有可见反馈）；
     * ② [revealHolder.close] 把覆盖层**收回到 `< >` 按钮矩形**（一镜到底的收拢半程）；
     * ③ 收拢结束（ClipReveal onClosed → [finishClosingSequence]）：恢复系统栏 + 解除横屏
     *    锁定 —— 旋转发生在空壳纯色屏之下，没有内容可重排；
     * ④ 等方向落地：`onConfigurationChanged` 接住；设备本就横握时不会触发 → 超时兜底；
     * ⑤ 方向落地后才 `finish()`（已禁用窗口过渡，见 onCreate ⑤），主页已是正确的竖屏单列。
     */
    private fun requestClose() {
        if (closing) return
        closing = true
        // 覆盖层收回：holder 已建立时 onClosed 必达（含未 beginOpen 的兜底路径）；
        // holder 尚未建立（极端：PreDraw 前就要求关闭）→ 直接走收尾
        val holder = revealHolder
        if (holder != null) holder.close() else finishClosingSequence()
    }

    /** 收拢动画播完后的收尾（原 requestClose 的 ①+②+③ 步）：系统栏 → 解锁方向 → 超时兜底 */
    private fun finishClosingSequence() {
        // closing 统一在此置位：requestClose 路径已提前设过（幂等）；返回键直接走
        // holder.close() 的路径到这里才设 —— onConfigurationChanged 的关闭分支、
        // onWindowFocusChanged 的防重放都依赖它
        closing = true
        if (closeSequenceStarted) return
        closeSequenceStarted = true
        // ① 系统栏恢复要在主页被绘制之前完成：主页顶栏高度 = safeDrawing 顶部 inset + 64dp
        //    （PowerMeterScreen 的 topBarHeight），若等窗口销毁才恢复，主页首帧会先按
        //    "无系统栏"排一次、再跳一次 —— 这是返回瞬闪的第二个来源
        NavigationBarHelper.exitImmersive(this)
        // ② 解锁方向 → 显示开始转回。旋转发生在空壳纯色屏之下，没有内容可重排
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // ③ 兜底：解锁不产生旋转（设备本就横握）时不会有 onConfigurationChanged
        window.decorView.postDelayed({ finishIfClosing() }, ORIENTATION_SETTLE_TIMEOUT_MS)
    }

    /** 方向已落地（或超时）→ 真正 finish()：转场已由覆盖层收拢播完，窗口过渡显式禁用 */
    private fun finishIfClosing() {
        if (closing && !isFinishing && !isDestroyed) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            finish()
        }
    }

    /**
     * 旋转 / 深浅色切换（声明 configChanges → 不重建 Activity）后重放沉浸设置。
     * 系统与 MIUI/HyperOS 会在配置变更后按主题默认值重放系统栏属性，可能把栏重新显示出来。
     *
     * ⚠️ 关闭中（[closing]）走另一条分支：此时系统栏**已恢复**，重放 [replayImmersive] 会把刚
     * 显示的栏再收回去；而这里的配置变更正是"方向已转回"的信号 —— 只重放窗口属性（不 hide），
     * 等一帧让窗口按新尺寸完成绘制，再执行关闭。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (closing) {
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
            window.decorView.post { finishIfClosing() }
            return
        }
        replayImmersive()
        // 延迟一帧兜底：确保系统重放之后再压一次
        window.decorView.post {
            if (!isFinishing && !isDestroyed) replayImmersive()
        }
    }

    /** 从多任务/锁屏回到前台时系统可能重新显示系统栏，重新隐藏（关闭中不再隐藏） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !closing) replayImmersive()
    }

    private fun replayImmersive() {
        NavigationBarHelper.setupEdgeToEdge(
            this,
            lightStatusBar = !isNightMode(),
            immersive = true,
        )
        NavigationBarHelper.enterImmersive(this)
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

@Composable
private fun TrendFullscreenScreen(
    initialMetric: Metric,
    /** true = 正在关闭：整页内容淡到主题底色（见 Activity 的 requestClose） */
    closing: Boolean,
    onFinish: () -> Unit,
) {
    val corner = LocalCornerRadius.current
    val cardShape = remember(corner) { RoundedCornerShape(corner) }
    // 数据源：导入态优先。与主页面同一判断口径，保证从卡片进全屏看到的是同一份数据
    val imported by ImportedSeries.samples.collectAsState()
    // 实时序列与主页面同口径：订阅版本号 → 按需取一次环形缓冲快照（档二-1）
    val liveVersion by SamplingService.sampleVersion.collectAsState()
    val live = remember(liveVersion) { SamplingService.snapshot() }
    val samples = if (imported.isNotEmpty()) imported else live
    val context = LocalContext.current

    // 指标多选：横屏叠加对比多条曲线。至少保留一条（点最后一条不可取消），
    // 否则图表会空掉、用户还得自己找回来
    var selected by rememberSaveable { mutableStateOf(listOf(initialMetric.name)) }
    // 指标 tab 与主页面同一口径（PMIC 温度只在真 root 机器上出现），勿在此另写一份过滤
    val tabs = rememberAvailableMetrics()
    // 选中项一律与 tab 集合求交：既兜住"存档里保存了本机当前不可用的指标"（root 结论变化后
    // 重进本页），也兜住"至少保留一条"—— 交集为空时取第一条（恒为功率）
    val metrics = selected.mapNotNull { name -> runCatching { Metric.valueOf(name) }.getOrNull() }
        .filter { it in tabs }
        .ifEmpty { listOf(tabs.first()) }

    // 曲线序列必须缓存：`toSeries` 是 O(n) 构建（实时态 n 最多 SampleStore.CAPACITY = 7200，导入态 20000），
    // 而关闭时的淡出动画（animateFloatAsState，120ms）会逐帧驱动重组 —— 不缓存则每帧重建
    // 全部序列。键里的 samples 在同一份数据下是同一实例，List.equals 走引用快路径 O(1)。
    //
    // 颜色也必须是键的一部分：POWER 的默认色跟随主题 `primary`，且用户可在本页改色。
    // 取色复用 ColorPickerDialog 的 Metric.seriesColor，避免默认色值在这里再写一份。
    val customColors by ChartColors.colors.collectAsState()
    val themePrimary = MaterialTheme.colorScheme.primary
    val seriesColors = remember(metrics, customColors, themePrimary) {
        metrics.map { m -> m.seriesColor(customColors, themePrimary) }
    }
    // 曲线标签在这里解析：remember 的计算 lambda 不是 Composable 作用域，调不了 stringResource，
    // 而图例 / 读数气泡又是在非 Composable 的 forEachIndexed 里读 ChartSeries.label 的
    val seriesList = remember(samples, metrics, seriesColors, context) {
        seriesColors.mapIndexed { i, color ->
            metrics[i].toSeries(samples, color, context.getString(metrics[i].labelRes))
        }
    }
    // 非空 = 颜色面板打开中，值为正在编辑的指标
    var colorTarget by remember { mutableStateOf<Metric?>(null) }
    // 多曲线叠加时为 true：点颜色按钮先弹「选曲线」列表，挑完再开颜色面板
    // （对齐 SportLink showColorPickerForSelection：单列直接开面板，多列先选曲线）
    var colorSelectOpen by remember { mutableStateOf(false) }
    val chartState = remember { TrendChartState() }

    // 关闭中：内容淡到主题底色（DialogBackdropHost 源层铺满主题底色，故淡出即"整页变纯色"）。
    // 纯色屏没有可重排的内容 —— 紧随其后的显示方向旋转（本页解锁方向后转回竖屏）
    // 因此完全不可见；窗口尺寸变化时纯色只是重新铺一次。
    val contentAlpha by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(durationMillis = CLOSE_FADE_MS),
        label = "trendFullscreenCloseFade",
    )

    // DialogBackdropHost：提供 miuix backdrop 源 + Haze 源（页面内容层）与弹窗浮层 slot 的
    // 「宿主 + slot」结构 —— 居中玻璃对话框（GlassDialog）作为源兄弟渲染，满足 miuix / Haze
    // 「同窗口兄弟」铁律；源层内部铺满主题不透明底色，保证模糊采样有像素。
    // 背景铺满全屏（含系统栏与挖孔区）——真沉浸的前提：底色延伸到栏下。
    DialogBackdropHost {
        Column(
            Modifier
                .fillMaxSize()
                // 关闭淡出：只作用于内容，底色由外层 Box 保留（淡出后即"整页纯色"）
                .alpha(contentAlpha)
                // 避开摄像头（竖屏顶部中央打孔 / 横屏左右侧打孔，全边避让一次覆盖）。
                // 只避 cutout，**不**避 systemBars：系统栏已隐藏，内容必须延伸到屏幕最底，
                // 这才是真沉浸；用 safeDrawing 全边避让只会把系统栏区域换成一条背景色带。
                .windowInsetsPadding(WindowInsets.displayCutout)
                // padding 无「horizontal + top + bottom」混合重载，须拆成两次调用
                .padding(horizontal = 12.dp)
                .padding(vertical = 12.dp),
        ) {
            Surface(
                shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // ⚠️ 同趋势卡：外层**只留垂直 padding**，水平内边距下放到各子节点，
                // 让指标 tab 行的滚动视口撑满到卡片左右边框；否则 chip 会在距边框 16dp
                // 处就被裁掉（2026-09-21 用户报告）
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(vertical = 16.dp),
                ) {
                    // 标题行：左侧关闭胶囊（口径对齐 SportLink 分段全屏页
                    // SegmentFullscreenActivity.kt:221-235）+ 居中标题 + 右侧颜色胶囊
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Box(modifier = Modifier.align(Alignment.CenterStart)) {
                            // 回调携带按钮矩形参数，但关闭页不需要展开起点，忽略之
                            FullscreenPillButton(text = "✕", onClick = { _ -> onFinish() })
                        }
                        Box(
                            modifier = Modifier.align(Alignment.Center),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.title_trend),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                            // 颜色胶囊底色走莫奈取色（2026-09-26，与竖屏趋势卡同一口径
                            // PowerMeterScreen.colorButtonBackground）：支持莫奈的机器 =
                            // Material You secondaryContainer（随壁纸），否则回落固定淡紫；
                            // 不随首条曲线色。曲线色改由 tab 上的色点表达 —— 见下方 FilterChip 的 leadingIcon。
                            val primary = metrics.first()
                            ChartPillButton(
                                text = stringResource(R.string.action_color),
                                background = colorButtonBackground(),
                                contentColor = colorButtonContent(),
                                // 多曲线叠加：先弹「选曲线」列表，挑完再开颜色面板；
                                // 仅一条曲线：直接开颜色面板（对齐 SportLink showColorPickerForSelection）
                                onClick = {
                                    if (metrics.size > 1) colorSelectOpen = true
                                    else colorTarget = primary
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 指标 tab 行必须是**横向滚动**容器（口径对齐 SportLink ChartSection.kt:115-135，
                    // 与趋势卡 TrendCard 同一写法）：普通 Row 按剩余宽度测量子项，竖屏下末位
                    // 「PMIC 温度」被压到近 0 宽 + label 逐字换行 → 撑成竖排细条（2026-09-21 用户报告）。
                    // 首端 16dp 写在 horizontalScroll **之后** ⇒ 属滚动内容、不收窄视口；
                    // 视口右边界 = 卡片边框，末位 chip 滑到卡缘才被裁切
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { m ->
                            val on = m in metrics
                            val chipColor = rememberMetricColor(m)
                            FilterChip(
                                selected = on,
                                onClick = {
                                    selected = when {
                                        // 已选且不止一条 → 取消
                                        on && metrics.size > 1 -> selected - m.name
                                        // 已选且只剩一条 → 保持（不允许取消到空）
                                        on -> selected
                                        else -> selected + m.name
                                    }
                                    // 指标集合变了 → 清掉缩放窗口，避免旧窗口落在新曲线的
                                    // 空档里显示成一段空白
                                    chartState.reset()
                                },
                                // 单行硬约束：宽度受限时只会被截断，绝不逐字换行撑高 chip
                                label = { Text(m.label(), maxLines = 1, softWrap = false) },
                                // 色点直接标出该指标当前的曲线色，叠加时不用去猜哪条是哪个
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(chipColor, RoundedCornerShape(50)),
                                    )
                                },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor =
                                        MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (samples.size < 2) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.empty_no_samples),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        TrendChart(
                            times = remember(samples) { samples.map { it.timeMillis } },
                            series = seriesList,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                            // 叠加多条时收细线宽，否则 4 条 8px 的线糊成一片
                            strokeWidth = if (metrics.size > 1) 5f else 8f,
                            axisLabelSp = 13.sp,
                            state = chartState,
                        )
                    }
                }
            }
        }

        // 关闭中整页已是纯色底，浮层不该继续挂在上面（本段位于 DialogBackdropHost 内，
        // 弹层经 GlassDialog→DialogOverlay 注册到宿主 slot，与采样源层同窗口兄弟）
        val sheetTarget = if (closing) null else colorTarget
        // 颜色面板 = 底部弹层（ModalBottomSheet），自带滑入/滑出动画，
        // 不需要 AnimatedVisibility 包裹（居中弹窗才需要，见下方选曲线层）
        sheetTarget?.let { target ->
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

    // 多曲线叠加时的「选曲线」浮层（关闭中同样不挂）：挑完某条 → 关本层、开颜色面板给该条改色
        val selectTarget = if (closing) null else if (colorSelectOpen) metrics else null
        AnimatedVisibility(
            visible = selectTarget != null,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(150)) +
                scaleOut(targetScale = 0.92f, animationSpec = tween(150)),
        ) {
            CurveSelectSheet(
                metrics = metrics,
                onSelect = { m ->
                    colorSelectOpen = false
                    colorTarget = m
                },
                onDismiss = { colorSelectOpen = false },
            )
        }
    }   // DialogBackdropHost
}
