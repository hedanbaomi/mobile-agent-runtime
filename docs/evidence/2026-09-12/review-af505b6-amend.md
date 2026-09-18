<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# af505b6 复审修订

时间：2026-09-12T19:45+08:00（Asia/Taipei）。基线：远端 `af505b6bc2887f6ffd00dfa87468f7fcb9bd3aa2`（PR #10 已入 main `7bf529d`）。证据包：本机 `mobile-agent-runtime-af505b6-review-evidence.zip`。

## 审核项分类

| ID | 结论 | 处理 |
| --- | --- | --- |
| F1 / P1 历史会话 Skill 未导出却强制解析 | CONFIRMED：`buildAgentBundle` 只导出当前 `agent.skillIds`，`importConversation` 却要求快照 `skillIds` 均可映射 | 含会话导出同时带上快照依赖；不把旧 Skill 写回当前 Agent；导入禁用且无授权 |
| F2 / P2 纯文字短字段仍被删 | CONFIRMED：无数字/`=`/列表前缀的短字段在同文档长命中时仍被删 | 只在 `sourceSpan` 标题或 ATX `#` 结构明确时硬删除；字段值保留 |
| F3 / P2 package id 与 sourceInstallId 同 Map 覆盖 | CONFIRMED：构造输入可使 Agent 两个绑定落到同一本地安装 | codec 拒绝跨命名空间碰撞；映射遇歧义失败而非覆盖；同 package id 不同 hash 允许 |
| F4 / P1 换目标继承旧 Header | CONFIRMED：`saveDraft` 换 URL 只要求新主 Key，仍复制 `headerSecretRefs` | 保存与仓储剥离未重新确认的辅助凭据；适配器不会把旧 Header 发到新 host |
| CI Convergence 首轮 expected 2 was 1 | 观察项：同 SHA 重跑已过，不改断言 2 | 失败诊断补 `state/stopReason/rounds/tools/requests`，不放宽上限 |

未采纳：删除旧会话掩盖失败、把旧 Skill 加回当前 Agent、继承源授权、用更多标点白名单、把断言 2 改成 1。

## 源码

提交 `5e3590e6524edd9e6239469da8bc4958f3320a64`（author/committer `luozhibai`），14 个源码/测试文件。无 HANDOFF/docs。PR [#11](https://github.com/hedanbaomi/mobile-agent-runtime/pull/11) 合并为 `16fe3a4958624ae557a9118e047e578eb35630a8`。

## 验证

- JVM（本机 main 工作区，exit 0）：`:shared:knowledge-api:test --tests ReciprocalRankFusionTest`、`:shared:serialization:test --tests TransferCodecTest`、`:shared:domain:test --tests ProviderDestinationBindingTest`、`:shared:provider-api:test --tests ProviderDestinationAdapterTest`、`:data:sqlite:test --tests SkillRepositoryTest --tests SecretInventoryTest`
- 隔离树 REUSE 617/617。本机 `licenseGuard` exit 0（隔离 worktree 仍会因 vendored LICENSE.html CRLF 哈希失败，沿用主工作区门禁）。
- GitHub：license-guard `34692140426`/`34692148995` success；ci `34692140432`/`34692149000` success（含 Convergence API 36；Signed release gate skipped）。
- 未做完整 Android/Gradle 全量本地构建、真实 DeepSeek、WinTrust、独立安全审阅或无人值守用户路径。
