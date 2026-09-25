<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 验收矩阵与证据要求

2026-09-25 人工反馈专项验收：在 `debuggable=false` review 包和真实 Android 设备分别测量发送到 IME 收起时长；验证 assistant→tool→assistant 顺序在单气泡内保留、思考折叠无空白、审批时对话可透过遮罩且键盘抬起时按钮可达；验证开关默认关闭、仅新会话开启、关闭实时生效、撤销 grant/切 Authority/重复 call ID 仍拒绝；将 24 MiB 文件经 Internal 和 SAF 分块读到 EOF 并校验拼接与版本，SAF 云端慢流单列耗时；在后台、锁屏、网络切换、进程被系统回收、服务超时和手动强停下区分可继续与 UNKNOWN，不自动重放；以硅基流动 `Qwen/Qwen3.8-27B` 的官方目录核对 AUTO 上下文值和目标变化时的失效。JVM 测试、编译或静态审查不能代替这些设备与真实服务路径。

2026-09-25 追加权限回归：先授予普通默认工作区，再将危险模式切至“高危命令确认”，确认旧策略授权明确显示需重新授权且新会话不绑定无效默认；在 Agent 内重新确认“完整设备文件”，再新建会话，验证普通目录与完整设备文件的授权工具都可用且两个范围仍分离。分别测试危险模式关闭、Authority 断联/切换、撤销完整设备授权、旧版本 grant、新旧会话和已冻结 Run，确认完整设备工具不越权、不会隐式出现 shell。诊断仅记录授权计数及后端探测状态，不含真实路径或文件内容。

普通目录续期还需负向验证：用户曾撤销写入/删除能力、旧授权过期、Skill/path scoped grant、其他 Agent/工作区授权均不得被完整设备确认带回；原有 path scoped 和 ONCE/TASK/SESSION 授权行不得被续期撤销。如果完整设备确认已提交而默认目录续期失败，界面应明确显示部分成功，不能宣称完整设备也失败。

本文件定义**设计与实现的验证方法**，矩阵本身不代表已执行。各项实际状态和证据以 [HANDOFF.md](../HANDOFF.md) 及对应验证记录为准；M0.5 软件页面 UI 设计基线已完成交付并满足 U01—U06 设计审查要求（见 [docs/UI_DESIGN.md](UI_DESIGN.md) 与 [docs/design/ui-tokens.json](design/ui-tokens.json)，状态为 `DOC_CHECK_PASS`），实际业务实现与设备验证随各后续阶段展开。

## 1. 完成状态

`NOT_STARTED`尚未做；`IN_PROGRESS`已开始；`IMPLEMENTED`有实现未充分验证；`AUTOMATED TESTED`指定自动化测试通过；`LOCAL_PASS`指定本地测试通过；`DEVICE_PASS`指定真机通过；`E2E BLOCKED`实现/自动化证据存在但真实端到端所需硬件、服务或授权缺失；`REVIEWED`独立审阅通过；`DEPLOYED`已授权部署且有目标证据；`RELEASED`已授权发布且完成后检。

状态可以分别记录，不要求假装线性升级。`IMPLEMENTED`、`AUTOMATED TESTED` 与 `E2E BLOCKED` 必须分开填写；阻塞记原因和缺失条件，不能把未执行记为PASS。设计校验通过只记`DOC_CHECK_PASS`，不代表M0完成或产品可用；M0.5 还须有用户对页面与交互方案的确认记录。原型走查不能代替真实 Compose/管理端实现及设备验证。Debug APK、JVM 测试或静态检查不能替代 `debuggable=false` review-like build 的安全证据。

## 2. 文档和许可证

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| D01 | 从 AGENTS 入口按 agent.md 的任务类型读取；核验本轮受影响的相对链接、Markdown fence 和适用需求 ID | 项目修改读取交接现行摘要并在项目状态变化时维护；纯咨询/只读审查不强制写交接，需持续跟踪的问题单独登记；无本轮新增断链，不将计划目录冒称已实现 |
| D02 | Git根/分支/HEAD/remote；CodeGraph status；检查ignore | 根唯一、main无提交或如实记录SHA、无擅自remote；initialized=true；无源码时零索引说明，不将数据库提交 |
| L01 | licenseGuard正常正例；篡改临时第一方SPDX为MIT、删除Header、替换LICENSE | 正例通过，每个反向fixture本地和CI都失败；不修改真实许可证做破坏性测试 |
| L02 | 合法第三方MIT fixture、用户Skill许可、REUSE无注释文件规则 | 不误判为第一方；保留版权；reuse lint通过且无全仓覆盖归属 |
| L03 | 受保护测试分支/PR触发许可失败、CODEOWNER文件变化、Agent身份绕过尝试 | 在授权测试范围内证明不能把失败检查合入main；提交SHA/CI链接/Ruleset设置证据，不能只看YAML |
| L04 | APK/About、服务/admin源码入口、版本commit、对应源代码、第三方notice和SBOM | 产物与源码revision对应，AGPL完整文本可访问；无秘密或用户内容；不得伪称已发布 |

### 2.1 软件页面 UI 设计（M0.5）

U01—U06 的首次验收对象为设计包，结果仅覆盖设计；后续 M1—M7 实现页面时复用对应场景并分别提供真实实现/设备证据。每条证据注明设计修订、`screenId`、平台与未验证项。

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| U01 | 逐屏查看 Android 七类页面及关键子页面的高保真稿，核对导航、返回、首次引导、详情/编辑及离开未保存页面 | 各页有实际布局/控件/文案设计及稳定 `screenId`；主次操作和入口/出口清楚；页面稿、导航及工程映射一致，不能只有页面名称或流程框图 |
| U02 | 逐页审查排版、颜色、字号、图标、间距、组件状态、明暗主题稿，并走查紧凑竖屏/横屏/宽屏、软键盘和大字体 | 页面视觉一致，标注和 token 有具体值/版本；Calm 设计板三主题的顶栏、抽屉、气泡、知识库大数字行、模型源分隔行和工具审批底部抽屉与 Compose 实现对照；S1 启动器图标的 SVG 母版、Adaptive Icon 前景/背景/单色层及 manifest 引用一致，模拟器桌面可辨；交付可编辑源稿及素材使用说明；关键内容/操作不被遮挡；触控尺寸、对比度、读屏标签与焦点顺序写成可验证要求，记录原型无法验证的设备项 |
| U03 | 按逐页矩阵切换空数据、加载、成功、失败、离线、未配置/无权限、运行中、取消、重试；走 Vision 等待、上传拒绝、流式中断、结果未知和预算耗尽 | 状态、原因、可执行下一步和禁用操作齐全，与领域状态对应；不把等待/部分结果冒充成功；不适用状态明确说明 |
| U04 | 使用页面实际布局和样式的原型走 Provider/Agent/知识库/Chat 闭环、引用回跳、Skill 安装/授权/撤销、公告阅读/确认和设置操作 | 关键成功与失败路径可点击重现；原型与高保真页面稿一致；自造数据/模拟状态醒目标识；无真实模型调用、文件上传、权限授予等副作用 |
| U05 | 审查密钥表单、有效请求检查、视觉/Embedding 外发、工具确认、删除/导出、公告统计开关等页面与文案 | 密钥默认遮蔽且示例无真实秘密；用户能看到数据发给谁、哪些数据及费用/权限影响；拒绝可退出且无隐式授权；纯文本降级需显式选择；设计与 A03/K03/K04/S09/S10/N08 安全要求一致 |
| U06 | 用户逐页评审外观/布局/操作并确认修订；接手者依据页面稿、源稿、标注、组件和映射定位实现任务；对照现有 M1 页面列差异 | 第8节高保真页面稿与配套产物齐全且版本一致；用户确认及修改记录可追溯；缺失接口/待定品牌显式登记；未通过项未伪装完成，不能把设计通过记成业务或设备通过 |
| U07 | 在 320dp、大字体、横屏与宽屏使用全局 Agent→Thread Drawer：新建 Thread 选择 Agent/已授权 workspace、切换 Thread/功能页、打开上下文 sheet、系统返回、IME、旋转/后台恢复；并在流式输出时切换目的地 | 手机只有一个可关闭 Drawer 且没有底部一级导航，宽屏为永久侧栏；Conversation 不再建立第二个 Drawer；返回优先关闭 IME/drawer/sheet/browser；主要操作可滚动触达；流式任务不因路由切换取消；浅色为首次默认，`66ccff` 仅为可选主题 |

## 3. Provider、Agent和数据

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| A01 | 最低API26与当前target构建；arm64真机、x86_64模拟器 | App启动且ABI完整；JDK/SDK/原生库版本清单；不能用只有编译成功代替启动 |
| A02 | 检查shared导入依赖、Compose/SAF/Binder依赖分布 | shared只含业务端口，不依赖Android平台对象；仅启用Android target |
| A03 | 用标记型测试secret请求401、500、超时；查看数据库/日志/Inspector/导出/备份 | 主密钥不可导出；持久层只有密文/ref；所有输出无测试secret；API失效不影响公告 |
| A04 | 参数正常/冲突/未知/保留字段嵌套绕过；伪compatible服务缺image/tools/stream | 保留字段拒绝，不静默丢参数或降级；用户看到真实错误与费用提示 |
| A05 | 建会话后修改Prompt/模型/参数；展开Effective Prompt并与测试server接收内容比较 | 旧会话保持快照；显式换配置有新边界；预览与最终真实请求角色/结构一致，secret脱敏 |
| A06 | 两个Agent共享KB/Skill/Provider；撤权后续跑旧快照 | 不重复向量化；撤权立即优先于旧配置；不存在跨Agent授权泄漏 |
| A07 | Agent/KB/Skill导入导出往返、旧schema迁移、未知schema/坏hash/部分失败 | 默认不含secret/敏感附件；显式完整导出保留许可；重导入完整且版本匹配；失败不清库 |
| A08 | 默认关闭诊断后触发能力开关/保存；主动开启后制造滚动量、标记 secret/URL/query/path/换行、权限/审批/断连/超时/取消/未知结果、受控未捕获异常、导出目标失败、清除并重开应用 | 关闭时零日志且偏好可持久化；当前/上一段各不超过256 KiB、最近崩溃不超过32 KiB、单事件不超过4 KiB、ZIP不超过640 KiB；仅固定事件/字段，导出不含标记秘密、命令/argv/cwd、路径/URI/serial、stdout/stderr、聊天/Prompt/知识文件名/请求正文/异常消息；异常记录后委托系统原处理器；失败导出不删除现场；manifest含revision/dirty/schema/build time/设备fingerprint；原生崩溃和系统强杀边界明确提示仍需ADB Logcat |
| A09 | 以 `debuggable=false` 的 review-like build 检查危险模式、工具暴露、审批绑定、选定 Authority 失效和恢复；分别准备真实 Shizuku 服务与 USB Desktop Companion | debug/JVM/静态结果不能作为控制面安全结论；未提供真实 Shizuku/USB 端时记 `E2E BLOCKED`，不记 `DEVICE_PASS`；只有选定 Authority 可派发且无自动 fallback，危险模式关闭时不注册 `shell_exec` |
| A10 | 对同一测试 Provider 分别执行 Test Connection 与 Capability Probe，覆盖 success、401、404、429、timeout、metadata 不支持但 Chat 成功、tools/image partial、failure→retry→success；切换 Provider 后复核状态隔离 | 两个操作使用正常请求相同 adapter/header/serialization 且 typed 结果互不污染；connection success 可以与 capability partial 同时成立；UI 不以 busy 反推成功，不解析自由字符串；失败不会把 endpoint/capability 误写为 PROBED；无真实付费请求，诊断无 URL/model/secret/body |

2026-09-09 修复回归补充（关联 A10、K03/K06、S01/S10/S18/S25、U05）：同一 completion 多次累计 usage、`choices=[]` 尾帧、长度截断及错误帧 usage 均只能结算一次；新 shell PERSISTENT grant 在下一 Run 可用而当前 Run 不扩权；纯指令 Skill 显式启用后可绑定且不继承重复/损坏/过期旧 grant；PDF 同意前不得栅格化，Vision 中间状态与批次计数须在外发前持久化；worker 失败可见，外部结果未知不自动重放；请求准备超预算须持久化明确错误，英文 SAF 四个操作须完整可达。执行结果见 [修复回归证据](evidence/2026-09-09/a933b11-user-qa-fixes.md)，未完成项不得由旧自动化结果补填。

2026-09-10 定向补充：Shell `cwd` 的 `[string,null]` schema 必须到达请求准备与实际派发；nullable union 仅接受一个有效值类型加 null，非法 union、boolean 字符串及不含 null 的 enum 均须拒绝。Vision 外发进行中仍可读取快照；按 job 取消必须停止关联 consent work，然后持久化未知结果，禁止自动重放。最终自动化门禁与真实用户路径分开记入同一修复证据。

2026-09-10 独立复审 P1 补充（关联 K02、K03）：十六进制 PDF 文字操作数必须进入正文提取；提取器无法覆盖的 text-show 操作数不得把该页标为完整文本。多页 Vision 处理期间，后续请求与结果记录必须仍绑定该 Job 已确认目标，配置切换不得把未同意的新目标当作本次外发目的地。JVM 回归见 [review P1 证据](evidence/2026-09-10/review-p1-vision-pdf.md)。未跑设备矩阵或用户原件。

2026-09-10 f24f5ae 复审补充（关联 K02、K03）：注释分隔的 `'`/`"`、嵌套字面量、反斜杠转义和字体 `/Differences` 必须进入可检索正文；文字不完整时即使有可用 JPEG 也须保留 PAGE 阻断并失败关闭。同文件再导入在 parser fingerprint 落后于当前解析器时必须重新解析。JVM 回归见 [f24f5ae 复审证据](evidence/2026-09-10/review-f24f5ae-pdf-text.md)。未跑设备矩阵或用户原件。

2026-09-10 fd87a80 复审补充（关联 K02、K03）：WinAnsi `€`、MacRoman `é` 及 `q`/`Q` 恢复后的未重映射正文必须可检索，且不得把未映射高位字节标为完整。混合文档中某一页的 PAGE 阻断不得跳过其它页的可用 JPEG；无证据的 needsVision 页在栅格失败时必须失败关闭。JVM 回归见 [fd87a80 复审证据](evidence/2026-09-10/review-fd87a80-pdf-encoding-coverage.md)。未跑设备矩阵或用户原件。

2026-09-10 903c33e 复审补充（关联 K02、K03）：无 `/Encoding`（或编码字典无 `/BaseEncoding`）的简单字体必须按 `/BaseFont` 解析内置编码；`/Symbol` 的 `(abg)` 须得到 `αβγ` 而不是 Latin，无法可靠映射的内置编码（如 ZapfDingbats）须 fail closed 进入 Vision。同 blob 再导入复用 `READY_WITH_VISUAL_GAPS` 版本时必须保留该状态，不得静默升级为 `READY` 或额外上传补图；完整 READY 复用行为不变。JVM 回归见 [903c33e 复审证据](evidence/2026-09-10/review-903c33e-symbol-builtin-and-gap-reuse.md)。未跑设备矩阵或用户原件。

2026-09-10 dbceaa2 复审补充（关联 K02、K03）：PDF 名称必须按 32000-1 7.3.5 解码 `#xx`，等价拼写（如 `/Sym#62ol`）与资源名/内容流名必须一致解析，不得因未解码而落入未知字体分支。无 (Base)Encoding 的简单字体，只有 base-14 拉丁文字面按 StandardEncoding 完整发布；`Symbol` 走 Symbol 基表；`ZapfDingbats` 及任何其它未知 `/BaseFont` 必须显式判为不完整并保留 PAGE 阻断，禁止用 catch-all 默认值把未知字体当已知。JVM 回归见 [dbceaa2 复审证据](evidence/2026-09-10/review-dbceaa2-builtin-font-boundary.md)。未跑设备矩阵或用户原件。
2026-09-11 4f0556d 复审补充（关联 K02、K03、K06）：PDF 字典必须按词法解析而非扫描 `/Name`：只认当前层级的键、区分键与名称值、跳过注释与字面量串后再配平 `<< >>`/`[ ]`。键「看似缺失」或数组「看似畸形」时不得静默退化为无映射并把未声明字节标为完整；/Differences 畸形必须 fail closed 进入 Vision。文件型 ZIP 导入必须在交给入库前核对实际解压字节的尺寸与 CRC-32，两个头声明的 CRC 相同不能证明正文未被改动。JVM 与 Repository 回归见 [4f0556d 复审证据](evidence/2026-09-11/review-4f0556d-dictionary-lexicon-and-zip-crc.md)。未跑设备矩阵或用户原件。
2026-09-10 7500ad3 终轮复审补充（关联 K02、K03）：PDF 字典的键也必须按 32000-1 7.3.5 解码 `#xx` 后再匹配，`/Enc#6Fding`、`/Diff#65rences`、`/Fil#74er` 与字面写法必须等效；键「看似缺失」时不得静默回退到更弱默认（丢弃 `/Differences`、跳过 FlateDecode）并把内容标为完整。另：`check` 的 review 变体门禁（`reviewGate`）必须在 CI 内存内完成，D8 外部 dex 合并 OOM 属门禁阻断，需保证可运行而非放宽步骤。JVM 回归见 [7500ad3 终轮复审证据](evidence/2026-09-10/review-7500ad3-final-keys-and-ci.md)。未跑设备矩阵或用户原件。
### 3.2 人工测试前收口（2026-09-04）

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| N11 | Drawer 直达十个一级目的地（对话/智能体/服务商/知识/技能/公告/设置/MCP/关于/请求检查器），无 More 中转；一级页显示 Menu、feature 详情显示 Back、两者互斥；系统 Back 不结束 Activity | `ReleaseGateUiDeviceTest` 新语义全绿；`NavigationScopeTest` 锁定 Drawer 列表与 Menu/Back XOR；`AppRoutes.MORE` 仅兼容保留 |
| W11 | Shizuku workspace 目录 1000/5000 直属项经 cursor 真分页读完，无重复、无漏项、顺序稳定、单页不超 Binder/JSON 上限 | `ShizukuWorkspaceFileStoreTest` 新增分页用例；`MAX_LISTED_ENTRIES` 仅为资源保护上限且与页大小分离 |
| W12 | 父目录 list 不递归进入巨大子目录（>512 项）或超深链（>16 层）；分页期间直属项增删改名使旧 cursor 返回 `INVALID_CURSOR`；symlink/FIFO 安全跳过；超长合法文件名保持有界响应 | 同上新增用例；fingerprint 仅含本目录元数据与直属可见子项 |
| W13 | Workspace Picker 在 >256 个目录后仍可到达；大量文件不挤出目录；continuation 失效/重启 fail-closed 为 typed 错误；symlink 不可选；Shizuku 与 Wired 同契约 | `ShizukuDirectoryHandleStoreTest`、`WiredAdbWorkspaceBackendAdapterTest`、`WorkspacePickerLoadMoreTest`、`WorkspacePickerUiTest`；UI 有明确“加载更多” |
| R11 | `OPENAI_RESPONSES` 默认请求含 `"store":false` 与 `include: ["reasoning.encrypted_content"]`；显式 boolean `store:true`/自定义 include 可覆盖；非 boolean `store` 报 `INVALID_CONFIG` | `OpenAiResponsesAdapterTest` fixture 断言 |
| R12 | Responses refusal（streaming delta/done 与 non-stream content）解析为独立 `RefusalDelta`/`RefusalPart` 并正常显示；Test Connection 把 completed refusal 判为协议成功；Capability TEXT probe 把 refusal 与 malformed 区分开 | 同上；`ChatViewModel` 把 refusal 并入可读 answer 流 |
| R13 | reasoning 加密项经 provider-private 通道在同一次 run 的 tool 循环中回送；不进入 UI/推理摘要/请求预览/诊断/持久化/日志；Chat Completions 路径不受影响 | `OpenAiWorkspaceToolLoopTest` 回路用例；`previewRequest` 排除断言 |
| R14 | `testConnection`/`probeFeature` 使用 64/128 token 探测预算（profile 10k 时仍为小常量）；forced tool probe 正常；失败不自动重试付费请求 | 两 adapter 的 clamp 用例 |
| C11 | 远端 CI 结果与本地门禁如实区分：未 push 不得写“GitHub CI 已绿”；`HANDOFF.md` 的 HEAD/dirty/CI 事实与真实 `git` 状态一致 | 交接复核项，非自动化断言 |

### 3.3 架构收敛（2026-09-05）

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| T01 | 合法 Denied/Invalid/Unknown 经 AgentRuntime → Chat → SQLite → reload | 全部为 JSON object 信封并保留 typed status/code；Denied/Invalid 永不升级 INTERNAL；`ToolOutcomeTest`、`ToolOutcomeRuntimeTest`、`ToolOutcomePersistenceTest` |
| T02 | Responses 非流式 `error` 缺席/`null`/object；completed/output/incomplete/failed/refusal/多输出/未知字段/流与非流等价 | 仅非 null object 进入失败；`OpenAiResponsesErrorNullTest` 真实协议形态 fixture |
| W21 | create-only 在目标出现后提交；并发 create-only 单胜者；同长改写 + mtime 恢复仍冲突；symlink/parent 替换/类型变化 fail-closed；Internal `c1:/m1:/d1:` 与 legacy bare digest、Shizuku opaque digest 投影为 `0..Long.MAX_VALUE`，每个成功返回的 version 可原样回送 expectedVersion | 不覆盖、`ENTRY_EXISTS`/`CONFLICT`、内容完整；真实 Backend → Adapter → Executor → JSON version → expected_version 的 write/patch 往返与 stale CONFLICT，含 `test` 高位摘要且不得条件跳过；写入已提交但版本投影失败须为 `UNKNOWN_OUTCOME`；Shizuku mutation 响应损坏为 UNKNOWN、有效结构化冲突保持 CONFLICT；`InternalWorkspaceDataIntegrityTest`、`SharedWorkspaceBackendAdapterVersionTest`、`WorkspaceVersionToolFlowTest`、`ShizukuVersionProjectionTest` 及 Shizuku create-only 用例；[ADR-0009](adr/0009-workspace-version-request-range.md)，UI/schema 不夸大保证 |
| K11 | 相同 KB 集合仅交换遍历顺序；100-hit KB 与 1-hit KB；多 space；lexical/vector 缺席；deleted KB | top-K 结果与 score/order 一致（metamorphic）；`RetrievalConvergenceTest`；partial coverage 结构化并进 prompt/UI/持久化/manifest |
| K12 | 同一 generation 连续查询；新 generation 发布；KB 删除；并发 search 与 invalidate 不得观察到已关闭句柄；首次 build 失败后同 key 仍单飞 | 索引 build 计数为 1、复用命中、切换后恰一次重建、删除回收；`vectorIndexStats`；`VectorIndexCacheLeaseTest`（含失败后单飞与并发 search/invalidate）；`VectorIndexCacheDeviceTest.concurrentSearchAndInvalidateNeverObservesClosedHandle` 必须保留首个异常且线程全部结束，不得吞掉失败；大规模真机耗时另测 |
| S31 | 缓存结果重放前重验披露权：revoke 后重复调用，覆盖 RunTools 与直接 factory/composite invoke；typed factory 不把模型 callId 当作执行键 | 无外部派发、无旧数据披露、不重执行；撤销/重验异常后旧缓存永久失效，恢复授权不复活；未知执行结果保留安全 `UNKNOWN_OUTCOME`，不得降为已知拒绝或重派发；同一模型 callId 的两个 runtime requestId 独立执行；`RunToolsReplayDeviceTest`、`ToolExecutorFactoryReplayTest`；授权决策六态四检查点同一向量（`AuthorizationEvaluatorTest`、`BuiltinToolsTest`） |
| S32 | 双 Skill 各自读写 memory；revoke A；重启；伪造命名空间/handle | A 不可读、B 可用、无身份并集；`MultiSkillMemoryIdentityTest`；单 Skill 保持 legacy 工具名 |
| R21 | run 持久化 `manifest_json`（DB v17）；旧行保持 `{}`；运行指纹可解释旧会话行为变化 | `RunManifestTest`、`RunCoordinatorTest`、`MigrationsTest` v17 用例；`RunCoordinator` 拥有 prepare/owner/stamp/cancel/release |
| C21 | 测试仓库 coding 全流程：读/改/冲突/diff/验证/回滚 | 不覆盖外部并发修改、失败可解释、有恢复方案；`CodingWorkflowBenchmarkTest` |
| C22 | 工作预算四限分离（返回/扫描/元数据读/墙钟） | 超限报 `ENTRY_LIMIT_EXCEEDED`；`WorkspaceWorkBudgetTest`；SAF/Shizuku/Wired 与 knowledge/Python/ZIP/picker 同原则待后续 |

### 3.1 Wire tool name、capability 与 backend-neutral 语义

公开 schema 只使用下表的 provider-neutral wire name；`shizuku_*`、`adb_*`、`saf_*` 等实现名不得出现在 Agent-facing schema。当前已有代码/历史证据中的兼容名称只在 Host 内部转换，不能成为新的规范。

| Wire tool name | Capability | Backend-neutral 语义 | 可用 backend / 当前证据 |
| --- | --- | --- | --- |
| `workspace_list` | `workspace.enumerate` | 列出当前 workspace 根 | Internal / SAF / selected privileged adapter；API 31 真实 SAF 与 Shizuku `DEVICE E2E PASS` |
| `file_list` | `file.list` | 列出相对路径下的子项 | Internal / SAF / selected privileged adapter；API 31 真实 SAF 与 Shizuku `DEVICE E2E PASS` |
| `file_stat` | `file.stat` | 返回受限元数据 | Internal / SAF / selected privileged adapter；统一实现与 fixture 自动化已存在，真实 SAF/Shizuku/USB E2E `BLOCKED` |
| `file_read_text` | `file.read_text` | 按 UTF-8 与字节预算读取文本 | Internal / SAF / selected privileged adapter；API 31 真实 SAF 与 Shizuku `DEVICE E2E PASS` |
| `file_write_text` | `file.write_text` | 按权限、配额与 backend 能力写入文本；只有可证明时才承诺原子替换 | Internal 支持版本化原子替换；SAF 仅支持 provider/grant 可证明的新建写，既有文件替换 fail-closed `UNSUPPORTED`；selected privileged adapter 按自身能力；API 31 真实 SAF 与 Shizuku `DEVICE E2E PASS` |
| `file_create_directory` | `file.create_directory` | 创建受限目录 | Internal / SAF / selected privileged adapter；应用私有兼容实现 `IMPLEMENTED`、`AUTOMATED TESTED` |
| `file_move` | `file.move` | 在同一授权 workspace 内移动 | Internal / SAF / selected privileged adapter；统一实现与 fixture 自动化已存在，真实 SAF/Shizuku/USB E2E `BLOCKED` |
| `file_delete` | `file.delete` | 删除单文件或空目录，遵守 backend 约束 | Internal / SAF / selected privileged adapter；API 31 真实 SAF 与 Shizuku 文件删除 `DEVICE E2E PASS`，空目录/异常 provider 边界保留自动化证据 |
| `memory_read` | `memory.read` | 读取当前 Skill memory | canonical SQLite SkillMemory backend；`IMPLEMENTED`、`AUTOMATED TESTED` |
| `memory_search` | `memory.search` | 在当前 Skill memory 内有界检索 | canonical SQLite SkillMemory backend；`IMPLEMENTED`、`AUTOMATED TESTED` |
| `memory_append` | `memory.append` | 追加当前 Skill memory 条目 | canonical SQLite SkillMemory backend；`IMPLEMENTED`、`AUTOMATED TESTED` |
| `memory_replace` | `memory.replace` | 替换当前 Skill memory 条目 | canonical SQLite SkillMemory backend；`IMPLEMENTED`、`AUTOMATED TESTED` |
| `shell_exec` | `shell.execute` | Dangerous Mode 下执行一次 Android `/system/bin/sh`，受 timeout/output/cancel/audit 控制 | Shizuku 官方服务在 API 31 完成真实 shell UserService `DEVICE E2E PASS`；Wired ADB 仅 `IMPLEMENTED`/`AUTOMATED TESTED`，物理 USB `E2E BLOCKED` |

Typed file tools 仍须 workspace scope、路径/symlink/配额和 canonical grant revalidation；`shell_exec` 是明确的高风险 escape hatch，不得声称继续受 typed workspace confinement。它不授予宿主 PowerShell、宿主 shell、Root、无线 ADB、DPC、Termux 或 PTY。

## 4. 知识库

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| K01 | 同文件重复导入，同blob两个KB复用，原文件移动/删除 | CAS不重复存储；托管副本可用；删除一个KB不破坏另一个 |
| K02 | TXT/MD/PDF/DOCX/EPUB/独立图片、坏文件、超限ZIP/遍历路径 | 支持格式分别给证据；坏/不支持文件显示原因，不外联加载资源；无越界写入；用户接受视觉缺口后 `READY_WITH_VISUAL_GAPS` 可作为 knowledge_search 引用源，不得伪装完整 READY |
| K03 | 含扫描图、矢量流程图、公式缺Vision；纯文本选择API Embedding但未同意；换Provider/域名/模型/数据范围 | 视觉和Embedding分别等待授权；拒绝/过期同意时外发请求数为0；变化后重确认；不丢图、不报READY、不自动换Provider |
| K04 | 原图命中，严格模式配文本Chat；再显式启用文本降级 | 严格模式拒绝；主动降级后回答醒目说明无原图；引用可回页码/图片 |
| K05 | 中英文专名、表格、代码query；不同space/维度KB；模型不可用 | 词法/向量/过滤/RRF生效；空间不混算；不可用库明确告知；记录召回样例与不足 |
| K06 | **300—500文件、总计约300—500 MB**；导入各阶段杀App、重启、离线、超时、磁盘满、取消 | 检查点继续；成功图片不重发；云端不确定结果提示重复收费风险；无重复记录、无虚假READY |
| K07 | 破坏/删除测试索引；模拟文件写完SQL未切换及反向故障；删除文档后旧索引仍在 | 从SQLite重建；保持旧有效代际；不返回已删/未授权/未发布数据；数量/哈希一致 |
| K08 | Token预算不足、原图过大、无命中、模型虚构 citation ID | 明示证据缺失，不自动去图；未知引用不生成假链接；可追溯chunk/version/asset |

2026-09-12 5f4fd1e 复审补充：完整知识备份允许 `READY_WITH_VISUAL_GAPS` 原样导出/导入，导入后须本地重建索引，不得升为 READY 或继承 Vision/Embedding 同意。含 Skill 的 Agent 备份按来源 `install_id` 重映射到目标安装记录，不把 package id 写入运行时绑定，也不携带原授权。检索不得仅因短且无句号删除字段值/赋值/列表项。JVM 见 [5f4fd1e 复审修订](evidence/2026-09-12/review-5f4fd1e-amend.md)。

2026-09-12 af505b6 复审补充：含会话备份必须带上历史快照仍引用的 Skill，空库恢复后历史会话保留、当前 Agent 不回绑旧 Skill、目标安装禁用且无新授权。短字段只在标题结构明确时删除。更换 Provider 目标时全部目标绑定凭据失效，不得把旧辅助 Header 发往新地址。JVM 见 [af505b6 复审修订](evidence/2026-09-12/review-af505b6-amend.md)。

2026-09-12 16fe3a4 复审补充：同一 package id 的 v1/v2 Skill 备份必须按来源安装身份恢复到对应本地安装，不能因可选 package-id 别名冲突失败；仅含歧义 package id 的遗留 Agent 绑定必须拒绝。JVM 见 [16fe3a4 复审修订](evidence/2026-09-12/review-16fe3a4-amend.md)。

K03/K06 追加 API 查询门禁反例：外部请求未知后，重启并重复同 KB/完整空间/查询时请求数不增长；知识库页批准只写一次许可、不立即外发；用户重新提交仅放行一次，再次未知重新等待。不同 KB/空间/query hash 不共用许可。取消/中断仍保留未知记录，UI、内置工具、Python Broker 都不得将其当普通可重试失败。无同意调用公开重建/修复入口也必须零外发。检查 schema v8→v9→v10 数据保留、重复迁移、复合主键/外键/布尔 CHECK，原始查询不得写入门禁表。外部 query vector 已成功后若 generation/chunk/vector blob/native index 等本地检索失败，门禁必须保留且下次复用已校验向量，API 请求数不得增长；只有完整 retrieve 成功才清理尝试与缓存。

A05/S10 追加取消竞态：流式中途取消保留部分回答并关闭连接，可能已受理的模型/工具调用为 UNKNOWN；在已观察到完成事件后才取消，不得覆盖 COMPLETED。Python 同一次未知结果经过 Broker、runtime teardown、取消多次上报时，仅保留首次具体原因和一条 invocation UNKNOWN 审计。

K06负载必须使用自造/许可允许的fixture并记录SHA清单。记录设备型号/Android/API/ABI、文档类型分布、图像数量、峰值内存、磁盘、处理耗时、耗电/温控和API次数。耗时/内存阈值待基线实测后由所有者认可，不用未经测量的“几分钟”承诺。

K06另需前台任务兼容矩阵：Android12+后台启动限制、Android14+服务类型/权限、Android15适用类型/targetSDK下累计6小时限制与onTimeout、Android16作业配额。逐项记录系统API、targetSDK、实际serviceType/权限、持续通知、要求时限内（通常5秒）提升前台、超时停止/检查点/用户恢复和WorkManager补偿。用受控测试环境验证，不为测试修改用户主设备；不适用项必须写明版本与原因，不能统写通过。启动被拒绝或配额耗尽时不得丢任务或进入无限重启。

2026-09-13 第二轮 QA 修复补充（关联 A07、A10、C22—C25、K06、W13、L04）：含会话的完整备份导入不得依赖 Android 未实现的 `java.nio.file.Files.readString(Path)`，必须有界流式回读并在真机/模拟器回归；能力探测须与真实请求同一 payload 构造并合并 Profile 模型参数，强制 `tool_choice` 被 4xx 拒绝时须去 `tool_choice` 重试并区分探测形状不兼容与不支持工具；上下文压缩只按完整轮次或自包含工具交换选候选，无信息候选不得触发注定为空摘要的付费请求，摘要被拒时必须在 stopReason 记录可诊断分类；`readGranted` 的 SAF 树（含空目录与全虚拟目录）必须声明 `file_read_text`；Windows Companion 必须用 provider key 或 Win32 文件 id 建立稳定 adb.exe 身份，退化元组只作最后兜底且在 Windows 下仍拒绝；APK 声明查看器的允许路径须与 `tools/runtime-notices.py` 生成的 `licenses/`、`modelpacks/` 一致，单条异常不得清空整个组件列表。本轮证据见 [第二轮 QA 修复](evidence/2026-09-13/round2-qa-fixes.md)；设备测试仅编译通过，模拟器验收未执行。

2026-09-13 复核修订（依据 mobile-agent-runtime-4d7f857-r2-fix-review-evidence.zip）：能力探测不得因探测预算（不超过 64）小于模型中合法的默认输出上限（例如 max_tokens=1024 对普通预算 4096）而拒绝派发，须只把 profile 已使用的那一个输出上限字段钳制到探测上限并保留字段名（max_tokens 或 max_completion_tokens）；强制 tool_choice 的去留重试只允许在请求形状不支持类 4xx（排除 401/403/408/429）触发；新增 device 回归必须被 CI 的 class 选择器实际选中，含会话备份恢复须在全新目标库（独立 AndroidContextSqlite）验证。


## 5. Skills与执行安全

所有负向用例只针对本地测试App/fixture，不读取真实secret或攻击其他应用。

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| S01 | 有/无mobile-skill清单、未知schema、A—E级包、签名/哈希错误 | 分类和原因正确；原包不改；E拒绝；源码/许可可见；不自动执行导入指令 |
| S02 | Zip Slip、链接、压缩炸弹、伪后缀ELF/DEX/JAR、原生wheel、pip依赖 | 安装阶段拒绝且无外部路径写入/下载；无“忽略继续”绕过 |
| S03 | isolated worker加载官方CPython、只读包FD、取消/退出后重启 | 真实隔离UID、非exported；两次执行的解释器全局/线程/文件无残留；宿主可用 |
| S04 | 脚本读取标记型secret、App测试DB、其他Skill目录、系统全局路径 | 未授权读取失败；只允许指定短期句柄；IPC不能泄漏真实路径和秘密 |
| S05 | 直接socket、subprocess、用户.so加载；Broker域名/方法/重定向/IP变换 | 直接网络/进程/载荷路径失败；只有已授权Broker请求可成功；无跨host凭据 |
| S06 | 无限循环、内存/输出/日志/文件洪泛、阻塞、线程残留、Binder过大消息 | watchdog终止worker；宿主不崩溃；限额生效；下一调用正常；记录平台剩余限制 |
| S07 | 重放IPC、错UID/调用id、包更新hash变更、用户撤权中途再次请求 | 一次凭证和当前grant强校验；旧授权不继承；所有拒绝可审计 |
| S08 | 内置knowledge_search/read_document/calculator/http_request及碎片tool JSON | 参数schema正确；完整后执行；重复call id不双执行；只读/副作用确认正确 |
| S09 | Prompt/KB/工具结果要求扩大权限；无tools模型；循环/Token/子模型预算耗尽 | 不能越权；不从自然语言猜命令；终态和耗用可追溯；取消传播 |
| S10 | 测试secret混入模型错误/Skill输出/日志/导出，模型超时副作用未知 | 全路径脱敏；不无限持久化内容；UNKNOWN_OUTCOME不自动重放 |
| S11 | 受控MCP server工具发现/新增/重连/取消/错误；Remote接口schema测试 | 不自动授权新增工具、不在手机起任意stdio、不重放副作用；Remote不自动上传用户包或知识库 |
| S12 | 导入无 `mobile-skill.json`、含标准库 `main()` CLI 的 Claude Skill；启用、空权限确认、Agent 绑定后由模型按 program enum/argv/虚拟 Markdown 文件调用；同包含重型依赖脚本 | 原 ZIP/hash 不改；只把通过兼容门槛的程序列入 `py_*` 工具；每次调用仍批准且在新 isolated UID 中执行；隐藏已验证源码字段不能由模型声明；虚拟文件无法映射宿主路径。依赖 PyMuPDF/NumPy/PyTorch/Transformers 的 `books_kb.py` 明确不直跑，绑定知识库时由 `knowledge_search`/`read_document` 承接且模型不得伪称原脚本执行 |
| S13 | Agent 调用应用私有 `workspace_list` 与 provider-neutral `file_*` typed tools；覆盖长路径、绝对路径、`..`、symlink、配额、重复 call ID、授权后撤销 Agent/快照、ONCE 并发消费、替换写中断 | 读、列、写、建目录、移动和受限删除由 backend-neutral schema 表达；已有有效 canonical capability grant 与 snapshot binding 时不再逐次弹出对话批准，但每次派发前仍复核撤销、过期、policy revision、workspace/path scope 与 selected Authority，ONCE grant 原子消费；真实路径不进入模型或错误；Agent+快照命名空间互相隔离；越界/撤权 fail-closed；Internal UTF-8 替换写须原子且无临时残留，SAF 仅在 provider/grant 能力可证明时新建、对既有目标的非原子替换必须拒绝；typed path 不等于 shell |
| S14 | 对照 wire tool name→capability→backend-neutral 语义矩阵；Provider 无 tools、未知 tool、backend 名称伪装、schema additionalProperties 和重复 call ID | 只发送当前 Provider 声明且经 capability intersection 的中性 schema；未知/后端专用名称拒绝；schema 严格；同一 call 不重复执行；状态：mapping `IMPLEMENTED`，逐项自动化证据按工具记录 |
| S15 | Dangerous Mode 首次开启/关闭、持久化、Agent capability、`ENABLED_CONFIRM_HIGH_RISK` 与 `ENABLED_AUTONOMOUS`、Authority 暂时不可用 | 首次开启有风险确认；显式关闭才关闭；Authority 暂时失效不清除 grant 或模式但不派发；普通模式不注册 `shell_exec`；高风险档逐次确认，自治档不逐条确认但仍受限；状态：契约 `IMPLEMENTED`，自动化/E2E 分别记录 |
| S16 | 选择 `SHIZUKU` 或 `WIRED_ADB`，grant/availability/connection 变化，断连、重连、切换和撤权 | 两种 Authority 平级；只调度 selected provider；selected provider 失效返回确定错误且不自动 fallback；Binder/USB 恢复需 revalidate 后恢复；Shizuku selected/granted/ready/connected 与 UserService 在 API 31 `DEVICE E2E PASS`，Wired ADB 物理 USB `E2E BLOCKED` |
| S17 | `shell_exec` command/cwd/timeout/output/cancel/exit code，超时、截断、断连、派发后未知结果 | 仅设备端 one-shot `/system/bin/sh`；无 PTY、宿主 shell、自动重放或模型指定 serial/host/port；结构化结果和终态；Shizuku 真实 shell UserService 在 API 31 `DEVICE E2E PASS`，Wired ADB 物理链路 `E2E BLOCKED` |
| S18 | approval 与 Agent/Skill snapshot、selected Authority、Dangerous Mode、capability revision 和参数摘要绑定；批准后 revalidation 与 audit | 绑定任一项变化都 fail-closed；不得把自然语言“已批准”当 grant；未知结果不可自动重放；进程重启旧审批失效；诊断仅写哈希/枚举/计数；状态：`IMPLEMENTED`、`AUTOMATED TESTED` |
| S19 | SAF 系统选择器、持久 URI grant、撤销、URI 不泄露到模型/日志；授权目录后为 Agent 选择只读/读写快捷预设并新建会话 | SAF 是独立 workspace backend，不是 Authority；只操作用户选定 URI，grant 可撤销；不转成全局路径；平台目录授权不会静默扩大 Agent 权限，快捷预设一次持久化完整只读/读写 capability 集，已有会话快照不被改写；API 31 系统 DocumentsUI 持久授权及 list/read/write/delete `DEVICE E2E PASS`，异常 provider/物理设备差异仍保留边界 |
| S20 | Shizuku 未安装/未授权/Binder dead/rebind；非 root UID；与 Wired ADB 同时可用 | 仅接受显式 Shizuku grant 与可证明 shell UID 2000；Root/UID0 不属于产品路线；Shizuku 失效不切 Wired ADB；官方 Shizuku 13.6.0、显式用户意图/授权、shell UID UserService 在 API 31 `DEVICE E2E PASS`，物理设备差异仍未验证 |
| S21 | Windows Companion doctor/pair/reverse/session/request/recovery；官方 adb USB、loopback、会话序号/HMAC、断开与重连 | 只支持有线 USB ADB 平级 backend；不支持无线 ADB/LAN；桥不接受 host PowerShell、raw command、serial/port 由 Agent 指定；Companion/protocol 有 `IMPLEMENTED` 与 `AUTOMATED TESTED` 证据，真实设备时 `E2E BLOCKED` |
| S22 | 非 debug 控制面、诊断导出/清除与 release gate | `debuggable=false` Review APK、Review SBOM/provenance 与 security gate 已 `LOCAL_PASS`；debug 证据单列；诊断限额固定为 256/256/32/4/640 KiB；API 31 真实 Shizuku/SAF `DEVICE E2E PASS`，物理 USB 与非模拟器差异保持 `E2E BLOCKED` |

| S23 | Agent 下多个 Session 的侧栏投影、从侧栏选择 Agent 新建会话、归档/已删除 Agent、切换页面与活跃流、系统返回 | 会话按 canonical snapshot→Agent 关系分组且不复制归属真相；删除/归档 Agent 的历史会话仍可识别；流生命周期不绑定目的地 entry；抽屉/sheet/browser 优先消费返回 |
| S24 | Agent 1/2 分别选择 SAF 文件夹 A/B；同一 Agent 同时获授 A/B 并把 B 设为默认；分别创建 Thread A/B；撤销 system persisted grant；重启后 hydrate | A/B 使用独立 opaque workspace 且 Grant 不互相撤销；默认值只影响新 Thread；每个 Thread 持久绑定自己的 workspace；选择流程原子完成 backend + Agent Grant + 可选 binding/default，取消无 mutation；只读不虚报写；模型/UI/诊断无 URI |
| S25 | Agent 的 Thread A 在 workspace A 上运行时新增 Grant B/改变默认，随后 Thread A 与新 Thread B 各开始 Run；再撤销 A | Run A frozen schema 不扩权；Thread A 仍绑定 A，新 Thread 可绑定 B；撤销在 dispatch 前立即 fail-closed且不自动改绑/回退；历史 snapshot/Thread binding 不因默认变化重写；已持久授权范围内的普通文件操作不重复要求对话批准 |
| S26 | 选定 Shizuku 或 Wired ADB 后浏览并绑定两个不同设备目录；连接失效、切换 selected Authority、输入越界路径/不透明句柄重放 | 每个连接可承载多个 workspace；目录浏览只使用 selected typed backend；不可用或 mismatch 时零 fallback；句柄绑定 authority/epoch/目标且过期或重放失败；真实 root/serial 不进入模型或普通 UI |
| S27 | 未确认、确认、撤销“完整设备文件（ADB 可见范围）”；尝试 ADB 可达与 UID/SELinux 拒绝目录；检查工具集与 shell | 未确认零 dispatch；确认后只产生 provider-neutral 文件 scope，不产生 Root 或隐式 `shell.execute`；拒绝返回稳定错误；不确定 mutation 为 `UNKNOWN_OUTCOME` 且不重放；撤销后下一 Run 移除工具 |
| S28 | 在已启用 Shizuku 或已配对 Wired ADB、已设置 Agent 当前工作区及完整设备文件授权后，依次断开 Binder/USB、关闭 Wi-Fi、后台/重建 Activity、重启 App；随后恢复连接，并分别测试离线显式撤销与底层授权真正撤销 | 临时断联期间 selected Authority、用户意图、Wired trust、Agent 当前工作区、普通 capability grant 与完整设备授权保持；只把 availability/connection 标成暂不可用且零 dispatch，恢复同一受信连接后无需重新配置即可继续；离线显式撤销可本地持久化并在恢复后仍生效；只有平台明确拒绝/撤销或 identity/protocol binding 失败才要求重新授权，且绝不 fallback 到另一 Authority |
| S29 | 分别用 Shizuku/Wired 选择目录，保存后杀 App/UserService/Companion 或断连并重启；篡改密文、AAD、locator version，删除目录或撤销平台权限 | DB/导出/诊断无 locator 明文；恢复时同 workspaceId 生成新 ephemeral handle；暂时断联只进入 UNAVAILABLE/REATTACHING且不撤 Grant；目录不存在、权限拒绝与密文不可恢复使用不同闭合状态；任何失败都不 fallback、不重放旧 handle |
| S30 | 在真实临时代码仓库创建超过单页上限的目录和大文件；分页 list、stat、offset read、并发外部修改后 apply_patch；尝试 `..`、symlink、超预算与 SAF 非原子覆盖 | 分页无漏项/重复且 cursor 不能跨 workspace/path 重放；stat 不读全文；分块结果含 size/next offset/eof/version；expected hash/version 冲突不覆盖；支持原子 replace 的 backend 才执行 patch，SAF 明确 UNSUPPORTED；所有结果仍受相对路径与序列化预算限制 |

## 6. 公告

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| N01 | 创建draft、定时、发布、修订、撤回、归档；并发编辑/创建第二个待发布修订 | 草稿/未来公告不外泄；数据库唯一约束触发409；新草稿不遮盖旧发布；审计和feed版本一致；撤回后成功同步停止主动展示 |
| N02 | Android/其他平台、min/maxVersionCode边界、渠道、locale、0/30/100%灰度 | 双端黄金向量一致；同安装稳定；版本用整数；语言回退不绕过受众 |
| N03 | 同revision多次启动、重要确认、标全部已读、revision+1、App更新 | 确认/关闭持久化；read不等于ack；新修订正确未读；不无穷弹窗 |
| N04 | 离线、过期、坏图片、长文本、小屏、字体放大、深浅色、接口500 | 缓存可读；不显示过期主动弹窗；正文可读、App不受阻 |
| N05 | 200/304、无缓存304、换语言/版本/安装ID、签名过期、定时到点 | ETag与target对应；不共享个性化feed；304不延长签名有效期；不会被旧缓存卡住 |
| N06 | HTML/script、intent/file/javascript/未知route、远程Skill调用载荷 | 内容/动作拒绝；不能执行代码或更改权限；更新必须用户进入独立流程 |
| N07 | 错key/篡改字节/错audience/旧feed/未知schema、管理员无权限/CSRF | 验签拒绝且保留旧有效缓存；管理API拒绝；APK不含管理secret；不能回退裸JSON |
| N08 | 关闭统计、清队列、并发重试同eventId、不同event同receipt、app_active在6小时边界并发；事件附用户内容 | 关闭仍读公告且不写统计；去重与聚合原子且无丢失/重复计数；未知/敏感字段拒绝；活跃数是实例数不是事件数 |
| N09 | 独立本地Worker/D1/Admin→Android流程；错误测试/生产绑定与Provider认证泄漏 | 不碰其他产品；请求头分域；本地PASS不标生产；管理发布/撤回在客户端真实体现 |

## 7. 证据产物和独立复核

### 7.0 自动上下文压缩（R34，ADR-0010）

| ID | 场景 | 预期 |
| --- | --- | --- |
| C20 | 相同正文/schema 将工具参数增至约 65 KiB；首次历史和工具回合后的再次请求；用户预算超过模型窗口 | 统一估算随完整参数增长，模型窗口扣除输出预留后取较小上限，派发前阻断超限，不将单位标成真实 tokens；超限 UI/诊断给出窗口、预留和分量拆解 |
| C21 | 超过 20 条历史、多轮工具交换、反复触发软阈值 | 自动摘要后继续，保留原始 Message、最初用户目标、当前消息、最近完整轮，工具调用/结果保持配对且不重放 |
| C22 | 段轮数触发压缩，连续摘要达到总请求/摘要/工具/时间硬限 | 成功摘要后只重置段轮数；总预算仍有界，关闭压缩且显式总请求上限 2 时，持续工具循环最多派发 2 次；摘要 requests 与 usage 显示在 Run |
| C23 | 无效/超长/空/工具型摘要、无终帧、超时、取消与进程重启 | 不使用半成品、不自动重发；PREPARED 取消、DISPATCHED UNKNOWN；原文保留，终态不可改写；Usage 后取消/直接异常/超时及成功落库后事件丢失均保留已知用量，重复累计快照与收尾补账不得重计 |
| C24 | SQLite v17→v18；跨会话来源、来源改变、陈旧 parent、模型/授权变化、撤权/删除 | 迁移保留原库内容；非法来源或链头拒绝；复用需所有指纹与源 hash 匹配；历史摘要不能恢复访问 |
| C25 | 设置数字清空/非法/保存重载、未知键保留、新会话快照、摘要记录展开与原文追溯；图片和私有续接 | 清楚展示配置及额外模型成本；图片/私有续接不进入文本摘要，私有续接不持久化；自动化/编译/设备/Provider 验证分别记录 |

执行证据：[2026-09-11 上下文压缩](evidence/2026-09-11/context-compaction.md)。

2026-08-31 v2 本地收敛证据：严格全仓 `check`、Debug evidence gate 与 `debuggable=false` Review gate 全部通过；Debug/Review 均生成 171-component CycloneDX 1.6 SBOM 和 SHA-bound provenance；REUSE、AGPL/license 正反向、Actions pin、28 个 lockfile、root+included-build strict dependency verification、148 Maven + 4 native/model 成品 notices 均通过。最终 Debug APK 已安装到 API 31 x86_64 并确认首次浅色主题；v2 instrumentation 按 Authority/Workspace/Memory、Tooling/Navigation/Search、UI/Release Gate、Diagnostics 分批执行。准确测试数、产物 hash、命令、独立只读复核与外部 `E2E BLOCKED` 列表见 [authority-tooling-v2-final](evidence/2026-08-31/authority-tooling-v2-final.md)。该证据是 dirty-source 本地候选，不代表正式签名、发布或生产部署。

2026-09-01 新诊断复现的根因是 typed workspace 在 canonical grant 与 snapshot binding 通过后仍创建第二份进程内批准，导致三次 `workspace_read` 停在重复确认而未派发。修复后持久授权直接进入实时复核；同 model call 并发只派发一次，live policy revision 变化立即撤销旧 executor 权限，STARTED 后异常按 `UNKNOWN_OUTCOME` 终结且不重放。API 31 `ToolingOrchestrationTest` 37/37、`DiagnosticsDeviceTest` 12/12，全仓 1078-task build 与 592-task Review gate 通过，第二轮独立只读复核 `PASS`。准确产物哈希和边界见根目录 `HANDOFF.md`；这仍是 dirty-source 本地候选，不是正式 release。

2026-09-01 最新真实 E2E 复核：系统 DocumentsUI 对 `Download/mar-workspace` 的持久 SAF grant 和官方 Shizuku 13.6.0 均在 API 31 x86_64 模拟器实际配置。真实红灯定位为审计空 workspace、SAF tree/document URI、SAF mutation handle、Shizuku 根 list 路径和首次并发 bind 等独立边界错误；公开 schema 与 Wired expectedVersion 的静默忽略也已 fail-closed 收口。修复均只在对应 API 边界规范化；具体操作审计、路径隔离与 SAF 既有文件非原子覆盖拒绝未放宽。最终首次无预热 Shizuku bind 1/1、SAF 2/2、模型侧 Shizuku 1/1、Shizuku UserService 2/2，完整 connected matrix 235 tests 全通过，另有 1 个未显式启用的受控大负载 Knowledge skip。准确步骤、命令、哈希和 Review 边界见 [2026-09-01 真实工作区 E2E 证据](evidence/2026-09-01/workspace-tool-real-e2e.md)。物理 USB Companion、物理断连恢复与非模拟器设备差异仍保持 `E2E_BLOCKED`。

2026-09-01 v2.4 交互与持久授权复核：Agent→Session 侧边栏、Agent 级当前工作区、多 SAF、selected Authority 目录与完整设备文件入口已实现。ADB 级 selection、用户意图、configured、平台 grant、Wired trust/secret、工作区、完整设备文件和 Dangerous Mode 与实时 availability/connection 分离；Binder、USB、Wi-Fi 或 Companion 暂时断联不撤权、不切换、不 fallback。API 31 聚合设备批次 95/95、`ToolingOrchestrationTest` 41/41、真实 Shizuku 模型工作区 1/1 通过；全仓 strict gate、lint、REUSE、许可与供应链门禁通过。独立复核发现并修复“离线 UI 以 ACTIVE 误判持久授权”的 P1，复核后未发现新的 P0/P1/P2。准确命令、产物和物理设备边界见 [v2.4 记录](evidence/2026-09-01/conversation-workspace-v2-4.md)。

2026-08-30 第二轮人工反馈包补充验证：API 31 x86_64 上 `DiagnosticsDeviceTest`、`ReleaseGateUiDeviceTest`、`NavigationScopeTest` 合计 17/17、0 failed；`check --dependency-verification=strict` 936 tasks 通过。覆盖诊断启停/边界、More 二级返回、公告配置隐藏、无智能体尺寸和稳定导航 owner；ZIP/Skill install/批次调度由共享与 SQLite 测试覆盖。用户实际 294 个 PDF 的完整耗时及真实 Provider 跨页流仍保留为人工终审，不据此标 K06 或正式 release PASS。证据见 [manual-review-round-2-fixes](evidence/2026-08-30/manual-review-round-2-fixes.md)。

2026-08-30 第三轮能力反馈包补充验证：API 31 x86_64 上 `NavigationScopeTest`、`WebSearchDeviceTest`、`PythonRuntimeDeviceTest`、`PythonSkillToolDeviceTest`、`DiagnosticsDeviceTest`、`ReleaseGateUiDeviceTest` 合计 34/34、0 failed；`check --dependency-verification=strict` 936 tasks 通过。`PythonSkillToolDeviceTest` 覆盖真实导入、启用、grant、Agent snapshot、模型可见 ToolSpec、逐次审批和 isolated CPython 虚拟文件执行；联网响应覆盖活动 key 脱敏。未调用真实 Brave/付费 Provider/Vision，也未跑用户 294 个 PDF 全量耗时，因此仍不是完整 K06 或正式 release PASS。证据见 [manual-review-round-3-capabilities](evidence/2026-08-30/manual-review-round-3-capabilities.md)。

2026-08-30 第四轮人工反馈先完成三项可验证修复：长工具确认卡可在固定操作区上方滚动、Provider 两个预算输入可完整清空后再校验、首次/缺失/非法主题回退浅色且保留用户显式选择。Agent 文件能力只新增 S13 的应用私有文本工作区，API 31 x86_64 `WorkspaceAppToolsTest` 6/6；SAF、Termux、无线 ADB、DPC、root/Shizuku 和任意 shell 均未实现。最终全仓门禁、APK hash 与剩余边界见 [第四轮证据](evidence/2026-08-30/manual-review-round-4-ui-workspace.md)。

上述第四轮记录是当时的时间限定证据，不是 v2 规范。v2 当前只保留 `SHIZUKU` 与 `WIRED_ADB` 两个 elevated Authority；无线 ADB、Termux、DPC、Root 与 PTY 仍为明确排除项。后续如有源码或测试进展，必须在本矩阵新增证据并分别填写 `IMPLEMENTED`、`AUTOMATED TESTED`、`E2E BLOCKED`，不得回写历史证据为真机 PASS。

实现后在`docs/evidence/日期-任务ID/`保留脱敏报告、测试清单及允许公开的截图/日志。每份报告包含：需求/验收ID、范围、工具/依赖/SDK版本、Git SHA或未提交状态、命令与退出码、fixture哈希、观测结果、失败/剩余风险、审阅结论。敏感原始材料只放`.private/`，不在公共报告链接私有用户内容。

证据链必须可追溯：**观察证据（命令/截图/测试）→ 结论（通过/不通过/待验证）→ 下一步（可执行修复/验证动作）**。独立审阅者只读检查作者证据、契约和关键路径，不接受“作者说已测”作为唯一证据。

使用现有可复用命令，并在各工作包记录准确命令与执行结果；缺少的测试或设计证据明确记为未执行。不为了绿灯建空测试，不用原型或 fake server 的结果冒充真实 API/真机/生产结果。

## 8. 发布门槛

2026-09-09 用户视角补充证据：`main/a933b11` 对应 `19b5dc3` review/debug-signed、debuggable=false 包，在本轮独立 API 36 Google APIs x86_64、RAM 4 GiB 上使用真实 SiliconFlow 完成主要用户路径测试。真实 Chat、Internal/SAF/Shizuku 文件往返、SAF 非原子覆盖拒绝、组合撤权后旧会话请求不再暴露文件工具通过。294 PDF/315435736 bytes 全量导入出现 5 次 LOW_MEMORY；两份原始 A 类 Skill 无法完成 Agent 绑定；完整 B 类 ZIP 可安装启用绑定，但调用前发生内部错误；shell 暴露和 Vision 完成状态仍阻塞。整体 **NEEDS_AMEND**，不覆盖物理 Wired USB/OEM、完整 K06 或 Python/shell 执行验收；这些结果不由既有自动化 PASS 补填。准确复现、候选根因、未测场景与清理见 [本轮报告](evidence/2026-09-08/a933b11-user-journey-qa.md)。本轮只更新证据和交接，未修改产品源码或发布。

必须满足功能范围、ABI/设备、安全、迁移、许可和隐私检查；M0.5 设计及后续 U 系列适用项必须有证据，M6未完成不能称功能完整MVP；Dangerous Mode 的安全门禁必须在 `debuggable=false` review-like build 上通过，不能用 debug APK 替代；Shizuku/Wired ADB 缺少真实端时只能记 `E2E BLOCKED`。M7发布准备通过也不代表授权部署或发布。发布任务另外记录目标、源码SHA/tag、产物hash、签名身份、依赖/SBOM、迁移备份、回退方法、用户授权和后检结果。

## 2026-09-13 P1 知识库批次补充验收

- 正常系统文件选择后必须出现独立批次确认框；显示可用 image CHAT 模型的真实目标，提交冻结的指纹，并在保存授权时再次复核。
- 294 项默认不展开单项列表；窄屏批次进度与暂停入口首屏可见。暂停、进程重启、继续后复用已完成成员。
- staging_complete=0 不派发 Worker、不授权未冻结成员；同名不同来源保留两份，ZIP 外部变更不影响暂停后的本地快照。
- 无模型的纯文本可完成；实际视觉缺口整批阻塞，配置并确认目标后恢复。真正缺失原图显示资料缺口。
- 请求前持久化 UNKNOWN_OUTCOME，重启不重放；明确成功页复用，显式重复收费确认才可重试未知请求。
- 紧凑合法图片 PDF 经正常 UI、真实视觉后端处理并可检索，日志具有对应派发、响应及检查点。

本轮实际证据与未测范围见 [复核报告](evidence/2026-09-13/p1-batch-import-review.md)，协议取舍见 [ADR 0011](adr/0011-knowledge-batch-staging-and-vision.md)。本节不把模拟器视为物理设备验收。
