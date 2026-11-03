// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.*

/**
 * Owns process-local Root prompts and their atomic terminal evidence. A foreground
 * controller owns approve/deny; dispatch still requires live OS proof and a separate
 * request-owned consumption receipt. Never use a generic approval alone for Root.
 * The App controller must retain one instance per local database; replacing it
 * intentionally loses pending prompts. Database uniqueness also guards multiple instances.
 */
class RootApprovalRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Pending(val binding: RootApprovalBinding, val started: Long)
    private val pending = linkedMapOf<String,Pending>()

    @Synchronized
    fun createPending(request: RootApprovalRequest): RootApprovalBinding {
        pending.filterValues { expired(it) }.keys.toList().forEach { id ->
            runCatching { finish(id, ApprovalDecision.EXPIRED) }.onFailure { pending.remove(id) }
        }
        require(pending.size < 128) { "Too many pending Root approvals" }
        require(pending.values.none { it.binding.request.invocationId == request.invocationId }) {
            "This invocation already has a pending Root approval"
        }
        val binding = db.transaction {
            requireNoTerminalInvocation(request.invocationId)
            val snapshot = currentSnapshot(request)
            val owner = invocationOwner(request, approving=true)
            if (owner.string("name") == "file_grep") {
                require(
                    RootToolExposureGate(db, clock).hasCompositeFileListGrant(
                        snapshotId = request.snapshotId,
                        workspaceId = requireNotNull(snapshot.grant.workspaceId),
                        requiredPathScope = snapshot.grant.pathScope,
                        execution = request.execution,
                        taskIdentity = request.taskIdentity,
                        sessionIdentity = request.sessionIdentity,
                    ),
                ) { "file_grep requires an active file.list grant in the same snapshot" }
            }
            val policy = AuthorityPolicyRepository(db).getPolicy()
            val approval = ApprovalRecord(
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),owner.string("call_id"),
                snapshot.grant.agentId,commandHash=request.commandHash,cwdHash=request.cwdHash,
                selectedAuthority=request.execution.identity.authority,dangerousMode=policy.dangerousMode,
                toolSchemaVersion=request.toolSchemaVersion,policyVersion=request.execution.policyVersion,
                configSnapshotHash=request.configSnapshotHash,createdAt=clock(),
                expiresAt=Instant.parse(clock()).plusMillis(PENDING_TTL_MILLIS).toString(),
            )
            RootApprovalBinding(request,snapshotHash(snapshot),snapshot.grant.revision,snapshot.grant.capability,
                requireNotNull(snapshot.grant.workspaceId),approval,owner.string("name"),argumentsHash(owner))
        }
        pending[binding.approval.approvalId] = Pending(binding,elapsedMillis())
        return binding
    }

    @Synchronized
    fun approve(approvalId: String): RootApprovalBinding = finish(approvalId,ApprovalDecision.APPROVED)

    @Synchronized
    fun deny(approvalId: String): RootApprovalBinding = finish(approvalId,ApprovalDecision.DENIED)

    /** Strict local preflight only. A consumed generic approval is deliberately rejected. */
    fun getCurrent(approvalId: String, request: RootApprovalRequest): RootApprovalBinding? = db.transaction {
        val binding = stored(approvalId) ?: return@transaction null
        if (binding.request != request || binding.approval.decision != ApprovalDecision.APPROVED) return@transaction null
        if (ApprovalRepository(db).get(approvalId) != binding.approval || approvalExpired(binding.approval)) return@transaction null
        if (runCatching { validateCurrent(binding, approving=false) }.isFailure) return@transaction null
        binding
    }

    private fun finish(approvalId: String, decision: ApprovalDecision): RootApprovalBinding {
        val current = pending[approvalId] ?: error("Root approval is not pending in this process")
        val actual = if (expired(current)) ApprovalDecision.EXPIRED else decision
        val binding = current.binding.copy(approval=current.binding.approval.copy(decision=actual))
        db.transaction {
            require(ApprovalRepository(db).get(approvalId) == null && stored(approvalId) == null) {
                "An existing approval cannot acquire Root trust"
            }
            requireNoTerminalInvocation(binding.request.invocationId)
            if (actual == ApprovalDecision.APPROVED) validateCurrent(binding, approving=true)
            val encoded = Json.encodeToString(binding)
            require(encoded.length <= MAX_BINDING_CHARS)
            check(ApprovalRepository(db).save(binding.approval) == binding.approval)
            db.execute("INSERT INTO root_approval_bindings(approval_id,request_id,run_id,invocation_id,binding_json,binding_version) VALUES(?,?,?,?,?,1)",
                listOf(approvalId,binding.approval.requestId,binding.request.runId,binding.request.invocationId,encoded))
            check(stored(approvalId) == binding) { "Root approval binding was not persisted" }
        }
        pending.remove(approvalId)
        return binding
    }

    private fun currentSnapshot(request: RootApprovalRequest): RootSnapshotGrantBinding {
        val state = requireLocalRootState(db,request.execution)
        val snapshot = requireNotNull(RootSnapshotGrantRepository(db,clock).revalidate(
            request.snapshotId,request.grantId,request.execution,request.taskIdentity,request.sessionIdentity))
        if (snapshot.grant.capability.value == CapabilityId.SHELL_EXECUTE) {
            require(state.consent.shellEnabled && state.consent.shellRevision == request.execution.shellConsentRevision &&
                AuthorityPolicyRepository(db).getPolicy().dangerousMode != DangerousMode.DISABLED)
        }
        return snapshot
    }

    private fun validateCurrent(binding: RootApprovalBinding, approving: Boolean) {
        val snapshot = currentSnapshot(binding.request)
        require(snapshotHash(snapshot) == binding.grantSnapshotSha256 && snapshot.grant.revision == binding.grantRevision &&
            snapshot.grant.capability == binding.capability && snapshot.grant.workspaceId == binding.workspaceId &&
            snapshot.grant.agentId == binding.approval.agentId)
        val owner = invocationOwner(binding.request,approving)
        require(owner.string("call_id") == binding.approval.callId && owner.string("name") == binding.invocationName &&
            argumentsHash(owner) == binding.invocationArgumentsSha256)
        if (binding.invocationName == "file_grep") {
            require(
                RootToolExposureGate(db, clock).hasCompositeFileListGrant(
                    snapshotId = binding.request.snapshotId,
                    workspaceId = binding.workspaceId,
                    requiredPathScope = snapshot.grant.pathScope,
                    execution = binding.request.execution,
                    taskIdentity = binding.request.taskIdentity,
                    sessionIdentity = binding.request.sessionIdentity,
                ),
            )
        }
        require(AuthorityPolicyRepository(db).getPolicy().dangerousMode == binding.approval.dangerousMode)
        require(!approvalExpired(binding.approval))
    }

    private fun invocationOwner(request: RootApprovalRequest, approving: Boolean): SqlRow {
        val row = db.query(
            "SELECT i.call_id,i.name,i.arguments_json,i.state AS invocation_state,r.snapshot_id,r.state,r.manifest_json FROM tool_invocations i JOIN runs r ON r.run_id=i.run_id WHERE i.invocation_id=? AND i.run_id=?",
            listOf(request.invocationId,request.runId),
        ).single()
        RootRunManifest.requireMatches(row.string("manifest_json"), request.execution)
        require(row.string("snapshot_id") == request.snapshotId && row.string("invocation_state") == "PENDING")
        val states = when {
            request.invocationId.startsWith("rchild.") -> ACTIVE_CHILD_RUN_STATES
            approving -> setOf(RunStatus.WAITING_TOOL_APPROVAL.name)
            else -> setOf(RunStatus.WAITING_TOOL_APPROVAL.name,RunStatus.TOOL_EXECUTING.name)
        }
        require(row.string("state") in states)
        return row
    }

    private fun requireNoTerminalInvocation(invocationId: String) {
        require(db.query("SELECT approval_id FROM root_approval_bindings WHERE invocation_id=?",listOf(invocationId)).isEmpty()) {
            "An invocation cannot receive a second Root approval"
        }
    }

    private fun snapshotHash(snapshot: RootSnapshotGrantBinding): String = digest(Json.encodeToString(snapshot))

    private fun argumentsHash(row: SqlRow): String = digest(row.string("arguments_json"))

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun expired(value: Pending): Boolean =
        elapsedMillis() - value.started >= PENDING_TTL_MILLIS || approvalExpired(value.binding.approval)

    private fun approvalExpired(approval: ApprovalRecord): Boolean =
        approval.expiresAt?.let { !Instant.parse(it).isAfter(Instant.parse(clock())) } ?: true

    internal fun stored(approvalId: String): RootApprovalBinding? {
        val row = db.query("SELECT * FROM root_approval_bindings WHERE approval_id=?",listOf(approvalId))
            .singleOrNull() ?: return null
        if (row.long("binding_version") != 1L) return null
        val encoded = row.string("binding_json")
        if (encoded.length > MAX_BINDING_CHARS) return null
        val binding = runCatching { Json.decodeFromString<RootApprovalBinding>(encoded) }.getOrNull() ?: return null
        return binding.takeIf { it.approval.approvalId == approvalId && it.approval.requestId == row.string("request_id") &&
            it.request.runId == row.string("run_id") && it.request.invocationId == row.string("invocation_id") }
    }

    companion object {
        private const val PENDING_TTL_MILLIS = 300_000L
        private const val MAX_BINDING_CHARS = 65_536
        internal fun install(db: SqlConnection) {
            db.execute("CREATE TABLE IF NOT EXISTS root_approval_bindings(approval_id TEXT NOT NULL PRIMARY KEY REFERENCES approval_records(approval_id) ON DELETE CASCADE,request_id TEXT NOT NULL UNIQUE,run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,invocation_id TEXT NOT NULL REFERENCES tool_invocations(invocation_id) ON DELETE CASCADE,binding_json TEXT NOT NULL,binding_version INTEGER NOT NULL DEFAULT 0 CHECK(binding_version IN(0,1)))")
            val columns = db.query("PRAGMA table_info(root_approval_bindings)").map { it.string("name") }
            if ("binding_version" !in columns) {
                // Older unpublished evidence is retained but never upgraded into effective trust.
                db.execute("ALTER TABLE root_approval_bindings ADD COLUMN binding_version INTEGER NOT NULL DEFAULT 0 CHECK(binding_version IN(0,1))")
            }
            db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_root_approval_invocation ON root_approval_bindings(invocation_id) WHERE binding_version=1")
            db.execute("CREATE TRIGGER IF NOT EXISTS root_approval_bindings_immutable BEFORE UPDATE ON root_approval_bindings BEGIN SELECT RAISE(ABORT,'Root approval binding is immutable'); END")
        }
    }
}
