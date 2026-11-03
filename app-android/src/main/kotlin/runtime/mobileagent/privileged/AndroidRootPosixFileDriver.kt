// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Path
import runtime.mobileagent.domain.DriverMutationStatus
import runtime.mobileagent.domain.DriverWriteStatus
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootDriverPatchOutcome
import runtime.mobileagent.domain.RootFdConfinementPolicy
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootFdRangeRead
import runtime.mobileagent.domain.RootOpenedFileTarget
import runtime.mobileagent.domain.RootOpenedParentTarget
import runtime.mobileagent.domain.RootPosixCreateCapableDriver
import runtime.mobileagent.domain.RootPosixFileDriver
import runtime.mobileagent.domain.RootTrustedMountRoots
import runtime.mobileagent.domain.RootDirectoryScan

/**
 * FD-based [RootPosixFileDriver] backing [runtime.mobileagent.domain.RootWorkspaceFileExecutor].
 *
 * Writes are only accepted inside a proven UID0-exclusive directory chain; the staged temporary
 * FD is bound to the committed dev/inode and full-content digest. Generic `rename(2)` has no
 * target-inode CAS, so non-exclusive ranges are refused before any side effect instead of being
 * reported as success after a repeated `stat`.
 */
class AndroidRootPosixFileDriver(
    private val workspaceRootPath: Path,
    private val fdOps: PosixFdOperations = AndroidOsPosixFdOperations(),
) : RootPosixCreateCapableDriver, AutoCloseable {

    constructor(
        workspaceRootPath: Path,
        defaultMountId: Long,
        selinuxLabelReader: (Path) -> String?,
        selinuxLabelWriter: (Path, String) -> Boolean = { _, _ -> false },
    ) : this(
        workspaceRootPath = workspaceRootPath,
        fdOps = AndroidOsPosixFdOperations(
            selinuxLabelReader = selinuxLabelReader,
            selinuxLabelWriter = selinuxLabelWriter,
        ),
    )

    private val lifecycle = RootPosixLifecycle()
    private val fileIo = RootPosixFileIo(
        fdOps = fdOps,
        requireActive = lifecycle::requireActive,
        openTracked = lifecycle::openTracked,
        mutateWhileActive = lifecycle::mutateWhileActive,
    )
    @Volatile
    private var directoryVerifier: ((RootFdNodeIdentity, String, Boolean) -> Boolean)? = null
    @Volatile
    private var ancestorVerifier: ((RootFdNodeIdentity) -> Boolean)? = null

    private val inspection = RootPosixInspection(
        fdOps = fdOps,
        lifecycle = lifecycle,
        fileIo = fileIo,
        workspaceRootPath = workspaceRootPath,
        directoryVerifier = { identity, path, isRoot ->
            directoryVerifier?.invoke(identity, path, isRoot) ?: true
        },
        ancestorVerifier = { identity ->
            ancestorVerifier?.invoke(identity) ?: true
        },
    )
    private val reads = RootPosixReads(fdOps, lifecycle, fileIo, inspection)
    private val mutations = RootPosixMutations(fdOps, lifecycle, fileIo, inspection)

    @Synchronized
    override fun configureConfinement(
        workspaceRoot: RootFdNodeIdentity,
        controlPlane: RootControlPlaneBoundary,
        confirmedMountIds: Set<Long>,
        trustedMountRoots: RootTrustedMountRoots?,
    ) {
        check(!lifecycle.closed && directoryVerifier == null && lifecycle.openFdCount() == 0) {
            "driver_already_bound_or_used"
        }
        ancestorVerifier = { identity -> !controlPlane.isNodeProtected(identity) }
        directoryVerifier = { identity, path, isRoot ->
            (!isRoot || identity.sameKernelObjectAs(workspaceRoot)) &&
                RootFdConfinementPolicy.verifyDirectoryStep(
                    workspaceRoot = workspaceRoot,
                    stepIdentity = identity,
                    candidateAbsolutePath = path,
                    controlPlane = controlPlane,
                    trustedMountRoots = trustedMountRoots,
                ) == null
        }
    }

    override fun inspectOpenedTarget(
        workspaceId: String,
        relativePath: String,
        followFinalSymlink: Boolean,
    ): RootOpenedFileTarget? = inspection.inspectTarget(
        workspaceId = workspaceId,
        relativePath = relativePath,
        followFinalSymlink = followFinalSymlink,
        includeContentDigest = true,
    )

    override fun inspectOpenedTarget(
        workspaceId: String,
        relativePath: String,
        followFinalSymlink: Boolean,
        includeContentDigest: Boolean,
    ): RootOpenedFileTarget? = inspection.inspectTarget(
        workspaceId = workspaceId,
        relativePath = relativePath,
        followFinalSymlink = followFinalSymlink,
        includeContentDigest = includeContentDigest,
    )

    override fun isOperational(): Boolean = !lifecycle.closed

    override fun inspectParentForCreate(
        workspaceId: String,
        relativePath: String,
    ): RootOpenedParentTarget? = inspection.inspectParent(workspaceId, relativePath, requireAbsent = true)

    override fun inspectParentForMutation(
        workspaceId: String,
        relativePath: String,
    ): RootOpenedParentTarget? = inspection.inspectParent(workspaceId, relativePath, requireAbsent = false)

    override fun releaseOpenedTarget(retainedFdHandle: String) = inspection.release(retainedFdHandle)

    override fun readOpenedFd(
        target: RootOpenedFileTarget,
        offsetBytes: Long,
        maxBytes: Int,
    ): RootFdRangeRead? = reads.read(target, offsetBytes, maxBytes)

    override fun listDirectory(
        workspaceId: String,
        relativePath: String,
        maxEntries: Int,
        cursor: String?,
    ): RootDirectoryScan? = reads.list(relativePath, maxEntries, cursor)

    override fun applyMetadataOnOpenedFd(
        target: RootOpenedFileTarget,
        modeOctal: Int?,
        ownerUid: Int?,
        ownerGid: Int?,
        selinuxLabel: String?,
    ): DriverMutationStatus = mutations.applyMetadata(target, modeOctal, ownerUid, ownerGid, selinuxLabel)

    override fun writeAtomicSiblingAndReplace(
        target: RootOpenedFileTarget,
        utf8Bytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
    ): DriverWriteStatus = mutations.writeAtomic(
        target,
        utf8Bytes,
        preserveModeOctal,
        preserveOwnerUid,
        preserveOwnerGid,
        preserveSelinuxLabel,
    )

    override fun applyPatchAtomicSiblingAndReplace(
        target: RootOpenedFileTarget,
        format: String,
        patchBytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
    ): RootDriverPatchOutcome = mutations.applyPatchAtomic(
        target = target,
        format = format,
        patchBytes = patchBytes,
        preserveModeOctal = preserveModeOctal,
        preserveOwnerUid = preserveOwnerUid,
        preserveOwnerGid = preserveOwnerGid,
        preserveSelinuxLabel = preserveSelinuxLabel,
    )

    override fun createNewRegularFile(
        parent: RootOpenedParentTarget,
        relativePath: String,
        utf8Bytes: ByteArray,
    ): DriverWriteStatus = mutations.createNewFile(parent, relativePath, utf8Bytes)

    override fun createDirectoryAt(parent: RootOpenedParentTarget, leafName: String): DriverMutationStatus =
        mutations.createDirectory(parent, leafName)

    override fun deleteOpenedTarget(target: RootOpenedFileTarget): DriverMutationStatus = mutations.delete(target)

    override fun moveOpenedTarget(
        source: RootOpenedFileTarget,
        destinationParent: RootOpenedParentTarget,
        destinationLeafName: String,
        replaceExisting: Boolean,
    ): DriverMutationStatus = mutations.move(source, destinationParent, destinationLeafName, replaceExisting)

    internal fun openFdCount(): Int = lifecycle.openFdCount()

    internal fun isClosed(): Boolean = lifecycle.closed

    override fun close() = lifecycle.closeAll { inspection.closeRetained() }

    companion object {
        internal fun toPosixAbsolutePath(path: Path): String = RootPosixInspection.toPosixAbsolutePath(path)
    }
}
