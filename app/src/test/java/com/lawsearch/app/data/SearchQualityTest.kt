package com.lawsearch.app.data

import com.lawsearch.app.util.ChineseToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索质量改进的单元测试（P1 单字提示 / P2 同序容错 / P3 打分一致性）。
 *
 * ---
 *
 * ## 方法论：先把判据本身验一遍，再信结论
 *
 * 这个项目的测试历史上吃过两次「判据错了」的亏，**都不是代码 bug**：
 *
 * 1. **「APK 里壁纸 = 0」** —— 脚本用 `'wp_' in filename` 判断，
 *    但 R8 会重命名资源路径（`drawable-nodpi/wp_01.jpg` → `res/dD.jpg`），
 *    判据本身永远命中 0，于是得出了「壁纸被删光了」的错误结论。
 *    正确判据是 `aapt2 dump resources`，查 `resources.arsc` 而不是文件名。
 *
 * 2. **「修复前正文丢失 = 0 条」** —— 脚本判断 `body == no`，
 *    但数据里 `no` 是汉字序数 `"一"`，而 `text` 是 `"第一条"`，**两者永远不等**，
 *    于是 0 条丢失。换成「正文长度 < 11 汉字」才测出真实的 29,244 条（42.0%）。
 *
 * **教训：当某个统计结果异常漂亮（0 失败 / 0 受影响 / 100% 通过）时，
 * 第一反应应该是怀疑判据写错了，而不是庆祝。**
 *
 * 写新测试时请照做：断言之前，先用一个小样例手工核对判据本身是否成立。
 * ---
 */
class SearchQualityTest {

    /* ==================== P1：单字查询提示 ==================== */

    @Test
    fun singleCjkCharProducesHint() {
        val hint = ArticleFts.singleCharHint("船")
        assertNotNull("单字「船」应给出提示", hint)
        assertTrue("提示应举例二字词，实际：$hint", hint!!.contains("船舶"))
        assertTrue("提示不应是命令式说教", !hint.contains("请输入"))
    }

    @Test
    fun singleCharHintAvoidsEchoingTheSameCharAsExample() {
        // 「法」的推荐例子必须是二字词，不能又给回一个单字
        val hint = ArticleFts.singleCharHint("法")!!
        assertTrue("例子应是二字词，实际：$hint", hint.contains("法人"))
    }

    @Test
    fun singleCharHintForUnknownCharIsStillGentle() {
        // 没有预置例子的生僻字：给通用提示，且不能是空
        val hint = ArticleFts.singleCharHint("龘")
        assertNotNull(hint)
        assertTrue("应给出通用提示", hint!!.contains("两个字"))
    }

    @Test
    fun multiCharQueryHasNoSingleCharHint() {
        assertNull("二字词不该提示", ArticleFts.singleCharHint("船舶"))
        assertNull("长句不该提示", ArticleFts.singleCharHint("不可抗力"))
    }

    @Test
    fun nonCjkSingleCharHasNoHint() {
        // 单个字母/数字不是「中文字太宽泛」的场景
        assertNull(ArticleFts.singleCharHint("a"))
        assertNull(ArticleFts.singleCharHint("5"))
        assertNull(ArticleFts.singleCharHint(" "))
    }

    /* ==================== P2：同序容错 ==================== */

    @Test
    fun relaxedExpressionIsBuiltFromAllDistinctGrams() {
        // 用户在「船舶优先权」里多打/错打一个字 → 严格短语 0 命中
        val typo = "船舶优先全权"
        val strict = ChineseToken.matchExpression(typo)!!
        val relaxed = ChineseToken.bigrams(typo).distinct()
            .joinToString(" AND ") { "\"$it\"" }

        // 严格写法是一个短语（内部空格，要求相邻有序）
        assertEquals("(\"船舶 舶优 优先 先全 全权\")", strict)
        // 放宽写法是多个独立短语 AND（不要求相邻）
        assertTrue("放宽后应是 AND 连接，实际：$relaxed", relaxed.contains(" AND "))
        assertTrue(relaxed.startsWith("\"船舶\" AND "))
    }

    @Test
    fun fuzzyLimitIsSmallEnoughToNotDrownPreciseResults() {
        // 容错结果必须远少于正常结果上限（默认 50），
        // 否则「其实没精确搜到」会被误导成「搜到很多相关条文」
        assertTrue(
            "FUZZY_LIMIT=${ArticleFts.FUZZY_LIMIT} 应远小于 50",
            ArticleFts.FUZZY_LIMIT <= 20,
        )
    }

    @Test
    fun searchHitCarriesFuzzyFlag() {
        val precise = SearchHit("b", "t", "第一条", "a1", "", "法律", "", "", 10.0)
        val fuzzy = precise.copy(fuzzy = true)
        assertTrue("默认应是精确命中", !precise.fuzzy)
        assertTrue("降级结果要能被识别", fuzzy.fuzzy)
    }

    /* ==================== P3：打分 token 一致性 ==================== */

    @Test
    fun queryGramsKeepsSingleCharToMatchExpression() {
        // 这个用例钉住的正是 P3 修复点：
        // 旧实现 filter { it.length >= 2 } 会丢掉单字，
        // 而 matchExpression 不会 → 两者 token 集合漂移 → 打分恒 0
        assertEquals("单字查询保留单字 gram", listOf("船"), ChineseToken.queryGrams("船"))
        // ⚠️ 注意：连续的两个 CJK 字（「船舶」）产出的是**一个 bigram**，
        // 不是两个单字。只有被空格/标点隔开的孤立字才产生单字 gram。
        // 我第一版这里写成 listOf("船","舶") 是错的 —— 代码本来是对的。
        assertEquals("连续两字是一个 bigram", listOf("船舶"), ChineseToken.queryGrams("船舶"))
        // 被空格隔开时才会拆成两个单字
        assertEquals("空格隔开 → 两个单字", listOf("船", "舶"), ChineseToken.queryGrams("船 舶"))
    }

    @Test
    fun queryGramsAndMatchExpressionAgreeOnTokens() {
        // 对任意查询，queryGrams 的去重集合应等于 matchExpression 短语里的 token 集合
        for (q in listOf("船", "船舶", "不可抗力", "正当防卫", "船 舶")) {
            val grams = ChineseToken.queryGrams(q)
            val expr = ChineseToken.matchExpression(q) ?: continue
            val tokensInExpr = Regex("\"([^\"]+)\"").findAll(expr)
                .flatMap { it.groupValues[1].split(' ') }
                .filter { it.isNotBlank() }
                .toSet()
            assertEquals(
                "查询「$q」的 queryGrams 与 matchExpression 不一致",
                tokensInExpr, grams.toSet(),
            )
        }
    }

    @Test
    fun singleCharGramsAreDownWeightedInScoring() {
        // ⚠️ 这个用例第一版写错了，记录下来避免后人重复：
        //
        // 我原本断言「单字命中分值 > 0（不该归零）」。但实测：
        //   tokens("船舶优先权是指海事请求人") = 船舶 舶优 优先 先权 权是 是指 指海 海事 …
        //   其中**不存在单字 "船"** —— 索引里只有 bigram。
        // 所以 grams=["船"] 时 coverage=0、best=0 → scoreTokens 直接返回 0.0。
        // 这是**正确行为**（确实没匹配上），不是「被单字权重折减成了 0」。
        //
        // 那单字权重折减到底在什么情况下生效？
        // 只有查询串里出现**被空格/标点隔开的孤立单字**时才产生单字 gram，
        // 例如 "船舶 的" → tokens 包含 "的"（孤立字）。
        // 这里构造这种场景来验证折减确实发生。
        val text = "船舶 的 定义"           // 孤立单字「的」会被切成单字 token
        val tokens = ChineseToken.tokenize(text)
        assertTrue("前提：tokens 里应有单字「的」，实际：$tokens", tokens.split(' ').contains("的"))

        // 只查单字「的」：命中，但分值应被压到很低
        val singleOnly = ArticleFts.scoreTokens(tokens, listOf("的"), "的")
        assertTrue("单字命中应能拿到分（否则无法排序）", singleOnly > 0.0)

        // 对照：查 bigram「船舶」，命中的分值应明显更高
        val bigram = ArticleFts.scoreTokens(tokens, listOf("船舶"), "船舶")
        assertTrue(
            "单字($singleOnly) 应显著低于 bigram($bigram)",
            singleOnly * 3.0 < bigram,
        )
    }

    @Test
    fun bigramScoringIsUnchangedByTheSingleCharWeighting() {
        // 回归保护：P3 只影响「只有单字」的场景，纯 bigram 查询的打分必须与修复前一致。
        // 船舶优先权 → [船舶, 舶优, 优先, 先权]
        val text = "下列各项海事请求具有船舶优先权：（一）船长、船员"
        val tokens = ChineseToken.tokenize(text)
        val grams = ChineseToken.queryGrams("船舶优先权")
        val score = ArticleFts.scoreTokens(tokens, grams, "船舶优先权")
        // 完整短语命中 → 至少拿到 100 的完整命中分
        assertTrue("完整短语命中应 >= 100，实际 $score", score >= 100.0)
    }
}
