package com.lawsearch.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lawsearch.app.ui.theme.tokens
import com.lawsearch.app.util.Validity

/** 时效性徽章：● 有效 / ● 已被修订 …，颜色区分状态 */
@Composable
fun ValidityBadge(validity: String, modifier: Modifier = Modifier) {
    val t = tokens()
    val color: Color = when (Validity.tone(validity)) {
        Validity.Tone.Valid -> t.badgeValid
        Validity.Tone.Pending -> t.badgePending
        Validity.Tone.Revised -> t.badgeRevised
        Validity.Tone.Revoked -> t.badgeRevoked
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text = validity, color = color, fontSize = 13.sp)
    }
}

/** 元信息小字（条数、日期） */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        color = color ?: tokens().textSecondary,
        fontSize = 13.sp,
        modifier = modifier,
    )
}

/**
 * 法规卡片：整宽、圆角 16dp、内容行高 ≥72dp。
 *
 * @param titleDate 同名法规的消歧后缀（公布日期，如 "2018-10-26"）。
 *   两个数据源合并后有 7 部法规**标题完全相同但 bbbs 不同**
 *   （环境噪声污染防治法、大气污染防治法、环境影响评价法、
 *   固体废物污染环境防治法、海洋环境保护法 等），列表里光看标题分不出是哪一部。
 *   由调用方判断「本屏内是否有同名」，只有真的重复时才传值，避免整屏标题都被加长。
 */
@Composable
fun LawRowCard(
    title: String,
    validity: String,
    articleCount: Int,
    gbrq: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleDate: String? = null,
) {
    val t = tokens()
    IOSCard(modifier = modifier) {
        IOSTap(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            pressedOverlay = if (t.isDark) Color(0x14FFFFFF) else Color(0x0A000000),
            pressedScale = 0.985f,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 标题 + 日期后缀放在同一段文本里，这样省略号作用在整行上，
                    // 不会出现「标题占满宽度、日期被挤出屏幕」的情况。
                    Text(
                        text = if (titleDate.isNullOrBlank()) title else "$title · $titleDate",
                        color = t.textPrimary,
                        fontSize = 17.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        // 长标题（如「中华人民共和国中国人民解放军军官军衔条例」）
                        // 两行放不下时必须补省略号，不能直接齐平截断成半句话
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("›", color = t.textTertiary, fontSize = 20.sp, modifier = Modifier.padding(start = 6.dp))
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ValidityBadge(validity)
                    if (articleCount > 0) MetaText("$articleCount 条", Modifier.padding(start = 8.dp))
                    if (gbrq.isNotEmpty()) MetaText(gbrq, Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}
