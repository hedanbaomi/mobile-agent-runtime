// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.ToolExecution

class ToolContractAdaptersTest {
    @Test fun resultKeepsKnownErrorsAndUsesCategoryFallbacksForFreeText() {
        val denied = ToolError(ToolErrorCode.CAPABILITY_DENIED)
        val invalid = ToolError(ToolErrorCode.INVALID_REQUEST)
        val unknown = ToolError(ToolErrorCode.UNKNOWN_OUTCOME)
        val detail = denied.copy(message = "Access denied", userAction = "Select a folder", retryable = true)
        assertEquals(ToolExecution.Value("{}"), ToolResult.Value("{}").toToolExecution())
        assertEquals(ToolExecution.Failed(detail), ToolResult.Failure(detail).toToolExecution())
        assertEquals(ToolExecution.Failed(denied), ToolResult.Denied("unstructured reason").toToolExecution())
        assertEquals(ToolExecution.Failed(invalid), ToolResult.Invalid("unstructured reason").toToolExecution())
        assertEquals(ToolExecution.Unknown(unknown), ToolResult.UnknownOutcome("unstructured reason").toToolExecution())
        assertEquals(ToolExecution.Unknown(invalid), ToolResult.UnknownOutcome(invalid.code.name).toToolExecution())
        assertEquals(ToolExecution.Failed(ToolError(ToolErrorCode.APPROVAL_REQUIRED)), ToolResult.NeedsApproval.toToolExecution())
    }

    @Test fun specForLegacyCallersKeepsTheSchemaVersionCapabilityAndSideEffect() {
        val spec = ToolSpec("file_write", "Write text", "{}", CapabilityId(CapabilityId.FILE_WRITE_TEXT), true, 3)
        assertEquals(LegacyToolSpec("file_write", "Write text", "{}", CapabilityId.FILE_WRITE_TEXT, true, 3), spec.toLegacySpec())
        assertEquals("", spec.copy(capability = null).toLegacySpec().capability)
    }

    @Test fun correlationRequiresOneDistinctRequestAcrossMatchingContexts() {
        val context = ToolExecutionContext("agent", "snap", "round", "session", "task", "config", 1)
        val owners = listOf(context, context.copy(agentId = "other"), context.copy(snapshotId = "other"),
            context.copy(sessionIdentity = "other"), context.copy(taskIdentity = "other"))
        val bindings = owners.associate { it.modelCallKey("call") to "request-a" }.toMutableMap()
        assertEquals(owners.size, bindings.size)
        assertEquals("request-a", bindings.resolveRequestId("call"))
        bindings[context.modelCallKey("call")] = "request-b"
        assertEquals("call", bindings.resolveRequestId("call"))
        assertEquals("runtime-id", bindings.resolveRequestId("runtime-id"))
    }
}
