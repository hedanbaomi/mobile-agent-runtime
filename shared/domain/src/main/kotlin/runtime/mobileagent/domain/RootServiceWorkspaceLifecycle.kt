// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

sealed interface RootWorkspaceAttachResult {
    class Staged(
        val workspaceId: String,
        val workspaceGenerationId: String,
        val workspaceSessionHandle: String,
        val originSha256: String,
        val locatorVersion: Int,
        locatorBytes: ByteArray,
    ) : RootWorkspaceAttachResult {
        private val bytes = locatorBytes.copyOf()

        fun locatorBytesCopy(): ByteArray = bytes.copyOf()

        fun zeroize() {
            bytes.fill(0)
        }

        override fun toString(): String =
            "RootWorkspaceAttachResult.Staged(workspaceId=$workspaceId, handle=$workspaceSessionHandle, locatorVersion=$locatorVersion)"
    }

    data class Rejected(val reason: RootConfinementRejection) : RootWorkspaceAttachResult
    data object UntrustedCaller : RootWorkspaceAttachResult
    data object SessionInactive : RootWorkspaceAttachResult
}

sealed interface RootWorkspaceReattachResult {
    data class Reattached(
        val workspaceId: String,
        val workspaceGenerationId: String,
        val workspaceSessionHandle: String,
        val rootKind: RootWorkspaceRootKind,
    ) : RootWorkspaceReattachResult

    data class Rejected(val reason: RootConfinementRejection) : RootWorkspaceReattachResult
    data object UntrustedCaller : RootWorkspaceReattachResult
    data object SessionInactive : RootWorkspaceReattachResult
}

internal class RootServiceWorkspaceStore(
    private val identity: PrivilegedSessionIdentity,
    private var controlPlane: RootControlPlaneBoundary? = null,
) {
    private class FdHolder(private val onCloseFd: () -> Unit) {
        private var closed = false
        fun closeOnce() {
            if (closed) return
            closed = true
            runCatching { onCloseFd() }
        }
    }

    private data class ProvisionalEntry(
        val handle: String,
        val rootPath: String,
        val rootKind: RootWorkspaceRootKind,
        val directoryIdentity: RootFdNodeIdentity,
        val fdHolder: FdHolder,
    )

    private data class StagedEntry(
        val origin: RootWorkspaceOrigin,
        val originSha256: String,
        val workspaceSessionHandle: String,
        val rootKind: RootWorkspaceRootKind,
        val directoryIdentity: RootFdNodeIdentity,
        val fdHolder: FdHolder,
    )

    private data class ActiveEntry(
        val workspaceId: String,
        val workspaceGenerationId: String,
        val workspaceSessionHandle: String,
        val rootKind: RootWorkspaceRootKind,
        val directoryIdentity: RootFdNodeIdentity,
        val fdHolder: FdHolder,
    )

    private val provisional = LinkedHashMap<String, ProvisionalEntry>()
    private val staged = LinkedHashMap<String, StagedEntry>()
    private val active = LinkedHashMap<String, ActiveEntry>()

    fun configureControlPlane(boundary: RootControlPlaneBoundary) {
        controlPlane = boundary
    }

    fun stageProvisional(
        provisionalHandle: String,
        rootPath: String,
        rootKind: RootWorkspaceRootKind,
        directoryIdentity: RootFdNodeIdentity,
        onCloseFd: () -> Unit,
    ): RootConfinementRejection? {
        val holder = FdHolder(onCloseFd)
        val cp = controlPlane ?: run {
            holder.closeOnce()
            return RootConfinementRejection.CONTROL_PLANE_PROTECTED
        }
        if (!RootPathGuards.isOpaqueId(provisionalHandle) || isHandleUsed(provisionalHandle)) {
            holder.closeOnce()
            return RootConfinementRejection.PROVISIONAL_HANDLE_INVALID
        }
        if (provisional.size >= MAX_PROVISIONAL_HANDLES) {
            holder.closeOnce()
            return RootConfinementRejection.CAPACITY_EXCEEDED
        }
        val rejection = RootFdConfinementPolicy.verifyWorkspaceRoot(rootPath, rootKind, directoryIdentity, cp)
        if (rejection != null) {
            holder.closeOnce()
            return rejection
        }
        provisional[provisionalHandle] = ProvisionalEntry(
            handle = provisionalHandle,
            rootPath = rootPath,
            rootKind = rootKind,
            directoryIdentity = directoryIdentity,
            fdHolder = holder,
        )
        return null
    }

    fun attachProvisional(
        provisionalHandle: String,
        workspaceSessionHandle: String,
        origin: RootWorkspaceOrigin,
        originSha256: String,
    ): RootWorkspaceAttachResult {
        val entry = provisional.remove(provisionalHandle)
            ?: return RootWorkspaceAttachResult.Rejected(RootConfinementRejection.PROVISIONAL_HANDLE_INVALID)
        val cp = controlPlane ?: run {
            entry.fdHolder.closeOnce()
            return RootWorkspaceAttachResult.Rejected(RootConfinementRejection.CONTROL_PLANE_PROTECTED)
        }
        if (!origin.matches(identity) || origin.canonicalSha256() != originSha256) {
            entry.fdHolder.closeOnce()
            return RootWorkspaceAttachResult.Rejected(RootConfinementRejection.ORIGIN_MISMATCH)
        }
        if (!RootPathGuards.isOpaqueId(workspaceSessionHandle) || isHandleUsed(workspaceSessionHandle)) {
            entry.fdHolder.closeOnce()
            return RootWorkspaceAttachResult.Rejected(RootConfinementRejection.STAGED_HANDLE_INVALID)
        }
        if (staged.size >= MAX_STAGED_WORKSPACES || active.size >= MAX_ACTIVE_WORKSPACES) {
            entry.fdHolder.closeOnce()
            return RootWorkspaceAttachResult.Rejected(RootConfinementRejection.CAPACITY_EXCEEDED)
        }
        val rootCheck = RootFdConfinementPolicy.verifyWorkspaceRoot(
            entry.rootPath,
            entry.rootKind,
            entry.directoryIdentity,
            cp,
        )
        if (rootCheck != null) {
            entry.fdHolder.closeOnce()
            return RootWorkspaceAttachResult.Rejected(rootCheck)
        }
        val locatorBytes = runCatching {
            RootRecoveryLocatorCodec.encode(
                RootRecoveryLocatorPayload(
                    locatorVersion = RootRecoveryLocatorCodec.CURRENT_VERSION,
                    originSha256 = originSha256,
                    workspaceId = origin.workspaceId,
                    workspaceGenerationId = origin.generationId,
                    authority = origin.authority,
                    rootKind = entry.rootKind,
                    absoluteRootPath = entry.rootPath,
                    rootDeviceId = entry.directoryIdentity.deviceId,
                    rootInode = entry.directoryIdentity.inode,
                    rootMountId = entry.directoryIdentity.mountId,
                ),
            )
        }.getOrElse {
            entry.fdHolder.closeOnce()
            return RootWorkspaceAttachResult.Rejected(RootConfinementRejection.LOCATOR_INVALID)
        }
        staged[workspaceSessionHandle] = StagedEntry(
            origin = origin,
            originSha256 = originSha256,
            workspaceSessionHandle = workspaceSessionHandle,
            rootKind = entry.rootKind,
            directoryIdentity = entry.directoryIdentity,
            fdHolder = entry.fdHolder,
        )
        return RootWorkspaceAttachResult.Staged(
            workspaceId = origin.workspaceId,
            workspaceGenerationId = origin.generationId,
            workspaceSessionHandle = workspaceSessionHandle,
            originSha256 = originSha256,
            locatorVersion = RootRecoveryLocatorCodec.CURRENT_VERSION,
            locatorBytes = locatorBytes,
        ).also { locatorBytes.fill(0) }
    }

    fun confirmStaged(
        workspaceId: String,
        workspaceGenerationId: String,
        workspaceSessionHandle: String,
        originSha256: String,
    ): Boolean {
        val entry = staged[workspaceSessionHandle] ?: return false
        if (entry.origin.workspaceId != workspaceId ||
            entry.origin.generationId != workspaceGenerationId ||
            entry.originSha256 != originSha256 ||
            active.size >= MAX_ACTIVE_WORKSPACES
        ) {
            return false
        }
        staged.remove(workspaceSessionHandle)
        active[workspaceSessionHandle] = ActiveEntry(
            workspaceId = workspaceId,
            workspaceGenerationId = workspaceGenerationId,
            workspaceSessionHandle = workspaceSessionHandle,
            rootKind = entry.rootKind,
            directoryIdentity = entry.directoryIdentity,
            fdHolder = entry.fdHolder,
        )
        return true
    }

    fun releaseStaged(workspaceSessionHandle: String): Boolean {
        val entry = staged.remove(workspaceSessionHandle) ?: return false
        entry.fdHolder.closeOnce()
        return true
    }

    /**
     * Releases a provisional handle before any origin is bound, closing its retained FD
     * immediately instead of waiting for session teardown.
     */
    fun releaseProvisional(provisionalHandle: String): Boolean {
        val entry = provisional.remove(provisionalHandle) ?: return false
        entry.fdHolder.closeOnce()
        return true
    }

    fun reattach(
        workspaceSessionHandle: String,
        locatorBytes: ByteArray?,
        origin: RootWorkspaceOrigin,
        originSha256: String,
        reopenedRootPath: String,
        reopenedIdentity: RootFdNodeIdentity,
        onCloseFd: () -> Unit,
    ): RootWorkspaceReattachResult {
        val holder = FdHolder(onCloseFd)
        val locatorCopy = locatorBytes?.copyOf()
        try {
            val cp = controlPlane ?: run {
                holder.closeOnce()
                return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.CONTROL_PLANE_PROTECTED)
            }
            if (!origin.matches(identity)) {
                holder.closeOnce()
                return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.ORIGIN_MISMATCH)
            }
            if (!RootPathGuards.isOpaqueId(workspaceSessionHandle) || isHandleUsed(workspaceSessionHandle)) {
                holder.closeOnce()
                return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.STAGED_HANDLE_INVALID)
            }
            if (active.size >= MAX_ACTIVE_WORKSPACES) {
                holder.closeOnce()
                return RootWorkspaceReattachResult.Rejected(RootConfinementRejection.CAPACITY_EXCEEDED)
            }
            val (decoded, rejection) = RootFdConfinementPolicy.verifyReattachLocator(
                locatorBytes = locatorCopy,
                origin = origin,
                expectedOriginSha256 = originSha256,
                reopenedRootPath = reopenedRootPath,
                reopenedIdentity = reopenedIdentity,
                controlPlane = cp,
            )
            if (rejection != null || decoded == null) {
                holder.closeOnce()
                return RootWorkspaceReattachResult.Rejected(rejection ?: RootConfinementRejection.LOCATOR_INVALID)
            }
            active[workspaceSessionHandle] = ActiveEntry(
                workspaceId = origin.workspaceId,
                workspaceGenerationId = origin.generationId,
                workspaceSessionHandle = workspaceSessionHandle,
                rootKind = decoded.rootKind,
                directoryIdentity = reopenedIdentity,
                fdHolder = holder,
            )
            return RootWorkspaceReattachResult.Reattached(
                workspaceId = origin.workspaceId,
                workspaceGenerationId = origin.generationId,
                workspaceSessionHandle = workspaceSessionHandle,
                rootKind = decoded.rootKind,
            )
        } finally {
            locatorCopy?.fill(0)
        }
    }

    /**
     * Test/synthetic harness helper only. Production workspace attachment must go
     * through [stageProvisionalDirectory] -> [attachProvisional] -> [confirmStaged]
     * or [reattach].
     */
    fun bindDirectForTestOrInternal(
        workspaceId: String,
        workspaceGenerationId: String,
        workspaceSessionHandle: String,
        onCloseFd: () -> Unit = {},
    ) {
        active.remove(workspaceSessionHandle)?.fdHolder?.closeOnce()
        check(active.size < MAX_ACTIVE_WORKSPACES) { "Too many active Root workspaces in session" }
        active[workspaceSessionHandle] = ActiveEntry(
            workspaceId = workspaceId,
            workspaceGenerationId = workspaceGenerationId,
            workspaceSessionHandle = workspaceSessionHandle,
            rootKind = RootWorkspaceRootKind.DIRECTORY,
            directoryIdentity = RootFdNodeIdentity(1L, 1L, 1L, true, false, false, 1L, 0b111_000_000, 0, 0),
            fdHolder = FdHolder(onCloseFd),
        )
    }

    fun isActive(
        workspaceId: String,
        workspaceGenerationId: String,
        workspaceSessionHandle: String,
    ): Boolean {
        val entry = active[workspaceSessionHandle] ?: return false
        return entry.workspaceId == workspaceId && entry.workspaceGenerationId == workspaceGenerationId
    }

    fun closeAll() {
        val holders = buildList {
            provisional.values.forEach { add(it.fdHolder) }
            staged.values.forEach { add(it.fdHolder) }
            active.values.forEach { add(it.fdHolder) }
        }
        provisional.clear()
        staged.clear()
        active.clear()
        holders.forEach { it.closeOnce() }
    }

    private fun isHandleUsed(handle: String): Boolean =
        handle in provisional || handle in staged || handle in active

    companion object {
        const val MAX_PROVISIONAL_HANDLES: Int = 64
        const val MAX_STAGED_WORKSPACES: Int = 16
        const val MAX_ACTIVE_WORKSPACES: Int = 64
    }
}
