// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootSnapshotGrantBinding
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.isRootAuthority

sealed interface RootRunTarget {
    data object None : RootRunTarget
    data class Bound(val authority: Authority) : RootRunTarget
    data object Invalid : RootRunTarget
}

/**
 * Creates a new Run with its immutable Root context. The trusted session controller
 * must verify live OS/transport identity and hold its coordination lock through this
 * local transaction. A stamp DTO alone is not platform authentication.
 * Existing Runs (including unstamped legacy Runs) can never be admitted here.
 */
class RootRunAdmission(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun resolveRunTarget(
        conversationId: String,
        snapshotId: String,
        extraWorkspaceIds: Set<String> = emptySet(),
        extraGrantIds: Set<String> = emptySet(),
    ): RootRunTarget = db.transaction {
        val snapshotBindings = if (snapshotId.isNotBlank()) {
            CapabilityGrantRepository(db, clock).listSnapshotBindings(snapshotId)
        } else {
            emptyList()
        }
        val workspaceIds = buildSet {
            if (conversationId.isNotBlank()) {
                ConversationWorkspaceBindingRepository(db, clock).get(conversationId)?.workspaceId?.let(::add)
            }
            snapshotBindings.mapNotNullTo(this) { it.workspaceId }
            addAll(extraWorkspaceIds.filter { it.isNotBlank() })
        }
        val grantIds = buildSet {
            snapshotBindings.mapTo(this) { it.grantId }
            addAll(extraGrantIds.filter { it.isNotBlank() })
        }
        val origins = RootWorkspaceRepository(db, clock)
        val workspaces = WorkspaceRepository(db, clock)
        val rootGrants = RootGrantRepository(db, clock)
        var sawRootMarker = false
        val authorities = linkedSetOf<Authority>()

        for (workspaceId in workspaceIds) {
            val origin = origins.getOrigin(workspaceId)
            val workspace = workspaces.get(workspaceId)
            if (origin != null) {
                sawRootMarker = true
                if (!origin.authority.isRootAuthority) return@transaction RootRunTarget.Invalid
                authorities += origin.authority
            } else if (workspace?.rootReference?.startsWith("root:") == true) {
                return@transaction RootRunTarget.Invalid
            }
        }
        for (grantId in grantIds) {
            val rootGrant = rootGrants.get(grantId) ?: continue
            sawRootMarker = true
            if (!rootGrant.authority.isRootAuthority) return@transaction RootRunTarget.Invalid
            authorities += rootGrant.authority
        }
        if (snapshotId.isNotBlank()) {
            val sealCount = db.query(
                "SELECT COUNT(*) AS count FROM root_snapshot_seals WHERE snapshot_id=?",
                listOf(snapshotId),
            ).single().long("count")
            val sealedRows = db.query(
                "SELECT binding_json FROM root_snapshot_grants WHERE snapshot_id=?",
                listOf(snapshotId),
            )
            if (sealCount > 0L || sealedRows.isNotEmpty()) {
                sawRootMarker = true
                if (sealCount != 1L || sealedRows.isEmpty()) return@transaction RootRunTarget.Invalid
                for (row in sealedRows) {
                    val encoded = row.string("binding_json")
                    if (encoded.length > 32_768) return@transaction RootRunTarget.Invalid
                    val entry = runCatching { Json.decodeFromString<RootSnapshotGrantBinding>(encoded) }.getOrNull()
                        ?: return@transaction RootRunTarget.Invalid
                    if (entry.snapshotId != snapshotId || !entry.root.authority.isRootAuthority) {
                        return@transaction RootRunTarget.Invalid
                    }
                    authorities += entry.root.authority
                }
            }
        }
        when {
            !sawRootMarker -> RootRunTarget.None
            authorities.size == 1 -> RootRunTarget.Bound(authorities.single())
            else -> RootRunTarget.Invalid
        }
    }
    fun admitOrCreateOrdinaryRun(
        record: RunRecord,
        selectedAuthority: Authority,
        resolveExecution: (Authority) -> RootExecutionStamp?,
    ): RunRecord = when (val target = resolveRunTarget(record.conversationId, record.snapshotId)) {
        RootRunTarget.None -> RunRepository(db, clock).create(record)
        is RootRunTarget.Bound -> {
            require(selectedAuthority == target.authority) {
                "Root workspace requires ${target.authority} to be selected"
            }
            val execution = requireNotNull(resolveExecution(target.authority)) {
                "Selected Root authority has no verified active session"
            }
            create(record, execution)
        }
        RootRunTarget.Invalid -> {
            throw IllegalArgumentException("Root workspace or snapshot binding is invalid")
        }
    }

    fun create(record: RunRecord, execution: RootExecutionStamp): RunRecord = db.transaction {
        require(record.state == RunStatus.CREATED) { "Root admission requires a new Run" }
        val state = requireLocalRootState(db, execution)
        require(state.consent.shellRevision == execution.shellConsentRevision)
        val frozen = record.copy(manifestJson = RootRunManifest.freeze(record.manifestJson, execution))
        val runs = RunRepository(db, clock)
        runs.create(frozen).also {
            check(runs.get(record.runId) == frozen) { "Root Run admission was not persisted" }
        }
    }

    fun matchesAdmittedRun(runId: String, execution: RootExecutionStamp): Boolean = db.transaction {
        val existing = RunRepository(db, clock).get(runId) ?: return@transaction false
        runCatching {
            val state = requireLocalRootState(db, execution)
            require(state.consent.shellRevision == execution.shellConsentRevision)
            RootRunManifest.requireMatches(existing.manifestJson, execution)
        }.isSuccess
    }
}
