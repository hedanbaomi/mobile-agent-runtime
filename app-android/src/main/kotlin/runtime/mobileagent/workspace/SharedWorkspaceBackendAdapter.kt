// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.content.Context
import android.net.Uri
import java.lang.Long.parseUnsignedLong
import java.nio.file.Path
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.WorkspaceBackendType as DomainWorkspaceBackendType
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceBackend as SharedWorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor as SharedWorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceEntry as SharedWorkspaceEntry
import runtime.mobileagent.skills.tooling.WorkspaceEntryType as SharedWorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspacePatchFormat
import runtime.mobileagent.skills.tooling.WorkspaceResult as SharedWorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceText
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.tooling.WorkspaceCopyBackend
import runtime.mobileagent.tooling.WorkspaceCopyRequest

/**
 * Thin bridge from the Android implementation to the shared skills-api contract.
 *
 * The implementation DTOs remain internal to this module.  In particular, URI references,
 * provider handles, byte buffers and hash strings never cross this adapter.  Shared versions are
 * the signed 64-bit projection of the implementation SHA-256; the full hash is retained inside
 * the backend for the second optimistic-concurrency check.
 */
class SharedWorkspaceBackendAdapter internal constructor(
    private val backend: InternalWorkspaceBackendApi,
) : SharedWorkspaceBackend, WorkspaceCopyBackend {
    /**
     * Capability metadata for the Android container.  SAF deliberately reports false; callers
     * must not advertise an atomic replace option solely because [WorkspaceBackend] is writable.
     */
    val supportsAtomicReplace: Boolean
        get() = backend.descriptor.supportsAtomicReplace

    override val descriptor: SharedWorkspaceDescriptor
        get() = backend.descriptor.toSharedDescriptor()

    /** Capabilities are backend-owned and may change after a SAF grant/provider revalidation. */
    override val capabilities: Set<CapabilityId>
        get() = backend.descriptor.operationCapabilities

    override suspend fun list(request: WorkspaceListRequest): SharedWorkspaceResult<WorkspaceListing> {
        if (request.workspaceId != backend.descriptor.id || request.maxEntries < 1) {
            return failure(ToolErrorCode.INVALID_REQUEST)
        }
        return when (val result = backend.list(
            relativePath = request.relativePath ?: "",
            maxEntries = request.maxEntries,
            cursor = request.cursor,
        )) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> {
                val entries = result.value.entries
                    .take(request.maxEntries)
                    .map(::toSharedEntry)
                SharedWorkspaceResult.Success(
                    WorkspaceListing(
                        relativePath = result.value.path.ifEmpty { ROOT_PATH },
                        entries = entries,
                        truncated = result.value.nextCursor != null || result.value.entries.size > entries.size,
                        nextCursor = result.value.nextCursor,
                    ),
                )
            }
        }
    }

    override suspend fun stat(request: WorkspaceStatRequest): SharedWorkspaceResult<WorkspaceFileStat> {
        if (request.workspaceId != backend.descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        return when (val result = backend.stat(request.relativePath)) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                WorkspaceFileStat(
                    relativePath = result.value.path.ifEmpty { ROOT_PATH },
                    type = result.value.type.toShared(),
                    sizeBytes = result.value.sizeBytes ?: 0L,
                    version = publicVersion(result.value.version),
                ),
            )
        }
    }

    override suspend fun readText(request: WorkspaceReadTextRequest): SharedWorkspaceResult<WorkspaceText> {
        if (request.workspaceId != backend.descriptor.id || request.maxBytes < 1L) {
            return failure(ToolErrorCode.INVALID_REQUEST)
        }
        return when (val result = backend.read(
            request.relativePath,
            request.maxBytes.coerceAtMost(backend.descriptor.maxReadBytes),
            request.offsetBytes,
        )) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> when (val decoded = InternalWorkspaceVersions.decode(result.value.bytes)) {
                is InternalWorkspaceResult.Failure -> failure(decoded.error)
                is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                    WorkspaceText(
                        relativePath = request.relativePath,
                        text = decoded.value,
                        byteSize = result.value.bytes.size.toLong(),
                        version = publicVersion(result.value.version),
                        offsetBytes = result.value.offsetBytes,
                        totalBytes = result.value.totalBytes,
                        eof = result.value.eof,
                    ),
                )
            }
        }
    }

    override suspend fun applyPatch(request: WorkspaceApplyPatchRequest): SharedWorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != backend.descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val expected = when (val checked = implementationVersion(request.relativePath, request.expectedVersion)) {
            is InternalWorkspaceResult.Failure -> return failure(checked.error)
            is InternalWorkspaceResult.Success -> checked.value
        }
        if (expected == null) return failure(ToolErrorCode.CONFLICT)
        val format = when (request.format) {
            WorkspacePatchFormat.UNIFIED_DIFF -> InternalWorkspacePatchFormat.UNIFIED_DIFF
            WorkspacePatchFormat.REPLACE -> InternalWorkspacePatchFormat.REPLACE
        }
        return when (val result = backend.applyPatch(request.relativePath, request.patch, expected, format)) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                WorkspaceMutation(
                    relativePath = result.value.path,
                    type = SharedWorkspaceEntryType.FILE,
                    byteSize = result.value.bytes,
                    version = publicVersion(result.value.version),
                ),
            )
        }
    }

    override suspend fun writeText(request: WorkspaceWriteTextRequest): SharedWorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != backend.descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val expected = when (val checked = implementationVersion(request.relativePath, request.expectedVersion)) {
            is InternalWorkspaceResult.Failure -> return failure(checked.error)
            is InternalWorkspaceResult.Success -> checked.value
        }
        val bytes = when (val encoded = InternalWorkspaceVersions.text(request.text)) {
            is InternalWorkspaceResult.Failure -> return failure(encoded.error)
            is InternalWorkspaceResult.Success -> encoded.value
        }
        return when (val result = backend.write(request.relativePath, bytes, expected, request.replace)) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                WorkspaceMutation(
                    relativePath = result.value.path,
                    type = SharedWorkspaceEntryType.FILE,
                    byteSize = result.value.bytes,
                    version = publicVersion(result.value.version),
                ),
            )
        }
    }

    override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): SharedWorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != backend.descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val expected = when (val checked = implementationVersion(request.relativePath, request.expectedVersion)) {
            is InternalWorkspaceResult.Failure -> return failure(checked.error)
            is InternalWorkspaceResult.Success -> checked.value
        }
        return when (val result = backend.createDirectory(request.relativePath, expected)) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                WorkspaceMutation(
                    relativePath = result.value.path,
                    type = SharedWorkspaceEntryType.DIRECTORY,
                    version = publicVersion(result.value.version),
                ),
            )
        }
    }

    override suspend fun move(request: WorkspaceMoveRequest): SharedWorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != backend.descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val expected = when (val checked = implementationVersion(request.sourcePath, request.expectedVersion)) {
            is InternalWorkspaceResult.Failure -> return failure(checked.error)
            is InternalWorkspaceResult.Success -> checked.value
        }
        return when (val result = backend.move(request.sourcePath, request.destinationPath, expected, false)) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                WorkspaceMutation(
                    relativePath = result.value.destinationPath,
                    type = result.value.type.toShared(),
                    byteSize = result.value.bytes ?: 0L,
                    version = publicVersion(result.value.version),
                ),
            )
        }
    }

    /** App-only copy extension; the shared contract intentionally has no copy method yet. */
    override suspend fun copy(request: WorkspaceCopyRequest): SharedWorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != backend.descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val expected = when (val checked = implementationVersion(request.sourcePath, request.expectedVersion)) {
            is InternalWorkspaceResult.Failure -> return failure(checked.error)
            is InternalWorkspaceResult.Success -> checked.value
        }
        return when (val result = backend.copy(
            request.sourcePath,
            request.destinationPath,
            expected,
            request.replace,
        )) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                WorkspaceMutation(
                    relativePath = result.value.destinationPath,
                    type = result.value.type.toShared(),
                    byteSize = result.value.bytes ?: 0L,
                    version = publicVersion(result.value.version),
                ),
            )
        }
    }

    override suspend fun delete(request: runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest): SharedWorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != backend.descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val expected = when (val checked = implementationVersion(request.relativePath, request.expectedVersion)) {
            is InternalWorkspaceResult.Failure -> return failure(checked.error)
            is InternalWorkspaceResult.Success -> checked.value
        }
        return when (val result = backend.delete(request.relativePath, expected)) {
            is InternalWorkspaceResult.Failure -> failure(result.error)
            is InternalWorkspaceResult.Success -> SharedWorkspaceResult.Success(
                WorkspaceMutation(
                    relativePath = result.value.path,
                    type = result.value.type.toShared(),
                ),
            )
        }
    }

    /** Bind a shared numeric version to the full internal hash before mutating. */
    private fun implementationVersion(path: String, expected: Long?): InternalWorkspaceResult<String?> {
        if (expected == null) return InternalWorkspaceResult.Success(null)
        return when (val current = backend.stat(path)) {
            is InternalWorkspaceResult.Success -> current.value.version
                .takeIf { publicVersion(it) == expected }
                ?.let { InternalWorkspaceResult.Success(it) }
                ?: InternalWorkspaceResult.Failure(InternalWorkspaceError(InternalWorkspaceErrorCode.CONFLICT))
            is InternalWorkspaceResult.Failure -> current
        }
    }

    private fun toSharedEntry(entry: InternalWorkspaceEntry): SharedWorkspaceEntry = SharedWorkspaceEntry(
        relativePath = entry.path,
        type = entry.type.toShared(),
        sizeBytes = entry.sizeBytes ?: 0L,
        version = publicVersion(entry.version),
    )

    private fun InternalWorkspaceEntryType.toShared(): SharedWorkspaceEntryType = when (this) {
        InternalWorkspaceEntryType.FILE -> SharedWorkspaceEntryType.FILE
        InternalWorkspaceEntryType.DIRECTORY -> SharedWorkspaceEntryType.DIRECTORY
    }

    private fun InternalWorkspaceDescriptor.toSharedDescriptor(): SharedWorkspaceDescriptor =
        SharedWorkspaceDescriptor(
            id = id,
            displayName = displayName,
            backendType = when (backendType) {
                InternalWorkspaceBackendType.INTERNAL -> DomainWorkspaceBackendType.INTERNAL
                InternalWorkspaceBackendType.SAF_TREE -> DomainWorkspaceBackendType.SAF_TREE
            },
            // Root references are retained by the registry's construction layer, never this
            // model-facing descriptor.
            rootReference = "",
            readable = readable,
            writable = writable,
            quotaBytes = quotaBytes,
            maxFileBytes = maxFileBytes,
            maxFiles = maxEntries,
            maxDirectoryEntries = maxDirectoryEntries,
            enabled = enabled,
        )

    private fun failure(error: InternalWorkspaceError): SharedWorkspaceResult.Failure =
        SharedWorkspaceResult.Failure(error.errorForShared())

    private fun failure(code: ToolErrorCode): SharedWorkspaceResult.Failure =
        SharedWorkspaceResult.Failure(ToolError(code, message = code.name))

    private fun InternalWorkspaceError.errorForShared(): ToolError {
        val sharedCode = when (this.code) {
            InternalWorkspaceErrorCode.INVALID_PATH,
            InternalWorkspaceErrorCode.INVALID_ARGUMENT,
            InternalWorkspaceErrorCode.INVALID_UTF8,
            InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE,
            InternalWorkspaceErrorCode.INVALID_PATCH,
            InternalWorkspaceErrorCode.READ_LIMIT_EXCEEDED,
            InternalWorkspaceErrorCode.ENTRY_EXISTS,
            InternalWorkspaceErrorCode.ENTRY_UNSUPPORTED,
            InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY,
            InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED,
            InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED,
                -> ToolErrorCode.INVALID_REQUEST
            InternalWorkspaceErrorCode.PATH_OUT_OF_SCOPE -> ToolErrorCode.PATH_OUT_OF_SCOPE
            InternalWorkspaceErrorCode.SYMLINK_FORBIDDEN -> ToolErrorCode.SYMLINK_FORBIDDEN
            InternalWorkspaceErrorCode.WORKSPACE_NOT_FOUND,
            InternalWorkspaceErrorCode.ENTRY_NOT_FOUND,
            InternalWorkspaceErrorCode.GRANT_LOST,
                -> ToolErrorCode.WORKSPACE_NOT_FOUND
            InternalWorkspaceErrorCode.READ_ONLY,
            InternalWorkspaceErrorCode.PERMISSION_DENIED,
                -> ToolErrorCode.WORKSPACE_READ_ONLY
            InternalWorkspaceErrorCode.ROOT_OPERATION_FORBIDDEN -> ToolErrorCode.ROOT_OPERATION_FORBIDDEN
            InternalWorkspaceErrorCode.FILE_TOO_LARGE -> ToolErrorCode.FILE_TOO_LARGE
            InternalWorkspaceErrorCode.QUOTA_EXCEEDED -> ToolErrorCode.QUOTA_EXCEEDED
            InternalWorkspaceErrorCode.CONFLICT -> ToolErrorCode.CONFLICT
            InternalWorkspaceErrorCode.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
            InternalWorkspaceErrorCode.PROVIDER_ALIAS_AMBIGUOUS,
            InternalWorkspaceErrorCode.UNSUPPORTED,
            InternalWorkspaceErrorCode.IO_ERROR,
                -> ToolErrorCode.IO_ERROR
        }
        return ToolError(sharedCode, message = sharedCode.name, retryable = retryable)
    }

    private fun publicVersion(version: String): Long =
        parseUnsignedLong(version.take(16).padEnd(16, '0'), 16)

    companion object {
        const val ROOT_PATH = "."

        /**
         * Construct the app-private backend without exposing implementation DTOs to callers.
         * The returned adapter is ready to register in [runtime.mobileagent.tooling.WorkspaceRegistry].
         */
        @JvmStatic
        fun createInternal(
            root: Path,
            workspaceId: String = "internal",
        ): SharedWorkspaceBackendAdapter =
            SharedWorkspaceBackendAdapter(InternalWorkspaceBackend(root, workspaceId = workspaceId))

        /**
         * Construct the persisted-tree backend after ACTION_OPEN_DOCUMENT_TREE has granted the
         * caller a URI.  This method does not request, persist, or broaden permissions.
         */
        @JvmStatic
        fun createSaf(
            context: Context,
            treeUri: Uri,
            workspaceId: String = "saf-tree",
        ): SharedWorkspaceBackendAdapter =
            SharedWorkspaceBackendAdapter(SafWorkspaceBackend(context, treeUri, workspaceId = workspaceId))
    }
}
