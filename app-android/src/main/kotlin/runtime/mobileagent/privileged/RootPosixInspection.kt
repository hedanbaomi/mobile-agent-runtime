// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Path
import java.util.UUID
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootOpenedFileTarget
import runtime.mobileagent.domain.RootOpenedParentTarget
import runtime.mobileagent.domain.RootWriteDomainPolicy

internal class RootPosixInspection(
    private val fdOps: PosixFdOperations,
    private val lifecycle: RootPosixLifecycle,
    private val fileIo: RootPosixFileIo,
    private val workspaceRootPath: Path,
    private val directoryVerifier: (RootFdNodeIdentity, String, Boolean) -> Boolean,
    private val ancestorVerifier: (RootFdNodeIdentity) -> Boolean = { true },
) {
    private val retained = RootPosixRetainedRegistry()
    private val rootPosixPath = toPosixAbsolutePath(workspaceRootPath)
    private val walker = RootPosixTargetWalker(
        fdOps = fdOps,
        openTracked = lifecycle::openTracked,
        verifyWalkDirectory = ::verifyWalkDirectory,
        verifyAncestorDirectory = ::verifyAncestor,
    )

    fun inspectTarget(
        workspaceId: String,
        relativePath: String,
        followFinalSymlink: Boolean,
        includeContentDigest: Boolean = true,
    ): RootOpenedFileTarget? {
        val segments = parseRelativeSegments(relativePath, allowEmpty = true) ?: return null
        if (segments.isEmpty()) return inspectWorkspaceRootTarget()
        val walk = walker.walk(rootPosixPath, segments.dropLast(1)) ?: return null
        val leafName = segments.last()
        val parentBefore = fdOps.fstatIdentity(walk.fd)?.takeIf { it.isDirectory && !it.isSymlink }
        val targetFd = parentBefore?.let {
            runCatching {
                lifecycle.openTracked {
                    fdOps.openTargetAt(walk.fd, leafName, followFinalSymlink, writable = false)
                }
            }.getOrNull()
        }
        if (targetFd == null) {
            walk.close()
            return null
        }
        val observed = fileIo.observeOpenFdPair(
            walk.fd,
            targetFd,
            parentBefore,
            walk.chain,
            walk.ancestorsAboveRoot,
            includeContentDigest = includeContentDigest,
        )
        if (observed == null) {
            targetFd.close()
            walk.close()
            return null
        }
        val handle = storeTarget(RetainedTargetEntry(walk.fd, targetFd, leafName))
            ?: return null
        return observed.copy(retainedFdHandle = handle)
    }

    fun inspectParent(
        workspaceId: String,
        relativePath: String,
        requireAbsent: Boolean,
    ): RootOpenedParentTarget? {
        val segments = parseRelativeSegments(relativePath, allowEmpty = false) ?: return null
        val walk = walker.walk(rootPosixPath, segments.dropLast(1)) ?: return null
        val leafName = segments.last()
        val parentBefore = fdOps.fstatIdentity(walk.fd)
        val parentCurrent = fdOps.fstatIdentity(walk.fd)
        val parentPath = fdOps.readFdResolvedPath(walk.fd)
        val childAbsent = fdOps.statAtNoFollow(walk.fd, leafName) == null
        if (parentBefore == null || parentCurrent == null || parentPath == null || (requireAbsent && !childAbsent)) {
            walk.close()
            return null
        }
        val targetPath = "${parentPath.trimEnd('/')}/$leafName"
        val handle = storeParent(RetainedParentEntry(walk.fd, leafName))
            ?: return null
        return RootOpenedParentTarget(
            parentIdentityBefore = parentBefore,
            parentIdentityCurrent = parentCurrent,
            resolvedParentAbsolutePath = parentPath,
            resolvedTargetAbsolutePath = targetPath,
            retainedFdHandle = handle,
            directoryChain = walk.chain,
            writeDomain = RootWriteDomainPolicy.assess(walk.fullChain, resolvedTargetParentPath = parentPath,
                workspaceChainStart = walk.ancestorsAboveRoot.size).let { evidence ->
                if (evidence.isUid0Exclusive && fdOps.usesEmulatedStoragePermissions(walk.fd)) evidence.copy(kind = runtime.mobileagent.domain.RootWriteDomainKind.SHARED_DIRECTORY)
                else evidence
            },
            ancestorsAboveRoot = walk.ancestorsAboveRoot,
        )
    }

    fun openDirectoryWalk(relativePath: String): RootPosixTargetWalker.Walk? {
        val segments = parseRelativeSegments(relativePath, allowEmpty = true) ?: return null
        return walker.walk(rootPosixPath, segments)
    }

    fun release(handle: String) {
        if (handle.isNotBlank()) retained.release(handle)
    }

    fun consumeTarget(handle: String): RetainedTargetEntry? = retained.consumeTarget(handle)

    fun consumeParent(handle: String): RetainedParentEntry? = retained.consumeParent(handle)

    fun closeRetained() = retained.closeAll()

    fun targetCount(): Int = retained.targetCount()

    fun verifyEntry(
        entry: RetainedTargetEntry,
        expected: RootOpenedFileTarget,
        requireContentDigestRecheck: Boolean = true,
    ): Boolean =
        lifecycle.isActive() &&
            fileIo.verifyRetainedEntryMatches(
                entry.parentFd,
                entry.targetFd,
                entry.leafName,
                expected,
                requireContentDigestRecheck,
            )

    fun verifyParentEntry(entry: RetainedParentEntry, expected: RootOpenedParentTarget): Boolean {
        if (!lifecycle.isActive()) return false
        val parentNow = fdOps.fstatIdentity(entry.parentFd) ?: return false
        return parentNow.sameKernelObjectAs(expected.parentIdentityCurrent) &&
            verifyWalkDirectory(entry.parentFd, isRoot = false) &&
            fdOps.statAtNoFollow(entry.parentFd, entry.leafName) == null
    }

    fun verifyParentEntryForMutation(entry: RetainedParentEntry, expected: RootOpenedParentTarget): Boolean {
        if (!lifecycle.isActive()) return false
        val parentNow = fdOps.fstatIdentity(entry.parentFd) ?: return false
        return parentNow.sameKernelObjectAs(expected.parentIdentityCurrent) &&
            verifyWalkDirectory(entry.parentFd, isRoot = false)
    }

    private fun inspectWorkspaceRootTarget(): RootOpenedFileTarget? {
        val dupParent = lifecycle.openTracked { fdOps.openDirectoryAbsoluteNoFollow(rootPosixPath) }
        if (dupParent == null) return null
        val rootWalk = walker.openRoot(rootPosixPath)
        if (rootWalk == null) {
            dupParent.close()
            return null
        }
        val observed = fileIo.observeOpenFdPair(
            dupParent,
            rootWalk.fd,
            rootWalk.chain.first(),
            rootWalk.chain,
            rootWalk.ancestorsAboveRoot,
        )
        if (observed == null) {
            dupParent.close()
            rootWalk.close()
            return null
        }
        val handle = storeTarget(RetainedTargetEntry(dupParent, rootWalk.fd, "."))
            ?: return null
        return observed.copy(retainedFdHandle = handle)
    }

    private fun storeTarget(entry: RetainedTargetEntry): String? =
        store(entry) { handle -> retained.storeTarget(entry, handle) }

    private fun storeParent(entry: RetainedParentEntry): String? =
        store(entry) { handle -> retained.storeParent(entry, handle) }

    private fun store(entry: AutoCloseable, store: (String) -> Unit): String? = lifecycle.withGate {
        if (!lifecycle.isActive()) {
            entry.close()
            return@withGate null
        }
        val handle = UUID.randomUUID().toString()
        store(handle)
        handle
    }

    private fun verifyWalkDirectory(fd: PosixRetainedFd, isRoot: Boolean): Boolean = runCatching {
        if (!lifecycle.isActive()) return false
        val identity = fdOps.fstatIdentity(fd) ?: return false
        val path = fdOps.readFdResolvedPath(fd) ?: return false
        identity.hasValidKernelIdentity && identity.isDirectory && !identity.isSymlink &&
            directoryVerifier(identity, path, isRoot)
    }.getOrDefault(false)

    private fun verifyAncestor(identity: RootFdNodeIdentity): Boolean =
        lifecycle.isActive() && identity.hasValidKernelIdentity &&
            identity.isDirectory && !identity.isSymlink && ancestorVerifier(identity)

    companion object {
        fun toPosixAbsolutePath(path: Path): String {
            val normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/')
            val withoutDrive = if (normalized.length >= 2 && normalized[1] == ':') {
                normalized.substring(2)
            } else {
                normalized
            }
            return if (withoutDrive.startsWith("/")) withoutDrive else "/$withoutDrive"
        }

        fun parseRelativeSegments(relativePath: String, allowEmpty: Boolean): List<String>? {
            // Authorization names are literal: whitespace is a valid filename component.
            if (relativePath.startsWith("/")) return null
            if (relativePath.isEmpty() || relativePath == ".") {
                return if (allowEmpty) emptyList() else null
            }
            val segments = relativePath.split('/')
            return segments.takeIf { it.all(PosixDirectoryNames::isValidEntryName) }
        }
    }
}
