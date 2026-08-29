<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# F-010 导航与编辑状态复核证据

时间：2026-08-29（Asia/Taipei）

## 修复范围

- `MainApp` 仅保留 Shell ViewModel；Chat、Agents、Providers、Knowledge、Skills、Announcements、Settings、MCP 均在对应 `NavHost` destination 内，以该路由的 `NavBackStackEntry` 作为 ViewModel owner。
- 导航切换使用 `popUpTo(startDestination) { saveState = true }`、`launchSingleTop` 和 `restoreState`，保留每个路由的 back-stack 状态，不再将页面状态集中在 Activity 宿主。
- Shell 路由、MCP 返回路由、待跳转路由、编辑器脏状态和 Provider 编辑草稿使用可保存状态；Agent/Chat/Skills/MCP 的选中项与编辑器/检查器状态额外写入各自 route ViewModel 的 `SavedStateHandle`。
- 编辑器打开状态与 `editorDirty` 分离。只有草稿实际不同于基线时才弹出放弃确认；干净编辑器可直接关闭。Provider API key 不写入保存状态。
- 保持既有产品路由与紧凑屏幕“更多”结构；未改动 `MainActivity`、Providers/Knowledge/Announcements 业务实现。

## 静态检查

| 检查 | 结果 |
| --- | --- |
| `git rev-parse --show-toplevel` | `E:/mobileAgentRuntime` |
| `git diff --check` | PASS |
| `rg -n "viewModel\(\)" app-android/src/main/kotlin/runtime/mobileagent/ui/MainScreens.kt` | 仅 Shell ViewModel 1 处；业务 ViewModel 均带 `viewModelStoreOwner = NavBackStackEntry` |
| `rg -n "SavedStateHandle|rememberSaveable|popUpTo|restoreState" ...` | PASS；命中 Shell/Agent/Chat 及路由保存状态实现 |
| `app-android/src/androidTest/.../NavigationScopeTest.kt` | 已新增手机/宽屏路由形状回归测试 |

## 构建边界

本次尝试执行 `./gradlew :app-android:compileDebugKotlin --no-daemon`，未能到达 `app-android` 编译阶段：共享工作区中其他并行修复当前仍有 `KnowledgeRepository.kt` 的 `BATCH_GENERATION_CHANGED` 和 `Repositories.kt` 的 `withEndpoint` 未解析错误。该失败不是导航文件报错，待集成者合并其他修复后必须重新执行 Android 编译与 instrumentation 测试。

尚未进行模拟器或真机导航走查，因此不能将此证据标为设备通过；需要集成者在全仓编译恢复后验证：路由切换/返回、进程重建后的 SavedState、Agent/Provider 脏编辑确认以及 Inspector 从 Chat 返回。

## 主流程最终集成更新

全仓 Android 编译已恢复，API 31 release UI smoke 2/2 通过。首次真实 Activity 启动另外暴露 `NavHost` graph 尚未建立时 `LaunchedEffect(route)` 提前读取 `startDestinationId` 的竞态；现以当前 back-stack destination 作为 graph 就绪门槛，并把 destination route 纳入 effect key。定向 UI 重跑与 31 项完整 instrumentation 均 0 failure/error。
