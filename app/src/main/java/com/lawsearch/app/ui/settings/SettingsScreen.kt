package com.lawsearch.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawsearch.app.BuildConfig
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.components.CapsuleButton
import com.lawsearch.app.ui.components.IOSCard
import com.lawsearch.app.ui.components.IOSDivider
import com.lawsearch.app.ui.components.IOSTap
import com.lawsearch.app.ui.components.LargeTitleHeader
import com.lawsearch.app.ui.components.MetaText
import com.lawsearch.app.ui.components.StatusBarScrim
import com.lawsearch.app.ui.components.rememberCollapsingTitle
import com.lawsearch.app.ui.components.tabBarBottomSpace
import com.lawsearch.app.ui.theme.tokens
import com.lawsearch.app.util.ReaderSettings
import com.lawsearch.app.util.LogBuffer
import kotlinx.coroutines.delay

/**
 * 设置页。
 *
 * 与旧版最大的区别：**不提供「更新法律库」**。
 * 本应用不联网拉取法规，数据随 APK 版本发布；联网仅用于用户主动「检查 App 新版本」。
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val t = tokens()
    val state by vm.settings.collectAsStateWithLifecycle()
    val updateState by vm.update.collectAsStateWithLifecycle()
    val readerFontSize = ReaderSettings.fontSize.coerceIn(15, 22)

    val clipboard = LocalClipboardManager.current
    var toast by remember { mutableStateOf<String?>(null) }
    var includeFullLog by remember { mutableStateOf(true) }
    // 复制后弹出预览面板，让用户确认实际复制了什么（日期/版本/设备/错误都在里面）
    var previewText by remember { mutableStateOf<String?>(null) }
    // 液态玻璃开关（Compose 可观察，切换后立即生效）
    var glassEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(vm.glassEnabled()) }

    LaunchedEffect(Unit) { vm.loadSettings() }
    // 复制提示自动消失
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    val listState = rememberLazyListState()
    // 大标题淡出仍需要折叠进度（导航栏已删除，不再有小标题）
    val collapseState = rememberCollapsingTitle(listState)

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                // TabBar + 两行说明文字的安全区（原来只有 16dp，长说明会被 TabBar 盖住）
                bottom = tabBarBottomSpace() + 56.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") { LargeTitleHeader(title = "设置", subtitle = "版本、阅读、反馈、关于") }

            // ---- 版本 ----
            item(key = "sec-version") { SectionLabel("版本") }
            item(key = "version-card") {
                IOSCard {
                    Column {
                        InfoRow("当前版本", "${BuildConfig.VERSION_NAME}${if (BuildConfig.IS_RC) "（测试版）" else ""}")
                        IOSDivider(startIndent = 16.dp)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("检查 App 新版本", color = t.textPrimary, fontSize = 17.sp)
                                MetaText("仅查询版本号，不下载任何内容", Modifier.padding(top = 2.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            CapsuleButton(
                                text = if (updateState.checking) "检查中…" else "检查",
                                onClick = { vm.checkAppUpdate() },
                                selected = updateState.checking,
                            )
                        }
                        updateState.info?.let { info ->
                            IOSDivider(startIndent = 16.dp)
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                if (info.isLatest) {
                                    Text(
                                        "已是最新版本",
                                        color = t.textPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                } else {
                                    Text(
                                        "发现新版本 ${info.version}",
                                        color = t.primary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (info.publishedAt.isNotBlank()) {
                                        MetaText("发布于 ${info.publishedAt}", Modifier.padding(top = 4.dp))
                                    }
                                    MetaText(
                                        "请到 GitHub Release 页面下载安装包",
                                        Modifier.padding(top = 6.dp),
                                    )
                                }
                                if (info.viaMirror) {
                                    MetaText("（通过镜像通道查询）", Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        updateState.message?.let { msg ->
                            IOSDivider(startIndent = 16.dp)
                            Text(
                                "检查失败：$msg",
                                color = t.badgeRevoked,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }

            // ---- 法律库 ----
            item(key = "sec-library") { SectionLabel("法律库") }
            item(key = "library-card") {
                IOSCard {
                    Column {
                        // 首屏数据还没加载完时显示「载入中…」，
                        // 否则会短暂显示「0 部」，让人以为库是空的。
                        val loaded = state.lawCount > 0
                        InfoRow("已收录", if (loaded) "${state.lawCount} 部规范性文件" else "载入中…")
                        IOSDivider(startIndent = 16.dp)
                        InfoRow("全文索引", if (loaded) "${state.ftsArticles} 条" else "载入中…")
                        IOSDivider(startIndent = 16.dp)
                        InfoRow("收藏", "${state.bookmarkCount} 项")
                    }
                }
            }
            item(key = "library-note") {
                Text(
                    "法律库随版本更新，本应用不会在线抓取法规。如需最新版本，请检查 App 更新。",
                    color = t.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // ---- 阅读 ----
            item(key = "sec-read") { SectionLabel("阅读") }
            item(key = "read-font") {
                IOSCard {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("正文字号", color = t.textPrimary, fontSize = 17.sp)
                                MetaText("${readerFontSize} pt", Modifier.padding(top = 2.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            CapsuleButton(
                                text = "A−",
                                onClick = { vm.setReaderFontSize(readerFontSize - 1) },
                            )
                            Spacer(Modifier.width(8.dp))
                            CapsuleButton(
                                text = "A+",
                                onClick = { vm.setReaderFontSize(readerFontSize + 1) },
                            )
                        }
                        IOSDivider(startIndent = 16.dp)
                        // 「正文字体」选择器已删除（v1.1.2）：
                        // 内置仿宋/楷体是北京中易电子的商业字体，随 APK 分发属侵权，已移除。
                        // 现在正文恒用系统字体，只有一个选项，留着选择器只会误导用户。
                        // 老用户的 bodyFontMode 旧值由 LawFonts.body() / BodyFont 安全降级为系统字体。
                        // 液态玻璃开关：默认开，卡顿时可关（关闭后退回不透明表面）
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("液态玻璃效果", color = t.textPrimary, fontSize = 17.sp)
                                MetaText(
                                    if (com.lawsearch.app.ui.components.LiquidGlass.isSupported) {
                                        "如感卡顿可关闭，关闭后退回纯色界面"
                                    } else {
                                        "当前系统版本不支持，已使用纯色界面"
                                    },
                                    Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            androidx.compose.material3.Switch(
                                checked = glassEnabled,
                                onCheckedChange = {
                                    glassEnabled = it
                                    vm.setGlassEffects(it)
                                },
                                enabled = com.lawsearch.app.ui.components.LiquidGlass.isSupported,
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    // 玻璃轨道：开启时半透明白 + 主色，关闭时更淡
                                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                                    checkedTrackColor = t.primary.copy(alpha = 0.85f),
                                    uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                                    uncheckedTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f),
                                    uncheckedBorderColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f),
                                ),
                            )
                        }
                    }
                }
            }

            // ---- 外观（壁纸） ----
            item(key = "sec-appearance") { SectionLabel("外观") }
            item(key = "wallpaper-card") {
                com.lawsearch.app.ui.wallpaper.WallpaperPicker()
            }

            // ---- 反馈与日志 ----
            item(key = "sec-log") { SectionLabel("反馈与日志") }
            item(key = "log-card") {
                IOSCard {
                    Column {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                "发现问题？复制日志后用邮件发送至",
                                color = t.textSecondary,
                                fontSize = 13.sp,
                            )
                            Text(
                                "3078511601@qq.com",
                                color = t.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        IOSDivider(startIndent = 16.dp)

                        // 复制按钮
                        IOSTap(
                            onClick = {
                                val report = vm.buildLogReport(includeFullLog)
                                clipboard.setText(AnnotatedString(report))
                                previewText = report
                                toast = "日志已复制（${if (includeFullLog) "含完整日志" else "仅错误摘要"}）"
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            pressedOverlay = if (t.isDark) Color0x14 else Color0x0A,
                        ) {
                            Row(Modifier.fillMaxWidth().align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("一键复制日志", color = t.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    MetaText("含错误摘要与设备信息", Modifier.padding(top = 2.dp))
                                }
                                Text("复制", color = t.primary, fontSize = 15.sp)
                            }
                        }
                        IOSDivider(startIndent = 16.dp)

                        // 是否包含完整日志
                        IOSTap(
                            onClick = { includeFullLog = !includeFullLog },
                            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                            pressedOverlay = Color0x14,
                        ) {
                            Row(Modifier.fillMaxWidth().align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                Text("复制时包含完整日志", color = t.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (includeFullLog) "开" else "关",
                                    color = if (includeFullLog) t.primary else t.textTertiary,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                        IOSDivider(startIndent = 16.dp)

                        // 清空日志
                        IOSTap(
                            onClick = {
                                vm.clearLogs()
                                toast = "日志已清空"
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp),
                            pressedOverlay = Color0x14,
                        ) {
                            Text(
                                "清空日志",
                                color = t.badgeRevoked,
                                fontSize = 15.sp,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                    }
                }
            }

            // ---- 关于 ----
            item(key = "sec-about") { SectionLabel("关于") }
            item(key = "about-card") {
                IOSCard {
                    Column {
                        InfoRow("应用", "LawSearch 法律法规数据库")
                        IOSDivider(startIndent = 16.dp)
                        InfoRow("版本号", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        IOSDivider(startIndent = 16.dp)
                        // 项目主页 / B 站主页：用户要求发布前加上。
                        // 用系统浏览器打开，本 App 不需要任何网络权限（保持「零网络」定位）。
                        LinkRow(
                            label = "项目主页",
                            url = "https://github.com/geydan81-ship-it/LawSearch",
                        )
                        IOSDivider(startIndent = 16.dp)
                        LinkRow(
                            label = "B 站主页",
                            url = "https://space.bilibili.com/673364404",
                        )
                        IOSDivider(startIndent = 16.dp)
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("数据来源", color = t.textPrimary, fontSize = 15.sp)
                            Text(
                                "国家法律法规数据库（flk.npc.gov.cn）。本应用为纯离线阅读工具，" +
                                    "不对收录内容的完整性、准确性作保证，请以官方发布为准。",
                                color = t.textTertiary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        IOSDivider(startIndent = 16.dp)
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("开源与致谢", color = t.textPrimary, fontSize = 15.sp)
                            Text(
                                "本应用基于 Kotlin、Jetpack Compose、Room、OkHttp、kotlinx.serialization 等开源项目构建，" +
                                    "详见「开源许可」页。",
                                color = t.textTertiary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // 只有状态栏保护，没有标题栏整块（大标题「设置」已经在列表里）
        StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))

        // 复制结果提示
        toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = tabBarBottomSpace() + 8.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (t.isDark) Color0xE62A2A2E else Color0xE61F1F23)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(msg, color = android.graphics.Color.WHITE.let { androidx.compose.ui.graphics.Color(it) }, fontSize = 14.sp)
                }
            }
        }

        // 日志预览面板：复制后让用户确认实际内容（日期、版本、设备、错误摘要都在里面）
        previewText?.let { text ->
            LogPreviewPanel(
                text = text,
                onDismiss = { previewText = null },
                onCopyAgain = {
                    clipboard.setText(AnnotatedString(text))
                    toast = "已重新复制"
                },
            )
        }
    }
}

private val Color0x14 = androidx.compose.ui.graphics.Color(0x14FFFFFF)
private val Color0x0A = androidx.compose.ui.graphics.Color(0x0A000000)
private val Color0xE62A2A2E = androidx.compose.ui.graphics.Color(0xE62A2A2E)
private val Color0xE61F1F23 = androidx.compose.ui.graphics.Color(0xE61F1F23)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = tokens().textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * 可点击的外链行（项目主页 / B 站主页）。
 *
 * 只交给系统浏览器打开，本 App **不申请任何网络权限**。
 * `FLAG_ACTIVITY_NEW_TASK`：Compose 里拿到的 `context` 可能是
 * Non-Activity（如 Application context），不加这个 flag 会抛
 * `AndroidRuntimeException: Calling startActivity() from outside of an Activity`。
 * 设备上没有浏览器时 `runCatching` 兜住，不会崩溃。
 */
@Composable
private fun LinkRow(label: String, url: String) {
    val t = tokens()
    val context = LocalContext.current
    IOSTap(
        onClick = { openUrl(context, url) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        pressedOverlay = if (t.isDark) Color0x14 else Color0x0A,
    ) {
        Row(Modifier.fillMaxWidth().align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = t.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                "打开",
                color = t.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 用系统浏览器打开 URL；没有可用应用时静默失败（不崩） */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        LogBuffer.w("Settings", "无法打开链接：$url", it)
    }
}

/**
 * 设置页的「标题 —— 值」行。
 *
 * ⚠️ 这里原来用固定 `height(52.dp)`，长值（如「当前版本 1.1.2RC（测试版）」、
 * 「已收录 1874 部规范性文件」）在窄屏或大字号下超出可用宽度后被直接裁掉，
 * 既看不到省略号也读不全。改为：
 *  - 用 `heightIn(min = 52.dp)` 允许自动增高，不再硬裁
 *  - 值侧 `weight(1f)` 参与分配剩余宽度，放不下时补省略号
 */
@Composable
private fun InfoRow(title: String, value: String) {
    val t = tokens()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = t.textPrimary,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            value,
            color = t.textSecondary,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 日志预览面板（半屏底部抽屉）。
 *
 * 为什么需要：复制日志后用户（和开发者）需要确认「实际复制了什么」。
 * 分享面板的缩略图太小看不清日期，截图又不全，所以直接在这里可滚动展示全文。
 *
 * 顶部固定显示关键信息（导出时间/版本/设备/错误数），正文可滚动。
 */
@Composable
private fun LogPreviewPanel(
    text: String,
    onDismiss: () -> Unit,
    onCopyAgain: () -> Unit,
) {
    val t = tokens()

    // 从报告里提取关键字段做摘要（报告格式固定，见 LogBuffer.buildReport）
    fun field(prefix: String): String =
        text.lineSequence().firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.trim().orEmpty()

    val exportedAt = field("导出时间:")
    val version = field("版本:")
    val device = field("设备:")
    val android = field("系统:")
    val screen = field("屏幕:")
    val errorLine = text.lineSequence().firstOrNull { it.startsWith("--------- 错误摘要") }.orEmpty()
    val errorCount = Regex("共\\s*(\\d+)\\s*条").find(errorLine)?.groupValues?.get(1) ?: "?"

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0x66000000))
            .let { it },
    ) {
        // 点击遮罩关闭
        IOSTap(
            onClick = onDismiss,
            modifier = Modifier.fillMaxSize(),
            pressedOverlay = androidx.compose.ui.graphics.Color.Transparent,
            pressedScale = 1f,
        ) {}

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(androidx.compose.ui.graphics.Color.Transparent),
        ) {
            // 顶部把手
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(t.separator),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已复制的内容",
                    color = t.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                CapsuleButton(text = "再次复制", onClick = onCopyAgain)
                Spacer(Modifier.width(8.dp))
                CapsuleButton(text = "关闭", onClick = onDismiss)
            }

            IOSDivider(startIndent = 20.dp)

            // 关键信息摘要
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                InfoLine("导出时间", exportedAt)
                InfoLine("版本", version)
                InfoLine("设备", "$device  $android")
                InfoLine("屏幕", screen)
                InfoLine("错误数", "$errorCount 条")
            }

            IOSDivider(startIndent = 20.dp)

            // 全文（可滚动、可选中复制）
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    "完整内容",
                    color = t.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
                )
                SelectionContainer {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    ) {
                        item {
                            Text(
                                text = text,
                                color = t.textPrimary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    val t = tokens()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = t.textSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(
            value,
            color = t.textPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
