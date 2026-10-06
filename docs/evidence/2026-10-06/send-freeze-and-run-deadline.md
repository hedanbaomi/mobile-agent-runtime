<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# v1.1.0 发送后输入法卡顿与“已取消接收”修复

2026-10-06。R18/R34，U02、C22/C23，[ADR-0026](../../adr/0026-run-deadline-admission-and-stall.md)。用户提供 v1.1.0（d4d2f27，realme RMX3888，Android 16）录屏与两份诊断包；原始视频、诊断包和设备信息只留本地，不进入仓库。

## 现象与已确认原因

- 发送卡顿：录屏中点击发送后输入框清空、键盘退为备用布局并冻结约 3.6 秒，列表不刷新。两次 Run 的诊断均显示 `conversation_workspace_resolution`（worker）到 `runtime_tool_exposure`（main）之间分别空档 3.6 秒和 4.6 秒，随后约 90 毫秒即发出模型请求。`ChatViewModel` 的 `runJob` 在主线程构建 `RunTools`：`UnifiedWorkspaceToolExecutor.exposedSpecs` 与 `toolExposureDiagnostics` 对每个工作区和操作反复读取 backend descriptor/capabilities；当前 SAF 工作区的 `SafWorkspaceBackend.descriptor` 按设计不缓存，每次读取都执行持久授权查询、根文档查询和子项列举的跨进程 ContentResolver 调用。1.1.0 的输入修复只降低了打字重组，没有覆盖发送后的主线程工作。
- 已取消接收：Run 04:32:55.36 开始，04:35:55.485 变为 `UNKNOWN_OUTCOME`，与 180 秒总时限一致；最后一次请求 HTTP 200 且终止前仍持续收到推理增量。`AgentRuntime` 在截止时间截断已派发的模型流，按派发后未知结果处理并显示为“已取消接收”。不是用户取消、断网或 watchdog。

## 修复

- Run 准备中的 `RunTools` 构建、Agent/知识库/Skill 授权读取、联网搜索与 MCP executor 组装移至 IO 线程；状态投影和事件收集仍在原调度器。
- 截止时间只管准入，在途模型/摘要/工具（含 RunTools 与 Python 进程）不截断；主请求派发前复查截止；新增停滞超时，事件与传输分块都算进展，判 UNKNOWN 且不重放；审批等待和 Python broker 新派发仍受截止约束；完整回复先按自身终态结算，截止后收完的摘要保留为 SUCCEEDED。
- watchdog 以空闲时长判定（单调时钟，传输分块计为进展）；流式对话请求只解除 Ktor 总请求时长，连接和空闲超时保留。
- 时间预算结束的提示改为说明已收回复完整保留、未发起新请求、可继续提问。

## 验证

| 验证 | 结果 |
| --- | --- |
| shared agent-runtime | 72/72 通过；首批新增/改写的 5 项在修复前 AgentRuntime 上失败；独立审阅后补充传输分块进展、慢消费者、中途异常、截止后摘要 5 项 |
| shared provider-api | 275/275 通过，含新增流式超时 3 项 |
| Android 单元 | testDebugUnitTest 253/253 通过 |
| API34 模拟器（mar_api34_matrix） | `ChatSendOffMainThreadDeviceTest` 修复前主线程读取 backend 10 次而失败；`RunToolsReplayDeviceTest` 截止回归在旧 RunTools 上失败；`PythonModelInvokeBrokerTest` 跨截止 model.invoke 在旧 broker 上变为 UNKNOWN_OUTCOME；修复后聊天所有权、流式取消、IME、压缩、输入预算、审批重启、线程工作区、设备访问、审批 UI、Python broker/Skill、RunTools 共 70 项通过 |
| 编译 | assembleDebug、assembleDebugAndroidTest strict/offline 通过 |
| 独立只读审阅 | 首轮 REQUEST_CHANGES（RunTools/Python 仍按截止截断、长摘要可误触 watchdog、摘要被拒后缺准入复查），修复后复审 APPROVE；其余非阻塞建议中 broker 截止语义与 toolImages 超时已处理，仅保活流残余风险记录于 ADR |

一次 Python broker 设备测试因模拟器既有数据中存在孤立的 Agent 默认工作区行而在启动迁移校验失败；`pm clear` 后同组 22 项全部通过，本轮修改不写该表。真实 realme 输入帧耗时、真实 Provider 超过 3 分钟的长回复、物理 Shizuku/USB 未验收。

## 2026-10-06 合并前复核（Codex）

本节为本次重新执行的证据，上面的 Claude 记录保留为历史；没有把历史模拟器结果当成本轮执行结果。

复核发现两处准入检查之间的挂起窗口：`RequestPrepared` 事件消费者与摘要 `DISPATCHED` 检查点写入可耗时到截止之后，随后仍派发请求。现分别在实际派发前再次检查；摘要尚未接触 Provider 时持久化为 `FAILED`，不误报未知结果。新增 `deadlineDuringRequestPreparationStartsNoModelRequest` 和 `deadlineDuringSummaryCheckpointStartsNoSummaryRequest`，原修复上两项失败，补修后两项通过，均断言 Provider 请求数为零。

| 本轮检查 | 实际结果 |
| --- | --- |
| 受影响单元测试 | agent-runtime 74/74，provider-api 275/275，Android Debug 253/253；零失败、零跳过 |
| 完整本地门禁 | `licenseGuard licenseGuardReverse check verifyCiPins verifyDependencyLock verifyDependencyVerification verifyWorkflowYaml :app-android:assembleDebug :app-android:assembleDebugAndroidTest`，strict/offline，通过 |
| REUSE | CI 相同版本 6.2.0；891/891 文件具有许可证与版权信息，通过 |
| 本轮设备回归 | 独立新建 API34/x86_64 模拟器，四组共 25/25 通过；未清理用户的既有模拟器或真机数据 |
| 独立只读复查 | DSH 的 DeepSeek V4.1 Flash 核对任务仓库、HEAD、实际差异与补修两处准入检查，最终 `APPROVED`；该结论仅为源码/协议审查 |
| CodeGraph | 已执行同步，索引已是最新；Kotlin 部分符号只有导入信息，相关状态转换另用实际源码核对 |

受影响单元测试命令：

```sh
./gradlew.bat :shared:agent-runtime:test :shared:provider-api:test :app-android:testDebugUnitTest --offline --dependency-verification=strict --no-daemon --console=plain
```

设备测试使用项目声明的 `runtime.mobileagent.PythonRuntimeDeviceTestRunner`，运行 `ChatSendOffMainThreadDeviceTest`、`ChatRunOwnershipDeviceTest`、`RunToolsReplayDeviceTest` 与 `PythonModelInvokeBrokerTest`。初次指定标准 runner 时测试没有启动，改用项目 runner 后完成上述 25 项。

本轮无真实收费 Provider 请求；真实 realme 输入帧耗时、持续超过 3 分钟的真实 Provider 回复与物理 Shizuku/USB 仍未验收。SSE 进展、周期性停滞检测与工具图片准备的界限见 ADR-0026 补充说明。本地保留执行日志与红/绿回归证据；公开仓库不含原始诊断包、用户对话或签名材料。
