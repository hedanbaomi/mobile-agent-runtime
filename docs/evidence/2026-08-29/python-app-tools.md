<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Android Python Skill 宿主接线

时间：2026-08-29T01:00:00+08:00。基线：`7511b22ffd7a7d3021b7857b6500cbe75d037ad6`，并行工作区，不提交或推送。

范围：R09/R10/R11/R12，S03—S10 的 App 接线部分；不代表 M6、安全或设备验收完成。

## 文件与入口

仅新增 [PythonSkillTools.kt](../../../app-android/src/main/kotlin/runtime/mobileagent/PythonSkillTools.kt) 和本证据文档；根交接、Gradle、ViewModel、迁移由主流程维护。

```kotlin
fun pythonSkillTools(
    container: AppContainer,
    context: Context,
    snapshot: AgentSnapshot,
    runId: String,
    budget: PythonRunBudget? = null,
): ToolExecutor

interface PythonRunBudget {
    fun reserveBrokerCall(): Boolean
    fun reserveModelCall(maxTokens: Int): Boolean
}
```

调用方须先持久化与 snapshot 绑定的非终态 Run，并将返回值与其他 ToolExecutor 组合。预算实现必须原子扣减与外层 AgentRuntime 相同的 toolCalls/modelRounds 和 Token 预算；返回 false 则拒绝。model reservation 包含 UTF-8 prompt 字节数、输出 Token 上限、256 个协议余量，保守扣留、不退款或重试。**未注入 budget 时所有 Broker 子调用均拒绝**，纯 Python 计算仍可在隔离限额内运行。没有 Run、Run 已终止、Run 超时均拒绝。工具说明包含授权能力和 HTTPS host 摘要，审批 UI 应展示该说明及冻结的参数。

## 已实现边界

- Discovery 仅接受 snapshot 与当前 Agent 同时绑定、启用、重新校验原包完整性后仍为 B 的 Python 安装。每安装一个 `py_<install digest>_<function>` 命名空间；入口取 manifest，不接受模型指定入口。发现时固定一条真实 grant，永不合并不同 grant 的能力与资源。
- 输入与输出须提供 inline `inputSchema`/`outputSchema`。支持有界的 object/array/string/integer/number/boolean/null、properties/required/additionalProperties、items、enum/const、长度/数组/数值范围。object 必须显式声明 properties 和 additionalProperties。不支持 `$ref`、组合 schema、pattern、格式等关键字；此类包不暴露工具，不静默跳过校验。
- 每个 callId 冻结包、grantId/installId/hash/revision、资源范围和参数；生成随机 UUID invocationId 与 SecureRandom 32-byte token。每个脚本先 NeedsApproval，approve 重查同一授权。已开始、取消、失败、成功、UNKNOWN 均不缓存结果供重放，同 callId 拒绝再次执行。
- 启动及每个 Broker frame 重新检查同一 ticket、Run/snapshot、当前 Agent 绑定、启用状态、同一条 grant 全值、可选 expiresAt 和原包 hash/manifest。撤权、包变更、资源解绑、终态 Run 不继续提供能力或返回旧结果。
- 只调用 `IsolatedPythonRuntime`，不加载 PythonNative、不启动宿主进程解释器、不提供主进程 fallback。单次时限取 manifest 限额与 Run 剩余时限较小值；Broker 有调用数、请求 ID 防重放、48 KiB 帧限额与 20 次调用上限。
- `knowledge.search`/`knowledge.read`/`document.read`：同 grant 与单个 capability 声明、snapshot、当前 Agent、现存 KB 的交集；读取前校验 document 的 KB 归属；结果有字节上限。
- SDK `http.request` 对应 `network.http`：只允许 GET，方法与同 grant/单 capability 声明求交；固定域名通过 HostHttp，DNS/重定向路径再查 live grant；不接受自定义 header/body。HostHttp 的地址绑定、TLS、私网和重定向策略由其独立实现负责。IPC 返回正文另限 24,000 bytes，超限报错，不伪装完整返回。
- `model.invoke`：只允许 snapshot 已绑定的 Chat profile/provider、文本 prompt 和 maxOutputTokens；同 grant scopesJson 与 manifest 都必须显式列 `modelProfileIds`、`maxModelCalls`、`maxModelTokens`，Run 必须有 maxModelTokens；最多三次并先保留 token 预算。秘密仅宿主注入，工具不接收秘密；事件/失败不触发自动重放。输出按本次秘密集合脱敏。
- `storage.get/put`：只访问本 install 哈希目录与哈希 key，AtomicFile 原子写，默认/上限 32 MiB、单值 24,000 bytes、最多 4096 个文件；不接受宿主路径。
- `files.writeArtifact`：内部 cache、随机 handle、受限名称、JSON 内容、单项 24,000 bytes、本次 1 MiB、共享 artifact cache 32 MiB/4096 文件；不导出。`files.readHandle` 仅接受本 invocation 创建的 opaque artifact handle，并复核根目录和体积。
- `log.info`：有界记录 severity/字节数；任意脚本 message 完全不保存，以免未知秘密绕过正则脱敏。Audit 只保存 invocation/install/hash/grant/revision、固定状态和计数，不保存 token、参数、正文、路径或异常堆栈。
- 不认识的 capability、无法执行的资源限定字段、任意外部文件 handle 均 DENIED。Python FAILED/CANCELLED/TIMED_OUT/UNKNOWN 映射明确 status/error JSON，附 automaticReplayAllowed=false；协程取消继续向上传播。模型子调用 UNKNOWN 会封锁后续 Broker 请求，并强制父 Python 结果保持 UNKNOWN，即使脚本捕获异常后自行返回成功也不能覆盖外部不确定状态。

## 明确未完成项

1. 当前权限 UI/仓储没有 modelProfileIds 和子模型 token/call 授权字段，Chat Run 默认预算也无 maxModelTokens，因此普通现有配置下 `model.invoke` 为 DENIED。未默认扩权或发起收费请求。已按主流程要求提供 PythonRunBudget 端口；主流程负责把子调用与外层后续模型轮次接入同一 AgentRun 计数，并完成费用/目标展示；端口存在本身不代表集成或验收通过。
2. 未接 SAF 用户短期只读 handle 注册/吊销端口。当前 readHandle 仅能读本次创建的 artifact；不能读取用户传入路径、URI 或未知 handle。
3. Artifact 是 cache 内本次调用结果，不是用户已导出文件；本文件不实现导出 UI、持久 artifact 索引、跨调用读取或清理策略。达到全局上限后拒绝继续写。
4. 当前 schema 支持子集刻意严格。缺 schema、引用外部 schema 或使用其他 schema 关键字的包不会出现在 tools；需显式扩展验证器后才能支持，不能自动补宽泛 schema。
5. 宿主只连执行端口；isolated UID、进程销毁、native 限额、FD、双 ABI 和无状态残留仍由 runtime/设备验证证明。本文件不宣称这些已经通过。

## 实际验证

- 已读取入口、agent、交接、技术方案、需求、许可、Skills 安全专题及实际端口源码。CodeGraph 优先执行，但精确文件查询返回大量不相关源码并被截断；记录后改为限定路径直接读取，不将索引缺失当作源码不存在。
- 2026-08-29T01:00+08 初版内存 Python 静态契约断言 **15/15，exit 0**；补共享预算端口后重跑 **16/16，exit 0**：SPDX、入口、无聚合 grant、单 grant 身份、完整性复核、随机 ticket、仅 isolated 入口、审批/防重放、HostHttp GET、未知权限拒绝、模型显式 scope、无 payload audit、原子 KV、artifact handle、共享预算 fail-closed 与无尾空格。该检查只检查源码文本，不是 Kotlin 编译或行为测试。
- `git diff --no-index --check -- NUL app-android/src/main/kotlin/runtime/mobileagent/PythonSkillTools.kt` 无空白错误输出（仅 LF→CRLF 提示）。
- **未执行** Gradle、Kotlin 编译、模拟器、真实网络、收费模型、生产变更或 commit/push。统一构建、App 集成、真实设备与独立安全审查由主流程继续；本交付状态为 `IMPLEMENTED / BUILD_AND_REVIEW_PENDING`。

## 建议主流程验证场景

使用自造 B 包和内存/测试 DB：同 grant 能力/资源成功、A 能力+B 资源拒绝、禁用/撤权/换包/过期/当前 Agent 解绑拒绝；审批后撤权拒绝；重用不同参数 callId 拒绝；UNKNOWN 不重放；两个 Skill KV 隔离；路径/外部 handle/超额拒绝；manifest narrowing qualifier 不被忽略；模型缺显式 profile/token/Run budget 时零网络。随后通过真实 isolated runtime 验证取消传播与下一次新进程。不要用上述静态断言替代这些行为测试。

## 2026-08-29T01:21+08：真实 runtime instrumentation 测试实现，未执行

本次新增 [PythonRuntimeDeviceTest.kt](../../../app-android/src/androidTest/kotlin/runtime/mobileagent/PythonRuntimeDeviceTest.kt)，不修改生产 runtime、App 配置或 Gradle。测试不是本节的设备通过证据；状态 `TEST_IMPLEMENTED / NOT_COMPILED / NOT_EXECUTED`。

### 精确 runner 与依赖

在 App defaultConfig 配置 `testInstrumentationRunner = "runtime.mobileagent.PythonRuntimeDeviceTestRunner"`。该 runner 位于同一个 androidTest 文件，继承 AndroidJUnitRunner，将测试 host 的 Application 换成 Android 基础 Application，避免执行 MobileAgentApp 的数据库、秘密和 DI 初始化。真实 IsolatedPythonRuntime、Service、Binder、JNI、打包的官方 CPython 与 SDK 均不替换。AndroidX Startup 自动 ContentProvider 仍可能初始化 WorkManager 自身数据库，因此应使用专用且无真实用户数据的模拟器；测试源码没有任何 DB/SecretStore/网络连接调用。

需要 JUnit4 和 AndroidX Test runner/extension；供主流程锁定的坐标：

```kotlin
androidTestImplementation("androidx.test:runner:1.6.2")
androidTestImplementation("androidx.test.ext:junit:1.2.1")
androidTestImplementation("junit:junit:4.13.2")
```

现有 App coroutines 和 JSON 依赖供测试使用，不需要 mocking 框架、coroutines-test 或测试服务替身。本轮未解析/下载这些测试依赖，也未修改 build.gradle。

### 场景矩阵

| 测试 | 实际将验证的路径 |
| --- | --- |
| officialCpythonExecutesJsonWithFreshPidAndNoPreviousGlobals | 真实 CPython `sys.implementation=cpython`、版本严格等于当前构建锁 `3.14.7`、计算 JSON 回值；Binder UID/PID 等于 Python os.getuid/getpid 且不同于 host；两次 PID 不同、builtins marker 不残留 |
| hostMarkerAndDirectSocketSubprocessAndCtypesAreDenied | 只创建、读取和删除本测试自身的单个 host-private marker；Python open、未连接 socket 创建、良性 subprocess shell、ctypes 对本 marker 的加载尝试必须由权限或模块缺失拒绝，不接受 OSError 等无关异常作为通过 |
| infiniteLoopTimesOutDiesAndNextInvocationSucceeds | SDK 真实发出 ready 帧证明已进入脚本，然后无限循环；Host 返回 TIMED_OUT，signal-zero/ESRCH 确认该已知子 PID 不存在；随后新 PID/无旧 global 的真实调用成功 |
| cancellationAfterReadyKillsWorkerAndNextInvocationSucceeds | 收到真实 ready 帧后取消 coroutine、有限时间 join，立即发起下一次调用且须成功，再确认旧子进程消失；不在两次调用间人为 sleep/等待死亡来掩盖复用竞争 |
| ticketIdentityAndLiveRevocationAreCheckedBeforeBrokerDispatch | invocationId/runId/hash/revision/token 任一改变在启动前拒绝且没有 capability dispatch；同 ticket 首帧允许后即时撤权，第二帧被拒绝且不进入 capability handler |
| inputAndOutputLimitsAreEnforcedAndLargeOutputUsesTheRealPipe | 输入上限差一个 byte 时在 authorization 前拒绝、恰好上限成功；真实 JSON 输出大于 64 KiB control frame 正常走 FD；128-byte 输出限额拒绝大结果且不暴露正文 |
| realSdkRoundTripsBrokerResponseLargerThanOneControlFrame | 真实 SDK→native→Binder pipe→host Broker→chunk frames→SDK 的大于 64 KiB 合成响应完整回传，原 ticket 保持不变 |

fixture 先使用真实 SkillArchive.inspect 检查 B 类、hash 和入口。ZIP 为内存构造的 STORED 条目，避免假定 zlib 可用；模块固定 `device_fixture.py`，manifest 入口 `device_fixture:run`，函数只收一个 JSON 参数，与 native `PyObject_CallOneArg` 契约一致。TicketGateBroker 只是无资源的确定性测试能力端点，不替换解释器或 IPC；生成的 token 不写证据、不用会输出 token 的对象比较断言。

限制：socket/subprocess/ctypes 被预打包 stdlib 排除时，通过表示该能力不可用，不等于已通过直接系统调用逃逸测试；本测试不提供/执行用户原生载荷。signal-zero 只检查已知测试子 PID 的存在性，不向其他进程发送终止信号。测试每项设 30—45 秒 JUnit 上限，协程与死亡等待另有限时。

### 静态验证与主流程后续

- 2026-08-29T01:21+08：抽取 7 份 Python source，仅 `ast.parse` 检查语法、验证顶层单参数 run，构造对应内存 STORED ZIP 并校验 CRC/原文回读，**7/7，exit 0**。没有在本机执行 fixture、无限循环、socket、shell 或 ctypes。
- 校验声明的 `@Test` 数量为 7、只出现 `Os.kill(pid, 0)` 而没有 killProcess、runner 使用基础 Application、无尾空格，exit 0。
- `git diff --no-index --check -- NUL app-android/src/androidTest/kotlin/runtime/mobileagent/PythonRuntimeDeviceTest.kt` 无空白错误输出，仅 LF→CRLF 提示。
- 未执行 Gradle、Kotlin 编译、模拟器启动、instrumentation、真实网络/数据库/密钥、收费 API 或提交推送。由主流程统一加入依赖并运行 `connectedDebugAndroidTest`，可过滤 `runtime.mobileagent.PythonRuntimeDeviceTest`。
- 已向主流程报告：当前 MobileAgentApp 必须有 isolated-process 早退，不能在 isolated service 启动时初始化宿主 DB/DI；现有 `isCurrentProcessIsolated` helper 在 runtime 模块为 internal，跨模块接线需要所有者明确处理。测试 runner 不可被用来掩盖这一生产启动缺陷。

## 2026-08-29：UNKNOWN 持久终态与设备反例增补（当前轮）

本节替代前文有关“UNKNOWN 作为 Value JSON”“超时一律 TIMED_OUT”“输出溢出一律 FAILED”的旧接口描述；此前静态检查结果仅代表当时源码，不是本轮设备证据。

### App 结果边界

- UNKNOWN 改用共享 `ToolResult.UnknownOutcome`，不得包装成成功 Value。Run 本地 sticky 标志与持久 `RunStatus.UNKNOWN_OUTCOME` 同时阻止该 Run 的后续任意 callId，不只是重放原 callId。
- `markUnknown` 在 `NonCancellable + Dispatchers.IO` 中通过同一数据库事务保存 Run、对应 ToolInvocation 及元数据审计。Run 清除 retryAcknowledgedAt；结果只保存固定 code/status/automaticReplayAllowed=false，不保存参数、密钥、原始异常或输出。
- 已派发 HTTP、模型、KV/artifact 写入后的 Broker 取消、响应限额/截断、授权变化或异常立即进入 UNKNOWN；脚本即使捕获 SDK 错误后返回成功也不能覆盖此状态。未派发的 secret/config/参数失败保持已知错误。模型调用没有自动重试或退款。
- Python 成功结果必须为完整 JSON 并通过声明的输出 schema；缺失、无效、超限或 schema 不符均不能作为成功接受。已知 FAILED 使用 Invalid，明确的未派发取消/超时使用 Denied；错误不再冒充 Value。
- 主流程负责消费 UnknownOutcome 后停止 ToolLoop，并在 Chat 取消/finally 时保留执行器已经持久化的 UNKNOWN。该路径仍需统一编译与真实 App 验证。
- 精确新增 runtime 合同为 PythonExecutionRequest 末尾 `onDispatched: (() -> Unit)? = null` 和 PythonExecutionResult 末尾 `dispatchAccepted: Boolean = false`。App 在 START Parcel 构造完成、即将 binder.transact 前的回调中置 volatile dispatchAttempted；结果字段作兜底。approve 最外层取消处理覆盖切入/切出 IO dispatcher 的竞态。未派发取消持久写 ToolInvocation `CANCELLED/CANCELLED_BEFORE_DISPATCH`，供 Chat 排除“仅审批即 UNKNOWN”的误判；已派发取消保持 UNKNOWN，且 NonCancellable 写入不被取消打断。

### 新增真实设备测试（仅实现）

| 场景 | 测试方法与限制 |
| --- | --- |
| os.posix_spawn | 原受限能力测试新增 `/system/bin/sh -c 'exit 0'`；没有外部副作用，必须因明确权限拒绝或 API 不存在失败，不接受普通 OSError 冒充安全通过 |
| raw FD 伪造 Broker/结果 | 新增 rawDescriptorsCannotInjectBrokerRequestsOrForgeSuccessfulResults；每种帧使用新 worker，宿主用真实 PythonIpcProtocol 编码结构合法请求/结果帧，携带本测试自己的合成 ticket 与随机猜测 channelNonce（真实 native nonce 不暴露）。脚本先经 SDK ready，再尝试 os.write、posix.write、writev、open(int)、io.FileIO、os.fdopen，包含已知有效 FD1 和 3..63。当前 nonce 实现必须 UNKNOWN/invalid_nonce、无正文、无伪造 capability；未来彻底移除 raw API 的实现可返回真实入口结果，但必须证明所有有效 FD1 尝试被明确拒绝且零成功写入。超时/日志超限不计作认证通过 |
| stdout 日志上限 | 新增 logOverflowStopsTheRealWorkerAndDoesNotBecomeSuccess；真实脚本 ready 后 print 超额并循环，要求超日志 UNKNOWN/log_limit 先于时间 watchdog 终止、进程消失、下一次新 PID 成功 |
| 真实服务死亡 | 新增 realWorkerDeathAfterReadyIsUnknownAndNextInvocationIsFresh；真实脚本经 SDK ready 后只终止自身 isolated 进程，要求结果 UNKNOWN、无正文且下次新进程成功。未替换 runtime、Binder 或 JNI，也不杀宿主/其他进程 |
| 授权完成前取消 | 新增 cancellationBeforeAuthorizationCompletesNeverDispatches；真实 runtime 的内存授权端点尚未返回时取消，onDispatched 必须为零次且没有 Broker invoke；随后真实隔离调用成功。原 ready 后取消用例对应要求 onDispatched 恰好一次 |

本轮测试仍用自造内存 STORED Skill ZIP 和随机合成 ticket，无真实 DB、密钥、网络或收费调用。进程死亡用脚本自退出，覆盖已执行但结果缺失；它不等于已经复现每个结果正文 byte 偏移上的截断。raw FD 扫描是黑盒攻击 probe，首次命中私有端点后可立即结束进程，不能据此声称已枚举每个 FD。原始 FD 反例即使在未修复实现上可能阻塞，也有外层有限超时，超时不能视作通过。该用例 JUnit 上限 60 秒，其余仍有独立时间上限。

### 本轮实际检查

- 抽取新增后的 10 份 Python 源码：只运行 ast.parse、单参数入口断言、内存 STORED ZIP CRC 与回读，10/10；声明的 instrumentation 测试数量为 11，SPDX/尾空格检查通过。未执行任何 fixture、shell、网络或子进程攻击代码。
- App 14/14 源码不变量检查通过：类型化/持久/粘性 UNKNOWN、未派发取消标记、START observer、Broker 发送边界、单 grant 与共享预算等；这是源码文本断言，不是 Kotlin 编译或行为测试。三个分配文件的 git no-index whitespace 检查未报告空白错误（Kotlin 文件仅提示 LF→CRLF）。
- 仍未执行 Kotlin 编译、Gradle、模拟器/设备、instrumentation 或 commit/push；测试实现不构成 S03–S07 设备通过。主流程统一运行配置的 runner 与 connectedDebugAndroidTest。

## 2026-08-29：日志上限与立即结果竞态反例

- 新增 logOverflowCannotLoseToAnImmediatelyReturnedValidResult：真实 Skill 向 stdout 一次打印 2048 个 ASCII x 并 flush=True，随后立即返回 schema 合法 JSON。单次 maxLogBytes=1024；测试要求最终严格为 UNKNOWN_OUTCOME/log_limit、dispatchAccepted=true 且无 valueJson，禁止完整结果先到而伪装 SUCCEEDED。
- 测试紧接着发起真实 identity Skill；要求成功、PID 不同，并以 signal zero/ESRCH 有界确认两个测试子进程消失。没有等待或重用旧全局来掩盖 fresh-process 边界。
- 本节仅是测试实现。本轮抽取 11 份 fixture 源码，AST、单参数 run、内存 STORED ZIP/CRC/原文回读为 11/11；声明的 instrumentation 测试为 12 项，竞态断言与尾空格检查通过。未运行 Gradle、ADB、模拟器或设备。
