// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.RootWorkspaceLocatorRepository
import runtime.mobileagent.data.RootWorkspaceRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootExecutionStamp
import runtime.mobileagent.domain.RootRecoveryLocatorCodec
import runtime.mobileagent.domain.RootRecoveryLocatorPayload
import runtime.mobileagent.domain.RootWorkspaceLocator
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.domain.RootWorkspaceRootKind
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.canonicalSha256

/** Exactly one JSON RPC per phase; Binder/socket ownership stays with the caller. */
fun interface RootWorkspaceAdmissionTransport {
    fun bindWorkspace(requestJson: String): RootServiceBindWorkspaceResponse
}

/** A directory the user picked explicitly. Nothing is inferred from the Host app's own UID or FDs. */
data class RootWorkspaceSelection(
    val displayName: String,
    val rootPath: String,
    val rootKind: RootWorkspaceRootKind = RootWorkspaceRootKind.DIRECTORY,
    val writable: Boolean = false,
) {
    override fun toString(): String =
        "RootWorkspaceSelection(displayName=$displayName, rootKind=$rootKind, writable=$writable)"
}

/** Root identity that only the Root service observed and reported; the Host never stats Root itself. */
data class RootServiceRootIdentity(
    val deviceId: Long,
    val inode: Long,
    val mountId: Long,
    val ownerUid: Int,
    val kind: RootWorkspaceRootKind,
) {
    val isValidKernelObject: Boolean
        get() = deviceId != 0L && inode >= 0L && mountId > 0L && ownerUid >= 0
}

/** Opaque one-shot service handle. Neither the handle nor the selected path is printable. */
class RootWorkspaceProposal internal constructor(
    val provisionalHandle: String,
    val selection: RootWorkspaceSelection,
    val root: RootServiceRootIdentity,
    internal val identity: PrivilegedSessionIdentity,
) {
    override fun toString(): String =
        "RootWorkspaceProposal(kind=${selection.rootKind}, displayName=${selection.displayName})"
}

sealed interface RootWorkspaceProposeOutcome {
    data class Proposed(val proposal: RootWorkspaceProposal) : RootWorkspaceProposeOutcome
    data class Refused(val reason: RootWorkspaceRefusal, val detail: String? = null) : RootWorkspaceProposeOutcome
}

enum class RootWorkspaceRollback { NOT_NEEDED, COMPLETED, INCOMPLETE }

enum class RootWorkspaceRelease { ACCEPTED, REJECTED, TRANSPORT_FAILED, NOT_ATTEMPTED }

/** Closed refusal set. Values describe local protocol/persistence gates, not OS authentication. */
enum class RootWorkspaceRefusal {
    SELECTION_INVALID,
    PATH_INVALID,
    TRANSPORT_FAILED,
    PROPOSE_REJECTED,
    PROPOSE_RESPONSE_INVALID,
    PROVISIONAL_HANDLE_INVALID,
    IDENTITY_MISMATCH,
    LOCAL_WORKSPACE_INVALID,
    STAGE_REJECTED,
    STAGE_RESPONSE_INVALID,
    STAGED_TOKEN_MISMATCH,
    ROOT_IDENTITY_DRIFT,
    LOCATOR_INVALID,
    LOCATOR_MISMATCH,
    SEAL_FAILED,
    LOCATOR_PERSIST_FAILED,
    CONFIRM_REJECTED,
    CONFIRM_RESPONSE_INVALID,
    INTERNAL_FAILURE,
}

/** Admission facts that exist only after the service confirmed and the sealed locator was persisted. */
data class RootAdmittedWorkspace internal constructor(
    val workspaceId: String,
    val workspaceGenerationId: String,
    val workspaceSessionHandle: String,
    val root: RootServiceRootIdentity,
    val keyVersion: Int,
    val origin: RootWorkspaceOrigin,
) {
    override fun toString(): String =
        "RootAdmittedWorkspace(workspaceId=$workspaceId, kind=${root.kind}, keyVersion=$keyVersion)"
}

sealed interface RootWorkspaceAdmissionOutcome {
    data class Admitted(val workspace: RootAdmittedWorkspace) : RootWorkspaceAdmissionOutcome
    data class Refused(
        val reason: RootWorkspaceRefusal,
        val detail: String? = null,
        val release: RootWorkspaceRelease = RootWorkspaceRelease.NOT_ATTEMPTED,
        val localRollback: RootWorkspaceRollback = RootWorkspaceRollback.NOT_NEEDED,
    ) : RootWorkspaceAdmissionOutcome
}

data class RootWorkspaceReleaseOutcome(val release: RootWorkspaceRelease, val detail: String? = null)

/**
 * Host-side initial Root workspace binding over the documented three-phase service protocol.
 *
 * `PROPOSE(user directory) -> STAGE(fresh origin) -> CONFIRM(token)`; the Host creates a brand-new
 * workspace generation, verifies the service-issued locator against that origin and the observed
 * kernel root identity, seals it with the Root AAD cipher, persists it through
 * [RootWorkspaceLocatorRepository.attachNew], and only then confirms. Any failure releases the
 * service handle and removes the freshly created local workspace. This class never creates a grant,
 * never manufactures a Run, and never falls back to a path-only legacy reattach: every request
 * carries an explicit phase.
 */
class RootWorkspaceAdmission(
    private val db: SqlConnection,
    private val transport: RootWorkspaceAdmissionTransport,
    private val locatorCipher: RootLocatorCipher,
    private val keyVersion: Int = 1,
    private val workspaces: WorkspaceRepository = WorkspaceRepository(db),
    private val origins: RootWorkspaceRepository = RootWorkspaceRepository(db),
    private val locators: RootWorkspaceLocatorRepository = RootWorkspaceLocatorRepository(db),
    private val adbBindings: PrivilegedWorkspaceBindingRepository = PrivilegedWorkspaceBindingRepository(db),
) {
    fun propose(selection: RootWorkspaceSelection, execution: RootExecutionStamp): RootWorkspaceProposeOutcome {
        selectionInvalid(selection)?.let { return RootWorkspaceProposeOutcome.Refused(it) }
        val request = baseRequest(PHASE_PROPOSE, execution.identity).copy(
            workspaceRootPath = selection.rootPath,
            rootKindName = selection.rootKind.name,
        )
        val response = call(request) ?: return RootWorkspaceProposeOutcome.Refused(RootWorkspaceRefusal.TRANSPORT_FAILED)
        if (!response.accepted) {
            return RootWorkspaceProposeOutcome.Refused(RootWorkspaceRefusal.PROPOSE_REJECTED, response.errorReason)
        }
        if (response.phase != PHASE_PROPOSE) {
            return RootWorkspaceProposeOutcome.Refused(RootWorkspaceRefusal.PROPOSE_RESPONSE_INVALID, "phase")
        }
        val handle = opaqueHandle(response.provisionalHandle)
            ?: return RootWorkspaceProposeOutcome.Refused(RootWorkspaceRefusal.PROVISIONAL_HANDLE_INVALID)
        val root = observedRoot(response, selection.rootKind)
            ?: return RootWorkspaceProposeOutcome.Refused(RootWorkspaceRefusal.PROPOSE_RESPONSE_INVALID, "root")
        val resolvedPath = response.resolvedRootPath ?: selection.rootPath
        if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(resolvedPath)) {
            releaseHandle(handle, execution.identity, provisional = true)
            return RootWorkspaceProposeOutcome.Refused(RootWorkspaceRefusal.PROPOSE_RESPONSE_INVALID, "resolved_root_path")
        }
        return RootWorkspaceProposeOutcome.Proposed(
            RootWorkspaceProposal(handle, selection.copy(rootPath = resolvedPath), root, execution.identity),
        )
    }

    /** User backed out of the directory dialog. Best effort: the service has no provisional-discard RPC. */
    fun abort(proposal: RootWorkspaceProposal): RootWorkspaceReleaseOutcome {
        if (!isOpaqueId(proposal.provisionalHandle)) {
            return RootWorkspaceReleaseOutcome(RootWorkspaceRelease.NOT_ATTEMPTED, "provisional_handle_invalid")
        }
        return releaseHandle(proposal.provisionalHandle, proposal.identity, provisional = true)
    }

    fun commit(proposal: RootWorkspaceProposal, execution: RootExecutionStamp): RootWorkspaceAdmissionOutcome {
        val identity = execution.identity
        if (identity != proposal.identity) {
            return RootWorkspaceAdmissionOutcome.Refused(RootWorkspaceRefusal.IDENTITY_MISMATCH)
        }
        var workspaceId: String? = null
        var generation: String? = null
        var stagedHandle: String? = null
        var step = RootWorkspaceRefusal.INTERNAL_FAILURE
        try {
            if (!proposal.root.isValidKernelObject || proposal.selection.rootKind != proposal.root.kind) {
                throw AdmissionRejection(RootWorkspaceRefusal.ROOT_IDENTITY_DRIFT)
            }
            step = RootWorkspaceRefusal.LOCAL_WORKSPACE_INVALID
            val created = origins.createNew(proposal.selection.displayName, proposal.selection.writable, execution)
            workspaceId = created.id
            val origin = origins.getOrigin(created.id)
                ?: throw AdmissionRejection(RootWorkspaceRefusal.LOCAL_WORKSPACE_INVALID, "origin")
            generation = origin.generationId
            if (!localWorkspaceIsRoot(created.id, origin, execution)) {
                throw AdmissionRejection(RootWorkspaceRefusal.LOCAL_WORKSPACE_INVALID, "workspace")
            }
            step = RootWorkspaceRefusal.STAGE_REJECTED
            val stage = call(
                baseRequest(PHASE_STAGE, identity).copy(
                    provisionalHandle = proposal.provisionalHandle,
                    workspaceId = created.id,
                    workspaceGenerationId = origin.generationId,
                    origin = origin,
                ),
            ) ?: throw AdmissionRejection(RootWorkspaceRefusal.TRANSPORT_FAILED)
            if (!stage.accepted) throw AdmissionRejection(RootWorkspaceRefusal.STAGE_REJECTED, stage.errorReason)
            if (stage.phase != PHASE_STAGE) throw AdmissionRejection(RootWorkspaceRefusal.STAGE_RESPONSE_INVALID, "phase")
            val handle = opaqueHandle(stage.workspaceSessionHandle)
                ?: throw AdmissionRejection(RootWorkspaceRefusal.STAGE_RESPONSE_INVALID, "handle")
            stagedHandle = handle
            verifyStage(stage, proposal, origin)
            step = RootWorkspaceRefusal.SEAL_FAILED
            val plaintext = stage.locatorBytes
                ?: throw AdmissionRejection(RootWorkspaceRefusal.LOCATOR_INVALID, "missing")
            val locator = try {
                val payload = verifyLocatorPayload(plaintext, proposal, origin)
                    ?: throw AdmissionRejection(RootWorkspaceRefusal.LOCATOR_MISMATCH)
                val ciphertext = locatorCipher.seal(
                    origin = origin,
                    plaintext = plaintext,
                    locatorVersion = payload.locatorVersion,
                    keyVersion = keyVersion,
                    aadVersion = RootWorkspaceLocator.CURRENT_AAD_VERSION,
                ) ?: throw AdmissionRejection(RootWorkspaceRefusal.SEAL_FAILED)
                try {
                    RootWorkspaceLocator(origin, ciphertext, payload.locatorVersion, keyVersion)
                } finally {
                    ciphertext.fill(0)
                }
            } finally {
                plaintext.fill(0)
            }
            step = RootWorkspaceRefusal.LOCATOR_PERSIST_FAILED
            try {
                locators.attachNew(locator, execution)
            } catch (failure: Exception) {
                throw AdmissionRejection(RootWorkspaceRefusal.LOCATOR_PERSIST_FAILED, failure.javaClass.simpleName)
            }
            if (locators.getCurrent(origin.workspaceId, execution) != locator) {
                throw AdmissionRejection(RootWorkspaceRefusal.LOCATOR_PERSIST_FAILED, "not_current")
            }
            step = RootWorkspaceRefusal.CONFIRM_REJECTED
            val confirm = call(
                baseRequest(PHASE_CONFIRM, identity).copy(
                    workspaceId = created.id,
                    workspaceGenerationId = origin.generationId,
                    workspaceSessionHandle = handle,
                    stagedToken = origin.canonicalSha256(),
                ),
            ) ?: throw AdmissionRejection(RootWorkspaceRefusal.TRANSPORT_FAILED)
            if (!confirm.accepted) throw AdmissionRejection(RootWorkspaceRefusal.CONFIRM_REJECTED, confirm.errorReason)
            if (confirm.phase != PHASE_CONFIRM) {
                throw AdmissionRejection(RootWorkspaceRefusal.CONFIRM_RESPONSE_INVALID, "phase")
            }
            if (confirm.workspaceSessionHandle != handle) {
                throw AdmissionRejection(RootWorkspaceRefusal.CONFIRM_RESPONSE_INVALID, "handle")
            }
            val confirmedRoot = observedRoot(confirm, proposal.root.kind)
                ?: throw AdmissionRejection(RootWorkspaceRefusal.CONFIRM_RESPONSE_INVALID, "root")
            if (confirmedRoot != proposal.root) {
                throw AdmissionRejection(RootWorkspaceRefusal.ROOT_IDENTITY_DRIFT, "confirm")
            }
            stagedHandle = null
            return RootWorkspaceAdmissionOutcome.Admitted(
                RootAdmittedWorkspace(created.id, origin.generationId, handle, proposal.root, keyVersion, origin),
            )
        } catch (failure: Throwable) {
            val rejection = failure as? AdmissionRejection
            val release = stagedHandle
                ?.let { releaseHandle(it, identity).release }
                ?: releaseHandle(proposal.provisionalHandle, identity, provisional = true).release
            val rollback = rollbackLocalWorkspace(workspaceId, generation)
            if (failure is Error) throw failure
            return RootWorkspaceAdmissionOutcome.Refused(
                rejection?.reason ?: step,
                rejection?.detail ?: failure.javaClass.simpleName,
                release,
                rollback,
            )
        }
    }

    /** Single-call path for the UI: propose and, after the user's confirmation, commit. */
    fun admit(selection: RootWorkspaceSelection, execution: RootExecutionStamp): RootWorkspaceAdmissionOutcome =
        when (val proposed = propose(selection, execution)) {
            is RootWorkspaceProposeOutcome.Refused ->
                RootWorkspaceAdmissionOutcome.Refused(proposed.reason, proposed.detail)
            is RootWorkspaceProposeOutcome.Proposed -> commit(proposed.proposal, execution)
        }

    private fun selectionInvalid(selection: RootWorkspaceSelection): RootWorkspaceRefusal? = when {
        selection.displayName.isBlank() || selection.displayName.length > MAX_DISPLAY_NAME ->
            RootWorkspaceRefusal.SELECTION_INVALID
        !RootRecoveryLocatorCodec.isValidAbsoluteRootPath(selection.rootPath) -> RootWorkspaceRefusal.PATH_INVALID
        else -> null
    }

    private fun baseRequest(phase: String, identity: PrivilegedSessionIdentity) = WorkspaceAdmissionRequest(
        phase = phase,
        targetServiceInstanceId = identity.serviceInstanceId,
        targetSessionId = identity.sessionId,
        targetIdentityEpoch = identity.identityEpoch,
    )

    private fun call(request: WorkspaceAdmissionRequest): RootServiceBindWorkspaceResponse? = try {
        transport.bindWorkspace(ADMISSION_JSON.encodeToString(WorkspaceAdmissionRequest.serializer(), request))
    } catch (failure: Exception) {
        null
    }

    private fun observedRoot(
        response: RootServiceBindWorkspaceResponse,
        kind: RootWorkspaceRootKind,
    ): RootServiceRootIdentity? {
        val reported = response.rootKindName
            ?.let { name -> runCatching { RootWorkspaceRootKind.valueOf(name) }.getOrNull() }
            ?: kind
        val root = RootServiceRootIdentity(
            deviceId = response.rootDeviceId ?: return null,
            inode = response.rootInode ?: return null,
            mountId = response.rootMountId ?: return null,
            ownerUid = response.rootOwnerUid ?: return null,
            kind = reported,
        )
        return root.takeIf { it.isValidKernelObject && it.kind == kind }
    }

    private fun verifyStage(
        stage: RootServiceBindWorkspaceResponse,
        proposal: RootWorkspaceProposal,
        origin: RootWorkspaceOrigin,
    ) {
        if (stage.stagedToken != origin.canonicalSha256()) {
            throw AdmissionRejection(RootWorkspaceRefusal.STAGED_TOKEN_MISMATCH)
        }
        val root = observedRoot(stage, proposal.root.kind)
            ?: throw AdmissionRejection(RootWorkspaceRefusal.STAGE_RESPONSE_INVALID, "root")
        if (root != proposal.root) throw AdmissionRejection(RootWorkspaceRefusal.ROOT_IDENTITY_DRIFT, "stage")
    }

    /**
     * The service-issued locator is the only proof that the Root FD it opened is the directory the
     * user picked and belongs to this exact origin/generation.
     */
    private fun verifyLocatorPayload(
        plaintext: ByteArray,
        proposal: RootWorkspaceProposal,
        origin: RootWorkspaceOrigin,
    ): RootRecoveryLocatorPayload? {
        val payload = RootRecoveryLocatorCodec.decode(plaintext) ?: return null
        val matches = payload.originSha256 == origin.canonicalSha256() &&
            payload.workspaceId == origin.workspaceId &&
            payload.workspaceGenerationId == origin.generationId &&
            payload.authority == origin.authority &&
            payload.rootKind == proposal.root.kind &&
            payload.absoluteRootPath == proposal.selection.rootPath &&
            payload.rootDeviceId == proposal.root.deviceId &&
            payload.rootInode == proposal.root.inode &&
            payload.rootMountId == proposal.root.mountId
        return payload.takeIf { matches }
    }

    private fun localWorkspaceIsRoot(
        workspaceId: String,
        origin: RootWorkspaceOrigin,
        execution: RootExecutionStamp,
    ): Boolean {
        val workspace = workspaces.get(workspaceId) ?: return false
        val stored = origins.getOrigin(workspaceId) ?: return false
        return workspace.backendType == WorkspaceBackendType.PRIVILEGED &&
            workspace.enabled &&
            workspace.readable &&
            workspace.rootReference == "root:${origin.generationId}" &&
            stored == origin &&
            stored.matches(execution.identity) &&
            adbBindings.get(workspaceId) == null
    }

    private fun releaseHandle(
        handle: String, identity: PrivilegedSessionIdentity, provisional: Boolean = false,
    ): RootWorkspaceReleaseOutcome {
        val request = baseRequest(PHASE_RELEASE, identity).let {
            if (provisional) it.copy(provisionalHandle = handle) else it.copy(workspaceSessionHandle = handle)
        }
        val response = call(request)
            ?: return RootWorkspaceReleaseOutcome(RootWorkspaceRelease.TRANSPORT_FAILED)
        return if (response.accepted) {
            RootWorkspaceReleaseOutcome(RootWorkspaceRelease.ACCEPTED)
        } else {
            RootWorkspaceReleaseOutcome(RootWorkspaceRelease.REJECTED, response.errorReason)
        }
    }

    /**
     * Removes only the workspace this call just created. Never touches rows of another generation;
     * the readback reports an incomplete compensation instead of claiming success.
     */
    private fun rollbackLocalWorkspace(workspaceId: String?, generation: String?): RootWorkspaceRollback {
        if (workspaceId == null || generation == null) return RootWorkspaceRollback.NOT_NEEDED
        val removed = runCatching {
            db.transaction {
                db.execute(
                    "DELETE FROM root_workspace_locators WHERE workspace_id=? AND generation_id=?",
                    listOf(workspaceId, generation),
                )
                db.execute(
                    "DELETE FROM root_workspace_origins WHERE workspace_id=? AND generation_id=?",
                    listOf(workspaceId, generation),
                )
                db.execute(
                    "DELETE FROM workspaces WHERE id=? AND root_reference=?",
                    listOf(workspaceId, "root:$generation"),
                )
                db.query(
                    "SELECT workspace_id FROM root_workspace_origins WHERE workspace_id=? " +
                        "UNION ALL SELECT workspace_id FROM root_workspace_locators WHERE workspace_id=? " +
                        "UNION ALL SELECT id FROM workspaces WHERE id=?",
                    listOf(workspaceId, workspaceId, workspaceId),
                ).isEmpty()
            }
        }.getOrDefault(false)
        return if (removed) RootWorkspaceRollback.COMPLETED else RootWorkspaceRollback.INCOMPLETE
    }

    private fun opaqueHandle(value: String?): String? = value?.takeIf(::isOpaqueId)

    private fun isOpaqueId(value: String): Boolean =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private class AdmissionRejection(
        val reason: RootWorkspaceRefusal,
        val detail: String? = null,
    ) : Exception(detail ?: reason.name)

    private companion object {
        const val PHASE_PROPOSE = "PROPOSE"
        const val PHASE_STAGE = "STAGE"
        const val PHASE_CONFIRM = "CONFIRM"
        const val PHASE_RELEASE = "RELEASE"
        const val MAX_DISPLAY_NAME = 256
        val ADMISSION_JSON = Json { encodeDefaults = true }
    }
}

/** Closed wire shape of the documented selection envelope; unknown keys are never sent. */
@Serializable
private data class WorkspaceAdmissionRequest(
    val phase: String,
    val targetServiceInstanceId: String = "",
    val targetSessionId: String = "",
    val targetIdentityEpoch: Long = 0L,
    val workspaceRootPath: String = "",
    val rootKindName: String = "",
    val provisionalHandle: String = "",
    val workspaceSessionHandle: String = "",
    val workspaceId: String = "",
    val workspaceGenerationId: String = "",
    val stagedToken: String = "",
    @Serializable(with = RootWorkspaceOriginWireSerializer::class)
    val origin: RootWorkspaceOrigin? = null,
) {
    override fun toString(): String =
        "WorkspaceAdmissionRequest(phase=$phase, targetIdentityEpoch=$targetIdentityEpoch, workspaceId=$workspaceId)"
}
