<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Skill、工作区授权与动态上下文压缩修复

日期：2026-10-07（Asia/Taipei）。关联 A06、S09/S13、R34/C20—C25；决定见 [ADR-0031](../../adr/0031-skill-bindings-and-adaptive-context.md)。

## 实现

- AgentRepository 区分既有关联与新绑定；禁用安装可以保留关联，保存与新快照不受阻，新快照只冻结启用安装。安装缺失和新增禁用绑定继续拒绝。UI 暂停关联显示未勾选，可取消关联；活动数量按当前启用/信任投影。
- RuntimeIntegration 内置工作区/shell 清除 Skill identity 后重新解析上下文；memory 与实际 Skill 仍使用 Skill envelope。Chat 每次 Run 过滤当前关联与启用安装。
- workspace_list 保留原 JSON 字段，并由 resolver 与 backend 的交集生成有效读写/操作/相对路径范围；atomic_replace 单独描述后端支持，SAF 创建能力单独列出。实际派发仍实时复核，描述不消费 file ONCE，真实枚举仍消费自身 ONCE。
- 默认按有效模型窗口压力触发压缩，85%/60% 阈值保留；固定消息数/用户轮/段轮模式可选。累计默认模型 128、工具 100、摘要 16、准入 1800 秒，可配置且压缩不重置；持久和内存预算一致。Python broker 不再把配置时限强行截至 180 秒。停滞、审批、取消、撤权和 UNKNOWN 不重放保持。
- 本地核验版本 1.1.4.1preview/code10；仅 installed preview 支持第四数值段，stable feed 继续严格三段、拒绝 preview。同 patch stable 需要更大的 versionCode。

## 验证

所有命令由 Windows Git Bash 启动，运行 JVM21 / JVM17 toolchain、Android SDK36。原始日志保留于本机 `.private/saf-skill-budget-20261007/`，不提交缓存或诊断材料。

- 修前真实红测：`SkillRepositoryTest.disabledRetainedSkillAllowsEditsAndOnlyNewEnabledSnapshotsRestoreIt` 因 disabled 绑定保存失败；`ContextCompactionRuntimeTest.millionUnitWindowDoesNotPayForCountOnlyCompaction` 因固定历史条数派发摘要失败。`red.log` 保留原始结果。
- domain **79/79**、agent-runtime **96/96**、SQLite **399/399**；覆盖 16K/1M 同历史差异、1M 条数/轮数不触发、真实容量压力与固定模式、旧值保留、暂停/重新启用不可变快照。
- Android JVM **290/290**，`app-final.log` 记录 strict dependency verification 下 `:app-android:testDebugUnitTest :app-android:assembleDebugAndroidTest` 成功。新 workspace 投影 7 项覆盖只读 Agent、SAF 创建/原子覆盖、路径范围、Skill 交集、ONCE 描述与派发区别；预算 editor 与 preview 比较回归通过。
- 专用 API36 x86_64 模拟器（emulator-5580），实际 Android instrumentation **25/25**，`device-final.log` 为 `OK (25 tests)`。覆盖禁用关联真实 Checkbox、唯一启用 instruction Skill 的真实 factory 内置列/写/读/删除及 durable audit、Python broker 旧 180 秒之后仍准入/跨截止完整回复/拒绝下一调用/未知不重放、聊天压缩/usage/固定模式/预算 UI、准备过程不在主线程探测后端。
- 独立只读权限审阅 **APPROVE**，范围为关联、Agent/Skill 上下文、预算/压缩及工作区投影。静态审阅与作者测试分开记录；审查确认实际枚举消费自身 ONCE，投影只 revalidate 而不消费文件 grant。
- `git diff --check` 通过；CodeGraph 已同步。隔离工作树 REUSE 已通过并在新增本证据后重新核验；`licenseGuard licenseGuardReverse check verifyCiPins verifyDependencyLock verifyDependencyVerification verifyWorkflowYaml --offline --dependency-verification=strict --no-build-cache --no-watch-fs -Pkotlin.incremental=false --max-workers=1` **PASS**（996 tasks、6m27s），完整日志 `pre-commit-final.log`。

首轮设备测试 23/25：两个失败是新增测试夹具按非保证的 Skill display name 查找安装及点击 Switch 标签；已按安装 hash/实际 Switch tag 修正并全量复跑。Gradle 的 cached Kotlin ABI、Windows immutable-workspace rename、离线 UTP 缺项与 Google Maven TLS 错误均保留原日志，不作为产品缺陷。最终通过缓存的相同依赖字节建立本地 Maven 视图、恢复已完成的一个 transform 目录并逐文件校验字节，strict checksum、许可、测试与源码均保持；设备通过 `adb shell am instrument` 运行相同用例，没有跳过失败测试或降低门禁。

## 交付边界

此证据不代表物理 arm64 设备、真实 Provider 1M tokenizer/长回复或 OEM SAF 已验收。用户材料显示 SAF 读取成功且没有实际写操作，不能据此声称 SAF 后端写失败；本轮修复的是授权描述与内置工具的上下文选择，不自动授予目录写权限。正式签名 preview 的合并 SHA、APK/hash、签名连续性及 clean-source provenance 由交付目录另证；不创建公开 Release。
