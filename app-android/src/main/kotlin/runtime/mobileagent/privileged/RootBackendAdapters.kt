// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.RootConfinementRejection
import runtime.mobileagent.domain.RootFileEntryKind
import runtime.mobileagent.domain.RootFileMetadataSnapshot
import runtime.mobileagent.domain.RootFileOperationResult
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.skills.tooling.FullDeviceFilesGrantStore
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecResult
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellExecutor
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.TypedAuthorityWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.workspace.WorkspaceVersionProjection

/**
 * Controlled Root [ShellExecutor] bound to a single Root authority channel
 * ([Authority.ROOT_DIRECT] or [Authority.SHIZUKU_ROOT]).
 */
class RootControlledShellExecutor(
    private val authority: Authority,
    private val coordinator: RootRuntimeCoordinator,
) : ShellExecutor {
    init {
        require(authority.isRootAuthority) { "Expected Root authority: $authority" }
    }

    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
        if (request.selectedAuthority != authority) {
            return ShellExecResult(
                status = ShellExecutionStatus.FAILED,
                authority = authority,
                requestId = request.requestId,
                error = ToolError(ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED),
            )
        }
        return coordinator.executeControlledShell(authority, request)
    }

    override suspend fun cancel(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        return coordinator.cancelControlledShell(authority, requestId)
    }
}

/**
 * Controlled Root [WorkspaceBackend] executing FD-confined file operations through
 * [RootRuntimeCoordinator.createWorkspaceFileExecutor].
 */
class RootControlledWorkspaceBackend(
    private val authority: Authority,
    workspaceId: String,
    displayName: String,
    scope: WorkspaceScope,
    private val workspaceRootPath: Path,
    private val coordinator: RootRuntimeCoordinator,
) : WorkspaceBackend {
    override val descriptor: WorkspaceDescriptor = WorkspaceDescriptor(
        id = workspaceId,
        displayName = displayName,
        backendType = WorkspaceBackendType.PRIVILEGED,
        rootReference = "authority:${authority.name}",
        readable = true,
        writable = true,
        enabled = true,
        scope = scope,
    )

    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_WRITE_TEXT),
    )

    /**
     * Conditional replacement needs a directory-entry CAS that only a UID0-exclusive directory
     * provides. A Root workspace root may be an app-owned private directory (the user can pick
     * one) or silently contain one, so the workspace-level capability table must not advertise
     * it. The executor still performs conditional writes on UID0-exclusive paths, and refuses
     * app-private ones with WORKSPACE_VERSION_UNSUPPORTED before any mutation.
     */
    override val expectedVersionCapabilities: Set<CapabilityId> = emptySet()

    private val observedDriverVersions = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, String>()

    fun resolveDriverVersion(relativePath: String, publicVersion: Long): String? =
        observedDriverVersions[relativePath to publicVersion]

    override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> {
        if (!coordinator.isAuthorityReadyForDispatch(authority)) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE))
        }
        val args = runCatching {
            RootTypedArguments.FileGetMetadata(
                workspaceId = request.workspaceId,
                relativePath = request.relativePath,
            )
        }.getOrNull() ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        val result = coordinator.executeReceiptedFileGetMetadata(authority, workspaceRootPath, args)
            ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE))
        return when (result) {
            is RootFileOperationResult.Succeeded -> {
                val stat = result.value.toFileStat()
                stat.version?.let { pub -> observedDriverVersions[result.value.relativePath to pub] = result.value.version }
                WorkspaceResult.Success(stat)
            }
            is RootFileOperationResult.PostMutationFailure ->
                WorkspaceResult.Failure(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
            is RootFileOperationResult.PermissionDenied ->
                WorkspaceResult.Failure(ToolError(ToolErrorCode.PERMISSION_DENIED))
            is RootFileOperationResult.UnsupportedAttributePreservation ->
                WorkspaceResult.Failure(ToolError(ToolErrorCode.CONFLICT))
            is RootFileOperationResult.Rejected -> mapRejection(result.rejection)
        }
    }

    override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> {
        if (!coordinator.isAuthorityReadyForDispatch(authority)) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE))
        }
        val driverExpectedVersion = when (val expectedPublic = request.expectedVersion) {
            null -> null
            else -> {
                if (!request.replace) {
                    return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
                }
                observedDriverVersions[request.relativePath to expectedPublic]
                    ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.CONFLICT))
            }
        }
        val utf8Bytes = request.text.toByteArray(StandardCharsets.UTF_8)
        val args = runCatching {
            RootTypedArguments.FileWriteText(
                workspaceId = request.workspaceId,
                relativePath = request.relativePath,
                contentSha256 = sha256Hex(utf8Bytes),
                contentByteLength = utf8Bytes.size,
                replaceExisting = request.replace,
                expectedVersion = driverExpectedVersion,
            )
        }.getOrNull() ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        val result = coordinator.executeReceiptedFileWriteText(authority, workspaceRootPath, args, utf8Bytes)
            ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE))
        return when (result) {
            is RootFileOperationResult.Succeeded -> {
                val pub = WorkspaceVersionProjection.toPublic(result.value.version)
                observedDriverVersions[result.value.relativePath to pub] = result.value.version
                WorkspaceResult.Success(
                    WorkspaceMutation(
                        relativePath = result.value.relativePath,
                        type = if (result.value.entryKind == RootFileEntryKind.DIRECTORY) {
                            WorkspaceEntryType.DIRECTORY
                        } else {
                            WorkspaceEntryType.FILE
                        },
                        byteSize = result.value.sizeBytes,
                        version = pub,
                    ),
                )
            }
            is RootFileOperationResult.PostMutationFailure ->
                WorkspaceResult.Failure(ToolError(ToolErrorCode.UNKNOWN_OUTCOME))
            is RootFileOperationResult.PermissionDenied ->
                WorkspaceResult.Failure(ToolError(ToolErrorCode.PERMISSION_DENIED))
            is RootFileOperationResult.UnsupportedAttributePreservation ->
                WorkspaceResult.Failure(ToolError(ToolErrorCode.CONFLICT))
            is RootFileOperationResult.Rejected -> mapRejection(result.rejection)
        }
    }

    private fun RootFileMetadataSnapshot.toFileStat(): WorkspaceFileStat = WorkspaceFileStat(
        relativePath = relativePath,
        type = if (entryKind == RootFileEntryKind.DIRECTORY) {
            WorkspaceEntryType.DIRECTORY
        } else {
            WorkspaceEntryType.FILE
        },
        sizeBytes = sizeBytes,
        version = WorkspaceVersionProjection.toPublic(version),
    )

    private fun <T> mapRejection(rejection: RootConfinementRejection): WorkspaceResult<T> =
        WorkspaceResult.Failure(RootBridgeErrorCode.fromConfinementRejection(rejection))

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}

object RootPrivilegedWorkspaceFactory {
    fun createWorkspaceBackend(
        authority: Authority,
        coordinator: RootRuntimeCoordinator,
        workspaceRootPath: Path = Paths.get("/data/local/tmp"),
        workspaceId: String = defaultWorkspaceId(authority),
        displayName: String = defaultDisplayName(authority),
        scope: WorkspaceScope = WorkspaceScope.SELECTED_DIRECTORY,
    ): WorkspaceBackend = RootControlledWorkspaceBackend(
        authority = authority,
        workspaceId = workspaceId,
        displayName = displayName,
        scope = scope,
        workspaceRootPath = workspaceRootPath,
        coordinator = coordinator,
    )

    fun createProvider(
        authority: Authority,
        coordinator: RootRuntimeCoordinator,
        fullDeviceGrantStore: FullDeviceFilesGrantStore?,
        workspaceRootPath: Path = Paths.get("/data/local/tmp"),
    ): PrivilegedWorkspaceProvider {
        val rootBackend = createWorkspaceBackend(
            authority = authority,
            coordinator = coordinator,
            workspaceRootPath = workspaceRootPath,
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        )
        val fullDeviceBackend = createWorkspaceBackend(
            authority = authority,
            coordinator = coordinator,
            workspaceRootPath = Paths.get("/"),
            workspaceId = "${defaultWorkspaceId(authority)}-device",
            displayName = "${defaultDisplayName(authority)} (Device)",
            scope = WorkspaceScope.FULL_DEVICE_FILES,
        )
        return TypedAuthorityWorkspaceProvider(
            authority = authority,
            rootBackend = rootBackend,
            fullDeviceBackend = fullDeviceBackend,
            fullDeviceGrantStore = fullDeviceGrantStore,
        )
    }

    private fun defaultWorkspaceId(authority: Authority): String = when (authority) {
        Authority.ROOT_DIRECT -> "root-direct-workspace"
        Authority.SHIZUKU_ROOT -> "shizuku-root-workspace"
        else -> error("Unsupported Root authority: $authority")
    }

    private fun defaultDisplayName(authority: Authority): String = when (authority) {
        Authority.ROOT_DIRECT -> "Root (su)"
        Authority.SHIZUKU_ROOT -> "Shizuku (Root)"
        else -> error("Unsupported Root authority: $authority")
    }
}
