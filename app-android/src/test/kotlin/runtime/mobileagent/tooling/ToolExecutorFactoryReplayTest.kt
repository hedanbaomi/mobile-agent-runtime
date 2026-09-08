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
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation

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
    @Test
    fun typedFactoryKeepsDistinctRuntimeInvocationsWithTheSameModelCallIdSeparate(): Unit = runBlocking {
        val accepted = linkedMapOf<String, ToolCall>()
        val child = object : ToolExecutor {
            override val specs = listOf(ToolSpec("probe", "fixture", "{\"type\":\"object\"}", "", false))
            override suspend fun invoke(call: ToolCall): ToolResult {
                val previous = accepted.putIfAbsent(call.callId, call)
                return if (previous != null && previous != call) {
                    ToolResult.Invalid("Execution ID was already used")
                } else {
                    ToolResult.Value(call.argumentsJson)
                }
            }
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")
        }
        val registry = ToolExecutorFactory(web = child).createToolRegistry()
        val snapshot = registry.snapshot()
        val first = ToolInvocation.fromRuntime("same-model-call", snapshot.snapshotId, "agent", "probe", "{}")
        val second = ToolInvocation.fromRuntime("same-model-call", snapshot.snapshotId, "agent", "probe", "{\"n\":2}")

        assertEquals(ToolExecution.Value("{}"), registry.dispatch(first))
        assertEquals(ToolExecution.Value("{\"n\":2}"), registry.dispatch(second))
        assertEquals(listOf(first.requestId, second.requestId), accepted.keys.toList())
        assertEquals("same-model-call", first.callId)
        assertEquals(first.callId, second.callId)
    }

    private class RevocableChild : ToolExecutor {
        override val specs = listOf(ToolSpec("protected_read", "fixture", "{\"type\":\"object\"}", "", false))
        var revoked = false
        var throwOnReplay = false
        var replayChecks = 0
        var dispatches = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            dispatches++
            return ToolResult.Value("{\"data\":\"fixture-only-secret\"}")
        }

        override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")

        override suspend fun authorizeReplay(call: ToolCall): Boolean {
            replayChecks++
            if (throwOnReplay) throw IllegalStateException("authorization store unavailable")
            return !revoked
        }
    }

    private class ApprovalChild : ToolExecutor {
        override val specs = listOf(ToolSpec("gated", "fixture", "{\"type\":\"object\"}", "", false))
        var dispatches = 0
        var approvals = 0
        var replayChecks = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            dispatches++
            return ToolResult.NeedsApproval
        }

        override suspend fun approve(callId: String): ToolResult {
            approvals++
            return ToolResult.Value("{\"data\":\"approval-settled-secret\"}")
        }

        override suspend fun authorizeReplay(call: ToolCall): Boolean {
            replayChecks++
            return true
        }
    }

    private class UnknownChild(private val throwOnReplay: Boolean) : ToolExecutor {
        override val specs = listOf(ToolSpec("unknown", "fixture", "{\"type\":\"object\"}", "", false))
        var dispatches = 0
        var replayChecks = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            dispatches++
            return ToolResult.UnknownOutcome("fixture-only-unknown-secret")
        }

        override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")

        override suspend fun authorizeReplay(call: ToolCall): Boolean {
            replayChecks++
            if (throwOnReplay) throw IllegalStateException("authorization store unavailable")
            return false
        }
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
    fun duplicateInvokeRevalidatesAndPermanentlyTombstonesDeniedCache(): Unit = runBlocking {
        val child = RevocableChild()
        val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
        val call = ToolCall("call-1", "protected_read", "{}")

        val first = composite.invoke(call)
        assertTrue(first is ToolResult.Value)
        val allowedReplay = composite.invoke(call)
        assertTrue(allowedReplay is ToolResult.Value)
        assertEquals(1, child.dispatches)
        assertEquals(1, child.replayChecks)

        child.revoked = true
        val deniedReplay = composite.invoke(call)
        assertTrue(deniedReplay is ToolResult.Denied)
        assertFalse((deniedReplay as ToolResult.Denied).reason.contains("fixture-only-secret"))
        assertEquals(1, child.dispatches, "replay must never redispatch the tool")
        assertEquals(2, child.replayChecks)

        child.revoked = false
        val afterRestore = composite.invoke(call)
        assertTrue(afterRestore is ToolResult.Denied)
        assertFalse((afterRestore as ToolResult.Denied).reason.contains("fixture-only-secret"))
        assertEquals(1, child.dispatches)
        assertEquals(2, child.replayChecks, "a denied cache must not be reauthorized after revocation")
    }

    @Test
    fun duplicateInvokeRevalidationExceptionDeniesAndTombstonesCache(): Unit = runBlocking {
        val child = RevocableChild().apply { throwOnReplay = true }
        val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
        val call = ToolCall("call-1", "protected_read", "{}")

        assertTrue(composite.invoke(call) is ToolResult.Value)
        val denied = composite.invoke(call)
        assertTrue(denied is ToolResult.Denied)
        assertFalse((denied as ToolResult.Denied).reason.contains("fixture-only-secret"))
        assertEquals(1, child.dispatches)
        assertEquals(1, child.replayChecks)

        child.throwOnReplay = false
        assertTrue(composite.invoke(call) is ToolResult.Denied)
        assertEquals(1, child.dispatches)
        assertEquals(1, child.replayChecks, "a failed revalidation must not be retried against a restored grant")
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
        assertTrue(composite.invoke(call) is ToolResult.NeedsApproval)
        assertEquals(1, child.dispatches, "a duplicate pending approval must not redispatch")
        assertFalse(composite.authorizeReplay(call), "a pending approval has no settled result to disclose")
        assertTrue(composite.approve("call-1") is ToolResult.Value)
        assertTrue(composite.authorizeReplay(call))
        assertTrue(composite.invoke(call) is ToolResult.Value)
        assertEquals(1, child.dispatches)
        assertEquals(1, child.approvals)
        assertEquals(2, child.replayChecks)
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

    @Test
    fun defaultReplayPolicyDeniesDuplicateWithoutLeakingCachedPayload(): Unit = runBlocking {
        var dispatches = 0
        val bare = object : ToolExecutor {
            override val specs = listOf(ToolSpec("default_deny", "x", "{\"type\":\"object\"}", "", false))
            override suspend fun invoke(call: ToolCall): ToolResult {
                dispatches++
                return ToolResult.Value("{\"data\":\"default-only-secret\"}")
            }
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")
        }
        val composite = ToolExecutorFactory(web = bare).createLegacyExecutor()
        val call = ToolCall("call-1", "default_deny", "{}")

        assertTrue(composite.invoke(call) is ToolResult.Value)
        val denied = composite.invoke(call)
        assertTrue(denied is ToolResult.Denied)
        assertFalse((denied as ToolResult.Denied).reason.contains("default-only-secret"))
        val afterRestore = composite.invoke(call)
        assertTrue(afterRestore is ToolResult.Denied)
        assertFalse((afterRestore as ToolResult.Denied).reason.contains("default-only-secret"))
        assertEquals(1, dispatches)
    }

    @Test
    fun unknownOutcomeDuplicateStaysUnknownWithoutRedispatchOrSensitiveReason(): Unit = runBlocking {
        listOf(false, true).forEach { throwOnReplay ->
            val child = UnknownChild(throwOnReplay)
            val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
            val call = ToolCall("call-1", "unknown", "{}")

            val first = composite.invoke(call)
            assertTrue(first is ToolResult.UnknownOutcome)
            assertTrue((first as ToolResult.UnknownOutcome).reason.contains("fixture-only-unknown-secret"))

            val duplicate = composite.invoke(call)
            assertTrue(duplicate is ToolResult.UnknownOutcome)
            assertFalse((duplicate as ToolResult.UnknownOutcome).reason.contains("fixture-only-unknown-secret"))
            assertEquals(1, child.dispatches, "an unknown outcome must never redispatch")
            assertEquals(0, child.replayChecks, "an unknown cache is not replayable")

            assertFalse(composite.authorizeReplay(call))
            val afterAuthorizeReplay = composite.invoke(call)
            assertTrue(afterAuthorizeReplay is ToolResult.UnknownOutcome)
            assertFalse((afterAuthorizeReplay as ToolResult.UnknownOutcome).reason.contains("fixture-only-unknown-secret"))
            assertEquals(1, child.dispatches)
            assertEquals(0, child.replayChecks, "authorizeReplay must not destroy or reauthorize unknown state")
        }
    }
}
