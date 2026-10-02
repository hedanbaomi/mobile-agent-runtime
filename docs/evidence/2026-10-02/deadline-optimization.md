<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-10-02 限时阶段优化证据

任务在附件修复 PR [#34](https://github.com/hedanbaomi/mobile-agent-runtime/pull/34) 合并/交付后开始；main 基线 `abe7a908fb1c0f23dbee2b7f6a0e8c9978ac48e2`，分支 `codex/deadline-optimization-20261002`，独立受管 C checkout。原 E 工作区附件、计划和历史 HANDOFF WIP 保留。用户要求 2026-10-02 10:00 PDT（17:00 UTC）前完成普通 CI 合并和 Debug 签名 Review APK，以阶段收口优先；新增范围于 15:24 UTC 冻结。

## 实现

1. 抽取一个固定摘要下载器，三处 ModelPack/USearch/官方 CPython 共用。最多三次恢复暂时性网络失败、HTTP 408/429/5xx 和声明长度截断；默认 1/2 秒退避、30/120 秒超时。摘要、TLS/协议、其它 4xx、本地 I/O、取消直接失败，不记录原始 URL/query。独立同目录临时文件完整摘要验证后发布，失败清理、旧缓存保留、首次目录创建；合法缓存零网络/mtime 不变。仅明确不支持原子移动时回退。
2. 用显示专用 `snapshotAgentIds` 替换逐会话 `getSnapshot`，只选两个列、去重、500 绑定参数分批。完整执行快照校验与同步刷新语义保留；避免此路径读取全快照和解析 KB/Skill ID JSON。其它刷新查询及消息解析仍存在。

固定版本、URL、SHA、CPython 来源限制/禁重定向、TLS、严格依赖验证、AGPL/许可门禁和执行权限未变；无新第三方依赖、迁移或 Provider 协议变化。

## 验证

- `./gradlew.bat :data:sqlite:test :app-android:compileReviewKotlin --offline --dependency-verification=strict --no-daemon`：退出 0，4m10s；三处 Gradle helper 引用和 Review Android 消费路径实际编译通过。
- `AgentSnapshotDisplayProjectionTest`：真实 JDBC SQLite 4/4 通过；1,201 个完整快照映射需 1,201 次查询，显示投影相同结果只需 3 次，每次最多 500 个绑定。覆盖两个 Agent、重复/未知/类似 SQL 的 ID、空列表及损坏非显示元数据。
- helper 初版 10 条及既有 5 条门禁用例通过后，独立审查发现首次空目录回归。主 Agent 修复并扩充首次/被文件占用目录、成功响应中取消、连接拒绝，当前 13/13 故障用例及既有 5/5 included-build 门禁用例已重新通过；旧 XML 不冒充本次结果。
- `./gradlew.bat check reviewGate --offline --dependency-verification=strict --no-daemon`：退出 0，9m17s，1,086 tasks（826 executed/245 from cache/15 up-to-date）。门禁完成时 250 份 JVM XML 共 1,790 条测试记录，0 failures/errors/skips；未变化的 up-to-date baseline suite 不宣称全新重跑。许可正反检查、native 16 KiB、APK notice、171-component SBOM 与 provenance 均通过。
- `reuse lint`：退出 0，828/828 文件 copyright/license 信息齐全。独立复核 APPROVE；最终八个源文件摘要与 reviewer 记录完全一致。

## 独立复核与取舍

Jev `jev-1.13.0` 选择 DSH DeepSeek V4.1 Flash 业务只读审查和 native `gpt-6-luna/xhigh` 独立实现审阅；无递归 delegation。DSH 经内置浏览器核验 E 源与合并基线一致；新 C checkout 无 CodeGraph，定向读取且未建索引。worker 只写 helper/test 两文件，其余集成/文档/Git/交付由主 Agent 独占，reviewer 只读。独立源审查最终 APPROVE；目录、传输取消和连接分类三项已修正，新 13 项执行结果由主 Agent 核验 XML 确认通过。原子移动不支持的 fallback 未直接故障注入。

整个 Chat 刷新移 IO 涉及同步调用、状态/草稿/引用竞争，留后续设计；本轮只收取可量化的显示查询收益。流式 indexOfFirst 提议仍需线性扫描和列表复制，未证明降低复杂度，未实施。技术/验收已同步，无重要架构/权限取舍，不新建 ADR。

## 交付与限制

本文件是提交前快照；PR head/CI/merge SHA 和最终 clean-source APK/hash/签名/manifest/native/notices 由外部交付报告补齐。日志、XML、审阅、DSH 截图及 receipts 保存于：

`C:/Users/32735/.codex/visualizations/2026/10/02/01a0faf5-a5fd-7253-8334-b1c13b1f2c74/optimization/`。

没有声明刷新 IO 已全部移出主线程；没有设备帧耗时/正式性能/长稳/真实付费 Provider 数据。本轮新实际结果独立记录，不把附件修复旧设备记录冒充本轮新增实测。
