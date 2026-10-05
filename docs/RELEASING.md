<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 正式版本构建与发布

正式安装包从 [GitHub Releases](https://github.com/hedanbaomi/mobile-agent-runtime/releases) 获取。v1.0.0 的应用版本为 `versionName=1.0.0`、`versionCode=2`，正式产物仅含 arm64-v8a，最低 Android 8.0（API 26）。

首次由 Debug/Review 切换至正式包时，签名身份不同，不能直接覆盖安装。先在旧包导出所需数据、核对导出文件，再处理旧安装；导出默认不包含密钥，重新配置服务商凭据。后续正式版本使用同一正式签名身份升级。

## 构建

从待发布 tag 对应的干净 Git checkout 构建，保留版本锁、严格依赖验证与许可门禁。私钥和密码存放在仓库外，通过进程环境提供：`ANDROID_RELEASE_KEYSTORE`、`ANDROID_RELEASE_STORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS`、`ANDROID_RELEASE_KEY_PASSWORD`。不要写进源码、命令历史、Gradle 配置缓存或 CI 日志；正式任务缺配置会失败，不回退 Debug 签名。

```powershell
.\gradlew.bat releaseGate :app-android:assembleRelease --dependency-verification=strict --no-daemon --no-configuration-cache
```

发布前核验 APK/AAB 的正式证书与既有证书一致、APK 不可调试、版本与 tag 一致、arm64 原生库及页面对齐检查通过。`releaseGate` 验证干净 Git SHA、AAB、CycloneDX SBOM 和源码归档哈希；APK 另记录哈希和证书核验结果。

## Release 附件

发布安装用 APK、AAB、对应源码归档、第三方声明、SBOM、来源/签名核验记录与 SHA-256 清单。Tag 指向已经验证并合并的源码提交，本地 main 与远端 main 一致。公开签名证书可用于比对，私钥、密码文件和用户日志不得上传。

发布说明分别记录本地门禁、模拟器回归和未完成的真机/供应商边界；GitHub Release 不代表应用商店已上架，也不把定向回归写成全量产品验收。
