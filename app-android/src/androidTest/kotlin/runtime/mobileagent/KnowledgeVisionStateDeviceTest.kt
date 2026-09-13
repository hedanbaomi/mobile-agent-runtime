// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

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
import runtime.mobileagent.feature.knowledge.KnowledgeUiState
import runtime.mobileagent.knowledge.VisionBinding

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

    private fun fingerprint(provider: ProviderProfile, model: ModelProfile): String = VisionBinding(
        provider.id, model.modelId, provider.baseUrl,
        maxOf(provider.revision, model.revision), provider.revision, model.revision,
    ).fingerprint

    private fun awaitState(vm: KnowledgeViewModel, ready: (KnowledgeUiState) -> Boolean): KnowledgeUiState {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + 10_000_000_000L
        var snapshot = KnowledgeUiState()
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { snapshot = vm.state.value }
            if (ready(snapshot)) return snapshot
            Thread.sleep(25)
        }
        error("KnowledgeViewModel did not publish the expected Vision target; configured=${snapshot.visionConfigured}, error=${snapshot.error}")
    }
}
