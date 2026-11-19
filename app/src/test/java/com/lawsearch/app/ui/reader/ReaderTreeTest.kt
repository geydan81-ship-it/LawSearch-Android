package com.lawsearch.app.ui.reader

import com.lawsearch.app.data.Block
import com.lawsearch.app.testutil.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠树验证（真实样本数据），对照规格书第九节「折叠功能验证」：
 *  - 默认展开到章级，条文折叠（可见条文 0）
 *  - 全部折叠：只剩顶层标题，可见条文 0
 *  - 全部展开：所有条文可见
 *  - 折叠外层章：内部节与条文随之隐藏
 *  - 跳转：祖先自动展开，锚点出现在祖先之后
 *
 * 样本里没有民法典，用海商法（310 条 / 16 章 / 27 节）与生态环境法典（1242 条）覆盖。
 */
class ReaderTreeTest {

    private val maritime by lazy { Samples.law(Samples.MARITIME) ?: error("样本缺失：${Samples.path}") }
    private val eco by lazy { Samples.law(Samples.ECO_CODE) ?: error("样本缺失：${Samples.path}") }

    /** 按块 id 取节点（测试里都是语义明确的单点，重复 id 取第一个） */
    private fun ReaderTree.nodeById(id: String) = byBlockId(id)

    @Test
    fun maritimeArticleCount() {
        assertEquals(310, maritime.articleCount)
        assertEquals(310, ReaderTree.build(maritime).articleCount)
    }

    @Test
    fun maritimeStructure() {
        assertEquals("16 章", 16, maritime.blocks.count { it.type == Block.TYPE_CHAPTER })
        assertEquals("27 节", 27, maritime.blocks.count { it.type == Block.TYPE_SECTION })
    }

    @Test
    fun article22HasSevenParagraphs() {
        val a22 = maritime.blocks.first { it.isArticle && it.noNum == 22 }
        assertEquals("第二十二条必须保留 7 个段落（分项不合并）", 7, a22.paragraphs.size)
        assertTrue(a22.paragraphs[0].startsWith("下列各项海事请求具有船舶优先权"))
        assertTrue(a22.paragraphs[1].startsWith("（一）"))
        assertTrue(a22.paragraphs[5].startsWith("（五）"))
        assertTrue(a22.paragraphs[6].startsWith("根据中华人民共和国缔结或者参加的国际条约"))
    }

    @Test
    fun seaPriorityArticlesContainTerm() {
        val hits = maritime.blocks
            .filter { it.isArticle }
            .filter { b -> b.bodyParagraphs().any { it.contains("船舶优先权") } }
            .mapNotNull { it.noNum }
        assertTrue("应包含第二十一条：$hits", hits.contains(21))
        assertTrue("应包含第二十二条：$hits", hits.contains(22))
        assertTrue("应包含第二十四条：$hits", hits.contains(24))
    }

    @Test
    fun treeStructure() {
        val tree = ReaderTree.build(maritime)
        assertEquals("顶层就是 16 章（海商法没有编）", 16, tree.roots.size)
        assertTrue(tree.roots.all { it.block.type == Block.TYPE_CHAPTER })
        // 第二十二条的祖先链：章 c2 > 节 s3（用 key 表示）
        assertEquals(listOf("c2", "c2>s3"), tree.nodeById("a22")?.ancestors)
        // 节点数与块数一致（每个块恰好一个节点）
        assertEquals(maritime.blocks.size, tree.ordered.size)
        assertEquals(310, tree.articleCount)
    }

    @Test
    fun defaultCollapsedHidesArticles() {
        // 海商法 310 条 > 阈值 80，属于「长法规」，应默认折叠到章级
        val tree = ReaderTree.build(maritime)
        val visible = tree.visible(tree.defaultCollapsed())
        assertEquals("长法规默认只露到章级：可见条文应为 0", 0, visible.count { it.block.isArticle })
        // 海商法没有「编」，顶层就是 16 个章；章本身默认收起，所以可见 = 16 个章标题
        assertEquals("可见节点 = 16 个章标题", 16, visible.size)
        assertTrue("可见的全是章标题", visible.all { it.block.rank == 2 })
    }

    /**
     * 短法规默认不折叠 —— 用户反馈：打开《人民法院法庭规则》这类短法规时
     * 首屏只有一排 `▶ 第一章`、一行正文都没有，会以为内容丢了。
     * 现在条文数 ≤ 80 的法规默认展开。
     */
    @Test
    fun shortLawDefaultsToExpanded() {
        // 构造一部只有 3 条的短法规
        val short = maritime.copy(blocks = maritime.blocks.take(30))
        val tree = ReaderTree.build(short)
        val articles = tree.ordered.count { it.block.isArticle }
        if (articles <= 80) {
            val visible = tree.visible(tree.defaultCollapsed())
            assertEquals(
                "短法规默认不折叠，所有条文应可见",
                articles,
                visible.count { it.block.isArticle },
            )
        }
    }

    @Test
    fun collapseAllLeavesOnlyTopLevel() {
        val tree = ReaderTree.build(maritime)
        val visible = tree.visible(tree.allCollapsed())
        assertEquals(0, visible.count { it.block.isArticle })
        assertEquals("只剩 16 个章标题", 16, visible.size)
    }

    @Test
    fun expandAllShowsEverything() {
        val tree = ReaderTree.build(maritime)
        val visible = tree.visible(emptySet())
        assertEquals(310, visible.count { it.block.isArticle })
        assertEquals(maritime.blocks.size, visible.size)
    }

    @Test
    fun collapseChapterHidesInnerSectionAndArticles() {
        val tree = ReaderTree.build(maritime)
        val c2 = tree.nodeById("c2")!!
        val visibleIds = tree.visible(setOf(c2.key)).map { it.block.id }
        // 第二章内部的节与条都不可见
        c2.children.forEach { child ->
            assertTrue("章节子节点 ${child.block.id} 应隐藏", !visibleIds.contains(child.block.id))
        }
        // 其他章仍然展开
        assertTrue(visibleIds.contains("a1"))
    }

    @Test
    fun jumpExpandsAncestors() {
        val tree = ReaderTree.build(maritime)
        val expanded = tree.expandTo("a22", tree.allCollapsed())
        val visibleIds = tree.visible(expanded).map { it.block.id }
        val index = visibleIds.indexOf("a22")
        assertTrue("锚点应在可见列表中", index >= 0)
        tree.ancestorsOf("a22").forEach { ancestorKey ->
            val ancestorId = ancestorKey.substringAfterLast(">")
            assertTrue("祖先 $ancestorKey 应出现在锚点之前", visibleIds.indexOf(ancestorId) in 0 until index)
        }
    }

    @Test
    fun duplicateBlockIdsDoNotDuplicateNodes() {
        // 生态环境法典里各分编下的「第一节」都叫 s1；节点数必须仍等于块数
        val tree = ReaderTree.build(eco)
        assertEquals(eco.blocks.size, tree.ordered.size)
        assertEquals(1242, tree.articleCount)
        assertEquals(eco.blocks.count { it.isArticle }, tree.articleCount)
        // key 必须唯一
        assertEquals(tree.ordered.size, tree.ordered.map { it.key }.toSet().size)
    }

    @Test
    fun largeLawBuildsAndFoldsFast() {
        val start = System.nanoTime()
        val tree = ReaderTree.build(eco)
        val visible = tree.visible(tree.defaultCollapsed())
        val collapsedAll = tree.visible(tree.allCollapsed())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(1242, tree.articleCount)
        assertEquals(0, visible.count { it.block.isArticle })
        assertEquals(0, collapsedAll.count { it.block.isArticle })
        assertTrue("构建 + 两次折叠应 < 100ms，实际 ${elapsedMs}ms", elapsedMs < 100)
    }

    @Test
    fun ecoAllCollapsedKeepsTopLevelHeadingsOnly() {        val tree = ReaderTree.build(eco)
        val visible = tree.visible(tree.allCollapsed())
        // 全部折叠后只剩顶层标题（编/分编/章），且没有条文
        assertEquals(0, visible.count { it.block.isArticle })
        assertEquals(tree.roots.size, visible.size)
        assertTrue("顶层节点应当都是标题", visible.all { it.block.isHeading })
        assertNotNull(tree.roots.firstOrNull())
    }

    // ---------------- 按条号跳转（阅读器底部跳转框） ----------------

    @Test
    fun articleByNoFindsArticles() {
        val tree = ReaderTree.build(maritime)
        assertEquals("第二十一条", tree.articleByNo(21)?.block?.no)
        assertEquals("第二十二条", tree.articleByNo(22)?.block?.no)
        assertEquals("第一条", tree.articleByNo(1)?.block?.no)
        assertEquals("第三百一十条", tree.articleByNo(310)?.block?.no)
        // 边界
        assertNull(tree.articleByNo(0))
        assertNull(tree.articleByNo(311))
        assertNull(tree.articleByNo(-1))
    }

    @Test
    fun articleByNoReturnsArticleNodeOnly() {
        val tree = ReaderTree.build(maritime)
        // 章/节 的 id 是 c1 / s1，不能被条号查出来
        val n = tree.articleByNo(1)!!
        assertTrue("查到的必须是条文节点", n.block.isArticle)
        assertEquals(1, n.block.noNum)
    }

    @Test
    fun jumpToArticleExpandsAncestorsAndBecomesVisible() {
        val tree = ReaderTree.build(maritime)
        val target = tree.articleByNo(22)!!
        // 从「默认展开到章级」的状态直接跳转
        val expanded = tree.expandTo(target.block.id, tree.defaultCollapsed())
        val ids = tree.visible(expanded).map { it.block.id }
        val at = ids.indexOf("a22")
        assertTrue("跳转后第二十二条应可见", at >= 0)
        tree.ancestorsOf("a22").forEach { key ->
            val ancestorId = key.substringAfterLast(">")
            assertTrue("祖先 $key 应在锚点之前", ids.indexOf(ancestorId) in 0 until at)
        }
    }

    @Test
    fun articleByNoWorksForLargeLaw() {
        val tree = ReaderTree.build(eco)
        assertEquals("第一千二百四十二条", tree.articleByNo(1242)?.block?.no)
        assertNull(tree.articleByNo(1243))
        // 1..1242 全部可查到，说明索引没漏条目
        val indexed = (1..1242).count { tree.articleByNo(it) != null }
        assertEquals(1242, indexed)
    }
}
