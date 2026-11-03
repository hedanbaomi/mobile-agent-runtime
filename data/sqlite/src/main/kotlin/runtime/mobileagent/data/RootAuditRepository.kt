// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.*

/**
 * Atomically appends the ordinary audit pair and frozen Root context. Audit must
 * remain possible after revocation or uncertain dispatch; it does not reauthorize
 * the operation. Denials before an approval exists need the ordinary audit path.
 */
class RootAuditRepository(private val db: SqlConnection) {
    fun append(detail: ToolAuditDetail, approval: RootApprovalBinding): RootAuditBinding = db.transaction {
        require(RootApprovalRepository(db).stored(approval.approval.approvalId) == approval) {
            "Root approval evidence is missing"
        }
        val canonical = requireNotNull(ApprovalRepository(db).get(approval.approval.approvalId))
        val frozen = approval.approval
        val consumed = frozen.decision == ApprovalDecision.APPROVED &&
            canonical.decision == ApprovalDecision.CONSUMED && canonical.consumedAt != null &&
            canonical.copy(decision=ApprovalDecision.APPROVED,consumedAt=null) == frozen
        require(canonical == frozen || consumed)
        require(detail.requestId == approval.approval.requestId && detail.agentId == approval.approval.agentId &&
            detail.authority == approval.approval.selectedAuthority && detail.capability == approval.capability &&
            detail.workspaceId == approval.workspaceId && detail.approvalId == approval.approval.approvalId &&
            detail.policyVersion == approval.request.execution.policyVersion && detail.skillId == null)
        require(detail.dangerousMode == approval.approval.dangerousMode &&
            detail.commandSha256 == approval.approval.commandHash && detail.cwdSha256 == approval.approval.cwdHash)
        val binding = RootAuditBinding(detail.auditId,approval)
        val existing = get(detail.auditId)
        require(existing == null || existing == binding) { "Root audit context is immutable" }
        val encoded = Json.encodeToString(binding)
        require(encoded.length <= MAX_BINDING_CHARS)
        check(AuditRepository(db).append(detail,approval.request.runId) == detail)
        if (existing == null) {
            db.execute("INSERT INTO root_audit_bindings(audit_id,binding_json) VALUES(?,?)",listOf(detail.auditId,encoded))
        }
        check(get(detail.auditId) == binding) { "Root audit context was not persisted" }
        binding
    }

    /** Read historical metadata; callers must never treat an audit row as an authorization. */
    fun get(auditId: String): RootAuditBinding? {
        val encoded = db.query("SELECT binding_json FROM root_audit_bindings WHERE audit_id=?",listOf(auditId))
            .singleOrNull()?.string("binding_json") ?: return null
        if (encoded.length > MAX_BINDING_CHARS) return null
        return runCatching { Json.decodeFromString<RootAuditBinding>(encoded) }.getOrNull()?.takeIf { it.auditId == auditId }
    }

    companion object {
        private const val MAX_BINDING_CHARS = 65_536
        internal fun install(db: SqlConnection) {
            // History outlives pending/approval cleanup; only deletion of its own ordinary audit cascades.
            db.execute("CREATE TABLE IF NOT EXISTS root_audit_bindings(audit_id TEXT NOT NULL PRIMARY KEY REFERENCES tool_audit_details(audit_id) ON DELETE CASCADE,binding_json TEXT NOT NULL CHECK(length(binding_json) BETWEEN 1 AND 65536))")
            // The insert trigger also bounds older unpublished tables lacking the new CHECK.
            db.execute("CREATE TRIGGER IF NOT EXISTS root_audit_bindings_bounded BEFORE INSERT ON root_audit_bindings WHEN length(NEW.binding_json) NOT BETWEEN 1 AND 65536 BEGIN SELECT RAISE(ABORT,'Root audit context exceeds its bound'); END")
            db.execute("CREATE TRIGGER IF NOT EXISTS root_audit_bindings_immutable BEFORE UPDATE ON root_audit_bindings BEGIN SELECT RAISE(ABORT,'Root audit context is immutable'); END")
        }
    }
}
