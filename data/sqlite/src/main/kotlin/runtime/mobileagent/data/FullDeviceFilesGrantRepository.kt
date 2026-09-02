// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.Utc
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrant
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrantStore
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceResult

/**
 * Canonical durable store for the explicit full-device-files confirmation.
 *
 * This is deliberately separate from an ordinary capability grant: the
 * authority provider requires the confirmation before it can even create a
 * device-root attachment. Revisions are monotonic, and a revoked row remains
 * as a tombstone so an old request cannot be replayed after revocation.
 */
class FullDeviceFilesGrantRepository(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) : FullDeviceFilesGrantStore {
    override fun load(workspaceId: String): FullDeviceFilesGrant? = read(workspaceId)
        ?.takeUnless { it.revokedAt != null }
        ?.let { it.toGrant() }

    /** Current revision, including a revoked tombstone, for a fresh UI CAS. */
    fun currentRevision(workspaceId: String): Long? = read(workspaceId)?.revision

    /** Internal recovery input; callers still decide whether a row is usable. */
    fun activeWorkspaceIds(): List<String> = db.query(
        "SELECT workspace_id FROM full_device_files_grants WHERE revoked_at IS NULL ORDER BY workspace_id",
    ).map { it.string("workspace_id") }

    override fun save(grant: FullDeviceFilesGrant): WorkspaceResult<Unit> = try {
        db.transaction {
            val current = read(grant.workspaceId)
            when {
                current == null -> {
                    require(grant.revision == 1L) { "Full-device grant revision must start at one" }
                    insert(grant)
                }
                current.revokedAt != null -> {
                    if (grant.revision != current.revision + 1L) {
                        throw AuthorityPolicyConflictException("Full-device grant revision changed")
                    }
                    update(grant, revokedAt = null, createdAt = current.createdAt)
                }
                grant.revision != current.revision -> {
                    throw AuthorityPolicyConflictException("Full-device grant revision changed")
                }
                else -> update(grant, revokedAt = null, createdAt = current.createdAt)
            }
            val persisted = read(grant.workspaceId)
            check(
                persisted != null && persisted.revokedAt == null &&
                    persisted.revision == grant.revision &&
                    persisted.confirmedAtEpochMs == grant.confirmedAtEpochMs,
            ) { "Full-device grant save lost its compare-and-set race" }
        }
        WorkspaceResult.Success(Unit)
    } catch (_: AuthorityPolicyConflictException) {
        failure(ToolErrorCode.CONFLICT)
    } catch (_: IllegalArgumentException) {
        failure(ToolErrorCode.INVALID_REQUEST)
    } catch (_: RuntimeException) {
        failure(ToolErrorCode.UNKNOWN_OUTCOME)
    }

    override fun revoke(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit> = try {
        require(expectedRevision > 0L) { "Expected full-device grant revision must be positive" }
        db.transaction {
            val current = read(workspaceId)
                ?: throw AuthorityPolicyConflictException("Full-device grant is missing")
            if (current.revokedAt != null || current.revision != expectedRevision) {
                throw AuthorityPolicyConflictException("Full-device grant revision changed")
            }
            val now = clock()
            db.execute(
                "UPDATE full_device_files_grants SET revision = ?, revoked_at = ?, updated_at = ? WHERE workspace_id = ? AND revision = ? AND revoked_at IS NULL",
                listOf(expectedRevision + 1L, now, now, workspaceId, expectedRevision),
            )
            val persisted = read(workspaceId)
            check(
                persisted != null && persisted.revokedAt != null &&
                    persisted.revision == expectedRevision + 1L,
            ) { "Full-device grant revoke lost its compare-and-set race" }
        }
        WorkspaceResult.Success(Unit)
    } catch (_: AuthorityPolicyConflictException) {
        failure(ToolErrorCode.CONFLICT)
    } catch (_: IllegalArgumentException) {
        failure(ToolErrorCode.INVALID_REQUEST)
    } catch (_: RuntimeException) {
        failure(ToolErrorCode.UNKNOWN_OUTCOME)
    }

    private fun insert(grant: FullDeviceFilesGrant) {
        db.execute(
            "INSERT INTO full_device_files_grants(workspace_id,revision,confirmed_at_epoch_ms,created_at,updated_at,revoked_at) VALUES(?,?,?,?,?,NULL)",
            listOf(grant.workspaceId, grant.revision, grant.confirmedAtEpochMs, clock(), clock()),
        )
    }

    private fun update(grant: FullDeviceFilesGrant, revokedAt: String?, createdAt: String) {
        db.execute(
            "UPDATE full_device_files_grants SET revision = ?, confirmed_at_epoch_ms = ?, created_at = ?, updated_at = ?, revoked_at = ? WHERE workspace_id = ?",
            listOf(grant.revision, grant.confirmedAtEpochMs, createdAt, clock(), revokedAt, grant.workspaceId),
        )
    }

    private fun read(workspaceId: String): StoredGrant? = db.query(
        "SELECT workspace_id,revision,confirmed_at_epoch_ms,created_at,revoked_at FROM full_device_files_grants WHERE workspace_id = ?",
        listOf(workspaceId),
    ).singleOrNull()?.let { row ->
        StoredGrant(
            workspaceId = row.string("workspace_id"),
            revision = row.long("revision"),
            confirmedAtEpochMs = row.long("confirmed_at_epoch_ms"),
            createdAt = row.string("created_at"),
            revokedAt = row.string("revoked_at").ifBlank { null },
        )
    }

    private data class StoredGrant(
        val workspaceId: String,
        val revision: Long,
        val confirmedAtEpochMs: Long,
        val createdAt: String,
        val revokedAt: String?,
    ) {
        fun toGrant() = FullDeviceFilesGrant(
            workspaceId = workspaceId,
            revision = revision,
            confirmedAtEpochMs = confirmedAtEpochMs,
        )
    }

    private fun <T> failure(code: ToolErrorCode): WorkspaceResult<T> =
        WorkspaceResult.Failure(ToolError(code))
}
