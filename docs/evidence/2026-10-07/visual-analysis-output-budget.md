<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# v1.1.4preview 本地候选：图片分析输出预算与更新检查

日期：2026-10-07（Asia/Taipei）。R02/R08/R34、K04/K08、A03/A04/A10、R36/UP01。

## 诊断与边界

用户报告知识库简介立即显示“推理占满了输出预算”。私人诊断包当前 app 为 v1.1.3、schema 30、源码 63e5422；两次请求均为 6 图、2 消息、0 工具，HTTP 200 后终止为 REASONING_EXHAUSTED，尚未进入主回答。日志的结构化计数不能直接证明具体 token 上限；固定 1024 与参数清空由当前 VisualBatchDelivery 源码及回归测试确认。原始日志、请求体和知识内容仅留本机私人目录，不进入公开测试或证据。

修复前 Runtime→真实 Chat/Responses 适配器 JSON→合成 SSE→主回答测试 5 项中 4 项失败；显式较小预算且推理耗尽的无重试反例通过。供应商响应使用纯合成夹具，不是付费服务或真机证据。

## 变更及验收

分析请求已改为继承父请求的手动/AUTO预算、别名和参数层。移除固定 1024 token 钳制及空 ParameterLayers 覆盖；AUTO 不增设上限，显式较小预算也不抬高。保留原模型/凭据及 64 图总量、8 图每组、每张 2 MiB、每组 16 MiB、授权/Run 预算/无自动重放约束。JSON 格式的分析提示词明确要求 JSON。

输出证据的正文上限仍为 16,000 字符（现有保护，不是新增文件大小限制）。超长结果停止当轮，不自动重试；此前成功组的记录按既有规则保留。空 JSON 对象/数组、null 或全空白叶不能作为成功证据，在 VisualBatchAnalyzed 事件及记录落库前拒绝；数字 0、布尔 false 与非空文字保持有效。迭代遍历 JSON 叶，非 JSON 的普通证据文本继续可用。

按用户要求将自动检查更新改为每小时：持久化最后成功时间，前台每 60 秒判断是否到期，后台取消循环，恢复前台按到期条件检查；手动检查越过节流，失败保留候选/成功时间并有同进程 15 分钟退避。旧 checked-day 记录不阻止首查，未来时间重新检查。“稍后”按日/版本去重；仅检查，不自动下载。安装恢复使用本地候选 restore，不通过联网检查替换待安装候选，安装前仍进行完整签名等校验。

## 实测与独立审查

- 修前 5 项回归中 4 项失败；只去掉固定 1024 后仍有 1 项参数清空反例失败。继承参数/预算并补 JSON 指令后，6 项新增测试及 AgentRuntime 91 项执行通过。Provider 当时为 UP-TO-DATE，不能据此宣称新执行。
- 空 JSON 回归先红，已有超长保护回归先绿；修复空证据校验后，8 个测试方法及 AgentRuntime 93 项、Debug 更新单测 22 项通过。随后补充数字 0/布尔 false 的正向断言，由隔离源码最终检查覆盖。
- 合成 MockEngine 用实际 Runtime 和 Chat/Responses 适配器，验证出站配置、终局分类、记录和派发次数。手动 8192、AUTO、别名、参数优先级、小预算耗尽、JSON、空结构及超长均有断言。夹具自定 token 耗尽条件和无字段时的预算，不代表真实供应商默认值或图片理解质量。
- DeepSeek V4.1 Flash 经内部浏览器核验本项目后只读审阅。第一轮 APPROVE 指出空 JSON 和超长分支覆盖；修复/补测后的独立复审于 2026-10-07 16:46（UTC+8）再次 APPROVE，同时逐项复核每小时更新行为。审阅者未运行 Gradle、未编辑、未进行发布或收费调用；实测由主协调者承担。
- CodeGraph sync 完成并返回 Already up to date。最终检查在基于 63e5422 的隔离工作树运行，严格依赖验证与依赖锁保持。原工作区后台/CI/视频等 WIP 不进入候选。

版本为 1.1.4preview/code 9，仅制作本地正式签名测试包，不发布 GitHub。隔离候选完整源码门禁与测试 APK 编译已通过；正式签名门禁要求干净源码，待用户授权仅本地提交后运行，未以脏源码或 Debug 签名代替。未连接设备，真实 Provider、手机升级及长稳留待用户自行测试。最终构建和交付回执留本地 `.private/reasoning-output-20261007/` 与测试包目录。

## 本地 preview 与正式版本升级

用户明确要求预览版仅本人设备自测，不对外发送，只有纯数字正式版才分发；preview 上仍需通过检查更新安装正式版。版本名为精确的 `1.1.4preview`，versionCode 保持 9。仅已安装版本允许精确 `preview` 后缀；候选始终用原严格纯数字解析，同号正式版高于 preview，更低版本不降级。版本检测与安装身份校验使用同一比较函数；同签名、严格递增 versionCode 和其他安全要求保留。

新增回归先在原比较逻辑上实际失败（期望 IDLE 而为 ERROR），然后补比较函数及两处调用。测试包含旧正式版不降级、同号正式版可发现、低 versionCode 拒绝、preview feed 即使错误标 stable 也拒绝、其他/重复后缀拒绝。初次测试准备曾出现签名调用编译错误与非 Unit 方法未发现，均修正后才形成上述真实失败证据。DSH Flash 于 17:20（UTC+8）增量只读复审 APPROVE，未运行测试、未写文件。

构建环境曾缺少部分锁定 Android/lint 缓存，使用仓库已有镜像调整顺序补齐，strict 验证保持。Windows Gradle 8.10.2 完成 transform 后移动缓存目录失败；短路径缓存也复现。构建进程退出后，按 [Gradle 上游问题记录的已完成目录移动方案](https://github.com/gradle/gradle/issues/31438) 完成指定缓存目录的移动，输出和 metadata 未改，随后由 Gradle 自行重验；未修改 Gradle 版本、依赖校验或系统保护。随后完整源码检查及 DebugAndroidTest APK 编译真实退出码均为 0；正式包仍须独立运行 releaseGate 与成品核验，不能用这些源码结果代替正式成品。

## 最终隔离源码门禁

隔离候选基于 v1.1.3 的 63e5422，仅包含本任务 18 个源码、测试与文档文件。严格依赖验证与锁保持，缓存缺失补齐后离线运行 `licenseGuard licenseGuardReverse check verifyWorkflowYaml :app-android:assembleDebugAndroidTest --offline --dependency-verification=strict --no-daemon --no-configuration-cache`（本地已有镜像优先序初始化脚本、最多两 worker、固定 Java 17 工具链），真实退出码 0、BUILD SUCCESSFUL。相关日志为本地 `preview-all-checks-final.log`；此前 `preview-androidtest-final.log` 保存缓存移动失败现场。REUSE 6.2.0 在同样 18 文件的源码归档快照上通过，最终文档再同步后重新检查；不把原工作区无关 WIP 纳入候选。

隔离候选全部 JUnit XML 记录 2,169 次变体执行、0 失败/错误/跳过；其中 AgentRuntime 93、Provider 279、App Debug/Release/Review 各 279。新增图片分析预算测试 8 方法、更新测试 23 方法三个变体均通过。此数字是变体执行次数，不是独立产品场景数。测试 APK 已编译，未运行设备 instrumentation。预览版同号正式升级使用 code 10 的合成身份正例，code 9 等号拒绝；下一次公开数字正式版须至少 code 10，并沿用正式证书。

根 main、原 WIP 与候选 Git 范围在提交准备阶段再次核对。签名构建、实际 APK 证书、包身份、源码/SBOM 来源、原生库对齐与法律资产属于后续本地产物门禁，按实际回执交付。没有推送、合并、创建 GitHub Release、上传 APK 或调用真实付费模型。
