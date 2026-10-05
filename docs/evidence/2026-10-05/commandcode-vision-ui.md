<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Command Code 窗口、图片探测与页面精简

时间：2026-10-05T09:12:21+08:00（UTC+8）。关联 R02/R03/R12/R18、A03/A04/A10、U02/U05。
范围：本地源码修复、离线协议测试、Android 模拟器与 Debug 签名 review 包；未提交、推送或合并。本轮基线和本地 main/origin/main 为 1400a54c9273d6471054c20ddef1468f0137a6fe，工作分支 codex/commandcode-metadata-vision-ui-20261005，源码未提交，provenance 如实记录 gitDirty=true。

## 用户复测与发布授权

2026-10-05T09:34:32+08:00 收到用户确认“实测修复成功”，并明确授权本轮提交、推送、PR 合并及同步本地 main。此项为用户实测反馈；主 Agent 未新增真实收费请求或收集该次 HTTP 正文。下述“未提交／真实服务待复测”等内容是 09:12 本地交付快照，已由本次反馈和发布流程取代；最终 Git/CI 身份以 PR 与根交接收据为准。

## 诊断证据与结论边界

输入 mobile-agent-diagnostics.zip：6,698,329 bytes，SHA256 e7aa6472e0b3ac23e7862cdb493cffba1602fb5b76bc918150309975c1bb1cee；原件未改。其 manifest 指向 1400a54、dirty=false、schema 29、Android 16；滚动日志也含较旧构建。当前构建的能力测试出现 invalid_response，但没有该次原始 HTTP 响应正文；不能用旧构建的 DEBUG 内容宣称已确定该次服务商响应根因。

本轮代码反例证明：Command Code 没有可信窗口 producer；Chat 内置 PNG 不能完整解码，Responses 的 PNG 只有 1×1；图片默认输出预算 64 tokens 无法可靠覆盖推理模型。公开 adapter 回归初始 10 项中 8 项失败，随后修复并扩充至 36 项全部通过。预算不足是确定的探测设计问题，但不能据此证明本次线上 invalid_response 必由它导致。

[Command Code 官方文档](https://commandcode.ai/docs/provider)提供公开模型目录。本轮匿名 GET https://api.commandcode.ai/provider/v1/models 得到 85 条记录，deepseek/deepseek-v4.1-flash 的 context_length 为 1000000；未使用用户 Key、未调用生成接口。公开目录快照 SHA256 db2ffc54fe5598edce7db3f279a493e6e045a5366c1be1c5ba299a351262bcb3。

## 已实现

| 请求 | 修复行为 | 关键约束 |
| --- | --- | --- |
| Command Code 自动窗口 | 两种 OpenAI adapter 接入公开目录；保存未知 AUTO 配置与连接/能力测试后补取；同目标改名或离线保存保留已有目录值和来源 | 精确 HTTPS 域名/端点、无凭据/自定义头、最终 URL 不变、2 MB/10 秒上限、唯一精确模型 ID 与正整型窗口；既有 revision/目标 CAS 拒绝迟到覆盖 |
| 图片能力测试 | 共用有效 128×128 红蓝 PNG；图片请求上限 1024 tokens，尊重较小的手动预算；明确预算截断记 UNKNOWN | 不自动扩大预算或收费重试；畸形响应仍 FAILED；普通图片 4xx 不能直接证明模型不支持图片；正常有正文响应才 VERIFIED |
| 结果和编辑状态 | 目录刷新移出收费测试 busy 等待；目录/本地重读失败不改写已完成的测试和 charged；编辑页显示真实目录窗口而不灌入声明字段 | 手动/声明优先；更换目标立即显示未知；目录窗口不得冒充用户声明 |
| 页面简洁性 | 删除重复英文眉题和 CAS/BYOK 存储副标题；缩短服务商、Agent、知识库、技能、设置的重复说明，降低标题区高度 | 保留收费、外发目标、权限、持续授权、Shell 风险、UNKNOWN、防重放及 AGPL 版权说明 |

主要实现：CommandCodeContextCatalog、CapabilityProbeImage、ImageProbeOutcome、两种 OpenAI adapter、ProvidersViewModel、ProvidersUi 和 MainScreens；其余页面仅收敛文案与标题。19 个变更源文件完整摘要、实际内容与基线补丁均在交付目录；未修改数据库 schema、依赖或许可证防线。决策见 [ADR-0021](../../adr/0021-provider-catalog-and-image-probe.md)。

## 实际验证

工作目录 E:/mobileAgentRuntime；Windows 启动器调用 D:/Git/bin/bash.exe，uname 为 MINGW64_NT。命令输出均留在 .private/commandcode-vision-ui-20261005/；不把 setup、合成响应或模拟器结果记为真实服务商验收。

| 层级 | 命令或范围 | 实测 |
| --- | --- | --- |
| 初始协议反例 | provider-api 的 CommandCodeProviderRegressionTest | 修前 10 项/8 失败；只修 PNG 后解码 2/2 绿；完整修复后 10/10，再扩充至 36/36 |
| 最终 Provider 套件 | provider-api/build/test-results/test | 272/272，0 失败/错误/跳过 |
| SQLite 套件 | data/sqlite/build/test-results/test | 375/375，含原有窗口 revision/目标 CAS 边界 |
| Provider 文案 | feature/providers 的 Debug unit | 6/6 |
| Android 单元 | Debug / Release / Review | 每变体 216/216，共 648 变体记录，0 失败/错误/跳过 |
| 最终门禁 | ./gradlew.bat reviewGate :app-android:assembleDebug :app-android:assembleDebugAndroidTest --offline --dependency-verification=strict --console=plain | BUILD SUCCESSFUL，2m11s，1132 tasks（110 executed / 1022 up-to-date），exit 0；含 check、licenseGuard、Lint、review 安全/依赖/成品/SBOM/provenance |
| 模拟器 | Android 14/API34 x86_64，专用无窗口 AVD；ProvidersViewModelConnectionDeviceTest、ProvidersTypedUiTest、GlobalConversationUiTest、ExecutionAuthoritiesUiTest | 43/43，20.369 秒 |
| 独立协议/安全复核 | 内置浏览器 DSH，DeepSeek V4.1 Flash，实际 E 仓库与同分支/HEAD，只读 | 首审 3 个 P2 均修复；补充两处观察亦修复；最终 PASS，19 个完整源 SHA 与交付一致 |
| 收口许可/文档 | licenseGuard、REUSE、diff 与新链接 | licenseGuard BUILD SUCCESSFUL（6s / 6 tasks）；REUSE 856/856；diff 无错误；新增链接检查通过 |

设备回归补充证据：离线保存目录窗口修前 expected 1000000 / actual null（1 项真实模拟器红测），修后通过；目录超时用闩确认 metadata 分支实际执行；手动覆盖在目录等待期间能保存，旧结果不能覆盖。编辑页目录标签修前 1 项失败，修后与换目标显示未知一起通过。320dp 费用确认测试验证弹窗前零派发、取消零派发、确认一次派发；实测截图 image-probe-consent-320dp.png。测试均使用内存 adapter/本地 fixtures，不调用真实 Provider。

独立审阅发现的首轮测试 harness 默认 MANUAL 导致 metadata 空跑已修为显式 AUTO，并增加执行闩；没有把原空跑当作有效窗口验证。审阅者未运行构建或设备测试，主 Agent 完成上述真实执行。Jev 增量路由返回 invalid_request，未循环重试；沿用已验证 DSH Flash 独立复核路径。CodeGraph 已按最终源码同步 2 个变化文件；首次索引片段截断/查询不相关时仅补读遗漏区域。

## 本地安装包

[Debug 签名 review APK](../../../.private/commandcode-vision-ui-20261005/delivery/mobile-agent-runtime-review-commandcode-20261005-debug-signed.apk)：
178,858,186 bytes，SHA256 c453a91a5131e67b4495eba2fa16fa37406618d4cf09eb380decf1049e19d562。

review 变体，debuggable=false、allowBackup=false，Android Debug 同证书（315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788），v2/v3 单签名；zipalign 16 KB 与 46/46 原生库 LOAD/RELRO/ZIP 检查通过；成品第三方 notices 校验通过；SBOM 171 组件，provenance 绑定基线 1400a54、dirty 源码及未签名成品/SBOM 哈希。签名后成品另由 delivery.json 绑定，不冒用未签名 APK 哈希。

专用模拟器安装最终签名包 Success，MainActivity 冷启动 Status ok，611 ms；已目视检查实际启动截图。此为 review 变体启动检查，不扩大成 Provider/真机/后台长稳验收。

[交付报告](../../../.private/commandcode-vision-ui-20261005/delivery/delivery-report.md)与[证据包](../../../.private/commandcode-vision-ui-20261005/delivery/commandcode-vision-ui-evidence.zip)含红绿日志、最终门禁/JUnit、19 源摘要和补丁、独立审阅、签名/对齐/notices/SBOM/provenance 与截图。证据包不包含用户原始诊断日志、凭据、旧 HANDOFF 私有历史或无关附件；APK 单独交付。

## 剩余边界与下一步

- 已完成本地实现、离线回归、模拟器与独立复核；未运行用户 Key/真实 Command Code 的付费能力探测，未做原 Android16 真机验收。线上图片仍须在该修复包实测，不能宣称服务商已恢复或全量验收通过。
- 安装该包后，对原配置保持/选择 AUTO 并保存即可免费补取目录；编辑页和列表应显示相同窗口及服务商目录来源。显式手动覆盖仍优先。
- 图片复测必须经现有费用确认，可能分别产生连接/流式/工具/图片费用；结果 UNKNOWN 时由用户决定下一步，本地代码不自动收费重放。若仍失败，保留该次响应类别与新诊断包进行定向分析。
- 本轮未 commit/push/merge，不沿用已经完成的前轮发布授权。原 4 份报告/计划摘要未变，HANDOFF 原文备份保留；主 Agent 仅追加本任务记录。没有新增 C 盘 Git 工作树或改动用户配置/凭据。
