package com.lawsearch.app.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 轻量设置（SharedPreferences 即可，不值得进 Room，也不会被清库影响）。
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lawsearch_prefs", Context.MODE_PRIVATE)

    /** 阅读器正文字号（sp），15–22 */
    var readerFontSize: Int
        get() = sp.getInt(KEY_FONT_SIZE, 17).coerceIn(15, 22)
        set(value) = sp.edit().putInt(KEY_FONT_SIZE, value.coerceIn(15, 22)).apply()

    /**
     * 正文字体。
     *
     * v1.1.2 起只剩 `system`（内置仿宋/楷体因字体版权已移除）。
     * 读到的旧值（`fangsong`/`kaiti`）会被安全降级为 `system`，
     * 避免老用户升级后拿到一个不存在的字体。
     */
    var bodyFontMode: String
        get() = sp.getString(KEY_BODY_FONT, BodyFont.SYSTEM).orEmpty().ifBlank { BodyFont.SYSTEM }
        set(value) = sp.edit().putString(KEY_BODY_FONT, value).apply()

    /** 壁纸选择（内置 id 或 "custom"） */
    var wallpaperId: String
        get() = sp.getString(KEY_WALLPAPER, "aurora").orEmpty().ifBlank { "aurora" }
        set(value) = sp.edit().putString(KEY_WALLPAPER, value).apply()

    /** 自定义壁纸文件路径 */
    var wallpaperPath: String?
        get() = sp.getString(KEY_WALLPAPER_PATH, null)
        set(value) = sp.edit().putString(KEY_WALLPAPER_PATH, value).apply()

    /** 液态玻璃效果开关（默认开；低端机卡顿时可关） */
    var glassEffects: Boolean
        get() = sp.getBoolean(KEY_GLASS, true)
        set(value) = sp.edit().putBoolean(KEY_GLASS, value).apply()

    /** 是否已提示过「测试版」说明 */
    var testNoticeShown: Boolean
        get() = sp.getBoolean(KEY_TEST_NOTICE, false)
        set(value) = sp.edit().putBoolean(KEY_TEST_NOTICE, value).apply()

    companion object {
        private const val KEY_FONT_SIZE = "reader_font_size"
        private const val KEY_BODY_FONT = "body_font_mode"
        private const val KEY_TEST_NOTICE = "test_notice_shown"
        private const val KEY_GLASS = "glass_effects"
        private const val KEY_WALLPAPER = "wallpaper_id"
        private const val KEY_WALLPAPER_PATH = "wallpaper_path"
    }
}

/**
 * 正文字体取值常量。
 *
 * ## ⚠️ 内置商业字体已移除（v1.1.2）
 *
 * `fangsong` / `kaiti` 原本对应内置的中易仿宋/楷体 TTF，
 * 因字体版权问题（`© Beijing ZhongYi Electronics`）已从 APK 中删除。
 *
 * **但常量必须保留**：老用户的 SharedPreferences 里存着 `"kaiti"` 之类的值，
 * 删掉常量会让 `label()` 走 else 分支看似无事，但将来若有人
 * 用 `BodyFont.all.indexOf(mode)` 定位下标就会得到 -1 → 越界崩溃。
 * 保留常量 + [all] 只列 [SYSTEM]，让旧值安全降级为「系统默认」。
 */
object BodyFont {
    const val SYSTEM = "system"

    /** @deprecated 内置仿宋已移除（字体版权），仅用于兼容老数据 */
    @Deprecated("内置仿宋已移除，恒降级为系统字体")
    const val FANGSONG = "fangsong"

    /** @deprecated 内置楷体已移除（字体版权），仅用于兼容老数据 */
    @Deprecated("内置楷体已移除，恒降级为系统字体")
    const val KAITI = "kaiti"

    /** 可选列表只剩系统字体（只有一个选项，UI 上不再展示选择器） */
    val all = listOf(SYSTEM)

    fun label(mode: String): String = mode.let {
        @Suppress("DEPRECATION")
        when (it) {
            // 老用户存了已下线的值 → 如实显示当前实际生效的字体
            FANGSONG, KAITI -> "系统默认"
            else -> "系统默认"
        }
    }
}

/** 阅读器设置（Compose 可观察，进入阅读器后退出再进仍然保持） */
object ReaderSettings {
    var fontSize by mutableStateOf(17)
    var bodyFontMode by mutableStateOf(BodyFont.SYSTEM)
}
