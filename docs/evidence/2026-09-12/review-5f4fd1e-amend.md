<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 5f4fd1e 复审修订

核验时间：2026-09-12T19:19:16+08:00（Asia/Taipei）。源码已发布：`af505b6bc2887f6ffd00dfa87468f7fcb9bd3aa2`（author/committer `luozhibai <wy3273564266@163.com>`，parent `e398f5f`），PR [#10](https://github.com/hedanbaomi/mobile-agent-runtime/pull/10) 合并 `origin/main` `7bf529d97544f14e92c9dba5482f84a040d9e75b`。本地工作区仍为 `codex/user-qa-fixes` / HEAD `3f45418`（仅文档）。HANDOFF 与 `/docs` 未提交。对照证据包 `mobile-agent-runtime-5f4fd1e-review-evidence.zip`。

## 分类

| ID | 对照 5f4fd1e | 本轮 |
| --- | --- | --- |
| P1 Skill 备份身份 | CONFIRMED：export 能按 install_id 找到包，`SkillTransfer.id` 仍是 package id，preflight/importAgent 用 package id 对 Agent 的 install_id | 导出写 `sourceInstallId`；导入重映射到本地 `skill_installs`；绑定保持 install_id；导入禁用且不带原授权 |
| P1 视觉缺口完整备份 | CONFIRMED：`exportKnowledge` 写出 `READY_WITH_VISUAL_GAPS`，`validateKnowledge` 只允许 STAGING/READY/FAILED/CANCELLED | 允许该状态原样进出；导入清空 generation 并提示本地重建；WAITING 仍拒绝；不升为 READY |
| P2 F-08 短事实删除 | CONFIRMED：同文档有长块时，短且无 `.`/`。` 的字段值被硬删除 | 数字/赋值/列表项保留；标题型短句仍可丢；不是 NLI |
| 其余复核项 | 证据包已核对成立或明确未验收 | 未重开 |

## 验证

- `.\gradlew.bat :shared:knowledge-api:test --tests ReciprocalRankFusionTest --tests PublishedCitationVersionTest :shared:serialization:test --tests TransferCodecTest :data:sqlite:test --tests SkillRepositoryTest --tests ProductDataRepositoryTest --dependency-verification=strict` exit 0。
- 隔离树 REUSE 615/615；本机 `licenseGuard` exit 0。GitHub license-guard `34689685658`/`34689695321` success；ci `34689685652`/`34689695281` success。
- PR 首轮 Convergence `ChatContextCompactionDeviceTest#requestCapStopsTheToolLoopWhenAutoCompactIsOff` expected 2 was 1；同 SHA push CI 已过，失败 job 重跑 PASS。

## Git 发布

- 提交身份：author/committer 均为 `luozhibai <wy3273564266@163.com>`，无 Co-authored-by。
- 推送：`git push origin af505b6:refs/heads/codex/user-qa-fixes`（`e398f5f..af505b6`，非 force）。
- 合并：未直推 main；PR #10 merge commit `7bf529d`。

## 未做

deploy、正式签名、真实 DeepSeek、无人值守用户路径、物理 Wired USB、独立安全审阅。
