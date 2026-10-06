<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0029：USB 激活的设备常驻 ADB 服务

- 状态：采纳；源码与 API34 模拟器专项验证通过，实体设备边界另列
- 日期：2026-10-06
- 依据：用户要求断开电脑连接也不主动降权，并明确选择软件自己的常驻服务，不依赖 Shizuku。

## 背景

原 WIRED_ADB 经电脑 Companion 和 adb reverse 执行。保存信任可以保留用户授权，但无法在 USB 断开后继续执行。旧手机指引还遗漏 USB 调试准备、真实命令参数和发行工具，并让用户等待电脑配对成功后才发起手机握手。旧 pair 成功后退出，不能获得活跃连接。

## 决定

正式 Android 的 WIRED_ADB 改为由官方 USB adb 启动设备内的 shell UID 2000 常驻服务。电脑只负责首次激活；类型化文件和明确授权的危险 shell 在设备端执行。Shizuku 保持独立的可选通道，不作为自动 fallback。旧 Companion 协议及注入测试保留为历史兼容路径，不把它描述为可离线执行的正式激活方式。

设备服务通过固定 APK app_process 入口启动。一次性激活令牌只从前台设置操作产生，电脑只通过 adb stdin 传递有界 bootstrap，不将秘密放入 argv、脚本文本、环境变量、日志或临时文件。启动进程的退出不代表激活成功，必须验证手机端已认证连接。

Binder publication provider 只允许持有跨用户权限的 shell UID 2000 调用，并验证一次性令牌、时限和代际。设备端执行器在握手前固定受信 App UID；每次操作校验 caller、服务 UID、协议和 session，拒绝 Root、其他 App 和旧 session。App 重启的重新连接使用 Keystore 包装的连续性凭据和新鲜 challenge，不能重新接受旧令牌。设备服务中的秘密只在内存保留。

独立 shell 进程按系统 shell 的 external-provider 接口获取 Provider，固定 authority、用户和 `com.android.shell` attribution，每次调用后释放引用。API 26—35 只接受对应的精确接口形状，不兼容的接口拒绝执行；API 26—28 不支持缺少显式用户释放接口的次级用户。

实测 Android 会拒绝普通 App 连接 shell 的 Unix socket。固定 abstract socket 因而只用于占用服务单例名称。常驻服务观察固定 App UID 和主进程名的 PID 变化，只有确认 App 进程启动后才在有界窗口重新发布 Binder，执行前再次核对 PID。未知或缺失进程不触发周期 Provider 调用；不依赖 USB、TCP、LAN 或宿主驻留程序。

撤销优先使用已认证控制 Binder；冷启动时等待服务重新认证，再请求退出并确认实际 Binder 死亡，最后清除凭据。未知连接状态或撤销失败保留恢复凭据。新激活先保留旧凭据，只有新服务完成身份认证才提交替换；取消、关闭意图和重新生成令牌会清除未提交激活与挑战。

目录恢复沿用类型化设备文件服务。持久目录定位符由 App 加密保存，服务代际变化只丢弃临时句柄，再按原目录定位符和授权重新打开。旧桌面桥的电脑端标识无法转换成设备路径，升级后须重新选择这类目录；不能猜测路径、扩大范围或删除 Agent 授权。

USB 拔插、电脑退出、App 界面关闭和普通客户端释放不销毁设备服务、不删除授权记录、不关闭危险模式。实际服务死亡、UID/协议/身份校验失败时阻断执行并反馈失效；只有用户明确撤销激活才停止该服务并清除其信任。系统重启和系统/OEM 终止服务后需要重新激活，不声称能对抗系统终止。

## 验证与边界

验收 S35—S37：显式 USB 设备选择及官方 adb 校验；令牌/UID/challenge/session 的负向测试；真实 shell UID 启动、设备内文件和 shell 调用；电脑进程退出与 App 重建后的连接；服务死亡与明确撤销；同一选定通道无 fallback、未知操作不重放。新增安全边界已经独立源码复查与非 debug 智能体工具流程验证。共享持久 revision 的便利设置入口每次取得最新状态，只进行一次严格 CAS；显式版本更新仍拒绝过期与并发修改，不循环覆盖。

Windows 官方 adb doctor 已实测通过。物理 USB、OEM 保活和用户真机验收仍须分列；模拟器关闭 reverse/退出电脑进程不能冒称实体设备拔线测试。完整结果见 [本轮证据](../evidence/2026-10-06/resident-adb-activation.md)。
