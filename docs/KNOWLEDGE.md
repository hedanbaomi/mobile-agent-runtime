<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 知识库、多模态和检索契约

状态：M3 文本路径本地 JVM 已落地（schema v4）。M4 在 schema v5 上增加 PDF 文本、DOCX/EPUB 正文/内嵌图、assets/vision_results、Vision 同意与成功缓存、引用页/图定位；M4R 修复后 schema v6 补 `document_version_id`、完整视觉结果（含 tableMarkdown）和显式 UNKNOWN 重试。M4RR 修复后 schema v7 补 `import_jobs.vision_binding_json`（同意/缓存绑定 Provider+endpoint+revision）。独立图片与 Markdown 外链图在无 Vision 时仍等待，不标 READY。向量空间仍是 `local-hash-v1-d32`（不是 ONNX 模型包、不是 USearch JNI）。K06 仅覆盖检查点续跑，不是 300—500 文件设备负载。对应 R05—R08、K01—K08。

## 1. 数据模型与一致性

下表为逻辑 schema；实现必须将其固化为带版本 SQL迁移及 schema测试，不能直接把表格当作已经建立的数据库。

| 表 | 必要字段/唯一性/关系 |
| --- | --- |
| knowledge_bases | id、name、activeGenerationId、embeddingSpaceId、createdAt、deletedAt |
| blobs | hash、byteLength、mediaType、localRef、refCount；内容寻址，文件名不作为主键 |
| documents | id、kbId、blobHash、displayName、format、sourceMetadata、activeVersionId、deletedAt；同库 blobHash重复导入幂等 |
| document_versions | id、documentId、parserFingerprint、contentHash、status、createdAt；版本不可原地改已发布内容 |
| assets | id、documentVersionId、blobHash、page/section、boundingBox、kind、surroundingTextHash；同图不同位置保留关联 |
| vision_results | cacheKey、assetHash、contextHash、modelFingerprint、promptVersion、outputSchemaVersion、result、status、processedAt；cacheKey唯一 |
| chunks | id、documentVersionId、ordinal、text、sourceSpan、assetIds、contentHash、chunkerFingerprint；版本+ordinal唯一 |
| embedding_spaces | id、provider/model pack指纹、dimension、dtype、normalization、distanceMetric、tokenizer/preprocessing版本 |
| embeddings | chunkId、spaceId、vectorBlob、contentHash；chunkId+spaceId唯一；维度校验；成功向量不可变并可跨索引代际复用 |
| generation_members | generationId+chunkId联合唯一，关联spaceId、documentVersionId和embedding；代际成员关系与向量本体分离 |
| index_generations | id、kbId、spaceId、manifestHash、state、vectorCount、ftsVersion、createdAt；active指针只指向 READY |
| import_jobs | id、kbId、documentId、state、stage、parser/vision/embedding指纹、checkpoint、error、updatedAt |
| import_items | jobId、itemKey、kind、state、attemptCount、resultRef；job+itemKey唯一 |

SQLite是向量与元数据真值，FTS与USearch均为可重建派生数据。Run开始时从activeGenerationId取得已就绪代际并固定引用；本次检索始终使用该代际，切换active不影响已开始Run，但删除/撤权实时生效。所有检索都检查文档未删除、授权仍存在、generation匹配该Run的有效pin。旧代际在使用者释放后才回收，即使旧向量文件尚未清理，也不能返回已删除/未发布内容。

索引重建在新 generation进行。新内容的chunks/embeddings写入staging，未变化内容复用已成功记录并建立generation_members → 构建临时USearch文件 → 校验空间、数量和文件哈希 → 原子替换文件名 → 单事务切activeGenerationId。崩溃留下的孤儿文件按引用回收，不先删除旧索引。FTS候选通过generation_members过滤，不能混用新文本和旧向量；同一向量可以同时被旧/新generation引用，不通过改写向量所属代际破坏旧查询。

整个应用的索引写入由单一队列/锁协调；同库不能并发切换代际。SQLite、文件系统不是一个原子事务，必须用 manifest和恢复流程弥合，不能以“两个写入都调用成功”当作一致性保证。

## 2. 文件导入与状态机

使用 SAF精确选择文件/目录，不要求全盘存储权限。默认流式复制到 App私有 CAS；保留展示名称和来源定位，但不把真实私有路径暴露给 Skill或远程后端。复制/解析前检查空间、大小、媒体类型与文件头；文件损坏或不支持格式产生明确错误。

```text
QUEUED → HASHING → COPYING → PARSING
  无图片 ────────────────────────→ CHUNKING
  有图片 → WAITING_FOR_VISION_MODEL → AWAITING_UPLOAD_CONSENT
                                             ↓
                                     VISION_PROCESSING
                                             ↓
CHUNKING → SELECT_EMBEDDING_BACKEND
  本地模型且可用 ──────────────────────→ EMBEDDING
  用户选择API → AWAITING_EMBEDDING_CONSENT → EMBEDDING
EMBEDDING → INDEXING → READY

可从工作阶段进入：PAUSED / RETRY_WAIT / FAILED / CANCELLED
```

`WAITING_FOR_VISION_MODEL`是可恢复等待，不是导入成功；配置好模型后回到原检查点。纯文本也必须经过Embedding后端选择；API分支在外发前进入`AWAITING_EMBEDDING_CONSENT`，未授权只暂停，不发请求。有效授权可跳过重复弹窗但不能跳过本地校验；绑定Provider、规范化目的域名、模型ID/版本、数据类型/范围和用途，任一变化须重新确认。用户拒绝时保留检查点，不能自动换Provider；本地模型失败不能自动回退API。视觉同意不自动授权把全部文本发送给Embedding。

首版默认整份文档原子就绪：存在待视觉处理项时，不把该文档标为 READY。其他已就绪文档仍可查询。若日后支持局部可用，必须有独立 `PARTIAL` 状态、醒目覆盖范围和验收，不能伪装完整成功。

TXT/Markdown、PDF、DOCX、EPUB、常见图片分阶段实现，但最终首版范围不能仅用纯文本替代。DOCX/EPUB为不可信归档，不执行宏/脚本/外部资源，不自动访问包内URL。限制解压文件数、总大小、膨胀比和路径；拒绝 Zip Slip、链接和压缩炸弹。

## 3. 图片与页面的完整性

PDF同时提取文本、图片和页面结构，并保留可渲染页。流程图/公式可能是矢量绘制，仅扫描嵌入图片不足以证明“无视觉内容”。首版可保守地将有图形/布局不确定的页面送视觉处理；不能证明可跳过的页面应进入待处理而不是静默过滤。用户可看到页/图数量、计划发送内容和费用估计，取消不会自动降级。

DOCX/EPUB关联图片与所在段落/章节；独立图片保留原始像素内容。可缩放/压缩的处理副本与原图分别哈希，记录转换参数；模型能力不支持时提示，不能以缩略图代替完整证据却不说明。

视觉结果至少包含 assetId、sourceDocumentId、page/section、type、ocrText、semanticDescription、entities、relationships、tableMarkdown、surroundingText和provenance。模型自报 confidence只是未校准元数据，不得作为“已经准确识别”的保证。

缓存键包括：图片/页面处理副本 hash + 周边文本 hash + Provider/模型指纹 + Vision Prompt版本 + 输出schema版本 + 预处理版本。只有所有维度一致才复用；相同图片但周围语境不同不能直接拿旧解释替代。不同文档可以共享结果缓存，但保留各自引用坐标。

已持久化成功结果不重发。网络超时不能证明模型未处理或未收费；将该项标为 `UNKNOWN_OUTCOME`，展示可能重复收费的风险，由用户选择重试。避免把“断点续传”宣传成云端恰好一次计费。

## 4. Embedding空间与模型包

默认本地 ONNX Embedding，可选用户 API。两者使用同一 EmbeddingPort，但不能混合向量空间。Model Pack含 manifest、权重、Tokenizer、预处理、池化、归一化、维度、距离度量、许可、来源和SHA-256；加载前校验，文件损坏或算子不支持时拒绝，不自动下载其他模型替代。

`spaceId` 对完整指纹取稳定哈希。查询必须使用目标知识库 spaceId对应的模型生成 query embedding，不能使用当前聊天模型猜测向量。模型/Tokenizer/维度/池化变化创建新代际，后台重建后切换，不原地覆盖。

一个 Agent绑定不同向量空间的多个 KB时，按spaceId分组分别生成查询向量、检索，再用排名融合；不能直接比较不同空间的原始cosine分数。对应模型不可用时报告哪些 KB不可检索，让用户选择修复或显式排除，不静默遗漏资料。

首轮建议 chunk 目标512 token、重叠64、topK=8、FTS/向量各取40候选、RRF k=60；这些是工程初值，需在中英文/代码/表格语料评测。不把字符数冒充准确token；保留标题层级，表格或公式可作为独立块，超长块分片仍能定位原文。

FTS5能力不等于中文分词质量。必须建立中文专名、英文术语、混合代码的词法召回集，验证tokenizer方案；若调整预分词/索引字段，记录版本并重建FTS。不能只测英文短句宣称中文混合检索通过。

## 5. 查询、预算和引用

查询顺序：验证Agent授权与KB状态 → 按space生成query向量 → FTS5与USearch候选 → metadata和有效代际过滤 → 去重 → RRF → 可选重排 → 扩展父/邻块/图片 → 预算截取 → CitationMap。

过滤条件采用结构化字段，不拼接用户SQL；授权过滤在候选阶段和返回阶段都执行。无命中返回空证据，回答不得捏造引用。

每条citation绑定：runId、citationId、kbId、document/version、chunkId、assetId、页/章/span、parser/vision指纹。模型只能引用本次CitationMap里的ID；未知ID显示为无效引用，不生成假链接。引用点击打开原文页或原图；资源已删除显示“来源已移除”，不能跳到另一个文件。

严格模式：绑定含图知识库时Chat Model必须支持image input，并在视觉命中时发送预算内的原图或可追溯处理副本。用户主动开启文本降级模式后才只传预生成描述；每个相关回答清楚提示“未提供原始图片，视觉证据可能不完整”。不得自动开降级。

预算覆盖文字、历史、工具schema、图片和输出预留；若无法容纳关键图/块，要求减少范围或分批提问。不得把删除所有图当作成功处理预算。

## 6. 长任务、恢复和删除

用户主动导入由前台可见任务执行，持久化检查点。WorkManager负责失败恢复/补偿/清理，不能依赖Activity常驻或无限后台执行。必须处理用户取消、进程死亡、系统重启、空间不足、网络变化、服务时限和电量/温控约束。

每份文档、每张图片、每批embedding独立记录状态；重启恢复检查文件hash、schema和处理指纹，已成功步骤不重复。导入重试不重复建Document或引用；取消保留可恢复部分并向用户说明磁盘占用，彻底删除需确认。

删除KB/document先撤销可检索性、事务更新引用，再异步清理索引和无引用CAS。两个知识库共用blob时删除一个不得损坏另一个。所有路径由内部ID解析，禁止用用户文件名直接拼目录。

## 7. 可启动任务与证据

先实现纯接口与小型fixture → CAS/SQLite迁移 → TXT/MD解析和FTS → embedding/USearch代际 → 恢复与删除 → PDF文本 → 视觉页/图片 → DOCX/EPUB → 真机长任务。每步均有K系列验收；视觉spike可以提前，但不能据此标记全格式支持。

测试语料必须是可合法提交的自造/开放许可fixture，包含扫描PDF、矢量图、坏PDF、DOCX图片、EPUB图、中文专名和两个KB复用blob。不使用用户私有知识库充当公开测试资产。

## 8. M3 本地验证（2026-08-28）

本轮实现：多知识库 CAS 引用计数、同库同 blob 幂等、`document_versions`/`index_generations`/`generation_members`、CJK 单字/双字 FTS、SQLite 内余弦检索 + RRF、删除后重建、COPYING 检查点续跑、CitationMap、6000 字符预算裁剪。Chat 用 `retrieve` + citation id；知识库页有 Rebuild index。向量写入 SQLite，**没有** ONNX pack，**没有** USearch 文件切换。

命令（均本机，`--no-daemon`）：

```bash
.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :data:sqlite:test :app-android:assembleDebug
python -B -m reuse lint
```

结果：Gradle BUILD SUCCESSFUL；REUSE 182/182 退出 0。

| 验收 | 本轮状态 | 证据边界 |
| --- | --- | --- |
| K01 | LOCAL_PASS | 同库幂等 refCount=1；两库共享 blob 删一库后另一库可检索 |
| K02 | LOCAL_PASS（文本/失败证据） | TXT/MD READY；PDF FAILED 含原因；合法 EPUB/DOCX ZIP 内存检查后 FAILED；zip-slip 拒绝；图与 MD 图等待。未解析 PDF/DOCX/EPUB 正文 |
| K05 | LOCAL_PASS | 「张伟」/USearch、代码与表格 token、异 space 警告且不混算。召回是 hashing 空间，不是模型包 |
| K06 | 检查点 LOCAL_PASS；非 DEVICE_PASS | `pauseAt=COPYING` 后续跑不重复 blob。未跑 300—500 文件/真机杀进程矩阵 |
| K07 | LOCAL_PASS | 删除文档后新代际不含该文档；旧 READY 代际行保留；BUILDING 代际在未切 active 前不用于检索 |
| K08 | LOCAL_PASS（文本） | 未知 citation id 不解析；空查询无命中；预算裁剪。原图回跳属 M4 |

未执行：模拟器/真机 FTS5 冷启动、ONNX 加载、USearch x86_64 JNI、K06 设备负载、独立安全审阅。

## 9. KAR01—KAR08 本地修复（2026-08-28）

- v3 READY 文档在 `Migrations.apply` 后回填 `document_versions` 并 `repairIndexes` 重建代际/FTS/向量。
- 先 staging chunks/embeddings/代际，事务成功后再把文档标 READY；embed 失败保持 FAILED，同 blob 重试可修复。
- `resumeImport` 从 CAS 校验 hash；错误 bytes 与已删文档/知识库拒绝；删除会取消未完成任务。
- blob `ref_count` 按仍存活文档计数，重复删除与失败重试不累计。
- 多库检索先合并词法/向量再全局 RRF。
- 一次 retrieve 固定 READY generation pin；rebuild 重写 FTS 并校验向量长度，失败不切 active。
- Chat 在检索异常时停止发送并提示，不把异常留在协程外。

## 10. M4 本地验证（2026-08-28）

本轮：保守 PDF 文本提取（含 JPEG XObject 检测）；DOCX/EPUB 在 ZipSafety 之后解析正文与内嵌图；Vision 缺模型等待、未同意不外发、成功结果按 cacheKey 不重发、UNKNOWN_OUTCOME 不自动重试；Chat 严格模式拒绝无图能力模型，显式文本降级才继续并提示原图未发送；citation 可定位页/图，已删来源显示 Source removed。未实现 PDF 页光栅化、ONNX pack、设备 K06 负载。

命令：`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :data:sqlite:test :app-android:assembleDebug --no-daemon`；`python -B -m reuse lint`。

| 验收 | 本轮状态 | 证据边界 |
| --- | --- | --- |
| K02 | LOCAL_PASS（PDF/DOCX/EPUB 文本路径） | 文本 PDF READY 可检索；残缺 EPUB/坏 PDF FAILED 含原因；zip-slip 仍拒绝 |
| K03 | LOCAL_PASS（JVM） | 无 Vision 等待且非 READY；未同意 0 次 Vision 调用；API embedding 未同意不建索引 |
| K04 | LOCAL_PASS（策略/定位） | 严格模式拒绝；文本降级警告；citation 含 page/asset。未跑真机原图查看器 |
| K06 | 检查点 LOCAL_PASS；非 DEVICE_PASS | 未跑 300—500 文件/真机杀进程矩阵 |
| K08 | LOCAL_PASS（定位） | 未知 citation 与已删来源不生成假链接。未做过大原图设备预算 |

## 11. M4R01—M4R07 本地修复（2026-08-28）

对照交接审查逐条核实后修复，未通过放宽视觉要求消掉报错。

- App 绑定 `OpenAiCompatibleVision`：有 image 能力的 Provider/模型/secret 才会真正调用；测试 fake backend 不能代替该接线。
- PDF 识别 `BT…ET` 之外的绘图指令；needsVision 且没有可处理光栅图时等待或失败，不用合成 `Page N:` 发布 READY。按 Kids/内容流映射页码，图片 `page` 来自页面 XObject，不再按字符数切页。
- DOCX `r:link`/`TargetMode=External` 与 EPUB 外链/缺失图记为 EXTERNAL/MISSING，零自动外联，文档不 READY。
- 严格模式在 image 能力下发送预算内 data-URI 原图；无法附带则阻止。文本降级警告写进回答正文，完成时不覆盖。
- `locateCitation` 校验 document/version/chunk/asset；图引用返回 asset hash。伪造 citation 为 removed。
- Vision 缓存写入 `table_markdown`/`result_type` 并建块；UNKNOWN_OUTCOME 仍不自动重放，提供 `retryUnknownVision(..., acknowledgeDuplicateCharge=true)` 与知识库页按钮。

PDF 页光栅化、ONNX、设备原图查看器仍未做。独立复审前不把 K02—K04/K08 升为阶段验收通过。

## 12. M4RR01—M4RR04 本地修复（2026-08-28）

对照第二次审查核实后修复，未通过放宽视觉完整性消掉报错。

- PDF 内容流 `BI/ID/EI` inline image 计入 needsVision；可提取则保存为 IMAGE，否则等待/失败，不再把带图页标 READY。
- 严格模式比较命中图与可附图集合；超限、缺失 CAS、超过 4 张均阻止，除非用户显式文本降级并在回答中保留警告。
- Vision 同意与缓存绑定 `providerId|modelId|endpoint|revision`。换 Provider/域名/版本后零外发直至重确认；同 modelId 跨 Provider 不再共用缓存。schema v7 增加 `import_jobs.vision_binding_json`。
- EPUB 按章节目录解析相对 `src`，同名文件不再错章绑定。
