// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import runtime.mobileagent.bridge.BridgeProtocol

/**
 * A binding is deliberately scoped to one authenticated connection. Its
 * device path is held only in this desktop memory object and is never part of
 * the model-facing typed request or a diagnostic record.
 */
/**
 * Desktop-side durable record for one authenticated companion workspace.
 * The root path is intentionally never included in its string form; callers
 * should keep this object inside the companion/DPAPI boundary.
 */
data class WiredAdbPersistedWorkspaceBinding(
    val workspaceId: String,
    val rootPath: String,
    val fullDevice: Boolean,
    val scope: String,
) {
    override fun toString(): String =
        "WiredAdbPersistedWorkspaceBinding(workspaceId=$workspaceId, fullDevice=$fullDevice, scope=$scope, rootPath=<redacted>)"
}

/**
 * Durable companion store. Implementations must protect the root locator
 * mapping at rest (the production implementation uses Windows DPAPI).
 */
interface WiredAdbWorkspaceBindingStore {
    fun load(recoveryLocator: String): WiredAdbPersistedWorkspaceBinding?
    fun save(recoveryLocator: String, binding: WiredAdbPersistedWorkspaceBinding)
    fun remove(recoveryLocator: String)
}

/** JVM/in-memory seam for protocol tests; it is not a production persistence fallback. */
class InMemoryWiredAdbWorkspaceBindingStore : WiredAdbWorkspaceBindingStore {
    private val records = LinkedHashMap<String, WiredAdbPersistedWorkspaceBinding>()

    @Synchronized
    override fun load(recoveryLocator: String): WiredAdbPersistedWorkspaceBinding? = records[recoveryLocator]

    @Synchronized
    override fun save(recoveryLocator: String, binding: WiredAdbPersistedWorkspaceBinding) {
        check(records[recoveryLocator] == null) { "workspace recovery locator is already stored" }
        records[recoveryLocator] = binding
    }

    @Synchronized
    override fun remove(recoveryLocator: String) {
        records.remove(recoveryLocator)
    }
}

internal class WiredAdbWorkspaceBindingRegistry(
    sessionId: ByteArray,
    private val durableStore: WiredAdbWorkspaceBindingStore = InMemoryWiredAdbWorkspaceBindingStore(),
) : AutoCloseable {
    private val random = SecureRandom()
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
        scope: String,
        recoveryLocator: String,
    ): Boolean {
        check(!closed)
        require(binding.length == BridgeProtocol.WORKSPACE_BINDING_BYTES * 2)
        require(recoveryLocator.length == BridgeProtocol.WORKSPACE_RECOVERY_LOCATOR_BYTES * 2)
        val record = BoundWorkspace(workspaceId, binding, rootPath, fullDevice, scope, recoveryLocator)
        synchronized(this) {
            if (entries.containsKey(binding) || durableStore.load(recoveryLocator) != null) return false
            durableStore.save(
                recoveryLocator,
                WiredAdbPersistedWorkspaceBinding(workspaceId, rootPath, fullDevice, scope),
            )
            return entries.putIfAbsent(binding, record) == null
        }
    }

    /** Generates a fresh 256-bit locator; the path mapping is stored separately. */
    fun newRecoveryLocator(): String = ByteArray(BridgeProtocol.WORKSPACE_RECOVERY_LOCATOR_BYTES).also {
        random.nextBytes(it)
    }.let { bytes ->
        try {
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            java.util.Arrays.fill(bytes, 0)
        }
    }

    fun loadForReopen(recoveryLocator: String): WiredAdbPersistedWorkspaceBinding? =
        durableStore.load(recoveryLocator)

    /** Rebuilds a fresh connection-scoped binding from durable locator data. */
    fun registerReopened(
        workspaceId: String,
        binding: String,
        recoveryLocator: String,
        expectedScope: String,
    ): BoundWorkspace? {
        check(!closed)
        require(binding.length == BridgeProtocol.WORKSPACE_BINDING_BYTES * 2)
        require(recoveryLocator.length == BridgeProtocol.WORKSPACE_RECOVERY_LOCATOR_BYTES * 2)
        synchronized(this) {
            if (entries.containsKey(binding) || entries.values.any { it.recoveryLocator == recoveryLocator }) return null
            val persisted = durableStore.load(recoveryLocator) ?: return null
            if (persisted.workspaceId != workspaceId || persisted.scope != expectedScope) return null
            val record = BoundWorkspace(
                workspaceId = workspaceId,
                binding = binding,
                rootPath = persisted.rootPath,
                fullDevice = persisted.fullDevice,
                scope = persisted.scope,
                recoveryLocator = recoveryLocator,
            )
            return if (entries.putIfAbsent(binding, record) == null) record else null
        }
    }

    fun lookup(workspaceId: String, binding: String): BoundWorkspace? =
        if (closed) null else entries[binding]?.takeIf { it.workspaceId == workspaceId }

    fun remove(workspaceId: String, binding: String): Boolean =
        if (closed) false else synchronized(this) {
            val current = entries[binding] ?: return@synchronized false
            if (current.workspaceId != workspaceId) return@synchronized false
            val removed = entries.remove(binding) ?: return@synchronized false
            durableStore.remove(removed.recoveryLocator)
            true
        }

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
        val scope: String,
        val recoveryLocator: String,
    )
}
