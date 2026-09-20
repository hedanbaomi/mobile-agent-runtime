#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Fail-closed 16 KB page-size gate for packaged Android native libraries.

Android 15+ devices can run with 16 KB memory pages.  The dynamic linker maps
``PT_LOAD`` segments at the granularity of the device page size, so a library
built for 4 KB pages (``p_align == 4096``) cannot be loaded on such a device.
16 KB readiness has four independently checkable parts:

1. every ``lib/<abi>/<name>.so`` entry in the APK is stored *uncompressed*;
2. that entry's ZIP data offset is a multiple of 16 KB, so the kernel can map
   the page-cache page straight out of the APK;
3. every ``PT_LOAD`` program header has ``p_align >= 16384`` and the segment is
   congruent modulo the page size (``p_vaddr - p_offset`` is a multiple of it);
4. ``PT_GNU_RELRO`` exists and page rounding cannot protect writable data
   outside that segment (an aligned end or an unused inter-segment gap).

An unaligned RELRO end is not alone proof of a crash: page rounding may cover
only an unused gap before the next LOAD. Inspect actual writable LOAD memory
ranges; fail if rounding would protect any non-RELRO writable bytes. Own JNI
targets use both linker page-size options, producing aligned ends directly.

The tool is standard-library only and never trusts its input.  A missing file,
a corrupt or truncated archive, an unparseable or truncated ELF, and an APK
with no native libraries all fail closed with exit code 2 instead of reporting
success.

Exit codes
----------
0   every rule passed for every native library
1   at least one library violated at least one rule
2   the input could not be verified (missing, corrupt, truncated, empty, ...)

Usage
-----
    python tools/verify_native_alignment.py <apk-or-so> [--min-page-size N]
    python tools/verify_native_alignment.py app-android/build/outputs/apk/debug/app-android-debug.apk
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import zipfile
import zlib


TOOL_NAME = "verify_native_alignment"
SCHEMA = "mobileagentruntime.native-alignment/1"
DEFAULT_MIN_PAGE_SIZE = 16384

EXIT_OK = 0
EXIT_VIOLATION = 1
EXIT_ERROR = 2

ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
ELF64_EHDR_SIZE = 64
ELF64_PHDR_SIZE = 56
ELF64_E_PHOFF_OFF = 0x20
ELF64_E_PHENTSIZE_OFF = 0x36
ELF64_E_PHNUM_OFF = 0x38
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PN_XNUM = 0xFFFF

ZIP_LOCAL_SIG = 0x04034B50
ZIP_LOCAL_HEADER_SIZE = 30

LIB_ENTRY_RE = re.compile(r"^lib/([^/]+)/([^/]+\.so)$")

# Every exception a damaged archive can raise while being read.  A 16 KB gate
# must fail closed on all of them (exit code 2) rather than crash with a
# traceback or, worse, report success: zlib.error escapes the
# zipfile.BadZipFile handler for a corrupt DEFLATE stream, which is exactly
# what a truncated download looks like.
ARCHIVE_READ_ERRORS = (
    zipfile.BadZipFile,
    zlib.error,
    EOFError,
    RuntimeError,
    NotImplementedError,
    OSError,
    ValueError,
)

READ_CHUNK = 1 << 20


class VerificationError(Exception):
    """The input cannot be verified, so the caller must fail closed."""


# --------------------------------------------------------------------------- #
# generic helpers
# --------------------------------------------------------------------------- #
def _sha256_file(fh) -> str:
    digest = hashlib.sha256()
    fh.seek(0)
    while True:
        chunk = fh.read(READ_CHUNK)
        if not chunk:
            break
        digest.update(chunk)
    fh.seek(0)
    return digest.hexdigest()


def _violation(kind: str, detail: str) -> dict:
    return {"kind": kind, "detail": detail}


def _detect_kind(path: str) -> str:
    try:
        with open(path, "rb") as handle:
            head = handle.read(4)
    except OSError as exc:
        raise VerificationError(f"{path}: cannot read ({exc})") from exc
    if head == ELF_MAGIC:
        return "elf"
    if head[:2] == b"PK":
        return "apk"
    raise VerificationError(
        f"{path}: not an ELF object or ZIP/APK archive (first bytes {head!r})"
    )


# --------------------------------------------------------------------------- #
# ZIP / APK layer
# --------------------------------------------------------------------------- #
def _local_data_offset(fh, header_offset: int, file_size: int) -> int:
    """Return the absolute file offset of an entry's stored payload.

    The padding that makes an APK 16 KB aligned lives in the *local* file
    header extra field, so the central directory extra length must not be used
    here.
    """
    if header_offset < 0 or header_offset + ZIP_LOCAL_HEADER_SIZE > file_size:
        raise VerificationError(
            f"truncated ZIP local file header at offset {header_offset}"
        )
    fh.seek(header_offset)
    head = fh.read(ZIP_LOCAL_HEADER_SIZE)
    if len(head) != ZIP_LOCAL_HEADER_SIZE:
        raise VerificationError(
            f"truncated ZIP local file header at offset {header_offset}"
        )
    (signature,) = struct.unpack_from("<I", head, 0)
    if signature != ZIP_LOCAL_SIG:
        raise VerificationError(
            f"bad ZIP local file header signature at offset {header_offset}: "
            f"{signature:#010x}"
        )
    name_len, extra_len = struct.unpack_from("<HH", head, 26)
    data_offset = header_offset + ZIP_LOCAL_HEADER_SIZE + name_len + extra_len
    if data_offset > file_size:
        raise VerificationError(
            f"ZIP entry payload offset {data_offset} lies past end of file "
            f"({file_size} bytes)"
        )
    return data_offset


# --------------------------------------------------------------------------- #
# ELF layer
# --------------------------------------------------------------------------- #
def _parse_elf64(data: bytes, label: str) -> list:
    if len(data) < ELF64_EHDR_SIZE:
        raise VerificationError(
            f"{label}: truncated ELF header ({len(data)} bytes, need "
            f"{ELF64_EHDR_SIZE})"
        )
    if data[:4] != ELF_MAGIC:
        raise VerificationError(f"{label}: not an ELF object (magic {data[:4]!r})")
    elf_class = data[4]
    elf_data = data[5]
    if elf_class != ELFCLASS64:
        raise VerificationError(
            f"{label}: unsupported ELF class {elf_class}; the 16 KB gate covers "
            "64-bit ABIs (arm64-v8a, x86_64) only"
        )
    if elf_data != ELFDATA2LSB:
        raise VerificationError(
            f"{label}: unsupported ELF data encoding {elf_data}; only "
            "little-endian ELF64 is supported"
        )

    (phoff,) = struct.unpack_from("<Q", data, ELF64_E_PHOFF_OFF)
    phentsize, phnum = struct.unpack_from(
        "<HH", data, ELF64_E_PHENTSIZE_OFF
    )
    if phentsize != ELF64_PHDR_SIZE:
        raise VerificationError(
            f"{label}: unexpected e_phentsize {phentsize}, expected "
            f"{ELF64_PHDR_SIZE}"
        )
    if phnum == 0:
        raise VerificationError(f"{label}: ELF declares no program headers")
    if phnum >= PN_XNUM:
        raise VerificationError(
            f"{label}: PN_XNUM extended program header count is not supported"
        )

    table_end = phoff + phnum * phentsize
    if phoff < ELF64_EHDR_SIZE or table_end > len(data):
        raise VerificationError(
            f"{label}: program header table [{phoff:#x}, {table_end:#x}) is "
            f"outside the {len(data)}-byte file"
        )

    headers = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, p_flags = struct.unpack_from("<II", data, offset)
        (
            p_offset,
            p_vaddr,
            p_paddr,
            p_filesz,
            p_memsz,
            p_align,
        ) = struct.unpack_from("<QQQQQQ", data, offset + 8)
        headers.append(
            {
                "index": index,
                "p_type": p_type,
                "p_flags": p_flags,
                "p_offset": p_offset,
                "p_vaddr": p_vaddr,
                "p_paddr": p_paddr,
                "p_filesz": p_filesz,
                "p_memsz": p_memsz,
                "p_align": p_align,
            }
        )
    return headers


def check_elf(data: bytes, min_page_size: int, label: str) -> dict:
    """Check one ELF64 image.  Raises VerificationError when it is unverifiable."""
    headers = _parse_elf64(data, label)
    loads = [h for h in headers if h["p_type"] == PT_LOAD]
    relros = [h for h in headers if h["p_type"] == PT_GNU_RELRO]
    violations = []

    if not loads:
        violations.append(
            _violation("no_load_segments", f"{label}: ELF has no PT_LOAD segment")
        )

    load_report = []
    for seg in loads:
        end = seg["p_offset"] + seg["p_filesz"]
        if end > len(data):
            raise VerificationError(
                f"{label}: PT_LOAD #{seg['index']} file range "
                f"[{seg['p_offset']:#x}, {end:#x}) extends past end of file "
                f"({len(data)} bytes)"
            )
        align_ok = seg["p_align"] >= min_page_size
        delta = seg["p_vaddr"] - seg["p_offset"]
        congruence_ok = delta % min_page_size == 0
        if not align_ok:
            violations.append(
                _violation(
                    "load_p_align_too_small",
                    f"{label}: PT_LOAD #{seg['index']} p_align="
                    f"{seg['p_align']} is below the {min_page_size} byte page "
                    "size",
                )
            )
        if not congruence_ok:
            violations.append(
                _violation(
                    "load_vaddr_offset_congruence",
                    f"{label}: PT_LOAD #{seg['index']} p_vaddr-p_offset="
                    f"{delta:#x} is not a multiple of {min_page_size}",
                )
            )
        load_report.append(
            {
                "index": seg["index"],
                "p_offset": seg["p_offset"],
                "p_vaddr": seg["p_vaddr"],
                "p_filesz": seg["p_filesz"],
                "p_memsz": seg["p_memsz"],
                "p_align": seg["p_align"],
                "p_flags": seg["p_flags"],
                "vaddr_minus_offset": delta,
                "p_align_ok": align_ok,
                "congruence_ok": congruence_ok,
                "ok": align_ok and congruence_ok,
            }
        )

    if not relros:
        violations.append(
            _violation(
                "relro_missing",
                f"{label}: no PT_GNU_RELRO segment, so the RELRO boundary "
                "cannot be verified",
            )
        )
        relro_report = {"present": False, "count": 0, "ok": False}
    else:
        relro = relros[0]
        relro_end = relro["p_vaddr"] + relro["p_memsz"]
        if relro["p_offset"] + relro["p_filesz"] > len(data):
            raise VerificationError(
                f"{label}: PT_GNU_RELRO file range extends past end of file"
            )
        protection_start = relro["p_vaddr"] // min_page_size * min_page_size
        protection_end = (relro_end + min_page_size - 1) // min_page_size * min_page_size
        overlaps = []
        for segment in loads:
            if not segment["p_flags"] & 2:
                continue
            start = max(protection_start, segment["p_vaddr"])
            end = min(protection_end, segment["p_vaddr"] + segment["p_memsz"])
            if start < end and (start < relro["p_vaddr"] or end > relro_end):
                overlaps.append(segment["index"])
        contained = any(
            segment["p_flags"] & 2
            # lld can extend RELRO memsz into unused page padding beyond
            # the LOAD's actual bytes. Only its start must be backed; the
            # overlap check above protects all writable non-RELRO bytes.
            and segment["p_vaddr"] <= relro["p_vaddr"] < segment["p_vaddr"] + segment["p_memsz"]
            and relro_end > relro["p_vaddr"]
            for segment in loads
        )
        relro_ok = not overlaps and contained
        if not relro_ok:
            violations.append(
                _violation(
                    "relro_end_misaligned",
                    f"{label}: rounding PT_GNU_RELRO to {min_page_size} byte pages "
                    f"protects writable non-RELRO bytes in LOAD segments {overlaps} "
                    f"or RELRO does not start in a writable LOAD (contained={contained})",
                )
            )
        relro_report = {
            "present": True,
            "count": len(relros),
            "p_offset": relro["p_offset"],
            "p_vaddr": relro["p_vaddr"],
            "p_filesz": relro["p_filesz"],
            "p_memsz": relro["p_memsz"],
            "end": relro_end,
            "end_mod_page_size": relro_end % min_page_size,
            "protection_start": protection_start,
            "protection_end": protection_end,
            "writable_overlap_segments": overlaps,
            "ok": relro_ok,
        }

    return {
        "load_segments": load_report,
        "gnu_relro": relro_report,
        "violations": violations,
    }


# --------------------------------------------------------------------------- #
# per-library checks
# --------------------------------------------------------------------------- #
def _check_library_entry(
    entry: str, payload: bytes, data_offset: int, compressed: bool, min_page_size: int
) -> dict:
    name = entry.rsplit("/", 1)[-1]
    abi = entry.split("/")[1]
    library = {
        "zip_entry": entry,
        "abi": abi,
        "name": name,
        "compression": "deflated" if compressed else "stored",
        "data_offset": data_offset,
        "data_offset_mod_page_size": data_offset % min_page_size,
        "violations": [],
        "errors": [],
    }
    label = entry

    if compressed:
        library["violations"].append(
            _violation(
                "zip_entry_compressed",
                f"{entry}: native library is compressed in the APK; 16 KB "
                "devices can only map uncompressed stored entries",
            )
        )
    if data_offset % min_page_size != 0:
        library["violations"].append(
            _violation(
                "zip_data_offset_misaligned",
                f"{entry}: payload offset {data_offset} is "
                f"{data_offset % min_page_size} bytes past a {min_page_size} "
                "byte boundary",
            )
        )

    try:
        elf = check_elf(payload, min_page_size, label)
        library["elf"] = elf
        library["violations"].extend(elf["violations"])
    except VerificationError as exc:
        library["elf"] = {"load_segments": [], "gnu_relro": {"present": False}, "violations": []}
        library["errors"].append(
            _violation("unparseable_elf", str(exc))
        )

    library["ok"] = not library["violations"] and not library["errors"]
    return library


def verify_apk(path: str, min_page_size: int) -> dict:
    file_size = os.path.getsize(path)
    library = {
        "source": path,
        "source_kind": "apk",
        "source_size_bytes": file_size,
        "libraries": [],
    }
    with open(path, "rb") as raw:
        library["source_sha256"] = _sha256_file(raw)
        try:
            with zipfile.ZipFile(path) as archive:
                try:
                    broken = archive.testzip()
                except ARCHIVE_READ_ERRORS as exc:
                    raise VerificationError(
                        f"{path}: archive failed its integrity check ({exc})"
                    ) from exc
                if broken is not None:
                    raise VerificationError(
                        f"{path}: ZIP entry {broken!r} failed its CRC check"
                    )
                infos = [
                    info
                    for info in archive.infolist()
                    if LIB_ENTRY_RE.match(info.filename)
                ]
                if not infos:
                    raise VerificationError(
                        f"{path}: no lib/<abi>/<name>.so entries; there is "
                        "nothing to verify"
                    )
                for info in sorted(infos, key=lambda item: item.filename):
                    try:
                        payload = archive.read(info.filename)
                    except ARCHIVE_READ_ERRORS as exc:
                        raise VerificationError(
                            f"{path}: cannot read {info.filename!r} ({exc})"
                        ) from exc
                    offset = _local_data_offset(raw, info.header_offset, file_size)
                    library["libraries"].append(
                        _check_library_entry(
                            info.filename,
                            payload,
                            offset,
                            info.compress_type != zipfile.ZIP_STORED,
                            min_page_size,
                        )
                    )
        except zipfile.BadZipFile as exc:
            raise VerificationError(f"{path}: not a valid ZIP/APK archive ({exc})") from exc
    return library


def verify_elf_file(path: str, min_page_size: int) -> dict:
    file_size = os.path.getsize(path)
    with open(path, "rb") as raw:
        data = raw.read()
        sha256 = _sha256_file(raw)
    name = os.path.basename(path)
    library = {
        "zip_entry": None,
        "abi": None,
        "name": name,
        "compression": None,
        "data_offset": None,
        "data_offset_mod_page_size": None,
        "violations": [],
        "errors": [],
    }
    try:
        elf = check_elf(data, min_page_size, path)
        library["elf"] = elf
        library["violations"].extend(elf["violations"])
    except VerificationError as exc:
        library["elf"] = {"load_segments": [], "gnu_relro": {"present": False}, "violations": []}
        library["errors"].append(_violation("unparseable_elf", str(exc)))
    library["ok"] = not library["violations"] and not library["errors"]
    return {
        "source": path,
        "source_kind": "elf",
        "source_size_bytes": file_size,
        "source_sha256": sha256,
        "libraries": [library],
    }


# --------------------------------------------------------------------------- #
# report
# --------------------------------------------------------------------------- #
def analyze(path: str, min_page_size: int = DEFAULT_MIN_PAGE_SIZE) -> dict:
    report = {
        "schema": SCHEMA,
        "tool": TOOL_NAME,
        "path": path,
        "min_page_size": min_page_size,
        "source_kind": None,
        "source_size_bytes": None,
        "source_sha256": None,
        "libraries": [],
        "violations": [],
        "errors": [],
    }

    if min_page_size <= 0 or min_page_size & (min_page_size - 1):
        report["errors"].append(
            _violation(
                "bad_min_page_size",
                f"--min-page-size must be a positive power of two, got {min_page_size}",
            )
        )
        return _finalize(report)

    if not os.path.isfile(path):
        report["errors"].append(
            _violation("missing_input", f"{path}: not a regular file")
        )
        return _finalize(report)

    try:
        kind = _detect_kind(path)
        if os.path.getsize(path) == 0:
            raise VerificationError(f"{path}: file is empty")
    except VerificationError as exc:
        report["errors"].append(_violation("unverifiable_input", str(exc)))
        return _finalize(report)

    report["source_kind"] = kind
    try:
        if kind == "apk":
            report.update(verify_apk(path, min_page_size))
        else:
            report.update(verify_elf_file(path, min_page_size))
    except VerificationError as exc:
        report["errors"].append(_violation("unverifiable_input", str(exc)))
        return _finalize(report)
    except OSError as exc:
        report["errors"].append(_violation("unverifiable_input", f"{path}: {exc}"))
        return _finalize(report)
    except Exception as exc:  # noqa: BLE001 - a gate must never leak a traceback
        report["errors"].append(
            _violation(
                "unverifiable_input",
                f"{path}: unexpected {type(exc).__name__}: {exc}",
            )
        )
        return _finalize(report)

    for library in report["libraries"]:
        report["violations"].extend(library["violations"])
        report["errors"].extend(library["errors"])
    return _finalize(report)


def _finalize(report: dict) -> dict:
    libraries = report["libraries"]
    if report["errors"]:
        status, exit_code = "error", EXIT_ERROR
    elif report["violations"]:
        status, exit_code = "fail", EXIT_VIOLATION
    else:
        status, exit_code = "pass", EXIT_OK

    abis = sorted({lib["abi"] for lib in libraries if lib.get("abi")})
    report["status"] = status
    report["exit_code"] = exit_code
    report["summary"] = {
        "libraries": len(libraries),
        "abis": abis,
        "passed": sum(1 for lib in libraries if lib["ok"]),
        "violation_count": len(report["violations"]),
        "error_count": len(report["errors"]),
        "violation_kinds": sorted({item["kind"] for item in report["violations"]}),
    }
    return report


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog=TOOL_NAME,
        description=(
            "Fail-closed check that every native library in an APK (or a single "
            "ELF object) satisfies the Android 16 KB page-size requirements."
        ),
    )
    parser.add_argument("path", help="APK/ZIP file, or a single ELF shared object")
    parser.add_argument(
        "--min-page-size",
        type=int,
        default=DEFAULT_MIN_PAGE_SIZE,
        help=f"required page size in bytes (default {DEFAULT_MIN_PAGE_SIZE})",
    )
    parser.add_argument(
        "--output",
        metavar="FILE",
        help="also write the JSON report to FILE",
    )
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="do not print the JSON report or the summary line",
    )
    return parser


def main(argv=None) -> int:
    args = _build_parser().parse_args(argv)
    report = analyze(args.path, args.min_page_size)
    text = json.dumps(report, indent=2, sort_keys=False)

    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text + "\n")

    if not args.quiet:
        print(text)
        print(
            f"{TOOL_NAME}: {report['status']} ({report['summary']['libraries']} "
            f"librar(y|ies), {report['summary']['violation_count']} violation(s), "
            f"{report['summary']['error_count']} error(s))",
            file=sys.stderr,
        )
    return report["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
