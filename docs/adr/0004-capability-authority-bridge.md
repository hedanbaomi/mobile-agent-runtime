<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0004：统一能力、Authority 与受控执行桥

- 状态：v2 规范采用；应用私有 typed workspace 已 `IMPLEMENTED`/`AUTOMATED TESTED`，真实 Shizuku/USB shell E2E 为 `E2E BLOCKED`
- 日期：2026-08-30
- 来源：[权限工具与受控执行 v2 规范](../mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)

## 背景

Agent 需要在用户批准后读写工作成果，并在明确的高风险模式下执行 Android shell。Android 基于 Linux 不代表普通应用自动拥有 shell、`adbd`、Root、其他应用私有目录或 Windows PowerShell。若把工作区、SAF、Shizuku、ADB、DPC、Termux 和 Root 合成一个“高权限”开关，就会隐藏授权主体、作用域、撤权、连接状态和审计差异。

## 决定

### 1. Agent-facing 能力保持中性

- 默认只暴露 provider-neutral typed tools：`workspace_list`、`file_list`、`file_stat`、`file_read_text`、`file_write_text`、`file_create_directory`、`file_move`、`file_delete`，以及 `memory_read`、`memory_search`、`memory_append`、`memory_replace`。
- wire tool name 映射到 capability 和 backend-neutral 语义，详见 [验收矩阵 §3.1](../ACCEPTANCE.md)。公开 schema 不出现 `shizuku_*`、`adb_*`、`saf_*` 或其他 backend 名称；现有 `workspace_read`、`workspace_write`、`memory_list` 等仅能作为 Host 内部兼容名。
- Typed file tools 继续执行路径、symlink、UTF-8、配额、原子写、Agent/快照和 approval revalidation。SAF 是 workspace backend，不是 elevated Authority。

### 2. 只有两个 elevated Authority，且平级无 fallback

- `SHIZUKU`：Binder 存活、显式 Shizuku grant、服务 UID/版本可证明且策略允许时可用。
- `WIRED_ADB`：Windows Desktop Companion 通过官方 adb 的 USB transport、`adb reverse`、loopback、一次性认证和固定协议可用。
- grant、availability、connection 和 selected provider 是不同状态。用户显式选择 Authority；selected provider 断开、撤权或不可用时 fail-closed，绝不自动切换到另一 Authority。
- Root、无线 ADB、DPC/Device Owner/Profile Owner、Termux、PTY、LAN bridge 和宿主 PowerShell/宿主 shell 均不在 v2 当前路线；不得用未来适配器或“设备管理权限”措辞暗示已实现。

### 3. Dangerous Mode 是独立高风险 control-plane

- `shell_exec` 只有在 Dangerous Mode 已持久开启、当前 Agent 获得 dangerous shell capability、selected Authority 已配置且其策略允许时才注册。
- Dangerous Mode 至少有 `ENABLED_CONFIRM_HIGH_RISK` 和 `ENABLED_AUTONOMOUS` 两档；用户显式关闭前保持状态，但 Authority 暂时断连时不派发，恢复后重新验证原 selected Authority。
- `shell_exec` 是明确的 Android one-shot `/system/bin/sh` escape hatch，支持 `cwd`、timeout、cancel、output limit 和结构化 exit/result；不做 PTY，不让 Agent 指定 adb path/serial/host/port，不自动重放未知结果。
- 风险检测器只能决定是否要求单次确认，不能修改命令、拆命令或声称把任意 shell 变成完整 allowlist。Shell executor 不得修改 trust store、伪造 approval、读取 Runtime secret 或把 secret 复制到外部目录。

### 4. 安全、诊断与证据

- Debug APK 可用于自动化测试，但不是 control-plane 安全证据。Dangerous Mode、工具暴露、approval 绑定和 Authority 断连恢复的安全结论必须在 `debuggable=false` review-like build 上独立复核。
- 诊断只写闭合事件、固定枚举、引用 HMAC、桶化字节/时长/计数和终态；不写 command、argv、cwd、path、URI、ADB serial、host/IP/port、stdout/stderr、token、secret、Prompt 或自由文本。限额固定为当前段/上一段/崩溃/单事件/ZIP = `256 KiB/256 KiB/32 KiB/4 KiB/640 KiB`。
- 事件在派发后无法确认时为 `UNKNOWN_OUTCOME`，不以失败掩盖未知，也不自动重放。缺真实 Shizuku、USB Companion 或非 debug build 时，证据分别标为 `E2E BLOCKED`，不得升格为 `DEVICE_PASS` 或 `RELEASED`。

## 被拒绝的替代方案

- 在 Skill/Python runtime 或 Android 普通模式内提供宿主 shell、PowerShell、PTY、`ProcessBuilder` 或任意 subprocess；这会绕过隔离 worker 和本地 control-plane。
- 把 Shizuku、ADB、Root、DPC、Termux、SAF 归为单一“高权限”或自动 fallback；其身份、授权和撤权语义不同。
- 把无线 ADB 或 LAN Companion 当作 Wired ADB 的等价传输；v2 只支持有线 USB Desktop Companion。
- 将 `shell_exec` 伪装成 allowlist command runner，或将 typed workspace confinement 虚称为 shell 的安全沙箱。

## 影响与验证

应用私有 workspace 的现有实现和 API 31 x86_64 自动化证据可继续作为 typed path 的实现基线；历史第四轮“SAF、Termux、无线 ADB、DPC、root/Shizuku 和任意 shell 未实现”结论保持时间限定，不改写为 v2 E2E 证据。后续证据必须分列：

| 能力 | 当前状态 | 不能声称 |
| --- | --- | --- |
| 应用私有 typed workspace | `IMPLEMENTED`、`AUTOMATED TESTED` | 不能推导 SAF 或 elevated Authority E2E |
| Authority 状态/selected provider/no-fallback 模型 | `IMPLEMENTED`（源码/协议基线） | 不能推导真实 Shizuku/USB 连接成功 |
| Shizuku typed/shell backend | runner/adapter 已 `IMPLEMENTED`；shell boundary 有 `AUTOMATED TESTED` fixture | 无真实服务不得声称 `DEVICE_PASS` |
| Wired ADB Companion | bridge/协议已 `IMPLEMENTED`；DesktopBridge/authority fixture 有 `AUTOMATED TESTED` | 无真实 USB Companion 不得声称 `DEVICE_PASS` |
| `shell_exec` Dangerous Mode | Runtime executor 已 `IMPLEMENTED`；部分 backend 有 `AUTOMATED TESTED` | debug/JVM/静态结果不等于非 debug 安全 PASS |

总方案见 [统一权限与受控执行方案](../plans/multi-authority-controlled-execution.md)，Shizuku 细节见 [Shizuku 受控执行子路线图](../plans/shizuku-controlled-execution.md)，危险 shell 细节见 [ADR-0005](0005-dangerous-shell-mode.md)，Windows Companion 见 [有线 ADB Desktop Bridge](../plans/wired-adb-desktop-bridge.md)。
