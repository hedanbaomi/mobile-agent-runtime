# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Execute Windows entry points with real native stderr/version/exit fixtures."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "nt", "Windows PowerShell entry points")
class DeveloperEntryPointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="mar-native-probes-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = pathlib.Path(cls.temp.name)
        cls.tools = cls.root / "tools"
        cls.tools.mkdir()
        source_tools = pathlib.Path(__file__).resolve().parent
        for name in ("dev-check.ps1", "dev-environment.ps1"):
            shutil.copyfile(source_tools / name, cls.tools / name)
        cls.bin = cls.root / "jdk/bin"
        cls.bin.mkdir(parents=True)
        source = cls.root / "Probe.cs"
        source.write_text('''using System;
using System.Diagnostics;
using System.IO;
class Probe {
    static int Main() {
        string name = Path.GetFileNameWithoutExtension(Process.GetCurrentProcess().MainModule.FileName);
        bool wrong = Environment.GetEnvironmentVariable("STUB_WRONG") == name;
        if (name == "py") Console.WriteLine(Environment.GetEnvironmentVariable("STUB_PYTHON_PATH"));
        else Console.Error.WriteLine(name == "java" ? "openjdk version \\\"" + (wrong ? "21.0.2" : "17.0.19") + "\\\"" : name == "python" ? "Python 3.11.9" : (wrong ? "v20.1.0" : "v24.1.0"));
        return Environment.GetEnvironmentVariable("STUB_FAIL") == name ? 7 : 0;
    }
}''', encoding="utf-8")
        compiler = pathlib.Path(os.environ["WINDIR"]) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
        subprocess.run([str(compiler), "/nologo", "/target:exe", "/out:" + str(cls.bin / "java.exe"), str(source)], check=True, capture_output=True)
        for name in ("py", "python", "node"):
            shutil.copyfile(cls.bin / "java.exe", cls.bin / (name + ".exe"))
        (cls.root / "gradlew.bat").write_text("@echo off\necho GRADLE_STUB\nexit /b %STUB_GRADLE_EXIT%\n", encoding="ascii")
        cls.shells = [path for name in ("powershell.exe", "pwsh.exe") if (path := shutil.which(name))]
        if not cls.shells:
            raise unittest.SkipTest("No PowerShell installed")

    def run_script(self, shell, script, extra=None, **overrides):
        env = os.environ.copy()
        env.update(PATH=str(self.bin) + os.pathsep + env["PATH"], STUB_PYTHON_PATH=str(self.bin / "python.exe"),
                   STUB_GRADLE_EXIT="0", STUB_FAIL="", STUB_WRONG="")
        env.update(overrides)
        return subprocess.run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(self.tools / script), *(extra or [])], env=env, capture_output=True, text=True)

    def test_successful_stderr_versions_reach_gradle_and_strict_environment(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                result = self.run_script(shell, "dev-check.ps1", ["-Java17Home", str(self.bin.parent)])
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("GRADLE_STUB", result.stdout)
                result = self.run_script(shell, "dev-environment.ps1", ["-Strict"])
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(3, result.stdout.count("matches CI: True"))

    def test_nonzero_native_probes_and_wrong_versions_fail_before_gradle(self):
        for shell in self.shells:
            for values in ({"STUB_FAIL": "java"}, {"STUB_FAIL": "py"}, {"STUB_FAIL": "node"}, {"STUB_WRONG": "java"}, {"STUB_WRONG": "node"}):
                with self.subTest(shell=shell, values=values):
                    result = self.run_script(shell, "dev-check.ps1", ["-Java17Home", str(self.bin.parent)], **values)
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotIn("GRADLE_STUB", result.stdout)
            result = self.run_script(shell, "dev-environment.ps1", ["-Strict"], STUB_FAIL="java")
            self.assertNotEqual(0, result.returncode)

    def test_gradle_exit_code_is_preserved(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                result = self.run_script(shell, "dev-check.ps1", ["-Java17Home", str(self.bin.parent)], STUB_GRADLE_EXIT="9")
                self.assertEqual(9, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
