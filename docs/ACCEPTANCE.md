<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 验收矩阵与证据要求

本文件定义**设计与实现的验证方法**，矩阵本身不代表已执行。各项实际状态和证据以 [HANDOFF.md](../HANDOFF.md) 及对应验证记录为准；M0.5 软件页面 UI 设计基线已完成交付并满足 U01—U06 设计审查要求（见 [docs/UI_DESIGN.md](UI_DESIGN.md) 与 [docs/design/ui-tokens.json](design/ui-tokens.json)，状态为 `DOC_CHECK_PASS`），实际业务实现与设备验证随各后续阶段展开。

## 1. 完成状态

`NOT_STARTED`尚未做；`IN_PROGRESS`已开始；`IMPLEMENTED`有实现未充分验证；`AUTOMATED TESTED`指定自动化测试通过；`LOCAL_PASS`指定本地测试通过；`DEVICE_PASS`指定真机通过；`E2E BLOCKED`实现/自动化证据存在但真实端到端所需硬件、服务或授权缺失；`REVIEWED`独立审阅通过；`DEPLOYED`已授权部署且有目标证据；`RELEASED`已授权发布且完成后检。

状态可以分别记录，不要求假装线性升级。`IMPLEMENTED`、`AUTOMATED TESTED` 与 `E2E BLOCKED` 必须分开填写；阻塞记原因和缺失条件，不能把未执行记为PASS。设计校验通过只记`DOC_CHECK_PASS`，不代表M0完成或产品可用；M0.5 还须有用户对页面与交互方案的确认记录。原型走查不能代替真实 Compose/管理端实现及设备验证。Debug APK、JVM 测试或静态检查不能替代 `debuggable=false` review-like build 的安全证据。

## 2. 文档和许可证

| ID | 场景/操作 | 预期与证据 |
| --- | --- | --- |
| D01 | 从AGENTS入口按顺序阅读；核验全部相对链接、Markdown fence和需求ID | 无断链；工作前读HANDOFF、工作后维护是明确硬要求；计划目录标为未实现 |
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
| U02 | 逐页审查排版、颜色、字号、图标、间距、组件状态、明暗主题稿，并走查紧凑竖屏/横屏/宽屏、软键盘和大字体 | 页面视觉一致，标注和 token 有具体值/版本；交付可编辑源稿及素材使用说明；关键内容/操作不被遮挡；触控尺寸、对比度、读屏标签与焦点顺序写成可验证要求，记录原型无法验证的设备项 |
| U03 | 按逐页矩阵切换空数据、加载、成功、失败、离线、未配置/无权限、运行中、取消、重试；走 Vision 等待、上传拒绝、流式中断、结果未知和预算耗尽 | 状态、原因、可执行下一步和禁用操作齐全，与领域状态对应；不把等待/部分结果冒充成功；不适用状态明确说明 |
| U04 | 使用页面实际布局和样式的原型走 Provider/Agent/知识库/Chat 闭环、引用回跳、Skill 安装/授权/撤销、公告阅读/确认和设置操作 | 关键成功与失败路径可点击重现；原型与高保真页面稿一致；自造数据/模拟状态醒目标识；无真实模型调用、文件上传、权限授予等副作用 |
| U05 | 审查密钥表单、有效请求检查、视觉/Embedding 外发、工具确认、删除/导出、公告统计开关等页面与文案 | 密钥默认遮蔽且示例无真实秘密；用户能看到数据发给谁、哪些数据及费用/权限影响；拒绝可退出且无隐式授权；纯文本降级需显式选择；设计与 A03/K03/K04/S09/S10/N08 安全要求一致 |
| U06 | 用户逐页评审外观/布局/操作并确认修订；接手者依据页面稿、源稿、标注、组件和映射定位实现任务；对照现有 M1 页面列差异 | 第8节高保真页面稿与配套产物齐全且版本一致；用户确认及修改记录可追溯；缺失接口/待定品牌显式登记；未通过项未伪装完成，不能把设计通过记成业务或设备通过 |

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
| K02 | TXT/MD/PDF/DOCX/EPUB/独立图片、坏文件、超限ZIP/遍历路径 | 支持格式分别给证据；坏/不支持文件显示原因，不外联加载资源；无越界写入 |
| K03 | 含扫描图、矢量流程图、公式缺Vision；纯文本选择API Embedding但未同意；换Provider/域名/模型/数据范围 | 视觉和Embedding分别等待授权；拒绝/过期同意时外发请求数为0；变化后重确认；不丢图、不报READY、不自动换Provider |
| K04 | 原图命中，严格模式配文本Chat；再显式启用文本降级 | 严格模式拒绝；主动降级后回答醒目说明无原图；引用可回页码/图片 |
| K05 | 中英文专名、表格、代码query；不同space/维度KB；模型不可用 | 词法/向量/过滤/RRF生效；空间不混算；不可用库明确告知；记录召回样例与不足 |
| K06 | **300—500文件、总计约300—500 MB**；导入各阶段杀App、重启、离线、超时、磁盘满、取消 | 检查点继续；成功图片不重发；云端不确定结果提示重复收费风险；无重复记录、无虚假READY |
| K07 | 破坏/删除测试索引；模拟文件写完SQL未切换及反向故障；删除文档后旧索引仍在 | 从SQLite重建；保持旧有效代际；不返回已删/未授权/未发布数据；数量/哈希一致 |
| K08 | Token预算不足、原图过大、无命中、模型虚构citation ID | 明示证据缺失，不自动去图；未知引用不生成假链接；可追溯chunk/version/asset |

K03/K06 追加 API 查询门禁反例：外部请求未知后，重启并重复同 KB/完整空间/查询时请求数不增长；知识库页批准只写一次许可、不立即外发；用户重新提交仅放行一次，再次未知重新等待。不同 KB/空间/query hash 不共用许可。取消/中断仍保留未知记录，UI、内置工具、Python Broker 都不得将其当普通可重试失败。无同意调用公开重建/修复入口也必须零外发。检查 schema v8→v9→v10 数据保留、重复迁移、复合主键/外键/布尔 CHECK，原始查询不得写入门禁表。外部 query vector 已成功后若 generation/chunk/vector blob/native index 等本地检索失败，门禁必须保留且下次复用已校验向量，API 请求数不得增长；只有完整 retrieve 成功才清理尝试与缓存。

A05/S10 追加取消竞态：流式中途取消保留部分回答并关闭连接，可能已受理的模型/工具调用为 UNKNOWN；在已观察到完成事件后才取消，不得覆盖 COMPLETED。Python 同一次未知结果经过 Broker、runtime teardown、取消多次上报时，仅保留首次具体原因和一条 invocation UNKNOWN 审计。

K06负载必须使用自造/许可允许的fixture并记录SHA清单。记录设备型号/Android/API/ABI、文档类型分布、图像数量、峰值内存、磁盘、处理耗时、耗电/温控和API次数。耗时/内存阈值待基线实测后由所有者认可，不用未经测量的“几分钟”承诺。

K06另需前台任务兼容矩阵：Android12+后台启动限制、Android14+服务类型/权限、Android15适用类型/targetSDK下累计6小时限制与onTimeout、Android16作业配额。逐项记录系统API、targetSDK、实际serviceType/权限、持续通知、要求时限内（通常5秒）提升前台、超时停止/检查点/用户恢复和WorkManager补偿。用受控测试环境验证，不为测试修改用户主设备；不适用项必须写明版本与原因，不能统写通过。启动被拒绝或配额耗尽时不得丢任务或进入无限重启。

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

2026-08-31 v2 本地收敛证据：严格全仓 `check`、Debug evidence gate 与 `debuggable=false` Review gate 全部通过；Debug/Review 均生成 171-component CycloneDX 1.6 SBOM 和 SHA-bound provenance；REUSE、AGPL/license 正反向、Actions pin、28 个 lockfile、root+included-build strict dependency verification、148 Maven + 4 native/model 成品 notices 均通过。最终 Debug APK 已安装到 API 31 x86_64 并确认首次浅色主题；v2 instrumentation 按 Authority/Workspace/Memory、Tooling/Navigation/Search、UI/Release Gate、Diagnostics 分批执行。准确测试数、产物 hash、命令、独立只读复核与外部 `E2E BLOCKED` 列表见 [authority-tooling-v2-final](evidence/2026-08-31/authority-tooling-v2-final.md)。该证据是 dirty-source 本地候选，不代表正式签名、发布或生产部署。

2026-09-01 新诊断复现的根因是 typed workspace 在 canonical grant 与 snapshot binding 通过后仍创建第二份进程内批准，导致三次 `workspace_read` 停在重复确认而未派发。修复后持久授权直接进入实时复核；同 model call 并发只派发一次，live policy revision 变化立即撤销旧 executor 权限，STARTED 后异常按 `UNKNOWN_OUTCOME` 终结且不重放。API 31 `ToolingOrchestrationTest` 37/37、`DiagnosticsDeviceTest` 12/12，全仓 1078-task build 与 592-task Review gate 通过，第二轮独立只读复核 `PASS`。准确产物哈希和边界见根目录 `HANDOFF.md`；这仍是 dirty-source 本地候选，不是正式 release。

2026-09-01 最新真实 E2E 复核：系统 DocumentsUI 对 `Download/mar-workspace` 的持久 SAF grant 和官方 Shizuku 13.6.0 均在 API 31 x86_64 模拟器实际配置。真实红灯定位为审计空 workspace、SAF tree/document URI、SAF mutation handle、Shizuku 根 list 路径和首次并发 bind 等独立边界错误；公开 schema 与 Wired expectedVersion 的静默忽略也已 fail-closed 收口。修复均只在对应 API 边界规范化；具体操作审计、路径隔离与 SAF 既有文件非原子覆盖拒绝未放宽。最终首次无预热 Shizuku bind 1/1、SAF 2/2、模型侧 Shizuku 1/1、Shizuku UserService 2/2，完整 connected matrix 235 tests 全通过，另有 1 个未显式启用的受控大负载 Knowledge skip。准确步骤、命令、哈希和 Review 边界见 [2026-09-01 真实工作区 E2E 证据](evidence/2026-09-01/workspace-tool-real-e2e.md)。物理 USB Companion、物理断连恢复与非模拟器设备差异仍保持 `E2E_BLOCKED`。

2026-08-30 第二轮人工反馈包补充验证：API 31 x86_64 上 `DiagnosticsDeviceTest`、`ReleaseGateUiDeviceTest`、`NavigationScopeTest` 合计 17/17、0 failed；`check --dependency-verification=strict` 936 tasks 通过。覆盖诊断启停/边界、More 二级返回、公告配置隐藏、无智能体尺寸和稳定导航 owner；ZIP/Skill install/批次调度由共享与 SQLite 测试覆盖。用户实际 294 个 PDF 的完整耗时及真实 Provider 跨页流仍保留为人工终审，不据此标 K06 或正式 release PASS。证据见 [manual-review-round-2-fixes](evidence/2026-08-30/manual-review-round-2-fixes.md)。

2026-08-30 第三轮能力反馈包补充验证：API 31 x86_64 上 `NavigationScopeTest`、`WebSearchDeviceTest`、`PythonRuntimeDeviceTest`、`PythonSkillToolDeviceTest`、`DiagnosticsDeviceTest`、`ReleaseGateUiDeviceTest` 合计 34/34、0 failed；`check --dependency-verification=strict` 936 tasks 通过。`PythonSkillToolDeviceTest` 覆盖真实导入、启用、grant、Agent snapshot、模型可见 ToolSpec、逐次审批和 isolated CPython 虚拟文件执行；联网响应覆盖活动 key 脱敏。未调用真实 Brave/付费 Provider/Vision，也未跑用户 294 个 PDF 全量耗时，因此仍不是完整 K06 或正式 release PASS。证据见 [manual-review-round-3-capabilities](evidence/2026-08-30/manual-review-round-3-capabilities.md)。

2026-08-30 第四轮人工反馈先完成三项可验证修复：长工具确认卡可在固定操作区上方滚动、Provider 两个预算输入可完整清空后再校验、首次/缺失/非法主题回退浅色且保留用户显式选择。Agent 文件能力只新增 S13 的应用私有文本工作区，API 31 x86_64 `WorkspaceAppToolsTest` 6/6；SAF、Termux、无线 ADB、DPC、root/Shizuku 和任意 shell 均未实现。最终全仓门禁、APK hash 与剩余边界见 [第四轮证据](evidence/2026-08-30/manual-review-round-4-ui-workspace.md)。

上述第四轮记录是当时的时间限定证据，不是 v2 规范。v2 当前只保留 `SHIZUKU` 与 `WIRED_ADB` 两个 elevated Authority；无线 ADB、Termux、DPC、Root 与 PTY 仍为明确排除项。后续如有源码或测试进展，必须在本矩阵新增证据并分别填写 `IMPLEMENTED`、`AUTOMATED TESTED`、`E2E BLOCKED`，不得回写历史证据为真机 PASS。

实现后在`docs/evidence/日期-任务ID/`保留脱敏报告、测试清单及允许公开的截图/日志。每份报告包含：需求/验收ID、范围、工具/依赖/SDK版本、Git SHA或未提交状态、命令与退出码、fixture哈希、观测结果、失败/剩余风险、审阅结论。敏感原始材料只放`.private/`，不在公共报告链接私有用户内容。

证据链必须可追溯：**观察证据（命令/截图/测试）→ 结论（通过/不通过/待验证）→ 下一步（可执行修复/验证动作）**。独立审阅者只读检查作者证据、契约和关键路径，不接受“作者说已测”作为唯一证据。

使用现有可复用命令，并在各工作包记录准确命令与执行结果；缺少的测试或设计证据明确记为未执行。不为了绿灯建空测试，不用原型或 fake server 的结果冒充真实 API/真机/生产结果。

## 8. 发布门槛

必须满足功能范围、ABI/设备、安全、迁移、许可和隐私检查；M0.5 设计及后续 U 系列适用项必须有证据，M6未完成不能称功能完整MVP；Dangerous Mode 的安全门禁必须在 `debuggable=false` review-like build 上通过，不能用 debug APK 替代；Shizuku/Wired ADB 缺少真实端时只能记 `E2E BLOCKED`。M7发布准备通过也不代表授权部署或发布。发布任务另外记录目标、源码SHA/tag、产物hash、签名身份、依赖/SBOM、迁移备份、回退方法、用户授权和后检结果。
