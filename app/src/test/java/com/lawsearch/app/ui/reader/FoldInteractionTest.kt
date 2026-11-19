package com.lawsearch.app.ui.reader

import com.lawsearch.app.data.Block
import com.lawsearch.app.data.LawJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现并守住「刚打开法规时点章节无效」的场景。
 *
 * 用户报告：刚打开一部法律，点任何章节名都没反应；
 * 必须先「全部展开」→「全部折叠」，之后点章名才有效。
 *
 * 这里按用户描述的操作序列逐步验证折叠集语义。
 */
class FoldInteractionTest {

    private fun law(vararg blocks: Block) = LawJson(
        bbbs = "test",
        title = "测试法",
        articleCount = blocks.count { it.isArticle },
        blocks = blocks.toList(),
    )

    private fun chapter(id: String, label: String) =
        Block(type = Block.TYPE_CHAPTER, id = id, label = label, text = label)

    private fun section(id: String, label: String) =
        Block(type = Block.TYPE_SECTION, id = id, label = label, text = label)

    private fun article(id: String, no: String, num: Int) =
        Block(type = Block.TYPE_ARTICLE, id = id, label = no, no = no, noNum = num, text = "$no 正文")

    @Test
    fun tappingChapterRightAfterOpenExpandsIt() {
        val tree = ReaderTree.build(
            law(
                chapter("c1", "第一章 总则"),
                article("a1", "第一条", 1),
                chapter("c2", "第二章 附则"),
                article("a2", "第二条", 2),
            )
        )
        var collapsed = tree.defaultCollapsed(0)

        // 刚打开：章可见、条文不可见
        val before = tree.visible(collapsed).map { it.block.id }
        assertTrue("第一章应可见", "c1" in before)
        assertFalse("条文默认折叠", "a1" in before)

        // 点第一章展开它
        val c1 = tree.ordered.first { it.block.id == "c1" }
        collapsed = collapsed - c1.key

        val after = tree.visible(collapsed).map { it.block.id }
        assertTrue("点击后第一章的条文 a1 应出现，实际：$after", "a1" in after)
    }

    @Test
    fun tappingEachChapterWorksIndependently() {
        val tree = ReaderTree.build(
            law(
                chapter("c1", "第一章"), article("a1", "第一条", 1),
                chapter("c2", "第二章"), article("a2", "第二条", 2),
                chapter("c3", "第三章"), article("a3", "第三条", 3),
            )
        )
        var collapsed = tree.defaultCollapsed(0)
        for (id in listOf("a1", "a2", "a3")) {
            assertFalse("默认 $id 不该可见", id in tree.visible(collapsed).map { it.block.id })
        }
        for ((cid, aid) in listOf("c1" to "a1", "c2" to "a2", "c3" to "a3")) {
            val node = tree.ordered.first { it.block.id == cid }
            collapsed = collapsed - node.key
            assertTrue("点开 $cid 后 $aid 应可见", aid in tree.visible(collapsed).map { it.block.id })
        }
        val ids = tree.visible(collapsed).map { it.block.id }
        assertTrue(listOf("a1", "a2", "a3").all { it in ids })
    }

    /**
     * 用户描述的操作序列：全部展开 → 全部折叠 → 点章名。
     * 关键语义：**默认与全部折叠都只露出章标题，条文为 0**；
     * 点一下章就能看到它下面的条文。
     */
    @Test
    fun defaultOnlyShowsChapterHeadings() {
        val tree = ReaderTree.build(
            law(
                chapter("c1", "第一章"), article("a1", "第一条", 1),
                chapter("c2", "第二章"), article("a2", "第二条", 2),
            )
        )
        val visible = tree.visible(tree.defaultCollapsed(0)).map { it.block.id }
        assertEquals("默认只看到两个章标题", listOf("c1", "c2"), visible)
    }

    @Test
    fun expandAllThenCollapseAllThenTapChapterWorks() {
        val tree = ReaderTree.build(
            law(chapter("c1", "第一章"), article("a1", "第一条", 1))
        )
        // 全部展开
        var collapsed = emptySet<String>()
        assertTrue("a1" in tree.visible(collapsed).map { it.block.id })

        // 全部折叠
        collapsed = tree.allCollapsed()
        assertFalse("全部折叠后 a1 不可见", "a1" in tree.visible(collapsed).map { it.block.id })

        // 点第一章
        val c1 = tree.ordered.first { it.block.id == "c1" }
        assertTrue("全部折叠后第一章在集合里", collapsed.contains(c1.key))
        collapsed = collapsed - c1.key
        assertTrue(
            "点开后 a1 应可见，实际：${tree.visible(collapsed).map { it.block.id }}",
            "a1" in tree.visible(collapsed).map { it.block.id },
        )
    }

    /** 章下有节：点章露出节，再点节才露出条文 */
    @Test
    fun chapterWithSectionRequiresTwoTaps() {
        val tree = ReaderTree.build(
            law(
                chapter("c1", "第一章"),
                section("s1", "第一节 一般规定"),
                article("a1", "第一条", 1),
            )
        )
        var collapsed = tree.defaultCollapsed(0)
        val c1 = tree.ordered.first { it.block.id == "c1" }
        val s1 = tree.ordered.first { it.block.id == "s1" }

        // 默认：只有章
        assertEquals(listOf("c1"), tree.visible(collapsed).map { it.block.id })

        // 点章 → 露出节
        collapsed = collapsed - c1.key
        assertEquals(listOf("c1", "s1"), tree.visible(collapsed).map { it.block.id })

        // 点节 → 露出条文
        collapsed = collapsed - s1.key
        assertEquals(listOf("c1", "s1", "a1"), tree.visible(collapsed).map { it.block.id })
    }

    /** 折叠态与箭头显示必须一致：折叠集包含该 key 时表示「已收起」 */
    @Test
    fun collapsedFlagMatchesVisibility() {
        val tree = ReaderTree.build(
            law(
                chapter("c1", "第一章"), article("a1", "第一条", 1),
                chapter("c2", "第二章"), article("a2", "第二条", 2),
            )
        )
        var collapsed = tree.allCollapsed()
        for (node in tree.ordered) {
            val shown = collapsed.contains(node.key)
            val hasVisibleChild = tree.visible(collapsed).any { it.ancestors.contains(node.key) }
            if (node.block.isHeading) {
                assertFalse("折叠的标题不应有可见子项：${node.key}", shown && hasVisibleChild)
            }
        }
        // 展开第一章后，第一章的子项可见，且它不在折叠集
        val c1 = tree.ordered.first { it.block.id == "c1" }
        collapsed = collapsed - c1.key
        assertTrue(tree.visible(collapsed).any { it.ancestors.contains(c1.key) })
    }
}
