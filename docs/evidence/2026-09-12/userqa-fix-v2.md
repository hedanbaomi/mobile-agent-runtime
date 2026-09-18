<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# user-QA 修复（证据包 v2）

最终核验时间：2026-09-12T16:53:37+08:00（Asia/Taipei）。源码已发布：`e398f5fdcd44a214c816777648b875c6685bdec6`（author/committer `luozhibai <wy3273564266@163.com>`，parent `0ca6f1a`），PR [#9](https://github.com/hedanbaomi/mobile-agent-runtime/pull/9) 合并 `origin/main` `5f4fd1e25ae51f90c5b7d5cb5e057ab18c7e9dce`。本地工作区仍为 `codex/user-qa-fixes` / HEAD `3f454183176b7664ff774593bd6dba396d595b2e`（仅文档，ahead 1 / behind 1）。HANDOFF 与 `/docs` 未提交。规范见用户提供的 prompt v2 与证据包 `.tmp-userqa-evidence-v2/`（不入库）。

## 分类（对照当前 HEAD，再按本轮修改）

| ID | 对照开工 HEAD | 本轮 |
| --- | --- | --- |
| U-01.1 预算拆解 | CONFIRMED：溢出只报总量与上限 | 超限 UI/诊断写入窗口、预留与分量 |
| U-01.2a autoCompact=false 硬上限 | ALREADY_FIXED（`0ca6f1a`） | 未改生产上限 |
| U-01.2b 摘要 Usage 中断结算 | ALREADY_FIXED（`0ca6f1a`） | 未改结算 |
| U-01.3 压缩 device/UI 测试入 CI | ALREADY_FIXED，并补 `AgentsConversationRoutingDeviceTest` | 本机 40/40 已执行，不是只编译 |
| U-02 `/v1` 插入 | 源码 join 为 `base.trimEnd('/') + path`，不是该故障 | 官方根与尾斜杠 Chat 路径断言无 `/v1` |
| U-02 `/models/{id}` 无斜杠 404 | CONFIRMED | 404/405 一律回退 `GET /models` 精确 id |
| U-02 可选探测阻断 Chat | ALREADY_FIXED | 工具 200 无强制 call 记 UNKNOWN/inconclusive |
| F-01 | CONFIRMED | 拒绝批准发 `APPROVAL_DENIED`，UI 非 INTERNAL |
| F-02 | CONFIRMED | `READY_WITH_VISUAL_GAPS` 可引用，仍非完整 READY |
| F-03 | CONFIRMED | 文件级 JNA `@Structure.FieldOrder`，仍调用 WinVerifyTrust |
| F-04 | CONFIRMED | 导出先按 `install_id` 解析，旧 `skill_packages.id` 回退 |
| F-05 | CONFIRMED | 仅 `embeddingSpaceId` 允许 `@`，其它 id 仍 `SAFE_ID` |
| F-06 | CONFIRMED | 失败将 `exportStatus` 置「导出失败。」并清除 running |
| F-07 | CONFIRMED | 探测 inconclusive ≠ Base URL 无效；Chat 仍用声明能力 |
| F-08 | CONFIRMED（质量缺口） | 同文档标题块让位于正文块；不是 NLI |
| F-09 | CONFIRMED | 通道暂不可用文案，不导向工作区重新授权 |
| F-10 | CONFIRMED | 新建会话写 prefs 并 `selectSession` 后进 Chat |

## 验证

- JVM（`--dependency-verification=strict`）：`:shared:provider-api:test`、`:shared:agent-runtime:test`、`:shared:knowledge-api:test`、`:shared:serialization:test`、`:data:sqlite:test`、`:desktop:bridge:test`。exit 0，**662 tests / 0 failures / 0 errors / 0 skipped**（76 XML）。含 `OpenAiCompatibleAdapterTest` 37、`RequestInputBudgetTest` 8、`RuntimeEventsTest` 11、`ToolOutcomeRuntimeTest` 7、`DesktopBridgeTest` 16、`TransferCodecTest` 8、`SkillRepositoryTest` 15。
- 设备：独占启动 `mar_api36_debug` / `emulator-5554`（无窗口、read-only、no-snapshot-save）。`connectedDebugAndroidTest` 选择 CI 收敛类加路由与诊断闭包整数：`RunToolsReplayDeviceTest`、`ShizukuWorkspaceFileStoreTest`、`VectorIndexCacheDeviceTest`、`ChatContextCompactionDeviceTest`、`ContextCompactionUiTest`、`ChatInputBudgetDeviceTest`、`AgentsConversationRoutingDeviceTest`、`DiagnosticsDeviceTest#runPreparationFailureIsClosedAndRequiresOptIn`。**40/40 PASS**，0 skip/fail，命令 exit 0，约 4m9s。Gradle 日志：`userqa-fix-device-tests.log`。未装物理机，未碰 `mar_userqa_20260912`。
- CodeGraph：`codegraph sync .` 先 34 files，补 `RunTools` Citation import 后再 1 file。索引不提交。
- 提交前隔离树：`python -m reuse --root .private/push-userqa-20260912 lint` exit 0，615/615。本机此前 `licenseGuard` 与 `check --dependency-verification=strict` 已 PASS。GitHub：license-guard `34683662019`/`34683700336` success；ci `34683662027`/`34683700299` success（Signed release gate skipped）。
- 未跑：独立只读安全审阅、真实 DeepSeek 凭据、无人值守用户路径复测、物理 Wired USB、正式签名。

## Git 发布

- 提交身份：`GIT_AUTHOR_*` / `GIT_COMMITTER_*` 均为 `luozhibai <wy3273564266@163.com>`，无 Co-authored-by。
- 推送：`git push origin e398f5f:refs/heads/codex/user-qa-fixes`（`0ca6f1a..e398f5f`，非 force，未推本地文档 HEAD `3f45418`）。
- 合并：未直推 main；PR #9 merge commit `5f4fd1e`。

## 未做（无对应授权或仍阻塞）

真实 DeepSeek 调用、付费 Provider、deploy、正式签名、独立安全审阅。
