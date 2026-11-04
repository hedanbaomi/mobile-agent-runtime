// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.time.Instant
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.privileged.RootRuntimeCoordinator
import runtime.mobileagent.skills.tooling.ElevatedAuthority
import runtime.mobileagent.tooling.WorkspaceRegistry

/** Foreground Root selection and display only. Model dispatch never calls this controller. */
internal class RootWorkspaceUiController(private val db: SqlConnection, private val coordinator: RootRuntimeCoordinator) : RootWorkspaceUiPort {
    private val workspaces = WorkspaceRepository(db)
    private val origins = RootWorkspaceRepository(db)
    private val grants = CapabilityGrantRepository(db)
    private val rootGrants = RootGrantRepository(db)

    fun isRoot(workspaceId: String): Boolean =
        origins.getOrigin(workspaceId) != null || workspaces.get(workspaceId)?.rootReference?.startsWith("root:") == true

    fun includeInRecent(workspace: Workspace, hasPrivilegedBinding: Boolean): Boolean =
        workspace.backendType != WorkspaceBackendType.PRIVILEGED || hasPrivilegedBinding || origins.getOrigin(workspace.id) != null

    fun requireOrdinaryGrantTarget(workspaceId: String?) {
        if (workspaceId != null && isRoot(workspaceId)) {
            throw IllegalArgumentException("Root workspace grants require foreground Root admission")
        }
    }

    fun requireOrdinarySnapshotBinding(bindingWorkspaceId: String?, grantWorkspaceId: String?, grantId: String) {
        requireOrdinaryGrantTarget(bindingWorkspaceId)
        requireOrdinaryGrantTarget(grantWorkspaceId)
        if (rootGrants.get(grantId) != null) {
            throw IllegalArgumentException("Root grants can only be bound into sealed Root snapshots")
        }
    }

    fun isDiagnosticWorkspaceReady(
        workspace: Workspace,
        registered: WorkspaceRegistry.RegisteredWorkspace?,
    ): Boolean {
        if (isRoot(workspace.id)) return status(workspace) == WorkspaceAccessStatus.ACTIVE
        val current = registered ?: return false
        val descriptor = current.descriptor
        return descriptor.enabled &&
            (descriptor.readable || descriptor.writable) &&
            current.backend.capabilities.isNotEmpty()
    }

    fun isAuthorityMismatched(
        workspace: Workspace,
        workspaceAuthority: Authority?,
        selected: ElevatedAuthority?,
    ): Boolean {
        if (workspace.backendType != WorkspaceBackendType.PRIVILEGED) return false
        val normalized = workspaceAuthority?.takeIf { it != Authority.NONE }
        return normalized == null || normalized != selected
    }

    fun useRecentNullable(
        workspaceId: String,
        target: WorkspacePickerTarget?,
        plan: WorkspaceIntentPlan,
    ): WorkspaceAccessResult = useRecent(
        workspaceId = workspaceId,
        target = target ?: WorkspacePickerTarget(),
        setAgentDefault = plan.setAgentDefault,
        bindThread = plan.bindThread,
        deferred = plan.deferred,
    )

    fun canSealNewThreadSnapshot(workspaceId: String): Boolean {
        val origin = origins.getOrigin(workspaceId)
        if (origin == null) return workspaces.get(workspaceId)?.rootReference?.startsWith("root:") != true
        val selected = AuthorityPolicyRepository(db).getPolicy().selectedAuthority
        return selected == origin.authority && coordinator.currentExecutionStamp(origin.authority) != null
    }

    fun filterDurableWorkspaceGrants(workspace: Workspace, candidates: List<CapabilityGrant>): List<CapabilityGrant> {
        if (!isRoot(workspace.id)) return candidates
        val origin = origins.getOrigin(workspace.id) ?: return emptyList()
        val policy = AuthorityPolicyRepository(db).getPolicy()
        return candidates.filter { grant -> rootGrants.isDurablyBoundGrant(grant, origin, policy) }
    }

    fun isDiagnosticReady(workspace: Workspace, registeredReady: Boolean): Boolean =
        if (isRoot(workspace.id)) status(workspace) == WorkspaceAccessStatus.ACTIVE else registeredReady

    fun status(workspace: Workspace): WorkspaceAccessStatus {
        if (!workspace.enabled) return WorkspaceAccessStatus.DISABLED
        val origin = origins.getOrigin(workspace.id) ?: return WorkspaceAccessStatus.UNAVAILABLE
        val execution = coordinator.currentExecutionStamp(origin.authority) ?: return WorkspaceAccessStatus.UNAVAILABLE
        return if (origin.matches(execution.identity) &&
            RootWorkspaceLocatorRepository(db).getCurrent(workspace.id, execution) != null
        ) WorkspaceAccessStatus.ACTIVE else WorkspaceAccessStatus.UNAVAILABLE
    }

    fun attach(
        authority: Authority, path: String, writable: Boolean, metadata: Boolean, shell: Boolean,
        target: WorkspacePickerTarget,
    ): WorkspaceAccessResult {
        if (!validTarget(target)) return failure(WorkspaceAccessErrorCode.CONFLICT)
        val admitted = coordinator.admitWorkspaceFromForeground(authority, path, writable, metadata, shell, target.agentId)
            ?: return failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        return commitTarget(
            admitted.workspace,
            target,
            admitted.grants,
            setAgentDefault = target.agentId != null && target.threadId == null,
            bindThread = target.threadId != null,
        )
    }

    fun attachWithPlan(
        authority: Authority, path: String, writable: Boolean, metadata: Boolean, shell: Boolean,
        plan: WorkspaceIntentPlan, target: WorkspaceTarget,
    ): WorkspaceAccessResult {
        val pickerTarget = if (plan.deferred) WorkspacePickerTarget() else WorkspacePickerTarget(target.agentId, target.threadId)
        if (!validTarget(pickerTarget)) return failure(WorkspaceAccessErrorCode.CONFLICT)
        val admitted = coordinator.admitWorkspaceFromForeground(authority, path, writable, metadata, shell, pickerTarget.agentId)
            ?: return failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        if (plan.deferred) {
            return WorkspaceAccessResult.Success(item(admitted.workspace, emptyList()), emptyList())
        }
        return commitTarget(admitted.workspace, pickerTarget, admitted.grants, plan.setAgentDefault, plan.bindThread)
    }

    fun commitDraft(draft: WorkspaceDraft, agentId: String): WorkspaceAccessResult {
        if (AgentRepository(db).get(agentId) == null) return failure(WorkspaceAccessErrorCode.CONFLICT)
        val workspace = workspaces.get(draft.workspaceId) ?: return failure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        val origin = origins.getOrigin(workspace.id) ?: return failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        if (status(workspace) != WorkspaceAccessStatus.ACTIVE) return failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        val capabilities = draft.requestedCapabilities
        if (capabilities.isEmpty()) return failure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        val admittedGrants = coordinator.admitDraftWorkspaceGrants(origin.authority, workspace.id, agentId, capabilities)
            ?: return failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        return commitTarget(
            workspace = workspace,
            target = WorkspacePickerTarget(agentId = agentId),
            active = admittedGrants,
            setAgentDefault = draft.setAsAgentDefault,
            bindThread = false,
        )
    }

    fun useRecent(
        workspaceId: String,
        target: WorkspacePickerTarget,
        setAgentDefault: Boolean = target.agentId != null && target.threadId == null,
        bindThread: Boolean = target.threadId != null,
        deferred: Boolean = false,
    ): WorkspaceAccessResult {
        if (!validTarget(target)) return failure(WorkspaceAccessErrorCode.CONFLICT)
        val workspace = workspaces.get(workspaceId) ?: return failure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        if (status(workspace) != WorkspaceAccessStatus.ACTIVE) return failure(WorkspaceAccessErrorCode.AUTHORITY_UNAVAILABLE)
        if (deferred) return failure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        val active = activeGrants(workspace, target.agentId)
        if (target.agentId != null && active.isEmpty()) return failure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        return commitTarget(workspace, target, active, setAgentDefault = setAgentDefault, bindThread = bindThread)
    }

    fun confirmNewThread(agentId: String, threadId: String, currentWorkspaceId: String, workspaceId: String): WorkspaceAccessResult {
        if (!validTarget(WorkspacePickerTarget(agentId, threadId))) return failure(WorkspaceAccessErrorCode.CONFLICT)
        val current = ConversationWorkspaceBindingRepository(db).get(threadId)?.workspaceId.orEmpty()
        if (current != currentWorkspaceId) return failure(WorkspaceAccessErrorCode.CONFLICT)
        val workspace = workspaces.get(workspaceId) ?: return failure(WorkspaceAccessErrorCode.WORKSPACE_NOT_FOUND)
        val active = activeGrants(workspace, agentId)
        if (status(workspace) != WorkspaceAccessStatus.ACTIVE || active.isEmpty()) return failure(WorkspaceAccessErrorCode.CAPABILITY_DENIED)
        // The caller creates a fresh conversation and snapshot. The old thread is never rebound.
        return WorkspaceAccessResult.Success(item(workspace, active), active.map(::summary))
    }

    private fun commitTarget(
        workspace: Workspace,
        target: WorkspacePickerTarget,
        active: List<CapabilityGrant>,
        setAgentDefault: Boolean,
        bindThread: Boolean,
    ): WorkspaceAccessResult {
        val item = item(workspace, active)
        val threadId = target.threadId?.takeIf { bindThread }
        if (threadId != null) return WorkspaceAccessResult.NewThreadRequired(
            agentId = requireNotNull(target.agentId), currentThreadId = threadId,
            currentWorkspaceId = ConversationWorkspaceBindingRepository(db).get(threadId)?.workspaceId.orEmpty(),
            requestedWorkspaceId = workspace.id, workspace = item, grants = active.map(::summary),
            authorizationState = NewThreadAuthorizationState.ALREADY_GRANTED,
        )
        if (setAgentDefault && target.agentId != null) {
            val defaults = AgentWorkspaceDefaultRepository(db)
            defaults.set(target.agentId, workspace.id, defaults.get(target.agentId)?.revision)
        }
        return WorkspaceAccessResult.Success(item, active.map(::summary))
    }

    private fun validTarget(target: WorkspacePickerTarget): Boolean {
        val agent = target.agentId ?: return target.threadId == null
        if (AgentRepository(db).get(agent) == null) return false
        val thread = target.threadId ?: return true
        return db.query("SELECT s.agent_id FROM conversations c JOIN agent_snapshots s ON s.id=c.agent_snapshot_id WHERE c.id=?",
            listOf(thread)).singleOrNull()?.string("agent_id") == agent
    }

    private fun activeGrants(workspace: Workspace, agentId: String?): List<CapabilityGrant> {
        val origin = origins.getOrigin(workspace.id) ?: return emptyList()
        val execution = coordinator.currentExecutionStamp(origin.authority) ?: return emptyList()
        return agentId?.let { grants.forAgent(it, includeRevoked = false) }.orEmpty().filter {
            it.workspaceId == workspace.id && rootGrants.get(it.grantId)?.matches(it, execution) == true &&
                it.isActiveFor(Instant.now(), null, null)
        }
    }

    private fun item(workspace: Workspace, active: List<CapabilityGrant>) = committedWorkspaceAccessItem(
        workspace, workspace.displayName, status(workspace), origins.getOrigin(workspace.id)?.authority, active,
        fullDeviceConfirmationPresent = workspace.scope != WorkspaceScope.FULL_DEVICE_FILES,
    )

    private fun summary(grant: CapabilityGrant) = WorkspaceAccessGrantSummary(
        grant.grantId, grant.capability, grant.lifetime, grant.revision,
    )

    private fun failure(code: WorkspaceAccessErrorCode) = WorkspaceAccessResult.Failure(code)

    // ---- Foreground browsing and in-place permission editing (`U7`) -----------------------
    //
    // Both entry points are foreground-only. Browsing never creates a workspace, a grant or an
    // approval; editing re-signs the durable Root grants through the coordinator, so old snapshots
    // keep their frozen identity and only new conversations can bind the new grants.

    /**
     * One foreground directory page. Paths stay display-only in the UI; the coordinator resolves
     * them against the live Root session and returns `null` when the session is not usable.
     */
    override suspend fun browseRootDirectory(
        authority: Authority,
        path: String,
        cursor: String?,
    ): RootDirectoryBrowseUiPage? {
        if (!authority.isRootAuthority) return null
        val page = coordinator.browseDirectoryFromForeground(authority, path, cursor) ?: return null
        return RootDirectoryBrowseUiPage(
            path = page.path,
            entries = page.entries.mapNotNull { entry ->
                // Foreground browsing exposes directories only; files are not attachable and must
                // not become extra rows the user can confuse with a workspace root.
                if (!entry.directory) return@mapNotNull null
                val name = entry.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val entryPath = entry.path.takeIf { it.startsWith('/') } ?: return@mapNotNull null
                RootDirectoryBrowseUiEntry(
                    name = name,
                    path = entryPath,
                    directory = true,
                    targetPath = entry.targetPath?.takeIf { it.startsWith('/') },
                )
            },
            nextCursor = page.nextCursor,
        )
    }

    /**
     * Current durable permission intent for exactly one Agent on one attached Root directory.
     *
     * Only this Agent's still-valid PERSISTENT Root grants count: the check reuses
     * [RootGrantRepository.isDurablyBoundGrant] (frozen binding/policy/identity) and
     * [CapabilityGrant.isActiveFor] (not revoked/consumed/expired). Other Agents' grants for the same
     * workspace must never leak into this dialog, or saving would silently widen or revoke them.
     */
    override fun rootWorkspacePermissions(workspaceId: String, agentId: String): RootWorkspacePermissionsUi? {
        if (!isRoot(workspaceId) || agentId.isBlank()) return null
        val origin = origins.getOrigin(workspaceId) ?: return null
        val policy = AuthorityPolicyRepository(db).getPolicy()
        val now = Instant.now()
        val active = grants.forAgent(agentId, includeRevoked = false)
            .asSequence()
            .filter { grant ->
                grant.workspaceId == workspaceId &&
                    grant.lifetime == GrantLifetime.PERSISTENT &&
                    grant.isActiveFor(now, null, null) &&
                    rootGrants.isDurablyBoundGrant(grant, origin, policy)
            }
            .mapTo(linkedSetOf()) { it.capability.value }
        return RootWorkspacePermissionsUi(
            workspaceId = workspaceId,
            writable = CapabilityId.FILE_WRITE_TEXT in active,
            // A read-only metadata selection is real intent: FILE_METADATA_READ alone must not be
            // reported as "metadata off", or saving would drop it.
            metadata = CapabilityId.FILE_METADATA_READ in active ||
                CapabilityId.FILE_METADATA_WRITE in active,
            shell = CapabilityId.SHELL_EXECUTE in active,
        )
    }

    /**
     * Replaces the foreground permission intent of one attached Root workspace.
     *
     * Returns false when the channel is not usable or the coordinator refused the edit; the host
     * then keeps the old state and shows the reason. Nothing here revives an old snapshot.
     */
    override suspend fun updateRootWorkspacePermissions(
        authority: Authority,
        workspaceId: String,
        agentId: String,
        writable: Boolean,
        metadata: Boolean,
        shell: Boolean,
    ): Boolean {
        if (!authority.isRootAuthority || !isRoot(workspaceId)) return false
        val updated = coordinator.updateWorkspacePermissionsFromForeground(
            authority = authority,
            workspaceId = workspaceId,
            agentId = agentId,
            writable = writable,
            metadata = metadata,
            shell = shell,
        ) ?: return false
        return updated.isNotEmpty()
    }
}

/** One foreground browse page; a model-facing path never leaves this host-side projection. */
data class RootDirectoryBrowseUiPage(
    val path: String,
    val entries: List<RootDirectoryBrowseUiEntry>,
    val nextCursor: String?,
)

data class RootDirectoryBrowseUiEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    /** Observed target of a directory alias; display-only and never used as a typed path. */
    val targetPath: String? = null,
)

/** Current durable permission intent of one attached Root workspace. */
data class RootWorkspacePermissionsUi(
    val workspaceId: String,
    val writable: Boolean,
    val metadata: Boolean,
    val shell: Boolean,
)

/**
 * Host capability consumed by the Agents UI for `U7` browsing and in-place permission editing.
 *
 * `RuntimeIntegration` implements this by delegating to its `RootWorkspaceUiController`:
 * ```kotlin
 * override suspend fun browseRootDirectory(authority: Authority, path: String, cursor: String?) =
 *     rootWorkspaceUi.browseRootDirectory(authority, path, cursor)
 * override fun rootWorkspacePermissions(workspaceId: String, agentId: String) =
 *     rootWorkspaceUi.rootWorkspacePermissions(workspaceId, agentId)
 * override suspend fun updateRootWorkspacePermissions(...) = rootWorkspaceUi.updateRootWorkspacePermissions(...)
 * ```
 * Until it is implemented the UI keeps the manual path flow and hides the edit entry.
 */
interface RootWorkspaceUiPort {
    suspend fun browseRootDirectory(
        authority: Authority,
        path: String,
        cursor: String? = null,
    ): RootDirectoryBrowseUiPage?

    /**
     * Durable permission intent of exactly this Agent on this attached Root directory. Returns
     * null when the workspace is not a Root workspace or the read cannot be trusted, so the caller
     * never mistakes a failed read for "nothing is granted".
     */
    fun rootWorkspacePermissions(workspaceId: String, agentId: String): RootWorkspacePermissionsUi?

    suspend fun updateRootWorkspacePermissions(
        authority: Authority,
        workspaceId: String,
        agentId: String,
        writable: Boolean,
        metadata: Boolean,
        shell: Boolean,
    ): Boolean
}
