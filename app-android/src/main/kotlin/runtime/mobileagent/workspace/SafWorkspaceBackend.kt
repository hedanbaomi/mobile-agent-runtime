// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        if (canCreate) capabilities += InternalWorkspaceCapabilities.CREATE_DIRECTORY
        if (canDelete) capabilities += InternalWorkspaceCapabilities.DELETE
        if (canMove) capabilities += InternalWorkspaceCapabilities.MOVE

        // SAF has no atomic replacement contract.  FILE_WRITE_TEXT is intentionally absent even
        // when a provider exposes FLAG_SUPPORTS_WRITE; existing-file replacement remains UNSUPPORTED.
        return SafCapabilitySnapshot(
            writable = canCreate || canDelete || canMove,
            operationCapabilities = capabilities,
        )
    }
}

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

    override fun list(relativePath: String): InternalWorkspaceResult<InternalWorkspaceList> = synchronized(lock) {
        guarded {
            requireGrant(write = false)
            val segments = parse(relativePath, allowRoot = true)
            val directory = resolve(segments)
            val children = children(directory.uri)
            if (children.size > limits.maxDirectoryEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
            val entries = children.sortedBy { it.name }.map { child ->
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

    override fun read(relativePath: String, maxBytes: Long): InternalWorkspaceResult<InternalWorkspaceContent> = synchronized(lock) {
        guarded {
            requireGrant(write = false)
            val segments = parse(relativePath, allowRoot = false)
            if (maxBytes < 1L || maxBytes > limits.maxReadBytes) InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED.error()
            val node = resolve(segments)
            if (node.type != InternalWorkspaceEntryType.FILE) InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED.error()
            if (node.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                InternalWorkspaceErrorCode.UNSUPPORTED.error()
            }
            val declaredSize = node.size
            if (declaredSize != null && declaredSize > limits.maxFileBytes) InternalWorkspaceErrorCode.FILE_TOO_LARGE.error()
            if (declaredSize != null && declaredSize > maxBytes) InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED.error()
            val bytes = readBounded(node.uri, maxBytes.toInt())
            InternalWorkspaceContent(segments.joinToString("/"), bytes, InternalWorkspaceVersions.bytes(bytes))
        }
    }

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
                    if (size != null && size > limits.maxFileBytes) InternalWorkspaceErrorCode.FILE_TOO_LARGE.error()
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
        if (result.size > limits.maxDirectoryEntries) InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED.error()
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
                if (size != null && size > limits.maxFileBytes) InternalWorkspaceErrorCode.FILE_TOO_LARGE.error()
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
            safeDocumentUri(uri)
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

    private fun fileVersion(uri: Uri): String = InternalWorkspaceVersions.bytes(readBounded(uri, limits.maxFileBytes.toInt()))

    private fun directoryVersion(uri: Uri): String {
        val digestInput = children(uri).sortedBy { it.name }.joinToString("\n") {
            "${it.id}\u0000${it.name}\u0000${it.type.name}\u0000${it.size ?: -1L}\u0000${it.flags}"
        }
        return InternalWorkspaceVersions.bytes(digestInput.toByteArray(Charsets.UTF_8))
    }

    private fun readBounded(uri: Uri, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(maximum.coerceAtMost(8 * 1024))
        val input = try {
            resolver.openInputStream(uri) ?: InternalWorkspaceErrorCode.IO_ERROR.error()
        } catch (_: SecurityException) {
            InternalWorkspaceErrorCode.PERMISSION_DENIED.error()
        }
        input.use {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = try {
                    it.read(buffer)
                } catch (_: IOException) {
                    InternalWorkspaceErrorCode.IO_ERROR.error()
                }
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > maximum) InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED.error()
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

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
                    val bytes = child.size ?: readBounded(child.uri, limits.maxFileBytes.toInt()).size.toLong()
                    if (bytes > limits.maxFileBytes) InternalWorkspaceErrorCode.FILE_TOO_LARGE.error()
                    if (bytes > limits.quotaBytes - usage.bytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
                    usage = usage.copy(files = usage.files + 1, bytes = usage.bytes + bytes)
                    if (usage.bytes > limits.quotaBytes) InternalWorkspaceErrorCode.QUOTA_EXCEEDED.error()
                }
            }
            return usage
        }
        return scan(treeUri, 0)
    }

    private fun inspectNode(node: Child): Usage {
        if (node.type == InternalWorkspaceEntryType.FILE) {
            val size = node.size ?: readBounded(node.uri, limits.maxFileBytes.toInt()).size.toLong()
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
