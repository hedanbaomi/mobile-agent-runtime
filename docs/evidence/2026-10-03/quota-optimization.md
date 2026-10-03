<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-10-03 周额度约束优化

本记录为本轮交付前源码快照。用户继续优化，并指定 Codex 周总额度新增消耗目标不超过10个百分点、绝对不超过14个百分点。09:20 UTC 基线接口0%，09:41 UTC接口1%；百分比为账号共享读数，有上报延迟/取整，不当作逐请求成本测量。达到7个百分点前停止扩展并预留收尾；没有消费额度重置或购入操作。

基线 main 343b9113b27e6ae72bb859f6ff8f3f3316dc98ee，分支 codex/quota-optimization-20261003，复用独立 C checkout；主 Agent 唯一负责源码、文档、验证和普通 Git/Review 交付，E根 WIP保持保护。

## 已实现

1. KB 左联接显示汇总覆盖存活库和存活文档，包括零文档库，保留创建顺序；256库从旧257次查询降为1次。
2. 选中 KB 导入任务通过 SQL 参数筛选后排序/物化，旧全局读取和统一decoder仍在；2,003行旧全局解码对比新选中3行。独立复审发现缺少匹配索引，本轮补充 Schema v29 非唯一索引 idx_import_jobs_kb_updated(kb_id, updated_at DESC)，旧迁移调用截止版本保持28；索引查询计划、同时间戳顺序、回滚和升级保留由独立迁移用例验证，设备耗时尚未测量。
3. 会话 workspace 显示标题仅一次刷新内复用；1,201个Thread共享5个workspace时，标题解析5次，binding仍各自读取。缓存不跨刷新，不影响执行/授权校验。

## 已验证及待验证

- 严格离线定向 Gradle：data:sqlite:test（KnowledgeDisplayQueryTest 7项）与 app-android:testReviewUnitTest（ChatSessionWorkspaceLabelsTest 5项），BUILD SUCCESSFUL 1m09s；真实 XML12项，0失败/错误/跳过。
- 覆盖空集合/零文档、重复名称/顺序、软删除、任务字段/UNKNOWN_OUTCOME/同意/视觉缺口、绑定参数、大列表物化数量、损坏元数据隔离与拒绝、刷新范围缓存/失败/null/空标题/重命名/删除/恢复。
- 完整严格离线check/reviewGate通过（2m58s；1086任务137 executed/949 up-to-date）；254份现存JVM XML共1812测试记录，0失败/错误/跳过。新12用例在SQLite及app三变体共22条执行记录，保留up-to-date历史XML，不把全部1812条说成重新执行。REUSE832/832通过。初始6文件经 gpt-6-luna/xhigh 独立只读复审 APPROVE_SOURCE_REVIEW，SHA256与本地文件一致；其非阻断索引建议已纳入有界增量，补充独立迁移复审亦APPROVE，12项源码/测试/ADR摘要均与主Agent保存值一致。远端CI/merge、干净来源APK仍待实际收据。
- 新增索引迁移 v28→v29，决策见 ADR0020；DDL仍在原事务内，保留旧绑定投影截止版本。技术方案及验收增量已同步。索引后的SQLite全套、独立迁移复审及严格完整门禁均通过，详见下方增量收据。
- 未做物理设备、真实付费Provider、帧耗时/ANR因果或长稳；批次展示读合并、整个Chat刷新异步化尚未实现。

## DSH 工作区选择纠正

首条有界只读候选审查的UI被误选为python test。任务已结束，展开的所有8条命令都显式cd到正确C checkout，首条输出核对了实际root、HEAD343b911与分支；没有文件修改命令。随后新任务入口通过menuitem明确改选mobileAgentRuntime并保存截图。正式复审要求先检查默认root属于本项目，再检查C源码root；不得把显示名称当作路径核验。该事件的UI/执行记录单独保留在本轮外部证据。

外部证据目录为本聊天visualizations下quota-optimization-20261003，原附件和私人材料不入本记录或新证据包。最后交付PR、合并SHA、干净来源/签名/16KiB/notices/SBOM/provenance和额度读数另记交付收据。

DSH正式复审的首个同名mobileAgentRuntime入口实际为旧skill-uninstall-announcement-layout worktree（HEAD1e9c81f），按默认路径门立即停止并未读取待审6文件；Jev重新建议luna_xhigh（实际jev-1.13.0），现改用gpt-6-luna/xhigh独立只读复审。另一同名入口已逐一明确选择并核验默认root=E:/mobileAgentRuntime、HEAD7c0f77d，与受保护根工作区一致；保存默认路径核验截图。所有实现与门禁仅来自已确认的C交付checkout。

## 索引增量本地门禁收据

Schema v29增量的SQLite全套通过（1m12s，21任务5 executed/16 up-to-date），新增3个独立索引用例及1个未绑定迁移回归均0失败。完整严格离线licenseGuard正反/check/reviewGate通过（2m47s，1086任务140 executed/946 up-to-date）；255份现存JVM XML共1816记录0失败/错误/跳过，新16项用例跨app三变体共26记录。REUSE834/834通过。初始6源文件和6项索引源/测试/ADR的本地SHA256已保存；补充独立迁移复审APPROVE，原P2缺口已关闭；reviewer只读核验12项文件摘要，没有独立运行主Agent的测试。真实用户旧库、物理设备、Provider与耗时/长稳没有新验收。
