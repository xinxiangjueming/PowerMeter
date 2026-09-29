package com.chen.powermeter.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chen.powermeter.R
import com.chen.powermeter.data.ImportedSeries
import com.chen.powermeter.service.SamplingService
import com.chen.powermeter.ui.common.AppCard
import com.chen.powermeter.ui.theme.LocalCornerRadius
import com.chen.powermeter.ui.theme.PowerMeterTheme
import com.chen.powermeter.util.AppTransitions
import com.chen.powermeter.util.NavigationBarHelper

/**
 * 横屏停留退出时「还栏 → 收拢」的等待时长（ms，SportLink ChartFullscreenActivity
 * barSettleDelayMs 同口径）：半透明窗口下的主页被连带排成无栏全高布局，还栏后要等它
 * 重排回"有栏"终态，收拢前向活性源条目现取的锚点矩形才是还栏后位置 —— 否则收拢落点
 * 与真实卡片差一个系统栏高度，finish 恢复后主页再跳一次（"还栏防列表跳动"）。
 */
private const val BAR_SETTLE_DELAY_MS = 300L

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
 * **关闭时序（2026-09-30 用户定案：按设备物理朝向分流，判据平放加固）**：✕ / 返回手势
 * 经 [requestClose] 全部路由进 [finish]：设备横握/平放（finish 后显示停留横屏）= 还栏
 * 等 300ms → 收拢前向活性源条目**现取**趋势卡当前矩形/新截图再收拢（终点 = 主页横屏
 * 两列布局里趋势卡的真实位置，无还栏跳动）；明确竖握 = **不收拢**，主题窗口动画滑出
 * （收拢播在被钉住的横屏窗口里"先强制横屏再旋转"已被用户否决）。进入/退出的窗口内
 * 转场都由 AppTransitions 的 ClipReveal 裁剪承担（同方向首帧直开；跨方向垫遮罩 + 轮询
 * 源页重排 + 现拍新鲜锚点再展开），窗口滑动动画在一镜到底路径双向压 0。
 * 打开方向不需要淡出铺底处理：本页是**独立窗口**，`setRequestedOrientation` 的效果在启动
 * 窗口（StartingWindow）底下就生效了，首帧即横屏。
 */
class TrendFullscreenActivity : ComponentActivity() {

    /**
     * 应用当前深浅（2026-09-28 系统深浅色切换动画铺开）：configChanges 含 uiMode 后系统
     * 切换不再重建，深浅由 onCreate 初值 / onConfigurationChanged / onResume 兜底驱动；
     * 变化经 PowerMeterTheme 闸门播圆孔揭露动画（ThemeTransition）。
     */
    private val darkThemeState by lazy { mutableStateOf(isNightMode()) }

    companion object {
        /** 进入时的指标选择（Metric.name）；缺省回退 POWER */
        const val EXTRA_METRIC = "extra_metric"

        /**
         * 打开趋势全屏页。
         *
         * 进入动画 = **AppTransitions 一镜到底**（2026-09-29 起进出全程照抄 SportLink
         * 图表卡）：[capture]（趋势卡截图，源页经 [AppTransitions.capture] 产出，锚点 =
         * 整张趋势卡，2026-09-28：原为 `< >` 胶囊，撑成整页时四边插值失衡）经 Handoff
         * 单例交接，本页 onPostCreate 把页面根包进 ClipRevealLayout —— 横屏主页（同方向）
         * 首帧从趋势卡矩形四向撑开；竖屏主页（跨方向）先垫不透明页面底色遮罩 + 整窗
         * 零裁剪，等旋转沉降且源页按新旋转重排完成后**现拍**趋势卡的新鲜截图（真实横屏
         * 矩形 + 当前主题像素 + 文字层）再撑开（详见 AppTransitions.installWindowTransform）。
         * capture == null 时降级为主题窗口滑动。
         * 窗口滑动动画双向压 0（见 onCreate ⑤），窗口内 ClipReveal 是唯一动画。
         */
        // internal：签名含 internal 的 Metric，public 会触发「public function exposes
        // its internal parameter type」；调用方（PowerMeterScreen）同模块，可见性足够
        internal fun launch(context: Context, metric: Metric, capture: AppTransitions.Capture? = null) {
            val intent = Intent(context, TrendFullscreenActivity::class.java)
                .putExtra(EXTRA_METRIC, metric.name)
            val act = context as? Activity
            if (act != null) AppTransitions.launchWithTransform(act, intent, capture)
            else context.startActivity(intent)
        }
    }

    /**
     * 正在关闭（Compose 可读的 state）。
     *
     * true = 返回收拢动画已/正在播（覆盖层收回锚点矩形）。同时作为弹层卸载闸门
     * （见 TrendFullscreenScreen 的 sheetTarget/selectTarget）。
     */
    private var closing by mutableStateOf(false)

    /**
     * 退出时序已启动（含还栏后的 300ms 等待期）：requestClose 的幂等护栏。
     * 与 [closing] 分开 —— 等待期内容**不能**开始淡出（SportLink 同款：还栏等待期间
     * 全屏页保持完整可见），期间系统栏已恢复，[replayImmersive] 类重放也必须跳过
     * （否则把刚还的栏再收回去）。
     */
    private var closeRequested = false

    /** 还栏动作是否已做（SportLink exitBarRestoreDone 同款）：finish() 会被收拢完成后的
     *  finishNow 重入，本标记保证还栏 + 300ms 等待只做一次 */
    private var exitBarRestoreDone = false

    // ── 退出朝向判定（SportLink ChartFullscreenActivity 同款分流 + 平放加固）────────
    // 本页恒锁 sensorLandscape：resources.orientation 恒为横屏，判不出"finish 后显示
    // 将转回的朝向"（自动旋转开 = 传感器方向；关 = 用户锁定旋转），必须跟踪加速度计。
    //
    // ⚠️ 平放加固（2026-09-30）：看横屏全屏的自然持机姿势**接近水平**，加速度计在该
    // 姿势读数 UNKNOWN/抖动 —— 只记象限的话会卡在进场时的初值（竖进 = 0），横握左滑
    // 被误判成"竖握返回"走了滑出（用户报的"横屏左滑直接闪"）。UNKNOWN 单独记平放
    // 标记：设备平放 = 显示不会自发转回竖屏 → 按"停留横屏 = 收拢"处理。
    //
    // 不分流会怎样（2026-09-30 二轮教训）：竖握返回也收拢 —— 收拢动画播在被本页钉住
    // 的横屏窗口里（内容横着），finish 后显示再转回竖屏 = "先强制横屏再旋转"，被用户
    // 否决。分流必须保留，判据必须可靠。

    /** 最近一次有效加速度计读数的象限（0/90/180/270）；null = 尚未读到过有效读数 */
    private var sensorQuadrant: Int? = null

    /** 设备是否接近水平（读数 UNKNOWN）：该姿势下显示不会自发旋转 → 停留横屏 */
    private var sensorFlat = false

    // ⚠️ lazy 而非属性初始化器：属性初始化在 Activity 构造期执行、早于 onCreate()，
    // OrientationEventListener 构造内 getSystemService 会抛 "System services not
    // available to Activities before onCreate()"（SportLink 真机实锤 2026-09-29）；
    // lazy 到 onResume 首次 enable 时才构造，那时系统服务已就绪
    private val deviceOrientationListener: OrientationEventListener by lazy {
        object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    sensorFlat = true
                    return
                }
                sensorFlat = false
                sensorQuadrant = ((orientation + 45) / 90 * 90) % 360
            }
        }
    }

    /**
     * finish 后显示将回到的朝向是否横屏（= 主页退出后停留的布局方向）：
     * - 自动旋转关：用户锁定旋转（USER_ROTATION）——确定值；
     * - 自动旋转开：设备平放（[sensorFlat]，显示不会自发旋转）或传感器明确横象限
     *   （[sensorQuadrant] 90/270）= 停留横屏；明确竖象限 = 转回竖屏。
     * 读数异常 → false（落回竖屏返回 = 滑出，SportLink 同款保守兜底）。
     */
    private fun exitWillBeLandscape(): Boolean {
        return try {
            if (Settings.System.getInt(
                    contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1,
                ) == 1
            ) {
                sensorFlat || sensorQuadrant == 90 || sensorQuadrant == 270
            } else {
                when (Settings.System.getInt(contentResolver, Settings.System.USER_ROTATION, 0)) {
                    Surface.ROTATION_90, Surface.ROTATION_270 -> true
                    else -> false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

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
            PowerMeterTheme(darkTheme = darkThemeState.value) {
                // 正常渲染全屏页 —— 转场不是覆盖层插入，而是 AppTransitions.installWindowTransform
                // （onPostCreate）把**这棵页面根**包进 ClipRevealLayout、横屏落地后从趋势卡
                // 矩形撑开（SportLink TrackActivity 同款结构：包根而非覆盖层，页面即本体）
                TrendFullscreenScreen(
                    initialMetric = initialMetric,
                    closing = closing,
                    onFinish = { requestClose() },
                )
            }
        }

        // 系统返回手势 / 返回键必须与 ✕ 走同一套关闭时序（requestClose → finish：
        // 还栏 + 一镜到底收拢；放行给默认实现会绕过整套时序直接结束）
        onBackPressedDispatcher.addCallback(this) { requestClose() }

        // ⑤ 窗口滑动动画双向压 0（一镜到底 = 窗口内 ClipReveal 是唯一动画）：主题
        //    activityOpen* 的整窗滑入会与四向撑开叠播，把展开前半段吞掉（2026-09-26 装机
        //    实测）；activityClose* 的滑出会把收拢末帧（唯一可见内容 = 按钮截图）整窗滑出。
        //    API 34+ 在此注册；更早版本由源页 overridePendingTransition(0,0)（launch →
        //    launchWithTransform 内）与 CollapseHost.finishNow 兜底。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    /**
     * 跨 Activity 一镜到底装配（SportLink ChartFullscreenActivity 同款时机与机制）：
     * 有新鲜 Handoff（从趋势卡进入）→ 页面根包进 ClipRevealLayout —— 同方向（横屏主页）
     * 首帧从趋势卡矩形四向撑开；跨方向（竖屏主页 → 本页锁横屏）垫不透明页面底色遮罩 +
     * 整窗零裁剪 + 逐帧轮询源页按新旋转重排，现拍新鲜锚点（真实横屏矩形 + 当前主题像素）
     * 后再展开；超时退化全宽中心线。无（通知/过期）→ no-op，页面普通显示 + 主题窗口动画。
     */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // landscapeTarget = 本页锁横屏（onCreate 已设）：跨方向判定据此不走"当前配置"
        // —— 配置在装配时点仍是竖屏（旋转未落地），详见 AppTransitions.installWindowTransform
        AppTransitions.installWindowTransform(this, landscapeTarget = true)
    }

    /**
     * 关闭全屏页 —— 全部路由进 [finish]，控制流照抄 SportLink ChartFullscreenActivity
     * （2026-09-29 用户定案"进入和退出都完全照搬"）：
     *
     * **横屏停留退出**（[exitWillBeLandscape] = true，设备横握，finish 后显示仍横屏、
     * 主页停留横屏两列布局 = 最终态）：
     * ⓪ 首次 finish：还系统栏 → 等 [BAR_SETTLE_DELAY_MS]（"还栏防列表跳动"：半透明
     *    窗口下的主页被连带排成无栏全高布局，收拢前现取的锚点必须是还栏后位置；
     *    等待期本页内容完整可见、不淡出）；等待期内用户可能翻回竖握 → 重评口径同
     *    SportLink 每次 finish 都重评；
     * ① 再次 finish：[closing] 置位 → [AppTransitions.collapseAndFinish](relocateAnchor
     *    = true) 收拢 —— 收拢前向活性源条目**现取**趋势卡当前矩形/新截图，终点与真实
     *    卡片逐像素重合（跨方向会话的进场锚点已在旋转沉降后现拍过一次，此处再取的是
     *    还栏后的最终位置）；
     * ② 收拢结束 finishNow → finish → 无宿主可收 → `super.finish()`，无额外等待。
     *
     * **竖屏返回退出**（设备竖握 / 判定失败）：**不收拢**（SportLink 定案：显示即将
     * 转回竖屏，任何横屏坐标系里的收拢终点在转回后必然失配）→ `super.finish()` 主题
     * 窗口动画滑出（Theme.PowerMeter.Transitions 继承 ActivitySlideWindowAnimation，
     * 300ms 侧滑），显示方向随窗口关闭交还系统（解锁动作不再需要 —— 方向请求随
     * Activity 销毁失效）。
     *
     * 无收拢宿主（无 Handoff 降级启动）时横屏路径直接落 `super.finish()`，同 SportLink。
     */
    private fun requestClose() {
        if (closing || closeRequested) return
        closeRequested = true
        finish()
    }

    /**
     * 关闭全屏页 —— 按设备物理朝向分流（SportLink ChartFullscreenActivity 同款，且
     * 判据经平放加固，见 [exitWillBeLandscape]）：
     *
     * **停留横屏**（设备横握/平放，finish 后显示仍横屏、主页停留横屏两列布局 = 最终态）：
     * ⓪ 首次 finish：还系统栏 → 等 [BAR_SETTLE_DELAY_MS]（"还栏防列表跳动"：半透明
     *    窗口下的主页被连带排成无栏全高布局，收拢前现取的锚点必须是还栏后位置；
     *    等待期本页内容完整可见、不淡出）；
     * ① 再次 finish：[closing] 置位 → [AppTransitions.collapseAndFinish](relocateAnchor
     *    = true) 收拢 —— 收拢前向活性源条目**现取**趋势卡当前矩形/新截图（共享槽位
     *    修复后读到的必是主页横屏布局的真实卡片，2026-09-30）；
     * ② 收拢结束 finishNow → finish → 无宿主可收 → `super.finish()`（关闭转场已压 0）。
     *
     * **转回竖屏**（设备明确竖握）：**不收拢** —— 收拢动画播在被本页钉住的横屏窗口里
     * （内容横着），finish 后显示再转回竖屏 = "先强制横屏再旋转"（2026-09-30 用户
     * 否决）→ `super.finish()` 主题窗口动画滑出，方向随窗口关闭交还系统。
     *
     * 无收拢宿主（无 Handoff 降级启动）时停留横屏路径直接落 `super.finish()`。
     */
    override fun finish() {
        // 退场交棒（2026-09-28 二轮）：把当前深浅留给即将显示的源页（主页），让它在首帧
        // 绘制之前就切成目标主题（口径同 FrameDetailActivity，消费方 MainActivity.onStart）
        ThemeTransition.noteExitTheme(isNightMode())
        val landscapeExit = exitWillBeLandscape()
        Log.i(
            "PowerMeterTransit",
            "trend exit split: landscapeExit=$landscapeExit quadrant=$sensorQuadrant flat=$sensorFlat",
        )
        if (!exitBarRestoreDone && landscapeExit) {
            // ⓪ 还系统栏 + 等主页按"有栏"重排落定（SportLink exitBarRestoreDone 同款：
            // finish() 会被收拢完成后的 finishNow 重入，本标记保证还栏只做一次）
            exitBarRestoreDone = true
            NavigationBarHelper.exitImmersive(this)
            window.decorView.postDelayed({
                if (!isDestroyed && !isFinishing) finish()
            }, BAR_SETTLE_DELAY_MS)
            return
        }
        if (landscapeExit) {
            // ① 停留横屏：收拢（收拢前现取锚点当前位置，语义见 collapseAndFinish）
            closing = true
            if (AppTransitions.collapseAndFinish(this, relocateAnchor = true)) return
            super.finish()
            return
        }
        // ② 转回竖屏：不收拢，主题窗口动画滑出（SportLink 同款）
        super.finish()
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
        // 深浅色切换（configChanges 含 uiMode → 不重建）在这里驱动 darkThemeState，
        // 经 PowerMeterTheme 闸门播圆孔揭露动画（2026-09-28）
        val dark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (darkThemeState.value != dark) {
            // 送达时本页可能还没 onStart（后台错过的变化随返回事务补发）：不在这里判
            // 静默（2026-09-29 用户定案，同 MainActivity）——由 PowerMeterTheme 闸门在
            // 首帧判定：宿主已 RESUMED → 播圆孔揭露；仍 paused 可见 → 闸门静默落地
            darkThemeState.value = dark
        }
        if (closing || closeRequested) {
            // 关闭中/还栏等待期：系统与 MIUI/HyperOS 会在配置变更后按主题默认值重放
            // 系统栏属性，这里只重放窗口属性（不 hide）——否则把刚还的栏再收回去
            // （等待期）或把收拢所需的"有栏"终态打回无栏（收拢中）。180° 翻面（横屏↔
            // 横屏，orientation 不变）也走这里：收拢中翻面的终点失配为已知遗留（批次
            // 五十五），还栏等待期翻面由收拢前的现拍兜底
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
            return
        }
        replayImmersive()
        // 延迟一帧兜底：确保系统重放之后再压一次
        window.decorView.post {
            if (!isFinishing && !isDestroyed) replayImmersive()
        }
    }

    override fun onResume() {
        super.onResume()
        // 退出朝向判定的数据源：前台期间持续跟踪设备物理朝向（见 deviceOrientationListener）
        deviceOrientationListener.enable()
        // 后台切换深浅兜底（configChanges 含 uiMode 的已知坑：后台时 ViewRootImpl 不分发
        // 配置）——回前台读 Resources 最新值；不静默（2026-09-29 用户定案，同 MainActivity）：
        // 状态落地后由 PowerMeterTheme 闸门在 onResume 之后的首帧播圆孔揭露动画
        if (darkThemeState.value != isNightMode()) {
            darkThemeState.value = isNightMode()
        }
    }

    override fun onPause() {
        deviceOrientationListener.disable()
        super.onPause()
    }

    /** 从多任务/锁屏回到前台时系统可能重新显示系统栏，重新隐藏（关闭中/退出等待期不再隐藏） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !closing && !closeRequested) replayImmersive()
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
    /** true = 正在关闭（横屏停留退出的收拢中）：仅用于卸载弹层，内容淡出由收拢交叉窗承担 */
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
    // 实时采样每秒都会改 samples —— 不缓存则每帧重建全部序列。
    // 键里的 samples 在同一份数据下是同一实例，List.equals 走引用快路径 O(1)。
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
    // 关闭中的内容退场 = 收拢动画自身的交叉淡变窗（contentAlphaOut，AppTransitions 驱动）
    // / 竖屏返回的主题窗口滑出 —— 本页不再叠一层自己的淡出（2026-09-29 照抄 SportLink：
    // SportLink 关闭时无内容淡出；旧淡出服务的"收拢后转屏在纯色屏之下"场景已随竖屏
    // 路径不收拢而消失）。[closing] 仍用于卸载弹层（见下方 sheetTarget/selectTarget）。

    // DialogBackdropHost：提供 miuix backdrop 源 + Haze 源（页面内容层）与弹窗浮层 slot 的
    // 「宿主 + slot」结构 —— 居中玻璃对话框（GlassDialog）作为源兄弟渲染，满足 miuix / Haze
    // 「同窗口兄弟」铁律；源层内部铺满主题不透明底色，保证模糊采样有像素。
    // 背景铺满全屏（含系统栏与挖孔区）——真沉浸的前提：底色延伸到栏下。
    DialogBackdropHost {
        Column(
            Modifier
                .fillMaxSize()
                // 避开摄像头（竖屏顶部中央打孔 / 横屏左右侧打孔，全边避让一次覆盖）。
                // 只避 cutout，**不**避 systemBars：系统栏已隐藏，内容必须延伸到屏幕最底，
                // 这才是真沉浸；用 safeDrawing 全边避让只会把系统栏区域换成一条背景色带。
                .windowInsetsPadding(WindowInsets.displayCutout)
                // padding 无「horizontal + top + bottom」混合重载，须拆成两次调用
                .padding(horizontal = 12.dp)
                .padding(vertical = 12.dp),
        ) {
            AppCard(
                shape = cardShape,
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
                            // ✕ 无需锚点截图，但 register 的锚点无消费者时按 TTL 自动过期，无害
                            FullscreenPillButton(text = "✕", onClick = onFinish)
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
