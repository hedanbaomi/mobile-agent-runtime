// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/**
 * Capture the currently approved MCP tool list for a real Agent snapshot.
 * A missing endpoint, discovery, grant, or persisted snapshot binding returns
 * null and therefore cannot silently enable network tools.
 */
fun captureMcpSnapshot(container: AppContainer, snapshotId: String, agentId: String): McpSnapshot? {
    if (snapshotId.isBlank() || agentId.isBlank()) return null
    val agentSnapshot = runCatching { container.agents.getSnapshot(snapshotId) }.getOrNull() ?: return null
    if (agentSnapshot.agentId != agentId) return null
    val config = McpConfigStore.read(container).value ?: return null
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision <= 0) return null
    val fingerprint = config.discoveryFingerprint ?: return null
    val grant = config.grants.singleOrNull { it.agentId == agentId } ?: return null
    val byName = config.tools.associateBy { it.namespacedName }
    if (grant.toolNames.isEmpty() || grant.toolNames.any { it !in byName }) return null
    if (grant.schemaHashes.keys != grant.toolNames.toSet()) return null
    if (grant.schemaHashes.any { (name, hash) -> byName[name]?.schemaHash != hash }) return null
    if (grant.revision != config.discoveryRevision) return null

    val pin = McpSnapshotStore.read(container.db, snapshotId)
    if (pin.present && pin.value == null) return null
    val existing = pin.value ?: config.snapshots.singleOrNull { it.snapshotId == snapshotId && it.agentId == agentId }
    val stored = existing ?: McpStoredSnapshot(
        snapshotId = snapshotId,
        agentId = agentId,
        grantId = grant.grantId,
        revision = grant.revision,
        discoveryFingerprint = fingerprint,
        toolNames = grant.toolNames.sorted(),
        schemaHashes = grant.schemaHashes.toSortedMap(),
    )
    if (stored.grantId != grant.grantId || stored.revision != grant.revision ||
        stored.discoveryFingerprint != fingerprint || stored.toolNames.toSet() != grant.toolNames.toSet() ||
        stored.schemaHashes != grant.schemaHashes
    ) return null
    if (stored.agentId != agentId || stored.snapshotId != snapshotId) return null
    val result = publicMcpSnapshot(config, stored) ?: return null
    // The conversation creation transaction owns this write. Never publish pins to another store.
    McpSnapshotStore.write(container.db, stored)
    return result
}

/**
 * Load only a binding that was already persisted for this exact Agent snapshot.
 * This is intentionally read-only: unlike [captureMcpSnapshot], it never creates
 * a grant or snapshot entry for an older session.
 */
fun loadMcpSnapshot(container: AppContainer, snapshotId: String, agentId: String): McpSnapshot? {
    if (snapshotId.isBlank() || agentId.isBlank()) return null
    val agentSnapshot = runCatching { container.agents.getSnapshot(snapshotId) }.getOrNull() ?: return null
    if (agentSnapshot.agentId != agentId) return null
    val config = readPinnedMcpConfig(container, snapshotId, agentId) ?: return null
    val stored = config.snapshots.singleOrNull { it.snapshotId == snapshotId && it.agentId == agentId }
    if (stored == null || stored.agentId != agentId) return null
    val snapshot = publicMcpSnapshot(config, stored) ?: return null
    return snapshot.takeIf { snapshotBindingIsCurrent(config, it) }
}

/** A present database pin is authoritative; corrupt data cannot reactivate a legacy pin. */
internal fun readPinnedMcpConfig(container: AppContainer, snapshotId: String, agentId: String): McpStoredConfig? =
    resolvePinnedMcpConfig(McpConfigStore.read(container).value, McpSnapshotStore.read(container.db, snapshotId), snapshotId, agentId)

internal fun resolvePinnedMcpConfig(config: McpStoredConfig?, pin: McpSnapshotStore.Read, snapshotId: String, agentId: String): McpStoredConfig? {
    if (config == null || !pin.present) return config
    val stored = pin.value?.takeIf { it.snapshotId == snapshotId && it.agentId == agentId } ?: return null
    return config.copy(snapshots = config.snapshots.filterNot { it.snapshotId == snapshotId } + stored)
}

private fun publicMcpSnapshot(config: McpStoredConfig, stored: McpStoredSnapshot): McpSnapshot? {
    if (config.networkApprovedAt.isNullOrBlank() || config.discoveryRevision <= 0) return null
    val fingerprint = config.discoveryFingerprint ?: return null
    if (stored.revision != config.discoveryRevision || stored.discoveryFingerprint != fingerprint) return null
    val grant = config.grants.singleOrNull { it.agentId == stored.agentId } ?: return null
    if (grant.grantId != stored.grantId || grant.revision != stored.revision) return null
    if (grant.toolNames.toSet() != stored.toolNames.toSet() || grant.schemaHashes != stored.schemaHashes) return null
    val byName = config.tools.associateBy { it.namespacedName }
    val tools = stored.toolNames.mapNotNull { name ->
        byName[name]?.let { tool ->
            McpSnapshotTool(tool.namespacedName, tool.description, tool.inputSchemaJson, tool.schemaHash)
        }
    }
    if (tools.size != stored.toolNames.size || tools.isEmpty()) return null
    return McpSnapshot(
        snapshotId = stored.snapshotId,
        agentId = stored.agentId,
        endpoint = config.endpoint,
        host = config.host,
        namespace = config.namespace,
        grantId = stored.grantId,
        discoveryRevision = stored.revision,
        discoveryFingerprint = stored.discoveryFingerprint,
        tools = tools,
    )
}
