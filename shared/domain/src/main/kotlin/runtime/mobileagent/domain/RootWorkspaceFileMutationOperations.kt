// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Create/delete/move/apply_patch primitives on top of the read and write primitives. */
class RootWorkspaceFileMutationOperations(
    private val workspaceRootIdentity: RootFdNodeIdentity,
    private val controlPlane: RootControlPlaneBoundary,
    private val driver: RootPosixFileDriver,
    private val trustedMountRoots: RootTrustedMountRoots?,
    private val workspaceRootRejection: RootConfinementRejection?,
    private val readOperations: RootWorkspaceFileReadOperations,
    private val writeAtomic: (RootTypedArguments.FileWriteText, ByteArray) -> RootFileOperationResult<RootFileMetadataSnapshot>,
    private val settlePatchedWrite: (
        workspaceId: String,
        relativePath: String,
        opened: RootOpenedFileTarget,
        patchedByteLength: Long,
        patchedContentSha256: String,
        status: DriverWriteStatus,
    ) -> RootFileOperationResult<RootFileMetadataSnapshot> = { _, _, _, _, _, _ ->
        RootFileOperationResult.PostMutationFailure("patch_settlement_unconfigured")
    },
) {
    fun createDirectory(args: RootTypedArguments.FileCreateDirectory): RootFileOperationResult<RootFileMetadataSnapshot> {
        if (workspaceRootRejection != null) return rejected(workspaceRootRejection)
        val leaf = args.relativePath.substringAfterLast('/')
        val parent = driver.inspectParentForCreate(args.workspaceId, args.relativePath)
            ?: return RootFileOperationResult.Rejected(
                RootConfinementRejection.SPECIAL_NODE_REJECTED,
                RootFileOperationReason.TARGET_NOT_FOUND,
            )
        try {
            val rejection = verifyParent(parent) ?: writeDomainRejection(parent.writeDomain)
            if (rejection != null) return rejection
            val status = runCatching { driver.createDirectoryAt(parent, leaf) }.getOrElse {
                DriverMutationStatus.PostMutationObservationOrPartialFailure("create_directory_dispatch_failed")
            }
            return settleApplied(args.workspaceId, args.relativePath, status)
        } finally {
            driver.releaseOpenedTarget(parent.retainedFdHandle)
        }
    }

    fun delete(args: RootTypedArguments.FileDelete): RootFileOperationResult<RootDeletedEntry> {
        if (workspaceRootRejection != null) return rejected(workspaceRootRejection)
        val opened = driver.inspectOpenedTarget(args.workspaceId, args.relativePath, followFinalSymlink = false)
            ?: return RootFileOperationResult.Rejected(
                RootConfinementRejection.SPECIAL_NODE_REJECTED,
                RootFileOperationReason.TARGET_NOT_FOUND,
            )
        try {
            val rejection = verifyTarget(opened, expectedVersion = null, requireRegularFile = false)
                ?: writeDomainRejection(opened.writeDomain)
            if (rejection != null) return rejection
            val status = runCatching { driver.deleteOpenedTarget(opened) }.getOrElse {
                DriverMutationStatus.PostMutationObservationOrPartialFailure("delete_dispatch_failed")
            }
            return when (status) {
                is DriverMutationStatus.Deleted -> RootFileOperationResult.Succeeded(
                    RootDeletedEntry(
                        workspaceId = args.workspaceId,
                        relativePath = args.relativePath,
                        deletedSnapshot = RootMetadataSnapshots.of(
                            args.workspaceId, args.relativePath, status.deletedTarget,
                        ),
                    ),
                    RootMetadataSnapshots.digest(
                        RootMetadataSnapshots.of(args.workspaceId, args.relativePath, status.deletedTarget),
                    ),
                )
                else -> failure(status, "delete")
            }
        } finally {
            driver.releaseOpenedTarget(opened.retainedFdHandle)
        }
    }

    fun move(args: RootTypedArguments.FileMove): RootFileOperationResult<RootMovedEntry> {
        if (workspaceRootRejection != null) return rejected(workspaceRootRejection)
        val destinationLeaf = args.destinationRelativePath.substringAfterLast('/')
        val source = driver.inspectOpenedTarget(args.workspaceId, args.sourceRelativePath, followFinalSymlink = false)
            ?: return RootFileOperationResult.Rejected(
                RootConfinementRejection.SPECIAL_NODE_REJECTED,
                RootFileOperationReason.TARGET_NOT_FOUND,
            )
        try {
            val sourceRejection = verifyTarget(source, expectedVersion = null, requireRegularFile = false)
                ?: writeDomainRejection(source.writeDomain)
            if (sourceRejection != null) return sourceRejection
            val destinationParent = driver.inspectParentForMutation(args.workspaceId, args.destinationRelativePath)
                ?: return RootFileOperationResult.Rejected(
                    RootConfinementRejection.SPECIAL_NODE_REJECTED,
                    RootFileOperationReason.TARGET_NOT_FOUND,
                )
            try {
                val destinationRejection = verifyParent(destinationParent)
                    ?: writeDomainRejection(destinationParent.writeDomain)
                    ?: crossOwnerMoveRejection(source.writeDomain, destinationParent.writeDomain)
                if (destinationRejection != null) return destinationRejection
                val status = runCatching {
                    driver.moveOpenedTarget(source, destinationParent, destinationLeaf, args.replaceExisting)
                }.getOrElse {
                    DriverMutationStatus.PostMutationObservationOrPartialFailure("move_dispatch_failed")
                }
                return when (status) {
                    is DriverMutationStatus.Moved -> {
                        val snapshot = RootMetadataSnapshots.of(
                            args.workspaceId, args.destinationRelativePath, status.updatedTarget,
                        )
                        RootFileOperationResult.Succeeded(
                            RootMovedEntry(
                                workspaceId = args.workspaceId,
                                sourceRelativePath = args.sourceRelativePath,
                                destinationRelativePath = args.destinationRelativePath,
                                snapshot = snapshot,
                            ),
                            RootMetadataSnapshots.digest(snapshot),
                        )
                    }
                    else -> failure(status, "move")
                }
            } finally {
                driver.releaseOpenedTarget(destinationParent.retainedFdHandle)
            }
        } finally {
            driver.releaseOpenedTarget(source.retainedFdHandle)
        }
    }

    fun applyPatch(
        args: RootTypedArguments.FileApplyPatch,
        patchBytes: ByteArray,
    ): RootFileOperationResult<RootFileMetadataSnapshot> {
        if (workspaceRootRejection != null) return rejected(workspaceRootRejection)
        if (patchBytes.size != args.patchByteLength ||
            RootMetadataSnapshots.sha256Hex(patchBytes) != args.patchSha256
        ) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.LOCATOR_INVALID,
                RootFileOperationReason.PATCH_INVALID,
            )
        }
        val opened = driver.inspectOpenedTarget(args.workspaceId, args.relativePath, followFinalSymlink = false)
            ?: return RootFileOperationResult.Rejected(
                RootConfinementRejection.SPECIAL_NODE_REJECTED,
                RootFileOperationReason.TARGET_NOT_FOUND,
            )
        val driverOutcome: RootDriverPatchOutcome? = try {
            val targetRejection = verifyTarget(opened, expectedVersion = args.expectedVersion, requireRegularFile = true)
                ?: writeDomainRejection(opened.writeDomain)
            if (targetRejection != null) return targetRejection
            // Patching reads then replaces; in a directory the target app can rewrite the
            // replacement cannot be bound to the read version, so it is refused up front.
            if (!opened.writeDomain.isUid0Exclusive) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.WRITE_DOMAIN_VERSION_UNSUPPORTED,
                    RootFileOperationReason.WRITE_DOMAIN_VERSION_UNSUPPORTED,
                )
            }
            val cur = opened.targetIdentityCurrent
            runCatching {
                driver.applyPatchAtomicSiblingAndReplace(
                    target = opened,
                    format = args.format,
                    patchBytes = patchBytes,
                    preserveModeOctal = cur.modeOctal,
                    preserveOwnerUid = cur.ownerUid,
                    preserveOwnerGid = cur.ownerGid,
                    preserveSelinuxLabel = cur.selinuxLabel,
                )
            }.getOrElse {
                RootDriverPatchOutcome.Applied(
                    DriverWriteStatus.PostCommitFailure("driver_uncaught_exception_after_dispatch"),
                    0L,
                    "",
                )
            }
        } finally {
            driver.releaseOpenedTarget(opened.retainedFdHandle)
        }
        when (driverOutcome) {
            is RootDriverPatchOutcome.PatchRejected -> return RootFileOperationResult.Rejected(
                if (driverOutcome.reason == RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET) {
                    RootConfinementRejection.MOUNT_ID_UNAVAILABLE
                } else {
                    RootConfinementRejection.SPECIAL_NODE_REJECTED
                },
                driverOutcome.reason,
            )
            is RootDriverPatchOutcome.Applied -> return settlePatchedWrite(
                args.workspaceId,
                args.relativePath,
                opened,
                driverOutcome.patchedByteLength,
                driverOutcome.patchedContentSha256,
                driverOutcome.writeStatus,
            )
            null -> Unit
        }
        val current = readOperations.readFull(args.workspaceId, args.relativePath, args.expectedVersion)
        val read = when (current) {
            is RootFileOperationResult.Succeeded -> current.value
            is RootFileOperationResult.Rejected -> return current
            is RootFileOperationResult.PermissionDenied -> return current
            is RootFileOperationResult.PostMutationFailure -> return current
            is RootFileOperationResult.UnsupportedAttributePreservation -> return current
        }
        val outcome = if (args.format == "search_replace") {
            RootSearchReplacePatch.apply(read.content, patchBytes)
        } else {
            RootUnifiedPatch.apply(read.content, patchBytes)
        }
        if (outcome !is RootPatchOutcome.Applied) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.SPECIAL_NODE_REJECTED,
                (outcome as RootPatchOutcome.Rejected).reason,
            )
        }
        val patched = outcome.bytes
        // file_write_text carries at most RootExecutionBudget.MAX_OUTPUT_BYTES; refuse before any
        // FileWriteText construction (whose init would throw) or side effect.
        if (patched.size > RootExecutionBudget.MAX_OUTPUT_BYTES) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
                RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET,
            )
        }
        if (patched.size > RootContentVersionPolicy.MAX_CONTENT_BYTES) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
                RootFileOperationReason.CONTENT_VERSION_UNOBSERVABLE,
            )
        }
        val writeArgs = RootTypedArguments.FileWriteText(
            workspaceId = args.workspaceId,
            relativePath = args.relativePath,
            contentSha256 = RootMetadataSnapshots.sha256Hex(patched),
            contentByteLength = patched.size,
            replaceExisting = true,
            expectedVersion = args.expectedVersion,
        )
        return writeAtomic(writeArgs, patched)
    }

    private fun verifyParent(parent: RootOpenedParentTarget): RootFileOperationResult<Nothing>? {
        val rejection = RootOperationBoundary.verifyOpenedParent(
            workspaceRootIdentity, controlPlane, trustedMountRoots, parent,
        ) ?: return null
        return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
    }

    private fun verifyTarget(
        opened: RootOpenedFileTarget,
        expectedVersion: String?,
        requireRegularFile: Boolean,
    ): RootFileOperationResult<Nothing>? {
        val rejection = RootOperationBoundary.verifyOpenedTarget(
            workspaceRootIdentity, controlPlane, trustedMountRoots, opened, expectedVersion, requireRegularFile,
        ) ?: return null
        return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
    }

    private fun writeDomainRejection(evidence: RootWriteDomainEvidence): RootFileOperationResult<Nothing>? =
        if (evidence.isProvenForMutation) {
            null
        } else {
            RootFileOperationResult.Rejected(
                RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
                "${RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN}:${evidence.unprovenReasons.joinToString("+")}",
            )
        }

    private fun crossOwnerMoveRejection(
        sourceDomain: RootWriteDomainEvidence,
        destinationDomain: RootWriteDomainEvidence,
    ): RootFileOperationResult<Nothing>? =
        if (sourceDomain.kind == destinationDomain.kind &&
            sourceDomain.ownerUid == destinationDomain.ownerUid
        ) {
            null
        } else {
            writeDomainRejection(
                RootWriteDomainEvidence.unproven(RootWriteDomainEvidence.REASON_MIXED_NON_ROOT_OWNERS),
            )
        }

    private fun settleApplied(
        workspaceId: String,
        relativePath: String,
        status: DriverMutationStatus,
    ): RootFileOperationResult<RootFileMetadataSnapshot> = when (status) {
        is DriverMutationStatus.Applied -> {
            val updated = status.updatedTarget
            val rejection = RootOperationBoundary.verifyOpenedTarget(
                workspaceRootIdentity, controlPlane, trustedMountRoots, updated,
                expectedVersion = null,
                requireRegularFile = updated.targetIdentityCurrent.isRegularFile,
                expectedInode = updated.targetIdentityCurrent.inode,
            )
            if (rejection != null) {
                RootFileOperationResult.PostMutationFailure("post_mutation_confinement_failed", rejection)
            } else {
                val snapshot = RootMetadataSnapshots.of(workspaceId, relativePath, updated)
                RootFileOperationResult.Succeeded(snapshot, RootMetadataSnapshots.digest(snapshot))
            }
        }
        else -> failure(status, "mutation")
    }

    private fun failure(
        status: DriverMutationStatus,
        operation: String,
    ): RootFileOperationResult<Nothing> = when (status) {
        is DriverMutationStatus.Unsupported -> RootFileOperationResult.UnsupportedAttributePreservation(status.attribute)
        is DriverMutationStatus.UnsupportedMutationDomain -> RootFileOperationResult.Rejected(
            RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
            "${RootFileOperationReason.UNSUPPORTED_WRITE_DOMAIN}:${status.reasons}",
        )
        is DriverMutationStatus.Denied -> RootFileOperationResult.PermissionDenied(status.selinuxConfirmed, status.errnoName)
        is DriverMutationStatus.PostMutationObservationOrPartialFailure ->
            RootFileOperationResult.PostMutationFailure(status.reason)
        DriverMutationStatus.RaceOrDisappeared ->
            RootFileOperationResult.Rejected(RootConfinementRejection.RENAME_RACE_DETECTED, "${operation}_race")
        is DriverMutationStatus.Applied, is DriverMutationStatus.Deleted, is DriverMutationStatus.Moved ->
            RootFileOperationResult.PostMutationFailure("${operation}_unexpected_applied_status")
    }

    private fun rejected(rejection: RootConfinementRejection): RootFileOperationResult<Nothing> =
        RootFileOperationResult.Rejected(rejection, RootFileOperationReason.WORKSPACE_ROOT_UNCONFINED)
}
