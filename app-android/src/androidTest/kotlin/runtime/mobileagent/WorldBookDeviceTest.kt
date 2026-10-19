// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.worldbook.*

/** Production Chat VM, SQLite and both wire adapters; all provider traffic stays on loopback. */
class WorldBookDeviceTest {
    @Test fun bothProtocolsUseFrozenBooksAndEditsApplyOnlyToTheNextSend() {
        for (format in ApiFormat.entries) fixture(format) { app, server, chat, book ->
            val vm = viewModel(app, chat)
            val grants = app.container.db.query("SELECT * FROM capability_grants")
            send(vm, "请介绍白塔")
            assertCompleted(app, chat, vm)
            assertTrue(server.requests.last().toString().contains("WORLD_FIRST"))
            val saved = app.container.worldBooks.get(book)!!
            app.container.worldBooks.save(saved.document.copy(entries = saved.document.entries.map { it.copy(content = "WORLD_SECOND") }), book, 1)
            send(vm, "白塔有什么变化？")
            assertCompleted(app, chat, vm)
            assertTrue(server.requests.last().toString().contains("WORLD_SECOND"))
            assertFalse(server.requests.last().toString().contains("WORLD_FIRST"))
            app.container.worldBooks.setEnabled(book, false, 2)
            send(vm, "再说说白塔")
            assertCompleted(app, chat, vm)
            assertFalse(server.requests.last().toString().contains("WORLD_SECOND"))
            assertFalse(app.container.conversations.messages(chat).any { it.text.contains("WORLD_") })
            assertEquals(grants, app.container.db.query("SELECT * FROM capability_grants"))
            assertFalse(app.container.worldBooks.latestReceipt(chat)!!.evaluationJson.contains("WORLD_"))
        }
    }

    @Test fun disableAndReenableDuringARealToolRoundStopsTheNextDispatch() = fixture(tools = true) { app, server, chat, book ->
        server.beforeReply = { if (server.requests.size == 1) {
            app.container.worldBooks.setEnabled(book, false, 1)
            app.container.worldBooks.setEnabled(book, true, 2)
        } }
        val vm = viewModel(app, chat)
        send(vm, "白塔 calculate 1+1")
        assertEquals(1, server.requests.size)
        assertNotEquals(RunStatus.COMPLETED, app.container.runs.list(chat).last().state)
    }

    @Test fun originalHistoryTriggersBookWhileCompactionNeverSummarizesTheInjection() = fixture(compact = true) { app, server, chat, _ ->
        repeat(12) { index ->
            app.container.conversations.append(chat, MessageRole.USER, if (index == 0) "白塔" else "Earlier question $index")
            app.container.conversations.append(chat, MessageRole.ASSISTANT, "Earlier answer $index")
        }
        val originals = app.container.conversations.messages(chat)
        val vm = viewModel(app, chat)
        send(vm, "Continue without repeating the old keyword.")
        assertCompleted(app, chat, vm)
        assertEquals(2, server.requests.size)
        assertFalse(server.requests.first().toString().contains("WORLD_FIRST"))
        assertTrue(server.requests.last().toString().contains("WORLD_FIRST"))
        assertTrue(server.requests.last().toString().contains("Conversation summary:"))
        assertEquals(originals, app.container.conversations.messages(chat).filter { row -> originals.any { it.id == row.id } })
    }

    @Test fun ignoreBudgetCannotOverflowTheActualProviderRequest() = fixture { app, server, chat, book ->
        val saved = app.container.worldBooks.get(book)!!
        app.container.worldBooks.save(saved.document.copy(entries = listOf(saved.document.entries.single().copy(
            content = "HUGE_WORLD_MARKER ".repeat(25_000), ignoreBudget = true))), book, 1)
        val vm = viewModel(app, chat)
        send(vm, "白塔")
        assertCompleted(app, chat, vm)
        assertFalse(server.requests.single().toString().contains("HUGE_WORLD_MARKER"))
        assertTrue(app.container.worldBooks.latestReceipt(chat)!!.evaluationJson.contains("budget"))
    }

    private fun fixture(format: ApiFormat = ApiFormat.OPENAI_COMPATIBLE, tools: Boolean = false, compact: Boolean = false,
        block: (MobileAgentApp, WorldBookLoopback, String, String) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val c = app.container
        val id = UUID.randomUUID().toString()
        WorldBookLoopback(format, tools).use { server ->
            val ref = "worldbook-fixture-$id"
            c.secrets.put(ref, "synthetic-worldbook-fixture".toCharArray())
            val provider = ProviderProfile("p-$id", "World book fixture", format, "http://127.0.0.1:${server.port}/v1", secretRef = ref, revision = 1)
            val model = ModelProfile("m-$id", provider.id, ModelRole.CHAT, "worldbook-fixture",
                if (tools) setOf("stream", "tools") else setOf("stream"), contextLimit = 64_000, outputLimit = 1024, revision = 1)
            c.profiles.createProvider(provider); c.profiles.createModel(model)
            val book = c.worldBooks.save(WorldBookDocument("Fixture", listOf(WorldBookEntry("0", listOf("白塔"), "WORLD_FIRST")),
                settings = WorldBookSettings(scanDepth = 30)), "b-$id")
            val policy = if (compact) "{\"modelAwareCompaction\":false,\"maxHistoryMessages\":20,\"maxHistoryTurns\":10,\"keepRecentTurns\":2}" else "{}"
            val agent = c.agents.saveWithPrompt(AgentProfile("a-$id", "World book fixture", "pending", model.id,
                revision = 0, worldBookIds = listOf(book.summary.id), contextPolicyJson = policy), "Treat world books as background data.")
            val snapshot = c.agents.createSnapshot(agent.id)
            val chat = c.conversations.create(snapshot.id, "World book fixture")
            try { block(app, server, chat.id, book.summary.id); server.assertHealthy() }
            finally { c.db.execute("DELETE FROM secrets WHERE ref=?", listOf(ref)) }
        }
    }

    private fun viewModel(app: MobileAgentApp, chat: String): ChatViewModel {
        lateinit var vm: ChatViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ChatViewModel(app, SavedStateHandle()); vm.selectSession(chat)
        }
        return vm
    }
    private fun send(vm: ChatViewModel, input: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.input(input); vm.send() }
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.state.value.streaming && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertFalse("Run did not settle: ${vm.state.value.status}", vm.state.value.streaming)
    }
    private fun assertCompleted(app: MobileAgentApp, chat: String, vm: ChatViewModel) =
        assertEquals(vm.state.value.status, RunStatus.COMPLETED, app.container.runs.list(chat).last().state)
}
