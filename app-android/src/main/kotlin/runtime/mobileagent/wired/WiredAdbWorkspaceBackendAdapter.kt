// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import java.nio.charset.StandardCharsets
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrantStore
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceAttachment
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryBrowser
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryEntry
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceEntry
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceText
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest

/**
 * Public typed workspace adapter for the wired authority.  It intentionally
 * has no shell fallback: the companion's file RPC is the only path used.
 */
class WiredAdbWorkspaceBackendAdapter(
    private val authority: WiredAdbAuthorityPort,
    workspaceId: String = DEFAULT_WORKSPACE_ID,
    displayName: String = DEFAULT_DISPLAY_NAME,
    private val boundHandle: WiredAdbWorkspaceHandle? = null,
    scope: WorkspaceScope = WorkspaceScope.SELECTED_DIRECTORY,
) : WorkspaceBackend {
    init {
        require(workspaceId.matches(SAFE_WORKSPACE_ID))
        require(displayName.isNotBlank() && displayName.length <= 256)
    }

    override val descriptor = runtime.mobileagent.skills.tooling.WorkspaceDescriptor(
        id = workspaceId,
        displayName = displayName,
        backendType = WorkspaceBackendType.PRIVILEGED,
        rootReference = "",
        readable = true,
        writable = true,
        quotaBytes = WIRED_MAX_TOTAL_BYTES,
        maxFileBytes = WIRED_MAX_FILE_BYTES.toLong(),
        maxFiles = WIRED_MAX_FILES,
        maxDirectoryEntries = WIRED_MAX_DIRECTORY_ENTRIES,
        enabled = true,
        scope = scope,
    )

    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
        CapabilityId(CapabilityId.FILE_LIST),
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
        CapabilityId(CapabilityId.FILE_WRITE_TEXT),
        CapabilityId("file.apply_patch"),
        CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
        CapabilityId(CapabilityId.FILE_MOVE),
        CapabilityId(CapabilityId.FILE_DELETE),
    )

    override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val path = request.relativePath.orEmpty()
        if (path.toByteArray(StandardCharsets.UTF_8).size > WIRED_MAX_PATH_BYTES) return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return execute(
            operation = WiredAdbFileOperation.LIST,
            path = path,
            maxEntries = request.maxEntries.coerceAtMost(WIRED_MAX_DIRECTORY_ENTRIES),
            cursor = request.cursor,
        ).mapValue { result ->
            if (result.relativePath.orEmpty() != path) throw ProtocolShapeException()
            WorkspaceListing(
                relativePath = path.ifEmpty { "." },
                entries = result.entries.take(request.maxEntries).map { entry ->
                    WorkspaceEntry(entry.relativePath, entry.type.toSharedType(), entry.bytes ?: 0L, entry.version)
                },
                truncated = result.truncated || result.nextCursor != null || result.entries.size > request.maxEntries,
                nextCursor = result.nextCursor,
                skippedEntries = result.skippedEntries,
                warnings = result.listingWarnings,
            )
        }
    }

    override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        return execute(WiredAdbFileOperation.STAT, request.relativePath, maxEntries = 1).mapValue { result ->
            if (result.relativePath != request.relativePath || result.entries.size != 1) throw ProtocolShapeException()
            val entry = result.entries.single()
            WorkspaceFileStat(entry.relativePath, entry.type.toSharedType(), entry.bytes ?: 0L, entry.version)
        }
    }

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.maxBytes < 1L || request.offsetBytes < 0L) return failure(ToolErrorCode.INVALID_REQUEST)
        val maxBytes = request.maxBytes.coerceAtMost(WIRED_MAX_READ_BYTES.toLong())
        return execute(
            operation = WiredAdbFileOperation.READ_TEXT,
            path = request.relativePath,
            maxEntries = 1,
            maxBytes = maxBytes.toInt(),
            offsetBytes = request.offsetBytes,
        ).mapValue { result ->
            val text = result.text ?: throw ProtocolShapeException()
            if (result.relativePath != request.relativePath) throw ProtocolShapeException()
            val bytes = result.bytes ?: text.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (bytes != text.toByteArray(StandardCharsets.UTF_8).size.toLong()) throw ProtocolShapeException()
            WorkspaceText(
                relativePath = request.relativePath,
                text = text,
                byteSize = bytes,
                version = result.version,
                offsetBytes = result.offsetBytes,
                totalBytes = result.totalBytes,
                eof = result.eof,
            )
        }
    }

    override suspend fun applyPatch(request: runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val patch = request.patch.toByteArray(StandardCharsets.UTF_8)
        if (patch.size > WIRED_MAX_PATCH_BYTES) return failure(ToolErrorCode.FILE_TOO_LARGE)
        return execute(
            operation = WiredAdbFileOperation.APPLY_PATCH,
            path = request.relativePath,
            maxEntries = 1,
            patch = patch,
            expectedVersion = request.expectedVersion,
            patchFormat = if (request.format == runtime.mobileagent.skills.tooling.WorkspacePatchFormat.REPLACE) {
                WiredAdbPatchFormat.REPLACE
            } else WiredAdbPatchFormat.UNIFIED_DIFF,
        ).mapValue { result ->
            if (result.relativePath != request.relativePath || result.bytes == null || result.version == null) {
                throw ProtocolShapeException()
            }
            WorkspaceMutation(
                relativePath = request.relativePath,
                type = WorkspaceEntryType.FILE,
                byteSize = result.bytes,
                version = result.version,
            )
        }
    }

    override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val bytes = request.text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > WIRED_MAX_FILE_BYTES) return failure(ToolErrorCode.FILE_TOO_LARGE)
        return execute(
            operation = WiredAdbFileOperation.WRITE_TEXT,
            path = request.relativePath,
            maxEntries = 1,
            content = bytes,
            replace = request.replace,
        ).mapValue { result ->
            if (result.relativePath != request.relativePath || result.bytes == null) throw ProtocolShapeException()
            WorkspaceMutation(request.relativePath, WorkspaceEntryType.FILE, result.bytes)
        }
    }

    override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        return execute(WiredAdbFileOperation.CREATE_DIRECTORY, request.relativePath, maxEntries = 1).mapValue { result ->
            if (result.relativePath != request.relativePath) throw ProtocolShapeException()
            WorkspaceMutation(request.relativePath, WorkspaceEntryType.DIRECTORY)
        }
    }

    override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        return execute(
            operation = WiredAdbFileOperation.MOVE,
            path = request.sourcePath,
            maxEntries = 1,
            destination = request.destinationPath,
        ).mapValue { result ->
            if (result.relativePath != request.destinationPath) throw ProtocolShapeException()
            val type = result.entries.singleOrNull()?.type?.toSharedType() ?: WorkspaceEntryType.FILE
            WorkspaceMutation(request.destinationPath, type, result.bytes ?: 0L)
        }
    }

    override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        return execute(WiredAdbFileOperation.DELETE, request.relativePath, maxEntries = 1).mapValue { result ->
            if (result.relativePath != request.relativePath || result.deleted != true) throw ProtocolShapeException()
            val type = result.entries.singleOrNull()?.type?.toSharedType() ?: WorkspaceEntryType.FILE
            WorkspaceMutation(request.relativePath, type)
        }
    }

    private suspend fun execute(
        operation: WiredAdbFileOperation,
        path: String,
        maxEntries: Int,
        destination: String? = null,
        content: ByteArray? = null,
        replace: Boolean = false,
        maxBytes: Int = WIRED_DEFAULT_READ_BYTES,
        cursor: String? = null,
        offsetBytes: Long = 0L,
        patch: ByteArray? = null,
        expectedVersion: Long? = null,
        patchFormat: WiredAdbPatchFormat = WiredAdbPatchFormat.UNIFIED_DIFF,
    ): WorkspaceResult<WiredAdbFileResult> {
        val request = runCatching {
            authority.newFileRequest(
                operation = operation,
                relativePath = path,
                destinationRelativePath = destination,
                contentUtf8 = content,
                replaceExisting = replace,
                maxBytes = maxBytes,
                cursor = cursor,
                maxEntries = maxEntries.coerceAtMost(WIRED_MAX_DIRECTORY_ENTRIES),
                offsetBytes = offsetBytes,
                patchUtf8 = patch,
                expectedVersion = expectedVersion,
                patchFormat = patchFormat,
            )
        }.getOrElse { return failure(ToolErrorCode.INVALID_REQUEST) }
        return try {
            when (val result = boundHandle?.let { authority.workspace.executeBoundFile(it, request) }
                ?: authority.workspace.executeFile(request)) {
                is WiredAdbResult.Success -> result.value
                    .takeIf { it.entries.size <= WIRED_MAX_ENTRIES }
                    ?.let { WorkspaceResult.Success(it) }
                    ?: failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
                is WiredAdbResult.Failure -> failure(result.code.toToolErrorCode())
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
    }

    private fun WiredAdbErrorCode.toToolErrorCode(): ToolErrorCode = when (this) {
        WiredAdbErrorCode.PAIRING_REQUIRED,
        WiredAdbErrorCode.BRIDGE_NOT_PAIRED -> ToolErrorCode.BRIDGE_NOT_PAIRED
        WiredAdbErrorCode.BRIDGE_DISCONNECTED,
        WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
        WiredAdbErrorCode.AUTHORITY_USER_DISABLED -> ToolErrorCode.AUTHORITY_NOT_GRANTED
        WiredAdbErrorCode.AUTHORITY_UNSUPPORTED -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
        WiredAdbErrorCode.REQUEST_INVALID -> ToolErrorCode.INVALID_REQUEST
        WiredAdbErrorCode.PROTOCOL_REPLAY -> ToolErrorCode.CALL_ID_REPLAY
        WiredAdbErrorCode.PROTOCOL_FRAME_INVALID,
        WiredAdbErrorCode.PROTOCOL_FRAME_TOO_LARGE,
        WiredAdbErrorCode.PROTOCOL_AUTH_FAILED,
        WiredAdbErrorCode.PROTOCOL_NO_COMPRESSION,
        WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH -> ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH
        WiredAdbErrorCode.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
        WiredAdbErrorCode.TIMEOUT -> ToolErrorCode.TIMEOUT
        WiredAdbErrorCode.WORKSPACE_NOT_FOUND -> ToolErrorCode.WORKSPACE_NOT_FOUND
        WiredAdbErrorCode.PATH_OUT_OF_SCOPE -> ToolErrorCode.PATH_OUT_OF_SCOPE
        WiredAdbErrorCode.SYMLINK_FORBIDDEN -> ToolErrorCode.SYMLINK_FORBIDDEN
        WiredAdbErrorCode.INVALID_CONTENT -> ToolErrorCode.INVALID_REQUEST
        WiredAdbErrorCode.TARGET_EXISTS,
        WiredAdbErrorCode.NON_EMPTY_DIRECTORY -> ToolErrorCode.CONFLICT
        WiredAdbErrorCode.FILE_TOO_LARGE -> ToolErrorCode.FILE_TOO_LARGE
        WiredAdbErrorCode.QUOTA_EXCEEDED -> ToolErrorCode.QUOTA_EXCEEDED
        WiredAdbErrorCode.PERMISSION_DENIED -> ToolErrorCode.PERMISSION_DENIED
        WiredAdbErrorCode.CONFLICT -> ToolErrorCode.CONFLICT
        WiredAdbErrorCode.OFFSET_OUT_OF_RANGE,
        WiredAdbErrorCode.INVALID_PATCH -> ToolErrorCode.INVALID_REQUEST
        WiredAdbErrorCode.INVALID_CURSOR -> ToolErrorCode.INVALID_CURSOR
        WiredAdbErrorCode.UNSUPPORTED_ENTRY -> ToolErrorCode.UNSUPPORTED_ENTRY
        WiredAdbErrorCode.OPERATION_UNAVAILABLE -> ToolErrorCode.OPERATION_UNAVAILABLE
        WiredAdbErrorCode.WRITE_UNVERIFIED -> ToolErrorCode.UNKNOWN_OUTCOME
        WiredAdbErrorCode.ATOMIC_REPLACE_UNAVAILABLE -> ToolErrorCode.IO_ERROR
        WiredAdbErrorCode.IO_ERROR,
        WiredAdbErrorCode.INTERNAL_ERROR -> ToolErrorCode.IO_ERROR
        else -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
    }

    private fun <T> failure(code: ToolErrorCode): WorkspaceResult<T> =
        WorkspaceResult.Failure(ToolError(code))

    private class ProtocolShapeException : RuntimeException()

    companion object {
        const val DEFAULT_WORKSPACE_ID = WIRED_WORKSPACE_ID
        const val DEFAULT_DISPLAY_NAME = "Wired ADB workspace"
        private val SAFE_WORKSPACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
    }
}

private inline fun <T, R> WorkspaceResult<T>.mapValue(transform: (T) -> R): WorkspaceResult<R> = when (this) {
    is WorkspaceResult.Failure -> this
    is WorkspaceResult.Success -> runCatching { WorkspaceResult.Success(transform(value)) }
        .getOrElse { WorkspaceResult.Failure(ToolError(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)) }
}

private fun WiredAdbEntryType.toSharedType(): WorkspaceEntryType = when (this) {
    WiredAdbEntryType.FILE -> WorkspaceEntryType.FILE
    WiredAdbEntryType.DIRECTORY -> WorkspaceEntryType.DIRECTORY
}

/**
 * Wired ADB provider backed by the authenticated companion's opaque directory
 * bindings. The only absolute-path input is [attachUserPath], which is called
 * from a foreground user action and is consumed inside the encrypted bridge.
 */
class WiredAdbDeviceWorkspaceProvider(
    private val authorityPort: WiredAdbAuthorityPort,
    private val fullDeviceGrantStore: FullDeviceFilesGrantStore?,
) : PrivilegedWorkspaceProvider {
    override val authority: Authority = Authority.WIRED_ADB
    /**
     * The picker starts at the user storage location instead of exposing the
     * device root.  This is a provider-internal canonical location; it never
     * crosses the model/runtime workspace contract.  The companion validates
     * it again before creating the short-lived navigation binding.
     */
    private val defaultPickerRoot = "/storage/emulated/0"
    private val pickerOwner = Any()
    private val browser = object : WorkspaceDirectoryBrowser {
        override suspend fun root(maxEntries: Int): WorkspaceResult<WorkspaceDirectoryPage> =
            browseRoot(maxEntries)

        override suspend fun browse(request: WorkspaceBrowseRequest): WorkspaceResult<WorkspaceDirectoryPage> =
            browsePickerDirectory(request)
    }
    private val activeHandles = linkedMapOf<String, WiredAdbWorkspaceHandle>()
    private var pickerRoot: PickerDirectoryHandle? = null
    @Volatile private var closed = false

    override val directoryBrowser: WorkspaceDirectoryBrowser get() = browser
    override val supportsFullDeviceFiles: Boolean get() = !closed && fullDeviceGrantStore != null

    suspend fun attachUserPath(
        workspaceId: String,
        displayName: String,
        absolutePath: String,
    ): WorkspaceResult<WorkspaceAttachment> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val attached = authorityPort.workspace.attachDirectory(
            workspaceId = workspaceId,
            displayName = displayName,
            absolutePath = absolutePath,
            scope = WiredAdbWorkspaceScope.SELECTED_DIRECTORY,
            confirmedByUser = true,
        )
        return attachmentResult(attached, displayName, WorkspaceScope.SELECTED_DIRECTORY)
    }

    override suspend fun attachDirectory(request: WorkspaceAttachRequest): WorkspaceResult<WorkspaceAttachment> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val handle = request.directory as? PickerDirectoryHandle
            ?: return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.owner !== pickerOwner) return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.virtualRoot) return failure(ToolErrorCode.ROOT_OPERATION_FORBIDDEN)
        val attached = try {
            authorityPort.workspace.attachDirectory(
                workspaceId = request.workspaceId,
                displayName = request.displayName,
                // This path is retained only by the provider-owned opaque
                // picker handle and is revalidated by the bridge.  It is not
                // accepted from model-facing requests.
                absolutePath = handle.absolutePath,
                scope = WiredAdbWorkspaceScope.SELECTED_DIRECTORY,
                confirmedByUser = true,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
        return attachmentResult(attached, request.displayName, WorkspaceScope.SELECTED_DIRECTORY)
    }

    /**
     * Creates one short-lived navigation binding for the default user-storage
     * location.  A normal picker must not bind `/` as a selected workspace;
     * full-device access remains behind the explicit grant path below.
     */
    private suspend fun browseRoot(maxEntries: Int): WorkspaceResult<WorkspaceDirectoryPage> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        if (maxEntries !in 1..WIRED_MAX_DIRECTORY_ENTRIES) {
            return failure(ToolErrorCode.INVALID_REQUEST)
        }
        val existing = synchronized(activeHandles) { pickerRoot }
        if (existing != null) {
            val checked = browseRemote(existing, maxEntries)
            if (checked is WorkspaceResult.Success) return syntheticRoot(existing)
            synchronized(activeHandles) {
                if (pickerRoot === existing) pickerRoot = null
            }
            return checked
        }
        val attached = try {
            authorityPort.workspace.attachDirectory(
                workspaceId = PICKER_WORKSPACE_ID,
                displayName = PICKER_DISPLAY_NAME,
                absolutePath = defaultPickerRoot,
                scope = WiredAdbWorkspaceScope.SELECTED_DIRECTORY,
                confirmedByUser = true,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
        val value = when (attached) {
            is WiredAdbResult.Failure -> return failure(attached.code.toToolErrorCode())
            is WiredAdbResult.Success -> attached.value
        }
        if (value.scope != WiredAdbWorkspaceScope.SELECTED_DIRECTORY ||
            value.initialPage.relativePath.isNotEmpty()
        ) {
            return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        }
        val root = PickerDirectoryHandle(
            owner = pickerOwner,
            remoteHandle = value.handle,
            relativePath = "",
            absolutePath = defaultPickerRoot,
            parent = null,
            virtualRoot = true,
        )
        synchronized(activeHandles) {
            pickerRoot = root
        }
        return syntheticRoot(root)
    }

    private suspend fun browsePickerDirectory(
        request: WorkspaceBrowseRequest,
    ): WorkspaceResult<WorkspaceDirectoryPage> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        if (request.maxEntries !in 1..WIRED_MAX_DIRECTORY_ENTRIES) {
            return failure(ToolErrorCode.INVALID_REQUEST)
        }
        val handle = request.handle as? PickerDirectoryHandle
            ?: return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.owner !== pickerOwner) return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.virtualRoot) return browseRoot(request.maxEntries)
        return browseRemote(handle, request.maxEntries)
    }

    private suspend fun browseRemote(
        handle: PickerDirectoryHandle,
        maxEntries: Int,
    ): WorkspaceResult<WorkspaceDirectoryPage> {
        val result = try {
            authorityPort.workspace.browseDirectory(
                handle = handle.remoteHandle,
                relativePath = handle.relativePath.ifEmpty { null },
                maxEntries = maxEntries,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
        val page = when (result) {
            is WiredAdbResult.Failure -> return failure(result.code.toToolErrorCode())
            is WiredAdbResult.Success -> result.value
        }
        if (page.handle !== handle.remoteHandle || page.relativePath != handle.relativePath) {
            return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        }
        val entries = ArrayList<WorkspaceDirectoryEntry>(page.entries.size)
        for (entry in page.entries) {
            val childName = directChildName(handle.relativePath, entry.relativePath)
                ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            val childPath = entry.relativePath
            val childAbsolutePath = joinAbsolute(handle.absolutePath, childName)
            val childHandle = if (entry.type == WiredAdbEntryType.DIRECTORY) {
                PickerDirectoryHandle(
                    owner = pickerOwner,
                    remoteHandle = handle.remoteHandle,
                    relativePath = childPath,
                    absolutePath = childAbsolutePath,
                    parent = handle,
                    virtualRoot = false,
                )
            } else {
                null
            }
            entries += WorkspaceDirectoryEntry(
                name = childName,
                type = entry.type.toSharedType(),
                sizeBytes = entry.bytes,
                // The typed browse result is produced by the authenticated
                // shell-UID helper.  Actual failures are reported by the
                // subsequent browse/attach operation, never guessed here.
                readable = true,
                writable = true,
                handle = childHandle,
            )
        }
        return WorkspaceResult.Success(
            WorkspaceDirectoryPage(
                current = handle,
                parent = handle.parent,
                entries = entries.take(maxEntries),
                truncated = page.truncated || entries.size > maxEntries,
            ),
        )
    }

    private fun syntheticRoot(handle: PickerDirectoryHandle): WorkspaceResult<WorkspaceDirectoryPage> {
        val storage = PickerDirectoryHandle(
            owner = pickerOwner,
            remoteHandle = handle.remoteHandle,
            relativePath = "",
            absolutePath = defaultPickerRoot,
            parent = handle,
            virtualRoot = false,
        )
        return WorkspaceResult.Success(
            WorkspaceDirectoryPage(
                current = handle,
                parent = null,
                entries = listOf(
                    WorkspaceDirectoryEntry(
                        name = "storage",
                        type = WorkspaceEntryType.DIRECTORY,
                        readable = true,
                        writable = true,
                        handle = storage,
                    ),
                ),
                truncated = false,
            ),
        )
    }

    private fun directChildName(parent: String, child: String): String? {
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        if (!child.startsWith(prefix)) return null
        val name = child.removePrefix(prefix)
        return name.takeIf { it.isNotEmpty() && !it.contains('/') && it != "." && it != ".." }
    }

    private fun joinAbsolute(parent: String, child: String): String {
        require(parent.startsWith('/') && !parent.endsWith('/'))
        require(child.isNotEmpty() && !child.contains('/') && child != "." && child != "..")
        return "$parent/$child"
    }

    private class PickerDirectoryHandle(
        val owner: Any,
        val remoteHandle: WiredAdbWorkspaceHandle,
        val relativePath: String,
        val absolutePath: String,
        val parent: PickerDirectoryHandle?,
        val virtualRoot: Boolean,
    ) : WorkspaceDirectoryHandle()

    private companion object {
        const val PICKER_WORKSPACE_ID = "wired-picker-root"
        const val PICKER_DISPLAY_NAME = "内部存储"
    }

    override suspend fun openFullDeviceFiles(request: FullDeviceFilesRequest): WorkspaceResult<WorkspaceAttachment> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        if (!request.confirmedByUser) return failure(ToolErrorCode.CAPABILITY_DENIED)
        val store = fullDeviceGrantStore ?: return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val grant = runCatching { store.load(request.workspaceId) }.getOrElse {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        } ?: return failure(ToolErrorCode.AUTHORITY_NOT_GRANTED)
        if (grant.revision != request.grantRevision) return failure(ToolErrorCode.CONFLICT)
        val attached = authorityPort.workspace.attachDirectory(
            workspaceId = request.workspaceId,
            displayName = request.displayName,
            absolutePath = "/",
            scope = WiredAdbWorkspaceScope.FULL_DEVICE_FILES,
            grantRevision = request.grantRevision,
            confirmedByUser = true,
        )
        return attachmentResult(attached, request.displayName, WorkspaceScope.FULL_DEVICE_FILES)
    }

    override suspend fun revokeFullDeviceFiles(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val store = fullDeviceGrantStore ?: return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val revoked = runCatching { store.revoke(workspaceId, expectedRevision) }.getOrElse {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
        if (revoked is WorkspaceResult.Failure) return revoked
        val handle = synchronized(activeHandles) { activeHandles.remove(workspaceId) }
        if (handle != null) {
            return when (val released = authorityPort.workspace.releaseDirectory(handle)) {
                is WiredAdbResult.Success -> WorkspaceResult.Success(Unit)
                is WiredAdbResult.Failure -> failure(released.code.toToolErrorCode())
            }
        }
        return WorkspaceResult.Success(Unit)
    }

    override fun close() {
        closed = true
        synchronized(activeHandles) { activeHandles.clear() }
    }

    private fun attachmentResult(
        result: WiredAdbResult<WiredAdbWorkspaceAttachment>,
        displayName: String,
        scope: WorkspaceScope,
    ): WorkspaceResult<WorkspaceAttachment> = when (result) {
        is WiredAdbResult.Failure -> failure(result.code.toToolErrorCode())
        is WiredAdbResult.Success -> {
            val attachment = result.value
            val expectedScope = if (scope == WorkspaceScope.FULL_DEVICE_FILES) {
                WiredAdbWorkspaceScope.FULL_DEVICE_FILES
            } else {
                WiredAdbWorkspaceScope.SELECTED_DIRECTORY
            }
            if (attachment.scope != expectedScope) {
                failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            } else {
                synchronized(activeHandles) { activeHandles[attachment.workspaceId] = attachment.handle }
                val backend = WiredAdbWorkspaceBackendAdapter(
                    authority = authorityPort,
                    workspaceId = attachment.workspaceId,
                    displayName = displayName,
                    boundHandle = attachment.handle,
                    scope = scope,
                )
                WorkspaceResult.Success(WorkspaceAttachment(backend.descriptor, backend))
            }
        }
    }
}

private fun WiredAdbErrorCode.toToolErrorCode(): ToolErrorCode = when (this) {
    WiredAdbErrorCode.PAIRING_REQUIRED,
    WiredAdbErrorCode.BRIDGE_NOT_PAIRED -> ToolErrorCode.BRIDGE_NOT_PAIRED
    WiredAdbErrorCode.BRIDGE_DISCONNECTED -> ToolErrorCode.BRIDGE_DISCONNECTED
    WiredAdbErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
    WiredAdbErrorCode.AUTHORITY_USER_DISABLED -> ToolErrorCode.AUTHORITY_NOT_GRANTED
    WiredAdbErrorCode.AUTHORITY_UNSUPPORTED -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
    WiredAdbErrorCode.REQUEST_INVALID -> ToolErrorCode.INVALID_REQUEST
    WiredAdbErrorCode.WORKSPACE_BINDING_INVALID -> ToolErrorCode.WORKSPACE_NOT_FOUND
    WiredAdbErrorCode.FULL_DEVICE_GRANT_REQUIRED -> ToolErrorCode.CAPABILITY_DENIED
    WiredAdbErrorCode.PROTOCOL_REPLAY -> ToolErrorCode.CALL_ID_REPLAY
    WiredAdbErrorCode.PROTOCOL_FRAME_INVALID,
    WiredAdbErrorCode.PROTOCOL_FRAME_TOO_LARGE,
    WiredAdbErrorCode.PROTOCOL_AUTH_FAILED,
    WiredAdbErrorCode.PROTOCOL_NO_COMPRESSION,
    WiredAdbErrorCode.BRIDGE_PROTOCOL_MISMATCH -> ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH
    WiredAdbErrorCode.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
    WiredAdbErrorCode.TIMEOUT -> ToolErrorCode.TIMEOUT
    WiredAdbErrorCode.WORKSPACE_NOT_FOUND -> ToolErrorCode.WORKSPACE_NOT_FOUND
    WiredAdbErrorCode.PATH_OUT_OF_SCOPE -> ToolErrorCode.PATH_OUT_OF_SCOPE
    WiredAdbErrorCode.SYMLINK_FORBIDDEN -> ToolErrorCode.SYMLINK_FORBIDDEN
    WiredAdbErrorCode.INVALID_CONTENT -> ToolErrorCode.INVALID_REQUEST
    WiredAdbErrorCode.TARGET_EXISTS,
    WiredAdbErrorCode.NON_EMPTY_DIRECTORY -> ToolErrorCode.CONFLICT
    WiredAdbErrorCode.FILE_TOO_LARGE -> ToolErrorCode.FILE_TOO_LARGE
    WiredAdbErrorCode.QUOTA_EXCEEDED -> ToolErrorCode.QUOTA_EXCEEDED
    WiredAdbErrorCode.PERMISSION_DENIED -> ToolErrorCode.PERMISSION_DENIED
    WiredAdbErrorCode.CONFLICT -> ToolErrorCode.CONFLICT
    WiredAdbErrorCode.OFFSET_OUT_OF_RANGE,
    WiredAdbErrorCode.INVALID_PATCH -> ToolErrorCode.INVALID_REQUEST
    WiredAdbErrorCode.INVALID_CURSOR -> ToolErrorCode.INVALID_CURSOR
    WiredAdbErrorCode.UNSUPPORTED_ENTRY -> ToolErrorCode.UNSUPPORTED_ENTRY
    WiredAdbErrorCode.OPERATION_UNAVAILABLE -> ToolErrorCode.OPERATION_UNAVAILABLE
    WiredAdbErrorCode.WRITE_UNVERIFIED -> ToolErrorCode.UNKNOWN_OUTCOME
    WiredAdbErrorCode.ATOMIC_REPLACE_UNAVAILABLE -> ToolErrorCode.IO_ERROR
    WiredAdbErrorCode.IO_ERROR,
    WiredAdbErrorCode.INTERNAL_ERROR -> ToolErrorCode.IO_ERROR
    else -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
}

private fun <T> failure(code: ToolErrorCode): WorkspaceResult<T> =
    WorkspaceResult.Failure(ToolError(code))

/** Public app-container seam; no transport identity or root path is exposed. */
object WiredAdbPrivilegedWorkspaceFactory {
    @JvmStatic
    fun create(
        authority: WiredAdbAuthorityPort,
        workspaceId: String = WiredAdbWorkspaceBackendAdapter.DEFAULT_WORKSPACE_ID,
        displayName: String = WiredAdbWorkspaceBackendAdapter.DEFAULT_DISPLAY_NAME,
        fullDeviceBackend: WorkspaceBackend? = null,
        fullDeviceGrantStore: FullDeviceFilesGrantStore? = null,
    ): PrivilegedWorkspaceProvider {
        // Retain the parameters for source compatibility; dynamic bindings
        // create their own descriptor only after the user selects a directory.
        require(workspaceId.isNotBlank() && displayName.isNotBlank())
        require(fullDeviceBackend == null) { "Wired full-device access must use an authenticated bound directory" }
        return WiredAdbDeviceWorkspaceProvider(authority, fullDeviceGrantStore)
    }
}
