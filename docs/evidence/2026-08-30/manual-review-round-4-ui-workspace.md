<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 第四轮人工反馈：确认卡、预算、默认主题与 Agent 工作区

日期：2026-08-30，Asia/Taipei。基线为 `main@0c24f0a95c0644d3aedc992bae037c2fcccadc02` 加当前未提交产品工作区；没有 reset、clean、stash、commit、push、正式 release、Cloudflare 部署或付费调用。

## 输入与修复

- 长工具确认内容原先会把批准/拒绝按钮推到可视区外。确认摘要现位于最高 200 dp 的垂直滚动区，工具名最多两行；安全警告和两个操作按钮固定在滚动区下方。
- Provider 上下文预算和输出预算原先在每次键入时立即回退为整数默认值，导致最后一位无法删除。编辑态改为字符串，允许空值；保存时再验证正整数以及输出不超过上下文，非法值显示错误并禁用保存。
- 首次启动、缺失值和非法主题值现在回退浅色。用户显式选择的 `system`、浅色、深色、`66ccff` 分别保留；`66ccff` 不再作为默认主题。
- 增加 `workspace_list`、`workspace_read`、`workspace_write`、`workspace_create_directory`。所有操作逐次批准并在批准时复核 Agent/冻结快照；工作区为应用私有 SHA-256 命名空间，模型只看到相对路径。拒绝绝对路径、遍历、symlink、canonical 越界和超限输入；UTF-8 替换写使用同目录临时文件、刷新和原子移动。
- 未实现或宣称 SAF 目录、Termux、无线 ADB、Device Owner/Profile Owner、root/Shizuku、PowerShell、通用 shell、删除或任意宿主文件系统。权限域和后续阶段见 [ADR-0004](../../adr/0004-capability-authority-bridge.md)。

## 已执行的定向验证

- API 31 x86_64 `ChatApprovalCardUiTest`：2/2，`BUILD SUCCESSFUL`。覆盖长中文摘要滚动后按钮仍可达，以及短英文安全文案。
- API 31 x86_64 `ProviderBudgetValidationTest`：3/3，`BUILD SUCCESSFUL`。覆盖完整清空、非法值/上下限及有效值。
- API 31 x86_64 `WorkspaceAppToolsTest`：6/6，`BUILD SUCCESSFUL`。覆盖路径反例、逐次批准和 call ID 防重放、UTF-8 原子替换、配额/symlink、撤销 Agent/快照、读取/列目录批准。
- `:shared:domain:test :data:sqlite:test`：`BUILD SUCCESSFUL`。覆盖空数据库默认浅色和四种显式主题选择保持。

## 全仓门禁与制品

- `gradlew.bat :shared:domain:test :data:sqlite:test licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification --dependency-verification=strict --no-daemon`：`BUILD SUCCESSFUL`；2 个 workflow pin、24 个 lockfile、261,631-byte verification metadata 均通过。
- `python -B -m reuse lint`：399/399，0 error。
- `gradlew.bat check :app-android:assembleDebug :app-android:generateDebugSbom --dependency-verification=strict --no-daemon`：`BUILD SUCCESSFUL`，985 tasks；CycloneDX 1.6 debug SBOM 为 166 components。
- `gradlew.bat :app-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.ChatApprovalCardUiTest,runtime.mobileagent.ProviderBudgetValidationTest,runtime.mobileagent.WorkspaceAppToolsTest --dependency-verification=strict --no-daemon`：API 31 x86_64 合计 11/11，`BUILD SUCCESSFUL`。
- APK：`app-android/build/outputs/apk/debug/app-android-debug.apk`，211,259,450 bytes，SHA-256 `df66bb7f7b15f1350c62caab668a8cec1b9298ba1252b2d63f126703b7dedd78`。
- `apksigner verify --verbose --print-certs`：v2=true，单一 Android Debug RSA signer；证书 SHA-256 `315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788`。
- `aapt dump badging`：`runtime.mobileagent`、versionName `0.1.0`、minSdk 26、targetSdk 35、debuggable、arm64-v8a+x86_64；权限没有增加广泛存储、ADB、Device Admin、root 或 shell 能力。
- `adb install -r -t`：`Success`；`am start -W`：`Status: ok`、`LaunchState: COLD`、`runtime.mobileagent/.MainActivity`。
- `git diff --check`：无 whitespace error；Windows checkout 仅报告既有 LF→CRLF 提示。

## 剩余边界

本轮设备测试是 API 31 x86_64 模拟器，不等价于用户真实设备与大字体/不同屏幕尺寸人工终审。应用私有工作区不会让 imported Skill 直接获得 Android 文件系统；Skill 输出需要由模型另行调用并批准工作区工具。外部 Termux/ADB/DPC/SAF 能力必须各自设计、授权与验收，不能由 S13 推定通过。
