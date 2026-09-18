<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-09-18 文档管线复审 R1–R5 收口

## 1. 范围与对象

- 复审输入：`mobile-agent-runtime-aaace272-review-evidence.zip`（sha256 `9d62c0cd6734d7d3956d5b2ba7b2e76c96222e5803d598ecadaec60ed59088ff`，19719 字节），
  结论 `NEEDS_AMEND`，指认 R1（P1）与 R2–R5（P2）。
- 被审提交：`aaace272b16df11a231df7ed61fbe926b2a32b5f`（父 `0f217fe51ffcb82107c0ca669427d1ba9910d0c4`）。
- 修复提交：`c0a2e3ca384088d6af8ad9c4e87b1264d829ec1c`（父 `aaace272`，`2026-09-18 23:33:04 +0800`，作者 hedanbaomi），分支 `codex/user-qa-fixes`。
- 提交范围：6 文件 `+418/-23`，仅源码与测试，未触碰 `HANDOFF.md`/`docs/`。

## 2. R1–R5 修复位置

| 编号 | 修复 | 位置 |
| --- | --- | --- |
| R1 | `requestText` 由 `String = ""` 改为 `String? = null`；`effectiveRequestText()` 改为 `requestText?.let { return it }`，不再用 `ifBlank` 把合法空白回退成整页；只有缺失字段且无正偏移的旧行才回落页面原文，非零偏移按 `coverage.textStart/textEnd` 还原并校验偏移 | `shared/knowledge-api/.../ProcessingUnit.kt`；`data/sqlite/.../KnowledgeRepository.kt:2452/2466/2489/2492/2516` |
| R2 | 新增 `MAX_REQUEST_TEXT_CHARS = 8_500` 本地 UTF-16 上限；`splitTextSlices` 要求 `text.length <= parts * 8500`，用 `long` 运算避免溢出；容量不足抛 `PIPELINE_TEXT_LIMIT_EXCEEDED`，不截断、不放大单片；发送前 `KnowledgeRepository` 另有防御性长度检查并 `failUnit(..., "LOCAL_PREPARE")` | `ProcessingUnit.kt`；`KnowledgeRepository.kt:2452` |
| R3 | `splitTextSlices` 先整体扫描并拒绝未配对代理项（`PIPELINE_INVALID_TEXT`）；切点经 `isCharacterBoundary` 校正到 Unicode scalar 边界（`codePoints <= parts` 分支按 `charCount` 递进）；末尾对每片重新断言长度与边界 | `ProcessingUnit.kt` |
| R4 | 切轴判定改为先看超限维度：`width > 2048 -> false`、`height > 2048 -> true`，表头仅作等维偏好；`middle = start + (end - start) / 2` 并 `require(middle > start && middle < end)`，不可分时抛 `PIPELINE_REGION_LIMIT_EXCEEDED` | `ProcessingUnit.kt` |
| R5 | `selectTarget` 不再回退 `priorUnitState`：`attempt?.string("state")?.takeIf { it != "SUCCEEDED" } ?: "PLANNED"`；成功只能来自 `saved`（当前目标存在有效结果） | `data/sqlite/.../DocumentPipelineStore.kt:37-44` |

保留不变：UNKNOWN 终态、迟到结算拒绝、显式重试授权与既有计费口径均未改动。

## 3. 新增/扩写回归

- `shared/knowledge-api/src/test/.../DocumentUnitPlannerReviewRegressionTest.kt`（133 行，11 条）：空白切片不扩张、显式空/空白经序列化存活、旧 JSON 缺失字段兼容、超额本地失败、恰好容量、逐片 Unicode 编码与 JSON 往返、超小 Unicode 文本多图区、坏代理项拒绝、宽表不产生退化区域、native-only 长文本不继承 Vision 限制、64 轮随机混合文本无损。
- `data/sqlite/src/test/.../DocumentPipelineReviewRegressionTest.kt`（149 行，5 条）：切到新目标不继承成功且保留付费结果、成功 attempt 无当前结果不得标成功、切目标不清除未确认 UNKNOWN、空白切片与本地重建不重复整页、超额页零渲染零派发。
- `app-android/src/test/kotlin/runtime/mobileagent/OpenAiCompatibleVisionTest.kt`（+36 行 1 条）：`reviewSlicesAreBoundedAndUnicodeSafeOnBothWireProtocols` 在 CHAT 与 Responses 两协议的真实出站 payload 上断言每片 ≤8500、拼接等于原文、逐片 UTF-8 往返无损。

## 4. 本轮独立复核（DSH 一审）

在不依赖 Android/Gradle 的独立口径下复核固定提交：

- 编译：从热缓存取 `kotlinc-jvm 2.1.10` + `kotlin-serialization-compiler-plugin-embeddable 2.1.10` + `kotlinx-serialization 1.7.3`，编译 `shared/knowledge-api` + `shared/domain` + `shared/serialization` 三模块 main 源集，424 class，0 error。
- 复现原审查五个反例：**全部不再复现**，23/23 断言 PASS。
  - R1：原反例 `"A" + " "×30000 + "B"` 的 outgoing 长度由 `[7526, 30002, 30002, 7475]` 变为 `[7526, 7501, 7500, 7475]`，全部 ≤8500，存在空白片，拼接等于原文。
  - R2：600,000 字符在规划阶段抛 `PIPELINE_TEXT_LIMIT_EXCEEDED`（不截断、无请求）；`splitTextSlices(8501, 1)` 拒绝；恰好 64×8500 全量字符保全且每片 ≤8500。
  - R3：`"甲"×4000 + "😀" + "乙"×4000` 逐片 UTF-8 往返与拼接均等于原文，无替换字符、无孤立代理项；未配对 `\uD83D` 前置拒绝。
  - R4：宽 4096×高 2000 带表头页面不再抛异常，产出 4 个非退化区域、宽度合规、面积完整覆盖、文本无损；无表头对照组同样可规划。
  - R5：`selectTarget("B")` 状态表达式结果为 `PLANNED`（修前为 `SUCCEEDED`）；该结论为静态/表达式级复核，与提交内 SQLite 用例共同构成语义确认。
- 未覆盖：完整 Gradle/JUnit、Ktor wire、Android 仪器、真实收费 Provider、294 份真实语料；提交正文所述远端验证 run `35362417522`（licenseGuard/Reverse、CI pins、依赖锁定/严格校验、受影响 JVM 套件、REUSE）的结论取自提交说明，本轮未独立重跑。
- 审查意义：本轮验证的是"被指认缺陷不再复现"，不等于"全部功能验收通过"；表内 23 条断言基于复刻原反例的独立测试代码，不计入仓库测试计数。

## 5. 未闭环的设计要求（原审查第 4 节）

1. 8500 现已是明确的本地 UTF-16 字符上限，但 planner 仍不接收目标模型窗口、输出预留或图像预算；本地保护、已知输入余量、未知窗口与输出预算尚未分别建模，也没有不同窗口的真实出站 payload 测试。
2. 文本偏移与均匀图像条带仍按序号配对，无版面坐标证明二者对应；`tableHeader`/`continuation` 保留在单元中但未进入 prompt，existing mock 渲染器返回固定假图像字节、HTTP mock 固定成功，不能证明多栏/宽表/跨条带图文关联正确。

## 6. 文档与 Git 边界

- `HANDOFF.md` 与 `docs/` 仅本地，本证据文件未提交、未推送。
- 未推 main、未合并、未部署、未正式签名、未调用收费服务；未对远端仓库做任何写操作。
- 分支 `codex/user-qa-fixes` 的 `HANDOFF.md` 停在 2026-09-17 且缺少本轮记录；根工作区 `HANDOFF.md` 是 2026-09-14 版本。两者差异较大，同步时以根工作区版本为准，不要把分支旧文档覆盖回来。