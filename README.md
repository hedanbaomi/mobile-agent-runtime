<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# mobileAgentRuntime

mobileAgentRuntime 是一款运行在 Android 上的本地 Agent 应用。用户自行配置模型服务，应用在设备端管理知识库、Skills、会话和工具权限。

## 功能

- 配置 OpenAI-compatible Provider、模型角色和生成预算。
- 创建 Agent，组合 Prompt、模型、知识库和 Skills。
- 导入文件、文件夹或 ZIP，在设备上建立知识索引。
- 运行本地 Python Skills，并按调用授予所需能力。
- 使用联网搜索、MCP 和脱敏请求检查器。
- 在应用工作区或用户授权的文件夹中创建和修改文件。
- 通过 Shizuku 或 Windows 有线 ADB Companion 调用高权限工具。
- 查看独立于模型服务的签名公告。

## 使用

应用支持 Android 8.0（API 26）及以上版本。安装后先在“更多 → 服务商”中添加 Provider、API Key 和模型，再到“智能体”创建 Agent。知识库和 Skills 可以随后绑定，也可以先直接开始普通对话。

知识文件从“知识”页导入。文本会在设备上建立索引，含图片的资料需要配置视觉模型。Skill 从“技能”页导入并启用，Agent 只有在获得对应授权后才会调用它。

每个新会话会保存一份 Agent 配置快照。修改 Agent 后，新配置从下一个会话开始生效。

设置页可以开启诊断记录。复现问题后导出诊断 ZIP，即可查看运行阶段和错误码。

## 工具与文件权限

普通文件操作使用应用私有工作区或 SAF 授权目录。Agent 的可用工具由会话快照、工作区范围和授权共同决定。

Shizuku 和 Wired ADB 是可选的高权限通道。选择通道并开启 Dangerous Mode 后，Agent 才能申请 `shell_exec`；执行前会显示命令、工作目录和权限来源，高风险调用需要确认。

## 构建

构建需要 JDK 17 和 Android SDK 35。

```powershell
.\gradlew.bat :app-android:assembleDebug --dependency-verification=strict
adb install -r app-android\build\outputs\apk\debug\app-android-debug.apk
```

### 提交前检查

```powershell
.\gradlew.bat licenseGuard licenseGuardReverse check `
  verifyCiPins verifyDependencyLock verifyDependencyVerification `
  --dependency-verification=strict
python -m reuse lint
```

## 代码结构

| 目录 | 内容 |
| --- | --- |
| `app-android/` | Android 应用、集成层、诊断与设备测试 |
| `feature/` | 对话、Agent、知识、Skills、设置等 Compose UI |
| `shared/` | 领域模型、Provider、Agent runtime、工具与桥接协议 |
| `data/sqlite/` | 本地持久化、迁移、权限与审计仓库 |
| `desktop/bridge/` | Windows 有线 USB ADB Companion |
| `services/announcements/` | 独立公告 Worker 与管理端 |

## 许可证

第一方代码和文档采用 **AGPL-3.0-only**。第三方依赖、导入的知识库和外部 Skills 保留各自的许可与权利归属，具体内容见 [LICENSE_POLICY.md](LICENSE_POLICY.md)。
