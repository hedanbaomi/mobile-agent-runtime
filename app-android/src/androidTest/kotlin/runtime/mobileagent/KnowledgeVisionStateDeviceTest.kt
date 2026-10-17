// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.feature.knowledge.KnowledgeBatchItemDetails
import runtime.mobileagent.feature.knowledge.KnowledgeUiState
import runtime.mobileagent.knowledge.ImportBatchKind

/** Real repository -> asynchronous ViewModel snapshot -> Compose state, without a model request. */
@RunWith(AndroidJUnit4::class)
class KnowledgeVisionStateDeviceTest {
    @Test
    fun reloadPublishesImageCapableChatTargetAndRefreshesItsRevisions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val profiles = app.container.profiles
        val suffix = UUID.randomUUID().toString().replace("-", "")
        // visionBinding sorts by provider name. The fixture is selected without changing,
        // disabling, or deleting any existing profile; the assertion guards that precondition.
        val provider = ProviderProfile(
            id = "provider.knowledge-vision-state.$suffix",
            name = "! Knowledge vision state fixture $suffix",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1",
            secretRef = "unresolved-knowledge-vision-state-$suffix",
            revision = 1,
        )
        val model = ModelProfile(
            id = "model.knowledge-vision-state.$suffix",
            providerId = provider.id,
            role = ModelRole.CHAT,
            modelId = "fixture-image-chat",
            capabilities = setOf("image", "stream"),
            contextLimit = 4096,
            outputLimit = 512,
            revision = 1,
        )
        var vm: KnowledgeViewModel? = null
        try {
            profiles.createProvider(provider)
            profiles.createModel(model)
            assertEquals("Fixture must be the selected image-capable CHAT model", model.id, profiles.visionBinding()?.second?.id)
            instrumentation.runOnMainSync { vm = KnowledgeViewModel(app) }
            val viewModel = requireNotNull(vm)
            val initialFingerprint = fingerprint(provider, model)
            val initial = awaitState(viewModel) { it.visionTargetFingerprint == initialFingerprint }
            assertTrue(initial.visionConfigured)
            assertTrue(initial.visionTargetLabel.contains(provider.name))
            assertTrue(initial.visionTargetLabel.contains(model.modelId))
            assertTrue(initial.visionTargetLabel.contains("provider rev 1 / model rev 1"))

            val changedProvider = profiles.updateProvider(provider.copy(revision = 2))
            val changedModel = profiles.updateModel(model.copy(revision = 3))
            val changedFingerprint = fingerprint(changedProvider, changedModel)
            assertNotEquals(initialFingerprint, changedFingerprint)
            instrumentation.runOnMainSync { viewModel.reload() }
            val refreshed = awaitState(viewModel) { it.visionTargetFingerprint == changedFingerprint }
            assertTrue(refreshed.visionConfigured)
            assertTrue(refreshed.visionTargetLabel.contains("provider rev 2 / model rev 3"))
            assertNotEquals(initial.visionTargetLabel, refreshed.visionTargetLabel)
        } finally {
            // Stop only this test's ViewModel before deleting only this test's profiles.
            vm?.let { viewModel -> runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
            if (profiles.getModel(model.id) != null) assertTrue(profiles.deleteModel(model.id))
            if (profiles.getProvider(provider.id) != null) assertTrue(profiles.deleteProvider(provider.id))
        }
    }

    /**
     * "Configure and continue": the staged file selection and the confirmed destination live in
     * the shell-scoped ViewModel.  A Provider-settings round trip - including a changed default
     * destination - must not drop the staged files nor silently switch the chosen destination.
     */
    @Test
    fun stagedImportSurvivesProviderRoundTripAndNeverSwitchesWithTheDefault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val profiles = app.container.profiles
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val providerA = ProviderProfile(
            id = "provider.knowledge-vision-roundtrip.$suffix",
            name = "! dsh vision round trip a $suffix",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1",
            secretRef = "unresolved-knowledge-vision-roundtrip-$suffix",
            revision = 1,
        )
        val modelA = ModelProfile(
            id = "model.knowledge-vision-roundtrip-a.$suffix",
            providerId = providerA.id,
            role = ModelRole.CHAT,
            modelId = "round-trip-a",
            capabilities = setOf("image", "stream"),
            contextLimit = 4096,
            outputLimit = 512,
            revision = 1,
        )
        val providerB = providerA.copy(
            id = "provider.knowledge-vision-roundtrip-b.$suffix",
            name = "! dsh vision round trip b $suffix",
        )
        val modelB = modelA.copy(
            id = "model.knowledge-vision-roundtrip-b.$suffix",
            providerId = providerB.id,
            modelId = "round-trip-b",
        )
        // Added while the user is "in Provider settings".  It sorts before both fixtures, so the
        // default destination really moves underneath the already staged, explicit choice.
        val providerC = providerA.copy(
            id = "provider.knowledge-vision-roundtrip-c.$suffix",
            name = "! 0 dsh vision round trip c $suffix",
        )
        val modelC = modelA.copy(
            id = "model.knowledge-vision-roundtrip-c.$suffix",
            providerId = providerC.id,
            modelId = "round-trip-c",
        )
        var vm: KnowledgeViewModel? = null
        try {
            profiles.createProvider(providerA)
            profiles.createModel(modelA)
            profiles.createProvider(providerB)
            profiles.createModel(modelB)
            instrumentation.runOnMainSync { vm = KnowledgeViewModel(app) }
            val viewModel = requireNotNull(vm)
            val uri = Uri.parse("content://review/round-trip-$suffix.png")
            val fixtureFingerprints = listOf(
                visionProfileBinding(providerA, modelA).fingerprint,
                visionProfileBinding(providerB, modelB).fingerprint,
            )
            val staged = awaitState(viewModel) { state ->
                !state.visionTargetsLoading &&
                    state.visionTargets.map { target -> target.fingerprint }.containsAll(fixtureFingerprints)
            }
            // The user explicitly picks a destination that is not the current default.
            val chosen = fixtureFingerprints.first { it != staged.visionTargetFingerprint }
            instrumentation.runOnMainSync { viewModel.stageImport(listOf(uri), "files") }
            awaitState(viewModel) { it.pendingImport?.uris == listOf(uri) && !it.visionTargetsLoading }
            instrumentation.runOnMainSync { viewModel.selectPendingVisionTarget(chosen) }
            val chosenState = awaitState(viewModel) { it.pendingImport?.selectedVisionTargetFingerprint == chosen }
            assertTrue(chosenState.pendingImport?.visionTargetSelectionInitialized == true)

            // Exercise the production Android port too: a default-only/legacy comparison here
            // used to reject this exact selection before WorkManager could start any member.
            val repository = app.container.knowledge
            val base = repository.createKnowledgeBase("vision-port-fixture-$suffix")
            val batch = repository.beginBatch(base,
                runtime.mobileagent.knowledge.ImportBatchKind.FILES, "vision-port-fixture")
            val job = repository.importBytes("fixture.txt", "text/plain", "local fixture".toByteArray(),
                visionConfigured = false, knowledgeBaseId = base,
                pauseAt = runtime.mobileagent.knowledge.ImportStage.COPYING)
            repository.bindJobToBatch(batch, job, "fixture.txt")
            repository.completeBatchStaging(batch)
            val ports = AndroidKnowledgeImportPorts(app)
            assertTrue(ports.authorizeBatchVision(batch, chosen))
            assertEquals(chosen, repository.batchVisionAuthorization(batch))
            assertTrue(!ports.authorizeBatchVision(batch, chosen + "-stale"))
            assertEquals(chosen, repository.batchVisionAuthorization(batch))

            // Provider round trip: configure one more model, then return to the Knowledge route.
            profiles.createProvider(providerC)
            profiles.createModel(modelC)
            instrumentation.runOnMainSync { viewModel.refreshVisionTargets() }
            val newFingerprint = visionProfileBinding(providerC, modelC).fingerprint
            val returned = awaitState(viewModel) { state ->
                !state.visionTargetsLoading && state.visionTargets.any { target -> target.fingerprint == newFingerprint }
            }
            assertEquals(listOf(uri), returned.pendingImport?.uris)
            assertEquals(chosen, returned.pendingImport?.selectedVisionTargetFingerprint)
            assertTrue(returned.pendingImport?.visionTargetSelectionInitialized == true)
            assertEquals(returned.visionTargets.firstOrNull()?.fingerprint, returned.visionTargetFingerprint)
            // The default destination moved, but the explicit choice was not silently switched to it.
            assertNotEquals(chosen, returned.visionTargetFingerprint)
        } finally {
            // Stop only this test's ViewModel before deleting only this test's profiles.
            vm?.let { viewModel -> runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
            listOf(modelC to providerC, modelB to providerB, modelA to providerA).forEach { (model, provider) ->
                if (profiles.getModel(model.id) != null) assertTrue(profiles.deleteModel(model.id))
                if (profiles.getProvider(provider.id) != null) assertTrue(profiles.deleteProvider(provider.id))
            }
        }
    }

    /**
     * A collapsed batch card must state its real counters without member rows, and only the batch
     * the user opens may be read back by its own id.  Opening, refreshing, closing and switching the
     * knowledge base must all keep that split truthful.
     */
    @Test
    fun collapsedBatchKeepsRealCountsAndOnlyTheOpenedBatchReadsItsRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val repo = app.container.knowledge
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val base = repo.createKnowledgeBase("knowledge batch summary $suffix")
        val otherBase = repo.createKnowledgeBase("knowledge batch summary other $suffix")
        val job = repo.importBytes("member.txt", "text/plain", "member source".toByteArray(), false, base)
        val batch = repo.beginBatch(base, ImportBatchKind.FILES, "summary batch $suffix")
        app.container.db.execute("UPDATE import_batches SET total_items=400,staging_complete=1 WHERE id=?", listOf(batch))
        app.container.db.execute("UPDATE import_jobs SET stage='FAILED' WHERE id=?", listOf(job.id))
        val states = listOf("PUBLISHED", "PENDING", "COPYING", "QUEUED", "PROCESSING", "WAITING", "FAILED", "CANCELLED")
        app.container.db.transaction {
            repeat(400) { index ->
                app.container.db.execute(
                    "INSERT INTO import_items(id,batch_id,item_key,relative_path,job_id,kind,state,error) VALUES(?,?,?,?,?,'FILE',?,?)",
                    listOf("$suffix-member-$index", batch, "item-$index", "synthetic/$index", job.id,
                        states[index % states.size], if (index == 3) "UNKNOWN_OUTCOME: receipt pending" else null),
                )
            }
        }
        var vm: KnowledgeViewModel? = null
        try {
            instrumentation.runOnMainSync { vm = KnowledgeViewModel(app) }
            val viewModel = requireNotNull(vm)
            instrumentation.runOnMainSync { viewModel.selectBase(base) }
            val collapsed = awaitState(viewModel) {
                it.selectedBaseId == base && it.batches.any { card -> card.id == batch }
            }.batches.first { it.id == batch }
            // Counters and the unknown reason are real while no member row has been read at all.
            assertTrue(collapsed.items is KnowledgeBatchItemDetails.Collapsed)
            assertEquals(400, collapsed.items.size)
            assertEquals("UNKNOWN_OUTCOME: receipt pending", collapsed.unknownOutcomeReason)
            assertTrue(collapsed.retryWaiting)

            instrumentation.runOnMainSync { viewModel.setBatchExpanded(batch, true) }
            val opened = awaitState(viewModel) {
                it.batches.firstOrNull { card -> card.id == batch }?.items is KnowledgeBatchItemDetails.Loaded
            }.batches.first { it.id == batch }
            val openedRows = (opened.items as? KnowledgeBatchItemDetails.Loaded)?.rows.orEmpty()
            assertEquals(400, openedRows.size)
            assertEquals(400, opened.items.size)
            assertEquals("QUEUED", openedRows.first { it.state == "QUEUED" }.state)

            // A refresh keeps the opened batch's rows.
            instrumentation.runOnMainSync { viewModel.reload() }
            val refreshed = awaitState(viewModel) {
                it.batches.firstOrNull { card -> card.id == batch }?.items is KnowledgeBatchItemDetails.Loaded
            }.batches.first { it.id == batch }
            assertEquals(400, refreshed.items.size)

            // Closing drops the rows but never the real count.
            instrumentation.runOnMainSync { viewModel.setBatchExpanded(batch, false) }
            val closed = awaitState(viewModel) {
                it.batches.firstOrNull { card -> card.id == batch }?.items is KnowledgeBatchItemDetails.Collapsed
            }.batches.first { it.id == batch }
            assertTrue(closed.items is KnowledgeBatchItemDetails.Collapsed)
            assertEquals(400, closed.items.size)

            // Another knowledge base never inherits this one's opened sections.
            instrumentation.runOnMainSync { viewModel.selectBase(otherBase) }
            awaitState(viewModel) { it.selectedBaseId == otherBase }
            instrumentation.runOnMainSync { viewModel.selectBase(base) }
            val switched = awaitState(viewModel) {
                it.selectedBaseId == base &&
                    it.batches.firstOrNull { card -> card.id == batch }?.items is KnowledgeBatchItemDetails.Collapsed
            }.batches.first { it.id == batch }
            assertEquals(400, switched.items.size)
        } finally {
            vm?.let { viewModel -> runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
            repo.deleteKnowledgeBase(base)
            repo.deleteKnowledgeBase(otherBase)
            app.container.db.execute("DELETE FROM import_items WHERE batch_id=?", listOf(batch))
            app.container.db.execute("DELETE FROM import_batches WHERE id=?", listOf(batch))
        }
    }

    private fun fingerprint(provider: ProviderProfile, model: ModelProfile): String =
        visionProfileBinding(provider, model).fingerprint

    private fun awaitState(vm: KnowledgeViewModel, ready: (KnowledgeUiState) -> Boolean): KnowledgeUiState {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + 10_000_000_000L
        var snapshot = KnowledgeUiState()
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { snapshot = vm.state.value }
            if (ready(snapshot)) return snapshot
            Thread.sleep(25)
        }
        error("KnowledgeViewModel did not publish the expected snapshot; configured=${snapshot.visionConfigured}, error=${snapshot.error}")
    }
}
