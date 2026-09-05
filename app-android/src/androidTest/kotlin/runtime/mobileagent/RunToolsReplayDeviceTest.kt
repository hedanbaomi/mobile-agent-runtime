// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.tooling.ToolExecutorFactory

/**
 * Cached tool results must be re-validated for disclosure before replay.
 *
 * call success → revoke grant → duplicate same call must produce:
 * no external dispatch, no cached-data disclosure (DENIED), and no
 * re-execution of the original tool.  A closed run must deny replay too.
 */
@RunWith(AndroidJUnit4::class)
class RunToolsReplayDeviceTest {
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
