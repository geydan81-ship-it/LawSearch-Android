#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
法规数据质量审计（P1）。

扫描 assets/laws/*.json.gz（打包后的真实数据，1874 部 + _meta + _index），
输出可直接贴进报告的结构化统计。

用法：
    python tools/audit_laws.py --out <报告.md>

设计要点（踩过的坑）：
  * **判据必须先自证**。历史上出过两次「判据写错 → 得到漂亮的 0」的事故
    （见 SearchQualityTest 的类注释）。所以每个判据下面都跑一次 sanity check。
  * 正文长度判据**不能**用 `block.text == block.no`——article 块的 text 里是正文，
    而内联数据三种 schema 的正文位置不同（paragraphs / content / text）。
    统一走 `body()` 三段回退，与 Kotlin 侧 `Block.bodyParagraphs()` 一一对应。
  * 数据以 assets（打包数据）为准；../laws 是爬虫工作目录，可能被清洗脚本改过，
    不一定是用户看到的那一份。
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8")

# ---------------------------------------------------------------- 基础工具

# 与 Kotlin Block.bodyParagraphs() 完全一致的回退顺序
def body(block: dict) -> str:
    paras = block.get("paragraphs") or []
    if paras:
        return "\n".join(paras)
    content = block.get("content") or ""
    if content:
        return content
    text = block.get("text") or ""
    return text


def is_article(b: dict) -> bool:
    return b.get("type") == "article"


CJK = re.compile(r"[\u4e00-\u9fff]")


def han_len(s: str) -> int:
    return len(CJK.findall(s or ""))


# ---------------------------------------------------------------- 加载

class Corpus:
    def __init__(self, assets_dir: str):
        self.dir = assets_dir
        self.meta = json.loads(
            gzip.open(os.path.join(assets_dir, "_meta.json.gz"), "rt", encoding="utf-8").read()
        )
        self.ids = sorted(self.meta["laws"].keys())
        self._cache: dict[str, dict] = {}

    def law(self, bbbs: str) -> dict | None:
        if bbbs in self._cache:
            return self._cache[bbbs]
        path = os.path.join(self.dir, bbbs + ".json.gz")
        try:
            j = json.loads(gzip.open(path, "rt", encoding="utf-8").read())
        except FileNotFoundError:
            j = None
        self._cache[bbbs] = j
        return j


# ---------------------------------------------------------------- 审计项

def sanity_check() -> list[str]:
    """判据自证：用构造样例确认每个判据在当前代码下会给出预期答案。"""
    out = []

    # 1) body() 三段回退
    a = body({"paragraphs": ["甲"], "content": "乙", "text": "丙"})
    b_ = body({"content": "乙", "text": "丙"})
    c = body({"text": "丙"})
    ok = a == "甲" and b_ == "乙" and c == "丙"
    out.append(f"body() 三段回退 paragraphs→content→text：{'OK' if ok else 'FAIL'} ({a}/{b_}/{c})")

    # 2) han_len
    ok2 = han_len("第一条abc") == 3
    out.append(f"han_len 只数汉字：{'OK' if ok2 else 'FAIL'}")

    # 3) is_article
    ok3 = is_article({"type": "article"}) and not is_article({"type": "chapter"})
    out.append(f"is_article 判别：{'OK' if ok3 else 'FAIL'}")
    return out


def audit(corpus: Corpus) -> dict:
    r: dict = {}
    per_law = []
    block_types = Counter()
    empty_body_laws = []           # 有 article 块但正文全空
    no_article_laws = []           # 没有任何 article 块
    article_count_mismatch = []    # 声明 articleCount 与实际 article 块数不符
    empty_article_laws = []        # articleCount>0 但正文 0
    headings_without_articles = []
    title_dup = defaultdict(list)
    no_laws = defaultdict(list)    # 有 block 但一个汉字都没有
    bare_no_laws = []              # no 是「一」「二」而非「第一条」
    canonical_no_laws = 0
    duplicate_pairs = []           # 同名法规的元数据对比
    orphan_sections = []           # section 出现在任何 chapter 之前
    text_prefixed = []             # text 块以 '##' 开头（markdown 残留）

    for bbbs in corpus.ids:
        j = corpus.law(bbbs)
        if j is None:
            empty_body_laws.append((bbbs, "(file missing)", 0, 0))
            continue
        title = (j.get("title") or "").strip()
        blocks = j.get("blocks") or []
        title_dup[title].append(bbbs)

        arts = [b for b in blocks if is_article(b)]
        types = Counter(b.get("type") or "(none)" for b in blocks)
        block_types.update(types)

        bodies = [body(b) for b in arts]
        han = sum(han_len(x) for x in bodies)
        nonempty = sum(1 for x in bodies if han_len(x) > 0)
        # 整部法规（含 text 块）的汉字数 —— 判断「真空白」还是「只是没有 article 块」
        total_han = sum(han_len(body(b)) for b in blocks)

        declared = j.get("articleCount") or 0
        meta_ac = corpus.meta["laws"].get(bbbs, {}).get("articleCount") or 0

        for b in blocks:
            t = b.get("type")
            if t == "text" and (b.get("text") or "").startswith("##"):
                text_prefixed.append((title, b.get("id"), (b.get("text") or "")[:20]))
            if t == "section":
                # 记录 section 出现在 chapter 之前的情况（可能在循环外判定，见下）
                pass

        # section 早于首个 chapter
        seen_chapter = False
        for b in blocks:
            if b.get("type") == "chapter":
                seen_chapter = True
            elif b.get("type") == "section" and not seen_chapter:
                orphan_sections.append((title, b.get("label") or b.get("text") or ""))
                break

        nos = [b.get("no") or "" for b in arts]
        filled = [n for n in nos if n]
        if filled:
            if all(n.startswith("第") for n in filled):
                canonical_no_laws += 1
            elif any(not n.startswith("第") for n in filled):
                bare_no_laws.append((title, filled[:3]))
        elif arts:
            bare_no_laws.append((title, ["(no 全空)"]))

        if not arts:
            no_article_laws.append((title, len(blocks), han))
            if total_han == 0:
                no_laws.append((title, bbbs, len(blocks)))
            if types.get("chapter") or types.get("section"):
                headings_without_articles.append((title, types.get("chapter", 0), types.get("section", 0)))
        else:
            if han == 0:
                empty_body_laws.append((bbbs, title, len(arts), han))
            elif nonempty == 0:
                empty_body_laws.append((bbbs, title, len(arts), han))

        if declared and declared != len(arts):
            article_count_mismatch.append((title, declared, len(arts)))
        if declared > 0 and han == 0:
            empty_article_laws.append((title, declared, len(arts)))

        per_law.append(dict(bbbs=bbbs, title=title, group=j.get("group") or "",
                            blocks=len(blocks), arts=len(arts), han=han,
                            total_han=total_han,
                            declared=declared, meta_ac=meta_ac,
                            gbrq=j.get("gbrq") or "", sxrq=j.get("sxrq") or "",
                            sxx=j.get("sxx") or 0))

    r["laws"] = len(corpus.ids)
    r["total_blocks"] = sum(p["blocks"] for p in per_law)
    r["total_articles"] = sum(p["arts"] for p in per_law)
    r["total_han"] = sum(p["han"] for p in per_law)
    r["total_han_all"] = sum(p["total_han"] for p in per_law)
    r["block_types"] = block_types
    r["empty_body_laws"] = empty_body_laws
    r["no_article_laws"] = no_article_laws
    r["no_laws"] = no_laws
    r["headings_without_articles"] = headings_without_articles
    r["article_count_mismatch"] = article_count_mismatch
    r["empty_article_laws"] = empty_article_laws
    r["dups"] = {t: v for t, v in title_dup.items() if len(v) > 1}
    by_id = {p["bbbs"]: p for p in per_law}
    for t, ids in r["dups"].items():
        rows = []
        for i in ids:
            p = by_id.get(i)
            if p is None:
                continue
            rows.append((i, p["gbrq"], p["sxrq"], p["sxx"], p["arts"], p["han"]))
        # 是否「正文完全相同」（完全重复）还是「不同版本」
        bodies = []
        for i in ids:
            j = corpus.law(i)
            bodies.append("".join(body(b) for b in (j or {}).get("blocks") or []))
        identical = len(set(bodies)) == 1 and len(bodies) > 1
        duplicate_pairs.append((t, rows, identical))
    r["bare_no_laws"] = bare_no_laws
    r["canonical_no_laws"] = canonical_no_laws
    r["duplicate_pairs"] = duplicate_pairs
    r["orphan_sections"] = orphan_sections
    r["text_prefixed"] = text_prefixed
    r["per_law"] = per_law
    return r


# ---------------------------------------------------------------- 报告

def write_report(r: dict, out: str, sanity: list[str]) -> None:
    L: list[str] = []
    a = L.append

    a("# 法规数据质量报告")
    a("")
    a(f"- 数据源：`app/src/main/assets/laws/*.json.gz`（打包进 APK 的那一份）")
    a(f"- 法规总数：**{r['laws']}**")
    a(f"- block 总数：**{r['total_blocks']:,}**")
    a(f"- article 块总数：**{r['total_articles']:,}**")
    a(f"- 正文汉字总数（仅 article 块）：**{r['total_han']:,}**")
    a(f"- 汉字总数（含 text 块）：**{r['total_han_all']:,}**")
    a("")
    a("## 0. 判据自证（先证明尺子是准的）")
    a("")
    for s in sanity:
        a(f"- {s}")
    a("")

    # ---- 1. block 类型分布
    a("## 1. block 类型分布")
    a("")
    a("| type | 数量 | 占比 |")
    a("|---|---:|---:|")
    tot = r["total_blocks"] or 1
    for t, n in r["block_types"].most_common():
        a(f"| `{t}` | {n:,} | {n / tot * 100:.2f}% |")
    a("")
    expected = {"part", "subpart", "chapter", "section", "article", "text"}
    unknown = {t: n for t, n in r["block_types"].items() if t not in expected}
    if unknown:
        a("⚠️ **出现未在 `Block` 常量里声明的 type**（`isHeading`/`rank` 会把它们当普通段落）：")
        a("")
        for t, n in sorted(unknown.items(), key=lambda x: -x[1]):
            a(f"- `{t}`：{n:,} 个")
    else:
        a("✅ 所有 block 的 `type` 都在 `Block` 已知常量内（part/subpart/chapter/section/article/text）。")
    a("")

    # ---- 2. 正文为空
    a("## 2. 正文为空的法规")
    a("")
    ebl = r["empty_body_laws"]
    a(f"**{len(ebl)}** 部法规有 article 块但全部正文为空（汉字总数 = 0）。")
    a("")
    if ebl:
        a("| bbbs | 标题 | article 块数 | 汉字数 |")
        a("|---|---|---:|---:|")
        for row in ebl[:60]:
            a(f"| `{row[0]}` | {row[1]} | {row[2]} | {row[3]} |")
        if len(ebl) > 60:
            a(f"| … | 另有 {len(ebl) - 60} 部 | | |")
    a("")

    eal = r["empty_article_laws"]
    a(f"其中 **{len(eal)}** 部声明了 `articleCount > 0`（元数据说有条文，正文却是空的）。")
    a("")
    a("> 判据说明：`empty_body_laws` 只统计**有 article 块**的法规。"
      "一个 article 块都没有、但正文在 `text` 块里的法规（会议决定、刑诉法解释、通知类）"
      "见下面第 4 节，它们**不是**正文为空。")
    a("")

    nl = r["no_laws"]
    a(f"### 2.1 真正一个汉字都没有的法规：**{len(nl)}** 部")
    a("")
    if nl:
        a("| 标题 | bbbs | block 数 |")
        a("|---|---|---:|")
        for t, i, nb in nl[:40]:
            a(f"| {t} | `{i}` | {nb} |")
        if len(nl) > 40:
            a(f"| … | 另有 {len(nl) - 40} 部 | |")
        a("")
        a("这些法规打开后是**完全空白**的，搜索也只能命中标题。")
    else:
        a("✅ 无：所有有 block 的法规都至少有 1 个汉字。")
    a("")

    # ---- 3. articleCount = 0 但有正文（刑法修正案类）
    a("## 3. `articleCount` 与实际不符")
    a("")
    n0 = [p for p in r["per_law"] if p["declared"] == 0 and p["arts"] > 0]
    a(f"### 3.1 声明 `articleCount = 0` 但实际有 article 块：**{len(n0)}** 部")
    a("")
    if n0:
        a("| 标题 | 声明 | 实际 article 块 | 汉字数 |")
        a("|---|---:|---:|---:|")
        for p in sorted(n0, key=lambda x: -x["arts"])[:40]:
            a(f"| {p['title']} | {p['declared']} | {p['arts']} | {p['han']:,} |")
        if len(n0) > 40:
            a(f"| … | 另有 {len(n0) - 40} 部 | | |")
    a("")

    nac = [p for p in r["per_law"] if p["declared"] > 0 and p["declared"] != p["arts"]]
    a(f"### 3.2 声明数与实际 article 块数不符：**{len(nac)}** 部")
    a("")
    if nac:
        a("| 标题 | 声明 articleCount | 实际块数 | 差值 |")
        a("|---|---:|---:|---:|")
        for p in sorted(nac, key=lambda x: -abs(x["declared"] - x["arts"]))[:40]:
            a(f"| {p['title']} | {p['declared']} | {p['arts']} | {p['arts'] - p['declared']:+d} |")
        if len(nac) > 40:
            a(f"| … | 另有 {len(nac) - 40} 部 | | |")
    a("")

    # ---- 4. 无 article 块的法规
    a("## 4. 完全没有 article 块的法规")
    a("")
    nal = r["no_article_laws"]
    a(f"**{len(nal)}** 部（这些法规在搜索里只能靠标题命中，条文级检索永远搜不到）。")
    a("")
    a("| 标题 | block 数 | 汉字数 |")
    a("|---|---:|---:|")
    for t, nb, han in sorted(nal, key=lambda x: -x[2])[:40]:
        a(f"| {t} | {nb} | {han:,} |")
    if len(nal) > 40:
        a(f"| … | 另有 {len(nal) - 40} 部 | |")
    a("")

    # ---- 5. 重复标题
    a("## 5. 重复标题")
    a("")
    dups = r["dups"]
    a(f"**{len(dups)}** 组标题重复（涉及 {sum(len(v) for v in dups.values())} 部法规）。")
    a("")
    if dups:
        a("### 5.1 列表")
        a("")
        a("| 标题 | 数量 | bbbs |")
        a("|---|---:|---|")
        for t, ids in sorted(dups.items(), key=lambda x: -len(x[1])):
            a(f"| {t} | {len(ids)} | {'<br>'.join('`' + i + '`' for i in ids)} |")
        a("")
        a("### 5.2 同名法规的元数据对比（判断是「完全重复」还是「新旧两个版本」）")
        a("")
        for t, rows, identical in r["duplicate_pairs"]:
            a(f"**{t}** —— 正文{'完全相同（完全重复）' if identical else '不同（是两个版本）'}")
            a("")
            a("| bbbs | 公布日期 | 施行日期 | sxx | article 块 | 汉字数 |")
            a("|---|---|---|---:|---:|---:|")
            for i, gbrq, sxrq, sxx, arts, han in rows:
                a(f"| `{i}` | {gbrq or '—'} | {sxrq or '—'} | {sxx} | {arts} | {han:,} |")
            a("")
        a("> **影响**：同名法规在列表里会出现两行、搜索里出现两次，用户无法分辨该点哪个。"
          "`sxx=1/3` 表示该条是修正/修订版本。")
        a("")

    # ---- 6. block 类型分布异常
    a("## 6. block 结构异常")
    a("")
    a(f"### 6.1 `no` 字段用的是裸中文数字而非「第X条」：**{len(r['bare_no_laws'])}** 部")
    a("")
    a(f"（对照：规范的「第X条」写法有 **{r['canonical_no_laws']}** 部）")
    a("")
    a("这些法规的 `no` 是 `一` / `二` / `三`，UI 直接显示 `no` 就会渲染成孤零零一个「一」，")
    a("**没有「第…条」的框架**。")
    a("")
    if r["bare_no_laws"]:
        a("| 标题 | no 前三项 |")
        a("|---|---|")
        for t, nos in r["bare_no_laws"][:25]:
            a(f"| {t} | {'、'.join(nos)} |")
        if len(r["bare_no_laws"]) > 25:
            a(f"| … | 另有 {len(r['bare_no_laws']) - 25} 部 |")
    a("")

    a(f"### 6.2 第一个 chapter 之前就出现 section：**{len(r['orphan_sections'])}** 部")
    a("")
    for t, label in r["orphan_sections"][:20]:
        a(f"- {t} —— `{label[:50]}`")
    a("")

    a(f"### 6.3 `text` 块里残留 markdown 标题（`## `）：**{len(r['text_prefixed'])}** 处")
    a("")
    for t, bid, txt in r["text_prefixed"][:20]:
        a(f"- {t} / `{bid}` —— `{txt}`")
    a("")
    if r["text_prefixed"]:
        a("这些会被原样渲染成带 `##` 的文字。")
        a("")

    write_known_defects(a)
    write_applied_fixes(a)

    a("---")
    a("")
    a("_本报告由 `tools/audit_laws.py` 生成。_")

    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")


def write_known_defects(a) -> None:
    """已知数据缺陷 —— 发现了但**刻意没有自动修**的。人工校对时按这张表查。"""
    a("## 7. 已知数据缺陷（未修复）")
    a("")
    a("> 这一节列的是**已经确认存在、但刻意没有自动修复**的问题。")
    a("> 原则：**数据修正宁可不做，也不能猜。** 改错了比不改更糟（用户会看到一份\"看起来对但其实是编的\"法条）。")
    a("> 每一条都附了「为什么不敢自动改」和「人工校对时该看什么」。")
    a("")

    a("### 7.1 标题疑似漏字 —— `2b2b07f3…`")
    a("")
    a("| 项目 | 值 |")
    a("|---|---|")
    a("| bbbs | `2b2b07f30352747b6961b18d91e6b752` |")
    a("| 当前标题 | 最高人民法院**于**适用财产刑若干问题的规定 |")
    a("| 疑似正确值 | 最高人民法院**关于**适用财产刑若干问题的规定 |")
    a("| 依据 | 同一天公布（2000-11-15）、**正文逐字相同**的 `689a0891d27c14e79dc019aba7e54a3b` 标题是「关于」 |")
    a("")
    a("**为什么不敢自动改**：`t` 与 `t''` 的差别只有 1 个字，但这 1 个字既可能是**采集时漏字**，")
    a("也可能是源站本身就是错的。人工校对时请对照 [flk.npc.gov.cn](https://flk.npc.gov.cn) 确认。")
    a("")
    a("**影响**：用户搜「关于适用财产刑」时，`2b2b07f3…` 这份的**标题检索**匹配不上；")
    a("正文检索不受影响（正文里本来就写「关于」）。")
    a("")

    a("### 7.2 宪法修正案（2018年）条号 52 → 123 跳变")
    a("")
    a("| 项目 | 值 |")
    a("|---|---|")
    a("| bbbs | `2c909fdd678bf17901678bf59da8002d` |")
    a("| 现象 | 26 条修正案的 `noNum` 为 `32…52, 123, 124, 125, 126, 127` |")
    a("| 应为 | `32…57`（2018 年共 26 条修正案，全库累计 57 条） |")
    a("")
    a("**成因**：修正案第 52 条原文是「宪法第三章『国家机构』中增加一节，作为第七节『监察委员会』；")
    a("**增加五条，分别作为第一百二十三条至第一百二十七条**」。其后的 5 个块是**新增的宪法条文正文**，")
    a("采集时误用了**宪法条号**（123–127），而不是**修正案条号**（53–57）。")
    a("")
    a("**为什么不敢自动改**：条号是法律文本的组成部分。虽然语义上可以推断 123→53，")
    a("但改错会让「宪法第一百二十三条」这条引用失效。需人工对照官方修正案文本后手工修正。")
    a("")
    a("**影响**：修正案列表里条号从「第五十二条」直接跳到「第一百二十三条」，用户以为缺了 70 条。")
    a("全文检索不受影响（`no` 不参与索引，只用于显示）。")
    a("")

    a("### 7.3 宪法修正案（2018年）的说明性文字被标成 section")
    a("")
    a("- bbbs：`2c909fdd678bf17901678bf59da8002d`，块 id `s2`")
    a("- 内容：`第七节相应改为第八节，第一百二十三条至第一百三十八条相应改为第一百二十八条至第一百四十三条。`")
    a("")
    a("这是一句**说明性文字**，不是章节标题，却被标成了 `type=section`（`isHeading=true`、`rank=3`）。")
    a("UI 会把它渲染成一个章节标题，并可能参与折叠树。")
    a("")
    a("**为什么不敢自动改**：改成 `text` 还是 `article` 需要判断它在原文档里的角色，需人工确认。")
    a("")

    a("### 7.4 5 组同名法规并存（新旧版本，**属真实数据，不建议删**）")
    a("")
    a("| 标题 | 旧版公布 | 新版公布 | 条数变化 |")
    a("|---|---|---|---|")
    a("| 环境噪声污染防治法 | 1996-10-29 | 2018-12-29 | 64 → 64 |")
    a("| 大气污染防治法 | 2015-08-29 | 2018-10-26 | 129 → 129 |")
    a("| 环境影响评价法 | 2016-07-02 | 2018-12-29 | 37 → 37 |")
    a("| 固体废物污染环境防治法 | 2016-11-07 | 2020-04-29 | 91 → 126 |")
    a("| 海洋环境保护法 | 2017-11-04 | 2023-10-24 | 97 → 124 |")
    a("")
    a("这 5 组是**合法的旧法/新法并存**，新旧条文不同，用户能对比，**保留**。")
    a("但列表里会出现两行同名、内容不同，用户无法分辨该点哪个。")
    a("**建议（UI 侧）**：列表项显示公布日期（`gbrq`），让用户自己分辨新旧。")
    a("")

    a("### 7.5 其他标题命名差异（不改）")
    a("")
    a("`tools/find_duplicates.py` 的 C 档共 7 组，除 7.1 外其余 6 组是**同一部法律的两种命名**，正文完全相同：")
    a("")
    a("- `中华人民共和国第五届全国人民代表大会常务委员会关于批准…决议` / `第五届全国人民代表大会常务委员会关于批准…决议`（缺「中华人民共和国」前缀）")
    a("- `最高人民法院 最高人民检察院…`（空格）/ `最高人民法院、最高人民检察院…`（顿号）")
    a("- `最高人民法院关于修改《中华人民共和国人民法院法庭规则》的决定` / `中华人民共和国人民法院法庭规则`（**注意：这两个是不同法规，正文相同是因为修改决定复述了原文**）")
    a("- `中华人民共和国中国人民解放军选举…办法`（多余「中华人民共和国」前缀）/ `中国人民解放军选举…办法`")
    a("- `中华人民共和国反分裂国家法` / `反分裂国家法`")
    a("- 宪法两份（见 §8）")
    a("")
    a("**处置**：全部不改。法规简称/全称并存是正常现象，改任何一边都可能破坏引用。")
    a("")


def write_applied_fixes(a) -> None:
    """已执行的清理 —— 有据可查的改动记录。"""
    a("## 8. 已执行的数据清理（2026-11-19）")
    a("")
    a("> 全部改动**只涉及标题字段，正文零改动**。执行脚本 `tools/clean_laws.py`，")
    a("> 备份在 `default-workspace\\laws-backup-20261119\\<时间戳>\\`（3 个文件）。")
    a("")

    a("### 8.1 宪法改名（2 项之一）")
    a("")
    a("| bbbs | 改前 | 改后 |")
    a("|---|---|---|")
    a("| `7ffbd0c9dc8698361aeca17662dd5e28` | 中华人民共和国宪法 | 中华人民共和国宪法（含序言·早期整理版） |")
    a("")
    a("**理由**：该条与 `2c909fdd…5a483004b`（宪法 2018 年修正文本）的 **143 条正文逐条 diff = 0 处不同**，")
    a("是同一部法律的两次不同整理。而**它是唯一带完整序言**（14 个 `text` 块）的版本，")
    a("`5a483004b` 的 blocks 直接从「第一章 总纲」开始、**没有序言**。")
    a("")
    a("它原本在 `ORDER BY grp, title` 下排宪法组**第 1 位**（名字最短），")
    a("导致用户点「宪法」必然先点到这份结构最脏（无 `label`、无 `toc`、条号是裸数字、有 `##` 泄漏）的版本。")
    a("")
    a("**改后排序（实测）**：")
    a("")
    a("```")
    a("1-5. 宪法修正案（1988/1993/1999/2004/2018年）")
    a("6.   中华人民共和国宪法（1982年）")
    a("7.   中华人民共和国宪法（2018年修正文本）   ← 用户默认点到的，结构最干净")
    a("8.   中华人民共和国宪法（含序言·早期整理版） ← 排最后，需要序言的人能找到")
    a("```")
    a("")
    a("**没有删除任何文件** —— 删除会丢失宪法序言，这是不可接受的。详见 `docs/宪法数据审计.md`。")
    a("")

    a("### 8.2 标题前导空格")
    a("")
    a("| bbbs | 改前 | 改后 |")
    a("|---|---|---|")
    a("| `ff8081819ff54ab801a0ae8cb4462d0e` | `\" 全国污染源普查条例\"` | `\"全国污染源普查条例\"` |")
    a("")
    a("全库**仅此 1 例**。前导空格导致该条在 `ORDER BY title` 下排到最前/最后，")
    a("且与 `b8b83f03…` 的同名条目无法对齐。")
    a("")

    a("### 8.3 `IMPORT_VERSION` 3 → 4")
    a("")
    a("`BundledData.kt` 的 `IMPORT_VERSION` 已改为 `\"4\"`。")
    a("**必须升版本**：改名后若不升，老用户 `isImported()` 仍返回 true 会跳过重导入，**看到的还是旧名字**。")
    a("")

    a("### 8.4 验证结果")
    a("")
    a("| 检查项 | 结果 |")
    a("|---|---|")
    a("| 法规总数 | 1874 → **1874**（不变） |")
    a("| 分组计数 | `{行政法规:876, 司法解释:551, 法律:439, 宪法:8}`（不变） |")
    a("| 正文 MD5（`7ffbd0c9…`）| 改后 `0fec7301…` = 备份 `0fec7301…`（169 块，不变） |")
    a("| 正文 MD5（`ff808181…`）| 改后 `7bb0712…` = 备份 `7bb0712…`（49 块，不变） |")
    a("| 前导空白标题 | 1 → **0** |")
    a("| 宪法组首位 | 最脏的 `7ffbd0c9…` → 干净的 `5a483004b` |")
    a("")


def main():
    ap = argparse.ArgumentParser()
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--assets", default=os.path.join(base, "app", "src", "main", "assets", "laws"))
    ap.add_argument("--out", default=os.path.join(base, "docs", "数据质量报告.md"))
    args = ap.parse_args()

    sanity = sanity_check()
    corpus = Corpus(args.assets)
    r = audit(corpus)
    write_report(r, args.out, sanity)

    # 控制台摘要
    print(f"laws={r['laws']} blocks={r['total_blocks']} articles={r['total_articles']} han={r['total_han']}")
    print(f"empty_body={len(r['empty_body_laws'])} no_article={len(r['no_article_laws'])} "
          f"bare_no={len(r['bare_no_laws'])} canonical_no={r['canonical_no_laws']} "
          f"dups={len(r['dups'])} orphan_section={len(r['orphan_sections'])} "
          f"md_prefix={len(r['text_prefixed'])} ac_mismatch={len(r['article_count_mismatch'])}")
    print("wrote", args.out)


if __name__ == "__main__":
    main()
