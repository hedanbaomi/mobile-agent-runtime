<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# M6 CPython Android evidence

## Scope and boundary

This evidence covers the isolated CPython path owned by the M6 runtime worker:

- `runtime/python-android/**`
- `platform/android/ipc/**`

The application/DI adapter remains with the main worker. The device and shared
root `HANDOFF.md` are also maintained by the main worker. No emulator was
started or controlled by this worker, and no release signing or production
operation was performed.

## Official CPython input

The runtime uses the official Python 3.14.7 Android embeddable packages from
the Python release page and its `ftp.python.org` artifacts. Python 3.14.7 was
released on 2026-08-05. The downloaded archives and Sigstore bundles are kept
under the ignored `runtime/python-android/build/official/3.14.7/` directory;
they are not treated as first-party source.

| ABI | Official artifact | SHA-256 |
| --- | --- | --- |
| `arm64-v8a` | `python-3.14.7-aarch64-linux-android.tar.gz` | `6d50cc3aa66e414a439594089bcdfb5f1264358155c70c1f00471c24cfb477fb` |
| `x86_64` | `python-3.14.7-x86_64-linux-android.tar.gz` | `2c16ce2359565cd8c24f86cfb75630768ba6607e732946b294b969797f583b60` |

The package license checked in the extracted prefix is the Python Software
Foundation License Version 2 (`prefix/lib/python3.14/LICENSE.txt`). Python's
Android embedding documentation identifies the same PSF license for CPython;
the M6 first-party bridge remains `AGPL-3.0-only` and does not relicense the
upstream package. The debug APK asset set carries the complete upstream text
at `assets/licenses/cpython-3.14.7/LICENSE.txt` plus a generated
`assets/licenses/cpython-3.14.7/NOTICE.txt` containing both official ABI URLs
and pinned SHA-256 values.

## Implemented path

1. The host validates a one-time `InvocationTicket`, entrypoint, package hash,
   input size and per-invocation limits. It copies package material into a
   private temporary file, verifies SHA-256, opens it read-only, and passes it
   as a `ParcelFileDescriptor`; the path is never exposed to Python.
2. The host copies the stored, uncompressed `assets/python/python3.14.zip`
   asset into a private read-only descriptor. The Gradle task builds that zip from the
   official pure-Python standard library and the first-party
   `mobileagent_sdk.py`; user archives cannot add native extensions.
3. The Binder start transaction carries only bounded metadata, one
   host-generated 32-byte URL-safe nonce and seven file descriptors. Result,
   Broker request/response and log payloads use private
   length-prefixed pipe frames; the input pipe is a bounded, finite UTF-8
   stream. Control frames are capped at 64 KiB, output at 1 MiB, logs at 512
   KiB, input at 256 KiB and Broker calls at 20. Broker responses dynamically
   split the raw JSON value with UTF-8 boundary aware binary search (up to
   1024 chunks, with an 8 MiB aggregate cap), so JSON string escaping cannot
   exceed the control frame cap, then reassemble in order inside the isolated
   worker.
4. Every native-to-host Broker request and result header carries that nonce;
   the host compares it exactly and aborts the disposable service on a missing
   or mismatched value. The nonce is never copied into Python input, SDK
   objects, host-to-worker responses, logs or error text. The child-side pipe
   descriptors are directional: result/Broker-request/log are write-only and
   input/package/stdlib plus Broker-response are read-only. Raw writes by a
   script therefore cannot forge an authenticated frame; API-26 has no
   portable seccomp contract in this module, so this boundary is enforced by
   descriptor direction, native nonce authentication and immediate process
   abort.
5. `IsolatedPythonService` is `android:exported="false"` and
   `android:isolatedProcess="true"`. It rejects a non-isolated UID, accepts
   one start only, loads `libpython3.14.so` only through JNI, and stops/kills
   its process after completion. A timeout sends cancel and then kills the
   disposable service after the short cancellation grace period.
6. The native entrypoint configures CPython isolated mode with environment and
   user-site disabled, a fixed `/proc/self/fd/<n>` module path, no bytecode
   writes and no CWD path. It imports `module:function`, parses JSON input,
   calls the function, and serializes a bounded JSON result.
7. `_mobileagent.request()` is the only SDK side-effect bridge. It sends the
   complete ticket on every Broker frame and waits for one bounded response.
   The host calls `authorize(ticket)` before execution and before each request;
   the app adapter must re-read the single skill/knowledge grant on each call.
8. Native Python audit hooks deny non-FD file access, sockets, process launch
   (including `os.posix_spawn`/`os.posix_spawnp`), dynamic native extensions
   and remote execution. The isolated service has no
   manifest permissions, including no `INTERNET`; network, storage, model and
   tool operations therefore remain host capabilities.

## Outcome contract

After START has been attempted, a missing, truncated, malformed or
nonce-invalid result, Binder death before a reliably drained result, output or
log limit termination, and timeout/cancellation are reported as
`UNKNOWN_OUTCOME`; callers must not automatically replay them. A timeout or
cancellation before START is dispatched remains a known local
`TIMED_OUT`/`CANCELLED` outcome. Coroutine cancellation still propagates, but
as `PythonExecutionCancellation(outcome)` with `dispatchAccepted` so the app
can persist the unknown state and suppress replay. The host drains a result
pipe briefly after Binder death so a valid result already written before the
one-shot process exits is not misclassified.

## Local verification commands

The following are the exact preparation/verification commands for the
repository root. They do not start or control an emulator. The parent worker
owns the final module/build execution after the shared NDK installation gate:

```powershell
Get-FileHash runtime/python-android/build/official/3.14.7/python-3.14.7-aarch64-linux-android.tar.gz -Algorithm SHA256
Get-FileHash runtime/python-android/build/official/3.14.7/python-3.14.7-x86_64-linux-android.tar.gz -Algorithm SHA256
.\gradlew.bat :runtime:python-android:verifyOfficialCpython --no-daemon --console=plain
.\gradlew.bat :platform:android:ipc:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :runtime:python-android:assembleDebug --no-daemon --console=plain
```

The hash values above are pinned in `runtime/python-android/build.gradle.kts`;
the Gradle task fails closed when either official archive, Sigstore bundle,
header, library or PSF license is missing. The final command is the M6 module
build gate; APK installation and emulator acceptance are intentionally left to
the main worker.

## Round12 runtime diagnosis

The first round12 device run (`.private/overnight/device/round12-native-device-tests.log`)
failed all 11 Python tests with the same `InterruptedIOException: read
interrupted by close() on another thread` from `drainLog` while the host was
cleaning up after an invocation. The companion logcat shows the isolated
service started and loaded `libmobileagent_python.so`; it contains no Python
initialization exception before the host unbound the service. The runtime now
marks cleanup before closing host pipe descriptors and treats only the
resulting API-26 `IOException` in the log drain as expected cleanup. A log-pipe
I/O error before cleanup is still propagated, so it cannot turn an unknown
worker outcome into success. This fix is source-reviewed only here; the main
worker must rebuild and rerun the normal JSON test before recording device
acceptance.

## Open verification boundary

The app-level `ToolExecutor`/grant adapter and the S03–S07 emulator acceptance
matrix still require the main worker's integration and device run. Until those
are recorded, this document does not claim device acceptance or release
readiness.

## Round14 PyConfig diagnosis and source fix

The round14 normal JSON device run (`.private/overnight/device/round14-python-normal.log`) reached the isolated worker (`dispatchAccepted=true`, PID 8716) and failed with `status=FAILED`, `errorCode=python_error`, `message=stage=python_initialize`. Its logcat shows the JNI library loaded and no Python exception text before the service exited; this distinguishes a CPython initialization status failure from the later `json` or skill import stages, but does not by itself prove the exact CPython status.

The official CPython 3.14.7 initialization contract says an isolated `PyConfig` should specify `home` so that unspecified path outputs do not trigger default path calculation. `initialize_python` now sets both `config.home` and `config.stdlib_dir` to the current read-only stdlib ZIP descriptor path (`/proc/self/fd/<n>`), while retaining the explicit stdlib and package `module_search_paths` and all isolated flags. The native result diagnostic additionally maps only a fixed allowlist of `PyStatus.func` values (`init_fs_encoding`, `init_importlib`, `init_sys_streams`, `init_interp_main`, `pyinit_main`, `pyinit_core`) to `stage=python_initialize status=...`; it never returns the status message, path, input, ticket or nonce.

The changed C source passed direct API-26 clang syntax checks for `x86_64-none-linux-android26` and `aarch64-none-linux-android26` with the verified CPython headers (exit 0; only the existing unused `python_request` module parameter warning). No Gradle, APK install or emulator command was run by this worker after the patch. The main worker must rebuild and rerun the normal JSON test; this document does not claim device acceptance.

### Round15 diagnostic amendment

The round15 APK still returned only `stage=python_initialize`; the native diagnostic had returned early when CPython supplied a NULL `PyStatus.func`. That early return is removed. Initialization failures now expose only `status_type` (`error` or `exit`), numeric `exitcode`, a bounded ASCII identifier `func` (or `unknown`), and a fixed phrase category (`filesystem_codec`, `encodings`, `importlib`, `std_streams`, `site`, `android_streams`, or `unknown`). The original `PyStatus.err_msg` is never serialized. The identifier accepts only `[A-Za-z0-9_.]` and at most 96 bytes. Both API-26 ABI clang syntax checks remain exit 0 with the existing unused parameter warning. Device verification of this amendment is pending the main worker's next APK.

### Round16 initialization diagnosis and audit bootstrap fix

The round16 normal JSON run still failed at `stage=python_initialize`, now
with `status_type=error`, `exitcode=0`, `func=unknown` and `msg=unknown`.
The native path was amended without weakening isolation: the native audit hook
continues to register before CPython initialization, but its policy callback is
disabled until `Py_InitializeFromConfig` has returned successfully. This avoids
calling Python object APIs or raising a Python exception while CPython is still
constructing the interpreter and importing its trusted frozen/stdlib bootstrap;
`site_import` remains disabled and no skill or package ZIP is present in
`module_search_paths` during that window. The initial list contains only the
official stdlib FD. After initialization succeeds, the policy is enabled first;
only then is the package FD path appended through `sys.path`, before `json` or
the skill entrypoint is imported. Audit path checks now accept only exact
Unicode or bytes spellings of the three read-only package/stdlib/input FD paths
and `/dev/urandom` or `/dev/null`; process, socket, native-extension and
remote-execution denials are unchanged. Failure to obtain or append `sys.path`
is reported as a failed invocation.

The native diagnostic captures `PyStatus` before `PyConfig_Clear`, because an
error message may be owned by the temporary configuration. It retains the
bounded type/exitcode/identifier/category fields and may include `detail` only
when the message is short printable ASCII words with no path separators,
control/non-ASCII bytes or opaque long token. No environment, traceback,
userdata, input, token or nonce is returned. Direct API-26 clang syntax checks
for both `x86_64-none-linux-android26` and `aarch64-none-linux-android26` exit
0 with only the existing unused `python_request` parameter warning. The main
worker must rebuild and rerun the normal JSON test; this source change has not
been device-verified here.

### Round17 stdlib ZIP diagnosis

The round17 normal JSON run still failed in the CPython initialization phase,
now with the fixed diagnostic `stage=python_initialize status_type=error
exitcode=0 func=unknown msg=encodings detail=Failed to import encodings
module`. The accompanying logcat shows the isolated service loading the JNI
library and then being killed by its one-shot lifecycle; no Python traceback or
skill import is present. A read-only local inspection of the generated asset
found 516 entries, including `encodings/__init__.py`, `encodings/utf_8.py`,
`codecs.py`, `importlib/__init__.py`, `zipimport.py`, `json/__init__.py`,
`os.py` and `_android_support.py`; `encodings/__init__.py` is stored
uncompressed (`compress_type=0`). The ZIP is 9,129,095 bytes with SHA-256
`72ed77263089d7d92a17f0df611e53e3b95045ad0c43b3be5c1d111f6e9b5277` in the
current local generated output. This is asset evidence only, not device
acceptance.

At that round, the native path performed a bounded read-only stdlib descriptor
check before CPython initialization: `fstat` validated a regular file and the
16 MiB bound, `pread` checked ZIP magic without changing the descriptor offset,
and a direct `open(O_RDONLY|O_CLOEXEC, /proc/self/fd/<n>)` plus magic check
tested the exact path used by zipimport. Failures returned only fixed stage names and errno
categories (`eacces`, `enoent`, `ebadf`, `einval`, `eio`, `enotdir`, `eloop`,
or `other`), with no path, content, ticket or nonce. Both ABI API-26 clang
syntax checks exit 0 with the existing unused parameter warning. The main
worker must use the next APK result to distinguish an invalid asset/FD from an
isolated `/proc/self/fd` reopen policy failure.

### Round18b isolated code-stream fix

Round18b supplied the first exact runtime cause: the descriptor passed to the
isolated process was readable (`fstat`/`pread` succeeded), while reopening its
`/proc/self/fd/<n>` spelling failed with `errno=eacces` (PID 11410, UID 99016,
`dispatchAccepted=true`). The runtime therefore does not retain a backing path,
change file permissions, or rely on `/proc` reopening.

The native worker now registers `PyFile_SetOpenCodeHook` before
`Py_InitializeFromConfig`. The hook accepts only the exact per-invocation
stdlib or package FD path. It validates a regular file and a 16 MiB stdlib or
32 MiB package bound, reads the still-open read-only descriptor with bounded
`pread` chunks, rechecks the size and ZIP magic, and constructs a binary
`_io.BytesIO` from those bytes. `_io` is obtained only from the already loaded
built-in module with `PyImport_GetModule`; no module import or path reopen is
performed. Each returned stream has an independent cursor and no `fileno`, so
it cannot alter the host descriptor offset or expose an IPC FD. Unknown paths,
non-regular/oversize archives and read failures return fixed Python errors; the
audit policy is never globally disabled around the hook.

The `BytesIO` factory is cached before package code can mutate `_io` attributes
and released before `Py_FinalizeEx`. The previous descriptor `fstat`/offset/
magic checks remain, but `/proc/self/fd` reopening is no longer a gate because
the hook consumes the descriptor directly. Direct API-26 clang syntax checks
for `x86_64-none-linux-android` and `aarch64-none-linux-android` both exit 0;
only the existing unused `python_request` module parameter warning remains.
The main worker must rebuild and run the normal JSON and adversarial device
tests; this source patch has not been device-verified here.

### Round19 raw descriptor write guard

Round19 device execution reached the real worker and passed the minimal normal
invocation plus the first six runtime checks. The raw-descriptor test then
showed that `os.write` on the inherited descriptor 1 remained writable. The
official CPython 3.14.7 `posixmodule.c` implementation calls `_Py_write` and
`writev` directly without a Python audit event, so adding only an audit-hook
event check would not enforce this policy.

The native worker now installs a fixed `PermissionError` C callable into the
`write`, `writev`, `pwrite`, `pwritev`, `sendfile`, `copy_file_range`, `splice`
and `tee` attributes that exist on both built-in `posix` and `os` modules. This
happens after the interpreter is initialized, the audit policy is enabled and
the package path is appended, but before any package or skill import. The audit
hook also rejects those explicit event names for defense in depth and future
CPython changes. Integer descriptor construction through `open`,
`_io.FileIO` and `os.fdopen` remains covered by the existing `open` audit rule,
which rejects non-path arguments.

Native `write_all` is unchanged: result and Broker frames continue to use the
native channel descriptors and authenticated nonce. `print(..., flush=True)`
continues to use the already-created `sys.stdout` stream, not the replaced
`os.write` functions, so the log-overflow fixture can still exercise the host
log limit. No device result is claimed for this source change; the parent
worker must rebuild and rerun the raw-descriptor and log-overflow tests. Direct
API-26 strict clang syntax checks for both `aarch64-none-linux-android` and
`x86_64-none-linux-android` exit 0 with `-Wall -Wextra -Werror` (suppressing
only the pre-existing unused `python_request` module parameter warning).

### Round20 native standard-stream bridge

Round20 confirmed the raw-descriptor policy passed, but the log-overflow
fixture timed out instead of reaching the host `maxLogBytes=1024` drain limit.
The fixture uses `print(..., flush=True)`, so the inherited CPython standard
stream was not a reliable Android log-pipe path even though native `dup2` was
performed before initialization.

After interpreter initialization, package-path setup and raw-descriptor guard
installation, the native worker now creates a private static Python stream
type and assigns separate instances to `sys.stdout`, `sys.__stdout__`,
`sys.stderr` and `sys.__stderr__`. The type has only positional `write(str)`
and `flush()` methods, no instance dictionary, `fileno`, `buffer`, path or
descriptor field. `write` UTF-8 encodes the supplied Unicode text and calls the
existing native `write_all(state->log_fd, ...)`; `flush` is a no-op after a
valid native-channel check. Write failure raises a fixed/OSError error and
never falls back to an inherited stream.

The result/Broker native writers, channel nonce and raw-descriptor guards are
unchanged. The bridge writes to the same pipe consumed by host `drainLog`, so
the existing byte limit and abort path remain authoritative; Python `print`
now reaches that pipe without exposing its FD. This source change has not yet
been device-verified; the parent worker must rerun the log-overflow fixture and
the full 11-test set. Direct API-26 strict clang syntax checks for both
`aarch64-none-linux-android` and `x86_64-none-linux-android` exit 0 with
`-Wall -Wextra -Werror` (suppressing only the pre-existing unused
`python_request` module parameter warning).

### Round21 producer-side log-limit ordering

Round21's independent review found a result-ordering race: the host
`drainLog` coroutine could be scheduled after a fast script had written beyond
`maxLogBytes` and returned a successful value. The native bridge now keeps an
atomic, saturating per-invocation byte counter. Each `write(str)` accounts for
the UTF-8 byte length before calling the existing native `write_all` on the
same host-drained log pipe; once the configured limit is crossed, an atomic
`log_limit_exceeded` flag is permanent. The bytes are still sent so the host
drain can observe its normal limit and abort path, while subsequent writes fail
closed.

The final native result writer checks that flag with acquire ordering before
serializing any result and overrides every candidate outcome, including a
successful Python return or cancellation, to `UNKNOWN_OUTCOME` with
`errorCode=log_limit` and no value. Counter saturation prevents wraparound
from re-enabling success. Broker/result frame authentication, raw-descriptor
guards, standard-stream object boundaries and the public Kotlin API are
unchanged. This is a source/static fix only; the parent worker must rebuild and
rerun the log-overflow and complete device suites. Direct API-26 strict clang
syntax checks for both ABIs remain required after the rebuild.

### Round22 main-agent device closure

The main agent rebuilt both native ABIs and the Android instrumentation APK as
part of `.private/overnight/build-round22-final.log`; the full Gradle command
completed successfully. The new race fixture prints 2048 ASCII bytes with
`flush=True` under `maxLogBytes=1024` and immediately returns valid JSON. Its
isolated run completed 1/1 in 5.142 seconds and observed
`UNKNOWN_OUTCOME/log_limit`, no returned value, `dispatchAccepted=true`, and a
fresh successful next invocation:
`.private/overnight/device/round22-python-log-race-only.log`.

The complete `PythonRuntimeDeviceTest` suite then completed 12/12 in 55.008
seconds at `.private/overnight/device/round22-python-all.log`. It includes the
new ordering counterexample plus official CPython startup, fresh isolated UID
and PID, network/process/ctypes denial, pre/post-dispatch cancellation, worker
death, grant revocation, multi-frame Broker response, raw descriptor injection,
input/output/log limits and timeout recovery. This closes the source-only
Round21 caveat for the tested Android 16/API 36 x86_64 debug environment; it is
not a formal release or the broader Android-version matrix.

The original independent reviewer then performed a bounded read-only closure
review. It confirmed the UTF-8 byte threshold and saturating atomic ordering,
the acquire check before every result serialization, fresh per-invocation state,
host abort/cleanup and the new-PID recovery assertion. The former P2 race is
closed and no new P1/P2 was found. The first oversized write can be delivered
to the host pipe before termination, so this evidence proves termination and
that success cannot win; it does not claim byte-perfect truncation at the exact
limit.
