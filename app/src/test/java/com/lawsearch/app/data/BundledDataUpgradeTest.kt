package com.lawsearch.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **升级安装的数据更新路径**（P0 回归测试）。
 *
 * ## 这个测试钉的是一个「导入触发了但空转」的 bug
 *
 * 现象：升级安装（`install -r`）后，APK 里换了新数据（如宪法改名），
 * 但 App 列表里**还是旧标题**。启动很快、没有导入过程、也不报错。
 *
 * 根因（修复前 `BundledData.importIfNeeded`）：
 * ```kotlin
 * if (!overwrite && isImported()) return
 * val existing = if (overwrite) emptySet() else repo.allIds()   // 永远是全量
 * if (bbbs in existing) return@forEachIndexed                    // 1874 次全跳过
 * ```
 * `IMPORT_VERSION` 变化让 `isImported()` 正确返回 false，导入流程**启动并跑完**，
 * 但每一条都在 `bbbs in existing` 处 `continue` —— **一条 JSON 都没重读**。
 * 更糟的是结尾仍执行 `putString(PREF_IMPORTED, 新指纹)`，
 * 于是指纹变新，**下次启动认定"已是最新"，永远不再尝试**。
 *
 * ## 为什么用 Robolectric
 *
 * `BundledData` 依赖 `Context.assets` 和 Room，纯 JVM 跑不起来。
 * Robolectric 提供真实的 `AssetManager`（能读 `src/main/assets`）和可用的 SQLite。
 *
 * ⚠️ **必须 `@Config(sdk = [33])`**：`targetSdk = 37` 超出 Robolectric 4.14.1 支持范围。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BundledDataUpgradeTest {

    private lateinit var context: Context
    private lateinit var db: LawDatabase
    private lateinit var repo: LawRepository
    private lateinit var bundled: BundledData

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 内存库：每个用例独立，互不污染
        db = Room.inMemoryDatabaseBuilder(context, LawDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LawRepository(context, db, ArticleFts(db))
        bundled = BundledData(context, repo)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /* ==================== 前置条件 ==================== */

    @Test
    fun assetsAreAvailableToTests() {
        // 前置判据：如果 assets 读不到，下面所有用例都会"通过"但毫无意义
        assertTrue("测试必须能读到内置数据 assets", bundled.available())
        val count = bundled.metaLawCount()
        assertTrue("内置数据应有 1874 部，实际 $count", count >= 1800)
    }

    /* ==================== 核心：升级后旧记录必须被覆盖 ==================== */

    /**
     * 模拟「升级安装」：库里有旧标题，assets 里是新标题，指纹变化后必须覆盖。
     *
     * 这是修复的核心断言。修复前它会失败 —— 因为旧记录会被跳过。
     */
    @Test
    fun changedFingerprintForcesReimportOfExistingLaws() = runBlocking {
        val target = TARGET_BBBS   // 7ffbd0c9…，本次改名的宪法

        // ---- 步骤 1：模拟"老版本已经把旧数据导进来了"
        // 直接写一条 law 记录，标题是老名字（与旧版 APK 一致）
        val oldEntity = LawEntity(
            bbbs = target,
            title = "中华人民共和国宪法",
            group = "宪法",
            subgroup = "宪法",
            articleCount = 143,
        )
        db.lawDao().upsert(oldEntity)
        val before = db.lawDao().get(target)
        assertEquals("前置：库里应是旧标题", "中华人民共和国宪法", before?.title)

        // ---- 步骤 2：模拟"老版本的指纹"，让 isImported() 认为数据已是最新
        // 通过写一个和当前 APK 指纹不同的值来模拟：老版本导入完成后写下的指纹
        repo.putString(BundledData.PREF_IMPORTED, "3:?:2026-11-17")
        // 注意：此时 isImported() 应为 false（指纹与当前 APK 不一致）
        assertFalse(
            "指纹与 APK 不一致时 isImported() 必须为 false，否则重导入不会触发",
            bundled.isImported(),
        )

        // ---- 步骤 3：跑导入（不传 overwrite，与生产 LawApp.kt 完全一致）
        val imported = bundled.importIfNeeded()

        // ---- 步骤 4：核心断言 —— 旧记录必须被新标题覆盖
        assertTrue("指纹变化后应真正写入数据（修复前这里是 0），实际 $imported", imported > 0)

        val after = db.lawDao().get(target)
        assertNotEquals("修复前：旧标题被跳过、不会更新 —— 这就是 bug 本身", null, after)
        assertEquals(
            "升级后标题必须更新为 APK 里的新值。修复前会停留在「中华人民共和国宪法」",
            "中华人民共和国宪法（含序言·早期整理版）",
            after!!.title,
        )
    }

    /**
     * 指纹已是最新时，导入必须**什么都不做**（不能每次都全量重导入拖慢启动）。
     */
    @Test
    fun unchangedFingerprintSkipsImportEntirely() = runBlocking {
        // 先完整导入一次
        val first = bundled.importIfNeeded()
        assertTrue("首次导入应写入数据，实际 $first", first > 0)

        val countAfterFirst = db.lawDao().count()

        // 再跑一次：指纹已一致，应立即返回 0
        val second = bundled.importIfNeeded()
        assertEquals("指纹未变化时不应重复导入，实际写入 $second", 0, second)
        assertEquals("法规数不应变化", countAfterFirst, db.lawDao().count())
    }

    /**
     * `overwrite = true` 必须能强制重导（供"用户手动修复数据"等场景使用）。
     */
    @Test
    fun explicitOverwriteStillForcesReimport() = runBlocking {
        bundled.importIfNeeded()
        val count = db.lawDao().count()
        assertTrue("首次导入应有数据", count > 0)

        val forced = bundled.importIfNeeded(overwrite = true)
        assertTrue("overwrite=true 应强制重新写入，实际 $forced", forced > 0)
        assertEquals("覆盖导入不应产生重复行", count, db.lawDao().count())
    }

    /* ==================== 覆盖后数据内容确实来自 APK ==================== */

    @Test
    fun reimportedTitleMatchesApkAsset() = runBlocking {
        bundled.importIfNeeded()

        // APK 里 7ffbd0c9… 的标题
        val entity = db.lawDao().get(TARGET_BBBS)
        assertEquals(
            "导入后的标题必须与 APK assets 中的 title 完全一致",
            "中华人民共和国宪法（含序言·早期整理版）",
            entity?.title,
        )

        // 宪法组应当 8 部齐全
        val constitution = db.lawDao().listAll().filter { it.group == "宪法" }
        assertEquals("宪法组应有 8 部", 8, constitution.size)
    }

    /**
     * 前导空格清理也要通过升级路径生效（同一批修复）。
     */
    @Test
    fun leadingSpaceInTitleIsCleanedAfterImport() = runBlocking {
        // 模拟旧数据：该条标题带前导空格（旧版就是这样）
        db.lawDao().upsert(
            LawEntity(
                bbbs = SPACED_BBBS,
                title = " 全国污染源普查条例",
                group = "行政法规",
                subgroup = "行政法规",
            )
        )
        repo.putString(BundledData.PREF_IMPORTED, "3:?:2026-11-17")

        bundled.importIfNeeded()

        val after = db.lawDao().get(SPACED_BBBS)
        assertEquals(
            "前导空格应被清理",
            "全国污染源普查条例",
            after?.title,
        )
    }

    /* ==================== 指纹本身 ==================== */

    /**
     * 指纹里的法规数必须是真的数字，不能是 `?`（旧正则匹配 dict 永远失败）。
     */
    @Test
    fun fingerprintContainsRealLawCount() = runBlocking {
        bundled.importIfNeeded()
        val saved = repo.getString(BundledData.PREF_IMPORTED, "")
        println("PREF_IMPORTED = $saved")

        // 形如 "4:1874:2026-11-19"
        val parts = saved.split(":")
        assertEquals("指纹应有 3 段（版本:法规数:日期），实际 $saved", 3, parts.size)
        assertEquals("第一段应是 IMPORT_VERSION", BundledData.IMPORT_VERSION, parts[0])
        assertNotEquals(
            "法规数不能是 '?'（旧正则想匹配 \"laws\": 数字，但 laws 是对象，永远匹配失败）",
            "?", parts[1],
        )
        val n = parts[1].toIntOrNull()
        assertTrue("法规数应是 1874 左右，实际 ${parts[1]}", n != null && n >= 1800)
    }

    companion object {
        /** 中华人民共和国宪法（含序言·早期整理版），原名「中华人民共和国宪法」 */
        const val TARGET_BBBS = "7ffbd0c9dc8698361aeca17662dd5e28"

        /** 全国污染源普查条例（旧数据标题带前导空格） */
        const val SPACED_BBBS = "ff8081819ff54ab801a0ae8cb4462d0e"
    }
}
