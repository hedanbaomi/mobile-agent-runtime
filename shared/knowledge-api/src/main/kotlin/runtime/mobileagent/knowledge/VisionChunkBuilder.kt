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
 *
 * Every span is built through [encodeSourceSpan], so a `section` value that
 * itself contains `|`, `%` or structural-looking text such as
 * `photo|part:context|.png` stays inert data instead of becoming a readable
 * provenance field. See [SourceSpan] for the reading rules and the legacy
 * compatibility contract.
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
            parts += chunkPart(description, SourceSpan.PART_DESCRIPTION, page, assetId, section, targetChars)
        }
        if (ocr.isNotEmpty()) {
            // A row-aware split keeps Markdown tables and OCR line order intact.
            val lines = TextChunker.splitLines(ocr, targetChars)
            parts += lines.mapIndexed { index, text ->
                VisionTextPart(text, SourceSpan.PART_OCR, page, listOf(assetId),
                    span(section, page, SourceSpan.PART_OCR, index, lines.size))
            }
        }
        if (table.isNotEmpty()) {
            val lines = TextChunker.splitLines(table, targetChars)
            parts += lines.mapIndexed { index, text ->
                VisionTextPart(text, SourceSpan.PART_TABLE, page, listOf(assetId),
                    span(section, page, SourceSpan.PART_TABLE, index, lines.size))
            }
        }
        // Pages that reach Vision are exactly the pages the normal text path
        // skips (`page.text.isNotBlank() && !page.needsVision`), so extracted
        // page text on a mixed page would otherwise never be published.  Emit it
        // as its own `context` component (never merged into OCR, so a fuzzy hit
        // cannot masquerade as recognition) to keep provenance and coverage.
        if (context.isNotEmpty()) {
            val chunks = chunksOf(context, targetChars)
            parts += chunks.mapIndexed { index, text ->
                // Context text is page-level provenance: it carries no asset and
                // an explicit PAGE_CONTEXT association so no reader can treat it
                // as crop evidence.
                VisionTextPart(text, SourceSpan.PART_CONTEXT, page, emptyList(),
                    span(null, page, SourceSpan.PART_CONTEXT, index, chunks.size,
                        association = SourceSpan.ASSOCIATION_PAGE_CONTEXT))
            }
        }
        if (parts.isEmpty()) {
            parts += VisionTextPart("Visual evidence page ${page ?: "?"} (no text recognized)", SourceSpan.PART_LABEL,
                page, listOf(assetId), span(section, page, SourceSpan.PART_LABEL, 0, 1))
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
        val chunks = chunksOf(text, targetChars)
        return chunks.mapIndexed { index, chunk ->
            VisionTextPart(chunk, part, page, listOf(assetId), span(section, page, part, index, chunks.size))
        }
    }

    private fun chunksOf(text: String, targetChars: Int): List<String> = TextChunker.chunk(text, targetChars)

    private fun span(
        section: String?,
        page: Int?,
        part: String,
        index: Int,
        total: Int,
        association: String? = null,
    ): String = encodeSourceSpan(
        page = page,
        section = section,
        part = part,
        segmentIndex = index + 1,
        segmentTotal = total,
        association = association,
    )
}