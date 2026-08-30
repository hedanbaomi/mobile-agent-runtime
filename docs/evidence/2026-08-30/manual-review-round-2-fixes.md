<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 人工终审反馈第二轮修复与 debug 包证据

时间：2026-08-30（Asia/Taipei）。仓库：`E:\mobileAgentRuntime`。基线 HEAD：`dec1e5118c674b91c6039c0576978c811d02410e`，工作树为未提交 dirty 状态；本轮未 commit/push、未重新部署 Cloudflare、未执行正式 release。

## 1. 输入与边界

- 用户提供 5 张人工检查截图，并补充两项导航/运行时现象，共 8 项问题。
- 用户诊断 ZIP 的 SHA-256 为 `6832ebf3d500db309e0b89679366271e447310f6909a3b4fd6077155d390e904`；其中仅 7 条 INFO 事件，没有知识导入或 Skill 事件，因此它只能证明旧日志观测面不足，不能直接证明第 4—6 项根因。
- 用户的知识目录观测为 294 个 PDF、315,435,736 bytes。公共证据不记录用户文件名、真实路径、文档内容、Skill 指令、聊天或秘密。
- 不调用真实付费 Provider/Vision；不把模拟器测试冒充用户设备上的 294 文件端到端完成。F-001 仍为 `candidate_intermittent`。

## 2. 问题、根因与修复

| # | 观察与根因 | 修复 |
| --- | --- | --- |
| 1 | “无智能体”只是小标签，与相邻按钮触控区不对称 | 改为 disabled `OutlinedButton`，最小高度 48 dp、最小宽度 112 dp，保留不可点击语义 |
| 2 | More 二级页没有应用内返回；根导航对系统返回没有稳定的二级页语义 | 六个二级页统一显示返回操作，并覆盖系统返回；一级页仍按应用导航规则处理 |
| 3 | 公告地址和公钥由表单直接显示并可保存 | 从两个公告页面移除配置表单；客户端仍从 BuildConfig 读取固定 URL、key id 和公钥，用户界面没有修改入口 |
| 4 | ZIP 路径只要包含 `..` 就被拒绝，合法文件名内部连续点被误判 | 知识与 Skill 归档共享规范化 segment 校验：拒绝 `.`/`..` 段、绝对路径、盘符/ADS、控制字符，允许合法 segment 内部连续点；补正反向测试 |
| 5 | Agent 编辑界面保存的是 `SkillInstall.installId`，Repository 却用 package id 查询，得到 missing/disabled | 绑定解析改为 `skill_installs.install_id AND enabled=1`；instruction-only/启用/禁用均有仓储测试；已绑定但已禁用项只允许取消，不允许重新勾选 |
| 6 | 大批量导入在全部复制后才调度，且尾端 `KEEP` 可能在 worker 正退出时吞掉新唤醒 | 每个 item 持久化后立即用 `KEEP` 唤醒处理，批次完成后用 `APPEND_OR_REPLACE` 加尾栅栏；诊断事件区分 staged 与实际 worker complete |
| 7 | ChatViewModel 绑定 Chat 目的地 entry；跨路由时流任务可能被取消或输出留在不可见的旧实例 | 将 ChatViewModel 提升到稳定 shell/activity owner，Chat 与 Inspector 使用同一实例；导航离开不结束当前 `viewModelScope` 运行 |
| 8 | Inspector 从 `previousBackStackEntry` 猜 Chat owner，从 More 进入时必然得到另一个空 VM | 由 shell 直接传同一 ChatViewModel；界面明确区分“检查器已关闭”“请求尚未准备”“已有脱敏请求”，不持久化正文或秘密 |

公告后台还补齐了 `status`、`revisionStatus`、`category`、`severity`、`displayMode`、`target`、`channel`、`platform`、动作类型与 locale 的运行时中文显示；wire value 保持协议英文值。

## 3. 诊断能力补充

- 默认关闭、用户主动启用、有界文件与 SAF 导出/清除语义不变。
- 新增知识导入开始、进度、入队、staged、失败，Skill 检查/安装，以及批次 worker 开始、进度、完成、失败的固定事件；进度按阶段或每 10 项采样。
- `thread` 只允许 `main`、`worker`、`other`，不记录自由线程名。初始化、轮转和未捕获异常 handler 安装是 best-effort；诊断失败不能阻止应用启动。
- 仍不记录 Provider/模型/Base URL、API key/header、聊天/Prompt、知识文件名/路径、请求/响应正文、异常 message。native 崩溃、系统强杀和 ANR 仍需同一 APK SHA 的完整 ADB Logcat。

## 4. 独立复核闭环

规范轴与需求轴的独立只读复核分别指出：WorkManager `KEEP` 尾竞态、自由 thread 值、诊断初始化可能阻断启动，以及复制/入队阶段被误记为 completed。实现已分别改为 `APPEND_OR_REPLACE` 尾栅栏、固定 thread 类别、disabled fallback/best-effort 初始化和 `knowledge_import_staged`。按用户要求，这些发现修复后不再开启新的自动复核循环。

## 5. 验证结果

| 命令/场景 | 结果 |
| --- | --- |
| `.\gradlew.bat :shared:knowledge-api:test :shared:skills-api:test :data:sqlite:test --no-daemon` | 最终通过；SQLite 107 tests，包含 ZIP 与 Skill install 绑定回归 |
| `.\gradlew.bat :app-android:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.diagnostics.DiagnosticsDeviceTest,runtime.mobileagent.ReleaseGateUiDeviceTest,runtime.mobileagent.NavigationScopeTest" --no-daemon` | API 31 x86_64，17/17、0 failed，BUILD SUCCESSFUL |
| `.\gradlew.bat check --dependency-verification=strict --no-daemon` | 936 tasks，77 executed、859 up-to-date，BUILD SUCCESSFUL |
| `npm run check && npm test`（`services/announcements`） | 通过；包含 Node VM 实际执行后台中文值映射 |
| `.\gradlew.bat :app-android:assembleDebug --no-daemon` | 342 tasks，BUILD SUCCESSFUL |
| `adb install -r` 与启动 | `Success`；`runtime.mobileagent/.MainActivity` 为 resumed activity |
| `python -B -m reuse lint` | 386/386，REUSE 3.3 PASS |
| `docs/DOCUMENTATION_CHECK.md` 内存检查器 | 49 Markdown、166 local links、2 JSON、19 requirements、6 UI acceptance，issues 0，PASS |
| `git diff --check` / `codegraph sync .` | diff 无空白错误；CodeGraph `Already up to date` |

## 6. 人工终审 APK

- 路径：`E:\mobileAgentRuntime\app-android\build\outputs\apk\debug\mobile-agent-runtime-manual-review-round2-20260830-debug.apk`
- 大小：211,785,942 bytes
- SHA-256：`650bcc7148f0cf341b91ec716b2ae445d2be104c7df1d29d808c3fbe356739df`
- BuildConfig：`GIT_REVISION=dec1e5118c674b91c6039c0576978c811d02410e-dirty`、`GIT_DIRTY=true`、`DB_SCHEMA_VERSION=11`、`BUILD_TIME_UTC=2026-08-30T02:42:00Z`
- 公告固定接线：`ANNOUNCEMENTS_BASE_URL=https://announcements.luotianyi.fun`、`ANNOUNCEMENTS_KEY_ID=mar-prod-20260829-1`；公钥只在构建配置内部，不在 Android 用户界面显示或编辑。
- `apksigner verify --verbose --print-certs`：通过；APK Signature Scheme v2；证书 `C=US, O=Android, CN=Android Debug`；证书 SHA-256 `315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788`；RSA 2048。

这是 dirty debug 人工终审包，不是正式 release。

## 7. 剩余验证边界

- 用户实际 294 个 PDF 的完整索引时间、热量、内存、磁盘和最终 READY/WAITING 分布尚未在测试设备重跑；本轮证明的是导致零进度/误拒绝的代码路径和调度竞态已修复。
- 没有真实 Provider 流式请求，所以“跨页面后真实供应商仍完整输出”仍以稳定 owner 实现、导航设备测试和用户人工终审共同确认。
- 没有执行真实 Vision、500 文件/约 500 MiB 完整 K06、初始 SAF→staging 进程死亡、ENOSPC、Android 15 六小时 timeout 或 Android 16 Job 配额耗尽。
- 没有正式 release 签名输入，保持 `BLOCKED_SIGNING`；没有 commit/push 或新的公告生产部署。
