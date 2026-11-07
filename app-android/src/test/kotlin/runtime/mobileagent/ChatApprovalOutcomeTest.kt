// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class ChatApprovalOutcomeTest {
    @ParameterizedTest
    @CsvSource(
        "EXPIRED, CANCELLED, EXPIRED, APPROVAL_EXPIRED, EXPIRED, expired, resource_limit",
        "DENIED, FAILED, DENIED, APPROVAL_DENIED, DENIED, denied, rejected",
        "INVALIDATED, CANCELLED, DENIED, APPROVAL_INVALIDATED, CANCELLED, invalidated, cancelled",
        "UNKNOWN, UNKNOWN_OUTCOME, APPROVED, UNKNOWN_OUTCOME, UNKNOWN_OUTCOME, unknown, unknown",
    )
    fun preservesStoredResultAndLiveAudit(
        outcome: ChatApprovalOutcome, state: String, decision: String, error: String,
        status: String, audit: String, reason: String,
    ) {
        assertEquals(state, outcome.invocationState)
        assertEquals(decision, outcome.permissionDecision)
        assertEquals(error, outcome.errorCode)
        val result = Json.parseToJsonElement(outcome.resultJson).jsonObject
        assertEquals(mapOf("status" to status, "errorCode" to error), result.mapValues { it.value.jsonPrimitive.content })
        assertEquals(audit, outcome.auditState.wireName)
        assertEquals(reason, outcome.reasonCode)
    }
}
