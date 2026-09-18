<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# main 合并与 Debug 签名人工测试 APK

当前状态：PASS；main 已合并，最终 APK 已构建与核验，PR 与 main 的 CI 全部通过。完成时间：2026-09-11T23:17:00+08:00（Asia/Taipei）。本文仅本地保存。

用户本轮授权将最新源码合并 main，并生成 Debug 签名 APK 供人工测试。通过 PR #8 正常合并，不直接推 main 或绕过检查。源提交为 0ca6f1accd5136a3009417e654cbc52000b62362，原 main 为 a933b1135d42ee85c1f475ba584784d13228160e；10 个提交、66 个源码/测试/构建文件，不含 HANDOFF/docs/AGENTS/agent 变更。原工作区 HEAD 3f454183176b7664ff774593bd6dba396d595b2e 及规则、文档 WIP 保留。

PR：https://github.com/hedanbaomi/mobile-agent-runtime/pull/8 。PR license-guard 34611362123、ci 34611362231 均 PASS 后，正常执行 gh pr merge 8 --merge --match-head-commit 0ca6f1accd5136a3009417e654cbc52000b62362，于 2026-09-11T22:57:03+08:00 合并。没有直接推 main、使用 admin 或绕过检查。

远端和本地 main 均已核验为 f1feffed51840a5da299394560619d64cac4b9b5，合并树与已验证的 0ca6f1a 完全一致。原工作区仍处在仅本地文档 HEAD 3f454183176b7664ff774593bd6dba396d595b2e，未切换或覆盖其改动。

main 的 license-guard 34613193443、ci 34613193357 均 completed / success，headSha 均为 f1feffed51840a5da299394560619d64cac4b9b5。check、API 31/34/35/36 smoke 与 API 36 convergence 共 6 个执行 job 全部 PASS；Signed release gate (manual only) 按预期 skipped。convergence job 103308552131 日志实际记录 Starting 38 tests、Finished 38 tests 和 BUILD SUCCESSFUL；不将仅加入 CI 列表当作执行证据。

## 构建与边界

- 独立构建目录：E:/mobileAgentRuntime/.private/main-apk-20260911。先在 0ca6f1a 完成全量 Review gate，再切到实际 main SHA f1feffe 重建；最终 gate 退出码 0，3m25s，1072 tasks（73 executed / 999 up-to-date）。最终 APK 的 BuildConfig GIT_REVISION 为完整 main SHA，GIT_DIRTY=false；构建目录和索引均干净。
- 使用现有 Review 变体：Debug 证书签名、debuggable=false，高权限控制面开启，支持 arm64-v8a 和 x86_64。此选择让人工测试覆盖 Shell 等权限功能。
- 构建命令：./gradlew reviewGate --dependency-verification=strict --no-daemon --no-build-cache --max-workers=2 --console=plain 。SDK 为 E:/Android/Sdk，实际执行器为 Git Bash；命令工具忽略 shell 参数时使用已验证的 Git Bash 绝对路径启动。
- 首次预构建因 Windows core.autocrlf=true 将固定哈希的 BouncyCastle LICENSE.html 转为 CRLF 而被 licenseGuard 拒绝。只在本轮独立目录恢复 HEAD blob 的原始 LF 字节并刷新对应索引；索引树 SHA 未变、Git 内容干净。未改许可证、预期哈希、全局配置或门禁。
- Review 的 APK、安全配置、171 组件 SBOM、provenance 和常规 check 门禁全部 PASS。apksigner verify --verbose --print-certs 通过；签名证书与本机前一个 Review 包相同，DN 为 C=US, O=Android, CN=Android Debug。
- 证书 SHA-256：315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788。
- aapt 核验包名 runtime.mobileagent，versionName 0.1.0，versionCode 1，minSdk 26，targetSdk 35，ABI 为 arm64-v8a / x86_64，非 debuggable。安全控制面为 true。
- 本地日志、原始状态、签名核验和 CI 日志保存在 .private/validation-main-debug-20260911/。最终 main 构建日志为 final-main-review-build.log，退出码和时长见 final-main-review-build-status.json。

## 交付文件

目录：E:/mobileAgentRuntime/.private/manual-test/20260911-main-f1feffe/。

- mobile-agent-runtime-main-f1feffe-debug-signed.apk：205078445 字节（195.58 MiB）。复制后再次核对字节 SHA-256，与构建门禁记录一致。
- APK SHA-256：4b5abdfe90b980e42039c0d618174b339db6ed8bcfd448a4ef98c33ced0ddbbd；同目录提供 .apk.sha256 校验文件。
- review.cdx.json、review.provenance.json、artifact.json、README.md 同目录提供。构建门禁原始 provenance 保留原 artifact 相对路径，APK 仅重命名复制，字节未变。
- sourceArchiveSha256：0d342f5d6e5a69827bd66da7c16c5a98f700c12cb36675ef400dc7387a8bbdab。

收尾保护核验：原 AGENTS.md、agent.md 和既有 ACCEPTANCE / IMPLEMENTATION_PLAN / REQUIREMENTS 文件与任务前 hash 完全一致；原工作区源码 diff 为空、索引干净。只维护本轮 HANDOFF 与新证据，文档不推送。原有临时 WIP 和另一个既有 worktree 保留。

未安装到设备，未执行本轮人工验收、真实 Provider 调用、正式签名 Release、商店发布或收费操作。历史未决验收不由合并和打包关闭。
