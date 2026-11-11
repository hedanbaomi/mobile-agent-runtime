# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import tempfile
from pathlib import Path
import unittest
from verify_i18n import HOST_FILES, check, literals, state

CHAT_HOST_FILE = 'app-android/src/main/kotlin/runtime/mobileagent/ChatExecutionController.kt'

class I18nGateTest(unittest.TestCase):
    def test_ratcheted_host_fields_cover_the_migrated_chat_files(self):
        for name in ('ChatViewModel.kt', 'ChatExecutionController.kt', 'ChatRunFailure.kt',
                     'ChatRuntimeEvents.kt', 'ChatRunInterruption.kt', 'ChatDisplayNotice.kt',
                     'ChatMessageProjection.kt', 'ChatHistoryReader.kt'):
            self.assertIn(f'app-android/src/main/kotlin/runtime/mobileagent/{name}', HOST_FILES)
        self.assertIn(CHAT_HOST_FILE, HOST_FILES)
    def test_host_startup_copy_and_resources_are_checked(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'feature').mkdir()
            file = root/'app-android/src/main/kotlin/runtime/mobileagent/ui/HostInitializationScreen.kt'
            file.parent.mkdir(parents=True)
            file.write_text('Text("硬编码")', encoding='utf-8')
            self.assertTrue(check(root, {}))
            file.write_text('Text(stringResource(R.string.title))', encoding='utf-8')
            en = root/'app-android/src/main/res/values/strings.xml'
            en.parent.mkdir(parents=True)
            en.write_text('<resources><string name="title">Retry</string></resources>', encoding='utf-8')
            problems = check(root, {})
            self.assertTrue(any(item.startswith('app-android:') for item in problems), problems)
            zh = en.parent.parent/'values-zh-rCN/strings.xml'
            zh.parent.mkdir()
            zh.write_text('<resources><string name="title">重试</string></resources>', encoding='utf-8')
            self.assertEqual([], check(root, {}))

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

    def test_migrated_chat_host_files_reject_new_chinese_copy(self):
        # The six migrated Chat host files stay in HOST_FILES: a clean file is fine, but any new
        # hardcoded Chinese status literal must fail even when the resource keys still match.
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'feature').mkdir()
            en = root/'app-android/src/main/res/values/strings.xml'
            en.parent.mkdir(parents=True)
            en.write_text('<resources><string name="chat_preparing_conversation">Preparing…</string></resources>',
                          encoding='utf-8')
            zh = en.parent.parent/'values-zh-rCN/strings.xml'
            zh.parent.mkdir()
            zh.write_text('<resources><string name="chat_preparing_conversation">正在准备…</string></resources>',
                          encoding='utf-8')
            file = root/CHAT_HOST_FILE
            file.parent.mkdir(parents=True)
            file.write_text('status = application.hostChatText(R.string.chat_preparing_conversation)\n',
                            encoding='utf-8')
            self.assertEqual([], check(root, {}))
            file.write_text('status = "正在准备会话…"\n', encoding='utf-8')
            self.assertTrue(check(root, {}))

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
