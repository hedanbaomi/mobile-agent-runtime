# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Dependency-free conditional-token ratchet for Kotlin production files.

This is a coarse maintainability metric, not a cyclomatic-complexity claim.
Existing files over 75 decisions have explicit budgets; new files use 75.
Budget increases require a reviewed baseline edit rather than silent renewal.
"""
from __future__ import annotations

import json
import os
import pathlib
import re

DEFAULT_BUDGET = 75
LAYERS = ("app-android", "shared", "data", "feature", "platform", "runtime", "desktop")


def decisions(source: str) -> int:
    # Ignore comments and literal text, including strings containing Kotlin
    # snippets or diagnostic messages. Branches inside interpolation are also
    # excluded consistently: this metric measures source structure only.
    tokens = re.sub(r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', " ", source, flags=re.DOTALL)
    return len(re.findall(r"\b(?:if|when|for|while|catch)\b|&&|\|\||\?:", tokens))


def production_sources(root: pathlib.Path):
    for layer in LAYERS:
        sources = []
        for directory, children, files in os.walk(root / layer):
            folder = pathlib.Path(directory)
            if "src" in children:
                sources.extend((folder / "src/main").rglob("*.kt"))
            children[:] = [name for name in children if name not in {"src", ".gradle", ".cxx"}]
            # Prune a module's output, while allowing modules and Kotlin packages named "build".
            scripts = ("build.gradle.kts", "build.gradle")
            if "build" in children and any(script in files for script in scripts):
                if not any((folder / "build" / script).is_file() for script in scripts):
                    children.remove("build")
        yield from sorted(sources)


def violations(root: pathlib.Path, budgets: dict[str, int]) -> list[str]:
    errors = []
    for source in production_sources(root):
        relative = source.relative_to(root).as_posix()
        count = decisions(source.read_text(encoding="utf-8"))
        budget = budgets.get(relative, DEFAULT_BUDGET)
        if count > budget:
            errors.append(f"{relative}: {count} conditional tokens exceed reviewed budget {budget}; extract behavior or review an explicit budget change")
    return errors


if __name__ == "__main__":
    root = pathlib.Path(__file__).resolve().parent.parent
    baseline = json.loads((root / "tools/complexity-baseline.json").read_text(encoding="utf-8"))
    failures = violations(root, baseline["budgets"])
    print("\n".join(failures) if failures else "Kotlin conditional-token ratchet: PASS (reviewed existing budgets; new-file limit 75).")
    raise SystemExit(bool(failures))
