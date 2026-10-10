# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
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
            first, _ = findings(report, root)
            write(30)
            moved, _ = findings(report, root)
            self.assertEqual(first, moved)
            write(30, "New")
            self.assertNotEqual(first, findings(report, root)[0])

    def test_errors_and_fatal_findings_cannot_be_warning_exemptions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            report = root / "lint.xml"
            report.write_text('<issues><issue id="Unsafe" severity="Error" message="Failure"/><issue id="Fatal" severity="Fatal" message="Fatal"/></issues>')
            warnings, errors = findings(report, root)
            self.assertEqual(set(), warnings)
            self.assertEqual(2, len(errors))


if __name__ == "__main__":
    unittest.main()
