package com.chen.powermeter.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.chen.powermeter.ui.ClipReveal
import com.chen.powermeter.ui.ClipRevealLayout
import java.io.File
import java.io.FileOutputStream
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * 1. 源条目登记（`Modifier.containerSource` 采集窗口矩形，onClick 里 [register]；
 *    可选 `Modifier.containerTextSource` 采集内部文字矩形 → 分层动画）；
 * 2. [capture] 消费锚点，用 `View.draw` 把窗口画到 Bitmap 后裁出源条目（带文字区时
 *    额外裁文字截图 + 本体抹字）→ [Capture]；
 * 3. 跨 Activity 展开转场：[launchWithTransform] 登记 Handoff + 普通启动（单次压制窗口
 *    滑动），[installWindowTransform] 在目标窗口内把页面根包进 ClipRevealLayout、首帧
 *    从源条目矩形四向撑开（含四角圆角插值 + 截图交叉淡变），收拢收回源矩形后 finish。
 *
 * **与 SportLink 原版的分叉**（本项目特有，勿"顺手删掉"）：
 * - 截图走 `View.draw` 同步绘制（PowerMeter 的 Compose 版本没有 `rememberGraphicsLayer`，
 *   官方 API 不可用），非 suspend；锚点矩形即窗口 decorView 坐标系，与 [Source.bounds] 同源；
 * - [Capture] 携带 bgColor（源页 Compose 主题 background）与源窗口宽高：前者替换
 *   resolveColorBackground（本项目 View 层主题的 colorBackground 深色模式下是白的，
 *   必须从 Compose 主题取色，历史结论见 ClipReveal.openRevealAt 的参数注释），
 *   后者供目标页做竖→横锚点换算（[anchorTransform]）；
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
    class Source(internal val bounds: Rect = Rect()) {
        /**
         * 源条目**内部文字**的窗口矩形（可选，`Modifier.containerTextSource` 采集）：
         * 列表行类锚点用它做"文字随窗口边滑移渐隐、卡片本体钉在原位"的分层动画；
         * 空 = 无分层（整卡截图一起滑移，紧凑按钮类锚点的既有行为）
         */
        internal val textBounds: Rect = Rect()
    }

    /**
     * 一次锚点截取结果：
     * - [bitmap] = 源条目截图（展开/收拢的"本体"交叉淡变，矩形做裁剪窗口）；
     * - [rect] = 源条目窗口矩形；
     * - [bgColor] = 源页 Compose 主题背景色（裁剪容器底色，与页面连续）；
     * - [sourceWidth]/[sourceHeight] = 源窗口宽高（目标页判方向：竖→横锚点换算用）；
     * - [textBitmap]/[textRect] = 内部文字截图及其窗口矩形（可选）：分层绘制 =
     *   卡片本体抹字后钉在原位、仅文字随窗口上边滑移渐隐（2026-09-27 SportLink
     *   8452508 新版口径）；已抠掉卡片表面底色（滑动层不带"方框"，见
     *   [stripAnchorTextBackground]）；null = 无分层，整卡一起滑移。
     * - [cardSurface] = 锚点卡片表面色（文字区旁留白带采样，0 = 无文字层/采样失败）：
     *   列表卡类锚点的收拢容器底色用它（见 [installWindowTransform]）。
     */
    class Capture(
        val bitmap: Bitmap,
        val rect: Rect,
        val bgColor: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val textBitmap: Bitmap? = null,
        val textRect: Rect? = null,
        val cardSurface: Int = 0,
        /**
         * 源页**整窗**截图（可选，[capture] 的 keepPageSnapshot=true 时携带）：collapse-back
         * 装配时垫在裁剪层下面当"冻结列表"背景。详情窗口不透明，收拢期间裁剪窗口外只有
         * 窗口底色、列表整页被盖住，结束后其它卡片凭空瞬现（2026-09-27 用户反馈"没有过渡
         * 动画"）；垫上冻结列表后收拢全程可见，末帧与真实列表逐像素一致（列表 stopped
         * 期间不重组、布局冻结），finish 后无缝替换。持有成本 ≈ 一张整窗 ARGB_8888
         * （1440×3200 ≈ 18MB），由 [PageSnapshotView] 在 onDetachedFromWindow 统一回收；
         * 只在同方向 collapse-back 流程（FrameDetailActivity）携带，趋势页（跨方向）不带。
         */
        val pageBitmap: Bitmap? = null,
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
     *
     * 锚点带文字区（Source.textBounds 非空）时额外裁出**文字截图**，并把主截图里的
     * 文字区抹成卡片表面色（钉住的本体不能带字，否则文字滑移时双重出现）。
     *
     * [keepPageSnapshot] = 同时保留整窗截图（[Capture.pageBitmap]）：collapse-back 流程
     * （FrameDetailActivity）用它做收拢期间的冻结列表背景；不传 = 只裁锚点，整窗图
     * 用完即回收（趋势页等既有调用方零成本变化）。
     */
    fun capture(windowView: View, bgColor: Int, keepPageSnapshot: Boolean = false): Capture? {
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
        // 内部文字截图（可选）：矩形空/裁剪失败一律 null → 调用方退化为整卡一起滑移
        var textBitmap: Bitmap? = null
        var textRect: Rect? = null
        var body: Bitmap = cropped
        var surface: Int? = null
        val tb = source.textBounds
        if (!tb.isEmpty) {
            val tl = tb.left.coerceIn(0, windowView.width - 1)
            val tt = tb.top.coerceIn(0, windowView.height - 1)
            val tr = tb.right.coerceIn(tl + 1, windowView.width)
            val tbt = tb.bottom.coerceIn(tt + 1, windowView.height)
            textBitmap = try {
                Bitmap.createBitmap(full, tl, tt, tr - tl, tbt - tt)
            } catch (t: Throwable) {
                Log.w(TAG, "锚点文字裁剪失败 → 整卡一起滑移", t)
                null
            }
            if (textBitmap != null) {
                textRect = Rect(tl, tt, tr, tbt)
                // 表面色只采样一次共用：抹字填回色 / 文字层抠底 / 收拢容器底色必须同一色，
                // 三层底色才无缝
                surface = sampleAnchorSurfaceColor(cropped, Rect(left, top, right, bottom), textRect)
                // 主截图抹掉文字区（填回卡片表面色）："卡里一份、飞出去一份"双重出现防御。
                // createBitmap(source,…) 裁出的是**不可变**位图，抹字必须走可变副本
                // （copy 出新图返回，原图回收）
                val erased = eraseAnchorTextRegion(cropped, Rect(left, top, right, bottom), textRect, surface)
                if (erased !== cropped) {
                    body = erased
                    cropped.recycle()
                }
                // 文字层抠掉卡片表面底色：滑动层只剩图标+文字本体，不带"方框"
                textBitmap = stripAnchorTextBackground(textBitmap, surface, bgColor)
                Log.i(
                    TAG,
                    "capture body=${b.toShortString()} text=${tb.toShortString()}" +
                        " surface=" +
                        (surface?.let { String.format("#%06X", it and 0xFFFFFF) } ?: "null") +
                        " page=" + String.format("#%06X", bgColor and 0xFFFFFF) +
                        " textLayer=${textBitmap.width}x${textBitmap.height}",
                )
            }
        }
        dumpCaptureIfDebuggable(windowView.context, full, body, textBitmap)
        Log.i(
            TAG,
            "capture rect=${b.toShortString()} bitmap=${body.width}x${body.height}" +
                " text=${textBitmap?.width}x${textBitmap?.height}" +
                if (keepPageSnapshot) " pageSnapshot=${full.width}x${full.height}" else "",
        )
        return if (keepPageSnapshot) {
            // 整窗截图随 Capture 带走（冻结列表背景，见 Capture.pageBitmap）。body/text
            // 都是 createBitmap 裁剪出的**独立像素拷贝**，与 full 的缓冲解耦，此处不
            // recycle full 不影响既有裁剪结果
            Capture(
                body, Rect(b), bgColor,
                windowView.width, windowView.height,
                textBitmap, textRect,
                surface ?: 0,
                full,
            )
        } else {
            full.recycle()
            Capture(
                body, Rect(b), bgColor,
                windowView.width, windowView.height,
                textBitmap, textRect,
                surface ?: 0,
            )
        }
    }

    /**
     * 卡片表面色采样（抹字填回色与文字层抠底**共用同一份**，两层底色才无缝衔接）：
     * 取文字区上/下侧留白带的中点（x 居中，避开圆角/边框/内容）；两侧留白都过薄
     * （<8px）或读像素失败返回 null（抹字/抠底双双降级）。
     * （[cardRect]/[textRect] 均为窗口坐标，内部换算为位图相对坐标。）
     */
    private fun sampleAnchorSurfaceColor(card: Bitmap, cardRect: Rect, textRect: Rect): Int? {
        // 首选：整张位图的**不透明主色**（卡片表面是纯色、占位图绝大多数面积；圆角外的
        // 页面底色、阴影、内容像素都不影响众数）。比"文字区上/下方留白单点采样"稳得多：
        // 单点一旦落在圆角外/异色元素上，抹字回填会把整片文字区刷成错误底色，跟着整段
        // 转场走（2026-09-28 用户截图实锤：卡片右端出现与页面背景同色的"阴影"）。
        dominantOpaqueColor(card)?.let { return it }
        // 回退：文字区上/下方留白带单点采样（旧口径）
        return try {
            val rel = Rect(
                textRect.left - cardRect.left, textRect.top - cardRect.top,
                textRect.right - cardRect.left, textRect.bottom - cardRect.top,
            )
            val topGap = rel.top
            val bottomGap = card.height - rel.bottom
            when {
                topGap >= 8 -> card.getPixel(card.width / 2, rel.top / 2)
                bottomGap >= 8 -> card.getPixel(card.width / 2, (rel.bottom + card.height) / 2)
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "锚点表面色采样失败", t)
            null
        }
    }

    /**
     * 位图**不透明主色**：每通道取高 5 bit 归一桶，取像素最多的桶，返回该桶里
     * 首次遇到的原色（±8 内的真实像素色，不做量化还原，避免偏色）。
     * 8px 步进取样，整窗量级位图也就万级次 getPixel，开销可忽略。
     */
    private fun dominantOpaqueColor(card: Bitmap): Int? {
        return try {
            val counts = HashMap<Int, Int>()
            val sample = HashMap<Int, Int>()
            val step = 8
            var y = 0
            while (y < card.height) {
                var x = 0
                while (x < card.width) {
                    val c = card.getPixel(x, y)
                    if (((c ushr 24) and 0xFF) >= 200) {
                        val key = (((c ushr 19) and 0x1F) shl 10) or
                            (((c ushr 11) and 0x1F) shl 5) or
                            ((c ushr 3) and 0x1F)
                        counts[key] = (counts[key] ?: 0) + 1
                        if (!sample.containsKey(key)) sample[key] = c
                    }
                    x += step
                }
                y += step
            }
            val bestKey = counts.maxByOrNull { it.value }?.key ?: return null
            sample[bestKey]
        } catch (t: Throwable) {
            Log.w(TAG, "锚点表面色众数统计失败", t)
            null
        }
    }

    /**
     * 把锚点主截图里的文字区域抹成卡片表面色，返回**可变**新位图：钉住的"卡片本体"
     * 不能带文字，否则文字滑移层会与本体里的文字双重出现（2026-09-27 用户实锤）。
     * 采样失败（[surface] == null）原样返回（退化为文字双重出现）。
     * （[cardRect]/[textRect] 均为窗口坐标，内部换算为位图相对坐标。）
     */
    private fun eraseAnchorTextRegion(card: Bitmap, cardRect: Rect, textRect: Rect, surface: Int?): Bitmap {
        if (surface == null) return card
        return try {
            val out = card.copy(Bitmap.Config.ARGB_8888, true) ?: return card
            // 抹除范围 = **整张卡片**（留 2px 边防越界），而不是只抹内容区：
            // 内容区之外的卡片端部留白里若混入任何非表面色像素（容器色元素、抗锯齿
            // 残留、阴影过渡带等），只抹内容区就会把它们留成一条"灰带"跟着整段转场
            // 走 —— 2026-09-28 用户反馈：单点采样 / 双基准抠底 / 内容遮罩多轮修复后
            // 灰块依旧（"还是一模一样"），说明残留就在本体位图里。
            // body 只保留"纯色卡片表面 + 圆角"，一切内容一律由文字层承载。
            val erase = android.graphics.RectF(
                2f,
                2f,
                (card.width - 2).toFloat(),
                (card.height - 2).toFloat(),
            )
            Canvas(out).drawRect(erase, android.graphics.Paint().apply { color = surface })
            out
        } catch (t: Throwable) {
            Log.w(TAG, "抹除锚点文字区失败 → 文字双重出现降级", t)
            card
        }
    }

    // 文字层抠底的色距档（曼哈顿距离，0~765）：≤ CLEAR 全透明、≥ OPAQUE 原样、中间线性。
    // 档宽取窄（40）：文字/图标本体的抗锯齿边多数直接落到 OPAQUE 侧（略变实），只有
    // 最外 10% 左右的过渡像素半透明——比宽档更少在深色内容上留"浅色残影"。
    // 阈值放宽（2026-09-28）：卡片内可能存在"接近表面色但仍有可见色差"的浅色填充
    // （如 surfaceContainerHighest 的圆角方块、行内统计格的浅底）；阈值过窄会把它们
    // 原样保留，叠在卡片上就是一块浅灰。放宽后按 min(dSurface, dPage) 判定，这类
    // 浅色一并抠透。代价：文字/图标的浅色抗锯齿边略变实（露出的正是下方的卡片色，
    // 视觉影响很小），远小于留一块灰的代价。
    private const val TEXT_KEY_CLEAR_DIST = 48
    private const val TEXT_KEY_OPAQUE_DIST = 96

    /**
     * 文字截图**抠掉卡片表面底色**（2026-09-27 用户实锤"图标和文字被包在一个方框里
     * 下滑，非常奇怪"）：文字层裁自整窗截图，天然带着卡片表面底色——滑动时是一个
     * 白色矩形盖在页面背景上，内容看起来"装在方框里"。SportLink 原版文字层走
     * GraphicsLayer 截图、天生透明底，本项目 View.draw 裁剪分叉必须手动抠底：按与
     * 表面色的色距生成 alpha（贴近表面色 → 透明，超过 [TEXT_KEY_OPAQUE_DIST] → 原样，
     * 中间线性过渡 = 压在表面色上的抗锯齿边平滑半透明）。
     * ⚠️ 已知代价：浅色图标内部的近表面色像素也会被抠透（SportLink 无此问题）；
     * 换来滑动层无方框，利大于弊。抠底失败/无采样色原样返回（保留方框降级）。
     */
    private fun stripAnchorTextBackground(text: Bitmap, surface: Int?, bgColor: Int): Bitmap {
        if (surface == null) return text
        return try {
            val out = text.copy(Bitmap.Config.ARGB_8888, true) ?: return text
            val w = out.width
            val h = out.height
            val px = IntArray(w * h)
            out.getPixels(px, 0, w, 0, 0, w, h)
            val sr = Color.red(surface)
            val sg = Color.green(surface)
            val sb = Color.blue(surface)
            val br = Color.red(bgColor)
            val bg = Color.green(bgColor)
            val bb = Color.blue(bgColor)
            for (i in px.indices) {
                val c = px[i]
                if (((c ushr 24) and 0xFF) == 0) continue
                val cr = Color.red(c)
                val cg = Color.green(c)
                val cb = Color.blue(c)
                // ⚠️ 必须同时抠"卡片表面色"与"页面背景色"两个基准：
                // 文字层裁自整窗截图，除卡片底色外还可能带上卡片外的页面底色
                // （E1E1E1 与卡片白 FFFFFF 的曼哈顿距离 = 90 ≥ TEXT_KEY_OPAQUE_DIST
                //  → 旧版只按 surface 判距时它落在"原样保留"档，整片页面底色被不透明
                //  带进转场，叠在卡片右端 = 用户截图上那块"与页面背景同色的阴影"）。
                // 取两个基准里更近的那个距离，任一底色都能被抠透。
                val dSurface = abs(cr - sr) + abs(cg - sg) + abs(cb - sb)
                val dPage = abs(cr - br) + abs(cg - bg) + abs(cb - bb)
                val d = if (dSurface < dPage) dSurface else dPage
                val keep = when {
                    d <= TEXT_KEY_CLEAR_DIST -> 0f
                    d >= TEXT_KEY_OPAQUE_DIST -> 1f
                    else -> (d - TEXT_KEY_CLEAR_DIST).toFloat() / (TEXT_KEY_OPAQUE_DIST - TEXT_KEY_CLEAR_DIST)
                }
                if (keep < 1f) {
                    val a = (c ushr 24) and 0xFF
                    px[i] = (c and 0x00FFFFFF) or (((a * keep).toInt().coerceIn(0, 255)) shl 24)
                }
            }
            out.setPixels(px, 0, w, 0, 0, w, h)
            text.recycle()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "文字层抠底失败 → 保留底色方框降级", t)
            text
        }
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

    /**
     * [debug] 转场截图取证（仅 DEBUGGABLE 包生效）：把截图时刻的**整窗原图 / 抹字后的
     * 本体 / 抠底后的文字层**三张 PNG 落盘到 app 外部 files 目录（每次覆盖，只留最近一场），
     * adb 可直接 pull —— 用于裁决"收拢残留"类问题：灰斑究竟是截图里就带着的（列表侧
     * 画的），还是转场绘制阶段加出来的。
     */
    private fun dumpCaptureIfDebuggable(context: Context, full: Bitmap, body: Bitmap, text: Bitmap?) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "transition_capture").apply {
                deleteRecursively()
                mkdirs()
            }
            fun Bitmap.write(name: String) {
                FileOutputStream(File(dir, name)).use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            full.write("full.png")
            body.write("body.png")
            text?.write("text.png")
            val profile = (0 until 10).joinToString("") { i ->
                val x = body.width - 1 - i * body.width / 12
                " %#06X".format(body.getPixel(x, body.height / 2) and 0xFFFFFF)
            }
            Log.i(TAG, "转场截图取证已落盘 ${dir.absolutePath} body(w=${body.width},h=${body.height}) 右缘色调:$profile")
        }.onFailure { Log.w(TAG, "转场截图落盘失败", it) }
    }

    // ============ 跨 Activity 上下展开（ClipReveal，目标窗口内手动驱动） ============
    //
    // 现行机制（SportLink 同款）：源端消费锚点 → Handoff 单例交接（普通 startActivity，
    // 单次压制主题滑动）→ 目标页 onPostCreate 把页面根包进 ClipRevealLayout，首帧从源
    // 条目矩形四向撑开（含四角圆角插值，截图与内容交叉淡变）；返回整页裁剪收回源条目
    // 矩形后 finish（收拢末帧压制窗口关闭转场，与源页无缝衔接）。

    /**
     * Handoff 保鲜期：超时未消费即丢弃，防陈旧截图污染后续启动。
     * ⚠️ 10s（2026-09-28 从 2s 放宽）：详情页首帧组合重（整页图表卡），采样服务在跑时
     * onPostCreate 偶尔被拖过 2s → 收拢宿主没装配 = 返回无收拢动画、闪一下直接结束
     * （用户实测"偶尔不出现"）。宿主在 onPostCreate 立即消费，放宽只影响极端慢启动。
     */
    private const val HANDOFF_TTL_MS = 10_000L

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
        /** 截图时刻源窗口尺寸（Capture 原样带上）：收拢前校验当前窗口是否同尺寸 */
        val sourceWindowWidth: Int = 0,
        val sourceWindowHeight: Int = 0,
        /**
         * 是否校验收拢时窗口尺寸：同方向装配（anchorTransform == null）= 源/目标窗口本就
         * 同尺寸，收拢时尺寸变了 = 用户在查看页转屏/分屏，源矩形在当前窗口坐标系失效
         * → 降级淡出退出；跨方向装配（趋势全屏页，锁横屏 + 锚点已换算）不校验
         * （源尺寸 ≠ 目标尺寸是常态，校验会永远误触发）。
         */
        val verifySourceWindow: Boolean = false,
    ) {
        var closing = false
        var finishRequested = false

        /**
         * 冻结列表背景层（collapse-back 专用，[PageSnapshotView]，见 installWindowTransform）：
         * 垫在裁剪层下面，收拢期间裁剪窗口外露出"冻结的源列表"，其它卡片不再凭空瞬现；
         * 视图移除窗口时在 onDetachedFromWindow 自回收整窗位图，这里只负责摘除视图。
         */
        var pageSnapshot: View? = null

        /**
         * 容器底色/遮罩色是否取自卡片表面色（装配时确定：带文字层且采样成功 = 卡片色，
         * 否则 = 源页背景色）。主题过期修复 [applyStaleThemeFix] 据此选择新主题对应色。
         */
        var containerColorIsCard: Boolean = false

        /** 冻结快照重映射任务代数：连切深浅时旧任务作废（防乱序替换） */
        private var snapshotRemapGen = 0

        /**
         * 冻结列表快照的主题重映射请求（2026-09-28）：详情页期间系统深浅变化 →
         * 后台把快照重映射到目标主题，收拢返回时无缝呈新色；连切时以代数作废旧任务。
         * 未就绪场景由 [applyStaleThemeFix] 兜底纯主题色。
         */
        fun requestSnapshotRemap(darkTarget: Boolean) {
            val snap = pageSnapshot as? PageSnapshotView ?: return
            if (snap.themeSynced(darkTarget)) return
            val gen = ++snapshotRemapGen
            val source = snap.sourceBitmap
            Thread {
                val mapped = runCatching { remapBitmapTone(source, darkTarget) }.getOrNull()
                    ?: return@Thread
                Handler(Looper.getMainLooper()).post {
                    if (gen != snapshotRemapGen) {
                        mapped.recycle()
                        return@post
                    }
                    snap.overrideBitmap(mapped, darkTarget)
                }
            }.start()
        }

        /**
         * 主题过期修复（收拢开始时调用，2026-09-28 用户报"返回动画用旧色"）：
         * 详情页期间系统深浅变化后，截图类素材（冻结快照 / 容器底色 / 遮罩色 / 锚点卡片
         * 截图）全部还是拍摄时（旧主题）的像素——逐项换成目标主题：
         * ① 冻结快照已重映射 → 直接可用；未就绪 → 纯主题背景色兜底（宁可无内容不闪旧色）；
         * ② 容器底色与遮罩色（两者同源）→ 新主题卡片表面色（或源页背景色）；
         * ③ 锚点卡片截图 / 文字层（小图）→ 同步分段亮度重映射。
         */
        fun applyStaleThemeFix(darkTarget: Boolean) {
            (pageSnapshot as? PageSnapshotView)?.let { snap ->
                if (!snap.themeSynced(darkTarget)) snap.showSolid(themeBackgroundColor(darkTarget))
            }
            clipView?.let { v ->
                val color = if (containerColorIsCard) themeCardColor(darkTarget)
                else themeBackgroundColor(darkTarget)
                v.setBackgroundColor(color)
                v.setVeilColor(color)
                v.remapAnchorTheme(darkTarget)
            }
        }

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
            // 转屏/分屏守卫（2026-09-27 用户实测：竖屏开详情 → 查看页转横屏 → 返回收拢
            // 位置异常，反向同理）：当前窗口与截图时尺寸不一致 = 源列表已随配置重排，而
            // 列表 stopped 期间 Compose 不重组、卡片实时位置无从取回 → 源矩形在当前窗口
            // 坐标系里没有意义，收拢终点必然错位 → 放弃裁剪收拢，整页快速淡出后结束
            // （窗口关闭转场已压 0，淡出完直接切回列表）。转回原方向再返回 = 尺寸恢复
            // 相等，列表冻结态与截图一致，照常收拢且终点仍正确。
            val decor = activity.window?.decorView
            if (verifySourceWindow &&
                (decor == null || decor.width != sourceWindowWidth || decor.height != sourceWindowHeight)
            ) {
                // 守卫命中必须有日志：尺寸为何变化（转屏/分屏/自由窗口）是诊断"收拢偶尔
                // 不出现"的关键证据，静默降级会让这类反馈无法定位
                Log.w(
                    TAG,
                    "requestClose size guard: decor=${decor?.width}x${decor?.height}" +
                        " source=${sourceWindowWidth}x${sourceWindowHeight} → fade exit",
                )
                finishWithFade()
                return
            }
            startClipCollapse()
        }

        /** 转屏/分屏后的降级退出：整页 ~200ms 淡出再 finish（关闭转场已压 0，末态直接切列表） */
        private fun finishWithFade() {
            // 冻结列表随裁剪层一起淡出：它是截图时刻（旧方向）的画面，转屏后继续挂着会
            // 穿帮；同步淡出避免它比裁剪层晚消失产生二次闪切
            pageSnapshot?.animate()?.alpha(0f)?.setDuration(200L)?.start()
            val v = clipView
            if (v == null) {
                finishNow()
                return
            }
            v.animate().alpha(0f).setDuration(200L).withEndAction { finishNow() }.start()
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
            // 锚点截图从 f=0 就可见（随窗口上边上行渐隐/分层滑移）——之前窗口路径漏
            // 驱动这个透明度，首帧裁剪窗口里只有透明内容 = 卡片区域空一拍
            clip.setAnchorAlpha(ClipReveal.anchorAlphaAt(0f))
            // 展开方向不挂内容遮罩：内容是从卡片位置"长大"出来的，遮住反而失真
            clip.setContentVeil(0f)

            expandSet = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = OPEN_MS
                interpolator = FastOutSlowInInterpolator
                addUpdateListener {
                    val f = it.animatedValue as Float
                    progress = f
                    geometry.applyTo(clip, f)
                    // 截图淡出必须逐帧驱动：上面只在启动前预置了 f=0 的初值 1，漏掉这行
                    // alpha 会整场钉在 1（卡片截图不消失、结束才瞬失）——同 ui/ClipReveal.Holder 口径
                    clip.setAnchorAlpha(ClipReveal.anchorAlphaAt(f))
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
                    val anchorA = maxOf(ClipReveal.anchorAlphaAt(f), ClipReveal.anchorAlphaIn(c))
                    clip.setAnchorAlpha(anchorA)
                    val contentAlpha = minOf(ClipReveal.contentAlphaAt(f), ClipReveal.contentAlphaOut(c))
                    page.alpha = contentAlpha
                    // 内容遮罩强度取 max(与内容淡出互补, 锚点卡片可见度)：
                    // 锚点卡片渐入期本身是半透明的，而它正下方就是详情页的灰底内容
                    // （ClipRevealLayout 的容器底色被 page 整片盖住、垫不到底）—— 半透明卡片
                    // 会让灰底透上来 = 用户看到的"卡片右端灰块，随图标文字一起出现"
                    // （2026-09-28 用户录屏 + 全屏截图实锤）。绑定到 anchorA 后，卡片越实、
                    // 垫底越实，末期与真实卡片完全同色。
                    clip.setContentVeil(maxOf(1f - contentAlpha, anchorA))
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
            // ⚠️ 绝不能在这里摘除 pageSnapshot：removeViewInLayout 立即把视图移出 children，
            // 而 finish() 之后窗口销毁前还要绘制 1~2 帧 —— 这几帧里裁剪窗口外的冻结列表
            // 瞬间消失（其它卡片闪没、只剩窗口底色）再切真实列表 = 用户实测"回收到卡片上
            // 后界面闪一下、那瞬间其余列表卡片看不到"（2026-09-27 二次反馈）。冻结层必须
            // 留到窗口销毁，由 PageSnapshotView.onDetachedFromWindow 统一回收位图（活动
            // 销毁/降级路径同样覆盖，不会泄漏整窗位图）。
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
        dropStaleHandoff()
        handoff = capture
        handoffAtMs = SystemClock.uptimeMillis()
        Log.i(TAG, "launchWithTransform ${intent.component?.className?.substringAfterLast('.')}")
        activity.startActivity(intent)
        // 目标窗口内手动播 ClipReveal：单次压制本次启动的窗口滑动（避免与窗口内动画叠画）
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    /**
     * 跨 Activity 启动的**拆分形态**（2026-09-27，FrameDetailActivity 专用）：登记 Handoff
     * 供目标页**退场**收拢（[installWindowTransform] 传 expandOnEnter=false），但**不压制
     * 窗口滑动**——进场走主题 activityOpen* 侧边滑入。背景：详情页大场次（整页图表卡）
     * 首帧组合重，ClipReveal 展开要求首帧整页布局绘制就绪、会明显卡顿；滑入期间组合
     * 在后台完成，退场时内容已就绪，收拢照样一镜到底。无锚点/过期降级普通启动。
     */
    fun launchWithCollapseBack(activity: Activity, intent: Intent, capture: Capture?) {
        if (capture == null) {
            activity.startActivity(intent)
            return
        }
        dropStaleHandoff()
        handoff = capture
        handoffAtMs = SystemClock.uptimeMillis()
        Log.i(TAG, "launchWithCollapseBack ${intent.component?.className?.substringAfterLast('.')}")
        activity.startActivity(intent)
    }

    /**
     * 登记新 Handoff 前丢弃残留的旧 Handoff：正常时序里旧 Handoff 要么已被目标页消费
     * （handoff == null）要么已过期，直接被覆盖的话其整窗截图（约 18MB）没人回收——
     * 显式 recycle 兜底（body/text 截图较小，交给 GC，避免误回收在途位图）。
     */
    private fun dropStaleHandoff() {
        handoff?.pageBitmap?.recycle()
        handoff = null
    }

    /**
     * 目标页装配（各跨 Activity 页在 setContent / onPostCreate 之后调用一次）。
     * 有新鲜 Handoff → 页面根包进 ClipRevealLayout 并登记收拢宿主；无（通知启动 /
     * Handoff 过期）→ no-op，页面普通显示。
     * [expandOnEnter] = false 时只装配收拢、不播进场展开（FrameDetailActivity 拆分形态：
     * 进场动画交给主题窗口滑动，见 [launchWithCollapseBack]）。
     *
     * [anchorTransform] = 锚点矩形换算钩子（可选）：源页与目标页**显示方向不同**时
     * （如本项目竖屏主页 → 强制横屏全屏页），源窗口坐标不能直接用，由目标页传入
     * "源矩形 → 目标窗口矩形"的换算（截图定位、展开几何、收拢终点三处统一应用）；
     * 同方向（横→横，SportLink 原生场景）传 null，锚点原样使用。
     */
    fun installWindowTransform(
        activity: Activity,
        anchorTransform: ((Capture) -> Rect)? = null,
        expandOnEnter: Boolean = true,
    ) {
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
            // 容器底色：列表卡类锚点（带文字层且有采样色）= **卡片表面色**（2026-09-27
            // 用户定稿"过渡背景改圆框内部填充色"）——收拢时窗口内随页面内容淡出从页面色
            // 自然过渡到卡片色，钉住的卡片本体在同色底上淡入 = 融进背景（不再有"白色圆框
            // 在灰底上突现"），只剩文字滑落、窗口收缩成圆框本体。紧凑按钮类锚点（趋势页
            // 胶囊，无文字层）维持源页背景色（整页观感，展开/收拢窗口都应是页面色）。
            // 颜色取自真实像素采样而非主题常量，深浅色/取色变化天然跟随
            val containerColor =
                if (capture.textBitmap != null && capture.cardSurface != 0) {
                    capture.cardSurface
                } else {
                    capture.bgColor
                }
            setBackgroundColor(containerColor)
            // 内容遮罩必须与容器底色同源：收拢时按 (1 - pageAlpha / anchorAlpha) 盖住详情页底色
            setVeilColor(containerColor)
            // 本体改纯色填充（2026-09-28）：不再采样锚点位图 —— 位图里任何"非表面色"
            // 残留（容器色元素、抗锯齿边、阴影过渡带）都会随整段转场全程可见，
            // 是"卡片右侧灰块"反复复现的根因所在。内容一律由文字层承载。
            setAnchorSolidColor(containerColor)
            // 卡片截图：按原始尺寸钉在源卡片屏幕位置（起始层），只随进度淡出、
            // 永不形变/位移（参考 HTML）
            setAnchorBitmap(
                capture.bitmap,
                (anchorRect.left - contentOrigin[0]).toFloat(),
                (anchorRect.top - contentOrigin[1]).toFloat(),
                (anchorRect.right - contentOrigin[0]).toFloat(),
                (anchorRect.bottom - contentOrigin[1]).toFloat(),
            )
            // 本体截图按锚点圆角抗锯齿圆角矩形绘制（ClipRevealLayout.drawAnchorBody）：
            // 裁剪矩形四角与卡片圆角之间的月牙区残留着源列表"页面底色+阴影"像素，容器
            // 底色改卡片表面色后会压在浅底上显形（用户截图："圆角卡片像从方框里裁出来、
            // 四角外发黑"）——半径与 buildRevealGeometry 的 startRadius 同源
            setAnchorCornerRadiusPx(ClipReveal.defaultAnchorRadiusPx(activity))
            // 文字层（可选）：分层绘制 = 卡片本体钉在原位、仅内部文字随窗口上边滑移
            // 渐隐（2026-09-27 SportLink 8452508 新版，修"列表文字整卡上滑/下滑"）。
            // 仅同方向装配（anchorTransform == null）才挂：跨方向换算钩子只映射主矩形；
            // 而跨方向页（趋势全屏）的锚点是紧凑按钮类、本就无文字层
            if (anchorTransform == null) {
                capture.textBitmap?.let { tb ->
                    val t = capture.textRect ?: return@let
                    setAnchorTextBitmap(
                        tb,
                        (t.left - contentOrigin[0]).toFloat(),
                        (t.top - contentOrigin[1]).toFloat(),
                        (t.right - contentOrigin[0]).toFloat(),
                        (t.bottom - contentOrigin[1]).toFloat(),
                    )
                }
            }
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
            sourceWindowWidth = capture.sourceWidth,
            sourceWindowHeight = capture.sourceHeight,
            verifySourceWindow = anchorTransform == null,
        )
        // 容器底色来源（与上面 setBackgroundColor 同一判据）：主题过期修复据此选新主题色
        host.containerColorIsCard = capture.textBitmap != null && capture.cardSurface != 0
        collapseHosts[activity] = host

        // 冻结列表背景（collapse-back 专用，2026-09-27）：详情窗口不透明，收拢期间裁剪
        // 窗口外只有窗口底色，结束后列表整页瞬现、其它卡片没有过渡（用户实测反馈）。
        // 把源页整窗截图垫到裁剪层下面（插到 index 0，比后加的 ClipRevealLayout 更底），
        // 收拢全程其它卡片可见；末帧冻结列表与真实列表逐像素一致（列表 stopped 期间
        // 不重组、布局冻结，转屏守卫已拦截尺寸变化场景），finish 后无缝替换。
        // 仅同方向 collapse-back（expandOnEnter=false）装配：趋势页跨方向（竖→横），
        // 竖屏截图垫进横屏窗口形状对不上；展开形态进场时窗口外应透出页面底色而非旧
        // 列表，用不上整窗截图，立即回收（约 18MB）
        capture.pageBitmap?.let { bmp ->
            if (expandOnEnter) {
                bmp.recycle()
            } else {
                val snapshot = PageSnapshotView(activity, bmp)
                content.addView(
                    snapshot,
                    0,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                host.pageSnapshot = snapshot
            }
        }

        // 进场展开：首帧 PreDraw 取消首绘 → 裁剪钳到源卡片矩形 → 四向撑开 + 内容延迟淡入。
        // expandOnEnter=false（进场走主题滑入的拆分形态）不挂：页面照常首帧绘制，
        // CollapseHost.progress 恒 1（整页），退场 startClipCollapse 从整页直接收拢
        if (expandOnEnter) {
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
    }

    /** 统一收尾入口：有收拢宿主 → 播收拢动画后 finish；无 → 直接 finish */
    fun finishWithTransform(activity: Activity) {
        if (!collapseAndFinish(activity)) activity.finish()
    }

    /**
     * 播收拢动画并在结束时 finish。返回 false = 没有收拢宿主或收拢已请求完毕
     * （调用方落回原 finish 路径）；返回 true = 收拢动画接管（重复 finish 被吞，
     * 防动画中途被二次打断）。
     *
     * [staleTheme] = 详情页期间系统深浅发生过变化（截图素材是旧主题像素，2026-09-28
     * 用户报"返回动画用旧色"）：收拢前执行 [CollapseHost.applyStaleThemeFix] 把冻结快照 /
     * 容器底色 / 遮罩色 / 锚点截图逐项换成目标主题；[darkTarget] = 变化后的目标深浅。
     */
    fun collapseAndFinish(
        activity: Activity,
        staleTheme: Boolean = false,
        darkTarget: Boolean = false,
    ): Boolean {
        val host = collapseHosts[activity] ?: return false
        if (host.finishRequested) return false
        if (host.closing) return true
        if (staleTheme) host.applyStaleThemeFix(darkTarget)
        host.requestClose()
        return true
    }

    /**
     * 详情页期间系统深浅变化时由页面调用（onConfigurationChanged / onResume 兜底路径）：
     * 提前在后台把冻结列表快照重映射到目标主题——收拢返回时快照已是新色（无缝），
     * 未就绪则由 [collapseAndFinish] 的 [CollapseHost.applyStaleThemeFix] 兜底纯主题色。
     * 无收拢宿主 / 无快照（普通启动无一镜到底）= no-op。
     */
    fun onThemeChangedForSnapshot(activity: Activity, darkTarget: Boolean) {
        collapseHosts[activity]?.requestSnapshotRemap(darkTarget)
    }

    private fun consumePendingStart(): Capture? {
        val capture = handoff ?: return null
        handoff = null
        val fresh = SystemClock.uptimeMillis() - handoffAtMs <= HANDOFF_TTL_MS
        if (fresh && !capture.rect.isEmpty) return capture
        // 过期/退化丢弃：整窗截图（约 18MB）显式回收，不等 GC
        capture.pageBitmap?.recycle()
        return null
    }
}

/**
 * 整窗冻结截图层（collapse-back 收拢的"冻结列表"背景，装配见
 * [AppTransitions.installWindowTransform]）：源页整窗截图按原尺寸铺满全屏（截图与
 * 窗口同尺寸时即 1:1 原样铺底），垫在裁剪容器 ClipRevealLayout 下面——详情窗口不透明，
 * 收拢期间裁剪窗口外原本只有窗口底色，返回结束后其它列表卡片凭空瞬现（2026-09-27
 * 用户反馈"没有过渡动画"）；垫上冻结列表后收拢全程其它卡片可见，末帧冻结列表与
 * 真实列表逐像素一致（列表 stopped 期间不重组、布局冻结），finish 后无缝替换。
 * 位图回收挂 [onDetachedFromWindow]：无论正常 finish、转屏降级淡出还是 Activity 直接
 * 销毁，视图脱离窗口即回收，不依赖收拢路径显式清理（防整窗位图 ≈18MB 泄漏）。
 */
private class PageSnapshotView(context: Context, val snapshot: Bitmap) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 当前显示位图（主题重映射后就地替换）；恒以 [snapshot] 原图为重映射源，二次切换不叠加失真 */
    private var current: Bitmap = snapshot

    /** 已同步到的主题（null = 尚未做过主题重映射 = 与拍摄时主题一致） */
    private var syncedDark: Boolean? = null

    /** 非 null = 纯色兜底模式（重映射未就绪时宁可无内容、不可闪旧色，见 [showSolid]） */
    private var solidColor: Int? = null

    /** 重映射源原图（AppTransitions 后台映射使用） */
    val sourceBitmap: Bitmap get() = snapshot

    /** 目标主题的重映射是否已就绪（就绪即可直接参与收拢，无需兜底） */
    fun themeSynced(dark: Boolean): Boolean = solidColor == null && syncedDark == dark

    /** 后台重映射完成：切换到新主题位图（主线程调用）。已进入纯色兜底则忽略（收拢动画中不再跳变） */
    fun overrideBitmap(bitmap: Bitmap, dark: Boolean) {
        if (solidColor != null) {
            bitmap.recycle()
            return
        }
        if (current !== bitmap) {
            if (current !== snapshot) current.recycle()
            current = bitmap
        }
        syncedDark = dark
        invalidate()
    }

    /**
     * 主题重映射未就绪时的兜底：纯主题底色铺满（2026-09-28，收拢返回时快照还是旧色 →
     * 用户实测"整屏旧色转场"；宁可底色无内容，不允许旧色上屏）。
     */
    fun showSolid(color: Int) {
        solidColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val solid = solidColor
        if (solid != null) {
            canvas.drawColor(solid)
            return
        }
        // 截图与窗口同尺寸（collapse-back 校验过同方向装配），drawBitmap 到整窗矩形
        // 即 1:1 原样铺底；尺寸意外不一致（理论不可达）时拉伸铺满，仍优于露窗口底色
        canvas.drawBitmap(current, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 映射副本与原图分别回收（同一引用时只回收一次）
        if (current !== snapshot) current.recycle()
        snapshot.recycle()
    }
}

// ── 截图素材的主题重映射（2026-09-28 深浅色切换后退场旧色修复）────────────────
// 冻结快照与锚点卡片截图都是"进详情页那一刻"拍的旧主题位图；详情页期间系统深浅变化后，
// 返回收拢动画会带着旧色（用户实测：整屏旧色转场）。这里按固定中性色对做**分段亮度
// 映射**（色值引用 PowerMeterTheme.withSportLinkSurfaces 的钉死中性色：页底 E0↔2A、
// 卡片白↔33、文字黑↔白），彩色按亮度等比缩放保色相；抗锯齿过渡像素由分段连续映射
// 自然覆盖（黑字与浅底的混合 → 白字与深底的同比例混合）。

/** 主题页底色（withSportLinkSurfaces 固定值，仅深浅两套） */
internal fun themeBackgroundColor(dark: Boolean): Int =
    if (dark) 0xFF2A2A2A.toInt() else 0xFFE0E0E0.toInt()

/** 主题卡片表面色（同上） */
internal fun themeCardColor(dark: Boolean): Int =
    if (dark) 0xFF333333.toInt() else 0xFFFFFFFF.toInt()

/**
 * 分段亮度映射（0..255 → 0..255）：
 * - 浅→深：0(黑字)→255(白)，224(页底)→42，255(卡片白)→51 —— [0,224] 与 [224,255] 两段线性；
 * - 深→浅：42(页底)→224，51(卡片)→255，255(白字)→0 —— [0,51] 与 [51,255] 两段线性。
 */
private fun mapTone(v: Int, darkTarget: Boolean): Int {
    val f = if (darkTarget) {
        if (v <= 224) 255f - 0.95f * v else 42f + 0.29f * (v - 224)
    } else {
        if (v <= 51) 224f + 31f * v / 51f else 255f - 1.25f * (v - 51)
    }
    return f.roundToInt().coerceIn(0, 255)
}

/**
 * 位图主题重映射（原位不动，返回新位图）：每像素按亮度走 [mapTone]，RGB 等比缩放
 * （保色相：暗色图标在深色体系变暗、亮色变亮，而非色相反转）；透明像素原样保留。
 * 分块处理（64 行/块）控制内存峰值——整窗快照 ≈18MB，整块 IntArray 会多占一份。
 */
internal fun remapBitmapTone(src: Bitmap, darkTarget: Boolean): Bitmap {
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val block = IntArray(w * 64)
    var y = 0
    while (y < h) {
        val rows = minOf(64, h - y)
        val n = w * rows
        src.getPixels(block, 0, w, 0, y, w, rows)
        for (i in 0 until n) {
            val c = block[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 亮度（整数近似 rec.601）→ 目标亮度；RGB 等比缩放
            val l = (77 * r + 151 * g + 28 * b) shr 8
            val l2 = mapTone(l, darkTarget)
            val r2 = if (l == 0) l2 else (r * l2 / l).coerceAtMost(255)
            val g2 = if (l == 0) l2 else (g * l2 / l).coerceAtMost(255)
            val b2 = if (l == 0) l2 else (b * l2 / l).coerceAtMost(255)
            block[i] = (a shl 24) or (r2 shl 16) or (g2 shl 8) or b2
        }
        out.setPixels(block, 0, w, 0, y, w, rows)
        y += rows
    }
    return out
}
