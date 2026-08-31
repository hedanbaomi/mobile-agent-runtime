<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# mobileAgentRuntime

mobileAgentRuntime 是一个以 Android 为主要运行环境的 BYOK（Bring Your Own Key）智能体应用。模型服务由用户自行选择，资料、知识库、Skills、会话和权限状态保存在本地；应用负责上下文编排、检索、工具调用、审批与审计，而不是把用户数据集中到项目服务器。

源码仓库：<https://github.com/hedanbaomi/mobile-agent-runtime>

> 当前应用元数据仍为 `0.1.0`。仓库可以构建 Debug/Review 包，但尚未发布正式签名的商店版本。

## 主要能力

- 配置 OpenAI-compatible 模型服务、模型角色、上下文预算和输出预算。
- 创建 Agent，并为不同会话冻结 Provider、模型、Prompt、Skill 和权限快照。
- 导入文件、文件夹或知识库 ZIP，在设备上建立文本与向量索引。
- 导入和启用本地 Skills；Python Skill 在受限运行时中执行，不直接获得宿主文件系统或系统命令权限。
- 使用内置联网搜索、MCP、请求检查器和脱敏诊断导出定位问题。
- 通过应用私有工作区或用户明确授权的 SAF 文件夹读写文件。
- 可选接入 Shizuku 或 Windows 有线 USB ADB Companion，为经过批准的工具提供更高权限。
- 接收独立签名的远程公告，不依赖模型 Provider。

## 安装与首次使用

应用支持 Android 8.0（API 26）及以上版本。当前没有正式 release 时，可以从源码构建 Debug APK：

```powershell
.\gradlew.bat :app-android:assembleDebug --dependency-verification=strict
adb install -r app-android\build\outputs\apk\debug\app-android-debug.apk
```

首次打开后按以下顺序配置：

1. 在“更多 → 服务商”中添加 Provider、API Key、模型 ID 和预算；需要真实能力探测时，阅读费用提示后再授权请求。
2. 在“智能体”中创建 Agent，选择对话模型，并按需绑定 Skill、知识库和工作区权限。
3. 在“知识”中导入文件或文件夹并等待索引完成。含图片的资料需要额外配置视觉模型，否则会保持等待而不会静默丢弃。
4. 回到“对话”新建会话。会话会固定当时的 Agent 配置，之后修改 Agent 不会悄悄改变已有会话。
5. 遇到问题时，在设置中开启诊断记录，复现后导出脱敏诊断 ZIP；诊断默认关闭，也不会记录 API Key、完整命令、文件正文或模型输出。

## 文件、Skills 与高权限工具

默认情况下，Agent 只能调用显式注册的 typed tools，并同时受 Agent grant、会话快照、工作区范围和逐次审批约束。

- 应用私有工作区用于 Skill 产物和应用内部文件。
- SAF 工作区由用户通过系统文件选择器授权，可随时撤销。
- Shizuku 与 Wired ADB 是两个平级的可选 Authority；一个不可用时不会自动降级或切换到另一个。
- `shell_exec` 只有在用户选择 Authority、授予相应 capability 并开启 Dangerous Mode 后才会出现。高风险操作仍需确认，并在执行前重新校验权限。

项目不提供 Root、应用内无线 ADB、DPC、Accessibility、PTY、Termux 集成，也不会把桌面 PowerShell 或宿主 shell 直接暴露给模型。

## 从源码构建

准备以下环境：

- JDK 17
- Android SDK 35 与可用的 Android build-tools/platform-tools
- Git；运行许可检查时还需要 Python 3 和 `reuse`

常用命令：

```powershell
# 编译 Debug APK
.\gradlew.bat :app-android:assembleDebug --dependency-verification=strict

# 运行 JVM/Android lint 等仓库检查
.\gradlew.bat check --dependency-verification=strict

# 许可、锁文件与依赖验证
.\gradlew.bat licenseGuard licenseGuardReverse `
  verifyCiPins verifyDependencyLock verifyDependencyVerification `
  --dependency-verification=strict
python -m reuse lint
```

主要目录：

| 目录 | 内容 |
| --- | --- |
| `app-android/` | Android 应用、集成层、诊断与设备测试 |
| `feature/` | 对话、Agent、知识、Skills、设置等 Compose UI |
| `shared/` | 领域模型、Provider、Agent runtime、工具与桥接协议 |
| `data/sqlite/` | 本地持久化、迁移、权限与审计仓库 |
| `desktop/bridge/` | Windows 有线 USB ADB Companion |
| `services/announcements/` | 独立公告 Worker 与管理端 |

参与开发前请阅读 [AGENTS.md](AGENTS.md)、[agent.md](agent.md) 和 [HANDOFF.md](HANDOFF.md)。许可证政策见 [LICENSE_POLICY.md](LICENSE_POLICY.md)。

## 许可证

本项目第一方代码和文档采用 **AGPL-3.0-only**。不得被脚手架默认的 MIT 或其他许可证覆盖。第三方依赖、导入的知识库和外部 Skills 保留原权利归属；详见 [许可证政策](LICENSE_POLICY.md)。
