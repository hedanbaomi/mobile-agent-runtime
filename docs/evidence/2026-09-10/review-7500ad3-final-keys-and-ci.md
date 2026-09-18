<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 7500ad3 终轮复审：字典键名称解码与 CI 内存门禁

状态：**2026-09-10 JVM 定向修复已落地**。审查包 `mobile-agent-runtime-7500ad3-final-review-evidence.zip`，审查提交 `7500ad342fde1d04c52a13813d2aad03ee3f5610`。审查方为方法级只读 harness，非 Android/Gradle 运行。本轮处理两项：主 CI 失败（验收阻断）与字典键未解码（P1）。审查方明确未建立 CI 失败根因，本轮据实际 job 日志补齐。

## 1. 验收阻断：主 CI 失败

`ci` run `34494819391`（`7500ad3`）**failure**：`check` job `102930452830` 的步骤 `Review-like non-debuggable APK/SBOM/provenance gate` 失败。同 job 的 licenseGuard、REUSE、CI pins/依赖锁、workflow YAML、Unit tests + debug APK/SBOM 均成功；`android-smoke` API 31/34/35/36 与 `convergence` API36 均成功；`Signed release gate` 按预期 skipped。

**实际根因（据 job 日志）**：`java.lang.OutOfMemoryError: Java heap space`，发生在 D8 合并外部依赖 dex：

```
> Task :app-android:collectReviewDependencies FAILED
> Task :app-android:mergeExtDexReview FAILED
ERROR: D8: java.lang.OutOfMemoryError: Java heap space
com.android.builder.dexing.DexArchiveMergerException: Error while merging dex archives
BUILD FAILED in 3m 57s
```

该 gate 在同一个 daemon 内要同时完成 `check`、review 变体（non-debuggable）APK 组装、R8/D8 dex 合并与 CycloneDX SBOM/provenance 生成；`-Xmx2g` 处于堆边界，`903c33e` 同配置曾通过，属边界性失败。**不是** PDF 解析、许可或 native 生命周期问题。

**修复**：`gradle.properties` 的 `org.gradle.jvmargs` 由 `-Xmx2g` 提升为 `-Xmx4g`，并写明原因与 run 号。门禁步骤本身一条未减、未跳过、未放宽；只移动 daemon 堆上限，使本地文档中记载的 `.\gradlew.bat :app-android:reviewGate` 命令在默认配置下同样可完成。

本机未执行 Android `reviewGate`（需完整 SDK/签名与较长时间），故**该修复未经本地复现验证**，须由 CI 重跑确认；本地只验证了 JVM 侧测试与许可门禁不受影响。

## 2. P1：字典键未做名称解码

`7500ad3` 已解码名称**值**，但键查找仍用字面正则匹配原始拼写，因此等价转义键被判为「缺省」并静默回退到更弱的默认：

- `namedDictionaryOrReference` / `dictionaryOrReference` / `arrayBody`：`/Enc#6Fding`、`/Diff#65rences` 找不到 → 编码字典的 `/Differences` 被丢弃 → 复用 StandardEncoding 发布 `ABC`，且 `complete=true`、`needsVision=false`。
- `streamFilters`：`/Fil#74er` 找不到 → Flate 内容流被当作未压缩字节，压缩数据会作为「完整文本」入索引。

**修复**：新增 `findPdfKeyEnd`（按 PDF 32000-1 7.3.5 解码键名后比较；跳过注释与字面量串，避免字符串内的 `/Name` 被误认为键）与 `skipLiteralString`，四处键查找统一改用；`streamFilters` 重写为「先定位键、再解析单值或数组形式的过滤器名」，过滤器名本身也解码。fingerprint 升为 `pdf-text-v13-pdfrenderer`。

## 3. 验证

干净构建（先 `clean`，并 `--no-build-cache`，避免下述陈旧内联问题）：

```
:shared:knowledge-api:clean :data:sqlite:clean
:shared:knowledge-api:test :data:sqlite:test licenseGuard --dependency-verification=strict --no-daemon --no-build-cache
```

**BUILD SUCCESSFUL**。`DocumentParserTest` **54/54**（本轮新增 2 例）；`:shared:knowledge-api:test` **97/97**、`:data:sqlite:test` **197/197**，合计 **294 tests、0 failure/error/skipped**；`licenseGuard` **PASS**。

新增回归：

- `escapedEncodingAndDifferencesKeysDecodeLikeTheirLiteralSpelling`：`/Encoding`+`/Differences`、仅转义 `/Enc#6Fding`、仅转义 `/Diff#65rences`、两者皆转义四种写法必须得到同一正文 `XYZ`（不得出现 `ABC`）。
- `escapedFilterKeyStillAppliesFlateDecode`：`/Fil#74er` 与 `/Filter` 必须解出相同正文（否则压缩流会以乱码形式被判为完整文本）。
- 配套夹具 `writeDifferencesWithKeySpellingsPdf`、`writeFlateTextPdf`（`assemblePages` 增加 `contentDictSuffix`/`deflateContent`）。

生产 `PdfParser.parse` 直接读取**审查包自带 PDF**（最强证据，审查方用 pypdf/PyMuPDF 给参考值）：

```
plain_encoding_key.pdf       text={KEEPTOKEN XYZ} needsVision=false
escaped_encoding_key.pdf     text={KEEPTOKEN XYZ} needsVision=false   ← 反例，已修复
escaped_differences_key.pdf  text={KEEPTOKEN XYZ} needsVision=false   ← 反例，已修复
plain_symbol.pdf             text={KEEPTOKEN αβγ} needsVision=false
escaped_symbol_value.pdf     text={KEEPTOKEN αβγ} needsVision=false
```

均与审查包 `reference-results.json` 的 pypdf/PyMuPDF 参考一致。

## 4. 一条本地假失败（供后续复现者参考）

首次本地全量回归出现 `staleParserFingerprintReimportsTheSameBlob` 失败，报 `[pdf-text-v7-pdfrenderer, pdf-text-v13-pdfrenderer] ... expected true`。查明为**构建缓存导致的编译陈旧**，不是产品缺陷：`PdfParser.FINGERPRINT` 是 `const val`，会在调用点内联；本机在多次改值与构建缓存复用后，`KnowledgeRepositoryTest.class` 仍内联旧值 `pdf-text-v12-pdfrenderer`，而运行时 parser 已是 `v13`，于是测试比对旧常量。删除测试类仍复现（Kotlin 增量状态保留），最终以 `clean` + `--no-build-cache` 复跑通过，编译产物确认内联 `v13`。CI 是全量新构建，不受此影响。**教训**：fingerprint 常量变更后本地复现应加 `--no-build-cache` 或先 `clean`。

## 5. 未建立

- 本机未运行 Android `reviewGate`；CI 内存修复需 CI 重跑确认，本轮不据此宣称门禁已通过。
- 未跑全仓 `check`、设备测试、真实 Vision 或用户 294 原件。
- 字典键之外的其余字面正则（如 `/Subtype`、`/Filter` 数组内部、`/Length`）仍按原始拼写匹配；其失败方向是 fail closed 或退回保守判定，未发现与本次反例同类。本轮不承诺全部 PDF 名称写法。
- 本轮修复尚未经新一轮独立只读审阅。