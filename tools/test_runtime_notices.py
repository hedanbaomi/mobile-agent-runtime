#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Regression checks for the packaged notice inventory guards."""

import runpy
import unittest
from pathlib import Path


notices = runpy.run_path(str(Path(__file__).with_name("runtime-notices.py")))


class PackagedNoticeInventoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.components = [{"files": [{"path": "licenses/maven/example__library__1/LICENSE.txt"}]}]

    def test_indexed_license_directory_is_accepted_for_apk_and_aab(self) -> None:
        for prefix in ("assets/", "base/assets/"):
            with self.subTest(prefix=prefix):
                notices["assert_no_unindexed_license_dirs"](
                    [prefix + "licenses/maven/example__library__1/LICENSE.txt"],
                    self.components,
                    "fixture",
                )

    def test_stale_license_directory_is_rejected_for_apk_and_aab(self) -> None:
        for prefix in ("assets/", "base/assets/"):
            with self.subTest(prefix=prefix):
                with self.assertRaisesRegex(RuntimeError, "example__library__old"):
                    notices["assert_no_unindexed_license_dirs"](
                        [
                            prefix + "licenses/maven/example__library__1/LICENSE.txt",
                            prefix + "licenses/maven/example__library__old/LICENSE.txt",
                        ],
                        self.components,
                        "fixture",
                    )

    def test_notice_hash_matching_is_case_insensitive_and_rejects_stale_hash(self) -> None:
        digest = "ab" * 32
        components = [{"files": [{"sha256": digest}]}]
        check = notices["assert_notice_hashes_are_indexed"]
        check(digest.upper().encode(), components, "fixture")
        with self.assertRaisesRegex(RuntimeError, "cd" * 32):
            check(("CD" * 32).encode(), components, "fixture")


if __name__ == "__main__":
    unittest.main()
