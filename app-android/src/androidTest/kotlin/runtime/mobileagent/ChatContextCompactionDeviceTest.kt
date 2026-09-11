// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.domain.*

/** Real Chat VM -> adapter -> loopback SSE -> durable SQLite, with synthetic fixtures only. */
class ChatContextCompactionDeviceTest {
    @Test fun longHistoryCompactsAndNextSendRestoresSummaryWithoutChangingOriginals() = fixture { app, server, conversation ->
        val original = app.container.conversations.messages(conversation)
        val vm = viewModel(app, conversation)
        send(vm, "Continue the original task.")
        val run = app.container.runs.list(conversation).last()
        assertEquals(vm.state.value.status, RunStatus.COMPLETED, run.state)
        assertEquals(2, run.modelRounds)
        assertEquals(28, run.inputTokens)
        assertEquals(14, run.outputTokens)
        val saved = app.container.contextCompactions.list(conversation).single()
        assertEquals(ContextCompactionState.SUCCEEDED, saved.state)
        assertEquals(21, saved.inputTokens)
        assertTrue(saved.sourceMessageIds.isNotEmpty())
        assertEquals(original, app.container.conversations.messages(conversation).filter { it.id in original.map { row -> row.id } })
        assertEquals(2, server.requests.size)
        assertTrue(server.requests.first()["tools"] == null || server.requests.first()["tools"]!!.jsonArray.isEmpty())
        assertTrue(server.requests[1].toString().contains("Original constraint: preserve the evidence."))
        assertTrue(server.requests[1].toString().contains("Conversation summary:"))
        send(vm, "Continue once more.")
        assertEquals(vm.state.value.status, RunStatus.COMPLETED, app.container.runs.list(conversation).last().state)
        assertEquals(3, server.requests.size)
        assertEquals(1, app.container.contextCompactions.list(conversation).size)
        assertTrue(server.requests.last().toString().contains("Conversation summary:"))
        assertEquals(1, vm.state.value.compactions.size)
    }

    @Test fun invalidSummaryPersistsFailureAndObservedUsageWithoutRetry() = fixture(invalidSummary = true) { app, server, conversation ->
        val original = app.container.conversations.messages(conversation)
        val vm = viewModel(app, conversation)
        send(vm, "Continue.")
        assertEquals(1, server.requests.size)
        val saved = app.container.contextCompactions.list(conversation).single()
        assertEquals(ContextCompactionState.FAILED, saved.state)
        assertEquals(21, saved.inputTokens)
        assertNull(saved.summaryJson)
        assertEquals(RunStatus.FAILED, app.container.runs.list(conversation).last().state)
        assertEquals(original, app.container.conversations.messages(conversation).filter { it.id in original.map { row -> row.id } })
        assertTrue(vm.state.value.messages.any { it.text.contains("summary", ignoreCase = true) || it.text.contains("压缩") })
    }

    @Test fun completedExchangesBeforeAnErrorRemainAvailableToLaterCompaction() = fixture { app, server, conversation ->
        // The last user turn contains one complete tool exchange and an incomplete tail.
        val store = app.container.conversations
        store.append(conversation, MessageRole.USER, "retain this completed exchange")
        store.append(conversation, MessageRole.ASSISTANT, "completed prefix", parts = listOf(ToolCallPart("kept-call", "fixture", "{}")))
        store.append(conversation, MessageRole.TOOL, "{\"value\":\"KEEP_COMPLETED_RESULT\"}",
            parts = listOf(ToolResultPart("kept-call", "{\"value\":\"KEEP_COMPLETED_RESULT\"}")))
        store.append(conversation, MessageRole.ASSISTANT, "incomplete tail", status = "ERROR")
        val vm = viewModel(app, conversation)
        send(vm, "Continue.")
        assertEquals(vm.state.value.status, RunStatus.COMPLETED, app.container.runs.list(conversation).last().state)
        assertTrue(server.requests.last().toString().contains("KEEP_COMPLETED_RESULT"))
        assertFalse(server.requests.last().toString().contains("incomplete tail"))
    }

    @Test fun roundLimitCompactsAndContinuesWithoutReplayingToolsOrLosingCheckpoints() = fixture(toolRounds = true) { app, server, conversation ->
        val vm = viewModel(app, conversation)
        send(vm, "Calculate three times, then finish.")
        val run = app.container.runs.list(conversation).last()
        assertEquals(vm.state.value.status, RunStatus.COMPLETED, run.state)
        assertEquals(5, run.modelRounds)
        assertEquals(3, run.toolCalls)
        assertEquals(5, server.requests.size)
        val saved = app.container.contextCompactions.list(conversation).single()
        assertEquals(ContextCompactionState.SUCCEEDED, saved.state)
        assertTrue(saved.reason.contains("round"))
        val messages = app.container.conversations.messages(conversation)
        assertTrue(messages.all { it.status == "COMPLETE" })
        val calls = messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>()
        val results = messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>()
        assertEquals(3, calls.size)
        assertEquals(3, results.size)
        assertEquals(3, calls.map { it.callId }.distinct().size)
        assertTrue(saved.sourceMessageIds.all { id -> messages.any { it.id == id && it.status == "COMPLETE" } })
        assertTrue(server.requests.last().toString().contains("Conversation summary:"))
    }

    @Test fun requestCapStopsTheToolLoopWhenAutoCompactIsOff() = fixture(toolRounds = true, requestCap = true) { app, server, conversation ->
        val store = app.container.conversations
        store.append(conversation, MessageRole.USER, "Original constraint: preserve this exact wording.")
        val original = store.messages(conversation)
        val vm = viewModel(app, conversation)
        send(vm, "Calculate until the request budget stops you.")
        val run = app.container.runs.list(conversation).last()
        // The explicit per-run request cap is honored even with compaction disabled.
        assertEquals(2, run.modelRounds)
        assertEquals(2, server.requests.size)
        assertEquals(RunStatus.BUDGET_EXHAUSTED, run.state)
        assertEquals("model-rounds", run.stopReason)
        // Both admitted dispatches issued a calculator tool call, so two real tool calls ran.
        assertEquals(2, run.toolCalls)
        val messages = store.messages(conversation)
        val calls = messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>()
        val results = messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>()
        assertEquals(2, calls.size)
        assertEquals(2, results.size)
        assertEquals(2, calls.map { it.callId }.distinct().size)
        assertEquals(calls.map { it.callId }.sorted(), results.map { it.callId }.distinct().sorted())
        // Compaction stayed off: no summary stored or injected, original rows untouched.
        assertTrue(app.container.contextCompactions.list(conversation).isEmpty())
        assertEquals(original, messages.filter { it.id in original.map { row -> row.id } })
    }

    private fun viewModel(app: MobileAgentApp, conversation: String): ChatViewModel {
        lateinit var vm: ChatViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ChatViewModel(app, SavedStateHandle())
            vm.selectSession(conversation)
        }
        return vm
    }

    private fun send(vm: ChatViewModel, text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.input(text); vm.send() }
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertFalse("Fixture run failed to settle: ${vm.state.value.status}", vm.state.value.streaming)
    }

    private fun fixture(invalidSummary: Boolean = false, toolRounds: Boolean = false, requestCap: Boolean = false, body: (MobileAgentApp, LoopbackChat, String) -> Unit) {
        check(BuildConfig.DEBUG)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val id = UUID.randomUUID().toString()
        LoopbackChat(invalidSummary, toolRounds).use { server ->
            val ref = "context-fixture-secret-$id"
            container.secrets.put(ref, "synthetic-context-fixture-only".toCharArray())
            val provider = ProviderProfile("context-provider-$id", "Local context fixture", ApiFormat.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.port}/v1", secretRef = ref, revision = 1)
            val model = ModelProfile("context-model-$id", provider.id, ModelRole.CHAT, "synthetic-context-model",
                if (toolRounds) setOf("stream", "tools") else setOf("stream"), contextLimit = 64_000, outputLimit = 1024, revision = 1)
            container.profiles.createProvider(provider); container.profiles.createModel(model)
            container.agents.saveWithPrompt(AgentProfile("context-agent-$id", "Context fixture", "pending", model.id,
                revision = 0, contextPolicyJson = "{\"maxHistoryMessages\":20,\"maxHistoryTurns\":10,\"keepRecentTurns\":2,\"maxModelRoundsPerSegment\":${if (toolRounds) 2 else 8}${if (requestCap) ",\"autoCompact\":false,\"maxModelRequestsPerRun\":2" else ""}}"), "Answer the fixture briefly.")
            val snapshot = container.agents.createSnapshot("context-agent-$id")
            val conversation = container.conversations.create(snapshot.id, "Synthetic context fixture")
            repeat(if (toolRounds) 0 else 12) { index ->
                container.conversations.append(conversation.id, MessageRole.USER,
                    if (index == 0) "Original constraint: preserve the evidence." else "Old question $index")
                container.conversations.append(conversation.id, MessageRole.ASSISTANT, "Old completed answer $index")
            }
            try { body(app, server, conversation.id); server.assertHealthy() }
            finally { container.db.execute("DELETE FROM secrets WHERE ref=?", listOf(ref)) }
        }
    }

    private class LoopbackChat(private val invalidSummary: Boolean, private val toolRounds: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        val port = server.localPort
        val requests = CopyOnWriteArrayList<JsonObject>()
        private val closed = AtomicBoolean()
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private var normalRequests = 0
        private val thread = Thread({ serve() }, "context-loopback-fixture").apply { isDaemon = true; start() }
        private fun serve() {
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    catch (_: Exception) { return }
                active.set(socket)
                try { socket.use {
                    it.soTimeout = 3000
                    val input = it.getInputStream()
                    val raw = ByteArrayOutputStream()
                    var suffix = 0
                    while (raw.size() < 16384) {
                        val next = input.read(); check(next >= 0); raw.write(next); suffix = (suffix shl 8) or next
                        if (suffix == 0x0d0a0d0a) break
                    }
                    val headers = raw.toString("US-ASCII").split("\r\n")
                    check(headers.first().startsWith("POST /v1/chat/completions "))
                    val length = headers.first { line -> line.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    check(length in 1..500_000)
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val n = input.read(bytes, offset, length - offset); check(n > 0); offset += n }
                    val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    requests.add(request); check(requests.size <= 5)
                    val summary = request["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
                        .startsWith("Summarize the supplied conversation data")
                    val content = if (summary) {
                        if (invalidSummary) "invalid summary" else """{"goals":["continue"],"constraints":["preserve evidence"],"decisions":[],"pending":[],"results":["older replies retained"]}"""
                    } else "continued"
                    if (!summary) normalRequests++
                    val callTool = toolRounds && !summary && normalRequests <= 3
                    if (callTool) check(request["tools"].toString().contains("calculator"))
                    val chunk = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                        put("delta", buildJsonObject {
                            if (callTool) put("tool_calls", buildJsonArray { add(buildJsonObject {
                                put("index", 0); put("id", "calculation-$normalRequests"); put("type", "function")
                                put("function", buildJsonObject { put("name", "calculator"); put("arguments", "{\"expression\":\"1+1\"}") })
                            }) }) else put("content", content)
                        }); put("finish_reason", if (callTool) "tool_calls" else "stop")
                    }) }); put("usage", buildJsonObject { put("prompt_tokens", if (summary) 21 else 7); put("completion_tokens", if (summary) 11 else 3) }) }
                    val response = "data: $chunk\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(response); flush()
                    }
                } } catch (error: Throwable) { if (!closed.get()) failure.compareAndSet(null, error) }
                finally { active.set(null) }
            }
        }
        fun assertHealthy() { assertNull(failure.get()?.javaClass?.simpleName, failure.get()) }
        override fun close() {
            closed.set(true); server.close(); active.getAndSet(null)?.close(); thread.join(2000)
            check(!thread.isAlive)
        }
    }
}
