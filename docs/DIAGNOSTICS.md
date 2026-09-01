<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 应用内诊断日志

本功能用于给无法稳定复现的移动端问题绑定构建版本、匿名操作面包屑和最近一次 JVM 崩溃摘要。它不是遥测、聊天记录或完整 Logcat 替代品。

## 1. 用户操作

1. 打开“设置 → 隐私与调试”，主动开启“应用内诊断记录”。默认关闭，关闭时不写事件。
2. 复现问题；若应用崩溃，重新打开同一 APK。
3. 点击“导出诊断 ZIP”，在 Android 系统文件选择器中选择保存位置。
4. 保存成功后把 ZIP 与发生时间、操作步骤一并提供。确认文件已保存前不要点“清除诊断日志”。

“清除诊断日志”只删除本应用拥有的诊断文件，不修改聊天、Provider、知识库、Skill或公告数据。导出失败会保留原始现场，可重新选择目标再试。

## 2. 记录范围与上限

- 固定公共字段：schema、session、pid、thread类别、UTC时间、level、event、Git revision、dirty、数据库schema、构建时间。`thread` 只能是 `main`、`worker` 或 `other`，不保存任意线程名。
- 固定事件：诊断启停、Provider模型tools/image能力开关、Provider模型保存开始/成功/失败、知识导入开始/进度/入队/staged/失败、Skill检查/安装、批次worker开始/进度/完成/失败、未捕获异常，以及权限选择/状态、权限配置维度、Shizuku/有线ADB生命期、工作区授权/操作、Skill memory操作、危险模式决策、runtime工具暴露、shell暴露、tool approval、shell执行、bridge请求、runtime tooling 不可用和诊断丢弃摘要。知识导入的“staged”仅表示文件已复制并入队，只有worker到达真实终态才记录完成。
- 本轮权限与工具排障事件使用 `DEBUG` 级别，但仍只有用户主动开启“应用内诊断记录”后才落盘。`authority_configuration_state` 分开记录 user intent、selected、platform grant、availability、connection 与 configured；`dangerous_mode_decision` 记录构建是否允许及固定拒绝原因；`runtime_tool_exposure` 只记录注册、已授权、快照绑定和三者交集的工作区数量、Provider 实际可见的工具总数与所有者分桶、模型 tools transport 开关、selected Authority/ready 状态、SAF grant/backend 布尔值和固定原因。它们不写权限对象、workspace ID、文件夹、URI、路径、命令、参数或模型正文。
- v2 新事件采用强类型 record API 和闭合字段白名单；未知事件或字段整条拒绝。允许的值只来自固定枚举、布尔值、桶化限制、有限计数、异常类型、错误类别和终态。Provider名称、模型ID、Base URL、知识/Skill文件名与秘密不进入字段。
- `runtime_tooling_unavailable` 只接受 `TOOL_EXECUTION_CONTEXT_UNAVAILABLE` 或 `TOOL_EXECUTOR_FACTORY_UNAVAILABLE`，并可带 HMAC 化的 session/run 引用；`tool_approval_state` 的 capability、authority 为固定枚举，sessionRef 同样只写 HMAC 化引用。审批原因只保留闭合的安全码；正常发起和超时分别为 `approval_required`、`timeout`，拒绝/失效可使用 `approval_denied`、`snapshot_stale`、`invalid_request` 或 `call_id_replay`，不会退化成无法定位的异常文本。
- 模型或用户可控的 agent、skill、workspace、call、approval 引用只写固定长度（32 个十六进制字符）的 app-local HMAC 截断值；Runtime 随机 requestRef 也归一化为同样长度。当前实现使用稳定会话 HMAC，密钥只在进程内存中生成，绝不写普通文件；可用受保护持久密钥时由平台适配器替换。
- shell 事件只写 commandSha256、authority、桶化限制、stdout/stderr 字节计数、duration 桶和终态；不写 command、script、argv、preview、stdout/stderr、result 或 arguments。
- 当前日志256 KiB、上一段256 KiB、最近崩溃32 KiB、单事件4 KiB、导出ZIP640 KiB，超限按拥有文件滚动或拒绝单条事件。滚动和导出都只保留完整 NDJSON 行。
- 导出manifest补充设备fingerprint，便于区分系统镜像和构建环境。

## 3. 隐私与崩溃边界

字段白名单之后仍执行 `SecretRedactor` 与URL/query、Windows/Unix路径、换行/控制字符、长度清洗。不得保存聊天、System Prompt、模型参数正文、知识库文件名/内容、Skill输入输出、API Key/Header/Cookie、请求或响应正文、异常message。

第四轮新增的应用私有工作区不增加路径或文件内容诊断事件；相对路径、真实 Android 路径、读写正文和目录列表都不能进入诊断 ZIP。v2 事件同样禁止 filename、path、cwd、URI、ADB serial、adb path、host、IP、port、Binder 参数、token、session key、API secret、prompt、exception message 和任意自由文本。当前只为 SAF workspace、Shizuku 和有线 ADB Companion 定义事件；无线 ADB、Termux、DPC、Root 与 PTY 是排除项，不得以诊断事件暗示它们已接线。

诊断写入失败和被白名单拒绝的事件只增加内存中的 failure/drop 计数，并将健康状态标为 degraded；不会递归写入失败日志。`diagnostic_drop_summary` 只在显式调用时记录一份固定字段快照，摘要自身失败也不会再次产生日志。

未捕获JVM异常只保存异常类和有限stack frame，随后委托Android原始未捕获异常处理器，不能吞掉崩溃或改变系统终止语义。应用不申请`READ_LOGS`，所以native崩溃、系统/内核强杀、ANR全量线程信息和未落盘系统日志仍需相同APK SHA对应的ADB Logcat。日志开启也不保证覆盖进程被立即杀死前的最后一步。

## 4. v2 事件契约

每条记录都使用闭合的 `DiagnosticRecord`，公共字段为 `schema`、`sessionRef`、`runRef`、`requestRef`、`invocationRef`、`approvalRef`、UTC、level、event、revision、dirty、dbSchema、buildTime 和 `thread`。引用字段只能是 32 个十六进制字符的 app-local HMAC 截断值；`authority` 只能是 `NONE`、`SHIZUKU`、`WIRED_ADB`，不记录 Binder、serial、endpoint 或设备地址。

| 事件族 | 允许的语义字段 | 必须覆盖的阶段/终态 |
| --- | --- | --- |
| `authority_configuration_state` | authority、user intent、selected、grant/availability/connection、configured 的固定枚举或布尔值 | 初始快照、refresh、显式启用/选择、平台状态变化；六个维度必须独立，连接成功不能冒充用户意图或选择 |
| `dangerous_mode_decision` | requested/current mode、selected authority、buildKnown/buildAdmitted、固定 decision reason | enable、disable、构建拒绝、Authority拒绝；Debug 拒绝必须可诊断，不能降低构建门禁 |
| `runtime_tool_exposure` | HMAC session/run 引用、注册/授权/快照绑定/交集工作区计数、Provider 可见工具总数、固定 owner 计数、model tool transport、selected Authority/ready、SAF active/registered、固定 reason | exposed、模型 tools transport 未启用、无有效 Agent grant、无快照绑定、合法空工具集合、factory unavailable；模型能力关闭和合法空集合都不能误报 factory failure |
| `authority_state` | selected authority、grant/availability/connection 的固定枚举、revision、错误类别 | configured、permission、connected、ready、temporarily unavailable、revoked、recovered；不得把 grant 与当前连接混成一个布尔值 |
| `tool_exposure` / `tool_approval` | capability bucket、tool snapshot hash、approval result、reason enum | exposure、approval、revalidation、dispatch；审批必须绑定当前 Agent/快照/Authority/危险模式 revision |
| `workspace_operation` / `memory_operation` | backend enum、operation bucket、字节/条目计数桶、终态 | read/list/write/move/delete、cancel、timeout、denied、unknown；不记录相对路径或正文 |
| `shell_exposure` / `shell_execution` | `commandSha256`、authority、dangerous policy、timeout/output buckets、stdout/stderr 字节数桶 | registered、revalidation、dispatch、started、completed、failed、cancelled、timed out、truncated、disconnected、unknown；不记录 command、cwd、argv、preview、stdout/stderr 或 result |
| `bridge_request` | bridge phase、protocol version、transport enum、固定错误类别、计数桶 | doctor、pair、reverse、session、request、recovery、disconnected；不记录配对码、HMAC、serial、路径、host、IP、port |
| `diagnostic_drop_summary` | drop/failure 计数、degraded 标志、固定原因枚举 | 显式导出时最多一条摘要；写入失败不得递归写日志 |

阶段字段只允许 `exposure`、`approval`、`revalidation`、`dispatch`、`execution`、`terminal`；终态只允许成功、拒绝、失败、取消、超时、截断、断连、恢复后重连和 `UNKNOWN_OUTCOME` 等固定枚举。派发后无法确认结果的写或 shell 调用必须记录 `UNKNOWN_OUTCOME`，不得记录为失败后自动重放。

诊断事件只证明事件被记录或被丢弃，不证明操作的真实成功，更不证明设备 E2E。`IMPLEMENTED`、`AUTOMATED TESTED` 和 `E2E BLOCKED` 必须在验收报告中分栏；缺真实 Shizuku 服务、USB Companion 或 `debuggable=false` review-like build 时，不得将静态/自动化结果升格为设备安全 PASS。

## 5. 验证入口

仪器测试 `runtime.mobileagent.diagnostics.DiagnosticsDeviceTest` 覆盖默认关闭零字节、偏好持久化、启停事件、白名单/脱敏、v2 事件闭合与引用固定长度、整 ZIP secret/path/URI/command/stdout byte-search、并发 NDJSON、完整行轮转、滚动上限、损坏轮转目标、ZIP/manifest、异常message排除、原处理器委托、失败导出保留、IO 失败不崩和清除。运行时应分别报告编译、设备测试和 APK 人工验收，不以静态或 JVM 结果替代设备验收；对应证据见 `docs/evidence/2026-08-30/` 下实际命令记录，验收映射见 [A08](ACCEPTANCE.md)。

## 6. 当前证据状态

- `IMPLEMENTED`：固定事件/字段白名单、app-local HMAC 引用、诊断轮转/导出/清除及失败降级边界已在当前实现中存在。
- `AUTOMATED TESTED`：API 31 的 `DiagnosticsDeviceTest` 12/12 覆盖关闭零字节、白名单/脱敏、v2 typed 事件、权限配置/危险模式决策/runtime工具暴露的 DEBUG 事件、HMAC 引用、ZIP byte-search、并发 NDJSON、完整行轮转、上限、IO 失败与清除；`runtime_tool_exposure` 的新增聚合字段和 `model_tool_transport_disabled` 原因均有闭合 schema 断言。完整 connected matrix 为 235 tests 全通过，另有 1 个未显式启用的受控 Knowledge load skip。不把历史 64/192 KiB 限额沿用到 v2，当前规范值始终是 256/256/32/4/640 KiB。
- `DEVICE E2E PASS（API 31 x86_64 emulator）`：系统 DocumentsUI 的真实持久 SAF grant 已完成 AgentRuntime 模型 tool call、workspace list/read/write/delete；官方 Shizuku 13.6.0 通过实际 shell UserService 完成模型侧 workspace list/read/write/delete 与一次 `/system/bin/sh`。该结论证明本模拟器上的真实 Android provider/Binder 服务，不外推到物理设备。
- `LOCAL_PASS`：`debuggable=false` Review APK 的 security/SBOM/provenance gate 已通过并覆盖安装冷启动；Debug APK 与 Review APK 的 notices 均在实际 ZIP/APK 边界验证。该结论不等于物理 USB 或物理设备高权限操作成功。
- `E2E BLOCKED`：真实有线 USB Companion、物理断连恢复和非模拟器设备差异仍未执行；不得用 API 31 模拟器结果替代这些边界。

本轮真实 SAF/Shizuku 配置、首次无预热 bind、完整 235-test 设备批次、产物哈希和未验证边界见 [2026-09-01 工作区工具真实 E2E](evidence/2026-09-01/workspace-tool-real-e2e.md)。
