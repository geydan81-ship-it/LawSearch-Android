package com.lawsearch.app.util

/**
 * 时间格式化。列表里显示「2 分钟前」「昨天」这类相对时间（iOS 风格）。
 */
object RelativeTime {

    fun format(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return ""
        val diff = now - epochMillis
        if (diff < 0) return "刚刚"
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour

        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            // 同一天内用小时
            diff < day -> "${diff / hour} 小时前"
            diff < 2 * day -> "昨天"
            diff < 7 * day -> "${diff / day} 天前"
            else -> {
                val days = diff / day
                when {
                    days < 30 -> "${days / 7} 周前"
                    days < 365 -> "${days / 30} 个月前"
                    else -> "${days / 365} 年前"
                }
            }
        }
    }
}
