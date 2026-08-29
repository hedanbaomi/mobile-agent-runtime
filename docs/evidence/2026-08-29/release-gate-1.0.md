<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 1.0 本地门禁与最终复核证据

## 判定

本轮 0.1.1、0.2、0.3 最终复核与确定性修复达到 `LOCAL_PACKAGE_READY`。这表示严格依赖验证、全仓静态/JVM 检查、API 31 instrumentation、debug APK 与 CycloneDX SBOM 可以从同一源码生成；不表示应用商店 1.0 已发布。

正式 release 当前为 `BLOCKED_SIGNING`：用户尚未提供 release keystore、store password、key alias、key password。构建不会回退 debug key、生成新身份或输出未签/错误签名的正式 AAB。`versionName=0.1.0`、`versionCode=1` 保持不变，待用户后续共同安排 release 时决定版本。

## 最终复核修复

- Provider metadata 必须解析并匹配 model id；stream/tools/image 仅在显式同意后发起各自最小请求，并按 SSE/JSON 实际语义独立判定。4xx、无效响应和网络失败不误报支持。
- 密钥退休扫描 Provider 主引用、自定义 Header 和不可变 snapshot；损坏持久数据 fail-closed。保存新 key 已提交后，即使旧 key 因损坏引用数据不能退休，也不把已成功保存谎报为失败。
- Chat/Agents/Providers/Knowledge/Skills/Announcements/Settings/MCP ViewModel 按 `NavBackStackEntry` 隔离；首帧 NavHost graph 未建立时不再提前导航。
- ZIP 采用应用私有 staging 文件和文件型中央目录验证，批次协调、item/job 同步、generation 与 consent ticket 均在持久边界重复校验。
- 公告 issue #1 实现 cache-first、前台单飞刷新、失败退避、可选统计身份、聚合统计与 Admin 预设；`app://update` 只触发签名公告更新检查，不自动安装。
- Android 26—29 的隔离 Python FD 方向检查改读 `/proc/self/fdinfo` 并 fail-closed；API 30+ 继续使用 `fcntl`。

## 自动验证

| 命令/证据 | 结果 |
| --- | --- |
| `.\gradlew.bat check --dependency-verification=strict` | 最终复跑 `BUILD SUCCESSFUL`；936 tasks（103 executed、833 up-to-date）；GitHub Actions 全 SHA pin、24 个 dependency lock、verification metadata、license guards 均通过 |
| JVM XML 汇总 | 46 suites、301 tests、0 failure/error/skip |
| `.\gradlew.bat :app-android:connectedDebugAndroidTest --dependency-verification=strict`，AVD `mar_api31_matrix` / API 31 | XML：31 tests、30 pass、1 skip、0 failure/error；skip 是需显式 `knowledgeLoad=true` 的受控大负载用例 |
| `ReleaseGateUiDeviceTest` 定向重跑 | 2/2 pass：手机导航入口与公告公共请求无 Provider credential |
| `ApiEmbeddingDeviceTest#providerRevisionInvalidatesPendingAndCapturedBindingsBeforeSecretRead` | pass；库存扫描不再被测试夹具误计为读取 ciphertext |
| `:shared:provider-api:test` XML | 37 tests、0 failure/error/skip；其中 `OpenAiCompatibleAdapterTest` 23 tests |
| `:data:sqlite:test` XML | 104 tests、0 failure/error/skip |
| `:shared:knowledge-api:test` XML | 51 tests、0 failure/error/skip |
| `:runtime:python-android:lintDebug :app-android:assembleDebug :app-android:assembleDebugAndroidTest :app-android:generateDebugSbom --dependency-verification=strict` | `BUILD SUCCESSFUL`；debug SBOM 为 CycloneDX 1.6、166 components |
| `services/announcements: npm test` / `npm run check` | 均 exit 0；含 issue #1 stats、隐私和 Admin 预设回归 |
| `python -B -m reuse lint` | 首跑只报告 24 个新 Gradle lockfile 缺归属；在 `REUSE.toml` 为生成锁文件统一声明 AGPL 后复跑 372/372，exit 0 |

首次完整 API 31 套件曾有两个确定性失败：ApiEmbedding fixture 把 `SELECT ref,status FROM secrets` 库存查询当成 secret 读取；Compose release smoke 在普通 `Application` 上启动 `MainActivity`。修复后 runner 对非 UI 测试延迟主容器初始化、真实 Activity 首帧显式初始化，随后又发现并修复 NavHost graph 首帧竞态。相同定向用例和完整套件均已重新通过，没有把初次失败隐去。

## 签名门禁与剩余边界

`:\app-android:verifyReleaseSigning` 的实测结果为预期失败关闭，报告缺少四项明确签名输入。因为这是用户后续 release 所需的私有身份，不在本轮创建、猜测或读取。根 `releaseGate` 因此不能标 PASS；debug APK 只供本地验收。

F-001 工具能力开关相关进程退出曾在问题统计期间稳定出现两次，但最初问题随后不能稳定复现，当前静态/自动测试也没有得到根因。状态保持 `candidate_intermittent`；只有取得对应 APK SHA、dirty/schema/build 字段和完整 Logcat 后才能重新启动复核修复程序。

未执行/不冒充：真实收费 Provider/Vision、500 文件批次实机、初始 ZIP staging 期间进程死亡、ENOSPC、Android 15 六小时 FGS timeout、Android 16 Job 配额耗尽、正式签名 AAB、应用商店发布。生产公告部署另以实际 Cloudflare version、D1 备份、source hash 与公网后检记录，不由本文件的本地 PASS 推断。

## 最终包记录

Android 制品从 clean source commit `dbdb526df2a4f5ab58c015cfc06f4fa650390806` 生成。之后的 `6baefddc3afe808b04acb0ce5f353e39d0199131` 与 `1582a89c67b3d74722da93f5981eb392ede793b7` 只修复公告 Worker 的跨 isolate/弱 ETag 条件请求，不改变 Android 源码；因此不把后续 Worker commit 冒充为 APK 的构建来源。

| 制品 | 字节 | SHA-256 |
| --- | ---: | --- |
| `app-android/build/outputs/apk/debug/app-android-debug.apk` | 211,144,278 | `81a4ad15a3a9dd974cf17c52cfef043ca0f0d53cc67f60857de1c9b13f3028ba` |
| `app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk` | 1,194,575 | `259c97a70f9f4a5431c970813b7620cc535ef6f05f7f19d2459f9667af0ec6a8` |
| `app-android/build/reports/sbom/debug.cdx.json` | 153,342 | `6c45cbb7a7269108c3cb74dad274f2853f58eba7e7024d53d5440b2b442ae174` |

三项制品同轮 `BUILD SUCCESSFUL`；SBOM 为 CycloneDX 1.6、166 components。APK 使用 debug 签名，仅供本地验收；正式 AAB 仍保持 `BLOCKED_SIGNING`。
