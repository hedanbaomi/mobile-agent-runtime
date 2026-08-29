<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# GitHub issue #1 — announcement Worker/Admin backend evidence

时间：2026-08-29（Asia/Taipei）
范围：仅 `services/announcements/**`、`admin/announcements/index.html` 与本证据文件。未执行 commit、push、生产部署、远程 D1 操作或秘密读取。

## 需求与实现

- `MemoryStore.stats(now)` 与 `D1Store.stats(now)` 复用现有 `install_state` 和 `announcement_receipts` 表，明确输出 `installSeen`、`appActive`、`dau`、`wau`、`mau`，以及近 30 日同意安装的 `byVersion`、`byChannel`、`byPlatform` 分布。
- `active24h`、`active7d`、`active30d` 保留为兼容别名；DAU/WAU/MAU 按 `last_active_at` 的服务端接收时间窗口去重安装计数，未来时间戳不计入。`installIdHash`、event id、原始安装 ID 与事件内容不出现在统计响应。
- 事件白名单、`install_seen`/`app_active` 六小时计数、30 日明细 retention、事件幂等去重、Access/admin 认证与 CSRF 边界未降级或改表。
- 后台增加“普通公告 / 重要公告 / 版本更新”预设；Advanced 字段默认折叠，仍提交完整 MAR body/target/action 模型。版本更新预设固定生成 `OPEN_APP_ROUTE`，URL 为 `app://update`。
- 后台增加同源匿名统计查看器，仅展示聚合结果；页面不接收签名私钥、Provider 凭据或其他敏感字段。

## 观察证据

基线工作区：`E:/mobileAgentRuntime`，CodeGraph 存在。修改源码后执行 `codegraph sync .`，结果为 `Already up to date`。本次记录时 HEAD 为 `7fe3d9b8109f86c8dc88977ccde79f8c369a929e`；工作区同时存在其他 Agent 的未提交变更，未回退、清理或覆盖。

| 命令 | 结果 |
| --- | --- |
| `npm test`（`services/announcements`） | exit 0；既有 rollout/worker/access 测试及新增统计、敏感内容、Admin 预设测试全部通过 |
| `npm run check`（`services/announcements`） | exit 0；Worker、App、D1、Access、签名与 Store 语法检查通过 |
| `git diff --check` | exit 0 |
| `npx --no-install wrangler d1 migrations apply mobile-agent-runtime-announcements-local --local --config wrangler.local.toml` | exit 0；仅 local all-zero D1，0001/0002 共 29 条命令成功 |
| `npx --no-install wrangler dev --local --config wrangler.local.toml --var MAR_ADMIN_TOKEN:test-admin-token` + `node scripts/local-smoke.mjs http://127.0.0.1:8787 test-admin-token` | smoke exit 0：创建/定时/Feed/ETag 304/事件去重/本地 D1 统计链路通过；随后已停止 local Wrangler |
| local D1 统计请求 | HTTP 200；返回 `installSeen`、`appActive`、`dau`、`wau`、`mau`、`byVersion`、`byChannel`、`byPlatform`，只含计数和版本/渠道/平台值 |
| Admin inline script syntax check | exit 0；`new Function` 校验页面脚本语法 |

新增测试文件 `services/announcements/src/stats.test.mjs` 覆盖：

- 4 个同意安装，分别落在 DAU、WAU、MAU 与 30 日外窗口，验证窗口计数和三种维度分布；
- `install_seen` 与六小时合并后的 `app_active` distinct-install 统计；
- Worker `/api/v1/events` → `/admin/v1/stats` 公共/管理路由闭环；
- `prompt`、`knowledge` 等敏感内容拒绝，以及统计输出不含 install/event 原始字段；
- D1 stats 查询 seam 的字段映射；
- Admin 三个预设、`<details id="advancedFields">`、完整模型字段与 `app://update` 路由静态约束。

## 结论与残余风险

结论：本地 Worker/Admin 实现对 issue #1 指定的统计维度、隐私/去重边界和管理预设达到 `LOCAL_PASS`；未将本地结果写成 `DEPLOYED` 或生产通过。

残余风险/未执行项：

- 未做生产 Access 会话、远程 D1、Cloudflare 部署或公网后检；生产操作由主 Agent/所有者另行授权。
- 未做真实浏览器视觉/交互验收；Admin 预设用静态测试与 inline-script 语法检查验证。
- 统计维度基于现有安装状态最新的 platform/channel/version，并限定近 30 日 MAU 样本；关闭统计的客户端偏差仍需在隐私说明与客户端验收中单独确认。
- 目前未完成本包之外的独立审阅者复核；集成前应由主 Agent 进行只读协议复核，并保持其他 Agent 的 Android/根文档 WIP 不变。

## 主流程最终集成更新

主流程已重新执行 `npm test` 与 `npm run check`，均 exit 0；严格 Gradle `check` 与 API 31 公共公告请求/UI smoke 同样通过。随后从 clean commit 导出远端 D1 备份并部署到 Cloudflare。生产首轮后检发现 isolate-local cache 与 Cloudflare 弱 ETag 导致条件请求返回 200，已用两个定点提交补回归并重新部署。

最终 Worker version 为 `70b42812-5fd9-45e7-902f-ae35d56151a2`，source hash 为 `b835d4709d29b1111f1673f19a5a64d5d8ae09c14138c3f363e4d6d5de40ca25`。自定义域名后检确认签名 feed 200、Ed25519 验签通过、条件请求 304、无效上下文 400、`/source` 归档逐字节一致；未认证 admin API 被 Cloudflare Access 302 拦截。结论由 `LOCAL_PASS` 提升为公共公告系统 `DEPLOYED`，但不冒充已完成带登录会话的 Admin 浏览器验收。
