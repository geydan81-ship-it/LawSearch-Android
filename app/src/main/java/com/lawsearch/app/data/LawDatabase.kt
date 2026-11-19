package com.lawsearch.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 书签：既能收藏整部法规（anchor 为空），也能收藏某一条文（anchor = 块 id）。
 *
 * 主键用 `bbbs + anchor` 拼成的 key，保证同一位置不会重复收藏。
 */
@Entity(tableName = "bookmark")
data class BookmarkEntity(
    @PrimaryKey val key: String,
    val bbbs: String,
    /** 空串表示收藏整部法规；否则是条文块 id */
    val anchor: String,
    /** 冗余存标题与条文号，避免列表页为每条书签再查一次库 */
    val lawTitle: String,
    val articleNo: String,
    /** 条文摘要，列表里显示一行 */
    val excerpt: String,
    val createdAt: Long,
)

/** 书签列表行（供 UI 直接渲染） */
data class BookmarkRow(
    val key: String,
    val bbbs: String,
    val anchor: String,
    val lawTitle: String,
    val articleNo: String,
    val excerpt: String,
    val createdAt: Long,
    val validity: String,
)

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark ORDER BY createdAt DESC")
    fun all(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE key = :key LIMIT 1")
    fun find(key: String): BookmarkEntity?

    @Query("SELECT COUNT(*) FROM bookmark WHERE key = :key")
    fun count(key: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: BookmarkEntity)

    @Query("DELETE FROM bookmark WHERE key = :key")
    fun delete(key: String)

    @Query("DELETE FROM bookmark WHERE bbbs = :bbbs")
    fun deleteByLaw(bbbs: String)

    @Query("SELECT COUNT(*) FROM bookmark")
    fun total(): Int

    /** 某部法规下的所有书签（阅读器渲染星标用） */
    @Query("SELECT * FROM bookmark WHERE bbbs = :bbbs")
    fun forLaw(bbbs: String): List<BookmarkEntity>
}

/** 法规 id → 时效性（书签列表里显示「有效/已修改」） */
@Dao
interface ValidityDao {
    @Query("SELECT bbbs, validity FROM laws")
    fun allValidity(): List<ValidityRow>
}

data class ValidityRow(val bbbs: String, val validity: String)

@Database(
    entities = [
        LawEntity::class,
        RecentEntity::class,
        SearchHistoryEntity::class,
        PrefEntity::class,
        BookmarkEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LawDatabase : RoomDatabase() {
    abstract fun lawDao(): LawDao
    abstract fun recentDao(): RecentDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun prefDao(): PrefDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun validityDao(): ValidityDao

    companion object {
        const val NAME = "lawsearch.db"
    }
}
