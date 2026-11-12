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


def _workflow_path_matches(path: str, pattern: str) -> bool:
    parts: list[str] = ["^"]
    i = 0
    n = len(pattern)
    while i < n:
        if pattern[i:i + 4] == "/**/":
            parts.append("(?:/.+/|/)")
            i += 4
        elif pattern[i:i + 3] == "**/":
            parts.append("(?:.+/)?")
            i += 3
        elif pattern[i:i + 3] == "/**" and i + 3 == n:
            parts.append("/.*")
            i += 3
        elif pattern[i:i + 2] == "**":
            parts.append(".*")
            i += 2
        elif pattern[i] == "*":
            parts.append("[^/]*")
            i += 1
        elif pattern[i] == "?":
            parts.append("[^/]")
            i += 1
        else:
            parts.append(re.escape(pattern[i]))
            i += 1
    parts.append("$")
    return re.match("".join(parts), path) is not None


def pr_triggers_recovery(root: pathlib.Path, changed_paths: list[str], manifest: dict | None = None) -> list[str]:
    import yaml
    full = yaml.safe_load((root / ".github/workflows/full-instrumentation.yml").read_text(encoding="utf-8"))
    events = full.get("on", full.get(True, {}))
    patterns = events.get("pull_request", {}).get("paths", [])
    if any(_workflow_path_matches(changed, pattern) for changed in changed_paths for pattern in patterns):
        if manifest is None:
            manifest = json.loads((root / "tools/instrumentation-suites.json").read_text(encoding="utf-8"))
        return select_suite(manifest, "recovery")
    return []


def select_suite(manifest: dict, name: str) -> list[str]:
    if name == "nightly":
        return [target for target in manifest["suites"]["full"] if target not in manifest["performance"]]
    if name == "performance":
        return manifest["performance"]
    if name == "minimum":
        return manifest["minimum_api"]
    if name == "recovery":
        return manifest["recovery"]
    return manifest["suites"][name]


def invocation_groups(manifest: dict, name: str) -> list[list[str]]:
    selected = select_suite(manifest, name)
    fresh = set(manifest.get("fresh_host_first", []))
    groups = [[target] for target in selected if target in fresh]
    rest = [target for target in selected if target not in fresh]
    return groups + ([rest] if rest else [])


def workflow_matrix_violations(root: pathlib.Path) -> list[str]:
    import yaml
    errors = []
    ci = yaml.safe_load((root / '.github/workflows/ci.yml').read_text(encoding='utf-8'))
    full = yaml.safe_load((root / '.github/workflows/full-instrumentation.yml').read_text(encoding='utf-8'))
    def scripts(job):
        return '\n'.join(str(step.get('with', {}).get('script', '')) for step in job.get('steps', []))
    ci_jobs = ci.get('jobs', {})
    smoke = ci_jobs.get('android-smoke', {})
    if 26 not in smoke.get('strategy', {}).get('matrix', {}).get('api-level', []):
        errors.append('API26 must remain in the smoke matrix')
    if "bash tools/run-device-suite smoke-api Debug '${{ matrix.api-level }}'" not in scripts(smoke):
        errors.append('smoke must pass the API level to the minimum-suite router')
    for job_name in ('android-smoke', 'convergence-device', 'knowledge-pipeline-device'):
        job = ci_jobs.get(job_name, {})
        needs = job.get('needs', [])
        needs_set = {needs} if isinstance(needs, str) else set(needs or [])
        if 'check' not in needs_set:
            errors.append(f'{job_name} must depend on check to consume prebuilt debug APKs')
        downloads = [
            step for step in job.get('steps', [])
            if str(step.get('uses', '')).startswith('actions/download-artifact@')
            and step.get('with', {}).get('name') == 'debug-apks-${{ github.sha }}'
        ]
        if not downloads:
            errors.append(f'{job_name} must download debug-apks-${{{{ github.sha }}}}')
        if '--prebuilt-apk-dir' not in scripts(job):
            errors.append(f'{job_name} must pass --prebuilt-apk-dir to tools/run-device-suite')
    events = full.get('on', full.get(True, {}))
    if not events.get('schedule'):
        errors.append('nightly device schedule is required')
    paths = events.get('pull_request', {}).get('paths', [])
    required = ['app-android/src/main/kotlin/runtime/mobileagent/MobileAgentApp.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/HostInitialization.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/DatabaseRecoveryStore.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/RunCoordinator.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/RunTools.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/BoundToolExecutor.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/ChatExecutionController.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/ChatRuntimeEvents.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/ChatToolApprovalRequest.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/ChatApprovalOutcome.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/ChatRootPreflight.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/ConfirmedDeviceActionCancellation.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/ChatRunLifecycle.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/integration/RuntimeIntegration.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/security/PrivilegedWorkspaceBindingCipher.kt',
                'app-android/src/main/kotlin/runtime/mobileagent/tooling/ApprovalEngine.kt',
                'data/sqlite/src/main/kotlin/runtime/mobileagent/data/Migrations.kt',
                'data/sqlite/src/main/kotlin/runtime/mobileagent/data/migrations/MigrationV2ControlPlane.kt',
                'data/sqlite/src/main/kotlin/runtime/mobileagent/data/RootRunAdmission.kt',
                'data/sqlite/src/main/kotlin/runtime/mobileagent/data/RootAuthorityRepository.kt',
                'data/sqlite/src/main/kotlin/runtime/mobileagent/data/RunRepository.kt',
                'tools/instrumentation-suites.json', 'tools/verify_test_selection.py']
    for path in required:
        if not any(_workflow_path_matches(path, pattern) for pattern in paths):
            errors.append(f'Review recovery trigger missing: {path}')
    expected = "bash tools/run-device-suite '${{ github.event_name == 'pull_request' && 'recovery' || inputs.suite || 'nightly' }}' '${{ github.event_name == 'pull_request' && 'Review' || inputs.variant || 'Debug' }}'"
    if expected not in scripts(full.get('jobs', {}).get('full-device', {})):
        errors.append('PR recovery must use Review and scheduled runs must use nightly')
    return errors


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
    for name in ("performance", "minimum_api", "recovery"):
        if name not in manifest:
            continue
        targets = manifest[name]
        if not targets or len(targets) != len(set(targets)) or not set(targets) <= set(suites["full"]):
            errors.append(f"{name}: must contain distinct automated full-suite targets")
    if "performance" in manifest:
        nightly = select_suite(manifest, "nightly")
        if not nightly or set(nightly) & set(manifest["performance"]):
            errors.append("nightly and performance must be nonempty disjoint partitions")
    # These fixtures intentionally require the deferred app host and build their
    # own synthetic storage. Keep them ahead of tests that initialize the host;
    # never remove their safety guard to make combined execution pass.
    fresh = manifest.get("fresh_host_first", [])
    if len(fresh) != len(set(fresh)) or any(target not in actual - set(manual) for target in fresh):
        errors.append("fresh-host targets must be distinct non-manual test classes")
    for name, classes in suites.items():
        required = [target for target in fresh if target in classes]
        if classes[:len(required)] != required:
            errors.append(f"{name}: fresh-host tests must run before host-initializing fixtures")
    workflow = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    for name in ("smoke", "convergence", "pipeline"):
        if ",".join(suites[name]) not in workflow and f"run-device-suite {name}" not in workflow:
            errors.append(f"ci.yml selector differs from {name} manifest")
    if any(name in manifest for name in ('performance', 'minimum_api', 'recovery')):
        if not all(name in manifest for name in ('performance', 'minimum_api', 'recovery')):
            errors.append('performance, minimum_api and recovery must all be declared')
        errors.extend(workflow_matrix_violations(root))
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
    parser.add_argument("--suite", choices=("smoke", "convergence", "pipeline", "full", "nightly", "performance", "minimum", "recovery"))
    parser.add_argument("--summary", type=pathlib.Path)
    parser.add_argument("--groups", action="store_true", help="Isolate fresh-host classes in separate instrumentation processes")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "tools/instrumentation-suites.json").read_text(encoding="utf-8"))
    errors = violations(ROOT, manifest)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    if args.suite:
        if args.groups:
            print("\n".join(",".join(group) for group in invocation_groups(manifest, args.suite)))
        else:
            print(",".join(select_suite(manifest, args.suite)))
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
