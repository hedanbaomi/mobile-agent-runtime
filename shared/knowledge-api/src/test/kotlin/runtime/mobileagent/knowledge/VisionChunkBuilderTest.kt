// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `VISION_POST_CHUNK`: a long OCR + description + table + page context must
 * enter retrieval as several bounded, provenance-carrying fragments instead of
 * one unbounded chunk.
 *
 * The fixture reproduces the projected body length from the incident window
 * (22 rebuilt bodies, 21 over 1800 characters, longest 11301) without any
 * private document text.
 */
class VisionChunkBuilderTest {
    @Test
    fun longVisionBodyIsSplitIntoBoundedFragments() {
        val result = VisionSuccess(
            ocrText = (1..40).joinToString("\n") { "line-$it " + "o".repeat(120) },
            semanticDescription = "d".repeat(4200),
            tableMarkdown = buildString {
                append("| item | value |\n| --- | --- |\n")
                repeat(30) { append("| row$it | ${"v".repeat(80)} |\n") }
            },
            type = "page",
        )
        val parts = VisionChunkBuilder.build(
            result = result,
            page = 71,
            assetId = "asset-71",
            section = "chapter-3",
            surroundingText = "surrounding page 71 text ".repeat(120),
        )

        assertTrue(parts.size > 3, "expected several fragments, got ${parts.size}")
        assertTrue(parts.all { it.text.length <= VisionChunkBuilder.TARGET_CHARS }, "lengths=${parts.map { it.text.length }}")
        assertTrue(parts.all { it.text.isNotBlank() })
        // Every fragment stays traceable to the original page/asset/section.
        assertTrue(parts.all { it.page == 71 && it.assetIds == listOf("asset-71") })
        assertTrue(parts.all { it.span?.contains("page:71") == true && it.span?.contains("section:chapter-3") == true })
        // The components stay distinguishable instead of being fused.
        assertEquals(setOf("description", "ocr", "table", "context"), parts.map { it.part }.toSet())
    }

    @Test
    fun mixedPageExtractedTextIsPublishedAsItsOwnContextPart() {
        // A page that needs Vision is skipped by the normal text path, so its
        // extracted text must be published here or it is lost entirely.
        val native = "SYNTHETIC_NATIVE_ONLY_CLAUSE_71 " + "n".repeat(3000)
        val parts = VisionChunkBuilder.build(
            result = VisionSuccess(ocrText = "recognized text", semanticDescription = "a visual description"),
            page = 71,
            assetId = "asset-71",
            section = null,
            surroundingText = native,
        )

        val context = parts.filter { it.part == "context" }
        assertTrue(context.isNotEmpty(), "extracted page text must be published")
        assertEquals(native.replace(" ", "").length, context.sumOf { it.text.count { c -> c != ' ' } })
        // It stays a separate component: never merged into the OCR part, so a
        // fuzzy hit cannot be presented as recognition.
        assertTrue(parts.filter { it.part == "ocr" }.none { it.text.contains("SYNTHETIC_NATIVE_ONLY_CLAUSE_71") })
        assertTrue(parts.all { it.text.length <= VisionChunkBuilder.TARGET_CHARS })
        assertTrue(context.all { it.span?.contains("part:context") == true && it.span?.contains("page:71") == true })
    }

    @Test
    fun surroundingTextIsUsedAsFallbackWhenVisionReturnedNothing() {
        val parts = VisionChunkBuilder.build(
            result = VisionSuccess(ocrText = "", semanticDescription = ""),
            page = 9,
            assetId = "asset-9",
            section = "s9",
            surroundingText = "fallback page text ".repeat(200),
        )

        assertTrue(parts.isNotEmpty())
        assertTrue(parts.all { it.part == "context" })
        assertTrue(parts.all { it.text.length <= VisionChunkBuilder.TARGET_CHARS })
    }

    @Test
    fun emptyVisionResultStillLabelsThePage() {
        val parts = VisionChunkBuilder.build(VisionSuccess("", ""), page = 12, assetId = "a12", section = null)

        assertEquals(1, parts.size)
        assertEquals("label", parts.single().part)
        assertTrue(parts.single().text.contains("page 12"))
    }

    @Test
    fun tableFragmentsRepeatTheirMarkdownHeader() {
        val table = buildString {
            append("| item | value |\n| --- | --- |\n")
            repeat(60) { append("| r$it | ${"t".repeat(90)} |\n") }
        }
        val parts = VisionChunkBuilder.build(
            result = VisionSuccess(ocrText = "", semanticDescription = "", tableMarkdown = table),
            page = 4,
            assetId = "a4",
            section = null,
        )

        assertTrue(parts.size > 1)
        parts.forEach { part ->
            assertEquals("table", part.part)
            assertTrue(part.text.startsWith("| item | value |"), "header lost in ${part.text.take(40)}")
            assertTrue(part.text.length <= VisionChunkBuilder.TARGET_CHARS)
        }
    }

    @Test
    fun measuredIncidentShapeStaysBounded() {
        // Mirrors the review's own projection: 22 rebuilt bodies, 21 longer than
        // 1800 characters, longest 11301.  All must now split.
        val longest = "x".repeat(11301)
        val parts = VisionChunkBuilder.build(
            result = VisionSuccess(ocrText = longest, semanticDescription = "desc"),
            page = 71,
            assetId = "asset-71",
            section = null,
        )

        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.text.length <= VisionChunkBuilder.TARGET_CHARS }, "max=${parts.maxOf { it.text.length }}")
        assertEquals(longest.length, parts.filter { it.part == "ocr" }.sumOf { it.text.length })
    }
}


