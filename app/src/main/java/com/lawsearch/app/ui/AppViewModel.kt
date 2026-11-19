package com.lawsearch.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lawsearch.app.AppContainer
import com.lawsearch.app.data.GroupCount
import com.lawsearch.app.data.LawEntity
import com.lawsearch.app.data.LawListRow
import com.lawsearch.app.data.LawRepository
import com.lawsearch.app.data.SearchHit
import com.lawsearch.app.data.SubgroupCount
import com.lawsearch.app.util.ReaderSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 应用级 ViewModel：首页/列表/搜索/设置共用（数据量小，单例更简单，也避免各页重复初始化）。
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    val repo: LawRepository = container.repo
    val bundled = container.bundledData

    /* ---------------- 首页 ---------------- */

    data class HomeState(
        val loading: Boolean = true,
        val total: Int = 0,
        val groups: List<GroupCount> = emptyList(),
        val recent: List<LawRepository.RecentLaw> = emptyList(),
        val latest: List<LawListRow> = emptyList(),
    )

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    /* ---------------- 列表 ---------------- */

    data class ListState(
        val loading: Boolean = true,
        val group: String = "",
        val subgroups: List<SubgroupCount> = emptyList(),
        val laws: List<LawListRow> = emptyList(),
        val selectedSubgroup: String = "",
        /** 「最近更新」专用：按公布日期倒序，不显示二级分类 chip */
        val latestMode: Boolean = false,
    ) {
        val filtered: List<LawListRow>
            get() = if (selectedSubgroup.isEmpty()) laws else laws.filter { it.subgroup == selectedSubgroup }
    }

    private val _list = MutableStateFlow(ListState())
    val list: StateFlow<ListState> = _list.asStateFlow()

    /* ---------------- 搜索 ---------------- */

    data class SearchState(
        val query: String = "",
        val searching: Boolean = false,
        val hits: List<SearchHit> = emptyList(),
        val history: List<String> = emptyList(),
        val titleMatches: List<LawListRow> = emptyList(),
        val elapsedMs: Long = 0L,
        val searched: Boolean = false,
    )

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    /* ---------------- 阅读器 ---------------- */

    data class ReaderState(
        val loading: Boolean = true,
        val error: String? = null,
        val law: com.lawsearch.app.data.LawJson? = null,
        val fontSize: Int = ReaderSettings.fontSize,
        val fontMode: String = ReaderSettings.bodyFontMode,
    )

    private val _reader = MutableStateFlow(ReaderState())
    val reader: StateFlow<ReaderState> = _reader.asStateFlow()

    /* ---------------- 设置 ---------------- */

    data class SettingsState(
        val lawCount: Int = 0,
        val ftsArticles: Int = 0,
        val historyCount: Int = 0,
        val bookmarkCount: Int = 0,
        val bodyFontMode: String = "system",
    )

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    /* ---------------- 检查更新 ---------------- */

    data class UpdateState(
        val checking: Boolean = false,
        val info: com.lawsearch.app.crawler.UpdateChecker.ReleaseInfo? = null,
        val message: String? = null,
    )

    private val _update = MutableStateFlow(UpdateState())
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /* ---------------- 书签 ---------------- */

    data class BookmarkState(
        val loading: Boolean = true,
        val items: List<com.lawsearch.app.data.BookmarkRow> = emptyList(),
    )

    private val _bookmarks = MutableStateFlow(BookmarkState())
    val bookmarks: StateFlow<BookmarkState> = _bookmarks.asStateFlow()

    /** 当前法规里被收藏的 key 集合（阅读器里渲染实心/空心星标） */
    private val _bookmarkedKeys = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedKeys: StateFlow<Set<String>> = _bookmarkedKeys.asStateFlow()

    init {
        ReaderSettings.fontSize = container.prefs.readerFontSize
        ReaderSettings.bodyFontMode = container.prefs.bodyFontMode
        refreshHome()
        loadSearchHistory()
        loadSettings()
        // 数据变化（导入/更新完成）后自动刷新首页
        viewModelScope.launch {
            repo.dataVersion.combine(kotlinx.coroutines.flow.flowOf(Unit)) { v, _ -> v }
                .collect { refreshHome() }
        }
    }

    /**
     * 刷新首页。
     *
     * 注意：**不要**在这里等 `importIfNeeded()` —— 导入在 [com.lawsearch.app.LawApp]
     * 的启动协程里做，766 部数据首次导入要一两分钟。早期版本在这里也调一次导入，
     * 结果首页一直卡在「正在载入…」（导入完成前 groupCounts 是空的）。
     * 这里只读当前已有数据；导入完成后由 dataVersion 信号触发再次刷新。
     */
    fun refreshHome(): Job = viewModelScope.launch {
        val groups = runCatching { repo.groupCounts() }.getOrDefault(emptyList())
        val total = groups.sumOf { it.count }
        val recent = runCatching { repo.recentLaws(6) }.getOrDefault(emptyList())
        val latest = runCatching { repo.latestLaws(12) }.getOrDefault(emptyList())
        _home.value = HomeState(loading = false, total = total, groups = groups, recent = recent, latest = latest)
        // 数据为空说明还在导入：持续轮询直到有数据（导入中首页显示进度卡片）
        if (total == 0 && homeRetries < 400) {
            homeRetries++
            kotlinx.coroutines.delay(1500)
            refreshHome()
        } else if (total > 0) {
            homeRetries = 0
        }
    }

    /** refreshHome 的轮询计数，防止数据为空时无限递归 */
    private var homeRetries = 0

    /* ---------------- 列表 ---------------- */

    fun loadList(group: String) {
        _list.value = _list.value.copy(loading = true, group = group)
        viewModelScope.launch {
            val laws = runCatching { repo.listByGroup(group) }.getOrDefault(emptyList())
            val subs = runCatching { repo.subgroupCounts(group) }.getOrDefault(emptyList())
            _list.value = ListState(
                loading = false,
                group = group,
                subgroups = subs,
                laws = laws,
                selectedSubgroup = _list.value.selectedSubgroup.takeIf { g ->
                    subs.any { it.subgroup == g }
                } ?: "",
            )
        }
    }

    fun selectSubgroup(subgroup: String) {
        _list.value = _list.value.copy(selectedSubgroup = subgroup)
    }

    /** 「最近更新」：全部法规按公布日期倒序 */
    fun loadLatest() {
        _list.value = ListState(loading = true, group = "", latestMode = true)
        viewModelScope.launch {
            val laws = runCatching { repo.latestLaws(500) }.getOrDefault(emptyList())
            _list.value = ListState(
                loading = false,
                group = "",
                subgroups = emptyList(),
                laws = laws,
                latestMode = true,
            )
        }
    }

    /* ---------------- 搜索 ---------------- */

    private var pending: kotlinx.coroutines.Job? = null

    fun onQueryChange(query: String) {
        _search.value = _search.value.copy(query = query)
        pending?.cancel()
        if (query.isBlank()) {
            _search.value = _search.value.copy(hits = emptyList(), searched = false, searching = false)
            return
        }
        pending = viewModelScope.launch {
            kotlinx.coroutines.delay(180) // 输入防抖
            runSearch(query, remember = false)
        }
    }

    fun submitQuery(query: String = _search.value.query) {
        pending?.cancel()
        if (query.isBlank()) return
        pending = viewModelScope.launch { runSearch(query, remember = true) }
    }

    private suspend fun runSearch(query: String, remember: Boolean) {
        _search.value = _search.value.copy(searching = true)
        val started = System.currentTimeMillis()
        // 兜底：若全文索引还没建好（首次启动导入尚未完成），先补齐再搜，
        // 否则用户第一次搜索会得到"没有找到"。
        runCatching {
            if (repo.fts.count() == 0) container.ensureIndex()
        }
        val hits = runCatching { repo.search(query, 60) }.getOrDefault(emptyList())
        val elapsed = System.currentTimeMillis() - started
        if (remember) {
            repo.recordSearch(query)
            loadSearchHistory()
        }
        _search.value = _search.value.copy(
            searching = false,
            hits = hits,
            searched = true,
            elapsedMs = elapsed,
        )
    }

    fun loadSearchHistory() {
        viewModelScope.launch {
            val history = runCatching { repo.searchHistory(10) }.getOrDefault(emptyList())
            _search.value = _search.value.copy(history = history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repo.clearSearchHistory()
            loadSearchHistory()
        }
    }

    /* ---------------- 阅读器 ---------------- */

    private var readingBbbs: String = ""

    fun openLaw(bbbs: String, anchor: String) {
        readingBbbs = bbbs
        _reader.value = ReaderState(loading = true, law = null)
        viewModelScope.launch {
            val law = runCatching { repo.loadLaw(bbbs) }.getOrNull()
            if (law == null) {
                _reader.value = ReaderState(loading = false, error = "法规正文缺失，请到设置页更新法律库", law = null)
            } else {
                _reader.value = ReaderState(loading = false, law = law)
                runCatching { repo.recordView(bbbs, anchor) }
            }
        }
    }

    fun rememberAnchor(anchor: String) {
        if (readingBbbs.isNotEmpty() && anchor.isNotEmpty()) {
            viewModelScope.launch { runCatching { repo.recordView(readingBbbs, anchor) } }
        }
    }

    /** 上次读到的条文 id（用于重新打开时恢复位置） */
    suspend fun lastReadAnchor(bbbs: String): String? =
        runCatching { repo.lastAnchor(bbbs) }.getOrNull()

    /** 滚动时保存当前阅读到哪一条（轻量节流，避免频繁写库） */
    fun saveReadingAnchor(bbbs: String, anchor: String) {
        if (bbbs.isEmpty() || anchor.isEmpty()) return
        if (anchor == lastSavedAnchor.get()) return
        lastSavedAnchor.set(anchor)
        viewModelScope.launch {
            runCatching { repo.saveAnchor(bbbs, anchor) }
        }
    }

    private val lastSavedAnchor = java.util.concurrent.atomic.AtomicReference("")

    fun setReaderFontSize(size: Int) {
        val v = size.coerceIn(15, 22)
        ReaderSettings.fontSize = v
        container.prefs.readerFontSize = v
        _reader.value = _reader.value.copy(fontSize = v)
    }

    /** 液态玻璃开关 */
    fun glassEnabled(): Boolean = container.prefs.glassEffects

    fun setGlassEffects(enabled: Boolean) {
        container.prefs.glassEffects = enabled
    }

    /** 正文字体：system（系统默认）/ fangsong（内置仿宋）/ kaiti（内置楷体） */
    fun setBodyFontMode(mode: String) {
        ReaderSettings.bodyFontMode = mode
        container.prefs.bodyFontMode = mode
        _settings.value = _settings.value.copy(bodyFontMode = mode)
        _reader.value = _reader.value.copy(fontMode = mode)
    }

    /* ---------------- 书签 ---------------- */

    fun loadBookmarks() = viewModelScope.launch {
        _bookmarks.value = _bookmarks.value.copy(loading = true)
        val items = runCatching { repo.bookmarks() }.getOrDefault(emptyList())
        _bookmarks.value = BookmarkState(loading = false, items = items)
        _settings.value = _settings.value.copy(bookmarkCount = items.size)
    }

    /** 刷新当前法规的收藏状态 */
    fun loadBookmarkKeys(bbbs: String) = viewModelScope.launch {
        _bookmarkedKeys.value = runCatching { repo.bookmarkedKeys(bbbs) }.getOrDefault(emptySet())
    }

    fun toggleBookmark(
        bbbs: String,
        lawTitle: String,
        anchor: String,
        articleNo: String,
        excerpt: String,
    ) = viewModelScope.launch {
        val key = com.lawsearch.app.data.bookmarkKey(bbbs, anchor)
        val exists = runCatching { repo.isBookmarked(key) }.getOrDefault(false)
        if (exists) {
            repo.removeBookmark(key)
        } else {
            repo.addBookmark(bbbs, lawTitle, anchor, articleNo, excerpt)
        }
        loadBookmarkKeys(bbbs)
        loadBookmarks()
    }

    fun removeBookmark(key: String) = viewModelScope.launch {
        repo.removeBookmark(key)
        loadBookmarks()
    }

    /* ---------------- 设置 ---------------- */

    fun loadSettings() {
        viewModelScope.launch {
            val count = runCatching { repo.lawCount() }.getOrDefault(0)
            val fts = runCatching { repo.integrity()["ftsArticles"] ?: 0 }.getOrDefault(0)
            val history = runCatching { repo.searchHistory(100).size }.getOrDefault(0)
            val bookmarks = runCatching { repo.bookmarkCount() }.getOrDefault(0)
            _settings.value = SettingsState(
                lawCount = count,
                ftsArticles = fts,
                historyCount = history,
                bookmarkCount = bookmarks,
                bodyFontMode = container.prefs.bodyFontMode,
            )
        }
    }

    /* ---------------- 检查 App 新版本（唯一联网行为，用户主动触发） ---------------- */

    fun checkAppUpdate() {
        if (_update.value.checking) return
        _update.value = _update.value.copy(checking = true, message = null, info = null)
        viewModelScope.launch {
            val current = versionName()
            val result = runCatching {
                com.lawsearch.app.crawler.UpdateChecker(container.appContext).check(current)
            }.getOrElse { com.lawsearch.app.crawler.UpdateChecker.Result.Failure(it.message ?: "未知错误") }

            _update.value = when (result) {
                is com.lawsearch.app.crawler.UpdateChecker.Result.Success ->
                    _update.value.copy(checking = false, info = result.info, message = null)
                is com.lawsearch.app.crawler.UpdateChecker.Result.Failure ->
                    _update.value.copy(checking = false, message = result.reason)
            }
        }
    }

    private fun versionName(): String = runCatching {
        val ctx = container.appContext
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    fun dismissUpdate() {
        _update.value = UpdateState()
    }

    /* ---------------- 日志 ---------------- */

    fun buildLogReport(includeFull: Boolean): String =
        com.lawsearch.app.util.LogBuffer.buildReport(container.appContext, includeFull)

    fun clearLogs() = com.lawsearch.app.util.LogBuffer.clear()

    private fun bumpData() {
        repo.bumpVersion()
    }
}
