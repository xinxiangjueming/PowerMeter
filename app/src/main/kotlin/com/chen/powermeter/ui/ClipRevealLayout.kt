package com.chen.powermeter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.chen.powermeter.util.remapBitmapTone
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
     * 内容遮罩（收拢专用，2026-09-28）：详情页整页底色画在 page（ComposeView）**内部**、
     * 随 page.alpha 一起淡出，交叉期与同时浮起的卡片白底形成"灰 vs 白"的对比 —— 用户
     * 看到的就是"卡片左右两侧的阴影"（横屏两列窄卡时占比更大，故"横屏更明显"）。
     * 由 [setContentVeil] 按 (1 - pageAlpha) 驱动，在页面内容之上、锚点卡片之下铺一层
     * [veilColor]（容器底色 = 卡片表面色）：内容改为"被卡片色收拢"，不再露出页面灰。
     * 绘制天然落在 [draw] 的 clipRect 之内，窗口外不受影响。
     */
    private var veilColor = 0
    private var veilAlpha = 0f
    private val veilPaint = Paint()

    /**
     * 本体纯色模式（2026-09-28）：非 0 时，锚点本体**不再采样位图**，直接用该色填充
     * 圆角矩形。位图里任何"非表面色"残留（容器色元素、抗锯齿边、阴影过渡带、被
     * 误烤进去的浅色填充）都会随整段转场全程可见 —— 用户连续多轮反馈的"卡片右侧
     * 灰块"反复复现即属此类。纯色填充从根上杜绝，内容一律由文字层承载。
     * 取值 = 容器底色（installWindowTransform 里与 setBackgroundColor/setVeilColor 同源）。
     */
    private var anchorSolidColor = 0

    /**
     * 锚点卡片截图。有 [anchorTextBitmap]（列表行类锚点）时**钉在原始矩形**不动——
     * "卡片本体留在原地，只有内部文字滑移"（2026-09-27 用户定稿，SportLink 8452508）；
     * 无文字截图时**整行跟随窗口上边移动**（紧凑按钮类锚点的既有行为）——宽高恒为
     * 原始像素（不拉伸不缩放）。是否可见由裁剪窗口与 [setAnchorAlpha] 共同决定。
     */
    private var anchorBitmap: Bitmap? = null
    private val anchorRect = RectF()
    private val anchorDst = RectF()
    private var anchorAlpha = 0f
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 本体截图的圆角半径 px（[setAnchorCornerRadiusPx]）：[drawAnchorBody] 据此走圆角绘制 */
    private var anchorCornerRadiusPx = 0f

    /** 本体截图的 shader（[setAnchorBitmap] 时创建）：圆角绘制的像素源 */
    private var anchorShader: BitmapShader? = null

    /** shader 采样原点配平矩阵（[drawAnchorBody] 每帧按 dst 左上角平移，复用免分配） */
    private val anchorShaderMatrix = Matrix()

    /** 圆角绘制专用画笔（与 [anchorPaint] 分开：后者还要画文字层，不挂 shader） */
    private val anchorBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 锚点内部文字截图（可选）：跟随窗口上边滑移渐隐（滑移时保持它在卡片内的
     * 横向/纵向偏移，等于"文字从卡片上浮起"）。null = 无分层，整卡一起滑移。
     */
    private var anchorTextBitmap: Bitmap? = null
    private val anchorTextRect = RectF()
    private val anchorTextDst = RectF()

    /** 主题重映射的原始副本（仅深浅切换后退场使用；二次切换从原始重算，不叠加映射失真） */
    private var anchorOriginalBitmap: Bitmap? = null
    private var anchorTextOriginalBitmap: Bitmap? = null

    /** 是否带有锚点截图（调用方据此决定是否做内容 ↔ 截图交叉淡变） */
    val hasAnchorBitmap: Boolean
        get() = anchorBitmap != null

    /** 设置锚点卡片截图及其屏幕固定矩形（容器坐标系，原尺寸原位置） */
    fun setAnchorBitmap(bitmap: Bitmap?, left: Float, top: Float, right: Float, bottom: Float) {
        anchorBitmap = bitmap
        anchorRect.set(left, top, right, bottom)
        anchorShader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    /** 本体截图的圆角半径（锚点控件实际显示圆角，见 [drawAnchorBody]）；0 = 无圆角信息 */
    fun setAnchorCornerRadiusPx(px: Float) {
        anchorCornerRadiusPx = px
    }

    /** 设置锚点内部文字截图及其固定矩形（容器坐标系）；null = 清除分层，整卡一起滑移 */
    fun setAnchorTextBitmap(bitmap: Bitmap?, left: Float, top: Float, right: Float, bottom: Float) {
        anchorTextBitmap = bitmap
        anchorTextRect.set(left, top, right, bottom)
        invalidate()
    }

    /**
     * 主题过期修复（2026-09-28：详情页期间系统深浅变化 → 返回收拢动画里的锚点截图
     * 还是旧主题像素，用户报"返回动画用旧色"）：把卡片本体 / 文字层截图分段亮度
     * 重映射到目标深浅体系（口径见 [com.chen.powermeter.util.remapBitmapTone]），
     * 本体 shader 同步重建。幂等：多次调用恒从原始位图重算，不叠加映射失真。
     */
    fun remapAnchorTheme(darkTarget: Boolean) {
        anchorBitmap?.let { src ->
            val base = anchorOriginalBitmap ?: src.also { anchorOriginalBitmap = it }
            val mapped = remapBitmapTone(base, darkTarget)
            anchorBitmap = mapped
            anchorShader = BitmapShader(mapped, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (src !== base && src !== mapped) src.recycle()
        }
        anchorTextBitmap?.let { src ->
            val base = anchorTextOriginalBitmap ?: src.also { anchorTextOriginalBitmap = it }
            val mapped = remapBitmapTone(base, darkTarget)
            anchorTextBitmap = mapped
            if (src !== base && src !== mapped) src.recycle()
        }
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
        // 稳态绝不能留遮罩：展开结束/降级路径都经过这里，残留会把整页刷成卡片色
        veilAlpha = 0f
        invalidateOutline()
        invalidate()
    }

    /** 遮罩色（= installWindowTransform 里的容器底色，两者必须同源，过渡才无缝）。 */
    fun setVeilColor(color: Int) {
        veilColor = color
    }

    /** 本体纯色模式的填充色（0 = 关闭，回落到位图绘制）。同源取容器底色。 */
    fun setAnchorSolidColor(color: Int) {
        anchorSolidColor = color
        invalidate()
    }

    /**
     * 内容遮罩强度 [0,1]：0 = 不盖（展开/稳态），1 = 完全用遮罩色盖住页面内容。
     * 收拢时传 `1f - page.alpha`，与页面淡出严格互补 —— 页面内容"被卡片色收拢"，
     * 而不是"淡出到页面底色"，交叉期不再与浮起的白卡形成灰白对比。
     */
    fun setContentVeil(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        if (veilAlpha == a) return
        veilAlpha = a
        invalidate()
    }

    /**
     * 锚点截图层画在 children **之后**（恒盖在页面内容上面）。历史实现放在 [onDraw] =
     * View.draw 固定顺序（背景 → onDraw → dispatchDraw）里的"子 View 之下"——收拢交叉窗
     * （页面内容淡出 raw 0→55% ↔ 卡片截图淡入 raw 45%→100%，见 AppTransitions.CollapseHost）
     * 中详情页的同位卡片边缘/阴影/统计文字以残影叠印在浮现的列表卡片上 = 用户截图
     * "卡片内部左右两侧黑色阴影"（2026-09-27 真机实锤）。展开侧不受影响：截图
     * f=0→0.22 已淡出、内容 f=0.48 才淡入，两窗无交叉，锚点永远在空窗期绘制。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // 内容遮罩：位置必须在子 View（详情页内容）**之后**、锚点卡片**之前** ——
        // 盖住页面底色，但不遮卡片本体与文字层
        if (veilAlpha > 0.001f) {
            veilPaint.color = veilColor
            veilPaint.alpha = (veilAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veilPaint)
        }
        drawAnchorLayers(canvas)
    }

    /** 锚点截图层（卡片本体 + 可选文字层），由 [dispatchDraw] 保证绘制在页面内容之上 */
    private fun drawAnchorLayers(canvas: Canvas) {
        val bitmap = anchorBitmap
        val textBitmap = anchorTextBitmap
        if (bitmap == null || !clipping || anchorAlpha <= 0f) return
        anchorPaint.alpha = (anchorAlpha * 255f).toInt().coerceIn(0, 255)
        if (textBitmap != null) {
            // 分层模式（列表行类锚点）：卡片本体钉在原始矩形不动（随进度淡变），
            // 内部文字随窗口上边滑移渐隐——滑移时保持它在卡片内的偏移
            // （δ = 文字顶边在卡片内的原始偏移），等于"文字从卡片上浮起/落回"
            anchorDst.set(anchorRect)
            drawAnchorBody(canvas, bitmap, anchorDst)
            val dy = anchorTextRect.top - anchorRect.top
            anchorTextDst.set(
                anchorTextRect.left,
                clipTop + dy,
                anchorTextRect.right,
                clipTop + dy + anchorTextRect.height(),
            )
            canvas.drawBitmap(textBitmap, null, anchorTextDst, anchorPaint)
        } else {
            // 整行模式（紧凑按钮类锚点）：整卡跟随窗口上边移动（宽高恒为原始像素，
            // 纯位移无形变）：展开时随上边上行渐隐、收拢时随上边下行归位；
            // f=0 时与真卡片逐像素重合
            anchorDst.set(
                anchorRect.left,
                clipTop,
                anchorRect.right,
                clipTop + anchorRect.height(),
            )
            drawAnchorBody(canvas, bitmap, anchorDst)
        }
    }

    /**
     * 本体截图按**锚点圆角的抗锯齿圆角矩形**绘制（BitmapShader + drawRoundRect）：
     * 裁剪矩形四角与卡片圆角之间的月牙区残留着源列表的"页面底色+卡片阴影"像素——
     * 容器底色还是页面色时它们随底色隐身，底色改成卡片表面色（2026-09-27 批次三十五）
     * 后就压在浅色底上显形（用户截图："圆角卡片像从方框里裁出来、四角外发黑"）。
     * 半径超绘制区短边一半时钳半（同 outline 口径，紧凑胶囊类锚点自动回落为胶囊形）；
     * 未设置半径（0）= 无圆角信息，退化为整矩形绘制（旧行为）。
     */
    /**
     * 本体截图四边的侵蚀量（px）：裁剪位图的最外圈是源窗口里卡片边缘的**抗锯齿过渡带**
     * （卡片表面与页面底色的混合像素），整卡淡入到不透明后（收拢末段）这条灰边贴在
     * 同色容器底上显形 = 用户二次反馈的"卡片内部左右黑色阴影"（2026-09-27）。侵蚀后
     * 绘制区只剩纯卡片表面像素，让出的边缘环由容器底色（采样自同一张卡、颜色一致）
     * 补齐，视觉无缝；3px 在常见密度下不足 1dp，无感知。
     */
    private val EDGE_ERODE_PX = 3f

    private fun drawAnchorBody(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val r = anchorCornerRadiusPx.coerceAtLeast(0f)
            .coerceAtMost(minOf(dst.width(), dst.height()) / 2f)
        // 纯色模式：完全不采样位图，直接填锚点表面色（见 anchorSolidColor 注释）。
        // 不参与 EDGE_ERODE —— 纯色不存在"位图边缘过渡像素"问题
        if (anchorSolidColor != 0) {
            anchorBodyPaint.shader = null
            anchorBodyPaint.color = anchorSolidColor
            anchorBodyPaint.alpha = anchorPaint.alpha
            canvas.drawRoundRect(dst, r, r, anchorBodyPaint)
            return
        }
        val shader = anchorShader
        if (shader == null || r <= 0f) {
            canvas.drawBitmap(bitmap, null, dst, anchorPaint)
            return
        }
        // 边缘侵蚀（见 EDGE_ERODE_PX）：绘制矩形与圆角半径同步内缩，保持与卡片真实
        // 圆角形状同心；让出的边缘环由容器底色补齐
        val l = dst.left + EDGE_ERODE_PX
        val t = dst.top + EDGE_ERODE_PX
        val rgt = dst.right - EDGE_ERODE_PX
        val btm = dst.bottom - EDGE_ERODE_PX
        // ⚠️ BitmapShader 以**画布坐标**采样（不随 dst 起点平移）：dst 不从 (0,0) 起时
        // 会整体错位读图、CLAMP 把位图外的边行/边列糊进绘制区，卡片两端烘焙的圆弧
        // 被错位采进来 = 用户截图"圆框内左右两侧月牙残留"——每次绘制把采样原点平移到
        // 绘制矩形左上角，与 drawBitmap 逐像素等价（圆角裁剪仍然生效）
        anchorShaderMatrix.setTranslate(-l, -t)
        shader.setLocalMatrix(anchorShaderMatrix)
        anchorBodyPaint.shader = shader
        anchorBodyPaint.alpha = anchorPaint.alpha
        canvas.drawRoundRect(l, t, rgt, btm, (r - EDGE_ERODE_PX).coerceAtLeast(0f), (r - EDGE_ERODE_PX).coerceAtLeast(0f), anchorBodyPaint)
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
