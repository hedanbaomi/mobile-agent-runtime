<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 五项复审缺陷修复：范围、契约与证据

基线：main `12171d09fe0e0e5b7f6571a3cc6a1161cab1eea6`。用户本轮明确授权修复以下五项、补定向回归、完成严格门禁与独立审查，再按 draft → ready → 普通合并流程集成并安全同步 main。用户原有 HANDOFF、未跟踪计划、报告和附件保留于原工作区；不进入此 PR。主 Agent 独占写入。

## 修复契约

1. **EPUB 视觉完整性（R05/R06；K02/K03/K05）**：单/双引号 `img` 保持章节与相对路径关联；外部资源绝不下载。内联/引用 SVG、object 等当前不支持的视觉内容显式记为 `UNSUPPORTED`，无视觉配置等待，未经批准不上传，不能静默完整 READY。仅用户显式接受缺口可进入 `READY_WITH_VISUAL_GAPS`。使用无 DTD/实体外联的有界 XHTML 扫描；仅 prolog 内唯一固定无实体的 `<!DOCTYPE html>` 剥离后可解析；正文、重复或错位声明拒绝且不静默删字；其他 DTD/实体声明、畸形 XML 或超深结构以固定错误失败且不附带源内容。parser 指纹升级到 `epub-xml-v4`，旧 v3 完整状态不得免重解析。本轮不实现 SVG 渲染、spine 重排或 DOCX 重写。
2. **API 查询 claim（K05；R05）**：确定的密钥/预检/401/429 失败释放本次持有的 claim；UNKNOWN、取消和中断仍需原有明确重试授权。按随机 owner token 与 `retry_authorized=0` 作 CAS，旧调用不能删除/覆盖已消费的新 claim，也不能消费用户尚未使用的授权。owner token 不进入公开 DTO；历史无 token 行保持门禁并可明确授权恢复。返回后校验/缓存失败保持 UNKNOWN；成功向量先入不可变缓存；本地 ANN 后续失败或有缓存的授权恢复不重新计费。无需 schema 迁移。
3. **MCP 实时撤权（R11/R12/R20；S09/S10/S11）**：初始化、工具发现之后、实际 HTTP 发送之前重新核验冻结绑定。发送门禁在可能挂起的密钥解析之后；密钥解析成功、抛错或空返回时均重新核验；失效时零工具派发、确定拒绝。已可能派发后撤权或配置读取失败，返回固定 UNKNOWN 并扣住结果；不宣称操作未发生，不披露结果/错误源文，不复活旧 call ID，不自动重试。
4. **终态审计（R11/R12/R20；S31）**：STARTED 失败仍拒绝且零派发。派发后的 TERMINAL 审计拒绝/异常返回 UNKNOWN + `audit_degraded=true` 并熔断；已生效的写入或后端 UNKNOWN 不降为可重试失败；重复及后续调用不再派发。
5. **SAF mkdir（R11/R12；S18/S31）**：创建前校验及 createDocument 原有错误分类保留。成功创建后 URI、权限、目录查询、别名和版本核验失败统一 UNKNOWN；不假定目录已回滚。生产目录创建使用同一 create/verify 边界，JVM 回归真实创建临时目录后注入核验故障；不冒称验证了 Android DocumentsProvider。

## 提交前验证快照（2026-10-03 UTC）

- 修复前 `EpubVisualCompletenessTest`：5 tests / 5 failures，证据保存在本机任务备份；确认新断言识别旧缺陷。
- 最终受影响套件 XML：knowledge-api 233、provider-api 225、data/sqlite 375、agent-runtime 58；app Debug/Release/Review JVM 各 216，全部 0 failure/error/skip。数量是测试记录，app 三个变体重复执行相同测试，不作为独立场景倍增。check 其他模块也完成；现存 XML 总数为 1850，包含 Gradle up-to-date/from-cache 收据，不冒称全部本轮重新执行。
- 实际命令 `gradlew.bat licenseGuard licenseGuardReverse check reviewGate --dependency-verification=strict --offline --console=plain`，退出 0，最终 1m57s / 1086 tasks（203 executed、883 up-to-date）。license guard 正反向、锁定/校验、workflow YAML、JVM/lint、review APK 安全/16 KB/native notices、SBOM 171 components 与 provenance 均 PASS。
- `reuse==6.2.0` + Windows 官方 charset-normalizer extra，`python -m reuse lint`：839/839，0 missing/read error/bad license；工具仅在任务私有 venv 安装。
- 独立 Standards：PASS。关闭三个旧缓存条款、DOCTYPE prolog边界；无未解决硬违规或必要异味建议。独立 Spec：PASS。关闭返回后缓存失败门禁、resolver 抛错/空返回撤权；未发现五项缺失/错误或未授权范围扩张。两位审阅者只读且未替代测试。
- 此为提交前快照；PR/远端 CI/main 同步尚未执行，不在本地 PASS 内。后续普通 PR 与 main CI 以对应 GitHub 收据及现行 HANDOFF 为准。

所有 Provider/MCP 测试均使用本地合成数据和假适配器/MockEngine；未调用付费模型、旧 API key 或硅基流动，未读取凭据，未上传用户材料。未做物理测试、全量用户文档导入、真实 Vision 或 §14 全量验收。通过这些回归不等同于完整 K06/S 系列或人工测试通过。

## 人工测试优先级

1. 包含单引号图片、SVG/object 与缺失图的 EPUB：等待、拒绝上传、显式文本缺口、重导入状态与引用。
2. API Embedding 模拟密钥缺失/401/429、UNKNOWN/取消、恢复后相同查询缓存与明确重试。
3. MCP 初始化/发现/调用各阶段撤权、清空配置、恢复授权后的旧调用防重放。
4. 文件写入后的审计失败与 SAF mkdir 查询/权限失败：UNKNOWN 提示、已存在内容和后续熔断。
5. 复杂 PDF/文档/图片导入的取消/重试、批次恢复及缓存引用仍需软件/模拟器人工实测。
