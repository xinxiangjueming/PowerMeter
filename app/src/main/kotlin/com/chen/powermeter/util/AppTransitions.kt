package com.chen.powermeter.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.chen.powermeter.ui.ClipReveal
import com.chen.powermeter.ui.ClipRevealLayout
import java.util.WeakHashMap

/**
 * 插值器：SportLink 原版用 androidx.interpolator 的 FastOutSlowIn/FastOutLinearIn；
 * PowerMeter 依赖树里没有该库，PathInterpolator（minSdk 30 可用）以相同贝塞尔控制点
 * 等价替换（与 ui/ClipReveal 同一处理）。
 */
private val FastOutSlowInInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
private val FastOutLinearInInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)

/**
 * 容器变换的**源端锚点采集 + 跨 Activity 目标窗口手动驱动**（2026-09-26，移植自
 * SportLink utils/AppTransitions，与 ui/ClipReveal 覆盖层同一套 RevealGeometry 公式）。
 *
 * 职责：
 * 1. 源条目登记（`Modifier.containerSource` 采集窗口矩形，onClick 里 [register]）；
 * 2. [capture] 消费锚点，用 `View.draw` 把窗口画到 Bitmap 后裁出源条目 → [Capture]；
 * 3. 跨 Activity 展开转场：[launchWithTransform] 登记 Handoff + 普通启动（单次压制窗口
 *    滑动），[installWindowTransform] 在目标窗口内把页面根包进 ClipRevealLayout、首帧
 *    从源条目矩形四向撑开（含四角圆角插值 + 截图交叉淡变），收拢收回源矩形后 finish。
 *
 * **与 SportLink 原版的分叉**（本项目特有，勿"顺手删掉"）：
 * - 截图走 `View.draw` 同步绘制（PowerMeter 的 Compose 版本没有 `rememberGraphicsLayer`，
 *   官方 API 不可用），非 suspend；锚点矩形即窗口 decorView 坐标系，与 [Source.bounds] 同源；
 * - [Capture] 携带 bgColor（源页 Compose 主题 background）与源窗口宽高：前者替换
 *   resolveColorBackground（本项目 View 层主题的 colorBackground 深色模式下是白的，
 *   见 ModeRevealOverlay 的历史结论），后者供目标页做竖→横锚点换算（[anchorTransform]）；
 * - [installWindowTransform] 的 [anchorTransform] 参数：源页竖屏 / 目标页强制横屏时，
 *   锚点矩形需要跨方向换算（TrendFullscreenActivity.mapPortraitRectToWindow）；
 *   横→横（同方向）传 null 即 SportLink 原生行为。
 *
 * [start] 保留为普通启动入口（部分二级页 helper 经由它启动），行为 = 直接
 * startActivity，动画交给各 Activity 主题的 windowAnimationStyle 滑动。
 *
 * **历史教训（SportLink 2026-09-24 多轮实测，同架构适用）**：跨 Activity 的框架共享元素
 * 与半透明窗口互斥 → 全部硬切 + 注入视图残留；手写帧动画跨窗口进出均有闪烁；框架共享
 * 元素在本机 HyperOS 上目标页首帧必崩。故跨窗口转场 = Handoff 单例 + 目标窗口内
 * ClipReveal 手动驱动，零框架协调器、零共享元素。
 */
object AppTransitions {

    private const val TAG = "PowerMeterTransit"

    /** 锚点保鲜期：超时未消费即视为过期，避免陈旧条目把无关跳转的动画带偏 */
    private const val ANCHOR_TTL_MS = 1500L

    /** 源条目的窗口矩形（窗口/decorView 坐标系，由 Modifier.containerSource 采集） */
    class Source(internal val bounds: Rect = Rect())

    /**
     * 一次锚点截取结果：
     * - [bitmap] = 源条目截图（展开/收拢的"本体"交叉淡变，矩形做裁剪窗口）；
     * - [rect] = 源条目窗口矩形；
     * - [bgColor] = 源页 Compose 主题背景色（裁剪容器底色，与页面连续）；
     * - [sourceWidth]/[sourceHeight] = 源窗口宽高（目标页判方向：竖→横锚点换算用）。
     */
    class Capture(
        val bitmap: Bitmap,
        val rect: Rect,
        val bgColor: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    /** 打开时长（ms），与参考 HTML 一致（580） */
    const val OPEN_MS = 580L

    /** 关闭时长（ms），与参考 HTML 一致（420） */
    const val CLOSE_MS = 420L

    private var anchor: Source? = null
    private var anchorAtMs: Long = 0L

    /** 源条目在 onClick 内调用（`Modifier.containerSource` 已自动处理，一般不必手写） */
    fun register(source: Source) {
        if (source.bounds.isEmpty) return
        anchor = source
        anchorAtMs = SystemClock.uptimeMillis()
    }

    private fun consumeAnchor(): Source? {
        val current = anchor ?: return null
        anchor = null
        val fresh = SystemClock.uptimeMillis() - anchorAtMs <= ANCHOR_TTL_MS
        return if (fresh && !current.bounds.isEmpty) current else null
    }

    /**
     * 消费当前锚点并截图：把 [windowView]（decorView）画到 Bitmap 后按锚点矩形裁出。
     * 同步绘制（几 ms），无需 suspend；无锚点/锚点过期/越界返回 null，
     * 调用方自行降级（无动画直接显示）。
     */
    fun capture(windowView: View, bgColor: Int): Capture? {
        val source = consumeAnchor() ?: return null
        if (windowView.width <= 0 || windowView.height <= 0) return null
        val full = try {
            val b = Bitmap.createBitmap(
                windowView.width, windowView.height, Bitmap.Config.ARGB_8888,
            )
            windowView.draw(Canvas(b))
            b
        } catch (t: Throwable) {
            Log.w(TAG, "窗口截图失败 → 无动画降级", t)
            return null
        }
        val b = source.bounds
        // 矩形钳到窗口内（锚点不该出界，但钳一下防 createBitmap 越界崩）
        val left = b.left.coerceIn(0, windowView.width - 1)
        val top = b.top.coerceIn(0, windowView.height - 1)
        val right = b.right.coerceIn(left + 1, windowView.width)
        val bottom = b.bottom.coerceIn(top + 1, windowView.height)
        val cropped = try {
            Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        } catch (t: Throwable) {
            Log.w(TAG, "锚点裁剪失败 → 无动画降级", t)
            full.recycle()
            return null
        }
        full.recycle()
        Log.i(TAG, "capture rect=${b.toShortString()} bitmap=${cropped.width}x${cropped.height}")
        return Capture(
            cropped, Rect(b), bgColor,
            windowView.width, windowView.height,
        )
    }

    private fun resolveColorBackground(activity: Activity): Int {
        val tv = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.colorBackground, tv, true) &&
            tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            return tv.data
        }
        return android.graphics.Color.WHITE
    }

    // ============ 跨 Activity 上下展开（ClipReveal，目标窗口内手动驱动） ============
    //
    // 现行机制（SportLink 同款）：源端消费锚点 → Handoff 单例交接（普通 startActivity，
    // 单次压制主题滑动）→ 目标页 onPostCreate 把页面根包进 ClipRevealLayout，首帧从源
    // 条目矩形四向撑开（含四角圆角插值，截图与内容交叉淡变）；返回整页裁剪收回源条目
    // 矩形后 finish（收拢末帧压制窗口关闭转场，与源页无缝衔接）。

    /** Handoff 保鲜期：超时未消费即丢弃，防陈旧截图污染后续启动 */
    private const val HANDOFF_TTL_MS = 2000L

    private var handoff: Capture? = null
    private var handoffAtMs = 0L

    /** 收拢宿主（每 Activity 一个，装配进场时登记，收拢结束/取消时移除） */
    private class CollapseHost(
        val activity: Activity,
        val content: ViewGroup,
        val page: View,
        /** 源条目窗口矩形（已过 anchorTransform）：ClipReveal 展开起点 / 收拢终点 */
        val sourceRect: Rect = Rect(),
        /** 页面根外层的裁剪容器（由 installWindowTransform 包好；截图淡变经容器直驱） */
        val clipView: ClipRevealLayout? = null,
    ) {
        var closing = false
        var finishRequested = false

        /** 展开进度 f ∈ [0,1]：进场动画实时更新；无进场恒为 1（整页） */
        private var progress = 1f
        private var expandSet: Animator? = null

        /** 返回收拢：整页从当前进度裁剪收回源条目矩形（与 ui/ClipReveal.Holder 同一套公式） */
        fun requestClose() {
            if (closing || finishRequested) return
            closing = true
            Log.i(TAG, "requestClose activity=${activity.componentName?.shortClassName}")
            // 打断进场展开（若在播）：内容先归位，收拢从当前进度继续，不跳变
            expandSet?.cancel()
            expandSet = null
            page.alpha = 1f
            startClipCollapse()
        }

        /**
         * 进场展开（installWindowTransform 首帧 PreDraw 调用）：容器四边 + 圆角共用同一
         * 条缓动同步插值（卡片本体连续长大），卡片截图与页面内容全部钉在屏幕坐标上只做
         * 透明度交叉（纯 f 函数，见 ClipReveal.anchorAlphaAt/contentAlphaAt），
         * 内容永不形变、永不位移。
         */
        fun beginExpand(geometry: ClipReveal.RevealGeometry, clip: ClipRevealLayout) {
            progress = 0f
            geometry.applyTo(clip, 0f)
            page.alpha = ClipReveal.contentAlphaAt(0f)

            expandSet = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = OPEN_MS
                interpolator = FastOutSlowInInterpolator
                addUpdateListener {
                    val f = it.animatedValue as Float
                    progress = f
                    geometry.applyTo(clip, f)
                    // 纯交叉淡变、无位移：目标页若与锚点有同位元素（如底部主按钮），
                    // 位移会让两者错开形成"按钮错位"观感
                    page.alpha = ClipReveal.contentAlphaAt(f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (closing || finishRequested) return // 已被 requestClose 接管，终态归它
                        clip.clearClip()
                        page.alpha = 1f
                    }
                })
                start()
            }
        }

        /**
         * ClipReveal 收拢：整页从当前进度裁剪收回源条目矩形（左右边界收到卡片边缘、
         * 四角圆角收到卡片实际显示圆角），结束 finish。公式/时长/插值与
         * ui/ClipReveal.Holder 同一套（[ClipReveal.buildRevealGeometry]）。
         */
        private fun startClipCollapse() {
            val clip = clipView ?: run { finishNow(); return }
            val w = clip.width.toFloat()
            val h = clip.height.toFloat()
            if (w <= 0f || h <= 0f) {
                finishNow()
                return
            }
            val loc = IntArray(2)
            clip.getLocationInWindow(loc)
            val geometry = ClipReveal.buildRevealGeometry(
                width = w,
                height = h,
                anchorRectInWindow = sourceRect,
                anchorYInWindow = sourceRect.exactCenterY(),
                // 源条目 = LocalCornerRadius 控件：默认值即其实际显示圆角（小米系 = 屏幕物理圆角）
                anchorCornerRadiusPx = null,
                clipOriginX = loc[0].toFloat(),
                clipOriginY = loc[1].toFloat(),
                activity = activity,
            )
            // 从当前进度继续：收拢侧独立时间窗（内容前 55% 淡出、卡片截图 45% 后淡回），
            // 与进场窗口取 max/min，中途打断不跳变；纯交叉淡变无位移（防同位元素错开）
            val reveal = ValueAnimator.ofFloat(progress, 0f).apply {
                duration = CLOSE_MS
                interpolator = FastOutLinearInInterpolator
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    val c = anim.animatedFraction
                    geometry.applyTo(clip, f)
                    clip.setAnchorAlpha(maxOf(ClipReveal.anchorAlphaAt(f), ClipReveal.anchorAlphaIn(c)))
                    page.alpha = minOf(ClipReveal.contentAlphaAt(f), ClipReveal.contentAlphaOut(c))
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = finishNow()
                })
            }
            reveal.start()
        }

        private fun finishNow() {
            Log.i(TAG, "collapse finishNow activity=${activity.componentName?.shortClassName}")
            finishRequested = true
            collapseHosts.remove(activity)
            activity.finish()
            // 必须压制窗口关闭转场：收拢末帧整窗唯一可见内容 = 源卡片截图（其余区域已被
            // 裁剪成透明），主题的侧滑关闭动画会把这帧整窗滑出——用户看到"卡片残影向右
            // 滑出屏幕"。窗口内动画已播完且末帧与源页真卡片像素一致，直接无缝切回源页
            // （与 launchWithTransform 进场压制同理）
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    private val collapseHosts = WeakHashMap<Activity, CollapseHost>()

    /**
     * 跨 Activity 启动：登记 Handoff（源条目截图 + 矩形）+ 普通启动（单次压制窗口滑动，
     * 目标页动画由 [installWindowTransform] 手动播放）。无锚点/过期时降级为普通启动
     * （主题 windowAnimationStyle 滑动）。
     */
    fun launchWithTransform(activity: Activity, intent: Intent, capture: Capture?) {
        if (capture == null) {
            activity.startActivity(intent)
            return
        }
        handoff = capture
        handoffAtMs = SystemClock.uptimeMillis()
        Log.i(TAG, "launchWithTransform ${intent.component?.className?.substringAfterLast('.')}")
        activity.startActivity(intent)
        // 目标窗口内手动播 ClipReveal：单次压制本次启动的窗口滑动（避免与窗口内动画叠画）
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    /**
     * 目标页装配（各跨 Activity 页在 setContent / onPostCreate 之后调用一次）。
     * 有新鲜 Handoff → 页面根包进 ClipRevealLayout 并登记收拢宿主；无（通知启动 /
     * Handoff 过期）→ no-op，页面普通显示。
     *
     * [anchorTransform] = 锚点矩形换算钩子（可选）：源页与目标页**显示方向不同**时
     * （如本项目竖屏主页 → 强制横屏全屏页），源窗口坐标不能直接用，由目标页传入
     * "源矩形 → 目标窗口矩形"的换算（截图定位、展开几何、收拢终点三处统一应用）；
     * 同方向（横→横，SportLink 原生场景）传 null，锚点原样使用。
     */
    fun installWindowTransform(activity: Activity, anchorTransform: ((Capture) -> Rect)? = null) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val capture = consumePendingStart() ?: run {
            Log.i(TAG, "installWindowTransform: no fresh handoff, plain display (${activity.componentName?.shortClassName})")
            return
        }
        val anchorRect = anchorTransform?.invoke(capture) ?: capture.rect
        val page = content.getChildAt(0) ?: return
        Log.i(TAG, "installWindowTransform rect=${anchorRect.toShortString()} page=${page.javaClass.simpleName}")
        val contentOrigin = IntArray(2)
        content.getLocationInWindow(contentOrigin)
        // 页面根包进裁剪容器：进场在窗口内从源卡片矩形展开，返回整页收回
        val w = ClipRevealLayout(activity).apply {
            // 容器底色 = 页面背景：裁剪窗口内的底色与页面一致，收拢时不穿帮。
            // 用源页 Compose 主题色（Capture.bgColor）——本项目 View 层主题的
            // android:colorBackground 深色模式下与页面底色不符，不能直接用
            setBackgroundColor(capture.bgColor)
            // 卡片截图：按原始尺寸钉在源卡片屏幕位置（起始层），只随进度淡出、
            // 永不形变/位移（参考 HTML）
            setAnchorBitmap(
                capture.bitmap,
                (anchorRect.left - contentOrigin[0]).toFloat(),
                (anchorRect.top - contentOrigin[1]).toFloat(),
                (anchorRect.right - contentOrigin[0]).toFloat(),
                (anchorRect.bottom - contentOrigin[1]).toFloat(),
            )
        }
        content.removeViewInLayout(page)
        w.addView(
            page,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        content.addView(
            w,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val host = CollapseHost(
            activity, content, page,
            sourceRect = Rect(anchorRect),
            clipView = w,
        )
        collapseHosts[activity] = host

        // 进场展开：首帧 PreDraw 取消首绘 → 裁剪钳到源卡片矩形 → 四向撑开 + 内容延迟淡入
        w.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    w.viewTreeObserver.removeOnPreDrawListener(this)
                    if (collapseHosts[activity] !== host || w.width <= 0 || w.height <= 0) return true
                    val loc = IntArray(2)
                    w.getLocationInWindow(loc)
                    val geometry = ClipReveal.buildRevealGeometry(
                        width = w.width.toFloat(),
                        height = w.height.toFloat(),
                        anchorRectInWindow = anchorRect,
                        anchorYInWindow = anchorRect.exactCenterY(),
                        // 源条目 = LocalCornerRadius 控件：默认值即其实际显示圆角
                        anchorCornerRadiusPx = null,
                        clipOriginX = loc[0].toFloat(),
                        clipOriginY = loc[1].toFloat(),
                        activity = activity,
                    )
                    host.beginExpand(geometry, w)
                    return false
                }
            },
        )
    }

    /** 统一收尾入口：有收拢宿主 → 播收拢动画后 finish；无 → 直接 finish */
    fun finishWithTransform(activity: Activity) {
        if (!collapseAndFinish(activity)) activity.finish()
    }

    /**
     * 播收拢动画并在结束时 finish。返回 false = 没有收拢宿主或收拢已请求完毕
     * （调用方落回原 finish 路径）；返回 true = 收拢动画接管（重复 finish 被吞，
     * 防动画中途被二次打断）。
     */
    fun collapseAndFinish(activity: Activity): Boolean {
        val host = collapseHosts[activity] ?: return false
        if (host.finishRequested) return false
        if (host.closing) return true
        host.requestClose()
        return true
    }

    private fun consumePendingStart(): Capture? {
        val capture = handoff ?: return null
        handoff = null
        val fresh = SystemClock.uptimeMillis() - handoffAtMs <= HANDOFF_TTL_MS
        return if (fresh && !capture.rect.isEmpty) capture else null
    }
}
