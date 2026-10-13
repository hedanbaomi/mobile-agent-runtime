// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.domain.*

/** Synthetic local persistence only; no real provider, secret, or device authority is used. */
class ChatStorageProjectionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(): Pair<MobileAgentApp, List<String>> {
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val c = app.container
        val id = "storage-${UUID.randomUUID()}"
        c.profiles.createProvider(ProviderProfile(id, "Synthetic", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = "absent-$id", revision = 1))
        c.profiles.createModel(ModelProfile("model-$id", id, ModelRole.CHAT, "synthetic", setOf("stream"),
            contextLimit = 32768, outputLimit = 256, revision = 1))
        c.agents.saveWithPrompt(AgentProfile("agent-$id", "Synthetic", "pending", "model-$id", revision = 0), "Synthetic")
        val snapshot = c.agents.createSnapshot("agent-$id")
        return app to List(4) { c.conversations.create(snapshot.id, "Synthetic $it").id }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(10)
        }
        fail("Local projection did not settle before the bounded timeout")
    }
    private fun withVm(app: MobileAgentApp, handle: SavedStateHandle, block: (ChatViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        instrumentation.runOnMainSync { vm = ChatViewModel(app, handle); store.put("chat", vm) }
        try { block(vm) } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun slowReadCannotBlockMainOrReplaceANewerConversationAndEdit() {
        val (app, ids) = fixture()
        app.container.conversations.append(ids[0], MessageRole.USER, "from A")
        app.container.conversations.append(ids[1], MessageRole.USER, "from B")
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pool.execute {
                    val hold = first.compareAndSet(true, false)
                    try { if (hold) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }; block.run() }
                    finally { if (hold) finished.countDown() }
                }
            }
        }
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        try {
            instrumentation.runOnMainSync {
                vm = ChatViewModel(app, SavedStateHandle(mapOf("chat.selectedSessionId" to ids[0])), ChatRunExecution(), dispatcher)
                store.put("chat", vm)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { vm.selectSession(ids[1]); vm.input("newer typed text") }
            await { !vm.state.value.loading && vm.state.value.messages.any { it.text == "from B" } }
            release.countDown(); assertTrue(finished.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(ids[1], vm.state.value.selectedSessionId)
                assertEquals("newer typed text", vm.state.value.input)
                assertTrue(vm.state.value.messages.none { it.text == "from A" })
            }
        } finally { release.countDown(); instrumentation.runOnMainSync { store.clear() }; pool.shutdownNow() }
    }

    @Test fun uiPagesRecoverAllMessagesWithoutTruncatingDurableExecutionHistory() {
        val (app, ids) = fixture()
        repeat(121) { app.container.conversations.append(ids[0], MessageRole.USER, "message $it") }
        withVm(app, SavedStateHandle(mapOf("chat.selectedSessionId" to ids[0]))) { vm ->
            await { !vm.state.value.loading }
            instrumentation.runOnMainSync { assertEquals(50, vm.state.value.messages.size); assertTrue(vm.state.value.hasMoreMessages) }
            repeat(2) {
                instrumentation.runOnMainSync { vm.loadEarlierMessages() }
                await { !vm.state.value.loadingEarlierMessages }
            }
            instrumentation.runOnMainSync {
                assertEquals((0..120).map { "message $it" }, vm.state.value.messages.map { it.text })
                assertFalse(vm.state.value.hasMoreMessages)
            }
            assertEquals(121, app.container.conversations.messages(ids[0]).size)
        }
    }

    @Test fun multipleLargeDraftsKeepSavedStateSmallAndRestoreFromSqlite() = runBlocking {
        val (app, ids) = fixture()
        val handle = SavedStateHandle(mapOf("chat.selectedSessionId" to ids[0]))
        val body = "中文a\n".repeat(150_000)
        withVm(app, handle) { vm ->
            ids.forEachIndexed { index, id ->
                instrumentation.runOnMainSync { vm.selectSession(id) }
                await { !vm.state.value.loading }
                instrumentation.runOnMainSync { vm.input(body + index) }
            }
            instrumentation.runOnMainSync {
                assertTrue(handle.keys().none { it.startsWith("chat.draft.") })
                val parcel = Parcel.obtain()
                try { parcel.writeBundle(handle.savedStateProvider().saveState()); assertTrue(parcel.dataSize() < 8192) }
                finally { parcel.recycle() }
            }
        }
        app.container.chatDraftPersistence.flushAll()
        ids.forEachIndexed { index, id -> assertEquals(body + index, app.container.chatDrafts.load(chatDraftOwner(id, null))) }
        withVm(app, SavedStateHandle(mapOf("chat.selectedSessionId" to ids[0]))) { vm ->
            await { !vm.state.value.loading }
            instrumentation.runOnMainSync { assertEquals(body + 0, vm.state.value.input) }
        }
    }
}
