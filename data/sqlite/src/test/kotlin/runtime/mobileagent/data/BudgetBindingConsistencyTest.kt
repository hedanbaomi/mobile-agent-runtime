// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ContextLimitMode
import runtime.mobileagent.domain.ContextLimitSource
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.contextWindowTargetKey

/**
 * The same persisted row must produce the same budget configuration through the
 * direct reader and through the JOIN used by chatBinding/visionBinding, and the
 * frozen context target must be built from the same revision everywhere.
 */
class BudgetBindingConsistencyTest {
    private val endpoint = "https://binding.example.invalid/v1"

    private fun provider(revision: Int = 5) = ProviderProfile(
        id = "provider.binding", name = "Binding", apiFormat = ApiFormat.OPENAI_COMPATIBLE,
        baseUrl = endpoint, revision = revision,
    )

    private fun model(
        modelRevision: Int,
        outputMode: OutputLimitMode,
        contextMode: ContextLimitMode,
        declaredWindow: Int?,
        recordedFor: String?,
    ) = ModelProfile(
        id = "model.binding", providerId = "provider.binding", role = ModelRole.CHAT, modelId = "binding-chat",
        capabilities = setOf("stream", "image"), contextLimit = 32_768, outputLimit = 8192, revision = modelRevision,
        outputLimitMode = outputMode, contextLimitMode = contextMode,
        contextWindowValue = declaredWindow,
        contextWindowSource = if (declaredWindow != null) ContextLimitSource.USER_DECLARED else ContextLimitSource.UNKNOWN,
        contextWindowTarget = recordedFor,
    )

    @Test
    fun directReaderAndJoinReaderAgreeOnModesAndWindow() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(provider())
            val target = contextWindowTargetKey("provider.binding", 1, endpoint, "binding-chat")
            profiles.createModel(model(1, OutputLimitMode.AUTO, ContextLimitMode.AUTO, 131_072, target))

            val direct = profiles.getModel("model.binding")!!
            val listed = profiles.listModels().single { it.id == "model.binding" }
            val viaChat = profiles.chatBinding()!!.second
            val viaVision = profiles.visionBinding()!!.second

            listOf(direct, listed, viaChat, viaVision).forEach { candidate ->
                assertEquals(OutputLimitMode.AUTO, candidate.outputLimitMode, candidate.toString())
                assertEquals(ContextLimitMode.AUTO, candidate.contextLimitMode, candidate.toString())
                assertEquals(131_072, candidate.contextWindowValue, candidate.toString())
                assertEquals(131_072, candidate.resolvedContextWindow(target), candidate.toString())
                assertNull(candidate.effectiveOutputTokenLimit(), candidate.toString())
            }
        }
    }

    @Test
    fun providerRevisionDoesNotBreakAModelRevisionTarget() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            // Provider revision (7) deliberately differs from the model revision (3).
            profiles.createProvider(provider(revision = 7))
            val target = contextWindowTargetKey("provider.binding", 3, endpoint, "binding-chat")
            profiles.createModel(model(3, OutputLimitMode.MANUAL, ContextLimitMode.AUTO, 65_536, target))

            val viaChat = profiles.chatBinding()!!.second
            // The consumer builds the key from the model revision, exactly like the producer.
            assertEquals(65_536, viaChat.resolvedContextWindow(contextWindowTargetKey("provider.binding", viaChat.revision, endpoint, viaChat.modelId)))
            assertTrue(viaChat.contextWindowIsStale(contextWindowTargetKey("provider.binding", 4, endpoint, "binding-chat")))
        }
    }

    @Test
    fun unknownAutoWindowStaysUnknownThroughEveryReader() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(provider())
            profiles.createModel(model(1, OutputLimitMode.AUTO, ContextLimitMode.AUTO, null, null))
            val target = contextWindowTargetKey("provider.binding", 1, endpoint, "binding-chat")

            listOf(profiles.getModel("model.binding")!!, profiles.chatBinding()!!.second).forEach { candidate ->
                assertEquals(ContextLimitSource.UNKNOWN, candidate.contextWindowSource, candidate.toString())
                assertNull(candidate.resolvedContextWindow(target), candidate.toString())
            }
        }
    }

    @Test
    fun legacyRowWithoutTheNewColumnsReadsAsManualEverywhere() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val profiles = ProfileRepository(db)
            profiles.createProvider(provider())
            profiles.createModel(
                ModelProfile(
                    id = "model.binding", providerId = "provider.binding", role = ModelRole.CHAT, modelId = "binding-chat",
                    capabilities = setOf("stream"), contextLimit = 32_768, outputLimit = 4096, revision = 1,
                ),
            )
            listOf(profiles.getModel("model.binding")!!, profiles.chatBinding()!!.second).forEach { candidate ->
                assertEquals(OutputLimitMode.MANUAL, candidate.outputLimitMode, candidate.toString())
                assertEquals(ContextLimitMode.MANUAL, candidate.contextLimitMode, candidate.toString())
                assertEquals(4096, candidate.effectiveOutputTokenLimit(), candidate.toString())
                assertEquals(32_768, candidate.resolvedContextWindow("irrelevant"), candidate.toString())
            }
        }
    }
}