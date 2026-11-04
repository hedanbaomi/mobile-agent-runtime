// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

enum class RootConfinementRejection {
    SYMLINK_REJECTED,
    HARDLINK_REJECTED,
    SPECIAL_NODE_REJECTED,
    NOT_A_DIRECTORY,
    MOUNT_ID_UNAVAILABLE,
    CROSS_MOUNT_REJECTED,
    CONTROL_PLANE_PROTECTED,
    RENAME_RACE_DETECTED,
    LOCATOR_INVALID,
    ORIGIN_MISMATCH,
    IDENTITY_MISMATCH,
    PROVISIONAL_HANDLE_INVALID,
    STAGED_HANDLE_INVALID,
    CAPACITY_EXCEEDED,

    /**
     * The requested operation needs a compare-and-swap on the directory entry, but the target
     * parent is writable by the target app itself. No POSIX primitive can enforce the condition
     * there, so the request is refused *before* any staging or mutation instead of being reported
     * as a conditional write that cannot actually be conditional.
     */
    WRITE_DOMAIN_VERSION_UNSUPPORTED,
}

/**
 * OS-verified App control-plane exclusion boundary for the target Android user.
 *
 * Rejects typed file/metadata access to the host application's credential-encrypted
 * (`dataDir`) and device-protected (`deviceProtectedDataDir`) storage trees — including
 * `databases`, `shared_prefs`, `no_backup`, `code_cache`, and private credential stores —
 * by FD `(deviceId, inode)`, `(mountId, inode)`, and normalized path prefix so bind-mount
 * and symlink/hardlink aliases are also refused.
 */
class RootControlPlaneBoundary(
    val credentialEncryptedDataDir: String,
    val credentialEncryptedIdentity: RootFdNodeIdentity,
    val deviceProtectedDataDir: String,
    val deviceProtectedIdentity: RootFdNodeIdentity,
    pinnedSubtreeIdentities: Collection<RootFdNodeIdentity> = emptyList(),
) {
    private val protectedDeviceInodes = HashSet<Pair<Long, Long>>()
    private val protectedMountInodes = HashSet<Pair<Long, Long>>()

    init {
        require(isValidDataDirPath(credentialEncryptedDataDir)) { "Invalid credential-encrypted dataDir" }
        require(isValidDataDirPath(deviceProtectedDataDir)) { "Invalid device-protected dataDir" }
        val allNodes = listOf(credentialEncryptedIdentity, deviceProtectedIdentity) + pinnedSubtreeIdentities
        allNodes.forEach { node ->
            require(node.hasValidKernelIdentity && !node.isSymlink) {
                "Control-plane node identity must be verified without symlinks"
            }
            protectedDeviceInodes += (node.deviceId to node.inode)
            protectedMountInodes += (node.mountId to node.inode)
        }
    }

    fun isNodeProtected(node: RootFdNodeIdentity): Boolean {
        if (!node.hasValidKernelIdentity) return true
        if ((node.deviceId to node.inode) in protectedDeviceInodes) return true
        if ((node.mountId to node.inode) in protectedMountInodes) return true
        return false
    }

    fun isChainProtected(chain: List<RootFdNodeIdentity>): Boolean =
        chain.any(::isNodeProtected)

    /** Both user-0 spellings remain protected whether Android implements the alias as a link or bind. */
    fun protectedDataDirectoryPaths(): List<String> = listOfNotNull(
        credentialEncryptedDataDir, deviceProtectedDataDir, primaryUserDataDirAlias(credentialEncryptedDataDir),
    )

    fun isProtected(candidateAbsolutePath: String?, node: RootFdNodeIdentity): Boolean {
        if (isNodeProtected(node)) return true
        if (candidateAbsolutePath != null) {
            if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(candidateAbsolutePath)) return true
            if (protectedDataDirectoryPaths().any { isSameOrDescendant(candidateAbsolutePath, it) }) return true
        }
        return false
    }

    private companion object {
        fun isValidDataDirPath(path: String): Boolean =
            path != "/" && RootRecoveryLocatorCodec.isValidAbsoluteRootPath(path)

        fun isSameOrDescendant(candidate: String, root: String): Boolean =
            candidate == root || candidate.startsWith("$root/")

        fun primaryUserDataDirAlias(ceDir: String): String? = when {
            ceDir.startsWith("/data/user/0/") -> "/data/data/" + ceDir.removePrefix("/data/user/0/")
            ceDir.startsWith("/data/data/") -> "/data/user/0/" + ceDir.removePrefix("/data/data/")
            else -> null
        }
    }
}

object RootFdConfinementPolicy {
    fun verifyWorkspaceRoot(
        rootPath: String,
        rootKind: RootWorkspaceRootKind,
        rootIdentity: RootFdNodeIdentity,
        controlPlane: RootControlPlaneBoundary,
    ): RootConfinementRejection? {
        if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(rootPath)) return RootConfinementRejection.LOCATOR_INVALID
        if (rootKind == RootWorkspaceRootKind.DEVICE_ROOT && rootPath != "/") return RootConfinementRejection.LOCATOR_INVALID
        if (rootKind == RootWorkspaceRootKind.DIRECTORY && rootPath == "/") return RootConfinementRejection.LOCATOR_INVALID
        if (rootIdentity.isSymlink) return RootConfinementRejection.SYMLINK_REJECTED
        if (!rootIdentity.isDirectory) return RootConfinementRejection.NOT_A_DIRECTORY
        if (!rootIdentity.hasValidKernelIdentity) return RootConfinementRejection.MOUNT_ID_UNAVAILABLE
        if (controlPlane.isProtected(rootPath, rootIdentity)) return RootConfinementRejection.CONTROL_PLANE_PROTECTED
        return null
    }

    fun verifyDirectoryStep(
        workspaceRoot: RootFdNodeIdentity,
        stepIdentity: RootFdNodeIdentity,
        candidateAbsolutePath: String?,
        controlPlane: RootControlPlaneBoundary,
        trustedMountRoots: RootTrustedMountRoots? = null,
    ): RootConfinementRejection? {
        if (candidateAbsolutePath != null &&
            !RootRecoveryLocatorCodec.isValidAbsoluteRootPath(candidateAbsolutePath)
        ) {
            return RootConfinementRejection.LOCATOR_INVALID
        }
        if (stepIdentity.isSymlink) return RootConfinementRejection.SYMLINK_REJECTED
        if (!stepIdentity.isDirectory) return RootConfinementRejection.NOT_A_DIRECTORY
        if (!stepIdentity.hasValidKernelIdentity || !workspaceRoot.hasValidKernelIdentity) {
            return RootConfinementRejection.MOUNT_ID_UNAVAILABLE
        }
        if (!isMountAllowed(workspaceRoot.mountId, stepIdentity, trustedMountRoots)) {
            return RootConfinementRejection.CROSS_MOUNT_REJECTED
        }
        if (controlPlane.isProtected(candidateAbsolutePath, stepIdentity)) {
            return RootConfinementRejection.CONTROL_PLANE_PROTECTED
        }
        return null
    }

    fun verifyRegularFileTarget(
        workspaceRoot: RootFdNodeIdentity,
        parentBefore: RootFdNodeIdentity,
        targetBefore: RootFdNodeIdentity,
        parentCurrent: RootFdNodeIdentity,
        targetCurrent: RootFdNodeIdentity,
        candidateAbsolutePath: String,
        controlPlane: RootControlPlaneBoundary,
        expectedInode: Long? = null,
        trustedMountRoots: RootTrustedMountRoots? = null,
    ): RootConfinementRejection? {
        if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(candidateAbsolutePath) || candidateAbsolutePath == "/") {
            return RootConfinementRejection.LOCATOR_INVALID
        }
        if (parentBefore.isSymlink || parentCurrent.isSymlink || targetBefore.isSymlink || targetCurrent.isSymlink) {
            return RootConfinementRejection.SYMLINK_REJECTED
        }
        if (!parentBefore.isDirectory || !parentCurrent.isDirectory) {
            return RootConfinementRejection.NOT_A_DIRECTORY
        }
        if (!targetBefore.isRegularFile || !targetCurrent.isRegularFile) {
            return RootConfinementRejection.SPECIAL_NODE_REJECTED
        }
        if (!workspaceRoot.hasValidKernelIdentity ||
            !targetBefore.hasValidKernelIdentity || !targetCurrent.hasValidKernelIdentity ||
            !parentBefore.hasValidKernelIdentity || !parentCurrent.hasValidKernelIdentity
        ) {
            return RootConfinementRejection.MOUNT_ID_UNAVAILABLE
        }
        if (targetBefore.hardlinkCount != 1L || targetCurrent.hardlinkCount != 1L) {
            return RootConfinementRejection.HARDLINK_REJECTED
        }
        if (!isMountAllowed(workspaceRoot.mountId, targetBefore, trustedMountRoots) ||
            !isMountAllowed(workspaceRoot.mountId, targetCurrent, trustedMountRoots) ||
            !isMountAllowed(workspaceRoot.mountId, parentBefore, trustedMountRoots) ||
            !isMountAllowed(workspaceRoot.mountId, parentCurrent, trustedMountRoots)
        ) {
            return RootConfinementRejection.CROSS_MOUNT_REJECTED
        }
        val parentPath = candidateAbsolutePath.substringBeforeLast('/').ifEmpty { "/" }
        if (controlPlane.isProtected(candidateAbsolutePath, targetBefore) ||
            controlPlane.isProtected(candidateAbsolutePath, targetCurrent) ||
            controlPlane.isProtected(parentPath, parentBefore) ||
            controlPlane.isProtected(parentPath, parentCurrent)
        ) {
            return RootConfinementRejection.CONTROL_PLANE_PROTECTED
        }
        if (!parentBefore.sameKernelObjectAs(parentCurrent) ||
            !targetBefore.sameKernelObjectAs(targetCurrent) ||
            (expectedInode != null && targetCurrent.inode != expectedInode)
        ) {
            return RootConfinementRejection.RENAME_RACE_DETECTED
        }
        return null
    }

    fun verifyReattachLocator(
        locatorBytes: ByteArray?,
        origin: RootWorkspaceOrigin,
        expectedOriginSha256: String,
        reopenedRootPath: String,
        reopenedIdentity: RootFdNodeIdentity,
        controlPlane: RootControlPlaneBoundary,
    ): Pair<RootRecoveryLocatorPayload?, RootConfinementRejection?> {
        if (origin.canonicalSha256() != expectedOriginSha256) {
            return null to RootConfinementRejection.ORIGIN_MISMATCH
        }
        val decoded = RootRecoveryLocatorCodec.decode(locatorBytes)
            ?: return null to RootConfinementRejection.LOCATOR_INVALID
        if (decoded.originSha256 != expectedOriginSha256 ||
            decoded.workspaceId != origin.workspaceId ||
            decoded.workspaceGenerationId != origin.generationId ||
            decoded.authority != origin.authority
        ) {
            return null to RootConfinementRejection.ORIGIN_MISMATCH
        }
        if (decoded.absoluteRootPath != reopenedRootPath) {
            return null to RootConfinementRejection.IDENTITY_MISMATCH
        }
        val rootCheck = verifyWorkspaceRoot(reopenedRootPath, decoded.rootKind, reopenedIdentity, controlPlane)
        if (rootCheck != null) return null to rootCheck
        if (reopenedIdentity.deviceId != decoded.rootDeviceId ||
            reopenedIdentity.inode != decoded.rootInode ||
            reopenedIdentity.mountId != decoded.rootMountId
        ) {
            return null to RootConfinementRejection.IDENTITY_MISMATCH
        }
        return decoded to null
    }

    /**
     * Numeric caller-provided mount IDs never authorize a cross-mount step. A different mount is
     * accepted only when its frozen root identity is confirmed by the service bind path.
     */
    private fun isMountAllowed(
        workspaceMountId: Long,
        candidate: RootFdNodeIdentity,
        trustedMountRoots: RootTrustedMountRoots?,
    ): Boolean =
        candidate.mountId == workspaceMountId ||
            (trustedMountRoots != null && trustedMountRoots.confirms(candidate.mountId, candidate))
}
