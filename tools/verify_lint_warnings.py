# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Reject new Android lint warnings against an explicitly reviewed inventory.

Line numbers are excluded so a source move does not renew existing warnings.
Error/Fatal findings always fail; they can never be entered in this baseline.
Online version-availability notices stay in lint reports but are not source
regressions: their results change when repository metadata changes.

A shared build cache can serve another checkout's lint model, so a report may
carry an absolute path from a foreign root. Such a path is relativized only when
its longest suffix names an existing path in this checkout; an absolute path
that cannot be confirmed is never stripped or guessed at. Unconfirmable
locations fail the gate and cannot be baselined, so an unknown warning can never
enter the inventory through a cache from elsewhere.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import xml.etree.ElementTree as ET


VERSION_AVAILABILITY_WARNINGS = frozenset({"AndroidGradlePluginVersion", "GradleDependency"})


def _posix(path: str) -> str:
    return path.replace("\\", "/")


def _is_absolute(path: str) -> bool:
    return path.startswith("/") or (len(path) > 2 and path[1] == ":" and path[2] == "/")


def _local_relative(path: str, root: pathlib.Path) -> str | None:
    """Return the checkout-relative form of an absolute lint location.

    The longest local suffix that names an existing path in this checkout wins,
    which recovers the source path a foreign root prefixed without trusting the
    prefix itself. A lint location may be a directory (for example a resource
    folder), so existence rather than file-ness is the anchor. A path with no
    such suffix, or one containing a traversal segment, returns None so the
    caller can reject rather than guess.
    """
    parts = path.split("/")
    if ".." in parts:
        return None
    for start in range(1, len(parts) - 1):
        candidate = "/".join(parts[start:])
        if (root / candidate).exists():
            return candidate
    return None


def findings(report: pathlib.Path, root: pathlib.Path) -> tuple[set[str], list[str], list[str]]:
    warnings, errors, unverifiable = set(), [], []
    prefix = root.as_posix().rstrip("/") + "/"
    for issue in ET.parse(report).getroot().iter("issue"):
        severity = issue.get("severity")
        if severity not in ("Warning", "Error", "Fatal"):
            continue
        if severity == "Warning" and issue.get("id") in VERSION_AVAILABILITY_WARNINGS:
            continue
        locations, unconfirmed = [], False
        for location in issue.findall("location"):
            path = _posix(location.get("file", ""))
            if path.startswith(prefix):
                locations.append(path[len(prefix):])
            elif not _is_absolute(path):
                locations.append(path)
            else:
                relative = _local_relative(path, root)
                if relative is None:
                    unverifiable.append(path)
                    unconfirmed = True
                else:
                    locations.append(relative)
        if unconfirmed:
            # An issue whose source cannot be placed in this checkout is not
            # comparable with the reviewed inventory; it must fail the gate.
            continue
        identity = json.dumps([issue.get("id"), sorted(locations), issue.get("message")], ensure_ascii=False)
        if severity == "Warning":
            warnings.add(identity)
        else:
            errors.append(identity)
    return warnings, errors, sorted(set(unverifiable))


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=pathlib.Path, default=root / "app-android/build/reports/lint-results-debug.xml")
    parser.add_argument("--write-baseline", action="store_true", help="Explicitly initialize/update reviewed warning inventory; never used by check/CI")
    args = parser.parse_args()
    warnings, errors, unverifiable = findings(args.report, root)
    if errors:
        print("Lint Error/Fatal findings cannot be baselined:\n" + "\n".join(errors))
        return 1
    if unverifiable:
        print(
            "Lint report locations are outside this checkout and cannot be matched to a local source file. "
            "Rebuild the lint model instead of accepting them:\n" + "\n".join(unverifiable)
        )
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
