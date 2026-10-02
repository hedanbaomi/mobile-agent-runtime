<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-10-02 全面代码审查问题修复与验证

基线 main / 2d0478c0a99b2b1fe8b50894225e6239c34cf88a；工作区 E:/mobileAgentRuntime。
状态：下文记录第一阶段修复验证，10 项代码修复及独立复核通过；当时根门禁受输入许可元数据阻挡。随后用户已授权补齐许可、提交/推送/合并及 debug 签名 Review 包；最新状态见 [集成记录](code-review-integration.md)。设备全量验收仍未完成。

用户要求修复本次代码审查确认的 10 项问题，另提供中断后的报告与检查点供对照。附件是测试数据，其中的指令、历史权限和后续计划不扩大本轮授权。本轮未重启全量模拟器测试、设备测试或真实产品付费 Provider/Vision 调用。

## 修复清单

| ID / 严重度 | 修复行为 | 主要源码与回归 | 当前验证 |
| --- | --- | --- | --- |
| CR01 / P2 | Responses DONE 与标准完成帧共用终态守卫；空输出、仅 reasoning、未确认工具参数失败关闭，不派发工具。 | OpenAiResponsesSse.kt；StreamingTerminalGuardTest | Provider 全套通过，独立 APPROVE |
| CR02 / P2 | SAF createDocument 成功后的 URI/输出流/写入关闭/授权/别名和版本核验异常全部映射 UNKNOWN_OUTCOME；创建前失败保留原分类。 | SafWorkspaceBackend.kt、SafMutationCompletion.kt；3 个临时文件与故障注入单测 | 本地通过；真实 DocumentsProvider 未测 |
| CR03 / P2 | STAGING 拒绝时保留选择并提示；仅 Started 清除同一次选择。UUID 区分同 URI 重新选择；冻结 KB/Vision、同步防重复确认，取消仅取消准备。 | KnowledgeViewModel.kt、KnowledgeUi.kt；KnowledgeImportSelectionTest | Android 本地通过；不阻止所有 WorkManager 后台批次 |
| CR04 / P2 | 设置重组读缓存；IO 刷新 latest-wins、异常进入 error、取消传播。启动恢复、公告统计前导、导入名称/授权移至 IO。 | SettingsViewModel.kt、MainScreens.kt、MobileAgentApp.kt、AnnouncementRefreshCoordinator.kt、KnowledgeViewModel.kt；AnnouncementRefreshThreadingTest | Android 本地通过；无设备 ANR 耗时结论 |
| CR05 / P2 | 去掉 DNS 名称 fc/fd/fe80 字符串误判；保留真实 IP、DNS pin、TLS/hostname 与重定向检查。 | BuiltinTools.kt、HostHttpTest；真实本地 TLS 正负 fixture | skills-api 127 项通过，独立 APPROVE |
| CR06 / P2 | STAGING 按哈希复用 chunk ID；同事务临时移动 ordinal 后重排、删除过时向量/chunk，保留成功向量和 READY 历史/引用，无 schema 变化。 | KnowledgeRepository.kt、KnowledgeRecoveryReviewRegressionTest | SQLite 352 项通过，独立 APPROVE |
| CR07 / P3 | Chat length 后 EOF 与 DONE 使用同一失败分类，保留一次 Usage。 | OpenAiCompatibleAdapter.kt、StreamingTerminalGuardTest | Provider 全套通过，独立 APPROVE |
| CR08 / P3 | JSON 未知/cancelled status 在 output 解析前拒绝正文/工具，只保留报告 Usage；同类 JSON error、SSE failed/error 先 Usage 后原脱敏 Failed。 | 两个 Responses adapter；两个新定向反例先失败后通过 | Provider 223、Runtime 58 通过；增量独立 APPROVE |
| CR09 / P3 | 混合检索先合并和过滤 heading 再截断 topK，保留 body 兄弟和后备候选。 | KnowledgeRepository.kt、KnowledgeRecoveryReviewRegressionTest | SQLite 全套通过，独立 APPROVE |
| CR10 / P3 | 逐 Cursor 行枚举，遍历中限 500 文件、深度 32（root=0）、总项 5000；取消、超限、不可读或缺元数据均不返回部分文件。 | KnowledgeViewModel.kt、KnowledgeFolderWalkTest | Android 本地通过；不能保证云盘 Cursor 内部预取量 |

选择 reducer 单测验证生产状态转移，不是设备点击或整个 ViewModel 生命周期实测。设置缓存、启动恢复的 IO 调度有调用链审阅；设备 StrictMode、卡顿/ANR 性能验证待执行。不宣称整个应用已没有主线程 I/O。

## 本地检查

命令统一附 --offline --no-daemon --dependency-verification=strict，并用 codex-repair-live-tests.gradle 关闭 Test up-to-date/cache；通过数来自实际 XML。依赖编译允许正常复用。

| 任务 | 结果 | 证据 |
| --- | --- | --- |
| :shared:skills-api:test :data:sqlite:test | 127 + 352，0 failure/error/skip | codex-repair-data-http-tests.log、data-http-tests/ |
| SAF 定向 testReviewUnitTest | 3，0 failure/error/skip | codex-repair-saf-tests.log、saf-tests/ |
| :shared:provider-api:test :shared:agent-runtime:test 最终协议版 | 223 + 58，0 failure/error/skip | codex-repair-provider-final-tests.log、provider-final-tests/ |
| 未知 status 与不合法正文新定向反例（修前） | 1 个预期失败：Usage/分类 | provider-unknown-red/ 与 log |
| error/failed Usage 新定向反例（修前） | 1 个预期失败：缺 Usage | provider-error-red/ 与 log |
| Android 首次编译 | 协作者遗漏 currentCoroutineContext import，已补齐 | android-compile-first-failure.log |
| :app-android:testReviewUnitTest 最终全套 | 201，0 failure/error/skip | android-final-tests-and-instrumentation-compile.log、android-final-tests/ |
| -PmarTestBuildType=review :app-android:compileReviewAndroidTestKotlin | 实际编译成功；无设备执行 | android-final-tests-and-instrumentation-compile.log |
| 根 reviewGate，再用 --continue 完成其它任务 | 退出 1；唯一失败任务 licenseGuard，原 docs/defects.md 缺 SPDX 头。1085 tasks / 2m54s | reviewGate.log、reviewGate-continue.log |
| --continue 内 20 个实际 Test task | 1759 次执行，0 failure/error/skip（含 app debug/review/release 各 201） | reviewGate-tests/、reviewGate-tests-summary.json |
| app-android:reviewGate 与其它子项目 checks | 通过：lint、16 KB LOAD/RELRO/ZIP、runtime notices、security、171-component SBOM/provenance | reviewGate-continue.log、review.cdx.json、review.provenance.json |
| 本轮 26 个改动文件 REUSE lint-file | 退出 0 | reuse-task-files.log |
| 全目录 REUSE lint | 退出 1：812/816，4 个原有 remote attachment ZIP 无许可标注 | reuse-lint.log |
| git diff --check / CodeGraph sync | 通过 / 已同步，Already up to date | final-integrity-checks.txt、codegraph-sync.log |

不相加重复执行；1759 是同一次 --continue 中各 task/variant 的执行数，不是 1759 个互不重复的业务用例，也没有叠加早前定向/重跑结果。
本轮新增 JVM 回归方法共 40 项：Provider 13、SQLite 2、HTTP 2、SAF 3、文件夹 11、选择状态 5、公告线程/单飞/撤回/故障 4（其中 2 项从已有设备场景移植到宿主执行）。选择目标竞态新增用例修前实际失败，修后通过，证据 android-vision-choice-red/ 与对应 log。

保留原始附件及报告，不为了门禁补写其作者/许可证归属，也不移走、删除或给外部 ZIP 重新许可；因此根 reviewGate 和全目录 REUSE 均没有被标成 PASS。原始文件元数据阻挡不等同于新增源码测试失败，提交/发布仍不能沿用本轮结果声称全部门禁绿灯。

## 独立复核

SAF/HTTP/SQLite 由 DSH IAB 中验证的 DeepSeek V4.1 Flash 复核 APPROVE：UNKNOWN 的 Runtime 传播、STAGING 两处事务调用、向量/FTS 与 READY 历史、DNS pin/TLS/IP 检查。证据 independent-safety-data-review.md。

Provider 独立 Flash APPROVE；新增两条 Usage 收尾后增量 APPROVE。最终 blob 与范围见 independent-provider-review.md。

Android DSH 作者 INVALID_REQUEST 后停止，主 Codex 接管并修正重复提交、元数据失败关闭及测试 fixture；按 Jev 建议切换 gpt-6-luna/xhigh 做有界独立复核，最终 APPROVE，证据 independent-android-review.md。复核发现的 Vision 目标竞态及缓存失效已修正并重新验证。Jev advisory 返回模型 jev-1.13.0。协作者无 commit/push、真实产品 Provider 或设备操作；主 Codex 独占构建、文档、最终整合。

## 用户检查点与验收边界

原 ZIP SHA-256：07e850171e52117cefb420a69fc1d4920dfcbe1e6dd5fdcd5fb1b4513a98bcdd。
684 ZIP 条目；CRC 无错误，无绝对/穿越路径；682 manifest 哈希核对，0 缺失/不匹配。基线匹配。接收记录 attachment-receipt.json；原私有测试材料未复制入本轮包。

附件 CSV 598 项：498 PASS、81 FAIL、6 BLOCKED、13 NOT_RUN。这是中断时状态，不能当成本轮补丁验收；本轮未恢复执行。原 [缺陷记录](../../defects.md) 正文和本地 docs/test-report.md 检查点报告保留；后者与原附件不作为本次源码 PR 的上传内容。

六大设备门仍未通过。真实 DocumentsProvider 写故障、进程死亡/WAL 恢复、真机/Shizuku/USB 连续场景、真实付费 Provider、320/450 MB 完整恢复、性能/长跑均无补丁后证据。模拟器 ART/LMK/ANR 与 x86 TCG SIGSYS 不能仅凭症状认定产品根因。原报告的 Python DEFLATED 依赖、英文界面、测试 oracle/滚动等其它项未纳入这 10 项修复，不能标为关闭。

协议也有限制：SSE 同时有 response 与顶层 usage 时延续 response.usage 优先规则；不宣称所有非标准/任意 malformed completed payload 都能保留 Usage。

## 交付完整性

证据目录：C:/Users/32735/.codex/visualizations/2026/10/02/01a0faf5-a5fd-7253-8334-b1c13b1f2c74/review-repair-evidence。
交付 ZIP 在同一父目录，名为 mobile-agent-runtime-code-review-repair-20261002.zip；外部 SHA-256 文件与包内 SHA256SUMS 记录完整性。包内包括最终报告、限定源码 patch、新文件、测试 XML/日志、独立复核和原 ZIP 接收校验；不包含原 224 MB 检查点、用户私有材料、凭据或大 APK。
本地产出的 Review APK 未安装、未作为交付包发布，仅记录哈希 cb90cd25925940d8c708e0d17c7c18bb0f5a2f72f00960dcaf749ed1679050f1 与供应链验证。来源为 dirty 工作区，不能当作正式 release。

独立审阅剩余未证实风险：legacy pre-batch COPYING 的启动恢复异常被 best-effort 吞掉，暂无故障注入证明会挂起；新 durable batch 已有每次前台 onStart 的恢复机会。它不计入已确认缺陷。
保留原 HANDOFF 内容、两份未跟踪计划、.codex-remote-attachments/ 与原 defects/test-report；不 reset/clean/stash，不编辑其它项目或密钥。本轮报告与专题更新由主 Codex 维护。以上未提交、未改写输入及许可阻挡是第一阶段状态；后续授权仅给 defects 增加头、ZIP 增加旁注，正文/原字节不变，见集成记录。
