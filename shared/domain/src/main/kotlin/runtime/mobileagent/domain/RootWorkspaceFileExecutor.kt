// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RootFileEntryKind {
    REGULAR_FILE,
    DIRECTORY,
}

@Serializable
data class RootFileMetadataSnapshot(
    val workspaceId: String,
    val relativePath: String,
    val entryKind: RootFileEntryKind,
    val sizeBytes: Long,
    val modeOctal: Int,
    val ownerUid: Int,
    val ownerGid: Int,
    val selinuxLabel: String? = null,
    val hardlinkCount: Long,
    val deviceId: Long,
    val inode: Long,
    val mountId: Long,
    val version: String,
)

sealed interface RootFileOperationResult<out T> {
    data class Succeeded<T>(
        val value: T,
        val resultDigestSha256: String,
    ) : RootFileOperationResult<T>

    data class Rejected(
        val rejection: RootConfinementRejection,
        val reason: String,
    ) : RootFileOperationResult<Nothing>

    data class PostMutationFailure(
        val reason: String,
        val rejection: RootConfinementRejection? = null,
        val terminalOutcome: RootServiceTerminalOutcome = RootServiceTerminalOutcome.UNKNOWN_OUTCOME,
        val requiresDegradedEpoch: Boolean = true,
    ) : RootFileOperationResult<Nothing>

    data class UnsupportedAttributePreservation(
        val attribute: String,
    ) : RootFileOperationResult<Nothing>

    data class PermissionDenied(
        val selinuxConfirmed: Boolean,
        val errnoName: String,
    ) : RootFileOperationResult<Nothing>
}

data class RootOpenedFileTarget @JvmOverloads constructor(
    val parentIdentityBefore: RootFdNodeIdentity,
    val targetIdentityBefore: RootFdNodeIdentity,
    val parentIdentityCurrent: RootFdNodeIdentity,
    val targetIdentityCurrent: RootFdNodeIdentity,
    val resolvedAbsolutePath: String,
    val sizeBytes: Long,
    val version: String,
    val retainedFdHandle: String = "",
    /** Verified directories from the pinned workspace root (inclusive) to the target parent (inclusive). */
    val directoryChain: List<RootFdNodeIdentity> = emptyList(),
    /** Proven write-domain evidence for the target parent (`UID0_EXCLUSIVE` or `SINGLE_APP_PRIVATE`). */
    val writeDomain: RootWriteDomainEvidence = RootWriteDomainEvidence.unproven(
        RootWriteDomainEvidence.REASON_NOT_ASSESSED,
    ),
    /** Full-content SHA-256 observed under the target FD when the driver can provide it. */
    val contentSha256: String? = null,
    /** Ancestor directories above the pinned workspace root collected during absolute root walk. */
    val ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
)

data class RootOpenedParentTarget @JvmOverloads constructor(
    val parentIdentityBefore: RootFdNodeIdentity,
    val parentIdentityCurrent: RootFdNodeIdentity,
    val resolvedParentAbsolutePath: String,
    val resolvedTargetAbsolutePath: String,
    val retainedFdHandle: String = "",
    val directoryChain: List<RootFdNodeIdentity> = emptyList(),
    val writeDomain: RootWriteDomainEvidence = RootWriteDomainEvidence.unproven(
        RootWriteDomainEvidence.REASON_NOT_ASSESSED,
    ),
    val ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
)

interface RootPosixFileDriver {
    fun inspectOpenedTarget(
        workspaceId: String,
        relativePath: String,
        followFinalSymlink: Boolean = false,
    ): RootOpenedFileTarget?

    fun inspectOpenedTarget(
        workspaceId: String,
        relativePath: String,
        followFinalSymlink: Boolean,
        includeContentDigest: Boolean,
    ): RootOpenedFileTarget? = inspectOpenedTarget(workspaceId, relativePath, followFinalSymlink)

    fun isOperational(): Boolean = true

    /**
     * Install the executor's trusted binding before any FD walk. Test drivers may have no OS walk.
     * Numeric [confirmedMountIds] never authorize cross-mount steps; only frozen
     * [RootTrustedMountRoots] identities do.
     */
    fun configureConfinement(
        workspaceRoot: RootFdNodeIdentity,
        controlPlane: RootControlPlaneBoundary,
        confirmedMountIds: Set<Long>,
        trustedMountRoots: RootTrustedMountRoots? = null,
    ) = Unit

    /** Releases a retained handle returned by [inspectOpenedTarget]/[inspectParentForCreate]. */
    fun releaseOpenedTarget(retainedFdHandle: String) = Unit

    /** Reads a bounded window under the retained target FD and returns its full-content version. */
    fun readOpenedFd(
        target: RootOpenedFileTarget,
        offsetBytes: Long,
        maxBytes: Int,
    ): RootFdRangeRead? = null

    /** Lists one directory under the retained walk; returns null when unavailable. */
    fun listDirectory(
        workspaceId: String,
        relativePath: String,
        maxEntries: Int,
        cursor: String?,
    ): RootDirectoryScan? = null

    fun inspectParentForCreate(
        workspaceId: String,
        relativePath: String,
    ): RootOpenedParentTarget? = null

    /** Parent inspection for move/patch where the destination may already exist. */
    fun inspectParentForMutation(
        workspaceId: String,
        relativePath: String,
    ): RootOpenedParentTarget? = inspectParentForCreate(workspaceId, relativePath)

    fun applyMetadataOnOpenedFd(
        target: RootOpenedFileTarget,
        modeOctal: Int?,
        ownerUid: Int?,
        ownerGid: Int?,
        selinuxLabel: String?,
    ): DriverMutationStatus

    fun writeAtomicSiblingAndReplace(
        target: RootOpenedFileTarget,
        utf8Bytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
    ): DriverWriteStatus

    fun createDirectoryAt(parent: RootOpenedParentTarget, leafName: String): DriverMutationStatus =
        DriverMutationStatus.Unsupported("mkdir_unsupported")

    fun deleteOpenedTarget(target: RootOpenedFileTarget): DriverMutationStatus =
        DriverMutationStatus.Unsupported("delete_unsupported")

    fun moveOpenedTarget(
        source: RootOpenedFileTarget,
        destinationParent: RootOpenedParentTarget,
        destinationLeafName: String,
        replaceExisting: Boolean,
    ): DriverMutationStatus = DriverMutationStatus.Unsupported("move_unsupported")

    fun applyPatchAtomicSiblingAndReplace(
        target: RootOpenedFileTarget,
        format: String,
        patchBytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
    ): RootDriverPatchOutcome? = null
}

sealed interface RootDriverPatchOutcome {
    data class Applied(
        val writeStatus: DriverWriteStatus,
        val patchedByteLength: Long,
        val patchedContentSha256: String,
    ) : RootDriverPatchOutcome

    data class PatchRejected(val reason: String) : RootDriverPatchOutcome
}

interface RootPosixCreateCapableDriver : RootPosixFileDriver {
    fun writeAtomicSiblingAndReplace(
        target: RootOpenedFileTarget,
        utf8Bytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
        replaceExisting: Boolean,
    ): DriverWriteStatus {
        if (!replaceExisting) return DriverWriteStatus.RaceDetectedTempCleanedUp
        return writeAtomicSiblingAndReplace(
            target = target,
            utf8Bytes = utf8Bytes,
            preserveModeOctal = preserveModeOctal,
            preserveOwnerUid = preserveOwnerUid,
            preserveOwnerGid = preserveOwnerGid,
            preserveSelinuxLabel = preserveSelinuxLabel,
        )
    }

    fun createNewRegularFile(
        parent: RootOpenedParentTarget,
        relativePath: String,
        utf8Bytes: ByteArray,
    ): DriverWriteStatus = DriverWriteStatus.Denied("ENOENT")
}

sealed interface DriverMutationStatus {
    data class Applied(val updatedTarget: RootOpenedFileTarget) : DriverMutationStatus
    data class Deleted(val deletedTarget: RootOpenedFileTarget) : DriverMutationStatus
    data class Moved(val updatedTarget: RootOpenedFileTarget) : DriverMutationStatus
    data class PostMutationObservationOrPartialFailure(val reason: String) : DriverMutationStatus
    data class Unsupported(val attribute: String) : DriverMutationStatus
    data class UnsupportedMutationDomain(val reasons: String) : DriverMutationStatus
    data class Denied(val errnoName: String, val selinuxConfirmed: Boolean = false) : DriverMutationStatus
    data object RaceOrDisappeared : DriverMutationStatus
}

sealed interface DriverWriteStatus {
    data class Replaced(val updatedTarget: RootOpenedFileTarget) : DriverWriteStatus
    data class PostCommitFailure(val reason: String) : DriverWriteStatus
    data class AttributePreservationUnsupported(val attribute: String, val tempFileCleanedUp: Boolean) : DriverWriteStatus
    data class UnsupportedMutationDomain(val reasons: String) : DriverWriteStatus
    data class Denied(val errnoName: String, val selinuxConfirmed: Boolean = false) : DriverWriteStatus
    data object RaceDetectedTempCleanedUp : DriverWriteStatus
}

/**
 * Controlled-service typed file & metadata executor (`file_get_metadata`, `file_set_metadata`,
 * `file_write_text`, `file_list`, `file_read_text`, `file_create_directory`, `file_delete`,
 * `file_move`, `file_apply_patch`), enforcing [RootOperationBoundary] on opened FD identities.
 *
 * Mutations require a proven UID0-exclusive parent chain ([RootWriteDomainEvidence]); when the
 * domain cannot be proven they fail deterministically with a `Rejected` result *before* any
 * side effect instead of relying on a non-existent target-inode CAS in `rename(2)`.
 */
class RootWorkspaceFileExecutor @JvmOverloads constructor(
    private val workspaceRootIdentity: RootFdNodeIdentity,
    private val controlPlane: RootControlPlaneBoundary,
    private val driver: RootPosixFileDriver,
    confirmedMountIds: Set<Long> = emptySet(),
    private val trustedMountRoots: RootTrustedMountRoots? = null,
) {
    private val workspaceRootRejection: RootConfinementRejection? =
        RootOperationBoundary.verifyWorkspaceRootIdentity(workspaceRootIdentity, controlPlane)

    private val readOperations = RootWorkspaceFileReadOperations(
        workspaceRootIdentity = workspaceRootIdentity,
        controlPlane = controlPlane,
        driver = driver,
        trustedMountRoots = trustedMountRoots,
        workspaceRootRejection = workspaceRootRejection,
    )

    private val searchOperations = RootWorkspaceSearchOperations(
        readOperations = readOperations,
        workspaceRootRejection = workspaceRootRejection,
        isActive = driver::isOperational,
    )

    private val mutationOperations = RootWorkspaceFileMutationOperations(
        workspaceRootIdentity = workspaceRootIdentity,
        controlPlane = controlPlane,
        driver = driver,
        trustedMountRoots = trustedMountRoots,
        workspaceRootRejection = workspaceRootRejection,
        readOperations = readOperations,
        writeAtomic = ::executeAtomicWriteText,
        settlePatchedWrite = ::settlePatchedWriteStatus,
    )

    init {
        driver.configureConfinement(
            workspaceRootIdentity,
            controlPlane,
            confirmedMountIds.toSet(),
            trustedMountRoots,
        )
    }

    fun executeStat(
        args: RootTypedArguments.FileStat,
    ): RootFileOperationResult<RootFileMetadataSnapshot> =
        inspectMetadata(args.workspaceId, args.relativePath, requireContentVersion = false)

    fun executeGetMetadata(
        args: RootTypedArguments.FileGetMetadata,
    ): RootFileOperationResult<RootFileMetadataSnapshot> =
        inspectMetadata(args.workspaceId, args.relativePath, requireContentVersion = true)

    private fun inspectMetadata(
        workspaceId: String,
        relativePath: String,
        requireContentVersion: Boolean,
    ): RootFileOperationResult<RootFileMetadataSnapshot> {
        if (workspaceRootRejection != null) return rootRejected()
        val opened = driver.inspectOpenedTarget(
            workspaceId = workspaceId,
            relativePath = relativePath,
            followFinalSymlink = false,
            includeContentDigest = requireContentVersion,
        ) ?: return targetNotFound()
        try {
            val rejection = verifyTargetConfinement(opened, expectedVersion = null, requireRegularFile = false)
            if (rejection != null) return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
            if (requireContentVersion &&
                opened.targetIdentityCurrent.isRegularFile &&
                opened.sizeBytes > RootContentVersionPolicy.MAX_CONTENT_BYTES
            ) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.CAPACITY_EXCEEDED,
                    RootFileOperationReason.CONTENT_VERSION_UNOBSERVABLE,
                )
            }
            val snapshot = RootMetadataSnapshots.of(workspaceId, relativePath, opened)
            return RootFileOperationResult.Succeeded(snapshot, RootMetadataSnapshots.digest(snapshot))
        } finally {
            driver.releaseOpenedTarget(opened.retainedFdHandle)
        }
    }

    fun executeSetMetadata(
        args: RootTypedArguments.FileSetMetadata,
        selinuxLabel: String? = null,
    ): RootFileOperationResult<RootFileMetadataSnapshot> {
        if (workspaceRootRejection != null) return rootRejected()
        val opened = driver.inspectOpenedTarget(
            workspaceId = args.workspaceId,
            relativePath = args.relativePath,
            followFinalSymlink = false,
        ) ?: return targetNotFound()
        try {
            if (opened.targetIdentityCurrent.isRegularFile &&
                opened.sizeBytes > RootContentVersionPolicy.MAX_CONTENT_BYTES
            ) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.CAPACITY_EXCEEDED,
                    RootFileOperationReason.CONTENT_VERSION_UNOBSERVABLE,
                )
            }
            val rejection = verifyTargetConfinement(
                opened = opened,
                expectedVersion = args.expectedVersion,
                requireRegularFile = false,
            )
            if (rejection != null) return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
            writeDomainRejection(opened.writeDomain)?.let { return it }
            val status = runCatching {
                driver.applyMetadataOnOpenedFd(
                    target = opened,
                    modeOctal = args.mode,
                    ownerUid = args.uid,
                    ownerGid = args.gid,
                    selinuxLabel = selinuxLabel,
                )
            }.getOrElse {
                DriverMutationStatus.PostMutationObservationOrPartialFailure("driver_uncaught_exception_after_dispatch")
            }
            return when (status) {
                is DriverMutationStatus.Applied -> verifyAppliedMetadataResult(
                    args = args,
                    requestedLabel = selinuxLabel,
                    opened = opened,
                    updatedTarget = status.updatedTarget,
                )
                is DriverMutationStatus.PostMutationObservationOrPartialFailure ->
                    RootFileOperationResult.PostMutationFailure(status.reason)
                is DriverMutationStatus.Unsupported ->
                    RootFileOperationResult.UnsupportedAttributePreservation(status.attribute)
                is DriverMutationStatus.UnsupportedMutationDomain -> RootFileOperationResult.Rejected(
                    RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
                    "${RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN}:${status.reasons}",
                )
                is DriverMutationStatus.Denied ->
                    RootFileOperationResult.PermissionDenied(status.selinuxConfirmed, status.errnoName)
                DriverMutationStatus.RaceOrDisappeared ->
                    RootFileOperationResult.Rejected(RootConfinementRejection.RENAME_RACE_DETECTED, "fd_mutation_race")
                is DriverMutationStatus.Deleted, is DriverMutationStatus.Moved ->
                    RootFileOperationResult.PostMutationFailure("unexpected_metadata_status")
            }
        } finally {
            driver.releaseOpenedTarget(opened.retainedFdHandle)
        }
    }

    fun executeAtomicWriteText(
        args: RootTypedArguments.FileWriteText,
        utf8Bytes: ByteArray,
    ): RootFileOperationResult<RootFileMetadataSnapshot> {
        if (workspaceRootRejection != null) return rootRejected()
        if (utf8Bytes.size != args.contentByteLength ||
            RootMetadataSnapshots.sha256Hex(utf8Bytes) != args.contentSha256
        ) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.LOCATOR_INVALID,
                "content_digest_or_length_mismatch",
            )
        }
        if (!args.replaceExisting && args.expectedVersion != null) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.LOCATOR_INVALID,
                "expected_version_forbidden_when_replace_disabled",
            )
        }
        val opened = driver.inspectOpenedTarget(
            workspaceId = args.workspaceId,
            relativePath = args.relativePath,
            followFinalSymlink = false,
        )
        if (opened == null) return executeCreateNewFile(args, utf8Bytes)
        try {
            if (!args.replaceExisting) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.RENAME_RACE_DETECTED,
                    "target_already_exists_replace_disabled",
                )
            }
            val rejection = verifyTargetConfinement(
                opened = opened,
                expectedVersion = args.expectedVersion,
                requireRegularFile = true,
            )
            if (rejection != null) return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
            writeDomainRejection(opened.writeDomain)?.let { return it }
            // A directory the target app itself can rewrite has no conditional-replace primitive:
            // refuse the conditional request before any staging instead of claiming a version
            // check the commit cannot enforce (see ROOT write-domain capability note).
            if (args.expectedVersion != null && !opened.writeDomain.isUid0Exclusive) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.WRITE_DOMAIN_VERSION_UNSUPPORTED,
                    RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED,
                )
            }
            val cur = opened.targetIdentityCurrent
            val status = runCatching {
                driver.writeAtomicSiblingAndReplace(
                    target = opened,
                    utf8Bytes = utf8Bytes,
                    preserveModeOctal = cur.modeOctal,
                    preserveOwnerUid = cur.ownerUid,
                    preserveOwnerGid = cur.ownerGid,
                    preserveSelinuxLabel = cur.selinuxLabel,
                )
            }.getOrElse {
                DriverWriteStatus.PostCommitFailure("driver_uncaught_exception_after_dispatch")
            }
            return settleWriteStatus(
                workspaceId = args.workspaceId,
                relativePath = args.relativePath,
                expectedParent = opened.parentIdentityCurrent,
                expectedResolvedPath = opened.resolvedAbsolutePath,
                expectedAttributeSource = cur,
                previousVersion = opened.version,
                utf8ByteLength = utf8Bytes.size.toLong(),
                expectedContentSha256 = RootMetadataSnapshots.sha256Hex(utf8Bytes),
                status = status,
                requireModeMatch = true,
            )
        } finally {
            driver.releaseOpenedTarget(opened.retainedFdHandle)
        }
    }

    fun executeList(args: RootTypedArguments.FileList): RootFileOperationResult<RootDirectoryListing> =
        readOperations.list(args)

    fun executeReadText(args: RootTypedArguments.FileReadText): RootFileOperationResult<RootFileReadResult> =
        readOperations.read(args)

    fun executeCreateDirectory(
        args: RootTypedArguments.FileCreateDirectory,
    ): RootFileOperationResult<RootFileMetadataSnapshot> = mutationOperations.createDirectory(args)

    fun executeDelete(args: RootTypedArguments.FileDelete): RootFileOperationResult<RootDeletedEntry> =
        mutationOperations.delete(args)

    fun executeMove(args: RootTypedArguments.FileMove): RootFileOperationResult<RootMovedEntry> =
        mutationOperations.move(args)

    fun executeApplyPatch(
        args: RootTypedArguments.FileApplyPatch,
        patchBytes: ByteArray,
    ): RootFileOperationResult<RootFileMetadataSnapshot> = mutationOperations.applyPatch(args, patchBytes)

    fun executeGlob(args: RootTypedArguments.FileGlob): RootFileOperationResult<RootGlobSearchResult> =
        searchOperations.glob(args)

    fun executeGrep(args: RootTypedArguments.FileGrep): RootFileOperationResult<RootGrepSearchResult> =
        searchOperations.grep(args)

    private fun settlePatchedWriteStatus(
        workspaceId: String,
        relativePath: String,
        opened: RootOpenedFileTarget,
        patchedByteLength: Long,
        patchedContentSha256: String,
        status: DriverWriteStatus,
    ): RootFileOperationResult<RootFileMetadataSnapshot> = settleWriteStatus(
        workspaceId = workspaceId,
        relativePath = relativePath,
        expectedParent = opened.parentIdentityCurrent,
        expectedResolvedPath = opened.resolvedAbsolutePath,
        expectedAttributeSource = opened.targetIdentityCurrent,
        previousVersion = opened.version,
        utf8ByteLength = patchedByteLength,
        expectedContentSha256 = patchedContentSha256,
        status = status,
        requireModeMatch = true,
    )

    private fun executeCreateNewFile(
        args: RootTypedArguments.FileWriteText,
        utf8Bytes: ByteArray,
    ): RootFileOperationResult<RootFileMetadataSnapshot> {
        val createDriver = driver as? RootPosixCreateCapableDriver
        if (args.expectedVersion != null || createDriver == null) return targetNotFound()
        val parent = createDriver.inspectParentForCreate(args.workspaceId, args.relativePath)
            ?: return targetNotFound()
        try {
            val parentRejection = verifyParentForCreate(parent)
            if (parentRejection != null) {
                return RootFileOperationResult.Rejected(parentRejection, "confinement_rejected")
            }
            writeDomainRejection(parent.writeDomain)?.let { return it }
            val status = runCatching {
                createDriver.createNewRegularFile(parent, args.relativePath, utf8Bytes)
            }.getOrElse {
                DriverWriteStatus.PostCommitFailure("driver_uncaught_exception_after_dispatch")
            }
            return settleWriteStatus(
                workspaceId = args.workspaceId,
                relativePath = args.relativePath,
                expectedParent = parent.parentIdentityCurrent,
                expectedResolvedPath = parent.resolvedTargetAbsolutePath,
                expectedAttributeSource = parent.parentIdentityCurrent,
                previousVersion = null,
                utf8ByteLength = utf8Bytes.size.toLong(),
                expectedContentSha256 = RootMetadataSnapshots.sha256Hex(utf8Bytes),
                status = status,
                requireModeMatch = false,
            )
        } finally {
            driver.releaseOpenedTarget(parent.retainedFdHandle)
        }
    }

    private fun verifyTargetConfinement(
        opened: RootOpenedFileTarget,
        expectedVersion: String?,
        requireRegularFile: Boolean,
        expectedInode: Long? = null,
    ): RootConfinementRejection? = RootOperationBoundary.verifyOpenedTarget(
        workspaceRoot = workspaceRootIdentity,
        controlPlane = controlPlane,
        trustedMountRoots = trustedMountRoots,
        opened = opened,
        expectedVersion = expectedVersion,
        requireRegularFile = requireRegularFile,
        expectedInode = expectedInode,
    )

    private fun verifyParentForCreate(parent: RootOpenedParentTarget): RootConfinementRejection? =
        RootOperationBoundary.verifyOpenedParent(
            workspaceRootIdentity,
            controlPlane,
            trustedMountRoots,
            parent,
        )

    private fun writeDomainRejection(evidence: RootWriteDomainEvidence): RootFileOperationResult<Nothing>? =
        if (evidence.isProvenForMutation) {
            null
        } else {
            RootFileOperationResult.Rejected(
                RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
                "${RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN}:${evidence.unprovenReasons.joinToString("+")}",
            )
        }

    private fun verifyAppliedMetadataResult(
        args: RootTypedArguments.FileSetMetadata,
        requestedLabel: String?,
        opened: RootOpenedFileTarget,
        updatedTarget: RootOpenedFileTarget,
    ): RootFileOperationResult<RootFileMetadataSnapshot> {
        val postRejection = verifyTargetConfinement(
            updatedTarget,
            expectedVersion = null,
            requireRegularFile = false,
            expectedInode = opened.targetIdentityCurrent.inode,
        )
        if (postRejection != null) {
            return RootFileOperationResult.PostMutationFailure("post_mutation_confinement_failed", postRejection)
        }
        if (!opened.parentIdentityCurrent.sameKernelObjectAs(updatedTarget.parentIdentityCurrent) ||
            updatedTarget.resolvedAbsolutePath != opened.resolvedAbsolutePath
        ) {
            return RootFileOperationResult.PostMutationFailure(
                "post_mutation_target_or_parent_drift",
                RootConfinementRejection.RENAME_RACE_DETECTED,
            )
        }
        if (updatedTarget.sizeBytes != opened.sizeBytes || updatedTarget.contentSha256 != opened.contentSha256) {
            return RootFileOperationResult.PostMutationFailure("metadata_content_drift")
        }
        val after = updatedTarget.targetIdentityCurrent
        val expectedMode = args.mode ?: opened.targetIdentityCurrent.modeOctal
        val expectedUid = args.uid ?: opened.targetIdentityCurrent.ownerUid
        val expectedGid = args.gid ?: opened.targetIdentityCurrent.ownerGid
        val expectedLabel = requestedLabel ?: opened.targetIdentityCurrent.selinuxLabel
        val attributesMatched = after.modeOctal == expectedMode &&
            after.ownerUid == expectedUid &&
            after.ownerGid == expectedGid &&
            after.selinuxLabel == expectedLabel
        val before = opened.targetIdentityCurrent
        val attributesChanged = after.modeOctal != before.modeOctal || after.ownerUid != before.ownerUid ||
            after.ownerGid != before.ownerGid || after.selinuxLabel != before.selinuxLabel
        // Versions describe observed state, not syscall attempts: an idempotent request may keep it.
        val versionValid = updatedTarget.version.isNotBlank() &&
            (!attributesChanged || updatedTarget.version != opened.version)
        if (!attributesMatched || !versionValid) {
            return RootFileOperationResult.PostMutationFailure("metadata_postcondition_mismatch")
        }
        val snapshot = RootMetadataSnapshots.of(args.workspaceId, args.relativePath, updatedTarget)
        return RootFileOperationResult.Succeeded(snapshot, RootMetadataSnapshots.digest(snapshot))
    }

    private fun settleWriteStatus(
        workspaceId: String,
        relativePath: String,
        expectedParent: RootFdNodeIdentity,
        expectedResolvedPath: String,
        expectedAttributeSource: RootFdNodeIdentity,
        previousVersion: String?,
        utf8ByteLength: Long,
        expectedContentSha256: String?,
        status: DriverWriteStatus,
        requireModeMatch: Boolean,
    ): RootFileOperationResult<RootFileMetadataSnapshot> = when (status) {
        is DriverWriteStatus.Replaced -> {
            val postRejection = verifyTargetConfinement(
                opened = status.updatedTarget,
                expectedVersion = null,
                requireRegularFile = true,
            )
            val updated = status.updatedTarget.targetIdentityCurrent
            val parentAndPathValid = expectedParent.sameKernelObjectAs(status.updatedTarget.parentIdentityCurrent) &&
                status.updatedTarget.resolvedAbsolutePath == expectedResolvedPath
            val expectedMode = if (requireModeMatch) expectedAttributeSource.modeOctal else 0b110_000_000
            val attributesPreserved = updated.ownerUid == expectedAttributeSource.ownerUid &&
                updated.ownerGid == expectedAttributeSource.ownerGid &&
                updated.modeOctal == expectedMode &&
                updated.selinuxLabel == expectedAttributeSource.selinuxLabel
            val contentBound = expectedContentSha256 == null ||
                status.updatedTarget.contentSha256 == null ||
                status.updatedTarget.contentSha256 == expectedContentSha256
            val sizeAndVersionValid = status.updatedTarget.sizeBytes == utf8ByteLength &&
                status.updatedTarget.version.isNotBlank() &&
                status.updatedTarget.version != previousVersion
            when {
                postRejection != null ->
                    RootFileOperationResult.PostMutationFailure("post_replace_confinement_failed", postRejection)
                !parentAndPathValid ->
                    RootFileOperationResult.PostMutationFailure(
                        "post_replace_target_or_parent_drift",
                        RootConfinementRejection.RENAME_RACE_DETECTED,
                    )
                !attributesPreserved ->
                    RootFileOperationResult.PostMutationFailure("post_replace_attribute_drift")
                !contentBound ->
                    RootFileOperationResult.PostMutationFailure(RootFileOperationReason.COMMITTED_CONTENT_MISMATCH)
                !sizeAndVersionValid ->
                    RootFileOperationResult.PostMutationFailure("post_replace_size_or_version_mismatch")
                else -> {
                    val snapshot = RootMetadataSnapshots.of(workspaceId, relativePath, status.updatedTarget)
                    RootFileOperationResult.Succeeded(snapshot, RootMetadataSnapshots.digest(snapshot))
                }
            }
        }
        is DriverWriteStatus.PostCommitFailure ->
            RootFileOperationResult.PostMutationFailure(status.reason)
        is DriverWriteStatus.AttributePreservationUnsupported ->
            if (status.tempFileCleanedUp) {
                RootFileOperationResult.UnsupportedAttributePreservation(status.attribute)
            } else {
                RootFileOperationResult.PostMutationFailure("temp_cleanup_unconfirmed:${status.attribute}")
            }
        is DriverWriteStatus.UnsupportedMutationDomain -> RootFileOperationResult.Rejected(
            RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
            "${RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN}:${status.reasons}",
        )
        is DriverWriteStatus.Denied ->
            RootFileOperationResult.PermissionDenied(status.selinuxConfirmed, status.errnoName)
        DriverWriteStatus.RaceDetectedTempCleanedUp ->
            RootFileOperationResult.Rejected(RootConfinementRejection.RENAME_RACE_DETECTED, "atomic_replace_race")
    }

    private fun rootRejected(): RootFileOperationResult<Nothing> = RootFileOperationResult.Rejected(
        workspaceRootRejection ?: RootConfinementRejection.IDENTITY_MISMATCH,
        RootFileOperationReason.WORKSPACE_ROOT_UNCONFINED,
    )

    private fun targetNotFound(): RootFileOperationResult<Nothing> = RootFileOperationResult.Rejected(
        RootConfinementRejection.SPECIAL_NODE_REJECTED,
        RootFileOperationReason.TARGET_NOT_FOUND,
    )
}
