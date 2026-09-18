<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 会话自动上下文压缩验证记录

时间：2026-09-11（Asia/Taipei）。根目录 `E:/mobileAgentRuntime`；分支 `codex/user-qa-fixes`，实现阶段基准 HEAD `05bea3e20878202b7171f4873ff38e665413d655`。本记录对应提交前的实现与定向验收；后续已按用户明确授权推送源码 `72dc60a`，新增完整提交门禁及文档隔离证据见 [源码推送记录](context-compaction-source-publish.md)。原有 AGENTS.md/agent.md 与临时 WIP 保留，文档仅本地保存。

## 范围和实现

用户要求在设定输入/模型上下文/单段轮次压力下自动压缩，并修复参考对话最后一轮唯一 P2：预算漏计工具调用参数。实现契约见 [ADR-0010](../../adr/0010-conversation-context-compaction.md)，需求 R34、验收 C20—C25。

- `RequestInputBudget` / `ModelAdapter.estimateInput`：完整正文、角色、工具参数/id/name/结果关联、schema、参数层与协议/图片/续接预留；Chat 凭据解析前与 Runtime 每次请求共用。
- `AgentContextPolicy` / `ContextWindow` / `AgentRuntime`：统一触发、保留固定上下文与完整工具交换、同模型无工具摘要、结构校验、先持久化再替换、段轮续跑且总预算不清零。
- `ContextCompactionRepository` / v18 / Run recovery：会话与快照来源、hash/parent、单一 active、不可变终态；FAILED/UNKNOWN 保存已知 usage；原 Message 不修改，重启不自动重发。
- Agent 编辑器保存独立上下文草稿及未知键，摘要记录可展开并跳转原始消息。错误/取消轮中的完整前缀仍可用于之后的压缩。

按用户追加要求，先通过内部浏览器使用本地 DeepSeek V4.1 Flash 子任务，接收预算器、持久化和设置代码；反复临时编译部分已收束。Luna Max 完成独立 Runtime 回归文件，Astra Low 完成独立只读协议审阅。主 Agent 负责接线、修正、有效编译测试与文档。浏览器子任务自报未编译不能当作构建证据。

## 已执行的 JVM 检查

Windows Git Bash `D:/Git/bin/bash.exe`，Java 17 缓存工具链；Gradle `--dependency-verification=strict --no-daemon --no-build-cache --max-workers=2`。shell 工具忽略显式 shell 并落入 WSL；通过 `/mnt/d/Git/bin/bash.exe --noprofile --norc -c ...` 启动真正 Git Bash，未声称修改宿主默认 shell。

| 模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| shared:domain | 32 | 0/0/0 |
| shared:provider-api | 99 | 0/0/0 |
| shared:agent-runtime | 48 | 0/0/0 |
| data:sqlite | 210 | 0/0/0 |
| app-android:testDebugUnitTest | 71 | 0/0/0 |

合计 460。含 12 个新 Runtime 场景、9 个 Repository 场景、7 个设置草稿场景和策略/预算器回归。累计覆盖超过 512 条来源、同时间戳链头、跨会话/错误归属/顺序/来源更改、终态派发拒绝、无效摘要与恢复均有明确断言。

最初预算器用例把 1 字节替换为 65536 字节时错误要求增加 65536，修正为 65535；其他初次失败为跨模块 smart cast、测试假凭据 `s` 把摘要字段正常脱敏导致非法 JSON、测试误把 operationId 后缀当摘要协议、lambda 参数顺序及新增模块缺少 JSON 依赖/锁。按实际失败修复后重跑，不将这些失败记为产品验收通过。

本地原始日志：`.private/validation-context/`；测试 XML：各共享模块 `build/test-results/test/`，App 为 `build/test-results/testDebugUnitTest/`。最终清单 `final-manifest.json` 包含 28 个修改的源码/测试/依赖配置文件 SHA-256、JVM 计数和两个 APK hash。

## 独立审阅

只读协议审阅报告的 5 个 P2 已闭合：失败轮完整来源恢复、失败/未知摘要 usage 持久化、512 条永久覆盖上限、PREPARED 后 Run 终止时的派发检查，以及进入 PREPARED 前将上一条 assistant 完整持久化后再释放其 ID。审阅者复核 Runtime/Chat/Repository/v18 migration/recovery、同时间戳 parent、单一 active 和最后的 assistant checkpoint 修复后给出本次范围 **PASS**，无剩余确定 P1/P2。该结论不替代编译、模拟器或实际模型质量验证。

CodeGraph 在初次源代码变更收敛后执行 `codegraph sync .`，返回 `Synced 2 changed files` / `Done`；设备测试暴露问题并修复后再同步最终增量。早期 explore 多次未返回关键方法体，按项目规则记录后使用定向文本检索。

## Android 定向验收

最终产品增量构建：`:shared:agent-runtime:test :app-android:assembleDebug :app-android:assembleDebugAndroidTest :app-android:testDebugUnitTest licenseGuard`，严格依赖校验，`BUILD SUCCESSFUL`。Runtime 48 项与 App 71 项再次通过。

首次 API 36 专用模拟器执行 7 项测试，其中 5 项通过、2 项失败，未隐去失败：① 历史只压到触发线以下，新增一个用户轮后再次摘要；SQLite 指纹/parent 证实摘要已正确复用，第二次由 history-turns 再次触发。现已对历史条数/轮数应用 60% 回落目标。② 失败提示仅显示通用 INVALID_RESPONSE 文案；现保留分类并明确显示压缩失败、原文保留、不会自动重试。两处增量经独立只读复核再次 PASS。

Compose 截图辅助随后改为沿用设备像素密度测试 320 dp / 1.3 字号，并等待 Android 窗口动画完成；未削弱原有断言。

最终在本任务启动的 `mar_api36_debug` / `emulator-5554`、API 36 上执行 **7/7 PASS**（8.238 秒；`device-compaction-2.log`，`OK (7 tests)`）：

| 场景 | 实际断言 |
| --- | --- |
| 12 个历史用户轮 | 同模型摘要一次后继续，下一次发送直接复用；总计 3 个请求；原消息逐项相等；Run 与摘要 usage 分别核对 |
| 无效摘要 | 仅 1 个请求，无重试，FAILED 与已知 usage 落盘，原文保留并显示明确压缩失败提示 |
| 工具轮次 | 段上限 2，正常×2→摘要→正常×2；总 5 个请求、3 次 calculator，3 个唯一 callId 与结果；所有消息 COMPLETE |
| 错误轮完整前缀 | 先前完成的工具结果仍进入后续模型上下文，未完成尾部不进入 |
| 摘要界面 | 展开记录、查看来源、点击后跳转原消息，覆盖标识可见 |
| 小屏设置 | 320 dp / 1.3 字号，可清空再输入数字，可滚动到高级次数上限 |
| 固定输入超限 | 凭据不存在仍先以 CONTEXT_OVERFLOW 终止，无工具调用 |

真实截图已人工查看：`.private/validation-context/context-summary.png`、`context-settings.png`；摘要对话框文字完整可读，设置高级字段在软件键盘展开时仍可滚动抵达。此为定向功能检查，不替代整个 UI 重做的设计验收。

定向模拟器验收时的产物（均为 debug，仅安装至专用模拟器；后续完整门禁会覆盖 build 输出目录，以下为当时 hash）：

- `app-android/build/outputs/apk/debug/app-android-debug.apk`：SHA-256 `a4bd1f984152688c8618f76d2c26d0929c2a4ab3241822d56998390f266009d8`。
- `app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk`：SHA-256 `83f2c18be37d89150c343f0a70da7eedfd226f2131ca50161ad165772b181139`。
- 最终构建日志 `android-final-build-2.log`（1m 6s；383 tasks），截图夹具增量 `android-ui-fixture-build.log`；`licenseGuard` 与 whitespace 检查通过。CodeGraph 最终同步完成。未执行全仓 check/reviewGate、正式签名或发布。

## 实际限制

- 预算采用保守估算；图片/私有续接没有通用准确 tokenizer，Provider 最终窗口拒绝仍明确报错。
- 固定内容、图片或单个不可分割工具交换超过窗口时停止并保留原文；不截断 JSON，不做长参数分块摘要。
- 原始记录留存但模型摘要仍可能遗漏事实；真实 Provider 的摘要质量、物理设备和发布均未由本地假 Provider 测试替代。
- 实现及定向验收时未 commit/push；随后用户授权的源码提交推送见上方补充记录。未部署/正式发布/产品真实 Provider 验收。该功能本地测试结果不关闭旧的物理 Wired/OEM/K06 或正式发布未决项。
