// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EMU-019/033 regression: enriched knowledge-search output must keep the
 * producer's warning (and every other sibling field) while hits are re-bound,
 * and must not embed per-hit citation object arrays that have no reader.
 */
class KnowledgeSearchOutputRebuildTest {

    @Test
    fun rebuildKeepsProducerWarningAndSiblings() {
        val root = buildJsonObject {
            put("hits", buildJsonArray { })
            put("warning", JsonPrimitive("knowledge base unavailable"))
            put("count", JsonPrimitive(0))
        }
        val verified = buildJsonArray {
            add(buildJsonObject { put("citationId", JsonPrimitive("cit-1")) })
        }
        val rebuilt = rebuildKnowledgeSearchRoot(root, verified)
        assertEquals("knowledge base unavailable", rebuilt["warning"]!!.jsonPrimitive.content)
        assertEquals(0, rebuilt["count"]!!.jsonPrimitive.content.toInt())
        assertTrue(rebuilt["hits"]!! === verified || rebuilt["hits"]!!.toString().contains("cit-1"))
    }

    @Test
    fun warningsMergeInsteadOfOverwrite() {
        assertEquals("a; b", mergeWarnings("a", "b"))
        assertEquals("b", mergeWarnings(null, "b"))
        assertEquals("b", mergeWarnings("", "b"))
    }

    @Test
    fun enrichedHitsCarryCitationIdOnly() {
        // The per-hit contract supplies exactly one citationId reference; the
        // removed per-hit "citations" array must stay absent from rebuilt hits.
        val rebuilt = rebuildKnowledgeSearchRoot(
            buildJsonObject { put("hits", buildJsonArray { }) },
            buildJsonArray {
                add(buildJsonObject {
                    put("citationId", JsonPrimitive("cit-9"))
                    put("text", JsonPrimitive("chunk text"))
                })
            },
        )
        val hit = rebuilt["hits"]!!.let { it as kotlinx.serialization.json.JsonArray }[0]
            .let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("cit-9", hit["citationId"]!!.jsonPrimitive.content)
        assertFalse("citations" in hit)
    }
}
