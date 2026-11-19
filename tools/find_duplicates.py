#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
同名/同正文法规的重复检测（P1-宪法专项 + 全库扫描）。

用法：
    python tools/find_duplicates.py            # 只报告
    python tools/find_duplicates.py --json     # 机器可读

判定三档（必须分开，否则会误删合法的多版本）：
  A. **同标题 + 正文逐字相同** → 真重复，可删
  B. **同标题 + 正文不同**     → 多是「旧版/新版」，不能删，但列表会重复显示
  C. **不同标题 + 正文相同**   → 换名转载，可能是重复收录

⚠️ 历史教训：本项目的判据翻过两次车（见 SearchQualityTest 注释），
所以这里每个判据都附带"实际看到的值"，不做"0 即通过"的静默断言。
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")


def body_text(block: dict) -> str:
    """与 Kotlin Block.bodyParagraphs() 一致的三段回退"""
    paras = block.get("paragraphs") or []
    if paras:
        return "\n".join(paras)
    content = block.get("content") or ""
    if content:
        return content
    return block.get("text") or ""


def law_fingerprint(law: dict) -> tuple[str, str, int]:
    """返回 (article 正文指纹, 全文指纹, article 数)"""
    parts = []
    for b in law.get("blocks") or []:
        if b.get("type") == "article":
            parts.append(re.sub(r"\s+", "", body_text(b)))
    arts = hashlib.sha256("\u0001".join(parts).encode("utf-8")).hexdigest()

    allparts = [re.sub(r"\s+", "", body_text(b)) for b in law.get("blocks") or []]
    full = hashlib.sha256("\u0001".join(allparts).encode("utf-8")).hexdigest()
    return arts, full, len(parts)


class Corpus:
    def __init__(self, assets: str):
        self.dir = assets
        self.meta = json.loads(
            gzip.open(os.path.join(assets, "_meta.json.gz"), "rt", encoding="utf-8").read()
        )["laws"]

    def law(self, bbbs: str) -> dict | None:
        p = os.path.join(self.dir, bbbs + ".json.gz")
        if not os.path.exists(p):
            return None
        return json.loads(gzip.open(p, "rt", encoding="utf-8").read())

    def quality(self, law: dict) -> dict:
        bs = law.get("blocks") or []
        heads = [b for b in bs if b.get("type") in ("part", "subpart", "chapter", "section")]
        arts = [b for b in bs if b.get("type") == "article"]
        return dict(
            blocks=len(bs),
            articles=len(arts),
            headings=len(heads),
            headings_without_label=sum(1 for b in heads if not (b.get("label") or "").strip()),
            toc=len(law.get("toc") or []),
            md_leak=sum(1 for b in bs if (b.get("text") or "").startswith("#")),
            has_preamble=any(b.get("type") == "text" for b in bs),
            canonical_no=sum(1 for b in arts if (b.get("no") or "").startswith("第")),
        )


def scan(corpus: Corpus) -> dict:
    by_title = defaultdict(list)
    by_arts = defaultdict(list)
    info = {}

    for bbbs, m in corpus.meta.items():
        law = corpus.law(bbbs)
        if law is None:
            continue
        arts_fp, full_fp, n_arts = law_fingerprint(law)
        q = corpus.quality(law)
        info[bbbs] = dict(title=m.get("title") or "", group=m.get("group") or "",
                          gbrq=m.get("gbrq") or "", sxrq=law.get("sxrq") or "",
                          sxx=law.get("sxx") or 0, arts_fp=arts_fp, full_fp=full_fp,
                          n_arts=n_arts, **q)
        by_title[info[bbbs]["title"]].append(bbbs)
        if n_arts > 0:
            by_arts[arts_fp].append(bbbs)

    exact_dups = []      # A：同标题 + article 正文逐字相同
    version_dups = []    # B：同标题 + 正文不同
    cross_title = []     # C：不同标题 + 正文相同

    for title, ids in by_title.items():
        if len(ids) < 2:
            continue
        fps = {info[i]["arts_fp"] for i in ids}
        if len(fps) == 1:
            exact_dups.append((title, ids))
        else:
            version_dups.append((title, ids))

    for fp, ids in by_arts.items():
        titles = {info[i]["title"] for i in ids}
        if len(ids) > 1 and len(titles) > 1:
            cross_title.append((sorted(titles), ids))

    return dict(info=info, exact=exact_dups, version=version_dups, cross=cross_title)


def dump(r: dict, out: str | None) -> None:
    info = r["info"]
    L = []
    a = L.append
    a("# 重复法规检测报告")
    a("")
    a("检测三档：")
    a("- **A 真重复**：同标题 + article 正文逐字相同 → 可安全删除一份")
    a("- **B 多版本**：同标题 + 正文不同 → 旧版/新版并存，列表会重复显示，**不建议删**")
    a("- **C 换名转载**：不同标题 + 正文相同 → 可能重复收录")
    a("")
    a(f"共扫描 **{len(info)}** 部法规。")
    a("")

    a(f"## A. 真重复（同标题 + 正文完全相同）：**{len(r['exact'])}** 组")
    a("")
    if r["exact"]:
        a("| 标题 | 数量 | bbbs | 各组结构质量 |")
        a("|---|---:|---|---|")
        for title, ids in r["exact"]:
            cells = []
            for i in ids:
                d = info[i]
                cells.append(
                    f"`{i}`：块{d['blocks']} 条{d['articles']} toc{d['toc']} "
                    f"标题无label{d['headings_without_label']} md泄漏{d['md_leak']}"
                )
            a(f"| {title} | {len(ids)} | {'<br>'.join('`' + i + '`' for i in ids)} | {'<br>'.join(cells)} |")
    else:
        a("✅ 无")
    a("")

    a(f"## B. 同标题多版本（正文不同）：**{len(r['version'])}** 组")
    a("")
    if r["version"]:
        a("| 标题 | bbbs | 公布 | 施行 | sxx | 条数 |")
        a("|---|---|---|---|---:|---:|")
        for title, ids in r["version"]:
            for i in ids:
                d = info[i]
                a(f"| {title} | `{i}` | {d['gbrq'] or '—'} | {d['sxrq'] or '—'} | {d['sxx']} | {d['articles']} |")
    else:
        a("✅ 无")
    a("")

    a(f"## C. 不同标题但正文相同：**{len(r['cross'])}** 组")
    a("")
    for titles, ids in r["cross"][:30]:
        a(f"- {' / '.join(titles)} → {', '.join('`' + i + '`' for i in ids)}")
    a("")

    text = "\n".join(L) + "\n"
    if out:
        os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
        open(out, "w", encoding="utf-8").write(text)
        print("wrote", out)
    else:
        print(text)


def main():
    ap = argparse.ArgumentParser()
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--assets", default=os.path.join(base, "app", "src", "main", "assets", "laws"))
    ap.add_argument("--out", default=None)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    c = Corpus(args.assets)
    r = scan(c)
    if args.json:
        print(json.dumps({k: (v if k != "info" else len(v)) for k, v in r.items()},
                         ensure_ascii=False, indent=2))
        for title, ids in r["exact"]:
            print("EXACT:", title, ids)
        for title, ids in r["version"]:
            print("VERSION:", title, ids)
        return
    dump(r, args.out)
    print(f"exact={len(r['exact'])} version={len(r['version'])} cross={len(r['cross'])}")


if __name__ == "__main__":
    main()
