<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# M5 HostHttp DNS 绑定与取消修复

时间：2026-08-29，Asia/Taipei。实现基线：`7511b22ffd7a7d3021b7857b6500cbe75d037ad6`。对应 R11/R12、S05/S08/S09、M5RR03/M5RR04。

## 已确认缺陷与代码变更

- 旧 `HostHttp.get` 先 `assertDestination`，随后第二次解析到未使用的 `pinned`，最后 `URI.toURL().openConnection()` 按原域名重新解析。校验结果没有绑定实际 socket。
- 现在使用锁定的 OkHttp 4.12.0。在实际 `Dns.lookup` 内仅解析一次，验证完整地址集合，再将同一份列表交给 OkHttp 的路由与 socket。每跳使用全新 DNS 实例及不保留空闲连接的独立连接池，不借用上一跳连接/HTTP2 coalescing 绕过解析。
- 原 URL host 保留给 HTTPS 的 SNI/证书主机名验证，且比对 URI 与 OkHttp 对 host 的解释；不使用“IP URL + 放宽 hostname verifier”。拒绝 URL userinfo、非 HTTPS、非 443 端口和已有 IP/内网过滤规则命中的目标。
- 明确 `Proxy.NO_PROXY`、无 CookieJar/Authenticator、禁止自动跳转与连接失败重试；额外拒绝 OkHttp 4.12 即使关闭连接重试仍可能自动重放的 `503 + Retry-After: 0` 路径。重定向由宿主逐跳重查，最多 3 次；跳转不携带前一跳 Cookie/Authorization。响应仍为既有 1 MiB 上限，按解压后流式字节累计，错误/超限/跳转全部关闭响应。
- 单次 30 秒整体截止覆盖 DNS、握手、响应体与所有跳转。阻塞宿主线程等待可中断的 latch，线程中断/截止直接调用真实 `Call.cancel()`。平台 DNS 查询本身可能不能中断，但迟到结果不再发起连接；不宣称取消可以销毁 JVM/platform DNS 内部线程。
- `ToolBroker` 参数按声明类型检查，`topK` 限 1—100，`maxChars` 限 1—16384，KB IDs 必须是字符串数组；不再用正则从任意 JSON 抽取 ID。同一 callId 不能替换工具名或参数，也不能替换待批准请求；缓存结果绑定原授权范围，授权变更后不披露旧结果。`InterruptedException`/`CancellationException` 不转换成缓存 Invalid。

## 调用与协作契约

`HostHttp.get` 和 `ToolBroker.invoke/approve` 保留同步签名。协程调用必须使用 `runInterruptible(Dispatchers.IO)`，仅用 `withContext(IO)` 或 `withTimeout` 包住同步调用不能使线程收到中断。已将此契约交给 `protocol_adapters`，由该工作包维护 `ToolExecutor.kt`；本文件不把发出协作消息当作集成已完成。

本工作包只写 `BuiltinTools.kt`、skills-api 模块必要 OkHttp 依赖、`BuiltinToolsTest.kt`、新增 `HostHttpTest.kt` 和本证据。不改权限/归档/远程适配器/Repository、根 Gradle、模拟器或生产。根 `HANDOFF.md` 与专题文档由主 Agent 单写汇总；CodeGraph 由主 Agent 统一同步以避免并行索引冲突。

## 测试设计与当前验证状态

`HostHttpTest` 使用临时内存测试证书与本进程 loopback TLS MockWebServer；SocketFactory 记录 OkHttp 选择的已验证地址，再仅映射到该本机服务。域名均为 `.invalid`；不执行外部 DNS 或公网 HTTP。

覆盖实际 OkHttp DNS 与 socket 目标、单次解析、混合公/私地址全拒、同域重定向重绑定拒绝、跨域白名单/无凭据、未批准域名和 URL 凭据拒绝、TLS 主机名不匹配拒绝、503 不自动重试、跳转次数/流式大小上限、线程中断关闭 socket、总截止关闭慢响应、迟到 DNS 不能连接。不是仅调用策略函数的绿测试。

主 Agent 串行执行入口：

```powershell
.\gradlew.bat :shared:skills-api:test --tests runtime.mobileagent.skills.HostHttpTest --tests runtime.mobileagent.skills.BuiltinToolsTest --no-daemon
```

- 子工作包未启动 Gradle（共享构建由主 Agent 串行安排）。主 Agent 回传首轮运行退出 1、约 48 秒：生产 `compileKotlin` 通过，`compileTestKotlin` 因测试中的 DNS lambda 不匹配 OkHttp 4.12 的非 fun-interface 而失败，随后改为 `object : Dns`。
- 第二轮主 Agent 回传约 37 秒、退出 1。已直接读取本地 XML（更新时间 `2026-08-29T00:29:55+08:00`）：`BuiltinToolsTest` 19 项/0 failures/0 errors；`HostHttpTest` 11 项/2 failures/0 errors。两项失败均是测试读取 `Host` 得 null，实际 TLS 协商 HTTP/2 使用 `:authority`；断言已修成读取 `:authority` 或 HTTP/1.1 的 `Host`，仍严格比较原始域名。
- 第三轮主 Agent 回传约 25 秒、退出 1，在其他工作包的 Gradle 配置阶段失败，尚未运行本模块。HTTP 源码/测试已冻结供主 Agent 重跑。**当前修正后测试仍为 PENDING**，不能把第二轮 28/30 或配置失败升级为 LOCAL_PASS。
- 已执行 `git diff --check -- shared/skills-api`，退出 0；仅 Git 的 LF→CRLF 提示，无 whitespace 错误。
- 独立只读审查、Android 设备接线、真机公网请求、生产与发布均未由本工作包执行。

## 上游源码核对

查阅锁定版本的 [ExchangeFinder](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/ExchangeFinder.kt)：DNS 路由计算返回后再次检查取消，再创建连接；[RealConnection](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealConnection.kt) 以 route socketAddress 连接，并以 URL host 配置 TLS 与主机名校验。此源码核对支持实现选择，不能代替本仓库集成测试。

[RetryAndFollowUpInterceptor](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt) 的 503 分支不检查 `retryOnConnectionFailure`，因此新增专门失败关闭及真实请求计数回归。

## 2026-08-29T00:48:40+08:00：Agent 绑定的内置工具适配器

主 Agent 新分配的唯一源码范围：`app-android/src/main/kotlin/runtime/mobileagent/BoundToolExecutor.kt`。未改上述冻结的 HostHttp/Broker/测试、Chat、根配置或其他 worker 文件。CodeGraph 先查询相关类型和路径，但其宽泛匹配/截断没有显示所需接口，随后只读核对 `ToolExecutor`、`PermissionGrant`、`AgentSnapshot` 与 KnowledgeRepository 目标方法。

交付入口：

```kotlin
fun boundBuiltinTools(container: AppContainer, snapshot: AgentSnapshot): ToolExecutor
```

静态实现依据与边界：

- 内置知识能力来自用户 Agent 绑定，不依赖任意 Skill 的知识 grant。范围为 snapshot KB IDs ∩ 当前同一 Agent 的 KB IDs ∩ `listKnowledgeBases()` 中未删除的 KB；无 KB 时隐藏知识 schema，直接 invoke 也拒绝。读前、读后、缓存结果返回前再次检查；已返回文档被删除后不重读缓存。calculator 等空 capability 的内置工具不被 KB/Skill 缺失禁用。
- 网络不调用 `effectiveGrant` 或 `grantForInvocation`。候选只能来自 snapshot Skill install IDs ∩ 当前同一 Agent 绑定，并要求安装仍 enabled、非 E、grant 未撤销、grant packageHash 等于当前安装包、capability 为 network.http，且同一 grant 明确包含 GET 和目标 host。空 methods 不代表 GET 授权。
- callId 第一次选定单个真实 grant 后固定 install/grantId/packageHash/revision/全部 scope；后续 invoke、approve、每跳 DNS 前后及结果外发前复查同一对象。原 grant 失效不会挑选另一 Skill 补足权限；未知工具/变更参数复用 callId 均拒绝。
- 网络仍使用原 URL 调用 HostHttp，不能改为 IP URL 或放宽 TLS。HTTP 始终先 NeedsApproval；批准只复用原绑定。Mutex 串行保护调用状态；invoke/approve 都用 `runInterruptible(IO)`。中断/取消留下终结拒绝状态，同一 callId 不重放执行。
- 这里只实现 built-ins。Python/MCP/Composite 及 Chat 接线由主 Agent/其他 owner 维护；不声称已经集成。

已执行 `git diff --check -- app-android/src/main/kotlin/runtime/mobileagent/BoundToolExecutor.kt`，退出 0。**未执行 Gradle、设备、DNS/HTTP 或生产操作**；新文件编译与独立审查均待主 Agent。建议串行编译入口：

```powershell
.\gradlew.bat :app-android:compileDebugKotlin --no-daemon
```

待验证反例：无 Skill 的合法 Agent KB 可用；快照/当前绑定/未删除 KB 三者交集；缓存后删除文档；一个 Skill 仅 host A/POST、另一个仅 host B/GET 时 GET A 拒绝；待批准期间撤权、改 revision/包 hash、禁用或解除 Agent 绑定均拒绝；相同 callId 改参数与取消后重放拒绝。本节为代码/静态交付，未将这些反例写成已运行结果。

## 2026-08-29：RunTools 组合与显式知识证据接线

主 Agent 后续分配的唯一源码范围为新增 `app-android/src/main/kotlin/runtime/mobileagent/RunTools.kt`；未改冻结的 HTTP、BoundToolExecutor、Chat、PythonSkillTools、AgentRuntime 或其他 worker 文件。交付 API：

```kotlin
class RunTools(
    container: AppContainer,
    context: Context,
    snapshot: AgentSnapshot,
    run: AgentRun,
    supportsImages: Boolean,
    textDegradation: Boolean,
) {
    val executor: ToolExecutor
    suspend fun toolImages(call: ToolCall, result: ToolResult): List<InlineImage>
    fun evidence(): List<Pair<Citation, String>>
    fun warnings(): List<String>
}
```

静态实现依据：

- 构造时校验 Run/snapshot 身份。仅组合 `boundBuiltinTools` 与 `pythonSkillTools`，按唯一 spec.name 分派；callId 固定请求和原 executor，重复变参拒绝，approve 不重新选路。UnknownOutcome 原样透传并记录该 callId 的终结结果；取消后同 ID 不再次执行。没有构造未实现 MCP 支持。
- `PythonRunBudget` 在 `synchronized(run)` 内扣除同一 Run 的 toolCalls/modelRounds，检查运行状态和总截止；model.invoke 必须存在持久化 Run budgetJson 中明确正数 maxModelTokens 才能预留额度，既有无此配置的 Run 默认拒绝。外层 AgentRuntime 的预算扣数/每调用检查和持久化由主 Agent 集成，不属于此文件的静态证明。
- 显式 knowledge_search 的每条结果必须与真实 chunk 的 KB、document、version、完整文本相符。read_document 按当前授权 document.active_version_id 的有序 chunks 重建实际返回的截断文本，完全相符才生成引文；只给实际返回的文本范围生成证据。
- 引文 ID 包含 runId、无歧义 Base64URL callId 和 ordinal。每个真实视觉 asset 各自生成 Citation，JSON 中提供 citationId/citations，read_document 还提供 textStart/textEnd；注册表保留对应文本，不猜测或合成来源。输出超既有工具字符上限直接 Invalid，不截断引文 JSON。
- `chunks.asset_ids` 以当前 Repository 的逗号格式读取，空串代表无图；非空集合必须是规范 UUID，无空项、重复项或外来 asset。未修改存储格式或 schema。SearchHit 只提供首个 asset，RunTools 从权威 chunk 读取完整集合，因此多图 chunk 不会静默遗漏其余原图。
- 每次产生结果、复用结果和 toolImages 读取都重查 snapshot KB IDs ∩ 当前 Agent KB IDs ∩ 未删除 KB，以及版本 READY、document/chunk/asset 归属和 locateCitation。原图只通过 evidenceBytes 读取，再检查实际字节 SHA256 等于定位器 blobHash、支持的图片 MIME 和非空内容；没有 raw assetBytes 回退。
- 严格模式在不支持 image、缺失原图、每图超过 2 MiB、数量超过 4 时失败关闭。图片数量累计考虑此前本 RunTools 的工具证据，不会每调用重置。显式纯文本降级才允许无原图，工具 JSON 和 warnings 均保留醒目中文警告。toolImages 仅处理成功 Value，并返回本次全部所需原图或抛错。

接线边界：构造 API 未传入自动 RAG 图片集合，因而 Chat/Runtime 必须在最终消息附件边界再次检查自动 RAG + 工具图片的合计数量，并在证据被撤权时停止外发；不能将本类的工具集合上限当作整个模型历史的证明。主 Agent 已收到此项和外层预算同步要求。根 HANDOFF/Chat/typed messages/引用持久化由主 Agent 单写整合。

验证仅为 CodeGraph/当前接口与 SQL 只读核对、新增源码 whitespace 检查；**未执行 Gradle、测试、设备、模型、网络或生产操作，编译与运行验收 PENDING**。统一编译建议仍为 `:app-android:compileDebugKotlin --no-daemon`。应验证正常无图搜索与读文档、单 chunk 多图、CSV 非法项、跨 KB/版本伪造、原图缺失/超限、不支持图片的严格拒绝和显式降级、结果后撤权、callId 变参/审批归属，以及 Python 嵌套预算耗尽和 UnknownOutcome 不重放。
