<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 464f8f4 复审修复与 v1.0.0 发布准备

基线：`464f8f433165258bc8cfb146433825b8b1fe0ff9`。来源：用户提供的 `REVIEW_464f8f4_2026-10-05.md`，只将 R-01—R-04 作为本轮修复范围。O-01—O-07 的性能与通用治理建议保留为候选，未冒充已完成。

| 问题 | 实现与回归 |
| --- | --- |
| R-01 P1 | 不可变运行/会话/generation；watchdog detach 页面并取消自身 job/审批，旧执行未退出时保留新输入并拒绝新 Run。预处理、预览、消息、错误、引用与 finally 按 owner 发布；审批/executor/前台资源随 owner 清理。真实 ChatViewModel/SQLite 的可控迟到事件和实际审批回调回归。 |
| R-02 P2 | 成功 query vector 与 owner CAS 清理同事务；既有 consent 下优先校验成功缓存并本地恢复。损坏/错 space/query/维度不复用，无成功证据未知仍禁止外发；保留不同 live owner。真实迁移 schema 的事务回滚、回执中断、恢复与并发 fixture。 |
| R-03 P2 | 派发前 DENIED、确定完成后 COMPLETED_WITHHELD、派发后未知分别保存；model-safe JSON、wrapper 缓存、工具消息、invocation 与诊断均保留执行事实；重启恢复不改写已完成但扣留状态。结果不泄露、不重新派发。 |
| R-04 P3 | 完整 URL 先长度/安全检查，2048 字符以上或脱敏会改写的地址整条拒绝；保留地址逐字使用原值。标题/摘要解析后脱敏限长。覆盖边界长度、签名/长参数、Unicode/百分号与敏感 URL。 |

独立只读复核与实际审批回调增量静态复核均无确认的新 P1/P2/P3 阻断；独立审阅是源码结论，没有冒充运行测试。

## 最终本地验证

环境为 Windows、JDK 21、Android API 34 模拟器；测试替身控制事件/撤权/数据库故障，不调用付费服务。实际执行：

```text
./gradlew.bat reviewGate verifyCiPins verifyDependencyLock verifyDependencyVerification
  :app-android:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.ChatRunOwnershipDeviceTest,runtime.mobileagent.ChatContextCompactionDeviceTest,runtime.mobileagent.ChatInputBudgetDeviceTest,runtime.mobileagent.RunToolsReplayDeviceTest,runtime.mobileagent.WebSearchPermissionDeviceTest,runtime.mobileagent.WebSearchDeviceTest
  --no-daemon --offline --dependency-verification=strict
reuse lint
```

- Gradle exit 0，4 分 53 秒，1133 个任务；`reviewGate` 包含 `check`、许可正反门、lint、依赖 pins/locks/checksums、Review APK 原生 16 KiB 对齐、法律资产、SBOM/provenance 和安全检查。
- API 34 定向设备测试 **22/22**，失败/错误/跳过均为 0。其中三个 Chat 所有权用例包含实际 `onApprove` 回调的 watchdog 结算与迟到回调拒绝、切换 B 后迟到事件/异常/finally、返回 A 后仍不重新投影；执行的是当前编译后的最终源码。
- Android Debug/Release/Review JVM 各 **230/230**；SQLite **387/387**，skills-api **139/139**，agent-runtime **59/59**，provider-api **272/272**。其他默认 `check` 套件也通过。计数来自本轮任务对应 XML，未混入历史自定义探针输出，也不将重复构建变体计为不同产品场景。
- REUSE exit 0，**878/878** 文件版权与许可完整，缺失/非法表达式/读取错误均为 0。
- 337 个 Kotlin 源文件的 SHA-256 与最终检查前快照一致；CodeGraph sync exit 0，返回 Already up to date。特定发布脚本未被索引准确定位时，先记录索引覆盖限制，再定向读取缺失源码。

本地原始日志、JUnit XML 和源码快照保存在 `.private/review-464f-v1-20261005/`；公开附件只包含对应源码、产物和核验记录，不上传用户原报告、私密日志或签名输入。远端 PR/main CI、最终合并 SHA 与正式产物核验结果由 Release 核验附件记录。

版本为 `versionName=1.0.0` / `versionCode=2`。正式构建仅使用既有正式身份、干净合并源码和严格依赖验证；APK、源码、SBOM、provenance、公开证书与哈希以 GitHub Release 附件为准。密钥/密码及用户原始报告不进入仓库或发布附件。

## APK 发布门禁补充核验

修复通过 [PR #42](https://github.com/hedanbaomi/mobile-agent-runtime/pull/42) 合并，真实 E 根 main 与 origin/main 同步到 `1eaf09e0d887ef8e447cba9ba507ffd3d949063a`，该合并提交 CI 为 8 success / 1 manual-release skip。

用户随后明确只构建和发布 APK。正式构建暴露出 defaultConfig 的双 ABI 会合入 Release；补充修正将 ABI 限制留在各 build type，Debug/Review 保持双 ABI，Release 仅 ARM64。发布 gate、手动 CI 附件、SBOM 与 provenance 均改为 APK；实际 signer 与既有正式证书的比对作为必要发布步骤独立核验。

补充 `reviewGate verifyCiPins verifyDependencyLock verifyDependencyVerification --offline --dependency-verification=strict --no-daemon --no-configuration-cache` exit 0，3 分 5 秒、1087 个任务；12 个 APK ABI ZIP 正反用例通过，Review 原生对齐、法律资产、安全及来源检查通过。`releaseGate --dry-run` 的任务图无 AAB 生成任务。REUSE exit 0、878/878；337 Kotlin 文件仍与上述设备回归的源码快照完全一致。补充独立只读审阅结论为 APPROVED，无阻断项；结论限于源码、发布核验流程和任务图，不冒充实际正式构建。正式发布只使用通过最终签名与产物门禁的 APK，最终合并 SHA、CI、证书与哈希由 Release 核验附件给出。

契约见 [ADR-0023](../../adr/0023-run-ownership-and-known-outcome-recovery.md)、[知识库](../../KNOWLEDGE.md)、[联网搜索](../../WEB_SEARCH.md) 和 [发布文档](../../RELEASING.md)。本轮受控故障/模拟器回归不等于真实 Android 杀进程、线上付费服务、物理设备/USB/OEM 或长稳验收；应用商店发布未包含在 GitHub Release 交付中。
