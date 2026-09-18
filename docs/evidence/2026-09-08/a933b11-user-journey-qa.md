<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# a933b11 用户视角真实服务商验收

状态：本轮用户测试已收口，验收结论 **NEEDS_AMEND**。2026-09-08T23:06+08:00 开始，2026-09-09T00:59+08:00 完成环境清理。真实用户路径已发现阻塞，不能据此前自动化测试宣称完整功能可用。

## 范围与环境

- 用户授权独立模拟器、真实 SiliconFlow 调用、指定 Skill/PDF、Computer Use 处理 Android 授权框。未授权本轮 commit、push、发布；本轮未修改产品源码。
- Git main / `a933b1135d42ee85c1f475ba584784d13228160e`；源码树与 `19b5dc3` 审查包一致。保留全部既有文档、规则和临时目录 WIP。
- APK：`build/manual-review/mobile-agent-runtime-19b5dc3-review-debug-signed.apk`；SHA-256 `81a5c837b3d7f5931068c1bbaa649af6c186e0974526ef36265b769c2244d967`，review、debug 签名、debuggable=false。
- 本次独立 AVD `codex_user_journey_api36`，API 36 Google APIs x86_64，720×1280、RAM 4 GiB；仅操作本次设备。其他 AVD 的配置不属于本次证据。
- UI 经 Android CLI layout、ADB input 和 Windows Computer Use 观察与操作；没有数据库种子、测试 adapter 或伪造模型返回。输入材料先复制到本次模拟器 Download，再由系统 DocumentsUI 选择。
- Chat：SiliconFlow / `deepseek-ai/DeepSeek-V3.2`；Vision：`Qwen/Qwen3-VL-8B-Instruct`；本地 Embedding：all-MiniLM-L6-v2、384 维。
- 原始测试材料：用户指定分享目录内两份 SKILL.md；知识库源目录 294 PDF、315,435,736 bytes。桌面 JSONL/NPY 索引没有 Android 导入路径，使用源 PDF 重建。材料不重新许可，不提交材料或密钥。
- 原始可见状态证据位于 `build/manual-qa-20260908/`；该目录不纳入提交。临时 key 不写入任何证据。

## 已完成的用户路径

| 路径 | 当前结果 | 决定性证据 |
| --- | --- | --- |
| Provider 新建、密钥遮蔽、连接确认、真实请求 | PASS，2820 ms | `provider-connection-success.json` |
| Capability probe | FAIL，metadata 失败，后续能力未测 | `provider-probe-failed.json` |
| 新建 Agent、默认应用工作区、真实 Chat | PASS，`QA_CHAT_OK 323` | `chat-real-success.json` |
| Internal 工具列表、创建、读回 | PASS，`qa-proof.txt` / `QA_FILE_20260908`，非负版本原样返回 | `file-roundtrip-complete.json`、`request-inspector-redacted.json` |
| 两份原始 SKILL.md 安装检查与启用 | 安装/启用 PASS，Agent 绑定 BLOCKED | 两个 `skill-*-preview.json`、`skills-both-enabled.json`、`agent-skill-binding-unavailable.json` |
| 294 PDF 全目录导入 | FAIL，5 次 LOW_MEMORY，RSS 约 2.8–3.1 GiB | `pdf-import-low-memory-all.txt` |
| 进程死亡恢复 | 对话可恢复；导入恢复反复触发同类内存终止 | 系统 ApplicationExitInfo；以 UI 删除本轮失败测试库后继续 |
| 3 个小 PDF 文件夹导入 | 2 Ready、1 Waiting for Vision；不虚报全量完成 | `kb-waiting-vision.json`、知识页状态 |
| 知识证据与模型检索 | PASS，真实 `knowledge_search` + `read_document`，显示来源入口；PDF 文本质量有问题 | `kb-evidence-lone-study.json`、`kb-chat-answer.json`、`kb-request-inspector.json` |
| Vision 外发授权与完成状态 | 确认入口 PASS；完成 BLOCKED，约 30 分钟后持久状态仍 WAITING，未看到 READY/FAILED | `vision-upload-consent.json`、`vision-still-waiting.json`；窗口点击再次核对后同样等待 |
| 官方 Shizuku 启动与应用授权 | PASS，UI Ready/Connected；运行画面报告 Version 13.5, adb；现场 ADB 检查为 shell UID 2000 | `shizuku-running.json`、`shizuku-app-authorized-connected.json` 支持 UI 状态，UID 为现场检查记录 |
| SAF 系统文件夹读写授权 | PASS | `saf-platform-grant.json` |
| 修改默认工作区后旧/新 Thread | PASS，旧 Thread 保留 Internal，新 Thread 为 QA-Workspace | `old-thread-workspace-preserved.json`、新会话可见状态 |
| SAF 种子读取、创建、读回 | PASS，真实 saf workspace_id 返回 | `saf-file-roundtrip.json` |
| SAF 覆盖已有文件保护 | PASS，拒绝 provider 不保证原子性的覆盖，原文件保持 | `saf-replace-rejected.json` |
| Shizuku 文件往返 | PASS，真实创建/读取 `shizuku-proof.txt`，返回 `SHIZUKU_OK_20260909`、19 bytes、非负 version | `shizuku-file-roundtrip.json`、同名 PNG |
| 完整 lieflat ZIP 导入、启用、绑定 | PASS，Class B、3 个标准库 CLI，Agent r4 为 1 KB / 1 Skill | `fixtures/packaging.json`；UI 安装/绑定记录。原文件未改，ZIP 12 项、268448 bytes |
| Class B 实际 Python 调用 | BLOCKED，Send 立即出现内部错误，Inspector 没有 prepared request；未到单次批准和隔离执行 | `class-b-preparation-error.json`；现场对照观察：去掉 Class B 后请求能到真实 Provider；普通 Chat 控制见 `chat-real-success.json` |
| 停用已绑定 Skill | PASS，禁止新建含失效绑定的会话；解除绑定后恢复新建 | UI 显示 missing or disabled；Agent r5 为 1 KB / 0 Skill |
| shell.execute 用户路径 | BLOCKED；现场设置显示 Shell available、Shizuku Connected；添加 Agent 级持久授权并保存后请求仍无 shell_exec，根因待诊断 | `shell-unavailable-request.json`、`shell-unavailable-provenance.json`；请求于 16:44 UTC 捕获、16:59 UTC 才落盘，mtime 不代表撤权后采集。r4/r6 授权编辑为现场观察 |
| 撤销 SAF、关闭 Shizuku 意图/危险模式 | PASS（组合负向场景），旧会话后续请求无 workspace/file/shell 工具 | `saf-revoked-dangerous-disabled.json`、`revoked-request-inspector.json`；仅剩 knowledge_search/read_document/calculator，不替代单因素权限回归；Shizuku 标识保留是现场 UI 观察 |
| Vision 等待任务取消 | PASS，Job 显示 Cancelled by user；batch 仍 WAITING，不能当成完成 | 本轮 UI 取消记录 |
| 公告手动刷新 | PASS，Checking → up to date；当前无公告 | 实际公告 UI；未伪造公告来验证详情/已读链路 |
| 语言、主题与导航 | 中文/English 切换、深色主题 PASS，窗口已目视；所有主要页面可进入 | Android UI 与 Computer Use 画面；英文部分长按钮超出视口需修复 |
| MCP / Brave 外部工具 | 配置页检查完成；外部 E2E 未测（无 MCP 端点或 Brave key） | UI 明确未配置，不虚构服务端或用 SiliconFlow key 代替 |
| 删除仍被引用的 Provider | PASS，删除被拒绝，提示先处理模型/Agent/快照引用 | `provider-delete-reference-guard.json`；不声称此操作已擦除密钥 |

## 已确认问题与边界

1. **全量 PDF 内存与恢复循环**：第 6 份左右 PDF 处理中进程被系统 LOW_MEMORY 终止，恢复后反复出现。独立只读调查指出 PDF 解析会累计保留渲染页 PNG，batch 恢复重新处理未完成项；这解释了风险，但没有用修复包验证根因。Use text only 仍会重读/解析 PDF，不是低内存恢复开关。删除本次失败测试库是用户界面恢复步骤，不是全量导入 PASS。
2. **A 类 Skill 无可达的绑定授权路径**：两份原始 SKILL.md 被识别为 A、identity verified、Enabled，但 Agent 页面不可绑定。独立只读调查确认信任条件要求当前 packageHash 的持久 grant；A 类启用不创建空能力 grant，详情页又没有可授予权限，因而用户无法完成绑定。未通过数据库或测试 API 绕过。
3. **SiliconFlow 能力探测端点不兼容且错误展示不足**：当前探测 GET `/v1/models/deepseek-ai%2FDeepSeek-V3.2`，独立只读 HTTP 检查为 404；列表端点 `/v1/models` 为 200，Chat 正常。状态证据 `endpoint-status.json` 从本轮已完成的 HTTP 响应对象保存，仅记录状态/目标/响应日期；它不是应用 HTTP 抓包。UI 的 `provider-probe-failed.json` 仅显示 Metadata failed，没有安全 HTTP 状态或具体错误。
4. **流式 token 用量虚高**：最小真实流式响应四段 usage 为 `(8,0),(8,1),(8,2),(8,2)`，正确末态是 8/2，当前相加为 32/5。完整工具回合的显示用量同样不能当作实际计费依据。`siliconflow-usage-shape.json` 仅保存计数，无密钥或响应正文。独立只读源码核对确认 SSE Usage 被逐段累加；另有 choices 空数组 usage-only 末态被忽略的风险。
5. **原始 Skill 名称不可辨识**：两份都显示 Instruction-only skill，没有使用 frontmatter name。列表/绑定页难以区分。
6. **PDF 提取文本质量**：Lone Study 被标为 Evidence verified，但正文大量单词插入空格并混入 `en-GB`。来源哈希验证不代表文本提取质量正确；真实检索仍命中，后续需单独修复和回归。
7. 系统手写输入浮窗曾遮挡控件，Android CLI layout 未列出该覆盖层。通过 Computer Use 调整浮窗后能操作；不把这种遮挡造成的点击失败认定为产品按钮缺陷。
8. Wired ADB 按项目契约要求实体 USB Companion。本次模拟器不可作为物理 Wired E2E；使用真实 Shizuku 路径覆盖模拟器可验证的 ADB 级权限。物理设备/OEM/USB 差异仍未验收。
9. **Class B 绑定使请求准备失败**：完整原包被识别为可执行 B 类、启用并成功绑定，但发起调用立即出现内部错误。Inspector 未生成请求，现场对照显示解除 Skill 绑定后的请求能到 Provider，故失败集中在 Python 工具发现/规格验证/请求组装之前或其中；尚无异常栈证实具体根因。不得写成隔离 CPython 执行失败或硅基流动拒绝。独立只读定位见 `PythonSkillTools.kt`、`AgentRuntime.kt` 的工具校验与 `OpenAiCompatibleAdapter.buildPayload`，具体需补安全诊断并回归。
10. **shell 持久授权与暴露链路不一致的候选问题**：两次通过新增授权草稿选择 Agent-scoped PERSISTENT shell.execute 并全局保存；设置页 Shizuku Ready/Connected、Dangerous Mode enabled，但模型请求中 shell_exec unavailable。独立只读发现 `EffectiveCapabilityResolver.resolveForRun` 接受 unbound non-ONCE Agent grant，而 `ShellToolExecutor.effectiveCapabilitiesAtRunStart` 再要求 snapshot binding。此路径与现象吻合，但本轮未读取私有 DB，不能单凭快照列表断言授权未落盘；需要用聚合诊断确认 accepted/exposure 两阶段结果。未执行高风险 shell 拒绝/批准测试。
11. **Vision 等待状态无法判断进度/失败**：目标模型与外发确认正确，约 30 分钟后仍显示 processing 0 / waiting 1 和批准按钮。独立只读发现 consent worker 的中间 QUEUED/VISION_PROCESSING 未及时持久化，worker 失败也可能不回写可见错误；因此相同 UI 既可能表示正在请求，也可能表示 worker 失败。本轮没有证据证明从未外发或没有计费；取消后 Job 为 Cancelled。需要可观察的处理中/失败状态，才能完成用户验收。
12. **英文长操作行可达性**：English 下 SAF 仅见 Choose directory/Re-authorize，改为中文后同一 APK 的撤销按钮可见并成功操作。不是 APK/源码版本差异。Skill 页的内嵌列表可独立滚动，新安装项能找到，不将其记为安装或刷新失败。
13. **完成但无助手正文**：请求不可用的 shell，以及撤权后请求文件访问的两个真实回合，界面最终显示“已完成”和 usage，但只有用户消息与检索覆盖提示，未出现助手解释。尚未采集原始响应，无法区分模型空正文/输出预算耗尽与客户端解析展示问题；记录为需要补诊断的用户可见异常，不归因于硅基流动，也不据此声称工具执行过。

## 证据边界与后续

优先修复全量 PDF 内存恢复循环、A 类 Skill 授权死路、B 类请求准备失败；再补 shell grant/exposure 的真实用户回归和 Vision 可观察性。随后修复 provider probe、token usage 与文本提取质量。本轮是测试与证据交付，没有修改产品源码或重建修复包。

未完成的独立验收：隔离 Python 实际执行/单次审批、高风险 shell 批准/拒绝、仅撤销 Agent grant 的单因素测试、真实 Binder death/rebind、物理 Wired USB/OEM、完整大库耗时/ENOSPC/温控/耗电、外部 MCP/Brave。294 文件约 315 MB 已实测失败，但不是完整 K06 全矩阵。不会用既有单元/设备自动化结果补填这些用户路径。

清理：SAF 已撤销，Shizuku 用户意图和 Dangerous Mode 已关闭；被 Agent/快照引用的 Provider 删除被保护拦截，随后 `adb -s emulator-5580 uninstall runtime.mobileagent` 返回 Success，`pm list packages runtime.mobileagent` 为空，以卸载本轮测试应用清理应用数据/凭据。仅删除测试进程中的临时 key 变量；账户侧 key 由用户按原约定撤销。`adb -s emulator-5580 emu kill` 返回 OK，后续 `adb devices` 为空。保留本轮 AVD/材料副本和脱敏证据，未变更其他模拟器。

文档收口验证：`python build/manual-qa-20260908/validate_report.py` exit 0；Markdown 路径、SPDX 声明和证据引用检查通过，文档/JSON/TXT 的密钥模式扫描为 0。`git diff --check` 通过；6 个受保护规则/专题的 tracked patch 与测试开始基线相同，当前 tracked diff 没有产品源码。准确计数保存为 `report-validation.json`。本轮没有运行全仓构建，因未修改产品源码。

独立报告复核：只读子 Agent 对实际证据和候选源码链路进行核对；经修正运行版本、文件落盘时间/采集时间、HTTP 状态证据及现场观察边界，报告准确性复核 **PASS**。此结论仅表示报告没有把候选根因或未测路径写成通过，产品用户验收仍为 **NEEDS_AMEND**。
