// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import runtime.mobileagent.domain.ErrorCode

/**
 * Recognizes the provider's *input window* rejection so it is never reported
 * as an output-budget outcome.
 *
 * Providers use different wording for "your request is too large"; this only
 * matches explicit window/context-length signals.  An unknown 4xx stays a
 * generic rejection instead of being retro-fitted into a cause we cannot see.
 */
object InputOverflowSignal {
    private val markers = listOf(
        "context_length_exceeded",
        "context length",
        "maximum context",
        "max context",
        "context window",
        "too many tokens",
        "reduce the length",
        "input is too long",
        "prompt is too long",
        "maximum number of tokens",
    )

    fun matches(rawBody: String): Boolean {
        val lower = rawBody.lowercase()
        return markers.any { lower.contains(it) }
    }

    /** Terminal failure code for a rejected request, or the supplied fallback. */
    fun failureCode(rawBody: String, fallback: String): String =
        if (matches(rawBody)) ErrorCode.INPUT_OVERFLOW.name else fallback
}
