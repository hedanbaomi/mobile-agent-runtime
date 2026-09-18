// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ContextLimitSource
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.provider.openai.OpenAiCompatibleAdapter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine

class ContextWindowProducerTest {
    private val now = Instant.parse("2026-09-18T00:00:00Z")
    private val producer = ContextWindowProducer { now }
    private val target = "provider|https://example.test/v1|model"

    @Test fun userDeclarationAndUnknownRemainDistinct() {
        assertEquals(ContextLimitSource.USER_DECLARED, producer.userDeclared(target, 8192).source)
        assertNull(producer.userDeclared(target, null).value)
        assertEquals(ContextLimitSource.UNKNOWN, producer.userDeclared(target, null).source)
        assertThrows(IllegalArgumentException::class.java) { producer.userDeclared(target, 0) }
    }

    @Test fun acceptsOnlyDocumentedMetadataForTheCurrentTarget() = runBlocking {
        val known = ContextWindowMetadata(8192, target, now.minusSeconds(1).toString(), "synthetic-adapter:context_window")
        val result = producer.metadata(target) { known }
        assertEquals(8192, result.value)
        assertEquals(ContextLimitSource.PROVIDER_METADATA, result.source)
        assertEquals(known.checkedAt, result.checkedAt)
        assertEquals(known.evidence, result.evidence)
        assertNull(producer.metadata(target) { known.copy(evidence = "") }.value)
        assertNull(producer.metadata(target) { known.copy(value = -1) }.value)
        assertNull(producer.metadata(target) { null }.value)
    }

    @Test fun changedExpiredOrFutureMetadataIsStaleAndNotEffective() = runBlocking {
        val known = ContextWindowMetadata(8192, target, now.minusSeconds(1).toString(), "synthetic:field")
        listOf(known.copy(target = "other"), known.copy(expiresAt = now.toString()),
            known.copy(checkedAt = now.plusSeconds(1).toString())).forEach { stale ->
            val result = producer.metadata(target) { stale }
            assertTrue(result.stale)
            assertNull(result.value)
            assertEquals(ContextLimitSource.UNKNOWN, result.source)
        }
    }

    @Test fun unavailableSourceRemainsUnknown() = runBlocking {
        val result = producer.metadata(target) { throw IllegalStateException("unavailable") }
        assertNull(result.value)
        assertEquals(ContextLimitSource.UNKNOWN, result.source)
    }

    @Test fun ordinaryCompatibleAdapterMakesNoProbeAndClaimsNoWindow() = runBlocking {
        var requests = 0
        HttpClient(MockEngine { requests++; error("Metadata discovery must not call inference or assume /models") }).use { http ->
            val model = ModelProfile("profile", "provider", ModelRole.VISION, "model-with-large-window-in-name",
                emptySet(), contextLimit = 16384, outputLimit = 4096, revision = 1)
            val result = producer.metadata(target, model, OpenAiCompatibleAdapter(http, "https://example.test/v1"))
            assertNull(result.value)
            assertEquals(ContextLimitSource.UNKNOWN, result.source)
            assertEquals(0, requests)
        }
    }

    @Test fun expiringMetadataCannotBecomeAnIndefinitePersistedCapability() = runBlocking {
        val result = producer.metadata(target) {
            ContextWindowMetadata(8192, target, now.toString(), "synthetic:field", now.plusSeconds(60).toString())
        }
        assertEquals(8192, result.value)
        val model = ModelProfile("profile", "provider", ModelRole.VISION, "model", emptySet(),
            contextLimit = 0, outputLimit = 0, revision = 1)
        val persisted = result.applyTo(model)
        assertNull(persisted.contextWindowValue)
        assertEquals(ContextLimitSource.UNKNOWN, persisted.contextWindowSource)
    }
}
