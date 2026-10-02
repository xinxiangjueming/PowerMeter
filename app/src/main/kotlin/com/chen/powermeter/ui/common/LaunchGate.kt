package com.chen.powermeter.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 跨页启动的**重复点击闸门**。
 *
 * 根因（2026-10-02 用户报障：帧率历史列表连点卡片，详情页叠开三四层，要返回三四次才回列表）：
 * `startActivity` 到目标窗口接管触点之间有一个窗口期，本页仍处于 resumed，落在窗口期里的
 * 每次 tap 都会照常进入点击回调 —— 每一次 tap 就是一次 startActivity，叠一层目标页。
 * 这个窗口期不固定（进场一镜到底的首帧组合 + 窗口动画，大场次上百 ms 起步），固定时长的
 * 防抖在慢机器上拦不住，所以不按时间闸，按**生命周期**闸。
 *
 * 闸门口径：首次放行即上闸，宿主页重新 [Lifecycle.Event.ON_RESUME]（从目标页返回 /
 * 目标页消亡）才复位 —— 置位期间用户必然不在本页（目标页盖在上面），复位时机与
 * 「用户回到了本页」天然对齐，无需拍任何超时数值。
 */
class LaunchGate internal constructor() {

    private var armed = false

    /** true = 放行（并上闸）；false = 已有导航在途，本次点击丢弃 */
    fun tryLaunch(): Boolean {
        if (armed) return false
        armed = true
        return true
    }

    internal fun reset() {
        armed = false
    }
}

/**
 * 取一个挂在**当前宿主页**生命周期上的 [LaunchGate]（复位 = 宿主页 ON_RESUME）。
 * 宿主按 [ComponentActivity] 处理（本应用两处调用方都在 MainActivity 的 setContent 里，
 * LocalContext 恒为它）；拿不到 ComponentActivity 时返回不自动复位的裸闸门（极端容错，
 * 只损失「从目标页返回后还能再点」）。
 */
@Composable
fun rememberLaunchGate(): LaunchGate {
    val gate = remember { LaunchGate() }
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) gate.reset()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    return gate
}
