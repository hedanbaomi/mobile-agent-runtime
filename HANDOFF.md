<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-09-01T16:27:07+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

> 本文件只保存当前事实、未决边界和接手动作。已完成工作的详细过程保存在 [证据目录](docs/evidence/) 和 Git 历史中，不再在这里重复流水账。

接手者必须依次阅读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。修改完成、受阻或中断前必须同步本文件及受影响的专题文档。

## 1. 当前状态

| 项目 | 当前事实 |
| --- | --- |
| 产品 | 权限、工具与危险模式 v2.3 已完成生产接线：统一 Capability/Workspace/Authority/Approval/Audit，Internal/SAF/selected privileged workspace，Skill Memory，Shizuku，Windows 有线 USB ADB Companion，持久 Dangerous Mode 与受控 `shell_exec`。公告、Provider、Agent、Knowledge、Skills、诊断和请求检查器均已有实现 |
| 工作区授权 | 有效 canonical capability grant 与 snapshot binding 直接授权 typed workspace 操作，不在 Chat 重复逐次确认；每次派发仍复核撤销、过期、policy revision、workspace/path scope、selected Authority，并原子消费 ONCE grant |
| Authority | 仅 `SHIZUKU` 与 `WIRED_ADB` 两个平级 elevated Authority；只派发 selected provider，失效时 fail-closed，不自动 fallback。普通模式不暴露 shell；危险模式仍受构建 admission、capability 和策略约束 |
| 真实 E2E | API 31 x86_64 已用系统 DocumentsUI 的 persisted SAF grant 和官方 Shizuku 13.6.0 UID 2000 UserService 完成模型侧 list/read/create/read/delete 与 `/system/bin/sh` E2E |
| 独立复核 | 最终只读复核 `PASS`；此前 3 个 P1 与 1 个 P2 均关闭，未发现新的 P0/P1/P2 |
| Git | 分支 `main`，远端 `origin`。本轮提交前基线 `71b0972520dc1821cbe1208290f1bdd784e964e2`，此前本地领先远端 2 个提交。用户已明确授权把本轮源码、测试和文档 commit/push 到 `origin/main`；最终 SHA 与远端一致性由本次操作结束时的 Git 核验和最终回复给出 |
| 许可 | 第一方保持 `AGPL-3.0-only`；license 正反向、Actions pin、28 个 lockfile、root/included-build strict dependency verification 与 REUSE 均通过，没有降低供应链门禁 |

## 2. 本轮修复

- 移除 canonical workspace grant 之后的第二份进程内逐次批准，解决模型已获授权却停在隐藏审批的问题；Shell 的高风险确认策略未放宽。
- 修复 Shizuku 首次授权时权限结果、Binder 回调和容器刷新并发触发重复 UserService bind 的竞态；无预热首次 bind 单独通过。
- 修复 `workspace_list` 空 workspace 审计、Shizuku 根 list 的 `null` 路径、SAF tree/document URI 与 mutation handle 重绑问题。
- SAF 只在 provider/grant 可证明时支持新建文本；既有文件非原子覆盖继续返回 `UNSUPPORTED`，不冒充原子替换。
- Agent-facing schema 不再声明未消费的 READ `expectedVersion` 或 MOVE `replace`；Wired write/create/move/delete 收到非空 `expectedVersion` 时在 bridge 前零 dispatch 返回 `CONFLICT`。
- `runtime_tool_exposure` 增加闭合的安全聚合原因，区分无 grant、无 snapshot binding、Authority 未就绪、SAF backend 状态、模型 tools transport 关闭与 factory 故障；不记录 URI、路径、命令、参数、正文或异常消息。

完整根因、命令与证据见 [2026-09-01 工作区工具真实 E2E](docs/evidence/2026-09-01/workspace-tool-real-e2e.md)。

## 3. 验证与产物

| 验证 | 结果 |
| --- | --- |
| 无预热 Shizuku 首次模型工具暴露 | 1/1 PASS；测试后只有一个 UserService |
| Shizuku typed workspace + shell | 2/2 PASS |
| 真实 DocumentsUI SAF 模型工具链 | 2/2 PASS；覆盖读、新建、读回、覆盖拒绝、原文不变与删除 |
| API 31 完整仪器批次 | 235 tests 全通过；K06 大负载未显式启用，不能算作完成 |
| Kotlin/AndroidTest/Debug 双 APK | 375 tasks `BUILD SUCCESSFUL` |
| 全仓 strict gate | 1084 tasks `BUILD SUCCESSFUL`；Debug/Review SBOM 均 171 components，Review provenance 通过 |
| 提交前许可复验 | `licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification` 通过；REUSE 517/517、0 缺失/无效 |
| CodeGraph | `codegraph sync .` 为 `Already up to date`；`.codegraph/` 不进入 Git |

人工终审使用同一个 non-debuggable Review 包检查普通模式与危险模式：

| 产物 | 大小 | SHA-256 |
| --- | ---: | --- |
| `app-android/build/outputs/apk/review/app-android-review.apk` | 204,289,319 | `E8289EE1DB02ADBF1C3F9C2AD8BF7B97FC07E676140EF5347AA2395C9F2AB477` |
| `app-android/build/outputs/apk/debug/app-android-debug.apk` | 212,754,194 | `ED6571CC4AE98101D6F049EC57FF9EE3EA6BA8F030CDFE2FD41C64A11E96FAD4` |
| `app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk` | 1,720,014 | `104371A4761E0EEA0FFFC9AB0CC14CF8719FD6CEFDC74F26E6B147022D7D69E4` |

这些 APK 使用本地 Android Debug v2 签名，只供验证，不是正式 release。Debug AndroidTest APK 不能安装到 Review target 上；该 build-variant 不匹配产生的 `NoSuchMethodError` 不是产品崩溃。

## 4. 安全与范围边界

- Root、应用内无线 ADB、DPC、Termux、PTY、Accessibility、宿主 PowerShell/宿主 shell 不在当前产品范围。
- SAF 是独立 workspace backend，不是 elevated Authority；URI、设备路径、ADB serial、配对材料和 secret 不进入模型或诊断。
- Shizuku 必须验证 shell UID 2000、caller/session/protocol；UID 0、未知 UID 或握手不一致均 fail-closed。
- 外部副作用无法确认时返回 `UNKNOWN_OUTCOME`，不得自动重放。
- 诊断默认关闭，事件/字段闭合，引用使用会话 HMAC；当前/上一段/崩溃/单事件/ZIP 上限为 256/256/32/4/640 KiB。
- 本轮 Git 授权只覆盖当前源码、测试与项目文档；不授权 Cloudflare/其他生产部署、正式签名、AAB/商店发布、付费调用、secret/Access 变更、强推或历史改写。

## 5. 未决事项

| 项目 | 状态与下一步 |
| --- | --- |
| F-001 工具能力开关历史崩溃 | `candidate_intermittent`，不能因暂未复现关闭。再次出现时记录 APK SHA、时间和步骤，导出应用内诊断 ZIP；原生崩溃或系统强杀另取完整 Logcat |
| 物理 USB Companion | `E2E_BLOCKED`：需要真实 Windows USB 设备、配对、物理断连和恢复验证；不得用模拟器替代 |
| OEM/非模拟器差异 | `E2E_BLOCKED`：需要物理设备上的 DocumentsProvider、Shizuku Binder death/rebind 与权限撤销验证 |
| K06 大负载 | 未完成：仍需 300—500 文件、约 300—500 MB，覆盖 ENOSPC、温控/耗电、进程死亡恢复和 Android 15/16 长时配额 |
| 正式 Android release | 未授权：正式包名/品牌、release keystore、AAB、商店发布与后检后置 |
| 推送后 CI | 本次 push 后检查 `main` GitHub Actions；普通 push 下 `Signed release gate (manual only)` skipped 是预期行为，不触发正式签名 release |

## 6. 接手顺序

1. 运行 `git rev-parse --show-toplevel`、`git status --short --branch`，核对分支、HEAD、远端和 dirty 状态。
2. 阅读 [权限工具 v2 规范](docs/mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)、[验收矩阵](docs/ACCEPTANCE.md) 与 [诊断规范](docs/DIAGNOSTICS.md)。
3. 若处理人工问题，先核对 Review APK SHA 和诊断 ZIP manifest，再按 `runtime_tool_exposure` 的闭合原因定位；不要先扩大权限或重建授权模型。
4. 修改源码后先用 CodeGraph，补正反向测试，执行适用的 strict Gradle、REUSE、设备验证和独立只读复核。
5. 未获得新授权时，不 commit/push、部署公告系统、正式签名、发布或调用付费服务。

## 7. 历史证据索引

- 当前权限/工作区闭环：[2026-09-01 工作区工具真实 E2E](docs/evidence/2026-09-01/workspace-tool-real-e2e.md)
- v2 历史收敛快照：[2026-08-31 权限、工具与诊断](docs/evidence/2026-08-31/authority-tooling-v2-final.md)
- 人工反馈包：[Round 4](docs/evidence/2026-08-30/manual-review-round-4-ui-workspace.md)、[Round 3](docs/evidence/2026-08-30/manual-review-round-3-capabilities.md)、[Round 2](docs/evidence/2026-08-30/manual-review-round-2-fixes.md)
- 公告后台、诊断与部署：[2026-08-30 证据](docs/evidence/2026-08-30/admin-cn-diagnostics-debug-deploy.md)；本轮未改生产公告系统
- 1.0 本地 release gate：[release-gate-1.0](docs/evidence/2026-08-29/release-gate-1.0.md)
- 知识、Python、Provider、协议、UI 与公告的早期分项证据：[2026-08-29 目录](docs/evidence/2026-08-29/)

后续维护本文件时更新当前事实、验证、未决事项和接手动作；完成过程写入对应 evidence 文件或 Git 提交，不再追加大段时间线。
