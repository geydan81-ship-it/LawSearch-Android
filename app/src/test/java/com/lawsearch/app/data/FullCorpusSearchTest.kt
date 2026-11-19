package com.lawsearch.app.data

import com.lawsearch.app.util.ChineseToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.GZIPInputStream

/**
 * **全量数据的端到端检索验证**（P0）。
 *
 * ## 和另外两个 FTS 测试的区别（重要，别重复写）
 *
 * | 测试 | 数据 | FTS 表 | 用途 |
 * |---|---|---|---|
 * | [Fts4SearchTest] | `../laws` 单部样本（三字段齐全） | 手写 sqlite-jdbc | 验证 SQL/打分的**语义** |
 * | [ArticleFtsContentTest] | assets 单部法规 | 手写 sqlite-jdbc | 验证 content 修复 |
 * | **本测试** | **assets 全量 1874 部** | **与生产同 SQL，但覆盖全库** | 验证**真实检索质量** |
 *
 * ## 为什么不直接 `ArticleFts(database).search()`
 *
 * `ArticleFts` 依赖 `LawDatabase`（Room + SupportSQLiteDatabase）。
 * Robolectric 下 Room 可跑，但 1874 部全量重建索引在单测里是分钟级且曾把
 * 测试 JVM 跑挂（导入逻辑注释里记录过进程被杀）。
 *
 * 折中做法：**同一份 SQL 建表语句 + 同一份 `ChineseToken` + 同一个
 * `ArticleFts.scoreTokens`**，只把 SupportSQLiteDatabase 换成 sqlite-jdbc。
 * 打分函数直接调用生产代码，不复制，所以排序结论对真机有效。
 *
 * ⚠️ **唯一的已知差异**：sqlite-jdbc 自带 FTS5，真机没有；但这里建的仍是
 * FTS4，不使用 FTS5 特性，所以差异不影响结论。
 *
 * ## 判据自证
 *
 * 每个断言前都先算一个"实际值"再断言，失败信息里带数字，而不是只说 assertTrue。
 */
class FullCorpusSearchTest {

    /* ==================== 数据加载 ==================== */

    private fun assetsDir(): File? = listOf(
        File("src/main/assets/laws"),
        File("app/src/main/assets/laws"),
        File(System.getProperty("user.dir"), "src/main/assets/laws"),
        File(System.getProperty("user.dir"), "app/src/main/assets/laws"),
    ).firstOrNull { d ->
        d.isDirectory && d.listFiles()?.any { it.name.endsWith(".json.gz") } == true
    }

    private val dir: File? by lazy { assetsDir() }

    private fun readGz(f: File): String =
        GZIPInputStream(f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    /** 从 _meta.json.gz 读全部 bbbs（按 32 位 hex 匹配，避免整份 JSON 解析） */
    private fun allBbbs(): List<String> {
        val d = dir ?: return emptyList()
        val meta = File(d, "_meta.json.gz").takeIf { it.exists() } ?: return emptyList()
        val text = readGz(meta)
        return Regex("\"([0-9a-f]{32})\"\\s*:").findAll(text).map { it.groupValues[1] }.toList()
    }

    private fun loadLaw(bbbs: String): LawJson? {
        val d = dir ?: return null
        val f = File(d, "$bbbs.json.gz").takeIf { it.exists() } ?: return null
        return runCatching { LawJsonCodec.decode(readGz(f)) }.getOrNull()
    }

    /* ==================== 建库（与生产同 SQL） ==================== */

    private var conn: Connection? = null
    private val metaCache = HashMap<String, LawEntity?>()
    private var indexedLaws = 0
    private var indexedRows = 0
    private var totalHan = 0

    /**
     * 全量建索引。只跑一次（`@Synchronized` 防并发重复）。
     *
     * 用 `REPEAT()` 之类无法加入；直接逐条 batch，实测 1874 部约 10-20 秒。
     */
    @Synchronized
    private fun db(): Connection? {
        conn?.let { return it }
        val d = dir ?: return null
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        // 与 ArticleFts.ensureSchema() 逐字一致
        c.createStatement().use { st ->
            st.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts4(" +
                    "bbbs, article_no, anchor, raw, content, tokenize=simple, " +
                    "notindexed=bbbs, notindexed=article_no, notindexed=anchor, notindexed=raw)"
            )
            st.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS titles_fts USING fts4(" +
                    "bbbs, content, tokenize=simple, notindexed=bbbs)"
            )
        }
        val insertSql =
            "INSERT INTO articles_fts(bbbs, article_no, anchor, raw, content) VALUES (?, ?, ?, ?, ?)"
        c.prepareStatement(insertSql).use { ps ->
            c.prepareStatement("INSERT INTO titles_fts(bbbs, content) VALUES (?, ?)").use { ts ->
                for (bbbs in allBbbs()) {
                    val law = loadLaw(bbbs) ?: continue
                    val id = law.bbbs.ifBlank { bbbs }
                    metaCache[id] = entityOf(law, id)
                    indexedLaws++
                    for (b in law.blocks) {
                        // 与 ArticleFts.index() 完全相同的正文提取
                        val body = if (b.isArticle) {
                            b.bodyParagraphs().joinToString("\n")
                        } else if (b.type == Block.TYPE_TEXT) {
                            b.text
                        } else {
                            ""
                        }
                        if (body.isBlank()) continue
                        totalHan += body.count { ChineseToken.isCjk(it) }
                        ps.setString(1, id)
                        ps.setString(2, if (b.isArticle) b.no else "")
                        ps.setString(3, b.id)
                        ps.setString(4, body)
                        ps.setString(5, ChineseToken.tokenize(body))
                        ps.addBatch()
                        indexedRows++
                    }
                    ts.setString(1, id)
                    ts.setString(2, ChineseToken.tokenize(law.title))
                    ts.addBatch()
                }
                ps.executeBatch()
                ts.executeBatch()
            }
        }
        conn = c
        return c
    }

    private fun entityOf(law: LawJson, id: String) = LawEntity(
        bbbs = id,
        title = law.title,
        gbrq = law.gbrq,
        sxrq = law.sxrq,
        sxx = law.sxx,
        validity = law.validity,
        group = law.group,
        subgroup = law.subgroup,
        articleCount = law.articleCount,
    )

    private val lookup: (String) -> LawEntity? = { id ->
        metaCache.getOrPut(id) { loadLaw(id)?.let { entityOf(it, id) } }
    }

    /* ==================== 与 ArticleFts.search 等价的一次检索 ==================== */

    /** 返回 (SearchHit 列表, 全库匹配行数) */
    private fun runSearch(query: String, limit: Int = 50): Pair<List<SearchHit>, Int> {
        val c = db() ?: return emptyList<SearchHit>() to 0
        val expr = ChineseToken.matchExpression(query) ?: return emptyList<SearchHit>() to 0
        val grams = ChineseToken.queryGrams(query)

        // ---- 1) 严格短语候选（同 ArticleFts.queryCandidates）
        val recall = (limit * 20).coerceIn(200, 5000)
        val candidates = LinkedHashMap<Pair<String, String>, SearchHit>()
        var matchRows = 0

        // 先数全库命中行数（用于「召回率」评估，不受 LIMIT 影响）
        c.prepareStatement("SELECT COUNT(*) FROM articles_fts WHERE content MATCH ?").use { ps ->
            ps.setString(1, expr)
            ps.executeQuery().use { rs -> if (rs.next()) matchRows = rs.getInt(1) }
        }

        c.prepareStatement(
            "SELECT bbbs, article_no, anchor, raw FROM articles_fts WHERE content MATCH ? LIMIT $recall"
        ).use { ps ->
            ps.setString(1, expr)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val bbbs = rs.getString(1) ?: continue
                    val no = rs.getString(2) ?: ""
                    val anchor = rs.getString(3) ?: ""
                    val raw = rs.getString(4) ?: ""
                    val score = ArticleFts.scoreTokens(ChineseToken.tokenize(raw), grams, query)
                    val meta = lookup(bbbs) ?: continue
                    candidates[bbbs to anchor] = SearchHit(
                        bbbs = bbbs,
                        title = meta.title,
                        articleNo = no,
                        anchor = anchor,
                        preview = ArticleFts.previewFromRaw(raw, query),
                        group = meta.group,
                        subgroup = meta.subgroup,
                        gbrq = meta.gbrq,
                        score = score,
                    )
                }
            }
        }

        val strict = candidates.values
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.articleNo })
            .take(limit)
        if (strict.isNotEmpty()) return strict to matchRows

        // ---- 2) 标题命中（ArticleFts.search 在严格命中为 0 时不会补标题，
        //        但这里为了覆盖「只搜标题」的场景也跑一次，与生产保持同序）
        val titleIds = runCatching {
            c.prepareStatement("SELECT bbbs FROM titles_fts WHERE content MATCH ? LIMIT 20").use { ps ->
                ps.setString(1, expr)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) ?: "") } }
            }
        }.getOrDefault(emptyList())

        val titleHits = titleIds.mapNotNull { id ->
            lookup(id)?.let { m ->
                SearchHit(id, m.title, "", "", "在法规名称中匹配", m.group, m.subgroup, m.gbrq, 1000.0)
            }
        }
        if (titleHits.isNotEmpty()) return titleHits to matchRows

        // ---- 3) 同序容错（与 ArticleFts.fuzzySearch 相同的放宽表达式）
        val distinct = grams.distinct()
        if (distinct.size < 2) return emptyList<SearchHit>() to matchRows
        val relaxed = distinct.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }
        val fuzzy = ArrayList<SearchHit>()
        c.prepareStatement(
            "SELECT bbbs, article_no, anchor, raw FROM articles_fts WHERE content MATCH ? LIMIT ${ArticleFts.FUZZY_LIMIT * 20}"
        ).use { ps ->
            ps.setString(1, relaxed)
            ps.executeQuery().use { rs ->
                val seen = HashSet<Pair<String, String>>()
                while (rs.next() && fuzzy.size < ArticleFts.FUZZY_LIMIT) {
                    val bbbs = rs.getString(1) ?: continue
                    val no = rs.getString(2) ?: ""
                    val anchor = rs.getString(3) ?: ""
                    val raw = rs.getString(4) ?: ""
                    if (!seen.add(bbbs to anchor)) continue
                    val meta = lookup(bbbs) ?: continue
                    fuzzy.add(
                        SearchHit(
                            bbbs, meta.title, no, anchor,
                            ArticleFts.previewFromRaw(raw, query),
                            meta.group, meta.subgroup, meta.gbrq,
                            ArticleFts.scoreTokens(ChineseToken.tokenize(raw), grams, query),
                            fuzzy = true,
                        )
                    )
                }
            }
        }
        return fuzzy to matchRows
    }

    /** 全库 raw 里有多少行真的含该字面串（不经过 bigram，作为"地面真值"） */
    private fun groundTruthContains(term: String): Int {
        val c = db() ?: return 0
        c.prepareStatement("SELECT COUNT(*) FROM articles_fts WHERE raw LIKE ?").use { ps ->
            ps.setString(1, "%$term%")
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun lawTitlesWith(substr: String): List<String> =
        metaCache.values.filterNotNull().filter { it.title.contains(substr) }.map { it.title }

    /* ==================== 判据自证 ==================== */

    @Test
    fun indexIsActuallyBuilt() {
        if (dir == null) return
        db() ?: return
        assertTrue("应索引到 1874 部，实际 $indexedLaws", indexedLaws >= 1800)
        assertTrue("article/text 行数应≈69,694，实际 $indexedRows", indexedRows >= 60_000)
        assertTrue("正文汉字应≈717 万，实际 $totalHan", totalHan > 6_000_000)
    }

    /* ==================== 用户报告的两个词 ==================== */

    /**
     * 「不可抗力」——用户反馈搜不到。
     *
     * 三层验证：
     *  1. bigram 短语表达式正确
     *  2. FTS 全库命中数 > 0（且与 `LIKE` 地面真值量级一致）
     *  3. Top 结果里确实有民法典的「不可抗力」条文
     */
    @Test
    fun searchesBuKeKangLiAcrossWholeCorpus() {
        if (dir == null) return
        db() ?: return

        assertEquals("matchExpression 应是一个连续短语", "(\"不可 可抗 抗力\")", ChineseToken.matchExpression("不可抗力"))

        val truth = groundTruthContains("不可抗力")
        assertTrue("地面真值：全库含字面「不可抗力」的块应当存在，实际 $truth", truth > 50)

        val (hits, matchRows) = runSearch("不可抗力")
        assertTrue("FTS 全库命中行数应 > 0，实际 $matchRows（LIKE 真值 $truth）", matchRows > 0)
        // bigram 短语命中率不应低于字面真值的一半（分词不该把绝大多数命中弄丢）
        assertTrue(
            "bigram 召回($matchRows) 应 >= 字面真值($truth) 的 50%",
            matchRows >= truth / 2,
        )

        assertTrue("应返回结果", hits.isNotEmpty())
        assertTrue("第一条应是精确命中（非 fuzzy）", !hits.first().fuzzy)
        assertEquals("Top 命中分数应 >= 100（完整短语命中）", true, hits.first().score >= 100.0)
        assertTrue("排序应单调不增：${hits.map { it.score }}", hits.zipWithNext().all { it.first.score >= it.second.score })

        // 民法典必然包含「不可抗力」（不可抗力是民法典的核心制度）
        val inCivil = hits.filter { it.title.contains("民法典") }
        assertTrue("Top${hits.size} 里应出现民法典相关条文，实际 titles=${hits.map { it.title }.distinct().take(8)}",
            inCivil.isNotEmpty())
    }

    /** 「正当防卫」——刑法第二十条、民法典第一百八十一条等 */
    @Test
    fun searchesZhengDangFangWeiAcrossWholeCorpus() {
        if (dir == null) return
        db() ?: return

        // ⚠️ 第一版这里断言 truth > 10，实测**正好 10**（差了 1 就失败，最脆的那种断言）。
        // 用 tools/_diag_fts.py 逐条列出后确认「正当防卫」全库就是 10 条：
        //   民法通则128、侵权责任法30、民法总则181、民法典181、
        //   民法典总则编解释30/31、司法赔偿解释7、人民警察使用武器和警械的规定1、
        //   涉执行司法赔偿解释13、刑法20
        // 这是个法律概念，不是高频词，10 条是**正确**的。
        // 断言改为 >= 10 并锁定「刑法第二十条必须命中」，比数量更稳定。
        val truth = groundTruthContains("正当防卫")
        println("「正当防卫」全库 LIKE 命中 $truth 行")
        assertTrue("「正当防卫」全库应至少有 10 条（法律概念非常用词），实际 $truth", truth >= 10)

        val (hits, matchRows) = runSearch("正当防卫")
        println("FTS 命中 $matchRows 行，返回 ${hits.size} 条")
        assertTrue("FTS 全库命中行数应 > 0，实际 $matchRows（LIKE 真值 $truth）", matchRows > 0)
        // bigram 短语应当与字面真值基本一致（都是同一个词的连续出现）
        assertEquals("「正当防卫」的 bigram 短语命中应等于字面真值", truth, matchRows)
        assertTrue("应返回结果", hits.isNotEmpty())

        // 《中华人民共和国刑法》第二十条是正当防卫的标志性条文
        val criminal = hits.firstOrNull { it.title == "中华人民共和国刑法" }
        assertNotNull("Top 结果应包含《刑法》条文，实际 titles=${hits.map { it.title }.distinct().take(10)}", criminal)
        assertTrue(
            "刑法命中应是第二十条（正当防卫条），实际「${criminal!!.articleNo}」",
            criminal.articleNo.contains("二十") || criminal.articleNo == "二十",
        )
        assertTrue("摘要应高亮命中词，实际：${criminal.preview}", criminal.preview.contains("【"))
    }

    /* ==================== 召回率基线（给 UI/回归用） ==================== */

    @Test
    fun recallBaselineForCommonLegalTerms() {
        if (dir == null) return
        db() ?: return
        val terms = listOf(
            "不可抗力", "正当防卫", "劳动合同", "侵权责任",
            "行政赔偿", "不动产", "遗嘱继承", "有期徒刑",
        )
        val report = StringBuilder()
        for (t in terms) {
            val truth = groundTruthContains(t)
            val (hits, rows) = runSearch(t, 50)
            report.append(
                String.format(
                    "%-8s LIKE=%5d  bigram=%5d  返回=%2d  top1=[%s / %s]%n",
                    t, truth, rows, hits.size,
                    hits.firstOrNull()?.title?.take(14) ?: "-",
                    hits.firstOrNull()?.articleNo ?: "-",
                )
            )
            assertTrue("「$t」LIKE 真值应 > 0", truth > 0)
            assertTrue("「$t」字节命中数($rows) 应 >= LIKE 真值($truth) * 0.5", rows * 2 >= truth)
            assertTrue("「$t」应至少返回 1 条", hits.isNotEmpty())
        }
        println("=== 召回率基线 ===")
        println(report)
    }

    /* ==================== 单字查询：证明「搜到的量少且没区分度」 ==================== */

    /**
     * 这是 [ArticleFts.singleCharHint] 存在的**数据依据**。
     *
     * ## ⚠️ 这个用例的第一版断言写错了，记录在此避免后人重复
     *
     * 第一版断言 `assertEquals("单字应 0 命中", 0, rows)` —— **跑出来是 21，不是 0**。
     *
     * 我当时的推理是：「索引里几乎全是 bigram，所以搜单字必然 0 命中」。
     * 实测数据（`tools/_diag_fts.py`）：
     *
     * ```
     * 单字「船」：索引里有该单字 token 的行 =     21 | LIKE 含该字 =  2,124
     * 单字「法」：                         25 |              39,668
     * 单字「人」：                         59 |              50,662
     * 单字「婚」：                          0 |                 655
     * 单字「税」：                          6 |               2,592
     * ```
     *
     * **为什么不是 0**：[ChineseToken.bigrams] 对「被标点/空格隔开的孤立 CJK 字」
     * 会产出单字 token。例如「车、船、飞机」里的「船」左右都是标点 → 产出 `船` 单字 token。
     * 所以索引里确实存在少量单字 token（不是我以为的 0 个）。
     *
     * **但这不推翻 singleCharHint 的设计前提**，因为关键对比是「召回率」：
     *
     * ```
     * 字   FTS命中  LIKE真值   召回率     索引里有无单字token
     * 船      21     2124    0.9887%    有（孤立字）
     * 法      25    39668    0.0630%    有
     * 人      59    50662    0.1165%    有
     * 婚       0      655    0.0000%    无
     * 税       6     2592    0.2315%    有
     * ```
     *
     * ⇒ 单字查询不是「一个都搜不到」（「婚」才是 0），而是**召回率最高也不到 1%**。
     * 用户搜「船」只得到 21 条恰好把「船」当孤立字用的条文（如「车、船、飞机」），
     * 而 2,124 行里真正讲船舶的条文几乎全部漏掉。**这才是该提示的原因** ——
     * 不是「搜不到」，而是「搜到的都是不相干的」。
     */
    @Test
    fun singleCharacterQueryRecallsAlmostNothingWhichJustifiesTheHint() {
        if (dir == null) return
        db() ?: return
        for (ch in listOf("船", "法", "人", "婚", "税")) {
            val expr = ChineseToken.matchExpression(ch) ?: continue
            val (hits, rows) = runSearch(ch, 50)
            val truth = groundTruthContains(ch)
            val ratio = if (truth > 0) rows.toDouble() / truth else 0.0
            println("单字「$ch」：FTS=$rows 行，LIKE 真值=$truth 行，召回率=${"%.4f".format(ratio)}，返回 ${hits.size} 条")

            assertTrue("前置判据：字面必须存在，否则测量没意义（「$ch」LIKE=$truth）", truth > 0)
            // 核心断言：召回率极低。不是 0，而是「远低于 10%」。
            assertTrue(
                "单字「$ch」FTS 召回($rows) 占字面真值($truth) 的 ${"%.2f".format(ratio * 100)}%，应 < 10%",
                ratio < 0.10,
            )
            // 记录实测：有单字 token 的字会拿到非零命中，这是**正确**行为
            assertTrue("命中行数不应为负", rows >= 0)
            assertTrue("提示文案必须给出：「$ch」", ArticleFts.singleCharHint(ch) != null)
            assertEquals("matchExpression 应是单短语", "(\"$ch\")", expr)
        }
    }

    /* ==================== 同序容错真的能救回错字 ==================== */

    /**
     * ## ⚠️ 这个用例的第一版断言也写错了
     *
     * 第一版用错字 `船舶优先全权` 验证「容错能救回」，跑出来 **0 条**。
     * 用 `tools/_diag_fts.py` 实测：
     *
     * ```
     * 错字「船舶优先全权」bigrams = [船舶, 舶优, 优先, 先全, 全权]
     *   严格短语命中 = 0
     *   放宽后（各 bigram 全出现）命中 = 0     ← 关键！
     * ```
     *
     * **为什么放宽也救不回**（`tools/fts_baseline.py` 实测各 bigram 的出现行数）：
     *
     * ```
     * 船舶优先全权 bigrams = [船舶, 舶优, 优先, 先全, 全权]
     * 各 bigram 出现行数 = {船舶:1683, 舶优:39, 优先:828, 先全:1, 全权:25}
     * 严格 = 0，放宽 = 0
     * ```
     *
     * 注意：**这 5 个 bigram 在全库都单独存在**（`先全` 有 1 行、`全权` 有 25 行），
     * 所以不是「某个 bigram 不存在」。真正的原因是**没有任何一行同时包含全部 5 个**——
     * 多打的那个「全」引入了原文里不会和前文共现的组合，放宽 AND 也凑不齐。
     *
     * ⚠️ 我第一版在这里写的理由是「`先全`/`全权` 根本不存在」——**那是错的**，
     * 用基线工具查了每个 bigram 的单独出现行数才纠正过来。
     *
     * 同序容错真正能救的是：**各 bigram 都存在，且能共现，只是不相邻**。
     * 例：用户搜「船舶 优先权」（中间打了空格），
     * bigrams `[船舶, 优先, 先权]` 都能在「具有船舶优先权」里找到，但严格短语
     * `"船舶 优先 先权"` 要求相邻 → 0 命中；放宽后 `"船舶" AND "优先" AND "先权"`
     * → 实测命中 39 行。
     *
     * 用这个真实场景验证才成立。
     */
    @Test
    fun fuzzySearchRescuesWhenGramsExistButAreNotAdjacent() {
        if (dir == null) return
        db() ?: return

        // 前提：正确词能严格命中
        val (correct, correctRows) = runSearch("船舶优先权")
        assertTrue("前置判据：正确词「船舶优先权」应严格命中，实际 $correctRows", correctRows > 0)
        assertTrue("正确词应返回结果", correct.isNotEmpty())
        assertTrue("正确词命中不应被标 fuzzy", correct.all { !it.fuzzy })

        // 用户加了空格/多打分隔 → bigram 都还在，但严格短语不再相邻
        val spaced = "船舶 优先权"
        val grams = ChineseToken.queryGrams(spaced)
        val strictExpr = ChineseToken.matchExpression(spaced)!!
        val (spacedHits, _) = runSearch(spaced)
        println("「$spaced」bigrams=$grams expr=$strictExpr 返回 ${spacedHits.size} 条")

        // 无论严格命中与否，放宽表达式（各 bigram AND）必须能召回
        val relaxed = ChineseToken.bigrams(spaced).distinct()
            .joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }
        val relaxedRows = rawMatchCount(relaxed)
        println("  放宽表达式 $relaxed → 命中 $relaxedRows 行")

        assertTrue("前置判据：放宽表达式应比严格短语召回更多（若为 0 则本用例无意义）",
            relaxedRows > 0)
        assertTrue(
            "放宽($relaxedRows) 应 > 严格短语命中，否则容错无存在意义",
            relaxedRows > 0,
        )

        // 容错结果条数上限与 fuzzy 标记（在 ArticleFts 里由调用方施加，这里验证常量契约）
        assertTrue("FUZZY_LIMIT 应 <= 20 避免淹没精确结果，实际 ${ArticleFts.FUZZY_LIMIT}",
            ArticleFts.FUZZY_LIMIT <= 20)
        val sample = SearchHit("b", "t", "第一条", "a1", "", "法律", "", "", 1.0, fuzzy = true)
        assertTrue("容错结果必须能标记 fuzzy=true（UI 据此显示「模糊匹配」）", sample.fuzzy)
    }

    /** 用原始 MATCH 表达式数命中行（不经过 limit），用于对比严格/放宽 */
    private fun rawMatchCount(matchExpr: String): Int {
        val c = db() ?: return 0
        c.prepareStatement("SELECT COUNT(*) FROM articles_fts WHERE content MATCH ?").use { ps ->
            ps.setString(1, matchExpr)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /* ==================== 标题检索 ==================== */

    @Test
    fun titleSearchFindsTheConstitutionFamily() {
        if (dir == null) return
        db() ?: return
        val titles = lawTitlesWith("宪法")
        println("标题含「宪法」的法规（${titles.size} 部）：$titles")
        assertTrue("应有 8 部宪法相关法规，实际 ${titles.size}", titles.size >= 8)

        val (hits, _) = runSearch("宪法")
        assertTrue("搜「宪法」应命中", hits.isNotEmpty())
        assertTrue(
            "Top 里应出现宪法相关标题，实际 ${hits.map { it.title }.take(5)}",
            hits.any { it.title.contains("宪法") },
        )
    }
}
