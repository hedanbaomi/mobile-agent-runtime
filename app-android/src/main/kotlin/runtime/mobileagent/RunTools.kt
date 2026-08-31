// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.knowledge.LoadedVisual
import runtime.mobileagent.knowledge.StrictVisualDecision
import runtime.mobileagent.knowledge.StrictVisualPolicy
import runtime.mobileagent.knowledge.VisualAttachmentPlan
import runtime.mobileagent.knowledge.VisualAttachmentPolicy
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.skills.BuiltinTools
import runtime.mobileagent.skills.HttpPolicy
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.tooling.ToolResultBudget
import runtime.mobileagent.skills.tooling.InternalRequestIds
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Run-local routing, provider composition, and verified knowledge evidence. */
class RunTools(
    private val container: AppContainer,
    context: Context,
    snapshot: AgentSnapshot,
    private val run: AgentRun,
    private val supportsImages: Boolean,
    private val textDegradation: Boolean,
    /** Provider-only fallback routes retained when the v2 integration seam is unavailable. */
    private val baseExecutors: List<ToolExecutor> = emptyList(),
    /** Optional per-run provider composition supplied by AppContainer's tooling seam. */
    private val runExecutor: ToolExecutor? = null,
    /**
     * Adapter used while the AppContainer seam is being materialized.  The
     * Python executor is still created here, with the run's shared budget,
     * before the factory receives it.
     */
    private val runExecutorFactory: ((python: ToolExecutor) -> ToolExecutor?)? = null,
) {
    init {
        require(run.runId.isNotBlank() && run.snapshotId == snapshot.id) { "Run and snapshot binding must match" }
    }

    private val agentId = snapshot.agentId
    private val snapshotKnowledgeIds = snapshot.knowledgeBaseIds.toSet()
    /**
     * Only the small call-state transitions use this monitor.  Provider and
     * workspace calls deliberately run outside it so a long invoke/approval
     * cannot block cancellation or a later lifecycle transition.
     */
    private val callLock = Any()
    private val registryLock = Any()
    private val registry = linkedMapOf<String, Evidence>()
    private val visualWarnings = linkedSetOf<String>()
    /** Runtime request ids are the execution/replay keys; model ids are correlation only. */
    private val calls = linkedMapOf<String, RoutedCall>()
    private val callsByModelId = linkedMapOf<String, MutableList<RoutedCall>>()
    private var reservedModelTokens = 0L

    private val pythonBudget = object : PythonRunBudget {
        override fun reserveBrokerCall(): Boolean = synchronized(run) {
            if (!runActive() || run.toolCalls >= run.budget.maxToolCalls) return@synchronized false
            run.toolCalls += 1
            true
        }

        override fun reserveModelCall(maxTokens: Int): Boolean = synchronized(run) {
            if (!runActive() || maxTokens <= 0 || run.modelRounds >= run.budget.maxModelRounds) return@synchronized false
            // There is no default token allowance in AgentRun. Only an explicitly persisted
            // Run token ceiling can enable model.invoke; ordinary Runs remain denied.
            val stored = container.runs.get(run.runId) ?: return@synchronized false
            val tokenLimit = objectOrNull(stored.budgetJson)?.integer("maxModelTokens")
                ?.takeIf { it > 0 } ?: return@synchronized false
            val used = stored.inputTokens.toLong() + stored.outputTokens + reservedModelTokens
            if (used < 0 || used + maxTokens > tokenLimit.toLong()) return@synchronized false
            run.modelRounds += 1
            reservedModelTokens += maxTokens
            true
        }
    }

    private val builtins = boundBuiltinTools(container, snapshot)
    // Always construct Python here so the factory receives the same run budget
    // and context used by the legacy path.  A factory-owned executor contains
    // Python/workspace/memory/shell; it replaces, rather than supplements, the
    // legacy provider list to prevent duplicate names or fallback routes.
    private val python: ToolExecutor = pythonSkillTools(container, context, snapshot, run.runId, pythonBudget)
    private val providerExecutor: ToolExecutor? = runExecutor ?: runExecutorFactory?.invoke(python)
    private val routeOwners: List<ToolExecutor> = when {
        providerExecutor != null -> listOf(builtins, providerExecutor)
        // A failed/unavailable v2 factory must not silently expose an old
        // privileged workspace/shell executor.  Web/MCP/Python remain the
        // only compatibility routes until the next run can rebuild the seam.
        else -> listOf(builtins, python) + baseExecutors
    }
    private val routes: Map<String, ToolExecutor> = buildMap {
        for (owner in routeOwners) {
            for (spec in owner.specs) {
                require(spec.name.isNotBlank() && !containsKey(spec.name)) { "Tool specification names must be unique" }
                put(spec.name, owner)
            }
        }
    }
    /** The model-visible contract is immutable for this run. */
    private val frozenSpecs: List<ToolSpec> =
        routes.keys.mapNotNull { name -> routes[name]?.specs?.firstOrNull { it.name == name } }.toList()

    val executor: ToolExecutor = object : ToolExecutor {
        override val specs: List<ToolSpec> = frozenSpecs

        override suspend fun invoke(call: ToolCall): ToolResult {
            if (call.callId.isBlank()) return ToolResult.Invalid("Tool call ID is missing")
            val routed: RoutedCall
            val owner: ToolExecutor
            synchronized(callLock) {
                val previous = callsByModelId[call.callId]?.firstOrNull { it.call == call }
                routed = previous ?: RoutedCall(call, routes[call.name]).also {
                    calls[it.runtimeInvocationId] = it
                    callsByModelId.getOrPut(call.callId) { mutableListOf() }.add(it)
                }
                routed.completed?.let { return it }
                // A pending approval is already the result of this model call;
                // do not re-enter the provider when a duplicate invoke arrives.
                // Returning the same authorization signal keeps this call ID
                // single-use while allowing the existing approval transition.
                if (routed.needsApproval) return ToolResult.NeedsApproval
                if (routed.inFlight) {
                    return ToolResult.UnknownOutcome("Tool call is already executing; use a new call ID")
                }
                owner = routed.owner ?: return reject(routed, ToolResult.Invalid("Unknown or unavailable tool"))
                routed.inFlight = true
            }
            return execute(routed) { owner.invoke(routed.ownerCall()) }
        }

        override suspend fun approve(callId: String): ToolResult {
            val routed: RoutedCall
            val owner: ToolExecutor
            synchronized(callLock) {
                routed = routedFor(callId) ?: return ToolResult.Invalid("No pending tool call")
                routed.completed?.let { return it }
                owner = routed.owner ?: return ToolResult.Invalid("No pending tool approval")
                if (!routed.needsApproval || routed.inFlight) return ToolResult.Invalid("No pending tool approval")
                // Keep this transition short; the external operation is below
                // and may suspend for the complete provider/backend deadline.
                routed.needsApproval = false
                routed.inFlight = true
            }
            return execute(routed) { owner.approve(routed.runtimeInvocationId) }
        }

        override suspend fun reject(callId: String): ToolResult = settleApproval(callId) { owner, runtimeId ->
            owner.reject(runtimeId)
        }

        override suspend fun expire(callId: String): ToolResult = settleApproval(callId) { owner, runtimeId ->
            owner.expire(runtimeId)
        }
    }

    /** Resolve and close a pending approval without re-entering the model call. */
    private suspend fun settleApproval(
        callId: String,
        operation: suspend (ToolExecutor, String) -> ToolResult,
    ): ToolResult {
        val routed: RoutedCall
        val owner: ToolExecutor
        synchronized(callLock) {
            routed = routedFor(callId) ?: return ToolResult.Invalid("No pending tool call")
            routed.completed?.let { return it }
            owner = routed.owner ?: return ToolResult.Invalid("No pending tool approval")
            if (!routed.needsApproval || routed.inFlight) return ToolResult.Invalid("No pending tool approval")
            routed.needsApproval = false
            routed.inFlight = true
        }
        // Reject/expire are lifecycle mutations, not external execution.  They
        // must still reach ApprovalEngine after AgentRuntime has marked the run
        // BUDGET_EXHAUSTED or CANCELLED, so do not route them through execute(),
        // whose runActive() guard intentionally blocks dispatch.
        return try {
            complete(routed, operation(owner, routed.runtimeInvocationId))
        } catch (cancelled: CancellationException) {
            reject(routed, ToolResult.UnknownOutcome("Approval settlement was cancelled; outcome is unknown"))
            throw cancelled
        } catch (_: Exception) {
            reject(routed, ToolResult.UnknownOutcome("Approval settlement failed; outcome is unknown"))
        }
    }

    /** Runtime-owned request identity paired with an AgentRuntime model call id. */
    fun runtimeInvocationId(modelCallId: String): String? = synchronized(callLock) {
        callsByModelId[modelCallId]?.let { routes ->
            routes.firstOrNull { it.needsApproval }?.runtimeInvocationId
                ?: routes.singleOrNull()?.runtimeInvocationId
        }
    }

    /** Called by AgentRuntime before attaching the tool's following multimodal user message. */
    suspend fun toolImages(call: ToolCall, result: ToolResult): List<InlineImage> {
        if (result !is ToolResult.Value) return emptyList()
        val processed = synchronized(callLock) {
            val routed = callsByModelId[call.callId]?.firstOrNull { it.call == call }
                ?: throw EvidenceInvalid("Tool image call is unknown")
            if (routed.call != call) throw EvidenceInvalid("Tool image call identity changed")
            routed.processed
        } ?: return emptyList()
        if (processed.result != result) throw EvidenceInvalid("Tool result changed before image attachment")
        return withTimeout(remainingMillis()) {
            runInterruptible(Dispatchers.IO) {
                verifyAll(processed.evidence)
                val visuals = planVisuals(processed.evidence)
                if (visuals.warning != processed.warning) throw EvidenceInvalid("Visual evidence changed after tool execution")
                val images = visuals.images.map { image ->
                    InlineImage(image.mediaType, Base64.getEncoder().encodeToString(image.bytes), image.assetId)
                }
                verifyAll(processed.evidence)
                images
            }
        }
    }

    /** UI/persistence receive only evidence that still has live Agent/KB/source authorization. */
    fun evidence(): List<Pair<Citation, String>> {
        val snapshot = synchronized(registryLock) { registry.values.toList() }
        return snapshot.filter { record -> runCatching { verify(record) }.isSuccess }.map { it.citation to it.text }
    }

    fun warnings(): List<String> = synchronized(registryLock) { visualWarnings.toList() }

    private data class RoutedCall(
        val call: ToolCall,
        val owner: ToolExecutor?,
        val runtimeInvocationId: String = InternalRequestIds.new(),
        var needsApproval: Boolean = false,
        var completed: ToolResult? = null,
        var inFlight: Boolean = false,
        var processed: Processed? = null,
    )

    private data class Evidence(val citation: Citation, val text: String, val wholeChunk: Boolean)
    private data class Processed(
        val rawJson: String,
        val result: ToolResult.Value,
        val evidence: List<Evidence>,
        val warning: String?,
    )
    private data class Visuals(val images: List<LoadedVisual>, val warning: String? = null)
    private data class Chunk(
        val id: String,
        val versionId: String,
        val documentId: String,
        val knowledgeBaseId: String,
        val text: String,
        val assetIds: List<String>,
        val page: Int?,
        val sourceSpan: String?,
    )

    private suspend fun execute(routed: RoutedCall, operation: suspend () -> ToolResult): ToolResult {
        if (!synchronized(run) { runActive() }) return reject(routed, ToolResult.Denied("Run deadline or terminal state prevents execution"))
        return try {
            withTimeout(remainingMillis()) {
                val raw = operation()
                if (raw == ToolResult.NeedsApproval) {
                    synchronized(callLock) {
                        routed.needsApproval = true
                        routed.inFlight = false
                    }
                    return@withTimeout raw
                }
                if (raw is ToolResult.UnknownOutcome) return@withTimeout reject(routed, raw)
                if (raw is ToolResult.Value && !ToolResultBudget.withinSerializedBudget(raw.json)) {
                    return@withTimeout complete(routed, ToolResult.Invalid("Tool result exceeds the serialization budget"))
                }
                if (raw !is ToolResult.Value || routed.owner !== builtins ||
                    routed.call.name !in setOf(BuiltinTools.knowledgeSearch.name, BuiltinTools.readDocument.name)) return@withTimeout complete(routed, raw)
                val enriched = runInterruptible(Dispatchers.IO) {
                    try {
                        val previous = synchronized(callLock) { routed.processed }
                        if (previous != null) {
                            if (previous.rawJson != raw.json) throw EvidenceInvalid("Repeated tool call returned different source data")
                            verifyAll(previous.evidence)
                            if (planVisuals(previous.evidence).warning != previous.warning) throw EvidenceInvalid("Visual evidence changed")
                            previous.result
                        } else {
                            val processed = enrich(routed.call, raw)
                            synchronized(registryLock) {
                                processed.evidence.forEach { item ->
                                    val old = registry[item.citation.citationId]
                                    check(old == null || old == item) { "Citation identifier collision" }
                                }
                                processed.evidence.forEach { registry[it.citation.citationId] = it }
                                processed.warning?.let { visualWarnings.add(it) }
                            }
                            synchronized(callLock) { routed.processed = processed }
                            processed.result
                        }
                    } catch (denied: EvidenceDenied) {
                        reject(routed, ToolResult.Denied(denied.message ?: "Knowledge authorization is unavailable"))
                    } catch (invalid: EvidenceInvalid) {
                        reject(routed, ToolResult.Invalid(invalid.message ?: "Knowledge evidence is invalid"))
                    }
                }
                if (enriched is ToolResult.Value && !ToolResultBudget.withinSerializedBudget(enriched.json)) {
                    complete(routed, ToolResult.Invalid("Tool result exceeds the serialization budget"))
                } else {
                    complete(routed, enriched)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            reject(routed, ToolResult.UnknownOutcome("Tool execution timed out; outcome is unknown"))
        } catch (cancelled: CancellationException) {
            // Once an owner operation has started, cancellation cannot prove
            // whether the external side effect happened.  Preserve the
            // terminal unknown outcome and never permit replay on this call ID.
            reject(routed, ToolResult.UnknownOutcome("Tool execution was cancelled; outcome is unknown"))
            throw cancelled
        } catch (denied: EvidenceDenied) {
            reject(routed, ToolResult.Denied(denied.message ?: "Run is unavailable"))
        } catch (_: Exception) {
            // A provider/backend disconnect can occur after dispatch.  The
            // legacy boundary has no dispatch bit, so fail closed as unknown
            // rather than inviting an unsafe automatic retry.
            reject(routed, ToolResult.UnknownOutcome("Tool execution failed; outcome is unknown"))
        }
    }

    /** Resolve either the model correlation id (AgentRuntime compatibility) or runtime id. */
    private fun routedFor(id: String): RoutedCall? = calls[id] ?: callsByModelId[id]?.let { routes ->
        // AgentRuntime still supplies the model id for approve(). Select the
        // pending route when possible; explicit lifecycle calls use the runtime
        // id and never depend on this compatibility path.
        routes.firstOrNull { it.needsApproval } ?: routes.singleOrNull()
    }

    private fun RoutedCall.ownerCall(): ToolCall = ToolCall(
        callId = runtimeInvocationId,
        name = call.name,
        argumentsJson = call.argumentsJson,
    )

    private fun reject(routed: RoutedCall, result: ToolResult): ToolResult {
        synchronized(callLock) {
            routed.needsApproval = false
            routed.completed = result
            routed.inFlight = false
        }
        return result
    }

    private fun complete(routed: RoutedCall, result: ToolResult): ToolResult {
        synchronized(callLock) {
            routed.needsApproval = false
            routed.completed = result
            routed.inFlight = false
        }
        return result
    }

    private fun enrich(call: ToolCall, raw: ToolResult.Value): Processed {
        val root = objectOrNull(raw.json) ?: throw EvidenceInvalid("Knowledge result must be a JSON object")
        val evidence = mutableListOf<Evidence>()
        val output = if (call.name == BuiltinTools.knowledgeSearch.name) {
            val hits = root["hits"] as? JsonArray ?: throw EvidenceInvalid("Knowledge hits must be an array")
            buildJsonObject {
                put("hits", buildJsonArray {
                    for (item in hits) {
                        val hit = item as? JsonObject ?: throw EvidenceInvalid("Knowledge hit must be an object")
                        val chunk = loadChunk(hit.requiredString("chunkId"), hit.requiredString("documentVersionId"))
                        if (hit.requiredString("documentId") != chunk.documentId ||
                            hit.requiredString("knowledgeBaseId") != chunk.knowledgeBaseId ||
                            hit.requiredString("text", allowEmpty = true) != chunk.text) throw EvidenceInvalid("Knowledge hit does not match its source chunk")
                        val suppliedAsset = hit["assetId"]?.let { hit.requiredString("assetId") }
                        if (suppliedAsset != null && suppliedAsset !in chunk.assetIds) throw EvidenceInvalid("Knowledge hit asset is outside its source chunk")
                        val entries = citeChunk(call, chunk, chunk.text, wholeChunk = true, firstOrdinal = evidence.size)
                        evidence.addAll(entries)
                        add(buildJsonObject {
                            put("knowledgeBaseId", chunk.knowledgeBaseId)
                            put("documentId", chunk.documentId)
                            put("documentVersionId", chunk.versionId)
                            put("chunkId", chunk.id)
                            put("text", chunk.text)
                            put("citationId", entries.first().citation.citationId)
                            put("citations", citationJson(entries))
                        })
                    }
                })
            }
        } else {
            enrichDocument(call, root, evidence)
        }
        verifyAll(evidence)
        val visuals = planVisuals(evidence)
        val enriched = JsonObject(output + if (visuals.warning == null) emptyMap() else mapOf(
            "warning" to JsonPrimitive(visuals.warning), "textDegradation" to JsonPrimitive(true),
        )).toString()
        if (enriched.length > HttpPolicy.MAX_TOOL_OUTPUT_CHARS) throw EvidenceInvalid("Cited tool output exceeds the tool limit")
        verifyAll(evidence)
        return Processed(raw.json, ToolResult.Value(enriched), evidence.toList(), visuals.warning)
    }

    private fun enrichDocument(call: ToolCall, root: JsonObject, evidence: MutableList<Evidence>): JsonObject {
        val args = objectOrNull(call.argumentsJson) ?: throw EvidenceInvalid("Document arguments are invalid")
        val documentId = args.requiredString("documentId")
        if (root.requiredString("documentId") != documentId) throw EvidenceInvalid("Read document identity changed")
        val maxChars = if ("maxChars" in args) args.integer("maxChars") else 4000
        if (maxChars == null || maxChars !in 1..HttpPolicy.MAX_READ_DOCUMENT_CHARS) throw EvidenceInvalid("Document character limit is invalid")
        val document = container.db.query(
            "SELECT kb_id, active_version_id, deleted_at FROM documents WHERE id = ?", listOf(documentId),
        ).singleOrNull() ?: throw EvidenceDenied("Document is unavailable")
        if (document.string("deleted_at").isNotBlank() || document.string("kb_id") !in liveKnowledgeIds()) throw EvidenceDenied("Document is outside live Agent authorization")
        val versionId = document.string("active_version_id").takeIf { it.isNotBlank() }
            ?: throw EvidenceInvalid("Document has no published version")
        val rows = container.db.query(
            "SELECT id FROM chunks WHERE document_version_id = ? ORDER BY ordinal", listOf(versionId),
        )
        val chunks = rows.map { loadChunk(it.string("id"), versionId) }
        if (chunks.any { it.documentId != documentId }) throw EvidenceInvalid("Document chunks belong to a different source")
        val text = chunks.joinToString("\n") { it.text }.take(maxChars)
        if (root.requiredString("text", allowEmpty = true) != text) throw EvidenceInvalid("Read document changed before citation binding")
        val citations = buildJsonArray {
            var start = 0
            for (chunk in chunks) {
                if (start > text.length || start >= maxChars) break
                val end = minOf(start + chunk.text.length, text.length)
                // A chunk whose content is entirely outside the returned prefix contributes no evidence.
                if (start == text.length && chunk.text.isNotEmpty()) break
                val visible = chunk.text.take((end - start).coerceAtLeast(0))
                val entries = citeChunk(call, chunk, visible, wholeChunk = visible == chunk.text, firstOrdinal = evidence.size)
                evidence.addAll(entries)
                entries.forEach { entry ->
                    add(JsonObject(citationObject(entry.citation) + mapOf("textStart" to JsonPrimitive(start), "textEnd" to JsonPrimitive(end))))
                }
                start += chunk.text.length + 1
            }
        }
        return buildJsonObject {
            put("documentId", documentId)
            put("documentVersionId", versionId)
            put("text", text)
            put("citations", citations)
            evidence.firstOrNull()?.let { put("citationId", it.citation.citationId) }
        }
    }

    private fun citeChunk(call: ToolCall, chunk: Chunk, text: String, wholeChunk: Boolean, firstOrdinal: Int): List<Evidence> {
        val assets: List<String?> = if (chunk.assetIds.isEmpty()) listOf(null) else chunk.assetIds
        return assets.mapIndexed { ordinal, assetId ->
            val page = assetId?.let { asset ->
                container.db.query(
                    "SELECT page FROM assets WHERE id = ? AND document_id = ? AND document_version_id = ?",
                    listOf(asset, chunk.documentId, chunk.versionId),
                ).singleOrNull()?.let { it.optionalPage() }
            } ?: chunk.page
            val citation = Citation(citationId(call.callId, firstOrdinal + ordinal), run.runId, chunk.knowledgeBaseId,
                chunk.documentId, chunk.id, assetId, page, chunk.versionId, chunk.sourceSpan)
            Evidence(citation, text, wholeChunk).also { verify(it) }
        }
    }

    private fun loadChunk(chunkId: String, versionId: String): Chunk {
        val row = container.db.query(
            """SELECT c.id AS chunk_id, c.text AS text, c.page AS page, c.source_span AS source_span,
                c.asset_ids AS asset_ids, v.id AS version_id, v.document_id AS document_id, v.status AS version_status,
                d.kb_id AS kb_id, d.deleted_at AS document_deleted, k.deleted_at AS kb_deleted
                FROM chunks c JOIN document_versions v ON v.id = c.document_version_id
                JOIN documents d ON d.id = v.document_id JOIN knowledge_bases k ON k.id = d.kb_id
                WHERE c.id = ? AND v.id = ?""".trimIndent(), listOf(chunkId, versionId),
        ).singleOrNull() ?: throw EvidenceDenied("Citation source is unavailable")
        if (row.string("document_deleted").isNotBlank() || row.string("kb_deleted").isNotBlank() ||
            row.string("kb_id") !in liveKnowledgeIds()) throw EvidenceDenied("Citation is outside live Agent authorization")
        if (row.string("version_status") != "READY") throw EvidenceInvalid("Citation source version is not published")
        return Chunk(row.string("chunk_id"), row.string("version_id"), row.string("document_id"), row.string("kb_id"),
            row.string("text"), assetIds(row.string("asset_ids")), row.optionalPage(), row.string("source_span").ifBlank { null })
    }

    private fun verify(record: Evidence) {
        val citation = record.citation
        if (citation.runId != run.runId) throw EvidenceInvalid("Citation belongs to a different Run")
        val chunk = loadChunk(citation.chunkId, citation.documentVersionId)
        if (chunk.documentId != citation.documentId || chunk.knowledgeBaseId != citation.knowledgeBaseId ||
            chunk.sourceSpan != citation.sourceSpan ||
            (record.wholeChunk && chunk.text != record.text) || (!record.wholeChunk && !chunk.text.startsWith(record.text))) {
            throw EvidenceInvalid("Citation does not match its source version")
        }
        if ((citation.assetId == null && chunk.assetIds.isNotEmpty()) ||
            (citation.assetId != null && citation.assetId !in chunk.assetIds)) throw EvidenceInvalid("Citation omitted or substituted a visual source")
        val locator = container.knowledge.locateCitation(citation)
        if (locator.removed || locator.assetId != citation.assetId || locator.page != citation.page) throw EvidenceDenied("Citation source is no longer available")
    }

    private fun verifyAll(evidence: List<Evidence>) = evidence.forEach(::verify)

    private fun planVisuals(evidence: List<Evidence>): Visuals {
        verifyAll(evidence)
        val assets = evidence.mapNotNull { it.citation.assetId }.distinct()
        if (assets.isEmpty()) return Visuals(emptyList())
        when (val decision = StrictVisualPolicy.allow(true, supportsImages, textDegradation)) {
            is StrictVisualDecision.Reject -> throw EvidenceInvalid(decision.reason)
            is StrictVisualDecision.Allow -> Unit
        }
        if (textDegradation) return Visuals(emptyList(), TEXT_DEGRADATION_WARNING)
        // Earlier tool images remain in model history. Count the union rather than
        // resetting the four-image allowance on every tool call. The caller must
        // also cap the combined automatic-RAG and tool image set at attachment.
        val previousAssets = synchronized(registryLock) { registry.values.mapNotNull { it.citation.assetId } }
        if ((previousAssets + assets).toSet().size > VisualAttachmentPolicy.MAX_IMAGES) {
            throw EvidenceInvalid("Run tool evidence exceeds the image count limit")
        }
        val byAsset = evidence.filter { it.citation.assetId != null }.associateBy { it.citation.assetId!! }
        return when (val plan = VisualAttachmentPolicy.plan(assets) { id ->
            val record = byAsset.getValue(id)
            verify(record)
            val source = container.knowledge.evidenceBytes(record.citation) ?: return@plan null
            val locator = container.knowledge.locateCitation(record.citation)
            val digest = MessageDigest.getInstance("SHA-256").digest(source.second).joinToString("") { "%02x".format(it) }
            if (locator.removed || locator.blobHash != digest || source.first !in IMAGE_MEDIA_TYPES || source.second.isEmpty()) return@plan null
            source
        }) {
            is VisualAttachmentPlan.Incomplete -> throw EvidenceInvalid(plan.reason)
            is VisualAttachmentPlan.Complete -> Visuals(plan.images)
        }
    }

    private fun liveKnowledgeIds(): Set<String> = snapshotKnowledgeIds intersect
        container.agents.get(agentId)?.knowledgeBaseIds.orEmpty().toSet() intersect
        container.knowledge.listKnowledgeBases().map { it.first }.toSet()

    private fun runActive(): Boolean {
        if (run.state in setOf(RunState.COMPLETED, RunState.CANCELLED, RunState.FAILED, RunState.BUDGET_EXHAUSTED, RunState.UNKNOWN_OUTCOME)) return false
        val elapsed = System.currentTimeMillis() - run.startedAtMs
        return run.startedAtMs > 0 && elapsed >= 0 && elapsed < run.budget.maxRuntimeMs
    }

    private fun remainingMillis(): Long = synchronized(run) {
        if (!runActive()) throw EvidenceDenied("Run deadline or terminal state prevents execution")
        (run.budget.maxRuntimeMs - (System.currentTimeMillis() - run.startedAtMs)).coerceAtLeast(1)
    }

    private fun citationId(callId: String, ordinal: Int): String = run.runId + "-tool-" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(callId.toByteArray(Charsets.UTF_8)) + "-$ordinal"

    private fun citationJson(evidence: List<Evidence>): JsonArray = buildJsonArray {
        evidence.forEach { add(citationObject(it.citation)) }
    }

    private fun citationObject(citation: Citation): JsonObject = buildJsonObject {
        put("citationId", citation.citationId)
        put("knowledgeBaseId", citation.knowledgeBaseId)
        put("documentId", citation.documentId)
        put("documentVersionId", citation.documentVersionId)
        put("chunkId", citation.chunkId)
        citation.assetId?.let { put("assetId", it) }
        citation.page?.let { put("page", it) }
        citation.sourceSpan?.let { put("sourceSpan", it) }
    }

    private fun assetIds(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val ids = raw.split(',')
        if (ids.any { id -> id.isBlank() || runCatching { UUID.fromString(id).toString() != id }.getOrDefault(true) } ||
            ids.distinct().size != ids.size) throw EvidenceInvalid("Chunk asset identifiers are invalid")
        return ids
    }

    private fun SqlRow.optionalPage(): Int? {
        if (columns["page"] == null || string("page").isBlank()) return null
        return string("page").toIntOrNull()?.takeIf { it > 0 } ?: throw EvidenceInvalid("Source page is invalid")
    }

    private fun objectOrNull(raw: String): JsonObject? = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun JsonObject.requiredString(key: String, allowEmpty: Boolean = false): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString && (allowEmpty || it.content.isNotBlank()) }?.content
            ?: throw EvidenceInvalid("Knowledge field $key is invalid")

    private class EvidenceDenied(message: String) : IllegalStateException(message)
    private class EvidenceInvalid(message: String) : IllegalStateException(message)

    private companion object {
        const val TEXT_DEGRADATION_WARNING = "已启用纯文本降级：未向模型发送原始图片，视觉证据可能不完整。"
        val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}
