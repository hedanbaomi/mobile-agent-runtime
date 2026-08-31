<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0005：Dangerous Mode 与 Android shell 执行

- 状态：v2 规范采用；实现与真实设备证据分别追踪
- 日期：2026-08-30
- 来源：[权限工具与受控执行 v2 规范](../mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)、[ADR-0004](0004-capability-authority-bridge.md)

## 背景

默认 typed tools 能表达有界的 workspace 操作，但不能表达用户主动要求的复杂 Android 诊断、管道或组合命令。为满足这一需求，v2 允许一个明确的高风险 escape hatch；该能力不能被误写成普通 Skill subprocess、Root 模式或宿主 shell。

## 决定

### 开启与持久状态

- 设置入口明确写出“允许 Agent 直接执行 Android Shell 命令”及数据丢失、应用停止、系统设置变化和设备异常风险；不默认勾选，不用暗色诱导。
- 用户确认后将 Dangerous Mode 持久保存，直到用户显式关闭。Authority 的 Binder/USB 暂时断连不自动关闭模式，也不自动撤销用户 grant；在 Authority 恢复并重新验证前，工具保持不可用。
- 必须区分两档策略：
  - `ENABLED_CONFIRM_HIGH_RISK`：普通 shell 命令可执行，风险检测器对明显高风险命令要求一次确认；检测不确定时要求确认。
  - `ENABLED_AUTONOMOUS`：用户明确允许 Agent 自主使用 shell，`shell_exec` 不逐条确认，但仍受 capability、Authority、超时、输出、并发和审计约束。
- 风险检测器只决定“是否需要确认”，不得改写命令、拆分命令、拒绝未知语法后偷偷换成其他命令，也不能宣称覆盖所有破坏性命令。

### 工具暴露与执行合同

只有以下条件同时满足时才向当前 Agent 注册 `shell_exec`：

```text
dangerousMode.enabled == true
AND current Agent allows shell.execute
AND selectedAuthority is SHIZUKU or WIRED_ADB
AND selected Authority is configured and policy-allows shell
AND current grant/snapshot/revision pass revalidation
```

输入是结构化 `command`、可选 `cwd`、`timeout_ms` 与 `max_output_bytes`；Agent 不得指定 host adb path、serial、bridge host、port 或 Shizuku service 参数。返回必须结构化，至少含 invocation reference、exit code/终态、受限 stdout/stderr 和 usage；诊断与模型展示不得越过隐私契约。

第一版只做 one-shot `/system/bin/sh`，不做 PTY、交互式终端、长期后台进程或自动重连重放。Shizuku 使用 `ShizukuShellExecutor`，Wired ADB 使用 `WiredAdbShellExecutor`；两者共享 Agent-facing schema，不共享隐含 fallback。

### 安全边界

- shell 进程不能修改 Dangerous Mode、Capability Grant、trust store 或 approval，不能读取 Runtime 内部 secret/Keystore，也不能把 secret 复制到外部工作区。
- typed workspace 的路径/symlink/配额约束仍适用于 typed tools；`shell_exec` 是有意的 escape hatch，不得虚称其仍受 typed workspace confinement。另一方面，它只能到达选定 Android Authority，不能得到 Windows 宿主 PowerShell、宿主文件系统、Root、无线 ADB、DPC、Termux 或 PTY。
- Authority 失效、grant 撤销、Agent/Skill snapshot 变化或 Dangerous Mode 关闭时，旧 call 必须返回确定的拒绝错误；派发后断连、超时或结果无法确认时返回 `UNKNOWN_OUTCOME`，不得自动重新执行。
- debug APK 只能证明开发/自动化行为。控制面安全审阅必须使用 `debuggable=false` review-like build；缺真实 Shizuku 或 USB Companion 时记 `E2E BLOCKED`。

### 诊断

允许记录 `commandSha256`、authority、policy、timeout/output bucket、stdout/stderr 字节数 bucket、duration bucket、phase 和 terminal outcome。不得记录 command/script/argv/cwd/preview/stdout/stderr/result、路径、URI、ADB serial、host、IP/port、token、secret、Prompt 或异常自由文本。诊断限额为当前段 `256 KiB`、上一段 `256 KiB`、崩溃 `32 KiB`、单事件 `4 KiB`、ZIP `640 KiB`。

## 被拒绝的替代方案

- 把 shell 约束成静态 allowlist；v2 明确允许 arbitrary shell，风险检测器只辅助确认。
- 在危险模式关闭时注册但隐藏 `shell_exec`；普通模式必须根本不暴露，旧 call 返回 `DANGEROUS_MODE_DISABLED`。
- 以 Root、`su`、无线 ADB、DPC 或 Termux 作为备用 backend；v2 只有平级的 Shizuku 与 Wired ADB，失效时 fail-closed。
- 采用 PTY 或长期 shell session；第一版保持 one-shot，避免交互状态和取消/重连语义失控。

## 验证门槛

| 验证面 | 要求 | 状态规则 |
| --- | --- | --- |
| schema/exposure | 条件交集、两档策略、关闭不注册、旧 call 拒绝 | 有代码但无测试可记 `IMPLEMENTED` |
| approval/revalidation | Agent/快照/Authority/grant/revision 绑定；撤权、切换、超时、取消、未知结果 | 自动化通过记 `AUTOMATED TESTED` |
| Shizuku | 真实 Binder/UserService、shell UID、断连/恢复且不 fallback | 缺服务或设备记 `E2E BLOCKED` |
| Wired ADB | Windows Companion、官方 adb USB、reverse、认证、断连/恢复且不 fallback | 缺 Companion/USB 设备记 `E2E BLOCKED` |
| security build | `debuggable=false` review-like build 的工具暴露、secret 隔离与审计复核 | Debug 结果不能升级为安全 PASS |
