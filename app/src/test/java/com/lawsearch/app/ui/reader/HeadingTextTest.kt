package com.lawsearch.app.ui.reader

import com.lawsearch.app.data.Block
import com.lawsearch.app.testutil.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节标题取字的回归测试。
 *
 * 背景：内置数据的标题块有两种 schema ——
 *   - `label="第一章 总 纲"` + `text=""`  （国家法律法规数据库导出的那批）
 *   - `text="第一章 总纲"`  + `label` 缺失 （内建「中华人民共和国宪法」等）
 *
 * 早先 `HeadingRow` 只读 `block.label`，导致第二种 schema 的标题**整行空白**，
 * 用户看到「宪法里条文挤在一起、没有章节」= 反馈的「宪法显示错乱」。
 * 全量 1874 部法规里 **325 部（17.3%）** 命中，所以这是系统性回归，必须用测试锁住。
 */
class HeadingTextTest {

    private fun heading(label: String = "", text: String = ""): Block =
        Block(type = Block.TYPE_CHAPTER, label = label, text = text)

    @Test
    fun labelWinsWhenBothPresent() {
        // label 是更规范的字段，两者都有时优先 label
        assertEquals("第一章 总 纲", headingTextOf(heading(label = "第一章 总 纲", text = "第一章 总纲")))
    }

    @Test
    fun fallsBackToTextWhenLabelBlank() {
        // 这就是 325 部法规当场的形态：label 空，正文在 text
        assertEquals("第一章 总纲", headingTextOf(heading(text = "第一章 总纲")))
    }

    @Test
    fun labelOnlySchemaUnchanged() {
        // 不能把原本正常的一批改坏
        assertEquals("第二章 公民的基本权利和义务", headingTextOf(heading(label = "第二章 公民的基本权利和义务")))
    }

    @Test
    fun bothBlankStaysBlankAndIsNotFaked() {
        // 两者都空时保持空串，不硬编码兜底文案（避免把脏数据掩盖成假标题）
        assertEquals("", headingTextOf(heading()))
    }

    @Test
    fun blankIsTreatedAsAbsentNotLiteral() {
        // ifBlank 语义：纯空白也算缺失，应回退到 text
        assertEquals("第三章 国家机构", headingTextOf(heading(label = "   ", text = "第三章 国家机构")))
    }

    // ---------------- 真实样本：两种 schema 各取一部 ----------------

    private fun law(name: String) = Samples.law(name) ?: error("样本缺失：${Samples.path}")

    @Test
    fun realConstitution2018LabelSchemaStillRenders() {
        val law = law(Samples.CONSTITUTION_2018)
        val headings = law.blocks.filter { it.isHeading }
        assertTrue("2018 修正文本应有标题块", headings.isNotEmpty())
        headings.forEach { b ->
            assertTrue(
                "label schema 的标题不应渲染成空：${b.type}/${b.id}",
                headingTextOf(b).isNotBlank(),
            )
        }
    }

    @Test
    fun realConstitutionTextSchemaNowRendersChapterTitles() {
        // 内建版：标题块 label 全空、内容在 text —— 修复前这些全部渲染为空白
        val law = law("7ffbd0c9dc8698361aeca17662dd5e28.json")
        val headings = law.blocks.filter { it.isHeading }
        assertTrue("内建宪法应有标题块", headings.isNotEmpty())

        val emptyLabels = headings.count { it.label.isBlank() }
        assertEquals("前提：这份数据的 label 应全为空（正是被修复的形态）", headings.size, emptyLabels)

        val rendered = headings.map { headingTextOf(it) }
        assertNotNull(rendered)
        assertTrue(
            "修复后每个章节标题都必须有文字，否则又是「显示错乱」",
            rendered.none { it.isBlank() },
        )
        // 抽查第一章
        assertTrue(
            "第一章应渲染为「第一章 总纲」，实际：${rendered.first()}",
            rendered.any { it.replace(" ", "").contains("第一章") },
        )
    }
}
