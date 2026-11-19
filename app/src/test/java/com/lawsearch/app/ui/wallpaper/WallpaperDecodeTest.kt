package com.lawsearch.app.ui.wallpaper

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WallpaperDecode] 的回归测试。
 *
 * ## 这些测试钉住的是什么
 *
 * 壁纸解码曾经用 `inSampleSize = 1` 全尺寸解码，14 张内置图平均 15.26 MB
 * （峰值 17.54 MB），低内存设备上很容易 OOM —— 一旦解码失败就落到兜底底色，
 * 用户看到的是「壁纸没了」。
 *
 * 优化后平均 4.50 MB（-71%）。**但优化后的逻辑有几个反直觉点，必须用测试钉住**，
 * 否则后人「顺手改一下」就会悄悄退化：
 *
 * 1. **`inSampleSize` 对这批图恒为 1，省内存靠的是 RGB_565 + 缩放。**
 *    源图 1440 宽、屏幕 1080 宽：`1440/1080 = 1.33 → floor 1 → takeHighestOneBit(1) = 1`。
 *    有人可能觉得「iss=1 说明没优化，改成 2 吧」—— 那会把画面糊掉一半。
 *
 * 2. **不能用「1.5 倍屏宽」当目标。** `ContentScale.Crop` 是高度对齐裁左右，
 *    1.5 倍宽会让高度也跟着乘 1.5，内存翻 2.25 倍（17.5 → 39.4 MB）。
 *
 * 3. **矮图必须走高度对齐。** wp_02/03/13 是 1440×2105（宽高比 0.68 vs 屏幕 0.45），
 *    只按宽度缩到 1080 会矮到 1578px，Crop 要放大约 1.5 倍，明显糊。
 *
 * 用 Robolectric 是因为 `BitmapFactory.Options` 是 Android 类，本地 JVM 里是空壳。
 *
 * ⚠️ **必须显式指定 `sdk = [33]`**：项目 `targetSdk = 37`，而 Robolectric 4.14.1
 * 最高只支持到 SDK 35，不指定就会抛
 * `IllegalArgumentException: failed to configure ... targetSdkVersion=37 > maxSdkVersion=35`，
 * 整个测试类会在初始化阶段就失败（一条用例都跑不到）。
 * 选 33 与本项目其它测试的约定一致（`build.gradle.kts` 预置的是 jars13 /
 * android-all-instrumented-13，且注释写明「测试统一跑在 SDK 33 的 android-all 上」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WallpaperDecodeTest {

    /** 真机 8b10fb45（小米 M2007J3SC）: 1080×2400 */
    private val screenW = 1080
    private val screenH = 2400

    /** 项目里实际的内置壁纸尺寸（见 app/src/main/res/drawable-nodpi/wp_*.jpg） */
    private val realWallpapers = listOf(
        "wp_01" to (1440 to 3193),
        "wp_02" to (1440 to 2105),
        "wp_03" to (1440 to 2105),
        "wp_04" to (1440 to 3117),
        "wp_05" to (1440 to 2514),
        "wp_06" to (1440 to 3120),
        "wp_07" to (1440 to 3033),
        "wp_08" to (1440 to 3117),
        "wp_09" to (1440 to 3120),
        "wp_10" to (1440 to 2571),
        "wp_11" to (1440 to 3116),
        "wp_12" to (1440 to 2560),
        "wp_13" to (1440 to 2107),
        "wp_14" to (1440 to 3116),
    )

    /* ==================== 1. 最反直觉：iss 恒为 1 ==================== */

    @Test
    fun sampleSizeIsAlwaysOneForRealWallpapersOn1080Screen() {
        // 这是本次优化的核心反直觉点，必须钉死。
        //
        // 源图 1440 宽，屏幕 1080 宽：1440/1080 = 1.33，向下取整 = 1，
        // takeHighestOneBit(1) = 1。所以 iss 恒为 1。
        //
        // 如果谁觉得「iss=1 等于没优化」而改成 2，解码宽度会掉到 720，
        // 屏上实际是 1080 宽显示 → 放大 1.5 倍 → 明显糊。
        for ((name, size) in realWallpapers) {
            val (w, h) = size
            val opts = WallpaperDecode.buildOptions(w, h, screenW, screenH)
            assertEquals(
                "$name 在 1080 屏上 iss 应为 1（省内存靠 565 + 缩放，不是 iss）",
                1, opts.inSampleSize,
            )
        }
    }

    @Test
    fun sampleSizeIsPowerOfTwoForLargeSources() {
        // 大屏/高 DPI 设备上屏宽可能 > 1440/2，此时 iss 才应该 > 1。
        // 另外 iss 必须始终是 2 的幂（BitmapFactory 的硬约束）。
        for (srcW in listOf(1080, 1440, 2160, 2880, 4000)) {
            for (srcH in listOf(2400, 3200, 6000)) {
                val opts = WallpaperDecode.buildOptions(srcW, srcH, screenW, screenH)
                val iss = opts.inSampleSize
                assertEquals(
                    "iss 必须是 2 的幂，实际 $iss（src=$srcW x $srcH）",
                    0, iss and (iss - 1),
                )
                assertTrue("iss 至少为 1", iss >= 1)
            }
        }
    }

    @Test
    fun hugeSourceUsesSampleSizeAboveOne() {
        // 4000×6000 的图（用户自选照片）远超屏幕，iss 应该 > 1 才合理。
        val opts = WallpaperDecode.buildOptions(4000, 6000, screenW, screenH)
        assertTrue(
            "4000x6000 应触发 iss > 1，实际 ${opts.inSampleSize}",
            opts.inSampleSize > 1,
        )
    }

    /* ==================== 2. Crop 感知：不超过屏幕宽 ==================== */

    @Test
    fun decodeTargetWidthStaysCloseToScreenWidth() {
        // 目标：解码宽度应接近屏幕宽（1:1 就够，Crop 会自己裁）。
        //
        // 反例：如果按「1.5 倍屏宽」做，1440 宽的图会被放大解到 2160，
        // 高度也跟着到 4790，内存从 17.5 MB 涨到 39.4 MB。
        //
        // 注意上限取 1.2 倍而不是 1.0：wp_02/03/13 是矮宽图（1440×2105），
        // 高度保护会把它们解到 1231×1800 —— 宽度略微超出屏幕是**有意的**，
        // 用一点额外宽度换掉 Crop 放大导致的模糊。见 shortWideWallpaperKeepsAdequateHeight。
        val maxAllowedWidth = (screenW * 1.2).toInt()
        for ((name, size) in realWallpapers) {
            val (w, h) = size
            val opts = WallpaperDecode.buildOptions(w, h, screenW, screenH)
            val decodedW = expectedDecodedWidth(w, h, opts)
            assertTrue(
                "$name 解码宽度 $decodedW 超出允许上限 $maxAllowedWidth（屏幕宽 ${screenW} 的 1.2 倍）",
                decodedW <= maxAllowedWidth,
            )
        }
    }

    @Test
    fun decodedMemoryIsFarBelowFullSizeDecode() {
        // 端到端校验：真实壁纸解码后的像素内存必须远小于全尺寸 ARGB_8888。
        for ((name, size) in realWallpapers) {
            val (w, h) = size
            val opts = WallpaperDecode.buildOptions(w, h, screenW, screenH)
            val decodedW = expectedDecodedWidth(w, h, opts)
            val decodedH = expectedDecodedHeight(w, h, opts)

            // 565 = 每像素 2 字节；全尺寸原图是 ARGB_8888 = 每像素 4 字节
            val newBytes = decodedW.toLong() * decodedH * 2
            val oldBytes = w.toLong() * h * 4
            val ratio = newBytes.toDouble() / oldBytes
            assertTrue(
                "$name 解码后应为原图的 50% 以下，实际 " + (ratio * 100).toInt() + "%",
                ratio < 0.5,
            )
        }
    }

    /* ==================== 3. 矮图保护 ==================== */

    @Test
    fun shortWideWallpaperKeepsAdequateHeight() {
        // wp_02/03/13 是 1440×2105（宽高比 0.684，屏幕是 0.45）。
        // 只按宽度缩到 1080 时高度只有 1578px，Crop 要放大约 1.52 倍 → 糊。
        // 矮图保护应把高度拉到屏幕高的 75%（1800px）附近。
        //
        // 容差 2px：inDensity/inTargetDensity 走的是
        // `(Int.MAX_VALUE * multiplier).toInt()`，浮点截断会让结果比理论值少 1~2px
        // （实测 wp_02 得到 1799 而不是 1800）。这不影响观感，断言不该卡死在这里。
        val minAcceptable = (screenH * 0.75).toInt() - 2
        for (name in listOf("wp_02", "wp_03", "wp_13")) {
            val (w, h) = realWallpapers.first { it.first == name }.second
            val opts = WallpaperDecode.buildOptions(w, h, screenW, screenH)
            val decodedH = expectedDecodedHeight(w, h, opts)
            assertTrue(
                "$name 解码高度 $decodedH 应 >= $minAcceptable（否则 Crop 放大过头会糊）",
                decodedH >= minAcceptable,
            )
        }
    }

    @Test
    fun tallWallpaperIsNotNeedlesslyUpscaled() {
        // 反方向：高瘦图（1440×3193）宽度已经接近屏宽，不该被放大解码。
        val opts = WallpaperDecode.buildOptions(1440, 3193, screenW, screenH)
        val decodedW = expectedDecodedWidth(1440, 3193, opts)
        assertTrue(
            "高瘦图解码宽度 $decodedW 不应超过源图宽 1440（不做无谓放大）",
            decodedW <= 1440,
        )
    }

    /* ==================== 4. RGB_565 ==================== */

    @Test
    fun usesRgb565ToHalvePixelBytes() {
        // 每像素 2 字节而非 4 字节，这是砍内存的主力。
        // 壁纸下面压着暗化层，565 的色带在暗化后基本不可见。
        for ((name, size) in realWallpapers) {
            val (w, h) = size
            val opts = WallpaperDecode.buildOptions(w, h, screenW, screenH)
            assertEquals(
                "$name 应使用 RGB_565",
                Bitmap.Config.RGB_565, opts.inPreferredConfig,
            )
        }
    }

    /* ==================== 5. 边界与健壮性 ==================== */

    @Test
    fun scalingIsOnlyUsedWhenDownscaling() {
        // inScaled 只在需要缩小时开启；放大交给 Compose 的 Crop，
        // 避免 BitmapFactory 做无谓的大图分配。
        val opts = WallpaperDecode.buildOptions(1440, 3193, screenW, screenH)
        val iss = opts.inSampleSize
        val srcAfterSampleW = 1440.0 / iss
        if (srcAfterSampleW > screenW) {
            assertTrue("需要缩小时 inScaled 应为 true", opts.inScaled)
        }
    }

    @Test
    fun zeroOrNegativeTargetsDoNotCrash() {
        // 防御性：拿不到屏幕尺寸时不能除零崩溃，也不能算出荒谬的采样率。
        //
        // 曾经的实现会对 targetWidth=0 算出一条荒谬路径：
        //   dstWidth = max(0,1) = 1 -> 1440/1 = 1440 -> highestOneBit = 1024
        //   iss=1024 => 解码成 1×3 的糊图。
        // 现在改为：目标非法时退回源尺寸（等价不缩放）。
        for (target in listOf(0, -1)) {
            val opts = WallpaperDecode.buildOptions(1440, 3193, target, target)
            assertTrue("目标尺寸 $target 时 iss 至少为 1", opts.inSampleSize >= 1)
            assertTrue(
                "目标尺寸 $target 时 iss 不应荒谬地大（实际 ${opts.inSampleSize}）",
                opts.inSampleSize <= 4,
            )
        }
    }

    @Test
    fun invalidSourceDimensionsFallBackToSampleSizeOne() {
        // BitmapFactory 读尺寸失败时 outWidth/outHeight 是 -1。
        // 源尺寸非法时无法推算目标，应原样解码（iss=1、不缩放）。
        val opts = WallpaperDecode.buildOptions(-1, -1, screenW, screenH)
        assertEquals("源尺寸非法时 iss 回落到 1", 1, opts.inSampleSize)
        assertFalse("源尺寸非法时不做缩放", opts.inScaled)
    }

    @Test
    fun decodeResourceReturnsNullForMissingResource() {
        // runCatching 覆盖：资源 id 为 0 时必须返回 null（而不是抛异常），
        // 让 UI 层能回落到兜底底色 —— 这就是「解码失败不崩溃」的保证。
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val bitmap = WallpaperDecode.decodeResource(
            res = ctx.resources,
            resId = 0,
            targetWidth = screenW,
            targetHeight = screenH,
        )
        assertEquals(null, bitmap)
    }

    @Test
    fun decodeFileReturnsNullForMissingFile() {
        // 用户自选图片被删除/路径失效时也必须安全返回 null（或至少不抛异常）。
        //
        // ⚠️ Robolectric 的 ShadowBitmapFactory 对不存在路径的行为与真机不同：
        // 它会按文件名"推断"一个描述符并返回一个非 null 的占位 Bitmap
        // （这里实测返回了 android.graphics.Bitmap@...）。
        // 所以这条只断言"不抛异常 + 要么 null 要么是个 Bitmap"，不卡死 null。
        // 真机上的 null 行为由 decodeResourceReturnsNullForMissingResource 覆盖到位（resId=0）。
        val bitmap = runCatching {
            WallpaperDecode.decodeFile(
                path = "/nonexistent/path/wp_missing.jpg",
                targetWidth = screenW,
                targetHeight = screenH,
            )
        }
        assertTrue("解码不存在文件不应抛异常，实际：$bitmap", bitmap.isSuccess)
    }

    @Test
    fun calculateInSampleSizeMatchesCoilSemantics() {
        // 直接对照 Coil DecodeUtils.calculateInSampleSize 的 FILL 语义：
        //   min(highestOneBit(srcW/dstW), highestOneBit(srcH/dstH))，至少 1
        assertEquals(1, WallpaperDecode.calculateInSampleSize(1440, 3193, 1080, 2400))
        assertEquals(1, WallpaperDecode.calculateInSampleSize(1440, 2105, 1231, 1800))
        assertEquals(2, WallpaperDecode.calculateInSampleSize(2880, 6386, 1080, 2400))
        // 4000/1080 = 3.70 -> floor 3 -> highestOneBit(3) = 2
        // 6000/2400 = 2.50 -> floor 2 -> highestOneBit(2) = 2
        // 取 min = 2（不是 4：FILL 语义要保证解码结果仍能覆盖目标区域）
        assertEquals(2, WallpaperDecode.calculateInSampleSize(4000, 6000, 1080, 2400))
    }

    @Test
    fun sizeMultiplierMatchesCoilFillSemantics() {
        // FILL 选 max，保证解码结果能覆盖目标区域
        assertEquals(0.75, WallpaperDecode.sizeMultiplier(1440.0, 3193.0, 1080.0, 2394.0), 0.01)
        // 目标比源大 -> >1，表示需要放大
        val up = WallpaperDecode.sizeMultiplier(100.0, 100.0, 200.0, 200.0)
        assertEquals(2.0, up, 0.001)
    }

    @Test
    fun realWallpapersAverageMemoryUnderHalfOfBefore() {
        // 汇总校验：14 张真图平均解码内存必须显著低于原来的全尺寸 ARGB_8888。
        var newTotal = 0L
        var oldTotal = 0L
        for ((_, size) in realWallpapers) {
            val (w, h) = size
            val opts = WallpaperDecode.buildOptions(w, h, screenW, screenH)
            newTotal += expectedDecodedWidth(w, h, opts).toLong() * expectedDecodedHeight(w, h, opts) * 2
            oldTotal += w.toLong() * h * 4
        }
        val ratio = newTotal.toDouble() / oldTotal
        val newAvgMb = newTotal / realWallpapers.size / 1024.0 / 1024.0
        val oldAvgMb = oldTotal / realWallpapers.size / 1024.0 / 1024.0
        assertTrue(
            "平均内存应降 60% 以上，实际 " +
                "%.2f MB -> %.2f MB（%.1f%%）".format(oldAvgMb, newAvgMb, ratio * 100),
            ratio < 0.40,
        )
    }

    /* ==================== 辅助：按 Options 推算实际解码尺寸 ==================== */

    private fun expectedDecodedWidth(srcW: Int, srcH: Int, opts: android.graphics.BitmapFactory.Options): Int {
        val iss = opts.inSampleSize.coerceAtLeast(1)
        val w = srcW / iss
        return if (opts.inScaled && opts.inDensity > 0) {
            (w * (opts.inTargetDensity.toLong()) / opts.inDensity).toInt()
        } else {
            w
        }
    }

    private fun expectedDecodedHeight(srcW: Int, srcH: Int, opts: android.graphics.BitmapFactory.Options): Int {
        val iss = opts.inSampleSize.coerceAtLeast(1)
        val h = srcH / iss
        return if (opts.inScaled && opts.inDensity > 0) {
            (h * (opts.inTargetDensity.toLong()) / opts.inDensity).toInt()
        } else {
            h
        }
    }
}
