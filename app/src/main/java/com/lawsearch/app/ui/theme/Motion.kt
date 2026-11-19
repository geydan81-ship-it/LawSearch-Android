package com.lawsearch.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * iOS 动效曲线。规格书 5.8 明确要求：折叠/切换/高亮都必须用 spring，
 * 不能用 Material 的 tween 线性动画 —— 这是「像不像 iOS」的关键。
 */
object IOSMotion {
    /** Tab 切换 */
    fun <T> tab() = spring<T>(dampingRatio = 0.75f, stiffness = 380f)

    /** 页面 push / pop */
    fun <T> page() = spring<T>(dampingRatio = 0.85f, stiffness = 300f)

    /** 卡片点击缩放 */
    fun <T> tap() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)

    /** 折叠展开 */
    fun <T> fold() = spring<T>(dampingRatio = 0.8f, stiffness = 400f)

    /** 高亮指示器 */
    fun <T> indicator() = spring<T>(dampingRatio = 0.75f, stiffness = 380f)

    /** 轻微的弹动（徽章出现、sheet 弹出） */
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

    /** 大标题收缩（跟随滚动，阻尼默认） */
    fun <T> scroll() = spring<T>(dampingRatio = 1f, stiffness = Spring.StiffnessLow)
}
