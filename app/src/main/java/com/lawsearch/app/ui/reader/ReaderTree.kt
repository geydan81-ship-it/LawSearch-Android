package com.lawsearch.app.ui.reader

import com.lawsearch.app.data.Block
import com.lawsearch.app.data.LawJson

/**
 * 折叠式阅读器的树模型。
 *
 * 规格书的折叠规则：
 *  - 层级：编 > 分编 > 章 > 节 > 条
 *  - 默认展开到「章」级，条文与章下的节折叠
 *  - 折叠外层章时，内部已展开的节也随之隐藏
 *  - 「全部折叠」后可见条文数 = 0（只剩顶层标题）
 *  - 跳转到某条时，祖先章节自动展开
 *
 * 重要：`Block.id` 在数据里**不保证全局唯一**（例如各分编下的「第一节」都叫 s1），
 * 因此树节点的身份用「从根到自身的 id 路径」表示（[key]），锚点定位也用 key。
 */
class ReaderNode(
    val block: Block,
    val children: List<ReaderNode>,
    /** 祖先标题的 key 链（从外到内），用于跳转时展开与可见性判断 */
    val ancestors: List<String>,
    /** 该节点的唯一键：祖先 key 链 + 自己的 block.id */
    val key: String,
) {
    /** 块自身的 id（同一部法规内可能重复，仅用于跳转锚点的原始 id） */
    val id: String get() = block.id

    override fun equals(other: Any?): Boolean = other is ReaderNode && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "ReaderNode(${block.type}:${block.id}@$key)"
}

class ReaderTree private constructor(
    /** 顶层节点（编/章 …） */
    val roots: List<ReaderNode>,
    /** 所有节点（按出现顺序） */
    val ordered: List<ReaderNode>,
) {

    private val nodeByKey: Map<String, ReaderNode> = ordered.associateBy { it.key }

    val headingIds: List<String> = ordered.filter { it.block.isHeading }.map { it.block.id }

    val articleCount: Int = ordered.count { it.block.isArticle }

    /** 条文序号 → 节点（跳转用，构建时一次性建好） */
    private val articleIndex: Map<Int, ReaderNode> = buildMap {
        for (n in ordered) {
            val no = n.block.noNum ?: continue
            if (n.block.isArticle && !containsKey(no)) put(no, n)
        }
    }

    fun node(key: String): ReaderNode? = nodeByKey[key]

    /**
     * 按「块 id」定位节点。
     * 数据里 id 可能重复（分编下的节都叫 s1），此时返回第一个匹配项。
     */
    fun byBlockId(id: String): ReaderNode? = ordered.firstOrNull { it.block.id == id }

    /**
     * 按 block id 找唯一 key。
     *
     * 列表索引必须用 key 定位：`block.id` 在同一部法规里可能重复（各分编下的 s1），
     * 用 id 做 indexOfFirst 会跳到错误位置。找不到时回退成 id 本身。
     */
    fun nodeKeyOf(blockId: String): String =
        ordered.firstOrNull { it.block.id == blockId }?.key ?: blockId

    /**
     * 按条文序号（阿拉伯数字）定位节点。
     *
     * 用于阅读器的「跳到第 N 条」：`noNum` 是解析时从「第二十二条」换算出的整数。
     * 同一序号若出现多次（法规修订时偶见重复编号），取第一个。
     */
    fun articleByNo(no: Int): ReaderNode? =
        articleIndex[no] ?: ordered.firstOrNull { it.block.isArticle && it.block.noNum == no }

    /** 默认折叠集（规格书）：默认展开到章级 —— 条文与节折叠，编/章展开 */
    /**
     * 默认折叠集。
     *
     * @param threshold 条文数阈值：≤ 此值不折叠（短法规直接看到正文）。
     *                  默认用 [FoldRules.AUTO_COLLAPSE_MIN]；测试里传 0 可强制折叠。
     */
    fun defaultCollapsed(threshold: Int = FoldRules.AUTO_COLLAPSE_MIN): Set<String> =
        FoldRules.defaultCollapsed(ordered, threshold)

    /** 全部折叠：所有标题都收起来 → 可见条文 0 */
    fun allCollapsed(): Set<String> = FoldRules.allCollapsed(ordered)

    /**
     * 可见节点（按渲染顺序）。
     *
     * 规则：
     *  - 标题节点：自身可见；只有它**未**被折叠时，才继续展开子节点。
     *  - 非标题节点（条文 / 普通段落）：只要父标题展开就可见。
     *
     * ⚠️ 非标题节点**不看**折叠集：折叠状态只属于标题。
     * 早先这里写成 `if (!isHeading && folded) continue`，一旦折叠集里混入了条文 key
     * （例如旧版 defaultCollapsed 把所有 rank>=3 的都放进去），
     * 展开章之后条文依然被跳过，表现为「点章节没反应」。
     */
    fun visible(collapsed: Set<String>): List<ReaderNode> {
        val out = ArrayList<ReaderNode>(ordered.size)

        fun walk(nodes: List<ReaderNode>) {
            for (node in nodes) {
                val isHeading = node.block.isHeading
                out.add(node)
                if (isHeading && !collapsed.contains(node.key)) walk(node.children)
            }
        }

        walk(roots)
        return out
    }

    /** 锚点（块 id）所在节点的祖先 key 链 */
    fun ancestorsOf(id: String): List<String> = byBlockId(id)?.ancestors ?: emptyList()

    /** 展开某块 id 的祖先，返回新的折叠集（跳转到搜索结果时用） */
    fun expandTo(id: String, collapsed: Set<String>): Set<String> {
        val node = byBlockId(id) ?: return collapsed
        val toOpen = ArrayList<String>(node.ancestors.size + 1)
        toOpen.addAll(node.ancestors)
        toOpen.add(node.key)
        return collapsed - toOpen.toSet()
    }

    /** 可见条文数（验证/统计用） */
    fun visibleArticleCount(collapsed: Set<String>): Int = visible(collapsed).count { it.block.isArticle }

    companion object {
        private const val SEP = ">"

        /**
         * UI 层专用的合成块类型（**不写回数据层**）。
         *
         * 用来承载从 markdown 标题提升出来的块。不用 `chapter`/`section`，
         * 是因为那些类型的 `rank` 参与真实章节的层级与折叠计算，
         * 借用会让「第一章」后面的 markdown 标题错误地嵌套/顶掉真实章节。
         */
        const val TYPE_MD_HEADING = "md_heading"

        /** `## 序言` / `### 重伤` / `#### 5.1.1 重伤一级` */
        private val MD_HEADING = Regex("^(#{1,6})[ \\t]+(\\S.*)$")

        /**
         * 把「长得像 markdown 标题」的正文块提升成 [TYPE_MD_HEADING] 标题块。
         *
         * 只处理 `type` 为空或 [Block.TYPE_TEXT] 的块 —— 真实章节/条文绝不动。
         *
         * 提升后：
         *  - `label` ← 去掉 `#` 前缀的标题文字（[HeadingRow] 读的就是 label）；
         *  - `text`  ← 同步清空，避免别的消费方（复制全文等）再取到 `##`；
         *  - `type`  ← [TYPE_MD_HEADING]，`rank` 落在 `else -> 5` 分支。
         *
         * ⚠️ `rank=5` 意味着它**既不进标题栈也不可折叠**（见 [Block.isHeading] 的 rank in 0..3）。
         * 这是刻意的：markdown 标题在数据里没有可靠的层级关系，
         * 硬塞进标题栈会把后面的真实条文挂到错误父节点下，重演「显示错乱」。
         * 所以这里只解决「`## ` 字样出现在屏幕上」这一个可证实的缺陷，
         * **折叠/层级是 backlog**，见下发说明。
         */
        fun promoteMarkdownHeading(block: Block): Block {
            val t = block.type
            if (t.isNotEmpty() && t != Block.TYPE_TEXT) return block
            val raw = when {
                block.text.isNotBlank() -> block.text
                block.content.isNotBlank() -> block.content
                else -> return block
            }
            val m = MD_HEADING.matchEntire(raw.trim()) ?: return block
            val title = m.groupValues[2].trim()
            if (title.isEmpty()) return block
            return block.copy(
                type = TYPE_MD_HEADING,
                label = title,
                text = "",
                content = "",
                paragraphs = emptyList(),
            )
        }

        fun build(law: LawJson): ReaderTree = build(law.blocks)

        /**
         * 把扁平 blocks 归约为树。
         *
         * 1. 栈式扫描：用「父节点 key」而不是块 id 来记录层级，
         *    这样重复的块 id（多个分编下的 s1）也能正确挂载。
         * 2. 按父 key 分组，递归构造节点。
         */
        fun build(blocks: List<Block>): ReaderTree {
            class Item(val block: Block, val key: String, val parentKey: String?, val ancestors: List<String>)

            // Markdown 标题（`## 序言` / `### 重伤`）在数据里是 type="text" 的普通段落，
            // 直接渲染会在屏幕上原样显示 `## 序言` 四个字符 —— 用户反馈的「宪法显示错乱」之一。
            // 在建树阶段统一提升成标题，比在 TextBlock 里 regex 剥前缀更彻底：
            // 剥前缀只能去掉符号，标题层级/折叠/字号仍然没有；提升成标题才是正确语义。
            // 实测全量 1874 部法规里有 66 部、共 300 处这种块（见 MarkdownHeadingTest）。
            val normalized = blocks.map(::promoteMarkdownHeading)

            val items = ArrayList<Item>(blocks.size)
            // 栈里放 (rank, key)
            val stack = ArrayList<Pair<Int, String>>()
            // 防止极深嵌套把递归打爆（畸形数据里可能出现连续几十层标题）
            val maxDepth = 32
            // key 去重计数：同一父级下 block id 可能重复（真实数据里出现过），
            // 单纯 parentKey + id 会生成重复 key，破坏树结构与折叠状态。重复时追加 #n。
            val keyCount = HashMap<String, Int>()

            for ((index, b) in normalized.withIndex()) {
                if (b.isHeading) {
                    while (stack.isNotEmpty() && stack.last().first >= b.rank) stack.removeAt(stack.size - 1)
                    // 超深时截断，避免递归栈溢出
                    while (stack.size >= maxDepth) stack.removeAt(0)
                }
                val parentKey = stack.lastOrNull()?.second
                // ⚠️ 关键：约 154/766 部法规的 blocks 缺 id 字段，反序列化后是空串。
                // 根级节点的 key 直接取 id，多个空 id 会变成同一个 key ""，
                // 而 Compose 的 LazyColumn 把空串当作「未提供 key」并抛
                // IllegalArgumentException: Key "" was already used。
                // 这里先用块下标给空 id 兜底，保证 key 永远非空且唯一。
                // （司法解释 73 部 100% 命中此问题，这就是「点开就闪退」的根因。）
                val idPart = b.id.ifBlank { "blk$index" }
                val rawKey = if (parentKey == null) idPart else parentKey + SEP + idPart
                val seen = keyCount.getOrDefault(rawKey, 0)
                keyCount[rawKey] = seen + 1
                val key = if (seen == 0) rawKey else rawKey + "#" + seen
                val ancestors = stack.map { it.second }
                items.add(Item(b, key, parentKey, ancestors))
                if (b.isHeading) stack.add(b.rank to key)
            }

            val childrenOfKey = HashMap<String, MutableList<Item>>()
            val roots = ArrayList<Item>()
            for (it in items) {
                if (it.parentKey == null) roots.add(it)
                else childrenOfKey.getOrPut(it.parentKey) { ArrayList() }.add(it)
            }

            fun nodeOfInternal(item: Item): ReaderNode = ReaderNode(
                block = item.block,
                children = childrenOfKey[item.key]?.map { nodeOfInternal(it) }.orEmpty(),
                ancestors = item.ancestors,
                key = item.key,
            )

            val rootNodes = roots.map { nodeOfInternal(it) }
            val ordered = ArrayList<ReaderNode>(blocks.size)
            fun collect(nodes: List<ReaderNode>) {
                for (n in nodes) {
                    ordered.add(n)
                    collect(n.children)
                }
            }
            collect(rootNodes)
            return ReaderTree(rootNodes, ordered)
        }
    }
}
