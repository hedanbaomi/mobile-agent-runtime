<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# v1.1.2 知识库续轮与对话归档

范围：R02/R08/R12/R34/R37；K04/K08、C26—C29。本文区分用户日志事实、源代码缺陷与本地验证，不将模拟响应视为真实服务验收。

## 故障证据

用户的 v1.1.1 DEBUG 诊断中，首轮模型成功，知识搜索和工作区列举均完成；第二轮带四张图片返回 HTTP 400 / PROVIDER_REJECTED，运行被错误映射为 INTERNAL。用户重试仍返回 400。日志未保留服务端响应正文，因此不能断言服务商只因某一个字段拒绝。

源码确认两个协议缺陷：同批工具结果之间插入了视觉 user 消息；显式推理只用于显示，工具续轮未回传。用户提供的六份 PDF 和原始诊断仅用于本地取证，没有提交至仓库或发布资产。

## 修复

- 收齐一批工具结果后再附图片；旧完整交换仅在请求投影修复顺序，不改写消息 ID、时间或内容。
- Chat 工具请求独立重放显式推理，保留包括纯空白内容，计入实际输入预算；预览和请求诊断不泄露该字段。无工具 Chat 和 Responses 不受重放上限阻断。
- PROVIDER_REJECTED 保留类型并显示明确配置提示，不自动重放模型或工具。
- 长按对话使用宿主传入的操作列表，当前为归档；空列表不生成菜单。已归档对话只读，可从设置→数据与备份查看和恢复。
- Schema 30 事务添加默认 false 的 archived 字段；旧数据保持活动，迁移失败回滚；备份保留状态。归档和 Run 准入共享数据库事务，新 USER 写入也复核归档；准入失败保留草稿，不生成虚构 Run 或助手错误。

## 验证记录

- 工具循环新增两项 MockEngine 回归先失败：混合搜索图片/工作区工具顺序，以及推理续轮；修复后受影响测试通过。
- 纯空白 SSE 片段回归先失败，再修复解析、收集和持久化；测试覆盖跨片段脱敏及纯空白内容序列化/SQLite 回读。
- SQLite 回归覆盖 v29 升级、重复升级、失败回滚、关闭重开、所有非终态拒绝归档，以及不同仓库实例间归档/USER/Run 准入次序；导入导出覆盖新状态和旧包缺省。
- 独立复查先提出推理空白、非重放路径上限、归档准入竞态三项问题，均补修；最终生产源码门 APPROVE；执行验证由主协调者独立完成。

本地日志和 receipts 位于受忽略的 `.private/kb-retrieval-112-20261006/`。发布状态由正式 Git tag、GitHub Release 与发布验证资产单独核验。

## 验收边界

受控协议测试不能证明用户实际服务当前接受所有图片/模型配置。未进行收费的真实 Provider 调用、用户原库全量重跑、实体 arm64 设备升级或 OEM 长稳验收。发布后应使用原对话和知识库复测；不承诺补造旧版未保存的推理。


## 最终执行结果

受影响单元（strict dependency verification、offline）：

| 模块 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| domain | 76 | 0 | 0 | 0 |
| agent-runtime | 77 | 0 | 0 | 0 |
| provider-api | 278 | 0 | 0 | 0 |
| app-debug | 257 | 0 | 0 | 0 |
| sqlite | 396 | 0 | 0 | 0 |
| serialization | 19 | 0 | 0 | 0 |

干净公开工作树 `check reviewGate licenseGuard licenseGuardReverse verifyCiPins verifyWorkflowYaml` 全部通过；其 JUnit reports 共 2079 项、零 failure/error（包括不同构建变体，不能视作不同产品场景数）。REUSE 899/899 文件合规。CodeGraph 同步完成。

API34/x86_64 专用测试模拟器执行 `GlobalConversationUiTest`、`ChatRunOwnershipDeviceTest`、`ArchiveNavigationDeviceTest` 和 `KnowledgeRuntimeDeviceTest`，最终 30/30 PASS。涵盖真实设置导航、长按与点击分离、空宿主操作列表、只读恢复、跨 VM preflight 归档与草稿保留，以及真实 bundled SQLite/ONNX/USearch 本地运行。设备首轮发现归档阻挡的提示过于笼统，补修固定权限提示后整组重跑通过。

独立只读复审使用 `gpt-6.1-sol/high`，最终生产源码门 APPROVE；该审查不声称执行测试或审查发布动作。最终实现保持未完成外部尝试不自动重放、旧消息/快照/授权不改写。源码和私有 receipts 的职责分开；用户原文和私人附件未进入公开 fixture。
