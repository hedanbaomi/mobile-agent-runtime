<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 权限、工具与诊断 v2 历史本地证据（已由 2026-09-01 闭环取代）

日期：2026-08-31（Asia/Taipei）。工作区：`E:\mobileAgentRuntime`。

> 历史快照说明：本文保留 2026-08-31 当时“真实 SAF/Shizuku 环境缺失”的事实。2026-09-01 已在 API 31 x86_64 模拟器完成系统 DocumentsUI 持久 SAF grant、官方 Shizuku UID 2000 UserService 和模型工具真实 E2E，见 [后续闭环证据](../2026-09-01/workspace-tool-real-e2e.md)。物理 USB Companion、物理断连恢复和非模拟器设备差异仍未验证。

## 1. 范围与结论边界

本轮实现 [权限、工具与危险模式 v2 规范](../../mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)：统一 Capability、Workspace、Authority、Approval 与 Audit；应用私有和 SAF workspace；Skill Memory；Shizuku；Windows 有线 USB ADB Desktop Companion；持久 Dangerous Mode；受控 `shell_exec`；以及相应设置、Agent、Chat、Skills 与诊断 UI。

明确排除 Root、应用内无线 ADB、DPC、Termux、PTY、通用工具链安装器、Accessibility、宿主 PowerShell/宿主 shell。形成本文证据时没有正式 release、Cloudflare 部署、付费服务、secret 变更、commit 或 push；2026-08-31 用户随后单独授权将这批 v2 源码、测试和文档 commit/push，该授权不改变本文的 dirty-source 产物事实，也不授权生产部署或正式 release。

源码基于 `main@0c24f0a95c0644d3aedc992bae037c2fcccadc02` 的 dirty WIP；provenance 同时记录 `gitDirty=true` 与 source archive SHA-256，不能把它描述为 clean release。

## 2. 实现摘要

- Authority：仅 `SHIZUKU` 与 `WIRED_ADB`，选择、用户意图、平台 grant、可用性和连接状态分离；选中通道不可用时 fail-closed，不自动 fallback。
- Workspace：Internal、SAF 和选中 privileged backend 统一使用 provider-neutral typed tools；路径、symlink、版本、配额、原子替换/未知结果按 backend 能力处理，URI、root 和设备路径不进入模型。
- Approval：调用绑定 Agent、snapshot、capability、Authority、Dangerous Mode 与参数摘要；批准时重新校验；跨进程等待批准失效并终结旧 run/invocation，不自动重放。
- Skill Memory：使用 canonical SQLite repository、snapshot/grant/Skill 身份交集及 frozen capability；状态和诊断不暴露包 hash、路径或正文。
- Shizuku：严格 UID/握手/session、PFD 有界输出、typed workspace 与 one-shot `/system/bin/sh`；Binder death、超时和取消按 `UNKNOWN_OUTCOME` 保守处理。
- Wired ADB：官方 adb 的有线 USB Companion、显式 serial、loopback authenticated protocol、Android Keystore 绑定 secret、配对 token 时限/次数/单次消费、会话序号与重放防护；不支持无线 ADB/LAN。
- UI：审批详情在小屏/IME 下有界滚动且批准/拒绝固定可达；Provider 预算可完整清空后重输；首次/缺失/非法主题默认为浅色，`66ccff` 只保留为显式彩蛋；Settings、Agent grants、Skills Memory 与 Inspector 使用稳定状态。
- Diagnostics：默认关闭；闭合事件/字段白名单；引用使用会话 HMAC；命令只记录摘要；不记录正文、路径、URI、serial、token、stdout/stderr 或异常 message；上限为当前/上一段/崩溃/单事件/ZIP `256/256/32/4/640 KiB`。

## 3. 严格构建、许可与供应链

以下命令均在当前 dirty tree 上执行，且保持 `--dependency-verification=strict`：

```powershell
.\gradlew.bat clean --dependency-verification=strict --no-daemon --no-build-cache --console=plain
python -B -m reuse lint
.\gradlew.bat licenseGuard licenseGuardReverse verifyCiPins verifyDependencyLock verifyDependencyVerification --dependency-verification=strict --no-daemon --no-build-cache --console=plain
.\gradlew.bat :shared:domain:test :shared:skills-api:test :shared:agent-runtime:test :shared:bridge-protocol:test :desktop:bridge:test :data:sqlite:test --rerun-tasks --dependency-verification=strict --no-daemon --no-build-cache --console=plain
.\gradlew.bat check :app-android:assembleDebug :app-android:generateDebugSbom :app-android:debugEvidenceGate --dependency-verification=strict --no-daemon --no-build-cache --console=plain
.\gradlew.bat :app-android:reviewGate --dependency-verification=strict --no-daemon --no-build-cache --console=plain
python -B tools/runtime-notices.py --check --artifact app-android/build/outputs/apk/debug/app-android-debug.apk --artifact app-android/build/outputs/apk/review/app-android-review.apk
```

结果：

- controlled clean：PASS。
- REUSE：514/514 copyright、514/514 license、0 缺失或无效，PASS。
- license 正/反向、GitHub Actions pin、28 个 dependency lockfile、root 与 included build verification metadata：PASS；未改为 lenient/off，未 wildcard 信任依赖。
- 共享/JVM 定向套件：37 tasks，PASS。
- 全仓 `check`、Debug APK、SBOM/provenance 与 Debug evidence gate：1024 tasks（81 executed、943 up-to-date），PASS。
- `lintDebug` 首次发现 `Path.of` 需要 API 34；改为 API 26 可用的 `File.toPath()` 后，focused lint 与完整门禁均 PASS。没有建立 lint baseline 或忽略错误。
- Review gate：592 tasks（29 executed、563 up-to-date），PASS；APK 为 `debuggable=false`，高权限控制面只在 review-like variant admission 下启用。
- runtime notices：148 Maven coordinates、4 native/model entries，Debug/Review 两个打包边界逐文件 hash 校验 PASS。校验器仅为固定根资产 `THIRD_PARTY_NOTICES.md` 增加收窄例外，组件路径仍限制在许可/model/python 前缀。
- 第一方许可与门禁没有降低：**NO**。

## 4. 2026-08-31 历史产物绑定（不可用于当前构建）

| 产物 | 大小 | SHA-256 | 说明 |
| --- | ---: | --- | --- |
| `app-android-debug.apk` | 212,309,041 bytes | `ee4640886e3135686ab6bc3a2d04e331291c875f98020cfa20ce861220073c44` | debuggable；Android Debug 证书；供人工测试 |
| `app-android-review.apk` | 204,272,935 bytes | `b8c96c49369054247d251f2646edc6c5b9804c1cf44f37edde07b9e0fec6cccc` | non-debuggable review-like；不是正式 release |
| Debug SBOM | — | `b6841c9b6f5ace3695e8433a80093ebb2ede5187a9dee1023897c9496fabe0e9` | CycloneDX 1.6，171 components |
| Review SBOM | — | `d53012aa833e0e6aaaeb3187beb487854128c1277b3c451339f8a79ac6871767` | CycloneDX 1.6，171 components |
| Debug provenance | — | `022e44b2124ee2ee87973ccba69732398f4323200f7a5ca0cfb6aa9b108c9535` | 绑定 APK、SBOM、HEAD、dirty 与 source archive |
| Review provenance | — | `3d065fb88ab6d36362194b8d26e5f0ab855443e1c276d3a43bc276d2449ee6aa` | 绑定 APK、SBOM、HEAD、dirty 与 source archive |

两份 provenance 的 source archive SHA-256 均为 `a24a51d00ee4418ffc08aca6a758f613e9a6fd488793767caf140ba634807a60`。两份 APK 均通过 APK Signature Scheme v2 验证；Debug 证书 SHA-256 为 `315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788`。Review 包使用本地 debug identity 只作非调试安全审查，不是正式签名身份。

## 5. 2026-08-31 历史 API 31 设备证据

设备：`mar_api31_matrix` / `emulator-5554`，Android API 31，x86_64，boot complete。

当前最终 Debug APK 已覆盖安装并启动 `runtime.mobileagent/.MainActivity`。首次启动截图确认浅色主题；`dumpsys package` 显示 `versionName=0.1.0`、`versionCode=1`、`minSdk=26`、`targetSdk=35` 与 Debuggable 标志。Compose/device 自动化覆盖预算字段完全清空再输入、审批卡小屏/大字体/受限高度、Agent grants、Settings authority、Skills Memory、Inspector 和 Release Gate UI。

统一执行形式：

```powershell
.\gradlew.bat :app-android:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=<classes>" --dependency-verification=strict --no-daemon --no-build-cache --console=plain
```

| 批次 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| Authority / Workspace / Memory | 91 | 0 | 0 | 0 |
| Tooling / Approval / Navigation / Search | 58 | 0 | 0 | 0 |
| UI / Release Gate | 27 | 0 | 0 | 0 |
| Diagnostics | 12 | 0 | 0 | 0 |
| Shizuku live | 2 | 0 | 0 | 2 |

四个可执行批次合计 **188/188 PASS**；Shizuku live 的两个用例因设备没有真实 Shizuku UserService 按 `assumeTrue` 跳过，不计 PASS。XML 保存在 `app-android/build/reports/final-device-matrix-after-review/<batch>/`。任何 fixture/模拟器 PASS 都不替代下节真实端 E2E。

## 6. 形成本文时仍为 E2E BLOCKED 的外部边界

- 真实 Shizuku App、用户授权、UserService、Binder death/rebind、真实 shell 与 typed workspace。
- Windows 真实 USB 设备、官方 adb、Companion 配对/reverse/encrypted session、物理断连与恢复。
- 真实 SAF provider 的 `OpenDocumentTree`、persisted URI grant、撤销和 provider 差异。
- Root、无线 ADB、DPC、Termux、PTY、Accessibility 和宿主 shell 是明确排除项，不是待测功能。
- 正式 release signing、AAB、应用商店发布、Cloudflare 或其他生产部署未执行也未获本目标授权。

## 7. 独立复核

最终三路独立只读复核分别覆盖核心权限安全、UI/生命周期/诊断、构建/许可/证据。首轮均给出 `NEEDS_AMEND`，并准确发现三个 P1：Companion 的 ADB 客户端诊断 stderr 可能进入模型、Inspector 关闭后旧预览仍可能显示、设备测试重写 Debug APK 后证据 hash 漂移。

三个问题均已修复并由原审查者复核关闭：Companion 在 bridge payload 前整段丢弃带 ADB 诊断标记的 stderr，注入 serial/宿主路径/platform-tools/异常文本的回归测试 11/11 通过；Chat 状态层与 UI 渲染层均以 `DISABLED` 为最高优先级并清空旧预览，API 31 Inspector 5/5 通过；最后一次设备矩阵后重新生成 Debug/Review APK、SBOM 与 provenance，三方 hash 一致。三路终审最终均为 `PASS`，未发现新的 P0/P1；该结论不提升第 6 节真实端 E2E 边界。
