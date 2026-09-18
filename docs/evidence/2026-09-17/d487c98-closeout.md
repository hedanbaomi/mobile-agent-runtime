<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# d487c98 复核收口：Run 费用授权与真实 broker 闭环

审查基线：hedanbaomi/mobile-agent-runtime 分支 codex/user-qa-fixes 的 d487c9857ca8fd139f955d8089ad1e53b31cb39d。
本轮交付提交：698933c（已推送 origin/codex/user-qa-fixes）。工作树：E:/mobileAgentRuntime/.tmp-budget-work/wt-f。
上一轮证据：docs/evidence/2026-09-17/f7a9152-next-round-closeout.md。主 CI 当前 main 仍为 95ee670，未混用。

## 1. 旧缺陷关闭（本轮之前已修，保持不变）

- Python 输出决策的旧漏参：pythonModelWireDecision 产出 value 与 field，broker 经生产 pythonModelRequest 传入 ModelRequest；两个 Adapter 对 field 存在而 value 缺失的组合前置拒绝（INVALID_CONFIG、零派发）。
- Runtime 多轮与摘要独立预算、公开 Responses probe 的真实四次派发（64/64/128/64）保持通过。
- model.invoke 作用域的严格持久化与撤权（拒绝超声明的模型集合、调用数、token；撤权清空范围）保持通过。

## 2. CI（F0：Release 单测依赖锁）

主 CI run 35196036902 的 check job 105119375931 在 :feature:providers:generateReleaseUnitTestStubRFile 解析 releaseUnitTestRuntimeClasspath 时拒绝 8 个未锁定 JUnit 坐标；license run 35196036983 与设备 smoke 均成功。本轮修复：

- feature/providers/gradle.lockfile 补齐 releaseUnitTestCompileClasspath 与 releaseUnitTestRuntimeClasspath（未关闭测试、未放宽严格验证）。
- 本地复证：:feature:providers:testReleaseUnitTest 成功（该任务即包含 generateReleaseUnitTestStubRFile）；verifyDependencyLock、verifyCiPins、verifyDependencyVerification 通过；全部 JVM 单测（shared:domain、provider-api、agent-runtime、skills-api、data:sqlite、feature:providers debug 与 release、app-android）成功。
- 完整本地门禁：licenseGuard、licenseGuardReverse、verifyCiPins、verifyDependencyLock、verifyDependencyVerification、debugEvidenceGate、reviewGate 全部通过（BUILD SUCCESSFUL，7m10s，1103 tasks；含 Debug 与 Review APK、SBOM、provenance）。
- 远端：针对 698933c 的 ci 与 license-guard 运行结果见本轮交接。


## 3. 作用域确认摘要（F2）

SkillsViewModel.scopeLabel 之前只接收 knowledgeBaseIds、hosts、methods，仅含 model.invoke 的声明会显示需要用户选择资源且默认无权限，而 confirmGrant 会存入 manifest 声明的全部模型集合与调用、token 上限。现在：

- 确认摘要展示模型身份列表、调用次数上限、token 上限，并注明每次运行另需 Run 费用上限。
- 允许的选择仍不得超出声明；approvePermissions 的后端校验保持不变，超声明即拒绝。
- 安装授权与 Run 费用许可是两个边界：安装页只描述安装范围，Run 费用另行授权。

## 4. Run 配额的生产入口（F1）

问题：普通 Chat 的 RunRecord.budgetJson 不含 maxModelTokens，而 broker 在访问共享 reserveModelCall 之前就要求该字段存在；生产 RunTools.pythonBudget 也要求持久 Run 上有正数配额。安装 grant 里的 maxModelTokens 不是 Run 许可。

本轮受控入口与冻结路径：

- 用户在 Agent 编辑器填写 Python 模型调用费用上限（每次运行，token），持久化到 AgentContextPolicy.pythonModelRunTokens；留空表示未授权，键被移除。
- 该策略随快照冻结；ChatViewModel 建 Run 时用生产 helper 计算 modelInvokeRunTokens(用户数字, 该快照绑定技能的已批准 model.invoke 上限集合)，取两者较小值；为 null 时不写 maxModelTokens。
- runBudgetJson(...) 是唯一的持久 Run 预算构造器；写入 maxModelTokens 后由共享 RunTools.pythonBudget 与 broker 读回，实际账本与预留一致。
- 未获配额时 broker 在派发前拒绝（零 HTTP）；没有为普通聊天暗加默认额度；包声明不会自动变成 Run 许可；缺省拒绝保持不变。


## 5. broker 到达与结算（G1，模拟器真机级）

新增 app-android/src/androidTest/.../PythonModelInvokeBrokerTest，7 项全部通过（mar_api36，23.2s，未访问收费 Provider）。路径：真实导入、真实授权、生产 RunTools 组装（含生产 pythonBudget）、真实隔离 CPython 与 IPC、真实 broker、真实 Adapter、MockEngine。

| 用例 | 到达阶段与断言 |
| --- | --- |
| manualProfileCapUsesTheRunAuthorizationAndSettlesOnce | 1 次派发，body 仅 max_tokens=512，结果保留，重复终态不再派发 |
| responsesProtocolKeepsItsNativeFieldOnTheSameRunAuthorization | 1 次派发，max_output_tokens=512，无 max_tokens |
| aRunWithoutTheFeeAuthorizationDispatchesNothing | Run 预算无 maxModelTokens：0 次 HTTP，结果非 Value（fail-closed） |
| aToolCapAboveTheRunCeilingIsRefusedBeforeDispatch | 工具要求 100000 超过 Run 上限：0 次 HTTP，派发前拒绝 |
| aMeasuredOverrunBlocksTheNextDispatch | 实测用量超预留后下一次被共享账本阻断，仍 1 次派发 |
| revocationBlocksTheNextDispatch | 撤权后第二次调用在审批前被拒，仍 1 次派发 |
| anUnknownOutcomeIsNeverReplayed | 无终止帧得到 UNKNOWN，重复终态不重放，仍 1 次派发 |

定位过程（脱敏）：审计链显示 broker/OK 之后是 invoke/UNKNOWN_OUTCOME/PYTHON_RESULT_UNCERTAIN，根因是用例的 Python 夹具误解了 broker 返回值形状：宿主桥返回的是 broker 的 value 对象本身，而不是含 requestId 与 status 的信封；被拒时由宿主抛 PermissionError。修正夹具后 7 项通过，该形状已写入用例注释。

## 6. 真实设备与收费 Provider 状态

- 已验证：mar_api36 模拟器、真实 CPython 隔离进程、真实 SQLite、真实 IPC、真实 Adapter；HTTP 由 MockEngine 承担，未访问收费 Provider。
- 未验证：物理设备、真实收费 Provider、294 份收费验收、正式发布。以上保持 PARTIAL，不冒充完整验收。

