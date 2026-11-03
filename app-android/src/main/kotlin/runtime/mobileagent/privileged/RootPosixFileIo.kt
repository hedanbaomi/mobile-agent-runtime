// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import runtime.mobileagent.domain.DriverWriteStatus
import runtime.mobileagent.domain.RootContentVersionPolicy
import runtime.mobileagent.domain.RootDriverPatchOutcome
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootFileOperationReason
import runtime.mobileagent.domain.RootOpenedFileTarget
import runtime.mobileagent.domain.RootStreamingPatch
import runtime.mobileagent.domain.RootStreamingPatchResult
import runtime.mobileagent.domain.RootWriteDomainPolicy

/**
 * FD I/O and observations; the owning driver supplies its lifecycle barriers.
 *
 * Content replacement binds the staged temporary FD to the actual commit: the temporary name must
 * still resolve to the staged dev/inode immediately before the rename/link, the committed name
 * must resolve to the same dev/inode afterwards, and the reopened full-content digest must equal
 * the staged content. A non-UID0-exclusive parent cannot reach this code (executor gate).
 */
internal class RootPosixFileIo(
    private val fdOps: PosixFdOperations,
    private val requireActive: () -> Unit,
    private val openTracked: (() -> PosixRetainedFd?) -> PosixRetainedFd?,
    private val mutateWhileActive: (() -> Unit) -> Unit,
) {
    data class StagedTempSibling(
        val fd: PosixRetainedFd,
        val identity: RootFdNodeIdentity,
        val contentSha256: String,
        val name: String,
    ) : AutoCloseable {
        override fun close() {
            runCatching { fd.close() }
        }
    }

    sealed interface StageOutcome {
        data class Staged(val temp: StagedTempSibling) : StageOutcome
        data class Failed(val status: DriverWriteStatus) : StageOutcome
    }

    internal class PatchStreamRejectException(
        val rejection: RootDriverPatchOutcome.PatchRejected,
    ) : Exception()

    fun streamPatchedContent(
        sourceFd: PosixRetainedFd,
        format: String,
        patchBytes: ByteArray,
        sink: (ByteArray, Int, Int) -> Unit,
    ): RootStreamingPatchResult.Succeeded {
        val sizeBytes = fdOps.fstatSizeBytes(sourceFd) ?: throw PosixOpException("ECANCELED")
        if (sizeBytes < 0L || sizeBytes > RootContentVersionPolicy.MAX_CONTENT_BYTES) {
            throw PatchStreamRejectException(
                RootDriverPatchOutcome.PatchRejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET),
            )
        }
        val patchRes = RootStreamingPatch.applyStreamed(
            totalSizeBytes = sizeBytes,
            readRange = { offset, len ->
                requireActive()
                fdOps.readFdRange(sourceFd, offset, len)
            },
            format = format,
            patchBytes = patchBytes,
            emitChunk = { buf, off, len ->
                requireActive()
                sink(buf, off, len)
            },
        )
        return when (patchRes) {
            is RootStreamingPatchResult.Succeeded -> patchRes
            is RootStreamingPatchResult.Rejected -> throw PatchStreamRejectException(
                RootDriverPatchOutcome.PatchRejected(patchRes.reason),
            )
        }
    }

    fun stageTempSibling(
        parentFd: PosixRetainedFd,
        tempName: String,
        utf8Bytes: ByteArray,
        modeOctal: Int,
        ownerUid: Int,
        ownerGid: Int,
        selinuxLabel: String?,
        sourceFdForXattrs: PosixRetainedFd? = null,
        streamWriter: (((ByteArray, Int, Int) -> Unit) -> String)? = null,
        deferAttributes: Boolean = false,
    ): StageOutcome {
        val expectedMode = modeOctal and MAX_MODE_BITS
        // Deferred staging keeps the inode root-owned 0600 until it is linked into the target
        // directory, so the directory owner cannot rewrite the bytes that are about to commit.
        val createMode = if (deferAttributes) DEFERRED_STAGE_MODE else expectedMode and 0b111_111_111
        val tempFd = try {
            openTracked { fdOps.createSiblingTempAt(parentFd, tempName, createMode) }
                ?: throw PosixOpException("ECANCELED")
        } catch (err: PosixOpException) {
            if (err.errnoName == "ECANCELED") {
                return StageOutcome.Failed(DriverWriteStatus.PostCommitFailure("temp_creation_cancelled_cleanup_unconfirmed"))
            }
            return StageOutcome.Failed(DriverWriteStatus.Denied(err.errnoName, err.selinuxConfirmed))
        }
        return try {
            // Order: write, then fchown (which clears setuid/setgid), then fchmod with the full mode,
            // then SELinux label and preservable user/security extended attributes.
            var streamedDigest: String? = null
            if (streamWriter != null) {
                fdOps.writeChunksAndFsync(tempFd) { sink ->
                    streamedDigest = streamWriter(sink)
                }
            } else {
                fdOps.writeAllAndFsync(tempFd, utf8Bytes)
            }
            requireActive()
            if (!deferAttributes) {
                fdOps.fchown(tempFd, ownerUid, ownerGid)
                fdOps.fchmod(tempFd, expectedMode)
                selinuxLabel?.takeIf { it.isNotBlank() }?.let { fdOps.fsetSelinuxLabel(tempFd, it) }
                PosixXattrAndAncestorSupport.copyPreservableXattrs(fdOps, sourceFdForXattrs, tempFd)
            } else {
                // Ownership/mode stay root-0600 until the link, but the label and extended
                // attributes are applied now so an unsupported attribute still fails *before*
                // anything becomes visible under the target name.
                selinuxLabel?.takeIf { it.isNotBlank() }?.let { fdOps.fsetSelinuxLabel(tempFd, it) }
                PosixXattrAndAncestorSupport.copyPreservableXattrs(fdOps, sourceFdForXattrs, tempFd)
            }
            requireActive()
            val identity = fdOps.fstatIdentity(tempFd) ?: throw PosixOpException("EIO")
            if (!identity.isRegularFile || (!deferAttributes && identity.hardlinkCount != 1L) ||
                (!deferAttributes && identity.modeOctal != expectedMode)
            ) {
                tempFd.close()
                val cleaned = cleanupTemp(parentFd, tempName)
                return when {
                    !cleaned -> StageOutcome.Failed(
                        DriverWriteStatus.PostCommitFailure("temp_cleanup_unconfirmed:mode_or_type_mismatch"),
                    )
                    modeOctal and SPECIAL_MODE_BITS != 0 && !deferAttributes -> StageOutcome.Failed(
                        DriverWriteStatus.AttributePreservationUnsupported("mode.special_bits", true),
                    )
                    else -> StageOutcome.Failed(DriverWriteStatus.Denied("EIO"))
                }
            }
            val digest = fdOps.fstatContentDigest(tempFd) ?: streamedDigest ?: contentDigest(utf8Bytes)
            if (streamedDigest != null && digest != streamedDigest) {
                throw PosixOpException("EIO")
            }
            StageOutcome.Staged(StagedTempSibling(tempFd, identity, digest, tempName))
        } catch (patchReject: PatchStreamRejectException) {
            tempFd.close()
            if (!cleanupTemp(parentFd, tempName)) {
                StageOutcome.Failed(DriverWriteStatus.PostCommitFailure("temp_cleanup_unconfirmed:patch_rejected"))
            } else {
                throw patchReject
            }
        } catch (err: PosixOpException) {
            tempFd.close()
            val cleaned = cleanupTemp(parentFd, tempName)
            if (!cleaned) {
                StageOutcome.Failed(DriverWriteStatus.PostCommitFailure("temp_cleanup_unconfirmed:${err.errnoName}"))
            } else if (modeOctal and SPECIAL_MODE_BITS != 0 &&
                (err.errnoName == "EPERM" || err.errnoName == "EOPNOTSUPP")
            ) {
                // Special mode bits cannot be preserved on this filesystem/identity: refuse
                // explicitly instead of silently committing an ordinary-mode replacement.
                StageOutcome.Failed(DriverWriteStatus.AttributePreservationUnsupported("mode.special_bits", true))
            } else {
                err.unsupportedAttribute?.let {
                    StageOutcome.Failed(DriverWriteStatus.AttributePreservationUnsupported(it, true))
                } ?: StageOutcome.Failed(DriverWriteStatus.Denied(err.errnoName, err.selinuxConfirmed))
            }
        } catch (_: Throwable) {
            tempFd.close()
            if (cleanupTemp(parentFd, tempName)) {
                StageOutcome.Failed(DriverWriteStatus.Denied("EIO"))
            } else {
                StageOutcome.Failed(DriverWriteStatus.PostCommitFailure("temp_cleanup_unconfirmed:EIO"))
            }
        }
    }

    fun commitAndObserveChild(
        parentFd: PosixRetainedFd,
        staged: StagedTempSibling,
        leafName: String,
        parentIdentityBefore: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>,
        replaceExisting: Boolean,
        ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
    ): DriverWriteStatus {
        var committed = false
        try {
            val tempNow = fdOps.statAtNoFollow(parentFd, staged.name)
            if (tempNow == null || !tempNow.sameKernelObjectAs(staged.identity)) {
                return if (cleanupTemp(parentFd, staged.name)) {
                    DriverWriteStatus.RaceDetectedTempCleanedUp
                } else {
                    DriverWriteStatus.PostCommitFailure("temp_source_identity_mismatch_cleanup_unconfirmed")
                }
            }
            if (replaceExisting) {
                mutateWhileActive {
                    fdOps.renameReplaceAt(parentFd, staged.name, leafName)
                    committed = true
                }
            } else {
                mutateWhileActive {
                    fdOps.linkNoReplaceAt(parentFd, staged.name, leafName)
                    committed = true
                }
                val linked = fdOps.statAtNoFollow(parentFd, leafName)
                if (linked == null || !linked.sameKernelObjectAs(staged.identity)) {
                    val removed = runCatching { fdOps.unlinkAt(parentFd, leafName) }.getOrDefault(false)
                    val cleaned = cleanupTemp(parentFd, staged.name)
                    return if (removed && cleaned) {
                        DriverWriteStatus.RaceDetectedTempCleanedUp
                    } else {
                        DriverWriteStatus.PostCommitFailure("create_link_identity_mismatch")
                    }
                }
                if (!cleanupTemp(parentFd, staged.name)) {
                    return DriverWriteStatus.PostCommitFailure("post_commit_temp_cleanup_unconfirmed")
                }
            }
            fdOps.fsyncDirectory(parentFd)
        } catch (err: PosixOpException) {
            if (committed) return DriverWriteStatus.PostCommitFailure("post_commit_error:${err.errnoName}")
            val cleaned = cleanupTemp(parentFd, staged.name)
            return when {
                !cleaned -> DriverWriteStatus.PostCommitFailure("temp_cleanup_unconfirmed:${err.errnoName}")
                !replaceExisting && err.errnoName == "EEXIST" -> DriverWriteStatus.RaceDetectedTempCleanedUp
                else -> DriverWriteStatus.Denied(err.errnoName, err.selinuxConfirmed)
            }
        } catch (_: Throwable) {
            if (committed) return DriverWriteStatus.PostCommitFailure("post_commit_uncertain")
            val cleaned = cleanupTemp(parentFd, staged.name)
            return DriverWriteStatus.PostCommitFailure(
                if (cleaned) "commit_uncertain" else "commit_uncertain_temp_cleanup_unconfirmed",
            )
        }
        val reopened = runCatching {
            openTracked { fdOps.openTargetAt(parentFd, leafName, followSymlink = false, writable = false) }
        }.getOrNull() ?: return DriverWriteStatus.PostCommitFailure("post_commit_reopen_failed")
        return reopened.use { newTargetFd ->
            val observed = observeOpenFdPair(
                parentFd,
                newTargetFd,
                parentIdentityBefore,
                chain,
                ancestorsAboveRoot,
            ) ?: return@use DriverWriteStatus.PostCommitFailure("post_commit_observation_failed")
            if (!observed.targetIdentityCurrent.sameKernelObjectAs(staged.identity) ||
                observed.targetIdentityCurrent.hardlinkCount != 1L ||
                (observed.contentSha256 != null && observed.contentSha256 != staged.contentSha256)
            ) {
                DriverWriteStatus.PostCommitFailure("committed_content_mismatch")
            } else {
                DriverWriteStatus.Replaced(observed)
            }
        }
    }

    /**
     * Observes an already-committed child. No entry is renamed or removed here: the caller has
     * completed the atomic directory-entry operation and only needs the post-conditions.
     */
    fun observeCommittedChild(
        parentFd: PosixRetainedFd,
        staged: StagedTempSibling,
        leafName: String,
        expectedModeOctal: Int,
        parentIdentityBefore: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>,
        ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
    ): DriverWriteStatus {
        val current = fdOps.fstatIdentity(staged.fd)
            ?: return DriverWriteStatus.PostCommitFailure("post_commit_fstat_failed")
        val linkedNow = fdOps.statAtNoFollow(parentFd, leafName)
        if (linkedNow == null || !linkedNow.sameKernelObjectAs(current)) {
            return DriverWriteStatus.PostCommitFailure("committed_target_identity_mismatch")
        }
        val digest = fdOps.fstatContentDigest(staged.fd)
        if (digest != null && digest != staged.contentSha256) {
            return DriverWriteStatus.PostCommitFailure("committed_content_mismatch")
        }
        val reopened = runCatching {
            openTracked { fdOps.openTargetAt(parentFd, leafName, followSymlink = false, writable = false) }
        }.getOrNull() ?: return DriverWriteStatus.PostCommitFailure("post_commit_reopen_failed")
        return reopened.use { newTargetFd ->
            val observed = observeOpenFdPair(
                parentFd,
                newTargetFd,
                parentIdentityBefore,
                chain,
                ancestorsAboveRoot,
            ) ?: return@use DriverWriteStatus.PostCommitFailure("post_commit_observation_failed")
            if (!observed.targetIdentityCurrent.sameKernelObjectAs(current) ||
                observed.targetIdentityCurrent.hardlinkCount != 1L ||
                observed.targetIdentityCurrent.modeOctal != (expectedModeOctal and MAX_MODE_BITS) ||
                (observed.contentSha256 != null && observed.contentSha256 != staged.contentSha256)
            ) {
                DriverWriteStatus.PostCommitFailure("committed_content_mismatch")
            } else {
                DriverWriteStatus.Replaced(observed)
            }
        }
    }

    fun cleanupTemp(parentFd: PosixRetainedFd, tempName: String): Boolean =
        runCatching { fdOps.unlinkAt(parentFd, tempName) }.getOrDefault(false)

    private val observation = RootPosixTargetObservation(fdOps)

    /** Thin delegate; the observation/version logic lives in [RootPosixTargetObservation]. */
    fun observeOpenFdPair(
        parentFd: PosixRetainedFd,
        targetFd: PosixRetainedFd,
        parentIdentityBefore: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>,
        ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
        includeContentDigest: Boolean = true,
    ): RootOpenedFileTarget? = observation.observeOpenFdPair(
        parentFd = parentFd,
        targetFd = targetFd,
        parentIdentityBefore = parentIdentityBefore,
        chain = chain,
        ancestorsAboveRoot = ancestorsAboveRoot,
        includeContentDigest = includeContentDigest,
    )

    /** Thin delegate; see [RootPosixTargetObservation.verifyRetainedEntryMatches]. */
    fun verifyRetainedEntryMatches(
        parentFd: PosixRetainedFd,
        targetFd: PosixRetainedFd,
        leafName: String,
        expected: RootOpenedFileTarget,
        requireContentDigestRecheck: Boolean = true,
    ): Boolean = observation.verifyRetainedEntryMatches(
        parentFd = parentFd,
        targetFd = targetFd,
        leafName = leafName,
        expected = expected,
        requireContentDigestRecheck = requireContentDigestRecheck,
    )

    /** Thin delegate; see [RootPosixTargetObservation.versionOf]. */
    fun versionOf(
        targetFd: PosixRetainedFd,
        identity: RootFdNodeIdentity,
        sizeBytes: Long,
        contentDigest: String? = fdOps.fstatContentDigest(targetFd),
    ): String? = observation.versionOf(targetFd, identity, sizeBytes, contentDigest)

    companion object {
        const val MAX_MODE_BITS = 0b111_111_111_111
        const val SPECIAL_MODE_BITS = 0b111_000_000_000

        /** Root-owned 0600 staging mode used until the inode is linked and attributed. */
        const val DEFERRED_STAGE_MODE = 0b110_000_000

        fun contentDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
