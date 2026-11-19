package com.lawsearch.app.ui.search

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawsearch.app.data.SearchHit
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.components.CapsuleButton
import com.lawsearch.app.ui.components.IOSCard
import com.lawsearch.app.ui.components.IOSTap
import com.lawsearch.app.ui.components.MetaText
import com.lawsearch.app.ui.components.StatusBarScrim
import com.lawsearch.app.ui.components.tabBarBottomSpace
import com.lawsearch.app.ui.theme.tokens

/**
 * 检索页（iOS 搜索栏 + 结果卡片）。
 *
 * - 搜索框：高 42dp、圆角 12dp、背景 rgba(118,118,128,0.12)
 * - 输入后出现 ✕ 清除；空输入显示搜索历史
 * - 结果显示法规名 + [第 N 条] 徽标 + 摘要，点击进阅读器并跳转高亮
 */
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpen: (bbbs: String, anchor: String) -> Unit,
    /** 外部传入的初始查询（例如从其它 App 分享文本过来，自动化测试也用这个入口） */
    initialQuery: String = "",
) {
    val t = tokens()
    val state by vm.search.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(initialQuery) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { vm.loadSearchHistory() }
    // 带初始查询进入时立即执行一次搜索
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            vm.onQueryChange(initialQuery)
            vm.submitQuery(initialQuery)
        }
    }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                // 悬浮 TabBar + 两行文字的安全区
                bottom = tabBarBottomSpace() + 56.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "searchbar") {
                SearchField(
                    value = input,
                    onChange = { input = it; vm.onQueryChange(it) },
                    onSubmit = { vm.submitQuery(input) },
                    focusRequester = focusRequester,
                )
            }

            if (input.isBlank()) {
                if (state.history.isNotEmpty()) {
                    item(key = "history-title") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("搜索历史", color = t.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Text(
                                "清除",
                                color = t.primary,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.clearHistory() }
                                    .padding(4.dp),
                            )
                        }
                    }
                    item(key = "history-chips") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            state.history.forEach { q ->
                                CapsuleButton(text = q, onClick = {
                                    input = q
                                    vm.onQueryChange(q)
                                    vm.submitQuery(q)
                                })
                            }
                        }
                    }
                }
                item(key = "tips") {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", fontSize = 36.sp)
                        Text("搜索法规名或正文", color = t.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                        Text(
                            "试试「船舶优先权」「不可抗力」「正当防卫」",
                            color = t.textTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else {
                item(key = "result-head") {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                state.searching -> "搜索中…"
                                !state.searched -> ""
                                state.hits.isEmpty() -> "没有找到"
                                else -> "找到 ${state.hits.size} 条结果"
                            },
                            color = t.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.searched && !state.searching) {
                            MetaText("${state.elapsedMs} ms")
                        }
                    }
                }
                if (state.searched && !state.searching && state.hits.isEmpty()) {
                    item(key = "no-result") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 50.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("没有找到「$input」", color = t.textPrimary, fontSize = 16.sp)
                            Text("换个关键词试试", color = t.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                items(state.hits, key = { it.bbbs + "#" + it.anchor }) { hit ->
                    HitCard(hit) { onOpen(hit.bbbs, hit.anchor) }
                }
            }
        }

        // 搜索页：**只有状态栏保护，没有标题栏**。
        // 搜索框本身就是最强的视觉锚点；用户明确要求删掉导航栏整块。
        // 状态栏那一条必须保留：搜索结果会滚到状态栏下面，
        // 没有底色就会和系统的时间/电量叠在一起。
        StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    val t = tokens()
    val glassOn = com.lawsearch.app.ui.components.LocalGlassEffectsEnabled.current
    val barShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(barShape)
            .then(
                if (glassOn) {
                    // 玻璃搜索框：半透明白 + 顶部亮线 + 细白边
                    Modifier
                        .background(Color.White.copy(alpha = if (t.isDark) 0.16f else 0.52f))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (t.isDark) 0.05f else 0.28f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .border(
                            0.7.dp,
                            Color.White.copy(alpha = if (t.isDark) 0.14f else 0.55f),
                            barShape,
                        )
                } else {
                    Modifier.background(t.searchBarBackground)
                },
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "搜索",
            tint = t.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f).padding(start = 6.dp)) {
            if (value.isEmpty()) {
                Text("搜索法规名 / 条号 / 全文", color = t.textSecondary, fontSize = 15.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = t.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(t.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { },
            )
        }
        if (value.isNotEmpty()) {
            IOSTap(
                onClick = { onChange("") },
                modifier = Modifier.size(24.dp),
                pressedScale = 0.85f,
                pressedOverlay = androidx.compose.ui.graphics.Color.Transparent,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(t.textSecondary.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = t.card, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun HitCard(hit: SearchHit, onClick: () -> Unit) {
    val t = tokens()
    IOSCard {
        IOSTap(onClick = onClick, modifier = Modifier.fillMaxWidth(), pressedScale = 0.985f) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = hit.title,
                        color = t.textPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = hit.articleNo.ifEmpty { "标题" },
                        color = t.highlight,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(t.primary.copy(alpha = 0.10f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (hit.preview.isNotBlank()) {
                    HighlightedPreview(
                        text = hit.preview.replace("\n", " "),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** 摘要里的【】片段用主题强调色高亮 */
@Composable
private fun HighlightedPreview(text: String, modifier: Modifier = Modifier) {
    val t = tokens()
    val annotated = remember(text, t.highlight) {
        val parts = text.split('【', '】')
        buildAnnotatedString {
            parts.forEachIndexed { i, part ->
                if (i % 2 == 1) {
                    pushStyle(SpanStyle(color = t.highlight, fontWeight = FontWeight.SemiBold))
                    append(part)
                    pop()
                } else {
                    append(part)
                }
            }
        }
    }
    BasicText(
        text = annotated,
        style = TextStyle(color = t.textSecondary, fontSize = 13.sp, lineHeight = 20.sp),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}
