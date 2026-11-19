package com.lawsearch.app.ui.reader

import com.lawsearch.app.data.Block
import com.lawsearch.app.data.LawJson
import com.lawsearch.app.testutil.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * markdown 标题提升的回归测试。
 *
 * 背景：一批法规的正文块里带 markdown 标题（`## 序言` / `### 重伤` / `#### 5.1.1 重伤一级`），
 * 早先它们以 `type="text"` 普通段落身份渲染，屏幕上**原样显示 `## ` 字样**。
 * 全量 1874 部法规里 **66 部、共 300 处**这种块，是「宪法显示错乱」的成因之一。
 *
 * 修复放在建树阶段（[ReaderTree.promoteMarkdownHeading]），而不是在 TextBlock 里 regex 剥前缀 ——
 * 剥前缀只去掉符号，标题层级/字号/折叠仍然没有。
 */
class MarkdownHeadingTest {

    private fun text(s: String, id: String = "t") = Block(type = Block.TYPE_TEXT, id = id, text = s)

    // ---------------- 纯逻辑：提升规则 ----------------

    @Test
    fun promotesH2ToHeading() {
        val out = ReaderTree.promoteMarkdownHeading(text("## 序言"))
        assertEquals(ReaderTree.TYPE_MD_HEADING, out.type)
        assertEquals("序言", out.label)
        assertEquals("提升后 text 必须清空，否则复制全文仍带 ##", "", out.text)
    }

    @Test
    fun promotesDeeperLevels() {
        assertEquals("重伤", ReaderTree.promoteMarkdownHeading(text("### 重伤")).label)
        assertEquals("5.1.1 重伤一级", ReaderTree.promoteMarkdownHeading(text("#### 5.1.1 重伤一级")).label)
        assertEquals("范围", ReaderTree.promoteMarkdownHeading(text("## 范围")).label)
    }

    @Test
    fun doesNotTouchPlainParagraphs() {
        val p = text("中国是世界上历史最悠久的国家之一。")
        val out = ReaderTree.promoteMarkdownHeading(p)
        assertEquals("普通段落必须原样返回", p, out)
        assertEquals(Block.TYPE_TEXT, out.type)
    }

    @Test
    fun doesNotTouchRealHeadingsOrArticles() {
        // 绝不把真实章节/条文改掉
        val chapter = Block(type = Block.TYPE_CHAPTER, id = "c1", label = "第一章 总 纲")
        val article = Block(type = Block.TYPE_ARTICLE, id = "a1", no = "第一条", text = "## 不是标题")
        assertEquals("chapter 不得被改写", chapter, ReaderTree.promoteMarkdownHeading(chapter))
        assertEquals("article 不得被改写", article, ReaderTree.promoteMarkdownHeading(article))
    }

    @Test
    fun requiresSpaceAfterHashes() {
        // CommonMark：`#` 后必须有空格/制表符才是标题；`#标签` 不是
        val tagged = text("#标签")
        assertEquals(Block.TYPE_TEXT, ReaderTree.promoteMarkdownHeading(tagged).type)
        // 七个 # 超出 1..6 范围，也不是标题
        val seven = text("####### 七个井号")
        assertEquals(Block.TYPE_TEXT, ReaderTree.promoteMarkdownHeading(seven).type)
    }

    @Test
    fun bareHashesWithoutTitleIsNotHeading() {
        val bare = text("##")
        assertEquals(Block.TYPE_TEXT, ReaderTree.promoteMarkdownHeading(bare).type)
    }

    // ---------------- 建树集成 ----------------

    @Test
    fun treeTreatsPromotedBlockAsNonCollapsibleLeaf() {
        val law = LawJson(
            title = "t",
            blocks = listOf(
                Block(type = Block.TYPE_CHAPTER, id = "c1", label = "第一章 总 纲"),
                text("## 序言", id = "t0"),
                Block(type = Block.TYPE_ARTICLE, id = "a1", no = "第一条", noNum = 1, text = "正文"),
            ),
        )
        val tree = ReaderTree.build(law)
        val md = tree.ordered.first { it.block.type == ReaderTree.TYPE_MD_HEADING }

        // rank=5 → 不是 heading，不进标题栈，也就不会把后续条文挂到自己名下
        assertFalse("markdown 标题不应参与折叠层级", md.block.isHeading)
        assertEquals("序言", md.block.label)
        assertEquals("不能有子节点", 0, md.children.size)

        // 条文仍挂在真实章节下，没被 markdown 标题顶掉
        val a1 = tree.byBlockId("a1")
        assertEquals(1, a1?.ancestors?.size ?: -1)
    }

    @Test
    fun chapterFollowingMarkdownHeadingStillNestsCorrectly() {
        val law = LawJson(
            title = "t",
            blocks = listOf(
                text("## 序言", id = "t0"),
                Block(type = Block.TYPE_CHAPTER, id = "c1", label = "第一章 总 纲"),
                Block(type = Block.TYPE_ARTICLE, id = "a1", no = "第一条", noNum = 1, text = "正文"),
            ),
        )
        val tree = ReaderTree.build(law)
        // 第一章应是根节点（markdown 标题不能把它压成子节点）
        assertTrue(
            "第一章必须是顶层节点，实际 roots=${tree.roots.map { it.block.id }}",
            tree.roots.any { it.block.id == "c1" },
        )
    }

    // ---------------- 真实样本 ----------------

    @Test
    fun realConstitutionPreambleNoLongerShowsHashes() {
        val law = Samples.law("7ffbd0c9dc8698361aeca17662dd5e28.json")
            ?: error("样本缺失：${Samples.path}")
        val tree = ReaderTree.build(law)

        val md = tree.ordered.filter { it.block.type == ReaderTree.TYPE_MD_HEADING }
        assertTrue("内建宪法应识别出「## 序言」，实际 ${md.size} 个", md.isNotEmpty())
        assertEquals("序言", md.first().block.label)

        // 全树不应再有以 # 开头的可见正文
        val leaking = tree.ordered
            .filter { !it.block.isArticle }
            .map { headingTextOf(it.block) }
            .filter { it.startsWith("#") }
        assertTrue("不应再有 # 泄漏到正文，实际：$leaking", leaking.isEmpty())
    }
}
