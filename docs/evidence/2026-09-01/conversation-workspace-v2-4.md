<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Agent 侧边栏与持久工作区访问 v2.4

日期：2026-09-01（Asia/Taipei）。仓库：`E:\mobileAgentRuntime`。基线为
`main@3bab147af26af65c5584ca42245420681c79c5cc` 上的未提交工作树。本记录是本地实现和验证证据，
不是正式 release、生产部署或 clean-source 证明；本轮没有 commit、push、Cloudflare 部署、正式签名或付费调用。

## 1. 产品与权限结论

- 对话与历史会话收敛为稳定的 Agent → Session 侧边栏；新会话先选择 Agent，同一 Agent 的所有 Session 共用其当前工作区。
- 工作区在 Agent 设置中配置。SAF 通过系统目录选择器快速授权；已选 ADB Authority 可浏览并绑定用户明确选择的目录；完整设备文件是独立、可撤销的高风险授权。
- Shizuku 与 Wired ADB 在展示层归为 ADB 级访问，但仍是两个独立 Authority，只派发用户当前选择的通道，不自动回退或切换。
- ADB 级授权的持久状态和实时连接严格分离。用户意图、selected Authority、configured、平台 grant、Wired trust/secret、Agent 工作区、完整设备文件和危险模式不会因 Binder、USB、Wi-Fi 或桌面 Companion 暂时断开而清除。断联只把 availability/connection 改为临时不可用/已断开。
- 已持久授权的工具在断联时仍保留在模型工具表；调用阶段重新验证实时连接，并返回 `AUTHORITY_TEMPORARILY_UNAVAILABLE`，零 dispatch、零 fallback。只有用户显式撤销、平台明确 DENIED/REVOKED，或身份/协议/secret 绑定明确失效才撤销持久授权。
- “完整设备文件”只代表所选 ADB Authority 的 shell UID/SELinux 可见范围，不是 Root，也不会自动授予 `shell.execute`。
- 已存在的 canonical Agent grant 不在 Chat 逐次重复确认；Run 启动时冻结 grant/revision/scope/policy，后续设置在下一次 Run 生效。

## 2. 本轮根因与修复

1. SAF grant、backend、Agent grant 和 snapshot binding 均可存在，但旧 schema 暴露逻辑把 provider 的实时 READY 当作持久授权条件，导致断联或初始化竞态时模型工具表为空。现在 schema 依据持久配置和 grant，派发才检查实时 READY。
2. 用户可能在 Shizuku Manager 中授予平台权限，同时已在应用中明确选择 Shizuku；旧容器只在应用内权限按钮回调时写 `configured=true`。现在只有“持久用户意图为 Shizuku + live grant 明确为 GRANTED”的组合才补齐配置；平台 grant 单独存在不会隐式选择或启用 Authority。
3. Agent 页面旧聚合曾以 `WorkspaceAccessStatus.ACTIVE` 判断授权是否存在，断联后会隐藏持久工作区、完整设备文件状态及撤销入口。现在 DTO 明确区分 `durablyAuthorized` 与实时可读写状态；离线显示“授权已保留，连接恢复后继续生效”，仍可撤销。
4. Wired 文件后端残留 `Path.of`，Android lint 判定需 API 34。改为 API 26 可用的 `Paths.get`，未提高 minSdk 或加入 lint baseline。

## 3. 自动化与真实设备证据

设备：`emulator-5554`，API 31 x86_64。Shizuku Manager 13.6.0 正常运行，真实 UserService UID 为 2000。

```text
RuntimeShizukuToolExposureDeviceTest (requireShizuku=true): OK (1 test)
ToolingOrchestrationTest: OK (41 tests)
侧边栏/设计系统/Agent 离线授权/SAF/Shizuku/诊断/Wired 聚合批次: OK (95 tests)
```

95 项批次包含：`AgentsGrantUiTest`、`ConversationSidebarUiTest`、`DesignSystemUiTest`、
`EffectiveCapabilityResolverHotUpdateTest`、`DiagnosticsDeviceTest`、`RuntimeSafToolExposureDeviceTest`、
`RuntimeShizukuToolExposureDeviceTest`、`ShizukuLiveDeviceTest`、`ToolingOrchestrationTest` 和
`WiredAdbAuthorityBridgeTest`。

关键正反向覆盖：

- Shizuku/Wired 授权在断联和 `AuthorityManager` 重建后仍保留 selection、intent、configured 与 GRANTED；availability/connection 只变为临时不可用/断开。
- 已授权的 Shizuku 工作区离线时仍暴露 `workspace_list`，调用返回 `AUTHORITY_TEMPORARILY_UNAVAILABLE` 且 backend dispatch 次数为 0。
- Shizuku 真实链路中，模型工具表包含 list/read/write，实际 list/create/read/delete 成功且不产生第二次对话批准。
- Agent UI 在 Authority 离线时仍显示完整设备授权和撤销入口；safe mapper 不暴露 URI、绝对路径、serial、token 或 secret。
- 连接恢复前不切换到另一 Authority；恢复后使用原持久授权重新派发。

## 4. 构建、许可与供应链

```powershell
.\gradlew.bat :app-android:assembleDebug :app-android:assembleDebugAndroidTest --dependency-verification=strict --no-daemon --no-build-cache --console=plain
.\gradlew.bat licenseGuard licenseGuardReverse --no-daemon --no-build-cache --console=plain
python -m reuse lint
.\gradlew.bat verifyCiPins verifyDependencyLock verifyDependencyVerification --dependency-verification=strict --no-daemon --no-build-cache --console=plain
.\gradlew.bat :app-android:lintDebug --dependency-verification=strict --no-daemon --no-build-cache --console=plain
.\gradlew.bat check :app-android:assembleDebug :app-android:generateDebugSbom --dependency-verification=strict --no-daemon --no-build-cache --console=plain
```

结果：双 APK 构建成功；license 正反向通过；REUSE 534/534、0 缺失/无效；Actions pin、28 个 lockfile、
root/included-build strict dependency verification 通过；lint 在修复 API 26 兼容问题后通过；最终全仓门禁
`BUILD SUCCESSFUL`（1021 tasks，60 executed / 961 up-to-date）；Debug SBOM 为 171 components。
没有降低 AGPL、REUSE、dependency verification、SBOM 或测试门禁。

## 5. 当前本地产物

| 产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| `app-android/build/outputs/apk/debug/app-android-debug.apk` | 213,705,503 | `6C8585A6EBD979246E341A23680F7EBB654E4FB65155E41E91AF96D8C8C87857` |
| `app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk` | 1,742,266 | `87A6F08AB0FF5B38B3B3C312FDE31734059798C0DAEC128E9B6CE06E0885E48F` |
| `app-android/build/reports/sbom/debug.cdx.json` | 158,231 | `D6224EC954C2FD7C6BB8E6F568D2A13B9B4E42917CBC882C5CB911D9546CF578` |

这些 APK 使用 Android Debug 签名，只供本地自动化和后续人工检查，不是正式 release。

## 6. 未完成的设备边界

- 真实 Windows Companion + 物理 USB、物理 Wi-Fi/USB 断连和重连仍需物理设备；模拟器状态机测试不能冒充该 E2E。
- OEM DocumentsProvider、非模拟器 Binder death/rebind、系统权限手工撤销差异仍需后续真机覆盖。
- 本轮没有执行 K06 300—500 文件/300—500 MB 长时负载，也没有正式发布授权。

## 7. 独立复核

独立只读复核最初发现一项 P1：Agent 页面把实时 `WorkspaceAccessStatus.ACTIVE` 当作授权是否存在的依据，导致 Authority 离线后隐藏已持久授权的工作区、完整设备文件和撤销入口。修复后 `WorkspaceAccessItem` 使用独立的 `durablyAuthorized` 维度；选择逻辑、状态文案和撤销入口均不再依赖实时连接，并由离线 UI 设备测试覆盖。

修复后的主线静态/协议复核重新检查了 `AuthorityManager` 的持久与实时状态拆分、`RuntimeIntegration` 的 Shizuku/Wired 状态映射、Wired `disconnect` 与显式 `forget` 的不同语义、工具 schema/dispatch 双重门禁、Agent 工作区安全 DTO、Dangerous Mode 生命周期和离线撤销路径。结论为未发现新的 P0/P1/P2；没有自动切换 Authority、没有把平台 grant 单独当成用户意图、没有把暂时断联当成撤权，也没有把 URI、绝对路径、serial、token 或 secret 暴露给 UI/模型/诊断。物理 USB Companion 与 OEM/真机差异仍按上一节保留为 `E2E_BLOCKED`。
