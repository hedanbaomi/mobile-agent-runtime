// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.knowledge.ApiQueryUnknownOutcomeException
import runtime.mobileagent.skills.BuiltinTools
import runtime.mobileagent.skills.CompatibilityClass
import runtime.mobileagent.skills.HostHttp
import runtime.mobileagent.skills.HttpPolicy
import runtime.mobileagent.skills.PermissionGrant
import runtime.mobileagent.skills.ToolBroker
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolContext
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.tooling.AuthorizationEvaluator
import java.net.InetAddress
import java.util.concurrent.CancellationException

/** Built-ins only. Python/MCP executors are composed by the Chat integration separately. */
fun boundBuiltinTools(container: AppContainer, snapshot: AgentSnapshot): ToolExecutor =
    BoundBuiltinToolExecutor(container, snapshot)

private class BoundBuiltinToolExecutor(
    private val container: AppContainer,
    snapshot: AgentSnapshot,
) : ToolExecutor {
    private val agentId = snapshot.agentId
    private val snapshotKnowledgeIds = snapshot.knowledgeBaseIds.toSet()
    private val snapshotSkillIds = snapshot.skillIds.toSet()
    private val mutex = Mutex()
    private val calls = linkedMapOf<String, BoundCall>()

    // This is the model's immutable discovery snapshot, not the authorization decision.
    override val specs: List<ToolSpec> = run {
        val hasKnowledge = liveKnowledgeIds().isNotEmpty()
        val hasHttp = liveHttpGrants().any { grant ->
            grant.hosts.any { host -> runCatching { HttpPolicy.assertRequest("https://$host/", grant.hosts) }.isSuccess }
        }
        BuiltinTools.all.filter { spec ->
            when (spec.capability) {
                "" -> true
                "knowledge.search", "knowledge.read" -> hasKnowledge
                "network.http" -> hasHttp
                else -> false
            }
        }
    }

    override suspend fun invoke(call: ToolCall): ToolResult = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            if (call.callId.isBlank()) return@runInterruptible ToolResult.Invalid("Tool call ID is missing")
            val previous = calls[call.callId]
            if (previous != null && previous.call != call) {
                return@runInterruptible ToolResult.Invalid("Tool call ID was already used for a different request")
            }
            // Once selected, a call can never switch to another install/grant on retry or approval.
            val bound = previous ?: bind(call).also { calls[call.callId] = it }
            executeBound(bound) { broker -> broker.invoke(call) }
        }
    }

    override suspend fun approve(callId: String): ToolResult = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            val bound = calls[callId]
                ?: return@runInterruptible ToolResult.Invalid("No pending side-effect call")
            executeBound(bound) { broker -> broker.approve(callId) }
        }
    }

    /**
     * Disclosure check for RunTools-level cached results.  Re-validates the
     * frozen call binding against live facts (knowledge scope, pinned Skill
     * grant incl. expiry) without re-executing the tool and without
     * disclosing the cached payload when authorization lapsed.
     */
    override suspend fun authorizeReplay(call: ToolCall): Boolean = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            val bound = calls[call.callId] ?: return@runInterruptible false
            if (bound.call != call) return@runInterruptible false
            // An unknown outcome is never replayable; terminal Denied/Invalid
            // envelopes carry no sensitive payload and keep their status.
            if (bound.rejected is ToolResult.UnknownOutcome) return@runInterruptible false
            bound.authorized()
        }
    }

    private data class BoundCall(
        val call: ToolCall,
        val broker: ToolBroker? = null,
        var rejected: ToolResult? = null,
        val authorized: () -> Boolean = { true },
        val uncertainResult: () -> ToolResult.UnknownOutcome? = { null },
    )

    private fun executeBound(bound: BoundCall, execute: (ToolBroker) -> ToolResult): ToolResult {
        bound.rejected?.let { return it }
        if (!bound.authorized()) return ToolResult.Denied("The original tool authorization is no longer available")
        val result = try {
            execute(checkNotNull(bound.broker))
        } catch (interrupted: InterruptedException) {
            bound.rejected = ToolResult.Denied("An interrupted call cannot be replayed; use a new call ID")
            throw interrupted
        } catch (cancelled: CancellationException) {
            bound.rejected = ToolResult.Denied("A cancelled call cannot be replayed; use a new call ID")
            throw cancelled
        }
        bound.uncertainResult()?.let { unknown ->
            bound.rejected = unknown
            return unknown
        }
        // Revocation while I/O was in flight must not expose its result or a remembered result.
        return if (bound.authorized()) result else ToolResult.Denied("Tool authorization changed during execution")
    }

    private fun bind(call: ToolCall): BoundCall {
        val spec = BuiltinTools.byName[call.name]
            ?: return BoundCall(call, rejected = ToolResult.Invalid("Unknown built-in tool"))
        if (spec.name == BuiltinTools.httpRequest.name) return bindHttp(call)
        val knowledgeIds = if (spec.capability.isEmpty()) emptySet() else liveKnowledgeIds()
        if (spec.capability.isNotEmpty() && knowledgeIds.isEmpty()) {
            return BoundCall(call, rejected = ToolResult.Denied("No authorized knowledge base in the current Agent"))
        }
        val returnedDocuments = linkedMapOf<String, String>()
        var queryUnknown: ApiQueryUnknownOutcomeException? = null
        val authorized = {
            spec.capability.isEmpty() || (liveKnowledgeIds().containsAll(knowledgeIds) &&
                returnedDocuments.all { (documentId, kbId) -> container.knowledge.documentKnowledgeBaseId(documentId) == kbId })
        }
        val context = ToolContext(
            search = { query, requestedIds, topK ->
                check(authorized()) { "Knowledge authorization changed" }
                val permitted = requestedIds.toSet() intersect knowledgeIds intersect liveKnowledgeIds()
                val hits = if (permitted.isEmpty()) emptyList() else try {
                    container.knowledge.search(query, topK, permitted.toList())
                } catch (unknown: ApiQueryUnknownOutcomeException) {
                    queryUnknown = unknown
                    throw unknown
                }
                check(authorized()) { "Knowledge authorization changed" }
                buildJsonObject {
                    put("hits", buildJsonArray {
                        hits.filter { hit ->
                            hit.knowledgeBaseId in permitted &&
                                container.knowledge.documentKnowledgeBaseId(hit.documentId) == hit.knowledgeBaseId
                        }.forEach { hit ->
                            returnedDocuments[hit.documentId] = hit.knowledgeBaseId
                            add(buildJsonObject {
                                put("knowledgeBaseId", hit.knowledgeBaseId)
                                put("documentId", hit.documentId)
                                put("documentVersionId", hit.documentVersionId)
                                put("chunkId", hit.chunkId)
                                put("text", hit.text)
                                hit.page?.let { put("page", it) }
                                hit.assetId?.let { put("assetId", it) }
                                hit.sourceSpan?.let { put("sourceSpan", it) }
                            })
                        }
                    })
                }.toString()
            },
            readDocument = { documentId, maxChars ->
                check(authorized()) { "Knowledge authorization changed" }
                val permitted = knowledgeIds intersect liveKnowledgeIds()
                check(container.knowledge.documentKnowledgeBaseId(documentId) in permitted) {
                    "Document is not in an authorized knowledge base"
                }
                returnedDocuments[documentId] = checkNotNull(container.knowledge.documentKnowledgeBaseId(documentId))
                val text = container.knowledge.readDocumentText(documentId, maxChars, permitted)
                check(authorized() && container.knowledge.documentKnowledgeBaseId(documentId) in permitted) {
                    "Document authorization changed"
                }
                buildJsonObject { put("documentId", documentId); put("text", text) }.toString()
            },
            grantedKnowledgeBaseIds = knowledgeIds,
            documentKnowledgeBaseId = { id -> container.knowledge.documentKnowledgeBaseId(id) },
        )
        return BoundCall(
            call = call,
            broker = ToolBroker(setOf("knowledge.search", "knowledge.read"), context),
            authorized = authorized,
            uncertainResult = { queryUnknown?.let {
                ToolResult.UnknownOutcome("API_EMBEDDING_QUERY_UNKNOWN: confirm one retry on the Knowledge screen before submitting the same query")
            } },
        )
    }

    private fun bindHttp(call: ToolCall): BoundCall {
        val args = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull()
            ?: return BoundCall(call, rejected = ToolResult.Invalid("Tool arguments are incomplete JSON"))
        val url = args.stringValue("url")
            ?: return BoundCall(call, rejected = ToolResult.Invalid("url must be a nonblank string"))
        val method = if ("method" in args) args.stringValue("method")?.uppercase() else "GET"
        if (method != "GET") return BoundCall(call, rejected = ToolResult.Invalid("This built-in supports GET only"))
        val pinned = liveHttpGrants().firstOrNull { grant ->
            runCatching { HttpPolicy.assertRequest(url, grant.hosts) }.isSuccess
        } ?: return BoundCall(call, rejected = ToolResult.Denied("No single current Skill grant permits this URL and method"))
        val authorized = { livePinnedGrant(pinned) != null }
        val broker = ToolBroker(
            effectiveCapabilities = setOf("network.http"),
            context = ToolContext(
                search = { _, _, _ -> error("Knowledge access is not part of this HTTP invocation") },
                readDocument = { _, _ -> error("Knowledge access is not part of this HTTP invocation") },
                httpGet = { requestedUrl ->
                    check(requestedUrl == url && authorized()) { "HTTP authorization changed" }
                    HostHttp.get(requestedUrl, pinned.hosts) { host ->
                        // Check the same grant on every redirect's actual DNS path, including
                        // after a slow resolution. Keep the original URL for TLS/SNI.
                        check(authorized()) { "HTTP authorization changed" }
                        val addresses = InetAddress.getAllByName(host).toList()
                        check(authorized()) { "HTTP authorization changed" }
                        addresses
                    }
                },
                allowedHosts = pinned.hosts,
                grantedMethods = setOf("GET"),
            ),
            autoApproveSideEffects = false,
            liveGrant = {
                livePinnedGrant(pinned) ?: pinned.copy(revoked = true, capabilities = emptySet())
            },
        )
        return BoundCall(call, broker = broker, authorized = authorized)
    }

    private fun liveKnowledgeIds(): Set<String> {
        val liveAgentIds = container.agents.get(agentId)?.knowledgeBaseIds?.toSet().orEmpty()
        val existingIds = container.knowledge.listKnowledgeBases().map { it.first }.toSet()
        return snapshotKnowledgeIds intersect liveAgentIds intersect existingIds
    }

    private fun liveHttpGrants(): List<PermissionGrant> {
        val liveAgentIds = container.agents.get(agentId)?.skillIds?.toSet().orEmpty()
        return (snapshotSkillIds intersect liveAgentIds).sorted().flatMap { installId ->
            val installed = container.skills.get(installId)
            if (installed == null || !installed.enabled || installed.classification == CompatibilityClass.E) {
                emptyList()
            } else {
                container.skills.grantsFor(installId).filter { grant ->
                    !grant.revoked && grant.installId == installId && grant.packageHash == installed.packageHash &&
                        grant.grantId.isNotBlank() && grant.revision > 0 &&
                        "network.http" in grant.capabilities && "GET" in grant.methods.map { it.uppercase() } &&
                        grant.hosts.isNotEmpty()
                }.sortedBy { it.grantId }.map { grant ->
                    // Take defensive copies before retaining this exact grant for a callId.
                    grant.copy(capabilities = grant.capabilities.toSet(), knowledgeBaseIds = grant.knowledgeBaseIds.toSet(),
                        hosts = grant.hosts.toSet(), methods = grant.methods.toSet())
                }
            }
        }
    }

    private fun livePinnedGrant(pinned: PermissionGrant): PermissionGrant? {
        if (pinned.installId !in snapshotSkillIds ||
            pinned.installId !in container.agents.get(agentId)?.skillIds.orEmpty()) return null
        val installed = container.skills.get(pinned.installId) ?: return null
        if (!installed.enabled || installed.classification == CompatibilityClass.E || installed.packageHash != pinned.packageHash) return null
        val current = container.skills.grantsFor(pinned.installId).singleOrNull { it.grantId == pinned.grantId } ?: return null
        // Includes installId/packageHash/revision and every resource scope; no union helper.
        // Expiry is time, not scope: an expired grant fails even when the stored row is unchanged.
        if (AuthorizationEvaluator.isExpired(current.scopesJson)) return null
        return current.takeIf { !it.revoked && it == pinned }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
}
