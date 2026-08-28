<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 文档与仓库初始化核查

日期：2026-08-28，Asia/Taipei。范围仅本地文档、许可文件、Git和CodeGraph；不是Android/Worker功能验收。

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
| 自动文档检查 | 重跑通过：12份Markdown、42个本地链接、1个JSON示例、17条需求；无断链、fence/JSON/头声明/尾随空白错误 |
| 验收与规则检查 | 41个预期验收ID完整；两个Agent入口含交接/维护/许可marker；根配置SPDX通过 |
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

下面为本轮实际运行的Python标准库检查器。环境Python 3.11；在PowerShell以单引号here-string传入 `python -`，或保存到临时目录执行。它检查当前文档文件，不是产品licenseGuard、REUSE或业务测试：

```python
# REUSE-IgnoreStart
from pathlib import Path
import re, json, hashlib
root = Path(r"E:\mobileAgentRuntime")
docs = sorted(root.rglob("*.md"))
docs = [p for p in docs if ".git" not in p.parts and ".codegraph" not in p.parts]
issues, link_count, json_count = [], 0, 0
required = ["README.md", "AGENTS.md", "agent.md", "HANDOFF.md", "LICENSE_POLICY.md",
            "docs/REQUIREMENTS.md", "docs/IMPLEMENTATION_PLAN.md", "docs/KNOWLEDGE.md",
            "docs/SKILLS_AND_SECURITY.md", "docs/ANNOUNCEMENTS.md", "docs/ACCEPTANCE.md",
            "docs/DOCUMENTATION_CHECK.md", "LICENSE", "LICENSES/AGPL-3.0-only.txt"]
for name in required:
    if not (root / name).is_file(): issues.append("missing: " + name)
for p in docs:
    t = p.read_text(encoding="utf-8")
    rel = p.relative_to(root).as_posix()
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
            if line.startswith("####"): issues.append(f"{rel}:{n}: heading deeper than level 3")
        if line.rstrip() != line: issues.append(f"{rel}:{n}: trailing whitespace")
    if inside: issues.append(rel + ": unclosed code fence")
    for target in re.findall(r"\]\(([^)]+)\)", "\n".join(prose)):
        target = target.strip("<>").split("#", 1)[0]
        if not target or re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target): continue
        link_count += 1
        resolved = (p.parent / target).resolve()
        if not resolved.is_file(): issues.append(rel + ": broken link " + target)
        if not resolved.is_relative_to(root): issues.append(rel + ": external local path " + target)
req = (root / "docs/REQUIREMENTS.md").read_text(encoding="utf-8")
ids = set(re.findall(r"\| (R\d{2}) \|", req))
if ids != {f"R{i:02}" for i in range(1, 18)}: issues.append("R01-R17 coverage missing")
license_path = root / "LICENSE"
license_hash = hashlib.sha256(license_path.read_bytes()).hexdigest() if license_path.exists() else None
if license_path.exists() and (root / "LICENSES/AGPL-3.0-only.txt").exists():
    if license_path.read_bytes() != (root / "LICENSES/AGPL-3.0-only.txt").read_bytes(): issues.append("license copies differ")
summary = {"markdown_files": len(docs), "local_links": link_count, "json_examples": json_count,
           "requirements": len(ids), "license_sha256": license_hash, "issues": issues,
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
