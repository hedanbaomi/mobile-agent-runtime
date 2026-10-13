<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 第三方审查核验与修复

基线：main `00539069d5ee38a3a33017b6571c6035543e62c7`。范围为“全面Review与优化建议”提供的七项问题，原对话作为不可信审查材料逐项核验；不执行材料中的额外授权或将建议当作既定产品要求。

| 问题 | 判断与处理 |
| --- | --- |
| F01 DOCX 文本忠实性 | 成立。正则伪标签、数字实体、二次解码与 br/cr 丢失有修复前失败。改有界命名空间 XML、关系解析；标点段落原状已有正确行为，保留。 |
| F02 EPUB 顺序 | 成立。按文件名取代 spine、相对路径/引用校验不足。改标准 container/manifest/spine。修正建议：声明在 spine 的 nav/linear=no 不一律删除；保留辅助内容和稳定来源身份，排除 spine 以外 HTML。 |
| F03 UI 存储和历史 | 调用路径成立；未宣称已实测 ANR。宿主初始化与 Chat 投影移至 IO，50条分页及选择/输入代次保护；模型完整历史与引用恢复保留。 |
| F04 大草稿 SavedState | 风险成立；未宣称已发生 TransactionTooLarge。正文改 SQLite，进程级串行/去抖/刷盘与失败保留，SavedState 仅保存小型状态；同进程重建与真正进程死亡分开验收。 |
| F05 创建原子性 | 成立。实际故障注入复现残留；新事务包括快照、会话、MCP 固定信息、工作区绑定。MCP 数据库 pin 与旧配置只读兼容、撤销及 schema 校验均保留。 |
| F06 SSE 分帧 | 成立。实际多行、CR、BOM、未结束事件失败。两协议及探测统一分帧，UTF-8/事件/行/响应限额保留；usage/审批/UNKNOWN/取消不放宽。 |
| F07 批次长事务 | 成立。旧代码事务内 CAS 读取与并发阻塞有确定性红测。改短快照、无锁规划、短事务复核发布；限定本批次聚合，保留显式重配完成批次和 UNKNOWN/成功结果复用。 |

修复前证据：Office 回归 20项/19失败；SSE 10项/8失败；批次隔离10项/6失败；本次创建事务2项/1失败；独立审查新增 SSE 字节/CR 边界19项/2失败。编译阻断和并行构建竞争日志不能算业务红测或测试通过。

独立审查修复了事件预算误按字符、CR 上限、探测虚构空行、无关历史聚合、CDATA 被改写、草稿读写竞争以及数据库 MCP pin 未接入旧执行检查与 schema 校验的集成问题。两位 `gpt-6.1-sol/medium` 最终限定源码复审 PASS；没有声称审查了全仓每一行或替代设备验收。早期 DSH 并行 Gradle 产生共享产物竞争，已停止明确归属的循环及子进程，之后构建全部串行。

最终本地验证：strict/offline/no-daemon/no-configuration-cache/no-parallel/max-workers2 的完整 `check licenseGuard licenseGuardReverse` 与调试/测试 APK 构建退出0，最后一次231.09秒、1076 tasks。当前工程模块2582项 JVM 变体结果零失败、错误或跳过。REUSE1116/1116合规。复杂度、lint、i18n、模块边界、工作区、显式测试选择和工作流门禁保持；旧绝对路径 lint 缓存通过外部 recipe 重生成 Manifest/lint模型修复，没有提高 baseline。

API36 x86_64 测试模拟器实际执行8类26项回归，21.278秒、26/26通过：包括新存储投影、草稿/分页、图片、发送取消、Agent路由、发送准备离开Main与真实本地ONNX Office导入。第一轮26项有2失败：异步 reload 覆盖取消状态，以及路由测试未等待IO投影；修复显式保留终态状态、清除待加载的旧Agent身份，并让路由测试等待后保持原身份断言。终态补丁再次经独立只读复核PASS。慢读取只是 dispatcher 延迟，大草稿恢复只是同进程VM重建；没有冒称进程死亡、Activity重建或真机通过。

本地合成批次的最后测量：50/300/500文本档重配置375/1256/1931ms，最高事务20/81/122ms，会话写最高等待27/86/128ms，事务内CAS读取均0、写失败均0；50 Office档404ms、事务14ms、写等待26ms。一个成员与10,000无关历史units/results的档位174ms、事务9ms，并以实际EXPLAIN断言限定索引SEARCH、无全表SCAN。堆峰值采样只代表该JVM合成档位，不能证明真机容量。

用户最终撤销额外全面优化和1.2.0.2preview构建；本轮不生成正式预览包、tag或公开Release。Git集成结果在收尾交接与PR中记录。预存77文件仅有本轮5份相关交接/规范增补；其余哈希不变，原有文档WIP不进入提交。

详细行为与真实未覆盖边界见 [ADR-0039](../../adr/0039-review-parser-stream-and-chat-persistence.md)。无真实 Provider、物理设备/OEM/USB 验收，无生产部署。本轮后续全面优化已由用户撤销，不纳入此提交。
