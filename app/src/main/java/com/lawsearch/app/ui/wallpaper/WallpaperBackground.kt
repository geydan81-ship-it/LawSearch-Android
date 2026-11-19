package com.lawsearch.app.ui.wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.lawsearch.app.ui.theme.tokens

/**
 * 全局壁纸背景。
 *
 * 图层（自下而上）：
 *   1. 壁纸（照片 / 渐变 / 用户图片）
 *   2. **暗化层（scrim）**：保证玻璃上的文字可读（HIG 建议 35%）
 *   3. 顶部柔光：给玻璃一点明暗层次可折射
 *
 * 照片壁纸通常有高光区域，需要比渐变更强的 scrim；
 * 具体强度在 [WallpaperSettings] 里可按需调。
 */
@Composable
fun WallpaperBackground(
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.34f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val wallpaper = WallpaperSettings.current
    val customFile = WallpaperSettings.customFile
    val context = LocalContext.current
    // 屏幕像素尺寸：壁纸按它解码，保证 1:1 而不是整张原图进内存。
    val screenSize = rememberScreenSizePx(context)

    Box(modifier.fillMaxSize()) {
        // ⚠️ 关键：**壁纸不可用时必须退回浅色底，不能是深色底**。
        //
        // 之前这里失败分支写的是 `Color(0xFF1C1C1E)`（近黑）。而 Theme.kt 用
        // `effectiveDark = 系统深色 || wallpaperOn` 决定令牌，壁纸一旦加载失败
        // 就落到「深色底 + 深色令牌」；再叠 0.5 的不透明黑 scrim，
        // 整屏就是纯黑 —— 用户看到的「壁纸全没了、背景变纯黑」正是这个组合。
        // HIG 的阅读环境是浅色，失败时退回浅灰纸感才不会让人觉得"东西丢了"。
        val fallback = if (tokens().isDark) Color(0xFF2C2C2E) else Color(0xFFEDEFF3)

        when {
            customFile != null && customFile.exists() ->
                CustomImageBackground(customFile, fallback, screenSize)

            wallpaper.isPhoto -> {
                // 运行时探测：发布包（不含内置壁纸）里找不到时返回 null，
                // 下面的 decode 会走 fallback 纯色，不会崩。
                val resId = remember(wallpaper.resName) {
                    wallpaper.resName?.let { WallpaperResources.find(context, it) }
                }
                // 按屏幕尺寸解码一次（内存友好）。
                // 详见 [WallpaperDecode]：inSampleSize 对 1440 宽源图恒为 1，真正的杠杆是
                // RGB_565 降配 + inScaled/inTargetDensity 精确缩放。
                val screenSize = rememberScreenSizePx(context)
                val bitmap = remember(resId, screenSize) {
                    // resId 为 null：该壁纸没打进这个 APK（发布包不含内置壁纸）
                    if (resId == null) null
                    else WallpaperDecode.decodeResource(
                        res = context.resources,
                        resId = resId,
                        targetWidth = screenSize.first,
                        targetHeight = screenSize.second,
                    )
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(fallback))
                }
            }

            else -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                // Brush 要求至少 2 个颜色；纯色壁纸会只给 1 个，这里补齐
                                colors = run {
                                    val c = wallpaper.colors.ifEmpty { listOf(Color(0xFF1C1C1E)) }
                                    if (c.size >= 2) c else listOf(c[0], c[0])
                                },
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            ),
                        )
                        .drawBehind { drawRadialGlow() },
                )
            }
        }

        // 暗化层：照片壁纸需要更强，否则白色区域会让文字读不清。
        //
        // ⚠️ 这里曾经写成 `scrimAlpha + 0.16f`，调用方传 0.30 → 照片壁纸实际 0.46，
        // 再叠上 Theme 的深色令牌，壁纸几乎被压成纯黑。
        // HIG 的「背景暗化」推荐值是 35% 左右（用于保证前景文字对比度），
        // 照片壁纸叠加后上限控制在 0.42，既能读清文字又**还能看出是壁纸**。
        val effective = if (wallpaper.isPhoto) {
            (scrimAlpha + 0.10f).coerceAtMost(0.42f)
        } else {
            scrimAlpha
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = effective.coerceIn(0f, 0.60f))))

        content()
    }
}

@Composable
private fun CustomImageBackground(
    file: java.io.File,
    fallback: Color,
    screenSize: Pair<Int, Int>,
) {
    // 用户自选图片同样是全尺寸来源（可能 4000×3000），必须按屏幕尺寸解码。
    // 旧实现写死 inSampleSize = 2，对 4000×3000 仍会解出 2000×1500 ≈ 12 MB。
    val bitmap = remember(file.absolutePath, file.lastModified(), screenSize) {
        WallpaperDecode.decodeFile(
            path = file.absolutePath,
            targetWidth = screenSize.first,
            targetHeight = screenSize.second,
        )
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(Modifier.fillMaxSize().background(fallback))
    }
}

private fun DrawScope.drawRadialGlow() {
    val center = Offset(size.width * 0.25f, size.height * 0.18f)
    val radius = size.maxDimension * 0.75f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * 屏幕真实像素尺寸（宽, 高）。
 *
 * 用 [android.util.DisplayMetrics]，它包含导航栏占用的整块屏幕。
 * 不依赖 Activity —— 这个 Composable 可能在预览或非 Activity 上下文里跑。
 */
@Composable
private fun rememberScreenSizePx(context: android.content.Context): Pair<Int, Int> {
    return remember(context) {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        // 兜底：拿不到尺寸时用当前手边这台设备的尺寸，避免后续除零
        if (w <= 0 || h <= 0) 1080 to 2400 else w to h
    }
}
