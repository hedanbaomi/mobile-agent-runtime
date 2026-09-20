#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Unit tests for tools/verify_native_alignment.py.

Standard library only (unittest).  Every fixture is synthesised byte by byte, so
the suite needs no NDK, no Gradle build and no device: it pins the checker's
rules and its fail-closed behaviour independently of the artifacts under review.

Run with::

    python tools/test_verify_native_alignment.py -v
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import struct
import sys
import tempfile
import unittest
import uuid
import zlib
from contextlib import redirect_stderr, redirect_stdout


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_native_alignment as vna  # noqa: E402


PAGE = vna.DEFAULT_MIN_PAGE_SIZE
ELF64_EHDR_SIZE = 64
ELF64_PHDR_SIZE = 56


# --------------------------------------------------------------------------- #
# fixture builders
# --------------------------------------------------------------------------- #
def build_elf64(
    loads,
    relro=None,
    *,
    machine=0xB7,
    ei_class=vna.ELFCLASS64,
    ei_data=vna.ELFDATA2LSB,
    magic=vna.ELF_MAGIC,
    truncate_to=None,
):
    """Assemble a minimal, structurally valid ELF64 carrying `loads`/`relro`."""
    phdrs = []
    covered = [ELF64_EHDR_SIZE + ELF64_PHDR_SIZE * (len(loads) + (relro is not None))]
    for seg in loads:
        phdrs.append(
            (
                vna.PT_LOAD,
                seg.get("flags", 5),
                seg["offset"],
                seg["vaddr"],
                seg["vaddr"],
                seg["filesz"],
                seg["memsz"],
                seg["align"],
            )
        )
        covered.append(seg["offset"] + seg["filesz"])
    if relro is not None:
        phdrs.append(
            (
                vna.PT_GNU_RELRO,
                4,
                relro["offset"],
                relro["vaddr"],
                relro["vaddr"],
                relro.get("filesz", 0x100),
                relro["memsz"],
                1,
            )
        )
        covered.append(relro["offset"] + relro.get("filesz", 0x100))

    data = bytearray(max(covered))
    ident = bytearray(16)
    ident[0:4] = magic
    ident[4] = ei_class
    ident[5] = ei_data
    ident[6] = 1
    struct.pack_into(
        "<16sHHIQQQIHHHHHH",
        data,
        0,
        bytes(ident),
        3,
        machine,
        1,
        0,
        ELF64_EHDR_SIZE,
        0,
        0,
        ELF64_EHDR_SIZE,
        ELF64_PHDR_SIZE,
        len(phdrs),
        0,
        0,
        0,
    )
    for index, phdr in enumerate(phdrs):
        struct.pack_into(
            "<IIQQQQQQ", data, ELF64_EHDR_SIZE + index * ELF64_PHDR_SIZE, *phdr
        )
    raw = bytes(data)
    return raw if truncate_to is None else raw[:truncate_to]


def valid_loads():
    """Two PT_LOAD segments describing a 16 KB aligned library."""
    return [
        {"offset": 0x0, "vaddr": 0x0, "filesz": 0x400, "memsz": 0x400, "align": PAGE, "flags": 5},
        {"offset": 0x4000, "vaddr": 0x4000, "filesz": 0x400, "memsz": 0x4000, "align": PAGE, "flags": 6},
    ]


def valid_relro():
    """GNU_RELRO ending exactly on a 16 KB boundary (0x4000 + 0x4000)."""
    return {"offset": 0x4000, "vaddr": 0x4000, "filesz": 0x400, "memsz": 0x4000}


def build_zip(entries, *, align=None):
    """Build a ZIP whose local headers may carry 16 KB alignment padding.

    `entries` is a list of ``(name, payload, stored)``.  Padding goes into the
    *local* header extra field only (as zipalign does), so the checker must not
    rely on the central directory extra length.
    """
    out = bytearray()
    central = bytearray()
    for name, payload, stored in entries:
        name_bytes = name.encode("utf-8")
        crc = zlib.crc32(payload) & 0xFFFFFFFF
        if stored:
            method, blob = 0, payload
        else:
            # ZIP method 8 stores a *raw* DEFLATE stream, not a zlib-wrapped
            # one, so compressobj(wbits=-15) is required here.
            compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
            method, blob = 8, compressor.compress(payload) + compressor.flush()

        extra = b""
        if align:
            pad = (-(len(out) + 30 + len(name_bytes))) % align
            if pad and pad < 4:
                pad += align
            if pad:
                extra = struct.pack("<HH", 0xFFFF, pad - 4) + b"\x00" * (pad - 4)

        local_offset = len(out)
        out += struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50,
            20,
            0,
            method,
            0,
            0,
            crc,
            len(blob),
            len(payload),
            len(name_bytes),
            len(extra),
        )
        out += name_bytes + extra + blob
        central += struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50,
            20,
            20,
            0,
            method,
            0,
            0,
            crc,
            len(blob),
            len(payload),
            len(name_bytes),
            0,
            0,
            0,
            0,
            0,
            local_offset,
        )
        central += name_bytes

    cd_offset = len(out)
    out += central
    out += struct.pack(
        "<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries), len(central), cd_offset, 0
    )
    return bytes(out)


def apk_bytes(names, *, align=PAGE, stored=True, elf=None, truncate_elf_to=None):
    payload = elf if elf is not None else build_elf64(valid_loads(), valid_relro())
    if truncate_elf_to is not None:
        payload = payload[:truncate_elf_to]
    return build_zip([(name, payload, stored) for name in names], align=align)


@contextlib.contextmanager
def captured():
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        yield out, err


class FixtureMixin(unittest.TestCase):
    def setUp(self):
        # Create the scratch directory with default permissions instead of
        # tempfile.mkdtemp: under some sandboxes mkdtemp's 0o700 mode produces a
        # directory that the same process may no longer write to or delete.
        # Cleanup stays best effort so a sandbox ACL artifact cannot turn a
        # passing assertion into a teardown error.
        self.tmpdir = os.path.join(
            tempfile.gettempdir(), f"align-test-{uuid.uuid4().hex}"
        )
        os.makedirs(self.tmpdir, exist_ok=True)
        self.addCleanup(shutil.rmtree, self.tmpdir, ignore_errors=True)

    def write(self, name, data):
        path = os.path.join(self.tmpdir, name)
        with open(path, "wb") as handle:
            handle.write(data)
        return path

    def run_cli(self, argv):
        with captured() as (out, err):
            code = vna.main(argv)
        return code, out.getvalue(), err.getvalue()


# --------------------------------------------------------------------------- #
# ELF rules
# --------------------------------------------------------------------------- #
class ElfRuleTests(FixtureMixin):
    def test_relro_padding_past_load_end_is_safe(self):
        loads = valid_loads()
        loads[1]["memsz"] = 0x1FD0
        relro = valid_relro()
        relro["memsz"] = 0x2000
        result = vna.check_elf(build_elf64(loads, relro), PAGE, "padded.so")
        self.assertEqual(result["violations"], [])

    def test_unaligned_relro_with_unused_gap_is_safe(self):
        loads = valid_loads()
        loads[1]["memsz"] = 0x2000
        relro = valid_relro()
        relro["memsz"] = 0x2000
        # Rounding 0x6000 -> 0x8000 changes no writable LOAD bytes.
        loads.append({"offset": 0x8000, "vaddr": 0x8000, "filesz": 0x100, "memsz": 0x100, "align": PAGE, "flags": 6})
        result = vna.check_elf(build_elf64(loads, relro), PAGE, "gap.so")
        self.assertEqual(result["violations"], [])
        self.assertEqual(result["gnu_relro"]["end_mod_page_size"], 0x2000)

    def test_rounding_into_another_writable_segment_fails(self):
        loads = valid_loads()
        loads[1]["memsz"] = 0x2000
        loads.append({"offset": 0x7000, "vaddr": 0x7000, "filesz": 0x100, "memsz": 0x100, "align": PAGE, "flags": 6})
        relro = valid_relro()
        relro["memsz"] = 0x2000
        result = vna.check_elf(build_elf64(loads, relro), PAGE, "overlap.so")
        self.assertFalse(result["gnu_relro"]["ok"])

    def test_relro_outside_load_memory_fails(self):
        relro = valid_relro()
        relro["vaddr"] = 0x10000
        result = vna.check_elf(build_elf64(valid_loads(), relro), PAGE, "outside.so")
        self.assertFalse(result["gnu_relro"]["ok"])

    def test_valid_16kb_library_passes(self):
        result = vna.check_elf(build_elf64(valid_loads(), valid_relro()), PAGE, "lib.so")
        self.assertEqual(result["violations"], [])
        self.assertTrue(all(seg["ok"] for seg in result["load_segments"]))
        self.assertTrue(result["gnu_relro"]["ok"])
        self.assertEqual(result["gnu_relro"]["end"], 0x8000)
        self.assertEqual(result["gnu_relro"]["end_mod_page_size"], 0)

    def test_4kb_load_align_is_rejected(self):
        loads = valid_loads()
        for seg in loads:
            seg["align"] = 4096
        result = vna.check_elf(build_elf64(loads, valid_relro()), PAGE, "lib.so")
        kinds = {item["kind"] for item in result["violations"]}
        self.assertEqual(kinds, {"load_p_align_too_small"})
        self.assertEqual(len(result["violations"]), 2)
        self.assertEqual([seg["p_align"] for seg in result["load_segments"]], [4096, 4096])

    def test_4kb_library_passes_when_the_page_size_is_4kb(self):
        loads = valid_loads()
        for seg in loads:
            seg["align"] = 4096
        relro = {"offset": 0x4000, "vaddr": 0x4000, "filesz": 0x400, "memsz": 0x1000}
        result = vna.check_elf(build_elf64(loads, relro), 4096, "lib.so")
        self.assertEqual(result["violations"], [])

    def test_incongruent_load_offsets_are_rejected(self):
        loads = valid_loads()
        loads[1]["vaddr"] = 0x5000
        result = vna.check_elf(build_elf64(loads, valid_relro()), PAGE, "lib.so")
        kinds = {item["kind"] for item in result["violations"]}
        self.assertIn("load_vaddr_offset_congruence", kinds)

    def test_relro_end_misaligned_is_rejected(self):
        relro = {"offset": 0x4000, "vaddr": 0x4000, "filesz": 0x400, "memsz": 0x1400}
        result = vna.check_elf(build_elf64(valid_loads(), relro), PAGE, "lib.so")
        kinds = {item["kind"] for item in result["violations"]}
        self.assertEqual(kinds, {"relro_end_misaligned"})
        self.assertEqual(result["gnu_relro"]["end"], 0x5400)
        self.assertEqual(result["gnu_relro"]["end_mod_page_size"], 0x1400)

    def test_missing_relro_is_rejected(self):
        result = vna.check_elf(build_elf64(valid_loads(), None), PAGE, "lib.so")
        kinds = {item["kind"] for item in result["violations"]}
        self.assertEqual(kinds, {"relro_missing"})
        self.assertFalse(result["gnu_relro"]["present"])

    def test_elf_without_load_segments_is_rejected(self):
        result = vna.check_elf(build_elf64([], valid_relro()), PAGE, "lib.so")
        kinds = {item["kind"] for item in result["violations"]}
        self.assertIn("no_load_segments", kinds)

    def test_truncated_elf_header_fails_closed(self):
        payload = build_elf64(valid_loads(), valid_relro(), truncate_to=32)
        with self.assertRaises(vna.VerificationError):
            vna.check_elf(payload, PAGE, "lib.so")

    def test_truncated_program_header_table_fails_closed(self):
        payload = build_elf64(valid_loads(), valid_relro(), truncate_to=200)
        with self.assertRaises(vna.VerificationError):
            vna.check_elf(payload, PAGE, "lib.so")

    def test_load_segment_past_end_of_file_fails_closed(self):
        payload = build_elf64(valid_loads(), valid_relro(), truncate_to=0x3000)
        with self.assertRaises(vna.VerificationError):
            vna.check_elf(payload, PAGE, "lib.so")

    def test_bad_magic_fails_closed(self):
        payload = build_elf64(valid_loads(), valid_relro(), magic=b"XXXX")
        with self.assertRaises(vna.VerificationError):
            vna.check_elf(payload, PAGE, "lib.so")

    def test_elf32_is_not_silently_accepted(self):
        payload = build_elf64(valid_loads(), valid_relro(), ei_class=1)
        with self.assertRaises(vna.VerificationError):
            vna.check_elf(payload, PAGE, "lib.so")

    def test_big_endian_is_not_silently_accepted(self):
        payload = build_elf64(valid_loads(), valid_relro(), ei_data=2)
        with self.assertRaises(vna.VerificationError):
            vna.check_elf(payload, PAGE, "lib.so")


# --------------------------------------------------------------------------- #
# ZIP / APK rules
# --------------------------------------------------------------------------- #
class ApkRuleTests(FixtureMixin):
    NAMES = (
        "lib/arm64-v8a/libmobileagent_python.so",
        "lib/arm64-v8a/libusearch_jni.so",
        "lib/x86_64/libmobileagent_python.so",
        "lib/x86_64/libusearch_jni.so",
    )

    def test_aligned_apk_passes(self):
        path = self.write("good.apk", apk_bytes(self.NAMES))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "pass")
        self.assertEqual(report["exit_code"], vna.EXIT_OK)
        self.assertEqual(report["summary"]["libraries"], 4)
        self.assertEqual(report["summary"]["abis"], ["arm64-v8a", "x86_64"])
        self.assertEqual(report["summary"]["passed"], 4)

    def test_zip_data_offsets_are_16kb_aligned(self):
        path = self.write("good.apk", apk_bytes(self.NAMES))
        report = vna.analyze(path, PAGE)
        for library in report["libraries"]:
            self.assertEqual(library["data_offset"] % PAGE, 0, library["zip_entry"])
            self.assertEqual(library["data_offset_mod_page_size"], 0)

    def test_misaligned_zip_offsets_are_rejected(self):
        path = self.write("misaligned.apk", apk_bytes(self.NAMES, align=None))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "fail")
        self.assertEqual(report["exit_code"], vna.EXIT_VIOLATION)
        self.assertTrue(report["violations"])
        for item in report["violations"]:
            self.assertEqual(item["kind"], "zip_data_offset_misaligned")

    def test_compressed_native_library_is_rejected(self):
        path = self.write("deflated.apk", apk_bytes(self.NAMES, stored=False))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "fail")
        kinds = {item["kind"] for item in report["violations"]}
        self.assertEqual(kinds, {"zip_entry_compressed"})
        self.assertEqual(report["libraries"][0]["compression"], "deflated")

    def test_apk_without_native_libraries_fails_closed(self):
        path = self.write("empty.apk", build_zip([("assets/readme.txt", b"hi", True)], align=PAGE))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)
        self.assertEqual(report["errors"][0]["kind"], "unverifiable_input")

    def test_corrupt_apk_fails_closed(self):
        path = self.write("not-a-zip.apk", b"this is definitely not a zip archive")
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)

    def test_truncated_apk_fails_closed(self):
        blob = apk_bytes(self.NAMES)
        path = self.write("truncated.apk", blob[: len(blob) // 3])
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)

    def test_corrupt_stored_payload_fails_closed(self):
        blob = bytearray(apk_bytes(self.NAMES))
        # Flip a byte inside the first stored ELF payload (e_ident padding, so
        # the image still parses) and require the CRC check to fail closed.
        start = blob.index(vna.ELF_MAGIC)
        blob[start + 8] ^= 0xFF
        path = self.write("bad-crc.apk", bytes(blob))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)
        self.assertEqual(report["errors"][0]["kind"], "unverifiable_input")

    def test_corrupt_deflate_stream_fails_closed(self):
        blob = bytearray(apk_bytes(self.NAMES, stored=False))
        # Locate the first entry's raw DEFLATE payload through its local header
        # and damage it; decompression or the CRC check must then fail closed.
        name_len, extra_len = struct.unpack_from("<HH", blob, 26)
        data_offset = 30 + name_len + extra_len
        for index in range(4):
            blob[data_offset + index] ^= 0xFF
        path = self.write("bad-deflate.apk", bytes(blob))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)

    def test_truncated_elf_inside_apk_fails_closed(self):
        path = self.write("trunc-elf.apk", apk_bytes(self.NAMES, truncate_elf_to=40))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)
        self.assertTrue(all(lib["errors"] for lib in report["libraries"]))

    def test_mixed_abis_report_every_library(self):
        good = build_elf64(valid_loads(), valid_relro())
        loads = valid_loads()
        for seg in loads:
            seg["align"] = 4096
        bad = build_elf64(loads, valid_relro())
        path = self.write(
            "mixed.apk",
            build_zip(
                [
                    ("lib/arm64-v8a/libmobileagent_python.so", good, True),
                    ("lib/x86_64/libmobileagent_python.so", bad, True),
                ],
                align=PAGE,
            ),
        )
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["status"], "fail")
        by_entry = {lib["zip_entry"]: lib for lib in report["libraries"]}
        self.assertTrue(by_entry["lib/arm64-v8a/libmobileagent_python.so"]["ok"])
        self.assertFalse(by_entry["lib/x86_64/libmobileagent_python.so"]["ok"])

    def test_empty_file_fails_closed(self):
        path = self.write("empty.bin", b"")
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)

    def test_missing_file_fails_closed(self):
        report = vna.analyze(os.path.join(self.tmpdir, "nope.apk"), PAGE)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)

    def test_unknown_container_fails_closed(self):
        path = self.write("mystery.bin", b"\x01\x02\x03\x04 and more")
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["exit_code"], vna.EXIT_ERROR)

    def test_bad_min_page_size_is_rejected(self):
        path = self.write("good.apk", apk_bytes(self.NAMES))
        self.assertEqual(vna.analyze(path, 1000)["exit_code"], vna.EXIT_ERROR)
        self.assertEqual(vna.analyze(path, 0)["exit_code"], vna.EXIT_ERROR)


# --------------------------------------------------------------------------- #
# bare ELF mode
# --------------------------------------------------------------------------- #
class BareElfTests(FixtureMixin):
    def test_aligned_shared_object_passes(self):
        path = self.write("libgood.so", build_elf64(valid_loads(), valid_relro()))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["source_kind"], "elf")
        self.assertEqual(report["status"], "pass")
        self.assertEqual(report["summary"]["libraries"], 1)

    def test_4kb_shared_object_is_rejected(self):
        loads = valid_loads()
        for seg in loads:
            seg["align"] = 4096
        path = self.write("libbad.so", build_elf64(loads, valid_relro()))
        report = vna.analyze(path, PAGE)
        self.assertEqual(report["exit_code"], vna.EXIT_VIOLATION)


# --------------------------------------------------------------------------- #
# CLI surface
# --------------------------------------------------------------------------- #
class CliTests(FixtureMixin):
    def test_pass_exit_code_and_json(self):
        path = self.write("good.apk", apk_bytes(ApkRuleTests.NAMES))
        code, out, err = self.run_cli([path])
        self.assertEqual(code, 0)
        report = json.loads(out)
        self.assertEqual(report["status"], "pass")
        self.assertEqual(report["schema"], vna.SCHEMA)
        self.assertEqual(report["min_page_size"], PAGE)
        self.assertIn("pass", err)

    def test_violation_exit_code_is_one(self):
        path = self.write("misaligned.apk", apk_bytes(ApkRuleTests.NAMES, align=None))
        code, out, _ = self.run_cli([path])
        self.assertEqual(code, vna.EXIT_VIOLATION)
        self.assertEqual(json.loads(out)["status"], "fail")

    def test_error_exit_code_is_two_and_never_reports_pass(self):
        code, out, _ = self.run_cli([os.path.join(self.tmpdir, "absent.apk")])
        self.assertEqual(code, vna.EXIT_ERROR)
        report = json.loads(out)
        self.assertEqual(report["status"], "error")
        self.assertEqual(report["summary"]["passed"], 0)

    def test_quiet_suppresses_output(self):
        path = self.write("good.apk", apk_bytes(ApkRuleTests.NAMES))
        code, out, err = self.run_cli([path, "--quiet"])
        self.assertEqual(code, 0)
        self.assertEqual(out, "")
        self.assertEqual(err, "")

    def test_output_file_is_written(self):
        path = self.write("good.apk", apk_bytes(ApkRuleTests.NAMES))
        target = os.path.join(self.tmpdir, "report.json")
        code, _, _ = self.run_cli([path, "--output", target, "--quiet"])
        self.assertEqual(code, 0)
        with open(target, encoding="utf-8") as handle:
            self.assertEqual(json.load(handle)["status"], "pass")

    def test_min_page_size_override(self):
        loads = valid_loads()
        for seg in loads:
            seg["align"] = 4096
        relro = {"offset": 0x4000, "vaddr": 0x4000, "filesz": 0x400, "memsz": 0x1000}
        path = self.write(
            "lib4k.so", build_elf64(loads, relro)
        )
        self.assertEqual(self.run_cli([path])[0], vna.EXIT_VIOLATION)
        self.assertEqual(self.run_cli([path, "--min-page-size", "4096"])[0], 0)

    def test_bad_min_page_size_exits_two(self):
        path = self.write("good.apk", apk_bytes(ApkRuleTests.NAMES))
        self.assertEqual(self.run_cli([path, "--min-page-size", "12345"])[0], vna.EXIT_ERROR)


if __name__ == "__main__":
    unittest.main(verbosity=2)
