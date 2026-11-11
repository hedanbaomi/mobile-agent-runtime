// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import runtime.mobileagent.data.AgentRepository
import runtime.mobileagent.data.CapabilityGrantRepository
import runtime.mobileagent.data.FullDeviceFilesGrantRepository
import runtime.mobileagent.data.PrivilegedWorkspaceBindingRepository
import runtime.mobileagent.data.SafWorkspaceGrantRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.diagnostics.AndroidDiagnosticLogger
import runtime.mobileagent.diagnostics.DiagnosticWorkspaceReattachPhase
import runtime.mobileagent.diagnostics.PrivilegedWorkspaceReattachRecord
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.SafGrantStatus
import runtime.mobileagent.domain.PrivilegedWorkspaceBinding
import runtime.mobileagent.domain.PrivilegedWorkspaceBindingStatus
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.security.PrivilegedWorkspaceBindingAad
import runtime.mobileagent.security.PrivilegedWorkspaceBindingOpenResult
import runtime.mobileagent.security.PrivilegedWorkspaceBindingSealResult
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.WorkspaceRegistry
import runtime.mobileagent.wired.WiredAdbAuthorityPort
import runtime.mobileagent.skills.tooling.PrivilegedWorkspaceProvider
import runtime.mobileagent.skills.tooling.WorkspaceRecoveryLocator
import runtime.mobileagent.skills.tooling.WorkspaceResult

/** Rebuilds runtime handles from durable grants; it never replays a tool operation. */
internal class RuntimeWorkspaceRecovery(
    private val appContext: Context,
    private val agents: AgentRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val safWorkspaceGrantRepository: SafWorkspaceGrantRepository,
    private val privilegedWorkspaceBindingRepository: PrivilegedWorkspaceBindingRepository,
    private val fullDeviceFilesGrantRepository: FullDeviceFilesGrantRepository,
    private val capabilityGrantRepository: CapabilityGrantRepository,
    private val workspaceRegistry: WorkspaceRegistry,
    private val authorityManager: AuthorityManager,
    private val wiredAuthority: WiredAdbAuthorityPort,
    private val bindingCipher: runtime.mobileagent.security.PrivilegedWorkspaceBindingCipher,
    private val appInstanceId: String,
    private val diagnostics: AndroidDiagnosticLogger,
    private val scope: CoroutineScope,
    private val closed: AtomicBoolean,
    private val authorityProviderFor: (Authority) -> PrivilegedWorkspaceProvider?,
) {
    private val reattachInFlight = ConcurrentHashMap.newKeySet<String>()

    fun hydrateWorkspaces() {
        adoptInternalWorkspace()
        adoptLegacyWorkspaces()
        workspaceRepository.list(enabledOnly = true)
            .filter { it.backendType == WorkspaceBackendType.SAF_TREE }
            .forEach { workspace ->
                val grant = safWorkspaceGrantRepository.get(workspace.id)
                if (grant != null && grant.status == SafGrantStatus.ACTIVE) {
                    val uri = grant.activePersistedUri(appContext)
                    if (uri != null) {
                        runCatching {
                            workspaceRegistry.registerOrReplace(
                                workspace,
                                runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, workspace.id),
                            )
                        }
                    } else {
                        safWorkspaceGrantRepository.markLost(workspace.id)
                    }
                }
            }
        // Authority root backends are navigation/attach providers, not Agent
        // workspaces.  Only a user-attached directory may enter the runtime
        // registry and become model-facing.
        privilegedWorkspaceBindingRepository.list()
            .filter { it.status != PrivilegedWorkspaceBindingStatus.REVOKED }
            .forEach { binding -> workspaceRegistry.unregister(binding.workspaceId) }
        // Registered privileged workspaces without an active binding carry no
        // recovery locator (for example full-device rows opened before bindings
        // were persisted for that scope).  They can never re-attach, so they
        // must not stay listed as available.  Wired-ADB workspaces are exempt:
        // they carry no sealed binding by contract and recover through their
        // own bridge reconnect, so a missing binding is normal there.
        workspaceRegistry.descriptors()
            .filter { it.backendType == WorkspaceBackendType.PRIVILEGED }
            .forEach { descriptor ->
                val authority = workspaceRepository.get(descriptor.id)
                    ?.rootReference?.removePrefix("authority:")
                    ?.let { runCatching { Authority.valueOf(it) }.getOrNull() }
                if (!missingBindingIsOrphan(authority, wiredAuthority is runtime.mobileagent.resident.ResidentAdbAuthorityBridge)) return@forEach
                val binding = privilegedWorkspaceBindingRepository.get(descriptor.id)
                if (binding == null || binding.status == PrivilegedWorkspaceBindingStatus.REVOKED) {
                    workspaceRegistry.unregister(descriptor.id)
                }
            }
        schedulePrivilegedWorkspaceReattach()
    }

    fun schedulePrivilegedWorkspaceReattach() {
        if (closed.get()) return
        val manager = authorityManager.state.value
        val selected = manager.selectedAuthority ?: return
        if (manager.statuses[selected]?.isReady != true) return
        privilegedWorkspaceBindingRepository.forAuthority(selected)
            .asSequence()
            .filter { binding ->
                binding.status !in setOf(
                    PrivilegedWorkspaceBindingStatus.REVOKED,
                    PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE,
                    PrivilegedWorkspaceBindingStatus.GRANT_LOST,
                ) &&
                    workspaceRegistry.registered(binding.workspaceId) == null &&
                    workspaceRepository.get(binding.workspaceId)?.enabled == true &&
                    (
                        binding.scope != WorkspaceScope.FULL_DEVICE_FILES ||
                            fullDeviceFilesGrantRepository.load(binding.workspaceId) != null
                        )
            }
            .forEach { binding ->
                if (!reattachInFlight.add(binding.workspaceId)) return@forEach
                scope.launch {
                    try {
                        reattachPrivilegedWorkspace(binding.workspaceId)
                    } finally {
                        reattachInFlight.remove(binding.workspaceId)
                    }
                }
            }
    }

    suspend fun reattachPrivilegedWorkspace(workspaceId: String) {
        val startedAt = System.currentTimeMillis()
        var binding = privilegedWorkspaceBindingRepository.get(workspaceId) ?: return
        val workspace = workspaceRepository.get(workspaceId) ?: return
        val authority = binding.authority
        val provider = authorityProviderFor(authority)
        if (provider == null) {
            markPrivilegedBindingStatus(binding, PrivilegedWorkspaceBindingStatus.UNAVAILABLE_AUTHORITY)
            return
        }
        binding = runCatching {
            privilegedWorkspaceBindingRepository.updateStatus(
                workspaceId,
                binding.revision,
                PrivilegedWorkspaceBindingStatus.REATTACHING,
            )
        }.getOrNull() ?: return
        diagnostics.recordPrivilegedWorkspaceReattachStarted(
            reattachDiagnostic(binding, DiagnosticWorkspaceReattachPhase.STARTED),
        )
        if (binding.aadAppInstanceId != appInstanceId || binding.aadWorkspaceId != workspaceId) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "BINDING_IDENTITY_MISMATCH", startedAt)
            return
        }
        val aad = runCatching {
            PrivilegedWorkspaceBindingAad(appInstanceId, workspaceId, authority, binding.locatorVersion)
        }.getOrNull()
        if (aad == null) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "BINDING_AAD_INVALID", startedAt)
            return
        }
        val opened = bindingCipher.open(binding.encryptedLocatorCopy(), binding.locatorNonceCopy(), aad)
        val recovered = when (opened) {
            is PrivilegedWorkspaceBindingOpenResult.Failure -> {
                failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, opened.error.code.name, startedAt)
                return
            }
            is PrivilegedWorkspaceBindingOpenResult.Success -> opened.locator
        }
        val locator = try {
            WorkspaceRecoveryLocator.fromBytes(recovered)
        } catch (_: RuntimeException) {
            recovered.fill(0)
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "LOCATOR_INVALID", startedAt)
            return
        } finally {
            recovered.fill(0)
        }
        val reopened = reopenRecoveredWorkspace(
            workspaceId = workspaceId,
            displayName = workspace.displayName,
            scope = binding.scope,
            locator = locator,
            provider = provider,
            fullDeviceGrantRevision = { fullDeviceFilesGrantRepository.load(workspaceId)?.revision },
        ) ?: run {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.GRANT_LOST, "GRANT_LOST", startedAt)
            return
        }
        val attachment = when (reopened) {
            is WorkspaceResult.Failure -> {
                val status = when (reopened.error.code) {
                    ToolErrorCode.WORKSPACE_NOT_FOUND -> PrivilegedWorkspaceBindingStatus.WORKSPACE_NOT_FOUND
                    ToolErrorCode.AUTHORITY_NOT_GRANTED,
                    ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
                    ToolErrorCode.CAPABILITY_DENIED,
                        -> PrivilegedWorkspaceBindingStatus.PERMISSION_DENIED
                    ToolErrorCode.CONFLICT,
                    ToolErrorCode.INVALID_REQUEST,
                    ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH,
                        -> PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE
                    else -> PrivilegedWorkspaceBindingStatus.UNAVAILABLE
                }
                failPrivilegedReattach(binding, status, reopened.error.code.name, startedAt)
                return
            }
            is WorkspaceResult.Success -> reopened.value
        }
        if (attachment.descriptor.id != workspaceId) {
            attachment.recoveryLocator?.clear()
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "WORKSPACE_ID_MISMATCH", startedAt)
            return
        }
        val freshLocator = attachment.recoveryLocator
        if (freshLocator == null) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "LOCATOR_MISSING", startedAt)
            return
        }
        val freshBytes = runCatching { freshLocator.copyBytes() }.getOrNull()
        if (freshBytes == null) {
            freshLocator.clear()
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, "LOCATOR_INVALID", startedAt)
            return
        }
        val envelope = try {
            when (val sealed = bindingCipher.seal(freshBytes, aad)) {
                is PrivilegedWorkspaceBindingSealResult.Success -> sealed.envelope
                is PrivilegedWorkspaceBindingSealResult.Failure -> {
                    failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.BINDING_UNRECOVERABLE, sealed.error.code.name, startedAt)
                    return
                }
            }
        } finally {
            freshBytes.fill(0)
            freshLocator.clear()
        }
        val active = try {
            privilegedWorkspaceBindingRepository.save(
                binding.copy(
                    encryptedLocator = envelope.encryptedLocator,
                    locatorNonce = envelope.locatorNonce,
                    status = PrivilegedWorkspaceBindingStatus.ACTIVE,
                    revision = binding.revision + 1L,
                    updatedAt = Utc.nowIso(),
                ),
            )
        } catch (_: RuntimeException) {
            failPrivilegedReattach(binding, PrivilegedWorkspaceBindingStatus.UNAVAILABLE, "PERSISTENCE_FAILED", startedAt)
            return
        }
        try {
            workspaceRegistry.registerOrReplace(workspace, attachment.backend)
        } catch (_: RuntimeException) {
            failPrivilegedReattach(active, PrivilegedWorkspaceBindingStatus.UNAVAILABLE, "REGISTRY_FAILED", startedAt)
            return
        }
        diagnostics.recordPrivilegedWorkspaceReattachSucceeded(
            reattachDiagnostic(
                active,
                DiagnosticWorkspaceReattachPhase.COMPLETED,
                durationMs = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    private fun markPrivilegedBindingStatus(
        binding: PrivilegedWorkspaceBinding,
        status: PrivilegedWorkspaceBindingStatus,
    ): PrivilegedWorkspaceBinding? = if (binding.status == status) {
        binding
    } else {
        runCatching {
            privilegedWorkspaceBindingRepository.updateStatus(binding.workspaceId, binding.revision, status)
        }.getOrNull()
    }

    private fun failPrivilegedReattach(
        binding: PrivilegedWorkspaceBinding,
        status: PrivilegedWorkspaceBindingStatus,
        errorCode: String,
        startedAt: Long,
    ) {
        workspaceRegistry.unregister(binding.workspaceId)
        val persisted = markPrivilegedBindingStatus(binding, status) ?: binding
        diagnostics.recordPrivilegedWorkspaceReattachFailed(
            reattachDiagnostic(
                persisted,
                DiagnosticWorkspaceReattachPhase.FAILED,
                durationMs = System.currentTimeMillis() - startedAt,
                errorCode = errorCode,
            ),
        )
    }

    private fun reattachDiagnostic(
        binding: PrivilegedWorkspaceBinding,
        phase: DiagnosticWorkspaceReattachPhase,
        durationMs: Long? = null,
        errorCode: String = "none",
    ): PrivilegedWorkspaceReattachRecord = PrivilegedWorkspaceReattachRecord(
        phase = phase,
        workspaceId = binding.workspaceId,
        authority = binding.authority.toDiagnostic(),
        bindingRevision = binding.revision.toDiagnosticGeneration(),
        grantGeneration = capabilityGrantRepository.forWorkspace(binding.workspaceId, includeRevoked = false)
            .maxOfOrNull { it.revision }
            ?.toDiagnosticGeneration()
            ?: 0,
        durationMs = durationMs,
        errorCode = errorCode.toDiagnosticErrorCode(),
    )

    private fun Long.toDiagnosticGeneration(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun String.toDiagnosticErrorCode(): String {
        val normalized = uppercase(Locale.ROOT)
            .map { character -> if (character.isLetterOrDigit() || character == '_') character else '_' }
            .joinToString("")
            .trim('_')
            .take(64)
        return normalized.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "UNKNOWN"
    }

    fun refreshSafWorkspace() {
        safWorkspaceGrantRepository.list(includeRevoked = true).forEach { grant ->
            val workspaceId = grant.workspaceId
            val uri = grant.activePersistedUri(appContext)
            if (uri != null) {
                val workspace = workspaceRepository.get(workspaceId)
                if (workspace != null && workspace.enabled) {
                    runCatching {
                        workspaceRegistry.registerOrReplace(
                            workspace,
                            runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createSaf(appContext, uri, workspaceId),
                        )
                    }.onFailure { workspaceRegistry.unregister(workspaceId) }
                } else if (workspace == null) {
                    // An active SAF row without its canonical workspace is an
                    // incomplete old transaction. Retain the row for user
                    // recovery, but do not leave it looking active to the
                    // runtime or to diagnostics.
                    safWorkspaceGrantRepository.markLost(workspaceId)
                    workspaceRegistry.unregister(workspaceId)
                }
            } else if (grant.status == SafGrantStatus.ACTIVE) {
                safWorkspaceGrantRepository.markLost(workspaceId)
                workspaceRegistry.unregister(workspaceId)
            } else if (grant.status == SafGrantStatus.REVOKED) {
                workspaceRegistry.unregister(workspaceId)
            }
        }
    }

    fun reconcileFullDeviceGrants() {
        fullDeviceFilesGrantRepository.activeWorkspaceIds().forEach { workspaceId ->
            val workspace = workspaceRepository.get(workspaceId)
            if (workspace == null || !workspace.enabled || workspace.scope != WorkspaceScope.FULL_DEVICE_FILES) {
                fullDeviceFilesGrantRepository.load(workspaceId)?.let { grant ->
                    fullDeviceFilesGrantRepository.revoke(workspaceId, grant.revision)
                }
            }
        }
    }

    fun adoptInternalWorkspace() {
        val id = RuntimeIntegration.INTERNAL_WORKSPACE_ID
        val existing = workspaceRepository.get(id)
        val defaultRoot = File(appContext.filesDir, "agent-workspace").toPath().toAbsolutePath().normalize()
        val root = existing?.takeIf { it.backendType == WorkspaceBackendType.INTERNAL }
            ?.rootReference?.let { safePrivatePath(it) } ?: defaultRoot
        runCatching { Files.createDirectories(root) }.getOrElse { return }
        val workspace = existing ?: Workspace(
            id = id,
            displayName = "Application workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = root.toString(),
            readable = true,
            writable = true,
            quotaBytes = 4L * 1024L * 1024L,
            maxFileBytes = 256L * 1024L,
            enabled = true,
        ).also(workspaceRepository::save)
        runCatching {
            workspaceRegistry.registerOrReplace(
                workspace,
                runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createInternal(root, id),
            )
        }
    }

    /**
     * Register another app-private workspace beside the canonical internal
     * root. Production UI does not create a second INTERNAL backend; tests use
     * this to prove a bound Thread cannot be rewritten onto a different
     * registered workspace.
     */
    internal fun registerAppPrivateWorkspace(workspaceId: String, root: Path): Workspace {
        require(workspaceId != RuntimeIntegration.INTERNAL_WORKSPACE_ID) {
            "The canonical app-private workspace is adopted by the host"
        }
        runCatching { Files.createDirectories(root) }.getOrElse {
            error("App-private workspace root is unavailable")
        }
        val existing = workspaceRepository.get(workspaceId)
        val workspace = Workspace(
            id = workspaceId,
            displayName = existing?.displayName?.takeIf { it.isNotBlank() } ?: "Application workspace",
            backendType = WorkspaceBackendType.INTERNAL,
            rootReference = root.toAbsolutePath().normalize().toString(),
            readable = true,
            writable = true,
            quotaBytes = existing?.quotaBytes ?: (4L * 1024L * 1024L),
            maxFileBytes = existing?.maxFileBytes ?: (256L * 1024L),
            enabled = true,
            revision = existing?.revision ?: 0,
            createdAt = existing?.createdAt.orEmpty(),
            updatedAt = existing?.updatedAt.orEmpty(),
            scope = WorkspaceScope.SELECTED_DIRECTORY,
        )
        val persisted = workspaceRepository.save(workspace)
        workspaceRegistry.registerOrReplace(
            persisted,
            runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createInternal(root, persisted.id),
        )
        return persisted
    }

    /** Map the old snapshot namespace to an opaque, neutral workspace id. */
    private fun adoptLegacyWorkspaces() {
        val legacyParent = File(appContext.filesDir, "agent-workspaces").toPath()
        val snapshots = runCatching { agents.listSnapshots() }.getOrDefault(emptyList())
        snapshots.forEach { snapshot ->
            val namespace = legacyNamespace(snapshot)
            val root = legacyParent.resolve(namespace).normalize()
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@forEach
            val id = "legacy-" + sha256("legacy-workspace-id\u0000${snapshot.agentId}\u0000${snapshot.id}").take(48)
            val existing = workspaceRepository.get(id)
            val workspace = existing ?: Workspace(
                id = id,
                displayName = "Legacy application workspace",
                backendType = WorkspaceBackendType.INTERNAL,
                rootReference = root.toString(),
                readable = true,
                writable = true,
                quotaBytes = 4L * 1024L * 1024L,
                maxFileBytes = 256L * 1024L,
                enabled = true,
            ).also(workspaceRepository::save)
            runCatching {
                workspaceRegistry.registerOrReplace(
                    workspace,
                    runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter.createInternal(root, id),
                )
            }
        }
    }


    private fun safePrivatePath(raw: String): Path? = runCatching {
        val candidate = File(raw).toPath().toAbsolutePath().normalize()
        val privateRoot = appContext.filesDir.toPath().toAbsolutePath().normalize()
        candidate.takeIf { it.startsWith(privateRoot) }
    }.getOrNull()

    private fun legacyNamespace(snapshot: AgentSnapshot): String =
        sha256("mobile-agent-runtime/workspace-v1\n${snapshot.agentId}\n${snapshot.id}")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

}
