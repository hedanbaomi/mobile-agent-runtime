<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-08-28T19:17:29+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

**接手者必须先读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。工作后必须维护本文件及受影响的专题文档。**

## 1. 当前事实

| 项目 | 状态 |
| --- | --- |
| 产品 | M1 部分实现；AR01—AR10 已修复。M0.5 UI 设计基线 `DOC_CHECK_PASS`。M2 本地公告闭环已实现（Worker/Admin/验签缓存/客户端），N01—N09 为本地测试 PASS，**不是**设备 PASS、**不是**生产部署。完整 MVP 未完成 |
| 业务源码/构建 | 本轮 `licenseGuard`/`licenseGuardReverse`、`:shared:announcements:test`、`:data:sqlite:test`、`:app-android:assembleDebug` 通过；`node src/worker.test.mjs` 通过；REUSE 178/178 |
| Git | 分支 `main` 跟踪 `origin/main`；HEAD 仍为 `a708a71079f8ef35da18071e7c0e41eef9d38cdd`。用户已授权提交并推送 M2；SHA 在提交后回写 |
| CodeGraph | 仓库无 `.codegraph/` 工作副本（被 gitignore）；本轮用直接读文件定位。未重建索引 |
| 许可 | `python -B -m reuse lint` 退出 0（178/178）；未改 LICENSE 正文；CI 增加 Node 20 公告 Worker 测试 |
| 授权范围 | 用户明确授权：commit 并 push M2，完成后开始 M3。仍不授权 Cloudflare 生产部署 |

## 2. 当前任务

进行中：按用户授权提交并推送 M2，随后开始 M3 文本知识库（K01、K02、K05—K08 本地）。不部署 Cloudflare 生产。

## 3. 关键约束

- 第一方 `AGPL-3.0-only`。applicationId 暂为 `runtime.mobileagent`。CODEOWNERS 为 `@hedanbaomi`。
- Python 不得在主进程执行；isolated service 已声明，CPython 包未嵌入。
- 含图知识库无 Vision 时等待。TXT/MD 可词法 READY；无 ONNX 向量。USearch JNI 未构建。
- 不部署 Cloudflare 生产资源。提交作者使用仓库 Git 用户 `luozhibai`，不含 Cursor。Cursor 环境会劫持 `git commit`；需用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`。

## 4. 接手顺序

1. `git status --short --branch`、`git log -1 --format=full`；确认 M2 未提交改动仍在工作区
2. 确认 HEAD 无 `Co-authored-by: Cursor`
3. 若用户授权提交：不要用被劫持的 `git commit`；用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`，作者 `luozhibai <wy3273564266@163.com>`
4. 设备/模拟器仍缺：bundled FTS5 冷启动、公告本地 Worker→真机拉取
5. GitHub Ruleset 仍为 `M0_REMOTE_PENDING`
6. M1 Compose 仍须按 M0.5 差异清单对齐；不要把 M2 功能闭环冒称为全套 UI 设计实现或生产部署

## 5. 未决事项

Play 生产包名、品牌、Cloudflare 账户/域名/Access、生产签名密钥、Embedding 模型包、NDK/USearch x86_64、CPython 3.14.x 包哈希。GitHub Ruleset 未验证。M2 未提交。未跑模拟器/真机公告拉取。公告 Compose 未按 M0.5 全部视觉标注对齐。历史 AR 修复不等于完整安全验收或发布许可。

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
- 未执行：commit/push、`wrangler deploy`、生产 D1、模拟器/真机拉取、独立安全审阅、付费模型。N04 小屏/大字体/深色未做设备走查。
- 验收记录：N01—N09 `LOCAL_PASS`（协议与 JVM）；非 `DEVICE_PASS`、非 `DEPLOYED`。
- Git：改动仍在工作区。用户已授权本轮提交与推送。
- 文档：`docs/ANNOUNCEMENTS.md` 第 9 节本地运行；`docs/design/ui-implementation-map.md` 公告实现状态；CI 增加 Node Worker 测试。
- 下一步：用户若要入库再提交；本地联调 `set MAR_ADMIN_TOKEN=...` 后 `node services/announcements/src/local-server.mjs`，把打印的公钥与 `http://10.0.2.2:8787` 填进调试版公告页。禁止把此次结果标为生产已部署。

## 7. 后续记录格式

每次新增日期标题，写清：任务/需求 ID、修改文件、事实结果、验证命令和证据、未执行项、Git 状态、待解决问题、下一步。
