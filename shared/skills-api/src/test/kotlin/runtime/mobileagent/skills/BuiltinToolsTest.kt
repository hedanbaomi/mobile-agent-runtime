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
                grantedKnowledgeBaseIds = setOf("kb-a"),
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

    @Test
    fun fileAndPlainHttpNeverReachCallback() {
        var calls = 0
        val tools = broker(autoApprove = true, http = { calls += 1; it })
        val file = tools.invoke(ToolCall("f", "http_request", """{"url":"file:///etc/passwd"}"""))
        val http = tools.invoke(ToolCall("h", "http_request", """{"url":"http://api.example.com/v1"}"""))
        assertTrue(file is ToolResult.Invalid)
        assertTrue(http is ToolResult.Invalid)
        assertEquals(0, calls)
    }

    @Test
    fun oversizedHttpOutputIsRejected() {
        val huge = "x".repeat(HttpPolicy.MAX_TOOL_OUTPUT_CHARS + 1)
        val result = broker(autoApprove = true, http = { huge }).invoke(
            ToolCall("h", "http_request", """{"url":"https://api.example.com/v1"}"""),
        )
        assertTrue(result is ToolResult.Invalid)
    }

    @Test
    fun searchDoesNotCrossUnauthorizedKnowledgeBase() {
        val tools = ToolBroker(
            setOf("knowledge.search"),
            ToolContext(
                search = { _, ids, _ -> ids.joinToString { "HIT:$it" } },
                readDocument = { _, _ -> "SECRET-KB-B" },
                grantedKnowledgeBaseIds = setOf("kb-a"),
            ),
        )
        val result = tools.invoke(
            ToolCall("s", "knowledge_search", """{"query":"q","knowledgeBaseIds":["kb-b"]}"""),
        ) as ToolResult.Value
        assertTrue("kb-b" !in result.json)
        assertTrue(result.json.contains("No authorized") || result.json.contains("hits"))
    }

    @Test
    fun readDocumentFromUnauthorizedKnowledgeBaseIsDenied() {
        val tools = ToolBroker(
            setOf("knowledge.read"),
            ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> "private-B-marker" },
                grantedKnowledgeBaseIds = setOf("kb-a"),
                documentKnowledgeBaseId = { id -> if (id == "doc-b") "kb-b" else "kb-a" },
            ),
        )
        val denied = tools.invoke(ToolCall("r", "read_document", """{"documentId":"doc-b"}"""))
        assertTrue(denied is ToolResult.Denied)
        assertTrue("private-B-marker" !in denied.toString())
        val empty = ToolBroker(
            setOf("knowledge.read"),
            ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> "secret" },
            ),
        ).invoke(ToolCall("r2", "read_document", """{"documentId":"doc-a"}"""))
        assertTrue(empty is ToolResult.Denied)
    }

    @Test
    fun postOnlyGrantRejectsGet() {
        var calls = 0
        val tools = ToolBroker(
            setOf("network.http"),
            ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> "{}" },
                httpGet = { calls += 1; """{"ok":true}""" },
                allowedHosts = setOf("api.example.com"),
                grantedMethods = setOf("POST"),
            ),
            autoApproveSideEffects = true,
        )
        val result = tools.invoke(ToolCall("h", "http_request", """{"url":"https://api.example.com/v1","method":"GET"}"""))
        assertTrue(result is ToolResult.Denied)
        assertEquals(0, calls)
    }

    @Test
    fun revokeStopsNewCallsOnSameBroker() {
        var grant = PermissionGrant("g", "i", "h", setOf("knowledge.read"), knowledgeBaseIds = setOf("kb-a"))
        val tools = ToolBroker(
            emptySet(),
            ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> "private-A-marker" },
                grantedKnowledgeBaseIds = setOf("kb-a"),
                documentKnowledgeBaseId = { "kb-a" },
            ),
            liveGrant = { grant },
        )
        val first = tools.invoke(ToolCall("r1", "read_document", """{"documentId":"doc-a"}"""))
        assertTrue(first is ToolResult.Value)
        grant = grant.copy(revoked = true, capabilities = emptySet(), knowledgeBaseIds = emptySet())
        val second = tools.invoke(ToolCall("r2", "read_document", """{"documentId":"doc-a"}"""))
        assertTrue(second is ToolResult.Denied)
    }

    @Test
    fun pendingHttpApproveAfterRevokeIsDenied() {
        var grant = PermissionGrant(
            "g",
            "i",
            "h",
            setOf("network.http"),
            hosts = setOf("api.example.com"),
            methods = setOf("GET"),
        )
        var calls = 0
        val tools = ToolBroker(
            emptySet(),
            ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> "{}" },
                httpGet = { calls += 1; """{"ok":true}""" },
                allowedHosts = setOf("api.example.com"),
            ),
            liveGrant = { grant },
        )
        val call = ToolCall("h1", "http_request", """{"url":"https://api.example.com/v1","method":"GET"}""")
        assertEquals(ToolResult.NeedsApproval, tools.invoke(call))
        grant = grant.copy(revoked = true, capabilities = emptySet())
        val approved = tools.approve("h1")
        assertTrue(approved is ToolResult.Denied)
        assertEquals(0, calls)
    }

    @Test
    fun ipLiteralsAreRejectedBeforeCallback() {
        var calls = 0
        val tools = broker(autoApprove = true, http = { calls += 1; it })
        val v6 = tools.invoke(ToolCall("a", "http_request", """{"url":"https://[fc00::1]/"}"""))
        val link = tools.invoke(ToolCall("b", "http_request", """{"url":"https://[fe80::1]/"}"""))
        val dotted = tools.invoke(ToolCall("c", "http_request", """{"url":"https://2130706433/"}"""))
        assertTrue(v6 is ToolResult.Invalid)
        assertTrue(link is ToolResult.Invalid)
        assertTrue(dotted is ToolResult.Invalid)
        assertEquals(0, calls)
    }

    @Test
    fun resolvedPrivateAddressIsRejected() {
        val error = runCatching {
            HttpPolicy.assertDestination(
                "https://public.example.invalid/",
                setOf("public.example.invalid"),
            ) { listOf(java.net.InetAddress.getByName("127.0.0.1")) }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        val v6 = runCatching {
            HttpPolicy.assertRequest("https://[fc00::1]/", setOf("fc00::1"))
        }.exceptionOrNull()
        assertTrue(v6 is IllegalStateException)
    }
}
