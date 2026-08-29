<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 0.2 能力探测复核与修复证据

## 范围

本证据覆盖 F-009 的 OpenAI-compatible Provider 能力探测与 Providers 页面记录路径。未调用真实 Provider、未发送真实可能计费请求，也未修改生产配置。

## 修复结果

- `GET /models/{model}` 的 2xx 仅在响应为可解析对象且 `id` 与配置模型 ID 完全一致时记为 `metadata=verified`；4xx、空响应、模型 ID 不匹配或格式错误均失败。
- `stream`、`tools`、`image` 各有独立的探测结果。仅在用户明确同意后，才为已声明且属于 Chat 的能力发送最小请求。
- stream 必须返回 `text/event-stream`，至少有一个可解析 payload，并以 `[DONE]` 正常结束；tools 必须返回指定虚拟函数的有效 JSON 参数；image 必须返回非空文本内容（兼容 OpenAI content parts）。HTTP 成功但语义不满足时记为 `invalid-response`，不会记为支持。
- 结果源使用稳定的 `metadata=...;stream=...;tools=...;image=...` 摘要。`CapabilityReport.supports*` 只来自实际验证结果；metadata 失败时全部为 false。
- `charged=true` 只表示已尝试可能计费的 Chat capability POST（包括 Provider 拒绝、响应不完整或传输中断导致的 `unknown-outcome`）；metadata GET 和未声明能力不标记为已计费。未同意时不发请求，保持 `PROFILE_ONLY`。
- ProvidersViewModel 展示每项结果和费用边界，并将同一摘要写入 `capability_probes`；保存新 API Key 时在 Provider 指针切换成功后调用 SecretInventory 的引用感知退休流程，不影响仍被共享 Header 或不可变快照引用的旧密钥。若引用扫描因损坏数据 fail-closed，保存仍保持一致，旧密钥不回收并向用户提示。

## 验证

命令：

```powershell
./gradlew.bat :shared:provider-api:test
```

结果：修改后的首轮（包含 consent、metadata malformed、stream 完成/缺 `[DONE]`、tools semantic、image 4xx）`BUILD SUCCESSFUL`，33 tests completed，0 failure/error/skip。随后补充 metadata 4xx 用例使总数达到 34；该最终计数待主代理在并行 Gradle 工作结束后统一重跑确认。

新增/更新的 MockEngine 覆盖：未授权零网络、metadata malformed、metadata model-id mismatch、stream 正常完成、stream 缺少 `[DONE]`、tools 有效函数调用、image 4xx；所有失败路径均断言不误报支持与费用语义。

主流程最终串行复验：`:shared:provider-api:test` XML 合计 37 tests，0 failure/error/skip；其中 `OpenAiCompatibleAdapterTest` 23 tests。此前并行 build cache 删除冲突不是测试失败，已由本次无并发复跑取代。

## 真实 Provider 边界

本轮只使用本地合成 HTTP 响应，不能证明任一真实服务的 OpenAI-compatible 协议完整兼容性、实际计费金额或流式实现质量。设备 UI、真实网络、Provider 费用、TLS、代理/网关差异仍需在用户明确安排的验收中单独记录。
