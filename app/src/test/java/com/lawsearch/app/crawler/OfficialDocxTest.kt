package com.lawsearch.app.crawler

import com.lawsearch.app.data.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**官网原版 docx** 验证解析器（规格书第四节的验收用例）。
 *
 * 样本 `samples/海商法-官网原版.docx` 于 2026-11-11 从
 * https://flk.npc.gov.cn/law-search/download/pc?bbbs=1349e6aadd4947fe83ff811739825d3c 下载。
 *
 * 期望（规格书）：海商法 310 条 / 16 章 / 27 节，第二十二条 7 个段落。
 */
class OfficialDocxTest {

    private val law by lazy { DocxParser.parse(load()) }

    private fun load(): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("samples/海商法-官网原版.docx")
            ?: error("缺少官网样本 docx")
        return stream.use { it.readBytes() }
    }

    @Test
    fun parsesTitleAndArticleCount() {
        assertEquals("中华人民共和国海商法", law.title)
        assertEquals("应解析出 310 条", 310, law.articleCount)
        assertEquals(310, law.blocks.count { it.isArticle })
    }

    @Test
    fun parsesChapterAndSectionCount() {
        assertEquals("16 章", 16, law.blocks.count { it.type == Block.TYPE_CHAPTER })
        assertEquals("27 节", 27, law.blocks.count { it.type == Block.TYPE_SECTION })
        assertTrue("不应解析出编", law.blocks.none { it.type == Block.TYPE_PART })
    }

    @Test
    fun articleRangeIsOneTo310() {
        assertEquals(listOf(1, 310), law.articleRange)
        val nums = law.blocks.filter { it.isArticle }.mapNotNull { it.noNum }
        assertEquals(nums.sorted(), nums) // 条号递增
        assertEquals(1, nums.first())
        assertEquals(310, nums.last())
    }

    @Test
    fun article22HasSevenParagraphs() {
        val a22 = law.blocks.first { it.isArticle && it.noNum == 22 }
        assertEquals("第二十二条必须保留 7 个段落", 7, a22.paragraphs.size)
        assertTrue(a22.paragraphs[0].startsWith("下列各项海事请求具有船舶优先权"))
        assertTrue(a22.paragraphs[1].startsWith("（一）"))
        assertTrue(a22.paragraphs[2].startsWith("（二）"))
        assertTrue(a22.paragraphs[3].startsWith("（三）"))
        assertTrue(a22.paragraphs[4].startsWith("（四）"))
        assertTrue(a22.paragraphs[5].startsWith("（五）"))
        assertTrue(a22.paragraphs[6].startsWith("根据中华人民共和国缔结或者参加的国际条约"))
    }

    @Test
    fun detectsChapterHeadings() {
        val first = law.blocks.first()
        assertEquals(Block.TYPE_CHAPTER, first.type)
        assertTrue("第一章标题应包含「总」", first.label.contains("总"))
        assertEquals(law.blocks.count { it.isHeading }, law.toc.size)
    }

    @Test
    fun fontStatsAreReasonable() {
        // 说明：2025-10 修订版《海商法》的官方 docx 通篇用黑体排正文
        // （仿宋_GB2312 只用在副标题、楷体_GB2312 用于章节标题），
        // 所以 bodyFont 判为「黑体」是正确的——不要按旧版体例假设成仿宋。
        val stats = law.meta.fontStats
        assertTrue("应统计到字体分布：$stats", stats.isNotEmpty())
        assertTrue("正文字体应为统计占比最高的字体", law.meta.bodyFont != null)
        assertEquals(
            "bodyFont 应等于 fontStats 中字符数最多的字体",
            stats.maxByOrNull { it.value }?.key,
            law.meta.bodyFont,
        )
        // 章节标题用楷体，正文用黑体，二者都应被统计到
        assertTrue("应统计到楷体（章节标题）", stats.keys.any { it.contains("楷体") })
        assertTrue("应统计到黑体（正文）", stats.keys.any { it.contains("黑体") })
    }

    @Test
    fun searchableTermsPresent() {
        val hits = law.blocks.filter { it.isArticle }
            .filter { b -> b.bodyParagraphs().any { it.contains("船舶优先权") } }
            .mapNotNull { it.noNum }
        assertTrue("第二十一条应提及船舶优先权", hits.contains(21))
        assertTrue("第二十二条应提及船舶优先权", hits.contains(22))
        assertTrue("第二十四条应提及船舶优先权", hits.contains(24))
    }

    @Test
    fun noTableOfContentsLeak() {
        // 目录区不应残留成正文
        assertTrue(
            law.blocks.none { it.type == Block.TYPE_TEXT && it.text.replace(" ", "").contains("目录") },
        )
    }
}
