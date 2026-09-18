<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 上下文压缩源码提交与推送

时间：2026-09-11T19:41+08:00（Asia/Taipei）。用户在当前任务明确要求提交并推送改动，同时禁止推送 HANDOFF.md 和 docs/。本记录仅本地保存。

## 提交与范围

- 远端：`https://github.com/hedanbaomi/mobile-agent-runtime.git`，分支 `codex/user-qa-fixes`。
- 已推送源码提交：`72dc60a1dfd4d2839e476baf053d87cd24d21c22`，父提交 `c43a54aafc4e81956a99014dbf887caf120b2f4e`。
- 源码 tree：`03877f3ad8c8779235fec9defc222a90067772c1`；28 个源码、测试和依赖配置文件，3816 行新增、68 行删除。作者/提交者沿用仓库配置 luozhibai。
- 比较父提交到源码提交：`HANDOFF.md`、`docs/`、`AGENTS.md`、`agent.md` 变更均为零；全局 Codex AGENTS.md 不属于此仓库。旧 `.tmp-*`、`.workbuddy/` 与本机验证资料未进入提交。
- `git push origin 72dc60a1dfd4d2839e476baf053d87cd24d21c22:refs/heads/codex/user-qa-fixes` 成功，未使用 force；随后 `git ls-remote` 返回完全相同的 SHA。

## 本地文档保留

原本 HEAD `05bea3e20878202b7171f4873ff38e665413d655` 是只留本地的文档提交，不能直接推送包含它的当前分支历史。通过独立索引构造以上源码 tree，以远端基线为父创建源码提交。未配置活动 Git hooks 或强制签名；所需构建与许可门禁均在提交前真实执行。

旧文档提交的 30 路径增量原样保留到源码提交之上的本地文档提交 `4fe39dc2066f8b9956dc4095bdca3508e7addf39`，文档 binary diff 与原增量逐字节相等；该提交未推送。因此本地分支仍正常 ahead 1，仅这一个文档提交在远端之外。

旧 HEAD 另保存在本地分支 `codex/local-docs-before-context-compaction-20260911`。更新本地 ref/index 时未检出或重写工作区文件；9 个已有文档/规则文件的原始 hash 全部保持，之后仅按项目要求更新本地交接。本次源码已无未提交差异，文档及规则 WIP 继续留在本地。

## 提交前验证

- `./gradlew licenseGuard check debugEvidenceGate reviewGate --dependency-verification=strict --no-daemon --no-build-cache --max-workers=2 --console=plain`：exit 0，`BUILD SUCCESSFUL in 5m 36s`；1097 个任务，含 licenseGuard 正/反向、工作流 YAML、CI pin、依赖锁/校验和、全仓 check、Debug/Review APK 安全检查、SBOM 和 provenance。两个 SBOM 均为 171 components。
- `python -m reuse --root <拟推送 Git tree 导出目录> lint`：exit 0，612/612 文件具备版权及许可，REUSE 3.3 合规。工作目录全扫曾因旧未跟踪临时缓存而失败；第一次导出目录检查的默认根发现也爬升回工作仓库。最终显式指定完整发布树为 root，不删除旧 WIP，不修改许可门禁或豁免名单。
- 公告 rollout/signature golden vectors 与 worker 协议测试：exit 0。
- 上轮的 460 项 JVM、7 项 API 36 定向模拟器用例及独立审阅证据仍见 [功能验收](context-compaction.md)。提交前再次核对全部 28 文件 hash，没有源码漂移。
- 原始日志、独立索引、发布 tree、SHA 清单和远端核验均在 `.private/validation-context/publish/` 与其关联导出目录。

门禁构建发生于提交前的工作区；已有 APK 的 provenance 不应表述为新提交 72dc60a 的精确提交打包。本任务仅提交推送源码，未正式签名发布、部署或执行真实 Provider 验收。

## 推送后 CI 快照

最新核验（2026-09-11T19:43:40+08:00），两个 run 的 headSha 均为 `72dc60a1dfd4d2839e476baf053d87cd24d21c22`：

- [ci 34595090600](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34595090600)：in_progress。
- [license-guard 34595090642](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34595090642)：completed / success。

此快照不是远端 CI PASS；本次交付状态为源码推送及远端 SHA 核验完成。
