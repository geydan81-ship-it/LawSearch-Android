package com.lawsearch.app.ui.wallpaper

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 壁纸解码：按屏幕尺寸解码，绝不整张原图塞进内存。
 *
 * ## 为什么需要这个文件
 *
 * 之前 [WallpaperBackground] 用 `inSampleSize = 1` 全尺寸解码。内置壁纸都是
 * **1440 宽的 JPEG**（1440×2105 ~ 1440×3193），按 ARGB_8888 解码：
 * `1440 × 3193 × 4 ≈ 17.5 MB`。低内存设备上叠加 Compose 本身的开销很容易 OOM；
 * 一旦解码失败就落到兜底底色，用户看到的是「壁纸没了」。
 *
 * ## 为什么不能只靠 inSampleSize（重要）
 *
 * AOSP 明确规定 `inSampleSize` **只能是 2 的幂，非 2 的幂向下取整**：
 * > "the decoder uses a final value based on powers of 2, any other value will be
 * >  rounded down to the nearest power of 2."
 * > — [AOSP BitmapFactory.Options#inSampleSize](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/graphics/java/android/graphics/BitmapFactory.java)
 *
 * 我们的源图是 1440 宽、屏幕 1080 宽：
 * `1440 / 1080 = 1.33 → floor 1 → takeHighestOneBit(1) = 1`。
 * **inSampleSize 永远是 1，一点内存都省不下来。** 要让它变成 2，得把目标宽度降到
 * ≤ 720（屏幕宽度的一半），画质明显糊掉。
 *
 * 所以真正的杠杆有两个：
 *  1. `inPreferredConfig = RGB_565` —— 每像素 4 字节降到 2 字节，直接砍一半。
 *     壁纸下面还压着暗化层（scrim），565 的色带在暗化后基本不可见。
 *  2. `inScaled + inDensity/inTargetDensity` —— AOSP 里唯一能做**任意比例**缩放的机制，
 *     用于把 1440 宽的图精确解到我们想要的像素尺寸。
 *
 * `inSampleSize` 仍然保留，用于大屏/平板/高 DPI 设备兜底（那时屏宽可能 >720）。
 *
 * ## 算法来源
 *
 * [inSampleSize] / [sizeMultiplier] 直接移植自 **Coil**（Apache-2.0，
 * ★11k）的解码工具类，这两段是经过大量图片加载场景验证的实现：
 *  - `DecodeUtils.calculateInSampleSize` / `computeSizeMultiplier`
 *  - https://github.com/coil-kt/coil/blob/main/coil-core/src/commonMain/kotlin/coil3/decode/DecodeUtils.kt
 *  - RGB_565 降配也来自 `BitmapFactoryDecoder.configureConfig`：
 *    https://github.com/coil-kt/coil/blob/main/coil-core/src/androidMain/kotlin/coil3/decode/BitmapFactoryDecoder.kt
 *
 * 官方最佳实践：
 *  https://developer.android.com/topic/performance/graphics/load-bitmap
 *
 * 之所以不直接引入 Coil 依赖：本项目的壁纸是**打包在 APK 里的本地 drawable**，
 * 没有网络/磁盘缓存需求，[androidx.compose.runtime.remember] 已经保证不会重复解码，
 * 为这点需求引入完整图片加载库会增加包体和复杂度，得不偿失。
 */
object WallpaperDecode {

    /**
     * 高度不足屏幕高度的这个比例时，改为按高度对齐解码。
     *
     * 有些壁纸很「宽」（如 1440×2105，宽高比 0.68，而屏幕是 0.45）。若只按宽度缩到
     * 1080，高度只剩 1578，低于屏幕 2400 的 66%，`ContentScale.Crop` 会把它放大约
     * 1.5 倍，明显糊。这里设下限，宁可多解一点像素也不让放大超过 1.33 倍。
     */
    private const val MIN_HEIGHT_RATIO = 0.75f

    /**
     * 解码内置壁纸。
     *
     * @param targetWidth 期望的解码宽度（通常为屏幕宽，Crop 语义下 1:1 即可）
     * @param targetHeight 期望的解码高度（通常为屏幕高）
     */
    fun decodeResource(
        res: Resources,
        resId: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        if (resId == 0) return null

        // 第一遍：只读尺寸，不分配像素内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, resId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = buildOptions(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        return runCatching { BitmapFactory.decodeResource(res, resId, opts) }.getOrNull()
    }

    /** 解码用户自选图片（文件）。 */
    fun decodeFile(
        path: String,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = buildOptions(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }

    /**
     * 根据源尺寸与目标尺寸算出解码参数（第一遍 bounds 之后调用）。
     */
    fun buildOptions(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): BitmapFactory.Options {
        // 源尺寸非法（BitmapFactory 读取失败时给 -1）：无法推算目标，直接原样解码。
        // iss=1 保证不会算出荒谬的采样率。
        if (srcWidth <= 0 || srcHeight <= 0) {
            return BitmapFactory.Options().apply {
                inSampleSize = 1
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }
        }

        // 目标尺寸非法（拿不到屏幕尺寸）：退回源尺寸，等价于不缩放。
        val safeTargetW = if (targetWidth > 0) targetWidth else srcWidth
        val safeTargetH = if (targetHeight > 0) targetHeight else srcHeight

        // 先按「宽度优先」定目标：Crop 是高度对齐裁左右，宽度对齐已能铺满。
        var dstWidth = safeTargetW
        var dstHeight = (srcHeight.toLong() * dstWidth / srcWidth).toInt().coerceAtLeast(1)

        // 太矮的话改为高度对齐，避免 Crop 放大过头
        val minHeight = (safeTargetH * MIN_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        if (dstHeight < minHeight) {
            dstHeight = minHeight
            dstWidth = (srcWidth.toLong() * dstHeight / srcHeight).toInt().coerceAtLeast(1)
        }

        val sampleSize = calculateInSampleSize(srcWidth, srcHeight, dstWidth, dstHeight)
        val multiplier = sizeMultiplier(
            srcWidth = srcWidth / sampleSize.toDouble(),
            srcHeight = srcHeight / sampleSize.toDouble(),
            dstWidth = dstWidth.toDouble(),
            dstHeight = dstHeight.toDouble(),
        )

        return BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // 每像素 2 字节而非 4：壁纸上有暗化层，565 的色带在暗化后基本不可见。
            inPreferredConfig = Bitmap.Config.RGB_565
            // 缩放只缩不放（multiplier <= 1）；放大交给 Compose 的 Crop 在屏幕尺寸下做。
            inScaled = multiplier < 1.0
            if (inScaled) {
                inDensity = Int.MAX_VALUE
                inTargetDensity = (Int.MAX_VALUE * multiplier).toInt().coerceAtLeast(1)
            }
        }
    }

    /**
     * 移植自 Coil `DecodeUtils.calculateInSampleSize`（Apache-2.0）。
     *
     * 注意返回值一定是 2 的幂 —— 这是 BitmapFactory 的硬约束。
     */
    fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): Int {
        if (dstWidth <= 0 || dstHeight <= 0) return 1
        val widthInSampleSize = (srcWidth / dstWidth).takeHighestOneBit()
        val heightInSampleSize = (srcHeight / dstHeight).takeHighestOneBit()
        // FILL 语义：取较小值，保证解码结果仍能覆盖目标区域
        return minOf(widthInSampleSize, heightInSampleSize).coerceAtLeast(1)
    }

    /**
     * 移植自 Coil `DecodeUtils.computeSizeMultiplier`（FILL 语义）。
     *
     * 返回把源尺寸乘以多少能覆盖目标尺寸；>1 表示需要放大。
     */
    fun sizeMultiplier(
        srcWidth: Double,
        srcHeight: Double,
        dstWidth: Double,
        dstHeight: Double,
    ): Double {
        if (srcWidth <= 0.0 || srcHeight <= 0.0) return 1.0
        return maxOf(dstWidth / srcWidth, dstHeight / srcHeight)
    }
}
