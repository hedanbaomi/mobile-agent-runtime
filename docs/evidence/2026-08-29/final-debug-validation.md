<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Final debug integration validation

Date: 2026-08-29 (Asia/Taipei)  
Scope: current uncommitted integration tree based on
`7511b22ffd7a7d3021b7857b6500cbe75d037ad6`.  
This is debug and device evidence. It is not a formal Android release, signed
release acceptance, full K06 acceptance, or authorization to commit/push.

## Final build

The following command completed successfully:

```powershell
.\gradlew.bat :app-android:assembleDebug :app-android:assembleDebugAndroidTest :app-android:generateDebugSbom :data:sqlite:test :shared:knowledge-api:test :shared:provider-api:test :shared:agent-runtime:test :shared:skills-api:test :shared:serialization:test :platform:android:ipc:testDebugUnitTest licenseGuard licenseGuardReverse --continue --configure-on-demand --no-daemon --console=plain
```

- Result: `BUILD SUCCESSFUL` in 29 seconds; 391 actionable tasks, 22 executed.
- Log: `.private/overnight/build-round22-final.log`.
- Debug APK SHA-256:
  `80FF8109B908B3D4E828B846B70C98B58A5B2AC3350C7449AF235D8F40616750`.
- Android-test APK SHA-256:
  `A75741FBA9742E1A885FA7C957425B019292FD7426E7DD54E21E6FFA28CD7026`.
- Package: `runtime.mobileagent`, version `0.1.0` (`versionCode=1`),
  `minSdk=26`, `targetSdk=35`.
- CycloneDX debug SBOM: 166 resolved components at
  `app-android/build/reports/sbom/debug.cdx.json`.

## Emulator acceptance

Device: `emulator-5556`, Android 16 / API 36, x86_64, debug signing.
The instrumentation runner was
`runtime.mobileagent.test/runtime.mobileagent.PythonRuntimeDeviceTestRunner`.

| Suite | Result | Evidence |
| --- | --- | --- |
| Isolated Python runtime | 12/12 passed in 55.008 s | `.private/overnight/device/round22-python-all.log` |
| Log-overflow/valid-result race | 1/1 passed in 5.142 s | `.private/overnight/device/round22-python-log-race-only.log` |
| API embedding | 5/5 passed in 5.858 s | `.private/overnight/device/round21-api-embedding.log` |
| Knowledge runtime | 4/4 passed in 6.886 s | `.private/overnight/device/round21-knowledge-runtime.log` |

The Python suite exercised official CPython startup, isolated UID and fresh PID,
socket/process/ctypes denial, cancellation before and after dispatch, worker
death, live grant revocation, multi-frame Broker responses, raw descriptor
injection, input/output/log limits, the log-limit-versus-valid-result race and
timeout recovery. The API embedding suite
covered explicit consent, wrong dimensions, durable unknown outcomes and cached
query vectors. The Knowledge suite covered device parsing/indexing/citation paths.

An independent read-only closure review rechecked the native atomic counter,
result ordering, per-invocation initialization, host abort/cleanup and the new
device logs. It marked the former log-limit/result race closed and found no new
P1/P2. The first oversized write may reach the host pipe in full before the
worker is terminated; this preserves the documented terminate/discard policy
and does not permit a successful result.

## Visual and license checks

The latest visual source was inspected on device before the final native-only
Round22 change. Settings, third-party list/detail and Chat screenshots show the
`66ccff` surface family without the former purple container fallback:

- `.private/overnight/device/round19-settings.png`
- `.private/overnight/device/round19-third-party-list.png`
- `.private/overnight/device/round19-third-party-detail.png`
- `.private/overnight/device/round19-chat.png`

`python -m reuse lint` reported 310/310 files compliant after the final
documentation update. The final Gradle run passed both license guards. The APK contains
the AGPL license, third-party overview, schema-v1 notice index with 148
components, 300 license assets, the byte-identical CPython 3.14.7 license and
notice, the 9,129,095-byte Python stdlib ZIP and both Python native ABIs.

## Production and remaining boundaries

The announcements Worker deployment version is
`dd2be020-85ff-48ef-8b83-779a7a9cc02b`. It binds the independent D1 database,
the production signing-key secret and the licensed source artifact
`07f164ef5f473ff426488eeeddf0bb7d1cb522286c5389e85baa2351bb473ae3`.
Remote migration inspection reports no pending migrations. Cloudflare Access
team/audience remain intentionally empty, so the admin API stays fail closed.
Creating the account Zero Trust organization and choosing an identity policy
requires the owner to be present. This host also resolves `workers.dev` through
the reserved `198.18.2.56` range and cannot complete the public TLS post-check.

The 320-file knowledge load fixture copied 472,363,598 bytes and verified the
local-text/storage-waiting component: 20 text documents READY, 300 image
documents WAITING, real MiniLM/USearch, citation checks and delete isolation.
It is explicitly not a full K06 pass because real Vision, all-stage
kill/cancel/offline/disk-full injection and the Android 12-16 foreground-service
matrix remain unexecuted.
