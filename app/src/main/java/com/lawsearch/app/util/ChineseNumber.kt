package com.lawsearch.app.util

/**
 * 中文数字 ↔ 阿拉伯数字。
 *
 * 法条编号是中文数字（「第一千二百六十条」），需要转成阿拉伯数字用于排序、
 * 锚点跳转和「第 N 条」快速定位。实现与 Windows 版 `law_parse.py::cn_to_int` 一致，
 * 支持到「万」。
 */
object ChineseNumber {

    private val NUM_MAP: Map<Char, Int> = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val UNIT_MAP: Map<Char, Int> = mapOf(
        '十' to 10, '百' to 100, '千' to 1000, '万' to 10000,
    )

    /**
     * 中文数字 → 整数。非法字符或空串返回 null。
     *
     * - `"一千二百六十"` → 1260
     * - `"二十二"` → 22
     * - `"十"` → 10
     * - `"一百五"` → 105（公文体例的省略写法）
     * - `"1260"` → 1260（纯阿拉伯数字原样解析）
     */
    fun cnToInt(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()

        var total = 0
        var section = 0
        var number = 0
        for (ch in s) {
            val digit = NUM_MAP[ch]
            if (digit != null) {
                number = digit
                continue
            }
            val unit = UNIT_MAP[ch] ?: return null
            if (unit == 10000) {
                section = (section + number) * unit
                total += section
                section = 0
            } else {
                if (number == 0) number = 1
                section += number * unit
            }
            number = 0
        }
        return total + section + number
    }

    private val CN_DIGITS = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')

    /** 整数 → 中文数字（法条编号风格：22 → 二十二，1260 → 一千二百六十，10 → 十） */
    fun intToCn(input: Int): String {
        if (input <= 0) return "零"
        if (input >= 100000) return input.toString()

        val sb = StringBuilder()
        val units = intArrayOf(10000, 1000, 100, 10)
        val unitChars = charArrayOf('万', '千', '百', '十')
        var rest = input
        var pendingZero = false
        for (i in units.indices) {
            val u = units[i]
            val d = rest / u
            if (d > 0) {
                if (pendingZero) sb.append('零')
                sb.append(CN_DIGITS[d]).append(unitChars[i])
                rest -= d * u
                pendingZero = false
            } else if (sb.isNotEmpty() && rest > 0) {
                pendingZero = true
            }
        }
        if (rest > 0) {
            if (pendingZero) sb.append('零')
            sb.append(CN_DIGITS[rest])
        }
        // 「一二」→「十二」：「十」字开头的习惯写法
        return if (sb.startsWith("一十")) sb.substring(1) else sb.toString()
    }

    /** 「第二十二条」→ 22；无法解析返回 null */
    fun articleNumberOf(no: String?): Int? {
        if (no == null) return null
        val m = Regex("第([一二三四五六七八九十百千零〇两\\d]+)条").find(no) ?: return null
        return cnToInt(m.groupValues[1])
    }
}
