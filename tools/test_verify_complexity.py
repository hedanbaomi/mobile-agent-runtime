# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import pathlib
import tempfile
import unittest
from verify_complexity import decisions, violations


class ComplexityTest(unittest.TestCase):
    def test_comments_and_literal_text_do_not_count(self):
        self.assertEqual(1, decisions('/* if && */ // when\nval s = "for ||"\nif (ready) proceed()'))

    def test_existing_budget_and_new_file_failures(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / "shared/domain/src/main/kotlin/Rules.kt"
            source.parent.mkdir(parents=True)
            source.write_text("if (ready) proceed()\n" * 76, encoding="utf-8")
            self.assertEqual(1, len(violations(root, {})))
            self.assertEqual([], violations(root, {source.relative_to(root).as_posix(): 76}))
            source.write_text("if (ready) proceed()\n" * 77, encoding="utf-8")
            self.assertEqual(1, len(violations(root, {source.relative_to(root).as_posix(): 76})))


if __name__ == "__main__":
    unittest.main()
