// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

internal enum class QuickStartStep { MODEL, AGENT, MESSAGE, COMPLETE }

/** Persistent facts, not a wizard counter: deletion and failed sends cannot report completion. */
internal fun quickStartStep(hasChatModel: Boolean, hasAgent: Boolean, hasCompletedRun: Boolean): QuickStartStep = when {
    hasCompletedRun -> QuickStartStep.COMPLETE
    !hasChatModel -> QuickStartStep.MODEL
    !hasAgent -> QuickStartStep.AGENT
    else -> QuickStartStep.MESSAGE
}
