# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Opt-in host kill matrix for an already installed synthetic Android emulator.

Does not build/install, clear app data, touch production keys, fill a volume, or
connect to a Provider. Keeps per-stage output and device synthetic DB/CAS files.
Run the separate boundedSqliteFull... and AndroidSecretStoreDeviceTest through
the normal instrumentation runner; those are not process-kill tests.
"""

import argparse
import json
import pathlib
import re
import subprocess
import time
import uuid


STAGES = (
    "MIGRATION_BEFORE", "MIGRATION_DURING", "MIGRATION_AFTER",
    "RESTORE_BEFORE", "RESTORE_DURING", "RESTORE_AFTER", "IN_FLIGHT_RECOVERY",
)
TEST_CLASS = "runtime.mobileagent.RecoveryFaultMatrixDeviceTest"
BOUNDARIES = {
    "MIGRATION_BEFORE": ("before_migration", 0),
    "MIGRATION_DURING": ("receipts_created_uncommitted", 1),
    "MIGRATION_AFTER": ("migration_committed", 1),
    "RESTORE_BEFORE": ("before_restore", 0),
    "RESTORE_DURING": ("two_messages_uncommitted", 2),
    "RESTORE_AFTER": ("restore_committed", 2),
    "IN_FLIGHT_RECOVERY": ("in_flight_committed", 1),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--application-id", required=True)
    parser.add_argument("--runner", required=True, help="Installed test-package/runner component")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--stage", choices=STAGES, action="append")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("This destructive synthetic test supports an explicit emulator serial only")
    if not re.fullmatch(r"runtime\.mobileagent(?:\.[a-zA-Z0-9_]+)*", args.application_id):
        parser.error("Unexpected application id")
    if not re.fullmatch(r"[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+", args.runner):
        parser.error("Invalid instrumentation component")
    adb = [args.adb, "-s", args.serial]

    def command(*parts, timeout=30, check=True):
        return subprocess.run(adb + list(parts), stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout, check=check)

    if command("get-state").stdout.strip() != "device":
        raise RuntimeError("Requested emulator is not ready")
    instrumentation = command("shell", "pm", "list", "instrumentation").stdout
    expected = f"instrumentation:{args.runner} (target={args.application_id})"
    if expected not in instrumentation.splitlines():
        raise RuntimeError("Installed instrumentation does not target the specified app")
    output = args.output.resolve() / ("fault-matrix-" + str(uuid.uuid4()))
    output.mkdir(parents=True, exist_ok=False)
    summary = {"serial": args.serial, "applicationId": args.application_id,
               "runner": args.runner, "stages": []}
    try:
        for stage in args.stage or STAGES:
            fixture_id = str(uuid.uuid4())
            folder = output / stage
            folder.mkdir()
            record = {"stage": stage, "fixtureId": fixture_id, "status": "STARTED"}
            summary["stages"].append(record)
            base = ["shell", "am", "instrument", "-w", "-r", "-e", "faultStage", stage,
                    "-e", "faultFixtureId", fixture_id]
            prepare = adb + base + ["-e", "class", TEST_CLASS + "#prepareAndWaitForHostKill", args.runner]
            with (folder / "prepare.txt").open("wb") as stream:
                process = subprocess.Popen(prepare, stdout=stream, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 90
                    pid = None
                    pattern = re.compile(r"MAR_FAULT_CHECKPOINT stage=" + stage +
                                         r" boundary=(\w+) schemaVersion=(\d+) transactionDepth=(\d+) readyRows=(\d+)"
                                         r" pid=(\d+) fixture=fault-recovery-" + fixture_id)
                    while time.monotonic() < deadline:
                        text = (folder / "prepare.txt").read_text(encoding="utf-8", errors="replace")
                        match = pattern.search(text)
                        if match:
                            boundary, version, depth, rows, pid = match.groups()
                            expected_boundary, expected_rows = BOUNDARIES[stage]
                            during = stage.endswith("_DURING")
                            if (boundary != expected_boundary or int(rows) != expected_rows or
                                    during != (int(depth) > 0) or
                                    (stage in ("MIGRATION_BEFORE", "MIGRATION_DURING") and int(version) != 39) or
                                    (stage not in ("MIGRATION_BEFORE", "MIGRATION_DURING") and int(version) < 40)):
                                raise RuntimeError(f"{stage}: checkpoint metadata does not prove the requested boundary")
                            record["checkpoint"] = {"boundary": boundary, "schemaVersion": int(version),
                                                    "transactionDepth": int(depth), "readyRows": int(rows)}
                            break
                        if process.poll() is not None:
                            raise RuntimeError(f"{stage}: preparation ended before checkpoint; see {folder}")
                        time.sleep(0.1)
                    if pid is None:
                        raise RuntimeError(f"{stage}: no checkpoint within 90 seconds")
                    record["killedPid"] = int(pid)
                    # Confirm this exact checkpoint PID belongs to the intended app before force-stop.
                    before = command("shell", "pidof", args.application_id).stdout.strip().split()
                    if pid not in before:
                        raise RuntimeError(f"{stage}: checkpoint PID is not owned by target app")
                    (folder / "force-stop.txt").write_text(
                        command("shell", "am", "force-stop", args.application_id).stdout, encoding="utf-8")
                    old_pid_path = command("shell", "test", "-d", "/proc/" + pid, check=False)
                    if old_pid_path.returncode == 0:
                        raise RuntimeError(f"{stage}: old PID still exists after force-stop")
                    record["oldPidGone"] = True
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()  # Only the host adb client started above.
                        process.wait(timeout=10)
                    record["expectedKilledInstrumentationExit"] = process.returncode
                finally:
                    if process.poll() is None:
                        command("shell", "am", "force-stop", args.application_id, check=False)
                        process.kill()
                        process.wait(timeout=10)
            verification = command(*(base + ["-e", "class", TEST_CLASS + "#verifyAfterHostKill", args.runner]),
                                   timeout=120, check=False)
            (folder / "verify.txt").write_text(verification.stdout, encoding="utf-8")
            if (verification.returncode != 0 or "OK (1 test)" not in verification.stdout or
                    "FAILURES!!!" in verification.stdout or "INSTRUMENTATION_FAILED" in verification.stdout):
                raise RuntimeError(f"{stage}: recovery verification failed; see {folder}")
            record["status"] = "PASS"
            print(f"{stage}: PASS, actual process {pid} killed; fixture {fixture_id}", flush=True)
    finally:
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Evidence: {output}", flush=True)


if __name__ == "__main__":
    main()
