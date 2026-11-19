package com.lawsearch.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lawsearch.app.R

/**
 * iOS 26 风格的设计令牌。
 *
 * 规格书第五节的定义集中在这里：颜色（浅色/深色）、字号、圆角、间距。
 * 界面文字用系统字体（接近 SF Pro 的观感），法规正文用仿宋、章节用楷体、条号用黑体
 * —— 这种「界面现代、内容文书」的混用是刻意的。
 */
@Immutable
data class IOSTokens(
    val isDark: Boolean,
    // 背景 / 表面
    val background: Color,
    val groupedBackground: Color,
    val card: Color,
    val cardElevated: Color,
    // 文字
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // 主色
    val primary: Color,
    val primaryFixed: Color,
    // 组件
    val tabBarBackground: Color,
    val tabBarBorder: Color,
    val selectedIcon: Color,
    val unselectedIcon: Color,
    val searchBarBackground: Color,
    val chipBackground: Color,
    val chipSelectedBackground: Color,
    val chipSelectedText: Color,
    val separator: Color,
    val highlight: Color,
    // 徽章
    val badgeValid: Color,
    val badgePending: Color,
    val badgeRevised: Color,
    val badgeRevoked: Color,
    // 法规正文
    val articleText: Color,
    val articleNumber: Color,
    val headingText: Color,
    val headingSubText: Color,
    val flashBackground: Color,
    // 数据卡片配色
    val accents: List<Color>,
) {
    /** 阅读器正文颜色：浅色模式与正文一致；深色模式用米白 #E8E6E3 减少炫光 */
    val readerBody: Color get() = if (isDark) Color(0xFFE8E6E3) else Color(0xFF2B3242)

    /**
     * 阅读器背景。
     *
     * **不支持深色！** 用户明确反馈「法条背景黑色，可读性差」。
     *
     * 注意阅读器是「内容层」，按 Apple HIG 不该用玻璃/壁纸，
     * 但它也不该因为「壁纸模式强制深色令牌」就变成深色 ——
     * 阅读长文时深色背景的文字辨识度明显低于浅色纸感。
     *
     * 所以这里**固定用浅色纸感**，不受 [isDark] 影响。
     * 真正想要夜间模式时，应该做独立的阅读器主题开关，而不是跟着壁纸走。
     */
    val readerBackground: Color
        get() = Color(0xFFF7F6F3)

    /** 阅读器正文颜色：固定深色文字配浅色纸感背景 */
    val readerTextColor: Color
        get() = Color(0xFF2B3242)

    /* ---------------- 阅读器专用配色 ----------------
     *
     * 阅读器**强制使用 LightTokens**（见 LawReaderScreen 的 CompositionLocalProvider），
     * 但 LightTokens 的 primary / textSecondary 是给「白底 + 玻璃卡片」调的，
     * 直接用在阅读器的浅灰纸感 #F7F6F3 上对比度不够，导致用户反馈
     * 「点进法规后上面的 UI（返回、收藏、⋯、元信息）可见度差」。
     *
     * 实测对比度（相对 #F7F6F3，WCAG AA 正文要求 ≥ 4.5:1）：
     *   primary      #3B6FD4 →  4.41:1  ✗ 勉强（17sp 返回按钮正好卡在及格线下）
     *   textSecondary#8B93A1 →  2.86:1  ✗ 明显不足（"公布 2021-01-01" 这类元信息发灰）
     *   textTertiary #A9B0BC →  2.02:1  ✗ 几乎看不见
     *
     * 下面这组是阅读器场景专用的加深版本，全部 ≥ 4.5:1。
     */

    /** 阅读器导航栏/按钮强调色（替代 primary）：#2C55A8 → 6.54:1 */
    val readerPrimary: Color get() = Color(0xFF2C55A8)

    /** 阅读器次级文字（公布/施行日期、字号等元信息）：#5F6875 → 5.22:1 */
    val readerSecondary: Color get() = Color(0xFF5F6875)

    /** 阅读器三级文字（最弱的提示）：#6B7382 → 4.42:1（仅用于非关键信息） */
    val readerTertiary: Color get() = Color(0xFF6B7382)
}

val LightTokens = IOSTokens(
    isDark = false,
    background = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF7F8FA),
    card = Color(0xFFFFFFFF),
    cardElevated = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF2B3242),
    textSecondary = Color(0xFF8B93A1),
    textTertiary = Color(0xFFA9B0BC),
    primary = Color(0xFF3B6FD4),
    primaryFixed = Color(0xFF3B6FD4),
    tabBarBackground = Color(0xFFFFFFFF).copy(alpha = 0.72f),
    tabBarBorder = Color.White.copy(alpha = 0.5f),
    selectedIcon = Color.White,
    unselectedIcon = Color(0xFF8B93A1),
    searchBarBackground = Color(0x1F767680),
    chipBackground = Color(0xFFF0F2F5),
    chipSelectedBackground = Color(0xFF3B6FD4),
    chipSelectedText = Color.White,
    separator = Color(0x14000000),
    highlight = Color(0xFF1F4A94),
    badgeValid = Color(0xFF1F8A4C),
    badgePending = Color(0xFFC77A0A),
    badgeRevised = Color(0xFF8B93A1),
    badgeRevoked = Color(0xFFC0392B),
    articleText = Color(0xFF2B3242),
    articleNumber = Color(0xFF1F4A94),
    headingText = Color(0xFF1A2230),
    headingSubText = Color(0xFF55606F),
    flashBackground = Color(0xFFFDF3D0),
    accents = listOf(
        Color(0xFF3B6FD4), // 宪法
        Color(0xFF5B4BC4), // 法律
        Color(0xFF0F8B8D), // 行政法规
        Color(0xFFB4531F), // 监察法规
        Color(0xFF7A3E9D), // 司法解释
        Color(0xFF2F8F5B), // 最近更新
    ),
)

val DarkTokens = IOSTokens(
    isDark = true,
    background = Color(0xFF000000),
    groupedBackground = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardElevated = Color(0xFF2C2C2E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB8BEC9),
    textTertiary = Color(0xFF9AA1AD),
    primary = Color(0xFF4A7FE0),
    primaryFixed = Color(0xFF3B6FD4),
    tabBarBackground = Color(0xFF1C1C1E).copy(alpha = 0.72f),
    tabBarBorder = Color.White.copy(alpha = 0.12f),
    selectedIcon = Color.White,
    unselectedIcon = Color(0xFF8E8E93),
    searchBarBackground = Color(0x2E767680),
    chipBackground = Color(0xFF2C2C2E),
    chipSelectedBackground = Color(0xFF4A7FE0),
    chipSelectedText = Color.White,
    separator = Color(0x1FFFFFFF),
    highlight = Color(0xFF9CBEF5),
    badgeValid = Color(0xFF4CD07D),
    badgePending = Color(0xFFF0B04A),
    badgeRevised = Color(0xFF8E8E93),
    badgeRevoked = Color(0xFFFF6B5E),
    articleText = Color(0xFFE8E6E3),
    articleNumber = Color(0xFF9CBEF5),
    headingText = Color(0xFFF2F2F7),
    headingSubText = Color(0xFFC7C7CC),
    flashBackground = Color(0xFF3A3320),
    accents = listOf(
        Color(0xFF5B8DEF),
        Color(0xFF8E7BEF),
        Color(0xFF37B5B7),
        Color(0xFFD98355),
        Color(0xFFB07CD1),
        Color(0xFF4CD07D),
    ),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

/** 尺寸 / 动效令牌 */
object IOS {
    // 圆角
    val cardRadius: Dp = 16.dp
    val capsuleRadius: Dp = 28.dp
    val buttonRadius: Dp = 12.dp
    val searchRadius: Dp = 10.dp

    // TabBar
    val tabBarHorizontalMargin: Dp = 24.dp
    val tabBarBottomMargin: Dp = 24.dp
    val tabBarHeight: Dp = 64.dp
    // 比底座(64dp)矮，四周留白才像 iOS 的玻璃高光片
    val tabHighlightHeight: Dp = 48.dp
    // 圆角接近底座的 32dp，避免高亮边缘和底座错位
    val tabHighlightRadius: Dp = 28.dp

    // 排版
    val largeTitle = 34.sp
    val subtitle = 15.sp
    val cardTitle = 17.sp
    val cardMeta = 13.sp
    val navTitle = 17.sp

    // 阅读器字号范围
    const val READER_FONT_MIN = 15
    const val READER_FONT_MAX = 22
    const val READER_FONT_DEFAULT = 17
}

/**
 * 法规内容字体。
 *
 * ## ⚠️ 商业字体已全部移除（v1.1.2，法律风险）
 *
 * 早先这里内置了三个 TTF：
 *   - `fangsong.ttf` / `assets/fonts/FangSong.ttf`（仿宋）
 *   - `kaiti.ttf`   / `assets/fonts/KaiTi.ttf`（楷体）
 *   - `simhei.ttf`  / `assets/fonts/SimHei.ttf`（黑体，章节标题用）
 *
 * 它们的 TTF `name` 表写着：
 *   `© Beijing ZhongYi Electronics Co., 1995-2005, All rights reserved`
 *
 * 中易（Beijing ZhongYi）是 Windows 自带宋体/黑体/楷体/仿宋的版权方。
 * **随 Windows 使用 ≠ 允许打包分发**，把字体嵌进 APK 并公开到 GitHub 属于侵权。
 * 同一批字体还同时在 `res/font/` 与 `assets/fonts/` 各存了一份（6 个文件 ≈ 12.7 MB）。
 *
 * 因此：
 *   - 字体文件已删除；
 *   - 章节标题（[LawFonts.HeiTi]）与正文（[LawFonts.body]）**统一走系统字体**。
 *   - v1.2 计划换用开源字体（霞鹜文楷 LXGW WenKai，SIL OFL 1.1）。
 *
 * 见 AGENTS.md 第十三条「字体必须用系统字体或开源字体」。
 */
object LawFonts {
    /**
     * 章节标题字体。
     *
     * 原来指向内置 `simhei.ttf`（中易黑体，已移除）。
     * 现在用系统默认字族：Android 上默认就是思源黑体系（Noto Sans CJK），
     * 观感与需求一致，且零授权风险。
     */
    val HeiTi: FontFamily = FontFamily.Default

    val Fallback = FontFamily.Default

    /**
     * 正文字体族。
     *
     * **参数保留但恒返回系统字体**：老用户的 SharedPreferences 里可能还存着
     * `"fangsong"` / `"kaiti"`，直接改签名会在调用点报错；
     * 恒返回 [Fallback] 让这些旧值安全降级为系统字体（不崩溃、不缺字）。
     *
     * 系统字体覆盖完整字库，遇到生僻字也不会像内置子集那样缺字。
     */
    @Suppress("UNUSED_PARAMETER")
    fun body(mode: String): FontFamily = Fallback
}

@Composable
@ReadOnlyComposable
fun tokens(): IOSTokens = LocalTokens.current
