package com.chen.powermeter.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 主题切换圆形揭露过渡（SportLink ThemeTransition.kt 原样移植，2026-09-28；
 * 用户指定：从屏幕右下角边框开始展开，过渡到整个界面）。
 *
 * 机制（纯应用内自绘，不触碰 configuration / 不 recreate）：
 * 1. [capture]（PowerMeterTheme 闸门，系统深浅色切换路径）在真正生效前先 PixelCopy
 *    截取当前窗口像素（旧主题界面）；
 * 2. 截图完成后写入 [snapshot]——同批状态变更、同帧重组：下层界面已是新主题，顶层
 *    叠加「旧快照 + 右下角圆孔」覆盖层，首帧观感与切换前完全一致（旧快照全覆盖），无跳变；
 * 3. 圆孔半径 0 → 屏幕对角线（圆心=右下角顶点，最远点=左上角，800ms easeIn 前慢后快）：
 *    孔外旧快照，孔内直接透出真实新界面——被扫过的区域颜色立即变为目标颜色；
 *    圆孔边缘经 BlurMaskFilter 羽化（FEATHER_PX 带宽），新旧两色柔和渐变不硬切。
 *    孔径扫满全屏即清除快照层，无收尾淡出阶段。
 * PixelCopy 失败（极少见）→ 直接切换，无动画降级。
 */
object ThemeTransition {

    /** 切换前的旧主题全窗快照；非 null 时 [ThemeTransitionOverlay] 播放圆孔擦除动画 */
    var snapshot by mutableStateOf(null as ImageBitmap?)
        private set

    /** 当前快照持有者标记：[clear] 只在快照仍是传入项时生效。
     *  快照可能被多个 composition 的 overlay 同时播放，必须防止某个 overlay 先播完
     *  把快照清掉、其余 overlay 提前消失导致擦除动画半途中断。 */
    private var owner: ImageBitmap? = null

    /**
     * 快照归属的**宿主窗口**（2026-09-28 二轮）：overlay 只播"本窗口自己截的"快照。
     * 防的是二级页动画未播完就返回 —— 那时详情页的 LaunchedEffect 随组合销毁被取消、
     * `clear` 从未执行，残留快照会被**源页窗口**重播一遍（表现与"补播"一模一样：
     * 先闪一张旧主题图、再从右下角滑开）。见 [isOwnedBy] / [ThemeTransitionOverlay]。
     */
    private var ownerHost: Activity? = null

    /** 动画播完由覆盖层清除快照（顶层 composable 无法访问 private setter，走此入口）；
     *  仅当快照仍是传入的 [snap] 时清除（幂等，多 overlay 并发安全） */
    fun clear(snap: ImageBitmap?) {
        if (owner != null && owner === snap) {
            snapshot = null
            owner = null
            ownerHost = null
        }
    }

    /** 快照是否由 [activity] 这个窗口截取（overlay 只播本窗口自己的快照，见 [ownerHost]） */
    fun isOwnedBy(activity: Activity?): Boolean = ownerHost != null && ownerHost === activity

    /**
     * 截取当前窗口像素（旧主题界面）并登记为过渡快照（PowerMeterTheme 闸门的
     * 系统深浅色切换路径，零延迟——系统切换面板是 SystemUI 窗口，不在本 app 窗口内）。
     * @return true = 快照已就绪（含「已有快照在播」的幂等场景，直接复用不打断）；
     *         false = 截图失败（窗口未就绪 / PixelCopy 失败），调用方直接切换、无动画降级
     */
    suspend fun capture(activity: Activity, delayMs: Long = 0): Boolean {
        // 已有快照在播（动画进行中又发生新的深浅切换）：复用现快照，不重复截、不打断。
        // ⚠️ 例外（2026-09-28 二轮）：归属宿主已结束（二级页动画未播完就 finish —— 其
        // LaunchedEffect 随组合销毁被取消，clear 从未执行）→ 残留快照无人回收，就地丢弃后重截
        if (snapshot != null) {
            val host = ownerHost
            if (host != null && (host === activity || (!host.isFinishing && !host.isDestroyed))) {
                return true
            }
            snapshot = null
            owner = null
            ownerHost = null
        }
        if (delayMs > 0) delay(delayMs)
        val decor = activity.window.decorView
        if (decor.width == 0 || decor.height == 0) return false
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { cont ->
            PixelCopy.request(
                activity.window, bitmap,
                { result -> cont.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()),
            )
        }
        if (!copied) return false
        val snap = bitmap.asImageBitmap()
        owner = snap
        ownerHost = activity
        snapshot = snap
        return true
    }

    /** 从 Compose 上下文向上找到宿主 Activity（PowerMeterTheme 闸门在系统切换路径使用） */
    fun activityOf(context: Context): Activity? = context.findActivity()

    /**
     * 宿主是否处于「可播动画」状态：只有**已 RESUMED 的页**才播圆孔揭露。
     *
     * 闸门在目标深浅变化后的首帧才触发，彼时宿主必已走完 onResume —— 从桌面/多任务/
     * 息屏回前台的「后台错过的变化」由此自然放行播动画（2026-09-29 用户定案，推翻
     * 2026-09-28 的"静默落地"决策；回前台瞬间窗口表面停留的旧主题帧正是揭露的起点画面）。
     * 唯一被拦下的是「被半透明二级页盖着、paused 但可见」的本页：用户看的是二级页
     * （它自己播动画），底下的本页静默换装，不双层各播一遍。
     */
    fun canAnimateFrom(activity: Activity?): Boolean = isHostForeground(activity)

    /**
     * 宿主是否已在前台（RESUMED）。
     *
     * 历史注记（2026-09-28 → 2026-09-29 推翻）：本判据曾用在三处 Activity 的
     * onConfigurationChanged 里区分「后台错过的变化」（送达时宿主未 RESUMED →
     * [requestSilent] 静默落地），修的是"返回瞬间闪回旧主题 + 紧接着补播 800ms 圆孔"
     * ——彼时详情页还是不透明窗口、源页真 onStop，错过配置的状态只能回前台补。
     * 2026-09-29 用户定案：后台错过的变化回前台**要播动画**（旧主题停留帧 = 揭露起点
     * 画面），且详情/趋势页已改半透明窗口主题（源页只 onPause 不停，不再有"被盖住收不到
     * 配置"的源页），三处静默调用随之移除。现在只经 [canAnimateFrom] 供闸门拦
     * 「paused 但被半透明二级页盖着可见」的场景。
     */
    fun isHostForeground(activity: Activity?): Boolean {
        val owner = activity as? LifecycleOwner ?: return false
        return !activity.isFinishing &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    // ── 退场主题交棒（2026-09-28 二轮：修"返回瞬间闪回白色"）──────────────────
    /** 待源页采用的深浅（二级页退场时写入，见 [noteExitTheme]） */
    private var exitThemeDark: Boolean? = null

    /**
     * 二级页收拢退场时把**当前深浅**交给即将显示的源页：源页在 onStart（首帧绘制之前）
     * 消费并静默落地 → 返回后第一帧就是目标主题，且随后的配置分发因状态已相等而不再
     * 触发圆孔动画。比"等配置回调"确定得多（配置回调可能在窗口已上屏之后才到）。
     */
    fun noteExitTheme(dark: Boolean) {
        exitThemeDark = dark
    }

    /** 源页 onStart 消费交棒值；null = 无交棒（冷启动 / 从多任务回前台等） */
    fun consumeExitTheme(): Boolean? {
        val v = exitThemeDark
        exitThemeDark = null
        return v
    }

    // ── 说明（2026-09-28 二轮复盘）─────────────────────────────────────────
    // 曾试过「回前台首次同步 = 后台错过的变化 → 一律静默」的标记（noteStopped + 用户交互
    // 清位 + consumeMissedChange），实测**会吞掉正常的前台切换**：只要页面被 onStop 过、
    // 之后用户没碰过本窗口，接着在系统面板切深浅就完全不播动画（自测日志实证：
    //   I PowerMeterTheme: gate dark=true → 静默放行（silent 标记）
    // 而那次是干净的前台切换）。根因是"本页是否在前台被人看着"在 resume 时刻无法可靠判定
    // （Resources 可能还是旧值），故删除该机制。改由 [noteExitTheme] 的**退场交棒**确定性地
    // 解决：退出方直接把目标深浅交给源页，源页在 onStart 落地，不依赖配置何时送达。

    /**
     * 「静默切换」标记：现仅由二级页退场交棒路径设置（MainActivity.applyExitHandoff）——
     * 源页在首帧绘制前吃掉交棒深浅、直接呈现目标主题，不播动画。交棒场景下源页表面停留的
     * 旧主题帧是"闪回旧色"（用户刚在二级页看过目标主题），不是揭露动画的合理起点。
     * 后台错过的系统深浅变化 2026-09-29 起不再走静默：回前台播圆孔揭露（见
     * [canAnimateFrom]）。闸门消费即清。仅主线程访问。
     */
    private var silentNext = false

    /** 请求下一次深浅放行不截屏、不播动画（见 [silentNext]） */
    fun requestSilent() {
        silentNext = true
    }

    /** 闸门消费静默标记：true = 本次放行跳过截图/动画 */
    fun consumeSilent(): Boolean {
        val v = silentNext
        silentNext = false
        return v
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}

// ==================== 过渡参数（SportLink 用户校准 2026-09-26） ====================
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
 * 主题切换过渡覆盖层：孔外=旧界面快照，孔内=直接透出真实新界面（被扫过的区域
 * 颜色立即变为目标颜色，不垫纯色、无收尾淡出）；圆孔边缘经 [FEATHER_PX] 羽化模糊，
 * 新旧两色柔和渐变。已内置在 [com.chen.powermeter.ui.theme.PowerMeterTheme] 内、
 * content 之上最后声明（勿在业务页面重复声明）。动画期间拦截全部触摸，扫满全屏后
 * 自动从组合中移除。
 */
@Composable
fun ThemeTransitionOverlay() {
    // 只播**本窗口自己截的**快照：二级页动画未播完就返回时，残留快照会在源页窗口重播
    // 一遍别人的旧界面（2026-09-28 二轮）。归属不符 → 不渲染，交由回收路径处理。
    val host = ThemeTransition.activityOf(LocalContext.current)
    val snap = ThemeTransition.snapshot?.takeIf { ThemeTransition.isOwnedBy(host) } ?: return
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
        ThemeTransition.clear(snap)
    }
    // 宿主组合销毁（Activity 结束 / 动画未播完就 finish）→ 就地回收快照：进度动画随组合
    // 取消时上面的 clear 永远不会执行，残留快照会被后续窗口重播（2026-09-28 二轮补，
    // 与 ModeTransitionOverlay 的处置同款）
    DisposableEffect(snap) {
        onDispose { ThemeTransition.clear(snap) }
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
