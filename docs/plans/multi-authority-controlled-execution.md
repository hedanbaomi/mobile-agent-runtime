<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 统一权限工具与双 Authority 受控执行方案

状态：v2 规范执行中；应用私有 typed workspace `IMPLEMENTED` / `AUTOMATED TESTED`，真实 elevated Authority 与 shell 链路 `E2E BLOCKED`
日期：2026-08-30
规范基线：[权限工具与受控执行 v2](../mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)
关联：[ADR-0004](../adr/0004-capability-authority-bridge.md)、[ADR-0005](../adr/0005-dangerous-shell-mode.md)

## 目标与非目标

Agent 看到的是稳定的 capability/tool schema；Runtime 决定是否允许；backend 决定如何完成。默认 typed tools 用于有界 workspace 与 Skill memory，Dangerous Mode 才能暴露一次性 `shell_exec`。

当前路线只有两个 elevated Authority：`SHIZUKU` 与 `WIRED_ADB`。二者平级、显式选择、互不 fallback。SAF 是独立 workspace backend，不是 Authority；Root、无线 ADB、DPC、Termux、PTY、LAN bridge、宿主 PowerShell/宿主 shell 和自动 ADB 安装均排除。

## 统一状态模型

每次 Agent run 保存 `UserIntent`、`CapabilityGrant`、`Approval`、`selectedAuthority` 与 tool snapshot。Authority 状态拆成：

| 状态 | 语义 | 失效行为 |
| --- | --- | --- |
| `configured` | 用户已选择并保存 Authority | 不代表可连接或可执行 |
| `grant` | 用户授予该 Authority/Agent/capability 的可撤销权限 | 撤销立即使待批准调用失效 |
| `availability` | Shizuku Binder 或 Companion 服务可发现 | 暂时不可用不清除 grant/Dangerous Mode |
| `connection` | 当前 Binder/USB/reverse 会话已认证 | 断连停止派发，恢复需 revalidate |
| `selected` | 本 run 唯一 provider | 失效时 fail-closed，绝不改选另一 Authority |

有效能力为：

```text
package declaration
∩ user grant
∩ Agent/Skill snapshot
∩ selected Authority
∩ current policy
∩ run/tool budget
```

## Agent-facing 工具层

公开 schema 使用 provider-neutral wire name：

- workspace：`workspace_list`、`file_list`、`file_stat`、`file_read_text`、`file_write_text`、`file_create_directory`、`file_move`、`file_delete`；
- Skill memory：`memory_read`、`memory_search`、`memory_append`、`memory_replace`；
- Dangerous Mode：`shell_exec`。

完整 name → capability → backend-neutral 映射见 [验收矩阵 §3.1](../ACCEPTANCE.md)。不得暴露 `shizuku_*`、`adb_*`、`saf_*` 或 serial/URI/真实路径。当前代码中的 `workspace_read`、`workspace_write`、`memory_list` 等只能由 Host 作兼容转换，不能改变公开规范。

Typed file tools 的 backend 可以是应用私有存储、用户选择的 SAF tree 或 selected privileged adapter。所有 typed 操作执行路径/symlink/UTF-8/配额/原子写、Agent/快照绑定和 approval 后 revalidation；返回不含真实路径。`shell_exec` 有意不受 typed workspace confinement 约束，但仍受 control-plane、Authority、策略、时限、输出上限和审计保护。

## Authority A：Shizuku

- 使用显式 Shizuku permission、存活 Binder、兼容服务版本和可证明 shell UID；Root/UID 0 不属于产品路线。
- Typed path 复用共享 `PrivilegedFileEngine`；Dangerous Mode 使用独立 `ShizukuShellExecutor`，在设备端执行一次性 `/system/bin/sh`。
- Binder death 只把 availability/connection 标为暂时不可用；不撤销 grant、不关闭持久 Dangerous Mode、不切换 Wired ADB。恢复后重新验证 permission/UID/version 并 rebind。
- 真实 Shizuku 服务、权限授予和断连恢复尚未在本方案中宣称完成；无设备证据统一 `E2E BLOCKED`。

## Authority B：Windows Wired ADB Companion

- 只支持官方 adb 的有线 USB transport。Windows Companion 由用户启动，监听 loopback，并用 `adb reverse` 将 Android 回环端口接到已认证会话。
- 首次连接通过一次性短码/挑战响应建立内存会话；协议固定版本、nonce、递增序号、request ID 和 HMAC。Android 与 Companion 双端均 revalidate selected device/session。
- Companion 只接受固定协议请求；Agent 不能指定 adb 路径、serial、host、port、PowerShell、cmd.exe 或 raw command。shell 能力仍只由 `WiredAdbShellExecutor` 在 Dangerous Mode 中处理。
- 不支持无线 ADB、LAN endpoint、app 内 ADB pairing/connect、Root、DPC 或 Termux。USB 断开、reverse 消失、序号/HMAC 错误均 fail-closed；恢复不自动切 Shizuku。
- Windows 使用说明、doctor/pair/reverse/session/recovery 命令和安全边界见 [有线 ADB Desktop Bridge](wired-adb-desktop-bridge.md)。真实 Companion/USB 设备未提供时为 `E2E BLOCKED`。

## SAF workspace

SAF 由用户系统选择器产生具体 URI grant，按 workspace backend 接入 typed file tools；URI 不进入模型、诊断或 Provider 请求，不转换为全局路径，grant 可撤销。SAF 不提供 shell，也不影响 selected Authority。当前规范已定义，真实 SAF E2E 另记 `E2E BLOCKED`。

## Dangerous Mode 与失败语义

仅以下条件全部满足才注册 `shell_exec`：Dangerous Mode 持久开启、当前 Agent 允许 `shell.execute`、selected Authority 为 Shizuku/Wired ADB、Authority policy 允许 shell，且 grant/snapshot/revision 复核通过。

模式至少有 `ENABLED_CONFIRM_HIGH_RISK` 与 `ENABLED_AUTONOMOUS`。前者由风险检测器决定明显高风险命令是否需要单次确认，后者不逐条确认；检测器不得改写命令或把任意 shell 冒充 allowlist。第一版不做 PTY、长期 session 或自动重放。

派发前的拒绝返回确定错误。派发后发生超时、取消、disconnect 或响应损坏而无法确认结果时返回 `UNKNOWN_OUTCOME`，不自动重试；新的尝试必须使用新的 invocation/call ID 并重新审批。

## 诊断与安全证据

诊断只记录固定 authority/capability/phase/outcome 枚举、HMAC 引用、字节/时长/计数桶；不记录命令、argv、cwd、path、URI、serial、host/IP/port、stdout/stderr、secret、Prompt 或自由文本。当前段/上一段/崩溃/单事件/ZIP 上限固定为 `256 KiB/256 KiB/32 KiB/4 KiB/640 KiB`。

Debug APK 只支持开发和自动化测试；Dangerous Mode 控制面安全结论必须在 `debuggable=false` review-like build 独立审阅。`IMPLEMENTED`、`AUTOMATED TESTED` 和 `E2E BLOCKED` 分栏记录，不把静态/模拟器结果称为真实 Shizuku/USB E2E。

## 实施与验收顺序

1. 保持 wire schema 与 capability intersection 单一实现，补齐 tool snapshot、approval、revalidation 和终态错误。
2. 完成 Authority 状态持久化：grant、availability、connection、selected 分离；显式选定；无 fallback。
3. 完成 typed backend 的 Internal/SAF/privileged adapter 接口，后端名称不泄露给 Agent。
4. 在 Dangerous Mode 下实现并测试 Shizuku/Wired ADB 各自的 one-shot shell executor、timeout/cancel/output limit 和 UNKNOWN_OUTCOME。
5. 按 `debuggable=false` review-like build 做安全复核；再分别准备真实 Shizuku 与有线 USB Companion E2E。

验收必须证明：

- 普通模式根本不注册 `shell_exec`；危险模式两档策略与持久关闭语义正确；
- Shizuku 与 Wired ADB 同时可用时只暴露 selected Authority，任一断连均不 fallback；
- approval 前无副作用，撤权、快照/Authority 切换、重复 call ID、超时/取消/未知结果都 fail-closed 且不可自动重放；
- Companion 不接受宿主 shell/raw command，配对材料/真实路径/用户文件正文不落日志、导出或模型请求；
- Root、无线 ADB、DPC、Termux、PTY 不出现在实现承诺或验收 PASS 中；
- 缺少真实端或非 debug build 时明确记 `E2E BLOCKED`，而非 `DEVICE_PASS`。

本方案只授权本地文档、实现和受控验证，不授权正式签名、部署、付费模型调用、secret 提交或 commit/push。
