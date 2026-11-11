# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Check release rollback and the current handoff/document surface."""
import argparse
import pathlib
import posixpath
import re
import subprocess
import sys

RELEASE_FILE = "app-android/build.gradle.kts"
# Last confirmed public release; also inspect every available stable Git tag.
RELEASE_FLOOR = (15, "1.1.5")
LOCAL_RECORDS = {
    "docs/defects.md", "docs/test-report.md",
    "docs/plans/device-acceptance-20260926.md",
    "docs/plans/emulator-e2e-robustness-20260929.md",
    "docs/evidence/2026-10-05/announcements-access-fetch-deploy.md",
    "docs/evidence/2026-10-05/announcements-visual-admin.md",
}
HANDOFF_SUMMARIES = ("current-task.md", "current-status.md", "open-items.md")


def check_local_git_baseline(root, text, required=False):
    errors = []
    for field, ref in (("local-head", "HEAD"), ("cached-origin-main", "refs/remotes/origin/main")):
        declared = re.search(rf'<!-- {field}: ([0-9a-f]{{7,40}}) -->', text)
        if not declared:
            if required or f'<!-- {field}:' in text:
                errors.append(f"current-status: missing or malformed {field}")
            continue
        actual = subprocess.run(["git", "rev-parse", "--verify", ref], cwd=root, capture_output=True, text=True)
        if actual.returncode or not actual.stdout.strip().startswith(declared[1]):
            errors.append(f"current-status: stale {field}; refresh the explicitly dated local baseline")
    return errors


def version(text):
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.M)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    if not code or not name:
        raise ValueError("Missing literal Android release identity")
    return int(code[1]), name[1]


def check(root, publication=False, revision=None):
    if revision is not None and not publication:
        raise ValueError("--revision requires --publication")
    failures = []
    current = version((root / RELEASE_FILE).read_text(encoding="utf-8"))
    floors = [RELEASE_FLOOR]
    tags = subprocess.check_output(["git", "tag", "--list", "v*"], cwd=root, text=True, encoding="utf-8").splitlines()
    for tag in tags:
        if re.fullmatch(r"v\d+\.\d+\.\d+(?:\.\d+)?", tag):
            blob = subprocess.check_output(["git", "show", f"{tag}:{RELEASE_FILE}"], cwd=root, text=True, encoding="utf-8")
            floors.append(version(blob))
    floor = max(floors)
    if current[0] < floor[0] or (current[0] == floor[0] and current[1] != floor[1]):
        failures.append(f"Release rollback: {current} is below/different from public {floor}")
    summary_dir = root / "docs/agents/handoff"
    # A public checkout contains the historical handoff. Enforce the current local
    # budget when the private summaries are installed or the handoff is being edited.
    local_handoff = not publication and (summary_dir.is_dir() or subprocess.run(
        ["git", "diff", "--quiet", "HEAD", "--", "HANDOFF.md"], cwd=root,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode != 0)
    docs = []
    if local_handoff:
        status = summary_dir / "current-status.md"
        if status.is_file():
            failures += check_local_git_baseline(root, status.read_text(encoding="utf-8"), required=True)
        handoff = (root / "HANDOFF.md").read_text(encoding="utf-8")
        if len(handoff.encode("utf-8")) > 24000 or len(handoff.splitlines()) > 120:
            failures.append("HANDOFF exceeds current-summary budget; archive completed history")
        if handoff.count("## 当前任务") != 1:
            failures.append("HANDOFF must contain exactly one current-task section")
        # These summaries are maintained locally. Fresh checkouts may omit them.
        summaries = [summary_dir / name for name in HANDOFF_SUMMARIES]
        hot = [p.read_bytes() for p in summaries if p.is_file()]
        if sum(map(len, hot)) > 12000 or sum(len(data.splitlines()) for data in hot) > 150:
            failures.append("Current handoff summaries exceed 12 KB / 150 lines; link completed history")
        docs = [root / "HANDOFF.md", *(p for p in summaries if p.is_file())]
    # Archived evidence keeps its historical interpretation and is not a live
    # requirement. Only current entry points are checked here; no disk-wide scan.
    # Local reports are intentionally untracked WIP, not portable requirements.
    tracked = set(subprocess.check_output(["git", "ls-files"], cwd=root, text=True, encoding="utf-8").splitlines())
    # Check the candidate tracked worktree by default, so a repair can be
    # verified before a commit. Untracked files never prove portability.
    # Historical audits opt into one immutable tree for both bytes and paths.
    if revision is not None:
        tree = subprocess.run(["git", "ls-tree", "-r", "--name-only", revision], cwd=root,
                              capture_output=True, text=True, encoding="utf-8")
        if tree.returncode:
            return failures + [f"Cannot read publication revision: {revision}"]
        published = set(tree.stdout.splitlines())
    else:
        published = {path for path in tracked if (root / path).is_file()}
    document_paths = published if publication else tracked
    docs += [root / path for path in sorted(document_paths)
             if pathlib.PurePosixPath(path).suffix == ".md"
             and pathlib.PurePosixPath(path).parent.as_posix() in ("docs", "docs/agents")]
    for doc in docs:
        relative_doc = doc.relative_to(root).as_posix()
        if revision is not None:
            result = subprocess.run(["git", "show", f"{revision}:{relative_doc}"], cwd=root, capture_output=True, text=True, encoding="utf-8")
            if result.returncode:
                failures.append(f"{relative_doc}: cannot read publication document")
                continue
            content = result.stdout
        else:
            if not doc.is_file():
                failures.append(f"{relative_doc}: missing current document")
                continue
            content = doc.read_text(encoding="utf-8")
        content = re.sub(r"```.*?```", "", content, flags=re.S)
        for match in re.finditer(r"(?<!!)\[[^\]\n]*\]\(([^)\n]+)\)", content):
            target = match[1].strip("<>").split("#", 1)[0]
            if not target or re.match(r"[A-Za-z][A-Za-z0-9+.-]*:", target):
                continue
            if revision is not None:
                # A historical audit must not follow today's filesystem links.
                relative_target = posixpath.normpath(posixpath.join(posixpath.dirname(relative_doc), target))
                if relative_target == ".." or relative_target.startswith(("../", "/")):
                    failures.append(f"{relative_doc}: document link escapes repository {target}")
                    continue
            else:
                resolved = (doc.parent / target).resolve()
                if not resolved.is_relative_to(root.resolve()):
                    failures.append(f"{relative_doc}: document link escapes repository {target}")
                    continue
                relative_target = resolved.relative_to(root.resolve()).as_posix()
            if not local_handoff and relative_target in LOCAL_RECORDS:
                # Explicit legacy local-only records. Never depend on their
                # bytes being present or publish them to make CI succeed.
                continue
            # Paths to local artifacts must be stated as local records rather
            # than presented as portable repository documentation links.
            if any(part == ".private" or part.startswith(".tmp-") for part in pathlib.PurePosixPath(relative_target).parts):
                failures.append(f"{doc.relative_to(root)}: local artifact link {target}")
            elif publication:
                if relative_target not in published:
                    failures.append(f"{relative_doc}: missing published link {target}")
            elif not resolved.exists():
                failures.append(f"{doc.relative_to(root)}: missing link {target}")
    return failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument("--publication", action="store_true", help="Portable CI checks; leave local-only HANDOFF and reports private")
    parser.add_argument("--revision", help="With --publication, audit document bytes and paths from this Git revision instead of the tracked worktree")
    args = parser.parse_args()
    if args.revision and not args.publication:
        parser.error("--revision requires --publication")
    errors = check(args.root, args.publication, args.revision)
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print("Release identity and current documentation checks passed")
    sys.exit(bool(errors))
