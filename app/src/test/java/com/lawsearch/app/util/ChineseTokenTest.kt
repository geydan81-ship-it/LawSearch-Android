package com.lawsearch.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 中文二元组分词与 FTS 查询表达式 */
class ChineseTokenTest {

    @Test
    fun bigramsOfFourCharQuery() {
        assertEquals(listOf("船舶", "舶优", "优先", "先权"), ChineseToken.bigrams("船舶优先权"))
    }

    @Test
    fun splitsOnPunctuation() {
        assertEquals(listOf("船舶", "优先"), ChineseToken.bigrams("船舶，优先"))
        // 换行会切断二元组：第二行只有一个字
        assertEquals(listOf("船舶", "优"), ChineseToken.bigrams("船舶\n优"))
    }

    @Test
    fun singleCharStaysSingle() {
        assertEquals(listOf("法"), ChineseToken.bigrams("法"))
    }

    @Test
    fun latinWordsStayWhole() {
        assertEquals(listOf("hello", "world"), ChineseToken.bigrams("Hello World"))
    }

    @Test
    fun mixedTextInterleaves() {
        // 「第22条」→ 22 是字母数字串，前后是两个单字
        assertEquals(listOf("第", "22", "条"), ChineseToken.bigrams("第22条"))
    }

    @Test
    fun matchExpressionQuotesGrams() {
        val expr = ChineseToken.matchExpression("船舶优先权")
        assertNotNull(expr)
        // 整个词放进同一个短语：短语内空格表示「相邻有序」。
        // 旧格式是每个 bigram 各包一层引号再用 AND 连接，实测无法命中中文短语。
        assertEquals("(\"船舶 舶优 优先 先权\")", expr)
    }

    @Test
    fun matchExpressionAndsMultipleWords() {
        val expr = ChineseToken.matchExpression("船舶 优先权")!!
        assertEquals("(\"船舶\") AND (\"优先 先权\")", expr)
    }

    @Test
    fun matchExpressionNullWhenNothingTokenizable() {
        assertNull(ChineseToken.matchExpression("   ，。 "))
    }

    @Test
    fun tokenizeProducesSpaceSeparatedGrams() {
        assertEquals("船舶 舶优 优先 先权", ChineseToken.tokenize("船舶优先权"))
    }

    @Test
    fun cjkDetection() {
        assertTrue(ChineseToken.isCjk('船'))
        assertTrue(!ChineseToken.isCjk('a'))
    }
}
