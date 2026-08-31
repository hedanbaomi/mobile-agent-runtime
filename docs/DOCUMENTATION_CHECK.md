<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 文档与仓库初始化核查

第1/2/4/5/6节保留初始文档任务的历史记录，不代表当前源码或里程碑状态；后续状态见 HANDOFF。本次软件页面 UI 设计阶段的文档变更记录见第7节。v2 权限工具文档在第10节登记为当前单一规范；历史轮次中的无线 ADB、Termux、DPC、Root 或 PTY 方案不再是现行路线。

日期：2026-08-30，Asia/Taipei。范围仅本地文档、许可文件、Git和CodeGraph；不是Android/Worker功能验收，也不是 Shizuku/USB Companion 真实 E2E 或 Dangerous Mode 安全签署。

## 1. 来源与交付边界

已读取指定对话的7轮用户/助手讨论。任务读取接口截断了S5长回复，随后从浏览器可见正文补齐里程碑和验收尾部。完整来源和覆盖见[需求依据](REQUIREMENTS.md)。

工作区初始为空，Git根查询明确返回非仓库；本轮仅在用户指定的 `E:\mobileAgentRuntime` 初始化。未读取其他项目源码或私有材料，未保存完整聊天原文，未创建远程仓库、提交、推送或部署。

## 2. 核查状态

| 检查 | 结果 |
| --- | --- |
| Git初始化 | `git init -b main` 成功，实际根目录为本工作区 |
| Git提交/远程 | main尚无提交，无remote，不把unborn HEAD称为现有SHA |
| CodeGraph | 1.1.1；`init`成功，`sync`显示up to date |
| CodeGraph索引 | initialized=true；files/nodes/edges=0；lastIndexed=null；没有业务源码，不能宣称已建立源码调用图 |
| 忽略本机索引 | `git check-ignore -v .codegraph/codegraph.db`匹配根.gitignore |
| 许可证获取 | 从GNU官方纯文本地址获取，长度与正文关键段校验通过 |
| 两份许可证 | 字节一致，SHA-256见下 |
| 自动文档检查 | 本轮完成后重跑；结果以当前脚本输出为准，覆盖 v2 所需文档、R01—R24、U01—U06、S01—S22 和原阶段顺序；无断链、fence/JSON/头声明/尾随空白错误才可记 PASS |
| 验收与规则检查 | v2 工具映射、Authority 无 fallback、Dangerous Mode、诊断限额与 `E2E BLOCKED` 状态由本文档检查和 [ACCEPTANCE](ACCEPTANCE.md) 共同覆盖；不是产品功能 PASS |
| 独立只读审阅 | 技术事实核查完成；文档初审NEEDS_AMEND，4项修订后定点复核PASS，无遗留P1/P2文档项 |
| Gradle/REUSE/CI/真机/云 | 尚未建立或未执行，不属于本轮通过项 |

许可证SHA-256（两份相同）：

```text
0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0
```

`git diff --check`在无已跟踪文件时不能覆盖新增文档；本轮另外直接扫描全部Markdown的尾随空白、fence、JSON和相对链接。没有将空diff检查当作完整文档检查。

## 3. 可复核命令

从项目根运行以下只读命令：

```powershell
git rev-parse --show-toplevel
git status --short --branch
git remote -v
git check-ignore -v .codegraph/codegraph.db
codegraph status . --json
Get-FileHash -Algorithm SHA256 -LiteralPath LICENSE,LICENSES/AGPL-3.0-only.txt
```

下面为 Python 标准库检查器，已扩展至 v2 所需文档、R01—R24、U01—U06、S01—S22 和阶段顺序；上方历史结果不因此改写。环境 Python 3.11；在 PowerShell 以单引号 here-string 传入 `python -B -`，或提取本代码块在内存中执行。它只检查根目录与 `docs/` 的第一方文档，不扫描构建/依赖目录，也不是产品 licenseGuard、REUSE 或业务测试：

```python
# REUSE-IgnoreStart
from pathlib import Path
import re, json, hashlib
root = Path(r"E:\mobileAgentRuntime")
docs = sorted([*root.glob("*.md"), *(root / "docs").rglob("*.md")])
issues, link_count, json_count = [], 0, 0
required = ["README.md", "AGENTS.md", "agent.md", "HANDOFF.md", "LICENSE_POLICY.md", "CONTEXT.md",
            "docs/REQUIREMENTS.md", "docs/IMPLEMENTATION_PLAN.md", "docs/KNOWLEDGE.md",
            "docs/SKILLS_AND_SECURITY.md", "docs/ANNOUNCEMENTS.md", "docs/ACCEPTANCE.md",
            "docs/DIAGNOSTICS.md", "docs/DOCUMENTATION_CHECK.md",
            "docs/mobile-agent-runtime-authority-tooling-codex-prompt-v2.md",
            "docs/adr/0004-capability-authority-bridge.md", "docs/adr/0005-dangerous-shell-mode.md",
            "docs/plans/multi-authority-controlled-execution.md", "docs/plans/shizuku-controlled-execution.md",
            "docs/plans/wired-adb-desktop-bridge.md", "LICENSE", "LICENSES/AGPL-3.0-only.txt"]
for name in required:
    if not (root / name).is_file(): issues.append("missing: " + name)
for p in docs:
    t = p.read_text(encoding="utf-8")
    rel = p.relative_to(root).as_posix()
    # The v2 prompt is a user-provided normative prompt and is intentionally
    # not rewritten or given a project SPDX header in this documentation task.
    if rel != "docs/mobile-agent-runtime-authority-tooling-codex-prompt-v2.md":
        if "SPDX-License-Identifier: AGPL-3.0-only" not in "\n".join(t.splitlines()[:5]):
            issues.append(rel + ": missing AGPL SPDX header")
        if "SPDX-FileCopyrightText:" not in "\n".join(t.splitlines()[:5]):
            issues.append(rel + ": missing copyright header")
    inside, language, block = False, "", []
    prose = []
    for n, line in enumerate(t.splitlines(), 1):
        if line.startswith("```"):
            if not inside:
                language, block, inside = line[3:].strip(), [], True
                if not language: issues.append(f"{rel}:{n}: missing fence language")
            else:
                if language == "json":
                    try: json.loads("\n".join(block)); json_count += 1
                    except Exception as e: issues.append(f"{rel}:{n}: invalid JSON: {e}")
                inside = False
            continue
        if inside: block.append(line)
        else:
            prose.append(line)
        if line.rstrip() != line: issues.append(f"{rel}:{n}: trailing whitespace")
    if inside: issues.append(rel + ": unclosed code fence")
    prose_text = re.sub(r"`[^`]*`", "", "\n".join(prose))
    for target in re.findall(r"\]\(([^)]+)\)", prose_text):
        target = target.strip("<>").split("#", 1)[0]
        if not target or re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target): continue
        link_count += 1
        resolved = (p.parent / target).resolve()
        if not resolved.is_file(): issues.append(rel + ": broken link " + target)
        if not resolved.is_relative_to(root): issues.append(rel + ": external local path " + target)
req = (root / "docs/REQUIREMENTS.md").read_text(encoding="utf-8")
ids = set(re.findall(r"\| (R\d{2}) \|", req))
if ids != {f"R{i:02}" for i in range(1, 25)}: issues.append("R01-R24 coverage missing")
acceptance = (root / "docs/ACCEPTANCE.md").read_text(encoding="utf-8")
ui_ids = set(re.findall(r"\| (U\d{2}) \|", acceptance))
if ui_ids != {f"U{i:02}" for i in range(1, 7)}: issues.append("U01-U06 coverage missing")
skill_ids = set(re.findall(r"\| (S\d{2}) \|", acceptance))
if skill_ids != {f"S{i:02}" for i in range(1, 23)}: issues.append("S01-S22 coverage missing")
plan = (root / "docs/IMPLEMENTATION_PLAN.md").read_text(encoding="utf-8")
phases = re.findall(r"^\| (M\d+(?:\.\d+)?) \|", plan, re.MULTILINE)
if phases != ["M0", "M0.5", *[f"M{i}" for i in range(1, 8)]]: issues.append("milestone order mismatch")
license_path = root / "LICENSE"
license_hash = hashlib.sha256(license_path.read_bytes()).hexdigest() if license_path.exists() else None
if license_path.exists() and (root / "LICENSES/AGPL-3.0-only.txt").exists():
    if license_path.read_bytes() != (root / "LICENSES/AGPL-3.0-only.txt").read_bytes(): issues.append("license copies differ")
summary = {"markdown_files": len(docs), "local_links": link_count, "json_examples": json_count,
            "requirements": len(ids), "ui_acceptance": len(ui_ids), "milestones": phases,
            "license_sha256": license_hash, "issues": issues,
           "status": "PASS" if not issues else "NEEDS_AMEND"}
print(json.dumps(summary, ensure_ascii=False, indent=2))
raise SystemExit(1 if issues else 0)
# REUSE-IgnoreEnd
```

此检查器不联网验证外部URL，不渲染Mermaid或Android UI；外部技术资料由只读核查与来源链接支持，图是架构说明，不宣称界面验收。

## 4. 证据到结论

| 观察证据 | 可支持的结论 | 下一步 |
| --- | --- | --- |
| 对话S1—S8及需求矩阵 | 方案范围已经归档，可按工作包理解任务 | 实现期维护需求/验收映射 |
| Git/CodeGraph实际输出 | 本地仓库与工具状态已初始化；索引为空 | 新增真实源码后sync并核验符号 |
| 许可正文hash与SPDX扫描 | 当前文档许可意图明确、正文一致 | M0建立真实guard/REUSE/CI/远程保护 |
| 平台官方资料和独立核查 | 有实施路线及明确风险关口 | 对CPython隔离、x86_64 JNI、FGS做真实验证 |
| 文档检查/审阅 | 仅用于文档完整性与可执行性交接 | 不能代替未来A/K/S/N/L功能验收 |

## 5. 保留的限制

没有Android工程或付费API调用证据，没有Python沙箱安全证明，没有USearch x86_64构建、真机长任务或公告端到端部署证据；所有这些仍在[验收矩阵](ACCEPTANCE.md)待执行。精确包名/owner/remote/生产资源与模型包尚未确定。文档中的预算、schema细化和签名信封属于明确标注的实施补充，不冒充用户逐项确认。

## 6. 独立审阅修订记录

| 初审发现 | 修订和定点复核 |
| --- | --- |
| P1 API Embedding同意未出现在状态机 | 增加独立等待同意分支、范围变化重确认和拒绝零外发验收；复核PASS |
| P2 长任务缺少Android版本验收矩阵 | K06加入Android12/14/15/16、服务类型/权限/通知/启动/超时/配额/恢复；复核PASS |
| P2 Remote接口缺必要schema | 补能力/请求/结果/取消/预算/授权/UNKNOWN_OUTCOME及不可重放语义；复核PASS |
| P2 公告并发约束不明确 | 补单待发布修订部分唯一索引、receipt联合唯一、事件去重原子upsert与并发验收；复核PASS |

审阅全程只读；没有运行产品代码或线上服务。最终结论仅为文档交接可用，M0与所有业务里程碑仍待实现和验证。

## 7. 2026-08-28 软件页面 UI 设计阶段文档更新

- 用户要求并澄清：新增专门设计软件页面的步骤。现已在 M0 与 M1 间增加 M0.5，保持 M1—M7 编号；以逐屏高保真页面稿/可编辑源稿、布局与视觉标注为核心交付。
- 来源与约束：新增 R18/S9、U01—U06 和 [ADR-0002](adr/0002-frontend-design-milestone.md)；M1 增加设计基线入口，后续页面按确认的设计实现。0.2/0.3 配置与导入批次见 [ADR-0003](adr/0003-model-endpoint-import-batch-secrets.md)。

## 8. 2026-08-28 M0.5 软件页面 UI 设计基线交付核查

- 交付内容：完成 [docs/UI_DESIGN.md](UI_DESIGN.md)、[docs/design/ui-tokens.json](design/ui-tokens.json)、[docs/design/ui-implementation-map.md](design/ui-implementation-map.md)、[docs/design/ui-prototype.html](design/ui-prototype.html)、[docs/design/screens/](design/screens/README.md) 8 份高保真矢量设计稿及 [docs/design/source/](design/source/README.md) 源稿说明。所有页面及文档全量禁用 Emoji。
- 实际验证：执行 Python 文档检查器，20 份 Markdown、97 个本地链接、18 条需求（R01—R18）、6 项 UI 验收（U01—U06）、9 个阶段顺序（M0—M7）、许可证哈希均通过，状态为 `PASS`；Emoji 扫描脚本确认 0 处违规字符；`python -B -m reuse lint` 退出 0（155/155）。
- 范围与限制：本轮完成 M0.5 设计包交付与设计文档验收（`DOC_CHECK_PASS`）；未修改产品业务逻辑源码、未进行真实付费模型请求、未执行设备与生产部署，未 commit/push。详见 [交接记录](../HANDOFF.md)。

## 9. 2026-08-30 Round3 能力修复文档同步

- 已同步 [技术实现方案](IMPLEMENTATION_PLAN.md)、[验收矩阵](ACCEPTANCE.md)、[Skills 与安全](SKILLS_AND_SECURITY.md)、[需求依据](REQUIREMENTS.md)、[交接记录](../HANDOFF.md)和 [Round3 证据](evidence/2026-08-30/manual-review-round-3-capabilities.md)。
- 文档明确区分：兼容标准库 Claude Skill 程序的真实 isolated CPython 调用；重型桌面依赖脚本改走原生知识工具；PowerShell、宿主 shell、任意文件系统和未授权网络不支持。
- 全仓 `check` 936 tasks、API 31 定向设备矩阵 34/34、公告 `npm test`、REUSE 392/392 已通过；真实 Brave/付费 Provider/Vision、294 PDF 全量耗时、正式签名 release、commit/push 和 Cloudflare 再部署均未执行。

## 10. 2026-08-30 v2 权限工具文档收敛

- 当前单一规范为 [v2 prompt](mobile-agent-runtime-authority-tooling-codex-prompt-v2.md)；其配套单一事实分布在 [REQUIREMENTS](REQUIREMENTS.md)、[ACCEPTANCE](ACCEPTANCE.md)、[SKILLS_AND_SECURITY](SKILLS_AND_SECURITY.md)、[DIAGNOSTICS](DIAGNOSTICS.md)、[ADR-0004](adr/0004-capability-authority-bridge.md)、[ADR-0005](adr/0005-dangerous-shell-mode.md) 及三个执行计划中。
- 当前 elevated Authority 仅为 `SHIZUKU` 与 `WIRED_ADB`，平级且无 fallback；SAF 是 workspace backend。Root、无线 ADB、DPC、Termux、PTY 和宿主 shell 仍排除。
- 应用私有 typed workspace 可按既有实现/自动化证据记 `IMPLEMENTED`、`AUTOMATED TESTED`；真实 Shizuku、Windows USB Companion、Dangerous Mode 安全链路和 `debuggable=false` review-like build 缺失时必须记 `E2E BLOCKED`，不得用 debug/静态结果替代。
- 诊断限额固定为当前段/上一段/崩溃/单事件/ZIP `256 KiB/256 KiB/32 KiB/4 KiB/640 KiB`；命令、路径、URI、serial、stdout/stderr、token 和自由文本禁止进入诊断。
- 本轮仅变更文档，保留已有 Round2/3/4 历史证据原样作为时间限定记录；未修改 `HANDOFF.md`、`docs/IMPLEMENTATION_PLAN.md`、v2 prompt、源码或 secrets，未执行 Gradle、commit、push、deploy。

## 11. 2026-08-31 v2 实现与证据同步

- v2 不再是“仅文档”：Capability/Workspace/Authority/Approval/Audit、Internal/SAF/privileged workspace、Skill Memory、Shizuku、Windows 有线 USB ADB Companion、Dangerous Mode、`shell_exec`、Settings/Agent/Chat/Skills/diagnostics 均已有生产接线与自动化。第 10 节是当时文档 checkpoint，不覆盖本节。
- 当前事实已同步到 [HANDOFF](../HANDOFF.md)、[IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md)、[ACCEPTANCE](ACCEPTANCE.md)、[DIAGNOSTICS](DIAGNOSTICS.md) 与 [v2 最终证据](evidence/2026-08-31/authority-tooling-v2-final.md)。更早历史证据保留原始时间边界，不回写为当前真机结论。
- 本地验证包括：REUSE 514/514；license 正反向；Actions pin；28 lockfiles；root+included-build strict dependency verification；共享/JVM tests；全仓 `check` 1024 tasks；Debug/Review evidence gate；两份 171-component SBOM/provenance；Debug/Review 成品 notices；API 31 分批 instrumentation；Debug APK 安装与首次浅色主题。
- `debuggable=false` Review gate 只证明本地非调试控制面和 artifact binding；真实 Shizuku、物理 USB Companion 与真实 SAF provider 仍是 `E2E BLOCKED`。Root、无线 ADB、DPC、Termux、PTY、Accessibility 和宿主 shell 是排除项。
- 本轮没有降低 AGPL、REUSE、dependency verification 或 release gate，没有 commit/push、正式签名、release、部署、secret 变更或付费调用。最终核心安全、UI/生命周期、构建证据三路独立复核发现的三个 P1 已修复并由原审查者复核关闭；准确结论写入 v2 最终证据。
