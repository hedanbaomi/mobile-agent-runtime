<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0008：b07 follow-up 收敛——fail-closed 默认与诚实能力声明

- 状态：已实现（JVM 定向测试全绿；device 测试编译通过，待远端 emulator 执行）
- 日期：2026-09-05
- 来源：`mobile-agent-runtime-b07-followup-convergence-prompt.md` + 独立审查证据包
  （`mobile-agent-runtime-b07-review-evidence.zip`，含 `ReplayWrapperProbe.kt`、
  `CacheLeaseProbe.kt`、`FilePrimitiveProbe.java` 及三份实跑输出）

## 复核结论（先复现/核对，再修改）

| # | 审查指控 | 结论 | 生产证据 |
| --- | --- | --- | --- |
| A | `CompositeToolExecutor` 未转发 `authorizeReplay`，撤销后仍披露缓存 | **CONFIRMED** | `ToolExecutorFactory.createLegacyExecutor()` 即 composite（`ToolExecutorFactory.kt:109`）；Chat 生产链经 `ChatViewModel.kt` 工厂组装；`RunTools.discloseCached` 调用的 owner 就是 composite；composite 无 override，继承接口默认 `true` |
| B | `VectorIndexCache` 借出裸句柄后可被 `put`/驱逐/失效/`close` 关闭 | **CONFIRMED** | `get` 返回裸 `VectorIndexPort`，`put`/驱逐/失效/`close` 立即 `close()`；`UsearchVectorIndex.close()` 释放 native pointer。转录与源码一致 |
| C1 | `CREATE_NEW` 独占创建 ≠ 内容整体原子发布 | **CONFIRMED**（原语性质） | `writeExclusive` 先占名后流式写入；claim 在 0 字节时即可见。同一进程内后端锁串行化读写，跨进程/外部读取者可观察 partial |
| C2 | 无 `REPLACE_EXISTING` 的 `ATOMIC_MOVE` 可证明 no-replace | **CONFIRMED**（指控成立，实现原注释错误） | reviewer Linux/JDK probe 覆盖成功；POSIX rename 本就替换。原注释“Linux/Android 由内核保证”（`WorkspaceModels.kt`、`ADR-0007 §3`）已撤回，以本 ADR 为准 |
| C3 | `COMPARE_AND_REPLACE` 为严格原子 CAS | **CONFIRMED**（夸大成立） | Internal 系“读-比-后写 + 进程锁 + 最后一刻重读”，跨进程检查与提交之间仍有窗口，无原子 CAS 原语 |
| D | `RunManifest` 系事后重读而非执行事实 | **CONFIRMED**（tool fingerprint 除外） | `generationPins()`、`effectiveGlobalRootPrompt()`（两次）、Skill pins/grants 均在 manifest 时刻重读；`toolSchemaFingerprint(toolExecutor.specs)` 本就是冻结 specs——该子项 **REJECTED**（无需改） |
| E | `isExpired` 对整体 malformed `scopesJson` 返回 `false` | **CONFIRMED** | `AuthorizationEvaluator.kt:141` 与其注释/文档矛盾 |

## 决定

1. **Composite 转发 + 接口默认 deny**：`CompositeToolExecutor.authorizeReplay`
   按 `callId` 找绑定，校验 `previous.call == call`、路由身份不变、
   `state == SETTLED` 后委托 child；child false/异常/未知 call/参数不一致
   一律 false，不重执行、不返 payload。`ToolExecutor.authorizeReplay`
   默认改为 `false`；纯计算工具经 `ToolBroker` 空 capability 作用域等值路径
   仍可放行（calculator），未知 call 改为 deny。
2. **各执行器显式策略**：WebSearch（仍授权+已结算+非 unknown 才放行）、
   workspace/shell（只读重验：绑定、作用域、Dangerous Mode/authority 现状；
   不消费 ONCE、不写审计、不碰 backend）允许；MCP（含 agent 桥与 App
   桥）、Shizuku、legacy workspace（均无已完成调用授权记录）显式 deny。
   MCP 批准后重复同 call 的披露行为因此收紧（fail-closed），模型需用新
   call 走批准路径；见证据文件。
3. **Vector lease**：不再外借裸 `VectorIndexPort`；`acquire`/`publish`/
   `getOrBuild`（同 key 单飞）+ `VectorIndexLease`（`AutoCloseable`，
   引用计数 + retired 延迟释放 + exactly-once close）。`close()` 同理。
   未实现跨 key 优先级以外的构建去重之外的任何东西：同 key 同 generation
   并发 miss 只构建一次；不同 key 仍各自构建（报告中明确）。
4. **Workspace 诚实语义（3f75 修订：no-replace move 改方案 A）**：
   - `publish(replaceExisting=false)` 走 `publishNew`（原子 link，EEXIST
     失败；link 不可用时回退独占创建流式拷贝；永不裸 rename）。
   - no-replace move 对一切节点一律 `UNSUPPORTED`（Internal/
     Shizuku `ATOMIC_REPLACE_UNAVAILABLE`/Wired 同码）：非原子 copy+delete
     无法证明“删除的即复制的”，曾返回成功却删掉未复制的新内容（3f75
     probe 实证）；`moveFileNoReplace` 已删除，不留危险原语。copy +
     delete 保留为两步显式操作；typed 拒绝码收敛到 `OPERATION_UNAVAILABLE`
    （Shizuku/Wired 适配层此前误标 INTERNAL/IO 已纠正）。
   - Internal 描述符移除 `COMPARE_AND_REPLACE`，保留 `ATOMIC_PUBLISH`（仅
     temp-complete→rename 替换路径）、`CREATE_IF_ABSENT`（仅创建原子性，
     非内容原子可见）、`BEST_EFFORT_CONFLICT_DETECTION`。SAF 本就仅
     best-effort 且已 fail-closed，无需改。
   - Wired 新增 `UNKNOWN_OUTCOME` 错误码并贯通映射；Shizuku/Wired move
     同修。内存后端仅替换路径 rename，不在 scope 内。
5. **Manifest 执行事实化 + 强 manifest**：新增 `PreparedRunFacts`（一次冻结，
   prompt 与 manifest 共用：root prompt 文本、Skill 文本+pins、检索实际
   pins、冻结授权 grants、工具指纹、scope）；`RetrievalResult.usedGenerations`
   由本次 `retrieve` 直接记录；grants 优先取冻结 `toolingContext`
  （legacy 回退才重读）；stamp 失败 fail-closed（`FAILED` + 释放所有权 +
   不做任何 provider/tool派发）。`generationPins()` 保留作现势查询，
   文档注明禁入 manifest。
6. **Evaluator**：`isExpired` 不可读文档一律 `true`。

## 未做（留后，与 prompt §1 一致）

完整 RunCoordinator 流式/tool-loop 抽离、model.invoke 编辑器控件、非
Internal 工作预算、1k/10k/50k 实测、物理 Wired USB、真实 Provider、真实
刘海设备、大 UI 重做。MCP 已完成调用的缓存披露取了最严 deny 而非远端
重验（远端无本地已完成调用记录可比重验）；跨进程替换写仍是 best-effort
（无 OS CAS 原语，见证据文件 §6）。
