// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import runtime.mobileagent.diagnostics.DiagnosticApprovalState

/** The persisted invocation, redacted result and live audit describe the same approval outcome. */
internal enum class ChatApprovalOutcome(
    val invocationState: String,
    val permissionDecision: String,
    val errorCode: String,
    resultStatus: String,
    val auditState: DiagnosticApprovalState,
    val reasonCode: String,
) {
    EXPIRED("CANCELLED", "EXPIRED", "APPROVAL_EXPIRED", "EXPIRED", DiagnosticApprovalState.EXPIRED, "resource_limit"),
    INVALIDATED("CANCELLED", "DENIED", "APPROVAL_INVALIDATED", "CANCELLED", DiagnosticApprovalState.INVALIDATED, "cancelled"),
    DENIED("FAILED", "DENIED", "APPROVAL_DENIED", "DENIED", DiagnosticApprovalState.DENIED, "rejected"),
    UNKNOWN("UNKNOWN_OUTCOME", "APPROVED", "UNKNOWN_OUTCOME", "UNKNOWN_OUTCOME", DiagnosticApprovalState.UNKNOWN, "unknown");

    val resultJson = buildJsonObject {
        put("status", resultStatus)
        put("errorCode", errorCode)
    }.toString()
}
