# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Fail closed when a GitHub Actions workflow is not parseable YAML.

Minimal syntax gate for .github/workflows (b07 follow-up finding A): an
invalid workflow file fails the whole CI run with zero jobs, which is easy
to misread as "nothing ran".  This check parses every workflow, requires the
minimal Actions shape (on/jobs/runs-on/steps), and forbids
``continue-on-error: true`` so a red security job can never be silenced.

Requires PyYAML (``python -m pip install "pyyaml==6.0.2"``).  A missing
dependency is a failure, never a skip.
"""

from __future__ import annotations

import pathlib
import sys


try:
    import yaml
except ImportError:
    print("verify-workflows: PyYAML is required: python -m pip install \"pyyaml==6.0.2\"")
    raise SystemExit(1)


_MISSING = object()


def check_workflow(path: pathlib.Path) -> list[str]:
    violations: list[str] = []
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as error:
        return [f"{path}: YAML parse error: {error}"]
    if not isinstance(document, dict):
        return [f"{path}: top level must be a mapping"]
    # PyYAML implements YAML 1.1: an unquoted `on:` key parses as boolean True.
    triggers = document.get("on", document.get(True, _MISSING))
    if triggers is _MISSING:
        violations.append(f"{path}: missing top-level `on` trigger")
    jobs = document.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        violations.append(f"{path}: missing non-empty top-level `jobs` mapping")
        return violations
    for job_id, job in jobs.items():
        prefix = f"{path} job {job_id!r}"
        if not isinstance(job, dict):
            violations.append(f"{prefix}: job must be a mapping")
            continue
        if "runs-on" not in job:
            violations.append(f"{prefix}: missing `runs-on`")
        steps = job.get("steps")
        if not isinstance(steps, list) or not steps:
            violations.append(f"{prefix}: missing non-empty `steps` list")
            continue
        for index, step in enumerate(steps):
            if not isinstance(step, dict) or ("uses" not in step and "run" not in step):
                violations.append(f"{prefix} step {index}: step needs `uses` or `run`")
    text = path.read_text(encoding="utf-8")
    # This repository never silences a gate: any opt-out is a violation.
    for lineno, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip().strip("'\"")
        if stripped.startswith("continue-on-error:") and "true" in stripped.lower():
            violations.append(f"{path}:{lineno}: `continue-on-error: true` is forbidden")
    return violations


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent / ".github" / "workflows"
    workflows = sorted(
        [p for p in root.iterdir() if p.is_file() and p.suffix in (".yml", ".yaml")]
    )
    if not workflows:
        print("verify-workflows: no workflows found")
        return 1
    violations: list[str] = []
    for workflow in workflows:
        violations.extend(check_workflow(workflow))
    if violations:
        print("verify-workflows: FAILED")
        for violation in violations:
            print(f"  {violation}")
        return 1
    print(f"verify-workflows: {len(workflows)} workflow(s) valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
