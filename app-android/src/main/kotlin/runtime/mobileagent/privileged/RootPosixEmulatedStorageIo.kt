// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.DriverMutationStatus
import runtime.mobileagent.domain.DriverWriteStatus
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootOpenedFileTarget
import runtime.mobileagent.domain.RootOpenedParentTarget

/**
 * Ordinary FD writes and `mkdirat` on emulated storage (`fuse`/`sdcardfs`/vfat/exFAT/`fuseblk`).
 *
 * These filesystems own ownership, mode and label: `chown`/`chmod`/`chcon` and the
 * `security.selinux` attribute are rejected or ignored, and hard links do not exist. Everything
 * here therefore writes through the retained descriptor, reports the attributes the kernel
 * actually shows, keeps conditional/atomic-replace semantics unsupported, and never retries or
 * removes an entry by name after the point where the change may be visible.
 */
internal class RootPosixEmulatedStorageIo(
    private val fdOps: PosixFdOperations,
    private val lifecycle: RootPosixLifecycle,
    private val fileIo: RootPosixFileIo,
) {
    fun replace(
        entry: RetainedTargetEntry,
        target: RootOpenedFileTarget,
        bytes: ByteArray,
        hasConditionalWriter: Boolean,
    ): DriverWriteStatus {
        // A patch streams the old content and replaces it: a conditional commit this filesystem
        // cannot express. Refused before the writable descriptor is even opened.
        if (hasConditionalWriter) {
            return DriverWriteStatus.UnsupportedMutationDomain("emulated_storage_conditional_write_unsupported")
        }
        val writable = try {
            lifecycle.openTracked { fdOps.openWritableFd(entry.targetFd) }
                ?: return DriverWriteStatus.RaceDetectedTempCleanedUp
        } catch (err: PosixOpException) {
            return DriverWriteStatus.Denied(err.errnoName, err.selinuxConfirmed)
        }
        return writable.use { fd ->
            val current = fdOps.fstatIdentity(fd)
            if (current == null || current != target.targetIdentityCurrent || !current.isRegularFile || current.hardlinkCount != 1L ||
                fdOps.statAtNoFollow(entry.parentFd, entry.leafName) != current) {
                return@use DriverWriteStatus.RaceDetectedTempCleanedUp
            }
            write(entry.parentFd, fd, entry.leafName, current, target.parentIdentityBefore,
                target.directoryChain, target.ancestorsAboveRoot, bytes, created = false)
        }
    }

    fun create(entry: RetainedParentEntry, parent: RootOpenedParentTarget, bytes: ByteArray): DriverWriteStatus {
        val created = try {
            // This existing primitive uses O_EXCL | O_NOFOLLOW; an appearing target is never overwritten.
            lifecycle.openTracked { fdOps.createSiblingTempAt(entry.parentFd, entry.leafName, 0b110_000_000) }
                ?: return DriverWriteStatus.PostCommitFailure("emulated_create_cancelled")
        } catch (err: PosixOpException) {
            return if (err.errnoName == "EEXIST") DriverWriteStatus.RaceDetectedTempCleanedUp
            else DriverWriteStatus.Denied(err.errnoName, err.selinuxConfirmed)
        }
        return created.use { fd ->
            val current = fdOps.fstatIdentity(fd)
            if (current == null || !current.hasValidKernelIdentity || !current.isRegularFile || current.hardlinkCount != 1L) {
                return@use DriverWriteStatus.PostCommitFailure("emulated_create_identity_unavailable")
            }
            write(entry.parentFd, fd, entry.leafName, current, parent.parentIdentityBefore,
                parent.directoryChain, parent.ancestorsAboveRoot, bytes, created = true)
        }
    }

    /**
     * Ordinary `mkdirat(2)` on emulated storage.
     *
     * The requested mode is advisory — FUSE synthesizes owner/mode/label and vfat/exFAT derive
     * them from the mount options — so nothing is chowned, chmodded or relabelled afterwards and
     * the observed attributes are returned as they are. Only "the directory appeared, and the
     * descriptor still observes exactly it" decides between success and an uncertain outcome.
     */
    fun createDirectory(
        entry: RetainedParentEntry,
        parent: RootOpenedParentTarget,
        leafName: String,
    ): DriverMutationStatus {
        var created = false
        try {
            lifecycle.mutateWhileActive {
                fdOps.mkdirAt(entry.parentFd, leafName, ADVISORY_DIRECTORY_MODE)
                created = true
            }
        } catch (err: PosixOpException) {
            return if (created) {
                DriverMutationStatus.PostMutationObservationOrPartialFailure("emulated_mkdir_close_race")
            } else {
                DriverMutationStatus.Denied(err.errnoName, err.selinuxConfirmed)
            }
        }
        val createdFd = lifecycle.openTracked { fdOps.openChildDirectoryAtNoFollow(entry.parentFd, leafName) }
            ?: return DriverMutationStatus.PostMutationObservationOrPartialFailure("emulated_mkdir_reopen_failed")
        createdFd.use { fd ->
            val observed = fileIo.observeOpenFdPair(
                entry.parentFd,
                fd,
                parent.parentIdentityBefore,
                parent.directoryChain,
                parent.ancestorsAboveRoot,
            ) ?: return DriverMutationStatus.PostMutationObservationOrPartialFailure("emulated_mkdir_observation_failed")
            if (!observed.targetIdentityCurrent.isDirectory) {
                return DriverMutationStatus.PostMutationObservationOrPartialFailure("emulated_mkdir_not_directory")
            }
            return DriverMutationStatus.Applied(observed)
        }
    }

    private fun write(
        parentFd: PosixRetainedFd, fd: PosixRetainedFd, leafName: String,
        identity: RootFdNodeIdentity, parentBefore: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>, ancestors: List<RootFdNodeIdentity>,
        bytes: ByteArray, created: Boolean,
    ): DriverWriteStatus {
        var mutated = created
        try {
            lifecycle.mutateWhileActive {
                if (!created) {
                    fdOps.truncate(fd, 0L)
                    mutated = true
                }
                fdOps.writeAllAndFsync(fd, bytes)
                if (created) syncParentDirectory(parentFd)
            }
            val observed = fileIo.observeOpenFdPair(parentFd, fd, parentBefore, chain, ancestors)
                ?: return DriverWriteStatus.PostCommitFailure("emulated_write_observation_failed")
            if (!observed.targetIdentityCurrent.sameKernelObjectAs(identity) ||
                fdOps.statAtNoFollow(parentFd, leafName) != observed.targetIdentityCurrent ||
                observed.contentSha256 == null) {
                return DriverWriteStatus.PostCommitFailure("emulated_write_target_changed")
            }
            return DriverWriteStatus.Replaced(observed, inPlace = !created, createdWithFilesystemDefaults = created)
        } catch (failure: Exception) {
            if (!mutated) {
                val posix = failure as? PosixOpException
                return DriverWriteStatus.Denied(posix?.errnoName ?: "EIO", posix?.selinuxConfirmed ?: false)
            }
            // Creation/truncation may already be visible. Retain the outcome; never retry or unlink by name.
            return DriverWriteStatus.PostCommitFailure("emulated_write_incomplete")
        }
    }

    /**
     * Directory `fsync(2)` after a create. Only the errors that prove the filesystem cannot
     * express a directory sync are ignored (`EINVAL`/`ENOSYS`/`EOPNOTSUPP`); `EIO` and every other
     * failure still propagate to the caller, which keeps the outcome `UNKNOWN`.
     */
    private fun syncParentDirectory(parentFd: PosixRetainedFd) {
        try {
            fdOps.fsyncDirectory(parentFd)
        } catch (unsupported: PosixOpException) {
            if (!PosixDirectoryDurability.isUnsupportedDirectorySync(unsupported.errnoName)) throw unsupported
        }
    }

    companion object {
        /** Advisory directory mode; the kernel/FUSE daemon or mount options decide the real one. */
        const val ADVISORY_DIRECTORY_MODE = 0b111_111_000
    }
}
