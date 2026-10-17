// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.observation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/**
 * The device_act `observe_after` option: one approval and one OS dispatch carry the
 * action plus its follow-up observation, while the confirmed action fact and the
 * observation fact stay separately visible.
 *
 * Every body is bounded so a wrong implementation fails instead of blocking.
 */
class ScreenActionObserveAfterTest {
    private val observationId = "bc44b235-74b0-40b6-8be8-ec45b290cc40"
    private val binding = ScreenActionAuthority(Authority.SHIZUKU, 1)
    private val freshHandle = FRESH_HANDLE

    private companion object {
        const val FRESH_HANDLE = "11111111-2222-3333-4444-555555555555"
    }

    /** A bounded test body: no scenario may wait on an unbounded synchronization point. */
    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { body() } }

    private fun call(id: String = "one", observeAfter: String? = null) = ToolCall(id, "device_act",
        buildString {
            append("{\"observation_id\":\"$observationId\",\"action\":\"click\",\"node_index\":0")
            observeAfter?.let { append(",\"observe_after\":$it") }
            append("}")
        })

    /** One object behind both ports, exactly like the production registry. */
    private class DualPort : ScreenActionPort, ScreenObservationPort {
        override val access = MutableStateFlow(ScreenAccess(true, true, 1, true))
        override val changes = MutableStateFlow(7L)
        var dispatches = 0
        var performed = true
        var afterDispatch: suspend () -> Unit = {}
        var captures = 0
        var expectedPackages = mutableListOf<String?>()
        var label = "after-click"
        var packageName = "after.app"
        var sequenceIncrement = 1L
        var beforeCapture: suspend () -> Unit = {}
        var captureFailure: Throwable? = null
        var nodes: List<ScreenNode>? = null
        var stable = true
        var observationHandle: String? = FRESH_HANDLE

        override suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean,
            onDispatch: () -> Unit): Boolean {
            if (!authorized()) throw ScreenObservationException("SCREEN_ACTION_ACCESS_CHANGED")
            onDispatch(); dispatches++
            changes.value += sequenceIncrement
            afterDispatch()
            return performed
        }

        override suspend fun capture(expectedPackage: String?): ScreenSnapshot {
            captures++; expectedPackages += expectedPackage
            beforeCapture()
            captureFailure?.let { throw it }
            return ScreenSnapshot(packageName, 1, changes.value, 1,
                nodes ?: listOf(ScreenNode(0, null, label, "", "Text", listOf(0, 0, 10, 10), true, false, false, true)),
                false, stable, observationHandle)
        }
    }

    /** An action channel without any observation channel: observe_after cannot be served. */
    private class ActionOnlyPort : ScreenActionPort {
        override val access = MutableStateFlow(ScreenAccess(true, true, 1, true))
        var dispatches = 0
        override suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean,
            onDispatch: () -> Unit): Boolean {
            onDispatch(); dispatches++; return true
        }
    }

    private fun value(result: ToolResult): JsonObject {
        assertTrue(result is ToolResult.Value, "expected a Value result, got $result")
        return Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    @Test fun oneCallReturnsTheConfirmedActionAndTheObservedScreen() = test {
        val p = DualPort().apply { label = "clicks=1 longs=0" }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{\"text\":\"clicks=1 longs=0\",\"timeout_ms\":1000}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        assertEquals(0, p.dispatches)
        val result = value(e.approve(call.callId))
        assertEquals("performed", result.text("status"))
        assertEquals(true, result.flag("action_confirmed"))
        assertEquals(false, result.flag("observe_again"))
        val observed = requireNotNull(result.obj("observation")) { "no observation in $result" }
        assertEquals("observed", observed.text("status"))
        assertEquals(true, observed.flag("matched"))
        assertEquals(freshHandle, observed.text("observation_id"))
        assertEquals(listOf("clicks=1 longs=0"),
            (observed["nodes"] as JsonArray).map { (it as JsonObject).text("text") })
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
    }

    @Test fun aSingleTransitionalCaptureStillAsksForAnActionableObservation() = test {
        for (stable in listOf(false, true)) {
            val p = DualPort().apply { this.stable = stable; observationHandle = null }
            val e = ScreenActionToolExecutor(p, { binding }, { true })
            val action = call(observeAfter = "{}")
            assertEquals(ToolResult.NeedsApproval, e.invoke(action))
            val receipt = e.approve(action.callId)
            val result = value(receipt)
            assertEquals("performed", result.text("status"))
            assertEquals(true, result.flag("observe_again"))
            val observed = requireNotNull(result.obj("observation"))
            assertEquals(stable, observed.flag("stable"))
            assertFalse("observation_id" in observed)
            assertEquals(1, p.captures)
            // A receipt replay reuses only the disclosed frame, never dispatches the action again.
            assertEquals(receipt, e.invoke(action))
            assertEquals(1, p.dispatches); assertEquals(1, p.captures)
            p.changes.value++
            assertFalse(e.authorizeReplay(action))
            assertTrue(e.invoke(action) is ToolResult.Denied)
        }
    }

    @Test fun aConditionalReadWaitsThroughATransitionalFrameUntilTheActionableOne() = test {
        val p = DualPort().apply { stable = false; observationHandle = null }
        val entered = CompletableDeferred<Unit>()
        p.beforeCapture = { if (p.captures == 1) entered.complete(Unit) }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val action = call(observeAfter = "{\"text\":\"after-click\",\"timeout_ms\":2000}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(action))
        val read = async { e.approve(action.callId) }
        withTimeout(1000) { entered.await() }
        assertFalse(read.isCompleted)
        p.stable = true; p.observationHandle = freshHandle; p.changes.value++
        val result = value(read.await())
        assertEquals(false, result.flag("observe_again"))
        assertEquals(true, result.obj("observation")?.flag("matched"))
        assertEquals(freshHandle, result.obj("observation")?.text("observation_id"))
        assertEquals(1, p.dispatches)
    }

    @Test fun legacyArgumentsKeepTheirOldResultAndNeverReadTheScreen() = test {
        val p = DualPort()
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call()
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val legacy = ToolResult.Value("{\"status\":\"performed\",\"observe_again\":true}")
        assertEquals(legacy, e.approve(call.callId))
        assertEquals(1, p.dispatches); assertEquals(0, p.captures)
        assertEquals(legacy, e.invoke(call))
        assertEquals(1, p.dispatches); assertEquals(0, p.captures)
    }

    @Test fun emptyObserveAfterCapturesOnceAndClaimsNothingItDidNotCheck() = test {
        val p = DualPort()
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val result = value(e.approve(call.callId))
        assertEquals("performed", result.text("status"))
        val observed = requireNotNull(result.obj("observation"))
        assertEquals("observed", observed.text("status"))
        assertNull(observed["matched"], "an unconditional capture makes no matching claim")
        assertEquals(freshHandle, observed.text("observation_id"))
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
    }

    @Test fun observeAfterConditionsAreAppliedToTheFreshCapture() = test {
        val p = DualPort().apply { label = "ready"; packageName = "com.example.after" }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{\"package_name\":\"com.example.after\",\"text\":\"ready\"}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val observed = requireNotNull(value(e.approve(call.callId)).obj("observation"))
        assertEquals("observed", observed.text("status"))
        assertEquals(true, observed.flag("matched"))
        assertEquals("com.example.after", observed.text("package_name"))
        assertEquals(listOf<String?>("com.example.after"), p.expectedPackages)
    }

    @Test fun aConditionalObserveAfterWaitsForTheEventThatMakesItMatch() = test {
        val p = DualPort().apply { label = "not-yet" }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{\"text\":\"ready\",\"timeout_ms\":2000}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val task = async { e.approve(call.callId) }
        delay(60)
        assertFalse(task.isCompleted)
        assertEquals(1, p.captures)
        p.label = "ready"; p.changes.value++
        val observed = requireNotNull(value(withTimeout(3_000) { task.await() }).obj("observation"))
        assertEquals("observed", observed.text("status"))
        assertEquals(true, observed.flag("matched"))
        assertEquals(2, p.captures)
    }

    @Test fun aConditionThatNeverMatchesTimesOutAsPerformedAndNeverRepeats() = test {
        val p = DualPort().apply { label = "clicks=0 longs=0" }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{\"text\":\"clicks=1 longs=0\",\"timeout_ms\":60}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val result = value(e.approve(call.callId))
        assertEquals("performed", result.text("status"))
        assertEquals(true, result.flag("action_confirmed"))
        val observed = requireNotNull(result.obj("observation"))
        assertEquals("timeout", observed.text("status"))
        assertEquals(false, observed.flag("matched"))
        assertNull(observed["observation_id"])
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
        // A timeout proves nothing about the screen; the cached answer is re-shown, never re-dispatched.
        assertEquals(result, value(e.invoke(call)))
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
    }

    @Test fun aFailedPostActionReadStillReportsTheConfirmedAction() = test {
        val cases = listOf<Pair<Throwable, String>>(
            ScreenObservationException("SCREEN_WINDOW_UNAVAILABLE") to "SCREEN_WINDOW_UNAVAILABLE",
            IllegalStateException("fixture") to "SCREEN_OBSERVATION_FAILED",
        )
        for ((failure, reason) in cases) {
            val p = DualPort().apply { captureFailure = failure }
            val e = ScreenActionToolExecutor(p, { binding }, { true })
            val call = call(observeAfter = "{}")
            assertEquals(ToolResult.NeedsApproval, e.invoke(call))
            val result = value(e.approve(call.callId))
            assertEquals("performed", result.text("status"), failure.toString())
            assertEquals(true, result.flag("action_confirmed"))
            val observed = requireNotNull(result.obj("observation"))
            assertEquals("unavailable", observed.text("status"))
            assertEquals(reason, observed.text("reason"))
            assertEquals(1, p.dispatches); assertEquals(1, p.captures)
        }
    }

    @Test fun aCancelledPostActionReadKeepsTheActionAndNeverRepeatsIt() = test {
        val p = DualPort(); val entered = CompletableDeferred<Unit>()
        p.beforeCapture = { entered.complete(Unit); awaitCancellation() }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val task = async { e.approve(call.callId) }
        withTimeout(3_000) { entered.await() }
        task.cancelAndJoin()
        assertEquals(1, p.dispatches)
        val again = value(e.invoke(call))
        assertEquals("performed", again.text("status"))
        assertEquals("cancelled", requireNotNull(again.obj("observation")).text("status"))
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
        assertTrue(e.approve(call.callId) is ToolResult.Invalid)
    }

    @Test fun revocationDuringThePostActionReadDisclosesNothingNew() = test {
        // (a) authority lost between the OS dispatch and the read: not even a capture is attempted.
        var authority: ScreenActionAuthority? = binding
        val early = DualPort()
        val earlyCall = call(observeAfter = "{}")
        val e1 = ScreenActionToolExecutor(early, { authority }, { true })
        assertEquals(ToolResult.NeedsApproval, e1.invoke(earlyCall))
        early.afterDispatch = { authority = null }
        val earlyResult = value(e1.approve(earlyCall.callId))
        assertEquals("performed", earlyResult.text("status"))
        assertEquals("withheld", requireNotNull(earlyResult.obj("observation")).text("status"))
        assertEquals(1, early.dispatches); assertEquals(0, early.captures)
        assertNoScreenContent(earlyResult, early)

        // (b) consent revoked while the screen is being read: the read result is dropped.
        val late = DualPort()
        val lateCall = call(observeAfter = "{}")
        val e2 = ScreenActionToolExecutor(late, { binding }, { true })
        assertEquals(ToolResult.NeedsApproval, e2.invoke(lateCall))
        late.beforeCapture = { late.access.value = ScreenAccess(false, true, 2) }
        val lateResult = value(e2.approve(lateCall.callId))
        assertEquals("performed", lateResult.text("status"))
        assertEquals("withheld", requireNotNull(lateResult.obj("observation")).text("status"))
        assertEquals(1, late.dispatches); assertEquals(1, late.captures)
        assertNoScreenContent(lateResult, late)
    }

    private fun assertNoScreenContent(result: JsonObject, p: DualPort) {
        val raw = result.toString()
        assertFalse(raw.contains(p.label), "fresh screen text must not be disclosed: $raw")
        assertFalse(raw.contains(p.packageName), "the fresh package must not be disclosed: $raw")
        assertFalse(raw.contains(freshHandle), "no fresh observation handle may be disclosed: $raw")
        assertFalse(raw.contains("nodes"), "no node list may be disclosed: $raw")
    }

    @Test fun anUnconfirmedActionNeverTriggersAPostActionRead() = test {
        val cases = listOf<Pair<String, (DualPort) -> Unit>>(
            "os timeout" to { p -> p.performed = false },
            "failure after dispatch" to { p -> p.afterDispatch = { throw IllegalStateException("fixture") } },
            "cancellation after dispatch" to { p -> p.afterDispatch = { throw CancellationException("fixture") } },
        )
        for ((name, configure) in cases) {
            val p = DualPort(); configure(p)
            val e = ScreenActionToolExecutor(p, { binding }, { true })
            val call = call(observeAfter = "{}")
            assertEquals(ToolResult.NeedsApproval, e.invoke(call), name)
            assertTrue(e.approve(call.callId) is ToolResult.UnknownOutcome, name)
            assertFalse(e.authorizeReplay(call), name)
            assertEquals(0, p.captures, name); assertEquals(1, p.dispatches, name)
        }
    }

    @Test fun duplicateApprovalAndRepeatedInvokeNeverDispatchOrReadTwice() = test {
        val p = DualPort(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        p.beforeCapture = { entered.complete(Unit); release.await() }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val first = async { e.approve(call.callId) }
        withTimeout(3_000) { entered.await() }
        assertTrue(e.approve(call.callId) is ToolResult.Invalid)
        assertTrue(e.invoke(call) is ToolResult.Invalid)
        release.complete(Unit)
        assertTrue(withTimeout(3_000) { first.await() } is ToolResult.Value)
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
        assertTrue(e.approve(call.callId) is ToolResult.Invalid)
        assertTrue(e.invoke(call) is ToolResult.Value)
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
    }

    @Test fun malformedObserveAfterRejectsBeforeAnyApprovalOrDispatch() = test {
        val p = DualPort()
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val bad = listOf("\"x\"", "[]", "null", "{\"unknown\":1}", "{\"text\":\"\"}",
            "{\"text\":\"" + "a".repeat(201) + "\"}", "{\"timeout_ms\":0}", "{\"timeout_ms\":10001}",
            "{\"timeout_ms\":\"1000\"}", "{\"timeout_ms\":1.5}", "{\"package_name\":\"not a package\"}",
            "{\"package_name\":null}")
        bad.forEachIndexed { index, shape ->
            val result = e.invoke(call(id = "bad$index", observeAfter = shape))
            assertTrue(result is ToolResult.Invalid, "shape $shape -> $result")
        }
        assertEquals(0, p.dispatches); assertEquals(0, p.captures)
        // The same action without the option is still approvable in this Run.
        assertEquals(ToolResult.NeedsApproval, e.invoke(call(id = "ok")))
    }

    @Test fun observeAfterIsRefusedWhenNoObservationChannelIsAdmitted() = test {
        val p = ActionOnlyPort()
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val refused = e.invoke(call(observeAfter = "{}"))
        assertTrue(refused is ToolResult.Denied, refused.toString())
        assertEquals(0, p.dispatches)
        assertEquals(ToolResult.NeedsApproval, e.invoke(call(id = "plain")))
        assertEquals(0, p.dispatches)
    }

    @Test fun anOversizedObservationKeepsTheConfirmedAction() = test {
        val p = DualPort().apply {
            nodes = (0 until 40).map { index -> ScreenNode(index, null, "x".repeat(3000), "", "Text",
                listOf(0, 0, 10, 10), true, false, false, true) }
        }
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        val result = value(e.approve(call.callId))
        assertEquals("performed", result.text("status"))
        assertEquals(true, result.flag("action_confirmed"))
        val observed = requireNotNull(result.obj("observation"))
        assertEquals("unavailable", observed.text("status"))
        assertEquals("SCREEN_RESULT_LIMIT", observed.text("reason"))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size < 96 * 1024)
        assertEquals(1, p.dispatches)
    }

    @Test fun aStaleCompositeSnapshotIsNotDisclosedAgain() = test {
        val p = DualPort()
        val e = ScreenActionToolExecutor(p, { binding }, { true })
        val call = call(observeAfter = "{}")
        assertEquals(ToolResult.NeedsApproval, e.invoke(call))
        assertTrue(e.approve(call.callId) is ToolResult.Value)
        assertTrue(e.invoke(call) is ToolResult.Value)
        p.changes.value++
        assertFalse(e.authorizeReplay(call))
        assertTrue(e.invoke(call) is ToolResult.Denied)
        assertEquals(1, p.dispatches); assertEquals(1, p.captures)
    }
}
