// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Run-local routing and verified knowledge evidence. This class does not add MCP support. */
class RunTools(
    private val container: AppContainer,
    context: Context,
    snapshot: AgentSnapshot,
    private val run: AgentRun,
    private val supportsImages: Boolean,
    private val textDegradation: Boolean,
    private val extraExecutors: List<ToolExecutor> = emptyList(),
) {
    init {
        require(run.runId.isNotBlank() && run.snapshotId == snapshot.id) { "Run and snapshot binding must match" }
    }

    private val agentId = snapshot.agentId
    private val snapshotKnowledgeIds = snapshot.knowledgeBaseIds.toSet()
    private val mutex = Mutex()
    private val registryLock = Any()
    private val registry = linkedMapOf<String, Evidence>()
    private val visualWarnings = linkedSetOf<String>()
    private val calls = linkedMapOf<String, RoutedCall>()
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
    private val python = pythonSkillTools(container, context, snapshot, run.runId, pythonBudget)
    private val routes: Map<String, ToolExecutor> = buildMap {
        for (owner in listOf(builtins, python) + extraExecutors) {
            for (spec in owner.specs) {
                require(spec.name.isNotBlank() && !containsKey(spec.name)) { "Tool specification names must be unique" }
                put(spec.name, owner)
            }
        }
    }

    val executor: ToolExecutor = object : ToolExecutor {
        override val specs: List<ToolSpec> = (builtins.specs + python.specs + extraExecutors.flatMap { it.specs }).toList()

        override suspend fun invoke(call: ToolCall): ToolResult = mutex.withLock {
            if (call.callId.isBlank()) return@withLock ToolResult.Invalid("Tool call ID is missing")
            val previous = calls[call.callId]
            if (previous != null && previous.call != call) {
                return@withLock ToolResult.Invalid("Tool call ID was already used for a different request")
            }
            val routed = previous ?: RoutedCall(call, routes[call.name]).also { calls[call.callId] = it }
            routed.failure?.let { return@withLock it }
            val owner = routed.owner ?: return@withLock reject(routed, ToolResult.Invalid("Unknown or unavailable tool"))
            execute(routed) { owner.invoke(call) }
        }

        override suspend fun approve(callId: String): ToolResult = mutex.withLock {
            val routed = calls[callId] ?: return@withLock ToolResult.Invalid("No pending tool call")
            routed.failure?.let { return@withLock it }
            val owner = routed.owner
            if (owner == null || !routed.needsApproval) return@withLock ToolResult.Invalid("No pending tool approval")
            routed.needsApproval = false
            execute(routed) { owner.approve(callId) }
        }
    }

    /** Called by AgentRuntime before attaching the tool's following multimodal user message. */
    suspend fun toolImages(call: ToolCall, result: ToolResult): List<InlineImage> = mutex.withLock {
        if (result !is ToolResult.Value) return@withLock emptyList()
        val routed = calls[call.callId] ?: throw EvidenceInvalid("Tool image call is unknown")
        if (routed.call != call) throw EvidenceInvalid("Tool image call identity changed")
        val processed = routed.processed ?: return@withLock emptyList()
        if (processed.result != result) throw EvidenceInvalid("Tool result changed before image attachment")
        withTimeout(remainingMillis()) {
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
        var needsApproval: Boolean = false,
        var failure: ToolResult? = null,
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
                routed.needsApproval = raw == ToolResult.NeedsApproval
                if (raw is ToolResult.UnknownOutcome) return@withTimeout reject(routed, raw)
                if (raw !is ToolResult.Value || routed.owner !== builtins ||
                    routed.call.name !in setOf(BuiltinTools.knowledgeSearch.name, BuiltinTools.readDocument.name)) return@withTimeout raw
                runInterruptible(Dispatchers.IO) {
                    try {
                        val previous = routed.processed
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
                            routed.processed = processed
                            processed.result
                        }
                    } catch (denied: EvidenceDenied) {
                        reject(routed, ToolResult.Denied(denied.message ?: "Knowledge authorization is unavailable"))
                    } catch (invalid: EvidenceInvalid) {
                        reject(routed, ToolResult.Invalid(invalid.message ?: "Knowledge evidence is invalid"))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            reject(routed, ToolResult.Denied("Cancelled tool call cannot be replayed; use a new call ID"))
            throw cancelled
        } catch (denied: EvidenceDenied) {
            reject(routed, ToolResult.Denied(denied.message ?: "Run is unavailable"))
        }
    }

    private fun reject(routed: RoutedCall, result: ToolResult): ToolResult {
        routed.needsApproval = false
        routed.failure = result
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
