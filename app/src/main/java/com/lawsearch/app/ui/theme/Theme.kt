package com.lawsearch.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 主题入口：跟随系统深浅色，把 iOS 令牌注入 CompositionLocal，
 * 并关闭 Material 的水波纹（iOS 没有 ripple）。
 */
@Composable
fun LawSearchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 内置壁纸改成运行时探测后，判断「壁纸是否可用」需要 resources
    val context = LocalContext.current
    // 令牌明暗只看**壁纸是否真的在画**，不再和「液态玻璃开关」绑在一起。
    //
    // ⚠️ 曾经的写法是：
    //   effectiveDark = darkTheme || wallpaperOn
    //   wallpaperOn  = WallpaperSettings.enabled && LocalGlassEffectsEnabled && isUsable()
    //
    // 问题：用户关掉「液态玻璃效果」时 LocalGlassEffectsEnabled 变 false → wallpaperOn 变 false
    // → 切成**浅色令牌**；但壁纸本身是独立开关（WallpaperSettings.enabled），仍在绘制，
    // 于是「浅底深字 + 深色壁纸」——文字几乎看不见。
    //
    // 现在的解耦规则：
    //  - 壁纸在画（enabled 且资源可用）→ 必须用**深色令牌**（浅色文字），保证照片/渐变上可读
    //  - 壁纸不画 → 跟随系统深浅色
    // 「液态玻璃」只影响毛玻璃/折射效果，不该影响文字配色。
    val wallpaperVisible = com.lawsearch.app.ui.wallpaper.WallpaperSettings.enabled &&
        com.lawsearch.app.ui.wallpaper.WallpaperSettings.isUsable(context)
    val effectiveDark = wallpaperVisible || darkTheme
    val token = if (effectiveDark) DarkTokens else LightTokens

    val colorScheme = if (effectiveDark) {
        darkColorScheme(
            primary = token.primary,
            onPrimary = Color.White,
            background = token.background,
            onBackground = token.textPrimary,
            surface = token.card,
            onSurface = token.textPrimary,
            surfaceVariant = token.cardElevated,
            onSurfaceVariant = token.textSecondary,
            outline = token.separator,
        )
    } else {
        lightColorScheme(
            primary = token.primary,
            onPrimary = Color.White,
            background = token.background,
            onBackground = token.textPrimary,
            surface = token.card,
            onSurface = token.textPrimary,
            surfaceVariant = token.cardElevated,
            onSurfaceVariant = token.textSecondary,
            outline = token.separator,
        )
    }

    // 界面文字用系统字体（接近 SF Pro 的观感）；法规正文另有楷体/仿宋/黑体
    val typography = Typography(
        headlineLarge = TextStyle(fontSize = IOS.largeTitle, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = IOS.cardTitle, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 17.sp),
        bodyMedium = TextStyle(fontSize = IOS.subtitle),
        labelSmall = TextStyle(fontSize = IOS.cardMeta),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // 边到边：内容绘制到状态栏/导航栏后面，底部 TabBar 用 insets 内边距避让
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !effectiveDark
            controller.isAppearanceLightNavigationBars = !effectiveDark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
        }
    }

    CompositionLocalProvider(LocalTokens provides token) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
