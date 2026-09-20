package com.chen.powermeter.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.alpha
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
 * 内容先淡到主题底色（纯色屏没有可重排的内容）→ 解锁方向（旋转发生在纯色之下）→
 * 等方向落地（`onConfigurationChanged` 或超时兜底）→ 才 `finish()` 交给主题的滑窗动画。
 * 打开方向不需要这套处理：本页是**独立窗口**，`setRequestedOrientation` 的效果在启动窗口
 * （StartingWindow）底下就生效了，首帧即横屏。
 */
class TrendFullscreenActivity : ComponentActivity() {

    companion object {
        /** 进入时的指标选择（Metric.name）；缺省回退 POWER */
        const val EXTRA_METRIC = "extra_metric"

        /**
         * 打开趋势全屏页。
         *
         * 过渡动画由**主题**的 `android:windowAnimationStyle` 提供（四向水平滑动，
         * 见 res/values/themes.xml 的 ActivitySlideWindowAnimation），此处不再调
         * overrideActivityTransition / overridePendingTransition ——
         * 主题声明一处即覆盖新旧 API，也省掉两套分支。
         *
         * @param context 调用方 Context（仅用于 startActivity）
         */
        // internal：签名含 internal 的 Metric，public 会触发「public function exposes
        // its internal parameter type」；调用方（PowerMeterScreen）同模块，可见性足够
        internal fun launch(context: Context, metric: Metric) {
            context.startActivity(
                Intent(context, TrendFullscreenActivity::class.java)
                    .putExtra(EXTRA_METRIC, metric.name),
            )
        }
    }

    /**
     * 正在关闭（Compose 可读的 state）。
     *
     * true = 已解除方向锁定、内容已/正在淡到主题底色，等显示方向转回落地后再真正 `finish()`。
     * 同时作为**幂等护栏**：✕ 与系统返回手势可能在极短时间内都触发一次，重复执行会让
     * 超时兜底与 `onConfigurationChanged` 两条路径各调一次 `finish()`。
     */
    private var closing by mutableStateOf(false)

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
                TrendFullscreenScreen(
                    initialMetric = initialMetric,
                    closing = closing,
                    onFinish = { requestClose() },
                )
            }
        }

        // 系统返回手势 / 返回键必须走同一套关闭时序：放行给默认实现（直接 finish()）时，
        // 本页会在显示仍是横屏的状态下被移除 → 主页先按横屏两列画出来再翻回竖屏（返回瞬闪）
        onBackPressedDispatcher.addCallback(this) { requestClose() }
    }

    /**
     * 关闭全屏页 —— C+ 时序，四步缺一不可（原因见类注释）：
     *
     * ① 先恢复系统栏（[NavigationBarHelper.exitImmersive]）；
     * ② 再解除横屏锁定，让显示在"纯色屏"之下转回竖屏；
     * ③ 等方向落地：`onConfigurationChanged` 接住；设备本就横握时不会触发 → 超时兜底；
     * ④ 方向落地后才 `finish()`，此时交给主题的四向滑窗动画，主页已是正确的竖屏单列。
     *
     * t=0 就有可见反馈（第 ①+② 步同时触发内容淡出），不会让 ✕ 看起来点了没反应。
     */
    private fun requestClose() {
        if (closing) return
        closing = true
        // ① 系统栏恢复要在主页被绘制之前完成：主页顶栏高度 = safeDrawing 顶部 inset + 64dp
        //    （PowerMeterScreen 的 topBarHeight），若等窗口销毁才恢复，主页首帧会先按
        //    "无系统栏"排一次、再跳一次 —— 这是返回瞬闪的第二个来源
        NavigationBarHelper.exitImmersive(this)
        // ② 解锁方向 → 显示开始转回。旋转发生在上一步那块纯色屏之下，没有内容可重排
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // ③ 兜底：解锁不产生旋转（设备本就横握）时不会有 onConfigurationChanged
        window.decorView.postDelayed({ finishIfClosing() }, ORIENTATION_SETTLE_TIMEOUT_MS)
    }

    /** 方向已落地（或超时）→ 真正 finish()，交给主题的 activityClose* 滑窗动画 */
    private fun finishIfClosing() {
        if (closing && !isFinishing && !isDestroyed) finish()
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
    val metrics = selected.mapNotNull { name -> runCatching { Metric.valueOf(name) }.getOrNull() }
        .ifEmpty { listOf(initialMetric) }

    // 曲线序列必须缓存：`toSeries` 是 O(n) 构建（实时态 n 最多 3600，导入态 20000），
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
    val chartState = remember { TrendChartState() }

    val bg = MaterialTheme.colorScheme.background

    // 关闭中：内容淡到主题底色（bg 由外层 Box 铺满，故淡出即"整页变纯色"）。
    // 纯色屏没有可重排的内容 —— 紧随其后的显示方向旋转（本页解锁方向后转回竖屏）
    // 因此完全不可见；窗口尺寸变化时纯色只是重新铺一次。
    val contentAlpha by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(durationMillis = CLOSE_FADE_MS),
        label = "trendFullscreenCloseFade",
    )

    // 背景铺满全屏（含系统栏与挖孔区）——真沉浸的前提：底色延伸到栏下，
    // 而不是把整块内容用 insets 顶开
    Box(Modifier.fillMaxSize().background(bg)) {
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
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    // 标题行：左侧关闭胶囊（口径对齐 SportLink 分段全屏页
                    // SegmentFullscreenActivity.kt:221-235）+ 居中标题 + 右侧颜色胶囊
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.align(Alignment.CenterStart)) {
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
                            // 多选叠加时颜色胶囊编辑「第一条」曲线 —— 图例上带色点的指标
                            // 才需要改色，而第一条是必然存在的那条（见上面 metrics 的非空兜底）
                            val primary = metrics.first()
                            val primaryColor = rememberMetricColor(primary)
                            ChartPillButton(
                                text = stringResource(R.string.action_color),
                                background = primaryColor,
                                contentColor = onColorFor(primaryColor),
                                onClick = { colorTarget = primary },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric.entries.forEach { m ->
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
                                label = { Text(m.label()) },
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
                                .weight(1f),
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
                            samples = samples,
                            series = seriesList,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            // 叠加多条时收细线宽，否则 4 条 8px 的线糊成一片
                            strokeWidth = if (metrics.size > 1) 5f else 8f,
                            axisLabelSp = 13.sp,
                            state = chartState,
                        )
                    }
                }
            }
        }
    }

    // 关闭中整页已是纯色底，浮层不该继续挂在上面
    val sheetTarget = if (closing) null else colorTarget
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
}
