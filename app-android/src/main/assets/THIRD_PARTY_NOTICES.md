<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Third-party notices

This APK carries the original notices for the application's resolved dependency
set (generated from the captured `debugRuntimeClasspath` report, which the
non-debuggable review variant resolves identically). First-party code remains
`AGPL-3.0-only`; these third-party texts retain their upstream licenses.

The complete, versioned index is `licenses/index.json`. It contains 148
external Maven coordinates (including Shizuku `aidl`, `api`, `provider`, and
`shared`, all at 13.1.5), the exact version POM source, the license source,
and APK-relative paths to the full license text. Each text file has a `.license`
REUSE sidecar. The index also records SHA-256 values for the text and cached
artifact evidence.

The bundle preserves legal text found in cached JAR/AAR files before using an
official standard or exact-version source. The POM metadata groups are:

| POM metadata | Count |
| --- | ---: |
| The Apache Software License, Version 2.0 | 136 |
| The Apache License, Version 2.0 (Kotlin stdlib family) | 4 |
| MIT License (Shizuku API x4 and ONNX Runtime 1.29.0) | 5 |
| Bouncy Castle Licence (1.79) | 1 |
| POM without a license element, resolved from parent or exact artifact | 2 |

OkHttp 4.12.0 additionally carries the original
`okhttp3/internal/publicsuffix/NOTICE` and the complete Mozilla Public License
2.0 text referenced by that notice. Its own Apache 2.0 text remains separate.

The index lists the native and model assets managed by other owners with exact
generated-boundary legal/payload SHA-256 values. `runtime-notices.py --check`
verifies the module-generated roots or an explicitly supplied APK/AAB/ZIP
asset boundary; it fails closed when a boundary or material is missing:

- CPython 3.14.7: `LICENSE.txt` `b0e25a78cffb43f4d92de8b61ccfa1f1f98ecbc22330b54b5251e7b6ba010231`,
  `NOTICE.txt` `87e76594ca56bd6b1116d2ee9b902b98c0940863762f9ef822de9a3cec31fcb3`.
  The standard-library payload `python/python3.14.zip` is recorded and verified by
  its exact SHA-256 in `licenses/index.json` (`payloads[]`); it is deliberately not
  repeated here, because its bytes can change when the archive's inputs or generator
  change and this prose must not pin a value that can become stale.
- USearch 2.25.1: `LICENSE.txt` `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4`,
  `NOTICE.txt` `145493df94feaef58efd5f5fcedc9d4d3c08a285dbbf87a9eefae3455cb475a4`,
  and pinned source archive `30dd99efab891a6385a89ecd3a3a8a85ed7d3f064b7657588fc3ef5ccd2d52e3`.
- ONNX Runtime 1.29.0: `LICENSE.txt` `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c`,
  `NOTICE.txt` `af50216ba6c698e64b3a91f63278543d79f963938ca14fd23df45f2d6de3491c`.
- distiluse-base-multilingual-cased-v2: Apache-2.0; pinned Xenova int8 ONNX and upstream Sentence Transformers Dense Tanh projection.
  Exact weights, tokenizer, projection and manifest SHA256 values and pinned source revisions are recorded in `licenses/index.json` and the packaged `LICENSE-NOTICE.txt`.




This asset notice is generated alongside the repository notice and is intended
to remain readable from the installed APK without network access.
