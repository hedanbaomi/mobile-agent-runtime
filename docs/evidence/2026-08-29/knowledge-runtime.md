<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Knowledge runtime evidence — 2026-08-29

## Scope and boundary

- Baseline inspected: `7511b22ffd7a7d3021b7857b6500cbe75d037ad`.
- This note covers the knowledge parser, Android PDF storage adapter, import
  WorkManager seam, local ONNX model pack, optional API embedding seam, and
  optional USearch JNI module. It does not authorize a production release,
  external Vision/API call, or a device acceptance claim.
- The M4RR image attachment and consent binding changes already present in
  `1a035aa` remain unchanged. Renderer failure continues to produce an
  explicit empty `PAGE` blocker; visual content is never silently published as
  text-only evidence.

## Implemented interfaces

### PDF parsing and rasterization

`PdfPageRasterizer` and `RenderedPdfPage` are platform-neutral shared types.
`PdfParser.parse(bytes, rasterizer)` renders only pages classified as visual
(drawing, inline/image content, or no extracted text). A successful page is a
locatable `IMAGE` asset with `page=N`, `section=pdf-page-N`, and PNG bytes.
Missing/failed pages keep a `PAGE` blocker. Raw inline samples are accepted as
standalone images only when they have PNG/JPEG/GIF signatures; otherwise they
remain blocked until a complete page raster is available.

`AndroidPdfRendererAdapter` uses Android `PdfRenderer` API 26-compatible code,
a private cache temporary file, one open page at a time, ARGB_8888 rendering,
PNG encoding, and bounded output dimensions (2,048 max dimension, 4,000,000
pixels). The temporary source is deleted in `finally`.

### Foreground import/resume/cancel

`ImportWorkScheduler` enqueues unique, tagged, local-only WorkManager work with
only the persisted job id in `Data`; it provides `enqueue`, `resume`, and
`cancel`. `ImportWorker` calls the registered repository handler on
`Dispatchers.IO`, publishes a foreground `dataSync` notification, and returns
user-action states as successful terminal work rather than retrying in a loop.
`FAILED` (including an `UNKNOWN_OUTCOME` error), `CANCELLED`, and `READY` are
terminal acknowledgements. Only the persisted `RETRY_WAIT` stage requests a
retry; a handler exception fails the WorkManager attempt because replaying it
could duplicate an external Vision call. A missing DI handler has a bounded
startup retry (three attempts), then fails closed.
`KnowledgeRepository.cancelImport` is idempotent and preserves the CAS source.
The app container must register `ImportWorkerRegistry.handler` and
`cancellationHandler`; the worker cannot reconstruct an arbitrary repository
after process death without that explicit DI registration.

`AndroidContextSqlite` now uses a re-entrant JVM lock plus SQLite savepoints for
nested repository transaction scopes. The outer scope remains
`BEGIN IMMEDIATE`/`COMMIT`; inner scopes use `SAVEPOINT`/`ROLLBACK TO`/`RELEASE`.

### Local ONNX model pack

The Android embedding module downloads at build time from the official pinned
Hugging Face repository and refuses hash mismatches:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `onnx/model.onnx` | 90,405,214 | `6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452` |
| `tokenizer.json` | 466,247 | `be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037` |

- Model: [`sentence-transformers/all-MiniLM-L6-v2`](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
- Pinned revision: `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`
- Official file URLs: [ONNX weights](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/1110a243fdf4706b3f48f1d95db1a4f5529b4d41/onnx/model.onnx), [tokenizer](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/1110a243fdf4706b3f48f1d95db1a4f5529b4d41/tokenizer.json)
- Model card license: Apache-2.0 (the model repository has no separate
  `LICENSE` file at the pinned revision; the authoritative model-card metadata
  is recorded in the generated `LICENSE-NOTICE.txt`). ONNX Runtime Android
  dependency: [`com.microsoft.onnxruntime:onnxruntime-android:1.29.0`](https://onnxruntime.ai/docs/install/),
  whose Maven POM identifies the runtime as MIT licensed.
- Space: `onnx:all-MiniLM-L6-v2@1110a243fdf4706b3f48f1d95db1a4f5529b4d41:d384:cosine`.

`AndroidModelPackLoader` verifies the manifest against the pinned id,
revision, hashes, source prefix, license, tokenizer, pooling, normalization,
and distance, then copies both files atomically into the app's no-backup
directory. `OnnxTextEmbedder` runs BERT WordPiece tokenization,
`input_ids`/`attention_mask`/`token_type_ids`, ONNX `last_hidden_state`, mean
pooling, and L2 normalization locally. This model card is English; the CJK
lexical path remains authoritative for CJK terms, and no multilingual claim is
made.

The Gradle asset root is `build/generated/embedding-assets`, with generated
files under `modelpacks/all-MiniLM-L6-v2/`, matching the runtime asset lookup
path. The generated pack also includes the repository's complete
`LICENSES/Apache-2.0.txt` alongside `LICENSE-NOTICE.txt`; a short notice alone
is not used as the model license text.

The embedding asset task also copies the complete official ONNX Runtime
1.29.0 MIT text from
`runtime/embedding-onnx/third-party/onnxruntime-1.29.0/LICENSE.txt` to
`assets/licenses/onnxruntime-1.29.0/LICENSE.txt`, with a URL/version notice.
The source license hash is
`2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c`.

`KnowledgeRepository` accepts an explicit API `TextEmbedder` and binds each
knowledge base to one embedding space. API consent is checked before the
adapter is invoked; local and API spaces are not mixed. The default JVM
fixture `HashingTextEmbedder` remains for deterministic tests only. Production
App DI must inject the verified `OnnxTextEmbedder`.

`ApiEmbeddingBinding` now makes the API space auditable without a schema
migration: provider id, endpoint (path case preserved), provider revision,
model id/revision, dimension, explicit data scope, and the explicit
`modelProfileId` are all encoded in a stable `api-embedding-v1` space id stored
on the KB. `modelProfileId` defaults to `modelId` for source compatibility.
`ApiEmbeddingBinding.parseSpaceId` is strict and round-trips only canonical
current identities; legacy, reordered, malformed, or non-canonical values
return unavailable instead of being guessed. `ApiEmbeddingTextEmbedder` is the
runtime bridge for the existing `ModelAdapter.embed(EmbeddingRequest, secret)`
contract. It resolves the secret immediately before one batch call, clears it
afterward, validates count/dimension/finite values, and never retries.
`KnowledgeRepository` first checks statically registered adapters by exact
space id, then may invoke the optional resolver with that exact persisted id;
resolver results must match both the id and parsed binding dimension. There is
no provider discovery or implicit network fallback. `createApiKnowledgeBase`
and `rebindApiKnowledgeBase` require a matching backend; rebinding requires
fresh text consent and builds a new generation before switching the active
pointer. If a rebind embedding call is uncertain, successful target-space
cache rows remain durable and a failed-job gate survives SQL rollback; a later
rebind requires explicit duplicate-charge acknowledgement.

Import jobs retain API consent separately from Vision consent. A visual import
can finish Vision processing and remain `AWAITING_EMBEDDING_CONSENT`; the
explicit `grantEmbeddingConsent` path preserves the Vision decision. A
transport or malformed-response uncertainty is persisted as
`UNKNOWN_OUTCOME`; `resumeImport` refuses it and only
`retryUnknownEmbedding(..., acknowledgeDuplicateCharge = true)` can replay it.
The retry path does not clear the persisted error before attempting the one
explicit replay: success replaces it with `READY`, while a crash or second
uncertain result leaves the gate durable. Rebind markers are exposed to the UI
only through the read-only `hasUnknownApiRebind(knowledgeBaseId)` method and
are rejected by the ordinary import retry API.
Successful vectors are immutable and reused by `(spaceId, contentHash)` across
new document versions, KBs, and index generations; cache misses use the
optional batch backend once. Query embedding selects only the KB's persisted
space and requires prior API consent, with no implicit local/provider fallback.
The public `rebuildIndex` path also fails closed for an API-bound KB unless
persisted API embedding consent exists; only an explicitly consented import or
rebind operation may pass the internal rebuild guard. Query attempts use the
versioned `embedding_query_attempts` table (migration v9): the key is exactly
`(knowledgeBaseId, complete spaceId, SHA-256(query UTF-8))`, and the row never
stores query text. The first call writes a pending row before the provider
request; an uncertain result leaves `retryAuthorized = false` and raises
`ApiQueryUnknownOutcomeException` with only the three key fields. A matching
query is blocked before adapter resolution until
`authorizeApiQueryRetry(..., acknowledgeDuplicateCharge = true)` atomically
grants one retry. The next matching call consumes that grant before the
request; only a validated vector deletes the row. Cancellation, failure, or
process interruption leaves the row for explicit user action, and a different
   query hash, space, or KB is independent. The v10 `embedding_query_vectors`
   table now stores successful query vectors under `(space_id, query_hash)`.
   The vector is committed before the attempt row is removed; an identical
   existing byte sequence is idempotent and any changed dimension/bytes is
   rejected without replacement. Local ANN/SQLite failures after that commit
   therefore reuse the cached vector rather than charging the provider again.

   API import, rebuild, and rebind also expose suspending repository bridges.
   Each uses v10 `embedding_operations` with `PREPARED`, `DISPATCHED`,
   `CACHE_READY`, and `PUBLISHED` states. Only short SQLite transactions are
   held while a full space, consent fingerprint, input manifest, live
   KB/document state, active document pointers, and the active generation
   pointer are captured or CAS-checked.
   A final short pre-dispatch check covers the same cancellation, deletion, and
   consent boundary. The provider call is made after `DISPATCHED` outside
   `indexLock`; successful chunk vectors are committed before generation
   publication. Cancellation, interruption, or any exception after dispatch
   is durable `UNKNOWN` and is never replayed by resume. A cached operation can
   be finalized without another provider call after a generation publication
   failure.
An API-bound KB cannot be imported or resumed with `embeddingIsApi = false`; the
repository fails closed before local embedding is selected.
The existing binding fields remain in `knowledge_bases.embedding_space_id`,
`import_jobs.embedding_is_api`, and `import_jobs.embedding_consent`; the
query-attempt gate is the separate v9 `embedding_query_attempts` table owned by
the product-data migration.

Citation lookup now requires a nonempty, live knowledge-base id matching the
document's `kb_id`, and an explicit nonempty document version. Asset citations
must carry a nonempty matching asset version; an empty or mismatched version is
rejected. `evidenceBytes(citation)` performs that full validation before
returning the blob media type and bytes, so the Android caller need not access
the CAS path. The repository test covers a citation forged from a second live
KB, a deleted KB, and blank KB/version fields.

Vision consent fingerprints now include independent provider and model
revisions. The legacy single `revision` constructor maps it to both fields;
new callers pass both revisions. Endpoint trailing separators are trimmed
without lowercasing, so path case remains part of the consent/cache binding.

### USearch

The module uses the official [USearch v2.25.1 release](https://github.com/unum-cloud/USearch/releases/tag/v2.25.1),
whose source license is [Apache-2.0](https://github.com/unum-cloud/USearch/blob/v2.25.1/LICENSE).
The pinned GitHub tag archive used for the source build has SHA-256
`30dd99efab891a6385a89ecd3a3a8a85ed7d3f064b7657588fc3ef5ccd2d52e3`.
The source preparation task now copies the verbatim official
`build/source/USearch-2.25.1/LICENSE` (SHA-256
`c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4`) to
`assets/licenses/usearch-2.25.1/LICENSE.txt` and writes a `NOTICE.txt` with
the source URL, tag, and archive hash. Both paths are generated on clean
builds, so the APK does not depend on an ignored build cache for notices.
`UsearchVectorIndex` and `UsearchVectorIndexFactory` call a JNI wrapper built
from the official `index_dense.hpp` implementation with cosine/f32 vectors.
The Android module declares only `arm64-v8a` and `x86_64`; the source build is
required because the upstream prebuilt Android artifact does not cover both
ABIs. The current host has CMake 3.22.1. The module now declares Android NDK
`27.3.13750724`; the main agent is installing/validating that exact version
before this native target can be built.

`KnowledgeRepository` accepts an optional `VectorIndexFactory`; without one,
JVM tests use the explicit cosine implementation. App DI must inject
`UsearchVectorIndexFactory` only after both ABI libraries are built.

## Verification record

- `:shared:knowledge-api:test` passed once before the added renderer tests.
- The model pack generation task completed and local hashes above were checked
  with PowerShell `Get-FileHash -Algorithm SHA256`.
- The first embedding assemble exposed a missing explicit serialization
  dependency; the build script now declares it. A fresh embedding assemble and
  native USearch build are pending the main agent's serial Gradle coordination
  and Android NDK installation. The source archive is present in the ignored
  build cache and its SHA-256 was rechecked locally.
- No emulator was started or controlled by this worker. Device acceptance,
  foreground-service runtime behavior, PdfRenderer device rendering, ONNX
  inference, and USearch ABI loading remain unverified here.
- `ApiEmbeddingTextEmbedder.embedBatch` keeps synchronous secret/profile lookup
  on the caller thread; it no longer forces `Dispatchers.IO` from inside a
  repository transaction, avoiding the Android SQLite monitor/IO deadlock
  observed in the Round17 device fixture. The main agent must rerun the device
  API consent and wrong-dimension checks after this source-only fix.
- The new API embedding/reuse JVM counterexamples are source-level additions;
  module Gradle execution remains intentionally delegated to the main agent's
  serial build coordination.
- The API query UNKNOWN gate, one-time acknowledgement path, public rebuild
  consent guard, and deterministic BM25 lexical ordering are source-level
  additions in this window; Gradle and device verification remain delegated to
  the main agent.
- The v10 slow path now rechecks the operation manifest, live KB/document,
  binding, consent, cancellation, and active-generation pointer immediately
  before dispatch. Every exception after `DISPATCHED` is recorded as
  `UNKNOWN`; preparation failures remain local `FAILED`. The added JVM
  ordinary-failure injection covers the no-replay boundary. This remains
  source-only until the main agent's next serial build.
- Round11's data test exposed `retrievePinsGenerationForTheWholeRun` observing
  zero active-generation reads after the KB-space guard was folded into that
  query. The implementation now restores the original active-generation query
  boundary and performs the space check separately. Dynamic resolver,
  strict-binding parse, no-consent query, local-selection rejection, and
  durable rebind-unknown gate tests were added after that run; they still need
  the main agent's next serial test run.

## Required main-agent integration

1. Inject `OnnxTextEmbedder(AndroidModelPackLoader(appContext).load())` as the
   repository's production `embedder` and preserve a separately configured API
   adapter only for explicitly consented API imports.
2. Pass `AndroidPdfRendererAdapter(appContext)` as `pdfRasterizer`.
3. Register `ImportWorkerRegistry` handlers and add the scheduler calls to the
   foreground import/resume/cancel ViewModel path.
4. Inject `UsearchVectorIndexFactory` after installing NDK 27.3.13750724 and
   building both declared ABIs. Keep the local cosine implementation only as
   the explicit JVM/test path.
5. Run module builds serially, then perform the independent static/protocol
   review and device acceptance; this note is not a `STAGING_PASS`,
   `PRODUCTION_PASS`, or `DEPLOY_GO`.
6. Build one `ApiEmbeddingTextEmbedder` per selected provider/model binding and
   pass static instances through `apiEmbedder`/`apiEmbedders`; for providers
   configured after repository construction, pass the trailing
   `apiEmbedderResolver`. It receives only the persisted space id and must
   return an adapter with the exact id and parsed dimension, or null. App UI
   should call `createApiKnowledgeBase` only after displaying the complete
   binding/data-scope confirmation, and call `grantEmbeddingConsent`
   separately from Vision confirmation. A failed rebind carrying an
   `UNKNOWN_OUTCOME` leaves a durable gate; retry requires
   `acknowledgeDuplicateCharge = true`.

## Round21 main-agent verification

The final serialized Gradle command compiled and tested the knowledge,
serialization, data and Android integration modules as part of a successful
391-task debug build. On Android 16/API 36 x86_64:

- `round21-api-embedding.log`: 5/5 API embedding device tests passed in
  5.858 seconds, including explicit consent, binding/dimension rejection,
  durable UNKNOWN and query-vector reuse.
- `round21-knowledge-runtime.log`: 4/4 knowledge runtime tests passed in
  6.886 seconds.
- Schema v10 migration, idempotency and repository JVM tests passed in the
  same final build. Remote calls run outside long SQLite transactions;
  dispatch-time rechecks and UNKNOWN persistence remain enabled.

The 320-file load checkpoint is recorded separately in `knowledge-load.md`.
Its 20 READY text and 300 WAITING image result is a local-text/storage-waiting
component pass, not a full K06 pass. Real Vision, every-stage process and
storage fault injection, and the Android 12-16 foreground-service matrix remain
outside the executed evidence.
