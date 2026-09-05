// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/**
 * Production-chain replay tests for the factory composite (b07 follow-up
 * finding A): a fake child behind [ToolExecutorFactory.createLegacyExecutor]
 * must actually observe the disclosure check, and a revoked child must deny
 * without redispatch and without leaking the cached payload.
 *
 * These are JVM tests: the factory/composite boundary is pure Kotlin with no
 * Android-framework dependency.  The full RunTools chain is covered on-device
 * by `RunToolsReplayDeviceTest`.
 */
class ToolExecutorFactoryReplayTest {
    private class RevocableChild : ToolExecutor {
        override val specs = listOf(ToolSpec("protected_read", "fixture", "{\"type\":\"object\"}", "", false))
        var revoked = false
        var replayChecks = 0
        var dispatches = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            dispatches++
            return ToolResult.Value("{\"data\":\"fixture-only-secret\"}")
        }

        override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")

        override suspend fun authorizeReplay(call: ToolCall): Boolean {
            replayChecks++
            return !revoked
        }
    }

    private class ApprovalChild : ToolExecutor {
        override val specs = listOf(ToolSpec("gated", "fixture", "{\"type\":\"object\"}", "", false))
        var dispatches = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            dispatches++
            return ToolResult.NeedsApproval
        }

        override suspend fun approve(callId: String): ToolResult =
            ToolResult.Value("{\"data\":\"approval-settled-secret\"}")

        override suspend fun authorizeReplay(call: ToolCall): Boolean = true
    }

    @Test
    fun revokedChildDeniesReplayWithoutRedispatch(): Unit = runBlocking {
        val child = RevocableChild()
        val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
        val call = ToolCall("call-1", "protected_read", "{}")

        val first = composite.invoke(call)
        assertTrue(first is ToolResult.Value)
        assertEquals(1, child.dispatches)

        assertTrue(composite.authorizeReplay(call))
        assertEquals(1, child.replayChecks)

        child.revoked = true
        assertFalse(composite.authorizeReplay(call))
        assertEquals(2, child.replayChecks)
        assertEquals(1, child.dispatches, "a denied replay must never re-dispatch the tool")
    }

    @Test
    fun unknownCallIdDeniesWithoutTouchingChild(): Unit = runBlocking {
        val child = RevocableChild()
        val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
        assertFalse(composite.authorizeReplay(ToolCall("never-seen", "protected_read", "{}")))
        assertEquals(0, child.replayChecks)
        assertEquals(0, child.dispatches)
    }

    @Test
    fun reusedCallIdWithDifferentArgumentsDenies(): Unit = runBlocking {
        val child = RevocableChild()
        val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
        val call = ToolCall("call-1", "protected_read", "{}")
        assertTrue(composite.invoke(call) is ToolResult.Value)
        assertFalse(composite.authorizeReplay(call.copy(argumentsJson = "{\"other\":true}")))
        assertEquals(0, child.replayChecks, "an identity mismatch must deny before reaching the child")
        assertEquals(1, child.dispatches)
    }

    @Test
    fun pendingApprovalIsNotReplayableButSettledResultIs(): Unit = runBlocking {
        val child = ApprovalChild()
        val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
        val call = ToolCall("call-1", "gated", "{}")
        assertTrue(composite.invoke(call) is ToolResult.NeedsApproval)
        assertFalse(composite.authorizeReplay(call), "a pending approval has no settled result to disclose")
        assertTrue(composite.approve("call-1") is ToolResult.Value)
        assertTrue(composite.authorizeReplay(call))
        assertEquals(1, child.dispatches)
    }

    @Test
    fun interfaceDefaultDeniesDisclosureFailClosed(): Unit = runBlocking {
        val bare = object : ToolExecutor {
            override val specs = listOf(ToolSpec("x", "x", "{\"type\":\"object\"}", "", false))
            override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Value("{}")
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")
        }
        assertFalse(
            bare.authorizeReplay(ToolCall("any", "x", "{}")),
            "an executor without an explicit replay policy must deny, never allow",
        )
    }

    @Test
    fun childExceptionDeniesFailClosed(): Unit = runBlocking {
        val failing = object : ToolExecutor {
            override val specs = listOf(ToolSpec("boom", "x", "{\"type\":\"object\"}", "", false))
            override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Value("{}")
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")
            override suspend fun authorizeReplay(call: ToolCall): Boolean = throw IllegalStateException("store gone")
        }
        val composite = ToolExecutorFactory(web = failing).createLegacyExecutor()
        val call = ToolCall("call-1", "boom", "{}")
        assertTrue(composite.invoke(call) is ToolResult.Value)
        assertFalse(composite.authorizeReplay(call))
    }
}
