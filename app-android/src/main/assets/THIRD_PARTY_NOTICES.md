<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Third-party notices

This debug APK carries the original notices for its resolved
`debugRuntimeClasspath` dependencies. First-party code remains
`AGPL-3.0-only`; these third-party texts retain their upstream licenses.

The complete, versioned index is `licenses/index.json`. It contains 144
external Maven coordinates, the exact version POM source, the license source,
and APK-relative paths to the full license text. Each text file has a `.license`
REUSE sidecar. The index also records SHA-256 values for the text and cached
artifact evidence.

The bundle preserves legal text found in cached JAR/AAR files before using an
official standard or exact-version source. The POM metadata groups are:

| POM metadata | Count |
| --- | ---: |
| The Apache Software License, Version 2.0 | 136 |
| The Apache License, Version 2.0 (Kotlin stdlib family) | 4 |
| MIT License (ONNX Runtime 1.29.0) | 1 |
| Bouncy Castle Licence (1.79) | 1 |
| POM without a license element, resolved from parent or exact artifact | 2 |

OkHttp 4.12.0 additionally carries the original
`okhttp3/internal/publicsuffix/NOTICE` and the complete Mozilla Public License
2.0 text referenced by that notice. Its own Apache 2.0 text remains separate.

The index lists the native and model assets managed by other owners as
`pending-main-flow`; their APK presence and hashes must be verified by the
main build/device flow:

- `licenses/cpython-3.14.7/LICENSE.txt` and `NOTICE.txt`
- `licenses/usearch-2.25.1/LICENSE.txt`
- `licenses/onnxruntime-1.29.0/LICENSE.txt`
- `modelpacks/all-MiniLM-L6-v2/LICENSES/Apache-2.0.txt`

This asset notice is generated alongside the repository notice and is intended
to remain readable from the installed APK without network access.
