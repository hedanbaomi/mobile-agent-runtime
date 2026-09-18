<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->


# 第二轮代码审查与用户 QA 修复

时间：2026-09-13（Asia/Taipei）。依据：[第二轮代码审查与完整模拟器用户测试](../2026-09-12/round2-code-review-and-full-user-qa.md)（被测 main `0c02b32e06f9f5ace7a0cfdf44bb7725898ee1a3`）。

本轮只做本地源码与测试修改，未提交、未推送、未出包、未执行付费 Provider 调用；模拟器设备验收与真实 Provider 复测未完成，原因见第 4 节。

## 1. 修复清单（对照 R2 编号）

| 编号 | 报告问题 | 本轮修改 |
| --- | --- | --- |
| R2-01 P1 | 含会话备份导入在 Android 触发 `java.nio.file.Files.readString` 的 `NoSuchMethodError` 崩溃（`TransferRepository.kt:439`） | 新增 `readStagedBytes`/`readStagedUtf8`：用 `Files.size` + `Files.newInputStream` 的有界流式读取替代 `readString(Path)`，并校验回读长度未变；Skill 包读取同样改走该有界读。`TransferRepository.kt` |
| R2-02 P2 | 能力探测未合并 Profile 模型参数、强制 `tool_choice`，DeepSeek 思考模式 400 却显示 Tools Unsupported | `probeDeclaredFeature` 改为与真实请求同一 `buildPayload` 路径并合并 `profile.parametersJson`；TOOLS 首次仍强制 no-op 调用，4xx 时去掉 `tool_choice` 重试一次：成功记 `verified-without-forced-tool-choice`，200 无调用记 `inconclusive`，仍 4xx 才是 `UNSUPPORTED`。`OpenAiCompatibleAdapter.kt` |
| R2-03 P2 | 候选只剩被固定首轮 user 的孤立 assistant ACK 时，空摘要被判失败并终止 Run | `ContextWindow.plan` 改为按完整轮次/自包含工具交换选择候选：未被固定的整轮原子替换，部分固定轮内只有工具交换可压缩，无 user 的孤立组不入选；Run 失败原因写为 `CONTEXT_COMPACTION_FAILED: invalid summary (<classification>)`，区分 `empty summary`/`unexpected summary schema` 等。`ContextWindow.kt`、`AgentRuntime.kt` |
| R2-04 P2 | 空（或全虚拟）SAF 目录首次授权后不暴露 `file_read_text` | `SafWorkspaceCapabilityPolicy.derive` 在 `readGranted` 时始终声明 `READ_TEXT`（根列举已证明授权可达）；目录当前是否有文件不再决定读能力，缺失/虚拟文档仍由读取操作自身失败关闭。`SafWorkspaceBackend.kt` |
| R2-05 P2 | SiliconFlow 图片探测假阴性（同配置真实 PDF Vision 成功） | 图片探测改用与真实 Vision 请求相同的 `buildPayload`/`encodeMessage` 图片编码并合并模型参数，失败保留 `http-<status>` 分类；不再手写固定 `image_url` 形状。`OpenAiCompatibleAdapter.kt` |
| R2-06 P2 | Windows Companion 因 `fileKey()=null` 报 "file identity is unavailable"，`devices`/`pair`/`run` 全部阻塞 | 新增 `AdbFileIdentity`：优先 provider `fileKey()`，Windows 下用 `Kernel32.GetFileInformationByHandleEx(FILE_ID_INFO)` 取卷序列号 + 128 位文件 id，最后才退回有界 `path/size/mtime` 元组；`AdbExecutableGuard` 在 Windows 下仍拒绝退化的 `fallback:` 身份，内容 SHA-256 复核保留。`AdbDoctor.kt` |
| R2-07 P2 | 打包声明路径 `modelpacks/all-MiniLM-L6-v2/LICENSE-NOTICE.txt` 被拒绝，整个组件列表清空 | 允许路径对齐 `tools/runtime-notices.py` 的 `licenses/` + `modelpacks/` 前缀并保留畸形/越界拒绝；索引改为逐项容错：单个非法条目只跳过该条，其余组件照常显示，跳过数量写入 `error` 提示。`ThirdPartyNoticeAssets.kt` |

未在本轮改动（属模型输出质量或既有正确行为）：`/models` 缺少别名属 Provider 数据差异；原样保存 stdout 少一字、检索漏 citation 属模型质量；32K 预算保护是正确拦截。

## 2. 新增/扩展回归测试

| 测试 | 覆盖 |
| --- | --- |
| `ContextCompactionRuntimeTest.uninformativeOnlyHistoryNeverSummarizesAnOrphanAssistantReply`（新，JVM） | 固定首轮 user + 保留最近 2 轮、只剩孤立 assistant 的历史：Run 必须完成且不产生 `FAILED` 压缩；任何摘要请求都必须是完整轮次。已验证：还原旧候选选择时该测试失败 |
| `OpenAiCompatibleAdapterTest.toolsProbeRetriesWithoutForcedToolChoiceWhenTheProviderRejectsTheShape`（新，JVM） | 强制 `tool_choice` 400 + 去掉 `tool_choice` 200 tool_call：Tools 记 VERIFIED、来源含 `verified-without-forced-tool-choice` |
| `OpenAiCompatibleAdapterTest.featureProbePayloadCarriesValidatedModelParameters`（新，JVM） | 探测请求体确实携带 `parametersJson`（`thinking.type=disabled`、`temperature`） |
| `WorkspaceBackendTest.safCapabilitiesExposeCreateOnlyTextWritesButNotUnsupportedMutations`（扩展，androidTest） | 空目录与全虚拟目录必须声明 `READ_TEXT` |
| `TransferArchiveDeviceTest.conversationArchiveImportsOnDeviceWithoutHostOnlyFileApis`（新，androidTest） | 真机/模拟器上含会话 ZIP 的导出再导入；旧实现在此路径必然 `NoSuchMethodError` |
| `ThirdPartyNoticesDeviceTest.bundledCatalogLoadsEveryComponentIncludingModelPackNotices`（新，androidTest） | 用真实 APK 资产加载目录：无拒绝项、组件数 > 100、modelpack NOTICE 路径可读 |

## 3. 本轮实际执行的验证

- 编译：`:shared:provider-api:compileKotlin :shared:agent-runtime:compileKotlin :data:sqlite:compileKotlin :desktop:bridge:compileKotlin`、`:app-android:compileDebugAndroidTestKotlin`（含新 androidTest）均 **BUILD SUCCESSFUL**。命令统一为 `gradlew --no-watch-fs <tasks> --console=plain`，JDK 17 toolchain、`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`、独立 `GRADLE_USER_HOME=E:\mobileAgentRuntime\.tmp-gradle-home`。
- JVM 测试：`:shared:provider-api:test` 105、`:shared:agent-runtime:test` 52、`:shared:serialization:test` 13、`:data:sqlite:test` 218、`:shared:domain:test` 35、`:shared:knowledge-api:test` 103、`:desktop:bridge:test` 18 —— 合计 **544 tests / 0 failures / 0 errors / 0 skipped**，exit 0。
- 定向反证：临时把 `ContextWindow` 的候选选择还原为旧的原子单元过滤后，`ContextCompactionRuntimeTest.uninformativeOnlyHistoryNeverSummarizesAnOrphanAssistantReply` 立即失败（`AssertionFailedError`），恢复后全绿。

## 4. 未执行与原因

- **设备（模拟器）验收未完成。** 本机尝试 headless 启动 `mar_api36_debug` 执行新增 androidTest，但沙箱拒绝 emulator 创建锁文件（`%USERPROFILE%\.android\emu-last-feature-flags.protobuf.lock`、`E:\Android\Avd\mar_api36_debug.avd\snapshot.lock.lock`，error 5 = access denied），`adb devices` 始终为空。device 测试只做到编译（`compileDebugAndroidTestKotlin` PASS），未运行、不记 PASS。
- **真实 Provider 未复测（R2-02/R2-05）。** 无付费调用授权，DeepSeek 思考模式与 SiliconFlow 图片探测只有宿主同构模拟测试。
- **物理 Wired USB / OEM DocumentsProvider / 294 PDF 全量 READY** 延续报告边界，未验收。
- 未执行 `reviewGate`、licenseGuard、REUSE、CI；这些是提交/推送前门禁，本轮无提交/推送授权。

## 5. 风险与遗留

- `readStagedBytes` 依赖 `Files.size`/`Files.newInputStream`（API 26+，与既有 `Files.createTempFile`/`write`/`deleteIfExists` 同级）；Android 设备回归待第 4 节条件解除。
- TOOLS 探测首次 4xx 时会多花一次极小请求（`max_tokens` 最多 64）以区分探测形状不兼容和不支持工具，`charged` 仍为 true。
- `AdbFileIdentity` 的 `fallback:` 元组只在 provider key 与 Win32 文件 id 都不可用时启用，且 Windows 下仍被 `AdbExecutableGuard` 拒绝；内容 SHA-256 复核始终保留。
- R2-04 观测到的内部错误没有设备原始 tool_call，无法断言未知工具名；本轮修的是能力派生根因，未知工具路径仍返回 `INVALID_REQUEST`（Unknown tool），未做 UI 文案细分。

## 6. 提交与推送（用户授权：提交并推送，不推送交接与 docs）

- 推送方式：从 `origin/main`（`0c02b32`）新建临时 worktree，只复制上述 12 个源码/测试文件，避免把本地 docs/WIP 一并推走。
- 提交：`4d7f8578319b305424d1e538478581b8c7645456`，parent `0c02b32e06f9f5ace7a0cfdf44bb7725898ee1a3`，作者/提交者 `luozhibai <wy3273564266@163.com>`，12 files changed, 636 insertions(+), 100 deletions(-)。
- 推送：`git push origin HEAD:refs/heads/codex/user-qa-fixes` → `214df5f..4d7f857`（fast-forward，非 force）。远端 `codex/user-qa-fixes` 现为 `4d7f857`；`main` 未动。
- 推送前门禁：临时 worktree 内 `licenseGuard --dependency-verification=strict --console=plain` **BUILD SUCCESSFUL**；`--no-watch-fs`。注：新 checkout 的 `app-android/src/main/assets/licenses/maven/org.bouncycastle__bcprov-jdk18on__1.79/LICENSE.html` 因 `core.autocrlf` 得到 CRLF 副本导致首次 licenseGuard 失败，按 HEAD blob 字节（1175 B / LF，SHA-256 `edbb…1849`）修复工作区副本后通过；该文件不在提交内。同一提交内容在临时 worktree 内重跑 `:shared:provider-api:test :shared:agent-runtime:test :data:sqlite:test :desktop:bridge:test` 全绿。
- 未推送：`HANDOFF.md`、`docs/`、`AGENTS.md`、`agent.md` 及本地临时 WIP 仍只在本机。

## 7. 合并到 main

- PR [#13](https://github.com/hedanbaomi/mobile-agent-runtime/pull/13)（`codex/user-qa-fixes` → `main`），2026-09-13T04:57:16Z 以普通 merge commit 合并（未用 admin bypass）。
- 合并前分支 CI 全绿：`check`、`Android UI smoke (API 31/34/35/36)`、`Convergence device tests (API 36)`、`license`；`Signed release gate (manual only)` skipped 属预期。
- merge commit `b93a9816ab372d8c827541ce07d0f06f777259c7`（author `hedanbaomi <wy3273564266@163.com>`，committer `GitHub <noreply@github.com>`）。
- `main` 相对 `0c02b32` 的差异恰为上述 12 个源码/测试文件，未带入任何 `docs/` 或 `HANDOFF.md` 变更。合并后 main 的 CI/license 复跑结果未在本报告跟踪。

## 8. 复核修订（依据 mobile-agent-runtime-4d7f857-r2-fix-review-evidence.zip）

复核结论 NEEDS_AMEND，确认两项 P2 探测缺陷与一项 CI 覆盖缺口；本次修订内容：

1. **探测预算与模型默认输出上限冲突（P2）**。`probeDeclaredFeature`/`testConnection` 合并 `profile.parametersJson` 后又把 `outputTokenLimit` 压到 ≤64，导致合法默认（如 `max_tokens=1024`、普通请求预算 4096）在 `applyOutputTokenLimit` 报 `exceeds outputTokenLimit`，探测返回 `invalid-model-parameters` 且不派发。现新增 `probeParameterLayers`：只把 profile 已使用的那一个输出上限字段钳制到探测上限（1024→64；更省的 32 保留；字段缺失时仍由 builder 注入 64；同时设置两个字段或非正整数/非数值时不改写，交由共享 merger 按普通请求同等拒绝）。字段名（`max_tokens` vs `max_completion_tokens`）保持不变。
2. 工具探测回退范围过宽（P2）。原先任意 400..499 都触发去掉强制 tool_choice 的重试（含 401/403/408/429）。现仅当 featureHttpStatus(status) == UNSUPPORTED（4xx 且非 401/403/408/429）才重试；鉴权、超时、限流保持首次结果，不追加第二次请求。
3. CI 未选择新增 device 测试。convergence-device 的 class 选择器补充 runtime.mobileagent.TransferArchiveDeviceTest、runtime.mobileagent.ThirdPartyNoticesDeviceTest、runtime.mobileagent.workspace.WorkspaceBackendTest。
4. R2-01 device 回归加强：TransferArchiveDeviceTest 改为源库导出后导入全新空库（各自独立 AndroidContextSqlite 数据库），不再用同库 KEEP_EXISTING 覆盖读点。
5. R2-06 原生路径验证：DesktopBridgeTest 新增用例，在本机 Windows 上实际调用 GetFileInformationByHandleEx(FILE_ID_INFO)；provider 无 fileKey 时断言得到 win32: 身份而非退化元组。
6. 验证：shared:provider-api 111、shared:agent-runtime 52、data:sqlite 218、desktop:bridge 19，合计 400 tests，0 failure/error/skipped（含 6 个新增探测用例与 1 个 Windows 身份用例）。device 测试仍只编译（本机沙箱无法启动模拟器），现已由 CI 选择器覆盖。

## 9. 复核修订的推送与合并

- 提交 `f5090838319b305424d1e538478581b8c7645456` 之外的完整 SHA 见远端；parent `b93a981`，5 files changed，404 insertions(+)/72 deletions(-)，作者/提交者 luozhibai。
- 推送 `origin/codex/user-qa-fixes`：`4d7f857..f509083`（fast-forward，非 force）。
- PR [#14](https://github.com/hedanbaomi/mobile-agent-runtime/pull/14) 于 2026-09-13T05:41:14Z 以普通 merge commit 合并（未用 admin bypass），main = `7caebd3eaef88b7282be11c6e5689408f77d2fa8`。
- 合并前 CI 全绿：check、Android UI smoke (API 31/34/35/36)、Convergence device tests (API 36)、license；Signed release gate (manual only) skipped 属预期。
- `main` 相对 `b93a981` 的差异恰为 5 个文件（provider adapter + test、DesktopBridgeTest、TransferArchiveDeviceTest、ci.yml）；未带入任何 docs/ 或 HANDOFF.md 变更。
