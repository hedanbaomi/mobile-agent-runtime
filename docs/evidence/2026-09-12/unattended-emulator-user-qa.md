<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-09-12 无人值守模拟器用户视角测试

结论：**NEEDS_AMEND**。已通过真实服务与 Android UI 验证主要 Agent 路径，存在可复现产品缺陷；不能作为完整产品验收 PASS 或发布许可。测试与报告为本地工作，未修源码、提交、推送或发布。原始 Skill/知识库资料未改动。

测试时段：2026-09-12 11:56–14:40（Asia/Taipei）。已关闭测试用高危权限并停止独占模拟器，保留 AVD 与证据供复核。

## 环境与证据范围

- 源码：`f1feffed51840a5da299394560619d64cac4b9b5`；原工作区 HEAD `3f454183176b7664ff774593bd6dba396d595b2e` 仅多本地文档，源码等同 main。
- 包：`.private/manual-test/20260911-main-f1feffe/mobile-agent-runtime-main-f1feffe-debug-signed.apk`，205078445 B，SHA-256 `4b5abdfe90b980e42039c0d618174b339db6ed8bcfd448a4ef98c33ced0ddbbd`。Review 变体、Debug 证书、debuggable=false、高权限控制面开启，不是正式签名 release。
- 新建并独占 `mar_userqa_20260912`，serial `emulator-5560`，Android API 36 x86_64，包名 `runtime.mobileagent`。ADB/AndroidCLI 操作正常 UI；原生 computer-use 处理输入与授权阻塞。未写应用内部数据库或用隐藏接口制造测试结果。
- SiliconFlow base URL：`https://api.siliconflow.cn/v1`，按[官方指南](https://docs.siliconflow.cn/docs/userguide/capabilities/stream-mode)核对；真实 /models 及应用内连接/聊天已执行。Chat：`Qwen/Qwen3-30B-A3B-Instruct-2507`，131072 context / 4096 output；Vision：`Qwen/Qwen3-VL-8B-Instruct`，32768 / 4096。临时 Key 仅用于指定服务，不写入报告。
- 用户目录的两个 Skills：Josephine 原始 SKILL.md（45154 B，Class A），lieflat 完整 ZIP（185276 B，Class B，三个 stdlib Python CLI）。294 PDF 原始副本共 315435736 B。小样为 Lone+study+updated.pdf（63056 B）及 working+with+the+void.pdf（68569 B）。
- 证据根：[私有原始记录](../../../.private/userqa-20260912/)。`ui/NNN-*.json` 有 UTC 时间、serial 和完整可访问 UI 文本；控制台截断的长 tool/request 内容以 JSON 文件为准。编号指交互快照，**不是独立测试数量**。诊断与工具返回分开记录，模型自述不单独作为成功证据。

## 实测矩阵

PASS 仅适用于该行的明确动作；PARTIAL 表示只覆盖部分行为；BLOCKED 表示必需环节没有完成。

| 路径 | 结果 | 关键观察与证据 |
| --- | --- | --- |
| 安装与初次启动 | PASS | 本次指定 APK 成功运行；版本与源码标识见 [UI 549](../../../.private/userqa-20260912/ui/549-settings-export-error-bottom.json)。 |
| Provider 必填与未保存保护 | PASS | 缺 Key 被阻止，离开未保存编辑有确认。[UI 009](../../../.private/userqa-20260912/ui/009-text-next.json) [UI 013](../../../.private/userqa-20260912/ui/013-observe.json) [UI 014](../../../.private/userqa-20260912/ui/014-tap.json) |
| SiliconFlow 连接与 Chat | PASS | 连接 1805 ms；实际多轮、流式和工具调用成功。[UI 020](../../../.private/userqa-20260912/ui/020-tap.json) [UI 530](../../../.private/userqa-20260912/ui/530-shell-positive-finished.json) |
| Chat capability probe | PARTIAL | Metadata/stream 可用，工具探测显示 Check failed，但实际 tool calling 成功。不能用 probe 一项失败否定整个 Provider。[UI 024](../../../.private/userqa-20260912/ui/024-observe.json) |
| Calculator | PASS | 137×29+86=4059，实际工具返回。[UI 055](../../../.private/userqa-20260912/ui/055-text-next.json) |
| 内部 workspace 写入/读取 | PASS | discover→write→read，23 B 的 QA 标记内容完全一致。[UI 055](../../../.private/userqa-20260912/ui/055-text-next.json) [UI 056](../../../.private/userqa-20260912/ui/056-observe.json) |
| 无效 workspace | PASS（拒绝路径） | 返回 WORKSPACE_NOT_FOUND，未伪造文件成功。 |
| 原始 Class A Skill 导入启用 | PASS | 原始 markdown 被识别和安装、启用。[UI 067](../../../.private/userqa-20260912/ui/067-tap.json) [UI 070](../../../.private/userqa-20260912/ui/070-tap.json) [UI 078](../../../.private/userqa-20260912/ui/078-skills-return-top.json) |
| 原始 Class B ZIP 导入 | PASS | 包解析识别 stdlib CLI、隔离 Python 虚拟文件，启用成功。[UI 081](../../../.private/userqa-20260912/ui/081-tap.json) [UI 082](../../../.private/userqa-20260912/ui/082-tap.json) [UI 084](../../../.private/userqa-20260912/ui/084-tap.json) |
| Agent 绑定两个 Skills | PASS | 保存 r3，2 Skills 绑定；不是只安装未使用。[UI 105](../../../.private/userqa-20260912/ui/105-tap.json) [UI 108](../../../.private/userqa-20260912/ui/108-after-back.json) |
| 小 context 的超额保护 | PASS（保护行为） | 32768/4096 时，保守估算 82685 > 28672，本地拒绝，未用截断必要指令绕过。易用性和预算准确性另见 U-01。[UI 114](../../../.private/userqa-20260912/ui/114-text-next.json) |
| Python 审批 Reject | FAIL | 用户拒绝被显示为内部错误，F-01。[UI 136](../../../.private/userqa-20260912/ui/136-observe.json) [UI 137](../../../.private/userqa-20260912/ui/137-tap.json) |
| Python 真实执行 | PASS（一次） | `check-structure.py --human human --ai ai` 用实际虚拟文件执行并返回指标；结果保存到 python-skill-result.json。只核验到一次成功，第二次批准不等于第二次成功。[UI 157](../../../.private/userqa-20260912/ui/157-tap.json) |
| 自动压缩与用量 | PASS（一次压缩） | 4 条原消息纳入摘要；估算 88504→87912，summary usage 1265/196，终态总计 112291 input / 627 output（含 summary）。[UI 159](../../../.private/userqa-20260912/ui/159-tap.json) [UI 160](../../../.private/userqa-20260912/ui/160-tap.json) [UI 161](../../../.private/userqa-20260912/ui/161-tap.json) |
| SAF 目录选择与持久授权 | PASS | 系统目录 picker、ALLOW、read-write 授权；仅本轮测试目录。[UI 263](../../../.private/userqa-20260912/ui/263-after-choose-phone-folder.json) [UI 268](../../../.private/userqa-20260912/ui/268-after-save.json) |
| SAF 实际写入后新 Run 读取 | PASS | write 18 B，再补 file.read_text 授权，新 Run 读取 QA-20260912-KB-SAF，版本 3468792567583496389、eof=true。[UI 295](../../../.private/userqa-20260912/ui/295-after-send.json) [UI 296](../../../.private/userqa-20260912/ui/296-observe.json) [UI 387](../../../.private/userqa-20260912/ui/387-observe.json) [UI 388](../../../.private/userqa-20260912/ui/388-saf-read-complete.json) |
| 空 SAF 目录首次授权工具集 | PARTIAL | 初始 capability probe 未暴露 read_text；首次写入后原 Run 工具集不增长，需新的 read grant/Run。旧 grant 的 Active 标签不等同本次 Run 实际可用工具。 |
| 294 PDF 文件夹导入 | PARTIAL | UI 列出 294 documents，导入期间应用继续运行；全批仍有等待 Vision。未把 294 个存在等同 294 READY。[UI 238](../../../.private/userqa-20260912/ui/238-after-allow.json) [UI 390](../../../.private/userqa-20260912/ui/390-after-knowledge.json) [UI 541](../../../.private/userqa-20260912/ui/541-after-knowledge.json) |
| Vision 等待与上传同意 | PASS（小样） | 未同意时等待，显示未发送 image bytes；确认展示服务商、model、修订号及计费/离设备说明。[UI 477](../../../.private/userqa-20260912/ui/477-fresh-vision-jobs.json) [UI 478](../../../.private/userqa-20260912/ui/478-tap.json) [UI 544](../../../.private/userqa-20260912/ui/544-after-approve-vision-upload.json) |
| Vision capability probe | PARTIAL | 连接通过 1649 ms，probe Images Unsupported；重新保存人工 images 声明后，两份原始 PDF 的真实 Vision 成功。探测误报根因未定位。[UI 207](../../../.private/userqa-20260912/ui/207-after-test-connection.json) [UI 212](../../../.private/userqa-20260912/ui/212-after-run-probe.json) [UI 449](../../../.private/userqa-20260912/ui/449-after-edit.json) [UI 451](../../../.private/userqa-20260912/ui/451-after-save.json) |
| 两份原始 PDF + Vision 入库 | PASS | Josephine Vision QA，2 items copied 2，processing/waiting/failed 全为 0，COMPLETED；两个 job 均 Ready。[UI 480](../../../.private/userqa-20260912/ui/480-vision-lone-result.json) [UI 546](../../../.private/userqa-20260912/ui/546-vision-small-batch.json) |
| READY PDF 的严格模式 | PASS（拒绝路径） | Chat model 不支持图像时，knowledge_search 返回 INVALID_REQUEST，明确要求 image-capable model 或 text-only degradation；无虚构结果。[UI 618](../../../.private/userqa-20260912/ui/618-after-inspect-request.json) |
| READY PDF 的文字降级问答 | PASS（工具链与原页） | UI 启用文字降级后，真实 knowledge_search→read_document→回答；34802 input / 388 output。两条理由均引用返回的第1页 ID，源卡实际显示 PDF 第1页。[UI 622](../../../.private/userqa-20260912/ui/622-after-more-options.json) [UI 629](../../../.private/userqa-20260912/ui/629-pdf-ready-textonly-complete.json) [UI 631](../../../.private/userqa-20260912/ui/631-after-inspect-request.json) [原页截图](../../../.private/userqa-20260912/pdf-ready-source-page1.png)。Chat 模型未查看原图。 |
| 原始 PDF “Use text only” | FAIL（检索） | UI Indexed text only / visual gaps，但 knowledge_search 拒绝 citation source version，F-02。[UI 224](../../../.private/userqa-20260912/ui/224-sample-text-only-progress.json) [UI 225](../../../.private/userqa-20260912/ui/225-after-use-text-only.json) [UI 284](../../../.private/userqa-20260912/ui/284-after-send.json) [UI 285](../../../.private/userqa-20260912/ui/285-observe.json) [UI 409](../../../.private/userqa-20260912/ui/409-observe.json) |
| TXT 对照 RAG 与 read_document | PASS（工具链） | 用小 PDF 原文派生两个 TXT（保留来源页头）；仅 READY TXT 时，实际文档读取返回→回答→打开证据卡成功；本行不据用户请求文字单独认证 search 调用。[UI 424](../../../.private/userqa-20260912/ui/424-observe.json) [UI 425](../../../.private/userqa-20260912/ui/425-rag-control-complete.json) [UI 426](../../../.private/userqa-20260912/ui/426-rag-answer-bottom.json) [UI 428](../../../.private/userqa-20260912/ui/428-tap.json) |
| 逐条引文正确性 | PARTIAL | 工具返回可追溯，回答内容有全文依据；但其中一个 citation id 指向仅来源标题的短 chunk，不能支持对应论点，F-08。[UI 427](../../../.private/userqa-20260912/ui/427-rag-source-chips.json) [UI 428](../../../.private/userqa-20260912/ui/428-tap.json) |
| Shizuku 安装、启动、平台许可 | PASS | 官方 13.6.0 APK；按该版本显示的启动命令运行，平台授权后 runtime Ready/Connected。[UI 302](../../../.private/userqa-20260912/ui/302-after-view-command.json) [UI 322](../../../.private/userqa-20260912/ui/322-after-allow-all-the-time.json) [UI 331](../../../.private/userqa-20260912/ui/331-scroll-enable-and-select-shizuku.json) [UI 483](../../../.private/userqa-20260912/ui/483-current-dangerous-mode.json) |
| 危险模式与 Shell grant | PASS | 最新 UI 确认 Dangerous Mode enabled / Shell available；随后重新发放 Agent-scoped persistent shell grant、保存 r4、建新会话。[UI 485](../../../.private/userqa-20260912/ui/485-observe.json) [UI 502](../../../.private/userqa-20260912/ui/502-shell-old-grant-unchecked.json) [UI 507](../../../.private/userqa-20260912/ui/507-after-shell.execute--high-risk.json) [UI 508](../../../.private/userqa-20260912/ui/508-after-save.json) [UI 523](../../../.private/userqa-20260912/ui/523-after-new.json) |
| Android Shell 真实调用 | PASS | Agent 调用 shell_exec 两次，逐次批准；id exit 0、uid=2000(shell)，getprop exit 0、stdout=36；authority SHIZUKU、116/110 ms。[UI 527](../../../.private/userqa-20260912/ui/527-observe.json) [UI 528](../../../.private/userqa-20260912/ui/528-after-allow-once.json) [UI 529](../../../.private/userqa-20260912/ui/529-after-allow-once.json) [UI 530](../../../.private/userqa-20260912/ui/530-shell-positive-finished.json) |
| Shizuku 断开后失败关闭 | PASS（权限边界） | 结束本轮自启的 Shizuku server 后同会话再调用 id；实际工具 DENIED/PERMISSION_DENIED、message AUTHORITY_TEMPORARILY_UNAVAILABLE，无替代 authority 的新执行。UI 提示另见 F-09。[UI 534](../../../.private/userqa-20260912/ui/534-shell-offline-finished.json) [UI 537](../../../.private/userqa-20260912/ui/537-after-inspect-request.json) |
| Windows Companion / wired ADB | BLOCKED | installDist 成功；doctor 和 devices 均 JNA IllegalAccessException exit 1，尚未配对，F-03。模拟器也不能认证物理 USB。 |
| 诊断 ZIP 导出与有限脱敏检查 | PASS（所列检查） | 本轮模拟器 diagnostics-01.zip CRC 正常，14301 B，SHA-256 fcc333215435136ac793356665118623b50e732a12e022b704cff8b079021ec5；两日志 547/661 行、last-crash 空；测试标记、文件名、URI、host、key 模式等检查零命中。见 diagnostic-redaction-check.json。 |
| 含 Skills 的 Agent 备份 | FAIL | 含包+会话导出0 B；全关扩展重试仍0 B，页首 missing skill，F-04。[UI 435](../../../.private/userqa-20260912/ui/435-after-export.json) [UI 440](../../../.private/userqa-20260912/ui/440-backup-export-result.json) [UI 551](../../../.private/userqa-20260912/ui/551-after-export.json) [UI 555](../../../.private/userqa-20260912/ui/555-backup-error-at-top.json) |
| 原生 ONNX KB 的 Agent 备份 | FAIL | 明确选中 QA Knowledge；全关扩展仍报 embeddingSpaceId unsafe identifier，0 B，F-05。[UI 564](../../../.private/userqa-20260912/ui/564-backup-knowledge-radio.json) [UI 565](../../../.private/userqa-20260912/ui/565-after-prepare-export.json) [UI 567](../../../.private/userqa-20260912/ui/567-backup-knowledge-result.json) |
| 无 Skill/KB 的 Agent 备份 | PASS（导出） | QA-Bare r1 导出 971 B，ZIP CRC 正常；manifest 无 secret/grant，SHA-256 488959ebd99822b96d6865c6a8d248c3ca7d038c76681d6b57042e0bde2bb69e。[UI 586](../../../.private/userqa-20260912/ui/586-after-save.json) [检查](../../../.private/userqa-20260912/backup-bare-check.json) |
| 同设备重复导入备份 | PASS（拒绝覆盖） | 导入上述 ZIP，返回 Agent already exists，现有 Agent 保留。这不是跨设备恢复 PASS。[UI 589](../../../.private/userqa-20260912/ui/589-bare-import-file.json) [UI 591](../../../.private/userqa-20260912/ui/591-bare-import-result.json) |
| 新 Agent 详情创建会话 | FAIL（选中路由） | 已创建 QA-Bare 新会话，但 Chat 仍显示旧 QA Knowledge；重启后在 drawer 手动选择才进入正确会话，F-10。[UI 606](../../../.private/userqa-20260912/ui/606-bare-start-chat.json) [UI 608](../../../.private/userqa-20260912/ui/608-new-agent-chat-selection-recheck.json) [UI 611](../../../.private/userqa-20260912/ui/611-drawer-find-bare-session.json) [UI 612](../../../.private/userqa-20260912/ui/612-after.json) |
| 应用重启后的已有记录 | PASS（所见历史） | force-stop/relaunch 后旧 Shell 会话记录仍可见；随后可选择新 Agent 会话并检索原 PDF。不扩大为所有持久化场景。[UI 609](../../../.private/userqa-20260912/ui/609-app-relaunch-persistence.json) [UI 631](../../../.private/userqa-20260912/ui/631-after-inspect-request.json) |
| 测试权限收尾 | PASS | 由 UI 将 Dangerous Mode 设为 Disabled；自启 Shizuku server 已在负向测试中停止。[UI 640](../../../.private/userqa-20260912/ui/640-after-disabled.json) |
| 取消系统备份 picker | PASS | 返回 Settings 并显示已取消导出。[UI 562](../../../.private/userqa-20260912/ui/562-export-picker-cancel.json) |
| 版本与本地许可入口 | PARTIAL | About 部分显示 review / full SHA / DB18 / AGPL-3.0-only；未执行正式更新或 release。[UI 549](../../../.private/userqa-20260912/ui/549-settings-export-error-bottom.json) |
| Brave / MCP 真实外部调用 | 未覆盖 | 无相应服务配置；界面显示 Brave 无 key、MCP 未配置。仅已配置的 SiliconFlow 被调用。[UI 548](../../../.private/userqa-20260912/ui/548-after-settings.json) [UI 549](../../../.private/userqa-20260912/ui/549-settings-export-error-bottom.json) |

## 可复现问题与优先级建议

### F-01 / P2：拒绝工具审批被映射成内部错误

复现：启用 lieflat Skill → 触发 Python 审批 → Reject → “运行时发生内部错误”。用户拒绝的安全边界实际生效，错误语义不正确。

源码链：`shared/agent-runtime/.../AgentRuntime.kt:629-633` 发出自由文本 “Tool … was rejected”；`RuntimeEvents.kt:157-185` 取首词后落入 INTERNAL 默认分支（179），265 映射上述中文；APPROVAL_DENIED 旁路审计不能修正聊天主错误。复测应同时核对会话状态、可读原因和 invocation 拒绝语义。

### F-02 / P1：接受 PDF 视觉缺口后，知识检索仍拒绝引用版本

复现：两个原始 PDF → Use text only → job 结束且 UI Indexed text only / Text only (visual gaps) → 绑定 KB、新会话触发 knowledge_search → “Citation source version is not published”。混合加入 READY TXT 仍失败；删除本轮测试 KB 中的两份 text-only PDF 后，同样 TXT 检索与读取成功。之后重导入原始 PDF 仍复用 text-only 状态；新建 Vision QA KB 才单独完成视觉处理。

`app-android/.../RunTools.kt:546` 要求引用源 `version_status == READY`，与允许视觉缺口的状态不一致。**不是**看到 “retrieval disabled 0/1” 就认定整个索引未发布：本 Agent 为 explicit retrieval，自动检索禁用与手动 knowledge_search 是不同路径。不可通过伪造所有 PDF READY 修复。

### F-03 / P1：Windows Companion 在 WinTrust 反射阶段失败

`bridge.bat doctor --adb …` 与 `devices` 均在真实 JNA `WinTrustFileInfo.cbStruct` 访问时报 IllegalAccessException；Oracle JDK21，build installDist 成功。日志：[build](../../../.private/userqa-20260912/bridge-build.log)、[doctor](../../../.private/userqa-20260912/bridge-doctor.log)、[devices](../../../.private/userqa-20260912/bridge-devices.log)。

`desktop/bridge/.../AdbDoctor.kt:76,91` 的私有嵌套 Structure 类及 public 字段可见性是明确可疑/验证方向；失败发生在 ADB 调用前。未绕过签名校验或构造虚假 verifier，未发起配对。pair/run 等入口影响只作共享代码推断，不冒充实测。

### F-04 / P1：有效 Skill Agent 无法导出

QA Explorer 的两种选项组合都失败；明确消息为 “Agent references missing skill …”。关闭 optional Skill package 内容也不能解决。安装、绑定与运行已在同一设备成功，需核对导出引用关系。

独立只读审查发现 Agent 绑定使用 skill install_id（`AgentRepository.kt:418-424`），而导出将其按 package id 查询（`TransferRepository.kt:186-188,1103-1105`）；install 与 package 是两个字段（`SkillRepository.kt:53-77`）。特定 UUID 的 DB 值未直接读取，因此个别 ID 的身份不作已证实断言。

### F-05 / P1：ONNX embeddingSpaceId 无法通过备份校验

明确切换到 QA Knowledge（0 Skills / 1 KB）并关闭三个扩展选项后，导出报 “knowledgeBase.embeddingSpaceId contains an unsafe identifier”。原生 ONNX space id 为 `onnx:all-MiniLM-L6-v2@…:d384:cosine`。

独立审查：`TransferCodec.kt:246,709` 的 SAFE_ID 允许冒号但不允许 @，而默认模型空间身份包含 @（`ModelPackLoader.kt:126`、`EmbeddingPort.kt:241-242`）。这不是导出体积、云同步或用户选错 Agent。

### F-06 / P2：备份失败仍显示正在导出

`SettingsViewModel.kt:564` 写进度，catch 571-572 仅设置 error，finally 573 退出 running。错误在 `SettingsUi.kt:261-268` 页首，备份卡片397继续显示旧 exportState。分时快照 UI 555（页首错误）与 UI 556（备份区旧进度）证明失败后旧进度未被清除；二者不是同一张截图。不能据旧文本认定死锁。失败应更新同位置终态、提供可发现的原因和重试入口。

### F-07 / P2 待定位：能力探测与真实调用结果不一致

Chat tool probe 显示 Check failed，但多次工具实际成功；Vision probe 显示 Unsupported，但人工声明后两份原始 PDF 视觉处理完成。连接、metadata/probe、实际请求须分别展示与记录。不把单个 probe 失败定性成整个 base URL 无效；具体网络响应/探测条件尚待复核。

### F-08 / P2（模型输出质量）：引用存在不代表逐条论点有依据

TXT 对照的实际工具返回（UI 424、427）包含三个 citation：第0条 textStart=0/textEnd=43，仅来源页头。回答第二条论点却引用该 id；工具全文含相关内容，但这一 citation 自身不能支撑论点。因此“证据卡可打开”PASS，不等于模型的每条 citation 都准确。报告不把 TXT 的源页头误称 PDF 页坐标。

### F-09 / P2：权限通道断开被提示成工作区权限不足

真实工具返回 DENIED / PERMISSION_DENIED，message 为 AUTHORITY_TEMPORARILY_UNAVAILABLE（request inspector537）；聊天卡片却显示“没有权限访问该工作区，请检查工作区授权”。模型最终文本正确说明 Shizuku unavailable。需要保留 authority 的失败原因，避免诱导用户反复授权 SAF。

### F-10 / P2：新 Agent 详情创建会话后，Chat 仍停留旧 Agent

QA-Bare r2 的详情页点击“Start a new conversation with this Agent”后，Chat 页仍为 QA Knowledge 并显示旧 Shell 历史（[UI 606](../../../.private/userqa-20260912/ui/606-bare-start-chat.json) [UI 607](../../../.private/userqa-20260912/ui/607-after-start-a-new-conversation-with.json) [UI 608](../../../.private/userqa-20260912/ui/608-new-agent-chat-selection-recheck.json)）。重启后 drawer 中存在新建 QA-Bare 会话；手动选中才显示正确 Agent（[UI 611](../../../.private/userqa-20260912/ui/611-drawer-find-bare-session.json) [UI 612](../../../.private/userqa-20260912/ui/612-after.json)）。因此是新会话选中/路由问题，不能说创建本身失败。后续测试发送前已核对实际 Agent。具体缓存或 selected conversation 更新链尚未定位。

## 用户本轮新增反馈

已纳入 [上下文预算与 DeepSeek Base URL 专项补充](user-feedback-context-provider.md)：

- U-01：真机同源码诊断包独立计数 12 次 context_budget / CONTEXT_OVERFLOW，正常对话被阻断的反馈成立；预算数字缺失，尚不能断言一定是估算误杀。
- U-02：DeepSeek 官方根地址失败、换 SiliconFlow 恢复的人工 A/B 反馈。官方地址和当前 join 源码已核验；本轮没有 DeepSeek 鉴权实测，不采纳“强制补 /v1 已证实”的猜测。

## 限制、复核与交付边界

- 294 文件的存在/入库不等于全视觉 READY、全页/表格识别正确。没有完成全部294文档视觉调用、300–500文件K06门槛、ENOSPC、温控/耗电或长时配额矩阵。
- 文档问答包含派生 TXT 对照和原始 READY PDF 正向链。后者使用聊天文字降级；界面可打开原始第1页，模型只收到提取文本及 Vision 文字结果，未认证多模态 Chat。原始 PDF 的两个论点已对照第1页正文，不能外推为全部文档无幻觉。知识证据作为数据处理，不授予设备/网络权限。
- 无资源 Agent 已成功导出，重复导入被拒绝；未执行跨设备/清空数据恢复。含 Skills 与 ONNX KB 的备份仍失败。
- 真实 Python 仅确认一次成功；重复批准不算执行成功。没有把模型自述替代工具返回，没有把 inspector 估算当供应商账单。
- SAF 初次写入后的一次 INTERNAL 终态尚未单独定位，不和已确认的审批拒绝映射混为同一根因。
- Shizuku 在模拟器上实际可用，因此可完成 Shell 路径；它不替代 Windows Companion 失败的 wired 路径，更不代表物理 USB/OEM 验收。
- 本轮模拟器 diagnostics-01.zip（fcc333…，547/661 行）与人工反馈附件 mobile-agent-diagnostics.zip（68425b…，342/543 行）是不同包；后者支持 U-01，不用于冒充模拟器自己的日志。
- 诊断标记扫描是有界证据，不是所有可能隐私字段的形式化证明。原始 UI/request 证据保留在 .private，报告不复制 API Key 或完整资料正文。
- DSH Flash 经 Codex 内置浏览器使用。新会话的 Pwsh 探活实际成功，之前失败不能泛化为“内置浏览器不能执行 shell”。只读子任务中缺乏证据的推断（KB index、backup busy、DeepSeek /v1）已由主 Agent 排除；Luna Max 对备份引用链及报告证据做独立复核，主 Agent 已按反馈区分两个诊断包、收窄 TXT search 断言、修正分时截图措辞。没有把子代理回复直接当运行验收。
- 模拟器操作和报告由主 Agent 独占；没有修产品代码或触发任何发布。原工作区既有 WIP 与用户原始 fixtures 保留。

## 收尾核验

- 2026-09-12T14:40:15.059433+08:00：Dangerous Mode 为 Disabled；自启 Shizuku server 不存在，emulator-5560 已停止并从 ADB 设备列表消失。未删除 AVD、测试数据或导出文件，未停止用户 DSH 服务。[收尾记录](../../../.private/userqa-20260912/cleanup.json)。
- 5 个受保护规则/需求文件 hash 与开工相同；两个原始 SKILL.md、294 PDF、Class B 包中12个原始文件逐项 hash 一致。HEAD 未变，暂存区为空，已有 tracked diff 仅受保护文档及 HANDOFF，无产品源码 diff。[完整性检查](../../../.private/userqa-20260912/final-integrity-check.json)。
- 两份报告的本地链接、AGPL-3.0-only 声明及 API Key 模式扫描通过。问题与待复测状态已更新 HANDOFF。本轮没有实施修复，不为尚未修复的问题填写修复验证 PASS。
