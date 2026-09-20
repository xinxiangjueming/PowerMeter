package com.kongj.powermeter.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongj.powermeter.data.ImportedSeries
import com.kongj.powermeter.service.SamplingService
import com.kongj.powermeter.ui.theme.LocalCornerRadius
import com.kongj.powermeter.ui.theme.PowerMeterTheme
import com.kongj.powermeter.util.NavigationBarHelper

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
                    onFinish = { finish() },
                )
            }
        }
    }

    /**
     * 旋转 / 深浅色切换（声明 configChanges → 不重建 Activity）后重放沉浸设置。
     * 系统与 MIUI/HyperOS 会在配置变更后按主题默认值重放系统栏属性，可能把栏重新显示出来。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        replayImmersive()
        // 延迟一帧兜底：确保系统重放之后再压一次
        window.decorView.post {
            if (!isFinishing && !isDestroyed) replayImmersive()
        }
    }

    /** 从多任务/锁屏回到前台时系统可能重新显示系统栏，重新隐藏 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) replayImmersive()
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
    onFinish: () -> Unit,
) {
    val corner = LocalCornerRadius.current
    val cardShape = remember(corner) { RoundedCornerShape(corner) }
    // 数据源：导入态优先。与主页面同一判断口径，保证从卡片进全屏看到的是同一份数据
    val imported by ImportedSeries.samples.collectAsState()
    val live by SamplingService.samples.collectAsState()
    val samples = if (imported.isNotEmpty()) imported else live
    val context = androidx.compose.ui.platform.LocalContext.current

    // 指标多选：横屏叠加对比多条曲线。至少保留一条（点最后一条不可取消），
    // 否则图表会空掉、用户还得自己找回来
    var selected by rememberSaveable { mutableStateOf(listOf(initialMetric.name)) }
    val metrics = selected.mapNotNull { name -> runCatching { Metric.valueOf(name) }.getOrNull() }
        .ifEmpty { listOf(initialMetric) }
    // 非空 = 颜色面板打开中，值为正在编辑的指标
    var colorTarget by remember { mutableStateOf<Metric?>(null) }
    val chartState = remember { TrendChartState() }

    val bg = MaterialTheme.colorScheme.background

    // 背景铺满全屏（含系统栏与挖孔区）——真沉浸的前提：底色延伸到栏下，
    // 而不是把整块内容用 insets 顶开
    Box(Modifier.fillMaxSize().background(bg)) {
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
                                "趋势",
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
                                text = "颜色",
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
                                label = { Text(m.label) },
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
                                "暂无采样数据",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        val seriesList = ArrayList<ChartSeries>(metrics.size)
                        metrics.forEach { m -> seriesList += m.toSeries(samples, rememberMetricColor(m)) }
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
