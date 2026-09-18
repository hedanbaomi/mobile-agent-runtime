<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# dbceaa2 独立复审：内置字体编码的接受边界与 PDF 名称转义

状态：**2026-09-10 JVM 定向修复已落地（本地未提交）**。审查包 `mobile-agent-runtime-dbceaa2-review-evidence.zip`，审查提交 `dbceaa2cbe9f0b6cb180d4a7080b33e91a533800`。审查方为方法/契约级只读 harness，不是 Android/Gradle 运行。本轮只修复复审确认的字体名接受边界，不改 Vision 适配器、不重跑用户原件，也不宣称完整 PDF 字体覆盖。源码与 `docs`/`HANDOFF` 本轮改动尚未 commit、未 push（等待用户授权）。

## 审查结论与本轮处置

| # | 复审判定 | 本轮处置 |
| --- | --- | --- |
| 1 | 上一轮同 blob 复用缺陷已修复：`publishedReadyStage` 返回原 published 状态，`reuseJob` 保留 `READY_WITH_VISUAL_GAPS`/`hasImages`/`visualGapsAccepted`；六个方法级夹具（local/API × gap/full/old-parser）通过 | 复核认可，未改 |
| 2 | 字面 `/BaseFont /Symbol` 已解码为 `αβγ`；字面 ZapfDingbats 已标为不完整；此前七个文本控制项仍符合预期 | 复核认可，未改 |
| 3 | **fail-closed 承诺仍不完整**：`builtInBaseEncoding` 的 catch-all `else` 返回 STANDARD，未支持的字体会被当作已知；且 `namedDictionaryOrReference` 返回未解码的原始名称拼写，`/BaseFont /Sym#62ol` 落入 catch-all，于是核心返回 `abg`、`complete=true`、`needsVision=false` | **CONFIRMED，本轮修复** |

复审给出的可接受结果原话是「Correct decoding OR an explicit incomplete result」；本轮两者都做：等价的名称拼写解码后一致解析，真正的未知内置编码显式判为不完整。

## 关键实现

`shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/PdfParser.kt`

- 新增 `decodePdfName`（PDF 32000-1 7.3.5：`#` + 两位十六进制表示任意字节；非法十六进制按字面 `#` 保留），并统一用于：`namedDictionaryOrReference` 的名称值、内容流 `readName`、资源字典条目名（`namedResourcesFrom`）、`Do` 操作数名（`hasUnresolvedXObjectDo`）、`/Differences` 字形名。名称在比较与查表前必须先解码，否则等价拼写会静默错过基表。
- `builtInBaseEncoding` 改为按已知基名判定，取消 catch-all：`Symbol` → Symbol 基表；`ZapfDingbats` → fail closed；Adobe base-14 的十二个拉丁文字面（Helvetica/Times/Courier 家族）→ STANDARD；`/BaseFont` 缺失或其它任何名称 → fail closed（`known=false`，该页需要 Vision）。子集前缀 `ABCDEF+` 先剥离。
- fingerprint 升为 `pdf-text-v12-pdfrenderer`，使旧解析结果按既有机制同 blob 重解析。

## 行为影响（明确的取舍）

- 只有「简单字体既没有 `/Encoding`、也没有编码字典 `/BaseEncoding`」的页面受影响。声明了 `/WinAnsiEncoding`、`/MacRomanEncoding`、`/StandardEncoding` 或 `/Differences` 的文档行为不变。
- 其中 base-14 拉丁文字面继续按 STANDARD 完整发布（回归用例 `base14LatinFontWithoutEncodingStillPublishesCompleteText`）。
- 其余（非 base-14、无编码）此前会按 StandardEncoding 猜测并把 ASCII 文本标为完整；现在显式标为不完整并保留 PAGE 阻断，需要 Vision 或用户显式选择仅文本降级。这是 fail-closed 方向的收紧，代价是这类页面从「静默完整」变为「待视觉处理」；不承诺全部 PDF 字体。
- 未支持编码的文本仍以原始字节保留为 `surroundingText`，用户显式 `acceptTextOnlyVisualGaps` 时仍可检索，不是静默丢数据。

## 验证

命令（仓库根目录，`--no-daemon`，exit 0）：

```
:shared:knowledge-api:test --tests runtime.mobileagent.knowledge.DocumentParserTest
:shared:knowledge-api:test :data:sqlite:test
licenseGuard
```

- `DocumentParserTest` **52/52**（含本轮新增 4 例）；整模块 `:shared:knowledge-api:test` **95/95**、`:data:sqlite:test` **197/197**，合计 292 tests、0 failure/error/skipped。`licenseGuard` **PASS**。
- 新增回归：`escapedBaseFontNameDecodesLikeItsLiteralSpelling`（`Sym#62ol` → `αβγ`）、`escapedResourceFontNameMatchesContentStreamName`（资源名 `/F#32` 与内容流 `/F2` 一致解析）、`unknownBuiltInFontFailsClosedInsteadOfAssumingStandardEncoding`（未知字体 → `needsVision=true` + PAGE 阻断）、`base14LatinFontWithoutEncodingStillPublishesCompleteText`（防止过度收紧）。
- 生产 `PdfParser.parse` 直接读取审查包 `fixtures/*.pdf` 的实测结果：

```
symbol_builtin   text={KEEPTOKEN αβγ} pageNeedsVision=false
escaped_symbol   text={KEEPTOKEN αβγ} pageNeedsVision=false   ← 复审反例，已修复
zapf_builtin     text={KEEPTOKEN abg} pageNeedsVision=true  assets=PAGE:1
winansi / macroman / font_restore / control_*  与上一轮一致，均 complete
```

`escaped_symbol` 现已与审查包 `reference-results.json` 的 pypdf/PyMuPDF 参考值（`KEEPTOKEN\nαβγ`）一致。`unknown_builtin` 审查包只提供字典夹具（无 PDF），故用生产 `writeBuiltInFontPdf("KEEPTOKEN","ReviewUnknownFont")` 生成等价 PDF 复核：`pageNeedsVision=true`、`assets=PAGE:1`。

## 未建立

- 未跑全仓 `check`、Android 设备测试、真实 Vision 或用户 294 原件；`PdfParserMemoryDeviceTest` 是 opt-in 真机用例，本轮未执行（仅断言页数与内存，与本次判定无关）。
- 字典**键**的 `#xx` 转义（如 `/#42aseFont`）仍未解码；其失败方向是查不到键 → fail closed，不会发布猜测文本。真实生成器基本不产生这种拼写。
- 除 Symbol 外的内置编码（ZapfDingbats 等）按 fail closed 进入 Vision，不做字符级承诺；审查包也明确 pypdf/PyMuPDF 对 Zapf 不一致，不能作为字符正确性判据。
- 本轮修复本身尚未经过新一轮独立只读审阅。