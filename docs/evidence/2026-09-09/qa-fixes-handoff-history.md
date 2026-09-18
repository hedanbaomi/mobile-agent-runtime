<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 修复收尾前的交接过程快照

以下为 2026-09-10 收尾前的第 1 节原文，仅用于保留过程及旧 WIP；已被当前 HANDOFF 和最终修复证据替代。旧状态、远端 CI、授权、待办和进行中认领均不是当前命令或事实。未决事项仍保留在 HANDOFF 第 3 节。

## 1. 现行状态与当前任务

- 2026-09-10 现行验证（替代下方 09-09 历史过程的待验描述）：真实两页 Vision READY/COMPLETED，4 chunks 与 evidence 已核验；canonical fingerprint 已修复。英文 SAF 实际授予/撤销、真实 UI Metadata/Streaming/Tools probe 均通过。测试模拟器 Shizuku 恢复单实例后 Granted/Ready/Connected。PDF 最终 DocumentParser **34/34**、原始语料页数 **294/294 一致且零解析错误**、Android 原生四轮内存 **PASS**（最大采样 PSS 328954 KiB），独立边界审阅已通过。
- 当前未完成：Vision 快照读锁及 ConsentWorker job tag 取消补丁已完成；并发回归、ConsentCancellationDeviceTest 1/1、独立静态审阅通过，实际外发状态/取消待最终包验证。Shell 新持久授权已证实暴露，但运行前发现 Runtime 拒绝 cwd nullable schema；定向补丁正在测试与独立审阅。主 Agent 独占 Gradle、设备和文档，待最终 strict check/APK、真实 Shell 执行与 Vision 取消、临时凭据和模拟器清理。当前不能声称全部修复通过。
- 最新核验（11:40+08:00）：第三版 Review 的 294 份原始 PDF 完成复制/解析进入知识库，同一 PID 未再崩溃；含图文件仍等待同意，不代表全库 READY。完整 Class B ZIP 已真实完成批准、隔离 Python 与最终回复。共享 Knowledge/Provider、data 全测试及 licenseGuard 合跑通过（`final-shared-data-gate.log`）；API 消费票据恢复 17 项测试通过，入口独立复核 PASS。批次取消补丁已交回，Archive 7/7；最终 app 构建/设备复测尚在进行。
- 当前剩余：独立 PDF 审查指出 stream 标记、继承 Resources 与未压缩流内存上限三项，PDF 子 Agent 独占 parser/tests 修复；主 Agent 修复真实 Vision 票据错误（界面展示串曾被当作 canonical fingerprint），负责唯一 Gradle、模拟器和交接。Vision 首次复测已准确显示 worker 未启动，尚未 READY；当前模拟器临时模型已改为 Qwen/Qwen3-VL-8B-Instruct，供专用单文件 QA-Fixed-Vision 验证，诊断开启。恢复后的最终 UI probe、shell/Shizuku、SAF revoke 尚待收口。
- 额度/操作约束（本轮用户再次强调）：已查 Codex 主额度使用 95%；没有使用两次可用 reset。用户再次授权按需 Computer Use。减少重复审查；若中断，从当前现场继续，不重做原 27 条路径。临时凭据仅在本任务模拟器应用内；任务收尾需清理，不能声称已清理。
- 当前任务（2026-09-09，修复进行中，主 Agent 负责）：用户明确授权解决 [用户验收报告](docs/evidence/2026-09-08/a933b11-user-journey-qa.md) 的问题。分支 `codex/user-qa-fixes`，基线 `a933b11`。三个独立子 Agent 分别负责 Skill/Python、Knowledge/PDF/Vision、Provider/SSE；主 Agent 负责 shell、诊断/Chat 集成、按钮可达性、唯一 Gradle 执行、设备复测和交接。先执行可复现红灯回归，再修复及验证，保留原 QA 结论，不提前标为已修复。旧 WIP 保护基线在 `build/user-qa-fixes-20260909/baseline-wip.patch`；本轮不 commit/push/发布。
- 当前回归环境：仅本任务 `emulator-5580` 已重新启动，API 36 / x86_64 / RAM 4 GiB；修复版定向 instrumentation 与真实用户路径分开取证。应用诊断默认关闭的行为不变，后续用户路径回归主动开启诊断。上轮 QA 的卸载/凭据清理/关闭记录仍是历史事实，本轮回归结束需再次清理。
- 当前 Git：实际根目录 `E:/mobileAgentRuntime`，分支 `codex/user-qa-fixes`，HEAD 为 `a933b1135d42ee85c1f475ba584784d13228160e`，本轮源码与测试修改尚未提交。既有 HANDOFF/docs、AGENTS/agent.md WIP 及临时目录保持保护；没有新增 commit/push/部署/发布。上轮合入 main 的来源与 tree 核验见版本修复证据。
- 修复验证：Shell JVM 4/4 与设备 45/45，Provider 45/45，Chat 预算与诊断设备 17/17 已通过；Shell 和 Chat/诊断独立审查 PASS。知识基础回归绿灯，新增 worker 失败场景与独立审查进行中；A 类 grant 审查问题已增加 3 项红灯测试并修复，复核进行中。证据持续更新于 [用户验收问题修复](docs/evidence/2026-09-09/a933b11-user-qa-fixes.md)。
- 以下产物、远端与提交门禁为上轮版本修复/发布准备的历史基线，不代表当前 dirty 修复通过同等门禁：
- 已实现：Internal c1/m1/d1/legacy 与两个 Shizuku adapter 统一非负版本投影；完整 token 与冲突检查保留，提交后映射失败仍为 UNKNOWN_OUTCOME。Composite 直接重复调用重验缓存披露权并永久清除失效缓存，未知结果只保留安全标记；类型化 factory 桥接保留 runtime requestId，避免相同模型 callId 混用执行键。
- 远端：`19b5dc3` 的 CI run `34174287929` 与 license-guard run `34174287949` 已 success，含 check、convergence-device API 36 和 UI API 31/34/35/36；手动 signed-release gate skipped 符合预期。合并提交 `a933b11` 的新 CI run `34175230254`、license-guard run `34175230347` 于 09:02+08:00 为 in_progress，不冒称已完成。未修改 USearch/VectorIndexCache。
- 本地：真实 Backend → Adapter → Executor → JSON version → 原样 expected_version 的 write/patch 往返与 stale CONFLICT 已通过；全仓 strict check + API 36 定向设备测试 **30/30 PASS**。最后的 factory requestId 修正通过 app 全变体 check + licenseGuard；独立只读复核 **PASS**，diff 检查和 CodeGraph sync 通过。最终结果见 [本轮证据](docs/evidence/2026-09-08/7ef48f1-version-contract.md)。Shizuku 新测试是 Android parser/dispatch seam，不等同于真实服务或物理设备验收。本轮独立创建的模拟器已关闭。
- 提交门禁：再次运行 `./gradlew licenseGuard check --dependency-verification=strict --no-daemon --max-workers=4 --console=plain`，981 tasks、exit 0；确切暂存树 REUSE lint **593/593 PASS**，提交 tree 与受检 tree 均为 `2392b336a6dcdbbe6eed580d92f3ae4b06774082`，日志和 allowlist 在 `build/codex-7ef48f1/publish-*`。
- 人工审查 APK：`build/manual-review/mobile-agent-runtime-19b5dc3-review-debug-signed.apk`，204,944,865 bytes，SHA-256 `81a5c837b3d7f5931068c1bbaa649af6c186e0974526ef36265b769c2244d967`。`assembleReview --dependency-verification=strict` 586 tasks PASS；APK v2 debug 签名验证 PASS，debuggable=false，arm64-v8a/x86_64；产品源码与 HEAD 一致，本地文档 WIP 使诊断版本标识如实带 dirty。详情见 [构建记录](docs/evidence/2026-09-08/19b5dc3-manual-review-apk.md)。本次构建未安装、未新增 commit/push 或正式发布。
- 任务前 WIP 保留；`AGENTS.md`、`agent.md`、`docs/DIAGNOSTICS.md`、`docs/REQUIREMENTS.md`、`docs/UI_DESIGN.md` 与开工差异一致。文档同步：新增 [ADR-0009](docs/adr/0009-workspace-version-request-range.md) 与本轮证据，更新 ACCEPTANCE W21/S31、IMPLEMENTATION_PLAN v3.4 及本交接；`HANDOFF.md`/`docs` 仅本地，临时保护目录不纳入提交。

