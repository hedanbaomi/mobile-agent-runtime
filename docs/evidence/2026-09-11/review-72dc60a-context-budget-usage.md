<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 72dc60a 上下文压缩复审修复

最终核验时间：2026-09-11T21:28:37+08:00。基线 `72dc60a1dfd4d2839e476baf053d87cd24d21c22`，修复提交 `0ca6f1accd5136a3009417e654cbc52000b62362`，分支 `codex/user-qa-fixes`。用户授权修复最新审查意见并提交推送；HANDOFF/docs 继续仅本地保留。本轮处理两项 P2 与 CI 设备覆盖，不重新打开已关闭的 PDF/ZIP 问题。

## 修复

1. Chat 创建 Run 无条件使用会话策略 maxModelRequestsPerRun（默认 32），不再在 autoCompact=false 时覆盖为 8。设备测试设置 false/2，真实 ChatViewModel → adapter → loopback SSE → SQLite；持续 calculator 回合只派发两次、执行两次独立工具调用，第三次请求阻断为 BUDGET_EXHAUSTED / model-rounds，无摘要、原文保留。
2. Runtime 每收到摘要 Usage 就将最新累计快照写入中断 checkpoint，取消/异常清理保存已观察用量。摘要用量由持久化尝试记录携带，不再进入普通 ModelEvent.Usage 通道。Chat 按 attempt ID 差额入账，finally 在 NonCancellable 中读取 SQLite 补齐未送达的终态事件。重复快照与重复收尾不重计，没收到的用量保持 0，不估算、不重发。
3. API 36 convergence-device 的实际 class 参数保留原 3 类，并加入 ChatContextCompactionDeviceTest、ContextCompactionUiTest、ChatInputBudgetDeviceTest，共 6 类。没有修改其他 job、Actions pins、安全门或失败处理。

## 本地验证

- 完整命令：`./gradlew licenseGuard check debugEvidenceGate reviewGate :app-android:assembleDebugAndroidTest --dependency-verification=strict --no-daemon --no-build-cache --max-workers=2 --console=plain`。exit 0，BUILD SUCCESSFUL，1124 tasks（104 executed、1020 up-to-date），286 秒。Debug/非调试 Review 产物、SBOM（各 171 components）、provenance 门禁通过。
- 相关 JVM XML：domain 32、provider-api 99、agent-runtime 48、sqlite 210、app Debug 78，共 **467 项，0 failures/errors/skips**。完整 check 还执行其他模块与 app 的 Release/Review 测试，不把不同变体重复数当作新增用例。
- 新增 CompactionUsageSettlementTest **7/7 PASS**：生产 AgentRuntime + 真实 JDBC SQLite 仓储 + Chat 使用的 RunCompactionUsage。累计快照 12/2 → 321/42 → 321/42；成功后再计普通请求 7/3，Run 恰为 328/45；明确失败、取消、直接异常、超时均保留 321/42；缺失 Usage 不推算；成功已落库但回调未返回/终态事件未送达，收尾仍正确入账。原消息不变、无重试，其他 Run 的尝试不入账。
- 专用 mar_api36_debug x86_64 模拟器（无窗口、read-only、no-snapshot-save）执行与更新后 CI 相同的 6 类：**38/38 PASS**，73.216 秒 instrumentation 时间、命令 exit 0。包含现有 30 项 replay/workspace/index、Chat 压缩 5 项、Compose 2 项与输入预检 1 项。仅安装到本轮启动的 emulator-5554，未连接/安装物理设备。
- 发布树 `6d95345854217e9183d7416f23ec2b2a9bf072d0` 的 REUSE：**614/614 PASS**。使用隔离 export 加明确 --root，没有改动历史临时 WIP，也没有新增扫描豁免。
- 独立 Astra Low 只读静态审阅 **PASS**：核对累计快照、取消后持久化、丢失事件补账、普通 usage 不变、请求硬限、CI 实际选择范围，以及 UNKNOWN/不重放边界。审阅者未运行测试，实测由主 Agent 执行。
- CodeGraph sync：Already up to date / Done；源码 whitespace 检查通过。

本地 APK 为提交前 4fe39dc + dirty-source 构建，6 个发布文件的 SHA-256 与最终提交清单一致。不能标成修复提交的 clean exact-SHA APK。

| 本地测试产物 | SHA-256 |
| --- | --- |
| app-android-debug.apk | 3d23a22577e61c6b611807a834ce92d252ad3a0482c9dc8e4158cb7e27da4f85 |
| app-android-debug-androidTest.apk | b47be03f1737ff5e243b6e1f8b7ed8ad93b8a3bf988892d79dfeff60bb4234a4 |

机器证据在本地 `.private/validation-context/review72dc/`：strict-gate.log/status、jvm-counts.json、device-convergence.log/device-status.json、reuse.log、source-state.json、codegraph-sync.log、push.log。

## Git 与文档隔离

- 源码提交 `0ca6f1accd5136a3009417e654cbc52000b62362` 已非 force 推送，ls-remote 精确匹配。相对基线只变更 6 个源码/测试/CI 文件，没有 HANDOFF/docs/AGENTS/agent。
- 本地 HEAD `3f454183176b7664ff774593bd6dba396d595b2e` 是新源码上保留的仅本地文档提交，ahead 1；旧 HEAD 4fe39dc 保存在 codex/local-docs-before-context-review-20260911。30 个既有文档文件的提交差异逐字节保持，当前文档和规则 WIP 未被覆盖。
- 后续不得直接把本地 HEAD 普通 push 带出文档；再次推源码仍须白名单和历史隔离。
- 本轮同步 ADR-0010、实现方案 §6.3/Run 预算、C22/C23 验收与 HANDOFF；未改变摘要格式、迁移或权限契约。

## 远端 CI

两个 run 的 headSha 均为 `0ca6f1accd5136a3009417e654cbc52000b62362`：

- [ci 34603030328](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34603030328)：completed / success，6 个执行 job 全部通过；手动正式签名 release skipped。
- [license-guard 34603030226](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34603030226)：completed / success。

本提交四档 UI smoke（API 31/34/35/36）与 API 36 convergence 均已 success。convergence job `103274709019` 的实际日志已下载至本地证据目录：class 参数明确包含新增三个测试类，2026-09-11T13:23:02Z `Starting 38 tests`，13:23:33Z `Finished 38 tests`，随后 BUILD SUCCESSFUL。因此本轮新增压缩用例已有当前源码 SHA 的远端执行证据，未以旧 CI 全绿代替。主 check 的单元/Debug、非调试 Review 门禁与证据上传均已通过（job 103274709089，14m4s）。最终 headSha、远端分支与六个发布文件 hash 已复核一致。

## 边界与工具限制

未调用真实付费 Provider、用户原始资料、物理设备或正式发布。摘要参数继承/覆盖属于参考审查中的非阻断兼容性关注；本轮没有对应真实服务商失败证据，保留在真实 Provider 验收范围，不盲目继承全部普通回复参数。

主线 shell 已核验为 Windows Git Bash。DSH Flash 委派全程使用 Codex 内置浏览器；其沙箱 Git Bash 出现 signal-pipe Win32 error 5，子代理报告后使用其受限 PowerShell 工具完成指定修改。主线未更改系统 shell 配置。整理证据时另一次 shell 转义使 Markdown 片段被错误解释，命令失败；此证据已通过直接补丁重建，发布文件 hash 再次核验不变。
