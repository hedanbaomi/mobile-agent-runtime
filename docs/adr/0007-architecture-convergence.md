<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0007：架构收敛——统一 ToolOutcome、授权披露与数据完整性

- 状态：已实现（Phase 1 正确性全部，Phase 2/3 部分；见 `docs/evidence/2026-09-05/architecture-convergence.md`）
- 日期：2026-09-05
- 来源：本轮收敛任务 prompt（`mobile-agent-runtime-architecture-convergence-review-prompt.md`）

## 背景

陌生生产级接手审查发现 13 处跨模块契约缺陷：合法 `Denied`/`Invalid` 经持久化变成内部错误、
Responses `error:null` 成功响应误判、create-only 静默覆盖、元数据版本冒充内容版本、
多 KB 检索顺序偏置、每次查询重建 ANN、检索覆盖范围丢失、缓存重放跳过授权、
授权规则三处不一致、ChatViewModel 拥有运行、多 Skill 失去身份、Python `model.invoke`
预算不可达、工作区执行成本无界。本 ADR 记录每一项的确认结论与取舍。

## 决定

### 1. ToolOutcome（确认并修复）

所有终态工具结果统一为 JSON object 信封
`{"ok":false,"status":"DENIED|INVALID|FAILED|UNKNOWN_OUTCOME","error":{"code","message","retryable"}}`。
`DENIED`/`INVALID` 永不升级为 INTERNAL；`UNKNOWN_OUTCOME` 永不可自动重放。
实现：`skills.tooling.ToolOutcome` + `AgentRuntime` 投影 + 持久化往返测试。

### 2. Responses `error:null`（确认并修复）

仅非 null object `error` 进入失败路径（三处：Responses/Compatible/MCP 同类）。
测试使用真实协议形态 fixture（absent/null/object、completed/incomplete/failed、
refusal、多输出项、未知字段、流/非流等价）。

### 3. 文件数据完整性（确认并修复，含一个平台级发现）

- Backend capability 明确拆分为 `ATOMIC_PUBLISH / CREATE_IF_ABSENT /
  COMPARE_AND_REPLACE / BEST_EFFORT_CONFLICT_DETECTION / RECOVERABLE_EDIT`；
  不再把任何后端统称“atomic write”。SAF 只声明 best-effort。
- 版本拆分为 `metadataVersion` / `contentVersion` / 带标签 CAS token
  （`c1:` 内容哈希、`m1:` 元数据、`d1:` 目录）；大文件/目录明确为 best-effort；
  旧 token 以 legacy 回退兼容（进程内有效，重启过期）。
- **平台发现（实测）**：Windows 上 `Files.move(tmp, target, ATOMIC_MOVE)`
  无 `REPLACE_EXISTING` 仍静默覆盖（probe 见证据）。因此 create-only 文件内容
  不走 rename：`WorkspaceAtomicCommit.writeExclusive` 用内核原子 exclusive
  create（`CREATE_NEW`），全平台可证明、无 REPLACE、无静默降级。
  move/copy 的 rename 路径在 Linux/Android（产品平台）由内核保证；
  Windows 偏差仅影响开发机测试，已在代码与证据中注明。

### 4. 检索顺序偏置 + 索引生命周期 + 覆盖范围（确认并修复）

- 融合输入改为“每 KB 每通道一个 ranking”，禁用跨 KB 拼接；RRF 只用 rank，
  不混排跨 space cosine 分数；tie 按 `(chunkId, knowledgeBaseId)` 确定。
- ANN 句柄按 `(kb, space, dimension, generation)` + 成员 id 集缓存复用；
  新 generation 原子切换；删除/发布时回收；SQLite 向量保持唯一真值；
  API embedding 计费缓存是另一层，不混淆。暂不承诺具体毫秒 SLO，
  以 build 计数与复用命中为证据。
- `RetrievalCoverage(requested/searched/unavailable+reason/partial)` 进入
  `RetrievalResult`、模型 prompt（runtime 注入、不可伪造）、UI 状态、
  assistant 消息 metadata（reload 可见）与 `RunManifest` 摘要；无 query 明文。

### 5. 缓存披露授权 + 统一授权评估器（确认并修复）

- 区分“不重复执行”与“是否允许再次披露”：`ToolExecutor.authorizeReplay`
 （默认允许；ToolBroker/Built-in/Python/Memory override），`RunTools`
  在所有重放点先验证 run 存活、路由稳定与披露授权，否则 `DENIED` 且
  净化缓存——绝不因撤销而重执行，也不泄漏旧结果。
- `AuthorizationEvaluator` 统一 revoked/expiresAt/ownership/frozen-grant/
  capability/scope 六维决策与四检查点；Python broker 的既有严格检查
  接入同一决策表；同一向量组验证所有路由。MCP/远端 Skill 暂保持默认，
  记为残留风险。

### 6. 运行所有权与清单（部分实现）

- `RunManifest`（版本/指纹、无 secret）随 run 持久化（DB v17 `runs.manifest_json`）；
  旧行保持 `{}`，不虚构冻结事实。
- `RunCoordinator`（container 持有）拥有 prepare/owner/stamp/cancel/release；
  ChatViewModel 经其持久化、注记清单、释放所有权；流式/tool-loop 收集器
  本轮仍在 VM（完整抽离待后续）。

### 7. 多 Skill 记忆身份（已实现）

单 Skill 保持 legacy 工具名；多 Skill 时每个 Skill 获得
`memory_<opaque>.<operation>` 命名空间（opaque 为代码身份摘要，非 installId），
handle 必须属于同一命名空间；撤销 A 不影响 B；重放披露重验 binding。

### 8. Python `model.invoke`（产品决定）

支持，但默认禁用：普通 run budget 无 `maxModelTokens` 即 fail-closed；
`RunManifest.modelTokenBudget` 记录显式配额（null=禁用）；
费用告知已在 Skill 工具描述中；Agent 编辑器可见配额控件待后续。

### 9. 工作区工作预算（Internal 已实现，其余记为后续）

`maxReturnedEntries / maxScannedEntries / maxMetadataReads / maxWallTimeMs`
四限分离；超限报 `ENTRY_LIMIT_EXCEEDED` 而非内部错误；descriptor 透出。
SAF/Shizuku/Wired/knowledge/Python/ZIP/picker 同原则待后续。

## 替代方案（已拒绝）

- 每个模块加 `if` 兼容：拒绝，债务正是这么欠下的；本轮只收敛到四个统一契约。
- 跨 embedding space 直接混排 cosine：拒绝，不可比。
- 撤销后重执行原工具以“刷新”缓存：拒绝，副作用不可重放。
- 把所有授权实体合并为一种 grant：拒绝，保留实体，只统一评估器。

## 验证

见 `docs/evidence/2026-09-05/architecture-convergence.md`：JVM 全绿清单、
metamorphic/fault-injection 明细、strict gate 与 REUSE 结果，
以及仍需真机/模拟器的项目（新 androidTest、真实 Provider、Wired USB、刘海）。
