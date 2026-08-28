<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 软件页面 UI 实现映射与差异对齐

本文件定义 Android 软件七类核心页面及其子页面/状态到代码架构、路由、组件、用例及验收 ID 的具体映射规范，并提供从 M1 早期界面向 M0.5 设计基线平滑迁移的差异对齐方案。

## 1. 屏幕与组件全量映射矩阵

| screenId | 页面/子页面名称 | 视觉原型与稿件 | Compose 对应实现 | ViewModel / 状态契约 | 关联里程碑 | 验收 ID |
| --- | --- | --- | --- | --- | --- | --- |
| SCR-CHAT-01 | 会话列表页面 | `scr-chat-01-light.svg` | `feature.chat.ChatListScreen` | `ChatListViewModel` | M1, M7 | U01, U04, A05 |
| SCR-CHAT-02 | 聊天详情与消息流 | `scr-chat-01-light.svg` | `feature.chat.ChatScreen` | `ChatViewModel.lines` | M1 | U01, U02, A01, A03 |
| SCR-CHAT-03 | 流式输出与取消操作 | `scr-chat-01-light.svg` | `feature.chat.StreamingIndicator` | `ChatViewModel.streaming` | M1 | U03, A04, S10 |
| SCR-CHAT-04 | 工具调用确认卡片 | `scr-chat-01-light.svg` | `feature.chat.ToolApprovalCard` | `ChatViewModel.pendingApproval` | M5 | U03, U05, S08, S09 |
| SCR-CHAT-05 | 引用卡片与原文回跳 | `scr-chat-01-light.svg` | `feature.chat.CitationCard` | `ChatViewModel.citations` | M3, M4 | U01, U04, K04, K08 |
| SCR-CHAT-06 | 有效请求审查抽屉 | `scr-chat-02-inspector-dark.svg` | `feature.chat.RequestInspectorDrawer` | `ChatViewModel.lastRequestPreview` | M1 | U05, A03, A05 |
| SCR-AGENT-01 | Agent 列表页面 | `scr-agent-01-light.svg` | `feature.agents.AgentsListScreen` | `AgentsViewModel.agents` | M1 | U01, A05, A06 |
| SCR-AGENT-02 | Agent 创建与配置详情 | `scr-agent-01-light.svg` | `feature.agents.AgentDetailScreen` | `AgentsViewModel.selectedAgent` | M1 | U01, U02, A05 |
| SCR-AGENT-03 | Prompt 编辑与版本管理 | `scr-agent-01-light.svg` | `feature.agents.PromptEditor` | `AgentsViewModel.promptRevisions` | M1, M7 | U01, A05 |
| SCR-AGENT-04 | 模型角色与高级参数调节 | `scr-agent-01-light.svg` | `feature.agents.ModelParamConfig` | `AgentsViewModel.modelProfiles` | M1 | U02, A04 |
| SCR-AGENT-05 | 知识库与 Skill 绑定选择 | `scr-agent-01-light.svg` | `feature.agents.ResourceBindingSheet` | `AgentsViewModel.resourceBindings` | M1, M5 | U04, A06, S08 |
| SCR-AGENT-06 | 快照变更与历史分界提示 | `scr-agent-01-light.svg` | `feature.agents.SnapshotBoundaryBanner`| `AgentsViewModel.snapshotNotice` | M1 | U03, A05 |
| SCR-PROV-01 | Provider 列表页面 | `scr-prov-01-dark.svg` | `feature.providers.ProvidersScreen` | `ProvidersViewModel.providers` | M1 | U01, A03, A04 |
| SCR-PROV-02 | Provider 编辑与密钥输入 | `scr-prov-01-dark.svg` | `feature.providers.ProviderDetailScreen` | `ProvidersViewModel.currentEdit` | M1 | U02, U05, A03 |
| SCR-PROV-03 | 模型配置与能力矩阵 | `scr-prov-01-dark.svg` | `feature.providers.ModelCapabilityList` | `ProvidersViewModel.modelList` | M1 | U01, A04 |
| SCR-PROV-04 | 连通性测试与失败诊断 | `scr-prov-01-dark.svg` | `feature.providers.ProbeResultDialog` | `ProvidersViewModel.probeState` | M1 | U03, U05, A03, A04 |
| SCR-KNOW-01 | 知识库总览列表 | `scr-know-01-light.svg` | `feature.knowledge.KnowledgeScreen` | `KnowledgeViewModel.repositories`| M1, M3 | U01, K01, K05 |
| SCR-KNOW-02 | 知识库文档详情列表 | `scr-know-01-light.svg` | `feature.knowledge.DocumentListScreen` | `KnowledgeViewModel.documents` | M3 | U01, K02 |
| SCR-KNOW-03 | SAF 系统文件选择与导入 | `scr-know-01-light.svg` | `feature.knowledge.SafImportDialog` | `KnowledgeViewModel.importJob` | M1, M3 | U04, K01, K02 |
| SCR-KNOW-04 | 导入进度与多任务状态 | `scr-know-01-light.svg` | `feature.knowledge.ImportProgressSheet` | `KnowledgeViewModel.jobs` | M1, M3 | U03, K06 |
| SCR-KNOW-05 | 视觉等待与 Embedding 确认 | `scr-know-01-light.svg` | `feature.knowledge.WaitingModelBanner` | `KnowledgeViewModel.waitingState`| M1, M4 | U03, U05, K03, K04 |
| SCR-KNOW-06 | 原文与原图证据查看器 | `scr-know-01-light.svg` | `feature.knowledge.EvidenceViewer` | `KnowledgeViewModel.activeEvidence`| M4 | U01, U04, K04, K08 |
| SCR-KNOW-07 | 知识库删除与索引重建 | `scr-know-01-light.svg` | `feature.knowledge.RebuildIndexDialog` | `KnowledgeViewModel.maintenance` | M3 | U03, U05, K07 |
| SCR-SKILL-01 | Skill 列表与状态概览 | `scr-skill-01-dark.svg` | `feature.skills.SkillsScreen` | `SkillsViewModel.installedSkills` | M5 | U01, S01 |
| SCR-SKILL-02 | Skill 详情与安全清单查看 | `scr-skill-01-dark.svg` | `feature.skills.SkillDetailScreen` | `SkillsViewModel.selectedManifest`| M5 | U01, U05, S01, S02 |
| SCR-SKILL-03 | Skill 导入与签名/哈希校验 | `scr-skill-01-dark.svg` | `feature.skills.InstallSkillSheet` | `SkillsViewModel.installWorkflow`| M5 | U04, S01, S02 |
| SCR-SKILL-04 | 权限矩阵与逐项撤销控制 | `scr-skill-01-dark.svg` | `feature.skills.PermissionMatrixView` | `SkillsViewModel.activeGrants` | M5, M6 | U03, U05, S04, S07 |
| SCR-SKILL-05 | 工具调用与 Broker 审计日志 | `scr-skill-01-dark.svg` | `feature.skills.ToolAuditScreen` | `SkillsViewModel.auditEvents` | M5, M6 | U05, S06, S10 |
| SCR-ANN-01 | 公告中心列表 | `scr-ann-01-light.svg` | `feature.announcements.AnnouncementsScreen` | `AnnouncementsViewModel.feed` | M1, M2 | U01, N01, N02 |
| SCR-ANN-02 | 公告正文阅读详情 | `scr-ann-01-light.svg` | `feature.announcements.AnnouncementDetailScreen` | `AnnouncementsViewModel.current` | M2 | U01, N04 |
| SCR-ANN-03 | 未读标记与分类筛选 | `scr-ann-01-light.svg` | `feature.announcements.CategoryFilterBar`| `AnnouncementsViewModel.filter` | M2 | U01, N03 |
| SCR-ANN-04 | 应用内置顶重要横幅 | `scr-ann-01-light.svg` | `feature.announcements.PinnedNoticeBanner`| `AnnouncementsViewModel.banner` | M2 | U01, N01, N04 |
| SCR-ANN-05 | 强提醒与确认阻断弹窗 | `scr-ann-01-light.svg` | `feature.announcements.AckNoticeDialog` | `AnnouncementsViewModel.ackState` | M2 | U03, U05, N03 |
| SCR-SETT-01 | 设置主页与隐私开关 | `scr-sett-01-dark.svg` | `feature.settings.SettingsScreen` | `SettingsViewModel.privacyPrefs` | M1, M7 | U01, U05, N08 |
| SCR-SETT-02 | 数据导入导出与离线备份 | `scr-sett-01-dark.svg` | `feature.settings.BackupRestoreScreen` | `SettingsViewModel.backupState` | M7 | U04, U05, A07 |
| SCR-SETT-03 | 应用版本与源码信息 | `scr-sett-01-dark.svg` | `feature.settings.AboutScreen` | `AboutViewModel.buildInfo` | M0, M1 | U01, L04 |
| SCR-SETT-04 | AGPL-3.0 协议与开源许可 | `scr-sett-01-dark.svg` | `feature.settings.LicenseViewerScreen` | `AboutViewModel.licenseText` | M0, M7 | U01, L04 |

## 2. 现有 M1 实现与 M0.5 设计基线差异分析

下表记录当前代码库中的极简界面与 M0.5 正式设计稿之间的差异，明确保留项与补齐范围：

### 2.1 Chat 模块 (`feature/chat`)
- **当前状态**：单屏文本追加列表，包含基础 `TextField` 与 `Send/Cancel` 按钮；状态文本作为简单字符串展示。
- **保留项**：ViewModel 内部流式取消机制、秘密脱敏出口、消息数据契约。
- **需按设计对齐项**：
  1. 升级为双层会话架构：会话列表（SCR-CHAT-01）与聊天详情（SCR-CHAT-02）。
  2. 引入气泡卡片布局、用户与 Agent 差异化头像与背景。
  3. 增加请求审查入口与抽屉（SCR-CHAT-06）。
  4. 增加引用卡片展开与回跳（SCR-CHAT-05）。
  5. 增加工具调用交互确认条目（SCR-CHAT-04）。

### 2.2 Providers 模块 (`feature/providers`)
- **当前状态**：简单的 Provider 列表卡片与名称/BaseURL 文本框。
- **保留项**：Android Keystore 加密存储与 `secretRef` 绑定流程。
- **需按设计对齐项**：
  1. 密钥输入框采用掩码显示（`***`）与切换可见性控件。
  2. 增加连通性与模型探测触发按钮及真实状态弹窗（SCR-PROV-04）。
  3. 增加模型列表与多角色模型选择器（Chat/Vision/Embedding/Reranker）。

### 2.3 Knowledge 模块 (`feature/knowledge`)
- **当前状态**：简单的导入任务列表与 SAF 文件拾取。
- **保留项**：CAS 内容寻址存储、FTS5 写入与 `WAITING_FOR_VISION_MODEL` 阻断逻辑。
- **需按设计对齐项**：
  1. 增加知识库卡片分组与文档明细表格（SCR-KNOW-01, 02）。
  2. 针对缺失视觉配置的文件展示醒目的等待授权横幅（SCR-KNOW-05）。
  3. 增加原文/原图查看器与索引状态（SCR-KNOW-06, 07）。

### 2.4 Agents, Skills, Announcements, Settings 模块
- **当前状态**：Agents/Skills 仍为占位。Announcements 已具备列表/详情/横幅/确认弹窗、未读筛选、本地验签缓存与统计开关（M2）；Settings/About 含匿名统计默认关闭。视觉仍未按全部 M0.5 标注对齐。
- **需按设计对齐项**：Agents/Skills 在对应里程碑按设计稿实现；公告管理 Web 已在 `admin/announcements/index.html` 提供本地编辑/预览/发布。

## 3. 实现顺序与文件归属

1. **M0.5（当前）**：完成设计系统规范（[docs/UI_DESIGN.md](../UI_DESIGN.md)）、设计 Token（[docs/design/ui-tokens.json](ui-tokens.json)）、可点击原型（[docs/design/ui-prototype.html](ui-prototype.html)）、高保真矢量稿（[docs/design/screens/](screens/README.md)）及映射文档；建立基础双语资源表（`values/strings.xml` 与 `values-zh-rCN/strings.xml`）。
2. **M1 实现**：按设计基线重构 `feature/chat`、`feature/providers`、`feature/agents` 主界面与会话流。
3. **M2 实现**：按设计基线实现 `feature/announcements` 客户端展示及通知弹窗。
4. **M3 & M4 实现**：按设计基线实现 `feature/knowledge` 文档管理、多模态等待横幅与证据回跳。
5. **M5 & M6 实现**：按设计基线实现 `feature/skills` 安全清单、权限开关与 Tool 确认卡片。
6. **M7 实现**：按设计基线实现 `feature/settings` 完整数据备份、隐私控制与许可阅读器。

## 4. 简体中文与本地化资源映射

- **资源路径**:
  - 默认/英文资源: `app-android/src/main/res/values/strings.xml`
  - 简体中文资源: `app-android/src/main/res/values-zh-rCN/strings.xml`
- **动态语言支持**:
  - `SettingsViewModel` 维护应用语言偏好（跟随系统 / 简体中文 / English）。
  - Compose 树通过 `CompositionLocalProvider(LocalConfiguration provides configuration)` 实现全应用界面语言热切换。
