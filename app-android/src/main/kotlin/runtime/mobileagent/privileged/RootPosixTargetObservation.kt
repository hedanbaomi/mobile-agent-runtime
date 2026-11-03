// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import runtime.mobileagent.domain.RootContentVersionPolicy
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootOpenedFileTarget
import runtime.mobileagent.domain.RootWriteDomainPolicy

/**
 * FD-pinned target observation and version derivation.
 *
 * Extracted from [RootPosixFileIo] for the conditional-token ratchet. The semantics are unchanged:
 * a version describes observed state (identity + attributes + size + content digest/stamp), and
 * verification compares the current kernel objects, the directory link and the observed version.
 */
internal class RootPosixTargetObservation(private val fdOps: PosixFdOperations) {
    fun observeOpenFdPair(
        parentFd: PosixRetainedFd,
        targetFd: PosixRetainedFd,
        parentIdentityBefore: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>,
        ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
        includeContentDigest: Boolean = true,
    ): RootOpenedFileTarget? = runCatching {
        val targetBefore = fdOps.fstatIdentity(targetFd) ?: return null
        val parentCurrent = fdOps.fstatIdentity(parentFd) ?: return null
        val targetCurrent = fdOps.fstatIdentity(targetFd) ?: return null
        val resolvedPath = fdOps.readFdResolvedPath(targetFd) ?: return null
        val parentPath = resolvedPath.substringBeforeLast('/').ifEmpty { "/" }
        val fullChain = ancestorsAboveRoot + chain
        val sizeBytes = fdOps.fstatSizeBytes(targetFd) ?: return null
        val contentDigest = targetFd.takeIf {
            includeContentDigest && sizeBytes <= RootContentVersionPolicy.MAX_CONTENT_BYTES
        }?.let { runCatching { fdOps.fstatContentDigest(it) }.getOrNull() }
        val stamp = runCatching { fdOps.fstatVersionStamp(targetFd) }.getOrNull().orEmpty()
        val version = computeVersion(targetCurrent, sizeBytes, contentDigest, stamp) ?: return null
        RootOpenedFileTarget(
            parentIdentityBefore = parentIdentityBefore,
            targetIdentityBefore = targetBefore,
            parentIdentityCurrent = parentCurrent,
            targetIdentityCurrent = targetCurrent,
            resolvedAbsolutePath = resolvedPath,
            sizeBytes = sizeBytes,
            version = version,
            directoryChain = chain,
            writeDomain = RootWriteDomainPolicy.assess(fullChain, resolvedTargetParentPath = parentPath),
            contentSha256 = contentDigest,
            ancestorsAboveRoot = ancestorsAboveRoot,
        )
    }.getOrNull()

    fun verifyRetainedEntryMatches(
        parentFd: PosixRetainedFd,
        targetFd: PosixRetainedFd,
        leafName: String,
        expected: RootOpenedFileTarget,
        requireContentDigestRecheck: Boolean = true,
    ): Boolean {
        val parentNow = fdOps.fstatIdentity(parentFd)
        val targetNow = fdOps.fstatIdentity(targetFd)
        val linkedNow = if (leafName == ".") targetNow else fdOps.statAtNoFollow(parentFd, leafName)
        val size = fdOps.fstatSizeBytes(targetFd) ?: return false
        val stamp = runCatching { fdOps.fstatVersionStamp(targetFd) }.getOrNull().orEmpty()
        val digest = when {
            expected.contentSha256 == null -> null
            !requireContentDigestRecheck -> expected.contentSha256
            else -> runCatching { fdOps.fstatContentDigest(targetFd) }.getOrNull()
        }
        val version = runCatching {
            computeVersion(targetNow ?: return false, size, digest, stamp)
        }.getOrNull() ?: return false
        return parentNow?.sameKernelObjectAs(expected.parentIdentityCurrent) == true &&
            parentNow.isDirectory && !parentNow.isSymlink &&
            targetNow == expected.targetIdentityCurrent && targetNow.hasValidKernelIdentity &&
            !targetNow.isSymlink && (targetNow.isDirectory ||
                (targetNow.isRegularFile && targetNow.hardlinkCount == 1L)) &&
            linkedNow == targetNow && version == expected.version &&
            fdOps.readFdResolvedPath(targetFd) == expected.resolvedAbsolutePath
    }

    fun versionOf(
        targetFd: PosixRetainedFd,
        identity: RootFdNodeIdentity,
        sizeBytes: Long,
        contentDigest: String? = fdOps.fstatContentDigest(targetFd),
    ): String? {
        val stamp = runCatching { fdOps.fstatVersionStamp(targetFd) }.getOrNull().orEmpty()
        return computeVersion(identity, sizeBytes, contentDigest, stamp)
    }

    private fun computeVersion(
        identity: RootFdNodeIdentity,
        sizeBytes: Long,
        contentDigest: String?,
        stamp: String,
    ): String? {
        if (contentDigest == null && stamp.isEmpty()) return null
        val raw = "${identity.deviceId}:${identity.inode}:${identity.mountId}:" +
            "${identity.modeOctal}:${identity.ownerUid}:${identity.ownerGid}:" +
            "${identity.selinuxLabel}:$sizeBytes:${contentDigest ?: stamp}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
