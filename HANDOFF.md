<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-08-28T21:00:26+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

**接手者必须先读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。工作后必须维护本文件及受影响的专题文档。**

## 1. 当前事实

| 项目 | 状态 |
| --- | --- |
| 产品 | M1 部分实现；AR01—AR10 历史修复保留。M0.5 UAR01—UAR05 已修复（本地提交 `9dc7560`）。M2 NAR01—NAR07 与 M3 KAR01—KAR08 已在本轮修复。完整 MVP 未完成 |
| 业务源码/构建 | 本轮 `licenseGuard`/`licenseGuardReverse`、`:shared:knowledge-api:test`、`:shared:announcements:test`、`:data:sqlite:test`、`:app-android:assembleDebug` 通过；`node src/worker.test.mjs` 通过；REUSE 182/182 |
| Git | 分支 `main`；本轮修复提交 `6ab2dc21ab955bf0ab317e0d4076db2805a0c749`，与未推送的 M0.5 `9dc7560` 一并 push。作者 `luozhibai`，无 Cursor trailer |
| CodeGraph | 仓库无 `.codegraph/` 工作副本（被 gitignore）；本轮用直接读文件定位。未重建索引 |
| 许可 | `python -B -m reuse lint` 退出 0（182/182）；未改 LICENSE 正文 |
| 授权范围 | 用户已授权修复交接中的 M2/M3 问题并 commit/push，使 origin 与本地相同。仍不授权 Cloudflare 生产部署 |

## 2. 当前任务

无进行中认领。NAR01—NAR07 与 KAR01—KAR08 已修复。下一步默认 M4，不要把 hashing 空间写成 ONNX。

## 3. 关键约束

- 第一方 `AGPL-3.0-only`。applicationId 暂为 `runtime.mobileagent`。CODEOWNERS 为 `@hedanbaomi`。
- Python 不得在主进程执行；isolated service 已声明，CPython 包未嵌入。
- 含图知识库无 Vision 时等待。TXT/MD 可词法 READY；向量空间是 `local-hash-v1-d32`，无 ONNX 模型包。USearch JNI 未构建。
- 不部署 Cloudflare 生产资源。提交作者使用仓库 Git 用户 `luozhibai`，不含 Cursor。Cursor 环境会劫持 `git commit`；需用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`。

## 4. 接手顺序

1. `git status --short --branch`、`git log -1 --format=full`；确认 HEAD 无 `Co-authored-by: Cursor`
2. 提交作者使用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`，作者 `luozhibai <wy3273564266@163.com>`
3. M0.5 UAR 与 M2 NAR01—NAR07、M3 KAR01—KAR08 已按交接修复；下一步默认 M4，不要把 `local-hash-v1-d32` 写成 ONNX pack
4. 设备/模拟器仍缺：bundled FTS5 冷启动、公告本地 Worker→真机拉取、K06 300—500 文件负载
5. `local-hash-v1-d32` 不是 ONNX pack；本轮没有改变 M4 的既定范围（Vision/PDF 正文/DOCX-EPUB）
6. M1 Compose 仍须按 M0.5 差异清单对齐；不要把 M2/M3 功能闭环冒称为全套 UI 设计实现或生产部署

## 5. 未决事项

Play 生产包名、品牌、Cloudflare 账户/域名/Access、生产签名密钥、Embedding ONNX 模型包、NDK/USearch x86_64、CPython 3.14.x 包哈希。GitHub Ruleset 未验证。未跑模拟器/真机公告拉取。公告 Compose 未按 M0.5 全部视觉标注对齐。历史 AR 修复不等于完整安全验收或发布许可。

## 6. 工作记录

### 2026-08-28：初始文档交接任务

见上一版记录：文档与本地 Git/CodeGraph 初始化，无业务工程。

### 2026-08-28：产品实现（M0 本地 + 共享契约）

- 需求：R16、R01、R02、R13 部分；验收 L01 本地正反向、A02 共享无 Android 类型。
- 远程：用户确认 `hedanbaomi/mobile-agent-runtime`。
- 验证：当时 licenseGuard、JVM 测试、assembleDebug、reuse lint 通过。Maven Central TLS 失败，依赖走阿里云。
- Git：实现提交 `aa2fa1ec`（无 Cursor）。

### 2026-08-28：首次 push

- `git push -u origin main` 成功。后续交接提交 `ed3efbb` 已推送。

### 2026-08-28：M1 Chat 流式 / 密钥 / SAF 导入

- 需求：R02、R06、R12；验收目标 A03/A04/K03 本地部分，非 DEVICE_PASS。
- 行为：Providers 将 API key 经 Android Keystore AES-GCM 写入 `secrets` 密文，库中只有 `secretRef`。Chat 使用 `OpenAiCompatibleAdapter` SSE 流式，可取消，错误走 SecretRedactor。知识库系统选择器导入；图片停在 `WAITING_FOR_VISION_MODEL`；TXT/MD 写入 FTS；PDF/Office 失败说明原因，文件仍复制到 CAS。
- 主要路径：`ChatViewModel`/`ProvidersViewModel`/`KnowledgeViewModel`、`KnowledgeRepository`、`CasBlobSink`、`OpenAiSse`、`AndroidSecretStore`、schema v2 `import_jobs`。
- 验证：`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :shared:provider-api:test :data:sqlite:test :shared:agent-runtime:test :app-android:assembleDebug` → BUILD SUCCESSFUL。未跑真机、未跑付费模型、未跑 `reuse lint` 本轮、未独立审阅。
- Git：用户授权 `commit并push`。已写入并推送 `7a71c26e2dcc8169bb50636e8391dc4c18881577`。作者 `luozhibai`，无 Cursor。
- 文档：`docs/KNOWLEDGE.md`、`docs/IMPLEMENTATION_PLAN.md`、本交接。
- 下一步：补 Agent 快照、PDF/视觉、公告客户端、Ruleset。设备验证仍缺。

### 2026-08-28T17:56:00+08:00：当前 M0/M1 实现审查

- 请求：审查当前工作。范围：固定 HEAD `315bb4c9be1672addcca3b00f1c155a335c1bac2` 的当前树，重点审查 M1 提交 `7a71c26`，兼查 M0 存储/构建/许可防线。覆盖 R01/R02/R05/R06/R12/R16，验收关联 A01/A03/A04、K01/K03/K06/K08、S10、L 系列。
- 结论：`NEEDS_AMEND`。下列问题是当前实现路径缺陷；未将已明确未实现的 ONNX/USearch、CPython、公告部署等直接列为缺陷。
- 分工：主审检查运行时、存储、密钥、知识导入；一个只读子审查检查 Gradle、许可扫描、反向测试、REUSE 和 CI。主审复核并收敛子审查发现；原始“仅追加 MIT annotation”示例会触发现有全局 MIT 拦截，已弃用，AR09 采用 Apache 示例。
- 修改范围：仅 `HANDOFF.md`。无代码修复、无 Gradle/安装命令，不读写应用数据库、不写配置、不读取真实秘密，无提交推送或部署；内存 fixture 全为自造数据。

#### 待修问题

| ID / 严重性 | 位置与触发条件 | 影响及修复方向 |
| --- | --- | --- |
| AR01 / P1 | [BundledSqliteConnection.kt](platform/android/storage/src/main/kotlin/runtime/mobileagent/storage/BundledSqliteConnection.kt) 第18行实际创建 `FrameworkSQLiteOpenHelperFactory`；[Migrations.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/Migrations.kt) 第22行无条件创建 FTS5；[MobileAgentApp.kt](app-android/src/main/kotlin/runtime/mobileagent/MobileAgentApp.kt) 第27行在启动中调用迁移 | Android 系统 SQLite 未启用 FTS5，标准 Android 构建会在启动迁移处因 `no such module: fts5` 抛异常。改用方案约定的 bundled 驱动并验证 APK 内实际能力；增加 Android 冷启动/迁移测试。依据为调用链与 Android 官方源码说明，未在设备上观察崩溃 |
| AR02 / P1 | [ChatViewModel.kt](app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt) 第38—39行；[Repositories.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/Repositories.kt) 第32—33、63—85行 | Provider 按名称取第一个，Chat 模型按全局 modelId 排序取第一个，没有使用 `model.providerId`。两个 Provider 即可把 B 的模型交给 A 的 endpoint/key，请求失败或流向不匹配的服务。按所选模型解析唯一绑定 Provider，加入双 Provider 逆序 fixture |
| AR03 / P1 | [OpenAiSse.kt](shared/provider-api/src/main/kotlin/runtime/mobileagent/provider/openai/OpenAiSse.kt) 第26—27行；[OpenAiCompatibleAdapter.kt](shared/provider-api/src/main/kotlin/runtime/mobileagent/provider/openai/OpenAiCompatibleAdapter.kt) 第97—99行；[ChatViewModel.kt](app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt) 第86—88行 | HTTP 200 的 SSE `error.message` 原文变成 `Failed.sanitizedMessage`，未用实际 token 脱敏；UI status 直接显示，聊天正文的通用正则也漏掉任意格式 token，并会进入后续历史。须在 adapter 的统一错误出口按本次秘密集合脱敏，并保证 status/正文不接收原文 |
| AR04 / P2 | [OpenAiCompatibleAdapter.kt](shared/provider-api/src/main/kotlin/runtime/mobileagent/provider/openai/OpenAiCompatibleAdapter.kt) 第103行 | EOF 缺少协议完成信号仍补发 `Completed`；错误帧后也补成功，覆盖 Runtime/UI 的失败状态。无 `[DONE]` 的半截回答、空 HTTP 200 都被报成功。应验证终止条件，异常/错误为终态，不补发成功 |
| AR05 / P2 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第53、62—70行；[MediaKind.kt](shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/MediaKind.kt) 第44行 | 只有独立 IMAGE 格式被认作有图，含 `![...](photo.png)` 的 Markdown 得到 `hasImages=false` 并直接 READY，绕过视觉等待且无缺图提示。检测 Markdown 图片引用并进入明确等待/不支持状态；不可为修复此项自动外联下载图片 |
| AR06 / P2 | [CasBlobSink.kt](platform/android/storage/src/main/kotlin/runtime/mobileagent/storage/CasBlobSink.kt) 第16—20行 | 直接写最终 hash 路径，进程退出/磁盘满后可留下半文件；重试只看 exists 就复用并返回完整长度/哈希，原件损坏不能恢复。应先写临时文件，校验/原子落盘，并检查已有 blob 的长度与哈希。静态故障路径分析，本轮未注入磁盘故障 |
| AR07 / P2 | [TextChunker.kt](shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/TextChunker.kt) 第19—28行 | 只在段落间切块，不切超长单段。目标1800字符时，100000字符单段仍只有1块；Chat 又直接拼接命中正文，topK 不能限制请求大小。拆分超长段落并在请求组装时做实际上下文预算 |
| AR08 / P2 | [REUSE.toml](REUSE.toml) 的文档归属；[docs/KNOWLEDGE.md](docs/KNOWLEDGE.md) 第1—2行；[ci.yml](.github/workflows/ci.yml) 第27—29行与 [license-guard.yml](.github/workflows/license-guard.yml) 第27—29行 | REUSE 6.2.0 实际 lint 退出1，虽有 SPDX 头仍报告该中文文档缺少版权/许可证，当前本地许可验收不通过。补可靠的文件归属/修复解析环境并固定工具依赖；未查询或执行 GitHub runner，不能声称远程 CI 已失败 |
| AR09 / P2 | [LicenseScanner.kt](build-logic/license-guard/src/main/kotlin/runtime/mobileagent/license/LicenseScanner.kt) 第65—70、106—130行 | 从任意 Apache/MIT/BSD annotation 推导第三方豁免，无第一方路径保护。内存 fixture 使用 Apache、`path=["shared/domain","shared/domain/**"]` 可让扫描器跳过第一方树且不触发 MIT 拦截。须结构化解析并限定第三方允许清单；本轮只验证扫描逻辑，未改文件运行整套 gate/CI |
| AR10 / P2 | [LicenseScanner.kt](build-logic/license-guard/src/main/kotlin/runtime/mobileagent/license/LicenseScanner.kt) 第94—100行 | 只判断 header 是否包含 AGPL-only 字串，`AGPL-3.0-only OR Apache-2.0` 仍命中通过分支，违背第一方单一许可约束。解析完整 SPDX expression，必须严格等于约定单一标识，并增加反向 fixture |

AR01 的平台依据：[Android 官方源码变更记录](https://android.googlesource.com/superprojects/androidx/%2B/11c218b86ae8a0b7ce2dc238a960ab288b3597d6%5E%21/) 说明 FTS5 需要 bundled SQLite，因为 Android 系统构建未启用它。编译/JDBC 单测不能覆盖此 Android 驱动差异。

#### 实际验证与边界

- 仓库只读核验：`git rev-parse --show-toplevel`、`git rev-parse HEAD`、`git status --porcelain=v1`；根目录与基线一致，写交接前工作区干净。优先 CodeGraph 定位代码，再读取对应文件；没有同步/重建图索引。
- 许可：`python -B -m reuse --version` → 6.2.0；`python -B -m reuse lint` → exit 1，`docs\\KNOWLEDGE.md` 缺少识别到的版权/许可，138/139文件识别成功。没有据此声称文档本来没有 SPDX 头。
- Provider：Python 3.11 内存 SQLite 执行仓库同样的两条排序查询；fixture 为 Provider `Alpha endpoint`/`Zulu endpoint`，分别绑定 `zeta-chat`/`alpha-chat`；输出 `provider_model_match=False`。不打开应用数据库。
- JVM 行为：复用已有 `provider-api.jar`、`knowledge-api.jar`、Kotlin 2.1.10、serialization 1.7.3、Ktor 3.0.1 产物，在 Java 21.0.2 JShell 内存执行；同时用 `--class-path` 和 `-J--class-path` 指定依赖，`--execution local`，不运行 Gradle、不产生仓库测试文件。此验证不是重新编译当前源码后的测试。
- SSE/脱敏：用 `OpenAiSse.eventsFromLine` 输入错误文本 `Invalid credential: synthetic-provider-token-12345`，随后用通用 `SecretRedactor.redact`，两处输出仍含完整自造 token。
- 流式：真实 `OpenAiCompatibleAdapter.stream` 接 Ktor MockEngine（请求不出网）再 `FlowKt.toList`：无 `[DONE]` → `[TextDelta(text=partial), Completed]`；错误帧 → `[Failed(sanitizedMessage=fixture rejected), Completed]`；空 HTTP 200 → `[Completed]`。
- 分块/识别：`TextChunker.chunk("x".repeat(100000),1800,200)` → `CHUNK_COUNT=1 MAX_CHARS=100000`；含图片引用的 `recipe.md` → `MARKDOWN_FORMAT=MARKDOWN HAS_IMAGES=false`。
- 许可扫描：Python 内存复写对应字符串/路径判断，Apache fixture 输出 `THIRD_PARTY_PREFIX_ADDED=True`、`FIRST_PARTY_FILE_SKIPPED=True`、`MIT_REUSE_CHECK_BLOCKS_APACHE_FIXTURE=False`；额外 OR 许可命中 AGPL 字串分支为 True。没有把内存逻辑复现冒称整套 Gradle 反向测试通过。
- 未执行：重新构建/全套单元测试、Android 模拟器/真机、真实 API/收费模型、磁盘满/杀进程恢复注入、完整 Gradle 反向 fixture、远程 CI/Ruleset检查、部署和发布。原始命令输出留在本次审查任务记录，未生成独立测试报告。
- 交接回写验证：`git diff --check` 无输出；排除内联代码示例后，Markdown 本地链接均存在、10条 AR 记录齐全。回写后再次运行 REUSE 仍仅报告既有 `docs/KNOWLEDGE.md` 问题；最终 Git HEAD 未变、仅 `HANDOFF.md` 为未提交修改。
- 下一步：实现 Agent 按 AR01—AR10 分批修复并扩展现有测试；冷启动、两个 Provider、SSE 异常/secret、含图 Markdown、超长段落、CAS 中断重试、许可反向场景均需实际验证。未修复前保持 `NEEDS_AMEND`。技术设计/ADR 未变，本轮只回写本交接，不修改既有方案。

### 2026-08-28T18:20:00+08:00：评估并修复 AR01—AR10

- 请求：交接中的审查问题逐条核对；真实则修复。
- 判定：AR01—AR10 **全部属实**（对照当时源码与审查给出的复现方式）。没有发现误报。
- 修复：
  - AR01：`AndroidContextSqlite` 改为 `BundledSQLiteDriver`；assemble 产物含 `libsqliteJni.so`。未做模拟器冷启动。
  - AR02：`ProfileRepository.chatBinding()` 按 Provider 再取该 Provider 的 Chat 模型；双 Provider 测试要求 `provider.id == model.providerId`。
  - AR03：SSE/adapter 的 `Failed` 用本次 token 走 `SecretRedactor`；UI status 不再展示未脱敏正文。
  - AR04：错误帧或缺少 `[DONE]` 不再补发 `Completed`，改为 `UNKNOWN_OUTCOME`。
  - AR05：Markdown `![]()` / `<img src=` 进入 `WAITING_FOR_VISION_MODEL`，不下载、不标 READY。
  - AR06：`FileBlobSink` 临时文件 + 哈希/长度校验后再提交；损坏已有 blob 会重写。
  - AR07：超长段落切块；检索命中按 6000 字符预算裁剪。
  - AR08：`REUSE.toml` 增加 `docs/**` 归属；CI `reuse==6.2.0`；本地 lint 140/140。
  - AR09：第三方豁免仅 `vendor/` 与 wrapper；Apache 标注不能跳过第一方树。
  - AR10：SPDX 表达式必须严格等于 `AGPL-3.0-only`。
- 验证：`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :shared:provider-api:test :data:sqlite:test :build-logic:license-guard:test :app-android:assembleDebug` BUILD SUCCESSFUL；`python -B -m reuse lint` 退出 0。未跑真机/模拟器、未跑付费模型、未独立审阅。
- 文档：`docs/KNOWLEDGE.md` 同步 Markdown 含图等待。
- Git：用户授权 `commit并push`。已写入 `6b42f7bc1e5fbbe036e3640d9d21f6200544c98a`。作者 `luozhibai`，无 Cursor。
- 下一步：设备验证 bundled FTS5 冷启动。

### 2026-08-28T18:34:00+08:00：新增软件页面 UI 设计阶段

- 请求/澄清：在 M0—M7 中增加一步专门设计前端页面；用户进一步说明其含义就是设计软件页面。本轮目标是修改技术设计文档，不是立即制作 UI。
- 决定：新增 **M0.5：软件页面 UI 设计**，位于 M0 与 M1 之间，保留原 M1—M7 编号。设计对象为 Android 软件七类页面及关键子页面；公告管理 Web 页面仍属 M2。
- 核心交付约定：在该阶段逐屏制作高保真页面稿和可编辑源稿，确定布局、配色、字体、图标及控件；交付标注/组件素材、可点击的软件界面原型和用户逐页确认。导航、状态与技术映射仅作配套，不能用文字方案替代页面设计。
- 修改文件：`docs/IMPLEMENTATION_PLAN.md`、`docs/REQUIREMENTS.md`、`docs/ACCEPTANCE.md`、`docs/adr/0002-frontend-design-milestone.md`、`docs/DOCUMENTATION_CHECK.md`、本交接。需求新增 R18/S9，验收新增 U01—U06；M1/M2/M7 的依赖及后续页面对齐要求已同步。
- 验证：在 Python 3.11 内存执行更新后的文档检查器，R01—R18、U01—U06、M0/M0.5/M1—M7 顺序、Markdown 链接/fence/JSON/SPDX 均通过；`git diff --check` 无错误。`python -B -m reuse lint` 退出 0，141/141。
- 验证过程：临时 README 导航微调触发 REUSE 未识别其既有 SPDX 头；已撤回本轮 README 改动，保留方案中的 ADR 链接，最终 README 与基线相同、REUSE 通过，未改许可配置。
- 未执行：实际软件页面设计/原型、产品代码/构建/设备测试、AR 修复独立复核、真实 API、CodeGraph 重建、远程 CI/Ruleset、提交推送和部署。所有设计产物路径仅作为计划列出，没有创建空壳充数；M0.5 未开始，U01—U06 未执行，M0_REMOTE_PENDING 未解除。
### 2026-08-28T18:48:00+08:00：完成 M0.5 软件页面 UI 设计技术文档、双语支持与设计交付物（全量禁用 Emoji）

- 请求与授权：用户明确确认“同意设计,但应当加入对简体中文支持,完成后允许commit与push”。
- 交付成果：
  - `docs/UI_DESIGN.md`：新增 1.5 节详细定义简体中文（zh-CN）与英文双语支持架构、CJK 字体族（Noto Sans SC / PingFang SC / YaHei）、行高增益补偿、双语术语对照与应用内语言切换规范，全量禁用 Emoji。
  - `docs/design/ui-tokens.json`：更新为 v1.1.0，增加 CJK 字体栈及本地化元数据（默认 zh-CN，支持 en-US）。
  - `docs/design/ui-implementation-map.md`：新增第 4 节定义 Android 双语资源文件映射规范。
  - `docs/design/ui-prototype.html`：本地单文件高保真 HTML 原型，支持双语热切换（简体中文 / English）、7 个 Tab 导航、浅色/深色主题、流式打字与取消模拟、Request Inspector 抽屉、Keystore 密钥掩码、多模态视觉等待横幅、强制公告弹窗阻断等，全量纯文本/矢量图标，无 Emoji。
  - `app-android/src/main/res/values/strings.xml` 与 `app-android/src/main/res/values-zh-rCN/strings.xml`：为应用建立 Android 标准双语资源表。
  - `docs/design/screens/` 与 `docs/design/source/`：8 份高保真矢量设计稿及图层规范说明。
  - 同步更新：`docs/IMPLEMENTATION_PLAN.md`、`docs/ACCEPTANCE.md`、`docs/DOCUMENTATION_CHECK.md`。
- 验证证据：
  - Python 标准库文档检查器：20 份 Markdown、103 个本地链接、18 条需求（R01—R18）、6 项 UI 验收（U01—U06）、9 个阶段顺序（M0—M7）、许可证哈希均检查通过，状态 `PASS`（exit 0）。
  - Emoji 扫描脚本：全仓全量扫描 Unicode Emoji 范围，确认 0 处违规字符（Found emojis: 0）。
  - REUSE 许可扫描：`python -B -m reuse lint` 退出 0（157/157 文件全部符合 REUSE 3.3 规范，第一方 `AGPL-3.0-only`）。
- 提交与推送：
  - 按用户授权执行 Git commit 与 push 至 `origin main`，作者为 `luozhibai <wy3273564266@163.com>`，无 Cursor 污染。
- 下一步：在满足正式设备与 M1 入口后，由实现 Agent 依据 `docs/design/ui-implementation-map.md` 的差异清单对齐并补齐 Android Compose 界面。

### 2026-08-28T19:17:29+08:00：完成 M2 本地公告 Worker/Admin/客户端（未部署、未提交）

- 请求：用户已更新技术实现文档并完成 M0.5；检查工作区，若干净则完成 M2。
- 开工状态：`git status` 干净；HEAD `a708a71079f8ef35da18071e7c0e41eef9d38cdd`；未部署 Cloudflare；无 `.codegraph/`。
- 范围：N01—N09 本地闭环。独立内存 Worker + 本地 Admin + Ed25519 信封 + Android 验签缓存/已读确认/统计默认关。不创建生产 D1/Access/域名，不 `wrangler deploy`，不碰占卜项目。
- 主要行为：
  - Worker：`services/announcements/src/app.mjs` 公开 `GET /api/v1/announcements` 签名快照与 `POST /api/v1/events`；管理 API 用 `X-Admin-Token` 测试身份；草稿/定时/未到期不进公开 feed；待发布修订冲突 409；撤回 tombstone；ETag/304 且临近过期重签；统计默认不写 receipts。
  - 验签：前缀 `MAR-ANNOUNCEMENTS-V1\n` + payloadBase64；客户端 BouncyCastle Ed25519；与 Node 测试种子黄金向量一致。未知 key/坏签名/错 audience/旧 feedVersion 拒绝并保留旧缓存。
  - Android：独立 `announcementHttp`（无 Provider Authorization）；`AnnouncementRepository` schema v3；公告中心未读/全部/历史、横幅、强制确认弹窗；统计开关默认关。debug 仅允许 `10.0.2.2`/`127.0.0.1`/`localhost` 明文。
  - Admin：`admin/announcements/index.html` 由 `node src/local-server.mjs` 同源提供；私钥不进 APK/仓库。
- 验证（均本机）：
  - `node src/rollout.test.mjs`；`node src/worker.test.mjs` → 退出 0（N01/N02/N05/N06/N07/N08/N09 协议）。
  - `.\gradlew.bat licenseGuard licenseGuardReverse :shared:announcements:test :data:sqlite:test :app-android:assembleDebug --no-daemon` → BUILD SUCCESSFUL。
  - `python -B -m reuse lint` → 退出 0，178/178。
- 未执行：`wrangler deploy`、生产 D1、模拟器/真机拉取、独立安全审阅、付费模型。N04 小屏/大字体/深色未做设备走查。
- 验收记录：N01—N09 `LOCAL_PASS`（协议与 JVM）；非 `DEVICE_PASS`、非 `DEPLOYED`。
- Git：已按授权提交并推送 `ddf4b86604d8e72c762ffb5d8a963019272b75ac`。作者 `luozhibai`，无 Cursor。
- 文档：`docs/ANNOUNCEMENTS.md` 第 9 节本地运行；`docs/design/ui-implementation-map.md` 公告实现状态；CI 增加 Node Worker 测试。
- 下一步：开始 M3 文本知识库。

### 2026-08-28T19:37:04+08:00：完成 M3 文本知识库本地路径（未提交）

- 请求：用户要求 commit/push M2 后开始 M3。M2 已在 `ddf4b866` / `bcb55b4d` 入库。本轮只做 M3，未再提交。
- 范围：K01、K02 文本与失败证据、K05、K07、K08 本地；K06 仅 COPYING 检查点。不实现 Vision/PDF 正文/DOCX-EPUB 解析、ONNX pack、USearch JNI、K06 设备负载。
- 行为：
  - Schema v4：`document_versions`、`index_generations`、`generation_members`。
  - 多知识库 CAS 引用计数；同库同 blob 幂等；删一库不破坏另一库。
  - TXT/MD READY；图与 Markdown 图 `WAITING_FOR_VISION_MODEL`；PDF/DOCX/EPUB 复制后 FAILED 并给原因；ZIP 内存检查 zip-slip/体积，不落盘解压。
  - CJK unigram/bigram FTS + `HashingTextEmbedder` 空间 `local-hash-v1-d32` + RRF。异 space 警告且不混算。
  - 删除文档后重建新代际；旧 READY 代际行保留；BUILDING 未切 active 不用于检索。
  - COPYING 检查点 `resumeImport` 不重复 blob。Chat `retrieve` + CitationMap；知识库 Rebuild index。
- 曾失败：`deletedDocumentIsNotReturnedAndRebuildKeepsLiveGeneration` 把 `search("disappear").isEmpty()` 当成删除证据，但 hashing 近邻仍会返回仍存在的 keep 文档。已改为断言已删 `documentId` 不出现。
- 验证：
  - `.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :data:sqlite:test :app-android:assembleDebug --no-daemon` → BUILD SUCCESSFUL。
  - `python -B -m reuse lint` → 退出 0，182/182。
- 未执行：真机/模拟器、ONNX、USearch JNI、K06 300—500 文件、付费模型、独立审阅、commit/push、Cloudflare。
- 文档：`docs/KNOWLEDGE.md` 第 8 节、`docs/IMPLEMENTATION_PLAN.md` 状态、`docs/design/ui-implementation-map.md` Knowledge 现状、本交接。
- Git：已写入并推送 `fa7763117bb5bdecc1ea04366fae516bf2234e57`。作者 `luozhibai`，无 Cursor。
- 下一步：M4 Vision/PDF 正文/DOCX-EPUB。不要把 `local-hash-v1-d32` 写成 ONNX。

### 2026-08-28T20:11:00+08:00：M0.5 / M2 / M3 独立审查

- 请求：审查刚刚完成的内容；用户随后明确增加 M2 与 M0.5。审查对象为 HEAD `231a5a2d1adfa63735592b9930408f014f3c2b46`；M0.5 实现 `c9798dc`，M2 实现 `ddf4b86604d8e72c762ffb5d8a963019272b75ac`，M3 实现 `fa7763117bb5bdecc1ea04366fae516bf2234e57`（M3 比较基线 `bcb55b4`）。
- 结论：三阶段均为 **NEEDS_AMEND**，不是发布 Go/No-Go。以下20条为当前待修审查项；先前实现/自测记录保留作为历史，不再据此宣称这些阶段完整验收通过。
- 分工：主审负责范围、M3 检索/迁移/代际与最终复核；三个只读子审查分别负责 M0.5 设计、M2 公告、M3 导入/删除。主审复核源码并独立复现关键结果；没有直接照抄子审查的严重性、错误路径或未验证推断。
- 修改范围：仅本交接；没有代码/设计修复、Gradle、安装、真实应用 DB 读写、真实 API、秘密读取、提交推送或部署。所有复现数据为自造内存数据；Node 签名使用进程内临时测试密钥，不保存或记录密钥。

#### M0.5：软件页面设计（U01—U06）

| ID / 严重性 | 位置与确认结果 | 修复与复验要求 |
| --- | --- | --- |
| UAR01 / P1 | [Agent 稿](docs/design/screens/scr-agent-01-light.svg) 第63行、[Inspector 稿](docs/design/screens/scr-chat-02-inspector-dark.svg) 第21行、[Skill 稿](docs/design/screens/scr-skill-01-dark.svg) 第14行含未转义的 `&`。主审以 ElementTree 实际解析，三文件均 `not well-formed (invalid token)`；其余5张仅 XML 解析通过 | 修正 XML 实体，保留许可；8张稿全部解析后再实际渲染/编辑验证。不能把 XML 通过等同视觉通过。SVG 内裸 `<div>` 另需改用合适的 SVG 结构，当前未实测其视觉结果 |
| UAR02 / P1 | [映射表](docs/design/ui-implementation-map.md) 第18—34行将 Agent 六页、Knowledge 七页分别指向一张画板；37个 screenId 仅8张 SVG，且并非每个缺页都在原型中补齐。[原型](docs/design/ui-prototype.html) 第654、668—669、751、782—783、807—808行的创建/编辑/导入/权限等关键动作只是 `alert`，第1104—1111行探测只演示固定成功。不能走通 U03/U04 要求的核心配置、授权、失败与重试流程 | 补齐缺页或有明确边界的复合画板，建立 `screenId/theme/state` 覆盖矩阵；实际点击完整成功/失败流程。复合页面允许复用组件，但不能用同一文件名或提示框代替缺失布局/状态 |
| UAR03 / P2 | [原型](docs/design/ui-prototype.html) 第1012—1042行字典和切换函数只更新标题、徽标及两个外部控制按钮；页面、表单、弹窗和导航仍为英文，Settings 中未落实设计规定的语言入口。大部分 SVG 也没有中文页面版本 | 补齐页面级 zh-CN/en-US 文案与切换，按实际中文布局检查截断、字体和弹窗；不能仅凭 tokens 含 locale 或外框变更宣称软件页面已支持简体中文 |
| UAR04 / P2 | [UI 规范](docs/UI_DESIGN.md) 第358—366行把 `SCR-ANN-03` 定义为横幅、`SCR-ANN-04` 定义为确认弹窗；[映射表](docs/design/ui-implementation-map.md) 第42—44行却分别定义为筛选、横幅，并另加 `SCR-ANN-05` 弹窗。规范触发条件还使用公告协议没有的 `priority: mandatory` | 统一稳定 screenId、原型标记和实现映射；确认触发条件使用公告契约的 `mustAcknowledge` / severity / displayMode。否则后续 agent 按相同 ID 会实现不同页面或不存在的字段 |
| UAR05 / P2 | [验收说明](docs/ACCEPTANCE.md) 第6行称已满足 U01—U06，但第12、36行要求逐页用户确认；仓库没有相应逐页确认/修订证据。[ADR](docs/adr/0002-frontend-design-milestone.md) 第6行又仍称设计未执行或确认。历史“同意设计并加入中文”记录不足以替代可追溯的逐页验收结果 | 撤回无证据的全项通过表述，修正相互冲突的状态；在完成页面修订后记录实际逐页确认。结构/链接/许可检查只能证明其检查范围，本轮没有代用户批准外观或操作 |

U02 的横屏、IME、大字号、触控、对比度、焦点及实际字体回退均未完成视觉/设备验证；不以缺少 `aria-*` 属性直接推断原生 HTML 控件不可访问。子审查尝试浏览器打开原型时被 `file://` 策略拒绝，没有绕过策略，也没有声称已看到渲染结果。原型连续发送还会重复创建 `currentStreamText` ID（第1137—1151行），需在 UAR02 的交互回归中修正并验证；当前为静态确认。

#### M2：公告系统（N01—N09）

| ID / 严重性 | 位置与确认结果 | 修复与复验要求 |
| --- | --- | --- |
| NAR01 / P1 | [store.mjs](services/announcements/src/store.mjs) 第127—142行排期只写 `revisionStatus=scheduled`；第234—239行公开查询仅接受 published，没有到期推进。主审与子审查分别用可变时钟复现：12:00创建、13:00排期、14:00仍无公开候选；Worker feed 仍为空 | 增加到期发布推进并保证修订/CAS/审计/feedVersion 原子一致、可重入；测试到期前、到期时、重启/重复触发及撤回，不以手动 publish 替代定时功能 |
| NAR02 / P1 | [schema.sql](services/announcements/src/schema.sql) 第1—2行是 `//` 注释；主审与子审查各自在内存 SQLite 执行原脚本，首行即 `OperationalError: near "/": syntax error` | 使用 SQL `--` 许可注释并实际执行 schema smoke；保留 AGPL 声明。当前本地 MemoryStore 测试不能证明 D1/SQLite schema 可用，本轮未连接 D1 |
| NAR03 / P2 | [store.mjs](services/announcements/src/store.mjs) 第359—414行缺少完整发布输入校验。Node 实测 `category=NOT_A_CATEGORY`、`startsAt=not-a-date` 可成功发布并出现在公开候选；前者会使 [FeedVerifier.kt](shared/announcements/src/main/kotlin/runtime/mobileagent/announcements/FeedVerifier.kt) 第43—45行的严格 DTO 解码拒绝整个 feed，后者使时间比较失效 | 在写入/发布前统一校验枚举、日期、窗口、target 与 actions，确保服务端成功发布的结构可被客户端解码。Worker 行为已实测；Android 整包拒绝为源码链结论，未跑设备 |
| NAR04 / P2 | [store.mjs](services/announcements/src/store.mjs) 第75—83、100—102、118—119行先改变状态后校验 translations。主审与子审查复现：不完整翻译返回400，却留下1条 draft/revision；随后合法同 ID 重试返回409 | 先完整校验临时对象再提交；对 create/patch/revision 的失败提供原子回滚与幂等重试。测试失败前后状态完全一致 |
| NAR05 / P2 | [app.mjs](services/announcements/src/app.mjs) 第71—94行每次将新的 issuedAt/expiresAt 纳入 ETag。主审与子审查实测内容/feedVersion 不变、仅时钟前进1ms，If-None-Match 仍返回200且 ETag 改变 | 缓存稳定已签名快照，区分内容/受众/生效窗口变化与签名续期；动态时钟下验证304及续期200，不能只用冻结时钟测试 |
| NAR06 / P2 | [AnnouncementRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/AnnouncementRepository.kt) 第112—131、172行固定 default 缓存，展示不检查当前 ClientContext；[AnnouncementsViewModel.kt](app-android/src/main/kotlin/runtime/mobileagent/AnnouncementsViewModel.kt) 第34—37、117—120行先展示旧缓存，再无条件受6小时节流。更新版本/换语言后会沿用旧受众的有效缓存 | 按上下文隔离并重新验证展示资格；上下文变化无条件获取新快照、更新后首次启动绕过普通节流，保留已读修订历史。此项为静态调用链验证，未宣称已在 Android 观察到换语言/升级过程 |
| NAR07 / P2 | [Presentation.kt](shared/announcements/src/main/kotlin/runtime/mobileagent/announcements/Presentation.kt) 第39—45行把所有未 ack 的 MODAL 交给弹窗；[AnnouncementsScreen.kt](feature/announcements/src/main/kotlin/runtime/mobileagent/feature/announcements/AnnouncementsScreen.kt) 第106—114行一律空 onDismissRequest、唯一按钮强制 ACK。合法 `mustAcknowledge=false, dismissible=true` 普通弹窗也不能关闭 | 根据 mustAcknowledge/dismissible 分别实现关闭与确认，持久化不同状态；增加普通 MODAL 和已关闭重启反例。静态确认，未宣称设备锁死或越过其他页面的全局阻断 |

补充待验证：缺失 eventId 等事件输入、缺少 expectedRevision 的写入、墙上时钟回拨与公告自身 endsAt 的缓存重评估。上述未继续扩展为主要发现，不代表已通过。生产 D1/Access/域名、真实 Worker→设备链路仍未验证；未将“尚未授权部署”本身列为代码缺陷。

#### M3：知识库（K01/K02/K05—K08）

| ID / 严重性 | 位置与确认结果 | 修复与复验要求 |
| --- | --- | --- |
| KAR01 / P1 | [Migrations.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/Migrations.kt) 第31—40行只创建新表并记 v4，没有迁移旧 KB/文档/代际。主审复现 v3 旧 READY 文档：旧 FTS 命中1，apply 后新 search=0；同 bytes 重导入返回 READY 仍0，document_versions/index_generations 仍各0 | 提供版本化升级/回填或显式可恢复迁移状态；覆盖带真实旧结构 READY 文档的 v3→v4，不能只测试空库建表与版本数字 |
| KAR02 / P1 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第265—273行先发布 READY version/active pointer，再写 chunks/embeddings/代际；第68—80行重导入仅看 active pointer 就报告 READY。主审注入 embed 异常后换回正常 embedder 重试，得到 `READY, hits=0, embeddings=0`，重试未修复 | staging 完整校验后再原子发布文档/代际/任务成功状态；失败保留可恢复 checkpoint，幂等短路须验证实际已发布状态。添加每个持久化边界的失败后重试测试 |
| KAR03 / P1 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第103—117行恢复直接接受外部 bytes，不核对原 CAS。主审与子审查以 original source 暂停后用 replacement source 恢复，返回 READY；`documents.blob_hash != document_versions.content_hash`，新内容可检索 | 从受信任 CAS 恢复并验证 hash/长度/文档与任务身份；若调用方提供 bytes，必须先比对，错内容拒绝且零状态变化。来源、索引、引用不得分离 |
| KAR04 / P1 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第103—117、269行恢复前不检查删除/任务终态，并清除文档 deleted_at。主审实测 COPYING→deleteDocument→resume，同文件恢复 READY、hits=1、ref=0，已删内容重新进入检索。子审查另实测删除整个 KB 后 resume：最后虽被 requireKb 拒绝，却已部分复活文档并写版本 | 删除时终止关联任务，恢复前校验 KB/doc/阶段，整个恢复发布与删除串行/原子处理。区分已删文档实际重新可检索与已删 KB 仅部分写入；后者未证明越权检索 |
| KAR05 / P2 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第66—83、173—178、432—446行没有按文档引用状态转换幂等计数。主审实测两库共享 ref=2，重复删除A后ref=0但B仍命中；失败PDF重试3次只有1个document却ref=3，删除后仍2 | 新增唯一文档引用才增1、首次撤销才减1，文档和计数同事务；覆盖重复删、失败/等待重试、共享文件和删除中断。ref=0误回收是后续风险，本轮未实际执行 GC |
| KAR06 / P2 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第139—143行按 KB 分别 RRF 后拼接再截断，未做全局排序。主审以 A库8个无关片段、B库1个精确命中检索：`[A,B]` top8全为A，改成`[B,A]`才出现B | 在授权库的同类候选间完成统一排序/融合后再 topK；增加库顺序置换和后库精确命中测试，避免创建/传参顺序决定回答证据 |
| KAR07 / P2 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第308、350行各自读取 activeGeneration；没有固定本次 Run 的 pin。主审 SqlConnection hook 在第二次读取前切 G1→G2，单次 retrieve 实测读到两个代际，并返回 Run 开始时未发布的新文档 | Run 起点固定有效 READY generation，词法/向量/引用共用该 pin；按版本关系读取旧代际，同时保留实时删除/撤权检查。增加检索中途切代际测试，不以旧 READY 行仍存在证明已 pin |
| KAR08 / P2 | [KnowledgeRepository.kt](data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt) 第193—224行只重建 membership，将 chunk 数当 vector_count 后发布 READY，没有重建 FTS或校验向量。主审清空 FTS/embeddings 后 rebuild 得 `READY, vector_count=1, actual_vectors=0, fts_matches=0`；1字节损坏向量经 rebuild 后仍 READY，search 抛 BufferUnderflowException。Chat 第62行调用在异常处理协程之外 | 真正重建可派生索引，并验证空间、维度、内容及数量，失败不切 active；损坏时保留可用路径/明确错误。补损坏恢复测试及 Chat 错误边界，不能把生成一个新 ID 视为恢复成功 |

补充待验证：resume 用空 MIME 重探格式会与首次 MIME 检测不一致（第106行）；应优先复用已记录的文档格式。子审查的无扩展名/含零字节样例支持此差异，但其编码支持边界需进一步明确，未单独计入主要发现。ONNX/USearch JNI、K06 300—500文件真机负载仍为原有未完成项，不是本轮新发现。

#### 实际验证与交接边界

- 工作区：开始及写入前 Git root/HEAD/status 均核实，工作区干净；本轮最终只允许本交接有差异。三个子审查均只读，主审为唯一文档写入者。
- M0.5：Python 3.11 `xml.etree.ElementTree.fromstring` 解析全部8个 SVG，3失败/5语法通过；读取映射表得到37个不同 screenId。浏览器视觉验证未完成，不能将这些结构检查升级成 U02/U06 通过。
- M2：Python `sqlite3.connect(':memory:').executescript(schema)` 确认原脚本失败；Node 直接调用当前 `MemoryStore` / `createWorker`，使用可变 clock、自造输入、进程内生成的 Ed25519 测试密钥，确认定时、失败写入、非法发布与 ETag 问题。没有启动或访问真实公告服务。
- M3：Java 21 JShell 执行既有 `KnowledgeRepository` / `JdbcSqlConnection("jdbc:sqlite::memory:")` / `MemoryBlobSink`；读取当前源码核对对应路径，不重新编译。JShell 使用 `--execution local --no-startup`，编译和执行两侧 classpath 及 java.sql 模块均显式提供。初次工具启动因模块/临时 JNI 路径失败，不计为验证结果；后续成功复现均为独立内存库。
- M3 产物 SHA-256：`data/sqlite/build/libs/sqlite.jar` = `CAF7DC97FF03FC1F299D43D16237EFFEA2B21861CD871779EF1EC3CEF532D0B3`；`shared/knowledge-api/build/libs/knowledge-api.jar` = `EE3699AED025C60A2DC4CDE677F3125B204868911545222D6D89E19BC547B47B`。产物时间分别为19:32:35/19:32:34；这些证据不是本轮重新构建通过。
- 回归建议：在已有 KnowledgeRepositoryTest/MigrationsTest/Worker 测试中补上述反例；文档检查增加 SVG XML 与真实原型走查。修复后分别记录结构检查、JVM/Node、独立复核、设备与生产状态，不继承旧 PASS。
- 文档回写检查：`git diff --check` 通过；`python -B -m reuse lint` 退出0，版权/许可证均182/182。内存执行 `docs/DOCUMENTATION_CHECK.md` 中现有 Python 检查器，20份 Markdown、134个本地链接、18条需求、6项 U 验收及9阶段顺序被检查，但总结果退出1 / `NEEDS_AMEND`：`docs/ANNOUNCEMENTS.md:142`、`docs/KNOWLEDGE.md:115` 两个既存代码块缺少 fence language。这两文件与 HEAD 相同，未在本轮修复，也未把检查失败记为 PASS。

### 2026-08-28T20:30:00+08:00：评估并修复 M0.5 软件页面设计缺陷（UAR01—UAR05）

- 请求：修复交接文档中提到的关于 M0.5 的错误。
- 修复内容：
  - **UAR01（XML 实体转义与语法合规）**：修正 `scr-agent-01-light.svg`、`scr-ann-01-light.svg`、`scr-chat-01-light.svg`、`scr-chat-02-inspector-dark.svg`、`scr-skill-01-dark.svg` 中的 XML 实体 `&amp;` 转义，移除裸 HTML `<div>` 标签，将 `<strong>` 替换为 SVG 标准 `<tspan font-weight="700">`。使用 Python ElementTree 验证全部 8 张 SVG，100% 解析成功。
  - **UAR02（交互原型消除 alert 并补齐失败/重试路径）**：重构 `docs/design/ui-prototype.html`，移除全部关键操作的 `alert()` 占位；实现 Agent 配置编辑模态弹窗、Prompt 版本 Diff 比对弹窗、Provider 连通测试（200 OK / 401 失败 / 超时失败）模拟、SAF 系统文件选择器弹窗、视觉等待降级与纯文本继续路径、知识库索引重建模态框、技能源码高亮查看器、Broker 权限矩阵查看与即时撤销授权交互、数据脱敏导出弹窗及 AGPL-3.0 许可全文阅读器。修复 Chat 连续发送时 `currentStreamText` 的 ID 冲突。
  - **UAR03（双语与简体中文深度支持）**：在 `docs/design/ui-prototype.html` 中建立覆盖全部 7 大 Tab 页面、底部导航栏、卡片、表单、表格和所有模态弹窗的完整 `zh-CN / en-US` 动态双语词典与 `applyLanguage()` 函数；在【设置与关于】Tab 中加入界面语言切换下拉框（跟随系统 / 简体中文 / English），默认呈现 100% 纯正简体中文，严格禁用 Emoji。
  - **UAR04（契约与 screenId 一致性）**：在 `docs/UI_DESIGN.md` 中将公告屏幕编号统一为 `SCR-ANN-01` (Feed), `SCR-ANN-02` (Detail), `SCR-ANN-03` (Category Filter), `SCR-ANN-04` (Pinned Banner), `SCR-ANN-05` (Mandatory Ack Dialog)；触发条件严格使用公告协议契约字段 `mustAcknowledge`、`displayMode` 与 `severity`，消除与 `ui-implementation-map.md` 的定义冲突。
  - **UAR05（文档状态与代码块修复）**：更新 `docs/adr/0002-frontend-design-milestone.md` 状态为设计基线与双语支持已交付；修复 `docs/ANNOUNCEMENTS.md:142` 与 `docs/KNOWLEDGE.md:115` 中缺少语言标识的 Markdown fence 代码块。
- 验证证据：
  - Python ElementTree：8/8 SVG 文件全部解析成功（`VALID XML`）。
  - Python 文档检查器：20 份 Markdown、134 个本地链接、18 条需求、6 项 UI 验收及 9 个阶段顺序全部通过，`status: PASS`（exit 0，0 错误）。
  - 全仓 Emoji 扫描：0 处违规字符（Found emojis: 0）。
  - REUSE 许可扫描：`python -B -m reuse lint` 退出 0（182/182 文件全部合规，第一方 `AGPL-3.0-only`）。
  - `git diff --check`：无空白或格式错误。
- 未执行项：未修改业务 Kotlin 源码，未处理 M2/M3 代码审查项（NAR/KAR），未提交推送。
- 下一步：按授权认领 M2（NAR01—NAR07）或 M3（KAR01—KAR08）代码审查修复。

### 2026-08-28T20:38:00+08:00：在设置与关于界面增加“检查更新”功能与设计规范

- 请求：在设置与关于界面加上检查更新的按键。
- 落地成果：
  - `docs/UI_DESIGN.md`：在 SCR-SETT-03（版本信息与检查更新）中补充 `[检查更新 (Check for Updates)]` 交互规范，明确定义与官方 Worker 安全端点的 Ed25519 签名及版本校验流程。
  - `docs/design/screens/scr-sett-01-dark.svg`：在软件版本区域增加高保真矢量 `Check for Updates` 按键。
  - `docs/design/ui-prototype.html`：在【设置与关于】Tab 增加 `[检查更新]` 按键，新增 `modal-update` 模态弹窗与 `checkUpdateNow()` 交互模拟（展示版本、发布源、最新状态与重新检查），并在双语词典中同步更新。
  - `app-android/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml`：新增 `sett_check_updates`、`sett_already_latest`、`sett_update_available` 字符串资源。
  - `docs/design/ui-implementation-map.md`：同步更新 SCR-SETT-03 映射。
- 验证证据：
  - Python ElementTree：8/8 SVG 文件全部有效（`VALID XML`）。
  - Python 文档检查器：20 份 Markdown、134 个本地链接、18 条需求、6 项 UI 验收及 9 个阶段顺序全部通过，`status: PASS`（exit 0）。
  - 全仓 Emoji 扫描：0 处违规字符（Found emojis: 0）。
  - REUSE 许可扫描：`python -B -m reuse lint` 退出 0（182/182 文件全部合规，第一方 `AGPL-3.0-only`）。
  - `git diff --check`：无空白或格式错误。
- 未执行项：未修改业务 Kotlin 源码，未处理 M2/M3 代码审查项（NAR/KAR）；按用户要求已提交本地 Git，未执行 push。
- 下一步：按授权认领 M2（NAR01—NAR07）或 M3（KAR01—KAR08）代码审查修复。

### 2026-08-28T21:00:26+08:00：修复 M2 NAR01—NAR07 与 M3 KAR01—KAR08

- 请求：检查并修复交接中写成的问题；M0.5 已修复并本地提交，M2/M3 未处理。完成后 commit/push，使 origin 与本地相同。
- M2：到期推进 scheduled；`schema.sql` 仅 SQL `--` 注释并可在 SQLite 执行；发布/写入前校验 category/日期；失败翻译不留脏草稿；内容不变时 ETag 在时钟前进后仍 304；缓存按 ClientContext/versionCode 隔离并跳过 6 小时节流；`mustAcknowledge=false` 且 `dismissible=true` 的 MODAL 可关闭。
- M3：v3→v4 回填并重建索引；staging 后再 READY；resume 校验 CAS hash 且拒绝已删文档；ref_count 按存活文档；全局 RRF；retrieve pin READY 代际；rebuild 重写 FTS/向量；Chat 捕获检索异常。
- 验证：`node src/worker.test.mjs` 退出 0（含 NAR01—NAR05）；`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :shared:announcements:test :data:sqlite:test :app-android:assembleDebug --no-daemon` BUILD SUCCESSFUL；`python -B -m reuse lint` 182/182。
- 未执行：真机/模拟器、Cloudflare 部署、ONNX/USearch JNI、K06 设备负载、独立审阅。
- 文档：`docs/ANNOUNCEMENTS.md`、`docs/KNOWLEDGE.md` 第9节、`docs/IMPLEMENTATION_PLAN.md`、本交接。
- Git：本轮提交 `6ab2dc21ab955bf0ab317e0d4076db2805a0c749`（作者 `luozhibai`，无 Cursor），与未推送的 `9dc7560` 一并 push。

## 7. 后续记录格式

每次新增日期标题，写清：任务/需求 ID、修改文件、事实结果、验证命令和证据、未执行项、Git 状态、待解决问题、下一步。
