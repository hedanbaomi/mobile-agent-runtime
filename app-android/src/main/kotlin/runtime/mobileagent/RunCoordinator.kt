// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.domain.GrantPin
import runtime.mobileagent.domain.KnowledgePin
import runtime.mobileagent.domain.RetrievalScopePin
import runtime.mobileagent.domain.RunManifest
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.SkillPin
import runtime.mobileagent.skills.ToolSpec

/**
 * Immutable facts frozen once per run and shared by the prompt build and the
 * manifest stamp (b07 follow-up finding D).  Every field holds the exact
 * object/version consumed by execution — never a later re-read — so the
 * manifest describes what ran, not a nearby database state.
 */
data class PreparedRunFacts(
    /** Exact global-root-prompt text sent to the model. */
    val rootPrompt: String,
    /** Hash of [rootPrompt]; the text itself never enters the manifest. */
    val rootPromptHash: String,
    /** Skill install/package/revision pins shared by prompt and manifest. */
    val skillPins: List<SkillPin>,
    /** Frozen Skill instruction texts used for the prompt build. */
    val skillInstructions: List<String>,
    /** Exact generation pins consumed by this run's retrieval. */
    val knowledgePins: List<KnowledgePin>,
    /** Grant pins from the run's frozen authorization context. */
    val grants: List<GrantPin>,
    /** Fingerprint of the exact model-visible frozen tool specs. */
    val toolSchemaFingerprint: String,
    /** Durable retrieval-scope summary (ids and reason codes only). */
    val retrievalScope: RetrievalScopePin,
)

/**
 * Owns run execution identity for the process.
 *
 * ChatViewModel today mixes session state, retrieval, tool wiring, approval,
 * streaming, checkpointing, and error recovery.  This coordinator is the
 * stable seam that outlives any single UI page: a run is registered to an
 * owner key (for example a conversation) at [prepare], only that owner may
 * [cancel] or [terminalize] it, and the frozen [RunManifest] is stamped once
 * dispatch facts are known.  Switching UI pages never changes the run owner.
 *
 * Non-goals for this round: parallel multi-task runs, a second run
 * repository, and moving the streaming/tool-loop collectors (they stay in
 * the ViewModel and call into this seam).
 */
class RunCoordinator(private val runs: RunRepository) {
    private val lock = Any()
    /** runId -> owner key.  Process-local; durability lives in the run row. */
    private val owners = linkedMapOf<String, String>()

    /**
     * Persist a new run row and register its owner.  Re-preparing the same
     * run id by the same owner is idempotent; a different owner is rejected.
     */
    fun prepare(record: RunRecord, ownerKey: String): RunRecord {
        require(ownerKey.isNotBlank()) { "Run owner must not be blank" }
        val saved = runs.save(record)
        synchronized(lock) {
            val current = owners[saved.runId]
            require(current == null || current == ownerKey) { "Run ${saved.runId} is already owned" }
            owners[saved.runId] = ownerKey
        }
        return saved
    }

    fun ownerOf(runId: String): String? = synchronized(lock) { owners[runId] }

    /**
     * Stamp the frozen manifest once dispatch facts (tool schema, KB
     * generations, grants) are known.  Identity must match the prepared run;
     * manifests never overwrite terminal UNKNOWN_OUTCOME rows (the repository
     * enforces that invariant).
     */
    fun stampManifest(runId: String, manifest: RunManifest, at: String): RunRecord {
        require(manifest.runId == runId) { "Manifest run identity does not match" }
        val current = runs.get(runId) ?: error("Run $runId does not exist")
        return runs.save(current.copy(manifestJson = manifest.toJson(), updatedAt = at))
    }

    /**
     * Owner-checked cancel: only the registered owner may terminalize the
     * run through this seam.  Returns false when the caller is not the owner
     * (a page switch must not cancel another owner's run).  Terminal rows are
     * left untouched.
     */
    fun cancel(runId: String, ownerKey: String, reason: String, at: String): Boolean {
        synchronized(lock) {
            if (owners[runId] != ownerKey) return false
        }
        val current = runs.get(runId) ?: return false
        if (current.state in TERMINAL) return true
        runs.save(
            current.copy(
                state = RunStatus.CANCELLED,
                stopReason = reason.ifBlank { "cancelled by owner" },
                finishedAt = at,
                updatedAt = at,
            ),
        )
        return true
    }

    /** Release ownership after the run reached a terminal state. */
    fun release(runId: String, ownerKey: String) {
        synchronized(lock) {
            if (owners[runId] == ownerKey) owners.remove(runId)
        }
    }

    companion object {
        private val TERMINAL = setOf(
            RunStatus.COMPLETED,
            RunStatus.CANCELLED,
            RunStatus.FAILED,
            RunStatus.BUDGET_EXHAUSTED,
            RunStatus.UNKNOWN_OUTCOME,
        )

        /** Fingerprint of the exact model-visible tool schema for the manifest. */
        fun toolSchemaFingerprint(specs: List<ToolSpec>): String {
            val canonical = specs.sortedBy { it.name }.joinToString("\n") { "${it.name}\u0000${it.parametersJson}" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /** Explicit per-run model-invoke allowance; null keeps `model.invoke` disabled. */
        fun modelTokenBudget(budgetJson: String): Int? {
            val root = runCatching { Json.parseToJsonElement(budgetJson) as? JsonObject }.getOrNull() ?: return null
            return root["maxModelTokens"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        }

        /** Durable retrieval-scope summary: ids and reason codes, never query text. */
        fun retrievalScopePin(
            coverage: runtime.mobileagent.knowledge.RetrievalCoverage?,
            remoteEmbeddingKbIds: List<String> = emptyList(),
        ): RetrievalScopePin {
            if (coverage == null) return RetrievalScopePin(remoteEmbeddingKbIds = remoteEmbeddingKbIds.toList())
            return RetrievalScopePin(
                requested = coverage.requested.toList(),
                searched = coverage.searched.toList(),
                unavailable = coverage.unavailable.map { "${it.knowledgeBaseId}:${it.reason.name}" },
                partial = coverage.partial,
                remoteEmbeddingKbIds = remoteEmbeddingKbIds.toList(),
            )
        }

        fun assembleManifest(
            runId: String,
            conversationId: String,
            snapshotId: String,
            agentRevision: Int,
            promptRevisionId: String,
            globalRootPromptHash: String,
            providerId: String,
            providerRevision: Int,
            modelId: String,
            modelRevision: Int,
            skills: List<SkillPin>,
            knowledge: List<KnowledgePin>,
            workspaceId: String?,
            grants: List<GrantPin>,
            policyVersion: Long,
            toolSchemaFingerprint: String,
            budgetJson: String,
            retrievalPolicy: String,
            modelTokenBudget: Int?,
            retrievalScope: RetrievalScopePin = RetrievalScopePin(),
        ): RunManifest = RunManifest(
            runId = runId,
            conversationId = conversationId,
            snapshotId = snapshotId,
            agentRevision = agentRevision,
            promptRevisionId = promptRevisionId,
            globalRootPromptHash = globalRootPromptHash,
            providerId = providerId,
            providerRevision = providerRevision,
            modelId = modelId,
            modelRevision = modelRevision,
            skills = skills.sortedBy { it.installId },
            knowledge = knowledge.sortedBy { it.knowledgeBaseId },
            workspaceId = workspaceId,
            grants = grants.sortedBy { it.grantId },
            policyVersion = policyVersion,
            toolSchemaFingerprint = toolSchemaFingerprint,
            budgetJson = budgetJson.ifBlank { "{}" },
            retrievalPolicy = retrievalPolicy,
            modelTokenBudget = modelTokenBudget,
            retrievalScope = retrievalScope,
        )

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
