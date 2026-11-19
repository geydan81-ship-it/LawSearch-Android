package com.lawsearch.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lawsearch.app.ui.theme.tokens

/**
 * 统一玻璃表面。
 *
 * ## 设计依据（Apple HIG「Materials」）
 *
 * HIG 把玻璃分成两档：
 *  - **Regular**：用于文字较多的元素（卡片、工具栏、面板）——「模糊 + 降低亮度对比」保证可读
 *  - **Clear**：高度透明，只用于浮在**视觉丰富媒体**上的控件
 *
 * 本 App 的全局壁纸就是「视觉丰富媒体」，所以：
 *  - 卡片 / 面板 → Regular（中等白度 + 模糊感）
 *  - 按钮 / 图标按钮 / 胶囊 → Clear（更高透明度，让壁纸透出来）
 *
 * ## 为什么不用真玻璃（lens 边缘折射）做所有元素
 *
 * backdrop 的 `lens` 每帧在 GPU 上跑 `RuntimeShader`，长列表里几十个卡片同时折射会直接掉帧。
 * 苹果自己的做法也是：**只有 TabBar / 工具栏这类少数「浮层控件」用真玻璃**，
 * 内容层的卡片用系统 Material（半透明 + 模糊）。这里遵循同样的取舍。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    /** Regular（卡片/面板）还是 Clear（按钮/胶囊） */
    clear: Boolean = false,
    /** 选中/按下时提亮 */
    emphasized: Boolean = false,
    elevation: Dp = 6.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val t = tokens()
    val glassOn = LocalGlassEffectsEnabled.current

    // 白度：Regular 更实，Clear 更透
    val baseAlpha = when {
        !glassOn -> 1f
        clear -> if (t.isDark) 0.16f else 0.42f
        emphasized -> if (t.isDark) 0.30f else 0.82f
        else -> if (t.isDark) 0.24f else 0.62f
    }
    val fill = if (t.isDark) {
        Color(0xFF1C1C1E).copy(alpha = if (glassOn) baseAlpha else 1f)
    } else {
        Color.White.copy(alpha = baseAlpha)
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = if (glassOn) elevation else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.18f),
            )
            .clip(shape)
            .background(fill)
            .then(
                if (glassOn) {
                    // 顶部内发光 + 细边——玻璃「厚度感」的来源
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (t.isDark) 0.06f else 0.30f),
                                Color.Transparent,
                            ),
                        ),
                    ).border(
                        0.7.dp,
                        Color.White.copy(alpha = if (t.isDark) 0.14f else 0.62f),
                        shape,
                    )
                } else {
                    Modifier
                }
            ),
        content = content,
    )
}

/** 胶囊形状的玻璃（按钮、chip、标签） */
@Composable
fun GlassCapsule(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        clear = true,
        emphasized = selected,
        elevation = if (selected) 4.dp else 2.dp,
        content = content,
    )
}
