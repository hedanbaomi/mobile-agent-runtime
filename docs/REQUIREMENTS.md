<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 需求与决策依据

基线日期：2026-08-30（Asia/Taipei）。本文件记录产品设计依据，不代表任何功能已实现；涉及权限工具时，以 v2 规范为唯一现行语义，历史轮次只保留为时间限定的证据。

## 1. 来源与解释规则

来源是用户指定的 [移动端知识库方案](https://chatgpt.com/c/6a912a23-8e4c-83ea-88fa-6680e18cdf71)，会话 ID `6a912a23-8e4c-83ea-88fa-6680e18cdf71`。以下按对话先后顺序列出轮次，便于其他 Agent 在具备访问权时复核；正文已经提炼实现所需内容，不以重新访问私有对话为开工前提。

| 来源 ID | 用户问题/后续讨论主题 | 轮次 ID |
| --- | --- | --- |
| S1 | 移动端 Skills、本地知识库、导入向量化、用户 API | `bbb21353-d99b-41db-85f5-cabb03b2cd06` |
| S2 | 认可方向并增加系统提示词与模型参数配置 | `bbb21e32-27b8-4575-b0dc-6071b6d1ed14` |
| S3 | 需求复述、Agent 为核心、数据和配置分离 | `bbb21e25-3676-45fb-92fb-a2033859234e` |
| S4 | 含图片知识库的多模态处理、Python 脚本执行 | `bbb21d9a-302c-475e-9f89-d10f7f7e5c97` |
| S5 | Android 优先、未来多端、AGPL 许可及完整实施方案 | `bbb21c49-42a3-4b26-a583-cdf69a8badd3` |
| S6 | 补充强制公告功能及其完整设计 | `bbb21cdb-03e6-447d-b479-69df0779d5d6` |
| S7 | 仓库命名讨论 | `bbb2113c-aa22-458c-a270-2c6d0bda48e7` |
| S8 | 当前任务：落地技术文档、根交接、强制工作规则；补充初始化 Git/CodeGraph | 本工作任务 |
| S9 | 2026-08-28 后续要求：在 M0—M7 中增加一步，专门设计前端页面；用户澄清即设计软件页面 | 本工作任务后续用户消息；M0.5 编号及交付细则为实施补充 |
| S10 | 2026-08-29 后续要求：增加抓取日志功能，用于定位无法稳定复现的移动端故障 | 本工作任务后续用户消息；隐私、大小和导出边界为实施补充 |
| V2 | 权限工具与受控执行 v2 规范：统一 typed tools、Shizuku/Wired ADB 双 Authority、持久危险模式、Windows USB Companion、shell_exec、诊断与 E2E 边界 | `docs/mobile-agent-runtime-authority-tooling-codex-prompt-v2.md` |

来源分三层：**用户明确要求**必须保留；**对话方案基线**按用户本次“依照方案”要求落实；**实施补充**用于填补原讨论的接口、事务和边界细节，不冒称用户亲自选定。后续讨论覆盖前面的冲突建议：S5 的受限纯 Python 优先于 S4 的 Full Python 探讨；S6 的早期公告优先于 S1 的四项极简 MVP；S5 的严格视觉聊天优先于早期默认文本降级。

读取完整性：7轮通过任务读取接口取得；S5长回复超过接口单项长度限制，其里程碑和验收尾部已于本轮通过浏览器可见正文补读。不在仓库保存整段私有聊天原文。S7仅给出候选产品名，没有最终品牌定案。本工作区名 `mobileAgentRuntime` 作为工程名，不擅自选择 Arca 或 AgentDeck，也不推断远程仓库地址。V2 prompt 是当前权限工具的规范基线；旧文档中把无线 ADB、Termux、DPC、Root 或 PTY 写成当前路线的内容均以本文件和 ADR-0004/0005 的更正为准。

## 2. 必须覆盖的需求

| ID | 要求 | 来源性质 | 验收入口 |
| --- | --- | --- | --- |
| R01 | 首版 Android，未来其他端保持可扩展；不要求手机跑完整 LLM | 用户要求 S1/S5 + 方案 | A01、A02 |
| R02 | BYOK，自定义 Provider/Base URL/模型/Header/API 格式 | 用户要求 S1/S2 + 方案 | A03、A04 |
| R03 | 可编辑、版本化、导入导出 System Prompt；模型通用/特有/JSON 参数 | 用户要求 S2 | A04、A05 |
| R04 | Agent Profile 核心、配置快照、Provider/KB/Skill 可复用 | 方案 S2/S3/S5 | A05、A06 |
| R05 | 本地托管文档；TXT/MD/PDF/DOCX/EPUB/常见图片；增量与恢复 | 用户要求 S1/S4 + 方案 | K01、K02、K06 |
| R06 | 含图片必须配置 Vision；处理图表、公式、矢量页面；不得静默丢失 | 用户要求 S4 + 方案 S5 | K03、K04 |
| R07 | 默认本地 Embedding，支持 API；本地 FTS5+向量+过滤+RRF，可选重排 | 方案 S1/S5 | K05、K07 |
| R08 | 引用可定位原文/原图，Token 预算，视觉证据说明 | 方案 S5 | K04、K08 |
| R09 | SKILL.md、资源、Python；兼容性检测、源码查看、安装授权；无清单 Claude Skill 中可兼容的标准库 CLI 必须能在启用/授权/Agent 绑定后成为实际模型工具，不能只展示说明 | 用户要求 S1/S4 + 2026-08-30 人工反馈 | S01、S02、S12 |
| R10 | Python 隔离进程和 Broker；每次新进程；纯 Python；不运行 pip/任意原生代码；程序只能读取本次调用虚拟文件或显式短期句柄，不能直接开放 PowerShell、宿主 Shell 或任意宿主文件系统 | 方案 S5 + 2026-08-30 人工反馈 | S03—S07、S12 |
| R11 | Native/HTTP 工具及 Tool Loop；未来 JS/MCP/Remote 可扩展；工具对 Agent 暴露 provider-neutral wire name，不暴露 backend 名称 | 方案 S1/S3/S5 + V2 | S08、S09、S13、S14 |
| R12 | API 密钥保护、最小数据外发、审计脱敏、导出排除秘密 | 方案 S5 | A03、S10 |
| R13 | 公告中心、横幅、重要弹窗，草稿/定时/发布/撤回/归档/修订 | 用户要求 S6 + 方案 | N01—N04 |
| R14 | 公告平台/版本/渠道/语言/稳定灰度、离线/已读/确认、ETag | 方案 S6 | N02—N05 |
| R15 | 公告独立 Worker+D1+管理端；匿名统计可关闭；不得上传用户内容或下发代码 | 方案 S6 | N06—N09 |
| R16 | 全部第一方代码/文档/公告服务 AGPL-3.0-only，保护防误改 | 用户要求 S5 + 方案 S6 | L01—L04 |
| R17 | 开工读交接，收工维护；本地 Git 和 CodeGraph 初始化 | 当前明确要求 S8 | D01、D02 |
| R18 | 独立软件页面 UI 设计阶段；逐屏设计布局、视觉样式和控件，交付高保真页面稿及可编辑源稿，再落实到软件 UI | 用户要求 S9及澄清；阶段细则为实施补充 | U01—U06 |
| R19 | 用户主动开启的本地诊断日志；有界滚动、字段白名单和秘密/URL/路径脱敏；支持 SAF 导出与清除，用于间歇性故障取证 | 用户要求 S10；隐私和崩溃处理边界为实施补充 | A08、S10、U05 |
| R20 | Agent 在逐次确认、Agent/快照复核与硬配额下使用 provider-neutral typed tools 访问应用私有工作区或用户明确选择的 SAF tree；不得泄露 Android 真实路径、跨 Agent/快照访问或借此获得 shell。SAF 是 workspace backend，不是 elevated Authority | V2 + 2026-08-30 第四轮人工反馈 | S13、S14、S19 |
| R21 | 统一 Authority/Capability/Approval/Tool Loop 模型；当前 elevated Authority 仅有 `SHIZUKU` 与 `WIRED_ADB`，持久 grant、availability、connection 和 selected provider 分离；Shizuku 与 Wired ADB 平级，失效时 fail-closed，绝不自动 fallback | V2 | S15、S16、S20、S21 |
| R22 | Windows Desktop Companion 仅作为有线 USB ADB 的受控 backend：官方 adb、`adb reverse`、loopback、一次性配对/挑战、会话序号/HMAC、固定 protocol；不接受 host shell、PowerShell、serial、端口或 raw command 由 Agent 指定 | V2 | S17、S21 |
| R23 | Dangerous Mode 持久保存至用户显式关闭；提供 `ENABLED_CONFIRM_HIGH_RISK` 与 `ENABLED_AUTONOMOUS` 两档；仅在选定 Authority、Agent capability 和策略均允许时暴露 `shell_exec`，执行 Android 端一次性 `/system/bin/sh`，不做 PTY，不把风险检测器伪装成 allowlist | V2 | S15、S17、S18 |
| R24 | 权限与危险模式安全证据必须在 `debuggable=false` 的 review-like build 上复核；debug/JVM/automated evidence 不得替代真实 Shizuku/USB E2E；硬件或 Companion 缺失统一标为 `E2E BLOCKED`，不虚报 `DEVICE_PASS`/`RELEASED` | V2 | A09、S20、S21 |

## 3. 对话技术基线

Android API 26+；arm64-v8a 正式、x86_64 模拟器；Kotlin + Compose；KMP 共享领域/协议而非 UI；Ktor + kotlinx.serialization；SAF；Keystore；Bundled SQLite/FTS5；USearch HNSW/JNI；ONNX Runtime Mobile Model Pack；PdfRenderer 与可替换 PDF 解析适配；CPython Android 3.14.x 经验证后锁定补丁版本；前台导入和持久补偿；Worker + D1 公告服务。

这些选型是待实现、待验证的方案，不能写成已构建成功或已经通过真机测试。精确 SDK、插件、模型包、ABI、资源预算必须在 M0/对应 spike 锁版本、记录 SHA 和验证证据。

## 4. 实施补充与待决定项

以下接口和策略是为可执行交接补充的默认设计，可经 ADR 调整，但不得降低 R01—R19：

| 编号 | 实施补充/待决定项 | 处理方式 |
| --- | --- | --- |
| D01 | 指纹化向量空间、SQLite 为真值、索引代际事务和删除隔离 | 按知识库文档默认实现并验证 |
| D02 | 逻辑 schema、端口结果类型、任务错误码、预算默认值 | 作为 v1 契约；代码落地时补机器 schema/迁移 |
| D03 | 公告签名信封、完整快照/撤回语义、统计去重口径 | 按公告文档；签名/缓存跨端测试后定版 |
| D04 | 生产包名、GitHub owner/repo、CODEOWNERS 主体、正式品牌 | 所有者确认前不得猜测或发布 |
| D05 | SDK/Gradle/Kotlin/依赖精确版本、Embedding 包及其许可 | M0/技术验证锁定；不以“最新版”代替版本锁 |
| D06 | Cloudflare 账户、独立资源名称、域名、管理员身份、密钥轮换 | 部署任务另行授权；当前不创建资源 |
| D07 | 统计默认关闭、保留期、安装 ID 重置；是否需要额外隐私要求 | 采用最小数据默认值；上线前所有者确认 |
| D08 | 导入体量已定为300—500个文件、约300—500 MB；具体设备和耗时/温度/内存阈值待定 | 保留该负载验收；先测量形成性能基线，不把早期耗时猜测当 SLA |
| D09 | 软件 UI 设计阶段编号、页面稿/源稿交付格式、组件 token、原型和评审流程 | 按 ADR-0002 插入 M0.5，原 M1—M7 不改号；实际页面布局与视觉方案在该阶段设计并由用户确认 |
| D10 | 诊断日志默认关闭；仅记录固定事件和匿名状态；当前段/上一段/崩溃/单事件/导出上限分别为 256 KiB/256 KiB/32 KiB/4 KiB/640 KiB；原生崩溃/系统强杀仍需 ADB Logcat | 按 [诊断日志契约](DIAGNOSTICS.md) 实现并以 A08/S10 验证，不把日志当遥测或用户内容备份 |
| D11 | 权限工具、审批、断连恢复、超时/取消/未知结果和危险模式都必须有闭合诊断事件与隐私边界；不记录命令、路径、URI、serial、stdout/stderr、token 或自由文本 | 按 [诊断日志契约](DIAGNOSTICS.md) 与 [验收矩阵](ACCEPTANCE.md) 验证；失败只产生有界 drop/degraded 状态 |

## 5. 文档任务范围与非目标

S8 初始文档任务只创建工程文档、许可说明、Agent 规则，并初始化本地 Git/CodeGraph；后续实现状态以 HANDOFF 为准。S9 本次只修改技术设计文档和阶段/验收安排，不制作实际页面、原型或业务代码，不执行付费模型调用、不自动执行外部 Skills、不访问占卜产品仓库、不改生产，不提交或推送。

首版不承诺：完整 PyPI、宿主 PowerShell/宿主 shell、Node/Docker/Termux 执行、无线 ADB、DPC、Root、PTY、本地完整 LLM、自动 Skill 下载/商店、任意远程代码、跨设备同步、多模型对比。v2 的 Android `shell_exec` 仅是选定 Shizuku 或 Wired ADB Authority 下的显式 Dangerous Mode 能力，不改变 Skill/Python 的宿主隔离边界，也不等于开放宿主 shell。后续扩展必须单独定义范围和验收。
