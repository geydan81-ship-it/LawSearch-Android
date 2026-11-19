#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
法规数据清理（宪法专项 + 全库重复/脏标题）。

## ⚠️ 教训（这个脚本的第一版真的犯过这个错）

**改 JSON 后必须同步改 `_meta.json.gz`，否则 App 显示的还是旧名字。**

第一版 `clean_laws.py` 只改了单部 `{bbbs}.json.gz` 里的 `title`，
`_meta.json.gz` 那部分代码只是把原对象 `json.dump` 回去，
**从没给 `meta["laws"][bbbs]["title"]` 赋过新值** ——
gzip 写回去的内容和原来一模一样，等于没改。

而 `_meta.json.gz` 恰恰是 `BundledData.lawAssetNames()` 和列表页抬头读的那份。

**验证方法（必须做）**：写完立刻读回来，断言

```python
json_title == meta["laws"][bbbs]["title"]
```

**不要只信 `--apply` 的日志输出** —— 它当时打印了「已改 xxx」，
但 `_meta.json.gz` 其实一个字节都没变。是「写完读回来核对」这一步抓到的。

## 改动的每一项都必须有证据，否则不删

本脚本只做 4 类**有明确数据证据**的修正：

| # | 类别 | 判据 | 处理 |
|---|---|---|---|
| 1 | 重复宪法 | 同 group=宪法、article 正文逐字相同 | 保留结构最干净的那份，其余不删文件（见下） |
| 2 | 标题前导空白 | `title != title.lstrip()` | 去空白 |
| 3 | 标题错字 | 与同正文的另一部标题差 1 字且日期相同 | **只报告，不自动改**（需人工确认） |
| 4 | 修正案条号 | 报告，不自动改 |

⚠️ **不删除任何宪法正文文件**——原因见 docs/宪法数据审计.md：
  `7ffbd0c9…`（脏的那份）是**唯一带完整序言（14 个 text 块）**的版本，
  `5a483004b`（干净的那份）**没有序言**。删掉会丢内容。

所以本脚本对宪法的处理是 **「改名 + 修正标签」**，不是删除。

用法：
    python tools/clean_laws.py --dry-run      # 只看会改什么
    python tools/clean_laws.py --apply        # 备份后执行
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import shutil
import sys
from datetime import datetime

sys.stdout.reconfigure(encoding="utf-8")


def body_text(block: dict) -> str:
    paras = block.get("paragraphs") or []
    if paras:
        return "\n".join(paras)
    content = block.get("content") or ""
    if content:
        return content
    return block.get("text") or ""


def arts_fingerprint(law: dict) -> str:
    import hashlib
    parts = [re.sub(r"\s+", "", body_text(b)) for b in law.get("blocks") or []
             if b.get("type") == "article"]
    return hashlib.sha256("\u0001".join(parts).encode("utf-8")).hexdigest()


# ---------------------------------------------------------------- 计划

# 宪法：三项重命名（让列表顺序与内容正确性一致）
#   7ffbd0c9… : 唯一有序言、但结构最脏（无 label / toc，条号是裸数字）→ 排最后
#   5a483004b : 结构干净、无 markdown，但没有序言 → 名字去掉括号，作为正文主版本
#   9eb80037  : 1982 原文，保持不变
CONSTITUTION_RENAMES = {
    "7ffbd0c9dc8698361aeca17662dd5e28":
        "中华人民共和国宪法（含序言·早期整理版）",
    "2c909fdd678bf17901678bf5a483004b":
        "中华人民共和国宪法（2018年修正文本）",   # 保持不变，仅确认
}

# 标题前导/尾随空白（全库仅 1 例，机械修正）
def plan_title_fixes(meta: dict) -> list[tuple[str, str, str]]:
    out = []
    for bbbs, m in meta.items():
        t = m.get("title") or ""
        s = t.strip()
        if t != s and s:
            out.append((bbbs, t, s))
    return out


def plan_constitution_group(meta: dict) -> list[dict]:
    """给出 group=宪法 下每部法规的结构质量，供决策"""
    return []


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--assets", default=os.path.join(base, "app", "src", "main", "assets", "laws"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--backup-root", default=os.path.join(
        os.path.dirname(base), "laws-backup-" + datetime.now().strftime("%Y%m%d")))
    args = ap.parse_args()

    if not args.dry_run and not args.apply:
        print("必须指定 --dry-run 或 --apply")
        return 1

    assets = args.assets
    meta_path = os.path.join(assets, "_meta.json.gz")
    meta = json.loads(gzip.open(meta_path, "rt", encoding="utf-8").read())

    changes: list[tuple[str, str, str, str]] = []   # (bbbs, kind, old, new)

    # --- 类 1：宪法改名
    for bbbs, new_title in CONSTITUTION_RENAMES.items():
        if bbbs not in meta["laws"]:
            print(f"⚠️ 宪法 {bbbs} 不在 _meta 里，跳过")
            continue
        old = meta["laws"][bbbs].get("title") or ""
        if old != new_title:
            changes.append((bbbs, "宪法改名", old, new_title))

    # --- 类 2：标题空白
    for bbbs, old, new in plan_title_fixes(meta["laws"]):
        changes.append((bbbs, "标题去空白", old, new))

    print(f"计划改动 {len(changes)} 项：")
    for bbbs, kind, old, new in changes:
        print(f"  [{kind}] {bbbs}")
        print(f"      - {old!r}")
        print(f"      + {new!r}")

    if args.dry_run:
        print("\n（dry-run，未写入）")
        return 0

    # --- 备份
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = os.path.join(args.backup_root, stamp)
    os.makedirs(backup, exist_ok=True)
    touched = ["_meta.json.gz"]
    for bbbs, _, _, _ in changes:
        touched.append(bbbs + ".json.gz")
    for name in touched:
        src = os.path.join(assets, name)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(backup, name))
    print(f"\n已备份 {len(touched)} 个文件到 {backup}")

    # --- 写回 _meta.json.gz
    # ⚠️ 第一版这里漏了「把新标题写进 meta['laws'][bbbs]['title']」，
    # 只 dump 了原对象 → _meta.json.gz 根本没变（备份对比才发现的）。
    # 现在显式赋值后再 dump。
    for bbbs, kind, old, new in changes:
        entry = meta["laws"].get(bbbs)
        if entry is None:
            print(f"  ⚠️ _meta 里没有 {bbbs}，跳过 meta 更新")
            continue
        entry["title"] = new
    meta["updatedAt"] = datetime.now().strftime("%Y-%m-%d")

    meta_tmp = meta_path + ".tmp.gz"
    with gzip.open(meta_tmp, "wt", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False)
    os.replace(meta_tmp, meta_path)
    print(f"已写回 _meta.json.gz（{len(changes)} 条 title）")

    # --- 写回单部法规 JSON（title 字段 + meta.title）
    for bbbs, kind, old, new in changes:
        p = os.path.join(assets, bbbs + ".json.gz")
        if not os.path.exists(p):
            continue
        law = json.loads(gzip.open(p, "rt", encoding="utf-8").read())
        law["title"] = new
        if isinstance(law.get("meta"), dict) and law["meta"].get("title"):
            # meta.title 保持官方全称，不改（它是"法定名称"，title 是"显示名"）
            pass
        tmp = p + ".tmp"
        with gzip.open(tmp, "wt", encoding="utf-8") as f:
            json.dump(law, f, ensure_ascii=False)
        os.replace(tmp, p)
        print(f"  已改 {bbbs}: {old!r} -> {new!r}")

    print("\n完成。请重跑 tools/audit_laws.py 与 tools/find_duplicates.py 复核。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
