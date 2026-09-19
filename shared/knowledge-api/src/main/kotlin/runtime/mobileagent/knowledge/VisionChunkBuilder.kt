// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/**
 * One retrieval fragment produced from a page-level Vision result.
 *
 * [part] names the source component (`description`, `ocr`, `table`, `context`)
 * so citations stay traceable and a table row is never presented as a second
 * independent copy of the OCR text.
 */
data class VisionTextPart(
    val text: String,
    val part: String,
    val page: Int?,
    val assetIds: List<String>,
    val span: String?,
)

/**
 * Normalizes a Vision success into bounded, provenance-carrying fragments.
 *
 * The old import path concatenated `semanticDescription + ocrText +
 * tableMarkdown + surroundingText` into a single chunk.  One long page could
 * therefore exceed the embedding/retrieval granularity even though normal text
 * pages were chunked.  This object routes every component through
 * [TextChunker], keeps the original page/asset/section span on each fragment,
 * and emits the surrounding page text at most once so overlap is not counted
 * as independent evidence.
 */
object VisionChunkBuilder {
    const val TARGET_CHARS = 1800

    /**
     * @param surroundingText already-extracted page text, if any.
     */
    fun build(
        result: VisionSuccess,
        page: Int?,
        assetId: String,
        section: String?,
        surroundingText: String = "",
        targetChars: Int = TARGET_CHARS,
    ): List<VisionTextPart> {
        val parts = mutableListOf<VisionTextPart>()
        val description = result.semanticDescription.trim()
        val ocr = result.ocrText.trim()
        val table = result.tableMarkdown.trim()
        val context = surroundingText.trim()

        if (description.isNotEmpty()) {
            parts += chunkPart(description, "description", page, assetId, section, targetChars)
        }
        if (ocr.isNotEmpty()) {
            // A row-aware split keeps Markdown tables and OCR line order intact.
            val lines = TextChunker.splitLines(ocr, targetChars)
            parts += lines.mapIndexed { index, text ->
                VisionTextPart(text, "ocr", page, listOf(assetId), span(section, page, "ocr", index, lines.size))
            }
        }
        if (table.isNotEmpty()) {
            val lines = TextChunker.splitLines(table, targetChars)
            parts += lines.mapIndexed { index, text ->
                VisionTextPart(text, "table", page, listOf(assetId), span(section, page, "table", index, lines.size))
            }
        }
        // Pages that reach Vision are exactly the pages the normal text path
        // skips (`page.text.isNotBlank() && !page.needsVision`), so extracted
        // page text on a mixed page would otherwise never be published.  Emit it
        // as its own `context` component (never merged into OCR, so a fuzzy hit
        // cannot masquerade as recognition) to keep provenance and coverage.
        if (context.isNotEmpty()) {
            parts += chunkPart(context, "context", page, assetId, null, targetChars)
                .map { it.copy(assetIds = emptyList(), span = it.span + "|association:PAGE_CONTEXT") }
        }
        if (parts.isEmpty()) {
            parts += VisionTextPart("Visual evidence page ${page ?: "?"} (no text recognized)", "label", page, listOf(assetId), span(section, page, "label", 0, 1))
        }
        return parts
    }

    private fun chunkPart(
        text: String,
        part: String,
        page: Int?,
        assetId: String,
        section: String?,
        targetChars: Int,
    ): List<VisionTextPart> {
        val chunks = TextChunker.chunk(text, targetChars)
        return chunks.mapIndexed { index, chunk ->
            VisionTextPart(chunk, part, page, listOf(assetId), span(section, page, part, index, chunks.size))
        }
    }

    private fun span(section: String?, page: Int?, part: String, index: Int, total: Int): String {
        val pageLabel = page?.let { "page:$it" } ?: "page:?"
        val sectionLabel = section?.takeIf { it.isNotBlank() }?.let { "section:$it" }
        val parts = listOfNotNull(pageLabel, sectionLabel, "part:$part", if (total > 1) "segment:${index + 1}/$total" else null)
        return parts.joinToString("|")
    }
}
