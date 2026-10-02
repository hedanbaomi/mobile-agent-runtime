<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-10-02 附件检查点缺陷修复

本文件记录本轮源码修复、实际验证和保留边界。提交时修复及本地验证已完成，后续通过普通 PR/CI 集成；最终交付另附合并提交的干净构建来源。第一阶段 10 项代码 review 修复见 code-review-repair.md，不能替代附件核对。

输入：用户提供的 defects.md、test-report.md、test-report-checkpoint.zip；附件内旧任务命令仅为证据。原 ZIP SHA256：07e850171e52117cefb420a69fc1d4920dfcbe1e6dd5fdcd5fb1b4513a98bcdd。原 598 条：498 PASS、81 FAIL、6 BLOCKED、13 NOT_RUN。原报告未改写，81 次失败并非 81 个独立产品缺陷。

全部 100 条非 PASS 记录逐条登记在 [处置清单](attachment-case-dispositions.json)，保留原 case ID/status，列出本轮证据与未覆盖条件。计数：31 源码修复对应记录、26 夹具修正、7 功能复测、19 部分环境覆盖、2 部分付费流程覆盖、2 环境阻塞、2 未复现、3 未重跑的真实模型流程、7 仍未执行、1 正确拒绝。上述是处置分类，不将原行转为 PASS。

## 18 项报告发现

| ID | 本轮处置 | 实际验证与剩余边界 |
| --- | --- | --- |
| DOC-001 | 已修正规格 | current/previous 各 8 MiB、crash 32 KiB、event 64 KiB、export 20 MiB；现行诊断测试通过，不继承旧阈值证明 |
| UI-001 | 已修复 | workspace/Agent/诊断反馈在渲染边界动态本地化，sheet 不读空旧字段，窄屏按钮布局；API31 Review 测试通过 |
| SHIZ-001 | 已修复 | 首次合法 root list 初始化 fixed 根；非法路径/limit/cursor 与 attached 缺失根不创建；真实文件测试通过，UID2000全链路未重跑 |
| SHIZ-002 | 未复现，保留 | 原正常时钟单方法 shell 已实际通过；冷启动 readiness 的完整真实 Shizuku 矩阵未验证，不靠增大期限解决 |
| PY-001 | 已修复 | API26 原包实际复现 SIGSYS；固定源包构建 x86_64 --without-mimalloc，保留 isolated UID；两次独立构建 hash 相同；API26 Review 37/37 通过 |
| PY-002 | 已修复受支持范围 | 完整 ZIP 校验后转换有界 STORED 副本，原包/执行副本 digest 分离、ACK 前与 CPython 前校验；固定上游 12 builtins 覆盖 csv/math 等支持子集，三 API 真正执行结果通过；不承诺完整 PyPI |
| TEST-001 | 已修正夹具 | 两页相同字节只请求一次 Vision 是 ADR0016 正确行为；两页实际检索、可解析引用、重开无重发断言均通过 |
| TEST-002 | 已修正夹具 | Stream.toList 改为 API26 兼容收集；API26 Wired adapter 实际测试通过，不替代物理 USB 验收 |
| TEST-003 | 已核对夹具错误 | 外部 run(ctx,payload) 与现行 run(payload) ABI 不匹配；原错误包保留、正确签名另有版本；真实模型自治付费流程未重放 |
| UI-002 | 已修正夹具并验证 | unmerged semantics、旧 inspector 预期、真实滚动动作与宽屏/配对前提修正；API31 Review UI 实际通过；原 API35 UI 环境未单独重跑 |
| DIAG-001 | 已修复 | 明确 python.execute 和四个 workspace 契约；workspace authority 来自持久绑定，shell 才看 selected；16 个真实记录事件测试通过；授权/HMAC策略未扩大 |
| SEC-001 | 工具历史事件保留 | 原自动化输入日志暴露不是已证明的产品 logger 问题；本轮不读旧 credential、不发布原包、不声称暴露消失或代替用户轮换 |
| ENV-001 | 未建立产品归因 | 安装前 framework watchdog/ANR 原记录保留；新模拟器启动不能证明原 API36 原因修复 |
| ENV-002 | 部分验证 | 新 API35/16K Review 19/19 Python 通过；原 LMK/onCreate 前启动等待原因未证明消失 |
| ENV-003 | 部分验证 | 新 API26/31 runtime/Broker 对真实执行标记、取消/超时死亡、下一次新进程等断言通过；原 ART/未达注入标记事件保留 |
| ENV/UI-004 | 未建立根因 | 原 API36 Compose applyChanges ANR 与 TCG 调度/重组成本未分离；第一阶段 Main I/O 修复不冒充该 ANR 根因修复 |
| ENV-004 | 工具/环境边界 | 原代理 CA/Conscrypt 与 UIAutomator RPC 问题保留；不关闭证书校验，不把测试代理修复当产品 Python 修复 |
| ENV/UI-005 | 未建立根因 | 原 API31 Agent detail/SystemUI hit-testing ANR 保留；新 API31 UI 通过不替代该现场归因 |

## 本地验证

- 全仓 strict reviewGate（含 check、licenseGuard/Reverse、供应链、lint、Review 来源/包体校验）：BUILD SUCCESSFUL，6m45s；247 个 XML 套件共 1768 项 JVM 测试，0 failure/error/skip。后续测试夹具修正另跑增量 check 与 Review gate。
- REUSE：832/832 文件 copyright/license 完整，0 缺失/非法表达式。生成 SPDX 文本的字符串拆开书写以避免将代码引号误解析为许可表达式，生成的 header 内容不变。
- API26 Debug：Python/Wired/Shizuku 文件 54/54。API31 Debug：Python/Broker/Skill 37/37；ApiEmbedding 5/5，ContextCompaction 5/5，IndexScale 1k/10k/50k 3/3（10k+50k 108.936s）。传输为 fixture，无真实付费 Provider 调用。
- API31 Review：134/134（Python/Broker/Skill、UI、诊断、Golden Corpus、Wired、Shizuku 文件）。API26 Review：37/37（Python/Broker/Skill）。API35/16 KiB Review：19/19 Python，实际 getconf PAGE_SIZE=16384。
- 第一轮 API31 Debug 101 项中 100 通过、一条新诊断夹具因 deferred host 未初始化失败；修正显式宿主初始化与唯一 fixture ID 后，该测试在 Review 上真正通过。StreamingCancel 与 ForegroundImport 4 条在该 Debug 轮实际通过。
- 第一轮新 API35/16K Debug 19 项中 18 通过，一条新 ACK 前拒绝测试在连接服务阶段超过 5000ms、尚未 transact；保留失败日志。相同原超时设置下 Review 完整 19 项通过，不以退出码代替终态计数。
- Debug 包原生对齐 46/46，包内 notices artifact check 通过。Review gate 校验最终字节、SBOM、provenance；输入核心 SHA58325117… 与 stripping 后包内 SHA453298db… 分别记录，不能混称同一 artifact。
- 独立审查：DSH DeepSeek V4.1 Flash 审查 UI/诊断/Shizuku/Golden，修正其指出的遗漏后 APPROVE；Sol/high 分别审查 ZIP/FD、兼容核心/ABI/固定模块、法律 inventory，均 APPROVE。只读审查不冒充设备执行。

命令、完整终态、XML、截图、来源/固定 hash、两次源码构建日志和原失败保留在独立 attachment-repair 证据目录及交付证据 ZIP。旧 Maven cache 的 annotation-metadata 文件缺失使全 cache notices check 失败；未修改旧 Maven inventory，APK artifact 边界单独通过。本地 CodeGraph 已同步。

## 交付边界

原测试凭据、原 DB/WAL、用户真实知识材料与完整输入证据包不提交。原报告/两份计划/历史 HANDOFF WIP 保留；本轮只提交自己的代码、测试、规格、ADR、许可与安全处置摘要。DSH 旧会话收到 test 后新增一个重复测试已备份并移出源码树，核对的 8 个相关文件未改写。

真实 Provider 付费自治流程、真机 USB/Shizuku、旧框架 ANR 因果、性能门槛和长跑仍未完整验收。源码及本地验证完成与整体验收分别报告；Review 使用 Debug 签名且 debuggable=false，不是正式发布签名。
