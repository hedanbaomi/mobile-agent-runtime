<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# K06 opt-in 大负载测试交付

日期：2026-08-29，Asia/Taipei。基线：`7511b22ffd7a7d3021b7857b6500cbe75d037ad6`，并行工作树。仅新增：

- `app-android/src/androidTest/kotlin/runtime/mobileagent/KnowledgeLoadDeviceTest.kt`
- `app-android/src/androidTest/kotlin/runtime/mobileagent/KnowledgeLoadDeviceFixtures.kt`
- `tools/knowledge-load/README.md`
- 本证据文件。

未改现有设备测试、产品源码、Gradle、其他 worker 或根文档。主 Agent 单写 HANDOFF。本次作者**未运行 Gradle、Android/模拟器、模型、设备 fixture 生成、网络或生产操作**，未提交/推送；编译、负载实测、独立审查均 PENDING。

## 范围批准与数据口径

已完整读取 ACCEPTANCE K06，包括负载/许可清单/性能和电量温控字段，以及 Android12–16 前台兼容矩阵。主审明确批准 320 文件方案：300 张基于可复算业务数值绘制的热力图 PNG，显式 stored DEFLATE 无压缩，约450MiB；20 份独立仓库运营报告使用真实本地 ONNX/JNI。manifest 明示此负载用于**存储、检查点与视觉等待**，不代表自然压缩语料、450MiB文本推理或450MiB视觉处理。没有以空文件、空白段落、随机像素噪声或文件尾填充凑体积。

每张图包含64产品×128窗口的需求/容量矩阵、轴/标题/图例、计算口径和AGPL来源；生成后用 Android BitmapFactory 真正解码，检查尺寸、两个确定业务格子的RGB以及可见标题。业务单元序列单独计算SHA，可按源码公式复算。20份报告含专名、六章、144条观察表格和处置/审计文字，逐份限制8–32KiB；四KB各五份，明确避免把单库反复重建的额外开销误读为320文件全部推理耗时。批量视觉数据不授权、不调用Vision，期待300图全部WAITING，不能标完整READY。

实际文件数、原文byte总量、各文件SHA/MIME/类型、实际耗时和采样峰值均在设备执行时记录，本文不预填数值或PASS。语料由第一方自造，`AGPL-3.0-only`；没有读取用户数据、真实secret或外部资源。

## 实现检查点

1. 第一条语句通过 Assume 检查 `knowledgeLoad=true`，默认 suite 不生成大文件、不加载本类模型。类指定和参数须同时按运行说明选择。
2. full：生成320文件/manifest → 真实 CAS+SQLite 320项COPYING checkpoint → 新连接/新Repository恢复 → 20文真实ONNX READY、300图WAITING → 逐报告专名检索/真实引文 → 重导入 document/ref 不增加 → 跨KB共享图删除隔离。USearch工厂返回真实JNI对象，只附实例计数。
3. checkpoint：只验证全部COPYING落盘并保留清单。resume：只接受日志中唯一UUID测试目录，校验manifest/源文件hash和完整320记录，从新进程/连接恢复。COPYING跨进程用法在工具README；本轮未执行杀进程，不把new Repository等同进程死亡恢复。
4. 无真实Vision或API Embedding网络客户端。Vision guard的任何调用都会立即失败并增加计数；bulk图缺配置停WAITING，独立共享图反例有配置但无上传同意停AWAITING_UPLOAD_CONSENT，计数应0。guard不是外部服务证据，0计数不冒充系统全局抓包。
5. 时间上限10–360分钟、默认90，只在文件/操作边界协作检查，不宣称可立即终止原生推理。超时/异常保留INCOMPLETE；成功状态也明确带 `NOT_K06_PASS`。
6. 1秒和每文件后采样Java/native heap/PSS、最少卷剩余空间；记录实际测试目录磁盘量、开始/结束电量/温度/thermal、型号/API/ABI/targetSDK、SQLite版本、锁定及完成校验的模型hash。采样峰值、卷统计和电池差值有明确测量限制。
7. 所有生成数据、DB/CAS、模型缓存和结果仅在目标app的 `cache/knowledge-load-<UUID>` 下。预检至少1.5GiB可用；不自动填盘、不删其他目录、不修改系统设置。测试保留精确目录，主审提取证据并结束进程后再单独清理。

## 主审运行入口

完整参数、阶段恢复、取证、清理范围见 [运行说明](../../../tools/knowledge-load/README.md)。作者未执行以下命令：

```powershell
.\gradlew.bat :app-android:compileDebugAndroidTestKotlin --no-daemon
adb -s <SERIAL> shell am instrument -w -r -e class runtime.mobileagent.KnowledgeLoadDeviceTest -e knowledgeLoad true -e knowledgeLoadPhase full -e knowledgeLoadMaxMinutes 90 runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner
```

先安装与本次源码一致的目标及test APK。主审回写实际serial/API/ABI、APK hash、命令、退出码、JUnit/XML、Logcat及目录内manifest/events/result；是否满足本组件断言由实测决定。此测试套件已有替换Application的runner，不能算MobileAgentApp启动或前台Worker集成通过。

## 不覆盖的完整K06要求

所有阶段杀进程、真实取消、离线/超时/云端不确定收费、成功Vision不重发、真实磁盘满、Android12–16前台任务矩阵均没有由作者执行。README提供独立设备操作/观察清单和只读检查命令，并指出缺少的前提：当前隔离DB不能直接驱动产品Worker；需要主审另行提供仅指向fixture的Worker接线；真实ENOSPC要在专用受限测试卷或获准的故障入口执行，不能把1.5GiB安全预检失败当作磁盘满恢复通过。此类前提未满足时维持PENDING/BLOCKED，不复制测试DB覆盖产品DB，不更改用户主设备设置。

## 静态验证

CodeGraph先查询当前import/resume、ImportStage和ImportWorker，再只读核对未覆盖的接口。新增两Kotlin文件执行 `git diff --no-index --check -- NUL <文件>`：没有whitespace错误，仅LF→CRLF提示；no-index退出1表示新文件有差异，不当作构建退出0。编译和大负载运行仍由主 Agent 串行负责。

## 主审实测：COPY 检查点（2026-08-29，Asia/Taipei）

在专用 `mar_api36_debug` / `emulator-5556`（Android16/API36、x86_64、target35）实际执行 checkpoint。目标 APK SHA256 为 `C47C5338BC9AEEE414550B4B0A98BBC967ECB1756623E823278B9A4E57CAC3EF`，test APK 为 `2E3DCD62EF2863147036158BD34BEEF0BE268910FEEB2B283295883E59160F9E`。没有同时运行 Gradle 或其他设备负载。

```powershell
adb -s emulator-5556 shell am instrument -w -r -e class runtime.mobileagent.KnowledgeLoadDeviceTest -e knowledgeLoad true -e knowledgeLoadPhase checkpoint -e knowledgeLoadMaxMinutes 90 runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner
```

实际结果为 `OK (1 test)`，JUnit 31.867秒，组件结果 `CHECKPOINT_COMPONENT_ASSERTIONS_PASSED_NOT_K06_PASS`。320源文件共472363598 bytes；320条COPYING记录均持久化；尚无READY文档。真实模型文件hash校验通过，Vision guard调用0、API客户端构造0。采样峰值Java heap75413760B、native heap108558544B、PSS312556544B；最少卷可用9274986496B，测试目录1036686489B。模拟器电量100%、25°C、thermal0不代表真实设备能耗。

测试目录为 `cache/knowledge-load-008b9f85-e0c3-49ee-a355-c8b77eeeca52`。主审只提取该目录的manifest/checkpoints/events/result以及一图一文样本至被忽略的 `.private/overnight/device/k06-checkpoint-evidence/`。manifest SHA256 `ab4d858132556be33a4f25bb242782fc5b068397256d28774c4c898dbfe26e3e`，checkpoints SHA256 `40a9d08740d011a8e7bc64a77d939b052882d3c224123ae132a2e44b1f278425`。抽样 `dashboard-000.png` 和 `report-000.md` 与manifest的长度/hash一致，实际查看热力图和六章报告内容；不存在以空白填充凑体积。

完成取证后已对本测试应用执行 `am force-stop runtime.mobileagent`，数据未清除。下一步将用同一fixture在新进程执行resume；本节**不预写恢复成功、完整K06通过或真实Vision/450MiB推理吞吐**。原始执行日志 `.private/overnight/device/k06-checkpoint.log`。

## 主审实测：跨进程恢复与召回失败（round17）

在停止原进程、安装round17 APK（SHA256 `7AEED66FFFD1449459398B90BA17B78D0DAE84F3197F8C1354D14E9C8BD495A3`，test `E4829E2B6E86EB39FC649812A955C4C9BBB0C3E7F3F749BDA7306A5ED7463A87`）后，以 `knowledgeLoadPhase=resume`、原fixture ID执行，未重新生成源文件或检查点。

- 实际：14.373秒，1项/1失败。20份文本均READY、300图均WAITING，0新COPY记录、0Vision/API调用、4个真实USearch实例；随后第4个专名“松岳仓库”未进入正确文档的top8，断言失败于 `KnowledgeLoadDeviceTest.kt:205`。状态保持 `INCOMPLETE`，后续幂等/跨库删除断言未执行。
- 采样PSS峰值350230528B、Java49712784B、native117252864B；不是连续真实峰值或真机能耗。
- 仅复制自造fixture的 `databases/load.db` 及本次events/result到 `.private/overnight/device/k06-resume-round17-evidence/`；DB SHA256 `f0b44cdba5989d66a9d91b8dd1aedaec393fa0ab3b278271ace6b568d4a6afdc`。未提取产品DB或secret。
- 独立只读复核：20篇源文件hash均一致，100个chunk/真实384维向量完备。FTS候选缺少相关度ORDER BY，将插入顺序交给RRF；目标在同库候选中排16—20。只读SQL加入 `ORDER BY bm25(chunks_fts),c.id` 后20专名目标均排1—5。尚未用修正APK复跑，不放宽topK、原语料或断言。

原fixture尚未执行删除分支，保留供修正后继续恢复验证；完整K06仍未通过。日志 `.private/overnight/device/k06-resume-round17.log`。

## 主审实测：同一 fixture 修复后恢复（round18b）

新 APK SHA256 `54971EFE31002543F171B4F26ECC4B7ECACE9D5B40A47FBEB53E638B529352EF`，测试 APK `8FC3948DA970BC8541A1956F63AFF8D081C421159B8B4BABD509CF4A33DDE958`。继续使用原 fixture，没有重新生成语料、放宽 topK=8 或改断言。设备、API、ABI 与上节一致；本次无并发 Gradle 构建。

- JUnit `OK (1 test)`，8.375 秒，组件计时 8303ms。20 文本 READY、300 图 WAITING；全部 20 个专名检索及引用归属通过，实际创建 21 个 USearch JNI 索引。
- 后续重复文本/图片导入文档增量为 0、blob 引用正确；两个 KB 共享图片后删除原图片 KB，另一库的托管图片和文本检索仍可用。Vision guard 调用 0，未构造外部 API 客户端。
- 采样 Java heap 峰值 49989936B、native heap 116688480B、PSS 344510464B；最少卷可用 9273790464B。模拟器能耗/温度不代表真机。
- 结果文件 SHA256 `50719ea2ac4facbfc07b9d08f179b61d6f110390c3027046200c4cedcc45f874`；events SHA256 `e2b49ce11ef147be4d2db9f8ca8794b5f0bcb1a3f1872ed3f86337bb5ba2d268`，位于忽略目录 `.private/overnight/device/k06-resume-round18-evidence/`；日志 `.private/overnight/device/k06-resume-round18.log`。

该 fixture **已经执行删除分支，不可再当作未改状态继续 resume**。结果严格为 `STORAGE_WAITING_AND_LOCAL_TEXT_COMPONENT_ASSERTIONS_PASSED_NOT_K06_PASS`；完整 Vision、自然压缩 450MiB 语料、全阶段进程死亡、磁盘满、Android12—16 前台矩阵仍未覆盖。
