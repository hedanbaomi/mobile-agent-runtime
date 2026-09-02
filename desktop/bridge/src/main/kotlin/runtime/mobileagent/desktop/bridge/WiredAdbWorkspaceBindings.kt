// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import runtime.mobileagent.bridge.BridgeProtocol

/**
 * A binding is deliberately scoped to one authenticated connection. Its
 * device path is held only in this desktop memory object and is never part of
 * the model-facing typed request or a diagnostic record.
 */
internal class WiredAdbWorkspaceBindingRegistry(sessionId: ByteArray) : AutoCloseable {
    private val sessionBinding = sessionId.copyOf().also {
        require(it.size == BridgeProtocol.SESSION_ID_BYTES)
    }
    private val entries = ConcurrentHashMap<String, BoundWorkspace>()
    @Volatile private var closed = false

    fun register(
        workspaceId: String,
        binding: String,
        rootPath: String,
        fullDevice: Boolean,
    ): Boolean {
        check(!closed)
        require(binding.length == BridgeProtocol.WORKSPACE_BINDING_BYTES * 2)
        val record = BoundWorkspace(workspaceId, binding, rootPath, fullDevice)
        return entries.putIfAbsent(binding, record) == null
    }

    fun lookup(workspaceId: String, binding: String): BoundWorkspace? =
        if (closed) null else entries[binding]?.takeIf { it.workspaceId == workspaceId }

    fun remove(workspaceId: String, binding: String): Boolean =
        if (closed) false else entries.remove(binding)?.workspaceId == workspaceId

    override fun close() {
        if (!closed) {
            closed = true
            entries.clear()
            Arrays.fill(sessionBinding, 0)
        }
    }

    /** Only a non-reversible digest is useful for debugging ownership tests. */
    fun sessionReference(): String = MessageDigest.getInstance("SHA-256")
        .digest(sessionBinding)
        .copyOfRange(0, 8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal data class BoundWorkspace(
        val workspaceId: String,
        val binding: String,
        val rootPath: String,
        val fullDevice: Boolean,
    )
}
