// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuiltinToolsTest {
    private fun broker(
        caps: Set<String> = setOf("knowledge.search", "knowledge.read", "network.http"),
        autoApprove: Boolean = false,
        http: (String) -> String = { """{"ok":true}""" },
    ) = ToolBroker(
        effectiveCapabilities = caps,
        context = ToolContext(
            search = { query, _, _ -> """{"hits":[{"q":"$query"}]}""" },
            readDocument = { id, max -> """{"id":"$id","text":"${"x".repeat(max.coerceAtMost(4))}"}""" },
            httpGet = http,
            allowedHosts = setOf("api.example.com"),
        ),
        autoApproveSideEffects = autoApprove,
    )

    @Test
    fun incompleteJsonDoesNotExecute() {
        val result = broker().invoke(ToolCall("c1", "calculator", """{"expression":"""))
        assertTrue(result is ToolResult.Invalid)
    }

    @Test
    fun duplicateCallIdDoesNotDoubleExecute() {
        var count = 0
        val tools = ToolBroker(
            setOf("knowledge.search"),
            ToolContext(
                search = { _, _, _ ->
                    count += 1
                    """{"n":$count}"""
                },
                readDocument = { _, _ -> "{}" },
            ),
            autoApproveSideEffects = true,
        )
        val call = ToolCall("same", "knowledge_search", """{"query":"widget"}""")
        val first = tools.invoke(call) as ToolResult.Value
        val second = tools.invoke(call) as ToolResult.Value
        assertEquals(first.json, second.json)
        assertEquals(1, count)
    }

    @Test
    fun calculatorEvaluatesNumericExpression() {
        val result = broker().invoke(ToolCall("c", "calculator", """{"expression":"(1+2)*3"}""")) as ToolResult.Value
        assertTrue(result.json.contains("9"))
    }

    @Test
    fun promptCannotGrantHttp() {
        val result = broker(caps = setOf("knowledge.search")).invoke(
            ToolCall("h", "http_request", """{"url":"https://api.example.com/v1"}"""),
        )
        assertTrue(result is ToolResult.Denied)
    }

    @Test
    fun httpGetRequiresApprovalThenRunsOnce() {
        val tools = broker(autoApprove = false)
        val call = ToolCall("h1", "http_request", """{"url":"https://api.example.com/v1","method":"GET"}""")
        assertEquals(ToolResult.NeedsApproval, tools.invoke(call))
        val done = tools.approve("h1") as ToolResult.Value
        assertTrue(done.json.contains("ok"))
        val again = tools.invoke(call) as ToolResult.Value
        assertEquals(done.json, again.json)
    }

    @Test
    fun loopbackHttpIsRejected() {
        val result = broker(autoApprove = true).invoke(
            ToolCall("h", "http_request", """{"url":"https://localhost/secret"}"""),
        )
        assertTrue(result is ToolResult.Invalid)
    }
}
