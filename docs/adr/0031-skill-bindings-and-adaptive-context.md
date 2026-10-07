<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0031：Skill 启停关联、授权投影与模型窗口压缩

日期：2026-10-07（Asia/Taipei）。关联 A06、S09/S13、R34/C20—C25；验证见 [专项证据](../evidence/2026-10-07/skill-workspace-adaptive-context.md)。

## 背景

禁用已绑定 Skill 会让 Agent 保存/新会话失败；唯一启用 Skill 的身份被复用于内置工作区工具，令其误取 Skill 窄权限。平台 SAF 目录可写时，工作区描述又只反映后端，不能说明只读 Agent 的有效授权。累计运行预算较小且 Python broker 将持久时限截至 180 秒；固定消息/段轮触发使 1M 模型在输入压力很低时仍反复摘要。

## 决定

- 安装身份、关联意图、当前启用状态和会话快照分别处理。已有禁用安装允许保留关联并保存，新快照仅含启用安装；重新启用仅影响后续新快照。每次 Run 再过滤当前安装/关联，卸载规则和新增禁用绑定拒绝保持，无数据库迁移。
- 内置 workspace/shell 的上下文清除 Skill 身份后重新冻结/解析。真正 Skill/memory 继续用原 Skill envelope；不能仅清除 identity 却沿用已收窄 capabilities，也不能用 Agent 权限替代 Skill 授权。
- `workspace_list` 保留现有字段，`readable/writable` 取当前 resolver 与后端支持操作交集。新增 `authorized_operations`、每操作的 `operation_scopes.whole_workspace/path_scopes`、`workspace_scope`、后端 `atomic_replace` 支持及 `create_only_operations`。路径仅为规范相对范围；无根路径/URI。描述阶段只 revalidate，真实派发仍消费自身 ONCE，文件授权不因枚举被消费。SAF 不支持原子覆盖，不提升平台 grant 为 Agent 授权。
- `modelAwareCompaction=true` 为缺失配置的默认值（包括旧 JSON）。有效输入预算仍取已解析模型窗口扣除输出预留与用户更小预算的较小值；未知窗口使用明确本地保护值。85% 压力触发并压至 60%，忽略固定条数/用户轮/段轮触发；`false` 恢复固定模式。最近轮、初始目标、图片、私有续接和完整工具交换继续保护。摘要本身仍有有界请求/输出限额，固定内容超窗仍明确失败，不保证 Provider tokenizer 精确计量。
- 累计默认 128 模型请求、100 工具、16 摘要、1800 秒准入；可配置模型 2—512、工具 1—1000、摘要 1—64、时限 1—86400000 ms。UI 以整数秒编辑，未改变的旧毫秒精度保留。显式旧值不覆盖，压缩不重置累计预算；持久 JSON 与内存 Run 相同，Python broker 不再强制 180 秒上限。180 秒操作停滞、实时撤权、审批、取消及 UNKNOWN 不重放规则保持。Python 付费调用仍须独立 Run token 授权。

## 替代方案及验证边界

按固定条数随窗口成比例放大仍会因短消息过早摘要；本次按完整请求压力决策。完全移除预算或把平台权限当作 Agent 权限均不采用。验证需覆盖同一历史在 16K/1M 的差异、固定模式、禁用/重新启用快照、真实 factory 单 Skill、SAF 只读/创建/范围与 ONCE 负向回归；权限变更须独立只读审阅。合成 Provider 与模拟器证据不替代真实长回复、物理设备或 OEM SAF 验收。
