// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.feature.providers.ProbeOperation
import runtime.mobileagent.feature.providers.ProbePhase
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckResult
import runtime.mobileagent.provider.CapabilityCheckStatus
import runtime.mobileagent.provider.CapabilityProbeStatus
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ProviderConnectionErrorCode
import runtime.mobileagent.provider.ProviderConnectionResult

/**
 * Exercises [ProvidersViewModel.testConnection] through a scripted adapter.
 * The production HTTP path is not rewritten; the factory is the only test seam.
 */
@RunWith(AndroidJUnit4::class)
class ProvidersViewModelConnectionDeviceTest {
    @Test
    fun testConnectionMapsTypedAdapterResultsIntoProbeState() {
        val cases = listOf(
            ProviderConnectionResult.Success(latencyMs = 12, charged = true) to ProbePhase.SUCCESS,
            failure(ProviderConnectionErrorCode.AUTH_FAILED, 401) to ProbePhase.FAILURE,
            failure(ProviderConnectionErrorCode.MODEL_NOT_FOUND, 404) to ProbePhase.FAILURE,
            failure(ProviderConnectionErrorCode.RATE_LIMITED, 429, retryable = true) to ProbePhase.FAILURE,
            failure(ProviderConnectionErrorCode.TIMEOUT, retryable = true, charged = true) to ProbePhase.FAILURE,
            failure(ProviderConnectionErrorCode.NETWORK_UNREACHABLE, retryable = true) to ProbePhase.FAILURE,
            failure(ProviderConnectionErrorCode.INVALID_RESPONSE, charged = true) to ProbePhase.FAILURE,
        )
        cases.forEach { (result, phase) ->
            val harness = harness(onTest = { result })
            harness.vm.testConnection(harness.modelId, approved = true)
            waitUntil { harness.vm.probeState.value.phase == phase }
            val state = harness.vm.probeState.value
            assertEquals(ProbeOperation.CONNECTION, state.operation)
            assertEquals(phase, state.phase)
            assertNotNull(state.connection)
            if (result is ProviderConnectionResult.Success) {
                assertTrue(state.connection!!.success)
                assertEquals(12L, state.connection!!.latencyMs)
            } else {
                val failure = result as ProviderConnectionResult.Failure
                assertFalse(state.connection!!.success)
                assertEquals(failure.code, state.connection!!.error)
                assertEquals(failure.httpStatus, state.connection!!.httpStatus)
            }
        }
    }

    @Test
    fun capabilityPartialDoesNotRewriteConnectionSuccess() {
        val harness = harness(
            onTest = { ProviderConnectionResult.Success(latencyMs = 9, charged = true) },
            onProbe = {
                CapabilityReport(
                    modelId = it.modelId,
                    supportsStream = true,
                    supportsTools = false,
                    supportsImages = false,
                    source = "scripted-partial",
                    probedAt = "2026-09-02T00:00:00Z",
                    charged = true,
                    status = CapabilityProbeStatus.PARTIAL,
                    checks = listOf(
                        CapabilityCheckResult(CapabilityCheck.TOOLS, CapabilityCheckStatus.UNSUPPORTED, 404),
                    ),
                )
            },
        )
        harness.vm.testConnection(harness.modelId, approved = true)
        waitUntil { harness.vm.probeState.value.connection?.success == true }
        harness.vm.probe(harness.modelId, approved = true)
        waitUntil { harness.vm.probeState.value.operation == ProbeOperation.CAPABILITY && harness.vm.probeState.value.phase == ProbePhase.PARTIAL }
        assertTrue(harness.vm.probeState.value.connection!!.success)
        assertEquals(ProbePhase.PARTIAL, harness.vm.probeState.value.phase)
    }

    @Test
    fun retryFailureThenSuccessUpdatesTypedState() {
        var calls = 0
        val harness = harness(
            onTest = {
                calls += 1
                if (calls == 1) {
                    failure(ProviderConnectionErrorCode.AUTH_FAILED, 401)
                } else {
                    ProviderConnectionResult.Success(latencyMs = 4, charged = true)
                }
            },
        )
        harness.vm.testConnection(harness.modelId, approved = true)
        waitUntil { harness.vm.probeState.value.connection?.success == false }
        assertEquals(ProviderConnectionErrorCode.AUTH_FAILED, harness.vm.probeState.value.connection?.error)
        harness.vm.testConnection(harness.modelId, approved = true)
        waitUntil { harness.vm.probeState.value.connection?.success == true }
        assertEquals(ProbePhase.SUCCESS, harness.vm.probeState.value.phase)
        assertEquals(2, calls)
    }

    @Test
    fun switchingModelDropsStaleGenerationResult() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val slowRef = "secret-slow-$suffix"
        val fastRef = "secret-fast-$suffix"
        app.container.secrets.put(slowRef, "sk-test".toCharArray())
        app.container.secrets.put(fastRef, "sk-test".toCharArray())
        val slowProvider = ProviderProfile(
            id = "provider-slow-$suffix",
            name = "Slow",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1",
            secretRef = slowRef,
            revision = 1,
        )
        val fastProvider = slowProvider.copy(id = "provider-fast-$suffix", name = "Fast", secretRef = fastRef)
        val slowModel = ModelProfile(
            id = "model-slow-$suffix",
            providerId = slowProvider.id,
            role = ModelRole.CHAT,
            modelId = "slow-model",
            capabilities = setOf("stream"),
            contextLimit = 1024,
            outputLimit = 64,
            revision = 1,
        )
        val fastModel = slowModel.copy(
            id = "model-fast-$suffix",
            providerId = fastProvider.id,
            modelId = "fast-model",
        )
        app.container.profiles.createProvider(slowProvider)
        app.container.profiles.createProvider(fastProvider)
        app.container.profiles.createModel(slowModel)
        app.container.profiles.createModel(fastModel)
        val started = CountDownLatch(1)
        val vm = ProvidersViewModel(
            app,
            ProviderAdapterFactory { provider ->
                ScriptedAdapter(
                    onTest = { profile ->
                        if (profile.id == slowModel.id) {
                            started.countDown()
                            delay(400)
                            failure(ProviderConnectionErrorCode.AUTH_FAILED, 401)
                        } else {
                            ProviderConnectionResult.Success(latencyMs = 3, charged = true)
                        }
                    },
                )
            },
        )
        vm.testConnection(slowModel.id, approved = true)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        vm.testConnection(fastModel.id, approved = true)
        waitUntil { vm.probeState.value.connection?.success == true }
        assertEquals(fastModel.id, vm.probeState.value.modelId)
        assertEquals(ProbePhase.SUCCESS, vm.probeState.value.phase)
        assertTrue(vm.probeState.value.connection!!.success)
    }

    private fun harness(
        onTest: suspend (ModelProfile) -> ProviderConnectionResult,
        onProbe: suspend (ModelProfile) -> CapabilityReport = { error("probe unused") },
    ): Harness {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val secretRef = "secret-connection-$suffix"
        app.container.secrets.put(secretRef, "sk-test".toCharArray())
        val provider = ProviderProfile(
            id = "provider-connection-$suffix",
            name = "Scripted",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1",
            secretRef = secretRef,
            revision = 1,
        )
        val model = ModelProfile(
            id = "model-connection-$suffix",
            providerId = provider.id,
            role = ModelRole.CHAT,
            modelId = "scripted-model",
            capabilities = setOf("stream", "tools"),
            contextLimit = 1024,
            outputLimit = 64,
            revision = 1,
        )
        app.container.profiles.createProvider(provider)
        app.container.profiles.createModel(model)
        val vm = ProvidersViewModel(
            app,
            ProviderAdapterFactory { ScriptedAdapter(onTest, onProbe) },
        )
        return Harness(vm, model.id)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("timed out waiting for ViewModel state")
    }

    private fun failure(
        code: ProviderConnectionErrorCode,
        httpStatus: Int? = null,
        retryable: Boolean = false,
        charged: Boolean = false,
    ) = ProviderConnectionResult.Failure(
        code = code,
        httpStatus = httpStatus,
        retryable = retryable,
        charged = charged,
    )

    private data class Harness(val vm: ProvidersViewModel, val modelId: String)

    private class ScriptedAdapter(
        private val onTest: suspend (ModelProfile) -> ProviderConnectionResult,
        private val onProbe: suspend (ModelProfile) -> CapabilityReport = { error("probe unused") },
    ) : ModelAdapter {
        override suspend fun probe(profile: ModelProfile): CapabilityReport = onProbe(profile)

        override suspend fun testConnection(
            profile: ModelProfile,
            secret: CharArray,
            operationId: String,
        ): ProviderConnectionResult = onTest(profile)

        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = emptyFlow()

        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch =
            EmbeddingBatch(emptyList(), 0)
    }
}
