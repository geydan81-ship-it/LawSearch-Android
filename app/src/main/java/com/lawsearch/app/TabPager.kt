package com.lawsearch.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.components.FloatingCapsuleTabBar
import com.lawsearch.app.ui.components.LocalGlassEffectsEnabled
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/**
 * 四个 Tab 的左右滑动容器。
 *
 * ## 为什么用 Pager 而不是 NavHost 承载 Tab
 *
 * NavHost 的转场是做「整页替换」，无法跟随手指连续跟手；iOS 的 Tab 切换必须
 * 让页面**跟着手指1:1 水平移动**，只有 `HorizontalPager` 能做到。
 *
 * ## 与二级页面的关系
 *
 * 结构是两层：
 *   - 本 [TabPager] 只在「Tab 层」存在（首页/收藏/搜索/设置）
 *   - 列表、阅读器等 push 页面仍走 NavHost，盖在 Pager 之上
 * 这样 Tab 之间滑动、Tab 内 push 返回，两种交互互不干扰。
 */
@Composable
fun TabPager(
    vm: AppViewModel,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    onOpenLaw: (bbbs: String, anchor: String) -> Unit,
    onOpenList: (group: String) -> Unit = {},
    onOpenLatest: () -> Unit = {},
    showTabBar: Boolean = true,
    initialQuery: String = "",
    onTabSelect: (Int) -> Unit = {},
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { 4 }
    val scope = rememberCoroutineScope()
    val glassLayer = rememberLayerBackdrop()

    // 外部 requested page 变化时同步（例如从二级页返回后点 TabBar）
    LaunchedEffect(initialPage) {
        if (initialPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(initialPage)
        }
    }

    // 页面变化 → 通知外部（用于高亮 TabBar）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onPageChanged(it) }
    }

    Box(Modifier.fillMaxSize()) {
        // 内容层：用一个稳定的 Box 包住 Pager 作为 Haze 背景源。
        // ⚠️ hazeSource 不能直接挂在 HorizontalPager 上 —— Pager 翻页会反复重建/回收
        // 页面图层，导致新页面的内容没被登记进背景源，玻璃就退化成实心底。
        // 包一层外层 Box，Pager 的变化都在这个 Box 内部完成，背景源始终有效。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(glassLayer),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
            ) { page ->
            when (page) {
                TabPages.HOME -> com.lawsearch.app.ui.home.HomeScreen(
                    vm = vm,
                    onCategoryClick = onOpenList,
                    onLawClick = onOpenLaw,
                    onSeeAllLaws = { onTabSelect(TabPages.BOOKMARKS) },
                    onSeeLatest = onOpenLatest,
                    onOpenBookmarks = { onTabSelect(TabPages.BOOKMARKS) },
                )
                TabPages.BOOKMARKS -> com.lawsearch.app.ui.bookmark.BookmarkScreen(
                    vm = vm,
                    onBack = null,
                    onOpen = onOpenLaw,
                )
                TabPages.SEARCH -> com.lawsearch.app.ui.search.SearchScreen(
                    vm = vm,
                    onOpen = onOpenLaw,
                    initialQuery = initialQuery,
                )
                TabPages.SETTINGS -> com.lawsearch.app.ui.settings.SettingsScreen(vm = vm)
            }
            }
        }  // end hazeSource Box

        if (showTabBar) {
            FloatingCapsuleTabBar(
                selectedIndex = pagerState.currentPage,
                onSelect = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                glassBackdrop = glassLayer,
                // 让高亮胶囊跟随滑动进度：手指拖到一半时，两个 Tab 之间做插值
                scrollProgress = rememberScrollProgress(pagerState),
            )
        }
    }
}

/**
 * 把 Pager 的滚动位置包装成 State<Float>。
 *
 * 为什么需要：如果直接把 lambda 传给 TabBar，Compose 会在**组合阶段**求值，
 * 每一帧滚动都触发整棵 TabBar 重组 → 卡顿。
 * 用 derivedStateOf 包成 State，只有真正读取的绘制层会感知变化。
 */
@Composable
private fun rememberScrollProgress(
    pagerState: androidx.compose.foundation.pager.PagerState,
): androidx.compose.runtime.State<Float> =
    androidx.compose.runtime.remember(pagerState) {
        androidx.compose.runtime.derivedStateOf {
            pagerState.currentPage + pagerState.currentPageOffsetFraction
        }
    }

object TabPages {
    const val HOME = 0
    const val BOOKMARKS = 1
    const val SEARCH = 2
    const val SETTINGS = 3
    val routes = listOf("home", "bookmarks", "search", "settings")

    fun indexOfRoute(route: String): Int = routes.indexOf(route).coerceAtLeast(0)
}
