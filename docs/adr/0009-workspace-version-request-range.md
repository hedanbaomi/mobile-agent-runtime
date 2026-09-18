<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR-0009：Workspace 返回版本与请求取值域一致

日期：2026-09-08（Asia/Taipei）。范围：R32 / W21 / S30；补充 ADR-0007/0008 的版本投影，不改变文件操作能力。

## 背景

`7ef48f1` 已能剥离 Internal 的 `c1:/m1:/d1:` 标签，但将摘要前 16 个 hex 字符按 unsigned Long 解析后，Kotlin `Long` 的最高位仍是符号位。普通内容 `test` 的摘要以 `9f86d081884c7d65` 开头，返回 `-6951639720043709083`。请求 DTO 与工具 schema 均要求 `expectedVersion >= 0`，因而无法原样回送。周边检查确认 Shizuku selected-workspace 与 device-token 两个 adapter 也有相同投影问题。

## 决定

- 保持现有非负 `Long` 请求和 JSON integer 契约。已识别标签和 legacy bare hex 使用同一投影：校验整个 hex body，沿用前 16 位及短 token 右补零规则，解析后 `and Long.MAX_VALUE`，结果范围为 `0..Long.MAX_VALUE`。
- 保留旧投影中所有非负值不变；原本为负的返回值改为对应的低 63 位。旧负数请求继续被拒绝，调用方需重新读取版本，不在入口猜测或翻译旧 token。
- `implementationVersion` 使用相同投影比较当前版本，再把当前完整内部 token 传给 backend 的提交前冲突检查。不得清空 expectedVersion、返回固定版本或绕过冲突判断。
- 两个 Shizuku adapter 复用同一投影，仍要求完整 64 位 hex，并保留、回送当前完整 opaque token；不改变 Authority、权限或设备文件操作能力。
- 该数值是有限位数的摘要投影，可能碰撞；不宣称无损或强原子 CAS。完整 token 的二次检查、元数据版本的既有限制及跨进程 best-effort 语义保持 ADR-0008 的约束。
- 无法映射已提交的 mutation 返回 `UNKNOWN_OUTCOME`；Internal 只读结果映射失败返回 `IO_ERROR`，Shizuku 只读协议映射失败返回 `BRIDGE_PROTOCOL_MISMATCH`。已知的结构化 `CONFLICT` 保留原分类。成功返回版本才承诺可原样用作下一次 expectedVersion；文件变化时仍应冲突。

## 未采用

- 放宽为 signed Long：会同时改变请求/schema 与其他后端约束，超出本轮最小兼容修复。
- 改成字符串或 53 位 JSON safe integer：需要更广的协议与兼容设计；当前生产链使用 Kotlin Long/JSON integer，未经过 Double 转换。
- 右移一位：会改变此前已合法的所有正数 token，不符合本轮兼容优先。

## 验证要求

- c1/m1/d1/legacy、最高位 0/1、零和 Long.MAX_VALUE 边界；每种版本均可构造所有 expectedVersion 请求。
- 真实 Internal → Shared Adapter → UnifiedWorkspaceToolExecutor → JSON version → 原样 expected_version，覆盖 write/patch 与 stale CONFLICT，不允许条件跳过。
- 真实目录/大文件 metadata token 往返；提交后映射失败仍证明文件已变化并返回 UNKNOWN_OUTCOME。
- Shizuku 两条 adapter 路径的高位版本、原始 token 保留与 mutation/read 错误分类；Android parser/dispatch seam 测试不等同于真实 Shizuku 服务或物理设备验收。
- 独立只读复核与本地有效测试；具体结果记录在本轮证据及 HANDOFF，不以本 ADR 冒充验证完成。
