<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0022：多供应商搜索与 Agent 显式授权

日期：2026-10-05（Asia/Taipei）。关联 R12/R28/R35、S10/S31/S33/S34。

## 背景与决定

用户要求搜索不再限于 Brave，至少支持 Tavily，并让用户逐 Agent 控制是否允许搜索，允许后无需逐次批准。

- 接入 Brave、Tavily、Exa 的固定官方搜索协议。凭据在 Android Keystore 加密，普通设置只保存 opaque ref。沿用旧 Brave 设置键，新服务独立设置键；回收扫描所有服务引用，保留未选中或停用服务的密钥。未知服务 ID fail-closed。
- 在现有 `permissionSettingsJson` 中保存布尔 `webSearchEnabled`，默认关闭；严格布尔解析，保留其他权限字段。沿用数据库、备份和快照字段，无 schema 迁移。仅冻结快照与实时 Agent 均允许搜索时暴露工具。宿主搜索 executor 跳过逐调用审批，其他工具保持原有确认政策。
- 每次执行、发送前和缓存结果返回前检查服务/密钥/配置修订及 Agent 权限和修订。切换服务、关闭再开启、Agent 保存均使旧 executor 失效；未含搜索权限的旧快照不会扩权。查询已发送后的撤权不能撤回远端请求。
- 服务切换同步更新 UI 名称并清除上一家密钥控件状态；保存、启停和删除携带渲染时的服务 ID，宿主拒绝与当前选择不符的旧回调。异步设置刷新仍使用原有 latest-revision 规则。
- 专用 HTTP 路径封闭 URL/认证/参数，保留真实 DNS 公共地址检查。禁止 redirect、自动 retry、代理、Cookie、Authenticator 和跨调用池复用；503 即使携带 Retry-After 也不重试。收费查询发送后异常采用 UNKNOWN_OUTCOME，不自动重放。
- 三服务只返回有界标题、公开 HTTPS 链接及摘要/高亮；先脱敏再解析，控制整体 JSON 输出上限，明确标为不可信，不执行网页内容。

## 替代与边界

不接受任意自定义搜索 URL 或可编辑认证头。增加服务需明确新增协议与回归，避免模型将搜索凭据带往其他地址。没有使用用户密钥或真实付费请求；本地协议 fixture 和模拟器结果不等于线上供应商验收。

协议依据：[Brave](https://api-dashboard.search.brave.com/app/documentation/web-search/query)、[Tavily](https://docs.tavily.com/documentation/api-reference/endpoint/search)、[Exa](https://exa.ai/docs/reference/search)。使用方式见 [联网搜索](../WEB_SEARCH.md)，验证见 [本轮证据](../evidence/2026-10-05/logging-and-search.md)。
