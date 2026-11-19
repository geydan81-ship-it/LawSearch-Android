package com.lawsearch.app.util

/**
 * 中文二元组（bigram）分词。
 *
 * SQLite FTS5 的 unicode61 分词器对中文按**单字**切分，
 * 搜「船舶优先权」会命中任何包含这几个字的条文，精度很差。
 * 这里在入库和查询时都把文本转成二元组：「船舶优先权」→「船舶 舶优 优先 先权」，
 * 再用短语匹配（"船舶 舶优 优先 先权"）保证「船舶」与「舶优」在原文中连续出现，
 * 召回率和精度都显著提升。
 *
 * 单字查询（「法」「船」）保留单字词项，直接按字匹配。
 */
object ChineseToken {

    /** CJK 统一表意文字（含扩展 A） */
    private const val CJK_START = 0x4E00
    private const val CJK_END = 0x9FFF
    private const val CJK_EXT_A_START = 0x3400
    private const val CJK_EXT_A_END = 0x4DBF

    fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in CJK_START..CJK_END) || (c in CJK_EXT_A_START..CJK_EXT_A_END)
    }

    /** 是否为「词字符」：CJK 或字母数字。标点、空白、换行都算分隔符。 */
    private fun isWordChar(ch: Char): Boolean = isCjk(ch) || ch.isLetterOrDigit()

    /**
     * 文本 → 二元组序列（已小写化）。
     *
     * ## 切分规则（**最容易搞错的地方**，写测试前务必看这段）
     *
     * 按「连续的 CJK 段」为单位处理，段内按 2 字滑窗：
     *
     * ```
     * 输入            输出                      说明
     * ─────────────────────────────────────────────────────────────
     * "船舶"      →  [船舶]                    连续 2 字 → 1 个 bigram，**不产生单字**
     * "船舶优先"  →  [船舶, 舶优, 优先]         n 字 → (n-1) 个 bigram
     * "船"        →  [船]                      孤立单字（前后是标点/空白/串首尾）→ 单字
     * "船 舶"     →  [船, 舶]                  被空格隔开 → **两个**独立的单字
     * "船舶的"    →  [船舶, 舶的]              全部连续，仍是 bigram
     * "a b"       →  [a, b]                    ASCII 连续串整词
     * ```
     *
     * ⚠️ **常见误解**：以为「船舶」会产出 `[船, 舶]` 两个单字。
     * **不会。** 它是两个连续 CJK 字符，走滑窗分支，产出 1 个 bigram `船舶`。
     * 只有 `len == 1`（该 CJK 段长度就是 1，即前后都被分隔符隔开）才产出单字。
     *
     * 这个区别直接影响：
     *  - 索引里几乎没有单字 token → 单字查询在 FTS 层面必然 0 命中
     *    （实测单字 token 仅占 0.79%：52,186 / 6,589,334）
     *  - `queryGrams` / `matchExpression` 的 token 集合对不对得上
     *
     * 我为这个误解写过错误的单元测试断言（`queryGrams("船舶") == ["船","舶"]`），
     * 见 `SearchQualityTest.queryGramsKeepsSingleCharToMatchExpression` 的注释。
     */
    fun bigrams(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (!isWordChar(ch)) {
                i++
                continue
            }
            if (isCjk(ch)) {
                // 收集连续的 CJK 段
                var j = i
                while (j < n && isCjk(text[j])) j++
                val len = j - i
                if (len == 1) {
                    out.add(text.substring(i, j))
                } else {
                    for (k in i until j - 1) out.add(text.substring(k, k + 2))
                }
                i = j
            } else {
                var j = i
                while (j < n && isWordChar(text[j]) && !isCjk(text[j])) j++
                out.add(text.substring(i, j).lowercase())
                i = j
            }
        }
        return out
    }

    /** 文本 → 用空格连接的二元组：直接存进 FTS5 的 content 列 */
    fun tokenize(text: String): String = bigrams(text).joinToString(" ")

    /**
     * 查询串 → FTS4 MATCH 表达式。
     *
     * ## 为什么现在这样写（踩过的坑）
     *
     * 之前是：每个 bigram 单独用双引号包成**独立短语**，短语之间 AND：
     * ```
     * ("不可" AND "可抗" AND "抗力")   ← 错
     * ```
     * 这要求「不可」「可抗」「抗力」各自作为独立 token 存在，而索引里
     * 是从正文连续切出的 bigram 序列（`... 力不 不可 可抗 抗力 力...`），
     * FTS4 的 AND 是「同一行包含」，不要求相邻，但也**不会把它们拼回连续片段**，
     * 实测「不可抗力」「正当防卫」这类词 0 命中，而「抗力」「防卫」有大量命中。
     *
     * 正确写法：把整个词的 bigram 放进**同一个短语**，短语内用空格分隔
     * （FTS4 短语内多 token 表示「相邻且有序」）：
     * ```
     * ("不可 可抗 抗力")   ← 对
     * ```
     * 这样要求三者连续出现，正是我们要的。
     *
     * 多个词之间仍用 AND 连接（要求全部出现）。
     * 会转义双引号，避免用户输入被当成 FTS 语法。
     */
    fun matchExpression(query: String): String? {
        val clauses = ArrayList<String>()
        for (raw in query.split(Regex("\\s+"))) {
            if (raw.isBlank()) continue
            val grams = bigrams(raw)
            if (grams.isEmpty()) continue
            // 整个词一个短语：phrase 内部用空格分隔即表示「相邻有序」
            val phrase = grams.joinToString(" ") { it.replace("\"", "\"\"") }
            clauses.add("(\"$phrase\")")
        }
        if (clauses.isEmpty()) return null
        return clauses.joinToString(" AND ")
    }

    /**
     * 判分用的查询二元组集合。
     *
     * ## 为什么不再过滤单字（P3）
     *
     * 原实现是 `bigrams(query).distinct().filter { it.length >= 2 }`，
     * 而 [matchExpression] **不过滤**。两者 token 集合不一致会导致同步漂移：
     * 当查询串出现被空格/标点隔开的单字时（「船　舶」），
     * [ArticleFts.scoreTokens] 拿到的 gram 集合与实际匹配到的 token 对不上，
     * 打分恒为 0 → 排序退化成任意顺序（看起来像「搜出来的结果没按相关度排」）。
     *
     * 现在统一保留单字，与 [matchExpression] 完全一致。
     * 单字 gram 的**权重**在 [ArticleFts.scoreTokens] 里单独调低，
     * 不在这里删掉 —— 「不参与匹配」和「匹配到但分值低」是两回事。
     */
    fun queryGrams(query: String): List<String> = bigrams(query).distinct()
}
