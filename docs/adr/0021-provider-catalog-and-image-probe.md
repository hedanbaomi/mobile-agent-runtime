<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0021：可信服务商目录与图片能力探测

日期：2026-10-05（Asia/Taipei）。关联 R02/R03/R12/R18、A03/A04/A10、U02/U05。

## 背景

1400a54 的诊断包记录一次能力探测 invalid_response，但没有该次请求正文，不能从较早构建的 DEBUG 内容推断本次响应。代码复现则确认：Chat 内置 PNG 无法完整解码，Responses 使用 1×1 PNG；两种图片探测默认仅给 64 tokens。Command Code 官方公开模型目录包含 context_length，但应用仅接入硅基流动的自动窗口读取。

## 决定

- Command Code 使用[官方文档](https://commandcode.ai/docs/provider)指定的公开 https://api.commandcode.ai/provider/v1/models。只有 HTTPS、精确域名、默认 HTTPS 端口、/provider/v1 基址且没有 userinfo/query/fragment 才启用。此 GET 不解析或携带 Provider 凭据及自定义头，不调用生成接口。最终响应地址必须仍为该目录，非 200 不采信。
- 读取最多 2,000,000 bytes，10 秒超时。仅匹配唯一、精确模型 ID 的正整型 context_length，拒绝重复、字符串数字、溢出、缺失与畸形内容。未知代理与其他普通 OpenAI /models 不自动获得此可信来源。
- 两种 OpenAI adapter 都实现 metadata 端口。Android 保存 AUTO 未知窗口及显式连接/能力测试后尝试补取。沿用原有 provider/端点/model 目标和 revision CAS：不会覆盖手动窗口、已有声明或晚到结果对应的旧修订。目录不可用仍保持未知，不改变已经完成的付费测试结果。测试后的目录刷新使用独立任务，不延长编辑 busy 状态；同一目标上的已有目录窗口在无关编辑或离线保存时保留原值、来源和 checkedAt。
- 两协议共用可完整解码的 128×128 RGB PNG，左红右蓝。图片请求最多 1024 输出 tokens，尊重更小的手动预算；Chat 现有参数别名夹取规则保留。连接和其他能力的原预算不变。确认页提示各请求可能收费和图片上限。
- 有效响应明确因输出预算截断时，图片能力记录 UNKNOWN/inconclusive，supportsImages=false；畸形响应仍 FAILED。未知结果不得自动发更大的收费请求。HTTP 4xx 对图片不直接证明模型不支持图片，两种协议均保留 UNKNOWN。此探测验证传输与响应，不替代真实模型图像理解质量验收。
- 页面缩短操作说明并移除重复英文眉题与存储实现副标题，保留关键目标、费用、权限、危险模式、未知结果和许可证内容。窗口来源区分服务商目录与用户声明。编辑页显示匹配当前目标的目录值，但不把它自动填成用户声明；换目标立即显示未知。

## 替代方案与边界

不从模型名字猜窗口；不信任任意代理的元数据；不为探测关闭用户推理配置；不自动增费重试。没有使用诊断包中的凭据或运行真实付费请求。真机/Command Code 线上能力仍需使用修复构建复测，离线回归不能记为线上通过。

## 验证

公开 adapter 回归覆盖解码/颜色/尺寸、预算、UNKNOWN 与畸形失败、来源及目录信任负例。Android 流程覆盖保存、旧 AUTO 配置补取、手动覆盖竞态及目录超时与连接状态隔离。具体结果见 [本轮证据](../evidence/2026-10-05/commandcode-vision-ui.md)。
