<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# README 图表

[会话与工具图](project-overview.png)由 [Archify](https://github.com/tt-a1i/archify) 3.0.1 生成，规格见 [project-overview.json](project-overview.json)。PNG 使用 Archify 的浅色主题导出，不包含查看器按钮或来源浮层。

图表覆盖 Android 界面发起会话、Agent Runtime 调用模型、检索绑定知识库及授权后执行工具的路径。公告使用独立通道；知识导入、API Embedding、联网搜索和 MCP 的使用条件在仓库 README 中说明。

## 来源与验证

- 项目源码基线：`6d38ce77961378d28e63601853faa775047a667f`。JSON 中每个组件均附带该修订的源码范围。
- Archify 上游修订：`594f6087358610bd16e64e5602976020871b6bff`；安装目录对应上游 `archify/` 树 `b39bcc918173641199e7ed3e4c54bf8fddd47848`。
- `finalize` 的 validate、deliver、严格 provenance check 和 browser-check 均通过；showcase 包含 9 项制品检查，0 错误、0 警告。
- 浏览器检查覆盖 1440×900、1600×1000、1920×1080、2048×1320；另检查了深浅主题截图和 README 的 PNG 导出。
- PNG SHA-256：`40e26de81a639eeafb77edef31030d6232ffd3e4b0cd677ecb248189cdd1f1b4`。

图表源文件与 PNG 采用项目的 AGPL-3.0-only；Archify 工具本身保留其 MIT 许可。仓库中只存放项目规格和静态图，不包含 Archify 查看器运行时代码。

## 重新生成

安装 [Archify](https://github.com/tt-a1i/archify)，在仓库根目录执行。以下示例使用其 Codex 全局安装位置：

```powershell
$archifyCli = Join-Path $env:USERPROFILE ".agents/skills/archify/bin/archify.mjs"
node $archifyCli finalize architecture docs/diagrams/project-overview.json .private/readme-archify-20261005/project-overview.html --repo-root . --quality showcase --json
```

JSON 的 `meta.output` 指定交互 HTML 的本地生成路径。打开生成的 HTML，切换至浅色主题，从“导出 → PNG”保存图片，再替换 README 引用的 PNG。更换源码基线时，先核对组件与关系，更新来源范围和修订，再运行验收；不要把旧来源当作新源码的证明。
