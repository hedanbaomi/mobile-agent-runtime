<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# b07 follow-up 收敛证据（2026-09-05）

基线 `b07b4b3ab61360b8034e897944eea7a5ca0b3793`，分支 `main`。
本轮只处理 prompt §1 的四个 follow-up + 两个收口；不做 Phase 2/3 大重构。
无 commit/push/deploy 授权；本轮未提交。

## 1. 修复清单（文件/函数）

- `shared/skills-api/.../skills/ToolExecutor.kt`：`authorizeReplay` 默认 `true` → `false`。
- `shared/skills-api/.../skills/BuiltinTools.kt`：`ToolBroker.authorizeReplay`
  未知 call 由 `true` → `false`（calculator 经空 capability 作用域等值仍放行）。
- `shared/skills-api/.../skills/WebSearchTools.kt`：新增显式放行策略（仍授权+已结算+非 unknown）。
- `shared/skills-api/.../skills/tooling/AuthorizationEvaluator.kt`：`isExpired`
  不可读文档 `return false` → `return true`。
- `shared/agent-runtime/.../agent/McpToolExecutor.kt`、
  `app-android/.../McpAppTools.kt`（App桥）、
  `app-android/.../shizuku/ShizukuToolExecutor.kt`、
  `app-android/.../WorkspaceAppTools.kt`：显式 `authorizeReplay = false`
 （无已完成调用授权记录，无法重验）。
- `app-android/.../tooling/ToolExecutorFactory.kt`：`CompositeToolExecutor`
  新增转发（绑定+身份+`SETTLED` 校验后委托 child，异常→false）；工厂新增
  `authorizeReplay` 直通。
- `app-android/.../tooling/UnifiedWorkspaceToolExecutor.kt`、
  `app-android/.../tooling/ShellToolExecutor.kt`：只读重验策略（不消费
  ONCE、不写审计、不碰 backend）。
- `shared/knowledge-api/.../knowledge/VectorIndexCache.kt`：lease/引用计数/
  retired 延迟释放/`getOrBuild` 单飞；`get/put` 改为 `acquire/publish`。
- `data/sqlite/.../data/KnowledgeRepository.kt`：`vectorHits` 改 lease `use`；
  `retrieve` 记录 `usedGenerations`；`buildVectorIndex` 只构造不发布。
- `shared/knowledge-api/.../knowledge/Retrieval.kt`：`RetrievalResult.usedGenerations`。
- `app-android/.../workspace/WorkspaceModels.kt`：`publish(false)`→`publishNew`；
  ~~新增 `moveFileNoReplace`~~（3f75 已删除，见下）；`writeExclusive` 可见性注释；`COMPARE_AND_REPLACE`
  严格定义；撤回“Linux rename 保证”注释。
- `app-android/.../workspace/InternalWorkspaceBackend.kt`：描述符去
  `COMPARE_AND_REPLACE`；move/copy 目录 no-replace→`UNSUPPORTED`；文件
  no-replace move→`moveFileNoReplace`；测试钩子
  `afterPreflightBeforeCommit`/`afterVersionCheckBeforeReplace`。
- `app-android/.../shizuku/ShizukuWorkspaceFileStore.kt`、
  `app-android/.../wired/WiredAdbFileEngine.kt`（+`WiredAdbSharedAdapter.kt`
  `FILE_UNKNOWN_OUTCOME`→`UNKNOWN_OUTCOME` 映射 + 新 `ERR_UNKNOWN_OUTCOME`）：
  同上语义。
- `app-android/.../RunCoordinator.kt`：新增 `PreparedRunFacts`。
- `app-android/.../ChatViewModel.kt`：一次冻结 + grants 取冻结 context +
  stamp 失败 fail-closed（`FAILED` + release + `return@launch`，零派发）。
- `.github/workflows/ci.yml`：新增 `convergence-device`（API 36，
  `RunToolsReplayDeviceTest` + `ShizukuWorkspaceFileStoreTest` +
  `VectorIndexCacheDeviceTest`，无 `continue-on-error`）。

## 2. 测试

| 集 | 结果 |
| --- | --- |
| `:shared:skills-api:test`（含恢复的 `AuthorizationEvaluatorTest` 既有向量 + 新增不可读文档向量） | PASS |
| `:shared:knowledge-api:test`（含 `VectorIndexCacheLeaseTest` 6：invalidate/LRU/replace/close/单飞/顺序复用） | PASS |
| `:data:sqlite:test`（含 `usedGenerations` G1/G2 见证） | PASS |
| `:shared:agent-runtime:test` | PASS |
| `:app-android:testDebugUnitTest`（含 `ToolExecutorFactoryReplayTest` 6、`InternalWorkspaceRaceHookTest` 11、`RunCoordinatorTest` 冻结见证） | PASS |
| `:app-android:compileDebugKotlin` + `compileDebugAndroidTestKotlin` | PASS |
| device 三类 | 本机无 emulator，仅编译；远端 `convergence-device` 执行 |
| strict gate（`licenseGuard`/`verifyCiPins`/`verifyDependencyLock`/`verifyDependencyVerification`/`check`/`assembleDebug`/`generateDebugSbom` + `reuse lint` + `git diff --check`） | 见 §4 |

## 3. 行为变更（调用方可见）

1. MCP（两桥）已完成调用的同 call 重放披露改为 deny；模型需新 call 走批准路径。
2. 无显式策略的老/新执行器默认 deny（calculator 等纯计算经 broker 仍放行）。
3. 目录 no-replace move/copy 改为 `UNSUPPORTED`（Internal/Shizuku/Wired）。
4. （3f75 修订）文件 no-replace move 亦改为 `UNSUPPORTED`（三后端；typed
   拒绝码收敛到 `OPERATION_UNAVAILABLE`）：非原子 copy+delete 被 probe 证明
   可在成功返回的同时删除未复制的新内容；`moveFileNoReplace` 已删除。
5. manifest stamp 失败不再继续执行，直接 `FAILED` 结束本次 run。
6. Internal 描述符不再声明 `COMPARE_AND_REPLACE`（`BEST_EFFORT_CONFLICT_DETECTION` 保留）。

## 4. 验证命令与结果

- 定向 JVM：
  `.\gradlew.bat :shared:skills-api:test :shared:knowledge-api:test :data:sqlite:test :shared:agent-runtime:test :app-android:testDebugUnitTest --dependency-verification=strict --no-daemon --console=plain` → **PASS**（本机 JDK 21.0.2）。
- Android 编译：
  `.\gradlew.bat :app-android:compileDebugKotlin :app-android:compileDebugAndroidTestKotlin --dependency-verification=strict --no-daemon --console=plain` → **PASS**。
- strict gate：
  `licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification` → **PASS**；
  `check :app-android:assembleDebug :app-android:generateDebugSbom --no-build-cache` → **PASS**。
- `python -m reuse lint`：597/602；缺失 5 个均为任务前已有保护文件
  （`.tmp-diag3/`×4、`.workbuddy/`×1），本轮新增文件全部合规，未触碰保护文件。
- `git diff --check`：PASS。
- `codegraph sync .`：21 文件，1462 节点，PASS。
- device 三类（`RunToolsReplayDeviceTest` 5、`ShizukuWorkspaceFileStoreTest`
  新增 2、`VectorIndexCacheDeviceTest` 3）：本机 `adb devices` 为空，仅编译
  通过；远端 `convergence-device`（API 36）执行，无 `continue-on-error`。

## 5. 安全结论

- 撤销后 0 dispatch / 0 cached leak（composite 转发 + 默认 deny + 显式策略）。
- 借出 native 句柄在使用期间不被释放（lease + exactly-once close）。
- 不覆盖用户数据：create-only/no-replace 提交永不覆盖；不支持的目录语义报
  `UNSUPPORTED`；部分提交报 `UNKNOWN_OUTCOME`。
- manifest/诊断不含 secret/path/reasoning/query（沿用既有字段白名单；
  本轮未新增任何敏感字段）。

## 6. 残留与诚实声明

- 跨进程替换写仍是 best-effort（无 OS CAS 原语；`COMPARE_AND_REPLACE`
  已从 Internal 声明中移除，schema/UI 从未承诺严格 CAS，无需改文案）。
- `CREATE_IF_ABSENT` 仅创建原子性；流式写入期间外部读取者可见 partial，
  已在 `writeExclusive` 与能力注释中写明。
- MCP 缓存披露取最严 deny（远端无本地可比重验的已完成调用记录）。
- 不同 key 并发 miss 各自构建（单飞仅同 key）；大规模真机性能、物理
  Wired USB、真实 Provider、cutout 仍留后（prompt §1）。
