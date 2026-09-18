<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 第二轮代码审查与完整模拟器用户测试

**结论：NEEDS_AMEND。本轮审查、可执行的完整模拟器路径及收尾已完成；不表示全部功能通过。** 测试时间：2026-09-12 至 2026-09-13（Asia/Taipei）。本轮没有修改产品源码，没有提交、推送或发布。

先审查代码，再按前轮 42 项用户路径实测，并额外覆盖 SiliconFlow 与 DeepSeek 官方。**未复现 DeepSeek 官方基础连接失败**；连接、流式聊天及连续工具调用均成功。但复现了能力探测假阴性、含会话备份恢复崩溃、特定上下文压缩失败、空 SAF 目录工具缺口，以及第三方声明无法列出。Windows Companion 在本机文件身份校验处阻塞。详见下面的证据和限制。

## 1. 被测版本、方法与证据口径

- 源码：main 的 **0c02b32e06f9f5ace7a0cfdf44bb7725898ee1a3**；审查范围 f1feffe..0c02b32，共 39 个文件。隔离构建目录：E:/mobileAgentRuntime/.private/qa-round2-main-0c02b32。
- review APK：app-android/build/outputs/apk/review/app-android-review.apk；SHA-256 **35dd646ba74cb2c940a23a7675a50992a4f08b5fa005966f41f542a411f83d61**。non-debuggable、DB schema 18、built 2026-09-12T15:50:15Z。
- 构建执行 reviewGate、Companion installDist、strict dependency verification，最终退出码 0。JVM 报告合计 120 suites / 928 项 / 0 failure、error、skipped，含变体重复运行；SBOM 171 components。门禁通过未覆盖本轮新发现的 Android 恢复崩溃。
- provenance 如实保留 gitDirty=true：隔离树中 Git 唯一状态文件 Bouncy Castle LICENSE.html 与 HEAD blob 完全同字节，1175 B，SHA-256 edbbb10380b1271998b867a2e36b1cbee226e03d438726e1a91f80c5dde11849；core.autocrlf 状态漂移没有被伪装成 clean。
- emulator-5560：保留前轮数据后升级，用于官方 DS 与跨安装恢复；emulator-5562：独立干净安装，用于完整 SF、Skills、知识库和权限路径。均 Android API 36 x86_64。
- 主 Agent 通过真实 UI、ADB 输入/系统文件选择器、Computer Use 处理授权及输入障碍；未通过修改应用数据库制造测试状态。请求检查器核对真实工具、参数、返回值及引用；应用诊断 ZIP 与 Logcat 分阶段保存。宿主 HTTP 仅作为同构请求的补充对照，**不是设备原始响应抓包**。
- 独立 DSH 审查使用 Codex iab，确认 E:/mobileAgentRuntime 对应隔离源码与 **DeepSeek V4.1 Flash**。权限改为 **工作区内修改**，任务文本限定只读，Pwsh 已实际工作。此前“仅可查看”模式的 Pwsh 问题属于 DSH 测试工具链，不能据此认定 Android shell 不可用。
- Git Bash 经宿主转接执行，uname 为 MINGW64_NT。构建曾受 CRLF、模型下载 429、Windows AIDL 生成注释反斜杠影响；最终同 SHA 在新隔离树重生成并完整通过门禁。没有跳过许可/依赖校验。
- 原始资料：用户指定目录中的两份 Skill、Class B 原目录 12 项及 294 PDF（315,435,736 B）。末次哈希核对均未改变，受保护 WIP 也没有变化。

私有证据根目录：[userqa-round2-20260912](../../../.private/userqa-round2-20260912/)。下文 **old N** 指根目录 ui/N*.json，**fresh N** 指 fresh/ui/N*.json，**restore N** 指 restore/ui/N*.json。所有快照有 UTC、serial、实际 UI 文本与坐标。完整机器矩阵见 [matrix-final.json](../../../.private/userqa-round2-20260912/matrix-final.json)。原始长提示、资料正文、工具 stdout 只保存在私有证据中。

## 2. 已确认的问题

### R2-01 / P1：含会话的备份导入直接使应用崩溃

**步骤**：fresh 选择 R2Explorer，导出包含 2 Skills、3 KB（6 docs）及 7 conversations 的完整 ZIP；在相同 APK 的另一安装 emulator-5560 通过 Settings → Import 导入。

**实际**：restore 007/008 退回 Android 桌面。18:07:30Z 的 Logcat 报 java.lang.NoSuchMethodError：java.nio.file.Files 缺少 readString(Path)。定位到被测源码 **data/sqlite/src/main/kotlin/runtime/mobileagent/data/TransferRepository.kt:439**，调用 TransferCodec.decodeConversation(Files.readString(it), …)。设备 API 36 不提供该实际调用的方法；错误穿出导入流程。

**影响**：备份 ZIP 本身 CRC 与结构合法，含会话时仍不能跨安装恢复。重启后旧 DS 会话及旧 Agents 保留，未出现新 R2Explorer，当前观察支持该次导入回滚；不扩大成所有数据库数据都已逐项审计。

**对照**：同一 Agent 不含 conversations 的资源 ZIP 在 restore 041 导入成功；2 Skills 绑定按本地安装映射，3 KB 本地重建，实际检索、read_document、Python 审批执行成功。资源-only 成功不能替代完整备份恢复。

**证据**：[crash-buffer.log](../../../.private/userqa-round2-20260912/restore/crash-buffer.log)、restore final-diagnostics.zip 的 last-crash.ndjson、fresh/full-backup-validation.json、restore 041/067/072/078/100/108–110。建议用 Android 支持的有界 UTF-8 读取路径，并增加非 debug APK 的含会话跨安装恢复验收；本轮未修复。

### R2-02 / P2：DeepSeek 工具探测显示不支持，真实工具循环却成功

deepseek-flash 的 Metadata/Streaming 探测通过，Tools 显示 Unsupported（old 100）；同配置真实两次 calculator 调用均成功（old 123）。补充同构探测请求默认思考模式返回 HTTP 400：Thinking mode does not support this tool_choice；显式 thinking disabled 后 HTTP 200、tool_calls、mar_probe_noop。

被测 **OpenAiCompatibleAdapter.kt:950–1015** 构造能力探测时未合并 Profile 模型参数，并强制 tool_choice。探测结果与实际用户配置能力分离。建议按模型参数与服务商约束构造探测，区分“探测方式不兼容”和“模型不支持工具”。

另一个独立现象：deepseek-v4-flash 可以聊天，但 GET /models 中没有该别名；metadata 精确匹配失败会提前停止后续探测（old 052）。改用当时官方列表的 deepseek-flash 后元数据通过。**这两个现象都不是基础网络连接失败。**

证据：[deepseek-probe-control.json](../../../.private/userqa-round2-20260912/deepseek-probe-control.json)、deepseek-metadata-supplement.json、provider-diagnostics.zip、deepseek-tool-chain-proof.json。官方思考模式说明见 [DeepSeek 文档](https://api-docs.deepseek.com/guides/thinking_mode/)；工具选择兼容性见 [官方集成说明](https://api-docs.deepseek.com/quick_start/agent_integrations/oh_my_pi/)。

### R2-03 / P2：压缩候选只剩无信息的 assistant ACK 时，摘要被判失败

**步骤**：R2Bare 无 Skills/KB，history=4、保留最近 2 轮；依次要求记住 token、地点、服务商，助手仅回复 ACK；第四轮触发压缩。fresh 695 的“原始覆盖消息”只有 assistant: ACK one.

**实际**：fresh 684/686 显示压缩 FAILED、覆盖 1 条、2171→0 units、253 input / 17 output；原始记录保留，没有自动重试。更早的多工具案例 fresh 065 也失败，覆盖 3 条、664 input / 17 output。

源码 **ContextWindow.kt:89–103** 固定第一条 user，再保留最近轮次；本例候选只留下第一轮 assistant。**ContextCompaction.kt:139–191** 要求五个数组字段中至少一项非空。补充宿主用真实摘要指令 + assistant ACK one 得到 HTTP 200、五个空数组、17 output；用第一条 user 做对照得到非空 pending。这支持“有效但无内容的摘要被当失败”的解释，**设备摘要原始响应没有被保留，不能将宿主响应说成设备原始响应**。

**成功对照必须保留**：restore 100/101 的真实知识任务完成 **3 次摘要**；最后一次覆盖 9 条，131653→98253 units，16569 input / 333 output。因此不是“所有压缩都失败”，也不是“DS/SF 连接失败”。

建议按完整轮次选择候选，处理无信息候选/空摘要，并记录可诊断的失败分类。证据：fresh/summary-ack-host-control.json、summary-host-control.json、fresh 684–695、restore 100/101。

### R2-04 / P2：空 SAF 目录第一次授权后，读工具集不完整

fresh 243 实际通过系统授权一个空目录；fresh 266 的 workspace_list + file_write_text 写入 r2-saf.txt 成功。随后要求读取，在 fresh 270 出现内部错误；272 请求检查器工具集缺 file_read_text，最后一个成功工具为 file_stat。重新在 UI 选择已有内容的目录、保存 Agent 并新开会话后，fresh 294 真正读取成功。

这是“新内容出现后工具可用性/错误处理”的可复现用户路径；没有设备原始最终 tool_call，不断言内部错误的未知工具名。无效 workspace 的独立测试 fresh 297 正确拒绝，没有自动转入其他 workspace。建议检查首次空目录授权后的能力刷新，并提供可解释的读工具不可用错误。

### R2-05 / P2：SiliconFlow 图片能力探测假阴性

R2Vision / Qwen/Qwen3-VL-8B-Instruct 的 Metadata/Streaming Supported，但 Images Unsupported（fresh 452）。随后两份原始 PDF 经逐项上传授权完成视觉处理，恢复侧也显示 Ready；知识读取返回视觉内容，来源卡可打开原始 PDF 页（fresh 476/527/531、restore 078）。

真实图像路径与探测结果矛盾已确认；具体探测失败原因未从本轮保留日志中还原，不能直接沿用 DS 工具探测根因。建议为图片探测保留失败分类并用相同配置验证。

### R2-06 / P2：Windows Companion 在官方 adb.exe 文件身份检查处阻塞

Companion doctor 成功、signatureVerified=true，旧 JNA IllegalAccessException 未复现。但 devices 退出码 1：adb.exe file identity is unavailable。相同 Java 21 对该文件的 BasicFileAttributes 读取为 WindowsFileSystemProvider、fileKey=null、regular=true。

**AdbDoctor.kt:236、265** 依赖可空 fileKey 并要求非空，本机不能继续 devices/pair/run。未绕过防护。建议使用支持 Windows/JDK 的稳定文件身份实现；保留原有校验目的。证据：companion-doctor.log、companion-devices.log、companion-filekey-probe.log。物理 USB 验收仍不在模拟器证据内。

### R2-07 / P2：随 APK 打包的合法声明路径被本地查看器拒绝，整个组件列表为空

Settings → Third-party notices：fresh 767 显示“第三方声明路径不在允许范围内”和 No components are available；overview 可打开，AGPL 主许可证正常。

实际 APK 的 licenses/index.json 有 152 个组件，其中 all-MiniLM-L6-v2 引用 **modelpacks/all-MiniLM-L6-v2/LICENSE-NOTICE.txt**，该文件确实存在于 APK。**ThirdPartyNoticeAssets.kt:22、76、145–155** 只允许 modelpack 的 LICENSES/Apache-2.0.txt 或 licenses/ 前缀，拒绝新增 NOTICE 路径；任一项异常让整个目录返回空列表（:94–97）。

建议将构建生成的声明清单与 UI 允许的打包路径保持一致，并以真实 APK 资产做目录加载检查。证据：fresh/notices-path-validation.json、fresh 765/767/768。这不等于 APK 缺失所有第三方许可证；问题在本地查看入口。

## 3. 模型输出与诊断限制

这些结果与上面的已定位产品缺陷分别记录，不能笼统归为服务商断网。

- **无效 Python 参数的 UNKNOWN_OUTCOME**：fresh 157/162 在审批前显示 UNKNOWN_OUTCOME。宿主同构 SF 请求两次 HTTP 200、完整 DONE/tool_calls，但模型 arguments 是裸数组，违反要求的 object；OpenAiSse.kt 的 flushToolCalls 读取 jsonObject 会失败。设备原始响应未保存，故只确认 UI 失败及同构模型/schema 反例，根因关联为有证据支持的推断。明确完整 object 后 fresh 167 出现真实审批，168 Reject 正确说明没有执行，173–175 允许后成功。建议将无效工具参数与真实未知外部执行结果区分。
- **要求原样保存 stdout 仍改了一个字**：Python 原 stdout 与实际 file_write_text 参数同为 479 UTF-8 bytes，但“段首”变成“殳首”。fresh/python-write-fidelity.json 逐字比较不相等。文件工具成功；模型的“原样”承诺失败，不能用等长证明保真。
- **知识检索质量**：一次模型自行捏造 knowledgeBaseIds 导致空 hits；明确省略该可选参数后真正 search/read 成功。restore 100 的资源恢复对照实际检索成功且给出正确算式结果，但最终回答漏了所要求的 citation ID。另一个 TXT 专门引用用例 fresh 557 的两条引用均正确落到正文 44–1844，而非标题 0–43。
- **32K 预算保护是正确拦截**：原始两套 Skills 的 fresh 125 预算为 82417 = 334 协议 + 75493 正文 + 6590 schema，超过 input limit 28672。诊断 run_preparation_failed 的 11 个数值字段与 UI 一致，未发送模型请求；调到 131072 后执行成功。引用的用户问题继续保留在 [上下文/服务商反馈记录](user-feedback-context-provider.md)，本轮增加预算实测与 R2-03，不把大输入本身等同压缩故障。
- **应用日志的边界**：最终 restore ZIP 的 last-crash.ndjson 保存了本轮 NoSuchMethodError 的类型与栈，但 UI 摘要失败没有原始响应/明确失败分类。最终 fresh ZIP 的 819 条事件中 765 条是 authority configuration/state/lifecycle 事件，轮转窗口难以保留更早的模型异常。保留阶段 ZIP、请求检查器、Logcat 才能组成当前证据；health=healthy 不代表应用从未崩溃。

## 4. 服务商额外实测

| 配置/场景 | 结果与证据 |
| --- | --- |
| SiliconFlow base | https://api.siliconflow.cn/v1；使用官方 [快速开始](https://docs.siliconflow.cn/docs/userguide/quickstart) 所示兼容入口 |
| SF Chat | Qwen/Qwen3-30B-A3B-Instruct-2507；连接 1913 ms（old 025）、1793 ms（fresh 035），流式/工具探测通过；完整工具、Skills、知识库路径实际使用 |
| SF Vision | Qwen/Qwen3-VL-8B-Instruct；两份原 PDF 上传逐项同意、视觉处理与原页核对成功；探测假阴性见 R2-05 |
| DS 官方 base | https://api.deepseek.com |
| DS deepseek-v4-flash | 连接 2802 ms；DS-R2-HELLO 真实流式完成（516/30 tokens）；calculator 4059（1192/80）；元数据别名问题单列 |
| DS deepseek-flash | 连接 957 ms；显式 thinking.type=enabled 后实际 calculator 4059→1353 两次调用成功（2048/222）；Tools Unsupported 属探测问题 |
| DS 思考内容续传 | 检查器未见 reasoning_content 进入后续请求，但本轮服务器接受工具续传，未出现所怀疑的 400。静态实现与官方契约有兼容风险，不能标为本轮已复现连接错误 |
| DS 64-token 连接探测 | 静态输出上限/判定风险仍为条件项，本轮连接均成功，未复现“200 却误判连接失败” |

## 5. 完整 42 项用户路径

PASS_BOUNDED 仅覆盖所列样本。PASS_IMPORT_ONLY 不代表 294 PDF 已全部视觉处理。BLOCKED_HOST / NOT_CONFIGURED 是未完成的外部条件，不能计为通过。备份的“资源-only 通过”和“含会话失败”必须分别读取。

| # | 路径 | 本轮状态 | 证据/限制 |
| --- | --- | --- | --- |
| 1 | 安装与初次启动 | PASS | fresh 首次安装与旧5560升级 |
| 2 | Provider 必填与未保存保护 | PASS | old037 缺密钥阻止保存；fresh多次Keep editing保护 |
| 3 | SiliconFlow 连接与 Chat | PASS | old025/028；fresh035/038及实际多轮 |
| 4 | Chat capability probe | PASS | SF CHAT元数据/stream/tools Supported |
| 5 | Calculator | PASS | old079/123；fresh064 |
| 6 | 内部 workspace 写入/读取 | PASS | fresh064/074真实写入及新会话读取 |
| 7 | 无效 workspace | PASS | fresh297拒绝无效workspace，无fallback |
| 8 | 原始 Class A Skill 导入启用 | PASS | 原始Class A安装启用；fresh175真实进入prompt |
| 9 | 原始 Class B ZIP 导入 | PASS | 原始12项Class B ZIP安装 |
| 10 | Agent 绑定两个 Skills | PASS | R2Explorer两个Skills；真实Python执行 |
| 11 | 小 context 的超额保护 | PASS | fresh125与budget-events精确11项预算字段 |
| 12 | Python 审批 Reject | PASS | fresh167/168拒绝后明确未执行 |
| 13 | Python 真实执行 | PASS | fresh173–175 Python真实stdout；写入原样性另列模型失败 |
| 14 | 自动压缩与用量 | FAIL_EDGE | fresh 684/686/695：只压缩 assistant ACK one 时失败；restore 100/101：3 次真实摘要成功，用量可见 |
| 15 | SAF 目录选择与持久授权 | PASS | fresh243 SAF真实系统授权 |
| 16 | SAF 实际写入后新 Run 读取 | PASS_WITH_WORKAROUND | fresh266写入，重新选工作区后294读取 |
| 17 | 空 SAF 目录首次授权工具集 | FAIL | 空目录首次缺read工具，fresh270内部错误；重选后成功 |
| 18 | 294 PDF 文件夹导入 | PASS_IMPORT_ONLY | fresh 206/780：294 documents，重启保留；783 仍有 Waiting for Vision，不等于全量 READY |
| 19 | Vision 等待与上传同意 | PASS | fresh466/472–476逐项授权和VISION_PROCESSING |
| 20 | Vision capability probe | FAIL_FALSE_NEGATIVE | fresh452 Images Unsupported，但476真实PDF Ready |
| 21 | 两份原始 PDF + Vision 入库 | PASS | fresh 476/527/531 两份原 PDF 视觉处理；restore 078 两份 Ready |
| 22 | READY PDF 的严格模式 | PASS | fresh521严格模式拒绝视觉证据 |
| 23 | READY PDF 的文字降级问答 | PASS | fresh527/528实际search/read，28505input/305output |
| 24 | 原始 PDF “Use text only” | PASS | fresh219两份PDF TEXT_ONLY_WITH_VISUAL_GAPS；258–262真实RAG |
| 25 | TXT 对照 RAG 与 read_document | PASS | fresh 555/557；txt-citation-inspector.json，真实 search/read |
| 26 | 逐条引文正确性 | PASS_BOUNDED | fresh 531 PDF 原页；txt-citation-verification.json：两项引用对应正文 44–1844 |
| 27 | Shizuku 安装、启动、平台许可 | PASS | Shizuku 13.6安装启动，fresh306 Ready/Connected Granted |
| 28 | 危险模式与 Shell grant | PASS | fresh 709 明确启用，R2Bare r3 保存当前策略全局 shell grant；754 关闭 |
| 29 | Android Shell 真实调用 | PASS | fresh 735–737：审批后 id 与 getprop 成功，uid=2000、SDK=36、exit=0 |
| 30 | Shizuku 断开后失败关闭 | PASS | fresh 742：停止 owned Shizuku 后 AUTHORITY_TEMPORARILY_UNAVAILABLE，无 workspace 丢失误报 |
| 31 | Windows Companion / wired ADB | BLOCKED_HOST | Companion doctor PASS；devices fileKey null fail closed |
| 32 | 诊断 ZIP 导出与有限脱敏检查 | PASS_BOUNDED | 6 份阶段诊断 ZIP CRC 正常；final-diagnostics-redaction.json；应用日志保存恢复崩溃栈 |
| 33 | 含 Skills 的 Agent 备份 | PASS_RESOURCES_ONLY | 完整/资源 ZIP 导出含原始 2 Skills；restore 041 导入资源、108–110 真实 Python；含会话导入另列 FAIL |
| 34 | 原生 ONNX KB 的 Agent 备份 | PASS_RESOURCES_ONLY | 3 个原生 ONNX KB 导出、跨安装导入与本地重建成功；restore 067/072/078/100；含会话导入另列 FAIL |
| 35 | 无 Skill/KB 的 Agent 备份 | PASS_EXPORT_CONFLICT_REJECTED | fresh 641 bare ZIP 合法；restore 139 因 Provider 与现有配置冲突而拒绝，未证明空目标裸包恢复 |
| 36 | 同设备重复导入备份 | PASS | fresh 614 明确 Agent already exists，未覆盖；restore 139 Provider 冲突拒绝 |
| 37 | 新 Agent 详情创建会话 | PASS | old073/fresh060/123/249/517从新Agent详情进入对应会话 |
| 38 | 应用重启后的已有记录 | PASS | fresh 769/776/780 重启保留会话、3 Agents、4 KB；restore 010/022 崩溃恢复后旧记录保留 |
| 39 | 测试权限收尾 | PASS | fresh 751 SAF Revoked，754 Dangerous Mode Disabled；cleanup.json 两台 owned AVD 关闭 |
| 40 | 取消系统备份 picker | PASS | fresh 591 明确已取消导出 |
| 41 | 版本与本地许可入口 | PARTIAL_FAIL | fresh 762/763 版本详情、765 AGPL 正常；767 第三方声明目录错误、列表为空 |
| 42 | Brave / MCP 真实外部调用 | NOT_CONFIGURED | 无Brave key/MCP server；没有声称真实外部调用 |

除原矩阵外，新增完整会话备份跨安装恢复 **FAIL（R2-01）**；资源恢复后的 calculator、RAG、Python 真执行通过，最终 RAG 回答漏 citation 的模型质量结果单列。

## 6. 备份、日志与资料核验

| 产物 | 校验及实际结果 |
| --- | --- |
| fresh/full-backup.zip | SHA-256 d322fcb633ab3c636b1c323be2554cffc08d0a57abb0f7953025e1cebde134b6；830487 B / 17 entries；CRC 正常；2 Skills、3 KB、7 conversations；跨安装导入崩溃 |
| fresh/resources-backup.zip | SHA-256 8c770b26241c901fbc37e8f0ec53b969e76cd31cafbddce1404d17c2f8e40646；10 entries；CRC 正常；2 Skills、3 KB、0 conversations；跨安装资源恢复、重建和实际调用成功 |
| fresh/bare-backup.zip | SHA-256 e690f9c0a02a5f46288f7d62e45e7215069c5f71357a2a2d4802b379badc7256；1 manifest entry；0 Skills/KB/conversations。导出通过；向已有不同 Provider 配置的目标导入被明确拒绝（restore 139），未证明空目标裸包恢复 |
| Skills 内容 | 完整备份 Class A 45154 B 与原始一致；Class B ZIP 12 项逐字节一致。实际恢复绑定使用目标安装 ID，Python 工具名随本地 install ID 改变后仍可执行 |
| secrets / grants | 备份未包含本轮 API key；资源导入后 UI 提示缺本地 key，需要重新配置。审批/授权不按备份恢复；目标已有相同 Skill 内容的本地信任状态不能算作新授信。恢复 Python 仍有逐次审批 |
| 两份最终诊断 | fresh 819 events、restore 768 events，CRC 正常；应用 crash buffer 留存恢复崩溃。全部 6 份阶段 ZIP 清单与 SHA 见 diagnostics-inventory.json |
| 有限脱敏 | 最终两份日志对两个确切测试 key、sk- 模式、3 个抽查资料名及指定源目录模式均零命中；这不是所有隐私字段的完备证明。原始聊天资料留在私有检查器证据中 |
| 原资料/WIP | final-integrity.json：294 PDF 哈希匹配、总量315435736 B；两份 Skill 与 Class B 12 文件不变；收尾写报告前 protected drift=[] |

完整恢复后的索引不是从备份直接复用：UI 明确要求本地重建；restore 067/072/078 分别核对 TXT Ready、text-only PDF 的视觉缺口、Vision PDF Ready。restore 100 完成真实 calculator/RAG 及 3 次自动摘要；108–110 明确批准后 Python stdout 与 fresh 相同。

## 7. 撤回、未复现与剩余边界

**撤回此前“Agent scoped 全局 shell grant 必然无法暴露”的判断。** 早期无工具快照对应的 Dangerous Mode 实际仍是 Disabled，单独阅读某个快照绑定函数也没有覆盖完整暴露路径。fresh 709 明确启用、R2Bare r3 保存当前策略授权后，735–737 两次真实 shell 审批与执行成功：uid=2000(shell)、SDK=36、exit=0。停止 owned Shizuku 后，742 明确 AUTHORITY_TEMPORARILY_UNAVAILABLE，并说明不是工作区授权丢失。日志亦有两次 shell_execution_state=succeeded。不得继续把早期错误判断登记为已确认缺陷。

本轮样本未复现：Python Reject 后错误提示执行、文字缺口 PDF 读取版本不匹配、TXT 仅引标题、新 Agent 新会话路由错误、取消导出仍成功提示、Shizuku 断开误报工作区丢失、官方 DS 基础连接失败。既往报告保留其原 SHA 与样本边界，不一概关闭所有历史问题。

DSH 只读审查提出的 KEEP_EXISTING 前置 remap、旧 package-id 选择最新版本、缺失历史 Skill 导出失败等是**条件兼容风险**；当前用户路径没有证明这些历史输入可达。见私有 code-review-notes.md，不与 R2-01 的真实 Android 方法缺失混为一谈。

仍未验收：294 PDF 全量付费 Vision/READY、物理 Wired USB 配对断连、OEM DocumentsProvider/Binder 差异、未配置 Brave/MCP 的外部真实调用、空目标裸 Agent 恢复。本轮全矩阵列出这些限制，没有通过代改数据库、绕过校验或借用其他服务凭据补出 PASS。

## 8. 收尾与交接

fresh 751：测试 SAF 目录 Revoked，Read/Write Not granted；754：Dangerous Mode Disabled。owned Shizuku server 已停止。重启后 fresh 769/776/780 保留测试会话、3 Agents、4 KB 及 294 documents；恢复目标崩溃重启后原记录保留。

两台模拟器经 AVD name 核实后关闭，3 个 owned Logcat 采集进程停止；AVD 数据与私有证据保留，既有 DSH 实例保留。详见 cleanup.json、logcat-cleanup.json。未清理原目录、未重置 protected WIP、未执行产品修复或任何发布操作。

后续修复应优先处理 R2-01，再处理探测、摘要边界、SAF、Companion 与声明入口；按每项实际触发条件做回归，不以宿主 JVM 门禁替代 Android 用户验收。
