<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# v1.1.2 USB 快速激活与常驻 ADB

范围：R21/R27/R30/R31/R38，S35—S37，ADR-0029。源码修复与设备专项验证已通过；提交、远端合并和正式发布在各自完成后单独记录。

## 已确认的问题

旧设置指引省略 USB 调试和 RSA 授权、必需的 --adb 参数，发行脚本实际名 bridge 与提示 mar-bridge 不一致。电脑等待手机握手，而手机提示先等待电脑成功，形成操作互等。pair 成功即退出并移除 reverse；手机配对只提交信任，不等于 active 认证连接。独立 DSH Flash 只读审查核对了这四处断裂。

本机使用既有电脑工具 doctor 检查官方 Platform-Tools：Windows 签名验证通过，adb version 返回 Android Debug Bridge 1.0.41。没有降低签名、文件身份或哈希校验。原始电脑诊断与构建日志保留于受忽略的 .private/kb-retrieval-112-20261006/。

## 用户要求与实现方向

用户明确要求软件自己的设备常驻服务，不依赖 Shizuku。USB 仅用于启动 shell UID 2000 的设备服务；手机认证后可退出电脑工具和拔线。保留当前权限选择、用户意图、Agent 授权及危险模式，实际服务失效时 fail-closed；不创建 Root、无线或宿主 shell 能力。

## 验证状态

独立 shell 进程的普通 ContentResolver 获取方式在实测中被系统拒绝，改为固定 authority 的 external-provider 调用并逐次释放引用。另一次实测确认 App → shell Unix socket 返回权限拒绝；已改为设备服务识别本 App 主进程 PID 的启动变化后重新认证，socket 只保留单例约束。API 26—35 接口形状检查不能代替对应系统的实际调用验收。

本任务专用 API34/x86_64 模拟器没有 Shizuku 安装和 adb reverse。以下七个场景均实际启动了 shell UID 2000 的独立进程；instrumentation 返回 OK，未跳过。每次跨进程场景均由宿主停止本 App，确认 App 未被服务重新拉起且同一设备服务 PID 仍存活，再运行新 instrumentation。

| 场景 | 已验证结果 |
| --- | --- |
| 首次激活、类型化文件、shell、普通客户端关闭与重建 | UID2000、文件创建/读写/删除、断开后保持 READY、重新认证、热撤销均通过 |
| 激活后 App 进程死亡，再打开 | 无新令牌和电脑参与，重新挑战认证，原文件可读，随后撤销通过 |
| App 重建后未先连接直接撤销 | 恢复认证控制、停止旧进程、清凭据，随后新激活通过 |
| 宿主核验并结束唯一的自身 shell 服务 | 原用户意图/配置保持、文件与 shell 零执行、无自动回退，明确撤销后重新激活通过 |

令牌过期/耗尽/单次使用、取消/意图关闭/新令牌使旧挑战失效、普通 App/错误首次 caller UID、Provider 精确接口和进程选择、真实 socket 不存在与其他错误保持未知，共 10 个 Android 负向用例通过。原始日志、进程状态和 `resident-device-lifecycle.json` 保留在忽略目录，未把令牌或认证秘密写入日志。

非 debug Review 包中，canonical 智能体流程实际通过 1 项：明确选用 WIRED_ADB、首次开启危险模式、真实 opaque 目录选择与绑定、9 项持久能力授权、Snapshot/factory 工具曝光、UID2000 shell、文件读写删除，以及关闭、重开和撤销后拒绝旧执行器。目录代际恢复另有 1 项实际通过：新服务代际使旧句柄失效，加密定位符重新打开原目录并保留文件。

本轮实测发现 Authority 与危险模式共享同一持久 revision，却使用各自缓存 revision，导致首次开启危险模式和关闭 Authority 被严格 CAS 拒绝。便利入口现读取最新持久状态后只进行一次严格 CAS，保留其他设置与实时连接；显式 expectedRevision 接口仍拒绝过期版本，真实并发更新不会被重试覆盖。两个 JVM 测试类共 6 项通过。撤销也区分“服务停止失败”和“已停止但权限配置保存失败”，不返回假成功。

最终权限、设置及既有适配器设备回归 36/36 通过，无跳过。独立 `gpt-6.1-sol/high` 源码复审结论为 APPROVE，范围包含驻留服务、安全协议、权限状态、桌面激活和迁移；复审不包含发布工作或实体设备验收。完整干净公开工作树门禁通过：strict/offline `check reviewGate licenseGuard licenseGuardReverse verifyCiPins verifyWorkflowYaml :desktop:bridge:test :desktop:bridge:distZip`；JUnit 按各构建变体计 2119 次执行，0 failure/error/skip；REUSE 926/926 合规，既有 CodeGraph 同步完成。旧知识库与归档验收见 [另一份证据](kb-and-conversation-archive.md)。

物理 USB 拔线、arm64 设备升级、API26—33/35、次级用户、OEM 保活与长期功耗未实测；模拟器证据不代替这些边界。系统重启或系统结束 shell 服务后需要重新激活。
