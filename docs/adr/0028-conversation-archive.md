<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0028：对话归档与共享运行准入

日期：2026-10-06。状态：采纳。对应 R37、C27—C29。

## 背景

用户要求长按会话弹出可扩展二级菜单，目前只提供归档；归档会话在设置 → 数据与备份 → 已归档的对话查看。归档是保留历史的整理操作，不能删除消息、改变冻结快照或重新授予权限。

## 决定

- DB v30 给 conversations 添加 archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1))。沿用迁移事务及幂等 ensureColumn，v29/旧库默认为未归档；DDL 或版本写入失败回滚。归档/恢复只更新布尔值，保留原时间、消息、运行、工作区和快照。
- ConversationRepository.list 保持包含全部会话，提供 active/archived 投影供界面使用。菜单以 host 提供的 SessionAction 列表渲染，不在两处列表中固定菜单项；当前默认列表只有 ARCHIVE。
- 设置入口列出已归档会话，可打开只读历史或显式恢复。打开、重建和重启不自动恢复；输入与发送都被禁用，发送入口复核持久状态。
- setArchived 在事务中拒绝所有非终态或未知运行状态。RunRepository.create 在同一共享持久化边界复核未归档再插入；新 USER append 同样在事务中复核。另一 Activity/VM 在预处理间隙归档时，新运行零准入、零模型/工具派发，保存草稿并显示失败；已合法写入的用户消息不被删除，失败不创建幽灵运行或向归档历史追加错误行。
- 完整导出保留 archived，旧传输数据缺字段默认为 false。加性字段维持传输格式 v1，DB 版本单独升 v30；逐会话与最终数据库变更栅栏拒绝导出期间的归档变化。旧版客户端不承诺保留新字段，不据此支持降级。

## 替代与影响

不用本机偏好表隐藏会话，因为那会使备份丢失状态；不用删除或变更快照模拟归档。UI 内存锁不足以协调多个 VM，故事务准入是必需的共享边界。

## 验证

真实 SQLite v29 升级/重开/幂等/注入失败回滚、归档保全与全部 Run 状态、归档先发生和新运行先发生的交错、旧/新传输与 ZIP 往返；设备长按/空操作列表/只读/恢复。执行结果见 [v1.1.2 证据](../evidence/2026-10-06/kb-and-conversation-archive.md)，独立迁移审查为门禁。
