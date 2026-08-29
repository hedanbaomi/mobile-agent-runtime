// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

/**
 * The values used by one conversation/run boundary.
 *
 * Every value is copied from the immutable snapshot expansion.  Callers must not
 * re-resolve these objects from the live profile tables while a run is active.
 * Optional model providers are present because an Agent may bind models owned by
 * different providers.
 */
@Serializable
data class SnapshotBinding(
    val snapshot: AgentSnapshot,
    val provider: ProviderProfile,
    val chatModel: ModelProfile,
    val prompt: PromptRevision,
    val agentName: String = "",
    val retrievalMode: String = "explicit",
    val visionModel: ModelProfile? = null,
    val embeddingModel: ModelProfile? = null,
    val rerankerModel: ModelProfile? = null,
    val visionProvider: ProviderProfile? = null,
    val embeddingProvider: ProviderProfile? = null,
    val rerankerProvider: ProviderProfile? = null,
)
