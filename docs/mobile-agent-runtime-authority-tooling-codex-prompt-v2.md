<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Codex 一次性实施任务：MobileAgentRuntime 权限、工具暴露、持久高权限与“危险模式” Shell 架构 v2

你现在位于本地仓库 `E:\mobileAgentRuntime`。请在**不丢弃、覆盖、回滚或隐藏现有未提交改动**的前提下，完成本文件规定的架构收敛、代码实现、测试和文档更新。

本文件是 v2 完整执行规范，**整体取代此前的 v1 方案**。不要把它当作增量补丁，也不要继续保留与本规范冲突的“永远禁止任意 Shell”设计。

请实际修改代码并尽可能完成验收，不要只写方案。除非存在无法绕过的真机、USB、Shizuku 或桌面环境硬阻断，否则不要中途询问用户，不要停在半成品状态。遇到外部硬件阻断时，完成所有不依赖该硬件的实现、自动化测试、文档和可验证的模拟链路，再明确报告阻断点。

---

# 0. 产品目标与强制边界

MobileAgentRuntime 的长期定位不只是“安全文件工具”，而是一个**能够在 Android 手机上提供接近 Codex 的 Agent 运行体验**的移动端 Runtime。

因此系统必须同时支持两种工作方式：

1. **默认安全模式**
   - Agent 优先使用稳定、类型化、后端无关的工具；
   - 权限按 capability、workspace、Agent、Skill 细粒度控制；
   - 普通用户和普通 Skill 不接触 Shell；
   - 角色扮演 Skill 的长期记忆完全不依赖高权限。

2. **危险模式（Dangerous Mode）**
   - 由用户显式开启；
   - 开启后允许 LLM 直接使用当前已选高权限 Authority 提供的 **arbitrary Android shell**；
   - 允许命令管道、重定向、`&&`、`||`、`find`、`grep`、`sed`、`pm`、`am`、`cmd`、`settings`、`dumpsys`、`getprop` 等 shell 能力；
   - 目标是让 Agent 可以像 Codex 使用终端一样观察环境、组合命令、执行、读结果、根据报错继续迭代；
   - 危险模式不是 Root，不应声称具备 Root 权限。

## 0.1 必须实现

- 应用私有沙箱文件后端；
- SAF 持久授权目录正式接入 Agent Workspace；
- Shizuku 高权限后端正式化；
- Windows 优先的**有线 USB ADB Desktop Companion**；
- 后端无关的 Agent typed tools；
- Capability / Workspace / Authority / Approval / Audit 统一模型；
- Skill 独立持久记忆；
- 高权限长期 grant 与临时连接状态分离；
- 用户显式、持久的 **Dangerous Mode**；
- Dangerous Mode 下向 Agent 暴露统一的 `shell_exec`；
- `shell_exec` 同时支持 Shizuku 和 Wired ADB 两种 backend；
- Shell 输出、超时、大小限制、审计、取消、错误码；
- 用户可选择“危险模式完全自主”或“高危命令仍需确认”的策略。

## 0.2 明确不做

本轮不得实现或扩展：

- Root、`su`、Magisk、Root Shell；
- 应用内无线 ADB；
- Android 无线调试 pairing/connect 协议；
- Device Admin / Device Owner / Profile Owner / DPC；
- 自动在 Shizuku 与 Wired ADB 之间回退；
- 网络暴露的远程 shell 服务；
- 局域网桌面桥；
- 自动安装、下载或更新 adb 可执行文件；
- 自动获得或绕过 Android/ADB/Shizuku 用户授权；
- 第一版完整 PTY、终端模拟器、交互式 REPL；
- 第一版通用 Python/Node/Git 工具链安装器；
- 第一版 Accessibility/屏幕/输入自动化能力。

可以为未来 PTY/工具链扩展预留接口，但不得让当前任务膨胀。

## 0.3 Git 与许可证边界

- 不得 `git reset` / `checkout` / `clean` / `stash` 破坏当前工作区；
- 不得覆盖用户现有未提交修改；
- 不得 commit、push、发布或部署；
- 保持 AGPL-3.0 许可口径；
- 新增第三方依赖必须检查许可证兼容性；
- 不得把项目改成 MIT/Apache-2.0 等其他主许可证。

开始前先读取并核对：

- `docs/plans/multi-authority-controlled-execution.md`
- `docs/plans/shizuku-controlled-execution.md`
- `docs/adr/0004-capability-authority-bridge.md`
- 当前未提交 diff
- 当前 ToolExecutor / ToolRegistry / tool snapshot / callId 绑定
- 当前文件工具及 Policy
- 当前审批机制
- 当前持久化层
- 当前 Shizuku Provider / AIDL / UserService
- 当前 Skill 格式与 Python Skill 执行模型

优先复用和迁移现有实现，不要另起一套互相冲突的平行体系。

---

# 1. 最重要的架构原则

必须严格区分以下概念：

```text
Agent Tool
Capability
Workspace
Policy / Approval
Authority
Execution Backend
Connection / Session
```

推荐主链：

```text
Agent
  -> ToolRegistry
  -> EffectiveCapabilityResolver
  -> PolicyEngine
  -> ApprovalEngine
  -> Workspace/Action Resolver
  -> selected Authority / Backend
  -> executor-side revalidation
  -> execution
  -> structured ToolResult
  -> AuditEvent
```

核心原则：

> Agent 看见的是“我要做什么”；Runtime 决定“是否允许”；Backend 决定“如何做到”。

只有 Dangerous Mode 下的 `shell_exec` 是有意允许 Agent 直接描述 shell 程序。

---

# 2. Agent 工具暴露模型

## 2.1 默认 Typed Tools

至少保留/统一以下 provider-neutral 工具：

```text
workspace_list

file_list
file_stat
file_read_text
file_write_text
file_create_directory
file_move
file_delete

memory_read
memory_search
memory_append
memory_replace
```

公开 schema 中不得出现：

```text
shizuku_read_file
adb_write_file
sandbox_delete
saf_read
```

统一使用中性参数，例如：

```json
{
  "workspace_id": "downloads",
  "relative_path": "foo/bar.md"
}
```

普通 typed tools 不应要求模型理解：

- `content://` URI；
- Android 绝对真实路径；
- ADB serial；
- Binder；
- reverse 端口；
- Desktop Companion；
- Shizuku Service。

## 2.2 Dangerous Mode 下新增 Shell Tool

仅当以下条件全部满足时，才向当前 Agent 注册 `shell_exec`：

```text
dangerousMode.enabled == true
AND
当前 Agent 被允许使用 dangerous shell
AND
selectedElevatedAuthority 已配置
AND
该 Authority 在策略层允许 shell capability
```

工具建议：

```text
shell_exec
```

输入：

```json
{
  "command": "find /sdcard/Download -type f | head -n 50",
  "cwd": "/sdcard",
  "timeout_ms": 30000,
  "max_output_bytes": 1048576
}
```

字段要求：

- `command`: 必填，真正的 shell 程序文本；
- `cwd`: 可选；若 backend 无法可靠设置 cwd，则由 shell 侧安全地 `cd` 后执行；
- `timeout_ms`: Runtime clamp 到合理范围；
- `max_output_bytes`: Runtime clamp，防止无限输出；
- 不允许 Agent 指定 host adb 路径、serial、bridge host、端口、Shizuku service 参数。

返回必须结构化：

```json
{
  "success": true,
  "exit_code": 0,
  "stdout": "...",
  "stderr": "",
  "timed_out": false,
  "cancelled": false,
  "stdout_truncated": false,
  "stderr_truncated": false,
  "authority": "SHIZUKU",
  "duration_ms": 123
}
```

失败时也必须返回稳定错误，不用自然语言猜状态。

## 2.3 Shell 是刻意的 escape hatch

不要把 Dangerous Mode 的 `shell_exec` 又偷偷变成 allowlist command runner。

在 Dangerous Mode 下，以下 shell 语法应正常工作：

```text
|
>
>>
&&
||
;
变量
子命令
glob
find/grep/sed/awk（设备存在时）
pm/am/cmd/settings/dumpsys/getprop
```

不要试图“解析 shell 后只允许安全子集”；这会破坏“手机上的 Codex”的产品目标。

安全边界应由：

- 用户显式危险模式授权；
- Authority 权限本身；
- Agent/Skill capability；
- 审批策略；
- 输出/时间/资源限制；
- Runtime 控制面隔离；
- 审计；

来承担，而不是伪装成 shell 的命令白名单。

## 2.4 Typed Tools 与 Shell 共存

即使 Dangerous Mode 开启，也不得删除 typed tools。

预期策略：

```text
普通稳定操作 -> typed tools
复杂环境探索/组合操作 -> shell_exec
```

Agent 可自行选择最合适工具。

---

# 3. Capability 模型

Capability 不得直接等同 Android 权限名。

至少定义：

```text
workspace.enumerate

file.list
file.stat
file.read_text
file.write_text
file.create_directory
file.move
file.delete

memory.read
memory.search
memory.append
memory.replace

shell.execute
```

如现有模型允许，可额外引入：

```text
shell.inspect_environment
```

但不要为每个 shell 命令创建 capability。

有效权限必须是交集：

```text
平台实际 Authority
∩ 用户启用状态
∩ 当前 Agent grant
∩ 当前 Skill manifest grant
∩ Workspace ACL（适用于文件工具）
∩ Runtime policy
∩ Dangerous Mode 状态（适用于 shell）
∩ 当前 approval policy
```

无权的工具：

- 优先根本不注册到模型；
- 即使被缓存 schema、重放旧 call 或绕过 UI 调用，也必须在执行入口 fail-closed。

---

# 4. Workspace 抽象

统一 `Workspace` / `WorkspaceBackend`，至少：

```text
INTERNAL
SAF_TREE
PRIVILEGED
```

建议：

```kotlin
data class WorkspaceDescriptor(
    val id: String,
    val displayName: String,
    val backendType: WorkspaceBackendType,
    val rootReference: String,
    val readable: Boolean,
    val writable: Boolean,
    val quotaBytes: Long?,
    val maxFileBytes: Long,
    val enabled: Boolean
)
```

`rootReference` 永远不暴露给 Agent。

## 4.1 文件路径安全

所有 typed file backend 必须继续保证：

- 只接受相对路径；
- 拒绝绝对路径；
- 拒绝 NUL；
- 拒绝 `..` 越界；
- 统一 `/`、`\`、Unicode 规范化后判定；
- `lstat`/等价检查防符号链接逃逸；
- 删除只允许普通文件或空目录；
- 禁止删除 workspace 根；
- 原子写；
- 单文件大小限制；
- 总 quota；
- 目录项数量限制；
- 乐观并发 / expected version；
- executor 侧二次验证；
- 不记录正文。

**注意：这些约束只约束 typed file tools。Dangerous Mode 的 `shell_exec` 是明确的高风险 escape hatch，不要假装它仍受 workspace confinement。**

这一区分必须在代码和文档中说清楚。

## 4.2 SAF

正式接入 Agent Workspace：

- 系统目录选择器；
- `takePersistableUriPermission()`；
- 持久化 URI + grant flags；
- App 启动、恢复、危险操作前重新验证；
- provider 撤销后标记 `GRANT_LOST`；
- 根据 provider 实际 capability 判断 create/rename/delete；
- URI 不暴露给模型。

本轮不新增 `MANAGE_EXTERNAL_STORAGE` 主线实现；如现有工程已有相关代码，不要破坏，但不要把它当本方案必需项。

---

# 5. Skill 记忆

角色扮演与其他 Skill 的长期记忆必须完全可以停留在应用私有目录：

```text
skills/<skill-id>/
  SKILL.md
  scripts/
  assets/
  memory/
    MEMORY.md
    journal/
      YYYY-MM-DD.md
```

规则：

- `SKILL.md` 默认只读；
- `scripts/` 默认只读；
- `assets/` 默认只读；
- `memory/` 是 Runtime 管理的持久可写状态；
- Agent 使用 `memory_*`，而不是绝对路径写文件；
- `memory_*` 自动绑定当前 skill id；
- Skill 不能指定其他 Skill 的 memory；
- 单 Skill quota；
- journal 数量/大小限制；
- 记忆工具默认不需要 Shizuku/ADB；
- 设备已经有高权限不等于 Skill 自动得到 `shell.execute`。

第三方 Skill 如果希望调用 shell，必须在 manifest/capability 层显式声明，同时用户/Agent policy 也允许，最后仍受 Dangerous Mode 总开关约束。

---

# 6. Authority 与权限生命周期

支持的 Elevated Authority 只保留：

```text
SHIZUKU
WIRED_ADB
```

明确不存在：

```text
ROOT
WIRELESS_ADB
DPC
```

不要预埋 Root backend 造成未来误用惯性。

至少拆分状态：

```text
UserIntent:
  ENABLED | DISABLED

PlatformGrant:
  UNKNOWN | GRANTED | DENIED | REVOKED

Availability:
  READY | TEMPORARILY_UNAVAILABLE | UNSUPPORTED

Connection:
  DISCONNECTED | CONNECTING | CONNECTED | DEGRADED
```

Dangerous Mode 另有独立状态：

```text
DangerousMode:
  DISABLED
  ENABLED_CONFIRM_HIGH_RISK
  ENABLED_AUTONOMOUS
```

## 6.1 长期 grant 与临时连接严格分离

以下事件不得主动清除：

- Authority 用户选择；
- Shizuku grant 的逻辑记录；
- ADB desktop trust record；
- Dangerous Mode 用户设置；
- Agent/Skill persistent capability grant；
- SAF persisted grant 记录。

以下事件**只改变 availability / connection / session**：

- Agent task 结束；
- 会话结束；
- App 后台；
- Activity 销毁；
- Binder death；
- Shizuku 服务暂未启动；
- USB 拔出；
- adb `offline`；
- Companion 退出；
- `adb reverse` 丢失；
- App 普通重启；
- Companion 普通重启。

权限模型必须遵循：

> 权限是长期授权；连接是临时状态。连接断开不等于权限撤销。

## 6.2 可以清理长期授权的情况

仅允许：

- 用户显式关闭/撤销；
- 用户“忘记电脑”；
- 平台明确报告 grant 被撤销；
- App 数据被清除/重装造成 app instance identity 变化；
- 持久密钥无法解密且无法安全恢复；
- 持久状态损坏且迁移无法恢复。

即使 ADB 变成 `unauthorized`，也保留桌面记录并显示 `REAUTH_REQUIRED`，而不是自动删除。

## 6.3 Dangerous Mode 也必须持久

危险模式不能因为：

```text
任务结束
聊天结束
App 切后台
USB 拔线
Shizuku 临时不可用
```

自动关闭。

用户一旦显式开启，应持久保存，直到用户显式关闭。

例如：

```text
dangerousMode = ENABLED_AUTONOMOUS
selectedAuthority = SHIZUKU
availability = TEMPORARILY_UNAVAILABLE
```

此时 UI 应显示：

```text
危险模式：已开启
Shell：当前不可用
```

Shizuku 恢复后重新注册/启用 shell tool，不要求用户再次开启危险模式。

---

# 7. Dangerous Mode UX 与审批策略

## 7.1 开启入口

在高级权限/Agent 安全设置中加入：

```text
危险模式
允许 Agent 直接执行 Android Shell 命令
```

首次开启必须显示清晰风险说明，例如：

> 开启后，AI 可以使用当前 Shizuku 或有线 ADB 的 shell 权限直接执行命令。它可能修改或删除文件、停止应用、修改部分系统设置，并可能因错误命令导致数据丢失或设备状态异常。此功能不是 Root，但具有明显高于普通应用的风险。

要求用户明确确认后才能打开。

不要使用暗色诱导或默认勾选。

## 7.2 两档执行策略

危险模式至少支持：

```text
ENABLED_CONFIRM_HIGH_RISK
ENABLED_AUTONOMOUS
```

含义：

### ENABLED_CONFIRM_HIGH_RISK

- 普通 shell command 可自主执行；
- Runtime 通过保守的风险检测器识别明显高风险命令，要求单次确认；
- 风险检测器**只能决定是否确认**，不得修改命令、拆命令、把 shell 变 allowlist；
- 检测不确定时宁可提示确认。

### ENABLED_AUTONOMOUS

- 用户已经明确授权 Agent 自主使用 shell；
- `shell_exec` 不逐条确认；
- 仍受 Authority、Agent/Skill capability、超时、输出、并发、审计约束；
- UI 应明确这是最高风险的非 Root 模式。

如果实现风险检测器，至少考虑：

- 大范围删除/覆盖；
- `rm` 针对高层目录；
- `dd`；
- `mkfs`；
- `settings put`；
- `pm uninstall/disable/clear`；
- `cmd package` destructive action；
- 大范围 `chmod/chown`；
- reboot/shutdown；
- 写 `/proc`、`/sys`、关键 Android 配置区；
- kill/force-stop 大量进程；
- shell 中再次启动长期后台任务。

不要承诺检测完整性；文档明确它只是确认辅助，不是安全沙箱。

## 7.3 普通模式

危险模式关闭时：

- `shell_exec` 不注册；
- 旧 tool call 重放必须返回 `DANGEROUS_MODE_DISABLED`；
- Shizuku/ADB 即使可用，也只能供 typed capability backend 使用。

---

# 8. Shizuku Backend

复用当前 Provider、AIDL、UserService。

## 8.1 Typed path

现有 typed 文件能力继续通过共享 `PrivilegedFileEngine`，保留：

- 路径约束；
- symlink 防护；
- 配额；
- 原子写；
- callId / tool snapshot；
- approval 后 revalidation。

## 8.2 Shell path

Dangerous Mode 下增加独立：

```text
ShizukuShellExecutor
```

其职责仅是：

- 在 Shizuku 提供的 shell UID 环境执行 `/system/bin/sh`；
- 接收 Runtime 已批准的 command；
- 支持 cwd；
- 捕获 stdout / stderr；
- 获取 exit code；
- timeout；
- cancel；
- output byte limit；
- 正确关闭 process/pipe；
- 进程异常退出后不污染后续请求。

不允许 Shell executor：

- 修改 Dangerous Mode 配置；
- 修改 trust store；
- 伪造 approval；
- 读取 Runtime 内部 secret；
- 直接访问 Android Keystore secret。

如果 shell UID 自身根据 Android DAC/SELinux 无法访问 Runtime 私有目录，这是自然安全边界；不要为了让 shell 更强而复制内部 secret 到外部存储。

## 8.3 Shizuku 状态恢复

- Binder received/dead listener；
- Binder dead -> `TEMPORARILY_UNAVAILABLE`；
- 不 revoke grant；
- Binder 恢复 -> revalidate permission -> rebind UserService；
- dangerous mode 保持原状态；
- shell tool availability 自动恢复；
- 不自动切换到 Wired ADB。

---

# 9. Wired USB ADB Desktop Companion

## 9.1 总体要求

Windows 首发，使用官方 adb，不实现 ADB wire protocol。

建议模块：

```text
:bridge-protocol
:desktop-bridge
:adb-helper
```

如仓库结构不适合名称可调整，但职责不可混淆。

## 9.2 连接模型

保留 App 与 Companion 的类型化控制桥：

```text
Android Runtime
  <-> adb reverse / loopback
Desktop Companion
  <-> official adb
Android device
```

Desktop server 只能绑定 `127.0.0.1`。

不允许监听 LAN。

多设备必须显式 serial 绑定，禁止“选第一台”。

## 9.3 长期 ADB 身份

- Companion 使用稳定的 adb host identity；
- 不每次运行重生 key；
- 不主动 revoke Android 的 trusted computer；
- 不在普通停止时 `adb kill-server`；
- 不主动执行“清除所有 ADB 授权”；
- USB 拔插只重建连接/session；
- 用户主动 forget 才清除 App-level desktop trust。

## 9.4 App-level Companion trust

ADB RSA 信任不等于 App Bridge 身份。

继续实现独立：

- `desktop_id`；
- `app_instance_id`；
- 一次性 pairing token；
- challenge-response；
- HKDF-SHA256；
- HMAC-SHA256；
- AEAD session；
- sequence；
- nonce；
- request_id；
- replay protection；
- protocol version。

Android secret 存 Android Keystore 包装的持久状态；
Windows secret 用 DPAPI CurrentUser；
日志不得出现 token/secret/session key。

普通 USB 断线或 Companion 重启：

```text
session key -> 丢弃
persistent trust -> 保留
```

## 9.5 Wired ADB typed tools

继续通过 typed helper，不让模型参数变成 host shell 命令。

Desktop Companion 对 typed request：

- 不经 `cmd.exe /c`；
- 使用 `ProcessBuilder(List<String>)`；
- 显式 serial；
- helper 使用封闭 enum；
- 复用 `PrivilegedFileEngine`。

## 9.6 Wired ADB Dangerous Shell

Dangerous Mode 下，允许 Desktop Companion 执行真正的设备 shell。

增加：

```text
WiredAdbShellExecutor
```

关键安全约束：

> 模型命令必须只在 Android device shell 内被解释，绝不能先经过 Windows `cmd.exe` / PowerShell / host shell。

优先使用类似：

```text
ProcessBuilder(
  adbPath,
  "-s", selectedSerial,
  "shell",
  "sh",
  "-s"
)
```

然后把模型生成的 script 通过 stdin 写给远端 `sh`，EOF 后读取 stdout/stderr 与 adb process exit status。

如果当前 platform-tools/实现验证表明 `sh -s` 的 exit status 或 stderr 分离不可靠，可采用经过测试的 shell-v2 等价实现，但必须满足：

- 不经 host shell；
- command 不参与本机命令拼接；
- command 只由 Android `/system/bin/sh` 解释；
- 能获得可靠 exit code；
- 能区分 stdout/stderr（若底层客观无法完全区分，必须在结果里明确标记，不得伪造）；
- timeout 后可终止对应 adb process；
- companion 退出时清理子进程。

不要使用：

```text
cmd.exe /c "adb shell <LLM command>"
powershell -Command ...
Runtime.exec(String)
```

这种会增加 host command injection 风险的实现。

## 9.7 Companion CLI

至少：

```text
mar-bridge doctor
mar-bridge devices
mar-bridge pair --serial <serial>
mar-bridge run --serial <serial>
mar-bridge status --serial <serial>
mar-bridge forget --serial <serial>
```

`run`：

- 持续监控 USB/adb；
- 自动重建 reverse；
- 自动重建 App session；
- 不清 desktop trust；
- 不自动切换其他设备；
- 不自动启动无线 adb。

---

# 10. Provider 选择：永不自动回退

持久化：

```text
selectedElevatedAuthority:
  SHIZUKU | WIRED_ADB
```

同一时刻所有 elevated typed operations 与 dangerous shell 使用用户当前明确选择的 provider。

若：

```text
selected = SHIZUKU
Shizuku unavailable
Wired ADB ready
```

仍然返回：

```text
AUTHORITY_TEMPORARILY_UNAVAILABLE
```

不得静默走 ADB。

反之亦然。

用户可手动切换 provider。

Dangerous Mode 不改变这一原则。

---

# 11. Shell 并发、生命周期与资源限制

第一版做 **one-shot shell_exec**，不做完整 PTY。

至少：

- 单 Agent 默认最多 1 个并发 shell；
- 全局并发设置合理硬上限；
- 默认 timeout，例如 30s；
- 用户/Agent 可申请更长，但 clamp 到配置上限；
- stdout/stderr 分别限制，例如默认各 1 MiB；
- 超限时停止继续缓冲并标记 truncated；
- 必须避免把无限 `logcat` 等命令一直挂住；
- timeout 后尽力终止 shell/adb child；
- App/Companion 崩溃后，下次启动不应把旧 process 视作仍可控 session；
- 不承诺后台 daemon 的生命周期管理。

如果命令主动执行：

```text
... &
nohup ...
```

Runtime 可以报告命令已返回，但不应声称能可靠追踪该后台进程。

为未来保留：

```text
shell_session_create
shell_session_write
shell_session_read
shell_session_resize
shell_session_close
```

接口设计空间，但**本轮不要实现 PTY**。

---

# 12. Runtime 控制面的硬边界

Dangerous Mode 是高风险自由 shell，但仍需保护 Runtime 自己的权限控制面。

设计原则：

> Agent 可以获得 Android shell UID 的真实能力，但不能因为 Runtime 自己错误地把秘密和控制面暴露给 shell 而轻易永久绕过用户授权模型。

至少保证：

- Desktop shared secret 不放 `/sdcard`；
- pairing token 不落普通文件；
- Android Keystore key material 不导出；
- Dangerous Mode flag 存在 App 私有受控存储；
- approval/trust 数据不复制到 public workspace；
- IPC endpoint 有认证；
- `adb reverse` loopback bridge 仍做 App-level session auth；
- shell 工具不能伪造 ToolExecutor 内部 callId；
- shell 调用结束后，Runtime 仍在 tool execution 层记录真实 audit；
- 不允许 Agent 通过 tool 参数指定其他 desktop id / adb serial / bridge endpoint。

不要试图声称这是“shell sandbox”。shell UID 能访问什么由 Android 系统决定；这里只是避免 Runtime 自己额外泄露控制凭据。

---

# 13. Approval / Grant 模型

支持：

```text
ONCE
TASK
SESSION
PERSISTENT
```

Typed capability grant 至少包含：

```text
agent_id
skill_id?
capability
workspace_id?
path_scope?
lifetime
policy_version
created_at
expires_at?
```

Dangerous Mode 不等于所有 Agent 自动允许 shell。

建议增加：

```text
agentDangerousShellAllowed
skillDangerousShellAllowed
```

或统一到 capability：

```text
shell.execute
```

必须同时满足：

```text
global dangerous mode enabled
AND effective capability contains shell.execute
```

才能暴露 tool。

## 13.1 高危命令确认

在 `ENABLED_CONFIRM_HIGH_RISK`：

- 风险检测发生在 command 已生成、执行前；
- 显示完整或可滚动 command 给用户；
- 显示当前 authority；
- 显示 cwd；
- 用户允许后执行**原命令**；
- approval 之后 revalidate dangerous mode / selected authority / agent identity / config snapshot；
- 若命令在等待审批期间发生变化，旧审批失效。

在 `ENABLED_AUTONOMOUS`：

- 不逐条确认；
- 仍做执行前 revalidation 与 audit。

---

# 14. 审计

所有 tool，包括 shell，都必须审计。

Typed tool：

- timestamp；
- request id；
- agent / skill；
- capability；
- workspace；
- relative path 的会话盐化摘要或范围桶（不得写原文）；
- authority；
- approval；
- result；
- duration。

Shell：

至少记录：

```text
timestamp
request_id
agent_id
skill_id?
authority
dangerous_mode_policy
cwd_sha256
command_sha256
command_preview（本实现固定关闭，不写审计或诊断）
exit_code
timeout/cancel/truncated
stdout_bytes
stderr_bytes
duration
approval_id?
```

不得把 shell command、cwd、stdout/stderr 原文写入审计或诊断日志；只记录摘要、长度、终态与离散错误码。

对于 command 本身：

- 为可追责性保留 hash；
- UI 的最近执行记录可选择显示短 preview；
- 不把超长完整命令永久写普通日志；
- 避免日志里意外持久化 API key/token；
- 如果现有安全审计设计允许加密的本地详细 history，可作为后续功能，不是本轮必需。

Desktop Companion 也要记录对应 request id，以便 Android/PC 侧关联。

---

# 15. 统一错误模型

至少：

```text
CAPABILITY_DENIED
APPROVAL_REQUIRED
APPROVAL_DENIED

WORKSPACE_NOT_FOUND
WORKSPACE_READ_ONLY
PATH_OUT_OF_SCOPE
SYMLINK_FORBIDDEN
ROOT_OPERATION_FORBIDDEN
FILE_TOO_LARGE
QUOTA_EXCEEDED
CONFLICT

AUTHORITY_NOT_GRANTED
AUTHORITY_PROVIDER_NOT_SELECTED
AUTHORITY_TEMPORARILY_UNAVAILABLE

SHIZUKU_PERMISSION_DENIED
SHIZUKU_SERVICE_UNAVAILABLE

BRIDGE_NOT_PAIRED
BRIDGE_DISCONNECTED
BRIDGE_PROTOCOL_MISMATCH

ADB_DEVICE_UNAUTHORIZED
ADB_DEVICE_OFFLINE
ADB_DEVICE_DISCONNECTED
ADB_APP_NOT_INSTALLED

DANGEROUS_MODE_DISABLED
SHELL_CAPABILITY_DENIED
SHELL_HIGH_RISK_APPROVAL_REQUIRED
SHELL_EXECUTION_FAILED
SHELL_TIMED_OUT
SHELL_CANCELLED
SHELL_OUTPUT_TRUNCATED

TIMEOUT
IO_ERROR
INTERNAL_ERROR
```

统一返回：

```json
{
  "success": false,
  "error": "DANGEROUS_MODE_DISABLED",
  "retryable": false,
  "user_action": "Enable Dangerous Mode in advanced permissions",
  "details": {}
}
```

不要把底层 stack trace 直接暴露给模型。

---

# 16. Tool Snapshot 与审批后二次校验

当前项目已经存在的：

- tool snapshot；
- `callId` 绑定；
- 审批后重新检查 Agent / 配置；
- Shizuku permission revalidation；

必须保留并扩展到 shell。

Shell approval 必须绑定至少：

```text
callId
agentId
skillId?
commandHash
cwd
selectedAuthority
dangerousModePolicy
toolSchemaVersion
policyVersion
configSnapshotHash
```

用户批准后，如果：

```text
command changed
authority changed
dangerous mode disabled
agent changed
skill changed
policy version changed
```

必须废弃旧 approval，不能执行。

AUTONOMOUS 模式仍需做同样的调用前 snapshot validation，只是不需要等待用户 confirmation。

---

# 17. UI 信息架构

主权限页不要用底层实现名淹没普通用户。

建议：

```text
Agent 能力

基础工作区          已启用
用户授权文件        已配置/未配置
系统增强            已启用/未启用
危险模式            已关闭/高危确认/完全自主
```

系统增强详情：

```text
Shizuku
  用户授权
  当前可用性
  Connection

有线 ADB
  已配对电脑
  USB/ADB 状态
  Bridge 状态
  serial

当前系统增强通道
  Shizuku / 有线 ADB
```

危险模式详情：

```text
关闭

开启：高危命令确认
  Agent 可直接执行 shell
  明显高危操作会要求确认

开启：完全自主
  Agent 可自主执行 shell，不逐条询问
```

Dangerous Mode UI 必须明确：

- 非 Root；
- 可能改文件/设置/应用状态；
- 权限不会因任务结束自动关闭；
- 用户可随时手动关闭；
- 当前 Authority 临时掉线时只显示“Shell 暂不可用”，不是“危险模式已关闭”。

---

# 18. 数据迁移

- v1/现有 Shizuku 原型必须增量迁入；
- 不删除固定 `MobileAgentRuntime-Shizuku` 中已有用户数据；
- 旧 workspace 建立中性 ID 映射；
- schema migration 不得 destructive；
- 持久数据增加版本；
- 旧 authority boolean 迁移后必须重新探测真实状态；
- v1 如果已有“禁止任意 shell”的 policy/ADR，需要更新文档并明确它已被 v2 Dangerous Mode 设计取代；
- 不要保留两个互相冲突的真相来源。

---

# 19. 自动化测试

必须尽可能补齐以下测试。

## 19.1 Tool exposure

- Dangerous Mode OFF -> `shell_exec` 不暴露；
- Dangerous Mode ON 但 agent 无 `shell.execute` -> 不暴露；
- Dangerous Mode ON + capability + provider configured -> 暴露；
- tool schema 不泄露 adb serial、Binder、desktop endpoint；
- provider 切换后 tool schema 不变化。

## 19.2 Dangerous Mode persistence

验证以下事件不会把 Dangerous Mode 改 OFF：

- task end；
- conversation/session end；
- app background；
- activity recreate；
- binder death；
- USB disconnect；
- bridge disconnect；
- app restart。

用户 explicit disable 必须关闭。

## 19.3 Authority lifecycle

- Shizuku binder death -> unavailable，不 revoke；
- Binder restore -> 自动 rebind；
- USB disconnect -> disconnected，不 forget desktop；
- USB reconnect -> 自动 restore bridge；
- unauthorized -> reauth required，不 delete trust；
- provider 不自动 fallback。

## 19.4 Shell execution

对可本地模拟的 executor contract 测：

```text
echo
exit non-zero
stdout
stderr
large output
timeout
cancel
cwd
pipeline
redirection
&&
||
command with quotes/newlines/unicode
```

Wired ADB backend 重点测试：

- 模型 command 不经过 host shell；
- command 中包含 `& | > < ^ % ! " '` 等 Windows 特殊字符时不会在 PC 端解释；
- serial 不可由模型覆盖；
- adb path 不可由模型覆盖；
- timeout 能终止 host adb child；
- bridge auth 失败时 shell 不执行。

## 19.5 Approval binding

- command hash 改变后旧 approval 无效；
- selectedAuthority 改变后旧 approval 无效；
- dangerous mode policy 改变后旧 approval 无效；
- agent/skill/config snapshot 改变后旧 approval 无效；
- autonomous 模式仍做 revalidation。

## 19.6 Workspace / Memory

继续保留：

- traversal；
- absolute path；
- symlink；
- quota；
- atomic write；
- delete non-empty dir rejected；
- root delete rejected；
- SAF persisted grant；
- Skill memory isolation。

---

# 20. 真机 / E2E 验收

自动化测试通过不代表 Shizuku/ADB 链路验收完成。

## 20.1 Shizuku E2E

只有同时满足才标 `PASS`：

1. 官方 Shizuku service 运行；
2. App permission 已 granted；
3. UserService 真正 bind；
4. typed file operation 成功；
5. Dangerous Mode 开启后 `shell_exec("id")` 成功；
6. 返回 UID/环境符合 Shizuku shell 预期；
7. Binder 短暂断开后状态正确变 unavailable；
8. 恢复后自动 rebind；
9. Dangerous Mode 仍保持开启；
10. 不需要重新 grant Runtime 自己的 Dangerous Mode。

如果缺真机条件，标：

```text
IMPLEMENTED / AUTOMATED TESTED / E2E BLOCKED
```

不得谎报 PASS。

## 20.2 Wired ADB E2E

需要真实 USB 设备时验证：

1. Companion `doctor`；
2. 指定 serial；
3. Android 已授权该 PC；
4. App-level pair；
5. adb reverse；
6. encrypted bridge session；
7. typed file request；
8. dangerous shell `id`；
9. pipeline，例如 `getprop | head` 或设备可用等价命令；
10. USB 拔出；
11. persistent trust 仍在；
12. Dangerous Mode 仍开启；
13. USB 重插；
14. 自动重建 reverse/session；
15. shell 再次可执行；
16. 不重新 App-level pairing（除非真实凭据失效）；
17. 不自动切 provider。

如果环境没有 USB 真机，完整报告阻断项。

---

# 21. 文档更新

至少更新/新增：

- `docs/plans/multi-authority-controlled-execution.md`
- `docs/plans/shizuku-controlled-execution.md`
- `docs/adr/0004-capability-authority-bridge.md`

建议新增：

```text
docs/adr/0005-dangerous-shell-mode.md
docs/plans/wired-adb-desktop-bridge.md
```

ADR 必须明确：

1. 为什么默认使用 typed tools；
2. 为什么产品仍需要 arbitrary shell；
3. Dangerous Mode 是用户显式 escape hatch；
4. 为什么不做 Root；
5. 为什么不做 Wireless ADB；
6. 为什么 Shizuku 与 Wired ADB 并列；
7. 为什么禁止自动 fallback；
8. 为什么 Authority grant 与 Connection 分离；
9. 为什么 Dangerous Mode 持久；
10. 为什么第一版 one-shot shell 而非 PTY；
11. 为什么 Wired ADB command 只能在 device shell 解释，不能经过 Windows shell；
12. Dangerous Mode 不是安全沙箱，风险由用户明确承担。

README/用户文档补充危险模式风险说明，不要营销成“安全的完全控制”。

---

# 22. 建议代码抽象

不要机械照抄名字，但最终职责应能映射到：

```text
ToolRegistry
EffectiveCapabilityResolver
PolicyEngine
ApprovalEngine
AuditLogger

WorkspaceRegistry
WorkspaceBackend
InternalWorkspaceBackend
SafWorkspaceBackend
PrivilegedWorkspaceBackend

AuthorityManager
ShizukuAuthority
WiredAdbAuthority

DangerousModeManager
ShellExecutor
ShizukuShellExecutor
WiredAdbShellExecutor

BridgeProtocol
DesktopTrustStore
BridgeSessionManager
AdbProcessManager
```

关键接口可以类似：

```kotlin
interface ShellExecutor {
    suspend fun execute(request: ShellExecRequest): ShellExecResult
    suspend fun cancel(callId: String): Boolean
}
```

以及：

```kotlin
data class ShellExecRequest(
    val callId: String,
    val command: String,
    val cwd: String?,
    val timeoutMs: Long,
    val maxOutputBytes: Long
)
```

Backend 选择只能来自 Runtime `selectedElevatedAuthority`，不得来自模型输入。

---

# 23. 实施顺序

请按以下顺序执行，避免继续横向堆功能：

1. 审计现有代码与未提交 diff；
2. 固化统一 Capability / ToolRegistry / Authority 模型；
3. 固化长期 grant 与临时 connection 状态；
4. 将已有 Sandbox/Shizuku 文件工具迁到统一接口；
5. SAF Workspace；
6. Skill memory 一等能力；
7. Shizuku 真正 E2E 修正；
8. Wired ADB Companion typed bridge；
9. DangerousModeManager 与持久状态；
10. `shell_exec` 公共 tool schema；
11. ShizukuShellExecutor；
12. WiredAdbShellExecutor；
13. 高危确认/完全自主两档；
14. shell audit / timeout / output limit / cancel；
15. UI；
16. migration；
17. 自动化测试；
18. 能做则真机 E2E；
19. 文档；
20. 最终 diff 自审。

不要先做 PTY、Root、Wireless ADB、Accessibility 等旁支。

---

# 24. 完成定义（Definition of Done）

本任务只有在以下条件达到后才能宣称对应部分完成：

## Core

- Agent tool 与 backend 解耦；
- Sandbox/SAF/Shizuku/Wired ADB 架构统一；
- Tool/Capability/Authority/Connection 概念无混淆；
- 不自动 fallback；
- 无 destructive migration。

## Dangerous Mode

- 用户可显式开启/关闭；
- 两档策略可用；
- 状态持久；
- task/session/background/disconnect 不自动关闭；
- OFF 时 shell tool 不暴露；
- ON 时只对拥有 `shell.execute` 的 Agent/Skill 暴露；
- Shizuku 与 Wired ADB 共用同一个 Agent-facing `shell_exec`；
- arbitrary shell 语法真实可用；
- 不经 host shell；
- timeout/output/cancel/audit 可用；
- 旧 approval 不可重放到变化后的 command/config。

## Security correctness

- 默认模式仍然最小权限；
- Typed tools 原有路径约束不降低；
- Shell 不得到 Runtime 的 secret；
- Bridge 仍有 App-level auth；
- 用户 grant 与 platform availability 分离；
- 拔线/掉 Binder 不主动 revoke；
- 没有 Root；
- 没有 Wireless ADB；
- 没有隐藏的 `cmd.exe /c` LLM command path。

## Tests

- 单元/集成测试通过；
- 能跑的 Gradle/JVM/Desktop 测试全部跑；
- 真机不可用时明确 E2E BLOCKED；
- 不能把“代码已接线”写成“真机已验收”。

---

# 25. 最终汇报格式

结束时只做一次完整汇报，不要边做边反复让用户决策。

必须包含：

```text
IMPLEMENTATION STATUS
PASS / PARTIAL / BLOCKED
```

然后报告：

1. **实际修改**
   - 文件/模块；
   - 核心架构变化；
   - Dangerous Mode；
   - shell backend；
   - SAF；
   - Shizuku；
   - Wired ADB Companion；
   - Skill memory。

2. **测试**
   - 运行的命令；
   - 通过/失败数量；
   - 失败原因；
   - 真机链路状态。

3. **权限生命周期验证**
   - Binder death；
   - USB disconnect；
   - restart；
   - Dangerous Mode persistence；
   - trust persistence。

4. **安全验证**
   - 默认无 shell；
   - shell command 未经过 Windows host shell；
   - provider 不自动 fallback；
   - secret 未暴露；
   - approval hash/revalidation；
   - typed path protection 未回退。

5. **仍未实现**
   必须明确列出：
   - Root；
   - Wireless ADB；
   - PTY；
   - 通用 runtime/toolchain；
   - Device Admin/DPC；
   - Accessibility/屏幕/输入自动化；
   - 以及任何实际阻断项。

6. **工作区状态**
   - `git status --short`
   - 当前 HEAD
   - 明确写明：`NO COMMIT / NO PUSH / NO DEPLOY`

如果发现本规范与当前仓库现实存在局部冲突，优先满足以下顺序：

```text
不丢用户现有工作
> 权限与凭据安全
> 不谎报验收
> 本 v2 架构目标
> 内部类名/目录建议
```

不要因为细节命名不同而停工。

---

# 最终产品原则

请把以下原则视为本次实现的不可破坏契约：

> 默认模式下，Agent 使用最小权限、类型化工具。

> 用户显式开启 Dangerous Mode 后，Agent 可以获得真正的 Android shell escape hatch，以支持接近 Codex 的开放式自主工作流。

> Dangerous Mode 是高风险能力，不是假装安全的 shell allowlist。

> Shizuku 与 Wired ADB 是并列 Authority，永不自动回退。

> Authority grant、Dangerous Mode 用户选择、Agent capability grant 都应尽可能长期保持；临时掉线只影响 Availability/Connection，不主动降权。

> Root 与 Wireless ADB 不在当前产品路线。

> Shell 可以操作 Android shell UID 能做到的事情，但 Runtime 自己的认证、密钥、审批和 trust 控制面不能因实现失误被主动暴露给 Shell。

> 用户明确选择完全自主模式后，不要用逐命令确认把 Agent 工作流重新退化成手动遥控器。
