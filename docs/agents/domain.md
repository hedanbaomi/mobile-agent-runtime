<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Domain docs

本仓库采用 single-context 布局。

探索业务或修改架构前，先读取根目录 `CONTEXT.md`（存在时）以及 `docs/adr/` 中与当前任务相关的 ADR。缺少 `CONTEXT.md` 时继续工作，不以此作为阻塞，也不创建空文档冒充领域建模完成。

输出中的领域术语应沿用 `CONTEXT.md` 已定义的词汇。若实现决定与现有 ADR 冲突，必须显式指出冲突并通过新的 ADR 重新决策，不能静默覆盖。
