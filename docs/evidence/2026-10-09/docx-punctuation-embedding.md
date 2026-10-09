<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# DOCX 本地嵌入纯标点准入修复

基线：v1.1.4，`911603e2f863de0f0d7dbd294ecd7e4b0136ed0f`。范围：R07、K02/K05/K06/K08；本地 WordPiece 输入准入。用户原件和诊断只用于本地检查，不作为公开 fixture、测试资产或审阅者输入。

## 根因与修复

DOCX 解析器按段落生成原生文本块。本地 BERT 词表把独立省略号「……」编码为两个 `[UNK]`；原来的全未知正文保护同时拒绝了这种合法标点块，使整个导入成为 FAILED。实际解析器、实际内置词表回放四份原文件：前三份分别有 1/1/2 个失败标点段落，对照没有；总文本块为 69/80/79/76。新诊断确认安装源码为基线正式版，但滚动日志没有保存本地错误正文；根因由原件重放和设备红测确认。

修复只给全部 basic token 均为 BERT 标点的输入放行原有编码；不丢字、不改 WordPiece、不替换模型、不更改空间或旧成功向量。不含可表达内容的词、数字和非标点符号仍被拒绝。字符上限、完整窗口覆盖及 128 窗口预算保持。

## 验证结果

- 最小回归修前 `PunctuationEmbeddingTest.standaloneEllipsisParagraphUsesTheModelUnknownPieces` 失败于 `LOCAL_EMBEDDING_UNSUPPORTED_TEXT`。
- JVM 原件重放通过生产 `OfficeParser`、`TextChunker`、内置词表及生产 tokenizer；修前恰好三份失败，修后四份的全部 304 块通过。
- API36/x86_64 专用模拟器，实际内置 ONNX、SQLite、USearch：修前四原件三份 FAILED/一份 READY；最终原件回放与恢复套件 3/3，四份全部 READY，逐项核对全部 304 个原生段落文本。自造文档验证原文、来源序号、重复导入和重开检索。
- 旧失败状态恢复：只在测试中复刻旧准入拒绝，生成持久化 FAILED 任务；重开生产 Repository 后通过 `resumeImport` 从 CAS 恢复，不重新提供原件，READY、任务 ID、文档 ID 及原文保全，库内仍只有一个文档。
- Tokenizer 回归包含 5 个新用例：纯标点、混合文本、未知词/数字/非标点符号拒绝、字符/128 窗口上限；与既有覆盖及投影用例合计 Debug/Release 各 12/12，通过且无失败/错误/跳过。
- 最终知识库设备回归 16/16（11.943s）：上述 3 项、自造四 DOCX、真实本地模型与索引、PDF/DOCX/EPUB 金样本、重建/重开、API 嵌入授权/维度/目标版本及 UNKNOWN 恢复边界。API 路径只用本机 MockWebServer，不调用收费 Provider。
- `./gradlew check licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification :app-android:assembleDebugAndroidTest --offline --dependency-verification=strict` 成功（6m4s；1053 tasks，87 executed、966 up-to-date；完整缓存/SDK 参数保存在私有收据）。检查当前 313 份 JVM 报告：含变体 2259 次、去重 1644 个用例，0 失败/错误/跳过；部分套件复用有效 up-to-date 结果，不宣称全部强制重跑。
- `reuse lint` 成功，949/949 文件具备许可/版权；`git diff --check` 通过。83 份根工作区原 WIP 逐项 SHA-256 与开工基线一致。
- 独立 DSH / DeepSeek V4.1 Flash 只读复审 APPROVE，核验最小准入改动、词表事实、既有空间兼容及回归边界；复审读取先前 2/2 和 15/15 设备日志及新增恢复测试源码，最终 3/3、16/16 由主 Agent 执行核验。未向审阅者提供私人课程原件、正文或凭据。

## 修复验证结束时的交付边界（后续构建见下节）

修复位于隔离工作树 `C:/Users/32735/.codex/worktrees/adaptive-context-preview/mobileAgentRuntime` 的 `codex/docx-local-embedding-fix` 分支，基线仍为上述 SHA；本轮改动尚未提交、推送、合并或公开发布，也未构建正式签名包。既有失败任务在安装含此修复的版本后可以使用重试恢复；当前用户安装的 v1.1.4 不因本地源码改动而自动更新。未验证用户 realme 实体设备。

私有执行收据：`E:/mobileAgentRuntime/.private/docx-local-embedding-20261009/`，包括 `originals-red.log`/`originals-green.log`、`device-originals-red.log`、`device-final-originals-recovery.log`、`device-final-regression.log`、`full-check.log`、`reuse.log`、`dsh-review.txt`/`.png` 及 `final-audit.json`。隔离工作树未有 CodeGraph 索引；根目录只维护交接及本专项证据副本，未修改其源码或生成索引。原有 WIP 按本轮摘要保护。没有收费 Provider 调用；此证据不能替代用户实体设备验收或已发布版本修复。

## 1.1.5.0preview 构建准备

用户随后授权既有正式身份本地签名构建；隔离树版本设为1.1.5.0preview/code13，RELEASING已同步，Release编译/单元/许可与依赖准入预检通过（267 tasks；1m51s），REUSE949/949。上述预检完成后，用户已明确授权本地提交和远端分支推送，以干净源码继续正式签名构建；preview仅用于测试，禁止公开Release。签名构建与成品核验尚待执行。签名运行器、成品核验和打包脚本已在私有目录准备，凭据只在子进程环境读取。后续具体结果以根交接和成品收据为准。
