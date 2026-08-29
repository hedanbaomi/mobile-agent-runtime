<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# mobileAgentRuntime 仓库审查问题报告

> 审查日期：2026-08-29（Asia/Taipei）
> 基线：`main@13edc5759b1f2fa393f29a095c0690dd7184c7c0`
> 结论：`NEEDS_AMEND`
> 报告类型：白盒静态代码审查，`flavor = null`
> 来源对话：`chatgpt-conversation://6a924072-21cc-83ea-bde0-d34c02b78f9d`

## 1. 执行摘要

本轮将用户在截图中提出的五项问题与随后完成的仓库级静态审查合并为一份可执行问题清单，共收录 20 项发现：P0 六项、P1 八项、P2 六项。安全内核整体成熟，Agent Runtime、隔离 Python、Keystore、Knowledge 的收费与未知结果边界不建议重写；主要问题集中在 App Shell、模型配置抽象、知识库批量导入、可观测性、UI 状态与发布门禁。

最优先的阻断是：工具能力开关曾在统计问题期间稳定出现两次进程崩溃、但随后已无法稳定复现且缺少可追溯 APK 与 Logcat；API Embedding 未知结果重试卡片存在确定的 ID 比较错误；导入过程的页面状态与维护操作互相冲突；存在无实际效果的“仅使用文本”按钮；构建 revision 被固定为 `uncommitted`；Chat 流式更新和预算单位存在静态可见的放大与语义错位。

本报告只整理和核实问题，不实施修复，不改变现有需求、架构或发布授权。正式修复应先完成 0.1.1 稳定性闭环，再做 0.2 配置模型迁移，随后建设 0.3 批量导入，最后进入 1.0 release 门禁。

## 2. 范围、证据边界与状态定义

### 2.1 范围

| 项目 | 本次范围 |
| --- | --- |
| 仓库 | `E:\mobileAgentRuntime` |
| Git 基线 | `main@13edc5759b1f2fa393f29a095c0690dd7184c7c0` |
| 覆盖面 | Android App Shell、Provider/Model、Agent、Chat、Knowledge、后台导入、密钥、CI、文档 |
| 输入 | 用户报告的五项问题、两张截图、引用对话中的仓库静态审查、当前工作树源码 |
| 未执行 | 未安装或运行 APK；未获取崩溃 Logcat；未运行性能基准、Compose UI 测试、迁移测试或 500 文件实机导入 |
| 禁止事项 | 不调用真实 Provider/Vision/Embedding，不执行公告生产操作，不发布 release，不修改用户数据 |

### 2.2 状态定义

| 状态 | 含义 |
| --- | --- |
| `validated_static` | 当前基线源码可直接证明代码结构或逻辑问题；不等同于设备复现 |
| `candidate_intermittent` | 用户已多次观察到现象，但当前无法稳定复现，且缺少 APK 绑定、Logcat 或确定触发步骤，尚未定位根因 |
| `design_gap` | 当前实现与目标负载或产品语义不匹配；需要设计和迁移决策 |
| `release_gap` | 不阻塞本地 debug，但阻塞正式 release 证据链 |

P0/P1/P2 是本项目的修复优先级，不是 CVSS 分数：P0 应在下一个 APK 前关闭；P1 需要一次兼容迁移的架构整理；P2 在批量导入或正式 release 前关闭。

## 3. Evidence

### E-001 用户观察与截图

- `source_type`: manual / screenshot
- `source_ref`: 引用对话中的首条用户消息与两张截图
- `content_hash`: n/a，截图未写入仓库
- `repro_command`: n/a；工具能力开关导致进程崩溃在统计问题期间稳定出现两次，随后已无法稳定复现。需要绑定产生问题的 APK revision，围绕 Provider 编辑器反复切换能力并采集完整 Logcat
- `raw_excerpt`: 底栏文字折叠；系统文件选择器难以处理超过 100 个文件；工具能力开关曾两次触发进程崩溃；视觉/Embedding 角色语义混乱；缺少全局根提示词入口

### E-002 仓库身份与审查基线

- `source_type`: command
- `source_ref`: `git rev-parse --show-toplevel`、`git rev-parse HEAD`、`git status --short --branch`
- `content_hash`: `13edc5759b1f2fa393f29a095c0690dd7184c7c0`
- `repro_command`: `git -C E:\mobileAgentRuntime rev-parse HEAD`
- `raw_excerpt`: 仓库为 `E:/mobileAgentRuntime`，分支 `main`，审查开始时与 `origin/main` 同步且工作区干净

### E-003 App Shell、Provider、Agent 与密钥源码

- `source_type`: file
- `source_ref`: `app-android/src/main/kotlin/runtime/mobileagent/ui/MainScreens.kt:52`、`app-android/src/main/kotlin/runtime/mobileagent/ui/AppNavigation.kt:25`、`feature/providers/src/main/kotlin/runtime/mobileagent/feature/providers/ProvidersUi.kt:300`、`app-android/src/main/kotlin/runtime/mobileagent/ProvidersViewModel.kt:60`、`app-android/src/main/kotlin/runtime/mobileagent/AgentsViewModel.kt:39`、`shared/domain/src/main/kotlin/runtime/mobileagent/domain/Profiles.kt:8`、`platform/android/security/src/main/kotlin/runtime/mobileagent/security/AndroidSecretStore.kt:17`
- `content_hash`: n/a；由 Git 基线固定
- `repro_command`: `codegraph explore "MainApp Provider ModelRole AndroidSecretStore"`
- `raw_excerpt`: 顶层创建全部 ViewModel；导航使用 `remember` 字符串；手机底栏固定七项；Provider 表单自由输入 API 格式和角色；模型仍为单值 `ModelRole`；SecretStore 只有读取和写入接口

### E-004 Knowledge UI、导入和后台任务源码

- `source_type`: file
- `source_ref`: `feature/knowledge/src/main/kotlin/runtime/mobileagent/feature/knowledge/KnowledgeUi.kt:139`、`app-android/src/main/kotlin/runtime/mobileagent/KnowledgeViewModel.kt:52`、`platform/android/background/src/main/kotlin/runtime/mobileagent/background/ImportWorkScheduler.kt:54`、`platform/android/background/src/main/kotlin/runtime/mobileagent/background/ImportWorker.kt:23`
- `content_hash`: n/a；由 Git 基线固定
- `repro_command`: `codegraph explore "KnowledgeScreen importUris textOnly selectedQueryAttempts ImportWorker"`
- `raw_excerpt`: 仅使用 `OpenMultipleDocuments`；每个 URI 独立复制并建立任务；页面每两秒全量刷新活动任务；未知查询按 `spaceId == selectedBaseId` 过滤；普通空状态不识别活动导入；“仅使用文本”只改状态文字

### E-005 Chat Runtime 与请求检查器源码

- `source_type`: file
- `source_ref`: `app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt:115`、`shared/agent-runtime/src/main/kotlin/runtime/mobileagent/agent/AgentRuntime.kt:180`、`feature/chat/src/main/kotlin/runtime/mobileagent/feature/chat/ChatUi.kt:581`
- `content_hash`: n/a；由 Git 基线固定
- `repro_command`: `codegraph explore "ChatViewModel TextDelta maxInputTokens RequestInspectorDialog"`
- `raw_excerpt`: 每个 delta 重新拼接和脱敏完整回答、复制消息列表并保存 RunRecord；输入预算以 UTF-8 字节和固定图片单位估算，却仍从 `maxInputTokens` 读取；请求检查器完整展示消息和提示词内容

### E-006 ZIP 类型识别与归档限制

- `source_type`: file
- `source_ref`: `shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/MediaKind.kt:15`、`shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/OfficeParser.kt:9`、`shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/ZipSafety.kt:9`
- `content_hash`: n/a；由 Git 基线固定
- `repro_command`: `codegraph explore "MediaKind OFFICE_ARCHIVE OfficeParser ZipSafety"`
- `raw_excerpt`: 所有 ZIP 签名统一识别为 `OFFICE_ARCHIVE`；仅按 DOCX/EPUB 解析；现有限制为 256 entries、单项 16 MiB、总解压 48 MiB，并在内存中检查

### E-007 构建、CI 与产品文案

- `source_type`: file
- `source_ref`: `app-android/build.gradle.kts:20`、`.github/workflows/ci.yml:10`、`README.md:4`、`app-android/src/main/kotlin/runtime/mobileagent/SkillsViewModel.kt:25`
- `content_hash`: n/a；由 Git 基线固定
- `repro_command`: `rg -n "uncommitted|placeholder|Python isolation is not in this build" app-android .github README.md`
- `raw_excerpt`: `GIT_REVISION` 固定为 `uncommitted`；CI 未运行 emulator/androidTest，SBOM 步骤写入占位文本；README 与 Skills 页面仍宣称隔离 Python 未完成或不在当前构建

## 4. Findings

### 4.1 汇总

| ID | 优先级 | 状态 | 发现 | 主要位置 | Evidence |
| --- | --- | --- | --- | --- | --- |
| F-001 | P0 | `candidate_intermittent` | 工具能力开关曾两次导致进程崩溃，但目前无法稳定复现，静态路径也无法解释进程退出 | `ProvidersUi.kt:315`、`MainScreens.kt:228`、`ProvidersViewModel.kt:60` | E-001、E-003 |
| F-002 | P0 | `validated_static` | API Embedding 未知查询重试卡片比较了向量空间 ID 与知识库 ID | `KnowledgeUi.kt:401`、`KnowledgeViewModel.kt:119` | E-004 |
| F-003 | P0 | `validated_static` | 导入中仍显示普通空状态，维护操作与活动任务缺少统一冲突状态 | `KnowledgeUi.kt:399`、`KnowledgeViewModel.kt:279` | E-001、E-004 |
| F-004 | P0 | `validated_static` | “仅使用文本”按钮没有改变任务或创建降级版本 | `KnowledgeViewModel.kt:277`、`KnowledgeUi.kt:488` | E-004 |
| F-005 | P0 | `validated_static` | APK 不能绑定真实源码 revision，阻断崩溃追踪 | `app-android/build.gradle.kts:27` | E-002、E-007 |
| F-006 | P0 | `validated_static` | Chat delta 更新存在重复全文处理/频繁写库；token 名称与字节预算语义不一致 | `ChatViewModel.kt:187`、`ChatViewModel.kt:242`、`ChatViewModel.kt:288` | E-005 |
| F-007 | P1 | `design_gap` | 单值模型角色与实际运行时选模路径错位 | `Profiles.kt:12`、`AgentsUi.kt:198`、`KnowledgeViewModel.kt:364` | E-003、E-004 |
| F-008 | P1 | `validated_static` | Provider 编辑器包含看似可配但未实际生效或被静默改写的字段 | `ProvidersUi.kt:307`、`MainScreens.kt:228`、`ProvidersViewModel.kt:82` | E-003 |
| F-009 | P1 | `validated_static` | 能力探测只验证模型 metadata；tools/image/stream 仍来自手工 capability | `OpenAiCompatibleAdapter.kt:70`、`OpenAiCompatibleAdapter.kt:117` | E-003 |
| F-010 | P1 | `validated_static` | App Shell 未使用 NavHost/SavedStateHandle，且“编辑器打开”被当作“草稿已修改” | `MainScreens.kt:52`、`MainScreens.kt:151`、`AgentsViewModel.kt:39` | E-003 |
| F-011 | P1 | `validated_static` | 手机底栏硬编码七个一级入口并强制单行省略 | `AppNavigation.kt:25`、`MobileAgentTheme.kt:255` | E-001、E-003 |
| F-012 | P1 | `design_gap` | 缺少可编辑但不越权的 Global Root Prompt 层 | `ChatViewModel.kt:181` | E-001、E-005 |
| F-013 | P1 | `validated_static` | Request Inspector 的“敏感信息已遮盖”文案覆盖范围表述过宽 | `ChatUi.kt:581` | E-005 |
| F-014 | P1 | `validated_static` | Provider 删除文案承诺移除凭据，但 SecretStore 没有删除/退休/垃圾回收闭环 | `ProvidersUi.kt:162`、`AndroidSecretStore.kt:21` | E-003 |
| F-015 | P2 | `validated_static` | 普通知识库 ZIP 被当作 DOCX/EPUB，无法承载目标数据集 | `MediaKind.kt:22`、`OfficeParser.kt:18`、`ZipSafety.kt:10` | E-006 |
| F-016 | P2 | `validated_static` | 缺少文件夹导入，只提供系统多文件选择器 | `KnowledgeUi.kt:151` | E-004 |
| F-017 | P2 | `design_gap` | 导入以多个独立文件/Worker 组织，没有 ImportBatch/ImportItem 与真实批次进度 | `KnowledgeViewModel.kt:291`、`ImportWorkScheduler.kt:54`、`ImportWorker.kt:38` | E-004 |
| F-018 | P2 | `validated_static` | 用户确认后的 Vision/API Embedding 仍由页面 ViewModel 协程直接推进 | `KnowledgeViewModel.kt:212`、`KnowledgeViewModel.kt:266` | E-004 |
| F-019 | P2 | `release_gap` | CI 缺少 UI/设备回归，SBOM 为占位步骤，Actions 未固定完整 SHA | `.github/workflows/ci.yml:14`、`.github/workflows/ci.yml:34` | E-007 |
| F-020 | P2 | `validated_static` | 产品文案、国际化资源和项目文档已与实际能力漂移 | `SkillsViewModel.kt:28`、`README.md:8` | E-007 |

### 4.2 P0：下一个 APK 前关闭

#### F-001 工具能力开关崩溃

- `impact`: Provider/Model 编辑流程可导致应用进程退出，阻断正常配置。
- `confidence`: medium；用户在统计问题期间已稳定观察到两次崩溃，但随后无法再稳定复现，且缺少可绑定源码的 APK revision 与 Logcat。
- `finding`: Checkbox 当前只调用 `draft.copy(tools = it)`；保存时才生成 `"tools"` capability，且 `saveDraft()` 捕获普通 `Exception`。因此不能从当前静态路径断言根因。
- `remediation`: 先自动注入真实 Git SHA、dirty 状态、schema version、构建时间；增加脱敏诊断导出；在产生问题的 APK 上采集完整 Logcat。
- `acceptance`: 新建/编辑模型的 image×tools 四种组合、连续反复开关、保存/关闭/重开、横竖屏、Activity 重建、旧数据库 fixture 迁移均不得崩溃；增加足够轮次的压力回归以覆盖间歇性触发；所有校验失败只显示行内错误。

#### F-002 API Embedding 未知查询重试卡片不可见

- `impact`: 已记录的 `UNKNOWN_OUTCOME` 查询无法从 UI 取得一次性重试授权，用户可能被卡死或绕过收费保护。
- `confidence`: high。
- `finding`: `apiQueryAttempts` 已由当前知识库的 `pendingApiQueries()` 生成，但 UI 再次执行 `it.spaceId == state.selectedBaseId`；两者是不同实体 ID。
- `remediation`: 直接使用 `state.apiQueryAttempts`，或显式携带并比较 `knowledgeBaseId`。
- `acceptance`: 构造当前 KB 的 unknown query，断言“允许再次提交此查询”可见；其他 KB 的记录不可见；授权后仍不自动发送请求。

#### F-003 导入状态与维护操作冲突

- `impact`: 导入时同时出现“正在复制”和“没有文档”，用户无法判断状态；重建/删除与活动任务之间的冲突处理不清晰。
- `confidence`: high，UI 状态错误已由源码确认；旧任务是否能回写仍需专项测试，不能仅由 UI 推定。
- `finding`: 文件循环完成后才 `reload()`；普通空状态只检查 `documents.isEmpty()`；文档与任务用普通 `forEach` 展示；重建按钮只依赖 `rebuildEnabled`。
- `remediation`: 引入活动批次状态；活动批次期间隐藏普通空状态；删除/重建必须先取消或明确等待；提交前校验 `knowledgeBaseId + generation`。
- `acceptance`: 导入中展示批次总数、已复制、处理中、等待、失败；删除/重建冲突有确定流程；被删除或换代的旧任务不得提交结果。

#### F-004 “仅使用文本”是假操作

- `impact`: 用户点击后任务仍等待，界面暗示的行为与实际状态不一致。
- `confidence`: high。
- `remediation`: 实现可审计的 `READY_WITH_VISUAL_GAPS` 文本降级版本，或移除按钮并说明只可在对话时选择忽略视觉证据。
- `acceptance`: 操作后必须有持久化状态变化和可恢复结果；不能把含未处理图片的文档标为完整 `READY`。

#### F-005 构建 provenance 不可用

- `impact`: 截图、诊断和崩溃无法可靠映射到源码，F-001 无法闭环。
- `confidence`: high。
- `remediation`: 构建时写入 commit SHA、dirty 标记、schema version、构建 UTC 时间；About 与诊断导出显示这些字段。
- `acceptance`: clean 与 dirty 构建均能唯一对应源码；正式产物、SBOM、provenance 和源代码归档引用同一 revision。

#### F-006 Chat 流式性能与预算单位

- `impact`: 长回答时可能放大字符串复制、脱敏、Compose 重组和 SQLite 写入；中文上下文可能因 UTF-8 字节被当作 token 而过度拒绝。
- `confidence`: 静态结构 high；具体卡顿/ANR 阈值尚未基准验证。
- `remediation`: 使用分段 buffer 和增量脱敏；30–60ms 合并 UI delta；只在状态迁移、usage 与固定 checkpoint 写 RunRecord；将字节预算明确命名为 `budgetUnits`，或接入模型 tokenizer。
- `acceptance`: 长流式基准记录帧耗时、分配、数据库写入数；中文、英文和工具 schema 的预算错误信息使用真实单位；未启用工具不应计入工具 schema 预算。

### 4.3 P1：兼容迁移的架构整理

#### F-007 至 F-009：模型和服务商能力抽象

当前 `ModelRole` 把 Chat、Vision、Embedding、Reranker 放在同一单值枚举中，但图片是 Chat 的输入模态，工具调用是 Chat 的功能能力，Embedding/Rerank 是独立操作。建议迁移为：

```text
ModelEndpoint
  operations: CHAT | EMBEDDING | RERANK
  inputModalities: TEXT | IMAGE
  features: STREAMING | TOOL_CALLING | STRUCTURED_OUTPUT
  verification: UNKNOWN | USER_DECLARED | PROBED
```

配置关系调整为：Agent 只要求 `primaryChatModelId`，视觉覆盖为高级可选；Knowledge Base 独立持有 Embedding backend/space，必要时持有视觉覆盖；Reranker 只有真正接线后才展示。工具调用不能替代 Embedding：FTS 可无 Embedding，语义/混合检索仍必须使用本地或 API Embedding。

Provider 与 Model 编辑器应拆分；在只支持 OpenAI Compatible 时不显示自由 API 格式输入；无效角色不得静默回退 Chat。能力探测分别记录“用户声明”和“真实行为验证”，工具探测使用无副作用虚拟函数，图片探测使用内置小图，并固定 Provider revision、Model ID、时间和响应摘要。

迁移验收必须证明原 Chat、Vision、Embedding、Agent 和不可变会话快照均可解释，且不要求用户重新输入全部配置。

#### F-010 至 F-011：导航和编辑状态

使用真正的 `NavHost`、route 级 ViewModel 与 `SavedStateHandle`；手机一级导航改为“对话、智能体、知识、技能、更多”，服务商、公告、MCP、设置和关于进入“更多”。平板可保留完整 Navigation Rail。

编辑状态拆为 `selectedId`、`editorOpen`、`editorDirty`。只有草稿与初始快照确实不同才阻止离开；选择 Agent 不应自动等价于“存在未保存内容”。验收覆盖 320/360/411dp、字体缩放 1.0/1.3/1.5、中英文、横屏、TalkBack 和不小于 48dp 的触控目标。

#### F-012 全局根提示词

提示词层级建议固定为：

```text
IMMUTABLE_RUNTIME_PROTOCOL
GLOBAL_USER_ROOT_PROMPT
AGENT_PROMPT_REVISION
ACTIVE_SKILL_INSTRUCTIONS
RETRIEVED_KNOWLEDGE
CONVERSATION_HISTORY
USER_INPUT
```

Runtime Protocol 继续不可编辑，只保存工具/引用协议和权限边界；Global Root Prompt 默认跟随内置值，在高级设置中可解锁，以 nullable override、revision、hash 和更新时间保存，并提供恢复默认。用户提示词不得改变 Tool Broker、网络/文件权限或 Python 隔离。

#### F-013 至 F-014：检查器与密钥生命周期

Request Inspector 文案应改为“API Key 和敏感请求头已遮盖；消息正文、提示词和知识内容仍会完整显示”，并迁移到全屏开发者页面。复制功能只能复制脱敏请求，预览默认不长期保存。

Secret 生命周期至少区分 `ACTIVE / RETIRED / ORPHANED / DELETED`，增加引用扫描和无引用密文垃圾回收。删除 Provider 前展示模型/快照引用数；在实现前先修正“凭据由宿主操作移除”的错误承诺。取消编辑或保存后应尽快清空 Compose 中的明文 API Key 草稿。

### 4.4 P2：批量导入和 release 前建设

#### F-015 至 F-018：真正的知识库批量导入

新增独立 `KNOWLEDGE_ARCHIVE`，不能复用 DOCX/EPUB 的 `OFFICE_ARCHIVE`。导入入口同时提供“添加文件、导入文件夹、导入 ZIP”；文件夹使用 `ACTION_OPEN_DOCUMENT_TREE`，保留相对路径并复制到应用管理存储。

ZIP 流程必须是：中央目录安全预检 → 导入摘要 → staging → 流式解压 → 逐项校验 → 解析/建索引 → 原子提交 → 清理。拒绝路径逃逸、Windows drive path、重复规范化路径、Unicode/大小写冲突、链接、异常压缩比、超限 entry/总量、加密包和嵌套包；处理超时、空间不足、进程终止与设备重启。

数据模型升级为 `ImportBatch + ImportItem`，一个协调 Worker 管理有界并行、暂停、恢复、取消和真实通知进度。每个批次绑定 KB generation；旧 generation 永远不能提交。用户同意后的付费 Vision/API Embedding 使用持久化一次性 consent ticket 和独立前台 Work；dispatch 后失联进入 `UNKNOWN_OUTCOME`，绝不自动重放。

#### F-019 至 F-020：release 门禁和文档一致性

CI 增加 Provider/Navigation/Import Compose UI、Activity recreation/process death、ZIP 安全反例、500 文件批次、前台任务恢复和 Chat 长流式基准；关键设备矩阵覆盖 API 31/34/35/36。正式 release 使用签名 AAB 与 ABI split，生成真实 SBOM/provenance，Actions 固定完整 commit SHA，并启用依赖锁定和依赖校验。

所有运行时错误返回语义化 error code 与参数，UI 统一从中英文资源表渲染。README、Skills 状态、HANDOFF 与实际实现必须在 release 时自动校验，不能继续把过期文案当作产品事实。

## 5. Paths

### P-001 Provider 工具能力配置路径

- `path_type`: callflow
- `start`: Provider 编辑器 Checkbox
- `goal`: 保存 `TOOL_CALLING` 能力或获得可复现崩溃证据
- `steps`:
  1. `ProvidersUi.CheckRow` 执行 `draft.copy(tools = it)` — E-003 — F-001
  2. `MainScreens` 把布尔值转换为 `"tools"` capability — E-003 — F-001/F-008
  3. `ProvidersViewModel.saveDraft()` 校验并持久化 Provider/Model — E-003 — F-001
  4. 当前路径没有解释已出现两次的间歇性进程崩溃，必须由 APK SHA + Logcat 继续定位 — E-001/E-007 — F-001/F-005
- `residual_risks`: 间歇性 Error、原生崩溃、旧 schema、Compose 重组或实际 APK 与基线不一致仍未排除；“当前不再复现”不能单独证明问题已关闭

### P-002 Knowledge 批量导入路径

- `path_type`: callflow
- `start`: `OpenMultipleDocuments`
- `goal`: 可恢复、可取消、原子提交的 300–500 文件数据集导入
- `steps`:
  1. UI 收集多个 URI 并一次性确认 — E-004 — F-016
  2. `importUris()` 逐项整文件读入内存并创建独立 ImportJob — E-004/E-006 — F-003/F-015/F-017
  3. 每个非 READY job 建立一个唯一 WorkRequest — E-004 — F-017
  4. ViewModel 每两秒全量加载 KB、文档、任务和 N+1 数量 — E-004 — F-003/F-017
  5. 付费确认回到 ViewModel 协程直接调用 Repository — E-004 — F-018
- `residual_risks`: 大文件内存、WorkManager 请求数量、进程终止、磁盘不足和真实付费请求恢复尚未设备验证

### P-003 模型能力与检索路径

- `path_type`: callflow
- `start`: 用户配置单值 `ModelRole`
- `goal`: 可解释的 Chat/Vision/Embedding/Rerank 选模
- `steps`:
  1. Provider/Agent UI 保存 Chat、Vision、Embedding、Reranker 角色 — E-003 — F-007/F-008
  2. Chat 实际取主 Chat 模型，并从 capability 判断 image/tools — E-005 — F-007
  3. Knowledge Vision 使用全局 `visionBinding()`，Embedding 使用 KB space — E-004 — F-007
  4. metadata probe 把手工 capability 作为 tools/image/stream 结果返回 — E-003 — F-009
- `residual_risks`: 迁移前旧 Agent、快照和导入包的兼容规则必须先冻结并做正反向测试

## 6. 建议实施顺序与退出条件

| 阶段 | 工作范围 | 退出条件 |
| --- | --- | --- |
| 0.1.1 稳定性补丁 | F-001–F-006、F-014 文案、F-020 过期文案 | 崩溃有根因和回归；真实构建 SHA；unknown-query UI 可见；导入状态一致；无假按钮；Chat 基准有界 |
| 0.2 配置模型重构 | F-007–F-014 | 无损迁移；主 Chat 自动兼任 IMAGE；Embedding 归 KB；NavHost/dirty tracking；全局根提示词不越权；Secret 生命周期闭环 |
| 0.3 批量导入 | F-015–F-018 | 文件夹与安全 ZIP；Batch/Item；杀进程/重启/断网/ENOSPC 恢复；500 文件约 500MB 实机证据；付费 consent ticket |
| 1.0 release 门禁 | F-019–F-020 | UI/设备 CI、正式签名 AAB、ABI split、SBOM/provenance、依赖校验、文档同步；release 仍需用户另行授权 |

任何阶段的静态检查、单元测试、模拟器、正式签名、部署与 release 都必须分别记录，不能互相替代。用户已明确后续公告系统部署自行操作，本报告不重新授权公告生产操作。

## 7. 附录：复核清单

- [ ] F-001 已取得对应 APK SHA、dirty 状态、schema version 和完整 Logcat
- [ ] F-002–F-006 有最小回归测试与设备/性能证据
- [ ] 0.2 迁移证明旧 Chat/Vision/Embedding/Agent/快照均不丢失
- [ ] ZIP 和文件夹导入经过路径、压缩比、重复名、Unicode、加密、嵌套、超时、ENOSPC 与重启反例
- [ ] 付费 Vision/API Embedding 的 consent ticket 与 `UNKNOWN_OUTCOME` 不自动重放
- [ ] Provider、Navigation、Import 的 Compose UI 回归进入 CI
- [ ] release 产物、源码、SBOM 与 provenance 指向同一真实 revision
- [ ] README、Skills 状态和中英文资源与当前实现一致

本清单是后续修复工作的验收输入，不代表问题已关闭，也不构成 commit、push、公告部署或正式 release 授权。
