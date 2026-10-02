<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-10-02 Review 修复集成与 APK 交付

本文件为合并前的验证快照。用户已授权补齐许可、修复附件问题后提交/推送/合并及 debug 签名 Review APK；本轮没有发布正式 release 或调用真实付费 Provider。

基线 main：2d0478c0a99b2b1fe8b50894225e6239c34cf88a。代码提交：342a7d7d8d315231b5a7aa9f259d7e352a4c12a9，分支 codex/code-review-repair-20261002，已推送并创建 [PR #34](https://github.com/hedanbaomi/mobile-agent-runtime/pull/34)。最终交付以普通 CI/合并记录和合并提交的干净 APK provenance 为准。

## 修复与验证

第一阶段 10 项代码审查修复见 [代码修复报告](code-review-repair.md)。暂停发布后，又完成附件中的 Python API26/压缩与标准库、工作区根目录、UI/诊断和规格修正，以及 Golden/Wired/UI 夹具校正。18 项发现和全部 100 条非 PASS 记录见 [附件报告](attachment-repair.md) 与 [逐条清单](attachment-case-dispositions.json)；原 598 条状态保留，环境与真实 Provider 未覆盖项未改写成 PASS。

strict reviewGate/check（含许可、供应链与 lint）、1768 项 JVM、REUSE、包内 notices 与 46/46 native 对齐均通过。API26 Review 37/37、API31 Review 134/134、API35/16KiB Review 19/19 实际通过；初轮测试夹具失败和冷启动连接超时保留，后续同一超时设置复测不删除原失败。独立只读审查均 APPROVE，详情及边界见附件报告。

## 许可与提交边界

第一方 AGPL-3.0-only 政策、guard 和 CI 保留。新增受审查的第三方核心/固定模块保留原 PSF/MIT/CC0 文本及固定来源/构建输入摘要；Git 属性保持已固定文件的原始字节。stripping 后的包内 native hash 与构建输入 hash 分别记录，不混称官方预编译核心或同一个 artifact。

本地 docs/defects.md 仅补齐 SPDX 文件头，原正文保留。四份同内容的项目测试归档只加许可旁注，不改 ZIP；原报告、归档及其本地旁注未发布。86 项初始提交只含本轮代码/测试/专题/ADR/许可和安全处置摘要；本次修正该集成摘要为实际阶段状态。HANDOFF 仅暂存本轮现行任务/时间，既存历史 WIP、两份计划、test-report 和原附件保持未提交。

## 后续交付边界

等待该 PR 的常规 CI 完成后正常合并，不直推 main 或绕过检查。从合并 SHA 的干净受管工作区生成 debuggable=false、Android Debug 签名 Review APK，附 SHA256、SBOM、provenance、许可/原生校验与本轮证据包。预合并或 dirty 包不作为最终交付。

真实 Provider 付费自治、物理 USB/Shizuku、原框架 ANR 因果、性能门槛与长跑仍未完整验收；源码和本地验证收口不替代这些边界。用户追加的优化任务在本轮交付完成后开始，今日太平洋时间 10:00 前必须完成其阶段性 Git 与 Review 包收尾。
