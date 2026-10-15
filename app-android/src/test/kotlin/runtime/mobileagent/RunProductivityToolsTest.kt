// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.*

class RunProductivityToolsTest {
    @Test fun aLaterLegacyCallWithTheSameModelIdKeepsItsOwnApproval(): Unit = runBlocking {
        var approved: String? = null
        val core = object : ToolExecutor {
            override val specs = listOf(
                ToolSpec("calculator", "fixture", "{}", "", false),
                ToolSpec("shell_exec", "fixture", "{}", "shell.execute", true),
            )
            override suspend fun invoke(call: ToolCall): ToolResult =
                if (call.name == "shell_exec") ToolResult.NeedsApproval else ToolResult.Value("{\"ok\":true}")
            override suspend fun approve(callId: String): ToolResult {
                approved = callId
                return ToolResult.Value("{\"shell\":true}")
            }
            override suspend fun authorizeReplay(call: ToolCall): Boolean = true
        }
        val tools = RunProductivityTools(core, this, { true }, { it })
        try {
            val start = ToolCall("reused", "job_start", "{\"tool\":\"calculator\",\"arguments\":{}}")
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(start))
            tools.executor.approve("reused")
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(ToolCall("reused", "shell_exec", "{}")))
            assertEquals(ToolResult.Value("{\"shell\":true}"), tools.executor.approve("reused"))
            assertEquals("reused", approved)
        } finally { tools.executor.close() }
    }

    private class Core : ToolExecutor {
        var calls = 0
        var authorized = true
        var block = false
        override val specs = listOf(
            ToolSpec("calculator", "fixture", "{\"type\":\"object\"}", "", false),
            ToolSpec("shell_exec", "fixture", "{\"type\":\"object\"}", "shell.execute", true),
            ToolSpec("device_observe", "fixture", "{\"type\":\"object\"}", "device.observe", false),
        )
        override suspend fun invoke(call: ToolCall): ToolResult {
            calls++
            if (block) awaitCancellation()
            return ToolResult.Value("{\"answer\":\"result-fixture\"}")
        }
        override suspend fun approve(callId: String): ToolResult = error("not required")
        override suspend fun authorizeReplay(call: ToolCall): Boolean = authorized
    }

    @Test fun jobsUseSharedBudgetAndCanDiscloseAuthorizedResults(): Unit = runBlocking {
        val core = Core(); var reserved = 0
        val tools = RunProductivityTools(core, this, { true }, { reserved += it; it })
        try {
            val start = ToolCall("start", "job_start", "{\"tool\":\"calculator\",\"arguments\":{}}")
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(start)); assertEquals(0, core.calls)
            val invocationId = tools.runtimeInvocationId(start.callId)
            assertNotNull(invocationId)
            assertNotEquals(start.callId, invocationId)
            assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(start))
            assertEquals(invocationId, tools.runtimeInvocationId(start.callId))
            assertTrue(tools.executor.invoke(start.copy(argumentsJson = "{\"tool\":\"shell_exec\",\"arguments\":{}}")) is ToolResult.Invalid)
            val started = tools.executor.approve(requireNotNull(invocationId)) as ToolResult.Value
            val id = Json.parseToJsonElement(started.json).jsonObject.getValue("job_id").jsonPrimitive.content
            val read = ToolCall("output", "job_output", "{\"job_id\":\"$id\",\"wait_ms\":1000}")
            val output = tools.executor.invoke(read) as ToolResult.Value
            assertTrue(output.json.contains("result-fixture"), output.json)
            assertEquals(1, reserved); assertEquals(1, core.calls)
            core.authorized = false
            val withheld = tools.executor.invoke(read)
            assertFalse(withheld is ToolResult.Value && withheld.json.contains("result-fixture"))
        } finally { tools.executor.close() }
    }

    @Test fun cancellingAReadJobDoesNotBlockLaterToolsInTheRun(): Unit = runBlocking {
        val core = Core(); val tools = RunProductivityTools(core, this, { true }, { it })
        try {
            core.block = true
            val start = ToolCall("read", "job_start", "{\"tool\":\"calculator\",\"arguments\":{}}")
            tools.executor.invoke(start)
            val started = tools.executor.approve(start.callId) as ToolResult.Value
            val id = Json.parseToJsonElement(started.json).jsonObject.getValue("job_id").jsonPrimitive.content
            withTimeout(1000) { while (core.calls == 0) yield() }
            tools.executor.invoke(ToolCall("cancel", "job_cancel", "{\"job_id\":\"$id\"}"))
            assertFalse(tools.hasUnknownOutcomes)

            core.block = false
            val next = tools.executor.invoke(ToolCall("next", "calculator", "{}"))
            assertTrue(next is ToolResult.Value, "a stopped read must not lock the Run: $next")
        } finally { tools.executor.close() }
    }

    @Test fun screenCannotBeNestedAndRunningSideEffectsRemainUnknownAtClose(): Unit = runBlocking {
        val core = Core(); val tools = RunProductivityTools(core, this, { true }, { it })
        val forbidden = ToolCall("screen", "job_start", "{\"tool\":\"device_observe\",\"arguments\":{}}")
        assertTrue(tools.executor.invoke(forbidden) is ToolResult.Invalid)
        core.block = true
        val start = ToolCall("shell", "job_start", "{\"tool\":\"shell_exec\",\"arguments\":{}}")
        tools.executor.invoke(start); tools.executor.approve(start.callId)
        withTimeout(1000) { while (core.calls == 0) yield() }
        tools.executor.close()
        assertTrue(tools.hasUnknownOutcomes)
    }
}
