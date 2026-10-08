<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# SAF 写入与工作区增删改修复证据

日期：2026-10-08（Asia/Taipei）。R19/R20/R26/R28/R32，S13/S19/S24/S25/S30；基线 main/PR #51 合并提交 0f29e97a710213ad78ec4c4f7a9eb555f865737a，隔离分支 codex/saf-diagnostics-20261008。1.1.4.2preview/code11，供人工审查，不创建公开 Release。

## 诊断与根因

用户诊断包 SHA-256：84195d6024e9d8f6a710758f27db16ec7ec0fc46ca6619b6db8276b37d46d5c8；本次导出包含历史版本事件，当前构建记录以 clean 0f29e97 筛选。当前失败为一次 SAF 写入拒绝及十二次 Shizuku DELETE CONFLICT。SAF 拒绝早于随后 READ_WRITE 授权，包内无授权后 SAF 写入，因此不能据此宣称热更新失败。旧白名单把精确拒绝码归 unknown，无法还原。

源码确认默认重新选择目录、新 Agent 提交 deferred workspace draft 会撤销有效显式写权；普通 Shizuku 两适配器及 Wired 的四种 mutation 遇到任意 expectedVersion 无条件 CONFLICT，模型却获提供该参数。进一步检查发现 SAF 普通现有文档覆盖直接被拒绝，空可创建树会丢删除工具/授权操作面。

修复：默认选择保留有效显式授权，明确只读/撤权仍生效；按操作声明并生成可执行的版本参数，版本描述目标条目；SAF replace=true 用截断流更新并有界精确回读，atomic_replace=false/无原子 patch，开流后任何失败 UNKNOWN；可创建空树保留删除入口，目标 flag 仍检查。新诊断码仍为闭合枚举。详见 ADR-0032。

## 有效实跑

使用 Windows 原生 Git Bash、JDK21/显式 JDK17 编译器、Gradle8.10.2、离线已校验缓存；所有 Gradle 命令带 --dependency-verification=strict、--no-build-cache 和 -Pkotlin.incremental=false。未增加依赖或修改协议/数据库。

| 范围 | 结果 | 内容 |
| --- | --- | --- |
| Android Debug JVM | 296，零失败/错误/跳过 | 目标版本/兄弟变更、schema、授权交集、ONCE、错误映射、SAF 截断开流失败 UNKNOWN |
| shared skills-api | 139，零失败/错误/跳过 | typed contract 和安全边界 |
| shared agent-runtime | 97，零失败/错误/跳过 | 工具终态/model outcome 映射 |
| API36 设备 | 141，零失败/跳过，17.692秒 | 实际系统 DocumentsProvider 与文件存储回归 |

设备批次：RuntimeThreadWorkspaceDeviceTest、RuntimeSafToolExposureDeviceTest、WorkspaceBackendTest、ShizukuVersionProjectionTest、ShizukuWorkspaceFileStoreTest、WiredAdbWorkspaceBackendAdapterTest、RuntimePeerAdaptersTest、WorkspaceAccessCompletionTest、ToolingOrchestrationTest。专用 QA AVD 由系统选择器实际持久授予读写，仅含合成文件，无真实 Provider 请求。debug/AndroidTest APK 均由当前修改源码重新构建并安装。

SAF 通过真实重选→授权保留→新建→建目录→长/短/空/中文更新→精确回读→文件/空目录删除。Shizuku 两 adapter 经 typed bridge 测试替身连接真实 ShizukuWorkspaceFileStore，完成写/改/读/删及兄弟变化后的条件 patch；Wired adapter 经测试 authority 连接真实 NioPrivilegedFileEngine，完成写/建目录/改/读/删。Internal 真后端目标 B 不受 A/目录变更影响，B 真变化仍冲突。明确旧版本条件的异常请求零派发/不消耗 ONCE；正向可用性不由拒绝测试替代。

私有日志：crud-build.log、crud-device-build.log、crud-device-regression.log、crud-validation-receipt.json。初轮设备测试曾因旧覆盖拒绝断言及 URI fixture 选取错误失败；修正为实际 CRUD 验收，并由 workspace 的 SafWorkspaceGrantRepository 精确选择 URI 后，最终完整141通过。只引用最终当前源码实跑，不把早期失败批次或其他变体旧 XML 当作本次通过。

## 独立审阅与边界

DSH DeepSeek V4.1 Flash 独立只读排查确认默认重选及 draft 授权收敛缺陷；未采用其早期未经证实的 UI 猜测。native gpt-6-luna/xhigh 独立审閱版本/权限契约、mandatory patch backstop、SAF 覆盖/空树/测试 fixture，最终 APPROVED，无未解决阻断。Jev route request af4fda1e-856c-4845-8df5-4a3c187aad54，实际 returnedModel jev-1.13.0；建议仅作路由，不授予权限。

实际 DocumentsProvider 通过，不等于用户 realme 上“阅读”文件夹人工验收；本轮未运行真实 Shizuku Binder 服务、物理 Wired USB、OEM Provider 差异、真实付费模型或长稳。普通 SAF 覆盖为 provider 流，可能被外部进程观察到中间态，中断时不自动重放。分页目录真变更仍使旧 cursor 失效。

## 集成与交付状态

strict/offline licenseGuard/Reverse/check/workflow YAML 全通过（996 tasks，4m18s），REUSE941/941通过；Android Debug/Release/Review 各296单元均零失败/错误/跳过；commit/push/PR普通合并、本地main同步及合并源码正式签名人工包尚待完成。根目录83项原WIP保留，私有诊断/密钥/个人文件不进入提交。最终回执另列实际SHA、门禁与产物哈希。

2026-10-08 用户追加指定本次审查版本为1.1.4.2preview，versionCode递增到11；之前1.1.4.1preview/code10产物保留。前述141项功能设备回归与本地完整源码门禁在版本更新前执行，功能源码没有随后变化；最终版本从当前PR head经远端门禁合并后构建核验。下一个公开stable版本code须大于11。
