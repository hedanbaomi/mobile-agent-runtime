<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# f7a9152 复核收口：真实入口回归与 P1-01 交付

审查基线：hedanbaomi/mobile-agent-runtime 分支 codex/user-qa-fixes 的 f7a9152a694441dcbc434950399196294d3e43c5。
本轮实际续做基线：同分支 a9686aff835764dee20a8fe330f1c651a6df1df7（已含复核包指认的 P1-01 修复 e84ec68 与 13722d4 / a9686af 两条入口测试）。
工作树：E:/mobileAgentRuntime/.tmp-budget-work/wt-f。

## 1. 本轮真正到达的入口与捕获结果

| 交付项 | 真实入口 | 断言到的可观测结果 |
| --- | --- | --- |
| P1-01 Python 出站决策 | pythonModelWireDecision（domain，broker 调用的同一函数）→ 真实 OpenAiCompatibleAdapter / OpenAiResponsesAdapter + Ktor MockEngine | 捕获两个协议 HTTP body：AUTO 无覆盖 0 个输出别名；MANUAL=profile 数值；模型 512 + Agent 4096 得 4096；AUTO + Agent 1024 得 1024；tool 512 覆盖模型 4096 得 512；每个合法组合恰好 1 个别名 |
| 出站契约（两协议） | 两个 Adapter 的 stream() | outputTokenField 非空而 outputTokenLimit 为空 → 0 次派发、终态 INVALID_CONFIG，不静默 AUTO、不丢显式上限 |
| Runtime 多轮 | AgentRuntime + 真实 OpenAiCompatibleAdapter + MockEngine | 首轮与工具后续轮都带同一 max_completion_tokens=5000，且无第二别名 |
| Runtime 摘要预算 | 同上 | 摘要请求带自己的 max_tokens=1024（policy 数字），普通轮仍带 max_completion_tokens=5000，互不污染 |
| 公开 probe 集成 | OpenAiResponsesAdapter.probe(profile, secret, GRANTED, opId) | 4 次真实派发（metadata / STREAM / TOOLS / IMAGE），输出上限序列 64/64/128/64；STREAM body 含 stream:true、TOOLS 含 tool_choice、IMAGE 含 input_image；返回 SUCCEEDED、charged=true |
| 授权范围持久化 | SkillRepository.approvePermissions（真实 SQLite） | model.invoke 的 modelProfileIds / maxModelCalls / maxModelTokens 落库并可回读；超过 manifest 声明的范围被拒绝；撤销后范围清空 |
| UI 状态 | ProviderModelUi + contextWindowLabel（复用 contextWindowTargetMatches） | 自动窗口显示 有效/未知/失效；失效时不再显示裸 legacy 数值；manual 显示用户数字 |
| Broker 集成（设备） | 真实导入 Python 技能 → IsolatedPythonRuntime → PythonSkillToolExecutor.model() → 真实 Adapter + MockEngine | PARTIAL：技能能在真实 CPython 内调用 model_invoke 并到达 broker IPC；broker 预算守卫按设计返回 RESOURCE_LIMIT，因为设备测试的 Run 预算端口未接生产 RunTools.pythonBudget。用例与阻塞详见第 4 节 |

## 2. 本轮变更的源码

- shared/domain/BudgetDecision.kt：新增 pythonModelWireDecision / PythonModelWireDecision——Python model.invoke 的单一输出决策（tool > 冻结 Agent > 模型高级 > profile 默认；AUTO 不发明 cap）。
- shared/domain/Profiles.kt：新增 ContextWindowState 与 ModelProfile.resolvedContextWindowState()；resolvedContextWindow() 委托它，运行侧与 UI 共用同一有效性判定。
- app-android/PythonSkillTools.kt：broker 的预留、别名、实发值与结算改用同一决策；pythonSkillTools() 新增 providerHttp 注入点（生产默认 container.http）。
- shared/skills-api/Permissions.kt 与 SkillArchive.kt：PermissionSpec / PermissionGrant 增加子模型范围并解析 manifest 声明。
- data/sqlite/SkillRepository.kt：approvePermissions 校验并持久化子模型范围；合并授权取交集与最小值。
- app-android/SkillsViewModel.kt 与 integration/RuntimeIntegration.kt：授权/撤权路径传递并清除该范围。
- feature/providers/ProvidersUi.kt 与 app-android/ui/MainScreens.kt：模型行显示有效/未知/失效。

## 3. 新增/加强的测试（本地已执行通过）

| 文件 | 覆盖 |
| --- | --- |
| shared/provider-api/PythonModelInvokePayloadTest.kt | 生产决策到两协议真实 payload 字段矩阵 + field-without-cap 拒绝 |
| shared/provider-api/AutoOutputLimitPayloadTest.kt | 新增两个协议的前置拒绝（0 派发、INVALID_CONFIG） |
| shared/provider-api/ResponsesProbeBudgetTest.kt | 公开 probe 真实派发 4 次、每特性 cap 与结果分类；删除空列表恒真断言 |
| shared/agent-runtime/ContextCompactionRuntimeTest.kt | 多轮同一决策 + 摘要独立预算 |
| shared/domain/AutoContextWindowTest.kt | 有效/未知/失效状态与 canonical 与 legacy 目标一致 |
| data/sqlite/SkillRepositoryTest.kt | 子模型范围持久化 / 超范围拒绝 / 撤销清理 |
| feature/providers/ProviderContextWindowLabelTest.kt | UI 标签与运行侧同一判定 |

## 4. 结果与边界

- 本地 JVM 门禁：shared:domain、shared:provider-api、shared:agent-runtime、shared:skills-api、data:sqlite、feature:providers 的定向测试全部通过。
- feature:providers 首次引入 JVM 测试依赖，已用 --write-locks 更新 feature/providers/gradle.lockfile；verifyDependencyLock 与 license 门禁需远端 CI 复证。
- BROKER 设备集成 BLOCKED：app-android/src/androidTest/PythonModelInvokeBrokerTest.kt 能编译，并已在 mar_api36 模拟器上跑到真实 CPython + broker IPC；但该用例自己构造的 PythonRunBudget 与生产 RunTools.pythonBudget 不是同一端口实例，broker 预算守卫因此返回 RESOURCE_LIMIT。修复方向明确：让设备用例通过生产 RunTools/AgentRun 端口取得预算，或把 Run 预算端口暴露为可注入的窄接口。本轮模拟器随后从 adb 掉线（列表为空），无法再复现；用例保存在本地 .private/f7a9152-next-round/，不进提交。
- 真实收费 Provider、真机、294 份收费验收、正式发布均未执行；未推 main、未 force、未部署。
