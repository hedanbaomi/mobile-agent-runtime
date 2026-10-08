<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0032：工作区增删改、版本条件与授权保留

日期：2026-10-08（Asia/Taipei）。R19/R20/R26/R28/R32，S13/S19/S24/S25/S30；补充 ADR-0006/0008/0009/0031。

## 背景

源码确认 Shizuku 两个适配器和 Wired 对普通写入、建目录、移动、删除，只要有 expected_version 就无条件报 CONFLICT，而模型 schema 却暴露该参数。默认重选目录、新 Agent 提交工作区草稿还会把显式读写降为只读。SAF 直接拒绝普通覆盖写，空可创建目录缺少删除入口，冻结 Run 无法删除随后创建的文件。修复以实际增删改成功验收。

## 决定

- Backend 按操作声明 expectedVersionCapabilities。Internal 保留完整目标 token 的提交前复核；Shizuku/Wired 普通 RPC 不支持条件，patch 保留既有事务；SAF 没有条件变更原语。不改变 Binder、Companion 协议和数据库。
- 新 Run schema 在所有授权后端均不支持普通条件时移除可选 expected_version，模型直接使用正常文件工具。混合后端按 workspace_list.expected_version_operations 选择条件。version_scope=target_entry，兄弟文件变化不构成该目标冲突；分页 cursor 仍与所列目录的稳定页绑定。
- 不可履行的旧条件请求在派发/消费 ONCE 前为 WORKSPACE_VERSION_UNSUPPORTED，不伪报 CONFLICT、不静默删除条件、不自动重执行。真正目标变化仍为 CONFLICT。mandatory patch 无条件实现时不暴露 patch/atomic_replace。拒绝测试不代替正向操作验收。
- 普通 SAF replace=true 可以更新支持写入的现有文档：检查 grant/文档写 flag、大小和配额，通过 wt 流写入；关闭后复核 persisted grant、原路径同一 document ID、类型及精确有界回读（内容长度加一字节）。SAF 保持 atomic_replace=false/无原子 patch。打开截断流起任何失败为 UNKNOWN_OUTCOME，不能自动重放或宣称回滚。这取代旧规则对所有 SAF 既有文件写入的一律拒绝，普通更新与原子补丁分别处理。
- 已有可写非虚拟文档时，即使根不能新建也暴露普通写工具。可创建空树保留删除操作面及 READ_WRITE 授权，使新建文件可在同一 Run 删除；实际条目仍须 FLAG_SUPPORTS_DELETE，只删普通文件/空目录，根禁止删除。
- 默认重选/自动附加空能力集添加只读基线，并保留同 Agent/workspace/scope、当前策略下有效的普通持久授权；新 Agent deferred draft 同样保留刚显式选择的读写。新附加不会默认新增写权，显式只读/非空能力集仍精确对齐。撤销、过期、ONCE、task/session、Skill、其他 workspace 和路径范围不被恢复或扩大；旧 Thread 不自动改绑。
- CAPABILITY_DENIED 与 WORKSPACE_VERSION_UNSUPPORTED 进入闭合诊断码白名单，不增加正文、路径、URI、授权对象或参数。

## 取舍与验证

SAF 普通覆盖是 provider 流：外部读取可能见到中间内容，中断可能留下部分内容；成功须核验，不伪称原子写。需要原子 patch 时使用已有支持的后端。file_move/file_copy 的模型暴露状态保持既有决定。

真实 SAF 重选后保持写权，验证新建→建目录→长/短/空/Unicode 修改→回读→删除全部成功；Internal 目标版本、Shizuku 两条 adapter+真实存储、Wired adapter+真实引擎分别覆盖兄弟变化。负向覆盖只读/撤销/错误作用域、真实冲突、无条件契约零派发/无 ONCE 消费、覆盖中断 UNKNOWN。实跑与独立审阅见 [专项证据](../evidence/2026-10-08/saf-and-file-version-preconditions.md)，不代替 OEM 真机、实际 Shizuku Binder 或物理 Wired 验收。
