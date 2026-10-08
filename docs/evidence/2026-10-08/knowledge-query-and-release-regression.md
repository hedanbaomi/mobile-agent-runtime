<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 知识库查询报错调查与 v1.0.0 之后修复回归

2026-10-08。用户要求检查知识库调用的未知结果，修复自身缺陷，并在历次修复没有复发后发布 v1.1.4。原始诊断仅用于本地取证，不进入源码或发布附件。

## 诊断与自身缺陷

首份日志未保留早先报错；用户随后立即复现并提供新日志。最新运行UTC05:41:57派发8图、2条消息、0工具的Chat Completions分析请求；HTTP200，正文858,514字节，运行76,610ms。正常收到ReasoningDelta、TextDelta、Usage和唯一Completed，未出现Failed、拒绝或ToolCall。随后Runtime以FAILED/INVALID_RESPONSE终止，模型轮1、工具0。

源码对应的本地拒绝分支是图片证据正文超过16,000字符或缺少可用notes。诊断未捕获正文，不能测量实际字符数或排除空证据，但已定位为完成回复后的本地回执拒绝；超长8页分析是与字节量一致的解释。知识库此前返回10条带citationId的有效hits，不能归因于知识检索或没有视觉能力。服务为用户确认的Command Code GOAT，配置deepseek/deepseekv4.1flash、OpenAI兼容Chat Completions，模型ID保持不变。[Command Code文档](https://commandcode.ai/docs/provider)和[DeepSeek视觉文档](https://api-docs.deepseek.com/guides/vision/)亦支持图片接口。

修复使用专用已完成拒绝类型：流完整drain且Completed、无失败/拒绝/工具后，清除在途状态并按原来源二分组；单图最多一次精简恢复。每个尝试仍复核授权、哈希/大小和输入预算，继承同一模型、完整参数、手动别名/AUTO，计入同一Run预算及usage。子组回执及时保存，主回答只得到完整有效证据；不丢原图、不用截断父回复，不重执行知识工具。断流、Provider错误、协议不全、UNKNOWN及Completed之后传输异常不触发恢复。

初始4项真实Runtime恢复反例全部失败，记录保留；最终完整agent-runtime与10项恢复测试、两协议9项输出预算测试全部通过；覆盖已完成拒绝、23次分析上限、同一deadline、预算耗尽、撤权/取消和传输异常，不重放未知。最终API36设备233项全部通过，包含新的真实知识工具8图恢复及完成拒绝后的取消；合并既有非Debug/更新/常驻专项，去重240项零失败、零跳过。另GatewayKnowledgeContinuationTest3/3验证真实adapter的有效JSON、SSE末尾usage/DONE、下一工具，以及畸形返回分类；全部为合成引用和图片，不含用户材料或凭据。真实付费Provider请求未执行。实现契约见[ADR-0030](../../adr/0030-request-scoped-visual-budget.md)。

## 已发现并修复的回归缺陷

新 Agent 的完整设备工作区提交失败后，默认目录偏好依赖未开启的 SQLite cascade 清理，留下失去 Agent 的偏好。下一次进程初始化因此失败。专用 Android 设备的schema30数据实测有1条孤立偏好、49条有效偏好。新增JVM反例首轮4项中1项失败；最终7项全部通过。升级实测schema31、孤立项1→0，49条有效偏好、工作区及原488条Grant逐行保全；启动初始化新增14条Grant另记，不把新增与修改原授权混淆。独立只读审阅最终APPROVE，关闭迁移门禁。

实现及迁移边界见 [ADR-0033](../../adr/0033-agent-rollback-default-cleanup.md)。这不是用户所报知识库未知结果的已确认原因。

## 回归范围

以 v1.0.0 到6deb89d的9组合入记录为依据，建立69个去重测试文件与修复行为的映射。文件数不等于测试场景数。

| 合入修复 | 主要反馈路径 |
| --- | --- |
| PR44：统计默认值与 Skill 文案 | SkillsUiTest、AnnouncementRepositoryTest |
| PR45：签名更新和安装 | AppUpdatesTest、AppUpdateDeviceTest，下载/哈希/证书与真实 stable feed |
| PR46：中文输入、上下文、设备授权 | ChatImeResponsivenessDeviceTest、ContextCompactionUiTest、ChatContextCompactionDeviceTest、AgentDeviceAccessDeviceTest |
| PR47/48：发送准备与已准入长回复 | ChatSendOffMainThreadDeviceTest、ChatRunOwnershipDeviceTest、PythonModelInvokeBrokerTest、RunToolsReplayDeviceTest、StreamingRequestTimeoutTest |
| PR49：知识库续轮、归档、常驻 ADB | Provider/history 与视觉续轮测试、ArchiveNavigationDeviceTest、ResidentAdb 的实际 shell UID2000 激活和跨进程恢复 |
| PR50：原始图片分组及64次请求预算 | ChatVisualTransferDeviceTest、VisualToolBatchesTest、请求/输出预算测试 |
| PR51：动态上下文、禁用 Skill、工作区授权 | ContextCompactionRuntimeTest、DisabledSkillBindingUiTest、RuntimeThreadWorkspaceDeviceTest、视觉输出预算测试 |
| PR53：SAF 增删改与版本条件 | RuntimeSafToolExposureDeviceTest、WorkspaceBackendTest、Shizuku/Wired 的 adapter/存储与版本条件测试 |
| 知识库基础链路 | KnowledgeRuntimeDeviceTest（真实 ONNX/USearch/SQLite）、RunToolsEnrichDeviceTest、API 查询 owner/cache/unknown claim 等 JDBC 测试 |

首轮 API36 设备231项：230通过、1失败、0跳过。中文组合输入断言在3次独立运行中失败；暂停专用设备的系统 LatinIME 后，原断言通过，键盘已恢复。这说明真实键盘干扰测试主动驱动的 InputConnection，不能将此写成产品输入回归。更新下载的另2项独立测试在旧数据重开时暴露上述 Agent 回滚缺陷，须在修复后重新执行。

最终执行证据：

- strict/offline完整check、licenseGuardReverse、verifyWorkflowYaml及Debug/Review构建通过（1126 actionable tasks，7m53s）；新增Provider合成续轮测试随后3/3通过，最终提交前check、licenseGuardReverse、verifyWorkflowYaml再次通过（996 actionable tasks，5m39s）；JUnit保留报告2238项0失败/错误/跳过，其中SQLite406、Provider282、Android三变体各296。报告数包含按Gradle输入判定为up-to-date的测试，不冒称全部重新执行。REUSE945/945及差异空白检查通过。
- API36修复后按场景去重238项、0失败、0跳过：Review有效214项、更新5项、Debug-only本地模型fixture14项、隔离中文输入补足1项，以及常驻进程4个不同方法。重复seed/输入案例不重复计数。
- Review初始229项有15失败：14项用了要求Debug/本地明文HTTP的fixture（其中5项直接断言BuildConfig.DEBUG），另1项是主动创建InputConnection受到系统LatinIME干扰。正确Debug包14/14通过；单独禁用并停止专用设备LatinIME后输入2/2通过，键盘设置恢复。产品源码与断言未为此修改，不外推真实OEM输入。
- 常驻ADB使用实际shell UID2000、独立app_process服务；宿主杀App后原服务存活，重新连接读回原文件；冷启动撤权及服务真实丢失后重新激活通过。仅杀精确识别的本测试服务PID。
- 知识库真实ONNX/USearch/SQLite4项及生产知识工具调用/引用enrich1项通过；新增真实查询验证不把手造hits作为检索成功证据。实际持久SAF授权增删改与相邻变动测试通过。
- Android旧数据库重开成功；新Agent失败提交后检查孤立偏好并再次Migrations.apply，共5项通过。JDBC删除/迁移正反及故障注入7/7通过。

原始失败运行、配置不当的Review运行及修复后结果均保留于本地取证目录；Windows Gradle transform临时目录移动失败与变体命令错误不作为产品通过证据。

## 发布边界

v1.1.4/code12候选修复上述本地回执拒绝，并修复历史回归发现的Agent偏好清理缺陷。最终完整门禁、新Android用例和独立复审已通过；正式签名及发布仍需干净合并源码releaseGate和附件核验，收据另行记录。真机OEM输入/SAF差异、真实Shizuku Binder、物理USB和付费Provider质量仍为单独验收，不以模拟器和合成服务替代。

最终候选strict/offline `check licenseGuardReverse verifyWorkflowYaml` 与 Debug/DebugAndroidTest 构建通过，1070 actionable tasks、6m39s；REUSE946/946合规。源码独立审阅APPROVE，最终设备233/233通过（126.719秒）；与既有专项去重240项0失败0跳过。独立只读复审最终APPROVE，两项门禁均闭合。保留原失败与fixture配置/IME隔离证据，不将合成Provider或模拟器等同用户真机。

最终JUnit保留报告2249项、0失败/错误/跳过（含up-to-date）；最终重构建和运行时复跑385 tasks/1m45s通过，Windows桌面工具distribution及许可检查通过。
