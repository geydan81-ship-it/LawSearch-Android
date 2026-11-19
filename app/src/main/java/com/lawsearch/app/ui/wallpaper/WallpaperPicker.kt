package com.lawsearch.app.ui.wallpaper

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lawsearch.app.ui.components.IOSCard
import com.lawsearch.app.ui.components.IOSDivider
import com.lawsearch.app.ui.components.IOSTap
import com.lawsearch.app.ui.components.MetaText
import com.lawsearch.app.ui.theme.tokens

/**
 * 壁纸选择器。
 *
 * 为什么要有：液态玻璃在纯白/纯黑背景上前几乎看不出来。
 * iOS 的做法是让内容浮在**视觉丰富的背景**上（壁纸），玻璃才有折射与高光可表现。
 * 这里提供几套内置渐变 + 从相册自选，并在壁纸过亮时自动加暗化层保证可读。
 */
@Composable
fun WallpaperPicker() {
    val t = tokens()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val state = WallpaperState.fromUri(context, uri)
        if (state != null) {
            WallpaperSettings.setCustom(state.file)
            message = "已设置自定义壁纸"
        } else {
            message = "图片读取失败，请换一张试试"
        }
    }

    IOSCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("全局壁纸", color = t.textPrimary, fontSize = 17.sp)
                    MetaText("液态玻璃需要丰富背景才有折射效果", Modifier.padding(top = 2.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 内置壁纸横向列表
            //
            // ⚠️ 发布到 GitHub Releases 的 APK **不含内置壁纸**（版权原因，14 张图含
            // BanG Dream!、初音未来等商业 IP）。此时这里必须过滤掉，否则会显示
            // 14 个解不出来的黑块，看起来像 App 坏了。
            // 只保留「相册自选」入口。
            val pickerContext = LocalContext.current
            val builtin = remember { Wallpapers.builtIn.filter { wp ->
                !wp.isPhoto || WallpaperResources.find(pickerContext, wp.resName!!) != null
            } }
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(builtin) { wp ->
                    val selected = !WallpaperSettings.hasCustom && WallpaperSettings.currentId == wp.id
                    WallpaperChip(wp, selected) {
                        WallpaperSettings.selectBuiltIn(wp.id)
                        message = null
                    }
                }
                item {
                    // 「相册」入口
                    val selected = WallpaperSettings.hasCustom
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 88.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x22FFFFFF))
                            .then(
                                if (selected) Modifier.border(2.dp, t.primary, RoundedCornerShape(12.dp))
                                else Modifier
                            ),
                    ) {
                        IOSTap(
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(88.dp),
                            pressedScale = 0.94f,
                        ) {
                            Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("🖼", fontSize = 20.sp)
                                Text("相册", color = t.textPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (WallpaperSettings.hasCustom) {
                Spacer(Modifier.height(10.dp))
                IOSDivider(startIndent = 16.dp)
                IOSTap(
                    onClick = {
                        WallpaperSettings.clearCustom()
                        message = "已恢复内置壁纸"
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                    pressedScale = 0.98f,
                ) {
                    Text(
                        "移除自定义壁纸",
                        color = t.badgeRevoked,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun WallpaperChip(wp: Wallpaper, selected: Boolean, onClick: () -> Unit) {
    val t = tokens()
    val context = LocalContext.current
    val chipShape = RoundedCornerShape(12.dp)

    // 照片壁纸要显示真实缩略图。
    // 之前只渲染 wp.colors（渐变），照片壁纸的 colors 为空 → 回退成纯黑矩形，
    // 所以 5 个缩略图全是黑的。
    val thumb = remember(wp.resName) {
        val name = wp.resName ?: return@remember null
        // 运行时探测：发布包不含内置壁纸时返回 null（不会因此编译失败）
        val id = WallpaperResources.find(context, name) ?: return@remember null
        runCatching {
            BitmapFactory.decodeResource(context.resources, id)
        }.getOrNull()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 64.dp, height = 88.dp)
                .clip(chipShape)
                .then(
                    if (thumb != null) {
                        Modifier
                    } else {
                        Modifier.background(
                            Brush.linearGradient(
                                // 至少 2 个颜色，否则 Brush 抛异常
                                run {
                                    val c = wp.colors.ifEmpty { listOf(Color(0xFF2A2A2E), Color(0xFF4A4A50)) }
                                    if (c.size >= 2) c else listOf(c[0], c[0])
                                },
                            ),
                        )
                    },
                )
                .then(
                    if (selected) Modifier.border(2.dp, t.primary, chipShape) else Modifier,
                ),
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = wp.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            IOSTap(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(88.dp),
                pressedScale = 0.94f,
                pressedOverlay = Color.Transparent,
            ) {}
        }
        Spacer(Modifier.height(4.dp))
        Text(
            wp.name,
            color = if (selected) t.primary else t.textSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
