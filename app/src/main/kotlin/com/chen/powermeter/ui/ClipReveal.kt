package com.chen.powermeter.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * "从列表行向上下两个方向展开到下一界面"的转场调度（2026-09-25）。
 *
 * 交互（与 iOS 行展开 / Material Container Transform 的上下变体同类，但不是共享元素）：
 * 点击列表第 N 行 → 在本窗口 `android.R.id.content` 上插入一层全屏覆盖层
 * （[TouchBlockingHost] > [ClipRevealLayout] > 详情内容），初始被裁剪成落在该行
 * 垂直中心、高度为 0 的"线"，随后上边界扩到屏幕顶、下边界扩到屏幕底；
 * 详情内容延迟 [CONTENT_DELAY_MS] 后淡入 + 轻微上移，避免跟着边框被拉扯。
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
 *         createContent = { ctx, close -> buildDetail(ctx, close) },
 *         rowHighlight = { highlighted -> setRowHighlighted(row, highlighted) }, // 可选
 *     )
 * }
 * ```
 *
 * 用法（Compose 行 / 跨窗口）：先用 `Modifier.onGloballyPositioned` 采集行的窗口矩形
 * （与 `Modifier.containerSource` 同口径），再调：
 * ```
 * ClipReveal.openRevealAt(activity, rect.exactCenterY(), createContent, …)
 * ```
 * 跨 Activity 时在目标页 setContent 后调用，anchorYInWindow 传源页行中心的窗口 Y
 * （全屏窗口两页原点一致，可直接沿用源页坐标，同 `AppTransitions.installWindowTransform`
 * 的 rect 换算逻辑）。
 *
 * 动画公式（f: 0=锚点线 → 1=全屏；收拢反向）：
 * - clipTop    = anchorY * (1 - f)
 * - clipBottom = anchorY + (fullHeight - anchorY) * f
 */
object ClipReveal {

    private const val TAG = "ClipReveal"

    // SportLink 原版用 androidx.interpolator 的 FastOutSlowIn/FastOutLinearIn；PowerMeter
    // 依赖树里没有该库，PathInterpolator（minSdk 30 可用）以相同贝塞尔控制点等价替换。
    private val FastOutSlowInInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val FastOutLinearInInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)

    /** 展开时长（ms），配 FastOutSlowInInterpolator */
    const val OPEN_MS = 420L

    /** 收拢时长（ms），配 FastOutLinearInInterpolator */
    const val CLOSE_MS = 320L

    /** 详情内容浮现延迟（ms）：先让边框撑开一段，内容再淡入 + 上移归位 */
    const val CONTENT_DELAY_MS = 100L

    /** 内容浮现的初始下沉量（px 按 12dp 换算）：淡入同时轻微上移归位 */
    private const val CONTENT_SLIDE_DP = 12f

    /** 同一时刻只允许一个展开会话（窗口里只能有一层覆盖层） */
    private var current: Holder? = null

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

    /**
     * 从 [anchorView]（被点击的列表行）的垂直中心展开"下一界面"。
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
        return openRevealAt(
            activity, anchorYInWindow, backgroundColor, createContent, rowHighlight, onOpened, onClosed,
        )
    }

    /**
     * 从给定窗口 Y 坐标展开"下一界面"。[anchorYInWindow] = 锚点线的窗口坐标
     * （相对状态栏顶部的屏幕位置）。Compose 行/跨 Activity 场景用它（见类注释）。
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
                    // 行被滚出屏幕外时钳到 [0, height] 保证裁剪值合法
                    holder.beginOpen(
                        fullHeight = host.height.toFloat(),
                        anchorY = (anchorYInWindow - clipLoc[1]).coerceIn(0f, host.height.toFloat()),
                    )
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

    /** 页面背景色由调用方从 Compose 主题取色传入（ backgroundColor 参数），见 openRevealAt */

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

        /** 展开进度 f ∈ [0,1]（0=锚点线，1=全屏）；收拢从当前值继续，可随时打断进场 */
        private var progress = 0f
        private var fullHeight = 0f
        private var anchorY = 0f
        private var contentSlidePx = 0f

        /**
         * 进场（PreDraw 里调用：此刻覆盖层已布局完成）。
         * 先把裁剪钳到 0 高度的锚点线，再同时播"边框撑开"与"内容浮现"。
         */
        internal fun beginOpen(fullHeight: Float, anchorY: Float) {
            this.fullHeight = fullHeight
            this.anchorY = anchorY
            contentSlidePx = CONTENT_SLIDE_DP * host.resources.displayMetrics.density
            applyProgress(0f)
            // 内容初始态必须在动画启动前置好：contentFade 带 100ms startDelay，不预置的话
            // 前 100ms 内容以 alpha=1 全量出现在展开中的裁剪窗口里（目标页内容首帧即渲染，
            // 观感 = "先看到内容 → 被背景遮住 → 再淡入"），随后才被动画首帧拉回 0
            content?.let {
                it.alpha = 0f
                it.translationY = contentSlidePx
            }

            // 边框：上下边界从锚点线扩到全屏，420ms FastOutSlowIn
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
            // 内容：延迟 100ms 后淡入 + 轻微上移归位（12dp → 0），不跟着边框被拉扯
            val contentFade = content?.let { target ->
                ValueAnimator.ofFloat(0f, 1f).apply {
                    startDelay = CONTENT_DELAY_MS
                    duration = OPEN_MS - CONTENT_DELAY_MS
                    interpolator = FastOutSlowInInterpolator
                    addUpdateListener {
                        val c = it.animatedValue as Float
                        target.alpha = c
                        target.translationY = contentSlidePx * (1f - c)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (phase != Phase.OPENING) return
                            target.alpha = 1f
                            target.translationY = 0f
                        }
                    })
                }
            }
            openSet = AnimatorSet().apply {
                if (contentFade != null) playTogether(reveal, contentFade) else play(reveal)
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

            if (fullHeight <= 0f) {
                // beginOpen 尚未执行（PreDraw 还没跑）：屏幕上什么都没显示过，直接移除
                finishImmediately()
                return
            }
            val reveal = ValueAnimator.ofFloat(progress, 0f).apply {
                duration = CLOSE_MS
                interpolator = FastOutLinearInInterpolator
                addUpdateListener { applyProgress(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = finishImmediately()
                })
            }
            reveal.start()
        }

        /**
         * 跳过动画立即移除覆盖层（宿主组合被销毁等场景：Activity 重建/关闭时窗口
         * 本就要拆掉，没必要播 320ms 收拢）。已收拢/未展开时为 no-op。
         *
         * @param commit 是否触发 onClosed 收尾回调。Compose 宿主销毁路径应传 **false**：
         *   那条路径里 onCommit 写的是**正在销毁的组合**的状态，或会覆盖掉新一场转场
         *   的在途状态（2026-09-25 用户实测的概率性"切了但停在旧模式"即此竞态）——
         *   状态落地只应由收拢动画正常走完的路径（[close] → onAnimationEnd）触发。
         */
        fun dismissNow(commit: Boolean = true) = finishImmediately(commit)

        /** 应用进度 f → 裁剪窗口。上边界向 0 收、下边界向 fullHeight 收，对称于锚点线 */
        private fun applyProgress(f: Float) {
            progress = f
            clip.setClipBounds(
                anchorY * (1f - f),
                anchorY + (fullHeight - anchorY) * f,
            )
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
