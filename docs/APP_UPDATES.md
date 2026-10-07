<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Android 应用更新

适用：v1.0.2/code 4 起，R36、UP01—UP06、N06；[ADR-0025](adr/0025-verified-app-updates.md)。旧版本的检查按钮只刷新更新公告，必须先手动安装 v1.0.2 才能使用本流程。

## 版本来源与触发

应用使用独立匿名连接请求固定 GitHub 仓库 `hedanbaomi/mobile-agent-runtime` 的 `/releases/latest`，仅接受已公开 stable release。正式 tag 为 `vMAJOR.MINOR.PATCH`，按三个整数比较，候选包固定名为 `mobileAgentRuntime-vVERSION-arm64-v8a.apk`，并要求上传完成、大小 1—256 MiB、SHA-256 digest 和本仓库的精确 HTTPS 下载 URL。缺资产、无哈希、预发布、异常响应或请求失败显示错误，不能当作“最新版本”。无须更新公告、公告服务配置、Provider 或统计同意。

本地正式签名 `MAJOR.MINOR.PATCHpreview` 仅供用户设备自测，不公开分发。已安装版本允许这个精确后缀；同号纯数字正式版视为更新，更低正式版不会提示降级。更新源仍只接受纯数字正式版，preview tag 即使被错误标为 stable 也拒绝。正式版沿用同一证书并使用更高 versionCode，preview 可通过原有“检查更新→下载并安装”流程升级。

v1.1.4preview 起自动检查间隔为一小时。进入或恢复前台时检查是否到期，持续前台使用也定期检查；进入后台停止定时循环，恢复前台后补查到期的版本信息，不新增常驻服务。成功检查时间戳与合法候选持久化，进程重建且未到期时复用缓存；时钟回拨、未来时间戳、无合法缓存或仅保留旧每日记录时重新检查。失败不记录成功时间，不覆盖合法缓存；同进程自动检查失败退避 15 分钟，手动按钮跳过一小时/退避限制。同一进程只有一个检查或下载任务，防止重复点击及下载状态被检查覆盖。自动检查仅查询版本，下载必须用户点击。同一版本“稍后”仍按本地日期记录，小时检查不反复弹窗，次日或手动检查可以再次提示。

## 下载与验证

正式包只支持 arm64；Debug/Review 与正式包签名不同，只可查看版本，不能以此覆盖升级。下载与公告、模型请求的凭据域隔离，不带 API Key、Cookie、安装 ID 或用户内容。HTTPS 重定向最多 5 次，每跳校验地址，仅允许本仓库 GitHub 下载路径或 GitHub 的 `release-assets.githubusercontent.com` / `objects.githubusercontent.com`；禁止 HTTP、userinfo、自定义端口、伪域名与公告提供的地址。

用户点击“下载并安装”后展示字节进度，可取消并重试。下载在应用私有 `files/app-updates/`，不需存储权限。连接 15 秒/读 30 秒超时，流式限制声明大小和 15 分钟下载期限；磁盘预留 16 MiB。完整字节数和 SHA-256 匹配后以只读 APK 替换临时文件；失败/取消清临时包，不把部分下载交给系统。完整包允许进程重建后复用，但安装前重新验签和哈希。

Android `PackageManager` 收集 APK 签名，拒绝解析/签名失败，核对包名等于当前安装、版本名等于候选、版本 code 严格增加、正式版本更新（同号正式版高于本地 preview）、当前签名集合完全一致、最低 SDK 不超过设备、原生库 ABI 均受设备支持。这里不接受远程下发新公钥或签名轮换；未来轮换需要独立设计。系统安装程序还会独立验证包。私有文件不能由其他普通应用写入；安装前再次完整校验。

## 系统安装与恢复

仅声明 `REQUEST_INSTALL_PACKAGES`。首次需要用户在系统页面允许本应用安装未知来源应用，返回后继续原来主动发起的安装；拒绝或取消不改用户数据，可重试。最终由系统显示升级确认，普通应用不静默安装，也不调用 Root/Shizuku/ADB 提权。

FileProvider 非 exported，仅暴露更新目录；只向系统安装程序的显式组件授予这一个已验证文件的临时只读 URI，不授予写权限或用户文件目录。等待未知来源许可的用户动作可通过 Activity saved state 恢复；进程被杀导致下载中断时，重新点击即可重新下载，完整已下载候选可复用。应用页面切换不会取消应用级下载；Activity 在下载期间销毁时，完成后保留“安装更新”按钮供用户继续。

## 发布约束

每次正式发布沿用同一签名，递增 code 和 stable tag；上传固定命名 APK 并等待 GitHub 生成 SHA-256 digest，再将完整 release 公开为 latest stable。Draft 不提供给更新检查。签名、源码、SBOM、provenance 和校验附件仍按 [发布规范](RELEASING.md) 验证。Android 系统或网络失败不能阻塞聊天、知识库、技能和公告。

依据：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)、[Android 安装来源许可](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/training/secure-file-sharing/setup-sharing)。

2026-10-07 本地人工核验版本为 `1.1.4.1preview` / versionCode `10`。第四数值段仅用于本地 preview 迭代；stable feed 仍严格采用三段版本且拒绝 preview。后续同 patch 正式版可升级，正式 versionCode 必须大于 10。此 preview 不创建公开 Release。
