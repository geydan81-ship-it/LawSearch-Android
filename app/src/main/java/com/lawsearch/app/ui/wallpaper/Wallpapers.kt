package com.lawsearch.app.ui.wallpaper

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.io.File

/**
 * 壁纸。
 *
 * 设计依据（Apple HIG「Materials」）：
 *  - 液态玻璃只在**视觉丰富的背景**上才有意义，纯白背景等于没有玻璃
 *  - 背景过亮时要叠加暗化层保证前景可读（HIG 建议 35% dimming）
 *  - 玻璃不该出现在**内容层**（正文阅读区），所以阅读器用纯色背景
 *
 * 壁纸来源：
 *  1. 内置照片壁纸（res/drawable-nodpi/wp_XX.jpg，用户提供的二次元壁纸）
 *  2. 内置渐变（备用，资源占用小）
 *  3. 用户从相册自选
 */
object Wallpapers {

    /** 内置照片壁纸：按序号对应 res/drawable-nodpi/wp_NN.jpg */
    val photos: List<Wallpaper> = listOf(
        Wallpaper.Photo("p01", "复古"),
        Wallpaper.Photo("p02", "祥子"),
        Wallpaper.Photo("p03", "DeepSeek"),
        Wallpaper.Photo("p04", "书架"),
        Wallpaper.Photo("p05", "书与房间"),
        Wallpaper.Photo("p06", "初音未来"),
        Wallpaper.Photo("p07", "动漫"),
        Wallpaper.Photo("p08", "动漫人物"),
        Wallpaper.Photo("p09", "动漫少女"),
        Wallpaper.Photo("p10", "动漫少女 2"),
        Wallpaper.Photo("p11", "小王子"),
        Wallpaper.Photo("p12", "咖啡厅"),
        Wallpaper.Photo("p13", "伊蕾娜"),
        Wallpaper.Photo("p14", "插画"),
    )

    /** 内置渐变（照片壁纸之外的备选） */
    val gradients: List<Wallpaper> = listOf(
        Wallpaper.Gradient(
            "g_aurora", "极光",
            listOf(Color(0xFF1A0B2E), Color(0xFF2D1B69), Color(0xFF11998E), Color(0xFF38EF7D)),
        ),
        Wallpaper.Gradient(
            "g_sunset", "落日",
            listOf(Color(0xFFFF6B6B), Color(0xFFFFA07A), Color(0xFF9B59B6), Color(0xFF4A235A)),
        ),
        Wallpaper.Gradient(
            "g_sakura", "樱花",
            listOf(Color(0xFFFFC0CB), Color(0xFFDDA0DD), Color(0xFF9370DB), Color(0xFF4A235A)),
        ),
    )

    val builtIn: List<Wallpaper> get() = photos + gradients

    fun byId(id: String): Wallpaper? =
        builtIn.firstOrNull { it.id == id }

    val default: Wallpaper get() = photos.first()
}

/**
 * 一套壁纸。
 *
 * @param resName 资源名（照片壁纸）；为 null 表示渐变
 */
data class Wallpaper(
    val id: String,
    val name: String,
    /** drawable 资源名，如 "wp_01"；渐变壁纸为 null */
    val resName: String? = null,
    /** 渐变颜色；照片壁纸为空 */
    val colors: List<Color> = emptyList(),
) {
    val isPhoto: Boolean get() = resName != null

    companion object {
        /** id 形如 "p01"，对应 res/wp_01.jpg */
        fun Photo(seqId: String, name: String): Wallpaper =
            Wallpaper(id = seqId, name = name, resName = "wp_" + seqId.removePrefix("p"))

        /** 渐变壁纸 */
        fun Gradient(id: String, name: String, colors: List<Color>): Wallpaper =
            Wallpaper(id = id, name = name, colors = colors)
    }
}

/** 当前壁纸状态 */
sealed interface WallpaperState {
    data class BuiltIn(val wallpaper: Wallpaper) : WallpaperState
    data class Custom(val file: File) : WallpaperState

    companion object {
        fun fromUri(context: Context, uri: Uri): Custom? = runCatching {
            val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
            val out = File(dir, "custom.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.exists() && out.length() > 0) Custom(out) else null
        }.getOrNull()
    }
}

/**
 * 壁纸设置的 Compose 可观察状态（单例：Theme 与设置页共享）。
 */
object WallpaperSettings {

    /** 当前内置壁纸 id；选了自定义图片时为 null */
    var currentId by mutableStateOf(Wallpapers.default.id)

    /** 自定义壁纸文件（存在时优先使用） */
    var customFile by mutableStateOf<File?>(null)

    /** 是否启用壁纸（关闭后退回纯色） */
    var enabled by mutableStateOf(true)

    val current: Wallpaper get() = Wallpapers.byId(currentId) ?: Wallpapers.default

    val hasCustom: Boolean get() = customFile?.exists() == true

    /**
     * 当前壁纸是否**真的能显示**。
     *
     * 用于 Theme 判断要不要切深色令牌：如果壁纸资源被删/自定义图片丢失，
     * 界面会变成「纯黑背景 + 浅色文字」，看起来像「壁纸全没了、背景变黑」。
     * 这时应该退回系统深浅色，而不是硬撑深色。
     *
     * ⚠️ 调用方可能在**组合阶段每一帧**调用本方法（Theme.kt），
     * 所以这里只做内存内的映射表判断，**绝不做 IO / 资源解码**。
     * 图片文件是否存在用 [hasCustom] 的 File.exists()，代价可接受。
     */
    /**
     * 当前壁纸是否可用（能否真的画出来）。
     *
     * 需要 `context` 是因为内置壁纸改成了**运行时探测**：
     * 发布包不含壁纸资源，此时照片壁纸不可用，应回退到纯色背景。
     */
    fun isUsable(context: android.content.Context): Boolean {
        if (hasCustom) return true
        val wp = current
        // 渐变壁纸总有颜色，一定可用
        if (!wp.isPhoto) return wp.colors.isNotEmpty()
        // 照片壁纸要能在当前 APK 里真的找到资源
        return resIdOf(context, wp) != null
    }

    /** 壁纸对应的 drawable 资源 ID；找不到返回 null（渐变壁纸、资源未打包） */
    fun resIdOf(context: android.content.Context, wp: Wallpaper): Int? =
        wp.resName?.let { WallpaperResources.find(context, it) }

    /** 由 LawApp 注入，用于持久化 */
    internal var prefs: com.lawsearch.app.util.Prefs? = null

    /** 启动时从 Prefs 恢复；旧版 id 失效时回落到默认 */
    fun restore() {
        val p = prefs ?: return
        val savedId = p.wallpaperId
        currentId = if (Wallpapers.byId(savedId) != null) savedId else Wallpapers.default.id
        val path = p.wallpaperPath
        customFile = path?.let { File(it).takeIf { f -> f.exists() } }
        enabled = true
    }

    fun selectBuiltIn(id: String) {
        if (Wallpapers.byId(id) == null) return
        currentId = id
        customFile = null
        prefs?.apply {
            wallpaperId = id
            wallpaperPath = null
        }
    }

    fun setCustom(file: File) {
        customFile = file
        prefs?.wallpaperPath = file.absolutePath
    }

    fun clearCustom() {
        customFile = null
        prefs?.wallpaperPath = null
    }
}
