package com.lawsearch.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 法规 JSON 的数据模型。
 *
 * 与 Windows 版的单部法规 JSON 完全对应（规格书第二节），
 * 也用于把内置数据（assets/laws 下的 json 或 gz）与爬虫产物统一读写。
 */
@Serializable
data class LawJson(
    val bbbs: String = "",
    val title: String = "",
    val gbrq: String = "",
    val sxrq: String = "",
    val sxx: Int = 0,
    val validity: String = "",
    val zdjgName: String = "",
    val flxz: String = "",
    val group: String = "",
    val subgroup: String = "",
    val articleCount: Int = 0,
    val articleRange: List<Int> = emptyList(),
    val fetchedAt: String = "",
    val meta: LawMetaJson = LawMetaJson(),
    val toc: List<TocEntry> = emptyList(),
    val blocks: List<Block> = emptyList(),
)

@Serializable
data class LawMetaJson(
    val title: String = "",
    val subtitle: String = "",
    val bodyFont: String? = null,
    val fontStats: Map<String, Int> = emptyMap(),
)

@Serializable
data class TocEntry(
    val type: String = "",
    val label: String = "",
    val id: String = "",
)

@Serializable
data class Block(
    val type: String = "",
    val label: String = "",
    val id: String = "",
    val no: String = "",
    val noNum: Int? = null,
    val noFont: String? = null,
    val paragraphs: List<String> = emptyList(),
    val paraFonts: List<String?> = emptyList(),
    val text: String = "",
    /**
     * 条文正文。
     *
     * ⚠️ 这不是多余字段：内置数据里 article 块的正文放在 `content` 里，
     * 而 `text` 只有「第一条」这种标题。之前没声明这个字段，
     * 加上 `Json { ignoreUnknownKeys = true }`，正文被**静默丢弃** ——
     * 表现为「点进法规看到一条条标题、下面完全没有正文」。
     *
     * 影响面：全量 1876 部法规里约一半（935 部 / 28819 条）用的是 content 而非 paragraphs。
     */
    val content: String = "",
    val font: String? = null,
) {
    /** 章节类节点（编/分编/章/节），可折叠 */
    val isHeading: Boolean
        get() = rank in 0..3

    val isArticle: Boolean
        get() = type == TYPE_ARTICLE

    /** 层级：编 0，分编 1，章 2，节 3，条 4（用于建树和折叠） */
    val rank: Int
        get() = when (type) {
            TYPE_PART -> 0
            TYPE_SUBPART -> 1
            TYPE_CHAPTER -> 2
            TYPE_SECTION -> 3
            TYPE_ARTICLE -> 4
            else -> 5
        }

    /**
     * 条文正文。
     *
     * 内置数据实际存在**三种** schema，回退顺序必须是
     * `paragraphs → content → text`：
     *   1. `paragraphs` —— 民法典等，按段切好的数组
     *   2. `content` —— 法院规则 / 司法解释，整段正文
     *   3. `text` —— 刑法修正案各篇、普通段落，正文就在 text
     *
     * ⚠️ `content` 必须排在 `text` **之前**：article 块的 `text` 往往只有
     * 「第一条」这种条号标题，先取 text 会得到一条只有一个词的正文。
     */
    fun bodyParagraphs(): List<String> = when {
        paragraphs.isNotEmpty() -> paragraphs
        content.isNotEmpty() -> content.split('\n').filter { it.isNotBlank() }
            .ifEmpty { listOf(content) }
        text.isNotEmpty() -> text.split('\n')
        else -> emptyList()
    }

    companion object {
        const val TYPE_PART = "part"
        const val TYPE_SUBPART = "subpart"
        const val TYPE_CHAPTER = "chapter"
        const val TYPE_SECTION = "section"
        const val TYPE_ARTICLE = "article"
        const val TYPE_TEXT = "text"
    }
}

/** 索引文件 meta.json（文件名以下划线开头，与法规文件区分） */
@Serializable
data class MetaFile(
    val laws: Map<String, MetaLaw> = emptyMap(),
    val updatedAt: String = "",
)

@Serializable
data class MetaLaw(
    val title: String = "",
    val gbrq: String = "",
    val group: String = "",
    val subgroup: String = "",
    val articleCount: Int = 0,
    val savedAt: String = "",
)

object LawJsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    fun decode(text: String): LawJson = json.decodeFromString(text)

    fun encode(law: LawJson): String = json.encodeToString(LawJson.serializer(), law)

    fun decodeMeta(text: String): MetaFile = json.decodeFromString(text)
}
