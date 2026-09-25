<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0006：稳定会话侧栏与统一工作区访问

状态：已修订（2026-09-02）。2026-09-01 的 Agent 级单工作区实现与证据仍作为历史事实保留；本次修订以用户指定的 Workspace/UI/Provider Prompt 覆盖其生命周期语义，改为 Agent 多 Grant + Thread 固定 binding。物理 USB Companion、OEM provider 与非模拟器断连恢复继续作为 `E2E_BLOCKED` 边界，不以模拟器证据替代。

## 背景

当前产品把会话历史、Agent 编辑、SAF 文件夹授权、系统增强通道和工作区 Grant 分散在不同页面。系统取得 SAF 持久权限不等于当前 Agent 已取得工具权限；旧实现还曾把不同 SAF 选择复用为同一个工作区。Shizuku 与有线 ADB 使用固定根工作区，用户不能像桌面 Agent 产品那样从当前会话快速添加工作目录。

同时必须保留以下安全事实：

- SAF 是用户选择的普通工作区，不是 elevated Authority；
- Shizuku 和 Wired ADB 是两个独立 Authority，不自动互相回退；
- ADB shell UID 不等于 Root，仍受 Android 和 SELinux 限制；
- 一次 Run 的工具 schema 和授权必须冻结，新增授权不能在运行中扩权；
- 撤权、过期、策略变化和 Authority 失效仍须在真正派发前重新校验。

## 决策

### 1. 稳定会话侧栏

应用外壳提供唯一的全局 Agent → Thread 侧栏投影。手机使用抽屉，宽屏使用永久侧栏；手机不再同时保留底部一级导航，Chat 也不再建立第二个私有 Drawer。流式任务由稳定的应用/父图生命周期对象持有，不依赖某个目的地的 `NavBackStackEntry`。

侧栏支持：

- 在 Agent 下查看和切换多个 Session；
- 选择 Agent 后新建会话；
- 从 Agent 侧栏入口进入该 Agent 的完整设置；新 Thread 可在创建 sheet 中选择 Agent 已获 Grant 的 workspace；Conversation 只显示本 Thread 的工作区摘要；
- 进入完整 Agent 设置，而不在侧栏复制 Agent 编辑逻辑。

侧栏安全投影不得包含 snapshot ID、SAF URI、真实根路径、ADB serial、Binder/session 或 secret。

### 2. 独立 WorkspaceAccess 深模块

Agent 设置页面只依赖 provider-neutral 的 `WorkspaceAccessPort`；Chat 和 Session 只读取安全摘要。该端口统一处理以下入口：

1. **选择手机文件夹**：系统 `ACTION_OPEN_DOCUMENT_TREE`；
2. **浏览设备目录**：通过当前明确选定且已就绪的 Shizuku 或 Wired ADB typed backend；
3. **完整设备文件（ADB 可见范围）**：显式高风险、持久、可撤销的 provider-neutral 工作区。

每个 SAF tree 具有独立 opaque workspace ID。兼容旧 `saf-tree`，但新选择不得覆盖其他 Agent 正在使用的工作区。一个 Agent 可以持有多个 workspace Grant，并可设置一个仅供新 Thread 预选的默认 workspace；每个 Thread 创建时持久绑定一个 workspace。Agent 设置或 picker 流程一次完成持久权限、backend 注册、Agent Grant 以及可选的默认/Thread binding；单独取得系统权限时，界面必须明确显示“尚未授予当前 Agent”。

目录浏览器返回 opaque handle 和安全显示名。真实绝对路径只存在于 Android Authority adapter 内部，模型只获得 workspace ID 与规范化相对路径。系统 SAF picker 返回的 URI 不得冒充 ADB 路径选择结果。

### 3. ADB 级系统访问的用户模型

设置页把 Shizuku 和有线 ADB展示为“ADB 级系统访问”的两种连接方式，便于理解；领域模型继续使用 `Authority.SHIZUKU` 与 `Authority.WIRED_ADB`。用户明确选择的通道不可用时必须 fail closed，不得切换到另一通道。

一个 Shizuku/Wired ADB 连接可以承载多个由用户选择的 privileged workspace；连接方式不决定工作区，工作区也不得反向改变 selected Authority。目录选择后，运行期 handle 与 durable locator 严格分离：locator 立即以 Android Keystore AEAD 加密并持久化，恢复时经同一 Authority 重新验证后产生新 handle。暂时断联只进入 unavailable/reattaching，不撤销 Grant 或自动选择另一 Authority。

### 4. 完整设备文件

“完整设备文件”表示所选 ADB Authority 在当前设备上实际可达的文件范围，不表示 Root，也不承诺可以访问全部 `/data`。它：

- 使用独立的高风险 scope 和明确确认；
- 持久保存并可随时撤销；
- 不因 Binder、USB、Wi-Fi 或桌面 Companion 暂时断联而撤销；断联只使当次派发不可用，连接恢复后继续使用原授权；
- 只有用户显式撤销、底层平台权限明确失效，或受信身份/协议绑定明确失败时才失效或要求重新授权；
- 不自动包含 `shell.execute`；
- 始终受 capability、workspace scope、selected Authority、危险模式 admission、UID/SELinux 与结果预算约束；
- 不支持或被拒绝时返回稳定错误码；mutation 结果无法证明时返回 `UNKNOWN_OUTCOME`，不得自动重放。

### 5. 权限热更新

历史 Agent snapshot、snapshot Grant binding 与 Thread workspace binding 保持不可变。每次新 Run 从当前 canonical Agent Grant 与该 Thread 的 workspace binding 构造一份 run-local binding manifest：

- Agent 设置中增加或撤销 Grant 后，下一 Run 重新复核；默认 workspace 的变化只影响之后创建的 Thread；
- 正在运行的 Run 不获得新增权限；
- 同一 Agent 的不同 Thread 可以绑定不同的已授权 workspace；普通工具仅能访问该 Thread 的 binding，完整设备文件仍需独立高风险 Grant；
- 撤权、过期、policy revision、path scope 与 selected Authority 在派发前继续实时复核。

2026-09-25 补充：完整设备文件的 Agent Grant 独立于 Thread 所选目录，每次新 Run 可在当前危险模式、策略版本和选定 Authority 有效时将其纳入冻结视图；它仍不得成为 Agent/Thread 默认目录，也不自动授予 Shell。危险模式变更使旧版本 Grant 失效，界面应提示重新授权；再次明确确认完整设备文件时，可只按该 Agent 已选普通默认目录现存、未撤销、未过期的能力集重新确认，避免新会话绑定无效授权且不得恢复被撤销的能力。完整设备授权与默认目录续期各自提交，失败状态须分别显示。

run-local binding 只是冻结视图，不建立第二套权限事实；`CapabilityGrantRepository` 仍为 canonical truth。

### 6. 设计语言

采用以内容和操作层级为中心的移动 Agent 设计语言：浅色为首次启动默认，`66ccff` 保留为可选彩蛋主题；统一排版、间距、形状、卡片、列表行、图标按钮、sheet、风险状态和空状态。设计可借鉴主流聊天产品的信息架构，但不得复制第三方代码、素材、品牌或专有视觉。

## 不采用的方案

- 不让 Settings、Agent、Session 和 Chat 各自直接写 Repository；这会制造多份权限真相。
- 不把所有能力塞进一个 Conversation Hub 巨型 ViewModel；侧栏投影与 WorkspaceAccess 分离。
- 不把 privileged workspace 固定为一个 Download 子目录，也不把固定根直接改成 `/`。
- 不把系统文件管理器返回值当作 ADB 绝对路径。
- 不用自动 fallback、`continue-on-error` 或逐次隐藏批准来掩盖授权状态不一致。

## 验收边界

- 两个 SAF 文件夹可同时存在并授予同一或不同 Agent；单个 Agent 可持有多个 Grant 和一个新 Thread 默认值；
- Thread A/B 可以绑定不同 workspace；改变 Agent 默认值不改写既有 Thread，当前 Run 不扩权；
- Shizuku 与 Wired ADB 各自可选择多个目录，且无自动 fallback；
- 完整设备文件未确认时零 dispatch，确认后仍不出现 Root 或隐式 Shell；
- 手机抽屉、宽屏侧栏、小屏、大字体、返回行为和流式切换均有设备/Compose 证据；
- 模型 schema、普通 UI 和诊断 ZIP 中无 URI、绝对根路径、serial、命令、secret；
- 真实 SAF E2E 与可用的 Shizuku/Wired ADB E2E 分开记录，缺失物理条件时标 `E2E_BLOCKED`。

## 许可与参考边界

第一方实现和本文保持 `AGPL-3.0-only`。RikkaHub、ChatGPT、Codex Desktop 等只作为交互和信息架构参考；不得复制品牌、素材或未审计的实现代码。
