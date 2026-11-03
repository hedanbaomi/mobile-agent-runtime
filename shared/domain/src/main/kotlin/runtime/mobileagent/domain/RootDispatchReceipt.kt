// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class RootDispatchStage { CLAIMED, ATTEMPTED, ACKNOWLEDGED, TERMINAL }

@Serializable
enum class RootDispatchOutcome {
    SUCCEEDED, FAILED, REJECTED_BEFORE_ACCEPTED, CANCELLED_BEFORE_ATTEMPT, UNKNOWN_OUTCOME,
}

@Serializable
enum class RootDisclosureState { PENDING, RELEASED, WITHHELD }

/** Durable facts only. Neither a restored claim nor a receipt grants execution or disclosure. */
@Serializable
data class RootDispatchClaim(
    val approval: RootApprovalBinding,
    val controllerId: String,
    val grantBeforeSha256: String,
    val grantAfterSha256: String,
    val grantAfterRevision: Long,
    val once: Boolean,
    val grantConsumedAt: String?,
    val approvalConsumedAt: String,
    val claimedAt: String,
    val receiptVersion: Int = 1,
) {
    init {
        require(receiptVersion == 1 && approval.approval.decision == ApprovalDecision.APPROVED)
        require(controllerId.matches(Regex("[A-Za-z0-9-]{16,128}")))
        require(listOf(grantBeforeSha256,grantAfterSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(grantAfterRevision == Math.addExact(approval.grantRevision,if (once) 1L else 0L))
        require(once == (grantConsumedAt != null))
        listOfNotNull(grantConsumedAt,approvalConsumedAt,claimedAt).forEach { Instant.parse(it) }
    }

    val requestId: String get() = approval.approval.requestId
    override fun toString(): String = "RootDispatchClaim(authority=${approval.approval.selectedAuthority})"
}

@Serializable
data class RootDispatchReceipt(
    val claim: RootDispatchClaim,
    val stage: RootDispatchStage = RootDispatchStage.CLAIMED,
    val revision: Long = 1,
    val outcome: RootDispatchOutcome? = null,
    val terminalAuditId: String? = null,
    val finishedAt: String? = null,
    val resultSha256: String? = null,
    val closed: Boolean = false,
    val disclosure: RootDisclosureState = RootDisclosureState.PENDING,
) {
    init {
        require(revision > 0)
        require((stage == RootDispatchStage.TERMINAL) ==
            (outcome != null && terminalAuditId != null && finishedAt != null))
        require(stage == RootDispatchStage.TERMINAL || (outcome == null &&
            terminalAuditId == null && finishedAt == null && resultSha256 == null))
        require(resultSha256 == null || resultSha256.matches(Regex("[0-9a-f]{64}")))
        require(!closed || stage == RootDispatchStage.TERMINAL)
        require(closed == (disclosure != RootDisclosureState.PENDING))
        finishedAt?.let { Instant.parse(it) }
    }

    override fun toString(): String = "RootDispatchReceipt(stage=$stage, outcome=$outcome, closed=$closed)"
}
