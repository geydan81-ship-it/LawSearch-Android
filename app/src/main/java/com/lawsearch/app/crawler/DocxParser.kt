package com.lawsearch.app.crawler

import com.lawsearch.app.data.Block
import com.lawsearch.app.data.LawJson
import com.lawsearch.app.data.LawMetaJson
import com.lawsearch.app.data.TocEntry
import com.lawsearch.app.util.ChineseNumber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 把国家法律法规数据库下载的 `.docx` 解析成阅读器可用的结构化 JSON。
 *
 * Windows 版 `law_parse.py` 的 Kotlin 移植（规格书第四节方案 B：纯 XmlPullParser，
 * 不引 POI）。`.docx` 本质是 zip，正文在 `word/document.xml`。
 *
 * 与 Python 版一一对应的步骤：
 *  1. 提取每个 <w:p> 的文本 + 中文字体（按字符数最多的 run 判断；显式字体覆盖不足一半时
 *     回退到 Normal 样式——公文体例里「第二十二条」是小号黑体 run，正文 run 不带字体）
 *  2. 第一段非空 = 标题；第二段以「（」开头 = 通过日期（subtitle）
 *  3. 跳过「目 录」区
 *  4. 逐段分类：part / subpart / chapter / section / article / text
 *  5. 生成 id：章 c1/c2…，节 s1/s2…，条 a1/a2…
 *  6. 统计正文字体（按字符数加权，排除章节标题与条号本身）
 */
object DocxParser {

    /* ---------------- 正则 ---------------- */

    private const val NUM = "一二三四五六七八九十百千零〇两\\d"
    private val RE_PART = Regex("^第([$NUM]+)(编|分编)[\\s\\u3000]*(.*)$")
    private val RE_CHAPTER = Regex("^第([$NUM]+)章[\\s\\u3000]*(.*)$")
    private val RE_SECTION = Regex("^第([$NUM]+)节[\\s\\u3000]*(.*)$")
    private val RE_ARTICLE = Regex("^第([$NUM]+)条[\\s\\u3000]*(.*)$")
    private val RE_TOC_HEAD = Regex("^目\\s*录$")
    private val RE_SUBTITLE = Regex("^[（(]")
    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** 解析结果 */
    data class Result(
        val law: LawJson,
        val warnings: List<String> = emptyList(),
    )

    /** 爬虫行数据提供的元信息，覆盖/补齐 docx 里没有的字段 */
    data class LawOverrides(
        val bbbs: String = "",
        val title: String = "",
        val gbrq: String = "",
        val sxrq: String = "",
        val sxx: Int = 0,
        val zdjgName: String = "",
        val flxz: String = "",
        val group: String = "",
        val subgroup: String = "",
    )

    /* ---------------- 内部：段落模型 ---------------- */

    private class Run(val text: CharSequence, val font: String?)

    private class Para(val text: String, val runs: List<Run>)

    private class Docx(val paras: List<Para>, val normalFont: String?)

    /* ---------------- 公共入口 ---------------- */

    fun parse(bytes: ByteArray, overrides: LawOverrides = LawOverrides()): LawJson =
        parseWithWarnings(bytes, overrides).law

    fun parse(stream: InputStream, overrides: LawOverrides = LawOverrides()): LawJson =
        parse(stream.readBytes(), overrides)

    fun parseWithWarnings(bytes: ByteArray, overrides: LawOverrides = LawOverrides()): Result {
        val docx = extract(bytes)
        return build(docx, overrides)
    }

    /* ---------------- 1. 解压 + 读 XML ---------------- */

    private fun extract(bytes: ByteArray): Docx {
        var documentXml: ByteArray? = null
        var stylesXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "word/document.xml" -> documentXml = zip.readAllBytesCompat()
                    "word/styles.xml" -> stylesXml = zip.readAllBytesCompat()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val doc = documentXml ?: throw IllegalStateException("不是有效的 docx：缺少 word/document.xml")
        val normalFont = stylesXml?.let { parseNormalStyleFont(it) }
        return Docx(parseParagraphs(doc), normalFont)
    }

    private fun ZipInputStream.readAllBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream(128 * 1024)
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = this.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * docx 的 document.xml / styles.xml → W3C DOM。
     *
     * 用 JDK/Android 都自带的 org.w3c.dom + javax.xml.parsers，不依赖 XmlPullParser 的
     * ServiceLoader 注册（在单元测试等环境里 XmlPullParserFactory 可能返回 null），
     * 行为在设备和 JVM 上一致。docx 的单部文件只有几 MB，DOM 内存开销可以接受。
     */
    private fun parseDom(xml: ByteArray): org.w3c.dom.Document {
        val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        dbf.isExpandEntityReferences = false
        // 关闭外部实体：docx 来自网络，避免 XXE
        runCatching { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        dbf.isXIncludeAware = false
        return dbf.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    /** 取 w:* 属性（按命名空间取，取不到再按本地名兜底） */
    private fun attr(el: org.w3c.dom.Element, localName: String): String? {
        val v = el.getAttributeNS(NS_W, localName)
        if (v.isNotEmpty()) return v
        val v2 = el.getAttribute("w:$localName")
        return v2.ifEmpty { null }
    }

    private fun styleId(el: org.w3c.dom.Element): String? = attr(el, "styleId")

    /** styles.xml 里 Normal 段落样式的中文字体 */
    private fun parseNormalStyleFont(xml: ByteArray): String? {
        val doc = parseDom(xml)
        val styles = doc.getElementsByTagNameNS(NS_W, "style")
        for (i in 0 until styles.length) {
            val style = styles.item(i) as? org.w3c.dom.Element ?: continue
            val type = attr(style, "type") ?: "paragraph"
            val id = styleId(style) ?: ""
            if (type == "paragraph" && id.equals("Normal", ignoreCase = true)) {
                val fonts = style.getElementsByTagNameNS(NS_W, "rFonts")
                for (j in 0 until fonts.length) {
                    val ea = attr(fonts.item(j) as org.w3c.dom.Element, "eastAsia")
                    if (!ea.isNullOrBlank()) return ea.trim()
                }
            }
        }
        return null
    }

    /* ---------------- 2. 段落 + run 字体 ---------------- */

    /**
     * 解析正文段落。每个 <w:p> 产出一个 [Para]：
     * 文本 = 所有 <w:t> 拼接；runs = 每个 <w:r> 的文字与它的显式 eastAsia 字体。
     *
     * 注意：python-docx 的 `r.text` 只含普通文字（tab/br 不计），
     * 这里保持一致，段落内真正的换行段落由 <w:p> 本身体现。
     */
    private fun parseParagraphs(xml: ByteArray): List<Para> {
        val doc = parseDom(xml)
        val paragraphs = doc.getElementsByTagNameNS(NS_W, "p")
        val out = ArrayList<Para>(paragraphs.length)
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? org.w3c.dom.Element ?: continue
            val runs = ArrayList<Run>()
            val paraText = StringBuilder()
            val texts = p.getElementsByTagNameNS(NS_W, "t")
            for (j in 0 until texts.length) {
                val t = texts.item(j) as? org.w3c.dom.Element ?: continue
                val run = runAncestorOf(t) ?: continue
                val runText = t.textContent ?: continue
                paraText.append(runText)
                val font = runFont(run)
                val existing = runs.indexOfFirst { it.font == font }
                if (existing >= 0) {
                    (runs[existing].text as StringBuilder).append(runText)
                } else {
                    runs.add(Run(StringBuilder(runText), font))
                }
            }
            out.add(Para(paraText.toString(), runs.map { Run(it.text.toString(), it.font) }))
        }
        return out
    }

    /** 向上找到所属的 w:r */
    private fun runAncestorOf(el: org.w3c.dom.Element): org.w3c.dom.Element? {
        var p: org.w3c.dom.Node? = el.parentNode
        while (p != null) {
            if (p is org.w3c.dom.Element && p.namespaceURI == NS_W && p.localName == "r") {
                return p
            }
            p = p.parentNode
        }
        return null
    }

    /** run 的显式中文字体（w:rPr/w:rFonts/@w:eastAsia） */
    private fun runFont(run: org.w3c.dom.Element): String? {
        val fonts = run.getElementsByTagNameNS(NS_W, "rFonts")
        for (j in 0 until fonts.length) {
            val el = fonts.item(j) as? org.w3c.dom.Element ?: continue
            // 只认 run 自己的 rPr 里的 rFonts
            val parent = el.parentNode
            if (parent is org.w3c.dom.Element && parent.localName == "rPr") {
                val ea = attr(el, "eastAsia")
                if (!ea.isNullOrBlank()) return ea.trim()
            }
        }
        return null
    }

    /* ---------------- 字体判定（等价于 law_parse.py 的 eastasia_font / explicit_font） ---------------- */

    /**
     * 段落的中文字体：按「字符数最多的显式字体」判断，
     * 显式字体覆盖了段落文字的 ≥50% 才采用，否则回退 Normal 样式，
     * 再不行就用出现最多的显式字体。
     */
    private fun eastAsiaFont(p: Para, normalFont: String?): String? {
        val counter = HashMap<String, Int>()
        for (r in p.runs) {
            val f = r.font
            val t = r.text.trim()
            if (f != null && t.isNotEmpty()) counter[f] = (counter[f] ?: 0) + t.length
        }
        val total = counter.values.sum()
        val textLen = p.text.trim().length
        if (counter.isNotEmpty()) {
            val best = counter.maxByOrNull { it.value }!!
            if (textLen == 0 || best.value.toDouble() / maxOf(1, textLen) >= 0.5) return best.key
        }
        normalFont?.let { return it }
        return counter.maxByOrNull { it.value }?.key
    }

    /** 只看显式字体（不回退），用于区分「条号黑了」和「正文继承默认」 */
    private fun explicitFont(p: Para): String? = p.runs.firstOrNull { it.font != null }?.font

    /* ---------------- 3~6. 结构化 ---------------- */

    private fun clean(s: String): String =
        s.replace('\u3000', ' ').replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .trim()

    private fun isHeadingText(t: String): Boolean =
        RE_PART.containsMatchIn(t) || RE_CHAPTER.containsMatchIn(t) || RE_SECTION.containsMatchIn(t)

    private fun isArticleText(t: String): Boolean = RE_ARTICLE.containsMatchIn(t)

    private fun build(docx: Docx, ov: LawOverrides): Result {
        val warnings = ArrayList<String>()
        val paras = docx.paras.map { clean(it.text) }
        val fonts = docx.paras.map { eastAsiaFont(it, docx.normalFont) }
        val explicitFonts = docx.paras.map { explicitFont(it) }

        // ---- 1. 标题与通过日期 ----
        var title = ""
        var subtitle = ""
        val nonEmpty = paras.indices.filter { paras[it].isNotEmpty() }
        var bodyStart = 0
        if (nonEmpty.isNotEmpty()) {
            title = paras[nonEmpty[0]]
            if (nonEmpty.size > 1 && RE_SUBTITLE.containsMatchIn(paras[nonEmpty[1]])) {
                subtitle = paras[nonEmpty[1]]
                bodyStart = nonEmpty[1] + 1
            } else {
                bodyStart = nonEmpty[0] + 1
            }
        }

        // ---- 2. 跳过目录区 ----
        var skipUntil = bodyStart
        val headWindow = (bodyStart until minOf(paras.size, bodyStart + 60)).map { paras[it] }
        val hasTocHead = headWindow.any { RE_TOC_HEAD.containsMatchIn(it) }
        if (hasTocHead) {
            val firstArticle = (bodyStart until paras.size).firstOrNull { isArticleText(paras[it]) }
            if (firstArticle != null) {
                var lastHeadingBefore: Int? = null
                var j = firstArticle - 1
                while (j > bodyStart) {
                    if (isHeadingText(paras[j])) {
                        lastHeadingBefore = j
                        break
                    }
                    j--
                }
                skipUntil = lastHeadingBefore ?: firstArticle
            }
        } else {
            // 没有「目 录」标记：连续 >6 行标题且无条文 → 判定为目录
            var runStart: Int? = null
            var runCount = 0
            var j = bodyStart
            while (j < paras.size) {
                val t = paras[j]
                if (t.isEmpty()) {
                    j++
                    continue
                }
                if (isHeadingText(t)) {
                    if (runStart == null) runStart = j
                    runCount++
                } else {
                    break
                }
                j++
            }
            if (runCount >= 6 && runStart != null) {
                val firstArticle = (runStart until paras.size).firstOrNull { isArticleText(paras[it]) }
                if (firstArticle != null) {
                    var lastH: Int? = null
                    var k = firstArticle - 1
                    while (k >= runStart) {
                        if (isHeadingText(paras[k])) {
                            lastH = k
                            break
                        }
                        k--
                    }
                    skipUntil = lastH ?: firstArticle
                }
            }
        }

        // ---- 3. 逐段分类 ----
        data class MutableArticle(
            val type: String,
            val no: String,
            val noNum: Int?,
            val noFont: String,
            val paragraphs: MutableList<String>,
            val paraFonts: MutableList<String?>,
        )

        val merged = ArrayList<Any>()  // MutableArticle 或 Block
        var buf: MutableArticle? = null
        var i = skipUntil
        while (i < paras.size) {
            val c = paras[i]
            if (c.isEmpty()) {
                i++
                continue
            }
            val mArt = RE_ARTICLE.find(c)
            val mPart = RE_PART.find(c)
            val mCh = RE_CHAPTER.find(c)
            val mSec = RE_SECTION.find(c)
            when {
                mArt != null -> {
                    buf?.let { merged.add(it) }
                    val first = mArt.groupValues[2].trim()
                    buf = MutableArticle(
                        type = Block.TYPE_ARTICLE,
                        no = "第" + mArt.groupValues[1] + "条",
                        noNum = ChineseNumber.cnToInt(mArt.groupValues[1]),
                        noFont = explicitFonts[i] ?: "黑体",
                        paragraphs = if (first.isNotEmpty()) mutableListOf(first) else mutableListOf(),
                        paraFonts = if (first.isNotEmpty()) mutableListOf(fonts[i]) else mutableListOf(),
                    )
                }
                mPart != null || mCh != null || mSec != null -> {
                    buf?.let { merged.add(it) }
                    buf = null
                    val block = when {
                        mPart != null -> Block(
                            type = if (mPart.groupValues[2] == "分编") Block.TYPE_SUBPART else Block.TYPE_PART,
                            label = c,
                            font = fonts[i],
                        )
                        mCh != null -> Block(type = Block.TYPE_CHAPTER, label = c, font = fonts[i])
                        else -> Block(type = Block.TYPE_SECTION, label = c, font = fonts[i])
                    }
                    merged.add(block)
                }
                buf != null -> {
                    buf.paragraphs.add(c)
                    buf.paraFonts.add(fonts[i])
                }
                else -> merged.add(Block(type = Block.TYPE_TEXT, text = c, font = fonts[i]))
            }
            i++
        }
        buf?.let { merged.add(it) }

        // ---- 4. 生成 id + toc ----
        val counters = mutableMapOf(
            Block.TYPE_PART to 0, Block.TYPE_SUBPART to 0,
            Block.TYPE_CHAPTER to 0, Block.TYPE_SECTION to 0, Block.TYPE_ARTICLE to 0,
        )
        val blocks = ArrayList<Block>(merged.size)
        val toc = ArrayList<TocEntry>()
        val articleNums = ArrayList<Int>()

        for (item in merged) {
            when (item) {
                is MutableArticle -> {
                    counters[Block.TYPE_ARTICLE] = counters.getValue(Block.TYPE_ARTICLE) + 1
                    val id = "a" + counters.getValue(Block.TYPE_ARTICLE)
                    articleNums.add(item.noNum ?: -1)
                    blocks.add(
                        Block(
                            type = Block.TYPE_ARTICLE,
                            id = id,
                            no = item.no,
                            noNum = item.noNum,
                            noFont = item.noFont,
                            paragraphs = item.paragraphs.toList(),
                            paraFonts = item.paraFonts.toList(),
                            text = item.paragraphs.joinToString("\n"),
                        )
                    )
                }
                is Block -> {
                    // 普通段落块（text）不计数、不带标题 id
                    if (!item.isHeading) {
                        blocks.add(item.copy(id = "", font = item.font))
                        continue
                    }
                    val prefix = when (item.type) {
                        Block.TYPE_PART -> "p"
                        Block.TYPE_SUBPART -> "sp"
                        Block.TYPE_CHAPTER -> "c"
                        Block.TYPE_SECTION -> "s"
                        else -> ""
                    }
                    val withId = item.copy(id = prefix + (counters[item.type]!! + 1))
                    counters[item.type] = counters.getValue(item.type) + 1
                    blocks.add(withId)
                    toc.add(TocEntry(withId.type, withId.label, withId.id))
                }
            }
        }

        // 去掉开头的孤立短说明文字（「目 录」残留、页眉号等）
        while (blocks.isNotEmpty() && blocks[0].type == Block.TYPE_TEXT && blocks[0].text.length < 10) {
            blocks.removeAt(0)
        }

        // ---- 5. 正文字体统计 ----
        val fontCounter = LinkedHashMap<String, Int>()
        for (idx in paras.indices) {
            val t = paras[idx]
            if (t.isEmpty()) continue
            if (isHeadingText(t)) continue
            val f = fonts[idx] ?: continue
            val m = RE_ARTICLE.find(t)
            val weight = if (m != null) m.groupValues[2].trim().length else t.length
            if (weight > 0) fontCounter[f] = (fontCounter[f] ?: 0) + weight
        }
        val bodyFont = fontCounter.maxByOrNull { it.value }?.key

        val articleCount = counters.getValue(Block.TYPE_ARTICLE)
        val validNums = articleNums.filter { it > 0 }
        val resolvedTitle = ov.title.ifBlank { title }
        if (articleCount == 0) warnings.add("未解析出任何条文")

        val law = LawJson(
            bbbs = ov.bbbs,
            title = resolvedTitle,
            gbrq = ov.gbrq,
            sxrq = ov.sxrq,
            sxx = ov.sxx,
            validity = "",
            zdjgName = ov.zdjgName,
            flxz = ov.flxz,
            group = ov.group,
            subgroup = ov.subgroup,
            articleCount = articleCount,
            articleRange = if (validNums.isEmpty()) listOf(0, 0) else listOf(validNums.min(), validNums.max()),
            fetchedAt = "",
            meta = LawMetaJson(
                title = resolvedTitle,
                subtitle = subtitle,
                bodyFont = bodyFont,
                fontStats = fontCounter,
            ),
            toc = toc,
            blocks = blocks,
        )
        return Result(law, warnings)
    }
}
