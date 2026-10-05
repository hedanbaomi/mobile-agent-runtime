// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.skills

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.tooling.ToolOutcome
import runtime.mobileagent.skills.tooling.ToolOutcomeStatus

class WebSearchCompletionTest {
    private val call = ToolCall("search", "web_search", """{"query":"public example"}""")

    @Test fun dispatchedCompletionWithholdsContentAcrossRevocationMatrixAndNeverReplays() = runTest {
        for (changed in listOf("permission", "provider", "key", "agentRevision")) {
            val state = mutableMapOf("permission" to 1, "provider" to 1, "key" to 1, "agentRevision" to 1)
            val dispatched = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            var calls = 0
            val tools = WebSearchToolExecutor(true, { state.values.all { it == 1 } }, false) { _, _, dispatch ->
                calls++
                dispatch()
                dispatched.complete(Unit)
                returned.await()
                """{"results":[{"title":"WITHHELD_PROVIDER_CONTENT"}]}"""
            }
            val pending = async { tools.invoke(call) }
            dispatched.await()
            state[changed] = 2
            returned.complete(Unit)
            val result = pending.await() as ToolResult.Denied
            assertEquals(ToolResult.Completion.COMPLETED_WITHHELD, result.completion, changed)
            assertFalse(result.reason.contains("WITHHELD_PROVIDER_CONTENT"))
            val envelope = ToolOutcome.completedWithheld(result.reason)
            assertEquals(ToolOutcomeStatus.COMPLETED_WITHHELD, ToolOutcome.statusOf(envelope))
            assertTrue(envelope.contains("\"dispatched\":true"))
            assertTrue(envelope.contains("\"completed\":true"))
            assertTrue(envelope.contains("\"chargesMayApply\":true"))
            assertTrue(envelope.contains("\"automaticReplayAllowed\":false"))
            assertEquals(result, tools.invoke(call))
            assertFalse(tools.authorizeReplay(call))
            state[changed] = 1
            assertEquals(result, tools.invoke(call))
            assertFalse(tools.authorizeReplay(call))
            assertEquals(1, calls)
        }
    }

    @Test fun cachedSuccessBecomesPermanentlyWithheldAfterRevocationWithoutRedispatch() = runTest {
        var allowed = true
        var calls = 0
        val tools = WebSearchToolExecutor(true, { allowed }, false) { _, _, dispatch ->
            calls++; dispatch(); """{"results":[{"title":"secret response"}]}"""
        }
        assertTrue(tools.invoke(call) is ToolResult.Value)
        allowed = false
        assertFalse(tools.authorizeReplay(call))
        val withheld = tools.invoke(call) as ToolResult.Denied
        assertEquals(ToolResult.Completion.COMPLETED_WITHHELD, withheld.completion)
        allowed = true
        assertEquals(withheld, tools.invoke(call))
        assertEquals(1, calls)
    }

    @Test fun preDispatchDenialAndInvalidPostDispatchResponseRemainDistinct() = runTest {
        var calls = 0
        val denied = WebSearchToolExecutor(true, { false }, false) { _, _, _ -> calls++; error("must not dispatch") }
        assertNull((denied.invoke(call) as ToolResult.Denied).completion)
        assertEquals(0, calls)
        var allowed = true
        val malformed = WebSearchToolExecutor(true, { allowed }, false) { _, _, dispatch ->
            calls++; dispatch(); allowed = false; "invalid"
        }
        val result = malformed.invoke(call)
        assertTrue(result is ToolResult.UnknownOutcome)
        assertEquals(result, malformed.invoke(call))
        assertEquals(1, calls)
    }
}
