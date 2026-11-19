package com.lawsearch.app.data

import com.lawsearch.app.testutil.Samples
import com.lawsearch.app.util.ChineseToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 全文检索的 SQL 语义验证（纯 JVM）。
 *
 * 说明：Android 系统 SQLite **没有 FTS5**（真机报 `no such module: fts5`），
 * 所以生产代码用的是 **FTS4**。这里用 JVM 的 sqlite-jdbc（自带 FTS4/FTS5）
 * 验证与 ArticleFts 完全一致的建表语句、二元组索引、MATCH 表达式与打分逻辑。
 *
 * 打分/摘要函数直接复用 [ArticleFts] 的实现（不复制代码），
 * 这样只要这里通过，真机上的行为就有保障。
 */
class Fts4SearchTest {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private val maritime by lazy {
        Samples.law(Samples.MARITIME) ?: error("样本数据缺失：${Samples.path}")
    }

    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    private fun query(sql: String, params: List<String> = emptyList()) =
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, v -> ps.setString(i + 1, v) }
            ps.executeQuery().use { rs ->
                val rows = ArrayList<List<String>>()
                while (rs.next()) {
                    rows.add((1..rs.metaData.columnCount).map { rs.getString(it) ?: "" })
                }
                rows
            }
        }

    /** 与 ArticleFts.ensureSchema 完全相同的建表语句 */
    private fun createSchema() {
        // raw 列存原文（不索引），摘要从原文截取
        exec(
            "CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts4(" +
                "bbbs, article_no, anchor, raw, content, tokenize=simple, " +
                "notindexed=bbbs, notindexed=article_no, notindexed=anchor, notindexed=raw)"
        )
        exec(
            "CREATE VIRTUAL TABLE IF NOT EXISTS titles_fts USING fts4(" +
                "bbbs, content, tokenize=simple, notindexed=bbbs)"
        )
    }

    private fun insertArticle(bbbs: String, no: String, anchor: String, raw: String) {
        conn.prepareStatement(
            "INSERT INTO articles_fts(bbbs, article_no, anchor, raw, content) VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, bbbs)
            ps.setString(2, no)
            ps.setString(3, anchor)
            ps.setString(4, raw)
            ps.setString(5, ChineseToken.tokenize(raw))
            ps.executeUpdate()
        }
    }

    @Test
    fun fts4IsAvailableWithOurSchema() {
        createSchema()
        insertArticle("x", "第一条", "a1", "船舶优先权是指海事请求人")
        val expr = ChineseToken.matchExpression("船舶优先权")!!
        val rows = query("SELECT bbbs FROM articles_fts WHERE content MATCH ?", listOf(expr))
        assertEquals("二元组短语应命中", 1, rows.size)
    }

    /** 海商法真实数据：搜「船舶优先权」必须命中第二十一、二十二、二十四条 */
    @Test
    fun searchesMaritimeLawArticles() {
        createSchema()
        conn.prepareStatement(
            "INSERT INTO articles_fts(bbbs, article_no, anchor, raw, content) VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            var n = 0
            for (b in maritime.blocks) {
                if (!b.isArticle) continue
                val body = b.bodyParagraphs().joinToString("\n")
                if (body.isBlank()) continue
                ps.setString(1, maritime.bbbs)
                ps.setString(2, b.no)
                ps.setString(3, b.id)
                ps.setString(4, body)
                ps.setString(5, ChineseToken.tokenize(body))
                ps.addBatch()
                n++
            }
            ps.executeBatch()
            assertEquals("应索引 310 条", 310, n)
        }

        val expr = ChineseToken.matchExpression("船舶优先权")!!
        val rows = query("SELECT article_no, anchor, raw, content FROM articles_fts WHERE content MATCH ?", listOf(expr))
        val nums = rows.map { it[0] }
        assertTrue("应命中第二十一条：$nums", nums.contains("第二十一条"))
        assertTrue("应命中第二十二条：$nums", nums.contains("第二十二条"))
        assertTrue("应命中第二十四条：$nums", nums.contains("第二十四条"))

        // 用生产代码的打分函数排序：前三条应包含「定义/列举」类核心条文
        // （第二十一条是定义，第二十二条是列举；第二十五条等也是主题条文，允许排在前面）
        val grams = ChineseToken.queryGrams("船舶优先权")
        val ranked = rows.sortedByDescending { ArticleFts.scoreTokens(it[3], grams, "船舶优先权") }
        val top3 = ranked.take(3).map { it[0] }
        assertTrue(
            "前三条应包含第二十一条或第二十二条，实际 $top3（全部顺序 ${ranked.map { it[0] }}）",
            top3.contains("第二十一条") || top3.contains("第二十二条"),
        )
    }

    @Test
    fun unknownTermReturnsNothing() {
        createSchema()
        insertArticle("x", "第一条", "a1", "船舶优先权")
        val expr = ChineseToken.matchExpression("量子纠缠火箭")!!
        assertEquals(0, query("SELECT bbbs FROM articles_fts WHERE content MATCH ?", listOf(expr)).size)
    }

    @Test
    fun titleIndexWorksAndIsUsedForNameSearch() {
        createSchema()
        conn.prepareStatement("INSERT INTO titles_fts(bbbs, content) VALUES (?, ?)").use { ps ->
            ps.setString(1, maritime.bbbs)
            ps.setString(2, ChineseToken.tokenize(maritime.title))
            ps.executeUpdate()
        }
        val expr = ChineseToken.matchExpression("海商法")!!
        val rows = query("SELECT bbbs FROM titles_fts WHERE content MATCH ?", listOf(expr))
        assertEquals(listOf(maritime.bbbs), rows.map { it[0] })
    }

    @Test
    fun deleteByBbbsRemovesOnlyThatLaw() {
        createSchema()
        insertArticle("a", "第一条", "a1", "船舶")
        insertArticle("b", "第一条", "a1", "船舶")
        conn.prepareStatement("DELETE FROM articles_fts WHERE bbbs = ?").use {
            it.setString(1, "a")
            it.executeUpdate()
        }
        val rows = query("SELECT bbbs FROM articles_fts")
        assertEquals(listOf("b"), rows.map { it[0] })
    }

    // ---------------- 摘要（从原文截取，不能出现二元组重影） ----------------

    @Test
    fun previewComesFromRawTextNotBigrams() {
        val raw = "下列各项海事请求具有船舶优先权：（一）船长、船员和其他船上在岗人员因在船上工作产生的工资。"
        val preview = ArticleFts.previewFromRaw(raw, "船舶优先权")
        // 必须包含完整原词，且命中片段被【】包住
        assertTrue("摘要应包含【船舶优先权】，实际：$preview", preview.contains("【船舶优先权】"))
        // 不能出现二元组逐字拼接导致的重复字
        assertTrue("摘要不应出现「船舶舶」这类重影，实际：$preview", !preview.contains("船舶舶"))
        assertTrue("摘要不应出现「优先先」，实际：$preview", !preview.contains("优先先"))
    }

    @Test
    fun previewOfMaritimeArticle22ContainsOriginalWording() {
        val a22 = maritime.blocks.first { it.isArticle && it.noNum == 22 }
        val raw = a22.bodyParagraphs().joinToString(" ")
        val preview = ArticleFts.previewFromRaw(raw, "船舶优先权")
        assertTrue("应包含「下列各项海事请求具有船舶优先权」，实际：$preview",
            preview.replace("【", "").replace("】", "").contains("具有船舶优先权"))
    }

    @Test
    fun previewFallsBackWhenPhraseAbsent() {
        val raw = "船舶所有权、船舶抵押权、船舶优先权的说明"
        val preview = ArticleFts.previewFromRaw(raw, "船舶")
        assertTrue("部分命中时也应给出原文片段，实际：$preview", preview.contains("船舶"))
        assertTrue("不应为空白", preview.isNotBlank())
    }

    @Test
    fun previewTruncatesLongText() {
        val raw = "甲".repeat(300) + "船舶优先权" + "乙".repeat(300)
        val preview = ArticleFts.previewFromRaw(raw, "船舶优先权")
        assertTrue("长文本应被截断并加省略号，实际长度 ${preview.length}", preview.length < 140)
        assertTrue("应以省略号结尾", preview.endsWith("…"))
        assertTrue("应包含命中词", preview.contains("船舶优先权"))
    }
}
