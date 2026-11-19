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
 * 「content 修复后，全文索引是不是真的索引到了正文」的端到端验证（纯 JVM）。
 *
 * ## 为什么要这个测试
 *
 * `ArticleFts.index()` 里取正文用的是 `Block.bodyParagraphs()`。
 * 修复前 `Block` 没有 `content` 字段，内置数据里 956 部法规的正文
 * （只存在 `content`、`paragraphs` 为空、`text` 只有「第一条」）被
 * `ignoreUnknownKeys` 静默丢弃 —— 于是**索引里存的是「第一条」而不是正文**，
 * 表现为「不可抗力」「正当防卫」这类词搜不到。
 *
 * 本测试直接读 APK 的 assets 数据，走与生产完全相同的
 * `LawJsonCodec.decode → bodyParagraphs() → ChineseToken.tokenize → FTS4` 路径，
 * 断言正文关键词能命中。
 *
 * 与 [Fts4SearchTest] 的区别：那个用的是 samples（完整三字段数据，天然能过），
 * 这个用的是 **assets 里真实打包的数据**，才能暴露 content 丢失类问题。
 */
class ArticleFtsContentTest {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun assetsDir(): File? {
        val candidates = listOf(
            File("src/main/assets/laws"),
            File("app/src/main/assets/laws"),
            File(System.getProperty("user.dir"), "src/main/assets/laws"),
            File(System.getProperty("user.dir"), "app/src/main/assets/laws"),
        )
        return candidates.firstOrNull { it.isDirectory && it.listFiles()?.any { f -> f.name.endsWith(".json.gz") } == true }
    }

    /** 读一个 bbbs 的正文 gz；返回 null 表示不存在 */
    private fun readLaw(name: String): LawJson? {
        val dir = assetsDir() ?: return null
        val f = File(dir, name)
        if (!f.exists()) return null
        val text = GZIPInputStream(f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        return LawJsonCodec.decode(text)
    }

    /** 与 ArticleFts.ensureSchema 完全一致的建表 */
    private fun createSchema() {
        conn.createStatement().use {
            it.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts4(" +
                    "bbbs, article_no, anchor, raw, content, tokenize=simple, " +
                    "notindexed=bbbs, notindexed=article_no, notindexed=anchor, notindexed=raw)"
            )
        }
    }

    /**
     * 走与 [ArticleFts.index] 完全相同的正文提取逻辑。
     *
     * ⚠️ 这里必须和 ArticleFts.kt 的 `index()` 保持同一份规则：
     * article 用 bodyParagraphs()，type==text 用 text，其它跳过。
     */
    private fun bodyOf(b: Block): String = when {
        b.isArticle -> b.bodyParagraphs().joinToString("\n")
        b.type == Block.TYPE_TEXT -> b.text
        else -> ""
    }

    private fun indexLaw(law: LawJson): Int {
        createSchema()
        var n = 0
        conn.prepareStatement(
            "INSERT INTO articles_fts(bbbs, article_no, anchor, raw, content) VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            for (b in law.blocks) {
                val body = bodyOf(b)
                if (body.isBlank()) continue
                ps.setString(1, law.bbbs)
                ps.setString(2, if (b.isArticle) b.no else "")
                ps.setString(3, b.id)
                ps.setString(4, body)
                ps.setString(5, ChineseToken.tokenize(body))
                ps.addBatch()
                n++
            }
            ps.executeBatch()
        }
        return n
    }

    private fun search(query: String): List<String> {
        val expr = ChineseToken.matchExpression(query) ?: return emptyList()
        return conn.prepareStatement(
            "SELECT article_no FROM articles_fts WHERE content MATCH ?"
        ).use { ps ->
            ps.setString(1, expr)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) ?: "") }
            }
        }
    }

    /* ---------------- 核心回归：content-only 数据必须能搜到 ---------------- */

    @Test
    fun contentOnlyLawIsSearchableByBodyKeyword() {
        // 《人民法院法庭规则》：27 条，全部只有 content，paragraphs 为空，text 只有条号
        val law = readLaw("55a521519d6bb1acf91bcd037e4488e0.json.gz")
        if (law == null) return  // 数据目录不可用时跳过（CI 差异）

        assertEquals("人民法院法庭规则应有 27 条", 27, law.blocks.count { it.isArticle })

        // 修复前：所有 article 的 bodyParagraphs() 都退回 text（=「第一条」），
        // 于是下面每一句都会返回「第一条」而不是正文 —— 这条断言就会失败。
        val a1 = law.blocks.first { it.isArticle && it.noNum == 1 }
        val body = a1.bodyParagraphs().joinToString("")
        assertTrue(
            "第一条正文应含「法庭」而不是只有条号，实际：$body",
            body.length > 10 && body != a1.text,
        )

        val indexed = indexLaw(law)
        assertEquals("应索引 27 条", 27, indexed)

        // 正文关键词必须能命中
        val hits = search("法庭")
        assertTrue("搜「法庭」应命中，实际 $hits", hits.isNotEmpty())
    }

    @Test
    fun indexedBodyIsNotJustTheArticleHeading() {
        val law = readLaw("55a521519d6bb1acf91bcd037e4488e0.json.gz") ?: return
        indexLaw(law)
        // raw 列（不索引，用于摘要）里存的应是正文
        val bad = conn.prepareStatement(
            "SELECT article_no, raw FROM articles_fts"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) {
                    val no = rs.getString(1) ?: ""
                    val raw = rs.getString(2) ?: ""
                    // 正文 == 条号 就是丢了
                    if (raw.trim() == no.trim()) out.add(no)
                }
                out
            }
        }
        assertTrue("有 ${bad.size} 条索引里的正文只剩条号：$bad", bad.isEmpty())
    }

    /* ---------------- 用户报告搜不到的两个词 ---------------- */

    /**
     * 「不可抗力」——用户明确反馈搜不到。
     * 全量数据里该词出现在 192 条条文中。
     */
    @Test
    fun searchableByBuKeKangLi() {
        val law = readLaw("55a521519d6bb1acf91bcd037e4488e0.json.gz") ?: return
        // 用一部确定含「不可抗力」的法规：民事/行政司法赔偿解释
        val dsf = readLaw("$DSF_JUDICIAL_COMPENSATION.json.gz") ?: readLaw("$DSF_JUDICIAL_COMPENSATION.json")
        if (dsf == null) {
            // 数据不同版本时退化为只验证分词表达式本身
            assertNotNull(ChineseToken.matchExpression("不可抗力"))
            return
        }
        createSchema()
        indexLaw(dsf)
        val hits = search("不可抗力")
        assertTrue("搜「不可抗力」应命中，实际 $hits", hits.isNotEmpty())
    }

    @Test
    fun searchableByZhengDangFangWei() {
        val law = readLaw("$TORT_LIABILITY.json.gz") ?: return
        createSchema()
        indexLaw(law)
        val hits = search("正当防卫")
        assertTrue("搜「正当防卫」应命中，实际 $hits", hits.isNotEmpty())
    }

    /**
     * 分词层的直接验证：短语必须**连续有序**，不能退化成散碎的 AND。
     *
     * 这是曾经的真实 bug：「("不可" AND "可抗" AND "抗力")」0 命中，
     * 因为 FTS4 的 AND 只要求同一行包含，不要求相邻。
     */
    @Test
    fun matchExpressionUsesSingleContiguousPhrase() {
        val expr = ChineseToken.matchExpression("不可抗力")
        assertEquals("(\"不可 可抗 抗力\")", expr)
        // 不能出现独立短语 AND 的旧写法
        assertTrue("不应是散碎 AND 短语：$expr", !expr!!.contains("\" AND \""))
    }

    private companion object {
        /** 最高人民法院关于民事、行政诉讼中司法赔偿若干问题的解释（含「不可抗力」） */
        const val DSF_JUDICIAL_COMPENSATION = "014ce9a25c455a99f3f021fb992241cd"

        /** 中华人民共和国侵权责任法（含「正当防卫」） */
        const val TORT_LIABILITY = "2c909fdd678bf17901678bf706b1055f"
    }
}
