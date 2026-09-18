<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 3f75 follow-up lifecycle 证据（2026-09-05）

基线 `3f75a53d6e31408109505c980771478f445d5c24`，分支 `main`。
本轮只处理 prompt 的 4 个 finding（A CI YAML、B move 删新内容、C retired
hash 滞留、D build 复活、E manifest 文案范围）；不做 §1 明确留后的事项。
本 prompt 不授权 commit/push/deploy；本轮未提交。

## 1. 复核结论

| # | 指控 | 结论 | 生产证据 |
| --- | --- | --- | --- |
| A | CI YAML 破坏，零 job 启动 | **CONFIRMED** | `ci.yml:130` `name` 与 `if` 同行（上轮编辑事故）；reviewer run 33960854867 jobs=0 与 license-guard 成功并存 |
| B | `moveFileNoReplace` 成功删除未复制的新源内容 | **CONFIRMED** | `WorkspaceModels.kt:352-388`：size 比较 + 无条件 `delete`；probe 以 `checkDelete` 钩确定性复现 `BBBB` 丢失 |
| C | retired 集合因可变 data class hash 滞留 | **CONFIRMED** | `Entry` 为含 `var refCount/retired` 的 data class，`release` 先改 hash 后 `remove`，21 次后滞留 21 项（probe）；旧测试只断言 closeCount，故未发现 |
| D | in-flight build 在 invalidate/close 后复活 | **CONFIRMED** | `getOrBuild` 构建后无条件 publish；`invalidate/close` 只动已发布 entry；无 epoch/closed 标志（probe 两 case 均复活） |
| E | manifest 失败文案“零外部派发”范围不实 | **CONFIRMED** | 文案在 `ChatViewModel.kt:682-683`；API embedding KB 的 `retrieve()` 经 `selectedEmbedder.embed()`（`KnowledgeRepository.kt:3301`）在 stamp 前即可派发计费调用 |

调用点核对（§3.2）：`moveFileNoReplace` 仅 Internal/Shizuku/Wired move 使用；
SAF 走 provider rename（best-effort 契约不变）；无 copy+delete 组合调用；
typed `file_move` 全链（executor/三适配器）恒为 no-replace。

## 2. 修复

- `ci.yml:130` 拆行；新增 `tools/verify-workflows.py`（PyYAML 解析 + 最小
  shape + 禁 `continue-on-error`）与根任务 `verifyWorkflowYaml`（接入根
  `check` + CI check job 独立 step，`pyyaml==6.0.2` 与 reuse 同例固定版本）。
  正反验证：修复后 2 workflows valid；reviewer 坏片段复现 ScannerError。
- 方案 A：三后端 `move(!replaceExisting)` 一律拒绝（既存目标→
  EXISTS 系；缺失目标→`UNSUPPORTED`/`ATOMIC_REPLACE_UNAVAILABLE`/`ERR_*`），
  hook 仍在拒绝前运行以供 race 测试；删除 `moveFileNoReplace`；
  Shizuku/Wired 适配层拒收码收敛到 `OPERATION_UNAVAILABLE`（此前误标
  INTERNAL/IO）。
- `Entry` 改普通 class（身份语义）+ `retiredCount()` 内省。
- `closed`/`globalEpoch`/`kbEpochs`；`publish`/首检拒绝 closed；构建后 epoch
  比对，失配则关闭自建句柄并抛 `StaleVectorBuildException`（`close` 后为
  `IllegalStateException`）；`invalidate`  bump 对应 kb epoch；
  `vectorHits` 捕获 stale 后向量通道置空 + warning（lexical 照常服务）。
- 方案 A：manifest 失败文案改为“已阻止后续 Chat 模型与工具执行。检索预处理
  可能已发生；若使用远程 Embedding，请查看本次检索/费用记录。”；
  `RetrievalResult.usedRemoteEmbedding`（仅缓存未命中真派发；命中重插不计数）
  → `RetrievalScopePin.remoteEmbeddingKbIds`；attempt/vector-cache 行以
  SHA-256 digest 为键，无 query 明文。

## 3. 测试

- JVM：`VectorIndexCacheLeaseTest` 10（+retired 零残留/1000 循环、build×
  invalidate/close/异 KB、单飞保持）；`RetrievalConvergenceTest` +2（远端派发
  计数1/命中0计数/无明文行、本地零标记）；`RunCoordinatorTest`
  scope 映射；`InternalWorkspaceRaceHookTest` move 改 UNSUPPORTED（含 hook
  窗口 BBBB 完好）；既有 create-only/TARGET_EXISTS/MOVE_INTO_SELF 不变。
- Device：`ShizukuWorkspaceFileStoreTest`（no-replace 拒绝双断言）、
  `typedStatAndAtomicMove` 改 replace 成功、`ShizukuLiveDeviceTest` 改断言
  `OPERATION_UNAVAILABLE`；新增 `VectorIndexCacheDeviceTest` 不动（close
  幂等兼容）。本机无 emulator，仅编译；远端待跑。
- `verifyWorkflowYaml` 本地 PASS；strict gate 见 §4。

## 4. 验证

- 定向 JVM（skills-api/knowledge-api/sqlite/agent-runtime/app-android
  unit）：PASS；Android 编译（main+androidTest）：PASS。
- `licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock
  verifyDependencyVerification`：PASS；`check assembleDebug
  generateDebugSbom --no-build-cache`（含新 YAML 门）：PASS。
- `reuse lint`：仅任务前保护文件缺失（同上轮）；`git diff --check` PASS；
  `codegraph sync` 待收尾执行。

## 5. 残留与诚实声明

- Typed `file_move` 恒 no-replace，现对三 NIO 后端一律拒绝（SAF 保持
  best-effort provider rename，已声明）；move 能力靠 copy+delete 两步。
- 跨进程替换写仍 best-effort（无 OS CAS 原语）。
- 远端 CI/device 结果待 push 后观察，本轮不冒充。
