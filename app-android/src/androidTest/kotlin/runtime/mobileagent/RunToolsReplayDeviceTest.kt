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
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

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

        @Volatile
        var revoked = false

        override suspend fun invoke(call: ToolCall): ToolResult {
            dispatches += 1
            return ToolResult.Value("""{"ok":true,"echo":"sensitive-cached-payload"}""")
        }

        override suspend fun approve(callId: String): ToolResult =
            ToolResult.Invalid("No pending tool approval")

        override suspend fun authorizeReplay(call: ToolCall): Boolean = !revoked
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
}
