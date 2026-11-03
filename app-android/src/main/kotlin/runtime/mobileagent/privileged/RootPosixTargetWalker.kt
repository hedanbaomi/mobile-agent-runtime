// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.RootFdNodeIdentity

/** FD-pinned component walk; every directory is verified before its child is opened. */
internal class RootPosixTargetWalker(
    private val fdOps: PosixFdOperations,
    private val openTracked: (() -> PosixRetainedFd?) -> PosixRetainedFd?,
    private val verifyWalkDirectory: (PosixRetainedFd, Boolean) -> Boolean,
    private val verifyAncestorDirectory: (RootFdNodeIdentity) -> Boolean = { true },
) {
    data class Walk(
        val fd: PosixRetainedFd,
        val chain: List<RootFdNodeIdentity>,
        val ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
    ) : AutoCloseable {
        val fullChain: List<RootFdNodeIdentity>
            get() = if (ancestorsAboveRoot.isEmpty()) chain else ancestorsAboveRoot + chain

        override fun close() {
            runCatching { fd.close() }
        }
    }

    fun openRoot(rootPosixPath: String): Walk? {
        var ancestors: List<RootFdNodeIdentity> = emptyList()
        val rootFd = openTracked {
            fdOps.openDirectoryWithAncestorsNoFollow(rootPosixPath)?.also { ancestors = it.second }?.first
        } ?: return null
        if (!ancestors.all(verifyAncestorDirectory) || !verifyWalkDirectory(rootFd, true)) {
            rootFd.close()
            return null
        }
        val identity = fdOps.fstatIdentity(rootFd)
        if (identity == null) {
            rootFd.close()
            return null
        }
        return Walk(rootFd, listOf(identity), ancestors)
    }

    fun descend(root: Walk, segments: List<String>): Walk? {
        var current = root
        for (segment in segments) {
            val nextFd = openTracked { fdOps.openChildDirectoryAtNoFollow(current.fd, segment) }
            if (nextFd == null) {
                current.close()
                return null
            }
            current.fd.close()
            if (!verifyWalkDirectory(nextFd, false)) {
                nextFd.close()
                return null
            }
            val identity = fdOps.fstatIdentity(nextFd)
            if (identity == null) {
                nextFd.close()
                return null
            }
            current = Walk(nextFd, current.chain + identity, current.ancestorsAboveRoot)
        }
        return current
    }

    fun walk(rootPosixPath: String, segments: List<String>): Walk? {
        val root = openRoot(rootPosixPath) ?: return null
        return descend(root, segments)
    }
}
