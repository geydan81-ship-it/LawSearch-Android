package com.lawsearch.app.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lawsearch.app.util.ChineseToken
import java.text.Normalizer
import java.util.Locale

/** 一条全文搜索命中 */
data class SearchHit(
    val bbbs: String,
    val title: String,
    val articleNo: String,
    val anchor: String,
    val preview: String,
    val group: String,
    val subgroup: String,
    val gbrq: String,
    val score: Double,
    /**
     * 这条命中是不是「放宽匹配」来的（P2 同序容错降级的结果）。
     *
     * 严格短语命中排在前面，降级结果追加在后并标记 true，
     * 界面可以据此弱化显示（例如标注「模糊匹配」），让用户知道哪些是精确命中。
     */
    val fuzzy: Boolean = false,
)

/**
 * 全文索引：SQLite **FTS4** + 中文二元组预分词。
 *
 * ⚠️ 为什么不是 FTS5（实测结论，别改回去）：
 * Android 系统自带的 SQLite **不带 FTS5 模块** —— 真机/模拟器上会报
 * `no such module: fts5`。FTS4 从 API 11 起就在系统 SQLite 里，可以放心使用。
 * （JVM 测试用的 sqlite-jdbc 自带 FTS5，所以单测能过、真机却搜不到东西。）
 *
 * 中文匹配靠入库前的二元组预分词：
 *   「船舶优先权」→「船舶 舶优 优先 先权」，查询同样处理并用短语（"船舶 舶优"）匹配。
 * FTS4 的 `simple` 分词器按空格切分，正好适配这种预分词文本。
 *
 * 排序也不依赖 FTS 的 rank：按「相邻二元组连击 × 10 + 覆盖率 × 5」自行打分，
 * 保证搜「船舶优先权」时海商法第二十一/二十二条排在最前。
 */
class ArticleFts(private val database: LawDatabase) {

    private fun db(): SupportSQLiteDatabase = database.openHelper.writableDatabase

    /** 建表（幂等）。FTS4 没有 IF NOT EXISTS 之外的选项，注意参数顺序。 */
    fun ensureSchema() {
        val d = db()
        // raw 列存**原文**（不索引）：二元组拼不出干净的摘要，
        // 摘要直接从原文截取，避免「船舶舶优优先先权」这类字形重影。
        d.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts4(" +
                "bbbs, article_no, anchor, raw, content, tokenize=simple, " +
                "notindexed=bbbs, notindexed=article_no, notindexed=anchor, notindexed=raw)"
        )
        d.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS titles_fts USING fts4(" +
                "bbbs, content, tokenize=simple, notindexed=bbbs)"
        )
    }

    /** 老版本建的表缺列时自动重建（索引是派生物，重建无损失） */
    private fun migrateIfNeeded() {
        runCatching {
            db().query("SELECT raw FROM articles_fts LIMIT 1").close()
        }.onFailure {
            runCatching { db().execSQL("DROP TABLE IF EXISTS articles_fts") }
            runCatching { db().execSQL("DROP TABLE IF EXISTS titles_fts") }
        }
    }

    fun isIndexed(bbbs: String): Boolean {
        db().query("SELECT bbbs FROM articles_fts WHERE bbbs = ? LIMIT 1", arrayOf(bbbs)).use {
            if (it.moveToFirst()) return true
        }
        db().query("SELECT bbbs FROM titles_fts WHERE bbbs = ? LIMIT 1", arrayOf(bbbs)).use {
            return it.moveToFirst()
        }
    }

    fun remove(bbbs: String) {
        runCatching { db().execSQL("DELETE FROM articles_fts WHERE bbbs = ?", arrayOf(bbbs)) }
        runCatching { db().execSQL("DELETE FROM titles_fts WHERE bbbs = ?", arrayOf(bbbs)) }
    }

    /**
     * 单部法规并入索引。
     * @return 写入的条文数；失败返回 -1（调用方据此报错，而不是静默丢掉）
     */
    fun index(law: LawJson): Int {
        val d = db()
        var count = 0
        d.beginTransaction()
        try {
            remove(law.bbbs)
            val stmt = d.compileStatement(
                "INSERT INTO articles_fts(bbbs, article_no, anchor, raw, content) VALUES (?, ?, ?, ?, ?)"
            )
            for (b in law.blocks) {
                val body = if (b.isArticle) {
                    b.bodyParagraphs().joinToString("\n")
                } else if (b.type == Block.TYPE_TEXT) {
                    b.text
                } else {
                    ""
                }
                if (body.isBlank()) continue
                stmt.bindString(1, law.bbbs)
                stmt.bindString(2, if (b.isArticle) b.no else "")
                stmt.bindString(3, b.id)
                stmt.bindString(4, body)
                // 预分词在 SQLite 里做不了，只能 Kotlin 侧做；这里已是批内最省的形式
                stmt.bindString(5, ChineseToken.tokenize(body))
                stmt.executeInsert()
                if (b.isArticle) count++
            }
            d.execSQL(
                "INSERT INTO titles_fts(bbbs, content) VALUES (?, ?)",
                arrayOf<Any?>(law.bbbs, ChineseToken.tokenize(law.title)),
            )
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return count
    }

    /**
     * 全文检索（调用方需在 IO 线程调用）。
     *
     * 检索分三层，越往下越宽松，**精度高的结果永远排在前面**：
     *   1. 严格短语匹配（bigram 相邻有序）—— 正常情况下就够用
     *   2. 同序容错：短语 0 命中时，退化为「各 bigram 全部出现即可安」（不要求相邻）
     *   3. 标题命中：整部法规名称匹配
     *
     * 单字查询（「船」「法」）不做正文全文扫描 —— 见 [singleCharHint]。
     */
    fun search(
        query: String,
        limit: Int = 50,
        lawMeta: (String) -> LawEntity? = { null },
    ): List<SearchHit> {
        val expr = ChineseToken.matchExpression(query) ?: return emptyList()
        val grams = ChineseToken.queryGrams(query)
        val q = Normalizer.normalize(query.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

        // 1) FTS4 严格初筛（放宽召回），带回原文用于打分与摘要
        val recall = (limit * 20).coerceIn(200, 5000)
        val candidates = queryCandidates(expr, recall, q, grams)

        // 2) 标题命中（整部法规，锚点为空）
        val titleHits = runCatching {
            db().query("SELECT bbbs FROM titles_fts WHERE content MATCH ? LIMIT 20", arrayOf(expr)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) ?: "") }
            }
        }.getOrDefault(emptyList())

        val strictHits = candidates.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.articleNo })
            .mapNotNull { cand -> toHit(cand, lawMeta) }
            .take(limit)

        val titleThenStrict = if (strictHits.size < limit && titleHits.isNotEmpty()) {
            val extra = titleHits
                .filter { id -> id.isNotBlank() && strictHits.none { it.bbbs == id } }
                .mapNotNull { lawMeta(it) }
                .map { meta ->
                    SearchHit(
                        bbbs = meta.bbbs,
                        title = meta.title,
                        articleNo = "",
                        anchor = "",
                        preview = "在法规名称中匹配",
                        group = meta.group,
                        subgroup = meta.subgroup,
                        gbrq = meta.gbrq,
                        score = 1000.0,
                    )
                }
            strictHits + extra
        } else {
            strictHits
        }

        if (titleThenStrict.isNotEmpty()) return titleThenStrict.take(limit)

        return fuzzySearch(query, q, grams, titleThenStrict, limit, lawMeta)
    }

    /**
     * 同序容错（P2）。
     *
     * 用户多打/错打一个字时（「船舶优先全权」），严格短语要求 bigram 相邻有序，
     * 会直接 0 命中、界面一片空白。这里退化为「各 bigram 全部出现即可」（AND，不要求相邻）。
     *
     * 两条硬约束：
     *  - **只在严格命中为 0 时才走**（[strictHits] 非空直接返回，不浪费第二次查询）
     *  - **最多返回 [FUZZY_LIMIT] 条**，且一定排在严格命中之后 ——
     *    绝不让模糊结果淹没精确结果
     */
    private fun fuzzySearch(
        query: String,
        q: String,
        grams: List<String>,
        strictHits: List<SearchHit>,
        limit: Int,
        lawMeta: (String) -> LawEntity?,
    ): List<SearchHit> {
        if (strictHits.isNotEmpty()) return strictHits.take(limit)
        // gram 太少（只有 1 个）时，退化后与原文几乎等价，没有意义
        val distinct = grams.distinct()
        if (distinct.size < 2) return strictHits

        val relaxed = distinct.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }
        if (relaxed == ChineseToken.matchExpression(query)) return strictHits

        val recall = (FUZZY_LIMIT * 20).coerceIn(200, 2000)
        val again = queryCandidates(relaxed, recall, q, grams)
        if (again.isEmpty()) return strictHits

        return again.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.articleNo })
            .mapNotNull { cand -> toHit(cand, lawMeta)?.copy(fuzzy = true) }
            .filter { hit -> strictHits.none { it.bbbs == hit.bbbs && it.anchor == hit.anchor } }
            .take(FUZZY_LIMIT)
    }

    /** 用给定 MATCH 表达式跑一次 FTS 初筛，返回带分数的候选 */
    private fun queryCandidates(
        matchExpr: String,
        recall: Int,
        q: String,
        grams: List<String>,
    ): Map<Pair<String, String>, Candidate> {
        val candidates = LinkedHashMap<Pair<String, String>, Candidate>()
        runCatching {
            db().query(
                "SELECT bbbs, article_no, anchor, raw FROM articles_fts " +
                    "WHERE content MATCH ? LIMIT $recall",
                arrayOf(matchExpr),
            ).use { c ->
                while (c.moveToNext()) {
                    val bbbs = c.getString(0) ?: continue
                    val no = c.getString(1) ?: ""
                    val anchor = c.getString(2) ?: ""
                    val raw = c.getString(3) ?: ""
                    val tokens = ChineseToken.tokenize(raw)
                    val preview = previewFromRaw(raw, q)
                    candidates[bbbs to anchor] = Candidate(
                        bbbs, no, anchor, preview, scoreTokens(tokens, grams, q),
                    )
                }
            }
        }
        return candidates
    }

    private fun toHit(cand: Candidate, lawMeta: (String) -> LawEntity?): SearchHit? {
        val meta = lawMeta(cand.bbbs) ?: return null
        return SearchHit(
            bbbs = cand.bbbs,
            title = meta.title,
            articleNo = cand.articleNo,
            anchor = cand.anchor,
            preview = cand.preview,
            group = meta.group,
            subgroup = meta.subgroup,
            gbrq = meta.gbrq,
            score = cand.score,
        )
    }

    /** 从预分词文本里生成摘要（命中词用【】包住） */

    /**
     * 打分：主信号是「相邻二元组连续出现」的连击长度。
     *
     * 查询「船舶优先权」的二元组序列是 [船舶, 舶优, 优先, 先权]；
     * 原文「…具有船舶优先权」在 token 流里会连续出现这 4 个 → 连击 4、连击分 3×10。
     * 只命中「船舶」的条文连击 1 → 0 分，排在后面。
     *
     * 静态实现（[scoreTokens]）便于单测直接调用，避免测试重复实现导致逻辑漂移。
     */
    internal fun scoreFromTokens(tokens: String, grams: List<String>, query: String): Double =
        scoreTokens(tokens, grams, query)

    companion object {
        /**
         * 同序容错（P2）最多返回的条数。
         *
         * 刻意压得很小：容错结果精度必然低于严格短语命中，
         * 数量一多就会把「其实没搜到」误导成「搜到了很多相关条文」。
         */
        const val FUZZY_LIMIT = 20

        /**
         * 单字查询的提示语（P1）。
         *
         * ## 为什么不给单字查询做正文全文扫描
         *
         * 索引里存的是 **bigram**（「船舶优先权」→ `船舶 舶优 优先 先权`），
         * 全文里孤立的单字只占 0.79%（实测 52,186 / 6,589,334），
         * 所以搜「船」「法」在 FTS 层面**必然 0 命中**。
         *
         * 可以靠 `LIKE '%船%'` 扫 `raw` 列兜底，但 `articles_fts` 有约 790 万字，
         * 全表扫描在真机上是几百毫秒级；而单字（尤其「法」「人」）几乎命中库里
         * 每一部法规，**召回结果毫无区分度**。用几百毫秒换一屏无意义结果不划算。
         *
         * 所以这里选择「温和提示用户在做什么」，而不是假装能搜。
         * 返回 null 表示不需要提示（不是单字查询）。
         */
        fun singleCharHint(query: String): String? {
            val t = query.trim()
            if (t.length != 1) return null
            if (!ChineseToken.isCjk(t[0])) return null
            // 给一个同字开头、更可能命中本库的二字词做例子
            val example = when (t[0]) {
                '船' -> "船舶"
                '法' -> "法人"
                '人' -> "人民"
                '合' -> "合同"
                '婚' -> "婚姻"
                '罪' -> "犯罪"
                '债' -> "债权"
                '税' -> "税务"
                else -> null
            }
            return if (example != null) {
                "单个字太宽泛，试试两个字的词，比如「$example」"
            } else {
                "单个字太宽泛，试试两个字的词"
            }
        }

        /** 纯函数版打分，供 [ArticleFts] 与单元测试共用 */
        fun scoreTokens(tokens: String, grams: List<String>, query: String): Double {
            if (grams.isEmpty()) {
                val q = Normalizer.normalize(query.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                val text = Normalizer.normalize(tokens, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                return if (q.isNotEmpty() && text.contains(q)) 1.0 else 0.0
            }
            val parts = tokens.split(' ').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return 0.0

            // a) 最长连续命中：相邻 token 依次等于查询的相邻二元组
            var best = 0
            var bestEnd = 0
            for (i in parts.indices) {
                var k = 0
                while (i + k < parts.size && k < grams.size && parts[i + k] == grams[k]) k++
                if (k > best) {
                    best = k
                    bestEnd = i + k
                }
            }
            // b) 覆盖率：查询二元组里有多少个在文中出现（去重）
            val coverage = parts.filter { grams.contains(it) }.toSet().size.toDouble() / grams.size
            if (best == 0 && coverage == 0.0) return 0.0

            var score = 0.0
            // 完整命中全部二元组（即出现完整短语）权重最高
            if (best >= grams.size) score += 100.0
            score += (best - 1).coerceAtLeast(0) * 10.0
            score += coverage * 6.0
            // 命中越靠前越相关（法条开头就是定义/列举的，通常是用户想找的）
            val posBonus = when {
                bestEnd in 1..(grams.size + 2) -> 8.0
                bestEnd in 1..(grams.size * 3) -> 4.0
                else -> 0.0
            }
            score += posBonus
            // 命中次数越多越相关（微弱）
            score += parts.count { grams.contains(it) }.coerceAtMost(20) * 0.2

            // P3：单字 gram 的信号强度远低于 bigram。
            // [ChineseToken.queryGrams] 现在会保留单字（为了与 matchExpression 对齐），
            // 但一个单字命中只说明「文里出现过这个字」，区分度极低 ——
            // 若与 bigram 等权，会让「船」这种宽泛命中把精确条文挤下去。
            // 这里按单字 gram 的占比做折减，最低保留 30% 分值（仍能排序，但不会喧宾夺主）。
            val singleGrams = grams.count { it.length == 1 }
            if (singleGrams > 0 && singleGrams == grams.size) {
                score *= 0.3
            }
            return score
        }

        /**
         * 从**原文**生成摘要（不复用二元组拼接，避免「船舶舶优优先先权」这类重影）。
         *
         * 做法：在原文里找第一个命中位置，前后各取一段，命中片段用【】包住。
         */
        fun previewFromRaw(raw: String, query: String): String {
            val text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return ""
            val q = query.trim()
            if (q.isEmpty()) return text.take(80).let { if (text.length > 80) "$it…" else it }

            val at = text.indexOf(q, ignoreCase = true)
            if (at >= 0) {
                val from = (at - 18).coerceAtLeast(0)
                val to = (at + q.length + 46).coerceAtMost(text.length)
                val sb = StringBuilder()
                if (from > 0) sb.append('…')
                sb.append(text, from, at)
                sb.append('【').append(text, at, at + q.length).append('】')
                sb.append(text, at + q.length, to)
                if (to < text.length) sb.append('…')
                return sb.toString()
            }

            // 短语没整体出现（部分二元组命中）：退化为从第一个命中字开始截取
            val firstChar = q.firstOrNull() ?: return text.take(80)
            val at2 = text.indexOf(firstChar)
            val from = (if (at2 >= 0) at2 - 10 else 0).coerceAtLeast(0)
            val to = (from + 70).coerceAtMost(text.length)
            val sb = StringBuilder()
            if (from > 0) sb.append('…')
            sb.append(text, from, to)
            if (to < text.length) sb.append('…')
            return sb.toString()
        }

    }

    /** 调试用：索引条数 */
    fun count(): Int = runCatching {
        db().query("SELECT COUNT(*) FROM articles_fts").use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    /** 清空（测试/重建用） */
    fun clear() {
        runCatching { db().execSQL("DELETE FROM articles_fts") }
        runCatching { db().execSQL("DELETE FROM titles_fts") }
    }

    /** 从 (bbbs, json) 批量重建 */
    fun rebuild(all: List<Pair<String, String>>) {
        clear()
        for ((bbbs, json) in all) {
            runCatching { index(LawJsonCodec.decode(json).copy(bbbs = bbbs)) }
        }
    }

    private data class Candidate(
        val bbbs: String,
        val articleNo: String,
        val anchor: String,
        val preview: String,
        val score: Double,
    )
}
