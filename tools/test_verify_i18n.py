# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import tempfile
from pathlib import Path
import unittest
from verify_i18n import check, literals, state

class I18nGateTest(unittest.TestCase):
    def test_comments_and_exception_copy_are_not_ui(self):
        self.assertEqual([], list(literals('// "中文"\n/* nested /* "文字" */ */\nthrow IllegalStateException("异常")\n')))

    def test_line_shift_preserves_literal_but_replacement_or_duplicate_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            file = root/'feature/a/src/main/kotlin/Ui.kt'
            file.parent.mkdir(parents=True)
            file.write_text('Text("旧文本")\n', encoding='utf-8')
            baseline = state(root)
            file.write_text('\n// comment\nText("旧文本")\n', encoding='utf-8')
            self.assertEqual([], check(root, baseline))
            file.write_text('Text("新文本")\n', encoding='utf-8')
            self.assertTrue(check(root, baseline))
            file.write_text('Text("旧文本")\nText("旧文本")\n', encoding='utf-8')
            self.assertTrue(check(root, baseline))

    def test_missing_translation_is_a_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            file = root/'feature/a/src/main/res/values/strings.xml'
            file.parent.mkdir(parents=True)
            file.write_text('<resources><string name="label">Label</string></resources>', encoding='utf-8')
            self.assertTrue(check(root, {}))
            zh = file.parent.parent/'values-zh-rCN/strings.xml'
            zh.parent.mkdir()
            zh.write_text('<resources><string name="label">标签</string></resources>', encoding='utf-8')
            self.assertEqual([], check(root, {}))

    def test_format_argument_reordering_is_valid_but_missing_or_wrong_type_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            en = root/'feature/a/src/main/res/values/strings.xml'
            zh = en.parent.parent/'values-zh-rCN/strings.xml'
            en.parent.mkdir(parents=True)
            zh.parent.mkdir()
            en.write_text('<resources><string name="label">%1$s / %2$d</string></resources>', encoding='utf-8')
            for text, valid in [('%2$d / %1$s', True), ('%1$s', False), ('%1$s / %2$s', False)]:
                zh.write_text(f'<resources><string name="label">{text}</string></resources>', encoding='utf-8')
                self.assertEqual(valid, not check(root, {}))

if __name__ == '__main__':
    unittest.main()
