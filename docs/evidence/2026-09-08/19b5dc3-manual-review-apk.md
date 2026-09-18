<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 19b5dc3 Debug 签名人工审查 APK

时间：2026-09-08T08:53:59+08:00（Asia/Taipei）。用户要求「使用 debug 签名构建一个 apk 包以进行人工审查」。

状态：**BUILD PASS / SIGNATURE PASS / NOT INSTALLED / MANUAL REVIEW PENDING**。

## 构建来源与变体

- Git 根目录 `E:/mobileAgentRuntime`；分支 `codex/workspace-version-contract`；HEAD `19b5dc3e4e5f480e00a6678ef9fa60c0b57d39fc`。
- 产品源码与构建配置相对 HEAD 无差异。现有本地 HANDOFF/docs、AGENTS/agent.md 与临时目录 WIP 保留，因此现有构建逻辑如实产生 `GIT_DIRTY=true`、`GIT_REVISION=19b5dc3e4e5f480e00a6678ef9fa60c0b57d39fc-dirty`，未伪造 clean 标识。
- 使用项目现有 `review` build type：继承 debug，采用本机 debug signing config，`debuggable=false`，`HIGH_PRIVILEGE_CONTROL_PLANE_ENABLED=true`。该变体用于人工检查高权限控制功能；不使用正式 release 密钥，不改变产品代码或签名配置。
- `BUILD_TIME_UTC=2026-09-08T00:50:50Z`。

## 命令与核验

`./gradlew :app-android:assembleReview --dependency-verification=strict --no-daemon --max-workers=4 --console=plain`

exit 0，BUILD SUCCESSFUL，586 tasks，1m57s。日志：`build/codex-7ef48f1/manual-review-apk-build.log`。本轮只构建产物，未无因重复前两轮已通过的全仓与设备测试。

对最终交付副本执行 Android SDK 36.0.0 `apksigner verify --verbose --print-certs`：exit 0，Verifies，APK Signature Scheme v2=true，1 个 signer，DN=`C=US, O=Android, CN=Android Debug`。证书 SHA-256：`315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788`。

`apkanalyzer manifest debuggable` 返回 false；`aapt2 dump badging` 核验：

| 字段 | 实际值 |
| --- | --- |
| 应用名 | Agent Runtime |
| applicationId | runtime.mobileagent |
| versionName / versionCode | 0.1.0 / 1 |
| minSdk / targetSdk | 26 / 35 |
| ABI | arm64-v8a、x86_64 |

签名、manifest 与包信息日志分别为 `manual-review-signature.txt`、`manual-review-debuggable.txt`、`manual-review-badging.txt`，均位于 `build/codex-7ef48f1/`。

## 交付产物

- 构建原件：`E:/mobileAgentRuntime/app-android/build/outputs/apk/review/app-android-review.apk`。
- 带提交号的交付副本：[mobile-agent-runtime-19b5dc3-review-debug-signed.apk](../../../build/manual-review/mobile-agent-runtime-19b5dc3-review-debug-signed.apk)。
- 大小：204,944,865 bytes（约 205 MB）。复制后已逐字节摘要核验与原件相同。
- SHA-256：`81a5c837b3d7f5931068c1bbaa649af6c186e0974526ef36265b769c2244d967`。
- 校验文件：[APK SHA-256](../../../build/manual-review/mobile-agent-runtime-19b5dc3-review-debug-signed.apk.sha256)。机器可读构建记录：`build/codex-7ef48f1/manual-review-apk.json`。

未安装、未完成人工验收、未新增 commit/push、未合并、未部署或正式发布。交接与本证据仅保留本地。
