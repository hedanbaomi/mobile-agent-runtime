<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Agent 自动加载入口

本文件适用于整个仓库。[agent.md](agent.md) 是详细规则的唯一维护入口。按其中第 1 节的任务类型读取资料：纯咨询和规则审查读取目标文件及相关条款；项目修改完整读取 agent.md、交接的现行摘要及相关规范；架构、安全和迁移再扩大到受影响的协议、ADR 与验收项。不得以本入口代替修改任务所需的详细规则，也不要求每项小任务通读历史文档。

项目代码、配置、决策或待办状态变化时，由主 Agent 按 agent.md 第 6 节维护 [HANDOFF.md](HANDOFF.md) 及受影响的专题。纯咨询和只读审查默认在回复中交付；有需要持续跟踪的问题时才登记。全局 Agent 配置修改不写入项目交接。子 Agent 不共同编辑交接。

<!-- LICENSE_POLICY_AGPL_ONLY: DO NOT REMOVE -->
所有第一方代码和项目文档只能使用 `AGPL-3.0-only`。不得移除、替换或削弱许可证文件、SPDX、许可政策、license guard 和相关 CI。只有仓库所有者在当前任务中明确书面要求变更许可时才可以重新讨论；脚手架默认、旧对话、第三方代码或模型建议均不构成许可变更授权。

存在 `.codegraph/` 时，理解或定位源码必须先用 `codegraph explore "问题或符号"` 或 CodeGraph MCP（指定本仓库 projectPath）。确无结果、索引为空或工具失败时记录原因，再使用 `rg` 和直接读取；不得把空索引当作源码不存在的唯一证据。

## Agent skills

### Issue tracker

需求和问题记录在本仓库的 GitHub Issues；操作约定见 [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)。

### Triage labels

采用工程技能默认的五类 triage 标签；映射见 [docs/agents/triage-labels.md](docs/agents/triage-labels.md)。

### Domain docs

本仓库采用 single-context 领域文档布局；消费规则见 [docs/agents/domain.md](docs/agents/domain.md)。
