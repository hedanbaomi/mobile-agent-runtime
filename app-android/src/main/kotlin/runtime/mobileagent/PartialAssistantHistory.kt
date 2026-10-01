// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/** Preserve checkpointed answer text as context, never an unresolved tool invocation or error. */
internal fun canIncludePartialAssistant(role: String, status: String, text: String, hasProtocolOrErrorParts: Boolean): Boolean =
    role == "assistant" && status in setOf("STREAMING", "UNKNOWN_OUTCOME", "CANCELLED", "FAILED", "BUDGET_EXHAUSTED") &&
        text.isNotBlank() && !hasProtocolOrErrorParts
