<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Skills 执行与安全模型

状态：M5 本地 JVM 已落地（清单检查、A—E 分类、内置工具与 Tool Loop）。M5R/M5RR 修复后：工具协议含 assistant.tool_calls、live grant、read_document KB 校验、HTTPS/IP 字面值拒绝、完整 EOCD/central/local ZIP 结构校验、canonical duplicate/symlink class E、预算取消上游。对应R09—R12、S01—S11。**知识是数据，Prompt是指令，Skill脚本是可执行代码，三者不共享信任等级。** Python 隔离执行仍属 M6，不能用指令展示代替执行能力，本轮不宣称正式 release。

## 1. Skill包与兼容性

保留现有以 `SKILL.md` 为入口的目录；补充 `mobile-skill.json` 执行清单、脚本、资源、vendor、依赖锁、哈希和可选签名。缺清单时分析生成本地安装清单，不能改原始包或默认授权危险能力。

清单示例仅表达v1契约；这是本项目自有测试Skill，外部Skill按其原许可记录：

```json
{
  "schemaVersion": 1,
  "id": "dev.example.knowledge_helper",
  "name": "Knowledge helper",
  "version": "1.0.0",
  "license": "AGPL-3.0-only",
  "runtime": {
    "kind": "python",
    "python": "3.14",
    "entrypoint": "scripts.main:run",
    "mode": "pure-python"
  },
  "permissions": {
    "knowledge.search": {"scope": "selected-by-user"},
    "network.http": {"hosts": ["api.example.com"], "methods": ["GET"]},
    "storage.skill": {"quotaMiB": 32}
  },
  "limits": {"timeoutSeconds": 30, "maxOutputKiB": 1024, "maxLogKiB": 512}
}
```

清单还须关联可验证的输入/输出JSON Schema、作者/来源、依赖锁和包内容哈希。入口只允许包内模块与明确函数，拒绝绝对路径、遍历和任意表达式。描述只供模型理解，不能取代schema校验。

| 等级 | 处理 |
| --- | --- |
| A Instruction-only | 支持读取SKILL.md指令；无可执行代码 |
| B Pure Python Compatible | 符合版本/依赖/权限且用户批准后可执行 |
| C Unsupported dependencies | 保留说明，禁用脚本并列依赖原因 |
| D Platform-specific | Shell/Node/Docker/桌面自动化等不能在本地执行；保留说明 |
| E Dangerous/Invalid | 路径穿越、压缩炸弹、原生载荷、哈希不符等拒绝安装；不得回退成可信指令 |

A/C/D的指令同样是不可信导入内容，启用前用户确认，不能让其中的安装命令自动执行。兼容分析是提示，不是安全证明。签名有效只证明来源/完整性，不自动产生信任或权限。

## 2. 安装流程和持久数据

隔离暂存 → 限额解包和文件头检查 → Manifest/schema/hash验证 → 依赖/import静态分析 → 来源/许可/签名/权限/源码预览 → 用户逐资源批准 → 原子启用版本。版本切换保留回滚信息，权限新增必须重新批准。

记录 `SkillPackage(id, version, packageHash, manifestHash, compatibility, source, license, signatureStatus)`、`SkillInstall(installId, packageHash, enabled)`、`PermissionGrant(grantId, installId, packageHash, resourceScope, capability, expiry, revision, revokedAt)`、`Invocation(invocationId, runId, packageHash, grantRevision, state)`。grant绑定包哈希，不能通过同名更新继承扩大的权限。

默认上限作为实施初值：压缩包50 MiB、展开200 MiB、5000文件、膨胀比100；拒绝符号链接、硬链接、绝对路径、大小写冲突和规范化后重复路径。大小和内容检查独立于扩展名；不要只扫描`.so`字串。超限允许用户取消或在受控上限内调整，不能偷偷全部展开。

源码查看器是一级入口，支持SKILL.md、Python和锁文件，以纯文本展示，不执行HTML或代码。卸载要说明持久数据是否保留，不删除其他Skill或共享KB。

## 3. CPython隔离执行

宿主包含Agent Runtime、秘密、数据库、网络与Capability Broker。Python service必须声明`android:isolatedProcess="true"`和非外部导出；每次调用创建全新worker实例，调用后销毁。**普通 `android:process=":python"` 仍可能共享应用UID，不能作为等价替代。**

首选官方CPython Android 3.14.x嵌入包，经测试固定补丁/哈希，arm64-v8a与x86_64均验证。仅允许应用构建时携带的CPython/JNI库；“禁止.so”指用户Skill/动态依赖载荷，不是禁止应用自己的受审查原生运行时。

技术验证必须证明：isolated UID下能加载APK内受审查解释器与stdlib；无法读宿主私有文件；只读FD足以访问包；IPC在支持的最低Android版本可工作；取消/超时真的销毁进程；下一次调用无状态污染。无法在隔离身份加载时记录阻塞，不退回主进程。

`stopSelf()`或解除绑定不单独构成“进程已经死亡”的证据，必须证明进程生命周期结束、通道关闭和下一调用状态全新；如果平台不能满足，则该实现不能通过S03。API26使用Java Binder/ParcelFileDescriptor；不能直接采用要求API29+的NDK Binder FD接口并仍宣称支持API26。

执行流程：

1. 宿主重新验证包hash、启用状态和当前授权，创建不可复用的invocationId与一次性IPC凭证。
2. 启动独立service实例，绑定其进程/UID/Binder生命周期；不允许复用前次有状态worker。
3. 传只读包FD与有限输入；CPython以zipimport或等价只读加载机制读取纯Python代码。具体FD访问实现必须由spike证明，不假定zipimport直接接受任意FD。
4. SDK只能通过窄IPC提出结构化能力请求；Host执行身份/资源/参数/预算复核。
5. 输出走有界通道；完成、取消、超时或Binder死亡都关闭FD/通道、吊销凭证和销毁worker。

不能把1 MiB输出直接当作单个Binder消息传送；控制帧建议≤64 KiB，大内容通过受控只读FD/流并累计限额。Binder线程不能阻塞等待模型/网络；并发、背压和宿主断开时终止规则要测试。

在同一时刻首版可串行只运行一个Python调用，避免生命周期与资源竞争；若扩展并发，必须真正独立实例/UID隔离并重新测试。不承诺绝对不可逃逸，系统漏洞和资源限制差异属于剩余风险。

## 4. Broker权限与SDK

每次请求的有效权限 = 包声明 ∩ 用户当前grant ∩ Agent绑定范围 ∩ 系统安全策略 ∩ 本次调用预算。模型、Prompt、知识库、工具输出、签名或远程公告不能扩大这个交集。

| SDK能力 | 宿主验证 |
| --- | --- |
| ctx.knowledge.search | 明确KB ID集合、query/topK/预算；不可枚举未授权库 |
| ctx.document.read | 该document属于授权KB；限定页/片段/字节数 |
| ctx.http.request | host/method/path约束、重定向、DNS/IP、超时和响应体限额 |
| ctx.model.invoke | 单独授权profile、次数、token/费用上限；秘密不返回Skill |
| ctx.storage.get/put | Skill自身命名空间、32 MiB默认配额、原子写 |
| ctx.files.read_handle | 用户选择的短期只读句柄，不返回全局路径 |
| ctx.files.write_artifact | 本次sandbox artifact；导出到用户目录再次确认 |
| ctx.log.info | 结构化、有界、统一脱敏，不含秘密或默认完整输入 |

网络默认HTTPS精确host白名单，拒绝IP直连、回环/link-local/内网目标，逐跳重新校验重定向及解析地址；防止凭据跨host转发和DNS重绑定。通配符若支持须限制完整域边界，不用简单suffix匹配。用户自建Provider/MCP的内网端点是独立、显式授权范围，不继承为所有Skill网络权限。

HTTP写操作和文件导出等副作用必须向用户展示目的地与影响；不能因为模型说“用户已批准”而跳过。所有能力有超时和取消传播，调用完成后凭证不可重放。

## 5. 依赖和资源限额

首版只支持允许的stdlib子集、包内vendored纯Python和App预审依赖。**不支持运行时pip、自动下载依赖、任意subprocess、原生wheel、DEX/JAR/SO、Shell或可执行文件。** 直接socket/文件访问必须在真实系统边界被拒绝，import过滤只是减少攻击面。

| 限额 | 默认 | 强制位置 |
| --- | --- | --- |
| Python调用时间 | 30秒 | Host watchdog，终止worker，不依赖脚本配合 |
| 结构化输出 | 1 MiB | 宿主流计数和schema校验 |
| 日志 | 512 KiB | 宿主丢弃/终止规则并记录截断 |
| Skill持久存储 | 32 MiB | Broker配额和原子写 |
| 单次网络响应 | 8 MiB | Broker流式读取限额 |
| 工具子调用 | 20次/调用 | Broker，与Run总预算取更小值 |
| 模型子调用 | 3次/调用 | Broker，还受Run总Token/费用预算限制 |

CPU、FD、文件大小、地址空间等native限额在平台支持范围内设置并测量，内存限制不能仅凭声明声称硬保证。必须测试无限循环、内存/输出/文件洪泛和线程残留，确保宿主仍活着、下次调用正常。模型/HTTP超时的外部结果可能不确定，不盲目重试收费/副作用。

## 6. 本地秘密、日志和导出

Keystore生成不可导出的加密key，Provider/Skill秘密作为密文保存；不得在SQLite/DataStore/Preferences保存明文secret。硬件保护依设备能力，不保证所有API26设备同样安全。

日志、预览、错误、导出、崩溃信息统一脱敏。Request Inspector为用户本地按需查看，默认不持久化正文；“输入摘要”不能成为明文全文副本。生产日志默认不含聊天、Prompt、文件名、向量文本或Skill输入输出。

备份策略排除秘密和设备绑定key相关状态，恢复后要求重新输入密钥；不要把自动备份当作跨设备秘密迁移。默认导出Agent/Prompt/模型非秘密参数、KB元数据和Skill配置，不含Key/Header/Cookie/敏感附件。用户显式选择完整KB/对话/Skill包导出时展示体积和隐私范围，保留原权利信息并重新导入验证；未选的blob和源码不得夹带。

schema未知、包hash错误或无权限的导入必须拒绝；迁移失败不清库。秘密丢失时返回明确配置错误，不自动调用其他key。

## 7. MCP与Remote扩展

M7实现MCP Adapter；首版优先用户显式配置的远程HTTP传输，具体协议版本/transport在该阶段查官方规范并锁定。不在Android自动启动任意stdio/Node/Shell服务器。

适配器执行能力发现、工具schema校验、名称去冲突、调用/取消/错误映射，所有外部调用仍经权限与预算系统。server工具描述是不可信指令；工具列表变更或新增权限需要重新确认。凭据仍通过SecretStore注入；重连不能自动重放副作用。用受控测试server验证，不调用用户未知服务。

`RemoteSkillExecutor`保留版本化请求/结果/取消/能力声明接口，用户自托管实现另行授权。不提供默认公共执行服务器，不自动把C/D级Skill上传远程，不上传整个知识库或secret。

M7必须固化以下最小DTO契约；这是本项目executor端口，不冒充MCP官方wire schema，不在此阶段隐含建设远程服务器：

| 帧/方法 | 必需字段与类型 | 校验/语义 |
| --- | --- | --- |
| capabilities() → RemoteCapabilities | protocolVersion正整数、executorId字符串、runtimes字符串数组、capabilities字符串数组、maxInputBytes/maxOutputBytes/maxTimeoutMs正整数、supportsCancel布尔 | 未知主版本拒绝；只接受用户配置的executor；远程声明不是本地授权 |
| invoke(RemoteInvocationRequest) | protocolVersion、invocationId/runId/grantId字符串、executorId、skillId/skillVersion、packageSha256小写64位hex、inputSchemaVersion正整数、arguments对象、approvedCapabilities数组、deadlineAt UTC、limits对象 | 包必须已在该executor合法安装且hash匹配；grant绑定当前包/endpoint/调用，参数经schema校验；不随请求自动上传包 |
| RemoteInvocationResult | protocolVersion、invocationId、status枚举、result对象或artifactDescriptors数组、error对象或null、startedAt/finishedAt、usage对象 | status为SUCCEEDED/FAILED/CANCELLED/TIMED_OUT/UNKNOWN_OUTCOME；输入/输出必须有界，结果不可信 |
| cancel(RemoteCancelRequest) | protocolVersion、invocationId、cancelRequestId、reason枚举 | 幂等取消请求；只针对相同executor的当前调用，不把取消发送成功当作已停止 |
| RemoteCancelAck | protocolVersion、invocationId、cancelRequestId、accepted布尔、terminalStatus可空 | 未收到确认或执行器状态不明时记UNKNOWN_OUTCOME，不自动重发invoke |

`limits`必含timeoutMs、maxOutputBytes、maxToolCalls、maxModelCalls、maxModelTokens；与本地Run剩余预算取交集。`usage`至少报告实际时长/输出字节/工具与模型调用数，远程报告仅作审计，不代替本地限额。`error`沿用主方案code/userMessage/retryClass/sanitizedDetails。artifact仅为受控描述符（id、mediaType、size、sha256），获取另走已授权端点，不任意跟随返回URL。

executor认证由SecretStore在传输适配器注入，不放DTO。grantId本身不是可对外使用的权限令牌；本地能力调用仍须Broker逐次验证，不因远端宣称支持某capability就开放手机资源。相同invocationId不能重复执行：适配器记录已发送状态，executor需声明/实现去重和结果查询；无法确定结果时返回UNKNOWN_OUTCOME，只有用户知悉风险后用新的调用ID重新发起。传输路由、重连认证和结果查询细节由M7锁定协议并扩展schema，不能用自动重试掩盖缺失。

## 8. 发行边界

App外部导入Python仍涉及动态代码和平台政策约束。首版仅用户主动选择本地Python源码、不自动下载更新Skill、不加载外部native/DEX/JAR。发布前按当时Android/Google Play政策独立审查，不能从解释器存在推导“保证上架”。公告只能导航至更新页，不能充当代码分发渠道。

独立安全审阅必须覆盖安装器、Binder身份、撤权、网络过滤、秘密、资源终止和下一次调用恢复。测试通过只针对已测版本与场景，不宣传绝对防逃逸。

## 9. M5 本地验证（2026-08-28）

本轮：`SkillArchive`/`SkillInstaller` 对 zip-slip、ELF/DEX/JAR/SO、pip 远程依赖、未知 schema、哈希不符判 E 并拒绝安装；缺清单的 SKILL.md 为 A；pure-python 清单为 B；shell/node 为 D 可存指令不可执行。内置 `knowledge_search`/`read_document`/`calculator`/`http_request`：不完整 JSON 不执行、重复 call id 不双执行、HTTP 需确认、loopback 拒绝、Prompt 不能扩大 grant。`AgentRuntime` Tool Loop 计入轮次与工具预算；工具输出走 SecretRedactor。未嵌入 CPython，未跑 S03—S07 隔离。

| 验收 | 本轮状态 | 证据边界 |
| --- | --- | --- |
| S01 | LOCAL_PASS | 无清单 A、未知 schema E、哈希不符 E、源码/许可可见、不自动执行 |
| S02 | LOCAL_PASS | zip-slip/ELF/pip 拒绝且不写入 skill_installs |
| S08 | LOCAL_PASS | 四件内置工具 schema、碎片 JSON、重复 id、HTTP 确认 |
| S09 | LOCAL_PASS | 无 tools 能力不带工具；越权 HTTP Denied；预算耗尽 |
| S10 | LOCAL_PASS（工具输出） | 工具结果脱敏。未跑完整导出矩阵 |

## 10. M5R01—M5R11 本地修复（2026-08-28）

- Tool loop 第二轮保留 assistant.tool_calls 再跟 tool 结果；Adapter 按结构化 JSON 编码。HTTP NeedsApproval 在 Chat 暂停并提供批准/拒绝后续跑。
- Provider 可标记 tools 能力。Chat 不再全局合并全部 KB；`knowledge_search` 只使用当前 grant 的 knowledgeBaseIds。
- 安装保存完整 manifest JSON 与原包 bytes；同 hash 重导入不叠加 grant。限额/炸弹/远程依赖（大小写不敏感）一律 class E。
- HTTP 仅 HTTPS、allow-list、禁 loopback/私网；`file://` 与 `http://` 在回调前拒绝。工具输出与 read_document 有上限。
- 成功/失败/拒绝工具输出都用当前 Provider secret 脱敏。SSE 按 tool index 映射 id。总时限在模型轮结束和每个工具前重查。`toolsEnabled=false` 收到 tool call 不执行。

## 11. M5RR01—M5RR05 本地修复（2026-08-28）

- `read_document` 校验 document.kb_id 与当前 grant；空授权拒绝。HTTP method 与 grant.methods 求交集，通用批准不扩大范围。
- ToolBroker 每次 invoke/approve 重新读取 live grant；撤销后同一 Broker 的新 callId 与待批准恢复均拒绝。
- HTTP 拒绝 IP 字面值（含 IPv6 私网、整数 IPv4）；解析结果逐跳核验，私网/loopback/link-local 不得连接。
- Runtime 以剩余预算 `withTimeout` 取消模型流、审批等待与工具；上游 delay 不再把超时事件全部消费完。
- 截断 ZIP、无 EOCD、Unix symlink 属性一律 class E，不再把 PK 前缀空包当成 instruction-only A。

## 12. Skill ZIP 与运行预算收口（2026-08-29）

- Skill ZIP 在读取或暴露 manifest/source 前校验单磁盘 EOCD、central directory 边界/数量、每个 local header，以及名称、flags、method、CRC 与大小一致性；fake EOCD、无 central directory、central/local 分叉均归 Class E。
- 路径统一分隔符、去除 `.`、折叠可解析层级、NFC 归一化并按 `Locale.ROOT` 小写后检查重复；大小写或 canonical duplicate 不再允许两个 manifest/source 产生审批与执行分叉。
- bit3 data descriptor 的实际 CRC、压缩/解压大小必须与 central entry 一致；目录项也在跳过业务内容前执行同样的完整性、单项大小、压缩比和聚合总量检查，目录名不能成为资源限制旁路。
- Agent Runtime 对 `beforeModelRequest` 使用自身剩余预算的 `withTimeoutOrNull`：自身 deadline 到期记录 `BUDGET_EXHAUSTED` 且不启动模型；调用方取消仍沿取消路径处理，不伪装成预算耗尽。
- 新回归覆盖 fake EOCD、大小写/规范化重复路径、central/local 名称不一致和虚拟时间下的 pre-request budget timeout。
