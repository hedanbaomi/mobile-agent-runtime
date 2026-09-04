// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.AuditEvent
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.ipc.InvocationTicket
import runtime.mobileagent.ipc.PythonIpcProtocol
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.HeaderSecretResolver
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ParameterLayers
import runtime.mobileagent.provider.RequestHeaderValue
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.provider.openai.OpenAiAdapterFactory
import runtime.mobileagent.python.IsolatedPythonRuntime
import runtime.mobileagent.python.PythonCapabilityBroker
import runtime.mobileagent.python.PythonExecutionRequest
import runtime.mobileagent.python.PythonPackageSource
import runtime.mobileagent.skills.*
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The host must reserve from the same counters used by the outer AgentRuntime loop. */
interface PythonRunBudget {
    /** Atomically consume one tool call, returning false when the shared Run budget is exhausted. */
    fun reserveBrokerCall(): Boolean

    /** Atomically consume a model round and a conservative maximum token reservation. No refund/retry. */
    fun reserveModelCall(maxTokens: Int): Boolean
}

/** Run-local discovery and execution. No interpreter is loaded into the application process. */
fun pythonSkillTools(
    container: AppContainer,
    context: Context,
    snapshot: AgentSnapshot,
    runId: String,
    budget: PythonRunBudget? = null,
): ToolExecutor = PythonSkillToolExecutor(container, context.applicationContext, snapshot, runId, budget)

private class PythonSkillToolExecutor(
    private val container: AppContainer,
    private val context: Context,
    private val snapshot: AgentSnapshot,
    private val runId: String,
    private val budget: PythonRunBudget?,
) : ToolExecutor {
    private val mutex = Mutex()
    private val calls = ConcurrentHashMap<String, BoundPythonCall>()
    @Volatile private var runOutcomeUnknown = false
    private var reservedModelTokens = 0
    private val entries = discover()
    override val specs: List<ToolSpec> = entries.values.map { it.spec }

    private data class Entry(
        val installId: String,
        val packageHash: String,
        val grant: PermissionGrant,
        val manifest: SkillManifest,
        val raw: JsonObject,
        val inputSchema: JsonObject,
        val outputSchema: JsonObject,
        val spec: ToolSpec,
        val limits: PythonIpcProtocol.PythonLimits,
        val legacyPrograms: Set<String>,
    )

    private data class BoundPythonCall(
        val call: ToolCall,
        val entry: Entry,
        val grant: PermissionGrant,
        val ticket: InvocationTicket,
        val knowledgeIds: Set<String>,
        @Volatile var approved: Boolean = false,
        var started: Boolean = false,
        @Volatile var active: Boolean = false,
        @Volatile var unknownExternalOutcome: Boolean = false,
        @Volatile var sideEffectDispatched: Boolean = false,
        @Volatile var dispatchAttempted: Boolean = false,
        var terminal: Boolean = false,
        var brokerCalls: Int = 0,
        var modelCalls: Int = 0,
        var reservedModelTokens: Int = 0,
        var logBytes: Int = 0,
        var artifactBytes: Int = 0,
        val brokerRequestIds: MutableSet<String> = mutableSetOf(),
        val artifacts: MutableMap<String, File> = mutableMapOf(),
    )

    private fun discover(): Map<String, Entry> = buildMap {
        val current = container.agents.get(snapshot.agentId) ?: return@buildMap
        (snapshot.skillIds.toSet() intersect current.skillIds.toSet()).sorted().forEach { id ->
            // An invalid/missing/unsupported package contributes no executable tool.
            val entry = runCatching {
                val installed = container.skills.get(id) ?: return@runCatching null
                if (!installed.enabled || installed.classification != CompatibilityClass.B) return@runCatching null
                val inspected = container.skills.inspect(id)
                val manifest = inspected.manifest ?: return@runCatching null
                if (inspected.classification != CompatibilityClass.B || inspected.packageHash != installed.packageHash ||
                    manifest.runtimeKind != "python" || !PythonIpcProtocol.validateEntrypoint(manifest.entrypoint)) return@runCatching null
                val grant = currentGrants(id, installed.packageHash).firstOrNull() ?: return@runCatching null
                val raw = Json.parseToJsonElement(inspected.rawManifestJson ?: return@runCatching null) as? JsonObject
                    ?: return@runCatching null
                val input = raw["inputSchema"] as? JsonObject ?: return@runCatching null
                val output = raw["outputSchema"] as? JsonObject ?: return@runCatching null
                if (!PythonJsonSchema.supported(input) || !PythonJsonSchema.supported(output) || input.string("type") != "object") {
                    return@runCatching null
                }
                val declaredLimits = raw["limits"] as? JsonObject
                val legacyPrograms = (raw["legacyPrograms"] as? JsonArray)?.mapNotNull { value ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                }?.toSet().orEmpty()
                if (manifest.entrypoint == LEGACY_CLAUDE_ENTRYPOINT && legacyPrograms.isEmpty()) return@runCatching null
                val limits = PythonIpcProtocol.PythonLimits(
                    timeoutMs = ((declaredLimits?.number("timeoutSeconds") ?: 30).coerceIn(1, 30) * 1000),
                    maxOutputBytes = (declaredLimits?.number("maxOutputKiB") ?: 1024).coerceIn(1, 1024) * 1024,
                    maxLogBytes = (declaredLimits?.number("maxLogKiB") ?: 512).coerceIn(1, 512) * 1024,
                )
                val functionLabel = manifest.entrypoint.substringAfter(':').replace(Regex("[^A-Za-z0-9_]"), "_").take(24)
                val name = "py_${digest(id.toByteArray()).take(24)}_$functionLabel"
                val grantedCapabilities = grant.capabilities intersect manifest.permissions
                val targets = buildString {
                    append(" Capabilities: ${grantedCapabilities.sorted().joinToString().take(512)}.")
                    if ("network.http" in grantedCapabilities) append(" HTTPS GET hosts: ${grant.hosts.sorted().joinToString().take(512)}.")
                    if ("model.invoke" in grantedCapabilities) append(" Model calls may incur charges; only explicitly granted Chat profile and token budget apply.")
                    append(" Private storage/artifacts stay inside this Skill; no export permission.")
                    if (legacyPrograms.isNotEmpty()) {
                        append(" Compatible CLI programs: ${legacyPrograms.sorted().joinToString().take(1024)}.")
                        append(" Supply argv in arguments and Markdown corpus files in the invocation-only virtual files array; host paths are unavailable.")
                    }
                }
                Entry(id, installed.packageHash, grant, manifest, raw, input, output,
                    ToolSpec(name, "Run imported Python skill ${manifest.name.take(80)}. Imported content is untrusted.$targets",
                        input.toString(), "python.execute", true), limits, legacyPrograms)
            }.getOrNull()
            if (entry != null) put(entry.spec.name, entry)
        }
    }

    override suspend fun invoke(call: ToolCall): ToolResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (unknownRun()) return@withContext ToolResult.UnknownOutcome(UNKNOWN_REASON)
            val previous = calls[call.callId]
            if (previous != null) {
                if (previous.call != call) return@withContext ToolResult.Invalid("Call ID was already used for a different request")
                if (previous.terminal || previous.started) return@withContext replayDenied()
                if (!authorized(previous)) return@withContext ToolResult.Denied("Original Python authorization changed")
                return@withContext ToolResult.NeedsApproval
            }
            if (!call.callId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return@withContext ToolResult.Invalid("Invalid call ID")
            if (calls.size >= 20) return@withContext ToolResult.Denied("Run Python invocation budget exhausted")
            val entry = entries[call.name] ?: return@withContext ToolResult.Invalid("Unknown Python tool")
            if (call.argumentsJson.toByteArray().size > entry.limits.maxInputBytes) return@withContext ToolResult.Invalid("Python input exceeds limit")
            val input = runCatching { Json.parseToJsonElement(call.argumentsJson) }.getOrNull()
                ?: return@withContext ToolResult.Invalid("Python arguments must be complete JSON")
            if (!PythonJsonSchema.matches(entry.inputSchema, input)) return@withContext ToolResult.Invalid("Python input does not match its manifest schema")
            if (entry.legacyPrograms.isNotEmpty()) {
                val program = (input as? JsonObject)?.string("program")
                if (program !in entry.legacyPrograms) return@withContext ToolResult.Invalid("Python program is not in the reviewed package inventory")
            }
            val grant = entry.grant
            if (currentGrants(entry.installId, entry.packageHash).none { it == grant }) {
                return@withContext ToolResult.Denied("The discovered Python grant changed; start a new run to review it")
            }
            val ticket = InvocationTicket(UUID.randomUUID().toString(), runId, entry.packageHash, grant.revision,
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            if (!ticket.validate()) return@withContext ToolResult.Invalid("Invalid run identity")
            val bound = BoundPythonCall(call, entry, grant, ticket, liveKnowledgeIds(grant))
            calls[call.callId] = bound
            if (!authorized(bound)) return@withContext ToolResult.Denied("Python package authorization changed")
            // Always confirm a script, including storage-only scripts. The call, grant and hash
            // remain frozen while the UI displays the exact invocation for approval.
            audit(bound, "approval", "WAITING_TOOL_APPROVAL")
            ToolResult.NeedsApproval
        }
    }

    override suspend fun approve(callId: String): ToolResult = try { mutex.withLock {
        withContext(Dispatchers.IO) {
            if (unknownRun()) return@withContext ToolResult.UnknownOutcome(UNKNOWN_REASON)
            val bound = calls[callId] ?: return@withContext ToolResult.Invalid("No pending Python invocation")
            if (bound.started || bound.terminal) return@withContext replayDenied()
            if (!authorized(bound)) {
                bound.terminal = true
                return@withContext ToolResult.Denied("Original Python authorization changed before approval")
            }
            bound.approved = true
            bound.started = true
            bound.active = true
            try {
                val verified = container.skills.inspect(bound.entry.installId)
                if (verified.packageHash != bound.ticket.packageHash || !authorized(bound)) {
                    return@withContext ToolResult.Denied("Python package changed before execution")
                }
                val executionInput = if (bound.entry.legacyPrograms.isEmpty()) {
                    bound.call.argumentsJson
                } else {
                    legacyExecutionInput(bound.call.argumentsJson, verified, bound.entry.legacyPrograms)
                        ?: return@withContext ToolResult.Invalid("Reviewed Python program source is unavailable")
                }
                if (executionInput.toByteArray(Charsets.UTF_8).size > bound.entry.limits.maxInputBytes) {
                    return@withContext ToolResult.Invalid("Python input and reviewed program source exceed the isolated runtime limit")
                }
                audit(bound, "invoke", "STARTED")
                val result = IsolatedPythonRuntime(context, InvocationBroker(bound)).execute(PythonExecutionRequest(
                    ticket = bound.ticket,
                    entrypoint = bound.entry.manifest.entrypoint,
                    inputJson = executionInput,
                    packageSource = PythonPackageSource.Bytes(checkNotNull(verified.packageBytes)),
                    limits = bound.entry.limits.copy(timeoutMs = minOf(bound.entry.limits.timeoutMs, remainingRunMillis())),
                    onDispatched = { bound.dispatchAttempted = true },
                ))
                if (result.dispatchAccepted) bound.dispatchAttempted = true
                if (bound.unknownExternalOutcome || result.status == PythonIpcProtocol.RESULT_UNKNOWN ||
                    (bound.dispatchAttempted && result.status in setOf(PythonIpcProtocol.RESULT_CANCELLED, PythonIpcProtocol.RESULT_TIMED_OUT)) ||
                    (bound.sideEffectDispatched && result.status != PythonIpcProtocol.RESULT_SUCCEEDED)) {
                    return@withContext markUnknown(bound, "PYTHON_RESULT_UNCERTAIN")
                }
                if (!authorized(bound)) {
                    if (bound.sideEffectDispatched) return@withContext markUnknown(bound, "AUTHORIZATION_CHANGED_AFTER_DISPATCH")
                    audit(bound, "invoke", "DENIED", "PERMISSION_DENIED")
                    return@withContext ToolResult.Denied("Python authorization changed during execution")
                }
                if (result.status == PythonIpcProtocol.RESULT_SUCCEEDED) {
                    val raw = result.valueJson ?: return@withContext markUnknown(bound, "INVALID_OUTPUT")
                    if (raw.toByteArray().size > bound.entry.limits.maxOutputBytes) return@withContext markUnknown(bound, "OUTPUT_LIMIT")
                    val value = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                        ?: return@withContext markUnknown(bound, "INVALID_OUTPUT")
                    if (!PythonJsonSchema.matches(bound.entry.outputSchema, value)) return@withContext markUnknown(bound, "OUTPUT_SCHEMA_MISMATCH")
                    audit(bound, "invoke", result.status)
                    ToolResult.Value(raw)
                } else {
                    audit(bound, "invoke", result.status)
                    // Do not forward interpreter exception messages, paths or raw output to audit/UI.
                    when (result.status) {
                        PythonIpcProtocol.RESULT_CANCELLED -> ToolResult.Denied("Python invocation cancelled before dispatch")
                        PythonIpcProtocol.RESULT_TIMED_OUT -> ToolResult.Denied("Python invocation timed out before dispatch")
                        else -> ToolResult.Invalid("Python execution failed; this call cannot be replayed")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (bound.dispatchAttempted || bound.sideEffectDispatched || bound.unknownExternalOutcome) {
                    markUnknown(bound, "PYTHON_EXECUTION_UNCERTAIN")
                } else {
                    audit(bound, "invoke", "FAILED", "FAILED_BEFORE_DISPATCH")
                    ToolResult.Invalid("Python preparation failed before dispatch; this call cannot be replayed")
                }
            } finally {
                bound.active = false
                bound.terminal = true
            }
        }
    } } catch (cancelled: CancellationException) {
        // This outer handler also covers cancellation while switching to/from Dispatchers.IO.
        calls[callId]?.let { bound ->
            if (bound.dispatchAttempted || bound.sideEffectDispatched || bound.unknownExternalOutcome) {
                markUnknown(bound, "CANCELLED_AFTER_DISPATCH")
            } else {
                markKnownCancellation(bound)
            }
        }
        throw cancelled
    }

    private fun currentGrants(installId: String, hash: String): List<PermissionGrant> =
        container.skills.grantsFor(installId).filter {
            !it.revoked && it.grantId.isNotBlank() && it.installId == installId && it.packageHash == hash && it.revision > 0
        }.sortedBy { it.grantId }.map {
            it.copy(capabilities = it.capabilities.toSet(), knowledgeBaseIds = it.knowledgeBaseIds.toSet(),
                hosts = it.hosts.toSet(), methods = it.methods.toSet())
        }

    private fun remainingRunMillis(): Int {
        val run = container.runs.get(runId) ?: throw BrokerDenied("RESOURCE_LIMIT")
        val max = (objectOrNull(run.budgetJson)?.number("maxRuntimeMs") ?: 180_000).coerceIn(1, 180_000)
        val elapsed = Instant.now().toEpochMilli() - Instant.parse(run.startedAt ?: run.createdAt).toEpochMilli()
        val remaining = max.toLong() - elapsed
        if (remaining <= 0) throw BrokerDenied("RESOURCE_LIMIT")
        return remaining.coerceAtMost(30_000).toInt()
    }

    private fun authorized(bound: BoundPythonCall): Boolean = runCatching {
        val run = container.runs.get(runId) ?: return@runCatching false
        if (run.snapshotId != snapshot.id || run.state.name in setOf("COMPLETED", "CANCELLED", "FAILED", "BUDGET_EXHAUSTED", "UNKNOWN_OUTCOME")) {
            return@runCatching false
        }
        val maxRuntime = (objectOrNull(run.budgetJson)?.number("maxRuntimeMs") ?: 180_000).coerceIn(1, 180_000)
        if (Instant.now().toEpochMilli() - Instant.parse(run.startedAt ?: run.createdAt).toEpochMilli() > maxRuntime) return@runCatching false
        val agent = container.agents.get(snapshot.agentId) ?: return@runCatching false
        if (bound.entry.installId !in snapshot.skillIds || bound.entry.installId !in agent.skillIds) return@runCatching false
        if (!liveKnowledgeIds(bound.grant).containsAll(bound.knowledgeIds)) return@runCatching false
        val installed = container.skills.get(bound.entry.installId) ?: return@runCatching false
        if (!installed.enabled || installed.classification != CompatibilityClass.B || installed.packageHash != bound.entry.packageHash) return@runCatching false
        val current = container.skills.grantsFor(installed.installId).singleOrNull { it.grantId == bound.grant.grantId }
            ?: return@runCatching false
        if (current != bound.grant || current.revoked) return@runCatching false
        val scopes = objectOrNull(current.scopesJson) ?: return@runCatching false
        if ("expiresAt" in scopes) {
            val expiry = scopes.string("expiresAt") ?: return@runCatching false
            if (!Instant.parse(expiry).isAfter(Instant.now())) return@runCatching false
        }
        val inspection = container.skills.inspect(installed.installId)
        inspection.classification == CompatibilityClass.B && inspection.packageHash == bound.entry.packageHash &&
            inspection.manifest == bound.entry.manifest && inspection.rawManifestJson?.let { Json.parseToJsonElement(it) } == bound.entry.raw
    }.getOrDefault(false)

    private fun liveKnowledgeIds(grant: PermissionGrant): Set<String> = grant.knowledgeBaseIds intersect
        snapshot.knowledgeBaseIds.toSet() intersect container.agents.get(snapshot.agentId)?.knowledgeBaseIds.orEmpty().toSet() intersect
        container.knowledge.listKnowledgeBases().map { it.first }.toSet()

    private fun legacyExecutionInput(
        argumentsJson: String,
        inspection: SkillInspection,
        allowedPrograms: Set<String>,
    ): String? {
        val input = objectOrNull(argumentsJson) ?: return null
        val program = input.string("program")?.takeIf { it in allowedPrograms } ?: return null
        val source = inspection.legacyProgramSources[program] ?: return null
        return JsonObject(input + (LEGACY_SOURCE_FIELD to JsonPrimitive(source))).toString()
    }

    private inner class InvocationBroker(private val bound: BoundPythonCall) : PythonCapabilityBroker {
        private val brokerMutex = Mutex()
        private var requestEffectDispatched = false

        private fun effectDispatched() {
            requestEffectDispatched = true
            bound.sideEffectDispatched = true
        }

        override suspend fun authorize(ticket: InvocationTicket): Boolean = withContext(Dispatchers.IO) {
            bound.active && bound.approved && !bound.unknownExternalOutcome && ticket == bound.ticket && authorized(bound)
        }

        override suspend fun invoke(request: PythonIpcProtocol.BrokerRequest): PythonIpcProtocol.BrokerResponse = brokerMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!authorize(request.ticket)) return@withContext denied(request, "PERMISSION_DENIED")
                if (!bound.brokerRequestIds.add(request.requestId)) return@withContext denied(request, "REPLAY_DENIED")
                if (++bound.brokerCalls > bound.entry.limits.maxBrokerCalls || budget?.reserveBrokerCall() != true) {
                    return@withContext denied(request, "RESOURCE_LIMIT")
                }
                if (request.argumentsJson.toByteArray().size > PythonIpcProtocol.MAX_BROKER_ARGUMENT_BYTES) {
                    return@withContext denied(request, "RESOURCE_LIMIT")
                }
                val args = objectOrNull(request.argumentsJson) ?: return@withContext denied(request, "INVALID_ARGUMENTS")
                val permission = permissionFor(request.capability)
                    ?: return@withContext denied(request, "UNSUPPORTED_CAPABILITY")
                if (permission !in bound.grant.capabilities || permission !in bound.entry.manifest.permissions) {
                    return@withContext denied(request, "PERMISSION_DENIED")
                }
                requestEffectDispatched = false
                try {
                    declaration(permission)
                    audit(bound, "broker", "STARTED", capability = request.capability)
                    val value = when (request.capability) {
                        "knowledge.search" -> search(args, permission)
                        "knowledge.read", "document.read" -> readDocument(args, permission)
                        "http.request", "network.http" -> http(args, permission)
                        "model.invoke" -> model(args, permission)
                        "storage.get" -> storage(args, permission, false)
                        "storage.put" -> storage(args, permission, true)
                        "files.readHandle", "files.read_handle" -> readHandle(args)
                        "files.writeArtifact", "files.write_artifact" -> artifact(args)
                        "log.info" -> log(args)
                        else -> throw BrokerDenied("UNSUPPORTED_CAPABILITY")
                    }
                    if (!authorize(request.ticket)) return@withContext denied(request,
                        if (requestEffectDispatched) "UNKNOWN_OUTCOME" else "PERMISSION_DENIED")
                    val serialized = value.toString()
                    if (serialized.toByteArray().size > PythonIpcProtocol.MAX_BROKER_ARGUMENT_BYTES) throw BrokerDenied("RESOURCE_LIMIT")
                    audit(bound, "broker", "OK", capability = request.capability)
                    PythonIpcProtocol.BrokerResponse(request.requestId, "OK", serialized)
                } catch (cancelled: CancellationException) {
                    if (requestEffectDispatched) markUnknown(bound, "BROKER_CANCELLED_AFTER_DISPATCH")
                    throw cancelled
                } catch (unknown: runtime.mobileagent.knowledge.ApiQueryUnknownOutcomeException) {
                    markUnknown(bound, "API_EMBEDDING_QUERY_UNKNOWN")
                    denied(request, "UNKNOWN_OUTCOME")
                } catch (failure: BrokerDenied) {
                    denied(request, if (requestEffectDispatched) "UNKNOWN_OUTCOME" else failure.code)
                } catch (_: Exception) {
                    if (requestEffectDispatched) return@withContext denied(request, "UNKNOWN_OUTCOME")
                    audit(bound, "broker", "ERROR", "CAPABILITY_FAILED", request.capability)
                    PythonIpcProtocol.BrokerResponse(request.requestId, "ERROR", errorCode = "CAPABILITY_FAILED",
                        errorMessage = "Capability failed; do not automatically replay this request")
                }
            }
        }

        private fun permissionFor(capability: String): String? {
            val candidates = when (capability) {
                "knowledge.search" -> listOf("knowledge.search")
                "knowledge.read", "document.read" -> listOf("knowledge.read", "document.read")
                "http.request", "network.http" -> listOf("network.http")
                "model.invoke" -> listOf("model.invoke")
                "storage.get", "storage.put" -> listOf("storage.skill")
                "files.readHandle", "files.read_handle" -> listOf("files.read_handle")
                "files.writeArtifact", "files.write_artifact" -> listOf("files.write_artifact")
                "log.info" -> listOf("log.info")
                else -> emptyList()
            }
            return candidates.firstOrNull { it in bound.grant.capabilities && it in bound.entry.manifest.permissions }
        }

        private fun declaration(permission: String): JsonObject =
            ((bound.entry.raw["permissions"] as? JsonObject)?.get(permission) as? JsonObject
                ?: throw BrokerDenied("UNSUPPORTED_RESOURCE_SCOPE")).also { value ->
                val supported = when (permission) {
                    "knowledge.search", "knowledge.read", "document.read" -> setOf("scope", "knowledgeBaseIds")
                    "network.http" -> setOf("hosts", "methods")
                    "model.invoke" -> setOf("modelProfileIds", "maxModelCalls", "maxModelTokens")
                    "storage.skill" -> setOf("quotaMiB")
                    else -> emptySet()
                }
                // A qualifier which this adapter cannot enforce may narrow the declaration.
                // Refuse the capability instead of silently ignoring that qualifier.
                if (value.keys.any { it !in supported } || ("scope" in value && value.string("scope") != "selected-by-user")) {
                    throw BrokerDenied("UNSUPPORTED_RESOURCE_SCOPE")
                }
                for (key in setOf("knowledgeBaseIds", "hosts", "methods", "modelProfileIds")) {
                    val list = value[key] ?: continue
                    if (list !is JsonArray || list.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() }) {
                        throw BrokerDenied("UNSUPPORTED_RESOURCE_SCOPE")
                    }
                }
                for (key in setOf("maxModelCalls", "maxModelTokens", "quotaMiB")) {
                    if (key in value && (value.number(key) ?: 0) <= 0) throw BrokerDenied("UNSUPPORTED_RESOURCE_SCOPE")
                }
            }

        private fun permittedKnowledge(permission: String): Set<String> {
            val declared = declaration(permission).strings("knowledgeBaseIds")
            val live = bound.knowledgeIds intersect liveKnowledgeIds(bound.grant)
            return if (declared.isEmpty()) live else live intersect declared
        }

        private fun search(args: JsonObject, permission: String): JsonElement {
            val query = args.requiredString("query", 4096)
            val ids = permittedKnowledge(permission)
            if (ids.isEmpty()) throw BrokerDenied("PERMISSION_DENIED")
            val requestedIds = args.strings("knowledgeBaseIds")
            if (!ids.containsAll(requestedIds)) throw BrokerDenied("PERMISSION_DENIED")
            val selected = requestedIds.ifEmpty { ids }
            val limit = (args.number("limit") ?: args.number("topK") ?: 8).coerceIn(1, 10)
            val hits = container.knowledge.search(query, limit, selected.toList())
            return buildJsonObject { put("hits", buildJsonArray {
                hits.filter { it.knowledgeBaseId in selected && container.knowledge.documentKnowledgeBaseId(it.documentId) == it.knowledgeBaseId }
                    .forEach { hit -> add(buildJsonObject {
                        put("knowledgeBaseId", hit.knowledgeBaseId); put("documentId", hit.documentId)
                        put("chunkId", hit.chunkId); put("text", utf8Prefix(hit.text, 2048))
                    }) }
            }) }
        }

        private fun readDocument(args: JsonObject, permission: String): JsonElement {
            val id = args.requiredString("documentId", 256)
            val ids = permittedKnowledge(permission)
            if (container.knowledge.documentKnowledgeBaseId(id) !in ids) throw BrokerDenied("PERMISSION_DENIED")
            val max = (args.number("maxBytes") ?: 16_384).coerceIn(1, 24_000)
            val text = container.knowledge.readDocumentText(id, max, ids)
            if (container.knowledge.documentKnowledgeBaseId(id) !in permittedKnowledge(permission)) throw BrokerDenied("PERMISSION_DENIED")
            return buildJsonObject { put("documentId", id); put("text", utf8Prefix(text, max)) }
        }

        private suspend fun http(args: JsonObject, permission: String): JsonElement {
            val method = args.string("method")?.uppercase() ?: "GET"
            val declaration = declaration(permission)
            val hosts = bound.grant.hosts.map { it.lowercase().trim('.') }.toSet() intersect
                declaration.strings("hosts").map { it.lowercase().trim('.') }.toSet()
            val methods = bound.grant.methods.map { it.uppercase() }.toSet() intersect
                declaration.strings("methods").map { it.uppercase() }.toSet().ifEmpty { setOf("GET") }
            if (method != "GET" || method !in methods || hosts.isEmpty() ||
                ("method" in args && args.string("method") == null) ||
                ("headers" in args && args["headers"] !is JsonObject) ||
                (args["headers"] as? JsonObject)?.isNotEmpty() == true || args["body"]?.let { it != JsonNull } == true) {
                throw BrokerDenied("PERMISSION_DENIED")
            }
            val url = args.requiredString("url", 4096)
            HttpPolicy.assertRequest(url, hosts)
            effectDispatched()
            val response = runInterruptible(Dispatchers.IO) {
                HostHttp.get(url, hosts) { host ->
                    if (!authorized(bound)) throw BrokerDenied("PERMISSION_DENIED")
                    InetAddress.getAllByName(host).toList().also {
                        if (!authorized(bound)) throw BrokerDenied("PERMISSION_DENIED")
                    }
                }
            }
            // The transport is itself bounded; the narrower IPC frame has a separate limit.
            if (response.toByteArray().size > 24_000) throw BrokerDenied("RESOURCE_LIMIT")
            return buildJsonObject { put("body", response) }
        }

        private suspend fun model(args: JsonObject, permission: String): JsonElement {
            val scopes = objectOrNull(bound.grant.scopesJson) ?: throw BrokerDenied("PERMISSION_DENIED")
            val declared = declaration(permission)
            if (snapshot.chatModelId !in (scopes.strings("modelProfileIds") intersect declared.strings("modelProfileIds")) ||
                container.agents.get(snapshot.agentId)?.chatProfileId != snapshot.chatModelId) throw BrokerDenied("PERMISSION_DENIED")
            val binding = container.agents.resolveSnapshot(snapshot.id)
            if (binding.snapshot != snapshot || args.requiredString("provider", 128) != binding.provider.id) throw BrokerDenied("PERMISSION_DENIED")
            val payload = args["request"] as? JsonObject ?: throw BrokerDenied("INVALID_ARGUMENTS")
            if (payload.keys.any { it !in setOf("prompt", "maxOutputTokens") }) throw BrokerDenied("INVALID_ARGUMENTS")
            val prompt = payload.requiredString("prompt", 8192)
            val outputLimit = (payload.number("maxOutputTokens") ?: 512).coerceIn(1, minOf(2048, binding.chatModel.outputLimit.coerceAtLeast(1)))
            val maxCalls = minOf(scopes.number("maxModelCalls") ?: 0, declared.number("maxModelCalls") ?: 0, 3)
            val maxTokens = minOf(scopes.number("maxModelTokens") ?: 0, declared.number("maxModelTokens") ?: 0)
            val run = container.runs.get(runId) ?: throw BrokerDenied("RESOURCE_LIMIT")
            val runMaxTokens = objectOrNull(run.budgetJson)?.number("maxModelTokens") ?: throw BrokerDenied("RESOURCE_LIMIT")
            // UTF-8 bytes form a conservative input allowance; reserve before a possibly billable send.
            val reserved = prompt.toByteArray().size + outputLimit + 256
            if (++bound.modelCalls > maxCalls || bound.reservedModelTokens.toLong() + reserved > maxTokens ||
                run.inputTokens.toLong() + run.outputTokens + reservedModelTokens + reserved > runMaxTokens) throw BrokerDenied("RESOURCE_LIMIT")
            if (budget?.reserveModelCall(reserved) != true) throw BrokerDenied("RESOURCE_LIMIT")
            reservedModelTokens += reserved
            bound.reservedModelTokens += reserved
            val secrets = mutableListOf<CharArray>()
            try {
                val provider = binding.provider
                val secret = container.secrets.resolveForHost(provider.secretRef).also { secrets += it }
                if (!authorized(bound)) throw BrokerDenied("PERMISSION_DENIED")
                val adapter = OpenAiAdapterFactory.create(provider.apiFormat, container.http, provider.baseUrl,
                    HeaderSecretResolver { host, ref ->
                        if (host != URI(provider.baseUrl).host || !authorized(bound)) throw BrokerDenied("PERMISSION_DENIED")
                        container.secrets.resolveForHost(ref).also { secrets += it }
                    }, provider.nonSecretHeaders.mapValues { RequestHeaderValue.Plain(it.value) } +
                        provider.headerSecretRefs.mapValues { RequestHeaderValue.SecretRef(it.value) })
                val text = StringBuilder()
                var completed = false
                effectDispatched()
                adapter.stream(ModelRequest(binding.chatModel.modelId, listOf(ChatMessage("user", prompt)),
                    parameters = ParameterLayers(adapterDefaults = mapOf("max_tokens" to JsonPrimitive(outputLimit))),
                    operationId = bound.ticket.invocationId), secret).collect { event ->
                    if (!authorized(bound)) throw BrokerDenied("PERMISSION_DENIED")
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            text.append(event.text)
                            if (text.length > 16_000 || text.toString().toByteArray().size > 24_000) throw BrokerDenied("RESOURCE_LIMIT")
                        }
                        is ModelEvent.ReasoningDelta -> Unit
                        is ModelEvent.ProviderContinuation -> Unit
                        is ModelEvent.Usage -> if (event.inputTokens.toLong() + event.outputTokens > reserved) throw BrokerDenied("RESOURCE_LIMIT")
                        ModelEvent.Completed -> completed = true
                        is ModelEvent.RefusalDelta,
                        is ModelEvent.Failed, is ModelEvent.ToolCallDelta, is ModelEvent.ToolApprovalRequired -> throw BrokerDenied("UNKNOWN_OUTCOME")
                    }
                }
                if (!completed) throw BrokerDenied("UNKNOWN_OUTCOME")
                val redacted = SecretRedactor.redact(text.toString(), secrets.map { it.concatToString() })
                return buildJsonObject { put("text", redacted); put("reservedTokens", reserved) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BrokerDenied) {
                throw failure
            } catch (_: Exception) {
                // Failure before send is known; transport/stream failures after send are uncertain.
                throw BrokerDenied(if (requestEffectDispatched) "UNKNOWN_OUTCOME" else "CAPABILITY_FAILED")
            } finally { secrets.forEach { it.fill('\u0000') } }
        }

        private fun storage(args: JsonObject, permission: String, write: Boolean): JsonElement {
            val key = args.requiredString("key", 256)
            val quota = (declaration(permission).number("quotaMiB") ?: 32).coerceIn(1, 32) * 1024L * 1024
            // Hash both namespace and key; imported names never become filesystem paths.
            val root = childDirectory(context.filesDir, "python-skill-kv/${digest(bound.entry.installId.toByteArray())}")
            val file = File(root, "${digest(key.toByteArray())}.json")
            return synchronized(STORAGE_LOCK) {
                if (!authorized(bound)) throw BrokerDenied("PERMISSION_DENIED")
                if (!write) {
                    if (!file.exists()) JsonNull else {
                        if (file.length() > 24_000) throw BrokerDenied("RESOURCE_LIMIT")
                        Json.parseToJsonElement(AtomicFile(file).readFully().toString(Charsets.UTF_8))
                    }
                } else {
                    val value = args["value"] ?: throw BrokerDenied("INVALID_ARGUMENTS")
                    val bytes = value.toString().toByteArray()
                    val files = root.listFiles().orEmpty()
                    val used = files.sumOf { it.length() }
                    if (bytes.size > 24_000 || used - file.length() + bytes.size > quota ||
                        (!file.exists() && files.size >= 4096)) throw BrokerDenied("RESOURCE_LIMIT")
                    effectDispatched()
                    writeAtomic(file, bytes)
                    buildJsonObject { put("stored", true); put("bytes", bytes.size) }
                }
            }
        }

        private fun artifact(args: JsonObject): JsonElement {
            val name = args.requiredString("name", 128)
            if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) || name.contains("..")) throw BrokerDenied("INVALID_ARGUMENTS")
            val bytes = (args["content"] ?: throw BrokerDenied("INVALID_ARGUMENTS")).toString().toByteArray()
            if (bytes.size > 24_000 || bound.artifactBytes + bytes.size > 1024 * 1024) throw BrokerDenied("RESOURCE_LIMIT")
            return synchronized(STORAGE_LOCK) {
                val allArtifacts = childDirectory(context.cacheDir, "python-artifacts")
                val existing = allArtifacts.walkTopDown().filter { it.isFile }.take(4097).toList()
                if (existing.size >= 4096 || existing.sumOf { it.length() } + bytes.size > 32L * 1024 * 1024) throw BrokerDenied("RESOURCE_LIMIT")
                val root = childDirectory(context.cacheDir, "python-artifacts/${bound.ticket.invocationId}")
                val handle = UUID.randomUUID().toString()
                val file = File(root, "$handle.json")
                if (!authorized(bound)) throw BrokerDenied("PERMISSION_DENIED")
                effectDispatched()
                writeAtomic(file, bytes)
                bound.artifactBytes += bytes.size
                bound.artifacts[handle] = file
                buildJsonObject { put("handle", handle); put("name", name); put("size", bytes.size)
                    put("sha256", digest(bytes)); put("mediaType", "application/json"); put("exported", false) }
            }
        }

        private fun readHandle(args: JsonObject): JsonElement {
            val handle = args.requiredString("handle", 256)
            // No SAF handle registry is wired here. Only opaque artifacts from this invocation exist.
            val file = bound.artifacts[handle] ?: throw BrokerDenied("PERMISSION_DENIED")
            val root = File(context.cacheDir, "python-artifacts/${bound.ticket.invocationId}").canonicalFile
            val max = (args.number("maxBytes") ?: 24_000).coerceIn(1, 24_000)
            if (file.canonicalFile.parentFile != root || file.length() > max) throw BrokerDenied("RESOURCE_LIMIT")
            return Json.parseToJsonElement(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        }

        private fun log(args: JsonObject): JsonElement {
            val message = args.requiredString("message", 4096)
            bound.logBytes += message.toByteArray().size
            if (bound.logBytes > bound.entry.limits.maxLogBytes) throw BrokerDenied("RESOURCE_LIMIT")
            // Do not persist even a regex-redacted arbitrary message: it may contain unknown secrets.
            // Structured log audit records retain only severity and byte count.
            audit(bound, "log", "INFO", inputBytes = message.toByteArray().size.toLong())
            return buildJsonObject { put("recorded", true); put("messageStored", false) }
        }

        private suspend fun denied(request: PythonIpcProtocol.BrokerRequest, code: String): PythonIpcProtocol.BrokerResponse {
            if (code == "UNKNOWN_OUTCOME") markUnknown(bound, code)
            audit(bound, "broker", "DENIED", code)
            return PythonIpcProtocol.BrokerResponse(request.requestId, "DENIED", errorCode = code,
                errorMessage = "Capability unavailable under this invocation's authorization and budget")
        }
    }

    private fun audit(bound: BoundPythonCall, action: String, result: String, code: String? = null,
                      capability: String? = null, inputBytes: Long = 0) {
        container.audits.append(AuditEvent(UUID.randomUUID().toString(), runId, Utc.nowIso(), "python-broker", action, result,
            errorCode = code, summary = "Python invocation $action: $result", inputBytes = inputBytes,
            metadataJson = buildJsonObject {
                put("invocationId", bound.ticket.invocationId); put("installId", bound.entry.installId)
                put("packageHash", bound.entry.packageHash); put("grantId", bound.grant.grantId)
                put("grantRevision", bound.grant.revision)
                capability?.takeIf { PythonIpcProtocol.validateCapability(it) }?.let { put("capability", it) }
            }.toString()))
    }

    private fun unknownRun(): Boolean = runOutcomeUnknown || container.runs.get(runId)?.state == RunStatus.UNKNOWN_OUTCOME

    /** A durable positive signal for Chat: approval alone does not prove a request was sent. */
    private suspend fun markKnownCancellation(bound: BoundPythonCall) {
        bound.active = false
        bound.terminal = true
        withContext(NonCancellable + Dispatchers.IO) {
            container.db.transaction {
                val now = Utc.nowIso()
                val old = container.runs.invocations(runId).firstOrNull { it.callId == bound.call.callId }
                val invocation = (old ?: ToolInvocation("$runId:${bound.call.callId}", runId, bound.call.callId,
                    bound.call.name, argumentsJson = "{}", permissionDecision = "APPROVED", createdAt = now)).copy(
                    state = "CANCELLED", errorCode = "CANCELLED_BEFORE_DISPATCH", updatedAt = now, resultJson = null)
                if (old == null) container.runs.recordInvocation(invocation) else container.runs.updateInvocation(invocation)
                audit(bound, "invoke", "CANCELLED", "CANCELLED_BEFORE_DISPATCH")
            }
        }
    }

    /** Durable before returning/throwing, including when the caller's Job is already cancelled. */
    private suspend fun markUnknown(bound: BoundPythonCall, code: String): ToolResult.UnknownOutcome {
        runOutcomeUnknown = true
        bound.unknownExternalOutcome = true
        bound.active = false
        withContext(NonCancellable + Dispatchers.IO) {
            container.db.transaction {
                val now = Utc.nowIso()
                val run = checkNotNull(container.runs.get(runId)) { "Unknown Python outcome cannot be persisted without its Run" }
                if (run.state != RunStatus.UNKNOWN_OUTCOME) container.runs.save(run.copy(
                    state = RunStatus.UNKNOWN_OUTCOME, errorCode = "UNKNOWN_OUTCOME",
                    stopReason = UNKNOWN_REASON, finishedAt = now, updatedAt = now, retryAcknowledgedAt = null))
                val old = container.runs.invocations(runId).firstOrNull { it.callId == bound.call.callId }
                // Broker denial, runtime teardown and cancellation can report the same outcome.
                // Retain the first concrete cause and exactly one invocation UNKNOWN audit.
                if (old?.state == "UNKNOWN_OUTCOME") return@transaction
                val invocation = (old ?: ToolInvocation("$runId:${bound.call.callId}", runId, bound.call.callId,
                    bound.call.name, argumentsJson = "{}", permissionDecision = "APPROVED", createdAt = now)).copy(
                    state = "UNKNOWN_OUTCOME", errorCode = "UNKNOWN_OUTCOME", updatedAt = now,
                    resultJson = buildJsonObject { put("status", "UNKNOWN_OUTCOME"); put("code", code)
                        put("automaticReplayAllowed", false) }.toString())
                if (old == null) container.runs.recordInvocation(invocation) else container.runs.updateInvocation(invocation)
                audit(bound, "invoke", "UNKNOWN_OUTCOME", code)
            }
        }
        return ToolResult.UnknownOutcome(UNKNOWN_REASON)
    }

    private fun replayDenied(): ToolResult = ToolResult.Denied("Python invocation already started or ended; automatic replay is prohibited")

    private fun childDirectory(parent: File, relative: String): File {
        val base = parent.canonicalFile
        val child = File(base, relative).canonicalFile
        if (!child.path.startsWith(base.path + File.separator)) throw BrokerDenied("PERMISSION_DENIED")
        if (!child.isDirectory && !child.mkdirs()) throw BrokerDenied("STORAGE_UNAVAILABLE")
        return child
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) {
            atomic.failWrite(stream); throw error
        }
    }

    private companion object {
        val STORAGE_LOCK = Any()
        const val UNKNOWN_REASON = "UNKNOWN_OUTCOME: Python may have executed side effects; this Run is stopped and an explicitly acknowledged new Run is required"
    }
}

private class BrokerDenied(val code: String) : IllegalArgumentException(code)
private const val LEGACY_CLAUDE_ENTRYPOINT = "mobileagent_legacy_skill:run"
private const val LEGACY_SOURCE_FIELD = "__mobileagent_verified_program_source"
private fun objectOrNull(value: String): JsonObject? = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.strings(key: String): Set<String> = (this[key] as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content
}?.toSet().orEmpty()
private fun JsonObject.requiredString(key: String, max: Int): String = string(key)?.takeIf { it.isNotBlank() && it.length <= max }
    ?: throw BrokerDenied("INVALID_ARGUMENTS")
private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun utf8Prefix(value: String, max: Int): String {
    var end = minOf(value.length, max)
    while (end > 0 && value.substring(0, end).toByteArray().size > max) end--
    if (end > 0 && value[end - 1].isHighSurrogate()) end--
    return value.substring(0, end)
}

/** Deliberately small, fail-closed JSON Schema subset; unsupported keywords never get ignored. */
private object PythonJsonSchema {
    private val annotations = setOf("title", "description", "default", "examples", "\$schema", "\$id")
    private val keywords = annotations + setOf("type", "properties", "required", "additionalProperties", "items", "enum", "const",
        "minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems")

    fun supported(schema: JsonObject, depth: Int = 0): Boolean {
        if (depth > 12 || schema.keys.any { it !in keywords }) return false
        val type = schema.string("type") ?: return false
        if (type !in setOf("object", "array", "string", "integer", "number", "boolean", "null")) return false
        if (schema["enum"]?.let { it !is JsonArray || it.isEmpty() } == true) return false
        for (key in listOf("minLength", "maxLength", "minItems", "maxItems")) {
            if (key in schema && (schema.number(key) == null || schema.number(key)!! < 0)) return false
        }
        for (key in listOf("minimum", "maximum")) {
            if (key in schema && ((schema[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.isFinite() != true)) return false
        }
        if (type == "object") {
            val properties = schema["properties"] as? JsonObject ?: return false
            if (properties.values.any { it !is JsonObject || !supported(it, depth + 1) }) return false
            val required = schema["required"] ?: JsonArray(emptyList())
            if (required !is JsonArray || required.any { it !is JsonPrimitive || !it.isString || it.content !in properties }) return false
            if (schema["additionalProperties"] !in listOf(JsonPrimitive(true), JsonPrimitive(false))) return false
        }
        if (type == "array") {
            val item = schema["items"] as? JsonObject ?: return false
            if (!supported(item, depth + 1)) return false
        }
        return true
    }

    fun matches(schema: JsonObject, value: JsonElement, depth: Int = 0): Boolean {
        if (depth > 12) return false
        if (schema["enum"]?.let { value !in (it as JsonArray) } == true) return false
        if (schema["const"]?.let { it != value } == true) return false
        return when (schema.string("type")) {
            "object" -> {
                if (value !is JsonObject) false else {
                    val properties = schema["properties"] as JsonObject
                    schema.strings("required").all { it in value } &&
                        (schema["additionalProperties"] == JsonPrimitive(true) || value.keys.all { it in properties }) &&
                        value.all { (key, item) -> properties[key]?.let { matches(it as JsonObject, item, depth + 1) } ?: true }
                }
            }
            "array" -> value is JsonArray && value.size >= (schema.number("minItems") ?: 0) &&
                value.size <= (schema.number("maxItems") ?: 4096) && value.all { matches(schema["items"] as JsonObject, it, depth + 1) }
            "string" -> value is JsonPrimitive && value.isString && value.content.codePointCount(0, value.content.length) >= (schema.number("minLength") ?: 0) &&
                value.content.codePointCount(0, value.content.length) <= (schema.number("maxLength") ?: 262_144)
            "number", "integer" -> {
                val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                number != null && number.isFinite() && (schema.string("type") != "integer" || number % 1.0 == 0.0) &&
                    number >= ((schema["minimum"] as? JsonPrimitive)?.doubleOrNull ?: -Double.MAX_VALUE) &&
                    number <= ((schema["maximum"] as? JsonPrimitive)?.doubleOrNull ?: Double.MAX_VALUE)
            }
            "boolean" -> value == JsonPrimitive(true) || value == JsonPrimitive(false)
            "null" -> value == JsonNull
            else -> false
        }
    }
}
