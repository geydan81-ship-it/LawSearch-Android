package com.lawsearch.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 中文数字转换：规格书里的条文编号（「第一千二百六十条」）都要能正确换算 */
class ChineseNumberTest {

    @Test
    fun convertsArticleNumbers() {
        assertEquals(1, ChineseNumber.cnToInt("一"))
        assertEquals(10, ChineseNumber.cnToInt("十"))
        assertEquals(22, ChineseNumber.cnToInt("二十二"))
        assertEquals(138, ChineseNumber.cnToInt("一百三十八"))
        assertEquals(310, ChineseNumber.cnToInt("三百一十"))
        assertEquals(1260, ChineseNumber.cnToInt("一千二百六十"))
        assertEquals(105, ChineseNumber.cnToInt("一百五"))
    }

    @Test
    fun handlesZeroAndArabic() {
        assertEquals(0, ChineseNumber.cnToInt("零"))
        assertEquals(0, ChineseNumber.cnToInt("〇"))
        assertEquals(2021, ChineseNumber.cnToInt("2021"))
        assertEquals(2, ChineseNumber.cnToInt("两"))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(ChineseNumber.cnToInt(""))
        assertNull(ChineseNumber.cnToInt("abc"))
        assertNull(ChineseNumber.cnToInt("一二x"))
    }

    @Test
    fun roundTripsThroughIntToCn() {
        listOf(1, 9, 10, 11, 22, 99, 100, 105, 138, 310, 999, 1000, 1260).forEach { n ->
            assertEquals("intToCn($n)", n, ChineseNumber.cnToInt(ChineseNumber.intToCn(n)))
        }
    }

    @Test
    fun parsesArticleNumberFromLabel() {
        assertEquals(22, ChineseNumber.articleNumberOf("第二十二条"))
        assertEquals(1260, ChineseNumber.articleNumberOf("第一千二百六十条"))
        assertNull(ChineseNumber.articleNumberOf("第一章 总则"))
    }
}
