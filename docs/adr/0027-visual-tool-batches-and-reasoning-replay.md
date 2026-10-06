<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0027：视觉工具批次与显式推理回传

日期：2026-10-06。状态：采纳。对应 R02/R08/R34，K04/K08、C22/C26。

## 背景

正式 v1.1.1 诊断记录搜索和工作区枚举均成功，随后两次带四张图片的 Chat 请求得到 HTTP400/PROVIDER_REJECTED，却显示 INTERNAL。诊断没有服务商原始拒绝正文，不能证明其唯一原因。源码及修复前失败回归确认两处协议缺陷：同一 assistant 多工具调用的结果尚未齐全时插入 user 图片；显式返回的推理未被回传到 Chat 工具请求。用户配置的是 DeepSeek 推理模型；[DeepSeek 官方契约](https://api-docs.deepseek.com/guides/thinking_mode/)要求含 tools 的后续请求保留各 assistant 的 reasoning_content。

## 决定

1. 批次中所有工具结果先进入工作上下文和持久 transcript，再逐调用附加 user 图片。图片仍来自原政策回调并受数量/字节/来源授权及输入预算约束，不删除图、不重放工具。
2. 旧 transcript 仅在请求投影中修复完整、ID 配对的交换：标记为 toolEvidence 且仅含 ImagePart 的 user 行可移到该批次工具结果之后。真实用户行、缺结果、重复 ID 不跨越；原始行内容、ID、时间和数据库顺序不变。含图原子交换保持在压缩候选之外，持久摘要来源顺序要求不放宽。
3. ChatMessage.reasoningContent 只来自 Provider 明确声明的推理或持久 ReasoningPart，不从正文推断。保留所有非空流片段，包括空格和换行。Chat 含 tools 且有观测推理时把该值放在独立 reasoning_content 字段；同一请求的合成 assistant 使用空字段。无观测推理、无工具及 Responses 不编码它；不替代 Responses 的私有 encrypted continuation。
4. Chat 的实际推理回传纳入 UTF-8 保守输入预算；Responses 不计该字段。预览用占位符，请求内容诊断移除 reasoning_content；摘要不含推理、不外发私有续接。用户已可见的显式推理展示规则沿用。
5. 仅实际需要 Chat 工具回传时，持久推理达到保存上限阻止不完整重放；不需要回传的会话可继续。明确 HTTP400 分类为 PROVIDER_REJECTED，保留安全文案，无自动重试。

## 替代与影响

删除图片、关闭推理、自动重试同一失败请求都不能修复协议或保证费用边界，不采用。没有推断服务商秘密和隐藏推理；不改变模型或授权，不修复/覆盖用户原始记录。旧历史只有压缩后的摘要时使用运行时摘要字段，并不声称还原已省略推理。

## 验证

两协议多工具四图出站及事件持久顺序、Chat 推理分片/空白、预算/预览/诊断隔离、旧历史投影/不完整交换与错误分类。独立只读复审为门禁，执行结果和真实 Provider/OEM 设备边界见 [v1.1.2 证据](../evidence/2026-10-06/kb-and-conversation-archive.md)。
