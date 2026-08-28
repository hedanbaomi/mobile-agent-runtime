<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 软件页面 UI 设计规范与视觉设计系统

本文件为 mobileAgentRuntime Android 客户端软件的完整 UI 设计规范。涵盖设计原则、色彩与排版系统、导航架构、七类核心软件页面的逐屏高保真布局与状态规范、安全交互流程以及响应式无障碍标准。

页面设计遵循 Material Design 3 规范，采用现代、严谨、工程化视觉风格。全套界面禁用 Emoji 表情符号，统一使用纯文本标签、标准矢量图标及状态色阶表达界面意图。

## 1. 设计系统基础

### 1.1 视觉原则

1. **清晰严谨**：突出数据结构与真实运行时状态，不使用装饰性符号或含糊不清的图标；所有状态具备明确文字说明。
2. **安全透明**：所有离开设备的数据、外发目标模型、API 密钥引用、权限授予和计费影响均在界面上醒目展示，严禁隐式授权。
3. **确定性反馈**：流式传输、工具调用、多模态等待、任务中断等关键生命周期状态均有明确的视觉分界与可执行操作。
4. **无障碍友好**：所有可交互元素最小触控区域达到 48x48 dp，文字与背景对比度符合 WCAG AA 级标准（>= 4.5:1）。

### 1.2 色彩系统 (Color Palette)

色彩分为浅色模式 (Light Theme) 与深色模式 (Dark Theme)，具体数值定义于 [ui-tokens.json](design/ui-tokens.json)。

- **主色 (Primary)**:
  - 浅色: `#1A56DB` (深蓝) | 容器: `#E1EFFE` | 文本: `#FFFFFF`
  - 深色: `#76A9FA` (亮蓝) | 容器: `#233876` | 文本: `#1E429F`
- **中性底色 (Surface & Background)**:
  - 浅色背景: `#F9FAFB` | 浅色卡片: `#FFFFFF` | 描边: `#D1D5DB`
  - 深色背景: `#111827` | 深色卡片: `#1F2937` | 描边: `#4B5563`
- **状态语义色阶 (Status Colors)**:
  - **就绪/成功 (Ready/Success)**: `#0E9F6E` (绿) / 容器 `#DEF7EC`
  - **等待/挂起 (Waiting/Warning)**: `#C27803` (黄褐) / 容器 `#FEF08A`
  - **错误/阻断 (Error/Failed)**: `#E02424` (红) / 容器 `#FDE8E8`
  - **运行中 (Running/Streaming)**: `#1C64F2` (蓝) / 容器 `#EBF5FF`
  - **已停用/已撤销 (Revoked/Paused)**: `#6B7280` (灰) / 容器 `#F3F4F6`

### 1.3 排版系统 (Typography)

- **字体族**:
  - 正文字体: `Roboto`, `-apple-system`, `BlinkMacSystemFont`, `Segoe UI`, `sans-serif`
  - 代码/Token/数据字体: `JetBrains Mono`, `Menlo`, `Monaco`, `Consolas`, `monospace`
- **字号阶梯**:
  - `Display Small`: 36sp / 行高 44sp / Regular (主要用于欢迎页或大屏标题)
  - `Headline Small`: 24sp / 行高 32sp / SemiBold (页面一级标题)
  - `Title Medium`: 16sp / 行高 24sp / Medium (卡片标题、列表头)
  - `Body Large`: 16sp / 行高 24sp / Regular (主要聊天消息正文)
  - `Body Medium`: 14sp / 行高 20sp / Regular (二级说明、辅助信息)
  - `Label Medium`: 12sp / 行高 16sp / Medium (状态徽标、按钮标签)
  - `Code Medium`: 13sp / 行高 18sp / Regular (Prompt 预览、请求审查、JSON)

### 1.4 栅格与间距系统 (Spacing Grid)

基于 4dp 基础网格：
- `xs` (4dp): 标签内边距、极小元素间隔
- `sm` (8dp): 紧凑元素间隔、徽标内边距
- `md` (12dp): 输入框内边距、紧凑卡片间距
- `base` (16dp): 标准屏幕边距、标准卡片内边距
- `lg` (20dp): 模块间垂直间隔
- `xl` (24dp): 宽松区块间距
- `xxl` (32dp): 弹窗及底部抽屉顶部间距

### 1.5 简体中文与多语言支持 (Simplified Chinese & Localization)

系统以 **简体中文 (zh-CN)** 与 **英文 (en-US)** 作为第一方双语支持，默认优先采用简体中文或跟随系统语言设置：

1. **中文字体与字偶回退 (CJK Font Stack)**:
   - 优先使用 `Noto Sans SC`、`PingFang SC`、`Microsoft YaHei`，无此字体时平滑回退至系统默认 sans-serif。
   - 针对中文字符密集排版，行高在标准字阶基础上增加 1.25x - 1.4x 间距，保证复杂汉字在深色模式下的可读性。
2. **文本长度适配 (Layout Elasticity)**:
   - 中文表达通常比英文紧凑（字数约少 30% - 40%），但词组不可截断；按钮与标签预留自适应宽度，禁止固定字宽导致排版断字。
3. **术语中英双语标准对照**:
   - 会话 / 智能体: `Chat / Agents`
   - 服务商 / 密钥: `Providers / API Secrets (Keystore Protected)`
   - 知识库 / 内容寻址存储: `Knowledge Base / CAS (Content Addressed Storage)`
   - 技能 / 隔离沙箱: `Skills / Isolated Process Sandbox`
   - 公告中心 / 强制确认: `Announcements / Mandatory Acknowledgement`
   - 审查请求 / 引用回跳: `Inspect Request / Citation Jump-back`
4. **语言切换控制**:
   - 用户可在【设置/关于】（SCR-SETT-01）中随时切换语言（跟随系统 / 简体中文 / English），UI 界面即时响应无需重启。

---

## 2. 导航架构与全局布局

```text
+-------------------------------------------------------------+
| [Top App Bar] 页面标题 / 状态指示器 / 辅助操作按钮           |
+-------------------------------------------------------------+
|                                                             |
|                      [主内容区域 Content]                   |
|           (根据当前所选 Tab 显示列表、表单、详情或对话流)    |
|                                                             |
+-------------------------------------------------------------+
| [Bottom Navigation Bar] 7个核心入口                         |
| [Chat] [Agents] [Providers] [Knowledge] [Skills] [News] [About]
+-------------------------------------------------------------+
```

### 2.1 底部导航栏 (Bottom Navigation Bar)
包含 7 个一级入口：
1. **Chat (对话)**: 实时问答、流式输出、工具确认、引用追溯。
2. **Agents (智能体)**: Agent 配置、Prompt 版本控制、参数与资源绑定。
3. **Providers (模型源)**: BYOK 服务商管理、API Key 安全存储、连通性探测。
4. **Knowledge (知识库)**: 本地文档导入、状态跟踪、视觉等待横幅、索引管理。
5. **Skills (技能)**: 本地 Python/Native 技能清单、权限管理、审计日志。
6. **News (公告)**: 强制公告、更新通知、离线缓存、阅读确认。
7. **About (关于与设置)**: 隐私设置、数据备份导出、AGPL 许可与版本信息。

### 2.2 响应式适配规范
- **紧凑竖屏 (Phone Portrait, < 600dp)**: 采用标准底部导航栏与单列滚动卡片。
- **宽屏/横屏/平板 (Landscape / Tablet, >= 600dp)**: 底部导航栏自动转为左侧导航轨 (Navigation Rail)，主内容区采用 Master-Detail 双栏布局（左侧列表，右侧详情）。
- **软键盘遮挡自适应**: 输入框悬浮在输入法上方 (IME Inset Padding)，聊天列表自动滚动至最新消息。

---

## 3. 七类核心软件页面详细设计

### 3.1 Chat (对话管理)

#### SCR-CHAT-01: 会话列表 (Session List)
- **顶部**: 搜索会话框、新建会话按钮 `[+ New Session]`。
- **列表项**:
  - Agent 名称徽标（如 `[General Agent]`、`[Doc Research]`）。
  - 会话标题、最后更新时间（UTC/本地时间）。
  - 最近一条消息摘要。
  - 右侧滑动菜单：重命名、导出（脱敏）、删除会话。
- **空状态**: 显示 `[No active conversations. Start a new session below.]` 与快捷创建卡片。

#### SCR-CHAT-02: 聊天详情与消息流 (Conversation View)
- **顶部栏**: 显示当前绑定 Agent 名称、当前所用模型代号、请求审查入口按钮 `[Inspect Request]`。
- **消息气泡**:
  - **用户消息**: 右对齐，主色容器背景，白色文字。
  - **Agent 消息**: 左对齐，表面容器背景，深色文字；包含完整 Markdown 渲染（标题、列表、代码块、表格、LaTeX 公式）。
  - **状态徽标**: 消息下方标注 Token 消耗、生成耗时及所用索引代际。
- **输入区域**:
  - 多行自动扩展文本输入框，带占位提示 `[Ask anything or use attached knowledge...]`。
  - 附件按钮（快捷附加本地文档或图片）、发送按钮 `[Send]`、停止按钮 `[Stop]`。

#### SCR-CHAT-03: 流式输出与取消操作 (Streaming & Cancellation)
- **表现**:
  - 文本增量输出时显示脉冲光标 `|`。
  - 底部显示实时状态栏：`[Streaming: 142 tokens generated (24 t/s)]`。
  - 发送按钮在生成期间切换为高亮红色 `[Cancel / Stop]` 按钮。
  - 点击取消后，立即停止网络读取，消息状态标记为 `[Cancelled by User]`，保留已生成部分，严禁假装完整成功。

#### SCR-CHAT-04: 工具调用确认卡片 (Tool Approval Card)
- **触发条件**: 模型发起带副作用或需显式授权的 Tool Call（如写入文件、执行外部网络请求）。
- **卡片内容**:
  - 警告边框与标题：`[Tool Execution Approval Required]`。
  - 工具标识与所属 Skill：`skill_calc / compute_matrix`。
  - 参数预览表格（JSON 格式参数严格对齐展示）。
  - 风险级别提示（例如：`[Risk: Medium - Local Network Access]`）。
  - 操作按钮组：`[Approve Once]`、`[Approve for Session]`、`[Reject]`。

#### SCR-CHAT-05: 引用卡片与原文回跳 (Citation Card)
- **表现**:
  - Agent 回答中包含引用编号，如 `[Ref 1]`, `[Ref 2]`。
  - 底部展示折叠式引用卡片列表：
    - 引用源文档名称、所属知识库、对应页码/Chunk 序号、相关度评分。
    - 文本片段摘录（带高亮匹配词）。
    - 点击可展开底部查看器，直接跳转并高亮展示 CAS 中的原始文档或图片位置。

#### SCR-CHAT-06: 有效请求审查抽屉 (Request Inspector Drawer)
- **触发**: 点击顶部 `[Inspect Request]` 按钮从右侧滑出。
- **审查内容**:
  - **外发目标**: Provider 名称及 Base URL。
  - **脱敏密钥**: 显示为 `Header: Authorization: Bearer sk-*** [Protected in Keystore]`。
  - **分层 Prompt**:
    1. System Prompt (用户自定义与 Runtime 注入分段展示)
    2. 知识库注入上下文 (标明具体 Chunk 长度与 Token 预算占用)
    3. 历史对话上下文 (已裁剪轮次提示)
    4. 当前用户输入
  - **Token 预算统计**: 输入预算、保留输出预算及最大限制。

---

### 3.2 Agents (智能体配置)

#### SCR-AGENT-01: Agent 列表 (Agents Overview)
- **页面布局**:
  - 顶部搜索与过滤（内置 Agent / 自定义 Agent）。
  - 新建 Agent 悬浮按钮 `[+ Create Agent]`。
  - Agent 卡片展示：名称、简介、已绑定模型（Chat/Vision/Embedding）、绑定的知识库数量、绑定的 Skills 数量。

#### SCR-AGENT-02: Agent 编辑与详情 (Agent Editor)
- **基础信息**: Agent 名称、图标/标识选择、描述说明。
- **核心模型角色分配**:
  - Chat 主模型选择器（下拉选择已配置 Provider 下的模型）。
  - Vision 视觉模型选择器（标记是否具备视觉解析能力）。
  - Embedding 向量模型选择器（默认本地 ONNX / 可选用户 API）。
  - Reranker 重排模型选择器（可选）。

#### SCR-AGENT-03: Prompt 编辑与版本管理 (Prompt Editor & Revisions)
- **编辑器**: 支持语法高亮的多行文本框，支持白名单变量插值提示（如 `{date}`, `{agent_name}`）。
- **版本历史列表**:
  - 显示历史修改时间、修订 ID、版本说明。
  - 支持版本对比视图 (Diff View)，清晰显示增删行。
  - 支持一键回滚 `[Restore this revision]`。

#### SCR-AGENT-04: 模型与检索高级参数 (Model & Retrieval Parameters)
- **参数滑块与输入框**:
  - Temperature (0.0 - 2.0，步长 0.05)
  - Top P (0.0 - 1.0)
  - Max Context Tokens (数值输入与预算警告条)
  - Top K 召回条数 (1 - 20)
  - 混合检索权重 (FTS5 词法 vs 向量 RRF 比率滑块)
- **自定义 JSON 参数**: 供高级用户输入特定服务商专属参数，自动进行 JSON 校验。

#### SCR-AGENT-05: 知识库与 Skill 绑定 (Resource Binding)
- **知识库绑定勾选列表**: 列出已就绪知识库，支持多选，显示当前库的索引代际。
- **Skill 绑定勾选列表**: 列出已安装且启用的 Skills，显示其请求的权限范围。

#### SCR-AGENT-06: 快照变更提示 (Snapshot Boundary Warning)
- **触发**: 在已有会话进行中修改 Agent 配置时。
- **提示内容**: 明确告知用户 `[Modifying this configuration will create a new Snapshot Boundary for subsequent messages. Historical turns will retain their original configuration.]`。

---

### 3.3 Providers (模型服务商)

#### SCR-PROV-01: Provider 列表 (Providers Overview)
- **卡片列表**:
  - 服务商名称（如 `DeepSeek Official`, `OpenAI`, `Self-hosted vLLM`）。
  - API 格式（OpenAI Compatible 等）。
  - 状态指示标：`[Active]`、`[Error: 401 Unauthorized]`、`[Untested]`。
  - 关联模型数量统计。

#### SCR-PROV-02: Provider 编辑与密钥存储 (Provider Editor & Secrets)
- **表单字段**:
  - Provider 名称。
  - API 格式选择（默认 `Custom OpenAI-Compatible`）。
  - Base URL (例如 `https://api.deepseek.com/v1`)。
  - **API Key 输入框**:
    - 采用密码遮蔽模式显示 `***`。
    - 切换明文/密文查看按钮（需系统生物识别或提示）。
    - 提示文案：`[Stored securely in Android Keystore with AES-GCM encryption. Never leaves the device except in outbound API requests.]`。
  - 自定义 Header 编辑表格（支持添加附加 Header，敏感字段自动作为 Secret 处理）。

#### SCR-PROV-03: 模型与能力配置 (Model Profiles & Capabilities)
- **模型列表**:
  - 模型 ID（例如 `deepseek-chat`, `gpt-4o-mini`）。
  - 支持能力开关复选框：`[Streaming]`、`[Tool Calls]`、`[Vision / Multimodal]`、`[JSON Mode]`。
  - 上下文窗口上限 (Context Limit) 与最大输出限制 (Max Output)。

#### SCR-PROV-04: 连通性测试与诊断 (Connectivity Probe & Diagnostics)
- **操作**: 点击 `[Test Connection]` 按钮。
- **诊断弹窗**:
  - 发送轻量级探针请求。
  - **成功状态**: 绿色徽标 `[Status: Connected]`, 往返延迟 (如 `186ms`)，支持模型清单获取。
  - **失败状态**: 红色徽标 `[Status: Failed]`, 经过 SecretRedactor 脱敏的真实错误原因（如 `HTTP 401: Invalid API Key` 或 `DNS Resolution Timeout`），给出修改建议。

---

### 3.4 Knowledge (知识库管理)

#### SCR-KNOW-01: 知识库总览 (Knowledge Base Overview)
- **页面布局**:
  - 知识库卡片列表：显示库名称、描述、文档总数、图片总数、分块总数、所用 Embedding 模型空间、索引状态（`[READY]`、`[PROCESSING]`、`[WAITING_VISION]`）。
  - 新建知识库按钮 `[+ New Knowledge Base]`。

#### SCR-KNOW-02: 文档明细列表 (Document Detail & File Management)
- **列表内容**:
  - 文件名、文件格式徽标（`[TXT]`, `[MD]`, `[PDF]`, `[DOCX]`, `[IMAGE]`）。
  - 文件大小、导入时间、解析状态。
  - CAS SHA-256 哈希值缩略（例如 `sha256:8f4c...`）。
  - 单文件操作菜单：查看原文、查看分块、重新索引、删除。

#### SCR-KNOW-03: SAF 文件选择与批量导入 (SAF Import Flow)
- **交互**:
  - 调用 Android 系统的 Storage Access Framework (SAF) 文件选择器。
  - 支持多选文本文件、Markdown、PDF、Office 文档及常见格式图片。
  - 导入前检查清单：列出选中文件、格式合规性检查、是否包含图片提示。

#### SCR-KNOW-04: 导入进度与后台前台服务状态 (Import Job Monitor)
- **前台通知与界面进度卡片**:
  - 任务状态：`[Processing: Document 14 / 50]`。
  - 阶段进度条：`1. Parsing Document` -> `2. Chunking & Lexical Index (FTS5)` -> `3. Embedding Generation` -> `4. Vector Index Building`。
  - 暂停/恢复按钮 `[Pause]` / `[Resume]`、取消导入按钮 `[Cancel Job]`。

#### SCR-KNOW-05: 视觉模型等待与 Embedding 确认横幅 (Waiting State Banners)
- **视觉等待状态 (WAITING_FOR_VISION_MODEL)**:
  - 醒目黄色警告横幅：`[Notice: 6 images / visual pages found in this import. Waiting for Vision Model configuration. Documents will not be marked ready until vision processing is complete.]`。
  - 操作按钮：`[Configure Vision Model]`、`[Keep Waiting]`、`[Exclude Images (Text Only)]`。
- **云端 Embedding 授权确认**:
  - 蓝色提示横幅：`[Notice: External API Embedding selected. Text chunks will be sent to the configured provider for vectorization. Confirm to proceed.]`。

#### SCR-KNOW-06: 原文与原图证据查看器 (Evidence Viewer)
- **界面**:
  - 纯文本/Markdown: 带有行号的代码查看器，高亮显示命中 Chunk。
  - PDF: 页面预览与选区高亮框。
  - 图片: 高清原图缩放查看器，框出识别出的图表或区域。

#### SCR-KNOW-07: 索引重建与安全删除 (Rebuild & Deletion Modal)
- **删除确认**:
  - 强调说明：`[Deleting this knowledge base removes associations and search indices. Shared CAS files will be cleaned up safely only when no other knowledge bases reference them.]`。
- **重建索引**:
  - 允许在切换 Embedding 模型或索引损坏时一键重建向量索引。

---

### 3.5 Skills (技能与工具扩展)

#### SCR-SKILL-01: Skill 列表与状态 (Skills Overview)
- **分类标签页**: `[All Skills]`, `[Installed]`, `[System Builtin]`, `[Disabled]`。
- **卡片展示**:
  - Skill 名称与版本号（如 `Web Fetcher v1.2.0`、`Code Calculator v0.9`）。
  - 执行运行时类型：`[Isolated CPython]` / `[Native Kotlin]`。
  - 启用/禁用切换开关 (Switch)。
  - 权限摘要徽标：`[Network]`、`[File Read]`、`[No Permissions]`。

#### SCR-SKILL-02: Skill 详情与安全清单 (Security Manifest Viewer)
- **详情包含**:
  - 作者、开源许可证标识（如 `MIT`, `Apache-2.0`）。
  - 源代码查看器：可直接逐行审阅 `skill.py` 或脚本源文件，禁止黑盒执行。
  - 请求的权限列表（明确列出目标域名、允许访问的文件类型）。
  - 资源配额说明（单次运行超时时间：5s，最大内存限制：64MB）。

#### SCR-SKILL-03: Skill 导入与安装向导 (Skill Install Flow)
- **步骤**:
  1. 选取 ZIP / 目录包。
  2. 验证 `SKILL.md` 清单与签名哈希。
  3. 静态安全检查：扫描是否包含未知二进制 `.so`/`.dex`、非法软链接、目录穿越（Zip Slip）。
  4. 权限确认页面：用户明确勾选所授予的最小权限。
  5. 安装完成并就绪。

#### SCR-SKILL-04: 权限矩阵与实时撤销 (Permission Matrix)
- **控制项**:
  - 网络访问：可针对具体域名开启/关闭白名单。
  - 临时文件读取：查看当前活动的临时文件句柄。
  - 实时一键撤销按钮 `[Revoke All Permissions Immediately]`。
  - 撤销后正在进行的调用立即返回 `PERMISSION_DENIED`。

#### SCR-SKILL-05: 工具调用审计日志 (Tool Execution Audit Log)
- **日志表格**:
  - 时间戳 (UTC)、调用 ID (Invocation ID)。
  - 所属 Agent 与会话 ID。
  - 执行结果：`[SUCCESS]`、`[FAILED]`、`[REJECTED_BY_USER]`、`[TIMEOUT]`。
  - 耗时与消耗内存统计。
  - 脱敏后的输入参数与输出摘要。

---

### 3.6 Announcements (公告中心)

#### SCR-ANN-01: 公告中心列表 (Announcements Center Feed)
- **展示方式**: 按照发布时间逆序排列的卡片流。
- **顶部**: 状态过滤器（全部 / 未读）、一键标为已读按钮 `[Mark all as read]`。
- **公告卡片**:
  - 严重等级徽标 (severity)：`[Critical]` (红色)、`[Warning]` (橙黄色)、`[Info]` (蓝色)。
  - 公告标题、发布日期、修订号 (Revision)、ETag 摘要。
  - 未读红点指示器。
  - 摘要预览。

#### SCR-ANN-02: 公告详情阅读 (Announcement Detail View)
- **内容**:
  - 完整富文本与 Markdown 渲染（支持有序列表、链接、强调文本）。
  - 数字签名验证状态徽标：`[Signature: Ed25519 Verified [Worker Origin]]`。
  - 操作按钮：`[Acknowledge / Close]`。

#### SCR-ANN-03: 未读与分类筛选 (Category & Unread Filter Bar)
- **分类标签**: `[全部 (All)]`, `[安全 (Security)]`, `[功能 (Feature)]`, `[维护 (Maintenance)]`, `[未读 (Unread)]`。
- **交互**: 动态过滤 Feed 列表中的公告项目。

#### SCR-ANN-04: 应用内置顶横幅 (Pinned Announcement Banner)
- **展示位置**: 位于应用顶部导航栏下方。
- **触发条件**: 公告 `displayMode: BANNER` 且当前处于生效时间窗口内。
- **样式**: 高亮背景条，显示重要通知单行摘要与查看按钮 `[View Details]`，右侧有关闭按钮 `[X]`。

#### SCR-ANN-05: 强制重要公告确认弹窗 (Mandatory Acknowledgement Dialog)
- **触发条件**: 接收到标记为 `mustAcknowledge: true`（或 `displayMode: MODAL`）且本地尚未记录确认状态的公告（如安全补丁、重大服务变更）。
- **交互**:
  - 模态对话框，阻断常规操作（`mustAcknowledge=true` 时不可点击外部空白处忽略）。
  - 用户必须阅读并点击 `[I Have Read and Acknowledge]` 按钮后方可解除阻断，并将确认凭证持久化写入本地 SQLite。

---

### 3.7 Settings / About (设置与关于)

#### SCR-SETT-01: 设置与隐私偏好 (Settings & Privacy Preferences)
- **偏好开关**:
  - **匿名统计开关**: 默认关闭，文案：`[Anonymous Announcement Metrics: Disabled (No device telemetry is uploaded)]`。
  - **请求检查模式**: 开启后在发送前默认弹出有效请求检查。
  - **主题设置**: `[Follow System]`, `[Light Theme]`, `[Dark Theme]`。
  - **本地缓存清理**: 临时文件、缩略图缓存一键清理。

#### SCR-SETT-02: 数据备份与完整导出 (Backup, Export & Restore)
- **导出操作**:
  - 导出所有 Agent 配置与 Prompt 历史为标准 JSON。
  - 导出完整知识库与元数据（CAS 归档）。
  - 安全提示：`[Export excludes all API Keys and Keystore credentials by default. To create a full migration package, explicit secondary verification is required.]`。
- **导入恢复**:
  - 支持还原旧版本数据包，自动执行 Schema 迁移。

#### SCR-SETT-03: 版本信息与检查更新 (About, Version & Updates)
- **展示内容**:
  - 软件名称与工程代号: `mobileAgentRuntime`。
  - 版本号 (Version Name): 如 `v1.0.0-dev`。
  - 构建 Git Commit SHA: 如 `c9798dc...`。
  - 官方源代码仓库链接与 CODEOWNERS 声明。
- **操作按键**:
  - `[检查更新 (Check for Updates)]`：点击向官方 Worker 安全端点请求最新版本发布快照与 Ed25519 签名。
  - 若为最新版，弹出提示 `[当前已是最新版本 (v1.0.0-dev)]`；
  - 若有新版本发布，展示版本变更摘要、安全修复等级及 SHA-256 校验包信息。

#### SCR-SETT-04: 许可证与第三方声明 (License & Notices)
- **第一方许可**: 完整展示 GNU Affero General Public License v3 (AGPL-3.0-only) 官方文本。
- **第三方开源组件公告**: 完整展示项目使用的开源库（如 AndroidX, Jetpack Compose, Kotlin Multiplatform, Ktor, kotlinx.serialization, USearch, ONNX Runtime 等）的原作者版权及许可证声明。

---

## 4. 高保真页面稿件索引 (Screens Index)

高保真矢量设计稿存放在 [docs/design/screens/](design/screens/README.md)，支持无损缩放、暗色/明色主题与标注查看：

1. `scr-chat-01-light.svg`: Chat 详情、消息气泡、流式状态与工具调用确认卡片（浅色主题）
2. `scr-chat-02-inspector-dark.svg`: 有效请求审查抽屉与密钥脱敏面板（深色主题）
3. `scr-agent-01-light.svg`: Agent 配置编辑、Prompt 版本对比与模型角色分配（浅色主题）
4. `scr-prov-01-dark.svg`: Provider 管理、API 密钥掩码输入与连通性测试（深色主题）
5. `scr-know-01-light.svg`: 知识库总览、文档列表与多模态视觉等待横幅（浅色主题）
6. `scr-skill-01-dark.svg`: Skill 列表、安全清单、权限控制矩阵与审计日志（深色主题）
7. `scr-ann-01-light.svg`: 公告中心、置顶横幅与强制确认模态弹窗（浅色主题）
8. `scr-sett-01-dark.svg`: 设置主页、隐私保护开关、数据导出与 AGPL 许可证（深色主题）

---

## 5. 本地可点击原型与交互验证

配套交付单文件本地可点击原型 [ui-prototype.html](design/ui-prototype.html)。

- **运行方式**: 直接使用任意现代浏览器打开 `docs/design/ui-prototype.html` 即可预览完整交互流程。
- **覆盖流程**:
  1. 7 个主导航 Tab 之间的无缝切换与状态保持。
  2. 浅色 / 深色主题一键实时切换。
  3. Chat 消息发送模拟、流式打字输出模拟、实时取消操作。
  4. 请求审查抽屉 (Request Inspector) 的滑出与关闭。
  5. 知识库视觉等待横幅交互与状态切换。
  6. Provider 密钥遮蔽与连通性探测诊断弹窗。
  7. 公告横幅与强制确认阻断对话框。
  8. 设置隐私开关切换与数据导出模拟。
- **设计保证**: 原型所有数据均为本地 Mock 数据，不发起任何真实付费 API 请求，不读取真实系统密钥，完全离线运行。
