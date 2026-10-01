<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR 0018：导入再投递与追加索引栅栏

日期：2026-10-01。状态：实现及定向本地测试通过；最终门禁与设备证据见本轮验收修复记录。

## 决定

WorkManager 的下一次批次投递不保证重新创建 Application。每次取得进程内批次唯一执行权后先核对持久 job、item 与 attempt，再派发；进程内批次执行权跨 repository 实例共享，活跃 owner 不被恢复逻辑覆盖。遗留的 PREPARED/IN_PROGRESS 视觉诊断结清为 UNKNOWN_OUTCOME，保留原 dispatch_status，不推断派发成功。旧 pipeline attempt 终态仍不可改写。仅固定目标和资料范围的有效批次授权可按 ADR 0014 的上限重试，暂停、取消、阻塞与单文档一次性授权不被自动恢复。

前台 Activity 再次开始时，在 IO 核对可恢复批次，以 WorkManager KEEP 重新入队；存在的唯一工作不被替换。初始资料集合未完整复制的 staging fence 仍阻止处理。预检异常不能让同一非终态 item 被无限领取：保留源资料，记录可见 FAILED；未知外部结果仍走原有未知结果门禁。

派生索引的正常追加不再使同库其他暂停/等待批次失效。新旧 generation 都必须 READY、同一 KB 和 embedding space，且旧的所有 chunk/version/space 成员都仍在新集合内；满足时只推进引用相同旧 generation 的非终态批次栅栏，不改变停止状态或授权。第一次追加在发布事务内先证明旧栅栏为空、embedding space 相同且无任何已发布文档，再推进同一空栅栏。普通重建、删除、替换版本、embedding 重绑定或未知外部 generation 不享有此例外。资料范围和视觉目标哈希校验保持独立。

此决定细化 ADR 0011 的 generation 变化阻断：正常的、可证实只追加的派生索引变化可推进；破坏性或不能证明安全的变化仍失败关闭。不会迁移 schema、重写既有终态或把历史失败批次悄悄恢复。

## 验证与边界

新增回归覆盖无需 Application 重启的再投递、遗留在途诊断结清、暂停批次跟随追加、空库第一次独立追加、删除仍阻断、CAS 预检失败有界终止。保留未授权视觉阻断、成员变更撤销原同意、目标变更、重试耗尽与成功缓存复用反例。历史批次实际根因与 ANR 仍须原数据库/trace 才能确定，不以这些合成回归宣称原设备验收全部通过。
