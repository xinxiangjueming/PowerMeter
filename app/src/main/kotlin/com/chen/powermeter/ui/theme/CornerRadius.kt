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

fun getScreenCornerRadius(context: Context): Dp {
    if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.MANUFACTURER.equals("Redmi", ignoreCase = true)
    ) {
        runCatching {
            val resourceId = context.resources.getIdentifier(
                "rounded_corner_radius_top", "dimen", "android"
            )
            if (resourceId > 0) {
                val radiusPx = context.resources.getDimensionPixelSize(resourceId)
                if (radiusPx > 0) {
                    val radiusDp = radiusPx / context.resources.displayMetrics.density
                    Log.d("CornerRadius", "Using screen corner radius: ${radiusDp}dp (px=$radiusPx)")
                    return radiusDp.dp
                }
            }
        }.onFailure {
            Log.w("CornerRadius", "Failed to get screen corner radius: ${it.message}")
        }
    }
    return 28.dp
}
