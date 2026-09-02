// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Backend for a directory owned by this application.  All path components are checked before a
 * filesystem lookup, and every existing component is checked again without following links.
 */
internal class InternalWorkspaceBackend(
    root: Path,
    private val limits: InternalWorkspaceLimits = InternalWorkspaceLimits(),
    workspaceId: String = "internal",
    descriptorOverride: InternalWorkspaceDescriptor? = null,
) : InternalWorkspaceBackendApi {
    override val descriptor: InternalWorkspaceDescriptor = descriptorOverride ?: InternalWorkspaceDescriptor(
        id = workspaceId,
        displayName = "Application workspace",
        backendType = InternalWorkspaceBackendType.INTERNAL,
        readable = true,
        writable = true,
        quotaBytes = limits.quotaBytes,
        maxFileBytes = limits.maxFileBytes,
        maxReadBytes = limits.maxReadBytes,
        maxEntries = limits.maxEntries,
        maxDirectoryEntries = limits.maxDirectoryEntries,
        enabled = true,
        supportsAtomicReplace = true,
        operationCapabilities = setOf(
            InternalWorkspaceCapabilities.ENUMERATE,
            InternalWorkspaceCapabilities.LIST,
            InternalWorkspaceCapabilities.STAT,
            InternalWorkspaceCapabilities.READ_TEXT,
            InternalWorkspaceCapabilities.WRITE_TEXT,
            InternalWorkspaceCapabilities.CREATE_DIRECTORY,
            InternalWorkspaceCapabilities.MOVE,
            InternalWorkspaceCapabilities.DELETE,
            InternalWorkspaceCapabilities.APPLY_PATCH,
        ),
    )
    private val lock = Any()
    private val cursorStore = InternalWorkspaceCursorStore()
    private val rootPath: Path = root.toAbsolutePath().normalize()
    // Android may expose an app-private directory through a stable platform-managed ancestor
    // alias (for example, outside this workspace boundary).  Pin the resolved workspace root on
    // first use instead of rejecting every ancestor up to '/'.  The root entry itself and every
    // entry below it are still checked with NOFOLLOW_LINKS before canonical containment checks.
    private var pinnedCanonicalRoot: Path? = null

    override fun list(
        relativePath: String,
        maxEntries: Int,
        cursor: String?,
    ): InternalWorkspaceResult<InternalWorkspaceList> = synchronized(lock) {
        guarded {
            if (maxEntries < 1) InternalWorkspaceErrorCode.INVALID_ARGUMENT.error()
            val segments = parse(relativePath, allowRoot = true)
            if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
                InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
            }
            ensureRoot()
            val directory = resolveExisting(segments)
            requireDirectory(directory)
            val children = safeChildren(directory).sortedBy { it.fileName.toString() }
            if (children.size > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            val fingerprint = directoryVersion(directory)
            val start = cursor?.let {
                cursorStore.resolve(it, segments.joinToString("/"), fingerprint)
                    ?: InternalWorkspaceErrorCode.INVALID_ARGUMENT.error()
            } ?: 0
            if (start > children.size) InternalWorkspaceErrorCode.INVALID_ARGUMENT.error()
            val pageSize = minOf(maxEntries, limits.maxDirectoryEntries)
            val end = minOf(start + pageSize, children.size)
            val names = HashSet<String>()
            val entries = children.subList(start, end).map { child ->
                rejectLink(child)
                val name = safeChildName(child.fileName.toString(), names)
                val childSegments = segments + name
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> InternalWorkspaceEntry(
                        path = childSegments.joinToString("/"),
                        type = InternalWorkspaceEntryType.DIRECTORY,
                        sizeBytes = null,
                        version = directoryVersion(child),
                    )
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = Files.size(child)
                        InternalWorkspaceEntry(
                            path = childSegments.joinToString("/"),
                            type = InternalWorkspaceEntryType.FILE,
                            sizeBytes = size,
                            version = fileVersion(child),
                        )
                    }
                    else -> InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
                }
            }
            InternalWorkspaceList(
                path = segments.joinToString("/"),
                entries = entries,
                version = fingerprint,
                nextCursor = if (end < children.size) {
                    cursorStore.issue(segments.joinToString("/"), fingerprint, end)
                } else null,
            )
        }
    }

    override fun stat(relativePath: String): InternalWorkspaceResult<InternalWorkspaceStat> = synchronized(lock) {
        guarded {
            val segments = parse(relativePath, allowRoot = true)
            ensureRoot()
            val target = resolveExisting(segments)
            when {
                Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> InternalWorkspaceStat(
                    path = segments.joinToString("/"),
                    type = InternalWorkspaceEntryType.DIRECTORY,
                    sizeBytes = null,
                    version = directoryVersion(target),
                )
                Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> {
                    val size = Files.size(target)
                    InternalWorkspaceStat(
                        path = segments.joinToString("/"),
                        type = InternalWorkspaceEntryType.FILE,
                        sizeBytes = size,
                        version = fileVersion(target),
                    )
                }
                else -> InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            }
        }
    }

    override fun read(
        relativePath: String,
        maxBytes: Long,
        offsetBytes: Long,
    ): InternalWorkspaceResult<InternalWorkspaceContent> = synchronized(lock) {
        guarded {
            val segments = parse(relativePath, allowRoot = false)
            if (maxBytes < 1L || maxBytes > limits.maxReadBytes) InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED.error()
            if (offsetBytes < 0L) InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE.error()
            ensureRoot()
            val file = resolveExisting(segments)
            rejectLink(file)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            val size = Files.size(file)
            if (offsetBytes > size) InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE.error()
            val bytes = readBounded(file, offsetBytes, maxBytes.toInt())
            InternalWorkspaceContent(
                path = segments.joinToString("/"),
                bytes = bytes,
                version = fileVersion(file),
                offsetBytes = offsetBytes,
                totalBytes = size,
                eof = bytes.size.toLong() >= size - offsetBytes,
            )
        }
    }

    override fun applyPatch(
        relativePath: String,
        patch: String,
        expectedVersion: String?,
        format: InternalWorkspacePatchFormat,
    ): InternalWorkspaceResult<InternalWorkspaceWrite> = synchronized(lock) {
        guarded {
            val segments = parse(relativePath, allowRoot = false)
            val expected = expectedVersion ?: InternalWorkspaceErrorCode.INVALID_ARGUMENT.error()
            ensureRootForMutation()
            val parent = resolveExisting(segments.dropLast(1))
            requireDirectory(parent)
            val canonicalParent = canonicalWorkspaceDirectory(parent)
            val target = parent.resolve(segments.last())
            rejectLinkIfPresent(target)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
            ) {
                InternalWorkspaceErrorCode.ENTRY_NOT_FOUND.error()
            }
            val currentBytes = readAll(target)
            // The public expected version is the same metadata token returned
            // by stat/read; only the patch mutation's CAS check reads content.
            val currentVersion = fileVersion(target)
            val currentContentVersion = InternalWorkspaceVersions.bytes(currentBytes)
            expectVersion(currentVersion, expected)
            val currentText = when (val decoded = InternalWorkspaceVersions.decode(currentBytes)) {
                is InternalWorkspaceResult.Failure -> decoded.error.code.error()
                is InternalWorkspaceResult.Success -> decoded.value
            }
            val patchedText = applyPatchText(currentText, patch, format)
                ?: InternalWorkspaceErrorCode.INVALID_PATCH.error()
            val patchedBytes = when (val encoded = InternalWorkspaceVersions.text(patchedText)) {
                is InternalWorkspaceResult.Failure -> encoded.error.code.error()
                is InternalWorkspaceResult.Success -> encoded.value
            }
            if (patchedBytes.size.toLong() > limits.quotaBytes) {
                InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
            }
            if (patchedBytes.contentEquals(currentBytes)) {
                return@guarded InternalWorkspaceWrite(
                    path = segments.joinToString("/"),
                    bytes = patchedBytes.size.toLong(),
                    created = false,
                    version = currentVersion,
                )
            }
            val usage = inspectUsage(enforceIndividualFileLimit = false)
            val retainedBytes = usage.bytes - currentBytes.size.toLong()
            if (patchedBytes.size.toLong() > limits.quotaBytes - retainedBytes) {
                InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
            }
            val temporary = parent.resolve(".mar-workspace-write-${UUID.randomUUID()}.tmp")
            try {
                writeAndSync(temporary, patchedBytes)
                val latestBytes = readAll(target)
                val latestVersion = fileVersion(target)
                val latestContentVersion = InternalWorkspaceVersions.bytes(latestBytes)
                if (latestVersion != currentVersion || latestContentVersion != currentContentVersion) {
                    InternalWorkspaceErrorCode.CONFLICT.error()
                }
                atomicReplace(temporary, target, parent, canonicalParent)
                rejectLink(target)
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ||
                    Files.size(target) != patchedBytes.size.toLong()
                ) {
                    InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
                }
                InternalWorkspaceWrite(
                    path = segments.joinToString("/"),
                    bytes = patchedBytes.size.toLong(),
                    created = false,
                    version = fileVersion(target),
                )
            } finally {
                deleteTemporaryTree(temporary)
            }
        }
    }

    override fun write(
        relativePath: String,
        content: ByteArray,
        expectedVersion: String?,
        replaceExisting: Boolean,
    ): InternalWorkspaceResult<InternalWorkspaceWrite> = synchronized(lock) {
        guarded {
            val segments = parse(relativePath, allowRoot = false)
            checkFileSize(content.size.toLong())
            ensureRootForMutation()
            val parent = resolveExisting(segments.dropLast(1))
            requireDirectory(parent)
            val canonicalParent = canonicalWorkspaceDirectory(parent)
            val target = parent.resolve(segments.last())
            rejectLinkIfPresent(target)
            val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            val oldVersion = if (existed) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
                fileVersion(target)
            } else {
                null
            }
            expectVersion(oldVersion, expectedVersion)
            if (existed && !replaceExisting) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
            val usage = inspectUsage(rootPath)
            val oldBytes = if (existed) Files.size(target) else 0L
            if (usage.entries + (if (existed) 0 else 1) > limits.maxEntries) {
                InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            }
            val retainedBytes = usage.bytes - oldBytes
            if (content.size.toLong() > limits.quotaBytes - retainedBytes) {
                InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
            }

            val temporary = parent.resolve(".mar-workspace-write-${UUID.randomUUID()}.tmp")
            try {
                writeAndSync(temporary, content)
                // The lock protects this backend instance.  Re-reading immediately before the
                // replace also fails closed if another process changed the target.
                val currentVersion = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    rejectLink(target)
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.CONFLICT.error()
                    fileVersion(target)
                } else null
                if (currentVersion != oldVersion) InternalWorkspaceErrorCode.CONFLICT.error()
                expectVersion(currentVersion, expectedVersion)
                atomicReplace(temporary, target, parent, canonicalParent)
                rejectLink(target)
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != content.size.toLong()) {
                    // The atomic move already ran; a failed postcondition cannot prove whether a
                    // provider changed the target before the observation.  Report the explicit
                    // uncertain state instead of claiming that the old target survived.
                    InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
                }
                InternalWorkspaceWrite(
                    path = segments.joinToString("/"),
                    bytes = content.size.toLong(),
                    created = !existed,
                    version = fileVersion(target),
                )
            } finally {
                deleteTemporaryTree(temporary)
            }
        }
    }

    override fun createDirectory(
        relativePath: String,
        expectedVersion: String?,
    ): InternalWorkspaceResult<InternalWorkspaceDirectoryChange> = synchronized(lock) {
        guarded {
            val segments = parse(relativePath, allowRoot = false)
            ensureRootForMutation()
            val parent = resolveExisting(segments.dropLast(1))
            requireDirectory(parent)
            val canonicalParent = canonicalWorkspaceDirectory(parent)
            val directory = parent.resolve(segments.last())
            rejectLinkIfPresent(directory)
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
                expectVersion(directoryVersion(directory), expectedVersion)
                return@guarded InternalWorkspaceDirectoryChange(
                    segments.joinToString("/"),
                    created = false,
                    version = directoryVersion(directory),
                )
            }
            expectVersion(null, expectedVersion)
            val usage = inspectUsage(rootPath)
            if (usage.entries + 1 > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            Files.createDirectory(directory)
            verifyStableWorkspaceDirectoryAfterMutation(parent, canonicalParent)
            rejectLink(directory)
            InternalWorkspaceDirectoryChange(segments.joinToString("/"), true, directoryVersion(directory))
        }
    }

    override fun delete(relativePath: String, expectedVersion: String?): InternalWorkspaceResult<InternalWorkspaceDelete> = synchronized(lock) {
        guarded {
            val segments = parse(relativePath, allowRoot = true)
            if (segments.isEmpty()) InternalWorkspaceErrorCode.ROOT_OPERATION_FORBIDDEN.error()
            ensureRoot()
            val target = resolveExisting(segments)
            rejectLink(target)
            val parent = target.parent ?: InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
            val canonicalParent = canonicalWorkspaceDirectory(parent)
            val type = when {
                Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> InternalWorkspaceEntryType.FILE
                Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> {
                    if (safeChildren(target).isNotEmpty()) InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY.error()
                    InternalWorkspaceEntryType.DIRECTORY
                }
                else -> InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            }
            val currentVersion = if (type == InternalWorkspaceEntryType.FILE) fileVersion(target) else directoryVersion(target)
            expectVersion(currentVersion, expectedVersion)
            rejectLink(target)
            if (type == InternalWorkspaceEntryType.DIRECTORY && safeChildren(target).isNotEmpty()) {
                InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY.error()
            }
            if (!Files.deleteIfExists(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                InternalWorkspaceErrorCode.IO_ERROR.error()
            }
            verifyStableWorkspaceDirectoryAfterMutation(parent, canonicalParent)
            InternalWorkspaceDelete(segments.joinToString("/"), type, true)
        }
    }

    override fun move(
        sourcePath: String,
        destinationPath: String,
        expectedVersion: String?,
        replaceExisting: Boolean,
    ): InternalWorkspaceResult<InternalWorkspaceTransfer> = synchronized(lock) {
        guarded {
            val sourceSegments = parse(sourcePath, allowRoot = false)
            val destinationSegments = parse(destinationPath, allowRoot = false)
            ensureRoot()
            val source = resolveExisting(sourceSegments)
            val destinationParent = resolveExisting(destinationSegments.dropLast(1))
            requireDirectory(destinationParent)
            rejectLink(source)
            val sourceParent = source.parent ?: InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
            val canonicalSourceParent = canonicalWorkspaceDirectory(sourceParent)
            val canonicalDestinationParent = canonicalWorkspaceDirectory(destinationParent)
            val type = nodeType(source)
            if (type == InternalWorkspaceEntryType.DIRECTORY && destinationParent.normalize().startsWith(source.normalize())) {
                InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
            }
            val version = nodeVersion(source, type)
            expectVersion(version, expectedVersion)
            val destination = destinationParent.resolve(destinationSegments.last())
            rejectLinkIfPresent(destination)
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!replaceExisting) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
                if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) && safeChildren(destination).isNotEmpty()) {
                    InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY.error()
                }
            }
            val currentVersion = nodeVersion(resolveExisting(sourceSegments), type)
            if (currentVersion != version) InternalWorkspaceErrorCode.CONFLICT.error()
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                verifyStableWorkspaceDirectoryAfterMutation(destinationParent, canonicalDestinationParent)
                verifyStableWorkspaceDirectoryAfterMutation(sourceParent, canonicalSourceParent)
                forceDirectory(destinationParent)
                if (sourceParent != destinationParent) forceDirectory(sourceParent)
            } catch (_: AtomicMoveNotSupportedException) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            } catch (_: UnsupportedOperationException) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            } catch (_: SecurityException) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            rejectLink(destination)
            InternalWorkspaceTransfer(
                sourceSegments.joinToString("/"),
                destinationSegments.joinToString("/"),
                type,
                if (type == InternalWorkspaceEntryType.FILE) Files.size(destination) else null,
                nodeVersion(destination, type),
            )
        }
    }

    override fun copy(
        sourcePath: String,
        destinationPath: String,
        expectedVersion: String?,
        replaceExisting: Boolean,
    ): InternalWorkspaceResult<InternalWorkspaceTransfer> = synchronized(lock) {
        guarded {
            val sourceSegments = parse(sourcePath, allowRoot = false)
            val destinationSegments = parse(destinationPath, allowRoot = false)
            ensureRoot()
            val source = resolveExisting(sourceSegments)
            val destinationParent = resolveExisting(destinationSegments.dropLast(1))
            requireDirectory(destinationParent)
            rejectLink(source)
            val canonicalDestinationParent = canonicalWorkspaceDirectory(destinationParent)
            val type = nodeType(source)
            if (type == InternalWorkspaceEntryType.DIRECTORY && destinationParent.normalize().startsWith(source.normalize())) {
                InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
            }
            val version = nodeVersion(source, type)
            expectVersion(version, expectedVersion)
            val destination = destinationParent.resolve(destinationSegments.last())
            rejectLinkIfPresent(destination)
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!replaceExisting) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
                if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) && safeChildren(destination).isNotEmpty()) {
                    InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY.error()
                }
            }
            val sourceUsage = inspectNode(source)
            val destinationUsage = if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) inspectNode(destination) else Usage()
            val usage = inspectUsage(rootPath)
            val newFiles = usage.files - destinationUsage.files + sourceUsage.files
            val retainedBytes = usage.bytes - destinationUsage.bytes
            val newEntries = usage.entries - destinationUsage.entries + sourceUsage.entries
            if (newFiles > limits.maxEntries || newEntries > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            if (sourceUsage.bytes > limits.quotaBytes - retainedBytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
            val temporary = destinationParent.resolve(".mar-workspace-copy-${UUID.randomUUID()}.tmp")
            try {
                copyNode(source, temporary)
                forceDirectory(destinationParent)
                val currentVersion = nodeVersion(resolveExisting(sourceSegments), type)
                if (currentVersion != version) InternalWorkspaceErrorCode.CONFLICT.error()
                atomicReplace(temporary, destination, destinationParent, canonicalDestinationParent)
                rejectLink(destination)
                InternalWorkspaceTransfer(
                    sourceSegments.joinToString("/"),
                    destinationSegments.joinToString("/"),
                    type,
                    if (type == InternalWorkspaceEntryType.FILE) Files.size(destination) else null,
                    nodeVersion(destination, type),
                )
            } finally {
                deleteTemporaryTree(temporary)
            }
        }
    }

    private fun parse(raw: String?, allowRoot: Boolean): List<String> =
        WorkspacePathPolicy.parse(raw, allowRoot, limits)

    private fun ensureRoot() {
        rejectLinkIfPresent(rootPath)
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        pinOrValidateCanonicalRoot()
        rejectLink(rootPath)
        // A process interruption can happen after the temporary file has been fsynced but before
        // the atomic rename.  Sweep only our exact, UUID-shaped names before exposing a listing
        // or attempting another mutation; a stale implementation-owned artifact must never be
        // mistaken for user content or silently left addressable.
        cleanupTemporaryEntries(rootPath)
    }

    private fun ensureRootForMutation() {
        rejectLinkIfPresent(rootPath)
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            val parent = rootPath.parent ?: InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
            if (!Files.isDirectory(parent)) {
                InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
            }
            // The parent is outside the workspace boundary and may itself be reached through a
            // platform-managed alias.  Capture its canonical identity before creating anything,
            // then re-check it after the mutation.  A changed parent means we cannot prove where
            // the new directory landed, so the result is explicitly uncertain.
            val canonicalParentBefore = parent.toRealPath()
            Files.createDirectory(rootPath)
            validateCreatedRoot(parent, canonicalParentBefore)
        }
        ensureRoot()
    }

    private fun validateCreatedRoot(parent: Path, canonicalParentBefore: Path) {
        try {
            rejectLinkIfPresent(rootPath)
            if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            val canonicalParentAfter = parent.toRealPath()
            val canonicalRootAfter = rootPath.toRealPath()
            if (canonicalParentAfter != canonicalParentBefore || canonicalRootAfter.parent != canonicalParentAfter) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            pinCanonicalRoot(canonicalRootAfter, canonicalParentAfter)
        } catch (failure: InternalWorkspaceFailure) {
            if (failure.error.code == InternalWorkspaceErrorCode.UNKNOWN_OUTCOME) throw failure
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: IOException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: SecurityException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        }
    }

    private fun pinOrValidateCanonicalRoot(): Path {
        rejectLinkIfPresent(rootPath)
        val parent = rootPath.parent ?: InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        val canonicalParent = parent.toRealPath()
        val canonicalRoot = rootPath.toRealPath()
        pinCanonicalRoot(canonicalRoot, canonicalParent)
        return canonicalRoot
    }

    private fun pinCanonicalRoot(canonicalRoot: Path, canonicalParent: Path) {
        if (canonicalRoot.parent != canonicalParent) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
        val pinned = pinnedCanonicalRoot
        if (pinned != null && pinned != canonicalRoot) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
        pinnedCanonicalRoot = canonicalRoot
    }

    private fun resolveExisting(segments: List<String>): Path {
        var current = rootPath
        rejectLink(current)
        segments.forEach { segment ->
            requireDirectory(current)
            val names = HashSet<String>()
            val children = safeChildren(current)
            children.forEach { candidate ->
                rejectLink(candidate)
                safeChildName(candidate.fileName.toString(), names)
            }
            val child = children.firstOrNull { candidate -> candidate.fileName.toString() == segment }
                ?: InternalWorkspaceErrorCode.ENTRY_NOT_FOUND.error()
            current = child
            if (!current.normalize().startsWith(rootPath)) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
        }
        val canonicalRoot = pinnedCanonicalRoot ?: pinOrValidateCanonicalRoot()
        val canonical = current.toRealPath()
        if (!canonical.startsWith(canonicalRoot)) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
        return current
    }

    private fun requireDirectory(path: Path) {
        rejectLink(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
    }

    private fun safeChildren(directory: Path): List<Path> {
        requireDirectory(directory)
        val children = ArrayList<Path>()
        Files.newDirectoryStream(directory).use { stream: DirectoryStream<Path> ->
            stream.forEach(children::add)
        }
        return children
    }

    private fun safeChildName(name: String, names: MutableSet<String>): String {
        WorkspacePathPolicy.validateProviderName(name, limits)
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        if (!names.add(normalized)) InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS.error()
        return name
    }

    private fun rejectLinkIfPresent(path: Path) {
        if (Files.isSymbolicLink(path)) InternalWorkspaceErrorCode.SYMLINK_FORBIDDEN.error()
    }

    private fun rejectLink(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(rootPath)) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
        // Only links at or below the workspace root are forbidden.  Re-check every lexical
        // component so a directory swapped to a link after an earlier resolution is not accepted,
        // while Android's platform-managed ancestors outside root remain outside this policy.
        var component = rootPath
        rejectLinkIfPresent(component)
        rootPath.relativize(normalized).forEach { name ->
            component = component.resolve(name)
            rejectLinkIfPresent(component)
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            val root = pinnedCanonicalRoot ?: pinOrValidateCanonicalRoot()
            val actual = normalized.toRealPath()
            if (!actual.startsWith(root)) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
        }
    }

    private fun canonicalWorkspaceDirectory(path: Path): Path {
        rejectLink(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
        }
        return path.toRealPath()
    }

    private fun verifyStableWorkspaceDirectoryAfterMutation(path: Path, expectedCanonical: Path) {
        try {
            if (canonicalWorkspaceDirectory(path) != expectedCanonical) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
        } catch (failure: InternalWorkspaceFailure) {
            if (failure.error.code == InternalWorkspaceErrorCode.UNKNOWN_OUTCOME) throw failure
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: IOException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: SecurityException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        }
    }

    private data class Usage(val files: Int = 0, val bytes: Long = 0L, val entries: Int = 0)

    private fun inspectUsage(root: Path = rootPath, enforceIndividualFileLimit: Boolean = true): Usage {
        requireDirectory(root)
        var files = 0
        var bytes = 0L
        var entries = 0
        fun visit(directory: Path, depth: Int) {
            if (depth > limits.maxPathDepth) InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED.error()
            val children = safeChildren(directory)
            val names = HashSet<String>()
            children.forEach { child ->
                rejectLink(child)
                safeChildName(child.fileName.toString(), names)
                entries++
                if (entries > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> visit(child, depth + 1)
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = Files.size(child)
                        if (enforceIndividualFileLimit) checkFileSize(size)
                        files++
                        if (size > limits.quotaBytes - bytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
                        bytes += size
                    }
                    else -> InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
                }
            }
        }
        visit(root, 0)
        return Usage(files, bytes, entries)
    }

    private fun inspectNode(node: Path): Usage {
        rejectLink(node)
        if (Files.isRegularFile(node, LinkOption.NOFOLLOW_LINKS)) {
            val size = Files.size(node)
            return Usage(files = 1, bytes = size, entries = 1)
        }
        if (!Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
        var total = Usage(entries = 1)
        fun visit(directory: Path, depth: Int) {
            if (depth > limits.maxPathDepth) InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED.error()
            val names = HashSet<String>()
            val children = safeChildren(directory)
            children.forEach { child ->
                rejectLink(child)
                safeChildName(child.fileName.toString(), names)
                total = total.copy(entries = total.entries + 1)
                when {
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> {
                        visit(child, depth + 1)
                    }
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> {
                        val size = Files.size(child)
                        if (size > limits.quotaBytes - total.bytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
                        total = total.copy(files = total.files + 1, bytes = total.bytes + size)
                    }
                    else -> InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
                }
            }
        }
        visit(node, 0)
        return total
    }

    private fun checkFileSize(size: Long) {
        if (size > limits.maxFileBytes) InternalWorkspaceErrorCode.FILE_TOO_LARGE.error()
    }

    private fun readAll(file: Path): ByteArray {
        val size = Files.size(file)
        if (size > limits.quotaBytes || size > Int.MAX_VALUE.toLong()) {
            InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
        }
        return readBounded(file, 0L, size.toInt())
    }

    /** Apply a checked, line-oriented patch without accepting arbitrary commands or paths. */
    private fun applyPatchText(
        current: String,
        patch: String,
        format: InternalWorkspacePatchFormat,
    ): String? = when (format) {
        InternalWorkspacePatchFormat.REPLACE -> patch.takeIf { !it.contains('\u0000') }
        InternalWorkspacePatchFormat.UNIFIED_DIFF -> applyUnifiedDiff(current, patch)
    }

    private fun applyUnifiedDiff(current: String, patch: String): String? {
        val source = current.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
        val diff = patch.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
        if (diff.isEmpty()) return null
        var index = 0
        if (diff.getOrNull(0)?.startsWith("--- ") == true) {
            if (diff.getOrNull(1)?.startsWith("+++ ") != true) return null
            index = 2
        }
        val output = ArrayList<String>(source.size)
        var sourceIndex = 0
        var hunkCount = 0
        val hunkPattern = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
        while (index < diff.size) {
            if (diff[index].isEmpty() && index == diff.lastIndex) break
            val header = hunkPattern.matchEntire(diff[index]) ?: return null
            index++
            val oldStart = header.groupValues[1].toIntOrNull() ?: return null
            val oldCount = header.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            val newCount = header.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            if (oldStart < 0 || oldCount < 0 || newCount < 0) return null
            val targetIndex = if (oldStart == 0) 0 else oldStart - 1
            if (targetIndex < sourceIndex || targetIndex > source.size) return null
            while (sourceIndex < targetIndex) output += source[sourceIndex++]
            var consumedOld = 0
            var consumedNew = 0
            while (index < diff.size && !diff[index].startsWith("@@ ")) {
                val line = diff[index++]
                // Kotlin's split retains the final empty element for a patch
                // that ends with a newline. That delimiter is not a diff
                // record; an actual empty source line is represented by a
                // leading context/add/delete marker and is handled below.
                if (line.isEmpty() && index == diff.size) break
                if (line == "\\ No newline at end of file") continue
                if (line.isEmpty()) return null
                when (line[0]) {
                    ' ' -> {
                        if (consumedOld >= oldCount || sourceIndex >= source.size || source[sourceIndex] != line.substring(1)) return null
                        output += source[sourceIndex++]
                        consumedOld++
                        consumedNew++
                    }
                    '-' -> {
                        if (consumedOld >= oldCount || sourceIndex >= source.size || source[sourceIndex] != line.substring(1)) return null
                        sourceIndex++
                        consumedOld++
                    }
                    '+' -> {
                        if (consumedNew >= newCount) return null
                        val added = line.substring(1)
                        if (added.contains('\u0000')) return null
                        output += added
                        consumedNew++
                    }
                    else -> return null
                }
            }
            if (consumedOld != oldCount || consumedNew != newCount) return null
            hunkCount++
        }
        if (hunkCount == 0) return null
        while (sourceIndex < source.size) output += source[sourceIndex++]
        return output.joinToString("\n")
    }

    private fun nodeType(node: Path): InternalWorkspaceEntryType = when {
        Files.isRegularFile(node, LinkOption.NOFOLLOW_LINKS) -> InternalWorkspaceEntryType.FILE
        Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS) -> InternalWorkspaceEntryType.DIRECTORY
        else -> InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
    }

    private fun nodeVersion(node: Path, type: InternalWorkspaceEntryType): String =
        if (type == InternalWorkspaceEntryType.FILE) fileVersion(node) else directoryVersion(node)

    /**
     * Metadata-only version for list/stat/read. A file tool must not read an
     * entire large file merely to enumerate or stat it. The opaque digest
     * keeps platform file keys and timestamps out of the model contract.
     */
    private fun fileVersion(file: Path): String {
        val attributes = Files.readAttributes(
            file,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return InternalWorkspaceVersions.bytes(
            buildString {
                append(attributes.size()).append('\u0000')
                append(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)).append('\u0000')
                append(attributes.creationTime().to(TimeUnit.NANOSECONDS)).append('\u0000')
                append(attributes.fileKey()?.toString().orEmpty())
            }.toByteArray(Charsets.UTF_8),
        )
    }

    private fun directoryVersion(directory: Path): String {
        val digest = InternalWorkspaceVersions.digest()
        val children = safeChildren(directory).sortedBy { it.fileName.toString() }
        if (children.size > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
        val names = HashSet<String>()
        children.forEach { child ->
            rejectLink(child)
            val name = safeChildName(child.fileName.toString(), names)
            val type = nodeType(child)
            val size = if (type == InternalWorkspaceEntryType.FILE) Files.size(child) else -1L
            // A directory version is an optimistic-concurrency token for the complete
            // subtree, not merely for its immediate metadata.  Including the child version
            // means a same-sized file edit and a nested-directory edit both invalidate a
            // previously observed parent version.  Names and types remain part of the input
            // so a rename/type replacement cannot collide with a content-only change.
            val childVersion = if (type == InternalWorkspaceEntryType.FILE) {
                directoryFileVersion(child)
            } else {
                directoryVersion(child)
            }
            digest.update("$name\u0000${type.name}\u0000$size\u0000$childVersion\u0000".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().toHex()
    }

    /**
     * Use a bounded content fingerprint for files that fit in one model read envelope.  File
     * metadata remains part of the token for larger files so listing/stat never has to read a
     * large file body merely to describe it, while same-sized edits to ordinary source files
     * remain visible even on filesystems with coarse timestamp resolution.
     */
    private fun directoryFileVersion(file: Path): String {
        val metadataVersion = fileVersion(file)
        val size = Files.size(file)
        if (size > limits.maxReadBytes) return metadataVersion
        val contentVersion = InternalWorkspaceVersions.bytes(readBounded(file, 0L, size.toInt()))
        return "$metadataVersion\u0000$contentVersion"
    }

    private fun readBounded(file: Path, offset: Long, maximum: Int): ByteArray {
        val available = (Files.size(file) - offset).coerceAtLeast(0L)
        val target = minOf(available, maximum.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val output = ByteArrayOutputStream(target)
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(minOf(8 * 1024, maxOf(1, target)))
            var remaining = target
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity(), remaining))
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer.array(), 0, count)
                remaining -= count
            }
        }
        return output.toByteArray()
    }

    private fun writeAndSync(file: Path, content: ByteArray) {
        Files.newByteChannel(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) channel.write(buffer)
            if (channel is FileChannel) channel.force(true) else InternalWorkspaceErrorCode.UNSUPPORTED.error()
        }
        if (Files.size(file) != content.size.toLong()) InternalWorkspaceErrorCode.IO_ERROR.error()
    }

    private fun atomicReplace(
        temporary: Path,
        target: Path,
        parent: Path,
        expectedCanonicalParent: Path,
    ) {
        try {
            // The temporary node has already been created.  If its parent changed before the
            // rename, the exact mutation location is no longer provable and must not be retried.
            verifyStableWorkspaceDirectoryAfterMutation(parent, expectedCanonicalParent)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            verifyStableWorkspaceDirectoryAfterMutation(parent, expectedCanonicalParent)
            forceDirectory(parent)
        } catch (_: AtomicMoveNotSupportedException) {
            InternalWorkspaceErrorCode.UNSUPPORTED.error()
        } catch (_: UnsupportedOperationException) {
            InternalWorkspaceErrorCode.UNSUPPORTED.error()
        } catch (_: SecurityException) {
            // The move may have completed before a directory-sync permission failure surfaced;
            // never turn that uncertain state into a retryable ordinary permission error.
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: IOException) {
            // An atomic move can report an I/O failure after the provider has changed the target.
            // The caller must not be told that the old state is still authoritative.
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        }
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Some Android filesystem providers do not permit opening a directory as a channel;
            // the file itself is still fsynced, while unsupported atomic moves fail above.
        } catch (_: UnsupportedOperationException) {
            // Same rationale as the IOException branch.
        }
    }

    private fun copyNode(source: Path, target: Path) {
        rejectLink(source)
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
            FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
            return
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
        Files.createDirectory(target)
        val names = HashSet<String>()
        safeChildren(source).forEach { child ->
            rejectLink(child)
            val name = safeChildName(child.fileName.toString(), names)
            copyNode(child, target.resolve(name))
        }
        // File contents are forced above; force the copied directory metadata as well when the
        // filesystem exposes directory channels.  Unsupported directory fsync is deliberately
        // best effort, matching atomicReplace's platform-permits contract.
        forceDirectory(target)
    }

    private fun cleanupTemporaryEntries(directory: Path, depth: Int = 0) {
        if (depth > limits.maxPathDepth) InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED.error()
        val children = safeChildren(directory)
        val names = HashSet<String>()
        children.forEach { child ->
            rejectLink(child)
            val name = safeChildName(child.fileName.toString(), names)
            if (TEMPORARY_NAME.matches(name)) {
                deleteTemporaryTree(child)
            } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                cleanupTemporaryEntries(child, depth + 1)
            }
        }
    }

    private fun deleteTemporaryTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            safeChildren(path).forEach(::deleteTemporaryTree)
        }
        try {
            Files.deleteIfExists(path)
            // A provider/filesystem can report a successful delete while the entry is still
            // visible.  Do not hide that condition: callers must not receive a successful
            // mutation while an implementation-owned temporary tree remains addressable.
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
        } catch (_: IOException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (_: SecurityException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        }
    }

    private fun <T> guarded(action: () -> T): InternalWorkspaceResult<T> = try {
        InternalWorkspaceResult.Success(action())
    } catch (failure: InternalWorkspaceFailure) {
        InternalWorkspaceResult.Failure(failure.error)
    } catch (_: SecurityException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.PERMISSION_DENIED))
    } catch (_: AtomicMoveNotSupportedException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.UNSUPPORTED))
    } catch (_: IOException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.IO_ERROR))
    } catch (_: RuntimeException) {
        // Never pass exception text (which may contain a path) to the model.
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.IO_ERROR))
    }

    private companion object {
        // UUID.randomUUID().toString() is canonical lower-case hexadecimal plus four hyphens.
        // Restrict cleanup to this implementation-owned naming scheme so similarly named user
        // files are not treated as temporary state by accident.
        val TEMPORARY_NAME = Regex("\\.mar-workspace-(?:write|copy)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
