<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0025：正式版本检查与经验证的 Android 更新

日期：2026-10-06。状态：已实施，源码审阅和本地验证通过。依据：用户要求 v1.0.2 修复实际更新、每日首开检查、一键下载后安装；R36、UP01—UP06、N06。

现有按钮只筛选签名更新公告，无法得知是否有新版 APK，也没有下载/安装路径。公告内容和模型输出不能获得代码执行授权。

决定：固定官方 GitHub latest stable release 为版本来源，按整数版本比较；每天首开查询，成功日期和候选持久化，手动强制检查。用户点击才下载，匿名 HTTPS 白名单与大小/哈希限制、包名/版本/当前证书/系统/ABI 校验后进入显式系统安装界面。私有目录 FileProvider 只读共享，首次系统未知来源授权后继续，最终系统确认不可绕过。公告 `app://update` 仅触发同一独立检查，不能指定下载地址或自动安装。

2026-10-07 修订（用户要求，v1.1.4preview）：将上述每日成功检查节流改为一小时，成功时间戳及合法候选持久化，前台恢复/持续前台到期检查，后台取消定时循环，不引入常驻服务。旧仅日期记录、缺缓存和未来时间戳不阻止重新检查；失败保持 15 分钟退避且不推进成功时间，手动检查绕过节流。提示去重仍按本地日和版本保留“稍后”选择。下载、安装及安全校验契约保持原决定。验证见 [v1.1.4preview 本地候选证据](../evidence/2026-10-07/visual-analysis-output-budget.md)。

替代方案：公告作为唯一版本来源会遗漏真实 Release；打开网页下载无法实现应用内进度/校验；应用商店更新 API 不适用于当前 GitHub 分发；自动静默安装要求不适用特权，且违背独立确认边界。当前不引入商店、镜像、自更新脚本或远程签名轮换。

同日版本命名修订（用户明确要求）：本地 1.1.4preview/code 9 仅在用户设备测试，不对外发送；只公开纯数字正式版。仅已安装版本允许精确 preview 后缀，同号纯数字正式版比 preview 新，较低正式版不降级。远端候选的纯数字 tag、非 draft/prerelease 与资产要求不变，APK 仍要求同签名且 versionCode 递增，允许 preview 经检查更新安装同号或更高正式版。

影响：新增 `REQUEST_INSTALL_PACKAGES` 与非 exported FileProvider；无数据库迁移、Provider/公告协议/统计字段变更。网络依赖 GitHub 可达，arm64 正式签名安装可覆盖升级；Debug/Review 显示兼容限制。完整包可复用，下载中被系统杀进程后需用户重试。

契约与验收：[APP_UPDATES.md](../APP_UPDATES.md)、[ACCEPTANCE.md](../ACCEPTANCE.md)。验证范围与发布边界：[专项证据](../evidence/2026-10-06/app-updates-1.0.2.md)。
