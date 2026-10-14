<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0040：消息时间顺序与有界分页

状态：接受，2026-10-14（Asia/Taipei）。关联 R25、R34；A05、A07、U07、C21—C25。补充 ADR-0039 的历史分页及 ADR-0010 的来源顺序契约。

## 背景

1.2.0 发布验证发现分页设备测试的墙钟夹具不稳定。进一步回归确认真实问题：`Instant.toString()` 的 UTC 小数精度可变，文本排序会把 `.100001Z` 放在更早的 `.100Z` 前面。完整历史、分页与压缩来源均使用原文本顺序。固定测试时间只能稳定夹具，不能修复真实消息时间顺序。

## 决定

- SQLite 33 增加派生列 `messages.chronology_key TEXT NOT NULL DEFAULT ''`、`chronology_ordinal INTEGER`。时间键以 `Instant.parse` 解析，编码为 `0` + 相对 `Instant.MIN.epochSecond` 的 17 位秒数 + 9 位纳秒；全范围有序，偏移和小数等价表示使用同一键。ordinal 精确复制原 rowid，等价时间保留持久插入顺序。原消息 ID、正文、状态、时间戳、rowid、来源 hash 均不因此重写。
- 原接口只要求时间非空，继续接受无法解析的旧值。这些值用单字符键 `1`，排在有效时间之后并按原 rowid 排序；不复制任意长度的时间字符串到索引。解析器支持的扩展年份、偏移和闰秒按其 Instant 语义处理，不能把解析失败变成拒绝导入。
- 增加索引 `(conversation_id,chronology_key,chronology_ordinal)`，完整读取和反向分页共用同一顺序。游标用消息 ID 找到同会话的两个派生值，范围条件为 `(chronology_key,chronology_ordinal)<(?,?)`；只读取 `limit+1` 行，无 OFFSET 或 Kotlin 全历史排序。
- ordinal 必须显式入索引：实测 SQLite 对隐式 rowid 的范围只定位到时间键，随后过滤相同时间的多余记录。三列索引要求实际查询计划和 VM seek 包含三个范围字段，且不使用临时排序。
- 缺失元数据使用空键或 NULL ordinal 标记，部分索引定位。迁移在现有事务内每批最多读取 128 行的 ID/时间，修复至全部完成；中断整体回滚，重复打开不重写已完成元数据。append 和 transfer import 在同一写事务生成两个字段；兼容旧默认 SQL 插入的读取修复亦分批完成后才返回页面。
- 补齐使用指定部分索引的存在性读取，无缺失时不获取写事务；存在缺失时在短事务内重查并补齐。完整历史随后在正常读连接读取/解码，避免持有 Android 写连接处理全部正文。所有生产插入同事务生成元数据；并发外部默认 SQL 写入不属于生产仓储契约，旧默认写入完成后下一次读取会补齐。
- 压缩来源与完整历史使用同一数据库顺序。旧成功摘要及其来源 ID/hash 不重写；若旧来源顺序不再匹配，读取时不可复用。旧成功记录仍是链头，新尝试必须以它为 parent，验证新来源后才能成功。迁移只保留既有摘要形状校验，不自动发起摘要、Provider 或工具请求。

## 替代方案

统一原始时间字符串会改写不可变历史和既有摘要 hash；Kotlin 解析并全量排序不能保持有界分页；SQLite Julian-day 精度不足以区分纳秒。多个数字列也可表达时间键，但固定宽度 ASCII 键简化索引和游标，同时保留整个 Instant 范围。升级后旧版 schema32 程序会按既有规则拒绝打开 schema33；回退应用需要用户自己的升级前备份，不能删列或降级数据。

## 验收与证据

| 范围 | 必须验证 |
| --- | --- |
| A05/U07 | 纳秒不同精度、反向插入跨多个页面，完整历史与每个分页边界无漏无重；等价时间以 rowid 打破平局 |
| A07 | schema32 既有记录分批升级、故障整体回滚、重复迁移零元数据写；完整备份往返保留原时间和消息内容，导入即补齐派生值 |
| C21—C25 | 旧错误顺序摘要保留但不可复用；新正确来源可继承旧链头并完成；原来源 hash 和消息不变，未知结果不重放 |
| 性能与兼容 | JDBC 和 Android SQLite 实际计划使用三列范围，无临时排序；超128缺失行全部修复；闰秒、扩展年份、最小/最大 Instant、偏移和旧无效值保持明确定义 |

回归入口：ChatProjectionPersistenceTest、MessageChronologyMigrationTest、ContextCompactionRepositoryTest、TransferRepositoryIsolationTest、Android ChatStorageProjectionDeviceTest。执行收据由主 Agent 记入本次发布证据；JVM、模拟器、真实设备/Provider 与公开发布状态分别记录。
