<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Android 知识运行时设备测试交付

日期：2026-08-29（Asia/Taipei）。实现基线 `7511b22ffd7a7d3021b7857b6500cbe75d037ad6`，工作树有多人并行修改。本工作包只新增 `app-android/src/androidTest/kotlin/runtime/mobileagent/KnowledgeRuntimeDeviceTest.kt` 与本文件；不改生产实现、构建配置或其他测试。对应 K01/K05/K07/K08 和 Android ICU 初始化回归；不是完整 K06 负载或产品验收。

## 当前状态

**源码交付、静态接口核对已完成；编译、设备运行及独立审查 PENDING。** 作者未运行 Gradle、adb、模拟器、ONNX 推理、网络或生产操作。主 Agent 负责统一编译、指定设备、安装及真实执行，并维护根 HANDOFF。下列行为是测试内容，不是已观察到的通过结果。

## 依赖与实际接口

使用现有 AndroidJUnit4/JUnit 4.13.2，不加依赖。模型从 `InstrumentationRegistry.getInstrumentation().targetContext.assets` 读取 APK 的 `modelpacks/all-MiniLM-L6-v2/manifest.json` 及其 modelFile/tokenizerFile；使用 `AndroidModelPackLoader.load()` 和真实 `OnnxTextEmbedder`，没有 HashingTextEmbedder、替代权重或测试推理实现。

公开锁定元数据：

- revision：`1110a243fdf4706b3f48f1d95db1a4f5529b4d41`
- ONNX SHA-256：`6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452`
- tokenizer SHA-256：`be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037`
- dimension：384；mean pooling、归一化、cosine，loader 校验 manifest。这里记录的是当前源码锁定值，设备日志另记录实际完成加载校验的 hash。

`BundledSqliteConnection.kt` 的真实类名为 `AndroidContextSqlite(context, name)`，内部用 `BundledSQLiteDriver`；迁移为 `Migrations.apply(db)`。Repository 显式注入真实 ONNX 和 `UsearchVectorIndexFactory`。后者直接调用打包 `usearch_jni`；工厂计数器仅统计真实实例，不替换 add/search 结果。

## 四项测试

| 测试名 | 执行内容与断言 |
| --- | --- |
| `packagedModelPackLoadsVerifiedNormalizedStableEmbeddings` | 从目标 APK 的逻辑 asset 路径读取 manifest、权重与 tokenizer，独立流式 SHA 校验，再经 loader 加载及重查落盘 SHA。真实 ONNX 输出必须为 384 个有限值、L2 norm 约为 1；相同文本重复推理误差 ≤ 1e-5；天文与烘焙文本不能坍缩为相同向量。不是语义检索质量基准 |
| `usearchJniAddsSearchesAndRejectsUseAfterClose` | 以真实 ONNX 生成两条向量，真实 USearch JNI add/search；相同输入第一名正确，返回分数有限，重复 ID 拒绝；close 可重复，关闭后 add/search 拒绝。公开 API 无单条 delete，不伪造其存在 |
| `sqliteFtsImportCitationAndDeletionStayIsolatedAcrossGenerations` | bundled SQLite 真实迁移；两 KB 共享文本 blob、另有存活文本；直接 FTS5 MATCH 断言命中数，不能用 Repository 的 LIKE 回退掩盖 FTS 失败；英文与中文查询，真实 citation/源字节、伪造 KB 引文拒绝。删除文档后新 READY generation 成员数变化、旧成员保留、引用 removed，共享 blob 的另一 KB 仍可读。新 SQLite 连接和 Repository 读取持久化 generation 后继续验证隔离，最后删一 KB 不影响另一 KB |
| `agentRepositoryAndPromptTemplatesInitializeOnAndroid` | 真正构造 Android SQLite/AgentRepository，用自造 `.invalid` Provider/model 配置存储 Agent 和 prompt；渲染 agent_name/date/knowledge_bases，拒绝未知/畸形 placeholder 并检查无额外 prompt 版本。覆盖 Android ICU 正则类初始化及真实 Repository 校验路径，不调用 Provider |

纯文本测试的 Vision 参数是“任何调用立即失败”的计数 guard，断言调用数为 0。没有生成伪造 Vision 成功结果，也没有外发；**该 guard 不能作为真实 Vision/API 集成证据**。

## 隔离和证据边界

测试 ContextWrapper 保留 targetContext 的 assets，只将数据库、files、noBackupFilesDir 重定向到 `targetContext.cacheDir/knowledge-device-test-<UUID>/`。每项测试使用各自 fixture 子目录；已校验的模型缓存只在本测试套件目录内复用，不读用户 no-backup 模型缓存、应用 DB、用户 CAS、密钥或配置。

当前 `SqlConnection`/`AndroidContextSqlite` 没有公开 close；测试不反射关闭、不在连接仍存活时删除文件，独立 fixture 目录保留供主 Agent 核查，在 instrumentation 进程退出后才能针对日志中的确切测试目录清理。ONNX session 和独立 USearch 对象明确 close。新连接读取持久化数据不等于已经验证进程死亡恢复，也没有声称 USearch native index 文件持久化。

当前 runner 是 `PythonRuntimeDeviceTestRunner`，其 `newApplication` 将 Application 替换为基础 `android.app.Application`。因此此套件**不能作为 MobileAgentApp 启动、主进程 DI、UI 或系统启动流程通过的证据**；它只真实执行所列组件和 Android ICU 正则代码。

每项测试在 `KnowledgeRuntimeDevice` Logcat tag 下输出名称、唯一 fixture 路径、预期公开 model/tokenizer hash，最终 PASS/FAIL 与 elapsed_ms。模型加载额外输出 pack_load_ms 及完成校验的实际 model/tokenizer hash；SQLite 测试输出 SQLite 版本、generation IDs 和真实 JNI 实例数。未完成断言不会记 PASS；进程被终止时日志可能没有终结项，不能以缺失日志推断成功。

## 主 Agent 待执行

先串行编译测试：

```powershell
.\gradlew.bat :app-android:compileDebugAndroidTestKotlin --no-daemon
```

然后在主 Agent 已批准并选定的单个设备上执行该类；可由既有 Gradle 设备流程调用：

```powershell
.\gradlew.bat :app-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.KnowledgeRuntimeDeviceTest --no-daemon
```

执行者应同时保存 Gradle/AndroidJUnit XML、对应 Logcat、设备 serial/API/ABI、目标 APK 与测试 APK hash、实际命令/退出码/时间。arm64 与 x86_64 是两个不同设备验证项，单一 ABI 不可外推。未测试：真实 Vision 外发、收费模型、300—500 文件、异常杀进程矩阵、内存/温控长测、应用启动和发布。

静态检查使用 `git diff --no-index --check -- NUL <新增文件>`，没有 whitespace 错误；仅源码 LF→CRLF 提示。CodeGraph 先查询构造/调用关系，未覆盖或截断的接口才按实际路径只读核对。
