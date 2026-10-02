# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Rebuild unmodified CPython x86_64 for Android API26 isolated execution.

Supply exact official archives; no Skill execution, package installation,
seccomp changes or optional native extension installation occurs here.
"""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import zipfile

SOURCE_SHA256 = "3b48dac8fb59f62eaa67ac83c1eb12bda1b7a08406dd286e252c11a66be27f81"
NDK_SHA256 = "601246087a682d1944e1e16dd85bc6e49560fe8b6d61255be2829178c8ed15d9"

def verify(path, expected):
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"Pinned archive mismatch: {path.name}")

def unpack_ndk(archive, destination):
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            path = destination / entry.filename
            if not path.resolve().is_relative_to(destination.resolve()):
                raise ValueError("NDK archive path escapes build directory")
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode):
                target = source.read(entry).decode("utf-8")
                if not (path.parent / target).resolve().is_relative_to(destination.resolve()):
                    raise ValueError("NDK symlink escapes build directory")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.symlink_to(target)
            else:
                source.extract(entry, destination)
                if mode:
                    path.chmod(mode & 0o777)
    ndk = destination / "android-ndk-r27d"
    if "Pkg.Revision = 27.3.13750724" not in (ndk / "source.properties").read_text():
        raise ValueError("Unexpected NDK revision")
    return ndk

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-archive", required=True, type=Path)
    parser.add_argument("--ndk-archive", required=True, type=Path)
    parser.add_argument("--build-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        parser.error("This pinned recipe requires Linux x86_64")
    if sys.version_info[:3] != (3, 14, 4):
        parser.error("Use CPython 3.14.4 as the pinned build interpreter")
    if not 1 <= args.jobs <= 16:
        parser.error("jobs must be in 1..16")
    build, output = args.build_dir.resolve(), args.output.resolve()
    if build.exists() or output.exists():
        parser.error("build-dir/output must be new; existing paths are never replaced")
    verify(args.source_archive, SOURCE_SHA256)
    verify(args.ndk_archive, NDK_SHA256)
    build.mkdir(parents=True)
    with tarfile.open(args.source_archive) as archive:
        archive.extractall(build, filter="data")
    ndk = unpack_ndk(args.ndk_archive, build / "ndk")
    source = build / "Python-3.14.7"
    cross = source / "cross-build/x86_64-linux-android"
    cross.mkdir(parents=True)
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    env = os.environ.copy()
    env.update({
        "AR": str(toolchain / "llvm-ar"),
        "CC": str(toolchain / "x86_64-linux-android26-clang"),
        "CXX": str(toolchain / "x86_64-linux-android26-clang++"),
        "RANLIB": str(toolchain / "llvm-ranlib"),
        "READELF": str(toolchain / "llvm-readelf"),
        "STRIP": str(toolchain / "llvm-strip"),
        "CFLAGS": f"-D__BIONIC_NO_PAGE_SIZE_MACRO -ffile-prefix-map={build}=/usr/src/mobileagent-cpython",
        "LDFLAGS": "-Wl,--build-id=sha1 -Wl,--no-rosegment -Wl,-z,max-page-size=16384 "
                   "-Wl,-z,common-page-size=16384 -Wl,--no-undefined -lm",
        "SOURCE_DATE_EPOCH": "1785888000",
        "LC_ALL": "C",
    })
    commands = [
        ["../../configure", "--host=x86_64-linux-android", "--build=x86_64-pc-linux-gnu",
         f"--with-build-python={Path(sys.executable).resolve()}", "--prefix=/usr/local",
         "--without-ensurepip", "--enable-shared", "--without-static-libpython",
         "--without-mimalloc", "--disable-test-modules"],
        ["make", f"-j{args.jobs}", "libpython3.14.so"],
        [str(toolchain / "llvm-strip"), "--strip-debug", "libpython3.14.so"],
    ]
    for command in commands:
        subprocess.run(command, cwd=cross, env=env, check=True)
    if "/* #undef WITH_MIMALLOC */" not in (cross / "pyconfig.h").read_text():
        raise ValueError("mimalloc unexpectedly enabled")
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cross / "libpython3.14.so", output)
    print(f"SHA256 {hashlib.sha256(output.read_bytes()).hexdigest()} {output.name}")

if __name__ == "__main__":
    main()
