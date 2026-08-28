<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 贡献说明

## 许可证

第一方源代码、构建脚本、服务端、管理端、测试和文档使用 **AGPL-3.0-only**。不要从模板改成 MIT、Apache、BSD、GPL 或 AGPL-or-later。完整政策见 [LICENSE_POLICY.md](LICENSE_POLICY.md)。

## 贡献者归属

版权使用集体标记 `mobileAgentRuntime contributors`。提交必须使用仓库所有者的 Git 身份。

**不得**将 Cursor、其他编辑器、Agent 产品或自动化工具名称写入：

- `git` 作者或提交者
- `Co-authored-by` / `Signed-off-by` 等 trailer（除非那是真实人类贡献者）
- SPDX 版权行、AUTHORS、NOTICE 或本文件的贡献者名单

## 开工

阅读 [agent.md](agent.md)、[HANDOFF.md](HANDOFF.md) 和 [技术实现方案](docs/IMPLEMENTATION_PLAN.md)。修改后维护交接和受影响文档。

## 本地检查

```powershell
.\gradlew.bat licenseGuard
.\gradlew.bat check
.\gradlew.bat :app-android:assembleDebug
python -m reuse lint
```

不要跳过 hook，不要提交密钥、用户知识库、对话原文或 `.codegraph/` 数据库。
