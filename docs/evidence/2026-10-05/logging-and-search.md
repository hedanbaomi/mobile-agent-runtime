<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# INFO 日志、正式密钥与多供应商搜索

日期：2026-10-05（Asia/Taipei）。基线 `main` / HEAD `06be7ded44842bea5054c46a89103759f0e688f0`，真实根 `E:/mobileAgentRuntime`。本报告记录实现与本地验证；用户随后授权提交、推送、PR 合并和本地 main 同步，Git 结果以对应 PR 为准。原有附件、计划和交接历史保留。

## 实现

- 诊断默认关闭，日志默认 INFO；INFO 保留普通阶段、结果与 ERROR，过滤详细导入进度与 Vision 正文且不计为丢弃。用户可持久选择 DEBUG，不自动开启诊断；既有凭据、provider-private continuation、白名单与滚动限额保留。ZIP manifest 记录当前级别，并说明旧 DEBUG 历史仍可保留。
- Brave/Tavily/Exa 独立加密凭据及设置；兼容旧 Brave；未知服务 fail-closed，秘密回收覆盖全部服务引用。
- Agent 默认禁止搜索；显式开启且新建会话后，直接执行查询，无逐次审批。冻结快照与当前权限/修订、服务配置/修订及活动凭据共同约束发送与结果复用。撤销、关闭再开启、服务切换再切回不复活旧执行器。其他工具的确认设置独立。
- 固定官方搜索目标、请求及认证；真实 DNS 公共地址检查，禁止代理/Cookie/重定向/自动重试，含 503 Retry-After:0。发送后不确定结果保留 UNKNOWN_OUTCOME。结果统一、脱敏、有界、标为不可信，不自动打开网页。

## 验证

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| `:shared:skills-api:test` | 136/136，0 fail/error/skip | 含 HostHttpTest 20、BuiltinToolsTest 31；三家认证/请求、private/shared DNS、迟到撤权、302/307/503 单次请求、免审批/dedup/unknown |
| `:data:sqlite:test` | 378/378，0 fail/error/skip | 含 WebSearchSettingsTest 3；旧 Brave、独立密钥/回收、revision ABA、未知服务 |
| `:app-android:testDebugUnitTest` | 225/225，0 fail/error/skip | 含 DiagnosticLogLevelTest 6、WebSearchResponseTest 3；默认/过滤/持久化/manifest、结果规范化/凭据过滤/整体 JSON 限额/严格权限解析 |
| 定向 `connectedDebugAndroidTest` | API34 x86_64，29/29，0 fail/skip | DiagnosticsDeviceTest 19、DiagnosticsLogLevelUiTest 1、WebSearchDeviceTest 5、WebSearchConfigurationUiTest 2、WebSearchPermissionDeviceTest 2 |
| `compileDebugAndroidTestKotlin` / `lintDebug` / `licenseGuard` | PASS | `--no-daemon --offline --dependency-verification=strict`；修复后合并单元/设备/lint/许可命令 678 tasks |
| 正式密钥加载、签名与验签 | PASS | PKCS12 / RSA4096 / SHA256withRSA；Gradle verifyReleaseSigning、validateSigningRelease、signingReport |
| REUSE / CodeGraph sync | PASS | REUSE 871/871；索引已是最新；四份受保护附件/计划 SHA-256 与开工基线一致 |
| 提交前全仓门禁 | PASS | `reviewGate verifyCiPins verifyDependencyLock verifyDependencyVerification --no-daemon --offline --dependency-verification=strict`，5m2s、1086 tasks；包含全量 check、licenseGuard 正反向与 Review APK/SBOM/provenance 校验 |

首次设备 UI 测试在英文布局中点击屏幕外 INFO 控件失败，补 `performScrollTo()` 后通过。搜索回归初轮缺少测试 import，第二轮 lint 检出缩进；均修正，最终门禁通过。没有把失败尝试记录为通过。

设备测试使用本任务独立的 API34 无窗口模拟器、真实 Android SQLite/Keystore 和测试凭据。权限测试在派发前撤销并断言 Denied，不向供应商发送查询；成功免审批/协议字节链由共享层受控 TLS fixture 验证。设备测试不是手机或线上付费服务验收。

## 正式密钥

用户指定目录：`C:/Users/32735/Desktop/证书与密钥/mobileagentruntime`。密钥、随机密码文件与本地使用脚本仅在仓库外保存；密码不输出、不写日志或项目配置。目录禁用继承，访问主体限定当前用户、SYSTEM 与 Administrators。

- 文件：`mobileagentruntime-release.p12`；alias `mobileagentruntime-release`。
- 有效期：2026-10-05 至 2056-09-27（UTC）。
- 公共证书 SHA-256：`373f13cff1eee414c1cf01132457049e2f62bc0e89eb84c78e037d3d440cab50`。
- 保持现有四项环境变量签名配置；未生成正式 Release APK/AAB，Review 的 debug 签名配置未改变。

## 独立复核与证据边界

日志独立只读复核使用经验证的 DSH DeepSeek V4.1 Flash，结论无 P1/P2；P3 的导出当前级别和旧日志提示已补。搜索涉及授权及协议，按 Jev `jev-1.13.0` 的 Choice 结果采用原生 `gpt-6-luna/xhigh` 独立只读复核，发现 P2 服务商切换后异步 UI 名称与实际保存目标错位、P3 共享地址过滤遗漏。已同步更新服务名称，保存/启停/删除均绑定渲染时服务 ID 并拒绝旧回调；已拒绝 `100.64.0.0/10` 并补上下边界/邻域与零发送测试。修复后窄范围独立复核确认两项关闭，无残余产品发现；复核为静态只读，实际运行证据由主 Agent 完成。

本地原始 Gradle 日志、JUnit XML、源文件哈希、保护材料哈希和复核摘要在 `.private/info-release-key-20261005/` 与 `.private/search-providers-20261005/`；这些目录不作为公共源码发布。未调用真实搜索/模型付费服务、未发布安装包或部署；线上供应商凭据与物理设备兼容性仍待对应环境验证。
