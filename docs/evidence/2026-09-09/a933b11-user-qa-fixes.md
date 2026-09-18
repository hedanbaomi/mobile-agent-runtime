<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 用户验收问题修复与回归

状态：**2026-09-10 本地修复与本轮定向回归已收尾**。基线为 `a933b1135d42ee85c1f475ba584784d13228160e`，分支 `codex/user-qa-fixes`；未提交、推送或发布。以下最终结果替代历史过程段中的“进行中”“待验”表述，不代替未执行的真机或完整 K06 矩阵。

用户于 2026-09-09 明确要求解决 [上一轮报告](../2026-09-08/a933b11-user-journey-qa.md) 的问题。本轮授权本地修复、测试及同一独立模拟器回归；不 commit、push 或发布。保留全部原有规则/文档 WIP；改动前 patch 位于 `build/user-qa-fixes-20260909/baseline-wip.patch`。旧报告保持历史结论。

## 回归证据

本轮日志与本地 QA 产物位于 `build/user-qa-fixes-20260909/`。凭据不得进入产物；部分 UI/evidence 截图包含用户原始材料的本地呈现，不能把整个目录当作可公开分享的无内容日志。

## 最终结果（2026-09-10 11:30+08:00）

- 最终命令：`./gradlew licenseGuard check :app-android:assembleReview --dependency-verification=strict --no-daemon --max-workers=4 --console=plain`，exit 0，1066 tasks，3m10s；日志 `final-strict-check-review.log`。其中 AgentRuntimeTest **15/15**，nullable cwd、非法 union、boolean 字符串、nullable enum 均有回归；独立只读复核 **PASS**。
- 最终 Review：`qa-fixes-final-review.apk`，SHA-256 `8f7cf1f79876c2e5e1e9425299163a6bbd7413e4486d7a1f8b6d3462ee6e75ba`，APK v2 调试签名校验通过，已在本任务模拟器安装验证。不是正式签名或发布包。
- Shell **真实通过**：在启用当前策略后创建无 Skill、无 workspace 的 Agent-scoped PERSISTENT grant，发送仅执行 `echo QA_SHELL_FINAL_OK` 的请求并批准一次。Inspector 实际 tool 消息为 `SUCCEEDED`、`exit_code=0`、`stdout=QA_SHELL_FINAL_OK\n`、`timed_out=false`、`cancelled=false`；请求 `cwd=null`。见 `shell-final-inspector-result.png`、`shell-final-real-complete.*`，最终助手也正常完成。此证据验证暴露、准备、批准、Shizuku 执行、结果回传整个路径。
- Vision **实时状态/取消通过**：原始 17 页文件在确认准确 Qwen 目标后显示 `VISION_PROCESSING`，批次 PROCESSING、1 processing/0 waiting，页面可操作；点击 Cancel job 后转为 FAILED/UNKNOWN_OUTCOME，显示重复收费确认入口；重启应用后仍保持 UNKNOWN，没有观察到自动恢复处理。见 `vision-final-live-processing.*`、`vision-final-cancel-unknown.*`、`vision-final-restart-unknown.*`。没有再次批准或重发该未知请求；这不证明外部 Provider 没有处理或计费。两页正常完成路径另已有 READY/COMPLETED、4 chunks 的真实证据。
- PDF 最终 **34/34** 边界测试、**294/294** 原件页数一致且零解析错误、Android 原生四轮内存通过，详见下方。原始 Lone Study 的最终定向文本元数据检查：2 页、4658 字符，无 `en-GB` 污染，无 `L o n e` 字符拆分，保留完整 `lone`；`pdf-final-lone-text-quality.log` 不保存正文。这是针对原报告缺陷的检查，不是全语料 OCR/排版质量认证。
- 两份原始 A 类 Skill 名称及绑定、完整 Class B ZIP 的隔离 Python 与最终回复、英文 SAF 实际授予/撤销、真实 Provider UI probe 均已通过，证据见各过程段。SSE 累计用量、usage-only 尾帧、空完成和长度截断由协议测试验证；不把旧模型空回复的历史原因追认成已抓包证实。
- 清理 **已完成**：SAF 状态 Revoked，Shizuku 用户意图关闭，Dangerous Mode Disabled；`runtime.mobileagent` 与 `runtime.mobileagent.test` 卸载均 Success，包列表为空；测试进程内临时 key 已清除；恢复原手写设置后关闭本任务 emulator-5580，随后 `adb devices` 为空。账户侧 key 按用户原约定自行撤销。AVD/原始材料副本和本地证据保留。
- 最终末次 Review 的 Shell/Vision 路径采用 UI、Inspector 和重启后持久状态取证；收尾检查显示该安装的应用诊断 Off/0 B（`final-cleanup-settings.png`），没有最终诊断 ZIP。此前启用诊断的分批回归与 `shell-preparation-diagnostics.zip` 仍保留；不声称所有设备轮次都开启了应用诊断。
- CodeGraph sync 与 `git diff --check` 通过；相关安全/协议/恢复改动均完成独立只读审阅。完整 K06、物理 Wired USB/OEM、正式发布仍未验收。过程交接原文保留于 [历史快照](qa-fixes-handoff-history.md)。

## 2026-09-10 续测过程（历史，最终结果见上）

- 当时安装 Review APK：`dc31f2c01a4128ee5ab85c3f12715e4344de8591716fc97a27f873c2b307a0f1`。两页 `Breathing+with+the+Forest.pdf` 已真实 Vision → READY/COMPLETED，4 chunks 与 evidence 已核验；产物 `vision-real-ready.*`、`vision-real-evidence.*`。原先将展示标签当作 canonical fingerprint 的错误已修复，展示和授权指纹分开。
- 英文 SAF 已实际选择本任务本地目录、允许读写并撤销：`saf-active-english.*`、`saf-revoked-english.*`；撤销后显示 Revoked，读写均未授权。
- 模拟器存在两个 `shizuku_server`（2403/2418），管理器允许弹窗返回后应用仍 Denied。停止两个测试实例并用管理器展示的启动命令启动单实例 22867 后，应用恢复 Granted/Ready/Connected。证据 `shizuku-restored-connected.*`。没有修改权限检查或通过 ADB 强行授予应用权限。
- 最终 PDF 边界回归 **34/34 PASS**，包括精确 `/Length` 末尾紧接 `endstream` 的便携合成用例；重新编译后，294 原件与独立参考页数 **294/294 一致，零解析错误**（`pdf-final-page-comparison.json`）。stream 标记、继承/间接 Resources 和未压缩流上限修复已独立复核。最终 Android 原生四轮解析 **PASS**（`pdf-final-native-memory.log`，143.834 秒）；五个采样点最高 PSS 328954 KiB，约 321 MiB（`pdf-final-native-memory-samples.log`）。
- 17 页 Vision 续测发现 `loadSnapshot` 调用 `pendingApiQueries` 时等待整个同步 Vision 的 `indexLock`；读方法已去除该锁。`KnowledgeRepositoryPendingApiQueriesConcurrencyTest` 通过（首次缺失测试 import 的编译错误不作为红灯）。UI 按 job 取消没有关联 ConsentWorker 的问题也已补齐 tag 与调用方 job ID；先等待 WorkManager 停止，再进入持久化 hook。`vision-progress-cancel-green.log` 的并发测试与设备测试编译通过，独立静态复核 PASS；真实外发取消仍待最终包验证。当前测试请求已 force-stop，按未知结果恢复，不能自动重试。
- `provider-ui-probe-pass.*`：真实 UI 探测 Metadata、Streaming、Tools 均 Supported，Images 为 Not declared；02:26 UTC 完成。
- Shell 续测：先建 grant 再启用 Dangerous Mode 会使旧 policyVersion 授权失效；保留这一检查。在当前策略下新建无 Skill 的 Agent 后，`shell-preparation-diagnostics.zip` 02:43 UTC 记录 effectiveGrantCount=1、snapshotBindingCount=0、shellToolCount=1，证明新持久授权能暴露 Shell。RequestPrepared 前失败已定位为 Runtime 不支持 Shell cwd 的 `[string,null]` schema；定向支持 nullable 两成员类型并拒绝任意联合类型，正在测试与独立复核。实际 Shell 执行尚待最终包验证。
- `pdf-consent-device-green.log`：ConsentCancellationDeviceTest **1/1 PASS**，验证关联 job tag 与取消 consent work；并发查询测试与独立静态复核已通过。此设备用例不替代真实外发取消验收。
- 主 Agent 仍负责唯一 Gradle、设备和交接。临时 provider 凭据、Dangerous Mode 和测试模拟器尚未清理；不得将此记录作为最终验收或发布凭据。

| 路径 | 修改前 | 修改后及边界 |
| --- | --- | --- |
| 新持久 shell grant 无旧快照绑定 | `shell-red.log`：4 个 JVM 用例，正向暴露与撤权测试前置暴露共 2 项失败 | `shell-green.log/xml`：4/4；暴露统一使用已有 resolver 的当前 canonical/frozen-row 校验。`shell-device.log`：API 36，ToolingOrchestrationTest + EffectiveCapabilityResolverHotUpdateTest，45/45。独立只读审查 PASS，真实 Shizuku 回归待完成 |
| PDF 字符片段/栅格化/状态/取消 | `knowledge-red.log`：4/4 失败，分别为 TJ 单词重复空格、同意前栅格化、Vision 中间状态未落库、批次取消未刷新 | `knowledge-green-2.log`：DocumentParser 25、KnowledgeRepository 68、KnowledgeArchiveImport 5，全部通过；对应 XML 已另存。`knowledge-device.log`：KnowledgeImportLifecycle/KnowledgeRuntime 7/7。worker 失败新增场景、独立审查与真实材料复测待完成 |
| Provider metadata / SSE / 空完成 | `provider-skill-red.log`：41 个用例中 5 个预期失败 | `provider-review-red.log` 另复现尾部 usage 与错误帧 usage 两项遗漏；修复后 `provider-review-green.log` 全 Provider 90/90，独立复核通过。真实 metadata 已通过；能力探测的单 token 预算另见下文。最初 `provider-red.log` 是测试 import 编译失败，不作为产品红灯 |
| A 类名称/空 grant | `provider-skill-red.log` 名称用例失败；`skill-grant-red.log` 独立 grant 用例失败；`skill-review-red.log` 新增重复/损坏/过期授权 3/3 失败 | `skill-review-green.log/xml`：SkillRepository 14/14，独立复核通过。两个原始 SKILL.md 均显示实际名称、启用、绑定并保存为 Agent r1 的 2 个 Skill；`skill-a-both-bindable.json/png` |
| Class B 准备阶段错误 | 旧用户路径为 Inspector 未准备、INTERNAL；完整指令超过保守输入预算可复现同一错误分类 | ChatInputBudgetDeviceTest 修改前预期 CONTEXT_OVERFLOW、实际 INTERNAL；修改后与 DiagnosticsDeviceTest 合计设备 17/17，`provider-chat-diagnostics-green.log`。预算值保持，准备阶段错误可持久化且诊断只含固定字段，独立只读审查 PASS。完整原 Skill 用户路径待复测，不声称已证实隔离 Python 故障 |
| 英文 SAF 按钮 | 上轮用户界面只见前两个操作，须切中文才能发现撤销 | SAF 行改为 FlowRow；`saf-english-wrap.json/png` 在 API 36/720×1280 英文布局显示完整四个操作，已目视检查。目录授权后的真实撤销待复测 |

Shell 与 Chat 测试的首次编译错误仅为测试准备过程；不把编译失败当行为复现。`chat-budget-red.log` 首轮被 Provider 编译中断；后续 `chat-diagnostics-red-skill-green.log` 才是 Chat/诊断实际红灯。`knowledge-green.log` 首轮为未定义局部变量导致的编译失败，修正后才产生知识模块绿灯。

## 原始 PDF 与第二轮用户路径发现

- 第一版修复包 `e7fc813e…` 的完整目录导入仍耗尽内存，不能作为 OOM 修复通过。隔离设备测试去掉 ONNX 与渲染器后，仅解析原始 `12+adept+12.pdf` 仍被 LMKD 杀死；`pdf-native-memory-red.log` 与 `parser-memory-logcat-local.txt` 留有证据。
- 将整个 PDF 的对象扫描改为复用单个 Matcher 后，同一 API 36 模拟器连续四轮解析原始大小文件通过，最大采样 PSS 为 352609 KiB。`pdf-native-memory-matcher-green.log` 为设备绿灯；不以 JVM 内存结果代替 Android 结果。
- 新增嵌套页树、压缩对象流与压缩数据尾部 CR 的先红后绿回归。`pdf-page-tree-red.log`、`pdf-object-stream-red.log`、`pdf-stream-length-red.log` 对应真实缺陷；`pdf-stream-length-green.log` 中 DocumentParserTest 30/30。完整 294 份原始 PDF 的页数与独立 pypdf 解析结果 294/294 一致，零解析错误；仅记录页数等元数据，见 `pdf-page-count-comparison.json`。解压设 32 MiB 上限，异常不得静默变成完整知识。
- 第二版 Review APK SHA-256：`600c9d1beacf64ec0cb65f4392c237b94e18c2fce281f8fdd947608f05becb29`。诊断开启后导入已越过原 OOM 点，最终在 220 份文件后出现 `ForegroundServiceDidNotStartInTimeException`，当时采样 PSS 约 443 MiB。`candidate2-foreground-crash.txt` 和 `candidate2-workmanager.txt` 记录了连续 batch worker 交接时的前台服务停止/启动竞态。
- 项目原 WorkManager 2.10.0 升至 2.10.5；官方该补丁修复了[仍有待处理命令时停止前台服务的问题](https://developer.android.com/jetpack/androidx/releases/work#2.10.5)。6 个新增依赖文件的 SHA-256 已与 Google Maven 下载值核对，见 `workmanager-checksums.txt`。测试依赖同时使用版本目录，保留严格锁定与校验。第三版 `0fc0d61034ef8aa0d41ef0dca5cb29a4969dbcbbbbe0757cded9277333fee08b` 的完整目录回归仍待收口。
- SiliconFlow 真实连接成功（4165 ms），metadata 从此前 404 中断变为 Supported。后续 feature probe 仍失败，原因是固定 `max_tokens=1`：相同无副作用工具探测请求，1 token 返回 `unexpected_state`、无工具调用；64 token 返回 `tool_calls`、1 个调用、实际输出 30 tokens。新增测试先红，再将上限改为 `min(用户输出预算, 64)`；`provider-probe-budget-green.log` 是全模块绿灯，最终 UI probe 尚待确认。
- Class B 原始完整 ZIP 已启用并绑定至 Agent r2。配置足够的 131072 context / 4096 output 后，真实请求已完成准备并进入逐次批准；批准后隔离 Python 返回 `scripts/check-structure.py --help` 的结果，最终回复仍待记录。
- 独立审查继续指出 API Embedding 消费票据在 operation 尚未插入或 PREPARED/CACHE_READY 时的恢复缺口，以及批次取消没有停止 batch work。已有 DISPATCHED 的红/绿回归通过；其余生命周期修复仍在进行，不标为完成。

## 验证边界

### 11:40+08:00 收尾核验

- 第三版完整 294 文件 UI 导入通过，见 `full294-imported.json/png`，PID 9954 保持；这里只确认入库与无崩溃，含图项仍等待 Vision，不声称全部 READY。
- 完整 Class B 已完成隔离 Python 执行与最终回复，见 `skill-b-full-real-complete.json/png`，真实 usage 为 input 17550 / output 295。
- API 恢复补充了 consumed ticket 重投、批次启动及 worker 失败三种入口；无 operation/PREPARED/CACHE_READY 清除同意并返回等待，VISION_PROCESSING/DISPATCHED/UNKNOWN 保留未知结果门禁。`embedding-entry-recovery-red.log` 为17项中的2项失败；`embedding-entry-recovery-green.log` 17项通过；独立静态复核 PASS。
- `final-shared-data-gate.log` 为 Knowledge API、Provider API、data 全测试及 licenseGuard 合跑 exit 0；其中批次取消 Archive 7/7。最终 app 构建尚未完成。
- 真正 Vision 用户路径发现界面展示串与后台 `VisionBinding.fingerprint` 不同，导致未消费票据被拒绝；修复版已能显示“worker could not start”，不再静默等待。已分离显示标签和授权指纹，新增 JVM 回归，正在构建复测。
- PDF 独立审查仍为 NEEDS_AMEND：stream 内标记误识别、继承/间接资源未解析及未压缩流上限问题在修复中；此前 294 文件页数和内存结果只覆盖该材料集，不替代这三个缺口。

模拟器沿用本任务专用 API 36 / x86_64 / RAM 4 GiB / 720×1280，设备 `emulator-5580`。instrumentation fixture 与真实用户 UI 路径分开报告；Debug 构建不作为高权限控制面安全验收。设备用户回归前开启应用诊断，仍遵守默认关闭、固定枚举和限额，不记录正文、文件路径、命令或密钥。

本轮临时凭据的只读 `/v1/models` 核验为 200；未记录凭据。后续完整 Skill 回归需要配置足够输入预算，保持原有保守 UTF-8/图像预算，不暗中扩展用户设置或丢弃图像。SiliconFlow [模型说明](https://www.siliconflow.com/zh-tw/blog/deepseek-v3-2-now-on-siliconflow-reasoning-first-model-built-for-agents) 用于核对 V3.2 窗口和参数；实际应用预算与实际调用结果另记，不以文档代替执行。

物理 Wired USB、OEM、正式签名与发布不属于本次可完成的模拟器回归。
