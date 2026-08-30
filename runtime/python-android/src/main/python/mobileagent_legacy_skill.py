# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

"""Compatibility runner for audited manifestless Claude Skill CLI programs.

The host supplies one verified program path plus an in-memory virtual file set.
No Android or host filesystem path is exposed to the imported program.
"""

import io
import sys
import types


_MAX_FILES = 128
_MAX_ARGUMENTS = 64
_MAX_PATH = 240
_MAX_TEXT = 131_072
_MAX_STDOUT = 262_144


def _relative_path(value):
    if isinstance(value, _VirtualPath):
        return value._value
    if not isinstance(value, str):
        raise ValueError("virtual path must be text")
    value = value.replace("\\", "/")
    while value.startswith("./"):
        value = value[2:]
    pieces = value.split("/")
    if not value or value.startswith("/") or any(piece in ("", ".", "..") for piece in pieces):
        raise ValueError("virtual path must be relative and normalized")
    if len(value) > _MAX_PATH or ":" in pieces[0]:
        raise ValueError("virtual path is outside the invocation")
    return value


class _VirtualPath:
    def __init__(self, value):
        self._value = _relative_path(value)

    @property
    def name(self):
        return self._value.rsplit("/", 1)[-1]

    @property
    def stem(self):
        name = self.name
        return name.rsplit(".", 1)[0] if "." in name else name

    @property
    def parent(self):
        parent = self._value.rsplit("/", 1)[0] if "/" in self._value else ".virtual-root"
        return _VirtualPath(parent)

    def resolve(self):
        return self

    def read_text(self, encoding="utf-8"):
        if encoding.lower().replace("_", "-") != "utf-8":
            raise ValueError("only UTF-8 virtual files are supported")
        try:
            return _VIRTUAL_FILES[self._value]
        except KeyError as error:
            raise OSError("virtual file is unavailable") from error

    def rglob(self, pattern):
        if pattern not in ("*.md", "*.markdown"):
            raise ValueError("legacy compatibility only supports Markdown corpus discovery")
        prefix = self._value.rstrip("/") + "/"
        suffix = pattern[1:]
        return [
            _VirtualPath(path)
            for path in sorted(_VIRTUAL_FILES)
            if path.startswith(prefix) and path.endswith(suffix)
        ]

    def __fspath__(self):
        return self._value

    def __str__(self):
        return self._value

    def __repr__(self):
        return "VirtualPath(<invocation-file>)"

    def __hash__(self):
        return hash(self._value)

    def __eq__(self, other):
        return isinstance(other, _VirtualPath) and self._value == other._value


_VIRTUAL_FILES = {}


def _validated_input(value):
    source_field = "__mobileagent_verified_program_source"
    if not isinstance(value, dict) or set(value) != {"program", "arguments", "files", source_field}:
        raise ValueError("legacy program input is invalid")
    arguments = value["arguments"]
    files = value["files"]
    if not isinstance(arguments, list) or len(arguments) > _MAX_ARGUMENTS:
        raise ValueError("legacy program arguments exceed the limit")
    if any(not isinstance(argument, str) or len(argument) > 256 for argument in arguments):
        raise ValueError("legacy program argument is invalid")
    if not isinstance(files, list) or len(files) > _MAX_FILES:
        raise ValueError("legacy virtual file set exceeds the limit")
    virtual_files = {}
    for item in files:
        if not isinstance(item, dict) or set(item) != {"path", "text"}:
            raise ValueError("legacy virtual file is invalid")
        path = _relative_path(item["path"])
        text = item["text"]
        if not isinstance(text, str) or len(text) > _MAX_TEXT or path in virtual_files:
            raise ValueError("legacy virtual file is invalid")
        virtual_files[path] = text
    program = _relative_path(value["program"])
    source = value[source_field]
    if not isinstance(source, str) or not source or len(source.encode("utf-8")) > 256 * 1024 or "\x00" in source:
        raise ValueError("verified program source is invalid")
    return program, list(arguments), virtual_files, source


def run(value):
    """Run one package program with argv and a read-only in-memory Markdown tree."""
    program, arguments, virtual_files, source = _validated_input(value)

    global _VIRTUAL_FILES
    _VIRTUAL_FILES = virtual_files
    fake_pathlib = types.ModuleType("pathlib")
    fake_pathlib.Path = _VirtualPath
    previous_pathlib = sys.modules.get("pathlib")
    previous_argv = sys.argv
    previous_stdout = sys.stdout
    previous_stderr = sys.stderr
    output = io.StringIO()
    try:
        sys.modules["pathlib"] = fake_pathlib
        sys.argv = [program] + arguments
        sys.stdout = output
        sys.stderr = output
        namespace = {
            "__builtins__": __builtins__,
            "__file__": program,
            "__name__": "__mobileagent_legacy_program__",
            "__package__": None,
        }
        exec(compile(source, "<imported-skill-program>", "exec"), namespace, namespace)
        main = namespace.get("main")
        if not callable(main):
            raise ValueError("program has no callable main")
        try:
            main()
        except SystemExit as exit_status:
            if exit_status.code not in (None, 0):
                raise RuntimeError("program exited unsuccessfully") from None
        stdout = output.getvalue()
        if len(stdout) > _MAX_STDOUT:
            raise ValueError("program output exceeds the compatibility limit")
        return {"program": program, "stdout": stdout}
    finally:
        sys.argv = previous_argv
        sys.stdout = previous_stdout
        sys.stderr = previous_stderr
        if previous_pathlib is None:
            sys.modules.pop("pathlib", None)
        else:
            sys.modules["pathlib"] = previous_pathlib
        _VIRTUAL_FILES = {}
