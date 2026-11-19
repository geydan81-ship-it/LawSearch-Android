package com.lawsearch.app.ui.laws

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import com.lawsearch.app.ui.components.IOSTap
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box as ComposeBox
import androidx.compose.foundation.layout.Row as ComposeRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawsearch.app.data.LawListRow
import com.lawsearch.app.data.SubgroupCount
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.components.CapsuleButton
import com.lawsearch.app.ui.components.ChevronLeft
import com.lawsearch.app.ui.components.FloatingNavActions
import com.lawsearch.app.ui.components.IOSBackButton
import com.lawsearch.app.ui.components.IOSIconButton
import com.lawsearch.app.ui.components.LawRowCard
import com.lawsearch.app.ui.components.StatusBarScrim
import com.lawsearch.app.ui.components.tabBarBottomSpace
import com.lawsearch.app.ui.theme.tokens

/**
 * 法规列表页：顶部大标题、二级分类下拉、整宽法规卡片。
 *
 * group 为空时是「全部法规」页（列出全部法规），有 group 时是从首页分类卡 push 进来的列表页。
 */
@Composable
fun LawListScreen(
    vm: AppViewModel,
    group: String,
    onBack: (() -> Unit)?,
    onLawClick: (bbbs: String) -> Unit,
    /** 「最近更新」模式：按公布日期倒序，不显示二级分类 */
    latestMode: Boolean = false,
) {
    val t = tokens()
    val state by vm.list.collectAsStateWithLifecycle()

    LaunchedEffect(group, latestMode) {
        if (latestMode) {
            vm.loadLatest()
        } else {
            vm.selectSubgroup("")
            vm.loadList(group)
        }
    }

    val listState = rememberLazyListState()

    val title = when {
        latestMode -> "最近更新"
        group.isEmpty() -> "全部法规"
        else -> group
    }
    val laws: List<LawListRow> = state.filtered

    // 同名法规消歧：两个数据源合并后有 7 部法规标题完全相同、bbbs 不同
    // （环境噪声污染防治法、大气污染防治法、环境影响评价法、固体废物污染环境防治法、
    // 海洋环境保护法 等）。列表里光看标题分不出是哪一部，点错就白跑一趟。
    // 这里只在**本屏内真的重名**时才给标题补上公布日期，其余标题保持干净。
    val duplicateTitles: Set<String> = remember(laws) {
        laws.groupBy { it.title }.filterValues { it.size > 1 }.keys
    }

    /** 标题重复时返回公布日期（去重后拼接，极端情况下同一天多部则再补 bbbs 前 4 位） */
    fun disambiguator(law: LawListRow): String? {
        if (law.title !in duplicateTitles) return null
        if (law.gbrq.isNotBlank()) return law.gbrq
        // 没有公布日期的兜底：给 bbbs 前 4 位，至少能区分
        return law.bbbs.take(4)
    }

    // 滚动位置在返回时保留：rememberLazyListState 会随 NavBackStackEntry 一起恢复
    ComposeBox(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                // TabBar + 两行文字的安全区（原来只有 16dp，底部内容会被 TabBar 盖住）
                bottom = tabBarBottomSpace() + 56.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                ComposeRow(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = t.textPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "  ${laws.size} 部",
                        color = t.textSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.Bottom).padding(bottom = 4.dp),
                    )
                }
            }

            // ---- 二级分类：纵向列表（竖排比横滑好点，分类多时一屏能看全）----
            if (!latestMode && state.subgroups.size > 1) {
                item(key = "chips") {
                    SubgroupList(
                        subgroups = state.subgroups,
                        selected = state.selectedSubgroup,
                        total = state.subgroups.sumOf { it.count },
                        onSelect = { vm.selectSubgroup(it) },
                    )
                }
            }

            if (state.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("载入中…", color = t.textSecondary, fontSize = 15.sp)
                    }
                }
            } else if (laws.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("这里还没有法规", color = t.textSecondary, fontSize = 15.sp)
                    }
                }
            } else {
                items(laws, key = { it.bbbs }) { law ->
                    LawRowCard(
                        title = law.title,
                        validity = law.validity,
                        articleCount = law.articleCount,
                        gbrq = law.gbrq,
                        onClick = { onLawClick(law.bbbs) },
                        titleDate = disambiguator(law),
                    )
                }
            }
        }

        // 状态栏保护（只有状态栏那一窄条，不画整条导航栏背景）
        StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))

        // 浮动返回按钮：不再有「标题栏」整块，也不显示「宪法」小标题。
        // 大标题「宪法」已经说明了这是什么页面；用户明确要求删掉标题栏。
        if (onBack != null) {
            FloatingNavActions(
                modifier = Modifier.align(Alignment.TopCenter),
                left = { IOSBackButton(onClick = onBack) },
            )
        }
    }
}

/**
 * 二级分类选择：**纵向列表**。
 *
 * 之前是横向滑动 chip 行，分类一多就得反复左右滑，选起来费劲。
 * 改成竖排后一屏能看到全部分类及数量，点击一次到位。
 * 默认收起成一行「分类：全部 ▾」，点开后展开列表，避免占掉太多列表空间。
 */
@Composable
private fun SubgroupList(
    subgroups: List<SubgroupCount>,
    selected: String,
    total: Int,
    onSelect: (String) -> Unit,
) {
    val t = tokens()
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // 折叠态：一行显示当前选择
        IOSTap(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                // 玻璃表面：原来是 groupedBackground（深色模式下是纯黑，和壁纸冲突）
                .then(
                    if (com.lawsearch.app.ui.components.LocalGlassEffectsEnabled.current) {
                        Modifier
                            .background(Color.White.copy(alpha = if (t.isDark) 0.16f else 0.52f))
                            .border(
                                0.7.dp,
                                Color.White.copy(alpha = if (t.isDark) 0.14f else 0.55f),
                                RoundedCornerShape(12.dp),
                            )
                    } else {
                        Modifier.background(t.groupedBackground)
                    },
                ),
            pressedOverlay = if (t.isDark) Color(0x14FFFFFF) else Color(0x0A000000),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("分类", color = t.textSecondary, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (selected.isEmpty()) "全部（${total}）"
                    else "$selected（${subgroups.firstOrNull { it.subgroup == selected }?.count ?: 0}）",
                    color = t.primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    // 长分类名（如「有关法律问题和重大问题的决定（部分）」）必须单行省略，
                    // 否则换行后会撑破 44dp 高的框、压到下一行
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(4f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(10.dp).graphicsLayer {
                    rotationZ = if (expanded) 90f else 0f
                }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val p = Path().apply {
                            moveTo(size.width * 0.15f, size.height * 0.1f)
                            lineTo(size.width * 0.85f, size.height * 0.5f)
                            lineTo(size.width * 0.15f, size.height * 0.9f)
                            close()
                        }
                        drawPath(p, color = t.textSecondary)
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    // 展开的下拉列表同样用玻璃底，避免深色模式下变成一块纯黑
                    .then(
                        if (com.lawsearch.app.ui.components.LocalGlassEffectsEnabled.current) {
                            Modifier
                                .background(Color.White.copy(alpha = if (t.isDark) 0.18f else 0.68f))
                                .border(
                                    0.7.dp,
                                    Color.White.copy(alpha = if (t.isDark) 0.14f else 0.55f),
                                    RoundedCornerShape(12.dp),
                                )
                        } else {
                            Modifier.background(t.groupedBackground)
                        },
                    ),
            ) {
                SubgroupRow("全部", total, selected.isEmpty()) {
                    onSelect("")
                    expanded = false
                }
                subgroups.forEach { sub ->
                    SubgroupRow(sub.subgroup, sub.count, selected == sub.subgroup) {
                        onSelect(sub.subgroup)
                        expanded = false
                    }
                }
            }
        }
    }
}

@Composable
private fun SubgroupRow(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val t = tokens()
    IOSTap(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        pressedOverlay = if (t.isDark) Color(0x14FFFFFF) else Color(0x0A000000),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (selected) t.primary else t.textPrimary,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("$count", color = t.textTertiary, fontSize = 13.sp)
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Text("✓", color = t.primary, fontSize = 15.sp)
            }
        }
    }
}
