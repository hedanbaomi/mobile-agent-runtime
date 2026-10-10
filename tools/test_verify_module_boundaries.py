# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import pathlib
import tempfile
import unittest
from verify_module_boundaries import boundary_violations


class ModuleBoundaryTest(unittest.TestCase):
    def test_android_import_and_upward_dependency_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            module = root / "shared/domain"
            module.mkdir(parents=True)
            (module / "build.gradle.kts").write_text('implementation(project(":feature:agents"))')
            source = module / "src/main/kotlin/Bad.kt"
            source.parent.mkdir(parents=True)
            source.write_text("import android.content.Context\nclass Bad")
            violations = boundary_violations(root)
            self.assertTrue(any("upward dependency" in value for value in violations))
            self.assertTrue(any("Android import" in value for value in violations))

    def test_shared_dependency_is_allowed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            module = root / "data/sqlite"
            module.mkdir(parents=True)
            (module / "build.gradle.kts").write_text('implementation(project(":shared:domain"))')
            self.assertEqual([], boundary_violations(root))


if __name__ == "__main__":
    unittest.main()
