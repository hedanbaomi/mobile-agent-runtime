// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.text.Normalizer
import java.nio.charset.StandardCharsets
import runtime.mobileagent.domain.Workspace
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor

/**
 * Maps an opaque workspace id to a backend adapter.  The registry is the only
 * place where a backend instance/root reference is retained; model-facing
 * descriptors are returned through [WorkspaceDescriptor.forAgent].
 */
class WorkspaceRegistry {
    private val lock = Any()
    private val entries = linkedMapOf<String, RegisteredWorkspace>()

    fun register(descriptor: WorkspaceDescriptor, backend: WorkspaceBackend): Boolean {
        validateDescriptor(descriptor, backend)
        synchronized(lock) {
            if (entries.containsKey(descriptor.id)) return false
            entries[descriptor.id] = RegisteredWorkspace(descriptor, backend)
            return true
        }
    }

    fun register(workspace: Workspace, backend: WorkspaceBackend): Boolean = register(workspace.toDescriptor(), backend)

    fun registerOrReplace(descriptor: WorkspaceDescriptor, backend: WorkspaceBackend): Boolean {
        validateDescriptor(descriptor, backend)
        synchronized(lock) {
            val replaced = entries.put(descriptor.id, RegisteredWorkspace(descriptor, backend)) != null
            return replaced
        }
    }

    fun registerOrReplace(workspace: Workspace, backend: WorkspaceBackend): Boolean = registerOrReplace(workspace.toDescriptor(), backend)

    fun unregister(workspaceId: String): Boolean = synchronized(lock) { entries.remove(workspaceId) != null }

    /** Internal descriptor; callers exposing it should call [forAgent]. */
    fun descriptor(workspaceId: String): WorkspaceDescriptor? = synchronized(lock) {
        entries[workspaceId]?.descriptor?.forAgent()
    }

    fun descriptors(): List<WorkspaceDescriptor> = synchronized(lock) {
        entries.values.map { it.descriptor.forAgent() }
    }

    internal fun registered(workspaceId: String): RegisteredWorkspace? = synchronized(lock) { entries[workspaceId] }

    internal fun internalDescriptor(workspaceId: String): WorkspaceDescriptor? = synchronized(lock) {
        entries[workspaceId]?.descriptor
    }

    internal fun validatePath(relativePath: String?, allowRoot: Boolean): String = WorkspacePathPolicy.normalize(relativePath, allowRoot)

    class RegisteredWorkspace internal constructor(
        val descriptor: WorkspaceDescriptor,
        val backend: WorkspaceBackend,
    )

    private fun validateDescriptor(descriptor: WorkspaceDescriptor, backend: WorkspaceBackend) {
        requireValidWorkspaceId(descriptor.id)
        require(backend.descriptor.id == descriptor.id) { "Workspace backend id does not match descriptor" }
    }

    companion object {
        const val MAX_WORKSPACE_ID_BYTES = 128

        private fun requireValidWorkspaceId(id: String) {
            require(id.isNotBlank()) { "workspace id is blank" }
            require(id.toByteArray(StandardCharsets.UTF_8).size <= MAX_WORKSPACE_ID_BYTES) { "workspace id is too long" }
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}"))) { "workspace id is not opaque" }
        }
    }
}

private fun Workspace.toDescriptor(): WorkspaceDescriptor = WorkspaceDescriptor(
    id = id,
    displayName = displayName,
    backendType = backendType,
    rootReference = rootReference,
    readable = readable,
    writable = writable,
    quotaBytes = quotaBytes,
    maxFileBytes = maxFileBytes,
    enabled = enabled,
    scope = scope,
)

/** Adapter path guard; canonical result is the shared WorkspacePath String. */
object WorkspacePathPolicy {
    const val MAX_PATH_BYTES = 512
    const val MAX_SEGMENT_BYTES = 120
    const val MAX_DEPTH = 16

    fun normalize(raw: String?, allowRoot: Boolean): String {
        val value = Normalizer.normalize(raw ?: "", Normalizer.Form.NFC)
        if (value.isEmpty()) {
            if (allowRoot) return ""
            throw WorkspacePathException(runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE)
        }
        // The shared normalizer canonicalizes backslashes, but the v2 wire
        // contract rejects them so callers cannot smuggle a platform path.
        if (value.contains('\\') || value.indexOf('\u0000') >= 0 || value.startsWith('/') || value.endsWith('/')) {
            throw WorkspacePathException(runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE)
        }
        if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_PATH_BYTES) {
            throw WorkspacePathException(runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE)
        }
        val segments = value.split('/')
        if (segments.size > MAX_DEPTH || segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw WorkspacePathException(runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE)
        }
        if (segments.any { it.contains(':') || it.toByteArray(StandardCharsets.UTF_8).size > MAX_SEGMENT_BYTES }) {
            throw WorkspacePathException(runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE)
        }
        if (segments.any { segment -> segment.any { it.code < 0x20 || it == 0x7f.toChar() } }) {
            throw WorkspacePathException(runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE)
        }
        // Invoke the canonical shared normalizer as a final lexical check.
        return runCatching { runtime.mobileagent.skills.tooling.WorkspacePath.normalize(value, MAX_DEPTH) }
            .getOrElse { throw WorkspacePathException(runtime.mobileagent.skills.tooling.ToolErrorCode.PATH_OUT_OF_SCOPE) }
    }
}

class WorkspacePathException(val code: runtime.mobileagent.skills.tooling.ToolErrorCode) : IllegalArgumentException(code.name)
