// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

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
    fun expiredGrantDeniesFreshDispatchWithoutExecution() {
        var calls = 0
        var grant = PermissionGrant("g", "i", "h", setOf("knowledge.read"), knowledgeBaseIds = setOf("kb-a"))
        val tools = ToolBroker(
            emptySet(),
            ToolContext(
                search = { _, _, _ -> "{}" },
                readDocument = { _, _ -> calls += 1; "private-A-marker" },
                grantedKnowledgeBaseIds = setOf("kb-a"),
                documentKnowledgeBaseId = { "kb-a" },
            ),
            liveGrant = { grant },
        )
        assertTrue(tools.invoke(ToolCall("r1", "read_document", """{"documentId":"doc-a"}""")) is ToolResult.Value)
        grant = grant.copy(scopesJson = """{"expiresAt":"2020-01-01T00:00:00Z"}""")
        val denied = tools.invoke(ToolCall("r2", "read_document", """{"documentId":"doc-a"}"""))
        assertTrue(denied is ToolResult.Denied)
        assertEquals(1, calls)
    }

    @Test
    fun expiredGrantDeniesCachedReplayWithoutDisclosure() {
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
        val call = ToolCall("r1", "read_document", """{"documentId":"doc-a"}""")
        assertTrue(tools.invoke(call) is ToolResult.Value)
        // Same call id, same request: expiry alone (no scope-row change) must
        // deny the replay instead of disclosing the cached payload.
        grant = grant.copy(scopesJson = """{"expiresAt":"2020-01-01T00:00:00Z"}""")
        val replay = tools.invoke(call)
        assertTrue(replay is ToolResult.Denied)
        assertTrue(tools.authorizeReplay(call).not())
    }

    @Test
    fun healthyGrantAllowsReplayDisclosure() {
        val grant = PermissionGrant("g", "i", "h", setOf("knowledge.read"), knowledgeBaseIds = setOf("kb-a"))
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
        val call = ToolCall("r1", "read_document", """{"documentId":"doc-a"}""")
        val first = tools.invoke(call)
        assertTrue(first is ToolResult.Value)
        assertTrue(tools.authorizeReplay(call))
        assertEquals(first, tools.invoke(call))
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

    @Test
    fun reusedCallIdCannotChangeToolOrArgumentsOrReplacePendingApproval() {
        val tools = broker()
        val calculator = ToolCall("id", "calculator", """{"expression":"1+1"}""")
        assertTrue(tools.invoke(calculator) is ToolResult.Value)
        assertTrue(tools.invoke(calculator.copy(argumentsJson = """{"expression":"2+2"}""")) is ToolResult.Invalid)
        assertTrue(tools.invoke(calculator.copy(name = "http_request", argumentsJson = """{"url":"https://api.example.com/"}""")) is ToolResult.Invalid)
        val http = ToolCall("pending", "http_request", """{"url":"https://api.example.com/first"}""")
        assertEquals(ToolResult.NeedsApproval, tools.invoke(http))
        assertTrue(tools.invoke(http.copy(argumentsJson = """{"url":"https://api.example.com/replaced"}""")) is ToolResult.Invalid)
        assertTrue(tools.approve("pending") is ToolResult.Value)
    }

    @Test
    fun malformedParameterTypesAndBoundsNeverReachHostCallbacks() {
        var calls = 0
        val tools = ToolBroker(setOf("knowledge.search", "knowledge.read", "network.http"), ToolContext(
            search = { _, _, _ -> calls++; "{}" },
            readDocument = { _, _ -> calls++; "{}" },
            httpGet = { calls++; "{}" },
            grantedKnowledgeBaseIds = setOf("kb-a"),
            documentKnowledgeBaseId = { "kb-a" },
            allowedHosts = setOf("api.example.com"),
        ), autoApproveSideEffects = true)
        val inputs = listOf(
            "knowledge_search" to """{"query":7}""",
            "knowledge_search" to """{"query":"q","topK":"8"}""",
            "knowledge_search" to """{"query":"q","topK":0}""",
            "knowledge_search" to """{"query":"q","topK":101}""",
            "knowledge_search" to """{"query":"q","topK":1.5}""",
            "knowledge_search" to """{"query":"q","knowledgeBaseIds":"kb-a"}""",
            "knowledge_search" to """{"query":"q","knowledgeBaseIds":["kb-a",null]}""",
            "knowledge_search" to """{"query":"q","extra":true}""",
            "read_document" to """{"documentId":[]}""",
            "read_document" to """{"documentId":"doc","maxChars":16385}""",
            "http_request" to """{"url":null}""",
            "http_request" to """{"url":"https://api.example.com/","method":{}}""",
            "calculator" to """{"expression":true}""",
        )
        inputs.forEachIndexed { index, (name, arguments) ->
            assertTrue(tools.invoke(ToolCall("bad-$index", name, arguments)) is ToolResult.Invalid, arguments)
        }
        assertEquals(0, calls)
    }

    @Test
    fun cachedResultIsNotDisclosedAfterGrantScopeChanges() {
        var grant = PermissionGrant("g", "i", "h", setOf("knowledge.read"), knowledgeBaseIds = setOf("kb-a"))
        val tools = ToolBroker(emptySet(), ToolContext(
            search = { _, _, _ -> "{}" }, readDocument = { _, _ -> "private-A-marker" },
            documentKnowledgeBaseId = { "kb-a" },
        ), liveGrant = { grant })
        val call = ToolCall("read", "read_document", """{"documentId":"doc-a"}""")
        assertTrue(tools.invoke(call) is ToolResult.Value)
        grant = grant.copy(knowledgeBaseIds = setOf("kb-b"))
        assertTrue(tools.invoke(call) is ToolResult.Denied)
    }

    @Test
    fun interruptedBlockingToolIsNotConvertedToCachedInvalidResult() {
        val tools = broker(autoApprove = true, http = { throw InterruptedException("cancelled") })
        assertThrows(InterruptedException::class.java) {
            tools.invoke(ToolCall("cancel", "http_request", """{"url":"https://api.example.com/"}"""))
        }
    }

    @Test
    fun webSearchRequiresApprovalAndExecutesOnlyOnce() = runBlocking {
        var calls = 0
        val tools = WebSearchToolExecutor(
            configured = true,
            authorized = { true },
            search = { query, count, dispatched ->
                dispatched()
                calls++
                """{"results":[{"title":"$query","url":"https://example.com/","snippet":"$count"}]}"""
            },
        )
        val call = ToolCall("search-1", "web_search", """{"query":"android agent","maxResults":3}""")
        assertEquals(ToolResult.NeedsApproval, tools.invoke(call))
        val result = tools.approve(call.callId) as ToolResult.Value
        assertTrue(result.json.contains("android agent"))
        assertEquals(result, tools.invoke(call))
        assertEquals(1, calls)
    }

    @Test
    fun webSearchIsAbsentWithoutConfigurationAndRevocationStopsApproval() = runBlocking {
        val absent = WebSearchToolExecutor(false, { true }) { _, _, _ -> "{}" }
        assertTrue(absent.specs.isEmpty())
        assertTrue(absent.invoke(ToolCall("missing", "web_search", """{"query":"q"}""")) is ToolResult.Invalid)

        var authorized = true
        var calls = 0
        val tools = WebSearchToolExecutor(true, { authorized }) { _, _, _ -> calls++; "{}" }
        val call = ToolCall("revoked", "web_search", """{"query":"q"}""")
        assertEquals(ToolResult.NeedsApproval, tools.invoke(call))
        authorized = false
        assertTrue(tools.approve(call.callId) is ToolResult.Denied)
        assertEquals(0, calls)
    }

    @Test
    fun webSearchRejectsOversizedQueryAndMarksPostDispatchFailureUnknown() = runBlocking {
        var calls = 0
        val tools = WebSearchToolExecutor(true, { true }) { _, _, dispatched ->
            dispatched()
            calls++
            error("secret transport detail")
        }
        val tooLong = ToolCall("long", "web_search", """{"query":"${"x".repeat(401)}"}""")
        assertTrue(tools.invoke(tooLong) is ToolResult.Invalid)
        val call = ToolCall("unknown", "web_search", """{"query":"q"}""")
        assertEquals(ToolResult.NeedsApproval, tools.invoke(call))
        val result = tools.approve(call.callId)
        assertTrue(result is ToolResult.UnknownOutcome)
        assertTrue("secret transport detail" !in result.toString())
        assertEquals(1, calls)
    }
}
