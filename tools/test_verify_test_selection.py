# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Regression fixtures for absent, uncovered and misclassified runner targets."""
import pathlib
import tempfile
import unittest
from verify_test_selection import violations, result_summary


class TestSelectionTest(unittest.TestCase):
    def fixture(self, root):
        source = root / "app-android/src/androidTest/kotlin/ActualTest.kt"
        source.parent.mkdir(parents=True)
        source.write_text("package example\nclass ActualTest { @Test fun works() {} }", encoding="utf-8")
        workflow = root / ".github/workflows/ci.yml"
        workflow.parent.mkdir(parents=True)
        workflow.write_text("example.ActualTest", encoding="utf-8")
        return {"suites": {name: ["example.ActualTest"] for name in ("smoke", "pipeline", "convergence", "full")}, "manual_only": {}}

    def test_absent_class_cannot_pass_by_being_mentioned_in_yaml(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            manifest = self.fixture(root)
            manifest["suites"]["full"].append("example.InventedTest")
            self.assertIn("mapped class does not exist: example.InventedTest", violations(root, manifest))

    def test_new_class_requires_coverage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            manifest = self.fixture(root)
            (root / "app-android/src/androidTest/kotlin/NewTest.kt").write_text("package example\nclass NewTest { @Test fun works() {} }", encoding="utf-8")
            self.assertIn("unmapped test class: example.NewTest", violations(root, manifest))

    def test_manual_exemption_requires_reason_and_no_ci_selection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            manifest = self.fixture(root)
            manifest["manual_only"]["example.ActualTest"] = ""
            errors = violations(root, manifest)
            self.assertTrue(any("needs a reason" in error for error in errors))
            self.assertTrue(any("selected by CI" in error for error in errors))

    def test_assumption_skip_is_explicit_in_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "TEST-results.xml").write_text('<testsuite><testcase classname="example.Test" name="physical"><skipped message="physical USB absent"/></testcase></testsuite>')
            self.assertIn("skipped: 1", result_summary(root))
            self.assertIn("physical USB absent", result_summary(root))


if __name__ == "__main__":
    unittest.main()
