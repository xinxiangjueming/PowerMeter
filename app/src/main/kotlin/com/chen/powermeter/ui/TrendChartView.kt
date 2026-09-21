package com.chen.powermeter.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.chen.powermeter.R
import androidx.compose.ui.unit.sp
import com.chen.powermeter.data.PowerSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 增强版趋势曲线组件。
 *
 * 承载四项能力的组合，各调用点按需开启：
 * 1. **多序列叠加**（`series.size > 1`）—— 各曲线按**自身在可见窗口内的量程**归一化到绘图区，
 *    实现不同量纲指标同屏对比。此时 Y 轴刻度失去统一物理含义，故改为「图例标量程 +
 *    按住气泡显示真实值」，不再画数值刻度（避免误读）。
 * 2. **曲线下方同色渐变填充** —— 渐变锚定在绘图区上下边（即该序列自身量程的上下界），
 *    所以填充顶部恰好贴着曲线的最高点，是标准面积图形态。
 * 3. **按住读数** —— 竖线 + 数据点 + 悬浮气泡。气泡是 Compose 文本节点而非 Canvas 绘制：
 *    本项目 Compose 版本下 Canvas 内画文字的 API 不可用（见 PowerMeterScreen 顶部注释的
 *    核实结论），刻度文字也一直是走 Compose 列的，此处沿用同一路线。
 * 4. **双指缩放 + 平移 + 底部滑条** —— 只在传入 [state] 时启用（全屏页）。
 *
 * X 轴按**时间比例**映射而非按序号均分：导入的历史 CSV 可能有采样间隔突变
 * （如两次充电会话被拼在一个文件里），按序号均分会把时间轴画歪。
 */

/** Y 轴刻度条数（含上下端点）：4 段 → 5 个刻度值 */
private const val AXIS_TICKS = 4

/**
 * 曲线下方渐变填充的顶部不透明度（单曲线时）。
 *
 * 只出现在单曲线：叠加模式下不画填充（各条量程不同，填充高度无统一含义且互相叠色）。
 */
private const val FILL_TOP_ALPHA = 0.38f

/** 填充淡入/淡出时长：单 ↔ 多曲线切换时用，避免填充瞬间消失或出现 */
private const val FILL_FADE_MS = 260

/** [scrubIndex] 的「无读数」哨兵值 */
private const val NO_INDEX = -1

/** 数字统一等宽字体（口径同 PowerMeterScreen.NumericFontFamily：MIUI 下 "tnum" 不生效） */
private val ChartNumericFont = FontFamily.Monospace

private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)

private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

/** 电流 mA 整数档（2026-09-21 用户约定）：内核只上报 mA 整数 */
private fun Double.f0(): String = String.format(Locale.US, "%.0f", this)

/**
 * 按指标取小数位（2026-09-21 用户约定）：
 * 电流 → 整数；温度（电池）→ 1 位小数；其余（功率 / 电压 / OCV / 充电 IC 温度 /
 * PMIC 温度）→ 3 位。Y 轴刻度、图例量程、读数气泡三处共用，保证同一指标任何位置口径一致。
 *
 * [Double.NaN] = 该点无读数（典型：PMIC 温度在旧版 15 列 CSV 里整列为空），统一渲染成
 * 破折号 —— 与指标卡片的 `f3OrDash` 同口径。不处理的话 `String.format` 会写出 "NaN"，
 * 摆在读数气泡与量程里比留白更像故障。
 */
private fun Double.fMetric(metric: Metric?): String {
    if (isNaN()) return "—"
    return when (metric) {
        Metric.CURRENT -> f0()
        Metric.TEMP -> f1()
        else -> f3()
    }
}

/**
 * 一条曲线的绘制数据；[values] 与传入的 samples 按下标一一对应。
 *
 * [label] 是**已解析**的本地化文本：图例与读数气泡在 `forEachIndexed` 等非 Composable
 * lambda 里取用它，那里调不了 `stringResource`，故由 [toSeries] 的调用方提前解析好。
 */
internal data class ChartSeries(
    val metric: Metric,
    val label: String,
    val unit: String,
    val color: Color,
    val values: List<Double>,
)

/** 指标单位（Y 轴刻度、图例、读数气泡共用同一口径） */
internal fun Metric.unitLabel(): String = when (this) {
    Metric.POWER -> "W"
    Metric.VOLTAGE -> "V"
    Metric.CURRENT -> "mA"
    // 温度类共用量纲：电池温度 / 充电 IC 温度 / PMIC 温度
    Metric.TEMP, Metric.CHARGER_TEMP, Metric.PMIC_TEMP -> "℃"
}

internal fun Metric.toSeries(samples: List<PowerSample>, color: Color, label: String): ChartSeries =
    ChartSeries(
        metric = this,
        label = label,
        unit = unitLabel(),
        color = color,
        values = samples.map { value(it) },
    )

/**
 * 图表视图状态：可见时间窗口，以整段数据的 `[0,1]` 归一化时间表示。
 *
 * 用「窗口」而非「缩放系数」建模，是为了让捏合缩放、单指平移、滑条拖动三种操作
 * 落到同一组状态上 —— 否则每加一种交互都要来回做一次系数 ↔ 窗口的换算，
 * 也更容易在边界（0 / 1）上算出越界的窗口。
 */
@Stable
internal class TrendChartState {

    var windowStart by mutableFloatStateOf(0f)
        private set

    var windowEnd by mutableFloatStateOf(1f)
        private set

    /** 是否已放大；未放大时图表下方不绘制滑条（滑条只在「放大了」时才有意义） */
    val zoomed: Boolean
        get() = windowStart > ZOOM_EPS || windowEnd < 1f - ZOOM_EPS

    fun reset() {
        windowStart = 0f
        windowEnd = 1f
    }

    /** 以 [focus]（0..1 归一化时间）为锚点缩放；[factor] > 1 = 放大 */
    fun zoomBy(factor: Float, focus: Float) {
        if (factor <= 0f) return
        val span = (windowEnd - windowStart).coerceAtLeast(MIN_SPAN)
        val target = (span / factor).coerceIn(MIN_SPAN, 1f)
        val f = focus.coerceIn(0f, 1f)
        // 锚点不动：先按比例把窗口起点往回推，再把越界部分整体平移回来（保持跨度不变）
        var start = f - (f - windowStart) * (target / span)
        var end = start + target
        if (start < 0f) {
            end -= start
            start = 0f
        }
        if (end > 1f) {
            start -= (end - 1f)
            end = 1f
        }
        windowStart = start.coerceIn(0f, 1f)
        windowEnd = end.coerceIn(0f, 1f)
    }

    /** 平移内容；[deltaFraction] 正 = 内容向未来方向移动（与手指方向一致） */
    fun panBy(deltaFraction: Float) {
        if (!zoomed) return
        val span = windowEnd - windowStart
        val start = (windowStart - deltaFraction).coerceIn(0f, 1f - span)
        windowStart = start
        windowEnd = start + span
    }

    /** 滑条拖动：把窗口**中心**移到 [center]（0..1） */
    fun centerOn(center: Float) {
        val span = windowEnd - windowStart
        if (span >= 1f) return
        val c = center.coerceIn(span / 2f, 1f - span / 2f)
        windowStart = c - span / 2f
        windowEnd = c + span / 2f
    }

    private companion object {
        /** 最多放大 50 倍；再细下去屏幕上没有足够采样点，只剩折线尖角 */
        const val MIN_SPAN = 0.02f

        /** 浮点误差容差：捏合回到原位后必须判定为「未放大」，否则滑条会赖着不消失 */
        const val ZOOM_EPS = 5e-4f
    }
}

/**
 * 一次绘制所需的全部几何量。
 *
 * 在 Composable 作用域算一次，曲线 / 刻度列 / 滑条 / 读数气泡共用同一份 ——
 * 各处各算一遍是时间轴错位最常见的来源（改了一处忘了另一处）。
 */
private class ChartGeometry(
    /** 可见窗口的起止时间 */
    val vT0: Long,
    val vSpan: Long,
    /** 实际绘制的下标范围（两端各外扩一个点，保证窗口边缘的折线连续） */
    val from: Int,
    val to: Int,
    /** 可见窗口内命中的采样下标（含端点）；读数取点用 */
    val visStart: Int,
    val visEnd: Int,
    /** 每个采样点的横向比例（0..1，相对绘图区）。预计算一次，曲线/竖线/气泡共用 */
    val xFractions: FloatArray,
    /** 全部时间戳相同时为 true：此时时间轴退化，改为按序号均分 */
    val byIndex: Boolean,
    /** 每条曲线在可见窗口内的取值区间（含 5% 余量），下标与 series 对齐 */
    val ranges: List<Pair<Double, Double>>,
)

/**
 * Y 轴范围：最小值 ×0.95、最大值 ×1.05，两端各留 5% 余量。
 *
 * ⚠️ 负值的坑：机械地给最小值乘 0.95 会把边界**往 0 拉**（min = -5 → -4.75），
 *    真实最小值反而落在轴外被裁掉。放电电流/功率为负，此场景必然命中。
 *    故按「远离 0 的方向」外扩：min < 0 乘 1.05、max < 0 乘 0.95。
 */
private fun axisRange(min: Double, max: Double): Pair<Double, Double> {
    var lo = if (min >= 0.0) min * 0.95 else min * 1.05
    var hi = if (max >= 0.0) max * 1.05 else max * 0.95
    // 全程等值（如恒温 30.000）→ 上下各外扩 0.5，把平线画在中间而不是贴着边线
    if (hi - lo < 1e-9) {
        lo -= 0.5
        hi += 0.5
    }
    return lo to hi
}

/**
 * 按 min/max 分桶抽稀（档二-2）。
 *
 * 把 `[from, to]` 均分成 [buckets] 个桶，每桶只保留**最小值**与**最大值**两个点的下标，
 * 并按时间顺序输出。这样折线轮廓（尖峰、谷底）与全部点参与绘制时视觉等价，
 * 而点数从 O(样本数) 降到 O(像素宽)，Path 的构建与光栅化成本大幅下降。
 *
 * @return 升序下标数组；`null` = 无需抽稀（点数未超过 `buckets × 2`，调用方走全量区间）
 */
private fun decimateIndices(
    values: List<Double>,
    from: Int,
    to: Int,
    buckets: Int,
): IntArray? {
    val n = to - from + 1
    if (buckets < 2 || n <= buckets * 2) return null

    val out = IntArray(buckets * 2)
    var written = 0
    val step = n.toDouble() / buckets
    for (b in 0 until buckets) {
        var start = from + (b * step).toInt()
        var end = from + ((b + 1) * step).toInt() - 1
        if (end < start) end = start
        if (end > to) end = to
        if (start > to) break

        // 桶首点无读数（NaN）时不能拿它当基准：NaN 与任何值比较恒为 false，整个桶的极值
        // 都会"选不出来"，桶内有效点被静默丢掉。改为从桶内第一个有效点起算；
        // 整桶都无读数就直接跳过该桶（不出点，交给绘制侧形成断点）。
        if (values[start].isNaN()) {
            var j = start
            while (j <= end && values[j].isNaN()) j++
            if (j > end) continue
            start = j
        }
        var minIndex = start
        var maxIndex = start
        var minValue = values[start]
        var maxValue = values[start]
        for (i in start..end) {
            val v = values[i]
            if (v < minValue) {
                minValue = v
                minIndex = i
            }
            if (v > maxValue) {
                maxValue = v
                maxIndex = i
            }
        }
        // 按时间顺序压入，保证折线不出现回折
        if (minIndex <= maxIndex) {
            out[written++] = minIndex
            if (maxIndex != minIndex) out[written++] = maxIndex
        } else {
            out[written++] = maxIndex
            out[written++] = minIndex
        }
    }
    return if (written == 0) null else out.copyOf(written)
}

/** 第一个 timeMillis >= [t] 的下标（可能等于 size） */
private fun lowerBound(samples: List<PowerSample>, t: Long): Int {
    var lo = 0
    var hi = samples.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (samples[mid].timeMillis < t) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun computeGeometry(
    samples: List<PowerSample>,
    series: List<ChartSeries>,
    winStart: Float,
    winEnd: Float,
): ChartGeometry {
    val last = samples.lastIndex
    val t0 = samples.first().timeMillis
    // 时间跨度；全部时间戳相同 → 0，X 轴退化为按序号均分（否则所有点会挤在 x = 0）
    val tSpan = (samples[last].timeMillis - t0).coerceAtLeast(0L)
    val byIndex = tSpan <= 0L

    val ws = winStart.coerceIn(0f, 1f)
    val we = winEnd.coerceIn(0f, 1f).coerceAtLeast(ws + 1e-4f)
    val vT0 = t0 + (tSpan * ws).toLong()
    val vSpan = ((tSpan * we).toLong() - (tSpan * ws).toLong()).coerceAtLeast(1L)

    // 可见窗口边界用二分定位（导入文件可达 2 万点，线性扫描在每帧缩放时也会积少成多）
    var visStart = if (byIndex) (ws * last).roundToInt() else lowerBound(samples, vT0)
    var visEnd = if (byIndex) (we * last).roundToInt() else lowerBound(samples, vT0 + vSpan) - 1

    if (visStart > visEnd) {
        // 窗口窄到两个采样点之间：退化为离窗口起点最近的一个点，保证仍有东西可画
        val mid = visStart.coerceIn(0, last)
        visStart = (mid - 1).coerceAtLeast(0)
        visEnd = mid
    }
    visStart = visStart.coerceIn(0, last)
    visEnd = visEnd.coerceIn(visStart, last)

    // 横向比例预计算：曲线、读数竖线、气泡三处共用，避免各自换算导致时间轴错位
    val xFractions = FloatArray(samples.size)
    val visSpanFraction = (we - ws).coerceAtLeast(1e-4f)
    for (i in 0..last) {
        xFractions[i] = if (byIndex) {
            if (last <= 0) 0f else i.toFloat() / last
        } else {
            val global = (samples[i].timeMillis - t0).toDouble() / tSpan.toDouble()
            ((global - ws) / visSpanFraction).toFloat()
        }
    }

    val ranges = series.map { sr ->
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var seen = false
        for (i in visStart..visEnd) {
            val v = sr.values[i]
            // 无读数的点不参与量程：NaN 与任何值比较恒为 false，混进来会让"窗口内到底
            // 有没有读到数"无法分辨
            if (v.isNaN()) continue
            seen = true
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        when {
            // 窗口内整条曲线都没有读数（老 CSV 缺列 / 本机缺该温感区）→ 量程留 NaN，
            // Y 轴刻度与图例统一显示破折号，比谎报一个 0~1 的量程诚实
            !seen -> Double.NaN to Double.NaN
            lo > hi -> axisRange(0.0, 1.0)
            else -> axisRange(lo, hi)
        }
    }

    return ChartGeometry(
        vT0 = vT0,
        vSpan = vSpan,
        from = (visStart - 1).coerceAtLeast(0),
        to = (visEnd + 1).coerceAtMost(last),
        visStart = visStart,
        visEnd = visEnd,
        xFractions = xFractions,
        byIndex = byIndex,
        ranges = ranges,
    )
}

/** 手指横坐标 → 最近的采样下标（按序号均分的退化模式下直接线性映射） */
private fun nearestIndex(
    samples: List<PowerSample>,
    geom: ChartGeometry,
    x: Float,
    widthPx: Int,
): Int {
    if (samples.isEmpty()) return 0
    if (widthPx <= 0) return geom.visStart
    val fraction = (x / widthPx).coerceIn(0f, 1f)
    if (geom.byIndex) {
        val picked = (fraction * samples.lastIndex).roundToInt()
        return picked.coerceIn(geom.visStart, geom.visEnd)
    }
    val target = geom.vT0 + (geom.vSpan * fraction).toLong()
    val i = lowerBound(samples, target)
    val a = (i - 1).coerceIn(0, samples.lastIndex)
    val b = i.coerceIn(0, samples.lastIndex)
    val picked = if (abs(samples[a].timeMillis - target) <= abs(samples[b].timeMillis - target)) a else b
    return picked.coerceIn(geom.visStart, geom.visEnd)
}

@Composable
internal fun TrendChart(
    samples: List<PowerSample>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier.fillMaxWidth().height(150.dp),
    strokeWidth: Float = 6f,
    /** Y 轴刻度字号：卡片内 10sp，全屏页 13sp */
    axisLabelSp: TextUnit = 10.sp,
    /**
     * 非空 = 全屏交互模式：双指缩放 / 平移 + 放大后显示滑条 + **单指按下即读数**
     * （全屏页本身不可滚动，直接读最顺手）。
     * null = 卡片内嵌模式：不可缩放，读数改为**长按后拖动** —— 单指直接拖动会被
     * 图表吃掉，页面竖直滚动就废了。
     */
    state: TrendChartState? = null,
) {
    Column(modifier) {
        if (samples.size < 2 || series.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.empty_no_samples),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            ChartBody(
                samples = samples,
                series = series,
                strokeWidth = strokeWidth,
                axisLabelSp = axisLabelSp,
                state = state,
            )
        }
    }
}

@Composable
private fun ColumnScope.ChartBody(
    samples: List<PowerSample>,
    series: List<ChartSeries>,
    strokeWidth: Float,
    axisLabelSp: TextUnit,
    state: TrendChartState?,
) {
    val normalized = series.size > 1
    // 填充的显示程度：单曲线 1、叠加 0，用动画过渡（曲线数量变化时不出现瞬间跳变）
    val fillProgress by animateFloatAsState(
        targetValue = if (normalized) 0f else 1f,
        animationSpec = tween(durationMillis = FILL_FADE_MS),
        label = "chartFillProgress",
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleContainer = MaterialTheme.colorScheme.surfaceContainerHighest
    val density = LocalDensity.current

    val winStart = state?.windowStart ?: 0f
    val winEnd = state?.windowEnd ?: 1f

    // 几何量只依赖「样本 + 指标集合 + 窗口」，与颜色无关。
    // 用 series 的指标名当轻量签名：ChartSeries.values 是上万长度的 List，
    // 直接拿 series 当 remember key 会退化成每次重组做一次 O(n) 的 equals 比较。
    val seriesKey = series.map { it.metric.name }
    val geom = remember(samples, seriesKey, winStart, winEnd) {
        computeGeometry(samples, series, winStart, winEnd)
    }

    // 手势回调里要读到最新几何量，但 pointerInput 的 key 必须是稳定的：
    // 若把 geom 当 key，每缩放一步协程都会被取消重建，正在进行的捏合手势会当场断掉。
    val geomRef = rememberUpdatedState(geom)
    val samplesRef = rememberUpdatedState(samples)

    var scrubIndex by remember { mutableIntStateOf(NO_INDEX) }
    var plotWidthPx by remember { mutableIntStateOf(0) }
    var yAxisWidthPx by remember { mutableIntStateOf(0) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }

    // 抽稀（档二-2）：可见点数超过绘图区像素宽的 2 倍时，按分桶 min/max 抽到「约 1 像素 2 点」。
    // 只作用于**绘制** —— 几何量（xFractions / visStart..visEnd）仍按全量样本计算，
    // 否则读数气泡按 x 反查出的下标会与真实样本错位。
    // 键用 geom 而非 samples：values 只由「样本 + 指标」决定，而 geom 已完整覆盖这两项，
    // 拿 samples 当键会退化成每次重组做一次 O(n) 的 List.equals。
    val decimated: List<IntArray?> = remember(geom, plotWidthPx) {
        if (plotWidthPx <= 0) {
            List(series.size) { null }
        } else {
            val buckets = plotWidthPx / 2
            series.map { sr -> decimateIndices(sr.values, geom.from, geom.to, buckets) }
        }
    }

    val gestureModifier = if (state != null) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                var scrubbing = true
                scrubIndex = nearestIndex(samplesRef.value, geomRef.value, first.position.x, size.width)
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        // 双指 = 缩放/平移。本次读数立即结束，避免竖线跟着手指乱跑
                        if (scrubbing) {
                            scrubbing = false
                            scrubIndex = NO_INDEX
                        }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val focus = if (size.width > 0) {
                            (pressed.map { it.position.x }.average().toFloat() / size.width).coerceIn(0f, 1f)
                        } else 0.5f
                        if (zoom != 1f) state.zoomBy(zoom, focus)
                        if (pan.x != 0f) state.panBy(pan.x / size.width)
                        // 无条件消费：否则父级（卡片内的 verticalScroll）会同时滚起来
                        event.changes.forEach { it.consume() }
                    } else if (scrubbing) {
                        val change = pressed.first()
                        scrubIndex = nearestIndex(samplesRef.value, geomRef.value, change.position.x, size.width)
                        change.consume()
                    }
                }
                scrubIndex = NO_INDEX
            }
        }
    } else {
        Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    scrubIndex = nearestIndex(samplesRef.value, geomRef.value, offset.x, size.width)
                },
                onDrag = { change, _ ->
                    scrubIndex = nearestIndex(samplesRef.value, geomRef.value, change.position.x, size.width)
                },
                onDragEnd = { scrubIndex = NO_INDEX },
                onDragCancel = { scrubIndex = NO_INDEX },
            )
        }
    }

    if (normalized) {
        // 归一化叠加时 Y 轴没有统一物理含义 → 量程由图例承载，真实值由读数气泡承载
        LegendRow(series, geom.ranges, labelColor)
        Spacer(Modifier.height(6.dp))
    }

    // 图例 / 绘图区 / 滑条三段作为**外层 Column 的直接子节点**排布：绘图区 weight(1f)
    // 吃掉剩余高度、滑条固定高度、图例按内容高度。
    // 这里必须写成 ColumnScope 的扩展函数，而不是在内部再套一层 Column —— 只有这样才能
    // 拿到外层 TrendChart 那个 Column 的作用域（跨 Composable 调用会断掉隐式接收者，
    // 普通函数里 Modifier.weight 直接 Unresolved reference）。
    Row(Modifier.fillMaxWidth().weight(1f)) {
        if (!normalized) {
            YAxisColumn(
                labels = axisLabels(geom.ranges.first(), series.firstOrNull()?.metric),
                labelSp = axisLabelSp,
                color = labelColor,
                modifier = Modifier.onSizeChanged { yAxisWidthPx = it.width },
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { plotWidthPx = it.width }
                .then(gestureModifier),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // 与刻度列对齐：首/末刻度中心即绘图区上下边界
                val plotTop = h * 0.1f
                val plotBottom = h * 0.9f
                val plotH = plotBottom - plotTop

                for (k in 0..AXIS_TICKS) {
                    val y = h * (k + 0.5f) / (AXIS_TICKS + 1)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }

                val xAt = { i: Int -> geom.xFractions[i] * w }
                val yAt = { value: Double, lo: Double, hi: Double ->
                    val span = hi - lo
                    val ratio = if (span <= 0.0) 0.5f else ((value - lo) / span).toFloat()
                    plotBottom - ratio.coerceIn(0f, 1f) * plotH
                }

                // 填充与折线分两轮画：先铺完全部填充再画全部线，否则后画的填充会盖住先画的线。
                // 叠加模式（normalized）**不画填充**：各条曲线按自身量程映射到 0..1，
                // 填充高度没有统一物理含义，且四种半透明色互相叠压会糊成一片。
                // 单 → 多切换时由 fillProgress 做淡出/淡入，不让填充"啪"地消失。
                val fillTopAlpha = FILL_TOP_ALPHA * fillProgress
                clipRect(left = 0f, top = 0f, right = w, bottom = h) {
                    if (fillTopAlpha > 0.002f) {
                        series.forEachIndexed { index, sr ->
                            val (lo, hi) = geom.ranges[index]
                            val dec = decimated[index]
                            val count = dec?.size ?: (geom.to - geom.from + 1)
                            val fill = Path()
                            var started = false
                            var lastI = geom.from
                            for (k in 0 until count) {
                                val i = dec?.get(k) ?: (geom.from + k)
                                // 该点无读数：断开轮廓（下一有效点重新 moveTo 起一段），
                                // 绝不把 NaN 写进 Path —— 非有限坐标会让整条路径的光栅化
                                // 直接失效（整条曲线消失），比留一个缺口严重得多
                                if (sr.values[i].isNaN()) {
                                    started = false
                                    continue
                                }
                                lastI = i
                                val px = xAt(i)
                                val py = yAt(sr.values[i], lo, hi)
                                if (!started) {
                                    fill.moveTo(px, plotBottom)
                                    fill.lineTo(px, py)
                                    started = true
                                } else {
                                    fill.lineTo(px, py)
                                }
                            }
                            if (started) {
                                fill.lineTo(xAt(lastI), plotBottom)
                                fill.close()
                                drawPath(
                                    fill,
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            sr.color.copy(alpha = fillTopAlpha),
                                            sr.color.copy(alpha = 0f),
                                        ),
                                        startY = plotTop,
                                        endY = plotBottom,
                                    ),
                                )
                            }
                        }
                    }

                    series.forEachIndexed { index, sr ->
                        val (lo, hi) = geom.ranges[index]
                        val dec = decimated[index]
                        val count = dec?.size ?: (geom.to - geom.from + 1)
                        val line = Path()
                        var started = false
                        for (k in 0 until count) {
                            val i = dec?.get(k) ?: (geom.from + k)
                            // 无读数的点：断开折线（下一有效点重新 moveTo），
                            // 不把 NaN 写进 Path（理由同填充路径）
                            if (sr.values[i].isNaN()) {
                                started = false
                                continue
                            }
                            val px = xAt(i)
                            val py = yAt(sr.values[i], lo, hi)
                            if (!started) {
                                line.moveTo(px, py)
                                started = true
                            } else {
                                line.lineTo(px, py)
                            }
                        }
                        if (started) {
                            drawPath(
                                line,
                                sr.color,
                                style = Stroke(
                                    width = strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                    }

                    // 读数竖线与数据点
                    val idx = scrubIndex
                    if (idx in geom.visStart..geom.visEnd) {
                        val px = xAt(idx)
                        drawLine(
                            color = labelColor.copy(alpha = 0.75f),
                            start = Offset(px, 0f),
                            end = Offset(px, h),
                            strokeWidth = 1.5f,
                        )
                        series.forEachIndexed { index, sr ->
                            val (lo, hi) = geom.ranges[index]
                            // 该指标此处无读数：不画点（气泡里会显示破折号）。
                            // 否则 yAt 会算出 NaN 坐标，圆被画到屏幕外甚至整层失效
                            if (sr.values[idx].isNaN()) return@forEachIndexed
                            val center = Offset(px, yAt(sr.values[idx], lo, hi))
                            drawCircle(Color.White.copy(alpha = 0.9f), radius = 5.dp.toPx(), center = center)
                            drawCircle(sr.color, radius = 4.dp.toPx(), center = center)
                        }
                    }
                }
            }

            // 读数气泡：位置跟随手指所在的采样点，并按气泡自身宽度夹住两端不出框
            val idx = scrubIndex
            if (idx in samples.indices && idx in geom.visStart..geom.visEnd && plotWidthPx > 0) {
                val centerX = geom.xFractions[idx] * plotWidthPx
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                x = (centerX - bubbleWidthPx / 2f).roundToInt()
                                    .coerceIn(0, (plotWidthPx - bubbleWidthPx).coerceAtLeast(0)),
                                y = 0,
                            )
                        }
                        .onSizeChanged { bubbleWidthPx = it.width }
                        .clip(RoundedCornerShape(12.dp))
                        .background(bubbleContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Column {
                        // 读数时间不放气泡里，改由底部 x 轴行贴读数竖线显示（见 XAxisTimeRow）
                        series.forEachIndexed { index, sr ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(sr.color, RoundedCornerShape(50)),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "${sr.label} ${sr.values[idx].fMetric(sr.metric)} ${sr.unit}",
                                    fontSize = 11.sp,
                                    fontFamily = ChartNumericFont,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // x 轴时间（精确到秒）：常态标**可见窗口**两端，随缩放 / 平移实时更新；读数中改为贴竖线
    // 显示**当前该点**的时间（读数气泡不再重复带时间）。全屏页位于「x 轴与底部滑条之间的空白区」
    // ——即绘图区之后、ChartScrollbar 之前；卡片内嵌页无滑条，则直接贴在绘图区下方。
    val axisScrubIdx = scrubIndex
    val axisScrubbing = axisScrubIdx in samples.indices && axisScrubIdx in geom.visStart..geom.visEnd
    XAxisTimeRow(
        startMillis = geom.vT0,
        endMillis = geom.vT0 + geom.vSpan,
        cursorMillis = if (axisScrubbing) samples[axisScrubIdx].timeMillis else null,
        cursorFraction = if (axisScrubbing) geom.xFractions[axisScrubIdx] else 0f,
        leadingSpacePx = if (normalized) 0 else yAxisWidthPx,
        plotWidthPx = plotWidthPx,
        labelSp = axisLabelSp,
        color = labelColor,
        density = density,
    )

    if (state != null) {
        Spacer(Modifier.height(6.dp))
        ChartScrollbar(
            zoomed = state.zoomed,
            windowStart = state.windowStart,
            windowEnd = state.windowEnd,
            leadingSpacePx = if (normalized) 0 else yAxisWidthPx,
            onCenterTo = { state.centerOn(it) },
            density = density,
        )
    }
}

/** Y 轴刻度列；宽度取 IntrinsicSize.Max = 最宽一条刻度的固有宽度（自适应，不硬编码 dp） */
@Composable
private fun YAxisColumn(
    labels: List<String>,
    labelSp: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(IntrinsicSize.Max)
            .padding(end = 8.dp),
    ) {
        // 每条刻度各占 1/5 高度且在其槽内垂直居中 → 刻度中心恰好落在 (k + 0.5) / 5
        // 的高度比例上，与 Canvas 里的网格线公式天然对齐，不需要任何像素级换算
        labels.forEach { text ->
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text,
                    fontSize = labelSp,
                    fontFamily = ChartNumericFont,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private fun axisLabels(range: Pair<Double, Double>, metric: Metric?): List<String> {
    val (lo, hi) = range
    val span = hi - lo
    return (0..AXIS_TICKS).map { k -> (hi - span * k / AXIS_TICKS).fMetric(metric) }
}

/** 归一化叠加时的图例：色块 + 指标名 + **可见窗口内**的量程与单位 */
@Composable
private fun LegendRow(
    series: List<ChartSeries>,
    ranges: List<Pair<Double, Double>>,
    labelColor: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.forEachIndexed { index, sr ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(sr.color, RoundedCornerShape(50)))
                Spacer(Modifier.width(5.dp))
                Text(
                    "${sr.label} ${ranges[index].first.fMetric(sr.metric)}~${ranges[index].second.fMetric(sr.metric)} ${sr.unit}",
                    fontSize = 11.sp,
                    fontFamily = ChartNumericFont,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 底部滑条：轨道 = 整段时间轴，滑块 = 当前可见窗口。
 *
 * 只在 [zoomed] 时绘制内容，但**行高恒定保留** —— 若整行随缩放出现/消失，
 * 捏合过程中图表高度会跟着跳，手指锚点随之漂移，缩放会"打架"。
 *
 * 交互：按下即把窗口中心跳到该处，拖动连续定位。
 * 缩回全量靠**双指捏合缩小**（与放大同一套手势），不再另设「重置」按钮
 * —— 滑条行只保留轨道本身，避免与图表抢注意力。
 */
@Composable
private fun ChartScrollbar(
    zoomed: Boolean,
    windowStart: Float,
    windowEnd: Float,
    leadingSpacePx: Int,
    onCenterTo: (Float) -> Unit,
    density: androidx.compose.ui.unit.Density,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val thumbColor = MaterialTheme.colorScheme.primary
    var trackWidthPx by remember { mutableIntStateOf(0) }

    Row(
        Modifier.fillMaxWidth().height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingSpacePx > 0) {
            Spacer(Modifier.width(with(density) { leadingSpacePx.toDp() }))
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { trackWidthPx = it.width }
                .pointerInput(Unit) {
                    // 按下即跳转 + 拖动连续定位：两种情况都归一到「窗口中心」
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (size.width > 0) onCenterTo(offset.x / size.width)
                        },
                        onDrag = { change, _ ->
                            if (size.width > 0) onCenterTo(change.position.x / size.width)
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(trackColor, RoundedCornerShape(50)),
            )
            if (zoomed && trackWidthPx > 0) {
                val span = (windowEnd - windowStart).coerceIn(0.02f, 1f)
                val thumbWidth = (span * trackWidthPx).roundToInt().coerceAtLeast(24)
                val maxOffset = (trackWidthPx - thumbWidth).coerceAtLeast(0)
                val offset = ((windowStart / (1f - span).coerceAtLeast(1e-4f)) * maxOffset)
                    .roundToInt()
                    .coerceIn(0, maxOffset)
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(offset, 0) }
                        .width(with(density) { thumbWidth.toDp() })
                        .height(16.dp)
                        .background(thumbColor, RoundedCornerShape(50)),
                )
            }
        }
    }
}

/** x 轴时间的淡出时长（窗口起止 ↔ 读数点时间互切，短促即可，不影响读数手感） */
private const val AXIS_TIME_FADE_MS = 120

/**
 * x 轴时间行。
 *
 * - **常态**：只标**可见窗口**的两端时刻（精确到秒 `HH:mm:ss`），左端与绘图区左沿对齐
 *   （`leadingSpacePx` = Y 轴刻度列宽；归一化叠加模式无 Y 轴刻度列，传 0）；
 * - **读数中**（[cursorMillis] 非空）：两端淡出，改在**读数竖线正下方**显示当前该点的时间
 *   —— 读数气泡不再重复携带时间（用户 2026-09-21 拍板：「上面数据窗口里面的时间就不需要了」）。
 *
 * ⚠️ 两种状态都是「单行 + 同字号」文本，行高一致 —— 切换时绘图区（weight(1f)）高度不跳动，
 * 手指锚点不漂移、读数竖线不乱跑。故读数态**不移除本行**，只切换内容。
 */
@Composable
private fun XAxisTimeRow(
    startMillis: Long,
    endMillis: Long,
    cursorMillis: Long?,
    cursorFraction: Float,
    leadingSpacePx: Int,
    plotWidthPx: Int,
    labelSp: TextUnit,
    color: Color,
    density: androidx.compose.ui.unit.Density,
) {
    val windowAlpha by animateFloatAsState(
        targetValue = if (cursorMillis != null) 0f else 1f,
        animationSpec = tween(durationMillis = AXIS_TIME_FADE_MS),
        label = "axisWindowTimeAlpha",
    )
    var cursorLabelWidthPx by remember { mutableIntStateOf(0) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingSpacePx > 0) {
            Spacer(Modifier.width(with(density) { leadingSpacePx.toDp() }))
        }
        Box(Modifier.weight(1f)) {
            // 常态：可见窗口起止两端
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatClockTime(startMillis),
                    modifier = Modifier.alpha(windowAlpha),
                    fontSize = labelSp,
                    fontFamily = ChartNumericFont,
                    color = color,
                    maxLines = 1,
                )
                Text(
                    formatClockTime(endMillis),
                    modifier = Modifier.alpha(windowAlpha),
                    fontSize = labelSp,
                    fontFamily = ChartNumericFont,
                    color = color,
                    maxLines = 1,
                )
            }
            // 读数中：贴在读数竖线正下方的当前点时间（按标签自身宽度夹住两端，不出绘图区）
            if (cursorMillis != null) {
                val centerX = cursorFraction.coerceIn(0f, 1f) * plotWidthPx
                Text(
                    formatClockTime(cursorMillis),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (centerX - cursorLabelWidthPx / 2f).roundToInt()
                                    .coerceIn(0, (plotWidthPx - cursorLabelWidthPx).coerceAtLeast(0)),
                                y = 0,
                            )
                        }
                        .onSizeChanged { cursorLabelWidthPx = it.width },
                    fontSize = labelSp,
                    fontFamily = ChartNumericFont,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

private val ClockTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** 时间格式化（精确到秒）：读数气泡与 x 轴起止时间共用同一 `HH:mm:ss` 口径 */
private fun formatClockTime(timeMillis: Long): String = ClockTimeFormat.format(Date(timeMillis))
