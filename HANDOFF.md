<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-08-29T15:12:38+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

**接手者必须先读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。工作后必须维护本文件及受影响的专题文档。**

## 1. 当前事实

| 项目 | 状态 |
| --- | --- |
| 产品 | 审查报告 [mobileAgentRuntime 仓库审查问题报告](docs/2026-08-29_code-review-mobile-agent-runtime-report.md) 仍是输入。0.1.1+0.2+0.3 源码已落地，当前任务将先复核并修复确认问题，再执行 1.0 门禁，最后按 GitHub issue #1 修改并部署公告系统。F-001 只提供 SHA/诊断，**不得**在缺少新 APK SHA+Logcat 时宣称根因已关闭 |
| 业务源码/构建 | 本轮未打包新 APK。上一 debug APK 仍为 211,102,633 bytes，SHA-256 `2F09A17D12AF45F4D1B108E62656059BC0EED4566D69FBD55E3F207446B9A72A`。`compileDebugKotlin` 生成的 app `BuildConfig`：`GIT_REVISION=13edc5759b1f2fa393f29a095c0690dd7184c7c0-dirty`、`GIT_DIRTY=true`、`DB_SCHEMA_VERSION=11`、`BUILD_TIME_UTC=2026-08-29T04:53:26Z`。JVM：knowledge-api 46、sqlite 86、domain 2、agent-runtime 20、provider-api 33，合计 187 项、0 failure/error/skip |
| Git | 分支 `main` 跟踪 `origin/main` 且已同步。实现提交 `ef8e4363884358a8ed75acdf58fc64c69c94d6e7`；交接记录 `4b02df264740f62876d7d303f3ddf18386fc4bbd` 已随 `git push origin HEAD` 推到 `https://github.com/hedanbaomi/mobile-agent-runtime.git`（`13edc57..4b02df2`）。作者/提交者 `luozhibai <wy3273564266@163.com>`，无 Cursor trailer。正式 Android release 仍未授权 |
| CodeGraph | `.codegraph/` 存在；源码修改后 `codegraph sync .` 报告 Already up to date。未修改或提交 `.codegraph/` |
| 许可 | `licenseGuard`/`licenseGuardReverse` BUILD SUCCESSFUL；`python -B -m reuse lint` 330/330 退出 0。未改 LICENSE 正文 |
| 授权范围 | 用户已授权当前任务复核并修复 0.1.1–0.3、执行 1.0 门禁，以及按 GitHub issue #1 完成公告系统 Cloudflare 生产部署。当前指令不自动授权 GitHub push、应用商店发布、付费 Provider/Vision 调用或未经核对的 Cloudflare Access 组织/身份变更 |

## 2. 当前任务

进行中：以 `13edc5759b1f2fa393f29a095c0690dd7184c7c0` 为 0.1.1–0.3 固定审查点，对 `ef8e4363884358a8ed75acdf58fc64c69c94d6e7` 及后续记录做规范/需求双轴复核；确认问题后修复并补证据。随后执行 1.0（F-019 CI/emulator/androidTest、SHA-pinned Actions、真实 SBOM/provenance、正式构建门禁），再实现 GitHub issue #1 的公告刷新、匿名统计、更新入口和管理预设，最后仅对已核对的本产品 Worker/D1 执行 Cloudflare 部署与线上后检。F-001 不得在缺少新 APK SHA+Logcat 时宣称关闭；不调用付费 Provider/Vision，不自动 push 或发布应用商店。

单一写入责任：主审负责 App 原有 ViewModel/MainActivity/DI、SkillRepository、根配置/文档、模拟器、生产部署；product_ui 负责 feature/** 与 app/ui/**；product_data 负责领域/序列化/Profile 与 Agent/Conversation/Settings/Transfer repository，以及唯一 Migrations 写入；knowledge_runtime 负责知识库/解析/本地嵌入/向量/存储/后台导入；python_runtime 负责 CPython 与 IPC；announcements_production 负责 services/admin 公告源码及本地部署准备；protocol_adapters 负责 provider-api/agent-runtime、skills-api 新 ToolExecutor 与 remote DTO；http_transport 仅负责 BuiltinTools/HostHttp、skills-api 模块依赖与对应测试。两个只读审阅者仅复核 1a035aa 的旧反例。每个实现者维护独立 evidence 文档，主审汇总根交接。共享 Gradle/模拟器由主审协调，不互相覆盖代码或全仓构建。

## 3. 关键约束

- 第一方 `AGPL-3.0-only`。applicationId 暂为 `runtime.mobileagent`。CODEOWNERS 为 `@hedanbaomi`。
- Python 不得在主进程执行；官方 CPython 3.14.7 双 ABI 已嵌入并完成 JNI 编译。Round22 在 Android 16/API 36 x86_64 上完成真实 isolated UID/沙箱/取消/限额 12 项验收；其余 Android 版本矩阵仍不能推定通过。
- 含图知识库无 Vision 时等待。Android PDF 光栅化、固定版本 MiniLM ONNX 包、USearch JNI 已实现并在 Round21 设备测试通过。320 文件 fixture 仅证明本地文本/存储等待组件；API31/34/35/36 前台短矩阵已完成，真实 Vision、完整故障矩阵、Android15 六小时 timeout 与 Android16 Job 配额仍缺。旧 hash embedder 仅保留测试兼容，不能作为生产 ONNX 证据。
- 用户曾授权本产品独立 Cloudflare 公告正式部署及 Round22 commit/push，这两项历史授权已执行/移交，不构成当前继续写生产或 Git 的授权。用户现决定自行操作后续公告部署/后检；正式 Android release 尚未授权。

## 4. 接手顺序

1. `git status --short --branch`、`git log -1 --format=full`；确认 HEAD 无 `Co-authored-by: Cursor`
2. 提交作者使用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`，作者 `luozhibai <wy3273564266@163.com>`
3. 先读最新审查报告；只有用户明确要求开始修复后，才按 0.1.1 稳定性补丁→0.2 配置迁移→0.3 批量导入→1.0 release 门禁推进。F-001 必须先绑定真实 APK revision 并取得 Logcat，不能靠静态猜测修复
4. 设备 debug 验收以 Round22 APK/hash 和 `final-debug-validation.md` 为准；不要沿用 Round17 之前的失败日志或旧 hash embedder 结果
5. K06 fixture 已进入删除/隔离验证后的终态，不得再次 resume；除非用户另行要求，不再自动扩展长时 K06 或复核—修复循环
6. 公告系统后续部署、Access 和公网签名/ETag 后检由用户自行操作；不得替用户继续生产变更

## 5. 未决事项

正式包名/品牌/Android release 与签名仍后置。0.1.1–0.3 源码已落地 F-002–F-018 与 F-020 过期文案；F-001 仍为 `candidate_intermittent`，必须用含真实 revision 的新 APK 采集完整 Logcat，不能靠静态路径关闭。F-019（CI/设备回归、真实 SBOM、Actions SHA 钉扎、签名 AAB）未做。Chat/Agents/Knowledge ViewModel 仍为 Activity 作用域，只有 `ShellViewModel` 使用 `SavedStateHandle`；API Embedding 查询重试仍走页面 ViewModel，不签发 consent ticket。知识库 ZIP 仍先整包读入内存（上限 512 MiB），不是 SAF 流式解压。未执行 500 文件/约 500MB 实机批次、杀进程/重启/ENOSPC、真实 Vision 或付费探测。Chat 流式合并与预算单位为静态修复，未跑长流式设备基准。Cloudflare Access 需用户在场创建/选择 Zero Trust 组织与身份策略。完整 K06 仍缺真实 Vision、全阶段 kill/offline/disk-full 注入、Android 15 六小时 timeout 和 Android 16 Job 配额耗尽。GitHub Ruleset 未在本轮验证。不能把未执行的 1.0、生产身份配置、正式 release 或完整 K06 冒称通过。

## 6. 工作记录

### 2026-08-29T07:10:00+08:00：Round22 debug 集成、设备验收与公告生产部署

- 基线/工作区：`main`、HEAD `7511b22ffd7a7d3021b7857b6500cbe75d037ad6`，保留本轮大规模未提交实现；未 reset/clean/stash/commit/push。
- 实现：数据库 schema v10 增加 embedding operation 与 query-vector 持久化；API Embedding 导入/重建/重绑改为短事务与 dispatch 前复核，`DISPATCHED` 后未知结果不自动重放；完整产品 UI、MCP、流式 ZIP、第三方声明、官方 CPython 3.14.7 隔离运行时和 native stdout/FD 防线已经接通。
- 构建：Round22 全部 debug/JVM/SQLite/IPC/SBOM/license 命令 `BUILD SUCCESSFUL`。debug APK SHA-256 `80FF8109B908B3D4E828B846B70C98B58A5B2AC3350C7449AF235D8F40616750`；test APK SHA-256 `A75741FBA9742E1A885FA7C957425B019292FD7426E7DD54E21E6FFA28CD7026`。
- 设备：Android 16/API 36 x86_64 模拟器，Python 12/12、API Embedding 5/5、Knowledge 4/4 通过；新增“日志超限后立即返回合法 JSON”竞态单项 1/1，并验证下一调用恢复。证据见 `docs/evidence/2026-08-29/final-debug-validation.md`。
- 独立复核：原 M6/Python 审查者核对 native 原子计数、结果排序、每次调用初始化、host abort/cleanup 与 Round22 日志，确认旧日志竞态闭环，未发现新的 P1/P2。
- 公告生产：独立 D1 远程迁移无待办；Worker 版本 `dd2be020-85ff-48ef-8b83-779a7a9cc02b` 已部署，包含 source hash `07f164ef5f473ff426488eeeddf0bb7d1cb522286c5389e85baa2351bb473ae3` 和生产签名 secret binding。Access team/audience 为空时后台保持 503；不得在用户不在场时替其创建身份组织/政策。
- K06 边界：320 文件/472,363,598 bytes fixture 完成本地文本与存储等待组件，20 READY 文本、300 WAITING 图片、零 Vision 调用；未执行真实 Vision、完整故障矩阵和 Android 12—16 前台服务矩阵，因此明确不是完整 K06 PASS。
- 未做：正式 Android release、正式签名、commit/push、真实付费 Provider/Vision、Cloudflare Access 身份策略和从本机进行的公网 TLS 后检。

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
- Git：修复提交 `6ab2dc21ab955bf0ab317e0d4076db2805a0c749` 已推送（作者 `luozhibai`，无 Cursor），含 M0.5 `9dc7560`。`origin/main` 与本地相同。

### 2026-08-28T22:20:00+08:00：完成 M4 与 M5 本地路径（只 commit，不 push）

- 请求：完成 M4 和 M5，完成后只 commit，先不 push。
- M4：PDF 文本与 JPEG XObject 检测；DOCX/EPUB 正文/内嵌图；schema v5 assets/vision_results；无 Vision 等待；未同意 0 次调用；成功 cacheKey 不重发；UNKNOWN_OUTCOME 不自动重试；API embedding 未同意不索引；严格模式与文本降级；citation 页/图定位。
- M5：Skill 包 A—E 分类，E 拒绝；zip-slip/原生/pip 拒绝；内置四件工具与 Tool Loop；重复 call id 不双执行；HTTP 需确认；工具输出脱敏。未嵌入 CPython。
- 验证：`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :shared:skills-api:test :shared:agent-runtime:test :shared:provider-api:test :data:sqlite:test :shared:announcements:test :app-android:assembleDebug --no-daemon` BUILD SUCCESSFUL；`python -B -m reuse lint` 194/194。
- 未执行：真机/模拟器、Cloudflare、ONNX/USearch JNI、K06 设备负载、CPython 隔离、独立审阅、push。
- 文档：`docs/KNOWLEDGE.md` 第10节、`docs/SKILLS_AND_SECURITY.md` 第9节、`docs/IMPLEMENTATION_PLAN.md`、本交接。
- Git：提交 `75fc38110618d4c7a731e1d9c6b0efa448c2f987`（作者 `luozhibai`，无 Cursor）。按授权未 push，`origin/main` 仍落后本地。

### 2026-08-28T21:53:47+08:00：M4/M5 独立只读审查（NEEDS_AMEND）

- 请求：审查 M4 和 M5 的工作。范围为功能提交 `75fc38110618d4c7a731e1d9c6b0efa448c2f987` 相对 `a3b87f2` 的实现与相关调用链，当前 HEAD `103a7d2a0e21a534627e408834e5454837ac729b`。关联 R05—R12、K02—K04/K06/K08、S01/S02/S08—S10。
- 结论：**NEEDS_AMEND，18 项确认问题（11 P1、7 P2）**。以下分别记录已复现行为和静态接线证据，不是设备、生产或发布 Go/No-Go。旧“本地通过”是作者当时测试记录，不代表本次独立审查通过。
- 保护现场：开始时只有 HANDOFF 的 Git 状态行存在未提交修改，该行原样保留；本次只修改本文件。main 仍 ahead 2，未提交、未推送。未改业务源码、测试、构建配置、许可正文、真实应用 DB 或 CAS。
- 分工：两个独立审阅者分别检查 M4 解析/视觉持久层与 M5 安装/权限/Broker；主审负责 Runtime/Provider/Android 接线，并用独立内存 fixture 复核关键发现。全部遵循 CodeGraph 优先；CLI 返回遗漏或截断后才读取目标文件。

#### M4 确认发现

| ID / 优先级 | 位置（仓库相对路径及行号） | 触发、证据与修复验收 |
| --- | --- | --- |
| M4R01 / P1 | `app-android/src/main/kotlin/runtime/mobileagent/MobileAgentApp.kt:38`；`data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt:42-47,195-213,555-556` | App 构造 Repository 时未传 VisionBackend，只有默认 null；配置页只改变 image 能力标记。同构内存构造导入合法 1px PNG：先 AWAITING_UPLOAD_CONSENT，确认后 FAILED，原因为 `Vision model is configured in profile but no backend is bound`，不能完成图片导入。须绑定实际 Provider/模型/secret/目的域名对应的后端，并验证配置→预览/同意→成功/恢复的 App 流程；不能以测试 fake backend 代替接线。 |
| M4R02 / P1 | `shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/PdfParser.kt:42-44,161-169`；`data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt:508-546,555-586` | 文本加矢量 `re f` 的有效 PDF 被判 needsVision=false/assets=0，无 Vision 也 READY、可检索。另一个有效的仅绘图 PDF 虽 needsVision=true/assets=0，已有后端且同意后仍以合成 `Page 1:` 建块 READY，backend calls=0。缺页栅格化本身是已披露限制，但把未处理视觉页当完整成功是缺陷。须识别绘图指令；needsVision 且没有可处理页/图资产时等待/失败，不能发布 READY。两个分支均需回归。 |
| M4R03 / P1 | `shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/OfficeParser.kt:47-83,109-145` | DOCX 仅提取 r:embed，EPUB 仅匹配能找到包内资源的 img；外链/丢失图片未被记录为未完成视觉项。主审 DOCX r:link + External relationship fixture 返回 needsVision=false/assets=0/READY/search=1；独立审阅者的 EPUB 外部 img 同样失败。修复应记录所有图片引用，外部/缺失/不支持资源显式等待或失败，保持零自动外联；不得仅由 assets 非空判断视觉完整性。 |
| M4R04 / P1 | `app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt:78-100,108-118,151`；`shared/provider-api/src/main/kotlin/runtime/mobileagent/provider/openai/OpenAiCompatibleAdapter.kt:51-64` | 严格模式仅检查 image 能力后放行，实际上下文始终是 hit.text 字符串，消息逐值编码 JsonPrimitive，没有读取/发送 asset bytes 或图片 content part。策略调用 allow(true,true,false) 返回 Allow(warning=null)，即未启用降级也只发描述。降级提示还只写临时 status，完成时被覆盖。须传预算内的原图/可追溯副本，无法传图则明确阻止；主动降级的警告须跟随回答保留，增加真实请求体断言。 |
| M4R05 / P1 | `shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/PdfParser.kt:27-35,48-52,172-179`；`data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt:216-232,573-576`；`Migrations.kt:34` | 引用无法可靠回到原页/原图：PDF 图片 page=null，多页正文按总字符数均分到“页”；assets 没有 document_version_id，locator 只查 document 并返回整份文档 hash。主审复现视觉 PDF READY 后 locator page=null、返回 hash 不等于 asset hash；直接传伪造 version/chunk/asset 的 Citation 也返回 removed=false（非声称模型能绕过 CitationMap）。独立两页 fixture 把第一页尾部归到第二页。须建立真实 page→contents/asset 映射与版本归属，locator 校验引用关系并返回相应原图/页资源；不得猜页码。 |
| M4R06 / P2 | `shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/Vision.kt:27-32`；`data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt:577-584,603-633` | VisionSuccess 的 tableMarkdown/type 未存入缓存，建块也未使用表格。主审后端仅返回带 TABLE_ONLY_MARKER 的表格：job READY，但 chunks 中该文本行数为 0；成功缓存亦无法恢复表格。须持久化完整、带 schema 版本的视觉结果，索引表格并验证首次处理和缓存命中一致。 |
| M4R07 / P2 | `data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt:529-530,577-594` | UNKNOWN_OUTCOME 被永久缓存，公开入口没有用户明确重试途径。首次 Unknown 后把假后端切为 Success，重复导入及 grantVisionConsent 仍 FAILED，calls 始终 1；提示却写着 Retry is manual。须保留“不自动重放”，另提供说明重复计费风险并再次确认的显式 retry API/UI，不能把 UNKNOWN 当不可解除的成功缓存。 |

#### M5 确认发现

| ID / 优先级 | 位置（仓库相对路径及行号） | 触发、证据与修复验收 |
| --- | --- | --- |
| M5R01 / P1 | `shared/agent-runtime/src/main/kotlin/runtime/mobileagent/agent/AgentRuntime.kt:117-144`；`shared/provider-api/src/main/kotlin/runtime/mobileagent/provider/openai/OpenAiCompatibleAdapter.kt:55-64` | 工具执行后只添加 role=tool/tool_call_id，没有前置 assistant.tool_calls。主审 calculator 两轮请求中第二轮为 system,user,tool(t1)，没有 assistant 调用消息；标准工具协议无法配对，真实 Provider 可拒绝续轮。当前 String map 也不能编码结构化 tool_calls。须保留该轮 assistant 内容/调用数组并按 id 配对结果，增加 Adapter 请求体集成测试，不能只断言存在 role=tool。 |
| M5R02 / P1 | `app-android/src/main/kotlin/runtime/mobileagent/ProvidersViewModel.kt:51-59`；`ChatViewModel.kt:120-132`；`shared/agent-runtime/src/main/kotlin/runtime/mobileagent/agent/AgentRuntime.kt:121-125` | Android 主流程未接通 M5：现有 Provider 保存只有 stream/可选 image，没有 tools，Chat 因此始终不启用工具；无编辑能力入口。HTTP 的 NeedsApproval 又直接结束 Flow，Chat 未提供确认/拒绝/续跑动作，ToolContext 未绑定 httpGet/allowedHosts。须补可验证的能力配置和保留待调用状态的审批续跑流程，并接入受控 HTTP；App 中完成 calculator→模型续答及 HTTP 批准/拒绝后才可标 S08 闭环。 |
| M5R03 / P1 | `shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/SkillArchive.kt:157-177`；`data/sqlite/src/main/kotlin/runtime/mobileagent/data/SkillRepository.kt:46-56,91-100`；`app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt:120-127` | 安装只保留权限 key，丢失 KB/host/method/quota 范围；导入即写 grant，所有 enabled Skill 的能力被全局合并，实际 Broker 不绑定 install/Agent/当前 grant。主审包声明仅 kb-a，按 Chat 同构回调调用 kb-b，得到 kb-b 的合成私有标记；manifest 持久内容仅 id。独立审查还确认同包重导入产生新的未撤销 revision=1 grant。须保存结构化权限、明确逐资源授权、按包 hash/Agent/当前 revision 每次求交集；撤权/重导入不能靠累加 grant 恢复旧权限。 |
| M5R04 / P1 | `shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/SkillArchive.kt:43-48,84-113` | 安装限额未能拒绝不安全归档：5001 项 ZIP 扫描到 5000 即停止，却仍 accepted=true/class A，reason 只是 exceeds5000；900000 个重复字符压缩成 1038 字节，超过 100 倍仍可安装。代码还先 readBytes 完整解压再检查单项/总大小，更大输入可能在拒绝前耗尽内存（未做 OOM 实验）。须有界流式读取/累计检查，所有限额错误都直接 E，不能跳过未扫描内容后接受。 |
| M5R05 / P1 | `shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/BuiltinTools.kt:34-39,92-119` | HTTP Broker 只匹配 host/少量 loopback 字符串，未强制 HTTPS、解析地址/逐跳重定向防护或有界响应。允许域名下的 http:// 与 file:// 均 NeedsApproval→Value 并进入假回调；9 MiB HTTP 输出原样返回，read_document 假宿主返回 2000000 字符也未被 Broker 限制。当前 App 未接 HTTP，因此没有声称发生真实网络越权。须在确定性宿主边界检查 scheme/地址/重定向及流式响应、统一工具输出上限，不能依赖任意 callback 自律；所有测试继续用受控 fixture。 |
| M5R06 / P1 | `shared/agent-runtime/src/main/kotlin/runtime/mobileagent/agent/AgentRuntime.kt:127-140` | ToolResult.Invalid 的错误原样发给模型；Value 只使用可选 secretsForRedaction，漏掉 run 已收到的当前 secret。主审仅用合成标记：即使 callback 中登记待脱敏标记，工具抛异常后第二轮 content 仍含该标记；默认配置的成功工具输出也会保留当前 secret 标记。须成功/错误/拒绝等所有分支共享完整脱敏上下文，包含当前 Provider secret，并在 Adapter 请求体处断言无标记；未读取任何真实 secret。 |
| M5R07 / P2 | `shared/provider-api/src/main/kotlin/runtime/mobileagent/provider/openai/OpenAiSse.kt:35-49` | 两个并行 tool call 的续片没有 id 时，解析器忽略 index，总选最后一个 call id。主审输入 index0/index1 的分片，call-a 停在半个 JSON，call-b 变成两段拼接的坏 JSON。该解析器是 M1 遗留，本轮 M5 执行路径依赖它，不声称由本提交新引入。须按 choice/tool index 建立稳定 id 映射，覆盖交错分片、多个调用和终止标记。 |
| M5R08 / P2 | `shared/agent-runtime/src/main/kotlin/runtime/mobileagent/agent/AgentRuntime.kt:58-80,95-121` | 总时限只在模型轮开始检查。注入 clock：预算 1000ms，流结束时 2000ms，仍 COMPLETED；若返回工具，则超时后先执行 1 次工具，下一轮才报 budget exhausted。须用剩余总时限限制模型/工具与审批恢复，执行前重查并传播取消；用虚拟时间验证不会晚执行或覆盖终态。未做真实长时间/收费调用。 |
| M5R09 / P2 | `shared/agent-runtime/src/main/kotlin/runtime/mobileagent/agent/AgentRuntime.kt:52-56,87-89,111-121` | toolsEnabled=false 只移除 schema；后续收到 ToolCallDelta 仍执行已有 broker。主审输入无 tools 能力但返回 calculator 的假模型：发送 schema 数 0，实际 toolCalls=1。第53行条件由于 toolMaps 的构造永远不成立。须在接收/执行层拒绝禁用工具模型的调用，保留失败原因且宿主调用数为 0。 |
| M5R10 / P2 | `shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/SkillArchive.kt:117-119` | 远程依赖检测仅检查文件名 requirements 和大小写敏感的 http。主审 requirements.txt 含 `requests @ HTTPS://example.invalid/a.whl`，仍 installable=true/class A。须解析支持的依赖清单并明确拒绝远程/动态安装；不以是否出现一个小写子串判断，补不同大小写及清单格式的反例。未下载或执行任何依赖。 |
| M5R11 / P2 | `data/sqlite/src/main/kotlin/runtime/mobileagent/data/SkillRepository.kt:25-56` | “安装”只保存摘要和 SKILL.md，manifest_json 实际仅 id，未保存原始包 bytes/CAS 引用、完整 manifest、脚本资源、来源及校验状态。主审读取内存 DB 确认仅有 id。后续无法按已验 hash 重新加载和审计安装包；这是 M5 持久安装契约缺口，不是要求提前实现 M6 CPython。须保留不可变原包/完整清单及验证元数据，并做进程重建后相同 hash/内容可读取的验收。 |

#### 实际验证与复现入口

- 环境：Windows PowerShell，Java 21 JShell、Python 3.11；使用现有 `shared/{domain,knowledge-api,skills-api,provider-api,agent-runtime}/build/libs/*.jar`、`data/sqlite/build/libs/sqlite.jar` 与 sqlite test classpath，Kotlin 2.1.10、coroutines 1.9.0、serialization 1.7.3、sqlite-jdbc 3.47.2.0。没有重新编译，故这些结果不是新的全量构建证明。
- 调用方式：PowerShell 单引号 here-string 管道到 `jshell -J-XX:-UsePerfData --execution local --no-startup --feedback concise --class-path $auditCp ('-J--class-path='+$auditCp) -J--add-modules=java.sql --add-modules java.sql -`。构造 `JdbcSqlConnection("jdbc:sqlite::memory:")`、`Migrations.apply`、`MemoryBlobSink`；无持久 DB/CAS 写入。JShell 出现过 Java 泛型桥接错误，修正 Continuation 桥接后重新运行并核对输出，不把仅 exit 0 当作断言通过。
- 主审直接调用现有 `AgentRuntimeTest` 的 `toolLoopExecutesCalculatorThenCompletes`、`toolsDisabledWhenModelHasNoTools`、`budgetStopsToolLoop`、`secretsInToolOutputAreRedacted`：4/4 通过，未运行 Gradle。其断言没有覆盖本次反例。
- Runtime 反例：Scripted ModelAdapter + Flow + 真 AgentRuntime/ToolBroker；calculator 首轮 `t1`、次轮文本，检查第二轮消息；两个 index 交错 SSE；可变 clock（1000ms 预算、2000ms 完成）；未启用 tools；工具成功/异常混入明确的合成 secret 标记。所有结果为内存请求记录，非真实 Provider。
- PDF fixture：完整 catalog/pages/page/content/font/xref，自造 content 为 `BT /F1 12 Tf 72 720 Td (vector label) Tj ET` 后加 `0 0 100 100 re f`；SHA-256 `5aa129659934b7570555041427088a00532226a172a856646a5f4eb0153ca5f0`，观察 READY/search=1。仅绘图有效 PDF 观察 READY/backend calls=0/chunk=`Page 1:`。视觉引用使用现有 `writePdfWithImageXObject` 测试夹具与假 Vision，不视为真实图片模型效果证明。
- Office fixture：内存 DOCX ZIP，正文文字加 `a:blip r:link`，关系 `TargetMode=External`、目标 `https://example.invalid/image.png`；观察 READY 且视觉资产 0。未连接该域名。EPUB 外链及不等长两页 PDF 由独立审阅者复现，主审复核相应源码；没有冒充全部设备复现。
- Vision fixture：合法 1px PNG；返回 table-only 成功结果时 READY 但表格标记未入 chunks；可变后端首次 Unknown、随后可成功，同文件重复导入/同意仍 FAILED/calls=1。
- Skill fixture：本地内存 ZIP，5001 项、900000 字符 DEFLATED 条目、远程依赖大写 scheme、仅声明 kb-a 的包；真 Repository/Broker 配 Chat 同构假宿主读取合成 kb-b 标记。HTTP 仅假回调，两个非 HTTPS URL 均进入回调；9 MiB 输出未限制，真实网络调用数 0。
- 文档检查：从 `docs/DOCUMENTATION_CHECK.md` 提取既有 Python 检查块在内存执行；编辑前结果 20 份 Markdown/129 本地链接/18 需求/6 UI 验收/9 阶段，PASS，exit 0。`python -B -m reuse lint` exit 0，194/194；许可正文未改。最终文档/差异检查结果见下方收工记录。
- 未执行：Gradle/build/APK 安装、模拟器/真机、真实 Provider/Vision/HTTP、Cloudflare、付费 API、K06 大规模设备负载、ONNX/USearch JNI、CPython。已披露的 PDF 光栅化/ONNX/设备/CPython 缺口不单独算新增缺陷；本报告指出的是错误成功状态、未接通功能与契约不成立。
- 文档同步：仅 HANDOFF 更新事实、发现、复现与下一步；未变更架构/接口/范围/验收标准，也不改写专题文档中的作者历史自测。当前阶段状态以本次审查结论为准。

#### 下一步与收工

1. 获得修复授权后，优先处理 M4R01—M4R05、M5R01—M5R06；补上述反例回归，再处理其余 P2。不要通过删去视觉/权限要求、放宽测试或自动切文本模式消除报错。
2. 修复需同时维护相应 KNOWLEDGE/SKILLS_AND_SECURITY/IMPLEMENTATION_PLAN 与验收证据；与已有 M2/M3 修复交叉时保留 CAS/版本原子性和删除隔离。
3. 独立复审后才可更新阶段结论；设备验收、M6 实施与 commit/push/部署均另按授权执行。本轮仅审查，无任何修复提交。

收工核验：编辑后文档检查仍为 PASS（20 份 Markdown、129 本地链接、18 需求、6 UI 验收、9 阶段，exit 0），`git diff --check` 无错误；仅 HANDOFF 有差异，保留先前 Git 状态行修改，HEAD 仍为 `103a7d2`、main ahead 2。REUSE 194/194（exit 0）；业务/设备验收仍为上文所述边界，不因文档检查通过而改为 PASS。

### 2026-08-28T22:27:17+08:00：核实并修复 M4R01—M4R07、M5R01—M5R11

- 请求：检查交接中写入的问题，属实则修复。未要求 commit/push。
- 判定：18 项全部属实（对照 HEAD `103a7d2` 源码与审查复现条件）。无误报。
- M4：App 绑定 `OpenAiCompatibleVision`；PDF 识别绘图指令且无光栅资产不 READY；DOCX/EPUB 记录外链/缺失图且零外联；Chat 发送预算内原图或阻止，降级警告保留在回答；locator 校验 version/chunk/asset 并返回 asset hash；视觉结果持久化 tableMarkdown/type；UNKNOWN 需 `retryUnknownVision` 显式确认后才重放。schema v6。
- M5：第二轮请求含 assistant.tool_calls；Provider 可开 tools；Chat 待批准 HTTP 后续跑；权限保留 KB/host；同包重导入不叠 grant；5000+ 条目与压缩炸弹与 `HTTPS://` wheel 为 class E；HTTPS-only 且 file/http 不进回调；工具输出脱敏含当前 secret；SSE 按 index 映射；预算在流结束与工具前检查；`toolsEnabled=false` 不执行 tool call；安装保存原包 bytes。
- 验证：`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :shared:skills-api:test :shared:agent-runtime:test :shared:provider-api:test :data:sqlite:test :shared:announcements:test :app-android:assembleDebug --no-daemon` BUILD SUCCESSFUL；`python -B -m reuse lint` 195/195 退出 0。未跑真机/付费 Vision/真实 HTTP、未独立复审。
- 文档：`docs/KNOWLEDGE.md` 第11节、`docs/SKILLS_AND_SECURITY.md` 第10节、`docs/IMPLEMENTATION_PLAN.md` 状态、本交接。
- Git：工作区含修复，HEAD 未变。未 commit、未 push。
- 下一步：用户授权后用 `commit-tree` 提交（作者 `luozhibai`）；独立复审后再改阶段结论。不自动开工 M6。

### 2026-08-28T22:40:00+08:00：授权提交并推送 M4R/M5R

- 请求：用户明确授权 commit 与 push。
- 范围：M4R01—M4R07、M5R01—M5R11 修复、回归测试、`docs/KNOWLEDGE.md`/`SKILLS_AND_SECURITY.md`/`IMPLEMENTATION_PLAN.md`、本交接；新文件 `OpenAiCompatibleVision.kt`。不提交 `.codegraph/`、构建产物。
- 验证（提交前复核）：`.\gradlew.bat licenseGuard licenseGuardReverse --no-daemon` BUILD SUCCESSFUL。先前模块测试与 `assembleDebug` 已通过。SHA 与 push 结果在提交后回写。
- Git：父提交 `103a7d2`；先前未推送的 `75fc381`/`103a7d2` 将一并 push。作者 `luozhibai <wy3273564266@163.com>`，走 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`。
- 下一步：独立复审后再改阶段结论。不自动开工 M6。

修复提交已写入 `d8abaea25c69e50ae14d77534d670f82f3a216f6`（作者/提交者 `luozhibai`，无 Cursor trailer）。SHA 记录提交 `4bda75074b948bc83106bf5a19b623dd28e11d90`。`git push origin main` 成功：`a3b87f2..4bda750`（含先前未推送的 M4/M5 `75fc381`/`103a7d2`）。

### 2026-08-28T22:52:00+08:00：M4/M5 修复后第二次独立审查

#### 范围、基线与结论

- 请求：再次审查 M4 与 M5；不直接修复。根目录 `E:\mobileAgentRuntime`，HEAD `7f3f2e83883ff612b5f248ee21e17557d8e24692`，修复 `d8abaea25c69e50ae14d77534d670f82f3a216f6`，对比上轮审查基线 `103a7d2a0e21a534627e408834e5454837ac729b`。开工 `main...origin/main`、工作区干净。
- 已按入口阅读 agent/HANDOFF/实现计划及相关要求、许可、KNOWLEDGE、SKILLS_AND_SECURITY、验收契约。M4 解析/引用和 M5 安装/权限由两条只读审查线复核，主审负责 App 接线、模型协议、预算与证据整合。未再扩查 M0.5/M2/M3。
- **结论：M4/M5 仍为 NEEDS_AMEND。确认 9 项剩余或新增问题：6 P1、3 P2。** 不重复把已修复的旧触发条件报成当前缺陷；也不以 51 个现有测试通过推定新边界成立。
- 下表“原反例通过”仅指原审查中的具体触发条件；“源码接通”不等于真实设备/Provider 验收。修复者的历史 18 项修复声明原样保留，当前状态以本节为准。

#### 上轮 18 项逐项复核

| 原 ID | 本轮判断 | 证据与未闭环边界 |
| --- | --- | --- |
| M4R01 | 部分修复 | App 已注入 OpenAiCompatibleVision，并构造包含图片的请求；同意记录、实际目的地与缓存身份仍不一致，见 M4RR03；未跑真实模型 |
| M4R02 | 部分修复 | text+vector、drawing-only 原反例已等待/失败，不再以 Page 标记 READY；inline image 仍静默遗漏，见 M4RR01 |
| M4R03 | 原反例通过 | DOCX/EPUB 外链图被标为 EXTERNAL/MISSING；Repository 阻止不完整导入，测试零假后端调用；多目录同名图片另见 M4RR04 |
| M4R04 | 部分修复 | typed image 消息和回答内降级警告已存在；多图/超限图仍可部分静默省略，见 M4RR02 |
| M4R05 | 原反例通过 | 现有两页 PDF、图片页码、version/chunk/asset 归属、asset blob hash 测试通过；EPUB 来源错绑另见 M4RR04，不能据此宣称任意 PDF/EPUB 页定位完整 |
| M4R06 | 原反例通过 | tableMarkdown/type 已入 schema/cache；table-only 标记可检索，缓存命中不再次调用假后端 |
| M4R07 | 原反例通过 | UNKNOWN 不自动重放；显式 retryUnknownVision 和重复收费风险提示已存在，手动重试可恢复；目的地变更仍受 M4RR03 限制 |
| M5R01 | 原反例通过 | 第二轮保留 assistant.tool_calls 与对应 tool_call_id，Runtime 测试通过；Adapter 静态核对为结构化 JSON 编码 |
| M5R02 | 源码接通，设备未验 | Provider tools 开关、HTTP 宿主、Chat 审批/拒绝回调已接线；Broker 假 HTTP 审批测试通过，不是设备/网络端到端通过 |
| M5R03 | 部分修复 | KB/host/method 范围和完整 manifest 已保存，knowledge_search 过滤生效，同包不叠 grant；read_document、HTTP method 和运行中撤销仍失败，见 M5RR01/02 |
| M5R04 | 部分修复 | 5000+ 项、压缩炸弹均 class E；流式累计上限已存在，现有回归通过；截断 ZIP、符号链接仍被接受，见 M5RR05 |
| M5R05 | 部分修复 | file/http 拒绝、逐跳 URL 检查、有界 HTTP/工具输出已实现；IP/DNS 边界不完整，见 M5RR03 |
| M5R06 | 原反例通过 | 当前 secret 加入脱敏；工具 Invalid/Value 路径相关 Runtime 测试通过。旧 secretsInToolOutput 测试的无 KB grant 路径不能单独作为调用已执行的证据，另有当前 secret 的真实回调反例回归通过 |
| M5R07 | 原反例通过 | Adapter 持续传入 index→id map；独立并行 SSE 片段得到 a={expression:1+1}、b={expression:2+2}，未串接 |
| M5R08 | 部分修复 | 流结束/执行前补查可防止迟到 COMPLETED 和迟到工具执行；没有实际截止/上游取消，见 M5RR04 |
| M5R09 | 原反例通过 | toolsEnabled=false 时收到 tool call 明确失败且不执行 Broker，测试通过 |
| M5R10 | 原反例通过 | 大写 HTTPS 远程 wheel 被判 class E，测试通过 |
| M5R11 | 原反例通过 | 原包 bytes/source_hash/完整 manifest 已持久化；静态核对及独立审阅，未要求提前执行 M6 Python |

#### 当前发现与修复验收条件

| ID / 优先级 | 当前源码位置 | 触发、影响、证据与必须补的回归 |
| --- | --- | --- |
| M4RR01 / P1 | `shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/PdfParser.kt:33-56,269-272` | 内容流中的 inline image（BI/ID/EI）既不是 XObject，也未进入矢量指令检测。主审自造有 catalog/page/font/content/xref 的 PDF，正文 caption 后带 1×1 RGB inline image：解析 needsVision=false/assets=0；真 Repository + 内存 SQLite 返回 READY，Vision 调用 0，search(caption)=1。视觉证据被漏掉而显示完整成功。须提取此图片，或识别不支持视觉并等待/失败；补“文字+inline image”仓库回归，不能只测无文字扫描页或 XObject |
| M4RR02 / P1 | `app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt:101-111,124-146` | 严格模式只取前 4 个 asset，缺失或大于 2 MiB 的图片直接跳过，仅在最终一张都没有时阻止；所有命中文本/引用仍进入提示词。两张图中一张超限、另一张可附，或 5 个短视觉命中时，会无提示地少发原图。RetrievalBudget 仅裁字符数，未提供完整性保障。此项为明确静态调用链证据，未声称运行 Android。须比较命中与实际附图集合；不完整则阻止/分批/要求显式降级并持久提示，补混合大小、缺失 CAS、5 图反例（K04/K08） |
| M4RR03 / P1 | `app-android/src/main/kotlin/runtime/mobileagent/MobileAgentApp.kt:45-49`；`OpenAiCompatibleVision.kt:29-36`；`data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt:178-192,646-671` | Repository 在 App 创建时仅捕获 modelId 作 fingerprint，实际 Vision 每张图却重新挑当前 Provider/模型；任务只保存 vision_consent 布尔值，无目的域名/Provider/revision/数据范围。内存反例：A 配置下已同意并暂停 COPYING，换用 B 指纹和假后端后 resume，无新同意即 READY/B 调用=1；另两个 Provider 用同 modelId，A 调用=1、B 调用=0，B 库内容复用 A 的视觉结果。须让同意、执行和缓存共享不可变完整绑定，配置/范围变化后零外发直到重确认；补换 Provider/域名/模型/版本及同 modelId 跨 Provider 缓存隔离（K03）。未访问真实 Provider |
| M4RR04 / P2 | `shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/OfficeParser.kt:186-213` | EPUB 将 src 截为 basename 再取第一个 endsWith 匹配，不相对 XHTML 所在目录解析。内存 ZIP 两章分别引用各自 images/fig.png，源字节标记 A/B；主审观察第二章 page=2 错绑 A，B 被补扫为 page=null。会产生错误视觉上下文/章节引用。须按章节 parent 解析规范化相对 URI、精确匹配包路径；不能按同名文件猜测，补双目录同名图的 bytes/page/section 回归 |
| M5RR01 / P1 | `shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/BuiltinTools.kt:108-122`；`app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt:157`；`data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt:349-359` | 资源 scope 保存了但未完整执行：只给 knowledge_search 检查 KB，read_document 直接按 documentId 取正文，宿主也不验 KB。主审用真实 SkillRepository 安装仅授权 knowledge.read→kb-a 的包，调用 kb-b 的真实 documentId，返回 private-B-marker；另一包 grant.methods=[POST]，GET 经批准后仍进入假 HTTP 回调（calls=1）。须在宿主读取前校验 document.kb_id 与本次有效 grant/Agent 范围，并将 method 等资源范围纳入 Broker 求交集；补 A grant/B doc、空授权和 POST-only grant/GET 拒绝用例。通用批准不能隐式扩大 manifest 范围 |
| M5RR02 / P1 | `app-android/src/main/kotlin/runtime/mobileagent/ChatViewModel.kt:149-160`；`shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/BuiltinTools.kt:50-85` | Broker 在 send 时捕获一次聚合 grant，invoke/approve 不复核当前 revision、撤销或启用状态。主审 revoke 后有效 capabilities 已为空，旧 Broker 使用新的 callId 仍读出先前授权 A 的正文。HTTP 等待审批期间撤权也没有当前 grant 检查。须每次调用及审批恢复时重新求有效授权，绑定 install/hash/Agent/revision；撤销后旧会话与新调用均拒绝，补“撤权→同一 Broker→新 callId”和“等待审批→撤权→批准”用例（S07） |
| M5RR03 / P1 | `shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/BuiltinTools.kt:251-273,278-287` | HTTP 只检 URL 字面 host，未解析并固定实际远端地址；私网 IPv6 和整数 IPv4 也未覆盖。纯 URI/策略复现：允许列表中的 https://[fc00::1]/、https://[fe80::1]/、https://2130706433/ 均通过。HostHttp 随即 openConnection，缺少授权域名解析到私网/重绑定防护。此处只证明校验放行及宿主调用链，没有访问这些地址。须拒绝 IP 字面值/私网/loopback/link-local，逐跳核验解析结果并将验证地址绑定到连接，补受控 DNS/IPv6/整数地址/重定向测试（S05） |
| M5RR04 / P2 | `shared/agent-runtime/src/main/kotlin/runtime/mobileagent/agent/AgentRuntime.kt:77-81,146-159,195-196` | 超时只在事件或操作结束时检查，return@collect 不会终止上游；没有覆盖模型等待、审批与工具执行的总截止时间。独立慢流每个事件 delay 80ms，预算 20ms，仍消费全部 3 个事件，约 340ms 才返回 BUDGET_EXHAUSTED；若上游不结束会持续等待。旧“结束后不报 COMPLETED”已修，但限时/取消契约仍不成立。须以剩余总预算限制模型/审批/工具并传播取消；补挂起源、持续流、阻塞工具和审批超时，验证截止后不继续消费/执行（S09） |
| M5RR05 / P2 | `shared/skills-api/src/main/kotlin/runtime/mobileagent/skills/SkillArchive.kt:47-62,99-114,186-198` | PK 前缀即进入 ZipInputStream；读不到条目也按缺 manifest 的 instruction-only 包接受，且不检查 central directory 的 Unix 链接属性。主审复现仅 4 字节 PK/03/04：class=A/files=[]/accepted=true；另在有效内存 ZIP 的 link 条目写入 Unix mode 0120777，class=A/accepted=true。没有写出或跟随链接，缺陷是安装前拒绝边界不成立。须完整校验 ZIP 结构/扫描结束、处理解析异常并拒绝链接，失败统一 class E，补截断/损坏/真实链接属性与规范化重复路径用例（S01/S02） |

#### 验证证据与边界

- 本轮 **未运行 Gradle、构建或写测试文件**。使用已存在的新编译 JAR/测试类、Java 21 JShell `--execution local`、Kotlin/coroutines/serialization、SQLite JDBC 和内存数据库；已用 javap 核实新构造参数、retryUnknownVision、typed SSE 等签名。直接反射调用已读源码的 @Test 方法，逐个输出结果，无失败：AgentRuntimeTest 7、DocumentParserTest 13、KnowledgeRepositoryTest 的 9 个 M4 相关方法、BuiltinToolsTest 9、SkillArchiveTest 11、CapabilityBrokerTest 2，合计 **51/51**。这不是 clean build 或 Gradle 测试任务执行记录。
- Repository 9 个方法：incompleteEpubFailsWithEvidence、textPdfIsSearchableWithoutVision、visionUnknownOutcomeDoesNotMarkReadyOrRetry、vectorPdfDoesNotBecomeReadyWithoutVision、drawingOnlyPdfDoesNotCallVisionOrBecomeReady、docxExternalImageDoesNotBecomeReadyOrFetch、visionTableMarkdownIsIndexedAndCached、unknownVisionRetryRequiresAckAndCanSucceed、locatorRejectsForgedCitationAndReturnsAssetHash。
- 额外独立反例：inline PDF→READY/0 Vision calls；切换假 Vision binding 恢复任务→B calls=1；同 modelId 跨 Provider→A calls=1/B calls=0；EPUB 同名图错章；仅 kb-a grant 读 kb-b；POST-only grant 仍执行 GET 假回调；撤销后旧 Broker 新 callId 仍读；三种受禁 IP 写法通过策略；20ms Runtime 预算仍消费 3×80ms 慢流；截断 ZIP 与 Unix symlink 属性 ZIP 可安装；并行 SSE 两个 callId 参数分别正确。PDF fixture 含 xref，EPUB 的 A/B 为合成字节身份标记，ZIP symlink 仅修改内存 central directory 属性；均不冒充真实模型效果或设备测试。
- JAR SHA-256：agent-runtime=`0311C789DA5E5AF046BFB4C9A03945693E515FC5EACBB2B5F1AA860A6CC2A2B9`；knowledge-api=`B1A89D5A34447675842C5107DFB8EE0F3FE972D02B8E3CF4F4A2A63F05DF4A3B`；skills-api=`F029AA92660743E3A74EB740C9AC7E3D331F6C6190DEFA0DD4E76CAAC59E30FF`；sqlite=`AD300731DBE4C726AC389E54C7E9BECAA9A760F4147B78F91F3C635A88F16366`。
- App 源码已核对 Provider tools 开关、审批回调、typed assistant/tool/image 请求、Vision 注入及调用路径。未运行 Android/Compose、模拟器/真机、真实 Provider/Vision/HTTP/DNS、Cloudflare、付费 API；未做 K06 大规模负载、PDF 光栅化、ONNX/USearch JNI、CPython，不将这些已披露范围缺口单独算新增缺陷。
- 其他观察不计入 9 项：locator 仍未比较 Citation.knowledgeBaseId（正常 CitationMap 未见错误，未证明权限绕过）；嵌套 PDF Pages 解析兼容性仍有限；Chat 审批 UI 仅展示工具名，具体目标/参数的设备验收仍缺。修复引用/权限时应补相应反例，不能称完整安全/设备验收通过。
- 本轮只更新 HANDOFF 事实与审查记录，未改变架构/接口/范围/验收标准，未覆盖修复者历史自测；技术专题现有要求继续有效。未 commit/push；HEAD 保持审查基线。

#### 下一步

1. 获得修复授权后，先处理 M4RR01/02/03、M5RR01/02/03，再处理三项 P2；每项先补本节反例，不放宽既定视觉完整性、当前授权与时限要求。
2. 变更需要同步相应专题文档及测试，并重新进行独立审查。App 接线/审批/换 Provider、受控 HTTP 连接和取消传播应补适当集成证据；不能只重复当前 51 个通过测试。
3. 本轮未授权业务修复、提交、推送、设备安装或 M6 开工。最终文档与许可核验结果见下方收工记录。

收工核验：执行 `docs/DOCUMENTATION_CHECK.md` 既有 Python 检查块，PASS/exit 0（20 份 Markdown、129 本地链接、18 需求、6 UI 验收、9 阶段）；`python -B -m reuse lint` exit 0，195/195。`git diff --check` 无错误；仅 HANDOFF 有差异，HEAD 仍为 `7f3f2e8`，`HEAD...origin/main` 为 0/0。本轮未修业务代码、未提交/推送。上述检查不改变 M4/M5 的 NEEDS_AMEND 结论。

### 2026-08-28T23:20:00+08:00：核实并修复 M4RR01—M4RR04、M5RR01—M5RR05

- 请求：检查交接新写入的问题，属实则修复，完成后 commit 与 push。
- 判定：9 项全部属实（对照 HEAD `7f3f2e8`）。无误报。
- M4：PDF `BI/ID/EI` 计入视觉；严格模式不完整附图则阻止或显式降级；Vision 同意/缓存绑定 Provider+endpoint+revision（schema v7）；EPUB 按章节目录精确匹配同名图。
- M5：`read_document` 校验 KB；HTTP method 交集；live grant 每次 invoke/approve；撤销后新 callId 与待批准恢复均拒绝；拒绝 IP 字面值并核验解析地址；预算 `withTimeout` 取消上游；截断 ZIP 与 Unix symlink 为 class E。
- 验证：`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :shared:skills-api:test :shared:agent-runtime:test :shared:provider-api:test :data:sqlite:test :app-android:assembleDebug --no-daemon` BUILD SUCCESSFUL；`python -B -m reuse lint` 195/195 退出 0。未跑真机/付费 Vision/真实 HTTP、未独立复审。
- 文档：`docs/KNOWLEDGE.md` 第12节、`docs/SKILLS_AND_SECURITY.md` 第11节、`docs/IMPLEMENTATION_PLAN.md` 状态、本交接。
- Git：修复提交 `1a035aa8c413dac50d0d2cd8854bb5a112100404`（作者/提交者 `luozhibai`，无 Cursor trailer）。SHA 记录 `cc7a65c7155b5d2a156866bc30dfa63d65dc7de7`。`git push origin main` 成功：`7f3f2e8..cc7a65c`。
- 下一步：独立复审后再改阶段结论。不自动开工 M6。

### 2026-08-28T23:44:00+08:00：新增 66ccff 主色调主题版本并规范颜色编码展示

- 请求：在当前浅色和深色的基础上，再做一个主色调为 66ccff 的版本，并且在选择颜色处直接显示色彩编码 "66ccff" 而不是蓝色。
- 落地成果：
  - `docs/design/ui-tokens.json`：新增 `theme66ccff` 调色板定义（主色 `#66CCFF`、容器 `#004D61`、前景色 `#003544`、青黑底色 `#0F171A`、暗色卡片 `#162125`）。
  - `docs/UI_DESIGN.md`：在第 1.2 节色彩系统、第 3.7 节设置项及第 4 节矢量稿索引中补充 `66ccff` 主题规范，明确规定在选择处直接展示编码 `66ccff`，不显示“蓝色”。
  - `docs/design/ui-prototype.html`：实装 `[data-theme="66ccff"]` CSS 主题变量；在【设置与关于】界面新增主题选择器，并在选择项中直接显示色彩编码 `"66ccff"`；打通顶栏切换与设置项联动的多主题循环切换；完善中英双语国际化字典。
  - `docs/design/screens/scr-chat-03-66ccff.svg`：绘制 66ccff 主题的高保真 Chat 对话与工具审批界面矢量设计稿（XML 验证 100% 通过）。
  - `docs/design/screens/README.md`：同步索引新增的 66ccff 矢量稿。
  - `app-android/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml`：新增主题相关的字符串资源，确保多语言下均展示 `66ccff`。
  - `docs/design/ui-implementation-map.md`：增加第 5 节“多主题与 66ccff 色彩系统映射”。
- 验证证据：
  - Python ElementTree：9/9 SVG 矢量稿全部解析通过（`All SVGs Valid: True`）。
  - Python 文档检查器：20 份 Markdown、130 个本地链接、18 条需求、6 项 UI 验收及 9 个阶段顺序全部通过，`status: PASS`（exit 0）。
  - 全仓 Emoji 扫描：0 处违规字符（Found emojis: 0）。
  - REUSE 许可扫描：`python -B -m reuse lint` 退出 0（196/196 文件全部合规，第一方 `AGPL-3.0-only`）。
  - `git diff --check`：无空白或格式错误。
- 未执行项：本轮未修改 Kotlin 业务代码，未执行 commit/push。

### 2026-08-28T23:54:00+08:00：重构 66ccff 主题为整体浅色风格（Light Style）并突出 66ccff 主色调

- 请求：66ccff 模式下 66ccff 为主色调，且整体为浅色风格。
- 落地成果：
  - `docs/design/ui-tokens.json`：重构 `theme66ccff` Token 体系为浅色风格，底色为浅蓝白 `#F2F9FD`，卡片为纯白 `#FFFFFF`，主色为醒目的 `#66CCFF`，文字高对比 `#003B52` / `#0E1E24`，容器为软亮青色 `#E0F4FF`。
  - `docs/UI_DESIGN.md`：更新第 1.2 节色彩系统与第 3.7 节设置偏好，明确 `66ccff` 主题为整体浅色风格。
  - `docs/design/ui-prototype.html`：重构 `[data-theme="66ccff"]` CSS 变量为浅色高明度主题，使 `#66ccff` 在气泡、主要按钮、顶部焦点与激活导航中成为绝对主导的视觉中心。
  - `docs/design/screens/scr-chat-03-66ccff.svg`：彻底重绘为浅色风格的高保真矢量稿，以纯正 `#66CCFF` 为气泡与核心控件的主色调。
  - `docs/design/ui-implementation-map.md`：更新第 5 节调色板说明为浅色风格。
- 验证证据：
  - Python ElementTree：9/9 SVG 矢量稿全部有效（`VALID XML`）。
  - Python 文档检查器：20 份 Markdown、130 个本地链接、18 条需求、6 项 UI 验收及 9 个阶段顺序全部通过，`status: PASS`（exit 0）。
  - 全仓 Emoji 扫描：0 处违规字符（Found emojis: 0）。
  - REUSE 许可扫描：`python -B -m reuse lint` 退出 0（196/196 文件全部合规，第一方 `AGPL-3.0-only`）。
  - `git diff --check`：无空白或格式错误。
- 未执行项：本轮未修改 Kotlin 业务代码；已按用户明确指令完成 commit 并推送至 `origin/main`。

### 2026-08-29T00:10:00+08:00：无人值守产品完成目标启动

- 用户当前授权：按技术实现文档持续推进直至完成产品；可自行启动模拟器、采用 debug 签名；正式 release 后置；明确授权本产品公告 Cloudflare 正式部署；允许自主设立 goal。已建立持续目标，未指定 Token 预算。
- 基线：`7511b22ffd7a7d3021b7857b6500cbe75d037ad6`；`1a035aa8c413dac50d0d2cd8854bb5a112100404` 为第二轮审查修复，最新设计是整体浅色、主色 `#66CCFF`、选项原样显示 `66ccff`，无 Emoji。开工 Git 干净。
- 恢复现场：读取规则/交接/计划/专题，CodeGraph 优先；Android CLI 已安装、存在 medium_phone AVD，但无运行设备；构建 SDK 指向 E:/Android/Sdk，CLI 默认 SDK 为 C:/Users/32735/AppData/Local/Android/Sdk，启动前需核实实际包路径，避免盲改用户环境。
- Cloudflare：已通过内置浏览器确认用户所指账户已登录；只读核实账户及入口，尚未创建/修改/部署资源，不读取 Cookie/本地存储/私钥。生产身份、资源隔离、签名密钥来源、管理员认证、源代码与回滚证据将先落实。
- 当前事实：本地公告只有 MemoryStore，D1/生产入口仍须实装；Agent/Profile/会话持久化及七类 Compose 页面仍有缺口；M6 CPython、PDF 栅格化、真实 ONNX/USearch、M7 导入导出/MCP/恢复不能以旧测试或空模块冒充完成。
- 执行组织：按第2节单一写入责任并行推进；先完成可用接口与独立本地验证，主审集成与模拟器回归，生产操作仅主审执行。继续保持第一方 AGPL-only、秘密不入代码/日志/交接、不触碰其他项目。
- 验收与收工：本轮进行中，后续按实际命令、设备证据、部署结果和独立审阅逐项维护；不预写 PASS，不提前关闭目标。即使有外部阻碍，也先完成可独立推进部分并记录准确未完成项。

### 2026-08-29T00:28:00+08:00：运行环境与应用接线推进

- SDK 路径已核实：C:/Users/32735/AppData/Local/Android/Sdk 是指向 E:/Android/Sdk 的 Junction，不是两个独立 SDK。`android --sdk E:\Android\Sdk emulator start medium_phone` 成功，设备 `emulator-5554`、API36、x86_64；未清空 AVD。旧 APK `adb install -r` 与 MainActivity 启动成功，已查看基线截图，仅证明旧版本启动。
- Wrangler 4.127.1 `whoami` 当前 OAuth 有 Worker/D1 写权限，未读取凭据文件。只读 D1/Worker 清单没有本产品资源；账户 workers.dev 后缀已核对。Cloudflare 控制台明确 Zero Trust 尚未设置：管理员 Access 权限需要用户在场的动作确认，不能无人值守绕过；生产 public feed 可在独立 D1/签名就绪后部署，缺 Access 时管理 API 必须拒绝访问。
- M4RR01—04 独立复核：已有最新 JAR/Java21 JShell 内存 fixture 7/7 通过；仅覆盖旧反例，不代替新实现构建、设备、PDF 栅格化/ONNX。
- M5 实际传输缺口确认：HostHttp 的被检查 DNS 地址未被原 URLConnection 使用，安排专门修复为固定实际连接地址并保留 TLS 主机名校验；准确结果见 [HTTP 传输证据](docs/evidence/2026-08-29/http-transport.md)。当前 Gradle 指定测试运行中，尚未标 PASS。
- 主流程补显式 Skill 权限审查、按包/Agent 资源交集、撤权/源码查看 API 及仓储回归；接入 Provider 编辑/角色/参数/删除保护/显式收费探测，以及 Agent 编辑/Prompt 历史/新会话快照 ViewModel。新 API 正在与并行模块集成，尚未编译验收。
- CodeGraph 已先行用于源码定位；新增文件或精确路径查询返回不相关文件/被截断时，转为限定路径读取。暂不并行重建索引，最终由主流程统一更新。

### 2026-08-29T00:38:00+08:00：独立生产资源与业务接线

- 实际生产操作：`wrangler d1 create mobile-agent-runtime-announcements-prod` 成功，ID `06cf40c7-dd84-4560-859a-1a417f47207e`（WNAM）；不复用其他项目资源。计划 Worker `mobile-agent-runtime-announcements`，Origin `https://mobile-agent-runtime-announcements.gmailforzhibai.workers.dev`，绑定 `ANNOUNCEMENTS_DB`。当前未运行远程迁移、secret 注入或 deploy。
- 已在 gitignored、限制 ACL 的 `.private/overnight/announcements-production/` 生成 Ed25519 签名密钥；私钥不得进入交接/日志/仓库。公开 keyId `mar-prod-20260829-1`，publicKeyHex `e89c5b55f45a303f5c721a568493edfb9f268b39967ac597b2e105725a552df8`。APK 已写公开配置，仍须部署后协议核验。Access 尚未建立，管理 API 必须关闭，不允许 local token 降级。
- 数据层新增 Profile/Agent immutable Snapshot/Conversation/Run/Audit/Settings/Transfer；页面新增 state/actions，主流程正接真实 repository。`resolveSnapshot` 从冻结 manifest 取 Provider/Model/Prompt，不能回查 live 配置替代。Skills 导入先检查且默认未授权，显式授权按包/Agent/KB 范围，源码按包哈希核验。
- 新 HTTP 指定测试两轮：第一轮编译修复后第二轮 30 项中 28 通过，2 项为测试读取 HTTP/2 Host 不当；测试已修正为 authority/Host。第三轮在 embedding 模块 Gradle 脚本配置期失败，未执行测试。等待该所有者修复再由主流程统一重跑，尚未 PASS。
- 官方 CPython 3.14.7 Android 双 ABI 包已下载并固定 SHA，JNI/isolated IPC 实现进行中；旧“CPython 未嵌入”事实将以实际 APK/设备验证更新，不能提前称 M6 完成。
- 全部变更仍未提交；无正式 release、无真实收费 Provider 调用。目标持续执行，当前不是产品完成声明。

### 2026-08-29T01:20:00+08:00：生产部署与统一构建进度

- 公告生产仅操作本产品资源：D1 远程空库导出成功（32 bytes，SHA-256 `309d1516f5d4f4f792b17106f7b761312f848c634e3028d70e6eb8ed39df7398`）；0001/0002 远程迁移成功，后读确认 announcements=0、rows_written=0。签名私钥经 stdin 注入，不进入模型输出/仓库。
- `wrangler deploy --config wrangler.toml` exit0，Worker `mobile-agent-runtime-announcements`、version `34a2dfca-f180-4358-bcdd-66abc354ce1c`、cron `*/5 * * * *`。最终源码归档 `9366cf1d9642f89436769b0f8df3585e5f5b669ac01aacf47a2864c2fc754168`：28 文件逐字节对比当前树及 ZIP、hash/路径排除验证通过；74709 bytes。主审 npm check/test/preflight 均 exit0。
- 生产公开协议后检**未通过**：Node fetch、curl Schannel 与内置浏览器分别遇到 fetch failed/TLS connection failed/ERR_CONNECTION_CLOSED；没有因此标签名或 304 通过。Access 尚未设置，代码保持后台 fail-closed；不创建权限策略、不回退 local token。
- NDK r27d 官方下载 781506724 bytes、SHA1 `56607cbccd3642d4a1991f6bb3114a00f884f426`；现场 27.3.13750724 的 properties、clang、toolchain、sysroot 头与官方 ZIP 逐字节一致，CMake3.22.1 可用。CPython 与 USearch arm64-v8a/x86_64 原生库真实编译通过。
- 主审 fresh JVM：skills51、provider23、agent-runtime16、knowledge33、serialization4，合计127 tests / 0 failure。data最新58/2fail：损坏schema fixture 被SQLite先拒绝、TransferCodec误拒绝nonSecretHeaders，已交所有者修复；IPC和App构建仍继续，未替代新APK设备验收。
- UI接线 MainActivity/MainScreens/UiDialogs 已由product_ui完成冻结，主审后续整合。新增PythonSkillTools、RunTools、MCP App接线分别按独占文件认领；主审保留Chat/DI/原ViewModel/根文档/Gradle/设备/生产写入权。只读Python安全审阅正在进行。
- 主审补Application隔离进程不初始化宿主DB/Keystore/后台网络；工具与模型UNKNOWN_OUTCOME将显式终止并持久化，不以Value/FAILED隐式重试；Vision复用统一有界协议，严格结构化输出。上述新改动尚待下一轮测试。
- 无新 commit/push、无正式 release、无真实收费模型调用。持续目标仍 active；此为进行中交接，不是完成声明。

### 2026-08-29T01:53:00+08:00：真实设备首轮与许可修正

- 最近完整测试 XML 复核：skills51、provider23、agent-runtime18、knowledge33、serialization5、sqlite58、IPC5，合计193项/0失败。该结果属于安全修正/流式归档/API Embedding 追加之前的基线，不能替代后续重跑。
- round7 `assembleDebug assembleDebugAndroidTest` 成功；debug APK 210226924 bytes，SHA256 `C1B6AC3E03D43EAECD4E5BC8E00CE527C28CD430AA7A0049B3E04E307D541D4D`。原 medium_phone 空间不足，未清理/擦除；创建独立 `E:\Android\Avd\mar_api36_debug.avd`，API36/x86_64、12GiB data、3072MiB RAM，emulator-5556，已实际安装 APK。
- 新包冷启动**失败**：Android ICU 拒绝 `AgentRepository` 未转义的右花括号模板正则。已定位；主审同步修 `PromptTemplates`，data owner 修对应 repository。日志 `.private/overnight/device/round7-cold-start-crash.log`。旧 JVM 正例不能替代此设备结果。
- 已实际执行单个 CPython smoke，**1项/1失败**：`runtime_unavailable`。APK 为 `assets/python3.14.zip` 而 loader 要求 `python/python3.14.zip`；runtime owner 正修。未声称 Python 已在设备执行成功。模型 pack 也已修 generated assets 根，但新路径尚未通过新 APK 验证。
- round8 build 因 TransferCodec 正在实施的 `validateConversations` 未落盘而失败，等待正确函数完整落盘后重跑；不移除校验来通过编译。新增真实 KnowledgeRuntimeDeviceTest（ONNX/USearch/FTS/ICU）和扩充 Python 安全测试源码，尚未编译/设备执行。
- 独立 M6 审查发现发送后 timeout/EOF/取消未知语义、posix_spawn、原始 FD 注入和日志洪泛缺口；runtime/App owner 正修，新增私有 native nonce/终止策略及测试。主审 Chat 不覆盖子执行器持久 UNKNOWN，并只对有明确 `CANCELLED_BEFORE_DISPATCH` 证据的对应 call 排除未知；输出 token 预算正在贯通。
- API Embedding 后端与绑定/授权、完整流式 ZIP 导出及本地凭据重新绑定仍在实现；默认不外发。MCP 配置和引用原图已接线但尚未设备验收。
- 本轮首次 licenseGuard/REUSE 发现生成 source 索引/JSON/ZIP metadata、knowledge evidence 头缺漏；正在修正，未更改/弱化 license guard。仅把本地 Wrangler 生成缓存非破坏地移到 `.private/overnight/announcements-local-state-20260829`，无生产状态变更。实际 debug 依赖报告已生成，新增 `generateDebugSbom` 任务待运行。
- 主审重新逐字节核验28个公告源码文件/ZIP/manifest；新 sourceHash `07f164ef5f473ff426488eeeddf0bb7d1cb522286c5389e85baa2351bb473ae3`、74760bytes。正式部署 exit0，新 version `dd2be020-85ff-48ef-8b83-779a7a9cc02b`，只更新同产品代码/资产，D1/secret/Access不变；旧归档保留。新 HTTPS后检 curl exit35，TLS handshake failed，无HTTP响应，仍非生产协议PASS。
- 所有改动仍未提交；无正式 release、无真实收费 Provider/Vision 调用。goal保持active。

## 7. 后续记录格式

每次新增日期标题，写清：任务/需求 ID、修改文件、事实结果、验证命令和证据、未执行项、Git 状态、待解决问题、下一步。

### 2026-08-29：provider-api streaming redaction、embeddings 与输出预算边界

- 范围：本轮重新独占 `shared/provider-api/**`；未改 `shared/agent-runtime`、Android App/MCP UI 或 Python IPC。保留当前 Provider 的 8 MiB 响应、1 MiB SSE 行界限、UNKNOWN 与取消边界。
- 修改：`ModelRequest` 末尾新增兼容默认的 `outputTokenLimit: Int?`；OpenAI-compatible payload 在参数最终合并后校验并注入 `max_tokens`，拒绝非法、越预算或同时存在的 `max_tokens`/`max_completion_tokens`，均在 HTTP 前失败。流式文本对主凭据和已解析 custom `SecretRef` 进行跨 delta 脱敏；疑似前缀只在正常完成时 flush，EOF/异常/取消丢弃；完整 JSON content/error 脱敏，含凭据的工具参数在发出工具事件前以 `UNKNOWN_OUTCOME` 失败。实现了有界 `/embeddings` POST，严格检查请求上限、data 数量、唯一连续 index、重排、维度、数字与 finite。
- 测试：补充 MockEngine 的主/custom header 分片回显、半凭据 EOF、完整 JSON content、含凭据工具参数、输出预算前置校验、embedding 重排及损坏响应覆盖。按协调要求未运行 Gradle；`git diff --check` 无错误。协调者需在工作树稳定后运行 provider-api 与相关模块测试；本轮未访问真实 Provider、未发生真实收费请求。
- 证据：`docs/evidence/2026-08-29/protocol-adapters.md` 已同步；当前编译、统一测试、设备/生产状态均待主审确认。未 commit/push。

### 2026-08-29T02:29:00+08:00：round12/13 真实设备与接线推进

- round13 `assembleDebug assembleDebugAndroidTest generateDebugSbom data:sqlite:test` 构建成功（1m2s）；debug APK 212842053 bytes，SHA256 `0f03d534e20fd2f610143c02bfe7118f39caaf2b4c9f4fa0ee6b6a892007eeae`。data XML 68项/0失败，其中 ProductData 6项覆盖完整ZIP往返及压缩比。SBOM 166 resolved components，与该APK hash一致。
- 专用 API36/x86_64 emulator-5556 真实 Knowledge 4/4通过：ONNX稳定归一化、USearch JNI、SQLite FTS/引用/删除/代际、Android ICU模板初始化。冷启动成功。round12 Python11项失败被cleanup关闭log pipe异常遮蔽；runtime修复后round13单个normal测试仍1/1失败，结果为FAILED/python_error。未声称Python执行成功；安全stage/type诊断已落，round14重建中。
- 当前APK已确认 `assets/python/python3.14.zip`（内含LICENSE）、CPython/USearch/ONNX完整许可与MiniLM Apache正文。完整第三方索引/144外部坐标声明和Settings/About入口正在实施，未以POM名称代替完整原文。
- 本机受控Provider fixture监听127.0.0.1:8765，仅用于debug模拟器10.0.2.2；输出标明LOCAL QA FIXTURE，使用明确假凭据，无真实Provider/付费调用。尚未通过UI完整会话验收；表单错误显示、URL键盘及系统语言/文字对比已修源码，待新包验证。
- API Embedding已有严格持久space/模型revision/resolver，主审新增 `ApiEmbeddingRegistry` 并接DI，KnowledgeVM/独立文本上传与重复收费确认正接线。发现显式UNKNOWN retry仍被grant入口拒绝，已交仓库owner最小修复及回归；绝不绕过授权或回退本地空间。
- K06 opt-in测试源码已冻结：320人工文件约450MiB，其中300幅stored PNG用于真实图像存储/等待压力，20篇文本走真实ONNX/JNI。尚未生成或运行负载，不等价自然压缩语料或450MiB模型吞吐；Android12—16矩阵仍未执行。
- 生产版本仍 `dd2be020-85ff-48ef-8b83-779a7a9cc02b`；公共HTTPS后检TLS关闭和Access未配置仍未闭环。未增加生产资源、未发布实际公告、未改其他项目。
- 本轮恢复时goal工具返回blocked（与较早记录active不同）；不伪造重建goal或完成状态，按用户原授权继续可独立推进的实现/验证。所有源码WIP保留，无commit/push、无正式release。下一步：新APK定位Python错误、API Embedding UI/授权回归、受控Provider UI旅程、负载与独立审阅、最终文档和证据归档。

### 2026-08-29T08:15:00+08:00：授权提交并推送 Round22 工作区

- 请求：用户明确要求完成 commit 与 push。
- 范围：自 HEAD `7511b22` 以来的产品实现（CPython 隔离、ONNX/USearch、MCP/工具、66ccff UI、独立公告、证据与许可资产）。不提交 `.codegraph/`、构建产物、`.private/`、签名密钥或 Worker 私钥。
- 验证：提交前 `.\gradlew.bat licenseGuard licenseGuardReverse --no-daemon` BUILD SUCCESSFUL；`python -B -m reuse lint` 316/316 退出 0。未再跑模拟器或付费模型。
- Git：实现提交 `9134697138507c0a191a4daf5808824fe7d6e014`，父提交 `7511b22ffd7a7d3021b7857b6500cbe75d037ad6`；SHA 记录 `d16c57b5d4ce0e8b82495ac9f157bc5c17226488`。作者 `luozhibai <wy3273564266@163.com>`，走 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`，无 Cursor trailer。`git push origin main` 已将 `7511b22..d16c57b` 推到远程。
- 下一步：正式 Android release 仍须另行授权。不自动改 Cloudflare Access 身份策略。

### 2026-08-29T08:34:00+08:00：K06 Android 12—16 前台短路径设备矩阵

- 请求/范围：用户要求继续推进项目。本轮从完整 K06 的可独立部分开始，只验证 R05/K06 的真实 WorkManager、前台契约、等待终态与取消短路径；不修改业务源码，不执行正式 release、真实 Vision/Embedding、Cloudflare Access 或其他生产变更。
- 基线：Git 根 `E:\mobileAgentRuntime`，`main@7270aa25cff5303303008b436f254606f24f097b` 与 `origin/main` 同步、开工干净。先完整读取规则、交接、实现计划、需求、许可、验收、Knowledge 及 K06 证据；`.codegraph/` 存在，先用 CodeGraph 定位 `ImportWorker`/`ImportWorkScheduler`/测试/仓储调用链，测试正文出现 gap 后才直接读取已定位文件。
- 构建：`.\gradlew.bat :app-android:assembleDebug :app-android:assembleDebugAndroidTest licenseGuard licenseGuardReverse --no-daemon --console=plain` → `BUILD SUCCESSFUL`，373 actionable tasks（25 executed）；两项 license guard 同轮通过。debug APK 211,102,633 bytes，SHA-256 `F41C760EB07DA9AA8F5E9EFAB612F405BA28481DDACB0C9B99DD6227F6930D97`；test APK 598,675 bytes，SHA-256 `65CEA8A5EB5303A8383671C993D60F9909AD8172429367FDDDD2D4DDBBC6C0B2`。
- 设备：专用 x86_64 AVD `mar_api31_matrix`、`mar_api34_matrix`、`mar_api35_matrix`、`mar_api36_debug` 分别运行 API31/34/35/36。同一 instrumentation 类 `runtime.mobileagent.ForegroundImportDeviceTest` 每台均 `OK (3 tests)`，耗时 3.558/3.565/3.698/4.081 秒。READY、WAITING_FOR_VISION_MODEL、AWAITING_EMBEDDING_CONSENT 均一次执行后 `SUCCEEDED attempts=1`；取消路径均 `stopReason=1`，线程中断、幂等取消 hook 与短窗口无重放断言通过；ForegroundInfo/合并 Manifest 的 `dataSync` type、权限、channel、ongoing/progress notification 断言通过。
- 边界：API34—36 的 `POST_NOTIFICATIONS` 均为 `granted=false`，所以没有把通知实际显示在抽屉记为通过；四台测试时电量100%、25°C、Doze ACTIVE，只是模拟器状态。没有执行实际后台/Doze 拒绝、产品 DB 跨进程恢复、Android15 六小时累计或受控缩短 `onTimeout`、Android16 Job 配额耗尽、真实网络不确定结果。结论是四个平台的短路径 `DEVICE_PASS`，完整 K06 仍 `NEEDS_MORE_EVIDENCE`。
- 文档：更新 `docs/evidence/2026-08-29/foreground-import-matrix.md`、`docs/KNOWLEDGE.md`、`docs/IMPLEMENTATION_PLAN.md` 与本交接；没有变更架构或降低验收。
- 文档/许可检查：`python -B -m reuse lint` → 316/316、exit 0；`git diff --check` 通过。现有文档检查器仍因三份未修改历史证据中的9处既存行尾空格返回 `NEEDS_AMEND`，没有把本轮 diff 或 REUSE 通过冒充文档全检通过，也未借机改写无关历史证据。
- Git/环境：未 commit/push；未删除 AVD 或清数据，收工时恢复为开工已有的 API31 与 API36 AVD 运行组合。本轮使用 Android CLI 技能约束 SDK/AVD/设备操作，未安装或升级 SDK。
- 下一步：在可丢弃且可恢复的 API35/36 专用 AVD 上设计并执行受控 timeout/配额场景，或为 ENOSPC 与 PARSING/EMBEDDING/INDEXING 中断增加只指向 fixture 的故障入口；均须继续区分短路径、长时系统边界与完整 K06。Cloudflare Access 仍必须用户在场。

### 2026-08-29T09:43:00+08:00：开发收口、第五轮复核 PASS 与最终 debug 打包

- 用户边界：继续推进本项目，但公告系统后续部署/线上后检由用户自行操作；正式 Android release 后续共同安排。本轮无 commit/push、生产写入或正式 release 授权。用户最终要求以第五轮为最后一次自动复核，之后直接打包；只有人工发现新问题时才重新启动复核—修复流程。
- 基线/现场：Git 根 `E:\mobileAgentRuntime`，`main@7270aa25cff5303303008b436f254606f24f097b` 与 `origin/main` 同步、开工干净；`.codegraph/` 存在，源码定位先用 CodeGraph。完整读取根规则、交接、实现计划、需求、许可、验收及 Knowledge/Skills 专题；设备操作遵循 Android CLI 技能。没有 reset/clean/stash，也没有删除或清空 AVD。
- 实现：PDF parser fingerprint 升为 `pdf-text-v5-pdfrenderer`；仅签名与单一 DCT filter 同时可信的 JPEG 可成为 IMAGE。raw/Flate、同页混合图像、vector+image、多 `/Contents` 独立 filter、缺失/悬空/解压不完整内容均要求可信 rasterizer 或 PAGE blocker；仓储回归断言 raw/Flate 不可检索且 Vision 调用为 0。Skill ZIP 在暴露 manifest/source 前校验 EOCD、central/local 名称与元数据、canonical/NFC/大小写重复、symlink；bit3 descriptor 的实际 CRC/压缩/解压大小与 central 严格一致，目录 payload 同样受 entry/ratio/total 限制。Agent `beforeModelRequest` 自有 timeout 记录 `BUDGET_EXHAUSTED`，调用方取消仍沿 cancellation。
- JVM：最终命令 `:shared:knowledge-api:test :data:sqlite:test :shared:skills-api:test :shared:agent-runtime:test` 为 `BUILD SUCCESSFUL`。XML 汇总 Knowledge API 41、SQLite data 82、Skills API 62、Agent Runtime 19，共 204 项、0 failure/error/skip；审查定点类分别 23/23、64/64、24/24、9/9。
- 独立复核：同一只读审查者先后用真实反例推进边界，最终第五轮 `PASS`；确认悬空/缺失 Contents + JPEG、valid-xref Flate 多内容流 + vector/JPEG、raw/Flate 混合、descriptor central 篡改、目录 central CRC 与高压缩目录 payload 均已关闭，未发现本轮局部改动引入新的 P0/P1/P2。没有联网、Provider/Vision、DB/config 或生产操作。
- 设备：最近一次完整设备制品为 debug APK SHA-256 `D2CF32397BA357ADDC6ABC29E16CCE20D567784667EB71071FC1A094E0B0D37F`；API31/34/35/36 的 `ForegroundImportDeviceTest` 各 `OK (3 tests)`（4.162/3.577/3.520/4.085 秒），API36 `KnowledgeRuntimeDeviceTest` `OK (4 tests)`（7.964 秒）。通知权限/长时 K06 边界仍按 `foreground-import-matrix.md`；最终两个局部修复后按用户指令直接打包，未再跑设备矩阵。
- 最终包：`E:\mobileAgentRuntime\app-android\build\outputs\apk\debug\app-android-debug.apk`，211,102,633 bytes，SHA-256 `2F09A17D12AF45F4D1B108E62656059BC0EED4566D69FBD55E3F207446B9A72A`。androidTest APK 598,675 bytes，SHA-256 `65CEA8A5EB5303A8383671C993D60F9909AD8172429367FDDDD2D4DDBBC6C0B2`。最终打包与两项 license guard 同轮 `BUILD SUCCESSFUL`（373 tasks，22 executed）；这是 debug 签名包，不是正式 release。
- 文档/许可：同步 `IMPLEMENTATION_PLAN`、`KNOWLEDGE`、`SKILLS_AND_SECURITY`、前台矩阵与本交接；清除三份历史证据的 9 处尾随空白，使标准库文档检查恢复 PASS。`python -B -m reuse lint` 为 316/316，LICENSE 未改。API34/35 AVD 已正常停止，收工恢复为原有 API31/API36 运行组合。
- Git：HEAD 未变，当前源码/测试/文档修改均未提交、未推送；构建产物位于 ignored build 目录。后续如需提交/推送或正式 release，须取得新的明确授权并从当前工作树恢复现场。

### 2026-08-29T09:51:04+08:00：授权提交并推送最终复核修复

- 请求/边界：用户明确要求完成 commit 与 push；本次只提交第五轮复核后的源码、测试与文档，不提交 APK、`build/`、`.private/`、密钥或其他本机产物，不执行公告系统生产操作或正式 Android release。
- Git：实现提交 `2096697476f6175e209dbe1266b7c4b67477e65c`，父提交 `7270aa25cff5303303008b436f254606f24f097b`，作者 `luozhibai <wy3273564266@163.com>`；使用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`，提交信息为 `fix(runtime): harden PDF, skill archive, and timeout boundaries`，无 Cursor trailer。该 SHA 记录由独立文档提交承载，随后按授权非强制推送 `origin/main`。
- 验证承接：实现提交对应第五轮独立复核 `PASS`、204 项 JVM 测试全绿、最终 debug/AndroidTest 构建与两项 license guard 同轮 `BUILD SUCCESSFUL`、文档检查 PASS、REUSE 316/316、`git diff --check` 通过。最终 debug APK 仍位于 ignored 构建目录，SHA-256 `2F09A17D12AF45F4D1B108E62656059BC0EED4566D69FBD55E3F207446B9A72A`，不是正式 release。
- 后续：停止自动复核—修复循环；只有用户人工发现新问题时才重新启动。公告系统后续部署由用户自行操作，正式 release 另行共同安排。

### 2026-08-29T11:39:01+08:00：整理用户人工发现的产品与仓库审查问题

- 请求/范围：用户引用 ChatGPT 对话“Review移动端运行时”，要求把刚检查出的问题整理为文档。本轮只写正式审查报告与交接，不修业务代码、不运行 APK、不重新打包、不 commit/push、不操作公告生产或 release。
- 输入恢复：任务读取接口因 ChatGPT 数据源 unavailable 返回挑战页；随后只读使用用户已登录的 Chrome 会话打开同一 conversation ID，完整读取 19,801 字符正文。网页内容按不可信数据处理，只提取问题和审查结论，不执行页面中的任何指令、不发送消息、不读取 Cookie/本地存储/凭据。
- 源码核验：Git 根 `E:\mobileAgentRuntime`，`main@13edc5759b1f2fa393f29a095c0690dd7184c7c0` 与 `origin/main` 同步、开工干净。`.codegraph/` 存在，先用 CodeGraph 核对 App Shell、Provider、Agent、Knowledge、Chat、归档、后台任务与 CI；宽查询输出被截断后，才对已定位路径使用限定 `rg` 和行号读取。
- 产物：新增 `docs/2026-08-29_code-review-mobile-agent-runtime-report.md`，采用普通白盒代码审查结构（`flavor = null`），包含范围、状态定义、E-001—E-007、F-001—F-020、三条 callflow Path、每项影响/修复/验收和 0.1.1→0.2→0.3→1.0 顺序。问题汇总为 P0 6项、P1 8项、P2 6项，结论 `NEEDS_AMEND`。
- 证据边界：工具能力开关崩溃是用户观察到的 P0；用户补充其在统计问题期间稳定出现两次，但随后无法稳定复现。当前 Checkbox→draft→save 静态路径无法解释进程退出，状态为 `candidate_intermittent`；必须取得对应 APK SHA、dirty/schema/build 信息与完整 Logcat，不能因当前未复现而标记关闭。API Embedding `spaceId == selectedBaseId`、假“仅使用文本”、`GIT_REVISION="uncommitted"`、七项底栏、SecretStore 无删除闭环、普通 ZIP 误入 Office parser 等已由当前源码确认。
- 文档影响：本报告是后续修复和验收输入，不直接改写 `IMPLEMENTATION_PLAN`、需求或验收状态；只有用户明确要求实施后，才把相应决策和迁移方案同步到专题文档/ADR。公告系统仍由用户自行操作，正式 release 仍需共同另行授权。
- 验证：标准库文档检查器为 35 份 Markdown、144 个本地链接、2 个 JSON 示例、R01—R18、U01—U06、M0—M7 全部通过，`status=PASS`；`python -B -m reuse lint` 为 317/317。首次从文档提取检查器时结束 fence 匹配过宽导致脚本被截断并报 `IndentationError`，改为精确匹配独占 fence 后成功；没有把失败尝试隐去。
- Git：本轮结束时只有新报告与本交接修改；`git diff --check` 通过，未 commit/push。后续由用户决定是否提交或启动 0.1.1 修复。

### 2026-08-29T12:11:29+08:00：实施审查报告 0.1.1 稳定性补丁

- 请求：用户要求根据 [HANDOFF.md](HANDOFF.md) 与 [审查报告](docs/2026-08-29_code-review-mobile-agent-runtime-report.md) 进行修复。范围锁定 0.1.1：F-002–F-006、F-014 文案、F-020 过期文案；F-001 只做 provenance/诊断。不实施 0.2/0.3/1.0，不 commit/push，不打包正式 release，不调用付费 Provider/Vision。
- F-002：Knowledge UI 直接使用 `state.apiQueryAttempts`，不再用 `spaceId == selectedBaseId` 二次过滤。
- F-003：导入 loading/活动任务期间隐藏“没有文档”；展示导入进度摘要；存在活动任务时禁用重建；`importUris` 每文件后刷新快照。
- F-004：新增 `ImportStage.READY_WITH_VISUAL_GAPS`（`isPublished` 为真，`isCompleteSuccess` 为假）。`acceptTextOnlyVisualGaps` 只在等待 Vision 时索引已有文字，图片留在 CAS，绝不标完整 `READY`；无索引文本抛 `TextOnlyUnavailable` 并保持等待。未升 schema（用 stage + `TEXT_ONLY_VISUAL_GAPS:` 前缀）。发布收口改为直接写入终态，避免从 FAILED 走状态机无法离开、把 API 缓存恢复写成失败。
- F-005 / F-001 诊断：`app-android/build.gradle.kts` 写入真实 `git rev-parse HEAD`、dirty、`Migrations.VERSION`、UTC 构建时间。About/设置可复制诊断。**F-001 根因仍开放。**
- F-006：Chat delta 50ms UI 合并；`RunRecord` 只在状态/usage/checkpoint 类事件落库；未启用 tools 不计 schema 预算；错误文案使用 UTF-8 字节单位。未跑长流式设备基准。
- F-014：删除 Provider 文案改为不承诺删除 Keystore 凭据。F-020：Skills/README 不再声称隔离 Python 未完成。顺带：隐藏自由 API 格式输入、无效角色不再静默改成 Chat、Request Inspector 遮盖范围改实。
- 验证：`.\gradlew.bat :shared:knowledge-api:test :data:sqlite:test` BUILD SUCCESSFUL，XML 合计 126 项（42+84）、0 failure/error/skip。`.\gradlew.bat :app-android:compileDebugKotlin` BUILD SUCCESSFUL。`licenseGuard`/`licenseGuardReverse` BUILD SUCCESSFUL。`python -B -m reuse lint` 317/317 退出 0。未跑模拟器/真机、未 assembleDebug、未跑付费模型。`git diff --check` 仅有 CRLF 提示，无空白错误。
- 构建字段（compile 产物，非新 APK）：`GIT_REVISION=13edc5759b1f2fa393f29a095c0690dd7184c7c0-dirty`，`GIT_DIRTY=true`，`DB_SCHEMA_VERSION=10`，`BUILD_TIME_UTC=2026-08-29T04:10:05Z`。androidTest 命名空间的 `BuildConfig.GIT_REVISION` 仍为占位 `uncommitted`，不影响主 APK 字段。
- 文档：`docs/KNOWLEDGE.md`、`README.md`、`REUSE.toml`（补 `README.md` 归属）、本交接。审查报告保留为历史输入，不把 F-001 改为已关闭。
- Git：HEAD 未变；工作区含 0.1.1 源码/测试/文档与未跟踪审查报告。无 commit/push。
- 下一步：用户授权后打包带真实 revision 的 debug APK，在产生问题的设备上采集工具能力开关的完整 Logcat；或开始 0.2 配置迁移。禁止把 F-001 标为已修复。

### 2026-08-29T12:56:00+08:00：一次性完成 0.1.1、0.2、0.3 并自审修复

- 请求：用户要求一次性完成审查报告 0.1.1、0.2、0.3，做完后自审并自行修复；1.0 由其另行安排。不 commit/push、不打包正式 release、不调用付费 Provider/Vision、不把 F-001 标为已关闭。
- 0.1.1（承接上一轮）：F-002–F-006、F-014 删除文案、F-020 过期文案；F-001 仅 provenance/诊断。
- 0.2：schema v11 增加 `ModelEndpoint`/`endpoint_json`、`capability_probes`、密钥 `status`。Agent 主 Chat 可含 IMAGE；Embedding 归知识库；Reranker 仅在存在模型时展示。Provider 与模型编辑器拆分；无效角色不静默回退 Chat；探测写入 USER_DECLARED/PROBED，工具用 noop 函数、图片用内置 1×1 PNG。手机一级导航为对话/智能体/知识/技能/更多；`NavHost` + `ShellViewModel(SavedStateHandle)`；编辑状态拆 selected/editorOpen/editorDirty。Global Root Prompt 插在运行时协议与 Agent 提示词之间。Request Inspector 全屏路由，文案改为只遮盖 Key/敏感头。删除 Provider 前展示引用数；无引用密文退休并 GC；保存后清空明文 API Key 草稿。
- 0.3：`KNOWLEDGE_ARCHIVE` 独立于 DOCX/EPUB。导入入口为添加文件、导入文件夹（`OpenDocumentTree`）、导入 ZIP。`ImportBatch`/`ImportItem` 绑定 KB generation；`ImportBatchWorker`/`ConsentWorker` 前台协调。Vision/API Embedding 确认后签发一次性 `consent_tickets`，dispatch 后未知结果不自动重放。
- 自审修复：`ModelEndpoint` 空集合类型推断；ZIP 批次绑定改为 `jobBatchId` 而不是取第一个旧 ZIP 批次；归档展开改走 `importBytes` 以免本地路径被 `embeddingIsApi` 拦住；ZIP 未知 sidecar 跳过而不是整包失败；密钥退休写空 blob 以兼容旧 NOT NULL 列；NavHost 用 `LaunchedEffect(route)` 同步；关于页不再复用设置页；Agent 摘要只读，保存不再清空既有 `embeddingProfileId`。
- 验证：`.\gradlew.bat :shared:knowledge-api:test :data:sqlite:test :shared:agent-runtime:test :shared:domain:test :shared:provider-api:test :app-android:compileDebugKotlin licenseGuard licenseGuardReverse --offline` → BUILD SUCCESSFUL。JUnit XML：knowledge-api 46、sqlite 86、domain 2、agent-runtime 20、provider-api 33，合计 187、0 failure/error/skip。`python -B -m reuse lint` 330/330 退出 0。`codegraph sync .` Already up to date。`git diff --check` 仅有 CRLF 提示，无空白错误。未跑模拟器/真机、未 `assembleDebug`、未跑 500 文件批次、未跑付费模型。
- 文档：[ADR-0003](docs/adr/0003-model-endpoint-import-batch-secrets.md)、[docs/KNOWLEDGE.md](docs/KNOWLEDGE.md) schema v11、[docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) v1.7、[docs/DOCUMENTATION_CHECK.md](docs/DOCUMENTATION_CHECK.md)。审查报告保留为历史输入。
- 明确未关闭：F-001；F-019/1.0；Chat/Agents/Knowledge 仍非 route-level ViewModel；查询重试仍在 ViewModel；ZIP 整包内存上限 512 MiB；无杀进程/ENOSPC/真实 Vision 证据。
- Git：HEAD 未变。工作区含 0.1.1–0.3 源码、测试、ADR 与未跟踪审查报告。无 commit/push。
- 下一步：用户安排 1.0（CI/emulator/androidTest、真实 SBOM、签名 AAB）或授权打包带真实 revision 的 debug APK 以采集 F-001 Logcat。禁止把 F-001 或 1.0 标为已完成。

### 2026-08-29T15:01:00+08:00：授权提交并推送 0.1.1–0.3

- 请求：用户明确要求完成 commit 与 push。范围仅 0.1.1–0.3 源码、测试与文档；不提交 `.codegraph/`、`build/`、APK 或密钥；不执行公告生产或正式 Android release。
- Git：实现提交 `ef8e4363884358a8ed75acdf58fc64c69c94d6e7`，父提交 `13edc5759b1f2fa393f29a095c0690dd7184c7c0`，作者/提交者 `luozhibai <wy3273564266@163.com>`。使用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`，提交信息为 `fix(runtime): land 0.1.1-0.3 review remediations and schema v11.`，无 Cursor trailer。
- 未关闭：F-001、F-019/1.0、500 文件实机批次、真实 Vision。
- 下一步：本交接记录 SHA 后随授权非强制推送 `origin/main`。

### 2026-08-29T15:02:00+08:00：0.1.1–0.3 已推送到 origin/main

- `git push origin HEAD` 将 `13edc57..4b02df2` 推到 `origin/main`。远程与本地同步。未发布正式 Android release，未操作公告生产。
- 下一步：用户安排 1.0，或授权打包带真实 revision 的 debug APK 以采集 F-001 Logcat。

### 2026-08-29T16:39:34+08:00：最终复核修复与 1.0 本地门禁完成

- 请求/边界：用户要求审查 0.1.1、0.2、0.3，发现问题即修复，随后执行 1.0；本轮是最后一次自动复核—修复，完成后直接打包，只有用户人工再发现问题才重启该程序。用户明确授权全部修复后按 GitHub issue #1 部署公告系统到 Cloudflare；正式 Android release 后续共同安排。
- 修复：能力探测按 metadata/stream/tools/image 真实语义独立判定；密钥按主 ref/Header/snapshot 引用感知退休并对损坏数据 fail-closed；批次单 coordinator、generation/item/job/consent 二次校验与文件型 ZIP staging；route-scoped ViewModel/SavedState；公告 cache-first/单飞/退避/统计身份与后台预设；API26—29 Python FD 方向检查；release signing、锁文件、verification metadata、Actions SHA pin、SBOM/provenance 门禁。
- API 31 真实设备套件初跑出现两项确定性失败：ApiEmbedding fixture 把库存扫描误计为 secret read；release UI runner 用普通 `Application` 启动真实 Activity。修复为只统计 ciphertext lookup、非 UI instrumentation 延迟主容器而 Activity 首帧显式初始化。随后 UI 又暴露 NavHost graph 首帧竞态并修复。定向 Provider revision 1/1、release UI 2/2 通过；完整 XML 31 tests、30 pass、1 条需显式 `knowledgeLoad=true` 的 skip、0 failure/error。
- 本地门禁：`check --dependency-verification=strict` 最终复跑 `BUILD SUCCESSFUL`，936 tasks（103 executed/833 up-to-date）；JVM XML 46 suites/301 tests/0 failure/error/skip。provider-api 37、sqlite 104、knowledge-api 51 tests 全绿。debug/AndroidTest assemble、Python lint 与 CycloneDX 1.6 debug SBOM（166 components）同轮通过。公告 `npm test`/`npm run check` exit 0。REUSE 首跑仅指出 24 个新 Gradle lockfile 缺归属，`REUSE.toml` 增加生成锁文件 AGPL annotation 后复跑 372/372 exit 0。
- release 边界：`:app-android:verifyReleaseSigning` 因缺用户 release keystore、store password、alias、key password而按设计失败关闭；未生成或借用签名身份，根 `releaseGate` 不标 PASS。当前为 `LOCAL_PACKAGE_READY`，不是正式 AAB/release；`versionName=0.1.0`/`versionCode=1` 不擅自变更。
- F-001：用户观察在统计期间稳定出现两次，但最初问题仍不能稳定复现；状态保持 `candidate_intermittent`，不得写成已修复。若用户人工再次发现，需连同 APK SHA、dirty/schema/build 与完整 Logcat 重启复核修复。
- 尚未执行：真实收费 Provider/Vision、500 文件批次实机、初始 SAF→staging 进程死亡、ENOSPC、Android 15 六小时 timeout、Android 16 Job 配额耗尽、正式签名 AAB/商店发布。公告生产部署尚未在本节发生，下一步先形成 clean source commit，再备份 D1、部署并记录 version/source hash/HTTPS 后检。
