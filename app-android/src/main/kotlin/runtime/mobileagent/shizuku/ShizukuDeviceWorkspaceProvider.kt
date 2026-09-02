// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import java.nio.charset.StandardCharsets
import org.json.JSONObject
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrantStore
import runtime.mobileagent.skills.tooling.FullDeviceFilesRequest
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
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
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode

/**
 * Shizuku provider backed by the UserService's device-root typed RPC.
 *
 * The UserService owns absolute paths and canonicalization. This side only
 * holds opaque handles and relative paths, so no model-facing API can turn a
 * path into a shell command or disclose the selected root.
 */
internal class ShizukuDeviceWorkspaceProvider(
    private val bridge: ShizukuAuthorityBridge,
    private val defaultWorkspaceId: String,
    private val defaultDisplayName: String,
    private val fullDeviceGrantStore: FullDeviceFilesGrantStore?,
) : PrivilegedWorkspaceProvider {
    override val authority: Authority = Authority.SHIZUKU
    private val owner = Any()
    private val browser = DeviceBrowser()
    @Volatile private var closed = false

    override val directoryBrowser: WorkspaceDirectoryBrowser get() = browser
    override val supportsFullDeviceFiles: Boolean
        get() = !closed && fullDeviceGrantStore != null

    override suspend fun attachDirectory(request: WorkspaceAttachRequest): WorkspaceResult<runtime.mobileagent.skills.tooling.WorkspaceAttachment> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val handle = request.directory as? RemoteDirectoryHandle
            ?: return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.owner !== owner) return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.deviceRoot) return failure(ToolErrorCode.ROOT_OPERATION_FORBIDDEN)
        val dispatch = safeDispatch { bridge.dispatchDirectoryAttach(handle.token) }
        val workspaceHandle = parseAttach(dispatch) ?: return attachFailure(dispatch)
        return WorkspaceResult.Success(
            runtime.mobileagent.skills.tooling.WorkspaceAttachment(
                descriptor = descriptor(request.workspaceId, request.displayName, WorkspaceScope.SELECTED_DIRECTORY),
                backend = ShizukuTokenWorkspaceBackend(
                    bridge = bridge,
                    workspaceHandle = workspaceHandle,
                    workspaceId = request.workspaceId,
                    displayName = request.displayName,
                    scope = WorkspaceScope.SELECTED_DIRECTORY,
                    owner = owner,
                ),
            ),
        )
    }

    override suspend fun openFullDeviceFiles(request: FullDeviceFilesRequest): WorkspaceResult<runtime.mobileagent.skills.tooling.WorkspaceAttachment> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        if (!request.confirmedByUser) return failure(ToolErrorCode.CAPABILITY_DENIED)
        val store = fullDeviceGrantStore ?: return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val grant = runCatching { store.load(request.workspaceId) }.getOrElse {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        } ?: return failure(ToolErrorCode.AUTHORITY_NOT_GRANTED)
        if (grant.revision != request.grantRevision) return failure(ToolErrorCode.CONFLICT)
        val root = when (val result = browser.root(maxEntries = 1)) {
            is WorkspaceResult.Failure -> return result
            is WorkspaceResult.Success -> result.value
        }
        val rootHandle = root.current as? RemoteDirectoryHandle
            ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        if (rootHandle.owner !== owner || !rootHandle.deviceRoot) {
            return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        }
        val dispatch = safeDispatch { bridge.dispatchDirectoryAttach(rootHandle.token) }
        val workspaceHandle = parseAttach(dispatch) ?: return attachFailure(dispatch)
        return WorkspaceResult.Success(
            runtime.mobileagent.skills.tooling.WorkspaceAttachment(
                descriptor = descriptor(request.workspaceId, request.displayName, WorkspaceScope.FULL_DEVICE_FILES),
                backend = ShizukuTokenWorkspaceBackend(
                    bridge = bridge,
                    workspaceHandle = workspaceHandle,
                    workspaceId = request.workspaceId,
                    displayName = request.displayName,
                    scope = WorkspaceScope.FULL_DEVICE_FILES,
                    owner = owner,
                ),
            ),
        )
    }

    override suspend fun revokeFullDeviceFiles(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val store = fullDeviceGrantStore ?: return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        return runCatching { store.revoke(workspaceId, expectedRevision) }.getOrElse {
            failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
    }

    override fun close() {
        closed = true
    }

    private inner class DeviceBrowser : WorkspaceDirectoryBrowser {
        override suspend fun root(maxEntries: Int): WorkspaceResult<WorkspaceDirectoryPage> {
            if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
            if (maxEntries !in 1..ShizukuDirectoryHandleStore.MAX_DIRECTORY_ENTRIES) {
                return failure(ToolErrorCode.INVALID_REQUEST)
            }
            val dispatch = safeDispatch { bridge.dispatchDirectoryRoot(maxEntries) }
            return parseDirectoryPage(dispatch, "open_directory_root", maxEntries)
        }

        override suspend fun browse(request: WorkspaceBrowseRequest): WorkspaceResult<WorkspaceDirectoryPage> {
            if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
            if (request.maxEntries !in 1..ShizukuDirectoryHandleStore.MAX_DIRECTORY_ENTRIES) {
                return failure(ToolErrorCode.INVALID_REQUEST)
            }
            val handle = request.handle as? RemoteDirectoryHandle
                ?: return failure(ToolErrorCode.INVALID_REQUEST)
            if (handle.owner !== owner) return failure(ToolErrorCode.INVALID_REQUEST)
            val dispatch = safeDispatch {
                bridge.dispatchDirectoryBrowse(handle.token, request.maxEntries)
            }
            return parseDirectoryPage(dispatch, "browse_directory", request.maxEntries)
        }
    }

    private fun parseDirectoryPage(
        dispatch: ShizukuDispatchResult,
        operation: String,
        maxEntries: Int,
    ): WorkspaceResult<WorkspaceDirectoryPage> {
        val payload = payload(dispatch, operation) ?: return dispatchFailure(dispatch)
        val token = payload.optString("handle", "")
        if (!isOpaqueToken(token)) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        val parent = payload.opt("parentHandle")
            ?.takeUnless { it == JSONObject.NULL }
            ?.let { it as? String }
            ?.takeIf(::isOpaqueToken)
        if (payload.has("parentHandle") && payload.opt("parentHandle") != JSONObject.NULL && parent == null) {
            return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        }
        val entriesJson = payload.optJSONArray("entries") ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        if (entriesJson.length() > ShizukuDirectoryHandleStore.MAX_DIRECTORY_ENTRIES) {
            return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        }
        val entries = ArrayList<WorkspaceDirectoryEntry>(minOf(maxEntries, entriesJson.length()))
        for (index in 0 until minOf(maxEntries, entriesJson.length())) {
            val item = entriesJson.optJSONObject(index) ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            val name = item.optString("name", "")
            val type = when (item.optString("type", "")) {
                "file" -> WorkspaceEntryType.FILE
                "directory" -> WorkspaceEntryType.DIRECTORY
                else -> return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            }
            val size = item.opt("bytes")?.takeUnless { it == JSONObject.NULL }?.let {
                (it as? Number)?.toLong()
            }
            if (size != null && (size < 0L || size > ShizukuWorkspaceFileStore.MAX_FILE_BYTES)) {
                return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            }
            val childToken = item.optString("handle", "")
                .takeIf { type == WorkspaceEntryType.DIRECTORY && isOpaqueToken(it) }
            if (type == WorkspaceEntryType.DIRECTORY && childToken == null) {
                return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            }
            val child = childToken?.let { RemoteDirectoryHandle(owner, it, deviceRoot = false) }
            entries += WorkspaceDirectoryEntry(
                name = name,
                type = type,
                sizeBytes = size,
                readable = item.optBoolean("readable", false),
                writable = item.optBoolean("writable", false),
                handle = child,
            )
        }
        return WorkspaceResult.Success(
            WorkspaceDirectoryPage(
                current = RemoteDirectoryHandle(owner, token, payload.optBoolean("deviceRoot", false)),
                parent = parent?.let { RemoteDirectoryHandle(owner, it, deviceRoot = false) },
                entries = entries,
                truncated = payload.optBoolean("truncated", false) || entriesJson.length() > entries.size,
            ),
        )
    }

    private fun parseAttach(dispatch: ShizukuDispatchResult): String? {
        val payload = payload(dispatch, "attach_directory") ?: return null
        val token = payload.optString("workspaceHandle", "")
        return token.takeIf(::isOpaqueToken)
    }

    private fun attachFailure(dispatch: ShizukuDispatchResult): WorkspaceResult.Failure = dispatchFailure(dispatch)

    private fun descriptor(id: String, name: String, scope: WorkspaceScope) =
        runtime.mobileagent.skills.tooling.WorkspaceDescriptor(
            id = id,
            displayName = name,
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "",
            readable = true,
            writable = true,
            quotaBytes = null,
            maxFileBytes = ShizukuWorkspaceFileStore.MAX_FILE_BYTES.toLong(),
            maxFiles = if (scope == WorkspaceScope.FULL_DEVICE_FILES) 100_000 else ShizukuWorkspaceFileStore.MAX_FILES,
            maxDirectoryEntries = ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES,
            enabled = true,
            scope = scope,
        )

    private fun payload(dispatch: ShizukuDispatchResult, operation: String): JSONObject? {
        if (dispatch !is ShizukuDispatchResult.Success) return null
        val payload = runCatching { JSONObject(dispatch.payload) }.getOrNull() ?: return null
        return payload.takeIf { it.optBoolean("ok", false) && it.optString("operation", "") == operation }
    }

    private fun dispatchFailure(dispatch: ShizukuDispatchResult): WorkspaceResult.Failure {
        return when (dispatch) {
            is ShizukuDispatchResult.Denied -> WorkspaceResult.Failure(ToolError(ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE))
            is ShizukuDispatchResult.Failed -> WorkspaceResult.Failure(
                ToolError(if (dispatch.unknownOutcome) ToolErrorCode.UNKNOWN_OUTCOME else mapError(dispatch.errorCode)),
            )
            is ShizukuDispatchResult.Success -> WorkspaceResult.Failure(ToolError(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH))
        }
    }

    private fun mapError(code: String?): ToolErrorCode = when (code) {
        ShizukuDirectoryHandleStore.PERMISSION_DENIED -> ToolErrorCode.SHIZUKU_PERMISSION_DENIED
        ShizukuDirectoryHandleStore.INVALID_HANDLE -> ToolErrorCode.INVALID_REQUEST
        ShizukuWorkspaceFileStore.INVALID_PATH,
        ShizukuWorkspaceFileStore.OUTSIDE_ROOT -> ToolErrorCode.PATH_OUT_OF_SCOPE
        ShizukuWorkspaceFileStore.SYMLINK_REJECTED -> ToolErrorCode.SYMLINK_FORBIDDEN
        ShizukuWorkspaceFileStore.NOT_FOUND -> ToolErrorCode.WORKSPACE_NOT_FOUND
        ShizukuWorkspaceFileStore.FILE_TOO_LARGE -> ToolErrorCode.FILE_TOO_LARGE
        ShizukuWorkspaceFileStore.LIMIT,
        ShizukuWorkspaceFileStore.OUTPUT_LIMIT -> ToolErrorCode.QUOTA_EXCEEDED
        ShizukuWorkspaceFileStore.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
        else -> ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
    }

    private fun safeDispatch(block: () -> ShizukuDispatchResult): ShizukuDispatchResult = try {
        block()
    } catch (_: RuntimeException) {
        ShizukuDispatchResult.Failed("Shizuku dispatch failed", unknownOutcome = true)
    }

    private fun isOpaqueToken(token: String): Boolean =
        token.length in 40..128 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun <T> failure(code: ToolErrorCode): WorkspaceResult<T> =
        WorkspaceResult.Failure(ToolError(code))

    private class RemoteDirectoryHandle(
        val owner: Any,
        val token: String,
        val deviceRoot: Boolean,
    ) : WorkspaceDirectoryHandle()
}

/** Backend for a service-owned attached directory token. */
private class ShizukuTokenWorkspaceBackend(
    private val bridge: ShizukuAuthorityBridge,
    private val workspaceHandle: String,
    workspaceId: String,
    displayName: String,
    scope: WorkspaceScope,
    private val owner: Any,
) : WorkspaceBackend {
    override val descriptor = runtime.mobileagent.skills.tooling.WorkspaceDescriptor(
        id = workspaceId,
        displayName = displayName,
        backendType = WorkspaceBackendType.PRIVILEGED,
        rootReference = "",
        readable = true,
        writable = true,
        quotaBytes = null,
        maxFileBytes = ShizukuWorkspaceFileStore.MAX_FILE_BYTES.toLong(),
        maxFiles = if (scope == WorkspaceScope.FULL_DEVICE_FILES) 100_000 else ShizukuWorkspaceFileStore.MAX_FILES,
        maxDirectoryEntries = ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES,
        enabled = true,
        scope = scope,
    )

    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
        CapabilityId(CapabilityId.FILE_LIST),
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
        CapabilityId(CapabilityId.FILE_WRITE_TEXT),
        CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
        CapabilityId(CapabilityId.FILE_MOVE),
        CapabilityId(CapabilityId.FILE_DELETE),
    )

    override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val path = normalize(request.relativePath, allowRoot = true) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val result = dispatch { bridge.dispatchWorkspaceList(workspaceHandle, path, request.maxEntries) }
        return parseList(result, path, request.maxEntries)
    }

    override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val path = normalize(request.relativePath, allowRoot = false) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return parseStat(dispatch { bridge.dispatchWorkspaceStat(workspaceHandle, path) }, path)
    }

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        val path = normalize(request.relativePath, allowRoot = false) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        if (request.maxBytes !in 1L..ShizukuWorkspaceFileStore.MAX_READ_BYTES.toLong()) {
            return failure(ToolErrorCode.FILE_TOO_LARGE)
        }
        return parseRead(dispatch { bridge.dispatchWorkspaceRead(workspaceHandle, path, request.maxBytes.toInt()) }, path, request.maxBytes)
    }

    override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val path = normalize(request.relativePath, allowRoot = false) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val content = strictUtf8(request.text) ?: return failure(ToolErrorCode.INVALID_REQUEST)
        if (content.size > ShizukuWorkspaceFileStore.MAX_FILE_BYTES) return failure(ToolErrorCode.FILE_TOO_LARGE)
        return parseMutation(
            dispatch { bridge.dispatchWorkspaceWrite(workspaceHandle, path, content, request.replace) },
            "write",
            path,
            WorkspaceEntryType.FILE,
            content.size.toLong(),
        )
    }

    override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val path = normalize(request.relativePath, allowRoot = false) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        return parseMutation(dispatch { bridge.dispatchWorkspaceMkdir(workspaceHandle, path) }, "mkdir", path, WorkspaceEntryType.DIRECTORY, null)
    }

    override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val path = normalize(request.relativePath, allowRoot = false) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val result = dispatch { bridge.dispatchWorkspaceDelete(workspaceHandle, path) }
        val payload = payload(result, "delete") ?: return dispatchFailure(result)
        val type = when (payload.optString("type", "")) {
            "file" -> WorkspaceEntryType.FILE
            "directory" -> WorkspaceEntryType.DIRECTORY
            else -> return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        }
        if (payload.optString("path", "") != path || !payload.optBoolean("deleted", false)) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        return WorkspaceResult.Success(WorkspaceMutation(path, type))
    }

    override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> {
        if (request.workspaceId != descriptor.id) return failure(ToolErrorCode.INVALID_REQUEST)
        if (request.expectedVersion != null) return failure(ToolErrorCode.CONFLICT)
        val source = normalize(request.sourcePath, allowRoot = false) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val destination = normalize(request.destinationPath, allowRoot = false) ?: return failure(ToolErrorCode.PATH_OUT_OF_SCOPE)
        val result = dispatch { bridge.dispatchWorkspaceMove(workspaceHandle, source, destination, replaceExisting = false) }
        val payload = payload(result, "move") ?: return dispatchFailure(result)
        val type = when (payload.optString("type", "")) {
            "file" -> WorkspaceEntryType.FILE
            "directory" -> WorkspaceEntryType.DIRECTORY
            else -> return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        }
        val bytes = payload.optLong("bytes", -1L)
        if (payload.optString("sourcePath", "") != source || payload.optString("destinationPath", "") != destination ||
            payload.optString("path", "") != destination || !payload.optBoolean("moved", false) || bytes < 0L
        ) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        return WorkspaceResult.Success(WorkspaceMutation(destination, type, bytes))
    }

    private fun parseList(result: ShizukuDispatchResult, path: String, maxEntries: Int): WorkspaceResult<WorkspaceListing> {
        val payload = payload(result, "list") ?: return dispatchFailure(result)
        if (payload.optString("path", "") != path) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        val items = payload.optJSONArray("entries") ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        if (items.length() > ShizukuWorkspaceFileStore.MAX_DIRECTORY_ENTRIES) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        val output = ArrayList<WorkspaceEntry>(minOf(maxEntries, items.length()))
        for (index in 0 until minOf(maxEntries, items.length())) {
            val item = items.optJSONObject(index) ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            val entryPath = normalize(item.optString("path", ""), allowRoot = false) ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            val type = when (item.optString("type", "")) {
                "file" -> WorkspaceEntryType.FILE
                "directory" -> WorkspaceEntryType.DIRECTORY
                else -> return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            }
            val bytes = item.optLong("bytes", 0L)
            if (bytes < 0L || bytes > ShizukuWorkspaceFileStore.MAX_FILE_BYTES || !isChild(entryPath, path)) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            output += WorkspaceEntry(entryPath, type, bytes)
        }
        return WorkspaceResult.Success(WorkspaceListing(path.ifEmpty { "." }, output, items.length() > output.size))
    }

    private fun parseStat(result: ShizukuDispatchResult, path: String): WorkspaceResult<WorkspaceFileStat> {
        val payload = payload(result, "stat") ?: return dispatchFailure(result)
        val type = when (payload.optString("type", "")) {
            "file" -> WorkspaceEntryType.FILE
            "directory" -> WorkspaceEntryType.DIRECTORY
            else -> return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        }
        val bytes = payload.optLong("bytes", -1L)
        if (payload.optString("path", "") != path || bytes < 0L || bytes > ShizukuWorkspaceFileStore.MAX_FILE_BYTES) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        return WorkspaceResult.Success(WorkspaceFileStat(path, type, bytes))
    }

    private fun parseRead(result: ShizukuDispatchResult, path: String, maxBytes: Long): WorkspaceResult<WorkspaceText> {
        val payload = payload(result, "read") ?: return dispatchFailure(result)
        val text = payload.optString("text", "")
        val bytes = payload.optLong("bytes", -1L)
        if (payload.optString("path", "") != path || bytes < 0L || bytes > maxBytes || strictUtf8(text)?.size?.toLong() != bytes) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        return WorkspaceResult.Success(WorkspaceText(path, text, bytes))
    }

    private fun parseMutation(result: ShizukuDispatchResult, operation: String, path: String, type: WorkspaceEntryType, expectedBytes: Long?): WorkspaceResult<WorkspaceMutation> {
        val payload = payload(result, operation) ?: return dispatchFailure(result)
        if (payload.optString("path", "") != path) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        if (expectedBytes != null && payload.optLong("bytes", -1L) != expectedBytes) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        if (operation == "mkdir" && !payload.has("created")) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        return WorkspaceResult.Success(WorkspaceMutation(path, type, expectedBytes ?: 0L))
    }

    private fun payload(result: ShizukuDispatchResult, operation: String): JSONObject? {
        if (result !is ShizukuDispatchResult.Success) return null
        val payload = runCatching { JSONObject(result.payload) }.getOrNull() ?: return null
        return payload.takeIf { it.optBoolean("ok", false) && it.optString("operation", "") == operation }
    }

    private fun dispatchFailure(result: ShizukuDispatchResult): WorkspaceResult.Failure = when (result) {
        is ShizukuDispatchResult.Denied -> WorkspaceResult.Failure(ToolError(ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE))
        is ShizukuDispatchResult.Failed -> WorkspaceResult.Failure(
            ToolError(if (result.unknownOutcome) ToolErrorCode.UNKNOWN_OUTCOME else mapError(result.errorCode)),
        )
        is ShizukuDispatchResult.Success -> WorkspaceResult.Failure(ToolError(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH))
    }

    private fun mapError(code: String?): ToolErrorCode = when (code) {
        ShizukuDirectoryHandleStore.PERMISSION_DENIED -> ToolErrorCode.SHIZUKU_PERMISSION_DENIED
        ShizukuDirectoryHandleStore.INVALID_HANDLE -> ToolErrorCode.INVALID_REQUEST
        ShizukuWorkspaceFileStore.INVALID_PATH,
        ShizukuWorkspaceFileStore.OUTSIDE_ROOT -> ToolErrorCode.PATH_OUT_OF_SCOPE
        ShizukuWorkspaceFileStore.SYMLINK_REJECTED -> ToolErrorCode.SYMLINK_FORBIDDEN
        ShizukuWorkspaceFileStore.NOT_FOUND -> ToolErrorCode.WORKSPACE_NOT_FOUND
        ShizukuWorkspaceFileStore.FILE_TOO_LARGE -> ToolErrorCode.FILE_TOO_LARGE
        ShizukuWorkspaceFileStore.LIMIT,
        ShizukuWorkspaceFileStore.OUTPUT_LIMIT -> ToolErrorCode.QUOTA_EXCEEDED
        ShizukuWorkspaceFileStore.UNKNOWN_OUTCOME -> ToolErrorCode.UNKNOWN_OUTCOME
        else -> ToolErrorCode.IO_ERROR
    }

    private fun dispatch(block: () -> ShizukuDispatchResult): ShizukuDispatchResult = try {
        block()
    } catch (_: RuntimeException) {
        ShizukuDispatchResult.Failed("Shizuku dispatch failed", unknownOutcome = true)
    }

    private fun normalize(raw: String?, allowRoot: Boolean): String? = runCatching {
        ShizukuWorkspacePathPolicy.parse(if (allowRoot && raw == null) "" else raw, allowRoot).joinToString("/")
    }.getOrNull()

    private fun isChild(path: String, parent: String): Boolean = parent.isEmpty() || path.startsWith("$parent/")

    private fun strictUtf8(value: String): ByteArray? = runCatching {
        value.toByteArray(StandardCharsets.UTF_8).also { bytes ->
            require(String(bytes, StandardCharsets.UTF_8) == value)
        }
    }.getOrNull()

    private fun <T> failure(code: ToolErrorCode): WorkspaceResult<T> = WorkspaceResult.Failure(ToolError(code))
}
