// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.UUID
import runtime.mobileagent.domain.DriverWriteStatus
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootWriteDomainPolicy

/**
 * Protected staging + atomic commit for directories the target app itself can rewrite.
 *
 * Extracted from [RootPosixMutations] for the conditional-token ratchet; behaviour, proofs and
 * failure semantics are unchanged:
 * - a root-owned 0700 staging directory in the same parent (proven through its FD) holds the
 *   payload; the commit is an atomic `renameat` (replace) or `linkat` (create) from that name;
 * - every failure before the commit leaves the existing target byte-for-byte intact;
 * - a staging directory the app swapped in fails the root-owner/0700/same-mount proof and is never
 *   written to or removed.
 */
internal class RootPosixProtectedStageIo(
    private val fdOps: PosixFdOperations,
    private val lifecycle: RootPosixLifecycle,
    private val fileIo: RootPosixFileIo,
) {
    private class ProtectedStage(
        val parentFd: PosixRetainedFd,
        val name: String,
        val dirFd: PosixRetainedFd,
        val identity: RootFdNodeIdentity,
    )

    private sealed interface StageOpen {
        data class Ready(val stage: ProtectedStage) : StageOpen
        data class Unsupported(val reason: String) : StageOpen
        data class Denied(val errno: String, val selinuxConfirmed: Boolean = false) : StageOpen
    }

    fun replace(
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
    ): DriverWriteStatus {
        val stage = when (val opened = open(parentFd, parentIdentity)) {
            is StageOpen.Ready -> opened.stage
            is StageOpen.Unsupported -> return DriverWriteStatus.UnsupportedMutationDomain(opened.reason)
            is StageOpen.Denied -> return DriverWriteStatus.Denied(opened.errno, opened.selinuxConfirmed)
        }
        try {
            val staged = when (
                val outcome = fileIo.stageTempSibling(
                    parentFd = stage.dirFd,
                    tempName = STAGE_PAYLOAD,
                    utf8Bytes = utf8Bytes,
                    modeOctal = modeOctal,
                    ownerUid = ownerUid,
                    ownerGid = ownerGid,
                    selinuxLabel = selinuxLabel,
                    sourceFdForXattrs = sourceFd,
                    streamWriter = streamWriter,
                )
            ) {
                is RootPosixFileIo.StageOutcome.Staged -> outcome.temp
                is RootPosixFileIo.StageOutcome.Failed -> return outcome.status
            }
            staged.use {
                try {
                    lifecycle.mutateWhileActive {
                        fdOps.renameAt(stage.dirFd, STAGE_PAYLOAD, parentFd, leafName)
                    }
                } catch (err: PosixOpException) {
                    return DriverWriteStatus.Denied(err.errnoName, err.selinuxConfirmed)
                }
                try {
                    fdOps.fsyncDirectory(parentFd)
                } catch (err: PosixOpException) {
                    return DriverWriteStatus.PostCommitFailure("post_commit_fsync_failed:" + err.errnoName)
                } catch (_: Throwable) {
                    return DriverWriteStatus.PostCommitFailure("post_commit_fsync_failed")
                }
                return fileIo.observeCommittedChild(
                    parentFd = parentFd,
                    staged = staged,
                    leafName = leafName,
                    expectedModeOctal = modeOctal,
                    parentIdentityBefore = parentIdentityBefore,
                    chain = chain,
                    ancestorsAboveRoot = ancestorsAboveRoot,
                )
            }
        } finally {
            release(stage)
        }
    }

    fun create(
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
    ): DriverWriteStatus {
        val stage = when (val opened = open(parentFd, parentIdentity)) {
            is StageOpen.Ready -> opened.stage
            is StageOpen.Unsupported -> return DriverWriteStatus.UnsupportedMutationDomain(opened.reason)
            is StageOpen.Denied -> return DriverWriteStatus.Denied(opened.errno, opened.selinuxConfirmed)
        }
        try {
            val staged = when (
                val outcome = fileIo.stageTempSibling(
                    parentFd = stage.dirFd,
                    tempName = STAGE_PAYLOAD,
                    utf8Bytes = utf8Bytes,
                    modeOctal = modeOctal,
                    ownerUid = ownerUid,
                    ownerGid = ownerGid,
                    selinuxLabel = selinuxLabel,
                )
            ) {
                is RootPosixFileIo.StageOutcome.Staged -> outcome.temp
                is RootPosixFileIo.StageOutcome.Failed -> return outcome.status
            }
            staged.use {
                try {
                    lifecycle.mutateWhileActive {
                        fdOps.linkAtNoReplace(stage.dirFd, STAGE_PAYLOAD, parentFd, leafName)
                    }
                } catch (err: PosixOpException) {
                    return if (err.errnoName == "EEXIST") {
                        DriverWriteStatus.RaceDetectedTempCleanedUp
                    } else {
                        DriverWriteStatus.Denied(err.errnoName, err.selinuxConfirmed)
                    }
                }
                if (!fileIo.cleanupTemp(stage.dirFd, STAGE_PAYLOAD)) {
                    return DriverWriteStatus.PostCommitFailure("post_commit_payload_cleanup_unconfirmed")
                }
                try {
                    fdOps.fsyncDirectory(parentFd)
                } catch (err: PosixOpException) {
                    return DriverWriteStatus.PostCommitFailure("post_commit_fsync_failed:" + err.errnoName)
                } catch (_: Throwable) {
                    return DriverWriteStatus.PostCommitFailure("post_commit_fsync_failed")
                }
                return fileIo.observeCommittedChild(
                    parentFd = parentFd,
                    staged = staged,
                    leafName = leafName,
                    expectedModeOctal = modeOctal,
                    parentIdentityBefore = parentIdentityBefore,
                    chain = chain,
                    ancestorsAboveRoot = ancestorsAboveRoot,
                )
            }
        } finally {
            release(stage)
        }
    }

    /**
     * Creates and proves the staging directory through its FD: the service uid must own it, the
     * mode must be exactly 0700 and it must live on the target's device/mount. A directory swapped
     * in after `mkdirat` fails these checks and is never written to or deleted.
     */
    private fun open(parentFd: PosixRetainedFd, parentIdentity: RootFdNodeIdentity): StageOpen {
        val name = "$STAGE_PREFIX${UUID.randomUUID()}"
        try {
            lifecycle.mutateWhileActive { fdOps.mkdirAt(parentFd, name, STAGE_DIR_MODE) }
        } catch (err: PosixOpException) {
            return if (err.errnoName == "EOPNOTSUPP" || err.errnoName == "ENOSYS") {
                StageOpen.Unsupported("private_staging_unsupported")
            } else {
                StageOpen.Denied(err.errnoName, err.selinuxConfirmed)
            }
        }
        val dirFd = lifecycle.openTracked { fdOps.openChildDirectoryAtNoFollow(parentFd, name) }
            ?: return StageOpen.Unsupported("private_staging_unopenable")
        val identity = fdOps.fstatIdentity(dirFd)
        if (identity == null || !isTrustedStage(identity, parentIdentity)) {
            runCatching { dirFd.close() }
            return StageOpen.Denied("EPERM")
        }
        return StageOpen.Ready(ProtectedStage(parentFd, name, dirFd, identity))
    }

    private fun isTrustedStage(identity: RootFdNodeIdentity, parentIdentity: RootFdNodeIdentity): Boolean =
        identity.hasValidKernelIdentity &&
            identity.isDirectory && !identity.isSymlink &&
            identity.ownerUid == RootWriteDomainPolicy.SERVICE_UID &&
            (identity.modeOctal and 0b111_111_111) == STAGE_DIR_MODE &&
            identity.deviceId == parentIdentity.deviceId &&
            identity.mountId == parentIdentity.mountId

    private fun release(stage: ProtectedStage) {
        runCatching { fdOps.unlinkAt(stage.dirFd, STAGE_PAYLOAD) }
        val current = runCatching { fdOps.statAtNoFollow(stage.parentFd, stage.name) }.getOrNull()
        if (current != null && current.sameKernelObjectAs(stage.identity)) {
            runCatching { fdOps.removeDirectoryAt(stage.parentFd, stage.name) }
        }
        runCatching { stage.dirFd.close() }
    }

    companion object {
        private const val STAGE_PREFIX = ".mar-root-stage-"
        private const val STAGE_DIR_MODE = 0b111_000_000
        private const val STAGE_PAYLOAD = "payload"
    }
}
