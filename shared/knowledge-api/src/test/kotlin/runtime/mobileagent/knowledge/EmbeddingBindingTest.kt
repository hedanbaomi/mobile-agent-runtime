// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmbeddingBindingTest {
    @Test
    fun bindingSpaceRetainsEndpointPathCaseAndBothRevisions() {
        val binding = ApiEmbeddingBinding(
            providerId = "Provider-A",
            endpoint = "https://Api.example.test/V1/Embeddings/",
            providerRevision = 7,
            modelId = "Model-A",
            modelRevision = 11,
            dimension = 384,
            dataScope = "document text; retrieval only",
        )

        assertTrue(binding.spaceId.contains("https://Api.example.test/V1/Embeddings"))
        assertTrue(binding.spaceId.contains("providerRevision=7"))
        assertTrue(binding.spaceId.contains("modelRevision=11"))
        assertTrue(binding.spaceId.contains("dimension=384"))
        assertTrue(binding.spaceId.contains("dataScope=document text; retrieval only"))
        assertTrue(binding.spaceId.contains("modelProfileId=Model-A"))
        assertTrue(!binding.spaceId.endsWith('/'))
        assertEquals("Model-A", ApiEmbeddingBinding.parseSpaceId(binding.spaceId)?.modelProfileId)
    }

    @Test
    fun changingAnyBindingFieldChangesTheSpaceIdentity() {
        val base = ApiEmbeddingBinding(
            providerId = "provider",
            endpoint = "https://example.test/v1",
            providerRevision = 1,
            modelId = "model",
            modelRevision = 1,
            dimension = 8,
            dataScope = "text",
        )
        assertNotEquals(base.spaceId, base.copy(providerRevision = 2).spaceId)
        assertNotEquals(base.spaceId, base.copy(modelRevision = 2).spaceId)
        assertNotEquals(base.spaceId, base.copy(modelId = "model-2").spaceId)
        assertNotEquals(base.spaceId, base.copy(dimension = 16).spaceId)
        assertNotEquals(base.spaceId, base.copy(dataScope = "text plus title").spaceId)
        assertNotEquals(base.spaceId, base.copy(endpoint = "https://example.test/v2").spaceId)
        assertNotEquals(base.spaceId, base.copy(modelProfileId = "profile-2").spaceId)
    }

    @Test
    fun parseSpaceIdStrictlyRejectsLegacyOrNonCanonicalValues() {
        val binding = ApiEmbeddingBinding(
            providerId = "provider|one",
            endpoint = "https://example.test/V1?x=50%25",
            providerRevision = 3,
            modelId = "model|one",
            modelRevision = 4,
            dimension = 8,
            dataScope = "text|title",
            modelProfileId = "profile|one",
        )

        val parsed = ApiEmbeddingBinding.parseSpaceId(binding.spaceId)
        assertEquals(binding.spaceId, parsed?.spaceId)
        assertEquals(binding.providerId, parsed?.providerId)
        assertEquals(binding.endpoint, parsed?.endpoint)
        assertEquals(binding.modelId, parsed?.modelId)
        assertEquals(binding.modelProfileId, parsed?.modelProfileId)
        assertNull(ApiEmbeddingBinding.parseSpaceId("api-space-v1"))
        assertNull(ApiEmbeddingBinding.parseSpaceId(binding.spaceId.replace("%7C", "%7c")))
        assertNull(ApiEmbeddingBinding.parseSpaceId(binding.spaceId.replace("dimension=8", "dimension=08")))
    }
}
