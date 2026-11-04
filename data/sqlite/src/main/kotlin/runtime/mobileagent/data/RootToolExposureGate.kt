// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootSnapshotGrantBinding
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.isRootAuthority

data class RootVisibleToolBinding(
    val toolName: String,
    val capability: CapabilityId,
    val workspaceId: String,
    val grantId: String,
    val pathScope: String?,
    val writableWorkspace: Boolean,
)

data class RootToolExposureSnapshot(
    val snapshotId: String,
    val authority: Authority,
    val visibleTools: List<RootVisibleToolBinding>,
    val shellExposed: Boolean,
) {
    val visibleToolNames: Set<String>
        get() = visibleTools.mapTo(linkedSetOf()) { it.toolName }
}

/**
 * Production tool-exposure and startup-recovery gate for Root runs (`ROOT04`, `ROOT05`, `ROOT08`, `ROOT09`).
 *
 * Enforces:
 * 1. Typed Root workspace tools (`file_list`, `file_stat`, `file_read_text`, `file_write_text`,
 *    `file_create_directory`, `file_move`, `file_delete`, `file_apply_patch`,
 *    `file_get_metadata`, `file_set_metadata`) are exposed strictly by the intersection of:
 *    - Persisted `AuthorityPolicy.selectedAuthority` matching [execution] (`ROOT_DIRECT` or `SHIZUKU_ROOT`)
 *    - Live verified `PrivilegeTier.ROOT` (`serviceUid == 0`) and `RootAuthorityRecord` (`enabled && !degraded`)
 *    - Active, readable (and writable for mutation tools) `PRIVILEGED` workspace with valid
 *      `RootWorkspaceOrigin` and attached encrypted `RootWorkspaceLocator`
 *    - Unrevoked, unconsumed, unexpired `RootSnapshotGrantBinding` in the frozen [snapshotId]
 * 2. Root `shell_exec` additionally requires the channel's independent shell consent and grant:
 *    - Selected Root authority (`ROOT_DIRECT` or `SHIZUKU_ROOT`)
 *    - Verified `PrivilegeTier.ROOT` (`serviceUid == 0`)
 *    - `rootConsent.enabled == true` with matching `consentRevision`
 *    - `rootConsent.shellEnabled == true` with matching `shellConsentRevision > 0`
 *    - A valid `shell.execute`
 *      `RootSnapshotGrantBinding` on a writable Root workspace in [snapshotId].
 * 3. Consent edits and the global policy CAS advance revisions, so a frozen
 *    [RootExecutionStamp] or old [snapshotId] can never resurrect exposure.
 */
class RootToolExposureGate(
    private val db: SqlConnection,
    private val clock: () -> String = { Utc.nowIso() },
) {
    fun evaluateExposure(
        snapshotId: String,
        grantIds: Collection<String>,
        execution: RootExecutionStamp?,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): RootToolExposureSnapshot {
        val policy = AuthorityPolicyRepository(db).getPolicy()
        val selected = policy.selectedAuthority
        if (!selected.isRootAuthority || execution == null) {
            return RootToolExposureSnapshot(snapshotId, selected, emptyList(), shellExposed = false)
        }
        val state = runCatching { requireLocalRootState(db, execution) }.getOrNull()
            ?: return RootToolExposureSnapshot(snapshotId, selected, emptyList(), shellExposed = false)
        if (execution.identity.privilegeTier != PrivilegeTier.ROOT || execution.identity.serviceUid != 0) {
            return RootToolExposureSnapshot(snapshotId, selected, emptyList(), shellExposed = false)
        }
        val shellFiveWayReady = state.consent.shellEnabled &&
            execution.shellConsentRevision > 0L &&
            state.consent.shellRevision == execution.shellConsentRevision

        val snapshotRepo = RootSnapshotGrantRepository(db, clock)
        val workspaceRepo = WorkspaceRepository(db)
        val locatorRepo = RootWorkspaceLocatorRepository(db)
        val bindings = mutableListOf<RootVisibleToolBinding>()

        for (grantId in grantIds.distinct().sorted()) {
            val revalidated = snapshotRepo.revalidate(
                snapshotId = snapshotId,
                grantId = grantId,
                execution = execution,
                taskIdentity = taskIdentity,
                sessionIdentity = sessionIdentity,
            ) ?: continue
            val binding = buildToolBindingsForGrant(
                snapshotId = snapshotId,
                revalidated = revalidated,
                execution = execution,
                shellFiveWayReady = shellFiveWayReady,
                workspaceRepo = workspaceRepo,
                locatorRepo = locatorRepo,
                snapshotRepo = snapshotRepo,
                taskIdentity = taskIdentity,
                sessionIdentity = sessionIdentity,
            )
            bindings += binding
        }
        return RootToolExposureSnapshot(
            snapshotId = snapshotId,
            authority = selected,
            visibleTools = bindings,
            shellExposed = bindings.any { it.toolName == "shell_exec" },
        )
    }

    fun hasCompositeFileListGrant(
        snapshotId: String,
        workspaceId: String,
        requiredPathScope: String?,
        execution: RootExecutionStamp,
        taskIdentity: String? = null,
        sessionIdentity: String? = null,
    ): Boolean {
        val snapshotRepo = RootSnapshotGrantRepository(db, clock)
        val grantRows = db.query(
            "SELECT grant_id FROM root_snapshot_grants WHERE snapshot_id=?",
            listOf(snapshotId),
        )
        for (row in grantRows) {
            val revalidated = snapshotRepo.revalidate(
                snapshotId = snapshotId,
                grantId = row.string("grant_id"),
                execution = execution,
                taskIdentity = taskIdentity,
                sessionIdentity = sessionIdentity,
            ) ?: continue
            if (revalidated.root.workspaceId == workspaceId &&
                revalidated.grant.capability.value == CapabilityId.FILE_LIST &&
                revalidated.grant.lifetime != GrantLifetime.ONCE &&
                scopeCovers(revalidated.grant.pathScope, requiredPathScope)
            ) {
                return true
            }
        }
        return false
    }

    fun reconcileStartupUncertainPermits(excludedRequestIds: Set<String> = emptySet()): List<String> =
        RootReceiptRecoveryCoordinator(db, clock).recoverInterrupted(excludedRequestIds)

    private fun buildToolBindingsForGrant(
        snapshotId: String,
        revalidated: RootSnapshotGrantBinding,
        execution: RootExecutionStamp,
        shellFiveWayReady: Boolean,
        workspaceRepo: WorkspaceRepository,
        locatorRepo: RootWorkspaceLocatorRepository,
        snapshotRepo: RootSnapshotGrantRepository,
        taskIdentity: String?,
        sessionIdentity: String?,
    ): List<RootVisibleToolBinding> {
        val workspace = workspaceRepo.get(revalidated.root.workspaceId) ?: return emptyList()
        if (!workspace.enabled || !workspace.readable) return emptyList()
        if (locatorRepo.getCurrent(revalidated.root.workspaceId, execution) == null) return emptyList()
        val cap = revalidated.grant.capability
        if (RootApprovalTools.requiresWritableWorkspace(cap) && !workspace.writable) return emptyList()
        if (cap.value == CapabilityId.SHELL_EXECUTE && !shellFiveWayReady) return emptyList()
        val toolNames = toolNamesForCapability(
            cap = cap,
            hasCompositeList = cap.value == CapabilityId.FILE_READ_TEXT &&
                hasCompositeFileListGrant(
                    snapshotId = snapshotId,
                    workspaceId = workspace.id,
                    requiredPathScope = revalidated.grant.pathScope,
                    execution = execution,
                    taskIdentity = taskIdentity,
                    sessionIdentity = sessionIdentity,
                ),
        )
        return toolNames.map { toolName ->
            RootVisibleToolBinding(
                toolName = toolName,
                capability = cap,
                workspaceId = workspace.id,
                grantId = revalidated.grant.grantId,
                pathScope = revalidated.grant.pathScope,
                writableWorkspace = workspace.writable,
            )
        }
    }

    private fun toolNamesForCapability(cap: CapabilityId, hasCompositeList: Boolean = false): List<String> = when (cap.value) {
        CapabilityId.FILE_LIST -> listOf("file_list", "file_glob")
        CapabilityId.FILE_STAT -> listOf("file_stat")
        CapabilityId.FILE_READ_TEXT -> if (hasCompositeList) listOf("file_read_text", "file_grep") else listOf("file_read_text")
        CapabilityId.FILE_WRITE_TEXT -> listOf("file_write_text", "file_apply_patch")
        CapabilityId.FILE_CREATE_DIRECTORY -> listOf("file_create_directory")
        CapabilityId.FILE_MOVE -> listOf("file_move")
        CapabilityId.FILE_DELETE -> listOf("file_delete")
        CapabilityId.FILE_METADATA_READ -> listOf("file_get_metadata")
        CapabilityId.FILE_METADATA_WRITE -> listOf("file_set_metadata")
        CapabilityId.SHELL_EXECUTE -> listOf("shell_exec")
        else -> emptyList()
    }

    private fun scopeCovers(coveringScope: String?, targetScope: String?): Boolean {
        val cover = coveringScope?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return true
        val target = targetScope?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return false
        return target == cover || target.startsWith("$cover/")
    }
}
