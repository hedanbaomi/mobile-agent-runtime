// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdConfinementPolicy
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootRecoveryLocatorCodec
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootWorkspaceAttachResult
import runtime.mobileagent.domain.RootWorkspaceFileExecutor
import runtime.mobileagent.domain.RootWorkspaceOrigin
import runtime.mobileagent.domain.RootWorkspaceRootKind
import runtime.mobileagent.domain.canonicalSha256

/**
 * One workspace-control envelope carried by the existing `bindWorkspace(String)` RPC.
 * `phase` is mandatory for selection phases; a request without `phase` stays the legacy
 * reattach path so no path-only request can ever become an initial binding.
 */
@Serializable
internal data class RootServiceWorkspaceSelectionRequest(
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
        "RootServiceWorkspaceSelectionRequest(phase=$phase,workspaceId=$workspaceId,redacted=true)"
}

/**
 * Service-side three-phase workspace selection:
 * `PROPOSE(user-selected path) -> STAGE(origin) -> CONFIRM(token)`, with `RELEASE` for the
 * staged/active handle and the legacy locator-authenticated `REATTACH` for recovery only.
 *
 * The service observes the root FD itself and never creates a grant: policy/Keystore
 * persistence and grant creation stay on the Host after explicit user confirmation.
 */
internal class RootServiceWorkspaceSelection(
    private val fdOps: PosixFdOperations,
    private val workspaceExecutors: ConcurrentHashMap<String, BoundWorkspaceExecutor>,
    private val json: Json = RootPrivilegedServiceEngine.json,
) {
    private data class ObservedRoot(
        val rootPath: String,
        val rootKind: RootWorkspaceRootKind,
        val identity: RootFdNodeIdentity,
        val onCloseFd: () -> Unit = {},
    )

    private val provisionalRoots = ConcurrentHashMap<String, ObservedRoot>()
    private val stagedRoots = ConcurrentHashMap<String, ObservedRoot>()
    private val activeHandles = ConcurrentHashMap<String, String>()
    private val activeCloseCallbacks = ConcurrentHashMap<String, () -> Unit>()

    fun openStagingFdCount(): Int = provisionalRoots.size + stagedRoots.size

    fun dispatch(
        requestJson: String,
        session: RootServiceSession?,
        caller: RootCallerObservation?,
        boundary: RootControlPlaneBoundary?,
        closed: Boolean,
    ): RootServiceBindWorkspaceResponse {
        val phase = readPhase(requestJson)
        if (phase.isBlank() || phase == PHASE_REATTACH) {
            return legacyReattach(requestJson, session, caller, boundary, closed)
        }
        val req = runCatching {
            json.decodeFromString<RootServiceWorkspaceSelectionRequest>(requestJson)
        }.getOrNull() ?: return rejected(phase, "invalid_request")
        if (closed || session == null || caller == null || boundary == null) {
            return rejected(phase, "session_inactive")
        }
        if (!caller.matches(session.identity) || !targetMatches(req, session)) {
            return rejected(phase, "identity_mismatch")
        }
        return when (phase) {
            PHASE_PROPOSE -> propose(req, session, caller, boundary)
            PHASE_STAGE -> stage(req, session, caller)
            PHASE_CONFIRM -> confirm(req, session, caller, boundary)
            PHASE_CHECK -> check(req, session, caller)
            PHASE_RELEASE -> release(req, session, caller)
            else -> rejected(phase, "unknown_phase")
        }
    }

    fun close() {
        provisionalRoots.values.toList().forEach { runCatching { it.onCloseFd() } }
        stagedRoots.values.toList().forEach { runCatching { it.onCloseFd() } }
        activeCloseCallbacks.values.toList().forEach { runCatching { it() } }
        provisionalRoots.clear()
        stagedRoots.clear()
        activeHandles.clear()
        activeCloseCallbacks.clear()
    }

    private fun propose(
        req: RootServiceWorkspaceSelectionRequest,
        session: RootServiceSession,
        caller: RootCallerObservation,
        boundary: RootControlPlaneBoundary,
    ): RootServiceBindWorkspaceResponse {
        val path = runCatching { Paths.get(req.workspaceRootPath) }.getOrNull()
            ?: return rejected(PHASE_PROPOSE, "workspace_root_invalid")
        val rootPath = AndroidRootPosixFileDriver.toPosixAbsolutePath(path)
        if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(rootPath)) {
            return rejected(PHASE_PROPOSE, "workspace_root_invalid")
        }
        val rootKind = runCatching {
            RootWorkspaceRootKind.valueOf(req.rootKindName.ifBlank { RootWorkspaceRootKind.DIRECTORY.name })
        }.getOrNull() ?: return rejected(PHASE_PROPOSE, "root_kind_invalid")
        val rootFd = fdOps.openDirectoryAbsoluteNoFollow(rootPath)
            ?: return rejected(PHASE_PROPOSE, "workspace_root_unavailable")
        val identity = fdOps.fstatIdentity(rootFd)
        if (identity == null || !identity.isDirectory || identity.isSymlink) {
            runCatching { rootFd.close() }
            return rejected(PHASE_PROPOSE, "workspace_root_invalid")
        }
        val rejection = RootFdConfinementPolicy.verifyWorkspaceRoot(rootPath, rootKind, identity, boundary)
        if (rejection != null) {
            runCatching { rootFd.close() }
            return rejected(PHASE_PROPOSE, rejection.name)
        }
        val handle = newOpaqueId("ws-prov")
        val stageRejection = session.stageProvisionalDirectory(
            caller = caller,
            targetServiceInstanceId = req.targetServiceInstanceId,
            targetSessionId = req.targetSessionId,
            targetIdentityEpoch = req.targetIdentityEpoch,
            provisionalHandle = handle,
            rootPath = rootPath,
            rootKind = rootKind,
            directoryIdentity = identity,
            onCloseFd = { runCatching { rootFd.close() } },
        )
        if (stageRejection != null) return rejected(PHASE_PROPOSE, stageRejection.name)
        provisionalRoots[handle] = ObservedRoot(rootPath, rootKind, identity) { runCatching { rootFd.close() } }
        return accepted(PHASE_PROPOSE, identity, rootKind).copy(provisionalHandle = handle)
    }

    private fun stage(
        req: RootServiceWorkspaceSelectionRequest,
        session: RootServiceSession,
        caller: RootCallerObservation,
    ): RootServiceBindWorkspaceResponse {
        val observed = provisionalRoots[req.provisionalHandle]
            ?: return rejected(PHASE_STAGE, "provisional_handle_invalid")
        val origin = req.origin ?: return rejected(PHASE_STAGE, "origin_required")
        if (!origin.matches(session.identity)) return rejected(PHASE_STAGE, "origin_mismatch")
        val handle = req.workspaceSessionHandle.ifBlank { newOpaqueId("ws-sess") }
        val token = origin.canonicalSha256()
        val result = session.attachProvisionalWorkspace(
            caller = caller,
            targetServiceInstanceId = req.targetServiceInstanceId,
            targetSessionId = req.targetSessionId,
            targetIdentityEpoch = req.targetIdentityEpoch,
            provisionalHandle = req.provisionalHandle,
            workspaceSessionHandle = handle,
            origin = origin,
            originSha256 = token,
        )
        return when (result) {
            is RootWorkspaceAttachResult.Staged -> {
                val locator = result.locatorBytesCopy()
                result.zeroize()
                provisionalRoots.remove(req.provisionalHandle)
                stagedRoots[handle] = observed
                accepted(PHASE_STAGE, observed.identity, observed.rootKind).copy(
                    workspaceSessionHandle = handle,
                    stagedToken = token,
                    locatorBytes = locator,
                )
            }
            is RootWorkspaceAttachResult.Rejected -> rejected(PHASE_STAGE, result.reason.name)
            RootWorkspaceAttachResult.UntrustedCaller -> rejected(PHASE_STAGE, "identity_mismatch")
            RootWorkspaceAttachResult.SessionInactive -> rejected(PHASE_STAGE, "session_inactive")
        }
    }

    private fun confirm(
        req: RootServiceWorkspaceSelectionRequest,
        session: RootServiceSession,
        caller: RootCallerObservation,
        boundary: RootControlPlaneBoundary,
    ): RootServiceBindWorkspaceResponse {
        val observed = stagedRoots[req.workspaceSessionHandle]
            ?: return rejected(PHASE_CONFIRM, "staged_handle_invalid")
        workspaceExecutors[req.workspaceId]?.let { existing ->
            if (!existing.isClosed() || existing.openFdCount() > 0) {
                return rejected(PHASE_CONFIRM, "workspace_already_bound")
            }
            workspaceExecutors.remove(req.workspaceId, existing)
        }
        val current = fdOps.inspectDirectoryIdentityNoFollow(Paths.get(observed.rootPath))
        if (current == null || !current.sameKernelObjectAs(observed.identity)) {
            return rejected(PHASE_CONFIRM, "workspace_root_changed")
        }
        val confirmed = session.confirmStagedWorkspace(
            caller = caller,
            targetServiceInstanceId = req.targetServiceInstanceId,
            targetSessionId = req.targetSessionId,
            targetIdentityEpoch = req.targetIdentityEpoch,
            workspaceId = req.workspaceId,
            workspaceGenerationId = req.workspaceGenerationId,
            workspaceSessionHandle = req.workspaceSessionHandle,
            originSha256 = req.stagedToken,
        )
        if (!confirmed) return rejected(PHASE_CONFIRM, "staged_handle_invalid")
        stagedRoots.remove(req.workspaceSessionHandle)
        activeHandles[req.workspaceSessionHandle] = req.workspaceId
        activeCloseCallbacks[req.workspaceSessionHandle] = observed.onCloseFd
        val driver = AndroidRootPosixFileDriver(Paths.get(observed.rootPath), fdOps)
        val executor = RootWorkspaceFileExecutor(
            workspaceRootIdentity = current,
            controlPlane = boundary,
            driver = driver,
            confirmedMountIds = emptySet(),
        )
        workspaceExecutors[req.workspaceId] = BoundWorkspaceExecutor(observed.rootPath, driver, executor)
        return accepted(PHASE_CONFIRM, current, observed.rootKind).copy(
            workspaceSessionHandle = req.workspaceSessionHandle,
        )
    }

    /**
     * Host CHECK before every dispatch: this session must still hold the exact
     * `(workspaceId, generationId, handle)` active binding and a registered executor. It reads
     * the domain binding table only — no path reopen, no trust in a Host-supplied handle.
     */
    private fun check(
        req: RootServiceWorkspaceSelectionRequest,
        session: RootServiceSession,
        caller: RootCallerObservation,
    ): RootServiceBindWorkspaceResponse {
        val bound = session.verifyActiveWorkspaceBinding(
            caller = caller,
            targetServiceInstanceId = req.targetServiceInstanceId,
            targetSessionId = req.targetSessionId,
            targetIdentityEpoch = req.targetIdentityEpoch,
            workspaceId = req.workspaceId,
            workspaceGenerationId = req.workspaceGenerationId,
            workspaceSessionHandle = req.workspaceSessionHandle,
        )
        if (!bound) return rejected(PHASE_CHECK, "workspace_binding_inactive")
        if (workspaceExecutors[req.workspaceId]?.takeIf { !it.isClosed() } == null) {
            return rejected(PHASE_CHECK, "workspace_executor_unbound")
        }
        return RootServiceBindWorkspaceResponse(
            accepted = true,
            phase = PHASE_CHECK,
            workspaceId = req.workspaceId,
            workspaceGenerationId = req.workspaceGenerationId,
            workspaceSessionHandle = req.workspaceSessionHandle,
        )
    }

    private fun release(
        req: RootServiceWorkspaceSelectionRequest,
        session: RootServiceSession,
        caller: RootCallerObservation,
    ): RootServiceBindWorkspaceResponse {
        if (req.provisionalHandle.isNotBlank()) {
            val observed = provisionalRoots.remove(req.provisionalHandle)
            val released = session.releaseProvisionalWorkspace(
                caller = caller,
                targetServiceInstanceId = req.targetServiceInstanceId,
                targetSessionId = req.targetSessionId,
                targetIdentityEpoch = req.targetIdentityEpoch,
                provisionalHandle = req.provisionalHandle,
            )
            if (!released) return rejected(PHASE_RELEASE, "provisional_handle_invalid")
            return RootServiceBindWorkspaceResponse(
                accepted = true,
                phase = PHASE_RELEASE,
                provisionalHandle = req.provisionalHandle,
                rootInode = observed?.identity?.inode,
                rootDeviceId = observed?.identity?.deviceId,
                rootMountId = observed?.identity?.mountId,
                rootOwnerUid = observed?.identity?.ownerUid,
                rootKindName = observed?.rootKind?.name,
            )
        }
        val handle = req.workspaceSessionHandle
        val activeWorkspaceId = activeHandles.remove(handle)
        if (activeWorkspaceId != null) {
            // The domain keeps the active entry until session close; removing the executor makes
            // every later dispatch fail closed instead of using a released root.
            runCatching { activeCloseCallbacks.remove(handle)?.invoke() }
            workspaceExecutors[activeWorkspaceId]?.let { bound ->
                bound.close()
                if (bound.openFdCount() == 0) {
                    workspaceExecutors.remove(activeWorkspaceId, bound)
                }
            }
            return RootServiceBindWorkspaceResponse(
                accepted = true, phase = PHASE_RELEASE, workspaceSessionHandle = handle,
                errorReason = "active_entry_closed_on_session_end",
            )
        }
        val released = session.releaseStagedWorkspace(
            caller = caller,
            targetServiceInstanceId = req.targetServiceInstanceId,
            targetSessionId = req.targetSessionId,
            targetIdentityEpoch = req.targetIdentityEpoch,
            workspaceSessionHandle = handle,
        )
        if (!released) return rejected(PHASE_RELEASE, "staged_handle_invalid")
        stagedRoots.remove(handle)
        return RootServiceBindWorkspaceResponse(
            accepted = true, phase = PHASE_RELEASE, workspaceSessionHandle = handle,
        )
    }

    private fun legacyReattach(
        requestJson: String,
        session: RootServiceSession?,
        caller: RootCallerObservation?,
        boundary: RootControlPlaneBoundary?,
        closed: Boolean,
    ): RootServiceBindWorkspaceResponse {
        val req = runCatching {
            json.decodeFromString<RootServiceBindWorkspaceRequest>(requestJson)
        }.getOrNull() ?: return rejected(PHASE_REATTACH, "invalid_request")
        if (closed || session == null || caller == null || boundary == null) {
            return rejected(PHASE_REATTACH, "session_inactive")
        }
        return RootServiceWorkspaceBindings.bind(req, session, caller, boundary, fdOps, workspaceExecutors)
            .copy(phase = PHASE_REATTACH)
    }

    private fun readPhase(raw: String): String = runCatching {
        json.parseToJsonElement(raw).jsonObject["phase"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")

    private fun targetMatches(
        req: RootServiceWorkspaceSelectionRequest,
        session: RootServiceSession,
    ): Boolean {
        val id = session.identity
        return req.targetServiceInstanceId == id.serviceInstanceId &&
            req.targetSessionId == id.sessionId &&
            req.targetIdentityEpoch == id.identityEpoch
    }

    private fun accepted(
        phase: String,
        identity: RootFdNodeIdentity,
        rootKind: RootWorkspaceRootKind,
    ): RootServiceBindWorkspaceResponse = RootServiceBindWorkspaceResponse(
        accepted = true,
        rootInode = identity.inode,
        phase = phase,
        rootDeviceId = identity.deviceId,
        rootMountId = identity.mountId,
        rootOwnerUid = identity.ownerUid,
        rootKindName = rootKind.name,
    )

    private fun rejected(phase: String, reason: String): RootServiceBindWorkspaceResponse =
        RootServiceBindWorkspaceResponse(accepted = false, errorReason = reason, phase = phase)

    private fun newOpaqueId(prefix: String): String {
        val bytes = ByteArray(16)
        RANDOM.nextBytes(bytes)
        return prefix + "-" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val PHASE_REATTACH: String = "REATTACH"
        const val PHASE_PROPOSE: String = "PROPOSE"
        const val PHASE_STAGE: String = "STAGE"
        const val PHASE_CONFIRM: String = "CONFIRM"
        const val PHASE_CHECK: String = "CHECK"
        const val PHASE_RELEASE: String = "RELEASE"

        private val RANDOM = SecureRandom()
    }
}
