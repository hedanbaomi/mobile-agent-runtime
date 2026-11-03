// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.Instant
import runtime.mobileagent.domain.*

internal val ACTIVE_CHILD_RUN_STATES = setOf(
    RunStatus.WAITING_TOOL_APPROVAL.name,
    RunStatus.TOOL_EXECUTING.name,
    RunStatus.MODEL_STREAMING.name,
    RunStatus.RETRIEVING.name,
    RunStatus.ASSEMBLING.name,
    RunStatus.VALIDATING.name,
    "RUNNING",
)

/** Called only after the repository has checked the live opaque permit's ownership. */
internal class RootDispatchGuard(private val db: SqlConnection, private val clock: () -> String) {
    fun validate(
        permit: RootDispatchPermit,
        receipt: RootDispatchReceipt,
        invocationState: String,
        allowReleased: Boolean = false,
    ) {
        require(receipt.claim == permit.claim && (allowReleased || !receipt.closed))
        val c = receipt.claim
        val b = c.approval
        val r = b.request
        require(RootApprovalRepository(db,clock).stored(b.approval.approvalId) == b)
        requireConsumedApproval(c)
        require(RootReceiptDigests.snapshot(permit.snapshot) == b.grantSnapshotSha256 &&
            RootReceiptDigests.grant(permit.snapshot.grant) == c.grantBeforeSha256 &&
            RootReceiptDigests.grant(permit.afterGrant) == c.grantAfterSha256 &&
            permit.afterGrant.revision == c.grantAfterRevision && permit.afterGrant.consumedAt == c.grantConsumedAt)
        require(RootSnapshotGrantRepository(db,clock).revalidateForReceipt(permit.snapshot,permit.afterGrant,
            r.execution,r.taskIdentity,r.sessionIdentity) == permit.snapshot)
        val state = requireLocalRootState(db,r.execution)
        if (b.capability.value == CapabilityId.SHELL_EXECUTE) {
            require(state.consent.shellEnabled && r.execution.shellConsentRevision > 0 &&
                state.consent.shellRevision == r.execution.shellConsentRevision)
        }
        if (b.invocationName == "file_grep") {
            require(
                RootToolExposureGate(db, clock).hasCompositeFileListGrant(
                    snapshotId = r.snapshotId,
                    workspaceId = b.workspaceId,
                    requiredPathScope = permit.snapshot.grant.pathScope,
                    execution = r.execution,
                    taskIdentity = r.taskIdentity,
                    sessionIdentity = r.sessionIdentity,
                ),
            )
        }
        require(AuthorityPolicyRepository(db).getPolicy().dangerousMode == b.approval.dangerousMode)
        val row = db.query(
            "SELECT i.call_id,i.name,i.arguments_json,i.state AS invocation_state,r.state,r.snapshot_id,r.manifest_json FROM tool_invocations i JOIN runs r ON r.run_id=i.run_id WHERE i.invocation_id=? AND i.run_id=?",
            listOf(r.invocationId,r.runId),
        ).single()
        RootRunManifest.requireMatches(row.string("manifest_json"), r.execution)
        val validRunStates = if (allowReleased || r.invocationId.startsWith("rchild.")) {
            ACTIVE_CHILD_RUN_STATES
        } else {
            setOf(RunStatus.TOOL_EXECUTING.name)
        }
        require(row.string("state") in validRunStates && row.string("snapshot_id") == r.snapshotId &&
            row.string("invocation_state") == invocationState && row.string("call_id") == b.approval.callId &&
            row.string("name") == b.invocationName && RootReceiptDigests.text(row.string("arguments_json")) == b.invocationArgumentsSha256)
    }

    fun validateReleasedReceipt(permit: RootDispatchPermit, receipt: RootDispatchReceipt): Boolean {
        if (!receipt.closed || receipt.disclosure != RootDisclosureState.RELEASED ||
            receipt.stage != RootDispatchStage.TERMINAL || receipt.outcome != RootDispatchOutcome.SUCCEEDED
        ) return false
        return runCatching { validate(permit, receipt, "SUCCEEDED", allowReleased = true) }.isSuccess
    }

    fun requireConsumedApproval(claim: RootDispatchClaim) {
        val frozen = claim.approval.approval
        require(Instant.parse(claim.approvalConsumedAt).isBefore(Instant.parse(requireNotNull(frozen.expiresAt))))
        val live = requireNotNull(ApprovalRepository(db,clock).get(frozen.approvalId))
        require(live.decision == ApprovalDecision.CONSUMED && live.consumedAt == claim.approvalConsumedAt &&
            live.copy(decision=ApprovalDecision.APPROVED,consumedAt=null) == frozen)
    }

    fun moveInvocation(claim: RootDispatchClaim, from: String, to: String) {
        val r = claim.approval.request
        db.execute("UPDATE tool_invocations SET state=?,updated_at=? WHERE invocation_id=? AND run_id=? AND state=?",
            listOf(to,clock(),r.invocationId,r.runId,from))
        val row = db.query("SELECT run_id,state FROM tool_invocations WHERE invocation_id=?",listOf(r.invocationId)).single()
        check(row.string("run_id") == r.runId && row.string("state") == to) { "Root invocation transition lost its CAS" }
    }
}
