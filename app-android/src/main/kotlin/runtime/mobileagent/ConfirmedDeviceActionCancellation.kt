// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.util.UUID
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.ToolResultPart
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.skills.ToolResult

/** A monotonic OS acknowledgement, with no screen content or authority to dispatch anything. */
internal data class ConfirmedDeviceActionReceipt(val callId: String, val invocationId: String) {
    val resultJson: String get() = """{"status":"performed","action_confirmed":true,"observe_again":true,"observation":{"status":"cancelled"}}"""
    val timedOutResultJson: String get() = """{"status":"performed","action_confirmed":true,"observe_again":true,"observation":{"status":"timeout"}}"""

    companion object {
        /** A stall without an OS receipt must retain the ordinary unknown outcome. */
        fun timeoutOutcome(receipt: ConfirmedDeviceActionReceipt?): ToolResult =
            receipt?.let { ToolResult.Value(it.timedOutResultJson) }
                ?: ToolResult.UnknownOutcome("Tool execution timed out; outcome is unknown")
    }
}

/** Cancelled flow collectors cannot emit a result event; settle the known fact before generic cancellation cleanup. */
internal object ConfirmedDeviceActionCancellation {
    fun persist(db: SqlConnection, runId: String, conversationId: String,
        receipt: ConfirmedDeviceActionReceipt): ToolInvocation? = db.transaction {
        val runs = RunRepository(db)
        if (runs.get(runId)?.conversationId != conversationId) return@transaction null
        val invocation = runs.invocations(runId).singleOrNull {
            it.invocationId == receipt.invocationId && it.callId == receipt.callId && it.name == "device_act" &&
                it.permissionDecision == "APPROVED" && it.state in setOf("PENDING", "WAITING_APPROVAL")
        } ?: return@transaction null
        val completed = invocation.copy(state = "SUCCEEDED", resultJson = receipt.resultJson,
            errorCode = null, updatedAt = Utc.nowIso())
        runs.updateInvocation(completed)
        val messageId = UUID.nameUUIDFromBytes("cancelled-action:$runId:${receipt.invocationId}".toByteArray(Charsets.UTF_8)).toString()
        ConversationRepository(db).append(conversationId, MessageRole.TOOL, receipt.resultJson, messageId = messageId,
            parts = listOf(ToolResultPart(receipt.callId, receipt.resultJson, "SUCCEEDED")))
        completed
    }
}
