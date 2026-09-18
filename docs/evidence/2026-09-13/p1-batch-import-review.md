<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# P1 知识库批次导入：DSH 实现复核与修复验收

日期：2026-09-13，Asia/Taipei。当前授权为复核并修复“设备管理器权限用途”最后两轮确定的批次导入需求，完成源码 commit/push；HANDOFF 与 docs 仅本地。本文替代同日 DSH 初稿的现行验收结论，原稿保留为历史。

## 结论与范围

DSH 初稿不能直接验收：正常导入确认框被嵌入不相关的 Embedding 对话框，目标状态未发布到 UI，暂存恢复和未知视觉结果持久化存在缺口。上述问题及实测发现的紧凑 PDF 解析问题已修复。独立只读复核通过；Android API 36、真实硅基流动视觉小样、294 项混合批次暂停重启恢复均有证据。

本轮没有重跑前轮全部 42 条产品路径，也没有声称原始 294 份 PDF 全部付费视觉处理完成。DeepSeek 官方基础连接沿用前轮“未复现”历史结论，本次专项没有新 DS 实测证据。严格视觉聊天与显式 OCR 文本检索区分记录。没有物理手机验收或正式 release 发布。

## 发现与修复

| 优先级 | 复核发现 | 最终修复与证据 |
| --- | --- | --- |
| P1 | 确认框在 Embedding 配置对话框内部，正常 Add files/ZIP 不能显示；仅 JVM 测试无法发现 | 移至独立 UI 分支；旧 APK device red 重现，修复 device green；对话框固定目标和费用/材料提示 |
| P1 | loadSnapshot 已读到视觉模型，reload 的 state.copy 没有发布三个 Vision 字段，页面仍显示未配置 | 同步 configured/label/fingerprint；新增 ProfileRepository→KnowledgeViewModel 异步状态刷新设备回归；真实 CHAT 角色图片模型可直接选择 |
| P1 | 暂存成员未封口即恢复处理可能错判完成/授权范围；重启恢复遗漏源队列，同名输入可能重用错项 | schema 20 持久暂存清单、staging_complete 栅栏、完整 source key 幂等、进程恢复只续未完成；文本批次无视觉仍发布 |
| P1 | ZIP 暂停后继续依赖外部源，原归档修改会影响恢复范围 | 固定本地原件快照；设备实测暂停后外部 ZIP 改变仍使用原快照 |
| P1 | 进程在请求派发与结果持久化之间退出可能无声重发；结果成功但发布失败也不能重复收费请求 | backend 前持久 UNKNOWN 标记；成功结果先保存，可复用；未知结果要求显式风险确认，取消/暂停不隐式重放 |
| P1 | 有效图片 PDF 使用紧凑 >>endobj，解析器边界拒绝导致 Missing PDF page tree | 仅 endobj 接受 PDF 关闭分隔符，保留 stream 二进制跳过边界；parser fingerprint v15；实际 Pillow PDF 红绿与 57 个解析测试 |
| P2 | Markdown 丢失图片原件仍误导为缺模型 | MISSING_VISUAL_SOURCE 独立阻塞原因及明确文本降级恢复，不伪装完整视觉成功 |
| P2 | 294 项页面明细埋没操作；已完成复制仍因 legacy item COPY 显示 Copying | 默认折叠成员和文档；主进度/暂停在首屏；完整暂存后派生 PROCESSING，未封口仍 COPYING；旧断言同步 |
| P2 | 只有 Worker 启停日志，缺真实派发、响应、保存链路 | 实际 backend/persist 调用位置写闭合 phase/reasonCode；opaque batch/item refs；无密钥、正文、文件名或路径 |

保留一次批次确认、固定服务商/模型及版本指纹；没有把图片能力开关当作未来全部资料的无限外发许可。授权目标变动要求重新确认，处理途中不得静默换模型。暂停保持数据与结果，不靠取消 WorkManager 杀死请求来假装已安全结束。

## 验证记录

证据根目录：E:/mobileAgentRuntime/.private/vision-batch-review-20260913/。测试 worktree：E:/mobileAgentRuntime/.private/qa-vision-batch-review，base 7caebd3eaef88b7282be11c6e5689408f77d2fa8。

| 验证 | 结果与文件 |
| --- | --- |
| 独立只读协议/迁移/权限复核 | DSH DeepSeek V4.1 Flash，工作区内修改权限避免只读 Pwsh 缺陷、任务明确只读；四轮增量 PASS，independent-dsh-review.md；无凭据发送 |
| 原始 UI 红测试 | red-ui-device-retry.log，预热旧 APK 后正常入口缺确认框；首次冷 instrumentation ANR 单独记录，未当作产品根因 |
| PDF 红绿 | recovery-pdf-red.log 预期 Missing PDF page tree；recovery-pdf-green.log，DocumentParserTest 57/57；recovery-pdf-fixture-smoke.log 实际 PDF 1 页/1 图片 |
| SQLite 恢复/视觉/日志 | final-stage-green.log 及 XML：237 项；包含派发前持久 UNKNOWN、恢复不重发、已保存缓存复用、同名源键、暂存栅栏与阶段、成员授权范围等 |
| Android API 36 | final-device.log，4 个类合计 11/11 PASS，44.049 秒；UI 2、生命周期 5、暂存 3、视觉状态刷新 1。最终阶段修订另行回归见后续最终验证记录 |
| 常规与许可门禁 | final-diagnostics-gates.log 已通过 licenseGuard/check/assembleDebug；最终阶段断言旧值失败后修正，最终结果见收尾记录；reuse-final.log 624/624，REUSE 3.3 |

构建严格离线依赖验证，JDK 21。缓存 AIDL 含未转义路径与许可证 CRLF 字节漂移在临时 worktree 内修复，不纳入源码提交；第三方 LICENSE 内容与 base 完全一致。ADB XML 采集改用每次唯一文件，避免 Android dump 未更新时读取旧树；最终关键状态同时由截图与数据库交叉验证。

## 真实视觉小样与检索

生产 Android/Ktor 适配器，硅基流动 https://api.siliconflow.cn/v1，Qwen/Qwen3-VL-8B-Instruct，模型角色 CHAT 且具有 image。API key 已在测试机 Keystore，未写入提交或报告。

合成图片 PDF 内容为 ORBIT 732 与蓝色等边三角形。ReviewVisionFinal 从正常文件选择、批次确认进入处理并完成。ui/065-tap.json 记录目标/费用确认；068/070 完成；074 显示 2 个 verified evidence chunks，OCR 与图形描述正确。PDF 图片与页面为两份视觉资产，两次处理有意且不等同重复重试。

真实 Agent R2Knowledge 绑定该库，在显式工具检索模式调用 knowledge_search 和 read_document，回答蓝色等边三角形并引用证据：real-vision-rag-answer.png、ui/116-observe.json。聊天模型为文字模型，严格视觉查询先被正确阻断；用户路径显式开启 text-only mode 后完成 OCR 文本检索。此证据不宣称严格图片输入聊天已验收。

## 294 项暂停、重启与完成

原始用户 R2Full294 的 294 PDF 列表以真实 UI 核对折叠和首屏布局：real-294-collapsed.png。该历史库没有在本轮全部付费完成。

另建明确合成的 Review294：review-294.zip 含 1 个 ORBIT 733 图片 PDF 和 293 个微型 TXT。正常创建后，在复制出 228/294 个成员时点击暂停。paused-device.db 与 restarted-paused-device.db 均为同一批次 2c1cbdd6-063e-41aa-becc-c5ca171282ad、PAUSED、staging_complete=0、members=228。force-stop 后重启未自行继续。ui/145-after-review294.json 显示暂停；146 用户点击继续。

恢复后补完剩余成员并处理，2026-09-13 20:04:44+08:00 完成：294 个唯一成员全部 PUBLISHED，failed=0、unknown=0、staging_complete=1。证据 real-294-completed.png、ui/149-observe.json、completed-device.db、completed-batch-summary.json。

同批次真实视觉链路（UTC+8）：20:01:51.687 派发→20:01:57.909 成功→20:01:57.955 保存；20:02:00.344 派发→20:02:02.493 成功→20:02:02.546 保存；20:04:44.754 Worker 完成。2 次派发、2 次 SUCCESS 检查点，未知结果 0。完整脱敏链见 completed-batch-event-chain.json 与 checkpoint-final.ndjson。单元/设备故障注入覆盖在途暂停与未知结果，本真实 Provider 场景的进程终止发生在暂存阶段，不能混称在途网络重启验收。

## Git、最终构建与交接

源码提交 **dafaae379dc45e53017899a234ca92170bdd25ba**，parent **7caebd3eaef88b7282be11c6e5689408f77d2fa8**，已普通 push 到 **origin/codex/knowledge-batch-review-20260913**。ls-remote 复核完全一致，远端 main 仍为 7caebd3，未合并。21 个源码/测试文件，2757 insertions / 191 deletions；相对基线 HANDOFF/docs/AGENTS/agent/.private/.codegraph 零差异。根工作区仅回同步这 21 项，全部字节哈希一致，其他保护 WIP 零漂移。证据 commit-result.log、push-result.log、publish-verification.json、final-source-hashes.json。

最终 **licenseGuard check :app-android:assembleDebug**（严格离线依赖验证）退出 0，3m17s；final-stage-green.log。121 份 XML 共 **953 tests / 0 failures / 0 errors / 0 skipped**，含 debug/release/review 变体重复；SQLite 237、DocumentParser 57 已包含在总数中。最终 APK 另跑 KnowledgeStagingDeviceTest **3/3 PASS，35.253 秒**，final-stage-device.log。此前 11/11 设备检查与最终 3 项增量是两轮，不合称 14 个独立用例。REUSE 624/624 与 diff --check PASS。

最终 debug APK SHA-256 **07385ce7818dde779e0320a8e15df750090c3585e7cd0d00b4f5e34ad1fded93**，路径 .private/qa-vision-batch-review/app-android/build/outputs/apk/debug/app-android-debug.apk。APK 在提交前构建，元信息是 7caebd3-dirty；源码字节指纹记录于 final-source-hashes.json，除构建元信息外不声称 APK 内嵌新提交 SHA。真实 SF 294 批次执行于最后阶段显示修订之前，最终阶段差异由主机断言与 3 项设备回归覆盖。

通过 Settings → Export diagnostics ZIP → 本地 Download 正常流程导出，ui/165-tap.json 明确“已保存”。p1-batch-diagnostics.zip **869 个可解析事件，CRC PASS，10 个该批次链路事件**；密钥模式扫描 0（不是全面隐私审计）。ZIP SHA-256 **43ee370db759943dd4355c519969262d6a37afa13de0a9aedfdfcfdf82808c8e**。原始日志未清空，原生崩溃记录保留历史时间，不把历史 last-crash 当本轮新崩溃。

根目录 CodeGraph sync 退出 0：16 changed、4 added/12 modified、1530 nodes，34.1s（索引器统计不同于 Git 文件数），codegraph-sync.log。本地报告、HANDOFF、专题/ADR 更新已完成且未提交、未推送。远端 CI 状态另附只读快照，不以本地通过冒称 CI 全绿。


20:18+08:00 远端只读快照：CI [34756694962](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34756694962) 与 license-guard [34756694935](https://github.com/hedanbaomi/mobile-agent-runtime/actions/runs/34756694935) 均 in_progress，HEAD 均为 dafaae379dc45e53017899a234ca92170bdd25ba；没有等待结果，不写为远端通过。

收尾：已通过 adb emu kill 停止本轮 owned emulator-5562，AVD 数据和 DSH 实例保留；未清空诊断、未更改服务商账户或主任务模型。


## 2026-09-13 20:42+08:00：按新授权合并 main

用户明确要求将最新分支合并到 main。核验 dafaae3 的分支 CI 34756694962 与 license-guard 34756694935 均 success；创建 PR [#15](https://github.com/hedanbaomi/mobile-agent-runtime/pull/15)，普通非管理员 merge，匹配精确 head dafaae379dc45e53017899a234ca92170bdd25ba。PR 创建新触发的重复检查在合并时仍运行，没有使用管理员覆盖或关闭门禁。

GitHub 返回 MERGED，2026-09-13T12:41:22Z，merge commit **90170880e4aa8db3273eca76d6b8581aaab4194d**。fetch 后远端 main 指向同 SHA，tree 与 dafaae3 完全一致；相对旧 main 仅 21 项源码/测试，HANDOFF/docs/AGENTS/agent/.private/.codegraph 零差异。根工作区 WIP 和文档 HEAD 未切换、未提交、未推送。证据 .private/vision-batch-review-20260913/merge-verification.json。
