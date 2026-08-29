<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 0.2 F-014 密钥生命周期与 legacy 迁移复核证据

## 范围

本证据覆盖 SQLite 的 Provider 密钥引用回收、模型 endpoint/role/capability 的 legacy
迁移与读取，以及不可变 Agent snapshot manifest 的 fail-closed 校验。未读取、打印或写入
任何真实密钥值；测试只使用合成 reference 和合成 ciphertext。

## 修复结果

- `SecretInventory.referencedSecretRefs()` 同时扫描 Provider 的主 `secret_ref`、
  `header_secret_refs`，以及 snapshot binding/expanded manifest 中的 Provider 主密钥和
  header 引用。引用扫描遇到损坏 JSON 或非法类型时抛出可审计的 `INVALID_CONFIG`，不会
  继续垃圾回收。
- Provider 更新/删除在同一数据库事务内捕获旧的主引用与 header 引用，再执行引用感知
  的退休流程；仍被其他 Provider、header 或不可变 snapshot 使用的密钥保持存活。
  `retireIfUnreferenced(String)` 兼容既有调用方，并新增 iterable 入口供批量切换使用。
- v11 backfill 和普通 profile 读取共用严格 decoder：非法 role、capability JSON、空
  capability 项、空 endpoint、无法反序列化的 endpoint、role/operation 或 capability/
  modality 冲突都会失败，不再回退为 CHAT、空能力或另一个 endpoint。合法的 `{}` endpoint
  只按现有 role/capability 派生一次并持久化。
- snapshot manifest 中的模型和 Provider 片段在迁移时也按同一 fail-closed 语义校验，避免
  不可变快照在升级后静默改绑。metadata-only probe 不会把所有 capability 或 endpoint
  标为 `PROBED`；只有 metadata 成功且至少一项能力有成功语义证据、其余已声明能力也成功
  或明确未声明时才提升 endpoint 验证状态。

## Fixture 与测试覆盖

`MigrationsTest` 增加了旧 Chat、Vision、Embedding、Agent/Reranker 模型与不可变 snapshot
的最小 legacy fixture，验证角色、endpoint、提示词和四类模型绑定保持不变；另覆盖非法
role、非法 capability JSON、非法/空 operation endpoint、role-endpoint 冲突，以及损坏
snapshot fragment 的事务回滚和 schema version 保持不变。

`SecretInventoryTest` 覆盖共享 header/主密钥、Provider 更新/删除、snapshot 引用保活、
两阶段 orphan 回收，以及损坏 snapshot JSON 时不回收活动密钥。

仓储探测测试覆盖 metadata-only、完整 per-capability 成功和 capability 失败降级，确认
`capability_probes` 保留独立摘要和 `PROBED`/`USER_DECLARED` 来源语义。

## 验证与剩余风险

目标命令：

```powershell
./gradlew.bat :data:sqlite:test --tests runtime.mobileagent.data.SecretInventoryTest --tests runtime.mobileagent.data.MigrationsTest --no-daemon
```

本轮代码检查使用 `codegraph explore` 和 `git diff --check`；Gradle 结果以主代理最终串行
运行记录为准。若仓库其他并行改动仍使 Gradle 在 `KnowledgeRepository.kt` 等非本范围文件
编译失败，该失败不应被解释为本修复的测试通过。

schema v11 之前若 snapshot 的 binding/expanded manifest 都是空对象，数据库没有可供恢复的
历史引用，本修复只能保留该空快照并让后续解析继续 fail-closed；这项不可重建性是现有
schema 的精确限制。真实 Android Keystore、设备并发事务、真实 Provider 计费/网络行为不在
本证据范围内。

主流程最终串行复验：`:data:sqlite:test` XML 104 tests、0 failure/error/skip；API 31 的 Provider revision 设备用例通过。测试夹具现只把 `SELECT ciphertext,status ... WHERE ref=?` 计为 credential read，允许引用库存/GC 只读扫描，同时继续强制 ciphertext 只能读取该 fixture 自身 dummy ref。
