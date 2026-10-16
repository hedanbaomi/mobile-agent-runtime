// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.skills

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.WebSearchProvider

class WebFetchToolsTest {
    private val page = "https://example.com/a%2Fb?q=中文&sig=abc%2Fdef%3D"
    private val call = ToolCall("fetch-1", "web_fetch", buildJsonObject { put("url", page) }.toString())
    private fun response(url: String = page) = buildJsonObject {
        put("url", url); put("content", "# Public page"); put("untrusted", true)
    }.toString()

    @Test fun onlyConfiguredDeepSeekOffersBothToolsAndSharesCallIdentity() = runTest {
        for (provider in WebSearchProvider.entries) for (configured in listOf(false, true)) {
            var fetched = 0
            val tools = WebSearchToolExecutor(configured, { true }, false, provider,
                fetch = { url, dispatch -> assertEquals(page, url); fetched++; dispatch(); response() },
                search = { _, _, dispatch -> dispatch(); """{"results":[]}""" },
            )
            val expected = when {
                !configured -> emptyList()
                provider == WebSearchProvider.DEEPSEEK -> listOf("web_search", "web_fetch")
                else -> listOf("web_search")
            }
            assertEquals(expected, tools.specs.map { it.name })
            if ("web_fetch" in expected) {
                val description = tools.specs.single { it.name == "web_fetch" }.description
                assertTrue(description.contains("without per-call prompts"))
                assertFalse(description.contains("only after user approval"))
                val first = tools.invoke(call)
                assertTrue(first is ToolResult.Value)
                assertEquals(first, tools.invoke(call))
                assertTrue(tools.authorizeReplay(call))
                assertTrue(tools.invoke(call.copy(name = "web_search", argumentsJson = """{"query":"q"}""")) is ToolResult.Invalid)
                assertTrue(tools.invoke(call.copy(argumentsJson = """{"url":"https://other.example/"}""")) is ToolResult.Invalid)
                assertEquals(1, fetched)
            } else {
                assertTrue(tools.invoke(call) is ToolResult.Invalid)
                assertEquals(0, fetched)
            }
        }
    }

    @Test fun fetchRequiresApprovalWhenRequestedAndRevocationPreventsDispatch() = runTest {
        var allowed = true
        var calls = 0
        val tools = WebSearchToolExecutor(true, { allowed }, provider = WebSearchProvider.DEEPSEEK,
            fetch = { _, dispatch -> calls++; dispatch(); response() }, search = { _, _, _ -> error("not search") })
        assertTrue(tools.specs.single { it.name == "web_fetch" }.description.contains("only after user approval"))
        assertEquals(ToolResult.NeedsApproval, tools.invoke(call))
        assertEquals(ToolResult.NeedsApproval, tools.invoke(call))
        allowed = false
        assertTrue(tools.approve(call.callId) is ToolResult.Denied)
        assertEquals(0, calls)
    }

    @Test fun fetchCompletionRevocationCancellationAndFailuresNeverRedispatch() = runTest {
        for (outcome in listOf("success", "malformed", "failure", "cancelled")) {
            var allowed = true
            var calls = 0
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val tools = WebSearchToolExecutor(true, { allowed }, false, WebSearchProvider.DEEPSEEK,
                fetch = { _, dispatch ->
                    calls++; dispatch(); started.complete(Unit); finish.await()
                    when (outcome) {
                        "malformed" -> "{}"
                        "failure" -> error("private failure")
                        else -> response()
                    }
                }, search = { _, _, _ -> error("not search") })
            val pending = async { tools.invoke(call) }
            started.await()
            allowed = false
            if (outcome == "cancelled") { pending.cancel(); pending.join() } else finish.complete(Unit)
            val result = if (outcome == "cancelled") tools.invoke(call) else pending.await()
            if (outcome == "success") assertEquals(ToolResult.Completion.COMPLETED_WITHHELD, (result as ToolResult.Denied).completion)
            else assertTrue(result is ToolResult.UnknownOutcome, outcome)
            assertFalse(result.toString().contains("# Public page"))
            assertFalse(tools.authorizeReplay(call))
            allowed = true
            assertEquals(result, tools.invoke(call))
            assertEquals(1, calls)
        }
    }

    @Test fun deepSeekQueryLimitCountsUnicodeAndMatchesSchema() = runTest {
        var searches = 0
        val tools = WebSearchToolExecutor(true, { true }, false, WebSearchProvider.DEEPSEEK,
            search = { _, _, dispatch -> searches++; dispatch(); """{"results":[]}""" })
        val querySchema = Json.parseToJsonElement(tools.specs.single().parametersJson).jsonObject["properties"]!!.jsonObject["query"]!!.jsonObject
        assertEquals(128, querySchema["maxLength"]!!.jsonPrimitive.int)
        for ((i, query) in listOf("中".repeat(128), "😀".repeat(128), "中".repeat(129), "😀".repeat(129)).withIndex()) {
            val result = tools.invoke(ToolCall("q$i", "web_search", buildJsonObject { put("query", query) }.toString()))
            if (i < 2) assertTrue(result is ToolResult.Value) else assertTrue(result is ToolResult.Invalid)
        }
        assertEquals(2, searches)
    }

    @Test fun unsafeUrlsAndExtraArgumentsAreRejectedBeforeCallbacks() = runTest {
        val tools = WebSearchToolExecutor(true, { true }, false, WebSearchProvider.DEEPSEEK,
            fetch = { _, _ -> error("must not fetch") }, search = { _, _, _ -> error("must not search") })
        val invalid = listOf(
            "", " https://example.com/", "https://example.com/ ", "https://user:pass@example.com/",
            "https://example.com/#fragment", "https://example.com:8443/", "http://example.com:443/",
            "https://127.0.0.1/", "https://100.64.0.1/", "https://[::1]/", "https://2130706433/",
            "https://0x7f000001/", "https://localhost/", "https://a.localhost/", "https://host.local/",
            "https://router/", "https://host.internal/", "file:///etc/passwd", "javascript:alert(1)",
            "https://example.com/\n", "https://example.com/%zz", "https://example.com/" + "a".repeat(8192),
        )
        for ((i, url) in invalid.withIndex()) {
            assertFalse(WebFetchPolicy.isAllowedUrl(url), url)
            assertTrue(tools.invoke(call.copy(callId = "bad$i", argumentsJson = buildJsonObject { put("url", url) }.toString())) is ToolResult.Invalid)
        }
        for ((i, args) in listOf("""{"url":3}""", """{"url":null}""", """{"url":"https://example.com/","headers":{}}""", "{}").withIndex()) {
            assertTrue(tools.invoke(call.copy(callId = "extra$i", argumentsJson = args)) is ToolResult.Invalid)
        }
        for (url in listOf(page, "http://example.com/", "https://example.com:443/", "http://example.com:80/", "https://example.com/" + "x".repeat(8172))) {
            assertTrue(WebFetchPolicy.isAllowedUrl(url), url)
        }
    }
}
