<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Skills 执行与安全模型

状态：M5 工具协议和 M6 官方 CPython 3.14.7 隔离执行已经进入 debug 人工终审基线；工具协议含 assistant.tool_calls、live grant、read_document KB 校验、HTTPS/IP 字面值拒绝、完整 EOCD/central/local ZIP 结构校验、canonical duplicate/symlink class E、预算取消上游。2026-08-30 追加无清单 Claude Skill 的标准库 CLI 兼容层和逐次批准的应用私有文本工作区，但仍不构成正式 release。v2 进一步定义 provider-neutral typed tools、SAF workspace、Shizuku/Wired ADB 双 Authority 与 Dangerous Mode；这些能力按 `IMPLEMENTED`、`AUTOMATED TESTED`、`E2E BLOCKED` 分别报告。对应R09—R12、R20—R24、S01—S22。**知识是数据，Prompt是指令，Skill脚本是可执行代码，三者不共享信任等级。**

## 1. Skill包与兼容性

保留现有以 `SKILL.md` 为入口的目录；补充 `mobile-skill.json` 执行清单、脚本、资源、vendor、依赖锁、哈希和可选签名。缺清单时分析生成本地安装清单，不能改原始包或默认授权危险能力。

清单示例仅表达v1契约；这是本项目自有测试Skill，外部Skill按其原许可记录：

```json
{
  "schemaVersion": 1,
  "id": "dev.example.knowledge_helper",
  "name": "Knowledge helper",
  "version": "1.0.0",
  "license": "AGPL-3.0-only",
  "runtime": {
    "kind": "python",
    "python": "3.14",
    "entrypoint": "scripts.main:run",
    "mode": "pure-python"
  },
  "permissions": {
    "knowledge.search": {"scope": "selected-by-user"},
    "network.http": {"hosts": ["api.example.com"], "methods": ["GET"]},
    "storage.skill": {"quotaMiB": 32}
  },
  "limits": {"timeoutSeconds": 30, "maxOutputKiB": 1024, "maxLogKiB": 512}
}
```

清单还须关联可验证的输入/输出JSON Schema、作者/来源、依赖锁和包内容哈希。入口只允许包内模块与明确函数，拒绝绝对路径、遍历和任意表达式。描述只供模型理解，不能取代schema校验。

| 等级 | 处理 |
| --- | --- |
| A Instruction-only | 支持读取SKILL.md指令；没有可由本机隔离运行时安全承接的程序 |
| B Pure Python Compatible | 显式清单程序，或本机兼容分析确认的无清单标准库 CLI；启用、授权、Agent 绑定且逐次批准后可执行 |
| C Unsupported dependencies | 保留说明，禁用脚本并列依赖原因 |
| D Platform-specific | Skill 内的 Shell/Node/Docker/桌面自动化等不能由 Python 隔离 worker 执行；保留说明。v2 的 Android `shell_exec` 是独立 Dangerous Mode control-plane，不是 Skill runtime 能力 |
| E Dangerous/Invalid | 路径穿越、压缩炸弹、原生载荷、哈希不符等拒绝安装；不得回退成可信指令 |

A/C/D的指令同样是不可信导入内容，启用前用户确认，不能让其中的安装命令自动执行。兼容分析是提示，不是安全证明。签名有效只证明来源/完整性，不自动产生信任或权限。

### 1.1 无清单 Claude Skill 的 CLI 兼容层

用户主动选择的 ZIP 若含 `SKILL.md` 及 Python CLI，但没有 `mobile-skill.json`，检查器只把同时满足以下条件的程序列入本机兼容清单：源码不超过 256 KiB、UTF-8、存在 `main()` 和 `__main__` 入口、import 仅属于受支持标准库子集，且静态检查未发现动态导入/动态代码、进程、网络或 native 扩展入口。其余脚本保留在源码预览和原因列表，不进入模型工具清单。该判断是 fail-closed 兼容门槛，不是对第三方源码的信任背书。

本地兼容清单由原包 SHA-256、Skill 名称和允许程序路径确定；原 ZIP 字节不修改。模型只能从 schema 的 `program` 枚举选择程序，并提供有界 `arguments` 与本次 invocation 专用的 UTF-8 Markdown 虚拟文件。Host 在执行前再次验证原包 hash、启用状态、live grant、Agent/快照绑定及清单一致性，再把对应的已验证源码放入模型不可声明的内部字段；超出 1 MiB 输入预算时在 dispatch 前拒绝。隔离 worker 用替代 `pathlib.Path` 只实现虚拟语料所需的只读 `rglob/read_text/name/stem/parent`，不映射 SAF 路径、Android 路径或宿主目录。每次调用仍要求用户确认，worker 仍为一次性 isolated UID。

`lieflat-less-ai-tone` 的 `compare-human-ai.py`、`check-translationese.py` 和 `check-structure.py` 属于上述标准库 CLI 形状，可通过同一个包绑定的 `py_*` 工具按原 `argv` 语义运行。`josephine-mccarthy-perspective/knowledge_base/books_kb.py` 依赖 PyMuPDF、RapidOCR、NumPy、PyTorch 和 Transformers，不能在该隔离 stdlib 中直接运行；Android 端必须明确改用已经绑定知识库的原生 PDF 解析、ONNX embedding、`knowledge_search`/`read_document`，且不得向用户或模型宣称桌面脚本已执行。

## 2. 安装流程和持久数据

隔离暂存 → 限额解包和文件头检查 → Manifest/schema/hash验证 → 依赖/import静态分析 → 来源/许可/签名/权限/源码预览 → 用户逐资源批准 → 原子启用版本。版本切换保留回滚信息，权限新增必须重新批准。

记录 `SkillPackage(id, version, packageHash, manifestHash, compatibility, source, license, signatureStatus)`、`SkillInstall(installId, packageHash, enabled)`、`PermissionGrant(grantId, installId, packageHash, resourceScope, capability, expiry, revision, revokedAt)`、`Invocation(invocationId, runId, packageHash, grantRevision, state)`。grant绑定包哈希，不能通过同名更新继承扩大的权限。

Agent 资源绑定的稳定标识是 `SkillInstall.installId`，不是清单中的 package id。保存 Agent 时必须按 `skill_installs.install_id` 且 `enabled=1` 解析；instruction-only 的 Class A 包同样需要先由用户启用。一个已绑定后被禁用的 Skill 可以在编辑界面被取消绑定，但不能再次勾选或保存为有效绑定；否则显示明确的“缺失或已禁用”状态。

默认上限作为实施初值：压缩包50 MiB、展开200 MiB、5000文件、膨胀比100；拒绝符号链接、硬链接、绝对路径、大小写冲突和规范化后重复路径。大小和内容检查独立于扩展名；不要只扫描`.so`字串。超限允许用户取消或在受控上限内调整，不能偷偷全部展开。

源码查看器是一级入口，支持SKILL.md、Python和锁文件，以纯文本展示，不执行HTML或代码。卸载要说明持久数据是否保留，不删除其他Skill或共享KB。

## 3. CPython隔离执行

宿主包含Agent Runtime、秘密、数据库、网络与Capability Broker。Python service必须声明`android:isolatedProcess="true"`和非外部导出；每次调用创建全新worker实例，调用后销毁。**普通 `android:process=":python"` 仍可能共享应用UID，不能作为等价替代。**

首选官方CPython Android 3.14.x嵌入包，经测试固定补丁/哈希，arm64-v8a与x86_64均验证。仅允许应用构建时携带的CPython/JNI库；“禁止.so”指用户Skill/动态依赖载荷，不是禁止应用自己的受审查原生运行时。

技术验证必须证明：isolated UID下能加载APK内受审查解释器与stdlib；无法读宿主私有文件；只读FD足以访问包；IPC在支持的最低Android版本可工作；取消/超时真的销毁进程；下一次调用无状态污染。无法在隔离身份加载时记录阻塞，不退回主进程。

`stopSelf()`或解除绑定不单独构成“进程已经死亡”的证据，必须证明进程生命周期结束、通道关闭和下一调用状态全新；如果平台不能满足，则该实现不能通过S03。API26使用Java Binder/ParcelFileDescriptor；不能直接采用要求API29+的NDK Binder FD接口并仍宣称支持API26。

执行流程：

1. 宿主重新验证包hash、启用状态和当前授权，创建不可复用的invocationId与一次性IPC凭证。
2. 启动独立service实例，绑定其进程/UID/Binder生命周期；不允许复用前次有状态worker。
3. 传只读包FD与有限输入；显式清单包由CPython以zipimport或等价只读加载机制读取纯Python代码。无清单兼容程序的源码由Host从已复核包中按允许路径提取，并作为模型无法声明的有界内部输入传给固定兼容入口；不能把隔离 UID 无法重新打开 `/proc/self/fd` 的平台行为当作宿主文件访问回退理由。
4. SDK只能通过窄IPC提出结构化能力请求；Host执行身份/资源/参数/预算复核。
5. 输出走有界通道；完成、取消、超时或Binder死亡都关闭FD/通道、吊销凭证和销毁worker。

不能把1 MiB输出直接当作单个Binder消息传送；控制帧建议≤64 KiB，大内容通过受控只读FD/流并累计限额。Binder线程不能阻塞等待模型/网络；并发、背压和宿主断开时终止规则要测试。

在同一时刻首版可串行只运行一个Python调用，避免生命周期与资源竞争；若扩展并发，必须真正独立实例/UID隔离并重新测试。不承诺绝对不可逃逸，系统漏洞和资源限制差异属于剩余风险。

## 4. Broker权限与SDK

每次请求的有效权限 = 包声明 ∩ 用户当前grant ∩ Agent绑定范围 ∩ 系统安全策略 ∩ 本次调用预算。模型、Prompt、知识库、工具输出、签名或远程公告不能扩大这个交集。

| SDK能力 | 宿主验证 |
| --- | --- |
| ctx.knowledge.search | 明确KB ID集合、query/topK/预算；不可枚举未授权库 |
| ctx.document.read | 该document属于授权KB；限定页/片段/字节数 |
| ctx.http.request | host/method/path约束、重定向、DNS/IP、超时和响应体限额 |
| ctx.model.invoke | 单独授权profile、次数、token/费用上限；秘密不返回Skill |
| ctx.storage.get/put | Skill自身命名空间、32 MiB默认配额、原子写 |
| ctx.files.read_handle | 用户选择的短期只读句柄，不返回全局路径 |
| ctx.files.write_artifact | 本次sandbox artifact；导出到用户目录再次确认 |
| ctx.log.info | 结构化、有界、统一脱敏，不含秘密或默认完整输入 |

网络默认HTTPS精确host白名单，拒绝IP直连、回环/link-local/内网目标，逐跳重新校验重定向及解析地址；防止凭据跨host转发和DNS重绑定。通配符若支持须限制完整域边界，不用简单suffix匹配。用户自建Provider/MCP的内网端点是独立、显式授权范围，不继承为所有Skill网络权限。

HTTP写操作和文件导出等副作用必须向用户展示目的地与影响；不能因为模型说“用户已批准”而跳过。所有能力有超时和取消传播，调用完成后凭证不可重放。

### 4.1 Agent 应用私有文本工作区

当前应用私有工作区已有结构化实现和设备自动化证据；历史兼容 wire name 为 `workspace_list`、`workspace_read`、`workspace_write`、`workspace_create_directory`（以及受限删除），v2 Agent-facing schema 统一使用 `workspace_list`、`file_list`、`file_stat`、`file_read_text`、`file_write_text`、`file_create_directory`、`file_move`、`file_delete`。命名空间由 Agent ID 与冻结快照 ID 的 SHA-256 派生，真实目录位于应用私有存储；模型只看到相对路径。读取和列目录也会把内容交给远程模型，因此与写入、建目录一样逐次显示确认，批准时再次检查 Agent 与快照仍然存在，call ID 只能使用一次。

路径拒绝绝对路径、反斜杠、冒号、NUL、`.`/`..`、过深层级、符号链接和 canonical 越界。文本必须是 UTF-8；单文件、单次读取、文件数、目录项、总字节与工具输出都有硬上限。替换写先写同目录临时文件、刷新后原子移动；删除（如 backend 支持）只允许单文件或空目录，不提供改权限、可执行位、原始文件描述符或 Android 真实路径。该工作区让模型可以在用户批准后创建和修改应用内部文本成果，也可以保存隔离 Skill 的结果，但不会把目录直接挂载进 Python worker，Skill 与工作区之间仍经过独立工具批准。

下列能力不是该工具的别名；是否实现必须按独立权限域验收：

- SAF：只在用户通过系统选择器选择具体文件或目录后，使用可撤销的 URI grant；不能把 URI 变成全局路径或默认授权整棵共享存储。SAF 是 workspace backend，不是 elevated Authority。
- Shizuku 与有线 ADB：是两个平级、显式选择的 elevated Authority；selected provider 失效时 fail-closed，绝不自动 fallback。
- Termux、无线 ADB、Device Owner/Profile Owner、Root：均不属于 v2 当前路线；不得以“外部 CLI”“ADB host”或“设备管理器权限”文案暗示已经接线。
- PowerShell 是 Windows 宿主能力，不是 Android 能力。Windows Companion 只允许官方 adb、USB、loopback、`adb reverse` 和固定协议；不把 `ProcessBuilder`、`Runtime.exec`、PowerShell 或宿主文件系统暴露给 Agent。

### 4.2 v2 wire/capability 与执行分层

完整 wire name → capability → backend-neutral 映射见 [验收矩阵 §3.1](ACCEPTANCE.md)。Skill、Prompt、模型输出和 server description 都不能声明 Authority 或扩大 capability；最终权限是包声明 ∩ 用户 grant ∩ Agent/Skill snapshot ∩ selected Authority ∩ 当前策略 ∩ 本次预算。

`shell_exec` 只在 Dangerous Mode、当前 Agent 允许 dangerous shell、已配置 selected `SHIZUKU`/`WIRED_ADB` 且该 Authority 允许 `shell.execute` 时注册。它执行 Android 端一次性 `/system/bin/sh`，支持 timeout/cancel/output limit，禁止 PTY 和自动重放；不改变 Skill/Python 的 isolated worker 边界，也不提供宿主 shell。Typed file tools 的路径和 workspace confinement 不得被描述为 shell 的安全沙箱。

Dangerous Mode 持久保存至用户显式关闭，至少区分 `ENABLED_CONFIRM_HIGH_RISK` 与 `ENABLED_AUTONOMOUS`；确认检测器只能决定是否需要单次确认，不能改写命令或伪装 allowlist。Binder/USB 暂时断连不自动撤销 grant 或模式，但在 revalidation 前不派发；恢复后仍使用原 selected Authority，不切换到另一 Authority。

### 4.3 Debug 安全边界与状态

Debug APK 仅用于开发和自动化测试；它不是 control-plane security evidence。危险模式、shell 注册/审批、Authority 绑定与 secret 边界的安全结论必须来自 `debuggable=false` 的 review-like build，并由独立审阅记录。缺少真实 Shizuku 服务或 USB Companion 时，静态/单元/仪器证据可记 `IMPLEMENTED` 或 `AUTOMATED TESTED`，但设备链路必须记 `E2E BLOCKED`。

## 5. 依赖和资源限额

首版 Skill/Python runtime 只支持允许的stdlib子集、包内vendored纯Python和 App 预审依赖。**不支持运行时pip、自动下载依赖、任意subprocess、原生wheel、DEX/JAR/SO、Skill 内 Shell 或可执行文件。** v2 的 Android `shell_exec` 仅由独立 control-plane 在 Dangerous Mode 下提供，不得被实现成 Python/Skill 的逃逸入口。直接socket/文件访问必须在真实系统边界被拒绝，import过滤只是减少攻击面。

| 限额 | 默认 | 强制位置 |
| --- | --- | --- |
| Python调用时间 | 30秒 | Host watchdog，终止worker，不依赖脚本配合 |
| 结构化输出 | 1 MiB | 宿主流计数和schema校验 |
| 日志 | 512 KiB | 宿主丢弃/终止规则并记录截断 |
| Skill持久存储 | 32 MiB | Broker配额和原子写 |
| 单次网络响应 | 8 MiB | Broker流式读取限额 |
| 工具子调用 | 20次/调用 | Broker，与Run总预算取更小值 |
| 模型子调用 | 3次/调用 | Broker，还受Run总Token/费用预算限制 |

CPU、FD、文件大小、地址空间等native限额在平台支持范围内设置并测量，内存限制不能仅凭声明声称硬保证。必须测试无限循环、内存/输出/文件洪泛和线程残留，确保宿主仍活着、下次调用正常。模型/HTTP超时的外部结果可能不确定，不盲目重试收费/副作用。

## 6. 本地秘密、日志和导出

Keystore生成不可导出的加密key，Provider/Skill秘密作为密文保存；不得在SQLite/DataStore/Preferences保存明文secret。硬件保护依设备能力，不保证所有API26设备同样安全。

日志、预览、错误、导出、崩溃信息统一脱敏。Request Inspector为用户本地按需查看，默认不持久化正文；“输入摘要”不能成为明文全文副本。生产日志默认不含聊天、Prompt、文件名、向量文本或Skill输入输出。

应用内诊断日志同样默认关闭，只能由用户在设置页主动开启。实现使用固定事件/字段白名单，只记录会话、进程、`main`/`worker`/`other`线程类别、UTC、等级、构建 revision/dirty/schema/build time，以及匿名的能力开关、保存结果、知识导入/Skill检查安装/批次worker阶段、Authority/approval/revalidation/dispatch/execution/terminal 阶段与计数；不得记录 Provider/模型/Base URL/API Key/Header、聊天/Prompt、知识或Skill文件名/真实路径、命令/argv/cwd、URI/ADB serial、请求正文、stdout/stderr 或异常消息。所有字符串再次经过 secret、URL/query、Windows/Unix 路径、控制字符和长度清洗。当前段与上一段各至多256 KiB，最近崩溃摘要至多32 KiB，单事件至多4 KiB，导出ZIP至多640 KiB；未捕获异常只保留异常类型和有界类/方法/行号，写入后必须委托Android原始崩溃处理器。诊断初始化、轮转和handler安装均为best-effort，失败不能阻止App启动。

诊断包经 Storage Access Framework 写到用户选择的位置，manifest包含构建和设备fingerprint以绑定复现环境；导出失败不得清除原始日志，清除只删除应用自有诊断文件。应用不申请`READ_LOGS`，不能捕获native崩溃、内核/系统强杀或被杀前未落盘的Android系统日志，这些场景仍需用户提供相同APK SHA对应的ADB Logcat。完整操作和字段契约见[诊断日志](DIAGNOSTICS.md)。

备份策略排除秘密和设备绑定key相关状态，恢复后要求重新输入密钥；不要把自动备份当作跨设备秘密迁移。默认导出Agent/Prompt/模型非秘密参数、KB元数据和Skill配置，不含Key/Header/Cookie/敏感附件。用户显式选择完整KB/对话/Skill包导出时展示体积和隐私范围，保留原权利信息并重新导入验证；未选的blob和源码不得夹带。

schema未知、包hash错误或无权限的导入必须拒绝；迁移失败不清库。秘密丢失时返回明确配置错误，不自动调用其他key。

## 7. MCP与Remote扩展

M7实现MCP Adapter；首版优先用户显式配置的远程HTTP传输，具体协议版本/transport在该阶段查官方规范并锁定。不在Android自动启动任意stdio/Node/Shell服务器。

适配器执行能力发现、工具schema校验、名称去冲突、调用/取消/错误映射，所有外部调用仍经权限与预算系统。server工具描述是不可信指令；工具列表变更或新增权限需要重新确认。凭据仍通过SecretStore注入；重连不能自动重放副作用。用受控测试server验证，不调用用户未知服务。

`RemoteSkillExecutor`保留版本化请求/结果/取消/能力声明接口，用户自托管实现另行授权。不提供默认公共执行服务器，不自动把C/D级Skill上传远程，不上传整个知识库或secret。

M7必须固化以下最小DTO契约；这是本项目executor端口，不冒充MCP官方wire schema，不在此阶段隐含建设远程服务器：

| 帧/方法 | 必需字段与类型 | 校验/语义 |
| --- | --- | --- |
| capabilities() → RemoteCapabilities | protocolVersion正整数、executorId字符串、runtimes字符串数组、capabilities字符串数组、maxInputBytes/maxOutputBytes/maxTimeoutMs正整数、supportsCancel布尔 | 未知主版本拒绝；只接受用户配置的executor；远程声明不是本地授权 |
| invoke(RemoteInvocationRequest) | protocolVersion、invocationId/runId/grantId字符串、executorId、skillId/skillVersion、packageSha256小写64位hex、inputSchemaVersion正整数、arguments对象、approvedCapabilities数组、deadlineAt UTC、limits对象 | 包必须已在该executor合法安装且hash匹配；grant绑定当前包/endpoint/调用，参数经schema校验；不随请求自动上传包 |
| RemoteInvocationResult | protocolVersion、invocationId、status枚举、result对象或artifactDescriptors数组、error对象或null、startedAt/finishedAt、usage对象 | status为SUCCEEDED/FAILED/CANCELLED/TIMED_OUT/UNKNOWN_OUTCOME；输入/输出必须有界，结果不可信 |
| cancel(RemoteCancelRequest) | protocolVersion、invocationId、cancelRequestId、reason枚举 | 幂等取消请求；只针对相同executor的当前调用，不把取消发送成功当作已停止 |
| RemoteCancelAck | protocolVersion、invocationId、cancelRequestId、accepted布尔、terminalStatus可空 | 未收到确认或执行器状态不明时记UNKNOWN_OUTCOME，不自动重发invoke |

`limits`必含timeoutMs、maxOutputBytes、maxToolCalls、maxModelCalls、maxModelTokens；与本地Run剩余预算取交集。`usage`至少报告实际时长/输出字节/工具与模型调用数，远程报告仅作审计，不代替本地限额。`error`沿用主方案code/userMessage/retryClass/sanitizedDetails。artifact仅为受控描述符（id、mediaType、size、sha256），获取另走已授权端点，不任意跟随返回URL。

executor认证由SecretStore在传输适配器注入，不放DTO。grantId本身不是可对外使用的权限令牌；本地能力调用仍须Broker逐次验证，不因远端宣称支持某capability就开放手机资源。相同invocationId不能重复执行：适配器记录已发送状态，executor需声明/实现去重和结果查询；无法确定结果时返回UNKNOWN_OUTCOME，只有用户知悉风险后用新的调用ID重新发起。传输路由、重连认证和结果查询细节由M7锁定协议并扩展schema，不能用自动重试掩盖缺失。

## 8. 发行边界

App外部导入Python仍涉及动态代码和平台政策约束。首版仅用户主动选择本地Python源码、不自动下载更新Skill、不加载外部native/DEX/JAR。发布前按当时Android/Google Play政策独立审查，不能从解释器存在推导“保证上架”。公告只能导航至更新页，不能充当代码分发渠道。

独立安全审阅必须覆盖安装器、Binder身份、撤权、网络过滤、秘密、资源终止和下一次调用恢复。测试通过只针对已测版本与场景，不宣传绝对防逃逸。

## 9. M5 本地验证（2026-08-28）

本轮：`SkillArchive`/`SkillInstaller` 对 zip-slip、ELF/DEX/JAR/SO、pip 远程依赖、未知 schema、哈希不符判 E 并拒绝安装；缺清单的 SKILL.md 为 A；pure-python 清单为 B；shell/node 为 D 可存指令不可执行。内置 `knowledge_search`/`read_document`/`calculator`/`http_request`：不完整 JSON 不执行、重复 call id 不双执行、HTTP 需确认、loopback 拒绝、Prompt 不能扩大 grant。`AgentRuntime` Tool Loop 计入轮次与工具预算；工具输出走 SecretRedactor。未嵌入 CPython，未跑 S03—S07 隔离。

| 验收 | 本轮状态 | 证据边界 |
| --- | --- | --- |
| S01 | LOCAL_PASS | 无清单 A、未知 schema E、哈希不符 E、源码/许可可见、不自动执行 |
| S02 | LOCAL_PASS | zip-slip/ELF/pip 拒绝且不写入 skill_installs |
| S08 | LOCAL_PASS | 四件内置工具 schema、碎片 JSON、重复 id、HTTP 确认 |
| S09 | LOCAL_PASS | 无 tools 能力不带工具；越权 HTTP Denied；预算耗尽 |
| S10 | LOCAL_PASS（工具输出） | 工具结果脱敏。未跑完整导出矩阵 |

## 10. M5R01—M5R11 本地修复（2026-08-28）

- Tool loop 第二轮保留 assistant.tool_calls 再跟 tool 结果；Adapter 按结构化 JSON 编码。HTTP NeedsApproval 在 Chat 暂停并提供批准/拒绝后续跑。
- Provider 可标记 tools 能力。Chat 不再全局合并全部 KB；`knowledge_search` 只使用当前 grant 的 knowledgeBaseIds。
- 安装保存完整 manifest JSON 与原包 bytes；同 hash 重导入不叠加 grant。限额/炸弹/远程依赖（大小写不敏感）一律 class E。
- HTTP 仅 HTTPS、allow-list、禁 loopback/私网；`file://` 与 `http://` 在回调前拒绝。工具输出与 read_document 有上限。
- 成功/失败/拒绝工具输出都用当前 Provider secret 脱敏。SSE 按 tool index 映射 id。总时限在模型轮结束和每个工具前重查。`toolsEnabled=false` 收到 tool call 不执行。

## 11. M5RR01—M5RR05 本地修复（2026-08-28）

- `read_document` 校验 document.kb_id 与当前 grant；空授权拒绝。HTTP method 与 grant.methods 求交集，通用批准不扩大范围。
- ToolBroker 每次 invoke/approve 重新读取 live grant；撤销后同一 Broker 的新 callId 与待批准恢复均拒绝。
- HTTP 拒绝 IP 字面值（含 IPv6 私网、整数 IPv4）；解析结果逐跳核验，私网/loopback/link-local 不得连接。
- Runtime 以剩余预算 `withTimeout` 取消模型流、审批等待与工具；上游 delay 不再把超时事件全部消费完。
- 截断 ZIP、无 EOCD、Unix symlink 属性一律 class E，不再把 PK 前缀空包当成 instruction-only A。

## 12. Skill ZIP 与运行预算收口（2026-08-29）

- Skill ZIP 在读取或暴露 manifest/source 前校验单磁盘 EOCD、central directory 边界/数量、每个 local header，以及名称、flags、method、CRC 与大小一致性；fake EOCD、无 central directory、central/local 分叉均归 Class E。
- 路径统一分隔符、去除 `.`、折叠可解析层级、NFC 归一化并按 `Locale.ROOT` 小写后检查重复；大小写或 canonical duplicate 不再允许两个 manifest/source 产生审批与执行分叉。
- bit3 data descriptor 的实际 CRC、压缩/解压大小必须与 central entry 一致；目录项也在跳过业务内容前执行同样的完整性、单项大小、压缩比和聚合总量检查，目录名不能成为资源限制旁路。
- Agent Runtime 对 `beforeModelRequest` 使用自身剩余预算的 `withTimeoutOrNull`：自身 deadline 到期记录 `BUDGET_EXHAUSTED` 且不启动模型；调用方取消仍沿取消路径处理，不伪装成预算耗尽。
- 新回归覆盖 fake EOCD、大小写/规范化重复路径、central/local 名称不一致和虚拟时间下的 pre-request budget timeout。

## 13. 应用私有工作区（2026-08-30）

- Chat 的冻结 Agent snapshot 注入四个结构化工作区工具；Provider 未声明 `tools` 时仍不会发送工具 schema。
- API 31 x86_64 `WorkspaceAppToolsTest` 6/6 通过：拒绝绝对路径/遍历、逐次批准和 call ID 防重放、UTF-8 原子替换且无临时文件残留、大小上限和 symlink 拒绝、撤销 Agent/快照后 fail-closed、读取/列目录也需要批准。
- 设备测试只证明应用私有工作区路径。本轮没有执行 SAF、Termux、无线 ADB、DPC、root/Shizuku 或任意 shell 验收，不得以 S13 通过替代这些后续能力的独立设计和用户授权。
