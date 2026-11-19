package com.lawsearch.app.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lawsearch.app.ui.theme.IOS
import com.lawsearch.app.ui.theme.IOSMotion
import com.lawsearch.app.ui.theme.tokens

/**
 * 悬浮胶囊 TabBar（规格书 5.3）。
 *
 * 关键还原点：
 * - 胶囊悬浮：左右各 24dp，距底部 24dp（再避让手势导航条），**不贴底**
 * - 磨砂：Android 12+ 真实模糊（Modifier.blur / RenderEffect），低版本半透明白色降级
 * - 选中态：独立蓝色胶囊色块，切换时用 iOS 弹簧曲线做缩放淡入
 * - 四个 Tab：首页 / 收藏 / 搜索 / 设置
 */
data class TabItem(val route: String, val icon: ImageVector, val label: String)

val AppTabs: List<TabItem> = listOf(
    TabItem("home", Icons.Rounded.Home, "首页"),
    // 原「法规」Tab 展示全部法规时一直显示 0 部、实际没用，改为「收藏」
    TabItem("bookmarks", Icons.Rounded.Star, "收藏"),
    TabItem("search", Icons.Rounded.Search, "搜索"),
    TabItem("settings", Icons.Rounded.Settings, "设置"),
)

/**
 * TabBar 占据的底部空间，页面用它给内容留白，避免最后一行被挡住。
 *
 * 需要覆盖：TabBar 高度（64）+ 距底边距（24）+ 系统导航条/手势区 + 缓冲。
 * 真机（Android 16 + 手势导航）实测：系统手势区约 66px，且 mandatorySystemGestures
 * 比 navigationBars 更大，只按 navigationBars 算会让最后一行贴着手势条。
 * 这里取两者较大值，再多留一段缓冲。
 */
@Composable
fun tabBarBottomSpace(): Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gestures = WindowInsets.mandatorySystemGestures.asPaddingValues().calculateBottomPadding()
    val safe = maxOf(nav, gestures)
    return IOS.tabBarHeight + IOS.tabBarBottomMargin + safe + 32.dp
}

@Composable
fun FloatingCapsuleTabBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    items: List<TabItem> = AppTabs,
    /** backdrop 库的背景层；为空时退回不透明表面 */
    glassBackdrop: com.kyant.backdrop.Backdrop? = null,
    /**
     * 当前滑动进度（Pager 的 currentPage + offsetFraction）。
     *
     * 注意：这里必须传**可观察的 State<Float>**而不是 lambda。
     * 早期用 lambda 时每次读取都在组合阶段求值，导致滑动时整棵 TabBar 重组、卡顿。
     * 用 State 后只有绘制层读取，滚动只触发重绘不触发重组。
     */
    scrollProgress: androidx.compose.runtime.State<Float>? = null,
) {
    val t = tokens()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 竖屏：左右各留 24dp 的悬浮胶囊；横屏/平板：限制最大宽度并居中，
    // 否则胶囊会被拉到满屏、四个图标散得太开。
    // 注意 modifier 顺序：先 widthIn 再 fillMaxWidth，否则 fillMaxWidth 会先占满再被忽略。
    val widthModifier = Modifier
        .widthIn(max = 520.dp)
        .fillMaxWidth()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = IOS.tabBarHorizontalMargin,
                end = IOS.tabBarHorizontalMargin,
                bottom = IOS.tabBarBottomMargin + navBottom,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(widthModifier) {
            // ---- 玻璃表面 ----
            val corner = RoundedCornerShape(32.dp)
            val glassTint = if (t.isDark) Color(0xFF232326) else Color(0xFFF8F9FB)
            val glassEnabled = rememberGlassEffects()

            // ---- 切 Tab 期间临时降级 ----
            //
            // 实测数据（perf-tester，K24 release）：
            //   场景 C TabBar 切换：p95 从 11-14ms（玻璃关）涨到 24-25ms（玻璃开），
            //   而 GPU 分位数几乎不变（4ms vs 2ms），Slow issue draw commands 涨 3-8 倍。
            //   → 瓶颈在「每帧重建 RuntimeShader 渲染节点 + 提交 draw command」的 CPU 开销，
            //     不是 shader 本身慢。
            //
            // 所以：**页面切换动画期间**（Pager 正在滑动、currentPage 未稳定）关掉折射，
            // 用静态玻璃兜底；动画结束再恢复。这样能把最贵的那几十帧的 draw command 省掉，
            // 而用户几乎察觉不到——因为这期间画面本来就在动。
            //
            // ⚠️ 原实现有一个真实缺陷，已修：
            //   1. `scrollProgress` 是 State，但在这里用 `scrollProgress.value` 是**普通读取**，
            //      发生在组合阶段 → 每帧滑动都会触发**整棵 TabBar 重组**。
            //      这本身就抵消了降级想省的开销（当时为了同样原因，高亮胶囊已经改用 State 了）。
            //      改用 derivedStateOf：只有布尔值真正翻转时才重组。
            //   2. `allowGlass` 初值是 false，而 `LaunchedEffect(pagerSettled)` 在
            //      **首次进入 App 且已停稳**时会先 delay(120) 再置 true —— 也就是启动后
            //      前 120ms TabBar 是「静态磨砂」而不是玻璃，首帧观感不对（会看到一次跳变）。
            //      改为：只有「原本就是停稳的」才带上延迟；从滑动状态恢复时才延迟。
            val pagerSettled by remember(scrollProgress) {
                androidx.compose.runtime.derivedStateOf {
                    val sp = scrollProgress ?: return@derivedStateOf true
                    val p = sp.value
                    kotlin.math.abs(p - Math.round(p)) < 0.02f
                }
            }
            // 停稳后延迟 120ms 再恢复玻璃，避免手指微抖导致来回切换
            var allowGlass by remember { mutableStateOf(false) }
            // 记录是否经历过「滑动 → 停稳」的转换，用于决定要不要延迟
            var wasMoving by remember { mutableStateOf(false) }
            LaunchedEffect(pagerSettled) {
                if (pagerSettled) {
                    if (wasMoving) kotlinx.coroutines.delay(120)
                    allowGlass = true
                    wasMoving = false
                } else {
                    allowGlass = false
                    wasMoving = true
                }
            }
            val effectiveGlass = glassEnabled && glassBackdrop != null && allowGlass

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IOS.tabBarHeight)
                    .shadow(
                        elevation = 10.dp,
                        shape = corner,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.16f),
                        spotColor = Color.Black.copy(alpha = 0.20f),
                    ),
            ) {
                if (effectiveGlass) {
                    // 液态玻璃：AGSL 边缘折射 + 轻微模糊 + 高透明
                    LiquidGlassBox(
                        backdrop = glassBackdrop,
                        modifier = Modifier.fillMaxWidth().height(IOS.tabBarHeight),
                        shapeRadius = 32.dp,
                        capsule = false,
                        tint = if (t.isDark) Color(0xFF232326).copy(alpha = 0.35f)
                        else Color.White.copy(alpha = 0.30f),
                        // 参数对齐官方 demo 的 LiquidBottomTabs：
                        //   blur(8.dp) + lens(24.dp, 24.dp)
                        // 见 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs.kt。
                        // 注意 blur() 的 radius 是标准差，不是高斯核半径。
                        blurRadius = 8.dp,
                        refraction = 24.dp,
                    )
                } else {
                    FrostedGlassSurface(
                        modifier = Modifier.fillMaxWidth().height(IOS.tabBarHeight),
                        tint = glassTint,
                        shapeRadius = 32.dp,
                        borderColor = t.tabBarBorder,
                    )
                }
            }
            // ---- 胶囊内容 ----
            // 高亮胶囊不放在每个 Tab 内部，而是单独画一层、按「滑动进度」定位，
            // 这样手指拖动页面时胶囊会跟着 1:1 移动（iOS 的行为），
            // 而不是等页面切换完才跳过去。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(IOS.tabBarHeight),
            ) {
                // 高亮层：iOS 26 的选中态不是纯色填充，而是**玻璃高光**
                // （SwiftUI: .glassEffect(.regular.interactive())）。
                // 这里用「半透明白 + 顶部亮线 + 细白边」模拟，跟随滑动连续插值。
                if (items.isNotEmpty()) {
                    // 用普通 Box + onSizeChanged 拿宽度。
                    // BoxWithConstraints 会把子约束改成「填满」，导致 .size() 被拉伸
                    // （实测高亮 48dp 被拉成 64dp，上下顶出底座）。
                    Box(Modifier.fillMaxWidth().height(IOS.tabBarHeight)) {
                        var containerWidth by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(0)
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(IOS.tabBarHeight)
                                .onSizeChanged { containerWidth = it.width },
                        )
                        if (containerWidth > 0) {
                            val cellWidth: androidx.compose.ui.unit.Dp = with(
                                androidx.compose.ui.platform.LocalDensity.current,
                            ) { (containerWidth / items.size).toDp() }
                            val highlightW: androidx.compose.ui.unit.Dp = cellWidth - 12.dp

                            val progress = scrollProgress
                            val pos = if (progress != null) progress.value else selectedIndex.toFloat()
                            val upper = items.size.toFloat() - 1f
                            val clamped = if (pos < 0f) 0f else if (pos > upper) upper else pos

                            Box(
                                Modifier
                                    .offset(
                                        x = cellWidth * clamped + (cellWidth - highlightW) / 2,
                                        y = (IOS.tabBarHeight - IOS.tabHighlightHeight) / 2,
                                    )
                                    // 用 requiredSize 而不是 size：
                                    // 父容器高度已固定为 tabBarHeight，size() 会被父约束拉伸
                                    // （实测 48dp 被拉成 64dp，上下顶出底座）。
                                    .requiredSize(width = highlightW, height = IOS.tabHighlightHeight)
                                    .clip(RoundedCornerShape(IOS.tabHighlightRadius))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = if (t.isDark) 0.42f else 0.98f),
                                                Color.White.copy(alpha = if (t.isDark) 0.30f else 0.86f),
                                            ),
                                        ),
                                    )
                                    .border(
                                        0.8.dp,
                                        Color.White.copy(alpha = if (t.isDark) 0.22f else 0.85f),
                                        RoundedCornerShape(IOS.tabHighlightRadius),
                                    ),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { index, item ->
                        CapsuleTab(
                            item = item,
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleTab(
    item: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = tokens()
    val spec = IOSMotion.indicator<Float>()
    Box(
        modifier = modifier.semantics {
            contentDescription = item.label
            this.selected = selected
        },
        contentAlignment = Alignment.Center,
    ) {
        IOSTap(
            onClick = onClick,
            pressedScale = 0.88f,
            pressedOverlay = Color.Transparent,
            // 填满整个格子，图标才能真正居中在高亮胶囊里
            // （先前固定 56dp，格子更宽时图标偏左）
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                // 高亮片是白色，图标用深色才看得清（iOS 的白玻璃片配深色图标）
                tint = if (selected) t.textPrimary else t.unselectedIcon,
                modifier = Modifier.size(24.dp).align(Alignment.Center),
            )
        }
    }
}

/**
 * 磨砂玻璃三明治结构（正确做法）。
 *
 * Compose 的 `Modifier.blur` 只能模糊**它之后绘制的内容**，直接把 blur 加在
 * 背景层上不会模糊「下方已有内容」。iOS 的 TabBar 效果需要：
 *   1. 在内容上再画一层「背景捕获层」（blur + 半透明 tint）——它模糊的是已经绘制的内容；
 *   2. 文字与图标层保持在捕获层之上，不被模糊。
 *
 * 本函数返回的 Modifier 用在**内容层**上（`Composables` 里的 Row），
 * 玻璃背景由 [FrostedGlassSurface] 单独绘制在下方。
 */
@Composable
fun Modifier.frostedGlass(
    tint: Color,
    blurRadius: Dp = 20.dp,
    shapeRadius: Dp = 32.dp,
): Modifier {
    val shape = RoundedCornerShape(shapeRadius)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+：真实模糊（RenderEffect via Modifier.blur），配合半透明 tint
        this
            .blur(blurRadius, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle)
            .clip(shape)
            .background(tint)
    } else {
        // API 26–30：没有 RenderEffect，用高不透明度的纯色保证文字可读
        this
            .clip(shape)
            .background(if (tint.alpha < 1f) tint.copy(alpha = 0.92f) else tint)
    }
}

/**
 * 玻璃「表面」：不透明 tint + 顶部 1dp 内发光 + 细边框。
 * 负责视觉质感；模糊由调用方用 [frostedGlass] 叠加在内容层上完成。
 */
@Composable
fun FrostedGlassSurface(
    modifier: Modifier = Modifier,
    tint: Color,
    shapeRadius: Dp = 32.dp,
    borderColor: Color = Color.White.copy(alpha = 0.5f),
) {
    val shape = RoundedCornerShape(shapeRadius)
    Box(
        modifier
            .clip(shape)
            .background(tint)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.45f), borderColor.copy(alpha = 0.12f)),
                ),
                shape = shape,
            )
    )
}

/** 顶部导航栏背景：滚动时为磨砂白，回到顶部时透明 */
@Composable
fun navBarBackground(alpha: Float, height: Dp): Modifier {
    val t = tokens()
    if (alpha <= 0.01f) return Modifier
    val base = if (t.isDark) Color(0xFF1C1C1E) else Color.White
    return Modifier
        .fillMaxWidth()
        .height(height)
        .background(base.copy(alpha = alpha * 0.92f))
}


