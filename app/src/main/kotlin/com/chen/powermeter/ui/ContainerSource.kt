package com.chen.powermeter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.chen.powermeter.util.AppTransitions

/**
 * 容器变换的**源条目**（被点后要"被纵向拉开"的那一枚按钮/那一张卡）。
 *
 * 数据结构用 [AppTransitions.Source]（调度侧持有），这里只提供 Compose 侧的采集能力：
 * 自身在**窗口坐标**中的矩形（必须是窗口坐标，因为目标页是另一个窗口）。
 * 与 SportLink 原版的分叉：不做 GraphicsLayer 内容录制（截图走 [AppTransitions.capture]
 * 的 decorView `View.draw` 同步绘制后裁出），文字层也只采集矩形（文字截图从同一张
 * 窗口位图上按矩形裁出，见 [rememberContainerTextSource]）。
 *
 * 只做采集，**不接管点击** —— 点击仍由调用方自己的 clickable 负责，
 * 不会与既有手势冲突。调用方在自己的点击回调里加一行 `AppTransitions.register(source)`。
 *
 * 用法：
 * ```
 * val source = rememberContainerSource()
 * val textSource = rememberContainerTextSource(source)   // 可选：列表行类锚点的文字层
 * SomeCard(
 *     modifier = Modifier.containerSource(source),
 *     onClick = { AppTransitions.register(source); openDetail() },
 *     textSourceModifier = textSource,
 * )
 * ```
 */
@Composable
fun rememberContainerSource(): AppTransitions.Source = remember { AppTransitions.Source() }

fun Modifier.containerSource(source: AppTransitions.Source): Modifier =
    onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        source.bounds.set(
            position.x.toInt(),
            position.y.toInt(),
            (position.x + coordinates.size.width).toInt(),
            (position.y + coordinates.size.height).toInt(),
        )
    }

/**
 * 登记源条目**内部文字/内容**的窗口矩形（配合 [containerSource] 使用，可选）：列表行类
 * 锚点传给动画做"卡片底面钉在原位、内容随窗口边滑移渐隐"的分层绘制
 * （见 [AppTransitions.Capture.textBitmap] / ClipRevealLayout.onDraw 分层分支）。
 * 挂在**内容整体**所在的节点上；未挂 = 整卡一起滑移（紧凑按钮类锚点的既有行为）。
 *
 * ⚠️ 节点必须落在卡片 padding **之内**：矩形若含 padding 环，抹字留白带 <8px 会被
 * 拒绝（见 AppTransitions.eraseAnchorTextRegion）→ 钉住的本体带着全部内容，
 * 滑移层与本体双重出现。SportLink 原版挂在左侧文本列（只有文字滑移），本项目按
 * 用户 2026-09-27 定稿挂全内容节点（图标+文字+统计一起滑移）。
 *
 * ```
 * val source = rememberContainerSource()
 * val textSource = rememberContainerTextSource(source)
 * Card(modifier = Modifier.containerSource(source), onClick = { register(source); open() }) {
 *     Column(Modifier.padding(16.dp)) {
 *         Column(Modifier.then(textSource)) { /* 全部内容 */ }
 *     }
 * }
 * ```
 */
@Composable
fun rememberContainerTextSource(source: AppTransitions.Source): Modifier =
    Modifier.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        source.textBounds.set(
            position.x.toInt(),
            position.y.toInt(),
            (position.x + coordinates.size.width).toInt(),
            (position.y + coordinates.size.height).toInt(),
        )
    }
