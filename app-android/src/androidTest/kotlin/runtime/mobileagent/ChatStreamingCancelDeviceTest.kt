// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunStatus

/** Synthetic streamed body held open until the user cancels; no paid provider is contacted. */
class ChatStreamingCancelDeviceTest {
    @Test
    fun cancellationKeepsLatestPartialTextAndTerminalMarker() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val id = UUID.randomUUID().toString()
        val released = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serving = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream()
                        val header = ByteArrayOutputStream()
                        var tail = 0
                        while (header.size() < 16_384) {
                            val next = input.read()
                            check(next >= 0)
                            header.write(next)
                            tail = (tail shl 8) or next
                            if (tail == 0x0d0a0d0a) break
                        }
                        val length = header.toString("US-ASCII").split("\r\n")
                            .first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        repeat(length) { check(input.read() >= 0) }
                        val delta = "data: {\"choices\":[{\"delta\":{\"content\":\"visible partial\"},\"finish_reason\":null}]}\n\n"
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            write(delta.toByteArray())
                            flush()
                        }
                        released.await(10, TimeUnit.SECONDS)
                    }
                }
            }.apply { isDaemon = true; start() }
            val secretRef = "synthetic-stream-$id"
            container.secrets.put(secretRef, "synthetic-only".toCharArray())
            val provider = ProviderProfile("provider-stream-$id", "Synthetic stream", ApiFormat.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.localPort}/v1", secretRef = secretRef, revision = 1)
            val model = ModelProfile("model-stream-$id", provider.id, ModelRole.CHAT, "synthetic-stream",
                setOf("stream"), contextLimit = 4096, outputLimit = 512, revision = 1)
            container.profiles.createProvider(provider)
            container.profiles.createModel(model)
            val agentId = "agent-stream-$id"
            container.agents.saveWithPrompt(AgentProfile(agentId, "Synthetic stream", "pending", model.id,
                revision = 0), "Reply briefly.")
            val snapshot = container.agents.createSnapshot(agentId)
            val conversation = container.conversations.create(snapshot.id, "Synthetic stream")
            try {
                lateinit var vm: ChatViewModel
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    vm = ChatViewModel(app, SavedStateHandle())
                    vm.selectSession(conversation.id)
                    vm.input("Begin a streamed reply")
                    vm.send()
                }
                awaitUntil(15_000) { vm.state.value.messages.any { it.role == "assistant" && it.text.contains("visible partial") } }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.cancel() }
                awaitUntil(15_000) {
                    !vm.state.value.streaming && vm.state.value.messages.any {
                        it.role == "assistant" && it.eventSummary.contains("已取消接收")
                    }
                }
                val assistant = container.conversations.messages(conversation.id)
                    .last { it.role == MessageRole.ASSISTANT }
                assertTrue(assistant.text.contains("visible partial"))
                assertEquals("UNKNOWN_OUTCOME", assistant.status)
                assertEquals(MessageErrorCode.UNKNOWN_OUTCOME,
                    assistant.parts.filterIsInstance<ErrorPart>().last().code)
                assertTrue(vm.state.value.messages.last { it.role == "assistant" }.eventSummary.contains("已取消接收"))
                assertTrue(vm.state.value.status.contains("已取消接收"))
                assertEquals(RunStatus.UNKNOWN_OUTCOME, container.runs.list(conversation.id).last().state)
                assertFalse(vm.state.value.streaming)
            } finally {
                released.countDown()
                serving.join(2_000)
                container.db.execute("DELETE FROM secrets WHERE ref=?", listOf(secretRef))
            }
        }
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertTrue("Condition did not become true before deadline", condition())
    }
}
