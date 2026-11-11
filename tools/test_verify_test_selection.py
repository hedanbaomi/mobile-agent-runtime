# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Regression fixtures for absent, uncovered and misclassified runner targets."""
import pathlib
import tempfile
import unittest
from verify_test_selection import violations, result_summary, select_suite, invocation_groups, workflow_matrix_violations


class TestSelectionTest(unittest.TestCase):
    def test_risk_matrix_rejects_missing_triggers_wrong_variants_and_minimum_routing(self):
        import shutil
        source = pathlib.Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            workflows = root / '.github/workflows'
            workflows.mkdir(parents=True)
            for name in ('ci.yml', 'full-instrumentation.yml'):
                shutil.copyfile(source / '.github/workflows' / name, workflows / name)
            self.assertEqual([], workflow_matrix_violations(root))
            full = workflows / 'full-instrumentation.yml'
            original = full.read_text(encoding='utf-8')
            for old, new in [("'data/sqlite/src/main/kotlin/runtime/mobileagent/data/Root*'", "'unrelated/**'"),
                             ("'app-android/src/main/kotlin/runtime/mobileagent/ChatExecutionController.kt'", "'unrelated/Controller.kt'"),
                             ("&& 'Review'", "&& 'Debug'"), ('  schedule:', '  disabled_schedule:')]:
                full.write_text(original.replace(old, new), encoding='utf-8')
                self.assertTrue(workflow_matrix_violations(root))
            full.write_text(original, encoding='utf-8')
            ci = workflows / 'ci.yml'
            ci.write_text(ci.read_text(encoding='utf-8').replace('smoke-api Debug', 'smoke Debug'), encoding='utf-8')
            self.assertTrue(workflow_matrix_violations(root))

    def test_fresh_host_classes_have_independent_invocations_and_complete_coverage(self):
        manifest = {"suites": {"full": ["a", "b", "c", "d"]}, "fresh_host_first": ["a", "b"]}
        self.assertEqual([["a"], ["b"], ["c", "d"]], invocation_groups(manifest, "full"))

    def test_nightly_and_performance_partition_every_automated_class(self):
        manifest = {"suites": {"full": ["fresh", "normal", "large"]}, "performance": ["large"], "minimum_api": ["fresh"]}
        self.assertEqual(["fresh", "normal"], select_suite(manifest, "nightly"))
        self.assertEqual(["large"], select_suite(manifest, "performance"))
        self.assertEqual(["fresh"], select_suite(manifest, "minimum"))

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

    def test_fresh_host_fixture_must_precede_other_classes_without_omitting_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            manifest = self.fixture(root)
            (root / "app-android/src/androidTest/kotlin/DeferredTest.kt").write_text(
                "package example\nclass DeferredTest { @Test fun works() {} }", encoding="utf-8")
            manifest["fresh_host_first"] = ["example.DeferredTest"]
            manifest["suites"]["full"].append("example.DeferredTest")
            self.assertIn("full: fresh-host tests must run before host-initializing fixtures", violations(root, manifest))
            manifest["suites"]["full"].reverse()
            self.assertEqual([], violations(root, manifest))


if __name__ == "__main__":
    unittest.main()
