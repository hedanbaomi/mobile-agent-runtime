// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Frozen run facts recorded at dispatch time.
 *
 * A run is influenced by more than its [AgentSnapshot]: the global root
 * prompt, live Skill instructions, KB generations, current grants and
 * revocations, workspace binding, tool schema, provider config, and policy
 * version all matter.  The manifest records the non-sensitive versions and
 * fingerprints of exactly those inputs so an old session's behavior change
 * can be explained, a failure reproduced, a cached result's disclosure
 * re-authorized, a crashed run recovered, and an audit answered.
 *
 * It never carries secrets, provider-private encrypted reasoning, raw
 * workspace paths/URIs, tokens, or serials — only ids, revisions, hashes,
 * and fingerprints.
 */
@Serializable
data class SkillPin(
    val installId: String,
    val packageHash: String,
    val revision: Int,
)

@Serializable
data class KnowledgePin(
    val knowledgeBaseId: String,
    /** Null while the KB has no READY generation (covered as unavailable). */
    val generationId: String? = null,
    val embeddingSpaceId: String = "",
)

@Serializable
data class GrantPin(
    val grantId: String,
    val revision: Long,
    val revoked: Boolean = false,
)

/**
 * Durable summary of the run's automatic retrieval scope.  Per-retrieval
 * detail (including evidence) lives in message metadata and diagnostics;
 * the manifest keeps the scope fact so a partial run stays explainable.
 * No query text — only ids and reason codes.
 */
@Serializable
data class RetrievalScopePin(
    val requested: List<String> = emptyList(),
    val searched: List<String> = emptyList(),
    /** Entries look like `kbId:REASON`. */
    val unavailable: List<String> = emptyList(),
    val partial: Boolean = false,
    /**
     * KBs whose query vector came from a fresh remote embedding call during
     * this run's retrieval.  Ids only; the durable attempt/vector-cache rows
     * are keyed by SHA-256 query digest, never query text.
     */
    val remoteEmbeddingKbIds: List<String> = emptyList(),
)

@Serializable
data class RunManifest(
    val runId: String,
    val conversationId: String,
    val snapshotId: String,
    val agentRevision: Int,
    val promptRevisionId: String,
    /** Hash of the effective global root prompt text (never the text). */
    val globalRootPromptHash: String = "",
    val providerId: String = "",
    val providerRevision: Int = 0,
    val modelId: String = "",
    val modelRevision: Int = 0,
    val skills: List<SkillPin> = emptyList(),
    val knowledge: List<KnowledgePin> = emptyList(),
    val workspaceId: String? = null,
    /** Durable grant pins (ids + revisions, no scopes or secrets). */
    val grants: List<GrantPin> = emptyList(),
    val policyVersion: Long = 0,
    /** Fingerprint of the exact model-visible tool schema for this run. */
    val toolSchemaFingerprint: String = "",
    /** Opaque budget copy (limits only, no secrets). */
    val budgetJson: String = "{}",
    val retrievalPolicy: String = "",
    /**
     * Explicit per-run model-invoke token allowance for Python Skills.
     * Null means disabled: `model.invoke` stays fail-closed.
     */
    val modelTokenBudget: Int? = null,
    /** Durable summary of the run's automatic retrieval scope. */
    val retrievalScope: RetrievalScopePin = RetrievalScopePin(),
) {
    init {
        require(runId.isNotBlank() && conversationId.isNotBlank() && snapshotId.isNotBlank()) {
            "Run manifest identity must not be blank"
        }
        require(promptRevisionId.isNotBlank()) { "Run manifest prompt revision must not be blank" }
        val budget = runCatching { manifestJson.parseToJsonElement(budgetJson) }.getOrNull()
        require(budget is kotlinx.serialization.json.JsonObject) { "Run manifest budget must be a JSON object" }
        require(modelTokenBudget == null || modelTokenBudget >= 0) { "Model token budget must not be negative" }
    }

    fun toJson(): String = manifestJson.encodeToString(serializer(), this)

    companion object {
        internal val manifestJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        fun fromJson(raw: String): RunManifest = manifestJson.decodeFromString(serializer(), raw)

        fun empty(runId: String, conversationId: String, snapshotId: String, promptRevisionId: String): RunManifest =
            RunManifest(runId, conversationId, snapshotId, 0, promptRevisionId)
    }
}
