package com.lawsearch.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lawsearch.app.ui.theme.IOS
import com.lawsearch.app.ui.theme.tokens

/**
 * 大标题（Large Title）的滚动收缩。
 *
 * iOS 行为：内容在顶部时显示 34sp 大标题；向上滚动时大标题淡出。
 * 注意：**导航栏不再有小标题**，见 [FloatingNavActions] 的说明。
 */
@Composable
fun rememberCollapsingTitle(state: LazyListState): () -> Float {
    val offset by remember(state) {
        derivedStateOf {
            if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat() else 1000f
        }
    }
    return remember(offset) { { (offset / COLLAPSE_DISTANCE_PX).coerceIn(0f, 1f) } }
}

@Composable
fun rememberCollapsingTitle(state: ScrollState): () -> Float {
    val offset by remember(state) { derivedStateOf { state.value.toFloat() } }
    return remember(offset) { { (offset / COLLAPSE_DISTANCE_PX).coerceIn(0f, 1f) } }
}

/** 大标题完全收起所需的滚动距离（dp）。[rememberCollapsingTitle] 与 [LargeTitleHeader] 共用。 */
private const val COLLAPSE_DISTANCE_PX = 220f

/**
 * 大标题淡出速率：[LargeTitleHeader] 用 `1 - collapse * LARGE_TITLE_FADE_RATE`。
 * collapse ≥ 1/1.6 = **0.625** 时大标题彻底不可见。
 */
internal const val LARGE_TITLE_FADE_RATE = 1.6f

/** 状态栏保护层的不透明度（**固定值，不随滚动变化**）。 */
internal const val STATUS_BAR_SCRIM_ALPHA = 0.45f

/** 独立浮动按钮的直径（视觉），触控区仍是 44dp */
internal val FLOATING_ACTION_SIZE: Dp = 40.dp

/**
 * 状态栏保护层。
 *
 * ## 为什么需要
 *
 * `Theme.kt` 把 `window.statusBarColor` 设成透明并开启边到边显示，
 * 内容会一直卷到状态栏（时间、电量）底下。没有这一层，滚动时正文与系统图标会直接叠在一起。
 *
 * ## 为什么不再是一条「导航栏」
 *
 * 早先这里连同下方的 56dp 导航栏行一起上色，整块是一个近黑/近白的实心条 ——
 * 用户反馈「最上方一行导航栏是加了一块黑色滤镜，这个非加不可吗」「一大块黑色太丑了」。
 *
 * 现在：
 *  - **只保护状态栏那一窄条**（高度 = `WindowInsets.statusBars`），不再有 56dp 的行；
 *  - **透明度固定**为 [STATUS_BAR_SCRIM_ALPHA]，**不随滚动从 0 渐变到 1** ——
 *    原来那套渐变会让页面向下滚时「突然冒出一块深色」，观感最差。
 *
 * @param background 覆盖底色；默认浅色用白、深色用 #1C1C1E
 */
@Composable
fun StatusBarScrim(
    modifier: Modifier = Modifier,
    background: Color? = null,
) {
    val t = tokens()
    val base = background ?: if (t.isDark) Color(0xFF1C1C1E) else Color.White
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier
            .fillMaxWidth()
            .height(top)
            .background(base.copy(alpha = STATUS_BAR_SCRIM_ALPHA)),
    )
}

/**
 * 浮动导航按钮组（**不绘制任何背景条**）。
 *
 * ## 设计变更原因
 *
 * 用户明确要求：
 * > 我觉得还是把标题栏导航栏全部删掉，一大块黑色太丑了
 * > 只有宪法两个大字不可见时，这时才需要在导航栏显示宪法两个字
 * > （后改为）干脆不要这个标题栏了，就一个返回按钮单独绘制不行吗
 *
 * 所以最终形态是：**没有任何整条背景、也不显示小标题**，
 * 只有左上角的返回圆钮，以及右上角的操作圆钮（阅读器的 ☆ / ⋯）。
 *
 * 滚动后也不出现标题 —— 用户点名不要。
 *
 * @param left 左侧内容（通常是「‹ 返回」）
 * @param right 右侧内容（阅读器的 ☆ / ⋯）
 * @param topPadding 额外顶部内边距；调用方通常传状态栏高度
 */
@Composable
fun FloatingNavActions(
    modifier: Modifier = Modifier,
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = top)
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) {
            if (left != null) FloatingActionBacking { left() } else Spacer(Modifier.width(0.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
            if (right != null) FloatingActionBacking { right() } else Spacer(Modifier.width(0.dp))
        }
    }
}

/**
 * 浮动按钮的衬底：始终存在的半透明胶囊。
 *
 * QA 实测：阅读器里「返回」按钮是 7 个顶部按钮中**唯一没有背景 View** 的那个，
 * 因此它在米白纸底 (246,245,242) 上几乎消失（对比度 1.34:1）。
 * ☆ / ⋯ / 展开折叠 / A± 都有底，唯独返回没有。
 *
 * 现在所有浮动按钮统一加衬底，不再依赖页面底色。
 */
@Composable
fun FloatingActionBacking(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = tokens()
    Box(
        modifier
            .height(FLOATING_ACTION_SIZE)
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (t.isDark) Color(0xFF1C1C1E).copy(alpha = 0.55f)
                else Color.White.copy(alpha = 0.72f),
            )
            .border(
                0.7.dp,
                if (t.isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.75f),
                RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 大标题 + 副标题（首页/搜索页顶部） */
@Composable
fun LargeTitleHeader(
    title: String,
    subtitle: String? = null,
    collapse: Float = 0f,
    modifier: Modifier = Modifier,
    topPadding: Dp = 20.dp,
) {
    val t = tokens()
    val titleAlpha = (1f - collapse * LARGE_TITLE_FADE_RATE).coerceIn(0f, 1f)
    val fontSize = (IOS.largeTitle.value * (1f - 0.5f * collapse)).sp
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().padding(top = topPadding, bottom = 12.dp)) {
        Text(
            text = title,
            color = t.textPrimary.copy(alpha = titleAlpha),
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = t.textSecondary.copy(alpha = (1f - collapse * 1.4f).coerceIn(0f, 1f)),
                fontSize = IOS.subtitle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
