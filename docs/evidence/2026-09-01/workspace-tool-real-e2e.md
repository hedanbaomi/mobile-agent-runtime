<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 工作区工具真实 SAF / Shizuku E2E 复核

日期：2026-09-01（Asia/Taipei）。工作区：`E:\mobileAgentRuntime`。源码状态为 `main@71b0972520dc1821cbe1208290f1bdd784e964e2` 上的未提交 dirty tree；本文不是正式 release、生产部署或 clean-source 证明。

## 1. 输入与结论边界

用户提供的 `mobile-agent-diagnostics.zip` 只作为不可信、只读现场证据处理，SHA-256 为 `581761ABB1B9F50D2F926955B9E398F03F861B626534E8D22BDD25ABED2EDCF5`。诊断显示 canonical grant 与 snapshot binding 已存在，但 Provider 可见工具数仍可为零；本轮因此用真实 Android 系统授权和真实 Shizuku UserService 重建整条链路，而不是只根据日志推断。

结论覆盖 API 31 x86_64 模拟器上的系统 DocumentsUI、官方 Shizuku 13.6.0、UID 2000 UserService、AgentRuntime 模型工具循环及 Debug/Review 本地产物。它不外推到物理 USB Companion、物理断连恢复、OEM 文件 provider 或非模拟器设备；这些仍为 `E2E_BLOCKED`。没有调用付费 Provider，没有读取用户文件正文以外的授权测试 fixture，没有 commit/push、Cloudflare 部署、正式签名或正式 Android release。

## 2. 根因与修复

1. `workspace_list` 审计没有单一 workspace 标识，空字符串却被传入禁止空值的 hash helper，成功派发被审计 fail-closed 改写为 `AUDIT_UNAVAILABLE`。现在仅把该枚举事件的空值规范化为“无 workspace hash”；具体工作区操作仍保留 HMAC/摘要审计。
2. SAF 配额扫描将 tree URI 直接当 document URI，部分 provider 返回 `IO_ERROR`；写入后 provider 返回的 document handle 也未重新绑定到持久 tree。现在从 `buildDocumentUriUsingTree` 得到的根 document 遍历，并将同 provider 的 mutation result 按 document ID 重建为 tree-bound URI；外部 authority、query/fragment 或不可解析句柄 fail-closed。
3. SAF 无法证明原子覆盖既有文件，因此只在 grant/provider 明确支持创建时暴露新建文本能力；既有文件 replace 继续返回 `UNSUPPORTED`，不会把非原子写伪装成安全替换。
4. Shizuku 的统一 `file_list` 允许省略路径表示根目录，Binder 边界只接受显式空字符串；适配器现仅在 root list 边界将 `null` 规范化为空路径，其他操作仍拒绝空、绝对和越界路径。
5. 首次 Shizuku 绑定可能同时由权限结果、Binder 回调和容器刷新触发，重复启动 UserService 后状态停留在 `CONNECTING`。bridge 现以进程内锁和 10 秒 in-flight 窗口保留单一 bind，并在 pending/断连/close 时清理；显式用户意图、已配置、已授权且 selected 的前台刷新会绑定服务，但不会隐式请求权限或切换 Authority。
6. Agent-facing schema 曾暴露后端不消费的 `expectedVersion`/`replace` 参数；Wired bridge 对带 `expectedVersion` 的变更也可能静默忽略。统一 schema 现只声明实际支持的参数；Wired write/create/move/delete 在非空 `expectedVersion` 时零 dispatch 返回 `CONFLICT`。
7. `runtime_tool_exposure` 增加闭合的安全聚合字段，可区分注册、授权、snapshot binding、交集工作区、selected Authority readiness、SAF grant/backend 和模型 tools transport；不记录 workspace ID、URI、路径、命令、参数、文件正文或异常消息。

## 3. 真实 Android 链路

设备为 AVD `mar_api31_matrix`、`emulator-5554`、API 31 x86_64。测试目录为系统 DocumentsUI 选择的 `Download/mar-workspace`；`seed.txt` 内容为受控 fixture `workspace-e2e-seed`。

Shizuku 使用包 `moe.shizuku.privileged.api`、版本 13.6.0.r1086.2650830c，由 ADB 启动服务。服务端 UID 经真实 handshake 证明为 2000；测试前清除旧 instrumentation UserService，未运行任何预热测试。

首次绑定硬证据：

```text
RuntimeShizukuToolExposureDeviceTest (FIRST, requireShizuku=true): OK (1 test)
首次测试后 runtime.mobileagent:shizuku-file-service 数量: 1
ShizukuLiveDeviceTest (requireShizuku=true): OK (2 tests)
```

随后在真实应用 Settings 中打开系统 `ACTION_OPEN_DOCUMENT_TREE`，选择 `Download/mar-workspace`，点击“使用此文件夹”和系统确认。返回应用后状态显示 `Active / Read Granted / Write Granted`。SAF 设备测试为：

```text
RuntimeSafToolExposureDeviceTest: OK (2 tests)
```

这两个测试覆盖实际模型可见的 provider-neutral tool specs、AgentRuntime tool call → tool result → 模型完成、workspace list、读 seed、创建并读取新文件、拒绝既有文件非原子覆盖且原文不变、删除测试文件。测试没有使用 fake DocumentsProvider 或绕过 persisted URI permission。

完整仪器批次：

```powershell
adb shell am instrument -w -r -e requireShizuku true runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner
```

结果为 `OK (235 tests)`，耗时 80.161 秒。K06 大负载用例仍因未提供显式 `-e knowledgeLoad true` 而受控跳过；235/235 不能描述为完成 300—500 文件、300—500 MB 的 K06 验收。仪器测试创建的额外 UserService 在测试进程结束后按精确 PID 清理；冷启动 Review 应用后生产态只保留一个 Shizuku UserService。

## 4. 构建、许可与供应链

```powershell
.\gradlew.bat :app-android:compileDebugKotlin :app-android:compileDebugAndroidTestKotlin :app-android:assembleDebug :app-android:assembleDebugAndroidTest --dependency-verification=strict --no-daemon --no-build-cache --console=plain
.\gradlew.bat licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification --dependency-verification=strict --no-daemon --no-build-cache --console=plain
python -m reuse lint
.\gradlew.bat check :app-android:assembleDebug :app-android:generateDebugSbom :app-android:reviewGate --dependency-verification=strict --no-daemon --no-build-cache --console=plain
```

结果：编译/Debug APK/AndroidTest APK 构建 `BUILD SUCCESSFUL`（375 tasks）；许可、Actions pin、28 个 lockfile、root 与 included-build strict verification `BUILD SUCCESSFUL`；REUSE 516/516 copyright 与 license、0 缺失/无效；最终全仓门禁 `BUILD SUCCESSFUL`（1084 tasks，75 executed / 1009 up-to-date）。Debug/Review SBOM 均为 171 components，Review provenance 通过。没有降低 AGPL、REUSE、dependency verification、SBOM 或 release gate。

## 5. 最终本地产物

| 产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| `app-android/build/outputs/apk/debug/app-android-debug.apk` | 212,754,194 | `ED6571CC4AE98101D6F049EC57FF9EE3EA6BA8F030CDFE2FD41C64A11E96FAD4` |
| `app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk` | 1,720,014 | `104371A4761E0EEA0FFFC9AB0CC14CF8719FD6CEFDC74F26E6B147022D7D69E4` |
| `app-android/build/outputs/apk/review/app-android-review.apk` | 204,289,319 | `E8289EE1DB02ADBF1C3F9C2AD8BF7B97FC07E676140EF5347AA2395C9F2AB477` |
| Debug SBOM | 158,231 | `AF6A7AAC5AD809CC4F4BA8B70F82216C046D66AE3EB52814A4386AFB96DE86B3` |
| Review SBOM | 158,245 | `F53A1F08B6BA50E50B3F9C9D782CDEC0FE55034F112960B7DBC3B7D54D9835F9` |
| Review provenance | 880 | `13F9C8D5DC8A90A09A2413D812D10078D74739C77DEF148C56FCF50A181FC299` |

Review APK 已覆盖安装并冷启动；package flags 不含 `DEBUGGABLE`，`run-as runtime.mobileagent` 被系统拒绝。同一 Review 包的 UI 已实际切换普通模式与 `ENABLED_AUTONOMOUS`，危险模式启用后显示 Shell available，满足“一个包同时检查一般模式和危险模式”的人工验收需求。

Debug AndroidTest APK 不能与 Review target 混用：尝试该不兼容组合会因 build-variant 专用测试 seam 触发 `NoSuchMethodError`。这不是产品崩溃，也不构成 Review instrumentation 通过；真实 SAF/Shizuku E2E 在匹配的 Debug target/test APK 上完成，Review 仅完成 non-debuggable security gate、安装冷启动与真实 UI 模式切换。

## 6. 剩余边界

- 物理 USB Wired ADB Companion、物理断连恢复、OEM DocumentsProvider 和非模拟器设备差异仍需后续设备条件。
- K06 300—500 文件/300—500 MB、Android 15/16 长时配额、ENOSPC/温控/耗电仍未完成。
- 当前产物使用本地 Android Debug v2 签名，仅供人工终审；不是正式签名 release。
- 本轮没有 commit/push、生产部署、正式发布或付费调用。
