<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-08-28T17:05:00+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

**接手者必须先读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。工作后必须维护本文件及受影响的专题文档。**

## 1. 当前事实

| 项目 | 状态 |
| --- | --- |
| 产品 | Android 工程与共享运行时已开始实现；完整 MVP 未完成 |
| 业务源码/构建 | Gradle 多模块已建立；`:app-android:assembleDebug` 本地通过 |
| Git | 分支 main；远程 `https://github.com/hedanbaomi/mobile-agent-runtime.git`；首次提交待本轮结束写入 |
| CodeGraph | 1.1.1；源码增加后需 `codegraph sync .` |
| 许可 | licenseGuard / reverse 本地通过；REUSE 需在提交后按 gitignore 复核；Ruleset 未配置 |
| 授权范围 | 实现产品；提交时不得把 Cursor 写入贡献者；未授权 push |

## 2. 当前任务

无进行中认领。本轮实现 M0 本地防线并写入共享领域、Provider/Agent 运行时、公告协议、知识导入状态机、Skills Broker、Android 界面壳、本地 Worker/Admin 草稿。

## 3. 关键约束

- 第一方 `AGPL-3.0-only`。applicationId 暂为 `runtime.mobileagent`。CODEOWNERS 为 `@hedanbaomi`。
- Python 不得在主进程执行；isolated service 已声明，CPython 包未嵌入。
- 含图知识库无 Vision 时等待。USearch JNI 未构建，现为 SQLite 真值上的暴力检索端口。
- 不部署 Cloudflare 生产资源。提交作者使用仓库 Git 用户 `luozhibai`，不含 Cursor。

## 4. 接手顺序

1. `git rev-parse --show-toplevel`、`git status --short --branch`、`git remote -v`
2. `.\gradlew.bat licenseGuard licenseGuardReverse`、JVM 测试、`:app-android:assembleDebug`
3. 远程 Ruleset/required checks 仍为 `M0_REMOTE_PENDING`
4. 下一功能优先补全 Chat 真实流式、Provider 密钥写入、知识导入 SAF

## 5. 未决事项

Play 生产包名、品牌、Cloudflare 账户/域名、签名身份、Embedding 模型包、NDK/USearch x86_64、CPython 3.14.x 包哈希。GitHub Ruleset 未验证。

## 6. 工作记录

### 2026-08-28：初始文档交接任务

见上一版记录：文档与本地 Git/CodeGraph 初始化，无业务工程。

### 2026-08-28：产品实现（M0 本地 + 共享契约）

- 需求：R16、R01、R02、R13 部分；验收 L01 本地正反向、A02 共享无 Android 类型。
- 远程：用户确认 `hedanbaomi/mobile-agent-runtime`，已 `git remote add origin`。空远程。未 push。
- 已写入：build-logic/license-guard、Gradle 8.10.2 / AGP 8.8.2 / Kotlin 2.1.10、shared 模块、data/sqlite、Android feature 壳、isolated Python service 声明、services/announcements 灰度向量、admin 壳、ADR-0001、CODEOWNERS、CONTRIBUTING。
- 验证：`licenseGuard` 与 `licenseGuardReverse` 通过；JVM 测试（serialization/knowledge/skills/announcements/agent-runtime/provider-api/data.sqlite）通过；`node services/announcements/src/rollout.test.mjs` 黄金向量通过；`:app-android:assembleDebug` BUILD SUCCESSFUL。本机 Maven Central TLS 失败，依赖改走阿里云镜像。未跑真机、未跑 `reuse lint` 于干净 gitignore 提交后状态（先前因 unborn HEAD 扫到 build/）。未部署 Worker。
- Git：仍可能为 unborn HEAD 直至本轮提交；作者应为 `luozhibai`，无 Cursor Co-authored-by。
- 文档：ADR-0001、README、本交接已更新。
- 下一步：提交（不含 Cursor）；用户授权后再 push；配置 Ruleset；补 Chat/Provider 闭环与知识导入。

## 7. 后续记录格式

每次新增日期标题，写清：任务/需求 ID、修改文件、事实结果、验证命令和证据、未执行项、Git 状态、待解决问题、下一步。
