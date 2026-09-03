// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ApiFormat {
    /** Legacy Chat Completions-compatible providers. */
    OPENAI_COMPATIBLE,
    /** OpenAI Responses API providers using POST /responses. */
    OPENAI_RESPONSES,
}

@Serializable
enum class ModelRole { CHAT, VISION, EMBEDDING, RERANKER }

@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val apiFormat: ApiFormat,
    val baseUrl: String,
    val headerSecretRefs: Map<String, String> = emptyMap(),
    val nonSecretHeaders: Map<String, String> = emptyMap(),
    /** Empty only for imported portable snapshots/providers awaiting local credential binding. */
    val secretRef: String = "",
    val revision: Int,
)

@Serializable
data class ModelProfile(
    val id: String,
    val providerId: String,
    val role: ModelRole,
    val modelId: String,
    val capabilities: Set<String>,
    val parameterSchemaJson: String = "{}",
    val contextLimit: Int,
    val outputLimit: Int,
    val revision: Int,
    /** Validated model defaults; the schema above describes allowed values. */
    val parametersJson: String = "{}",
    val endpoint: ModelEndpoint = ModelEndpoint.fromLegacy(role, capabilities),
)

@Serializable
data class AgentProfile(
    val id: String,
    val name: String,
    val promptRevisionId: String,
    val chatProfileId: String,
    val visionProfileId: String? = null,
    val embeddingProfileId: String? = null,
    val rerankerProfileId: String? = null,
    val knowledgeBaseIds: List<String> = emptyList(),
    val skillIds: List<String> = emptyList(),
    val retrievalMode: String = "explicit",
    val revision: Int,
    /** JSON object containing validated per-agent model parameter overrides. */
    val parameterOverridesJson: String = "{}",
    /** JSON object containing the context and retrieval budget policy. */
    val contextPolicyJson: String = "{}",
    /** JSON object containing deterministic permission and degradation settings. */
    val permissionSettingsJson: String = "{}",
)

@Serializable
data class PromptRevision(
    val id: String,
    val agentId: String,
    val parentRevisionId: String? = null,
    val template: String,
    val allowedVariables: Set<String> = setOf("date", "agent_name", "knowledge_bases"),
    val createdAt: String,
)
