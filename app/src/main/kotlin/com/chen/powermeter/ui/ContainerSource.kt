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
 * 与 SportLink 原版的分叉：不做 GraphicsLayer 内容录制（本项目 Compose 版本没有该 API），
 * 截图由 [AppTransitions.capture] 用 decorView `View.draw` 同步绘制后裁出。
 *
 * 只做采集，**不接管点击** —— 点击仍由调用方自己的 clickable 负责，
 * 不会与既有手势冲突。调用方在自己的点击回调里加一行 `AppTransitions.register(source)`。
 *
 * 用法：
 * ```
 * val source = rememberContainerSource()
 * SomePillButton(
 *     modifier = Modifier.containerSource(source),
 *     onClick = { AppTransitions.register(source); openDetail() },
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
