# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import pathlib
import subprocess
import tempfile
import unittest
from verify_workspace import check


class WorkspaceGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        (self.root / "app-android").mkdir()
        (self.root / "docs/agents").mkdir(parents=True)
        (self.root / "HANDOFF.md").write_text("## 当前任务\n", encoding="utf-8")
        self.build = self.root / "app-android/build.gradle.kts"

    def identity(self, code, name):
        self.build.write_text(f'versionCode = {code}\nversionName = "{name}"\n', encoding="utf-8")

    def test_public_release_rollback_and_collision_are_rejected(self):
        for code, name in [(10, "1.1.4.1preview"), (15, "1.1.5.1preview")]:
            self.identity(code, name)
            self.assertTrue(check(self.root))
        self.identity(15, "1.1.5")
        self.assertEqual([], check(self.root))
        self.identity(16, "1.1.6.1preview")
        self.assertEqual([], check(self.root))

    def test_future_stable_tag_raises_floor(self):
        self.identity(17, "1.1.6")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"], cwd=self.root, check=True)
        subprocess.run(["git", "tag", "v1.1.6"], cwd=self.root, check=True)
        self.identity(16, "1.1.6.1preview")
        self.assertTrue(check(self.root))

    def test_missing_reference_and_bloated_handoff_fail(self):
        self.identity(15, "1.1.5")
        (self.root / "docs/CURRENT.md").write_text("[missing](absent.md)\n", encoding="utf-8")
        subprocess.run(["git", "add", "docs/CURRENT.md"], cwd=self.root, check=True)
        self.assertTrue(check(self.root))
        (self.root / "docs/absent.md").write_text("present", encoding="utf-8")
        self.assertEqual([], check(self.root))
        self.assertTrue(check(self.root, publication=True), "local file existence must not prove published availability")
        subprocess.run(["git", "add", "docs/absent.md"], cwd=self.root, check=True)
        (self.root / "HANDOFF.md").write_text("## 当前任务\n" + "old\n" * 121, encoding="utf-8")
        self.assertTrue(check(self.root))
        self.assertEqual([], check(self.root, publication=True))


if __name__ == "__main__":
    unittest.main()
