# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Validate explicit instrumentation suites against real source test classes."""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent


def test_classes(root: pathlib.Path) -> set[str]:
    result = set()
    for source in (root / "app-android/src/androidTest").rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        if not re.search(r"@Test\b", text):
            continue
        package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        # File names identify these top-level JUnit fixtures; helper classes
        # inside the same file are not runner targets.
        if package and re.search(rf"\bclass\s+{re.escape(source.stem)}\b", text):
            result.add(f"{package.group(1)}.{source.stem}")
        else:
            raise ValueError(f"JUnit source needs an explicit top-level target: {source}")
    return result


def violations(root: pathlib.Path, manifest: dict) -> list[str]:
    actual = test_classes(root)
    suites = manifest["suites"]
    manual = manifest["manual_only"]
    errors = []
    selected = set()
    for name, classes in suites.items():
        if not classes or len(classes) != len(set(classes)):
            errors.append(f"{name}: empty or duplicate class list")
        selected.update(classes)
    mapped = selected | set(manual)
    for target in sorted(mapped - actual):
        errors.append(f"mapped class does not exist: {target}")
    for target in sorted(actual - mapped):
        errors.append(f"unmapped test class: {target}")
    for target, reason in manual.items():
        if not isinstance(reason, str) or not reason.strip():
            errors.append(f"manual-only class needs a reason: {target}")
        if target in selected:
            errors.append(f"manual-only class is selected by CI: {target}")
    if set(suites["full"]) != actual - set(manual):
        errors.append("full suite must explicitly include every non-manual test class")
    workflow = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    for name in ("smoke", "convergence", "pipeline"):
        if ",".join(suites[name]) not in workflow:
            errors.append(f"ci.yml selector differs from {name} manifest")
    return errors


def result_summary(directory: pathlib.Path) -> str:
    lines = ["### Instrumentation result summary"]
    files = sorted(directory.rglob("TEST-*.xml"))
    if not files:
        return "### Instrumentation result summary\nNo JUnit XML found; device acceptance is unverified.\n"
    tests = failures = errors = skipped = 0
    for source in files:
        tree = ET.parse(source)
        for case in tree.getroot().iter("testcase"):
            tests += 1
            failures += case.find("failure") is not None
            errors += case.find("error") is not None
            skip = case.find("skipped")
            if skip is not None:
                skipped += 1
                lines.append(f"- SKIPPED {case.get('classname')}.{case.get('name')}: {skip.get('message') or skip.text or 'JUnit assumption'}")
    lines.insert(1, f"Tests: {tests}; failures: {failures}; errors: {errors}; skipped: {skipped}.")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", choices=("smoke", "convergence", "pipeline", "full"))
    parser.add_argument("--summary", type=pathlib.Path)
    args = parser.parse_args()
    manifest = json.loads((ROOT / "tools/instrumentation-suites.json").read_text(encoding="utf-8"))
    errors = violations(ROOT, manifest)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    if args.suite:
        print(",".join(manifest["suites"][args.suite]))
    elif args.summary:
        print(result_summary(args.summary))
        print("### Explicit manual-only exclusions")
        for target, reason in manifest["manual_only"].items():
            print(f"- {target}: {reason}")
    else:
        print(f"Test selection: {len(test_classes(ROOT))} real classes mapped; full suite {len(manifest['suites']['full'])}; manual-only {len(manifest['manual_only'])}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
