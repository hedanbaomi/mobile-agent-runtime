<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 验收矩阵与证据要求

2026-10-02 全面代码 review 修复追加回归（实际结果与未测边界以 [专项记录](evidence/2026-10-02/code-review-repair.md) 为准，不代表中断的全量设备测试已通过）：

- Responses 仅收到 `[DONE]` 时分别验证空响应、推理独占、已确认工具、未确认工具、正文与拒绝；不能虚报空回复成功或释放未确认调用。Chat `length` 的 EOF 与 `[DONE]` 路径保持相同分类与 Usage；未知 Responses JSON 状态只保留校验通过的 Usage。
- SAF `createDocument` 成功后的开流、部分写入、关闭、授权及别名复核失败均为 `UNKNOWN_OUTCOME`；创建前失败保持原分类。主机故障注入只证明异常归约，真实 DocumentsProvider 行为须单列设备证据。
- HTTP 的 `fc`、`fd`、`fe80` DNS 名称可通过精确 host 授权及有效 TLS；解析到 ULA、link-local、loopback 或 IPv4 私网仍在建立连接前拒绝。
- STAGING 重建复用相同正文的 chunk ID 与成功向量，重排无 ordinal 冲突，替换时同事务清理对应向量；其他文档、READY 历史版本和旧引用保持可定位。检索先对完整有界候选池做标题剪枝，再截断或补齐 `topK`。
- 第一批正在准备或复制原件时提交第二批，必须显示未接受原因并保留第二批选择；已接受后才清除对应选择。设置重组、前台统计、导入 URI 元数据和启动导入恢复的存储访问移出主线程；设备 StrictMode 与实际页面延迟分别记录。
- 文件夹恰好 500 文件可接受，501 文件、超过目录深度或总条目预算整批拒绝；逐 Cursor 行读取应在预算或取消时停止，不先物化完整超宽目录，不把截断的子集当作成功导入。

2026-10-01 r2 修复追加回归（实际执行与跳过项见 [逐项记录](evidence/2026-10-01/acceptance-r2-repair.md)，本段不代表整体验收通过）：

- 同工作区绑定和新会话保留当前策略有效的整目录持久授权；过期、撤销、已消费、其他 Agent/工作区及 scoped grant 不得被带回。策略过期授权只能由用户明确重新确认后以新 ID 保存，范围和期限不扩大。
- 不重启 Application 的批次再投递应结清遗留 attempt 并恢复本地队列；暂停/取消/阻塞不自动恢复，未知外部结果和视觉固定目标/范围校验保留。CAS 预检失败应有界停止，不能无限领取同一 item。
- 暂停批次可以跟随可证明的同 KB/embedding space 追加及空库首次追加；删除、替换版本和 rebind 仍阻断。证明条件见 [ADR 0018](adr/0018-acceptance-batch-delivery-and-append-fences.md)。
- 归档逐会话读取；在前一条目写完后修改 Run/Audit（不改变会话时间）也必须拒绝导出混合快照，并给出安全可见原因。静态源的完整会话导出/导入和既有 16/32 MiB 界限继续验证。
- 中断的协议完整助手文本可以显式续接，但不能成为完整摘要来源；未配对工具交换拒绝进入该路径。MCP 业务字段可用，凭据和主机标识过滤不放宽。
- 长中文目录名分页每页不超过既有 32 KiB，连续游标不漏不重；英文界面保留真实目录名。导航烟测以已处理首启提示为测试前置，仅写测试应用偏好，不授予 OS 通知权限。

2026-09-26 设备报告 `8d97dc0` 修复复测项：分别导入合成旧格式配置与当前 ZIP，前后按 Agent ID 核对 `agent_profiles`、`agent_snapshots`、`conversations`、消息、消息部分、Run、工具及审计 ID，删除正文或同数量替换也必须回滚并显示结果；报告所用 c2eedf6 旧备份原件已无法取得，因此该原始路径不能复现。在历史会话配置本机同目标 Provider 凭据后运行，确认冻结快照未变且错误不再是悬空密钥 `INTERNAL`；导入完整知识内容后启动修复/手动重建应命中已知文本，只有元数据、缺 chunks 或存在失败/取消且无活动版本的文档时，手动整库重建必须给出源内容缺失而非“重建成功”；单文档成功导入不得被其他失败文档阻断，原有可检索内容不得因终态失败任务被清空。>16 MiB 且 ≤32 MiB 的单会话条目应导出导入；发送、取消、30 秒设备命令超时、>256 特权目录、只读预设撤写和默认只读附加分别复测。现有 JVM 与模拟器合成测试通过不等于旧备份、真实 Provider 或真机验收通过。

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
| A08 | 默认关闭诊断；INFO→DEBUG→重建→INFO，分别记录普通/错误/视觉正文/详细进度；主动开启后制造滚动量、标记 secret/URL/query/path/换行、权限/断连/超时/取消/未知结果、受控异常、失败导出、清除 | 默认 INFO；选择级别不自动开启诊断，持久保存；INFO 不采集/分块 Vision 正文与详细进度，跳过不计为丢弃；DEBUG 保留凭据及 private continuation 过滤和正文分享提示，切回 INFO 不清除历史；关闭零日志；8/8 MiB、32/64 KiB、ZIP20 MiB 限额；固定字段与既有脱敏，异常后委托系统处理器，失败导出不删现场；manifest 含构建/fingerprint/activeLogLevel 及历史级别说明；滚动及原生崩溃边界明确；具体证据见 2026-10-05/logging-and-search |
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
| K02 | TXT/MD/PDF/DOCX/EPUB/独立图片、坏文件、超限ZIP/遍历路径；EPUB 单引号图、SVG/object、旧指纹与不安全 XHTML | 支持格式分别给证据；坏/不支持文件显示原因，不外联加载资源；无越界写入；用户接受视觉缺口后 `READY_WITH_VISUAL_GAPS` 可作为 knowledge_search 引用源，不得伪装完整 READY |
| K03 | 含扫描图、矢量流程图、公式缺Vision；纯文本选择API Embedding但未同意；换Provider/域名/模型/数据范围 | 视觉和Embedding分别等待授权；拒绝/过期同意时外发请求数为0；变化后重确认；不丢图、不报READY、不自动换Provider |
| K04 | 原图命中，严格模式配文本Chat；再显式启用文本降级 | 严格模式拒绝；主动降级后回答醒目说明无原图；引用可回页码/图片 |
| K05 | 中英文专名、表格、代码query；不同space/维度KB；模型不可用 | 词法/向量/过滤/RRF生效；空间不混算；不可用库明确告知；记录召回样例与不足 |
| K06 | **300—500文件、总计约300—500 MB**；导入各阶段杀App、重启、离线、超时、磁盘满、取消 | 检查点继续；成功图片不重发；批次同一已确认目标的暂时错误和 UNKNOWN 有界自动重试，保留可能重复计费的 attempt 证据；用户暂停/取消与不可恢复错误停止；不同文件及同文件独立视觉单元至少两路实际重叠且不超过批次策略（新批次默认四路、上限六路；旧批次保留原值），API Embedding 单路；无重复记录、无虚假READY |
| K07 | 破坏/删除测试索引；模拟文件写完SQL未切换及反向故障；删除文档后旧索引仍在 | 从SQLite重建；保持旧有效代际；不返回已删/未授权/未发布数据；数量/哈希一致 |
| K08 | Token预算不足、原图过大、无命中、模型虚构 citation ID | 明示证据缺失，不自动去图；未知引用不生成假链接；可追溯chunk/version/asset |

2026-09-12 5f4fd1e 复审补充：完整知识备份允许 `READY_WITH_VISUAL_GAPS` 原样导出/导入，导入后须本地重建索引，不得升为 READY 或继承 Vision/Embedding 同意。含 Skill 的 Agent 备份按来源 `install_id` 重映射到目标安装记录，不把 package id 写入运行时绑定，也不携带原授权。检索不得仅因短且无句号删除字段值/赋值/列表项。JVM 见 [5f4fd1e 复审修订](evidence/2026-09-12/review-5f4fd1e-amend.md)。

2026-09-12 af505b6 复审补充：含会话备份必须带上历史快照仍引用的 Skill，空库恢复后历史会话保留、当前 Agent 不回绑旧 Skill、目标安装禁用且无新授权。短字段只在标题结构明确时删除。更换 Provider 目标时全部目标绑定凭据失效，不得把旧辅助 Header 发往新地址。JVM 见 [af505b6 复审修订](evidence/2026-09-12/review-af505b6-amend.md)。

2026-09-12 16fe3a4 复审补充：同一 package id 的 v1/v2 Skill 备份必须按来源安装身份恢复到对应本地安装，不能因可选 package-id 别名冲突失败；仅含歧义 package id 的遗留 Agent 绑定必须拒绝。JVM 见 [16fe3a4 复审修订](evidence/2026-09-12/review-16fe3a4-amend.md)。

K03/K06 追加 API 查询门禁反例：外部请求未知后，重启并重复同 KB/完整空间/查询时请求数不增长；知识库页批准只写一次许可、不立即外发；用户重新提交仅放行一次，再次未知重新等待。不同 KB/空间/query hash 不共用许可。取消/中断仍保留未知记录，UI、内置工具、Python Broker 都不得将其当普通可重试失败。无同意调用公开重建/修复入口也必须零外发。检查 schema v8→v9→v10 数据保留、重复迁移、复合主键/外键/布尔 CHECK，原始查询不得写入门禁表。外部 query vector 成功入不可变缓存后，以本次 owner 清理门禁，缓存不随 retrieve 成功删除；随后 generation/chunk/vector blob/native index 本地失败复用缓存且不增长 API 请求数。存在并行新 owner pending 时，旧调用不能清理，须明确授权后本地收尾。返回后校验或缓存失败保持 UNKNOWN，不能普通重发。

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
| S11 | 受控MCP server工具发现/新增/重连/取消/错误；初始化/发现/密钥解析/已派发各窗口撤权与旧 ID 重放；Remote接口schema测试 | 不自动授权新增工具、不在手机起任意stdio、不重放副作用；Remote不自动上传用户包或知识库 |
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
| S22 | 非 debug 控制面、诊断导出/清除与 release gate | `debuggable=false` Review APK、Review SBOM/provenance 与 security gate 已 `LOCAL_PASS`；debug 证据单列；现行诊断限额为 8/8 MiB、32/64 KiB、20 MiB；API 31 真实 Shizuku/SAF `DEVICE E2E PASS`，物理 USB 与非模拟器差异保持 `E2E BLOCKED` |

| S23 | Agent 下多个 Session 的侧栏投影、从侧栏选择 Agent 新建会话、归档/已删除 Agent、切换页面与活跃流、系统返回 | 会话按 canonical snapshot→Agent 关系分组且不复制归属真相；删除/归档 Agent 的历史会话仍可识别；流生命周期不绑定目的地 entry；抽屉/sheet/browser 优先消费返回 |
| S24 | Agent 1/2 分别选择 SAF 文件夹 A/B；同一 Agent 同时获授 A/B 并把 B 设为默认；分别创建 Thread A/B；撤销 system persisted grant；重启后 hydrate | A/B 使用独立 opaque workspace 且 Grant 不互相撤销；默认值只影响新 Thread；每个 Thread 持久绑定自己的 workspace；选择流程原子完成 backend + Agent Grant + 可选 binding/default，取消无 mutation；只读不虚报写；模型/UI/诊断无 URI |
| S25 | Agent 的 Thread A 在 workspace A 上运行时新增 Grant B/改变默认，随后 Thread A 与新 Thread B 各开始 Run；再撤销 A | Run A frozen schema 不扩权；Thread A 仍绑定 A，新 Thread 可绑定 B；撤销在 dispatch 前立即 fail-closed且不自动改绑/回退；历史 snapshot/Thread binding 不因默认变化重写；已持久授权范围内的普通文件操作不重复要求对话批准 |
| S26 | 选定 Shizuku 或 Wired ADB 后浏览并绑定两个不同设备目录；连接失效、切换 selected Authority、输入越界路径/不透明句柄重放 | 每个连接可承载多个 workspace；目录浏览只使用 selected typed backend；不可用或 mismatch 时零 fallback；句柄绑定 authority/epoch/目标且过期或重放失败；真实 root/serial 不进入模型或普通 UI |
| S27 | 未确认、确认、撤销“完整设备文件（ADB 可见范围）”；尝试 ADB 可达与 UID/SELinux 拒绝目录；检查工具集与 shell | 未确认零 dispatch；确认后只产生 provider-neutral 文件 scope，不产生 Root 或隐式 `shell.execute`；拒绝返回稳定错误；不确定 mutation 为 `UNKNOWN_OUTCOME` 且不重放；撤销后下一 Run 移除工具 |
| S28 | 在已启用 Shizuku 或已配对 Wired ADB、已设置 Agent 当前工作区及完整设备文件授权后，依次断开 Binder/USB、关闭 Wi-Fi、后台/重建 Activity、重启 App；随后恢复连接，并分别测试离线显式撤销与底层授权真正撤销 | 临时断联期间 selected Authority、用户意图、Wired trust、Agent 当前工作区、普通 capability grant 与完整设备授权保持；只把 availability/connection 标成暂不可用且零 dispatch，恢复同一受信连接后无需重新配置即可继续；离线显式撤销可本地持久化并在恢复后仍生效；只有平台明确拒绝/撤销或 identity/protocol binding 失败才要求重新授权，且绝不 fallback 到另一 Authority |
| S29 | 分别用 Shizuku/Wired 选择目录，保存后杀 App/UserService/Companion 或断连并重启；篡改密文、AAD、locator version，删除目录或撤销平台权限 | DB/导出/诊断无 locator 明文；恢复时同 workspaceId 生成新 ephemeral handle；暂时断联只进入 UNAVAILABLE/REATTACHING且不撤 Grant；目录不存在、权限拒绝与密文不可恢复使用不同闭合状态；任何失败都不 fallback、不重放旧 handle |
| S30 | 在真实临时代码仓库创建超过单页上限的目录和大文件；分页 list、stat、offset read、并发外部修改后 apply_patch；尝试 `..`、symlink、超预算与 SAF 非原子覆盖 | 分页无漏项/重复且 cursor 不能跨 workspace/path 重放；stat 不读全文；分块结果含 size/next offset/eof/version；expected hash/version 冲突不覆盖；支持原子 replace 的 backend 才执行 patch，SAF 明确 UNSUPPORTED；所有结果仍受相对路径与序列化预算限制 |
| S31 | 卸载已启用且已绑定 Agent 的 Skill；同包重新导入、另一个 Skill、旧会话导出与专属记忆 | 安装项消失，当前 Agent 解绑且授权/待用批准失效；旧快照、审计和记忆保留，历史会话仍可导出；同包重装生成新安装 ID 且不得继承旧授权；无历史引用时清理包字节，不删其他 Skill/共享 KB |
| S33 | 旧 Brave 配置；选择 Tavily/Exa，分别保存/禁用/删除密钥；切换、重建、秘密回收、异步刷新前的旧 UI 回调；协议 fixture 检查 auth/body、private/shared DNS、302/307/503 | 旧配置兼容，三家凭据隔离且未选中/停用的凭据不误删；旧 UI 回调不跨供应商保存/禁用/删密钥，名称同步变化；未知服务拒绝；固定目标和认证，零跨地址凭据披露，零重定向/自动重试；有界不可信结果；fixture 不记为供应商线上验收 |
| S34 | Agent 默认关闭、显式开启并新建会话、关闭、关闭再开启；查询后重复 callId；服务切换再切回；发送前/后撤销及失败 | 开启无需逐查询批准，其他工具政策不变；旧快照不扩权；实时撤权阻断旧 executor 和缓存重放，修订变化不复活旧调用；派发前 DENIED、确定完成但扣留 COMPLETED_WITHHELD、派发后未知 UNKNOWN 分别保存，已知完成的工具不被重启恢复改成未知；同调用一次派发，未知结果不重放；模拟器/UI/Keystore 与协议 fixture 证据分列 |

## 6. 公告

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| N01 | 创建draft、定时、发布、修订、撤回、归档；并发编辑/创建第二个待发布修订 | 草稿/未来公告不外泄；数据库唯一约束触发409；新草稿不遮盖旧发布；审计和feed版本一致；撤回后成功同步停止主动展示 |
| N02 | Android/其他平台、min/maxVersionCode边界、渠道、locale、0/30/100%灰度 | 双端黄金向量一致；同安装稳定；版本用整数；语言回退不绕过受众 |
| N03 | 同revision多次启动、重要确认、标全部已读、revision+1、App更新 | 确认/关闭持久化；read不等于ack；新修订正确未读；不无穷弹窗 |
| N04 | 离线、过期、坏图片、长文本、小屏、字体放大、深浅色、接口500；320dp/360dp 公告筛选栏 | 缓存可读；不显示过期主动弹窗；正文可读、App不受阻；“全部标为已读”单行无裁切，四个控件等宽同高 |
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

## 2026-09-28 含文字层 PDF 的局部视觉验收

- 完整文字层与可信 JPEG 插图共存时，原生文字可检索，视觉任务仅发送插图，整页 renderer 调用次数为 0；图像结果仍保留页码和资产引用。
- 文本不完整、矢量绘制、未知绘图指令、注释、图片遮罩/旋转或图片不可信时保持整页渲染或明确阻断，不把部分文本发布为完整 READY；插图解码失败则尝试整页渲染。
- `pdf-text-v16-pdfrenderer` 与 `document-units-v4` 必须使旧规划失效并重新计算；用自造 fixture 和假视觉后端验证，不将其当作真实 Provider 或物理设备验收。

## 2026-09-28 PDF 文字快路径补充验收

- `pdf-text-v18-pdfrenderer` 应使旧 PDF 解析结果（含本地过渡版 v17）失效。
- 字形名：`acircumflex`、`Scedilla`、`uni00E9`、`f_f_i`、`one.oldstyle`、`dotlessi`、`u1F600` 等按字形列表规则解码；未知名称只影响实际显示该字节的页面，且不回退 BaseEncoding；小写十六进制、代理区、越界或长度错误的 `uni`/`u` 名称不得认证文字。
- ToUnicode：简单字体与 `/Identity-H`/`/Identity-V` 的 `Type0` 字体按 CMap（含 Flate、数组 bfrange）发布文字，且 ToUnicode 覆盖 Differences；缺失代码、私用区、U+FFFD、孤立代理、奇数字节、非 Identity CID 编码、名称形式 ToUnicode、`usecmap`、数组长度不符、源代码宽度不一致或畸形 bfchar 均保持整页视觉。
- `sc`/`SC`/`scn`/`SCN` 的一至四个分量及可选图案名不使文字不完整；缺少分量或超过四个分量仍不完整。
- 细轴对齐线（含脚注短线、穿字位置横线、竖线与折线）、矩形边框、细填充条、白色矩形、整页背景、纯裁剪路径和标记内容不触发视觉；粗线（含 CTM 放大后）、斜线、曲线、非白色面板、非矩形填充、文字对象内改回非白色后的填充、`sh` 渐变、填充描边的黑色方块、超过 96 条路径仍需视觉。
- 无外观流 Link 批注在 `/BS /W 0` 时不可见；`/BS` 缺 `/W`、引用形式、非零宽度或带外观流仍需视觉。真空白页和有效空压缩流无需视觉，无效压缩流或可见批注不能当作空白，整份空白 PDF 不发布合成页码。
- 用户 294 个 PDF 的本地解析应为 11,896 页中 2,941 页需要视觉，其中指定 174 页 PDF 为 6 页（4 图、2 FreeText）；转为文字的非空白页逐页对照独立解析器。此项不替代真实 Provider、物理设备与整包耗时测试。

## 2026-09-28 插图单独视觉与重复图片复用补充验收

- `pdf-text-v19-pdfrenderer` 应使 v18 及更早的 PDF 解析结果失效。
- 插图单独视觉正例：装饰线与颜色算子并存、嵌套 `cm`、宽高不同的缩放、矩形裁剪与标记内容、ICCBased N=3、所有属性安全的 ExtGState、不可见 Link 批注；这些页不得产生 PAGE 阻断。反例保持整页视觉：翻转、旋转、越出页面、彩色面板、曲线、`Tr` ≥ 4、CMYK、SMask、ICCBased N=4、页面 `/Rotate`、可见或无法解析的批注、透明度/软遮罩/混合/传递函数 ExtGState、资源中未声明的 ExtGState。
- 重复图片：同一文档两页同图只派发一次；之后导入的另一文档含同一图片时不再派发；只差一个像素的图片必须另行派发；每份文档的检索结果带各自正确页码。只复用 SUCCESS。
- 用户整包本地估算约 2,630 次视觉请求（v18 为 2,941）。此项不替代真实 Provider、物理设备与整包耗时测试。

## 2026-09-29 知识库导入吞吐与 PDF 解析补充验收

- 同一文档有多个独立视觉单元时，用阻塞式假后端证明至少两项请求实际重叠；同一批次在途请求不得超过持久化的 1—6 路策略上限，槽满只能等待，不得把批次误标为暂停。相同输入的并行单元只派发一次；不同页的结果和引用仍按各自页码保存。
- 暂停或取消后不再发起新请求，已派发请求的明确成功结果仍持久保存；杀进程并恢复后仅重做未完成单元。授权目标、资料范围或 generation 变化时停止后续派发；任一视觉单元未成功前，整份文档不得发布 READY 或进入可检索的 active generation。
- 原生文字分块与已获授权的本地嵌入可在等待视觉结果时准备，但 API Embedding 未获单独同意不得外发；最终文档版本和索引可见性仍由同一发布屏障控制。用慢视觉假后端与嵌入计数证明实际重叠，失败后无部分发布。
- 使用自造的 Standard Security V4/R4 空密码 PDF 验证密码认证与文字层提取；错误密码、未支持的密码/crypt filter、损坏内容流均不得被宣布为完整原生文字。实际调用的嵌套 Form XObject 中的文字和图片必须进入页面证据，未调用资源不应引起视觉请求；循环引用、资源缺失、遮罩或不支持的图形状态仍需完整页面视觉或明确阻断。Form 中不可见、被裁剪或缺显式字体的字串不得进入原生索引，即使整页另有视觉识别。
- 用户 294 个 PDF 的离线解析只用于核对需视觉页、文字完整性和本地耗时；真实 Provider、物理设备及 K06 300—500 文件恢复/耗时矩阵分别记录，不以假后端或桌面 JVM 结果代替。


## 2026-10-02 隔离 Python 附件修复增量

S03/S04/S05/S07 增量必须通过真实 isolated worker：API26 JSON/新 PID；STORED 与 DEFLATED 同源结果；固定标准库实际输出；原包 hash 和执行副本 hash 分别篡改拒绝，副本在 ACK 前与 CPython 前校验；取消、超时、日志/输出限额及下一调用新进程恢复；Broker 保留原包与 live grant 校验。禁止用进程未就绪的超时冒充脚本已执行的恢复通过。

详见 [ADR 0019](adr/0019-python-isolated-api26-and-fixed-stdlib.md) 与 [附件修复处置及证据](evidence/2026-10-02/attachment-repair.md)。真实 Provider/真机/USB/Shizuku/长跑验收单独报告。


## 2026-10-02 限时优化增量

- L01—L04、`check/reviewGate`、严格依赖校验继续作为发布前门禁；输入的固定 URL/SHA/来源限制与 AGPL-3.0-only 不变。
- 固定摘要下载覆盖合法缓存零网络/mtime 不变、首次空目录、503/连接拒绝/截断恢复、3 次耗尽、摘要/404/TLS/被文件占用的父路径直接失败、旧缓存保护、临时清理、退避与传输取消保留 interrupt 且不发布新文件。
- 真实迁移后 SQLite 覆盖空输入、重复/未知/类似 SQL 的 ID、1,201 条快照超过旧 999 参数限制的分批查询，与完整快照映射一致且只选两个列。损坏的非显示元数据不妨碍显示投影，但完整读取和执行解析仍拒绝损坏快照。
- 结果和未测范围见 [限时优化证据](evidence/2026-10-02/deadline-optimization.md)。查询数量不替代设备帧耗时、ANR 根因、长稳或真实收费 Provider 验收。

## 2026-10-03 周额度约束优化增量

- K01/K06：真实迁移 SQLite 比对旧 KB 名称/计数与新汇总，覆盖零文档、软删除 KB/文档、重复名称、created_at 排序、256 KB。旧 257 次显示查询对比新 1 次，不替代真机帧耗时或导入吞吐测试。
- K02：KB 任务筛选结果与全局列表内存过滤逐字段/顺序一致，保留 Vision/Embedding 同意、UNKNOWN_OUTCOME、视觉缺口；2,003 行对比只物化选中 3 行。未知/类似 SQL 的 KB ID 为绑定参数；未选中损坏行不解码，选中/全局损坏行仍拒绝。
- Schema v29：合成旧 v28 fixture 升级保持任务逐字段、同时间戳顺序、UNKNOWN_OUTCOME 和同意字段；真实 SQLite 查询计划必须 SEARCH USING idx_import_jobs_kb_updated 且无全表扫描或临时排序。空库/重复升级、索引 DDL 后注入失败回滚旧版本和全部任务、可重试升级、v28 未绑定 Thread 与原 grant 不变均需通过。真实用户原库迁移仍单独验收。
- A06/U07：1,201 Thread/5 workspace 显示解析 5 次，binding 逐线程保持；空列表、未绑定、null/空标题、绑定读取异常和标题异常、下一刷新重命名/删除/恢复均覆盖。仅显示缓存，不授予任何执行权限。
- L01—L04、严格 check/reviewGate/REUSE 和常规 CI 持续作为交付门禁。该阶段的设备/Provider/长稳边界与本轮事实见 evidence/2026-10-03/quota-optimization.md。

2026-10-03 五项复审新增边界：K02/K05 检查旧 EPUB v3 不复用为完整 READY、明确文本缺口、查询确定失败释放本次 claim、UNKNOWN/取消仍受明确授权门禁、旧 owner 不覆盖新授权且有效缓存不重发；S11 检查撤权前零工具派发和派发后结果扣留/UNKNOWN；S18/S31 检查 SAF 已创建目录核验失败与工作区 TERMINAL 审计失败均保留 UNKNOWN、内容/目录留存、熔断及防重放。验证状态见 [五项复审修复](evidence/2026-10-03/five-review-fixes.md)，不据此标 §14 全量验收通过。

2026-10-05 A10/R02/R03/R12 兼容性补充：分别通过 Chat 和 Responses 的公开连接测试验证 404 的缺失/null/空/非字符串 code、明确模型/路由 code 优先级，以及仅 type/param 的普通 400 参数错误。认证/限流/超时仍优先于响应正文；错误结果不得携带 raw body。基础 Chat 连通请求不带可选采样、停止与高级参数，实际能力探测保留它们；模型编辑空 ID 沿用已有 ID 并保持同一配置身份。实测状态见 [兼容性修复证据](evidence/2026-10-05/provider-compatibility.md)，本地 MockEngine 成功不等同于真实服务连接或全量设备验收。

2026-10-05 回归补充（A03/A04/A10/U02/U05，见 [ADR-0021](adr/0021-provider-catalog-and-image-probe.md)）：Command Code 的 AUTO 窗口通过公开目录精确模型记录得到，来源为 PROVIDER_METADATA；无凭据、无生成调用、错误/重复/畸形/超大目录及相似域名保持未知。保存和旧配置连接测试能写入窗口，晚到目录不能覆盖手动值；目录等待不阻塞编辑，离线改名保留同目标窗口，目录/本地重读失败不改写付费结论。Chat/Responses 图片探测使用可解码 128×128 PNG，上限 1024，较小手动限制仍生效；预算截断 UNKNOWN、畸形 FAILED、不自动收费重试。精简页面后，费用确认、外发目标、授权边界和窗口来源仍可见；离线/模拟器与真实 Provider 结果分别记录。


## 2026-10-05 464f8f4 复审与正式发布增量

- C22—C25/A06：可控时钟触发 watchdog 后切换 B 或返回 A，再投递迟到 RequestPrepared、错误、真实审批回调与 finally；当前会话 ID、消息、preview、审批和草稿不变，A 的真实结果仍归 A。旧执行未退出前新发送保留输入且零新 Run；旧前台引用不能停止另一所有者。
- K05：成功 query vector 与本 owner claim 清理同事务；真实 SQLite 插入后中断/删除异常回滚、commit 回执中断恢复、旧 cache+pending、错 space/query/维度/NaN/坏字节、consent 撤销、替换 owner 和多库同空间并发。有效成功缓存本地完成且零 provider 调用；无证据未知仍不自动重试。
- S33/S34：URL 2048/2049 字符、长查询和签名参数、百分号编码/Unicode、fragment/userinfo/private 过滤；保留 URL 必须等于完整原值。撤权后的安全反馈和 durable invocation 保留已派发/完成/扣留/可能收费，缓存及重启不重派发、不泄露。
- L01—L04：版本 1.0.0 / code 2，正式证书一致、不可调试、arm64 APK/AAB、native alignment/notice、源码归档/SBOM/provenance/hash 对应干净合并提交；正常 PR/CI 合并、本地 main 同步及 GitHub Release 分别核验。定向测试和正式构建不替代未完成的全量设备/付费服务/长稳验收。见 [修复证据](evidence/2026-10-05/review-464f-fixes.md)。
