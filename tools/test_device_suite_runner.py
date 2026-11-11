# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Execute the real Bash coordinator against deterministic build/ADB stubs."""
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest


class DeviceRunnerTest(unittest.TestCase):
    def test_failed_second_invocation_cannot_reuse_first_groups_xml(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / 'tools').mkdir()
            (root / 'bin').mkdir()
            script = pathlib.Path(__file__).with_name('run-device-suite')
            shutil.copyfile(script, root / 'tools/run-device-suite')
            scripts = {
                'bin/python3': '#!/usr/bin/env bash\nprintf "fresh.Class\\nnormal.Class\\n"\n',
                'bin/adb': '#!/usr/bin/env bash\nexit 0\n',
                'gradlew': '''#!/usr/bin/env bash
if [[ -f first-done ]]; then exit 23; fi
touch first-done
mkdir -p app-android/build/outputs/androidTest-results/connected
printf '<testsuite><testcase classname="fresh.Class" name="ok"/></testsuite>' > app-android/build/outputs/androidTest-results/connected/TEST-fresh.xml
''',
            }
            for name, content in scripts.items():
                path = root / name
                path.write_text(content, encoding='utf-8', newline='\n')
                path.chmod(0o755)
            result = subprocess.run(['bash', '-c', 'export PATH="$PWD/bin:$PATH"; bash tools/run-device-suite full Review'],
                                    cwd=root, capture_output=True, text=True, timeout=30)
            self.assertEqual(23, result.returncode, result.stdout + result.stderr)
            evidence = next((root / 'app-android/build/reports/device-evidence').iterdir())
            first = evidence / 'full-Review-1'
            second = evidence / 'full-Review-2'
            self.assertEqual(1, len(list(first.rglob('TEST-*.xml'))))
            self.assertEqual([], list(second.rglob('TEST-*.xml')))
            self.assertEqual({'classes': 'normal.Class', 'variant': 'Review', 'exitCode': 23, 'producedResults': False},
                             json.loads((second / 'invocation.json').read_text()))
            rerun = subprocess.run(['bash', '-c', 'export PATH="$PWD/bin:$PATH"; bash tools/run-device-suite full Review'],
                                   cwd=root, capture_output=True, text=True, timeout=30)
            self.assertEqual(23, rerun.returncode)
            current = root / 'app-android/build/reports/device-evidence'
            self.assertEqual([], list(current.rglob('TEST-*.xml')))
            previous = list((root / 'app-android/build/reports').glob('previous-device-evidence.*'))
            self.assertEqual(1, len(previous))
            self.assertEqual(1, len(list(previous[0].rglob('TEST-*.xml'))))


if __name__ == '__main__':
    unittest.main()
