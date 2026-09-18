<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# P1：知识库批次导入——批次级视觉授权、整体进度与暂停续跑（本地修复报告）

> 历史 DSH 初稿：现行审查与修复验收结论已由 [P1 批次导入复核报告](p1-batch-import-review.md) 替代。以下保留当时证据和限制。

时间：2026-09-13（Asia/Taipei）。任务来源：`mobile-agent-runtime-p1-kb-batch-import-prompt.md` 及同包
`00_START_HERE.md`、`EVIDENCE_SUMMARY.md`、`SOURCE_NOTES.md`、两份真机诊断 ZIP。

> 本报告是**本地修复 + JVM 证据**，不是真机验收。**构建通过不等于上传成功**：本轮没有手机、没有
> 真实/付费 Provider、没有 294 份全量运行。凡是本轮没有实测到的，第 7 节逐条列明。

## 0. 结论速览

- 用户点「批准视觉上传」后没有可见响应的**代码级候选断点**已定位并可复现：逐文件一次性票据把授权绑定在
  **整个知识库文档指纹**上，而同一批次的后台推进（每发布一项就改 `documents.active_version_id`）会不断改写
  该指纹，于是授权在 Worker 消费时已失效且**不产生任何视觉请求**。
- 同时，原交互对每个等待文件各要一次批准；294 份里 84 份等待意味着最多 84 次逐文件授权，与「一次授权整批」
  的产品要求相冲突。本轮把批次导入改为**批次级一次性授权**，并把整批的进度、暂停、阻塞恢复收敛到一张总卡。
- 已交付：批次级授权与作用域绑定、整体进度语义、暂停/继续、阻塞/配置并继续、已发布内容复用、UNKNOWN 不自动
  重发、最小脱敏事件。验收用 JVM 测试覆盖：真实本地 HTTP 上传服务的请求计数 + 数据库持久状态断言。

## 1. 被测构建与源码基线核对

| 项 | 值 |
| --- | --- |
| 手机诊断 ZIP 标记 | `3f454183176b7664ff774593bd6dba396d595b2e-dirty`，schema 18，buildTimeUtc 2026-09-13T06:42:04Z |
| 本地仓库 HEAD | `3f454183176b7664ff774593bd6dba396d595b2e`（`codex/user-qa-fixes`）——**与 dirty 构建的提交号一致** |
| 本地工作树 | dirty（78 项未提交改动 + 本轮新增/修改），含用户既有 WIP；本轮未 reset/clean、未提交、未推送 |
| 远端 `origin/main` | `7caebd3eaef88b7282be11c6e5689408f77d2fa8`（含 PR #13 `4d7f857`、PR #14 `f509083`） |
| 远端 `origin/codex/user-qa-fixes` | `f5090832229aa03f9565edfa96e716b420dc35ec` |

无法证明手机 APK 与远端 main 字节一致；本轮所有结论都取自**本地工作树**的代码与 JVM 测试，并在报告中标明
哪些是代码可证、哪些只是候选。诊断日志复算（`analyze_diagnostics.py` 结论、`diagnostic-comparison.json`）与
交接包一致：新 441 条为旧 196 条的严格前缀 + 245 条新增（`authority_configuration_state` 135、
`authority_state_changed` 69、`shizuku_lifecycle` 20、`wired_adb_lifecycle` 13、`process_started` 2、
`authority_selection_changed` 2、`dangerous_mode_changed` 2、`batch_worker_start` 2）。新增事件里**没有**授权
动作接收、票据消费、视觉请求派发或完成。

## 2. 断点分析

### 2.1 已由代码 + 可复现测试确认

1. **逐文件票据的整库指纹门禁会被批次自身推进作废。**
   - `KnowledgeViewModel.confirmVision()` 生成票据指纹 `GRANT\n<vision-fingerprint>\n<sha256(整库 documents 指纹)>`；
     `KnowledgeRepository.validateConsentTicketLocked()` 在 Worker 侧重新计算同一指纹，不一致即
     `"consent ticket documents changed; no data was sent"`，**不发送任何图片**。
   - 但同一批次每成功发布一项都会写 `documents.active_version_id`（发布事务），指纹因此**持续变化**。
   - JVM 复现：`KnowledgeBatchVisionTest.legacyPerFileVisionTicketIsInvalidatedByOrdinaryKnowledgeBaseProgress`
     —— 授权后同库另一份资料正常解析发布，票据即被判定失效，上传计数保持 0。这正是 "点击后没有可见响应 /
     没有票据消费证据" 的合理机制（用户在几百张卡之间翻到按钮、点击，得到的结果与"无事发生"一致）。
2. **逐文件授权无法满足批次产品语义。** 等待项每项各需一次批准；`processBatch` 串行推进到第一个需要视觉的
   成员就把它置为 `AWAITING_UPLOAD_CONSENT`，其余继续排队，于是出现"处理中 210 / 等待 84 / 完成 0"的现场。
3. **进度不可信。** `refreshBatchProgressLocked` 把已复制/排队/等待一并计入 `copied`，UI 又把排队算作"处理中"，
   因此"已复制 294"与"完成 0"可以同时出现，用户无法判断真实完成度。
4. **暂停语义缺失。** 只有取消（`cancelBatch` → `CANCELLED`）而没有可持久化的暂停；WorkManager 取消会直接
   走取消路径，重启后 `recoverableBatchIds()` 只认 STAGING/COPYING/PROCESSING。
5. **等待项不区分"缺模型"与"缺授权"。** `WAITING_FOR_VISION_MODEL` 与 `AWAITING_UPLOAD_CONSENT` 都映射成
   `ImportItemState.WAITING`，UI 一律显示"等待视觉模型"，批次状态也只是 `WAITING`（不是"阻塞：需要视觉模型"）。

### 2.2 仍无法确认（不得当作已证实根因）

- 用户那次点击到底走没走到 `confirmVision`、是否弹出了 `VisionConsentDialog`、ConsentWorker 是否被调度：
  诊断里没有对应事件，也没有任务表快照。因此**不能**断定"票据已生成但没有消费"。
- `readOnIo` 缺少该动作专用 loading/超时（源注释已确认），在真机上是否存在 DB 读等待、锁竞争或 UI 未重组
  导致"点击无反应"，本地无法验证。
- `last-crash.ndjson` 为空、manifest 报 healthy **不能**排除强杀/OOM/未记录错误。
- 是否曾经发生过真实外发：日志既不支持"成功"也不支持"绝对没有"。

## 3. 用户路径（本轮改了什么）

1. **创建即授权（一次动作）。** 选完文件/ZIP/目录后弹出「创建并开始导入」：显示本批次选定资料、所选视觉目标、
   可能外发的内容（只有确实含图且本机解析覆盖不了的页/图）、费用提示。确认后这一动作授权**该批次已选成员**的
   视觉处理；`visionTarget` 为 `null` 表示"没有视觉目标也先跑纯文本"。
2. **默认只有一张总进度卡**（每个批次一张）：阶段文案、`已完成 published/total (percent)` 进度条、以及
   `已复制 / 处理中 / 排队 / 等待 / 失败 / 待确认` 的次级明细；逐项卡片、技术字段与错误移到用户主动
   「展开 N 项详情」。总进度**只用 published** 计算，绝不用 copied 冒充完成。
3. **暂停 / 继续。** 卡片上「暂停」先写持久化 `PAUSED`（再让 WorkManager 停 worker），显示"已暂停"且不再有
   运行中动画；「继续导入」从检查点原地续跑。暂停不删资料、不改模型配置、不重置进度，**重启后仍是暂停**。
4. **阻塞 / 配置并继续。** 没有可用视觉目标而实际遇到需要视觉的内容时，整批置为 `BLOCKED`
   （`blockedReason = NEEDS_VISION_MODEL`），停止调度后续成员，已完成内容保留；卡片变为错误色并提供
   「配置并继续」（= 选定目标并授权该批次后原地续跑）与「配置视觉模型」。
   目标变更则 `VISION_TARGET_CHANGED`，需重新确认，不会静默漂移到新目标。
5. **纯文本不预拦截。** 没有视觉目标时纯文本照常本地处理、发布、可检索。
6. **显式文字降级仍是高级选项**（逐项「仅使用文本」），且如实标注视觉缺口，不作为默认降级。

## 4. 状态 / 授权 / 恢复语义

- 新增 `ImportBatchState.BLOCKED`；`PAUSED` 与 `BLOCKED` 都是**持久化用户可见状态**。
  `refreshBatchProgressLocked` 不会把 `PAUSED` 改回运行态；`BLOCKED` 只在出现终态（全部完成/失败/取消）或
  显式授权时才离开。
- **授权绑定**：`import_batches.vision_target`（`VisionBinding.fingerprint`）+ `vision_scope_hash`
  （= `sha256` over 该批次 `(item_key, document.blob_hash)` 有序成员表）+ `vision_authorized_at`。
  作用域**不含** generation、parser 指纹或 `active_version_id`，所以批次自身的正常推进不会作废授权；
  但**新增成员会改变 scope hash** → 旧授权立即失效，不会悄悄扩张到新加入的资料。
- **成员级落地**：`applyBatchVisionAuthorization(job)` 只在 `batch_id` 存在、`vision_authorized_at` 非空、
  `vision_target == 当前 visionFingerprint()`、`vision_scope_hash == 当前 scope` 时才把授权落到该 job 上；
  它明确跳过 `UNKNOWN_OUTCOME` 的 job。**没有任何"把所有 `job.visionConsent` 设 true"的路径**。
- **暂停的安全性**：`pauseBatch` 先把 `PROCESSING` 且**未越过外发边界**（local stage，且 error 不含
  `UNKNOWN_OUTCOME`）的项退回 `QUEUED`；已派发结果未知的项保持原样。`claimNextBatchJob` 对
  PAUSED/BLOCKED/CANCELLED/COMPLETED/FAILED 一律不再取件。
- **取消 vs 暂停**：`pauseBatch` 先落 `PAUSED` 再请求 WorkManager 取消，worker 的取消钩子到达
  `cancelBatch` 时看到 `PAUSED` 即返回，不会把暂停变成取消。
- **重启恢复**：`recoverableBatchIds()` 仍只重建 STAGING/COPYING/PROCESSING；`PAUSED`/`BLOCKED` 不会被自动
  拉起，必须由用户「继续导入 / 配置并继续」。
- **不重复派发**：`processBatch` 末尾对终态批次再次运行是空操作；已 `PUBLISHED` 的成员不会被重新取件；
  `reused` 文档（内容不变且已有已发布版本）直接以 `PUBLISHED` 进入批次，不产生新的视觉请求。
- **UNKNOWN 不复用不重发**：视觉结果不确定时保持 `FAILED + UNKNOWN_OUTCOME`，只提供显式「重试（可能重复收费）」
  入口（`retryUnknownVision(acknowledgeDuplicateCharge = false)` 仍会拒绝）。

## 5. 持久化与 schema

- `Migrations.VERSION` 18 → **19**（列增量迁移，不删数据）。`import_batches` 新增：
  `vision_target`、`vision_scope_hash`、`vision_authorized_at`、`paused_at`、`blocked_reason`、
  `published_items`、`unknown_items`；并加入 `REQUIRED_COLUMNS` 校验。
- `ContextCompactionRepositoryTest` 中写死的 `18L` 改为 `Migrations.VERSION.toLong()`。
- 新增 `ImportBatchProgress`（派生只读进度）与 `ImportBatchItemView`（仅 UI 明细用，不进诊断）。

## 6. 实际跑过的测试与证据

命令（全部 `--offline`）：

- `:app-android:assembleDebug` → **BUILD SUCCESSFUL**
- `:app-android:compileDebugAndroidTestKotlin` → PASS
- `:data:sqlite:test` → **228 tests / 0 failures**
- 受影响 JVM 套件合计：`:data:sqlite` 228、`:shared:knowledge-api` 103、`:shared:provider-api` 111、`:shared:agent-runtime` 52、`:shared:domain` 35、`:shared:serialization` 13、`:desktop:bridge` 19、`:app-android:testDebugUnitTest` 78 → **639 tests / 0 failures**
- 其中新增 `KnowledgeBatchVisionTest`（10 项，全部 PASS）：

| 用例 | 断言要点 |
| --- | --- |
| `oneBatchAuthorizationUploadsEveryVisualMemberOnce...` | 2 份含图 PDF + 1 份文本，一次批次授权 → 本地 mock 上传服务**只**收到 2 次请求；批次 `COMPLETED`；`published == 3`；`vision_results == 2`；`consent_tickets == 0`；文本与视觉结果都能 `search` 到 |
| `batchWithoutAConfirmedTargetBlocksAtTheFirstVisualMember...` | 无目标时第一个需视觉成员后整批 `BLOCKED(NEEDS_VISION_MODEL)`；第 3 项仍是 `COPYING`（**未派发**）；`uploads == 0`；授权后原地续跑 → `COMPLETED`，`uploads == 1` |
| `requestedDestinationOtherThanTheConfiguredOneAuthorizesNothing` | 目标不符 → 授权抛错、`batchVisionAuthorization == null`、批次阻塞、`uploads == 0` |
| `addingAMemberInvalidatesTheAuthorizationInsteadOfWideningIt` | 新增成员后 scope hash 变化 → 旧授权为 `null` |
| `pauseStopsDispatchAndResumeContinuesWithoutDuplicatingUploads` | 暂停期间 `uploads == 0`、`recoverableBatchIds()` 不含它（重启不复跑）；继续后完成且 `uploads == 2`；再次 `processBatch` 仍为 2 |
| `uncertainVisionOutcomeIsPersistedAndNeverAutoRetried` | `FAILED + UNKNOWN_OUTCOME`，`unknown == 1`，再跑 worker 后端调用次数仍为 1；无 ack 的重试被拒绝 |
| `alreadyPublishedVisualPageIsReusedInsteadOfReuploaded` | 同字节再入批 → 直接 `COMPLETED`，无新增上传、无新增视觉结果行 |
| `batchLifecycleEventsCarryOnlyOpaqueRefsAndClosedCodes` | 事件含 `AUTHORIZATION_SAVED/STARTED/CHECKPOINT`；渲染串**不含**真实文件名，也不含 `/`；reasonCode 有界 |
| `legacyPerFileVisionTicketIsInvalidatedByOrdinaryKnowledgeBaseProgress` | 复现第 2.1(1) 条：授权后同库正常发布即令票据失效，`uploads == 0` |
| `progressNeverReportsCopiedBytesAsCompletion` | 仅复制完成时 `copied == 2` 但 `published == 0`、`percentComplete() == 0` |

- 契约更新：`KnowledgeArchiveImportTest` 4 处把"等待中批次 = `WAITING`"改为 `BLOCKED`（这是本轮**有意**的产品
  变更，见第 3、4 节），并新增"阻塞时后续成员保持 `COPYING` 不被派发"的断言。
- 其余受影响模块 JVM 套件（`shared:knowledge-api`、`shared:provider-api`、`shared:agent-runtime`、
  `shared:domain`、`shared:serialization`、`desktop:bridge`、`:app-android:testDebugUnitTest`）结果见
  `p1-batch-import-vision-authorization-jvm-tests.log`（同目录）与各模块 `build/reports/tests`。

诚实的证据边界：mock 上传服务是**真实本地 HTTP 服务**，计数来自服务端实际收到的请求体；但它替代的是生产
`OpenAiCompatibleVision`（该适配器需要 Android Keystore + 真实凭据，JVM 无法构造）。因此本报告证明的是
**仓库/状态机/上传触发链**的正确性，不是生产适配器的字节级行为。

## 7. 未测范围与风险

- **未做**：物理手机复测、真实/付费 Provider、294 份全量运行、HTTP 抓包、Provider 后台对账、SQLite 导出、
  Android instrumented（androidTest）执行、CI/reviewGate/licenseGuard/REUSE。
- `OpenAiCompatibleVision` 的 Ktor/OpenAI 适配器路径未被自动测试覆盖（需要真机凭据）。
- 手机 dirty 包与本地工作树是否逐字节一致无法证明。
- UI 只有编译级验证；Compose 交互（展开/暂停/继续的视觉与点击反馈）需要真机或 Robolectric 复核。
- 2.2(1) 的点击路径仍未被设备端证据闭环：若真机断点不在票据指纹上（例如读等待/UI 未重组），本轮修复同样
  会通过"批次级授权 → 不依赖票据与整库指纹"绕过它，但**这属于消除症状类别，不等于已证实那次点击的根因**。
- `pauseBatch` 依赖"先写 PAUSED 再取消 WorkManager"的顺序；若未来有调用方直接取消 work 而不先落暂停，
  会退回取消语义（现有 App 路径不会）。

## 8. 复现与验证命令

```
./gradlew.bat --offline :data:sqlite:test --tests "runtime.mobileagent.data.KnowledgeBatchVisionTest"
./gradlew.bat --offline :data:sqlite:test
./gradlew.bat --offline :app-android:testDebugUnitTest
./gradlew.bat --offline :app-android:compileDebugAndroidTestKotlin
./gradlew.bat --offline :app-android:assembleDebug
```

本轮**没有** commit / push / 部署 / 付费调用；`HANDOFF.md`、`docs/` 仍按既有约定只保存在本地。
