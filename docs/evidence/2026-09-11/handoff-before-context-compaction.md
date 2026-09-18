<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-09-11T11:05:00+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

按 [agent.md 第 1 节](agent.md#1-按任务读取与开工) 选择资料。现行规则见 agent.md；本文件记录现场和待办，历史任务中的授权不自动延续。

## 1. 现行状态与当前任务

- 当前任务：**2026-09-11 4f0556d 复审修复（PDF 字典词法解析 + ZIP 正文 CRC）JVM 定向回归通过，源码已推送、docs 仅本地**；无活跃子任务。用户已授权本轮 commit + push 源码，未授权部署或发布；`HANDOFF.md` 与 `docs/` 按既有约定只本地 commit、不 push。不要把 `.tmp-diag3/`、`.workbuddy/`、`.tmp-review-*`、`.tmp-push-wt*/`、`.tmp-gradle-home/`、`.tmp-gh-cache/`、`AGENTS.md`/`agent.md` 的本地规则 WIP 加入提交。
- Git：实际根目录 `E:/mobileAgentRuntime`，分支 `codex/user-qa-fixes`。已推送：`c43a54aafc4e81956a99014dbf887caf120b2f4e`（`origin/codex/user-qa-fixes`，父提交 `4f0556d`，即 4f0556d 复审修复那一轮）；其前为 `4f0556dcb016700fb2b6a02854e8ceae4324aec2`（父提交 `7500ad3`）。仅本地提交：其上的 docs 提交（含 `HANDOFF.md`/`docs`，不 push）。本轮源码改动：`shared/knowledge-api/.../PdfParser.kt`、`shared/knowledge-api/.../KnowledgeArchive.kt`、`shared/knowledge-api/.../DocumentParserTest.kt`、`data/sqlite/.../KnowledgeRepositoryTest.kt`；作者/提交者均为 `luozhibai <wy3273564266@163.com>`，无 Cursor trailer。上一轮源码改动：`shared/knowledge-api/.../PdfParser.kt`、`shared/knowledge-api/.../DocumentParserTest.kt`、`data/sqlite/.../KnowledgeRepository.kt`、`data/sqlite/.../KnowledgeRepositoryTest.kt`；作者/提交者均为 `luozhibai <wy3273564266@163.com>`，无 Cursor trailer。上一轮本地 docs 提交 `ddd9f50` 已并入当前 docs 提交，文档内容逐字节保留。
- 已修复（本轮，依据审查包 `mobile-agent-runtime-4f0556d-review-evidence.zip`）：① PDF 字典改为词法解析：`findTopLevelValueStart` 只认当前层级键、按键值交替推进、整体跳过注释/字面量串/嵌套结构/间接引用，`dictionaryEnd`/`arrayEnd` 同步跳过；`arrayBody` 区分缺失/畸形/合法空，畸形 `/Differences` fail closed 进入 Vision（旧实现六个词法/层级变体都误发布 `ABC` 且 `complete=true`）。fingerprint `pdf-text-v14-pdfrenderer`。② 文件型 ZIP 在交给 `onEntry` 前核对实际解压字节的尺寸与 CRC-32（两个头声明的 CRC 相同不能证明正文未改）。CI `34551014140` 已确认上一轮 `-Xmx4g` 修复使 `reviewGate` 通过。- 上轮（7500ad3 复审）已修复：① 字典**键**按 32000-1 7.3.5 解码后再匹配，新增 `findPdfKeyEnd`/`skipLiteralString`，`/Enc#6Fding`、`/Diff#65rences`、`/Fil#74er` 与字面写法等效（此前会被判缺键，静默丢弃 `/Differences` 或跳过 FlateDecode，把错误字节当完整文本）。fingerprint `pdf-text-v13-pdfrenderer`。② 主 CI `check` 失败根因确认为 review 变体 D8 外部 dex 合并 `OutOfMemoryError`，`gradle.properties` 的 `org.gradle.jvmargs` 提升为 `-Xmx4g`，门禁步骤未减未放宽；本机未跑 Android `reviewGate`；CI 重跑 `34551014140` 已确认该步骤 **success**。- 上轮（903c33e 复审）已修复：① 简单字体无 (Base)Encoding 时按 `/BaseFont` 解析内置编码，`/Symbol` 走 Adobe Symbol 基表、`ZapfDingbats` 与未知内置编码 fail closed 进入 Vision；parser fingerprint 升至 `pdf-text-v11-pdfrenderer`。② `isPublishedReady()` 改为 `publishedReadyStage()`，同 blob 再导入复用 `READY_WITH_VISUAL_GAPS` 版本时保留该状态、`visualGapsAccepted`、`hasImages` 与文本降级说明，不再静默升级为 READY 或上传补图；完整 READY 复用不变。
- 上轮（dbceaa2 复审）已修复：① 新增 `decodePdfName`，按 PDF 32000-1 7.3.5 解码名称 `#xx`，统一用于 `/BaseFont` 等名称值、内容流名称、资源字典条目名、`Do` 操作数名与 `/Differences` 字形名，`/Sym#62ol` 与 `/Symbol` 一致解析。② `builtInBaseEncoding` 取消 catch-all：`Symbol` 走 Symbol 基表，`ZapfDingbats` 与任何其它未知名 fail closed，仅 base-14 十二个拉丁文字面按 STANDARD。fingerprint `pdf-text-v12-pdfrenderer`。
- 更早各轮（f24f5ae / fd87a80 复审）已修复并保持：WinAnsi/MacRoman/Standard 基表与 `/BaseEncoding`+`/Differences`；未映射字节 `complete=false`；内容流 `q`/`Q` 保存/恢复字体；PAGE 阻断只跳过该页 JPEG，其它 needsVision 页仍可处理，缺证据失败关闭。审查包 A 项（混合页）复核为 Ok。未改 Vision 适配器。
- 验证：`DocumentParserTest` **56/56**、`:shared:knowledge-api:test` **99/99**、`:data:sqlite:test` **201/201**（合计 300 tests，0 failure/error/skipped；`clean` + `--no-build-cache` 干净构建），`licenseGuard --dependency-verification=strict --no-daemon` **PASS**（均为 2026-09-10，exit 0）。生产 `PdfParser.parse` 直接复核审查包 `fixtures/*.pdf`：`symbol_builtin`/`escaped_symbol` 均得 `KEEPTOKEN αβγ`，`zapf_builtin` 为不完整 + PAGE 阻断，七个控制项不变；未知字体用等价生成 PDF 复核为不完整 + PAGE 阻断。环境与沙箱变通见 [dbceaa2 复审证据](docs/evidence/2026-09-10/review-dbceaa2-builtin-font-boundary.md)。未跑全仓 check、设备测试、真实 Vision 或 294 原件。
- `rebuildIndex()` 仍从已存 chunks 重建。过期 fingerprint 的升级路径是同 blob 再导入。完整 K06、物理 Wired USB/OEM、正式发布仍未验收。下一步：真机验收与发布仍需单独授权。本轮修复本身尚未经过新一轮独立只读审阅。**复现提醒**：fingerprint 为 `const val` 会被内联，改值后本地复现须 `clean` 或 `--no-build-cache`，否则会看到陈旧内联导致的假失败。

## 2. 持续保护边界

- 第一方代码、文档和服务保持 `AGPL-3.0-only`，保留 license guard、SPDX、第三方归属及供应链门禁。
- `HANDOFF.md` 和 `docs/` 按既有约定仅本地保存，不推送。新任务的提交、推送、部署、正式签名、发布和付费调用必须有对应授权。
- 产品范围仍以已确认需求为准：Root、应用内无线 ADB、DPC、Termux、PTY、Accessibility、宿主 PowerShell/宿主 shell 不属于当前产品范围；不得因整理文档扩大范围。
- SAF 是独立 workspace backend；Shizuku/Wired 是独立 Authority，不自动 fallback。未知外部结果不得自动重放。秘密、URI、设备路径及配对材料的保护沿用当前专题规范。
- 用户已将 UI 重做留作独立任务；功能修复不能冒充 UI 验收。

## 3. 保留的未决事项（上轮记录，需按任务复核）

下表保留既有未决事项；未单独注明 2026-09-08 核验的“本轮”、历史 SHA 和验证状态均指原记录时间。本轮只更新与第 1 节修复及远端检查有关的状态，未据旧记录继续无关产品任务。后续处理某项时核对当前实现及证据；全部旧记录仍可在快照追溯。

| 项目 | 状态与下一步 |
| --- | --- |
| P0 统一 Workspace canonical flow | **源码已推送 `2d35933`，文档仅本地**。剩余：① Agent 草稿 attach 先入图书馆，与 `saveWithPrompt` 不是同一 SQLite 事务，靠 `rollbackNewAgent` 补偿；② Global Picker 走 `WorkspacePickerPort`/`planFor` 而非 `CanonicalWorkspaceCoordinator` 类（intent 语义相同）；③ `WorkspaceAccessPort.attach*` 仍给内部/遗留测试用，Agent 编辑页不再调用；④ Agent 编辑页仍保留 grant preset / default dropdown 作为已有 workspace 上的高级授权，不是第二套 attach；⑤ 本轮未做独立安全复核、全仓 strict gate、REUSE、物理 Wired USB |
| Thread workspace immutable / Provider 连接集成 | **源码已推送 `17a695e`，文档仅本地**。已绑定 Thread 切 workspace = 新 Thread。Insets = `IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED`。Workspace UI presentation 卸载丢失仍可接受，fallback 已接上。真机 punch-hole 与物理 Wired USB 仍待验证 |
| 当前 dirty tree | 源码已推送 `903c33e`（`codex/user-qa-fixes`）。HANDOFF/`docs` 仅本地提交、不推送。不要把 `.tmp-diag3/`、`.workbuddy/`、审查包解压目录加入提交 |
| UI 设计语言 | 用户已明确当前重构不符合要求；本轮按要求停止 UI 改造，只修功能。后续作为独立任务重新梳理交互与视觉，不得把本轮功能修复视为 UI 验收通过 |
| F-001 工具能力开关历史崩溃 | `candidate_intermittent`，不能因暂未复现关闭。再次出现时记录 APK SHA、时间和步骤，导出应用内诊断 ZIP；原生崩溃或系统强杀另取完整 Logcat |
| 物理 USB Companion | `E2E_BLOCKED`：需要真实 Windows USB 设备、配对、物理断连和恢复验证；不得用模拟器替代 |
| OEM/非模拟器差异 | `E2E_BLOCKED`：需要物理设备上的 DocumentsProvider、Shizuku Binder death/rebind 与权限撤销验证 |
| K06 大负载 | **2026-09-10 定向修复回归 PASS，完整 K06 未验收**：294 PDF 入库不再崩溃；最终 294 页数核对与原生四轮内存通过。含图文件未全部付费 Vision/READY；ENOSPC、温控/耗电及 Android 15/16 长时配额矩阵仍未验收 |
| 真实 Provider / Skill 用户验收 | **2026-09-10 本轮定向回归已收尾**：结果见第 1 节与最终修复证据；不代替所有外部服务、模型或真机路径验收。旧报告保留历史 NEEDS_AMEND |
| 正式 Android release | 未授权：正式包名/品牌、release keystore、AAB、商店发布与后检后置 |
| 推送后 CI | **2026-09-11（`4f0556d`）**：ci `34551014140` **success**（6/6 job 全绿，含此前 OOM 的 `check` job 及其 `Review-like non-debuggable APK/SBOM/provenance gate` 步骤），license-guard `34551014020` **success** —— 上轮 `-Xmx4g` 修复已由 CI 确认。**本轮（`c43a54a`，4f0556d 复审修复）**：ci `34576879237` **success**（7/7 job，含 `check` 全 19 步与 `Review-like non-debuggable APK/SBOM/provenance gate`）、license-guard `34576879212` **success**。**2026-09-11（`7500ad3`）**：license-guard `34494819479` success；ci `34494819391` **failure** —— `check` job `102930452830` 的 review 变体门禁 D8 外部 dex 合并 `java.lang.OutOfMemoryError`（根因与修复见本轮证据）。上一条 `dbceaa2` 的 license-guard `34476278614` 为 **success**；其 ci `34476278584` 自 12:21Z 起长时间停在 **in_progress**（updatedAt 12:21Z，疑似排队/卡住），同样不得记为通过。2026-09-08 09:02+08:00 `19b5dc3` 的 CI `34174287929`、license-guard `34174287949` 已 PASS。普通 push 下 `Signed release gate (manual only)` skipped 是预期行为 |
| 收敛轮独立只读复核 | 2026-09-08 对本轮版本/factory 修改执行独立只读审阅，结果见本轮证据；不据此宣称此前全部授权/持久化/native 并发改动均已完成独立审计 |
| 收敛轮新增 device 测试执行 | 2026-09-08 核验：`7ef48f1` 远端 convergence-device API 36 **30/30 PASS**，关闭待观察项。本轮本地另跑 Shizuku 版本/文件 store 与 RunTools replay 定向 **30/30 PASS**；不替代物理设备验收 |
| RunCoordinator 完整抽离 | **部分**：prepare/owner/stamp/cancel/release + manifest 已接入 Chat；流式/tool-loop 收集器与 `cancel()` 的 durable 写入仍在 VM（后者为故意保留：提前写 CANCELLED 会覆盖 dispatch 中的 UNKNOWN 判定）。本轮按用户要求不重开 |
| model.invoke 编辑器控件 | **未做**：manifest + fail-closed + 费用告知先行；Agent 编辑器可见配额控件待后续任务 |
| 工作预算（非 Internal） | **未做**：SAF/Shizuku/Wired/knowledge/Python/ZIP/picker 同原则待后续；Internal 先行并有测试。本轮按用户要求不重开 |
| 收敛轮大规模实测 | **未做**：1k/10k/50k chunks 的索引 load/内存/重建次数（`vectorIndexStats` 计数器已就绪）；真实 Provider/large KB/打孔/Wired USB 仍阻塞（同前） |
| MCP/远端 Skill 重放 | **已收敛**：接口默认改 deny；两 MCP 桥显式 deny（无已完成调用记录可比重验）；composite 转发已实现并有 JVM + device 测试。行为变更：批准后同 call 重放披露改为 deny，需新 call 走批准路径 |

## 4. 接手动作与证据

1. 依据新任务确认范围；修改前核验 Git 根目录、HEAD 和 dirty 状态，保护现有 WIP。
2. 本轮源码 HEAD 为 `903c33e`（`codex/user-qa-fixes` 已 push，父提交 `fd87a80`）。此前 PR #7 合并提交 `a933b11` 仍在 main。HANDOFF/`docs` 仅本地提交，不推送；未部署。
3. 按 agent.md 分级读取相关需求、实现方案、专题、ADR 和验收项；不重新执行历史任务或机械通读历史快照。现行证据入口：[4f0556d 复审](docs/evidence/2026-09-11/review-4f0556d-dictionary-lexicon-and-zip-crc.md)；上一轮：[7500ad3 终轮复审](docs/evidence/2026-09-10/review-7500ad3-final-keys-and-ci.md)；更早：[dbceaa2 复审](docs/evidence/2026-09-10/review-dbceaa2-builtin-font-boundary.md)、[903c33e 复审](docs/evidence/2026-09-10/review-903c33e-symbol-builtin-and-gap-reuse.md)。
4. 旧交接全文、历史测试和产物索引见 [整理前快照](docs/evidence/2026-09-05/handoff-before-agent-rules.md)。迁移原文中的相对链接以原 `E:\mobileAgentRuntime\HANDOFF.md` 所在目录解析，不作为归档文件目录下的现行入口。
