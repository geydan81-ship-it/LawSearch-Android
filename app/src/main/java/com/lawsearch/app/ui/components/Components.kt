package com.lawsearch.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lawsearch.app.ui.theme.IOSMotion
import com.lawsearch.app.ui.theme.tokens

/**
 * iOS 触控反馈：按下缩到 0.96、松开弹回，**没有** Material 水波纹。
 * 规格书 5.9 要求 `indication = null`，这里统一封装，所有可点元素都走它。
 */
@Composable
fun IOSTap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressedOverlay: Color = Color(0x0F000000),
    pressedScale: Float = 0.96f,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = IOSMotion.tap(),
        label = "iosTapScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = source,
                indication = null, // 关键：禁用安卓水波纹
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        content = {
            content()
            if (pressed) {
                Box(Modifier.matchParentSize().background(pressedOverlay))
            }
        },
    )
}

/** 玻璃卡片：16dp 圆角 + 玻璃质感（壁纸透出 + 顶部内发光 + 细边） */
@Composable
fun IOSCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    background: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = tokens()
    val glassOn = LocalGlassEffectsEnabled.current

    if (background != Color.Unspecified) {
        // 调用方指定了底色：按老方式画，不叠玻璃
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .background(background),
            content = content,
        )
        return
    }

    if (!glassOn) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(radius), clip = false,
                    ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
                .clip(RoundedCornerShape(radius))
                .background(t.card),
            content = content,
        )
        return
    }

    // 玻璃卡片：复用统一表面，Regular 档（文字多，需要更高白度保证可读）
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(radius),
        clear = false,
        elevation = 7.dp,
        content = content,
    )
}

/**
 * 胶囊按钮（chip）：高 34dp，圆角 17dp，内边距 14dp。玻璃质感，选中态是高光而非纯色填充。
 *
 * @param accentColor 文字/边框强调色。默认取主题 primary；
 *   阅读器这类「浅灰纸感」页面传入对比度更高的 [com.lawsearch.app.ui.theme.IOSTokens.readerPrimary]。
 */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    height: Dp = 34.dp,
    fontSize: Float = 14f,
    accentColor: Color? = null,
) {
    val t = tokens()
    val accent = accentColor ?: t.primary
    val glassOn = LocalGlassEffectsEnabled.current
    IOSTap(
        onClick = onClick,
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .then(
                if (glassOn) {
                    // 玻璃胶囊：选中态提亮（iOS 的 .glassEffect(.regular.interactive())）
                    // ⚠️ 在阅读器的浅灰纸感底上，白色玻璃底几乎和背景同色，
                    // 胶囊边界完全消失（用户反馈"上面的按钮看不清"）。
                    // 浅色场景下改用「白底 + 明显边框」，让边界清晰可见。
                    if (t.isDark) {
                        Modifier
                            .background(
                                Color.White.copy(alpha = if (selected) 0.26f else 0.12f),
                            )
                            .border(
                                0.7.dp,
                                Color.White.copy(alpha = if (selected) 0.16f else 0.16f),
                                RoundedCornerShape(height / 2),
                            )
                    } else {
                        Modifier
                            .background(Color.White.copy(alpha = if (selected) 1f else 0.92f))
                            .border(
                                1.dp,
                                if (selected) accent.copy(alpha = 0.75f) else Color(0xFFD3D7DE),
                                RoundedCornerShape(height / 2),
                            )
                    }
                } else {
                    Modifier.background(if (selected) t.chipSelectedBackground else t.chipBackground)
                },
            ),
        pressedScale = 0.94f,
        pressedOverlay = Color.Transparent,
    ) {
        Text(
            text = text,
            // 选中态用强调色文字（白底 + 加深强调色），而不是白字
            color = when {
                !glassOn -> if (selected) t.chipSelectedText else t.textPrimary
                selected -> accent
                // 未选中时也不要纯灰：在白底胶囊上要能看清
                else -> if (t.isDark) t.textPrimary else accent.copy(alpha = 0.85f)
            },
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
        )
    }
}

/** 0.5dp 分隔线 */
@Composable
fun IOSDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    val t = tokens()
    val glassOn = LocalGlassEffectsEnabled.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            // 玻璃卡片上的分隔线：用半透明白更贴合，实黑会显得突兀
            .background(
                if (glassOn) Color.White.copy(alpha = if (t.isDark) 0.08f else 0.34f)
                else t.separator,
            )
    )
}

/** 小圆点（时效性徽章前的 ●） */
@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** iOS 返回箭头 ‹ */
@Composable
fun ChevronLeft(color: Color = tokens().primary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 20.dp, height = 20.dp)) {
        val sw = 2.2.dp.toPx()
        val p = Path().apply {
            moveTo(size.width * 0.62f, sw)
            lineTo(size.width * 0.30f, size.height / 2f)
            lineTo(size.width * 0.62f, size.height - sw)
        }
        drawPath(p, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 图标按钮（导航栏用）：44dp 触控区 */
@Composable
fun IOSIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IOSTap(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        pressedScale = 0.9f,
        pressedOverlay = Color.Transparent,
    ) {
        Box(Modifier.align(Alignment.Center)) { content() }
    }
}

/**
 * 导航栏返回按钮：‹ + 文字，横向排列。
 * 不能用 [IOSIconButton]（它固定 44dp 见方，文字会被挤成竖排）。
 */
@Composable
fun IOSBackButton(
    onClick: () -> Unit,
    text: String = "返回",
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val t = tokens()
    IOSTap(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        pressedScale = 0.94f,
        pressedOverlay = Color.Transparent,
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ 必须把 color 也传给箭头。
            //
            // 这里原来是无参的 `ChevronLeft()`，于是箭头取默认值 `tokens().primary`(#3B6FD4)，
            // 而「返回」文字取传入的 `color`(阅读器传的是 readerPrimary #2C55A8) ——
            // 两者分别渲染成 4.37:1 和 6.48:1。加上箭头是 2.2dp 细描边，
            // 在阅读器米白纸底 (246,245,242) 上抗锯齿后实测对比度只剩 **1.34:1**
            // （QA 采样到最蓝像素 (207,214,227)），用户反馈「返回按钮那一栏透明度很高根本看不清」。
            // 修法：箭头与文字共用同一个 color。
            ChevronLeft(color = color ?: t.primary)
            Text(
                text = text,
                // 默认用主题主色；阅读器等浅灰纸感页可传入对比度更高的加深色
                color = color ?: t.primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
