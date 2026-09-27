package com.chen.powermeter.ui.theme

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.chen.powermeter.ui.ThemeTransition
import com.chen.powermeter.ui.ThemeTransitionOverlay

/**
 * Material 3 Expressive 主题。
 *
 * 关键点：
 * - Expressive API 只在 material3 1.5.0-alpha 中提供（稳定版 1.4.0 不含），故使用 alpha 版本。
 * - MotionScheme.expressive() 启用 Express 弹簧动效（所有 M3 组件继承该动效体系）。
 * - 背景 / 卡片等**中性色系固定为 SportLink 同款色值**（见 [withSportLinkSurfaces]，
 *   2026-09-27 用户定案：不再随壁纸压深/取色），强调色（primary/secondary 等）仍走莫奈动态。
 * - **主题切换闸门**（2026-09-28，对齐 SportLink SportLinkTheme / Now in Android 模式）：
 *   「目标深浅」[darkTheme] 与「已生效深浅」[appliedDark] 分离——目标变化时先 PixelCopy
 *   截旧界面快照再放行，消除系统深浅色切换的硬切；覆盖层内置 Box 顶层，全部页面自动获得
 *   「右下角圆孔扩散露出新主题」动画（详见 ThemeTransition）。
 */
@Composable
fun PowerMeterTheme(
    // 「目标深浅」：默认读系统（注意在 provider 之前求值，读到的是系统真值）；
    // 经下方闸门延迟生效，全部渲染取材见 appliedDark。
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    /** 主题切换圆形揭露过渡开关（ThemeTransition）：仅离屏渲染等特殊场景显式关掉 */
    themeTransition: Boolean = true,
    content: @Composable () -> Unit,
) {
    // ── 主题切换闸门 ──────────────────────────────────────────────────────
    // 目标深浅变化时先截旧界面快照再放行；全部渲染取材以 appliedDark 为准，二元读点
    // （isSystemInDarkTheme 等）经下方 LocalConfiguration 深色位覆盖与动画同步切换。
    // 首次组合 appliedDark 即终值，无动画；截图失败无动画直接切（降级不劣于现状）。
    var appliedDark by remember { mutableStateOf(darkTheme) }
    val view = LocalView.current

    LaunchedEffect(darkTheme) {
        if (appliedDark == darkTheme) return@LaunchedEffect
        if (!themeTransition) { appliedDark = darkTheme; return@LaunchedEffect }
        // 后台错过的变化在 onResume 兜底补状态（requestSilent）→ 静默放行，不补播动画
        // （2026-09-28：返回本页时应直接呈现目标主题，而非再播一遍圆孔动画）
        if (ThemeTransition.consumeSilent()) { appliedDark = darkTheme; return@LaunchedEffect }
        // 已有快照在播（动画未播完又发生新切换）：直接放行，overlay 继续播
        if (ThemeTransition.snapshot != null) { appliedDark = darkTheme; return@LaunchedEffect }
        // 宿主不在前台（被不透明二级页覆盖的源页走了 onStop，后台期间系统深浅变化分不到它，
        // 状态只能等回前台才补）→ 直接放行：不截图、不播动画。否则用户会看到"返回瞬间按旧
        // 主题画一帧 + 紧接着补播 800ms 圆孔"（2026-09-28 用户报）。判据见
        // [ThemeTransition.isHostForeground]；前台切换不受影响（那时宿主必为 RESUMED）。
        val host = ThemeTransition.activityOf(view.context)
        if (host == null || !ThemeTransition.canAnimateFrom(host)) {
            appliedDark = darkTheme
            return@LaunchedEffect
        }
        // 系统切换路径：立即截旧界面；失败则无动画直接切
        ThemeTransition.capture(host)
        appliedDark = darkTheme
    }

    val context = LocalContext.current
    val cornerRadius = remember { getScreenCornerRadius(context) }
    val baseScheme = remember(appliedDark, dynamicColor, context) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (appliedDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            appliedDark -> darkColorScheme()
            else -> lightColorScheme()
        }
    }
    // 中性色系（背景/卡片/浮层容器）钉成 SportLink 同款固定值，强调色保持动态
    val colorScheme = remember(baseScheme, appliedDark) {
        baseScheme.withSportLinkSurfaces(appliedDark)
    }

    // LocalConfiguration 深色位覆盖（对齐 SportLinkTheme）：BlurGlass / ColorPickerDialog /
    // PowerMeterScreen 里直调 isSystemInDarkTheme() 的取色点不经本函数参数——覆盖后它们
    // 与圆孔擦除动画同步切换，不绕过闸门硬切。只改 NIGHT 位，density/locale 等字段原样复制。
    // LocalConfiguration 是 staticCompositionLocalOf：覆盖值变化 → 整个子树重组（放行
    // 瞬间的一次性成本），无需 recreate。
    val baseConfig = LocalConfiguration.current
    val appConfig = remember(baseConfig, appliedDark) {
        Configuration(baseConfig).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (appliedDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
    }

    // 小米/Redmi 读 HyperOS 屏幕物理圆角，其余设备回落 28.dp；
    // 必须通过 CompositionLocalProvider 下发，否则下游 LocalCornerRadius.current 拿到的永远是默认值
    CompositionLocalProvider(
        LocalCornerRadius provides cornerRadius,
        LocalConfiguration provides appConfig,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = Typography(),
        ) {
            // Box 不加约束：尺寸由 content 决定（全屏 content → 覆盖层全屏；非全屏场景
            // 零布局影响）。覆盖层最后声明盖在 content 之上，动画期间拦截全部触摸、
            // 播完自动从组合移除（见 ThemeTransition）。
            Box {
                content()
                if (themeTransition) ThemeTransitionOverlay()
            }
        }
    }
}

// ==================== 中性色系 = SportLink 固定色 ====================
// 色值逐项取自 SportLink ui/theme/SportLinkTheme.kt 的 Light/DarkColorScheme（背景
// #E0E0E0/#2A2A2A、卡片 #FFFFFF/#333333，surfaceContainer* 全档与 on* 文字透明度同源）。
// 只钉中性槽位；primary/secondary/error 及容器对不动 —— 颜色胶囊莫奈取色等动态特性保留。
// 消费面（全部自动跟随，无需散改）：卡片 surfaceContainerLow、弹窗 surfaceContainerHigh
// （= SportLink DialogBg）、顶栏毛玻璃 tint = surface、转场截图底色 = background。
private fun ColorScheme.withSportLinkSurfaces(darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        copy(
            background = Color(0xFF2A2A2A),
            onBackground = Color.White,
            surface = Color(0xFF333333),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF2A2A2A),
            onSurfaceVariant = Color(0xB3FFFFFF),
            surfaceContainerLowest = Color(0xFF333333),
            surfaceContainerLow = Color(0xFF333333),
            surfaceContainer = Color(0xFF333333),
            surfaceContainerHigh = Color(0xFF3A3A3A),
            surfaceContainerHighest = Color(0xFF424242),
        )
    } else {
        copy(
            background = Color(0xFFE0E0E0),
            onBackground = Color(0xDE000000),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xDE000000),
            surfaceVariant = Color(0xFFE0E0E0),
            onSurfaceVariant = Color(0x8A000000),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFFFFFF),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFFFFFFF),
            surfaceContainerHighest = Color(0xFFF5F5F5),
        )
    }
