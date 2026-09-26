<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-09-26T23:55+08:00（Asia/Taipei）。本提交在临时 worktree `E:/mobileAgentRuntime/.private/handoff-fix-20260926` 完成，推送后即移除。

## 2026-09-26 PR #28 合并、Z05 降级 P2 与新评审包（当前）

- **合并**：按用户明确指令将 draft [PR #28](https://github.com/hedanbaomi/mobile-agent-runtime/pull/28)（head `codex/skill-uninstall-announcement-layout` @ `1e9c81f8`）以 `--no-ff` 合并为 **`3913e231939a84fe8be6d23c728e173a36f6df19`** 并推送；`8d97dc0` 是其 merge-base，合并树与 CI 全绿的 head 树完全一致（`e37ebdb3`）。GitHub 自动标记 PR MERGED。`git ls-remote origin main` 与本地 main 均为 `3913e231`；其 ci run `36251129942` 7/7 job success、license-guard run `36251129936` success。
- **Z05 定级**：真机曾报告 c2eedf6 时代备份导入删除无关 Agent 历史。用户确认：修复后新版使用当时的旧备份未导致无关 Agent 历史被删除，且旧版本未做复现测试——用户将严重度定为 **P2** 跟踪（未证实的历史报告），不再是合并或验收阻断项；复核路径保留在真机验收流程 PR-09，若复现删除再升级。
- **评审包**：`8d97dc0` 包已落后 main 一个合并，已在干净 detached worktree 对 `3913e23` 重跑 `reviewGate`（BUILD SUCCESSFUL，构建前后工作树干净）。交付 `.private/manual-test/20260926-review-3913e23-clean/`（APK SHA-256 `b62f175df7c42e894eeadb9a75daf43e07e3cdae6b8d7d05bc047d68d5e4968d`，176,379,063 B，v2 签名同一 Debug 证书，22/22 原生库 16 KB 对齐，notices PASS，provenance `gitDirty=false`、SHA 绑定）。真机验收流程文档更新为 `docs/plans/device-acceptance-3913e23.md`（**仅本地，未入仓**），新增 6.3 节 PR-01–PR-09 覆盖本 PR 改动面。
- **范围**：本提交仅改本交接文件，无代码变更；真机验收仍未执行；根工作区 `E:/mobileAgentRuntime` 的本地 HANDOFF 与计划文件 WIP 不受影响。

## 2026-09-26 设备验收报告修复（历史记录，同日已按用户指令合并为 `3913e23`，Z05 已由用户定级 P2，见上节当前条目）

- 来源：用户提供 `device-acceptance-8d97dc0.md` 与同名 `-0926.zip`，针对 review APK `8d97dc0` 的 Z04/Z05、E02、H04/H10、I04 等发现。证据包仅本地分析，未入仓；当前工作树仍为 `codex/skill-uninstall-announcement-layout`，基线 main `8d97dc0`，保留上一节 Skill 卸载与公告布局未提交工作。根工作区 `E:/mobileAgentRuntime` 的 `HANDOFF.md` 与计划文件 WIP 未改动。
- 实现候选：导入的冻结会话只在运行时从同 ID/同格式/同地址本机 Provider 取 secret 引用；知识库修复核对当前活动 chunks 与 READY generation 成员，失败/取消的导入任务不能使手动整库重建假报完整，但正常单文档导入仍可发布其他已就绪内容；导入事务逐项核对已有 Agent 的快照、会话、消息、消息部分、Run、工具记录和审计 ID，减少或同数量替换均回滚；单会话 ZIP 条目从 16 MiB 元数据限制分离至 32 MiB 内容限制，设置页同步显示实际上限。流式取消保留部分正文与终态标记，shell 等待后端明确超时，特权目录浏览器支持 continuation，只读预设撤既有整目录写权限且新附加默认只读，导入有可见进度与成功/失败提示。G04 已派发 Python 的副作用不可证实，仍保持 UNKNOWN 且不自动重放。
- 当前验证：最新源码的 `licenseGuard licenseGuardReverse check verifyCiPins verifyDependencyLock verifyDependencyVerification` 严格离线全仓门禁退出 0，`python -m reuse lint` 775/775，工作流 2/2，`git diff --check` 退出 0。API 35 `mar_api35_matrix` 模拟器定向 `connectedDebugAndroidTest` 6/6，含流式取消、只读授权、shell 超时与公告 320dp/大字体；最新源码追加 `TransferArchiveDeviceTest` 1/1，证实 Android 端 ZIP 导入事务可用。导入保护 `TransferRepositoryIsolationTest` 6/6；知识库失败任务、批次恢复和手动重建的定向回归通过。无真机、旧版备份原件及真实 Provider 复测。
- Z05 阻断：报告称旧版备份导入后无关 Agent「验收A」历史物理消失；当前 `TransferRepository` 无删除该历史的路径，合成旧 JSON/ZIP 导入保留无关快照、会话与消息，异常副作用触发器会在事务内检测并回滚。用户证据 ZIP 只有当前 review 包导出的 3 个有效备份与 1 个失败的部分 ZIP，不含所述 c2eedf6 旧备份原件；用户已确认原件无法取得。因此原设备现象与具体原因尚未复现，不能称设备验收 PASS，也不能据此合并。（2026-09-26 后续：PR 已按用户明确指令合并；用户确认新版用当时旧备份未复现删除、旧版未做复现测试，问题定级 P2 跟踪，不再是阻断项。）设置页增加显式结果提示，但需新包重测导入与无关历史的前后状态。
- DSH：按用户指示用 Computer Use 在系统文件夹选择器中选择准确 worktree 后即退出；DeepSeek V4.1 Flash 验证 Git 根/分支一致。其 Z05 只读调查在模型多次重试且长时间未给结论后已停止；不把它当成独立复核通过。
- 文档与 Git：`docs/IMPLEMENTATION_PLAN.md`、`docs/ACCEPTANCE.md`、`docs/SKILLS_AND_SECURITY.md` 与 [ADR-0013](docs/adr/0013-imported-history-and-index-recovery.md) 已同步；上一节 Skill 卸载专题与 ADR-0012 同在提交 `eadd8f7d289996bdc3b66ddfabbeb69e64fca5c9`，状态交接提交 `17b09425473060170e1b8d07db54e8cc9a2cab14`。两个提交已推送 `origin/codex/skill-uninstall-announcement-layout`，草稿 [PR #28](https://github.com/hedanbaomi/mobile-agent-runtime/pull/28) 已附 Z05 未闭环说明；远端 main 核对为 `8d97dc0`，PR CI 于 22:34+08:00 仍运行。根工作区 WIP 未动。独立只读安全审查最初结论 `NEEDS AMEND` 的两处确定缺口已修正，相关回归与全仓门禁通过；未再次申请独立复核，不冒称最终审查 `PASS`。Z05 安全闭环前不得宣称全部修复或普通合并。（已被 2026-09-26 用户指令取代：已合并，Z05 降级 P2。）
- 清洁 review 包：从 `17b0942` 且 `git status` 清洁的工作树执行 `:app-android:assembleReview` 严格离线依赖校验退出 0；包及来源清单在 `.private/manual-test/20260926-review-17b0942-clean/`，APK SHA-256 `54b45789d61b4422cf9c8b84110791498c92189ad89821d8348bce03b3d4c3f6`、176379063 bytes、`runtime.mobileagent` 0.1.0 review、minSdk 26/targetSdk 35，Manifest 未设置 `debuggable`，使用 Android Debug 证书签名而非正式签名。自启 API 35 模拟器已关闭。此包供 Z05 替代备份/无关历史前后核对、Z04 真实 Provider 与其他设备缺陷重测；原 c2eedf6 备份不可取得，不能据包构建完成宣称验收通过。

## 2026-09-26 技能卸载与公告按钮布局（同分支先前工作）

- 基线：`origin/main` `8d97dc03ea27758e62ea267b73026119632a1bfa`；独立分支 `codex/skill-uninstall-announcement-layout`。根工作区 `E:/mobileAgentRuntime` 的分支 `fix/review-apk-notice-integrity` 与既有 `HANDOFF.md`、计划文件 WIP 未改动；本文件从 main 的交接增量编辑，不得整体覆盖根工作区现行交接。
- 实现：Skill 详情可请求并确认卸载。事务内解除 Agent 当前绑定、撤销现有授权和能力、使待用审批过期、删除活跃安装；保留会话/审计/记忆，历史快照需要导出时保留包字节及原安装身份；其他场景清除未使用的包字节。重新导入须获新安装身份和新授权。公告页 4 个操作等宽；中文“全部标为已读”按实测字宽缩小字号，单行居中完整显示。相关契约与验收见 `docs/REQUIREMENTS.md`、`docs/SKILLS_AND_SECURITY.md`、`docs/UI_DESIGN.md`、`docs/IMPLEMENTATION_PLAN.md`、`docs/ACCEPTANCE.md`，取舍见 `docs/adr/0012-skill-uninstall-history-boundary.md`。
- 验证：命令范围 `:data:sqlite:test :app-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.AnnouncementsLayoutTest`，以 `ANDROID_HOME=/c/Users/32735/AppData/Local/Android/Sdk`、`--offline --no-daemon --dependency-verification=strict --quiet` 运行；SQLite `SkillRepositoryTest` 23/23，最终 API 31 模拟器公告布局 3/3（320dp、360dp、320dp 且字体比例 1.3），失败/错误 0。`:app-android:compileDebugKotlin` 单独通过。`licenseGuard` 退出码 0；`python -m reuse lint` 771/771；`git diff --check` 退出码 0。实机视觉、真机 Skill 卸载/重装尚未验收，不把模拟器结果当真机结果。
- 独立只读复核：原生 `gpt-6-luna/xhigh` 核对了本工作树 Git 根与分支，审查当前 diff，指出原 12sp 公告文案在 320dp 下会裁切；主 Agent 增加实际文字布局溢出断言并修正字号/内容宽度后，上述 3 项模拟器测试通过。卸载事务、授权撤销、历史导出和重装身份隔离未发现其他确定问题。DSH 的 DeepSeek V4.1 Flash 模型可见，但新会话的独立工作树路径无法核实，故未向其派发。
- Git 与下一步：本节代码、测试和文档已随本轮修复提交 `eadd8f7`；此前“未提交”状态仅为提交前历史。根工作区 WIP 受保护；本次用户已明确授权提交，并要求修复完成后推送合并，Z05 原始设备缺陷仍待验，合并继续受阻。（2026-09-26 后续：已合并为 `3913e23`，Z05 降级 P2。）

## 2026-09-25 用户诊断八项与工作区授权回归修复（历史记录）

- 隔离 worktree：`E:/mobileAgentRuntime/.private/qa-eight-fixes-20260925`，分支 `codex/qa-eight-fixes-20260925`，基线 `origin/main` `ebcbbbf9d9cc5a479af9160331e88aa5d8347dcd`。根工作区 `fix/review-apk-notice-integrity` 的既有主题、图标与文档 WIP 已单独本地提交为 `709b3f796f0dd9cf86feed3fbddab658a73420fe`，不与本分支混合；不得用本分支旧交接整体覆盖根工作区现行交接。诊断 ZIP 仅用于问题归类，未入仓。
- 源码改动：发送清空输入后将预检移到 IO，预检可取消；助手同一回合的文字、思考和工具按顺序置于一气泡；审批遮罩半透明并保留对话；Agent 可显式开启跳过逐次工具确认，仅新会话快照与实时设置同时允许时生效；普通会话运行增加前台服务；Internal/SAF 已有大文件按单次预算分块读取；硅基流动 `Qwen/Qwen3.8-27B` 从官方目录读取上下文长度并校验目标。
- 追加诊断 ZIP5 的工作区回归：危险模式切换会递增策略版本，既有 Grant 失效仍保留这一安全语义。默认工作区解析现要求当前策略版本；Agent 编辑页标明旧版本授权且预设可重新授权。完整设备文件的持久授权独立于 Thread 目录，在新 Run 纳入当前 Agent 的有效 Grant，并经危险模式、Authority、后端与实时授权闸门。再次明确确认完整设备访问时，原默认目录仅按最新授权代仍有效的原能力续期，已撤销的写/删能力不可复活；两步部分失败分别显示。工具暴露诊断补齐聚合后端探测和授权计数，未写路径/密钥。
- 独立只读审查：DSH `DeepSeek V4.1 Flash` 已逐轮审查八项修复和新增授权问题，促成时间戳顺序、快照开关、预检取消、滚动与消息顺序、目录回写 CAS、孤儿工具 UNKNOWN、目录有界读取等修订；新增授权审查又发现默认目录续期可能恢复已撤销能力、误撤路径范围/一次性授权，现改为最新授权代的整目录持久原能力集且仅替换同类旧行。审计 sink 测试与 SAF Binder 持锁问题已修订；末轮只读复核对权限范围、SQLite 唯一约束、旧授权处理和事务原子性未发现确定问题。独立审查未编译或上设备，主 Agent 本地构建/测试证据见下。未调用付费模型或用户原件。
- 最终受影响模块命令 `./gradlew :app-android:compileDebugKotlin :app-android:compileDebugAndroidTestKotlin :app-android:testDebugUnitTest :data:sqlite:test --offline --no-daemon --quiet` 退出码 0；XML 报告 app-android JVM 130/130、SQLite JVM 313/313，失败/错误/跳过均 0。全仓 `./gradlew licenseGuard licenseGuardReverse check verifyCiPins verifyDependencyLock verifyDependencyVerification --offline --no-daemon --dependency-verification=strict --quiet` 退出码 0；`python -m reuse lint` 769/769，退出码 0；`verify-workflows` 2/2。源码提交 `03e8e329d0b9e8abf8e04f736e3c2d5de1374624` 后工作树清洁，`assembleReview` 严格离线依赖验证退出码 0；人工审查包位于 `.private/manual-test/20260925-review-03e8e32-qa-clean/app-android-review.apk`（SHA-256 `16df79d59b3b3c6d866d6348710bc56c513c6b5edf7401eae28575a634ec8000`，176362679 bytes）。APK 元数据为 `review`，minSdk 26、targetSdk 35；签名为 Android Debug，应用 manifest 未设置 `debuggable`，不可当正式签名发布包。`adb devices -l` 无设备，不能冒称真机验收。
- 待验证边界：前台服务不保证系统杀进程后自动恢复无查询接口的已派发流式响应；未知收费/副作用结果不得自动重放。SAF 非可寻址云端流可能按 offset 顺序跳过；24 MiB 用户原件与真实服务、真机 IME/后台/审批视觉、完整设备与原目录并行暴露尚未执行端到端验收。默认目录若后端能力集变化导致续期失败，完整设备授权仍可单独成功，界面会明确提示需手动重授默认目录。
- 文档同步：本节、`docs/IMPLEMENTATION_PLAN.md`、`docs/ACCEPTANCE.md`、`docs/SKILLS_AND_SECURITY.md`、`docs/UI_DESIGN.md` 与 ADR-0006 已更新。后续人工审查应安装上述 review 包，覆盖 IME/后台/审批视觉、完整设备与原目录工具并行、24 MiB 原件读取及真实硅基流动元数据；不得用本 worktree 的旧交接覆盖根工作区或把未验设备项写成通过。

按 [agent.md 第 1 节](agent.md#1-按任务读取与开工) 选择资料。现行规则见 agent.md；本文件记录现场和待办，历史任务中的授权不自动延续。

## 0a. 2026-09-18 文档管线复审 R1–R5 收口（本轮，冻结待复核）

- **审查对象**：用户的独立复审证据包 `mobile-agent-runtime-aaace272-review-evidence.zip`（sha256 `9d62c0cd…`，19719 字节），结论 `NEEDS_AMEND`，指认切片类 R1（P1）与 R2–R5（P2）。被审提交 `aaace272b16df11a231df7ed61fbe926b2a32b5f`；修复提交 `c0a2e3ca384088d6af8ad9c4e87b1264d829ec1c`（父 `aaace272`，`2026-09-18 23:33:04 +0800`），分支 `codex/user-qa-fixes`，已从 `origin` 拉取。
- **修复（6 文件 +418/−23，仅源码与测试）**：
  - R1：`ProcessingUnit.requestText` 由 `String = ""` 改为 `String? = null`；`effectiveRequestText()` 改为 `requestText?.let { return it }`，不再用 `ifBlank` 把合法空白切片回退成整页；缺失字段且无正偏移的旧行才回落页面原文，非零偏移按 `coverage.textStart/textEnd` 还原并校验。`KnowledgeRepository` 的发送文本、重建与分块四处统一走 `effectiveRequestText()`。
  - R2：新增 `MAX_REQUEST_TEXT_CHARS = 8_500` 并作为硬校验；容量不足抛 `PIPELINE_TEXT_LIMIT_EXCEEDED`（不截断、不放大单片），派发前另有防御性长度检查 `failUnit(..., "LOCAL_PREPARE")`。
  - R3：切点校正到 Unicode scalar 边界，未配对 UTF-16 代理项前置拒绝（`PIPELINE_INVALID_TEXT`），末尾对每片重新断言长度与边界。
  - R4：切轴先看超限维度（表头只作等维偏好），整数中点必须严格落在区间内部，否则抛 `PIPELINE_REGION_LIMIT_EXCEEDED`，不再产生零高度退化区域。
  - R5：`selectTarget` 不再回退 `priorUnitState`，成功只来自当前目标有效结果；无结果且无当前 attempt 时回到 `PLANNED`，UNKNOWN 终态与显式重试授权不变。
- **测试**：新增 `DocumentUnitPlannerReviewRegressionTest`（11 条）、`DocumentPipelineReviewRegressionTest`（5 条）与 `OpenAiCompatibleVisionTest.reviewSlicesAreBoundedAndUnicodeSafeOnBothWireProtocols`（CHAT/Responses 两协议真实出站 payload）。
- **本轮独立复核（DSH 一审，不依赖 Android/Gradle）**：用热缓存 `kotlinc-jvm 2.1.10` + serialization 插件编译 knowledge-api/domain/serialization 三模块 main 源集（424 class，0 error），复刻原审查 R1–R5 反例并执行 **23/23 PASS**：空白切片长度由 `[7526, 30002, 30002, 7475]` 收为 `[7526, 7501, 7500, 7475]` 且拼接无损；600,000 字符规划阶段本地失败、零派发；恰好 64×8500 全字符保全；emoji 反例逐片 UTF-8 往返无损；宽表由抛异常变为 4 个合法区域且面积完整覆盖；R5 状态表达式返回 `PLANNED`（修前 `SUCCEEDED`）。提交正文所述远端验证 run `35362417522`（licenseGuard/Reverse、CI pins、依赖锁定/严格校验、受影响 JVM 套件、REUSE）本轮未独立重跑。
- **未闭环边界（不随本轮关闭）**：① 8,500 只是本地字符上限，planner 仍无目标模型窗口/输出预留/图像预算建模与不同窗口的出站 payload 测试；② 文本偏移与图像条带仍按序号配对，无版面坐标证明，`tableHeader`/`continuation` 未进入 prompt，现有 mock 渲染器与固定成功 HTTP mock 不能证明多栏/宽表/跨条带图文关联。
- **Git 与文档**：本轮只更新本地 `HANDOFF.md`/`docs/evidence/2026-09-18/`，未提交、未推送、未合并 main、未部署、未调用收费服务。分支内 `HANDOFF.md` 停在 2026-09-17 且缺本轮记录；根工作区版本为最新，后续同步以根工作区为准，不要用分支旧文档整体覆盖。
- 完整证据：[2026-09-18 文档管线复审 R1–R5 收口](docs/evidence/2026-09-18/document-pipeline-r1-r5-closeout.md)。

## 1. 现行状态与当前任务

- **2026-09-14 12:08+08:00 源码提交、推送与合并 main 已完成，无进行中认领**：实现提交 058a02ef50897c2d533600b058c948590e0b775b，加测试计数竞态修正 daa8cfa821f987976f804ae77e225eae7e9aa5ca，经 [PR #16](https://github.com/hedanbaomi/mobile-agent-runtime/pull/16) 普通 merge 成 95ee670b2e4bc1586e1a659d37c37e39f818e044。合并前最新 SHA 的 push/PR CI 与 license-guard 均成功；正式 release 任务按预期跳过。main tree 与验证分支 HEAD 完全一致，仅34源码/测试/构建文件，HANDOFF、docs、AGENTS/agent、.private/.codegraph 对原 main 零差异。根工作区分支与既有 WIP 保留，本地交接仅更新状态，未推送。

- **2026-09-14 P1 Vision follow-up：实现、独立只读源码审查、本地回归与 API 36 non-debuggable review 专项通过，无进行中认领。** 按用户要求两个 Luna Max 子代理已停止，由 DSH DeepSeek V4.1 Flash 接续并完成 UI/恢复回归。开始前选完整 Vision 目标、Android staging 到实际适配器按目标执行、typed 错误/真实 dispatch/attempt 诊断、详细 DEBUG 正文与图像、旧缓存唯一映射、UNKNOWN 精确手动重试、暂停恢复原子事务均已落地。501 JVM 测试记录 0 失败/错误/跳过；review 设备 29/29；licenseGuard/Reverse、REUSE 633/633。唯一产品工作树 E:/mobileAgentRuntime/.private/vision-followup-20260914，分支 codex/vision-followup-20260914，HEAD 90170880e4aa8db3273eca76d6b8581aaab4194d，实现交付时为36 tracked 修改与9 untracked文件；后续源码提交/推送/合并状态见上条，未正式发布。原始两次 UNKNOWN 原因、真实收费 Provider、全量294 Vision及物理设备未验；DEBUG保留密钥/私有续传字段过滤和16MiB滚动窗口。根既有 WIP 保留，DSH早期单文件误写已备份并仅撤回该误写。详见 [本轮报告](.private/vision-followup-20260914/docs/evidence/2026-09-14/p1-vision-followup.md)。


### 上轮已完成状态（历史记录）

- **2026-09-13 P1 知识库批次导入复核与修复：实现和专项模拟器验收通过，源码 dafaae3 已通过 PR #15 合并入 main 9017088，无进行中认领。** DSH 初稿被真实 UI 红测试发现导入确认框入口不可达；另修复 Vision 配置状态未发布、暂存清单/同名源/ZIP 快照恢复、派发前 UNKNOWN 持久化、结果复用、紧凑 PDF 解析和真实诊断调用链。schema 20。完整说明：[P1 批次导入复核报告](docs/evidence/2026-09-13/p1-batch-import-review.md)。
- **验证**：独立只读协议/迁移/授权审查 PASS；最终 licenseGuard/check/assembleDebug PASS（953 JVM 测试执行数，含变体重复，0 失败/错误/跳过）；REUSE 624/624；API 36 设备 11/11 PASS。真实 SF Qwen3-VL CHAT 角色视觉小样正常 UI 导入、生成证据并完成显式文本工具检索。合成 294 项批次在 228 个成员时暂停，force-stop 重启后仍 PAUSED，手动继续后 294 个唯一成员全部发布，2 次实际视觉调用、0 失败/UNKNOWN。最后阶段修订在最终 APK 另跑暂存设备回归 3/3 PASS（与前 11 项重复部分，不计为 14 个独立场景）。
- **范围限制**：原始 294 PDF 只验 UI，不是本轮全部付费视觉处理；严格图片聊天未验收，真实 RAG 使用明确 text-only 模式；本专项没有重新运行全部 42 条 QA 或 DeepSeek 官方连接，不将前轮历史当本轮证据。无物理设备、正式签名或 release。
- **Git**：隔离 worktree .private/qa-vision-batch-review，分支 codex/knowledge-batch-review-20260913，基线 7caebd3；提交 dafaae379dc45e53017899a234ca92170bdd25ba 已推送同名 origin 分支并核验远端 SHA，仅 21 个源码/测试文件。main 已在 20:41:22+08:00 通过 PR #15 普通 merge 更新为 90170880e4aa8db3273eca76d6b8581aaab4194d；main tree 与 dafaae3 完全一致。根工作区仍为 codex/user-qa-fixes、HEAD 3f454183176b7664ff774593bd6dba396d595b2e（仅本地文档，不得 push）。HANDOFF/docs/AGENTS/agent、.private 和 CodeGraph 缓存全部排除；其他既有 WIP 保留。用户随后明确授权合并最新分支到 main，已完成；正式发布未授权。
- **合并核验**：[PR #15](https://github.com/hedanbaomi/mobile-agent-runtime/pull/15) MERGED，远端 main SHA 已复核；仅 21 个源码/测试文件，HANDOFF/docs 等排除路径零差异。dafaae3 分支 CI 与 license-guard 均 success；PR 新触发的重复检查在合并时仍运行，使用普通非管理员合并，没有绕过规则。
- **运行收尾**：本轮 owned emulator-5562 已关闭，AVD/DSH/诊断数据保留。GitHub CI 和 license-guard 于 20:18+08:00 仍在运行；本地通过不代表远端已绿。
- **文档同步**：KNOWLEDGE、IMPLEMENTATION_PLAN、ACCEPTANCE、ADR 0011 和本报告仅本地。旧 DSH 初稿及前轮记录归档于 [收尾前交接快照](docs/evidence/2026-09-13/handoff-before-p1-review.md)，不作为新的待执行任务。此前源码已由 PR #14 合入 main 7caebd3；前轮第二次完整 QA、R2-01—R2-07 的证据仍在对应日期报告。

## 2. 持续保护边界

- 第一方代码、文档和服务保持 `AGPL-3.0-only`，保留 license guard、SPDX、第三方归属及供应链门禁。
- `HANDOFF.md` 和 `docs/` 在历史任务中曾只本地保存；本次用户已明确要求提交未提交工作，并在修复完成后推送合并。新任务的提交、推送、部署、正式签名、发布和付费调用仍须以当次授权为准。
- 产品范围仍以已确认需求为准：Root、应用内无线 ADB、DPC、Termux、PTY、Accessibility、宿主 PowerShell/宿主 shell 不属于当前产品范围；不得因整理文档扩大范围。
- SAF 是独立 workspace backend；Shizuku/Wired 是独立 Authority，不自动 fallback。未知外部结果不得自动重放。秘密、URI、设备路径及配对材料的保护沿用当前专题规范。
- 用户已将 UI 重做留作独立任务；功能修复不能冒充 UI 验收。

## 3. 保留的未决事项（上轮记录，需按任务复核）

下表保留既有未决事项；未单独注明 2026-09-08 核验的“本轮”、历史 SHA 和验证状态均指原记录时间。本轮只更新第 1 节 main 合并、人工测试出包任务及当前 dirty 状态，未据旧记录继续无关产品任务。后续处理某项时核对当前实现及证据；全部旧记录仍可在快照追溯。

| 项目 | 状态与下一步 |
| --- | --- |
| P0 统一 Workspace canonical flow | **源码已推送 `2d35933`，文档仅本地**。剩余：① Agent 草稿 attach 先入图书馆，与 `saveWithPrompt` 不是同一 SQLite 事务，靠 `rollbackNewAgent` 补偿；② Global Picker 走 `WorkspacePickerPort`/`planFor` 而非 `CanonicalWorkspaceCoordinator` 类（intent 语义相同）；③ `WorkspaceAccessPort.attach*` 仍给内部/遗留测试用，Agent 编辑页不再调用；④ Agent 编辑页仍保留 grant preset / default dropdown 作为已有 workspace 上的高级授权，不是第二套 attach；⑤ 本轮未做独立安全复核、全仓 strict gate、REUSE、物理 Wired USB |
| Thread workspace immutable / Provider 连接集成 | **源码已推送 `17a695e`，文档仅本地**。已绑定 Thread 切 workspace = 新 Thread。Insets = `IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED`。Workspace UI presentation 卸载丢失仍可接受，fallback 已接上。真机 punch-hole 与物理 Wired USB 仍待验证 |
| 当前 dirty tree | 本地 HEAD 3f45418 是文档提交，禁止普通 push；本轮仅隔离分支提交 21 项源码/测试并回同步主工作区，详见第 1 节及报告。其他 WIP 保留 |
| UI 设计语言 | 用户已明确当前重构不符合要求；本轮按要求停止 UI 改造，只修功能。后续作为独立任务重新梳理交互与视觉，不得把本轮功能修复视为 UI 验收通过 |
| F-001 工具能力开关历史崩溃 | `candidate_intermittent`，不能因暂未复现关闭。再次出现时记录 APK SHA、时间和步骤，导出应用内诊断 ZIP；原生崩溃或系统强杀另取完整 Logcat |
| 物理 USB Companion | `E2E_BLOCKED`：需要真实 Windows USB 设备、配对、物理断连和恢复验证；不得用模拟器替代 |
| OEM/非模拟器差异 | `E2E_BLOCKED`：需要物理设备上的 DocumentsProvider、Shizuku Binder death/rebind 与权限撤销验证 |
| K06 大负载 | **2026-09-10 定向修复回归 PASS，完整 K06 未验收**：294 PDF 入库不再崩溃；最终 294 页数核对与原生四轮内存通过。含图文件未全部付费 Vision/READY；ENOSPC、温控/耗电及 Android 15/16 长时配额矩阵仍未验收 |
| 真实 Provider / Skill 用户验收 | **2026-09-10 本轮定向回归已收尾**：结果见第 1 节与最终修复证据；不代替所有外部服务、模型或真机路径验收。旧报告保留历史 NEEDS_AMEND |
| 正式 Android release | 未授权：正式包名/品牌、release keystore、AAB、商店发布与后检后置 |
| 历史推送后 CI（当前 main 见第 1 节） | **2026-09-11（`4f0556d`）**：ci `34551014140` **success**（6/6 job 全绿，含此前 OOM 的 `check` job 及其 `Review-like non-debuggable APK/SBOM/provenance gate` 步骤），license-guard `34551014020` **success** —— 上轮 `-Xmx4g` 修复已由 CI 确认。**本轮（`c43a54a`，4f0556d 复审修复）**：ci `34576879237` **success**（7/7 job，含 `check` 全 19 步与 `Review-like non-debuggable APK/SBOM/provenance gate`）、license-guard `34576879212` **success**。**2026-09-11（`7500ad3`）**：license-guard `34494819479` success；ci `34494819391` **failure** —— `check` job `102930452830` 的 review 变体门禁 D8 外部 dex 合并 `java.lang.OutOfMemoryError`（根因与修复见本轮证据）。上一条 `dbceaa2` 的 license-guard `34476278614` 为 **success**；其 ci `34476278584` 自 12:21Z 起长时间停在 **in_progress**（updatedAt 12:21Z，疑似排队/卡住），同样不得记为通过。2026-09-08 09:02+08:00 `19b5dc3` 的 CI `34174287929`、license-guard `34174287949` 已 PASS。普通 push 下 `Signed release gate (manual only)` skipped 是预期行为 |
| 收敛轮独立只读复核 | 2026-09-08 对本轮版本/factory 修改执行独立只读审阅，结果见本轮证据；不据此宣称此前全部授权/持久化/native 并发改动均已完成独立审计 |
| 收敛轮新增 device 测试执行 | 2026-09-08 核验：`7ef48f1` 远端 convergence-device API 36 **30/30 PASS**，关闭待观察项。本轮本地另跑 Shizuku 版本/文件 store 与 RunTools replay 定向 **30/30 PASS**；不替代物理设备验收 |
| RunCoordinator 完整抽离 | **部分**：prepare/owner/stamp/cancel/release + manifest 已接入 Chat；流式/tool-loop 收集器与 `cancel()` 的 durable 写入仍在 VM（后者为故意保留：提前写 CANCELLED 会覆盖 dispatch 中的 UNKNOWN 判定）。本轮按用户要求不重开 |
| model.invoke 编辑器控件 | **未做**：manifest + fail-closed + 费用告知先行；Agent 编辑器可见配额控件待后续任务 |
| 工作预算（非 Internal） | **未做**：SAF/Shizuku/Wired/knowledge/Python/ZIP/picker 同原则待后续；Internal 先行并有测试。本轮按用户要求不重开 |
| 收敛轮大规模实测 | **未做**：1k/10k/50k chunks 的索引 load/内存/重建次数（`vectorIndexStats` 计数器已就绪）；真实 Provider/large KB/打孔/Wired USB 仍阻塞（同前） |
| MCP/远端 Skill 重放 | **已收敛**：接口默认改 deny；两 MCP 桥显式 deny（无已完成调用记录可比重验）；composite 转发已实现并有 JVM + device 测试。行为变更：批准后同 call 重放披露改为 deny，需新 call 走批准路径 |

## 4. 本轮专项：文档管线 R1–R5（2026-09-18）

| 项目 | 状态与下一步 |
| --- | --- |
| 文档管线切片/Target 状态 R1–R5 | **修复已推送但冻结待复核**：`c0a2e3c`（父 `aaace272`）在 `codex/user-qa-fixes` 关闭 R1（P1）与 R2–R5（P2），源码级已由本轮独立口径 23/23 反例复核通过。下一步：由独立审查者按原证据包口径复核该提交；在复核通过前不改判审查结论，也不合并 main |
| 8500 本地上限 vs 真实模型窗口 | **未闭环设计缺口**：8,500 现为明确的本地 UTF-16 上限，但 planner 仍无目标窗口/输出预留/图像预算建模，缺不同窗口的真实出站 payload 测试。不得把本地字符上限说成模型上下文规划 |
| 图文对应证据 | **未闭环设计缺口**：文本偏移与图像条带按序号配对，无版面坐标证明；`tableHeader`/`continuation` 未进入 prompt；现有 mock 渲染器与固定成功 HTTP mock 不能证明多栏/宽表/跨条带关联。不得据此宣称图文关联已验收 |
| `HANDOFF.md` 双版本分叉 | 分支 `codex/user-qa-fixes` 的 `HANDOFF.md` 停在 2026-09-17 且缺本轮记录，根工作区版本为最新。后续同步以根工作区为准；合入分支前需显式确认文档差异，避免用分支旧文档整体覆盖 |
## 5. 接手动作与证据

1. 以新任务确认范围，核验 Git 根目录、分支、HEAD 和 dirty 状态，保护已有 WIP。不要从根工作区文档 HEAD 直接 push。
2. 本轮 P1 结果以 [复核报告](docs/evidence/2026-09-13/p1-batch-import-review.md) 的实际提交/远端 SHA 与最终验证记录为准；不要重新执行已经完成的检查，也不要把历史授权扩大为合并 main 或发布。
3. 按 agent.md 分级读取受影响专题；保留第 3 节未决事项，只有当前任务触及才复核。
4. 文档管线 R1–R5 的复核与验证记录见 [2026-09-18 收口证据](docs/evidence/2026-09-18/document-pipeline-r1-r5-closeout.md)；该轮只更新本地文档，分支源码提交 `c0a2e3c` 未合并 main。
5. 历史完整记录见 [本轮整理前交接](docs/evidence/2026-09-13/handoff-before-p1-review.md) 与 [旧规则整理前快照](docs/evidence/2026-09-05/handoff-before-agent-rules.md)。快照内相对链接以原 HANDOFF 所在目录解释。
