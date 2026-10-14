// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ChatImageUpload
import runtime.mobileagent.domain.ImagePart
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile

/** Composer lifetime and original delivery on the real ViewModel; synthetic providers only. */
@RunWith(AndroidJUnit4::class)
class ChatViewModelImageDraftTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
    private val gif = ChatImageUpload("GIF89a".toByteArray(), "image/gif")

    private fun fixture(capabilities: Set<String>): Pair<String, String> {
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")
        container.profiles.createProvider(ProviderProfile("provider.draft.$suffix", "Draft fixture",
            ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "fixture-draft-$suffix", revision = 1))
        container.profiles.createModel(ModelProfile("model.draft.$suffix", "provider.draft.$suffix", ModelRole.CHAT,
            "fixture-draft", capabilities, contextLimit = 4096, outputLimit = 512, revision = 1))
        container.agents.saveWithPrompt(AgentProfile("agent.draft.$suffix", "Draft fixture", "pending",
            "model.draft.$suffix", revision = 0), "Draft fixture.")
        val snapshot = container.agents.createSnapshot("agent.draft.$suffix")
        val first = container.conversations.create(snapshot.id, "Draft A")
        val second = container.conversations.create(snapshot.id, "Draft B")
        return first.id to second.id
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun sameSessionHistoryCompletionKeepsAttachedAndRemovedImagesVisible() {
        val (first, _) = fixture(setOf("stream", "image"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pool.execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); block.run() }
            }
        }
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        fun awaitProjection() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                var loaded = false
                onMain { loaded = !vm.state.value.loading && vm.state.value.selectedSessionId == first }
                if (loaded) return
                Thread.sleep(10)
            }
            org.junit.Assert.fail("Same-session history did not complete")
        }
        try {
            var ids = emptyList<String>()
            onMain {
                vm = ChatViewModel(app, SavedStateHandle(), ChatRunExecution(), dispatcher)
                store.put("images", vm)
                vm.selectSession(first)
                vm.attachImages(List(9) { gif to null })
                ids = vm.state.value.pendingImages.map { it.id }
                assertEquals(8, ids.size)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            release.countDown()
            awaitProjection()
            onMain {
                assertEquals(ids, vm.state.value.pendingImages.map { it.id })
                vm.removePendingImage(ids.first())
                vm.reload()
            }
            awaitProjection()
            onMain {
                assertEquals(ids.drop(1), vm.state.value.pendingImages.map { it.id })
                vm.clearImageDraft()
                vm.reload()
            }
            awaitProjection()
            onMain { assertTrue(vm.state.value.pendingImages.isEmpty()) }
        } finally {
            release.countDown()
            onMain { store.clear() }
            pool.shutdownNow()
        }
    }

    @Test
    fun uploadedOriginalAndTextFollowupReachMainRequestsInBothProtocols() {
        app.ensureHostInitialized()
        val container = app.container
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jGd0AAAAASUVORK5CYII=")
        for (format in listOf(ApiFormat.OPENAI_COMPATIBLE, ApiFormat.OPENAI_RESPONSES)) {
            val suffix = UUID.randomUUID().toString().replace("-", "")
            val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 30_000
                val serving = Thread {
                    try {
                        repeat(2) {
                            server.accept().use { socket ->
                                socket.soTimeout = 30_000
                                val input = socket.getInputStream()
                                val header = java.io.ByteArrayOutputStream()
                                var tail = 0
                                while (header.size() < 16_384) {
                                    val next = input.read(); check(next >= 0)
                                    header.write(next); tail = (tail shl 8) or next
                                    if (tail == 0x0d0a0d0a) break
                                }
                                val length = header.toString("US-ASCII").split("\r\n")
                                    .first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                                check(length in 1..1_048_576)
                                val body = ByteArray(length)
                                var read = 0
                                while (read < length) { val n = input.read(body, read, length - read); check(n > 0); read += n }
                                bodies += body.toString(Charsets.UTF_8)
                                val stream = if (format == ApiFormat.OPENAI_COMPATIBLE)
                                    "data: {\"choices\":[{\"delta\":{\"content\":\"image answer\"}}]}\n\ndata: [DONE]\n\n"
                                else "data: {\"type\":\"response.output_text.delta\",\"delta\":\"image answer\"}\n\n" +
                                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(stream.toByteArray()); flush()
                                }
                            }
                        }
                    } catch (error: Throwable) { failure.set(error) }
                }.apply { isDaemon = true; start() }
                val secret = "direct-image-$suffix"
                container.secrets.put(secret, "synthetic-only".toCharArray())
                val provider = ProviderProfile("provider.direct.$suffix", "Synthetic loopback", format,
                    "http://127.0.0.1:${server.localPort}/v1", secretRef = secret, revision = 1)
                container.profiles.createProvider(provider)
                val model = ModelProfile("model.direct.$suffix", provider.id, ModelRole.CHAT,
                    "synthetic-direct", setOf("stream", "image"), contextLimit = 64_000, outputLimit = 512, revision = 1)
                container.profiles.createModel(model)
                val agent = "agent.direct.$suffix"
                container.agents.saveWithPrompt(AgentProfile(agent, "Direct fixture", "pending", model.id, revision = 0), "Describe the original image.")
                lateinit var vm: ChatViewModel
                onMain {
                    vm = ChatViewModel(app, SavedStateHandle())
                    vm.selectAgent(agent)
                    vm.sendWithImages(listOf(ChatImageUpload(png, "image/png")))
                }
                try {
                    fun awaitRun() {
                        val deadline = System.currentTimeMillis() + 30_000
                        while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(50)
                        assertFalse("${vm.state.value.status}; server=${failure.get()}", vm.state.value.streaming)
                        assertTrue(vm.state.value.error.orEmpty(), vm.state.value.error.isNullOrEmpty())
                    }
                    awaitRun()
                    assertEquals("No separate analysis request", 1, bodies.size)
                    val session = requireNotNull(vm.state.value.selectedSessionId)
                    onMain { vm.input("Read the same original again."); vm.send() }
                    awaitRun()
                    assertEquals(2, bodies.size)
                    val dataUri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
                    bodies.forEach { body ->
                        assertTrue(body, body.contains(dataUri))
                        assertTrue(body.contains(if (format == ApiFormat.OPENAI_COMPATIBLE) "image_url" else "input_image"))
                        assertFalse(body.contains("untrusted-visual-evidence-analysis"))
                        assertTrue(body.contains("Describe the original image."))
                    }
                    assertTrue(container.runs.list(session).all { it.state == runtime.mobileagent.domain.RunStatus.COMPLETED && it.modelRounds == 1 })
                    assertTrue(container.conversations.messages(session).none { it.metadataJson.contains("\"visualBatchAnalysis\":true") })
                } finally {
                    onMain { vm.cancel() }
                    server.close(); serving.join(2_000)
                }
            }
        }
    }

    @Test
    fun capLimitRemovalAndSessionSwitchKeepStripTruthful() {
        val (first, second) = fixture(setOf("stream", "image"))
        lateinit var vm: ChatViewModel
        onMain {
            vm = ChatViewModel(app, SavedStateHandle())
            vm.selectSession(first)
            vm.attachImages(List(9) { gif to null })
        }
        assertEquals(ChatImageUpload.MAX_IMAGES_PER_MESSAGE, vm.state.value.pendingImages.size)
        assertTrue(vm.state.value.status.contains("已忽略 1 张"))

        onMain { vm.removePendingImage(vm.state.value.pendingImages.first().id) }
        assertEquals(7, vm.state.value.pendingImages.size)

        // A read that finishes after the user switched pages must not land on the new page.
        onMain { vm.selectSession(second) }
        assertTrue(vm.state.value.pendingImages.isEmpty())
        onMain { vm.attachImages(listOf(gif to null), forSession = first) }
        assertTrue(vm.state.value.pendingImages.isEmpty())
        assertTrue(vm.state.value.status.contains("未添加"))

        onMain { vm.attachImages(listOf(gif to null)); vm.clearImageDraft() }
        assertTrue(vm.state.value.pendingImages.isEmpty())
    }

    @Test
    fun pickerStartedOnNewChatCannotAttachAfterSwitchingAgents() {
        val (first, _) = fixture(setOf("stream", "image"))
        val (second, _) = fixture(setOf("stream", "image"))
        val firstAgent = app.container.agents.getSnapshot(app.container.conversations.get(first)!!.snapshotId)!!.agentId
        val secondAgent = app.container.agents.getSnapshot(app.container.conversations.get(second)!!.snapshotId)!!.agentId
        lateinit var vm: ChatViewModel
        onMain {
            vm = ChatViewModel(app, SavedStateHandle())
            vm.selectAgent(firstAgent)
            vm.selectAgent(secondAgent)
            vm.attachImages(listOf(gif to null), forSession = null, forAgent = firstAgent)
        }
        assertTrue(vm.state.value.pendingImages.isEmpty())
        assertTrue(vm.state.value.status.contains("未添加"))
    }

    @Test
    fun latePickerResultWhileSendingIsRejectedWithVisibleFeedback() {
        val (first, _) = fixture(setOf("stream", "image"))
        onMain {
            val vm = ChatViewModel(app, SavedStateHandle())
            vm.selectSession(first)
            vm.input("synthetic input")
            vm.send()
            assertTrue(vm.state.value.streaming)
            vm.attachImages(listOf(gif to null))
            assertTrue(vm.state.value.pendingImages.isEmpty())
            assertTrue(vm.state.value.status.contains("所选图片未添加"))
        }
    }

    @Test
    fun textOnlyModelRejectsBeforePersistenceAndKeepsDraft() {
        val (first, _) = fixture(setOf("stream"))
        lateinit var vm: ChatViewModel
        onMain {
            vm = ChatViewModel(app, SavedStateHandle())
            vm.selectSession(first)
            vm.input("describe")
            vm.attachImages(listOf(gif to null))
            vm.send()
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(50)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse(vm.state.value.streaming)
        assertTrue(vm.state.value.error.orEmpty().contains("不支持图片"))
        assertEquals(1, vm.state.value.pendingImages.size)
        assertEquals("describe", vm.state.value.input)
        assertTrue(app.container.conversations.messages(first).none { message -> message.parts.any { it is ImagePart } })
    }
}
