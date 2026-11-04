// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Frozen, identity-verified mount roots confirmed by the controlled service bind path. */
class RootTrustedMountRoots(identitiesByMountId: Map<Long, RootFdNodeIdentity>, private val allowedMountIds: Set<Long> = emptySet()) {
    private val roots: Map<Long, RootFdNodeIdentity> = identitiesByMountId.filterValues { node ->
        node.isDirectory && !node.isSymlink && node.hasValidKernelIdentity
    }

    fun confirms(mountId: Long, identity: RootFdNodeIdentity): Boolean =
        mountId in allowedMountIds || roots[mountId]?.sameKernelObjectAs(identity) == true

    val mountIds: Set<Long> get() = roots.keys + allowedMountIds

    companion object {
        val NONE: RootTrustedMountRoots = RootTrustedMountRoots(emptyMap())
        /** IDs come from a kernel mount-source snapshot excluding control-plane source aliases. */
        fun directoryTree(mountIds: Set<Long>) = RootTrustedMountRoots(emptyMap(), mountIds.toSet())
    }
}

enum class RootWriteDomainKind {
    UID0_EXCLUSIVE,
    SINGLE_APP_PRIVATE,
    SHARED_DIRECTORY,
    UNPROVEN,
}

/**
 * Proof (or explicit lack of proof) that entry mutations in the target parent directory are
 * confined to either a `UID 0`-exclusive directory chain ([RootWriteDomainKind.UID0_EXCLUSIVE])
 * or a single Android app's `0700` private data directory tree ([RootWriteDomainKind.SINGLE_APP_PRIVATE])
 * whose ancestors above the app root are controlled exclusively by `UID 0` or `UID 1000` (`system`).
 *
 * Pre/post FD-identity checks around `renameat`/`unlinkat` are fail-closed race detectors (converting
 * detected drift into `RENAME_RACE_DETECTED` before commit or `UNKNOWN_OUTCOME` + degraded epoch
 * after commit), never a claim of kernel conditional-atomic-replace (`CAS`) semantics.
 */
data class RootWriteDomainEvidence(
    val kind: RootWriteDomainKind,
    val unprovenReasons: List<String> = emptyList(),
    val chainLength: Int = 0,
    val ownerUid: Int = 0,
) {
    val isUid0Exclusive: Boolean get() = kind == RootWriteDomainKind.UID0_EXCLUSIVE

    val isProvenForMutation: Boolean
        get() = (kind == RootWriteDomainKind.UID0_EXCLUSIVE ||
            kind == RootWriteDomainKind.SINGLE_APP_PRIVATE || kind == RootWriteDomainKind.SHARED_DIRECTORY) && chainLength > 0

    companion object {
        const val REASON_NOT_ASSESSED = "write_domain_not_assessed"
        const val REASON_EMPTY_CHAIN = "directory_chain_empty"
        const val REASON_ROOT_MISMATCH = "directory_chain_root_mismatch"
        const val REASON_IDENTITY_UNAVAILABLE = "directory_identity_unavailable"
        const val REASON_NOT_DIRECTORY = "write_domain_not_directory"
        const val REASON_NOT_SERVICE_UID = "directory_owner_not_uid0"
        const val REASON_MIXED_NON_ROOT_OWNERS = "directory_chain_mixed_non_root_owners"
        const val REASON_GROUP_WRITABLE = "directory_group_writable"
        const val REASON_OTHER_WRITABLE = "directory_other_writable"

        fun unproven(vararg reasons: String): RootWriteDomainEvidence = RootWriteDomainEvidence(
            kind = RootWriteDomainKind.UNPROVEN,
            unprovenReasons = reasons.toList(),
        )

        fun uid0Exclusive(chainLength: Int): RootWriteDomainEvidence = RootWriteDomainEvidence(
            kind = RootWriteDomainKind.UID0_EXCLUSIVE,
            chainLength = chainLength,
            ownerUid = RootWriteDomainPolicy.SERVICE_UID,
        )

        fun singleAppPrivate(chainLength: Int, ownerUid: Int): RootWriteDomainEvidence =
            RootWriteDomainEvidence(
                kind = RootWriteDomainKind.SINGLE_APP_PRIVATE,
                chainLength = chainLength,
                ownerUid = ownerUid,
            )
    }
}

object RootWriteDomainPolicy {
    const val SERVICE_UID = 0
    const val SYSTEM_UID = 1000
    private const val PER_USER_UID_RANGE = 100_000
    private const val FIRST_APP_ID = 10_000
    private const val LAST_APP_ID = 19_999
    private const val CACHE_GID_OFFSET = 10_000
    private const val STICKY_BIT = 0b001_000_000_000
    private const val GROUP_WRITE_BIT = 0b000_010_000
    private const val OTHER_WRITE_BIT = 0b000_000_010

    private val APP_PRIVATE_PATH_REGEX = Regex(
        "^/(?:data/(?:user|user_de)/([0-9]+)/([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)|data/data/([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+))(?:/(.*))?$",
    )

    private data class AppPrivatePathLayout(
        val expectedUserId: Int,
        val appDirCount: Int,
        val validOsAncestorCounts: IntRange,
    )

    fun isAndroidAppUid(uid: Int): Boolean =
        uid >= FIRST_APP_ID && (uid % PER_USER_UID_RANGE) in FIRST_APP_ID..LAST_APP_ID

    private fun parseAppPrivateLayout(resolvedPath: String?): AppPrivatePathLayout? {
        if (resolvedPath.isNullOrBlank()) return null
        val match = APP_PRIVATE_PATH_REGEX.matchEntire(resolvedPath.trimEnd('/').ifEmpty { "/" }) ?: return null
        val isDataData = match.groupValues[3].isNotEmpty()
        val pathUserId = if (isDataData) 0 else match.groupValues[1].toIntOrNull() ?: return null
        val subPath = match.groupValues[4]
        val appDirCount = 1 + subPath.split('/').count { it.isNotEmpty() }
        val validOsAncestors = when {
            isDataData -> 3..3
            pathUserId == 0 -> 3..4
            else -> 4..4
        }
        return AppPrivatePathLayout(pathUserId, appDirCount, validOsAncestors)
    }

    fun isAppPrivateDataPathForUid(resolvedPath: String?, ownerUid: Int): Boolean {
        if (!isAndroidAppUid(ownerUid)) return false
        val layout = parseAppPrivateLayout(resolvedPath) ?: return false
        return layout.expectedUserId == (ownerUid / PER_USER_UID_RANGE)
    }

    /**
     * Directory ownership describes replacement guarantees, not Root's ordinary write permission.
     * The workspace FD is already pinned; writable ancestors above it cannot change that object.
     * Kernel DAC/SELinux and the operation's FD/control-plane checks remain authoritative.
     */
    @JvmOverloads
    fun assess(
        chain: List<RootFdNodeIdentity>,
        serviceUid: Int = SERVICE_UID,
        resolvedTargetParentPath: String? = null,
        workspaceChainStart: Int = 0,
        target: RootFdNodeIdentity? = null,
    ): RootWriteDomainEvidence {
        if (chain.isEmpty() || workspaceChainStart !in chain.indices) {
            return RootWriteDomainEvidence.unproven(RootWriteDomainEvidence.REASON_EMPTY_CHAIN)
        }
        if (chain.any { !it.hasValidKernelIdentity || !it.isDirectory || it.isSymlink }) {
            return RootWriteDomainEvidence.unproven(RootWriteDomainEvidence.REASON_IDENTITY_UNAVAILABLE)
        }
        val scoped = chain.drop(workspaceChainStart)
        if (scoped.all { it.ownerUid == serviceUid && it.modeOctal and (GROUP_WRITE_BIT or OTHER_WRITE_BIT) == 0 }) {
            return if (target == null || target.ownerUid == serviceUid &&
                target.modeOctal and (GROUP_WRITE_BIT or OTHER_WRITE_BIT) == 0
            ) RootWriteDomainEvidence.uid0Exclusive(scoped.size)
            else RootWriteDomainEvidence(RootWriteDomainKind.SHARED_DIRECTORY, chainLength = scoped.size, ownerUid = target.ownerUid)
        }
        val owner = scoped.last().ownerUid
        if (isAppPrivateDataPathForUid(resolvedTargetParentPath, owner) &&
            scoped.all {
                it.ownerUid in setOf(serviceUid, SYSTEM_UID, owner) && it.modeOctal and OTHER_WRITE_BIT == 0 &&
                    (it.modeOctal and GROUP_WRITE_BIT == 0 || it.ownerGid in setOf(it.ownerUid, serviceUid, it.ownerUid + CACHE_GID_OFFSET))
            }
        ) return RootWriteDomainEvidence.singleAppPrivate(scoped.size, owner)
        return RootWriteDomainEvidence(RootWriteDomainKind.SHARED_DIRECTORY, chainLength = scoped.size, ownerUid = owner)
    }

}

object RootOperationBoundary {
    fun verifyWorkspaceRootIdentity(
        workspaceRoot: RootFdNodeIdentity,
        controlPlane: RootControlPlaneBoundary,
    ): RootConfinementRejection? = when {
        workspaceRoot.isSymlink -> RootConfinementRejection.SYMLINK_REJECTED
        !workspaceRoot.isDirectory -> RootConfinementRejection.NOT_A_DIRECTORY
        !workspaceRoot.hasValidKernelIdentity -> RootConfinementRejection.MOUNT_ID_UNAVAILABLE
        controlPlane.isProtected(null, workspaceRoot) -> RootConfinementRejection.CONTROL_PLANE_PROTECTED
        else -> null
    }

    fun verifyDirectoryChain(
        workspaceRoot: RootFdNodeIdentity,
        chain: List<RootFdNodeIdentity>,
        controlPlane: RootControlPlaneBoundary,
        trustedMountRoots: RootTrustedMountRoots?,
    ): RootConfinementRejection? {
        if (chain.isEmpty()) return RootConfinementRejection.IDENTITY_MISMATCH
        if (!chain.first().sameKernelObjectAs(workspaceRoot)) return RootConfinementRejection.IDENTITY_MISMATCH
        chain.forEach { node ->
            val step = RootFdConfinementPolicy.verifyDirectoryStep(
                workspaceRoot = workspaceRoot,
                stepIdentity = node,
                candidateAbsolutePath = null,
                controlPlane = controlPlane,
                trustedMountRoots = trustedMountRoots,
            )
            if (step != null) return step
        }
        return null
    }

    internal fun verifyAncestorsAboveRoot(
        ancestorsAboveRoot: List<RootFdNodeIdentity>,
        controlPlane: RootControlPlaneBoundary,
    ): RootConfinementRejection? = when {
        ancestorsAboveRoot.any { it.isSymlink } -> RootConfinementRejection.SYMLINK_REJECTED
        ancestorsAboveRoot.any { !it.isDirectory } -> RootConfinementRejection.NOT_A_DIRECTORY
        ancestorsAboveRoot.any { !it.hasValidKernelIdentity } -> RootConfinementRejection.MOUNT_ID_UNAVAILABLE
        controlPlane.isChainProtected(ancestorsAboveRoot) -> RootConfinementRejection.CONTROL_PLANE_PROTECTED
        else -> null
    }

    fun verifyOpenedTarget(
        workspaceRoot: RootFdNodeIdentity,
        controlPlane: RootControlPlaneBoundary,
        trustedMountRoots: RootTrustedMountRoots?,
        opened: RootOpenedFileTarget,
        expectedVersion: String?,
        requireRegularFile: Boolean,
        expectedInode: Long? = null,
    ): RootConfinementRejection? {
        if (expectedVersion != null && opened.version != expectedVersion) {
            return RootConfinementRejection.RENAME_RACE_DETECTED
        }
        val ancestorRejection = verifyAncestorsAboveRoot(opened.ancestorsAboveRoot, controlPlane)
        if (ancestorRejection != null) return ancestorRejection
        val chainRejection = verifyDirectoryChain(
            workspaceRoot,
            opened.directoryChain,
            controlPlane,
            trustedMountRoots,
        )
        if (chainRejection != null) return chainRejection
        val targetCur = opened.targetIdentityCurrent
        if (!requireRegularFile && targetCur.isDirectory && !opened.targetIdentityBefore.isSymlink) {
            if (!opened.parentIdentityBefore.sameKernelObjectAs(opened.parentIdentityCurrent) ||
                !opened.targetIdentityBefore.sameKernelObjectAs(targetCur) ||
                (expectedInode != null && targetCur.inode != expectedInode)
            ) {
                return RootConfinementRejection.RENAME_RACE_DETECTED
            }
            return RootFdConfinementPolicy.verifyDirectoryStep(
                workspaceRoot = workspaceRoot,
                stepIdentity = targetCur,
                candidateAbsolutePath = opened.resolvedAbsolutePath,
                controlPlane = controlPlane,
                trustedMountRoots = trustedMountRoots,
            )
        }
        return RootFdConfinementPolicy.verifyRegularFileTarget(
            workspaceRoot = workspaceRoot,
            parentBefore = opened.parentIdentityBefore,
            targetBefore = opened.targetIdentityBefore,
            parentCurrent = opened.parentIdentityCurrent,
            targetCurrent = targetCur,
            candidateAbsolutePath = opened.resolvedAbsolutePath,
            controlPlane = controlPlane,
            expectedInode = expectedInode,
            trustedMountRoots = trustedMountRoots,
        )
    }

    fun verifyOpenedParent(
        workspaceRoot: RootFdNodeIdentity,
        controlPlane: RootControlPlaneBoundary,
        trustedMountRoots: RootTrustedMountRoots?,
        parent: RootOpenedParentTarget,
    ): RootConfinementRejection? {
        if (!RootRecoveryLocatorCodec.isValidAbsoluteRootPath(parent.resolvedTargetAbsolutePath) ||
            parent.resolvedTargetAbsolutePath == "/"
        ) {
            return RootConfinementRejection.LOCATOR_INVALID
        }
        val ancestorRejection = verifyAncestorsAboveRoot(parent.ancestorsAboveRoot, controlPlane)
        if (ancestorRejection != null) return ancestorRejection
        val chainRejection = verifyDirectoryChain(
            workspaceRoot,
            parent.directoryChain,
            controlPlane,
            trustedMountRoots,
        )
        if (chainRejection != null) return chainRejection
        if (!parent.parentIdentityBefore.sameKernelObjectAs(parent.parentIdentityCurrent)) {
            return RootConfinementRejection.RENAME_RACE_DETECTED
        }
        return RootFdConfinementPolicy.verifyDirectoryStep(
            workspaceRoot = workspaceRoot,
            stepIdentity = parent.parentIdentityCurrent,
            candidateAbsolutePath = parent.resolvedParentAbsolutePath,
            controlPlane = controlPlane,
            trustedMountRoots = trustedMountRoots,
        )
    }

    fun requireWriteDomain(
        evidence: RootWriteDomainEvidence,
    ): RootConfinementRejection? =
        if (evidence.isProvenForMutation) null else RootConfinementRejection.MOUNT_ID_UNAVAILABLE
}
