<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# PDF 文字快路径专项：用户 174 页样本

## 输入与边界

- 诊断包 `(9)` SHA-256：`884768e8b1c37ab319d4f7f3b974bf4afdc2c55c15d85f25279ed73b78048b5d`。其 APK 构建修订为已合并的 `0950948207a630d2e2cb0efa93be91ab6af227dc`，`dirty=false`。
- 用户给出的 ZIP 共 294 个条目；被逐页复核的 PDF 为 174 页、1,061,024 字节，SHA-256：`5c787ede49f595dd6033f38a137e230d533ef0e29c61cbc8f1092eca7662d867`。证据只记录技术统计，不收录书籍正文或用户绝对路径。
- 诊断日志显示 ZIP 暂存约 7.7 秒，而 3 个并发 PDF 在后续约 13.5 分钟内发生 29 次整页视觉派发；目标 PDF 的前 12 页均为 `image/png` 整页发送。已响应视觉请求的主要耗时是 Provider 响应阶段，非本机 PDF 解析或 ZIP 复制。

## 可证伪基线与结果

| 阶段 | 使用应用 `PdfParser.parse` 的实际样本结果 | 说明 |
| --- | --- | --- |
| 修改前 | 174 页，174 页 `needsVision`，174 个 PAGE 阻断，解析约 1.14 秒 | 原生文字已经提取，但非标准子集字体没有 BaseEncoding，旧实现将该字体整体标为未知；细水平分隔线与两个空压缩内容流也触发视觉。 |
| 字体映射后 | 174 页，36 页 `needsVision`，解析约 0.88 秒 | 对实际使用字节验证 Differences，未映射仍视觉。 |
| 审查前实验版 | 曾降至 4 页，但未达到完整性要求 | 独立审查指出可见批注和短下划线不可遗漏；该实验判定已废弃，未打包交付。 |
| 审查修订后 | 174 页，20 页 `needsVision`，20 个 PAGE 阻断，解析约 1.42 秒 | 第 1、2 页有可见 FreeText 批注，4 页含图，14 页含短水平线；其它 154 页走文字或空白页路径。 |

独立的 `pypdf` 6.19.0 扫描全 174 页约 2.5 秒；它识别出 4 个含图页、30 个只有水平线的文字页和 2 个真正空白页。批注检查另发现两页 FreeText 外观流和多处不可见 Link 批注。对 172 个非空白页，应用提取文字与 `pypdf` 的逐页文字在统一小写、去空白/标点后字符序列完全相同（中位相似度 1.0，最低 1.0）。这验证文字没有因快路径明显丢失；批注内容不属于上述文字层对照，因此保留整页视觉。独立解析器的相同结果不构成每个字形语义的形式证明。

## 变更与回归

- `PdfParser` 指纹升到 `pdf-text-v17-pdfrenderer`，让旧文档重新解析。非标准 Type1 字体无 BaseEncoding 时，只对 Differences 已明确映射的实际显示字节解码；未知映射、畸形/越界 Differences 或未解码的优先 `/ToUnicode` 继续标记为不完整。补充样本用到的常见数字、标点、连字和受限后缀。
- 完整文字、无图与可见批注、且只有页内不超过 1 pt、长度至少页宽 65%、与 Type1 FontDescriptor 字形边界分离的受限细水平分隔线时跳过整页视觉；Type3、无可靠 FontBBox、短下划线、穿字横线、粗线、斜线、填充形状继续需要视觉。
- Flate 解码器允许“零输出且 inflater 已完成”的合法空流；无视觉内容的有效空白页不再要求视觉，失效流继续阻断。整份空白 PDF 拒绝导入，不把 `Page N` 合成标签索引为正文。
- `DocumentParserTest` 增加正反向 fixture；先运行并观察到完整 Differences 和装饰横线的两个正例失败，再完成修复。独立只读审查先指出可见批注等完整性缺口，再发现 Type3 的超大字形可能被细横线穿过。Type3 和 Type1 扩大 FontBBox 两个反例在修前均为红，改用字形边界判断后通过；其余负例覆盖批注、穿字横线、ToUnicode 冲突、畸形 Differences、全空白文档与无效 Flate。最终源码上 `:shared:knowledge-api:test :data:sqlite:test` 通过；临时真实样本探针再次确认 174 页中恰好 20 个视觉页、20 个 PAGE 阻断，已从测试源删除；临时书籍文字对照文件已删除。
- 最终独立只读复审为 `PASS`，未发现新的具体误判路径；`:app-android:reviewGate --offline --no-build-cache --dependency-verification=strict` 592 tasks 成功，产出本地 debug 签名、不可调试的 review 包。该包的原生库、运行时许可资产与 provenance 哈希均在复制后单独核验。

## 接手复查与 v18 扩展（同日）

接手复查发现 v17 只对首个样本有效：对整包 294 个 PDF（11,896 页）运行应用 `PdfParser.parse` 的临时 JVM 探针，v17 仍有 9,437 页需要视觉；同系列 `2+app+book+2` 为 176/179 页，`3+apprentice+bk3` 为 201/203 页。临时插桩按页记录原因后，主要来源为：字体 `/Differences` 出现未收录字形名（如 `acircumflex`、`dollar`）时整个字体未知；`/ToUnicode` 与 `Type0` 字体一律未知；文字提取器未登记 `sc`/`scn` 颜色算子；以及任何矢量路径都触发整页视觉。

| 阶段（整包本地解析） | 需视觉页 | 指定 174 页 PDF |
| --- | --- | --- |
| v17（接手时） | 9,437 | 20 |
| 字形名按字形列表规则解析、未知名称局部化 | 7,465 | — |
| 加 ToUnicode CMap | 6,683 | — |
| 加颜色算子与装饰图形判定、`sh` 计入绘制 | 2,970 | 6 |
| 加零宽 `/BS` Link 批注（最终 v18） | 2,941（2,806 个 PAGE 阻断，1,003 个嵌入图片资产） | 6（4 图、2 FreeText） |

最终剩余视觉页中，1,991 页含图片 XObject（含整页扫描图与多页重复的小图、Form XObject），536 页来自两份 Standard 安全处理器 V4/R4 加密的 PDF（内容流无法读取），其余为非装饰矢量图与 FreeText 批注。解析全部 294 个文件约 97 秒（桌面 JVM），无解析失败。

文字对照：对最终版本所有转为文字路径的非空白页（8,908 页），用 `pypdf` 独立提取并统一 NFKC、小写、仅保留字母数字后比较，8,899 页完全相同，其余 9 页相似度不低于 0.988；检查最低一页，差异仅为希伯来文从右到左的字序。探针源码、逐页文字导出与插桩已删除，证据不含书籍正文。

`DocumentParserTest` 新增字形名、未知字形局部化、ToUnicode、非法映射、颜色算子、装饰图形正反例及 Link `/BS` 正反例，替换原先依赖“字形边界排除穿字横线”的三项测试；新增字形名两项测试先红后绿。`./gradlew.bat :shared:knowledge-api:test :data:sqlite:test --offline --dependency-verification=strict --no-build-cache` 退出 0（218 + 336 项，0 失败）。取舍与接受的风险见 [ADR-0015](../../adr/0015-pdf-text-layer-trust.md)。前一轮构建的 v17 review APK 已被取代。

## 插图单独视觉与重复图片复用（v19，同日）

临时探针以内容哈希统计整包图片：v18 下 2,941 个视觉页全部整页渲染，插图单独路径 0 页生效；893 次图片出现只有 410 种不同内容，重复最多的两张各出现 162 次（跨 162 个课程文件），另有 35 次、32 次和 15 次的重复图。v19 放宽插图路径的几何与装饰条件后，521 页改为只发插图，产生 593 次插图请求，其中 210 种不同内容；按同一进程内字节完全相同才复用计算，视觉请求约由 2,941 降至 2,630（不拆分多图页也只少约 20 次）。第 1 册的 4 个含图页仍为整页视觉。判重仅认发送字节 SHA-256 完全一致，不做近似比较。

`KnowledgeRepositoryTest.onlyByteIdenticalIllustrationsReuseOneVisionResult` 先红后绿（修前同一文档两页同图派发 2 次）。`DocumentPipelineTest.f4` 原夹具两页渲染字节与文字完全相同，按新规则会合理复用，已改为按页不同的渲染字节以保持原测试意图；`DocumentUnitPlannerTest` 的“空批注数组”反例改为无法解析的批注并新增页面旋转反例。`./gradlew.bat :shared:knowledge-api:test :data:sqlite:test --offline --dependency-verification=strict --no-build-cache` 退出 0。探针已删除。详见 [ADR-0016](../../adr/0016-vision-exact-duplicate-reuse.md)。

## 验收限制

本地结果仅证明解析判定及文字一致性。未调用真实收费视觉 Provider，未在物理设备上完成新 APK 的 294 项整包导入，也未宣称整包 K06 总耗时通过。其它 PDF 可包含扫描页、复杂图表和无可验证文字层，它们仍可能受视觉 Provider 延迟约束。
