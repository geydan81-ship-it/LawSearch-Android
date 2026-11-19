package com.lawsearch.app.ui.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawsearch.app.data.BookmarkRow
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.components.FloatingNavActions
import com.lawsearch.app.ui.components.IOSBackButton
import com.lawsearch.app.ui.components.IOSCard
import com.lawsearch.app.ui.components.IOSDivider
import com.lawsearch.app.ui.components.IOSTap
import com.lawsearch.app.ui.components.LargeTitleHeader
import com.lawsearch.app.ui.components.MetaText
import com.lawsearch.app.ui.components.StatusBarScrim
import com.lawsearch.app.ui.components.rememberCollapsingTitle
import com.lawsearch.app.ui.components.tabBarBottomSpace
import com.lawsearch.app.ui.theme.tokens
import com.lawsearch.app.util.RelativeTime

/**
 * 我的收藏。
 *
 * 支持两种粒度：
 *  - 整部法规（articleNo 为空）
 *  - 某一条文（articleNo = 第二十二条）
 * 点击直达；长按/右侧「移除」删除。
 */
@Composable
fun BookmarkScreen(
    vm: AppViewModel,
    onBack: (() -> Unit)? = null,
    onOpen: (bbbs: String, anchor: String) -> Unit,
) {
    val t = tokens()
    val state by vm.bookmarks.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 大标题仍需要折叠进度：滚下去时大标题淡出。
    // （导航栏已整块删除，不再有「滚动后出现小标题」那套逻辑。）
    val collapse = rememberCollapsingTitle(listState)

    LaunchedEffect(Unit) { vm.loadBookmarks() }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                // 底部要给悬浮 TabBar + 两行说明文字留足空间，
                // 否则最后一项会被 TabBar 盖住（QA 实测的「说明文字被裁」就是这个）。
                bottom = tabBarBottomSpace() + 56.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                LargeTitleHeader(
                    title = "我的收藏",
                    subtitle = if (state.items.isEmpty()) "还没有收藏" else "共 ${state.items.size} 项",
                )
            }

            if (state.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("载入中…", color = t.textSecondary, fontSize = 15.sp)
                    }
                }
            } else if (state.items.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("⭐", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("还没有收藏", color = t.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在阅读器里点右上角 ☆ 可以收藏整部法规或当前条文",
                            color = t.textTertiary,
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                items(state.items, key = { it.key }) { item ->
                    BookmarkCard(
                        item = item,
                        onClick = { onOpen(item.bbbs, item.anchor) },
                        onRemove = { vm.removeBookmark(item.key) },
                    )
                }
            }
        }

        // 只有状态栏保护 + 可选返回按钮，**没有标题栏整块**（用户要求删掉导航栏）。
        StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))
        if (onBack != null) {
            FloatingNavActions(
                modifier = Modifier.align(Alignment.TopCenter),
                left = { IOSBackButton(onClick = onBack) },
            )
        }
    }
}

@Composable
private fun BookmarkCard(item: BookmarkRow, onClick: () -> Unit, onRemove: () -> Unit) {
    val t = tokens()
    IOSCard {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            IOSTap(
                onClick = onClick,
                modifier = Modifier.weight(1f).fillMaxSize(),
                pressedScale = 0.99f,
            ) {
                Column(
                    Modifier.fillMaxWidth().align(Alignment.CenterStart).padding(start = 16.dp, end = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.articleNo.isNotBlank()) {
                            Text(
                                item.articleNo,
                                color = t.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = item.lawTitle,
                            color = t.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.excerpt.ifBlank { RelativeTime.format(item.createdAt) },
                        color = t.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IOSDividerVertical()
            IOSTap(
                onClick = onRemove,
                modifier = Modifier.height(72.dp),
                pressedScale = 0.9f,
            ) {
                Text(
                    "移除",
                    color = t.badgeRevoked,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun IOSDividerVertical() {
    Box(
        Modifier
            .width(0.5.dp)
            .height(28.dp)
            .background(tokens().separator),
    )
}

@Composable
private fun MetaRow(item: BookmarkRow) {
    if (item.validity.isNotBlank()) MetaText(item.validity)
}
