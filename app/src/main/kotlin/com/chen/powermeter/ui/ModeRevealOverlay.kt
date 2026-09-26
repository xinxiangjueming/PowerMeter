package com.chen.powermeter.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.ContextWrapper
import com.chen.powermeter.ui.theme.PowerMeterTheme

/** 一次监测模式切换转场的参数（ClipReveal 锚点卡片展开，见 [ClipReveal]） */
class ModeSwitchArgs(
    /** 切换目标模式（覆盖层里渲染的屏） */
    val target: MonitorMode,
    /**
     * 双击标题处标题栏的**窗口矩形**：展开窗口的四边与四角圆角都从它起算——视觉上是
     * "标题本体"在向外长大铺满全屏（SportLink 新版 Container Transform 形态，
     * Compose 侧用 `LayoutCoordinates.boundsInWindow()` 采集）。
     */
    val anchorRectInWindow: Rect,
)

/**
 * 监测模式切换的 **ClipReveal 锚点展开**覆盖层（口径对齐 SportLink 的 DeviceDetailOverlay：
 * 同一套 View 层 [ClipReveal] + ComposeView 承载新屏的接入形态）。
 *
 * 交互全貌（2026-09-25 用户口径：功率 ↔ 帧率的切换动画 = sportlink 最新的 ClipReveal）：
 * - 双击顶栏标题 → 全屏覆盖层插入到整棵界面之上，初始被裁剪成**标题矩形本体**（四角
 *   圆角 = 屏幕物理圆角），随后四边同时向外撑开铺满全屏，露出目标模式的完整界面；
 * - 详情内容在展开中段（f 0.48→0.88）淡入 + 12dp 上移归位（ClipReveal 内建）；
 * - **展开完成（onOpened）即落地 mode / Prefs 并撤掉覆盖层**：一次双击 = 完整切换，
 *   底层同帧换成目标模式、两层内容一致无缝交接。⚠️ 落地不能挂在收拢结束 —— 那是
 *   "预览后确认"的两段式语义，用户实测就是"第一次双击不切换、必须点第二次"
 *   （2026-09-25 反馈）；展开期（580ms 内）返回键 = 放弃切换（onCancel）。
 *
 * 覆盖层是独立 ComposeView（新组合），MaterialTheme 不会从外层组合继承 —— 必须自带
 * [PowerMeterTheme]；弹窗玻璃路径（稳帧指数 ⓘ / 删除确认等）也自带一套
 * [DialogBackdropHost]，与主界面互不依赖（同 SportLink DetailContent 的做法）。
 */
@Composable
fun ModeRevealOverlay(
    args: ModeSwitchArgs,
    /**
     * 覆盖层底色 = 页面真实背景色。由调用方在 PowerMeterTheme 内取
     * `MaterialTheme.colorScheme.background.toArgb()` 传入 —— 深浅色/动态取色都跟随主题；
     * 不能用 View 层主题的 android:colorBackground（深色模式下是白的，动画闪白）。
     */
    backgroundColor: Int,
    /** 展开完成：落地 mode / Prefs（MainActivity 侧幂等守卫） */
    onCommit: () -> Unit,
    /** 展开期被收回（返回键）：放弃本次切换 */
    onCancel: () -> Unit,
    /** 渲染目标模式屏；入参 onToggleMode = 覆盖层内的切换入口（展开期收拢，见下） */
    screen: @Composable (onToggleMode: (Rect) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }

    DisposableEffect(activity, args) {
        val holder = activity?.let { act ->
            // Compose 侧全程用 geometry.Rect（positionInWindow 口径），openRevealAt 的
            // View 层边界吃 android.graphics.Rect —— 在这里一次性换算（px 值一致）
            val r = args.anchorRectInWindow
            val anchorRect = android.graphics.Rect(
                r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(),
            )
            ClipReveal.openRevealAt(
                activity = act,
                anchorYInWindow = r.center.y,
                backgroundColor = backgroundColor,
                // 展开完成 = 切换落地（一次双击完整切换，2026-09-25 用户口径）
                onOpened = onCommit,
                // 展开期收拢（返回键）= 放弃切换
                onClosed = onCancel,
                // 锚点矩形：展开窗口从标题本体起算（buildRevealGeometry 对退化矩形
                // 自动回落"过中心线的全宽线"，Rect.Zero 等异常值不会崩）
                anchorRectInWindow = anchorRect,
                createContent = { ctx, _ ->
                    ComposeView(ctx).apply {
                        setContent {
                            PowerMeterTheme {
                                DialogBackdropHost {
                                    // 覆盖层内的双击 = 收回（回到底层旧模式）
                                    screen { _ -> ClipReveal.closeActive() }
                                }
                            }
                        }
                    }
                },
            )
        }
        // 宿主组合销毁（Activity 重建/销毁、或本覆盖层已被新转场替换）：移除覆盖层但
        // **不触发 commit** —— 那条路径的 onCommit 写的是销毁中的组合的状态、或会覆盖
        // 掉新一场转场的在途状态（概率性"切了但停在旧模式"的竞态，见 ClipReveal.dismissNow）
        // 提交后的撤层 / 宿主组合销毁：移除覆盖层但**不触发任何回调** —— 落地只走
        // onOpened，收拢放弃只走 onClosed；销毁路径的回调会覆盖新转场的在途状态
        // （2026-09-25 概率性"切了但停在旧模式"的竞态，见 ClipReveal.dismissNow）
        onDispose { holder?.dismissNow(commit = false) }
    }
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
