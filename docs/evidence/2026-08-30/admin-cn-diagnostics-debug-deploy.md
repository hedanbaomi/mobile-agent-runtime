<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 公告后台中文化、诊断日志、最终 debug 包与生产部署证据

时间：2026-08-30（Asia/Taipei）。仓库：`E:\mobileAgentRuntime`。基线 HEAD：`dec1e5118c674b91c6039c0576978c811d02410e`，分支 `main` 相对 `origin/main` ahead 4；本轮实现与文档仍为未提交工作区，未 commit/push。

## 1. 范围与边界

- 公告管理后台面向运营者的字段、按钮、预设、状态、统计键和值使用中文；API 路由、请求字段和枚举 wire value 保持协议原值。
- Android 增加默认关闭、用户主动启用、主进程内有界且脱敏的诊断日志，支持 SAF 导出 ZIP 和清除。
- 以规范/需求两个独立轴完成本轮唯一一次最终复核；确认问题修复后不再开启新的自动复核—修复循环。
- 生成 Android debug 签名 APK 供用户人工终审。正式 release、正式签名 AAB、应用商店发布均未执行。
- 用户明确授权后，仅部署本产品公告 Worker/D1/Admin；未创建、编辑、发布、撤回、归档任何公告，未更改 Access 策略或生产签名 secret。

## 2. 最终复核发现与修复

1. 后台原实现只翻译 JSON 键，`status`、`revisionStatus`、`category`、`severity`、`displayMode`、`target`、`channel`、`platform` 等可见值仍可能显示英文。修复为按键映射中文显示，并处理布尔值、空值、动作类型和 locale；增加 Node VM 实际执行显示函数的测试，避免仅静态搜索文案。
2. 诊断日志轮转规范化可能因损坏文件异常影响启动或设置页。修复为 best-effort、写入 fail-closed，并加入损坏轮转目标故障注入设备测试。
3. 启停事件、协程取消传播和 SAF 错误路径脱敏不完整。补充 `diagnostics_toggle`；重新抛出 `CancellationException`；UI 错误先经诊断清洗器处理。

最终复核未把 F-001 标为已修复：工具能力开关导致进程退出曾稳定出现两次，但当前仍不能稳定复现，状态保持 `candidate_intermittent`。

## 3. 本地验证

| 验证 | 结果 |
| --- | --- |
| `:app-android` debug / androidTest、全仓 `check`、严格依赖验证、两项 license guard | `BUILD SUCCESSFUL`；1010 tasks，72 executed、938 up-to-date |
| API 31 x86_64 `DiagnosticsDeviceTest` | 最终 8/8 通过；覆盖默认关闭、持久化、脱敏、边界、崩溃摘要、委托、导出失败保留、损坏轮转和启停事件 |
| 公告 `npm test` / `npm run check` | 通过；包含后台中文值映射的 Node VM 运行时测试 |
| `python -B -m reuse lint` | 385/385，退出 0 |
| 文档检查 | 48 Markdown、162 links、2 JSON、19 requirements、6 UI acceptance；无问题 |
| `git diff --check` | 退出 0；仅报告工作树将来可能发生的 LF/CRLF 提示 |

## 4. 人工终审 APK

- 路径：`E:\mobileAgentRuntime\app-android\build\outputs\apk\debug\app-android-debug.apk`
- 大小：211,146,446 bytes
- SHA-256：`b224a1621a9c16a77e550187eab66db7223d39eb0f0809a591c46e756b73b062`
- BuildConfig：`GIT_REVISION=dec1e5118c674b91c6039c0576978c811d02410e-dirty`、`GIT_DIRTY=true`、`DB_SCHEMA_VERSION=11`、`BUILD_TIME_UTC=2026-08-29T16:12:30Z`
- `apksigner verify --verbose --print-certs`：通过；APK Signature Scheme v2；证书 `C=US, O=Android, CN=Android Debug`；证书 SHA-256 `315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788`；RSA 2048。

该 APK 是 dirty debug 诊断包，只用于人工终审和问题取证，不是正式 release。

## 5. Cloudflare 生产部署

- Worker：`mobile-agent-runtime-announcements`
- D1：`mobile-agent-runtime-announcements-prod`，ID `06cf40c7-dd84-4560-859a-1a417f47207e`
- 自定义域名：`https://announcements.luotianyi.fun`
- 生产迁移：`No migrations to apply!`
- 部署前 D1 备份：`.private/announcements-backups/2026-08-30-before-admin-cn-5ca57950.sql`，4,183 bytes，SHA-256 `dcff7e39f88ce9ac51c16149a9821882e3676b56a63f274ceec00a18660c7c7b`；路径受 `.gitignore` 保护，内容与临时下载 URL 未写入证据。
- source hash：`5ca5795074e6e95842b9a93c8ba67a1af8eac100a1b9589d69530fd345d1cee9`，29 files，forbidden paths 0。
- source ZIP：84,485 bytes，SHA-256 `c60e573e2f61f7d0941a919ec3b312fe393c02f81d898411779e52e446ff069e`。
- 当前 Worker version：`9480180b-d6d2-44e9-9a89-a4fe88b9bcbd`，部署列表显示 100% 流量。

## 6. 线上后检

- 公共 feed：HTTP 200，schema 1，key id `mar-prod-20260829-1`，feedVersion 0，items 0，Android 固定公钥验签成功。
- 条件请求：匹配 `ETag` 返回 304；非法 context 返回 400。
- `/source`、manifest 和 ZIP 回读成功，source hash、84,485 bytes 与 ZIP SHA-256 均与本地一致。
- 匿名访问 Admin 页面和统计 API 均被 Cloudflare Access 302 拦截。
- 在用户现有 Access 登录态中只读刷新 `/admin/announcements`：标题、字段、预设、按钮、下拉值、预览与空状态均显示中文；未触发写请求。
- feedVersion 仍为 0 且 items 为 0，证明本轮没有发布测试公告或改变公告内容。

## 7. 后续边界

用户人工终审尚未完成。若再次出现 F-001 或其他不稳定问题，应在同一 APK 上先保持日志、重新打开应用，然后从“设置 → 隐私与调试”导出诊断 ZIP，并附发生时间和步骤；native 崩溃、系统强杀或 ANR 仍需同一 APK SHA 对应的完整 ADB Logcat。只有用户人工报告新问题时才重新启动复核—修复程序。正式 release 另行共同安排。
