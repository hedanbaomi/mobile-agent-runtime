# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Enforce the existing pure-JVM shared/data and presentation dependency direction."""
from __future__ import annotations

import pathlib
import re

FEATURE_MODULES = (
    "chat",
    "agents",
    "providers",
    "knowledge",
    "skills",
    "announcements",
    "settings",
    "worldbooks",
)

PROJECT_DEP_PATTERN = re.compile(
    r'project\(\s*(?:path\s*=\s*)?["\'](:[^"\'\s]+)["\']\s*\)',
    re.MULTILINE,
)


def strip_comments(text: str) -> str:
    clean_text = re.sub(r"//.*$", "", text, flags=re.MULTILINE)
    return re.sub(r"/\*.*?\*/", "", clean_text, flags=re.DOTALL)


def boundary_violations(root: pathlib.Path) -> list[str]:
    errors = []
    for layer in ("shared", "data"):
        for build in sorted((root / layer).glob("*/build.gradle.kts")):
            text = build.read_text(encoding="utf-8")
            clean_text = strip_comments(text)
            if "libs.plugins.android" in clean_text:
                errors.append(f"{build.relative_to(root)}: pure-JVM layer applies Android plugin")
            for dependency in PROJECT_DEP_PATTERN.findall(clean_text):
                allowed = dependency.startswith(":shared:") or (layer == "data" and dependency.startswith(":data:"))
                if not allowed:
                    errors.append(f"{build.relative_to(root)}: upward dependency {dependency}")
            for source in sorted(build.parent.glob("src/main/**/*.kt")):
                if re.search(r"^import\s+(android\.|androidx\.)", source.read_text(encoding="utf-8"), re.MULTILINE):
                    errors.append(f"{source.relative_to(root)}: Android import in pure-JVM production source")
    for name in FEATURE_MODULES:
        build = root / "feature" / name / "build.gradle.kts"
        if not build.is_file():
            continue
        text = build.read_text(encoding="utf-8")
        clean_text = strip_comments(text)
        deps = PROJECT_DEP_PATTERN.findall(clean_text)
        if ":data:sqlite" in deps:
            errors.append(f"{build.relative_to(root)}: presentation module depends directly on SQLite")
        for dependency in deps:
            if dependency == ":data:sqlite":
                continue
            if dependency.startswith(":feature:"):
                errors.append(f"{build.relative_to(root)}: illegal cross-feature dependency {dependency}")
            elif not dependency.startswith(":shared:"):
                errors.append(f"{build.relative_to(root)}: presentation module depends on disallowed layer {dependency}")
    return errors


if __name__ == "__main__":
    failures = boundary_violations(pathlib.Path(__file__).resolve().parent.parent)
    print("\n".join(failures) if failures else "Module boundaries: pure-JVM shared/data and presentation dependency direction verified.")
    raise SystemExit(bool(failures))
