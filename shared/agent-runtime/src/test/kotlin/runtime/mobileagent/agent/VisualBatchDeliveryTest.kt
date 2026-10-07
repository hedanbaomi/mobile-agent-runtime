// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.provider.*

class VisualBatchDeliveryTest {
    private fun refs(count: Int) = (1..count).map { InlineImage("image/png", "", "asset-" + it, 5, "hash-" + it) }
    private fun request(count: Int) = ModelRequest("fixture", listOf(ChatMessage("user", "goal", images = refs(count))), operationId = "run")
    @Test fun sixtyFourAreLoadedInParallelOnlyWithinSerialGroupsAndNeverReplayed() = runBlocking {
        val delivery = VisualBatchDelivery(64, 8, true, "goal")
        var loading = 0; var peak = 0; var analyzed = 0; var loaded = 0
        val projected = delivery.prepare(request(64), { source ->
            loading++; peak = maxOf(peak, loading); loaded++
            delay(5); loading--; source.copy(base64 = "aW1hZ2U=")
        }) { group, _, images ->
            assertEquals(0, loading)
            assertEquals(8, images.size)
            assertTrue(group.tools.isEmpty())
            analyzed++; "evidence group " + analyzed
        }
        assertEquals(8, peak); assertEquals(8, analyzed); assertEquals(64, loaded)
        assertTrue(projected.messages.all { it.images.isEmpty() })
        assertTrue(projected.messages.single().text.contains("asset-64"))
        delivery.prepare(request(64), { error("must reuse receipt") }) { _, _, _ -> error("must not replay") }
    }
    @Test fun groupReceiptsAppearOnceAcrossSixtyFourSourceMessages() = runBlocking {
        val input = request(64).copy(messages = refs(64).map { ChatMessage("user", images = listOf(it)) })
        val result = VisualBatchDelivery(64, 8, true, "goal").prepare(input,
            { it.copy(base64 = "aW1hZ2U=") }) { _, _, _ -> "notes" }
        assertEquals(8, result.messages.count { it.text.contains("sourceIds") })
        assertTrue(result.messages.all { it.images.isEmpty() })
    }
    @Test fun sixtyFifthSourceFailsBeforeAnyOriginalReadOrDispatch() = runBlocking {
        var loaded = 0
        try {
            VisualBatchDelivery(64, 8, true, "goal").prepare(request(65), { loaded++; it }) { _, _, _ -> error("dispatch") }
            fail<Unit>("expected budget failure")
        } catch (_: VisualDeliveryBudgetExceeded) { }
        assertEquals(0, loaded)
    }
    @Test fun changedOrOversizedLoadedSourceIsRejectedBeforeAnalysis() = runBlocking {
        for (loaded in listOf(refs(1).single().copy(base64 = "aW1hZ2U=", sha256 = "changed"),
            refs(1).single().copy(base64 = "aW1hZ2U=", byteLength = 9))) {
            try {
                VisualBatchDelivery(64, 8, true, "goal").prepare(request(1), { loaded }) { _, _, _ -> error("dispatch") }
                fail<Unit>("changed source accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun incompleteSecondGroupStopsWithoutReplayAndRetainsFirstReceipt() = runBlocking {
        val adapter = RecordingAdapter(unknownAt = 2)
        val run = AgentRun("run", "s", "c", budget = RunBudget(maxModelRounds = 32))
        val events = AgentRuntime(adapter).run(AgentRuntimeRequest(run,
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = refs(17)),
            "fixture", charArrayOf(), false, batchAllImages = true,
            imageLoader = { it.copy(base64 = "aW1hZ2U=") })).toList()
        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(2, adapter.requests.size)
        assertEquals(1, events.filterIsInstance<RuntimeEvent.VisualBatchAnalyzed>().size)
        assertEquals(2, run.modelRounds)
    }
    @Test fun cancellationBeforeReceiptConfirmationKeepsUnknownOutcome() = runBlocking {
        val adapter = RecordingAdapter()
        val run = AgentRun("run", "s", "c")
        val arrived = CompletableDeferred<Unit>()
        val task = launch {
            AgentRuntime(adapter).run(AgentRuntimeRequest(run,
                EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = refs(1)),
                "fixture", charArrayOf(), false, batchAllImages = true,
                imageLoader = { it.copy(base64 = "aW1hZ2U=") })).collect {
                    if (it is RuntimeEvent.VisualBatchAnalyzed) { arrived.complete(Unit); awaitCancellation() }
                }
        }
        arrived.await(); task.cancelAndJoin()
        assertEquals(RunState.CANCELLED, run.state)
        assertTrue(run.stopReason.orEmpty().contains("UNKNOWN_OUTCOME"), run.stopReason)
        assertEquals(1, adapter.requests.size)
    }
    @Test fun revocationDuringDurablePreviewPreventsDispatch() = runBlocking {
        val adapter = RecordingAdapter()
        var allowed = true
        val run = AgentRun("run", "s", "c")
        AgentRuntime(adapter).run(AgentRuntimeRequest(run,
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = refs(1)),
            "fixture", charArrayOf(), false, batchAllImages = true,
            imageLoader = { it.copy(base64 = "aW1hZ2U=") },
            beforeModelRequest = { require(allowed) { "PERMISSION_DENIED" } })).collect {
                if (it is RuntimeEvent.VisualBatchStarted) allowed = false
            }
        assertEquals(RunState.FAILED, run.state)
        assertEquals(0, adapter.requests.size)
    }
    @Test fun sourceRevocationAfterPreviewBlocksImageDispatch() = runBlocking {
        val adapter = RecordingAdapter()
        var sourceExists = true
        val run = AgentRun("run", "s", "c")
        AgentRuntime(adapter).run(AgentRuntimeRequest(run,
            EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "goal", currentImages = refs(1)),
            "fixture", charArrayOf(), false, batchAllImages = true,
            imageLoader = { it.copy(base64 = "aW1hZ2U=") },
            beforeImageRequest = { images ->
                assertEquals("asset-1", images.single().assetId)
                require(sourceExists) { "PERMISSION_DENIED: source revoked" }
            })).collect { if (it is RuntimeEvent.VisualBatchStarted) sourceExists = false }
        assertEquals(RunState.FAILED, run.state)
        assertEquals(0, adapter.requests.size)
    }
    private class RecordingAdapter(val unknownAt: Int = -1) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            requests += request
            emit(ModelEvent.TextDelta("notes"))
            if (requests.size != unknownAt) emit(ModelEvent.Completed)
        }
    }
}
