<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# f24f5ae 独立复审：PDF 文字覆盖、PAGE 阻断与 parser 升级

状态：**2026-09-10 JVM 定向修复已落地**。审查包 `mobile-agent-runtime-f24f5ae-review-evidence.zip`，审查提交 `f24f5ae516f2e0a4a20579f76204ae58a78f8f4f`。修复源码提交 `fd87a80fd6332b9311cf2c36097eba98a05c3dd1`（`codex/user-qa-fixes` 已 push，作者/提交者 `luozhibai <wy3273564266@163.com>`，无 Cursor trailer）。探针复制当时 `extractPdfStrings` 等辅助函数，不是完整 `PdfParser.parse`、Android 或仓储运行。本轮按生产路径修复，不重写 Vision。HANDOFF/`docs` 仅本地提交。

## 审查指控与结论

| # | 指控 | 结论 | 修复 |
| --- | --- | --- | --- |
| 1 | 注释隔开的 `'` 操作、嵌套括号、`\\n` 转义、`/Differences` 字体映射仍被正则漏提或误提，且残留检查不覆盖 `'`/`"` | **CONFIRMED** | 内容流改为词法扫描：跳过 `%` 注释、嵌套字面量、十六进制、TJ 数组；从左到右解码 PDF 转义；按页内 `/Font` 的 `/Encoding`/`Differences` 映射字节。parser fingerprint `pdf-text-v9-pdfrenderer` |
| 2 | `hasUnsupportedPageVisual` 不含文字不完整；可用 JPEG 会去掉 PAGE 阻断，栅格失败时仓储可用图代替漏掉的正文 | **CONFIRMED** | `!extracted.complete` 计入 PAGE 阻断。存在 PAGE 阻断时不把内嵌 JPEG 当完整页证据；无 rasterizer 或渲染失败则失败关闭 |
| 3 | `isPublishedReady()` 不核对 `parser_fingerprint`；同 blob 再导入直接 READY，不重新解析 | **CONFIRMED** | 已发布 READY 还要求当前格式的 parser fingerprint 一致；过期 fingerprint 走再导入。`rebuildIndex()` 仍从已存 chunks 重建，不重读 CAS 字节 |

上一轮 Vision 目标冻结在源码中保持不变，本轮未改 `OpenAiCompatibleVision`。

## 验证

```
.\gradlew.bat :shared:knowledge-api:test --tests runtime.mobileagent.knowledge.DocumentParserTest --dependency-verification=strict --no-daemon
.\gradlew.bat :data:sqlite:test --tests runtime.mobileagent.data.KnowledgeRepositoryTest --dependency-verification=strict --no-daemon
```

两者均为 **BUILD SUCCESSFUL**。提交前 `licenseGuard --dependency-verification=strict --no-daemon` **PASS**。CodeGraph `sync`：6 个变更文件。未跑全仓 `check`、设备测试、真实 Vision 或 294 原件。

回归覆盖：quoted-comment + `'`、`C:\notes` 转义、Differences 将 `<414243>` 映为 XYZ、嵌套括号配 JPEG 抽出正文、不完整文字配 JPEG 仍有 PAGE 阻断且不能单靠 JPEG READY、过期 `pdf-text-v7-pdfrenderer` 同 blob 再导入后写入当前 fingerprint。
