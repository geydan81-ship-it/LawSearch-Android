package com.lawsearch.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/**
 * 液态玻璃（Liquid Glass）—— 基于 `io.github.kyant0:backdrop`。
 *
 * ## 为什么换成 backdrop
 *
 * 之前用的 AGSL 玻璃库已不再发布，现改用
 * [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的
 * `backdrop` 库（Apache-2.0）。选它的理由：
 *  - AGSL 边缘折射（真正的透镜效果），带 `chromaticAberration` 色散开关
 *  - `vibrancy()` 提供类似 iOS 的玻璃饱和度提升，避免模糊后发灰
 *  - `Highlight` / `Shadow` / `InnerShadow` 自带 iOS 26 的高光与投影层次
 *  - 纯 `RenderEffect` + `GraphicsLayer` 实现，不依赖 `minCompileSdk` 的硬门槛
 *
 * ## 与旧实现的区别
 *
 * 之前只是「半透明 + 模糊」= 毛玻璃。
 * iOS 26 Liquid Glass 的核心是**高透明 + 边缘折射 + 高光**，模糊只是辅助。
 *
 * ## backdrop 的实际 API（2.0.1，编译期确认）
 *
 * ```kotlin
 * Modifier.drawBackdrop(
 *     backdrop: Backdrop,
 *     shape: () -> Shape,                      // Function0<Shape>
 *     effects: BackdropEffectScope.() -> Unit,
 *     highlight: () -> Highlight = { Highlight.Default },
 *     shadow: () -> Shadow = { Shadow.Default },
 *     innerShadow: () -> InnerShadow,
 *     layerBlock: GraphicsLayerScope.() -> Unit,
 *     exportedBackdrop: LayerBackdrop? = null,
 *     onDrawBehind: DrawScope.() -> Unit,
 *     onDrawBackdrop: (DrawScope, DrawScope.() -> Unit) -> Unit,
 *     onDrawSurface: DrawScope.() -> Unit,
 *     onDrawFront: DrawScope.() -> Unit,
 * )
 *
 * BackdropEffectScope.() -> Unit 里可用：
 *   vibrancy()                                   // 无参数
 *   blur(radius: Float, edgeTreatment: TileMode = TileMode.Clamp)
 *   lens(refractionHeight: Float, refractionAmount: Float,
 *        depthEffect: Boolean = false, chromaticAberration: Boolean = false)
 * ```
 */
object LiquidGlass {

    val level: Level
        get() = when {
            android.os.Build.VERSION.SDK_INT >= 33 -> Level.AGSL
            android.os.Build.VERSION.SDK_INT >= 31 -> Level.BLUR
            else -> Level.FROST
        }

    /**
     * 当前设备能否真正渲染玻璃效果。
     *
     * 之前的实现写死 `= true`，导致设置页里「当前系统版本不支持，已使用纯色界面」
     * 这个分支**永远走不到**——是死代码，开关也永远可点。
     *
     * 判定依据（与 backdrop 库的实际能力对齐）：
     *  - API 31+ 有 `RenderEffect`（`Modifier.blur` / `GraphicsLayer.renderEffect`），
     *    半透明模糊可以真实渲染；
     *  - API < 31 没有 RenderEffect，只能画半透明表面，属于「纯色降级」。
     *
     * 注意 API 33+ 才有 AGSL（`RuntimeShader`），但 lens 折射在 backdrop 内部
     * 已用 `isRuntimeShaderSupported()` 自行降级，所以这里只看 31 这条基线。
     */
    val isSupported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    enum class Level { AGSL, BLUR, FROST }
}

/** 用户可在「设置 → 外观」关闭 */
val LocalGlassEffectsEnabled = compositionLocalOf { true }

@Composable
fun rememberGlassEffects(): Boolean = LocalGlassEffectsEnabled.current

/**
 * 液态玻璃容器。
 *
 * @param backdrop 背景层；页面内容需用 `Modifier.layerBackdrop(backdrop)` 登记进去
 * @param capsule 是否胶囊形（TabBar / 按钮用）
 */
@Composable
fun LiquidGlassBox(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shapeRadius: Dp = 24.dp,
    capsule: Boolean = false,
    tint: Color = Color.White.copy(alpha = 0.10f),
    blurRadius: Dp = 6.dp,
    refraction: Dp = 24.dp,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current

    if (backdrop == null || !enabled) {
        Box(
            modifier = modifier.background(
                if (tint.alpha < 0.5f) Color.White.copy(alpha = 0.96f) else tint,
            ),
            content = content,
        )
        return
    }

    // 半径与折射参数在组合期就换算成 px：drawBackdrop 的 effects lambda
    // 在绘制阶段执行，scope 本身是 Density，但这里提前算好更直观、也避免重复 toPx。
    val blurPx = with(density) { blurRadius.toPx() }
    val refractionHeightPx = with(density) { refraction.toPx() }
    val refractionAmountPx = with(density) { (refraction * 0.6f).toPx() }

    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = {
                if (capsule) Capsule() else RoundedRectangle(shapeRadius)
            },
            effects = {
                // 饱和度提升：模糊后画面会发灰，vibrancy 把它拉回来（iOS 玻璃观感的关键）
                vibrancy()
                // refractionHeight = 折射带高度，refractionAmount = 最大采样偏移
                lens(
                    refractionHeight = refractionHeightPx,
                    refractionAmount = refractionAmountPx,
                )
                blur(blurPx)
            },
            // ⚠️ 不要用 onDrawSurface 铺 tint！
            //
            // 曾经的写法是 `onDrawSurface = { drawRect(tint) }`，导致 TabBar 变成
            // 一块纯色实心板（实测整块像素恒为 RGB(35,35,38)、std=0.00），
            // 折射层完全被盖住 —— 玻璃效果等于消失。
            //
            // backdrop 的 effects{} 已经负责"取背景 → 模糊 → 折射"，
            // 这里再叠一层不透明的 surface 就把结果盖掉了。
            // 想要轻微着色的话，正确做法是在 effects 里用 colorControls()/opacity()，
            // 或者干脆不叠（官方 LiquidBottomTabs 的 containerColor 也只有 0.4 alpha，
            // 且它画在自己的 layerBlock 上，不会压住折射结果）。
            onDrawSurface = {
                // 只画极轻的一层色偏，让白玻璃在深色背景上不显得脏。
                // 用 tint 的 alpha 但要压到很低（≤0.12），保证底下的折射仍然可见。
                if (tint.alpha > 0f) {
                    drawRect(tint.copy(alpha = (tint.alpha * 0.28f).coerceAtMost(0.12f)))
                }
            },
        ),
        content = content,
    )
}
