<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-09-05T15:09:06+08:00（Asia/Taipei；Agent 规则审查记录）。项目根目录：`E:\mobileAgentRuntime`。

> 本文件只保存当前事实、未决边界和接手动作。已完成工作的详细过程保存在 [证据目录](docs/evidence/) 和 Git 历史中，不再在这里重复流水账。

接手者必须依次阅读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。修改完成、受阻或中断前必须同步本文件及受影响的专题文档。

## 1. 当前状态

> **本次路由建议补充**：用户进一步要求主 Agent 获得更多子 Agent 调度自主权，同时考虑性价比。建议以任务总成本（调用、上下文重复、等待和返工）选择模型与推理强度，解除普通工作一律 Luna xhigh 的固定下限，允许按明确困难信号升级；简单任务主 Agent 直接完成，独立工作再委派，高成本 Astra 子 Agent 用于需要额外独立推理的复杂问题。此处仅记录建议方向，尚未修改全局路由或任何模型配置。独立只读规则审查已返回，确认读取/交接适用范围、授权悬空引用与 reverse-skill 文本问题。

> **Agent 规则审查（2026-09-05T15:09:06+08:00，建议阶段）**：本次范围仅为全局 `C:\Users\32735\.codex\AGENTS.md`、项目 `AGENTS.md` / `agent.md` 的静态审查及 GPT-6 Astra 适配建议；未修改这些规则或产品代码。建议：补齐授权规则的悬空引用、修复 reverse-skill 名称断行；按任务分级读取文档和维护交接；明确 Astra 主 Agent 与既有 Luna/Sol 子 Agent 路由的职责边界；增加按风险选择验证及停止复查的条件；区分现行规范、历史证据与任务授权。已读取官方 Astra 与 AGENTS.md 指导；这是规则建议，不是产品安全复核或运行验收。Git 只读核验退出码 0：根目录 `E:/mobileAgentRuntime`，分支 `main`，实际 HEAD `b07b4b3ab61360b8034e897944eea7a5ca0b3793`，与下方旧快照中的 `a361a39` 不同；未核验实时远端，本轮未重判历史产品交付状态。保留已有文档 WIP 及 `.tmp-diag3/`、`.workbuddy/`、`docs.zip`，不读取保护内容。仅按现行收工规则补此交接；未运行构建或设备测试（无产品修改），未 commit/push/deploy。下一步如用户要求落地，按选定建议同步修改规则；本轮未改变架构、接口、验收或许可，无需同步产品专题。

> **本次交付（2026-09-05，源码已提交并推送，DOCS LOCAL, NO DEPLOY）**：架构收敛任务（prompt 见 `C:\Users\32735\Downloads\mobile-agent-runtime-architecture-convergence-review-prompt.md`，13 个 finding 全部逐项确认：12 CONFIRMED、§4.2 按“元数据版本冒充内容版本”确认）。Phase 1 正确性五项全部修复并测试；`RunManifest`/`RunCoordinator`/索引生命周期/coverage/多 Skill 身份/工作预算/coding+knowledge+memory 基准已实现并测试；`cancel()` durable 写入、`model.invoke` 编辑器控件、SAF/Shizuku/Wired 与 knowledge/Python/ZIP/picker 的工作预算、大规模实测按设计留作后续（见 §5）。验证：strict gate **1030 tasks `BUILD SUCCESSFUL`**（217 executed；含 `compileDebugAndroidTestKotlin`）；JVM 聚合 495 tests 0 失败；REUSE 589/595（缺失 6 个均为任务前已有且禁止触碰的 `.tmp-diag3/`×4、`.workbuddy/`×1、`docs.zip`×1）；`codegraph sync .` 43 files。源码与测试已提交为 `b07b4b3ab61360b8034e897944eea7a5ca0b3793` 并以普通快进推送至 `origin/main`（`git ls-remote` 已复核远端同 SHA）；提交不含 `HANDOFF.md`、`docs/`、受保护目录。设计取舍见 [ADR-0007](docs/adr/0007-architecture-convergence.md)，命令与结果见 [本轮证据](docs/evidence/2026-09-05/architecture-convergence.md)。独立只读安全复核尚未做（本轮改动触及授权/持久化/执行边界，按规则必须复核）。新增 device 测试因本机无 emulator/device（`adb devices` 为空）仅编译通过，未执行。

> **本次交付（2026-09-04，SOURCE PUSHED / DOCS LOCAL / NO DEPLOY）**：人工测试前收口源码 36 个路径已形成提交 `a361a3946b25f0d3a01aca1f5d949b959bdb9b67`，以普通快进推送至 `origin/main`（`a45ca10..a361a39`），并通过 `git ls-remote` 复核远端同 SHA；提交不包含 `HANDOFF.md`、`docs/`、`.tmp-diag3/` 或 `.workbuddy/`。内容：Desktop Bridge 跨平台 fixture、导航 IA 去 More 化（Drawer 十目的地/Menu-Back XOR/Back 回 Chat）、Shizuku 真分页（`MAX_LISTED_ENTRIES=8192` + 浅 fingerprint + 可复用 cursor）、Picker 双端 continuation + 加载更多、Responses（store 默认 false/加密 continuation 同 run 回送/refusal 独立输出/探测 64-128 clamp）。验证：strict gate 1021 tasks `BUILD SUCCESSFUL`（SBOM 171）；`:desktop:bridge:test` PASS；JVM 聚合（provider/agent-runtime/skills/serialization/domain/sqlite）PASS；API 36 全量 connected 382 tests 0 失败（6 skip 为既有 skip）；ReleaseGate 新语义 7/7；REUSE 570/575（缺失仅受保护目录既有 5 文件）；`codegraph sync` 已执行。APK 见下述构建步骤（构建后回填哈希）。未部署、未正式签名发布、未调用真实/付费 Provider。

> **人工测试 APK（2026-09-04，基于 `a361a39` 源码构建，`debugEvidenceGate` 后最终产物）**：`app-android/build/outputs/apk/debug/app-android-debug.apk`，214136729 bytes，SHA-256 `9CD0143C2F4A130E73081DBD7C856E719979F4A13D9236D23D2479617352681F`，包名 `runtime.mobileagent`、versionName `0.1.0`、versionCode `1`，Android Debug 签名（`apksigner verify` 通过）。未安装到设备、未部署；产物已就绪供人工安装测试。

> **前次状态（2026-09-04，仍有效）**：`a45ca101ace1e2f490b15192f153a1bd94e124fc` 为本次推送前的远端基线；纯文档本地提交 `e9615bb67bf3d8e2d6ff86a2c710ef985a5aa469` 仍在仅本地分支 `codex/local-docs-20260904`；当前 `HANDOFF.md` 与 `docs/` 继续仅留本地、不推送。

> **当前任务 —— OpenAI Responses / 全局导航 Chrome / Workspace typed error（2026-09-04，源码已提交并推送，APK 已构建，DOCS LOCAL, NO DEPLOY）**：用户指定 `mobile-agent-runtime-responses-shell-workspace-runtime-fix-codex-prompt.md` 为本轮规范，`mobile-agent-diagnostics(5).zip` 为只读测试证据；任务在上一轮 18 个 tracked 文件的 dirty WIP 上继续，`.tmp-diag3/` 与 `.workbuddy/` 未读取、未修改。诊断 ZIP SHA-256 `67D7BD0ED1EB4C22743D8F816DF48A9466148C2C349A8DC9E789CB7916C89075`，build `e9615bb67bf3d8e2d6ff86a2c710ef985a5aa469-dirty`、schema 16、517/517 条合法有序事件；20 条 `workspace_operation_state` 中 10 started、8 succeeded、2 failed，失败项缺 backend 且 `errorCode=unknown`，不能从日志断言是大文件或具体 backend。代码根因是 `UnifiedWorkspaceToolExecutor.recordTerminal()` 只留下粗粒度 FAILED/DENIED，`SqliteRuntimeAuditSink.record()` 再转小写，而 diagnostics canonicalizer 不接受粗粒度 `failed`，因此 known backend failure 被降为 unknown。现已新增兼容旧持久化值的 `OPENAI_RESPONSES`、独立 `/responses` request/SSE adapter 与统一 factory；两种协议均进入同一 `AgentRuntime` / ToolExecutor 工具循环，tools capability probe 用强制 `tool_choice` 避免模型自行不调用工具造成假阴性。Shell 统一拥有 Scaffold + TopAppBar、Menu/Back、标题和 window insets，Feature 根页不再重复标题或依赖浮动 overlay；特权工作区根目录、子目录、向上导航与选择失败均保留 typed code 并显示安全可操作文案。Internal/SAF/Shizuku/Wired 的 list 保留超大文件元数据但在 read 返回 `FILE_TOO_LARGE`，symlink/特殊/瞬时不可读条目不跟随并安全跳过；list 返回有界、无路径的 `skippedEntries`/`warnings`，生产 Shizuku parser 会校验计数、类别与 opaque cursor，旧协议缺字段兼容为零值。SAF 缺 size 时有界探测；SAF grant 丢失统一映射 `PERMISSION_DENIED`；Wired helper 不再把既有 `FILE_TOO_LARGE` 重复包装成 `FILE_FILE_TOO_LARGE`，桌面 frame decoder 也显式接受并原样保留该 code；`BRIDGE_PROTOCOL_MISMATCH` 等已知错误在 diagnostics 保持 FAILED，不降为 unknown。题设 Workspace Cases A-E 现有直接自动化覆盖，包括 Internal/Shizuku/Wired 的真实 FIFO unsupported entry、desktop warning payload 与 `FILE_TOO_LARGE` framed error。API 36 模拟器最终定向批次 179/179 PASS（0 fail/skip）；最终 strict gate 1021 tasks `BUILD SUCCESSFUL`（license guard、CI pin、dependency lock/verification、全仓 check/lint、Debug APK、171-component SBOM），聚合 JVM 65 suites / 463 tests PASS（0 fail/error/skip），并已按最终源码执行 `codegraph sync .`；独立复核最终结论 `APPROVE`。未调用真实/付费 Provider，未做真实 Provider 网络验收；物理 Wired USB 与真实刘海/打孔设备仍未验收。

> **当前任务 —— Agent 默认工作区自动授权 / Unbound Thread UX / Menu-Back XOR / diagnostics（2026-09-03T21:45:28+08:00，本地实现，NO COMMIT, NO PUSH, NO DEPLOY）**：规范来自用户指定的 `mobile-agent-runtime-default-workspace-unbound-thread-nav-prompt.md`，诊断证据为只读检查的 `mobile-agent-diagnostics(4).zip`（SHA-256 `9016471D540A6D3456736D720F2A3EFDBAFF8D7E53E404EBA060DB9074673A33`）。ZIP 显示 Shizuku 与两次 `read_write` workspace grant 均成功，真正断点在上层：没有 Agent default、Conversation resolution/binding 或 tool exposure 证据。现已把已有 Agent 的选择统一到 canonical `SET_AGENT_DEFAULT`，按后端能力精确生成 PERSISTENT grant（显式 workspace/file allowlist，READ_ONLY 不含写/删/移动/patch，READ_WRITE 包含后端支持项，永不自动授予 shell），重复选择复用 grant；高级权限撤销后不静默恢复，只有重新选择才再授权。新建 Agent 使用 WorkspaceDraft，保存才提交 Agent+grant+default，取消或过期异步结果不产生/串入 grant。旧 unbound Thread 不继承后来设置的 default；Chat 显示 BOUND / UNBOUND+DEFAULT AVAILABLE / UNBOUND+NO DEFAULT，并通过显式按钮创建绑定 default 的新 Thread；Drawer 行按「workspace · agent」展示。Shell 导航采用 route policy 保证 Menu/Back XOR，overlay 抑制底层 Menu。新增 `agent_workspace_default_changed` 与 `conversation_workspace_resolution` 闭合脱敏事件；configured-but-revoked default 保留匿名 ref 但仍判为不可用。编译通过；API 36 `emulator-5554` 上相关批次 46/46 与最后竞态/只读 UI 批次 33/33 PASS，Provider 回归 7/7 PASS；最终 strict gate 1021 tasks `BUILD SUCCESSFUL`（license guard、CI pin、28 个 lockfile、dependency verification、全仓 check/lint、Debug APK、171-component SBOM），独立标准复审 `APPROVE`。本轮 18 个变更文件逐文件 REUSE 18/18 PASS；全目录 REUSE 仅被任务前已有且禁止触碰的 `.tmp-diag3/` / `.workbuddy/` 5 个 untracked 文件阻断。物理 Wired USB 仍为 `E2E_BLOCKED`，真实打孔/刘海设备仍为 `REAL CUTOUT VERIFY REQUIRED`。

> **本轮任务 —— NewThreadRequired 零副作用确认与运行时授权收口（2026-09-03T08:35:00+08:00，本地修改，NO NEW COMMIT, NO PUSH, NO DEPLOY）**：任务规范来自用户 prompt。修复了已绑定 Thread 在选择新工作区时提前落盘 Agent capability grant 的语义副作用漏洞。确认前取消或关闭弹窗严格保持原 Thread 为 W1，Agent default 不变，Agent W2 persistent grant count 保持 0，无 Snapshot W2，无 Conversation W2。确认流程收口到 Runtime canonical seam `WorkspacePickerPort.confirmNewThreadWorkspace(agentId, currentThreadId, currentWorkspaceId, requestedWorkspaceId)`：进行严格 revalidation（Agent 存在、Thread 绑定未发生 TOCTOU、工作区有效及 SAF/Privileged 选定且就绪检查，特权失效 fail-closed 不 fallback），并在单事务中持久化 `persistWorkspaceGrantBundle`（自动复用既有活跃 grant 不产生重复）。UI 弹窗确认按钮接入 `confirmNewThread`，成功后再分发新会话。自动化测试已全线通过：离线单元测试通过，API 36 真实模拟器（`emulator-5554`）上 `RuntimeThreadWorkspaceDeviceTest` 15/15 PASS、`WorkspacePickerContractTest` 4/4 PASS、`CanonicalWorkspaceCoordinatorTest` 4/4 PASS、`ProvidersViewModelConnectionDeviceTest` 4/4 PASS、`GlobalConversationUiTest` 9/9 PASS。本轮未执行 git commit，未 push，未 deploy。

> **上一轮 follow-up —— 源码已提交并推送（2026-09-03T07:40:00+08:00）**：任务规范 `mobile-agent-runtime-followup-thread-provider-ui-prompt.md`。源码/测试提交 `17a695eb20e16775ae834e1ecb5db2677dfedf3a` 已快进至 `origin/main`，作者/提交者均为 `luozhibai <wy3273564266@163.com>`，无其他贡献者 trailer。已完成：已绑定 Thread 的 workspace 不可变（切换即新 Thread）、Provider ViewModel/adapter 集成回归、全局 Menu IconButton、Insets 代码与 Compose 约束测试、Workspace UI presentation fallback。Insets 验收级别为 `IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED`。`HANDOFF.md` 与 `/docs` 只做本地提交，不推送。未部署、未正式发布。

> **上一轮 Android Runtime/UI 收口 —— 源码已提交并推送（2026-09-02T22:22:00+08:00）**：源码/测试提交 `2d35933d6162e849387b45652856d45036eadba9` 已快进至 `origin/main`。`HANDOFF.md` 与 `/docs` 只做本地提交，不推送。21:20 交接仍写“进行中”，且未声称 Conversation TopBar / Provider 小屏 / Insets / Workspace canonical flow 已彻底修好。本轮复核了那份交接，并对照真机包 `mobile-agent-diagnostics(3).zip`（SHA-256 `82774409adcba30a70976abff976a7cceffcfdfa3df12772318fe79fa6a75ee2`，APK revision `231e07b-dirty`，早于基线 `6394896`）。断链是：Agent 编辑页 attach+grant 但不设 default → 新会话 `resolveNewThreadWorkspace=null` → snapshot unbound → `grantedWorkspaceCount=0/boundWorkspaceCount=0/workspaceToolCount=0`，`reasonCode=no_effective_agent_grants`。现已收口为唯一 canonical intent，并用 `ChatViewModel.newSession()` 回归该产品路径。草稿路径曾把 `setAsAgentDefault=true` 直接写入 persist 事务，无 Agent 时抛错并显示 `PERSISTENCE_FAILED`；现仅在 Agent 已存在时落 default。定向仪器批次 36/36 PASS（API 36 emulator `mar_api36_debug`）。全仓 strict gate、REUSE、独立安全复核、物理 Wired USB 本轮未做。

> **上一轮 P0 源码草稿（2026-09-02T19:45:00+08:00）**：领域模型/coordinator/sink/draft 已存在，但当时 androidTest 旧签名未修，display-only presentation、Insets、TopBar、Provider 响应式布局未完成。上述缺口已由本轮补齐。

> **上一轮（2026-09-02T19:15:35+08:00，Workspace/UI/Provider 结构修复）**：用户指定 `C:\Users\32735\Downloads\mobile-agent-runtime-codex-workspace-ui-provider-fix-prompt.md` 为本轮任务规范，并提供 `mobile-agent-diagnostics(2).zip`（SHA-256 `D9A2E9D9221F5302F1FA39E42EC0176E3A165C7EBF1699819EAB3FAF972A14D6`）作为诊断证据。归档 manifest 为 `3bab147af26af65c5584ca42245420681c79c5cc-dirty`、schema 14；它实际记录 Shizuku selected/granted/ready/connected、一个 workspace grant 成功、7 个 workspace tool 暴露及两次 enumerate 成功，未记录 attach、tool call、approval、shell 或 `runtime_tooling_unavailable` 失败，不能被误称为本轮新故障的直接复现。当前源码已完成 Android Keystore AES-256-GCM privileged locator、Shizuku/Wired 可恢复 binding 与 reattach、Thread-bound workspace、Agent 多 workspace grant/default、分页/分块/stat/apply-patch typed file tools、全局 Drawer/极简 Conversation/Workspace Picker、Reasoning/Diff/Error 消息、Provider typed connection/probe 和闭合诊断；选择器在未选择 Agent 时已禁用“打开工作区”，避免实际无法持久化时误报通用失败。严格全仓 gate、REUSE、API 36 目标设备测试及 Shizuku picker 人工 smoke 均通过；独立复核提出的 Wired 浏览空实现、SAF 大文件分块上限与 Wired UTF-8 偏移三项缺陷均已修复并复审通过。物理 Wired USB 断连恢复仍为 `E2E_BLOCKED`。用户随后明确授权 commit 与 push；93 个源码/测试路径已提交为 `639489622c74bcaa5cca970c46c1dcb77e6754cb` 并快进推送至 `origin/main`，`git ls-remote` 已复核远端同 SHA。`HANDOFF.md` 与 `/docs` 继续遵守既有边界，仅形成当前本地文档提交，不推送；未部署、未正式发布。

> **当前任务（2026-09-02T09:24:22+08:00，源码已提交并推送）**：用户提供的 `mobile-agent-diagnostics(1).zip`（SHA-256 `93B214980010FFA7B974DE7B21C67B9EE17DF1D8948352AF1CC303CB6346E845`）显示 14:22:59 已写入 `workspace_grant_changed(granted=true)`，但 UI 随后仍显示“工作区操作失败”。根因是 SAF 持久权限、DB workspace/grant 事务与 backend 注册全部成功后，返回 DTO 又重新读取 workspace/grant 状态；真机现有数据上的这次多余投影异常被 UI 外层统一折叠为 `UNKNOWN_OUTCOME`，造成“实际已提交却显示失败”。现已从同一事务返回的确定 grant bundle 直接组装成功结果，且只在结果组装完成后记录成功诊断，不再进行成功提交后的仓库重读。源码与测试已提交为 `71ad5443e62c9c7d6852722f80a57f3221ebe4a6` 并以普通快进推送到 `origin/main`，`git ls-remote` 已确认远端指向同一 SHA；本轮没有继续修改用户已明确不满意、留待后续重做的 UI，也没有部署或正式发布。

> **本地文档边界**：基于人工日志 `mobile-agent-diagnostics-1.zip` 的工作区与整体交互重构已随上述源码提交进入远端。按用户既有明确要求，`HANDOFF.md` 与 `/docs` 变更仅形成后续本地文档提交，不推送；因此本地 `main` 会比 `origin/main` 领先一个纯文档提交。新架构采用“稳定 Agent→Session 侧边栏 + 独立 WorkspaceAccess 深模块 + 每次 Run 冻结权限”：SAF 快速选择、selected Authority 下的设备目录、完整设备文件（ADB 可见范围）三条入口统一到同一授权门面；Shizuku 与 Wired ADB 只在 UI 上归类为 ADB 级系统访问，内部仍保持两个不回退的 Authority。API 31 的真实 SAF/Shizuku、95 项聚合设备批次、全仓 strict gate 和修复后复核均已通过；物理 Wired ADB 断连恢复仍为设备阻塞。

> **现行产品修正**：Agent 可拥有多个持久 workspace grant，并可设置只影响新 Thread 的默认工作区；每个 Thread 绑定一个 canonical workspace，创建后的绑定不随 Agent 默认值变化。当前 Run 继续冻结 workspace/grant/revision/Authority；撤销某个 workspace 不会自动回退到另一个 workspace。旧会话只有在迁移时能证明唯一有效 workspace 才自动绑定，否则保持未绑定。Workspace 选择只解析为 `ADD_TO_LIBRARY` / `SET_AGENT_DEFAULT` / `BIND_THREAD`。Agent 编辑默认是 attach → grant → set default；新建 Agent 允许 draft，保存时再提交。Conversation picker 只 `BIND_THREAD`。无 default 的新会话 unbound，不 fallback。

| 项目 | 当前事实 |
| --- | --- |
| 产品 | 权限、工具与危险模式 v2.3 已完成生产接线：统一 Capability/Workspace/Authority/Approval/Audit，Internal/SAF/selected privileged workspace，Skill Memory，Shizuku，Windows 有线 USB ADB Companion，持久 Dangerous Mode 与受控 `shell_exec`。公告、Provider、Agent、Knowledge、Skills、诊断和请求检查器均已有实现 |
| 工作区授权 | Agent 可授权多个 workspace；默认 workspace 只用于新 Thread。Thread 首次绑定后 workspace 不可变；再选其他 workspace 不改写 binding/snapshot，确认后创建新 Thread。有效 canonical capability grant 直接授权 typed workspace 操作，不在 Chat 重复逐次确认。Run 启动时冻结 workspace/grant/revision/scope/policy，派发仍复核撤销、过期、workspace/path scope、selected Authority，并原子消费 ONCE grant |
| Authority | 仅 `SHIZUKU` 与 `WIRED_ADB` 两个平级 elevated Authority；只派发 selected provider，失效时 fail-closed，不自动 fallback。selection、user intent、configured、grant、trust、workspace、完整设备授权和 Dangerous Mode 均持久；Binder/USB/Wi-Fi/Companion 断联只改变 availability/connection，不撤权、不隐藏 schema |
| 真实 E2E | 本轮 API 36 emulator 已验证 Shizuku selected/granted/ready/connected、受控目录浏览及 picker 安全边界；目标仪器批次 66/66 PASS。既有 API 31 x86_64 已用系统 DocumentsUI persisted SAF grant 和官方 Shizuku 13.6.0 UID 2000 UserService 完成模型侧 list/read/create/read/delete 与 `/system/bin/sh` E2E；物理 Wired USB 仍未验收 |
| 独立复核 | `6394896` 基线上的独立复核结论为 `APPROVE`（Wired browser / SAF chunk / Wired UTF-8 offset）。**本轮 canonical flow / Insets / TopBar / Provider 布局尚未做新的独立安全复核**。物理 Wired USB 仍须独立真机验收 |
| Git | 分支 `main`。本地与远端 `origin/main` 均为源码提交 `b07b4b3ab61360b8034e897944eea7a5ca0b3793`（`git ls-remote` 已复核远端同 SHA；`a361a39..b07b4b3` 普通快进）；该提交不含 `HANDOFF.md`、`docs/`、受保护目录。当前 `HANDOFF.md` 与 `docs/` 继续仅留本地、不推送。不要把 `.tmp-diag3/`、`.workbuddy/`、`docs.zip` 加入提交 |
| 许可 | 第一方保持 `AGPL-3.0-only`；本轮未改 lockfile 或许可文件 |

## 2. 本轮修复

### 2.0 NewThreadRequired 零副作用确认与运行时授权收口（本地修改，NO NEW COMMIT, NO PUSH, NO DEPLOY）

**漏洞与根因**：此前在已绑定工作区 W1 的 Thread 选择新工作区 W2 时，`RuntimeIntegration`（`useRecentWorkspace`、`attachSafWorkspace`、`persistPrivilegedAttachment`）在触发 `requiresNewThread` / `switchingBoundThread` 之前，在事务中直接调用了 `persistWorkspaceGrantBundle`。这导致即便用户随后在弹窗中点击“取消”，Agent 也会被提前持久化授权 W2 的能力集，违反“用户未确认创建新会话前不得新增持久授权”的核心原则。

**修复方案**：
1. **零副作用预检**：在 `useRecentWorkspace`、`attachSafWorkspace` 和 `persistPrivilegedAttachment` 中检测到 `switchingBoundThread` 时，仅进行合法性校验与工作区/特权绑定准备（SAF 保存 workspace+safGrant，Privileged 保存 workspace+binding 并注册 backend），**严禁调用 `persistWorkspaceGrantBundle` 或 `persistPickerTarget`**。若 Agent 之前已有对 W2 的有效活跃 grant，标记为 `NewThreadAuthorizationState.ALREADY_GRANTED`（`requiresGrantCommit = false`）；否则标记为 `NewThreadAuthorizationState.REQUIRES_CONFIRMATION_COMMIT`（`requiresGrantCommit = true`）。
2. **运行时收口**：在 `WorkspacePickerPort` 新增 `confirmNewThreadWorkspace(agentId, currentThreadId, currentWorkspaceId, requestedWorkspaceId)` 门面并在 `RuntimeIntegration` 中实现：
   - 验证 Agent 存在；
   - 验证当前 Thread 依然绑定于 `currentWorkspaceId`（防 TOCTOU，若不匹配返回 `CONFLICT`）；
   - 验证目标工作区存在且处于启用目录范围；
   - 验证权限：Privileged 检查 Authority 选定且就绪（失效 fail-closed，严禁自动 fallback 到其他 Authority）；SAF 检查 persistedUriPermissions；
   - 在单个 SQLite 事务中原子调用 `persistWorkspaceGrantBundle(workspace, registered.backend, WorkspaceAccessGrantTarget(agentId))`（自动复用既有活跃 grant 不产生重复）；
   - 返回 `WorkspaceAccessResult.Success`。
3. **UI 链路收口**：`WorkspacePickerViewModel` 增加 `confirmNewThread(pending, onConfirmed)`；`MainScreens.kt` 弹窗确认按钮调用 `confirmNewThread`，成功后再分派 `chatVm.selectAgent` 和 `chatVm.newSession`；弹窗文案根据 `requiresGrantCommit` 明确告知将要创建新会话并授权。取消直接清空结果，不触发任何授权写入。
4. **测试与回归**：
   - `RuntimeThreadWorkspaceDeviceTest` 15/15 PASS（更新原有切换测试，验证确认前 0 grants，确认后新 session 工具完整生效；新增 8.1 取消零副作用、8.2 确认复用无重复、8.3 状态陈旧防穿透、8.4 Authority失效 fail-closed 不回退测试）；
   - `WorkspacePickerContractTest` 4/4 PASS；
   - `CanonicalWorkspaceCoordinatorTest` 4/4 PASS；
   - `ProvidersViewModelConnectionDeviceTest` 4/4 PASS；
   - `GlobalConversationUiTest` 9/9 PASS。

### 2.1 Thread workspace 不可变、Provider 连接链、全局 Menu / Insets（源码已推送 `17a695e`；文档仅本地）

**Thread workspace**：`ConversationWorkspaceBinding` 首次 bind 后不可改 workspace。已绑定 Thread 选择另一 workspace 时 `RuntimeIntegration` 不改写 binding/snapshot，返回 `WorkspaceAccessResult.NewThreadRequired`。UI 文案为“工作区属于当前会话上下文，切换将创建新会话。”确认后 `ChatViewModel.newSession(requestedWorkspaceId)` 用同一 Agent 创建新 Thread 并冻结目标 workspace。未绑定 Thread 可首次 bind，之后同样不可变。本轮不做 snapshot rollover。Agent 编辑页若撞到该结果映射为 `CONFLICT`，不走会话分叉。

**Provider**：`ProvidersViewModel` 增加最窄 `ProviderAdapterFactory`；`testConnection()` 与 `probe()` 共用 `adapterFor()`，生产路径仍是 `OpenAiCompatibleAdapter`。`ProvidersViewModelConnectionDeviceTest` 真实调用 `testConnection()`，覆盖 200、401、404/`MODEL_NOT_FOUND`、429、timeout、network、malformed、capability PARTIAL 不改写 connection success、failure→success retry、切换 model 后旧 generation 不污染 UI。未调用付费 Provider。此前仅有 renderer 构造 `ConnectionCheckUi` 的测试不能把“测试连接总失败”标为已修；本轮该集成测试 PASS 后才关闭这条缺口。

**全局 Menu**：compact opener 为 48dp `IconButton(Icons.Menu)`，`contentDescription` 为「打开菜单」/「Open menu」，不再显示文字「菜单」。Chat TopBar 与非 Chat `GlobalDrawerShell` 同一视觉语言；wide layout 不显示 opener。

**Insets**：顶层 `AppNavigationScaffold` 只消费一次 `safeDrawing ∪ displayCutout`（排除 IME）；Chat 路由不消费 bottom system insets，Composer 使用 `ime ∪ navigationBars`。Compose 覆盖 320dp、fontScale 1.6/1.8、640×320 横屏约束。真机 punch-hole 无法自动模拟，状态为 `IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED`。

**Workspace UI presentation**：`WorkspaceUiPresentationStore` 是 SharedPreferences 显示元数据，不是授权/恢复 source of truth；取不到时 `fallbackWorkspaceUiPresentation` 从 canonical Workspace 生成安全标题。丢失 presentation 不使 Workspace 失效。`android:allowBackup=false` 未改。

普通模式不暴露 arbitrary shell；用户显式开启 Dangerous Mode 且具备 `shell.execute` capability 后，仍允许真正的 Android shell。Root/Wireless ADB 仍不实现。

完整命令与文件清单见 [2026-09-02 Thread/Provider/UI follow-up](docs/evidence/2026-09-02/thread-provider-ui-followup.md)。

### 2.2 P0 统一 Workspace canonical flow（源码已推送；文档仅本地）

**真机根因（diag3，不得当已修）**：Agent 编辑旧流程 attach+grant 不设 Agent default → 新会话 unbound → run 0 workspace tools。Global picker `BIND_THREAD` 在已有 Thread 上能暴露 tools，因此表现为“有时有工具、新会话没有”。

**产品语义**：

| Intent | 副作用 |
| --- | --- |
| `ADD_TO_LIBRARY` | 注册/复用 workspace；有 agentId 才 grant；不改 default、不绑 Thread |
| `SET_AGENT_DEFAULT` | attach + grant + Agent default。无 agentId 时 `deferred`：只入图书馆并暂存 draft |
| `BIND_THREAD` | attach + grant + `ConversationWorkspaceBinding`；禁止改 Agent default |

**写路径**：UI → `CanonicalWorkspaceCoordinator`（Agent 编辑）或 `WorkspacePickerPort`/`planFor`（Global Picker）→ `RuntimeIntegration` 单事务（workspace + grant + default/binding）。`WorkspacePickerTarget` 只保留 `agentId`/`threadId`，UI 不能再拼 grant/default。`planFor`：threadId 非空 → `BIND_THREAD`；仅 agentId → `SET_AGENT_DEFAULT`；否则 → `ADD_TO_LIBRARY`。`persistDefaultNow() = setAgentDefault && !deferred`。

**草稿**：新建 Agent 可在保存前选择 Workspace；`AgentsViewModel.stageWorkspaceDraft`；`save()` 先 `saveWithPrompt` 再 `commitDraft`；失败 `rollbackNewAgent`（revoke + delete）；`closeEditor()` 丢 draft。补偿回滚不是与 `saveWithPrompt` 同一 SQLite 事务。草稿 attach 会先把 workspace 写入图书馆，但不产生 Agent grant。

**新会话**：`ChatViewModel.newSession()` / `AgentsViewModel.createConversation()` 用 `resolveNewThreadWorkspace`；有 default 则 `createSnapshotWithWorkspace` 只冻该 workspace 的 grants，并写 `ConversationWorkspaceBinding`；无 default 则 unbound，不 fallback。Run 经 `createToolExecutionContextForWorkspace` 只看见 bound workspace。

**Display-only**：`WorkspaceUiPresentation` / `WorkspaceUiPresentationStore`。App private 固定文案；SAF 显示解码后的目录名，禁止 `content://`；特权路径如 `/storage/emulated/0/...` 仅 UI。不写入 `AgentWorkspaceUi`、tool schema、prompt、diagnostics。

**Insets / TopBar / Provider**：`setDecorFitsSystemWindows(false)`；Chat 路由不消费 bottom insets，Composer 使用 `ime ∪ navigationBars`。Conversation TopBar 为 Menu / Agent·Workspace / MoreVert IconButton（contentDescription「打开菜单」「更多选项」）。Provider：测试连接主操作、能力探测次操作、编辑/删除进 overflow；长 model id ellipsis。仍使用 typed `ProviderConnectionResult`/`ProbeState`。

完整根因、命令与证据见 [2026-09-01 Agent 侧边栏与持久工作区访问 v2.4](docs/evidence/2026-09-01/conversation-workspace-v2-4.md) 和 [工作区工具真实 E2E](docs/evidence/2026-09-01/workspace-tool-real-e2e.md)。

### 2.3 已完成的历史修复

- 特权 workspace locator 采用 Android Keystore AES-256-GCM，AAD 绑定 app instance、workspace、Authority 与 locator version；数据库只存密文/nonce，篡改、密钥丢失、AAD 不匹配均 fail-closed。Shizuku 与 Wired ADB 重新连接后以原 workspaceId 生成新的进程内 handle；暂时断联不撤销 grant/binding，也不自动切换 Authority。
- 数据库 schema 16 增加 privileged、Conversation 与 Agent default workspace binding；旧数据只有在能证明唯一有效 workspace 时迁移。Agent 多 workspace grant、Thread 固定 workspace、default 只影响新 Thread，并保持 CAS/revision 与 revoke-no-fallback 语义。
- typed workspace 工具增加有界分页、分块读取、大文件 stat 与原子 `file_apply_patch`；Internal 完整支持原子替换，SAF 等无法证明原子替换的 provider 返回 typed `UNSUPPORTED`，不冒充成功。
- 全局导航改为 compact Drawer / wide permanent sidebar，移除手机底部一级导航；新 Thread、Agent/Session、workspace picker、首消息错误、Reasoning/Diff/Error、IME 与 Back 优先级均接入。未选择 Agent 时 workspace picker 明确禁用持久化入口。
- Provider 将 Test Connection 与 Capability Probe 拆成 typed 独立结果，覆盖 success、401、404、429、timeout、metadata 不支持但 Chat 可用及 partial capability；UI 不再从 busy/string 推断成功。
- 独立复核后将 Wired workspace browser 接到既有 bridge 的 typed root/browse/opaque-handle attach，并从受控 `/storage/emulated/0` 起步；修正 SAF 超过 256 KiB 文本的有界分块读取，以及 Wired UTF-8 多字节字符的实际消耗字节偏移和 EOF 计算。三项均有新增设备回归并通过复审。
- 新增 privileged selection/binding/reattach、Conversation workspace、tool exposure、Provider connection/probe 等闭合诊断；所有实体引用 HMAC 化，禁止 locator、URI、绝对路径、serial、secret、请求/响应正文和原始异常文本。
- 修复 SAF 目录选择已经持久化、注册并写入 grant 后仍因第二次仓库投影异常而在 UI 谎报 `UNKNOWN_OUTCOME`：成功返回只消费同一事务的 committed workspace/grant bundle；列表刷新仍按正常路径重新读取，权限、backend 和持久 grant 校验未放宽。
- 将对话/历史会话重构为稳定 Agent→Session 侧边栏；工作区只在 Agent 设置中配置，每个 Thread 首次绑定后 workspace 不可变（见 §2.1），默认值变化不改写旧 Thread。
- 新增统一 WorkspaceAccess 门面、多 SAF workspace、selected Authority 目录浏览及独立的完整设备文件授权；UI/模型/诊断均不暴露 URI、绝对路径、serial、token 或 secret。
- 将 ADB 持久授权与实时连接彻底拆分：断联时 selection、intent、configured、GRANTED、Wired trust、workspace、完整设备文件和 Dangerous Mode 保留；工具 schema 继续暴露，调用返回 `AUTHORITY_TEMPORARILY_UNAVAILABLE` 且不派发、不 fallback。
- 修复 Shizuku Manager 外部明确授权后 canonical `configured` 未补齐的问题：只有应用持久 user intent 与 live GRANTED 同时成立时才恢复配置，平台 grant 单独存在不会隐式选择 Authority。
- Agent UI 以 `durablyAuthorized` 展示持久授权，离线时明确提示授权保留并保留撤销入口，不再用实时 `ACTIVE` 误判授权已关闭。
- Wired 文件后端将 API 34 的 `Path.of` 替换为 API 26 可用的 `Paths.get`，保持 minSdk 与 lint 门禁不变。
- 移除 canonical workspace grant 之后的第二份进程内逐次批准，解决模型已获授权却停在隐藏审批的问题；Shell 的高风险确认策略未放宽。
- 修复 Shizuku 首次授权时权限结果、Binder 回调和容器刷新并发触发重复 UserService bind 的竞态；无预热首次 bind 单独通过。
- 修复 `workspace_list` 空 workspace 审计、Shizuku 根 list 的 `null` 路径、SAF tree/document URI 与 mutation handle 重绑问题。
- SAF 只在 provider/grant 可证明时支持新建文本；既有文件非原子覆盖继续返回 `UNSUPPORTED`，不冒充原子替换。
- Agent-facing schema 不再声明未消费的 READ `expectedVersion` 或 MOVE `replace`；Wired write/create/move/delete 收到非空 `expectedVersion` 时在 bridge 前零 dispatch 返回 `CONFLICT`。
- `runtime_tool_exposure` 增加闭合的安全聚合原因，区分无 grant、无 snapshot binding、Authority 未就绪、SAF backend 状态、模型 tools transport 关闭与 factory 故障；不记录 URI、路径、命令、参数、正文或异常消息。

 完整根因、命令与证据见 [2026-09-01 Agent 侧边栏与持久工作区访问 v2.4](docs/evidence/2026-09-01/conversation-workspace-v2-4.md) 和 [工作区工具真实 E2E](docs/evidence/2026-09-01/workspace-tool-real-e2e.md)。

### 2.4 人工测试前收口（2026-09-04，源码见 §1 新推送，DOCS LOCAL, NO DEPLOY）

**Desktop Bridge**：跨平台单测不再写死 `C:\adb.exe`/`C:\trust`，改用宿主绝对路径 fixture + `absoluteFixturePath` helper；生产 `isAbsolute` 校验保留。新增相对路径拒绝、重复/未知参数拒绝、`status/forget` 缺 appInstance 拒绝的负向用例；Windows 盘符语义保留 Windows-only 用例。`:desktop:bridge:test` 本地 PASS。

**导航 IA**：移除用户可见 More Hub；Drawer 为唯一一级入口（对话/智能体/服务商/知识/技能/公告/设置/MCP/关于/请求检查器）。`AppRoutes.MORE`、`moreHubItems`、`phonePrimaryDestinations`、`isMoreChildRoute` 仅兼容保留，无生产使用点。一级页一律 MENU，feature 内部 detail/overlay 经 `childDetailOpen` 提升为 BACK（XOR 由构造保证）；系统 Back 无历史时回 Chat，只有 app root 可退出。`ReleaseGateUiDeviceTest` 重写为新 IA 门禁（7/7 PASS on API 36），`NavigationScopeTest` 同步。

**Shizuku list**：取消 512 总量前置限制，新增与页大小分离的资源保护上限 `MAX_LISTED_ENTRIES=8192`（typed `LIMIT`）；fingerprint 与子目录 entry version 改为浅层（本目录元数据 + 直属可见子项），父 list 永不递归进子孙；cursor 可复用（bounded 1024，淘汰/突变/重启 → `INVALID_CURSOR`）；`fileVersion` 文件 CAS 语义不变。新增 1000/5000 真分页、巨大子目录、超深链、增删改名失效、symlink/FIFO、超长文件名、包大小 envelope 用例。附带修复 `ShizukuDirectoryHandleStore.validateDirectory`：只校验 deviceRoot 以下 segments（`/data/user/0` 等平台 symlink 祖先不再误杀），deviceRoot 为 `/` 时行为与之前一致。

**Picker**：Shizuku picker 服务端目录优先 + opaque continuation（AIDL 新增 35/36 分页事务，旧 18/19 保留兼容）；Wired 透传 companion 既有 cursor；共享 `WorkspaceBrowseRequest/WorkspaceDirectoryPage.continuation`；VM 累积追加 + `loadMore()` + 导航竞态丢弃；UI “加载更多”按钮（tag `workspacePicker.loadMore`）；stale continuation typed `INVALID_CURSOR` → 前端“状态已变化请刷新”。Shizuku/Wired/VM/UI 均有新用例。

**Responses**：默认 `store:false`（boolean 覆盖、非 boolean `INVALID_CONFIG`）；`store:false` 且无显式 include 时自动 `include: ["reasoning.encrypted_content"]`；加密 reasoning 经 `ProviderContinuationItem` 通道在同 run tool 循环回送，不进 UI/预览/诊断/持久化/日志；refusal 为独立 `RefusalDelta`/`RefusalPart`（显示 + 持久化，Test Connection 判成功，TEXT probe 与 malformed 区分）；支持 `reasoning_text` 事件；探测预算夹紧 64/128 tokens（Compatible 同理 64）。`TransferCodec`/`ConversationRepository`/`TransferRepository` 接纳 `refusal` part（无 DB 迁移，TEXT 无 CHECK 约束）。

### 2.5 架构收敛（2026-09-05，本地修改未提交，NO COMMIT, NO PUSH, NO DEPLOY）

13 个 finding 逐项核对：12 CONFIRMED（§4.2 按“元数据版本冒充内容版本”确认）。
Phase 1 五项正确性修复：`ToolOutcome` 统一信封（Denied/Invalid/Failed/Unknown 全部 JSON object，永不升级 INTERNAL）；Responses/Compatible/MCP 三处 `error` 仅非 null object 进入失败；三后端 create-only 改内核原子 exclusive create（附带发现：Windows rename 无 replace 仍静默覆盖，已实测）；版本拆分 `c1:/m1:/d1:` + capability 五态；检索每 KB 每通道独立 ranking + 确定性 tie；`VectorIndexCache` 按 generation 复用回收；`RetrievalCoverage` 进 result/prompt/UI/metadata/manifest；`authorizeReplay` 重放门 + `AuthorizationEvaluator` 六决策四检查点同一向量（Python 接入，MCP 残留默认）。Phase 2/3：`RunManifest` + DB v17 + `RunCoordinator`（prepare/owner/stamp/cancel/release，Chat 已接入）+ 多 Skill `memory_<opaque>` 命名空间 + model.invoke 默认禁用进 manifest + Internal 工作预算四限 + coding/knowledge/memory 基准。strict gate 1030 tasks 通过，JVM 495 tests 0 失败，REUSE 589/595（缺失为既有保护文件），device 新测试仅编译（本机无 emulator），独立复核未做。细节见 [ADR-0007](docs/adr/0007-architecture-convergence.md) 与 [本轮证据](docs/evidence/2026-09-05/architecture-convergence.md)。

## 3. 验证与产物

| 验证 | 结果 |
| --- | --- |
| 本轮 Thread/Provider/UI follow-up（2026-09-02） | `:shared:domain:test --tests runtime.mobileagent.domain.WorkspaceIntentTest` 11 tests PASS。`:data:sqlite:test --tests runtime.mobileagent.data.WorkspaceBindingRepositoryTest` PASS。`:shared:provider-api:test` PASS（含 typed connection 200/401/404/429/timeout/partial）。`:app-android:compileDebugKotlin` 与 `compileDebugAndroidTestKotlin` BUILD SUCCESSFUL。focused `connectedDebugAndroidTest` **45/45 PASS** on `mar_api36_debug` API 36：`RuntimeThreadWorkspaceDeviceTest`、`WorkspacePickerContractTest`、`CanonicalWorkspaceCoordinatorTest`、`WorkspaceUiPresentationTest`、`GlobalConversationUiTest`、`ProvidersTypedUiTest`、`ProvidersViewModelConnectionDeviceTest`、`PrivilegedWorkspaceBindingCipherTest`。全仓 strict gate / REUSE / 全部 androidTest / 物理 Wired USB / 真机挖孔 **未跑**。Insets = `IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED` |
| 本轮 Workspace canonical / UI 定向测试（2026-09-02） | `:shared:domain:test --tests runtime.mobileagent.domain.WorkspaceIntentTest` BUILD SUCCESSFUL（11 tests）。`:shared:provider-api:test` BUILD SUCCESSFUL（含 200/401/404/429/timeout/partial typed connection）。`:app-android:compileDebugKotlin` 与 `compileDebugAndroidTestKotlin` BUILD SUCCESSFUL。focused `connectedDebugAndroidTest` 36/36 PASS on `mar_api36_debug` API 36：`RuntimeThreadWorkspaceDeviceTest`、`WorkspacePickerContractTest`、`CanonicalWorkspaceCoordinatorTest`、`WorkspaceUiPresentationTest`、`GlobalConversationUiTest`、`ProvidersTypedUiTest`、`PrivilegedWorkspaceBindingCipherTest`。全仓 strict gate / REUSE / 全部 androidTest / 物理 Wired USB **未跑** |
| 本轮日志重建 | ZIP SHA-256 `D9A2E9D9221F5302F1FA39E42EC0176E3A165C7EBF1699819EAB3FAF972A14D6`；manifest `3bab147...-dirty` / schema 14。日志证明 Shizuku ready、grant 与 enumerate 成功，但没有 attach/tool-call/approval 失败事件，结论为“未直接复现”，不把推断冒充日志事实 |
| 本轮核心与全仓自动化 | shared domain/serialization/agent-runtime/provider/skills/bridge、SQLite、desktop bridge 聚合测试通过；`:data:sqlite:test` 144 tests PASS；最终 `licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification check :app-android:assembleDebug :app-android:generateDebugSbom --dependency-verification=strict` 1021 tasks `BUILD SUCCESSFUL`，SBOM 171 components |
| 本轮 API 36 仪器回归 | 目标批次 66/66 PASS：Global Conversation/Drawer、Workspace Picker、Provider typed UI、Diagnostics、Thread workspace、committed-result、picker contract、binding cipher、Shizuku store、SAF/Internal/Wired workspace backend；独立复核修复后的 focused 批次 19/19 PASS，最终无 Agent 的 picker 禁用边界 focused 8/8 PASS |
| 本轮 Shizuku 人工 smoke | emulator 上官方 Shizuku 已连接并授权，应用显示 selected/granted/ready/connected；picker 根只显示受控入口，可浏览到 `storage/emulated/0/Download/mar-e2e`，不显示 raw `/data`、`/proc`、`/apex`。该 smoke 不替代真实物理 Wired USB 验收 |
| 本轮许可与供应链 | REUSE 556/556、0 缺失/0 无效；AGPL/license guard、dependency locks、strict verification、CI pin 与 SBOM 门禁均未降低 |
| 本轮 Debug APK | `app-android/build/outputs/apk/debug/app-android-debug.apk`；213,145,763 bytes；SHA-256 `3B93DBBBF77BB284094982A325E47A241C9C969DFE3FAA674216CDD46C3B6402`；Android Debug 签名，仅供人工验收 |
| 本轮独立只读复核 | `APPROVE`；首轮指出 Wired browser、SAF chunk、Wired UTF-8 offset 三项阻断，全部完成最小修复并通过定向复审；复核者未修改文件。物理 Wired USB E2E 仍为设备阻塞，不属于已通过范围 |
| SAF 选择成功边界与真目录重绑定 | `WorkspaceAccessCompletionTest` 1/1 PASS；API 31 真实 persisted SAF 目录重新绑定到现有 Agent 1/1 PASS，返回 `ACTIVE`、durable grant 非空且列表可读回；既有 internal workspace round-trip 1/1 PASS |
| 本轮 Kotlin/AndroidTest/Debug 双 APK | 严格 `compileDebugAndroidTestKotlin`、`assembleDebug`、`assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`；Debug APK 213,705,505 bytes，SHA-256 `0904EF627196FCBE354F452F75AF87E14E49156748C3C3A7945B0F56059B7886`；AndroidTest APK 1,743,431 bytes，SHA-256 `DBBF8481DD6A78E9991DF00422E965BA35A3C22BA6715B5B9E16B4857191019D` |
| 本轮许可 | `licenseGuard licenseGuardReverse` 通过；REUSE 536/536，0 缺失/无效；AGPL 与供应链门禁未降低 |
| v2.4 侧边栏/Agent 离线授权/SAF/Shizuku/诊断/Wired 聚合设备批次 | 95/95 PASS |
| Shizuku 模型工作区真实强制链路 | 1/1 PASS；真实 UID 2000 UserService，工具表与 list/create/read/delete 成功，无第二次 Chat 审批 |
| Authority 断联/重建与 schema/dispatch 正反向 | `ToolingOrchestrationTest` 41/41 PASS；Shizuku/Wired selection、intent、configured、grant 保留；离线工具仍暴露，派发为暂时不可用且零 backend dispatch |
| 当前 Kotlin/AndroidTest/Debug 双 APK | `BUILD SUCCESSFUL`；Debug APK 213,705,503 bytes，AndroidTest APK 1,742,266 bytes |
| 当前全仓 strict gate | 1021 tasks `BUILD SUCCESSFUL`；Debug SBOM 171 components |
| 当前许可与供应链 | license 正反向、Actions pin、28 个 lockfile、strict verification 通过；REUSE 536/536、0 缺失/无效 |
| 无预热 Shizuku 首次模型工具暴露 | 1/1 PASS；测试后只有一个 UserService |
| Shizuku typed workspace + shell | 2/2 PASS |
| 真实 DocumentsUI SAF 模型工具链 | 2/2 PASS；覆盖读、新建、读回、覆盖拒绝、原文不变与删除 |
| API 31 完整仪器批次 | 235 tests 全通过；K06 大负载未显式启用，不能算作完成 |
| v2.3 基线 Kotlin/AndroidTest/Debug 双 APK | 375 tasks `BUILD SUCCESSFUL` |
| v2.3 基线全仓 strict gate | 1084 tasks `BUILD SUCCESSFUL`；Debug/Review SBOM 均 171 components，Review provenance 通过 |
| v2.3 基线提交前许可复验 | `licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification` 通过；REUSE 517/517、0 缺失/无效 |
| CodeGraph | `codegraph sync .` Synced 17 changed files（Added 1, Modified 16）；`.codegraph/` 不进入 Git |

上一轮人工终审使用同一个 non-debuggable Review 包检查普通模式与危险模式；下表 Review 产物属于上一轮证据，不是本轮 v2.4 当前 dirty tree 的新 Review 包：

| 产物 | 大小 | SHA-256 |
| --- | ---: | --- |
| `app-android/build/outputs/apk/review/app-android-review.apk` | 204,289,319 | `E8289EE1DB02ADBF1C3F9C2AD8BF7B97FC07E676140EF5347AA2395C9F2AB477` |
| `app-android/build/outputs/apk/debug/app-android-debug.apk` | 212,754,194 | `ED6571CC4AE98101D6F049EC57FF9EE3EA6BA8F030CDFE2FD41C64A11E96FAD4` |
| `app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk` | 1,720,014 | `104371A4761E0EEA0FFFC9AB0CC14CF8719FD6CEFDC74F26E6B147022D7D69E4` |

这些 APK 使用本地 Android Debug v2 签名，只供验证，不是正式 release。Debug AndroidTest APK 不能安装到 Review target 上；该 build-variant 不匹配产生的 `NoSuchMethodError` 不是产品崩溃。

## 4. 安全与范围边界

- Root、应用内无线 ADB、DPC、Termux、PTY、Accessibility、宿主 PowerShell/宿主 shell 不在当前产品范围。
- 普通模式不暴露 arbitrary shell；用户显式开启 Dangerous Mode 且具备 `shell.execute` capability 后，仍允许真正的 Android shell。Root/Wireless ADB 仍不实现。
- SAF 是独立 workspace backend，不是 elevated Authority；URI、设备路径、ADB serial、配对材料和 secret 不进入模型或诊断。
- Shizuku 必须验证 shell UID 2000、caller/session/protocol；UID 0、未知 UID 或握手不一致均 fail-closed。
- 外部副作用无法确认时返回 `UNKNOWN_OUTCOME`，不得自动重放。
- 诊断默认关闭，事件/字段闭合，引用使用会话 HMAC；当前/上一段/崩溃/单事件/ZIP 上限为 256/256/32/4/640 KiB。
- 本轮 Git 授权已用于把源码与测试普通快进推送到 `origin/main`；按用户要求，`HANDOFF.md` 与 `/docs` 只做本地提交，不推送。不授权 Cloudflare/其他生产部署、正式签名、AAB/商店发布、付费调用、secret/Access 变更、强推或历史改写。

## 5. 未决事项

| 项目 | 状态与下一步 |
| --- | --- |
| P0 统一 Workspace canonical flow | **源码已推送 `2d35933`，文档仅本地**。剩余：① Agent 草稿 attach 先入图书馆，与 `saveWithPrompt` 不是同一 SQLite 事务，靠 `rollbackNewAgent` 补偿；② Global Picker 走 `WorkspacePickerPort`/`planFor` 而非 `CanonicalWorkspaceCoordinator` 类（intent 语义相同）；③ `WorkspaceAccessPort.attach*` 仍给内部/遗留测试用，Agent 编辑页不再调用；④ Agent 编辑页仍保留 grant preset / default dropdown 作为已有 workspace 上的高级授权，不是第二套 attach；⑤ 本轮未做独立安全复核、全仓 strict gate、REUSE、物理 Wired USB |
| Thread workspace immutable / Provider 连接集成 | **源码已推送 `17a695e`，文档仅本地**。已绑定 Thread 切 workspace = 新 Thread。Insets = `IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED`。Workspace UI presentation 卸载丢失仍可接受，fallback 已接上。真机 punch-hole 与物理 Wired USB 仍待验证 |
| 当前 dirty tree | 源码已推送。HANDOFF/`docs` 仅本地提交，不推送。不要把 `.tmp-diag3/`、`.workbuddy/` 加入提交 |
| UI 设计语言 | 用户已明确当前重构不符合要求；本轮按要求停止 UI 改造，只修功能。后续作为独立任务重新梳理交互与视觉，不得把本轮功能修复视为 UI 验收通过 |
| F-001 工具能力开关历史崩溃 | `candidate_intermittent`，不能因暂未复现关闭。再次出现时记录 APK SHA、时间和步骤，导出应用内诊断 ZIP；原生崩溃或系统强杀另取完整 Logcat |
| 物理 USB Companion | `E2E_BLOCKED`：需要真实 Windows USB 设备、配对、物理断连和恢复验证；不得用模拟器替代 |
| OEM/非模拟器差异 | `E2E_BLOCKED`：需要物理设备上的 DocumentsProvider、Shizuku Binder death/rebind 与权限撤销验证 |
| K06 大负载 | 未完成：仍需 300—500 文件、约 300—500 MB，覆盖 ENOSPC、温控/耗电、进程死亡恢复和 Android 15/16 长时配额 |
| 正式 Android release | 未授权：正式包名/品牌、release keystore、AAB、商店发布与后检后置 |
| 推送后 CI | `origin/main` 已更新到 `a361a39`；GitHub Actions 结果仍需远端完成（本地等价门禁见 §1，不冒充远端已绿）。普通 push 下 `Signed release gate (manual only)` skipped 是预期行为，不触发正式签名 release |
| 收敛轮独立只读复核 | **未做**：本轮改动触及授权（evaluator/重放门/approve）、持久化（ToolOutcome/v17/manifest）、执行边界（commit 原语/检索融合），按仓库规则必须独立复核。复核者只读，不改文件 |
| 收敛轮新增 device 测试执行 | **未跑**：`RunToolsReplayDeviceTest`、Shizuku create-only 用例仅编译通过（本机无 emulator）。需 API 36（或 API 31）设备执行 |
| RunCoordinator 完整抽离 | **部分**：prepare/owner/stamp/cancel/release + manifest 已接入 Chat；流式/tool-loop 收集器与 `cancel()` 的 durable 写入仍在 VM（后者为故意保留：提前写 CANCELLED 会覆盖 dispatch 中的 UNKNOWN 判定） |
| model.invoke 编辑器控件 | **未做**：manifest + fail-closed + 费用告知先行；Agent 编辑器可见配额控件待后续任务 |
| 工作预算（非 Internal） | **未做**：SAF/Shizuku/Wired/knowledge/Python/ZIP/picker 同原则待后续；Internal 先行并有测试 |
| 收敛轮大规模实测 | **未做**：1k/10k/50k chunks 的索引 load/内存/重建次数（`vectorIndexStats` 计数器已就绪）；真实 Provider/large KB/打孔/Wired USB 仍阻塞（同前） |
| MCP/远端 Skill 重放 | **残留**：`authorizeReplay` 默认放行（新调用仍受派发时检查）；override 待后续 |

## 6. 接手顺序

1. 运行 `git rev-parse --show-toplevel`、`git status --short --branch`，核对分支、HEAD、远端和 dirty 状态。远端源码为 `b07b4b3`；本地随后会领先纯文档改动。不要把 `.tmp-diag3/`、`.workbuddy/`、`docs.zip` 加入提交，也不要把 `HANDOFF.md` 或 `/docs` 推送到远端。未获新授权时不 commit/push。
2. 阅读 [权限工具 v2 规范](docs/mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)、[验收矩阵](docs/ACCEPTANCE.md) 与 [诊断规范](docs/DIAGNOSTICS.md)。
3. 若处理人工问题，先核对 Review APK SHA 和诊断 ZIP manifest，再按 `runtime_tool_exposure` 的闭合原因定位；不要先扩大权限或重建授权模型。
4. 修改源码后先用 CodeGraph，补正反向测试，执行适用的 strict Gradle、REUSE、设备验证和独立只读复核。
5. 未获得新授权时，不 commit/push、部署公告系统、正式签名、发布或调用付费服务。

## 7. 历史证据索引

- 本轮架构收敛：[2026-09-05 架构收敛与隐藏风险修复](docs/evidence/2026-09-05/architecture-convergence.md)（ADR-0007；strict gate 1030 tasks 通过；JVM 495 tests 0 失败；device 测试待跑；独立复核待做）

- 当前 Thread/Provider/UI follow-up：[2026-09-02 Thread workspace 不可变与 Provider 连接集成](docs/evidence/2026-09-02/thread-provider-ui-followup.md)
- 当前 v2.4 交互与持久授权闭环：[2026-09-01 Agent 侧边栏与持久工作区访问](docs/evidence/2026-09-01/conversation-workspace-v2-4.md)
- 当前权限/工作区闭环：[2026-09-01 工作区工具真实 E2E](docs/evidence/2026-09-01/workspace-tool-real-e2e.md)
- v2 历史收敛快照：[2026-08-31 权限、工具与诊断](docs/evidence/2026-08-31/authority-tooling-v2-final.md)
- 人工反馈包：[Round 4](docs/evidence/2026-08-30/manual-review-round-4-ui-workspace.md)、[Round 3](docs/evidence/2026-08-30/manual-review-round-3-capabilities.md)、[Round 2](docs/evidence/2026-08-30/manual-review-round-2-fixes.md)
- 公告后台、诊断与部署：[2026-08-30 证据](docs/evidence/2026-08-30/admin-cn-diagnostics-debug-deploy.md)；本轮未改生产公告系统
- 1.0 本地 release gate：[release-gate-1.0](docs/evidence/2026-08-29/release-gate-1.0.md)
- 知识、Python、Provider、协议、UI 与公告的早期分项证据：[2026-08-29 目录](docs/evidence/2026-08-29/)

后续维护本文件时更新当前事实、验证、未决事项和接手动作；完成过程写入对应 evidence 文件或 Git 提交，不再追加大段时间线。
