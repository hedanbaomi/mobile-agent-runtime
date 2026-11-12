#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Permanent regression tests for tools/run-device-suite prebuilt APK mode."""

from __future__ import annotations

import ast
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().with_name("run-device-suite")


def _extract_embedded_block(begin_marker: str, end_marker: str) -> str:
    text = SCRIPT_PATH.read_text(encoding="utf-8")
    start = text.index(begin_marker) + len(begin_marker)
    end = text.index(end_marker, start)
    return text[start:end].strip() + "\n"


VALIDATOR_CODE = _extract_embedded_block(
    "# MAR_APK_CONTRACT_VALIDATOR_BEGIN",
    "# MAR_APK_CONTRACT_VALIDATOR_END",
)
PARSER_CODE = _extract_embedded_block(
    "# MAR_INSTRUMENTATION_PARSER_BEGIN",
    "# MAR_INSTRUMENTATION_PARSER_END",
)


def _encode_utf8_len(val: int) -> bytes:
    if val < 0x80:
        return bytes([val])
    return bytes([0x80 | ((val >> 8) & 0x7F), val & 0xFF])


def build_binary_axml(
    package_name: str,
    *,
    debuggable: bool | None = True,
    instrumentation: tuple[str, str] | None = None,
    utf8_pool: bool = False,
) -> bytes:
    """Construct a valid Android binary XML (ResXMLTree 0x0003) manifest."""
    android_ns = "http://schemas.android.com/apk/res/android"
    # Keep resource-mapped attribute names at indices 0..2 so 0x0180 maps them cleanly.
    pool_strings = [
        "name",           # 0 -> 0x01010003
        "debuggable",     # 1 -> 0x0101000f
        "targetPackage",  # 2 -> 0x01010021
        "package",        # 3 (no resource ID)
        android_ns,       # 4
        "manifest",       # 5
        "application",    # 6
        "instrumentation",  # 7
        package_name,     # 8
    ]
    res_map_ids = [0x01010003, 0x0101000F, 0x01010021]

    def str_idx(value: str) -> int:
        if value not in pool_strings:
            pool_strings.append(value)
        return pool_strings.index(value)

    elements: list[tuple[int, list[tuple[int, int, int, int, int]]]] = []
    # <manifest package="...">
    pkg_val_idx = str_idx(package_name)
    elements.append((
        str_idx("manifest"),
        [(0xFFFFFFFF, str_idx("package"), pkg_val_idx, 0x03, pkg_val_idx)],
    ))
    # <instrumentation android:name="..." android:targetPackage="..."/>
    if instrumentation is not None:
        runner_cls, target_pkg = instrumentation
        r_idx = str_idx(runner_cls)
        t_idx = str_idx(target_pkg)
        ns_i = str_idx(android_ns)
        elements.append((
            str_idx("instrumentation"),
            [
                (ns_i, 0, r_idx, 0x03, r_idx),
                (ns_i, 2, t_idx, 0x03, t_idx),
            ],
        ))
    # <application [android:debuggable="true|false"]/>
    app_attrs: list[tuple[int, int, int, int, int]] = []
    if debuggable is not None:
        app_attrs.append((
            str_idx(android_ns),
            1,
            0xFFFFFFFF,
            0x12,
            0xFFFFFFFF if debuggable else 0x00000000,
        ))
    elements.append((str_idx("application"), app_attrs))

    # Build 0x0001 ResStringPool
    encoded_blobs: list[bytes] = []
    offsets: list[int] = []
    cursor = 0
    for s in pool_strings:
        offsets.append(cursor)
        if utf8_pool:
            u16_units = len(s.encode("utf-16-le")) // 2
            u8_bytes = s.encode("utf-8")
            blob = _encode_utf8_len(u16_units) + _encode_utf8_len(len(u8_bytes)) + u8_bytes + b"\x00"
        else:
            u16_bytes = s.encode("utf-16-le")
            u16_units = len(u16_bytes) // 2
            blob = struct.pack("<H", u16_units) + u16_bytes + b"\x00\x00"
        encoded_blobs.append(blob)
        cursor += len(blob)
    raw_pool = b"".join(encoded_blobs)
    while len(raw_pool) % 4 != 0:
        raw_pool += b"\x00"

    sp_header_size = 28
    strings_start = sp_header_size + len(pool_strings) * 4
    sp_total_size = strings_start + len(raw_pool)
    sp_flags = 0x0100 if utf8_pool else 0x0000
    sp_chunk = (
        struct.pack(
            "<HHIIIIII",
            0x0001,
            sp_header_size,
            sp_total_size,
            len(pool_strings),
            0,
            sp_flags,
            strings_start,
            0,
        )
        + struct.pack(f"<{len(pool_strings)}I", *offsets)
        + raw_pool
    )

    # Build 0x0180 ResXMLTree_attrExt resource ID map
    rm_header_size = 8
    rm_total_size = rm_header_size + len(res_map_ids) * 4
    rm_chunk = struct.pack("<HHI", 0x0180, rm_header_size, rm_total_size) + struct.pack(
        f"<{len(res_map_ids)}I", *res_map_ids
    )

    # Build 0x0102 start element chunks
    elem_chunks: list[bytes] = []
    for name_i, attrs in elements:
        el_header_size = 16
        attr_size = 20
        el_total_size = el_header_size + 20 + len(attrs) * attr_size
        head = struct.pack("<HHIII", 0x0102, el_header_size, el_total_size, 1, 0xFFFFFFFF)
        ext = struct.pack(
            "<IIHHHHHH",
            0xFFFFFFFF,
            name_i,
            20,
            attr_size,
            len(attrs),
            0,
            0,
            0,
        )
        attr_bytes = b"".join(
            struct.pack("<IIIHBBI", a_ns, a_name, a_raw, 8, 0, a_dtype, a_dval)
            for a_ns, a_name, a_raw, a_dtype, a_dval in attrs
        )
        elem_chunks.append(head + ext + attr_bytes)

    body = sp_chunk + rm_chunk + b"".join(elem_chunks)
    top_header = struct.pack("<HHI", 0x0003, 8, 8 + len(body))
    return top_header + body


def write_apk(path: Path, manifest_bytes: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("AndroidManifest.xml", manifest_bytes)
        zf.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 32)


class InstrumentationParserRegressionTest(unittest.TestCase):
    def _run_parser(
        self,
        raw_stream: str,
        *,
        adb_exit: int = 0,
        suite_name: str = "smoke-Debug-1",
    ) -> tuple[subprocess.CompletedProcess[str], ET.Element]:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            inst_file = tmp_dir / "instrumentation.txt"
            xml_file = tmp_dir / "TEST-smoke-Debug-1.xml"
            inst_file.write_text(raw_stream, encoding="utf-8", newline="\n")
            proc = subprocess.run(
                [sys.executable, "-B", "-c", PARSER_CODE, str(inst_file), str(adb_exit), str(xml_file), suite_name],
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertTrue(xml_file.is_file(), f"JUnit XML must always be written (stderr={proc.stderr!r})")
            root = ET.parse(xml_file).getroot()
            if proc.returncode != 0:
                failures_plus_errors = int(root.attrib["failures"]) + int(root.attrib["errors"])
                self.assertGreaterEqual(
                    failures_plus_errors,
                    1,
                    f"Non-zero exit ({proc.returncode}) MUST record >=1 failure/error in JUnit XML, got {ET.tostring(root, encoding='unicode')}",
                )
            return proc, root

    def test_complete_sample_passes_and_writes_clean_junit_xml(self) -> None:
        stream = "\n".join([
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: test=appLaunches",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: test=appLaunches",
            "INSTRUMENTATION_STATUS_CODE: 0",
            "INSTRUMENTATION_RESULT: stream=",
            "",
            "Time: 0.42",
            "",
            "OK (1 test)",
            "",
            "",
            "INSTRUMENTATION_CODE: -1",
        ])
        proc, xml_root = self._run_parser(stream)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("1", xml_root.attrib["tests"])
        self.assertEqual("0", xml_root.attrib["failures"])
        self.assertEqual("0", xml_root.attrib["errors"])
        self.assertEqual("0", xml_root.attrib["skipped"])

    def test_missing_terminal_code_fails_and_injects_junit_error(self) -> None:
        stream = "\n".join([
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: test=appLaunches",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: test=appLaunches",
            "INSTRUMENTATION_STATUS_CODE: 0",
            "INSTRUMENTATION_RESULT: stream=",
            "Time: 0.42",
            "OK (1 test)",
        ])
        proc, xml_root = self._run_parser(stream)
        self.assertNotEqual(0, proc.returncode)
        self.assertIn("INSTRUMENTATION_CODE", proc.stderr)
        self.assertGreaterEqual(int(xml_root.attrib["errors"]), 1)

    def test_count_mismatch_samples_fail_and_inject_junit_error(self) -> None:
        for numtests_val, ok_val in ((9, 9), (9, 1), (1, 9)):
            with self.subTest(numtests=numtests_val, ok=ok_val):
                ok_noun = "test" if ok_val == 1 else "tests"
                stream = "\n".join([
                    "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
                    "INSTRUMENTATION_STATUS: current=1",
                    "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
                    f"INSTRUMENTATION_STATUS: numtests={numtests_val}",
                    "INSTRUMENTATION_STATUS: test=onlyOneCollected",
                    "INSTRUMENTATION_STATUS_CODE: 1",
                    "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
                    "INSTRUMENTATION_STATUS: current=1",
                    "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
                    f"INSTRUMENTATION_STATUS: numtests={numtests_val}",
                    "INSTRUMENTATION_STATUS: test=onlyOneCollected",
                    "INSTRUMENTATION_STATUS_CODE: 0",
                    "INSTRUMENTATION_RESULT: stream=",
                    f"OK ({ok_val} {ok_noun})",
                    "INSTRUMENTATION_CODE: -1",
                ])
                proc, xml_root = self._run_parser(stream)
                self.assertNotEqual(0, proc.returncode)
                self.assertGreaterEqual(int(xml_root.attrib["errors"]), 1)

    def test_runner_abort_after_passing_test_fails_and_injects_junit_error(self) -> None:
        stream = "\n".join([
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
            "INSTRUMENTATION_STATUS: numtests=2",
            "INSTRUMENTATION_STATUS: test=firstTestPasses",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.SmokeTest",
            "INSTRUMENTATION_STATUS: numtests=2",
            "INSTRUMENTATION_STATUS: test=firstTestPasses",
            "INSTRUMENTATION_STATUS_CODE: 0",
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
            "INSTRUMENTATION_CODE: 0",
        ])
        proc, xml_root = self._run_parser(stream)
        self.assertNotEqual(0, proc.returncode)
        self.assertIn("Process crashed", proc.stderr)
        self.assertGreaterEqual(int(xml_root.attrib["errors"]), 1)
        runner_cases = [
            tc for tc in xml_root.findall("testcase") if tc.attrib.get("classname") == "instrumentation.runner"
        ]
        self.assertEqual(1, len(runner_cases))
        self.assertIsNotNone(runner_cases[0].find("error"))

    def test_unfinished_in_flight_test_marks_testcase_and_suite_as_error(self) -> None:
        stream = "\n".join([
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.CrashTest",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: test=crashesMidRun",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_RESULT: shortMsg=Native crash",
            "INSTRUMENTATION_CODE: 0",
        ])
        proc, xml_root = self._run_parser(stream)
        self.assertNotEqual(0, proc.returncode)
        crash_case = next(
            tc for tc in xml_root.findall("testcase") if tc.attrib.get("name") == "crashesMidRun"
        )
        self.assertIsNotNone(crash_case.find("error"))
        self.assertGreaterEqual(int(xml_root.attrib["errors"]), 2)

    def test_skipped_tests_and_intermediate_metric_bundles_pass_cleanly(self) -> None:
        stream = "\n".join([
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.MixedSuiteTest",
            "INSTRUMENTATION_STATUS: numtests=3",
            "INSTRUMENTATION_STATUS: test=activeTest",
            "INSTRUMENTATION_STATUS_CODE: 1",
            # Intermediate sendStatus(0, Bundle) without class/test (CrossPageTableDeviceTest pattern)
            "INSTRUMENTATION_STATUS: tableMetric=rows:128",
            "INSTRUMENTATION_STATUS_CODE: 0",
            # Intermediate sendStatus(2, Bundle) with class/test (ChatFramePerformanceDeviceTest pattern)
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.MixedSuiteTest",
            "INSTRUMENTATION_STATUS: test=activeTest",
            "INSTRUMENTATION_STATUS: p95FrameMs=9.4",
            "INSTRUMENTATION_STATUS_CODE: 2",
            # Intermediate sendStatus(0, Bundle) with custom key and no lifecycle header
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.MixedSuiteTest",
            "INSTRUMENTATION_STATUS: test=activeTest",
            "INSTRUMENTATION_STATUS: customMetric=ok",
            "INSTRUMENTATION_STATUS_CODE: 0",
            # Real test completion
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.MixedSuiteTest",
            "INSTRUMENTATION_STATUS: numtests=3",
            "INSTRUMENTATION_STATUS: test=activeTest",
            "INSTRUMENTATION_STATUS_CODE: 0",
            # Ignored test (-3)
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=2",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.MixedSuiteTest",
            "INSTRUMENTATION_STATUS: numtests=3",
            "INSTRUMENTATION_STATUS: test=ignoredTest",
            "INSTRUMENTATION_STATUS_CODE: -3",
            # Assumption-skipped test (1 then -4)
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=3",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.MixedSuiteTest",
            "INSTRUMENTATION_STATUS: numtests=3",
            "INSTRUMENTATION_STATUS: test=api29OnlyTest",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: current=3",
            "INSTRUMENTATION_STATUS: class=runtime.mobileagent.MixedSuiteTest",
            "INSTRUMENTATION_STATUS: numtests=3",
            "INSTRUMENTATION_STATUS: test=api29OnlyTest",
            "INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: API < 29",
            "INSTRUMENTATION_STATUS_CODE: -4",
            "INSTRUMENTATION_RESULT: stream=",
            "OK (2 tests)",
            "INSTRUMENTATION_CODE: -1",
        ])
        proc, xml_root = self._run_parser(stream)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("3", xml_root.attrib["tests"])
        self.assertEqual("0", xml_root.attrib["failures"])
        self.assertEqual("0", xml_root.attrib["errors"])
        self.assertEqual("2", xml_root.attrib["skipped"])

    def test_adb_nonzero_exit_with_empty_output_writes_error_xml(self) -> None:
        proc, xml_root = self._run_parser("", adb_exit=255)
        self.assertEqual(255, proc.returncode)
        self.assertGreaterEqual(int(xml_root.attrib["errors"]), 1)

    def test_final_stream_verdict_is_independent_of_test_names_and_metrics(self) -> None:
        status = [
            "INSTRUMENTATION_STATUS: class=Smoke",
            "INSTRUMENTATION_STATUS: test=recovery[Native crash]",
            "INSTRUMENTATION_STATUS: numtests=1",
        ]
        for summary, success in (("OK (1 test)", True), ("OK (1 test) continued", False),
                                 ("OK (1 test)\ntrailing text", False)):
            with self.subTest(summary=summary):
                stream = "\n".join([
                    *status, "INSTRUMENTATION_STATUS_CODE: 1",
                    *status, "INSTRUMENTATION_STATUS_CODE: 0",
                    "INSTRUMENTATION_RESULT: stream=", summary,
                    "INSTRUMENTATION_RESULT: metric=Process crashed",
                    "INSTRUMENTATION_CODE: -1",
                ])
                proc, xml = self._run_parser(stream)
                self.assertEqual(success, proc.returncode == 0, proc.stderr)
                self.assertEqual(success, xml.attrib["errors"] == "0")


class AaptXmltreeClassicValueRegressionTest(unittest.TestCase):
    def test_debuggable_boolean_in_classic_and_spaced_aapt_output(self) -> None:
        # Load the actual parser functions without running the command-line installer.
        module = ast.parse(VALIDATOR_CODE)
        module.body = [node for node in module.body if isinstance(node, (ast.FunctionDef, ast.Import, ast.ImportFrom))]
        namespace = {}
        exec(compile(module, "<apk-validator>", "exec"), namespace)
        for raw, expected in (("(type 0x12)0x0", "false"), ("(type 0x12)0xffffffff", "true"),
                              ("(type 0x12) 0x0", "false"), ("(type 0x12) 0x1", "true")):
            with self.subTest(raw=raw):
                dump = "\n".join([
                    "  E: manifest (line=2)",
                    '    A: package="runtime.mobileagent" (Raw: "runtime.mobileagent")',
                    "    E: application (line=4)",
                    f"      A: android:debuggable(0x0101000f)={raw}",
                ])
                package, debuggable, _ = namespace["parse_aapt_xmltree"](dump)
                self.assertEqual("runtime.mobileagent", package)
                self.assertEqual(expected, debuggable)


class ApkContractValidatorNoToolsTest(unittest.TestCase):
    def _run_validator_without_sdk_tools(self, apk_dir: Path, build_type: str = "debug") -> subprocess.CompletedProcess[str]:
        env = {
            "SystemRoot": os.environ.get("SystemRoot", ""),
            "WINDIR": os.environ.get("WINDIR", ""),
            "PATH": "",
            "ANDROID_SDK_ROOT": "",
            "ANDROID_HOME": "",
            "LOCALAPPDATA": "",
            "MAR_DISABLE_DEFAULT_SDK_PROBE": "1",
            "MAR_MANIFEST_BACKEND": "auto",
        }
        return subprocess.run(
            [sys.executable, "-B", "-c", VALIDATOR_CODE, str(apk_dir), build_type],
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=env,
        )

    def test_valid_debug_pair_passes_via_pure_python_axml_fallback(self) -> None:
        for utf8_pool in (False, True):
            with self.subTest(utf8_pool=utf8_pool), tempfile.TemporaryDirectory() as tmp:
                apk_dir = Path(tmp)
                write_apk(
                    apk_dir / "app-android-debug.apk",
                    build_binary_axml("runtime.mobileagent", debuggable=True, utf8_pool=utf8_pool),
                )
                write_apk(
                    apk_dir / "app-android-debug-androidTest.apk",
                    build_binary_axml(
                        "runtime.mobileagent.test",
                        debuggable=True,
                        instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "runtime.mobileagent"),
                        utf8_pool=utf8_pool,
                    ),
                )
                proc = self._run_validator_without_sdk_tools(apk_dir, "debug")
                self.assertEqual(0, proc.returncode, proc.stderr)
                out_lines = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip()]
                self.assertEqual(3, len(out_lines))
                self.assertEqual(
                    "runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner",
                    out_lines[2],
                )

    def test_valid_review_pair_passes_with_false_or_omitted_debuggable(self) -> None:
        for dbg_attr in (False, None):
            with self.subTest(debuggable=dbg_attr), tempfile.TemporaryDirectory() as tmp:
                apk_dir = Path(tmp)
                write_apk(
                    apk_dir / "app-android-review.apk",
                    build_binary_axml("runtime.mobileagent", debuggable=dbg_attr),
                )
                write_apk(
                    apk_dir / "app-android-review-androidTest.apk",
                    build_binary_axml(
                        "runtime.mobileagent.test",
                        debuggable=False,
                        instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "runtime.mobileagent"),
                    ),
                )
                proc = self._run_validator_without_sdk_tools(apk_dir, "review")
                self.assertEqual(0, proc.returncode, proc.stderr)

    def test_contract_mismatches_fail_closed_without_apkanalyzer(self) -> None:
        cases = [
            (
                "wrong_app_id",
                "debug",
                build_binary_axml("com.example.wrong", debuggable=True),
                build_binary_axml(
                    "runtime.mobileagent.test",
                    debuggable=True,
                    instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "runtime.mobileagent"),
                ),
            ),
            (
                "debug_requires_debuggable_true",
                "debug",
                build_binary_axml("runtime.mobileagent", debuggable=False),
                build_binary_axml(
                    "runtime.mobileagent.test",
                    debuggable=True,
                    instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "runtime.mobileagent"),
                ),
            ),
            (
                "review_forbids_debuggable_true",
                "review",
                build_binary_axml("runtime.mobileagent", debuggable=True),
                build_binary_axml(
                    "runtime.mobileagent.test",
                    debuggable=False,
                    instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "runtime.mobileagent"),
                ),
            ),
            (
                "wrong_test_app_id",
                "debug",
                build_binary_axml("runtime.mobileagent", debuggable=True),
                build_binary_axml(
                    "runtime.mobileagent.wrongtest",
                    debuggable=True,
                    instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "runtime.mobileagent"),
                ),
            ),
            (
                "wrong_runner_class",
                "debug",
                build_binary_axml("runtime.mobileagent", debuggable=True),
                build_binary_axml(
                    "runtime.mobileagent.test",
                    debuggable=True,
                    instrumentation=("androidx.test.runner.AndroidJUnitRunner", "runtime.mobileagent"),
                ),
            ),
            (
                "wrong_target_package",
                "debug",
                build_binary_axml("runtime.mobileagent", debuggable=True),
                build_binary_axml(
                    "runtime.mobileagent.test",
                    debuggable=True,
                    instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "com.other.target"),
                ),
            ),
            (
                "corrupt_manifest_bytes",
                "debug",
                b"not-a-valid-binary-axml-manifest",
                build_binary_axml(
                    "runtime.mobileagent.test",
                    debuggable=True,
                    instrumentation=("runtime.mobileagent.PythonRuntimeDeviceTestRunner", "runtime.mobileagent"),
                ),
            ),
        ]
        for label, build_type, main_manifest, test_manifest in cases:
            with self.subTest(case=label), tempfile.TemporaryDirectory() as tmp:
                apk_dir = Path(tmp)
                write_apk(apk_dir / f"app-android-{build_type}.apk", main_manifest)
                write_apk(apk_dir / f"app-android-{build_type}-androidTest.apk", test_manifest)
                proc = self._run_validator_without_sdk_tools(apk_dir, build_type)
                self.assertEqual(2, proc.returncode, f"Case {label} must fail closed with exit 2; stderr={proc.stderr!r}")


if __name__ == "__main__":
    unittest.main()
