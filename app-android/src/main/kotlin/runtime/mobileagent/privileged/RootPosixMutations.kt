// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.UUID
import runtime.mobileagent.domain.DriverMutationStatus
import runtime.mobileagent.domain.DriverWriteStatus
import runtime.mobileagent.domain.RootContentVersionPolicy
import runtime.mobileagent.domain.RootDriverPatchOutcome
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootFileOperationReason
import runtime.mobileagent.domain.RootOpenedFileTarget
import runtime.mobileagent.domain.RootOpenedParentTarget
import runtime.mobileagent.domain.RootWriteDomainPolicy

/** FD-pinned metadata/content/entry mutations. The executor proves the write domain first. */
internal class RootPosixMutations(
    private val fdOps: PosixFdOperations,
    private val lifecycle: RootPosixLifecycle,
    private val fileIo: RootPosixFileIo,
    private val inspection: RootPosixInspection,
) {
    fun applyMetadata(
        target: RootOpenedFileTarget,
        modeOctal: Int?,
        ownerUid: Int?,
        ownerGid: Int?,
        selinuxLabel: String?,
    ): DriverMutationStatus {
        val entry = inspection.consumeTarget(target.retainedFdHandle)
            ?: return DriverMutationStatus.RaceOrDisappeared
        return entry.use {
            if (!inspection.verifyEntry(entry, target)) return DriverMutationStatus.RaceOrDisappeared
            if (!target.writeDomain.isProvenForMutation) {
                return DriverMutationStatus.UnsupportedMutationDomain(
                    target.writeDomain.unprovenReasons.joinToString("+"),
                )
            }
            if (target.targetIdentityCurrent.modeOctal and RootPosixFileIo.SPECIAL_MODE_BITS != 0) {
                // Explicit metadata cannot represent setuid/setgid/sticky; refuse before mutation
                // instead of silently clearing or re-granting them.
                return DriverMutationStatus.Unsupported("mode.special_bits")
            }
            val cur = target.targetIdentityCurrent
            var anyMutationApplied = false
            try {
                modeOctal?.let {
                    lifecycle.mutateWhileActive {
                        fdOps.fchmod(entry.targetFd, it and RootPosixFileIo.MAX_MODE_BITS)
                        anyMutationApplied = true
                    }
                }
                if (ownerUid != null || ownerGid != null) {
                    lifecycle.mutateWhileActive {
                        fdOps.fchown(entry.targetFd, ownerUid ?: cur.ownerUid, ownerGid ?: cur.ownerGid)
                        anyMutationApplied = true
                    }
                }
                selinuxLabel?.let {
                    lifecycle.mutateWhileActive {
                        fdOps.fsetSelinuxLabel(entry.targetFd, it)
                        anyMutationApplied = true
                    }
                }
            } catch (err: PosixOpException) {
                return mapMetadataError(err, anyMutationApplied)
            } catch (_: Throwable) {
                return DriverMutationStatus.PostMutationObservationOrPartialFailure("metadata_mutation_exception")
            }
            val updated = fileIo.observeOpenFdPair(
                entry.parentFd,
                entry.targetFd,
                target.parentIdentityBefore,
                target.directoryChain,
                target.ancestorsAboveRoot,
            )?.copy(targetIdentityBefore = target.targetIdentityBefore)
                ?: return DriverMutationStatus.PostMutationObservationOrPartialFailure(
                    "post_metadata_observation_failed",
                )
            if (updated.sizeBytes != target.sizeBytes || updated.contentSha256 != target.contentSha256) {
                return DriverMutationStatus.PostMutationObservationOrPartialFailure("metadata_content_drift")
            }
            DriverMutationStatus.Applied(updated)
        }
    }

    fun writeAtomic(
        target: RootOpenedFileTarget,
        utf8Bytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
    ): DriverWriteStatus = replaceOpenedRegularFile(
        target = target,
        tempPrefix = ".mar-root-tmp-",
        utf8Bytes = utf8Bytes,
        preserveModeOctal = preserveModeOctal,
        preserveOwnerUid = preserveOwnerUid,
        preserveOwnerGid = preserveOwnerGid,
        preserveSelinuxLabel = preserveSelinuxLabel,
    )

    fun applyPatchAtomic(
        target: RootOpenedFileTarget,
        format: String,
        patchBytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
    ): RootDriverPatchOutcome {
        return try {
            var patchedLength = 0L
            var patchedSha256 = ""
            val status = replaceOpenedRegularFile(
                target = target,
                tempPrefix = ".mar-root-patch-",
                utf8Bytes = ByteArray(0),
                preserveModeOctal = preserveModeOctal,
                preserveOwnerUid = preserveOwnerUid,
                preserveOwnerGid = preserveOwnerGid,
                preserveSelinuxLabel = preserveSelinuxLabel,
                streamWriter = { sourceFd, sink ->
                    val ok = fileIo.streamPatchedContent(sourceFd, format, patchBytes, sink)
                    patchedLength = ok.totalWrittenBytes
                    patchedSha256 = ok.sha256Hex
                    ok.sha256Hex
                },
            )
            RootDriverPatchOutcome.Applied(status, patchedLength, patchedSha256)
        } catch (rejected: RootPosixFileIo.PatchStreamRejectException) {
            rejected.rejection
        }
    }

    private fun replaceOpenedRegularFile(
        target: RootOpenedFileTarget,
        tempPrefix: String,
        utf8Bytes: ByteArray,
        preserveModeOctal: Int,
        preserveOwnerUid: Int,
        preserveOwnerGid: Int,
        preserveSelinuxLabel: String?,
        streamWriter: ((PosixRetainedFd, (ByteArray, Int, Int) -> Unit) -> String)? = null,
    ): DriverWriteStatus {
        val entry = inspection.consumeTarget(target.retainedFdHandle)
            ?: return DriverWriteStatus.RaceDetectedTempCleanedUp
        return entry.use {
            val cur = target.targetIdentityCurrent
            if (!inspection.verifyEntry(entry, target) || !cur.isRegularFile || cur.hardlinkCount != 1L) {
                return DriverWriteStatus.RaceDetectedTempCleanedUp
            }
            if (!target.writeDomain.isProvenForMutation) {
                return DriverWriteStatus.UnsupportedMutationDomain(
                    target.writeDomain.unprovenReasons.joinToString("+"),
                )
            }
            val writer = streamWriter?.let { fn ->
                { sink: (ByteArray, Int, Int) -> Unit -> fn(entry.targetFd, sink) }
            }
            // A directory the target app itself can rewrite gives no trusted commit source.
            val deferred = !target.writeDomain.isUid0Exclusive
            if (deferred) {
                // Stage inside a root-owned 0700 directory in the same parent (same filesystem)
                // and commit with an atomic rename from *there*: the app may rename/remove the
                // staging directory itself, but it cannot alter the entries inside it, and a
                // failed commit leaves the old target untouched.
                return replaceThroughProtectedStage(
                    parentFd = entry.parentFd,
                    parentIdentity = target.parentIdentityCurrent,
                    leafName = entry.leafName,
                    utf8Bytes = utf8Bytes,
                    modeOctal = preserveModeOctal,
                    ownerUid = preserveOwnerUid,
                    ownerGid = preserveOwnerGid,
                    selinuxLabel = preserveSelinuxLabel,
                    sourceFd = entry.targetFd,
                    streamWriter = writer,
                    parentIdentityBefore = target.parentIdentityBefore,
                    chain = target.directoryChain,
                    ancestorsAboveRoot = target.ancestorsAboveRoot,
                )
            }
            val tempName = "$tempPrefix${UUID.randomUUID()}"
            val staged = when (
                val outcome = fileIo.stageTempSibling(
                    parentFd = entry.parentFd,
                    tempName = tempName,
                    utf8Bytes = utf8Bytes,
                    modeOctal = preserveModeOctal,
                    ownerUid = preserveOwnerUid,
                    ownerGid = preserveOwnerGid,
                    selinuxLabel = preserveSelinuxLabel,
                    sourceFdForXattrs = entry.targetFd,
                    streamWriter = writer,
                )
            ) {
                is RootPosixFileIo.StageOutcome.Staged -> outcome.temp
                is RootPosixFileIo.StageOutcome.Failed -> return outcome.status
            }
            staged.use {
                if (!inspection.verifyEntry(entry, target)) {
                    return if (fileIo.cleanupTemp(entry.parentFd, tempName)) {
                        DriverWriteStatus.RaceDetectedTempCleanedUp
                    } else {
                        DriverWriteStatus.PostCommitFailure("temp_cleanup_unconfirmed_after_race")
                    }
                }
                fileIo.commitAndObserveChild(
                    parentFd = entry.parentFd,
                    staged = staged,
                    leafName = entry.leafName,
                    parentIdentityBefore = target.parentIdentityBefore,
                    chain = target.directoryChain,
                    replaceExisting = true,
                    ancestorsAboveRoot = target.ancestorsAboveRoot,
                )
            }
        }
    }

    fun createNewFile(
        parent: RootOpenedParentTarget,
        relativePath: String,
        utf8Bytes: ByteArray,
    ): DriverWriteStatus {
        val entry = inspection.consumeParent(parent.retainedFdHandle)
            ?: return DriverWriteStatus.RaceDetectedTempCleanedUp
        return entry.use {
            if (!inspection.verifyParentEntry(entry, parent)) return DriverWriteStatus.RaceDetectedTempCleanedUp
            if (!parent.writeDomain.isProvenForMutation) {
                return DriverWriteStatus.UnsupportedMutationDomain(
                    parent.writeDomain.unprovenReasons.joinToString("+"),
                )
            }
            val cur = parent.parentIdentityCurrent
            val deferred = !parent.writeDomain.isUid0Exclusive
            if (deferred) {
                return createThroughProtectedStage(
                    parentFd = entry.parentFd,
                    parentIdentity = parent.parentIdentityCurrent,
                    leafName = entry.leafName,
                    utf8Bytes = utf8Bytes,
                    modeOctal = 0b110_000_000,
                    ownerUid = cur.ownerUid,
                    ownerGid = cur.ownerGid,
                    selinuxLabel = cur.selinuxLabel,
                    parentIdentityBefore = parent.parentIdentityBefore,
                    chain = parent.directoryChain,
                    ancestorsAboveRoot = parent.ancestorsAboveRoot,
                )
            }
            val tempName = ".mar-root-create-${UUID.randomUUID()}"
            val staged = when (
                val outcome = fileIo.stageTempSibling(
                    parentFd = entry.parentFd,
                    tempName = tempName,
                    utf8Bytes = utf8Bytes,
                    modeOctal = 0b110_000_000,
                    ownerUid = cur.ownerUid,
                    ownerGid = cur.ownerGid,
                    selinuxLabel = cur.selinuxLabel,
                )
            ) {
                is RootPosixFileIo.StageOutcome.Staged -> outcome.temp
                is RootPosixFileIo.StageOutcome.Failed -> return outcome.status
            }
            staged.use {
                fileIo.commitAndObserveChild(
                    parentFd = entry.parentFd,
                    staged = staged,
                    leafName = entry.leafName,
                    parentIdentityBefore = parent.parentIdentityBefore,
                    chain = parent.directoryChain,
                    replaceExisting = false,
                    ancestorsAboveRoot = parent.ancestorsAboveRoot,
                )
            }
        }
    }

    fun createDirectory(parent: RootOpenedParentTarget, leafName: String): DriverMutationStatus {
        val entry = inspection.consumeParent(parent.retainedFdHandle)
            ?: return DriverMutationStatus.RaceOrDisappeared
        return entry.use {
            if (!inspection.verifyParentEntry(entry, parent)) return DriverMutationStatus.RaceOrDisappeared
            if (!parent.writeDomain.isProvenForMutation) {
                return DriverMutationStatus.UnsupportedMutationDomain(
                    parent.writeDomain.unprovenReasons.joinToString("+"),
                )
            }
            var created = false
            try {
                lifecycle.mutateWhileActive {
                    fdOps.mkdirAt(entry.parentFd, leafName, 0b111_000_000)
                    created = true
                }
            } catch (err: PosixOpException) {
                return if (created) {
                    DriverMutationStatus.PostMutationObservationOrPartialFailure("mkdir_close_race")
                } else {
                    DriverMutationStatus.Denied(err.errnoName, err.selinuxConfirmed)
                }
            }
            val createdFd = lifecycle.openTracked { fdOps.openChildDirectoryAtNoFollow(entry.parentFd, leafName) }
                ?: return DriverMutationStatus.PostMutationObservationOrPartialFailure("mkdir_reopen_failed")
            createdFd.use { fd ->
                val cur = parent.parentIdentityCurrent
                try {
                    lifecycle.mutateWhileActive {
                        fdOps.fchown(fd, cur.ownerUid, cur.ownerGid)
                        fdOps.fchmod(fd, 0b111_000_000)
                        cur.selinuxLabel?.takeIf { it.isNotBlank() }?.let { fdOps.fsetSelinuxLabel(fd, it) }
                    }
                } catch (_: Throwable) {
                    return DriverMutationStatus.PostMutationObservationOrPartialFailure("mkdir_attribute_preserve_failed")
                }
                val observed = fileIo.observeOpenFdPair(
                    entry.parentFd,
                    fd,
                    parent.parentIdentityBefore,
                    parent.directoryChain,
                    parent.ancestorsAboveRoot,
                ) ?: return DriverMutationStatus.PostMutationObservationOrPartialFailure("mkdir_observation_failed")
                val createdIdentity = observed.targetIdentityCurrent
                if (!createdIdentity.isDirectory ||
                    createdIdentity.ownerUid != cur.ownerUid ||
                    createdIdentity.ownerGid != cur.ownerGid ||
                    createdIdentity.modeOctal != 0b111_000_000 ||
                    createdIdentity.selinuxLabel != cur.selinuxLabel
                ) {
                    return DriverMutationStatus.PostMutationObservationOrPartialFailure("mkdir_attribute_mismatch")
                }
                DriverMutationStatus.Applied(observed)
            }
        }
    }

    fun delete(target: RootOpenedFileTarget): DriverMutationStatus {
        val entry = inspection.consumeTarget(target.retainedFdHandle)
            ?: return DriverMutationStatus.RaceOrDisappeared
        return entry.use {
            if (!inspection.verifyEntry(entry, target)) return DriverMutationStatus.RaceOrDisappeared
            if (!target.writeDomain.isProvenForMutation) {
                return DriverMutationStatus.UnsupportedMutationDomain(
                    target.writeDomain.unprovenReasons.joinToString("+"),
                )
            }
            var removed = false
            try {
                lifecycle.mutateWhileActive {
                    removed = fdOps.unlinkAt(entry.parentFd, entry.leafName)
                }
            } catch (err: PosixOpException) {
                return if (removed) {
                    DriverMutationStatus.PostMutationObservationOrPartialFailure("delete_close_race")
                } else {
                    DriverMutationStatus.Denied(err.errnoName, err.selinuxConfirmed)
                }
            }
            if (!removed) return DriverMutationStatus.Denied("EIO")
            val stillLinked = fdOps.statAtNoFollow(entry.parentFd, entry.leafName)
            if (stillLinked != null) {
                return DriverMutationStatus.PostMutationObservationOrPartialFailure("delete_unconfirmed")
            }
            DriverMutationStatus.Deleted(target)
        }
    }

    fun move(
        source: RootOpenedFileTarget,
        destinationParent: RootOpenedParentTarget,
        destinationLeafName: String,
        replaceExisting: Boolean,
    ): DriverMutationStatus {
        val sourceEntry = inspection.consumeTarget(source.retainedFdHandle)
            ?: return DriverMutationStatus.RaceOrDisappeared
        return sourceEntry.use {
            val destinationEntry = inspection.consumeParent(destinationParent.retainedFdHandle)
                ?: return DriverMutationStatus.RaceOrDisappeared
            destinationEntry.use {
                if (!inspection.verifyEntry(sourceEntry, source)) return DriverMutationStatus.RaceOrDisappeared
                if (!inspection.verifyParentEntryForMutation(destinationEntry, destinationParent)) {
                    return DriverMutationStatus.RaceOrDisappeared
                }
                if (!source.writeDomain.isProvenForMutation || !destinationParent.writeDomain.isProvenForMutation) {
                    return DriverMutationStatus.UnsupportedMutationDomain(
                        (source.writeDomain.unprovenReasons + destinationParent.writeDomain.unprovenReasons)
                            .joinToString("+"),
                    )
                }
                val existing = fdOps.statAtNoFollow(destinationEntry.parentFd, destinationLeafName)
                if (existing != null) {
                    if (!replaceExisting ||
                        source.targetIdentityCurrent.isDirectory ||
                        existing.isSymlink ||
                        !existing.isRegularFile ||
                        existing.hardlinkCount != 1L ||
                        !existing.hasValidKernelIdentity
                    ) {
                        return DriverMutationStatus.RaceOrDisappeared
                    }
                }
                var destinationMutated = false
                try {
                    if (source.targetIdentityCurrent.isDirectory) {
                        lifecycle.mutateWhileActive {
                            fdOps.renameAt(
                                sourceEntry.parentFd,
                                sourceEntry.leafName,
                                destinationEntry.parentFd,
                                destinationLeafName,
                            )
                            destinationMutated = true
                        }
                    } else if (replaceExisting) {
                        lifecycle.mutateWhileActive {
                            fdOps.renameAt(
                                sourceEntry.parentFd,
                                sourceEntry.leafName,
                                destinationEntry.parentFd,
                                destinationLeafName,
                            )
                            destinationMutated = true
                        }
                    } else {
                        lifecycle.mutateWhileActive {
                            fdOps.linkAtNoReplace(
                                sourceEntry.parentFd,
                                sourceEntry.leafName,
                                destinationEntry.parentFd,
                                destinationLeafName,
                            )
                            destinationMutated = true
                        }
                        val linked = fdOps.statAtNoFollow(destinationEntry.parentFd, destinationLeafName)
                        if (linked == null || !linked.sameKernelObjectAs(source.targetIdentityCurrent)) {
                            runCatching { fdOps.unlinkAt(destinationEntry.parentFd, destinationLeafName) }
                            return DriverMutationStatus.PostMutationObservationOrPartialFailure(
                                "move_link_identity_mismatch",
                            )
                        }
                        if (!fdOps.unlinkAt(sourceEntry.parentFd, sourceEntry.leafName)) {
                            return DriverMutationStatus.PostMutationObservationOrPartialFailure(
                                "move_source_unlink_unconfirmed",
                            )
                        }
                    }
                } catch (err: PosixOpException) {
                    return if (destinationMutated) {
                        DriverMutationStatus.PostMutationObservationOrPartialFailure(
                            "move_partial_mutation_failed:${err.errnoName}",
                        )
                    } else {
                        DriverMutationStatus.Denied(err.errnoName, err.selinuxConfirmed)
                    }
                }
                val movedFd = lifecycle.openTracked {
                    fdOps.openTargetAt(
                        destinationEntry.parentFd,
                        destinationLeafName,
                        followSymlink = false,
                        writable = false,
                    )
                } ?: return DriverMutationStatus.PostMutationObservationOrPartialFailure("move_reopen_failed")
                movedFd.use { fd ->
                    val observed = fileIo.observeOpenFdPair(
                        destinationEntry.parentFd,
                        fd,
                        destinationParent.parentIdentityCurrent,
                        destinationParent.directoryChain,
                        destinationParent.ancestorsAboveRoot,
                    ) ?: return DriverMutationStatus.PostMutationObservationOrPartialFailure("move_observation_failed")
                    if (!observed.targetIdentityCurrent.sameKernelObjectAs(source.targetIdentityCurrent)) {
                        return DriverMutationStatus.PostMutationObservationOrPartialFailure("move_target_identity_mismatch")
                    }
                    if (fdOps.statAtNoFollow(sourceEntry.parentFd, sourceEntry.leafName) != null) {
                        return DriverMutationStatus.PostMutationObservationOrPartialFailure("move_source_still_linked")
                    }
                    DriverMutationStatus.Moved(observed)
                }
            }
        }
    }

    /**
     * Unconditional replacement in a directory the target app can rewrite.
     *
     * A root-owned 0700 staging directory in the same parent (same filesystem) holds the payload
     * under a fixed name; the commit is an atomic `renameat(stageDirFd, payload, parentFd, leaf)`.
     * The directory owner may rename or remove that staging directory, but can neither create nor
     * replace entries inside it, and a failed commit leaves the old target byte-for-byte intact.
     * When the backend cannot provide that directory, the mutation is refused before any side
     * effect instead of falling back to a name in the attacker-controlled parent.
     */
    private val protectedStageIo = RootPosixProtectedStageIo(fdOps, lifecycle, fileIo)

    /** Thin delegate; the protected staging logic lives in [RootPosixProtectedStageIo]. */
    private fun replaceThroughProtectedStage(
        parentFd: PosixRetainedFd,
        parentIdentity: RootFdNodeIdentity,
        leafName: String,
        utf8Bytes: ByteArray,
        modeOctal: Int,
        ownerUid: Int,
        ownerGid: Int,
        selinuxLabel: String?,
        sourceFd: PosixRetainedFd?,
        streamWriter: (((ByteArray, Int, Int) -> Unit) -> String)?,
        parentIdentityBefore: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>,
        ancestorsAboveRoot: List<RootFdNodeIdentity>,
    ): DriverWriteStatus = protectedStageIo.replace(
        parentFd = parentFd,
        parentIdentity = parentIdentity,
        leafName = leafName,
        utf8Bytes = utf8Bytes,
        modeOctal = modeOctal,
        ownerUid = ownerUid,
        ownerGid = ownerGid,
        selinuxLabel = selinuxLabel,
        sourceFd = sourceFd,
        streamWriter = streamWriter,
        parentIdentityBefore = parentIdentityBefore,
        chain = chain,
        ancestorsAboveRoot = ancestorsAboveRoot,
    )

    /** Thin delegate; create-if-absent shares the same protected staging. */
    private fun createThroughProtectedStage(
        parentFd: PosixRetainedFd,
        parentIdentity: RootFdNodeIdentity,
        leafName: String,
        utf8Bytes: ByteArray,
        modeOctal: Int,
        ownerUid: Int,
        ownerGid: Int,
        selinuxLabel: String?,
        parentIdentityBefore: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>,
        ancestorsAboveRoot: List<RootFdNodeIdentity>,
    ): DriverWriteStatus = protectedStageIo.create(
        parentFd = parentFd,
        parentIdentity = parentIdentity,
        leafName = leafName,
        utf8Bytes = utf8Bytes,
        modeOctal = modeOctal,
        ownerUid = ownerUid,
        ownerGid = ownerGid,
        selinuxLabel = selinuxLabel,
        parentIdentityBefore = parentIdentityBefore,
        chain = chain,
        ancestorsAboveRoot = ancestorsAboveRoot,
    )

    private fun mapMetadataError(err: PosixOpException, anyMutationApplied: Boolean): DriverMutationStatus {
        if (anyMutationApplied) {
            return DriverMutationStatus.PostMutationObservationOrPartialFailure(
                "partial_metadata_mutation_failed:${err.errnoName}",
            )
        }
        return err.unsupportedAttribute?.let { DriverMutationStatus.Unsupported(it) }
            ?: DriverMutationStatus.Denied(err.errnoName, err.selinuxConfirmed)
    }

}
