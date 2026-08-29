<!--
SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
SPDX-License-Identifier: AGPL-3.0-only
-->

# K06 可选设备负载入口

生成器在 `app-android/src/androidTest/kotlin/runtime/mobileagent/KnowledgeLoadDeviceFixtures.kt`，由 `KnowledgeLoadDeviceTest` 在目标应用的**唯一 cache 子目录**内生成。不会把 450 MiB 生成数据提交或打包进 APK，也不需要 Python、下载文件或用户文档。全部内容为第一方自造合成数据，许可 `AGPL-3.0-only`。

## 语料口径

320 个输入文件：300 张 1024×512 RGB 业务热力图 PNG + 20 份独立仓库运营报告 Markdown。热力图对应 64 个产品 × 128 个两小时窗口，每格由固定需求/容量公式得到，有标题、轴、颜色图例和场景编号；业务单元数据单独计算 SHA。PNG 使用明确的 stored DEFLATE level 0，约 450 MiB 主要为实际 RGB scanlines，不是空白文件、随机颜色噪声或尾部填充。此设计经主审批准，仅测 **CAS/检查点/视觉等待阶段**，不代表自然压缩文件分布、450 MiB 文本推理或视觉推理吞吐。

Markdown 含仓库专名、六个章节、144 条需求/容量/缺口观察表格、公式、处置与审计文字，每份约 8–32 KiB，生成器严格检查字节范围。20 份报告分成四个 KB，每库五份，真实运行 ONNX、FTS 和 USearch JNI；不会使用 hash embedder。300 图全部缺 Vision 授权并应停在 WAITING_FOR_VISION_MODEL。另一个共享图隔离反例具有配置但没有上传同意，应停在 AWAITING_UPLOAD_CONSENT，Vision guard 调用计数仍为 0。

`manifest.json` 列出每文件的 MIME/类型/实际体积/SHA、场景编号/数据 SHA、许可和预期阶段。主审应抽看生成 PNG 与报告，不能只用 byte 总量判断语料质量。

## 默认不会运行

类内第一步 `Assume` 检查 `knowledgeLoad=true`，在此之前不创建目录、不加载模型。默认完整 instrumentation suite 只得到此用例的 skipped 状态。

| 参数 | 值 |
| --- | --- |
| `knowledgeLoad` | 必须精确为 `true` |
| `knowledgeLoadPhase` | `full`（默认）、`checkpoint`、`resume` |
| `knowledgeLoadFixture` | 仅 resume：上次日志中的 `knowledge-load-<UUID>`，不是任意路径 |
| `knowledgeLoadMaxMinutes` | 10–360，默认 90；每文件之间协作检查，不是原生 ONNX 中途 watchdog |

预检要求 cache 所在卷至少 1.5 GiB 可用。输入、CAS 和私有模型缓存共存，实际磁盘成本高于 450 MiB。预检失败不通过继续填盘，也不自动删其他文件。

## 编译与单独执行

这些是交给主审的命令，作者未执行。先串行编译，并按主流程将目标/test APK 安装到已批准的测试设备；不要依赖旧 APK。替换 `<SERIAL>` 为明确设备编号。

```powershell
.\gradlew.bat :app-android:compileDebugAndroidTestKotlin --no-daemon
adb -s <SERIAL> shell am instrument -w -r -e class runtime.mobileagent.KnowledgeLoadDeviceTest -e knowledgeLoad true -e knowledgeLoadPhase full -e knowledgeLoadMaxMinutes 90 runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner
```

`full` 生成、验证文件并将全部 320 个 job 停在 COPYING，随后创建新 SQLite connection/Repository，从持久化检查点恢复。成功断言 20 文 READY、300 图 WAITING，逐仓专名召回/引用、同库重复导入不增 document/ref、跨库共享 blob 在删除一个库后保留。全程不配置网络客户端/API embedder，不授予 Vision 同意。

## 单独验证进程边界

```powershell
adb -s <SERIAL> shell am instrument -w -r -e class runtime.mobileagent.KnowledgeLoadDeviceTest -e knowledgeLoad true -e knowledgeLoadPhase checkpoint runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner
```

等待日志出现 `CHECKPOINT_COMPONENT_ASSERTIONS_PASSED_NOT_K06_PASS`，记录 `fixture=knowledge-load-<UUID>`；仅当 320 条 COPYING 清单完整时继续。结束 instrumentation 后，在主审批准的隔离设备停止目标进程，然后重开：

```powershell
adb -s <SERIAL> shell am force-stop runtime.mobileagent
adb -s <SERIAL> shell am instrument -w -r -e class runtime.mobileagent.KnowledgeLoadDeviceTest -e knowledgeLoad true -e knowledgeLoadPhase resume -e knowledgeLoadFixture knowledge-load-<UUID> -e knowledgeLoadMaxMinutes 90 runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner
```

这验证 COPY 检查点跨进程恢复，不是任意阶段 kill matrix。若在 resume 的文本处理期间手动停止进程，可用同目录再次 resume 观察；不得预先声称所有状态可恢复。已完成 full 的目录包含删除隔离反例，不要再次 resume，另建新 fixture。生成阶段被终止、manifest/320 checkpoint 不完整、系统清掉 cache 时必须报告 INCOMPLETE，不能重建清单冒充恢复成功。

## 采样和取证

Logcat tag 为 `KnowledgeLoadDevice`。目录内有 `manifest.json`、`checkpoints.json`、`events-<时间>-<phase>.jsonl`、`result-<时间>-<phase>.json`、sources、私有 CAS、test DB 和模型缓存。事件逐文件记录 SHA、字节、stage 和耗时；结果记录总时间、真实 byte 总量、阶段计数、SQLite/JNI使用路径、1秒及每文件末尾采样的 Java/native heap/PSS、卷剩余空间、测试目录大小、开始/结束电量、温度、thermal status、设备型号/API/ABI/targetSDK。

样本峰值不等于连续真实峰值，卷剩余空间包含其他进程影响，开始/结束电量不是精确功耗仪读数；模拟器电池/温控可能是固定值。API 次数只指无网络客户端构造和注入 Vision guard 的 0 次调用，不是假装系统全局抓包。

```powershell
adb -s <SERIAL> logcat -d -s KnowledgeLoadDevice:I
adb -s <SERIAL> shell run-as runtime.mobileagent ls -l cache/knowledge-load-<UUID>
adb -s <SERIAL> exec-out run-as runtime.mobileagent cat cache/knowledge-load-<UUID>/manifest.json
```

对日志列出的确切 result/events 文件用同样 `exec-out ... cat` 提取文本，再记录设备和两个 APK hash、开始/结束时间、执行命令/退出码、XML。不要用宽泛 app-data 归档提取用户 DB/secret，不应把当前 Application 替换 runner 算作产品启动测试。

## 剩余 K06 手工矩阵

下面是主审在专用 AVD/设备上的操作清单，当前测试没有执行这些步骤。实际 ImportWorker 使用产品 DB/注册表，此测试使用隔离 DB，二者不能靠复制数据库互换。

1. **阶段 kill/取消**：COPY 跨进程步骤见上。对 PARSING/EMBEDDING/INDEXING 的主动中断，启动独立 fixture 的 resume，观察对应进度后停止进程，再 resume 原目录；记录中断时持久化 stage，而不是用估计时间代替。此入口没有真实 Vision 外发，因此 VISION_PROCESSING/云端 UNKNOWN_OUTCOME 仍需另一个获准的可控 API 测试，不能用这里的 guard 作完成证据。取消与 kill 的语义不同；若需要真实 WorkManager cancel，先完成下述 Worker 接线前提。
2. **磁盘满**：仅在可丢弃 AVD 快照/专用受限测试卷设计，先记录卷容量和可用量，保存 fixture manifest/checkpoint。当前入口有 1.5 GiB 安全预检，不能靠降低阈值或填满用户卷制造绿灯；应由主审另行批准有限测试卷/文件系统故障注入入口，在 CAS 临时文件/SQLite 写入时返回实际 ENOSPC，观察失败而非 READY、无损已发布内容、释放测试限制后从原 checkpoint 恢复。当前没有此故障入口，状态为 PENDING/需测试夹具，不能把安全预检拒绝等同 ENOSPC 回归。
3. **Android 12/14/15/16 前台矩阵**：准备 API31/34/35/36 单独 AVD，在每个 AVD 记录目标 APK targetSDK、manifest 的 dataSync 权限/type。真实 Worker 的前提是主流程提供仅指向本 fixture DB/目录的隔离接线；禁止将 fixture DB 覆盖到产品 DB。前提未满足时此项是 BLOCKED，不使用 replacement Application 测试伪造前台状态。接线后依次前台发起、切后台发起、用户取消、系统超时/配额耗尽、用户恢复；记录持续通知和前台提升时间、异常、持久 stage、WorkManager补偿次数，观察无任务丢失/无限重启。Android15的6小时条件需实际持续测试或该专用 AVD 允许的受控测试机制；本工具不修改系统时限。Android16需实际记录作业配额场景，不能按 targetSDK 一项笼统判通过。

可用于上述设备只读核验的命令：

```powershell
adb -s <SERIAL> shell getprop ro.build.version.sdk
adb -s <SERIAL> shell getprop ro.product.cpu.abilist
adb -s <SERIAL> shell dumpsys package runtime.mobileagent
adb -s <SERIAL> shell dumpsys activity services runtime.mobileagent
adb -s <SERIAL> shell dumpsys jobscheduler runtime.mobileagent
adb -s <SERIAL> shell dumpsys notification
```

只在专用测试设备使用，并只保留本测试相关输出，避免从个人设备收集其他应用通知。12–16 的“不适用”必须说明 API、targetSDK 和原因。

## 清理范围

没有自动清理。SQLite接口没有公开 close，测试不反射关闭或删仍打开的数据库。先结束 instrumentation/目标测试进程，主审核对日志 UUID、路径直属目标 cache 且含本 manifest，再只清理该**一个确切** `cache/knowledge-load-<UUID>` 目录；不能用通配符、清空整个 cache、`pm clear`、删除用户目录或复制/覆盖产品 DB。报告和必要样本提取完成后再清理。未执行清理也要记录剩余字节。
