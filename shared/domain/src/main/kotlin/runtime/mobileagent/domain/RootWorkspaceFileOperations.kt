// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.security.MessageDigest

/** Stable reasons surfaced through [RootFileOperationResult] without adding sealed branches. */
object RootFileOperationReason {
    const val UNSUPPORTED_WRITE_DOMAIN = "unsupported_uid0_exclusive_write_domain"
    const val WRITE_DOMAIN_RECHECK_FAILED = "write_domain_recheck_failed"

    /**
     * A conditional (expected_version / patch) mutation was requested for a parent directory the
     * target app can rewrite. The capability is refused up front; it is not degraded silently into
     * an unconditional overwrite.
     */
    const val WRITE_DOMAIN_VERSION_UNSUPPORTED = "expected_version_unsupported_in_app_private_domain"
    const val CONTENT_VERSION_UNOBSERVABLE = "content_version_unobservable"
    const val TEMP_SOURCE_IDENTITY_MISMATCH = "temp_source_identity_mismatch"
    const val COMMITTED_CONTENT_MISMATCH = "committed_content_mismatch"
    const val SPECIAL_MODE_BITS = "mode.special_bits"
    const val PATCH_INVALID = "patch_invalid"
    const val PATCH_CONTEXT_MISMATCH = "patch_context_mismatch"
    const val PATCH_AMBIGUOUS = "patch_ambiguous"
    const val PATCH_RESULT_EXCEEDS_BUDGET = "patch_result_exceeds_output_budget"
    const val READ_WINDOW_INVALID = "read_window_invalid"
    const val FD_HANDLE_INVALID = "retained_fd_handle_invalid"
    const val WORKSPACE_ROOT_UNCONFINED = "workspace_root_unconfined"
    const val LIST_CURSOR_INVALID = "list_cursor_invalid"
    const val TARGET_NOT_FOUND = "target_not_found_or_unopenable"
    const val DESTINATION_PARENT_UNCONFINED = "destination_parent_unconfined"
    const val DELETE_UNCONFIRMED = "delete_unconfirmed"
    const val MOVE_UNCONFIRMED = "move_unconfirmed"
    const val READ_FAILED = "read_failed"
}

object RootContentVersionPolicy {
    /** Upper bound for streamed full-content SHA-256 version and streamed patch (512 MiB). */
    const val MAX_CONTENT_BYTES: Long = 512L * 1024 * 1024
    /** Upper bound for legacy single-buffer in-memory reads (8 MiB). */
    const val MAX_IN_MEMORY_CONTENT_BYTES: Long = 8L * 1024 * 1024
    const val MAX_LIST_SCAN_ENTRIES: Int = 4_096
}

data class RootDirectoryEntry(
    val name: String,
    val relativePath: String,
    val entryKind: RootFileEntryKind,
    val sizeBytes: Long,
    val version: String? = null,
)

data class RootDirectoryListing(
    val workspaceId: String,
    val relativePath: String,
    val entries: List<RootDirectoryEntry>,
    val skippedSpecialEntries: Int,
    val truncated: Boolean,
    val nextCursor: String? = null,
)

class RootFileReadResult(
    val workspaceId: String,
    val relativePath: String,
    val offsetBytes: Long,
    val totalSizeBytes: Long,
    val content: ByteArray,
    val version: String,
    val truncated: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is RootFileReadResult &&
        workspaceId == other.workspaceId &&
        relativePath == other.relativePath &&
        offsetBytes == other.offsetBytes &&
        totalSizeBytes == other.totalSizeBytes &&
        content.contentEquals(other.content) &&
        version == other.version &&
        truncated == other.truncated

    override fun hashCode(): Int = listOf(
        workspaceId, relativePath, offsetBytes, totalSizeBytes, version, truncated,
    ).hashCode() * 31 + content.contentHashCode()
}

data class RootDeletedEntry(
    val workspaceId: String,
    val relativePath: String,
    val deletedSnapshot: RootFileMetadataSnapshot,
)

data class RootMovedEntry(
    val workspaceId: String,
    val sourceRelativePath: String,
    val destinationRelativePath: String,
    val snapshot: RootFileMetadataSnapshot,
)

/** Range read observed under one retained target FD. */
data class RootFdRangeRead(
    val bytes: ByteArray,
    val totalSizeBytes: Long,
    val version: String,
    val contentSha256: String?,
)

data class RootDirectoryScanEntry(
    val name: String,
    val entryKind: RootFileEntryKind,
    val sizeBytes: Long,
)

data class RootDirectoryScan @JvmOverloads constructor(
    val relativePath: String,
    val resolvedAbsolutePath: String,
    val directoryIdentity: RootFdNodeIdentity,
    val directoryChain: List<RootFdNodeIdentity>,
    val entries: List<RootDirectoryScanEntry>,
    val skippedSpecialEntries: Int,
    val truncatedByScan: Boolean,
    val ancestorsAboveRoot: List<RootFdNodeIdentity> = emptyList(),
)

object RootMetadataSnapshots {
    fun of(workspaceId: String, relativePath: String, opened: RootOpenedFileTarget): RootFileMetadataSnapshot {
        val node = opened.targetIdentityCurrent
        return RootFileMetadataSnapshot(
            workspaceId = workspaceId,
            relativePath = relativePath,
            entryKind = if (node.isDirectory) RootFileEntryKind.DIRECTORY else RootFileEntryKind.REGULAR_FILE,
            sizeBytes = opened.sizeBytes,
            modeOctal = node.modeOctal,
            ownerUid = node.ownerUid,
            ownerGid = node.ownerGid,
            selinuxLabel = node.selinuxLabel,
            hardlinkCount = node.hardlinkCount,
            deviceId = node.deviceId,
            inode = node.inode,
            mountId = node.mountId,
            version = opened.version,
        )
    }

    fun digest(snapshot: RootFileMetadataSnapshot): String = RootRecoveryLocatorCodec.lengthPrefixedSha256(
        listOf(
            "MAR-ROOT-FILE-METADATA-V1",
            snapshot.workspaceId,
            snapshot.relativePath,
            snapshot.entryKind.name,
            snapshot.sizeBytes.toString(),
            snapshot.modeOctal.toString(),
            snapshot.ownerUid.toString(),
            snapshot.ownerGid.toString(),
            snapshot.selinuxLabel.orEmpty(),
            snapshot.hardlinkCount.toString(),
            snapshot.deviceId.toString(),
            snapshot.inode.toString(),
            snapshot.mountId.toString(),
            snapshot.version,
        ),
    )

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** List/read primitives. Every operation releases its retained FD before returning. */
class RootWorkspaceFileReadOperations(
    private val workspaceRootIdentity: RootFdNodeIdentity,
    private val controlPlane: RootControlPlaneBoundary,
    private val driver: RootPosixFileDriver,
    private val trustedMountRoots: RootTrustedMountRoots?,
    private val workspaceRootRejection: RootConfinementRejection?,
) {
    fun list(args: RootTypedArguments.FileList): RootFileOperationResult<RootDirectoryListing> {
        if (workspaceRootRejection != null) {
            return RootFileOperationResult.Rejected(workspaceRootRejection, RootFileOperationReason.WORKSPACE_ROOT_UNCONFINED)
        }
        val scan = runCatching {
            driver.listDirectory(
                workspaceId = args.workspaceId,
                relativePath = args.relativePath,
                maxEntries = args.maxEntries.coerceIn(1, RootContentVersionPolicy.MAX_LIST_SCAN_ENTRIES),
                cursor = args.cursor,
            )
        }.getOrNull() ?: return RootFileOperationResult.Rejected(
            RootConfinementRejection.SPECIAL_NODE_REJECTED,
            RootFileOperationReason.TARGET_NOT_FOUND,
        )
        val rejection = RootOperationBoundary.verifyAncestorsAboveRoot(scan.ancestorsAboveRoot, controlPlane)
            ?: RootOperationBoundary.verifyDirectoryChain(
                workspaceRootIdentity,
                scan.directoryChain,
                controlPlane,
                trustedMountRoots,
            ) ?: RootFdConfinementPolicy.verifyDirectoryStep(
            workspaceRoot = workspaceRootIdentity,
            stepIdentity = scan.directoryIdentity,
            candidateAbsolutePath = scan.resolvedAbsolutePath,
            controlPlane = controlPlane,
            trustedMountRoots = trustedMountRoots,
        )
        if (rejection != null) {
            return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
        }
        val cursor = args.cursor
        val prefix = if (args.relativePath.isEmpty()) "" else "${args.relativePath}/"
        val ordered = scan.entries
            .filter { cursor == null || it.name > cursor }
            .sortedBy { it.name }
        val window = ordered.take(args.maxEntries)
        val remaining = ordered.size > window.size
        val hasMore = remaining || scan.truncatedByScan
        val entries = window.map { entry ->
            RootDirectoryEntry(
                name = entry.name,
                relativePath = prefix + entry.name,
                entryKind = entry.entryKind,
                sizeBytes = entry.sizeBytes,
            )
        }
        val listing = RootDirectoryListing(
            workspaceId = args.workspaceId,
            relativePath = args.relativePath,
            entries = entries,
            skippedSpecialEntries = scan.skippedSpecialEntries,
            truncated = hasMore,
            nextCursor = if (hasMore) window.lastOrNull()?.name else null,
        )
        return RootFileOperationResult.Succeeded(listing, RootMetadataSnapshots.sha256Hex(
            entries.joinToString("\u0000") { "${it.name}:${it.entryKind.name}:${it.sizeBytes}" }
                .toByteArray(Charsets.UTF_8),
        ))
    }

    fun read(args: RootTypedArguments.FileReadText): RootFileOperationResult<RootFileReadResult> {
        if (workspaceRootRejection != null) {
            return RootFileOperationResult.Rejected(workspaceRootRejection, RootFileOperationReason.WORKSPACE_ROOT_UNCONFINED)
        }
        if (args.maxBytes <= 0) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.LOCATOR_INVALID,
                RootFileOperationReason.READ_WINDOW_INVALID,
            )
        }
        val opened = driver.inspectOpenedTarget(args.workspaceId, args.relativePath, followFinalSymlink = false)
            ?: return RootFileOperationResult.Rejected(
                RootConfinementRejection.SPECIAL_NODE_REJECTED,
                RootFileOperationReason.TARGET_NOT_FOUND,
            )
        try {
            val rejection = RootOperationBoundary.verifyOpenedTarget(
                workspaceRoot = workspaceRootIdentity,
                controlPlane = controlPlane,
                trustedMountRoots = trustedMountRoots,
                opened = opened,
                expectedVersion = null,
                requireRegularFile = true,
            )
            if (rejection != null) return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
            if (opened.sizeBytes > RootContentVersionPolicy.MAX_CONTENT_BYTES) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.CAPACITY_EXCEEDED,
                    RootFileOperationReason.CONTENT_VERSION_UNOBSERVABLE,
                )
            }
            val range = runCatching { driver.readOpenedFd(opened, args.offsetBytes, args.maxBytes) }.getOrNull()
                ?: return RootFileOperationResult.Rejected(
                    RootConfinementRejection.RENAME_RACE_DETECTED,
                    RootFileOperationReason.FD_HANDLE_INVALID,
                )
            if (range.version != opened.version || range.bytes.size > args.maxBytes ||
                range.totalSizeBytes < 0L || args.offsetBytes > range.totalSizeBytes
            ) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.RENAME_RACE_DETECTED,
                    RootFileOperationReason.READ_WINDOW_INVALID,
                )
            }
            return RootFileOperationResult.Succeeded(
                RootFileReadResult(
                    workspaceId = args.workspaceId,
                    relativePath = args.relativePath,
                    offsetBytes = args.offsetBytes,
                    totalSizeBytes = range.totalSizeBytes,
                    content = range.bytes,
                    version = range.version,
                    truncated = args.offsetBytes + range.bytes.size < range.totalSizeBytes,
                ),
                range.contentSha256 ?: RootMetadataSnapshots.sha256Hex(range.bytes),
            )
        } finally {
            driver.releaseOpenedTarget(opened.retainedFdHandle)
        }
    }

    /** Full-content read used by apply_patch before composing an atomic replacement. */
    fun readFull(
        workspaceId: String,
        relativePath: String,
        expectedVersion: String,
    ): RootFileOperationResult<RootFileReadResult> {
        val opened = driver.inspectOpenedTarget(workspaceId, relativePath, followFinalSymlink = false)
            ?: return RootFileOperationResult.Rejected(
                RootConfinementRejection.SPECIAL_NODE_REJECTED,
                RootFileOperationReason.TARGET_NOT_FOUND,
            )
        try {
            val rejection = RootOperationBoundary.verifyOpenedTarget(
                workspaceRoot = workspaceRootIdentity,
                controlPlane = controlPlane,
                trustedMountRoots = trustedMountRoots,
                opened = opened,
                expectedVersion = expectedVersion,
                requireRegularFile = true,
            )
            if (rejection != null) return RootFileOperationResult.Rejected(rejection, "confinement_rejected")
            if (opened.sizeBytes > RootContentVersionPolicy.MAX_IN_MEMORY_CONTENT_BYTES) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.MOUNT_ID_UNAVAILABLE,
                    RootFileOperationReason.CONTENT_VERSION_UNOBSERVABLE,
                )
            }
            val range = runCatching { driver.readOpenedFd(opened, 0L, opened.sizeBytes.toInt()) }.getOrNull()
                ?: return RootFileOperationResult.Rejected(
                    RootConfinementRejection.RENAME_RACE_DETECTED,
                    RootFileOperationReason.FD_HANDLE_INVALID,
                )
            if (range.version != opened.version || range.totalSizeBytes != opened.sizeBytes) {
                return RootFileOperationResult.Rejected(
                    RootConfinementRejection.RENAME_RACE_DETECTED,
                    RootFileOperationReason.WRITE_DOMAIN_RECHECK_FAILED,
                )
            }
            return RootFileOperationResult.Succeeded(
                RootFileReadResult(
                    workspaceId = workspaceId,
                    relativePath = relativePath,
                    offsetBytes = 0L,
                    totalSizeBytes = range.totalSizeBytes,
                    content = range.bytes,
                    version = range.version,
                    truncated = false,
                ),
                range.contentSha256 ?: RootMetadataSnapshots.sha256Hex(range.bytes),
            )
        } finally {
            driver.releaseOpenedTarget(opened.retainedFdHandle)
        }
    }
}
