<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 验收 r2 修复逐项记录

基线 `6eefff3fdc150fd420b55aad428a8e5c0556abed`。本轮输入 107404 bytes，SHA256 `d001e1f4bf80b0f16f0e76e2b93647c57d39c6bcc235421597d88331718e1836`，64 个 ZIP 项，58 issue（4 P1 / 25 P2 / 29 P3）。实际由任务附件同步到本机并读出，不冒称缺失的 Library materialization 已成功。

用户允许跳过无法确认问题。以下“已修源码”表示存在可确认的源码缺口并已实现修复，具体测试/设备边界见验证表；不等于原报告全场景重演或整个 §14 已通过。P1 EMU-044 未解决；EMU-059 只关闭合成回归证明的恢复缺口。ANR 确实发生，5004ms MotionEvent / Dialog.dismiss / accessibility Binder；原trace不在输入，DB竞争只是线索，无法归因，跳过。

输入复核的历史事实保持独立：`7d57d08f` 为 PROCESSING、30/52 已发布、20 COPYING、2 FAILED，processing/waiting 均为 0；`dcc70e7f` 为 PROCESSING、31/42 已发布、10 COPYING；28 个 vision_attempts 为 IN_PROGRESS。RBK06 的确切批次未知，不能把这些记录混为同一次运行。原备份 46/108 会话未导出，源 DB 仍在，不是永久丢失；原 soak 由脚本重启进程，不能据此声称 FGS 自动恢复或连续一小时稳定。撤回的掉帧证据和不足的阶段采样不用于性能倍率结论。

本地 HANDOFF 原有修改及两个未跟踪计划保留；根工作区 .codex-remote-attachments/ 同样保留。HANDOFF 只在原工作区追加本机事实，原未提交内容不混入代码提交；本受跟踪记录提供可复核修复范围。

## 逐项审查

| ID | 级别 | 状态 | 证据、实现或残余问题 |
| --- | --- | --- | --- |
| EMU-001 | P2 | 已修源码 | PartialAssistantHistory / ContextWindow：保留协议完整的中断文本，禁止作为摘要完整来源。 |
| EMU-002 | P2 | 已修源码 | ChatViewModel：运行中切换/新建显示明确状态，保留草稿。 |
| EMU-003 | P2 | 已修源码 | MainActivity / MainScreens：根 Chat 运行中 Back 移入后台。 |
| EMU-004 | P3 | 已修源码 | McpViewModel / McpSettingsScreen：选择集合与 Checkbox 状态同步。 |
| EMU-005 | P2 | 已修源码 | MainActivity：API33+ 仅首次申请通知权限，仍由用户决定。 |
| EMU-006 | P3 | 部分修复 | 保留超限原文并提供显式新会话恢复；仍拒绝无法容纳的固定内容，未实现丢弃历史/无界自动摘要。 |
| EMU-007 | P2 | 已修源码 | ChatViewModel：SavedStateHandle 按会话保存草稿。 |
| EMU-008 | P3 | 已修源码 | ChatViewModel：孤立 USER 提示本机已保存、送达/处理未确认，不自动重发。 |
| EMU-009 | P3 | 已修源码 | ChatUi / ChatViewModel：空助手与重开后的非实时状态明确显示。 |
| EMU-010 | P3 | 已修源码 | ChatViewModel：会话草稿保存；Home 时 VM 保持。 |
| EMU-011 | P2 | 已修源码 | MainScreens：绑定成功后重载聊天摘要。 |
| EMU-012 | P2 | 已修源码 | MarkdownText / ChatUi：基础 Markdown 与代码、表格横滚。 |
| EMU-013 | P1 | 已修源码 | RuntimeIntegration：同工作区绑定保留当前策略有效整目录持久写授权；负向边界不保留。 |
| EMU-014 | P3 | 部分修复 | 同进程按会话缓存脱敏预览；跨进程保持明确不可用（安全规范默认不持久化请求正文），未重发。 |
| EMU-015 | P3 | 已修源码 | ChatUi：正文选择复制、完整工具结果展开。 |
| EMU-016 | P2 | 跳过/未复现 | 长文档 COPYING 四分钟症状已记录；无停止时 thread dump，不能确定死锁/调度/解析根因，未改。 |
| EMU-017 | P2 | 跳过/未复现 | 确认页全快照加载耦合有源码线索；无实际阻塞 trace 和截图，未确认 loading 长驻根因，跳过。 |
| EMU-018 | P3 | 已修源码 | KnowledgeRepository：可证明的同库同 space 追加只推进派生 generation 栅栏；不更改同意和停止状态。 |
| EMU-019 | P2 | 跳过/未复现 | 现行 BuiltinTools 已对无授权 KB 交集返回 warning；Python 路径拒绝越权 ID。未复现报告路径，无回退扩大范围。 |
| EMU-020 | P3 | 已修源码 | ChatViewModel：按 conversation ID 隔离草稿。 |
| EMU-021 | P2 | 已修源码 | MainScreens：route 单向驱动导航，去除竞争反向同步。 |
| EMU-022 | P3 | 跳过/未复现 | 现行 read_text 按严格 UTF-8，不按扩展名；新增 jpg文本可读/txt非法UTF8拒绝反例。原400MiB JPG字节未核验。 |
| EMU-023 | P2 | 部分修复 | 摘要失败有保留历史的新会话恢复入口；无效 JSON 仍失败关闭，不自动重试收费请求。 |
| EMU-024 | P3 | 跳过/未复现 | 基线非秘密 Provider/Model draft 已 rememberSaveable；apiKey 不能存 Bundle。未复现报告旋转丢失。 |
| EMU-025 | P2 | 跳过/未复现 | 4MiB 总quota包含存量是既有合同；配置入口/参数需产品决定，未暗改。报告 SAF patch/replace 与 UNSUPPORTED 边界混淆。 |
| EMU-026 | P3 | 已修源码 | ToolErrors / adapter / AgentRuntime：ENTRY_NOT_FOUND 与丢失 workspace 分开。 |
| EMU-027 | P3 | 跳过/未复现 | SAF ACTIVE 投影仅凭持久 grant/registry 的可达性缺口有源码线索；实际 renamed-provider 复现链未完成，未改。 |
| EMU-028 | P3 | 已修源码 | WorkspacePickerUi / MCP：报告静态标签中英映射，真实目录名保持。未承诺全仓 i18n。 |
| EMU-029 | P3 | 已修源码 | Settings：显式 REJECT / KEEP_EXISTING 选择；保留本机同 ID 配置和历史，显示 warnings。 |
| EMU-030 | P2 | 部分修复 | 合法 schema message/error/reason/path 等字段不误删；匿名工具/远端 prose 旧隐私门禁保持，语义描述不足未修。 |
| EMU-031 | P3 | 已修源码 | 与 EMU-004 同一 Checkbox 状态路径。 |
| EMU-033 | P3 | 跳过/未复现 | 报告重复citation/工具体积路径未在本机原材料复现；保留来源完整性，不猜减字段或声称预算问题已修。 |
| EMU-034 | P3 | 跳过/未复现 | 页渲染与上下文不同不满足 ADR0016 exact-request 去重；未改成图片单hash/近似去重。 |
| EMU-035 | P3 | 已修源码 | KnowledgeViewModel：数据库错误安全投影，成功刷新清理读取错误。原 no such table 根因未确认。 |
| EMU-036 | P2 | 已修源码 | KnowledgeRepository：正常追加不使暂停/等待 peer 批次失败；删除、替换、rebind、外部未知 generation 仍阻断。 |
| EMU-037 | P3 | 已修源码 | ChatUi：纯文本降级独立可见提示。 |
| EMU-038 | P3 | 已修源码 | KnowledgeUi：长文件名与状态分行布局。 |
| EMU-039 | P2 | 跳过/未复现 | 同URI reauthorization 失败无原 provider状态/URI证据；backend每次重探并非缓存，未确认原因，未改。 |
| EMU-040 | P2 | 部分修复 | 前台重新核对并 KEEP 入队，下一投递回收遗留状态；原 ed0df042 WorkManager 停滞机制未确认为此唯一根因。 |
| EMU-041 | P2 | 部分修复 | 与 EMU-023 同一路径；原会话摘要仍按格式验证失败关闭。 |
| EMU-042 | P2 | 跳过/未复现 | full-device unavailable 原通道日志/句柄状态不足，本轮无法确认恢复链，未改。 |
| EMU-043 | P2 | 已修源码 | ShizukuDirectoryHandleStore：按 UTF-8 JSON 字节预算分页，游标从未发送项继续；32KiB不放宽。 |
| EMU-044 | P1 | 跳过/未复现 | 握手 session 覆写、service death 重绑有源码线索；没有真实Binder/UserService复现证据，未盲修。P1仍未解决。 |
| EMU-045 | P1 | 已修源码 | AgentsViewModel：用户明确重确认的 stale grant 新 ID/current policy 落盘，保留原期限范围；旧授权撤销。 |
| EMU-046 | P2 | 跳过/未复现 | sleep超时远端进程孤儿未取得进程/dispatch原日志；保持UNKNOWN不自动重试语义，未改。 |
| EMU-047 | P3 | 跳过/未复现 | 报告一次性观察，危险模式持久化竞态未稳定复现，未改。 |
| EMU-048 | P3 | 跳过/未复现 | 多文件服务进程的生命周期原证据不足，未确认泄漏根因，未改。 |
| EMU-049 | P2 | 部分修复 | HTTP 历史保留为不可执行档案，导入警示和 HTTPS 配置→显式新会话入口；不改旧快照，不提供带历史自动转发 successor。 |
| EMU-050 | P3 | 已修源码 | AgentsUi / KnowledgeUi：管理卡片随页面滚动，去除小块固定高列表。 |
| EMU-051 | P3 | 跳过/未复现 | Markdown普通行合并只是结构优化；无原始gfxinfo/阶段数据，性能问题未关闭，不复用撤回的系统PID掉帧计数。 |
| EMU-052 | P2 | 跳过/未复现 | 提示词历史默认折叠为源码优化；11.81秒仅单次测量，主线程editor load仍需测量，未宣称时限/倍率通过。 |
| EMU-053 | P2 | 跳过/未复现 | 目录枚举在IO，所谓SAF同步扫描原因未证实；未取得阶段trace，未改。 |
| EMU-054 | P3 | 已修源码 | SettingsUi：Feature chips FlowRow 按完整宽度换行。 |
| EMU-055 | P3 | 已修源码 | ProvidersVM / Settings：报告中的校验/不可用说明中英投影。 |
| EMU-056 | P3 | 已修源码 | ProvidersUi：imePadding 使保存/取消避让键盘。 |
| EMU-057 | P3 | 已修源码 | MainScreens：危险模式 DISABLED 的暂停说明优先于 stale grant 状态。 |
| EMU-058 | P2 | 部分修复 | 逐会话流式导出、源变化栅栏与可见安全原因；原46会话失败没有原DB复测，不能断言OOM。 |
| EMU-059 | P1 | 部分修复 | 无需Application重启的 worker 再投递重建队列、结清视觉诊断，预检失败有界停止；原批次未取得DB/WAL/日志，不能断言已还原唯一根因。 |

## 验证记录

本地执行环境：Windows LIU / Git Bash；隔离分支 `codex/acceptance-r2-20261001`，SDK 已安装的 API36 google_apis x86_64，独立新建 AVD `codex_acceptance_r2` / `emulator-5580`。设备 HTTP 均为 Ktor MockEngine，未调用真实收费 Provider。日志保存在隔离 worktree 的 `.private/acceptance-20261001/`，不把本机缓存、数据库或原用户材料提交。

| 检查 | 实际结果 | 证据/界限 |
| --- | --- | --- |
| 修改前两项合法导入回归 | FAILED 2/2 | `knowledge-repro-before-valid.log`：再投递恢复与 peer append 栅栏。更早 `knowledge-repro-before.log` 含 fixture 误改不可变 attempt 的错误，不作为根因证据。 |
| 批次/归档定向 JVM | 最终 PASSED | `knowledge-transfer-after.log`、`archive-fence-regression-retry.log`；后者 10/10，通过 Run/Audit 交错提交、会话时间不变的反例。 |
| 完整 reviewGate、CI pins、锁与校验和 | PASSED，退出 0，2m45s | `final-reviewGate-retry.log`；`--offline --dependency-verification=strict --no-daemon --console=plain`，1085 task，75 executed / 1010 up-to-date。包含 root check、许可正反向、lint、非调试 review APK、安全/16KiB/notices/SBOM/provenance。未绕过依赖验证。 |
| JVM XML 汇总 | PASSED：1550 次执行，0 failures/errors/skipped | 多构建变体及缓存报告，非 1550 个唯一用例；data/sqlite 350/350，其中 KnowledgeBatchVision 27/27、TransferRepositoryIsolation 10/10；ContextCompactionRuntime 16/16。`final-jvm-results.json`。 |
| REUSE | PASSED，793/793 | `final-reuse.log`；AGPL 第一方政策未改。 |
| 公告 rollout/worker、native alignment、runtime notices 回归 | PASSED，四个命令退出 0 | `final-announcements-rollout.log`、`final-announcements-worker.log`、`final-native-alignment-test.log`、`final-runtime-notices-test.log`。 |
| API36 第一轮定向设备测试 | FAILED：69 执行 / 4 failures / 0 skips | `device-mock-api36.log`。MCP fixture 缺容器初始化；首启通知弹窗遮挡 Knowledge 组件；两个 Vision mock 配置的 4096 上下文小于生产图像预检 6176 输入单位。 |
| API36 重跑与新增导航/目标测试 | 中间 19 执行 / 1 failure；最后失败项已关闭 | `device-regressions-final.log`：导航 8/8、目标 3/3 及原失败大部分通过。剩余断言误期待首次429即终止，现按既有最多6次授权批次重试验证。 |
| API36 Vision 与归档最终 | PASSED：3/3，0 failures/skips | `device-vision-transfer-final.log`：固定非默认目标、429诊断/六次停止、归档 fresh DB 恢复。各批次覆盖共80个不同设备用例，全部至少一次 PASS；不是同次全量执行。 |
| 失败修复过程 | 均记录，最终无未关闭本地测试失败 | 曾修正 smart-cast 编译错误、恢复同步误覆盖显式视觉继续队列、ZIP 测试对 JDK 分段写入的错误假设；另有新增断言的错误列名和漏 import。中间日志保留，不把失败过程包装成一次通过。 |
| 两个独立只读审查 | REVIEWED | chat/MCP/UI 与 durable recovery/权限/归档分开审查；发现两个 P2（全归档一致性、空库首次独立追加）和目录名误翻译 P3，修复后复核未发现新的 P1/P2。审查者未冒称运行门禁。 |
| 原 DB/WAL、真实 Shizuku Binder/UserService、真机、真实 Provider、ANR 因果、性能/一小时 soak | NOT RUN / 未确认 | 原约248MiB证据不在消费端；按用户指令跳过，不能把 mock、源码或脚本重启当作这些验收通过。 |
| 修复后 GitHub CI / main 集成 | 以 PR checks 和最终本地 HANDOFF 为准 | 本记录随修复提交；主 Agent 只在 CI 合格后正常合入并 fetch 核对 main，不修改保护规则、强推或部署发布。 |

原报告 §14.2、§14.3、§14.4 没有在本轮被重新判定通过。31 项源码已修 / 9 项部分修复 / 18 项跳过或未复现均按上表明确交付；缺少原始因果证据的条目继续保留。
