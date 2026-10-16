// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

class ScreenActionToolExecutorTest {
    private val id = "bc44b235-74b0-40b6-8be8-ec45b290cc40"
    private val binding = ScreenActionAuthority(Authority.SHIZUKU, 1)
    private fun call(name: String = "one", extra: String = "") = ToolCall(name, "device_act",
        """{"observation_id":"$id","action":"click","node_index":0$extra}""")
    private class Port : ScreenActionPort {
        override val access = MutableStateFlow(ScreenAccess(true, true, 1, true))
        var dispatches = 0
        var performed = true
        var beforeDispatch: () -> Unit = {}
        var afterDispatch: suspend () -> Unit = {}
        override suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean,
            onDispatch: () -> Unit): Boolean {
            beforeDispatch()
            if (!authorized()) throw ScreenObservationException("SCREEN_ACTION_ACCESS_CHANGED")
            onDispatch(); dispatches++
            afterDispatch()
            return performed
        }
    }

    @Test fun approvalAndReplayAreBoundAndNeverRepeatTheAction() = runBlocking {
        val p = Port(); val e = ScreenActionToolExecutor(p, { binding }, { true })
        assertTrue(e.specs.single().sideEffect)
        assertEquals(ToolResult.NeedsApproval, e.invoke(call()))
        assertEquals(0, p.dispatches)
        assertTrue(e.invoke(call(extra = ",\"text\":\"bad\"")) is ToolResult.Invalid)
        assertTrue(e.approve("one") is ToolResult.Value)
        assertTrue(e.approve("one") is ToolResult.Invalid)
        assertTrue(e.invoke(call()) is ToolResult.Value)
        assertEquals(1, p.dispatches)
    }

    @Test fun consentAndSelectedAuthorityAreRequiredAtAdmissionAndDispatch() = runBlocking {
        val p = Port()
        var current: ScreenActionAuthority? = null
        val absent = ScreenActionToolExecutor(p, { current }, { true })
        current = binding
        assertTrue(absent.specs.isEmpty())
        assertTrue(absent.invoke(call()) is ToolResult.Invalid)
        p.access.value = p.access.value.copy(actionsAllowed = false)
        val disabled = ScreenActionToolExecutor(p, { current }, { true })
        p.access.value = p.access.value.copy(actionsAllowed = true, revision = 2)
        assertTrue(disabled.specs.isEmpty())
        val ready = ScreenActionToolExecutor(p, { current }, { true })
        assertEquals(ToolResult.NeedsApproval, ready.invoke(call()))
        current = binding.copy(authority = Authority.WIRED_ADB)
        assertTrue(ready.approve("one") is ToolResult.Denied)
        current = binding.copy(selectionRevision = 3)
        assertTrue(ready.approve("one") is ToolResult.Denied)
        assertEquals(0, p.dispatches)
    }

    @Test fun revocationAtTheOsBoundaryPreventsDispatch() = runBlocking {
        val p = Port(); var current: ScreenActionAuthority? = binding
        val e = ScreenActionToolExecutor(p, { current }, { true })
        e.invoke(call())
        p.beforeDispatch = { current = null }
        assertTrue(e.approve("one") is ToolResult.Denied)
        assertEquals(0, p.dispatches)
    }

    @Test fun appConsentServiceAndAgentRevocationPreventPendingActions() = runBlocking {
        for (kind in 0..3) {
            val p = Port(); var alive = true
            val e = ScreenActionToolExecutor(p, { binding }, { alive })
            e.invoke(call())
            when (kind) {
                0 -> p.access.value = p.access.value.copy(actionsAllowed = false, revision = 2)
                1 -> p.access.value = p.access.value.copy(connected = false, revision = 2)
                2 -> alive = false
                3 -> e.close()
            }
            assertFalse(e.authorizeReplay(call()))
            assertTrue(e.approve("one") !is ToolResult.Value)
            assertEquals(0, p.dispatches)
        }
    }

    @Test fun unconfirmedAndInterruptedDispatchedActionsStayUnknownAndCannotRepeat() = runBlocking {
        for (kind in 0..2) {
            val p = Port()
            when (kind) {
                0 -> p.performed = false
                1 -> p.afterDispatch = { throw IllegalStateException("fixture failure") }
                2 -> p.afterDispatch = { throw CancellationException("fixture cancellation") }
            }
            val e = ScreenActionToolExecutor(p, { binding }, { true })
            e.invoke(call())
            assertTrue(e.approve("one") is ToolResult.UnknownOutcome)
            assertFalse(e.authorizeReplay(call()))
            assertTrue(e.invoke(call()) is ToolResult.Denied)
            assertTrue(e.approve("one") is ToolResult.Invalid)
            assertEquals(1, p.dispatches)
        }
    }

    @Test fun duplicateApprovalWhileInFlightDispatchesExactlyOnce() = runBlocking {
        val p = Port(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        p.afterDispatch = { entered.complete(Unit); release.await() }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        e.invoke(call())
        val first = async { e.approve("one") }; entered.await()
        assertTrue(e.approve("one") is ToolResult.Invalid)
        assertTrue(e.reject("one") is ToolResult.Invalid)
        release.complete(Unit); assertTrue(first.await() is ToolResult.Value)
        assertEquals(1, p.dispatches)
    }

    @Test fun requestShapesAndLimitsRejectWithoutDispatch() = runBlocking {
        val p = Port(); val e = ScreenActionToolExecutor(p, { binding }, { true })
        val bad = listOf("{}", """{"observation_id":"$id","action":"tap","node_index":0}""",
            """{"observation_id":"$id","action":"home","node_index":0}""",
            """{"observation_id":"$id","action":"click","node_index":"0"}""",
            """{"observation_id":"$id","action":"click","node_index":160}""",
            """{"observation_id":"$id","action":"set_text","node_index":0}""",
            """{"observation_id":"$id","action":"set_text","node_index":0,"text":"${"a".repeat(2001)}"}""",
            """{"observation_id":"$id","action":"click","node_index":0,"backend":"adb"}""")
        bad.forEachIndexed { i, json -> assertTrue(e.invoke(ToolCall("bad$i", "device_act", json)) is ToolResult.Invalid) }
        assertEquals(0, p.dispatches)
        val clear = ToolCall("clear", "device_act", """{"observation_id":"$id","action":"set_text","node_index":0,"text":""}""")
        assertEquals(ToolResult.NeedsApproval, e.invoke(clear))
        assertTrue(e.approve("clear") is ToolResult.Value)
    }
}
