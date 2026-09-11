// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.provider.openai.OpenAiCompatibleAdapter
import runtime.mobileagent.provider.openai.OpenAiResponsesAdapter

class RequestInputBudgetTest {

    @Test
    fun toolCallArgumentsJsonGrowthRaisesTheEstimate() {
        // The former estimator only summed message text and therefore ignored
        // ChatMessage.toolCalls.argumentsJson completely; growing only the
        // historical tool arguments must move the budget by the added bytes.
        val arguments = "{\"q\":\"" + "x".repeat(65_536) + "\"}"
        val small = RequestInputBudget.estimate(budgetRequest("""{"q":"x"}"""))
        val large = RequestInputBudget.estimate(budgetRequest(arguments))
        assertEquals(65_535L, large.units - small.units,
            "replacing one argument byte with 65536 bytes must add exactly 65535 units")
        assertEquals(small.imageCount, large.imageCount)
    }

    @Test
    fun unicodeBodiesUseConservativeUtf8ByteCounts() {
        assertEquals(0L, conservativeUtf8Units(""))
        assertEquals(3L, conservativeUtf8Units("abc"))
        assertEquals(2L, conservativeUtf8Units("\u00e9"))
        assertEquals(6L, conservativeUtf8Units("\u4e2d\u6587"))
        assertEquals(4L, conservativeUtf8Units("\uD83D\uDE00"))
        assertEquals(3L, conservativeUtf8Units("\uD83D"), "unpaired high surrogate must not understate bytes")
        assertEquals(3L, conservativeUtf8Units("\uDE00"), "unpaired low surrogate must not understate bytes")

        val ascii = RequestInputBudget.estimate(messageRequest("aaa"))
        val cjk = RequestInputBudget.estimate(messageRequest("\u4e2d\u4e2d\u4e2d"))
        val supplementary = RequestInputBudget.estimate(messageRequest("\uD83D\uDE00"))
        val twoAscii = RequestInputBudget.estimate(messageRequest("aa"))
        assertEquals(6L, cjk.units - ascii.units)
        assertEquals(2L, supplementary.units - twoAscii.units)
    }

    @Test
    fun rolesToolCallIdsAndToolSchemasAreMetered() {
        val base = RequestInputBudget.estimate(messageRequest("hi"))
        val longerRole = RequestInputBudget.estimate(
            messageRequest("hi").copy(messages = listOf(ChatMessage("developer", "hi"))),
        )
        assertEquals(("developer".length - "user".length).toLong(), longerRole.units - base.units)

        val toolResult = ModelRequest("estimate-model", listOf(ChatMessage(role = "tool", text = "result")))
        val toolCallId = "call-0001"
        val linkedResult = ModelRequest(
            "estimate-model",
            listOf(ChatMessage(role = "tool", toolCallId = toolCallId, text = "result")),
        )
        assertTrue(RequestInputBudget.estimate(linkedResult).units - RequestInputBudget.estimate(toolResult).units > toolCallId.length)

        val shortId = "c1"
        val longId = "c1-0000000"
        val shortName = "search"
        val longName = "search_extension"
        val shortArguments = "{}"
        val longArguments = """{"q":"x"}"""
        val shortCall = RequestInputBudget.estimate(toolCallRequest(shortId, shortName, shortArguments))
        val longCall = RequestInputBudget.estimate(toolCallRequest(longId, longName, longArguments))
        val expectedCallDelta = (longId.length - shortId.length) +
            (longName.length - shortName.length) +
            (longArguments.length - shortArguments.length)
        assertEquals(expectedCallDelta.toLong(), longCall.units - shortCall.units)

        val shortSchema = """{"type":"object"}"""
        val longSchema = """{"type":"object","properties":{"q":{"type":"string"}}}"""
        val schemaDelta = RequestInputBudget.estimate(schemaRequest(longSchema)).units -
            RequestInputBudget.estimate(schemaRequest(shortSchema)).units
        assertEquals((longSchema.length - shortSchema.length).toLong(), schemaDelta)
    }

    @Test
    fun imagesAddARepeatedFixedReservationAndCount() {
        val textOnly = RequestInputBudget.estimate(messageRequest("describe"))
        val images = listOf(
            InlineImage(mediaType = "image/png", base64 = "aGVsbG8="),
            InlineImage(mediaType = "image/jpeg", base64 = "aGVsbG8=", assetId = "asset-2"),
        )
        val withImages = RequestInputBudget.estimate(
            ModelRequest(
                "estimate-model",
                listOf(ChatMessage(role = "user", text = "describe", images = images)),
            ),
        )
        assertEquals(0, textOnly.imageCount)
        assertEquals(2, withImages.imageCount)
        assertEquals(
            2 * RequestInputBudget.IMAGE_UNITS_PER_IMAGE,
            withImages.units - textOnly.units,
        )
        assertTrue(withImages.basis.contains("4096"), "image reservation must be stated in the basis")
        assertTrue(withImages.basis.contains("not a tokenizer"), "the basis must not pretend to be a tokenizer")
    }

    @Test
    fun providerContinuationIsMeteredOnlyForTransportsThatSendIt() {
        val encrypted = "E".repeat(2_048)
        val without = continuationRequest(null)
        val with = continuationRequest(ProviderContinuationItem(itemId = "rs-1", encryptedContent = encrypted))
        val defaultWithout = RequestInputBudget.estimate(without)
        val defaultWith = RequestInputBudget.estimate(with)
        assertTrue(
            defaultWith.units - defaultWithout.units >= encrypted.length.toLong(),
            "a replayed continuation payload must be reserved",
        )
        val excluded = RequestInputBudget.estimate(with, includeProviderContinuation = false)
        assertEquals(defaultWithout.units, excluded.units)
        assertNotEquals(defaultWith.basis, excluded.basis)

        val compatible = OpenAiCompatibleAdapter(
            HttpClient(MockEngine { error("estimateInput must not touch the network") }),
            "https://example.invalid/v1",
        )
        val responses = OpenAiResponsesAdapter(
            HttpClient(MockEngine { error("estimateInput must not touch the network") }),
            "https://example.invalid/v1",
        )
        assertEquals(
            compatible.estimateInput(without).units,
            compatible.estimateInput(with).units,
            "Chat Completions drops provider continuation, so it must not be reserved",
        )
        assertEquals(defaultWith.units, responses.estimateInput(with).units)
        assertTrue(compatible.estimateInput(with).basis.contains("continuation", ignoreCase = true))
        assertTrue(responses.estimateInput(with).basis.contains("continuation", ignoreCase = true))
    }

    @Test
    fun estimateNeverEchoesSecretHeadersOrContinuationContent() {
        val secretValue = "sk-test-not-a-real-secret"
        val secretAlias = "provider-key-alias"
        val encrypted = "ENCRYPTED-CONTENT-MARKER"
        val request = ModelRequest(
            modelId = "estimate-model",
            messages = listOf(
                ChatMessage(
                    role = "assistant",
                    text = "answer",
                    toolCalls = listOf(AssistantToolCall("call-1", "search", """{"q":"x"}""")),
                    providerContinuationItems = listOf(
                        ProviderContinuationItem(itemId = "rs-1", encryptedContent = encrypted),
                    ),
                ),
            ),
            headers = mapOf(
                "Authorization" to RequestHeaderValue.Plain(secretValue),
                "X-Provider-Key" to RequestHeaderValue.SecretRef(secretAlias),
            ),
        )
        val estimate = RequestInputBudget.estimate(request)
        assertEquals(
            RequestInputBudget.estimate(request.copy(headers = emptyMap())),
            estimate,
            "authorization material must never change the input budget",
        )
        val rendered = estimate.toString()
        assertFalse(rendered.contains(secretValue))
        assertFalse(rendered.contains(secretAlias))
        assertFalse(rendered.contains(encrypted))
        assertFalse(rendered.contains("Authorization"))
        assertFalse(rendered.contains("X-Provider-Key"))
    }

    @Test
    fun saturatingArithmeticClampsInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, saturatingAddUnits(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, saturatingAddUnits(Long.MAX_VALUE - 1L, 2L))
        assertEquals(Long.MIN_VALUE, saturatingAddUnits(Long.MIN_VALUE, -1L))
        assertEquals(0L, saturatingMultiplyUnits(0L, Long.MAX_VALUE))
        assertEquals(0L, saturatingMultiplyUnits(Long.MAX_VALUE, 0L))
        assertEquals(Long.MAX_VALUE, saturatingMultiplyUnits(Long.MAX_VALUE / 2L + 1L, 2L))
        assertEquals(
            Long.MAX_VALUE,
            saturatingAddUnits(conservativeUtf8Units("x".repeat(1_000)), Long.MAX_VALUE),
        )
        assertTrue(RequestInputBudget.estimate(messageRequest("x".repeat(70_000))).units > 70_000L)
    }

    @Test
    fun modelAdapterDefaultEstimateDelegatesToTheSharedBudget() {
        val request = continuationRequest(ProviderContinuationItem(itemId = "rs-1", encryptedContent = "E".repeat(64)))
        assertEquals(RequestInputBudget.estimate(request), OfflineAdapterStub.estimateInput(request))
        // The default keeps the generic "continuation is reserved" basis; the
        // adapters override it for their real transport.
        assertEquals(INPUT_BUDGET_BASIS, OfflineAdapterStub.estimateInput(request).basis)
    }

    private fun messageRequest(text: String) =
        ModelRequest(modelId = "estimate-model", messages = listOf(ChatMessage(role = "user", text = text)))

    private fun budgetRequest(argumentsJson: String) = ModelRequest(
        modelId = "estimate-model",
        messages = listOf(
            ChatMessage(role = "system", text = "fixed instructions"),
            ChatMessage(role = "user", text = "fixed question"),
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(AssistantToolCall("call-1", "search", argumentsJson)),
            ),
            ChatMessage(role = "tool", toolCallId = "call-1", text = "fixed result"),
        ),
        tools = listOf(
            mapOf(
                "name" to "search",
                "description" to "fixed schema",
                "parameters" to """{"type":"object"}""",
            ),
        ),
    )

    private fun toolCallRequest(id: String, name: String, argumentsJson: String) = ModelRequest(
        modelId = "estimate-model",
        messages = listOf(
            ChatMessage(role = "assistant", toolCalls = listOf(AssistantToolCall(id, name, argumentsJson))),
        ),
    )

    private fun schemaRequest(parameters: String) = ModelRequest(
        modelId = "estimate-model",
        messages = listOf(ChatMessage(role = "user", text = "hi")),
        tools = listOf(mapOf("name" to "search", "description" to "fixed", "parameters" to parameters)),
    )

    private fun continuationRequest(continuation: ProviderContinuationItem?) = ModelRequest(
        modelId = "estimate-model",
        messages = listOf(
            ChatMessage(
                role = "assistant",
                text = "answer",
                toolCalls = listOf(AssistantToolCall("call-1", "search", """{"q":"x"}""")),
                providerContinuationItems = listOfNotNull(continuation),
            ),
        ),
    )

    private object OfflineAdapterStub : ModelAdapter {
        override suspend fun probe(profile: ModelProfile): CapabilityReport = error("not used")

        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = error("not used")

        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("not used")
    }
}
