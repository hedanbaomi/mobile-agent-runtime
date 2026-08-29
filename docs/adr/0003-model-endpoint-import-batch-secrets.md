<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0003：ModelEndpoint、导入批次与密钥生命周期

- 状态：已采用（0.2/0.3 实现）
- 日期：2026-08-29
- 来源：审查报告 F-007–F-018

## 背景

单值 `ModelRole` 把 Chat、Vision、Embedding、Reranker 混在一起，但图片是 Chat 输入模态，工具是 Chat 功能，Embedding/Rerank 是独立操作。知识库 ZIP 被误判为办公文档；导入缺少批次与一次性付费授权票据。Provider 删除文案曾承诺移除凭据，而密钥没有退休/回收闭环。

## 决定

- 引入 `ModelEndpoint`（operations / inputModalities / features / verification）。`ModelRole` 仅作快照与迁移兼容的派生字段。Agent 只要求主 Chat；Vision 为可选覆盖；Embedding 归知识库；Reranker 仅在存在 RERANK 模型时展示。
- 能力探测区分 USER_DECLARED 与 PROBED，并写入 `capability_probes`。工具探测使用无副作用虚拟函数，图片探测使用内置 1×1 PNG。
- 手机一级导航为对话、智能体、知识、技能、更多；服务商/公告/MCP/设置/关于/检查器进入更多。编辑状态拆为 selected / editorOpen / editorDirty。
- Global Root Prompt 以可空覆盖保存在 `app_prefs`，插在不可编辑运行时协议与 Agent 提示词之间，不能授予工具、网络、文件或 Python。
- 密钥状态为 ACTIVE / RETIRED / ORPHANED / DELETED；删除 Provider 前展示模型与快照引用数；无引用密文退休并 GC。
- `KNOWLEDGE_ARCHIVE` 独立于 `OFFICE_ARCHIVE`。导入入口为添加文件、导入文件夹、导入 ZIP。`ImportBatch`/`ImportItem` 绑定 KB generation。Vision/API Embedding 确认后签发一次性 `consent_tickets`，由独立前台 Work 消费；UNKNOWN_OUTCOME 不自动重放。

## 替代方案

- 继续用 ModelRole 加 capability 字符串：运行时选模路径会再次错位。
- 把知识库 ZIP 留在 OFFICE_ARCHIVE：无法承载目标数据集且会误走 DOCX 解析。
- 在 ViewModel 协程里直接调用付费继续：进程被杀后无法与一次性授权对齐。

## 影响与验证

schema 升至 v11。JVM 覆盖 endpoint 映射、ZIP 安全反例、密钥 GC、ZIP 展开与根提示词层级。未执行 500 文件/约 500MB 实机批次、杀进程/ENOSPC、真实 Vision 或付费探测。F-001 仍需新 APK SHA 与 Logcat。1.0（F-019 CI/正式 release）不在本 ADR 范围。
