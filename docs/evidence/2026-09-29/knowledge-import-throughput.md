<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 2026-09-29 知识库导入吞吐与 PDF 文字快路径

## 范围与实现

基线为 `9d6ea66c44b5e511a3b325f4382a3e52d1575783`，改动位于独立工作树 `knowledge-import-throughput`。用户选择实施单文档视觉单元并发、原生文字与本地嵌入和视觉重叠、空密码 Standard V4/R4 与被调用 Form 解析；多图合批、独立视觉模型与跨进程重复图片缓存不在本轮。

- 同文档最多两路视觉单元并行，全批次持久派发槽可设一至六路，新批次默认四路；旧批次未保存策略时回填三路，已有显式策略保留。槽满等待，单元结果先落持久层，全部证据齐备后才发布 active generation。
- 原生文字分块与**本地**嵌入在视觉请求期间准备；最终索引只在视觉完成后按原有发布屏障一次提交。未额外调用 API Embedding。
- PDF 指纹为 `pdf-text-v20-pdfrenderer`。空密码认证成功的受支持 V4/R4 内容流可解密取文字；错误密码明确阻断。只遍历实际 `Do` 调用的 Form，循环、资源或外观不完整时保留整页视觉。Form 内隐藏/裁剪文字与继承但未显式选择的字体不进入原生文字块。

## 根因与预期收益

诊断包 `(9)` 的 ZIP 暂存约 7.7 秒，三份文档的 29 次视觉请求历时约 13.5 分钟；瓶颈主要是视觉 Provider 往返，单文档串行放大尾部等待。先前 v19 离线估算用户 `books.zip` 的 294 个 PDF、11,896 页约有 2,630 次视觉请求。并发只减少等待，不能保证线性加速，且受 Provider 限流、手机内存和实际网络影响。

本地受支持的非 LXXXI 加密样本为 308 页；在初版 v20 解析探针中，原先需视觉的 308 页降为 89 页，另有 30 个提取图片。这个数值先于独立审查指出的 Form 保守性补修，不视为最终整包计数，也不等于真实 Provider 耗时。受保护的 LXXXI 内容未用于本轮探针或测试。

## 验证

- 自造一页 AESV2 Standard V4/R4 空密码与非空密码 PDF 夹具：分别断言原生文字路径和错误密码阻断；二进制夹具均有 AGPL-3.0-only sidecar，并以 `.gitattributes` 的 `-text` 保证检出时字节不被换行转换。两份夹具的暂存 blob 与工作树原始字节哈希逐一相同。
- Form 正例覆盖被调用文字、嵌套图片与未调用资源；负例覆盖循环、`3 Tr` 不可见文字、零面积裁剪以及调用方 Symbol 字体而 Form 未执行 `Tf`。负例同时断言需整页视觉且不把不可信字串加入原生页面文字。
- 仓储假后端覆盖单文档视觉请求重叠、原生嵌入与视觉重叠、暂停恢复、相同输入并发复用；迁移测试覆盖 v26/v27 至 v28、旧策略与六路上限。
- `:shared:knowledge-api:test :data:sqlite:test :feature:knowledge:compileDebugKotlin --offline --dependency-verification=strict --no-build-cache --no-daemon`：修复 Form 边界前已通过；最终受影响套件与 `reviewGate` 结果见下方收口记录。
- `licenseGuard` 与 `python -B -m reuse lint` 在加入测试夹具后通过；首次全仓 `reviewGate` 在独立复审补修前通过，含 review APK 16 KB 原生库、notices、SBOM 与 provenance 检查。

## 独立审查与限制

只读独立审查初次结论 `NEEDS_AMEND`：Form 内容的 `Tr`/裁剪和继承字体未纳入完整性判定；二次指出虽要求整页视觉，仍会把不可信 Form 字串作为原生文字发布。现已增加失效时不附加 Form 字串的修复及负例；最终针对性复审为 `PASS`，未发现剩余可确认的 P0–P2。审查员只读核对源码与测试，未独立运行 Gradle。

本地单元测试和桌面离线解析不能代表真机长任务、实际 Provider 限流/收费、杀进程恢复或 `books.zip` 整包耗时。人工设备验收需记录每份文档的耗时、派发峰值、内存、失败/恢复与检索质量；本轮不声称设备或真实 Provider 通过。

## 收口记录

- 最终补修后执行 `:shared:knowledge-api:test --tests runtime.mobileagent.knowledge.DocumentParserTest :data:sqlite:test --offline --dependency-verification=strict --no-build-cache --no-daemon`：`BUILD SUCCESSFUL in 1m 12s`，24 tasks。全仓 `reviewGate --offline --dependency-verification=strict --no-daemon`：`BUILD SUCCESSFUL in 2m 34s`，1085 tasks；review APK 的原生 LOAD/RELRO/ZIP 16 KB 对齐、runtime notices、CycloneDX SBOM 与 provenance 校验均通过。
- `python -B -m reuse lint`：785/785 合规；`git diff --check` 退出 0。独立审查最终 `PASS`。
- [人工核验 review APK](../../../.private/manual-test/20260929-review-throughput-9d6ea66-dirty/mobile-agent-runtime-review-throughput-9d6ea66-dirty-debug-signed.apk)：176,411,831 字节，SHA-256 `19790fb280db52d48b5f8b9f0dc6974f2e48165fd84afd4cd8589039eaeee522`；`apksigner verify --print-certs` 通过，Android Debug 证书 SHA-256 `315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788`。review 构型不可调试；同目录保存 SBOM、provenance。构建基线 `9d6ea66c` 加本地改动，provenance 为 `gitDirty=true`，不是干净提交或正式发布。
- Git 交付分支为 `codex/knowledge-import-throughput-20260929`，从 `9d6ea66c` 分叉；按用户授权提交并推送该分支，最终 SHA 以远端分支 HEAD 核对。未合并 `main`，未调用付费 Provider，也未执行真机或整包性能验收。根工作区用户未跟踪的设备验收计划未动。
