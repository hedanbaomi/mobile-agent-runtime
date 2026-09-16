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

/**
 * How a model's context window is configured.
 *
 * AUTO means "use the upstream capability when one is known": the app shows the
 * value, its source and the target it was recorded for, and treats an unknown
 * window as unknown instead of inventing a number.  MANUAL keeps the stored
 * [ModelProfile.contextLimit] as the user's explicit override (legacy rows).
 */
@Serializable
enum class ContextLimitMode { AUTO, MANUAL }

/**
 * Where an AUTO context window came from.  UNKNOWN is a first-class state: no
 * value is claimed, and local protection still applies.
 */
@Serializable
enum class ContextLimitSource { UNKNOWN, USER_DECLARED, PROVIDER_METADATA }

/**
 * Frozen identity of the target a recorded window belongs to.  A recorded value
 * stops applying as soon as the provider, endpoint, model or revision changes.
 */
fun contextWindowTargetKey(providerId: String, providerRevision: Int, endpoint: String, modelId: String): String =
    "$providerId|$providerRevision|${endpoint.trimEnd('/')}|$modelId"

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
/** True when any supplied JSON layer already sets an explicit output cap. */
/** Why a budget selection cannot be saved; the UI localizes it, the ViewModel rejects on it. */
enum class BudgetValidationError {
    MANUAL_CONTEXT_REQUIRED,
    DECLARED_WINDOW_INVALID,
    MANUAL_OUTPUT_REQUIRED,
    OUTPUT_EXCEEDS_WINDOW,
}

/**
 * Single source of truth for the editor and the save path.
 *
 * Validation follows the selected mode and the sources the user can see: an
 * AUTO context window may be unknown (and never borrows a hidden legacy
 * number), while a manual output cap is only compared against a window that is
 * actually effective.
 */
/**
 * Correct a reservation ledger with the provider's final usage.
 *
 * `actualTokens == null` means the provider reported nothing: the conservative
 * reservation is kept and the real consumption stays unknown.  The caller must
 * settle one request at most once.
 */
fun settleReservedTokens(ledger: Long, reservation: Int, actualTokens: Int?): Long {
    if (actualTokens == null || reservation <= 0) return ledger
    return (ledger - reservation + actualTokens.coerceAtLeast(0)).coerceAtLeast(0L)
}

/**
 * Admission rule for an explicitly budgeted model invocation: persisted usage
 * plus the unsettled ledger plus this reservation must stay inside the ceiling.
 */
fun admitsModelInvocation(storedTokens: Long, ledger: Long, reservation: Int, ceiling: Int): Boolean =
    reservation > 0 && ceiling > 0 && storedTokens + ledger + reservation <= ceiling.toLong()
fun validateBudgetSelection(
    manualContext: Int?,
    manualOutput: Int?,
    contextMode: ContextLimitMode,
    outputMode: OutputLimitMode,
    declaredWindow: Int?,
    declaredWindowRawFilled: Boolean,
): BudgetValidationError? = when {
    contextMode == ContextLimitMode.MANUAL && manualContext == null -> BudgetValidationError.MANUAL_CONTEXT_REQUIRED
    contextMode == ContextLimitMode.AUTO && declaredWindowRawFilled && declaredWindow == null ->
        BudgetValidationError.DECLARED_WINDOW_INVALID
    outputMode == OutputLimitMode.MANUAL && manualOutput == null -> BudgetValidationError.MANUAL_OUTPUT_REQUIRED
    else -> {
        val effectiveWindow = if (contextMode == ContextLimitMode.MANUAL) manualContext else declaredWindow
        if (outputMode == OutputLimitMode.MANUAL && effectiveWindow != null && manualOutput != null &&
            manualOutput > effectiveWindow
        ) {
            BudgetValidationError.OUTPUT_EXCEEDS_WINDOW
        } else {
            null
        }
    }
}
fun hasAdvancedOutputLimitOverride(vararg parameterJsonLayers: String?): Boolean =
    parameterJsonLayers.any { layer ->
        if (layer.isNullOrBlank()) return@any false
        val root = runCatching { Json.parseToJsonElement(layer) as? JsonObject }.getOrNull() ?: return@any false
        ADVANCED_OUTPUT_LIMIT_KEYS.any { key -> root[key] is JsonPrimitive }
    }
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
    /** Automatic vs manual context window (legacy rows are MANUAL, so nothing is lost). */
    val contextLimitMode: ContextLimitMode = ContextLimitMode.MANUAL,
    /** Window recorded for [contextWindowTarget]; null while unknown. */
    val contextWindowValue: Int? = null,
    val contextWindowSource: ContextLimitSource = ContextLimitSource.UNKNOWN,
    val contextWindowTarget: String? = null,
    val contextWindowCheckedAt: String? = null,
) {
    /** The cap that must be sent upstream, or `null` when the provider default applies. */
    fun effectiveOutputTokenLimit(): Int? =
        if (outputLimitMode == OutputLimitMode.AUTO) null else outputLimit

    /** True when the app will not add an output-limit field of its own. */
    val followsProviderOutputLimit: Boolean get() = outputLimitMode == OutputLimitMode.AUTO

    /**
     * The context window the next request may rely on, or null when it must be
     * treated as unknown.  A recorded value only applies while the frozen target
     * still matches, so a provider/model/endpoint change degrades to unknown
     * instead of silently reusing a stale number.
     */
    fun resolvedContextWindow(currentTargetKey: String): Int? = when {
        contextLimitMode == ContextLimitMode.MANUAL -> contextLimit
        contextWindowSource == ContextLimitSource.UNKNOWN -> null
        !contextWindowTargetMatches(contextWindowTarget, currentTargetKey) -> null
        contextWindowValue == null || contextWindowValue <= 0 -> null
        else -> contextWindowValue
    }

    /** True when a previously recorded AUTO window no longer matches this target. */
    fun contextWindowIsStale(currentTargetKey: String): Boolean =
        contextLimitMode == ContextLimitMode.AUTO &&
            contextWindowSource != ContextLimitSource.UNKNOWN &&
            !contextWindowTargetMatches(contextWindowTarget, currentTargetKey)
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
