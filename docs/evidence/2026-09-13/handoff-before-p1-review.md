<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-09-13T12:25:00+08:00（Asia/Taipei）。项目根目录：E:/mobileAgentRuntime。

按 [agent.md 第 1 节](agent.md#1-按任务读取与开工) 选择资料。现行规则见 agent.md；本文件记录现场和待办，历史任务中的授权不自动延续。

## 1. 现行状态与当前任务

- **2026-09-13 P1 知识库批次导入（整体进度 / 批次级视觉授权 / 暂停续跑）：本地修复，未提交、未推送、未真机验收。**
  依据 `mobile-agent-runtime-p1-kb-batch-import-prompt.md` 交接包：诊断复算仍只说明"导入启动与批次 Worker 启动存在"，
  没有授权接收/票据消费/视觉请求证据。代码级可复现断点：逐文件视觉票据绑定**整库** documents 指纹，而批次自身每发布
  一项就改写 `active_version_id`，授权在 Worker 消费时已作废且不产生请求（JVM 复现见新增测试）。已改：
  ①创建页一次动作授权本批次已选成员（`vision_target` + `vision_scope_hash`，成员表变化即失效、不扩张）；
  ②默认只有一张总进度卡，完成度只由 `published` 计算，逐项明细折叠；③`PAUSED`/`BLOCKED` 持久化状态 + 暂停/继续/
  "配置并继续"，无视觉目标时整批阻塞并停止派发后续成员，纯文本不预拦截；④已发布内容复用、UNKNOWN 不自动重发、
  最小脱敏批次事件（仅不透明 ref + 闭合 code）。schema 18→19（仅加列）。验证：`:app-android:assembleDebug`、
  `:app-android:compileDebugAndroidTestKotlin` PASS；`:data:sqlite:test` 226/0，含新增 `KnowledgeBatchVisionTest`
  9 项（真实本地 HTTP 上传服务计数 + 持久状态断言）。**未做**：物理手机、真实/付费 Provider、294 份全量、
  androidTest、CI 门禁；生产 Ktor 视觉适配器未被自动测试覆盖。报告：`docs/evidence/2026-09-13/p1-batch-import-vision-authorization.md`。
  契约变更：批次"等待视觉模型"由 `WAITING` 改为 `BLOCKED(NEEDS_VISION_MODEL)`，`KnowledgeArchiveImportTest` 已同步。
- **2026-09-13 第二轮代码审查 + 完整模拟器用户测试已收尾：NEEDS_AMEND；随后按用户授权完成 R2-01—R2-07 源码修复并推送（`4d7f857`，`codex/user-qa-fixes`）。** 被测 main `0c02b32e06f9f5ace7a0cfdf44bb7725898ee1a3`；隔离 review APK SHA-256 `35dd646ba74cb2c940a23a7675a50992a4f08b5fa005966f41f542a411f83d61`。本地/DSH 只读审查（Flash、工作区内修改权限以避免仅查看 Pwsh 问题）后，执行前轮 42 项模拟器路径并额外对照 SF/DS。完整 `reviewGate :desktop:bridge:installDist --dependency-verification=strict --console=plain` PASS，JVM 汇总 928 项（含变体重复）无失败。官方 DS 连接、流式及两次工具调用通过；确认含会话备份导入 Android NoSuchMethodError 崩溃、DS/SF 能力探测假阴性、ACK 压缩边界失败、空 SAF 读工具缺口、Companion fileKey=null、第三方声明路径拒绝。资源-only 恢复、RAG/Python、实际 Shizuku shell 与断连错误通过；撤回早期“全局 shell grant 必然不能暴露”的判断。294 PDF 仅完成导入与持久化，未全部 Vision/READY；物理 USB、Brave/MCP、空目标裸包恢复未验收。报告：[第二轮代码审查与完整用户 QA](docs/evidence/2026-09-12/round2-code-review-and-full-user-qa.md)；私有证据 `.private/userqa-round2-20260912/`。已关闭危险模式、撤销测试 SAF、停止 owned Shizuku/Logcat/模拟器；DSH 和 AVD 数据保留。仅更新本报告与 HANDOFF，产品源码/配置/ADR 无变化，无需改写技术专题或验收规范；没有提交、推送、发布。

- **2026-09-13 第二轮 QA 修复（本轮，源码已推送 `4d7f857`）。** 按报告逐项修复：R2-01 `TransferRepository` 用有界流式回读替代 Android 缺失的 `Files.readString(Path)`；R2-02/R2-05 能力探测改走真实 `buildPayload` 并合并 Profile 模型参数，强制 `tool_choice` 被 4xx 时去 `tool_choice` 重试并区分探测不兼容与不支持；R2-03 压缩候选按完整轮次或自包含工具交换选取，无信息候选不发起摘要请求，摘要失败记录可诊断分类；R2-04 `readGranted` 的 SAF 树（含空目录与全虚拟目录）始终声明 `file_read_text`；R2-06 adb.exe 身份改用 provider key 或 Win32 `FILE_ID_INFO`，保留 Windows 拒绝退化身份与内容 SHA-256 复核；R2-07 声明允许路径对齐 `licenses/`+`modelpacks/` 并逐项容错。验证：受影响模块编译 + `:app-android:compileDebugAndroidTestKotlin` PASS；JVM **544 tests / 0 failures / 0 errors / 0 skipped**（provider-api 105、agent-runtime 52、serialization 13、data:sqlite 218、domain 35、knowledge-api 103、desktop:bridge 18）；`ContextCompactionRuntimeTest` 新增用例在还原旧候选选择时失败、修复后通过。未执行：模拟器 androidTest（沙箱拒绝 emulator 锁文件）、真实 Provider 复测、reviewGate/licenseGuard/REUSE/CI、物理 USB。 已提交 `4d7f8578319b305424d1e538478581b8c7645456`（parent `0c02b32`）并推送 `origin/codex/user-qa-fixes`（`214df5f..4d7f857`，非 force，仅 12 个源码/测试文件）；经 PR [#13](https://github.com/hedanbaomi/mobile-agent-runtime/pull/13) 合并入 `main`（merge commit `b93a981`，分支 CI check / Android UI smoke API 31/34/35/36 / Convergence API 36 / license 全绿）；`HANDOFF.md`、`docs/`、`AGENTS.md`、`agent.md` 保持本地未提交、未推送。证据：[第二轮 QA 修复](docs/evidence/2026-09-13/round2-qa-fixes.md)。

- **2026-09-13 复核修订（NEEDS_AMEND 后重新修复）。** 依据复核证据包修复两项 P2 探测缺陷与一项 CI 缺口：①探测预算（不超过 64）不得拒绝模型中合法的默认输出上限，新增 `probeParameterLayers` 只把 profile 已使用的那一个输出上限字段钳制到探测上限（1024→64、更省的 32 保留、缺失时仍注入 64；双字段或非法值交共享 merger 拒绝），`testConnection` 同步；②强制 `tool_choice` 的去留重试只在请求形状类 4xx（排除 401/403/408/429）触发；③CI `convergence-device` 选择器补入 `TransferArchiveDeviceTest`、`ThirdPartyNoticesDeviceTest`、`workspace.WorkspaceBackendTest`。同时把 R2-01 device 回归改为源库导出后导入全新空库，`DesktopBridgeTest` 新增 Windows 原生 `FILE_ID_INFO` 身份用例。验证：provider-api 111 / agent-runtime 52 / data:sqlite 218 / desktop:bridge 19 = 400 tests 全绿。证据见 [第二轮 QA 修复](docs/evidence/2026-09-13/round2-qa-fixes.md) 第 8 节。
- **2026-09-13 复核修订已推送并合并。** 复核证据包确认的两项 P2 探测缺陷与 CI 覆盖缺口已修复：源码提交 `f509083`（parent `b93a981`，5 个文件）推送 `origin/codex/user-qa-fixes`（`4d7f857..f509083`），经 PR [#14](https://github.com/hedanbaomi/mobile-agent-runtime/pull/14) 以普通 merge commit 合并入 `main` = `7caebd3`（分支 CI check / Android UI smoke API 31/34/35/36 / Convergence API 36 / license 全绿）。`main` 相对 `b93a981` 的差异恰为这 5 个文件；`HANDOFF.md`、`docs/` 仍未提交、未推送。


- **2026-09-13 debug APK 交付（本地构建）。** `:app-android:assembleDebug` BUILD SUCCESSFUL（2m20s）。产物：`.private/manual-test/20260913-main-7caebd3/mobile-agent-runtime-debug-7caebd3.apk`，204.34 MB，SHA-256 `e3754f7a479cad59fe278c22a032f5e5331c16d68f2759c5848d8a341197aa2f`；`runtime.mobileagent` 0.1.0 (code 1)，minSdk 26 / targetSdk 35，arm64-v8a + x86_64，`application-debuggable`，签名 `CN=Android Debug`（SHA-256 `315148930a…b788`）。源码内容与 `origin/main` `7caebd3` 一致。本机沙箱无法启动模拟器，未做安装/启动冒烟；同目录 `build-info.txt` 记录安装命令。

- **2026-09-12 16fe3a4 复审修订：源码已提交并入 main。IMPLEMENTATION STATUS: PARTIAL。** 源码 `214df5fca90092038aaf1905dc5a6c23db3ef93c`（author/committer `luozhibai`），2 个源码/测试文件，无 HANDOFF/docs。PR [#12](https://github.com/hedanbaomi/mobile-agent-runtime/pull/12) 合并为 `0c02b32e06f9f5ace7a0cfdf44bb7725898ee1a3`。GitHub：license-guard `34696538914`/`34696555938` success；ci `34696538922`/`34696555906` success（含 Convergence API 36，`Signed release gate` skipped）。main 合并后 license-guard `34697589283` / ci `34697589316` success。处理同 package id 的 v1/v2 Skill 备份恢复。该次源码提交时未做真实 DeepSeek、独立安全审阅或无人值守用户路径；本轮审查/实测见首条，不能代替独立安全审计。详情：[16fe3a4 复审修订](docs/evidence/2026-09-12/review-16fe3a4-amend.md)。
- **前一轮**：源码 `5e3590e` 已入此前 `origin/main` `16fe3a4`（PR #11）。
- **Git**：远端 `origin/main` = `7caebd3`（PR #13 合并 `4d7f857`、PR #14 合并 `f509083`；此前含 `214df5f`）。远端 `origin/codex/user-qa-fixes` = `4d7f857`（本轮源码已推送）。本地工作区仍在 `codex/user-qa-fixes` / HEAD `3f454183176b7664ff774593bd6dba396d595b2e`（仅本地文档，不得 push；本地 dirty 源码内容已等价于 `4d7f857`，不要据本地 HEAD 重推）。HANDOFF/docs/AGENTS/agent 与临时 WIP 仍只在本地。未部署；本轮新增本地 review QA APK，见首条。
- **前轮最终包与门禁**：独立干净 worktree .private/main-apk-20260911 从实际 main SHA 重建，reviewGate 退出码 0。交付见 `.private/manual-test/20260911-main-f1feffe/`，SHA-256 `4b5abdfe90b980e42039c0d618174b339db6ed8bcfd448a4ef98c33ced0ddbbd`。main CI license-guard 34613193443 / ci 34613193357 success。该包未覆盖本轮 user-QA 源码修复。
- **前轮压缩**：[72dc60a 上下文复审修复](docs/evidence/2026-09-11/review-72dc60a-context-budget-usage.md)，源码 `0ca6f1a`。真实 Provider 参数兼容性、PDF/ZIP 完整验收和旧未决项继续按其原边界保留。

## 2. 持续保护边界

- 第一方代码、文档和服务保持 `AGPL-3.0-only`，保留 license guard、SPDX、第三方归属及供应链门禁。
- `HANDOFF.md` 和 `docs/` 按既有约定仅本地保存，不推送。新任务的提交、推送、部署、正式签名、发布和付费调用必须有对应授权。
- 产品范围仍以已确认需求为准：Root、应用内无线 ADB、DPC、Termux、PTY、Accessibility、宿主 PowerShell/宿主 shell 不属于当前产品范围；不得因整理文档扩大范围。
- SAF 是独立 workspace backend；Shizuku/Wired 是独立 Authority，不自动 fallback。未知外部结果不得自动重放。秘密、URI、设备路径及配对材料的保护沿用当前专题规范。
- 用户已将 UI 重做留作独立任务；功能修复不能冒充 UI 验收。

## 3. 保留的未决事项（上轮记录，需按任务复核）

下表保留既有未决事项；未单独注明 2026-09-08 核验的“本轮”、历史 SHA 和验证状态均指原记录时间。本轮只更新第 1 节 main 合并、人工测试出包任务及当前 dirty 状态，未据旧记录继续无关产品任务。后续处理某项时核对当前实现及证据；全部旧记录仍可在快照追溯。

| 项目 | 状态与下一步 |
| --- | --- |
| P0 统一 Workspace canonical flow | **源码已推送 `2d35933`，文档仅本地**。剩余：① Agent 草稿 attach 先入图书馆，与 `saveWithPrompt` 不是同一 SQLite 事务，靠 `rollbackNewAgent` 补偿；② Global Picker 走 `WorkspacePickerPort`/`planFor` 而非 `CanonicalWorkspaceCoordinator` 类（intent 语义相同）；③ `WorkspaceAccessPort.attach*` 仍给内部/遗留测试用，Agent 编辑页不再调用；④ Agent 编辑页仍保留 grant preset / default dropdown 作为已有 workspace 上的高级授权，不是第二套 attach；⑤ 本轮未做独立安全复核、全仓 strict gate、REUSE、物理 Wired USB |
| Thread workspace immutable / Provider 连接集成 | **源码已推送 `17a695e`，文档仅本地**。已绑定 Thread 切 workspace = 新 Thread。Insets = `IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED`。Workspace UI presentation 卸载丢失仍可接受，fallback 已接上。真机 punch-hole 与物理 Wired USB 仍待验证 |
| 当前 dirty tree | 远端 main 已合并至 `0c02b32`（含源码 `214df5f`）。本地 HEAD 仍为 `3f45418` 仅文档；HANDOFF/docs/AGENTS/agent 与临时 WIP 仍 dirty，不得普通 push |
| UI 设计语言 | 用户已明确当前重构不符合要求；本轮按要求停止 UI 改造，只修功能。后续作为独立任务重新梳理交互与视觉，不得把本轮功能修复视为 UI 验收通过 |
| F-001 工具能力开关历史崩溃 | `candidate_intermittent`，不能因暂未复现关闭。再次出现时记录 APK SHA、时间和步骤，导出应用内诊断 ZIP；原生崩溃或系统强杀另取完整 Logcat |
| 物理 USB Companion | `E2E_BLOCKED`：需要真实 Windows USB 设备、配对、物理断连和恢复验证；不得用模拟器替代 |
| OEM/非模拟器差异 | `E2E_BLOCKED`：需要物理设备上的 DocumentsProvider、Shizuku Binder death/rebind 与权限撤销验证 |
| K06 大负载 | **2026-09-10 定向修复回归 PASS，完整 K06 未验收**：294 PDF 入库不再崩溃；最终 294 页数核对与原生四轮内存通过。含图文件未全部付费 Vision/READY；ENOSPC、温控/耗电及 Android 15/16 长时配额矩阵仍未验收 |
| 真实 Provider / Skill 用户验收 | **2026-09-10 本轮定向回归已收尾**：结果见第 1 节与最终修复证据；不代替所有外部服务、模型或真机路径验收。旧报告保留历史 NEEDS_AMEND |
| 正式 Android release | 未授权：正式包名/品牌、release keystore、AAB、商店发布与后检后置 |
| 历史推送后 CI（当前 main 见第 1 节） | **2026-09-11（`4f0556d`）**：ci `34551014140` **success**（6/6 job 全绿，含此前 OOM 的 `check` job 及其 `Review-like non-debuggable APK/SBOM/provenance gate` 步骤），license-guard `34551014020` **success** —— 上轮 `-Xmx4g` 修复已由 CI 确认。**本轮（`c43a54a`，4f0556d 复审修复）**：ci `34576879237` **success**（7/7 job，含 `check` 全 19 步与 `Review-like non-debuggable APK/SBOM/provenance gate`）、license-guard `34576879212` **success**。**2026-09-11（`7500ad3`）**：license-guard `34494819479` success；ci `34494819391` **failure** —— `check` job `102930452830` 的 review 变体门禁 D8 外部 dex 合并 `java.lang.OutOfMemoryError`（根因与修复见本轮证据）。上一条 `dbceaa2` 的 license-guard `34476278614` 为 **success**；其 ci `34476278584` 自 12:21Z 起长时间停在 **in_progress**（updatedAt 12:21Z，疑似排队/卡住），同样不得记为通过。2026-09-08 09:02+08:00 `19b5dc3` 的 CI `34174287929`、license-guard `34174287949` 已 PASS。普通 push 下 `Signed release gate (manual only)` skipped 是预期行为 |
| 收敛轮独立只读复核 | 2026-09-08 对本轮版本/factory 修改执行独立只读审阅，结果见本轮证据；不据此宣称此前全部授权/持久化/native 并发改动均已完成独立审计 |
| 收敛轮新增 device 测试执行 | 2026-09-08 核验：`7ef48f1` 远端 convergence-device API 36 **30/30 PASS**，关闭待观察项。本轮本地另跑 Shizuku 版本/文件 store 与 RunTools replay 定向 **30/30 PASS**；不替代物理设备验收 |
| RunCoordinator 完整抽离 | **部分**：prepare/owner/stamp/cancel/release + manifest 已接入 Chat；流式/tool-loop 收集器与 `cancel()` 的 durable 写入仍在 VM（后者为故意保留：提前写 CANCELLED 会覆盖 dispatch 中的 UNKNOWN 判定）。本轮按用户要求不重开 |
| model.invoke 编辑器控件 | **未做**：manifest + fail-closed + 费用告知先行；Agent 编辑器可见配额控件待后续任务 |
| 工作预算（非 Internal） | **未做**：SAF/Shizuku/Wired/knowledge/Python/ZIP/picker 同原则待后续；Internal 先行并有测试。本轮按用户要求不重开 |
| 收敛轮大规模实测 | **未做**：1k/10k/50k chunks 的索引 load/内存/重建次数（`vectorIndexStats` 计数器已就绪）；真实 Provider/large KB/打孔/Wired USB 仍阻塞（同前） |
| MCP/远端 Skill 重放 | **已收敛**：接口默认改 deny；两 MCP 桥显式 deny（无已完成调用记录可比重验）；composite 转发已实现并有 JVM + device 测试。行为变更：批准后同 call 重放披露改为 deny，需新 call 走批准路径 |

## 4. 接手动作与证据

1. 依据新任务确认范围；修改前核验 Git 根目录、HEAD 和 dirty 状态，保护现有 WIP。
2. 源码 `214df5f` 已入 `origin/main` `0c02b32`。本地 HEAD 仍为 `3f45418`（仅文档），不得 push 该 HEAD。不得 reset/clean 本地 WIP。本轮只读审阅、真实 DeepSeek 与模拟器完整矩阵已执行，失败及限制见第二轮报告；随后已按新授权完成 R2-01—R2-07 源码修复（本地 dirty，未提交/未推送）。剩余：模拟器 androidTest 与真实 Provider 回归、独立安全审计、物理 Wired USB；不得据历史授权提交或发布。
3. 按 agent.md 分级读取相关需求、实现方案、专题、ADR 和验收项；不重新执行历史任务或机械通读历史快照。现行证据入口：[16fe3a4 复审修订](docs/evidence/2026-09-12/review-16fe3a4-amend.md)；此前：[af505b6 复审修订](docs/evidence/2026-09-12/review-af505b6-amend.md)、[5f4fd1e 复审修订](docs/evidence/2026-09-12/review-5f4fd1e-amend.md)、[user-QA 修复 v2](docs/evidence/2026-09-12/userqa-fix-v2.md)、[无人值守 QA](docs/evidence/2026-09-12/unattended-emulator-user-qa.md)、[上下文压缩](docs/evidence/2026-09-11/context-compaction.md)。
4. 旧交接全文、历史测试和产物索引见 [整理前快照](docs/evidence/2026-09-05/handoff-before-agent-rules.md)。迁移原文中的相对链接以原 `E:\mobileAgentRuntime\HANDOFF.md` 所在目录解析，不作为归档文件目录下的现行入口。
