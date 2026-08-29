<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Protocol adapters and runtime boundary evidence

## Scope

This work package covers the M1/M5/M7 shared boundaries only:

- `ModelRequest` keeps its existing constructor and adds typed parameter
  layers, per host header values (`Plain` or `SecretRef`), and an operation ID.
  `ParameterMerger` rejects reserved protocol fields and non JSON values before
  the final wire object is built.
- `OpenAiCompatibleAdapter` uses the same builder for the wire body and the
  opt in `previewRequest(ModelRequest)` inspector representation. Preview mode
  removes inline image bytes and never includes authorization or resolved
  secret header values. A live metadata probe is sent only with
  `ProbeConsent.GRANTED`; a profile only probe performs no network request.
  The source compatible `ModelRequest` now has an optional final
  `outputTokenLimit`. After all parameter layers are merged, the adapter
  validates `max_tokens` and `max_completion_tokens` as positive integers,
  rejects both fields together and any value above that limit, and injects
  `max_tokens` when a limit was supplied without an override. Invalid values
  fail before the HTTP request is prepared.
  Streaming text is redacted across delta boundaries for the primary
  credential and every resolved custom header secret. Possible credential
  prefixes remain buffered and are discarded on EOF, error or cancellation;
  only confirmed normal completion may flush the suffix. Complete JSON
  content and error text is redacted, while tool arguments containing a
  credential are rejected as `UNKNOWN_OUTCOME` before any call event is
  emitted.
  The adapter also implements bounded OpenAI compatible `/embeddings` POSTs.
  It sends only the exact model and input batch, uses the existing host bound
  secret header resolver without retries, and rejects malformed responses,
  duplicate or missing indexes, reordered results, unequal dimensions and
  non finite components before returning an `EmbeddingBatch`.
- `AgentRuntime` retains the old `Flow<ModelEvent>` API and adds
  `AgentRuntimeRequest`/`Flow<RuntimeEvent>`. Request summaries, tool calls,
  approval, results, lifecycle, parameter key names, and optional in memory
  previews are structured. `ToolResultProduced.resultJson` is the complete
  bounded redacted result while `resultSummary` remains a short Inspector
  field, so typed persistence does not replay a truncated JSON value. Tool
  specifications and calls are schema checked, including unknown and duplicate
  call IDs, before any executor invocation. Secrets and request bodies are not
  automatically persisted. A generic suspend `ToolExecutor` is available; the
  old blocking `ToolBroker` bridge uses `runInterruptible(Dispatchers.IO)`.
  A caller supplied `toolImages` hook can return policy checked visual evidence;
  the runtime places it in a following user multimodal message and fails closed
  on hook errors, timeout, or invalid image metadata.
- Prompt assembly marks runtime contract, user configuration, skill text, and
  retrieved evidence with explicit trust tags. Typed history can retain tool
  calls, tool results, and image references.
- MCP is pinned to Streamable HTTP revision `2025-06-18`. The adapter performs
  `initialize`, `notifications/initialized`, paginated `tools/list`, explicit
  namespaced grant freezing, `tools/call`, cancellation notifications, and
  unknown outcome mapping. A changed list/schema/order or list changed
  notification stales every grant. Requests are never automatically replayed
  and there is no stdio/process launcher.
- `RemoteSkillExecutor` implements the versioned project DTO boundary only.
  Strict validation covers protocol version, IDs, hashes, timestamps, limits,
  result/error shape, capability declarations, and cancellation acknowledgements.
  One invocation ID is accepted once; transport uncertainty becomes
  `UNKNOWN_OUTCOME`. There is no package upload, default server, secret field,
  or knowledge base field in the DTO.
- Android MCP settings are local-only until the user confirms discovery. The
  `McpViewModel` stores endpoint metadata, discovered schemas/hashes, revisions,
  and per-Agent grants in one non-secret `SharedPreferences` JSON value. A
  submitted password is immediately written under a new MCP-specific
  `AndroidSecretStore` reference bound to the canonical host; the UI never
  stores or displays the token or reference. Changing host requires a new
  password submission. `captureMcpSnapshot` records an immutable snapshot
  binding only for a newly created Agent snapshot; read-only
  `loadMcpSnapshot` and the `mcpTools(container, AgentSnapshot)` overload never
  create bindings for older sessions. `mcpTools` returns no tools for a
  missing, revoked, or stale binding. Each approved call reinitializes and
  rediscovers once, compares the stored fingerprint and schema hashes, and
  then calls only the frozen grant.
  Every call first returns `NeedsApproval`, including tools labeled read-only;
  no automatic reconnect, replay, stdio launch, or newly discovered grant is
  performed.

## Locked primary source

The MCP revision and transport semantics were checked against the official
specification:

- [MCP transports, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- [MCP lifecycle, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)
- [MCP tools, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)

The production transport is `KtorMcpStreamableHttpTransport`; tests inject an
in memory transport or Ktor `MockEngine`. No external endpoint is contacted by
the test design.

## Test command for coordinator

The coordinator should run the module scoped checks after all shared workers
are quiescent (no full repository Gradle run is claimed here):

```text
./gradlew :shared:provider-api:test :shared:agent-runtime:test :shared:skills-api:test --no-daemon
```

Added coverage includes parameter/header/probe/preview/output budget and
streaming credential redaction behavior, bounded embeddings with strict
response validation, MCP
initialize/discovery/grant invalidation/call/cancel and Ktor transport session
handling, runtime structured events, typed history, complete result persistence,
visual tool results, argument schema rejection, the blocking executor bridge,
and Remote DTO validation/single use/unknown cancellation behavior.

The Android MCP UI and wiring are intentionally reported as static additions in
this evidence file. The coordinator must compile the Android module separately;
this worker did not run Gradle, an emulator, a real endpoint, or production
operations. No real credential was read or entered.

## Boundary status

This document records implementation intent and the exact coordinator test
command. It does not claim that the command has passed until the coordinator
runs it against the merged working tree. Android device/emulator acceptance,
real provider billing behavior, remote executor installation verification, and
production deployment remain unverified. A caller must provide the local package
allow list and explicit MCP/remote endpoint grant; remote declarations are not
local authorization.
