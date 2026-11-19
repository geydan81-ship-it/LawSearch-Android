# LawSearch · 中国大陆法律法规检索

一款**完全离线**的**中国大陆法律法规**阅读器，覆盖 **1,874 部**法规、**69,694 条**条文，
支持中文全文检索、收藏与阅读位置记忆。无需联网即可使用全部功能。

> **数据范围 / Scope**：本应用仅收录**中国大陆**（中华人民共和国）法律法规及司法解释，
> **不包含**香港特别行政区、澳门特别行政区及台湾地区法规。
> This app covers **laws and regulations of Mainland China only**.

![Platform](https://img.shields.io/badge/Android-8.0%2B%20%28API%2026%29-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blueviolet)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.12.0-blue)
![Scope](https://img.shields.io/badge/laws-Mainland%20China%20only-red)
![License](https://img.shields.io/badge/code-Apache--2.0-blue)

**仓库**：[github.com/geydan81-ship-it/LawSearch-Android](https://github.com/geydan81-ship-it/LawSearch-Android)　|　
**B 站**：[space.bilibili.com/673364404](https://space.bilibili.com/673364404)

---

## 目录

- [其他平台](#其他平台)
- [数据范围](#数据范围)
- [特性](#特性)
- [截图](#截图)
- [内置数据](#内置数据)
- [技术栈](#技术栈)
- [构建](#构建)
- [项目结构](#项目结构)
- [中文检索的实现](#中文检索的实现)
- [数据来源与署名](#数据来源与署名)
- [开源协议](#开源协议)
- [English](#english)

---

## 其他平台

LawSearch 是一个跨平台项目，各平台独立仓库：

| 平台 | 仓库 | 说明 |
|---|---|---|
| **Android** | **本项目** | 液态玻璃界面，内置 1,874 部法规 |
| Windows | [LawSearch-Desktop](https://github.com/geydan81-ship-it/LawSearch-Desktop) | Windows 桌面版 |
| 项目门户 | [LawSearch](https://github.com/geydan81-ship-it/LawSearch) | 项目总览与下载入口 |

---

## 数据范围

本应用收录的是**中国大陆**（中华人民共和国）的法律、行政法规、司法解释等规范性文件。

| | 类别 | 部数 |
|---|---|---:|
| ✅ 包含 | 宪法 | 8 |
| ✅ 包含 | 法律 | 439 |
| ✅ 包含 | 行政法规 | 876 |
| ✅ 包含 | 司法解释 | 551 |
| ❌ **不包含** | 香港特别行政区法律 | — |
| ❌ **不包含** | 澳门特别行政区法律 | — |
| ❌ **不包含** | 台湾地区法规 | — |

> **Scope / 适用范围**：Mainland China (PRC) only.
> Does **not** include the laws of Hong Kong SAR, Macau SAR, or the Taiwan region.

数据来源：[国家法律法规数据库](https://flk.npc.gov.cn/)（全国人大常委会办公厅主办）。
更新日期：2026-11-19。

---

## 特性

### 📚 离线法规库

- **1,874 部**法规，**69,694 条**条文，约 **717 万字**正文，全部随 APK 打包
- 分类：宪法 8 / 法律 439 / 行政法规 876 / 司法解释 551
- 按「编 / 分编 / 章 / 节 / 条」完整还原层级结构，支持章节折叠
- 首次安装后完全离线，**无任何后台网络请求**

### 🔍 中文全文检索

- 基于 SQLite **FTS4** + 二元组（bigram）预分词，针对中文优化
- 相关度排序：相邻二元组连击长度为主信号，保证「船舶优先权」搜出的是定义条而非泛泛提及
- **同序容错**：查询词打错/多加字导致 0 命中时，自动降级为「各二元组全部出现」，结果标记为模糊匹配
- **单字查询提示**：单字（「船」「法」）在 bigram 索引里召回率不足 1%，会提示用户改用二字词
- 摘要高亮：命中片段从原文截取并用【】标出，不出现二元组拼接的字形重影

### 🎨 界面

- **液态玻璃**（Liquid Glass）视觉效果：`RuntimeShader` 实时背景模糊 + 折射
- 可更换壁纸（14 张内置）
- iOS 风格导航、大标题、卡片、TabBar
- 阅读器支持字号调节、段落间距、深色模式

### ⭐ 其他

- 条文级 / 整部法规级收藏
- 阅读进度记忆（自动记录最后阅读位置）
- 搜索历史
- 时效性判定（有效 / 已修改 / 已被修订 / 已废止）
- 内核级全文搜索，无网络、无账号、无追踪

---

## 截图

> 截图待补充（`docs/screenshots/`）

---

## 内置数据

| 项目 | 数值 |
|---|---:|
| 法规总数 | 1,874 部 |
| 条文总数 | 69,694 条 |
| 正文汉字数 | 7,173,071 字 |
| block 总数 | 85,168 |
| 数据文件 | `assets/laws/*.json.gz`（1,876 个，gzip 压缩后 10.4 MB） |

分类构成：

| 分类 | 部数 |
|---|---:|
| 行政法规 | 876 |
| 司法解释 | 551 |
| 法律 | 439 |
| 宪法 | 8 |

---

## 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Kotlin | 2.4.10 | 主语言 |
| AGP | 9.3.2 | Android Gradle Plugin（AGP 9 内置 Kotlin，不应用 `kotlin-android` 插件） |
| Jetpack Compose | 1.12.0（BOM 2026.09.00） | UI |
| Material 3 | 随 BOM | 基础组件 |
| Room | 2.7.1 | 元数据 / 收藏 / 历史的持久化 |
| **SQLite FTS4** | 系统内置 | 全文检索 |
| kotlinx.serialization | 1.8.0 | 法规 JSON 读写 |
| OkHttp | 4.12.0 | 仅用于「检查更新」 |
| Coroutines | — | 并发 |
| [Kyant0/Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | 2.0.1 | 液态玻璃效果 |
| [Kyant0/Shapes](https://github.com/Kyant0/Shapes) | 1.2.1 | 连续曲率圆角 |

**最低要求**：Android 8.0（API 26）
**目标版本**：API 37

> **为什么用 FTS4 而不是 FTS5**：Android 系统自带的 SQLite **不编译 FTS5 模块**，
> 真机上会报 `no such module: fts5`。FTS4 从 API 11 起就在系统里，可放心使用。
> （JVM 测试用的 `sqlite-jdbc` 自带 FTS5，所以单测能过、真机搜不到——这个坑踩过。）

---

## 构建

### 环境

- JDK 21
- Android SDK（compileSdk 37）

### 步骤

```bash
# 1. 克隆
git clone https://github.com/geydan81-ship-it/LawSearch-Android.git
cd LawSearch-Android

# 2. 生成签名（首次，会生成 workspace 内的 debug keystore）
python make_keystore.py

# 3. 构建
./gradlew :app:assembleStableRelease

# 产物：app/build/outputs/apk/stable/release/app-stable-release.apk
```

Windows 下可用项目自带的封装脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 :app:assembleStableRelease
```

### 运行测试

```bash
./gradlew :app:testStableDebugUnitTest
```

共 **165** 个单元测试（纯 JVM + Robolectric），覆盖：
数据解析、FTS4 检索语义、全库检索质量、中文数字、时效性判定、导入升级路径、UI 折叠树等。

### 打包变体

| Flavor | applicationId | 用途 |
|---|---|---|
| `stable` | `com.lawsearch.app.glass` | 正式版 |
| `rc` | `com.lawsearch.app.glass.rc` | 测试版，可与正式版共存 |

---

## 项目结构

```
LawSearch-Glass-K24/
├── app/
│   ├── src/main/
│   │   ├── assets/laws/        # 1,874 部法规 JSON（gzip）
│   │   ├── java/com/lawsearch/app/
│   │   │   ├── data/           # 数据层：Room、FTS、导入、仓储
│   │   │   ├── ui/             # 界面：主题、液态玻璃、列表、阅读器、搜索
│   │   │   ├── util/           # 中文分词、中文数字、时效性
│   │   │   └── crawler/        # 数据抓取与转换（开发用）
│   │   └── res/                # 资源
│   └── src/test/               # JVM 单元测试
├── tools/                      # Python 数据工具体系
│   ├── audit_laws.py           # 数据质量审计
│   ├── find_duplicates.py      # 重复法规检测
│   ├── fts_baseline.py         # 检索基线离线复核
│   └── clean_laws.py           # 数据清理（幂等、自动备份）
└── build.ps1                   # Windows 构建封装
```

### 核心数据类

| 类 | 职责 |
|---|---|
| `LawRepository` | 数据访问统一入口 |
| `ArticleFts` | FTS4 索引与检索、相关度打分 |
| `ChineseToken` | 中文二元组分词、MATCH 表达式构造 |
| `BundledData` | 内置数据导入与版本升级 |
| `Block` | 条文/章节块（`paragraphs → content → text` 三段正文回退） |

---

## 中文检索的实现

中文没有空格分词，而 Android 的 SQLite 只提供 `simple` 分词器（按空格切分）。
本项目在**入库前**把文本预分词成二元组：

```
「船舶优先权」 → 「船舶 舶优 优先 先权」
```

查询时把整个词的二元组放进**同一个短语**（FTS4 短语内多 token 表示「相邻且有序」）：

```
("船舶 舶优 优先 先权")
```

> ⚠️ 不要写成 `("船舶" AND "舶优" AND "抗力")` —— FTS4 的 AND 只要求同一行包含，
> 不要求相邻，会导致「不可抗力」「正当防卫」这类词 0 命中。

排序不依赖 FTS 的 rank，而是自行打分：

```
score = 完整短语命中(100) + 连击长度×10 + 覆盖率×6 + 位置加成 + 出现次数×0.2
```

实测召回率：常用法律术语的 bigram 短语命中数 **= 字面真值（100%）**。

---

## 数据来源与署名

### 法规数据

本项目内置的法规数据来自 **国家法律法规数据库**，经开源项目整理：

- **原始来源**：[国家法律法规数据库](https://flk.npc.gov.cn)（全国人大常委会办公厅）
- **数据整理**：[LawRefBook/Laws](https://github.com/LawRefBook/Laws)（★1890+）
  - 该仓库将上述官方数据整理为结构化 Markdown
  - Markdown 规范见仓库内「法律法规数据库.md」
- 部分条文经二次解析、结构化与清洗（详见 `tools/` 与 `docs/数据质量报告.md`）

**数据版权说明**：

- 法律法规文本属于**官方文献**，依《中华人民共和国著作权法》第五条不受著作权保护，
  可自由查阅、复制、传播。
- 本项目对数据的**整理格式**（JSON 结构、分类、锚点 id）不主张任何权利。
- 数据可能存在收录滞后、解析误差或遗漏，**不构成法律依据**；
  涉及实际法律事务请以[国家法律法规数据库](https://flk.npc.gov.cn)的官方文本为准。

### 界面与效果

- 液态玻璃效果：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）
- 连续曲率圆角：[Kyant0/Shapes](https://github.com/Kyant0/Shapes)（Apache-2.0）
- 液态玻璃的 CSS/SVG 滤镜思路参考：[nikdelvin/liquid-glass](https://github.com/nikdelvin/liquid-glass)

### 素材说明

**本仓库不包含示例壁纸**（版权原因）。应用支持从设备相册选择自定义壁纸。

**本仓库不包含内置字体**。早期版本曾打包仿宋/楷体/黑体，但这些是第三方商业字体
（版权方保留一切权利），随应用分发存在侵权风险，已全部移除。
现在正文统一使用**系统字体**——完整字库、无缺字、零授权风险。

---

## 开源协议

| 部分 | 协议 | 文件 |
|---|---|---|
| **源代码** | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) | [`LICENSE`](LICENSE) |
| **第三方组件署名** | Apache-2.0 要求保留的归因声明 | [`NOTICE`](NOTICE) |
| **法规数据** | 官方文献，不受著作权保护（见上节） | `NOTICE` 第一节 |
| **第三方依赖** | 见下方清单，全部 Apache-2.0 | — |

`Copyright 2026 The LawSearch Project`

本项目采用 Apache-2.0 而非 MIT 的原因：**Apache-2.0 包含明确的专利授权**（第 3 条），
对使用者更友好；且与全部依赖（AndroidX / Compose / Room / Backdrop 等）协议一致，
下游使用者无需处理协议兼容问题。

### 第三方依赖许可证

| 依赖 | 协议 |
|---|---|
| AndroidX（core-ktx / activity-compose / lifecycle / navigation） | Apache-2.0 |
| Jetpack Compose（ui / foundation / material3） | Apache-2.0 |
| Room | Apache-2.0 |
| WorkManager | Apache-2.0 |
| OkHttp | Apache-2.0 |
| kotlinx.serialization / kotlinx.coroutines | Apache-2.0 |
| Kyant0 Backdrop | Apache-2.0 |
| Kyant0 Shapes | Apache-2.0 |

**全部依赖均为 Apache-2.0**，与本项目的 Apache-2.0 协议完全兼容。
各项版权归属详见 [`NOTICE`](NOTICE)。

---

## 致谢

- [国家法律法规数据库](https://flk.npc.gov.cn) —— 法规数据原始来源
- [LawRefBook/Laws](https://github.com/LawRefBook/Laws) —— 数据整理与结构化
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) —— 液态玻璃效果
- [Kyant0/Shapes](https://github.com/Kyant0/Shapes) —— 连续曲率形状
- 以及所有为本项目提供反馈与测试的用户

## 关于作者

- GitHub：[geydan81-ship-it](https://github.com/geydan81-ship-it)
- B 站：[space.bilibili.com/673364404](https://space.bilibili.com/673364404)

## 数据质量报告

本项目的法规数据经过系统性的质量审计，报告见 [`docs/`](docs/)：

| 报告 | 内容 |
|---|---|
| [`数据质量报告.md`](docs/数据质量报告.md) | 1874 部法规的结构审计、已知数据缺陷清单 |
| [`宪法数据审计.md`](docs/宪法数据审计.md) | 8 部宪法的逐项核查（含 3 份「宪法」的辨析） |
| [`重复法规检测.md`](docs/重复法规检测.md) | 按正文指纹判重（真重复 / 新旧版本 / 改名收录） |

审计与清理工具见 [`tools/`](tools/)：`audit_laws.py`、`find_duplicates.py`、`fts_baseline.py`、`clean_laws.py`。

---

## 免责声明

本应用仅供**学习、查阅与研究**使用。法规文本可能存在录入错误、版本滞后或解析遗漏，
**不构成法律意见**，不能替代专业法律咨询。涉及实际法律问题，请咨询执业律师或
以[国家法律法规数据库](https://flk.npc.gov.cn)的官方文本为准。

本应用**仅覆盖中国大陆（中华人民共和国）法律法规**，不适用于香港特别行政区、
澳门特别行政区及台湾地区。

---

## English

**LawSearch** is a **completely offline** reader for **laws and regulations of
Mainland China** (中华人民共和国法律法规), covering **1,874 statutes** and
**69,694 articles** with full-text Chinese search, bookmarks and reading-position memory.

### Scope

**Mainland China only.**

| | Category | Count |
|---|---|---:|
| ✅ Included | Constitution | 8 |
| ✅ Included | Statutes (法律) | 439 |
| ✅ Included | Administrative regulations (行政法规) | 876 |
| ✅ Included | Judicial interpretations (司法解释) | 551 |
| ❌ **Excluded** | Hong Kong SAR laws | — |
| ❌ **Excluded** | Macau SAR laws | — |
| ❌ **Excluded** | Taiwan region statutes | — |

### Language

Chinese (Simplified) only. The UI, content and search are all in Chinese.

### Features

- **Fully offline** — no network requests are ever made, no account required
- **Chinese full-text search** using bigram tokenization over SQLite FTS4
  (Android's bundled SQLite does not ship FTS5, so FTS4 was chosen deliberately)
- **Fuzzy tolerance** — if a query yields no exact match, it degrades to
  "all bigrams present" and flags the results as fuzzy
- Liquid Glass UI (real-time `RuntimeShader` blur and refraction)
- Bookmarks (article-level and statute-level), reading progress, search history
- Validity status (in force / amended / revised / repealed)

### Data source

- **[National Laws and Regulations Database](https://flk.npc.gov.cn/)**
  (国家法律法规数据库), maintained by the NPC Standing Committee of the PRC
- Structured by the open-source project
  **[LawRefBook/Laws](https://github.com/LawRefBook/Laws)**

According to **Article 5 of the Copyright Law of the People's Republic of China**,
laws, administrative regulations and other documents of a legislative,
administrative or judicial nature are **not protected by copyright**. The legal
texts bundled here are therefore freely redistributable.

> The texts may contain transcription errors, outdated versions or parsing
> omissions. This app **does not constitute legal advice** and must not replace
> professional legal counsel. For authoritative texts, always refer to the
> official National Laws and Regulations Database.

### License

- **Code**: [Apache License 2.0](LICENSE) — includes an explicit patent grant.
- **Legal data**: official documents, not subject to copyright (see above).
- **Third-party components**: see [NOTICE](NOTICE). All dependencies are Apache-2.0.

### Author

- GitHub: [@geydan81-ship-it](https://github.com/geydan81-ship-it)
- Bilibili: [673364404](https://space.bilibili.com/673364404)
