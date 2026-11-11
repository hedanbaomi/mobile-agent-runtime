# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import pathlib
import subprocess
import tempfile
import unittest
from verify_workspace import check, check_local_git_baseline


class WorkspaceGuardTest(unittest.TestCase):
    def test_stale_explicit_local_baseline_is_rejected(self):
        self.identity(15, "1.1.5")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"], cwd=self.root, check=True)
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        self.assertEqual([], check_local_git_baseline(self.root, f'<!-- local-head: {sha} -->'))
        self.assertTrue(check_local_git_baseline(self.root, '<!-- local-head: 0000000 -->'))
        self.assertTrue(check_local_git_baseline(self.root, '<!-- local-head: not-a-sha -->'))
        self.assertTrue(check_local_git_baseline(self.root, 'stale baseline without markers', required=True))

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

    def test_hotfix_stable_tag_raises_floor(self):
        self.identity(19, "1.1.6.1")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "hotfix fixture"], cwd=self.root, check=True)
        subprocess.run(["git", "tag", "v1.1.6.1"], cwd=self.root, check=True)
        self.identity(18, "1.1.6")
        self.assertTrue(check(self.root))
        self.identity(19, "1.1.6.1")
        self.assertEqual([], check(self.root))

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


    def test_clean_public_clone_needs_no_private_handoff_but_checks_local_edits(self):
        self.identity(15, "1.1.5")
        (self.root / "HANDOFF.md").write_text("old\n" * 121, encoding="utf-8")
        doc = self.root / "docs/CURRENT.md"
        doc.write_text("[local archive](test-report.md)\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                        "commit", "-qm", "published tree"], cwd=self.root, check=True)
        self.assertEqual([], check(self.root))
        doc.write_text("[broken](missing-portable-file.md)\n", encoding="utf-8")
        self.assertTrue(any("missing link" in error for error in check(self.root)))
        (self.root / "docs/agents/handoff").mkdir()
        self.assertTrue(any("budget" in error for error in check(self.root)))

    def test_publication_checks_candidate_and_revision_without_mixing_trees(self):
        self.identity(15, "1.1.5")
        doc = self.root / "docs/CURRENT.md"
        doc.write_text("[broken](missing.md)\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                        "commit", "-qm", "published broken link"], cwd=self.root, check=True)
        doc.write_text("[fixed](PRESENT.md)\n", encoding="utf-8")
        target = self.root / "docs/PRESENT.md"
        target.write_text("portable\n", encoding="utf-8")
        self.assertTrue(check(self.root, publication=True), "untracked local targets must fail")
        subprocess.run(["git", "add", "docs/PRESENT.md"], cwd=self.root, check=True)
        self.assertEqual([], check(self.root, publication=True))
        self.assertTrue(any("missing.md" in error for error in check(self.root, publication=True, revision="HEAD")))
        target.unlink()
        self.assertTrue(check(self.root, publication=True), "tracked but deleted targets must fail")
        self.assertTrue(check(self.root, publication=True, revision="absent-revision"))

    def test_publication_rejects_nested_private_artifact_even_if_tracked(self):
        self.identity(15, "1.1.5")
        doc = self.root / "docs/agents/CURRENT.md"
        target = self.root / ".private/proof.md"
        target.parent.mkdir()
        target.write_text("private\n", encoding="utf-8")
        doc.write_text("[private](../../.private/proof.md)\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        self.assertTrue(any("local artifact" in error for error in check(self.root, publication=True)))

    def test_revision_checks_documents_deleted_from_worktree(self):
        self.identity(15, "1.1.5")
        doc = self.root / "docs/CURRENT.md"
        doc.write_text("[missing](missing.md)\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                        "commit", "-qm", "historical doc"], cwd=self.root, check=True)
        doc.unlink()
        self.assertEqual([], check(self.root, publication=True))
        self.assertTrue(any("missing.md" in error for error in check(self.root, publication=True, revision="HEAD")))


if __name__ == "__main__":
    unittest.main()
