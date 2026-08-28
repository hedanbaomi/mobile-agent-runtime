<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-08-28T18:20:00+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

**接手者必须先读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。工作后必须维护本文件及受影响的专题文档。**

## 1. 当前事实

| 项目 | 状态 |
| --- | --- |
| 产品 | M1 部分实现；AR01—AR10 已按代码核实并修复。完整 MVP 未完成 |
| 业务源码/构建 | 本轮 `licenseGuard`/`licenseGuardReverse`、相关 JVM 测试、`:app-android:assembleDebug` 通过；APK 含 `libsqliteJni.so`。未做设备冷启动 |
| Git | 分支 `main` 跟踪 `origin/main`；AR 修复提交 `6b42f7bc1e5fbbe036e3640d9d21f6200544c98a` 已授权 push。作者 `luozhibai`，无 Cursor trailer |
| CodeGraph | 修后未重建索引 |
| 许可 | `python -B -m reuse lint` 本轮退出 0（140/140）；CI 固定 `reuse==6.2.0` |
| 授权范围 | 用户已授权评估并修复 AR01—AR10，并于 2026-08-28 授权 commit 并 push `main` |

## 2. 当前任务

无进行中认领。AR01—AR10 均判定为真实缺陷并已改代码/测试；设备冷启动与独立审阅仍缺。

## 3. 关键约束

- 第一方 `AGPL-3.0-only`。applicationId 暂为 `runtime.mobileagent`。CODEOWNERS 为 `@hedanbaomi`。
- Python 不得在主进程执行；isolated service 已声明，CPython 包未嵌入。
- 含图知识库无 Vision 时等待。TXT/MD 可词法 READY；无 ONNX 向量。USearch JNI 未构建。
- 不部署 Cloudflare 生产资源。提交作者使用仓库 Git 用户 `luozhibai`，不含 Cursor。Cursor 环境会劫持 `git commit`；需用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`。

## 4. 接手顺序

1. `git status --short --branch`、`git log -1 --format=full`
2. 确认 HEAD 无 `Co-authored-by: Cursor`
3. 在 API26 与当前 target 的设备/模拟器验证冷启动（FTS5/bundled SQLite），再走双 Provider、含图 Markdown、Chat 流式/取消/异常
4. GitHub Ruleset 仍为 `M0_REMOTE_PENDING`

## 5. 未决事项

Play 生产包名、品牌、Cloudflare 账户/域名、签名身份、Embedding 模型包、NDK/USearch x86_64、CPython 3.14.x 包哈希。GitHub Ruleset 未验证。本轮已做限定范围的实现/安全/许可审查，发现 AR01—AR10；不是完整安全验收、设备验收或发布许可。

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

## 7. 后续记录格式

每次新增日期标题，写清：任务/需求 ID、修改文件、事实结果、验证命令和证据、未执行项、Git 状态、待解决问题、下一步。
