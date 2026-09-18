<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 903c33e 独立复审：Symbol 内置编码与视觉缺口再导入

状态：**2026-09-10 JVM 定向修复已落地**。审查包 `mobile-agent-runtime-903c33e-review-evidence.zip`，审查提交 `903c33eadba4e20693233bac86e55d95e0b67c27`（`codex/user-qa-fixes`，审查方为方法/契约级只读 harness，不是 Android 或 Gradle 运行）。本轮按生产路径修复独立复审确认的两处缺陷，不改 Vision 适配器、不重跑用户原件，也不宣称完整 PDF 字体覆盖或 K06 验收。源码已作为 `dbceaa2` 推送到 `origin/codex/user-qa-fixes`；`HANDOFF.md` 与 `docs/` 按既有约定仅本地 commit、不 push。

## 审查指控与结论

| # | 指控 | 结论 | 修复 |
| --- | --- | --- | --- |
| A | 混合页中某页 PAGE 阻断会跳过其它页可用 JPEG；栅格失败可静默返回 Ok 而缺页 | **已在前轮修复，本轮复核通过** | 审查包 `coverage-results.txt` 显示 `mixed_page2_render_failure` 为 `Ok`、`missingPages=[]` 且第 2 页 JPEG 已处理；阻断页栅格失败仍 `Failed`。本轮未改覆盖逻辑 |
| B | `/BaseFont /Symbol` 无 `/Encoding` 时，Symbol 内置编码被当成 StandardEncoding，`(abg)` 得到 Latin `abg`、`complete=true`、`needsVision=false` | **CONFIRMED** | 无 (Base)Encoding 时按 `/BaseFont` 解析内置编码：`Symbol` 走 Symbol 内置表（经 `\uXXXX` 转 Unicode）；`ZapfDingbats` 无可靠文本映射，fail closed；未知内置编码 fail closed。parser fingerprint `pdf-text-v11-pdfrenderer` |
| C | 同 blob 再导入复用 `READY_WITH_VISUAL_GAPS` 版本时返回 `stage=READY`、`isCompleteSuccess=true`，把文本降级版本静默升级为完整 | **CONFIRMED** | `isPublishedReady()` 改为 `publishedReadyStage()`，返回原 published 状态；复用分支保留 `READY_WITH_VISUAL_GAPS`、`visualGapsAccepted=true`、`hasImages=true` 与 `TEXT_ONLY_VISUAL_GAPS` 说明，不再静默上传补图或标为完整 |

审查包 README 另记录 UI 观察：`KnowledgeViewModel.loadSnapshot` 从 `document_versions` 读文档状态、从 `job.stage` 读任务状态，因而可同时显示互相矛盾的文档/任务状态。该现象正是指控 C 的直接症状；复用分支保留原状态后两者一致，本轮不需要额外 UI 改动，也未做 UI 截图验证。

## 关键实现

- `shared/knowledge-api/.../PdfParser.kt`：`encodingFromFont` 在无 `/Encoding`、或编码字典无 `/BaseEncoding` 时调用 `builtInBaseEncoding(dict)`；新增 `SYMBOL` 基表、`symbolChar`、`SYMBOL_ASCII_OVERRIDES` 与 `SYMBOL_HIGH_80`。测试辅助 `writeSymbolBuiltinPdf` / `writeZapfDingbatsBuiltinPdf`。
- `data/sqlite/.../KnowledgeRepository.kt`：`publishedReadyStage` 返回 `ImportStage?`（`READY` 或 `READY_WITH_VISUAL_GAPS`）；新增 `reuseJob` 统一两处复用分支（`importBytes` 与 `importBytesCancellable`）。
- 解析行为变化使旧 fingerprint 结果过期，同 blob 再导入按既有版本机制重新解析。

## 验证

命令（仓库根目录，`--no-daemon`，exit 0）：

```
.\gradlew.bat :shared:knowledge-api:test --tests runtime.mobileagent.knowledge.DocumentParserTest --dependency-verification=strict --no-daemon --console=plain
.\gradlew.bat :data:sqlite:test --tests runtime.mobileagent.data.KnowledgeRepositoryTest --dependency-verification=strict --no-daemon --console=plain
```

两者均 **BUILD SUCCESSFUL**（2026-09-10）。定向类 `DocumentParserTest` 48/48、`KnowledgeRepositoryTest` 84/84；随后整模块 `:shared:knowledge-api:test` 与 `:data:sqlite:test` 亦 **BUILD SUCCESSFUL**，合计 91+197=288 tests、0 failure/error/skipped。提交前许可门禁 `licenseGuard --dependency-verification=strict --no-daemon` **PASS**（含本轮新增证据文档的 SPDX 检查）。

执行环境说明：本机沙箱不能写默认 `C:\Users\32735\.gradle`，因此设 `GRADLE_USER_HOME=<项目内临时目录>`、`GRADLE_RO_DEP_CACHE=C:\Users\32735\.gradle\caches`，并用已安装的 Gradle 8.10.2 直接执行等价的 `:shared:knowledge-api:test` / `:data:sqlite:test`，以 `-Porg.gradle.java.installations.paths=...\jdks\eclipse_adoptium-17-amd64-windows.2` 指定 JDK 17。两次 exit 0。本机 schannel 后端无法握手（`SEC_E_NO_CREDENTIALS`），推送改用 `git -c http.sslBackend=openssl`，凭据由已认证的 `gh` 提供；仅推送源码提交，docs 提交未推送。

新增回归：

- `symbolBuiltInEncodingMapsGreekInsteadOfLatinBytes`：`/BaseFont /Symbol` 的 `(abg)` 得到 `αβγ`、不含 `abg`、`needsVision=false`、无 PAGE 阻断。
- `zapfDingbatsBuiltInEncodingFailsClosedInsteadOfPublishingLatin`：ZapfDingbats 内置编码不发布 Latin 文本，该页 `needsVision=true` 且保留 PAGE 阻断。
- `reimportOfTextOnlyGapDocumentKeepsGapStatusInsteadOfSilentlyBecomingReady`：同一 Markdown blob 显式 `READY_WITH_VISUAL_GAPS` 后再次导入仍为 `READY_WITH_VISUAL_GAPS`、`isCompleteSuccess=false`、`visualGapsAccepted=true`，且未新增 `document_versions`（走复用而非重解析）。
- `reimportOfReadyDocumentStillReusesAsCompleteReady`：完整 READY 文档复用仍为 READY，防止过度收紧。

审查夹具交叉核对（生产 `PdfParser.parse` 直接读取审查包 `fixtures/*.pdf`，JVM 21）：

```
symbol_builtin | needsVision=false | text=KEEPTOKEN αβγ
winansi        | needsVision=false | text=Price: €10
macroman       | needsVision=false | text=café
font_restore   | needsVision=false | text=XYZ ABC
control_nested | needsVision=false | text=TITLE BODY: (nested) KEEP THIS SENTENCE.
```

即审查包 `reference-results.json` 的 pypdf/PyMuPDF 参考值（`symbol_builtin` 为 `KEEPTOKEN\nαβγ`）现由生产解析器一致返回。

## 未建立

- 未跑全仓 `check`、Android 设备测试、真实 Vision 或用户 294 原件；本轮只做 JVM 定向回归。
- 不承诺全部 PDF 字体；除 Symbol 外的内置编码（ZapfDingbats 等）按 fail closed 进入 Vision。
- 未对 UI 做截图或人工验证；复用状态一致性由仓储层测试与 `job.stage`/`document_versions.status` 同源断言支撑。
- 本轮修复本身尚未经过新一轮独立只读审阅；如需按安全边界口径收口，应另起独立复审。