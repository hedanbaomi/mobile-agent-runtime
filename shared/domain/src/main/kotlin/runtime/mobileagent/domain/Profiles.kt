// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
enum class ApiFormat {
    /** Legacy Chat Completions-compatible providers. */
    OPENAI_COMPATIBLE,
    /** OpenAI Responses API providers using POST /responses. */
    OPENAI_RESPONSES,
}

@Serializable
enum class ModelRole { CHAT, VISION, EMBEDDING, RERANKER }

/**
 * How a model's maximum output cap is configured.
 *
 * AUTO means "follow the provider": unless an explicit advanced-JSON override
 * supplies a cap, no output-limit field is added to the outbound request -- the
 * app does not send 0, null, an enormous value, or a restored 4096/10240.
 * MANUAL keeps [ModelProfile.outputLimit] as the effective cap.
 *
 * Legacy rows and imported snapshots without this field are MANUAL, so an
 * upgrade never silently discards a number the user configured.
 */
@Serializable
enum class OutputLimitMode { AUTO, MANUAL }

/** Advanced-parameter keys that are themselves an explicit output-cap override. */
val ADVANCED_OUTPUT_LIMIT_KEYS = listOf("max_tokens", "max_completion_tokens", "max_output_tokens")

/**
 * The explicit output cap carried by advanced parameters, or null when the JSON
 * does not set one.
 *
 * An explicit advanced value is a manual override: it wins over AUTO, which is
 * why the UI must display it as the effective source instead of claiming
 * "follow the provider" while a fixed cap is sent.
 */
fun advancedOutputLimitOverride(parametersJson: String): Pair<String, Long>? {
    val root = runCatching { Json.parseToJsonElement(parametersJson) as? JsonObject }.getOrNull() ?: return null
    return ADVANCED_OUTPUT_LIMIT_KEYS.firstNotNullOfOrNull { key ->
        val value = (root[key] as? JsonPrimitive)?.longOrNull ?: return@firstNotNullOfOrNull null
        key to value
    }
}

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
    /** Automatic vs manual output cap; appended last so positional call sites keep working. */
    val outputLimitMode: OutputLimitMode = OutputLimitMode.MANUAL,
) {
    /** The cap that must be sent upstream, or `null` when the provider default applies. */
    fun effectiveOutputTokenLimit(): Int? =
        if (outputLimitMode == OutputLimitMode.AUTO) null else outputLimit

    /** True when the app will not add an output-limit field of its own. */
    val followsProviderOutputLimit: Boolean get() = outputLimitMode == OutputLimitMode.AUTO
}

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
