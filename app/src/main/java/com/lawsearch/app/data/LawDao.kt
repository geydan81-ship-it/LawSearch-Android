package com.lawsearch.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LawDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(law: LawEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(laws: List<LawEntity>)

    @Query("SELECT * FROM laws WHERE bbbs = :bbbs LIMIT 1")
    fun get(bbbs: String): LawEntity?

    @Query("SELECT * FROM laws")
    fun getAll(): List<LawEntity>

    @Query("UPDATE laws SET validity = :validity WHERE bbbs = :bbbs")
    fun updateValidity(bbbs: String, validity: String)

    /**
     * 批量更新效力。
     *
     * 导入 1874 部法规时，逐条 updateValidity 会产生近两千次独立事务，
     * 实测会卡到 ANR / 进程被系统杀掉。这里用 upsertAll 走单个事务：
     * 先读出受影响的整行、改 validity 字段、一次性写回。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun applyValidityBatch(rows: List<LawEntity>)

    @Query("SELECT * FROM laws WHERE bbbs IN (:bbbsList)")
    fun getMany(bbbsList: List<String>): List<LawEntity>

    @Query("SELECT COUNT(*) FROM laws")
    fun count(): Int

    @Query("SELECT bbbs, title, gbrq, validity, article_count, grp, subgroup FROM laws ORDER BY grp, title")
    fun listAll(): List<LawListRow>

    @Query(
        "SELECT bbbs, title, gbrq, validity, article_count, grp, subgroup FROM laws " +
            "ORDER BY updated_at DESC LIMIT :limit"
    )
    fun recentlyUpdated(limit: Int): List<LawListRow>

    /** 标题检索：参数只作为字面量拼进 LIKE 模式 */
    @Query(
        "SELECT bbbs, title, gbrq, validity, article_count, grp, subgroup FROM laws " +
            "WHERE title LIKE '%' || :query || '%' ORDER BY LENGTH(title) LIMIT :limit"
    )
    fun searchByTitle(query: String, limit: Int): List<LawListRow>

    @Query("DELETE FROM laws WHERE bbbs = :bbbs")
    fun delete(bbbs: String)

    @Query("SELECT bbbs FROM laws")
    fun allIds(): List<String>
}

@Dao
interface RecentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun touch(item: RecentEntity)

    @Query("SELECT * FROM recent ORDER BY viewed_at DESC LIMIT :limit")
    fun latest(limit: Int): List<RecentEntity>

    /** 只更新阅读锚点，不改浏览时间（滚动时频繁调用） */
    @Query("UPDATE recent SET anchor = :anchor WHERE bbbs = :bbbs")
    fun updateAnchor(bbbs: String, anchor: String)
}

@Dao
interface SearchHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun touch(item: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY queried_at DESC LIMIT :limit")
    fun latest(limit: Int): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history")
    fun clear()

    @Query("DELETE FROM search_history WHERE `query` = :query")
    fun delete(query: String)
}

@Dao
interface PrefDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(pref: PrefEntity)

    @Query("SELECT value FROM prefs WHERE `key` = :key LIMIT 1")
    fun get(key: String): String?
}
