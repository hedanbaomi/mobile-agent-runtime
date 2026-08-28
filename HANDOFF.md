<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 项目交接

最后更新：2026-08-28T17:45:00+08:00（Asia/Taipei）。项目根目录：`E:\mobileAgentRuntime`。

**接手者必须先读 [agent.md](agent.md)、本文件和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。工作后必须维护本文件及受影响的专题文档。**

## 1. 当前事实

| 项目 | 状态 |
| --- | --- |
| 产品 | M1 部分接通：OpenAI 兼容流式 Chat、Keystore 密钥、SAF 导入；完整 MVP 未完成 |
| 业务源码/构建 | Gradle 多模块；`:app-android:assembleDebug` 本轮通过 |
| Git | 分支 `main` 跟踪 `origin/main`；HEAD `7a71c26e2dcc8169bb50636e8391dc4c18881577` 已 push。作者/提交者 `luozhibai`，无 Cursor trailer |
| CodeGraph | 本轮曾执行 `codegraph sync .`（扫描/解析开始；CLI 提前返回，索引是否写完未确认） |
| 许可 | 本轮 `licenseGuard` / `licenseGuardReverse` 通过 |
| 授权范围 | 实现产品；提交时不得把 Cursor 写入贡献者；用户已授权 push `main`，并于 2026-08-28 再次授权 commit 并 push 本轮 M1 |

## 2. 当前任务

无进行中认领。本轮已完成 Chat 真流式、Provider 密钥写入、知识 SAF 导入（含图等待）。

## 3. 关键约束

- 第一方 `AGPL-3.0-only`。applicationId 暂为 `runtime.mobileagent`。CODEOWNERS 为 `@hedanbaomi`。
- Python 不得在主进程执行；isolated service 已声明，CPython 包未嵌入。
- 含图知识库无 Vision 时等待。TXT/MD 可词法 READY；无 ONNX 向量。USearch JNI 未构建。
- 不部署 Cloudflare 生产资源。提交作者使用仓库 Git 用户 `luozhibai`，不含 Cursor。Cursor 环境会劫持 `git commit`；需用 `D:\Git\mingw64\libexec\git-core\git.exe commit-tree`。

## 4. 接手顺序

1. `git status --short --branch`、`git log -1 --format=full`
2. 确认 HEAD 无 `Co-authored-by: Cursor`
3. 真机/模拟器走：Providers 存密钥 → Knowledge 导入 txt 与 png → Chat 流式/取消。未在本轮做设备验证
4. GitHub Ruleset 仍为 `M0_REMOTE_PENDING`
5. 下一功能：Agent/Prompt 快照、PDF 解析、公告客户端验签缓存、或 CPython/USearch spike（需 NDK）

## 5. 未决事项

Play 生产包名、品牌、Cloudflare 账户/域名、签名身份、Embedding 模型包、NDK/USearch x86_64、CPython 3.14.x 包哈希。GitHub Ruleset 未验证。未做独立安全审阅。

## 6. 工作记录

### 2026-08-28：初始文档交接任务

见上一版记录：文档与本地 Git/CodeGraph 初始化，无业务工程。

### 2026-08-28：产品实现（M0 本地 + 共享契约）

- 需求：R16、R01、R02、R13 部分；验收 L01 本地正反向、A02 共享无 Android 类型。
- 远程：用户确认 `hedanbaomi/mobile-agent-runtime`。
- 验证：当时 licenseGuard、JVM 测试、assembleDebug、reuse lint 通过。Maven Central TLS 失败，依赖走阿里云。
- Git：实现提交 `aa2fa1ec`（无 Cursor）。

### 2026-08-28：首次 push

- `git push -u origin main` 成功。后续交接提交 `ed3efbb` 已推送。

### 2026-08-28：M1 Chat 流式 / 密钥 / SAF 导入

- 需求：R02、R06、R12；验收目标 A03/A04/K03 本地部分，非 DEVICE_PASS。
- 行为：Providers 将 API key 经 Android Keystore AES-GCM 写入 `secrets` 密文，库中只有 `secretRef`。Chat 使用 `OpenAiCompatibleAdapter` SSE 流式，可取消，错误走 SecretRedactor。知识库系统选择器导入；图片停在 `WAITING_FOR_VISION_MODEL`；TXT/MD 写入 FTS；PDF/Office 失败说明原因，文件仍复制到 CAS。
- 主要路径：`ChatViewModel`/`ProvidersViewModel`/`KnowledgeViewModel`、`KnowledgeRepository`、`CasBlobSink`、`OpenAiSse`、`AndroidSecretStore`、schema v2 `import_jobs`。
- 验证：`.\gradlew.bat licenseGuard licenseGuardReverse :shared:knowledge-api:test :shared:provider-api:test :data:sqlite:test :shared:agent-runtime:test :app-android:assembleDebug` → BUILD SUCCESSFUL。未跑真机、未跑付费模型、未跑 `reuse lint` 本轮、未独立审阅。
- Git：用户授权 `commit并push`。已写入并推送 `7a71c26e2dcc8169bb50636e8391dc4c18881577`。作者 `luozhibai`，无 Cursor。
- 文档：`docs/KNOWLEDGE.md`、`docs/IMPLEMENTATION_PLAN.md`、本交接。
- 下一步：补 Agent 快照、PDF/视觉、公告客户端、Ruleset。设备验证仍缺。

## 7. 后续记录格式

每次新增日期标题，写清：任务/需求 ID、修改文件、事实结果、验证命令和证据、未执行项、Git 状态、待解决问题、下一步。
