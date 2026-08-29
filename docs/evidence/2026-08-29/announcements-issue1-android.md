<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Issue #1 Android 公告客户端证据

## 范围

本次只覆盖 Android 公告客户端与 SQLite 缓存：按 `ClientContext` 保留 feed rollout
install ID、签名 envelope、ETag、feed version、公告 `id + revision` 和本地状态；可选统计
使用独立 telemetry identity。`MainActivity.onStart` 触发真实前台刷新，
`AnnouncementRefreshCoordinator` 负责单飞、成功时间节流、短失败退避、强制刷新和事件队列。
没有修改 UpdateManager；公告的 `app://update` 仍只通过现有路由接口交给主应用处理。

统计默认关闭。开启时才生成 telemetry identity；关闭会在事务中清空 pending queue、删除
telemetry identity 和去重标记，同时保留公告 rollout install ID 与 feed cache。事件仅包含白名单
字段，正文、摘要、URL 和动作 URL 不进入队列。`install_seen` 每个 telemetry identity 一次，
`app_active` 同版本六小时去重，版本变化立即允许新事件。

## TDD 与测试

先加入 repository 行为测试，再实现缺失 API。初始命令（实现前）为：

```text
.\gradlew.bat :data:sqlite:test --tests runtime.mobileagent.data.AnnouncementRepositoryTest --no-daemon --console=plain
```

初始结果为失败，测试编译阶段报告待实现的 `markAttempt` 时间参数、telemetry identity、
install/app-active 和 failure-cache API；这确认测试先于实现。

实现后最近一次相同命令退出码 0，6 个 `AnnouncementRepositoryTest` 全部通过，覆盖：

- 自动检查依据 last successful fetch，而非失败的 last attempt；
- 失败不覆盖已验签 cache；
- feed rollout ID 与 telemetry ID 分离，关闭清 queue/identity，重新启用生成新 identity；
- `install_seen` 一次、`app_active` 六小时边界和版本变化；
- 事件字段白名单和无公告正文。

随后仅补充了事件内容校验与统计关闭回调；按主代理要求停止继续构建，需在统一串行验证中复跑上述命令。

新增 `AnnouncementRefreshCoordinatorTest`（Android instrumentation source）覆盖共享 in-flight
Deferred、前台刷新绕过六小时成功节流，以及关闭统计时取消上传并不确认仍在飞行中的批次。

曾尝试：

```text
.\gradlew.bat :app-android:compileDebugKotlin --no-daemon --console=plain
```

该命令到达 app 编译阶段，但被工作区其他未完成改动阻塞：`AgentsViewModel.kt`、
`ChatViewModel.kt`、`SkillsViewModel.kt` 存在类型推断错误。本次新增/修改的公告源文件未产生
编译诊断；统一构建由主代理在合并其他 WIP 后串行复验。

## 未执行与边界

本次没有安装 APK、设备/模拟器验收、访问生产网络、部署、commit 或 push。真实 Worker/D1
事件接口联调和 production PASS 仍待主代理/用户按授权执行。证据中的六小时窗口使用
可注入 `Instant`，不代表设备时钟或 Android 设备验收已通过。

## 主流程最终集成更新

统一串行验证已完成：`check --dependency-verification=strict` 通过；API 31 完整 instrumentation XML 为 31 tests、30 pass、1 条受控 load skip、0 failure/error。`ReleaseGateUiDeviceTest` 2/2 通过，覆盖手机 Provider/Knowledge/公告入口与公共公告请求不携带 Authorization、`X-Api-Key` 或 `api-key`。`app://update` 已接到设置页一次签名公告强制检查，仅筛选当前客户端有效 `UPDATE` 项，不自动下载/安装。生产结果仍以本轮 Cloudflare 部署后追加的实际证据为准。
