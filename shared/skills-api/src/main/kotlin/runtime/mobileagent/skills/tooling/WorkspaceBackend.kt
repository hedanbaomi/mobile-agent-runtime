// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import java.nio.charset.StandardCharsets
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.WorkspaceBackendType as DomainWorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

/** Backend identities are implementation details, never model tool names. */
typealias WorkspaceBackendType = DomainWorkspaceBackendType
typealias WorkspaceType = WorkspaceBackendType

data class WorkspaceDescriptor(
    val id: String,
    val displayName: String,
    val backendType: WorkspaceBackendType,
    /** Internal-only reference; adapters must not put it in the model schema. */
    val rootReference: String = "",
    val readable: Boolean = true,
    val writable: Boolean = false,
    val quotaBytes: Long? = null,
    val maxFileBytes: Long = 1L * 1024L * 1024L,
    val maxFiles: Int = 5_000,
    val maxDirectoryEntries: Int = 1_000,
    val enabled: Boolean = true,
    val scope: WorkspaceScope = WorkspaceScope.SELECTED_DIRECTORY,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(quotaBytes == null || quotaBytes >= 0)
        require(maxFileBytes >= 0)
        require(maxFiles >= 0)
        require(maxDirectoryEntries >= 0)
    }

    /** A descriptor safe to expose alongside a model-facing tool result. */
    fun forAgent(): WorkspaceDescriptor = copy(rootReference = "")
}

enum class WorkspaceEntryType {
    FILE,
    DIRECTORY,
}

data class WorkspaceEntry(
    val relativePath: String,
    val type: WorkspaceEntryType,
    val sizeBytes: Long,
    val version: Long? = null,
) {
    init {
        require(relativePath.isNotBlank())
        require(sizeBytes >= 0)
    }
}

data class WorkspaceListing(
    val relativePath: String,
    val entries: List<WorkspaceEntry>,
    val truncated: Boolean = false,
) {
    init {
        require(relativePath.isNotEmpty())
        require(entries.size <= 100_000)
    }
}

data class WorkspaceFileStat(
    val relativePath: String,
    val type: WorkspaceEntryType,
    val sizeBytes: Long,
    val version: Long? = null,
) {
    init {
        require(relativePath.isNotBlank())
        require(sizeBytes >= 0)
    }
}

data class WorkspaceText(
    val relativePath: String,
    val text: String,
    val byteSize: Long = text.toByteArray(StandardCharsets.UTF_8).size.toLong(),
    val version: Long? = null,
) {
    init {
        require(relativePath.isNotBlank())
        require(byteSize >= 0)
        require(byteSize == text.toByteArray(StandardCharsets.UTF_8).size.toLong())
    }
}

data class WorkspaceMutation(
    val relativePath: String,
    val type: WorkspaceEntryType,
    val byteSize: Long = 0,
    val version: Long? = null,
) {
    init {
        require(relativePath.isNotBlank())
        require(byteSize >= 0)
    }
}

data class WorkspaceListRequest(
    val workspaceId: String,
    val relativePath: String? = null,
    val maxEntries: Int = 256,
) {
    init {
        require(workspaceId.isNotBlank())
        require(relativePath?.let { runCatching { WorkspacePath.normalize(it) }.isSuccess } ?: true)
        require(maxEntries in 1..100_000)
    }
}

data class WorkspaceStatRequest(
    val workspaceId: String,
    val relativePath: String,
) {
    init {
        require(workspaceId.isNotBlank())
        WorkspacePath.normalize(relativePath)
    }
}

data class WorkspaceReadTextRequest(
    val workspaceId: String,
    val relativePath: String,
    val maxBytes: Long = 1L * 1024L * 1024L,
) {
    init {
        require(workspaceId.isNotBlank())
        WorkspacePath.normalize(relativePath)
        require(maxBytes in 1..16L * 1024L * 1024L)
    }
}

data class WorkspaceWriteTextRequest(
    val workspaceId: String,
    val relativePath: String,
    val text: String,
    val replace: Boolean = true,
    val expectedVersion: Long? = null,
) {
    init {
        require(workspaceId.isNotBlank())
        WorkspacePath.normalize(relativePath)
        require(!text.contains('\u0000'))
        require(expectedVersion == null || expectedVersion >= 0)
    }
}

data class WorkspaceCreateDirectoryRequest(
    val workspaceId: String,
    val relativePath: String,
    val expectedVersion: Long? = null,
) {
    init {
        require(workspaceId.isNotBlank())
        WorkspacePath.normalize(relativePath)
        require(expectedVersion == null || expectedVersion >= 0)
    }
}

data class WorkspaceMoveRequest(
    val workspaceId: String,
    val sourcePath: String,
    val destinationPath: String,
    val expectedVersion: Long? = null,
) {
    init {
        require(workspaceId.isNotBlank())
        WorkspacePath.normalize(sourcePath)
        WorkspacePath.normalize(destinationPath)
        require(expectedVersion == null || expectedVersion >= 0)
    }
}

data class WorkspaceDeleteRequest(
    val workspaceId: String,
    val relativePath: String,
    val expectedVersion: Long? = null,
) {
    init {
        require(workspaceId.isNotBlank())
        WorkspacePath.normalize(relativePath)
        require(expectedVersion == null || expectedVersion >= 0)
    }
}

/** Typed result shared by internal, SAF and privileged adapters. */
sealed interface WorkspaceResult<out T> {
    data class Success<T>(val value: T) : WorkspaceResult<T>
    data class Failure(val error: ToolError) : WorkspaceResult<Nothing>
}

typealias WorkspaceSuccess<T> = WorkspaceResult.Success<T>
typealias WorkspaceFailure = WorkspaceResult.Failure

/**
 * Pure Kotlin backend contract.  Platform adapters own URI/Binder/SAF details
 * outside this package; no Android type is present in the interface.
 */
interface WorkspaceBackend {
    val descriptor: WorkspaceDescriptor

    val capabilities: Set<CapabilityId>
        get() = setOf(
            CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
            CapabilityId(CapabilityId.FILE_LIST),
            CapabilityId(CapabilityId.FILE_STAT),
            CapabilityId(CapabilityId.FILE_READ_TEXT),
            CapabilityId(CapabilityId.FILE_WRITE_TEXT),
            CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
            CapabilityId(CapabilityId.FILE_MOVE),
            CapabilityId(CapabilityId.FILE_DELETE),
        )

    suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> =
        unsupported()

    suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> =
        unsupported()

    suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
        unsupported()

    suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> =
        unsupported()

    suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> =
        unsupported()

    suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> =
        unsupported()

    suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> =
        unsupported()

    private fun <T> unsupported(): WorkspaceResult<T> = WorkspaceResult.Failure(
        ToolError(ToolErrorCode.INTERNAL_ERROR, message = "Workspace operation is not implemented"),
    )

    suspend fun list(workspaceId: String, relativePath: String? = null, maxEntries: Int = 256): WorkspaceResult<WorkspaceListing> =
        list(WorkspaceListRequest(workspaceId, relativePath, maxEntries))

    suspend fun stat(workspaceId: String, relativePath: String): WorkspaceResult<WorkspaceFileStat> =
        stat(WorkspaceStatRequest(workspaceId, relativePath))

    suspend fun readText(workspaceId: String, relativePath: String, maxBytes: Long = 1L * 1024L * 1024L): WorkspaceResult<WorkspaceText> =
        readText(WorkspaceReadTextRequest(workspaceId, relativePath, maxBytes))

    suspend fun writeText(workspaceId: String, relativePath: String, text: String, replace: Boolean = true): WorkspaceResult<WorkspaceMutation> =
        writeText(WorkspaceWriteTextRequest(workspaceId, relativePath, text, replace))

    suspend fun createDirectory(workspaceId: String, relativePath: String): WorkspaceResult<WorkspaceMutation> =
        createDirectory(WorkspaceCreateDirectoryRequest(workspaceId, relativePath))
}
