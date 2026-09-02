// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

/**
 * A directory handle is intentionally an object rather than a path string.
 * Implementations keep its authority/session binding private and invalidate
 * it when that binding closes. It has no serializable token or path property.
 */
abstract class WorkspaceDirectoryHandle protected constructor() {
    final override fun toString(): String = "WorkspaceDirectoryHandle"
}

data class WorkspaceDirectoryEntry(
    val name: String,
    val type: WorkspaceEntryType,
    val sizeBytes: Long? = null,
    val readable: Boolean = true,
    val writable: Boolean = false,
    val handle: WorkspaceDirectoryHandle? = null,
) {
    init {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\'))
        require(name != "." && name != ".." && !name.contains('\u0000'))
        require(sizeBytes == null || sizeBytes >= 0)
        require(type == WorkspaceEntryType.DIRECTORY || handle == null)
    }
}

data class WorkspaceDirectoryPage(
    val current: WorkspaceDirectoryHandle,
    val parent: WorkspaceDirectoryHandle?,
    val entries: List<WorkspaceDirectoryEntry>,
    val truncated: Boolean = false,
) {
    init {
        require(entries.size <= 100_000)
    }
}

data class WorkspaceBrowseRequest(
    val handle: WorkspaceDirectoryHandle,
    val maxEntries: Int = 256,
) {
    init { require(maxEntries in 1..100_000) }
}

data class WorkspaceAttachRequest(
    val workspaceId: String,
    val displayName: String,
    val directory: WorkspaceDirectoryHandle,
) {
    init {
        require(workspaceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")))
        require(displayName.isNotBlank() && displayName.length <= 256)
    }
}

/**
 * Opaque provider-owned bytes used to recover a privileged directory after a
 * process or Binder service restart.  The value is deliberately not a
 * String/path model: callers can only obtain a defensive copy for immediate
 * encryption/transport and can wipe the in-memory copy afterwards.
 *
 * The container is responsible for encrypting the bytes before persistence.
 * Implementations must keep the locator provider-specific and must never put
 * it in model-facing DTOs, logs, or error messages.
 */
class WorkspaceRecoveryLocator private constructor(
    bytes: ByteArray,
) : AutoCloseable {
    private val lock = Any()
    private val value = bytes.copyOf()
    private var cleared = false

    val sizeBytes: Int
        get() = synchronized(lock) { if (cleared) 0 else value.size }

    val isCleared: Boolean
        get() = synchronized(lock) { cleared }

    /** Returns a defensive copy for immediate encryption or provider use. */
    fun copyBytes(): ByteArray = synchronized(lock) {
        check(!cleared) { "Recovery locator is cleared" }
        value.copyOf()
    }

    /** Wipes this process-local copy; repeated calls are harmless. */
    fun clear() = synchronized(lock) {
        java.util.Arrays.fill(value, 0)
        cleared = true
    }

    override fun close() = clear()

    override fun toString(): String = "WorkspaceRecoveryLocator"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        const val MAX_BYTES: Int = 16 * 1024

        fun fromBytes(bytes: ByteArray): WorkspaceRecoveryLocator {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
                "Recovery locator size is invalid"
            }
            return WorkspaceRecoveryLocator(bytes)
        }
    }
}

/** Request to reopen a previously attached privileged directory. */
data class WorkspaceReattachRequest(
    val workspaceId: String,
    val displayName: String,
    val recoveryLocator: WorkspaceRecoveryLocator,
    val scope: WorkspaceScope = WorkspaceScope.SELECTED_DIRECTORY,
    /** Required when reopening a FULL_DEVICE_FILES attachment. */
    val grantRevision: Long? = null,
) {
    init {
        require(workspaceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")))
        require(displayName.isNotBlank() && displayName.length <= 256)
        if (scope == WorkspaceScope.FULL_DEVICE_FILES) require(grantRevision != null && grantRevision > 0)
    }
}

/** The operator has to persist this grant before a full-device backend opens. */
data class FullDeviceFilesGrant(
    val workspaceId: String,
    val revision: Long,
    val confirmedAtEpochMs: Long,
) {
    init {
        require(workspaceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")))
        require(revision > 0)
        require(confirmedAtEpochMs > 0)
    }
}

/**
 * Canonical persistence seam for the explicit high-risk full-device grant.
 * The implementation belongs to the container/database layer; this contract
 * deliberately carries no root path, URI, serial, or shell identity.
 */
interface FullDeviceFilesGrantStore {
    fun load(workspaceId: String): FullDeviceFilesGrant?
    fun save(grant: FullDeviceFilesGrant): WorkspaceResult<Unit>
    fun revoke(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit>
}

data class FullDeviceFilesRequest(
    val workspaceId: String,
    val displayName: String,
    val grantRevision: Long,
    val confirmedByUser: Boolean,
) {
    init {
        require(workspaceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")))
        require(displayName.isNotBlank() && displayName.length <= 256)
        require(grantRevision > 0)
    }
}

/** Runtime-only result used to register a backend without exposing its root. */
data class WorkspaceAttachment(
    val descriptor: WorkspaceDescriptor,
    val backend: WorkspaceBackend,
    /** Provider-owned locator, absent for non-recoverable/legacy backends. */
    val recoveryLocator: WorkspaceRecoveryLocator? = null,
) {
    init { require(descriptor.id == backend.descriptor.id) }

    fun descriptorForAgent(): WorkspaceDescriptor = descriptor.forAgent()
}

interface WorkspaceDirectoryBrowser {
    suspend fun root(maxEntries: Int = 256): WorkspaceResult<WorkspaceDirectoryPage>
    suspend fun browse(request: WorkspaceBrowseRequest): WorkspaceResult<WorkspaceDirectoryPage>
}

/**
 * Provider-neutral authority workspace seam. A concrete Shizuku or Wired ADB
 * adapter binds [authority] and supplies an already typed backend. Authority
 * selection is done by the container; this class never falls back between
 * providers and never turns shell execution into file access.
 */
interface PrivilegedWorkspaceProvider : AutoCloseable {
    val authority: Authority
    val directoryBrowser: WorkspaceDirectoryBrowser
    val supportsFullDeviceFiles: Boolean

    suspend fun attachDirectory(request: WorkspaceAttachRequest): WorkspaceResult<WorkspaceAttachment>

    /**
     * Reopens a privileged directory using a locator returned by a prior
     * [attachDirectory] call.  Implementations must create a new ephemeral
     * backend handle; they must never reuse the old service-session handle.
     * The default is fail-closed for providers without durable locators.
     */
    suspend fun reattachDirectory(request: WorkspaceReattachRequest): WorkspaceResult<WorkspaceAttachment> =
        reopenDirectory(request)

    /** Explicit alias for callers that use the provider's reopen terminology. */
    suspend fun reopenDirectory(request: WorkspaceReattachRequest): WorkspaceResult<WorkspaceAttachment> =
        WorkspaceResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE))

    suspend fun openFullDeviceFiles(request: FullDeviceFilesRequest): WorkspaceResult<WorkspaceAttachment>

    suspend fun revokeFullDeviceFiles(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit>
}

/**
 * Common typed browser/attach implementation shared by Shizuku and Wired
 * adapters. The supplied backend is the authority's typed root; all child
 * handles are session-bound objects and are never represented as strings.
 */
class TypedAuthorityWorkspaceProvider(
    override val authority: Authority,
    private val rootBackend: WorkspaceBackend,
    private val fullDeviceBackend: WorkspaceBackend? = null,
    private val fullDeviceGrantStore: FullDeviceFilesGrantStore? = null,
) : PrivilegedWorkspaceProvider, WorkspaceDirectoryBrowser {
    private val owner = Any()
    private val rootHandle = DirectoryHandle(owner, "", null)
    @Volatile private var closed = false

    init {
        require(authority != Authority.NONE)
        require(rootBackend.descriptor.enabled)
        require(rootBackend.descriptor.backendType == WorkspaceBackendType.PRIVILEGED)
        if (fullDeviceBackend != null) {
            require(fullDeviceBackend.descriptor.enabled)
            require(fullDeviceBackend.descriptor.backendType == WorkspaceBackendType.PRIVILEGED)
        }
    }

    override val directoryBrowser: WorkspaceDirectoryBrowser get() = this

    override val supportsFullDeviceFiles: Boolean
        get() = !closed && fullDeviceBackend != null && fullDeviceGrantStore != null

    override suspend fun root(maxEntries: Int): WorkspaceResult<WorkspaceDirectoryPage> =
        browse(WorkspaceBrowseRequest(rootHandle, maxEntries))

    override suspend fun browse(request: WorkspaceBrowseRequest): WorkspaceResult<WorkspaceDirectoryPage> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val handle = request.handle as? DirectoryHandle
            ?: return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.owner !== owner) return failure(ToolErrorCode.INVALID_REQUEST)
        val result = try {
            rootBackend.list(
                WorkspaceListRequest(
                    workspaceId = rootBackend.descriptor.id,
                    relativePath = handle.relativePath.takeIf { it.isNotEmpty() },
                    maxEntries = request.maxEntries,
                ),
            )
        } catch (_: RuntimeException) {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
        return when (result) {
            is WorkspaceResult.Failure -> result
            is WorkspaceResult.Success -> page(handle, result.value, request.maxEntries)
        }
    }

    override suspend fun attachDirectory(request: WorkspaceAttachRequest): WorkspaceResult<WorkspaceAttachment> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        val handle = request.directory as? DirectoryHandle
            ?: return failure(ToolErrorCode.INVALID_REQUEST)
        if (handle.owner !== owner) return failure(ToolErrorCode.INVALID_REQUEST)
        return WorkspaceResult.Success(
            WorkspaceAttachment(
                descriptor = scopedDescriptor(request.workspaceId, request.displayName, WorkspaceScope.SELECTED_DIRECTORY),
                backend = PathScopedWorkspaceBackend(
                    delegate = rootBackend,
                    workspaceId = request.workspaceId,
                    displayName = request.displayName,
                    prefix = handle.relativePath,
                    scope = WorkspaceScope.SELECTED_DIRECTORY,
                ),
            ),
        )
    }

    override suspend fun openFullDeviceFiles(request: FullDeviceFilesRequest): WorkspaceResult<WorkspaceAttachment> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        if (!request.confirmedByUser) return failure(ToolErrorCode.CAPABILITY_DENIED)
        val backend = fullDeviceBackend ?: return unsupportedScope()
        val store = fullDeviceGrantStore ?: return unsupportedScope()
        val grant = try { store.load(request.workspaceId) } catch (_: RuntimeException) {
            return failure(ToolErrorCode.UNKNOWN_OUTCOME)
        } ?: return failure(ToolErrorCode.AUTHORITY_NOT_GRANTED)
        if (grant.revision != request.grantRevision) return failure(ToolErrorCode.CONFLICT)
        return WorkspaceResult.Success(
            WorkspaceAttachment(
                descriptor = scopedDescriptor(request.workspaceId, request.displayName, WorkspaceScope.FULL_DEVICE_FILES),
                backend = PathScopedWorkspaceBackend(
                    delegate = backend,
                    workspaceId = request.workspaceId,
                    displayName = request.displayName,
                    prefix = "",
                    scope = WorkspaceScope.FULL_DEVICE_FILES,
                ),
            ),
        )
    }

    override suspend fun revokeFullDeviceFiles(
        workspaceId: String,
        expectedRevision: Long,
    ): WorkspaceResult<Unit> {
        if (closed) return failure(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
        if (fullDeviceGrantStore == null) return unsupportedScope()
        return try {
            fullDeviceGrantStore.revoke(workspaceId, expectedRevision)
        } catch (_: RuntimeException) {
            failure(ToolErrorCode.UNKNOWN_OUTCOME)
        }
    }

    override fun close() {
        closed = true
    }

    private fun page(
        handle: DirectoryHandle,
        listing: WorkspaceListing,
        maxEntries: Int,
    ): WorkspaceResult<WorkspaceDirectoryPage> {
        val responsePath = canonicalRootRelative(listing.relativePath) ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        if (responsePath != handle.relativePath) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        if (listing.entries.size > 100_000) return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
        val output = ArrayList<WorkspaceDirectoryEntry>(listing.entries.size)
        for (entry in listing.entries) {
            val path = runCatching { WorkspacePath.normalize(entry.relativePath) }.getOrNull()
                ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            val child = childName(handle.relativePath, path)
                ?: return failure(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)
            output += WorkspaceDirectoryEntry(
                name = child,
                type = entry.type,
                sizeBytes = entry.sizeBytes,
                handle = if (entry.type == WorkspaceEntryType.DIRECTORY) {
                    DirectoryHandle(owner, path, handle)
                } else {
                    null
                },
            )
        }
        return WorkspaceResult.Success(
            WorkspaceDirectoryPage(
                current = handle,
                parent = handle.parent,
                entries = output.take(maxEntries),
                truncated = listing.truncated || output.size > maxEntries,
            ),
        )
    }

    private fun scopedDescriptor(
        workspaceId: String,
        displayName: String,
        scope: WorkspaceScope,
    ): WorkspaceDescriptor = rootBackend.descriptor.copy(
        id = workspaceId,
        displayName = displayName,
        rootReference = "",
        scope = scope,
    )

    private fun childName(parent: String, child: String): String? {
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        if (!child.startsWith(prefix)) return null
        val name = child.removePrefix(prefix)
        return name.takeIf { it.isNotEmpty() && !it.contains('/') }
    }

    private class DirectoryHandle(
        val owner: Any,
        val relativePath: String,
        val parent: DirectoryHandle?,
    ) : WorkspaceDirectoryHandle()

    private fun <T> failure(code: ToolErrorCode): WorkspaceResult<T> =
        WorkspaceResult.Failure(ToolError(code))

    private fun <T> unsupportedScope(): WorkspaceResult<T> = WorkspaceResult.Failure(
        ToolError(
            ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            details = mapOf("reason" to "scope_unsupported"),
        ),
    )
}

private class PathScopedWorkspaceBackend(
    private val delegate: WorkspaceBackend,
    workspaceId: String,
    displayName: String,
    private val prefix: String,
    scope: WorkspaceScope,
) : WorkspaceBackend {
    override val descriptor: WorkspaceDescriptor = delegate.descriptor.copy(
        id = workspaceId,
        displayName = displayName,
        rootReference = "",
        scope = scope,
    )

    override val capabilities: Set<runtime.mobileagent.domain.CapabilityId> = delegate.capabilities

    override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> =
        delegate.list(
            WorkspaceListRequest(
                workspaceId = delegate.descriptor.id,
                relativePath = join(request.relativePath),
                maxEntries = request.maxEntries,
                cursor = request.cursor,
            ),
        ).mapValue { value ->
            val path = strip(value.relativePath, rootAllowed = true) ?: throw ProtocolShapeException()
            val entries = value.entries.map { entry ->
                WorkspaceEntry(
                    relativePath = strip(entry.relativePath, rootAllowed = false) ?: throw ProtocolShapeException(),
                    type = entry.type,
                    sizeBytes = entry.sizeBytes,
                    version = entry.version,
                )
            }
            WorkspaceListing(path.ifEmpty { "." }, entries, value.truncated, value.nextCursor)
        }

    override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> =
        delegate.stat(WorkspaceStatRequest(delegate.descriptor.id, joinRequired(request.relativePath))).mapValue { value ->
            value.copy(relativePath = strip(value.relativePath, rootAllowed = false) ?: throw ProtocolShapeException())
        }

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
        delegate.readText(
            WorkspaceReadTextRequest(
                workspaceId = delegate.descriptor.id,
                relativePath = joinRequired(request.relativePath),
                maxBytes = request.maxBytes,
                offsetBytes = request.offsetBytes,
            ),
        ).mapValue { value ->
            value.copy(relativePath = strip(value.relativePath, rootAllowed = false) ?: throw ProtocolShapeException())
        }

    override suspend fun applyPatch(request: WorkspaceApplyPatchRequest): WorkspaceResult<WorkspaceMutation> =
        delegate.applyPatch(
            WorkspaceApplyPatchRequest(
                workspaceId = delegate.descriptor.id,
                relativePath = joinRequired(request.relativePath),
                patch = request.patch,
                expectedVersion = request.expectedVersion,
                format = request.format,
            ),
        ).mapValue { value ->
            value.copy(relativePath = strip(value.relativePath, rootAllowed = false) ?: throw ProtocolShapeException())
        }

    override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> =
        delegate.writeText(
            WorkspaceWriteTextRequest(delegate.descriptor.id, joinRequired(request.relativePath), request.text, request.replace, request.expectedVersion),
        ).mapValue { value ->
            value.copy(relativePath = strip(value.relativePath, rootAllowed = false) ?: throw ProtocolShapeException())
        }

    override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> =
        delegate.createDirectory(
            WorkspaceCreateDirectoryRequest(delegate.descriptor.id, joinRequired(request.relativePath), request.expectedVersion),
        ).mapValue { value ->
            value.copy(relativePath = strip(value.relativePath, rootAllowed = false) ?: throw ProtocolShapeException())
        }

    override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> =
        delegate.move(
            WorkspaceMoveRequest(delegate.descriptor.id, joinRequired(request.sourcePath), joinRequired(request.destinationPath), request.expectedVersion),
        ).mapValue { value ->
            value.copy(relativePath = strip(value.relativePath, rootAllowed = false) ?: throw ProtocolShapeException())
        }

    override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> =
        delegate.delete(
            WorkspaceDeleteRequest(delegate.descriptor.id, joinRequired(request.relativePath), request.expectedVersion),
        ).mapValue { value ->
            value.copy(relativePath = strip(value.relativePath, rootAllowed = false) ?: throw ProtocolShapeException())
        }

    private fun join(path: String?): String? {
        val normalized = path?.let { WorkspacePath.normalize(it) }
        return when {
            prefix.isEmpty() -> normalized
            normalized == null -> prefix
            else -> WorkspacePath.normalize("$prefix/$normalized")
        }
    }

    private fun joinRequired(path: String): String = join(path) ?: throw IllegalArgumentException("path is required")

    private fun strip(raw: String, rootAllowed: Boolean): String? {
        val normalized = if (raw == "." || raw.isEmpty()) "" else runCatching { WorkspacePath.normalize(raw) }.getOrNull() ?: return null
        if (prefix.isEmpty()) return normalized.takeIf { rootAllowed || it.isNotEmpty() }
        if (normalized == prefix) return "".takeIf { rootAllowed }
        val marker = "$prefix/"
        if (!normalized.startsWith(marker)) return null
        return normalized.removePrefix(marker).takeIf { it.isNotEmpty() }
    }

    private class ProtocolShapeException : RuntimeException()
}

private inline fun <T, R> WorkspaceResult<T>.mapValue(transform: (T) -> R): WorkspaceResult<R> = when (this) {
    is WorkspaceResult.Failure -> this
    is WorkspaceResult.Success -> runCatching { WorkspaceResult.Success(transform(value)) }
        .getOrElse { WorkspaceResult.Failure(ToolError(ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH)) }
}

private fun canonicalRootRelative(raw: String): String? = when {
    raw.isEmpty() || raw == "." -> ""
    else -> runCatching { WorkspacePath.normalize(raw) }.getOrNull()
}
