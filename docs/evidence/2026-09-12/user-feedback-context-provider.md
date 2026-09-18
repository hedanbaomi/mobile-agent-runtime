<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-09-12 人工补充反馈：上下文预算与 DeepSeek Base URL

状态：**NEEDS_AMEND / 已登记，未修复**。本记录随同本日无人值守用户测试报告交付；不执行源码修改、提交、推送或发布。

来源：用户在当前任务明确要求纳入 [诊断上下文预算不足](chatgpt-conversation://6aa4e678-6bc0-83e9-ac47-44cbba02f717) 的两项反馈。已读取完整两轮对话及其附件。对话中 AI 的诊断、优先级和修复建议仅作线索，不作为事实或新的实施授权。

## U-01 / P1：正常使用频繁被本地上下文预算拦截

**用户现象**：频繁出现上下文预算不足，无法正常对话。**附件日志已独立复核**：12 条 `run_preparation_failed`，全部为 `stage=context_budget`、`errorCode=CONTEXT_OVERFLOW`、`exceptionType=runtime.mobileagent.ChatInputBudgetExceeded`；previous.ndjson 8 条，current.ndjson 4 条。

- 日志构建：`f1feffed51840a5da299394560619d64cac4b9b5`，dirty=false，DB/schemaVersion=18，与本轮模拟器 APK 源码一致。
- 时间：2026-09-12 05:15:39.689Z 至 05:29:08.310Z。
- 12 条记录属于同一诊断 sessionId（应用日志会话）；日志没有足够 conversationId 信息，**不能据此证明“多个新对话首轮均失败”**。
- 这 12 次失败位于本地准备阶段；不能当成供应商返回的 400/429/上下文错误。也不能扩大为该诊断包期间完全没有其他网络请求。
- 各失败事件只有 stage/errorCode/exceptionType，没有 configured context、output reserve、fixed/skill/tool/history/retrieval 各部分估算及 compaction 前后数值。**频繁拦截成立；误估、配置过小、固定上下文膨胀各自贡献尚未证实。**
- 同轮模拟器有对照：两份原始 Skill 配合 32768 context / 4096 output 时，保守估算 82685 超过 28672，在网络请求前拒绝；改为 131072 / 4096 后真实 Python 工具与压缩执行成功。小窗口 fail-closed 的安全行为 PASS，与正常用户难以配置/估算过重的产品问题可同时成立。

下一步建议：重放原 Agent/model/context 配置；保留必要指令和完整工具配对，记录纯数字预算拆解及真实供应商 usage 对照，再判断固定开销、估算方法和默认配置。不得直接删除安全预算检查或静默扩大用户明确设置的 hard cap。

## U-02 / P1 待复现：DeepSeek 官方 Base URL 被判无效，换 SiliconFlow 后恢复

**用户报告已登记**：填入 DeepSeek 官方地址时失败；改用硅基流动地址后恢复。本轮只拥有用户指定的 SiliconFlow 测试凭据，**未使用其他服务凭据对 DeepSeek 发起鉴权请求，因此不标为模拟器复现通过或根因已定位**。

独立核验：
- 2026-09-12 读取的 [DeepSeek 官方首次调用文档](https://api-docs.deepseek.com/) 列出的 OpenAI base_url 为 `https://api.deepseek.com`，示例请求为 `/chat/completions`。
- 当前源码 `shared/provider-api/src/main/kotlin/runtime/mobileagent/provider/openai/OpenAiCompatibleAdapter.kt:1496`：`base.trimEnd('/') + path`。没有在这个 join 函数强制补 `/v1`。因此不能把“无条件补 /v1”写成已证实根因，也不能宣称官方地址必须加 /v1。
- 切换服务商会同时改变主机、鉴权、模型、探测兼容性等变量；现有 A/B 描述不能单独排除这些因素。
- 应分别复测保存校验、连接测试、模型 metadata/capability probe、实际 chat，并记录脱敏后的最终 endpoint 路径、状态码及阶段。辅助 probe 不支持不应冒充整体 Chat 不可用；本轮 SiliconFlow 的 probe 与真实工具/视觉调用也出现过不同结果，但尚不能断言与 DeepSeek 同根因。

最小后续矩阵：DeepSeek 根地址及末尾斜杠、SiliconFlow 的 /v1 根地址、普通自定义根及 /v1 根；同时断言最终 URL 与各阶段结果，保持各自匹配的 provider key/model。不要用 SiliconFlow key 测 DeepSeek。

## 证据保存与解释边界

附件 `mobile-agent-diagnostics.zip` SHA-256：
`68425b8ea32b54854383f90c01d9b8c02a3d75d66ad8eccf5bf03d5e82c4ace1`。

私有核验摘要：`.private/userqa-20260912/user-feedback-diagnostic-summary.json`。ZIP 包含 manifest.json、current.ndjson（342 行）、previous.ndjson（543 行）、空 last-crash.ndjson；没有把正文、API Key 或设备指纹复制进本报告。空 crash 文件仅代表包内无 crash 记录，不是整台设备无崩溃证明。

本记录不采纳参考对话中的“已证明预算误杀”“两项均 P0”“换 URL 排除网络/鉴权”或子代理的“DeepSeek 必须 /v1”等过强结论。真实故障现象、当前源码、官方资料与待复现项分别记录。
