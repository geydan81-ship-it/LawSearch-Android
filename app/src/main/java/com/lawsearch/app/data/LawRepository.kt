package com.lawsearch.app.data

import android.content.Context
import com.lawsearch.app.util.Validity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate

/**
 * 数据访问统一入口：法规正文文件、Room 元数据、FTS5 全文索引。
 *
 * 法规正文（单部 JSON）存在私有目录 files/laws/bbbs.json，
 * 与 Windows 版格式完全一致（样本目录可以直接拷进来）。
 *
 * 公开方法都是 suspend，内部通过 [dbCall] 切到 IO 线程。
 */
class LawRepository(
    private val context: Context,
    private val db: LawDatabase,
    val fts: ArticleFts,
) {
    private val lawDao get() = db.lawDao()
    private val recentDao get() = db.recentDao()
    private val historyDao get() = db.searchHistoryDao()
    private val prefDao get() = db.prefDao()
    private val bookmarkDao get() = db.bookmarkDao()

    val lawsDir: File
        get() = File(context.filesDir, "laws").apply { mkdirs() }

    private val _dataVersion = MutableStateFlow(0L)

    /** 数据变化信号：导入/更新完成后自增，界面据此刷新 */
    val dataVersion: Flow<Long> = _dataVersion.asStateFlow()

    fun bumpVersion() {
        _dataVersion.value = System.currentTimeMillis()
    }

    private fun fileFor(bbbs: String): File = File(lawsDir, "$bbbs.json")

    /* ---------------- 读取 ---------------- */

    suspend fun lawCount(): Int = dbCall { lawDao.count() }

    /**
     * 分类计数。法规总量约 1800 部，一次取回元数据在内存里统计，
     * 比在 Room 里为 GROUP BY 结果定义返回实体更稳妥。
     */
    suspend fun groupCounts(): List<GroupCount> = dbCall {
        lawDao.listAll()
            .groupBy { it.group }
            .map { (group, rows) -> GroupCount(group, rows.size) }
            .sortedByDescending { it.count }
    }

    suspend fun subgroupCounts(group: String): List<SubgroupCount> = dbCall {
        lawDao.listAll()
            .filter { it.group == group }
            .groupBy { it.subgroup }
            .map { (subgroup, rows) -> SubgroupCount(subgroup, rows.size) }
            .sortedBy { it.subgroup }
    }

    suspend fun meta(bbbs: String): LawEntity? = dbCall { lawDao.get(bbbs) }

    suspend fun listByGroup(group: String): List<LawListRow> = dbCall {
        lawDao.listAll().filter { it.group == group }.sortedBy { it.title }
    }

    suspend fun listBySubgroup(group: String, subgroup: String): List<LawListRow> = dbCall {
        lawDao.listAll().filter { it.group == group && it.subgroup == subgroup }.sortedBy { it.title }
    }

    suspend fun listAll(): List<LawListRow> = dbCall { lawDao.listAll() }

    suspend fun recentlyUpdated(limit: Int = 20): List<LawListRow> = dbCall { lawDao.recentlyUpdated(limit) }

    suspend fun latestLaws(limit: Int = 20): List<LawListRow> =
        dbCall { lawDao.listAll().sortedByDescending { it.gbrq }.take(limit) }

    suspend fun allIds(): Set<String> = dbCall { lawDao.allIds().toSet() }

    /**
     * 打开一部法规：读文件，若正文文件缺失返回 null（界面显示错误）。
     * 单部 JSON 直接读文件，不解析整库——民法典量级也在几十毫秒内。
     */
    suspend fun loadLaw(bbbs: String): LawJson? = dbCall {
        val f = fileFor(bbbs)
        if (!f.exists()) return@dbCall null
        val law = runCatching {
            val parsed = LawJsonCodec.decode(f.readText())
            if (parsed.bbbs.isBlank()) parsed.copy(bbbs = bbbs) else parsed
        }.getOrNull() ?: return@dbCall null
        val meta = lawDao.get(bbbs)
        law.copy(
            title = law.title.ifBlank { meta?.title ?: "" },
            validity = law.validity.ifBlank { meta?.validity ?: "" },
            group = law.group.ifBlank { meta?.group ?: "" },
            subgroup = law.subgroup.ifBlank { meta?.subgroup ?: "" },
        )
    }

    /* ---------------- 写入 ---------------- */

    /**
     * 保存一部法规：正文写文件 + 元数据写库 + 重建该法规的全文索引。
     *
     * 注意：这里只做写入，**不调用**其它 suspend 方法以外的嵌套写操作，
     * 避免在同一个 dbCall 里二次派发导致状态不一致（测试里曾出现索引写不进去的问题）。
     * 索引在同一个 IO 块里完成，保证索引条数与写入条数一致。
     */
    /**
     * 批量导入用：只写文件 + 记录实体，**不建 FTS 索引**。
     *
     * 1874 部法规逐部 fts.index() 会产生几千次索引写入，实测导入还没跑完
     * 进程就被系统杀掉。导入结束后统一调 [rebuildIndex] 一次性建索引。
     */
    suspend fun saveLawBulk(law: LawJson, validity: String? = null): LawEntity? = dbCall {
        if (law.bbbs.isBlank()) return@dbCall null
        val normalized = normalizeLaw(law)
        val f = fileFor(normalized.bbbs)
        f.writeText(LawJsonCodec.encode(normalized))
        LawEntity(
            bbbs = normalized.bbbs,
            title = normalized.title,
            gbrq = normalized.gbrq,
            sxrq = normalized.sxrq,
            sxx = normalized.sxx,
            validity = validity ?: normalized.validity,
            group = normalized.group,
            subgroup = normalized.subgroup,
            articleCount = normalized.articleCount,
            jsonPath = f.absolutePath,
            zdjgName = normalized.zdjgName,
            flxz = normalized.flxz,
            fetchedAt = normalized.fetchedAt,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun saveLaw(law: LawJson, validity: String? = null): Boolean = dbCall {
        if (law.bbbs.isBlank()) return@dbCall false
        val normalized = normalizeLaw(law)
        val f = fileFor(normalized.bbbs)
        val tmp = File(lawsDir, "${normalized.bbbs}.json.tmp")
        tmp.writeText(LawJsonCodec.encode(normalized))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
        lawDao.upsert(
            LawEntity(
                bbbs = normalized.bbbs,
                title = normalized.title,
                gbrq = normalized.gbrq,
                sxrq = normalized.sxrq,
                sxx = normalized.sxx,
                validity = validity ?: normalized.validity,
                group = normalized.group,
                subgroup = normalized.subgroup,
                articleCount = normalized.articleCount,
                jsonPath = f.absolutePath,
                zdjgName = normalized.zdjgName,
                flxz = normalized.flxz,
                fetchedAt = normalized.fetchedAt,
                updatedAt = System.currentTimeMillis(),
            )
        )
        fts.index(normalized)
        true
    }

    /**
     * 批量写库（导入专用，单个事务）。
     */
    suspend fun upsertEntities(rows: List<LawEntity>) = dbCall {
        if (rows.isEmpty()) return@dbCall
        lawDao.upsertAll(rows)
    }

    /**
     * 重建全文索引。
     *
     * 导入 1874 部法规时逐部建索引太慢，改为导入结束后统一跑一次：
     * 读出全部 (bbbs, jsonPath)，交给 [ArticleFts.rebuild]。
     */
    suspend fun rebuildIndex(): Int = dbCall {
        fts.ensureSchema()
        // rebuild 需要 (bbbs, JSON 正文)，不是文件路径
        val rows = lawDao.getAll().mapNotNull { e ->
            val f = File(e.jsonPath)
            if (f.exists()) runCatching { e.bbbs to f.readText() }.getOrNull() else null
        }
        fts.rebuild(rows)
        rows.size
    }

    private fun normalizeLaw(law: LawJson): LawJson {
        val articles = law.blocks.count { it.isArticle }
        val group = law.group.ifBlank { "法律" }
        return law.copy(
            articleCount = if (law.articleCount > 0) law.articleCount else articles,
            group = group,
            subgroup = law.subgroup.ifBlank { group },
            title = law.title.ifBlank { law.meta.title },
        )
    }

    suspend fun deleteLaw(bbbs: String) = dbCall {
        lawDao.delete(bbbs)
        fts.remove(bbbs)
        fileFor(bbbs).delete()
        Unit
    }

    /* ---------------- 搜索 ---------------- */

    suspend fun search(query: String, limit: Int = 50): List<SearchHit> = dbCall {
        val q = query.trim()
        if (q.isEmpty()) return@dbCall emptyList()
        val metaCache = HashMap<String, LawEntity?>()
        val lookup: (String) -> LawEntity? = { id -> metaCache.getOrPut(id) { lawDao.get(id) } }
        val hits = fts.search(q, limit, lookup).toMutableList()

        // 标题 LIKE 兜底（单字、特殊字符导致 FTS 无结果时）。
        //
        // ⚠️ 判断条件原来是 `hits.none { it.title.contains(q) }` —— 语义是
        // 「结果里没有任何一部法规的标题包含查询串」，但 ArticleFts.search() 在
        // 有正文命中时**已经**先返回了（不再补标题命中），所以这里再查一遍标题
        // 会把同一批「在法规名称中匹配」的结果重复加进去。
        // 改成只在**完全没有任何结果**时才兜底，这也正是它原本的注释意图。
        if (hits.isEmpty()) {
            for (row in lawDao.searchByTitle(q, 10)) {
                if (hits.any { it.bbbs == row.bbbs && it.anchor.isEmpty() }) continue
                hits.add(
                    SearchHit(
                        bbbs = row.bbbs,
                        title = row.title,
                        articleNo = "",
                        anchor = "",
                        preview = "在法规名称中匹配",
                        group = row.group,
                        subgroup = row.subgroup,
                        gbrq = row.gbrq,
                        score = 500.0,
                    )
                )
            }
        }
        hits
    }

    suspend fun recordSearch(query: String) = dbCall {
        val q = query.trim()
        if (q.isNotEmpty()) historyDao.touch(SearchHistoryEntity(q, System.currentTimeMillis()))
        Unit
    }

    suspend fun searchHistory(limit: Int = 10): List<String> =
        dbCall { historyDao.latest(limit).map { it.query } }

    suspend fun clearSearchHistory() = dbCall { historyDao.clear() }

    /* ---------------- 最近浏览 ---------------- */

    suspend fun recordView(bbbs: String, anchor: String = "") = dbCall {
        recentDao.touch(RecentEntity(bbbs, anchor, System.currentTimeMillis()))
        Unit
    }

    /** 最近一次读到的条文 id */
    suspend fun lastAnchor(bbbs: String): String? = dbCall {
        recentDao.latest(50).firstOrNull { it.bbbs == bbbs }?.anchor?.takeIf { it.isNotBlank() }
    }

    /**
     * 只更新锚点、不改「最近浏览」时间：
     * 滚动时会被频繁调用，不应把浏览时间刷成"刚刚"。
     */
    suspend fun saveAnchor(bbbs: String, anchor: String) = dbCall {
        recentDao.updateAnchor(bbbs, anchor)
        Unit
    }

    data class RecentLaw(val meta: LawEntity, val viewedAt: Long)

    suspend fun recentLaws(limit: Int = 5): List<RecentLaw> = dbCall {
        recentDao.latest(limit).mapNotNull { r -> lawDao.get(r.bbbs)?.let { RecentLaw(it, r.viewedAt) } }
    }

    /* ---------------- 设置 ---------------- */

    suspend fun getInt(key: String, default: Int): Int = dbCall { prefDao.get(key)?.toIntOrNull() ?: default }

    suspend fun putInt(key: String, value: Int) = dbCall { prefDao.put(PrefEntity(key, value.toString())) }

    suspend fun getString(key: String, default: String): String = dbCall { prefDao.get(key) ?: default }

    suspend fun putString(key: String, value: String) = dbCall { prefDao.put(PrefEntity(key, value)) }

    /* ---------------- 时效性 ---------------- */

    /**
     * 按规格书算法重算全库时效性：「已被修订」需要全库同名版本对比，所以整体一起算。
     * 只更新真正变了的行。
     */
    suspend fun recomputeValidity(today: LocalDate = LocalDate.now()) = dbCall {
        val all = lawDao.getAll()
        if (all.isEmpty()) return@dbCall
        val metas = all.map { Validity.LawMeta(it.bbbs, it.title, it.gbrq, it.sxrq, it.sxx) }
        // 批量更新：逐条 updateValidity 在 1874 部时会做近两千次独立事务，
        // 实测导入过程被拖到 ANR。这里收集变化行，一次性 upsertAll 写回（单个事务）。
        val changedRows = ArrayList<LawEntity>()
        all.forEachIndexed { i, entity ->
            val v = Validity.resolve(metas[i], metas, today)
            if (v != entity.validity) changedRows.add(entity.copy(validity = v))
        }
        if (changedRows.isNotEmpty()) {
            lawDao.upsertAll(changedRows)
            bumpVersion()
        }
        Unit
    }

    /** 数据体检：条数 / 缺正文文件 / 未建索引 */
    suspend fun integrity(): Map<String, Int> = dbCall {
        val ids = lawDao.allIds()
        val missingFiles = ids.count { !fileFor(it).exists() }
        val indexed = ids.count { runCatching { fts.isIndexed(it) }.getOrDefault(false) }
        mapOf(
            "laws" to ids.size,
            "missingFiles" to missingFiles,
            "indexed" to indexed,
            "ftsArticles" to fts.count(),
        )
    }

    /* ---------------- 书签 ---------------- */

    suspend fun bookmarks(): List<BookmarkRow> = dbCall {
        val entities = bookmarkDao.all()
        val validityOf = db.validityDao().allValidity().associate { it.bbbs to it.validity }
        entities.map { e ->
            BookmarkRow(
                key = e.key,
                bbbs = e.bbbs,
                anchor = e.anchor,
                lawTitle = e.lawTitle,
                articleNo = e.articleNo,
                excerpt = e.excerpt,
                createdAt = e.createdAt,
                validity = validityOf[e.bbbs].orEmpty(),
            )
        }
    }

    suspend fun bookmarkCount(): Int = dbCall { bookmarkDao.total() }

    suspend fun isBookmarked(key: String): Boolean = dbCall { bookmarkDao.count(key) > 0 }

    suspend fun bookmarkedKeys(bbbs: String): Set<String> = dbCall {
        bookmarkDao.forLaw(bbbs).mapTo(HashSet()) { if (it.anchor.isEmpty()) LAW_LEVEL else it.anchor }
    }

    suspend fun addBookmark(
        bbbs: String,
        lawTitle: String,
        anchor: String,
        articleNo: String,
        excerpt: String,
    ) = dbCall {
        bookmarkDao.insert(
            BookmarkEntity(
                key = bookmarkKey(bbbs, anchor),
                bbbs = bbbs,
                anchor = anchor,
                lawTitle = lawTitle,
                articleNo = articleNo,
                excerpt = excerpt.take(120).replace('\n', ' '),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeBookmark(key: String) = dbCall { bookmarkDao.delete(key) }

    companion object {
        /** 「整部法规」收藏在锚点位上使用的哨兵值 */
        const val LAW_LEVEL = "__law__"
    }
}

/** 书签主键：法规 id + 锚点（空串表示收藏整部法规） */
fun bookmarkKey(bbbs: String, anchor: String): String = "$bbbs::$anchor"
