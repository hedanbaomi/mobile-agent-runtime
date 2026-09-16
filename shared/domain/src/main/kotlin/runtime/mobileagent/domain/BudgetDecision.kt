// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Where the output cap that will actually be sent comes from. */
enum class OutputCapSource {
    /** An explicit advanced-parameter (or per-call) override; it replaces the profile default. */
    ADVANCED_OVERRIDE,
    /** The profile's MANUAL number. */
    PROFILE_MANUAL,
    /** Nothing is sent: the provider default applies (AUTO). */
    PROVIDER_DEFAULT,
}

/**
 * The one effective output decision consumed by payload construction, adapter
 * validation, local reservation, UI copy and the Python broker.
 *
 * Policy (explicit, single): an advanced-parameter cap is a user override and
 * **replaces** the profile value; the profile MANUAL number stays the default
 * when no override is present.  Nothing is invented for AUTO.
 */
data class EffectiveOutputCap(
    val value: Int?,
    val source: OutputCapSource,
    val key: String? = null,
) {
    val isAdvancedOverride: Boolean get() = source == OutputCapSource.ADVANCED_OVERRIDE
}

/**
 * Resolve the effective cap from the profile mode and the JSON layers, most
 * specific last (agent overrides win over model parameters, as the parameter
 * merger does).
 */
/** Why an output-cap layer cannot be trusted as written. */
enum class OutputCapValidationError {
    NOT_AN_INTEGER,
    NON_POSITIVE,
    OUT_OF_RANGE,
    AMBIGUOUS_ALIASES,
}

/**
 * Validate the raw advanced-parameter values *before* any decision narrows or
 * normalizes them.  A layer that states two different output aliases is an
 * ambiguity; a value that is not a positive Int-range integer must be rejected
 * rather than silently narrowed (4294975488 must not become 8192).
 */
fun validateOutputCapLayers(vararg parameterJsonLayers: String?): OutputCapValidationError? {
    parameterJsonLayers.forEach { layer ->
        if (layer.isNullOrBlank()) return@forEach
        val root = runCatching { Json.parseToJsonElement(layer) as? JsonObject }.getOrNull() ?: return@forEach
        val stated = ADVANCED_OUTPUT_LIMIT_KEYS.mapNotNull { key ->
            root[key]?.let { element -> key to element }
        }
        if (stated.isEmpty()) return@forEach
        val values = mutableListOf<Pair<String, Long>>()
        for ((key, element) in stated) {
            val primitive = element as? JsonPrimitive
            if (primitive == null || primitive.isString) return OutputCapValidationError.NOT_AN_INTEGER
            val value = primitive.longOrNull ?: return OutputCapValidationError.NOT_AN_INTEGER
            if (value <= 0L) return OutputCapValidationError.NON_POSITIVE
            if (value > Int.MAX_VALUE.toLong()) return OutputCapValidationError.OUT_OF_RANGE
            values += key to value
        }
        if (values.map { it.second }.distinct().size > 1) return OutputCapValidationError.AMBIGUOUS_ALIASES
    }
    return null
}
fun resolveEffectiveOutputCap(
    mode: OutputLimitMode,
    profileLimit: Int,
    modelParametersJson: String? = null,
    agentOverridesJson: String? = null,
): EffectiveOutputCap {
    val override = listOf(agentOverridesJson, modelParametersJson)
        .firstNotNullOfOrNull { layer -> layer?.let { advancedOutputLimitOverride(it) } }
    if (override != null) {
        val value = override.second
        require(value in 1..Int.MAX_VALUE.toLong()) { "Advanced output cap is out of range" }
        return EffectiveOutputCap(value.toInt(), OutputCapSource.ADVANCED_OVERRIDE, override.first)
    }
    return if (mode == OutputLimitMode.MANUAL) {
        EffectiveOutputCap(profileLimit, OutputCapSource.PROFILE_MANUAL)
    } else {
        EffectiveOutputCap(null, OutputCapSource.PROVIDER_DEFAULT)
    }
}

/**
 * Independent probe budget.  A probe never reads the numeric column that the
 * mode declares ignored: AUTO uses its own task-local cap, MANUAL still refuses
 * to exceed the user's own number.
 */
fun probeOutputTokenLimit(mode: OutputLimitMode, profileLimit: Int, probeMax: Int): Int =
    if (mode == OutputLimitMode.MANUAL) minOf(profileLimit.coerceAtLeast(1), probeMax) else probeMax

/**
 * Identity of the upstream capability a recorded context window belongs to.
 *
 * Only provider, endpoint and model id select the upstream capability: local
 * profile revisions (parameter edits, capability-probe bookkeeping) must not
 * invalidate a window the user declared for the same target.
 */
/**
 * Does a recorded target still describe the live upstream capability?
 *
 * Accepts both the canonical key and the legacy `provider|revision|endpoint|model`
 * form (the revision segment is dropped): a local profile revision must not
 * invalidate a window the user declared for the same provider/endpoint/model,
 * while a changed endpoint or model id still does.
 */
fun contextWindowTargetMatches(storedTarget: String?, currentTarget: String): Boolean {
    if (storedTarget.isNullOrBlank()) return false
    if (storedTarget == currentTarget) return true
    val parts = storedTarget.split('|')
    if (parts.size != 4) return false
    return "${parts[0]}|${parts[2]}|${parts[3]}" == currentTarget
}
fun contextWindowTarget(providerId: String, endpoint: String, modelId: String): String =
    "$providerId|${endpoint.trimEnd('/')}|$modelId"
