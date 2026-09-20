package com.kongj.powermeter.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * 顶栏模糊封装（API 门控三档，移植自 fold 同名组件 ui/common/BlurTopBar.kt）：
 * - API≥33：kyantBackdrop 非空 → com.kyant.backdrop 渐进式模糊（顶部全模糊 → 底部渐隐至透明的玻璃质感）。
 * - API 31–32：hazeState/hazeStyle 非空 → Haze 真实模糊（RenderEffect 可用区间）。
 * - API 26–30：Haze 的 RenderEffect 不可用（需 API≥31）→ surface 色纵向渐变盖板（渐变假模糊，非真实模糊）。
 *
 * 模糊背景与 [content]（如 containerColor = Transparent 的 TopAppBar）自动同尺寸（包裹内容测量），
 * 无需显式传高度。顶栏内容三路复用，不重复编写。
 * [tintColor] 覆盖混色（渐进式/渐变盖板两档用）：默认取 MaterialTheme.surface。
 *
 * 配套：内容层根节点需按版本挂采样源——
 *   API≥33 → Modifier.layerBackdrop(kyantBackdrop)；API 31+ → Modifier.hazeSource(hazeState)。
 *   两者可挂同一节点；顶栏（本组件）必须位于采样源节点之外作为兄弟节点，避免自我引用
 *   （MIUI/HyperOS MiBackgroundBlurBlend 自引用崩溃的教训）。
 */
@Composable
fun BlurTopBar(
    kyantBackdrop: Backdrop?,
    hazeState: HazeState?,
    hazeStyle: HazeStyle?,
    modifier: Modifier = Modifier,
    tintIntensity: Float = 0.2f,
    tintColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tint = tintColor ?: MaterialTheme.colorScheme.surface
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            kyantBackdrop != null && Build.VERSION.SDK_INT >= 33 -> {
                // 渐进式玻璃：录制背后内容为降采样离屏层，叠加 BlurEffect(4dp) 与 AGSL 着色器
                // ProgressiveBlurAlphaMask，实现「顶部全模糊 → 底部渐隐」并按 tintIntensity 混入 surface 色
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawPlainBackdrop(
                            backdrop = kyantBackdrop,
                            shape = { RectangleShape },
                            effects = {
                                blur(4f.dp.toPx())
                                runtimeShaderEffect(
                                    "ProgressiveBlurAlphaMask",
                                    """
    uniform shader content;
    uniform float2 size;
    layout(color) uniform half4 tint;
    uniform float tintIntensity;

    half4 main(float2 coord) {
        float blurAlpha = smoothstep(size.y, size.y * 0.6, coord.y);
        float tintAlpha = smoothstep(size.y, size.y * 0.7, coord.y);
        return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, tintIntensity);
    }""",
                                    "content"
                                ) {
                                    // backdrop 2.0.1 的 BackdropEffectScope 不再暴露 downsampleScale，
                                    // 这里直接把离屏层的实际像素尺寸传给着色器（fold 版本为 1.x/早期 2.0 的写法）
                                    setFloatUniform("size", size.width, size.height)
                                    setColorUniform("tint", tint)
                                    setFloatUniform("tintIntensity", tintIntensity)
                                }
                            }
                        )
                )
            }
            hazeState != null && hazeStyle != null && Build.VERSION.SDK_INT >= 31 -> {
                // Haze 真实模糊：效果节点与内容层（hazeSource）分离为兄弟，规避 MIUI 自引用崩溃
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .hazeEffect(state = hazeState, style = hazeStyle)
                )
            }
            else -> {
                // API 26–30：surface 色纵向渐变盖板（顶部 alpha 0.9 向下渐隐至透明，非真实模糊）
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to tint.copy(alpha = 0.9f),
                                    0.4f to tint.copy(alpha = 0.82f),
                                    0.7f to tint.copy(alpha = 0.6f),
                                    1.0f to tint.copy(alpha = 0.0f)
                                )
                            )
                        )
                )
            }
        }
        content()
    }
}
