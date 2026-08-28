<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 设计源稿与矢量素材说明

本目录存放 mobileAgentRuntime 软件页面的矢量设计源稿及组件图层说明。

## 1. 交付源稿格式与规范

- **源稿格式**: 标准 SVG 矢量图与结构化 JSON Token。接手者可直接使用任意矢量设计软件（如 Inkscape, Figma, Adobe Illustrator, Penpot）或直接编辑 XML 代码进行样式调整。
- **色彩与标注绑定**: 严格绑定 `docs/design/ui-tokens.json` 中定义的语义 Token，支持浅色（Light）与深色（Dark）模式无损换肤。
- **排版字体**: 正文统一采用 `Roboto`，代码与 Token 采用 `JetBrains Mono`。
- **字符合规**: 全套设计稿及源文件严禁包含 Emoji 表情符号，状态指示与操作全部采用标准矢量图标或文字徽标。

## 2. 页面矢量文件清单

所有矢量源稿均存放在 `docs/design/screens/`：

1. `scr-chat-01-light.svg`: Chat 主对话界面、气泡排版、引用卡片与工具确认卡片（浅色）
2. `scr-chat-02-inspector-dark.svg`: 有效请求审查抽屉、Prompt 分层与密钥脱敏面板（深色）
3. `scr-agent-01-light.svg`: Agent 配置编辑、Prompt 版本对比与模型角色分配（浅色）
4. `scr-prov-01-dark.svg`: BYOK 服务商管理、Keystore 密钥掩码输入与连通性测试（深色）
5. `scr-know-01-light.svg`: 知识库总览、文档列表与多模态视觉等待横幅（浅色）
6. `scr-skill-01-dark.svg`: Skill 沙箱管理、安全清单、权限矩阵与审计日志（深色）
7. `scr-ann-01-light.svg`: 公告中心、置顶横幅与强制确认模态弹窗（浅色）
8. `scr-sett-01-dark.svg`: 设置主页、隐私保护开关、数据导出与 AGPL 许可证（深色）

## 3. 许可证声明

本目录所有第一方设计源稿及文档均以 `AGPL-3.0-only` 开源。
