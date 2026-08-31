<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Mobile Agent Runtime

This context defines the v2 trust and execution vocabulary used when an Agent asks the Android host to perform work. The v2 prompt is the single normative source for authority tooling; this file supplies the short vocabulary only.

## Language

**Execution Authority**:
A live, verifiable source of operating-system identity or resource access that may satisfy one part of an Agent tool request. The current elevated values are `SHIZUKU` and `WIRED_ADB` only.
_Avoid_: High-permission switch, super permission, Root mode.

**Capability Grant**:
A user-owned, revocable authorization that binds an Agent snapshot to a named capability, resource scope, limits, selected Authority where applicable, and revision.
_Avoid_: Permission toggle, model permission, blanket approval.

**Approval**:
A one-call foreground decision over normalized arguments and a live Capability Grant; it does not create a new grant. Dangerous Mode autonomous policy changes the per-call confirmation rule but not revalidation or audit.

**Authority Adapter**:
A fail-closed integration that proves one Execution Authority and exposes its declared typed operations. It never chooses a replacement Authority when the selected one fails.
_Avoid_: Shell bridge, privilege fallback, best-available authority.

**Shizuku Authority**:
The `SHIZUKU` adapter backed by a live Shizuku Binder, explicit Shizuku permission, compatible service and a supported non-root identity. Binder death makes it temporarily unavailable; it does not revoke the grant or switch to Wired ADB.
_Avoid_: Root mode, ADB mode.

**Wired ADB Authority**:
The `WIRED_ADB` adapter backed by a user-started Windows Desktop Companion, official adb over USB, explicit device serial binding, `adb reverse`, loopback and an authenticated App-level session.
_Avoid_: Wireless ADB, inherited desktop permission, unauthenticated localhost shell.

**Selected Authority**:
The single external adapter chosen by the user for a run. If it becomes unavailable, the operation fails closed instead of selecting another adapter. Shizuku and Wired ADB are peers with no automatic fallback.

**Workspace backend**:
An implementation of backend-neutral typed file semantics. It may be app-private storage, a user-selected SAF tree, or the selected privileged adapter. SAF is not an Execution Authority and never becomes a global path.

**Typed tool**:
A provider-neutral wire tool such as `file_read_text`, `file_write_text` or `memory_search`, mapped to a capability and backend-neutral semantics. Agent-facing schema must not contain `shizuku_*`, `adb_*`, `saf_*`, Binder, URI, serial or real paths.

**Dangerous Mode**:
A persistent, explicitly confirmed high-risk policy that may register `shell_exec` only when the Agent capability, selected Authority and current policy all allow it. It has `ENABLED_CONFIRM_HIGH_RISK` and `ENABLED_AUTONOMOUS` forms.
_Avoid_: Root mode, unrestricted host shell, safety sandbox.

**Shell execution**:
The one-shot Android `/system/bin/sh` operation behind `shell_exec`, implemented by either `ShizukuShellExecutor` or `WiredAdbShellExecutor`. It supports timeout, cancel, output limits and structured terminal results; it is not a PTY, long-lived session, Skill subprocess, PowerShell or host shell.

**Command Template**:
A fixed operation identifier with typed arguments, bounded resources, and known side effects. This term applies to typed tools and Companion protocol operations. Dangerous `shell_exec` intentionally accepts command text under its separate high-risk control-plane; it is not an allowlist runner.
_Avoid_: Raw command for typed tools, shell string in the host process.

**E2E BLOCKED**:
Evidence may show implementation or automated behavior, but a real Shizuku service, USB Companion/device, or `debuggable=false` review-like build is missing. It is not `DEVICE_PASS`, `RELEASED`, or permission to claim real-world completion.

## Explicit exclusions

The v2 route does not include Root/UID 0, app Wireless ADB pairing, DPC/Device Owner/Profile Owner, Termux, PTY, LAN bridge, automatic ADB install, host PowerShell/cmd.exe, or arbitrary host filesystem access. A failure in Shizuku or Wired ADB never falls back to any of these or to the other current Authority.
