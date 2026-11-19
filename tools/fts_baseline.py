#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
检索基线的**离线复核工具**（不依赖 Gradle / Android）。

用途：写检索测试断言前，先用它确认真实数字，避免「凭印象写断言」。
`FullCorpusSearchTest.kt` 里那 3 个断言的第一版就是凭印象写的，全错了：

| 我以为 | 实测 | 错在哪 |
|---|---|---|
| 单字查询 0 命中 | 「船」21 行、「法」25 行、「人」59 行 | bigram 分词对**被标点隔开的孤立 CJK 字**会产出单字 token |
| 「正当防卫」> 10 条 | 正好 10 条 | 是法律术语非常用词，10 条是对的 |
| 错字「船舶优先全权」能被同序容错救回 | 0 条 | `先全`/`全权` 这两个 bigram 文中根本不存在，**放宽不要求相邻、但要求 bigram 存在** |

本工具在 Python 里**逐字复刻** `ChineseToken.bigrams()` 与 `ArticleFts.index()` 的正文提取规则，
所以结论与 Kotlin 侧一致。

用法：
    python tools/fts_baseline.py                  # 打印全部基线
    python tools/fts_baseline.py --term 不可抗力   # 查单个词
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")

# ---------------- 逐字复刻 Kotlin ChineseToken ----------------
CJK_START, CJK_END = 0x4E00, 0x9FFF
EXT_A_START, EXT_A_END = 0x3400, 0x4DBF


def is_cjk(ch: str) -> bool:
    c = ord(ch)
    return CJK_START <= c <= CJK_END or EXT_A_START <= c <= EXT_A_END


def is_word(ch: str) -> bool:
    return is_cjk(ch) or ch.isalnum()


def bigrams(text: str) -> list[str]:
    """
    与 Kotlin `ChineseToken.bigrams()` 逐行对应。

    规则：按「连续 CJK 段」切，段长 1 → 单字 token；段长 n≥2 → (n-1) 个 bigram。
    ASCII 连续串整词小写。
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if not is_word(ch):
            i += 1
            continue
        if is_cjk(ch):
            j = i
            while j < n and is_cjk(text[j]):
                j += 1
            if j - i == 1:
                out.append(text[i:j])
            else:
                for k in range(i, j - 1):
                    out.append(text[k:k + 2])
            i = j
        else:
            j = i
            while j < n and is_word(text[j]) and not is_cjk(text[j]):
                j += 1
            out.append(text[i:j].lower())
            i = j
    return out


def match_expression(query: str) -> str | None:
    """与 Kotlin `ChineseToken.matchExpression()` 一致：整词一个短语，词间 AND"""
    clauses = []
    for raw in query.split():
        if not raw.strip():
            continue
        grams = bigrams(raw)
        if not grams:
            continue
        phrase = " ".join(g.replace('"', '""') for g in grams)
        clauses.append(f'("{phrase}")')
    return " AND ".join(clauses) if clauses else None


def query_grams(query: str) -> list[str]:
    return list(dict.fromkeys(bigrams(query)))


def body_text(b: dict) -> str:
    """与 Kotlin `Block.bodyParagraphs()` 一致的三段回退"""
    paras = b.get("paragraphs") or []
    if paras:
        return "\n".join(paras)
    content = b.get("content") or ""
    if content:
        return content
    return b.get("text") or ""


# ---------------- 建索引 ----------------

def build(assets: str):
    meta = json.loads(gzip.open(os.path.join(assets, "_meta.json.gz"), "rt", encoding="utf-8").read())["laws"]
    rows = []
    for bbbs in meta:
        p = os.path.join(assets, bbbs + ".json.gz")
        if not os.path.exists(p):
            continue
        law = json.loads(gzip.open(p, "rt", encoding="utf-8").read())
        for b in law.get("blocks") or []:
            if b.get("type") == "article":
                body = body_text(b)
            elif b.get("type") == "text":
                body = b.get("text") or ""
            else:
                continue
            if not body.strip():
                continue
            toks = bigrams(body)
            rows.append((bbbs, b.get("no") or "", body, toks))
    return meta, rows


def strict_hits(rows, grams: list[str]) -> int:
    """连续有序出现（等价 FTS4 短语）"""
    n = len(grams)
    if n == 0:
        return 0
    return sum(1 for _, _, _, t in rows
               if any(t[i:i + n] == grams for i in range(len(t) - n + 1)))


def relaxed_hits(rows, grams: list[str]) -> int:
    """各 bigram 全部出现（不要求相邻，等价同序容错）"""
    s = set(grams)
    if not s:
        return 0
    return sum(1 for _, _, _, t in rows if s <= set(t))


def like_hits(rows, term: str) -> int:
    return sum(1 for _, _, raw, _ in rows if term in raw)


def main():
    ap = argparse.ArgumentParser()
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--assets", default=os.path.join(base, "app", "src", "main", "assets", "laws"))
    ap.add_argument("--term", default=None, help="只查这一个词")
    args = ap.parse_args()

    meta, rows = build(args.assets)
    print(f"法规 {len(meta)} 部，索引行 {len(rows):,} 行\n")

    if args.term:
        q = args.term
        g = bigrams(q)
        print(f"查询「{q}」")
        print(f"  bigrams        = {g}")
        print(f"  matchExpression= {match_expression(q)}")
        print(f"  queryGrams     = {query_grams(q)}")
        print(f"  严格(短语)命中 = {strict_hits(rows, g)}")
        print(f"  放宽(AND)命中  = {relaxed_hits(rows, g)}")
        print(f"  LIKE 字面真值  = {like_hits(rows, q)}")
        return

    print("=" * 74)
    print("一、单字查询：不是 0 命中，而是「召回率极低」")
    print("=" * 74)
    print(f"{'字':^4}{'FTS命中':>10}{'LIKE真值':>12}{'召回率':>10}   说明")
    for ch in ["船", "法", "人", "婚", "税"]:
        fts = strict_hits(rows, bigrams(ch))
        lk = like_hits(rows, ch)
        r = fts / lk if lk else 0
        note = "索引里有单字 token（孤立字）" if fts else "索引里无单字 token"
        print(f"{ch:^4}{fts:>10}{lk:>12}{r:>9.4%}   {note}")

    print("\n" + "=" * 74)
    print("二、常用法律术语：严格 vs 放宽 vs 字面真值")
    print("=" * 74)
    print(f"{'查询':<10}{'bigram数':>9}{'严格':>7}{'放宽':>7}{'LIKE':>8}{'严格/LIKE':>11}")
    for q in ["不可抗力", "正当防卫", "劳动合同", "侵权责任",
              "行政赔偿", "不动产", "遗嘱继承", "有期徒刑", "船舶优先权"]:
        g = bigrams(q)
        s, rl, lk = strict_hits(rows, g), relaxed_hits(rows, g), like_hits(rows, q)
        ratio = s / lk if lk else 0
        print(f"{q:<10}{len(g):>9}{s:>7}{rl:>7}{lk:>8}{ratio:>10.1%}")

    print("\n" + "=" * 74)
    print("三、同序容错到底能救什么")
    print("=" * 74)
    print("关键：放宽只放松「相邻」，**不放松「存在」**。")
    print("      只要有一个 bigram 全库为 0，两种写法都是 0。")
    print("      但如果各 bigram 都存在、只是从不同时出现在同一行，放宽仍然是 0。")
    print()
    cases = [
        ("船舶优先全权", "多打一个字"),
        ("船舶 优先权", "加了空格"),
        ("船舶优先权", "正确写法"),
    ]
    for q, why in cases:
        g = bigrams(q)
        s, rl = strict_hits(rows, g), relaxed_hits(rows, g)
        per = {x: sum(1 for _, _, _, t in rows if x in t) for x in g}
        missing = [x for x, n in per.items() if n == 0]
        print(f"  「{q}」({why})")
        print(f"     bigrams = {g}")
        print(f"     各 bigram 出现行数: {per}")
        print(f"     严格={s:<6} 放宽={rl:<6} 全库为0的bigram={missing or '无'}")
        if s == 0 and rl > 0:
            print(f"     ⇒ 容错救得回来：严格 0 → 放宽 {rl}")
        elif s == 0 and rl == 0 and missing:
            print(f"     ⇒ 救不回来：{missing} 在全库不存在（放宽不放松「存在」）")
        elif s == 0 and rl == 0:
            print(f"     ⇒ 救不回来：各 bigram 都单独存在，但**没有任何一行同时包含全部**")
            print(f"        （错字的代价是引入了文中不会共现的字，放宽 AND 也凑不齐）")
        else:
            print(f"     ⇒ 严格即可命中，无需容错")
        print()


if __name__ == "__main__":
    main()
