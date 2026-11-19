package com.lawsearch.app.data

import com.lawsearch.app.testutil.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Block.content` 反序列化回归测试。
 *
 * ## 背景（真实事故）
 *
 * 内置数据里 article 块的正文有三种存放方式：
 *   1. `paragraphs` —— 按段切好的数组（民法典、工作区 samples 里的海商法/宪法）
 *   2. `content`    —— 整段正文（人民法院法庭规则、绝大多数司法解释、打包后的 assets）
 *   3. `text`       —— 正文就在 text 里（刑法修正案各篇、`type:"text"` 的普通段落）
 *
 * 而 `Block` 之前**没有声明 `content` 字段**，配合
 * `Json { ignoreUnknownKeys = true }`，第 2 类数据的正文被**静默丢弃**。
 * 结果：点进这类法规只看到一条条「第一条」「第二条」标题，下面空白。
 * 全量实测影响 935 部 / 28819 条条文。
 *
 * 这个测试把三种 schema 的读取行为钉死，防止再退化。
 */
class BlockContentTest {

    /* ---------------- 纯逻辑：三段回退顺序 ---------------- */

    @Test
    fun prefersParagraphsWhenPresent() {
        val b = Block(
            type = Block.TYPE_ARTICLE,
            text = "第一条",
            paragraphs = listOf("第一段", "第二段"),
            content = "content 里的全文",
        )
        assertEquals(listOf("第一段", "第二段"), b.bodyParagraphs())
    }

    @Test
    fun fallsBackToContentWhenNoParagraphs() {
        // 这就是人民法院法庭规则 / 司法解释的真实形态：text 只有条号，正文在 content
        val b = Block(
            type = Block.TYPE_ARTICLE,
            id = "a0",
            no = "一",
            noNum = 1,
            text = "第一条",
            content = "为规范法庭安全与秩序，保护…",
        )
        assertEquals(listOf("为规范法庭安全与秩序，保护…"), b.bodyParagraphs())
    }

    @Test
    fun contentWinsOverTextBecauseTextIsOnlyTheHeading() {
        // content 必须排在 text 之前 —— 否则「第一条」会被当成正文整段显示
        val b = Block(type = Block.TYPE_ARTICLE, text = "第一条", content = "真正的正文")
        assertEquals("真正的正文", b.bodyParagraphs().single())
    }

    @Test
    fun fallsBackToTextWhenNeitherParagraphsNorContent() {
        // 刑法修正案各篇、type:"text" 的普通段落：正文只在 text
        val b = Block(type = Block.TYPE_TEXT, text = "一、将刑法第一百三十三条之一修改为…")
        assertEquals(listOf("一、将刑法第一百三十三条之一修改为…"), b.bodyParagraphs())
    }

    @Test
    fun contentKeepsParagraphBreaks() {
        val b = Block(type = Block.TYPE_ARTICLE, content = "第一款\n第二款\n\n第三款")
        // 空行不应产生空白段
        assertEquals(listOf("第一款", "第二款", "第三款"), b.bodyParagraphs())
    }

    @Test
    fun emptyBlockYieldsNoParagraphs() {
        assertEquals(emptyList<String>(), Block(type = Block.TYPE_ARTICLE).bodyParagraphs())
    }

    /* ---------------- 反序列化：content 真的读进来了 ---------------- */

    @Test
    fun decodesContentFromJson() {
        val json = """
            {"bbbs":"x","title":"演练","blocks":[
              {"id":"a0","type":"article","text":"第一条","no":"一","noNum":1,
               "content":"甲方应当履行义务。"}
            ]}
        """.trimIndent()
        val law = LawJsonCodec.decode(json)
        val b = law.blocks.single()
        assertEquals("甲方应当履行义务。", b.content)
        assertEquals(listOf("甲方应当履行义务。"), b.bodyParagraphs())
    }

    @Test
    fun contentSurvivesEncodeDecodeRoundTrip() {
        // 导入时是 decode → 落库 encode → 阅读时再 decode，
        // content 必须在整条链路上都不丢，否则「导入成功但点开仍空白」。
        val law = LawJson(
            bbbs = "x",
            blocks = listOf(
                Block(id = "a1", type = Block.TYPE_ARTICLE, text = "第一条", content = "正文一"),
                Block(id = "a2", type = Block.TYPE_ARTICLE, text = "第二条", content = "正文二"),
            ),
        )
        val back = LawJsonCodec.decode(LawJsonCodec.encode(law))
        assertEquals(listOf("正文一", "正文二"), back.blocks.map { it.content })
        assertEquals(listOf("正文一", "正文二"), back.blocks.map { it.bodyParagraphs().single() })
    }

    /**
     * 复现**修复前的真实事故形态**并确保已修好。
     *
     * 内置数据里 956 部法规的 article 块只有 `content`，没有 `paragraphs`，
     * 且 `text` 只有条号：
     * ```json
     * {"type":"article","text":"第一条","content":"为规范法庭活动秩序…"}
     * ```
     * 修复前 `Block` 没有 `content` 字段 → `ignoreUnknownKeys` 静默丢弃 →
     * `bodyParagraphs()` 退回 `text` → 正文只剩「第一条」。
     * 这就是用户看到的「点进法规只有条号、下面空白」。
     */
    @Test
    fun contentOnlyArticleShapeRendersBodyNotJustTheArticleNumber() {
        val json = """
            {"bbbs":"55a5","title":"人民法院法庭规则","blocks":[
              {"id":"a0","type":"article","text":"第一条","no":"一","noNum":1,
               "content":"为规范法庭活动秩序，保障人民法院依法独立行使审判权…"},
              {"id":"a1","type":"article","text":"第二条","no":"二","noNum":2,
               "content":"法庭应当设置审判席、公诉席、辩护席。"}
            ]}
        """.trimIndent()
        val law = LawJsonCodec.decode(json)
        val first = law.blocks[0]
        // 关键：如果这里拿到的是「第一条」，说明 content 又被丢了
        assertEquals("为规范法庭活动秩序，保障人民法院依法独立行使审判权…", first.bodyParagraphs().single())
        // 且绝不能再出现「第一条 第一条」这种标题当正文的情况
        assertTrue(first.bodyParagraphs().none { it == "第一条" })
    }

    /* ---------------- 真实数据：不要再出现「正文是标题」 ---------------- */

    @Test
    fun realSamplesNeverYieldOnlyTheArticleHeading() {
        if (!Samples.available) return
        val files = listOf(Samples.MARITIME, Samples.CONSTITUTION_1982)
        for (name in files) {
            val law = Samples.law(name) ?: continue
            val articles = law.blocks.filter { it.isArticle }
            assertTrue("${law.title} 应有条文", articles.isNotEmpty())

            // ⚠️ 断言标准要说清楚，第一版这里写错了：
            //
            // samples 目录里的数据是**完整**的：text / content / paragraphs 三者都存着，
            // 且 text == content（310/310 条完全相等），文本本身就是正文。
            // 所以「正文 == text」在这个数据集里是**正常**的，不能当成缺陷。
            //
            // 真正的缺陷形态是：**正文为空**，或者**正文短到只剩条号**
            // （修复前，content-only 的数据经 ignoreUnknownKeys 丢弃 content 后，
            //  bodyParagraphs() 只能退回 text，而该形态的 text 恰好就是「第一条」三个字）。
            // 这里就用这两条判据。
            val blank = articles.count { it.bodyParagraphs().joinToString("").isBlank() }
            assertEquals("${law.title} 有 $blank 条正文为空", 0, blank)

            // 正文只等于条号（「第一条」这种）就是正文丢了
            val headingOnly = articles.count { b ->
                val body = b.bodyParagraphs().joinToString("").trim()
                val no = b.no.trim()
                no.isNotEmpty() && body == no
            }
            assertEquals(
                "${law.title} 有 $headingOnly 条正文只剩条号（content 字段没读进来）",
                0, headingOnly,
            )

            // 抽样确认正文真的更像正文，而不是标题
            val sample = articles.first { it.noNum == 1 }
            assertTrue(
                "${law.title} 第一条正文过短，疑似 content 未读入：${sample.bodyParagraphs()}",
                sample.bodyParagraphs().joinToString("").length > 10,
            )
        }
    }
}
