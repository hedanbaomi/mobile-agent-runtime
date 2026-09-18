<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# fd87a80 独立复审：字体编码、图形状态与混合页视觉覆盖

状态：**2026-09-10 JVM 定向修复已落地**。审查包 `mobile-agent-runtime-fd87a80-review-evidence.zip`，审查提交 `fd87a80fd6332b9311cf2c36097eba98a05c3dd1`。修复源码提交 `903c33eadba4e20693233bac86e55d95e0b67c27`（`codex/user-qa-fixes` 已 push，作者/提交者 `luozhibai <wy3273564266@163.com>`，无 Cursor trailer）。探针复制当时 `extractPdfStrings` / `processVisualAssets` 控制流，不是完整 `PdfParser.parse`、Android、SQLite READY 或真实 Provider。本轮按生产路径修复，不重写 Vision 适配器，不承诺全部 PDF 字体。HANDOFF/`docs` 仅本地提交。

## 审查指控与结论

| # | 指控 | 结论 | 修复 |
| --- | --- | --- | --- |
| A | `/WinAnsiEncoding`、`/MacRomanEncoding` 被当成 Latin-1 恒等且 `complete=true`；`q`/`Q` 不保存/恢复当前字体，复审夹具得到 U+0080、U+008E 和 `XYZ XYZ` | **CONFIRMED** | 命名编码与 `/BaseEncoding`+`/Differences` 走 WinAnsi / MacRoman / Standard 基表；未映射字节 `complete=false`。`q` 压栈、`Q` 弹栈当前字体。fingerprint `pdf-text-v10-pdfrenderer` |
| B | 文档中任一 PAGE 阻断会跳过全部可处理 JPEG；无阻断页渲染失败被静默跳过，方法级可返回 Ok 而缺页 | **CONFIRMED** | 仅跳过阻断页自己的 JPEG；其它页的图仍可处理。栅格失败时：阻断页或尚未有证据的 needsVision 页失败关闭；已有 JPEG 证据的页可跳过整页渲染。结束后每个 PAGE 阻断页和每个 needsVision 页都必须有证据 |

Vision 目标冻结与过期 fingerprint 再导入不在本包反例中，源码保持上一轮行为。`rebuildIndex()` 仍从已存 chunks 重建。

## 验证

```
.\gradlew :shared:knowledge-api:test --tests runtime.mobileagent.knowledge.DocumentParserTest :data:sqlite:test --tests runtime.mobileagent.data.KnowledgeRepositoryTest --dependency-verification=strict --no-daemon
```

**BUILD SUCCESSFUL**（2026-09-10，42s）。提交前 `licenseGuard --dependency-verification=strict --no-daemon` **PASS**。CodeGraph `sync`：7 个变更文件。未跑全仓 `check`、设备测试、真实 Vision 或 294 原件。

回归覆盖：WinAnsi `Price: €10`、MacRoman `café`、`q`/`Q` 后第二段 `(ABC)` 保持 ABC、两页混合文档在第 1 页 PAGE 阻断且第 2 页渲染失败时仍处理第 2 页 JPEG 并 READY、第 2 页无图且渲染失败则 FAILED。
