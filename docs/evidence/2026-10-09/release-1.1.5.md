<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# v1.1.5 正式发行

2026-10-09，Asia/Taipei。用户授权以已验证的功能源码发布 v1.1.5，正式 versionCode 15。

## 范围

基于 e62099961ac96f8b55796bbbd907e82799d05f99；包含前置 DOCX 本地纯标点修复。仅提升发行版本及维护发行文档，无新的数据库、协议、权限或 Provider 行为变更。公开更新日志只描述自 v1.1.4 以来的功能改进，不列工作中的测试版本。

既有功能验证见 [DOCX 专项](docx-punctuation-embedding.md) 与 [对话展示专项](chat-citations-and-tools.md)：四份原始文档导入成功、304段原文保全；引用投影28条单元各变体通过、API36模拟器90项通过、DSH / DeepSeek V4.1 Flash Fast只读审阅APPROVE，源码CI成功。该功能证据不冒称当前正式产物或用户真机验收。

## 发行门禁与边界

本轮须运行 licenseGuard、check、REUSE，普通PR经远端检查合并；随后在干净合并源码运行 releaseGate、许可反向、工作流及 Windows distZip。核验正式证书连续、包名/15/1.1.5、非调试、arm64、16KiB对齐、源码/SBOM/provenance/APK哈希绑定；完整附件服务器名称、大小及SHA-256逐项核对后才设 latest stable。

保护根工作区84份原有WIP，根main同步使用逐文件备份与恢复，不纳入源码提交。原始用户文档、诊断与签名秘密不发布。应用更新仅查询官方GitHub stable Release，无需修改公告服务、Provider配置或生产资源。

合并SHA、CI、正式产物与公开收据在完成后补充；当前为发行准备记录。用户实体设备、真实付费Provider、OEM及物理USB验收仍由人工完成。
