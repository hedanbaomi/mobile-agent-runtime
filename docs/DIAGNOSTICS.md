<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 应用内诊断日志

本功能用于给无法稳定复现的移动端问题绑定构建版本、匿名操作面包屑和最近一次 JVM 崩溃摘要。它不是遥测、聊天记录或完整 Logcat 替代品。

## 1. 用户操作

1. 打开“设置 → 隐私与调试”，主动开启“应用内诊断记录”。默认关闭，关闭时不写事件。
2. 复现问题；若应用崩溃，重新打开同一 APK。
3. 点击“导出诊断 ZIP”，在 Android 系统文件选择器中选择保存位置。
4. 保存成功后把 ZIP 与发生时间、操作步骤一并提供。确认文件已保存前不要点“清除诊断日志”。

“清除诊断日志”只删除本应用拥有的诊断文件，不修改聊天、Provider、知识库、Skill或公告数据。导出失败会保留原始现场，可重新选择目标再试。

## 2. 记录范围与上限

- 固定公共字段：schema、session、pid、thread类别、UTC时间、level、event、Git revision、dirty、数据库schema、构建时间。`thread` 只能是 `main`、`worker` 或 `other`，不保存任意线程名。
- 固定事件：诊断启停、Provider模型tools/image能力开关、Provider模型保存开始/成功/失败、知识导入开始/进度/入队/staged/失败、Skill检查/安装、批次worker开始/进度/完成/失败、未捕获异常。知识导入的“staged”仅表示文件已复制并入队，只有worker到达真实终态才记录完成。
- 允许的事件字段仅含能力名、布尔状态、能力集合、模型角色、阶段、匿名计数/状态、异常类型和有限类/方法/行号。Provider名称、模型ID、Base URL、知识/Skill文件名与秘密不进入字段。
- 当前日志64 KiB、上一段64 KiB、最近崩溃32 KiB、单事件4 KiB、导出ZIP192 KiB，超限按拥有文件滚动或拒绝单条事件。
- 导出manifest补充设备fingerprint，便于区分系统镜像和构建环境。

## 3. 隐私与崩溃边界

字段白名单之后仍执行 `SecretRedactor` 与URL/query、Windows/Unix路径、换行/控制字符、长度清洗。不得保存聊天、System Prompt、模型参数正文、知识库文件名/内容、Skill输入输出、API Key/Header/Cookie、请求或响应正文、异常message。

未捕获JVM异常只保存异常类和有限stack frame，随后委托Android原始未捕获异常处理器，不能吞掉崩溃或改变系统终止语义。应用不申请`READ_LOGS`，所以native崩溃、系统/内核强杀、ANR全量线程信息和未落盘系统日志仍需相同APK SHA对应的ADB Logcat。日志开启也不保证覆盖进程被立即杀死前的最后一步。

## 4. 验证入口

仪器测试 `runtime.mobileagent.diagnostics.DiagnosticsDeviceTest` 覆盖默认关闭、偏好持久化、启停事件、白名单/脱敏、滚动上限、损坏轮转目标、ZIP/manifest、异常message排除、原处理器委托、失败导出保留和清除；与发布门禁界面、导航作用域组成的最终 API 31 x86_64 定向回归为 17/17。对应 debug APK SHA-256 为 `650bcc7148f0cf341b91ec716b2ae445d2be104c7df1d29d808c3fbe356739df`。完整命令、签名和边界见 [第二轮人工反馈证据](evidence/2026-08-30/manual-review-round-2-fixes.md)，最初诊断/部署证据仍见 [首包证据](evidence/2026-08-30/admin-cn-diagnostics-debug-deploy.md)，验收映射见 [A08](ACCEPTANCE.md)。
