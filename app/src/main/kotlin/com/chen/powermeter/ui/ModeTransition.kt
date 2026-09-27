package com.chen.powermeter.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 监测模式切换的圆形揭露过渡（口径照搬 SportLink 深浅色模式切换的 ThemeTransition，
 * 2026-09-27 用户指定：圆孔从**屏幕右下角**开始展开 —— 与双击的顶栏标题（左上角）呈
 * 对角，视觉上由远及近扫向标题）。
 *
 * 机制（纯应用内自绘，同 SportLink：先截旧界面、底层即时切换、顶层圆孔擦除）：
 * 1. [begin] 在切换前先 PixelCopy 截取当前窗口像素（旧模式界面，此时旧页完整可见）；
 * 2. 截图完成后写入 [snapshot] 并回调 onSwitch（落地 mode / Prefs）——同批状态变更、
 *    同帧重组：下层界面已是目标模式，顶层叠加「旧快照 + 右下角圆孔」覆盖层，首帧观感
 *    与切换前完全一致（旧快照全覆盖），无跳变；
 * 3. 圆孔半径 0 → 屏幕对角线（圆心 = 右下角顶点，最远点 = 左上角，800ms easeIn 前慢后快）：
 *    孔外旧快照，孔内直接透出真实新界面——被扫过的区域立即变为目标模式内容，不垫纯色、
 *    无收尾淡出；圆孔边缘经 BlurMaskFilter 羽化（[FEATHER_PX] 带宽），新旧两页柔和渐变。
 *    孔径扫满全屏即清除快照层，整个界面完成切换。
 * PixelCopy 失败（极少见）→ 直接切换，无动画降级。
 *
 * 与旧实现（ModeRevealOverlay + ClipReveal 锚点展开）的语义差异：状态在动画**开始**时
 * 就已落地（onSwitch 即切 mode），没有"预览后确认/返回键放弃"的两段式——返回键、触摸
 * 都不影响结果，动画只是视觉外衣。切换是一次性的，不存在中间态需要取消。
 */
object ModeTransition {

    /** 切换前的旧模式全窗快照；非 null 时 [ModeTransitionOverlay] 播放圆孔擦除动画 */
    var snapshot by mutableStateOf(null as ImageBitmap?)
        private set

    /** [begin] 已接管（截图在途或动画在播）：双击重入守卫，动画结束由 [clear] 复位 */
    private var running = false

    /** 动画播完由覆盖层清除快照（顶层 composable 无法访问 private setter，走此入口） */
    fun clear() {
        snapshot = null
        running = false
    }

    /**
     * 捕获旧界面并切换模式。
     * @param onSwitch 真正执行切换的回调（落地 mode / Prefs）；必须幂等且在主线程生效
     */
    fun begin(activity: Activity, onSwitch: () -> Unit) {
        if (running || snapshot != null) return
        running = true
        CoroutineScope(Dispatchers.Main.immediate).launch {
            val decor = activity.window.decorView
            if (decor.width == 0 || decor.height == 0) {
                running = false
                onSwitch()
                return@launch
            }
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val copied = suspendCancellableCoroutine { cont ->
                PixelCopy.request(
                    activity.window, bitmap,
                    { result -> cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            }
            if (!copied) {
                running = false
                onSwitch()
                return@launch
            }
            snapshot = bitmap.asImageBitmap()
            onSwitch()
        }
    }
}

// ==================== 过渡参数（照搬 SportLink ThemeTransition，用户校准 2026-09-26） ====================
/** 总时长 ms */
private const val TRANSITION_DURATION_MS = 800

/** 圆孔边缘羽化带宽 px：被扫过的区域立即显示新界面颜色，边缘新旧两色柔和渐变 */
private const val FEATHER_PX = 48f

/**
 * 孔径曲线：easeIn 慢启动——前 500ms 缓慢扩散（easeIn 在 t=0.625 时约走到一半），
 * 后 300ms 加速扩完，节奏「前慢后快」，避免开场从右下角瞬间闪出。
 */
private val REVEAL_EASING = CubicBezierEasing(0.42f, 0f, 1f, 1f)

/**
 * 模式切换过渡覆盖层：孔外=旧界面快照，孔内=直接透出真实新界面（被扫过的区域
 * 立即变为目标模式内容）；圆孔边缘经 [FEATHER_PX] 羽化模糊，新旧两页柔和渐变。
 * 必须叠在界面最顶层（MainActivity setContent 内 PowerMeterTheme 之下最后声明，
 * 同 SportLink ThemeTransitionOverlay 的挂载位置）。动画期间拦截全部触摸，
 * 扫满全屏后自动从组合中移除。
 */
@Composable
fun ModeTransitionOverlay() {
    val snap = ModeTransition.snapshot ?: return
    val progress = remember(snap) { Animatable(0f) }
    // 旧快照 shader + 羽化 blur paint 只随快照构建一次（每帧只换 path）
    val snapshotPaint = remember(snap) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(
                snap.asAndroidBitmap(),
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
            maskFilter = android.graphics.BlurMaskFilter(
                FEATHER_PX,
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
    }
    LaunchedEffect(snap) {
        progress.animateTo(1f, tween(durationMillis = TRANSITION_DURATION_MS, easing = REVEAL_EASING))
        ModeTransition.clear()
    }
    // 宿主组合销毁（动画在播时 Activity 重建/销毁）：清掉进程级快照，避免下次进入
    // 残留旧图重新播一段（clear 幂等，正常播完路径也会再触发一次，无害）
    DisposableEffect(snap) {
        onDispose { ModeTransition.clear() }
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(snap) {
                // 动画期间吞掉所有触摸，避免误操作；覆盖层移除时协程随之取消
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        // 圆心=右下角顶点，最远可见点=左上角；留 5% 余量补 blur 内缩的圆边（175px > FEATHER_PX）
        val maxR = sqrt(w * w + h * h) * 1.05f
        val r = maxR * progress.value
        // ⚠️ 外框矩形必须放大到屏幕外（margin ≥ 2×blur）：BlurMaskFilter 作用于 path 的
        // 所有边缘——矩形边若贴着屏幕边界，blur 会向屏幕内扩散，四周出现一圈旧色羽化
        // 残留直到动画结束（SportLink 真机反馈 bug）。放大后矩形边的模糊落在屏幕外，
        // 只有圆孔边缘在屏幕内羽化。屏幕外区域由 CLAMP shader 采样快照边缘像素，颜色正确。
        val margin = FEATHER_PX * 2f
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(-margin, -margin, w + margin, h + margin))
            addOval(Rect(center = Offset(w, h), radius = r))
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(outside.asAndroidPath(), snapshotPaint)
        }
    }
}
