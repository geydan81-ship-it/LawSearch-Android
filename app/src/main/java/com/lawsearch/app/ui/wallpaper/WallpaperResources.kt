package com.lawsearch.app.ui.wallpaper

import android.content.Context
import androidx.annotation.DrawableRes

/**
 * 内置壁纸的资源查找表。
 *
 * ## 历史：为什么这里曾经是「静态 R.drawable.wp_NN 引用表」
 *
 * 壁纸最初用 `resources.getIdentifier(name, "drawable", pkg)` 动态查找。
 * R8 的资源压缩器（`isShrinkResources = true`）只认**静态引用**，
 * 检测不到 `getIdentifier` 的字符串，于是把 `drawable-nodpi/wp_*.jpg` **全部删掉**，
 * 导致 APK 里 wp_ 文件数 = 0、App 壁纸全部消失、背景变纯黑（1.1.2RC 真实事故）。
 *
 * 当时的修法是在 `WallpaperResources.all` 里真实写下每个 `R.drawable.wp_NN`。
 *
 * ## 现在：改成「运行时探测 + 可缺省」
 *
 * 发到 GitHub Releases 的 APK **不内置壁纸**（14 张壁纸含 BanG Dream!、初音未来
 * 等商业动漫 IP，直接分发有版权问题），构建前会移走 `drawable-nodpi/wp_*.jpg`。
 *
 * 一旦壁纸文件不存在，`R.drawable.wp_NN` 会**编译失败**（资源符号未定义）。
 * 所以这里改回运行时查找：
 *
 * ```kotlin
 * context.resources.getIdentifier("wp_01", "drawable", packageName)  // 不存在返回 0
 * ```
 *
 * 好处：壁纸在与不在都能编译，**本地开发仍能正常显示内置壁纸**。
 * 代价：R8 可能会压缩掉壁纸资源 —— 但对发布包无所谓（本来就不含壁纸），
 * 而本地 debug 构建默认不开 shrinkResources，壁纸正常。
 */
object WallpaperResources {

    /** 内置壁纸的数量（编号 wp_01..wp_14） */
    const val COUNT = 14

    /** 资源名列表，如 "wp_01" */
    val names: List<String> = (1..COUNT).map { "wp_%02d".format(it) }

    /**
     * 按资源名查找 drawable id。
     *
     * @return 资源 id；该壁纸**未打包进本 APK** 时返回 `null`。
     */
    @DrawableRes
    fun find(context: Context, name: String): Int? {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else null
    }

    /**
     * 当前 APK 里**实际存在**的壁纸资源名（顺序与编号一致）。
     *
     * 发布包（不含壁纸）会返回空列表，此时设置页应隐藏内置壁纸选择区，
     * 只保留「从相册选择」。
     */
    fun availableNames(context: Context): List<String> =
        names.filter { find(context, it) != null }

    /** 是否内置了任何壁纸 */
    fun hasBuiltin(context: Context): Boolean =
        names.any { find(context, it) != null }
}
