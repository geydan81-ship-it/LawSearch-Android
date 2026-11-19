package com.lawsearch.app.ui.reader

import com.lawsearch.app.data.Block

/**
 * 折叠集规则。
 *
 * 规格书语义：
 *  - 默认：**只露出编/分编/章标题**，章下的节与条文都收起（可见条文 0）。
 *  - 全部折叠：编/分编/章/节 全部收起，只剩最外层标题。
 *  - 全部展开：什么都不折叠。
 *
 * 用 [Block.rank]：编 0 / 分编 1 / 章 2 / 节 3 / 条 4 / 其他 5。
 * 集合里存节点 key（唯一），不是块 id。
 *
 * ⚠️ 集合里**只放标题节点**（rank 0..3）。条文（rank 4）不参与折叠标记 ——
 * 它的可见性完全由所属标题决定。
 *
 * 「默认展开到章级」的实现：
 *   把**章本身**放进折叠集。这样章标题可见、章内部（节与条文）全部收起，
 *   点一下章就展开，条文随之出现。之前只折叠节，导致没有节的章一点开就刷出上百条条文。
 */
object FoldRules {

    private const val RANK_CHAPTER = 2
    private const val RANK_SECTION = 3

    /**
     * 超过这个条数才默认折叠章。
     *
     * 之前是「一律默认折叠」，实测用户反馈：打开《人民法院法庭规则》（27 条）这类
     * 短法规，首屏只有一排 `▶ 第一章`，一行正文都没有，**会以为内容丢了**。
     * 长法规（如《人民检察院刑事诉讼规则》684 条）折叠是为了避免首屏卡顿，
     * 短法规折叠纯粹是负体验。
     */
    const val AUTO_COLLAPSE_MIN = 30

    /**
     * 默认折叠集：
     *  - **短法规**（条文 ≤ [threshold]）：不折叠，直接看到正文
     *  - **长法规**：编/分编展开，章与节收起，只露出章标题
     *
     * @param threshold 自动折叠的条文数阈值；测试里传 0 可强制折叠
     */
    fun defaultCollapsed(
        nodes: List<ReaderNode>,
        threshold: Int = AUTO_COLLAPSE_MIN,
    ): Set<String> {
        val articleCount = nodes.count { it.block.isArticle }
        if (articleCount <= threshold) {
            // 短法规：什么都不折叠，打开就看得到正文
            return emptySet()
        }
        val keys = ArrayList<String>(nodes.size)
        for (node in nodes) {
            if (node.block.isHeading && node.block.rank >= RANK_CHAPTER) keys.add(node.key)
        }
        return HashSet(keys)
    }

    /** 全部折叠：所有标题都收起 → 可见条文 0 */
    fun allCollapsed(nodes: List<ReaderNode>): Set<String> {
        val keys = ArrayList<String>(nodes.size)
        for (node in nodes) {
            if (node.block.isHeading && node.block.rank <= RANK_SECTION) keys.add(node.key)
        }
        return HashSet(keys)
    }
}
