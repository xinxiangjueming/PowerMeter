package com.chen.powermeter.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 纵向裁剪容器 —— "从列表行向上下展开到下一界面"转场的核心（2026-09-25）。
 *
 * 原理：动画期间把自身绘制裁剪到 [setClipBounds] 给出的横向窗口 (0, clipTop, width, clipBottom)
 * 内——上一半内容从锚点行中心向上揭开、下一半向下揭开，视觉上就是下一界面从一条
 * 高度为 0 的线向上下两个方向同时"撑开"。
 *
 * **为什么用 clipRect 而不是 scaleY**：内容始终按全尺寸布局，只是显示范围变化，
 * 文字/图片不会被拉伸变形；scaleY 会把整页内容压扁再展开。
 *
 * **为什么在 [draw] 里裁剪而不是 dispatchDraw**：View.draw() 的顺序是
 * background → onDraw → dispatchDraw（children）→ 前景，在这里下 clip 可以让
 * "详情页底色（本容器的 background）"和"详情内容（children）"一起被裁剪；
 * 若只裁 dispatchDraw，收起动画中容器底色会先于内容露出，穿帮。
 *
 * 独立成容器类（而非把逻辑写进调度器）是为了也可以在 XML 里直接声明使用：
 * `<com.example.sportlink.ui.ClipRevealLayout …/>`，动画数值仍由代码驱动。
 */
class ClipRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var clipTop = 0f
    private var clipBottom = 0f

    /** 是否处于裁剪状态。动画结束后 [clearClip] 关闭，省掉每帧 save/clip/restore */
    var clipping = false
        private set

    /**
     * 设置当前显示窗口（本容器自身坐标系：x ∈ [0, width]，y ∈ [0, height]）。
     * top == bottom 时裁剪高度为 0（初始的"一条线"）；top=0、bottom=height 即全屏。
     */
    fun setClipBounds(top: Float, bottom: Float) {
        clipTop = top
        clipBottom = bottom
        clipping = true
        invalidate()
    }

    /** 解除裁剪，恢复完整绘制（展开完成后的稳态） */
    fun clearClip() {
        clipping = false
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (!clipping) {
            super.draw(canvas)
            return
        }
        val saveCount = canvas.save()
        canvas.clipRect(0f, clipTop, width.toFloat(), clipBottom)
        super.draw(canvas)
        canvas.restoreToCount(saveCount)
    }
}
