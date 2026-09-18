<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 架构收敛与隐藏风险修复（2026-09-05）

规范：`mobile-agent-runtime-architecture-convergence-review-prompt.md`（本轮任务 prompt）。
基线：`a361a3946b25f0d3a01aca1f5d949b959bdb9b67`（远端 `main`，CI 已绿；不视为架构正确证据）。
设计取舍见 [ADR-0007](../adr/0007-architecture-convergence.md)。
未提交、未推送、未部署（用户未授权 commit/push）。

## 1. Findings 确认结论

| # | Finding | 结论 | 证据 |
| --- | --- | --- | --- |
| §2 | ToolOutcome 持久化不一致 | CONFIRMED | `AgentRuntime.kt:515-517` Denied/Invalid 为普通字符串；`ConversationRepository.validateMessage` 要求 JSON object |
| §3 | Responses `error:null` 误判 | CONFIRMED | `OpenAiResponsesAdapter.kt:594` `root["error"]?.let` 对 JsonNull 触发；另有 Compatible probe（L974）、Compatible 非流（L1174）、MCP（L469）同类 |
| §4.1 | create-only TOCTOU | CONFIRMED | 三后端 commit 均用 `REPLACE_EXISTING`；另实测 Windows rename 无 replace 仍静默覆盖 |
| §4.2 | fileVersion 非内容版本 | CONFIRMED | `fileVersion` 仅 size/mtime/ctime/fileKey；同长改写 + mtime 恢复可复现 |
| §5 | 多 KB 检索顺序偏置 | CONFIRMED | `retrieve()` 逐 KB 拼接到两个大 list 再 RRF；tie 用稳定排序依赖插入顺序 |
| §6 | 每次查询重建向量索引 | CONFIRMED | `vectorHits()` 内 create/add/search/close |
| §7 | 检索覆盖范围丢失 | CONFIRMED | warning 后继续，无结构化 coverage、无 prompt/UI/持久化 |
| §8 | 缓存重放跳过授权 | CONFIRMED | `RunTools` 三处 `routed.completed?.let { return it }` 在 owner 重验之前 |
| §9 | 授权规则不一致 | CONFIRMED | Python 查 expiresAt/packageHash/ownership，Builtin 不查 |
| §10 | ChatViewModel 拥有运行 | CONFIRMED | 会话/run/retrieval/tool/approval/streaming/checkpoint 同类 |
| §11 | snapshot 不等于冻结全部 | CONFIRMED | Run 事实分散在 prompt/KB/grants/workspace/schema 各处 |
| §12 | 多 Skill 失去 trusted 身份 | CONFIRMED | `trustedSkillId = skillIds.singleOrNull()` |
| §13 | Python model.invoke 预算不可达 | CONFIRMED | 普通 run budget 无 `maxModelTokens`，`reserveModelCall` 恒拒 |
| §14 | 工作预算与输出预算混淆 | CONFIRMED | fingerprint/配额遍历无界；只有返回页限制 |

## 2. 修复与测试

| 修复 | 测试 | 结果 |
| --- | --- | --- |
| ToolOutcome 统一信封（DENIED/INVALID/FAILED/UNKNOWN_OUTCOME/NEEDS_APPROVAL） | `ToolOutcomeTest` 7，`ToolOutcomeRuntimeTest` 5，`ToolOutcomePersistenceTest` 2（含 reload 语义） | PASS |
| `error` 仅非 null object 进入失败 | `OpenAiResponsesErrorNullTest` 9 fixtures；既有 Responses/Compatible/MCP 套件 | PASS |
| `WorkspaceMutationCapability` 五态；`metadata/content/cas` 版本（`c1:/m1:/d1:` + legacy 回退）；`WorkspaceAtomicCommit.writeExclusive`（CREATE_NEW，rename 不再承担 create-only）；Shizuku/Wired 同修 | `InternalWorkspaceDataIntegrityTest` 10（含并发 create-only 单胜者、mtime 恢复冲突、symlink/parent/type-change fail-closed）；Shizuku `createOnlyWriteNeverOverwritesExternallyCreatedTarget`（device，编译通过待真机） | JVM PASS；device 待跑 |
| 每 KB 每通道独立 ranking + 确定性 tie；`VectorIndexCache`（kb,space,dim,generation + 成员集，LRU4，发布/删除回收）；`RetrievalCoverage` 进 result/prompt/UI/metadata/manifest | `RetrievalConvergenceTest` 5（含顺序交换 metamorphic、build 计数=1、复用命中、覆盖原因码、删除失效、citation 版本回看） | PASS |
| `ToolExecutor.authorizeReplay` + RunTools 三处披露门（run 存活/路由稳定/重验，不重执行、不泄漏、净化缓存）；`AuthorizationEvaluator` 六决策×四检查点同一向量 | `AuthorizationEvaluatorTest` 10，`BuiltinToolsTest` +3（过期即拒/重放拒/健康放行），`RunToolsReplayDeviceTest`（device，编译通过待跑） | JVM PASS；device 待跑 |
| `RunManifest`（无 secret）+ DB v17 `runs.manifest_json` + `RunCoordinator`（prepare/owner/stamp/cancel/release）+ Chat 接入 | `RunManifestTest` 5，`RunCoordinatorTest` 4，`MigrationsTest` +1（v17 旧行留空） | PASS |
| 多 Skill `memory_<opaque>` 命名空间 + handle 同域校验 + 重放重验 binding；单 Skill 保持 legacy 名 | `MultiSkillMemoryIdentityTest` 6（含隔离、跨域拒、撤销 A 保 B、重启、伪造拒） | PASS |
| model.invoke：fail-closed 保持，配额进 manifest（null=禁用），费用告知已在工具描述 | `RunCoordinatorTest.modelTokenBudgetDefaultsToDisabled` | PASS |
| Internal 四限分离 + `ScanBudget` + descriptor 透出 | `WorkspaceWorkBudgetTest` 6 | PASS |
| 基准：coding（读/改/冲突/diff/验证/回滚）、knowledge（顺序/覆盖/复用/citation）、multi-skill（隔离/撤销/重启） | `CodingWorkflowBenchmarkTest` 2 及上述套件 | PASS |

## 3. 全量验证

- strict gate：`licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock
  verifyDependencyVerification check :app-android:assembleDebug
  :app-android:generateDebugSbom --dependency-verification=strict` →
  **1030 tasks BUILD SUCCESSFUL**（217 executed）。
- JVM 聚合（受影响模块）：495 tests，0 failures/errors。
- `compileDebugAndroidTestKotlin`：BUILD SUCCESSFUL（含新增 device 测试编译）。
- REUSE：589/595；缺失 6 个均为任务前已存在且禁止触碰的
  `.tmp-diag3/`（4）、`.workbuddy/`（1）、`docs.zip`（1），本轮新增文件 100% 合规。
- `codegraph sync .`：43 files（Added 14, Modified 29）。

## 4. 未执行（需设备/真机/人工）

- 新增 androidTest 在真机/模拟器上执行（`RunToolsReplayDeviceTest`、
  Shizuku create-only 用例；本机 `adb devices` 为空）。
- 真实 Responses Provider 回包、`error:null` 在 Test Connection 的真网验证。
- 物理 Wired USB、Shizuku Binder death/权限撤销、大规模 KB 真机性能、真实刘海。
- 1k/10k/50k chunks 的索引 load/内存/重建次数实测（lifecycle 与计数器已就绪）。
- 独立只读安全复核（本轮改动覆盖授权/持久化/执行边界，按仓库规则必须复核）。

## 5. 残留风险（诚实记录）

- MCP/远端 Skill executor 未 override `authorizeReplay`（默认放行重放）；
  新调用仍受派发时检查，重放披露是残留缺口。
- move/copy 的 create-only rename 在 Windows 开发机上仍可能替换
  （产品平台 Linux/Android 由内核保证；已在代码注释与 ADR 注明）。
- `RunCoordinator` 尚未接管流式/tool-loop 收集器与 `cancel()` 的 durable 写入
  （后者故意保留现状：提前写 CANCELLED 会覆盖 dispatch 中的 UNKNOWN 判定）。
- Agent 编辑器可见的 `modelTokenBudget` 控件未做（manifest + fail-closed 先行）。
- SAF/Shizuku/Wired/knowledge/Python/ZIP/picker 的工作预算未做（Internal 先行）。
