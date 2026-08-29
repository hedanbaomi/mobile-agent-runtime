<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# K06 Android 12—16 前台导入矩阵

日期：2026-08-29（Asia/Taipei）。基线 HEAD：`7511b22ffd7a7d3021b7857b6500cbe75d037ad6`，工作区包含多人未提交实现。本记录对应 R05、K06，只覆盖 WorkManager/前台执行契约，不代表 300—500 文件负载、真实 Vision 或完整 K06 已通过。

## 实现范围

- `ForegroundImportDeviceTest` 使用目标 APK 中的真实 `WorkManager`、`ImportWorkScheduler`、`ImportWorker` 和进程级 `ImportWorkerRegistry`。测试 runner 使用普通 `Application`，测试 handler 只返回自造 `ImportJob`，不初始化产品数据库、用户任务、SecretStore 或网络客户端。
- 真实调度用例在没有 Activity 的 instrumentation 环境中 enqueue 三个任务，验证 `READY`、`WAITING_FOR_VISION_MODEL`、`AWAITING_EMBEDDING_CONSENT` 均为一次执行的 `SUCCEEDED` 终态。
- 取消用例让真实 Worker 阻塞于可中断 handler，经 `ImportWorkScheduler.cancel` 验证 WorkInfo 进入 `CANCELLED`、执行线程被中断、幂等持久化 hook 至少被调用一次，并观察短窗口内没有自动重放。
- 前台契约用真实 Worker 构造 `ForegroundInfo`，检查 `dataSync` service type、ongoing/progress notification、低重要性 channel；同时从已安装包读取合并 Manifest 中的 service type 和两项前台权限。
- `androidx.work:work-testing:2.10.0` 只用于构造真实 Worker 并读取其公开前台信息；真实调度/取消用例不使用同步 executor、TestDriver 或伪造 SDK 版本。

## 平台依据与诚实边界

Android 12 起后台前台服务启动受限；本实现由 WorkManager 调度，而不是 Activity 直接启动 service。本 instrumentation 用例没有启动 Activity，能证明安装包在当前设备的真实 WorkManager 路径可运行，但 instrumentation 进程的重要性由系统控制，因此**不能单独证明任意生产后台状态都不会被拒绝**。

Android 14 要求声明实际 foreground service type 及对应权限。合并 Manifest 应包含 `SystemForegroundService foregroundServiceType=dataSync`、`FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_DATA_SYNC`；设备测试同时检查 Worker 提交的 type。

当前 `targetSdk=35`。Android 15 对后台 `dataSync` 前台服务实行 24 小时累计六小时限制，超限调用 `Service.onTimeout(int,int)`，随后必须及时停止。WorkManager 2.10.0 的发布说明包含 API 35 兼容及前台 Worker timeout stop reason；设备用例在 API 35+ 检查打包的 `SystemForegroundService` 确实覆盖该回调。参考：[Android dataSync timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)、[WorkManager 2.10.0 release notes](https://developer.android.com/jetpack/androidx/releases/work#2.10.0)。

六小时累计限制不能在普通 90 秒 instrumentation 中自然达到。本轮没有篡改系统时钟、伪造 SDK 或预写超时 PASS。官方提供 `FGS_INTRODUCE_TIME_LIMITS` compat flag 与 `data_sync_fgs_timeout_duration` 的受控缩短方法；这会更改测试设备全局状态，只能在专用、可恢复的测试 AVD 上由主流程单独执行，不能在用户主设备运行。

Android 16 将并发前台服务的 Job 纳入运行配额；WorkManager 管理 Job 生命周期，仍应记录 `WorkInfo.stopReason` 并依赖 SQLite/CAS checkpoint 由用户恢复，不把停止当成功。参考：[Android 16 all-app behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)。普通短测不能诚实耗尽真实配额，本轮只记录当前设备的 stop reason、一次执行和取消后不重放；配额耗尽仍待专用 AVD 长时矩阵。

## 待执行设备矩阵

| Android | API | 当前可运行断言 | 长时/系统边界 | 状态 |
| --- | ---: | --- | --- | --- |
| 12 | 31 | WorkManager 无 Activity 调度、真实 ForegroundInfo/通知、终态与取消 | 实际后台/Doze/进程重启后 checkpoint | NOT_RUN |
| 14 | 34 | dataSync type、专用权限、channel/ongoing notification、真实调度 | 后台启动拒绝与通知权限组合 | NOT_RUN |
| 15 | 35 | target 35、WorkManager timeout callback、stop reason、取消恢复契约 | 六小时累计或专用 AVD 缩短后的真实 `onTimeout` | NOT_RUN |
| 16 | 36 | 与 API 35 契约相同，记录真实 Job stop reason | 配额耗尽、重启补偿、用户恢复且无无限重启 | NOT_RUN |

每台设备必须记录：型号/AVD 定义、Android/API、ABI、target SDK、APK/test APK SHA-256、通知权限状态、电池/Doze状态、开始/结束时间、每个测试方法结果、WorkInfo state/runAttemptCount/stopReason、logcat 中的 FGS/JobScheduler 拒绝或 timeout。不能把一台 API 36 的结果外推为四个版本通过。

## 主流程构建与运行命令

工作区稳定后先统一构建；作者本轮未执行：

```powershell
.\gradlew.bat :app-android:assembleDebug :app-android:assembleDebugAndroidTest --no-daemon
```

对每个专用 API 31/34/35/36 设备分别安装同一轮 APK，再只运行本类：

```powershell
adb -s <serial> install -r app-android\build\outputs\apk\debug\app-android-debug.apk
adb -s <serial> install -r app-android\build\outputs\apk\androidTest\debug\app-android-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class runtime.mobileagent.ForegroundImportDeviceTest runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner
```

Android 15 专用 AVD 的缩短超时实验必须先保存原值，实验后恢复或删除临时 override。以下仅是官方机制的主流程手工步骤，不是本轮已执行证据：

```powershell
adb -s <api35-test-serial> shell device_config get activity_manager data_sync_fgs_timeout_duration
adb -s <api35-test-serial> shell am compat enable FGS_INTRODUCE_TIME_LIMITS runtime.mobileagent
adb -s <api35-test-serial> shell device_config put activity_manager data_sync_fgs_timeout_duration <controlled-duration-ms>
# 在独立长时 fixture 上观察 onTimeout、checkpoint、stopReason 与用户恢复；不要使用本测试类的短任务冒充。
adb -s <api35-test-serial> shell device_config delete activity_manager data_sync_fgs_timeout_duration
adb -s <api35-test-serial> shell am compat disable FGS_INTRODUCE_TIME_LIMITS runtime.mobileagent
```

Android 16 配额实验只在可丢弃 AVD 上执行，采集 `dumpsys jobscheduler` 和 WorkInfo stop reason；不建议用危险的全局配额 override 制造绿灯。启动拒绝或配额停止时，验收要求是任务仍有 checkpoint、不得 READY、不得自动无限 enqueue；恢复必须来自明确用户操作。

## 本轮验证

- 已完成：CodeGraph 优先核对调用链；静态检查待本文件与测试落盘后执行。
- 未执行：Gradle、APK 安装、instrumentation、AVD、真实六小时限制、Android 16 Job 配额、网络或付费 Provider。
- 当前结论：测试实现为 `IMPLEMENTED / DEVICE_NOT_RUN`；矩阵各行保持 `NOT_RUN`。
