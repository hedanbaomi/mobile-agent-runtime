<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# mobileAgentRuntime

Android 优先的 BYOK Agent Runtime：本地知识库、多模态资料处理、可配置 Prompt 与模型参数、受控 Skills 执行，以及独立远程公告。

**当前状态：** 0.1.1、0.2、0.3 的最终复核与修复已收口；1.0 本地门禁已完成严格依赖校验、全仓 `check`、API 31 instrumentation 与 debug APK/SBOM 打包。正式 AAB 仍因未提供用户 release keystore 而按设计 `BLOCKED_SIGNING`，版本号暂不擅自改为 1.0.0，后续与用户共同安排 release。F-001（工具能力开关相关进程退出）曾稳定观察到两次但仍无法稳定复现，保持 `candidate_intermittent`，不能写成已修复。公告 issue #1 的 Android、Worker/Admin 修改已部署到 Cloudflare，公网签名 feed、条件 304、源码归档与 Access 匿名拒绝后检通过；完整 version/source hash/备份见生产证据。

源码：<https://github.com/hedanbaomi/mobile-agent-runtime>

## 从这里开始

所有 Agent 开工前必须依次阅读 [agent.md](agent.md)、[HANDOFF.md](HANDOFF.md)、[技术实现方案](docs/IMPLEMENTATION_PLAN.md)，并查阅本次任务涉及的专题文档。完成、受阻或中断前必须维护交接记录。

| 文档 | 用途 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 自动加载入口；指向统一工作规则 |
| [agent.md](agent.md) | 开工、执行、验证、收工的强制规则 |
| [HANDOFF.md](HANDOFF.md) | 当前事实、未完成工作、下一步及维护记录 |
| [需求与决策依据](docs/REQUIREMENTS.md) | 对话来源、范围、已定要求与实施补充 |
| [技术实现方案](docs/IMPLEMENTATION_PLAN.md) | 模块、数据模型、端口契约、任务顺序 |
| [知识库与检索](docs/KNOWLEDGE.md) | 多模态导入、向量空间、恢复和引用 |
| [Skills 与安全](docs/SKILLS_AND_SECURITY.md) | Python 隔离、权限、模型调用、数据边界 |
| [公告系统](docs/ANNOUNCEMENTS.md) | Android、Worker、D1、管理端和协议 |
| [验收与证据](docs/ACCEPTANCE.md) | 可复现验收场景、证据格式、完成门槛 |
| [本轮核查记录](docs/DOCUMENTATION_CHECK.md) | 文档、许可文件、Git/CodeGraph的实际结果与限制 |
| [许可证政策](LICENSE_POLICY.md) | AGPL-3.0-only、防误改、第三方归属 |
| [ADR-0001](docs/adr/0001-build-matrix-and-provisional-identity.md) | 构建矩阵与仓库身份 |

## 本地检查

```powershell
.\gradlew.bat licenseGuard
.\gradlew.bat licenseGuardReverse
.\gradlew.bat check --dependency-verification=strict
.\gradlew.bat :app-android:assembleDebug
.\gradlew.bat :app-android:generateDebugSbom
python -m reuse lint
```

依赖解析优先使用阿里云 Maven 镜像，因为本机访问 Maven Central 会出现 TLS 握手失败。远程 CI 仍可解析官方仓库。

## 许可证

本项目第一方代码和文档采用 **AGPL-3.0-only**。不得被脚手架默认的 MIT 或其他许可证覆盖。第三方依赖、导入的知识库和外部 Skills 保留原权利归属；详见 [许可证政策](LICENSE_POLICY.md)。
