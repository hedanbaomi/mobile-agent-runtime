# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Reject new Android lint warnings against an explicitly reviewed inventory.

Line numbers are excluded so a source move does not renew existing warnings.
Error/Fatal findings always fail; they can never be entered in this baseline.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import xml.etree.ElementTree as ET


def findings(report: pathlib.Path, root: pathlib.Path) -> tuple[set[str], list[str]]:
    warnings, errors = set(), []
    prefix = root.as_posix().rstrip("/") + "/"
    for issue in ET.parse(report).getroot().iter("issue"):
        severity = issue.get("severity")
        if severity not in ("Warning", "Error", "Fatal"):
            continue
        locations = [location.get("file", "").replace("\\", "/").removeprefix(prefix) for location in issue.findall("location")]
        identity = json.dumps([issue.get("id"), sorted(locations), issue.get("message")], ensure_ascii=False)
        if severity == "Warning":
            warnings.add(identity)
        else:
            errors.append(identity)
    return warnings, errors


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=pathlib.Path, default=root / "app-android/build/reports/lint-results-debug.xml")
    parser.add_argument("--write-baseline", action="store_true", help="Explicitly initialize/update reviewed warning inventory; never used by check/CI")
    args = parser.parse_args()
    warnings, errors = findings(args.report, root)
    if errors:
        print("Lint Error/Fatal findings cannot be baselined:\n" + "\n".join(errors))
        return 1
    baseline = root / "tools/lint-warning-baseline.json"
    if args.write_baseline:
        baseline.write_text(json.dumps({"SPDX-FileCopyrightText": "2026 mobileAgentRuntime contributors", "SPDX-License-Identifier": "AGPL-3.0-only", "warnings": sorted(warnings)}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"Explicit lint warning inventory written: {len(warnings)} warnings; review the baseline diff.")
        return 0
    allowed = set(json.loads(baseline.read_text(encoding="utf-8"))["warnings"])
    added = sorted(warnings - allowed)
    if added:
        print("New lint warnings require repair or an explicit reviewed baseline change:\n" + "\n".join(added))
        return 1
    print(f"Lint warning ratchet: PASS ({len(warnings)} existing warnings; zero new warnings).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
