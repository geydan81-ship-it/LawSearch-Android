package com.lawsearch.app.util

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 时效性计算。
 *
 * 规格书明确要求：**不要直接信任官方接口的 `sxx` 字段**。
 * 宪法 1982 年原版和 5 个修正案全被标成 `sxx=3`，必须按日期和同名版本重新计算。
 */
object Validity {

    const val VALID = "有效"
    const val PENDING = "尚未生效"
    const val REVISED = "已被修订"
    const val AMENDED = "已修改"
    const val ABOLISHED = "已废止"

    /** 用于比较的最简元信息 */
    data class LawMeta(
        val bbbs: String,
        val title: String,
        val gbrq: String = "",
        val sxrq: String = "",
        val sxx: Int = 0,
    )

    private val BRACKET_SUFFIX = Regex("[（(][^）)]*[）)]")
    private val AMENDMENT_SUFFIX = Regex("修正案$")

    /** 去掉「（2018年修正文本）」这类括号后缀，得到比较用的基础名 */
    fun baseTitle(title: String): String =
        title.replace(BRACKET_SUFFIX, "").trim()

    /**
     * 用于「同名是否被更新版本取代」比较的族名。
     *
     * 规格书要求验证：《宪法修正案（2004年）》→ 已被修订（因为存在 2018 年修正案）。
     * 修正案的标题去括号后是「中华人民共和国宪法修正案」，与宪法的「中华人民共和国宪法」
     * 不同名，所以这里再把「修正案」后缀去掉，让它们归入同一族：
     *   中华人民共和国宪法（1982年）      → 中华人民共和国宪法
     *   中华人民共和国宪法修正案（2004年） → 中华人民共和国宪法
     *   中华人民共和国宪法修正案（2018年） → 中华人民共和国宪法
     *
     * 注意：这样会让宪法的各版本与各修正案互相判定「已被修订」，符合「旧版本已被取代」的语义。
     */
    fun familyTitle(title: String): String =
        AMENDMENT_SUFFIX.replace(baseTitle(title), "").trim()

    fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.length < 10) return null
        return try {
            LocalDate.parse(s.substring(0, 10))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * 规格书给出的算法（strict 顺序）：
     * 1. 施行日期在今天之后 → 尚未生效
     * 2. 存在同名（去括号后缀）且公布日期更晚的版本 → 已被修订
     * 3. 官方标记 sxx == 2 → 已修改
     * 4. 其余 → 有效
     *
     * 另外补充两条线上数据里真实存在的规则（不影响规格书给出的 4 个用例）：
     * - 标题带「（已废止）」或官方 sxx == 5 → 已废止
     * - 施行日期早于「今天」但 sxx == 4（官方标未生效）→ 视为数据滞后，按有效处理
     */
    fun resolve(
        law: LawMeta,
        allLaws: List<LawMeta>,
        today: LocalDate = LocalDate.now(),
    ): String {
        if (law.title.contains("已废止")) return ABOLISHED

        val effective = parseDate(law.sxrq)
        if (effective != null && effective.isAfter(today)) return PENDING

        val base = familyTitle(law.title)
        if (base.isNotEmpty()) {
            val myDate = law.gbrq
            val hasNewer = allLaws.any { other ->
                other.bbbs != law.bbbs &&
                    !other.title.contains("已废止") &&
                    familyTitle(other.title) == base &&
                    other.gbrq > myDate
            }
            if (hasNewer) return REVISED
        }

        return when (law.sxx) {
            2 -> AMENDED
            5 -> ABOLISHED
            else -> VALID
        }
    }

    /** 徽章配色语义：有效 / 尚未生效 / 已被修订 / 已废止 */
    fun tone(validity: String): Tone = when (validity) {
        VALID -> Tone.Valid
        PENDING -> Tone.Pending
        REVISED, AMENDED -> Tone.Revised
        ABOLISHED -> Tone.Revoked
        else -> Tone.Valid
    }

    enum class Tone { Valid, Pending, Revised, Revoked }
}
