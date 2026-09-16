// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentProfile
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.OutputLimitMode

/**
 * Snapshot / import-export compatibility for the automatic output cap.
 *
 * A new payload carries the mode; a legacy payload without the field must
 * restore MANUAL and keep the number a user configured.
 */
class AutoOutputLimitTransferTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun profile(mode: OutputLimitMode, limit: Int) = ModelProfile(
        id = "model.transfer",
        providerId = "provider.transfer",
        role = ModelRole.CHAT,
        modelId = "transfer-chat",
        capabilities = setOf("stream"),
        contextLimit = 32_768,
        outputLimit = limit,
        revision = 1,
        outputLimitMode = mode,
    )

    @Test
    fun automaticModeSurvivesAnExportImportRoundTrip() {
        val encoded = json.encodeToString(ModelProfile.serializer(), profile(OutputLimitMode.AUTO, 0))
        val decoded = json.decodeFromString(ModelProfile.serializer(), encoded)
        assertEquals(OutputLimitMode.AUTO, decoded.outputLimitMode)
        assertEquals(null, decoded.effectiveOutputTokenLimit())
    }

    @Test
    fun manualModeSurvivesAnExportImportRoundTrip() {
        val encoded = json.encodeToString(ModelProfile.serializer(), profile(OutputLimitMode.MANUAL, 8192))
        val decoded = json.decodeFromString(ModelProfile.serializer(), encoded)
        assertEquals(OutputLimitMode.MANUAL, decoded.outputLimitMode)
        assertEquals(8192, decoded.effectiveOutputTokenLimit())
    }

    @Test
    fun legacyJsonWithoutTheFieldRestoresManualAndKeepsItsNumber() {
        val legacy = """{"id":"model.transfer","providerId":"provider.transfer","role":"CHAT","modelId":"transfer-chat","capabilities":["stream"],"parameterSchemaJson":"{}","contextLimit":32768,"outputLimit":8192,"revision":1,"parametersJson":"{}"}"""
        val decoded = json.decodeFromString(ModelProfile.serializer(), legacy)
        assertEquals(OutputLimitMode.MANUAL, decoded.outputLimitMode)
        assertEquals(8192, decoded.effectiveOutputTokenLimit())
    }

    /** A manual cap without a positive number is a validation failure. */
    @Test
    fun manualZeroIsRejectedByBundleValidation() {
        assertThrows(runtime.mobileagent.domain.AppException::class.java) {
            TransferCodec.validate(bundleFor(profile(OutputLimitMode.MANUAL, 0)), "test")
        }
    }

    /** AUTO carries 0 in the legacy column and must validate. */
    @Test
    fun automaticZeroPassesBundleValidation() {
        // Must not throw: an AUTO profile legitimately stores 0 and validates.
        TransferCodec.validate(bundleFor(profile(OutputLimitMode.AUTO, 0)), "test")
    }

    private fun bundleFor(model: ModelProfile) = TransferBundle(
        schemaVersion = SchemaVersion.CURRENT,
        exportedAt = "2026-09-16T00:00:00Z",
        agent = AgentTransfer(
            profile = AgentProfile(
                id = "agent.transfer",
                name = "Transfer",
                promptRevisionId = "prompt.transfer",
                chatProfileId = model.id,
                revision = 1,
            ),
            providers = listOf(
                ProviderTransfer(
                    id = model.providerId,
                    name = "Transfer",
                    apiFormat = "OPENAI_COMPATIBLE",
                    baseUrl = "https://transfer.example.invalid/v1",
                    revision = 1,
                ),
            ),
            models = listOf(ModelTransfer(model)),
        ),
    )
}