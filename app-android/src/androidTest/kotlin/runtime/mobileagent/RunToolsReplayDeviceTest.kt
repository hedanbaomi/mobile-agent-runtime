// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunBudget
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.agent.AgentRuntime
import runtime.mobileagent.agent.AgentRuntimeRequest
import runtime.mobileagent.agent.EffectivePrompt
import runtime.mobileagent.agent.RuntimeEvent
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.domain.ToolResultPart
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.observation.*
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.tooling.ToolExecutorFactory
import runtime.mobileagent.provider.*

/**
 * Cached tool results must be re-validated for disclosure before replay.
 *
 * call success → revoke grant → duplicate same call must produce:
 * no external dispatch, no cached-data disclosure (DENIED), and no
 * re-execution of the original tool.  A closed run must deny replay too.
 */
@RunWith(AndroidJUnit4::class)
class RunToolsReplayDeviceTest {
    @Test fun randomNumberIsAvailableWithoutGrantsAndReplayKeepsTheDraw(): Unit = runBlocking {
        val chain = prepareChain("random-number")
        val run = newRun(chain)
        val tools = RunTools(chain.container, chain.app, chain.snapshot, run, false, false)
        try {
            assertTrue(tools.executor.specs.any { it.name == "random_number" })
            val call = ToolCall("die", "random_number", """{"min":1,"max":20}""")
            val result = tools.executor.invoke(call)
            assertTrue(result is ToolResult.Value)
            val invocationId = tools.runtimeInvocationId(call.callId)
            repeat(10) {
                assertEquals(result, tools.executor.invoke(call))
                assertEquals(invocationId, tools.runtimeInvocationId(call.callId))
                assertTrue(tools.executor.authorizeReplay(call))
            }
            assertTrue(tools.executor.invoke(call.copy(callId = "another-die")) is ToolResult.Value)
            assertNotEquals(invocationId, tools.runtimeInvocationId("another-die"))
            run.state = RunState.COMPLETED
            assertFalse(tools.executor.authorizeReplay(call))
            assertTrue(tools.executor.invoke(call) is ToolResult.Denied)
        } finally { tools.executor.close() }
    }

    @Test fun runtimeStallRecoversTheExactActionWhenModelCallIdsAreReused(): Unit = runBlocking {
        withTimeout(15_000) {
            val chain = prepareChain("runtime-device-stall")
            val registry = ScreenObservationRegistry.get(chain.app)
            val previous = registry.access.value.consent
            registry.setConsent(false)
            var dispatches = 0
            val run = AgentRun("run.${UUID.randomUUID()}", chain.snapshot.id, "conversation.${UUID.randomUUID()}",
                budget = RunBudget(stallTimeoutMs = 250))
            val port = object : ScreenActionPort, ScreenObservationPort {
                override val access = MutableStateFlow(ScreenAccess(true, true, 1, true))
                override val changes = MutableStateFlow(1L)
                override suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean,
                    onDispatch: () -> Unit): Boolean {
                    check(authorized()); onDispatch(); dispatches++
                    return true
                }
                override suspend fun capture(expectedPackage: String?): ScreenSnapshot = awaitCancellation()
            }
            val action = ScreenActionToolExecutor(port, { ScreenActionAuthority(Authority.SHIZUKU, 1) }, { true })
            val tools = RunTools(chain.container, chain.app, chain.snapshot, run, false, false, runExecutor = action)
            try {
                val first = ToolCall("reused-model-id", "device_act",
                    """{"observation_id":"${UUID.randomUUID()}","action":"click","node_index":0}""")
                val second = first.copy(argumentsJson =
                    """{"observation_id":"${UUID.randomUUID()}","action":"click","node_index":1,"observe_after":{}}""")
                var round = 0
                val adapter = object : ModelAdapter {
                    override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
                    override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
                    override fun stream(request: ModelRequest, secret: CharArray) = flow {
                        when (round++) {
                            0 -> emit(ModelEvent.ToolCallDelta(first.callId, first.name, first.argumentsJson))
                            1 -> emit(ModelEvent.ToolCallDelta(second.callId, second.name, second.argumentsJson))
                            else -> emit(ModelEvent.TextDelta("done"))
                        }
                        emit(ModelEvent.Completed)
                    }
                }
                val invocationIds = mutableListOf<String>()
                val events = AgentRuntime(adapter, onApprove = { call ->
                    invocationIds += requireNotNull(tools.runtimeInvocationId(call.callId))
                    true
                }).run(AgentRuntimeRequest(run, EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello"),
                    "model", charArrayOf(), toolsEnabled = true, executor = tools.executor,
                    confirmedToolResultAfterTimeout = tools::confirmedDeviceActionAfterTimeout)).toList()
                val results = events.filterIsInstance<RuntimeEvent.ToolResultProduced>()
                assertEquals(listOf("VALUE", "VALUE"), results.map { it.status })
                assertTrue(results.last().resultJson.contains("\"status\":\"timeout\""))
                assertEquals(RunState.COMPLETED, run.state)
                assertEquals(2, dispatches)
                assertEquals(2, invocationIds.toSet().size)
                assertNull(tools.confirmedDeviceActionReceipt(second.callId))
                assertEquals(invocationIds.last(), tools.confirmedDeviceActionReceipt(invocationIds.last())?.invocationId)
                assertNull(tools.confirmedDeviceActionAfterTimeout(second.copy(argumentsJson = "{}")))
                assertEquals(results.last().resultJson, tools.confirmedDeviceActionAfterTimeout(second)?.json)
                assertEquals(2, dispatches)
            } finally { tools.executor.close(); registry.setConsent(previous) }
        }
    }

    @Test fun stalledFollowUpPreservesOnlyConfirmedActionsWithoutRedispatch(): Unit = runBlocking {
        withTimeout(15_000) {
            val chain = prepareChain("confirmed-device-stall")
            val registry = ScreenObservationRegistry.get(chain.app)
            val previous = registry.access.value.consent
            registry.setConsent(false)
            try {
                for ((acknowledged, revoked) in listOf(false to false, true to false, true to true)) {
                    val run = AgentRun(runId = "run.${UUID.randomUUID()}", snapshotId = chain.snapshot.id,
                        conversationId = "conversation.${UUID.randomUUID()}", state = RunState.TOOL_EXECUTING,
                        budget = RunBudget(stallTimeoutMs = 200), startedAtMs = System.currentTimeMillis())
                    var dispatches = 0
                    val port = object : ScreenActionPort, ScreenObservationPort {
                        override val access = MutableStateFlow(ScreenAccess(true, true, 1, true))
                        override val changes = MutableStateFlow(1L)
                        override suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean,
                            onDispatch: () -> Unit): Boolean {
                            check(authorized()); onDispatch(); dispatches++
                            if (!acknowledged) awaitCancellation()
                            return true
                        }
                        override suspend fun capture(expectedPackage: String?): ScreenSnapshot {
                            if (revoked) access.value = ScreenAccess(false, true, 2)
                            awaitCancellation()
                        }
                    }
                    val action = ScreenActionToolExecutor(port, { ScreenActionAuthority(Authority.SHIZUKU, 1) }, { true })
                    val tools = RunTools(chain.container, chain.app, chain.snapshot, run, false, false, runExecutor = action)
                    try {
                        val call = ToolCall("stall-${UUID.randomUUID()}", "device_act",
                            """{"observation_id":"${UUID.randomUUID()}","action":"click","node_index":0,"observe_after":{}}""")
                        assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(call))
                        val result = tools.executor.approve(call.callId)
                        if (acknowledged) {
                            assertTrue("Confirmed action lost after its read stalled: $result", result is ToolResult.Value)
                            val json = (result as ToolResult.Value).json
                            assertTrue(json.contains("\"status\":\"performed\""))
                            assertTrue(json.contains("\"status\":\"timeout\""))
                            assertFalse(json.contains("nodes"))
                        } else assertTrue(result is ToolResult.UnknownOutcome)
                        val replay = tools.executor.invoke(call)
                        if (acknowledged && !revoked) assertEquals(result, replay)
                        else assertTrue(replay is ToolResult.Denied)
                        assertEquals(1, dispatches)
                    } finally { tools.executor.close() }
                }
            } finally { registry.setConsent(previous) }
        }
    }

    @Test fun cancellationPreservesOnlyARealOsAcknowledgementAndPublishesItsReceiptAtomically() = runBlocking {
        withTimeout(30_000) {
            val chain = prepareChain("confirmed-device-cancel")
            val registry = ScreenObservationRegistry.get(chain.app)
            val previous = registry.access.value.consent
            registry.setConsent(false)
            try {
                for (acknowledged in listOf(false, true)) {
                    val conversation = chain.container.conversations.create(chain.snapshot.id, "Synthetic cancellation")
                    val run = AgentRun(runId = "run.${UUID.randomUUID()}", snapshotId = chain.snapshot.id, conversationId = conversation.id,
                        state = RunState.TOOL_EXECUTING, startedAtMs = System.currentTimeMillis())
                    chain.container.runs.save(RunRecord(run.runId, run.snapshotId, run.conversationId,
                        state = RunStatus.TOOL_EXECUTING, createdAt = Utc.nowIso()))
                    val entered = CompletableDeferred<Unit>()
                    var dispatches = 0
                    val port = object : ScreenActionPort, ScreenObservationPort {
                        override val access = MutableStateFlow(ScreenAccess(true, true, 1, true))
                        override val changes = MutableStateFlow(1L)
                        override suspend fun perform(request: ScreenActionRequest, authorized: suspend () -> Boolean,
                            onDispatch: () -> Unit): Boolean {
                            check(authorized()); onDispatch(); dispatches++
                            if (!acknowledged) { entered.complete(Unit); awaitCancellation() }
                            return true
                        }
                        override suspend fun capture(expectedPackage: String?): ScreenSnapshot {
                            entered.complete(Unit); awaitCancellation()
                        }
                    }
                    val action = ScreenActionToolExecutor(port, { ScreenActionAuthority(Authority.SHIZUKU, 1) }, { true })
                    val tools = RunTools(chain.container, chain.app, chain.snapshot, run, false, false, runExecutor = action)
                    try {
                        val call = ToolCall("cancel-${UUID.randomUUID()}", "device_act",
                            """{"observation_id":"${UUID.randomUUID()}","action":"click","node_index":0,"observe_after":{}}""")
                        assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(call))
                        assertNull(tools.confirmedDeviceActionReceipt(call.callId))
                        val invocationId = requireNotNull(tools.runtimeInvocationId(call.callId))
                        chain.container.runs.recordInvocation(ToolInvocation(invocationId, run.runId, call.callId, call.name,
                            call.argumentsJson, permissionDecision = "APPROVED", createdAt = Utc.nowIso()))
                        val execution = async { tools.executor.approve(call.callId) }
                        withTimeout(5000) { entered.await() }
                        execution.cancelAndJoin()
                        val receipt = tools.confirmedDeviceActionReceipt(call.callId)
                        assertEquals(acknowledged, receipt != null)
                        if (receipt != null) {
                            assertNull(ConfirmedDeviceActionCancellation.persist(chain.container.db, run.runId, "wrong-conversation", receipt))
                            val fault = object : SqlConnection by chain.container.db {
                                override fun execute(sql: String, args: List<Any?>) {
                                    if (sql.startsWith("INSERT INTO messages")) error("synthetic receipt persistence failure")
                                    chain.container.db.execute(sql, args)
                                }
                            }
                            assertThrows(IllegalStateException::class.java) {
                                ConfirmedDeviceActionCancellation.persist(fault, run.runId, conversation.id, receipt)
                            }
                            assertEquals("PENDING", chain.container.runs.invocations(run.runId).single().state)
                            assertTrue(chain.container.conversations.messages(conversation.id).isEmpty())
                            val settled = ConfirmedDeviceActionCancellation.persist(chain.container.db, run.runId, conversation.id, receipt)
                            assertEquals("SUCCEEDED", settled?.state)
                            assertTrue(settled?.resultJson.orEmpty().contains("\"action_confirmed\":true"))
                            assertFalse(settled?.resultJson.orEmpty().contains("nodes"))
                            assertNull(ConfirmedDeviceActionCancellation.persist(chain.container.db, run.runId, conversation.id, receipt))
                            val parts = chain.container.conversations.messages(conversation.id).flatMap { it.parts }.filterIsInstance<ToolResultPart>()
                            assertEquals(1, parts.size)
                            assertEquals(receipt.resultJson, parts.single().resultJson)
                        } else assertEquals("PENDING", chain.container.runs.invocations(run.runId).single().state)
                        assertEquals(1, dispatches)
                        run.state = RunState.CANCELLED
                        assertTrue(tools.executor.invoke(call) is ToolResult.Denied)
                        assertEquals(1, dispatches)
                    } finally { tools.executor.close() }
                }
            } finally { registry.setConsent(previous) }
        }
    }
    @Test fun disabledObservationLeavesExistingShellRouteAvailable() = runBlocking {
        val chain = prepareChain("optional-observation")
        val registry = runtime.mobileagent.observation.ScreenObservationRegistry.get(chain.app)
        val previous = registry.access.value.consent
        registry.setConsent(false)
        var dispatches = 0
        val shell = object : ToolExecutor {
            override val specs = listOf(ToolSpec("shell_exec", "fixture", "{}", "shell.execute", true))
            override suspend fun invoke(call: ToolCall): ToolResult {
                dispatches++
                return ToolResult.Value("{\"stdout\":\"adb-route-fixture\"}")
            }
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")
        }
        val tools = RunTools(chain.container, chain.app, chain.snapshot, newRun(chain), false, false,
            runExecutor = ToolExecutorFactory(web = shell).createLegacyExecutor())
        try {
            assertTrue(tools.executor.specs.any { it.name == "shell_exec" })
            assertFalse(tools.executor.specs.any { it.name in setOf("device_observe", "device_wait_for") })
            assertTrue(tools.executor.invoke(ToolCall("shell", "shell_exec", "{}")) is ToolResult.Value)
            assertEquals(1, dispatches)
        } finally { tools.executor.close(); registry.setConsent(previous) }
    }
    @Test fun externalDisclosureMapsTheExactRuntimeCallAndCannotResurrectRevokedData() = runBlocking {
        val chain = prepareChain("external-replay")
        val run = newRun(chain)
        val child = ApprovalEchoExecutor()
        val composite = ToolExecutorFactory(web = child).createLegacyExecutor()
        val tools = RunTools(chain.container, chain.app, chain.snapshot, run, false, false, runExecutor = composite)
        val call = ToolCall("model-output", "approval_echo", "{}")
        assertFalse(tools.executor.authorizeReplay(call))
        assertEquals(ToolResult.NeedsApproval, tools.executor.invoke(call))
        assertFalse(tools.executor.authorizeDeferredResult(call))
        assertTrue(tools.executor.approve(call.callId) is ToolResult.Value)
        assertTrue(tools.executor.authorizeReplay(call))
        assertTrue(tools.executor.authorizeDeferredResult(call))
        assertFalse(tools.executor.authorizeReplay(call.copy(argumentsJson = "{\"changed\":true}")))
        child.revoked = true
        assertFalse(tools.executor.authorizeDeferredResult(call))
        child.revoked = false
        assertFalse(tools.executor.authorizeReplay(call))
        assertFalse(tools.executor.invoke(call).toString().contains("approval-settled-payload"))
        assertEquals(1, child.dispatches)
        tools.executor.close()
        assertFalse(tools.executor.authorizeDeferredResult(call))
    }
    @Test fun searchReplayPreservesCompletedAndUnknownFactsWithoutRedispatch() = runBlocking {
        for (name in listOf("web_search", "web_fetch")) for (unknown in listOf(false, true)) {
            val chain = prepareChain("search-facts")
            val run = newRun(chain)
            var allowed = true
            var dispatches = 0
            val fake = object : ToolExecutor {
                override val specs = listOf(if (name == "web_fetch") runtime.mobileagent.skills.WebSearchTools.webFetch else runtime.mobileagent.skills.WebSearchTools.webSearch)
                override suspend fun invoke(call: ToolCall): ToolResult {
                    dispatches++
                    return if (unknown) ToolResult.UnknownOutcome("dispatched; unknown")
                    else ToolResult.Value("""{"results":[{"title":"SEARCH_PRIVATE_BODY"}]}""")
                }
                override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("not pending")
                override suspend fun authorizeReplay(call: ToolCall): Boolean = allowed
            }
            val tools = RunTools(chain.container, chain.app, chain.snapshot, run, false, false, runExecutor = fake)
            val call = ToolCall("search-replay", name, if (name == "web_fetch") """{"url":"https://example.com/"}""" else """{"query":"public"}""")
            val first = tools.executor.invoke(call)
            allowed = false
            val withheld = tools.executor.invoke(call)
            if (unknown) assertEquals(first, withheld)
            else {
                assertTrue(withheld is ToolResult.Denied)
                assertEquals(ToolResult.Completion.COMPLETED_WITHHELD, (withheld as ToolResult.Denied).completion)
            }
            assertFalse(withheld.toString().contains("SEARCH_PRIVATE_BODY"))
            allowed = true
            assertEquals(withheld, tools.executor.invoke(call))
            run.state = RunState.COMPLETED
            assertEquals(withheld, tools.executor.invoke(call))
            assertEquals(1, dispatches)
        }
    }

    private class RevocableEchoExecutor : ToolExecutor {
        override val specs = listOf(ToolSpec("echo", "echo", "{\"type\":\"object\"}", "", false))
        var dispatches = 0
        var replayChecks = 0

        @Volatile
        var revoked = false

        override suspend fun invoke(call: ToolCall): ToolResult {
            dispatches += 1
            return ToolResult.Value("""{"ok":true,"echo":"sensitive-cached-payload"}""")
        }

        override suspend fun approve(callId: String): ToolResult =
            ToolResult.Invalid("No pending tool approval")

        override suspend fun authorizeReplay(call: ToolCall): Boolean {
            replayChecks += 1
            return !revoked
        }
    }

    private class ApprovalEchoExecutor : ToolExecutor {
        override val specs = listOf(ToolSpec("approval_echo", "approval_echo", "{\"type\":\"object\"}", "", false))
        var dispatches = 0
        var replayChecks = 0

        @Volatile
        var revoked = false
        private val pending = linkedSetOf<String>()
        private val completed = linkedSetOf<String>()

        override suspend fun invoke(call: ToolCall): ToolResult {
            if (call.callId in completed) return ToolResult.Value("""{"ok":true,"echo":"approval-settled-payload"}""")
            dispatches += 1
            pending += call.callId
            return ToolResult.NeedsApproval
        }

        override suspend fun approve(callId: String): ToolResult {
            if (!pending.remove(callId)) return ToolResult.Invalid("No pending tool approval")
            completed += callId
            return ToolResult.Value("""{"ok":true,"echo":"approval-settled-payload"}""")
        }

        override suspend fun authorizeReplay(call: ToolCall): Boolean {
            replayChecks += 1
            return !revoked && call.callId in completed
        }
    }

    private data class ReplayChain(
        val app: MobileAgentApp,
        val container: AppContainer,
        val snapshot: AgentSnapshot,
        val suffix: String,
    )

    private fun prepareChain(prefix: String): ReplayChain {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = "$prefix-${UUID.randomUUID().toString().replace("-", "")}"
        container.profiles.createProvider(
            ProviderProfile(
                id = "provider.$suffix",
                name = "Replay fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = "model.$suffix",
                providerId = "provider.$suffix",
                role = ModelRole.CHAT,
                modelId = "fixture-replay",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        val agentId = "agent.$suffix"
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Replay fixture",
                promptRevisionId = "pending",
                chatProfileId = "model.$suffix",
                revision = 0,
            ),
            "Replay disclosure fixture.",
        )
        return ReplayChain(app, container, container.agents.createSnapshot(agentId), suffix)
    }

    private fun newRun(chain: ReplayChain): AgentRun = AgentRun(
        runId = "run.${chain.suffix}",
        snapshotId = chain.snapshot.id,
        conversationId = "conversation.${chain.suffix}",
        state = RunState.MODEL_STREAMING,
        startedAtMs = System.currentTimeMillis(),
    )

    /** ADR-0026: the deadline admits a tool; it never cuts one that is already running. */
    @Test
    fun toolAdmittedBeforeTheDeadlineKeepsItsResultAndLaterCallsAreDenied() = runBlocking {
        val chain = prepareChain("deadline")
        val run = AgentRun(
            runId = "run.${chain.suffix}",
            snapshotId = chain.snapshot.id,
            conversationId = "conversation.${chain.suffix}",
            state = RunState.MODEL_STREAMING,
            budget = RunBudget(maxRuntimeMs = 300),
            startedAtMs = System.currentTimeMillis(),
        )
        var dispatches = 0
        val slow = object : ToolExecutor {
            override val specs = listOf(ToolSpec("slow_echo", "slow echo", "{\"type\":\"object\"}", "", false))
            override suspend fun invoke(call: ToolCall): ToolResult {
                dispatches++
                kotlinx.coroutines.delay(800)
                return ToolResult.Value("""{"ok":true}""")
            }
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("not pending")
        }
        val tools = RunTools(chain.container, chain.app, chain.snapshot, run, false, false, runExecutor = slow)

        val first = tools.executor.invoke(ToolCall("slow-1", "slow_echo", "{}"))
        assertEquals(ToolResult.Value("""{"ok":true}"""), first)
        val late = tools.executor.invoke(ToolCall("slow-2", "slow_echo", "{}"))
        assertTrue(late is ToolResult.Denied)
        assertEquals(1, dispatches)
    }

    @Test
    fun revokedGrantDeniesReplayWithoutRedispatchOrDisclosure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val container = app.container
        val suffix = UUID.randomUUID().toString().replace("-", "")
        container.profiles.createProvider(
            ProviderProfile(
                id = "provider.replay.$suffix",
                name = "Replay fixture",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "fixture-replay-$suffix",
                revision = 1,
            ),
        )
        container.profiles.createModel(
            ModelProfile(
                id = "model.replay.$suffix",
                providerId = "provider.replay.$suffix",
                role = ModelRole.CHAT,
                modelId = "fixture-replay",
                capabilities = setOf("stream", "tools"),
                contextLimit = 4096,
                outputLimit = 512,
                revision = 1,
            ),
        )
        val agentId = "agent.replay.$suffix"
        container.agents.saveWithPrompt(
            AgentProfile(
                id = agentId,
                name = "Replay fixture",
                promptRevisionId = "pending",
                chatProfileId = "model.replay.$suffix",
                revision = 0,
            ),
            "Replay disclosure fixture.",
        )
        val snapshot = container.agents.createSnapshot(agentId)
        val run = AgentRun(
            runId = "run.replay.$suffix",
            snapshotId = snapshot.id,
            conversationId = "conversation.replay.$suffix",
            state = RunState.MODEL_STREAMING,
            startedAtMs = System.currentTimeMillis(),
        )
        val fake = RevocableEchoExecutor()
        val runTools = RunTools(container, app, snapshot, run, false, false, runExecutor = fake)
        val call = ToolCall("model-call-replay", "echo", "{}")

        val first = runTools.executor.invoke(call)
        assertTrue(first is ToolResult.Value)
        assertTrue((first as ToolResult.Value).json.contains("sensitive-cached-payload"))
        assertEquals(1, fake.dispatches)

        // Authorized replay discloses the cache without re-dispatching.
        val replay = runTools.executor.invoke(call)
        assertTrue(replay is ToolResult.Value)
        assertEquals(1, fake.dispatches)

        // Revocation denies the replay: no dispatch, no disclosure, no re-execution.
        fake.revoked = true
        val denied = runTools.executor.invoke(call)
        assertTrue(denied is ToolResult.Denied)
        assertFalse((denied as ToolResult.Denied).reason.contains("sensitive-cached-payload"))
        assertEquals(1, fake.dispatches)

        // A closed run never discloses, even with a cooperating owner.
        fake.revoked = false
        run.state = RunState.COMPLETED
        val closed = runTools.executor.invoke(call)
        assertTrue(closed is ToolResult.Denied)
        assertEquals(1, fake.dispatches)
    }

    @Test
    fun factoryCompositeChainForwardsRevocationWithoutRedispatch() = runBlocking {
        // Production composition: fake child → ToolExecutorFactory →
        // CompositeToolExecutor → RunTools.  The composite must forward the
        // disclosure check to the child that actually ran the call (b07 A).
        val chain = prepareChain("chain")
        val fake = RevocableEchoExecutor()
        val composite = ToolExecutorFactory(web = fake).createLegacyExecutor()
        val runTools = RunTools(chain.container, chain.app, chain.snapshot, newRun(chain), false, false,
            runExecutor = composite)
        val call = ToolCall("model-call-chain", "echo", "{}")

        val first = runTools.executor.invoke(call)
        assertTrue(first is ToolResult.Value)
        assertTrue((first as ToolResult.Value).json.contains("sensitive-cached-payload"))
        assertEquals(1, fake.dispatches)

        val replay = runTools.executor.invoke(call)
        assertTrue(replay is ToolResult.Value)
        assertEquals(1, fake.dispatches)
        assertTrue("the child behind the composite must observe the disclosure check", fake.replayChecks >= 1)

        fake.revoked = true
        val denied = runTools.executor.invoke(call)
        assertTrue(denied is ToolResult.Denied)
        assertFalse((denied as ToolResult.Denied).reason.contains("sensitive-cached-payload"))
        assertEquals(1, fake.dispatches)
    }

    @Test
    fun unconfiguredMcpDeniesReplayByDefault() = runBlocking {
        // Remote MCP executors without a completed-call record deny disclosure
        // fail-closed instead of inheriting an allow default (b07 A).
        val chain = prepareChain("mcpdefault")
        val mcp = mcpTools(chain.container, chain.snapshot)
        assertTrue("fixture has no MCP grant configured", mcp.specs.isEmpty())
        assertFalse(mcp.authorizeReplay(ToolCall("mcp-1", "anything", "{}")))
        val composite = ToolExecutorFactory(mcp = mcp).createLegacyExecutor()
        assertFalse(composite.authorizeReplay(ToolCall("mcp-1", "anything", "{}")))
    }

    @Test
    fun sameCallIdWithDifferentArgsDoesNotServeStaleCache() = runBlocking {
        val chain = prepareChain("reuse")
        val fake = RevocableEchoExecutor()
        val runTools = RunTools(chain.container, chain.app, chain.snapshot, newRun(chain), false, false,
            runExecutor = fake)
        val call = ToolCall("model-call-reuse", "echo", "{}")
        assertTrue(runTools.executor.invoke(call) is ToolResult.Value)
        // Different arguments are a different request: a new dispatch with
        // dispatch-time checks, never the old cached payload.
        assertTrue(runTools.executor.invoke(call.copy(argumentsJson = "{\"other\":true}")) is ToolResult.Value)
        assertEquals(2, fake.dispatches)
    }

    @Test
    fun approvalSettledReplayDisclosesUntilRevoked() = runBlocking {
        val chain = prepareChain("approval")
        val fake = ApprovalEchoExecutor()
        val composite = ToolExecutorFactory(web = fake).createLegacyExecutor()
        val runTools = RunTools(chain.container, chain.app, chain.snapshot, newRun(chain), false, false,
            runExecutor = composite)
        val call = ToolCall("model-call-approval", "approval_echo", "{}")

        assertTrue(runTools.executor.invoke(call) is ToolResult.NeedsApproval)
        val settled = runTools.executor.approve("model-call-approval")
        assertTrue(settled is ToolResult.Value)
        assertTrue((settled as ToolResult.Value).json.contains("approval-settled-payload"))

        val replay = runTools.executor.invoke(call)
        assertTrue(replay is ToolResult.Value)
        assertEquals(1, fake.dispatches)
        assertTrue(fake.replayChecks >= 1)

        fake.revoked = true
        val denied = runTools.executor.invoke(call)
        assertTrue(denied is ToolResult.Denied)
        assertFalse((denied as ToolResult.Denied).reason.contains("approval-settled-payload"))
        assertEquals(1, fake.dispatches)
    }
}
