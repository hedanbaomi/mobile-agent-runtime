<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 7ef48f1 Workspace version 契约修复

日期：2026-09-08（Asia/Taipei）。需求：R32；验收：W21 / S30 / S31。

## 范围与现场

- 用户明确指定 Downloads 的 `mobile-agent-runtime-7ef48f1-short-followup-prompt.md` 为本轮规范；同名前缀 ZIP 只作复核证据。另授权检查项目并处理确认的问题。
- 实际 Git 根目录 `E:/mobileAgentRuntime`；分支 `main`；任务基线及 HEAD 为 `7ef48f1b118337072c7db4899672cf0ea2702ae8`，远端 main 已核对相同。
- 保留任务前 `AGENTS.md`、`agent.md`、`HANDOFF.md`、`docs/` 与未跟踪临时目录 WIP。任务前差异和状态保存于 `build/codex-7ef48f1/baseline-wip.patch`、`baseline-status.txt`。本轮未获 commit/push/deploy 授权。
- 本轮不重开完整 RunCoordinator 抽离、model.invoke UI、非 Internal 全工作预算、大 KB、物理 USB、真实 Provider、cutout、UI 重做、branch protection 或 no-replace move 恢复。

## 确认与修复

### 1. 返回版本不能被请求接受：CONFIRMED

基线源码对 digest 前 16 位调用 `Long.parseUnsignedLong`。普通文本 `test` 的 SHA-256 前缀为 `9f86d081884c7d65`，返回负 Long，而共享请求构造器及工具 schema 只接受非负 expectedVersion。该 finding 经源码上下游及真实文件测试自行复核。

`WorkspaceVersionProjection` 校验整个 hex body；c1/m1/d1 与 legacy bare token 统一沿用原前 16 位/短 token 右补零规则，再 `and Long.MAX_VALUE`。原合法非负版本保持不变；最高位为 1 的版本落到合法范围。完整内部 token 仍用于 backend 提交前检查，未丢弃 expectedVersion。数值投影存在有限位数碰撞限制，不宣称强 CAS，详见 [ADR-0009](../../adr/0009-workspace-version-request-range.md)。

`SharedWorkspaceBackendAdapterVersionTest` 覆盖 24 组标签/数值边界、全部 expectedVersion DTO、非法 hex 后缀及真实 c1/d1/m1 往返；移除会跳过负值断言的条件分支。

`WorkspaceVersionToolFlowTest` 使用真实 Internal backend、adapter、registry、capability grants、snapshot binding 和 UnifiedWorkspaceToolExecutor：创建 `test` → stat/read/list 返回相同 version → 原 JSON version 不作转换地回送 write → 返回的新 version 回送 patch。保留旧 version 再 write/patch 均为 `CONFLICT`，文件内容不被覆盖。另一用例在真实写入完成后损坏返回 token，验证 executor 返回 `UNKNOWN_OUTCOME` 且文件已经写入。

### 2. Shizuku 同类版本与提交后映射问题：CONFIRMED

selected-workspace 与 device-token adapter 各自的 unsigned 64 位解析会产生负数，现统一复用同一非负投影，保留 64 hex 严格校验及完整 lowercase opaque token。mutation 成功响应无法可靠映射时保持未知结果；只读协议损坏仍为 `BRIDGE_PROTOCOL_MISMATCH`；有效结构化 `CONFLICT` 保持冲突。

新增 `ShizukuVersionProjectionTest` 在 Android 的真实 JSONObject 环境验证两个 adapter 的 parser/dispatch seam、DTO 接受、opaque token 保留和错误分类。它不是完整 Binder 服务调用或物理 Shizuku 验收；本轮要求的真实纵向链由上面的 Internal 测试承担。

独立复核补充确认：缺失/非 Boolean 的 `ok`，以及失败 envelope 的缺失/非字符串/未知 code，不能证明 mutation 未发生。两个 adapter 现严格校验该 envelope 并将这些情况归为 `UNKNOWN_OUTCOME`；有效 `{ok:false, operation, code:CONFLICT}` 仍为 `CONFLICT`。测试覆盖七种损坏 envelope，避免 JSONObject 自动转换字符串掩盖协议损坏。

### 3. Composite 直接 invoke 绕过缓存披露重验：CONFIRMED

`RunTools` 路径已有 replay gate，但 factory 生成的 Composite 对相同 call 的直接重复 invoke 会立即返回旧缓存。现抓取结算结果后在锁外向原 owner 重验授权，再校验同一 call、route 和缓存对象。撤销或重验异常将旧结果替换为永久拒绝标记；恢复授权不会复活旧缓存，重复调用不会再次派发。

未知执行结果单独保持 `UNKNOWN_OUTCOME`，只缓存安全标记，既不重放原敏感 reason，也不把已发生与否不明的操作改成已知拒绝。审批结算同样适用。`ToolExecutorFactoryReplayTest` 覆盖直接调用、默认拒绝、撤销、重验异常、审批与未知结果。

### 4. 类型化 factory 桥接丢失运行时执行 ID：CONFIRMED

`ToolInvocation.callId` 只用于关联模型响应，执行与重放应使用 runtime `requestId`。NamedExecutor 先前把 callId 传入使用该字段作为缓存/审批键的 legacy child，同一模型 ID 的两次独立调用可能冲突。类型化入口现传 requestId；原 legacy 入口保持原契约。回归通过真实 factory registry 和有状态 child，确认相同模型 callId、不同参数的两次运行时 invocation 各自成功，并持有不同 requestId。

空工具集提供 `createToolRegistryOrNull`；非空 registry 的 `beginRun` 有明确前置要求，未找到违反该要求的生产调用证据，因此未将审阅中的该疑点当作确认缺陷修改。

## 远端基线结果

只读核验 [CI run 34003831250](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34003831250)：headSha 与本轮基线完全相同，最终 success。[Convergence device tests API 36](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34003831250/job/101407341641) 于 2026-09-06T01:34:38Z 完成，30/30 通过。check、API 31/34/35/36 UI smoke 均 success，手动 signed-release gate 在普通 push 下 skipped 符合预期；独立 license-guard run 34003831268 为 success。

保存响应及 job 日志于 `build/codex-7ef48f1/remote-ci.json`、`remote-device.txt`。据此关闭本轮 USearch 待观察项，未修改 USearch 或 VectorIndexCache。远端结果只证明基线，不代表本轮未提交修改已有远端 CI。

## 本地验证

**IMPLEMENTATION STATUS: PASS。** 日志根目录为 `E:/mobileAgentRuntime/build/codex-7ef48f1`。

| 验证 | 命令 / 证据 | 实际结果 |
| --- | --- | --- |
| 全仓与 API 36 定向设备回归 | `ANDROID_SERIAL=emulator-5580 ./gradlew check :app-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.shizuku.ShizukuVersionProjectionTest,runtime.mobileagent.RunToolsReplayDeviceTest,runtime.mobileagent.shizuku.ShizukuWorkspaceFileStoreTest --dependency-verification=strict --no-daemon --max-workers=4 --console=plain`；`final-check-device-strict-response.log` | exit 0，BUILD SUCCESSFUL，1056 tasks；30 tests，0 failure/error/skip |
| 最后 requestId 修正后 app 全变体检查 | `./gradlew :app-android:check licenseGuard --dependency-verification=strict --no-daemon --max-workers=4 --console=plain`；`final-factory-check.log` | exit 0，BUILD SUCCESSFUL，845 tasks；包含 debug/release/review 单元测试与 lint |
| 版本契约与真实纵向链 | 最终三变体 JUnit XML、`final-test-summary.json` | 每变体 AdapterVersion 5/5、ToolFlow 2/2、InternalDataIntegrity 10/10，均无 skip |
| 工厂执行/披露契约 | 最终三变体 `ToolExecutorFactoryReplayTest` XML | 每变体 11/11，均无 skip |
| 设备侧明细 | connected JUnit XML、`final-test-summary.json` | ShizukuVersionProjection 3/3；RunToolsReplay 5/5；ShizukuWorkspaceFileStore 22/22 |
| 独立只读复核 | 独立 reviewer 审查本轮 4 个生产文件与 4 个测试文件，检查最终变更及协议上下游 | PASS；发现的 malformed envelope 和 typed requestId 问题已修并重新审阅，无阻塞 finding；审阅者未修改文件、未重复运行 Gradle |
| 源码定位索引 | `codegraph sync .`；`codegraph-sync.log` | exit 0，Already up to date；索引不提交 |
| 差异与保护边界 | `git diff --check`；`collect-evidence.py` 比较开工 patch；最终 `git ls-remote origin refs/heads/main` | exit 0；5 个禁止顺改的既有 tracked WIP 差异一致；HEAD/远端仍为 `7ef48f1b118337072c7db4899672cf0ea2702ae8` |

全仓检查已覆盖主版本修复、Shizuku 与 Composite 重放修复；随后仅 factory requestId 桥接及其 JVM 回归改变，因此最后重复受影响 app 全变体检查，未无因重跑全仓或模拟器。设备测试结果不声称包含最后的类型化 requestId 补丁；该纯 Kotlin 桥接路径由最后的真实 registry JVM 回归验证。最终源码 SHA-256 与 XML 摘要存于 `final-test-summary.json`。

早期 `project-check.log`（981 tasks）以及 `final-check-device.log`（29 tests）保留为过程记录，最终结果以上表为准。首次测试编译因 Android compile stub 不提供 `Files.readString` 失败，测试改用现有兼容的 `Files.readAllBytes` 后通过；`version-red.log` 实际是这次编译失败，不作为行为 red-test 证据。

本轮使用独立创建、仅位于 build 下的 `codex_version_contract_api36` AVD（已安装 Google APIs API 36 x86_64 image），端口 5580。早期既有 Google Play AVD 的隔离启动遇到 ADB unauthorized，未更改用户 AVD、ADB keys 或关闭认证；改用任务独立 AVD 后 boot_completed=1，完成测试并关闭。最后 `adb devices` 为空。未运行真实 Shizuku 服务、物理 USB 或 OEM 验收。

命令工具未遵守配置的 Windows shell，实际先进入 WSL；本轮通过 `/mnt/d/Git/bin/bash.exe --noprofile --norc -c ...` 启动原生 Git Bash，`uname -s` 已验证为 MINGW64_NT。源码定位先调用 CodeGraph；对未命中目标或过宽结果，使用针对性搜索补充。未修改主机 shell 默认值。

## 交付边界

实现回合结束时未 commit/push；后续新授权与提交结果见下一节。未部署、发布 APK 或调用真实付费 Provider。文档仅本地，既有 WIP 不纳入任何提交。模拟器测试不代表物理设备、OEM 或真实 Shizuku 服务验收；原有这些未决项继续保留。

## 后续授权提交与推送（2026-09-08T08:44:20+08:00）

用户明确要求「完成 commit 与 push，交接文档和 /docs 不进行 push」。按 agent.md 的分支规则，在 `codex/workspace-version-contract` 提交并推送；未直接推 main、未合并、未创建 PR 或部署。

- Commit：`19b5dc3e4e5f480e00a6678ef9fa60c0b57d39fc`，`fix(workspace): enforce version round-trip and replay contracts`。
- 提交仅包含本证据涉及的 4 个生产 Kotlin 文件及 4 个测试文件。`git diff-tree` 核验无 HANDOFF/docs、AGENTS/agent.md 或保护临时目录。
- 提交前再次运行 `./gradlew licenseGuard check --dependency-verification=strict --no-daemon --max-workers=4 --console=plain`：exit 0，981 tasks，2m56s，日志 `publish-check.log`。
- 对确切暂存 tree 导出隔离快照并运行 `python -m reuse lint`：593/593、exit 0，日志 `publish-reuse.log`。受检暂存 tree 和实际提交 tree 相同：`2392b336a6dcdbbe6eed580d92f3ae4b06774082`。清单及快照信息见 `publish-preflight.json`、`publish-source-allowlist.json`。未为 REUSE 修改或排除待提交文件；隔离快照不带工作区未提交文档及临时材料。
- `git push --set-upstream origin codex/workspace-version-contract` 成功；`git ls-remote` 核验该分支为完整 commit SHA，远端 main 仍为 `7ef48f1b118337072c7db4899672cf0ea2702ae8`。
- 新提交 [CI run 34174287929](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34174287929) 和 [license-guard run 34174287949](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34174287949) 在上述时间均为 in_progress；此次交付不将它们报告为 PASS。
- 更新本地 HANDOFF 与本证据以记录新状态；文档改动未提交或推送，剩余 WIP 保留。

## 合并 main（2026-09-08T09:02:01+08:00）

用户随后明确要求「合并到 main」。合并前核验远端 main 为原基线 `7ef48f1`，来源分支 head 为 `19b5dc3`，仅相差已审阅的单个提交、8 个源码/测试文件；HEAD push CI `34174287929` 与 license-guard `34174287949` 均已 success，包含 convergence-device 与 API 31/34/35/36 UI smoke。

创建 [PR #7](https://github.com/hedanbaomi/mobile-agent-runtime/pull/7)，base=main、head=codex/workspace-version-contract。PR 创建额外触发同一 head 的检查，合并时该批检查仍在运行；已完成的 head push 检查全部通过，base 未改变。使用普通 `gh pr merge 7 --merge --match-head-commit 19b5dc3e4e5f480e00a6678ef9fa60c0b57d39fc`，未使用 admin/force、未更改仓库规则或删除分支。

- PR state：MERGED，mergedAt=`2026-09-08T01:01:05Z`。
- Merge commit：`a933b1135d42ee85c1f475ba584784d13228160e`；`git ls-remote origin refs/heads/main` 一致。
- 本地 `git fetch origin main` → `git switch main` → `git merge --ff-only origin/main` 完成，HEAD 与 origin/main 一致。
- Merge tree=`2392b336a6dcdbbe6eed580d92f3ae4b06774082`，与已验证/提交的 `19b5dc3` tree 完全相同，`git diff 19b5dc3 HEAD` 为空。
- 切换与同步前后的本地 tracked WIP binary patch 以 `cmp` 验证完全相同，记录在 `build/codex-7ef48f1/merge-local-wip-before.patch` 和 `merge-local-wip-after.patch`；未提交交接/docs/agent-rule WIP 或临时材料。
- 合并后 main 的 [CI 34175230254](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34175230254) 与 [license-guard 34175230347](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34175230347) 于本节时间为 in_progress，未冒称 PASS。
- 未重新构建 APK；现有 debug 签名 Review APK 仍标识 `19b5dc3-dirty`，源码 tree 与 main 合并结果相同。未安装、未新增人工验收、未部署。
