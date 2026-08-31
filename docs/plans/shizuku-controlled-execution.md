<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Shizuku 受控执行子路线图

状态：v2 规范执行中；Authority/shell runner `IMPLEMENTED`，shell boundary `AUTOMATED TESTED`，真实 Shizuku 设备链路 `E2E BLOCKED`
日期：2026-08-30
规范基线：[权限工具与受控执行 v2](../mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)
总方案：[统一权限工具与双 Authority 受控执行](multi-authority-controlled-execution.md)

## 目标

交付可撤销、可审计、与 Wired ADB 平级的 Shizuku Authority。Shizuku 不是必需依赖；它不可用时不自动选择 Wired ADB。Agent 只看到 provider-neutral typed tools 与 Dangerous Mode 的统一 `shell_exec`，不看到 Shizuku service、Binder、UID、真实路径或 backend 专用名称。

## 锁定边界

- Authority wire name 只有 `SHIZUKU`。它需要存活 Binder、显式 Shizuku permission、兼容服务版本和策略允许；安装提示、Developer options 或无线调试可见不等于授权/可用。
- 只接受受审查的 Shizuku shell 身份；Root/UID 0、`su` 和 rooted provider 不属于产品路线。不得把 Shizuku 写成 Root 模式。
- Shizuku 与 `WIRED_ADB` 平级。Binder dead、撤权或协议不兼容时返回确定错误，绝不 fallback 到 Wired ADB、Root、无线 ADB、Termux 或 DPC。
- typed path 使用共享 `PrivilegedFileEngine`，继续执行 capability、Agent/快照、approval 后 revalidation、路径/symlink/配额、UTF-8 和原子写。Dangerous Mode 才使用独立 `ShizukuShellExecutor`。
- `shell_exec` 仅在 Dangerous Mode 和当前 selected Shizuku 满足策略时注册；设备端执行一次性 `/system/bin/sh`，支持 cwd、timeout、cancel、output limit、exit code 和 UNKNOWN_OUTCOME，不做 PTY 或长期 session。
- Shizuku executor 不能修改 Dangerous Mode、grant、trust store 或 approval，不能读取 Runtime/Keystore secret，也不能为 Skill/Python worker 提供宿主文件系统逃逸。
- 无清单 Skill、Python runtime、SAF workspace 和 Shizuku Authority 是不同信任域；模型、Skill、Prompt、MCP 或公告不能创建 grant/approval。

## 状态与生命周期

| 状态 | 进入条件 | 工具行为 |
| --- | --- | --- |
| `NOT_CONFIGURED` | 用户未选择 Shizuku | 不注册 privileged tools 或 shell |
| `CONFIGURED` | 用户选择 Shizuku 并保存 grant 绑定 | 仍需 Binder/permission |
| `TEMPORARILY_UNAVAILABLE` | Binder dead、服务停止或版本不兼容 | 不派发；不清除 grant/Dangerous Mode；不切 Wired ADB |
| `READY` | Binder alive、permission、UID/version/policy 全部复核 | 只服务 selected run |
| `REVOKED` | 用户或系统撤销 permission/grant | 立即使待审批/旧 call 失效，需显式重新授权 |
| `UNKNOWN_OUTCOME` | 派发后 Binder death/timeout/坏响应无法确认 | 不自动重放；新调用需新 call ID 和 approval |

Binder received/dead 监听只更新 availability/connection；恢复后重新验证 permission、服务 UID、版本、Agent/快照、grant revision 并 rebind UserService。持久 Dangerous Mode 原样保留，但在 revalidation 前不重新暴露 shell。

## 阶段

### S0：供应链与最小权限

固定经审查的 Shizuku API/provider 版本、许可证、Gradle lock 和 strict verification metadata；Manifest 不增加广泛存储、Device Admin、Root、无线 ADB 或其他非 v2 权限。第三方许可单独记录，不削弱仓库 AGPL-3.0-only。

### S1：探测、授权与选择

Settings 分开呈现检测、请求授权、打开 Shizuku 说明、选择 Authority、撤销/忘记。持久化 grant 不等于当前 connection；selected Authority 只由用户改变。无 Binder/permission 时不调用工具，不因 Wired ADB 可用而自动替代。

### S2：typed backend

Shizuku UserService 承载受审查的 Java/Kotlin 文件 API，不启动 Skill subprocess。它实现 backend-neutral `file_*` 语义，由 Host 在客户端和服务端都校验 workspace scope、路径、symlink、配额、Agent/快照、call ID 和 approval revision。公开 schema 不使用 `shizuku_workspace_*`。

### S3：Dangerous shell backend

在 Dangerous Mode 下增加 `ShizukuShellExecutor`：接收已通过 Runtime revalidation 的 command/cwd/limits，在 shell UID 环境启动一次 `/system/bin/sh`，捕获 stdout/stderr、exit code，传播 timeout/cancel，关闭 process/pipe，超限截断并产生结构化终态。风险检测器只能决定确认，不改命令或将 shell 伪装成 allowlist。

### S4：恢复、隐私与错误

为 exposure、approval、revalidation、dispatch、execution、terminal 记录闭合诊断事件；只写 authority/capability/phase/outcome、HMAC 引用、字节/时长/计数桶，不写 command、argv、cwd、path、URI、serial、stdout/stderr、token、secret 或异常自由文本。应用内限额为 256 KiB 当前段、256 KiB 上一段、32 KiB 崩溃、4 KiB 单事件、640 KiB ZIP。

## 验收门槛与状态

| 验收面 | 需要证明 | 当前状态 |
| --- | --- | --- |
| authority state | grant/availability/connection/selected 分离；Binder death/rebind 不 fallback | `IMPLEMENTED`（源码/协议基线） |
| typed path | 路径/symlink/配额/原子写/call ID/approval revalidation | 应用私有 path 有 `AUTOMATED TESTED`；Shizuku backend `E2E BLOCKED` |
| shell path | Dangerous Mode 两档、one-shot、timeout/cancel/output/UNKNOWN_OUTCOME | Runtime/runner `IMPLEMENTED`；shell boundary `AUTOMATED TESTED`；真实 Authority 执行 `E2E BLOCKED` |
| security build | `debuggable=false` review-like build 的暴露、secret/trust/approval 隔离 | 未提供该 build 时 `E2E BLOCKED` |
| real device | 真实 Shizuku 服务、用户 grant、写/读/列/删除、Binder death/reconnect | 缺真实服务/设备时 `E2E BLOCKED`，不记 `DEVICE_PASS` |

## 明确排除

本路线不实现、不验收 Root/UID 0、无线 ADB、DPC、Termux、宿主 PowerShell、宿主 shell、LAN bridge、PTY、长期 shell session、自动安装 ADB 或权限绕过。它也不授权正式签名、部署、付费调用、secret 提交或 commit/push。
