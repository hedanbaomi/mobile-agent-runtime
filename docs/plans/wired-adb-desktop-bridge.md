<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Wired ADB Windows Desktop Companion

状态：v2 规范执行中；bridge/protocol `IMPLEMENTED`，DesktopBridge/authority fixture `AUTOMATED TESTED`，真实 USB Companion E2E `E2E BLOCKED`
日期：2026-08-30
规范基线：[权限工具与受控执行 v2](../mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)
关联：[统一权限工具与双 Authority 受控执行](multi-authority-controlled-execution.md)、[ADR-0004](../adr/0004-capability-authority-bridge.md)、[ADR-0005](../adr/0005-dangerous-shell-mode.md)

## 目标与边界

Windows 首发的 `WIRED_ADB` Authority 使用官方 Android `adb` 和 USB transport，不实现 ADB wire protocol。链路固定为：

```text
Android Runtime <-> adb reverse / loopback <-> Desktop Companion <-> official adb <-> Android device
```

Companion server 只能绑定 `127.0.0.1`，不监听 LAN。多设备必须由用户显式绑定 serial，禁止选择第一台。无线 ADB、LAN bridge、Root、DPC、Termux、PowerShell、cmd.exe、PTY 和任意宿主文件系统均排除。

Companion 是与 Shizuku 平级的唯一另一 elevated Authority。用户必须显式选择 `WIRED_ADB`；USB 或 Companion 断开时 fail-closed，绝不自动切 Shizuku，也不自动启用无线 ADB。

## Windows 使用说明（设计合同）

以下是用户可见的最小 CLI 形状；实现前须锁定实际发行包名、platform-tools 路径发现和签名校验，不把下列示例当作已经存在的可执行文件：

```powershell
mar-bridge doctor
mar-bridge devices
mar-bridge pair --serial <serial>
mar-bridge run --serial <serial>
mar-bridge status --serial <serial>
mar-bridge forget --serial <serial>
```

建议操作顺序：

1. 安装并验证官方 platform-tools，使用 USB 连接用户自己的 Android 设备，在设备屏幕批准该电脑的 ADB 信任。
2. 运行 `mar-bridge doctor` 检查 adb 版本、platform-tools、回环监听和本地依赖；运行 `mar-bridge devices`，若有多台设备必须复制用户明确选择的 serial。
3. 运行 `mar-bridge pair --serial <serial>` 建立 App-level Companion trust；ADB RSA 信任不等于 App Bridge 身份。一次性 pairing token 只用于挑战响应，不写日志或普通配置。
4. 运行 `mar-bridge run --serial <serial>`。启动器为该 serial 建立 `adb reverse`，只向 Android 回环端口提供固定协议；`status` 显示 selected/configured/connected/ready，不显示 token、私钥或用户路径。
5. Android 设置中显式选择 `WIRED_ADB`，按 Dangerous Mode 风险说明决定是否开启 shell capability；普通模式仅注册 typed tools。
6. 结束时停止 `run` 或使用 Android 设置关闭 Authority。普通停止不执行 `adb kill-server`，不主动撤销 Android trusted computer；只有用户主动 `forget --serial <serial>` 才清除 App-level desktop trust。

这些步骤不授权部署、自动安装 APK、自动配对/开启无线调试、访问其他设备或执行宿主 shell。若 `doctor`/`devices`/`pair`/`run` 任一步失败，UI 必须显示确定错误并保持 `WIRED_ADB` 不可用。

## 身份与会话

- Companion 使用稳定的 adb host identity，不每次运行重生 key；Windows secret 使用 DPAPI CurrentUser。
- App-level trust 单独保存 `desktop_id`/`app_instance_id` 等受保护状态；Android 端使用 Keystore 包装的持久状态。
- pairing token 一次性；challenge-response、HKDF-SHA256、HMAC-SHA256、AEAD session、sequence、nonce、request_id、replay protection 和 protocol version 属于协议字段，但 raw token/secret/session key 不进入诊断。
- USB 拔插或 Companion 重启时丢弃 session key、保留 persistent trust；恢复后重建 reverse/session 并重新验证 selected device，不自动切换设备或 Authority。
- serial 只由用户/Host 配置并在 Companion 内部绑定；Agent 不能指定 serial、adb path、host、port 或 endpoint。

## Typed tools

Companion 通过 typed helper 执行 provider-neutral `file_*` capability，复用 `PrivilegedFileEngine`。不得用 `cmd.exe /c`、PowerShell 或字符串拼接；内部 helper 使用封闭 enum 和显式 serial，并在 Android 端再次校验 workspace scope、路径、symlink、配额、Agent/快照和 approval revision。

Agent-facing schema 不出现 `adb_write_file` 等后端名。完整 wire tool name → capability → backend-neutral 映射见 [验收矩阵 §3.1](../ACCEPTANCE.md)。Companion 的真实路径、platform-tools 路径、serial、配对材料和 adb 输出不进入模型、Provider 请求或诊断 ZIP。

## Dangerous shell

Dangerous Mode 下才允许 `WiredAdbShellExecutor` 执行统一 `shell_exec`。模型命令必须只在 Android device shell 内解释，不能先经过 Windows host shell。实现优先采用等价于以下参数列表的 process invocation，并通过 stdin 把 script 交给远端 `sh`：

```text
ProcessBuilder(adbPath, "-s", selectedSerial, "shell", "sh", "-s")
```

要求：

- `adbPath`、`selectedSerial` 来自已验证的 Companion 配置，不是模型输入；command 不参与 Windows 命令拼接。
- 只能由 Android `/system/bin/sh` 解释 command；支持 timeout、cancel、stdout/stderr 读取、exit status 和 output byte limit。
- 如果 `sh -s` 的 stderr/exit status 在某 platform-tools 版本不可靠，只能采用已测试的 shell-v2 等价实现，并在结果中如实标记能力，不能伪造分离结果。
- timeout 终止对应 adb process；Companion 退出时清理子进程；失败或断连后不自动重放未知 invocation。
- 严禁 `cmd.exe /c "adb shell <LLM command>"`、`powershell -Command ...`、`Runtime.exec(String)` 和任何先由宿主 shell 解释的实现。

`shell_exec` 是 Android 高风险 one-shot escape hatch，不是 allowlist runner，也不是 PTY/长期后台 session。它不能修改 trust、grant、approval 或 Dangerous Mode，不能读取 Runtime/Keystore secret，不提供 Root/无线 ADB/DPC/Termux 能力。

## 协议与恢复

固定协议至少包含版本、nonce、递增 sequence、request ID、HMAC 和结构化 result。桥的阶段为 `doctor`、`pair`、`reverse`、`session`、`request`、`recovery`；断连、serial 改变、HMAC/sequence 异常、协议版本不兼容或 Android 端拒绝均 fail-closed。

恢复顺序是：检测 USB/adb → 验证仍是用户绑定的 serial → 重建 reverse → 建立新的 App session → revalidate grant/Agent snapshot/Dangerous Mode → 恢复 tool exposure。任何一步失败都不切到 Shizuku，不自动启动 wireless adb。派发后无法确认结果时返回 `UNKNOWN_OUTCOME`，新的尝试须新的 invocation/call ID。

## 证据与验收

| 面 | 需要证明 | 当前状态 |
| --- | --- | --- |
| CLI/doctor | platform-tools、版本、USB、loopback 和固定 serial 检查，不泄露 secret | parser/bridge `IMPLEMENTED`；部分 `AUTOMATED TESTED`；真实 CLI/设备 E2E `E2E BLOCKED` |
| Pair/session | 一次性 token、challenge/HMAC、DPAPI/Keystore、sequence/replay protection | bridge/存储 `IMPLEMENTED`；部分 `AUTOMATED TESTED`；真实跨端 `E2E BLOCKED` |
| Typed path | file tools 与 Android 双端 revalidation、原子写、配额、未知结果 | 应用私有 backend 有 `AUTOMATED TESTED`；Wired backend `E2E BLOCKED` |
| Dangerous shell | 非 host shell、Android `/system/bin/sh`、timeout/cancel/output、终态 | executor `IMPLEMENTED`；backend fixture `AUTOMATED TESTED`；真实 USB 执行 `E2E BLOCKED` |
| Recovery/no-fallback | 拔插、Companion 重启、reverse 丢失、serial 改变不切 Authority | 需真实设备；缺设备记 `E2E BLOCKED` |
| Security build | `debuggable=false` review-like build 的暴露、trust、secret 和 approval 审阅 | Debug 证据不能替代；缺 build 记 `E2E BLOCKED` |

文档、协议和本地测试不授权正式签名、部署、付费调用、secret 提交或 commit/push。
