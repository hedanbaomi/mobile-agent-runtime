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

    @Test
    fun dropsHeadingOnlyHitWhenTheSameDocumentHasABodyHit() {
        val heading = SearchHit("c-head", "doc-1", "Source: working with the void", 0.9)
        val body = SearchHit(
            "c-body",
            "doc-1",
            "The void is approached through practice rather than description. ".repeat(3),
            0.8,
        )
        val other = SearchHit("c-other", "doc-2", "short", 0.7)
        val filtered = ReciprocalRankFusion.preferClaimSupporting(listOf(heading, body, other))
        assertEquals(listOf("c-body", "c-other"), filtered.map { it.chunkId })
    }

    @Test
    fun keepsShortFieldValuesWhenTheSameDocumentHasABodyHit() {
        val body = SearchHit(
            "c-body",
            "doc-1",
            "本节说明配置文件的读取步骤、加载顺序以及页面的一般操作说明；它不包含具体并发数值。".repeat(3),
            0.3,
        )
        val fact = SearchHit("exact-fact", "doc-1", "最大并发数：8", 1.0)
        val assignment = SearchHit("config", "doc-1", "MAX_RETRIES=3", 0.9)
        val bullet = SearchHit("bullet", "doc-1", "- timeout 30s", 0.8)
        val filtered = ReciprocalRankFusion.preferClaimSupporting(listOf(fact, assignment, bullet, body))
        assertEquals(listOf("exact-fact", "config", "bullet", "c-body"), filtered.map { it.chunkId })
    }

    @Test
    fun stillDropsTitleLikeHeadingsWithoutTreatingThemAsFacts() {
        val heading = SearchHit("heading", "doc-1", "来源：配置说明", 0.9)
        val body = SearchHit(
            "c-body",
            "doc-1",
            "本节说明配置文件的读取步骤、加载顺序以及页面的一般操作说明；它不包含具体并发数值。".repeat(3),
            0.3,
        )
        val filtered = ReciprocalRankFusion.preferClaimSupporting(listOf(heading, body))
        assertEquals(listOf("c-body"), filtered.map { it.chunkId })
    }
}

class PublishedCitationVersionTest {
    @Test
    fun visualGapVersionsArePublishedForCitation() {
        assertTrue(isPublishedCitationVersion("READY"))
        assertTrue(isPublishedCitationVersion("READY_WITH_VISUAL_GAPS"))
        assertFalse(isPublishedCitationVersion("WAITING_FOR_VISION_MODEL"))
        assertFalse(isPublishedCitationVersion("INDEXING"))
    }
}
