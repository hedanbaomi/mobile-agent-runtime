<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 正式版本构建与发布

已公开的正式安装包从 [GitHub Releases](https://github.com/hedanbaomi/mobile-agent-runtime/releases) 获取。当前已发布 v1.1.3/code 8；本轮 v1.1.4.1preview/code 10 是供用户人工审查的本地正式签名候选，不发布至 GitHub。正式产物仅含 arm64-v8a，最低 Android 8.0（API 26）。前版 v1.0.0/code 2、v1.0.1/code 3、v1.0.2/code 4、v1.1.0/code 5、v1.1.1/code 6、v1.1.2/code 7 与 v1.1.3/code 8 可使用同一正式签名升级。v1.0.2 起支持检查 GitHub stable release、用户点击下载与系统确认安装；v1.1.4preview 将自动检查间隔改为一小时，详见 [更新契约](APP_UPDATES.md)。

首次由 Debug/Review 切换至正式包时，签名身份不同，不能直接覆盖安装。先在旧包导出所需数据、核对导出文件，再处理旧安装；导出默认不包含密钥，重新配置服务商凭据。后续正式版本使用同一正式签名身份升级。

历史本地 `1.1.4preview` 使用 code 9，当前 `1.1.4.1preview` 使用 code 10；后续公开的纯数字正式版须使用大于 10 的 code，并保留同一正式签名，使 preview 能经应用内检查更新覆盖升级。preview 不建立公开 Release 或公开分发附件。

## 构建

从待发布 tag 对应的干净 Git checkout 构建，保留版本锁、严格依赖验证与许可门禁。私钥和密码存放在仓库外，通过进程环境提供：`ANDROID_RELEASE_KEYSTORE`、`ANDROID_RELEASE_STORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS`、`ANDROID_RELEASE_KEY_PASSWORD`。不要写进源码、命令历史、Gradle 配置缓存或 CI 日志；正式任务缺配置会失败，不回退 Debug 签名。

```powershell
.\gradlew.bat releaseGate --dependency-verification=strict --no-daemon --no-configuration-cache
```

发布前核验 APK 的正式证书与既有证书一致、不可调试、版本与 tag 一致。`releaseGate` 只构建 APK，检查 arm64 独占、原生库 16 KiB 对齐及法律资产，并将 APK、CycloneDX SBOM、源码归档哈希与干净 Git SHA 绑定。Debug/Review 保留 arm64-v8a 与 x86_64，以支持模拟器回归。

正式身份比对是门禁通过后的必要发布步骤：运行 Android SDK 的 `apksigner verify --verbose --print-certs`，将实际 APK signer 的 SHA-256 与既有公开正式证书逐字比对，并保存核验记录。

## Release 附件

v1.1.2 起同时发布 `mobileAgentRuntime-vVERSION-windows-adb.zip`。解压后双击 `start-wired-adb.bat`，电脑需安装 Java 17 或更新版本，并另行准备 [Google 官方 Platform-Tools](https://developer.android.com/tools/releases/platform-tools)。工具只激活用户明确选择的 USB 设备，不包含 adb/JRE，也不在电脑执行模型命令。手机开始配对后，把临时令牌输入电脑；看到等待提示时点击手机“完成配对”，确认手机已连接后即可退出电脑工具并拔线。设备常驻服务持续提供 ADB 级能力，不依赖 Shizuku；设备重启或服务被系统结束后需要重新激活。工具的第三方许可和运行库清单随 ZIP 发布。

从旧桌面桥升级时，需要按新流程激活设备服务。旧电脑端目录标识不能直接用于设备端服务；已有有线 ADB 目录若提示恢复失败，请重新选择该目录。升级不会把旧标识猜测为路径或自动扩大文件范围，原 Agent 授权与其他对话数据保留。

发布安装用 APK、对应源码归档、第三方声明、SBOM、来源/签名核验记录与 SHA-256 清单。Tag 和正式构建 checkout 指向已经验证并合并的远端源码提交；本地 main 的同步按本次用户指令处理，不覆盖工作区未提交内容。公开签名证书可用于比对，私钥、密码文件和用户日志不得上传。

发布说明分别记录本地门禁、模拟器回归和未完成的真机/供应商边界；GitHub Release 不代表应用商店已上架，也不把定向回归写成全量产品验收。

更新客户端依赖固定命名 `mobileAgentRuntime-vVERSION-arm64-v8a.apk` 与 GitHub Release asset SHA-256 digest。完整附件上传并验证后再公开并设为 latest stable；tag 只使用 `vMAJOR.MINOR.PATCH`，正式 versionCode 必须递增，签名必须与已发布正式版本一致。

2026-10-07 本地人工核验版本为 `1.1.4.1preview` / versionCode `10`。第四数值段仅用于本地 preview 迭代；stable feed 仍严格采用三段版本且拒绝 preview。后续同 patch 正式版可升级，正式 versionCode 必须大于 10。此 preview 不创建公开 Release。
