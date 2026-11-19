package com.lawsearch.app.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 时效性计算：规格书第二节与第九节的验收用例。
 *
 * 期望结果（规格书原文）：
 *  - 《宪法（2018年修正文本）》→ 有效
 *  - 《宪法（1982年）》→ 已被修订
 *  - 《宪法修正案（2004年）》→ 已被修订
 *  - 《海商法》→ 有效
 */
class ValidityTest {

    private val constitution2018 = Validity.LawMeta(
        bbbs = "c2018",
        title = "中华人民共和国宪法（2018年修正文本）",
        gbrq = "2018-03-11",
        sxrq = "1982-12-04",
        sxx = 3,
    )
    private val constitution1982 = Validity.LawMeta(
        bbbs = "c1982",
        title = "中华人民共和国宪法（1982年）",
        gbrq = "1982-12-04",
        sxrq = "1982-12-04",
        sxx = 3,
    )
    private val amendment2004 = Validity.LawMeta(
        bbbs = "a2004",
        title = "中华人民共和国宪法修正案（2004年）",
        gbrq = "2004-03-14",
        sxrq = "2004-03-14",
        sxx = 3,
    )
    private val amendment2018 = Validity.LawMeta(
        bbbs = "a2018",
        title = "中华人民共和国宪法修正案（2018年）",
        gbrq = "2018-03-11",
        sxrq = "2018-03-11",
        sxx = 3,
    )
    private val maritime = Validity.LawMeta(
        bbbs = "maritime",
        title = "中华人民共和国海商法",
        gbrq = "2025-10-28",
        sxrq = "2026-05-01",
        sxx = 3,
    )

    private val all = listOf(
        constitution2018, constitution1982, amendment2004, amendment2018, maritime,
    )

    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun constitution2018IsValid() {
        assertEquals(Validity.VALID, Validity.resolve(constitution2018, all, today))
    }

    @Test
    fun constitution1982IsRevised() {
        assertEquals(Validity.REVISED, Validity.resolve(constitution1982, all, today))
    }

    @Test
    fun amendment2004IsRevised() {
        assertEquals(Validity.REVISED, Validity.resolve(amendment2004, all, today))
    }

    @Test
    fun amendment2018IsValid() {
        assertEquals(Validity.VALID, Validity.resolve(amendment2018, all, today))
    }

    @Test
    fun maritimeIsValid() {
        assertEquals(Validity.VALID, Validity.resolve(maritime, all, today))
    }

    @Test
    fun futureEffectiveDateIsPending() {
        val pending = Validity.LawMeta("p", "某新法", gbrq = "2026-10-01", sxrq = "2027-01-01", sxx = 3)
        assertEquals(Validity.PENDING, Validity.resolve(pending, emptyList(), today))
    }

    @Test
    fun sxxTwoIsAmended() {
        val amended = Validity.LawMeta("m", "某法", gbrq = "2001-01-01", sxrq = "2001-03-01", sxx = 2)
        assertEquals(Validity.AMENDED, Validity.resolve(amended, emptyList(), today))
    }

    @Test
    fun familyTitleGroupsAmendmentsWithParentLaw() {
        assertEquals(
            "中华人民共和国宪法",
            Validity.familyTitle("中华人民共和国宪法（1982年）"),
        )
        assertEquals(
            "中华人民共和国宪法",
            Validity.familyTitle("中华人民共和国宪法修正案（2004年）"),
        )
        assertEquals(
            "中华人民共和国宪法",
            Validity.familyTitle("中华人民共和国宪法（2018年修正文本）"),
        )
    }

    @Test
    fun baseTitleStripsBrackets() {
        assertEquals("中华人民共和国宪法", Validity.baseTitle("中华人民共和国宪法（2018年修正文本）"))
        assertEquals("中华人民共和国宪法", Validity.baseTitle("中华人民共和国宪法(1982)"))
        assertEquals("海商法", Validity.baseTitle("海商法"))
    }

    @Test
    fun differentLawsDoNotAffectEachOther() {
        // 同名比较只看基础名，不同法规之间不会互相「修订」
        assertEquals(Validity.VALID, Validity.resolve(maritime, listOf(amendment2018), today))
    }
}
