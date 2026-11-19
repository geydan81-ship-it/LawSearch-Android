package com.lawsearch.app.data

import android.content.Context
import com.lawsearch.app.BuildConfig
import com.lawsearch.app.util.LogBuffer
import com.lawsearch.app.util.Validity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 内置法规数据导入。
 *
 * APK 的 `assets/laws/` 里放 `_meta.json` + `{bbbs}.json`（可以是 `.json.gz`，
 * 由 `tools/bundle_laws.py` 生成，体积约为原始 JSON 的 40%）。
 * 首次启动时解压到私有目录并建好全文索引，之后纯离线可用。
 */
class BundledData(
    private val context: Context,
    private val repo: LawRepository,
) {
    private val assetDir = BuildConfig.BUNDLED_LAWS_DIR

    data class Progress(val done: Int, val total: Int, val title: String)

    /** assets 里是否有内置数据 */
    fun available(): Boolean {
        if (!BuildConfig.BUNDLE_LAWS) return false
        // 用清单判断，不要依赖 AssetManager.list()（它有返回条数上限）
        return runCatching { lawAssetNames().isNotEmpty() }.getOrDefault(false)
    }

    /**
     * 列出 assets 里全部法规文件名。
     *
     * ⚠️ **不能用 `context.assets.list(dir)`**：Android 的 AssetManager.list()
     * 对单个目录有返回条数上限（实测 1874 个文件只返回 766 个），
     * 导致「APK 里有 1874 部但只导入了 766 部」。
     *
     * 改为读打包时生成的 `_index.json.gz` 清单，再逐个 open 对应文件。
     */
    private fun lawAssetNames(): List<String> = runCatching {
        // assets 里的文件可能是 .json.gz 也可能是 .json（取决于构建时的压缩配置），
        // 两种都试，谁存在用谁。
        val index = sequenceOf("_index.json.gz", "_index.json")
            .mapNotNull { n -> runCatching { readAsset(n) }.getOrNull() }
            .firstOrNull()
            ?: return@runCatching emptyList()
        Regex("\"([0-9a-f]{32})\"").findAll(index).map { it.groupValues[1] + ".json" }.toList()
    }.getOrDefault(emptyList())

    /** 读某个 bbbs 的正文：同样兼容 .json / .json.gz */
    private fun readLawAsset(bbbs: String): String {
        for (name in listOf("$bbbs.json.gz", "$bbbs.json")) {
            runCatching { return readAsset(name) }
        }
        throw java.io.FileNotFoundException("$bbbs 在 assets 中不存在")
    }

    /**
     * 内置数据的版本指纹。
     *
     * 之前是写死的常量 "1"，每次扩充法规库都要手动改，忘了改就导致
     * 「新数据打包进 APK 了但 App 认为已导入过、直接跳过」——1874 部数据
     * 装进去后首页仍显示 766 部就是这么来的。
     *
     * 现在用「导入逻辑版本 + 法规数 + 数据日期」三者组合成指纹：
     * 任意一项变化都会触发重新导入。
     *
     * ## ⚠️ 法规数那个正则是坏的，别照抄
     *
     * 原实现：
     * ```
     * Regex("\"laws\"\\s*:\\s*(\\d+)")   // 期望 "laws": 1874
     * ```
     * 但 `_meta.json.gz` 的实际结构是 **`laws` 为对象**（bbbs → 元数据），
     * 开头是 `{"laws": {"00d020f3...": {...` —— **这个正则永远匹配不到数字**，
     * 于是指纹里的法规数恒为 `"?"`。
     *
     * 危害：如果哪天数据变了但 `updatedAt` 没变（比如上游只换了正文没动日期），
     * 指纹不变 → 不重导入 → 用户拿不到新数据。是个定时炸弹。
     *
     * 现在改成**数 bbbs 的个数**（`"<32位hex>":` 出现几次），不依赖 JSON 结构。
     */
    private fun assetFingerprint(): String = runCatching {
        // 兼容 .json.gz / .json
        val meta = sequenceOf("_meta.json.gz", "_meta.json")
            .mapNotNull { n -> runCatching { readAsset(n) }.getOrNull() }
            .firstOrNull() ?: return@runCatching IMPORT_VERSION
        // laws 是对象不是数字，所以数 bbbs 键的个数（与 lawAssetNames 同一套 id 格式）
        val laws = Regex("\"[0-9a-f]{32}\"\\s*:").findAll(meta).count()
        val at = Regex("\"updatedAt\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
        "${IMPORT_VERSION}:${if (laws > 0) laws else "?"}:${at ?: "?"}"
    }.getOrDefault(IMPORT_VERSION)

    /** 是否已经导入过 */
    suspend fun isImported(): Boolean =
        withContext(Dispatchers.IO) {
            repo.getString(PREF_IMPORTED, "") == assetFingerprint() && repo.lawCount() > 0
        }

    private fun readAsset(name: String): String {
        val raw = context.assets.open("$assetDir/$name").use { it.readBytes() }
        val text = if (name.endsWith(".gz") || (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())) {
            GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }
        return text.toString(Charsets.UTF_8)
    }

    /**
     * 导入内置数据。
     *
     * @param overwrite 强制覆盖（即使指纹一致）。
     * @param onProgress 进度回调。
     * @return 本次真正写入磁盘的法规数。
     *
     * ## ⚠️ 关键修复：「导入触发了但空转」的 bug
     *
     * 修复前这里是这样：
     * ```kotlin
     * if (!overwrite && isImported()) return
     * val existing = if (overwrite) emptySet() else repo.allIds()   // ← 永远是全量 existing
     * ...
     * if (bbbs in existing) return@forEachIndexed                    // ← 1874 次全跳过
     * ```
     *
     * 唯一调用点 `LawApp.kt` **从不传 `overwrite`**，所以 `existing` 永远是
     * 全部已导入的 bbbs。当 `IMPORT_VERSION` 变化、`isImported()` 正确返回 false
     * 时，导入流程会**启动并跑完**，但 1874 次循环每次都在 `bbbs in existing`
     * 处 `continue` —— **一条 JSON 都没重读、一条记录都没重写**。
     *
     * 更隐蔽的是：流程最后仍然 `putString(PREF_IMPORTED, 新指纹)`，
     * 于是指纹被更新成新的，**下次启动 `isImported()` 返回 true，永远不再尝试**。
     * 表现为：升级安装后标题/正文全是旧的，但导入"看起来成功了"（进度条走完、无报错）。
     *
     * ## 修复
     *
     * 指纹不匹配 = 打包数据变了 = **必须覆盖**，而不是"跳过已存在的"：
     * ```kotlin
     * val upToDate = isImported()
     * if (!overwrite && upToDate) return          // 已经最新，真的不用做
     * val forceOverwrite = overwrite || !upToDate  // 数据变了 → 全量覆盖
     * val existing = if (forceOverwrite) emptySet() else repo.allIds()
     * ```
     *
     * 安全性：
     *  - **已经是最新指纹时启动路径零变化**（原地 return，不读 assets）
     *  - 数据变了才全量覆盖，`LawDao.upsertAll` 是 `OnConflictStrategy.REPLACE`，
     *    按主键 `bbbs` 覆盖，**不会产生重复行**，也不会删除任何新数据
     *  - 不清库：`bookmarks`/`recent` 里冗余存的 `lawTitle` 不会因此失效；
     *    导入中途失败时用户也不会看到空库
     *
     * ## 已知限制（未处理）
     *
     * 如果新数据**删掉**了某个 bbbs（本次宪法清理只改名、没删），旧记录会残留在
     * `laws` 表里变成"孤儿"。清理孤儿需要同时处理 `bookmarks` / `recent` 的引用，
     * 属于更大改动，本次未做。
     */
    suspend fun importIfNeeded(
        overwrite: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        if (!available()) return@withContext 0

        // 先判断数据是否已是最新 —— 注意这里**不能**把它当成"是否需要跳过覆盖"的唯一依据
        val upToDate = isImported()
        if (!overwrite && upToDate) return@withContext 0
        // 走到这里说明「强制覆盖」或「指纹变了」，两种情况都必须重新读一遍 JSON
        val forceOverwrite = overwrite || !upToDate

        // 清单里是 bbbs（32 位 hex），逐个读正文
        val lawFiles = lawAssetNames()
        var imported = 0
        // ⚠️ 只有「不是强制覆盖」时才跳过已有条目。指纹变了（forceOverwrite=true）
        // 时 existing 必须为空，否则又会退化成"全都跳过"的老 bug。
        val existing = if (forceOverwrite) emptySet() else repo.allIds()

        ImportProgress.start(lawFiles.size)

        val batch = ArrayList<LawEntity>(100)
        lawFiles.forEachIndexed { index, fileName ->
            val bbbs = fileName.substringBefore('.').removeSuffix(".json")
            if (bbbs in existing) {
                ImportProgress.step(index + 1, "")
                return@forEachIndexed
            }
            try {
                val law = LawJsonCodec.decode(readLawAsset(bbbs)).let {
                    if (it.bbbs.isBlank()) it.copy(bbbs = bbbs) else it
                }
                ImportProgress.step(index + 1, law.title)
                onProgress(Progress(index + 1, lawFiles.size, law.title))
                repo.saveLawBulk(law, law.validity.ifBlank { null })?.let { batch.add(it) }
                if (batch.size >= 100) {
                    repo.upsertEntities(batch)
                    batch.clear()
                }
                imported++
            } catch (_: Exception) {
                // 单部损坏不影响整体导入
            }
        }
        if (batch.isNotEmpty()) repo.upsertEntities(batch)

        // 导入完成后统一重建全文索引（一次性，比逐部建快得多）
        repo.rebuildIndex()
        repo.recomputeValidity()

        // ⚠️ 防"永久空转"：只有真的写入了数据，才把指纹标记为新版。
        //
        // 如果 lawFiles 为空（assets 目录被裁掉）或全部解码失败，imported 会是 0。
        // 这时若仍写入新指纹，下次启动 isImported() 直接返回 true，
        // 用户将**永远没有机会**再导一次 —— 这正是上一版 bug 的同类灾难。
        // 宁可每次启动都重试，也不要假装成功。
        if (imported > 0) {
            repo.putString(PREF_IMPORTED, assetFingerprint())
        } else {
            LogBuffer.w(
                "Import",
                "未写入任何法规（lawFiles=${lawFiles.size}），保留旧指纹以便下次重试",
            )
        }
        repo.bumpVersion()
        ImportProgress.finish()
        imported
    }

    /** 内置数据的法规标题（用于统计信息展示） */
    fun metaLawCount(): Int = runCatching {
        if (!available()) return 0
        // 兼容 .json / .json.gz
        val raw = sequenceOf("_meta.json.gz", "_meta.json")
            .mapNotNull { n -> runCatching { readAsset(n) }.getOrNull() }
            .firstOrNull() ?: return 0
        LawJsonCodec.decodeMeta(raw).laws.size
    }.getOrDefault(0)

    /** 首次启动需要导入时的标记 */
    suspend fun markImported() = withContext(Dispatchers.IO) {
        repo.putString(PREF_IMPORTED, assetFingerprint())
    }

    /** 是否有一部法规的正文文件存在（用于「更新失败时只做内置数据」的降级） */
    fun lawFileExists(bbbs: String): Boolean = File(repo.lawsDir, "$bbbs.json").exists()

    companion object {
        const val PREF_IMPORTED = "bundled_imported"

        /**
         * 导入逻辑的版本。**改动导入逻辑或数据格式时必须 +1**，
         * 否则老用户升级后 isImported() 为 true，永远不会重新导入。
         *
         * v3：`Block` 补上了 `content` 字段。
         * 之前这个字段被 `ignoreUnknownKeys` 静默丢弃，导致约一半法规
         * （935 部 / 28819 条）落库时正文为空 —— 阅读器里只看得到「第一条」标题。
         * 必须重新导入，`laws` 表里存的那份 JSON 才会有 content；
         * 全文索引也是在导入末尾由 rebuildIndex() 重建的，一并修好。
         *
         * v4：**宪法数据清理**（标题层面，正文一字未改）。
         *  - `7ffbd0c9…`「中华人民共和国宪法」→「中华人民共和国宪法（含序言·早期整理版）」。
         *    它与「宪法（2018年修正文本）」的 143 条正文逐条完全相同，是同一部法律的
         *    两次不同整理；而它是**唯一带完整序言**（14 个 text 块）的版本，不能删。
         *    原名字在 `ORDER BY grp, title` 下排宪法组第一，导致用户点宪法必然先点到
         *    这份结构最脏（无 label / 无 toc / 有条号裸数字）的版本。改名后它排到组内最后。
         *  - `ff808181…`「 全国污染源普查条例」的标题**前导空格**已去除（全库仅此 1 例）。
         *
         * 不升这个版本号，老用户的 `laws` 表里仍是旧标题，列表里会一直显示旧名字。
         */
        const val IMPORT_VERSION = "4"
    }
}
