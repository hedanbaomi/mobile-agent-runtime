<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Thread workspace 不可变、Provider 连接集成与全局 UI 收口

日期：2026-09-03（Asia/Taipei）。仓库：`E:\mobileAgentRuntime`。源码/测试已提交并快进推送为
`17a695eb20e16775ae834e1ecb5db2677dfedf3a`。作者/提交者均为 `luozhibai <wy3273564266@163.com>`，
无其他贡献者 trailer。`HANDOFF.md` 与 `/docs` 只做本地提交，不推送。本记录不是正式
release 或生产部署；本轮没有部署或付费 Provider 调用。

## 1. 产品结论

- 一个 Thread 首次绑定 workspace 后，该绑定视为 immutable。再选择另一个 workspace 不改写
  `ConversationWorkspaceBinding` 或 snapshot。UI 提示“工作区属于当前会话上下文，切换将创建新会话。”
  确认后用同一 Agent 与目标 workspace 创建新 Thread。未绑定 Thread 允许首次 bind。
- Provider Test Connection 与 Capability Probe 共用 `ProvidersViewModel.adapterFor()`；生产路径
  仍是 `OpenAiCompatibleAdapter`。测试通过 `ProviderAdapterFactory` 注入 scripted adapter，真实调用
  `testConnection()`，不再只构造 renderer state。
- compact 全局菜单是 48dp Menu `IconButton`，TalkBack 文案完整，不再显示文字「菜单」。wide layout
  不显示 opener。
- Insets：`IMPLEMENTED / AUTOMATED TESTED / REAL CUTOUT VERIFY REQUIRED`。
- `WorkspaceUiPresentationStore` 不是授权/恢复 source of truth；丢失后从 canonical Workspace 生成
  安全 fallback。`android:allowBackup=false` 未改。
- 普通模式不暴露 arbitrary shell；用户显式开启 Dangerous Mode 且具备 `shell.execute` capability
  后，仍允许真正的 Android shell。Root/Wireless ADB 仍不实现。

## 2. 验证

```powershell
.\gradlew.bat :shared:domain:test --tests runtime.mobileagent.domain.WorkspaceIntentTest --offline --console=plain
.\gradlew.bat :data:sqlite:test --tests runtime.mobileagent.data.WorkspaceBindingRepositoryTest --offline --console=plain
.\gradlew.bat :shared:provider-api:test --offline --console=plain
.\gradlew.bat :app-android:compileDebugKotlin :app-android:compileDebugAndroidTestKotlin --offline --console=plain
.\gradlew.bat :app-android:connectedDebugAndroidTest --offline "-Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.integration.RuntimeThreadWorkspaceDeviceTest,runtime.mobileagent.integration.WorkspacePickerContractTest,runtime.mobileagent.workspace.CanonicalWorkspaceCoordinatorTest,runtime.mobileagent.workspace.WorkspaceUiPresentationTest,runtime.mobileagent.GlobalConversationUiTest,runtime.mobileagent.ProvidersTypedUiTest,runtime.mobileagent.ProvidersViewModelConnectionDeviceTest,runtime.mobileagent.security.PrivilegedWorkspaceBindingCipherTest" --console=plain
```

结果：domain `WorkspaceIntentTest` 11 tests PASS；SQLite binding 测试 PASS；provider-api typed
connection 回归 PASS；Kotlin compile BUILD SUCCESSFUL；focused `connectedDebugAndroidTest`
45/45 PASS on `mar_api36_debug`。全仓 strict gate、REUSE、物理 Wired USB 与真机挖孔未跑。

`codegraph sync .`：Synced 17 changed files（Added 1, Modified 16）。

## 3. 未完成的设备边界

- 真机 punch-hole / 挖孔屏：`REAL CUTOUT VERIFY REQUIRED`。
- 物理 Wired USB Companion 仍为 `E2E_BLOCKED`。
- 本轮没有独立安全复核、全仓 strict gate 或正式发布授权。
