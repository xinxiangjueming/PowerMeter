package com.chen.powermeter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive 主题。
 *
 * 关键点：
 * - Expressive API 只在 material3 1.5.0-alpha 中提供（稳定版 1.4.0 不含），故使用 alpha 版本。
 * - MotionScheme.expressive() 启用 Express 弹簧动效（所有 M3 组件继承该动效体系）。
 * - 页面底色在 scheme 之上再压深一档，见 [ColorScheme.withDeeperBackground]。
 */
@Composable
fun PowerMeterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val cornerRadius = remember { getScreenCornerRadius(context) }
    val baseScheme = remember(darkTheme, dynamicColor, context) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            darkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
    }
    // 页面底色压深，保证卡片能从底上浮出来（详见函数注释）
    val colorScheme = remember(baseScheme, darkTheme) {
        baseScheme.withDeeperBackground(darkTheme)
    }

    // 小米/Redmi 读 HyperOS 屏幕物理圆角，其余设备回落 28.dp；
    // 必须通过 CompositionLocalProvider 下发，否则下游 LocalCornerRadius.current 拿到的永远是默认值
    CompositionLocalProvider(LocalCornerRadius provides cornerRadius) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = Typography(),
            content = content,
        )
    }
}

private fun Color.darken(ratio: Float): Color = lerp(this, Color.Black, ratio)

/**
 * 把页面底色压到比卡片内部色更深的一档，拉开层次。
 *
 * 背景问题的根因：M3 的 baseline / dynamic scheme 中 `background` 与卡片填充色
 * `surfaceContainerLow` 只差一个 tonal step（夜间 #1C1B1F vs #211F26，日间
 * #FEF7FF vs #F7F2FA），亮度差约 2~4 L*，视觉上几乎连成一片，卡片边界全靠阴影撑。
 *
 * 取值口径对齐 SportLink（ui/theme/SportLinkTheme.kt）：
 * 日间 background #E0E0E0 / 卡片 #FFFFFF，夜间 background #2A2A2A / 卡片 #333333，
 * 两者都维持「底色比卡片更深一档」的关系。
 * - 夜间：直接取 surfaceContainerLowest（≈ #0F0D13），比卡片（≈ #211F26）深约 8 L*；
 * - 日间：surfaceContainerHighest 再压暗 8%（≈ #D4CED6），比卡片（≈ #F7F2FA）深约 12 L*。
 *
 * 只改 `background`，不动 `surface`：surface 留给 Dialog / Menu / BottomSheet 等浮层，
 * 浮层保持比底色浅才不会糊进页面。顶栏与页面共用 background，底色与内容区自然连成一片。
 */
private fun ColorScheme.withDeeperBackground(darkTheme: Boolean): ColorScheme =
    copy(
        background = if (darkTheme) {
            surfaceContainerLowest
        } else {
            surfaceContainerHighest.darken(0.08f)
        },
    )
