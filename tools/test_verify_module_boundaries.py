# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import pathlib
import tempfile
import unittest
from verify_module_boundaries import (
    FEATURE_MODULES,
    PROJECT_DEP_PATTERN,
    boundary_violations,
    strip_comments,
)


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

    def test_all_eight_feature_modules_checked_for_sqlite_and_cross_dependencies(self):
        self.assertEqual(len(FEATURE_MODULES), 8)
        self.assertEqual(
            FEATURE_MODULES,
            ("chat", "agents", "providers", "knowledge", "skills", "announcements", "settings", "worldbooks"),
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for name in FEATURE_MODULES:
                build = root / "feature" / name / "build.gradle.kts"
                build.parent.mkdir(parents=True)
                build.write_text('implementation(project(":data:sqlite"))')
            violations = boundary_violations(root)
            self.assertEqual(len(violations), 8)
            self.assertTrue(all("presentation module depends directly on SQLite" in v for v in violations))

    def test_cross_feature_dependency_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            build = root / "feature/chat/build.gradle.kts"
            build.parent.mkdir(parents=True)
            build.write_text('implementation(project(":feature:agents"))')
            violations = boundary_violations(root)
            self.assertTrue(any("illegal cross-feature dependency" in v for v in violations))

    def test_feature_disallowed_layer_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            build = root / "feature/chat/build.gradle.kts"
            build.parent.mkdir(parents=True)
            build.write_text('implementation(project(":runtime:embedding-onnx"))')
            violations = boundary_violations(root)
            self.assertTrue(any("presentation module depends on disallowed layer" in v for v in violations))

    def test_multiline_dependency_syntax(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            chat_build = root / "feature/chat/build.gradle.kts"
            chat_build.parent.mkdir(parents=True)
            chat_build.write_text(
                """
                dependencies {
                    implementation(
                        project(
                            ":data:sqlite"
                        )
                    )
                    implementation(
                        project(
                            ":feature:agents"
                        )
                    )
                    implementation(
                        project(
                            path = ":runtime:embedding-onnx"
                        )
                    )
                }
                """
            )
            shared_build = root / "shared/domain/build.gradle.kts"
            shared_build.parent.mkdir(parents=True)
            shared_build.write_text(
                """
                dependencies {
                    implementation(
                        project(
                            ":data:sqlite"
                        )
                    )
                }
                """
            )
            violations = boundary_violations(root)
            self.assertTrue(any("presentation module depends directly on SQLite" in v for v in violations))
            self.assertTrue(any("illegal cross-feature dependency :feature:agents" in v for v in violations))
            self.assertTrue(any("presentation module depends on disallowed layer :runtime:embedding-onnx" in v for v in violations))
            self.assertTrue(any("upward dependency :data:sqlite" in v for v in violations))

    def test_whitespace_and_named_parameter_syntax(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            chat_build = root / "feature/chat/build.gradle.kts"
            chat_build.parent.mkdir(parents=True)
            chat_build.write_text(
                """
                dependencies {
                    implementation(project( path = ":data:sqlite" ))
                    implementation(project( ":feature:agents" ))
                    implementation(project(path = ':runtime:embedding-onnx' ))
                }
                """
            )
            shared_build = root / "shared/domain/build.gradle.kts"
            shared_build.parent.mkdir(parents=True)
            shared_build.write_text(
                """
                dependencies {
                    implementation(project( path = ':feature:chat' ))
                }
                """
            )
            violations = boundary_violations(root)
            self.assertTrue(any("presentation module depends directly on SQLite" in v for v in violations))
            self.assertTrue(any("illegal cross-feature dependency :feature:agents" in v for v in violations))
            self.assertTrue(any("presentation module depends on disallowed layer :runtime:embedding-onnx" in v for v in violations))
            self.assertTrue(any("upward dependency :feature:chat" in v for v in violations))

    def test_commented_dependencies_are_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            chat_build = root / "feature/chat/build.gradle.kts"
            chat_build.parent.mkdir(parents=True)
            chat_build.write_text(
                """
                // Single-line commented-out dependencies:
                // implementation(project(":data:sqlite"))
                // implementation(project(":feature:agents"))
                // implementation(project(path = ":runtime:embedding-onnx"))

                /* Single-line block comments */
                /* implementation(project(":data:sqlite")) */
                /* implementation(project(":feature:agents")) */

                /*
                 * Multi-line block comments with TODOs:
                 * implementation(project(":data:sqlite"))
                 * implementation(
                 *     project(
                 *         path = ":feature:agents"
                 *     )
                 * )
                 */

                dependencies {
                    implementation(project(":shared:domain"))
                }
                """
            )
            shared_build = root / "shared/domain/build.gradle.kts"
            shared_build.parent.mkdir(parents=True)
            shared_build.write_text(
                """
                // implementation(project(":feature:agents"))
                /* implementation(project(":data:sqlite")) */
                // plugins { id("libs.plugins.android") }

                dependencies {
                    implementation(project(":shared:core"))
                }
                """
            )
            violations = boundary_violations(root)
            self.assertEqual([], violations)

    def test_strip_comments_helper(self):
        sample = (
            "alpha // line comment\n"
            "/* block comment */ beta\n"
            "/* multi-line\n"
            " * comment\n"
            " */ gamma // end of line\n"
        )
        cleaned = strip_comments(sample)
        self.assertNotIn("line comment", cleaned)
        self.assertNotIn("block comment", cleaned)
        self.assertNotIn("multi-line", cleaned)
        self.assertNotIn("end of line", cleaned)
        self.assertIn("alpha", cleaned)
        self.assertIn("beta", cleaned)
        self.assertIn("gamma", cleaned)

    def test_project_dep_pattern(self):
        cases = [
            ('project(":feature:chat")', [":feature:chat"]),
            ("project(':feature:chat')", [":feature:chat"]),
            ('project(path = ":data:sqlite")', [":data:sqlite"]),
            ("project(path=':data:sqlite')", [":data:sqlite"]),
            ('project( ":shared:domain" )', [":shared:domain"]),
            ('project(\n    path = ":shared:domain"\n)', [":shared:domain"]),
        ]
        for text, expected in cases:
            with self.subTest(text=text):
                self.assertEqual(PROJECT_DEP_PATTERN.findall(text), expected)


if __name__ == "__main__":
    unittest.main()
