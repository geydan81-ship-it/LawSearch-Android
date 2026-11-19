package com.lawsearch.app.crawler

import com.lawsearch.app.data.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * docx 解析器测试（规格书第四节）。
 *
 * 说明：app/src/test/resources/samples 下的三个 docx 是**精简样本**
 * （3~6 段、无字体标记），只能验证标题识别与条目切分；
 * 公文排版（字体判定、目录跳过、分项分行、编/分编）用本文件内构造的 fixture 验证。
 */
class DocxParserTest {

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("samples/$name")
            ?: error("缺少测试样本：$name")
        return stream.use { it.readBytes() }
    }

    @Test
    fun parsesContractLawSample() {
        val law = DocxParser.parse(resourceBytes("中华人民共和国劳动合同法.docx"))
        assertEquals("中华人民共和国劳动合同法", law.title)
        assertEquals(4, law.articleCount)
        assertEquals(listOf(10, 19, 37, 82), law.blocks.filter { it.isArticle }.map { it.noNum })
        assertEquals("a1", law.blocks.first { it.isArticle }.id)
        // 样本没有章节标题，不应凭空生成章
        assertTrue(law.blocks.none { it.isHeading })
    }

    @Test
    fun parsesConsumerProtectionSample() {
        val law = DocxParser.parse(resourceBytes("中华人民共和国消费者权益保护法.docx"))
        assertEquals("中华人民共和国消费者权益保护法", law.title)
        assertEquals(listOf(24, 25, 55), law.blocks.filter { it.isArticle }.map { it.noNum })
    }

    @Test
    fun parsesCivilCodeMarriageSample() {
        val law = DocxParser.parse(resourceBytes("中华人民共和国民法典（婚姻家庭编）.docx"))
        assertTrue(law.title.contains("民法典"))
        assertEquals(listOf(1041, 1047, 1049, 1077, 1079), law.blocks.filter { it.isArticle }.map { it.noNum })
    }

    // ---------------- 构造的公文排版 fixture ----------------

    private fun p(text: String, font: String? = null): String {
        val rPr = if (font != null) "<w:rPr><w:rFonts w:eastAsia=\"$font\" w:ascii=\"$font\"/></w:rPr>" else ""
        return "<w:p><w:r>$rPr<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
    }

    /** 条号用黑体小 run、正文用无字体 run（继承 Normal）——真实公文体例的样子 */
    private fun article(no: String, body: String, items: List<String> = emptyList()): String {
        val noXml = "<w:p><w:r><w:rPr><w:rFonts w:eastAsia=\"黑体\"/></w:rPr>" +
            "<w:t xml:space=\"preserve\">$no</w:t></w:r>" +
            "<w:r><w:t xml:space=\"preserve\">　$body</w:t></w:r></w:p>"
        return buildString {
            append(noXml)
            items.forEach { append(p(it)) }
        }
    }

    private fun docx(body: String): ByteArray {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body</w:body></w:document>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Normal\"><w:rPr><w:rFonts w:eastAsia=\"仿宋_GB2312\"/></w:rPr></w:style>" +
            "</w:styles>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write(styles.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun skipsTableOfContents() {
        val body = buildString {
            append(p("测试法"))
            append(p("（2020年1月1日通过）"))
            append(p("目　录"))
            append(p("第一章 总则"))
            append(p("第二章 附则"))
            append(p("第一章 总则"))
            append(article("第一条", "为了测试，制定本法。", listOf("（一）分项一；", "（二）分项二。")))
            append(article("第二条", "本法自公布之日起施行。"))
            append(p("第二章 附则"))
            append(article("第三条", "附则条文。"))
        }
        val law = DocxParser.parse(docx(body))
        assertEquals("测试法", law.title)
        assertEquals("（2020年1月1日通过）", law.meta.subtitle)
        assertEquals(3, law.articleCount)
        val a1 = law.blocks.first { it.isArticle }
        assertEquals("第一条", a1.no)
        assertEquals(1, a1.noNum)
        // 正文 +「（一）」+「（二）」：分项不合并
        assertEquals(3, a1.paragraphs.size)
        assertEquals("（一）分项一；", a1.paragraphs[1])
        assertEquals("（二）分项二。", a1.paragraphs[2])
        assertEquals("a1", a1.id)
        // 目录区不应产生多余的章
        assertEquals(2, law.blocks.count { it.type == Block.TYPE_CHAPTER })
        // 条号字体取显式黑体；正文取 Normal 样式里的仿宋
        assertEquals("黑体", a1.noFont)
        assertEquals("仿宋_GB2312", law.meta.bodyFont)
    }

    @Test
    fun parsesPartsAndSubparts() {
        val body = buildString {
            append(p("测试法典"))
            append(p("第一编　总则"))
            append(p("第一章　基本规定"))
            append(article("第一条", "条文。"))
            append(p("第二编　分则"))
            append(p("第一分编　通则"))
            append(p("第一章　一般规定"))
            append(article("第二条", "条文。"))
        }
        val law = DocxParser.parse(docx(body))
        val types = law.blocks.map { it.type }
        assertTrue(types.contains(Block.TYPE_PART))
        assertTrue(types.contains(Block.TYPE_SUBPART))
        assertEquals(2, law.articleCount)
        assertEquals(listOf("p1", "p2"), law.blocks.filter { it.type == Block.TYPE_PART }.map { it.id })
        assertEquals(listOf("sp1"), law.blocks.filter { it.type == Block.TYPE_SUBPART }.map { it.id })
        // 目录（toc）只包含标题
        assertEquals(5, law.toc.size)
    }

    @Test
    fun warningsWhenNoArticleParsed() {
        val body = buildString {
            append(p("说明文件"))
            append(p("这不是法规，没有任何条文。"))
        }
        val result = runCatching { DocxParser.parseWithWarnings(docx(body)) }
        if (result.isFailure) {
            val sw = java.io.StringWriter()
            result.exceptionOrNull()!!.printStackTrace(java.io.PrintWriter(sw))
            System.err.println("PARSE-FAILURE: " + sw.toString())
        }
        if (result.isFailure) {
            // 解析器可以选择直接抛错；只要不是静默产出脏数据即可
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        } else {
            assertEquals(0, result.getOrThrow().law.articleCount)
            assertTrue(result.getOrThrow().warnings.isNotEmpty())
        }
    }

    @Test
    fun rejectsNonDocx() {
        val e = runCatching { DocxParser.parse("not a zip".toByteArray()) }.exceptionOrNull()
        assertTrue("应抛 IllegalStateException，实际：$e", e is IllegalStateException)
    }

    @Test
    fun appliesOverrides() {
        val body = buildString {
            append(p("某个文件"))
            append(article("第一条", "条文。"))
        }
        val law = DocxParser.parse(
            docx(body),
            DocxParser.LawOverrides(
                bbbs = "abc123",
                title = "中华人民共和国某法",
                gbrq = "2025-01-01",
                sxrq = "2025-06-01",
                sxx = 3,
                group = "法律",
                subgroup = "民法商法",
            ),
        )
        assertEquals("abc123", law.bbbs)
        assertEquals("中华人民共和国某法", law.title)
        assertEquals("2025-06-01", law.sxrq)
        assertEquals("法律", law.group)
        assertEquals(1, law.articleCount)
    }

    @Test
    fun longParagraphsKeepLineBreaks() {
        // 条文后的多个普通段落都属于该条（不分片到下一标题）
        val body = buildString {
            append(p("测试法"))
            append(p("第一章　总则"))
            append(article("第一条", "第一段。", listOf("（一）甲；", "（二）乙；", "（三）丙。", "根据其他法律规定，另作处理。")))
            append(p("第二章　附则"))
        }
        val law = DocxParser.parse(docx(body))
        val a1 = law.blocks.first { it.isArticle }
        assertEquals(5, a1.paragraphs.size)
        assertEquals("根据其他法律规定，另作处理。", a1.paragraphs.last())
    }
}
