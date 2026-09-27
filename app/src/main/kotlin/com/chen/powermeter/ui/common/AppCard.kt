package com.chen.powermeter.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 全 App 卡片的**唯一**容器实现（2026-09-27 统一口径）。
 *
 * 背景：此前同一张"卡片"在三个页面上有三种写法、三种 tonalElevation ——
 * 功率页 [Surface](tonal=2)、功率页主卡(tonal=3)、帧率列表页 ElevatedCard(tonal=0)、
 * 帧率详情页概览卡(tonal=3) 与其余卡(tonal=2)。
 *
 * 为什么 tonal 差异会**真的显色**（而非只影响阴影）：Theme 把 surfaceContainerLow
 * 钉成与 surface 同值（深 0xFF333333 / 浅 0xFFFFFFFF，见 Theme.withSportLinkSurfaces），
 * 而 material3 1.5.0-alpha27 的 Surface 无条件走
 * `applyTonalElevation(color, absoluteElevation)`，该函数仅在 `color == surface` 时
 * 叠加 surfaceTint —— 两个条件同时成立，于是 tonal 2/3 让卡片带上 primary 色偏，
 * tonal 0（ElevatedCard 内部 Surface 不传 tonalElevation）则保持纯灰。
 *
 * **统一基准 = 帧率列表页数据列表卡的观感**（用户 2026-09-27 指定）：纯
 * surfaceContainerLow、不叠任何 surfaceTint，即 tonalElevation 恒 0 —— 与旧
 * ElevatedCard 完全同色。其余所有卡片（功率页、帧率详情页、全屏趋势页）向它对齐。
 *
 * 错误提示卡（errorContainer）与导入提示条（secondaryContainer）语义不同，不并入本组件。
 */
@Composable
fun AppCard(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = APP_CARD_TONAL_ELEVATION,
        shadowElevation = APP_CARD_SHADOW_ELEVATION,
        modifier = modifier,
        content = content,
    )
}

/**
 * 卡片色调 elevation：全 App 唯一取值，勿在调用点另行指定。
 *
 * ⚠️ 恒 0 是**有意的**（基准 = 帧率列表卡旧观感）：surfaceContainerLow 与 surface 同值，
 * 一旦给非零 tonal，M3 就会往卡片上叠 surfaceTint（primary 色偏），卡片不再是纯
 * surfaceContainerLow（深 0xFF333333 / 浅 0xFFFFFFFF）。要恢复带色调的版本改这里即可，
 * 但必须三页一起变。
 */
private val APP_CARD_TONAL_ELEVATION = 0.dp

/** 卡片投影 elevation：全 App 唯一取值，勿在调用点另行指定 */
private val APP_CARD_SHADOW_ELEVATION = 1.dp
