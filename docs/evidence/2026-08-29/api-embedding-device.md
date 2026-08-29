<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# API Embedding Android 集成测试实现

状态：TEST_IMPLEMENTED / COMPILE_AND_DEVICE_PENDING。本文不是设备通过证据。

## 范围与基线

- 仓库 E:\mobileAgentRuntime，HEAD 7511b22ffd7a7d3021b7857b6500cbe75d037ad6；保留全部并行 WIP。
- 本轮仅新增 app-android/src/androidTest/kotlin/runtime/mobileagent/ApiEmbeddingDeviceTest.kt 与本文；未修改 Python、Knowledge、Registry、SecretStore、Gradle 或生产配置。
- 已阅读项目入口规则、交接及 Knowledge 契约。CodeGraph 先查询 Registry/仓储和精确 Registry 文件，但返回大量不相关源码并截断；随后限定路径读取实际端口，不以索引遗漏判定源码缺失。
- 对应知识库 API Embedding 授权、空间隔离与 UNKNOWN 恢复边界。HANDOFF 由主流程单一写入者同步。

## 真实测试链

测试使用真实 AndroidContextSqlite/BundledSQLiteDriver、Migrations、ProfileRepository、AndroidSecretStore、ApiEmbeddingRegistry、OpenAiCompatibleAdapter/Ktor/OkHttp、KnowledgeRepository，以及 UsearchVectorIndexFactory 的 JNI。

只有 Provider 服务端是测试自建的 HTTP ServerSocket，显式绑定 127.0.0.1 和动态端口；返回三维合成向量。它不是互联网 Provider，不提供真实模型语义。仓储未选中的本地空间保留默认 HashingTextEmbedder；被测试的 API 空间必须走真实 Registry/HTTP，不使用本地向量替换 API 返回值。ONNX 已由独立测试负责，本轮不重复加载模型包。

ObservedSql 只委托真实 SqlConnection，并统计测试 DB 中 secrets 表的 SELECT。它不替换 SQL 结果、事务、密文、异常或 SecretStore。统计时要求只查询本次 UUID 的测试 ref，不输出值。

## 四项固定测试

全部使用 @Test(timeout = 30_000)，没有 opt-in 长负载。

| 方法 | 合同 |
| --- | --- |
| consentGatesHttpAndSecretsThenUsesTheExactBinding | 未 consent 导入/恢复均停 AWAITING_EMBEDDING_CONSENT，零 HTTP 连接、零 secret SELECT、无 active READY；明确 grant 后仅一次 POST，精确 endpoint/model/text，凭据匹配只记录布尔值；API 空间 READY 并实际调用 USearch JNI |
| providerRevisionInvalidatesPendingAndCapturedBindingsBeforeSecretRead | Provider revision 更新后旧 space resolver 返回 null；此前已捕获 adapter 的延迟调用及待授权 job 也在 secret SELECT/HTTP 前失败；新绑定有新 space，但构造/resolve 不代表授权或读取凭据 |
| wrongProviderDimensionCannotPublishReadyVectors | 请求绑定三维，受控端返回二维；真实适配器/仓储必须 FAILED/UNKNOWN，零持久向量、零 active READY、未进入原生索引构建 |
| droppedResponseStaysUnknownUntilExplicitDuplicateChargeRetry | 本地服务完整收到首次 POST 后断开，模拟可能已收费却没有结果；普通 resume/grant、未确认 retry 和重复导入均不发第二次请求；重建仓储对象后仍有持久 gate；仅明确 acknowledgeDuplicateCharge=true 可再发一次并 READY |

最后一项重建的是仓储对象，复用测试 SQLite 连接；不冒充设备进程死亡、数据库重新打开或后台 Worker 自动恢复。

## 安全与生命周期

- 使用现有 runtime.mobileagent.PythonRuntimeDeviceTestRunner，强制断言目标 Application 的实际类型为基础 Application；不会把 MobileAgentApp 初始化或用户数据读取当作测试前提。
- 每次测试在 target cache 下新建 api-embedding-device-UUID 独立根，数据库固定在该根内；Provider/model/KB/ref 均加本次 UUID。
- 唯一凭据为源码明确标注的 LOCAL-QA-FIXTURE-NOT-A-REAL-KEY，经真实 AndroidSecretStore 加密保存到测试 DB。不读取、枚举或解析任何用户凭据引用。
- AndroidSecretStore 使用产品固定 Keystore master alias；本轮不改变它、不删除它。只在 finally 删除本次测试 DB 中精确 UUID ref 的 dummy row。
- SqlConnection 没有公开 close 端口；遵循既有 Knowledge 设备 fixture，保留独立测试 DB/CAS 供检查，不反射关闭、不删除仍打开的数据库、不清 App 数据。根目录中的文件全部是合成测试资料。
- 仅 debug 现有 localhost cleartext 许可可运行。HTTP application interceptor 在 DNS/connect 前校验 host=127.0.0.1、动态 port 和 http scheme，拒绝任何其他目的地；不改 TLS 校验或产品 URL 校验。
- HTTP redirects 与自动连接重试关闭；请求/读超时 3 秒、连接超时 2 秒。HTTP 头及正文各最多 16 KiB、输入最多 8 条、连接最多 8 次。
- 服务关闭时关闭监听 socket 和当前连接，线程 join 最多 2 秒。记录仅包含合成 model/text/path 及 dummy authorization 匹配布尔值，不保存认证头、密钥值或整个请求包。

## 依赖与 runner

现有 App 已有 JUnit 4.13.2、AndroidX test runner 1.6.2、ext:junit 1.2.1、Ktor core/OkHttp、coroutines、serialization，以及 storage/security/data/vector/embedding 模块。无需新增测试库或修改 build.gradle。

主流程统一构建后可使用现有 runner 过滤 runtime.mobileagent.ApiEmbeddingDeviceTest。本文作者未执行该构建或命令。

## 本轮验证

- 源码静态检查 13/13：四个 30 秒用例、plain Application、真实端口、native index、动态 loopback、连接前目的地保护、禁止传输重试、明确 retry、secret SELECT 计数、精确清理、关闭线程、AGPL、尾空格。
- Kotlin 字符串/注释剥离后的括号配对检查通过；这不是 Kotlin 编译。
- git diff --no-index --check 未发现空白错误，仅提示 Kotlin 文件 LF→CRLF。
- 未运行 Gradle/Kotlin 编译、adb、模拟器/设备、测试 socket、真实凭据、互联网请求、收费 Provider 或生产变更；未 commit/push。
- 状态仍为等待主流程统一编译与设备执行，不能据此宣称四项通过或产品验收完成。
