<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# bf1fc762 独立复审 P1：Vision 目标冻结与 PDF 十六进制文字

状态：**2026-09-10 JVM 定向修复已落地**。源码提交 `f24f5ae516f2e0a4a20579f76204ae58a78f8f4f`（`codex/user-qa-fixes` 已 push，作者/提交者 `luozhibai <wy3273564266@163.com>`，无 Cursor trailer）。审查仓库固定提交 `bf1fc76280f80ba0c98e3828177bf5ed0b5bf616`。本轮依据用户提供的只读证据包修复两处独立 P1，不重写 Vision 系统，不复跑用户 294 份原件或付费 Provider。HANDOFF/`docs` 仅本地提交。

## 审查指控与结论

| # | 指控 | 结论 | 修复 |
| --- | --- | --- | --- |
| 1 | 多页 Vision 处理期间，后续页可用未经本次同意的新目标；结果也可记到新 fingerprint | **CONFIRMED** | 每个 asset 外发前复核 job 已确认 fingerprint；`VisionInput.modelFingerprint` 与 `vision_results.model_fingerprint` 都使用该值，不再在循环中重读当前 Profile |
| 2 | `extractPdfStrings` 只识别 `(...)` 字面量；`<hex> Tj` 正文可被漏掉，而 `needsVision=false` | **CONFIRMED** | 提取 `Tj`/`TJ`/`'`/`"` 的十六进制操作数；未能覆盖的 text-show 操作数使该页 `needsVision`。parser fingerprint 升为 `pdf-text-v8-pdfrenderer` |

审查包为合成 PDF 与控制流探针，不是真机或付费调用证据。生产路径对应 `PdfParser.parse`、`KnowledgeRepository.processAssets`/`persistVision`。`OpenAiCompatibleVision.process` 仍比较 input 与当前 binding，作为适配器侧二次核对，不是本次同意记录的来源。

## 验证

命令（仓库根目录，`--no-daemon`，exit 0）：

```
.\gradlew.bat :shared:knowledge-api:test --tests runtime.mobileagent.knowledge.DocumentParserTest --dependency-verification=strict --no-daemon --max-workers=4 --console=plain
.\gradlew.bat :data:sqlite:test --tests runtime.mobileagent.data.KnowledgeRepositoryTest --dependency-verification=strict --no-daemon --max-workers=4 --console=plain
```

新增回归：混合字面量/十六进制页可检索且非 Vision；无法解码的 hex show 不把标题当完整文本发布；两页 Vision 在第一页返回前切换 Provider 时，第二页零外发，已写入结果仍为原同意 fingerprint。

CodeGraph `sync`：7 个变更文件。未跑 `licenseGuard check`、设备测试、真实 Vision 或 294 原件。
