package com.lawsearch.app.crawler

import android.content.Context
import com.lawsearch.app.util.LogBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * **只查版本号，不下载任何内容**。
 *
 * 这是本应用唯一的联网行为，且必须由用户主动触发。
 *
 * 为什么这样设计：
 *  - 法律库数据完全内置，随版本发布；用户不需要在线拉取法规正文。
 *  - 官方数据源有访问限制，App 不应承担爬取职责（合规 + 稳定）。
 *  - 这里的请求量极小（每次 1~2 个 HTTP GET 元数据），与"批量抓取"性质完全不同。
 *
 * GitHub 在国内常被阻断，所以按顺序尝试多个地址，任一成功即返回。
 */
class UpdateChecker(private val context: Context) {

    /** 仓库地址：发布时改成实际仓库 */
    companion object {
        const val OWNER = "lawsearch"
        const val REPO = "LawSearch-Android"

        private val API_URLS = listOf(
            "https://api.github.com/repos/$OWNER/$REPO/releases/latest",
            "https://gh-proxy.com/https://api.github.com/repos/$OWNER/$REPO/releases/latest",
            "https://ghfast.top/https://api.github.com/repos/$OWNER/$REPO/releases/latest",
        )
    }

    data class ReleaseInfo(
        val tagName: String,
        /** 去掉前缀 v 的版本名，例如 1.1.0 */
        val version: String,
        val name: String,
        val publishedAt: String,
        val body: String,
        val htmlUrl: String,
        /** 当前是否已经是最新 */
        val isLatest: Boolean,
        /** 检查时是否走了镜像 */
        val viaMirror: Boolean,
    )

    sealed class Result {
        data class Success(val info: ReleaseInfo) : Result()
        data class Failure(val reason: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun check(currentVersionName: String): Result {
        var lastError: String? = null
        for ((index, url) in API_URLS.withIndex()) {
            val viaMirror = index > 0
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "LawSearch-Android")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    if (body.trimStart().startsWith("<")) {
                        error("返回了网页而非数据（可能被拦截）")
                    }
                    val obj = json.parseToJsonElement(body).jsonObject
                    val tag = obj["tag_name"]?.jsonPrimitive?.content.orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                    val publishedAt = obj["published_at"]?.jsonPrimitive?.content.orEmpty()
                    val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()
                    val html = obj["html_url"]?.jsonPrimitive?.content.orEmpty()
                    if (tag.isBlank()) error("未获取到版本号")

                    val remoteVersion = tag.removePrefix("v").removePrefix("V").trim()
                    val isLatest = !isNewer(remoteVersion, currentVersionName)
                    LogBuffer.i("Update", "检查更新成功: 远端=$remoteVersion 本机=$currentVersionName 镜像=$viaMirror")
                    return Result.Success(
                        ReleaseInfo(
                            tagName = tag,
                            version = remoteVersion,
                            name = name.ifBlank { tag },
                            publishedAt = publishedAt.take(10),
                            body = notes,
                            htmlUrl = html,
                            isLatest = isLatest,
                            viaMirror = viaMirror,
                        )
                    )
                }
            }.onFailure {
                lastError = it.message
                LogBuffer.w("Update", "检查更新失败（$url）: ${it.message}")
            }
        }
        return Result.Failure(
            lastError ?: "无法连接 GitHub，请检查网络或稍后重试"
        )
    }

    /** 语义化版本比较：remote 是否比 current 新 */
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String): List<Int> =
            v.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val a = parse(remote)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
