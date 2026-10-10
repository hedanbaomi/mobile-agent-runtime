# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Check release rollback and the current handoff/document surface."""
import argparse
import pathlib
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


def version(text):
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.M)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    if not code or not name:
        raise ValueError("Missing literal Android release identity")
    return int(code[1]), name[1]


def check(root, publication=False):
    failures = []
    current = version((root / RELEASE_FILE).read_text(encoding="utf-8"))
    floors = [RELEASE_FLOOR]
    tags = subprocess.check_output(["git", "tag", "--list", "v*"], cwd=root, text=True, encoding="utf-8").splitlines()
    for tag in tags:
        if re.fullmatch(r"v\d+\.\d+\.\d+", tag):
            blob = subprocess.check_output(["git", "show", f"{tag}:{RELEASE_FILE}"], cwd=root, text=True, encoding="utf-8")
            floors.append(version(blob))
    floor = max(floors)
    if current[0] < floor[0] or (current[0] == floor[0] and current[1] != floor[1]):
        failures.append(f"Release rollback: {current} is below/different from public {floor}")
    if not publication:
        handoff = (root / "HANDOFF.md").read_text(encoding="utf-8")
        if len(handoff.encode("utf-8")) > 24000 or len(handoff.splitlines()) > 120:
            failures.append("HANDOFF exceeds current-summary budget; archive completed history")
        if handoff.count("## 当前任务") != 1:
            failures.append("HANDOFF must contain exactly one current-task section")
    # Archived evidence keeps its historical interpretation and is not a live
    # requirement. Only current entry points are checked here; no disk-wide scan.
    docs = [] if publication else [root / "HANDOFF.md"]
    # Local reports are intentionally untracked WIP, not portable requirements.
    tracked = set(subprocess.check_output(["git", "ls-files"], cwd=root, text=True, encoding="utf-8").splitlines())
    tree = subprocess.run(["git", "ls-tree", "-r", "--name-only", "HEAD"], cwd=root, capture_output=True, text=True, encoding="utf-8")
    published = set(tree.stdout.splitlines()) if tree.returncode == 0 else tracked
    docs += [p for p in [*sorted((root / "docs").glob("*.md")), *sorted((root / "docs/agents").glob("*.md"))]
             if p.relative_to(root).as_posix() in tracked]
    for doc in docs:
        relative_doc = doc.relative_to(root).as_posix()
        if publication:
            result = subprocess.run(["git", "show", f"HEAD:{relative_doc}"], cwd=root, capture_output=True, text=True, encoding="utf-8")
            if result.returncode:
                result = subprocess.run(["git", "show", f":{relative_doc}"], cwd=root, capture_output=True, text=True, encoding="utf-8")
            content = result.stdout
        else:
            content = doc.read_text(encoding="utf-8")
        content = re.sub(r"```.*?```", "", content, flags=re.S)
        for match in re.finditer(r"(?<!!)\[[^\]\n]*\]\(([^)\n]+)\)", content):
            target = match[1].strip("<>").split("#", 1)[0]
            if not target or re.match(r"[A-Za-z][A-Za-z0-9+.-]*:", target):
                continue
            resolved = (doc.parent / target).resolve()
            if not resolved.is_relative_to(root.resolve()):
                failures.append(f"{relative_doc}: document link escapes repository {target}")
                continue
            relative_target = resolved.relative_to(root.resolve()).as_posix()
            if publication and relative_target in LOCAL_RECORDS:
                # Explicit legacy local-only records. Never depend on their
                # bytes being present or publish them to make CI succeed.
                continue
            # Paths to local artifacts must be stated as local records rather
            # than presented as portable repository documentation links.
            if target.startswith((".private/", ".tmp-", "../.private/")):
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
    args = parser.parse_args()
    errors = check(args.root, args.publication)
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print("Release identity and current documentation checks passed")
    sys.exit(bool(errors))
