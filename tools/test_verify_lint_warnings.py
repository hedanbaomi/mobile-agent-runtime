# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import json
import pathlib
import tempfile
import unittest
from verify_lint_warnings import findings


class LintWarningTest(unittest.TestCase):
    def test_line_moves_preserve_identity_but_new_warning_does_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            report = root / "lint.xml"
            def write(line, message="Existing"):
                report.write_text(f'<issues><issue id="Api" severity="Warning" message="{message}"><location file="src/Api.kt" line="{line}"/></issue></issues>')
            write(2)
            first, _, _ = findings(report, root)
            write(30)
            moved, _, _ = findings(report, root)
            self.assertEqual(first, moved)
            write(30, "New")
            self.assertNotEqual(first, findings(report, root)[0])

    def test_errors_and_fatal_findings_cannot_be_warning_exemptions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            report = root / "lint.xml"
            report.write_text('<issues><issue id="Unsafe" severity="Error" message="Failure"/><issue id="Fatal" severity="Fatal" message="Fatal"/></issues>')
            warnings, errors, _ = findings(report, root)
            self.assertEqual(set(), warnings)
            self.assertEqual(2, len(errors))

    def test_foreign_checkout_absolute_path_matches_the_local_baseline_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "app-android" / "src" / "main" / "AndroidManifest.xml"
            source.parent.mkdir(parents=True)
            source.write_text("<manifest/>")
            report = root / "lint.xml"
            # A shared build cache served another checkout's lint model, so the
            # report carries that checkout's absolute path for the same source.
            foreign = pathlib.Path("E:/mobileAgentRuntime-scratch/python-budget-20261011/source") / "app-android/src/main/AndroidManifest.xml"
            report.write_text(f'<issues><issue id="DataExtractionRules" severity="Warning" message="M"><location file="{foreign}"/></issue></issues>')
            warnings, _, _ = findings(report, root)
            self.assertEqual(
                {json.dumps(["DataExtractionRules", ["app-android/src/main/AndroidManifest.xml"], "M"], ensure_ascii=False)},
                warnings,
            )

    def test_normalized_foreign_path_still_rejects_a_genuinely_new_warning(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "app-android" / "src" / "main" / "AndroidManifest.xml"
            source.parent.mkdir(parents=True)
            source.write_text("<manifest/>")
            report = root / "lint.xml"
            foreign = pathlib.Path("E:/mobileAgentRuntime-scratch/python-budget-20261011/source") / "app-android/src/main/AndroidManifest.xml"
            def write(message):
                report.write_text(f'<issues><issue id="DataExtractionRules" severity="Warning" message="{message}"><location file="{foreign}"/></issue></issues>')
            write("Reviewed")
            reviewed, _, _ = findings(report, root)
            write("Brand new")
            changed, _, _ = findings(report, root)
            self.assertEqual(set(), reviewed & changed)

    def test_foreign_absolute_directory_location_is_normalized_too(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            directory = root / "app-android" / "src" / "main" / "res" / "mipmap-anydpi-v26"
            directory.mkdir(parents=True)
            report = root / "lint.xml"
            # The reviewed inventory holds directory locations as well as files.
            foreign = pathlib.Path("E:/mobileAgentRuntime-scratch/python-budget-20261011/source") / "app-android/src/main/res/mipmap-anydpi-v26"
            report.write_text(f'<issues><issue id="IconMissingDensityFolder" severity="Warning" message="M"><location file="{foreign}"/></issue></issues>')
            warnings, _, unverifiable = findings(report, root)
            self.assertEqual([], unverifiable)
            self.assertEqual(
                {json.dumps(["IconMissingDensityFolder", ["app-android/src/main/res/mipmap-anydpi-v26"], "M"], ensure_ascii=False)},
                warnings,
            )

    def test_unconfirmable_absolute_location_is_rejected_instead_of_stripped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            report = root / "lint.xml"
            foreign = "E:/unknown-checkout/source/app-android/src/main/AndroidManifest.xml"
            report.write_text(f'<issues><issue id="DataExtractionRules" severity="Warning" message="M"><location file="{foreign}"/></issue></issues>')
            warnings, errors, unverifiable = findings(report, root)
            self.assertEqual([foreign], unverifiable)
            self.assertEqual(set(), warnings)
            self.assertEqual([], errors)

    def test_traversal_segment_is_never_used_as_a_trusted_suffix(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "app-android" / "src" / "main" / "AndroidManifest.xml"
            source.parent.mkdir(parents=True)
            source.write_text("<manifest/>")
            report = root / "lint.xml"
            foreign = "E:/unknown-checkout/../../app-android/src/main/AndroidManifest.xml"
            report.write_text(f'<issues><issue id="DataExtractionRules" severity="Warning" message="M"><location file="{foreign}"/></issue></issues>')
            warnings, _, unverifiable = findings(report, root)
            self.assertEqual([foreign], unverifiable)
            self.assertEqual(set(), warnings)

    def test_online_version_notices_do_not_hide_code_or_build_compatibility_findings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            report = root / "lint.xml"
            report.write_text('''<issues>
                <issue id="AndroidGradlePluginVersion" severity="Warning" message="New plugin available"/>
                <issue id="GradleDependency" severity="Warning" message="New dependency available"/>
                <issue id="GradleCompatible" severity="Warning" message="Incompatible dependency"/>
                <issue id="NewApi" severity="Warning" message="Unsupported API"/>
                <issue id="GradleDependency" severity="Error" message="Still an error"/>
                <issue id="AndroidGradlePluginVersion" severity="Fatal" message="Still fatal"/>
            </issues>''')
            warnings, errors, _ = findings(report, root)
            self.assertEqual(2, len(warnings))
            self.assertTrue(any('GradleCompatible' in item for item in warnings))
            self.assertTrue(any('NewApi' in item for item in warnings))
            self.assertEqual(2, len(errors))


if __name__ == "__main__":
    unittest.main()
