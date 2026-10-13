// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpSnapshotPersistenceTest {
    private val stored = McpStoredSnapshot("snapshot", "agent", "grant", 1, "fingerprint", listOf("fixture.tool"), mapOf("fixture.tool" to "hash"))
    private val config = McpStoredConfig(endpoint = "https://example.invalid/mcp", host = "example.invalid", namespace = "fixture",
        networkApprovedAt = "2026-10-13T00:00:00Z", discoveryRevision = 1, discoveryFingerprint = "fingerprint",
        tools = listOf(McpStoredTool("fixture.tool", inputSchemaJson = "{\"type\":\"object\"}", schemaHash = "hash")),
        grants = listOf(McpStoredGrant("agent", "grant", 1, stored.toolNames, stored.schemaHashes)))
    private val snapshot = McpSnapshot("snapshot", "agent", config.endpoint, config.host, config.namespace, "grant", 1,
        "fingerprint", listOf(McpSnapshotTool("fixture.tool", "", "{\"type\":\"object\"}", "hash")))

    @Test fun databaseOnlyPinActivatesWithoutCreatingALegacyPreferenceSnapshot() {
        DraftConnection().use { db ->
            McpSnapshotStore.write(db, stored)
            val resolved = checkNotNull(resolvePinnedMcpConfig(config, McpSnapshotStore.read(db, "snapshot"), "snapshot", "agent"))
            assertTrue(config.snapshots.isEmpty())
            assertTrue(snapshotBindingIsCurrent(resolved, snapshot))
            assertTrue(mcpTools(snapshot, { resolved }, { _, _, _ -> error("No network adapter needed") }).specs.isNotEmpty())
            assertFalse(snapshotBindingIsCurrent(resolved.copy(grants = emptyList()), snapshot))
            assertTrue(mcpTools(snapshot, { resolved.copy(grants = emptyList()) }, { _, _, _ -> error("No network") }).specs.isEmpty())
        }
    }

    @Test fun corruptOrWrongOwnerDatabasePinCannotReactivateLegacyEntry() {
        val legacy = config.copy(snapshots = listOf(stored))
        assertEquals(legacy, resolvePinnedMcpConfig(legacy, McpSnapshotStore.Read(false, null), "snapshot", "agent"))
        assertNull(resolvePinnedMcpConfig(legacy, McpSnapshotStore.Read(true, null), "snapshot", "agent"))
        assertNull(resolvePinnedMcpConfig(legacy, McpSnapshotStore.Read(true, stored.copy(agentId = "other")), "snapshot", "agent"))
        DraftConnection().use { db ->
            db.execute("INSERT INTO app_prefs VALUES(?,?)", listOf("mcp.snapshot.v1:snapshot", "corrupt"))
            assertTrue(McpSnapshotStore.read(db, "snapshot").present)
            assertNull(McpSnapshotStore.read(db, "snapshot").value)
        }
    }

    @Test fun failedConversationTransactionRollsBackTheImmutablePin() {
        DraftConnection().use { db ->
            assertThrows(IllegalStateException::class.java) { db.transaction { McpSnapshotStore.write(db, stored); error("synthetic bind failure") } }
            assertFalse(McpSnapshotStore.read(db, "snapshot").present)
        }
    }

    @Test fun pinSchemaCorruptionAfterApprovalRequestDeniesBeforeNetworkDispatch() = kotlinx.coroutines.runBlocking {
        var current = checkNotNull(resolvePinnedMcpConfig(config, McpSnapshotStore.Read(true, stored), "snapshot", "agent"))
        val executor = mcpTools(snapshot, { current }, { _, _, _ -> error("A corrupt pin must never construct a transport") })
        val call = runtime.mobileagent.skills.ToolCall("call", executor.specs.single().name, "{}")
        assertEquals(runtime.mobileagent.skills.ToolResult.NeedsApproval, executor.invoke(call))
        current = current.copy(snapshots = listOf(stored.copy(schemaHashes = mapOf("fixture.tool" to "corrupt"))))
        assertTrue(executor.approve("call") is runtime.mobileagent.skills.ToolResult.Denied)
    }
}
