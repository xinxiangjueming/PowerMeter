package com.chen.powermeter.ui.theme

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局圆角：小米/Redmi 设备取 HyperOS 屏幕物理圆角，其余设备默认 28dp。
 * 与 SportLink 保持同一口径：卡片、按钮一律使用该圆角，禁止默认方形。
 */
val LocalCornerRadius = staticCompositionLocalOf { 28.dp }

private fun isXiaomiLike(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.MANUFACTURER.equals("Redmi", ignoreCase = true)

/**
 * 小米系屏幕物理圆角（px，HyperOS API: rounded_corner_radius_top）。
 * 非小米设备/读取失败返回 null。
 * 系统对整块屏幕的显示遮罩就是按这个值圆的，转场动画的圆角（ui/ClipReveal）与它
 * 同源，展开收尾时四角才能和遮罩无缝衔接。
 * https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1631
 */
fun screenCornerRadiusPx(context: Context): Float? {
    if (!isXiaomiLike()) return null
    return try {
        val resourceId = context.resources.getIdentifier(
            "rounded_corner_radius_top", "dimen", "android"
        )
        if (resourceId > 0) {
            val radiusPx = context.resources.getDimensionPixelSize(resourceId)
            if (radiusPx > 0) {
                Log.d("CornerRadius", "Using screen corner radius (px=$radiusPx)")
                return radiusPx.toFloat()
            }
        }
        null
    } catch (e: Exception) {
        Log.w("CornerRadius", "Failed to get screen corner radius: ${e.message}")
        null
    }
}

/**
 * 获取屏幕圆角（dp 口径）。小米 HyperOS API: rounded_corner_radius_top，
 * 非小米/读取失败回落 28dp —— 与 [screenCornerRadiusPx] 同一份数据源。
 * https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1631
 */
fun getScreenCornerRadius(context: Context): Dp {
    val radiusPx = screenCornerRadiusPx(context) ?: return 28.dp
    return (radiusPx / context.resources.displayMetrics.density).dp
}
