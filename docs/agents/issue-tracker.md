<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Issue tracker：GitHub

本仓库的需求、问题与 PRD 记录在 GitHub Issues。所有读取与写入通过 `gh` CLI 完成，并由当前仓库的 `origin` 自动解析目标仓库。

## 约定

- 创建：`gh issue create --title "..." --body "..."`
- 读取：`gh issue view <number> --comments`
- 列表：`gh issue list --state open --json number,title,body,labels,comments`
- 评论：`gh issue comment <number> --body "..."`
- 标签：`gh issue edit <number> --add-label "..."` 或 `--remove-label "..."`
- 关闭：`gh issue close <number> --comment "..."`

Pull Request 不作为需求或 triage 请求入口。若工程技能要求“发布到 issue tracker”，即在本仓库创建 GitHub Issue；要求“获取相关 ticket”时，使用 `gh issue view <number> --comments`。

GitHub Issue 与 Pull Request 共用编号空间。裸编号存在歧义时先执行 `gh pr view <number>`，失败后再执行 `gh issue view <number>`。
