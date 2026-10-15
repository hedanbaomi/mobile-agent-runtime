// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

class ScreenObservationToolExecutorTest {
    private class FakePort : ScreenObservationPort {
        override val access = MutableStateFlow(ScreenAccess(true, true, 1))
        override val changes = MutableStateFlow(1L)
        var reads = 0
        var label = "first"
        var revokeDuringRead = false
        override suspend fun capture(expectedPackage: String?): ScreenSnapshot {
            reads++
            if (revokeDuringRead) access.value = ScreenAccess(false, true, 2)
            return ScreenSnapshot("example.app", 1, changes.value, 1,
                listOf(ScreenNode(0, null, label, "", "Text", listOf(0, 0, 10, 10), false, false, false, true)), false, true)
        }
    }

    @Test fun approvalIsRequiredAndBoundToExactArguments(): Unit = runBlocking {
        val p = FakePort(); val e = ScreenObservationToolExecutor(p) { true }
        val call = ToolCall("one", "device_observe", "{}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call)); assertEquals(0, p.reads)
        assertTrue(e.invoke(call.copy(argumentsJson = "{\"package_name\":\"other.app\"}")) is ToolResult.Invalid)
        assertTrue(e.approve("one") is ToolResult.Value); assertEquals(1, p.reads)
        assertTrue(e.approve("one") is ToolResult.Invalid); assertEquals(1, p.reads)
        assertTrue(e.invoke(call) is ToolResult.Value); assertEquals(1, p.reads)
        p.changes.value++
        assertFalse(e.authorizeReplay(call)); assertTrue(e.invoke(call) is ToolResult.Denied)
    }

    @Test fun oldRunCannotAcquirePermissionAfterConstruction(): Unit = runBlocking {
        val p = FakePort().apply { access.value = ScreenAccess(false, false, 1) }
        val e = ScreenObservationToolExecutor(p) { true }
        p.access.value = ScreenAccess(true, true, 2)
        assertTrue(e.specs.isEmpty())
        assertTrue(e.invoke(ToolCall("one", "device_observe", "{}")) is ToolResult.Invalid)
        assertEquals(0, p.reads)
    }

    @Test fun revokeBeforeAndDuringReadWithholdsContent(): Unit = runBlocking {
        val p = FakePort(); val e = ScreenObservationToolExecutor(p) { true }
        val c = ToolCall("one", "device_observe", "{}")
        e.invoke(c); p.revokeDuringRead = true
        assertTrue(e.approve(c.callId) is ToolResult.Denied)
        assertFalse(e.authorizeReplay(c))
        p.access.value = ScreenAccess(true, true, 3)
        assertTrue(e.invoke(c.copy(callId = "new")) is ToolResult.Denied)
    }

    @Test fun waitsForFreshMatchingContentAndCancellationStopsWait(): Unit = runBlocking {
        val p = FakePort(); val e = ScreenObservationToolExecutor(p) { true }
        val c = ToolCall("wait", "device_wait_for", "{\"text\":\"ready\",\"timeout_ms\":1000}")
        e.invoke(c)
        val task = async { e.approve(c.callId) }
        delay(30); assertFalse(task.isCompleted)
        p.label = "ready"; p.changes.value++
        assertTrue(withTimeout(1000) { task.await() } is ToolResult.Value)
        val next = c.copy(callId = "cancel", argumentsJson = "{\"text\":\"never\",\"timeout_ms\":1000}")
        e.invoke(next)
        val pending = async { e.approve(next.callId) }
        delay(20); pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
    }

    @Test fun invalidFieldsAndBoundedTimeoutDoNotDispatch(): Unit = runBlocking {
        val p = FakePort(); val e = ScreenObservationToolExecutor(p) { true }
        listOf("{\"timeout_ms\":10001}", "{\"timeout_ms\":\"1\"}", "{}", "{\"text\":\"x\",\"unknown\":1}").forEachIndexed { i, args ->
            assertTrue(e.invoke(ToolCall("bad$i", "device_wait_for", args)) is ToolResult.Invalid)
        }
        assertEquals(0, p.reads)
        val c = ToolCall("timeout", "device_wait_for", "{\"text\":\"absent\",\"timeout_ms\":10}")
        e.invoke(c)
        assertTrue((e.approve(c.callId) as ToolResult.Value).json.contains("timeout"))
    }

    @Test fun deletedAgentAndConsentRevisionRejectPendingApproval(): Unit = runBlocking {
        val p = FakePort(); var alive = true
        val e = ScreenObservationToolExecutor(p) { alive }
        val c = ToolCall("one", "device_observe", "{}")
        e.invoke(c); alive = false
        assertTrue(e.approve(c.callId) is ToolResult.Denied); assertEquals(0, p.reads)
    }

    @Test fun revocationSettlesPendingCallsWithoutRewritingCompletedResults(): Unit = runBlocking {
        val p = FakePort(); val e = ScreenObservationToolExecutor(p) { true }
        val done = ToolCall("done", "device_observe", "{}")
        val waiting = ToolCall("waiting", "device_observe", "{}")
        e.invoke(done)
        val completed = e.approve(done.callId)
        assertTrue(completed is ToolResult.Value)
        e.invoke(waiting)

        p.access.value = ScreenAccess(false, true, 2)
        assertFalse(e.authorizeReplay(done), "a revoked result is not disclosed again")
        assertTrue(e.invoke(ToolCall("after", "device_observe", "{}")) is ToolResult.Denied)
        // The pending call is settled as denied; the completed one keeps its own result.
        assertTrue(e.approve(waiting.callId) is ToolResult.Denied)
        p.access.value = ScreenAccess(true, true, 1)
        assertEquals(completed, e.invoke(done), "restoring the admitted access shows the unchanged result")
        assertEquals(1, p.reads)
    }

    @Test fun revisionChangedInsideLiveLookupCannotDiscloseFreshOrCachedResult(): Unit = runBlocking {
        val p = FakePort(); var revoke = false
        val e = ScreenObservationToolExecutor(p) {
            if (revoke) p.access.value = ScreenAccess(false, true, 2)
            true
        }
        val c = ToolCall("one", "device_observe", "{}")
        e.invoke(c)
        assertTrue(e.approve(c.callId) is ToolResult.Value)
        revoke = true
        assertFalse(e.authorizeReplay(c))
        assertTrue(e.invoke(c) is ToolResult.Denied)
        assertEquals(1, p.reads)
    }
}
