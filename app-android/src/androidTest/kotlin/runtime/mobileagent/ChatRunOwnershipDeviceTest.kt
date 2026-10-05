// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.agent.RuntimeEvent
import runtime.mobileagent.domain.*
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.skills.ToolCall

/** Real ChatViewModel and SQLite with a controlled runtime/deadline, no provider request. */
class ChatRunOwnershipDeviceTest {
    @Test fun watchdogSettlesRealApprovalCallbackWithoutPublishingIntoAnotherConversation() = fixture { app, a, b ->
        val deadline = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val approvalReturned = CountDownLatch(1)
        val decision = AtomicReference<Boolean>()
        lateinit var approve: suspend (ToolCall) -> Boolean
        val execution = ChatRunExecution(
            awaitWatchdog = { deadline.await() },
            approvalReady = { approve = it },
            collectEvents = { _, accept -> withContext(NonCancellable) {
                accept(RuntimeEvent.ToolApprovalRequested("approval-A", "calculator", "{}"))
                decision.set(withContext(Dispatchers.IO + NonCancellable) {
                    approve(ToolCall("approval-A", "calculator", "{}"))
                })
                approvalReturned.countDown()
                resume.await()
                // Invoke the same real callback after detachment as well.
                assertFalse(withContext(Dispatchers.IO + NonCancellable) {
                    approve(ToolCall("late-approval-A", "calculator", "{}"))
                })
                val run = app.container.runs.list(a).single()
                accept(RuntimeEvent.RunFinished(run.runId, RunState.CANCELLED, "cancelled approval", 0, 0))
            } },
        )
        val vm = viewModel(app, a, execution)
        main { vm.input("Wait for approval"); vm.send() }
        await { vm.state.value.pendingTool?.id == "approval-A" }
        deadline.complete(Unit)
        await { !vm.state.value.streaming }
        main { vm.selectSession(b); vm.input("B approval draft") }
        val selected = vm.state.value
        assertTrue(approvalReturned.await(15, TimeUnit.SECONDS))
        assertEquals(false, decision.get())
        resume.complete(Unit)
        await { app.container.runs.list(a).single().finishedAt != null }
        main { }
        assertEquals(selected, vm.state.value)
        assertNull(vm.state.value.pendingTool)
        assertTrue(app.container.runs.list(b).isEmpty())
        val invocation = app.container.runs.invocations(app.container.runs.list(a).single().runId).single()
        assertEquals("CANCELLED", invocation.state)
        assertEquals("DENIED", invocation.permissionDecision)
    }

    @Test fun lateRequestErrorApprovalAndCleanupCannotOverwriteSelectedConversation() = fixture { app, a, b ->
        val arrived = CountDownLatch(1)
        val released = CountDownLatch(1)
        val deadline = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val execution = ChatRunExecution(
            awaitWatchdog = { deadline.await() },
            collectEvents = { _, accept ->
                // Simulate a storage/runtime continuation that ignores cancellation until
                // after delivery. The normal runtime uses a cancellable rendezvous flow.
                withContext(NonCancellable) {
                    arrived.countDown()
                    resume.await()
                    accept(RuntimeEvent.RequestPrepared("late-A", "fixture", emptyList(), emptyList(),
                        emptyList(), emptyList(), "A_PRIVATE_PREVIEW"))
                    accept(RuntimeEvent.ModelEvent(ModelEvent.TextDelta("A durable partial answer")))
                    accept(RuntimeEvent.ToolApprovalRequested("late-call-A", "calculator", "{}"))
                    accept(RuntimeEvent.ModelEvent(ModelEvent.Failed("late A failure")))
                    val run = app.container.runs.list(a).single()
                    accept(RuntimeEvent.RunFinished(run.runId, RunState.FAILED, "late A failure", 1, 0))
                    released.countDown()
                }
            },
        )
        val vm = viewModel(app, a, execution)
        main { vm.input("A request"); vm.send() }
        assertTrue("Runtime must reach controlled barrier", arrived.await(15, TimeUnit.SECONDS))
        deadline.complete(Unit)
        await { !vm.state.value.streaming }
        main { vm.selectSession(b); vm.input("B draft") }
        val bState = vm.state.value
        // A detached task still owns execution until it finishes; sending does not erase B input.
        main { vm.send() }
        assertEquals("B draft", vm.state.value.input)
        assertTrue(app.container.runs.list(b).isEmpty())
        val pendingBState = vm.state.value
        resume.complete(Unit)
        assertTrue(released.await(15, TimeUnit.SECONDS))
        await { app.container.runs.list(a).single().finishedAt != null }
        main { /* Drain Main including the ownership cleanup. */ }
        assertEquals(pendingBState, vm.state.value)
        assertEquals(bState.selectedSessionId, vm.state.value.selectedSessionId)
        assertEquals(bState.messages, vm.state.value.messages)
        assertNull(vm.state.value.requestPreview)
        assertNull(vm.state.value.pendingTool)
        assertNull(vm.unknownRetry.value)
        val rows = app.container.conversations.messages(a)
        assertTrue(rows.any { it.role == MessageRole.USER && it.text == "A request" })
        assertTrue(rows.any { it.role == MessageRole.ASSISTANT && it.text.contains("A durable partial answer") })
        assertFalse(app.container.conversations.messages(b).any { it.text.contains("A durable") })
        assertEquals(RunStatus.FAILED, app.container.runs.list(a).single().state)
        assertEquals("FAILED", app.container.runs.invocations(app.container.runs.list(a).single().runId).single().state)
    }

    @Test fun returningToTimedOutConversationDoesNotReattachOldPreviewOrStatus() = fixture { app, a, b ->
        val arrived = CountDownLatch(1)
        val deadline = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val execution = ChatRunExecution(
            awaitWatchdog = { deadline.await() },
            collectEvents = { _, accept -> withContext(NonCancellable) {
                arrived.countDown()
                resume.await()
                accept(RuntimeEvent.RequestPrepared("late-same", "fixture", emptyList(), emptyList(),
                    emptyList(), emptyList(), "OLD_A_PREVIEW"))
                val run = app.container.runs.list(a).single()
                accept(RuntimeEvent.RunFinished(run.runId, RunState.COMPLETED, null, 1, 0))
            } },
        )
        val vm = viewModel(app, a, execution)
        main { vm.input("A request"); vm.send() }
        assertTrue(arrived.await(15, TimeUnit.SECONDS))
        deadline.complete(Unit)
        await { !vm.state.value.streaming }
        main { vm.selectSession(b); vm.selectSession(a) }
        val selected = vm.state.value
        resume.complete(Unit)
        await { app.container.runs.list(a).single().finishedAt != null }
        main { }
        assertEquals(selected, vm.state.value)
        assertNull(vm.state.value.requestPreview)
        assertEquals(RunStatus.COMPLETED, app.container.runs.list(a).single().state)
    }

    private fun viewModel(app: MobileAgentApp, id: String, execution: ChatRunExecution): ChatViewModel {
        lateinit var vm: ChatViewModel
        main { vm = ChatViewModel(app, SavedStateHandle(), execution); vm.selectSession(id) }
        return vm
    }

    private fun fixture(body: (MobileAgentApp, String, String) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val id = UUID.randomUUID().toString()
        val ref = "ownership-fixture-$id"
        container.secrets.put(ref, "synthetic-ownership-test-only".toCharArray())
        val provider = ProviderProfile("owner-provider-$id", "Ownership fixture", ApiFormat.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", secretRef = ref, revision = 1)
        val model = ModelProfile("owner-model-$id", provider.id, ModelRole.CHAT, "fixture",
            setOf("stream", "tools"), contextLimit = 64_000, outputLimit = 1024, revision = 1)
        container.profiles.createProvider(provider)
        container.profiles.createModel(model)
        container.agents.saveWithPrompt(AgentProfile("owner-agent-$id", "Ownership fixture", "pending", model.id,
            revision = 0, contextPolicyJson = "{\"autoCompact\":false}"), "Answer briefly.")
        val snapshot = container.agents.createSnapshot("owner-agent-$id")
        val a = container.conversations.create(snapshot.id, "A ownership fixture").id
        val b = container.conversations.create(snapshot.id, "B ownership fixture").id
        container.conversations.append(b, MessageRole.USER, "B stored message")
        try { body(app, a, b) }
        finally { container.db.execute("DELETE FROM secrets WHERE ref=?", listOf(ref)) }
    }

    private fun main(body: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(body)

    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("Controlled continuation did not settle", condition())
    }
}
