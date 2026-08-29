// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CjkLexicalTest {
    @Test
    fun emitsUnigramsAndBigrams() {
        val indexed = CjkLexical.indexText("张伟")
        assertTrue(indexed.contains("张"))
        assertTrue(indexed.contains("伟"))
        assertTrue(indexed.contains("张伟"))
    }
}

class ZipSafetyTest {
    @Test
    fun rejectsParentPathEntries() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../x.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val result = ZipSafety.inspect(out.toByteArray())
        assertFalse(result.ok)
        assertTrue(result.reason.contains("path"))
    }
}

class CitationMapTest {
    @Test
    fun unknownIdIsInvalid() {
        val hits = listOf(SearchHit("c1", "d1", "text", 1.0, knowledgeBaseId = "kb"))
        val citations = CitationMap.bind("run", hits)
        assertEquals("c1", CitationMap.resolve(citations, "0")?.chunkId)
        assertEquals(null, CitationMap.resolve(citations, "9"))
    }
}

class VisionBindingTest {
    @Test
    fun fingerprintKeepsEndpointPathCaseAndBothRevisions() {
        val binding = VisionBinding(
            providerId = "provider",
            modelId = "vision-model",
            endpoint = "HTTPS://Host.example/V1/CaseSensitive///",
            revision = 7,
            providerRevision = 2,
            modelRevision = 9,
        )
        assertTrue(binding.fingerprint.contains("HTTPS://Host.example/V1/CaseSensitive"))
        assertFalse(binding.fingerprint.contains("casesensitive"))
        assertTrue(binding.fingerprint.endsWith("provider:2|model:9"))
        assertFalse(binding.fingerprint == binding.copy(providerRevision = 3).fingerprint)
        assertFalse(binding.fingerprint == binding.copy(modelRevision = 10).fingerprint)
    }

    @Test
    fun legacyRevisionConstructorBindsBothRevisionFields() {
        val legacy = VisionBinding("provider", "model", "https://host/v1", 4)
        val explicit = VisionBinding("provider", "model", "https://host/v1", 4, 4, 4)
        assertEquals(explicit.fingerprint, legacy.fingerprint)
    }
}

class ReciprocalRankFusionTest {
    @Test
    fun prefersItemsHighInBothRankings() {
        val a = listOf(SearchHit("x", "d", "x", 1.0), SearchHit("y", "d", "y", 0.5))
        val b = listOf(SearchHit("y", "d", "y", 1.0), SearchHit("x", "d", "x", 0.5))
        val merged = ReciprocalRankFusion.merge(listOf(a, b))
        assertEquals(setOf("x", "y"), merged.map { it.chunkId }.toSet())
        assertEquals(2, merged.size)
    }
}
