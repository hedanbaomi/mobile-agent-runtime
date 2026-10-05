<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 默认统计与界面精简：v1.0.1 发布前证据

日期：2026-10-06，Asia/Taipei。需求 R09/R15/R18；验收 N08、S01/S12、U05、L01–L04。用户明确授权提交、推送、正常 PR 合并及正式 v1.0.1 发布。

## 实现与边界

- 缺失统计选择时，同一数据库事务初始化开启状态及独立统计身份；保存的关闭选择、无效已有值保持关闭。撤回仍清空队列与身份，重新开启轮换身份。上传字段、服务端协议和 Provider 凭据隔离没有变化。决定见 [ADR-0024](../../adr/0024-announcement-statistics-default-on.md)。
- 技能首页缩短说明，移除“访问边界摘要”和重复初始状态文案；说明正文按需展开且仍脱敏。保留实际权限、授权状态、安装兼容性检查与确认操作。设置页缩短搜索、备份和系统增强说明，导入兼容说明按需展开。
- 普通 Skill 的入口为 SKILL.md。mobile-skill.json 是本项目可选的 Android 执行扩展；缺失时由本机生成兼容清单，不修改原包。移除暗示 Claude Skill 缺文件的产品文案。
- 应用版本 1.0.1 / code 3。保留 AGPL-3.0-only 与现有正式签名配置。当前修改发生在本地 main；依用户指令不额外同步远端合并提交，正式包从远端已合并 SHA 的干净 checkout 构建。
- 本轮不提交工作区此前未提交的公告后台/Access 部署改动、私有测试报告或本机附件。相关工作区文件原样保留。

## 本地检查

Windows Git Bash（MINGW64），严格依赖验证，离线依赖缓存；命令退出码为 0：

```text
./gradlew.bat check :app-android:assembleDebug :app-android:assembleDebugAndroidTest licenseGuardReverse verifyCiPins verifyWorkflowYaml --offline --no-daemon --dependency-verification=strict --console=plain
```

最终门禁 BUILD SUCCESSFUL（2m48s，1070 tasks）。SQLite 测试结果 389 tests，应用单元结果 690 tests（Debug/Review/Release 三变体合计），均 0 failures/errors/skipped。新增默认开启、无需进入设置记录活动、身份/去重在重建后稳定及保存关闭不被覆盖的测试；现有关闭清队列、身份轮换测试继续通过。

初轮 AndroidTest 编译发现多余 assertDoesNotExist import，删除后最终编译门禁通过。设备测试的说明脱敏场景改为先展开再检查；删除一个本轮开始前已不存在的旧提示文案断言，新增精简首页及说明展开/收起验证。

独立只读审查：DeepSeek V4.1 Flash，原始改动与 SkillsViewModel 两条文案补审均 APPROVED，无阻断问题。审阅者核对源码与门禁日志，没有自行执行 Gradle、模拟器或生产请求。原始报告、命令日志和截图保存在本机忽略的 .private/stats-skill-copy-20261006/，不发布用户数据。

CodeGraph 未返回相关模块的有效源码定位，已按规则使用定向搜索；最终 codegraph sync 完成。

## 模拟器定向验证

Android API 34 / x86_64，独立测试 AVD。应用启动前启用飞行模式并配置不可达代理，未向生产公告或 Provider 发送测试数据。

- 技能首页实际截图和布局确认没有大段初始说明、访问边界摘要或 mobile-skill.json 提示。
- 统计开关初始为开启，设置说明默认开启且可以随时关闭。
- 点击关闭后实际布局为未勾选；停止进程并覆盖同签名 Debug APK、重新启动后仍未勾选。布局验证按同一行的开关语义匹配，不依赖滑动后固定坐标。
- 定向 instrumentation 19/19 通过，0 失败/跳过（1m36s）：SkillsUiTest、SkillGrantScopeUiTest、AnnouncementRefreshCoordinatorTest、ExecutionAuthoritiesUiTest、DiagnosticsLogLevelUiTest。覆盖精简首页、说明展开/收起与脱敏、四态技能记忆、字体放大下权限可见性、授权范围及撤回/协调器回归。

命令：`ANDROID_SERIAL=emulator-5558 ./gradlew.bat :app-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=runtime.mobileagent.SkillsUiTest,runtime.mobileagent.SkillGrantScopeUiTest,runtime.mobileagent.AnnouncementRefreshCoordinatorTest,runtime.mobileagent.ExecutionAuthoritiesUiTest,runtime.mobileagent.DiagnosticsLogLevelUiTest --offline --no-daemon --dependency-verification=strict --console=plain`。Gradle 原始 XML 与 HTML 报告已核对；本机原始证据不随源码上传。

## 发布边界

此文件记录发布前验证。正式发布需待远端 CI 和正常合并完成后，在干净合并提交上执行 releaseGate；再核对既有正式证书、不可调试、arm64 独占、版本、源码/SBOM/来源哈希，并在 GitHub Release 保存结果及附件。签名私钥与密码只通过进程环境读取，不进入源码、日志或附件。

定向模拟器回归不等于完整产品验收；本轮没有执行 arm64 真机覆盖升级、真实 Provider 付费调用、Shizuku/有线 ADB 或长期稳定性验收。
