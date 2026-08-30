<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 第三轮人工反馈：跨页任务、联网搜索与 Claude Skill 程序调用

日期：2026-08-30，Asia/Taipei。基线为 `main` 的 `dec1e5118c674b91c6039c0576978c811d02410e` 加当前未提交工作区；没有 reset、clean、stash、commit 或 push。

## 输入与结论

- 本轮诊断 ZIP SHA-256 为 `4b053560b3c9ffa479ab5e56c204a1afb3891392f2a513c2c3a05e51e9c23c03`。公共证据不记录用户文件内容、宿主绝对路径或秘密。
- Chat 流和知识导入状态改由稳定 shell owner 持有；切到智能体、更多、请求检查器或其他顶层页面不再因目的地 `NavBackStackEntry` 离开而取消正在进行的任务。请求检查器读取同一 Chat 状态。
- 增加固定 Brave Search HTTPS 工具 `web_search`。搜索 key 进入 Android secret store；模型不能修改目标 host/header；每次搜索仍需用户批准，返回值限定为公开 HTTPS 结果、数量/字段长度受限并标记为 untrusted，活动 key 在解析前后均参与脱敏。
- 无清单 Claude Skill 若包含 `SKILL.md` 和可由 Android 受限标准库兼容层执行的 Python CLI，会生成本地 Class B 清单。启用、授权并绑定到 Agent 后，程序成为真实模型工具；每次调用需审批，执行发生在 isolated UID CPython 服务中，只能读取本次调用显式传入的内存虚拟 Markdown 文件。
- 已针对用户样本中的 `check-structure.py`、`compare-human-ai.py`、`check-translationese.py` 所需的 `re`、`sys`、`collections`、`pathlib` 子集完成兼容。含 PyMuPDF、NumPy、RapidOCR、PyTorch 或 Transformers 等桌面依赖的重型知识库程序仍明确不可直接执行；这类包应导入原生知识库，由模型调用 `knowledge_search`/`read_document`，不得声称原脚本已经运行。
- 不提供 PowerShell、宿主 shell、任意宿主文件系统、原生扩展、子进程或未授权网络。普通“只有 Python 文件”的 ZIP 不会仅因含 Python 自动提升为可执行 Skill。

## 复核修订

最终两个只读复核轴发现并已修订：搜索工具描述与固定 Brave 实现不一致；搜索响应可能回显活动 token；Chat 的通用运行时脱敏未注入当前 Provider secret；NFD ZIP 文件名在检查后与源码查看阶段比较不一致；验收证据尚未穿过真实 `PythonSkillTools` 接线；专题文档状态漏写 S12。修订后没有再启动新的复核—修复循环。

## 验证

- `gradlew.bat check --no-daemon --dependency-verification=strict`：`BUILD SUCCESSFUL`，936 tasks。
- API 31 x86_64：`NavigationScopeTest`、`WebSearchDeviceTest`、`PythonRuntimeDeviceTest`、`PythonSkillToolDeviceTest`、`DiagnosticsDeviceTest`、`ReleaseGateUiDeviceTest` 共 34/34，0 failed。
- `PythonSkillToolDeviceTest` 穿过真实导入、启用、grant、Agent snapshot、模型可见 ToolSpec、逐次审批和 isolated CPython 调用，输出来自虚拟 Markdown 文件，不是直接调用隐藏测试入口。
- 公告 Worker `npm test`：通过；管理端中文显示与 Worker 协议测试保持通过。本轮未部署 Cloudflare。
- `python -B -m reuse lint`：392/392，0 error；第一方新增文件均为 `AGPL-3.0-only`。
- APK：`app-android/build/outputs/apk/debug/mobile-agent-runtime-manual-review-round3-20260830-debug.apk`，214,088,013 bytes，SHA-256 `d68a062b12121b76e502afd8a8cf3610d756876d3a7a00eca916f597ad564682`。
- `apksigner verify --verbose --print-certs`：v2=true，单一 Android Debug RSA signer；证书 SHA-256 `315148930a70085176f864d43de4c7bf3469bca4e912a5ac84b057259350b788`。
- `adb install -r -t`：Success；`am start -W` 冷启动 `runtime.mobileagent/.MainActivity`：Status ok。

## 保留边界

未调用真实付费 Provider、真实 Brave Search 或 Vision；未在测试设备重跑用户 294 个 PDF 的完整耗时；未执行正式签名 release、商店发布、Git commit/push、Cloudflare 部署或 Access/secret 修改。F-001 继续保持 `candidate_intermittent`。本包交由用户人工终审，只有收到新的人工问题才重新启动复核与修复。
