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
import runtime.mobileagent.domain.RootTrustedMountRoots
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
    val cursor: String? = null,
    @Serializable(with = RootWorkspaceOriginWireSerializer::class)
    val origin: RootWorkspaceOrigin? = null,
) {
    override fun toString(): String =
        "RootServiceWorkspaceSelectionRequest(phase=$phase,workspaceId=$workspaceId,redacted=true)"
}

/** Foreground picker data only; never returned by model-facing workspace discovery. */
@Serializable
data class RootDirectoryBrowseEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    /** Observed target of a directory alias; display-only, never used for typed path resolution. */
    val targetPath: String? = null,
)
@Serializable
data class RootDirectoryBrowsePage(
    val path: String, val entries: List<RootDirectoryBrowseEntry>, val nextCursor: String? = null,
)

/**
 * Safe, distinguishable, model-facing name for a newly attached Root workspace.
 *
 * The absolute device path is deliberately not part of the name: the model may read the workspace
 * list, and a full path leaks device layout. The relative tail of the path still distinguishes two
 * Common roots such as `data/local/tmp` and `storage/emulated/0`.
 */
internal fun rootWorkspaceDisplayName(rootPath: String): String {
    val segments = rootPath.trim().split('/').filter { it.isNotBlank() }
    val label = if (segments.isEmpty()) "device" else segments.takeLast(3).joinToString("/")
    return "Root · $label".take(200)
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
    private val mountPolicyProvider: (RootControlPlaneBoundary) -> runtime.mobileagent.domain.RootTrustedMountRoots? = RootMountBoundary::read,
    private val selectionPathResolver: (String) -> java.nio.file.Path = { java.io.File(it).canonicalFile.toPath() },
) {
    private data class ObservedRoot(
        val rootPath: String,
        val rootKind: RootWorkspaceRootKind,
        val identity: RootFdNodeIdentity,
        val mountPolicy: runtime.mobileagent.domain.RootTrustedMountRoots,
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
            "BROWSE" -> browse(req, boundary)
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

    private fun browse(
        req: RootServiceWorkspaceSelectionRequest, boundary: RootControlPlaneBoundary,
    ): RootServiceBindWorkspaceResponse = runCatching {
        val path = selectionPathResolver(req.workspaceRootPath)
        val absolute = AndroidRootPosixFileDriver.toPosixAbsolutePath(path)
        if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(absolute)) return rejected("BROWSE", "workspace_root_invalid")
        val rootFd = fdOps.openDirectoryAbsoluteNoFollow(absolute)
            ?: return rejected("BROWSE", "workspace_root_unavailable")
        rootFd.use { directoryFd ->
            val identity = fdOps.fstatIdentity(directoryFd) ?: return rejected("BROWSE", "workspace_root_unavailable")
            val mounts = mountPolicyProvider(boundary) ?: return rejected("BROWSE", "mountinfo_unavailable")
            if (identity.mountId !in mounts.mountIds) return rejected("BROWSE", "control_plane_protected")
            val rejection = runtime.mobileagent.domain.RootOperationBoundary.verifyWorkspaceRootIdentity(identity, boundary)
            if (rejection != null) return rejected("BROWSE", rejection.name)
            // Foreground browsing lists directories only. Aliases are resolved with their already
            // open target FD solely to display the real target; typed operations keep their
            // O_NOFOLLOW path walk and are not granted the ability to follow aliases.
            val (entries, nextCursor) = browseDirectories(directoryFd, absolute, req.cursor, boundary, mounts)
            RootServiceBindWorkspaceResponse(
                accepted = true,
                phase = "BROWSE",
                directoryPage = RootDirectoryBrowsePage(absolute, entries, nextCursor),
            )
        }
    }.getOrElse { rejected("BROWSE", "directory_unavailable") }

    private fun browseDirectories(
        parentFd: PosixRetainedFd,
        absolute: String,
        cursor: String?,
        boundary: RootControlPlaneBoundary,
        mounts: RootTrustedMountRoots,
    ): Pair<List<RootDirectoryBrowseEntry>, String?> {
        val names = runCatching { fdOps.listChildNames(parentFd) }.getOrNull()
            ?.filter(PosixDirectoryNames::isValidEntryName)
            ?.sorted()
            ?.filter { cursor == null || it > cursor }
            ?: return emptyList<RootDirectoryBrowseEntry>() to null
        val entries = ArrayList<RootDirectoryBrowseEntry>()
        var nextCursor: String? = null
        for ((index, name) in names.withIndex()) {
            if (entries.size >= BROWSE_PAGE_SIZE) {
                // The cursor is the last name already consumed on this page, so the next request
                // resumes exactly there. Files and unresolved links are skipped without stalling
                // pagination.
                nextCursor = names.getOrNull(index - 1)
                break
            }
            val identity = runCatching { fdOps.statAtNoFollow(parentFd, name) }.getOrNull() ?: continue
            if (identity.isSymlink) {
                val target = resolveAliasDirectory(parentFd, name, boundary, mounts) ?: continue
                entries += RootDirectoryBrowseEntry(
                    name = name,
                    path = target,
                    directory = true,
                    targetPath = target,
                )
            } else if (identity.isDirectory) {
                val childPath = childRelativeToAbsolute(absolute, name)
                val childIdentity = openEnterableDirectory(parentFd, name) ?: continue
                if (childIdentity.mountId !in mounts.mountIds) continue
                val rejection = RootFdConfinementPolicy.verifyWorkspaceRoot(
                    childPath, RootWorkspaceRootKind.DIRECTORY, childIdentity, boundary,
                )
                if (rejection != null) continue
                entries += RootDirectoryBrowseEntry(
                    name = name,
                    path = childPath,
                    directory = true,
                )
            }
        }
        return entries to nextCursor
    }

    /** A row is useful only when the directory can actually be opened now. */
    private fun openEnterableDirectory(parentFd: PosixRetainedFd, name: String): RootFdNodeIdentity? {
        val childFd = runCatching {
            fdOps.openChildDirectoryAtNoFollow(parentFd, name)
        }.getOrNull() ?: return null
        return childFd.use {
            val identity = runCatching { fdOps.fstatIdentity(childFd) }.getOrNull() ?: return@use null
            identity.takeIf { it.isDirectory && !it.isSymlink }
        }
    }

    /**
     * Opens an entry's symlink target only to verify that it is an enterable directory and to read
     * the path the kernel resolved. No ID, locator or typed grant is derived from this display-only
     * walk. The resolved target must pass the same root confinement and mount policy as a typed
     * workspace root, so a host-private bind alias is never shown as a selectable directory.
     */
    private fun resolveAliasDirectory(
        parentFd: PosixRetainedFd,
        name: String,
        boundary: RootControlPlaneBoundary,
        mounts: RootTrustedMountRoots,
    ): String? {
        val targetFd = runCatching {
            fdOps.openTargetAt(parentFd, name, followSymlink = true, writable = false)
        }.getOrNull() ?: return null
        return targetFd.use {
            val identity = runCatching { fdOps.fstatIdentity(targetFd) }.getOrNull() ?: return@use null
            if (!identity.isDirectory || identity.isSymlink) return@use null
            val resolvedPath = runCatching { fdOps.readFdResolvedPath(targetFd) }.getOrNull()
                ?: return@use null
            val rejection = RootFdConfinementPolicy.verifyWorkspaceRoot(
                resolvedPath, RootWorkspaceRootKind.DIRECTORY, identity, boundary,
            )
            if (rejection != null) return@use null
            if (identity.mountId !in mounts.mountIds) return@use null
            resolvedPath
        }
    }

    private fun childRelativeToAbsolute(parentAbsolute: String, name: String): String =
        parentAbsolute.trimEnd('/') + "/" + name

    private fun propose(
        req: RootServiceWorkspaceSelectionRequest,
        session: RootServiceSession,
        caller: RootCallerObservation,
        boundary: RootControlPlaneBoundary,
    ): RootServiceBindWorkspaceResponse {
        val path = runCatching { selectionPathResolver(req.workspaceRootPath) }.getOrNull()
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
        val mounts = mountPolicyProvider(boundary)
        if (mounts == null || identity.mountId !in mounts.mountIds) {
            rootFd.close()
            return rejected(PHASE_PROPOSE, "mount_source_unavailable_or_protected")
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
        provisionalRoots[handle] = ObservedRoot(rootPath, rootKind, identity, mounts) { runCatching { rootFd.close() } }
        return accepted(PHASE_PROPOSE, identity, rootKind).copy(provisionalHandle = handle, resolvedRootPath = rootPath)
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
                trustedMountRoots = observed.mountPolicy,
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
        return RootServiceWorkspaceBindings.bind(req, session, caller, boundary, fdOps, workspaceExecutors, mountPolicyProvider)
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

        private const val BROWSE_PAGE_SIZE = 128

        private val RANDOM = SecureRandom()
    }
}
