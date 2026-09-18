<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 4f0556d 复审：PDF 字典词法与 ZIP 正文 CRC

状态：**2026-09-11 JVM 定向修复已落地**。审查包 `mobile-agent-runtime-4f0556d-review-evidence.zip`，审查提交 `4f0556dcb016700fb2b6a02854e8ceae4324aec2`，判定 `NEEDS_AMEND`。可关闭项：旧转义键反例、CI Review gate 失败。本轮修复两项：PDF 字典结构识别导致的错误完整性标记（P1）与文件型 ZIP 正文 CRC 未复核（P2）。不改 Vision 适配器，不重跑用户原件。

## 1. P1：字典结构识别与错误的 complete

审查包 05–10 六个词法/层级变体在旧实现下都返回 `KEEPTOKEN ABC`、`complete=true`，而 pypdf 与 PyMuPDF 对 12 个文件一致返回 `KEEPTOKEN XYZ`。根因是**键查找不是词典解析**：`findPdfKeyEnd` 用「全串扫描 `/Name`」代替词法，且 `arrayBody`/`dictionaryEnd` 不跳过注释与字符串。四类失效：

| 变体 | 旧行为 | 原因 |
| --- | --- | --- |
| 05 键与数组之间注释 | 丢 `/Differences` | `arrayBody` 跳过空白后遇 `%` 判定非数组 |
| 06 数组内注释含 `]` | 提前闭合 | 不跳注释，`]` 被当成数组结束 |
| 07 字典内注释含 `>>` | 提前闭合字典 | `dictionaryEnd` 裸数 `>>` |
| 08 字符串值含 `>>` | 提前闭合字典 | 同上，未跳字面量串 |
| 09 嵌套字典同名键 | 取到嵌套空数组 | 平扫不区分层级 |
| 10 名称值 `/Differences` | 把值当键 | 平扫不区分键/值位置 |

**修复**：把键查找改成按词法走 token 流。

- 新增 `findTopLevelValueStart(dict, name)`：只认**当前层级**的键，并按「键值交替」状态推进；值用 `skipPdfValue` 整体跳过（字典、数组、字面量串、十六进制串、名称、数字/布尔/null、以及三段式间接引用 `n 0 R`）。因此名称值不会被当键，嵌套同名键不会被误取。
- 新增 `skipPdfValue`、`endOfPdfSimpleValue`、`endOfPdfName`、`endOfLiteralString`、`endOfHexString`、`endOfRegularToken`、`skipPdfSpaceAndComments`、`endOfPdfComment`、`isPdfTokenEnd`。
- `dictionaryEnd`、新增 `arrayEnd` 统一跳过注释/字面量串/十六进制串后再配平 `<< >>` 与 `[ ]`。
- `arrayBody` 改为返回结构化 `PdfArrayValue(present, body, malformed)`：**「缺失」「畸形」「合法空」是三件不同的事**。旧实现把畸形与空都返回 `""`，于是畸形 `/Differences` 静默退化成「无映射」并发布未声明字节；现在畸形一律 fail closed（`known=false` → 该页需要 Vision 并保留 PAGE 阻断）。
- `streamFilters` 同样改为先定位键的值、再解析单值或数组，过滤器名也解码。
- fingerprint `pdf-text-v14-pdfrenderer`。

## 2. P2：文件型 ZIP 正文 CRC 未复核

`KnowledgeArchive.forEachEntry(file)` 只比较两个头里声明的 CRC/尺寸。审查包把正文改成同长度的 `VALUE=900`、头保持 `VALUE=100` 的 CRC 与尺寸，旧序列**接受**该包并把改动后的正文交给 `onEntry`。`ZipFile.getInputStream` 信任中央目录、不校验正文，因此错误内容会被发布。

**修复**：两条路径都在交给 `onEntry`/返回结果前核对**实际解压字节**的尺寸与 CRC-32（新增 `verifyPayload`/`payloadIssue`/`crc32`）。CRC 是损坏检测，不是真实性认证。字节路径原本会由 `ZipInputStream` 抛 `ZipException`（同样 fail closed），显式校验使两条路径行为与原因一致。

## 3. 验证

命令（仓库根目录，先 `clean`，`--no-build-cache`，exit 0）：

```
:shared:knowledge-api:test :data:sqlite:test licenseGuard --dependency-verification=strict --no-daemon --no-build-cache
```

`:shared:knowledge-api:test` **99/99**、`:data:sqlite:test` **201/201**，合计 **300 tests、0 failure/error/skipped**；`licenseGuard` **PASS**。

生产 `PdfParser.parse` 直接读取**审查包自带 12 个 PDF**（含 05–10 六个反例），12/12 与 pypdf/PyMuPDF 参考一致：

```
01_plain … 04_escaped_both          KEEPTOKEN XYZ
05_comment_after_key                KEEPTOKEN XYZ   ← 旧 ABC
06_comment_array_closer             KEEPTOKEN XYZ   ← 旧 ABC
07_comment_dict_closer              KEEPTOKEN XYZ   ← 旧 ABC
08_literal_dict_closer              KEEPTOKEN XYZ   ← 旧 ABC
09_nested_same_name                 KEEPTOKEN XYZ   ← 旧 ABC
10_name_value_not_key               KEEPTOKEN XYZ   ← 旧 ABC
11_comment_before_key_control       KEEPTOKEN XYZ
12_literal_before_key_control       KEEPTOKEN XYZ
MISMATCHES: 0/12
```

生产 `KnowledgeArchive` 直接读取**审查包自带 2 个 ZIP**：

```
valid.zip                 BYTES ok=true   payloads=[VALUE=100|size=10]
valid.zip                 FILE  ok=true   payloads=[VALUE=100|size=10]
payload_crc_mismatch.zip  BYTES REJECTED-BY-EXCEPTION ZipException: invalid entry CRC
payload_crc_mismatch.zip  FILE  ok=false reason=ZIP entry note.txt decompressed CRC mismatch payloads=[]
```

两个反例都**没有任何 payload 到达 `onEntry`**。跨轮回归：12 个 4f0556d 夹具 + 13 个 7500ad3/dbceaa2/903c33e 夹具共 35 个 PDF 全部解析成功且与各自参考一致（`symbol_builtin`/`escaped_symbol` → `αβγ`；`zapf_builtin` 保持不完整 + PAGE 阻断），0 解析失败。

新增回归（都在真实 `PdfParser.parse` 与 Repository 发布路径上）：

- `DocumentParserTest.differencesIsFoundAcrossLexiconShapesOtherwiseDiscardedByAFlatScan`：六种词法/层级变体都必须得到 `XYZ` 且不得出现 `ABC`。
- `DocumentParserTest.malformedDifferencesValueFailsClosedInsteadOfPublishingUnmappedText`：`/Differences 5` 必须标为需要 Vision 并保留 PAGE 阻断。
- `KnowledgeRepositoryTest.pdfWithDifferencesBehindCommentsAndNestingPublishesMappedGlyphsAsReady`：四种变体导入后 `READY`、可检索 `KEEPTOKEN XYZ`、`document_versions.status = READY`。
- `KnowledgeRepositoryTest.malformedDifferencesPdfDoesNotPublishAsReady`：畸形映射停在 `WAITING_FOR_VISION_MODEL`，不发布 `ABC`。
- `KnowledgeRepositoryTest.corruptedArchiveBodyFailsImportWithoutPublishingAnyEntry`：改正文的包导入抛错、无 READY job、搜索为空。
- `KnowledgeRepositoryTest.intactArchiveBodyImportsWhenHeadersAndBodyAgree`：正常包仍可导入，防止过度收紧。

## 4. 未建立

- 未跑全仓 `Gradle check`、Android 设备测试、真实 Vision 或用户 294 原件；CI 结论需另查本轮推送后的运行。
- 修复版尚未经新一轮独立只读审阅。
- 审查包 `FILTER CHECKS` 的辅助输出（`comment_gap`/`indirect`/`invalid_type` → `[]`）本轮未单列处理：这些代表扫描器面对非常规写的过滤器时的**保守空结果**（fail closed，不会把未解压字节当完整文本），未发现与 05–10 同类的误发布路径。
- `isImageDict` 现在每个对象都会走一次词法键查找；对象字典通常很小，但超大对象字典上的成本未单独压测。