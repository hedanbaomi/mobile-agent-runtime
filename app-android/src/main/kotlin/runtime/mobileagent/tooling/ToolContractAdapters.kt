// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.ToolExecution

internal fun ToolSpec.toLegacySpec() = LegacyToolSpec(
    name = name,
    description = description,
    parametersJson = inputSchema,
    capability = capability?.value.orEmpty(),
    sideEffect = sideEffect,
    schemaVersion = schemaVersion,
)

internal fun ToolResult.toToolExecution(): ToolExecution = when (this) {
    is ToolResult.Value -> ToolExecution.Value(json)
    is ToolResult.Denied -> ToolExecution.Failed(ToolError(reason.toToolErrorCode(ToolErrorCode.CAPABILITY_DENIED)))
    is ToolResult.Invalid -> ToolExecution.Failed(ToolError(reason.toToolErrorCode(ToolErrorCode.INVALID_REQUEST)))
    is ToolResult.Failure -> ToolExecution.Failed(error)
    is ToolResult.UnknownOutcome -> ToolExecution.Unknown(ToolError(reason.toToolErrorCode(ToolErrorCode.UNKNOWN_OUTCOME)))
    ToolResult.NeedsApproval -> ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
}

private fun String.toToolErrorCode(fallback: ToolErrorCode): ToolErrorCode =
    runCatching { ToolErrorCode.valueOf(this) }.getOrDefault(fallback)

internal fun ApprovalDecision.toToolExecution(): ToolExecution = when (this) {
    is ApprovalDecision.Approved -> ToolExecution.Failed(ToolError(ToolErrorCode.INTERNAL_ERROR))
    is ApprovalDecision.Required -> ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED))
    is ApprovalDecision.Rejected -> ToolExecution.Failed(ToolError(code))
}
