# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Opt-in synthetic emulator storage fault acceptance on an installed test APK.

Index stages stop the exact app PID at a signalled production SQL publication
boundary. Optional ENOSPC uses an ALREADY prepared, app-owned <=8MiB tmpfs at
/data/user/0/<application-id>/files/storage-publication-<uuid>/enospc-mount.
The app itself verifies its mount namespace/type/capacity before filling it.
This script never roots, mounts, installs, clears app data, fills a real volume,
uses a Provider, or deletes a fixture. Mount setup/teardown remains explicit
coordinator work. Keep tmpfs mounted until ENOSPC evidence has been collected.
"""

import argparse
import json
import pathlib
import re
import subprocess
import time
import uuid

STAGES = ("INDEX_BEFORE", "INDEX_AFTER")
TEST_CLASS = "runtime.mobileagent.StoragePublicationFaultDeviceTest"
BOUNDARIES = {
    "INDEX_BEFORE": ("ready_before_pointer_write_uncommitted", 1),
    "INDEX_AFTER": ("pointer_transaction_committed", 0),
}
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--application-id", required=True)
    parser.add_argument("--runner", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--stage", choices=STAGES, action="append")
    parser.add_argument("--enospc-fixture-path", help="Explicit already-mounted bounded app-owned tmpfs")
    parser.add_argument("--enospc-only", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("An explicit synthetic emulator serial is required")
    if not re.fullmatch(r"runtime\.mobileagent(?:\.[a-zA-Z0-9_]+)*", args.application_id):
        parser.error("Unexpected application id")
    if not re.fullmatch(r"[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+", args.runner):
        parser.error("Invalid instrumentation component")
    if args.enospc_only and (not args.enospc_fixture_path or args.stage):
        parser.error("--enospc-only requires --enospc-fixture-path and excludes --stage")
    enospc_id = None
    if args.enospc_fixture_path:
        match = re.fullmatch(r"/data/user/0/" + re.escape(args.application_id) +
                             r"/files/storage-publication-(" + UUID + r")/enospc-mount", args.enospc_fixture_path)
        if not match:
            parser.error("ENOSPC path must name the unique target-app-owned synthetic fixture")
        enospc_id = match.group(1)
    adb = [args.adb, "-s", args.serial]

    def command(*parts, timeout=30, check=True):
        return subprocess.run(adb + list(parts), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding="utf-8", errors="replace", timeout=timeout, check=check)

    def fixture_json(fixture_id, name):
        # Only known synthetic paths, never application configuration or secrets.
        path = "files/storage-publication-" + fixture_id + "/" + name
        return json.loads(command("shell", "run-as", args.application_id, "cat", path).stdout)

    def verify_test(base, method, folder):
        result = command(*(base + ["-e", "class", TEST_CLASS + "#" + method, args.runner]),
                         timeout=150, check=False)
        (folder / "verify.txt").write_text(result.stdout, encoding="utf-8")
        if (result.returncode != 0 or "OK (1 test)" not in result.stdout or
                "FAILURES!!!" in result.stdout or "INSTRUMENTATION_FAILED" in result.stdout):
            raise RuntimeError("Instrumentation verification failed; see " + str(folder))

    if command("get-state").stdout.strip() != "device":
        raise RuntimeError("Requested emulator is not ready")
    expected = f"instrumentation:{args.runner} (target={args.application_id})"
    if expected not in command("shell", "pm", "list", "instrumentation").stdout.splitlines():
        raise RuntimeError("Installed instrumentation does not target the requested app")
    output = args.output.resolve() / ("storage-publication-" + str(uuid.uuid4()))
    output.mkdir(parents=True, exist_ok=False)
    summary = {"schema": 1, "serial": args.serial, "applicationId": args.application_id,
               "runner": args.runner, "stages": [],
               "limits": "Synthetic hashing vectors; SQLite/CAS/repository/USearch JNI; no provider, physical device or real user data"}
    current = None
    try:
        for stage in [] if args.enospc_only else (args.stage or STAGES):
            fixture_id = str(uuid.uuid4())
            folder = output / stage
            folder.mkdir()
            current = {"stage": stage, "fixtureId": fixture_id, "status": "STARTED"}
            summary["stages"].append(current)
            base = ["shell", "am", "instrument", "-w", "-r", "-e", "faultStage", stage,
                    "-e", "faultFixtureId", fixture_id]
            prepare = adb + base + ["-e", "class", TEST_CLASS + "#prepareIndexAndWaitForHostKill", args.runner]
            with (folder / "prepare.txt").open("wb") as stream:
                process = subprocess.Popen(prepare, stdout=stream, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 90
                    pid = None
                    pattern = re.compile(r"MAR_STORAGE_CHECKPOINT stage=" + stage +
                        r" boundary=(\w+) schemaVersion=(\d+) transactionDepth=(\d+) candidateMembers=(\d+)" +
                        r" pid=(\d+) previous=(" + UUID + r") candidate=(" + UUID +
                        r") fixture=storage-publication-" + fixture_id)
                    while time.monotonic() < deadline:
                        text = (folder / "prepare.txt").read_text(encoding="utf-8", errors="replace")
                        match = pattern.search(text)
                        if match:
                            boundary, version, depth, members, pid, previous, candidate = match.groups()
                            expected_boundary, expected_depth = BOUNDARIES[stage]
                            if (boundary != expected_boundary or int(version) < 40 or
                                    int(depth) != expected_depth or int(members) != 1 or previous == candidate):
                                raise RuntimeError(stage + ": marker does not prove the exact requested publication boundary")
                            checkpoint = fixture_json(fixture_id, "checkpoint.json")
                            expected_active = previous if stage == "INDEX_BEFORE" else candidate
                            exact = {"stage": stage, "boundary": boundary, "schemaVersion": int(version),
                                     "transactionDepth": int(depth), "candidateMembers": 1, "pid": int(pid),
                                     "previous": previous, "candidate": candidate, "active": expected_active}
                            if checkpoint != exact:
                                raise RuntimeError(stage + ": durable checkpoint disagrees with live instrumentation marker")
                            (folder / "checkpoint.json").write_text(json.dumps(checkpoint, indent=2) + "\n", encoding="utf-8")
                            current["checkpoint"] = checkpoint
                            break
                        if process.poll() is not None:
                            raise RuntimeError(stage + ": preparation ended without the exact checkpoint")
                        time.sleep(0.1)  # Poll a durable signal; never choose the kill boundary by elapsed time.
                    if pid is None:
                        raise RuntimeError(stage + ": required checkpoint was not reached in 90 seconds")
                    if pid not in command("shell", "pidof", args.application_id).stdout.split():
                        raise RuntimeError(stage + ": exact checkpoint PID is not owned by the target app")
                    (folder / "force-stop.txt").write_text(
                        command("shell", "am", "force-stop", args.application_id).stdout, encoding="utf-8")
                    if command("shell", "test", "-d", "/proc/" + pid, check=False).returncode == 0:
                        raise RuntimeError(stage + ": checkpoint PID still exists after force-stop")
                    current["killedPid"] = int(pid)
                    current["oldPidGone"] = True
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()  # Only this script's host adb client.
                        process.wait(timeout=10)
                    current["expectedKilledInstrumentationExit"] = process.returncode
                finally:
                    if process.poll() is None:
                        command("shell", "am", "force-stop", args.application_id, check=False)
                        process.kill()
                        process.wait(timeout=10)
            verify_test(base, "verifyIndexAfterHostKill", folder)
            evidence = fixture_json(fixture_id, "index-evidence.json")
            if (evidence.get("status") != "PASS" or evidence.get("stage") != stage or
                    evidence.get("killedPid") != int(pid) or evidence.get("verificationPid") == int(pid) or
                    evidence.get("active") != expected_active):
                raise RuntimeError(stage + ": missing or contradictory restart evidence")
            (folder / "device-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            current["status"] = "PASS"
            print(stage + ": PASS, exact checkpoint PID " + pid + " force-stopped", flush=True)
        if enospc_id:
            folder = output / "FILESYSTEM_ENOSPC"
            folder.mkdir()
            current = {"stage": "FILESYSTEM_ENOSPC", "fixtureId": enospc_id, "status": "STARTED"}
            summary["stages"].append(current)
            base = ["shell", "am", "instrument", "-w", "-r", "-e", "faultFixtureId", enospc_id,
                    "-e", "enospcFixturePath", args.enospc_fixture_path]
            verify_test(base, "boundedFilesystemEnospcPreservesCasAndMetadata", folder)
            evidence = fixture_json(enospc_id, "enospc-evidence.json")
            if (evidence.get("status") != "PASS" or evidence.get("filesystem") != "tmpfs" or
                    not 1024 * 1024 <= evidence.get("capacityBytes", 0) <= 8 * 1024 * 1024 or
                    evidence.get("casEnospcObserved") is not True or evidence.get("fullWriteObserved") is not True):
                raise RuntimeError("Missing actual bounded filesystem ENOSPC evidence")
            (folder / "device-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            current["status"] = "PASS"
            print("FILESYSTEM_ENOSPC: PASS, real bounded tmpfs/CAS ENOSPC and recovery", flush=True)
    except Exception as failure:
        if current is not None:
            current["status"] = "FAIL"
            current["error"] = str(failure)
        raise
    finally:
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Evidence: " + str(output), flush=True)


if __name__ == "__main__":
    main()
