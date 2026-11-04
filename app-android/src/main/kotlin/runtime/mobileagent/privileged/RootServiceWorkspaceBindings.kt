// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootWorkspaceFileExecutor
import runtime.mobileagent.domain.RootWorkspaceReattachResult
import runtime.mobileagent.domain.canonicalSha256

internal data class BoundWorkspaceExecutor(
    val rootPath: String,
    val driver: AndroidRootPosixFileDriver,
    val executor: RootWorkspaceFileExecutor,
) : AutoCloseable {
    fun openFdCount(): Int = driver.openFdCount()

    fun isClosed(): Boolean = driver.isClosed()

    override fun close() {
        runCatching { driver.close() }
    }
}

/** Reattachment only: initial workspace creation still requires the three-phase UI protocol. */
internal object RootServiceWorkspaceBindings {
    fun bind(
        req: RootServiceBindWorkspaceRequest,
        session: RootServiceSession,
        caller: RootCallerObservation,
        boundary: RootControlPlaneBoundary,
        fdOps: PosixFdOperations,
        workspaceExecutors: ConcurrentHashMap<String, BoundWorkspaceExecutor>,
        mountPolicyProvider: (RootControlPlaneBoundary) -> runtime.mobileagent.domain.RootTrustedMountRoots? = RootMountBoundary::read,
    ): RootServiceBindWorkspaceResponse {
        val id = session.identity
        if (!caller.matches(id) || req.targetServiceInstanceId != id.serviceInstanceId ||
            req.targetSessionId != id.sessionId || req.targetIdentityEpoch != id.identityEpoch) {
            return rejected("identity_mismatch")
        }
        val path = runCatching { Paths.get(req.workspaceRootPath) }.getOrNull()
            ?: return rejected("workspace_root_invalid")
        val rootIdentity = fdOps.inspectDirectoryIdentityNoFollow(path)
        if (rootIdentity == null || !rootIdentity.isDirectory || rootIdentity.isSymlink) {
            return rejected("workspace_root_invalid")
        }
        val mounts = mountPolicyProvider(boundary) ?: return rejected("mountinfo_unavailable")
        if (rootIdentity.mountId !in mounts.mountIds || boundary.isProtected(req.workspaceRootPath, rootIdentity)) {
            return rejected("control_plane_protected")
        }
        if (req.expectedRootDeviceId <= 0L || req.expectedRootInode < 0L || req.expectedRootMountId <= 0L ||
            req.expectedRootDeviceId != rootIdentity.deviceId || req.expectedRootInode != rootIdentity.inode ||
            req.expectedRootMountId != rootIdentity.mountId) {
            return rejected("workspace_root_identity_mismatch")
        }
        val origin = req.origin
        val locator = req.recoveryLocator
        if (origin == null || locator == null) {
            return rejected("workspace_binding_protocol_unsupported")
        }
        try {
            if (req.confirmedMountIds.isNotEmpty()) return rejected("mount_roots_unsupported")
            if (origin.workspaceId != req.workspaceId || origin.generationId != req.workspaceGenerationId) {
                return rejected("workspace_origin_mismatch")
            }
            workspaceExecutors[req.workspaceId]?.let { existing ->
                if (!existing.isClosed() || existing.openFdCount() > 0) return rejected("workspace_already_bound")
                workspaceExecutors.remove(req.workspaceId, existing)
            }
            val driver = AndroidRootPosixFileDriver(path, fdOps)
            val bound = BoundWorkspaceExecutor(req.workspaceRootPath, driver, RootWorkspaceFileExecutor(
                workspaceRootIdentity = rootIdentity, controlPlane = boundary, driver = driver,
                confirmedMountIds = emptySet(),
                trustedMountRoots = mounts,
            ))
            val result = session.reattachWorkspace(
                caller, req.targetServiceInstanceId, req.targetSessionId, req.targetIdentityEpoch,
                req.workspaceSessionHandle, locator, origin, origin.canonicalSha256(),
                req.workspaceRootPath, rootIdentity,
                onCloseFd = {
                    bound.close()
                    if (bound.openFdCount() == 0) {
                        workspaceExecutors.remove(req.workspaceId, bound)
                    }
                },
            )
            if (result !is RootWorkspaceReattachResult.Reattached) {
                bound.close()
                return rejected("workspace_reattach_rejected")
            }
            workspaceExecutors[req.workspaceId] = bound
            return RootServiceBindWorkspaceResponse(true, rootInode = rootIdentity.inode)
        } finally {
            locator.fill(0)
        }
    }

    private fun rejected(reason: String) = RootServiceBindWorkspaceResponse(false, errorReason = reason)
}
