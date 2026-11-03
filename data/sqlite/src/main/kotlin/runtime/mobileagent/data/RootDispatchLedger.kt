// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.*

internal object RootReceiptDigests {
    fun text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun grant(value: CapabilityGrant): String = text(Json.encodeToString(value))
    fun snapshot(value: RootSnapshotGrantBinding): String = text(Json.encodeToString(value))
}

/** One writer transaction owns every call; this class never authorizes an OS operation. */
internal class RootDispatchLedger(private val db: SqlConnection) {
    fun get(requestId: String): RootDispatchReceipt? = db.query(
        "SELECT * FROM root_dispatch_receipts WHERE request_id=?",listOf(requestId),
    ).singleOrNull()?.let(::decode)

    fun list(): List<RootDispatchReceipt> = db.query("SELECT * FROM root_dispatch_receipts ORDER BY request_id").map(::decode)

    fun insert(receipt: RootDispatchReceipt) {
        require(receipt.stage == RootDispatchStage.CLAIMED && receipt.revision == 1L)
        val c = receipt.claim
        val r = c.approval.request
        val encoded = Json.encodeToString(c)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_CLAIM_BYTES)
        db.execute(
            "INSERT INTO root_dispatch_receipts(request_id,approval_id,run_id,invocation_id,snapshot_id,grant_id,authority,identity_epoch,controller_id,receipt_version,claim_json,stage,revision,closed,disclosure) VALUES(?,?,?,?,?,?,?,?,?,?,?,'CLAIMED',1,0,'PENDING')",
            listOf(c.requestId,c.approval.approval.approvalId,r.runId,r.invocationId,r.snapshotId,r.grantId,
                r.execution.identity.authority.name,r.execution.identity.identityEpoch,c.controllerId,c.receiptVersion,encoded),
        )
        check(get(c.requestId) == receipt) { "Root dispatch claim was not persisted" }
    }

    fun update(current: RootDispatchReceipt, next: RootDispatchReceipt) {
        require(current.claim == next.claim && next.revision == current.revision+1)
        db.execute(
            "UPDATE root_dispatch_receipts SET stage=?,revision=?,outcome=?,terminal_audit_id=?,finished_at=?,result_sha256=?,closed=?,disclosure=? WHERE request_id=? AND revision=?",
            listOf(next.stage.name,next.revision,next.outcome?.name,next.terminalAuditId,next.finishedAt,
                next.resultSha256,if(next.closed) 1 else 0,next.disclosure.name,current.claim.requestId,current.revision),
        )
        check(get(current.claim.requestId) == next) { "Root dispatch transition lost its CAS" }
    }

    private fun decode(row: SqlRow): RootDispatchReceipt {
        val encoded = row.string("claim_json")
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_CLAIM_BYTES)
        val c = Json.decodeFromString<RootDispatchClaim>(encoded)
        val r = c.approval.request
        require(row.long("receipt_version") == 1L && c.requestId == row.string("request_id") &&
            c.approval.approval.approvalId == row.string("approval_id") && r.runId == row.string("run_id") &&
            r.invocationId == row.string("invocation_id") && r.snapshotId == row.string("snapshot_id") &&
            r.grantId == row.string("grant_id") && r.execution.identity.authority.name == row.string("authority") &&
            r.execution.identity.identityEpoch == row.long("identity_epoch") && c.controllerId == row.string("controller_id"))
        return RootDispatchReceipt(c,RootDispatchStage.valueOf(row.string("stage")),row.long("revision"),
            row.string("outcome").takeIf { it.isNotBlank() }?.let(RootDispatchOutcome::valueOf),
            row.string("terminal_audit_id").ifBlank { null },row.string("finished_at").ifBlank { null },
            row.string("result_sha256").ifBlank { null },row.long("closed") == 1L,
            RootDisclosureState.valueOf(row.string("disclosure")))
    }

    companion object { const val MAX_CLAIM_BYTES = 65_536 }
}
