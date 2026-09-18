<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 16fe3a4 复审修订

时间：2026-09-12T21:51:00+08:00（Asia/Taipei）。基线：远端 `16fe3a4958624ae557a9118e047e578eb35630a8`（PR #11 已入 main）。证据包：本机 `mobile-agent-runtime-16fe3a4-review-evidence.zip`。

## 审核项分类

| ID | 结论 | 处理 |
| --- | --- | --- |
| P1 同 package id 的 v1/v2 备份通过 codec 但恢复失败 | CONFIRMED：`rememberSkillInstall` 总是写入 package id 别名，第二版本碰撞抛 `maps to multiple local installs` | 安装身份为必选别名；package id 仅在无歧义时作为可选别名；歧义时丢弃该别名并拒绝只含 package id 的遗留绑定 |
| F2 短字段结构判断 | 复检已修，本轮未改 | 保持 `sourceSpan` 标题/`h1`–`h6` 或 ATX `#` 才硬删除 |
| F4 换目标辅助凭据 | 复检已修，本轮未改 | 保持目标变更清除未重新确认的 `headerSecretRefs` |
| 上下文压缩 request cap | 复检无新缺陷 | 不放宽断言 2 |

未采纳：删除碰撞检查、按迭代/`LIMIT 1` 选版本、把歧义 package id 静默绑到某一安装。

## 源码

提交 `214df5fca90092038aaf1905dc5a6c23db3ef93c`（author/committer `luozhibai`），2 个源码/测试文件。无 HANDOFF/docs。PR [#12](https://github.com/hedanbaomi/mobile-agent-runtime/pull/12) 合并为 `0c02b32e06f9f5ace7a0cfdf44bb7725898ee1a3`。

## 验证

- JVM（本机 main 工作区，exit 0）：`:data:sqlite:test --tests SkillRepositoryTest`、`:shared:serialization:test --tests TransferCodecTest`
- 隔离树 REUSE 617/617。本机 `licenseGuard` exit 0（隔离 worktree 仍会因 vendored LICENSE.html CRLF 哈希失败，沿用主工作区门禁）。
- `codegraph sync .`：Synced 5 changed files。
- GitHub PR：license-guard `34696538914`/`34696555938` success；ci `34696538922`/`34696555906` success（含 Convergence API 36；`Signed release gate` skipped）。
- GitHub main：license-guard `34697589283` success；ci `34697589316` success。
- 未做完整 Android/Gradle 全量本地构建、真实 DeepSeek、WinTrust、独立安全审阅或无人值守用户路径。
