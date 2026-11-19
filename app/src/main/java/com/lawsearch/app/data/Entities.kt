package com.lawsearch.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 法规元数据（列表页只查这张表，正文 JSON 按需读文件） */
@Entity(tableName = "laws", indices = [Index("grp"), Index("subgroup")])
data class LawEntity(
    @PrimaryKey val bbbs: String,
    val title: String,
    val gbrq: String = "",
    val sxrq: String = "",
    val sxx: Int = 0,
    val validity: String = "",
    @ColumnInfo(name = "grp") val group: String = "",
    val subgroup: String = "",
    @ColumnInfo(name = "article_count") val articleCount: Int = 0,
    /** 正文文件路径（私有目录下 bbbs.json） */
    @ColumnInfo(name = "json_path") val jsonPath: String = "",
    @ColumnInfo(name = "zdjg_name") val zdjgName: String = "",
    @ColumnInfo(name = "flxz") val flxz: String = "",
    @ColumnInfo(name = "fetched_at") val fetchedAt: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

/** 最近浏览 */
@Entity(tableName = "recent", indices = [Index("viewed_at")])
data class RecentEntity(
    @PrimaryKey val bbbs: String,
    /** 最近一次停留的锚点（条 id），首页可直接继续阅读 */
    val anchor: String = "",
    @ColumnInfo(name = "viewed_at") val viewedAt: Long = 0L,
)

/** 搜索历史 */
@Entity(tableName = "search_history", indices = [Index(value = ["queried_at"])])
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    @ColumnInfo(name = "queried_at") val queriedAt: Long = 0L,
)

/** 键值设置（字号、主题等） */
@Entity(tableName = "prefs")
data class PrefEntity(
    @PrimaryKey val `key`: String,
    val value: String,
)

/* ---------------- 查询结果行 ---------------- */

/** 列表页用的轻量行 */
data class LawListRow(
    @ColumnInfo(name = "bbbs") val bbbs: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "gbrq") val gbrq: String,
    @ColumnInfo(name = "validity") val validity: String,
    @ColumnInfo(name = "article_count") val articleCount: Int,
    @ColumnInfo(name = "grp") val group: String,
    @ColumnInfo(name = "subgroup") val subgroup: String,
)

/** 首页分类计数 */
data class GroupCount(
    @ColumnInfo(name = "grp") val group: String,
    @ColumnInfo(name = "count") val count: Int,
)

data class SubgroupCount(
    @ColumnInfo(name = "subgroup") val subgroup: String,
    @ColumnInfo(name = "count") val count: Int,
)

/** 法规元数据 + 最近浏览时间（首页「最近浏览」用） */
data class RecentLawRow(
    @ColumnInfo(name = "bbbs") val bbbs: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "anchor") val anchor: String,
    @ColumnInfo(name = "viewed_at") val viewedAt: Long,
)
