# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Exercise the real PowerShell wrapper against synthetic native adb output."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "nt" and shutil.which("powershell.exe"), "Windows wrapper")
class DeviceInvocationTest(unittest.TestCase):
    def run_fixture(self, body, exit_code=0, stderr_lines=()):
        with tempfile.TemporaryDirectory(prefix="mar-device-runner-") as temp:
            root = Path(temp)
            escaped_err = [line.replace("^", "^^").replace("(", "^(").replace(")", "^)") for line in stderr_lines]
            escaped = [line.replace("^", "^^").replace("(", "^(").replace(")", "^)") for line in body]
            commands = (
                ["echo " + line + " 1>&2" for line in escaped_err]
                + ["echo " + line for line in escaped]
            )
            (root / "adb.cmd").write_text("@echo off\n" + "\n".join(commands)
                                         + f"\nexit /b {exit_code}\n", encoding="ascii")
            evidence = root / "evidence"
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", str(Path(__file__).with_name("run-device-tests.ps1")),
                "-Serial", "emulator-9998", "-Classes", "runtime.mobileagent.FixtureTest",
                "-Evidence", str(evidence)], env=environment, capture_output=True, text=True, timeout=30)
            self.assertTrue((evidence / "invocation.json").exists(), result.stdout + result.stderr)
            return result.returncode, json.loads((evidence / "invocation.json").read_text(encoding="utf-8-sig"))

    def test_metric_status_zero_is_not_a_completed_test(self):
        code, record = self.run_fixture([
            "INSTRUMENTATION_STATUS: test=one", "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: frameMetric=synthetic", "INSTRUMENTATION_STATUS_CODE: 0",
            "INSTRUMENTATION_STATUS: test=one", "INSTRUMENTATION_STATUS_CODE: 0", "OK (1 test)"])
        self.assertEqual(0, code)
        self.assertEqual(1, record["passedTests"])
        self.assertEqual(0, record["skippedTests"])

    def test_adb_stderr_banner_does_not_abort_evidence_recording(self):
        code, record = self.run_fixture(
            ["INSTRUMENTATION_STATUS: test=one", "INSTRUMENTATION_STATUS_CODE: 0", "OK (1 test)"],
            stderr_lines=["* daemon not running; starting now at tcp:5037 *", "* daemon started successfully *"],
        )
        self.assertEqual(0, code)
        self.assertTrue(record["passed"])
        self.assertEqual(1, record["passedTests"])

    def test_metrics_alone_cannot_claim_a_test_pass(self):
        code, record = self.run_fixture([
            "INSTRUMENTATION_STATUS: metric=synthetic", "INSTRUMENTATION_STATUS_CODE: 0", "OK (1 test)"])
        self.assertNotEqual(0, code)
        self.assertFalse(record["passed"])
        code, record = self.run_fixture([
            "INSTRUMENTATION_STATUS: test=   ", "INSTRUMENTATION_STATUS_CODE: 0", "OK (1 test)"])
        self.assertNotEqual(0, code)
        self.assertFalse(record["passed"])

    def test_named_skip_is_separate_and_failure_status_wins(self):
        code, record = self.run_fixture([
            "INSTRUMENTATION_STATUS: test=one", "INSTRUMENTATION_STATUS_CODE: 0",
            "INSTRUMENTATION_STATUS: test=ignored", "INSTRUMENTATION_STATUS_CODE: -3", "OK (2 tests)"])
        self.assertEqual(0, code)
        self.assertEqual((1, 1), (record["passedTests"], record["skippedTests"]))
        code, record = self.run_fixture([
            "INSTRUMENTATION_STATUS: test=one", "INSTRUMENTATION_STATUS_CODE: -2", "FAILURES!!!"])
        self.assertNotEqual(0, code)
        self.assertFalse(record["passed"])


if __name__ == "__main__":
    unittest.main()
