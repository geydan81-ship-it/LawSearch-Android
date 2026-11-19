package com.lawsearch.app.data

import com.lawsearch.app.testutil.Samples
import com.lawsearch.app.util.Validity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据层契约测试（纯 JVM）。
 *
 * 本机沙箱下 Robolectric 无法解压 SQLite 原生库，所以这里不启动 Android，
 * 而是直接验证数据层依赖的「纯逻辑」部分：
 *  - 时效性重算（宪法 1982 → 已被修订，2018 → 有效）
 *  - 分类计数 / 列表（在仓储的纯函数口径上复算）
 *  - 搜索命中数与打分（通过 JVM SQLite FTS5，见 [Fts5SearchTest]）
 *
 * Room/FTS 的 Android 集成路径由 app 内 ArticleFts + LawRepository 实现，
 * 二者共享同一套二元组/打分逻辑（ChineseToken / ArticleFts.score）。
 */
class DataContractTest {

    private fun sample(name: String): LawJson = Samples.law(name) ?: error("样本缺失：${Samples.path}")

    @Test
    fun validityRecomputeMarksOldConstitutionRevised() {
        val files = listOf(
            Samples.CONSTITUTION_1982,
            Samples.CONSTITUTION_2018,
            Samples.AMENDMENT_2004,
            Samples.MARITIME,
        )
        val laws = files.map { sample(it) }
        val metas = laws.map { Validity.LawMeta(it.bbbs, it.title, it.gbrq, it.sxrq, it.sxx) }
        val today = java.time.LocalDate.of(2026, 11, 11)

        val resolved = metas.map { Validity.resolve(it, metas, today) }
        val byTitle = metas.indices.associate { laws[it].title to resolved[it] }

        assertEquals("已被修订", byTitle.entries.first { it.key.contains("1982") }.value)
        assertEquals("有效", byTitle.entries.first { it.key.contains("2018") && it.key.contains("修正文本") }.value)
        assertEquals("已被修订", byTitle.entries.first { it.key.contains("2004") }.value)
        assertEquals("有效", byTitle.entries.first { it.key.contains("海商法") }.value)
    }

    @Test
    fun groupCountsFromSampleData() {
        // 模拟仓储的 groupCounts：按 group 聚合
        val rows = publicLawFiles().map { sample(it) }
        val byGroup = rows.groupingBy { it.group }.eachCount()
        assertEquals(rows.size, byGroup.values.sum())
        assertTrue("应包含法律类", byGroup.containsKey("法律"))
    }

    @Test
    fun articleCountsMatchMeta() {
        val maritime = sample(Samples.MARITIME)
        assertEquals(310, maritime.articleCount)
        assertEquals(310, maritime.blocks.count { it.isArticle })
        val a22 = maritime.blocks.first { it.isArticle && it.noNum == 22 }
        assertEquals(7, a22.paragraphs.size)
    }

    /** 读工作区样本目录里的全部法规文件名 */
    private fun publicLawFiles(): List<String> {
        val dir = java.io.File(Samples.path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "_meta.json" }
            ?.map { it.name } ?: emptyList()
    }
}
