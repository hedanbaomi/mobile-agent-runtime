// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import kotlinx.serialization.Serializable

/**
 * Explicitly conservative **admission estimate** for planning and guarding one
 * Vision request.
 *
 * This is admission-control metadata, **not** a tokenizer count, provider
 * usage, or verified provider capacity. Fitting this budget never proves that a
 * provider will accept the request.
 *
 * ## Units are not tokens
 * A *unit* is a deliberately conservative UTF-8 byte upper bound of the
 * model-visible text payload plus fixed protocol/image reservations. For
 * well-formed text a byte count usually over-estimates tokens, but that is a
 * heuristic: [imageInputUnits] is a fixed constant that does not model a
 * provider's tile or dimension accounting, and no provider tokenizer is
 * consulted. Units are planning metadata only: never reported as usage, never
 * billed, and never turned into a wire output cap.
 *
 * ## Unknown windows are a policy default, not verified capacity
 * When [contextWindowTokens] is null the planner uses [UNKNOWN_WINDOW_TOKENS]
 * (32768) instead of treating the window as unlimited. That number is a chosen
 * conservative policy, **not** a verified window of any provider. The adapter's
 * final guard falls back to the same number, so a model whose real window is
 * smaller can still reach the provider and fail once with a provider context
 * error; that failure is terminal and must not be retried automatically. Only a
 * resolved, non-null [contextWindowTokens] reflects a window the app actually
 * recorded, and even then the image and tokenizer figures remain estimates.
 *
 * ## AUTO reservation
 * [outputReserveTokens] only subtracts room for the model's own answer from the
 * input planning capacity. It MUST NOT be written to `max_tokens` or any other
 * wire output field; the provider profile owns the real output limit.
 *
 * ## Overflow safety
 * Every arithmetic path saturates at [Long.MAX_VALUE] (or floors at 0), so a
 * pathological profile cannot wrap into a small budget that admits an
 * oversized request.
 *
 * @param contextWindowTokens recorded provider/model input+output window in
 *   tokens, or null when the profile has not resolved one. Null selects
 *   [UNKNOWN_WINDOW_TOKENS]; it does not mean unlimited and is not a promise.
 * @param availableInputUnits optional caller-imposed input cap in units, e.g.
 *   a shared pipeline admission reservation. null means no extra cap.
 * @param outputReserveTokens room reserved for the model answer; planning only,
 *   never a wire output cap.
 * @param imageInputUnits fixed per-image reservation (default 4096, matching
 *   [runtime.mobileagent.provider.RequestInputBudget.IMAGE_UNITS_PER_IMAGE]).
 *   A heuristic reservation, not a measured provider image cost.
 * @param requestOverheadUnits fixed envelope for prompt, schema and parameter
 *   layers the planner cannot see. Callers that measured the exact envelope
 *   should supply the larger value.
 */
@Serializable
data class VisionRequestBudget(
    val contextWindowTokens: Int? = null,
    val availableInputUnits: Long? = null,
    val outputReserveTokens: Int = 4096,
    val imageInputUnits: Long = 4096,
    val requestOverheadUnits: Long = 1024,
) {
    init {
        require(contextWindowTokens == null || contextWindowTokens >= 0) {
            "PIPELINE_INVALID_BUDGET: contextWindowTokens must be null or non-negative"
        }
        require(availableInputUnits == null || availableInputUnits >= 0) {
            "PIPELINE_INVALID_BUDGET: availableInputUnits must be null or non-negative"
        }
        require(outputReserveTokens >= 0) {
            "PIPELINE_INVALID_BUDGET: outputReserveTokens must be non-negative"
        }
        require(imageInputUnits >= 0) {
            "PIPELINE_INVALID_BUDGET: imageInputUnits must be non-negative"
        }
        require(requestOverheadUnits >= 0) {
            "PIPELINE_INVALID_BUDGET: requestOverheadUnits must be non-negative"
        }
    }

    /**
     * Window used for planning. A null profile window falls back to the policy
     * default [UNKNOWN_WINDOW_TOKENS]; that fallback is bounded but not a
     * verified provider capacity.
     */
    val planningWindowTokens: Long
        get() = contextWindowTokens?.toLong() ?: UNKNOWN_WINDOW_TOKENS

    /**
     * Input capacity in conservative units:
     * `min(availableInputUnits ?: unlimited, window - outputReserve)`.
     *
     * A window that is not larger than the output reservation yields 0, which
     * makes every Vision request fail locally instead of dispatching. This is a
     * policy bound computed from possibly-unverified inputs, not a provider
     * guarantee.
     */
    val effectiveInputUnits: Long
        get() {
            val window = planningWindowTokens
            val reserve = outputReserveTokens.toLong()
            val windowInput = if (window > reserve) window - reserve else 0L
            val available = availableInputUnits ?: Long.MAX_VALUE
            return minOf(windowInput, if (available < 0L) 0L else available)
        }

    /**
     * Conservative cost of one Vision request in units.
     *
     * @param text request text actually attached to this crop. Empty for
     *   PAGE_CONTEXT units, exact for PROVEN_LAYOUT units.
     * @param extraText header/continuation/context hints sent alongside the
     *   image. Counted even though the planner does not read their content.
     * @param imageCount inline images attached to this one request.
     */
    fun requestUnits(text: String, extraText: String? = null, imageCount: Int = 1): Long {
        var units = saturatingAddUnits(requestOverheadUnits, conservativeUtf8Units(text))
        extraText?.let { units = saturatingAddUnits(units, conservativeUtf8Units(it)) }
        val images = saturatingMultiplyUnits(imageCount.toLong().coerceAtLeast(0L), imageInputUnits)
        return saturatingAddUnits(units, images)
    }

    /** True when [requestUnits] stays within [effectiveInputUnits]. */
    fun fits(text: String, extraText: String? = null, imageCount: Int = 1): Boolean =
        requestUnits(text, extraText, imageCount) <= effectiveInputUnits

    companion object {
        /**
         * Policy fallback for an unresolved context window. It is never treated
         * as unlimited, but it is also **not** a verified provider capacity; a
         * smaller real window can still surface a terminal provider error.
         */
        const val UNKNOWN_WINDOW_TOKENS = 32_768L
    }
}

/**
 * Conservative UTF-8 byte length of [text].
 *
 * Well-formed text is measured exactly (4 bytes per surrogate pair, 3 bytes
 * per other non-ASCII BMP character). Unpaired surrogates are charged 3 bytes
 * (the UTF-8 length of the replacement character) instead of the JDK encoder's
 * lossy 1-byte `?`, so malformed input can never understate the estimate.
 */
internal fun conservativeUtf8Units(text: String): Long {
    if (text.isEmpty()) return 0L
    var units = 0L
    var index = 0
    while (index < text.length) {
        val code = text[index].code
        when {
            code < 0x80 -> units = saturatingAddUnits(units, 1L)
            code < 0x800 -> units = saturatingAddUnits(units, 2L)
            Character.isHighSurrogate(text[index]) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1]) -> {
                units = saturatingAddUnits(units, 4L)
                index++
            }
            else -> units = saturatingAddUnits(units, 3L)
        }
        index++
    }
    return units
}

/** Saturating addition: overflow clamps to [Long.MAX_VALUE], never wraps. */
internal fun saturatingAddUnits(current: Long, extra: Long): Long = when {
    extra > 0L && current > Long.MAX_VALUE - extra -> Long.MAX_VALUE
    extra < 0L && current < Long.MIN_VALUE - extra -> Long.MIN_VALUE
    else -> current + extra
}

/** Saturating non-negative multiplication: overflow clamps to [Long.MAX_VALUE]. */
internal fun saturatingMultiplyUnits(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left < 0L || right < 0L -> Long.MAX_VALUE
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}
