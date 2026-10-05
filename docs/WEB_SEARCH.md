<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 联网搜索

设置页支持 Brave、Tavily 和 Exa。选择服务，保存 API Key 并启用；每家服务独立保存 Android Keystore 加密凭据。切换服务保留其他密钥，删除只作用于当前服务。未保存选择的旧安装默认使用已有 Brave 配置；未知服务 ID 不会回退并发送 Brave 密钥。

Agent 编辑页的“允许联网搜索”默认关闭，须保存。开启后可新建会话使用；未包含搜索权限的旧快照不会扩权。关闭并保存立即阻断旧会话后续查询与缓存结果复用。允许搜索的 Agent 无需逐查询批准；开关不改变其他工具的权限或确认设置。使用搜索可能收费，查询文本会发送到所选服务商。

切换服务立即更新输入框名称并清空未保存的密钥。保存、启停和删除都绑定界面当时显示的服务 ID；过期界面的操作会被拒绝，避免异步刷新时把一家的密钥误存或删除到另一家。

模型只能提供 `query`（1–400 字符，无控制字符，至多 50 个空白分隔词）和 `maxResults`（1–10，缺省 5）。请求由宿主构建：

| 服务 | 固定目标 | 认证 | 请求方式 |
| --- | --- | --- | --- |
| Brave | `https://api.search.brave.com/res/v1/web/search` | `X-Subscription-Token` | GET；`safesearch=strict` |
| Tavily | `https://api.tavily.com/search` | Bearer | POST；basic/general；关闭自动参数、answer、raw content 和 images |
| Exa | `https://api.exa.ai/search` | `x-api-key` | POST；auto；返回 highlights |

模型不能选择 URL、Header 或任意请求正文。连接拒绝私有地址解析，不使用代理、Cookie 或跨调用连接池；禁止重定向及自动重试。实际发送前再次检查当前服务、配置修订、有效密钥和 Agent 的冻结及实时授权。Agent 或服务配置修订变化使旧执行器失效，恢复开关也不会复活旧调用。

HTTP 响应最多 1 MiB，工具输出序列化后最多 32,768 字符。三家结果统一为 `provider`、`untrusted: true` 和 `results`（title/url/snippet）；仅保留公开 HTTPS 链接，最多 10 项。完整 URL 超过 2,048 字符直接拒绝，不能截断成另一地址；含活动密钥、需要脱敏改写的 URL 整条丢弃。标题与摘要解析后先脱敏再限制长度。不返回服务商 answer/raw content，不自动抓取或打开结果网页；所有正文与外部链接仍是不可信资料。

同一执行器的相同调用 ID 与参数只执行一次。发送前撤权为 `DENIED`；成功响应后撤权为 `COMPLETED_WITHHELD`：查询已经发送并完成、可能收费，但结果内容不再披露。已发送而无法确认结果的失败仍为 `UNKNOWN_OUTCOME`。这三种状态分别保存，缓存复用不重派发，进程恢复不把已知完成的工具改成未知。撤权无法撤回已经送达服务商的查询。

协议及决策见 [ADR-0022](adr/0022-configurable-search-and-agent-permission.md)，回归与尚未验证的线上边界见 [本轮证据](evidence/2026-10-05/logging-and-search.md)。
