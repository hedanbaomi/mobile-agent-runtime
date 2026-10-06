<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 输入、上下文压缩及设备访问修复

2026-10-06。R18/R21/R23/R34，U02/U07、C20—C25、S15/S18。用户授权修复、提交、推送及普通 PR 合并；后续明确要求完成后对修复工作独立复查，通过后正式发布 v1.1.0（versionCode=5）。

## 现象与已确认原因

- 用户两份诊断均来自 v1.0.2，未包含本轮修改。摘要派发后失败，但旧摘要请求没有诊断 sink；日志不足以确认 Provider 的具体拒绝或输出内容。原始日志、视频、设备信息仅留本地私有证据。
- 长历史输入边界基线：输入七个字符产生七次额外 shell 重组（1→8）。ChatRoute/MainApp 读取包含输入的完整状态，输入框只保存字符串而没有保留 IME composition/selection。
- 已连接并选定 Shizuku 时仍暴露零个 shell 工具；旧代码要求额外 Agent shell.execute grant，与用户全局危险模式确认的预期不符。
- 首次配置完整设备文件的确认回调在 editorAgentId 为 null 时直接返回，没有授权和状态提示。

## 修复

- 输入草稿在 Composer 内读取，本地 TextFieldValue 保留中文 composition 与光标；真实外部清空/会话切换仍同步。全局 shell 与历史投影排除输入；Markdown 行内解析按内容缓存。
- 摘要保留 outputTokenField 和禁止内容采集的诊断 sink；只兼容单个完整 JSON/no-language 围栏，仍严格拒绝多围栏、解释前缀、额外字段、空值及截断。
- 明确结束的摘要失败保留 FAILED checkpoint 与已知 usage；完整原文及图片符合硬预算时继续本次 Run，并停止其后续摘要请求。超窗、取消、无终帧、结果未知仍停止；不使用半成品，不自动重试摘要或重放工具。
- 新配置摘要预留 4096、摘要估算上限 16384；显式已有配置不覆盖。基础设置仅展示自动开关、预算摘要、推荐设置和高级展开，推荐设置保留 Python 调用预算及未知键。
- 内置 Agent shell 直接使用冻结的全局危险模式确认；factory 清除附带 Skill 的 shell 身份。独立 Skill executor 仍须 canonical grant。所选通道、当前策略与确认 revision 在派发、审批结算和缓存披露前复核；关闭再开启不能复活旧调用。命令使用设备语法，如 pm list packages（等价于 adb shell 后的命令）。
- 新 Agent 的完整设备确认暂存于编辑草稿，保存后通过 canonical sink 写入 grant-only 计划；不替换默认目录/Thread binding。取消/编辑器切换丢弃，过期 token 拒绝，策略变化要求重新确认，失败回滚新 Agent并保留两份草稿。

## 验证与边界

| 验证 | 本轮证据 |
| --- | --- |
| IME 基线 | 模拟器真实 production presentation seam：七个输入字符导致 shell 1→8，反例 FAIL |
| Runtime 基线 | 新增压缩回归在修改前 3 FAIL；修改后 shared agent-runtime 的 21 项压缩测试通过 |
| API34 输入/压缩/审批 | 13 项通过：长 Markdown 输入及光标、Android InputConnection 中文 composition、基础/高级设置、SQLite 压缩记录及账务、审批 UI |
| API34 授权集成 | AgentDeviceAccessDeviceTest 5/5：首次 Agent 保存、取消/过期 token/策略变化、失败回滚显式重试，production factory 的 Agent 审计身份、策略/通道撤销后的缓存拒绝、旧审批拒绝 |
| Shell 单测 | DangerousAgentShellAuthorizationTest 6/6：全局确认、独立 Skill 拒绝、旧 Run 撤销、通道失效、高风险确认、未知结果不重放 |
| Android 构建 | assembleDebug 与 assembleDebugAndroidTest strict/offline 通过 |
| 独立只读审阅 | 初次 DSH 静态 nullable 编译疑虑由实际编译及明确表达式核验；原生独立审阅要求补 factory 集成测试，补齐后扩展审阅及修复完成后的最终修复复查均 APPROVE |
| 完整门禁 | 修复树干净检出 strict/offline licenseGuard/Reverse/check/pins/lock/checksums/YAML 通过（3m27s）；1.1.0 版本修订后再次通过（2m01s），REUSE 886/886。根目录初次 licenseGuard 被受保护的既有视频素材草稿缺 SPDX 阻挡，不修改/纳入这些草稿 |

设备验证使用专属 API34 模拟器及合成 transport，不调用真实模型或执行真实高权限 shell。首次授权成功测试替换 full-device sink，验证真实 VM/SQLite Agent/default 及 grant-only 请求，不等同真实 full-device backend/grant 落库验收。真实 realme 输入帧耗时、Shizuku Binder、物理 USB 和真实 Provider 摘要质量均未在本轮验收。发布须使用已合并源码、干净 checkout、既有正式签名与完整附件；修复工作的最终独立复查 APPROVE；发布工作按构建、签名和附件门禁执行，不在该审查范围。

## 可复现命令

- Gradle：licenseGuard licenseGuardReverse check verifyCiPins verifyDependencyLock verifyDependencyVerification verifyWorkflowYaml，附 --offline --dependency-verification=strict --console=plain。
- Android：assembleDebug assembleDebugAndroidTest，同样严格离线。
- Instrumentation runner：runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner；类为 ChatImeResponsivenessDeviceTest、ContextCompactionUiTest、ChatContextCompactionDeviceTest、ChatApprovalCardUiTest、ChatToolApprovalDetailUiTest、integration.AgentDeviceAccessDeviceTest。
- REUSE：python -m reuse lint。
