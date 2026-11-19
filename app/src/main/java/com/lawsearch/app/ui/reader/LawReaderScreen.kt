package com.lawsearch.app.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawsearch.app.data.Block
import com.lawsearch.app.data.LawJson
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.components.CapsuleButton
import com.lawsearch.app.ui.components.ChevronLeft
import com.lawsearch.app.ui.components.FloatingActionBacking
import com.lawsearch.app.ui.components.FloatingNavActions
import com.lawsearch.app.ui.components.IOSBackButton
import com.lawsearch.app.ui.components.IOSIconButton
import com.lawsearch.app.ui.components.IOSTap
import com.lawsearch.app.ui.components.MetaText
import com.lawsearch.app.ui.components.StatusBarScrim
import com.lawsearch.app.ui.components.ValidityBadge
import com.lawsearch.app.ui.theme.IOS
import com.lawsearch.app.ui.theme.IOSMotion
import com.lawsearch.app.ui.theme.LawFonts
import com.lawsearch.app.ui.theme.tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 阅读器（全屏 push 页，隐藏 TabBar）。
 *
 * 规格书 5.6 与验收标准：
 *  - 章标题黑体、节标题黑体左缩进、条号黑体蓝色、正文仿宋首行缩进两字
 *  - 「（一）」类分项不缩进、左侧留 2 字
 *  - 折叠：默认展开到章级；全部折叠后可见条文 0
 *  - 从搜索结果进入时按锚点定位、自动展开祖先章节并高亮
 */
@Composable
fun LawReaderScreen(
    vm: AppViewModel,
    bbbs: String,
    initialAnchor: String,
    onBack: () -> Unit,
) {
    // 注意：这里**不能**读 tokens()。
    // 阅读器整体强制浅色令牌（见 ReaderContent 的 CompositionLocalProvider），
    // 而这一层的背景是 loading/error 兜底用的。原先写的是 t.background，
    // 壁纸模式下全局令牌是 DarkTokens（background = 纯黑 #000000），
    // 于是「加载中…」「无法打开这部法规」这两屏会在黑底上显示深灰文字 = 一片黑。
    // 直接用阅读器固定的纸感色，与 ReaderContent 完全一致。
    val state by vm.reader.collectAsStateWithLifecycle()

    LaunchedEffect(bbbs) { vm.openLaw(bbbs, initialAnchor) }

    Box(Modifier.fillMaxSize().background(com.lawsearch.app.ui.theme.LightTokens.readerBackground)) {
        val law = state.law
        when {
            state.loading -> LoadingView()
            law == null -> ErrorView(state.error ?: "无法打开这部法规", onBack)
            else -> ReaderContent(law = law, initialAnchor = initialAnchor, vm = vm, onBack = onBack)
        }
    }
}

@Composable
private fun LoadingView() {
    // 阅读器的 loading/error 兜底也走浅色纸感，不能用全局 tokens()：
    // 壁纸模式下全局是 DarkTokens，textSecondary 是 #B8BEC9，配纯黑底尚可，
    // 但这里背景已是浅色纸感，用深色令牌的文字会几乎看不见。
    val t = com.lawsearch.app.ui.theme.LightTokens
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("加载中…", color = t.readerSecondary, fontSize = 15.sp)
    }
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    val t = com.lawsearch.app.ui.theme.LightTokens
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠️", fontSize = 40.sp)
        Text(message, color = t.readerTextColor, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        CapsuleButton(text = "返回", onClick = onBack, height = 40.dp, accentColor = t.readerPrimary)
    }
}

@Composable
private fun ReaderContent(
    law: LawJson,
    initialAnchor: String,
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    // ⚠️ 这里**不能**读 tokens()。
    //
    // ReaderContent 的全部 UI 都包在 CompositionLocalProvider(LightTokens) 里
    // （见下方 ~268 行的 Provider）。
    // 如果在这一层（Provider 之外）取 tokens()，
    // 壁纸模式下拿到的是全局 DarkTokens，而界面实际渲染用的是 LightTokens ——
    // 一旦有人用 t.textPrimary 之类依赖 isDark 的颜色，就会出现
    // 「#FFFFFF 白字叠在浅色纸感底上」这种白字白底。
    // 阅读器专用的 t 在 Provider 内部重新获取。
    val context = LocalContext.current
    val tree = remember(law.bbbs) { ReaderTree.build(law) }

    // 折叠状态：默认展开到章级（条文与节折叠）
    var collapsed by remember(law.bbbs) { mutableStateOf(tree.defaultCollapsed()) }
    var flashAnchor by rememberSaveable(law.bbbs) { mutableStateOf(initialAnchor) }

    val visible = remember(tree, collapsed) { tree.visible(collapsed) }
    val listState = rememberLazyListState()
    val readerState by vm.reader.collectAsStateWithLifecycle()
    val fontSize = readerState.fontSize.coerceIn(IOS.READER_FONT_MIN, IOS.READER_FONT_MAX)
    val fontMode = readerState.fontMode
    val bookmarkedKeys by vm.bookmarkedKeys.collectAsStateWithLifecycle()

    LaunchedEffect(law.bbbs) { vm.loadBookmarkKeys(law.bbbs) }

    // ---- 条文跳转（输入阿拉伯数字）----
    val scope = rememberCoroutineScope()
    var gotoOpen by rememberSaveable(law.bbbs) { mutableStateOf(false) }
    var gotoText by rememberSaveable(law.bbbs) { mutableStateOf("") }
    var gotoError by rememberSaveable(law.bbbs) { mutableStateOf("") }

    /** 按条号跳转：展开祖先 → 滚动到该条 → 闪烁高亮 */
    fun jumpToArticleNo(raw: String) {
        val n = raw.trim().toIntOrNull()
        if (n == null || n <= 0) {
            gotoError = "请输入条号，例如 22"
            return
        }
        val target = tree.articleByNo(n)
        if (target == null) {
            val range = law.articleRange
            val hint = if (range.size == 2 && range[1] > 0) "（本篇共 1–${range[1]} 条）" else ""
            gotoError = "没找到第 $n 条$hint"
            return
        }
        val id = target.block.id
        gotoError = ""
        collapsed = tree.expandTo(id, collapsed)
        scope.launch {
            delay(30) // 等一帧，列表按新的可见集重建
            val list = tree.visible(collapsed)
            // 用 key 查找：block.id 在同一部法规里可能重复（各分编下都有 s1），
            // 用 id 会跳到错误的位置
            val targetKey = tree.nodeKeyOf(id)
            val index = list.indexOfFirst { it.key == targetKey }
            if (index >= 0) {
                // 让目标条文不要紧贴导航栏：滚到它**前面**的那一项，
                // 这样屏幕顶部还能看到所属章节，上下文更清楚。
                // （负 scrollOffset 在靠近顶部时会被截断，用前一项更可靠。）
                val anchorIndex = (index - 1).coerceAtLeast(0)
                listState.animateScrollToItem(anchorIndex)
                flashAnchor = id
                vm.rememberAnchor(id)
            }
        }
        gotoOpen = false
        gotoText = ""
    }

    // ---- 锚点跳转：展开祖先 → 滚动 → 高亮 ----
    LaunchedEffect(initialAnchor) {
        if (initialAnchor.isBlank()) return@LaunchedEffect
        collapsed = tree.expandTo(initialAnchor, collapsed)
        delay(30) // 等一帧，列表按新的可见集重建
        val anchorKey = tree.nodeKeyOf(initialAnchor)
        val index = tree.visible(collapsed).indexOfFirst { it.key == anchorKey }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            vm.rememberAnchor(initialAnchor)
        }
    }

    // ---- 阅读位置记忆：没有指定锚点时，恢复到上次读到的条文 ----
    var restored by remember(law.bbbs) { mutableStateOf(initialAnchor.isNotBlank()) }
    LaunchedEffect(law.bbbs) {
        if (restored) return@LaunchedEffect
        val last = vm.lastReadAnchor(law.bbbs)
        if (!last.isNullOrBlank() && tree.byBlockId(last) != null) {
            collapsed = tree.expandTo(last, collapsed)
            delay(30)
            val lastKey = tree.nodeKeyOf(last)
            val index = tree.visible(collapsed).indexOfFirst { it.key == lastKey }
            if (index >= 0) {
                listState.scrollToItem(index)
                flashAnchor = last
            }
        }
        restored = true
    }

    // ---- 滚动时记录当前条目（既用于恢复阅读位置，也用于「收藏当前条文」）----
    //
    // ⚠️ 这里必须**同时**更新 flashAnchor。
    // 早先只调了 saveReadingAnchor（那是给「下次打开恢复到哪条」用的），
    // 而收藏按钮读的是 flashAnchor —— 它只在「按条号跳转」和「恢复阅读位置」时被赋值。
    // 结果：用户自然滚动到第 42 条再点 ☆，flashAnchor 仍是初始的空串，
    // 于是**实际收藏的是整部法规**，而不是当前条文。
    // 这就是「收藏法条的某一条功能未实现」的根因。
    LaunchedEffect(listState, visible) {
        snapshotFlow {
            // 跳过顶部的信息卡与工具栏（前 2 个 item）
            val idx = listState.firstVisibleItemIndex
            visible.getOrNull((idx - 2).coerceIn(0, (visible.size - 1).coerceAtLeast(0)))
        }.collect { node ->
            if (node != null && node.block.isArticle) {
                vm.saveReadingAnchor(law.bbbs, node.block.id)
                // 只有条文改变时才写 state，避免每帧滚动都触发重组
                if (flashAnchor != node.block.id) flashAnchor = node.block.id
            }
        }
    }

    // 阅读器**强制使用浅色令牌**。
    //
    // 原因：壁纸模式会把全局令牌切成深色（浅色文字），但阅读器背景是固定的
    // 浅色纸感（见 IOSTokens.readerBackground）。两者叠加 = 浅底浅字，看不清。
    // 用户反馈过「法条背景黑色」「点进法规上部 UI 可见度差」都是这个引起的。
    //
    // 阅读器是「内容层」，按 HIG 不该跟随壁纸风格，应该有自己的稳定主题。
    androidx.compose.runtime.CompositionLocalProvider(
        com.lawsearch.app.ui.theme.LocalTokens provides com.lawsearch.app.ui.theme.LightTokens,
    ) {
        // 必须**在 Provider 内部**取：这样拿到的就是上面注入的 LightTokens。
        // 原先它写在函数开头（Provider 之外），壁纸模式下会拿到 DarkTokens。
        val t = tokens()
        Box(Modifier.fillMaxSize().background(t.readerBackground)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    // 顶部要给悬浮工具栏让位（工具栏 + 状态栏高度）
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp + 52.dp,
                    // 底部要给悬浮的条文跳转条留位置，否则最后一条会被它盖住。
                    // 加 32dp 缓冲：跳转条本身有阴影与圆角，视觉上会比几何高度多占一点。
                    bottom = 32.dp + jumpBarReservedHeight(),
                ),
            ) {
                item(key = "info") { LawInfoHeader(law) }

                // 树节点的唯一键是 key（块 id 在同一部法规里可能重复，例如各分编下的「第一节」）
                items(visible, key = { it.key }) { node ->
                    val b = node.block
                    when {
                        b.isHeading -> HeadingRow(
                            block = b,
                            isCollapsed = collapsed.contains(node.key),
                            onToggle = {
                                collapsed = if (collapsed.contains(node.key)) {
                                    collapsed - node.key
                                } else {
                                    collapsed + node.key
                                }
                            },
                        )
                        b.isArticle -> ArticleBlock(
                            block = b,
                            fontSize = fontSize,
                            fontMode = fontMode,
                            flash = flashAnchor == b.id,
                            onClick = { vm.rememberAnchor(b.id) },
                        )
                        // markdown 提升出来的标题（`## 序言`）：只当标题画，**不可折叠**。
                        // 它不是真实章节，没有子节点，给了箭头反而误导。
                        b.type == ReaderTree.TYPE_MD_HEADING -> HeadingRow(
                            block = b,
                            isCollapsed = false,
                            onToggle = {},
                        )
                        else -> TextBlock(b.text, fontSize, fontMode)
                    }
                }
                item(key = "bottom-space") { Spacer(Modifier.height(32.dp)) }
            }

            // ---- 顶部：状态栏保护 + 浮动按钮（**没有整条导航栏背景**）----
            // 用户要求把导航栏整块删掉（「一大块黑色太丑」），只留浮动按钮。
            // 状态栏那一条保留极淡保护，否则正文会卷到时间/电量底下。
            StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))

            // 浮动按钮：返回 / ☆ / ⋯，每个都自带胶囊衬底。
            // QA 实测：阅读器 7 个顶部按钮里「返回」是唯一没有背景 View 的，
            // 在米白纸底 (246,245,242) 上对比度只有 1.34:1。
            // 现在统一由 FloatingActionBacking 提供衬底，不再依赖页面底色。
            FloatingNavActions(
                modifier = Modifier.align(Alignment.TopCenter),
                left = {
                    // 阅读器纸感浅底上要用加深的强调色（6.48:1），
                    // 默认 t.primary 只有 4.37:1。
                    // ⚠️ 注意 IOSBackButton 内部会把同一个 color 同时用于文字和 ‹ 箭头。
                    IOSBackButton(onClick = onBack, color = t.readerPrimary)
                },
                right = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 收藏当前阅读位置：有锚点收藏该条文，否则收藏整部法规
                        val favKey = flashAnchor
                        val isFav = if (favKey.isBlank()) {
                            bookmarkedKeys.contains(com.lawsearch.app.data.LawRepository.LAW_LEVEL)
                        } else {
                            bookmarkedKeys.contains(favKey)
                        }
                        // 收藏目标：当前条文 or 整部法规。同时用于无障碍语义，
                        // 让用户（和 QA）能确认「我刚才收藏的到底是哪一条」。
                        // 早先这里既没有文字提示，锚点又不会随滚动更新，
                        // 用户以为在收藏第 42 条，实际收藏了整部法规。
                        val favTarget = if (favKey.isBlank()) {
                            "整部法规"
                        } else {
                            tree.byBlockId(favKey)?.block?.no?.takeIf { it.isNotBlank() }?.let { "第 $it" }
                                ?: "当前条文"
                        }
                        FloatingActionBacking {
                            IOSTap(
                                onClick = {
                                    val anchor = flashAnchor
                                    val node = if (anchor.isBlank()) null else tree.byBlockId(anchor)
                                    vm.toggleBookmark(
                                        bbbs = law.bbbs,
                                        lawTitle = law.title,
                                        anchor = anchor,
                                        articleNo = node?.block?.no.orEmpty(),
                                        excerpt = node?.block?.bodyParagraphs()?.firstOrNull()
                                            ?: "整部法规",
                                    )
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .semantics {
                                        // TalkBack / QA 自动化都能读到当前收藏目标与状态
                                        contentDescription = if (isFav) {
                                            "已收藏$favTarget，点击取消"
                                        } else {
                                            "收藏$favTarget"
                                        }
                                        role = Role.Button
                                    },
                                pressedScale = 0.9f,
                                pressedOverlay = Color.Transparent,
                            ) {
                                Text(
                                    // ★ 实心＝已收藏，☆ 空心＝未收藏；再叠一个小圆点标示「条文级」
                                    // （区别于整部法规），让用户一眼看出收藏粒度。
                                    text = when {
                                        isFav && favKey.isNotBlank() -> "★•"
                                        isFav -> "★"
                                        else -> "☆"
                                    },
                                    fontSize = 18.sp,
                                    // 阅读器用加深的强调色（readerPrimary，对比度 6.48:1），
                                    // 原来的 t.primary 在纸感底上只有 4.37:1，星星几乎看不见
                                    color = if (isFav) t.readerPrimary else t.readerSecondary,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FloatingActionBacking {
                            MoreMenu(
                                onCopyAll = { copyToClipboard(context, lawToPlainText(law)) },
                                onExpandAll = { collapsed = emptySet() },
                                onCollapseAll = { collapsed = tree.allCollapsed() },
                            )
                        }
                    }
                },
            )

            // ---- 顶部：悬浮工具栏（全部展开/折叠 + 字号）----
            // 固定在屏幕上方、不随内容滚动，随时可调（用户要求）。
            // 位置：状态栏之下、浮动按钮行（56dp）之下，避免盖住「返回 / ☆ / ⋯」。
            //
            // 需要一层 scrim：否则正文会从「返回 / ☆」后面穿过，糊成一团。
            // 用垂直渐变（实色 → 透明）而不是整块纯色，既能遮住正文又不会显得生硬。
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                t.readerBackground,
                                t.readerBackground.copy(alpha = 0.96f),
                                t.readerBackground.copy(alpha = 0.72f),
                                androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 让开浮动按钮行（与 FloatingNavActions 的 56dp 高度对齐）
                Spacer(Modifier.height(56.dp))
                ToolsRow(
                    onExpandAll = { collapsed = emptySet() },
                    onCollapseAll = { collapsed = tree.allCollapsed() },
                    onFontDown = { vm.setReaderFontSize(fontSize - 1) },
                    onFontUp = { vm.setReaderFontSize(fontSize + 1) },
                    fontSize = fontSize,
                )
            }

            // ---- 底部：条文跳转条（输入阿拉伯数字直达对应条文）----
            // ⚠️ 必须在 CompositionLocalProvider 内部调用，这样它内部的 tokens()
            // 拿到的才是 LightTokens；挪到 Provider 外面会拿到 DarkTokens，
            // 在浅色阅读器上文字会看不清。
            ArticleJumpBar(
                value = gotoText,
                error = gotoError,
                expanded = gotoOpen,
                rangeHint = law.articleRange,
                onValueChange = {
                    // 只接受数字，避免用户输入「第22条」这类字符后无从下手
                    gotoText = it.filter { ch -> ch.isDigit() }.take(5)
                    gotoError = ""
                },
                onExpandedChange = { open ->
                    gotoOpen = open
                    if (!open) gotoText = ""
                },
                onSubmit = { jumpToArticleNo(gotoText) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding(),
            )
        }
    }  // end CompositionLocalProvider(LightTokens)
}

/** 底部条文跳转条占用的高度（列表底部留白用） */
private val JUMP_BAR_HEIGHT = 56.dp

@Composable
private fun jumpBarReservedHeight(): androidx.compose.ui.unit.Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return JUMP_BAR_HEIGHT + 12.dp + 12.dp + nav
}

/**
 * 底部悬浮的「跳到第 N 条」输入条。
 *
 * 设计取舍：
 *  - 放在**底部**而不是顶部：顶部已有返回与工具条，再塞输入框会挤；
 *    底部悬浮条不占正文空间，且离拇指最近。
 *  - 收起时是一个窄胶囊（只显示「跳到第 N 条」提示），点开后展开为输入框。
 *  - 只接受阿拉伯数字（输入时自动过滤其它字符），回车即跳转。
 *  - 键盘弹起时用 imePadding 上浮，不会被输入法挡住。
 */
@Composable
private fun ArticleJumpBar(
    value: String,
    error: String,
    expanded: Boolean,
    rangeHint: List<Int>,
    onValueChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = tokens()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // 展开时自动聚焦并弹出数字键盘
    LaunchedEffect(expanded) {
        if (expanded) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    val rangeText = if (rangeHint.size == 2 && rangeHint[1] > 0) "1–${rangeHint[1]}" else ""

    Box(
        modifier.fillMaxWidth().padding(bottom = 12.dp + nav),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (!expanded) {
            // 收起态：窄胶囊
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (t.isDark) Color(0xFF2A2A2E).copy(alpha = 0.82f) else Color(0xFFF2F3F6).copy(alpha = 0.88f),
                shadowElevation = 8.dp,
                modifier = Modifier.height(JUMP_BAR_HEIGHT),
            ) {
                com.lawsearch.app.ui.components.IOSTap(
                    onClick = { onExpandedChange(true) },
                    // 用 fillMaxHeight 让胶囊按内容收紧，而不是撑满整行
                    modifier = Modifier.fillMaxHeight(),
                    pressedScale = 0.95f,
                    pressedOverlay = Color.Transparent,
                ) {
                    Row(
                        Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "跳到指定条文",
                            tint = t.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = if (rangeText.isNotEmpty()) "跳到第 N 条（$rangeText）" else "跳到第 N 条",
                            color = t.textPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        } else {
            // 展开态：输入框 + 跳转 + 取消
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (t.isDark) Color(0xFF2A2A2E).copy(alpha = 0.86f) else Color(0xFFF2F3F6).copy(alpha = 0.92f),
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("第", color = t.textSecondary, fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (t.isDark) Color(0xFF3A3A3E) else Color.White)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    if (rangeText.isNotEmpty()) "输入条号 $rangeText" else "输入条号",
                                    color = t.textTertiary,
                                    fontSize = 15.sp,
                                )
                            }
                            BasicTextField(
                                value = value,
                                onValueChange = onValueChange,
                                singleLine = true,
                                textStyle = TextStyle(color = t.textPrimary, fontSize = 17.sp),
                                cursorBrush = SolidColor(t.primary),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        focusManager.clearFocus()
                                        onSubmit()
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("条", color = t.textSecondary, fontSize = 15.sp)
                        Spacer(Modifier.width(10.dp))
                        CapsuleButton(
                            text = "跳转",
                            onClick = {
                                focusManager.clearFocus()
                                onSubmit()
                            },
                            selected = value.isNotEmpty(),
                        )
                        Spacer(Modifier.width(6.dp))
                        com.lawsearch.app.ui.components.IOSTap(
                            onClick = {
                                focusManager.clearFocus()
                                onExpandedChange(false)
                            },
                            modifier = Modifier.height(34.dp),
                            pressedScale = 0.92f,
                            pressedOverlay = Color.Transparent,
                        ) {
                            Text(
                                "取消",
                                color = t.textSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
                            )
                        }
                    }
                    if (error.isNotEmpty()) {
                        Text(
                            text = error,
                            color = t.badgeRevoked,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LawInfoHeader(law: LawJson) {
    val t = tokens()
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = law.title,
            color = t.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 32.sp,
        )
        if (law.meta.subtitle.isNotBlank()) {
            Text(
                text = law.meta.subtitle,
                color = t.readerSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 元信息用小字时对比度会掉（#8B93A1 只有 2.86:1），阅读器里改用 readerSecondary
            val metaColor = t.readerSecondary
            if (law.gbrq.isNotBlank()) MetaText("公布 ${law.gbrq}", color = metaColor)
            if (law.sxrq.isNotBlank()) MetaText("　施行 ${law.sxrq}", color = metaColor)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ValidityBadge(law.validity.ifBlank { "有效" })
            // 法律解释/批复/修正案类文件本来就没有「第X条」编号，articleCount 会是 0。
            // 直接显示「0 条」会让人以为内容缺失。
            //
            // 注意：不能只读「N 段」——刑法修正案（九）有 134 个 block，
            // 但很多是「（一）逐条修改…」这种同一条的续行，用户会把「134 段」
            // 读成「134 个条款」，与事实不符。加「文本」二字消歧。
            val countLabel = if (law.articleCount > 0) {
                "${law.articleCount} 条"
            } else {
                val paras = law.blocks.count {
                    it.type == com.lawsearch.app.data.Block.TYPE_TEXT && it.text.isNotBlank()
                }
                "${paras} 段文本"
            }
            MetaText("  ·  $countLabel", Modifier.padding(start = 6.dp), color = t.readerSecondary)
            if (law.zdjgName.isNotBlank()) {
                MetaText("　${law.zdjgName}", Modifier.padding(start = 6.dp), color = t.readerSecondary)
            }
        }
    }
}

@Composable
private fun ToolsRow(
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onFontDown: () -> Unit,
    onFontUp: () -> Unit,
    fontSize: Int = 17,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = tokens().readerPrimary
        CapsuleButton("展开", onExpandAll, accentColor = accent)
        CapsuleButton("折叠", onCollapseAll, accentColor = accent)
        Spacer(Modifier.weight(1f))
        CapsuleButton("A−", onFontDown, accentColor = accent)
        // 中间显示当前字号，调完心里有数
        Text(
            "$fontSize",
            // 原来用 textSecondary（#8B93A1，对比度 2.86:1），在纸感底上几乎看不见
            color = tokens().readerSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        CapsuleButton("A+", onFontUp, accentColor = accent)
    }
}

/**
 * 章节标题要显示的文本。
 *
 * ⚠️ **内置数据里标题字段有两种 schema，必须两者都认：**
 *   1. `label` —— 国家法律法规数据库导出的那批（宪法 2018 修正文本等），
 *      形如 `label="第一章 总 纲"`，此时 `text` 为空串；
 *   2. `text`  —— 另一批（**内建「中华人民共和国宪法」等**），
 *      形如 `text="第一章 总纲"`，此时 `label` 缺失/为空。
 *
 * 早先 `HeadingRow` 只读 `block.label`，于是第 2 类法规的**章节标题整行渲染成空白** ——
 * 表现为「点开宪法，一条条条文挤在一起，没有任何章节分隔」，即用户反馈的「宪法显示错乱」。
 *
 * 实测影响面：全量 1874 部法规中 **325 部（17.3%）** 的标题块 `label` 全部为空，
 * 所以这不是宪法个案，而是系统性缺陷。见 `HeadingTextTest`。
 *
 * 优先级：`label` 优先（它是更规范的字段），为空时回退 `text`；
 * 两者都空就返回空串 —— **不硬编码兜底文案**，避免把脏数据掩盖成假标题。
 */
internal fun headingTextOf(block: Block): String =
    block.label.ifBlank { block.text }

/** 章节标题（可折叠）：黑体；编/章 19sp SemiBold，节/分编 17sp Medium 且左侧缩进 */
@Composable
private fun HeadingRow(
    block: Block,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
) {
    val t = tokens()
    // markdown 提升出的标题没有子节点，折叠箭头无意义 → 隐藏箭头并去掉点击。
    val isMd = block.type == ReaderTree.TYPE_MD_HEADING
    val leading = when {
        isMd -> 8.dp
        block.type == Block.TYPE_SUBPART -> 8.dp
        block.type == Block.TYPE_SECTION -> 16.dp
        else -> 0.dp
    }
    val textSize = when {
        isMd -> 18.sp
        block.type == Block.TYPE_PART -> 21.sp
        block.type == Block.TYPE_CHAPTER -> 20.sp
        block.type == Block.TYPE_SUBPART -> 18.sp
        else -> 17.sp
    }
    val weight = when {
        isMd -> FontWeight.SemiBold
        // 内嵌黑体只有单一字重，SemiBold 由系统合成；编/章用 SemiBold 形成明显层级
        block.type == Block.TYPE_PART || block.type == Block.TYPE_CHAPTER -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }
    val rotation by animateFloatAsState(
        targetValue = if (isCollapsed) 0f else 90f,
        animationSpec = IOSMotion.fold(),
        label = "caret",
    )
    val color = when {
        isMd -> t.headingText
        block.type == Block.TYPE_PART || block.type == Block.TYPE_CHAPTER -> t.headingText
        else -> t.headingSubText
    }
    IOSTap(
        onClick = if (isMd) ({}) else onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = leading, top = if (block.type == Block.TYPE_PART) 22.dp else 14.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp)),
        pressedOverlay = if (t.isDark) Color(0x14FFFFFF) else Color(0x0A000000),
        pressedScale = 0.99f,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isMd) {
                // markdown 标题不可折叠，不画箭头，也不占位（避免左侧多出 18dp 空白，
                // 让它与「## 序言」这类根级标题的视觉起线一致）。
                Unit
            } else {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    // 用矢量三角绘制折叠箭头：朝右（折叠）→ 朝下（展开）。
                    // 之前用「▸」字符旋转，在部分字体下被渲染成小点，观感不可辨。
                    val arrowColor = t.textSecondary
                    androidx.compose.foundation.Canvas(
                        Modifier.size(9.dp).graphicsLayer { rotationZ = rotation },
                    ) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.15f, size.height * 0.1f)
                            lineTo(size.width * 0.85f, size.height * 0.5f)
                            lineTo(size.width * 0.15f, size.height * 0.9f)
                            close()
                        }
                        drawPath(path, color = arrowColor)
                    }
                }
            }
            Text(
                // 不能只读 label：325 部法规的标题在 text 里，只读 label 会渲染成空白。
                // 详见 headingTextOf 的注释。
                text = headingTextOf(block),
                color = color,
                fontSize = textSize,
                fontWeight = weight,
                // 章节标题用**黑体**而不是楷体：楷体笔画细、在小字号屏幕上容易糊成一团，
                // 阅读体验明显差于黑体。层级改由字号（19/17sp）、字重（SemiBold/Medium）
                // 与颜色（正文黑 / 灰）区分，不依赖字体族。
                fontFamily = LawFonts.HeiTi,
                lineHeight = (textSize.value * 1.6f).sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 条文：条号单独一行（黑体蓝色），正文仿宋、首行缩进两字、行高 1.95；
 * 「（一）」类分项不缩进、左侧留 2 字。搜索结果跳转命中时整条闪一下。
 */
@Composable
private fun ArticleBlock(
    block: Block,
    fontSize: Int,
    flash: Boolean,
    onClick: () -> Unit,
    fontMode: String = com.lawsearch.app.util.BodyFont.SYSTEM,
) {
    val t = tokens()
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash) {
            highlight.snapTo(1f)
            highlight.animateTo(0f, animationSpec = tween(1800))
        }
    }
    val highlightedAlpha = (0.85f * highlight.value).coerceIn(0f, 0.85f)
    val lineHeight = (fontSize * 1.95f).sp
    val bodyStyle = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = lineHeight,
        fontFamily = LawFonts.body(fontMode),
        color = t.readerBody,
    )
    val itemIndent = with(LocalDensity.current) { (fontSize * 2).dp }

    IOSTap(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(t.flashBackground.copy(alpha = highlightedAlpha))
            .padding(vertical = 8.dp, horizontal = 2.dp),
        pressedOverlay = if (t.isDark) Color(0x12FFFFFF) else Color(0x08000000),
        pressedScale = 1f,
    ) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().animateContentSize(IOSMotion.fold())) {
                Text(
                    text = block.no,
                    color = t.articleNumber,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = LawFonts.HeiTi,
                    letterSpacing = 0.5.sp,
                )
                // 「第X条」与正文之间留出呼吸感：正文行高 1.95 倍，首行本身有半行留白，
                // 原先只加 2dp 会显得「字贴着字」。这里给 6dp，视觉上接近纸质法条排版。
                Column(Modifier.padding(top = 6.dp)) {
                    val paragraphs = block.bodyParagraphs().filter { it.isNotBlank() }
                    val twoChar = (fontSize * 2).sp
                    paragraphs.forEachIndexed { index, para ->
                        val isItem = ITEM_PATTERN.containsMatchIn(para.take(4))
                        val prevIsItem = index > 0 &&
                            ITEM_PATTERN.containsMatchIn(paragraphs[index - 1].take(4))
                        Text(
                            text = para,
                            style = bodyStyle.copy(
                                // 悬挂缩进：首行缩进 2 字，**续行回到 0（顶格）**。
                                // 分项也要用同一规则 —— 不能靠 Modifier.padding(start) 把整块右移，
                                // 那样分项自身的续行会被一起推过去，脱离左侧对齐线。
                                textIndent = TextIndent(firstLine = twoChar, restLine = 0.sp),
                            ),
                            modifier = when {
                                index == 0 -> Modifier
                                // 分项之间（（一）→（二））留得比普通段落更多一点，
                                // 否则读不出一条一项的分隔感
                                isItem && prevIsItem -> Modifier.padding(top = (fontSize * 0.7f).dp)
                                isItem -> Modifier.padding(top = (fontSize * 0.5f).dp)
                                else -> Modifier.padding(top = (fontSize * 0.4f).dp)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 非条文的普通段落（前言、附注等） */
@Composable
private fun TextBlock(text: String, fontSize: Int, fontMode: String = com.lawsearch.app.util.BodyFont.SYSTEM) {
    val t = tokens()
    SelectionContainer {
        Text(
            text = text,
            color = t.readerBody,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.9f).sp,
            fontFamily = LawFonts.body(fontMode),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }
}

private val ITEM_PATTERN = Regex("^[（(][一二三四五六七八九十百千0-9]+[）)]")

@Composable
private fun MoreMenu(
    onCopyAll: () -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val t = tokens()
    Box {
        IOSIconButton(onClick = { open = true }) {
            Text("⋯", color = t.readerPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = t.card) {
            DropdownMenuItem(
                text = { Text("复制全文", color = t.textPrimary) },
                onClick = { open = false; onCopyAll() },
            )
            DropdownMenuItem(
                text = { Text("全部展开", color = t.textPrimary) },
                onClick = { open = false; onExpandAll() },
            )
            DropdownMenuItem(
                text = { Text("全部折叠", color = t.textPrimary) },
                onClick = { open = false; onCollapseAll() },
            )
        }
    }
}

internal fun lawToPlainText(law: LawJson): String = buildString {
    append(law.title).append('\n')
    if (law.meta.subtitle.isNotBlank()) append(law.meta.subtitle).append('\n')
    append("公布 ").append(law.gbrq).append("　施行 ").append(law.sxrq).append('\n')
    if (law.validity.isNotBlank()) append(law.validity).append("　${law.articleCount} 条\n")
    append('\n')
    for (b in law.blocks) {
        when {
            b.isHeading -> append('\n').append(b.label).append('\n')
            b.isArticle -> {
                append('\n').append(b.no).append('\n')
                b.bodyParagraphs().forEach { append(it).append('\n') }
            }
            b.text.isNotBlank() -> append(b.text).append('\n')
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("法规", text))
}
