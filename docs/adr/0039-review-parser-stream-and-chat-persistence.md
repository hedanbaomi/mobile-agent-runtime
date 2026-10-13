<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0039：解析忠实性、事件分帧与会话持久化

状态：接受，2026-10-13。适用 R02、R04、R05、R25；本次为现有契约修复。

## 背景与决定

第三方审查的七个问题逐项由源码与合成回归核验。DOCX 正则误匹配、实体处理、EPUB 阅读顺序和 SSE 逐行解析可复现。主线程存储、SavedState 大正文、创建中途残留及批次长事务的风险由调用路径确认；没有把推测的 ANR、TransactionTooLarge 或设备容量写成已发生的事实。

- DOCX 使用有界命名空间 XML 解析，保留 tab/br/cr、标点与段落，实体只解码一次，拒绝 DTD、外部实体及外部资源。EPUB 按 container→OPF manifest→spine，保留显式声明的导航和辅助内容；不能将 manifest 中所有 HTML 或文件名排序当作阅读顺序。固定路径与章节序号保留来源身份。指纹为 `docx-xml-v3`、`epub-xml-v5`。旧 Office 当前版本阻止新检索、当前正文读取与仅索引重建，并提示重新导入原文件；明确指定历史版本的读取保留。不会自动重放 Vision/API embedding。
- 两个 OpenAI 协议先按 LF/CRLF/CR 读取真实行，再按空行提交事件并合并 data 字段。未定界的末事件丢弃，流与连接探测共用分帧规则。保留单行 1 MiB、响应 8 MiB，并新增 UTF-8 事件 1 MiB 与事件计数上限；费用未知、审批、取消、usage 和 continuation 的原语义保持。
- 宿主迁移、容器和运行恢复在 IO 初始化，Activity 观察 INITIALIZING/READY/RECOVERY/FAILED。会话投影使用可注入 IO dispatcher、选择代次与输入修订检查。首屏消息及会话每页 50；UI 分页不缩短模型完整历史、引用或导出。
- 草稿正文在现有 SQLite `app_prefs` 命名空间内持久化，SavedState 仅保留小型选择/开关。进程级单写者 350 ms 去抖，读取和保存共用 mutex；生命周期请求刷盘，失败保留待保存输入，旧状态迁入不能覆盖较新草稿或明确清空。正文沿用 1,000,000 字符上限。突然进程死亡仍可能丢失未刷盘的最后去抖窗口，不能宣称每次按键已同步落盘。
- 创建快照、MCP 固定信息、会话和工作区绑定由同一 SQLite 事务发布。不可变 MCP 固定信息使用 `app_prefs`，现场端点和审批仍在原配置存储；旧固定信息只读兼容。数据库中已有但损坏的固定信息不能回退旧记录；加载及每次派发检查身份、工具与 schema hash、当前授权及撤销。
- 批次重配置分为短快照、无锁 CAS 读取/规划、短事务复核发布。复核成员、状态/授权、文档版本与 hash、generation、计划/结果指纹。聚合查询限定本批次任务；保留已成功结果、UNKNOWN 单独确认、取消和显式重配已完成批次的原行为。发布写入仍随成员数增长，变更计划的 units 仍在内存中，不承诺常数内存或设备容量。

## 取舍与验证

保留现有 Runtime、Broker、数据库和向量引擎；不以换引擎或大规模重写掩盖解析和调用路径问题。本轮不增加 schema 版本，不提高复杂度、告警或许可 baseline。

回归入口：OfficeParserReviewRegressionTest、EpubVisualCompletenessTest、OfficeParserRecoveryTest；SseEventFramingTest、SseEventDecoderTest、SseProbeFramingTest；CreateConversationUseCaseTest、ChatProjectionPersistenceTest、ChatDraftPersistenceTest、McpSnapshotPersistenceTest；BatchReconfigurationIsolationTest、BatchReconfigurationScaleTest；Android ChatStorageProjectionDeviceTest。

源码独立审查、JVM、模拟器、真机和真实 Provider 分别记录。设备慢读取测试延迟 dispatcher 入口，草稿恢复测试是同进程 ViewModel 重建；它们不能代替活动 SQLite 阻塞、Activity 重建或进程死亡验收。执行结果见 [本轮证据](../evidence/2026-10-13/review-remediation.md)。
