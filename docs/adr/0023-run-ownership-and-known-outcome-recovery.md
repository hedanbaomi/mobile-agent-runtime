<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0023：会话运行所有权与已知结果恢复

日期：2026-10-05（Asia/Taipei）。关联 R12/R19/R35、A06/C22—C25/K05/S34/L04；对应复审 R-01—R-04。

## 背景

界面的等待状态不能代表执行已退出。超时后，旧事件仍可能到达；查询成功缓存与 pending 分开提交也可能留下相互矛盾的恢复状态。搜索撤权决定是否披露内容，不能改写请求已经送达并完成的事实。

## 决定

- 每次发送绑定不可变 `(runId, conversationId, generation)`。页面发布许可与执行所有权分开；watchdog 撤销页面发布许可、取消本次 job 和审批等待。旧执行未收尾前保留新输入并拒绝新发送，允许查看其他会话。返回原会话也不重新绑定旧运行。
- 预处理、迟到 RequestPrepared、消息刷新、错误、引用及 finally 均校验所有者后才更新页面。引用和审批/executor 资源随原运行持有；旧运行继续保存自身结果、关闭自身审批，不能清理别人的状态。前台服务按 runId 持有引用，最后一个所有者退出才停止。
- watchdog 不再使用独立异步写入把未退出的运行强制改成 UNKNOWN；collector 负责实际结果和取消分类。执行尚未退出时不能启动新运行，进程死亡仍由既有 in-flight 恢复持久化 UNKNOWN，并保持明确重试门禁。
- 查询向量成功缓存与本次 owner claim 的 CAS 清理同事务提交；完整提交之后才标记成功。恢复在现有 Embedding 同意成立后校验缓存空间、查询键、绑定维度、长度及有限数值。有效缓存是已知成功证据，优先本地检索，无需新付费重试授权；无证据的未知尝试继续阻断。不同 live owner 不被恢复清理。
- 搜索的派发前拒绝保持 DENIED；收到合法结果后撤权为 `Denied.completion=COMPLETED_WITHHELD`。Runtime 与持久工具记录保留同名状态，安全 JSON 明示 dispatched/completed/resultWithheld/chargesMayApply 与禁止自动重放。未知结果仍是 UNKNOWN，缓存披露守卫不能把它降级成普通拒绝。
- URL 的原始完整值先做长度与安全校验；过长或需要脱敏改写的 URL 丢弃。标题和摘要可限长，身份地址不截断或改写。

## 替代方案与影响

只判断 `streaming` 无法隔离迟到事件；清空所有共享字段会伤及新页面。此轮采用运行所有者及小范围资源封装，保留现有 Runtime/SQLite 边界，没有引入并行会话执行或数据库迁移。缓存优先只恢复已有成功证据，不扩大外发授权。已完成但扣留使用兼容的 Denied 扩展，其他工具默认行为不变。

验证覆盖真实 SQLite 事务故障、owner 替换和同空间并发；真实 ChatViewModel/SQLite 配合可控截止时间与迟到事件；搜索派发/撤权/缓存与 URL 身份边界。受控 fixture 不代表真机杀进程、线上供应商、长稳或 OEM 接入验收。最终结果见 [修复证据](../evidence/2026-10-05/review-464f-fixes.md)。
