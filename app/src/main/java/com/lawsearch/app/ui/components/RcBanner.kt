package com.lawsearch.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lawsearch.app.BuildConfig
import com.lawsearch.app.ui.theme.tokens

/**
 * 测试版横幅。
 *
 * 只在 `IS_RC` 构建里显示（flavor = rc，包名 `com.lawsearch.app.rc`）。
 * 正式版完全不渲染，用户看不到任何"测试"字样。
 */
@Composable
fun RcBanner(modifier: Modifier = Modifier) {
    if (!BuildConfig.IS_RC) return
    val t = tokens()
    val amber = Color(0xFFB26A00)
    val bg = if (t.isDark) Color(0xFF3A2A12) else Color(0xFFFFF4E0)

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text("⚠️", fontSize = 16.sp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    "你正在使用测试版 ${BuildConfig.VERSION_NAME}",
                    color = amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "本版本用于体验新功能，可能存在崩溃、显示异常或数据错误，功能也可能随时变更。" +
                        "请勿用于正式场合。遇到问题请在 设置 → 反馈与日志 中复制日志发送给开发者。",
                    color = if (t.isDark) Color(0xFFD8C49A) else Color(0xFF8A6A2F),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 测试版「关于」页顶部的一句话标签 */
@Composable
fun RcVersionTag() {
    if (!BuildConfig.IS_RC) return
    val t = tokens()
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFFFFE9BF))
            .padding(horizontal = 10.dp),
    ) {
        Text(
            "测试版",
            color = Color(0xFF8A6A2F),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
