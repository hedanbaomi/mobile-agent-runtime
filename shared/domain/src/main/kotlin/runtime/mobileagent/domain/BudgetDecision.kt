// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

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
fun resolveEffectiveOutputCap(
    mode: OutputLimitMode,
    profileLimit: Int,
    modelParametersJson: String? = null,
    agentOverridesJson: String? = null,
): EffectiveOutputCap {
    val override = listOf(agentOverridesJson, modelParametersJson)
        .firstNotNullOfOrNull { layer -> layer?.let { advancedOutputLimitOverride(it) } }
    if (override != null) {
        return EffectiveOutputCap(override.second.toInt(), OutputCapSource.ADVANCED_OVERRIDE, override.first)
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
fun contextWindowTarget(providerId: String, endpoint: String, modelId: String): String =
    "$providerId|${endpoint.trimEnd('/')}|$modelId"