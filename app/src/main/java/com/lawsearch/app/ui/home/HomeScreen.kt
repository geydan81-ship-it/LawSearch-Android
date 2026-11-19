package com.lawsearch.app.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawsearch.app.data.GroupCount
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.components.Dot
import com.lawsearch.app.ui.components.IOSCard
import com.lawsearch.app.ui.components.IOSDivider
import com.lawsearch.app.ui.components.IOSTap
import com.lawsearch.app.ui.components.LargeTitleHeader
import com.lawsearch.app.ui.components.MetaText
import com.lawsearch.app.ui.components.RcBanner
import com.lawsearch.app.ui.components.ValidityBadge
import com.lawsearch.app.ui.components.rememberCollapsingTitle
import com.lawsearch.app.ui.components.tabBarBottomSpace
import com.lawsearch.app.ui.theme.IOS
import com.lawsearch.app.ui.theme.tokens
import com.lawsearch.app.util.RelativeTime

/** 首页分类卡片顺序与图标（规格书 5.4） */
private val CATEGORY_ICONS = mapOf(
    "宪法" to "🏛️",
    "法律" to "⚖️",
    "行政法规" to "📋",
    "监察法规" to "🔎",
    "司法解释" to "📜",
    CARD_LATEST to "📖",
)

/**
 * 首页：大标题 + 分类网格（2 列）+ 最近浏览 + 最近更新。
 * 底部是悬浮胶囊 TabBar（由 AppRoot 画在最上层）。
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onCategoryClick: (String) -> Unit,
    onLawClick: (bbbs: String, anchor: String) -> Unit,
    onSeeAllLaws: () -> Unit,
    onSeeLatest: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
) {
    val t = tokens()
    val state by vm.home.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val collapse = rememberCollapsingTitle(listState)

    LaunchedEffect(Unit) { vm.refreshHome() }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                // 底部给悬浮 TabBar + 两行文字留出空间
                bottom = tabBarBottomSpace() + 56.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LargeTitleHeader(
                    title = "法律法规数据库",
                    subtitle = when {
                        state.total > 0 -> "共 ${state.total} 部规范性文件"
                        else -> "首次启动正在导入法律库，约需 1–2 分钟，请耐心等待…"
                    },
                    collapse = collapse(),
                )
            }

            // ---- 测试版横幅（正式版不渲染）----
            item(key = "rc-banner") { RcBanner() }

            // ---- 首次导入进度（导入完成后自动消失）----
            if (state.total == 0) {
                item(key = "import-progress") { ImportProgressCard() }
            }

            // ---- 分类网格（2 列）----
            val categories = categoryCards(state.groups)
            categories.chunked(2).forEach { pair ->
                item(key = "row-" + pair.joinToString { it.name }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEachIndexed { index, card ->
                            CategoryCard(
                                name = card.name,
                                count = card.count,
                                icon = CATEGORY_ICONS[card.name] ?: "📖",
                                accent = t.accents[(categories.indexOf(card)) % t.accents.size],
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (card.name == CARD_LATEST) onSeeLatest()
                                    else onCategoryClick(card.name)
                                },
                            )
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }

            // ---- 最近浏览 ----
            if (state.recent.isNotEmpty()) {
                item(key = "recent-title") {
                    SectionTitle("最近浏览", Modifier.padding(top = 10.dp))
                }
                item(key = "recent-card") {
                    IOSCard {
                        Column {
                            state.recent.forEachIndexed { index, recent ->
                                IOSTap(
                                    onClick = { onLawClick(recent.meta.bbbs, "") },
                                    modifier = Modifier.fillMaxWidth(),
                                    pressedScale = 0.99f,
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = recent.meta.title,
                                            color = t.textPrimary,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            // 长标题（「中华人民共和国中国人民解放军军官军衔条例」）
                                            // 必须补省略号，否则文字被硬切成半截
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        MetaText(RelativeTime.format(recent.viewedAt), Modifier.padding(start = 10.dp))
                                    }
                                }
                                if (index != state.recent.lastIndex) IOSDivider(startIndent = 16.dp)
                            }
                        }
                    }
                }
            }

            // 注：原先这里还有一个「最近更新」列表区块。
            // 首页已有「最近更新」卡片，内容重复，故移除；该列表仍可通过卡片进入。
        }

        // 首页不绘制导航栏。
        //
        // 首页的大标题「法律法规数据库」本身就是页面身份，导航栏再显示一遍就是重复 ——
        // 用户反馈「标题 法律法规数据库 还跟首页法律法规数据库几个大字重叠」。
        // 首页既没有返回按钮也没有 actions，删掉整条导航栏后屏幕顶部只剩大标题。
    }
}

private data class CategoryCardModel(val name: String, val count: Int)

/** 「最近更新」卡片的固定名称（不是真实分类） */
const val CARD_LATEST = "最近更新"

/** 规格书顺序：宪法/法律/行政法规/监察法规/司法解释 + 「最近更新」卡片 */
private fun categoryCards(groups: List<GroupCount>): List<CategoryCardModel> {
    val order = listOf("宪法", "法律", "行政法规", "监察法规", "司法解释")
    val byName = groups.associateBy { it.group }
    val cards = order.mapNotNull { name ->
        byName[name]?.let { CategoryCardModel(it.group, it.count) }
    }
    // 接口可能返回其他一级分类，一并展示
    val extra = groups.filter { it.group !in order }.map { CategoryCardModel(it.group, it.count) }
    return cards + extra + CategoryCardModel(CARD_LATEST, 0)
}

@Composable
private fun CategoryCard(
    name: String,
    count: Int,
    icon: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = tokens()
    // ⚠️ 必须是 heightIn(min=) 而不是 height()。
    //
    // 卡片内容（16dp 上 padding + 24sp 图标 ≈28.8 + 6 间距 + 17sp 标题 ≈20.4
    // + 2 间距 + 13sp「N 部」≈15.6 + 16dp 下 padding）合计 **≈104.8dp**，
    // 实测 107.7dp，固定 108dp 时余量只剩 0.3dp。用户开启系统大字体后
    // 17sp→19.5sp、13sp→15sp，合计 **≈115dp**，直接超出 → 最后一行「N 部」被裁掉。
    // 这正是用户反馈的「高度稍微小了一点导致卡片的字被截断」。
    IOSCard(modifier = modifier.heightIn(min = 108.dp)) {
        IOSTap(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 108.dp),
            pressedScale = 0.94f,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(108.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = if (t.isDark) 0.20f else 0.10f), Color.Transparent)
                        )
                    )
            )
            Column(Modifier.padding(16.dp)) {
                Text(text = icon, fontSize = 24.sp)
                Box(Modifier.height(6.dp))
                Text(
                    text = name,
                    color = t.textPrimary,
                    fontSize = IOS.cardTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (name != CARD_LATEST) {
                    Text(
                        text = "$count 部",
                        color = t.textSecondary,
                        fontSize = IOS.cardMeta,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = tokens().textPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
private fun RecentLawRow(
    title: String,
    gbrq: String,
    articleCount: Int,
    validity: String,
    onClick: () -> Unit,
) {
    val t = tokens()
    IOSCard {
        IOSTap(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            pressedScale = 0.99f,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = t.textPrimary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ValidityBadge(validity)
                        if (articleCount > 0) MetaText("  $articleCount 条", Modifier.padding(start = 8.dp))
                        if (gbrq.isNotEmpty()) MetaText(gbrq, Modifier.padding(start = 10.dp))
                    }
                }
                Text("›", color = t.textTertiary, fontSize = 20.sp)
            }
        }
    }
}

/**
 * 首次导入进度卡片。
 *
 * 766 部法规首次导入约 1–2 分钟（解压 gzip + 写入 Room + 建 4 万多条 FTS 索引），
 * 期间首页原本只有空白，用户会以为卡死。这里显示百分比和当前法规名，
 * 并明确提示"耐心等待、只需一次"。
 */
@Composable
private fun ImportProgressCard() {
    val t = tokens()
    val progress by com.lawsearch.app.data.ImportProgress.state.collectAsState()

    IOSCard {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                "正在准备法律库",
                color = t.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "首次启动需要解压并建立全文索引，约需 1–2 分钟。只需一次，之后秒开。",
                color = t.textSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(14.dp))

            // 进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (t.isDark) Color(0xFF3A3A3E) else Color(0xFFE5E5EA)),
            ) {
                val ratio = (progress.percent.coerceIn(0, 100)) / 100f
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(t.primary),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        progress.indexing -> "建立索引 ${progress.indexed}/${progress.indexTotal}"
                        progress.total > 0 -> "导入 ${progress.current}/${progress.total}"
                        else -> "正在准备…"
                    },
                    color = t.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${progress.percent}%",
                    color = t.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (progress.currentTitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    progress.currentTitle,
                    color = t.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
