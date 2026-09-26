package com.chen.powermeter.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.chen.powermeter.ui.theme.getScreenCornerRadius
import com.chen.powermeter.ui.theme.screenCornerRadiusPx

/**
 * "从列表行向上下两个方向展开到下一界面"的转场调度（2026-09-25）。
 *
 * 交互（iOS 行展开 / Material Container Transform 的同窗口实现，不是共享元素）：
 * 点击列表第 N 行 → 在本窗口 `android.R.id.content` 上插入一层全屏覆盖层
 * （[TouchBlockingHost] > [ClipRevealLayout] > 详情内容），初始被裁剪成**锚点卡片
 * 矩形本体**——窗口矩形/四角圆角都与卡片一致，窗口内铺卡片截图（像素级连续，
 * 见 [RevealGeometry]），随后卡片四边同时向外撑开铺满全屏；卡片截图与详情内容在
 * 展开中段交叉淡变（截图淡出 / 内容淡入 + 上移归位）。收拢反向：
 * 内容淡出 / 截图淡回，窗口收回卡片矩形后移除覆盖层，与真卡片无缝衔接。
 * 四角圆角从锚点控件实际显示圆角插值到屏幕物理圆角（小米系 = HyperOS
 * rounded_corner_radius_top，与系统显示遮罩同源，收尾无缝）。
 * 返回时反向收回（从当前进度继续，不要求进场已播完），结束后 removeView 露出列表。
 *
 * 实现要点（都是踩过坑的硬约束）：
 * - **裁剪而非 scaleY**：内容全尺寸布局，只有显示范围变化，无拉伸（见 [ClipRevealLayout]）；
 * - **锚点用窗口坐标**：`getLocationInWindow` 取行与裁剪容器的位置再相减
 *   （anchorY = rowY - clipY + row.height/2），层级差/状态栏差/fitsSystemWindows 差全部
 *   在相减中抵消，不要用 getY()（相对父级，层级不同必错）；
 * - **布局时机**：挂在覆盖层首帧 PreDraw 上（layout 已完成、height 非 0），并返回 false
 *   取消首帧绘制，防止裁剪生效前详情整页先闪现一帧；
 * - **触摸**：覆盖层在动画期间吞掉全部触摸（禁止点到未展开区域/穿透到列表），
 *   展开完成后未被子内容消费的触摸也由覆盖层兜住，同样不穿透；
 * - **返回键**：androidx [OnBackPressedCallback]（与预测性返回/OnBackInvoked 兼容），
 *   收拢结束才 removeView + remove 回调。
 *
 * 用法（View 行）：
 * ```
 * row.setOnClickListener {
 *     reveal = ClipReveal.openReveal(
 *         activity = this,
 *         anchorView = row,                                  // 被点击的行（任意层级都行）
 *         backgroundColor = pageBackgroundColor,             // Compose 主题底色（见 openRevealAt）
 *         createContent = { ctx, close -> buildDetail(ctx, close) },
 *         rowHighlight = { highlighted -> setRowHighlighted(row, highlighted) }, // 可选
 *     )
 * }
 * ```
 *
 * 用法（Compose 行 / 跨窗口）：先用 `Modifier.onGloballyPositioned` 采集行的窗口矩形
 * （`LayoutCoordinates.boundsInWindow()`，与 `Modifier.containerSource` 同口径），再调：
 * ```
 * ClipReveal.openRevealAt(activity, rect.exactCenterY(), …, anchorRectInWindow = rect)
 * ```
 * 跨 Activity 时在目标页 setContent 后调用，anchorYInWindow 传源页行中心的窗口 Y
 * （全屏窗口两页原点一致，可直接沿用源页坐标，同 `AppTransitions.installWindowTransform`
 * 的 rect 换算逻辑）。
 *
 * 动画公式（f: 0=锚点卡片矩形 → 1=全屏；收拢反向；实现见 [RevealGeometry.applyTo]）：
 * - left    = anchorLeft * (1 - f)                        （锚点矩形缺省 = 全宽/中心线）
 * - right   = width - (width - anchorRight) * (1 - f)
 * - top     = anchorTop * (1 - f)
 * - bottom  = height - (height - anchorBottom) * (1 - f)
 * - cornerR = startRadius + (endRadius - startRadius) * f （小米系两端相等 = 恒定）
 */
object ClipReveal {

    private const val TAG = "ClipReveal"

    // SportLink 原版用 androidx.interpolator 的 FastOutSlowIn/FastOutLinearIn；PowerMeter
    // 依赖树里没有该库，PathInterpolator（minSdk 30 可用）以相同贝塞尔控制点等价替换。
    private val FastOutSlowInInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val FastOutLinearInInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)

    /** 展开时长（ms），配 FastOutSlowInInterpolator（参考 HTML：580） */
    const val OPEN_MS = 580L

    /** 收拢时长（ms）（参考 HTML：420） */
    const val CLOSE_MS = 420L

    /** 卡片截图淡出完成点（f）：0→0.22 快速淡出（用户反馈：太慢要快点） */
    internal const val ANCHOR_FADE_END = 0.22f

    /** 详情内容淡入窗口（f）：0.48→0.88，展开结束前就开始显示（参考 HTML 同参） */
    internal const val CONTENT_IN_START = 0.48f
    internal const val CONTENT_IN_END = 0.88f

    /** 收拢侧时间窗（raw = 收拢已播比例）：内容在前 55% 淡出（参考 HTML raw/0.55） */
    internal const val CONTENT_OUT_END = 0.55f

    /** 收拢侧：列表文字（卡片截图）从 raw 45% 之后才淡回（用户反馈：出现太早要晚点） */
    internal const val ANCHOR_IN_START = 0.45f

    /** 内容浮现的初始下沉量（px 按 12dp 换算）：淡入同时轻微上移归位（AppTransitions 进场共用） */
    internal const val CONTENT_SLIDE_DP = 12f

    /** 同一时刻只允许一个展开会话（窗口里只能有一层覆盖层） */
    private var current: Holder? = null

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

    /**
     * 从 [anchorView]（被点击的列表行）的垂直中心展开"下一界面"。
     * 锚点矩形与实际显示圆角自动从 [anchorView] 采集（窗口 bounds + 背景 drawable 的
     * outline；读不到圆角时走 [buildRevealGeometry] 的默认值），展开/收拢的窗口形状
     * 与四角圆角都从它起算。
     *
     * @param activity androidx [ComponentActivity]（AppCompatActivity 均满足），
     *   需要它的 [ComponentActivity.getOnBackPressedDispatcher] 接管返回键
     * @param createContent 构建详情内容 View；入参 [close] 直接绑定收拢动作，
     *   供详情页自己的返回按钮调用（Holder 在工厂执行时尚未返回，故经此闭包转发）
     * @param rowHighlight 行高亮回调：展开开始传 true，展开结束/收拢开始传 false
     *   （高亮样式由调用方自定，例如换卡片描边/底色）
     * @return 会话句柄，可在任意时机调 [Holder.close] 收拢
     */
    fun openReveal(
        activity: ComponentActivity,
        anchorView: View,
        backgroundColor: Int,
        createContent: (context: Context, close: () -> Unit) -> View,
        rowHighlight: ((Boolean) -> Unit)? = null,
        onOpened: (() -> Unit)? = null,
        onClosed: (() -> Unit)? = null,
    ): Holder {
        val loc = IntArray(2)
        anchorView.getLocationInWindow(loc)
        // 行的垂直中心（窗口坐标）。height 为 0（未测量）时退化用行顶，可接受
        val anchorYInWindow = loc[1] + anchorView.height / 2f
        // 实际显示圆角 = 背景 drawable 的 outline；读不到（无背景/方角）传 null 走默认值
        val outline = Outline()
        anchorView.background?.getOutline(outline)
        val cornerRadiusPx = if (!outline.isEmpty && outline.radius > 0f) outline.radius else null
        val anchorRect = Rect(loc[0], loc[1], loc[0] + anchorView.width, loc[1] + anchorView.height)
        return openRevealAt(
            activity, anchorYInWindow, backgroundColor, createContent, rowHighlight, onOpened, onClosed,
            anchorRect, cornerRadiusPx,
        )
    }

    /**
     * 从给定窗口 Y 坐标展开"下一界面"。[anchorYInWindow] = 锚点线的窗口坐标
     * （相对状态栏顶部的屏幕位置）。Compose 行/跨 Activity 场景用它（见类注释）。
     *
     * @param anchorRectInWindow 锚点控件完整窗口矩形（可选）：传入时展开窗口四边都从
     *   它起算——是卡片本体在长大（一镜到底的完整形态）；null/退化 = 过锚点线的
     *   全宽线展开（旧行为兜底）
     * @param anchorCornerRadiusPx 锚点控件实际显示圆角 px（可选）：null = 用
     *   [getScreenCornerRadius]（小米系即屏幕物理圆角，与 LocalCornerRadius 控件同源）
     * @param anchorBitmap 锚点卡片截图（可选）：铺在窗口起点处与详情内容交叉淡变，
     *   实现"卡片本体 ↔ 整页"的像素级连续；null = 纯裁剪无截图
     */
    fun openRevealAt(
        activity: ComponentActivity,
        anchorYInWindow: Float,
        /**
         * 覆盖层底色 = **目标页面的真实背景色**。必须由调用方从 Compose 主题取
         * （colorScheme.background，随深浅色/动态取色走）——View 层主题的
         * android:colorBackground 与 Compose 页面底色不是一回事，拿它会闪白
         * （2026-09-25 用户实测：深色模式下展开动画是白的）。
         */
        backgroundColor: Int,
        createContent: (context: Context, close: () -> Unit) -> View,
        rowHighlight: ((Boolean) -> Unit)? = null,
        onOpened: (() -> Unit)? = null,
        onClosed: (() -> Unit)? = null,
        anchorRectInWindow: Rect? = null,
        anchorCornerRadiusPx: Float? = null,
        anchorBitmap: android.graphics.Bitmap? = null,
    ): Holder {
        current?.let {
            Log.w(TAG, "已有一个展开会话，忽略本次 openReveal")
            return it
        }
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: error("ClipReveal: activity 未 setContentView，找不到 android.R.id.content")

        val host = TouchBlockingHost(activity)
        val clip = ClipRevealLayout(activity).apply {
            // 容器底色 = 页面背景：内容淡入期间（alpha < 1）透出的必须是详情页自己的
            // 底色而不是下面的列表（背景随内容一起被裁剪，收起时不穿帮）
            setBackgroundColor(backgroundColor)
        }
        val holder = Holder(host, clip, rowHighlight, onOpened, onClosed)
        current = holder

        val detail = createContent(activity) { holder.close() }
        holder.content = detail
        clip.addView(detail, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        host.addView(clip, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        contentRoot.addView(host, MATCH_PARENT, MATCH_PARENT)

        // 返回键收拢：androidx 回调在 dispatcher 栈顶，优先于 Activity 既有回调
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = holder.close()
        }
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        holder.backCallback = backCallback

        // 行高亮：进场动画期间保持，进场结束由 Holder 取消（收拢开始也会取消）
        rowHighlight?.invoke(true)

        // 必须等覆盖层布局完成（height 非 0）才能算锚点/全屏高 → 挂首帧 PreDraw。
        // 返回 false 取消首帧绘制：此刻裁剪尚未启动，否则详情整页先闪现一帧再开始动画。
        // （与 ui/DeviceDetailOverlay 同一套时序，真机验证过不能用 overlay.post 代替）
        host.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    host.viewTreeObserver.removeOnPreDrawListener(this)
                    if (holder.phase == Holder.Phase.CLOSED) return true
                    if (host.width <= 0 || host.height <= 0) {
                        holder.showPlain()
                        return true
                    }
                    val clipLoc = IntArray(2)
                    clip.getLocationInWindow(clipLoc)
                    // 窗口坐标相减：状态栏/导航栏/fitsSystemWindows 造成的偏移全部抵消；
                    // 锚点矩形/中心线钳到容器内保证裁剪值合法
                    val geometry = buildRevealGeometry(
                        width = host.width.toFloat(),
                        height = host.height.toFloat(),
                        anchorRectInWindow = anchorRectInWindow,
                        anchorYInWindow = anchorYInWindow,
                        anchorCornerRadiusPx = anchorCornerRadiusPx,
                        clipOriginX = clipLoc[0].toFloat(),
                        clipOriginY = clipLoc[1].toFloat(),
                        activity = activity,
                    )
                    // 卡片截图交给裁剪容器：按原始尺寸钉在原屏幕位置（起始层），只随
                    // 进度淡出，永不形变/位移（参考 HTML）
                    if (anchorBitmap != null && anchorRectInWindow != null) {
                        clip.setAnchorBitmap(
                            anchorBitmap,
                            (anchorRectInWindow.left - clipLoc[0]).toFloat(),
                            (anchorRectInWindow.top - clipLoc[1]).toFloat(),
                            (anchorRectInWindow.right - clipLoc[0]).toFloat(),
                            (anchorRectInWindow.bottom - clipLoc[1]).toFloat(),
                        )
                    } else {
                        clip.setAnchorBitmap(null, 0f, 0f, 0f, 0f)
                    }
                    holder.beginOpen(geometry)
                    return false
                }
            },
        )
        return holder
    }

    /** 收拢当前展开会话（若在播）。返回 true = 已接管收拢 */
    fun closeActive(): Boolean {
        val holder = current ?: return false
        holder.close()
        return true
    }

    /**
     * 卡片截图透明度：f 的**纯函数**（0→0.38 淡出；收拢反向即淡回）。纯函数驱动 =
     * 进场/收拢/中途打断共用同一状态，永不跳变（参考 HTML：起始层 0%→38% 淡出）。
     */
    internal fun anchorAlphaAt(f: Float): Float =
        1f - (f / ANCHOR_FADE_END).coerceIn(0f, 1f)

    /**
     * 详情内容透明度：f 的**纯函数**（0.48→0.88 淡入；收拢反向即淡出）。展开还没结束
     * 内容就已开始显示（参考 HTML：结束层 48%→88%）。
     */
    internal fun contentAlphaAt(f: Float): Float =
        ((f - CONTENT_IN_START) / (CONTENT_IN_END - CONTENT_IN_START)).coerceIn(0f, 1f)

    /** 收拢侧卡片截图淡入：raw 45%→100%（用户反馈出现太早要晚点） */
    internal fun anchorAlphaIn(raw: Float): Float =
        ((raw - ANCHOR_IN_START) / (1f - ANCHOR_IN_START)).coerceIn(0f, 1f)

    /** 收拢侧内容淡出：raw 0→55%（参考 HTML close：endLayer raw/0.55） */
    internal fun contentAlphaOut(raw: Float): Float =
        1f - (raw / CONTENT_OUT_END).coerceIn(0f, 1f)

    /**
     * 一次展开的几何（裁剪容器坐标系），f ∈ [0,1]：0 = 锚点卡片矩形本体，1 = 全屏。
     * **照参考 HTML**：容器 left/top/width/height/border-radius 共用同一条缓动同步
     * 插值——窗口本身就是那块连续长大的圆角表面；两层内容（卡片截图/下一界面）全部
     * 钉在屏幕坐标上纹丝不动，只做透明度交叉（见 [anchorAlphaAt]/[contentAlphaAt]），
     * 内容永不形变、永不位移。
     * ui/ClipReveal 与 utils/AppTransitions 的收拢共用这一个公式，禁止各写一份。
     */
    internal class RevealGeometry(
        private val width: Float,
        private val height: Float,
        private val anchorLeft: Float,
        private val anchorTop: Float,
        private val anchorRight: Float,
        private val anchorBottom: Float,
        private val startRadius: Float,
        private val endRadius: Float,
    ) {
        /** 把进度 f 对应的裁剪窗口（矩形 + 圆角）应用到 [clip]。透明度由调用方按进/收拢时间窗驱动 */
        fun applyTo(clip: ClipRevealLayout, f: Float) {
            clip.setClipBounds(
                anchorLeft * (1f - f),
                anchorTop * (1f - f),
                width - (width - anchorRight) * (1f - f),
                height - (height - anchorBottom) * (1f - f),
                startRadius + (endRadius - startRadius) * f,
            )
        }
    }

    /**
     * 计算展开几何：锚点矩形换算到裁剪容器坐标系（窗口坐标相减，层级差/状态栏差/
     * fitsSystemWindows 差全部抵消），并钳到容器内保证裁剪值合法。
     * 锚点矩形缺省/退化（宽或高 ≤ 0 / 钳后为空）时退化为旧行为：全宽 + 过
     * [anchorYInWindow] 的水平线。
     */
    internal fun buildRevealGeometry(
        width: Float,
        height: Float,
        anchorRectInWindow: Rect?,
        anchorYInWindow: Float,
        anchorCornerRadiusPx: Float?,
        clipOriginX: Float,
        clipOriginY: Float,
        activity: Activity,
    ): RevealGeometry {
        val rect = anchorRectInWindow?.takeIf { it.width() > 0 && it.height() > 0 }
        var anchorLeft = 0f
        var anchorRight = width
        var anchorTop = ((rect?.exactCenterY() ?: anchorYInWindow) - clipOriginY).coerceIn(0f, height)
        var anchorBottom = anchorTop
        if (rect != null) {
            val left = (rect.left - clipOriginX).coerceIn(0f, width)
            val right = (rect.right - clipOriginX).coerceIn(0f, width)
            val top = (rect.top - clipOriginY).coerceIn(0f, height)
            val bottom = (rect.bottom - clipOriginY).coerceIn(0f, height)
            if (right > left && bottom > top) {
                anchorLeft = left
                anchorRight = right
                anchorTop = top
                anchorBottom = bottom
            }
        }
        val startRadius = anchorCornerRadiusPx ?: defaultAnchorRadiusPx(activity)
        return RevealGeometry(
            width, height, anchorLeft, anchorTop, anchorRight, anchorBottom,
            startRadius, screenEndRadiusPx(activity),
        )
    }

    /** 锚点圆角缺省值 = 全 App 控件实际显示圆角（LocalCornerRadius 同源）：小米系 = 屏幕物理圆角，其余 28dp */
    private fun defaultAnchorRadiusPx(activity: Activity): Float =
        getScreenCornerRadius(activity).value * activity.resources.displayMetrics.density

    /**
     * 展开终态圆角 = 屏幕物理圆角：小米系读 HyperOS rounded_corner_radius_top（与系统
     * 显示遮罩同源，收尾 clearClip 时四角与遮罩无缝）；其余设备 API31+ 读 display 圆角，
     * 直角屏/低版本为 0。
     */
    private fun screenEndRadiusPx(activity: Activity): Float {
        screenCornerRadiusPx(activity)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { return it.radius.toFloat() }
        }
        return 0f
    }

    /**
     * 动画期间吞掉全部触摸的覆盖层根：
     * - blockTouch=true（进场/收拢中）：dispatchTouchEvent 直接消费，事件到不了
     *   详情内容，更到不了列表（列表是本层的兄弟节点，父级不会再分发）；
     * - blockTouch=false（展开完成）：事件正常进入详情内容；详情未消费的区域
     *   落在本层（clickable）也不会穿透到列表。
     */
    internal class TouchBlockingHost(context: Context) : FrameLayout(context) {
        var blockTouch = true

        init {
            isClickable = true
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (blockTouch) return true
            return super.dispatchTouchEvent(ev)
        }
    }

    /** 一次展开的会话句柄。持有收拢入口 [close]；展开/收拢动画在此驱动 */
    class Holder internal constructor(
        private val host: TouchBlockingHost,
        private val clip: ClipRevealLayout,
        private val rowHighlight: ((Boolean) -> Unit)?,
        private val onOpened: (() -> Unit)?,
        private val onClosed: (() -> Unit)?,
    ) {
        /**
         * 详情内容 View。[openRevealAt] 在 [openReveal]、工厂闭包（需要先拿到 close）
         * 执行完之后注入，进场动画对它做延迟淡入 + 上移。
         */
        internal var content: View? = null

        internal enum class Phase { OPENING, OPEN, CLOSING, CLOSED }

        internal var phase = Phase.OPENING
            private set

        internal var backCallback: OnBackPressedCallback? = null
        private var openSet: AnimatorSet? = null

        /** 展开进度 f ∈ [0,1]（0=锚点卡片矩形，1=全屏）；收拢从当前值继续，可随时打断进场 */
        private var progress = 0f

        /** 展开几何（锚点矩形 + 圆角插值）；null = beginOpen 尚未执行（PreDraw 还没跑） */
        private var geometry: RevealGeometry? = null
        private var contentSlidePx = 0f

        /**
         * 进场（PreDraw 里调用：此刻覆盖层已布局完成）。
         * 裁剪钳到锚点卡片矩形本体，同时播"卡片撑开 + 截图淡出 ↔ 内容淡入"交叉淡变
         * （截图由 [ClipRevealLayout] 钉在原屏幕位置，卡片本体随窗口一起长大）。
         */
        internal fun beginOpen(geometry: RevealGeometry) {
            this.geometry = geometry
            contentSlidePx = CONTENT_SLIDE_DP * host.resources.displayMetrics.density
            applyProgress(0f)
            // 内容初始态必须在动画启动前置好：内容淡入窗口从 f=0.48 才开始，不预置的话
            // 首 48% 内容以 alpha=1 全量出现在展开中的裁剪窗口里，随后才被动画首帧拉回 0

            // 窗口：容器 left/top/width/height/圆角共用同一条缓动同步插值（参考 HTML），
            // 580ms FastOutSlowIn；卡片截图与内容的透明度都是 f 的纯函数（见 applyProgress）
            val reveal = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = OPEN_MS
                interpolator = FastOutSlowInInterpolator
                addUpdateListener { applyProgress(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (phase != Phase.OPENING) return // 已被 close() 接管，终态交给收拢流程
                        phase = Phase.OPEN
                        clip.clearClip()
                        host.blockTouch = false
                        rowHighlight?.invoke(false)
                        onOpened?.invoke()
                    }
                })
            }
            openSet = AnimatorSet().apply {
                play(reveal)
                start()
            }
        }

        /** 布局异常兜底（宽高为 0）：跳过动画直接进入稳态，保证详情可用 */
        internal fun showPlain() {
            phase = Phase.OPEN
            clip.clearClip()
            host.blockTouch = false
            rowHighlight?.invoke(false)
            onOpened?.invoke()
        }

        /**
         * 反向收拢到锚点行，结束后移除覆盖层。
         * 可在任意阶段调用：进场未完成时先取消进场，再从当前进度继续收拢（不跳变）。
         */
        fun close() {
            when (phase) {
                Phase.CLOSED, Phase.CLOSING -> return
                Phase.OPENING -> {
                    // 先改 phase 再 cancel：进场动画的 onEnd 检查 phase，跳过其终态处理
                    phase = Phase.CLOSING
                    openSet?.cancel()
                    openSet = null
                }
                Phase.OPEN -> phase = Phase.CLOSING
            }
            backCallback?.isEnabled = false
            rowHighlight?.invoke(false)
            host.blockTouch = true

            if (geometry == null) {
                // beginOpen 尚未执行（PreDraw 还没跑）：屏幕上什么都没显示过，直接移除
                finishImmediately()
                return
            }
            val reveal = ValueAnimator.ofFloat(progress, 0f).apply {
                duration = CLOSE_MS
                interpolator = FastOutLinearInInterpolator
                // 收拢侧独立时间窗（参考 HTML close）：内容前 55% 淡出、列表文字 45% 后
                // 才淡回；与进场窗口取 max/min，中途打断时状态连续不跳变
                addUpdateListener {
                    val f = it.animatedValue as Float
                    val c = it.animatedFraction
                    progress = f
                    geometry?.applyTo(clip, f)
                    clip.setAnchorAlpha(maxOf(anchorAlphaAt(f), anchorAlphaIn(c)))
                    val ca = minOf(contentAlphaAt(f), contentAlphaOut(c))
                    content?.let {
                        it.alpha = ca
                        it.translationY = contentSlidePx * (1f - ca)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = finishImmediately()
                })
            }
            reveal.start()
        }

        /**
         * 跳过动画立即移除覆盖层（宿主组合被销毁等场景：Activity 重建/关闭时窗口
         * 本就要拆掉，没必要播收拢）。已收拢/未展开时为 no-op。
         *
         * @param commit 是否触发 onClosed 收尾回调。Compose 宿主销毁路径应传 **false**：
         *   那条路径里 onCommit 写的是**正在销毁的组合**的状态，或会覆盖掉新一场转场
         *   的在途状态（2026-09-25 用户实测的概率性"切了但停在旧模式"即此竞态）——
         *   状态落地只应由收拢动画正常走完的路径（[close] → onAnimationEnd）触发。
         */
        fun dismissNow(commit: Boolean = true) = finishImmediately(commit)

        /** 进场进度 f → 裁剪窗口 + 截图/内容透明度（进场时间窗：截图 0→0.22 淡出、内容 0.48→0.88 淡入） */
        private fun applyProgress(f: Float) {
            progress = f
            geometry?.applyTo(clip, f)
            clip.setAnchorAlpha(anchorAlphaAt(f))
            val ca = contentAlphaAt(f)
            content?.let {
                it.alpha = ca
                it.translationY = contentSlidePx * (1f - ca)
            }
        }

        private fun finishImmediately(runCallbacks: Boolean = true) {
            if (phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            clip.clearClip()
            (host.parent as? ViewGroup)?.removeView(host)
            backCallback?.remove()
            backCallback = null
            if (current === this) current = null
            if (runCallbacks) onClosed?.invoke()
        }
    }
}
