<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Agent 自动加载入口

本文件适用于整个仓库。**开始任何工作前，必须完整阅读根目录 [agent.md](agent.md) 和 [HANDOFF.md](HANDOFF.md)，再阅读 [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) 及本次任务涉及的专题文档。** 不得仅阅读本入口便开始修改。

**工作结束、受阻或中断前，必须更新 HANDOFF.md；涉及架构、接口、范围、验收或许可变化时，必须同步维护对应文档。未维护文档，不得声明完成。**

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
