package com.chen.powermeter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 纵向裁剪容器 —— "从列表行向上下展开到下一界面"转场的核心（2026-09-25）。
 *
 * 原理：动画期间把自身绘制裁剪到 [setClipBounds] 给出的**圆角矩形窗口**内——窗口
 * 从锚点行中心一条圆头线向上下（及左右）撑开到全屏，视觉上就是下一界面从锚点控件
 * 长出来。窗口四角圆角半径随进度在"锚点控件实际圆角 → 屏幕物理圆角"间插值（数值由
 * ui/ClipReveal 的 RevealGeometry 驱动）：起始细条呈圆头、展开终态四角与系统屏幕
 * 圆角遮罩对齐，满足一镜到底的形状连续性。
 *
 * **为什么裁剪而不是 scaleY**：内容始终按全尺寸布局，只是显示范围变化，
 * 文字/图片不会被拉伸变形；scaleY 会把整页内容压扁再展开。
 *
 * **圆角怎么裁（双层保险）**：
 * 1. [clipToOutline] + [ViewOutlineProvider]：RenderNode 级圆角裁剪，GPU 抗锯齿
 *    （canvas.clipPath 不抗锯齿，静止的圆角边缘会发毛）；
 * 2. [draw] 里的 canvas.clipRect 直角兜底：若个别 ROM 对"小于自身 bounds 的 outline
 *    子矩形裁剪"行为异常，退化成旧版直角窗口，不穿帮。
 * 两层裁剪取交集 = 圆角窗口。零尺寸窗口整帧不画，防止空/退化 outline 被忽略时闪现一帧。
 *
 * **稳态绝不能把 outline 置空**（真机 HyperOS 实锤 2026-09-25）：clipToOutline=true 时
 * 空 outline 会把整页裁没——动画结束后详情页"消失"透出下层列表、点击被吞、看起来像
 * 卡死。因此稳态 outline 给全屏矩形（裁剪=自身 bounds，视觉无效果），且 [clearClip]
 * 直接关闭 clipToOutline，稳态行为与无裁剪完全一致。
 *
 * **为什么在 [draw] 里兜底裁剪而不是 dispatchDraw**：View.draw() 的顺序是
 * background → onDraw → dispatchDraw（children）→ 前景，canvas 层的 clip 能让
 * "详情页底色（本容器的 background）"和"详情内容（children）"一起被裁剪；
 * 若只裁 dispatchDraw，收起动画中容器底色会先于内容露出，穿帮。
 *
 * 独立成容器类（而非把逻辑写进调度器）是为了也可以在 XML 里直接声明使用：
 * `<com.chen.powermeter.ui.ClipRevealLayout …/>`，动画数值仍由代码驱动。
 */
class ClipRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var clipLeft = 0f
    private var clipTop = 0f
    private var clipRight = 0f
    private var clipBottom = 0f
    private var cornerRadius = 0f

    /**
     * 锚点卡片截图：**整行跟随窗口上边移动**（层级 = 底色之上、子内容之下）——宽高
     * 恒为原始像素（不拉伸不缩放），顶边钉在窗口上边：展开时随上边上行并渐隐
     * （"向上移动消失"），收拢时随上边下行归位浮现（"向下移动出现"）。
     * 是否可见由裁剪窗口与 [setAnchorAlpha] 共同决定。
     */
    private var anchorBitmap: Bitmap? = null
    private val anchorRect = RectF()
    private val anchorDst = RectF()
    private var anchorAlpha = 0f
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 是否带有锚点截图（调用方据此决定是否做内容 ↔ 截图交叉淡变） */
    val hasAnchorBitmap: Boolean
        get() = anchorBitmap != null

    /** 设置锚点卡片截图及其屏幕固定矩形（容器坐标系，原尺寸原位置） */
    fun setAnchorBitmap(bitmap: Bitmap?, left: Float, top: Float, right: Float, bottom: Float) {
        anchorBitmap = bitmap
        anchorRect.set(left, top, right, bottom)
        invalidate()
    }

    /** 锚点截图透明度（0~1）：与页面内容交叉淡变，由动画逐帧驱动 */
    fun setAnchorAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        if (clamped != anchorAlpha) {
            anchorAlpha = clamped
            invalidate()
        }
    }

    /** 是否处于裁剪状态。动画结束后 [clearClip] 关闭，稳态无 outline 开销 */
    var clipping = false
        private set

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (!clipping) {
                    // 稳态：全屏矩形（裁剪=自身 bounds，视觉无效果）。绝不能 setEmpty()——
                    // clipToOutline=true 时空 outline 会把整页裁没（真机 HyperOS 实锤）
                    outline.setRect(0, 0, view.width, view.height)
                    return
                }
                // 半径超过窗口短边一半时 RenderNode 行为未定义，钳成圆头（起始细条即此形态）
                val r = cornerRadius.coerceAtLeast(0f)
                    .coerceAtMost(minOf(clipRight - clipLeft, clipBottom - clipTop) / 2f)
                if (r > 0f) {
                    // Outline 只有 Int 坐标重载（API 30+ 才有 float 版，minSdk 28 不满足）：
                    // 取整只影响圆角部分（≤1px），移动边缘的亚像素精度由 draw() 的 canvas clip 保持
                    outline.setRoundRect(
                        clipLeft.roundToInt(), clipTop.roundToInt(),
                        clipRight.roundToInt(), clipBottom.roundToInt(), r,
                    )
                } else {
                    outline.setRect(
                        clipLeft.roundToInt(), clipTop.roundToInt(),
                        clipRight.roundToInt(), clipBottom.roundToInt(),
                    )
                }
            }
        }
    }

    /**
     * 设置当前显示窗口（本容器自身坐标系：x ∈ [0, width]，y ∈ [0, height]）与四角
     * 圆角半径。top == bottom 时高度为 0（初始的"一条线"）；窗口为全屏且半径为 0
     * 时即全量显示。
     */
    fun setClipBounds(left: Float, top: Float, right: Float, bottom: Float, cornerRadiusPx: Float) {
        clipLeft = left
        clipTop = top
        clipRight = right
        clipBottom = bottom
        cornerRadius = cornerRadiusPx
        clipping = true
        clipToOutline = true
        invalidateOutline()
        invalidate()
    }

    /**
     * 解除裁剪，恢复完整绘制（展开完成后的稳态）。关闭 clipToOutline：稳态与无裁剪
     * 完全一致（outline 裁剪只在动画期间参与；详见类注释"稳态绝不能把 outline 置空"）
     */
    fun clearClip() {
        clipping = false
        clipToOutline = false
        invalidateOutline()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = anchorBitmap
        // 内容整行跟随窗口上边移动（宽高恒为原始像素，纯位移无形变）：展开时随上边
        // 上行渐隐、收拢时随上边下行归位；f=0 时与真卡片逐像素重合
        if (bitmap != null && clipping && anchorAlpha > 0f) {
            anchorDst.set(
                anchorRect.left,
                clipTop,
                anchorRect.right,
                clipTop + anchorRect.height(),
            )
            anchorPaint.alpha = (anchorAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, anchorDst, anchorPaint)
        }
    }

    override fun draw(canvas: Canvas) {
        if (!clipping) {
            super.draw(canvas)
            return
        }
        // 初始"一条线"（高度/宽度为 0）：整帧不画，避免空 outline 被忽略时闪现整页
        if (clipBottom - clipTop <= 0f || clipRight - clipLeft <= 0f) return
        val saveCount = canvas.save()
        canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom) // 直角兜底；圆角由 outline 裁出
        super.draw(canvas)
        canvas.restoreToCount(saveCount)
    }
}
