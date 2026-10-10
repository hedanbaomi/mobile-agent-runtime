<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 贡献指南

欢迎贡献代码、测试、文档和问题报告。mobileAgentRuntime 的首阶段产品面向 Android，共享模块承载领域逻辑与协议；各模块的位置见 [README 的代码结构](README.md#代码结构)。

## 报告问题与讨论改动

请先搜索 [已有 Issues](https://github.com/hedanbaomi/mobile-agent-runtime/issues)，避免重复报告。问题报告应包含应用版本与构建号、Android 版本、复现步骤、预期行为和实际结果；必要时附已检查并脱敏的诊断信息。不要公开 API Key、认证头、Cookie、用户文件、对话原文或设备标识。

较大的功能、协议、安全边界或数据库改动，建议先在 Issue 中说明方案和影响范围。小修复、文案和测试改进可以直接提交 Pull Request。涉及未公开漏洞时，先与维护者商定私密报告方式，公开 Issue 不要附利用细节或敏感数据。

## 准备开发环境

1. Fork 仓库并克隆自己的副本，或在有写权限的仓库中创建功能分支。每个 Pull Request 尽量只解决一个明确问题。
2. 安装 JDK 17 和 Android SDK 35，使用仓库内的 Gradle Wrapper。SDK 路径通过 Android Studio 或本机 `local.properties` 设置；不要提交机器路径。
3. 原生库、Python 运行时和模型包使用仓库锁定的版本、来源和校验值。首次构建需要下载依赖；NDK、CMake 等组件版本以构建配置为准。
4. 安装 Python 3.11+ 和用于许可证检查的 REUSE。CI 当前使用 `reuse==6.2.0`；公告服务开发使用 Node.js 24，最低为 22.13.0（测试使用内置 `node:sqlite`，具体要求以该服务的 package.json 为准）。

Windows PowerShell：

```powershell
.\gradlew.bat :app-android:assembleDebug --dependency-verification=strict
adb install -r .\app-android\build\outputs\apk\debug\app-android-debug.apk
```

macOS / Linux：

```sh
./gradlew :app-android:assembleDebug --dependency-verification=strict
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
```

使用模拟器时准备支持 `x86_64` 的系统镜像。Debug 用于普通功能调试；验证危险控制能力时使用 `:app-android:assembleReview` 生成的不可调试 Review 包，详见 README 的构建说明。Debug/Review 与正式 Release 的签名不同，安装前保留已有数据；正式构建与发布由维护者按 [发布说明](docs/RELEASING.md) 处理，提交普通改动无需正式签名私钥。

## 了解相关契约

按改动范围阅读 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)、[需求](docs/REQUIREMENTS.md) 和 [验收项](docs/ACCEPTANCE.md)。知识导入、Skill 权限、Provider 和工作区等模块有对应专题与 ADR；只需继续阅读受影响的契约及其上下游。文档索引见 [领域文档说明](docs/agents/domain.md)。

修改接口、默认值、授权规则或迁移时，同步相关文档与验收项；重要取舍记录为 ADR。`HANDOFF.md` 是维护者的工作状态记录，贡献者无需把本机环境或开发过程写进交接。使用自动化开发工具时，另遵守仓库的 `AGENTS.md` 与 `agent.md`。

## 实现与验证

保持现有模块边界，优先使用已有接口和测试。权限由 Runtime/Broker 确定，模型输出、Skill 与外部工具内容不能成为权限来源。模型调用使用用户配置；自动化测试使用合成数据和伪 Provider，实际服务验证使用经授权的测试环境。

先执行覆盖改动风险的单元或设备测试，再运行提交门禁。以下为 PowerShell 示例；macOS/Linux 将 `.\gradlew.bat` 换成 `./gradlew`。

```powershell
.\gradlew.bat licenseGuard licenseGuardReverse check verifyCiPins verifyDependencyLock verifyDependencyVerification verifyWorkflowYaml --dependency-verification=strict
python -m reuse lint
```

Android 界面或平台集成改动还应编译测试包，并在独立测试设备或模拟器上运行相关 instrumentation 测试：

```powershell
.\gradlew.bat :app-android:assembleDebugAndroidTest --dependency-verification=strict
.\gradlew.bat :app-android:connectedDebugAndroidTest --dependency-verification=strict
```

设备测试可能修改应用数据，请使用专门的测试安装；不要对日常使用的数据运行清理操作。记录实际执行的命令、结果和测试环境，分别说明单元测试、模拟器、真机和真实服务的覆盖。无法执行的检查请在 Pull Request 中写明原因，不能把未执行的验收写为通过。

公告服务的本地测试在 `services/announcements/` 中按 `package.json` 的 scripts 运行；不要使用生产数据库或部署凭据做普通回归。安全边界、协议或迁移改动需要独立审查。

## 提交 Pull Request

- 使用自己的真实 Git 作者身份或自己的 GitHub noreply 地址，不要冒用仓库所有者。自动化工具名称不作为人类贡献者署名，也不添加虚构的共同作者。
- 提交说明写清具体问题和修改后的行为；Pull Request 列出验证结果、受影响契约，以及仍需复测的边界。
- 只提交与本次改动有关的文件。不要提交密钥、签名材料、用户数据、本机配置、构建产物或 `.codegraph/` 索引。
- 遵守检查和审查要求，通过普通 Pull Request 合并；不要跳过 hooks、削弱检查或绕过分支保护。

维护者可能要求补充回归、缩小范围或修改方案。收到反馈后更新同一 Pull Request，并保持测试和文档与最终差异一致。

## 许可证与归属

第一方源代码、构建脚本、服务端、管理端、测试和文档统一使用 **AGPL-3.0-only**。贡献代码时保留现有许可声明，为新增第一方文件添加 SPDX 信息；提交的贡献按本项目许可分发。完整政策见 [LICENSE_POLICY.md](LICENSE_POLICY.md)。

现有集体版权标记为 `mobileAgentRuntime contributors`，使用这一标记不转移贡献者的著作权。第三方依赖、vendored 代码、模型包、外部 Skills 和用户资料保留各自许可；引入新依赖时核对许可与分发要求，不要将其原始声明改写成项目许可。
