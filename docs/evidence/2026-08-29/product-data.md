<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Product data contracts

日期：2026-08-29。实现基线：`7511b22ffd7a7d3021b7857b6500cbe75d037ad6`。本记录只覆盖 M1/M7 数据层；未执行正式 release、commit、push 或生产操作。

## Public repository API

- `ProfileRepository` 提供 Provider/Model 的 get/list/create/update/upsert/delete API。Model 的 `parametersJson` 保存已验证默认值，`parameterSchemaJson` 只保存参数 schema。删除会拒绝仍被 Agent 或 immutable snapshot 引用的对象。
- `AgentRepository.saveWithPrompt(profile, template, allowedVariables)` 在一个事务中生成不可变 `PromptRevision`，再创建或更新 Agent。`createSnapshot` 展开当前 Agent、Prompt、Provider、Chat/Vision/Embedding/Reranker Model 及其 provider；`resolveSnapshot(snapshotId): SnapshotBinding` 只从 `bindingManifestJson` 读取完整冻结值，不重新查询 live profile。
- `ConversationRepository` 保存 Conversation 与 typed `MessagePart`（text/image/tool call/tool result/citation）。流式助手先插入 `ASSISTANT/STREAMING` 空消息，再通过 `checkpointAssistant(...)` 在同一事务更新消息和 `message_parts`；终态消息不会被后续 checkpoint 覆盖。
- `RunRepository` 持久化 Run、ToolInvocation 和 `UNKNOWN_OUTCOME` 恢复。进程恢复会把未完成 Run、工具调用及关联 STREAMING 消息标记为未知结果；`acknowledgeUnknown(..., acknowledgeDuplicateCharge=true)` 持久化 `retry_acknowledged_at`，重试必须使用新 run id。
- `AuditRepository` 为追加式审计记录；`SettingsRepository` 持久化 `ThemePreference` 与 `LocalePreference`。

## Snapshot manifest

`agent_snapshots.binding_manifest_json` 的顶层键为：`schemaVersion`、`snapshotId`、`agentId`、`agentRevision`、`agentName`、`retrievalMode`、`provider`、`chatModel`、`prompt`、可选的 `visionModel`/`embeddingModel`/`rerankerModel` 及对应 provider、`parameterOverridesJson`、`contextPolicyJson`、`permissionSettingsJson`、`knowledgeBaseIds`、`skillIds`。嵌套 Provider/Model/Prompt 是完整 serializable 值；其中 Provider 的 secret 仅允许作为本机引用，实际密文不进入快照导出。

## Migration

数据库在 v7→v8 阶段保留已有数据、不清库，新增 Model 参数默认值、Agent 参数/上下文/权限策略、完整 snapshot 展开字段、typed message 存储、Run/ToolInvocation、审计计数与 retry acknowledgement。v8→v9 继续保留旧数据，并新增 `embedding_query_attempts(kb_id, space_id, query_hash, retry_authorized, error, updated_at)`：查询只保存 SHA，不保存原文；`retry_authorized` 默认 0 且受 `CHECK(retry_authorized IN(0,1))` 约束，`kb_id` 外键指向 `knowledge_bases`，复合主键防止重复尝试。当前 v9→v10 再保留 query attempt、KB 及全部既有数据，新增 `embedding_operations`（IMPORT/REBUILD/REBIND、PREPARED/DISPATCHED/CACHE_READY/PUBLISHED/FAILED/CANCELLED/ABORTED/UNKNOWN 状态、取消标记、操作指纹、可选 job/document/document-version 外键）和 `embedding_query_vectors`（space/query 复合主键、向量 bytes、正维度）；活动 embedding operation 对同一 KB 由 partial unique index 串行化，job_id 有独立索引。两张表均不存正文或 secret，向量重复键不会由 schema replace 覆盖。历史 v1-v9 的 ALTER 列表会先通过 `PRAGMA table_info` 检查；只对确实缺失的列执行 `ALTER TABLE`，任何 SQL、版本或必需表/列错误都会向上抛出，不再吞掉迁移错误。更高版本、负数、非整数或多行 `schema_version` 会被拒绝。

## Transfer boundary

`TransferCodec` 使用严格 `schemaVersion` 校验和 `ignoreUnknownKeys=false`。Agent/Knowledge/Skill 导出不包含 secret ciphertext、secret ref 或运行快照；Provider 导出只含非敏感 headers，Skill 包体可选并以 SHA-256 校验。导入在 preflight 完成引用、hash、冲突与相对路径校验后，才在单一 SQLite transaction 写入；Skill 默认 disabled 且不自动恢复 grant。导入后需在本机配置 Provider secret 并重新授权 Skill，再创建新 snapshot。

## Validation record

已新增 `MigrationsTest`、`ProductDataRepositoryTest` 与 `TransferCodecTest` 源码，覆盖 v8/v9/v10 列、v9→v10 旧数据保留、重复 apply 幂等、embedding operation/vector 的外键/默认值/检查约束/partial unique、更新/引用保护、Prompt 原子保存、冻结快照解析、typed message checkpoint、崩溃 UNKNOWN、持久 retry gate、Settings、严格 transfer 校验。按主流程多 worker 门禁，本任务未自行继续运行 Gradle；全仓 compile/test 由主审统一协调。已做静态源码检查，未执行设备验收。

## M7 archive extension

- `TransferOptions` 保持旧 JSON API 兼容，默认仍为 metadata-only；`includeSkillPackageBytes` 仅保留旧 JSON 的有界包体选项。完整知识库源 blob/asset bytes 与完整对话历史只能通过 `TransferRepository.exportArchive(agentId, options, OutputStream)` 和 `importArchive(InputStream, conflictPolicy)` 传输，缺少显式 `BlobSink` 时会拒绝完整知识库内容。
- Archive 使用 `manifest.json` 加 `blobs/<sha256>`、`skills/<sha256>`、`conversations/<id>.json` 独立条目，manifest 严格 JSON、引用、hash、长度、重复项及相对路径校验；导入先读取并校验全部条目，再单事务写 SQLite。CAS 写入失败后最多留下不可引用孤儿，不发布半套数据库。Skill 导入保持 disabled，embedding/index generation 不跨设备导入并返回本地重建警告。
- 硬限制：metadata 每个条目 16 MiB、单 blob/Skill 条目 32 MiB、展开总量 512 MiB、条目数 8192、条目名 512 字符、压缩比 100 倍。路径拒绝绝对路径、盘符、反斜杠、`.`、`..` 和重复条目；archive 输出不会关闭调用方的 OutputStream。
- portable conversation snapshot 会移除 provider secret/ref、凭据、未脱敏 request/body/response 字段，并写入 `LOCAL_CREDENTIALS_REQUIRED`。历史可展示但不能自动运行、继承 Vision/Embedding/Skill/MCP grant 或绑定任意已有 secret；UNKNOWN_OUTCOME 保留且不重放。
- 新增源码测试覆盖 MemoryBlobSink archive round-trip、secret 清除、对话恢复、缺 BlobSink 拒绝，以及 UNKNOWN_OUTCOME 不能被后续终态覆盖。按主流程门禁，本任务仍未自行运行 Gradle 或设备测试。
- 主审集中 round11 构建日志显示 `:data:sqlite:compileKotlin`、`:data:sqlite:compileTestKotlin` 成功，已执行的 `ProductDataRepositoryTest` 为 5/5、0 failure；整轮 data 还有一个既有 `KnowledgeRepositoryTest.retrievePinsGenerationForTheWholeRun` 失败，另有 UI `fillMaxWidth` 编译失败，均不属于本数据层改动。随后新增压缩比 data-descriptor 反例后，ProductDataRepositoryTest 当前为 6 项，需主审下一轮重跑确认。
