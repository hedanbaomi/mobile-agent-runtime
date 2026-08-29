// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelEndpointTest {
    @Test
    fun visionLegacyMapsToChatPlusImage() {
        val endpoint = ModelEndpoint.fromLegacy(ModelRole.VISION, setOf("stream"))
        assertEquals(setOf(ModelOperation.CHAT), endpoint.operations)
        assertTrue(InputModality.IMAGE in endpoint.inputModalities)
        val profile = ModelProfile(
            id = "m1",
            providerId = "p1",
            role = ModelRole.VISION,
            modelId = "vision",
            capabilities = setOf("stream", "image"),
            contextLimit = 8_000,
            outputLimit = 1_024,
            revision = 1,
        ).withEndpoint()
        assertTrue(profile.isChatEndpoint())
        assertTrue(profile.acceptsImages())
        assertEquals(ModelRole.VISION, profile.role)
    }

    @Test
    fun embeddingAndRerankStayDistinctFromChat() {
        val embedding = ModelEndpoint.fromLegacy(ModelRole.EMBEDDING, emptySet())
        val rerank = ModelEndpoint.fromLegacy(ModelRole.RERANKER, emptySet())
        assertEquals(setOf(ModelOperation.EMBEDDING), embedding.operations)
        assertEquals(setOf(ModelOperation.RERANK), rerank.operations)
        assertEquals(ModelRole.EMBEDDING, embedding.derivedRole())
        assertEquals(ModelRole.RERANKER, rerank.derivedRole())
    }
}
