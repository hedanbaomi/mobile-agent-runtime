<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 原图传输与分组预算修复

日期：2026-10-07（Asia/Taipei）。关联 R02/R08/R34、K04/K08、C26。基于 v1.1.2 合并源码 f6d36ee1b3675dfd259a1d8d80975a0aa9fe3b93；目标版本 1.1.3/code 8。

## 原因与行为

工具结果报告七处原图未提供，原因 RUN_IMAGE_BUDGET_EXCEEDED。旧 RunTools 用整个 Run 的引用注册表计数，固定四张上限；默认上下文图片预算同为四张。诊断中的出站事件图片数为零，与该本地阻断相符。能力探测的聚合成功不能证明具体一次请求已经传输图片，模型名称也不能代替 endpoint 能力配置。私人诊断包和原文未纳入源码或公开资产。

修复将未指定的 Run 总预算提高至 64 张，保留显式较小预算。单请求最多 8 张、16 MiB，每张最多 2 MiB。自动检索、工具和历史使用统一 acceptsImages() 能力判断。先传授权及哈希已验证的 CAS 元数据，仅在当前组并行读取和编码原图；各组按顺序使用同一选定模型分析，工具禁用，明确成功后才继续。分析记录包含来源标识和哈希，以不可信证据交给主请求综合，并随对话保存。

成功组的记录只投影一次；后续主请求和历史复用分析记录，避免重复附加全部原图。每组计入 Run 模型轮数、输入、时间及用量预算，实际派发前重新验证当前组来源。撤权、来源变化、超量、未知结果和失败均阻断后续派发；已成功组保留，未知请求不自动重放。文本模型和用户显式文本降级不读取或附带历史原图。工具卡展示实际 warning 或成功传输张数。设计与影响见 [ADR-0030](../../adr/0030-request-scoped-visual-budget.md)。

## 验证

- 两种 OpenAI 兼容协议的实际出站 HTTP 测试核对原始 Base64、来源及顺序；64 张形成八个分析请求，每次不超过八张，主综合请求仅使用记录。超过 64 张、单图/聚合超限、错误哈希、加载后撤权、未知结果和取消均有负向测试。
- 元数据阶段 64 个最大图片引用不读取全部图片；组内并行与组间串行有实际并发计数断言。运行中来源哈希去重及跨消息记录去重单独验证。
- API 34/x86_64 模拟器的 ChatVisualTransferDeviceTest 八项通过：七张小图、七张接近 2 MiB 的图、64 张工具图、endpoint IMAGE、旧 image 标签、文本模型、显式降级和新 ViewModel 重载历史后文本降级。HTTP 接收端逐张对比 CAS 原始字节，检查记录与 citation 持久化。这是合成 loopback 服务，不能代替供应商接收验收。
- 独立只读审查发现的历史降级绕过、成功记录缺少 provenance、跨消息重复记录三项已修复。最终派发前来源复核经独立只读复审，无阻断项。准备阶段取消可能被界面保守记录为 UNKNOWN_OUTCOME，不会自动重放。
- 最终发布工作树 strict/offline 的 licenseGuard、licenseGuardReverse、check、CI pins、依赖锁/验证、workflow YAML 和 Debug/测试 APK 构建全部通过（6 分 21 秒，1070 tasks）。299 份 JUnit XML 共 2130 次变体执行记录，零失败、错误或跳过；其中包含 Gradle 的有效缓存及 up-to-date 结果。REUSE 对显式源码快照 956/956 通过。最终源码的 API34 定向设备回归 24/24 通过，包含上述八项图片传输及 RunToolsEnrich/Replay、输入预算和上下文压缩/UI。CodeGraph 最终同步成功，现有索引已最新。

## 交付边界

正式发布必须先普通提交、推送、PR 门禁和合并，然后从干净的合并源码运行 releaseGate，核对正式证书、arm64-only APK、源码归档、许可、SBOM、provenance 及附件摘要，完整上传后才公开为 latest stable。Git 与发布的实际回执记录在发布 verification 附件中。

尚未验收：真实付费 Provider 的图片接收及理解、arm64 实体手机升级、其他 Android API/OEM 的服务存活、物理 USB 拔线与长稳。根工作区现有后台、CI、视频和共享文档 WIP 保留；私人附件、原始日志和签名秘密不发布。
