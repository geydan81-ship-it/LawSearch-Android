package com.lawsearch.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.lawsearch.app.data.ArticleFts
import com.lawsearch.app.data.BundledData
import com.lawsearch.app.data.LawDatabase
import com.lawsearch.app.data.LawRepository
import com.lawsearch.app.util.LogBuffer
import com.lawsearch.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口 + 极简依赖注入（没有引入 Hilt，避免额外注解处理器拖慢构建）。
 *
 * ⚠️ 本应用**不做任何自动化网络访问**。
 * 法律库数据完全内置随版本发布；唯一的联网是用户主动点「检查 App 新版本」时，
 * 查询 GitHub Release 元数据（不下载任何内容）。这样既不影响离线使用，
 * 也避免了对官方数据源的高频访问。
 */
class LawApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LogBuffer.install(this)
        container = AppContainer(this)
        // 壁纸设置的持久化
        com.lawsearch.app.ui.wallpaper.WallpaperSettings.prefs = container.prefs
        com.lawsearch.app.ui.wallpaper.WallpaperSettings.restore()
        // 首次启动：后台导入内置数据，导入后补齐全文索引（顺序很重要）
        container.appScope.launch {
            LogBuffer.i("Import", "开始检查内置数据…")
            val imported = runCatching { container.bundledData.importIfNeeded() }.getOrElse {
                LogBuffer.e("Import", "导入失败", it)
                -1
            }
            LogBuffer.i("Import", "导入结束 imported=$imported")
            container.repo.recomputeValidity()
            LogBuffer.i("Import", "开始补齐全文索引…")
            container.ensureIndex()
            LogBuffer.i("Import", "索引补齐完成")
            container.repo.bumpVersion()
        }
        // 清掉历史版本可能残留的定时爬取任务（旧版曾用 WorkManager 后台更新）
        container.cancelLegacyWorkers()
    }
}

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 供需要 Context 的组件使用（日志、版本检查等） */
    val appContext: Context = context.applicationContext

    private val app: Context get() = appContext

    val db: LawDatabase = Room.databaseBuilder(app, LawDatabase::class.java, LawDatabase.NAME)
        // FTS4 是手动建的虚表，这里不做破坏性迁移之外的特殊处理
        .fallbackToDestructiveMigration()
        .build()

    val fts = ArticleFts(db)
    val repo = LawRepository(app, db, fts)
    val bundledData = BundledData(app, repo)
    val prefs = Prefs(app)

    init {
        // 建虚表要尽早，但**索引补齐必须放在导入之后**：
        // 首次启动时 laws 表还是空的，若在 constructor 里补索引，会一条都补不到，
        // 结果就是"法规能打开、搜索永远没结果"。（这是真机上发现的问题。）
        appScope.launch {
            runCatching { fts.ensureSchema() }
        }
    }

    /** 补齐缺失的全文索引（导入/更新后调用，也用于启动自检） */
    suspend fun ensureIndex() {
        runCatching {
            fts.ensureSchema()
            val ids = db.lawDao().allIds()
            val missing = ids.filterNot { fts.isIndexed(it) }
            if (missing.isNotEmpty()) {
                LogBuffer.i("Index", "需要补索引 ${missing.size} 部")
                com.lawsearch.app.data.ImportProgress.startIndexing(missing.size)
            }
            missing.forEachIndexed { i, id ->
                repo.loadLaw(id)?.let { law -> runCatching { fts.index(law) } }
                com.lawsearch.app.data.ImportProgress.indexStep(i + 1)
            }
        }
    }

    /** 取消旧版本遗留的后台更新任务 */
    fun cancelLegacyWorkers() {
        runCatching { WorkManager.getInstance(app).cancelAllWork() }
    }

    companion object {
        /** 旧版本用过的工作名，保留常量仅用于清理 */
        const val LEGACY_WORK_NAME = "law_update"
    }
}
