<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 9f5257 follow-up：版本投影断链、convergence-device、single-flight（2026-09-06）

规范：用户审查 + `Downloads/mobile-agent-runtime-9f5257-review-evidence.zip`。
基线：`9f5257dd9548b1fbca2036714820c2374110c93e`（`origin/main`）。
本轮已按用户授权 commit + push 源码 `7ef48f1`（作者/提交者 `luozhibai <wy3273564266@163.com>`）；HANDOFF/`docs` 仍仅本地。

## 1. Findings

| # | 指控 | 结论 | 证据 |
| --- | --- | --- | --- |
| A | Internal 升级 `c1:/m1:/d1:`，Shared adapter 仍按旧 hex 解析 | **CONFIRMED** | `casVersion()`/`directoryVersion()` 输出带前缀；旧 `publicVersion` 对 `c1:` 触发 `NumberFormatException`（reviewer `VersionProjectionProbe`） |
| B | 首次 build 失败后 single-flight 可分裂成两把锁 | **CONFIRMED** | `getOrBuild` 在失败路径 `finally` 无条件 `buildLocks.remove`；waiter 仍占旧 monitor 时新来者 `getOrPut` 第二把锁。reviewer probe：`simultaneous_builders=2` |
| C | `convergence-device` 已执行但不是 PASS | **CONFIRMED（根因不是 YAML）** | GitHub run 33970715391 / job 101318610136：30 项中仅 `VectorIndexCacheDeviceTest.concurrentSearchAndInvalidateNeverObservesClosedHandle` 失败，`expected:<0> but was:<1>`。旧测试吞掉 `IllegalStateException`，日志不能证明 UAF。JNI `try_reserve(..., threads=1)` 使同一 leased 句柄上的并发 `search` 抛 `IllegalStateException` |

## 2. 修复

- `WorkspaceVersionProjection`：剥掉 `c1:`/`m1:`/`d1:` 再取 hex 前 16 位；非法 token 抛错。读路径投影失败 → `IO_ERROR`；写/mkdir/move/copy/patch 已提交后投影失败 → `UNKNOWN_OUTCOME`。`implementationVersion` 两侧同一投影。
- `VectorIndexCache.BuildGate.holders`：gate 留到最后一位 holder 离开；失败后的 waiter 与后来者共享同一把锁。
- USearch JNI：`index_limits_t` 并发线程改为 `max(32, hardware_concurrency)`。`UsearchVectorIndex` 对 add/search/close 加实例锁，`pointer` 为 `@Volatile`。
- Device 测试保留首个异常与栈，并断言全部线程结束；不再吞掉失败。

## 3. 验证

| 命令 | 结果 |
| --- | --- |
| `:shared:knowledge-api:test --tests VectorIndexCacheLeaseTest` | 12 tests, 0 failures（含 `failedFirstBuildDoesNotSplitSingleFlight`、`concurrentSearchAndInvalidateNeverObservesClosedHandle`） |
| `:app-android:testDebugUnitTest --tests SharedWorkspaceBackendAdapterVersionTest` | 4 tests, 0 failures |
| `:app-android:testDebugUnitTest --tests InternalWorkspaceDataIntegrityTest` | 10 tests, 0 failures |
| `:app-android:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |
| `:runtime:vector-usearch:externalNativeBuildDebug` | arm64-v8a + x86_64 CMake PASS |
| `licenseGuard` | PASS |
| `git diff --check`（本轮源码） | PASS |
| `codegraph sync .` | 6 files / 183 nodes |
| `adb devices` | 空；`connectedDebugAndroidTest` **未执行**，不冒充 device PASS |
| 远端 `convergence-device` | 源码已推 `7ef48f1`，待 GitHub Actions 观察，不冒充已绿 |

`.codegraph/` 存在但被 gitignore；开工时 glob 未列出该目录，本轮后半用 `codegraph sync` 补齐。未把空索引当源码不存在。

## 4. 未做

- 未重开 RunCoordinator 完整抽离、大 UI、SAF/Shizuku/Wired 工作预算。
- 独立只读安全复核仍未做。
- 源码已推 `7ef48f1`；HANDOFF/`docs` 未推送。远端 `convergence-device` 待 Actions 观察。
