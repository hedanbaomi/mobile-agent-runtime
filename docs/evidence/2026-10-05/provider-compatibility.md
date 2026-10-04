<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Provider 兼容性修复（2026-10-05，Asia/Taipei）

关联 R02/R03/R12、A03/A04/A10。基线 main：8d5bc8dba1b260d42086a26ea7dd357f7884b87a。

## 变更

- Chat 与 Responses 共用客户端错误分类；nullable code 不遮蔽明确模型缺失信息，未知明确 code 优先于消息。
- 普通参数请求错误不因出现 unsupported/stream/tool 被标为 FEATURE_UNSUPPORTED；消息回退仅消费 error.message 或兼容纯文本。
- Responses 运行期 HTTP 错误与连接测试使用同一脱敏分类；401/403、408、429、5xx 的既有状态、重试和计费策略保留。
- 纳入前轮已验证 WIP：Chat 基础连接测试省略可选采样、停止与高级参数，仅保留输出预算别名，能力探测保留模型参数；模型编辑空 ID 沿用已有 ID。

## 回归与实际结果

永久回归 OpenAiProviderErrorCompatibilityTest 通过公开 adapter + Ktor MockEngine 验证两种协议的错误 envelope、状态优先级、脱敏和 Responses 运行期分类；保留原有探测参数与 capability 测试。
- Provider JVM：236/236，零失败/错误/跳过；Android debug/review/release 单元测试各 216/216。
- API 34 临时空数据模拟器：ProvidersViewModelConnectionDeviceTest 5/5，包括空 ID 编辑持久化、连接/能力状态隔离、错误类型、重试、切换目标丢弃旧结果。最后扩充仅涉及 HTTP 消息分类，设备测试相关 VM/测试源码自成功后未改。
- 最终源码严格门禁：licenseGuard、licenseGuardReverse、check、verifyCiPins、verifyDependencyLock、verifyDependencyVerification、verifyWorkflowYaml，offline + dependency-verification=strict；BUILD SUCCESSFUL，995 tasks（3m11s）。此前带 connectedDebugAndroidTest 的同组门禁也成功（1070 tasks）。
- REUSE：850/850（最后文档收据后提交前复核）；CodeGraph 同步成功。
- 独立只读审阅：已核验路径/model 的 DSH Flash 初审及两次增量复核。初审发现的协议文案、模型缺失消息形状、可选探测参数、失效 KDoc、不透明 404 回归均已处置；额外消除 model list/catalog/endpoint/route/path 的消息误判。最终 PASS；主协调器对 8 个最终源文件逐个核对审阅 SHA256 相同。
- 无明确模型缺失证据的数字 message、param=model + Unknown endpoint、不透明 404 明确保留 ENDPOINT_UNSUPPORTED；不将这些形状强行改成 MODEL_NOT_FOUND。
- 本文件是提交前的源码/本地验证证据，PR/合并/干净 APK 的最终身份以交付收据及根工作区交接为准；无源码活跃认领。

## 边界

没有调用真实收费 Provider，没有读取或重新使用用户 API Key。历史 Command Code 失败缺少原始状态码/协议/脱敏错误 envelope，不能据此断言真实服务已恢复；全量设备、真实 Provider 和长稳验收仍需单独记录。Debug 签名 review APK 应来自最终合并后的干净源码，构建 provenance 和哈希随交付保存。
