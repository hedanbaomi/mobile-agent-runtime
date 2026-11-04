// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.Instant
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.*

/**
 * Root typed-grant snapshots are born sealed. The callback may only create the new
 * canonical Agent snapshot on this connection; no platform work belongs in the transaction.
 * Revalidation is a local preflight, not OS authentication or an ONCE dispatch receipt.
 * The seal detects drift and incomplete writes; arbitrary writers of the private DB remain trusted.
 */
class RootSnapshotGrantRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun <T> createNewSnapshot(
        snapshotId: String,
        agentId: String,
        grantIds: Set<String>,
        execution: RootExecutionStamp,
        createCanonicalSnapshot: () -> T,
    ): T = db.transaction {
        require(grantIds.size in 1..128)
        require(db.query("SELECT id FROM agent_snapshots WHERE id=?", listOf(snapshotId)).isEmpty()) {
            "Root grants cannot be appended to an existing snapshot"
        }
        require(db.query("SELECT snapshot_id FROM root_snapshot_seals WHERE snapshot_id=?", listOf(snapshotId)).isEmpty())
        requireCurrentAuthority(execution)
        val entries = grantIds.sorted().map { id ->
            val grant = CapabilityGrantRepository(db).get(id) ?: error("Canonical grant is missing")
            val root = RootGrantRepository(db).get(id) ?: error("Explicit Root grant is missing")
            require(grant.agentId == agentId && root.matches(grant, execution))
            require(RootApprovalTools.isSupportedCapability(grant.capability))
            if (grant.capability.value == CapabilityId.SHELL_EXECUTE) {
                require(shellConsentMatches(execution)) { "Root shell snapshot requires the channel's current shell consent" }
            }
            require(grant.isActiveFor(Instant.parse(clock()), grant.taskId, grant.sessionId))
            val origin = RootWorkspaceRepository(db).requireOrigin(root.workspaceId,execution)
            require(origin.generationId == root.workspaceGenerationId)
            val workspace = requireWorkspace(root.workspaceId, grant.capability)
            RootSnapshotGrantBinding(snapshotId, grant, root, workspaceHash(workspace))
        }
        val result = createCanonicalSnapshot()
        val snapshot = db.query("SELECT agent_id FROM agent_snapshots WHERE id=?", listOf(snapshotId)).single()
        require(snapshot.string("agent_id") == agentId)
        requireCurrentAuthority(execution)
        val canonical = CapabilityGrantRepository(db, clock)
        entries.forEach { entry ->
            require(canonical.get(entry.grant.grantId) == entry.grant)
            require(RootGrantRepository(db).get(entry.grant.grantId) == entry.root)
            require(RootWorkspaceRepository(db).requireOrigin(entry.root.workspaceId,execution).generationId ==
                entry.root.workspaceGenerationId)
            require(workspaceHash(requireWorkspace(entry.root.workspaceId, entry.grant.capability)) == entry.workspaceRevisionHash)
            canonical.bindSnapshot(SnapshotGrantBinding(
                snapshotId, entry.grant.grantId, entry.grant.capability,
                entry.grant.workspaceId, entry.grant.pathScope, entry.grant.policyVersion,
            ))
            val encoded = Json.encodeToString(entry)
            require(encoded.length <= 32_768)
            db.execute("INSERT INTO root_snapshot_grants(snapshot_id,grant_id,binding_json) VALUES(?,?,?)",
                listOf(snapshotId,entry.grant.grantId,encoded))
        }
        val storedRows = db.query("SELECT grant_id,binding_json FROM root_snapshot_grants WHERE snapshot_id=?",listOf(snapshotId))
        check(storedRows.associate { it.string("grant_id") to it.string("binding_json") } ==
            entries.associate { it.grant.grantId to Json.encodeToString(it) }) { "Root snapshot grants were not persisted" }
        val digest = bindingSetHash(storedRows)
        db.execute("INSERT INTO root_snapshot_seals(snapshot_id,agent_id,grant_count,created_at,binding_set_hash) VALUES(?,?,?,?,?)",
            listOf(snapshotId,agentId,entries.size,clock(),digest))
        val seal = db.query("SELECT agent_id,grant_count,binding_set_hash FROM root_snapshot_seals WHERE snapshot_id=?",listOf(snapshotId)).singleOrNull()
        check(seal != null && seal.string("agent_id") == agentId && seal.long("grant_count") == entries.size.toLong() &&
            seal.string("binding_set_hash") == digest) { "Root snapshot seal was not persisted" }
        result
    }

    /** Strict preflight: consumed ONCE grants cannot reenter ordinary admission. */
    fun revalidate(
        snapshotId: String,
        grantId: String,
        execution: RootExecutionStamp,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): RootSnapshotGrantBinding? = db.transaction {
        val entry = sealedEntry(snapshotId,grantId) ?: return@transaction null
        validateEntry(entry,entry.grant,execution,taskIdentity,sessionIdentity)
    }

    /**
     * Internal receipt path only. Its caller must first establish the live opaque
     * permit owner and its exact durable claim. No public skip-consumed switch exists.
     */
    internal fun revalidateForReceipt(
        frozen: RootSnapshotGrantBinding,
        expectedAfter: CapabilityGrant,
        execution: RootExecutionStamp,
        taskIdentity: String?,
        sessionIdentity: String?,
    ): RootSnapshotGrantBinding? = db.transaction {
        val entry = sealedEntry(frozen.snapshotId,frozen.grant.grantId) ?: return@transaction null
        if (entry != frozen || !receiptConsumptionMatches(entry.grant,expectedAfter,taskIdentity,sessionIdentity)) {
            return@transaction null
        }
        validateEntry(entry,expectedAfter,execution,taskIdentity,sessionIdentity)
    }

    private fun sealedEntry(snapshotId: String, grantId: String): RootSnapshotGrantBinding? {
        val seal = db.query(
            "SELECT s.agent_id,s.grant_count,s.binding_set_hash FROM root_snapshot_seals s JOIN agent_snapshots a ON a.id=s.snapshot_id AND a.agent_id=s.agent_id WHERE s.snapshot_id=?",
            listOf(snapshotId),
        ).singleOrNull() ?: return null
        val rows = db.query("SELECT grant_id,binding_json FROM root_snapshot_grants WHERE snapshot_id=?",listOf(snapshotId))
        if (rows.size.toLong() != seal.long("grant_count") || bindingSetHash(rows) != seal.string("binding_set_hash")) return null
        val encoded = rows.singleOrNull { it.string("grant_id") == grantId }?.string("binding_json") ?: return null
        if (encoded.length > 32_768) return null
        val entry = runCatching { Json.decodeFromString<RootSnapshotGrantBinding>(encoded) }.getOrNull() ?: return null
        return entry.takeIf { it.snapshotId == snapshotId && it.grant.grantId == grantId &&
            it.grant.agentId == seal.string("agent_id") }
    }

    private fun validateEntry(
        entry: RootSnapshotGrantBinding,
        expectedCurrent: CapabilityGrant,
        execution: RootExecutionStamp,
        taskIdentity: String?,
        sessionIdentity: String?,
    ): RootSnapshotGrantBinding? {
        if (!currentAuthorityMatches(execution)) return null
        val before = entry.grant
        val canonical = CapabilityGrantRepository(db)
        if (canonical.get(before.grantId) != expectedCurrent || !entry.root.matches(before,execution)) return null
        if (RootGrantRepository(db).get(before.grantId) != entry.root) return null
        if (!before.isActiveFor(Instant.parse(clock()),taskIdentity,sessionIdentity)) return null
        val expected = SnapshotGrantBinding(entry.snapshotId,before.grantId,before.capability,before.workspaceId,before.pathScope,before.policyVersion)
        if (canonical.listSnapshotBindings(entry.snapshotId).none { it.copy(boundAt="") == expected }) return null
        val origin = runCatching { RootWorkspaceRepository(db).requireOrigin(entry.root.workspaceId,execution) }
            .getOrNull() ?: return null
        if (origin.generationId != entry.root.workspaceGenerationId) return null
        if (!RootApprovalTools.isSupportedCapability(before.capability)) return null
        if (before.capability.value == CapabilityId.SHELL_EXECUTE && !shellConsentMatches(execution)) return null
        val workspace = WorkspaceRepository(db).get(entry.root.workspaceId) ?: return null
        if (!workspace.enabled || workspace.backendType != WorkspaceBackendType.PRIVILEGED ||
            !workspace.readable || (RootApprovalTools.requiresWritableWorkspace(before.capability) && !workspace.writable) ||
            workspaceHash(workspace) != entry.workspaceRevisionHash) return null
        return entry
    }

    private fun receiptConsumptionMatches(
        before: CapabilityGrant, after: CapabilityGrant, task: String?, session: String?,
    ): Boolean {
        if (before.lifetime != GrantLifetime.ONCE) return before == after
        val at = after.consumedAt ?: return false
        return before.consumedAt == null && before.revokedAt == null &&
            after.revision == Math.addExact(before.revision,1) &&
            after.copy(revision=before.revision,consumedAt=null) == before &&
            runCatching { before.isActiveFor(Instant.parse(at),task,session) }.getOrDefault(false)
    }

    private fun requireCurrentAuthority(execution: RootExecutionStamp) {
        require(currentAuthorityMatches(execution)) { "Root authority changed before snapshot admission" }
    }

    private fun currentAuthorityMatches(execution: RootExecutionStamp): Boolean =
        runCatching { requireLocalRootState(db,execution) }.isSuccess

    private fun shellConsentMatches(execution: RootExecutionStamp): Boolean = runCatching {
        val state = requireLocalRootState(db, execution)
        state.consent.shellEnabled &&
            execution.shellConsentRevision > 0 &&
            state.consent.shellRevision == execution.shellConsentRevision
    }.getOrDefault(false)

    private fun requireWorkspace(id: String, capability: CapabilityId): Workspace {
        val workspace = requireNotNull(WorkspaceRepository(db).get(id))
        require(
            workspace.enabled &&
                workspace.backendType == WorkspaceBackendType.PRIVILEGED &&
                workspace.readable &&
                (!RootApprovalTools.requiresWritableWorkspace(capability) || workspace.writable),
        )
        return workspace
    }

    private fun bindingSetHash(rows: List<SqlRow>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        rows.sortedBy { it.string("grant_id") }.forEach { row ->
            val bytes = row.string("binding_json").toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun workspaceHash(workspace: Workspace): String = MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(workspace.copy(displayName="Root workspace",createdAt="",updatedAt="")).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        internal fun install(db: SqlConnection) {
            db.execute("CREATE TABLE IF NOT EXISTS root_snapshot_seals(snapshot_id TEXT NOT NULL PRIMARY KEY REFERENCES agent_snapshots(id) ON DELETE CASCADE,agent_id TEXT NOT NULL,grant_count INTEGER NOT NULL CHECK(grant_count BETWEEN 1 AND 128),created_at TEXT NOT NULL,binding_set_hash TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS root_snapshot_grants(snapshot_id TEXT NOT NULL REFERENCES agent_snapshots(id) ON DELETE CASCADE,grant_id TEXT NOT NULL REFERENCES capability_grants(grant_id) ON DELETE CASCADE,binding_json TEXT NOT NULL CHECK(length(binding_json)<=32768),PRIMARY KEY(snapshot_id,grant_id))")
            if (db.query("PRAGMA table_info(root_snapshot_seals)").none { it.string("name") == "binding_set_hash" }) {
                // No backfill: old unsealed WIP snapshots must not acquire a new authorization set.
                db.execute("ALTER TABLE root_snapshot_seals ADD COLUMN binding_set_hash TEXT NOT NULL DEFAULT ''")
            }
            db.execute("CREATE TRIGGER IF NOT EXISTS root_snapshot_seals_immutable BEFORE UPDATE ON root_snapshot_seals BEGIN SELECT RAISE(ABORT,'Root snapshot seal is immutable'); END")
            db.execute("CREATE TRIGGER IF NOT EXISTS root_snapshot_grants_immutable BEFORE UPDATE ON root_snapshot_grants BEGIN SELECT RAISE(ABORT,'Root snapshot grant is immutable'); END")
        }
    }
}
