# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Enforce the existing pure-JVM shared/data dependency direction."""
from __future__ import annotations

import pathlib
import re


def boundary_violations(root: pathlib.Path) -> list[str]:
    errors = []
    for layer in ("shared", "data"):
        for build in sorted((root / layer).glob("*/build.gradle.kts")):
            text = build.read_text(encoding="utf-8")
            if "libs.plugins.android" in text:
                errors.append(f"{build.relative_to(root)}: pure-JVM layer applies Android plugin")
            for dependency in re.findall(r'project\("(:[^" ]+)"\)', text):
                allowed = dependency.startswith(":shared:") or (layer == "data" and dependency.startswith(":data:"))
                if not allowed:
                    errors.append(f"{build.relative_to(root)}: upward dependency {dependency}")
            for source in sorted(build.parent.glob("src/main/**/*.kt")):
                if re.search(r"^import\s+(android\.|androidx\.)", source.read_text(encoding="utf-8"), re.MULTILINE):
                    errors.append(f"{source.relative_to(root)}: Android import in pure-JVM production source")
    for name in ("agents", "knowledge", "providers"):
        build = root / "feature" / name / "build.gradle.kts"
        if build.is_file() and re.search(r'project\(":data:sqlite"\)', build.read_text(encoding="utf-8")):
            errors.append(f"{build.relative_to(root)}: presentation module depends directly on SQLite")
    return errors


if __name__ == "__main__":
    failures = boundary_violations(pathlib.Path(__file__).resolve().parent.parent)
    print("\n".join(failures) if failures else "Module boundaries: pure-JVM shared/data and presentation dependency direction verified.")
    raise SystemExit(bool(failures))
