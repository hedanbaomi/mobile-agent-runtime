<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0001：构建矩阵与仓库身份

- 状态：已接受（本地 M0）
- 日期：2026-08-28

## 背景

M0 必须锁定 JDK/Gradle/AGP/Kotlin/SDK，不能盲目取最新版。生产包名此前未确认；用户随后提供远程仓库。

## 决定

| 项 | 锁定值 | 证据/来源 |
| --- | --- | --- |
| JDK toolchain | 17（开发机可为 21） | AGP 8.8 要求 JDK 17 运行构建 |
| Gradle | 8.10.2 | AGP 8.8 最低/默认 Gradle |
| Android Gradle Plugin | 8.8.2 | 支持 compileSdk 35 的 8.8 补丁线 |
| Kotlin | 2.1.10 | 与 AGP 8.8 / Compose compiler 插件同版本 |
| kotlinx.serialization | 1.7.3 | 与 Kotlin 2.1 兼容的已发布线；避免未解析的 1.8.0 |
| Ktor | 3.0.1 | 与 serialization 1.7 线匹配 |
| Compose BOM | 2024.12.01 | 锁定已发布 BOM，不取未验证的更新线 |
| compileSdk / targetSdk | 35 | 本机已安装 `android-35` 与 build-tools 35.0.0 |
| minSdk | 26 | 需求 R01 |
| ABI | arm64-v8a 正式；x86_64 测试 | 方案基线 |
| 远程仓库 | `https://github.com/hedanbaomi/mobile-agent-runtime.git` | 用户 2026-08-28 书面确认 |
| CODEOWNERS | `@hedanbaomi` | 与远程 owner 一致 |
| applicationId / namespace | `runtime.mobileagent` | 本地/开发身份；Play 生产包名仍待所有者确认 |
| 共享模块形态 | 纯 Kotlin JVM 库，不依赖 Android 类型 | 满足 A02 的共享边界，并允许宿主单元测试 |

共享层不启用 iOS/桌面产品 target。Android 应用是唯一首版交付 target。

## 替代方案

- AGP 8.9+/compileSdk 36：本机虽有 android-36，但 8.8.2 对 API 35 有明确兼容表，先锁已核查组合。
- 使用未确认的品牌包名（Arca/AgentDeck）：需求明确禁止。
- 在共享模块启用 KMP `androidTarget()` 且无 JVM：宿主测试依赖 Android 单元测试，M0 CI 更重。

## 后果

- 更换 AGP/Kotlin/SDK 必须更新本 ADR、version catalog 与验证证据。
- About/源码入口使用上述 GitHub URL。
- 生产 applicationId 变更需要另一次所有者确认和迁移说明。
- GitHub Ruleset 仍须在远程单独配置并取证，仓库内 CODEOWNERS 不能代替服务端规则。
