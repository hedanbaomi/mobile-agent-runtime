<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# v1.0.2 应用真实更新验证

日期：2026-10-06。需求：R36；验收：UP01—UP06、N06。此记录覆盖发布前实现和本地验证；正式签名构建、远端门禁、合并 SHA 与资产哈希以 Release 的 verification/provenance 附件及最终交接收据为准。

## 行为与信任边界

设置页检查更新和每日首次前台启动使用固定官方 GitHub latest stable Release，按整数版本比较，独立于公告。成功日期和候选跨重建保存；手动检查绕过每日节流，失败保留候选并退避；关闭当日提示后不重复打扰。检查不自动下载。

用户选择下载并安装后显示进度，校验受控 HTTPS 跳转、大小、SHA-256、包名、递增版本、当前签名集合、SDK 和 ABI。缓存位于私有目录，安装前再次校验，只读 FileProvider URI 交给显式系统安装器。首次未知来源许可返回后继续，最终由用户确认；取消后可以重试。生产没有测试源或测试参数。协议见 [APP_UPDATES.md](../../APP_UPDATES.md)、[ADR-0025](../../adr/0025-verified-app-updates.md)。

## 执行证据

- 严格依赖校验、离线完整 `check licenseGuardReverse verifyCiPins verifyWorkflowYaml :app-android:assembleDebug :app-android:assembleDebugAndroidTest`：BUILD SUCCESSFUL，2m55s，1070 tasks。Android Debug/Release/Review 单元各 246，合计 738，零失败；每变体包含 16 条新增更新测试。测试覆盖版本/来源/跳转负例、每日/手动/缓存/并发/失败退避、下载异常与取消清理、校验失败拒绝、完整文件复用与安装前复验。既有 SQLite 389 条结果由该次门禁复用，没有数据库修改。
- 最终设备测试夹具编译：BUILD SUCCESSFUL，50s，358 tasks。API34 x86_64 隔离模拟器定向更新测试 3 条执行成功，1 条可选联网检查跳过；该联网项另以参数单独执行，1/1 通过（10.153s），实际读取官方 Release digest 并跟随 APK 下载跳转验证 ZIP 前缀。
- 实际 Android PackageManager 对运行 APK 的原件解析成功，对修改 classes.dex 一字节的副本签名收集失败；FileProvider 非导出、可读授权及拒绝目录外文件均实测。
- 真实弹窗按钮驱动下载与 Android 校验，进入未知来源设置，许可返回后出现系统升级确认。先取消，再从“安装更新”重试并确认，包实际升级至私有测试 fixture 1.0.3/code5，测试数据 marker 保留；随后恢复 debug 1.0.2/code4并停止自有 AVD。成功替换应用会结束目标进程，该次 instrumentation 尾部的进程退出不计为 JUnit PASS；升级结果通过安装后 package 信息和数据文件独立核验。fixture 沿用本机 debug 证书，仅供隔离测试，不是正式资产。
- 独立只读 DeepSeek V4.1 Flash 审阅及最终补审：生产源码 APPROVED。审阅者没有亲自运行 Gradle、网络或模拟器，设备结论来自协调者实测日志；不得将静态审阅写成独立设备复跑。

日志与收据保留在本地忽略目录 `.private/app-update-102-20261006/`：`gate-final.log`、`device-tests-clean-fixture-build.log`、`device-update-tests-final.log`、`live-release-check.log`、`system-install-receipt.json`、`installed-fixture-package.txt`、`restored-debug-package.txt`、`preserved-marker-after-restore.txt`、独立审阅记录、许可检查与索引同步日志。

## 未验证范围与升级入口

未实测正式 arm64 真机覆盖升级、API26—27/OEM 安装器、旋转/进程被杀的全部交互、收费 Provider 和全量产品长稳；模拟器系统升级成功不替代这些验收。旧 v1.0.1 没有该下载更新实现，用户须先手动安装一次 v1.0.2；后续版本才使用此每日检查与下载安装流程。系统最终安装确认仍需用户操作。
