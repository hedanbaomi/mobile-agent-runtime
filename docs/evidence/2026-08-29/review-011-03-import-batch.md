<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 0.3 批量导入复核与修复证据

## 范围

本证据覆盖批次协调、`import_items` 与 `import_jobs` 状态同步、批次 generation 绑定、ZIP 展开以及 Worker 边界的 consent ticket 校验。没有调用真实 Provider/Vision，没有修改公告、Cloudflare、CI 或生产配置，也没有执行 commit/push/deploy。

## 已实现

- `KnowledgeRepository.processBatch` 是唯一的批次协调入口，按持久化 item 逐项 claim 并 resume；`KnowledgeViewModel` 不再为同一批次逐项重复 enqueue。每次 job 状态持久化都会回写对应 `import_items`，批次只有全部 item 为 `PUBLISHED` 才能变为 `COMPLETED`，等待、处理、失败均不会提前完成。
- 批次 claim、发布前和发布后均检查 generation。批次自身成功发布后在同一数据库事务中推进其 generation 绑定；外部 generation 变化则 fail-closed，并将批次、未发布 item 和未完成 job 置为失败。
- `KnowledgeArchive.forEachEntry` 先完成中央目录安全检查，再以单 entry payload 回调，移除了旧的 `inspect` 后再次 `extract` 并保留完整 payload 列表的路径。ZIP 扩展先写入持久批次，再逐项建立 CAS/job/item 记录。
- consent ticket 在 consumed 之前和 Worker apply 边界再次校验 KB、job、完整 API model/space binding、文档列表 fingerprint、源文档/CAS 与 retry 状态；校验失败不消费且不触发 Provider/Vision。Provider dispatch 后的不确定结果保留 `UNKNOWN`，已消费 ticket 不会自动重放。

## 自动验证

以下命令在共享工作区执行并成功：

```powershell
.\gradlew.bat :shared:knowledge-api:test --tests runtime.mobileagent.knowledge.KnowledgeArchiveTest :data:sqlite:test --tests runtime.mobileagent.data.KnowledgeArchiveImportTest
.\gradlew.bat :data:sqlite:test --tests runtime.mobileagent.data.ConsentTicketTest --no-daemon
```

覆盖证据包括：

- 流式 ZIP entry 回调与路径/重复/嵌套安全检查；
- 两项批次逐项发布、item/job 状态同步及非提前 `COMPLETED`；
- 外部 generation 变化在处理前失败关闭且不产生搜索结果；
- Vision 未配置时 item/batch 保持 `WAITING`；
- 有效 API consent 只消费并执行一次；文档 fingerprint 变化在消费和 Provider 调用前拒绝；不确定 Provider 结果消费 ticket 但不会自动重放。

`git diff --check` 通过。共享工作区的完整 `:data:sqlite:test` 另有公告模块并行改动导致的两个既有 `AnnouncementRepositoryTest` 失败（与本批次改动无关）；主代理需在并行修改收口后串行重跑全仓验证。

## 尚未由自动测试证明的边界

- 本轮未修改 `MobileAgentApp.kt`。主代理必须将 `ImportWorkerRegistry.batchHandler` 接到 `knowledge.processBatch(batchId, configured)`，删除旧 handler 中对每个 job 的 enqueue 循环；否则 Android 端仍不满足单协调 Worker 的集成条件。
- 当前公共归档入口仍接收调用方提供的 `ByteArray`，每次只保留一个 entry payload；已移除双遍/全量 extracted list，但尚未把 Android URI 完整改造成应用管理 staging 文件。因此 500 MiB 输入峰值、进程在 staging/展开/发布各断点后的自动恢复、ENOSPC 行为仍需设备或受控文件系统测试。
- 未进行 Android WorkManager、500 MiB ZIP、进程强杀、磁盘满、真实 API/Vision、网络中断或计费 Provider 测试；这些不能标记为设备/生产通过。

## 主流程最终集成更新

- `MobileAgentApp` 的 batch handler 已改接 `knowledge.processBatch(batchId, configured)`，旧的逐 job enqueue 循环已删除；启动恢复按 durable batch enqueue，legacy 无 batch 的 COPYING job 才单独恢复。
- Android URI 现以 16 KiB 缓冲限额复制并 `fsync` 到应用私有 `files/import-staging`，仓储调用 `importKnowledgeArchiveFile` 与 `KnowledgeArchive.forEachEntry(File)`；完整 ZIP 不再进入堆，只保留单个受限 entry payload。
- `:data:sqlite:test` XML 104 tests、`:shared:knowledge-api:test` XML 51 tests，均 0 failure/error/skip；API 31 完整 instrumentation 31 tests、30 pass、1 条受控 load skip、0 failure/error。
- 剩余边界收窄为：初始 SAF→staging 复制期间没有可恢复断点，进程死亡需用户重新选择；ENOSPC、500 文件/500 MiB、真实 Provider/Vision 和全阶段杀进程仍未执行，不能标完整 K06。
