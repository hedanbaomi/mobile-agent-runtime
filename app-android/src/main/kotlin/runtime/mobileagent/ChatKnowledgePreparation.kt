// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.SkillRepository
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.knowledge.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ChatKnowledgeInputs(
    val agentRevision: Int,
    val knowledgeBaseIds: List<String>,
    val skillIds: Set<String>,
    val retrieval: RetrievalResult,
)

/** Resolves live revocation against the frozen snapshot before retrieval; called on storage IO. */
internal class ChatKnowledgePreparation(
    private val agents: AgentRepository,
    private val skills: SkillRepository,
    private val knowledge: KnowledgeRepository,
) {
    suspend fun prepare(snapshot: AgentSnapshot, runId: String, text: String, automatic: Boolean): ChatKnowledgeInputs {
        val live = agents.get(snapshot.agentId) ?: error("Agent authorization was removed")
        val bases = snapshot.knowledgeBaseIds.intersect(live.knowledgeBaseIds.toSet())
            .intersect(knowledge.listKnowledgeBases().map { it.first }.toSet()).toList()
        val installed = snapshot.skillIds.intersect(live.skillIds.toSet())
            .filter { skills.get(it)?.enabled == true }.toSet()
        currentCoroutineContext().ensureActive()
        val retrieval = if (automatic && text.isNotBlank()) knowledge.retrieve(runId, text, 8, bases)
        else RetrievalResult(emptyList(), emptyList(), listOf("retrieval disabled for this run"),
            RetrievalCoverage(requested = bases, searched = emptyList(),
                unavailable = bases.map { UnavailableSource(it, RetrievalUnavailableReason.RETRIEVAL_DISABLED) }))
        return ChatKnowledgeInputs(live.revision, bases, installed, retrieval)
    }
}
