<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR 0033：Agent 删除与默认工作区偏好的事务清理

日期：2026-10-08。状态：已实现并通过独立只读审阅、JDBC7项及Android旧数据升级/失败回滚验证；随v1.1.4候选集成，发布收据见回归证据。

## 原因

新 Agent 保存工作区失败后，回滚调用 AgentRepository.delete。该方法删除 profile 和 prompt，但依赖 agent_workspace_defaults 的 ON DELETE CASCADE 清理偏好。Android BundledSQLiteDriver 没有默认开启 foreign_keys；JDBC fixture 则开启了它。实际 Android 回归产生孤立偏好，下一次初始化被 Migrations.validateWorkspaceBindings 拒绝。

## 决定

- 删除未被快照保留的 Agent 时，同一事务显式删除该 Agent 的默认工作区偏好、prompt 和 profile。快照保留规则不变；删除失败时全部回滚。
- SQLite schema 升为31。仅升级15—30版本时，清理已不存在 Agent 的默认工作区偏好，然后执行原有完整校验。保留现有 Agent 的偏好及其修订、所有工作区和能力授权，不创建替代绑定。
- schema31 的孤立或畸形数据继续被校验拒绝。迁移中后续失败必须回滚清理及版本更新。
- 默认目录是新 Thread 的偏好，不是 Capability Grant。该修复不扩大权限、不重绑已有 Thread，也不改变整个连接的外键策略。

## 验证

JDBC 分别覆盖外键关闭/开启、其他 Agent 保全、快照阻止删除、删除失败回滚、旧版迁移重复执行、工作区/Grant 保全、迁移失败回滚及当前版损坏拒绝。Android 的失败提交回归在回滚后检查孤立偏好，并再次运行启动校验。实际旧版设备数据覆盖升级另行记录于[回归证据](../evidence/2026-10-08/knowledge-query-and-release-regression.md)。

该 ADR 补全 [ADR-0006](0006-conversation-sidebar-workspace-access.md) 的新 Agent 保存失败回滚约定，不改变默认目录或授权的领域含义。
