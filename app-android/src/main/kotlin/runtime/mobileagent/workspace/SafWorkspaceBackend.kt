// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.util.HashSet
import runtime.mobileagent.domain.CapabilityId

internal data class SafCapabilityChild(
    val type: InternalWorkspaceEntryType,
    val flags: Int,
)

internal data class SafCapabilitySnapshot(
    val writable: Boolean,
    val operationCapabilities: Set<CapabilityId>,
)

/**
 * Derives SAF operations from the persisted grant and the provider's current document flags.
 * This is intentionally internal: the shared contract receives only the adapter's canonical
 * capability set, never provider flags or URI details.
 */
internal object SafWorkspaceCapabilityPolicy {
    fun derive(
        readGranted: Boolean,
        writeGranted: Boolean,
        rootFlags: Int,
        children: List<SafCapabilityChild>,
    ): SafCapabilitySnapshot {
        if (!readGranted) return SafCapabilitySnapshot(false, emptySet())

        val capabilities = linkedSetOf(
            InternalWorkspaceCapabilities.ENUMERATE,
            InternalWorkspaceCapabilities.LIST,
            InternalWorkspaceCapabilities.STAT,
        )
        if (children.any {
                it.type == InternalWorkspaceEntryType.FILE &&
                    it.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0
            }
        ) {
            capabilities += InternalWorkspaceCapabilities.READ_TEXT
        }

        val canCreate = writeGranted &&
            rootFlags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        val canDelete = writeGranted && children.any {
            it.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
        }
        val canMove = writeGranted && children.any {
            it.flags and (DocumentsContract.Document.FLAG_SUPPORTS_MOVE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
        }
        if (canCreate) {
            // A writable SAF tree can safely create a new text document even though the SAF
            // contract cannot atomically replace an existing one.  Advertise the typed write
            // operation so model calls with replace=false can reach the backend; write() still
            // rejects existing-file replacement and expected-version compare/create requests.
            capabilities += InternalWorkspaceCapabilities.WRITE_TEXT
            capabilities += InternalWorkspaceCapabilities.CREATE_DIRECTORY
        }
        if (canDelete) capabilities += InternalWorkspaceCapabilities.DELETE
        if (canMove) capabilities += InternalWorkspaceCapabilities.MOVE

        // Writable describes the mutation surface, not an atomic-replacement guarantee.  The
        // descriptor keeps supportsAtomicReplace=false and the backend fails closed for replace.
        return SafCapabilitySnapshot(
            writable = canCreate || canDelete || canMove,
            operationCapabilities = capabilities,
        )
    }
}

/**
 * The bounded SAF read primitive shared by the provider-backed implementation and its tests.
 *
 * It deliberately stops issuing reads as soon as the requested chunk is full.  A
 * [DocumentsProvider] stream is not required to expose an EOF cheaply, so draining it to EOF
 * would turn a bounded read into a full-file read and make every file larger than the read limit
 * fail with [InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED].
 */
internal data class SafWorkspaceReadChunk(
    val bytes: ByteArray,
    val totalBytes: Long?,
    val eof: Boolean,
)

internal fun readSafChunk(
    input: InputStream,
    offset: Long,
    maximum: Int,
    declaredSize: Long? = null,
): SafWorkspaceReadChunk {
    if (maximum < 1) InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED.error()
    if (offset < 0L) InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE.error()
    if (declaredSize != null && (declaredSize < 0L || offset > declaredSize)) {
        InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE.error()
    }

    var remainingOffset = offset
    while (remainingOffset > 0L) {
        val skipped = try {
            input.skip(remainingOffset)
        } catch (_: IOException) {
            InternalWorkspaceErrorCode.IO_ERROR.error()
        }
        if (skipped < 0L) InternalWorkspaceErrorCode.IO_ERROR.error()
        if (skipped > 0L) {
            remainingOffset -= skipped
        } else {
            val one = try {
                input.read()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.IO_ERROR.error()
            }
            if (one < 0) InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE.error()
            remainingOffset--
        }
    }

    // A declared size lets us avoid asking a non-conforming provider for bytes past its own
    // metadata.  The subtraction is safe because offset was checked above.
    val chunkLimit = declaredSize
        ?.minus(offset)
        ?.coerceAtMost(maximum.toLong())
        ?.toInt()
        ?: maximum
    val output = ByteArrayOutputStream(chunkLimit.coerceAtMost(8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remainingBytes = chunkLimit
    while (remainingBytes > 0) {
        val requested = minOf(buffer.size, remainingBytes)
        val count = try {
            input.read(buffer, 0, requested)
        } catch (_: IOException) {
            InternalWorkspaceErrorCode.IO_ERROR.error()
        }
        if (count < 0) break
        if (count == 0) {
            // InputStream implementations should return a positive count for a non-empty
            // request, but making progress on a one-byte read avoids a provider-specific spin.
            val one = try {
                input.read()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.IO_ERROR.error()
            }
            if (one < 0) break
            output.write(one)
            remainingBytes--
            continue
        }
        if (count > requested || count > remainingBytes) {
            InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED.error()
        }
        output.write(buffer, 0, count)
        remainingBytes -= count
    }

    val bytes = output.toByteArray()
    val totalBytes = declaredSize ?: if (bytes.size < maximum) {
        offset.takeIf { it <= Long.MAX_VALUE - bytes.size.toLong() }
            ?.plus(bytes.size.toLong())
    } else {
        null
    }
    val eof = declaredSize?.let { bytes.size.toLong() >= it - offset } ?: (bytes.size < maximum)
    return SafWorkspaceReadChunk(bytes, totalBytes, eof)
}

/**
 * Rebind a provider-returned mutation handle to the user's persisted tree.
 *
 * DocumentsProvider mutation methods are allowed to return an ordinary document URI rather
 * than a tree URI.  The returned URI is still untrusted: accept only the same content provider,
 * extract its opaque document ID through DocumentsContract, and rebuild a tree-scoped URI using
 * the original persisted grant.  No URI path is concatenated or exposed.
 */
internal fun rebindSafMutationDocumentUri(treeUri: Uri, returnedUri: Uri): Uri? = runCatching {
    require(treeUri.scheme == ContentResolver.SCHEME_CONTENT)
    require(returnedUri.scheme == ContentResolver.SCHEME_CONTENT)
    require(returnedUri.authority == treeUri.authority)
    require(returnedUri.query == null && returnedUri.fragment == null)
    val documentId = DocumentsContract.getDocumentId(returnedUri)
    require(documentId.isNotBlank())
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}.getOrNull()

/**
 * Storage Access Framework tree backend.
 *
 * The URI is supplied by a caller that completed ACTION_OPEN_DOCUMENT_TREE and persisted the
 * grant.  This class never starts the picker and never exposes the URI to a tool caller.  Every
 * operation rechecks persisted permissions; a revoked grant therefore cannot be revived by a
 * cached backend instance.
 */
internal class SafWorkspaceBackend(
    context: Context,
    private val treeUri: Uri,
    private val limits: InternalWorkspaceLimits = InternalWorkspaceLimits(),
    private val workspaceId: String = "saf-tree",
    internal val resolverOverride: ContentResolver? = null,
) : InternalWorkspaceBackendApi {
    private val resolver: ContentResolver = resolverOverride ?: context.contentResolver
    private val lock = Any()
    private val cursorStore = InternalWorkspaceCursorStore()

    /**
     * Descriptor state is intentionally re-probed instead of being cached.  Persisted SAF
     * permissions and provider flags can change while an adapter remains registered (for
     * example, after the user revokes a grant in system settings).  A stale writable descriptor
     * would otherwise cause the tooling layer to expose mutation tools that are guaranteed to
     * fail.
     */
    override val descriptor: InternalWorkspaceDescriptor
        get() = descriptorSnapshot()

    private data class DescriptorProbe(
        val enabled: Boolean,
        val readable: Boolean,
        val writable: Boolean,
        val operationCapabilities: Set<CapabilityId>,
    )

    private fun descriptorSnapshot(): InternalWorkspaceDescriptor {
        val probe = runCatching { probeDescriptor() }.getOrElse {
            // Descriptors are model-visible metadata.  If probing itself fails, return a
            // bounded disabled/read-only view and let the operation return the typed error.
            DescriptorProbe(false, false, false, emptySet())
        }
        return InternalWorkspaceDescriptor(
            id = workspaceId,
            displayName = "User-authorized files",
            backendType = InternalWorkspaceBackendType.SAF_TREE,
            readable = probe.readable,
            writable = probe.writable,
            quotaBytes = limits.quotaBytes,
            maxFileBytes = limits.maxFileBytes,
            maxReadBytes = limits.maxReadBytes,
            maxEntries = limits.maxEntries,
            maxDirectoryEntries = limits.maxDirectoryEntries,
            enabled = probe.enabled,
            // SAF providers do not promise atomic replacement for this API.  Existing-file
            // writes, and replacement moves/copies, therefore fail closed as UNSUPPORTED.
            supportsAtomicReplace = false,
            operationCapabilities = probe.operationCapabilities,
        )
    }

    private fun probeDescriptor(): DescriptorProbe {
        val grant = persistedPermission() ?: return DescriptorProbe(false, false, false, emptySet())
        if (!grant.isReadPermission) return DescriptorProbe(false, false, false, emptySet())

        // Query the root and one bounded child listing before advertising any operation.  This
        // proves that the persisted grant still reaches this provider and supplies the flags
        // needed for operation-specific capability decisions without exposing the URI.
        val root = rootChild()
        val children = children(root.uri)
        val capabilitySnapshot = SafWorkspaceCapabilityPolicy.derive(
            readGranted = grant.isReadPermission,
            writeGranted = grant.isWritePermission,
            rootFlags = root.flags,
            children = children.map { SafCapabilityChild(it.type, it.flags) },
        )
        return DescriptorProbe(
            enabled = true,
            readable = true,
            writable = capabilitySnapshot.writable,
            operationCapabilities = capabilitySnapshot.operationCapabilities,
        )
    }

    override fun list(
        relativePath: String,
        maxEntries: Int,
        cursor: String?,
    ): InternalWorkspaceResult<InternalWorkspaceList> = synchronized(lock) {
        guarded {
            if (maxEntries < 1) InternalWorkspaceErrorCode.INVALID_ARGUMENT.error()
            requireGrant(write = false)
            val segments = parse(relativePath, allowRoot = true)
            val directory = resolve(segments)
            val children = children(directory.uri).sortedBy { it.name }
            if (children.size > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            val fingerprint = directoryVersion(directory.uri)
            val start = cursor?.let {
                cursorStore.resolve(it, segments.joinToString("/"), fingerprint)
                    ?: InternalWorkspaceErrorCode.INVALID_ARGUMENT.error()
            } ?: 0
            if (start > children.size) InternalWorkspaceErrorCode.INVALID_ARGUMENT.error()
            val pageSize = minOf(maxEntries, limits.maxDirectoryEntries)
            val end = minOf(start + pageSize, children.size)
            val entries = children.subList(start, end).map { child ->
                val childPath = (segments + child.name).joinToString("/")
                InternalWorkspaceEntry(
                    path = childPath,
                    type = child.type,
                    sizeBytes = child.size,
                    version = metadataVersion(child),
                )
            }
            InternalWorkspaceList(
                path = segments.joinToString("/"),
                entries = entries,
                version = directoryVersion(directory.uri),
                nextCursor = if (end < children.size) {
                    cursorStore.issue(segments.joinToString("/"), fingerprint, end)
                } else null,
            )
        }
    }

    override fun stat(relativePath: String): InternalWorkspaceResult<InternalWorkspaceStat> = synchronized(lock) {
        guarded {
            requireGrant(write = false)
            val segments = parse(relativePath, allowRoot = true)
            val node = resolve(segments)
            if (node.type == InternalWorkspaceEntryType.FILE) {
                val version = fileVersion(node.uri)
                InternalWorkspaceStat(segments.joinToString("/"), node.type, node.size, version)
            } else {
                InternalWorkspaceStat(segments.joinToString("/"), node.type, null, directoryVersion(node.uri))
            }
        }
    }

    override fun read(
        relativePath: String,
        maxBytes: Long,
        offsetBytes: Long,
    ): InternalWorkspaceResult<InternalWorkspaceContent> = synchronized(lock) {
        guarded {
            requireGrant(write = false)
            val segments = parse(relativePath, allowRoot = false)
            if (maxBytes < 1L || maxBytes > limits.maxReadBytes) InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED.error()
            if (offsetBytes < 0L) InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE.error()
            val node = resolve(segments)
            if (node.type != InternalWorkspaceEntryType.FILE) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            if (node.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val declaredSize = node.size
            if (declaredSize != null && offsetBytes > declaredSize) InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE.error()
            val chunk = readChunk(node.uri, offsetBytes, maxBytes.toInt(), declaredSize)
            InternalWorkspaceContent(
                path = segments.joinToString("/"),
                bytes = chunk.bytes,
                version = fileVersion(node.uri),
                offsetBytes = offsetBytes,
                totalBytes = chunk.totalBytes,
                eof = chunk.eof,
            )
        }
    }

    override fun applyPatch(
        relativePath: String,
        patch: String,
        expectedVersion: String?,
        format: InternalWorkspacePatchFormat,
    ): InternalWorkspaceResult<InternalWorkspaceWrite> =
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.UNSUPPORTED))

    override fun write(
        relativePath: String,
        content: ByteArray,
        expectedVersion: String?,
        replaceExisting: Boolean,
    ): InternalWorkspaceResult<InternalWorkspaceWrite> = synchronized(lock) {
        guarded {
            requireGrant(write = true)
            val segments = parse(relativePath, allowRoot = false)
            checkFileSize(content.size.toLong())
            val parent = resolve(segments.dropLast(1))
            if (parent.type != InternalWorkspaceEntryType.DIRECTORY) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            val existing = children(parent.uri).firstOrNull { it.name == segments.last() }
            if (existing != null) {
                if (existing.type != InternalWorkspaceEntryType.FILE) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
                expectVersion(fileVersion(existing.uri), expectedVersion)
                if (!replaceExisting) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
                if (existing.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0) {
                    InternalWorkspaceErrorCode.READ_ONLY.error()
                }
                // DocumentsProvider has no contract for atomic replacement.  Do not truncate an
                // existing document or claim that a provider rename is atomic.
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            expectVersion(null, expectedVersion)
            if (expectedVersion != null) {
                // createDocument has no compare-and-create contract, so an expected absence
                // cannot be made atomic against another writer.
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            if (parent.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val usage = inspectUsage()
            if (usage.entries + 1 > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            if (content.size.toLong() > limits.quotaBytes - usage.bytes) {
                InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
            }

            val createdUri = try {
                DocumentsContract.createDocument(
                    resolver,
                    parent.uri,
                    "text/plain",
                    segments.last(),
                ) ?: InternalWorkspaceErrorCode.UNSUPPORTED.error()
            } catch (_: SecurityException) {
                InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.IO_ERROR.error()
            }
            val safeUri = postMutationUri(createdUri)
            val createdId = DocumentsContract.getDocumentId(safeUri)
            val output = try {
                resolver.openOutputStream(safeUri, "w") ?: InternalWorkspaceErrorCode.UNSUPPORTED.error()
            } catch (_: SecurityException) {
                InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
            }
            var streamClosed = false
            try {
                output.use {
                    it.write(content)
                    it.flush()
                }
                streamClosed = true
            } catch (_: IOException) {
                // The provider may already have created a partial document.  Its outcome is not
                // safely retryable, so report the explicit unknown state.
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            } catch (_: RuntimeException) {
                // A provider exception after create may leave a partial document behind.
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            } finally {
                if (!streamClosed) runCatching { output.close() }
            }
            if (!hasPersistedGrant(write = true)) InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            val created = children(parent.uri).firstOrNull { it.id == createdId }
                ?: InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            if (created.name != segments.last()) InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS.error()
            if (created.type != InternalWorkspaceEntryType.FILE || created.size != null && created.size != content.size.toLong()) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            val version = fileVersion(safeUri)
            InternalWorkspaceWrite(segments.joinToString("/"), content.size.toLong(), true, version)
        }
    }

    override fun createDirectory(
        relativePath: String,
        expectedVersion: String?,
    ): InternalWorkspaceResult<InternalWorkspaceDirectoryChange> = synchronized(lock) {
        guarded {
            requireGrant(write = true)
            val segments = parse(relativePath, allowRoot = false)
            val parent = resolve(segments.dropLast(1))
            if (parent.type != InternalWorkspaceEntryType.DIRECTORY) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            val existing = children(parent.uri).firstOrNull { it.name == segments.last() }
            if (existing != null) {
                if (existing.type != InternalWorkspaceEntryType.DIRECTORY) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
                expectVersion(directoryVersion(existing.uri), expectedVersion)
                return@guarded InternalWorkspaceDirectoryChange(
                    segments.joinToString("/"),
                    created = false,
                    version = directoryVersion(existing.uri),
                )
            }
            expectVersion(null, expectedVersion)
            if (expectedVersion != null) {
                // createDocument has no compare-and-create contract, so an expected absence
                // cannot be made atomic against another writer.
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            if (parent.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val usage = inspectUsage()
            if (usage.entries + 1 > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            val createdUri = try {
                DocumentsContract.createDocument(
                    resolver,
                    parent.uri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segments.last(),
                ) ?: InternalWorkspaceErrorCode.UNSUPPORTED.error()
            } catch (_: SecurityException) {
                InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.IO_ERROR.error()
            }
            val safeUri = postMutationUri(createdUri)
            val createdId = DocumentsContract.getDocumentId(safeUri)
            if (!hasPersistedGrant(write = true)) InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            val created = children(parent.uri).firstOrNull { it.id == createdId }
                ?: InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            if (created.name != segments.last() || created.type != InternalWorkspaceEntryType.DIRECTORY) {
                InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS.error()
            }
            InternalWorkspaceDirectoryChange(segments.joinToString("/"), true, directoryVersion(safeUri))
        }
    }

    override fun delete(relativePath: String, expectedVersion: String?): InternalWorkspaceResult<InternalWorkspaceDelete> = synchronized(lock) {
        guarded {
            requireGrant(write = true)
            val segments = parse(relativePath, allowRoot = true)
            if (segments.isEmpty()) InternalWorkspaceErrorCode.ROOT_OPERATION_FORBIDDEN.error()
            val target = resolve(segments)
            if (target.type == InternalWorkspaceEntryType.DIRECTORY && children(target.uri).isNotEmpty()) {
                InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY.error()
            }
            if (target.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE == 0) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val version = if (target.type == InternalWorkspaceEntryType.FILE) fileVersion(target.uri) else directoryVersion(target.uri)
            expectVersion(version, expectedVersion)
            if (expectedVersion != null) {
                // deleteDocument does not expose a compare-and-delete guarantee.
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val deleted = try {
                DocumentsContract.deleteDocument(resolver, target.uri)
            } catch (_: SecurityException) {
                InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            if (!deleted) InternalWorkspaceErrorCode.UNSUPPORTED.error()
            if (!hasPersistedGrant(write = true)) InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            ensureMissing(segments)
            InternalWorkspaceDelete(segments.joinToString("/"), target.type, true)
        }
    }

    override fun move(
        sourcePath: String,
        destinationPath: String,
        expectedVersion: String?,
        replaceExisting: Boolean,
    ): InternalWorkspaceResult<InternalWorkspaceTransfer> = synchronized(lock) {
        guarded {
            requireGrant(write = true)
            val sourceSegments = parse(sourcePath, allowRoot = false)
            val destinationSegments = parse(destinationPath, allowRoot = false)
            if (isPathPrefix(sourceSegments, destinationSegments.dropLast(1))) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
            val source = resolve(sourceSegments)
            val sourceParent = resolve(sourceSegments.dropLast(1))
            val destinationParent = resolve(destinationSegments.dropLast(1))
            if (destinationParent.type != InternalWorkspaceEntryType.DIRECTORY) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            val destinationExisting = children(destinationParent.uri).firstOrNull { it.name == destinationSegments.last() }
            if (destinationExisting != null && !replaceExisting) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
            if (destinationExisting != null && replaceExisting) InternalWorkspaceErrorCode.UNSUPPORTED.error()
            if (destinationExisting != null && destinationExisting.type == InternalWorkspaceEntryType.DIRECTORY && children(destinationExisting.uri).isNotEmpty()) {
                InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY.error()
            }
            val version = nodeVersion(source)
            expectVersion(version, expectedVersion)
            if (expectedVersion != null) {
                // Provider rename/move has no compare-and-move transaction in the SAF contract.
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val flags = source.flags
            val changesParent = sourceParent.uri != destinationParent.uri
            if (changesParent && flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE == 0) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            if (source.name != destinationSegments.last() &&
                flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0
            ) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            var movedUri = source.uri
            try {
                if (source.name != destinationSegments.last()) {
                    movedUri = postMutationUri(
                        DocumentsContract.renameDocument(resolver, movedUri, destinationSegments.last()),
                    )
                }
                if (changesParent) {
                    movedUri = postMutationUri(
                        DocumentsContract.moveDocument(resolver, movedUri, sourceParent.uri, destinationParent.uri),
                    )
                }
            } catch (_: SecurityException) {
                InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            if (!hasPersistedGrant(write = true)) InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            val moved = resolveAfterMutation(destinationSegments)
            if (DocumentsContract.getDocumentId(moved.uri) != DocumentsContract.getDocumentId(movedUri)) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            if (sourceSegments != destinationSegments) ensureMissing(sourceSegments)
            InternalWorkspaceTransfer(
                sourceSegments.joinToString("/"),
                destinationSegments.joinToString("/"),
                moved.type,
                moved.size,
                nodeVersion(moved),
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
            requireGrant(write = true)
            val sourceSegments = parse(sourcePath, allowRoot = false)
            val destinationSegments = parse(destinationPath, allowRoot = false)
            if (isPathPrefix(sourceSegments, destinationSegments.dropLast(1))) InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE.error()
            val source = resolve(sourceSegments)
            val destinationParent = resolve(destinationSegments.dropLast(1))
            val destinationExisting = children(destinationParent.uri).firstOrNull { it.name == destinationSegments.last() }
            if (destinationExisting != null && !replaceExisting) InternalWorkspaceErrorCode.ENTRY_EXISTS.error()
            if (destinationExisting != null && replaceExisting) InternalWorkspaceErrorCode.UNSUPPORTED.error()
            if (destinationExisting != null && destinationExisting.type == InternalWorkspaceEntryType.DIRECTORY && children(destinationExisting.uri).isNotEmpty()) {
                InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY.error()
            }
            val version = nodeVersion(source)
            expectVersion(version, expectedVersion)
            if (expectedVersion != null) {
                // copyDocument plus optional rename cannot provide an atomic source-version
                // precondition through the SAF contract.
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val sourceUsage = inspectNode(source)
            val usage = inspectUsage()
            val destinationUsage = destinationExisting?.let(::inspectNode) ?: Usage()
            if (usage.entries - destinationUsage.entries + sourceUsage.entries > limits.maxEntries) {
                InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            }
            val retainedBytes = usage.bytes - destinationUsage.bytes
            if (sourceUsage.bytes > limits.quotaBytes - retainedBytes) {
                InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
            }
            if (source.flags and DocumentsContract.Document.FLAG_SUPPORTS_COPY == 0) InternalWorkspaceErrorCode.UNSUPPORTED.error()
            if (destinationParent.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            var copiedUri: Uri
            try {
                copiedUri = postMutationUri(
                    DocumentsContract.copyDocument(resolver, source.uri, destinationParent.uri),
                )
                if (source.name != destinationSegments.last()) {
                    copiedUri = postMutationUri(
                        DocumentsContract.renameDocument(resolver, copiedUri, destinationSegments.last()),
                    )
                }
            } catch (_: SecurityException) {
                InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
            } catch (_: IOException) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            if (!hasPersistedGrant(write = true)) InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            val copied = resolveAfterMutation(destinationSegments)
            if (DocumentsContract.getDocumentId(copied.uri) != DocumentsContract.getDocumentId(copiedUri)) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
            InternalWorkspaceTransfer(
                sourceSegments.joinToString("/"),
                destinationSegments.joinToString("/"),
                copied.type,
                copied.size,
                nodeVersion(copied),
            )
        }
    }

    private data class Child(
        val uri: Uri,
        val id: String,
        val name: String,
        val type: InternalWorkspaceEntryType,
        val size: Long?,
        val flags: Int,
    )

    private data class Usage(val files: Int = 0, val bytes: Long = 0L, val entries: Int = 0)

    private fun parse(raw: String?, allowRoot: Boolean): List<String> =
        WorkspacePathPolicy.parse(raw, allowRoot, limits)

    private fun requireGrant(write: Boolean) {
        val grant = persistedPermission() ?: InternalWorkspaceErrorCode.GRANT_LOST.error()
        if (!grant.isReadPermission) InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
        if (write && !grant.isWritePermission) InternalWorkspaceErrorCode.READ_ONLY.error()
    }

    private fun hasPersistedGrant(write: Boolean): Boolean {
        val grant = persistedPermission() ?: return false
        return grant.isReadPermission && (!write || grant.isWritePermission)
    }

    private fun persistedPermission(): android.content.UriPermission? {
        if (treeUri.scheme != ContentResolver.SCHEME_CONTENT || treeUri.authority.isNullOrBlank()) return null
        return try {
            resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun resolve(segments: List<String>): Child {
        var current = rootChild()
        segments.forEach { segment ->
            if (current.type != InternalWorkspaceEntryType.DIRECTORY) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            current = children(current.uri).firstOrNull { it.name == segment }
                ?: InternalWorkspaceErrorCode.ENTRY_NOT_FOUND.error()
        }
        return current
    }

    private fun rootChild(): Child {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        }
        val rootDocument = try {
            safeDocumentUri(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        }
        val metadata = queryDocument(rootDocument)
        if (metadata.id != rootId) InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS.error()
        val type = if (metadata.type == InternalWorkspaceEntryType.DIRECTORY) InternalWorkspaceEntryType.DIRECTORY else {
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        }
        return metadata.copy(uri = rootDocument, id = rootId, type = type)
    }

    private fun children(parentUri: Uri): List<Child> {
        if (parentUri.authority != treeUri.authority || parentUri.scheme != ContentResolver.SCHEME_CONTENT) {
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        }
        val parentId = try {
            DocumentsContract.getDocumentId(parentUri)
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        }
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.UNSUPPORTED.error()
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val result = ArrayList<Child>()
        val ids = HashSet<String>()
        val names = HashSet<String>()
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0 || flagsIndex < 0) {
                    InternalWorkspaceErrorCode.UNSUPPORTED.error()
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: InternalWorkspaceErrorCode.UNSUPPORTED.error()
                    if (id.isBlank()) InternalWorkspaceErrorCode.UNSUPPORTED.error()
                    val name = cursor.getString(nameIndex) ?: InternalWorkspaceErrorCode.UNSUPPORTED.error()
                    WorkspacePathPolicy.validateProviderName(name, limits)
                    val normalizedName = Normalizer.normalize(name, Normalizer.Form.NFC)
                    if (!ids.add(id) || !names.add(normalizedName)) {
                        InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS.error()
                    }
                    val mime = cursor.getString(mimeIndex)
                    if (mime != null && mime.lowercase().contains("symlink")) InternalWorkspaceErrorCode.SYMLINK_FORBIDDEN.error()
                    val type = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        InternalWorkspaceEntryType.DIRECTORY
                    } else {
                        InternalWorkspaceEntryType.FILE
                    }
                    val size = if (type == InternalWorkspaceEntryType.FILE && sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    } else null
                    val flags = cursor.getInt(flagsIndex)
                    // buildDocumentUriUsingTree is the Android API operation that turns the
                    // provider-returned document ID into the provider's child URI.  No URI path
                    // string is concatenated here, and only these returned child URIs are reused.
                    val childUri = safeDocumentUri(DocumentsContract.buildDocumentUriUsingTree(parentUri, id))
                    result += Child(childUri, id, name, type, size, flags)
                }
            } ?: InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        } catch (failure: InternalWorkspaceFailure) {
            throw failure
        } catch (_: SecurityException) {
            InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.IO_ERROR.error()
        }
        return result
    }

    private fun queryDocument(uri: Uri): Child {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0 || flagsIndex < 0 || !cursor.moveToFirst()) {
                    InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
                }
                val id = cursor.getString(idIndex) ?: InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
                if (id.isBlank()) InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
                val name = cursor.getString(nameIndex) ?: ""
                if (name.isNotEmpty()) WorkspacePathPolicy.validateProviderName(name, limits)
                val mime = cursor.getString(mimeIndex)
                if (mime != null && mime.lowercase().contains("symlink")) InternalWorkspaceErrorCode.SYMLINK_FORBIDDEN.error()
                val type = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) InternalWorkspaceEntryType.DIRECTORY else InternalWorkspaceEntryType.FILE
                val size = if (type == InternalWorkspaceEntryType.FILE && sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { it >= 0L }
                } else null
                return Child(uri, id, name, type, size, cursor.getInt(flagsIndex))
            } ?: InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        } catch (failure: InternalWorkspaceFailure) {
            throw failure
        } catch (_: SecurityException) {
            InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.IO_ERROR.error()
        }
    }

    private fun safeDocumentUri(uri: Uri): Uri {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != treeUri.authority ||
            uri.query != null || uri.fragment != null
        ) {
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        }
        try {
            if (DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(treeUri)) {
                InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
            }
            DocumentsContract.getDocumentId(uri)
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND.error()
        }
        return uri
    }

    /** A provider URI returned after a mutation is untrusted until it is bound to this tree. */
    private fun postMutationUri(uri: Uri?): Uri {
        if (uri == null) InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        return try {
            val treeBound = rebindSafMutationDocumentUri(treeUri, uri)
                ?: InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            safeDocumentUri(treeBound)
        } catch (_: InternalWorkspaceFailure) {
            // The provider may have completed the operation before returning an invalid handle.
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        }
    }

    /** Verify a post-mutation source disappearance without exposing provider/path details. */
    private fun ensureMissing(segments: List<String>) {
        try {
            resolve(segments)
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        } catch (failure: InternalWorkspaceFailure) {
            if (failure.error.code != InternalWorkspaceErrorCode.ENTRY_NOT_FOUND) {
                InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
            }
        } catch (_: RuntimeException) {
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
        }
    }

    private fun resolveAfterMutation(segments: List<String>): Child = try {
        resolve(segments)
    } catch (_: InternalWorkspaceFailure) {
        // A provider may have completed a mutation while returning an unusable/ambiguous view.
        InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
    } catch (_: RuntimeException) {
        InternalWorkspaceErrorCode.UNKNOWN_OUTCOME.error()
    }

    private fun metadataVersion(child: Child): String = InternalWorkspaceVersions.bytes(
        "${child.id}\u0000${child.name}\u0000${child.type.name}\u0000${child.size ?: -1L}\u0000${child.flags}".toByteArray(Charsets.UTF_8),
    )

    private fun nodeVersion(node: Child): String =
        if (node.type == InternalWorkspaceEntryType.FILE) fileVersion(node.uri) else directoryVersion(node.uri)

    /**
     * A metadata-only token is used for SAF stat/list/read and conditional
     * mutation checks. Reading a document just to version it would defeat
     * chunked reads and make a large provider file block the file tool.
     */
    private fun fileVersion(uri: Uri): String = metadataVersion(queryDocument(safeDocumentUri(uri)))

    private fun directoryVersion(uri: Uri): String {
        val digestInput = children(uri).sortedBy { it.name }.joinToString("\n") {
            "${it.id}\u0000${it.name}\u0000${it.type.name}\u0000${it.size ?: -1L}\u0000${it.flags}"
        }
        return InternalWorkspaceVersions.bytes(digestInput.toByteArray(Charsets.UTF_8))
    }

    private fun readChunk(
        uri: Uri,
        offset: Long,
        maximum: Int,
        declaredSize: Long? = null,
    ): SafWorkspaceReadChunk {
        val input = try {
            resolver.openInputStream(uri) ?: InternalWorkspaceErrorCode.IO_ERROR.error()
        } catch (_: SecurityException) {
            InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
        }
        return input.use {
            readSafChunk(it, offset, maximum, declaredSize)
        }
    }

    private fun readBounded(uri: Uri, offset: Long, maximum: Int): ByteArray =
        readChunk(uri, offset, maximum).bytes

    private fun readBounded(uri: Uri, maximum: Int): ByteArray = readBounded(uri, 0L, maximum)

    private fun inspectUsage(): Usage {
        val visited = HashSet<String>()
        fun scan(directory: Uri, depth: Int): Usage {
            if (depth > limits.maxPathDepth) InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED.error()
            val directoryId = DocumentsContract.getDocumentId(directory)
            if (!visited.add(directoryId)) InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS.error()
            var usage = Usage()
            children(directory).forEach { child ->
                usage = usage.copy(entries = usage.entries + 1)
                if (usage.entries > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
                if (child.type == InternalWorkspaceEntryType.DIRECTORY) {
                    val nested = scan(child.uri, depth + 1)
                    if (nested.bytes > limits.quotaBytes - usage.bytes) {
                        InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
                    }
                    usage = usage.copy(
                        files = usage.files + nested.files,
                        bytes = usage.bytes + nested.bytes,
                        entries = usage.entries + nested.entries,
                    )
                    if (usage.entries > limits.maxEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
                } else {
                    val bytes = child.size ?: readBounded(child.uri, maxFileProbeBytes()).size.toLong()
                    if (bytes > limits.maxFileBytes) InternalWorkspaceErrorCode.FILE_TOO_LARGE.error()
                    if (bytes > limits.quotaBytes - usage.bytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
                    usage = usage.copy(files = usage.files + 1, bytes = usage.bytes + bytes)
                    if (usage.bytes > limits.quotaBytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
                }
            }
            return usage
        }
        // A persisted tree grant URI (`.../tree/<id>`) is not itself a document URI on every
        // DocumentsProvider.  Traverse from the verified root document URI so getDocumentId()
        // and child queries use the provider's canonical tree-bound document form.
        return scan(rootChild().uri, 0)
    }

    private fun inspectNode(node: Child): Usage {
        if (node.type == InternalWorkspaceEntryType.FILE) {
            val size = node.size ?: readBounded(node.uri, maxFileProbeBytes()).size.toLong()
            checkFileSize(size)
            return Usage(1, size, 1)
        }
        var result = Usage(entries = 1)
        children(node.uri).forEach { child ->
            val nested = inspectNode(child)
            if (nested.bytes > limits.quotaBytes - result.bytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
            result = result.copy(
                files = result.files + nested.files,
                bytes = result.bytes + nested.bytes,
                entries = result.entries + nested.entries,
            )
        }
        return result
    }

    private fun checkFileSize(size: Long) {
        if (size > limits.maxFileBytes) InternalWorkspaceErrorCode.FILE_TOO_LARGE.error()
    }

    /** Read one byte beyond the per-file limit when a provider does not report a size. */
    private fun maxFileProbeBytes(): Int =
        if (limits.maxFileBytes < Int.MAX_VALUE.toLong()) limits.maxFileBytes.toInt() + 1 else Int.MAX_VALUE

    private fun isPathPrefix(prefix: List<String>, path: List<String>): Boolean =
        prefix.size <= path.size && path.subList(0, prefix.size) == prefix

    private fun <T> guarded(action: () -> T): InternalWorkspaceResult<T> = try {
        InternalWorkspaceResult.Success(action())
    } catch (failure: InternalWorkspaceFailure) {
        InternalWorkspaceResult.Failure(failure.error)
    } catch (_: SecurityException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.PERMISSION_DENIED))
    } catch (_: IOException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.IO_ERROR))
    } catch (_: RuntimeException) {
        InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.IO_ERROR))
    }
}
