// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolRegistrySnapshotBindingTest {
    private val registration = ToolRegistration(ToolSpec("read", "fixture", "{}", schemaVersion = 4), "owner")

    @Test
    fun explicitInitialSnapshotRotatesOnEveryPublication() {
        // Deliberately resembles the historical generated ID to rule out collisions.
        val registry = ToolRegistry(listOf(registration), initialSnapshotId = "tool-snapshot-2")
        val original = registry.snapshot()
        assertEquals("tool-snapshot-2", original.snapshotId)
        assertEquals(4, original.specs.single().schemaVersion)
        val next = registry.switchOwner("read", "other-owner")
        assertNotEquals(original.snapshotId, next.snapshotId)
        assertEquals(original.schemaDigest, next.schemaDigest)
        val stale = ToolInvocation.fromRuntime("model", original.snapshotId, "agent", "read", "{}")
        val rejected = registry.bind(stale) as ToolRouteResult.Rejected
        assertEquals(ToolErrorCode.SNAPSHOT_STALE, rejected.error.code)
        assertNotEquals(next.snapshotId, registry.publish(listOf(registration)).snapshotId)
        assertNotEquals(ToolRegistry(listOf(registration)).snapshot().snapshotId, ToolRegistry(listOf(registration)).snapshot().snapshotId)
    }

    @Test
    fun resourceValueReplayRevalidatesWithoutRedispatchAndDenialIsSticky() = runBlocking {
        var calls = 0
        var authorized = true
        val registry = ToolRegistry(listOf(registration), mapOf("owner" to ToolHandler {
            calls++
            ToolExecution.Value("private result")
        }), initialSnapshotId = "bound", completedResultAuthorization = { authorized })
        val invocation = ToolInvocation.fromRuntime("model", "bound", "agent", "read", "{}")
        assertEquals(ToolExecution.Value("private result"), registry.dispatch(invocation))
        assertEquals(ToolExecution.Value("private result"), registry.dispatch(invocation))
        authorized = false
        assertTrue(registry.dispatch(invocation) is ToolExecution.Failed)
        authorized = true
        assertTrue(registry.dispatch(invocation) is ToolExecution.Failed)
        assertEquals(1, calls)
    }
}
